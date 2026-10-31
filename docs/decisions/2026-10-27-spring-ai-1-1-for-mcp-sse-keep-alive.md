# ADR: Spring AI 1.1 for the MCP SSE keep-alive

> Date: 2026-10-27
> Status: ✅ Active

## Context

MCP calls from Claude Code started timing out (`The operation timed out.`) after a few idle minutes,
until the client reconnected. Writes made during that window were sometimes lost silently.

The diagnosis held four probes idle for 7 minutes:

- curl kept the `GET /mcp` SSE stream open for 7 minutes, both through the reverse proxy and directly
  against the backend. The proxies and the app are not cutting it.
- Bun's `fetch`, Claude Code's runtime, aborted the stream with `TimeoutError: The operation timed out.`
  at +360 s. It drops a response body that receives no byte for about 6 minutes. The Python MCP
  SDK's SSE client (used by bridges such as mcpo) has the same kind of limit (`sse_read_timeout`, 300 s by default).
- Once the client has dropped the stream, the server still accepts `POST /mcp/message` for the old
  session and writes the reply on a stream nobody reads. The client therefore waits until its request
  timeout instead of getting an error.

The stream is silent between two replies because Spring AI 1.0.3 bundles MCP Java SDK 0.10.0, whose
`WebMvcSseServerTransportProvider` has no keep-alive. SDK 0.18.x, bundled with Spring AI 1.1.x, adds
`keepAliveInterval` on the SSE transport. Spring AI exposes it as `spring.ai.mcp.server.keep-alive-interval`.

The [2026-09-17 ADR](./2026-09-17-defer-mcp-protocol-upgrade.md) had rejected a 1.1.x bump, because
it does not close the protocol-version gap and costs a Boot 3.5 bump. That reasoning still holds for
the protocol gap. Here the bump buys something else: a server keep-alive that fixes a data-losing
timeout.

## Decision

- Bump Spring AI **1.0.3 → 1.1.8** (latest 1.1.x GA) and Spring Boot **3.4.9 → 3.5.16**.
  Spring AI 1.1.8 is built against Boot 3.5.15 / Spring Framework 6.2.19, and its README lists
  1.1.x for Boot 3.5.x, so Boot moves with it.
- Drop the `tomcat`, `netty`, `spring-security` and `testcontainers` version overrides. The Boot
  3.5.16 BOM manages each of them at or above the version the pom pinned (Tomcat 10.1.55,
  Netty 4.1.135, Spring Security 6.5.11, Testcontainers 1.21.4).
- Set `spring.ai.mcp.server.keep-alive-interval` to `60s` (overridable with `MCP_KEEP_ALIVE_INTERVAL`).
  The SDK then sends a JSON-RPC `ping` to every open session every minute, well under both clients'
  idle limits.
- Keep the HTTP+SSE transport, pinned explicitly with `protocol: SSE`, on `/mcp` and `/mcp/message`.

## Alternatives considered

### Hand-rolled keep-alive on SDK 0.10.0

- **Pros**: no dependency bump. `McpServerTransportProvider.notifyClients` is public in 0.10.0, so a
  scheduled task could push a harmless notification to every session.
- **Cons**: a notification is not a ping. Clients either ignore an unknown method or log a validation
  warning every interval (the Python SDK does), and a logging notification nobody asked for bends the
  protocol. It is also one more piece of custom code to delete once the SDK is upgraded.

### Client-side workaround

- **Pros**: no server change.
- **Cons**: Claude Code has no setting to lift Bun's body timeout. A local stdio bridge only moves the
  same idle limit into its own HTTP client.

### Switch to Streamable HTTP

- **Pros**: available in Spring AI 1.1 (`protocol: STREAMABLE`). The result comes back in the POST
  response, so no long-lived stream needs to stay alive for a reply to arrive.
- **Cons**: it changes the endpoint contract every existing client is configured for (Claude Code,
  `mcp-remote`, Python bridges), and the SSE path would still need the keep-alive for clients that stay on it.
  It remains a separate, deliberate change.

## Reasoning

The keep-alive is the protocol's own mechanism for this failure, and it is a single property once
the SDK has it. The Boot 3.4 → 3.5 bump is a minor-line move inside Spring Framework 6.2, unlike the
Boot 4 / Jackson 3 migration the 2026-09-17 ADR defers. The security pins it removes are all superseded
by newer managed versions. The only code change the bump forced was `OllamaOptions` → `OllamaChatOptions`.

## Trade-offs accepted

- **The protocol-version gap stays open.** SDK 0.18.x still does not negotiate the version the
  claude.ai hosted connector asks for. That remains deferred to the Boot 4 migration.
- **One extra request per minute per open session.** The client answers each ping with a
  `POST /mcp/message`, which counts against that access key's rate-limit bucket.
- **Boot 3.5 brings its own minor upgrades** (Flyway 11, Spring Security 6.5, Spring Authorization
  Server 1.5), covered only by the existing test suite.

## Consequences

- `backend/pom.xml`: Boot parent 3.5.16, `spring-ai.version` 1.1.8, four version overrides removed.
- `application.yml`: `protocol: SSE` and `keep-alive-interval: ${MCP_KEEP_ALIVE_INTERVAL:60s}`.
- `AiChatModelFactory`: `OllamaOptions` is now `OllamaChatOptions`.
- `McpSseKeepAliveTest` drives the real SSE transport and asserts that a server `ping` reaches an idle stream.
- The `syncTools` bean that forces `@Component("picsouSyncTools")` now comes from
  `ToolCallbackConverterAutoConfiguration`. The constraint itself is unchanged.
