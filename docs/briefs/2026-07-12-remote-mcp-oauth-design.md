# Design: Remote-MCP OAuth for third-party clients (claude.ai)

> Date: 2026-07-12
> Status: 🎨 Design (approved — implementation pending)
> Branch: `1.1.0`

## Context

A user tried to connect **claude.ai's remote MCP connector** to their self-hosted Picsou at
`https://mcp-picsou.patato.es/mcp` (→ `192.168.1.149`) and the OAuth login failed. Diagnosis found
two layers:

1. **Infra (out of scope here):** the Picsou upstream is currently down — every path routed to it
   (`/mcp`, `/oauth2/*`, `/api/*`) returns `502`. The homelab edge proxy also denies `/`,
   `/.well-known/*`, and `/authorize` with a bare `403`. This must be fixed operationally on the box;
   it is a prerequisite for testing but not a code change in this repo (except the reverse-proxy
   `location` blocks — see Proxy).

2. **Structural (this design):** Picsou's OAuth2 Authorization Server was built **only for the
   native iOS app** (`AuthorizationServerConfig`): one in-memory public client `picsou-ios`, redirect
   `picsou://callback`, endpoints under `/oauth2/**`, no discovery exposure, no dynamic client
   registration. claude.ai's remote-MCP flow needs RFC 9728 protected-resource metadata, RFC 8414
   authorization-server metadata, RFC 7591 dynamic client registration, an `https://claude.ai/...`
   redirect, and a `WWW-Authenticate` challenge on the `/mcp` `401`. None of that exists. The
   `client_id=psk_...` seen in the failing URL is a Picsou **access-key** mistakenly used as an OAuth
   client id; the `/authorize` (not `/oauth2/authorize`) path is claude.ai's fallback after discovery
   failed.

ADR [`2026-07-06-drop-oauth2-token-scope`](../decisions/2026-07-06-drop-oauth2-token-scope.md)
explicitly anticipated this: *"If a future use case needs it (e.g. claude.ai should be able to obtain
its own OAuth2 access token), that's a distinct design problem … new registered client + new grant
type + service layer."* This design is exactly that case.

## Goal / success criteria

- A user can add Picsou as a **custom remote MCP connector in claude.ai using OAuth**, complete the
  login (their existing password + TOTP), pick which MCP scopes claude.ai gets on a **consent
  screen**, and thereafter claude.ai calls `/mcp` successfully with a Picsou-issued token.
- The connection **survives backend redeploys** (persistent clients + authorizations).
- The user can **revoke** claude.ai's access from Settings.
- The **entire existing security model is preserved**: an OAuth-issued MCP token is a *scope-limited
  MCP credential*, never a full `/api/**` web token. Security Properties A/B/C (see below) hold
  structurally.
- The **iOS app flow (`picsou-ios`) is unchanged**, and the **web cookie flow is unchanged**.

## Non-goals

- No change to the curated MCP tool catalogue or scope vocabulary (`Scopes.ALL`).
- No support for confidential OAuth clients (client secrets). Remote MCP clients are **public** +
  PKCE, like the iOS client.
- No admin/credential/bank-auth/MFA/export surface exposed to OAuth tokens (same as access-keys).
- Fixing the homelab edge proxy config (operational; documented but not in this repo).

## Locked decisions (from brainstorming)

1. **Scope model:** interactive **consent screen with per-scope checkboxes** at authorize time.
2. **Client registration:** **Dynamic Client Registration (RFC 7591)**, open endpoint.
3. **Persistence:** **Postgres via Spring AS JDBC repositories** (+ Flyway `V54`).
4. **`/mcp` validation seam:** **Approach A** — extend the existing MCP auth path so an OAuth MCP
   token yields the *same* `AccessKeyAuthentication` principal (scopes → authorities). The `@Tool`
   layer, `ScopeEnforcementAspect`, and `UserContext` are untouched.

## Token model

OAuth remote-MCP clients receive a **JWT MCP access token**, HS256-signed with the same
`JWT_SECRET` as every other Picsou token, but with a distinct claim shape:

| Claim | Web/iOS token (`picsou-ios`) | **MCP token (remote clients)** |
|-------|------------------------------|--------------------------------|
| `type` | `access` | `mcp` |
| `aud` | (default) | `picsou-mcp` |
| `uid` | owner user id | owner user id |
| `scope` | — | space-delimited granted MCP scopes |
| `role` | present | **absent** |
| `tv` | present | present (revocation via password change still works) |

The `type=mcp` / `aud=picsou-mcp` marker is **load-bearing for Property A**: `JwtTokenAuthenticator`
accepts `type=mcp` **only** on the `/mcp` path and `type=access` **only** on the `/api` path. An MCP
token presented to `/api/**` (which `JwtAuthenticationFilter` also reads as a Bearer) is rejected →
an OAuth MCP credential can never reach the web surface. Refresh tokens rotate (reuse disabled), same
as iOS.

## Architecture & flow

```
1. claude.ai → GET /mcp                        ← 401 + WWW-Authenticate: Bearer
                                                   resource_metadata="…/.well-known/oauth-protected-resource"   [NEW]
2. claude.ai → GET /.well-known/oauth-protected-resource   [NEW, RFC 9728]
                                                ← { resource: ".../mcp", authorization_servers: ["https://mcp-picsou.patato.es"] }
3. claude.ai → GET /.well-known/oauth-authorization-server [Spring AS serves it; must be routed+exposed]
                                                ← { issuer, authorization_endpoint, token_endpoint,
                                                    registration_endpoint: "…/oauth2/register", code_challenge_methods:["S256"] }
4. claude.ai → POST /oauth2/register           [NEW, RFC 7591, unauthenticated]
                                                ← { client_id }   → persisted (JdbcRegisteredClientRepository)
5. claude.ai → GET /oauth2/authorize?client_id&redirect_uri&code_challenge&scope
     - no access_token cookie → redirect to SPA /login (existing password+TOTP), bounce back
     - authenticated → CONSENT SCREEN [NEW]: checkboxes over requested scopes
     - approve → auth code → redirect https://claude.ai/api/mcp/auth_callback?code
6. claude.ai → POST /oauth2/token (code + PKCE verifier)   ← { access_token: <MCP JWT>, refresh_token }
7. claude.ai → GET /mcp  Authorization: Bearer <MCP JWT>
     - AccessKeyAuthFilter [EXTENDED]: non-psk_ Bearer → validate MCP JWT → AccessKeyAuthentication(owner, scopes)
     ← normal MCP SSE; tools run scoped; Properties A/B/C intact
```

## Components & files

### Backend — OAuth server core
- `config/AuthorizationServerConfig.java` *(modify)* — switch to `JdbcRegisteredClientRepository`,
  `JdbcOAuth2AuthorizationService`, `JdbcOAuth2AuthorizationConsentService`; **seed `picsou-ios`** at
  startup if absent; enable consent (`requireAuthorizationConsent(true)`) for DCR clients only;
  `authorizationEndpoint().consentPage("/oauth2/consent")`. Extend `jwtTokenCustomizer` to stamp the
  MCP-token claim shape for remote clients (branch on registered client / a client-setting flag).
- `config/JwtTokenAuthenticator.java` *(modify)* — add MCP-token validation (`type=mcp`,
  `aud=picsou-mcp`) exposing `{uid, scopes}`; keep `type=access` path for web/iOS. Single validation
  path — no divergence (per the iOS ADR).
- `config/DynamicClientRegistrationController.java` *(new)* — `POST /oauth2/register` (RFC 7591):
  validate `redirect_uris`, force `token_endpoint_auth_method=none` + PKCE S256, register scopes =
  `Scopes.ALL`, write via the JDBC client repository. (Spring AS's built-in OIDC registration
  endpoint requires an initial access token; a small custom endpoint matches claude.ai's open DCR.)
- `config/ProtectedResourceMetadataController.java` *(new)* — `GET /.well-known/oauth-protected-resource`
  (RFC 9728) returning `{ resource, authorization_servers, scopes_supported, bearer_methods_supported }`.
  (Ship a controller unless the pinned Spring Security 6.4.10 already provides PRM — confirm during
  planning.)

### Backend — /mcp validation seam (Approach A)
- `config/AccessKeyAuthFilter.java` *(modify)* — on `/mcp` only: a Bearer that is **not** `psk_` is
  tried as an MCP JWT → build the same `AccessKeyAuthentication(owner AppUser, authorities=scopes)`.
  `psk_` handling byte-for-byte unchanged. `shouldNotFilter` still restricts to `/mcp` (Property A).
- `config/SecurityConfig.java` *(modify)* — add an `AuthenticationEntryPoint` for `/mcp/**` that emits
  `WWW-Authenticate: Bearer resource_metadata="…"` on the `401`.

### Backend — revocation ("connected apps")
- `controller/ConnectedAppsController.java` *(new)* — cookie-authed, member-scoped, under `/api`:
  list the caller's OAuth authorizations (client name, scopes, issued/last-used) and **revoke** one
  (delete from `oauth2_authorization`). Mirrors access-key revoke semantics.
- DTOs under `dto/`.

### Frontend
- `pages/oauth/ConsentPage.tsx` *(new)* — consent screen at `/oauth2/consent`: renders requested
  scopes as checkboxes (grouped, reusing the access-key scope UI helpers), POSTs the selection back to
  `/oauth2/authorize`. **Mobile-responsive.** i18n `en`/`fr` (`oauthConsent.*`).
- `features/connectedApps/*` + a **"Connected apps"** section in Settings, placed **next to** the
  existing "Access keys & MCP" section (two blocks: static keys vs OAuth apps). List + revoke.
- Reuse `features/accessKeys/scopes.ts` grouping/i18n so scope labels stay consistent.

### Database
- `db/migration/V54__oauth2_authorization_server.sql` *(new)* — the standard Spring AS JDBC schema:
  (next free slot: `main` reaches V53 `transaction_fees`, `1.1.0` reaches V51 — V54 is clear on both)
  `oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent`.

### Proxy
- `frontend/nginx.conf` + `docker/nginx.conf` *(modify)* — add `location` for
  `/.well-known/oauth-authorization-server`, `/.well-known/oauth-protected-resource`, and ensure
  `/oauth2` covers `/oauth2/register` → backend.
- **Homelab edge (out of repo):** open the same paths publicly (currently `403`). Documented in the
  feature note; the operator must apply it on `192.168.1.149`.

## Security properties (must hold)

- **A — keys/tokens only reach `/mcp`, never `/api`.** `AccessKeyAuthFilter` still runs only on
  `/mcp`. The MCP token's `type=mcp`/`aud=picsou-mcp` is rejected by the `/api` Bearer path — an MCP
  token cannot authenticate the web surface even though `JwtAuthenticationFilter` reads Bearer there.
- **B — no impersonation.** The principal is `AccessKeyAuthentication` (owner `AppUser`);
  `UserContext.getMemberIdOverride()` short-circuits for that type — unchanged.
- **C — scope-only authorities.** MCP-token authorities are the granted scope strings; never
  `ROLE_ADMIN`. Same aspect enforcement as access-keys.
- **Consent gating.** DCR registration is open (RFC 7591), but issuing a token requires the user to
  authenticate (password + TOTP) *and* approve scopes on the consent screen — an unauthenticated
  registration alone grants nothing.
- **Redirect strictness.** DCR stores exact `redirect_uris`; the authorize step enforces exact match.
- **Scope validation.** Requested scopes are validated ⊆ `Scopes.ALL`; the consent screen can only
  narrow, never widen.
- **Revocation.** Delete the persisted authorization → the refresh token stops working and the access
  token expires shortly after (short TTL). Password change (bumps `tv`) also invalidates.

## Error handling

- `/mcp` unauthenticated → `401` + `WWW-Authenticate` (bootstraps discovery).
- Unknown/again-unregistered `client_id` at authorize/token → standard `invalid_client`.
- `redirect_uri` mismatch → rejected before login (no open redirect).
- Requested scope ⊄ `Scopes.ALL` → registration/authorize rejected.
- Expired/invalid MCP token at `/mcp` → `401` (claude.ai refreshes or re-auths).
- MCP token at `/api` → `401` (Property A).

## Testing

Backend (`mvn test`, Mockito + a focused slice):
- `JwtTokenAuthenticatorTest` *(extend)* — MCP token accepted on `/mcp` path, rejected on `/api`
  path; web token rejected on `/mcp` path.
- `AccessKeyAuthFilterTest` *(extend)* — `/mcp` with a valid MCP JWT ⇒ `AccessKeyAuthentication` with
  the right scope authorities; malformed/expired ⇒ `401`; Property A still denies `/api`.
- `DynamicClientRegistrationControllerTest` *(new)* — RFC 7591 happy path, redirect validation,
  forced `none` + PKCE, scope subset.
- `ProtectedResourceMetadataControllerTest` *(new)* — PRM JSON shape.
- `AuthorizationServerConfigTest` *(extend)* — JDBC repos wired; `picsou-ios` seeded; consent required
  for DCR clients, not for iOS; token customizer stamps the right claim shape per client.
- `ConnectedAppsControllerTest` *(new)* — list/revoke, member isolation.
- `McpToolCatalogTest` — unchanged (no new tools; guardrail that no auth/admin tool leaked).

Frontend (`bunx vitest run`; verify with a clean `bun run build`):
- Consent page renders requested scopes, submits selection; scope-label parity with `Scopes.ALL`.
- Connected-apps list/revoke hook behavior.

End-to-end (manual, needs the box up + edge opened + a real claude.ai connector): 401→discovery→DCR→
authorize+consent→token→`/mcp` tools call. Documented as a runbook in the feature note.

## Framework-version caveats (confirm during planning)

- **DCR:** implement a custom RFC 7591 `/oauth2/register` backed by the JDBC client repo (Spring AS's
  OIDC registration endpoint expects an initial access token; claude.ai does open DCR).
- **PRM (RFC 9728):** ship a small controller unless spring-security 6.4.10 already exposes it.
- **Spring AS JDBC schema:** use the version-matched DDL shipped with the pinned Spring Authorization
  Server release.

## Verification limits (be honest)

- The upstream is **down** right now (`502`); nothing can be exercised end-to-end until the box and
  the homelab edge are back and the discovery/authorize/register/token paths are publicly reachable.
- The full claude.ai handshake can only be verified against a live public instance. Unit/slice tests
  cover the server-side pieces; the e2e handshake is a manual runbook.

## Follow-ups (after implementation)

- Feature note `docs/features/mcp-oauth-remote.md`.
- ADR amending `2026-07-06-drop-oauth2-token-scope` (the anticipated use case is now built) and
  `2026-07-03-oauth2-authorization-server-for-native-app` (second client class: remote MCP).
