# Architecture

> Project overview. This file describes the macro structure.
> Update when a new module is added or a flow changes.

## Overview

Picsou is a self-hosted personal-finance dashboard for an individual or a small family. It aggregates accounts from banks (PSD2/scraping), brokers (Trade Republic), crypto exchanges (Binance, Meria), and on-chain wallets (BTC/ETH/SOL); tracks balances over time, computes net worth, and helps members set savings goals, manage debts, and export their data. Each authenticated `AppUser` is linked to a `FamilyMember`, and every financial row is scoped by `member_id` with optional sharing.

The backend is one Rust crate (`backend-rs/`: Axum, Tokio, sqlx). It replaced the Java backend
in 1.3.0 and keeps its HTTP contract, its PostgreSQL schema and its Flyway history
([decision](./decisions/2026-11-21-rust-backend.md)). It runs on PostgreSQL 16 or on an embedded
SQLite file, the engine of the desktop app (`desktop/`, a Tauri shell that links the backend) and
of the single-container Docker stack.

The data flows below and the feature notes were written against the Java backend and still name
its classes (`SyncService`, `AccountController`) as the names of the components. Each Rust module
names the class it took over in its header comment: `grep -rn "SyncService" backend-rs/src` finds
it. A `.java` file name with no path is a class of the Java backend that no single Rust file
replaces.

## Backend modules

```
backend-rs/
├── migrations/          PostgreSQL migrations (Flyway naming and checksums), embedded at build time
├── src/
│   ├── main.rs, lib.rs  the binary (server, `picsou healthcheck`, the release CLI) and the crate root
│   ├── config.rs        typed configuration from the environment; sidecars.tsv lists the connector sidecars
│   ├── routes.rs        the route registry: every module registers its (method, path) here
│   ├── wiring.rs        connects each domain's seam traits to their implementations in AppState
│   ├── clock.rs         the injectable clock behind every "now" and "today"
│   ├── error.rs         AppError and its RFC 7807 rendering
│   ├── logging.rs       tracing to stdout, optional rolling file
│   ├── actuator.rs      /actuator/health (liveness, readiness) and /actuator/info
│   ├── db/              both engines (PostgreSQL, embedded SQLite), the query layer and the
│   │                    Flyway-compatible migrator; sqlite/ holds the SQLite schema, dialect and migrations
│   ├── http/            the HTTP edge: forwarded headers, body limit, CORS, security headers, CSRF,
│   │                    setup gate, rate limits, compression, the SPA
│   ├── auth/            JWT session cookies, remember-me tokens, access keys, the Auth extractor
│   ├── crypto/          encryption at rest (AES-256-GCM) and the install secrets
│   ├── setup_auth/      setup wizard, login, MFA, sessions, the desktop gate
│   ├── identity/        sign-in methods, access keys, member profile, the OAuth2 authorization
│   │                    server (oauth/), SSO (sso/)
│   ├── admin/           admin settings, member resets, call logs, telemetry, instance status
│   ├── family/          family members and the family view
│   ├── ledger/          shared by every domain: accounts, holdings, transactions, balance snapshots,
│   │                    FX reads, ownership shares, the ingestion primitives
│   ├── accounts/        accounts, dashboard, history, analysis/, logos, securities
│   ├── budget/          budgets, cashflow, categories, rules, recurring series, merchants
│   ├── wealth/          goals, savings, peer loans, real estate, SCPI, dividends
│   ├── market/          prices, FX, metals, instrument data providers
│   ├── sync/            Enable Banking, exchanges, wallets, IBKR, SimpleFIN, Wise, Louve, Finary
│   ├── connectors/      one module per sidecar bank, and sidecar/, the generic sidecar client
│   ├── native_sidecars/ the six pure-HTTP banks served in-process (BoursoBank, CIC, CMES, DEGIRO,
│   │                    Natixis Interépargne, Sofidy)
│   ├── transfer/        imports and exports: CSV, Actual Budget, HomeBank, Finary, the XLSX
│   │                    workbook, the member export
│   ├── ai/              AI transaction categorization
│   ├── mcp/             the embedded MCP server and its tools
│   ├── jobs/            schedulers, catch-up, cleanups, startup runners
│   └── testing/         TestApp, the in-process test harness
├── tests/integration/   one test binary, one file or folder per module
└── contract/            the HTTP contract harness and its goldens

desktop/
├── src-tauri/src/       the shell: supervisor.rs (in-process backend, data folder, secrets),
│                        sidecars.rs (Chromium sidecars on demand), webview_bank/ (BNP, AMEX and
│                        Revolut sign-in in the OS WebView), upgrade.rs (backup before an update),
│                        update.rs, i18n.rs
├── ui/                  the loading and error page
└── scripts/             fetch_runtime.py assembles CPython and the Chromium sidecars
```

Module layout, routes, handlers and tests: [`backend-rs/README.md`](../backend-rs/README.md).

## Frontend modules

```
frontend/src/
├── app/             Entry: App.tsx, providers, routes (lazy-loaded chunks)
├── pages/           Route pages: accounts/, dashboard/, goals/, login/, settings/, sync/
├── components/
│   ├── layout/      AppSidebar, AppLayout
│   ├── ui/          shadcn/ui generated (do not edit)
│   └── shared/      App-specific reusable components
├── features/        Feature slices: api.ts + hooks.ts per feature
├── stores/          Zustand stores (auth-store, app-store)
├── lib/             api-client, utils, constants, query-client
├── types/           api.ts (DTOs), app.ts (frontend types)
├── demo/            Demo mode interceptor + mock data
├── i18n/            i18next setup + FR/EN/DE/ES translations
└── main.tsx         Bootstrap + demo mode setup
```

## Main data flows

### 1. Bank sync

```
Client → SyncController → SyncService → BankConnectorPort → Enable Banking
```

Enable Banking (PSD2) is the bank connector. The connector reports each account's type; Enable Banking maps only `cash_account_type = CARD` to `CREDIT_CARD`, everything else is `CHECKING` (see `docs/features/bank-sync.md`).

### 2. Price refresh

```
SchedulerService (hourly) → PriceService → PriceProviderPort → CoinGecko / Yahoo Finance
                                        ↘ 15-min in-memory cache
                                        ↘ price_snapshot (last known price, ≤ 7 days)
```

`SchedulerService.refreshPrices` runs hourly over one global ticker set (account tickers ∪ holding tickers). CoinGecko for crypto, Yahoo Finance for stocks/ETFs. On-demand reads resolve a whole set in one call and degrade in three steps — cache, batched provider call, last recorded price — so a rate-limited provider makes prices *older*, not absent. See [ADR 2026-08-01](./decisions/2026-08-01-last-known-price-fallback.md).

After the prices, the same pass queues a background lookup of share and ETF logos (`InstrumentLogoService` → `InstrumentLogoPort` → Yahoo quote page), at most 10 tickers, stored once in `instrument_logo` and served by `/api/instrument-logos`. It yields to the price path on a Yahoo 429. See [holding-logos.md](./features/holding-logos.md).

### 3. Trade Republic

```
Client → TradeRepublicController → TRSyncService → TRAdapter → tr-auth (Python) → TR WebSocket
```

Broker sync via the `tr-auth` Python sidecar (login confirmed in the Trade Republic app, session refresh); the backend reads the portfolio over the WebSocket. Two modes: automatic sync and CSV import fallback. The session is a `SidecarSession` row (bank `TRADE_REPUBLIC`), synced through `SidecarSyncTemplate` like the other sidecar banks.

### 4. Bourse Direct

```text
Client -> BourseDirectController -> BourseDirectSyncService
       -> BourseDirectAdapter -> internal FastAPI/Playwright sidecar -> Bourse Direct
```

The sidecar owns browser login, one-time-code completion and normalization of
modern/legacy portfolio streams. It returns only strict, reconciled snapshots.
The backend queues imports, performs external calls outside database
transactions, then atomically replaces holdings and writes the daily account
snapshot. The encrypted browser state and observable job status live in
`SidecarSession`, shared with the other sidecar banks ([ADR](./decisions/2026-11-02-one-sidecar-session-table.md)). See the [Bourse Direct ADR](./decisions/2026-07-21-bourse-direct-isolated-atomic-sync.md).

### 5. Amundi Épargne Salariale

```text
Client -> AmundiController -> AmundiSyncService
       -> AmundiAdapter -> internal FastAPI/Playwright sidecar -> Amundi espace épargnant
```

Same shape as Bourse Direct, for a login that is captcha-gated and always
second-factor protected. The sidecar owns the browser login, the app-push or SMS
step, and normalization of the `dispositifsMulti` payload; it returns only strict,
reconciled snapshots. The backend queues imports, calls upstream outside
database transactions, then atomically replaces each plan's holdings and writes
the daily snapshot. One Picsou account per *dispositif*, typed `EMPLOYEE_SAVINGS`.
Encrypted session and job status live in `SidecarSession`. See the
[Amundi ADR](./decisions/2026-08-09-amundi-epargne-salariale-sidecar.md).

A SCPI share is not this, and it is not a house either. It is an `SCPI` account valued at
the withdrawal price times the share count. See
[SCPI shares](./features/scpi.md). The same CORUM login also syncs CORUM Life
(assurance-vie) contracts, one `ASSURANCE_VIE` account each with a holding per fund, in the
same read and the same transaction. See [CORUM Life sync](./features/corum-life.md).

### 6. Fortuneo

```text
Client -> FortuneoController -> FortuneoSyncService
       -> FortuneoAdapter -> internal FastAPI/Playwright sidecar -> Fortuneo
```

The sidecar owns SSO login, six-digit-code completion and normalization of the
provider API and legacy HTML pages. It returns only complete, reconciled account
snapshots and follows securities-history pagination to the declared row count.
The backend performs upstream I/O before one atomic persistence phase,
backfills market prices and reconstructs value and invested history from batched
inputs. Encrypted browser state and observable job status live in
`SidecarSession`. See the
[Fortuneo ADR](./decisions/2026-07-26-fortuneo-isolated-atomic-history-sync.md).

### 7. Crypto exchange

```
Client → CryptoExchangeController → CryptoExchangeSyncService → CryptoExchangePort → exchange API
                                                                  ├── BinanceAdapter → Binance API
                                                                  └── MeriaAdapter   → Meria API
```

Exchange API credentials are encrypted at rest with AES-256-GCM (`CryptoEncryption`); `CRYPTO_ENCRYPTION_KEY` env var required. Which credentials an exchange needs is declared by its adapter: Binance signs each request with an HMAC over an API secret, Meria authenticates with a single read-only API key and stores a `NULL` secret (`CryptoExchangePort.requiresApiSecret()`).

### 8. Wallet sync

```
Client → WalletController → WalletSyncService → WalletPort → blockchain RPCs
```

Three adapters: Bitcoin (Blockstream Esplora, BIP32 xpub/zpub/descriptors), EVM (keyless PublicNode RPCs — one `0x` address fanned out across Ethereum, BNB Chain, Polygon, Arbitrum, Optimism, Base, Avalanche; native + curated ERC-20 tokens), Solana (RPC + curated SPL tokens). See the [EVM multichain wallets ADR](./decisions/2026-07-17-evm-multichain-wallets.md).

### 9. Dashboard

```
Client → DashboardController → DashboardService → Account + Snapshot + PriceService aggregation
```

Aggregates all account balances, applies current prices via `PriceService`, computes net worth and allocation breakdown.

### 10. Goals

```
Client → GoalController → GoalService → Goal + GoalMonthOverride repos
```

Savings goals with deadlines, linked to accounts via M:N join table (`goal_account`). Monthly tracking with optional per-month overrides.

### 11. First-launch setup wizard

```
Browser → SetupFilter → /setup → SetupController → SetupService → AppSetting / SetupAudit
                                                  → CryptoKeyGeneratorService
                                                  → EnableBankingKeyPairService
                                                  → IntegrationsService
```

`SetupFilter` redirects every request to `/setup` until `SetupState.completed = true`. The wizard collects admin credentials, security settings (CORS, encryption key), and per-integration credentials. Each step is appended to `setup_audit` (actor, IP, timestamp). After completion, the filter becomes a no-op. With `SSO_ADMIN_GROUP` set, the admin step only offers SSO: the first member of that IdP group to sign in becomes the password-less administrator (flow 16).

### 12. Authentication & MFA

Tomcat's native forwarding support trusts forwarded client addresses only from the proxy named by
`PICSOU_PROXY_IP`, and nobody when it is unset. All per-IP limiters, session metadata and audit
readers share `getRemoteAddr()` through this policy. The public origin (scheme, host, port) is a
separate decision: `ForwardedOriginCustomizer` applies `X-Forwarded-Proto`, `X-Forwarded-Host` and
`X-Forwarded-Port` from any peer, whether or not `PICSOU_PROXY_IP` is set, as nginx did. See
[CORS & cookie security](./features/security-cors-cookies.md).

```
POST /api/auth/login → AuthController → (if 2FA) issue mfa_challenge JWT → 401 + cookie
POST /api/mfa/verify → MfaController → MfaService.verifyTotp() → issue access + refresh
                                    → optionally issue persistent_token (Remember Me)
Every request → JwtAuthenticationFilter → check tv claim vs AppUser.tokenVersion
              → PersistentTokenAuthFilter → re-issue access if persistent_token valid
```

Password change in `AuthController.changePassword` bumps `AppUser.tokenVersion`, revokes all `PersistentSession`s for the user, clears the persistent cookie, and re-issues fresh access/refresh cookies.

### 13. Family sharing

```
Member viewing dashboard → DashboardService scopes by UserContext.currentMemberId()
Family dashboard → FamilyViewController → FamilyViewService
                → for each FamilyMember: read SharingSettings (NONE / ALL / MANUAL)
                → if MANUAL, intersect with SharedResource(memberId, resourceType, resourceId)
```

Admins can use `/admin/impersonate/{memberId}` to view another member's data; `UserContext.getMemberIdOverride()` returns the override; audit trail in `setup_audit`.

### 14. GDPR data export

```
POST /api/me/export → ReAuthService accepts a valid reauth_proof cookie,
                      else the inline TOTP (2FA on) or password (2FA off)
                    → DataExportService runs each EntityExporter
                    → emits a single ZIP containing JSON + CSV per entity
```

Runs in one read-only transaction; rate-limited (`backend-rs/src/http/rate_limit.rs`).

### 15. Loan amortization

```
GET /api/accounts/{id}/loan-schedule → AccountController → LoanAmortizationService
                                    → returns monthly schedule (principal/interest split)
```

Computed on the fly from `Debt` (principal, rate, term, fees) — no per-month rows persisted. See ADR `2026-04-26-loan-amortization-on-the-fly.md`.

### 16. SSO login

```
/login → form POST /oauth2/sso/authorization/oidc → SsoStartGuardFilter → IdP (PKCE, state, nonce)
IdP → GET /login/sso/callback (SPA page) → form POST /oauth2/sso/code/oidc
    → OIDC client (code exchange, ID token, userinfo)
    → SsoAuthenticationSuccessHandler → SsoAccountService (resolve by iss + sub)
                                      → SsoRoleSyncService (admin group)
                                      → SessionIssuer (Picsou's own cookies, sso_at)
    → /login/sso-complete → POST /api/auth/refresh → app
```

Optional, configured by the `SSO_*` environment variables, and handled by
`backend-rs/src/identity/sso/` on `/oauth2/sso/**`. The IdP only authenticates; IdP tokens
are discarded. SSO sessions pass through the IdP again after `SSO_SESSION_REVALIDATE_AFTER`
(refresh answers 401 `SSO_REVALIDATE`). The same flow handles re-authentication, identity
linking, invitation activation and the first admin. See [sso-login.md](./features/sso-login.md)
and the [ADR](./decisions/2026-11-04-sso-login-alongside-passwords.md).

## External dependencies

| Service | Usage | Config |
|---------|-------|--------|
| PostgreSQL 16 | Persistence | `SPRING_DATASOURCE_URL` |
| SQLite (embedded) | Persistence for the desktop app and `docker/compose.sqlite.yml` | `DATABASE_URL=sqlite:///…` |
| Flyway | Schema migrations | `backend-rs/migrations/` |
| Enable Banking | PSD2 bank sync (optional) | `ENABLEBANKING_*` |
| SimpleFIN | Bank balances and posted transactions from a setup token (optional, no app credentials) | User-supplied token |
| Wise API | Wise jar balances from a personal API token (optional, no app credentials) | User-supplied token |
| Trade Republic | Broker sync via Python microservice | `TR_AUTH_URL` |
| Bourse Direct | PEA/CTO sync via internal Python sidecar | `BOURSE_DIRECT_AUTH_URL` |
| Amundi Épargne Salariale | PEE/PEG/PERCO/PER sync via internal Python sidecar | `AMUNDI_AUTH_URL` |
| Fortuneo | Current accounts, PEA/PEA-PME/CTO and transaction history via internal Python sidecar | `FORTUNEO_AUTH_URL` |
| BoursoBank | Current accounts, livrets and PEA/CTO sync via internal Python sidecar | `BOURSO_AUTH_URL` |
| CORUM | SCPI shares and CORUM Life (assurance-vie) contracts via internal Python sidecar (`corum-auth`) | `CORUM_AUTH_URL` |
| Caisse d'Epargne | Current account, Livret A, Livret Jeune, LDDS, card, loans and PER via internal Python sidecar; browser-assisted login (keypad + Sécur'Pass), on demand | `CAISSE_EPARGNE_AUTH_URL` |
| Banque Populaire | Current account, Livret A, Livret Jeune, LDDS, Compte sur Livret, PEL, term deposits, cooperative shares (no cards) and PER via internal Python sidecar sharing the BPCE code with Caisse d'Epargne; region + keypad + Sécur'Pass login, on demand | `BANQUE_POPULAIRE_AUTH_URL` |
| La Banque Postale | Current account and livrets (Livret A, LDDS, LEP) via internal Chromium login sidecar, on demand | `LBP_AUTH_URL` |
| BNP Paribas / Hello bank! | Current accounts, savings, PEA/CTO, life insurance, PER and loans, and transaction history via internal Camoufox sidecar (`bnp-auth`); customer number, keypad clicked in the member's browser, then app approval or SMS code, on demand | `BNP_AUTH_URL` |
| Natixis Interépargne | PEE and PER employee-savings plans and their operations history via internal Python sidecar (`natixis-auth`); SMS, voice or e-mail code once, then the refresh token | `NATIXIS_AUTH_URL` |
| DEGIRO | Compte-titres sync via internal Python sidecar (`degiro-auth` in `docker/docker-compose.yml`) | `DEGIRO_AUTH_URL` |
| Binance | Crypto exchange balances | Via CryptoExchangePort |
| Meria | Crypto exchange balances (wallets + staking + lending) | Via CryptoExchangePort |
| CoinGecko | Crypto prices (free) | No config |
| Yahoo Finance | Stock/ETF prices (free); quote page read once per ticker for its logo | No config (`INSTRUMENT_LOGOS_ENABLED=false` stops logo lookups) |
| PublicNode EVM RPCs | EVM wallet balances (Ethereum, BNB Chain, Polygon, Arbitrum, Optimism, Base, Avalanche) — native + curated ERC-20 | No config (keyless) |
| Solana RPC | Solana wallet balances | No config |
| Blockstream Esplora | Bitcoin wallet balances | No config |
| Finary | Import xlsx or API sync (optional) | `FINARY_*` |
| OIDC identity provider | SSO login, admin group (optional) | `SSO_*` |

## Key constraints

- **Ports & adapters:** a port exists only where providers are swappable (two implementations or a real swap seam); services depend on the port there. A single-provider connector client is injected as its concrete class. See [the ADR](./decisions/2026-11-02-ports-only-for-swappable-providers.md).
- **Migrations own the schema:** every schema change is a new migration file, in `backend-rs/migrations/` for PostgreSQL and in `backend-rs/src/db/sqlite/migrations/` for SQLite.
- **Multi-member families:** each authenticated user is an `AppUser` linked to a `FamilyMember`. All financial rows are scoped by `member_id`; cross-member visibility is gated by `SharingSettings` + `SharedResource`. Admin role can impersonate any member.
- **Auth:** JWT (`access_token` + `refresh_token`) in HttpOnly `SameSite=Lax` cookies. Optional TOTP 2FA, rotating persistent sessions ("Remember Me"), stateless invalidation via `tokenVersion` claim on password change. Optional SSO through one external OIDC provider ends in the same cookies: the IdP only authenticates, accounts are linked by `(iss, sub)` only, SSO sessions carry `sso_at` and are re-validated through the IdP after `SSO_SESSION_REVALIDATE_AFTER`, and accounts may have no password. Credential changes need the short-lived `reauth_proof` cookie.
- **First-launch setup wizard:** on a fresh install, `SetupFilter` redirects to a wizard that creates the admin, configures CORS, generates the encryption key, and seeds integration credentials. No env-var editing required.
- **AES-256-GCM encryption:** crypto-exchange API secrets, bank session tokens, and Finary credentials encrypted at rest. `CRYPTO_ENCRYPTION_KEY` must be backed up — lost key means re-authenticating all integrations.
- **Scheduled tasks:** `SchedulerService` handles daily balance snapshots, price cache refresh, and per-member auto-sync. `CatchUpScheduler` starts its cron jobs and runs a missed slot once after a restart or wake ([scheduler-catch-up.md](./features/scheduler-catch-up.md)).
- **Demo mode:** frontend-only, mock interceptor short-circuits API calls, no backend needed.
- **Secrets from environment variables or wizard store:** never hardcoded. Required at startup: `JWT_SECRET`, `CRYPTO_ENCRYPTION_KEY`. `APP_USERNAME` / `APP_PASSWORD_HASH` are optional — the wizard creates the admin if they're absent.

## Disabled / experimental integrations

- **Powens / Budget Insight** — the experimental adapter of the Java backend, never
  validated against a real Powens tenant, was not carried over to the Rust backend.
  Enable Banking is the bank connector.
