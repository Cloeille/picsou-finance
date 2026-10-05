# ADR: Caddy as an opt-in TLS terminator for the Docker stack

> Date: 2026-07-19
> Status: ✅ Active

## Context

Enable Banking rejects plain-HTTP callback URLs for **PRODUCTION** applications, and PRODUCTION is
the only mode that exposes real banks (SANDBOX lists fictitious ones — see
[bank-sync.md](../features/bank-sync.md)). The shipped Docker stack terminated no TLS: `nginx.conf`
listened on plain `:8080` and `docker/.env.example` actively suggested
`ENABLEBANKING_REDIRECT_URI=http://your-nas-ip:8080/sync/callback`. **No Docker deployment could
complete a bank connection**, and nothing in the app or docs explained why.

The existing guidance was "bring your own reverse proxy" (README, `security-cors-cookies.md`). That
works for operators who already run one, but leaves the common self-hosted case — a NAS on a LAN with
no domain — with no viable path at all.

A prior change (#39) added HTTPS to the Vite dev server via mkcert/basic-ssl, but it is gated on
`command === 'serve'` and therefore cannot affect Docker, where only `vite build` output is served.

## Decision

1. **Ship a Caddy TLS terminator** (`docker/Caddyfile`, `caddy:2-alpine`) as a `proxy` service in
   `docker/docker-compose.yml`.
2. **Gate it behind the `tls` compose profile** — inert unless `--profile tls` is passed.
3. **Select the certificate strategy from `PICSOU_DOMAIN` alone.** Caddy resolves this at runtime:
   a public FQDN gets ACME (Let's Encrypt); a LAN IP or `.local`/`.internal`/`.localhost` name gets
   Caddy's built-in CA. No issuer configuration is exposed.
4. **Make HSTS opt-in** (`HSTS_ENABLED`, default off) at *both* emitters: a snippet `entrypoint.sh`
   writes and `nginx.conf` includes (SPA/static), and `SecurityConfig`'s `app.hsts-enabled` gate
   (`/api`, `/actuator`). Gating only nginx leaves the policy pinned by the first API call.
5. **Rely on Caddy's default `X-Forwarded-For` handling — do not add a `header_up` directive.**
   Caddy's `trusted_proxies` is empty here, so it already overwrites any client-supplied
   `X-Forwarded-For` with the real peer address; verified by sending a spoofed
   `X-Forwarded-For: 10.0.0.99` through this config and observing only the peer address reach the
   upstream. Stating it explicitly is redundant and makes Caddy log
   `Unnecessary header_up X-Forwarded-For` on every start. This matters because
   `forward-headers-strategy: framework` makes `getRemoteAddr()` return `XFF[0]`, which
   `AuthController.getClientIp()` uses as the login rate-limit key.

## Alternatives considered

### Traefik instead of Caddy

- **Pros**: many homelab users already run it as their single ingress; best-in-class DNS-01 support
  for wildcard certificates without exposing port 80; label-driven config needs no extra file.
- **Cons**: **no equivalent to Caddy's `tls internal`.** With no matching certificate Traefik serves
  a throwaway "TRAEFIK DEFAULT CERT" issued by no installable CA — a permanent browser warning with
  no path to trust. The domainless LAN case, the one that is currently unsolvable, would require
  supplying mkcert certificates through a file provider or standing up Step-CA alongside it.

### Always-on proxy (no profile)

- **Pros**: HTTPS by default; nothing to opt into; no chance of an operator missing it.
- **Cons**: publishing `:80`/`:443` unconditionally collides with any ingress proxy the operator
  already runs, breaking existing working deployments on upgrade.

### TLS directly in the app image (nginx `listen 443 ssl`)

- **Pros**: no extra container; reuses the existing `entrypoint.sh` secret-bootstrap pattern.
- **Cons**: reimplements certificate lifecycle — issuance, renewal, ACME, a local CA — that Caddy
  provides for free. Self-signed certificates generated at boot have no installable root, so the
  domainless case stays unsolved.

### Documentation only (tell operators to add a proxy)

- **Pros**: zero new moving parts.
- **Cons**: the status quo that produced the bug. Leaves the no-domain case with no answer, and the
  requirement itself (Enable Banking needs HTTPS) was nowhere documented.

## Reasoning

Caddy is the only option that makes the **domainless LAN case** work with a single directive while
also handling the real-domain case automatically — and it does so without the operator choosing an
issuer, which is the configuration surface most likely to be got wrong.

Making it opt-in preserves the "bring your own proxy" path that already works; the two compose
seamlessly, and README 3e tells operators who prefer their own ingress to point it at the app
container's `:8080` and forward `X-Forwarded-Proto`.

The decisive detail for the no-domain case: **Enable Banking never fetches the callback URL.** The
redirect is browser-side only (`window.location.href = authLink`, then the SPA POSTs the `code`), so
a certificate from a local CA is fully sufficient provided the household's own browsers trust its
root. No public DNS or publicly-trusted certificate is required for bank sync to work.

HSTS had to become opt-in as a precondition. It was previously emitted unconditionally, which is a
one-way lockout once a locally-issued certificate is in play: the browser stores the policy, then
refuses the "proceed anyway" bypass, leaving no in-app recovery. Shipping `tls internal` on top of
the old configuration would have bricked exactly the users this change is meant to serve.

## Trade-offs accepted

- **Trust is not zero-touch without a domain.** The internal CA root must be installed once per
  device. This is inherent to certificate trust, not to Caddy; only a publicly-trusted certificate
  avoids it.
- **HSTS is off by default**, a mild regression for deployments already behind a publicly-trusted
  certificate — they must now set `HSTS_ENABLED=true`. Accepted because the header only helps when
  the certificate is already trusted, and defaulting it on is what created the lockout.
- **Closing the plain-HTTP port needs a second `-f` flag.** Compose cannot vary a service's `ports`
  by profile, so `docker/docker-compose.no-http.yml` (`ports: !reset []`) is an overlay the operator
  opts into, and must keep passing. Two env-var forms were tried first and both were worse: an
  explicit host IP binds one address family and silently breaks IPv6 for everyone, and a host-address
  *prefix* turns a missing trailing colon into an opaque "invalid published port" that aborts the
  whole stack. Requires Compose v2.24+.
- **A second network-facing container** to operate, and a `caddy_data` volume that must persist —
  losing it regenerates the CA root and invalidates every device's trust.

## Consequences

- New files: `docker/Caddyfile`. New volumes: `caddy_data`, `caddy_config`.
- New files: `docker/docker-compose.no-http.yml` (optional hardening overlay).
- New env vars: `PICSOU_DOMAIN`, `HSTS_ENABLED` (the latter documented in **both** `.env.example`
  files — the split stack reads it through the backend).
- `docker/nginx.conf` no longer hardcodes HSTS; it includes
  `/etc/nginx/snippets/picsou-hsts.conf`, written per-boot by `entrypoint.sh`. `frontend/nginx.conf`
  (legacy split stack) drops the header entirely — that container only ever serves plain HTTP, and
  the backend gate still gives that stack an `HSTS_ENABLED` opt-in via its `/api` responses.
- **TLS becomes a first-boot decision.** The setup wizard derives the callback URL, allowed origins,
  and cookie flag from the origin it is opened on and writes them to `app_setting`, which resolves
  **database-first**. Enabling TLS after setup means correcting three settings by hand
  (README 3f).
- **Backend change required for HSTS.** `SecurityConfig` now gates its `Strict-Transport-Security`
  writer on `app.hsts-enabled` (`${HSTS_ENABLED:false}`). Spring Security's `HstsHeaderWriter` fires
  whenever `isSecure()` is true, which `forward-headers-strategy: framework` derives from
  `X-Forwarded-Proto: https` — so gating nginx alone left every `/api/*` response still pinning the
  policy, defeating the whole change. Proxying itself needed no backend change: Caddy's default
  `X-Forwarded-*` headers already satisfy the `map` block in `nginx.conf`.
- **`HSTS_ENABLED` is parsed leniently on both sides** (`true|1|yes|on`, surrounding whitespace
  trimmed, case-insensitive). `SecurityConfig` binds it as a `String` rather than a primitive
  `boolean` on purpose: a primitive fails Spring field injection on any unconvertible value —
  including a bare `HSTS_ENABLED=` — which kills the backend under supervisord while nginx keeps
  serving, turning a typo into an app-wide 502.

## Links

- [docker-deployment.md](../features/docker-deployment.md) — profile mechanics, HSTS gating, gotchas
- [bank-sync.md](../features/bank-sync.md) — why HTTPS is mandatory for Enable Banking
- [security-cors-cookies.md](../features/security-cors-cookies.md) — `X-Forwarded-Proto` handling
