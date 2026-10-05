# ADR: Override Spring Authorization Server's public-client refresh-token defaults

> Date: 2026-07-22
> Status: ✅ Active

## Context

The iOS app's OAuth2 client (`picsou-ios`, decided in
[2026-07-03-oauth2-authorization-server-for-native-app.md](./2026-07-03-oauth2-authorization-server-for-native-app.md))
is a **public client**: no client secret, PKCE-only, `ClientAuthenticationMethod.NONE` — the correct
model for a native app that cannot hold a confidential secret. That ADR's own trade-offs section
already anticipated refresh tokens working ("refreshed access tokens carry the stale `tv`…").

Live end-to-end testing (`ios-app/PicsouTests/E2E/LiveBackendE2ETests.swift`, run against a real
backend instead of `MockURLProtocol`) found that the refresh flow never actually worked: the token
response from the authorization_code exchange had **no `refresh_token` field at all**. Decompiling
`spring-security-oauth2-authorization-server` 1.4.5 pinned two independent, silent framework defaults
as the cause:

1. `OAuth2RefreshTokenGenerator.generate()` calls a private `isPublicClientForAuthorizationCodeGrant`
   check and returns `null` — no log, no error — whenever the requesting client's
   `ClientAuthenticationMethod` is `NONE`. This holds regardless of the client's own
   `authorization_grant_types` (which correctly listed `refresh_token`) or its
   `TokenSettings.refreshTokenTimeToLive`.
2. Even with a refresh token minted some other way, `PublicClientAuthenticationConverter` — the only
   built-in converter that can authenticate a `NONE`-method client at all — only activates for a
   PKCE-shaped request (`OAuth2EndpointUtils.matchesPkceTokenRequest`, which requires a
   `code_verifier` parameter). A `grant_type=refresh_token` request never has one (PKCE is an
   authorization_code-grant mechanism), so a public client's redemption request falls through
   unauthenticated and the AS's `exceptionHandling` entry point 302s it to `/login`.

Net effect in production: every device was force-logged-out on the access-token TTL (15 minutes)
with the app's own `TokenRefresher` (built assuming refresh would work — see the 2026-07-03 ADR)
never getting a token to use.

## Decision

Add two small, targeted overrides in `AuthorizationServerConfig`, both scoped to exactly the gap
above and nothing else:

1. **`tokenGenerator()`** — a `@Bean` supplying a `DelegatingOAuth2TokenGenerator` built from the
   framework's own `JwtGenerator` (with the existing `jwtTokenCustomizer()`) and
   `OAuth2AccessTokenGenerator`, plus a private `NativeAppRefreshTokenGenerator` that reimplements
   `OAuth2RefreshTokenGenerator`'s real generation logic (96-byte URL-safe base64 key via
   `Base64StringKeyGenerator`, TTL from `RegisteredClient.getTokenSettings()`) **minus** the
   public-client bypass.
2. **`PublicClientRefreshTokenAuthenticationConverter` + `...Provider`** — registered via
   `.clientAuthentication(c -> c.authenticationConverter(...).authenticationProvider(...))`, which
   `OAuth2ClientAuthenticationConfigurer.init()` **prepends** ahead of the framework's own
   converters/providers (`authenticationProviders.addAll(0, custom)`, confirmed by decompiling that
   class too). The converter recognizes a `grant_type=refresh_token` POST carrying a bare `client_id`
   (and no `Authorization` header, so it never shadows a future confidential client); the provider
   authenticates by `client_id` alone after checking the registered client exists, allows `NONE`
   auth, and has the `refresh_token` grant type — the same checks
   `PublicClientAuthenticationProvider` makes, minus the PKCE verification that only applies to an
   authorization_code exchange.

Both defer (return `null`) for any request shape they don't recognize, so the authorization_code+PKCE
path — and any future confidential client — is untouched.

3. **Reuse detection and a fixed sign-in lifetime for `picsou-ios`** (amended 2026-10-05). Rotation
   alone does not contain a stolen refresh token: if the attacker redeems it first, the device's copy
   is the one that fails, and the attacker's chain keeps rotating. Each rotation also used to set
   `expiresAt = now + TTL`, so that chain had no end. Per OAuth 2.1 and RFC 9700 §4.14.2:
   - `NativeAppRefreshTokens` makes `picsou-ios` refresh tokens `<authorizationId>.<random>.<mac>`,
     the MAC an HMAC-SHA256 (keyed by `JWT_SECRET`, domain-separated from the JWT signatures) over the
     first two parts. The framework still stores and looks tokens up by full value.
   - `RefreshTokenReuseDetector`, inserted at index 0 of the token endpoint's providers, handles a
     refresh request whose token has a valid MAC but is not the authorization's current refresh
     token. That token was issued and rotated away, so it deletes the whole authorization (current
     refresh token, current access token through the `aid` check) and answers `invalid_grant`.
     Anything else (current token, unknown format, authorization already gone) defers to the
     framework.
   - `NativeAppRefreshTokenGenerator` sets a `picsou-ios` refresh token's expiry to the
     authorization code's issue time plus the refresh TTL (`app.oauth.refresh-token-ttl-days`, 30
     days). Rotation renews the token, never the sign-in: a device signs in again at most every 30
     days.

   Remote-MCP clients keep the opaque, sliding refresh token: their retry behaviour is not ours to
   control, and a false reuse there would disconnect a connector.

## Alternatives considered

### Give `picsou-ios` a client secret

- **Pros**: `ClientAuthenticationMethod.CLIENT_SECRET_BASIC`/`_POST` clients get the framework's
  refresh-token generation and authentication for free — no overrides needed.
- **Cons**: defeats the entire point of the original ADR's client model. A secret embedded in a
  distributed native binary isn't confidential — this would be security theater, not a real
  authentication factor, and would misrepresent the client's actual trust level to anyone reading
  the config later.

### Hand-roll a bearer-refresh endpoint outside the Authorization Server

- **Pros**: full control; no need to reverse-engineer Spring AS internals.
- **Cons**: re-fragments the token model the 2026-07-03 ADR deliberately unified (one AS, one token
  issuer, one validation path). Would need its own persistence, rotation, and revocation logic —
  reimplementing exactly what `JdbcOAuth2AuthorizationService` already does correctly for the
  authorization_code leg.

### Accept 15-minute sessions; rely on silent re-authorization instead of refresh

- **Pros**: zero new code.
- **Cons**: not actually silent — `ASWebAuthenticationSession` doesn't run headlessly, so a session
  drop was a real (if brief) UI interruption, not the "device re-authenticates silently" experience
  the original ADR promised for a backend *restart*; happening every 15 minutes during normal use is
  a different order of problem, not a corner case worth shipping.

## Reasoning

Spring Authorization Server's defaults here are a defensible security posture for **browser-based**
public clients (a JS-readable refresh token is a bigger prize than a short-lived access token) but
overly broad for a native app whose refresh token lives in the Keychain, never touches JS-accessible
storage, and is already rotated on every use. RFC 6749's public-client model treats the client's own
`client_id` as non-secret by design — the *token itself* (high-entropy, single-use via
`reuseRefreshTokens(false)`) is the real credential, which is exactly what the custom provider checks
for. This isn't a security downgrade relative to what the 2026-07-03 ADR already committed to; it's
making that commitment actually functional.

## Trade-offs accepted

- **Two more classes to reason about in the AS filter chain**, both undocumented-by-the-framework
  workarounds tied to a specific Spring Authorization Server version's internal behavior (verified
  against 1.4.5 by decompiling — a version bump should re-verify both `OAuth2RefreshTokenGenerator`
  and `PublicClientAuthenticationConverter` haven't changed shape).
- **Provider order is load-bearing.** The fix relies on `OAuth2ClientAuthenticationConfigurer`
  prepending custom providers ahead of the framework defaults, so the custom refresh-grant provider
  is tried (and returns a real result) before `PublicClientAuthenticationProvider` gets a chance to
  throw `invalid_grant` on the missing PKCE verifier. This is framework behavior, not something this
  codebase controls — a future Spring AS upgrade that changes provider ordering would need this
  re-verified (the regression test would fail loudly, not silently).
- **A lost refresh response signs the device out.** If the server rotates the token but the
  response never reaches the app, the app retries with the old token, which now reads as reuse and
  revokes the sign-in. No grace window, unlike the web Remember-Me cookie: the app's
  `TokenRefresher` is single-flight, so concurrent refreshes never produce this, and the cost of a
  false positive is one new sign-in.
- **Hashes of rotated-away tokens were not kept.** Storing them on the authorization's attributes
  grows the row with every rotation (an attacker can rotate on purpose) and needs a scan to map an
  unknown token back to its row. The MAC'd format needs no storage and no migration.

## Consequences

- **Edited**: `backend/src/main/java/com/picsou/config/AuthorizationServerConfig.java` — added
  `tokenGenerator()`, `NativeAppRefreshTokenGenerator`, `PublicClientRefreshTokenAuthenticationConverter`,
  `PublicClientRefreshTokenAuthenticationProvider`; `authorizationServerSecurityFilterChain` now takes
  a `RegisteredClientRepository` param and wires `.clientAuthentication(...)`.
- **New test**: `backend/src/test/java/com/picsou/config/PublicClientRefreshTokenIntegrationTest.java`
  — real filter chain, real Postgres (Testcontainers): authorize → exchange (refresh_token present) →
  redeem (200, rotated tokens) → rotated access token works against `/api/dashboard` → the
  rotated-away refresh token is rejected (`invalid_grant`) → that reuse also kills the current
  refresh token and access token. Self-skips without Docker.
- **New (2026-10-05)**: `NativeAppRefreshTokens.java`, `RefreshTokenReuseDetector.java`, with unit
  tests `RefreshTokenReuseDetectorTest` and `NativeAppRefreshTokenGeneratorTest`.
- **No schema change**, no client-facing API shape change beyond the token response now correctly
  including `refresh_token`.
- Full context and the two-bug narrative: [`docs/features/ios-app.md`](../features/ios-app.md)
  (Authentication section).

## Supersedes

None. Amends [2026-07-03-oauth2-authorization-server-for-native-app.md](./2026-07-03-oauth2-authorization-server-for-native-app.md)
— that ADR's public-client + PKCE decision is unchanged; this is a correction to its implementation
(the refresh grant it assumed would work).
