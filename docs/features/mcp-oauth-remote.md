# Feature: Remote-MCP OAuth for third-party clients (claude.ai)

> Last updated: 2026-07-12

## Context

The embedded MCP server ([mcp-server.md](./mcp-server.md)) authenticates apps with static
access-keys (`psk_…`) presented as `Authorization: Bearer`. That is the right model for a headless
key you paste into a config file — but **claude.ai's remote-MCP connector drives OAuth**: its UI
only offers OAuth (client id/secret) and cannot hold a static bearer token (a known gap). So to
connect claude.ai to a self-hosted Picsou, the MCP endpoint has to speak the remote-MCP OAuth
profile: discovery → dynamic client registration → authorize+consent → token, ending in a token the
client sends to `/mcp`.

Picsou already ran a Spring Authorization Server, but **only for the native iOS app** (one in-memory
public client `picsou-ios`, redirect `picsou://callback`, no discovery, no registration). This
feature extends that server into a standards-compliant remote-MCP authorization + resource server,
while keeping the iOS and web-cookie flows byte-for-byte unchanged.

The ADR [2026-07-06 drop oauth2:token scope](../decisions/2026-06-05-access-key-auth-and-embedded-mcp.md)
had explicitly deferred exactly this ("claude.ai should be able to obtain its own OAuth2 access
token … a distinct design problem: new registered client + new grant type + service layer"). This
feature is that design. See the ADR [2026-07-12 remote-MCP OAuth](../decisions/2026-07-12-remote-mcp-oauth-authorization.md).

## How it works

```
1. claude.ai → GET /mcp                        → 401 + WWW-Authenticate: Bearer
                                                  resource_metadata="<base>/.well-known/oauth-protected-resource"
2. GET /.well-known/oauth-protected-resource   → RFC 9728: { resource:"<base>/mcp", authorization_servers:["<base>"], scopes_supported, bearer_methods_supported:["header"] }
3. GET /.well-known/oauth-authorization-server → RFC 8414: issuer, authorize/token/jwks endpoints + registration_endpoint="<base>/oauth2/register", S256
4. POST /oauth2/register                        → RFC 7591 (open): creates a public PKCE client → { client_id }
5. GET /oauth2/authorize?client_id&redirect_uri&code_challenge&scope
     - no session cookie → redirect to SPA /login (existing password + TOTP), bounce back
     - authenticated → redirect to the SPA consent page /consent
     - user ticks scopes, Approve → auth code → redirect to https://claude.ai/api/mcp/auth_callback
6. POST /oauth2/token (code + PKCE verifier)   → { access_token: <MCP JWT>, refresh_token }
7. GET /mcp  Authorization: Bearer <MCP JWT>    → scoped MCP session; tools run under the granted scopes
```

### Token model — the load-bearing security decision

A remote-MCP client receives a **scope-limited MCP JWT**, never a full web token. Same HS256
`JWT_SECRET` as every Picsou token, distinct claim shape:

| Claim | Web/iOS token (`picsou-ios`) | **MCP token (remote clients)** |
|-------|------------------------------|--------------------------------|
| `type` | `access` | `mcp` |
| `aud` | (default) | `picsou-mcp` |
| `uid` | owner user id | owner user id |
| `scope` | — | space-delimited granted MCP scopes |
| `role` | present | **absent** |
| `tv` | present | present |

`type=mcp` / `aud=picsou-mcp` is what enforces **Property A**: `JwtTokenAuthenticator` accepts
`type=mcp` only on `/mcp` and `type=access` only on `/api`. An MCP token presented to `/api/**`
(which `JwtAuthenticationFilter` also reads as a Bearer) is rejected → an OAuth MCP credential can
never reach the web surface. On `/mcp`, `AccessKeyAuthFilter` turns a valid MCP JWT into the **same
`AccessKeyAuthentication`** (owner `AppUser`, authorities = granted scopes) an access-key produces,
so `ScopeEnforcementAspect`, `UserContext`, and the whole `@Tool` layer work unchanged, and
Properties B (no `?memberId=` override) and C (scope-only authorities, never `ROLE_ADMIN`) hold.

### Consent, registration, persistence

- **Consent** is interactive: the AS `consentPage("/consent")` redirects to a React page (SPA route
  `/consent`, served by nginx `try_files` — it is deliberately NOT under `/oauth2`, which nginx
  routes to the backend). The page reads the requested scopes via `GET /api/oauth2/consent-info` and
  submits a native form POST to `/oauth2/authorize` with one `scope` field per approved scope (deny =
  submit with none → `access_denied`). Only the logged-in user (password + TOTP) can approve, and
  the consent narrows scopes — it can never widen past `Scopes.ALL`.
- **Dynamic Client Registration** (`POST /oauth2/register`, RFC 7591) is open (unauthenticated per
  spec — harmless because issuing a token still requires login + consent). It creates public
  (`none` auth) PKCE clients with `requireAuthorizationConsent(true)` and the `picsou-mcp` client
  flag; scopes validated ⊆ `Scopes.ALL`; `redirect_uris` stored and exact-matched.
- **Persistence:** the AS uses the JDBC repositories (`oauth2_registered_client`,
  `oauth2_authorization`, `oauth2_authorization_consent`, Flyway `V54`), so registered clients and
  authorizations survive redeploys. `picsou-ios` is seeded at startup if absent.
- **Revocation:** Settings → "Connected apps" (`GET/DELETE /api/connected-apps`, cookie-authed,
  member-scoped) lists and revokes OAuth authorizations, mirroring access-key revoke.

## Key files

**Backend**
- `config/AuthorizationServerConfig.java` — JDBC repos, `picsou-ios` seed, MCP-token claim customizer, `consentPage("/consent")`, metadata customizer (`registration_endpoint`), `MCP_CLIENT_SETTING`.
- `config/JwtTokenAuthenticator.java` — `authenticateMcpToken` (path-scoped: `type=mcp`+`aud=picsou-mcp` on `/mcp` only; `/api` requires `type=access`).
- `config/AccessKeyAuthFilter.java` — non-`psk_` Bearer on `/mcp` → MCP JWT → `AccessKeyAuthentication`.
- `config/McpAuthenticationEntryPoint.java` — `WWW-Authenticate` challenge on `/mcp` 401.
- `config/ProtectedResourceMetadataController.java` — RFC 9728.
- `config/DynamicClientRegistrationController.java` + `dto/ClientRegistration{Request,Response}.java` — RFC 7591.
- `controller/OAuthConsentController.java` — `GET /api/oauth2/consent-info`.
- `controller/ConnectedAppsController.java` + `dto/ConnectedAppResponse.java` — list/revoke.
- `mcp/tools/OAuth2Tools.java` — `get_oauth2_session_status` now handles MCP-JWT sessions (no `AccessKey` row; `source="oauth2"`).
- `db/migration/V54__oauth2_authorization_server.sql` — AS JDBC schema (blob columns → `text`; see Gotchas).

**Frontend**
- `pages/oauth/ConsentPage.tsx` (route `/consent`) + `features/oauthConsent/`.
- `features/connectedApps/` + `pages/settings/sections/ConnectedAppsSection.tsx`.

**Reverse proxy**
- `frontend/nginx.conf` + `docker/nginx.conf` — `location /.well-known/oauth-` → backend.

## Gotchas / Pitfalls

- **`oauth2_authorization` blob columns are `text`, not `bytea`.** `JdbcOAuth2AuthorizationService`
  only binds a column as binary when its JDBC-reported type is `Types.BLOB`; pgjdbc reports `bytea`
  as `Types.BINARY`, so a `bytea` column breaks the String write path. The vendor
  `oauth2-authorization-schema.sql` says so in its own comment. The migration keeps `text`.
- **Consent page must live outside `/oauth2`.** nginx routes `/oauth2/*` to the backend; the SPA
  consent page is at `/consent` so `location /` serves `index.html`.
- **`McpAuthenticationEntryPoint` uses `ServletUriComponentsBuilder.fromContextPath(request)`**, not
  `fromCurrentContextPath()` — `RequestContextHolder` is not bound when `ExceptionTranslationFilter`
  calls `commence()`. It is wired via two `defaultAuthenticationEntryPointFor(...)` mappings; a plain
  `.authenticationEntryPoint(...)` on the chain would override all default mappings.
- **DCR is open by design.** Registration alone grants nothing — a token requires login + consent.

## Deployment runbook (operator — the homelab edge is NOT in this repo)

For `mcp-picsou.patato.es` (→ `192.168.1.149`) the front reverse-proxy on the box must:
1. Have the Picsou stack **up** (a `502` on `/mcp` means the backend is down — `docker compose ps` / `up -d`).
2. **Publicly expose** (currently these return `403` at the edge): `/mcp`, `/oauth2/*` (authorize,
   token, jwks, **register**), and **`/.well-known/oauth-authorization-server`** +
   **`/.well-known/oauth-protected-resource`**. Without the `.well-known` paths, claude.ai's OAuth
   discovery fails and it falls back to a bogus `/authorize` — the original failure symptom.
3. Serve over HTTPS (Secure cookies during the in-browser login).

### Manual e2e check (once up + exposed)
1. `curl -i https://<host>/mcp` → 401 + `WWW-Authenticate … resource_metadata=…`.
2. `curl https://<host>/.well-known/oauth-protected-resource` and `…/oauth-authorization-server` → JSON.
3. claude.ai → add custom connector `https://<host>/mcp` → OAuth → login (password+TOTP) → consent → connected.
4. Run a read tool → scoped data. Settings → Connected apps → revoke → access lost after token expiry.

## Tests

Backend (`mvn test`, Testcontainers Postgres where DB fidelity matters): schema migration, JDBC repo
wiring + `picsou-ios` seed, MCP-token claim shape, `authenticateMcpToken` path-scoping, `/mcp` JWT →
`AccessKeyAuthentication` + Property A denial, `/mcp` 401 challenge header, PRM/DCR/metadata
endpoints, consent-info, connected-apps list/revoke isolation, `OAuth2Tools` MCP-JWT session.
Frontend (`vitest` + clean `bun run build`): consent page scope selection + form submission,
connected-apps list/revoke.

**Not unit-testable** (needs a live public instance + the homelab edge): the full claude.ai
handshake — covered by the runbook above.

## Links

- ADR: [2026-07-12 remote-MCP OAuth authorization](../decisions/2026-07-12-remote-mcp-oauth-authorization.md)
- Related: [MCP server + access-keys](./mcp-server.md), [Budget + OAuth2 MCP tools](./mcp-budget-oauth2.md), [OAuth2 AS for iOS](../decisions/2026-07-03-oauth2-authorization-server-for-native-app.md)
- Design brief: `docs/briefs/2026-07-12-remote-mcp-oauth-design.md`
