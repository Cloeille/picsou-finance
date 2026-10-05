# ADR: The iOS app's sign-ins are revocable sessions, checked on every request

> Date: 2026-10-05
> Status: ✅ Active

## Context

Settings › Sessions (`GET /api/auth/sessions`) listed only browser "Remember Me" rows
(`persistent_session`). The iOS app signs in through the OAuth2 authorization server and never
creates one, so a phone could neither see nor revoke itself, and "log out everywhere else" left
every phone signed in. Its access token is a self-contained HS256 JWT that the resource server
validated with no lookup beyond the `tv` token-version check, so removing the authorization alone
would still leave the device a working token for up to the access-token TTL.

## Decision

Each live `oauth2_authorization` row of the `picsou-ios` client is a session
(`SessionResponse.kind = IOS_APP`, opaque `id` = the row id). App access tokens carry an `aid`
claim naming their row. `JwtTokenAuthenticator` rejects a token whose `aid` row is gone or whose
access token is invalidated. Revoking deletes the row, which kills the refresh token and the
current access token at once.

## Alternatives considered

### Remove the row and let the access token expire

- **Pros**: no per-request lookup.
- **Cons**: a revoked phone keeps API access for up to 15 minutes (the access TTL), which is what a
  user revoking a lost phone least expects.

### Bump the user's `tv` on revoke

- **Pros**: reuses the existing revocation lever, no new claim.
- **Cons**: logs out every device and browser of the user, not the one they picked.

### Write a `persistent_session` row per app sign-in

- **Pros**: one list, one revoke path.
- **Cons**: duplicates state the authorization server already persists, and the two would drift on
  refresh rotation, expiry and token revocation.

## Reasoning

The authorization row already is the device: it survives refresh-token rotation and restarts, and
Spring AS keeps it up to date. Keying revocation on it gives per-device precision without a second
store. A primary-key lookup per app request is cheap at a household's scale.

## Trade-offs accepted

- One `oauth2_authorization` read per request made with an app token (browser cookies and MCP
  tokens are unaffected).
- Access tokens minted before the `aid` claim existed stay valid until their TTL.
- Remote-MCP (DCR) authorizations are not listed; they have their own consent and revocation story.

## Consequences

- `SessionResponse.id` is a string; `DELETE /api/auth/sessions/{id}` dispatches numeric ids to
  Remember Me rows and anything else to app authorizations.
- `NativeAppSessionService` owns listing, revoking and the per-request check.
- See [mfa-and-remember-me.md](../features/mfa-and-remember-me.md#ios-app-sessions) and
  [ios-app.md](../features/ios-app.md).
