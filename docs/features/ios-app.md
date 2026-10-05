# Feature: Native iOS app

> Last updated: 2026-10-05

## Context

A native SwiftUI iPhone client for a self-hosted Picsou instance. The founding assumption changed on
2026-07-22: this is **not a companion app** to the web frontend — some users will only ever use iOS,
never open the web app — so every feature area has to stand on its own, not lean on "do it on the
web" as an acceptable answer. The app has 5 tabs: Dashboard, Accounts, Goals, **Budget**, Settings
(Access keys, Family, Sync, Two-Factor, Appearance, Profile, Security). Sync lists the Enable
Banking connections (retry, delete) and, read-only, the connected sidecars that share one status
shape (Amex, BoursoBank, Bourse Direct, Amundi, Fortuneo, CORUM, Sofidy). Security lists the
browser Remember Me sessions and the app's own sign-ins.

**Budget** is the most complete write-capable surface in the app (redesigned 2026-07-22, see
[Links](#links) for the ADR): a hub (cycle cashflow + status strip, envelope list, nav rows) pushing
to a **categorization inbox** (1-tap AI-suggestion accept, confidence-gated bulk-accept, undo),
**inline categorization** everywhere a transaction appears (including the Accounts tab's transaction
detail sheet — one `CategoryPickerView`, pushed, reused across the app, with the transaction's
backend AI suggestion pinned on top when it has one), envelope **create/edit/
delete**, a **spending breakdown** with drill-down, **recurring/subscription triage** (confirm/
ignore/undo via swipe, a "what changed" activity feed, an upcoming-payments calendar), and minimal
budget **settings** (cycle start day, AI toggle). Deliberately NOT native (per the same "iOS-only
user" framing, still legitimately web-only): category-tree management, the categorization-rules
engine UI, bank/crypto connection wizards, CSV import, family member management, and admin/setup —
these involve third-party credentials, OAuth consent, or file handling that don't fit a phone
session well, not features the team forgot.

## How it works

### Authentication (backend becomes an OAuth2 Authorization Server)

The app authenticates with **OAuth2 Authorization Code + PKCE** against the user's own instance. The
backend gained a second, higher-priority `SecurityFilterChain` (`AuthorizationServerConfig`,
`@Order(1)`) scoped to `/oauth2/**`; the existing stateless API chain is unchanged (`@Order(2)`).

- Tokens are **HS256-signed with the same `JWT_SECRET`** as the cookie flow (a symmetric
  `OctetSequenceKey` JWKSource) and an `OAuth2TokenCustomizer` stamps the exact claims the existing
  resource server expects (`type=access`, `uid`, `tv`, `sub`, `role`). The resource-server
  validation is unchanged; `JwtAuthenticationFilter` only gained an `Authorization: Bearer`
  transport for `/api/**`. When a request sends a JWT Bearer, the filter uses it and never reads
  the `access_token` cookie, even if the Bearer is rejected; `PersistentTokenAuthFilter` likewise
  never falls back to the Remember-Me cookie. A stale or other-user cookie can't override the
  identity the client chose, and a rejected Bearer still surfaces as the 401 the app refreshes on.
  Browsers send no `Authorization` header, so the web flow is unchanged.
- The app never renders a login UI: `ASWebAuthenticationSession` opens `/oauth2/authorize`; when the
  request isn't already authenticated, `CookieBridgeAuthenticationFilter` + an entry point redirect
  the in-app browser to the SPA login (`/login?redirect=…`), which runs the untouched password +
  TOTP + Remember-Me flow and bounces back to `/oauth2/authorize`.
- The single public client `picsou-ios` (PKCE S256, `redirect_uri = picsou://callback`, consent
  skipped) and its authorizations are **JDBC-persisted** (`oauth2_registered_client` /
  `oauth2_authorization`, V54 migration — added for the remote-MCP OAuth work, see
  [mcp-oauth-remote.md](./mcp-oauth-remote.md)), so they survive a backend restart.
- **The refresh grant actually works, but only because of two targeted overrides** — Spring
  Authorization Server 1.4.5's defaults silently defeat it for a public (secret-less) client like
  `picsou-ios`, confirmed by decompiling the framework and by a live end-to-end run before the fix
  (the token response had no `refresh_token` field at all):
  1. `OAuth2RefreshTokenGenerator.generate()` unconditionally returns `null` for a
     `ClientAuthenticationMethod.NONE` client, regardless of its registered grant types or
     `TokenSettings`. `AuthorizationServerConfig.tokenGenerator()` supplies a
     `DelegatingOAuth2TokenGenerator` with a custom `NativeAppRefreshTokenGenerator` that mints one
     anyway (same 96-byte key shape, same TTL from the registered client — just without the
     public-client bypass).
  2. Even with a token minted, `PublicClientAuthenticationConverter` only recognizes a PKCE-shaped
     `authorization_code` request (it requires `code_verifier`), so a `NONE`-method client can never
     authenticate itself on a `refresh_token` grant via any built-in converter — the request falls
     through anonymous and 302s to `/login`. `PublicClientRefreshTokenAuthenticationConverter` +
     `PublicClientRefreshTokenAuthenticationProvider` (same file) authenticate by `client_id` alone
     for that one grant type — the standard RFC 6749 public-client model, where the refresh token
     itself (rotated on every use, `reuseRefreshTokens(false)`) is the actual credential.
  3. Rotation is backed by reuse detection: a `picsou-ios` refresh token names its authorization
     (`NativeAppRefreshTokens`, HMAC-signed), and presenting one that was already rotated away
     deletes the whole authorization (`RefreshTokenReuseDetector`). Its expiry counts from the
     sign-in, not from the last rotation, so a device signs in again at most every
     `refreshTokenTtlDays` (30). See the 2026-07-22 ADR.
  4. The startup runner (`seedIosClientRunner`) inserts the `picsou-ios` row on first boot and, on
     every later boot, reconciles its redirect URI and token lifetimes with `OAuthClientProperties`
     (`OAUTH_IOS_REDIRECT_URI`, `OAUTH_ACCESS_TTL_MINUTES`, `OAUTH_REFRESH_TTL_DAYS`). It keeps the
     row id and existing authorizations, writes only when a value differs, and logs one line when it
     does. New values apply on restart. An existing sign-in picks up a new refresh TTL at its next refresh,
     still counted from its sign-in time.

  Regression-tested end-to-end (real filter chain, real Postgres via Testcontainers) by
  `PublicClientRefreshTokenIntegrationTest`.

### iOS app (`ios-app/`)

SwiftUI, iOS 17+, **no third-party dependencies**. The Xcode project is generated from `project.yml`
with XcodeGen (not committed).

- `AppState` (`@Observable`) is a finite-state machine: `unconfigured → loggedOut → locked → ready`.
- `ServerConfig` stores the instance URL (validated via `/actuator/health`).
- `OAuthService` runs the PKCE flow and token/refresh grants; `TokenStore` persists the `TokenSet`
  in the Keychain (device-only); `BiometricGate` gates entry with Face ID.
- Every API and token call goes through `URLSession.cookieless` (no cookie storage, accept or
  send). The backend answers a username or password change with web `access_token` cookies; a
  stored copy would otherwise ride along on every later request. `ASWebAuthenticationSession`
  keeps its own browser cookies for the login flow.
- `APIClient` injects the Bearer token and refreshes once when the server rejects it (and
  proactively near expiry) via an actor-based single-flight (`TokenRefresher`); on terminal failure
  it asks `AppState` to sign out. "Rejected" means a 401 whose ProblemDetail `type` is
  `urn:picsou:problem:authentication-required`, which only the security entry point sends
  (`SecurityConfig.AUTHENTICATION_REQUIRED_TYPE`) for a missing, expired or revoked token. A 401
  from a credential check (wrong current password on change-password, `REAUTH_FAILED`) keeps the
  default type, so the app surfaces it as an ordinary `.http` error and the Profile / 2FA screens
  show it. Before this, a typo in the current password refreshed, retried, then signed the device
  out. `AppState.signOut()` / `resetServer()` clear the Keychain and change phase at once,
  then send a best-effort `DELETE /api/auth/sessions/{aid}` (the `aid` claim of the access token,
  refreshed first if it is about to expire; 5 s timeout, errors ignored) so the server forgets the
  device too. This refresh path only became a real, working feature with the two backend overrides
  above — before them, every device force-logged-out roughly every 15 minutes (the access-token
  TTL) with no refresh to fall back on.
- The dashboard reads a single `GET /api/dashboard?range=` and renders net worth + PnL, a Swift
  Charts area history chart and allocation donut, accounts/liabilities lists, and goal progress.
  Accounts (`Features/Accounts/`), Goals (`Features/Goals/`), Budget (`Features/Budget/`), and
  Settings (`Features/Settings/`) each have their own `Live*DataSource` following the same
  live/demo-seam pattern as the dashboard.

### Demo mode

Mirrors the web app's `VITE_DEMO_MODE`: a **build flag**, not a runtime toggle. The `Picsou Demo`
scheme (a `Demo` build configuration) defines the `DEMO` compilation condition, read once by
`AppConfig.isDemo`. In a demo build, `AppState` boots straight to `.ready` (no server, no auth, no
Face ID) and `makeDashboardDataSource()` returns `DemoDashboardDataSource` — canned mock data
(`DemoData`) with every section populated — instead of `LiveDashboardDataSource` (the API). The
`DashboardDataSource` protocol is the only seam; the UI is identical, and a small "Démo" badge marks
the mode. The demo source/mocks compile in all configs (so they're testable); only the boot decision
is gated by `#if DEMO`.

### Design system

Ported from the "Picsou Design System" Claude Design project (`Core/DesignSystem/`). `Color(oklch:)`
converts the web tokens' exact OKLCH values to sRGB and `Color(light:dark:)` resolves per appearance,
so `Theme` mirrors the web `index.css` 1:1 (colors, radii, brand `#2563eb`, emerald charts); type is
SF Pro behind `Theme.font(...)` (swappable for Geist). Screens implement the project's two mobile
templates: the **dashboard** is Variant B (blue hero net-worth card + sparkline, goal card, condensed
assets list) over a bottom `PicsouTabBar` — a floating **Liquid Glass** pill on iOS 26+, a material
bar below. The **onboarding** flow (intro → server setup → login → Face ID lock) is the dark,
aurora-lit treatment with white pill CTAs (`Features/Onboarding/`). Verified on the iOS 17/26
simulator via the Demo scheme.

### Flow

```
iOS app ──ASWebAuthenticationSession──▶ /oauth2/authorize
   ▲ picsou://callback?code=…                │ (no cookie)
   │                                         ▼
   │                             /login?redirect=/oauth2/authorize…  (existing SPA login + TOTP)
   │                                         │ sets access_token cookie, navigates back
   │  code + PKCE verifier                   ▼
   └──── POST /oauth2/token ◀──── cookie bridge authenticates ──▶ auth code
             │ access + refresh (HS256 JWT)
             ▼
        Keychain ──▶ APIClient (Bearer) ──▶ GET /api/dashboard
```

### Key files

Backend:
- `backend/src/main/java/com/picsou/config/AuthorizationServerConfig.java` — AS chain, client, JWK,
  token customizer, `tokenGenerator()` (refresh-token-for-public-clients override),
  `PublicClientRefreshTokenAuthenticationConverter`/`Provider` (public-client refresh-grant auth)
- `backend/src/main/java/com/picsou/config/CookieBridgeAuthenticationFilter.java` — authorize-request auth from the cookie
- `backend/src/main/java/com/picsou/config/JwtTokenAuthenticator.java` — shared access-token validation (cookie + Bearer + bridge)
- `backend/src/main/java/com/picsou/config/JwtAuthenticationFilter.java` — cookie + `Bearer` transport on `/api/**`
- `backend/scripts/verify-oauth-pkce.sh` — curl smoke test of the full flow

iOS (`ios-app/Picsou/`): `App/AppState.swift`, `Core/Auth/{OAuthService,TokenStore,PKCE,BiometricGate}.swift`,
`Core/Networking/APIClient.swift`, `Core/Config/ServerConfig.swift`, `Features/{Dashboard,Accounts,Goals,Budget,Settings}/*`, `Models/*`.

iOS e2e (`ios-app/PicsouTests/E2E/`): `LiveBackend.swift` (auth fixture against a real backend, reusing
`OAuthService.authorizeURL`/`.exchange`), `LiveBackendE2ETests.swift` (every `Live*DataSource` against
a real running instance).

Infra: `docker/nginx.conf` + `frontend/nginx.conf` (`location /oauth2`); `frontend/src/pages/login/*`
+ `frontend/src/lib/utils.ts` (`isOAuthAuthorizeRedirect`).

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Backend as OAuth2 AS (Spring Authorization Server) | Standard, secure native login that reuses the login/TOTP UI | Bearer-login endpoint (re-implements MFA in-app); reuse cookies via URLSession (not idiomatic) |
| HS256 symmetric JWK from `JWT_SECRET` + token customizer | Existing resource-server validates AS tokens unchanged; one signing key | RSA JWKS (would need the API to validate a second key model) |
| Reuse SPA login via cookie bridge + entry-point redirect | No new login UI, MFA stays 100% server-side | Session-based form login with a re-plumbed TOTP step |
| `tv` read from the principal snapshot at authorize time | Password change → stale `tv` → API rejects → device logs out (correct revocation) | Reload `tv` on refresh (would defeat revocation) |
| Custom `tokenGenerator()` + refresh-grant converter/provider for the public client | Spring AS's defaults never issue *or* let a secret-less client redeem a refresh token; the client already carries the OAuth 2.1-recommended mitigation (rotation) | Give `picsou-ios` a client secret (defeats "public native app, no embedded secret"); hand-roll a bearer-refresh endpoint outside the AS (re-fragments the token model) |
| XcodeGen `project.yml` | Reproducible, plain-text project; no `.pbxproj` churn | Commit the `.xcodeproj` |

## Gotchas / Pitfalls

- **nginx must proxy `/oauth2/**`.** It otherwise falls through to the SPA `index.html`. Added to both
  the all-in-one and split-compose nginx configs.
- **`SameSite=Lax` + HTTPS.** The redirect back to `/oauth2/authorize` is a top-level GET (Lax cookies
  are sent). `Secure` cookies require HTTPS in production; local dev needs `SECURE_COOKIES=false`.
- **A Java record's own `isX` field name is NOT stripped by Jackson**, unlike a classic
  `getX()`/`isX()` bean accessor. `AccountResponse.isManual` and `TransactionResponse.isManual`
  serialize as the JSON key `isManual`, not `manual` — the iOS `Account`/`Transaction` models
  (and their `PicsouTests` mocks) assumed `manual` for a long time, which meant the app could not
  decode a single real account or transaction. Only caught once `LiveBackendE2ETests` hit a real
  backend; `MockURLProtocol`-based unit tests can't catch this class of bug because they mock the
  same (wrong) assumption they're meant to be checking. `GoalProgressResponse.isOnTrack` does NOT
  have this problem — its Swift property is spelled `isOnTrack` too, so no stripping mismatch exists
  there either way.
- **Refresh tokens for a public client need the two `AuthorizationServerConfig` overrides above** —
  don't assume Spring Authorization Server "just handles" native-app refresh once
  `authorizationGrantType(REFRESH_TOKEN)` is on the registered client. It silently doesn't.
- **Money as `Decimal`** decodes from JSON numbers via `Double`; fine for 2-dp display, not exact
  arithmetic.
- **The `.xcodeproj` is generated** — run `xcodegen generate` after pulling; never edit it by hand.
- Two active ADRs previously set OAuth2 aside for their scopes (MFA, MCP) — the new ADR scopes those
  conclusions and does not reverse them.
- **The app is a session too.** `GET /api/auth/sessions` lists each live `picsou-ios`
  authorization as `kind: IOS_APP` next to the browser Remember Me rows, flagged `current` from
  the Bearer token's `aid` claim. Revoking one deletes the authorization; the resource server
  checks `aid` on every request, so the device's access token dies at once, not after its TTL.
  Settings › Sécurité lets the user revoke other devices and, after a confirmation, this one
  (which signs the app out). See [mfa-and-remember-me.md](./mfa-and-remember-me.md#ios-app-sessions)
  and the [2026-10-05 ADR](../decisions/2026-10-05-ios-app-sessions-revocable-authorizations.md).
- **The backend omits nulls** (`default-property-inclusion: non_null`): every field that can be
  null must be optional in Swift, and fixtures must omit the key rather than send `null` (a
  `null` fixture hides a non-optional property that a real omitted key would break).
- **`Instant`s carry fractional seconds** (`2026-07-04T08:00:00.123456Z`) whenever Postgres has
  them, which is always. A bare `ISO8601DateFormatter` returns nil for those, which silently
  rendered every "last synced/used" as "—". Parse with `DateParsing.instant`.
- **Backend enums grow.** Decode them through `LenientEnum` (unknown value → `.unknown`), or
  `AccountType(raw:)` (→ `.other`). `AiCategorizationMode` is a raw-value struct instead, because
  budget settings are saved read-modify-write and an unknown mode must go back unchanged.
- **Credit cards (`CREDIT_CARD`) are liabilities without a loan schedule.** The Debts view sends
  them to the account detail (amount due, due date, reward points), never to `loan-summary`. The
  recurring calendar lists a card's statement payment with `creditCardPayment: true` and
  `seriesId` = minus the card's account id: there is no series behind it.

## Tests

Backend (Mockito + AssertJ, plus Testcontainers Postgres integration tests):
- `AuthorizationServerConfigTest` — AS-minted HS256 token validates through the existing resource-server path
- `JwtTokenAuthenticatorTest`, `JwtAuthenticationFilterTest`, `CookieBridgeAuthenticationFilterTest`
- `PublicClientRefreshTokenIntegrationTest` — real filter chain, real Postgres: authorize → exchange
  (refresh_token present) → redeem (200, rotated) → rotated access token works → the rotated-away
  refresh token is rejected. Self-skips without Docker.
- `Oauth2ConsentHandshakeIntegrationTest` — the DCR/remote-MCP consent handshake (separate client, separate bug)

iOS (XCTest, run on the simulator):
- `PKCETests` (incl. the RFC 7636 S256 vector), `DashboardDecodingTests`, `APIClientTests`
  (401→refresh→retry, proactive near-expiry refresh, refresh refused → one sign-out, a
  credential-check 401 or 400 on change-password / MFA → no refresh, no sign-out, French
  message), `DemoDashboardDataSourceTests`
- `AccountsTests`, `BudgetTests`, `GoalsTests`, `FamilyTests`, `SettingsTests`, `AccessKeysTests` —
  per-feature decode/demo-source coverage, all against `MockURLProtocol` fixtures
- `ApiContractTests` — payloads shaped like the 1.1.0 backend writes them (omitted nulls,
  fractional-second instants, credit cards, card payments in the calendar, unknown enum values,
  both session kinds, sidecar statuses, the pinned AI suggestion). Two of them read
  `AccountType.java` and `Scopes.java` from the checkout and fail when the Swift copies drift;
  they skip when the sources aren't reachable (a device run).
- **`LiveBackendE2ETests` (`PicsouTests/E2E/`)** — the same `Live*DataSource`s against a REAL running
  backend instead of mocked JSON: dashboard, accounts+goals CRUD round-trip (including a manual cash
  transaction), budget, access-key create/list/revoke, family, sync, settings/MFA, and the OAuth
  refresh grant itself. This is what caught both the refresh-token bug and the `isManual`/`manual`
  bug above — `MockURLProtocol` tests structurally can't, since they assert against fixtures the same
  team wrote. Self-skips (`XCTSkip`) unless `PICSOU_E2E_PASSWORD` is set, mirroring the backend's own
  `@Testcontainers(disabledWithoutDocker = true)` convention. To run for real, against a backend
  seeded via `APP_USERNAME`/`APP_PASSWORD_HASH` (see `.env.example`):
  ```sh
  PICSOU_E2E_BASE_URL=http://localhost:8080 PICSOU_E2E_USERNAME=e2e_admin PICSOU_E2E_PASSWORD=... \
    xcodebuild test -project Picsou.xcodeproj -scheme Picsou \
    -destination 'platform=iOS Simulator,name=iPhone 17' \
    -only-testing:PicsouTests/LiveBackendE2ETests
  ```
  The scheme's `PICSOU_E2E_*` environment variables are `$(VAR)` passthroughs (see `project.yml`) —
  no secret is ever committed; unset, the tests just skip.

## Links

- ADR: [2026-07-03 OAuth2 Authorization Server for the native app](../decisions/2026-07-03-oauth2-authorization-server-for-native-app.md),
  [2026-07-22 Override Spring AS's public-client refresh-token defaults](../decisions/2026-07-22-public-client-refresh-token-overrides.md)
- Related: [2FA (TOTP) and Remember Me](./mfa-and-remember-me.md), [MCP server + scoped access-keys](./mcp-server.md),
  [Remote-MCP OAuth (JDBC-persisted client/authorization tables)](./mcp-oauth-remote.md),
  [CORS & cookie security](./security-cors-cookies.md)
