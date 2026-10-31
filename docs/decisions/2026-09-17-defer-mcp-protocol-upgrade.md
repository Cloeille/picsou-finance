# ADR: Defer the MCP protocol upgrade until the Spring Boot 4 migration

> Date: 2026-09-17
> Status: ✅ Active

## Context

Connecting the Picsou MCP connector from **claude.ai** completes OAuth (login + consent both work),
then claude.ai reports **"This connector has no tools available"** ([#53]).

The server log on the `initialize` handshake shows why:

```
Client initialize request - Protocol: 2025-11-25, Capabilities: ClientCapabilities[...]
Client requested unsupported protocol version: 2025-11-25, so the server will suggest the 2024-11-05 version instead
```

Nothing follows — no `tools/list`, no error. The client abandons the session rather than accepting
the server's downgrade suggestion, so the tool list is never fetched and the connector looks empty.

The backend embeds the MCP server via `spring-ai-starter-mcp-server-webmvc` governed by
`spring-ai.version=1.0.3` (`backend/pom.xml`), which bundles **MCP Java SDK 0.10.0** — a version that
only speaks protocol **`2024-11-05`**. The protocol version a client and server agree on is decided
entirely by that bundled SDK; it is not something the application code can negotiate or override.

This is the same dependency pin recorded in
[Access-key auth + embedded MCP server](./2026-06-05-access-key-auth-and-embedded-mcp.md), which
already flagged a related symptom of the same root cause: HTTP+SSE instead of Streamable HTTP.

Two scoping notes:

- The pin (`spring-ai.version=1.0.3`, Boot `3.4.9`) is identical on `main` and on `1.1.0`, so the
  protocol gap affects both.
- The OAuth2 authorization server that makes the claude.ai connector flow possible at all
  (`AuthorizationServerConfig`, `DynamicClientRegistrationController`) and the three model starters
  (`spring-ai-starter-model-ollama`, `-openai`, `-anthropic`) exist **only on `1.1.0`**, not on
  `main`. The migration described below therefore lands on the `1.1.0` line.

## Decision

**Do not upgrade the MCP protocol version now.** Treat it as a deliverable of a separately scoped
**Spring Boot 3 → 4 migration**, planned and tested on its own, rather than as a hotfix on [#53].

Until that migration ships, [#53] stays open as a known limitation, and clients that need Picsou's
MCP tools use one of the documented workarounds:

- call the **REST API** directly (the MCP tools are thin wrappers over the same member-scoped
  services), or
- put a **standalone MCP protocol bridge** in front of the server — a small external process that
  speaks `2025-11-25` to the client and `2024-11-05` to Picsou (the same shape as the `mcp-remote`
  bridge already documented for the HTTP+SSE transport).

## Alternatives considered

### Bump to an intermediate Spring AI 1.1.x release

- **Pros**: would stay on Spring Boot 3 — no framework migration, no Jackson change.
- **Cons**: **it does not close the gap.** Spring AI `1.1.8` (released 2026-06-12, the same day as
  `2.0.0`) still bundles MCP SDK `0.18.3` and targets Spring Boot `3.5.15`. So it would cost a Boot
  3.4 → 3.5 bump and a fight with the `tomcat` / `netty` / `spring-security` pins the pom carries for
  OSV-clean versions, and still not speak `2025-11-25`.

### Upgrade to Spring AI 2.0 now, as part of fixing #53

- **Pros**: fixes the protocol gap directly; also unlocks Streamable HTTP, closing the other
  trade-off from the 2026-06-05 ADR.
- **Cons**: Spring AI 2.0 **hard-requires Spring Boot 4.0/4.1 + Spring Framework 7 + Jackson 3** — it
  cannot load in a Boot 3.x context, so this is a full framework migration disguised as a dependency
  bump. It drags in, all at once: hand-rolled Spring Security wiring for the OAuth2 AS / MCP flow,
  a Jackson 2 → 3 change whose serialization behaviour affects the **whole** app (not just MCP), and
  three model starters moving to a new API surface. Shipping that under a bug-fix banner on an app
  holding real financial data is how you turn a cosmetic connector bug into a data incident.

### Patch or pin the MCP SDK independently of the Spring AI BOM

- **Pros**: in principle the narrowest possible change — swap only the transitive SDK jar.
- **Cons**: the starter's auto-configuration is written against the `0.10.0` API; a newer SDK changes
  types the Spring AI 1.0.3 autoconfig binds to, so this trades a clean unsupported-version log line
  for `NoSuchMethodError` at runtime. Unsupported by both projects and untestable against future
  patch releases.

### Rewrite the MCP server by hand against a current SDK

- **Pros**: full control of the protocol version, independent of Spring AI's release cadence.
- **Cons**: throws away the auto-configuration, the scope-enforcement AOP layer, and the security
  context propagation that took real work to get right (see
  [`lessons/thread-local-context-across-async-hop.md`](../lessons/thread-local-context-across-async-hop.md)).
  Maintaining a bespoke protocol implementation is a permanent cost for a self-hosted side feature.

## Reasoning

The gap is **not fixable at the application layer** — no configuration, adapter, or shim inside
Picsou can make the bundled SDK negotiate a protocol it does not implement. Every path to
`2025-11-25` runs through Spring AI 2.0, and Spring AI 2.0 runs through Spring Boot 4. So the only
real choice is *when* to do the Boot 4 migration, not *whether*.

Doing it now, under time pressure from a connector bug, is the worst version of that migration: the
blast radius (Spring Security OAuth2 wiring, Jackson serialization across every entity and DTO) is
far wider than the symptom, and the app manages real bank and portfolio data where a silent
serialization change is expensive to detect. Deferring costs little because the impact is bounded —
one client (claude.ai's hosted connector) cannot list tools, and both workarounds reach the same
member-scoped services with the same authorization model.

Recording the deferral as an ADR rather than leaving it in an issue thread matters because the
conclusion is **non-obvious and expensive to re-derive**: the natural next move — "just bump the
minor version" — was investigated and is a dead end (1.1.8 stays on SDK 0.18.3). Without this note
the next person spends the same afternoon re-reading Spring AI release blogs.

## Trade-offs accepted

- **[#53] stays open.** The claude.ai hosted connector remains unusable against Picsou until the
  migration ships; there is no partial fix to offer in the meantime.
- **Growing distance from the ecosystem.** Every Spring AI / MCP SDK release widens the gap, so the
  eventual migration gets larger, not smaller. This is a deliberate bet that a planned migration —
  even a bigger one — beats an unplanned one.
- **Workarounds shift work to the client side.** The REST API is a different integration surface
  (no tool discovery), and a protocol bridge is one more process for a self-hoster to run and keep
  patched — neither is a drop-in replacement for a native connector.
- **Streamable HTTP stays blocked too**, since it is gated on the same version wall — the
  2026-06-05 ADR's "revisit when the platform can move" is now explicitly scheduled behind Boot 4.

## Consequences

- **No code change.** `spring-ai.version` stays `1.0.3` and the parent stays Spring Boot `3.4.9`; the
  existing pom comment warning against a 1.1.x bump remains correct and should be left in place.
- **The Boot 4 migration is its own piece of work**, on the `1.1.0` line, and should be scoped to
  cover at minimum: Spring Boot 4.0/4.1 + Spring Framework 7, Jackson 2 → 3 across the whole app,
  the hand-rolled Spring Security / OAuth2 authorization-server config, and the three Spring AI
  model starters — with Spring Security / OAuth2 AS behaviour and Jackson 3 serialization verified
  against real data shapes before any production deploy.
- **When that migration lands**, this ADR should be marked superseded and the transport / protocol
  rows in [`features/mcp-server.md`](../features/mcp-server.md) and the 2026-06-05 ADR's trade-offs
  section updated together.
- **Until then**, [#53] is the tracking issue and carries the two workarounds.

[#53]: https://github.com/Cloeille/picsou-finance/issues/53
