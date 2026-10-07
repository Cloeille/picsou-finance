# ADR: Remote-MCP OAuth authorization for third-party clients

> Date: 2026-07-12
> Status: ✅ Active

## Context

claude.ai's remote-MCP connector authenticates via **OAuth** (its UI cannot hold a static bearer
token — a documented gap), so connecting it to a self-hosted Picsou requires the MCP endpoint to
speak the remote-MCP OAuth profile: RFC 9728 protected-resource metadata, RFC 8414 authorization-
server metadata, RFC 7591 dynamic client registration, Authorization Code + PKCE, and an
`https://claude.ai/...` redirect. Picsou's Spring Authorization Server existed but served **only the
native iOS app** (`picsou-ios`, in-memory, `picsou://callback`, no discovery/registration).

ADR [2026-07-06](./2026-07-06-drop-oauth2-token-scope.md) explicitly deferred this exact case,
naming its requirements: a new registered client class, a new participation path, and a service
layer. This ADR builds it.

## Decision

Extend the existing Spring Authorization Server into a **remote-MCP authorization + resource
server**, issuing a **scope-limited MCP JWT** distinct from the web/iOS token, and validating it at
`/mcp` as the existing `AccessKeyAuthentication`.

1. **Distinct token class.** MCP tokens carry `type=mcp`, `aud=picsou-mcp`, `uid`, `scope`, `tv` and
   **no** `role`; web/iOS tokens keep `type=access`. Same HS256 `JWT_SECRET`.
   `JwtTokenAuthenticator` accepts `type=mcp` only on `/mcp` and `type=access` only on `/api`, so an
   MCP token cannot authenticate the web surface (**Property A**).
2. **Same principal at `/mcp`.** `AccessKeyAuthFilter` maps a valid MCP JWT to the same
   `AccessKeyAuthentication` (owner `AppUser`, scope authorities) an access-key produces — the
   `@Tool` layer, `ScopeEnforcementAspect`, and `UserContext` are untouched (Properties B, C hold).
3. **Interactive consent.** A React consent page (`/consent`) lets the logged-in user (password +
   TOTP) tick the scopes a client gets; consent can only narrow within `Scopes.ALL`.
4. **Open Dynamic Client Registration** (RFC 7591, `POST /oauth2/register`) creating public PKCE
   clients — safe because a token still requires login + consent.
5. **JDBC persistence** (Flyway `V54`) so clients/authorizations survive redeploys, plus a
   "Connected apps" Settings page to revoke.

## Alternatives considered

### A. Reuse the `picsou-ios` token for claude.ai
Rejected: that token is a full web-surface credential (`role`, `type=access`) — handing it to a
third party would grant `/api/**` (bank-auth, admin, export), destroying the curated MCP boundary.

### B. Mint a real `psk_` access-key under the hood at consent
Rejected: two credential objects (OAuth authorization + `access_key` row) per connection → ambiguous
revocation and inconsistent state. The persisted OAuth authorization already *is* the credential.

### C. Dedicated Spring resource-server filter chain on `/mcp`
Rejected (for now): a second filter chain to interleave, and it would have to reconstruct
`AccessKeyAuthentication` anyway to keep Properties B/C — more moving parts for a cosmetic gain.
Extending the existing MCP auth path reuses the trusted security apparatus.

### D. A single hardcoded claude.ai client (no DCR)
Rejected: rigid (only claude.ai's exact redirect; a new client needs a redeploy) and it is not what
claude.ai's connector attempts first. DCR is the standard remote-MCP path.

## Reasoning

The curated, scope-limited MCP boundary (ADR 2026-06-05) is the invariant to protect. Making the
OAuth token a *scoped MCP credential* that resolves to the existing `AccessKeyAuthentication` keeps
one security model, not two: the type marker (`type=mcp`/`aud=picsou-mcp`) is the structural seam
that keeps MCP tokens off `/api`, exactly as the distinct `AccessKeyAuthentication` type is the seam
for Properties B/C. Consent + login gate every issuance, so open DCR adds standards compliance
without adding risk.

## Trade-offs accepted

- **Open registration endpoint.** Anyone can register a client, but registration alone grants
  nothing (no token without login + consent). Matches RFC 7591 and the remote-MCP spec.
- **`client_id`/authorization persistence, seeded `picsou-ios`.** The iOS client is seeded
  idempotently at startup; later `OAuthClientProperties` changes do not retroactively rewrite an
  already-seeded row (documented; the iOS client rarely changes).
- **Homelab edge exposure is out of repo.** The reverse proxy on the box must publicly expose
  `/.well-known/oauth-*` and `/oauth2/*`; the repo only carries the container nginx blocks.

## Consequences

- New backend endpoints: `/.well-known/oauth-protected-resource`, `/oauth2/register`,
  `/api/oauth2/consent-info`, `/api/connected-apps`; `/mcp` 401 now carries a `WWW-Authenticate`
  challenge; `/.well-known/oauth-authorization-server` advertises `registration_endpoint`.
- New frontend: `/consent` page + "Connected apps" Settings section.
- New migration `V54__oauth2_authorization_server.sql` (Spring AS 1.4.5 JDBC schema, blob→`text`).
- `Scopes.ALL` and the MCP tool catalogue are unchanged; no new tool.
- iOS and web-cookie flows unchanged.

## Amends

- [2026-07-06 drop oauth2:token scope](./2026-07-06-drop-oauth2-token-scope.md) — the deferred
  "claude.ai obtains its own token" use case is now built (via OAuth, not an MCP tool).
- [2026-07-03 OAuth2 AS for native app](./2026-07-03-oauth2-authorization-server-for-native-app.md)
  — adds a second client class (remote-MCP) and JDBC persistence to that server.

## Links

- Feature note: [mcp-oauth-remote.md](../features/mcp-oauth-remote.md)
- Design brief: `docs/briefs/2026-07-12-remote-mcp-oauth-design.md`
