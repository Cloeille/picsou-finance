# Remote-MCP OAuth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a third-party remote MCP client (claude.ai) connect to Picsou's `/mcp` via a standards-compliant OAuth flow (discovery → DCR → authorize+consent → token), receiving a **scope-limited MCP JWT** that authorizes `/mcp` exactly like an access-key.

**Architecture:** Extend the existing Spring Authorization Server (built for `picsou-ios`) with: persistent JDBC repositories, RFC 9728 protected-resource metadata, RFC 7591 dynamic client registration, an interactive consent screen, and a distinct **MCP token claim shape** (`type=mcp`, `aud=picsou-mcp`, `scope=…`). At `/mcp`, the existing auth path is extended so a non-`psk_` Bearer MCP JWT builds the *same* `AccessKeyAuthentication` (scopes → authorities) — the tool layer, `ScopeEnforcementAspect`, and `UserContext` are untouched.

**Tech Stack:** Java 21, Spring Boot 3.4.9, Spring Security 6.4.10, Spring Authorization Server (~1.4.x, BOM-managed), Nimbus JOSE, PostgreSQL 16 / Flyway, React 19 / TS / Tailwind v4 / bun.

**Design spec:** [`docs/briefs/2026-07-12-remote-mcp-oauth-design.md`](./2026-07-12-remote-mcp-oauth-design.md)

## Global Constraints

- **Branch:** commit on `1.1.0` (do NOT branch, do NOT bump semver).
- **Flyway:** next free slot is **V54** (global max across all branches is V53 `transaction_fees` on `main`). Verify with `git ls-tree` across branches before finalizing the number.
- **Docs language:** all `docs/` content in **English**.
- **Frontend:** must be **mobile-responsive**; verify frontend with a clean `bun run build` (CI runs `tsc -b` over the whole tree — a cached `tsc --noEmit` can miss errors).
- **Security invariants (must not regress):** Property A (keys/MCP-tokens authenticate `/mcp` only, never `/api`), Property B (no `?memberId=` impersonation — principal is `AccessKeyAuthentication`), Property C (scope-only authorities, never `ROLE_ADMIN`).
- **Same signing key:** all tokens HS256 with `JWT_SECRET`; do not introduce a second key.
- **`picsou-ios` and the web cookie flow must not change behavior.**
- **Subagents:** do NOT commit — the orchestrator owns commit boundaries.
- **Verification ceiling:** the full claude.ai handshake cannot be exercised until the host + homelab edge are up. Each task is verifiable by `mvn test` / `bunx vitest run` / `bun run build`; the e2e handshake is a manual runbook (Task 15).

---

## Phase 1 — OAuth server core + persistence

### Task 1: Flyway V54 — Spring Authorization Server JDBC schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V54__oauth2_authorization_server.sql`

**Interfaces:**
- Produces: tables `oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent` (the schema Spring AS's `Jdbc*` repositories expect).

- [ ] **Step 1:** Confirm the number is free: `git ls-tree -r --name-only origin/main -- backend/src/main/resources/db/migration/ | grep -E 'V5[0-9]'` and the same for every branch; expect max V53. Use V54.
- [ ] **Step 2:** Copy the **version-matched** DDL from the Spring Authorization Server release that the BOM resolves (`mvn -q dependency:tree -Dincludes=org.springframework.security:spring-security-oauth2-authorization-server | head` to read the version — resolved to **1.4.5**), from its `oauth2-registered-client-schema.sql`, `oauth2-authorization-schema.sql`, `oauth2-authorization-consent-schema.sql`. Adapt types for PostgreSQL: **`blob`→`text`** (NOT `bytea` — `JdbcOAuth2AuthorizationService` only binds columns whose JDBC-reported type is `Types.BLOB`; pgjdbc reports `bytea` as `Types.BINARY`, so a `bytea` column would break the String write path. The vendor `oauth2-authorization-schema.sql` says so in its own inline comment). Keep `timestamp`.
- [ ] **Step 3:** Boot check — run the app's Flyway migration path in a test. If a Testcontainers Postgres test exists (`BudgetSeedWriteOnReadPostgresTest` pattern), add a minimal `@SpringBootTest` that asserts the three tables exist; else assert migration applies cleanly. Run: `mvn test -Dtest=OAuth2SchemaMigrationTest` — Expected: PASS (self-skips if no Docker, matching the repo convention).
- [ ] **Step 4:** Report for commit (do not commit).

### Task 2: JDBC repositories + seed `picsou-ios`

**Files:**
- Modify: `backend/src/main/java/com/picsou/config/AuthorizationServerConfig.java`
- Test: `backend/src/test/java/com/picsou/config/AuthorizationServerConfigTest.java`

**Interfaces:**
- Consumes: V54 schema (Task 1), `JWKSource` bean (existing).
- Produces: beans `RegisteredClientRepository` (Jdbc), `OAuth2AuthorizationService` (Jdbc), `OAuth2AuthorizationConsentService` (Jdbc). Seeds the `picsou-ios` `RegisteredClient` (from `OAuthClientProperties`) if absent.

- [ ] **Step 1: failing test** — `AuthorizationServerConfigTest`: assert the `RegisteredClientRepository` bean is a `JdbcRegisteredClientRepository` and `registeredClientRepository.findByClientId(props.getClientId())` returns non-null (picsou-ios seeded). Use `@SpringBootTest` slice or a focused context; follow the existing test's style.
- [ ] **Step 2:** Run → FAIL (currently `InMemoryRegisteredClientRepository`).
- [ ] **Step 3: implement** — replace the in-memory repos with `JdbcRegisteredClientRepository(jdbcTemplate)`, `JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository)`, `JdbcOAuth2AuthorizationConsentService(jdbcTemplate)`. Add a `CommandLineRunner`/`@PostConstruct` (or `ApplicationRunner` bean) that seeds `picsou-ios` via `RegisteredClientRepository.save(...)` only if `findByClientId` is null. Keep the exact existing client settings (PKCE required, `none` auth, `picsou://callback`, TTLs).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Report for commit.

### Task 3: MCP token claim shape

**Files:**
- Modify: `backend/src/main/java/com/picsou/config/AuthorizationServerConfig.java` (the `jwtTokenCustomizer` bean)
- Test: `backend/src/test/java/com/picsou/config/AuthorizationServerConfigTest.java` (extend)

**Interfaces:**
- Produces: for a registered client flagged as an MCP client, the access token carries `type=mcp`, `aud=["picsou-mcp"]`, `uid=<AppUser id>`, `scope="<space-delimited granted scopes>"`, `tv`, and **no** `role`. For `picsou-ios`, the claim shape is unchanged (`type=access`, `uid`, `tv`, `role`).
- Convention: an MCP client is marked by a client setting `settings.put("settings.client.picsou-mcp", true)` (a `ClientSettings` custom setting) set at DCR time (Task 8); the customizer branches on `context.getRegisteredClient()` reading that setting.

- [ ] **Step 1: failing test** — build a `JwtEncodingContext` for (a) an MCP-flagged client and (b) picsou-ios, run the customizer, assert claim shapes above. (Mockito-construct the context; principal = an `AppUser` with a known id/tokenVersion.)
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: implement** — in the customizer, after the existing `type=access` branch, add: if the registered client has the `picsou-mcp` flag, stamp `type=mcp`, `audience(List.of("picsou-mcp"))`, `uid`, `tv`, and `scope` = `String.join(" ", context.getAuthorizedScopes())`; do **not** stamp `role`. Keep HS256 header.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Report for commit.

---

## Phase 2 — `/mcp` validation seam (Approach A)

### Task 4: `JwtTokenAuthenticator` — MCP token validation, path-scoped acceptance

**Files:**
- Modify: `backend/src/main/java/com/picsou/config/JwtTokenAuthenticator.java`
- Test: `backend/src/test/java/com/picsou/config/JwtTokenAuthenticatorTest.java` (extend)

**Interfaces:**
- Produces: `Optional<McpPrincipal> authenticateMcpToken(String jwt)` where `McpPrincipal` = `{ long uid, Set<String> scopes }`. Validates HS256 signature, expiry, `type=mcp`, `aud` contains `picsou-mcp`, and `tv` matches the loaded `AppUser.tokenVersion`. Returns empty on any failure.
- Guarantee: the existing web/bearer path (`authenticate(...)` used by `JwtAuthenticationFilter` on `/api`) must **reject** `type=mcp` tokens (only `type=access`), and `authenticateMcpToken` must reject `type=access` tokens.

- [ ] **Step 1: failing tests** — (a) a valid MCP JWT → `authenticateMcpToken` returns `{uid, scopes}`; (b) a `type=access` web JWT → `authenticateMcpToken` empty; (c) an MCP JWT passed to the existing `/api` `authenticate(...)` → empty/reject; (d) expired / bad-signature / wrong-`tv` MCP JWT → empty.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: implement** — add `authenticateMcpToken` reusing the shared HS256 verification; add a guard in the `/api` validation to require `type=access`. Load `AppUser` by `uid` to compare `tv` and confirm the owner is active (mirror the existing web-token checks).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Report for commit.

### Task 5: `AccessKeyAuthFilter` — accept MCP JWT on `/mcp`

**Files:**
- Modify: `backend/src/main/java/com/picsou/config/AccessKeyAuthFilter.java`
- Test: `backend/src/test/java/com/picsou/config/AccessKeyAuthFilterTest.java` (extend)

**Interfaces:**
- Consumes: `JwtTokenAuthenticator.authenticateMcpToken` (Task 4), `AppUserRepository`, existing `AccessKeyAuthentication`.
- Produces: on `/mcp`, `Authorization: Bearer <token>` where token does **not** start with `psk_` → validate as MCP JWT → set `AccessKeyAuthentication(ownerAppUser, authorities = scopes.map(SimpleGrantedAuthority::new))`. `psk_` path unchanged. `shouldNotFilter` unchanged (still `/mcp`-only → Property A).

- [ ] **Step 1: failing tests** — (a) `/mcp` + valid MCP JWT → `SecurityContext` holds `AccessKeyAuthentication` with the expected scope authorities and owner principal; (b) `/api/**` + MCP JWT → filter does not authenticate (Property A: `shouldNotFilter` true for non-`/mcp`); (c) `/mcp` + malformed/expired JWT → no authentication (chain yields 401); (d) `/mcp` + `psk_…` → unchanged behavior.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: implement** — in `doFilterInternal`, branch the extracted Bearer: `psk_` → existing `AccessKeyService.validate`; else → `authenticateMcpToken` → resolve owner `AppUser` → build `AccessKeyAuthentication`. Keep the per-key throttle only for `psk_` (MCP tokens are short-lived and rotate; no bucket needed, or reuse a light guard — keep it simple).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Report for commit.

### Task 6: `/mcp` 401 `WWW-Authenticate` entry point

**Files:**
- Modify: `backend/src/main/java/com/picsou/config/SecurityConfig.java`
- Create: `backend/src/main/java/com/picsou/config/McpAuthenticationEntryPoint.java`
- Test: `backend/src/test/java/com/picsou/config/McpAuthenticationEntryPointTest.java` (new)

**Interfaces:**
- Produces: on an unauthenticated `/mcp/**` request, `401` with header `WWW-Authenticate: Bearer resource_metadata="<issuer>/.well-known/oauth-protected-resource"`. The issuer/base URL is derived from the request (respecting `X-Forwarded-*`, `forward-headers-strategy=framework`).

- [ ] **Step 1: failing test** — `MockMvc` GET `/mcp` unauthenticated → status 401 and the `WWW-Authenticate` header matches `Bearer resource_metadata=".*/.well-known/oauth-protected-resource"`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: implement** — write `McpAuthenticationEntryPoint implements AuthenticationEntryPoint` building the absolute metadata URL from `ServletUriComponentsBuilder.fromCurrentContextPath()`. In `SecurityConfig`, set it via `exceptionHandling().defaultAuthenticationEntryPointFor(new McpAuthenticationEntryPoint(), new AntPathRequestMatcher("/mcp/**"))` so only `/mcp` gets the OAuth challenge (other paths keep their current 401/redirect).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Report for commit.

---

## Phase 3 — Discovery + Dynamic Client Registration

### Task 7: Protected Resource Metadata (RFC 9728)

**Files:**
- Create: `backend/src/main/java/com/picsou/config/ProtectedResourceMetadataController.java`
- Test: `backend/src/test/java/com/picsou/config/ProtectedResourceMetadataControllerTest.java` (new)

**Interfaces:**
- Produces: `GET /.well-known/oauth-protected-resource` → JSON `{ "resource": "<base>/mcp", "authorization_servers": ["<base>"], "scopes_supported": [<Scopes.ALL>], "bearer_methods_supported": ["header"] }`, base URL derived from the request. Must be reachable without authentication (permit in `SecurityConfig`).

- [ ] **Step 1: failing test** — `MockMvc` GET the path → 200, JSON has `resource` ending `/mcp`, `authorization_servers[0]` = base, `scopes_supported` equals `Scopes.ALL`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: implement** — `@RestController` returning a `Map`/record; permit the path in `SecurityConfig` (`permitAll`) and confirm no filter (SetupFilter/AccessKey) blocks it. First check whether the pinned Spring Security already exposes PRM (`OAuth2 Resource Server metadata`); if it does, prefer configuring it over a controller — note the finding in the PR.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Report for commit.

### Task 8: Dynamic Client Registration (RFC 7591)

**Files:**
- Create: `backend/src/main/java/com/picsou/config/DynamicClientRegistrationController.java`
- Create: `backend/src/main/java/com/picsou/dto/ClientRegistrationRequest.java`, `ClientRegistrationResponse.java`
- Test: `backend/src/test/java/com/picsou/config/DynamicClientRegistrationControllerTest.java` (new)

**Interfaces:**
- Consumes: `RegisteredClientRepository` (Jdbc, Task 2).
- Produces: `POST /oauth2/register` accepting `{ client_name?, redirect_uris[], token_endpoint_auth_method?, grant_types?, scope? }` → creates a public PKCE `RegisteredClient` with: `clientAuthenticationMethod(NONE)`, grant types `authorization_code`+`refresh_token`, the given `redirect_uris`, scopes = subset of `Scopes.ALL` (default all read scopes if none given), `ClientSettings` with `requireProofKey(true)`, `requireAuthorizationConsent(true)`, and the custom `picsou-mcp` flag (Task 3). Returns `201 { client_id, client_id_issued_at, redirect_uris, token_endpoint_auth_method:"none", grant_types, scope }`. No client secret.

- [ ] **Step 1: failing tests** — (a) happy path returns `client_id`, persists a findable client with `none`+PKCE+consent+`picsou-mcp` flag; (b) empty/malformed `redirect_uris` → 400; (c) requested scope ⊄ `Scopes.ALL` → 400; (d) any `token_endpoint_auth_method` other than `none` is coerced to `none` (public only).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: implement** — the controller + DTOs; generate `client_id` (`UUID`); `registeredClientRepository.save(...)`. Permit `/oauth2/register` (unauthenticated) in the AS or API chain — ensure it is NOT captured by the AS endpoints matcher in a way that demands auth; simplest is a plain `@RestController` on the API chain with `permitAll` for that exact path, writing to the shared Jdbc repo.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Report for commit.

### Task 9: Advertise `registration_endpoint` + reachability

**Files:**
- Modify: `backend/src/main/java/com/picsou/config/AuthorizationServerConfig.java` (`AuthorizationServerSettings`)
- Test: `backend/src/test/java/com/picsou/config/AuthorizationServerMetadataTest.java` (new)

**Interfaces:**
- Produces: `/.well-known/oauth-authorization-server` advertises `registration_endpoint = <base>/oauth2/register` and `code_challenge_methods_supported` includes `S256`.

- [ ] **Step 1: failing test** — `MockMvc` GET `/.well-known/oauth-authorization-server` → 200; body contains `registration_endpoint` ending `/oauth2/register` and `S256`.
- [ ] **Step 2:** Run → FAIL (default settings don't advertise a custom registration endpoint).
- [ ] **Step 3: implement** — set `AuthorizationServerSettings.builder()....` to include the registration endpoint (or, if the custom `/oauth2/register` isn't part of AS settings, add it to the PRM/AS metadata document explicitly — a small response post-processor or a supplementary field). Confirm the metadata endpoint is permitted (unauthenticated) and routed.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Report for commit.

---

## Phase 4 — Consent screen + connected-apps management

### Task 10: Consent backend wiring

**Files:**
- Modify: `backend/src/main/java/com/picsou/config/AuthorizationServerConfig.java`
- Create: `backend/src/main/java/com/picsou/controller/OAuthConsentController.java`
- Test: `backend/src/test/java/com/picsou/controller/OAuthConsentControllerTest.java` (new)

**Interfaces:**
- Produces: (a) AS configured with `authorizationEndpoint().consentPage("/oauth2/consent")`; (b) `GET /api/oauth2/consent-info?client_id=&scope=&state=` (cookie-authed) → `{ client_name, requested_scopes[], state }` so the SPA can render the checkboxes and re-POST. The actual approval POSTs to `/oauth2/authorize` (Spring AS consent submission: `client_id`, `state`, and one `scope` param per approved scope).

- [ ] **Step 1: failing test** — `consent-info` returns the requested scopes for a known pending authorization / client; unknown client → 404; unauthenticated → 401.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: implement** — controller reads the client + requested scopes (from query, validated ⊆ registered client scopes ⊆ `Scopes.ALL`); permit `/oauth2/consent` (SPA route served by index.html — nginx already does try_files) and `/api/oauth2/consent-info` (cookie auth).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Report for commit.

### Task 11: Consent page (frontend)

**Files:**
- Create: `frontend/src/pages/oauth/ConsentPage.tsx`
- Create: `frontend/src/features/oauthConsent/api.ts`
- Modify: `frontend/src/app/routes.tsx` — add route `/consent` (NOT under `/oauth2`, which nginx routes to the backend; `/consent` is served as the SPA)
- Modify: `frontend/src/i18n/locales/en.json`, `fr.json` (`oauthConsent.*`)
- Test: `frontend/src/pages/oauth/ConsentPage.test.tsx` (new)

**Interfaces:**
- Consumes: `GET /api/oauth2/consent-info` (Task 10).
- Produces: a mobile-responsive consent screen: app name, scope checkboxes (reuse `features/accessKeys/scopes.ts` grouping + i18n labels), Approve/Deny. Approve builds a form POST to `/oauth2/authorize` with `client_id`, `state`, and a `scope` field per checked scope; Deny redirects back with `error=access_denied`.

- [ ] **Step 1: failing test** — vitest: renders scopes from a mocked `consent-info`; checking/unchecking updates the form; Approve submits the selected scopes. Assert scope-label parity with `features/accessKeys/scopes.ts`.
- [ ] **Step 2:** Run `bunx vitest run src/pages/oauth/ConsentPage.test.tsx` → FAIL.
- [ ] **Step 3: implement** the page + api + route + i18n. Tailwind, responsive (stacks on mobile, two-column scope grid on desktop like the access-key dialog).
- [ ] **Step 4:** Run vitest → PASS; then `bun run build` → PASS (clean tsc).
- [ ] **Step 5:** Report for commit.

### Task 12: Connected-apps backend (list/revoke)

**Files:**
- Create: `backend/src/main/java/com/picsou/controller/ConnectedAppsController.java`
- Create: `backend/src/main/java/com/picsou/dto/ConnectedAppResponse.java`
- Test: `backend/src/test/java/com/picsou/controller/ConnectedAppsControllerTest.java` (new)

**Interfaces:**
- Consumes: `OAuth2AuthorizationService` (Jdbc) + repository access to `oauth2_authorization` for listing by principal.
- Produces: `GET /api/connected-apps` (cookie-authed, member/user-scoped) → `[{ id, client_name, scopes[], issued_at, last_used_at }]`; `DELETE /api/connected-apps/{id}` → revoke (remove the authorization). Only the caller's own authorizations (filter by `principal_name` = the user).

- [ ] **Step 1: failing tests** — list returns only the caller's authorizations; delete removes one; deleting another user's authorization → 404 (member isolation).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: implement** — query `oauth2_authorization` (a small `JdbcTemplate` query or `OAuth2AuthorizationService.findById`); scope by `principal_name`. Revoke = delete the row(s) for that authorization id.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Report for commit.

### Task 13: Connected-apps frontend + Settings section

**Files:**
- Create: `frontend/src/features/connectedApps/{api.ts,hooks.ts}`
- Create: `frontend/src/pages/settings/sections/ConnectedAppsSection.tsx`
- Modify: the Settings page to render `ConnectedAppsSection` next to `AccessKeysSection`
- Modify: `frontend/src/i18n/locales/en.json`, `fr.json` (`connectedApps.*`)
- Test: `frontend/src/features/connectedApps/hooks.test.ts` (new)

**Interfaces:**
- Consumes: `/api/connected-apps` (Task 12).
- Produces: a Settings block listing connected OAuth apps (name, scopes, dates) with a Revoke button (confirm) — mirrors `AccessKeysSection` UX. Mobile-responsive.

- [ ] **Step 1: failing test** — vitest: list hook renders apps; revoke calls DELETE and invalidates the query.
- [ ] **Step 2:** Run vitest → FAIL.
- [ ] **Step 3: implement** the feature + section + i18n; place it as a sibling block to "Access keys & MCP".
- [ ] **Step 4:** vitest → PASS; `bun run build` → PASS.
- [ ] **Step 5:** Report for commit.

---

## Phase 5 — Proxy + documentation

### Task 14: Reverse-proxy routing (repo)

**Files:**
- Modify: `frontend/nginx.conf`, `docker/nginx.conf`

**Interfaces:**
- Produces: `location` blocks routing `/.well-known/oauth-authorization-server`, `/.well-known/oauth-protected-resource`, and `/oauth2/register` to the backend (the latter is already covered if `location /oauth2` matches `/oauth2/register` — verify prefix match). Keep `/mcp` SSE block intact.

- [ ] **Step 1:** Add `location = /.well-known/oauth-authorization-server` and `location = /.well-known/oauth-protected-resource` proxying to the backend (same `proxy_set_header` block as `/oauth2`). Confirm `/oauth2/register` is matched by the existing `location /oauth2` prefix.
- [ ] **Step 2:** Validate config syntax locally if possible (`nginx -t` against the file, or a container build). No automated test; note the manual check in the PR.
- [ ] **Step 3:** Report for commit.

### Task 15: Docs — feature note, ADR, edge runbook

**Files:**
- Create: `docs/features/mcp-oauth-remote.md`
- Create: `docs/decisions/2026-07-12-remote-mcp-oauth-authorization.md`
- Modify: `docs/INDEX.md`
- Modify: `docs/features/mcp-server.md` (cross-link), `docs/features/mcp-budget-oauth2.md` (note the token path now exists)

**Interfaces:**
- Produces: the feature note (flow, token model, security properties, files, gotchas), an ADR (amends `2026-07-06-drop-oauth2-token-scope` and `2026-07-03-oauth2-authorization-server-for-native-app`), and an **edge runbook** section: the operator must open `/.well-known/oauth-*` and `/oauth2/*` publicly on the `192.168.1.149` reverse proxy (currently `403`), and the backend host must be up (currently `502`).

- [ ] **Step 1:** Write the feature note from the design spec + as-built details.
- [ ] **Step 2:** Write the ADR; update `docs/INDEX.md` and cross-links.
- [ ] **Step 3:** Report for commit.

---

## Self-Review (completed by author)

- **Spec coverage:** discovery (T7,T9), DCR (T8), consent (T10,T11), token model (T3), `/mcp` seam (T4,T5), 401 challenge (T6), persistence (T1,T2), revocation (T12,T13), proxy (T14), docs (T15). All spec sections mapped.
- **Placeholder scan:** framework-glue steps (JDBC DDL version, PRM framework support, AS settings for registration endpoint) are flagged with an explicit *verify-then-implement* step, not left as TODO.
- **Type consistency:** `authenticateMcpToken → {uid, scopes}` (T4) is consumed verbatim by T5; the `picsou-mcp` client-settings flag set in T8 is read in T3; `Scopes.ALL` gates T7/T8/T10.

## Manual e2e runbook (post-deploy, needs host + edge up)

1. `curl -i https://mcp-picsou.patato.es/mcp` → `401` + `WWW-Authenticate: … resource_metadata=…`.
2. `curl https://mcp-picsou.patato.es/.well-known/oauth-protected-resource` → JSON.
3. `curl https://mcp-picsou.patato.es/.well-known/oauth-authorization-server` → JSON with `registration_endpoint`.
4. In claude.ai: add custom connector → `https://mcp-picsou.patato.es/mcp` → OAuth → login (password+TOTP) → consent (pick scopes) → connected.
5. In claude.ai, run a read tool (e.g. dashboard) → returns scoped data.
6. Settings → Connected apps → revoke → claude.ai loses access after token expiry.
