# Feature: Bank Sync

> Last updated: 2026-08-10 (sync-flow hardening: OAuth `state` correlation, reconnect path, visible errors — merged with the bank search country picker: `listCountries`, `GET /api/sync/countries`, `DEFAULT_COUNTRY`; on top of PSU types, 2026-08-01)

> **Status (1.0.0).** Enable Banking is the only enabled provider. The Powens
> adapter ships in the codebase but is **experimental and untested** —
> `@Primary` was removed from `PowensBankConnector` so Enable Banking remains
> injected as the canonical `BankConnectorPort` even when `POWENS_CLIENT_ID`
> is set. Sections below referring to Powens describe the full design that
> can be re-enabled once the adapter has been validated end-to-end.

## Context

Picsou syncs bank accounts from Enable Banking's ~29-country EEA coverage — bank search and connection are not restricted to France (see [Country selection](#country-selection)), though France remains the default/primary market. In 1.0.0 the active provider is Enable Banking (PSD2, open banking). A second provider — Powens / Budget Insight (screen scraping) — is implemented behind `BankConnectorPort` but disabled because it has not been tested against a real Powens tenant. The scheduler runs daily auto-sync at 08:00 for all linked requisitions.

## How it works

### Provider architecture

Both providers implement the `BankConnectorPort` interface: `initiateConnection`, `exchangeCode`, `fetchBalances`, `searchInstitutions`, `listCountries`, and (since 1.1.0) `fetchTransactions`. The service layer (`SyncService`) never imports adapters directly -- it depends only on the port.

### Transaction ingestion (1.1.0)

Since 1.1.0 the sync also ingests **transactions**, not just balances. After upserting balances, `SyncService` calls `BankConnectorPort.fetchTransactions(sessionId, externalAccountId, from)` for each Enable Banking account (`GET /accounts/{id}/transactions`, mapping creditor/debtor → `counterparty`, remittance → `description`). Transactions are deduplicated by `(account, externalId)`, persisted with `isManual=false`, then categorized by `CategorizationService.apply`. Ingestion is incremental on `lastSyncedAt`; the first attach pulls the maximum available window (≈90 days). This feeds the entire Budget module — see [budget.md](./budget.md).

`BankTransactionImportService` also stores SimpleFIN's transactions, which arrive with the
balances (`importProvided`); see [simplefin-sync.md](./simplefin-sync.md). For both providers it
clips description and category to their column widths, skips an amount that does not fit
`numeric(20,8)`, and hashes an external id longer than 255 characters into an `fp:` key. The
fingerprint rounds the amount to 8 decimals before hashing, so it matches the stored value.

**Enable Banking** (`EnableBankingBankConnector`): Uses the PSD2 Bank Account Data API. Auth is JWT-based (RS256 signed with an RSA private key). Sessions are created via OAuth redirect. After the user authorizes, Enable Banking populates the `session.accounts` list **asynchronously** — some ASPSPs take seconds, others hours (Boursorama). The adapter polls `GET /sessions/{id}` up to 8 times with 2-second delays (≤ 16 s total, configurable via `app.enablebanking.session-poll-attempts` / `session-poll-delay-ms`). If the session still has no accounts, it returns an empty list rather than throwing — the requisition is marked **FAILED** (preserving the session id) so the scheduler can retry the next day without going back through OAuth.

**Powens** (`PowensBankConnector`) — ⚠ experimental, disabled in 1.0.0. Uses screen scraping via the Budget Insight API. Auth is an OAuth webview that handles bank selection and credential entry. The OAuth code is exchanged for a permanent access token. Gated behind `@ConditionalOnExpression` (so it only registers when `POWENS_CLIENT_ID` is set), but `@Primary` was removed for 1.0.0, so Enable Banking remains injected even when the bean is registered.

### Account-matching resilience (Enable Banking 0.16.4)

Enable Banking 0.16.4 (released 2026-05-30) changed the account identification hash for ASPSPs that
do not expose a per-account currency (e.g. Boursorama) — the account `uid` is now derived from the
account number alone, breaking the uid-based match used by the previous upsert logic.

Picsou now handles this with a two-pronged strategy:

1. **IBAN-first account matching.** `SyncService.upsertAccount()` matches an incoming account against
   stored accounts by **IBAN** first (stable, bank-issued identifier), falling back to the Enable
   Banking `uid` only when the IBAN is absent. On a successful IBAN match the stored `uid` is
   refreshed in place, so the next sync uses the new uid without user intervention. The soft-delete
   guard (which prevents re-creating an account the user removed) also checks IBAN alongside uid.
   - New column: `account.iban VARCHAR(34)`, added by `V46__account_iban.sql`.

2. **Per-account failure isolation in `fetchBalances()`.** Previously, a single failing account
   would surface an exception that zeroed the entire balance batch. Expected per-account failures
   are now caught: a `SyncException` (every provider-call failure, such as the 404 after a uid
   rotation, is mapped to it) and a `NumberFormatException` from a malformed balance amount. The
   adapter logs the error and skips that account, letting the rest of the batch succeed. Any other
   exception is a bug and propagates, so the scheduled resync marks the requisition `FAILED` instead
   of saving a partial result. When every account fails, the first failure is rethrown instead: that is a bank- or
   consent-level outage (5xx, 429, expired consent), and an empty list would read upstream as
   "accounts still linking".

3. **Missing currency tolerance.** Some ASPSPs omit the per-account currency field. The adapter now
   defaults to `EUR` rather than throwing, matching the most common case for French current accounts.

### Country selection

Bank search is not restricted to France — `GET /api/sync/institutions?query=...&country=CC` accepts any ISO 3166-1 alpha-2 code, and `GET /api/sync/countries` lists what the active provider actually supports. It has its **own** rate-limit bucket, separate from `POST /api/sync/initiate`'s (`SyncController.checkSyncRateLimit` keys buckets by `ip + endpoint name`) — sharing one bucket would let a passive, auto-fetched read (the picker populating on every "Add Account" open) exhaust the budget meant for an explicit user action like connecting a bank. The frontend's `BankCountrySelect` (`frontend/src/components/shared/BankCountrySelect.tsx`) populates its options live from that endpoint rather than a hardcoded list, so it never drifts from real coverage; it snaps back to the first available option if the current selection isn't in the loaded list (e.g. a provider without France coverage), and shows a visible inline error — while staying functional on the France-only fallback — whenever it's genuinely stuck there: the request failed, *or* it succeeded but came back empty (both cases are indistinguishable from "no other countries exist" without an explicit message). That error is intentionally **not** shown just because a background refetch failed while good data from a prior fetch is still being rendered — the message would then be false ("showing France only" while the full list is on screen). `BankConnectorPort.DEFAULT_COUNTRY` ("FR") is the single named fallback used by the controller's `@RequestParam defaultValue` and by every caller that needs a concrete country — previously this was scattered across independent `"FR"` string literals, one of which could silently mis-resolve a bank with a blank country field.

`BankConnectorPort.parseInstitutionId()` is the shared, provider-agnostic parser used by `SyncService.parseCountry` (which backs logo backfill). It reads the **country as the second segment** of the id, which is the position it occupies in both the current `name::country::psuType` form and the legacy `name::country` one (see [PSU types](#psu-types-retail-vs-business-banks)) — reading the *last* segment instead would silently return `"business"` as the country for every three-segment id. It returns a blank name/country (rather than throwing) for a `null` input. Callers decide their own fallback for a blank/absent country: `parseCountry` returns `null`, so an unknown-country logo lookup searches unfiltered across all countries rather than narrowing to France and possibly missing the real institution, while `EnableBankingBankConnector.initiateConnection` needs a concrete value to send upstream and therefore uses its own PSU-aware `parseInstitutionId`, which falls back to `DEFAULT_COUNTRY`. `SyncController.InitiateRequest`'s fields are `@NotBlank`-validated (422 on a missing `institutionId`/`institutionName`), so the parser's null-safety is defense in depth, not the primary guard.

`EnableBankingBankConnector.listCountries()` never caches a null/empty result from `GET /application` for the full 6h TTL — a transient blip serves (and keeps) the last good cached value instead, matching the same "don't negative-cache" principle used elsewhere in this file.

`EnableBankingBankConnector.listCountries()` calls Enable Banking's `GET /application`, which returns the countries this specific application is registered/active for — a small, near-static payload, and more correct than deriving coverage from the full ASPSP catalog (which could list countries this particular app isn't licensed for). The result is cached in-memory for 6 hours (`CachedCountries`, a single-record TTL cache in the same spirit as `PriceService.CachedPrice`, though simpler — no invalidation hook, since app-country coverage essentially never changes at runtime). `PowensBankConnector.listCountries()` fetches its `/connectors` catalog uncached (Powens is disabled by default in 1.0.0; revisit if it's ever re-enabled).

**Aside, found and fixed while building this:** `EnableBankingBankConnector`'s `WebClient` used the default 256 KB in-memory buffer limit, which `searchInstitutions()` could already exceed on a single large country (Germany alone is ~1.4 MB across ~1100 institutions) — a latent bug independent of the country picker. Both Enable Banking's and Powens' `WebClient`s now raise this to 8 MB.

### PSU types (retail vs business banks)

Enable Banking partitions its ASPSP catalog by **PSU type** — `personal` for
retail customers, `business` for professionals. `GET /aspsps` takes it as an
optional filter and returns each ASPSP's own `psu_types` array; `POST /auth`
takes it as a required field that decides which login page the bank presents.

Picsou originally hardcoded `personal` in both places, which made every
business-oriented institution invisible in the bank picker — a user with a
Swan.io account searched "Swan", got nothing, and had no way to tell that from a
misconfiguration. Swan, like other BaaS providers, is published under `business`
only.

The catalog is now fetched **unfiltered** and `EnableBankingBankConnector`
resolves one PSU type per ASPSP (`resolvePsuType`): `personal` whenever the bank
offers it — so every retail bank behaves exactly as before — otherwise the
bank's first declared type, passed through verbatim rather than coerced to
`business`, so an unrecognised provider value can't turn into an `/auth` the API
rejects. The resolved type is carried on `InstitutionData.psuType`, surfaced in
both bank pickers as a **Pro** badge, and echoed back on `/auth`. The badge is
keyed on `business` exactly, not on "not `personal`": the pass-through above means
an unrecognised value can reach the picker, and it is not evidence that the bank is
a professional one — so it renders unbadged.

**Institution id format.** The id is an opaque round-trip token the client never
parses, so the PSU type rides along inside it rather than as a second field the
client would have to remember to send back:

```
"Swan::FR::business"     name::country::psuType   (current)
"BoursoBank::FR"         name::country            (written before this change)
```

`parseInstitutionId` and `SyncService.findInstitution` both accept the legacy
two-segment form — existing requisitions predate business support and are
therefore `personal`. `findInstitution` gained a middle matching tier
(name+country, ignoring the PSU segment) so those rows keep their country
preference instead of degrading to a bare name match.

### Requisition lifecycle

1. **CREATED** -- `SyncService.initiateConnection()` calls the port and stores a `Requisition` with `authLink`.
2. **LINKED** -- `SyncService.completeConnection()` exchanges the OAuth callback code, fetches balances, upserts at least one account, and marks the requisition as LINKED. Immediately after a successful exchange, `RequisitionLifecycleWriter` commits the `session_id` and clears the spent OAuth nonce in an independent `REQUIRES_NEW` transaction. A later fetch or account-upsert rollback therefore cannot restore the stale authorization id.
3. **FAILED** -- If the code exchange, balance fetch, or account upsert fails, or if Enable Banking returns zero accounts after polling, `RequisitionLifecycleWriter` marks the requisition FAILED in an independent transaction. Account and snapshot writes are explicitly flushed inside the guarded block so deferred constraints surface before method return; they still roll back atomically, while the session id remains available to `retrySync()`.
4. **Reconnect** -- `POST /api/sync/{id}/reconnect` (`SyncService.reconnect()`) re-initiates the OAuth flow **on the existing requisition** for the cases a retry can never fix: a failed code exchange (the stored id is an authorization id, not a session) or an expired/revoked PSD2 consent (~90 days). Status returns to CREATED with a fresh `authLink`; accounts survive because `upsertAccount` matches on `externalAccountId`. **Refused for LINKED requisitions** — overwriting a working session id with an unconsumed authorization id would break scheduled syncs if the user abandons the new OAuth flow. Exposed in the UI as a "Reconnect to bank" button next to retry on FAILED connections (BankSyncTab and SyncAllModal). Rate-limited like `/initiate` (it triggers an outbound EB `/auth` call), on its own `ip + endpoint` bucket — as is `/complete`, whose `exchangeCode` + `fetchBalances` also hit the provider.

Every public method of `EnableBankingBankConnector` maps **all** provider failures (HTTP errors, timeouts, connection errors) to `SyncException`, keeping the external-error contract stable. `completeConnection()` additionally catches arbitrary runtime failures from account persistence, marks the requisition retryable through the independent lifecycle writer, and lets its main transaction roll back partial account/snapshot work.

### Automatic retry of FAILED sessions

`SchedulerService.dailyBankSync()` calls `SyncService.retryAllFailed(memberId)` (after `resyncAll`) for every member. It finds all FAILED requisitions and calls `retrySync()` for each — which re-polls the Enable Banking session. If accounts are now populated, the requisition transitions to LINKED and accounts are upserted. If not, it stays FAILED and is retried again the next day. Enable Banking sessions are valid for 90 days (`valid_until`), so there is ample time for banks that take hours to asynchronously populate accounts (observed: Boursorama ~4 h+).

### Account type detection

`SyncService.detectType()` maps provider metadata (product name, cash account type) to the `AccountType` enum. Keywords like "pea", "lep", "livret", "titre" in the product string are matched case-insensitively. The `cashAccountType` field (e.g. "SVGS") is used as a fallback. Default is `CHECKING`.

> **Known gap (pre-existing, not addressed by the country picker above):** as of this writing, no `detectType()` method actually exists in `SyncService` — `upsertAccount()` hardcodes `AccountType.CHECKING` for every new account. This section (and the matching description in `docs/ARCHITECTURE.md` / the dual-bank-providers ADR) describes intended/previous behavior that doesn't currently match the code. Flagging it here since widening bank search to more countries makes correct type detection more relevant, not less — but fixing it is a separate task from this one.

### Key files

- `backend/src/main/java/com/picsou/adapter/EnableBankingBankConnector.java` -- PSD2 adapter (RSA JWT, async account linking)
- `backend/src/main/java/com/picsou/adapter/PowensBankConnector.java` -- Scraping adapter (experimental, OAuth webview; `@Primary` removed in 1.0.0)
- `backend/src/main/java/com/picsou/port/BankConnectorPort.java` -- Port interface with `AccountData`, `InstitutionData` records, `DEFAULT_COUNTRY`, and the shared `parseInstitutionId()` static parser
- `backend/src/main/java/com/picsou/service/SyncService.java` -- Orchestration: initiate, complete, retry, resync, type detection
- `backend/src/main/java/com/picsou/service/RequisitionLifecycleWriter.java` -- Independent transaction checkpoints for consumed OAuth sessions and retryable failures
- `backend/src/main/java/com/picsou/controller/SyncController.java` -- REST endpoints under `/api/sync/`
- `backend/src/main/java/com/picsou/model/Requisition.java` -- Tracks connection lifecycle (CREATED/LINKED/FAILED)
- `frontend/src/components/shared/BankCountrySelect.tsx` -- Country picker, populated from `GET /api/sync/countries`

### Flow

```
User initiates connection
        |
        v
SyncController.initiate() --> SyncService --> BankConnectorPort.initiateConnection()
        |                                         |
        |                          Enable Banking: POST /auth (RSA JWT)
        |                          Powens: build webview URL
        |
        v
User authorizes in browser --> redirect to /sync/callback?code=xxx&state=yyy
        |                      (frontend POSTs code + state to /api/sync/complete;
        |                       the requisition is resolved by its stored state nonce)
        |
        v
SyncController.complete() --> SyncService.completeConnection()
        |                         |
        |                         v
        |               BankConnectorPort.exchangeCode() --> session_id
        |                         |
        |                         v
        |               RequisitionLifecycleWriter.checkpointSession()
        |                         |  (REQUIRES_NEW commit)
        |                         v
        |               BankConnectorPort.fetchBalances(session_id)
        |                         |
        |                         v
        |               upsertAccount() with detectType()
        |                         |
        |                         v
        |               AccountService.upsertSnapshot()
        |
        v
SchedulerService.dailyBankSync() --> SyncService.resyncAll()   (LINKED only)
                               --> SyncService.retryAllFailed() (FAILED: retry until LINKED or 90d expiry)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Dual providers with `@Primary` | PSD2 can't access LEP/PEA/livrets; scraping covers all French account types | Single provider only |
| `@ConditionalOnExpression` for Powens | No-code activation: set env var, adapter appears; unset, it disappears | Feature toggles, profiles |
| Keyword-based type detection | Banks rarely expose a standardized type field; product name is the most reliable signal | Hardcoded institution-to-type mapping |
| Async polling for Enable Banking accounts | EB links accounts asynchronously after OAuth; polling (3 x 1.5 s) handles the delay | Webhook (EB does not provide one) |
| Permanent access token for Powens | Powens tokens do not expire; stored directly as the requisition ID | Refresh token rotation (not needed) |
| Independent requisition lifecycle checkpoint | OAuth codes are single-use; the exchanged session id and FAILED status must survive rollback of account/snapshot writes | `saveAndFlush` in the outer transaction (flushes SQL but still rolls back with that transaction) |
| PSU type encoded in the composite institution id | The id is already an opaque token the client round-trips untouched, so no new request field, no DTO change — and legacy two-segment ids stay parseable | A separate `psuType` field on `POST /sync/initiate` (one more thing the client must send back, plus a nullable column on `requisition`) |
| One PSU type resolved per bank, `personal` preferred | A personal-finance app should not ask retail users to pick a login flavour; only business-only banks need the other one | A Particulier/Pro toggle in the search UI |

## Enable Banking onboarding caveats

Two pitfalls cost real users a lot of time during 1.0.0 testing — both are surfaced in the wizard now (`EBStep1Explain`, `EBStep2Credentials`):

- **PRODUCTION vs SANDBOX**: The Enable Banking developer dashboard defaults to SANDBOX, which only exposes fictitious test banks. A user who creates a SANDBOX application will reach the bank picker, see a list of unfamiliar test banks, and never find their real one. The wizard now shows a warning encart on step 1 and forces the user to tick a "my application is in PRODUCTION mode" checkbox before submitting credentials on step 3.
- **PSD2 scope is current accounts only (`CACC`)**: PSD2 standardises consent for cash accounts. PEA, Assurance Vie, Livret A, and other savings/investment products are out of scope — Enable Banking has no API for them. This is a permanent product limitation, not a Picsou bug. Users should be directed to the dedicated integrations (Trade Republic, BoursoBank sidecar, Finary) or manual entry. The wizard surfaces this on step 1, and `BankSyncTab` repeats the note above the connection list.

## Enable Banking configuration: the configured pieces

Enable Banking needs the following, stored/loaded in **two different places** — a distinction that has confused operators:

| Piece | Stored in | Set via |
| --- | --- | --- |
| Application ID | DB (`app_setting`) or `ENABLEBANKING_APPLICATION_ID` | Setup wizard / Admin → Integrations |
| Redirect URI | DB (`app_setting`) or `ENABLEBANKING_REDIRECT_URI` | Setup wizard / Admin → Integrations |
| **RSA private key** | **Filesystem** (`/data/keys/enablebanking-private.pem` in Docker, `backend/.local/keys/enablebanking-private.pem` in dev), or `ENABLEBANKING_PRIVATE_KEY_PATH` / inline `ENABLEBANKING_PRIVATE_KEY` | Setup wizard keypair step / **Admin → Integrations keypair panel** |

**Application ID == Key ID.** Per Enable Banking's [quick-start spec](https://enablebanking.com/docs/api/quick-start/) the JWT header `kid` *is* the application's ID and the private key file is named `<applicationId>.pem` — so the "Key ID" is never a distinct value. Picsou therefore collects **only the Application ID** (in both the wizard and the admin page) and derives the Key ID from it: `EnableBankingConfigProvider.keyId()` returns an explicitly-configured value if present (legacy `ENABLEBANKING_KEY_ID` env / `key-id` DB row, kept for backward compatibility), otherwise falls back to `applicationId()`. `SetupService.writeEnableBankingConfig(applicationId, redirectUri)` writes the `key-id` row in lock-step with `application-id` so a later app-id change can't be shadowed by a stale DB key-id (which, being DB-first in `resolve()`, would otherwise win).

`EnableBankingConfigProvider.isConfigured()` requires app id + key id + redirect URI + a parseable private key; `isConfiguredLenient()` / `privateKeyPresent()` are cheap, **non-parsing** checks used by status surfaces and the admin integration toggle (so a malformed key reports as "present" rather than 500-ing the page — the parse error surfaces at real use in `privateKey()`).

Because the text fields (Application ID + Redirect URI) live in Postgres while the key is a file, they can be "saved" while the key is absent — e.g. configuring via the Admin page, abandoning the wizard after the credentials step, or losing the `/data/keys` volume while the DB survives. Symptom: institution search / sync fails with a `SyncException` (HTTP 502).

**Recovery / management:**
- The **Admin → Integrations → Enable Banking** section shows a readiness banner naming every missing piece (incl. the private key) and provides a keypair panel to **generate** (idempotent — never invalidates an already-uploaded public key) or **import** a `.pem`. Endpoints: `POST /api/admin/settings/enablebanking/keypair` and `/keypair/import` (mirror the wizard's but without the setup-complete guard, so they work post-setup). `GET /api/admin/settings` returns `enableBanking.privateKeyPresent`.
- Connector errors are **field-specific**: `EnableBankingBankConnector` names the single missing piece (e.g. *"Enable Banking private key is missing on the server…"*) instead of a blanket "not configured", so the operator knows exactly what to fix.

## Gotchas / Pitfalls

- **Powens is disabled in 1.0.0**: `@Primary` was removed from `PowensBankConnector`, so even setting `POWENS_CLIENT_ID` will NOT activate Powens — Enable Banking stays injected. To re-enable after validating the adapter, restore `@Primary` on `PowensBankConnector` and set `POWENS_CLIENT_ID`.
- **Enable Banking RSA key**: The private key must be PKCS8 PEM format. The `ENABLEBANKING_PRIVATE_KEY` env var can contain literal `\n` characters -- both formats are handled in `parsePem()`. The key lives on disk, **not** in the DB — see "Enable Banking configuration" above; setting only the text fields (Application ID + Redirect URI) leaves searches failing until a key is generated/imported.
- **Local dev key path**: the `dev` Spring profile stores the generated key under `backend/.local/keys/enablebanking-private.pem` so bare-metal macOS/Linux runs do not try to create Docker's `/data/keys` directory at filesystem root.
- **The callback URI must be HTTPS — a plain-HTTP deployment cannot sync banks.** Enable Banking rejects `http://` redirect URLs for PRODUCTION applications, and PRODUCTION is the only mode that lists real banks (see the SANDBOX pitfall above), so there is no HTTP escape hatch. Docker deployments must therefore terminate TLS: either the bundled `tls` compose profile (`docker compose --profile tls up -d`, see [docker-deployment.md](./docker-deployment.md)) or an existing reverse proxy. Note that Enable Banking never *fetches* the callback URL — the redirect is browser-side only (`window.location.href = authLink`, then the SPA POSTs the `code`) — so a certificate from Caddy's internal CA works fine on a LAN, as long as the family's browsers trust its root.
- **Enable Banking redirect URI must be registered**: `ENABLEBANKING_REDIRECT_URI` defaults to `https://localhost:5173/sync/callback` for local development, matching Vite's HTTPS dev server. In production, set it to the public frontend callback URL (for example `https://picsou.example.com/sync/callback`). The exact same URL must be registered in the Enable Banking developer portal under the application's Redirect URIs. A mismatch causes a `REDIRECT_URI_NOT_ALLOWED` 400 error at auth initiation — it surfaces in the Add Account modal bank wizard.
- **ALREADY_AUTHORIZED**: If the OAuth code is reused (e.g. browser back button), `SyncService.completeConnection()` catches the error and falls back to refreshing the latest linked session **of the same institution** instead of failing (a replayed Revolut callback must not resync BNP).
- **OAuth `state` correlation**: each initiation stores a random single-use nonce on the requisition; the callback resolves the requisition by it (member derived from the row — this is what makes admin-impersonated connections complete correctly). See [ADR 2026-07-08](../decisions/2026-07-08-oauth-state-requisition-correlation.md).
- **Session identifiers stay out of logs**: Enable Banking session ids are opaque references that still identify a long-lived PSD2 consent. Use the internal requisition id and institution name for log correlation; never print the raw session id.
- **One bank-status query key**: bank-sync feature hooks own `syncKeys.banks()`, and every complete/retry/reconnect/delete mutation invalidates it. Components must use those hooks rather than introducing a parallel key such as `['sync', 'connections']`, or another sync surface can remain stale until its polling interval elapses.
- **Type upgrade on resync**: If the user has not customized an account's type, `upsertAccount()` will upgrade it from CHECKING to the detected type on the next sync. Manual user changes are preserved (only CHECKING is auto-upgraded).
- **Both providers are optional**: The app starts fine without either. No `BankConnectorPort` bean is required at startup.
- **Enable Banking uid instability.** EB 0.16.4 changed the account uid derivation for ASPSPs that
  don't expose per-account currency. Picsou resolves this via IBAN-first matching and uid refresh on
  match; if an account has no IBAN it will fall back to uid matching and may be treated as a new
  account after an EB provider upgrade. This is tracked by `V46__account_iban.sql`.
- **A business bank in the list can still fail at authorization**: Enable Banking
  lets an ASPSP declare `required_psu_headers` that must accompany `/auth`.
  Picsou does not send them, so a business bank may now be *findable* yet fail
  when connecting. That is a different failure from "the bank isn't listed" —
  don't re-diagnose the search path for it.
- **`IntegrationsHealthService` queries `/aspsps` unfiltered too**, deliberately:
  it is a JWT/connectivity probe (`toBodilessEntity()`), and leaving a lone
  `psu_type=personal` there would read as an oversight.
- **Bank logos**: `InstitutionData.logoUrl` (Enable Banking only — Powens hardcodes `null`) is captured at connection time and copied onto each `Account`. See [bank-logos.md](./bank-logos.md) for the capture/backfill flow and the account card fallback to `color`.
- **Deleting the last account on a requisition deletes the requisition** (see [the account-deletion ADR](../decisions/2026-08-11-account-deletion-removes-its-connection.md)). `upsertAccount` refuses to resurrect a soft-deleted account, so a requisition whose accounts are all deleted can never produce one again — it would sync forever, cost an outbound call per run, and still appear in "Sync accounts" offering to sync something the user removed. `AccountConnectionService` removes it once no live account is left; a requisition backing several accounts survives until the last one goes. The confirmation dialog names the bank first (`GET /accounts/{id}/deletion-impact`), because getting it back means a full OAuth round trip through the bank — unlike a wallet, which is re-added by pasting an address.
- **`account.requisition_id` is what makes that possible, and it is nullable on purpose**: every other connector is recognisable from `external_account_id` (`wallet_`, `amundi_`, `tr_`, `bd_`, `ibkr_`, `sfin_`, `degiro-portfolio`), but an Enable Banking account holds the bank's own opaque id and carries no namespace. V76 adds the column and `upsertAccount` sets it on **both** the create and the update path, so rows that predate it link themselves on their next sync. The V76 backfill matches `provider` against `institution_name` and deliberately skips members holding several requisitions for one institution — a name cannot say which one, and those rows keep their connection rather than risk removing the wrong one. Do not read `NULL` as "not a bank account".

## Tests

- `SyncServiceTest` -- unit tests for type detection, upsert logic, retry flow, checkpoint ordering on fetch/upsert failures, and logo matching for both the current and the legacy institution id format
- `RequisitionLifecycleWriterTest` -- session/nonce transitions and `REQUIRES_NEW` propagation invariant
- `AddAccountModal.test.tsx` -- the Pro badge shows for a business-only institution, and not for a retail one nor for an unrecognised PSU type
- `EnableBankingConfigProviderTest` -- DB/env resolution precedence, and `keyId()` falling back to the Application ID vs honoring an explicitly-configured value
- `EnableBankingBankConnectorTest` -- JWT build / institution search against a mocked provider, plus the pure catalog helpers: `resolvePsuType` (business-only banks, the Swan regression), `toInstitutions` (composite id, de-duplication, country fallback) and `parseInstitutionId` (three-segment, legacy two-segment, unexpected PSU segment)
- `BankConnectorPortTest` -- `parseInstitutionId` cases (name+country, three-segment id with a PSU type, blank country segment, no separator, name containing `"::"`, `null` input)
- `SyncControllerTest` -- `GET /countries` returns the service result; rate-limit-exceeded returns 429 without calling the service; `/countries` and `/initiate` use independent buckets
- `BankCountrySelect.test.tsx` (frontend) -- loading fallback (no error shown), live options with default-country-first ordering, `onChange` on selection, visible error on a failed fetch with no prior data, visible error on an empty-but-successful response, error suppressed when stale-but-real data is still shown during a failed background refetch, snap-to-first-available when the current value isn't in the loaded list
- `AdminControllerTest` -- `getSettings` reads the resolved provider; `updateEnableBanking` delegates the 2-arg writer
- `IntegrationsServiceTest` -- `isEffectivelyEnabled` = stored flag OR detected config (env/DB/session presence)
- Manual integration testing against real provider APIs

## Links

- Related ADR: [Dual bank providers](../decisions/2026-03-01-dual-bank-providers.md)
- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Downstream feature: [Budget & Cashflow](./budget.md) (consumes ingested transactions)
- Related: [bank-logos.md](./bank-logos.md) — logo capture/backfill and account card rendering
