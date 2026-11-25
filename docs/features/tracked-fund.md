# Feature: Tracked fund (an account that holds one fund)

> Last updated: 2026-11-25

## Context

Some accounts hold a fund, but the provider reports them as one cash balance with no holding
lines. A Wise "Stocks" jar synced through Enable Banking arrives as a `SAVINGS` account whose
balance is the jar's market value, with nothing saying it is invested or in what. The
[wealth pyramid](./wealth-pyramid.md) therefore counted it as safety net, and
[diversification](./portfolio-diversification.md) ignored it. The member can now declare the ISIN
of the single fund such an account holds, and the analysis pages count its balance as that fund.

## How it works

### What is stored

`account.tracked_isin` (nullable, PostgreSQL `V157__account_tracked_isin.sql`, SQLite
`V4__account_tracked_isin.sql`) holds the member's ISIN, trimmed and uppercased. Nothing else is
stored: no `account_holding` row is ever written for it, and the balance stays the source of truth.

`PUT /api/accounts/{id}` and `POST /api/accounts` accept `trackedIsin`:

| Request value | Effect |
|---|---|
| missing or `null` | keeps the stored value, so a client that does not know the field never clears it |
| blank (`""` or whitespace) | clears it |
| a valid ISIN | stores it uppercased (shape plus ISO 6166 check digit, `ledger::instrument::normalize_isin`) |
| anything else | `422` with the field error `errors.trackedIsin` |

The account response carries `trackedIsin` and `trackedTicker`. `trackedTicker` is the market
ticker the ISIN resolved to, or `null` while unresolved; the ISIN is never echoed there. Right
after a save it is usually still `null`, since resolution runs in the background: a client that
wants the ticker refetches the account.

Syncs never write the column. They load the whole row and write it back through
`ledger::accounts::update`, so the value round-trips through every resync.

### When it applies

`tracked_fund::eligible`: an ISIN is declared, and the account is **not a liability**, **not a
peer loan** and **not a pocket** (`parent_account_id` set). `tracked_fund::keys` then keeps only
eligible accounts **without holding rows**: when an account has real lines, they win and the ISIN
is ignored (but kept, so it applies again if the lines go away).

### The key: the linked ticker, else the ISIN

Held lines carry market tickers (`IWDA.AS`), never ISINs, and profiles are keyed on tickers. A
bare ISIN used as a ticker never gets a profile: Yahoo refuses ISIN-shaped symbols, justETF is
only asked about ETFs, and the refresh writes `NO_DATA` for 30 days. So the line is keyed on the
ticker linked to the ISIN in `security_profile` (`ticker`, `isin`): the **smallest** linked ticker
other than the ISIN itself, else the ISIN.

A tracked line therefore merges with a held line of the same listing, and the member's
`holding_classification` on that ticker (tier, sector, country) applies to it too.

### Resolution: on save, then weekly

- **On save.** When create or update stores a new ISIN, `tracked_fund::spawn_resolution` runs off
  the request: it asks the `IsinResolver` (OpenFIGI, the path the syncs use) for the ticker,
  records the `(ticker, isin)` pair through `SecurityIdentities` the way syncs do, then refreshes
  the profile of the key: the ticker linked to the ISIN after that write, else the ISIN. The
  write only fills an empty `isin`, so a ticker that already carries another ISIN is not linked
  and the key stays the ISIN. It runs in a spawned task, so the response
  does not wait for it; a failure is logged and never refuses the save.
- **Weekly.** `refresh_security_profiles` (Sundays 03:45) first calls
  `tracked_fund::resolve_pending`, which retries every distinct tracked ISIN of a live account
  that still has no linked ticker (counting only the ISINs actually linked afterwards), then adds
  `tracked_fund::profile_tickers` to its ticker set. OpenFIGI does not cache a miss that follows a
  failed call (transport error, timeout, non-2xx status such as 429), so the retry asks again in
  the same process; a definitive answer, found or not, stays cached for the process.
  The on-demand `POST /api/analysis/security-profiles/refresh` (`RefreshRunner::trigger`) adds the
  same keys.
- `profile_tickers` is unioned only into those two security-profile ticker sets.
  `holdings::distinct_tickers` is left alone: the price and logo jobs share it, and a fund valued
  by its balance needs neither.

The analysis endpoints never call the network: they read the link with one batched query.

### Pages affected

- **Wealth pyramid.** In the no-holdings branch, the account's weighted live value goes to
  `tracked_fund::tier`: the member's `wealth_tier` override on the key, else `EQUITY`. It is listed
  under that tier in `accountsByTier`. The daily-cash test uses the **resolved** tier, so a tracked
  `CHECKING` account counts as daily cash only if an override moves its fund to `SAFETY_NET`.
- **Projection.** Built on the pyramid, so it follows. A recurring plan into a tracked account is
  tiered the same way, so new money and existing value grow at the same rate.
- **Diversification.** The account is one line on the key, valued at its share-weighted EUR live
  value (an overdrawn account, at zero or below, adds no line), after the held lines: a held line on the same ticker names it (its name and origin win),
  else the UI shows the ticker. The existing equity filter applies: an override to a non-equity
  tier removes the line. An unresolved ISIN has no profile, so it lands in the unclassified list.

### Pages not affected

Dashboard, net worth, goals, history, budget, syncs and the account balance. The tracked fund
only reclassifies the balance inside the two analysis pages; `dashboard_totals_ignore_the_tracked_fund`
pins that.

### Key files

- `backend-rs/src/accounts/tracked_fund.rs` — eligibility, keys, tier, resolution, profile tickers
- `backend-rs/src/accounts/service.rs` — `trackedIsin` validation, keep/clear, resolution spawn
- `backend-rs/src/accounts/dto.rs` — `trackedIsin` and `trackedTicker` in the response
- `backend-rs/src/ledger/instrument.rs` — `normalize_isin` (shape and check digit)
- `backend-rs/src/accounts/analysis/pyramid.rs`, `projection.rs`, `diversification.rs`
- `backend-rs/src/accounts/analysis/profiles.rs` — `RefreshRunner::trigger` ticker set
- `backend-rs/src/jobs/scheduler.rs` — `refresh_security_profiles`, the weekly retry
- `backend-rs/migrations/V157__account_tracked_isin.sql`,
  `backend-rs/src/db/sqlite/migrations/V4__account_tracked_isin.sql`

### Flow

```text
PUT /api/accounts/{id}  { trackedIsin }
  ├─ validate (422 errors.trackedIsin) → store uppercased / keep / clear
  └─ ISIN changed → spawn_resolution (off the request)
       ├─ IsinResolver.resolve(isin) → ticker ≠ isin → SecurityIdentities.record (security_profile)
       └─ profile_runner.refresh_stale({ticker or isin})

GET /api/analysis/pyramid | /diversification | /projection   (no network)
  └─ tracked_fund::keys(accounts)   eligible + no holdings → linked ticker ▸ ISIN
       ├─ pyramid / projection: tier = override on key ▸ EQUITY
       └─ diversification: one line on the key, live EUR × share

Sunday 03:45 refresh_security_profiles
  ├─ tracked_fund::resolve_pending   live tracked ISINs without a linked ticker
  └─ distinct_tickers ∪ tracked_fund::profile_tickers → refresh_stale (≤40)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Declared ISIN on the account, read only by the analysis | The balance stays the source of truth; nothing outside two pages moves | A synthetic holding row; retyping to `COMPTE_TITRES` (see the ADR) |
| Key on the linked ticker, ISIN as fallback | Profiles and held lines are keyed on tickers; a bare ISIN never gets a profile | Keying on the ISIN |
| Resolution in a background task after the save | OpenFIGI can be slow or down; the member's save must not wait for it or fail with it | Resolving inline in the request |
| Missing or `null` keeps the stored value | Older clients and other forms send the account without the field | `null` clears, which a form unaware of the field would trigger on every save |
| Separate `profile_tickers`, not `distinct_tickers` | The price and logo jobs share `distinct_tickers`; a balance-valued fund needs no price | Extending `distinct_tickers` |
| Real holdings beat the ISIN | Lines are richer than one declared fund | Adding the fund on top of the lines, which would count the value twice |

## Gotchas / Pitfalls

- **Resolution delay.** The save answers before OpenFIGI does. Until the background pass links a
  ticker and refreshes its profile, the key is the ISIN and the fund shows as unclassified in
  diversification (the pyramid already counts it as equity).
- **Bare-ISIN fallback.** When OpenFIGI cannot resolve the ISIN, the key stays the ISIN, which has
  no profile: the line sits in the unclassified list until a weekly retry links it, or the member
  classifies it by hand.
- **A hidden tracked account still appears in diversification.** Diversification has no hidden
  filter for held lines either; this feature keeps each page's existing behaviour (the pyramid
  leaves hidden accounts out).
- **One ISIN may link to several tickers.** Several listings record the same ISIN in
  `security_profile`. The smallest ticker wins (`min(ticker)`), so the choice is stable, but text
  collation can differ between PostgreSQL and SQLite for unusual tickers.
- **The key can move under a member's feet.** `min(ticker)` runs over the instance-wide
  `security_profile`, so another member's sync recording a smaller listing of the same ISIN
  changes the key. A tier or sector override on the old ticker then stops applying; the remedy
  is to classify the new ticker. A future improvement could prefer a ticker the viewer overrides
  or holds.
- **Not in the data export.** The data export does not include it.
- **The tier override lives on the key.** A bond or money-market fund leaves `EQUITY` through a
  `holding_classification.wealth_tier` on the linked ticker. An override set on the ISIN while it
  was the key stops applying once a ticker is linked.

## Tests

- `tests/integration/accounts/tracked_fund.rs` — validation (stored uppercase, 422 field error,
  blank and whitespace clear, missing or `null` keeps, create), keys (linked ticker else ISIN),
  eligibility (debt, peer loan, holdings, pocket ignored but ISIN kept), `trackedTicker` resolved
  only, resolution (link, unresolvable, save still answers when it fails, a ticker that already
  has another ISIN is not reported as linked and the ISIN profile is refreshed), the weekly retry
  (live unlinked ISINs only, retries after a failed OpenFIGI call in the same process), profile
  ticker sets (weekly pass, on-demand refresh, live accounts only)
- `tests/integration/accounts/analysis.rs` — pyramid (equity by default, override wins, holdings
  win, shared, foreign currency, hidden out, daily cash on the resolved tier), projection plan
  (equity by default, overridden tier), diversification (one line spread by its profile, merges
  with a held line and takes its name, unresolved is unclassified, override out of equity leaves,
  overdrawn adds no line)
- `tests/integration/accounts/reads.rs` — `dashboard_totals_ignore_the_tracked_fund`
- `tests/integration/sync/enable_banking/tracked.rs` — a resync keeps the ISIN
- `src/accounts/tracked_fund.rs`, `src/ledger/instrument.rs` — tier default and override, check
  digit

## Links

- Related ADR: [How Picsou names a fund the provider does not identify](../decisions/2026-11-25-tracked-fund-isin.md)
- Related: [Wealth pyramid](./wealth-pyramid.md), [Portfolio diversification](./portfolio-diversification.md)
- Related: [ISIN-keyed lookups and justETF](../decisions/2026-08-15-isin-keyed-lookups-and-justetf.md)
- Ticket: #685
