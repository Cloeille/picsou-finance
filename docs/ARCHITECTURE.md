# Architecture

> Project overview. This file describes the macro structure.
> Update when a new module is added or a flow changes.

## Overview

Picsou is a self-hosted personal-finance dashboard for an individual or a small family. It aggregates accounts from banks (PSD2/scraping), brokers (Trade Republic), crypto exchanges (Binance, Meria), and on-chain wallets (BTC/ETH/SOL); tracks balances over time, computes net worth, and helps members set savings goals, manage debts, and export their data. Each authenticated `AppUser` is linked to a `FamilyMember`, and every financial row is scoped by `member_id` with optional sharing.

The backend uses Spring Boot 3.5.16 with Spring AI 1.1.8, as recorded in the
[Spring AI keep-alive decision](./decisions/2026-10-27-spring-ai-1-1-for-mcp-sse-keep-alive.md).
A Spring Boot 4 upgrade also requires coordinated Spring AI, Jackson, Spring Security, and test
changes, so it remains a separately scoped migration.

## Backend modules

```
com.picsou/
├── model/          JPA entities — financial: Account, AccountHolding, Transaction,
│                   BalanceSnapshot, Goal, GoalManualContribution, GoalContributor,
│                   Debt, RealEstateMetadata, PropertyValuation, AccountOwnership, WalletAddress;
│                   integrations: Requisition, CryptoExchangeSession,
│                   FinarySession, DegiroSession, RevolutSession,
│                   SidecarSession (one row per sidecar bank and member, Trade Republic included),
│                   PriceSnapshot;
│                   identity & sharing: AppUser, FamilyMember, UserRole, SharingSettings,
│                   SharingLevel, SharedResource, UserMfa, UserMfaRecoveryCode,
│                   PersistentSession, UserIdentity (linked SSO identity);
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
│                   MfaService, PersistentSessionService, ReAuthService,
│                   SessionIssuer, ActivationService, SignInMethodsService,
│                   UsernameDeriver;
│                   sso/: SsoAccountService, SsoRoleSyncService, GroupsClaimParser,
│                   SsoIdentity, SsoIntent;
│                   setup: SetupService, SetupAuditService, IntegrationsService,
│                   IntegrationsHealthService, CryptoKeyGeneratorService,
│                   EnableBankingKeyPairService
├── controller/     REST controllers under /api/ — auth, mfa, sessions, family,
│                   accounts, transactions, holdings, goals, debts, dashboard, history,
│                   sync, tr, bourso, bourse-direct, degiro, amundi, crypto-exchange, wallet,
│                   finary-import, finary-api-sync, setup, admin, admin-mfa, me-export, price,
│                   sign-in methods (/api/auth/sso, reauth, password, identities),
│                   admin member identities
├── dto/            Request/response records (records are the convention)
├── port/           Port interfaces for swappable providers (BankConnectorPort,
│                   PriceProviderPort, CryptoExchangePort, WalletPort, ...) and the
│                   shared sidecar types (SidecarErrorCode, SidecarInitiate)
├── adapter/        Port implementations, single-provider connector clients
│                   (TradeRepublicAdapter, BoursoAdapter, ...) + util/BitcoinKeyUtils
│   ├── EnableBankingBankConnector (bank sync)
│   ├── PowensBankConnector (Powens / Budget Insight — experimental, disabled in 1.0.0)
│   ├── BoursoAdapter (BoursoBank — current accounts, livrets and PEA/CTO sidecar)
│   ├── DegiroAdapter (DEGIRO — compte-titres sync; runs as `degiro-auth` in docker/docker-compose.yml)
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
│                   Wallets, LinkedIdentities, SharedResources, BankConnections,
│                   BalanceSnapshots) + CsvWriter
├── config/         SecurityConfig, JwtUtil, JwtAuthenticationFilter,
│                   PersistentTokenAuthFilter, AuthCookieWriter, SecureCookieProvider,
│                   DynamicCorsConfigurationSource, LoggingCorsProcessor, SetupFilter,
│                   DataSeeder, RateLimitConfig, FinaryProperties, CryptoEncryption,
│                   TotpConfig, EnableBankingConfigProvider, PriceBackfillRunner,
│                   StartupSyncService;
│                   SSO: SsoProperties, SsoEnabledCondition, SsoSecurityConfig,
│                   SsoClientRegistrationRepository, SsoStartGuardFilter,
│                   SsoAuthorizationRequestResolver, SsoAuthorizationRequestRepository,
│                   SsoAuthentication{Success,Failure}Handler, SafeRedirect
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
The Java service queues imports, performs external calls outside database
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
reconciled snapshots. The Java service queues imports, calls upstream outside
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
The Java service performs upstream I/O before one atomic persistence phase,
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

Wrapped in a read-only Spring transaction; rate-limited via `RateLimitConfig`.

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
    → Spring OIDC client (code exchange, ID token, userinfo)
    → SsoAuthenticationSuccessHandler → SsoAccountService (resolve by iss + sub)
                                      → SsoRoleSyncService (admin group)
                                      → SessionIssuer (Picsou's own cookies, sso_at)
    → /login/sso-complete → POST /api/auth/refresh → app
```

Optional, configured by the `SSO_*` environment variables, and served by its own
`SecurityFilterChain` at `@Order(0)` on `/oauth2/sso/**`. The IdP only authenticates; IdP tokens
are discarded. SSO sessions pass through the IdP again after `SSO_SESSION_REVALIDATE_AFTER`
(refresh answers 401 `SSO_REVALIDATE`). The same flow handles re-authentication, identity
linking, invitation activation and the first admin. See [sso-login.md](./features/sso-login.md)
and the [ADR](./decisions/2026-11-04-sso-login-alongside-passwords.md).

## External dependencies

| Service | Usage | Config |
|---------|-------|--------|
| PostgreSQL 16 | Persistence | `SPRING_DATASOURCE_URL` |
| Flyway | Schema migrations | `db/migration/` |
| Enable Banking | PSD2 bank sync (optional) | `ENABLEBANKING_*` |
| SimpleFIN | Bank balances and posted transactions from a setup token (optional, no app credentials) | User-supplied token |
| Wise API | Wise jar balances from a personal API token (optional, no app credentials) | User-supplied token |
| Powens / Budget Insight | Scraping bank sync (**experimental, disabled in 1.0.0**) | `POWENS_*` |
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
- **Flyway owns schema:** never use `ddl-auto: create/update`. Every schema change is a new migration file.
- **Multi-member families:** each authenticated user is an `AppUser` linked to a `FamilyMember`. All financial rows are scoped by `member_id`; cross-member visibility is gated by `SharingSettings` + `SharedResource`. Admin role can impersonate any member.
- **Auth:** JWT (`access_token` + `refresh_token`) in HttpOnly `SameSite=Lax` cookies. Optional TOTP 2FA, rotating persistent sessions ("Remember Me"), stateless invalidation via `tokenVersion` claim on password change. Optional SSO through one external OIDC provider ends in the same cookies: the IdP only authenticates, accounts are linked by `(iss, sub)` only, SSO sessions carry `sso_at` and are re-validated through the IdP after `SSO_SESSION_REVALIDATE_AFTER`, and accounts may have no password. Credential changes need the short-lived `reauth_proof` cookie.
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
