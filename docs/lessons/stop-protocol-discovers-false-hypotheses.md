# Lesson: Stop protocol surfaces false brief hypotheses before cargo-cult code

> Brief assumptions ≠ reality. The stop protocol (diagnostic + STATUS.md + commit, don't guess) is better than inventing proxy code.

## Context

Brief: OAuth2 + Budget MCP tools, branch 1.1.0. The brief assumed an "OAuth2 service/controller" that an MCP tool could delegate token minting to. During implementation exploration, the builder discovered the actual `AuthorizationServerConfig` is Spring's stock starter with one public PKCE client for the native app — no per-user token issuance possible. The tool would have no real caller and no service to delegate to.

## What happened

**Symptom:** The builder's code review (reading `AuthorizationServerConfig`, `OAuth2Controller`, `OAuth2Service`) revealed there was nothing to call. A plausible proxy implementation would:
1. Accept an access-key-authenticated MCP call
2. Forward it to `/oauth2/token`
3. Return the response

But the /oauth2/token endpoint itself has nowhere to get the auth code or verifier from. An MCP caller has neither. Even a bare proxy would be dead code — no realistic caller would use it.

**Root cause:** The brief author assumed a service layer for per-user OAuth2 token issuance, but the actual architecture is "PKCE-only public client for native app". The assumption was reasonable on paper (typical auth server setup) but wrong for Picsou's actual configuration.

## What we did

The builder invoked the **stop protocol**: did NOT invent a proxy. Instead:

1. **Diagnostic:** Read `AuthorizationServerConfig`, confirmed there was no per-user service.
2. **STATUS.md:** Documented the root cause (Spring stock AS, one public client, no per-user override).
3. **Committed:** Left the branch with a clean `STATUS.md` and decision (drop the scope).
4. **Escalated:** Did not merge or push — let the orchestrator validate the decision and clean up.

The orchestrator then:

1. **Validated:** Confirmed the diagnostic was sound.
2. **Cleaned up:** Dropped `oauth2:token` from `Scopes.java` (removed dead constant, removed from `ALL` allowlist).
3. **Ratified:** Created an ADR for the decision (scope was removed because no service exists to honour it; if the use case emerges later, it's a separate design).

## How to apply

**When a brief assumes a service/layer that doesn't exist in the code:**

1. Do not build a proxy, mock, or placeholder. That's cargo-cult code — it will never execute and confuses future readers.
2. Invoke the stop protocol:
   - **Diagnostic:** Read the relevant source code and confirm the service is (or isn't) there.
   - **Document:** Write the finding in `STATUS.md` (what you expected, what you found, why).
   - **Commit:** Leave the branch clean with your diagnostic. Do not push/merge.
   - **Escalate:** Let the orchestrator decide: is this a planning miss, an architecture gap, or a future work item?

3. The orchestrator then:
   - **Validates** the diagnostic (asks clarifying questions if needed).
   - **Cleans up:** Removes dead code/scope, updates docs, ratifies the decision.
   - **Saves knowledge:** Creates an ADR if the decision is architectural.

This pattern prevents:
- **Silent bugs:** Code that compiles but never runs.
- **Confusion:** Scopes/methods that exist but don't work.
- **Wasted refactoring:** Cleaning up the dead code later, after someone else has built on top of it.

It also makes the brief-writing process better: future briefs learn that assumptions must be validated against the actual codebase before finalizing scope.

## References

- ADR: [Drop oauth2:token scope](../decisions/2026-07-06-drop-oauth2-token-scope.md)
- Feature: [Budget + OAuth2 tools in MCP](../features/mcp-budget-oauth2.md)
- Brief: `docs/briefs/oauth2-budget-mcp.md` (section "Out of scope" lists what was *not* built)
- Commit: `fix(mcp): drop unused oauth2:token scope` (cleaned up after the discovery)
