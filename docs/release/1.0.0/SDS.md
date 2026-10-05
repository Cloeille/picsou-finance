# Software Detailed Specification (SDS)

> Project: **Picsou** — version **1.0.0**
> Date: 2026-04-26

The SDS expands the [SDD](./SDD.md) with component-level interfaces:
classes, methods, REST contracts, and key invariants. It is the
reference for contributors implementing or modifying a component.

---

## 1. REST API contract

### 1.1 Conventions

- Base URL: `/api`
- Auth: HttpOnly cookies (`access_token`, `refresh_token`,
  `mfa_challenge`, `persistent_token`)
- Errors: RFC 7807 `ProblemDetail`
- Validation: 422 with `errors` map
- See [`docs/conventions/api-rest.md`](../../conventions/api-rest.md)

### 1.2 Endpoints (summary)

#### Auth & sessions

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/auth/login` | Username + password → access/refresh, or `mfa_challenge` if 2FA |
| POST | `/api/auth/refresh` | Rotate refresh token, re-issue access |
| POST | `/api/auth/logout` | Clear all auth cookies |
| GET  | `/api/me` | Current user + member + role |
| POST | `/api/me/password` | Change password (bumps `tokenVersion`) |
| POST | `/api/me/username` | Rename, re-issues tokens |
| GET  | `/api/me/export` | GDPR ZIP export (re-auth required) |
| GET  | `/api/sessions` | List active persistent sessions |
| DELETE | `/api/sessions/{id}` | Revoke a session |

#### MFA

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/mfa/enroll` | Generate TOTP secret + QR |
| POST | `/api/mfa/activate` | Activate after first valid code |
| POST | `/api/mfa/verify` | Consume `mfa_challenge`, issue access/refresh |
| POST | `/api/mfa/recovery` | Use a recovery code |
| POST | `/api/mfa/disable` | Disable MFA (re-auth) |
| POST | `/api/mfa/reauth` | Step-up reauth challenge |

#### Setup wizard

| Method | Path | Purpose |
|--------|------|---------|
| GET  | `/api/setup/status` | Setup completion state |
| POST | `/api/setup/admin` | Create the first admin |
| POST | `/api/setup/security` | Set CORS, secure cookies, encryption key location |
| POST | `/api/setup/integrations/*` | Per-integration sub-flows |
| POST | `/api/setup/complete` | Finalise; `SetupFilter` becomes inert |

#### Family

| Method | Path | Purpose |
|--------|------|---------|
| GET    | `/api/family/members` | List members |
| POST   | `/api/family/members` | Invite member |
| PATCH  | `/api/family/members/{id}` | Activate, deactivate, rename |
| DELETE | `/api/family/members/{id}` | Soft-delete |
| GET    | `/api/family/dashboard` | Aggregate net worth across visible members |
| GET/PUT | `/api/family/sharing` | View / update sharing rules |

#### Accounts, holdings, transactions

| Method | Path | Purpose |
|--------|------|---------|
| GET/POST/PATCH/DELETE | `/api/accounts[/{id}]` | Manual account CRUD |
| GET/POST/PATCH/DELETE | `/api/accounts/{id}/holdings[/{hid}]` | Holdings CRUD |
| GET/POST/DELETE | `/api/transactions[/{id}]` | Manual transactions |
| GET | `/api/dashboard` | Aggregated dashboard |
| GET | `/api/dashboard/history` | Net-worth time series |
| GET | `/api/holdings/prices` | Live price refresh |

#### Bank sync (Enable Banking)

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/sync/initiate` | Build OAuth `authLink` |
| GET  | `/api/sync/complete` | Handle callback, link accounts |
| POST | `/api/sync/{id}/retry` | Retry a failed requisition |
| POST | `/api/sync/all` | Re-sync everything for a member |

#### Brokerage / crypto / wallets

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/tr/auth/initiate` + `/verify` | TR phone+PIN+SMS OTP |
| POST | `/api/tr/sync` | Pull TR portfolio |
| POST | `/api/crypto-exchanges/binance` | Save encrypted API keys |
| POST | `/api/crypto-exchanges/binance/sync` | Refresh balances |
| GET/POST/DELETE | `/api/wallets` | On-chain addresses |

#### Goals & loans

| Method | Path | Purpose |
|--------|------|---------|
| GET/POST/PATCH/DELETE | `/api/goals[/{id}]` | Goals CRUD |
| POST | `/api/goals/{id}/contributions` | Manual contribution |
| GET  | `/api/goals/{id}/breakdown` | Monthly breakdown |
| GET/POST/PATCH/DELETE | `/api/debts[/{id}]` | Debts/loans CRUD |
| GET  | `/api/debts/{id}/amortization` | On-the-fly schedule |

#### Admin

| Method | Path | Purpose |
|--------|------|---------|
| GET/PUT | `/api/admin/settings` | Integrations + security |
| POST    | `/api/admin/members/{id}/reset-password` | Force-reset |
| POST    | `/api/admin/members/{id}/reset-mfa` | Disable a member's 2FA |

---

## 2. Key services (backend)

### 2.1 `AccountService`

```java
List<Account> listForMember(Long memberId);
Account create(Long memberId, AccountRequest req);
Account update(Long memberId, Long accountId, AccountRequest req);
void delete(Long memberId, Long accountId);
void upsertSnapshot(Account a, BigDecimal balance);
```

Invariants:
- Every read scopes by `memberId` or via `SharedResource`.
- Manual accounts cannot be touched by a sync run.

### 2.2 `SyncService`

```java
SyncInitiateResponse initiateConnection(Long memberId, String institutionId);
void completeConnection(Long memberId, String code, String state);
void retrySync(Long memberId, Long requisitionId);
void resyncAll(Long memberId);
AccountType detectType(String product, String cashAccountType);
```

Invariants:
- The `BankConnectorPort` is injected (not constructed); tests provide
  a mock.
- Manual transactions are never overwritten (`is_manual = true`).

### 2.3 `JwtUtil`

```java
String issueAccessToken(AppUser u, long memberId);
String issueRefreshToken(AppUser u);
String issueMfaChallenge(AppUser u);
Claims parse(String token); // throws JwtException
```

Invariants:
- All tokens carry `tv` (token-version). Mismatch with
  `AppUser.tokenVersion` → 401.

### 2.4 `CryptoEncryption`

```java
String encrypt(String plaintext);   // base64( IV ‖ ciphertext ‖ tag )
String decrypt(String ciphertext);
```

Invariants:
- Random 12-byte IV per call.
- 128-bit auth tag.
- The key is loaded once at startup from
  `CRYPTO_ENCRYPTION_KEY` or `APP_CRYPTO_KEY_PATH`.

### 2.5 `LoanAmortizationService`

```java
AmortizationSchedule compute(Debt d);
LoanProgress progress(Debt d, LocalDate asOf);
```

Invariants:
- Pure function; no rows persisted.
- Handles APR, fees, and supporting columns added by V27.

### 2.6 `DataExportService`

```java
@Transactional(readOnly = true)
StreamingResponseBody export(Long memberId, ExportRequest req);
```

Invariants:
- One transaction across all exporters → consistent snapshot.
- Re-auth required (controller-level).

---

## 3. Filter chain (Spring Security)

```
Order  Filter                              Role
-----  ----------------------------------  ----------------------------------------
  1    SetupFilter                         503 unless setup complete or /api/setup/*
  2    DynamicCorsFilter (LoggingCors…)    Origin match against AppSetting CORS list
  3    PersistentTokenAuthFilter           Re-issue access from rotating cookie
  4    JwtAuthenticationFilter             Validate access_token + tv claim
  5    Spring Security default chain       Authorize /api/* requests
```

---

## 4. Frontend module contracts

### 4.1 `frontend/src/lib/api-client.ts`

```ts
export const api: AxiosInstance   // baseURL '/api', withCredentials: true
export function isSetupRequiredResponse(status: number | undefined, data: unknown): boolean
```

- Single Axios instance; feature `api.ts` modules call `api.get/post/...`.
- Request interceptor injects `memberId` when an admin impersonates a managed profile.
- Response interceptor tracks connectivity, then routes by status:
  - `403` on a GET → redirects to `/error/403`.
  - `503` with `setup_required` → redirects to `/setup`.
  - `5xx` on a GET → redirects to `/error/500?code=<status>` (skippable via `skipGlobalErrorRedirect`).
  - `401` (non-`/auth/*`) → silently `POST /auth/refresh` and retry, queueing concurrent requests; on failure logs out and redirects to `/login`.
- Rejections propagate the original Axios error (carrying the `ProblemDetail` body).

### 4.2 Stores (Zustand)

| Store | Purpose |
|-------|---------|
| `auth-store` | Current user, member, role |
| `family-store` | Visible members, current member override |
| `theme-store` | Light/dark, persisted |
| `setup-flow-store` | Wizard step state |
| `setup-credentials` | In-memory wizard form payload |
| `demo-store` | Demo-mode toggle |

### 4.3 Routing (React Router)

```
/login, /mfa-challenge, /setup/*
/                          dashboard
/accounts, /accounts/:id
/transactions
/holdings
/goals, /goals/:id
/debts, /debts/:id
/sync, /sync/callback
/family
/admin/*
/settings/*
```

All routes except `/login`, `/mfa-challenge`, `/setup/*`, `/demo`
require an authenticated session.

---

## 5. Database schema (V1 → V29)

| Migration | Adds |
|-----------|------|
| V1–V10 | Core entities (AppUser, Account, Transaction, Holding, Snapshot, Goal, Debt) |
| V11–V13 | Enable Banking requisitions, encryption-related columns |
| V14–V18 | Trade Republic, Finary, crypto exchange sessions |
| V19 | Wallet addresses |
| V20 | OpenFIGI ticker cache |
| V21 | Token-version claim |
| V22 | Persistent session table |
| V23 | BoursoBank session *(disabled in 1.0.0)* |
| V24 | UserMfa + UserMfaRecoveryCode |
| V25 | SetupState |
| V26 | SetupAudit |
| V27 | Loan extra fields (APR, fees) |
| V28 | FamilyMember + member_id back-fill on every domain table |
| V29 | SharingSettings + SharedResource |

Conventions: `docs/conventions/database.md`.

---

## 6. Configuration keys

All under `app.*` (Spring Boot relaxed binding from env vars).

| Property | Env var | Default | Notes |
|----------|---------|---------|-------|
| `app.jwt.secret` | `JWT_SECRET` | random | Mandatory |
| `app.crypto.key` | `CRYPTO_ENCRYPTION_KEY` | (file) | Mandatory; OR file at `APP_CRYPTO_KEY_PATH` |
| `app.username` | `APP_USERNAME` | (wizard) | Skip wizard for CI |
| `app.password-hash` | `APP_PASSWORD_HASH` | (wizard) | bcrypt cost 12 |
| `app.allowed-origins` | `ALLOWED_ORIGINS` | (wizard) | Editable at runtime via admin |
| `app.secure-cookies` | `SECURE_COOKIES` | `true` | Set `false` only for local HTTP |
| `app.mfa.issuer` | `APP_MFA_ISSUER` | `Picsou` | Authenticator label |
| `app.jwt.mfa-challenge-expiry-minutes` | … | `5` | TTL of `mfa_challenge` |
| `app.persistent-session.expiry-days` | … | `90` | "Remember Me" TTL |
| `app.enablebanking.*` | `ENABLEBANKING_*` | (wizard) | OAuth credentials |
| `app.tr.*` | `TR_*` | — | TR sidecar URL |

Disabled defaults: Powens (`POWENS_*`) and BoursoBank (`BOURSO_*`).

---

## 7. Invariants enforced repo-wide

1. Every repository finder takes `memberId` as the first argument.
2. No controller imports an adapter; controllers depend on services.
3. No controller has a try/catch — `GlobalExceptionHandler` handles
   everything.
4. No `@Autowired` field injection — constructor injection only
   (Lombok `@RequiredArgsConstructor`).
5. `ddl-auto` is `validate` only; Flyway owns the schema.
6. All `docs/` content is English.
7. The frontend is mobile-responsive.
