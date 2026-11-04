# ADR: Spring Boot serves the SPA and every response header

> Date: 2026-11-03
> Status: Active (the nginx forwarding step is superseded by [One non-root Java process](./2026-11-03-one-non-root-process.md))

## Context

The all-in-one image ran nginx in front of Spring Boot. nginx served the built React app (index.html
fallback, one-year caching of hashed assets, gzip) and added the security headers, including an
HSTS snippet that `entrypoint.sh` rewrote at boot. Spring Security emitted its own copies on API
paths, so `/api` and `/actuator` needed `proxy_hide_header Strict-Transport-Security` to avoid a
duplicate. `HSTS_ENABLED` was parsed twice, in Bash and in Java, and a test pinned the two token
lists together. The OAuth2 authorization-server chain used Spring's default headers and sent HSTS
over HTTPS whatever the flag said. Issue #342 aims at a single-process image (no nginx, supervisord
or Python); this is the first of three steps.

## Decision

- Spring Boot serves the SPA from `app.spa.location` (`SPA_LOCATION`, `/app/spa` in the image).
  `SpaWebConfig` registers `/**` with a resolver that returns an existing file, `index.html` for a
  client route, and nothing for a backend path or a missing file with a static extension
  (`SpaPaths.hasStaticExtension`).
- Caching follows what the name guarantees. Vite writes content-hashed files only under `/assets/`,
  so `SpaPaths.isHashedAsset` files get `max-age=31536000, public, immutable` and `Expires`. Every
  other file the handler serves, `index.html` and the client-route fallbacks included, gets
  `Cache-Control: no-cache` and is revalidated with its ETag. nginx cached every file with a static
  extension for a year, including the unhashed copies of `frontend/public` (`/favicon.svg`,
  `/theme-init.js`, `/providers/*.svg`), which a redeploy could therefore not replace.
- `SpaPaths.isBackend` is the one definition of "backend path": `/api`, `/oauth2`,
  `/.well-known/oauth-`, `/actuator`, `/mcp`, matched by plain prefix like the old nginx locations.
  The resource resolver, Spring Security (`permitAll` for GET/HEAD outside them, `no-store` only
  inside them) and `SetupFilter` (SPA stays reachable before setup) all use it.
- One header customizer in `SecurityConfig` is applied to both security filter chains: frame
  options, nosniff, referrer policy, CSP, Permissions-Policy and the HSTS gate.
- nginx stays for this step but only forwards: real-IP trust, `X-Forwarded-*` maps, per-endpoint
  timeouts, SSE buffering off for `/mcp`. It adds no header and compresses nothing.
- Compression moves to `server.compression` with nginx's types and threshold.
- The Bash HSTS parser and snippet are deleted. The "not understood" warning is logged by Java.

## Alternatives considered

### Keep nginx serving static files

- **Pros**: no Java change; nginx is fast at static files.
- **Cons**: blocks removing nginx; headers and the HSTS gate stay duplicated in two languages.

### Bundle the SPA into the JAR (`classpath:/static`)

- **Pros**: no extra directory, works outside Docker.
- **Cons**: couples the backend build to the frontend build and its tests; a directory keeps the
  existing Docker stages and lets tests use a temporary folder.

## Reasoning

Headers and caching belong with the code that knows the paths. Once Spring owns them, the second
HSTS emitter, the strip rules and the drift test have nothing left to protect.

## Trade-offs accepted

- Tomcat serves static files instead of nginx. For a self-hosted dashboard this is not measurable.
- Weak ETags: Tomcat skips compression for responses with a strong ETag. Every revalidated file
  (`index.html` and the unhashed public files) gets a weak digest of its content: it keeps its
  name across builds, so a build with the same length and modification second would otherwise
  collide. `frontend/public` is 46,808 bytes in 12 files, so reading them per conditional request
  is negligible. Hashed `/assets/` files keep nginx's timestamp-and-length value, so a bundle is
  never read to build a tag.
- Unhashed public files and `index.html` now cost a conditional request (a `304`) per load instead
  of being served from the browser cache for a year. A redeploy is visible immediately.
- A missing file with a static extension is a 404 even when the request would otherwise look like a
  route, as with nginx before.

## Consequences

- OAuth2 endpoints now follow the HSTS gate (they previously ignored it).
- `index.html`, `/favicon.svg` and the other non-`/assets/` files send `Cache-Control: no-cache`
  where nginx sent none (index) or a one-year `immutable` (favicon).
- nginx's own 502/504 pages (backend down or too slow) carry no security header until PR 3
  removes nginx. They hold no app content and no script, so CSP and frame options protect nothing
  there. HSTS is not lost either: the browser keeps the policy from earlier responses.
- The cookie authentication filters (`JwtAuthenticationFilter`, `PersistentTokenAuthFilter`)
  skip SPA paths. Static files carry no user data, so no user lookup runs per asset and no token
  rotation can put a `Set-Cookie` on a `public, immutable` response.
- `docker/tests/header_diff.py` compares two running deployments header by header.
- Follow-ups of #342: secret generation in Java, then removing nginx, supervisord and Python from the image.
