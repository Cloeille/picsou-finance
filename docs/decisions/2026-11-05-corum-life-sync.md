# ADR: CORUM Life contracts sync as one assurance-vie account each, from a single portfolio read

> Date: 2026-11-05
> Status: ✅ Active
> Supersedes: point 7 of [the CORUM sidecar ADR](2026-09-26-corum-scpi-sidecar.md)

## Context

The CORUM sidecar read the real-estate contract only (point 7 of the sidecar ADR): CORUM Life,
PER and capitalisation were left out because they are insurance, not shares. A holder whose login
sees a CORUM Life contract and no real-estate contract could not use the connector at all. The
sidecar answered `PORTFOLIO_INCOMPLETE`, which reads like a CORUM outage rather than "this login
holds something Picsou does not read yet".

The live client space answers the missing read. `GET /api/contract/life/{code}` (200, verified
against a real contract) returns the whole contract in one call: management pockets with a
quantity, a unit price and an amount per line, a NAV date per line, and an asset-class view that
names the euro fund. Four facts from that payload shape this ADR:

1. **A fund sits in several pockets.** The same code (`CC`, `XL`, `EU`) appears under "Gestion
   libre", "Corum Life Horizon" and the euro-fund pocket at once, each time with its own
   quantity and amount.
2. **The euro fund is marked only in the asset-class view.** Section `euroLife` of
   `chartSections` is the only place that says which code is the euro fund.
3. **The euro fund line has no usable unit.** Its integer `quantity` and `unitPrice` carry no
   proven meaning, and it has no NAV date.
4. **Every amount is a JSON number.** Nothing in the payload is ever a string.

The euro fund is invested money. The user decided that on 2026-11-05, against the first plan,
which parked it as cash.

## Decision

1. **One `/portfolio` read.** The sidecar gains `POST /portfolio`, which opens one browser
   session and returns `{ realEstate, life[] }`. `realEstate` is `null`, not absent, when the
   login holds no SCPI contract. `CorumPort.fetchSnapshot` becomes `fetchPortfolio`. The old
   `/positions` stays as a SCPI-only wrapper for one release so a backend not yet on
   `/portfolio` keeps syncing. PER and capitalisation are still not read.
2. **One `ASSURANCE_VIE` account per Life contract**, keyed by `externalAccountId =
   corum_life_<contractCode>`. Its balance is the contract's `valuationAmount`, its cash is 0, and
   its units are `account_holding` rows valued by CORUM (`providerValueEur`), because no market
   feed quotes a CORUM unit. `AccountService.isProviderValued` treats the synced CORUM Life
   accounts (non-manual, `externalAccountId` prefixed `corum_life_`) as provider-valued, whatever
   their editable provider string says; a manual account whose free-text provider is "CORUM"
   keeps the usual valuation.
3. **Aggregate per fund code across pockets.** Quantity, amount and net invested are summed, so
   one fund is one holding. Pockets may quote one code at different prices (one at an older
   NAV); the holding's unit price is then `Σamount / Σquantity` (8 decimals), and the first
   pocket's price when no pocket holds a unit.
4. **The euro fund is a holding**, not cash. Codes listed under `euroLife` are written with
   `quantity = valueEur`, `unitPrice = 1` and `euroFund: true`. The payload's own quantity and
   price are ignored. A euro fund line that carries a NAV date is refused.
5. **Tolerance.** Every reconciliation uses `max(0.05, 0.01 × lines)` euros, the rule BoursoBank
   already uses, moved into `SyncValues.linesClose`. The sidecar checks lines against each
   pocket, pockets against `contractDetail.valuationAmount`, and the aggregated holdings against
   the same total. The backend repeats the last check on the wire shape, using the raw line
   count the sidecar sends (`valuationLineCount`). The `/contract/active` total is rounded and
   cached on CORUM's side, so a mismatch with it is logged, never a gate.
6. **All-or-nothing.** `CorumSyncService.commitPortfolio` validates both parts before writing
   either, then writes SCPI funds and Life contracts in one transaction under the member lock.
   A refused Life contract leaves the SCPI accounts as they were, and the reverse. A login with
   neither part is refused as `PORTFOLIO_INCOMPLETE`.
7. **Guarded adoption.** A sync adopts a manual account only when all of these hold: type
   `ASSURANCE_VIE`, manual, no `externalAccountId`, name equal to "CORUM Life" after
   normalization **or** provider `CORUM` (case-insensitive) whatever the name, **no holdings**, exactly one such candidate, and exactly one Life contract in
   the snapshot. When such manual accounts exist but none qualifies, the contract is not written
   (`AMBIGUOUS`, as for an SCPI fund) and the status reports it (`lifeAmbiguousCount`). With no
   such account it creates one, named "CORUM Life" or "CORUM Life <code>" when there are several
   contracts. The name is set on creation only and a rename survives every
   sync. A soft-deleted account is never brought back.
8. **Tickers are `CORUM-LIFE-<CODE>`**, with a label from a fixed map in
   `life_parser.LIFE_FUND_NAMES` (confirmed by a CORUM Life holder) and `CORUM <code>` for an
   unknown code.
9. The positions timeout goes from 90 s to 120 s: the read now covers every contract.

Added after the first live sync (same day), when the UI showed a gain against the lines' summed
costs that the contract did not have:

10. **Contract-level cost basis (D11).** A line's `netInvestedAmountCumulated` leaves out money
    switched between funds: after a switch out of the euro fund into a bond fund, the bond line
    shows a gain it never made and the lines' costs no longer add up to the contract's. The
    sidecar now sends `contractDetail.netInvestedAmount` as the contract's `netInvestedEur`,
    after checking `valuationAmount − netInvestedAmount == gainOrLoss` within 0.05
    (`UPSTREAM_FORMAT_CHANGED` otherwise). `CorumLifeWriter` stores it as
    `account.provider_invested_eur` and uses it for the snapshot's invested amount. A line keeps
    its own cost only when every line has one and they add up to the contract's
    (`SyncValues.linesClose`); otherwise no line carries one. `AccountService.valuation` pairs
    `currentBalance` with it on a provider-valued account, and the intraday chart takes that
    pair. The same read also stores what was paid in, the savings plan (as a
    `RecurringCadence`; an unknown word or a zero plan is left out, never refused) and the
    opening date, only while the account has none.
11. **Provider tier on the holding (D12).** Each holding carries its `chartSections` section name
    (`assetClass`); a code under two sections is refused. The writer maps `realEstate` to
    `account_holding.provider_wealth_tier = REAL_ESTATE`. The wealth pyramid reads member
    override ▸ provider tier ▸ CoinGecko ▸ account tier; the sync never writes the member's
    override table. The diversification breakdown leaves out a line whose override or provider
    tier is not `EQUITY`.
12. **Bonds and the euro fund stay `EQUITY` (D13).** User decision: `bondFund`, `euroLife` and any
    other section map to no provider tier, so they keep the account's tier. No new tier.

## Alternatives considered

### Keep per-pocket keys

One holding per (code, pocket), for example `CORUM-LIFE-XL-LIBRE`.

- **Pros**: mirrors the payload one to one and keeps the per-pocket split.
- **Cons**: the same fund shows as several lines in holdings, in the classification editor and
  in the diversification breakdown, and the pocket names are French free text that CORUM can
  rename. A rename would orphan a member's classification override.

### Euro fund as cash

`cashBalance = valueEur`, no holding. This was the first plan.

- **Pros**: matches how some wrappers show "fonds euros" and needs no `euroFund` flag.
- **Cons**: the euro fund is invested, long-horizon money, not spendable cash. As cash it
  would drop out of the holdings list. Rejected by the user.

### Commit SCPI and Life separately

Two transactions, or two reads.

- **Pros**: a bad Life contract would not block the SCPI part, and the reverse.
- **Cons**: the net-worth series would take a step on a partly-applied run, and the two parts
  would describe different moments. The connector already fails closed (point 6 of the sidecar
  ADR); a refused sync costs a retry, a half-applied one corrupts a series that cannot be
  rebuilt.

### Bare tickers

Use the fund code as the ticker (`XL`, `EU`, `CC`).

- **Pros**: shorter.
- **Cons**: a bare code can collide with a real symbol, and `CC` or `EU` could be resolved by a
  price feed or logo lookup against an unrelated instrument. The `CORUM-LIFE-` prefix keeps the
  key unambiguous and still unique per member.

### Rebuild the cost from the lines and their reallocations

Add each line's `reallocationAmount` back to its `netInvestedAmountCumulated`.

- **Pros**: would keep a per-line gain.
- **Cons**: verified against the live contract, the sum still misses the contract's net invested,
  positive reallocations included. A rebuilt figure that does not match CORUM's own is a guess.

### A tier for bonds and the euro fund

Map `bondFund` and `euroLife` to a new tier, or to `SAFETY_NET`.

- **Pros**: closer to what the money is.
- **Cons**: rejected by the user. The euro fund is long-horizon money, not a cushion, and a new
  tier changes the allocation targets of every member.

## Reasoning

A Life contract is a wrapper around units CORUM itself prices, so the right model is the one the
other insurance and broker connectors use: one account, provider-valued holdings. Aggregating by
code gives the user one line per fund, which is what the holdings view, the classification
editor and the diversification breakdown all key on.

The strictness mirrors the SCPI parser. A string where a number belongs, a negative amount or a
pocket that does not add up means the format moved, and the whole contract is refused. Two prices
for one fund are not in that list: pockets can be valued at different NAV dates, and the amounts
and units, which are what the balance and cost rest on, still add up. The balance of a long-horizon account is worse off with a wrong point than
with a missing one.

Adoption is narrower than the SCPI rule on purpose. The SCPI sync only overwrites a count and a
price the user typed; a Life sync replaces holdings, and a hand-typed holding is the one write
it cannot undo. A manual account it cannot adopt is not a reason to create a second one either:
both would count in the net worth, so the contract waits until the user resolves it. The
provider match was added after review: an existing user who tracked the contract by hand under
provider `CORUM` but another name would otherwise end with a second, `CREATED` account; matching
on the provider too makes that case `LINKED` or `AMBIGUOUS`, never a duplicate.

## Trade-offs accepted

- The per-pocket split is lost. The account shows one line per fund, not the pocket it sits in.
- The euro fund as 1-euro units is a modelling choice: its "quantity" is a euro amount, and its
  average buy-in and PnL come from the paid-in amount, not from a NAV.
- A fund with an unknown paid-in amount in any one pocket has no cost for the whole fund.
  A partial sum would invent a gain.
- Fund labels are a static map. A new CORUM fund shows as `CORUM <code>` until the map grows.
- An account whose contract CORUM stops listing is left as it is: nothing zeroes or closes it.
- Only `realEstate` lines are tiered automatically. Bond funds and the euro fund stay in
  `EQUITY` until the member reclassifies them.
- After a switch, no line shows a gain of its own: per-line and per-pocket performances from
  CORUM are wrong, so only the contract's gain is shown.
- Public CI cannot prove the live login. The parser runs on fixtures shaped like the real
  payload, and `test_live_contract.py` pins the routes.

## Consequences

- New: `services/corum-auth/life_parser.py`, `CorumLifeWriter`, `CorumPort.Portfolio` /
  `LifeContract` / `LifeHolding`, `SyncValues.linesClose` (moved out of `BoursoSyncService`).
- `CorumSyncService` validates, then writes both parts in one transaction; `CorumAdapter` calls
  `/portfolio`. The sidecar Dockerfile copies `life_parser.py`.
- Frontend: `CorumLifeSyncSummary`, `lifeRun`, a `hideWhenEmpty` prop on `ScpiSyncSummary`, new
  `sync.corum.life` strings in the four locales.
- `V122` stores the `AMBIGUOUS` count on the session; `V123` adds the contract facts to
  `account` and `provider_wealth_tier` to `account_holding`. `AccountResponse` gains a nested
  `contract` and `HoldingResponse` a `providerWealthTier`.
- An existing CORUM connection starts creating a Life account on its next sync, without a new
  login.

## Addendum (2026-11-05): contract operations as account transactions

**Decision.** CORUM Life operations (`GET /api/operations/`) become the transactions of the
contract's account, reconciled on every sync by CORUM's operation id (upsert, never touching the
category on update, delete what is no longer reported between the oldest reported date and
today; a missing or empty list keeps the stored rows). A failed read of that list never fails the
sync: the sidecar reports `operations: null` and the valuation is written as usual. The same goes
for a list in which two operations still share an id and a sign: the sidecar reports `null` for
that contract, and the backend, should a stale sidecar let one through, logs a count and keeps
the stored rows instead of refusing the sync as `INVALID_DATA`.

A new column, `transaction.budget_neutral` (V124, default false), marks a row that moves money
inside the wrapper rather than in or out of the budget. Every CORUM operation except a payment is
budget-neutral.

- **U1 (user):** a coupon (`couponDetachment`) is a `DIVIDEND`, ticker `CORUM-LIFE-<CODE>` when
  CORUM names exactly one fund, listed under dividends received with the existing "not in
  budget" flag of locked wrappers.
- **U2 (user):** budget-neutral rows appear only on the account page. They are left out of the
  global transactions view (`searchByMember`, even with an account filter) and of every budget,
  cashflow and categorisation query; dividend classification skips them.
- Payments (`scheduledPayment`, `subscription`) are `DEPOSIT` rows in the member's
  `investissement` transfer category, set on insert only, so they count as contributions and stay
  out of income and spending. Fees are `FEE`; profit participation, switch legs and unknown types
  carry no `txType`. No row is ever `BUY`/`SELL`: the holdings stay CORUM's own figures.
- **A1 (assumption):** the account export (XLSX/CSV) keeps budget-neutral rows; it is a dump of
  the account's data, not a budget view.

**Alternatives considered.** Filtering by account type or provider in each budget query was
rejected: it would hide a real payment into the contract too, and every query would need to know
which connector owns which rows. Leaving the rows out of the database was rejected: the account
page is where a member checks a fee or a coupon. Categorising neutral rows into a dedicated
category was rejected: the member could move them back into the budget by hand, and rule-based
categorisation would keep touching them.

**Consequences.** `TransactionResponse` gains `budgetNeutral`; CORUM rows carry the raw
`operationType` as `type` for the UI to label. A payment type name alone is no proof of origin,
so the UI labels a payment that way only on its CORUM account's page (the list receives the
account's provider); lists spanning accounts show the synced description. Any future connector with in-wrapper movements can
set the same flag. Details: [CORUM Life feature note](../features/corum-life.md#operations).

## Addendum (2026-11-05): remembered credentials and the daily sync

**Context.** A stored CORUM session eventually expires, and the contracts then went stale until the
member typed the password again; CORUM was also absent from the 08:00 member sync. CORUM asks for no second factor at login (`services/corum-auth/main.py`,
`/initiate`), so a stored password is enough to log in unattended.

**Decision.** Follow the Crédit Mutuel Épargne Salariale precedent
([feature note](../features/cmes-epargne-salariale.md#backend)), with Revolut's opt-in: the login
form has an unticked "Remember my credentials (encrypted)" checkbox. Ticked, the login and
password are stored encrypted (`CryptoEncryption`, AES-256-GCM) in
`corum_session.encrypted_credentials` (V125); a sync whose session expired logs in again once
through `/initiate`, replaces the session and reads. A rejected password clears them and turns the
session inactive; disconnecting deletes them with the session; a login without the box ticked
stores none and replaces any stored before. CORUM joins the daily member sync as source `corum`,
gated on an active session like `cmes`.

**Trade-off.** An encrypted password now sits in the database for members who opt in, so a leak
of both the database and the encryption key exposes a CORUM password, and with it the member's
whole CORUM client space, not only the read-only use Picsou makes of it.
Members who do not tick the box keep today's behaviour exactly: only the session blob is stored.
The password is never logged and never returned: the status exposes `credentialsRemembered`, a
boolean. The re-login is one attempt per run, so a changed password costs CORUM one failed login,
after which nothing is replayed.

**Alternatives considered.** Always storing the credentials (as CMES does) was rejected: CMES needs
them because its sessions last minutes, so nearly every sync logs in again; a CORUM session
outlives a sync, so reconnecting by hand stays workable and the member can choose. Keeping the session-only model was rejected: it leaves CORUM the one connector that never
refreshes on its own. Re-logging in from the browser instead of the backend was rejected: the
daily sync runs without a browser.

**Consequences.** Point 2 of [2026-09-26-corum-scpi-sidecar.md](2026-09-26-corum-scpi-sidecar.md)
("credentials are ephemeral") now holds only when the box is unticked. Details:
[CORUM session and remembered credentials](../features/scpi.md#corum-session-and-remembered-credentials).

## Supersedes

Point 7 of [2026-09-26-corum-scpi-sidecar.md](2026-09-26-corum-scpi-sidecar.md): CORUM Life is now
read. PER and capitalisation remain out of scope, and a login with more than one real-estate
contract is still refused as `MULTIPLE_CONTRACTS`.

Point 2 of the same ADR ("credentials are ephemeral"), for members who tick "remember my
credentials": see the addendum above.
