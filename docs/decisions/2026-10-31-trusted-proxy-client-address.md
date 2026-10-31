# ADR: Resolve client addresses only through explicit proxy peers

> Date: 2026-10-31
> Status: Active

## Context

The bundled TLS deployment adds Caddy before nginx. nginx's immediate peer therefore identifies
Caddy rather than the browser. The earlier `ClientIp` helper read `X-Real-IP`, while Spring's
framework forwarding filter independently rewrote `getRemoteAddr()` from incoming forwarded
headers. Rate limiters and audit readers needed one consistent client-address policy.

## Decision

- nginx uses its real-IP module with an explicit trusted upstream address. The optional Caddy
  service has a stable address on a dedicated bridge; the entrypoint generates its trust snippet
  from validated configuration. Without an upstream proxy configuration, no forwarded client
  address is trusted at nginx.
- Every nginx backend location replaces outgoing `X-Forwarded-For` and `X-Real-IP` with its
  resolved address. Incoming client-address chains are not appended.
- Use `server.forward-headers-strategy: native` and an explicit Tomcat internal-proxy expression.
  The bundled backend trusts only loopback. The split deployment trusts its frontend nginx's
  exact address on a dedicated ingress bridge; sidecars do not join that bridge.
- `ClientIp` delegates to `getRemoteAddr()`. Existing direct readers, including audit logs,
  receive the same container-resolved value. No application helper parses address headers.
- Forward public origin information too. nginx derives an explicit public port from the public
  host or upstream port header, never its container listener. Tomcat reads host and port
  separately, preserving same-origin checks on standard and custom HTTP/HTTPS ports.

## Alternatives considered

- **Keep unrestricted framework forwarding and validate header syntax:** an IP literal does not
  identify who supplied it, and a request helper cannot govern other readers of the remote address.
- **Trust private-network ranges:** this would include unrelated Docker peers and LAN clients.
- **Resolve the optional Caddy hostname when nginx starts:** nginx must also start when the TLS
  profile is off; resolving an absent service would prevent startup.
- **Custom forwarding parser/filter:** duplicates existing container and proxy functionality.

## Consequences

The login quota and other limits remain unchanged, with separate buckets per resolved client.
Untrusted direct connections keep their socket address regardless of client-address headers.
Custom deployments must identify their actual proxy peers explicitly. Stable small bridge
subnets can conflict with an existing network; operators must adjust the documented addresses
and trust settings together. The TLS bridge's subnet and proxy address are configurable.

## Validation

Synthetic tests cover separate proxied login buckets, untrusted direct headers, IPv4/IPv6
addresses, audit consistency, public origin ports, nginx forwarding and entrypoint configuration.
See [CORS & cookie security](../features/security-cors-cookies.md) and
[Docker deployment](../features/docker-deployment.md).

The trust behavior follows nginx's [real-IP module](https://nginx.org/en/docs/http/ngx_http_realip_module.html)
and Tomcat's [Remote IP valve](https://tomcat.apache.org/tomcat-10.1-doc/config/valve.html#Remote_IP_Valve).
