# Feature: DEGIRO sync

> Last updated: 2026-08-05
> Status: ✅ **Active — validated end-to-end against a live DEGIRO account**
> (login incl. TOTP, portfolio sync, holdings resolution). Wired into both
> the DEGIRO tab in `SyncPage` and the unified "Add account" modal
> (`AddAccountModal` → `DegiroPanel`, mirroring `BourseDirectPanel`).
> `degiro-auth` must be enabled in `docker-compose.yml` (uncommented) to run.
> See "Known limitations" below for the couple of things still worth
> double-checking, and "Fixed during live testing" for what a first real
> account run actually caught.

## Context

DEGIRO is a compte-titres-only broker (no PEA envelope in France) with no
official public API. Picsou imports its valuation and open positions through
`services/degiro-auth`, an isolated FastAPI sidecar that speaks DEGIRO's
unofficial, reverse-engineered trading API — the same pattern already used for
Trade Republic and Bourse Direct.

## How it works

### Architecture

```text
Client → DegiroController → DegiroSyncService → DegiroPort
      → DegiroAdapter → degiro-auth (FastAPI, httpx — no browser needed) → trader.degiro.nl
```

Unlike `tr-auth`/`bourse-direct-auth`, `degiro-auth` does **not** use
Playwright: DEGIRO's undocumented API is JSON-over-HTTPS with a session
cookie, closer in shape to BoursoBank's `httpx`-only sidecar than to a
browser-automation one.

### Auth flow

1. `POST /api/degiro/auth/initiate {username, password}` → sidecar attempts
   `POST /login/secure/login`. If DEGIRO requires TOTP (the common case),
   responds `{totpRequired: true, processId}`.
2. `POST /api/degiro/auth/complete {processId, code}` → sidecar retries with
   `POST /login/secure/login/totp` plus the 6-digit code, then
   `GET /pa/secure/client` to resolve `intAccount`. Returns an opaque
   `{sessionId, intAccount}` blob, which Java encrypts via `CryptoEncryption`
   into `DegiroSession.sessionBlob` — Java never parses this blob's contents.

### Session lifetime — the one thing genuinely different from other integrations

DEGIRO's session times out after ~30 minutes of inactivity and there is no
refresh token, unlike TR/Bourse Direct/Bourso sessions which last days to
weeks. Rather than storing the account's TOTP *secret* to re-authenticate
unattended (a materially bigger trust step than a session cookie — see the
[session-only ADR](../decisions/2026-08-05-degiro-session-only-no-stored-totp.md)),
Picsou:

- Never schedules a background resync for DEGIRO — `DegiroSyncService` is not
  wired into `SchedulerService.dailyBankSync`. Sync is manual only, from the
  DEGIRO tab's "Synchronize" button.
- Flips `DegiroSession.status` to `REAUTH_REQUIRED` when a sync call meets an
  expired session (`DegiroPort.fetchPortfolio` throws `DegiroSessionExpiredException`
  on an upstream 401), instead of retrying or failing silently. The UI surfaces
  this as a reconnect prompt, not an error.

  That write goes through `DegiroSessionStatusWriter`, a separate bean whose
  method is `@Transactional(REQUIRES_NEW)`, and **not** by mutating the managed
  entity: `DegiroSyncService` is `@Transactional` and rethrows after flipping the
  status, which marks its transaction rollback-only. An in-transaction write would
  therefore be discarded, the stored status would stay `ACTIVE`, and every later
  sync would sail past the `REAUTH_REQUIRED` guard in `sync()` and call the sidecar
  again instead of prompting the user. Same reasoning and same shape as
  `IbkrStatusWriter` — it has to be a distinct bean, since a `REQUIRES_NEW` method
  called via `this` would not cross the Spring proxy.

  The expiry condition is carried by a dedicated exception type rather than a
  `"SESSION_EXPIRED"` message string, so a reworded message can't silently break
  the transition and the internal marker never reaches a user-facing error.

### Sync scope (v1)

Account valuation (cash in EUR) + open positions only — one `Account`
(`type = COMPTE_TITRES`, `provider = "DEGIRO"`, fixed `externalAccountId =
"degiro-portfolio"`, one per member) and its `AccountHolding` rows. Order and
transaction history are deliberately out of scope, same exclusion Bourse
Direct made — a user who wants historical trades can backfill them through the
generic CSV importer ([csv-transaction-import.md](csv-transaction-import.md))
using DEGIRO's own transaction export.

Position ISIN resolution reuses the existing `OpenFigiIsinConverter` pipeline
(shared with manual transactions, CSV import, Bourso) — the sidecar resolves
DEGIRO's internal `productId` to an ISIN via
`POST /product_search/secure/v5/products/info` before Java ever sees the
position. Holdings that resolve to the same ticker are merged with
`HoldingDedup.vwapMerge` (shared with Bourso/IBKR/TR).

### Key files

- `services/degiro-auth/main.py` — FastAPI sidecar: `/initiate`, `/complete`, `/portfolio`, `/health`
- `services/degiro-auth/portfolio_parser.py` — dependency-free parsing of DEGIRO's
  `{"value": [{"name", "value"}, ...]}` row shape, unit-tested in isolation
  (mirrors `services/bourse-direct-auth/portfolio_parser.py`)
- `backend/src/main/java/com/picsou/port/DegiroPort.java` — port interface
- `backend/src/main/java/com/picsou/adapter/DegiroAdapter.java` — `WebClient` calls to the sidecar
- `backend/src/main/java/com/picsou/service/DegiroSyncService.java` — auth orchestration, sync, `REAUTH_REQUIRED` handling
- `backend/src/main/java/com/picsou/service/DegiroSessionStatusWriter.java` — commits the `REAUTH_REQUIRED` flip in its own transaction
- `backend/src/main/java/com/picsou/exception/DegiroSessionExpiredException.java` — typed expiry signal between adapter and service
- `backend/src/main/java/com/picsou/controller/DegiroController.java` — REST endpoints under `/api/degiro/`
- `backend/src/main/java/com/picsou/model/DegiroSession.java`, `DegiroSessionStatus.java`
- `backend/src/main/resources/db/migration/V71__degiro_session.sql`
- `frontend/src/pages/sync/DegiroTab.tsx` — the DEGIRO tab in `SyncPage`
- `frontend/src/components/sync/DegiroPanel.tsx` — same auth/sync flow, compact form for the unified "Add account" modal (mirrors `BourseDirectPanel.tsx`)
- `frontend/src/components/shared/AddAccountModal.tsx` — `degiro` wizard step, renders `DegiroPanel`
- `frontend/src/features/sync/{api,hooks}.ts` — `degiroApi`, `useDegiroSessionStatus` etc.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| `httpx`-only sidecar, no Playwright | DEGIRO's unofficial API is plain JSON-over-HTTPS; no browser fingerprinting known to be required | Playwright, matching TR/Bourse Direct |
| Session-only storage, no stored TOTP secret | See [ADR](../decisions/2026-08-05-degiro-session-only-no-stored-totp.md) | Storing the TOTP secret for unattended daily sync |
| No scheduled background resync | Session lifetime (~30 min) makes a daily job pointless without a stored secret | Wiring into `SchedulerService` like every other integration |
| Single fixed-ID account per member | DEGIRO's unofficial API exposes one portfolio per login | Per-sub-account modeling (not exposed by the API used here) |
| Sidecar resolves ISIN before Java sees positions | Reuses the sidecar-owns-provider-quirks pattern; Java stays a typed contract | Passing raw `productId` to Java and resolving there |

## Known limitations

- **The daily snapshot is taken after the holdings are replaced.** The 3-arg `AccountService.upsertSnapshot` derives the day's `investedAmount` from the holdings in the table; taken before `deleteByAccountId` + re-insert it costed today's snapshot with the previous sync's positions. Same rule in `TradeRepublicSyncService`.
This integration has had a first live smoke test (login, TOTP, portfolio
fetch, account/holding upsert all completed successfully), which confirmed the
overall design but surfaced real DEGIRO API quirks the original
public-reference-based guesses got wrong or missed entirely:

**Confirmed during live testing:**

- **DEGIRO's `portfolio.value` rows are not all real instruments.** A cash
  sub-position (`id: "FLATEX_EUR"`, the EUR balance held at DEGIRO's partner
  bank Flatex) is mixed in among real security rows. `parse_raw_positions`
  now filters to numeric product ids only (`is_real_product_id`) — DEGIRO's
  `cashFunds` rows already carry that balance, so including it as a
  "position" would have double-counted cash as a phantom holding. This one
  bad id previously crashed `_fetch_product_info` for the **entire** batch
  (a single `int(pid)` call outside a try/except), silently losing ISIN/name
  resolution for every position in the sync, not just the cash row — now
  handled per-item so one bad id costs only that id.
- **DEGIRO's product-info endpoint returns the literal string `"NULL"`** for
  a missing `isin`/`symbol` field on at least one real observed instrument,
  rather than omitting the key or returning JSON `null`. `sanitize_product_info`
  now treats `"NULL"` (any case) and blank strings as absent.
- **Product info has no `breakEvenPrice` field at all** — a real captured
  response (ETF and stock entries) confirmed the field set is `id`, `name`,
  `isin`, `symbol`, `contractSize`, `productType(Id)`, `tradable`, `category`,
  `currency`, `active`, `exchangeId`, `onlyEodPrices`, order types, `closePrice`
  + `closePriceDate`, `isShortable`, `feedQuality`, `vwdId`/`vwdModuleId` — no
  average-cost field anywhere. Average cost (`breakEvenPrice`) actually lives
  on the **portfolio row itself** (`parse_raw_positions` now reads it from
  there), and current price now prefers product info's `closePrice` over the
  portfolio row's own `price` field, which is closer to DEGIRO's own notion of
  a reference price for `onlyEodPrices`-flagged instruments.
- **The actual root cause of every "NULL" ticker/name seen in testing, found
  after the two fixes above still didn't clear it**: `_fetch_product_info`
  keyed its returned map by `int(pid)`, while `build_positions` (and
  `parse_raw_positions`, which produces `productId`) always deals in strings —
  JSON object keys are strings, and DEGIRO's response itself confirmed
  perfectly clean data (valid ISIN, symbol `IB01`, `closePrice` 121.36) for
  the exact holding that still showed up as `ticker=NULL, name=15690087` in
  Postgres. The int/string mismatch meant `products.get(p["productId"])`
  *always* missed, regardless of how good the upstream response was — a type
  bug in the sidecar's own glue code, not a DEGIRO quirk. Root-caused by
  correlating exact sidecar-restart and Java-persist timestamps with a direct
  Postgres query, which also ruled out stale pre-fix holdings surviving a
  resync (`DegiroSyncService` deletes all holdings before reinserting, so a
  holding's `last_synced_at` matching the fresh sync's timestamp proves it
  came from *that* run). Fixed by extracting the map-building step into a pure,
  unit-tested `build_product_info_map()` in `portfolio_parser.py` instead of
  leaving it inline in `main.py`, with an end-to-end regression test
  reproducing this exact case.
- **`Account.currentBalance` was set to cash only, not cash + positions.**
  Confirmed live: the dashboard total matched exactly the sum of position
  values and silently excluded the cash sitting in the account (~1,700€ on
  the test account) — Bourse Direct's `balanceEur`/`cashBalance` split showed
  this was wrong: `currentBalance` must be the *total* account value.
  `DegiroSyncService.upsertAccount` now computes
  `totalValueEur = cashEur + Σ(quantity × currentPrice)` and sets
  `Account.currentBalance` to that total while `Account.cashBalance` keeps
  just the cash portion, matching every other broker integration's contract.
- **Some holdings resolve to a Yahoo ticker with no live quote** (shows as a
  missing "Valeur" rather than a wrong one) — a pre-existing, shared
  limitation of `OpenFigiIsinConverter.pickBest()` (used by every
  ISIN-resolving integration, not just DEGIRO), surfaced here because so many
  DEGIRO holdings are Irish/Luxembourg-domiciled UCITS ETFs. An exchange-
  priority reordering was tried and **reverted** the same day — it fixed one
  holding but broke two others on this same test portfolio. Full account in
  [ISIN_TO_TICKER_CONVERSION.md](./ISIN_TO_TICKER_CONVERSION.md) — this
  remains a known, accepted gap, not something DEGIRO-specific to fix.

**Still unconfirmed / open:**

- The exact JSON shape DEGIRO returns when 2FA is required. Live testing saw
  two different unanticipated shapes on the same `/login/secure/login` call
  across attempts: an HTTP 200 with a session cookie that turned out not to
  be fully authenticated yet (no account behind it), and a plain **HTTP 202**
  the original code didn't handle at all. `_login()` now logs the full
  response (status, cookie presence, JSON body) on every call; login has
  since succeeded reliably in later attempts, but the 202 case itself was
  never definitively root-caused.
- The exact request/response envelope of `/product_search/secure/v5/products/info`
  beyond the `isin`/`symbol`/`name`/`closePrice` fields read so far (confirmed
  live — see the `closePrice`/`breakEvenPrice` entry above).
- Whether other non-numeric pseudo-position ids besides `FLATEX_EUR` exist
  (e.g. for other currencies or account types) — `is_real_product_id`'s
  numeric-only filter should catch any of them the same way, but none besides
  `FLATEX_EUR` have been observed yet.

These remaining items are minor relative to the confirmed end-to-end success —
the status banner at the top of this file already reflects that — but worth
keeping an eye on across future syncs, especially the 2FA response shape.

## Tests

- `services/degiro-auth/test_portfolio_parser.py` — pure parsing logic
  (value-pair flattening, cash/position extraction, product-info merge with
  graceful fallback, `FLATEX_EUR`-style pseudo-position filtering, `"NULL"`
  literal sanitization, `closePrice`/`breakEvenPrice` sourcing, and an
  end-to-end regression test for the int/string product-id key mismatch that
  caused every "NULL" ticker seen live), run with `python3 -m unittest` —
  30/30 passing.
- `backend/src/test/java/com/picsou/service/DegiroSyncServiceTest.java` —
  auth flow, sync upsert + holding dedup, expired-session → `REAUTH_REQUIRED`
  transition (asserted through `DegiroSessionStatusWriter`, since an
  in-transaction write would be rolled back by the rethrow), the non-expiry
  failure path leaving the status alone, and the status/clear endpoints. Run
  with `mvn test -Dtest=DegiroSyncServiceTest` — 10/10 passing. The full
  backend suite (`mvn test`) is the CI gate; no absolute count is recorded here
  because it drifts with every unrelated PR.
- Frontend: `tsc --noEmit` and `eslint .` both clean; no dedicated
  `DegiroTab.test.tsx` yet (matching `BoursoTab`'s own untested precedent).
- First live smoke test completed (real account, real 2FA) — login, sync, and
  account/holding upsert all completed end-to-end. See "Known limitations"
  above for what that test surfaced.

## Links

- ADR: [Session-only, no stored TOTP secret](../decisions/2026-08-05-degiro-session-only-no-stored-totp.md)
- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related ADR: [Bourse Direct isolated sidecar](../decisions/2026-07-21-bourse-direct-isolated-atomic-sync.md) (the "fail closed, never overwrite last known good" discipline this integration follows)
- Sibling integration: [bourso-bank.md](bourso-bank.md) (closest architectural analog — `httpx`-only sidecar, no browser)
