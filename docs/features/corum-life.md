# Feature: CORUM Life (assurance-vie) sync

> Last updated: 2026-11-05

## Context

A CORUM Life contract is a life-insurance wrapper holding units of CORUM funds (SCPI units and
bond funds) plus a euro fund. The same CORUM login that fills SCPI shares can see it. The CORUM
connection now reads it too and writes one `ASSURANCE_VIE` account per contract, with a holding
per fund. See [the ADR](../decisions/2026-11-05-corum-life-sync.md) for the decisions and
[SCPI shares](./scpi.md#corum-sync) for the real-estate half of the same sync.

## How it works

One session, one read. The sidecar logs in once (see the
[CORUM sidecar ADR](../decisions/2026-09-26-corum-scpi-sidecar.md)), lists the contracts, and
reads the real-estate contract and every Life contract in the same browser page. The backend
validates both parts, then writes both in one transaction.

- A Life contract is read with `GET /api/contract/life/{code}` from inside the page (a
  same-origin `fetch`, as for every CORUM read).
- `life_parser.parse_life` turns the payload into a snapshot. It never sees a URL.
- `CorumLifeWriter` validates the snapshot again and writes the account, the holdings and the
  daily snapshot.
- The contracts' operations are read once with `GET /api/operations/` and written as the
  account's transactions (see [Operations](#operations)).
- PER and capitalisation contracts are still not read.
- The contracts sync daily at 08:00 with the other connectors. When the member ticked "remember my
  credentials" at login, a sync whose session expired logs in again by itself first; see
  [CORUM session and remembered credentials](./scpi.md#corum-session-and-remembered-credentials).

### Key files

- `services/corum-auth/life_parser.py` — payload to snapshot: aggregation, euro fund, strictness
- `services/corum-auth/life_parser.py` — `parse_operations`: `/api/operations/` to per-contract lists
- `services/corum-auth/main.py` — `POST /portfolio` (SCPI + Life), `POST /positions` (SCPI only,
  kept for one release), `_select_contracts`, `_read_operations`
- `services/corum-auth/Dockerfile` — copies `life_parser.py`; a missing `COPY` breaks the import
  at startup
- `backend/.../adapter/CorumAdapter.java` — `Portfolio`, `LifeContract`, `LifeHolding`; `fetchPortfolio`, 120 s timeout, refuses any
  contract the sidecar did not certify
- `backend/.../service/CorumSyncService.java` — `validatePortfolio`, `commitPortfolio`
- `backend/.../service/CorumLifeWriter.java` — `validate` and `write`, adoption
- `backend/.../service/CorumLifeOperationWriter.java` — operations to transactions, reconcile
  (`Propagation.MANDATORY`)
- `backend/src/main/resources/db/migration/V126__transaction_budget_neutral.sql` —
  `transaction.budget_neutral`
- `backend/.../service/SyncValues.java` — `linesClose`, shared with BoursoBank
- `backend/.../service/AccountService.java` — `isProviderValued` / `isSyncedCorumLife`: non-manual
  accounts whose `externalAccountId` starts with `Account.CORUM_LIFE_EXTERNAL_ID_PREFIX`
- `backend/src/main/resources/db/migration/V124__corum_session_life_ambiguous.sql` —
  `corum_session.last_sync_life_ambiguous` (copied into `sidecar_session` by V142), exposed as `lifeAmbiguousCount` in the CORUM status
- `backend/src/main/resources/db/migration/V125__corum_life_contract_facts.sql` — the contract
  facts on `account` and `account_holding.provider_wealth_tier`
- `backend/.../service/AccountService.java` — `valuation`: the contract's value and net invested
  pair; `HistoryService.buildIntradayHistory` takes the same pair
- `backend/.../service/WealthPyramidService.java` — `tierFor`; `PortfolioDiversificationService`
  leaves out lines tiered elsewhere
- `frontend/src/components/sync/CorumLifeSyncSummary.tsx` — accounts written by the last run
- `frontend/src/components/sync/CorumPanel.tsx` — shows the SCPI summary and the Life summary
- `frontend/src/components/scpi/scpi-sync-run.ts` — `lifeRun`

### Flow

```
CorumPanel  ->  CorumController  ->  CorumSyncService (login queues the import)
                                          |
                          CorumAdapter.fetchPortfolio  --POST /portfolio-->  corum-auth
                                                                                |
                         /api/contract/active ---------------------------------+  one browser page
                         real-estate contract + one call per fund -> parse_snapshot
                         /api/contract/life/{code}, per Life contract -> parse_life
                         /api/operations/, once -> parse_operations (failure: null for all)
                                          |
                  { realEstate: Snapshot | null, life: [LifeContract] }
                                          |
                    validatePortfolio (SCPI rules + CorumLifeWriter.validate)
                                          |
          one transaction, member row locked:
            ScpiFundReconciler (SCPI funds)  +  CorumLifeWriter.write (Life contracts
                                                 + CorumLifeOperationWriter per written contract)
            + session.markSuccessful(now)      (every row stamped with the same `now`)
```

### Payload facts

Shape only. No amounts or identifiers belong in this note. `fixtures_life.py` holds a payload with
the same keys, nesting and number types and invented figures.

- `contractType` is `LIFE` (compared case-insensitively) and `contractCode` is a string.
- `contractDetail.valuationAmount` is the contract total, a JSON number.
- `contractDetail.netInvestedAmount` is the contract's net invested amount and `gainOrLoss` its
  gain; `valuationAmount − netInvestedAmount == gainOrLoss` to the cent.
  `grossInvestedAmount` is what was paid in (an integer). `programSavingAmount` is
  `{amount, frequency}` (`frequency` seen as `monthly`). `creationDate` is `DD/MM/YYYY`.
- **A line's `netInvestedAmountCumulated` leaves out money switched between funds.** After a
  switch from the euro fund into a bond fund, the euro fund line shows a negative
  `reallocationAmount`, the bond line a positive one and a gain it never made, and the lines'
  net invested no longer add up to the contract's, positive reallocations included. Per-line and
  per-pocket performances are wrong; only the contract-level figures are right.
- `contractProfile.distributionTableSections[]` are the management pockets. Each has a total
  `amount` and `sectionProduct[]`. A pocket line has `productName` (the fund code, for example
  `XL`), `quantity`, `unitPrice`, `amount`, `valorisationDate`, `netInvestedAmountCumulated`
  and `reallocationAmount`.
- `contractProfile.chartSections[]` is the asset-class view. Section names seen live are
  `bondFund`, `realEstate` and `euroLife`. Each lists product codes with a percent and an amount.
  Each code's section name is sent as the holding's `assetClass`; `euroLife` also marks the
  euro fund.
- The same code can appear in several pockets.
- The euro fund line has an integer `quantity` and `unitPrice` of unproven meaning and no
  `valorisationDate`.
- `GET /api/contract/active` lists every contract with its own `valuationAmount`, rounded and
  cached on CORUM's side.

## Rules

### Parsing (`life_parser.py`)

| Rule | Detail |
|------|--------|
| Numbers only | A string, bool, `NaN` or missing amount is `UPSTREAM_FORMAT_CHANGED` |
| Signs | A negative amount, quantity or unit price is `UPSTREAM_FORMAT_CHANGED`. A negative contract total is `PORTFOLIO_INCOMPLETE` |
| Half-written line | `quantity = 0` with `amount != 0` is `PORTFOLIO_INCOMPLETE` |
| Aggregation | One holding per fund code: quantity, amount and net invested summed across pockets, latest NAV date kept |
| Unit price across pockets | Pockets may quote one code at different prices (one at an older NAV). The holding's `unitPrice` is then `Σamount / Σquantity`, 8 decimals, half-up. With `Σquantity = 0` the first pocket's price stands |
| Euro fund | Codes under `chartSections` / `euroLife`: `quantity = amount`, `unitPrice = 1`, `euroFund = true`. The payload's own quantity and price are ignored. A euro fund line with a NAV date is `UPSTREAM_FORMAT_CHANGED` |
| Pending switch | `reallocationAmount` is already inside `amount` and is ignored, otherwise the same euros count twice |
| Cost basis | `netInvestedAmountCumulated` summed per fund. Unknown in any pocket makes the whole fund's cost unknown (`null`) |
| Labels | `LIFE_FUND_NAMES`: `CC` CORUM Origin, `XL` CORUM XL, `EU` CORUM Eurion, `BCO` CORUM BCO, `CBEHY` CORUM BEHY, `CBE` CORUM Butler Entreprise, `FGCORUM` CORUM EuroLife (fonds euros). Any other code is `CORUM <code>` |
| Contract code | The payload's `contractCode` must equal the code requested, else `UPSTREAM_FORMAT_CHANGED` |
| Contract cost | `netInvestedEur` = `contractDetail.netInvestedAmount`, required, `>= 0`. `\|valuationAmount − netInvestedAmount − gainOrLoss\| > 0.05` (or a missing `gainOrLoss`) is `UPSTREAM_FORMAT_CHANGED`. Per-line `netInvestedEur` is still sent raw |
| Paid in | `paidInEur` = `grossInvestedAmount`, `null` when absent, a negative one is `UPSTREAM_FORMAT_CHANGED` |
| Opening date | `openedAt` = `creationDate` as ISO, `null` when unreadable |
| Savings plan | `scheduledPayment` = `{amountEur, frequency}` from `programSavingAmount`; `null` for any odd shape (no dict, non-number or negative amount, no frequency). Informational, never a refusal. A zero amount is sent as is |
| Asset class | `assetClass` = the code's `chartSections` section name, raw, `null` when not listed. A code under two sections, or a section without a name, is `UPSTREAM_FORMAT_CHANGED` |

### Reconciliation

Tolerance is `max(0.05, 0.01 × lines)` euros (`SyncValues.linesClose`, and the same formula in
`life_parser._tolerance`).

1. Lines of each pocket against the pocket's `amount`.
2. Pockets against `contractDetail.valuationAmount`.
3. Aggregated holdings against `contractDetail.valuationAmount`. The backend repeats this on
   the wire shape, with `valuationLineCount`, the raw row count across every pocket (euro fund
   included).

The `/contract/active` total is only logged when it differs by more than 0.05 (no figure in the
log). There is no per-line `quantity × unitPrice` check.

### Selection

`_select_contracts` reads one real-estate contract at most and every Life contract. Two
real-estate contracts is `MULTIPLE_CONTRACTS` and also blocks the Life part. The same Life code
listed twice is `UPSTREAM_FORMAT_CHANGED` on `/portfolio` only: `/positions` never reads the Life
part and answers as before. On `/portfolio` (`reads_life=True`), a `LIFE` entry whose
`contractCode` is empty or not a string is `UPSTREAM_FORMAT_CHANGED` too, before any contract is
read: skipping it would report a successful sync that left that contract stale. `/positions` still
skips it. Neither kind is `PORTFOLIO_INCOMPLETE`. An `/contract/active` entry whose
`contractCode` is not a string is left out of the logged totals.

Every in-page read (`_get_json`) aborts its `fetch` (body included) through an `AbortController`
after `READ_TIMEOUT_SECONDS` (20 s, below the 30 s navigation bound), and the evaluation itself is
bounded `READ_EVALUATE_GRACE_SECONDS` (5 s) later for a wedged page. Either is
`UPSTREAM_UNAVAILABLE`; the raise reaches `_read_in_portal`'s `finally`, which frees the browser
slot. The one exception is the operations read: `_read_operations` catches its timeout (like any
other failure of that read), so the sync goes on with `operations: null` and the stored rows are
kept. A contract whose operations stop updating while its valuation does is diagnosed from the
sidecar log line `CORUM Life operations not read (code=UPSTREAM_UNAVAILABLE)`.

### Writing (`CorumLifeWriter`)

For each contract, in order:

1. The live account with `externalAccountId = corum_life_<code>` is updated (`UPDATED`).
2. A soft-deleted one stops the sync from bringing it back; nothing is written (`IGNORED`).
3. The one manual account that qualifies is adopted (`LINKED`). It must be `ASSURANCE_VIE`, manual,
   with no `externalAccountId`, either named "CORUM Life" after normalization (case, accents,
   punctuation) or with provider `CORUM` (case-insensitive) whatever its name, and with
   **no holdings**. It also needs to be the only candidate, and the
   snapshot must hold exactly one Life contract. A hand-typed holding is never wiped.
4. When such manual accounts exist but none can be adopted (it has holdings, there are several,
   or the snapshot holds several contracts), nothing is written for the contract (`AMBIGUOUS`).
   A new account beside the manual one would count the contract twice in the net worth. The
   remedies are narrower than they look: deleting the manual account removes the candidate;
   renaming it does not when its provider is `CORUM` (the provider must change too); removing
   its holdings helps only when the snapshot holds exactly one contract and it is the only
   candidate. The UI message (`sync.corum.life.ambiguous`) gives the first two.
5. Otherwise an account is created (`CREATED`): "CORUM Life", or "CORUM Life <code>" when the
   snapshot holds several contracts.

The number of `AMBIGUOUS` contracts is stored on the session (`last_sync_life_ambiguous`, reset
when a sync is queued) and returned as `lifeAmbiguousCount` by the CORUM status. The SCPI funds
carry their `AMBIGUOUS` outcome in `scpi_provider_fund`; Life contracts have no such table, so
the count is the session's.

The name is set on creation only. Then, on every write:

| Field | Value |
|-------|-------|
| `currentBalance` | contract `valuationAmount` |
| `cashBalance` | 0. The euro fund is a holding |
| `provider` / `manual` | `CORUM` / `false` |
| Holdings | Deleted and rewritten each sync |
| Holding ticker | `CORUM-LIFE-<CODE>` (upper case) |
| Holding value | `quantity`, `currentPrice = unitPrice`, `providerValueEur = valueEur` |
| `providerInvestedEur` | contract `netInvestedEur`; null from a stale sidecar |
| `providerPaidInEur` | contract `paidInEur` |
| `scheduledPaymentEur` / `scheduledPaymentCadence` | the savings plan: `monthly` → `MONTHLY`, `quarterly` → `QUARTERLY`, `annual`/`yearly` → `YEARLY`. A zero amount or another word leaves both null and logs it; never a refusal |
| `openedAt` | contract `openedAt`, only while the account has none: a date the member typed wins |
| Holding cost | `averageBuyIn = netInvested / quantity` (8 decimals) and `providerPnlEur = valueEur - netInvested`, only when every line's cost is known **and** the lines' costs add up to the contract's (`SyncValues.linesClose` with `valuationLineCount`); otherwise both null on every line (logged). A stale sidecar with no contract cost keeps the line costs |
| Holding tier | `providerWealthTier`: `realEstate` → `REAL_ESTATE`; `bondFund`, `euroLife` and anything else → null (the account's `EQUITY`) |
| Holding date | The NAV date as `providerPriceDate` (none for the euro fund) |
| Snapshot | Daily snapshot with the contract's net invested; from a stale sidecar the summed line costs, or derived by `AccountService` when any line has none |

### Valuation

`AccountService.valuation` returns `(currentBalance, providerInvestedEur)` for an account with a
`providerInvestedEur` that `isProviderValued` (a synced CORUM Life account: non-manual, with a
`corum_life_` external id, whatever its editable provider string now says), with or without
holdings. The gain the UI shows is then the contract's own, never the gain against the lines'
summed costs, and never a zero gain once the last line was deleted by hand.
`HistoryService.buildIntradayHistory` takes that same cost for the 24H chart, weighed by the
member's share. A manual account carrying the column ignores it.

Editing a line's average buy-in (`AccountService.updateHolding`) on such an account keeps the
line's `providerValueEur` (no feed quotes it, so clearing it would unvalue the line and move its
tier until the next sync) and clears only `providerPnlEur`, so the edited buy-in becomes the
line's cost basis. On every other account both are cleared, as before.

### Classification

The wealth pyramid reads each line's tier as member override ▸ `providerWealthTier` ▸ CoinGecko ▸
account tier (see [wealth pyramid](./wealth-pyramid.md)). The sync never writes the member's
override table. The diversification breakdown leaves out lines whose effective tier (override ▸
provider) is not `EQUITY`, so the SCPI units do not show as unclassified equity.

### Operations

`GET /api/operations/` lists every contract of the login, split by year:
`[{contractCode, contractType, yearData: [{year, operations: [{operationType, operationCategory,
date: "DD/MM/YYYY", amount, operationIds: [str | int], fundCodes: str | null}]}]}]`, where the
live payload sends each id as a digit string (`["1234567"]`). There is no
per-operation detail endpoint. The sidecar reads it once, after the Life details, and
`parse_operations` keeps the Life codes only: ISO dates, numbers only, `fundCodes` split on commas
and upper-cased, `operationIds` exactly one id (a digit string or an integer, kept as a string). Any malformed entry, or a failed
read, gives `operations: null` for every contract and a log line with the cause only; the
valuation is synced all the same and the UI shows nothing amber. A Life code the payload does not
list is `null` too. Two operations of one contract that share an id **and** a sign (which the
backend's key cannot tell apart) are no malformed entry: that contract alone gets
`operations: null`, logged by cause only (`DUPLICATE_OPERATION`), and the others keep theirs.
A browser failure (`PlaywrightError`) during the read is handled like a failed read.

`CorumLifeWriter.validate` refuses (`INVALID_DATA`) an operation without id, type, date or
amount, and an id over 40 or a type over 60 characters. Two operations that still share a key
(a stale sidecar would let them through) do not refuse the sync: the count is logged and that
contract's list is treated as `null`, so its stored rows are kept and its valuation is written.
`CorumLifeOperationWriter.reconcile` runs only for a contract written as `UPDATED`, `LINKED` or
`CREATED`, inside the same transaction:

| CORUM `operationType` | `txType` | `budgetNeutral` | Category | Other |
|-----------------------|----------|-----------------|----------|-------|
| `scheduledPayment`, `subscription` | `DEPOSIT` | false | the member's `investissement` (TRANSFER), on insert only | a contribution in the allocation view while its category is a TRANSFER one |
| `couponDetachment` | `DIVIDEND` | true | none | `ticker` `CORUM-LIFE-<CODE>` and the holding's name when exactly one fund code |
| `administrativeManagementCosts` | `FEE` | true | none | negative |
| `profitParticipation`, `arbitrage` (both legs), anything else | null | true | none | an unknown type is counted in the log |

- `type` stores CORUM's raw `operationType`; the UI labels a row by it
  (`accounts.corumLifeOperation.<type>`), and `description` is a neutral English fallback.
- Key: `externalId` = `externalTransactionId` = `corum:<operationId>`; when two operations of one
  list share an id, `corum:<id>:+` / `corum:<id>:-` by sign. When a stored `corum:<id>` row gives
  way to those legs, the leg with the stored amount's sign inherits its `categoryRef` and
  `categoryManual` before the old row is deleted; the other leg starts as a fresh insert.
- Reconciliation, like Fortuneo's full-history one: upsert by key; an update rewrites date,
  amount, type, `txType`, ticker, name and `budgetNeutral` but never `categoryRef` or
  `categoryManual`; synced rows no longer reported between the oldest reported date and today are
  deleted. A null or empty list keeps every stored row.
- The category comes from `CategoryService.findOrCreateDefault`: an archived `investissement` is
  not revived, and one of another kind is not used; the payment then stays uncategorized. The
  allocation view reads contributions from TRANSFER-category rows only, so such a payment is not
  counted as a contribution until the member files it under a transfer category.
- `reconcile` reads the stored rows by account **and** member
  (`findByAccountIdAndAccountMemberIdAndIsManualFalse`), the member being the one the account
  was resolved for.
- No row is ever `BUY`/`SELL`, and no CORUM row goes through auto-categorisation or enrichment.
- Budget-neutral rows show on the account page only. `TransactionRepository` leaves them out of
  every budget, cashflow, categorisation and global-transactions query (`searchByMember`, even
  with an account filter); `DividendReceiptService.classifyRecent` skips them. The coupons still
  list under dividends received, flagged out of the budget like every locked-wrapper dividend.
  The account export (`TransactionsExporter`) keeps them: it is the account's data.
- `TransactionResponse.budgetNeutral` carries the flag to the UI. A neutral or synced
  `CORUM-LIFE-` ticker row is labelled by its type everywhere (a manual BUY/SELL on such a line
  keeps its trade label); a payment (`scheduledPayment`, `subscription`)
  only on its own account page, where `TransactionsList` gets `accountProvider` and the row is
  synced on a `CORUM` account: those type names are no proof of origin on their own. Lists that
  span accounts show the synced description ("Scheduled payment").
- `EssentialExpenseEstimator` never takes a neutral row as the other leg of a transfer.

All of it is one transaction with the SCPI part: a refused Life contract rolls back the SCPI
funds of the same run, and the reverse.

## Error codes

All are `SidecarErrorCode` values declared for `SidecarBank.CORUM` and reach the UI through the existing CORUM error mapping.

| Code | Raised when |
|------|-------------|
| `PORTFOLIO_INCOMPLETE` | Pockets or holdings do not add up to the contract. Negative total. Zero units with a value. Neither a real-estate nor a Life contract. `snapshotComplete` is false |
| `UPSTREAM_FORMAT_CHANGED` | A non-number figure, negative amount, a dated euro fund, a missing code, a contract code that differs from the one requested, a Life code listed twice (`/portfolio` only), a missing or negative contract net invested, a gain that is not value minus net invested, a code under two asset classes |
| `MULTIPLE_CONTRACTS` | More than one real-estate contract (blocks the Life part too) |
| `INVALID_DATA` | Backend re-check: missing Life list or code, duplicate contract or ticker, currency not EUR, negative units, negative contract net invested or paid in, euro fund not in 1 EUR units, line count below the holding count, an incomplete operation (a duplicate one only drops that contract's list) |
| `SESSION_EXPIRED`, `INVALID_CREDENTIALS`, `UPSTREAM_UNAVAILABLE`, `INTERNAL_ERROR` | Unchanged |

## Frontend

After a successful sync, `CorumPanel` shows:

- `ScpiSyncSummary` for the funds. It gets `hideWhenEmpty` when the run wrote a Life account or
  left one `AMBIGUOUS`, and while the accounts query is pending, so a Life-only login never shows
  the "no fund found" warning, not even for a moment. A failed accounts query does not hide it.
- `CorumLifeSyncSummary`: the Life accounts of the run with their balance, and an amber
  `sync.corum.life.ambiguous` line when `lifeAmbiguousCount > 0`, never a success line for them.

`lifeRun` selects accounts that are `ASSURANCE_VIE`, provider `CORUM`, not manual, and whose
`lastSyncedAt` equals the session's `lastSyncCompletedAt`. `CorumPanel` and `CorumLifeSyncSummary`
feed it `useAllAccounts()`, hidden accounts included: a hidden contract the run updated still counts. That works because the backend stamps
every row of a run with the same instant. Strings live under `sync.corum.life` in `en`, `fr`,
`es` and `de`; the panel's `scope` and `syncSuccess` strings now mention both kinds.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| One `/portfolio` read | Both parts describe the same moment and commit together | Two sidecar calls and two commits |
| One holding per fund code | Holdings view, classification and diversification all key on the ticker | One holding per (code, pocket) |
| Euro fund as a holding in 1 EUR units | It is invested money, and the payload's unit is unproven | Cash balance |
| Provider-valued for a synced CORUM Life account (non-manual, `externalAccountId` prefixed `corum_life_`, `Account.CORUM_LIFE_EXTERNAL_ID_PREFIX`) | No feed quotes a CORUM unit; "CORUM" on a manual account is free text, and the provider string is editable | `CORUM` in `PROVIDER_VALUED` for every account; keying on the provider string |
| Adoption needs no holdings | Replacing holdings is the one write a sync cannot undo | Adopting any "CORUM Life" account |
| Provider `CORUM` is a candidate too | An existing user's manual contract rarely carries the exact name; missing it creates a second account | Name match only |
| A duplicate operation key drops the list, not the sync | The ledger is secondary to the valuation | `INVALID_DATA` for the whole run |
| `AMBIGUOUS` writes nothing | A second account for the same contract double-counts the net worth | Creating an account beside the manual one |
| Derived price across pockets | Pockets can be priced at different NAV dates; value and units stay exact | Refusing the contract |
| Tickers prefixed `CORUM-LIFE-` | A bare `CC` or `EU` can collide with a real symbol | Bare codes |
| Contract-level cost basis | The lines' costs leave out switched money; only the contract's net invested gives the real gain | Summing the line costs, or adding the reallocations back |
| Line costs dropped when they miss the contract's | A line switched into shows a gain it never made | Keeping each line's own cost |
| Provider tier on the holding, override wins | It is CORUM's statement, rewritten every sync; the member's choice is kept apart | Writing a classification override from the sync |
| Bonds and the euro fund stay `EQUITY` | User decision: no new tier | A bond or guaranteed tier |

## Gotchas / Pitfalls

- **Wealth tier comes from CORUM's asset-class view.** SCPI units (`realEstate`) count as
  `REAL_ESTATE` in the [wealth pyramid](./wealth-pyramid.md); bond funds and the euro fund keep
  the account tier `EQUITY`. The member can reclassify a ticker in the holding classification
  editor; the override is keyed on `(member, ticker)`, so it survives resyncs even though the
  holdings are rewritten every time, and it wins over CORUM's tier.
- **Per-line gains are usually absent.** After any switch the lines' costs miss the contract's,
  so no line carries an average buy-in or a PnL. The account's gain is the contract's.
- **Holdings are rewritten, not merged.** Anything stored on an `account_holding` row is lost
  at the next sync. Keep per-line user data in a table keyed on the ticker.
- **The euro fund "quantity" is euros.** A reader multiplying quantity by price gets the value
  only because the price is 1. The backend refuses a euro fund that is not in 1 EUR units.
- **A fund in several pockets is one line.** Its cost is unknown if any pocket lacks one.
- **`/positions` is a wrapper.** It stays one release for a backend not yet on `/portfolio`
  and answers `PORTFOLIO_INCOMPLETE` on a Life-only login. Remove it afterwards.
- **A stale sidecar skips its own checks.** The backend repeats the reconciliation for that
  reason. Keep both.
- **No removal path.** A contract CORUM stops listing leaves its account as it is.
- **A failed operations read never fails a sync.** A failed or malformed `/api/operations/`
  read in the sidecar only logs; the stored rows stay. Check the sidecar log (`CORUM Life
  operations not read` / `rejected`) when a contract's ledger stops moving. The backend side
  can still fail: an operation that `CorumLifeWriter.validate` refuses fails the sync with
  `INVALID_DATA`, and an operation write failure rolls back the whole portfolio transaction.
- **Deletion is bounded by the oldest reported date.** CORUM returning a shorter history deletes
  nothing older than what it still reports.
- **Unproven live.** The euro fund's unit semantics and a contract with several Life accounts
  have fixtures, not a real account behind them.

## Deferred

- **`valuationData` history backfill.** The payload carries a value history. Today the series
  starts at the first sync. Separate change.
- **Per-pocket breakdown.** Pockets are summed away.
- **A tier for bonds or the euro fund.** They stay `EQUITY` by user decision.

## Tests

- `services/corum-auth/test_life_parser.py` — envelope, aggregation across pockets, euro fund as
  1 EUR units, cost basis unknown in one pocket, price derived across pockets, reconciliation at
  each level, tolerance scaling, strict number handling, reshaped payloads, labels, contract
  facts, the gain check, the switch-shaped fixture (lines' cost below the contract's), savings
  plan shapes, asset class per code and a code in two sections
- `services/corum-auth/test_live_contract.py` — `/portfolio` and `/positions` against the
  wire contract, Life route, contract selection (Life beside real estate, Life-only, PER left
  out, neither kind), duplicate Life codes refused on `/portfolio` only, a non-string active
  code ignored, payload for another contract refused, the new contract and holding keys
- `CorumLifeWriterTest` — `validate` refusals, create, update without rename, deleted account
  not revived, adoption rules (by name or by provider `CORUM`), `AMBIGUOUS` writes nothing, several contracts, cost derived when
  a line has no paid-in amount, contract facts stored, a typed opening date kept, line costs
  dropped or kept by the contract cost, stale sidecar, cadence mapping, provider tier
- `CorumSyncServiceTest` — Life-only portfolio, neither part, both parts in one run, one part
  refused writes nothing of the other, `lifeAmbiguousCount` in the status
- `CorumAdapterTest` — `fetchPortfolio` mapping, contract facts and a stale payload without
  them, uncertified contracts, error codes, timeout
- `SyncValuesTest` — `linesClose`
- `AccountServiceTest#valuation_corumLife_*` — CORUM's own line values when no feed quotes them,
  the contract value and cost pair; `valuation_manualAccountTypedCorum_*` and
  `valuation_manualAccountWithAProviderCost_*` — a manual "CORUM" account keeps the usual
  valuation; `getHoldings_carriesTheProviderWealthTier`, `response_carriesTheContractFacts*`
- `HistoryServiceTest#buildIntradayHistory_contractLevelCost_*` — the 24H chart's cost, whole
  and shared account
- `WealthPyramidServiceTest` — provider tier over the account tier, override over the provider
- `PortfolioDiversificationServiceTest` — real-estate lines left out, member tier wins, a
  member's non-`EQUITY` tier leaves an ordinary (non-CORUM) line out
- `ScpiSyncSessionPostgresTest` — one account created once, deleted account not recreated, a
  Life write failure rolls back the SCPI funds of the same run, the V125 columns read back;
  operations written once across two syncs (V126, the external-id unique index, both switch
  legs), the payment in `investissement`, only the payment visible to the budget queries
- `test_life_parser.py` `ParseOperationsTest`, `test_live_contract.py` — `/api/operations/` parsing,
  one read for all contracts, failure (HTTP, format or browser) → `operations: null` with the
  valuation kept; a shared (id, sign) → `null` for that contract only
- `CorumLifeOperationWriterTest` — mapping per type, coupon ticker and name, unknown type
  neutral, archived or retyped category, sign-suffixed keys, idempotent resync, deletion window,
  manual category kept on update and carried to the same-sign leg when a key splits, member-scoped
  read, null/empty list or a repeated key keeps rows
- `CorumLifeWriterTest` (operations) — validation, a repeated key reconciled as `null` with the
  valuation written, reconcile called for a written contract, never for `AMBIGUOUS`/`IGNORED`;
  `CorumSyncServiceTest` — a duplicate operation keeps the stored rows and syncs the valuation,
  a failed operation write fails the run
- `TransactionRepositoryAccountFetchTest#budgetQueriesSkipBudgetNeutralRows` (including the AI
  categorizer's `findRecentCategorizedByMemberId`), `#syncedRowsOfAnAccountAreReadForItsOwnerOnly`,
  `DividendReceiptServiceTest` (neutral never classified; CORUM coupon listed out of budget; a
  neutral row never `countsInBudget`, whatever the account type),
  `AllocationServiceTest#compute_contributions_countACorumLifePayment`,
  `EssentialExpenseEstimatorTest#aBudgetNeutralCreditIsNeverTakenForTheOtherLegOfATransfer`,
  `#aBudgetNeutralDebitIsNeverCountedAsSpending`
- `AccountServiceTest` — contract cost kept after a provider rename and without holdings, a
  non-manual `CORUM` account without the `corum_life_` id not provider-valued, a buy-in edit keeps
  the line value; `ScpiSyncSessionPostgresTest` runs the real `AccountService` and asserts the
  Life `balance_snapshot` row
- Frontend: `CorumLifeSyncSummary.test.tsx` and `CorumPanel.test.tsx` (hidden accounts read
  through `useAllAccounts`), `ScpiSyncSummary.test.tsx` (`hideWhenEmpty`),
  `CorumTab.test.tsx` (Life-only and mixed logins, no warning while accounts load, the warning
  when they fail, ambiguous contract), `TransactionRow.test.tsx` / `TransactionsList.test.tsx`
  (a payment labelled only on a synced CORUM account; the CORUM label wins over a merchant label;
  a manual row on a `CORUM-LIFE-` line keeps its trade label), `AccountDetailPage.test.tsx`
  (`accountProvider` passed)

## Links

- Related ADR: [CORUM Life contracts sync as one assurance-vie account each](../decisions/2026-11-05-corum-life-sync.md)
- Related ADR: [CORUM client space fills existing SCPI accounts through a browser sidecar](../decisions/2026-09-26-corum-scpi-sidecar.md)
- Related feature: [SCPI shares](./scpi.md), [Wealth pyramid](./wealth-pyramid.md)
