# ADR: Drop oauth2:token scope from MCP allowlist

**Date:** 2026-07-06  
**Status:** Active

## Context

During implementation of OAuth2 + Budget MCP tools (22 Budget tools + 2 OAuth2 tools), a scope `oauth2:token` was included in the brief to support token minting via MCP. The brief assumed an "OAuth2 service/controller" that an MCP tool could delegate token issuance to, scoped to the calling access-key's member.

## Decision

Drop `oauth2:token` from `Scopes.ALL` and do not build a `request_oauth2_token` MCP tool.

The scope is **not implementable** in Picsou's current architecture and would create dead code:
- The authorization server (`AuthorizationServerConfig`) is Spring's stock `OAuth2AuthorizationServerConfigurer` serving one first-party **public** client (`picsou-ios`, `ClientAuthenticationMethod.NONE`, PKCE mandatory, no secret) for the native iOS app's custom-scheme redirect.
- Token endpoint accepts only an authorization code (+ PKCE verifier) from a browser-completed PKCE flow, or a refresh token from a prior grant.
- An MCP call is authenticated by an access-key (`psk_`, a separate principal type). The MCP caller has no auth code or verifier to provide, and no legitimate path to mint a per-user token.
- Even as a bare HTTP proxy (no service logic, just forwarding), the tool would have no real use: a client that already completed PKCE handshake calls `/oauth2/token` directly — it doesn't need an MCP wrapper and doesn't have an access-key at that point in the flow anyway.

Keeping the scope would be misleading — it signals a capability that doesn't exist.

## Alternatives considered

### A. Keep oauth2:token in the allowlist, build a proxy tool

**Pros:** Leaves room for a future use case if requirements emerge.

**Cons:** Dead code today. The tool would never succeed because there's no service to delegate to. Confuses users — a scope that exists but no tool honours it is worse than no scope at all.

### B. Defer the decision and keep it for now

**Pros:** Avoids committing to the removal.

**Cons:** Accumulates scope debt. Future maintainers wonder why the scope exists with no tool.

## Reasoning

**Clean allowlist principle:** `Scopes.ALL` is the security allowlist and the source of truth for what is possible. Every scope in it should correspond to at least one working `@Tool`. A scope with no tool is either:
1. Reserved for a future feature (should be explicit in a comment + ADR about why it's reserved)
2. Dead code (should be removed)

`oauth2:token` is category 2. The OAuth2 configuration itself is read-only (PKCE-only, no per-user override possible), so there's no **future** scenario in which an MCP tool mints tokens for users — that's a separate architecture change (new registered client + grant type).

If a future use case needs it (e.g. "claude.ai should be able to obtain its own OAuth2 access token"), that's a distinct design problem — not a wrapper around the native app's PKCE flow. It would require:
1. A new registered client (separate from `picsou-ios`)
2. A new grant type or flow that the Picsou MCP caller can legitimately participate in
3. A service layer to orchestrate it (doesn't exist today)

**Simplicity:** Dropping it now keeps the scope vocabulary clear (12 total, not 13) and prevents confusion.

## Trade-offs accepted

- **Foreclosed option:** If someone later proposes "MCP tool to mint OAuth2 tokens", we can't revive this scope string without a design conversation. The scope must be justified by a real use case and a real implementation path.
- **No reservation:** We're not reserving the space for this in the allowlist. A future team would start from scratch (new ADR, new scope, new tool).

This trade-off is acceptable because:
1. The current architecture supports neither the scope nor the use case.
2. The `oauth2:discover` and `oauth2:session-status` tools already cover OAuth2 introspection on the MCP side.
3. If the use case emerges, it will be obvious from requirements, and a proper design (with a service layer) will be justified by concrete need.

## Consequences

- `Scopes.ALL` now contains 12 scopes (11 Budget + 1 OAuth2:discover + 1 OAuth2:session-status), all of which are implemented.
- No `request_oauth2_token` tool exists or is advertised.
- `AccessKeyController` will not accept `oauth2:token` in scope requests (400 Bad Request: unknown scope).
- Future use cases for external OAuth2 token minting are explicitly deferred to a separate design.

## References

- Brief: `docs/briefs/oauth2-budget-mcp.md`
- Feature note: `docs/features/mcp-budget-oauth2.md`
- Related ADR: [2026-07-03 OAuth2 authorization server for native app](./2026-07-03-oauth2-authorization-server-for-native-app.md)
- Stop protocol: [Lesson: stop protocol in action](../lessons/stop-protocol-discovers-false-hypotheses.md)
