# Feature: Finary Import

> Last updated: 2026-08-17

## Context

Finary is a French personal finance app. Picsou supports importing data from Finary via two methods: uploading an xlsx export file, or direct API sync using Finary credentials. Both methods use a two-phase flow (preview then execute) to let users review and map accounts before committing. Once accounts are mapped, a one-click auto-sync skips the mapping UI and syncs in the background, running daily via the scheduler.

## How it works

### Two import paths

**1. XLSX file import** (`FinaryImportService`)

The user exports their Finary data as an xlsx file and uploads it via the API. The file contains sheets per asset category (Checkings, Savings, Investments, Real Estate, Cryptos, Fonds Euro, Commodities, Credits, Other Assets, Startups) plus a Transactions sheet.

- **Preview**: `preview(MultipartFile)` parses the xlsx with Apache POI, extracts accounts and transactions, generates a UUID `fileToken`, stores parsed data in a `ConcurrentHashMap` cache, and returns account previews with suggested types and existing Picsou accounts for mapping.
- **Execute**: `executeImport(FinaryImportRequest)` retrieves cached data by `fileToken`, applies user mappings (SKIP / MAP_EXISTING / CREATE_NEW), creates accounts, reconstructs balance snapshots from transactions, and imports transactions.

**2. Direct API sync** (`FinaryApiSyncService`)

Authenticates directly with Finary via Clerk (their auth provider) and fetches accounts + transactions through the Finary API.

- **Authentication**: `FinaryApiClient.authenticate()` performs a 6-step Clerk OAuth flow: GET environment, GET client, POST sign_ins, (optionally POST TOTP), POST session touch, POST tokens. Returns a JWT for API calls.
- **TOTP/2FA handling**: When Clerk returns `needs_second_factor`, the backend throws `TotpRequiredException` → HTTP 403. The frontend detects 403 on the preview mutation, shows a TOTP input field, then retries with `?totp={code}` as query parameter.
- **Preview**: `preview(totp)` authenticates, fetches accounts from all 10 portfolio categories **plus the dedicated `/loans` endpoint** (loans are not exposed as a portfolio category), fetches transactions (paginated, 200 per page), caches everything with a `syncToken`, returns previews.
- **Execute**: `execute(syncToken, mappings)` retrieves cached data, applies user mappings, creates/updates accounts, imports holdings (positions) and transactions.

### Account mapping

Both paths present the user with a mapping screen where they choose for each Finary account:

- **SKIP** -- Ignore this account entirely.
- **MAP_EXISTING** -- Link the Finary account to an existing Picsou account (balance is updated).
- **CREATE_NEW** -- Create a new Picsou account with user-specified name, type, provider, and color.

Type suggestions are auto-computed from the Finary category via `FinaryPersistenceHelper.suggestTypeFromDisplayCategory()` or `suggestTypeFromApiCategory()`.

### Holdings (API sync only)

On execute / auto-sync, `FinaryHoldingsImporter` reads positions already nested on each account DTO:

| Finary list | Picsou |
|---|---|
| `securities` | holding; ISIN → OpenFIGI ticker, else ISIN / symbol / slug |
| `cryptos` | holding; ticker = crypto `code` (BTC, REG, RealT slug…) |
| `fonds_euro`, `generic_assets`, `scpis`, `precious_metals` | holding; ticker `FINARY_<sanitized name>` |
| `fiats` + "Solde Espèces" | `account.cash_balance`, not a holding |

Each holding stores Finary's EUR `display_current_value` / `display_unrealized_pnl` as `provider_value_eur` / `provider_pnl_eur`, so unlisted instruments still value on the dashboard. Account `currentBalance` is `display_balance` when Finary sends it (native `balance` on a USD wallet is USD).

### Cache and session management

- `FinaryImportService` uses a `ConcurrentHashMap` with 30-minute expiry (cleaned every 60s by `@Scheduled`).
- `FinaryApiSyncService` uses a `ConcurrentHashMap` with 10-minute expiry (cleaned every 60s by `@Scheduled`).
- Cache tokens are UUIDs. The preview+execute must complete within the TTL or the user must re-upload.

### Auto-sync

`FinaryApiSyncService.autoSync(memberId)` is the fast path when all Finary accounts are already known to Picsou (i.e., every account returned by the preview has a matching `externalAccountId` in the DB):

1. Runs the preview phase (authenticate + fetch).
2. Checks `autoMapped` flag: if all accounts have an `externalAccountId` match, auto-generates the `MAP_EXISTING` mappings and calls `execute()` directly.
3. If any new account is found: returns `{ status: "NEEDS_MAPPING" }` — the user must go through the mapping UI.
4. If Finary requires TOTP: sets `FinarySession.status = "TOTP_REQUIRED"` and returns gracefully.

**REST endpoint:** `POST /api/finary/api-sync/auto` → `FinaryAutoSyncResponse { status, accountsSynced, newAccountCount }`

Possible status values: `OK`, `NEEDS_MAPPING`, `TOTP_REQUIRED`, `NOT_CONNECTED`.

**Scheduler:** `SchedulerService.dailyBankSync()` calls `autoSync()` for each family member at 08:00 UTC. If it returns `NEEDS_MAPPING`, a warning is logged and the user must sync manually. If `TOTP_REQUIRED`, the session is flagged and the user must re-authenticate.

**Frontend:** The "Sync Finary" button in `FinaryTab` calls `POST /api/finary/api-sync/auto`. On `OK` it shows a success toast. On `NEEDS_MAPPING` it falls through to the full preview+mapping wizard. On `TOTP_REQUIRED` it shows the TOTP input and the user retries via the preview endpoint.

### Key files

- `backend/src/main/java/com/picsou/service/FinaryImportService.java` -- XLSX file import (Apache POI parsing, two-phase flow)
- `backend/src/main/java/com/picsou/finary/FinaryApiSyncService.java` -- Direct API sync (Clerk auth, two-phase flow, cache, `autoSync()`)
- `backend/src/main/java/com/picsou/finary/client/FinaryApiClient.java` -- Finary/Clerk HTTP client (6-step auth, TOTP, pagination, `fetchLoans()`)
- `backend/src/main/java/com/picsou/finary/dto/FinaryLoanDto.java` -- a loan/mortgage entry from the dedicated `/loans` endpoint
- `backend/src/main/java/com/picsou/exception/TotpRequiredException.java` -- Thrown when 2FA is required but no TOTP provided (returns 403)
- `backend/src/main/java/com/picsou/exception/FinaryServiceUnavailableException.java` -- Thrown when Clerk/Finary APIs are unreachable (network, timeout, DNS); returns 502
- `backend/src/main/java/com/picsou/finary/FinaryPersistenceHelper.java` -- Shared helper: account creation, snapshot reconstruction, transaction import (preserves manual transactions), type suggestion
- `backend/src/main/java/com/picsou/finary/FinaryHoldingsImporter.java` -- Maps Finary `securities` / `cryptos` / `fonds_euro` / generic assets onto `account_holding`, records leftover cash on `account.cash_balance`
- `backend/src/main/java/com/picsou/finary/dto/FinaryPositionDto.java` -- One Finary position line (`display_*` = EUR)
- `backend/src/main/java/com/picsou/controller/FinaryImportController.java` -- REST endpoints for xlsx upload
- `backend/src/main/java/com/picsou/controller/FinaryApiSyncController.java` -- REST endpoints for API sync (`/preview`, `/execute`, `/auto`)
- `backend/src/main/java/com/picsou/finary/dto/` -- 14 DTOs for Finary API responses (incl. `FinaryLoanDto`)
- `backend/src/main/java/com/picsou/finary/SyncSessionData.java` -- Cache record for API sync session
- `backend/src/main/java/com/picsou/dto/FinaryAutoSyncResponse.java` -- Response DTO for `/api/finary/api-sync/auto`

### Flow

```
XLSX Import:
User uploads xlsx file
        |
        v
FinaryImportService.preview(file)
        |
        +-- Apache POI: parse account sheets (10 categories)
        +-- Apache POI: parse Transactions sheet
        +-- Cache parsed data (UUID fileToken, 30-min TTL)
        +-- Return: account previews + existing Picsou accounts
        |
        v
User reviews + maps accounts (SKIP / MAP_EXISTING / CREATE_NEW)
        |
        v
FinaryImportService.executeImport(fileToken + mappings)
        |
        +-- Retrieve cached data
        +-- For each mapping:
        |       +-- SKIP: skip
        |       +-- MAP_EXISTING: update balance, set externalAccountId
        |       +-- CREATE_NEW: create account, set externalAccountId
        |       +-- Reconstruct balance snapshots from transactions
        |       +-- Import transactions (xlsx has no holdings payload)
        +-- Remove from cache
        +-- Return result (counts + imported accounts)

API Sync:
User triggers sync (no TOTP first attempt)
        |
        v
POST /api/finary/api-sync/preview
        |
        +-- FinaryApiClient.authenticate() via Clerk (6 steps)
        |
        +-- If Clerk returns "needs_second_factor" and no TOTP provided:
        |       throw TotpRequiredException → HTTP 403
        |
        v
Frontend receives 403 → shows TOTP input
        |
        v
User enters 6-digit TOTP code
        |
        v
POST /api/finary/api-sync/preview?totp={code}
        |
        +-- Clerk completes second factor with TOTP
        +-- Fetch accounts from all 10 categories
        +-- Fetch loans from the dedicated /loans endpoint  -> LOAN accounts
        +-- Fetch transactions (paginated, 200/page)
        +-- Cache with syncToken (10-min TTL)
        +-- Return: account previews + existing Picsou accounts
        |
        v
User reviews + maps accounts
        |
        v
FinaryApiSyncService.execute(syncToken + mappings)
        |
        +-- Retrieve cached session
        +-- Apply mappings
        +-- Persist holdings (ISIN → OpenFIGI ticker, crypto code as ticker)
        +-- Store display_balance as EUR currentBalance (fixes USD crypto wallets)
        +-- Import transactions
        +-- Remove from cache
        +-- Return result

Auto-sync (button or daily at 08:00):
        |
        v
POST /api/finary/api-sync/auto
        |
        +-- FinaryApiSyncService.autoSync(memberId)
        +-- preview() -- authenticate + fetch (no TOTP)
        |
        +-- If TOTP required:
        |       status = TOTP_REQUIRED, session flagged
        |
        +-- If new accounts found (autoMapped = false):
        |       status = NEEDS_MAPPING → user goes through mapping UI
        |
        +-- All accounts already mapped (autoMapped = true):
                execute() -- MAP_EXISTING for all
                status = OK, accountsSynced = N
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Two-phase preview+execute | Lets users review accounts and fix mappings before committing data | Direct import (no review, risk of duplicates/wrong types) |
| ConcurrentHashMap cache | Simple, no Redis dependency, single-user app | Redis or DB-backed cache (overkill) |
| Apache POI for xlsx | Standard Java library for Excel; Finary exports in xlsx format | CSV parsing (Finary does not export CSV) |
| Clerk auth flow reimplemented | Finary uses Clerk for auth; no official API; must reverse-engineer the 6-step flow | Finary API key (does not exist) |
| `TotpRequiredException` → 403 | Frontend already checks for 403 on preview mutations; 401 would trigger the Picsou JWT refresh flow which is wrong | Using 401 (conflicts with Picsou auth refresh), using 502 (frontend can't distinguish from real errors) |
| `FinaryServiceUnavailableException` → 502 | Distinguishes upstream outage (Clerk/Finary unreachable) from data sync issues; frontend shows "service unavailable" message | Returning 502 for all sync errors (confusing, cannot distinguish cause) |
| `SyncException` → 422 | Data/sync issues (bad response, mapping failures) are client errors, not gateway errors | 502 BAD_GATEWAY (semantically wrong for data processing failures) |
| Retry with exponential backoff | Transient HTTP 5xx and network errors from Clerk/Finary are common; retry reduces false failures | No retry (fails on first attempt even for transient issues) |
| Per-account mapping suggestions | Match by current `finary_{category}_{id}`, then legacy slug id, then unique name+type. One new Finary row must not wipe the rest | Fail-all on first miss (old behaviour: wizard defaulted every row to CREATE_NEW) |

## Gotchas / Pitfalls

- **TOTP must be disabled for background auto-sync**: `autoSync()` passes `null` for TOTP. If 2FA is enabled on the Finary account, auto-sync returns `TOTP_REQUIRED` and the session is flagged. The user must re-authenticate interactively (via the preview endpoint with TOTP). For interactive sync via the frontend button, the TOTP input is shown and the user retries through the preview flow.
- **Manual transactions survive Finary re-syncs**: `FinaryPersistenceHelper.importTransactions()` calls `deleteByAccountIdAndIsManualFalse()` instead of `deleteByAccountId()`. Manually-added transactions are preserved across any number of re-syncs.
- **TOTP is a query parameter**: The TOTP code is sent as `?totp={code}` on the POST preview request. This avoids body parsing complexity but means the code is visible in server access logs.
- **Preview tokens expire quickly**: XLSX tokens expire after 30 minutes, API sync tokens after 10 minutes. Users must complete the mapping within that window or re-upload.
- **Clerk API version is hardcoded**: The `__clerk_api_version` and `_clerk_js_version` query parameters are hardcoded in `FinaryApiClient`. If Clerk updates, these may need to be updated.
- **Account name matching is case-insensitive, trimmed, and type-aware**: a Finary `investments` row can map to PEA / CTO / épargne salariale with the same name; `fonds_euro` maps to SAVINGS/livrets, not the CTO of the same label. Two Coinbase wallets of type CRYPTO stay unmatched (ambiguous). After the first successful MAP_EXISTING, `externalAccountId` is written so the next auto-sync skips the wizard.
- **Transactions are per-category**: API sync fetches transactions only from checkings, savings, investments, and credits categories. Other categories (real estate, cryptos) do not have a transactions endpoint.
- **Holdings come from the account payload, not a separate endpoint**: `/portfolio/{cat}/holdings` is 404. Positions live on each account as `securities`, `cryptos`, `fonds_euro`, `fiats`, `generic_assets`, `scpis`, `precious_metals`. `FinaryHoldingsImporter` reads those lists on execute/auto-sync. XLSX import still has no holdings.
- **Use `display_*` for EUR, never native `balance` on FX accounts**: USD crypto wallets report `balance` / `current_value` in USD. `display_balance` / `display_current_value` are already in the user's display currency (EUR). Storing native USD as EUR is what used to inflate those wallets ~80×.
- **Cash is not a holding**: fiats and "Solde Espèces" (`symbol`/`isin` `000000000000`) go to `account.cash_balance`. Everything else is replaced on each sync (same wipe-and-write as Bourse Direct / TR).
- **Many Finary instruments have no Yahoo ticker**: Yomoni fonds, RealT tokens, fonds euros, Amundi `QS…` employee-savings codes. `provider_value_eur` / `provider_pnl_eur` keep the Finary valuation so the live balance does not collapse to cash-only.
- **Ticker column is VARCHAR(100)** (V80): RealT property codes exceed the old 30-char limit. Names widened to 255. `V64` on `origin/main` is already `backfill_trade_republic_valuations`.
- **External IDs use Finary category + ID**: Format is `finary_{category}_{finaryId}`. This means the same Finary account always maps to the same external ID, preventing duplicates across imports.
- **Loans come from a separate endpoint (issue #11)**: loan/mortgage accounts are *not* returned by the portfolio `credits`/`credit_accounts` categories — they live on the dedicated `/loans` endpoint. The API sync fetches them via `FinaryApiClient.fetchLoans()` and adapts each entry to the common `FinaryAccountDto` under a synthetic `loans` category (external ID `finary_loans_{id}`), so they flow through the normal preview/mapping/execute pipeline and map to `AccountType.LOAN`. The outstanding amount is stored as a **negative** balance (a loan is a liability). Only the balance is imported — the loans payload does not expose the original principal or interest rate, so **no `Debt` row is created**; the imported LOAN account shows a static balance until the user fills in the loan parameters for the amortization view (see [loans.md](loans.md)). The exact `/loans` JSON shape and path are best-effort from the issue's sample (`type`, `name`, `outstanding_amount`, `monthly_repayment`, `start_date`, `end_date`); `FinaryLoanDto` maps the snake_case keys explicitly and accepts camelCase aliases as a fallback.
- **Import mapping wizard type dropdown includes all account types (fix #17)**: The `CREATE_NEW` type selector in `FinaryTab` now uses `ACCOUNT_TYPES` from `@/lib/constants` instead of a hard-coded subset. This ensures `LOAN` and `REAL_ESTATE` are available in the dropdown, so loans imported from `/loans` are no longer forced into `OTHER` when the user overrides the backend suggestion.
- **Error status codes are differentiated (fix #27)**: `FinaryServiceUnavailableException` returns 502 (Clerk/Finary unreachable), `SyncException` returns 422 (data/sync issue), `TotpRequiredException` returns 403. Previously all sync errors returned 502. The frontend uses a `getFinaryError()` helper to show localized messages per status code.
- **HTTP calls retry transient failures (fix #27)**: `FinaryApiClient.sendWithRetry()` retries up to 3 times with exponential backoff (1s, 2s) on HTTP 5xx and network errors from Clerk/Finary.

## Tests

- `FinaryImportServiceTest` -- unit tests for xlsx parsing, type suggestion, mapping
- `FinaryAccountMatcherTest` -- external-id vs name+type, duplicate names, Fortuneo AV vs fonds euros
- `FinaryApiSyncServiceTest` -- unit tests for API sync flow, incl. loans appearing in the preview and being created as LOAN accounts on execute
- `FinaryLoanDtoTest` -- unit tests for parsing the `/loans` payload (snake_case + camelCase aliases)
- `FinaryHoldingsImporterTest` -- securities / crypto / fonds-euro mapping, cash skip, ticker sanitizing
- `FinaryAccountHoldingsDtoTest` -- Jackson parse of `display_balance` + nested positions
- `FinaryApiSyncServiceTest.eurBalance_prefersDisplayBalanceOverNativeUsd` -- stored balance uses display EUR, not native USD
- Manual integration testing with real Finary accounts

## Links

- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
