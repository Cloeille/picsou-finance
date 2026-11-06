# Feature: Trade Republic Sync

> Last updated: 2026-11-03 (sessions move to the shared `sidecar_session` table; in-app confirmation login)

## Context

Trade Republic is a German neobroker popular in France. Picsou syncs portfolio balances and holdings via TR's unofficial WebSocket API. Authentication requires a Python sidecar (`tr-auth`) because TR uses an AWS WAF challenge that cannot be solved from plain Java HTTP. A CSV import fallback exists for when the automated sync is unavailable.

## How it works

### Authentication (delegated to the `tr-auth` sidecar)

`TradeRepublicAdapter` calls three endpoints of the sidecar through the shared `SidecarClient`, which keeps the sidecar's `{"detail": CODE}` as the `SyncException` code:

1. **`POST /initiate`** -- Sends phone number + PIN. Returns `{processId, flow, countdownInSeconds}`. `flow` is `confirmInApp` (the member confirms in the Trade Republic app) or `otp` (TR fell back to a 4-digit code).
2. **`POST /complete`** -- Sends `processId` and `tan` (`null` for `confirmInApp`). Blocks while the member confirms, up to 120 s in the sidecar and 150 s in the adapter, then returns an opaque `sessionState`. The adapter always sends the `tan` key, with an explicit `null`, because the sidecar forbids unknown fields but declares it.
3. **`POST /session`** -- Sends the stored `sessionState`. Returns `{sessionToken, claims}`: a live `tr_session` cookie value and the decoded claims, which list the securities and cash accounts in `act.acc.owner`. The refresh cookie is not rotated, so the stored state never changes.

Phone number and PIN are never stored: they are used for the `/initiate` call and dropped. The state holds only the refresh cookie, the device cookie and the subject.

Failures keep the code the sidecar declared, restricted to `SidecarBank.TRADE_REPUBLIC`'s list: `INVALID_CREDENTIALS`, `INVALID_OTP`, `APP_VALIDATION_TIMEOUT`, `APP_VALIDATION_CANCELLED`, `AUTH_ATTEMPT_EXPIRED`, `SESSION_EXPIRED`, `INVALID_SESSION_STATE`, `RATE_LIMITED`, `UPSTREAM_FORMAT_CHANGED`, `INVALID_DATA`, `UPSTREAM_UNAVAILABLE`, `INTERNAL_ERROR`. A code outside the list, and a timeout, become `UPSTREAM_UNAVAILABLE`. A bare 401 means `INVALID_CREDENTIALS` on `/initiate`, `INVALID_OTP` on `/complete` and `SESSION_EXPIRED` on `/session`; a bare 410 means `AUTH_ATTEMPT_EXPIRED`.

`POST /api/tr/auth/complete` blocks for as long as the confirmation takes, so `docker/nginx.conf` gives it its own `proxy_read_timeout 180s`, like the CIC login.

Frontend auth state deliberately separates initiation failures from verification
failures:

- If `POST /tr/auth/initiate` fails, the phone/PIN form stays visible, the
  pending TAN and process id are cleared, and the user sees the inline error.
  The UI must not advance to the confirmation step because no valid
  `processId` exists.
- If `POST /tr/auth/complete` fails, the confirmation step stays visible and the
  current `processId` is kept so the member can retry (the code, for the `otp` flow)
  without re-entering phone/PIN.

### Session persistence

Sessions live in the shared `sidecar_session` table (bank `TRADE_REPUBLIC`), like the other sidecar banks; see the [ADR](../decisions/2026-11-02-one-sidecar-session-table.md). `V130__trade_republic_sidecar_session.sql` widens the table's two CHECK constraints with the Trade Republic branch and drops `trade_republic_session`.

**Members reconnect Trade Republic once after this upgrade.** The old rows are not copied: their refresh token only worked with the v1 refresh route, which Trade Republic now answers with 401. The member sees the connection as needing re-authentication and confirms once in the app.

`TradeRepublicSyncService` builds a `SidecarSyncTemplate` with a `Strategy`:

- `completeAuth()` calls `/complete`, then `replaceAndQueue` stores the encrypted `sessionState` (AES-256-GCM through `CryptoEncryption`, see [encryption-at-rest.md](./encryption-at-rest.md)), lifts the soft-delete tombstones on the member's TR accounts in the same transaction, and queues the first import on the `tradeRepublicSyncExecutor` single-worker executor. It returns a `SessionStatusResponse` straight away.
- A job runs in three short transactions fenced on the session row. `fetch` calls `/session`, then reads the portfolio over the WebSocket, outside any transaction. `commit` runs `upsertAccount` for each account inside the job's transaction.
- `SESSION_EXPIRED` and `INVALID_SESSION_STATE` end the session: it is kept but inactive, the member sees "reconnect". Any other failure (`UPSTREAM_UNAVAILABLE`, `RATE_LIMITED`...) leaves it active so the next sync retries.
- A backend restart turns `QUEUED`/`RUNNING` rows into a retryable failure: `TradeRepublicSyncService` is one of the services `SidecarSyncRecovery` runs at boot.

`POST /api/tr/sync` queues an import and returns the status; `GET /api/tr/status` and `DELETE /api/tr/session` read and delete the row. All of them answer `{isActive, syncStatus, lastSyncStartedAt, lastSyncCompletedAt, lastSyncError}`, except the delete (204) and the two auth steps.

### Data fetching (WebSocket, no sidecar)

The `TradeRepublicAdapter.fetchAccounts()` connects directly to `wss://api.traderepublic.com/` (protocol version 31) using `ReactorNettyWebSocketClient`. The handshake carries `Cookie: tr_session=<sessionToken>` from `/session`; the token is still passed in each subscription payload. An auth error frame on the stream means TR refused a token the sidecar had just minted, which says nothing about the stored session, so it is reported as `UPSTREAM_UNAVAILABLE` and the session stays active. The adapter:

1. Sends a `connect` message with locale, platform info, and client version.
2. Subscribes to `availableCash` (cash balance) and `compactPortfolioByType` (list of positions grouped by category, with `isin`, `netSize`, `averageBuyIn`). One subscription per `secAccNo` from the JWT.
3. For each distinct ISIN, subscribes to `instrument`, then to `ticker` with id `ISIN.<venue>` once that answer arrives, to get the live market price. A ticker id needs the venue, and `compactPortfolioByType` names none. The venue comes from the instrument answer's `exchangeIds`: `LSX` (Lang & Schwarz Exchange, TR's home venue for equities/ETFs) when listed, otherwise the first listed venue, and `LSX` again when the answer lists none or is an error frame. A wrong venue makes TR reject the ticker (`FORBIDDEN`). Guessing it (`LSX`, then `TRD0` for TR-native crypto `XF000...`) left every crypto position on its buy-in (GH #23, #268). Positions with `instrumentType: "privateFund"` (private equity funds) are skipped — TR never sends a price tick for these non-publicly-traded assets, and subscribing would add a ticker that never answers, holding the read open until the 30s cap (`WS_WAIT_CAP`).
4. Computes portfolio value as `sum(ticker.last.price * position.netSize)`, counting a position TR sent no price for at `averageBuyIn * netSize` so the account total does not drop it.
5. Extracts secAccNo (securities account numbers) from the `claims` object `/session` returned, falling back to the payload of the token when it is a JWT, to handle multiple sub-portfolios. The normal brokerage account is exposed under `act.acc.owner.default`; French PEA accounts are exposed under `act.acc.owner.tax_wrapper_fr`.
6. Builds `TrPosition` records from `positionsByIsin` map: each position includes ISIN, quantity (netSize), averageBuyIn, and currentPrice from the ticker. `currentPrice` is null when TR sent no price (a refused ticker, a private fund, a subscription still silent at the cap), logged at WARN as `TR price unavailable`, so the holding shows no price rather than its buy-in posing as a market price.

Returns a list of `TrAccountData` records: one securities account per TR wrapper (`TR Titres` as `COMPTE_TITRES`, `TR PEA` as `PEA`) and one cash account (`TR Cash`) for the default cash pocket. The PEA cash pocket is scoped with `availableCash(accountNumber=...)` and added to the `TR PEA` balance instead of being shown as a separate checking account.

### Holding deduplication

When persisting holdings, multiple ISINs can convert to the same Yahoo Finance ticker symbol (e.g., different listings of the same security). Before inserting into the database, `upsertAccount()` deduplicates holdings by ticker via the shared `HoldingDedup::vwapMerge` helper — quantities are summed and `averageBuyIn` is the quantity-weighted average. This prevents `DataIntegrityViolationException` on the `(account_id, ticker)` unique constraint AND keeps the cost basis mathematically correct. See [trade-republic-holding-deduplication.md](./trade-republic-holding-deduplication.md) for the VWAP formula and gotchas.

The WebSocket sync treats the returned position list as authoritative. Existing
holdings for a TR account are deleted and recreated on every WebSocket sync; if a
portfolio is returned with an empty position list, stale holdings are cleared. The
CSV fallback imports balances only and therefore does not replace holdings.

### Broker valuation fallback (`provider_value_eur`)

Each persisted holding also stores TR's own EUR valuation of the position in
`provider_value_eur`, with `quote_currency = "EUR"`. The value is
`currentPrice × quantity`, falling back to `averageBuyIn × quantity` when TR's
ticker stream returned no live price — mirroring exactly how `TradeRepublicAdapter`
already builds the account-level `TrAccountData.balanceEur`, so the sum of the
holdings agrees with the **securities subtotal** of that figure by construction.
When several ISINs deduplicate to one ticker, this value is the sum of their
individually rounded broker values rather than the aggregate quantity multiplied
by the first position's price. This preserves the broker subtotal even when the
merged positions carry different live prices.
For a compte-titres that subtotal *is* the account total; for a PEA the total also
includes a scoped cash amount Picsou never persists, so the holdings sum falls
short of `current_balance` by exactly that cash. This is why `V64` can only
backfill accounts that reconcile, and why PEAs are left to self-heal on sync.

This exists because `AccountService.liveBalanceEur` re-values holdings from Yahoo
and **drops** any it cannot price, while the invested side keeps their full cost
basis — an asymmetry that fabricates a loss (GH issue #76). TR positions Yahoo
cannot resolve (unmappable ISINs, thin US OTC listings for Irish/Luxembourg UCITS
ETFs — see [ISIN_TO_TICKER_CONVERSION.md](./ISIN_TO_TICKER_CONVERSION.md) and GH
issue #78) now fall back to this broker figure instead of vanishing.

Treating TR quotes as EUR is not a new assumption: the adapter already sums them
unconverted into `balanceEur`. Writing `quote_currency` makes that assumption
explicit in the schema rather than implicit in the adapter, which is what the
[FX-conversion ADR](../decisions/2026-05-19-yahoo-fx-conversion.md) asked for when
it rejected using the untagged `current_price` column as a fallback.

### CSV import fallback

`TradeRepublicSyncService.importCsv()` parses a CSV file with columns `name,type,balance`. Accounts are deduplicated via a stable external ID derived from the name (`tr_csv_` prefix + slugified name).

### CSV transaction import

`TradeRepublicSyncService.importTransactionsCsv()` parses the full Trade Republic transaction history CSV (exported from the TR app). It implements a **double-entry** pattern to reflect real cash flows without creating holdings positions:

| Row type | `account_type` | Action |
|----------|---------------|--------|
| `TRADING` **BUY** on PEA | `PEA` | **Cash leg**: `WITHDRAWAL` `−amount` on TR Cash + **Investment leg**: `DEPOSIT` `+amount` on TR PEA |
| `TRADING` **SELL** on PEA | `PEA` | **Cash leg**: `DEPOSIT` `+amount` on TR Cash + **Investment leg**: `WITHDRAWAL` `−amount` on TR PEA |
| `TRADING` on CTO | `DEFAULT` + category `TRADING` | Same pattern but the investment leg targets TR Titres |
| `CASH` (Saveback, interest, card, transfers from main bank) | `DEFAULT` | Single `DEPOSIT` or `WITHDRAWAL` on TR Cash |
| `TRANSFER_IN` or `TRANSFER_OUT` | any | **Ignored** — these are TR's internal Cash↔PEA accounting; the double-entry above covers the same movement without duplication |

The two legs are strict mirrors: the investment leg always carries the **opposite sign** of the cash leg (`amount.negate()`), so the same €X moves out of cash and into investments (or vice-versa).

**Cashflow / allocation treatment**: a securities purchase is treated as a real outflow (an "investment purchase"). The **cash leg** is booked under an `Investissement` **EXPENSE** category (slug `investissement-titres`), so `CashflowService` counts it as spending (and a sale as an inflow). That category is *not* part of the default seed set — it is created on demand the first time a member imports TR trades (existing members are never re-seeded), so members who never use this feature don't get an extra category. To avoid counting the same movement a second time (as income), the **investment leg** is tagged with the member's seeded `investissement` category (kind `TRANSFER`), which excludes it from cashflow while still feeding `AllocationService` — it reads the positive investment `DEPOSIT` as an investment contribution. This removes the earlier **double-counting** where both legs landed in the cashflow totals. `CASH` rows stay uncategorized and count as ordinary income/expense by sign.

**Deduplication**: every row produces external IDs suffixed with `_cash` and `_inv`. `TransactionRepository.existsByAccountIdAndExternalId()` guards both, backed by the `(account_id, external_id)` partial unique index (`V33`). Re-importing the same CSV is safe — already-present rows are skipped and counted.

**No positions created**: the import touches only the `transaction` table. Account holdings continue to be managed by the WebSocket sync or by manual BUY/SELL transactions. The imported legs use `tx_type` `DEPOSIT`/`WITHDRAWAL` (never `BUY`/`SELL`), so `HoldingComputeService` — which recomputes positions from `BUY`/`SELL` rows — ignores them.

**Balance ownership**: the TR Cash balance stays owned by the sync — the import writes ledger rows but never overwrites `currentBalance`. More generally, `ManualTransactionService` now recomputes a cash account's balance/history from its transactions **only for manual accounts** (`refreshManualCashBalance`). For a synced account the balance and snapshots come from the connector; recomputing them from this (deliberately partial — `TRANSFER_IN/OUT` excluded) ledger would corrupt the balance the next time a manual transaction is added.

**Endpoint**: `POST /api/tr/accounts/{id}/transactions/import-csv` (multipart `file` param). The `{id}` is validated to belong to the authenticated member. Returns `{ inserted: N, skipped: M }`.

**Frontend**: on the **TR Cash** (CHECKING) account detail page, the **"+ Ajouter CSV (TR)"** button appears next to the manual-transaction button. After upload, a sonner toast shows the count of inserted rows and, if some were already present, the skip count.

### Scheduled sync

`MemberSyncService` calls `TradeRepublicSyncService.resyncReporting()` for the daily run. It goes through `SidecarSyncTemplate.resyncReporting`: no session reports "not connected", an inactive or expired one reports "needs re-authentication", otherwise an import is queued. The daily run needs no refresh step of its own: every job opens a fresh cookie through `/session`.

### Key files

- `backend/src/main/java/com/picsou/adapter/TradeRepublicAdapter.java` -- WebSocket data fetching + sidecar auth calls; nests the `TrInitiate`, `TrSession`, `TrAccountData`, `TrPosition` records
- `backend/src/main/java/com/picsou/service/TradeRepublicSyncService.java` -- Auth flow, `SidecarSyncStrategy`, CSV import, session management
- `backend/src/main/java/com/picsou/controller/TradeRepublicController.java` -- REST endpoints under `/api/tr/`
- `backend/src/main/java/com/picsou/model/SidecarSession.java` -- the shared session entity (bank `TRADE_REPUBLIC`)
- `backend/src/main/resources/db/migration/V130__trade_republic_sidecar_session.sql` -- CHECK constraints + drop of the old table

### Flow

```
User triggers auth
        |
TRController.initiateAuth() --> TRAdapter.initiateAuth()
        |                         sidecar POST /initiate (phone+PIN)
        |                         <-- processId, flow, countdownInSeconds
        v
Member confirms in the TR app (or types the 4-digit code)
        |
TRController.completeAuth() --> TRSyncService.completeAuth()
        |                         sidecar POST /complete (processId, tan|null)  [blocks <= 150 s]
        |                         <-- sessionState
        |                         SidecarSyncTemplate.replaceAndQueue (store + lift tombstones + queue)
        |                         <-- SessionStatusResponse
        v
  tradeRepublicSyncExecutor, SidecarSyncTemplate:
        fetch:  sidecar POST /session --> sessionToken + claims
                WebSocket (Cookie: tr_session): connect -> availableCash
                  -> compactPortfolioByType (per secAccNo) -> instrument -> ticker ISIN.<venue>
        commit: upsert accounts + holdings (job transaction)

MemberSyncService (daily) --> TRSyncService.resyncReporting() --> same job when the session is usable
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Python sidecar for auth | TR sits behind an AWS WAF challenge; the sidecar solves it and owns the login | Headless Java HTTP (blocked by WAF) |
| WebSocket for data | No WAF on the WS endpoint; direct Java access works; real-time price data | REST API scraping (would also hit WAF) |
| One `sidecar_session` row per member (bank `TRADE_REPUBLIC`) | Same queue / run / commit / recover cycle as the other banks, enforced by `SidecarSyncTemplate` and a per-bank CHECK | A private `trade_republic_session` table and a hand-written sync cycle |
| CSV import fallback | When tr-auth is down or session expired, user can manually export and import | No fallback (bad UX) |
| Protocol version 31 hardcoded | TR protocol is undocumented and reverse-engineered; pinning avoids silent breakage | Dynamic version negotiation (not possible) |
| Input validation with `@NotBlank` on DTOs | Prevents null phone/PIN from reaching Map.of() constructor, which rejects nulls and throws NullPointerException | Try-catch in adapter (less clear, reactive) |
| User-friendly error messages on frontend | HTTP errors from sidecar are technical and nested JSON; frontend parses HTTP status + error codes to show user-friendly messages in both FR and EN | Display raw API error (confusing to users) |
| TrPosition record in port interface | Encapsulates position data (ISIN, quantity, prices) in a named record; returned within TrAccountData | Flatten positions into TrAccountData directly (less structured, harder to evolve) |
| Async background sync after auth | Auth returns once the import is queued; the job runs on `tradeRepublicSyncExecutor`. Frontend polls the status | Synchronous sync (blocks HTTP request 10-30s, bad UX) |
| `TransactionTemplate` for background sync | The executor thread has no Spring proxy/EntityManager; `SidecarSyncTemplate` opens the three short transactions | `@Async` (self-invocation bypasses proxy) |
| `holdingRepository.flush()` after delete | Hibernate may defer the DELETE, causing duplicate key on subsequent INSERT with same `(account_id, ticker)` | Rely on Hibernate flush ordering (unreliable) |

## Docker / deployment

The `tr-auth` sidecar builds on the shared `chromium` base (`services/base`, see [ADR 2026-10-21](../decisions/2026-10-21-shared-sidecar-base-image.md)): Python 3.12 slim bookworm, Playwright 1.64.1 and Chromium only, shared with the other browser sidecars. Its own Dockerfile adds only the `trauth` user and `main.py`. The two rules below now live in `services/base/Dockerfile` (Chromium in `/opt/playwright-browsers`, world-readable). The original size rationale is in [ADR 2026-04-25](../decisions/2026-04-25-tr-auth-sidecar-slim-image.md). Two ordering rules matter:

1. **Chromium system deps must be `apt-get install`-ed manually.** `playwright install --with-deps` would be simpler but fails on Debian bookworm because it tries to install Ubuntu-only font packages (`ttf-unifont`, `ttf-ubuntu-font-family`). The Dockerfile lists the working subset explicitly.
2. **`PLAYWRIGHT_BROWSERS_PATH` must be set BEFORE `playwright install chromium` runs**, so the browser lands in `/opt/playwright-browsers`, made world-readable for every sidecar user. Never `chown` it from a sidecar Dockerfile: that copies Chromium into a new layer per sidecar.

```dockerfile
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers
RUN pip install --no-cache-dir -r /opt/sidecar-base/requirements-chromium.txt \
    && playwright install chromium \
    && chmod -R a+rX /opt/playwright-browsers
```

Without rule 2, Playwright installs to `/root/.cache/ms-playwright/`, which the `trauth` user cannot read → every `/initiate` call throws an unhandled exception → FastAPI returns 500 with no useful error message.

`docker/docker-compose.yml` builds `services/tr-auth/Dockerfile` and forwards `APP_SIDECAR_API_KEY`. The sidecar refuses to start without it and answers every route except `/health` with a 401 `Picsou-Sidecar-Key` challenge unless the backend presents it; see [docker-deployment.md](./docker-deployment.md#sidecar-shared-secret--app_sidecar_api_key).

## Gotchas / Pitfalls

- **The daily snapshot is taken after the holdings are replaced.** The 3-arg `AccountService.upsertSnapshot` derives the day's `investedAmount` from the holdings in the table; taken before `deleteByAccountId` + re-insert it costed today's snapshot with the previous sync's positions, one sync late in every daily point. Same rule in `DegiroSyncService`.
- **The PEA's cash pocket reaches `Account.cashBalance`.** `TrAccountData.cashEur` carries the scoped cash that is already inside `balanceEur`; without it on the account, `valuation()` left the pocket out of the live value while the provider-valued total kept it, so the cash read as a gain or vanished depending on which path ran. Null for the CTO, whose cash is the separate `tr_cash` account. Null too when the pocket is unknown (no cash account to subscribe to, or its frame never came): the sync then keeps the last known pocket rather than writing an empty one, as it keeps the holdings on an error frame.
- **An error frame on the portfolio subscription is not an empty portfolio.** `<id> E {...}` (or a payload that does not parse) answers the subscription, so the sync's wait does not hang on it, but `TradeRepublicAdapter.shouldPersist` skips the account: persisting it as empty made the service delete every holding and snapshot the securities at zero, cash pocket kept, so a PEA looked like cash only until the next successful sync. The account keeps the previous sync's data and the warning names it. A genuinely emptied portfolio (an answer that parsed with no positions) still persists. Each subscription counts once toward the wait, whatever the number of frames it sends: an error or delta frame after an answer must not complete the wait while another account is still silent.
- **tr-auth must be running**: The Python sidecar must be accessible at `app.sidecars.tr-auth.url` (default `http://tr-auth:8001`). If it is down, `/initiate` and `/session` give up after 45 s with `UPSTREAM_UNAVAILABLE`.
- **Local Maven dev uses localhost**: `application-dev.yml` overrides `app.sidecars.tr-auth.url` to `http://127.0.0.1:8001` because `tr-auth` is a Docker-internal DNS name. When running the backend with `mvn spring-boot:run`, start the sidecar separately on port 8001.
- **tr-auth 500 = Playwright crash**: A generic 500 from the sidecar almost always means the Chromium browser could not launch. Check `PLAYWRIGHT_BROWSERS_PATH` is `/opt/playwright-browsers` and readable by `trauth` (see Docker section above). Run `docker logs <tr-auth-container>` to confirm.
- **Dockerfile changes need an explicit rebuild**: `docker compose up -d` does NOT rebuild existing images. After editing `services/tr-auth/Dockerfile`, run `docker compose build tr-auth && docker compose up -d tr-auth`. Symptom of a stale image: `docker compose ps` shows `tr-auth` with a SHA-only `IMAGE` column instead of a tagged name (`picsou-tr-auth` / `docker-tr-auth`), and the runtime error references a path that doesn't match the current Dockerfile (e.g. `/home/trauth/.cache/ms-playwright/` when the shared base sets `PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers`).
- **`tan` is optional**: `CompleteAuthRequest.tan` is `null` for the `confirmInApp` flow and 4 digits for `otp`; the sidecar validates it.
- **Initiation errors are not TAN prompts**: a failed `/tr/auth/initiate` call
  has no reliable `processId`, so frontend screens must keep the user on the
  phone/PIN step and clear any stale process id. Only `/tr/auth/complete` errors
  should keep the verification-code step visible for retry.
- **Frontend API field mapping**: Frontend sends `phoneNumber` and `pin` (not `phone` and `pin`). The API uses ISO field names; if frontend is updated, verify the DTO record field names match.
- **Error codes reach the frontend as `SyncException` codes**: `SyncException` carries one of the `SidecarErrorCode` names above; `GlobalExceptionHandler` maps it to an error response and the shared `formatTrAuthError()` (`frontend/src/lib/errors.ts`) turns it into a message. It is used by `TradeRepublicTab`, `AddAccountModal` **and** `SyncAllModal`. A new code needs a `SidecarBank.TRADE_REPUBLIC` entry, the CHECK branch in a migration and a frontend message.
- **WebSocket protocol is reverse-engineered**: The TR WebSocket API is undocumented. Raw responses are logged at INFO level. If TR changes the protocol, the adapter will break and need updating.
- **timeout-driven completion**: The WebSocket session completes when either all data is received (cash + all portfolios + all tickers) or 30 seconds have elapsed in total (`untilAnsweredOrCapped`). The bound is on elapsed time, not on the gap between two frames: answered tickers keep streaming deltas, so a gap timeout never fires while one position is ticking, and a single subscription TR never answers (a delisted share, a contingent value right) used to hold the read open until the 45s hard timeout failed the whole sync with nothing stored. The silent position falls back to `averageBuyIn`. An error on the stream (socket drop, a frame that fails to process) also ends the read and keeps the frames received so far, logged at WARN with the error type only.
- **TR WebSocket API breaks without notice**: Trade Republic removed `compactPortfolio` on 2026-06-21 (protocol v31) and replaced it with `compactPortfolioByType`. The JSON structure changed: positions are now nested under `categories[].positions[]` and use `isin` instead of `instrumentId`. Reference project for future breaks: [pytr-org/pytr](https://github.com/pytr-org/pytr) — they track TR API changes in commit history.
- **`compactPortfolioByType` requires `secAccNo`**: Unlike the old `compactPortfolio` which had a generic no-secAccNo fallback, `compactPortfolioByType` is always per-securities-account. If `secAccNos` is empty (no securities sub-account in the claims), the portfolio subscription is skipped entirely — only cash is returned.
- **Multiple sub-portfolios / PEA**: The adapter extracts wrapper-specific `secAccNo` values from the claims (`act.acc.owner.default.sec[]`) and subscribes to each one separately via per-account `compactPortfolioByType`. `default` maps to `TR Titres` (`COMPTE_TITRES`); `tax_wrapper_fr` maps to `TR PEA` (`PEA`). This avoids merging CTO and PEA holdings into a single securities account. If extraction fails, portfolio is skipped (no fallback subscription).
- **Bond prices are quoted in % of nominal**: For bond positions (`instrumentType` **or** the enclosing `categoryType` equal to `bond`, exact match, never a substring so bond ETFs/funds are not scaled), TR's ticker price is a percentage of the nominal (98.25 = 98.25 %), while `averageBuyIn` (0.8941) and `netSize` (559.19) are already per euro of nominal. The adapter divides the bond ticker price by 100 (`tickerPriceInEur`) so price, buy-in and quantity share one unit; otherwise a 500 EUR bond showed as ~55,000 EUR with a +10,888 % gain. Category detection records the ISIN without touching the position node, so the `privateFund` skip still reads the real `instrumentType`. **Currency fails closed**: the percentage only converts to a EUR price when the `instrument` answer names EUR; a non-EUR (e.g. USD Treasury) or unknown nominal leaves `currentPrice` null and the position is valued at `averageBuyIn`. The `bond` type string and the instrument currency field (`currencyId`/`currency`/`currencies[]`) are assumed, to be confirmed on a real payload. A sync corrects current holdings and today's snapshot. It does not rewrite earlier daily snapshots, so old net-worth history can retain the inflated value.
- **Holding deduplication by ticker (VWAP)**: Multiple ISINs can map to the same ticker. When syncing, holdings are deduplicated in-memory before insertion to avoid unique constraint violations. Quantities are summed and `averageBuyIn` is the quantity-weighted average (VWAP) via `HoldingDedup::vwapMerge`. The earlier "keep first averageBuyIn" approach was non-deterministic (HashMap iteration order) and produced wrong gain/loss percentages. See [trade-republic-holding-deduplication.md](./trade-republic-holding-deduplication.md).
- **Empty WebSocket portfolio clears holdings**: If TR returns a securities account
  with zero positions, the sync deletes old holdings for that account so a full sale
  does not leave stale securities visible.
- **A missing price stays missing**: When a ticker price is missing (ticker subscription timed out or failed), `TrPosition.currentPrice` is null and the adapter logs `TR price unavailable for <ISIN>` at WARN. The account total and `provider_value_eur` still count the position at `averageBuyIn`, so the balance does not drop it, but the stored current price is empty. Storing the buy-in there presented a refused crypto ticker as a market price with zero gain (GH #268).
- **Background sync is `SidecarSyncTemplate`'s job**: the executor thread runs outside Spring's proxy, so `@Transactional` has no effect there. The template opens the transactions; a new background path should be a strategy method, not a hand-rolled `TransactionTemplate` call. The CSV imports are plain `@Transactional` methods.
- **`holdingRepository.flush()` is required after delete**: `deleteByAccountId` does not guarantee immediate DB flush. Without an explicit `flush()` call before inserting new holdings, Hibernate may execute INSERT before DELETE, causing duplicate key violations on `(account_id, ticker)`.
- **SyncAllModal detects TR via accounts**: TR appears in the SyncAllModal when the user has any account with `provider === "Trade Republic"`, even without an active session. When the session is expired, clicking sync opens an inline phone + PIN form, then the in-app confirmation step. After successful auth, the backend sync runs in background and the frontend polls the status.
- **Ticker venue comes from TR's `instrument` answer, not a guess**: `compactPortfolioByType` gives no `exchangeId`. Hard-coded venues drifted twice (`TRX`, issue #23; `TRD0` for crypto, issue #268), each time leaving positions `FORBIDDEN` and on their buy-in. The adapter now sends one `instrument` subscription per distinct ISIN and subscribes the ticker on the venue the answer lists. `LSX` is preferred when listed because equities and ETFs are known to price there and the `exchangeIds` order is undocumented. The extra round trip happens before the ticker subscription, under the same 30s read cap.
- **Private equity funds (`privateFund`) are excluded from ticker subscriptions**: TR positions with `instrumentType: "privateFund"` are not publicly traded and TR never streams a price tick for them. The adapter checks `pos.path("instrumentType")` and skips the ticker subscription for these ISINs. They count at `averageBuyIn` in the account total and keep no current price. Without this skip, each one would add a ticker that never answers, so every sync would wait the full 30s cap (`WS_WAIT_CAP`) before going on.
- **Ticker completion counts distinct subscriptions, not messages**: A *successful* TR `ticker` subscription is a stream — an initial full state followed by continuous delta updates under the same `wsId`. The stream's `takeUntil` completes once every ticker subscription has answered, tracked as a set of answered `wsId`s (`answeredTickerSubs`), and only the first message per `wsId` is read (it carries the full state a sync snapshot needs). Counting raw messages instead let a fast-ticking position push the total to the expected count before slower positions had answered even once, closing the socket early and dropping their prices to the `averageBuyIn` fallback — the same symptom as issue #23. (The reference `pytr` client `unsub`s after the first message; we simply ignore later ones.)

## Tests

- `HoldingDedupTest` -- VWAP math + null handling + order independence (see [trade-republic-holding-deduplication.md](./trade-republic-holding-deduplication.md))
- `TradeRepublicSyncServiceTest#sync_mergesDuplicateTickersWithVwap` -- wiring test: two ISINs → same ticker → VWAP-merged `averageBuyIn` persisted
- `TradeRepublicSyncServiceTest#sync_deletesOldHoldingsWhenPortfolioReturnsEmpty` -- regression test: an authoritative empty WebSocket portfolio clears previous holdings
- `TradeRepublicSyncServiceTest#completeAuth_*`, `#sync_*` -- session stored encrypted, tombstones lifted, expired session deactivated, transient failure keeps it connected
- `TradeRepublicAdapterTest#initiateAuth_*`, `#completeAuth_*`, `#openSession_*` -- the sidecar's `detail` codes, bare-status fallbacks, explicit-null `tan`, the 150 s completion wait
- `TradeRepublicAdapterTest#fetchAccounts_*` -- against a fake TR WebSocket: the `tr_session` cookie on the handshake, accounts read from the claims, the ticker subscribed on the venue the `instrument` answer lists, `LSX` when it lists none, and a refused ticker leaves `currentPrice` null with the account total at cost (#268)
- `SidecarSyncContractTest`, `SidecarBankCodesTest`, `V130TradeRepublicSidecarSessionMigrationTest` -- the shared sync scenarios, the Java/SQL code lists, the migration
- `frontend/src/pages/sync/TradeRepublicTab.test.tsx` and `frontend/src/components/shared/AddAccountModal.test.tsx` -- frontend regression tests for initiation failure staying on phone/PIN and a failed confirmation staying on the confirmation step
- Manual integration testing against real TR accounts (auth flow, CSV import)

## Links

- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related ADR: [tr-auth as isolated sidecar with Chromium-only image](../decisions/2026-04-25-tr-auth-sidecar-slim-image.md)
- Related feature: [Encryption at rest](./encryption-at-rest.md)
- Related feature: [Docker deployment](./docker-deployment.md)
