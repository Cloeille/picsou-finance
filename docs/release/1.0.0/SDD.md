# Software Design Description (SDD)

> Project: **Picsou** — version **1.0.0**
> Date: 2026-04-26
> Format: IEEE 1016-style, condensed

This SDD describes HOW the [SRS](./SRS.md) is realised. It is the
high-level architectural view; component-level detail is in the
companion [SDS](./SDS.md).

---

## 1. Architectural overview

Picsou is a single-deployment, single-tenant web application:

```
┌────────────────────┐     ┌────────────────────────┐     ┌────────────┐
│  React 19 frontend │────▶│ Spring Boot 3.4 backend │────▶│ PostgreSQL │
│  Vite / Bun / TS   │◀────│      Tomcat :8080      │     │    16      │
└────────────────────┘     └────────────┬───────────┘     └────────────┘
                                        │
              ┌─────────────┬───────────┼────────────┬─────────────┐
              ▼             ▼           ▼            ▼             ▼
        Enable Banking  CoinGecko  Yahoo Finance  Trade Rep.  Binance / RPC
         (PSD2 OAuth)    (crypto)   (stocks)    (sidecar WS)  (crypto)
```

### 1.1 Style: Hexagonal (ports & adapters)

External integrations are abstracted behind **ports** in
`com.picsou.port`:

- `BankConnectorPort`
- `PriceProviderPort`
- `TradeRepublicPort`
- `CryptoExchangePort`
- `WalletPort`
- `BoursoPort` *(disabled in 1.0.0)*

Implementations (`com.picsou.adapter`) are wired via Spring
`@Primary` + `@ConditionalOnExpression` so an unconfigured adapter
silently does not register.

### 1.2 Layered packaging

```
com.picsou
├── controller/   REST surface — only HTTP wiring + DTO mapping
├── service/      Business logic — transactional, member-scoped
├── repository/   Spring Data JPA interfaces, all queries by member_id
├── model/        JPA entities
├── dto/          Records for request/response shapes
├── port/         Hexagonal ports
├── adapter/      Port implementations
├── config/       Spring beans (security, JWT, rate limiting, properties)
├── exception/    GlobalExceptionHandler + typed exceptions
└── export/       GDPR data export pipeline (one Exporter per domain)
```

The frontend mirrors this with `features/<domain>/api.ts` + `hooks.ts`
and shared `components/`.

---

## 2. Decomposition view

### 2.1 Backend modules

| Module | Responsibility |
|--------|----------------|
| `controller.AuthController` | Login, refresh, logout (cookie-based JWT) |
| `controller.MfaController` | TOTP enrolment, verification, recovery codes |
| `controller.SessionController` | List/revoke active persistent sessions |
| `controller.SetupController` | First-launch wizard (admin, security, integrations) |
| `controller.FamilyController` + `FamilyViewController` | CRUD members, family aggregate dashboard |
| `controller.AccountController` | Manual account CRUD + holdings |
| `controller.SyncController` | Enable Banking lifecycle (initiate, complete, retry) |
| `controller.TradeRepublicController` | TR auth + portfolio sync |
| `controller.CryptoExchangeController` | Binance setup + sync |
| `controller.WalletController` | On-chain wallet addresses |
| `controller.GoalController` | Goals, contributions |
| `controller.DashboardController` + `HistoryController` | Aggregated read endpoints |
| `controller.PriceController` | On-demand price fetch |
| `controller.MeExportController` | GDPR export with re-auth gate |
| `controller.AdminController` + `AdminMfaController` | Member admin, integrations settings |
| `service.*` | Transactional business logic (all member-scoped) |
| `config.JwtAuthenticationFilter` | Reads `access_token` cookie, validates `tv` claim |
| `config.PersistentTokenAuthFilter` | "Remember Me" silent re-login + theft detection |
| `config.SetupFilter` | Blocks `/api/*` except `/api/setup/*` until setup complete |
| `config.SecurityConfig` | Spring Security wiring (CSRF off, member endpoints authenticated) |
| `config.DynamicCorsConfigurationSource` | Runtime-editable CORS origins |
| `config.SecureCookieProvider` | Centralises `Secure` flag |
| `config.CryptoEncryption` | AES-256-GCM helpers |
| `config.RateLimitConfig` | Bucket4j buckets per endpoint group |
| `export.*` | One `EntityExporter` per domain + `DataExportService` orchestrator |

### 2.2 Frontend modules

| Module | Responsibility |
|--------|----------------|
| `features/auth/` | Login, MFA challenge, persistent session toggle |
| `features/setup/` | First-launch wizard pages |
| `features/dashboard/` | Net worth, P&L, contributions |
| `features/accounts/` | Accounts list, detail, holdings |
| `features/goals/` | Goal cards, calendar donut, contribution breakdown |
| `features/loans/` | Loan progress card, amortisation chart, monthly breakdown |
| `features/sync/` | Bank sync wizard (Enable Banking) |
| `features/family/` | Member switcher, family view |
| `features/admin/` | Members, integrations, security sections |
| `components/shared/` | Reusable UI (modals, charts, forms) |
| `frontend/src/lib/api-client.ts` | Single Axios instance (`withCredentials`) with request/response interceptors |
| `stores/` | Zustand stores for cross-cutting state |

---

## 3. Cross-cutting designs

### 3.1 Authentication design

```
LOGIN (no 2FA)              LOGIN (2FA)
─────────────────           ────────────────────────
POST /api/auth/login         POST /api/auth/login
  → access + refresh           → 401 + mfa_challenge cookie
                              POST /api/mfa/verify
                                → access + refresh + mfa_passed
```

- `JwtAuthenticationFilter` reads `access_token`, validates the `tv`
  (token-version) claim against `AppUser.tokenVersion`.
- `PersistentTokenAuthFilter` runs before `JwtAuthenticationFilter`; if
  no valid `access_token` but a valid `persistent_token` is present, it
  re-issues the access token and rotates the persistent token.
- Re-using a stale persistent token revokes the entire family (theft
  detection).
- Password change → bump `tokenVersion`, revoke persistent sessions,
  re-issue cookies for the current device.

ADRs: `2026-01-01-single-user-jwt-cookies.md` (superseded),
`2026-04-26-totp-2fa-and-persistent-sessions.md`.

### 3.2 Member-scoped authorization

```
HTTP request
   │
   ▼
JwtAuthenticationFilter sets SecurityContext with AppUser
   │
   ▼
Controller resolves UserContext.currentMemberId()
   │  (or currentMemberIdOverride() for admin ?memberId=X)
   ▼
Service calls repository.findByMemberId(memberId, ...)
   │
   ▼
For shared resources, SharingSettings + SharedResource gate the
cross-member read; never bypassed.
```

Every repository method takes `memberId` as the first argument; the
linter enforces no method without it.

### 3.3 Encryption at rest

`CryptoEncryption` (AES-256-GCM) wraps:

- `UserMfa.secret` (TOTP shared secret)
- `Requisition.sessionId` (Enable Banking session)
- `CryptoExchangeSession.apiKey` / `apiSecret`
- `TradeRepublicSession.refreshToken`
- `FinarySession.refreshToken`

The key is loaded from `CRYPTO_ENCRYPTION_KEY` env var or
`/data/.secrets/crypto_key`. The application refuses to start without
one.

ADRs: `2026-03-01-aes-gcm-crypto-secrets.md`,
`2026-04-08-mandatory-encryption-key.md`.

### 3.4 Setup wizard (first launch)

`SetupFilter` runs before Spring Security; if `setup_state.completed`
is `false`, every request except `/api/setup/*` returns 503. The
wizard collects admin credentials → security config → integrations,
each step audited via `setup_audit`. On the last step, `setup_state`
flips to `completed = true` and the filter becomes inert.

ADR: `2026-04-23-first-launch-wizard.md`.

### 3.5 Data export pipeline

```
GET /api/me/export (re-auth gate)
   │
   ▼
DataExportService (one read-only @Transactional)
   │  iterates EntityExporter beans
   ▼
ProfileExporter, AccountsExporter, HoldingsExporter,
TransactionsExporter, GoalsExporter, DebtsExporter,
WalletAddressesExporter, BankConnectionsExporter,
SharedResourcesExporter, BalanceSnapshotsExporter
   │  each writes one CSV via CsvWriter
   ▼
ZIP streamed to client
```

Feature doc: `docs/features/data-export.md`.

### 3.6 Schema ownership

Flyway is the single source of truth for the schema. `ddl-auto` is
disabled. Migrations live in `backend/src/main/resources/db/migration/`
and are versioned `V1__*` through `V29__*` in 1.0.0.

ADR: `2026-01-01-flyway-schema-ownership.md`.

---

## 4. Data design

### 4.1 Core entities

```
AppUser (auth) ──── 1:1 ──── FamilyMember (domain)
                                 │
                                 ├──< Account ──< Transaction
                                 │      │
                                 │      └──< AccountHolding ──< PriceSnapshot
                                 │
                                 ├──< Debt
                                 ├──< Goal ──< GoalContributor / GoalManualContribution
                                 ├──< WalletAddress
                                 ├──< Requisition  (Enable Banking)
                                 ├──< CryptoExchangeSession
                                 ├──< TradeRepublicSession
                                 └──< FinarySession

SharingSettings + SharedResource ── M:N across FamilyMember
UserMfa, UserMfaRecoveryCode, PersistentSession ── per AppUser
AppSetting ── global key/value store (CORS, secure cookies, EB config)
SetupState, SetupAudit ── first-launch wizard
BalanceSnapshot ── daily per-account history
```

### 4.2 Indexing strategy

- Every `member_id` column is indexed.
- `transaction(member_id, account_id, date DESC)` for fast scrolling.
- `balance_snapshot(member_id, account_id, date)` unique.
- `price_snapshot(ticker, date)` unique.

---

## 5. Deployment view

### 5.1 Bare-metal

Run PostgreSQL 16 → set env vars (or run `picsou-init.sh`) →
`mvn spring-boot:run` (backend on `:8080`) →
`bun run dev` (frontend on `:5173`, proxies `/api/*` to backend).

### 5.2 Docker Compose

`docker/docker-compose.yml` brings up:

- `picsou` — single image with backend + frontend behind nginx (`:8080`)
- `postgres` — Postgres 16 with named volume
- `tr-auth` — Trade Republic Python microservice (slim image)
- ~~`bourso-auth`~~ — commented out (disabled in 1.0.0)

Feature doc: `docs/features/docker-deployment.md`.

---

## 6. Error handling

`GlobalExceptionHandler` maps every exception to a `ProblemDetail`:

| Exception | Status |
|-----------|--------|
| `MethodArgumentNotValidException` | 422 + `errors` map |
| `BadCredentialsException` | 401 |
| `MfaException` | 401 / 403 |
| `TotpRequiredException` | 401 + `mfa_challenge` cookie |
| `ResourceNotFoundException` | 404 |
| `IllegalArgumentException` | 400 |
| `SyncException` | 502 |
| Anything else | 500 |

Stack traces are never exposed.

---

## 7. Testing approach

| Layer | Framework | Notes |
|-------|-----------|-------|
| Backend unit | JUnit 5 + Mockito | `@ExtendWith(MockitoExtension.class)` |
| Backend integration | `@DataJpaTest` + H2 | No external DB needed |
| Frontend unit | Vitest + Testing Library | `bunx vitest run` |
| E2E | Playwright | `bun run test:e2e` |

See [STP](./STP.md) for the test plan.

---

## 8. Trade-offs and open issues

- **Single Postgres instance.** No replica failover; backups are the
  operator's responsibility.
- **15-minute price cache.** Trades off freshness for provider quota.
- **Daily snapshot granularity.** Intraday history is provider-fetched
  on demand, not persisted (see `intraday-chart.md`).
- **Powens / BoursoBank disabled.** See SRS §5; re-enabling is a code
  change once tested.
