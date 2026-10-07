# ADR: OAuth2 Authorization Server for the native iOS app

> Date: 2026-07-03
> Status: ✅ Active

## Context

We are building a **native SwiftUI iOS app** (Phase 1: authentication + read-only dashboard,
targeting eventual parity with the web app). The app needs to authenticate against a user's
**self-hosted** Picsou instance and then call the existing `/api/**` surface as that user — the
same full surface the web client sees (this is the user's own first-party client, not a scope-
limited headless agent).

Today the only user principal is a **JWT in an HttpOnly cookie**, minted by `AuthController` after
password (+ optional TOTP) and validated by `JwtAuthenticationFilter` (HS256, `JWT_SECRET`). That
model is a poor fit for a native app: cookies are browser-session credentials, the sensitive login
+ MFA flow would have to be rebuilt natively, and stuffing the raw JWT into the app would mean the
app handling passwords directly.

Two earlier ADRs weighed OAuth2 and set it aside **for their scopes**; this ADR revisits it for a
genuinely different one and must address them head-on:

- `2026-01-01-single-user-jwt-cookies.md` (superseded) rejected "OAuth2/OIDC" because it "requires
  an external IdP … adds complexity for a single-user app."
- `2026-06-05-access-key-auth-and-embedded-mcp.md` (active) rejected an OAuth2 authorization-server
  build for **MCP** auth as "heavy machinery for a self-hosted personal app [that] still wouldn't
  give us the curated MCP boundary for free."

## Decision

**Turn the backend itself into an OAuth2 Authorization Server** (Spring Authorization Server) and
have the iOS app authenticate with **Authorization Code + PKCE**, storing the resulting tokens in
the **Keychain**.

- A dedicated `@Order(1)` `SecurityFilterChain` (`AuthorizationServerConfig`) serves `/oauth2/**`.
  The existing stateless API chain (`SecurityConfig`) becomes `@Order(2)` and is otherwise
  **unchanged** — the web cookie flow keeps working exactly as before.
- A single **public client** `picsou-ios` (no secret, PKCE S256 required,
  `redirect_uri = picsou://callback`, consent skipped) is registered in memory.
- Tokens are **HS256-signed with the same `JWT_SECRET`** via a symmetric `OctetSequenceKey`
  JWKSource, and an `OAuth2TokenCustomizer` stamps the exact claims the resource server already
  requires (`type=access`, `uid`, `tv`, `sub`, `role`). The existing validation logic is untouched;
  `JwtAuthenticationFilter` only gains an `Authorization: Bearer` transport alongside the cookie.
- **The existing web login is reused inside the OAuth flow.** A `CookieBridgeAuthenticationFilter`
  authenticates the authorize request from the existing `access_token` cookie; when it is absent, an
  `AuthenticationEntryPoint` redirects the in-app browser (`ASWebAuthenticationSession`) to the SPA
  login (`/login?redirect=…`, reusing the SPA's established redirect param), which runs the untouched
  password + TOTP + Remember-Me flow and bounces back. **No new login UI, no session-based MFA rebuild.**

## Alternatives considered

### Reuse cookies natively via `URLSession` (no backend change)

- **Pros**: zero backend work; `URLSession`'s cookie storage transparently persists and sends the
  existing `HttpOnly` cookies.
- **Cons**: cookies are session credentials, not a native-app auth model — biometric gating is
  awkward, we inherit cookie-expiry subtleties, and it couples the app to a browser-shaped flow. Not
  "the best" native experience the app is aiming for.

### Add a bearer-token login endpoint (JSON tokens in the body, no authorization server)

- **Pros**: lighter than a full authorization server; tokens in the Keychain; simple.
- **Cons**: the app would render its own password + TOTP UI and handle the raw credentials, and
  we'd hand-roll refresh/rotation — re-implementing security-sensitive flows that already exist and
  are well-tested server-side. A bearer-header "hack" rather than a standard.

### External IdP / "Sign in with Apple" (OIDC to a third party)

- **Pros**: offloads credential handling; familiar consumer UX.
- **Cons**: wrong identity model — Picsou accounts are self-hosted, username/password + TOTP, with
  family members; delegating identity to Apple/Google doesn't fit and adds a hard external
  dependency to a self-hosted app. This is the "external IdP" the 2026-01-01 ADR rightly rejected.

## Reasoning

The 2026-01-01 objection was to an **external IdP**. We are not adding one: the backend is its
**own** authorization server, reusing the existing user store, `BCryptPasswordEncoder`, and
`MfaService`. No third party enters the picture.

The 2026-06-05 objection was scoped to **MCP** auth, where the goal is a *curated, individually
revocable, scope-limited* boundary for a headless AI agent — for which scoped `psk_` access-keys are
genuinely a better fit than OAuth. That reasoning does **not** transfer to a first-party native app
that is meant to see the user's full authenticated surface (parity with the web). Here the
"heavy machinery" objection is answered two ways: (1) the need is now concrete and user-facing (a
real native app), so the cost buys something; and (2) with Spring Authorization Server the cost is a
**bounded configuration addition** (one config class, in-memory client, symmetric JWK, a claims
customizer) rather than a bespoke protocol implementation.

Authorization Code + PKCE is the industry standard for native clients precisely because it keeps the
password/MFA exchange in a system browser the app cannot read, and never embeds a client secret.
Signing with the **same HS256 secret** and reproducing the **same claims** means the two token
issuers converge on one validation path — no parallel identity system, no second signing key to
manage, and the `tv` token-version revocation still works across both web and app.

## Trade-offs accepted

- **In-memory `RegisteredClient` + `OAuth2AuthorizationService` (no persistence yet).** Issued
  authorizations/refresh sessions drop on backend restart, so a device must re-auth (silently, via
  Face ID → OAuth) after a redeploy. Acceptable for a personal app; the upgrade path is a JDBC-backed
  `OAuth2AuthorizationService` (framework tables in a future `V50` migration).
- **Refresh reads `tv` from the principal snapshot captured at authorization time**, not a fresh DB
  load. This is deliberate: a later password change bumps `AppUser.tokenVersion`, so refreshed access
  tokens carry the stale `tv` and the API rejects them — logging the device out, which is the desired
  revocation semantics.
- **A second SecurityFilterChain to reason about.** Mitigated by scoping it to
  `authorizationServer.getEndpointsMatcher()` and leaving the API chain byte-for-byte equivalent.
- **`/oauth2/**` must be reverse-proxied to the backend** (new nginx `location`) and, in production,
  served over HTTPS (required for `Secure` cookies during the in-browser login).

## Consequences

- **Dependency**: `spring-boot-starter-oauth2-authorization-server` (BOM-managed ~1.4.x, compatible
  with the pinned `spring-security.version=6.4.10`).
- **New code** (all under `config/`): `AuthorizationServerConfig` (`@Order(1)` chain, client, JWK,
  token customizer), `CookieBridgeAuthenticationFilter`, `JwtTokenAuthenticator` (extracted shared
  validation so cookie / bearer / cookie-bridge cannot diverge), `OAuthClientProperties`
  (`app.oauth.*`).
- **Edited**: `JwtAuthenticationFilter` (adds a non-`psk_` `Authorization: Bearer` transport for
  `/api/**`, delegates validation to `JwtTokenAuthenticator`); `SecurityConfig` (`@Order(2)`, injects
  the shared authenticator); `application.yml` (`app.oauth.*`).
- **No schema change** (in-memory client/authorization service). No web-flow behavior change.
- **Infra**: `location /oauth2/` added to `docker/nginx.conf` and `frontend/nginx.conf`.
- **Frontend**: the SPA login/MFA pages full-navigate to a `/oauth2/` `redirect` target (rather than
  a client-side route change), open-redirect guarded, so the authorize flow can resume after login.
- Full design in [`docs/features/ios-app.md`](../features/ios-app.md).

## Supersedes

None. Amends the OAuth-related reasoning in
[`2026-06-05-access-key-auth-and-embedded-mcp.md`](2026-06-05-access-key-auth-and-embedded-mcp.md)
(which remains active for MCP auth) by scoping its "no OAuth2" conclusion to the MCP/headless-agent
case, not first-party native clients.
