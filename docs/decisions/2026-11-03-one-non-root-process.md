# ADR: One non-root Java process in the app image

> Date: 2026-11-03
> Status: Active

## Context

The app image ran nginx and the JVM under supervisord, started by a shell entrypoint, all as root.
After [Spring serves the SPA](./2026-11-03-spring-serves-the-spa.md) and
`SecretBootstrap`, nginx only forwarded requests and the entrypoint only wrote its trusted-proxy
snippet. Issue #342 aims at one process with no root.

## Decision

- The runtime stage creates user and group `picsou` (1000:1000), owns `/data`, and runs
  `java -jar` as PID 1 in exec form under `USER 1000:1000`. Spring Boot listens on 8080.
- `TrustedProxyBootstrap` (an `EnvironmentPostProcessor`) sets
  `server.tomcat.remoteip.internal-proxies` from `PICSOU_PROXY_IP`: one exact IPv4 address, or an
  expression that matches nothing. Tomcat is the edge, so loopback is no longer trusted.
  This supersedes the nginx half of the [trusted proxy ADR](./2026-10-31-trusted-proxy-client-address.md).
- `ForwardedHostPortCustomizer` adds one Tomcat valve that restores what nginx derived: the public
  port from `X-Forwarded-Host`, else a numeric `X-Forwarded-Port`, for the trusted proxy only.
- Compose runs a one-shot `data-permissions` service (same image, root, `chown -R 1000:1000 /data`)
  that the app waits for; `deploy.sh` runs it before recreating the app. The Helm pod sets
  `fsGroup: 1000` with `OnRootMismatch`, and the migration Job runs as 1000.
- Helm probes use `/actuator/health/liveness` (startup, five minutes; liveness) and
  `/actuator/health` (readiness). The liveness group is open to anonymous callers, also during setup.

## Alternatives considered

### Keep nginx as a non-root sidecar process

- **Pros**: per-endpoint proxy timeouts stay.
- **Cons**: two processes and a supervisor remain; nothing nginx still did was needed.

### An init step in the image that chowns `/data` before dropping privileges

- **Pros**: no extra Compose service.
- **Cons**: needs root at start, a shell and an entrypoint, which is what this removes.

## Trade-offs accepted

- No proxy timeouts: Tomcat does not cut slow requests. The backend caps its own waits.
- Existing root-owned volumes need a one-time ownership change, done by the Compose service,
  Helm `fsGroup`, or by hand on a bind mount that ignores `chown`.
- Tomcat has no 1 MB body limit any more; Spring's 10 MB multipart limit applies.
