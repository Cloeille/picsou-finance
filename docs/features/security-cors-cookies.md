# Feature: CORS & Cookie Security

> Last updated: 2026-10-31 (explicit proxy trust and one container-resolved client address)

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

`server.forward-headers-strategy: native` enables Tomcat's `RemoteIpValve`. It accepts
`X-Forwarded-For/Proto/Host/Port` only from the explicit
`server.tomcat.remoteip.internal-proxies` expression. The default contains only IPv4/IPv6
loopback, the nginx-to-Java hop in the bundled image. The split Compose stack explicitly
configures its frontend nginx peer on a dedicated network. Other deployments must configure
the exact proxy peers; Tomcat's broad private-network defaults are not used.

Both nginx configs preserve the public scheme and host. `Host` uses `$http_host`, retaining
an explicit public port. The forwarded port comes from an explicit public host port, the
upstream `X-Forwarded-Port`, or the scheme's default. It never comes from nginx's container
listener. This matters with native forwarding: Tomcat removes a port in `X-Forwarded-Host`
and reads `X-Forwarded-Port` separately. Standard HTTPS, custom HTTPS ports and published HTTP
ports must all retain the browser's origin.

### Client IP trust (rate-limit keys and audit logs)

The address is resolved once through two explicit trust boundaries:

1. The bundled Caddy has a stable address on a dedicated Compose bridge. The application
   entrypoint writes nginx's `set_real_ip_from` snippet from `PICSOU_PROXY_IP`; without a
   configured upstream proxy, the snippet is empty. nginx uses `real_ip_header
   X-Forwarded-For` with recursive resolution, so forwarded client addresses are considered
   only when the socket peer is the configured proxy. Caddy retains its default handling
   of incoming forwarded headers.
2. Every nginx backend location **replaces** `X-Forwarded-For` and `X-Real-IP` with nginx's
   resolved `$remote_addr`. A request arriving directly at nginx retains its socket address
   even if it supplies its own client-address headers.
3. Tomcat accepts this normalized `X-Forwarded-For` only from the known nginx peer. An
   untrusted backend connection keeps its socket address, regardless of `Forwarded`,
   `X-Forwarded-For`, or `X-Real-IP` values.

`ClientIp.resolve(request)` returns `request.getRemoteAddr()` and never parses a header.
All existing address readers therefore share the container's resolution: login and MFA
buckets, other per-IP limiters, persistent-session metadata, setup/admin-recovery audit,
and export logs. A syntactically valid IP header alone establishes no trust.

The optional Caddy bridge has overridable subnet and proxy address settings; keep them in
step if the default subnet conflicts with an existing Docker network. Only the exact proxy
address is trusted, not its whole subnet or the shared sidecar network. See
[Docker deployment](./docker-deployment.md) for configuration and custom proxies.

`RateLimitConfig`'s bucket-store beans remain Caffeine-backed
(`expireAfterAccess(1h)` + `maximumSize(50_000)`). The five-attempt login quota and other
limits are unchanged.

### Cookies

Auth tokens are HttpOnly cookies written by `AuthCookieWriter`:
```
access_token=...; Max-Age=900; Path=/; HttpOnly; SameSite=Lax[; Secure]
```
- `SameSite=Lax` — `Strict` dropped cookies on Safari iOS on certain navigations.
  `Lax` is **not** a CSRF defence on its own; see the next section.
- The `Secure` flag is driven by `SecureCookieProvider` (DB `secure-cookies` setting → `SECURE_COOKIES`
  env, default `true`). Set `false` only when serving over plain HTTP. The wizard auto-detects this
  from `location.protocol`.

### CSRF — cross-site request check

`SameSite=Lax` blocks cookies on cross-**site** subrequests, but a page on a sibling subdomain
(another app on the same homelab domain) is same-site: the browser attaches our cookies to its
form POSTs. Many endpoints accept a body-less or multipart POST, which needs no CORS preflight,
and with no allowed origins configured `DynamicCorsConfigurationSource` returns `null`, so CORS
does not stop the request either.

The API chain therefore keeps Spring's `CsrfFilter` but without tokens:

- `CrossSiteCookieRequestMatcher` is the `requireCsrfProtectionMatcher`. It matches a request
  only when **all** hold:
  1. method is `POST`, `PUT`, `PATCH` or `DELETE`;
  2. an auth cookie is present (`access_token`, `refresh_token`, `mfa_challenge_token`,
     `persistent_token`). An `Authorization` header does not exempt the request: a browser
     attaches cached `Basic`/`Digest`/`Negotiate` credentials on its own (Picsou behind a
     basic-auth proxy), and `JwtAuthenticationFilter` reads the cookie before any Bearer token.
     Bearer clients (iOS app, MCP keys) send no auth cookie, so they are never matched;
  3. the request is cross-origin:
     - `Sec-Fetch-Site` present: anything but `same-origin` or `none` (so `same-site` and
       `cross-site` are rejected);
     - else `Origin`, or `Referer` as a last resort, compared with the request's own
       scheme/host/port. Behind nginx those come from `X-Forwarded-*` via
       `forward-headers-strategy: native` (see above), the same view CORS uses. `Origin: null`
       and malformed values count as foreign;
     - neither header: a non-browser client (curl, scripts, the iOS app), allowed;
  4. the `Origin` is **not** on the CORS allow-list (an allow-listed origin already has
     credentialed access, e.g. a split deployment with the SPA on another host).
- `NoStoredCsrfTokenRepository` never stores a token, so a matched request always fails the
  token comparison and gets **403** `application/problem+json`
  (`"detail":"Cross-site request rejected"`). Unmatched requests skip the check entirely: no
  session, no `XSRF-TOKEN` cookie, no header for the SPA to send.
- Only the `CsrfException` 403 gets that body (`DelegatingAccessDeniedHandler`); other 403s keep
  Spring's default handler.

**GET stays unchecked, so GET must not change state.** `Lax` cookies ride on top-level cross-site
navigations. The one offender was the bank OAuth callback: the bank redirects the browser to the
SPA's `/sync/callback`, which now `POST`s `{code, state}` to `/api/sync/complete` (it was a GET).

The OAuth2 authorization-server chain (`AuthorizationServerConfig`, `@Order(1)`, `/oauth2/**`
endpoints including the `/oauth2/authorize` cookie bridge) is a separate chain and is unchanged.

### Key files

| File | Role |
|------|------|
| `backend/src/main/java/com/picsou/config/DynamicCorsConfigurationSource.java` | Per-request origin resolution (DB → env → fail closed), wildcard stripping |
| `backend/src/main/java/com/picsou/config/SecurityConfig.java` | `.cors()`, explicit `CorsFilter` bean, token-less CSRF wiring + 403 body, filter chain |
| `backend/src/main/java/com/picsou/config/CrossSiteCookieRequestMatcher.java` | Decides which requests are cross-site cookie-authenticated writes |
| `backend/src/main/java/com/picsou/config/LoggingCorsProcessor.java` | Logs origin on CORS rejection |
| `backend/src/main/java/com/picsou/config/AuthCookieWriter.java` / `SecureCookieProvider.java` | Cookie construction + `Secure` flag |
| `backend/src/main/resources/application.yml` | `server.forward-headers-strategy: native`, explicit Tomcat proxy trust, CORS and cookie settings |
| `docker/nginx.conf`, `frontend/nginx.conf` | Resolve only known upstream proxies; normalize client headers and preserve the public origin |
| `backend/src/main/java/com/picsou/config/ClientIp.java` | Container-resolved client IP for rate-limit keys (`getRemoteAddr()`) |
| `backend/src/main/java/com/picsou/config/RateLimitConfig.java` | Bucket4j bucket definitions; bounded Caffeine-backed bucket-store beans |
| `backend/src/main/java/com/picsou/controller/SetupController.java` (`/api/setup/security`) | Persists wizard's allowed origins |

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| `forward-headers-strategy: native`, exact nginx peers | One trust policy for every address reader and the public origin | Unrestricted framework forwarding; trusting all private networks |
| Preserve the public scheme/host and derive the public port | Native forwarding reads host and port separately; the container listener is not the public port | `X-Forwarded-Port $server_port`; `Host $host` (drops the port) |
| Fail-closed empty default + wildcard stripping | `*` with credentials is unsafe and illegal in Spring | `ALLOWED_ORIGINS=*` default (previous behavior) |
| `setAllowedOrigins` (exact) | Credentialed CORS; origins come from the wizard | `setAllowedOriginPatterns("*")` |
| `SameSite=Lax` | Safari iOS compatibility | `SameSite=Strict` |
| CSRF by origin check (`Sec-Fetch-Site`, then `Origin`/`Referer`), no token | Stateless, nothing for the SPA or native app to carry; every supported browser sends Fetch Metadata or `Origin` on a POST | Synchronizer/double-submit token (`CookieCsrfTokenRepository` + `X-XSRF-TOKEN` in axios): more moving parts for the same coverage; `csrf.disable()` (left same-site CSRF open) |
| Reject `same-site`, not only `cross-site` | Sibling subdomains are the realistic attacker on a self-hosted domain | Trusting `same-site` |
| Allow requests with neither Fetch Metadata nor `Origin`/`Referer` | Only non-browser clients omit both; they cannot be driven by another site | Rejecting them (breaks scripts and health tooling) |
| ClientIp delegates to the native-resolved remote address | Audit logs and rate limiters use the same address; untrusted direct headers are ignored | Parsing `X-Real-IP` without checking the sender |
| Bucket stores are Caffeine-backed (`expireAfterAccess(1h)`, `maximumSize(50_000)`) | Plain `ConcurrentHashMap` bucket maps never shrink; the key is attacker-controlled for the per-IP ones | Leaving them unbounded (memory-growth DoS) |

## Gotchas / Pitfalls

- **403 over HTTPS but works over HTTP = forwarded-headers problem, not a wrong origin.** The
  request is genuinely same-origin; the scheme just isn't reaching the backend. Check that the
  upstream proxy sends `X-Forwarded-Proto: https` and that nginx preserves it. Pre-seeding
  `ALLOWED_ORIGINS=https://host` only *masks* it (the exact origin then passes the cross-origin check).
- **Never set `X-Forwarded-Port` to nginx's `$server_port`.** On a standard `:443` deployment the
  browser's Origin has no explicit port (implies 443); forwarding port 8080 makes the backend
  perceive 8080 and mismatch → 403. Derive it from the public host port or the upstream header, otherwise use the public scheme default.
- **`docker-compose.override.yml` overrides `env_file`:** an `ALLOWED_ORIGINS` in its `environment:`
  block silently wins over `.env`. Check it first when CORS misbehaves in dev.
- **`Secure` flag on HTTP = redirect loop.** Cookies are dropped, `sessionStorage` stays set, the app
  loops dashboard ↔ `/login`. Fix: `SECURE_COOKIES=false`.
- **403 "Cross-site request rejected" on a write from a browser that sends no `Sec-Fetch-Site`**
  (Safari before 16.4, Chrome before 76, Firefox before 90) means the `Origin` fallback saw a
  scheme/host/port mismatch. Same root cause as the CORS trap above: the forwarded headers
  don't describe what the browser sees. `RemoteIpValve` resets the port to the scheme's
  default whenever `X-Forwarded-Proto` is present, so a custom public port must arrive in
  `X-Forwarded-Port` (derived by nginx from the public host or the upstream header). An upstream proxy that rewrites `Host` without the
  port and sends no `X-Forwarded-Host` re-creates the bug; add the origin to the CORS allow-list
  or have the proxy forward `X-Forwarded-Host`.
- **Never treat an `Authorization` header as proof of a non-browser caller.** A basic-auth proxy
  in front of Picsou makes the browser attach `Authorization: Basic …` to every request, forged
  ones included.
- **`PATCH` must be listed in allowed methods** — it is, but any new method needs adding.
- **`LoggingCorsProcessor` logs `getAllowedOriginPatterns()`** which is `null` here (we use
  `setAllowedOrigins`); the rejection log shows `patterns: null` — read the configured CSV instead.

## Tests

- `config/csrfslice/CrossSiteRequestProtectionTest` — through the real `SecurityConfig`:
  cookie-authenticated POST with `Sec-Fetch-Site: cross-site`/`same-site` → 403 problem+json,
  `same-origin`/`none` → 200; without Fetch Metadata a foreign/`null`/other-port `Origin` or a
  foreign `Referer` → 403, a matching one (including HTTPS behind the proxy) → 200; no headers →
  200; with the MockMvc forwarded-origin simulator, `X-Forwarded-Host: nas:8080` + `Origin:
  http://nas:8080` → 200, a foreign host or another port → 403, default ports (80/443) normalised;
  cookie + `Authorization: Basic` or `Bearer` cross-site → 403; Bearer or MCP `psk_` without a
  cookie → 200; GET unaffected; SPA login/refresh pass; an allow-listed CORS origin passes, with
  or without Fetch Metadata; a rejection creates no session and sets no cookie.
- `controller/SyncControllerTest#complete_*` — `/api/sync/complete` is a POST with a JSON body;
  GET answers 405.
- `config/ForwardedHeadersCorsTest` — drives the real Tomcat `RemoteIpValve` +
  `DynamicCorsConfigurationSource` + `LoggingCorsProcessor`: asserts an HTTPS same-origin request is
  **not** 403 with `X-Forwarded-Proto: https`, **is** 403 without it (the bug), and a genuine
  cross-origin request is still rejected.
- `config/DynamicCorsConfigurationSourceTest` — origin resolution (DB → env fallback, empty CSV, non-API routes).
- `config/SecureCookieProviderTest` — `Secure` flag resolution.
- `config/ClientIpTest` — header values never override the container-resolved address,
  including valid IPv4/IPv6 literals, malformed, repeated and oversized headers.
- `config/TrustedProxyClientIpTest` — production Tomcat configuration, separate five-attempt
  login buckets for two proxied clients, a stable bucket despite changing headers on an
  untrusted connection, private/IPv6 untrusted peers, consistent audit addresses, and public
  origins including custom HTTP/HTTPS ports.
- `config/RateLimitConfigTest` — bucket stores evict to their configured maximum size.
- Docker proxy regressions exercise the nginx configuration and entrypoint trust settings.

## Links

- Related ADR: [Trusted proxy client addresses](../decisions/2026-10-31-trusted-proxy-client-address.md),
  `docs/decisions/2026-01-01-single-user-jwt-cookies.md`,
  `docs/decisions/2026-04-23-first-launch-wizard.md`
- Feature: `docs/features/setup-wizard.md` (Security step writes the allowed origins)
