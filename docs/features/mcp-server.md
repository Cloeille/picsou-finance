# Feature: Embedded MCP server + scoped access-keys

> Last updated: 2026-10-07

## Context

Picsou exposes a rich, member-scoped REST API, but until now an external **app** (an AI
assistant / MCP client such as Claude Desktop) had no way to read or act on a user's finances —
auth was JWT-in-HttpOnly-cookie only, with no API-key concept. This feature serves Picsou over
the **Model Context Protocol** so an AI app can analyse and help manage finances, gated by
**access-keys** that each member creates in Settings and whose **scopes** they control. A key is
hard-bound to its owner's data and can only ever reach a small, curated, audited set of tools.

## How it works

The MCP server is **embedded in the Spring backend** (Spring AI MCP, HTTP+SSE transport). A
second authentication principal — a bearer **access-key** (`psk_…`) — sits alongside the cookie.
An app points an MCP client at `https://<host>/mcp` with `Authorization: Bearer psk_…`; the
`@Tool` methods resolve the key owner's member and delegate to the existing, already
member-scoped services, so member isolation is automatic.

Three security properties are guaranteed structurally (not by per-call checks):

- **A — Keys authenticate ONLY `/mcp/**`, never `/api/**`.** `AccessKeyAuthFilter.shouldNotFilter()`
  returns `true` for any non-`/mcp` path, so a `psk_` token presented to `/api/**` is never even
  validated → 401. A key therefore cannot bypass the curated surface by calling excluded REST
  endpoints directly (e.g. bank-auth).
- **B — No impersonation for keys.** The principal is the owning `AppUser` (so `UserContext` works
  unchanged), but the `Authentication` is a distinct `AccessKeyAuthentication`;
  `UserContext.getMemberIdOverride()` short-circuits to `null` for that type, so the admin
  `?memberId=` override can never apply to a key — even an admin-owned one.
- **C — Keys never carry `ROLE_ADMIN`.** Authorities are scope strings only → `/api/admin/**` and
  any `isAdmin()`-gated logic stay unreachable.

### Key files

**Backend — auth & data**
- `backend/src/main/java/com/picsou/mcp/AccessKeyService.java` — issue / validate / list / revoke keys; SHA-256 hashing, throttled `last_used_at`.
- `backend/src/main/java/com/picsou/mcp/AccessKeyUsageRecorder.java` — `REQUIRES_NEW` best-effort `last_used_at` writer (off the hot path).
- `backend/src/main/java/com/picsou/mcp/Scopes.java` — the scope vocabulary (`domain:action`) and the `ALL` allowlist.
- `backend/src/main/java/com/picsou/mcp/ScopeSetConverter.java` — `Set<String>` ↔ space-delimited column.
- `backend/src/main/java/com/picsou/config/AccessKeyAuthentication.java` — the `Authentication` a key runs as (principal = owner `AppUser`; authorities = scopes).
- `backend/src/main/java/com/picsou/config/AccessKeyAuthFilter.java` — validates the Bearer key for `/mcp/**` only; per-key Bucket4j throttle (429).
- `backend/src/main/java/com/picsou/config/SecurityConfig.java` — registers the filter (4th, anchored to `UsernamePasswordAuthenticationFilter`) and `requestMatchers("/mcp/**").authenticated()`.
- `backend/src/main/java/com/picsou/config/McpSecurityContextPropagationConfig.java` + `backend/src/main/java/com/picsou/config/SecurityContextThreadLocalAccessor.java` — carry the authenticated `SecurityContext` from the servlet thread to Spring AI's reactive tool-execution thread (see Gotchas: scope enforcement across the thread hop).
- `backend/src/main/java/com/picsou/service/UserContext.java` — Property B guard at the top of `getMemberIdOverride()`.
- `backend/src/main/java/com/picsou/model/AccessKey.java` + `backend/src/main/java/com/picsou/repository/AccessKeyRepository.java` + `backend/src/main/resources/db/migration/V37__access_keys.sql`.

**Backend — MCP surface**
- `backend/src/main/java/com/picsou/config/McpToolConfig.java` — the single `ToolCallbackProvider` bean; the one place tools are wired.
- `backend/src/main/java/com/picsou/mcp/tools/{Account,Transaction,Goal,Insight,Sync,Analysis}Tools.java` — the `@Tool` methods, each gated by `@RequiresScope`.
- `backend/src/main/java/com/picsou/mcp/RequiresScope.java` + `backend/src/main/java/com/picsou/mcp/ScopeEnforcementAspect.java` + `backend/src/main/java/com/picsou/exception/MissingScopeException.java` — scope enforcement (AOP) and its clean error.
- `backend/src/main/java/com/picsou/controller/AccessKeyController.java` + `dto/AccessKey{CreateRequest,Response,CreatedResponse}.java` — self-service management REST API under `/api/access-keys`.
- `backend/src/main/java/com/picsou/config/RateLimitConfig.java` — `mcpKeyBuckets`, `accessKeyCreateBuckets`, and the bucket factories.
- `backend/src/main/resources/application.yml` — `spring.ai.mcp.server.*` (HTTP+SSE, `SYNC`, `/mcp` + `/mcp/message`, `MCP_ENABLED` gate, instructions string).

**Frontend**
- `frontend/src/features/accessKeys/{api,hooks,scopes,status}.ts` — TanStack Query layer + scope/status helpers.
- `frontend/src/pages/settings/sections/AccessKeysSection.tsx` — the Settings UI (list, create dialog, one-time secret reveal, connect-your-client block, revoke).
- `i18n/locales/{en,fr}.json` — the `accessKeys.*` namespace.

**Deployment — reverse proxy** (the public `https://<host>/mcp` path; see Gotchas)
- `frontend/nginx.conf` + `docker/nginx.conf` — `location /mcp` → backend, SSE-tuned (`proxy_buffering off`, `proxy_read_timeout 3600s`, HTTP/1.1).
- `frontend/vite.config.ts` — dev proxy `/mcp` → `VITE_API_TARGET`.

The create access-key dialog is intentionally wider than the default dialog
primitive (`42rem` on desktop) because scope cards render in two columns. The
dialog itself stays inside `100dvh - 2rem`; only the form body scrolls, while the
title and action buttons remain visible.

The "Connect your MCP client" block uses full-width code-copy rows: endpoint and
snippet containers are `min-h-10`, rounded, and padded like app inputs, with copy
buttons that become full-width on mobile and keep a stable desktop width.

### Flow

```
AI app / MCP client ──HTTP  GET /mcp (SSE)  +  POST /mcp/message──▶ Spring backend
   │  Authorization: Bearer psk_…
   │
   │  Security filter chain (all anchored to UsernamePasswordAuthenticationFilter):
   │    SetupFilter → JwtAuthenticationFilter → PersistentTokenAuthFilter → AccessKeyAuthFilter
   │      • AccessKeyAuthFilter.shouldNotFilter == true for any non-/mcp path           (Property A)
   │      • validate(psk_…): prefix lookup → constant-time SHA-256 compare → usable? → owner active?
   │      • per-key Bucket4j throttle (429 on overflow)
   │      • set AccessKeyAuthentication(owner AppUser, scope authorities)            (Properties B/C)
   ▼
 Spring AI MCP endpoint  /mcp   (SYNC)   ← tool runs on a Reactor thread; the SecurityContext is
   ▼                                       propagated there (McpSecurityContextPropagationConfig)
 @Tool method  ──@RequiresScope──▶ ScopeEnforcementAspect (throws MissingScopeException if absent)
   ▼
 userContext.currentMemberId()  ← AccessKeyAuthentication ⇒ override refused (Property B)
   ▼
 existing service (findByIdAndMemberId(...)) → member-isolated data
```

Key creation goes the other way, over the cookie-authenticated management API:

```
WebApp (Settings) ──cookie──▶ POST /api/access-keys {name, scopes, expiresAt?}
   ▼  validate scopes ⊆ Scopes.ALL (400 on unknown) · per-member create throttle
 AccessKeyService.create → psk_ + 32 base62 chars · store SHA-256 only
   ▼
 201 { rawSecret (shown ONCE), key }     ← the only time the secret exists in plaintext
```

## Tool catalogue

Every tool acts only on the key owner's own data. The curated write surface covers record
maintenance, **manual account creation**, **refresh-existing-sync** triggers, and account deletion.
`McpToolCatalogTest` pins this exact set.

With `accounts:write`, `delete_account` can soft-delete both manual and synced accounts. Deleting
the last account on a connection also removes that connection: it can clear stored provider
sessions/credentials, remove a wallet or exchange connection, delete an IBKR or SimpleFIN connection,
or delete an Enable Banking requisition. A connection still used by another live account is kept.
The tool returns the `DeletionImpact` of what the deletion actually removed, in the same
transaction: the removed connection's label, or `false` / `null` when the connection is kept or
there was nothing left to remove (wallet row or exchange session already gone, unknown exchange
type, no stored session).
`get_account_deletion_impact` requires only `accounts:read` and makes no changes. It is a preview,
a prediction that can become stale before deletion, so callers should report the result returned
by `delete_account`.

| Scope | Tools |
|-------|-------|
| `accounts:read` | `list_accounts`, `get_account`, `get_account_holdings`, `get_account_balance_history`, `get_account_deletion_impact`, `get_savings_interest`, `get_property_valuations`, `get_loan_summary`, `get_realized_pnl`, `get_exchange_positions` |
| `transactions:read` | `list_account_transactions` |
| `goals:read` | `list_goals`, `get_goal`, `get_goal_monthly_entries` |
| `dashboard:read` | `get_dashboard`, `get_net_worth_history`, `get_profit_and_loss` |
| `family:read` | `get_family_dashboard` |
| `analysis:read` | `get_allocation`, `get_wealth_pyramid`, `get_portfolio_diversification`, `get_wealth_projection`, `get_allocation_targets`, `get_essential_expense_estimate`, `get_savings_suggestions`, `get_real_estate_summary` |
| `sync:read` | `get_sync_status` |
| `prices:read` | `get_price`, `get_security_insight` |
| `accounts:write` | `create_manual_account`, `update_account`, `delete_account`, `add_balance_snapshot`, `upsert_holding`, `delete_holding` |
| `transactions:write` | `add_transaction`, `update_transaction`, `delete_transaction` |
| `goals:write` | `create_goal`, `update_goal`, `delete_goal`, `set_goal_month_contribution` |
| `sync:trigger` | `trigger_full_sync`, `trigger_bank_sync`, `trigger_broker_sync`, `trigger_crypto_exchange_sync`, `trigger_crypto_wallet_sync` |

The wealth-analysis tools (`AnalysisTools`) are read-only. Each calls the service behind its REST
counterpart with the caller's member and returns the same payload. Whole-wealth judgements
(allocation, pyramid, diversification, projection, targets, expense estimate, savings suggestions,
real-estate summary) need `analysis:read`, a scope separate from `accounts:read`. It is not a
summary-only scope: each tool returns the same payload as its analysis page, so granting it shares
everything those pages show. That includes account names and current values, position lines
(ticker, name, account, value), flow figures derived from transactions (net contributions per
account, average monthly spending), the essential expenses the member declared and full property and loan details
(names, city, costs, rents, area, SCPI manager and shares, lender, monthly payment, end date). It
returns no individual transaction and no balance history. Tools that take an account id read one account, so they stay under
`accounts:read` like `get_account_holdings`. An account of another member gets the same not-found
error as every other account tool. `get_security_insight` is market reference data, not member
data, so it sits next to `get_price` under `prices:read`. `get_wealth_projection` keeps the REST
default of 20 years and the service's 1 to 40 clamp. Deliberately not exposed: the security-profile
refresh (a rate-limited fan-out to external providers) and every analysis write (allocation
targets, savings config, real-estate, debt, ownership and visibility settings).

The `budget:*` and `oauth2:*` scopes (including `budget:recurring-write` for recurring-series
triage) and their tools are listed in [Budget + OAuth2 tools in MCP](./mcp-budget-oauth2.md).

**Never exposed** (no `@Tool` exists, so no scope can initiate them): authentication flows,
credential submission or retrieval, connecting a new bank / broker / exchange / wallet, MFA,
admin settings, member management, and GDPR data export. This does not prohibit removing stored
sessions/credentials as the documented side effect of deleting a connection's last account.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| MCP server **embedded** in the backend | Tools call existing member-scoped services in-process; one deployable; smallest new attack surface | Standalone MCP sidecar proxying the REST API (extra hop, duplicated auth, more to secure) |
| **Access-key** principal, separate from the JWT cookie | A distinct `Authentication` type is the seam for Properties B/C; keys are revocable and scope-limited without touching the login | Reuse cookie/JWT for MCP (no scoping, full surface, no per-app revoke) |
| **SHA-256 + constant-time compare** for key hashes | The secret is high-entropy (~190 bits), so a fast hash is safe; enables O(1) prefix lookup then `MessageDigest.isEqual` | bcrypt (needed for low-entropy passwords; here it only adds latency to the hot auth path) |
| **HTTP+SSE** transport (`/mcp` stream + `/mcp/message`) | The only transport Spring AI 1.0.3 / MCP SDK 0.10.0 ship; clients reach it via `mcp-remote` | Streamable HTTP (not available on the pinned version — see the ADR) |
| **Reactor automatic context propagation** to carry the security context to the tool thread | Spring AI runs tools off the servlet thread; this restores the `SecurityContext` there so the existing thread-local check works unchanged | `MODE_INHERITABLETHREADLOCAL` (misses pooled scheduler threads) · making the aspect read auth some other way (leaks the thread concern into every tool) |
| **Curated** write surface (record maintenance, manual account creation, resync, and account deletion with idle-connection cleanup) | No credential submission/retrieval or new authentication flow; deleting the last synced account can remove its stored session/credentials | Expose the full REST surface as tools (uncontrolled blast radius) |
| Scopes as one **space-delimited column** via `@Convert` | Read in full on every auth, never queried individually; no join table | Join table (a query per auth for data that's always read whole) |
| Per-key + per-member **in-memory Bucket4j** throttles | Single-instance self-host; matches the existing `RateLimitConfig` pattern | Distributed rate store (unwarranted for a self-hosted single instance) |

## Gotchas / Pitfalls

- **Scope enforcement needs the security context across a thread hop.** `AccessKeyAuthFilter`
  authenticates on the Tomcat servlet thread, but Spring AI (WebMvc+SSE) executes `@Tool` methods on a
  Reactor scheduler thread. Because `SecurityContextHolder` is a plain `ThreadLocal`, that tool thread
  saw no `Authentication`, so `ScopeEnforcementAspect` (and `UserContext`) found **no scopes** — every
  scoped `tools/call` failed with `Missing required scope`, even for a key that holds the scope. Fixed by
  `McpSecurityContextPropagationConfig`: it registers `SecurityContextThreadLocalAccessor` with the
  Micrometer `ContextRegistry` and calls `Hooks.enableAutomaticContextPropagation()`, so Reactor captures
  the `SecurityContext` at subscription and restores it around tool execution. The accessor is null-safe on
  `restore()` because `SecurityContextHolder.setContext(null)` throws. Unit tests can't catch this — they
  set the context on the test thread — so it only shows up driving the real SSE transport.
- **The reverse proxy must forward `/mcp` with SSE settings.** An MCP client connects to the public origin
  (`https://<host>/mcp`), not the backend port, so nginx/Vite must route `/mcp` to the backend with
  `proxy_buffering off` + a long `proxy_read_timeout` (the GET `/mcp` stream is long-lived) — otherwise the
  SSE events are withheld and the stream stalls. Configured in `frontend/nginx.conf`, `docker/nginx.conf`,
  and the Vite dev proxy (`frontend/vite.config.ts`). Forgetting it yields a `404` (or, if `/` is a SPA
  fallback, an HTML page) on `/mcp` while the backend is perfectly healthy on `:8080/mcp`.
- **MCP sync triggers share one per-member cooldown.** `trigger_full_sync` and the four older
  trigger tools all call `MemberSyncService` and share `mcpMemberSyncBuckets`: one sync per member
  every 15 minutes, and four per day. Every advertised trigger description and the blocked-call
  response explain that this cooldown is shared across tools: a bank-only trigger can therefore
  block a subsequent broker-only trigger. The response includes `Try again in N min` and does not
  touch the banks. `get_sync_status` (`sync:read`) does not consume the cooldown. The 08:00
  scheduler does not use the bucket. This dedicated store expires entries 24 hours after creation,
  rather than using the other limiters' one-hour idle eviction, so hourly calls cannot reset the
  daily budget. The store is in-memory, like the other limiters, so a restart
  clears it — it stops an agent loop, it is not a durable PSD2 counter. Trade Republic has no
  last-sync column, so its status line says `lastSync=none`.
- **American Express participates in the same pipeline.** Its existing scheduled refresh and
  broker-tool refresh are preserved through `MemberSyncService`, immediately after Fortuneo and
  before IBKR. Full sync includes it once; `get_sync_status` exposes its queue/failure state and
  last completion time. These calls only reuse the stored session, never initiate authentication.
- **SimpleFIN is a bank source.** `MemberSyncService` runs it right after IBKR. Full sync includes
  it once, and `trigger_bank_sync` runs it with Enable Banking. A revoked access (HTTP 403) is
  `NEEDS_REAUTH` in the trigger summary. `get_sync_status` reads the stored connection: `ERROR`
  is `FAILED` with `reauth=false`, because the stored status does not keep the reason.
- **The sync summary distinguishes failures from missing connections and expired credentials.**
  An unconnected Finary source is `SKIPPED_NOT_CONNECTED`, and an IBKR connection in `ERROR`
  is `FAILED` in `get_sync_status`. A crypto-exchange batch keeps running after a session fails,
  reports `FAILED`, and names the exchanges that failed. Each exchange runs in its own
  `REQUIRES_NEW` transaction; both batch entry points suspend any calling transaction so a
  failed exchange cannot roll back a successful neighbour. Transient connector errors do not ask
  the user to reconnect; `NEEDS_REAUTH` is reserved for authentication/session-expiry codes.
- **Inactive browser sessions preserve their stored failure reason.** BoursoBank, Bourse Direct,
  Amundi and Fortuneo report `NEEDS_REAUTH` for `SESSION_EXPIRED` (also `INVALID_CREDENTIALS` for
  BoursoBank), `FAILED` for another stored error, and `SKIPPED_NOT_CONNECTED` only when no error is
  recorded. Database failures and sync exceptions retain their throwable in server logs; raw
  database messages are never returned in their MCP summary.
- **Exception details stay server-side across all trigger paths.** The shared exception classifier,
  member-level fallback, DEGIRO, Revolut, Trade Republic, IBKR and crypto-exchange reporting return
  fixed failure labels rather than truncated exception text. DEGIRO's stored last error is also
  omitted from `get_sync_status`; its failure/reauthentication flag remains visible.
  The cause is not lost: Revolut and Trade Republic log a reporting-path `SyncException` at WARN
  with its code and stack trace before returning the fixed label, like the queued brokers do.
- **Status reads do not share a transaction.** `SyncStatusService.describe` suspends a caller's
  transaction with `NOT_SUPPORTED`. Each reader owns its transaction, so one caught database
  failure cannot mark the whole report rollback-only or roll back an unrelated caller's writes.
- **Sync triggers are still synchronous.** The cooldown token is consumed before the connectors
  run. A client timeout does not prove that the sync stopped, and a retry can be blocked by the
  cooldown without receiving the original summary. A background run with a member-scoped run id
  and readable progress is a separate follow-up; this transport does not provide it yet.
- **`SyncTools` must be named `@Component("picsouSyncTools")`.** Spring AI's
  `McpServerAutoConfiguration` already defines a bean named `syncTools` (the SYNC server's tool-spec
  list). A `@Component` defaulting to `syncTools` collides with it and aborts the context
  (bean-definition overriding is disabled). The other tool components use unreserved default names.
- **Transport is HTTP+SSE, not Streamable HTTP.** The endpoints are `GET /mcp` (SSE stream) and
  `POST /mcp/message` (messages). Both are under `/mcp/**` so Property A's prefix check covers them.
  Do not "upgrade" to Streamable HTTP without bumping Spring AI past 1.0.x (which conflicts with the
  Boot 3.4.9 / Spring 6.2 pins — see `pom.xml`).
- **Property B lives in `UserContext`, not the filter.** The guard is the first statement in
  `getMemberIdOverride()`: if the current `Authentication` is an `AccessKeyAuthentication`, return
  `null` *before* `isAdmin()` can open the `?memberId=` override. Removing it would let an
  admin-owned key impersonate another member via a query param.
- **Scope enforcement is authority-based, not type-based** (`ScopeEnforcementAspect`). A cookie
  principal carries only `ROLE_*`, never a scope, so even if a tool were somehow reached over a
  cookie it would fail the scope check — defense in depth on top of Property A. This needs
  `spring-boot-starter-aop`; the **denial** test fails loudly if proxying is ever off.
- **`tools/list` advertises every tool name regardless of the key's scopes** (names + schemas only,
  no data). A scope a key lacks fails at call time with a clear "missing scope" error. Scope-filtered
  advertisement is a possible later enhancement.
- **`last_used_at` is throttled** (≤ once per key per ~5 min, via a `REQUIRES_NEW` write) and is
  best-effort — a failure there is logged and never breaks authentication.
- **`MCP_ENABLED`** (default `true`) gates the whole server. `SetupFilter` still blocks `/mcp` until
  first-launch setup completes.

## Tests

Backend (H2, `mvn test`):
- `mcp/AccessKeyServiceTest` — generate/format, SHA-256 hashing, validate (unknown / forged / revoked / expired / inactive owner), scope validation, member-scoped revoke.
- `mcp/AccessKeyUsageRecorderTest` — throttled `last_used_at` write.
- `mcp/ScopesTest`, `mcp/ScopeSetConverterTest` — vocabulary + converter round-trip.
- `mcp/ScopeEnforcementAspectTest` — **denial** when the required scope is absent.
- `mcp/tools/McpToolCatalogTest` — **curation guard**: pins the exact advertised tool set (no auth/credential/admin tool).
- `mcp/tools/{Account,Transaction,Goal,Insight,Sync}ToolsTest` — delegation + member-scoping per tool; account deletion reports the cleanup decision even when the earlier read-only preview has become stale.
- `mcp/tools/AnalysisToolsTest` — delegation per tool, another member's account surfaces the service's not-found, the scope each tool carries, and every tool rejected through the real `ScopeEnforcementAspect` proxy when its scope is missing.
- `mcp/tools/SyncToolsTest` — every trigger is a filter over `MemberSyncService`, failures stay visible, and the cooldown blocks a second call.
- `service/BrokerSyncReportingTest` — all four browser brokers preserve inactive-session errors,
  queue active sessions, retain exception-bearing logs and hide database details.
- `service/SyncStatusTransactionIsolationTest` — real Spring transaction proxies and H2 reproduce
  a rollback-only reader while preserving the report and an outer transaction's committed writes.
- `config/RateLimitConfigTest` — the member-sync bucket survives hourly calls, denies a fifth sync
  before 24 hours, expires at the daily boundary, and leaves ordinary one-hour limiter stores unchanged.
- `service/sync/SourceSyncResultTest` — exact authentication signals versus transient and unknown
  errors for the reporting connectors; IBKR invalid-query errors are not token expiry.
- `service/CryptoExchangeSyncServiceTest` — mixed-success batches keep attempting member-scoped
  sessions, name failed exchanges, and never expose adapter exception text in that summary.
- `service/CryptoExchangeSyncTransactionIsolationTest` — real PostgreSQL constraint failure in
  one exchange does not undo another exchange's committed positions, including the legacy batch
  entry point invoked inside a caller's transaction.
- `service/IbkrSyncServiceTest` — unexpected reporting errors retain an exception-bearing server log.
- `service/TradeRepublicSyncServiceTest`, `service/sync/MemberSyncServiceTest` — session lookup,
  Finary failures and transaction-proxy exit errors retain their throwable and do not stop later sources.
- `service/SyncStatusServiceTest` — last sync, status and reauth flag per connection; a secret never appears in the text.
- `mcp/SyncToolsSseTest` — real SSE transport: `GET /mcp`, then `tools/call trigger_full_sync` with a `sync:trigger` key, and the per-source summary comes back. The sync itself is stubbed so the test does not call a bank.
- `service/AccountConnectionServiceTest` — last-account cleanup, connection preservation, and deletion results with labels captured before the connection is removed.
- `config/AccessKeyAuthFilterTest` — Property A (key on `/api/**` ⇒ not authenticated; on `/mcp` ⇒ authenticated), Property C (scope authorities only), throttle 429.
- `service/UserContextTest` — Property B (`AccessKeyAuthentication` ⇒ override returns `null`, even for an admin-owned key).
- `controller/AccessKeyControllerTest` — create/list/revoke, one-time secret, unknown-scope 400, member isolation, create throttle.
- `model/AccessKeyTest` — `isUsable` (revoked / expired / live).

Frontend (`bunx vitest run`):
- `frontend/src/features/accessKeys/scopes.test.ts` — scope grouping, i18n-key mapping, a **vocabulary guard** asserting the frontend list equals backend `Scopes.ALL`, and a label guard so no scope reaches the consent screen as a raw key.
- `frontend/src/features/accessKeys/status.test.ts` — `keyStatus` (revoked > expired > active, boundary at "now").

**Not covered by unit tests** (they run on a single thread, so they can't reproduce it): the
cross-thread `SecurityContext` propagation. `SyncToolsSseTest` covers that hop for
`trigger_full_sync` against the embedded server. The `/mcp` reverse-proxy route is still only
verified by driving the same exchange against the public origin.

## Links

- ADR: [Access-key auth + embedded MCP server](../decisions/2026-06-05-access-key-auth-and-embedded-mcp.md)
- Related: [Multi-account family system](./multi-account-family.md), [2FA (TOTP) and Remember Me](./mfa-and-remember-me.md), [CORS & cookie security](./security-cors-cookies.md)
