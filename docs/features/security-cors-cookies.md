# Feature: CORS & Cookie Security

> Last updated: 2026-07-20 (client IP trust — rate-limit keys use X-Real-IP, not the XFF-spoofable getRemoteAddr())

## Context

Picsou authenticates with HttpOnly JWT cookies. The CORS configuration controls which browser
origins may make credentialed requests to the API. Getting it wrong either blocks legitimate
clients or produces confusing silent failures. The standard deployment serves the SPA **and** the
API from the **same origin** (nginx serves the static build and reverse-proxies `/api`), so normal
login/setup traffic is same-origin and must never be subject to CORS at all.

## How it works

### CORS — dynamic, fail-closed

`DynamicCorsConfigurationSource` resolves the allowed origins **per request** (not at startup), so
the setup wizard's Security step takes effect without a container restart:

1. Look up `cors.allowed-origins` in the `app_setting` table (key
   `SetupService.KEY_CORS_ALLOWED_ORIGINS`). The wizard / admin page writes a CSV here.
2. If absent/empty, fall back to the `ALLOWED_ORIGINS` env var (`app.cors.allowed-origins`).
3. If still empty → return `null` ⇒ **fail closed** (no cross-origin allowed).

Origins are `setAllowedOrigins` (exact match, `allowCredentials: true`). **Wildcards are stripped**
(`sanitize()`): a `*` entry is incompatible with credentialed CORS, so an operator who sets `*`
fails closed rather than silently echoing every origin. Methods: `GET/POST/PUT/PATCH/DELETE/OPTIONS`.

An explicit `CorsFilter` bean (not the Security DSL) carries a `LoggingCorsProcessor` that logs the
origin on every rejection.

### Same-origin detection depends on the request scheme (the 403-behind-proxy trap)

Spring's `CorsUtils.isCorsRequest()` classifies same-vs-cross origin by comparing the `Origin`
header's **scheme + host + port** against `request.getScheme()/getServerName()/getServerPort()`.
All three equal ⇒ same-origin ⇒ CORS skipped entirely. Any differ ⇒ enforced as cross-origin.

Behind a TLS-terminating reverse proxy the browser sends `Origin: https://host`, but the
nginx→backend hop is plain HTTP. Without trusting forwarded headers the backend reports
`getScheme() == "http"`, the **scheme mismatches**, a genuinely same-origin request is treated as
cross-origin, and the fail-closed allow-list rejects it with **403**.

**Fix (1.0.2):** `server.forward-headers-strategy: framework` in `application.yml` activates
Spring's `ForwardedHeaderFilter`, which rewrites scheme/host/port from `X-Forwarded-*`. The backend
then sees `https`, recognizes the request as same-origin, and skips CORS. For this to work the
chain must carry the headers end to end:

- The **upstream TLS terminator** (Caddy, Traefik, Nginx Proxy Manager, Cloudflare Tunnel, …) must
  send `X-Forwarded-Proto: https`. All of the above do by default.
- Picsou's **own nginx** must not clobber it. It previously hardcoded `X-Forwarded-Proto $scheme`
  (always `http`, since that nginx listens on plain :8080). It now preserves the upstream value via
  a `map`, falling back to `$scheme` only when it is the edge. `X-Forwarded-Host`/`X-Forwarded-Port`
  are passed through **only when present** — never synthesized — so the backend derives the port
  from the scheme (443 for https) on the common standard-port deployment.

### Client IP trust (rate-limit keys)

`server.forward-headers-strategy: framework` fixes CORS (above) but has a side effect nothing
about CORS warns you of: Spring's `ForwardedHeaderFilter` also rewrites
`request.getRemoteAddr()` from the **leftmost** entry of `X-Forwarded-For`. Both nginx configs
set `X-Forwarded-For` via `proxy_add_x_forwarded_for`, which **appends** to whatever the client
already sent rather than replacing it — so a raw HTTP client can put anything it wants as the
first entry and `getRemoteAddr()` reflects that client-chosen value, not the real TCP peer. Every
per-IP Bucket4j limiter (login, MFA verify/enroll, setup wizard, BoursoBank/TR/IBKR/sync auth)
used to key its bucket on `getRemoteAddr()` — meaning a client could rotate the header on every
request and get a fresh bucket each time, nullifying the limiter entirely.

The trustworthy signal is `X-Real-IP`: both nginx configs unconditionally set it to
`$remote_addr` — the address nginx itself observed the connection from — on every proxied
request (`docker/nginx.conf`, `frontend/nginx.conf`), so a client cannot inject or override it
through our proxy the way it can with `X-Forwarded-For`.

`com.picsou.config.ClientIp#resolve(HttpServletRequest)` is the single helper every rate-limit
call site now uses instead of `getRemoteAddr()`:

1. Prefer `X-Real-IP`, but only if it parses as a plain IPv4/IPv6 literal — defense in depth
   against a directly-exposed backend (no nginx in front) where the header would otherwise be
   attacker-supplied. The literal check is pure regex, deliberately not
   `InetAddress.getByName()`, which falls through to a DNS/hosts lookup for anything that isn't
   already a literal — network I/O driven by an untrusted header value.
2. Otherwise fall back to `getRemoteAddr()`. Correct for local dev / unit tests: with no
   `X-Forwarded-*` headers on the request, `ForwardedHeaderFilter` is a no-op and that value is
   the genuine socket address.

This trust chain assumes the backend is reachable **only** through our own nginx (the same
assumption `forward-headers-strategy: framework` itself already makes — see Technical choices
below). It does not defend against an attacker who can reach the backend port directly *and*
supply their own `X-Forwarded-For`; that would need trusted-proxy IP ranges (`native` strategy,
rejected below) rather than a request-local header check.

Left out of scope, worth a follow-up: `SetupAuditService` and the admin-recovery audit trail
still log `getRemoteAddr()` rather than `ClientIp.resolve()` for their `ip=` fields — honest
audit logs would want the same fix, but audit logging is a different concern from rate-limit
key derivation and the two weren't bundled. If a second proxy layer is ever added in front of
the container (e.g. a reverse proxy in front of Docker), `X-Real-IP` must be set by the
**outermost** trusted hop — document that in the compose README when it happens.

`RateLimitConfig`'s bucket-store beans are also Caffeine-backed
(`expireAfterAccess(1h)` + `maximumSize(50_000)`) rather than plain `ConcurrentHashMap`s, so a
key an attacker fully controls (their own resolved IP) can no longer grow the map without bound.

### Cookies

Auth tokens are HttpOnly cookies written by `AuthCookieWriter`:
```
access_token=...; Max-Age=900; Path=/; HttpOnly; SameSite=Lax[; Secure]
```
- `SameSite=Lax` — `Strict` dropped cookies on Safari iOS on certain navigations.
- The `Secure` flag is driven by `SecureCookieProvider` (DB `secure-cookies` setting → `SECURE_COOKIES`
  env, default `true`). Set `false` only when serving over plain HTTP. The wizard auto-detects this
  from `location.protocol`.

### Key files

| File | Role |
|------|------|
| `backend/src/main/java/com/picsou/config/DynamicCorsConfigurationSource.java` | Per-request origin resolution (DB → env → fail closed), wildcard stripping |
| `backend/src/main/java/com/picsou/config/SecurityConfig.java` | `.cors()`, explicit `CorsFilter` bean, CSRF disabled, filter chain |
| `backend/src/main/java/com/picsou/config/LoggingCorsProcessor.java` | Logs origin on CORS rejection |
| `backend/src/main/java/com/picsou/config/AuthCookieWriter.java` / `SecureCookieProvider.java` | Cookie construction + `Secure` flag |
| `backend/src/main/resources/application.yml` | `server.forward-headers-strategy: framework`, `app.cors.allowed-origins`, `app.secure-cookies` |
| `docker/nginx.conf`, `frontend/nginx.conf` | Preserve upstream `X-Forwarded-Proto/Host/Port`; always set `X-Real-IP: $remote_addr` |
| `backend/src/main/java/com/picsou/config/ClientIp.java` | Trusted client IP for rate-limit keys (`X-Real-IP`, validated, else `getRemoteAddr()`) |
| `backend/src/main/java/com/picsou/config/RateLimitConfig.java` | Bucket4j bucket definitions; bounded Caffeine-backed bucket-store beans |
| `backend/src/main/java/com/picsou/controller/SetupController.java` (`/api/setup/security`) | Persists wizard's allowed origins |

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| `forward-headers-strategy: framework` | Backend reachable only via local nginx; no per-IP trusted-proxy config; no-op without headers | `native` (Tomcat RemoteIpValve — needs trusted-proxy IP ranges) |
| Preserve upstream `X-Forwarded-Proto`, never synthesize port | Proto alone lets the backend derive 443 from scheme; a synthesized `$server_port` (8080) re-breaks the match → 403 | Always set `X-Forwarded-Port $server_port` (wrong port → cross-origin) |
| Fail-closed empty default + wildcard stripping | `*` with credentials is unsafe and illegal in Spring | `ALLOWED_ORIGINS=*` default (previous behavior) |
| `setAllowedOrigins` (exact) | Credentialed CORS; origins come from the wizard | `setAllowedOriginPatterns("*")` |
| `SameSite=Lax` | Safari iOS compatibility | `SameSite=Strict` |
| Rate-limit keys read `X-Real-IP`, not `getRemoteAddr()` | `getRemoteAddr()` is XFF-tainted under `framework`; `X-Real-IP` is nginx-owned and not client-injectable | Switching to `native` + Tomcat `RemoteIpValve` (bigger blast radius; only justified if forwarded Host/Proto/Port ever stop being needed) |
| Bucket stores are Caffeine-backed (`expireAfterAccess(1h)`, `maximumSize(50_000)`) | Plain `ConcurrentHashMap` bucket maps never shrink; the key is attacker-controlled for the per-IP ones | Leaving them unbounded (memory-growth DoS) |

## Gotchas / Pitfalls

- **403 over HTTPS but works over HTTP = forwarded-headers problem, not a wrong origin.** The
  request is genuinely same-origin; the scheme just isn't reaching the backend. Check that the
  upstream proxy sends `X-Forwarded-Proto: https` and that nginx preserves it. Pre-seeding
  `ALLOWED_ORIGINS=https://host` only *masks* it (the exact origin then passes the cross-origin check).
- **Never set `X-Forwarded-Port` to nginx's `$server_port`.** On a standard `:443` deployment the
  browser's Origin has no explicit port (implies 443); forwarding port 8080 makes the backend
  perceive 8080 and mismatch → 403. Forward it only if the upstream actually sent one.
- **`docker-compose.override.yml` overrides `env_file`:** an `ALLOWED_ORIGINS` in its `environment:`
  block silently wins over `.env`. Check it first when CORS misbehaves in dev.
- **`Secure` flag on HTTP = redirect loop.** Cookies are dropped, `sessionStorage` stays set, the app
  loops dashboard ↔ `/login`. Fix: `SECURE_COOKIES=false`.
- **`PATCH` must be listed in allowed methods** — it is, but any new method needs adding.
- **`LoggingCorsProcessor` logs `getAllowedOriginPatterns()`** which is `null` here (we use
  `setAllowedOrigins`); the rejection log shows `patterns: null` — read the configured CSV instead.

## Tests

- `config/ForwardedHeadersCorsTest` — drives the real `ForwardedHeaderFilter` +
  `DynamicCorsConfigurationSource` + `LoggingCorsProcessor`: asserts an HTTPS same-origin request is
  **not** 403 with `X-Forwarded-Proto: https`, **is** 403 without it (the bug), and a genuine
  cross-origin request is still rejected.
- `config/DynamicCorsConfigurationSourceTest` — origin resolution (DB → env fallback, empty CSV, non-API routes).
- `config/SecureCookieProviderTest` — `Secure` flag resolution.
- `config/ClientIpTest` — `X-Real-IP` preferred over a spoofed `X-Forwarded-For`; falls back to
  `getRemoteAddr()` when `X-Real-IP` is absent, blank, multi-valued, oversized, or not a plain
  IPv4/IPv6 literal; accepts well-formed literals at their boundaries (`0.0.0.0`, `255.255.255.255`,
  `::1`, IPv4-mapped IPv6, ...).
- `config/RateLimitConfigTest` — a bucket-store bean evicts back down to `maximumSize` after being
  overfilled.
- `controller/AuthControllerTest#login_ratelimitKey_isXRealIp_notSpoofableXForwardedFor` — two
  calls with different spoofed `X-Forwarded-For` but the same `X-Real-IP` resolve to one bucket key.

## Links

- Related ADR: `docs/decisions/2026-01-01-single-user-jwt-cookies.md`,
  `docs/decisions/2026-04-23-first-launch-wizard.md`
- Feature: `docs/features/setup-wizard.md` (Security step writes the allowed origins)
