# ADR: Opt-in Streamable HTTP for modern MCP clients

> Date: 2026-10-31
> Status: Active
> Supersedes: [Deferred MCP protocol upgrade](./2026-09-17-defer-mcp-protocol-upgrade.md)

## Context

Issue [#53](https://github.com/Cloeille/picsou-finance/issues/53) reports successful OAuth followed
by an empty connector. The initialization log shows a downgrade from `2025-11-25` to `2024-11-05`.
Merged [PR #295](https://github.com/Cloeille/picsou-finance/pull/295) updates main to Boot 3.5.16
and Spring AI 1.1.8, but retains `protocol: SSE`.

The earlier decision attributed the limitation to the SDK release. The upstream tagged source
shows that transport selection determines the advertised versions:

- [Spring AI 1.1.8](https://github.com/spring-projects/spring-ai/blob/v1.1.8/pom.xml) manages MCP SDK 0.18.3.
- [WebMvcSseServerTransportProvider](https://github.com/modelcontextprotocol/java-sdk/blob/v0.18.3/mcp-spring/mcp-spring-webmvc/src/main/java/io/modelcontextprotocol/server/transport/WebMvcSseServerTransportProvider.java)
  advertises only `2024-11-05` in `protocolVersions()`.
- [WebMvcStreamableServerTransportProvider](https://github.com/modelcontextprotocol/java-sdk/blob/v0.18.3/mcp-spring/mcp-spring-webmvc/src/main/java/io/modelcontextprotocol/server/transport/WebMvcStreamableServerTransportProvider.java)
  includes `2025-11-25` in `protocolVersions()`.
- [McpAsyncServer](https://github.com/modelcontextprotocol/java-sdk/blob/v0.18.3/mcp-core/src/main/java/io/modelcontextprotocol/server/McpAsyncServer.java)
  reads the provider's versions and returns the client's requested version when it is supported.
- [Spring AI's Streamable WebMVC auto-configuration](https://github.com/spring-projects/spring-ai/blob/v1.1.8/auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-webmvc/src/main/java/org/springframework/ai/mcp/server/autoconfigure/McpServerStreamableHttpWebMvcAutoConfiguration.java)
  selects this provider through the existing starter.

## Decision

Expose `MCP_PROTOCOL`, defaulting to `SSE`. Administrators can select `STREAMABLE` and restart the
app to serve modern clients at `/mcp`. Bind its keep-alive to `MCP_KEEP_ALIVE_INTERVAL`, as for SSE.
Keep the starter's synchronous server, curated tools, scope enforcement, and authentication filters.

Transport selection applies to the whole instance. In Streamable mode the legacy `/mcp/message`
endpoint is absent, so administrators must update SSE-only clients. The
[feature guide](../features/mcp-server.md#use-protocol-2025-11-25-with-a-hosted-connector) documents
activation, client configuration, verification, and rollback.

## Alternatives considered

- **Switch all installations to Streamable HTTP by default.** Rejected because existing clients
  and the Settings snippet explicitly use SSE. An opt-in change preserves their configuration.
- **Run both transports simultaneously.** Deferred because the starter selects one transport;
  managing a second server and tool registration is unnecessary for this independently usable slice.
- **Upgrade to Boot 4 and Spring AI 2.** Unnecessary for this protocol version. The current SDK
  already implements it through a supported transport.
- **Override SSE's advertised version.** Rejected because advertising a newer version does not
  implement its transport contract.

## Verification and limits

`McpStreamableHttpTest` exercises the real HTTP endpoint with both an access key and an
OAuth-shaped MCP JWT. It checks exact version negotiation, tool discovery, member-scoped reads,
scope denial, API isolation, and session cleanup. The existing SSE tests retain the default mode.

These integration tests require PostgreSQL through Docker. They do not drive claude.ai's public
OAuth login and consent flow. A public-instance connector check remains necessary before claiming
hosted-client compatibility. A default transport migration and an adaptive Settings snippet remain
separate work.
