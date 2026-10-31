# ADR: Access-key authentication + embedded MCP server

> Date: 2026-06-05
> Status: ✅ Active (amended 2026-10-04)
> Amended by:
> - [2026-08-11 — Deleting an account removes the connection behind it](./2026-08-11-account-deletion-removes-its-connection.md)
> - [`docs/features/mcp-server.md`](../features/mcp-server.md)

> **What changed.** The write surface is no longer limited to manual records and
> refresh-existing-syncs. With `accounts:write`, `delete_account` also deletes synced accounts,
> and deleting the last account on a connection removes that connection: stored provider
> sessions/credentials, a wallet or exchange connection, an IBKR connection, or an Enable Banking
> requisition. Auth and credential flows stay out of the tool set. The rest of this ADR is kept
> as the original decision record.

## Context

We want an external **app** (an AI assistant / MCP client such as Claude Desktop) to read and
help manage a user's finances over the **Model Context Protocol (MCP)**. Two questions had to be
answered together, because the answer to each constrains the other:

1. **Where does the MCP server run** relative to the existing Spring backend?
2. **How does an app authenticate**, given that today the only principal is a JWT in an HttpOnly
   cookie — there is no API-key / bearer-token concept, and cookies are unsuitable for a headless
   app that must be scope-limited and individually revocable.

Whatever we chose had to preserve Picsou's existing **member-scoped** authorization model (every
service queries by `member_id`) and must not widen the blast radius of a leaked credential to the
whole REST surface (bank-auth, admin, MFA, GDPR export).

## Decision

**Embed the MCP server in the Spring backend** (Spring AI MCP, **HTTP+SSE** transport, `SYNC`
server) and authenticate apps with **scoped bearer access-keys** (`psk_…`) — a *second*
authentication principal alongside the JWT cookie.

- The MCP endpoints live under `/mcp/**` (`GET /mcp` SSE stream + `POST /mcp/message`). Tools are
  plain `@Tool` methods that resolve the key owner's member via `UserContext` and delegate to the
  **existing member-scoped services** in-process.
- A key is **hard-bound to one member's data**, created **self-service** by that member in Settings,
  and carries **granular `domain:action` scopes** (read + curated write). The raw secret is shown
  **once**; only its SHA-256 is stored.
- The exposed tool set is **curated and audited** (manual records + refresh-existing-syncs). Auth /
  credential flows, admin settings, member management, MFA, and data export are **never** tools.

Three structural security properties back this up:

- **A** — `AccessKeyAuthFilter.shouldNotFilter()` returns `true` for any non-`/mcp` path, so a key
  authenticates **only** `/mcp/**`, never `/api/**`.
- **B** — the principal is the owning `AppUser`, but the `Authentication` is a distinct
  `AccessKeyAuthentication`; `UserContext` refuses the admin `?memberId=` override for that type.
- **C** — a key's authorities are scope strings only, never `ROLE_ADMIN`.

## Alternatives considered

### A standalone MCP server (separate deployable) proxying the REST API

- **Pros**: clean process isolation; could be scaled/restarted independently; MCP concerns kept out
  of the core app.
- **Cons**: it would need its own copy of the authentication and member-scoping logic, or it would
  call the REST API as a privileged client and re-implement scoping at the proxy — duplicating the
  exact security model we already trust in-process. Extra network hop, extra secret to manage, more
  surface to harden. No upside for a single-instance, self-hosted app.

### Reuse the JWT cookie / OAuth2 for MCP auth

- **Pros**: no new credential type; nothing new to store.
- **Cons**: cookies are session credentials for a browser, not a headless app — they aren't
  scope-limited, can't be revoked per-app without disrupting the user's own login, and would grant
  the **full** `/api/**` surface (bank-auth, admin, export). An OAuth2 authorization-server buildout
  is heavy machinery for a self-hosted personal app and still wouldn't give us the curated MCP
  boundary for free.

### Expose the full REST surface as MCP tools (no curation)

- **Pros**: least implementation effort; every endpoint immediately available to the app.
- **Cons**: hands an AI app the ability to start bank-auth flows, change admin settings, manage
  members, disable MFA, or trigger a GDPR export — an uncontrolled blast radius for a leaked or
  misbehaving key. Unacceptable.

## Reasoning

Embedding wins because the backend **already** enforces member isolation in every service via
`findByIdAndMemberId(...)`. By making the key's principal the owning `AppUser`, those services work
**unchanged** and isolation is automatic — we add a thin auth filter and a curated tool layer, not a
parallel security model. A distinct `Authentication` *type* (rather than a flag) is what makes
Properties B and C structural: the type difference is the seam `UserContext` keys off to refuse
impersonation, and scope-only authorities keep role-gated paths unreachable. SHA-256 (not bcrypt) is
correct precisely because the secret is high-entropy — a fast hash keeps the per-request auth path
cheap while a unique `key_prefix` gives O(1) lookup before a constant-time compare.

## Trade-offs accepted

- **HTTP+SSE, not Streamable HTTP.** The pinned **Spring AI 1.0.3 / MCP SDK 0.10.0** (kept on 1.0.x
  because 1.1.x targets Boot 3.5 / Spring 6.3 and conflicts with our `tomcat`/`netty`/`security`
  pins) ships only the HTTP+SSE transport. Clients connect via the `mcp-remote` bridge. Revisit when
  the platform can move to a Spring AI line that offers Streamable HTTP.
- **`tools/list` advertises all tool names regardless of scope** (names + schemas only, never data).
  A missing-scope call fails clearly at invocation. Scope-filtered advertisement is a later option.
- **In-memory rate-limit buckets** (per key, per member) — correct for a single-instance self-host,
  not a multi-node cluster.
- **Login-less "managed" profiles cannot own keys in v1.** A key is bound to an `AppUser` (the
  creator); members who have never activated a login are out of scope for now.
- **A new, second credential type to operate** (issue, store, revoke, expire). Mitigated by reusing
  the audit/throttle patterns already in the codebase and by `@JsonIgnore` + `toString` exclusion on
  the hash so it never leaks into logs or responses.

## Consequences

- **Schema**: new `access_key` table (Flyway `V37`), `member_id`/`created_by` both
  `ON DELETE CASCADE`, unique `key_prefix`.
- **Auth chain**: `AccessKeyAuthFilter` registered as the 4th filter anchored to
  `UsernamePasswordAuthenticationFilter`; `requestMatchers("/mcp/**").authenticated()`.
- **`UserContext`**: a one-line Property B guard at the top of `getMemberIdOverride()`.
- **MCP layer**: one `ToolCallbackProvider` bean (`McpToolConfig`) wiring five `@Tool` components;
  `@RequiresScope` + an AOP aspect enforce scopes; `MissingScopeException` maps to a clean error.
- **Management**: `AccessKeyController` under `/api/access-keys` (cookie-auth, self-service, not
  admin-gated) with a per-member create throttle.
- **Frontend**: a new "Access keys & MCP" section in Settings (create, one-time secret reveal,
  connect-your-client snippet, revoke), no admin guard.
- **Ops**: `MCP_ENABLED` env gate (default `true`); `/mcp` must be served over HTTPS in prod (HSTS
  already set). Full design in [`docs/features/mcp-server.md`](../features/mcp-server.md).
