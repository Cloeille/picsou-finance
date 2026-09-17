# Architecture

> Project overview. This file describes the macro structure.
> Update when a new module is added or a flow changes.

## Overview

Picsou is a self-hosted personal-finance dashboard for an individual or a small family. It aggregates accounts from banks (PSD2/scraping), brokers (Trade Republic), crypto exchanges (Binance, Meria), and on-chain wallets (BTC/ETH/SOL); tracks balances over time, computes net worth, and helps members set savings goals, manage debts, and export their data. Each authenticated `AppUser` is linked to a `FamilyMember`, and every financial row is scoped by `member_id` with optional sharing.

## Backend modules

```
com.picsou/
├── model/          JPA entities — financial: Account, AccountHolding, Transaction,
│                   BalanceSnapshot, Goal, GoalManualContribution, GoalContributor,
│                   Debt, RealEstateMetadata, PropertyValuation, AccountOwnership, WalletAddress;
│                   integrations: Requisition, TradeRepublicSession, CryptoExchangeSession,
│                   FinarySession, BoursoSession, BourseDirectSession, DegiroSession,
│                   AmundiSession,
│                   PriceSnapshot;
│                   identity & sharing: AppUser, FamilyMember, UserRole, SharingSettings,
│                   SharingLevel, SharedResource, UserMfa, UserMfaRecoveryCode,
│                   PersistentSession;
│                   setup: AppSetting, SetupState, SetupAudit
├── repository/     Spring Data JPA interfaces (one per entity, ~25 repos)
├── service/        Business logic — financial: AccountService, GoalService,
│                   DashboardService, HistoryService, ManualTransactionService,
│                   HoldingComputeService, LoanAmortizationService, PriceService,
│                   SchedulerService;
│                   integrations: SyncService, TradeRepublicSyncService,
│                   CryptoExchangeSyncService, WalletSyncService, BoursoSyncService,
│                   BourseDirectSyncService, DegiroSyncService, DegiroSessionStatusWriter,
│                   AmundiSyncService,
│                   FinaryImportService, FinaryApiSyncService;
│                   identity & family: UserContext, FamilyService, FamilyViewService,
│                   MfaService, PersistentSessionService, ReAuthService;
│                   setup: SetupService, SetupAuditService, IntegrationsService,
│                   IntegrationsHealthService, CryptoKeyGeneratorService,
│                   EnableBankingKeyPairService
├── controller/     REST controllers under /api/ — auth, mfa, sessions, family,
│                   accounts, transactions, holdings, goals, debts, dashboard, history,
│                   sync, tr, bourso, bourse-direct, degiro, amundi, crypto-exchange, wallet,
│                   finary-import, finary-api-sync, setup, admin, admin-mfa, me-export, price
├── dto/            Request/response records (records are the convention)
├── port/           Port interfaces (BankConnectorPort, PriceProviderPort,
│                   TradeRepublicPort, CryptoExchangePort, WalletPort, BoursoPort,
│                   BourseDirectPort, DegiroPort, AmundiPort)
├── adapter/        Port implementations + util/BitcoinKeyUtils
│   ├── EnableBankingBankConnector (bank sync)
│   ├── PowensBankConnector (Powens / Budget Insight — experimental, disabled in 1.0.0)
│   ├── BoursoAdapter (BoursoBank — current accounts, livrets and PEA/CTO sidecar)
│   ├── DegiroAdapter (DEGIRO — compte-titres sync; requires `degiro-auth` uncommented in docker-compose.yml)
│   ├── CoinGeckoPriceProvider, YahooFinancePriceProvider (prices)
│   ├── OpenFigiIsinConverter (ISIN → Yahoo ticker)
│   ├── TradeRepublicAdapter (broker)
│   ├── BourseDirectAdapter (broker sidecar)
│   ├── AmundiAdapter (employee-savings sidecar)
│   ├── BinanceAdapter, MeriaAdapter (crypto exchanges)
│   ├── BitcoinWalletAdapter, EvmWalletAdapter, SolanaWalletAdapter (on-chain)
│   └── util/BitcoinKeyUtils (BIP32 key derivation, Base58Check, Bech32)
├── finary/         Finary import + API-sync subsystem (client, DTOs, SyncSessionData,
│                   FinaryPersistenceHelper, FinaryApiSyncService)
├── export/         GDPR data export — DataExportService orchestrator + per-entity
│                   exporters (Profile, Accounts, Holdings, Transactions, Goals, Debts,
│                   Wallets, SharedResources, BankConnections, BalanceSnapshots) + CsvWriter
├── config/         SecurityConfig, JwtUtil, JwtAuthenticationFilter,
│                   PersistentTokenAuthFilter, AuthCookieWriter, SecureCookieProvider,
│                   DynamicCorsConfigurationSource, LoggingCorsProcessor, SetupFilter,
│                   DataSeeder, RateLimitConfig, FinaryProperties, CryptoEncryption,
│                   TotpConfig, EnableBankingConfigProvider, PriceBackfillRunner,
│                   StartupSyncService
└── exception/      GlobalExceptionHandler, ResourceNotFoundException, SyncException,
                    MfaException, TotpRequiredException
```

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

Enable Banking (PSD2) is the canonical `BankConnectorPort` in 1.0.0. The Powens adapter (`PowensBankConnector`) ships in the codebase behind `@ConditionalOnExpression` but is **experimental and untested** — `@Primary` was removed for 1.0.0 so Enable Banking remains injected even when `POWENS_CLIENT_ID` is set. `SyncService.detectType()` maps provider types to the `AccountType` enum.

### 2. Price refresh

```
SchedulerService (hourly) → PriceService → PriceProviderPort → CoinGecko / Yahoo Finance
                                        ↘ 15-min in-memory cache
                                        ↘ price_snapshot (last known price, ≤ 7 days)
```

`SchedulerService.refreshPrices` runs hourly over one global ticker set (account tickers ∪ holding tickers). CoinGecko for crypto, Yahoo Finance for stocks/ETFs. On-demand reads resolve a whole set in one call and degrade in three steps — cache, batched provider call, last recorded price — so a rate-limited provider makes prices *older*, not absent. See [ADR 2026-08-01](./decisions/2026-08-01-last-known-price-fallback.md).

### 3. Trade Republic

```
Client → TradeRepublicController → TRSyncService → TRAdapter → tr-auth (Python) → TR WebSocket
```

Broker sync via Python microservice (Playwright automation). Two modes: automatic WebSocket sync and CSV import fallback. Session persisted in `TradeRepublicSession` entity.

### 4. Bourse Direct

```text
Client -> BourseDirectController -> BourseDirectSyncService -> BourseDirectPort
       -> BourseDirectAdapter -> internal FastAPI/Playwright sidecar -> Bourse Direct
```

The sidecar owns browser login, one-time-code completion and normalization of
modern/legacy portfolio streams. It returns only strict, reconciled snapshots.
The Java service queues imports, performs external calls outside database
transactions, then atomically replaces holdings and writes the daily account
snapshot. The encrypted browser state and observable job status live in
`BourseDirectSession`. See the [Bourse Direct ADR](./decisions/2026-07-21-bourse-direct-isolated-atomic-sync.md).

### 5. Amundi Épargne Salariale

```text
Client -> AmundiController -> AmundiSyncService -> AmundiPort
       -> AmundiAdapter -> internal FastAPI/Playwright sidecar -> Amundi espace épargnant
```

Same shape as Bourse Direct, for a login that is captcha-gated and always
second-factor protected. The sidecar owns the browser login, the app-push or SMS
step, and normalization of the `dispositifsMulti` payload; it returns only strict,
reconciled snapshots. The Java service queues imports, calls upstream outside
database transactions, then atomically replaces each plan's holdings and writes
the daily snapshot. One Picsou account per *dispositif*, typed `EMPLOYEE_SAVINGS`.
Encrypted session and job status live in `AmundiSession`. See the
[Amundi ADR](./decisions/2026-08-09-amundi-epargne-salariale-sidecar.md).

### 6. Fortuneo

```text
Client -> FortuneoController -> FortuneoSyncService -> FortuneoPort
       -> FortuneoAdapter -> internal FastAPI/Playwright sidecar -> Fortuneo
```

The sidecar owns SSO login, six-digit-code completion and normalization of the
provider API and legacy HTML pages. It returns only complete, reconciled account
snapshots and follows securities-history pagination to the declared row count.
The Java service performs upstream I/O before one atomic persistence phase,
backfills market prices and reconstructs value and invested history from batched
inputs. Encrypted browser state and observable job status live in
`FortuneoSession`. See the
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

`SetupFilter` redirects every request to `/setup` until `SetupState.completed = true`. The wizard collects admin credentials, security settings (CORS, encryption key), and per-integration credentials. Each step is appended to `setup_audit` (actor, IP, timestamp). After completion, the filter becomes a no-op.

### 12. Authentication & MFA

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
POST /api/me/export/reauth → ReAuthService verifies password (+ TOTP if enabled)
GET  /api/me/export        → DataExportService runs each EntityExporter
                          → emits a single ZIP containing JSON + CSV per entity
```

Wrapped in a read-only Spring transaction; rate-limited via `RateLimitConfig`.

### 15. Loan amortization

```
GET /api/accounts/{id}/loan-schedule → AccountController → LoanAmortizationService
                                    → returns monthly schedule (principal/interest split)
```

Computed on the fly from `Debt` (principal, rate, term, fees) — no per-month rows persisted. See ADR `2026-04-26-loan-amortization-on-the-fly.md`.

## External dependencies

| Service | Usage | Config |
|---------|-------|--------|
| PostgreSQL 16 | Persistence | `SPRING_DATASOURCE_URL` |
| Flyway | Schema migrations | `db/migration/` (latest V81) |
| Enable Banking | PSD2 bank sync (optional) | `ENABLEBANKING_*` |
| Powens / Budget Insight | Scraping bank sync (**experimental, disabled in 1.0.0**) | `POWENS_*` |
| Trade Republic | Broker sync via Python microservice | `TR_AUTH_URL` |
| Bourse Direct | PEA/CTO sync via internal Python sidecar | `BOURSE_DIRECT_AUTH_URL` |
| Amundi Épargne Salariale | PEE/PEG/PERCO/PER sync via internal Python sidecar | `AMUNDI_AUTH_URL` |
| Fortuneo | Current accounts, PEA/PEA-PME/CTO and transaction history via internal Python sidecar | `FORTUNEO_AUTH_URL` |
| BoursoBank | Current accounts, livrets and PEA/CTO sync via internal Python sidecar | `BOURSO_AUTH_URL` |
| DEGIRO | Compte-titres sync via internal Python sidecar (sidecar off by default — uncomment in `docker-compose.yml`) | `DEGIRO_AUTH_URL` |
| Binance | Crypto exchange balances | Via CryptoExchangePort |
| Meria | Crypto exchange balances (wallets + staking + lending) | Via CryptoExchangePort |
| CoinGecko | Crypto prices (free) | No config |
| Yahoo Finance | Stock/ETF prices (free) | No config |
| PublicNode EVM RPCs | EVM wallet balances (Ethereum, BNB Chain, Polygon, Arbitrum, Optimism, Base, Avalanche) — native + curated ERC-20 | No config (keyless) |
| Solana RPC | Solana wallet balances | No config |
| Blockstream Esplora | Bitcoin wallet balances | No config |
| Finary | Import xlsx or API sync (optional) | `FINARY_*` |

## Key constraints

- **Ports & adapters:** controllers/services never import adapters directly. All external integrations go through port interfaces.
- **Flyway owns schema:** never use `ddl-auto: create/update`. Every schema change is a new migration file (latest: V81).
- **Multi-member families:** each authenticated user is an `AppUser` linked to a `FamilyMember`. All financial rows are scoped by `member_id`; cross-member visibility is gated by `SharingSettings` + `SharedResource`. Admin role can impersonate any member.
- **Auth:** JWT (`access_token` + `refresh_token`) in HttpOnly `SameSite=Lax` cookies. Optional TOTP 2FA, rotating persistent sessions ("Remember Me"), stateless invalidation via `tokenVersion` claim on password change.
- **First-launch setup wizard:** on a fresh install, `SetupFilter` redirects to a wizard that creates the admin, configures CORS, generates the encryption key, and seeds integration credentials. No env-var editing required.
- **AES-256-GCM encryption:** crypto-exchange API secrets, bank session tokens, and Finary credentials encrypted at rest. `CRYPTO_ENCRYPTION_KEY` must be backed up — lost key means re-authenticating all integrations.
- **Scheduled tasks:** `SchedulerService` handles daily balance snapshots, price cache refresh, and per-member auto-sync.
- **Demo mode:** frontend-only, mock interceptor short-circuits API calls, no backend needed.
- **Secrets from environment variables or wizard store:** never hardcoded. Required at startup: `JWT_SECRET`, `CRYPTO_ENCRYPTION_KEY`. `APP_USERNAME` / `APP_PASSWORD_HASH` are optional — the wizard creates the admin if they're absent.

## Disabled / experimental integrations

- **Powens / Budget Insight** — `PowensBankConnector` ships in 1.0.0 but is
  experimental and has not been tested end-to-end against a real Powens tenant.
  The `@Primary` annotation was removed so Enable Banking remains the injected
  `BankConnectorPort` even when `POWENS_CLIENT_ID` is set. Re-enable by
  re-adding `@Primary` once the adapter has been validated.
