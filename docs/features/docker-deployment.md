# Feature: Docker deployment

> Last updated: 2026-08-25 (Fortuneo internal sidecar)

## Context

Picsou deploys as six project images orchestrated by `docker/docker-compose.yml`:
- **`picsou:latest`** — main app: frontend (Nginx) + backend (Spring Boot), no Python. Published to GHCR as `ghcr.io/zoeille/picsou-finance`.
- **`docker-tr-auth`** — Trade Republic auth sidecar: headless Chromium + Python/uvicorn. Published to GHCR as `ghcr.io/zoeille/picsou-finance/tr-auth`.
- **`bourse-direct-auth`** — isolated Bourse Direct login/2FA sidecar, published to GHCR as `ghcr.io/zoeille/picsou-finance/bourse-direct-auth` and reachable only on the Compose network.
- **`amundi-auth`** — Amundi login/2FA sidecar, published to GHCR as `ghcr.io/zoeille/picsou-finance/amundi-auth`.
- **`bourso-auth`** — BoursoBank login/2FA sidecar, published to GHCR as `ghcr.io/zoeille/picsou-finance/bourso-auth`.
- **`fortuneo-auth`** — Fortuneo login/2FA, positions and transaction-history sidecar, published to GHCR as `ghcr.io/zoeille/picsou-finance/fortuneo-auth`.

PostgreSQL 16 and the optional Caddy TLS proxy use upstream images.

## How it works

### Main image — `docker/Dockerfile` (3-stage build, context: project root)

1. **`frontend-build`** — `oven/bun:alpine`. `bun install --frozen-lockfile`, `bun run build`. Output: `dist/`.
2. **`backend-build`** — `maven:3.9-eclipse-temurin-21-alpine`. `mvn dependency:go-offline` (layer cache), then `mvn package -DskipTests`. Output: fat JAR.
3. **Runtime** — `eclipse-temurin:21-jre-jammy`. Installs Nginx + supervisor + openssl. Copies `dist/` and JAR from build stages plus `docker/{nginx.conf,supervisord.conf,entrypoint.sh}`.

`supervisord` manages **two** processes in the main container:

| Process | Port | Role |
|---------|------|------|
| Nginx | 8080 (public) | Serves SPA, proxies `/api/*` and `/actuator` to backend |
| Java (Spring Boot) | 9090 (internal) | Backend API |

### tr-auth sidecar — `services/tr-auth/Dockerfile`

Based on `python:3.12-slim`. Installs only Chromium (not Firefox/WebKit) via `playwright install chromium`, with system deps pre-installed via apt. The sidecar exposes port 8001 and is reached by the backend via `TR_AUTH_URL=http://tr-auth:8001`.

### Bourse Direct sidecar — `services/bourse-direct-auth/Dockerfile`

Based on `python:3.12-slim-bookworm`, with Chromium only. It runs as a
dedicated non-root user and is reached by the backend at
`BOURSE_DIRECT_AUTH_URL=http://bourse-direct-auth:8001`. Compose does not
publish its port, so login, 2FA and portfolio endpoints remain internal.

### Fortuneo sidecar — `services/fortuneo-auth/Dockerfile`

Based on `python:3.12-slim-bookworm`, with Chromium only. It runs as a
dedicated non-root user and is reached by the backend at
`FORTUNEO_AUTH_URL=http://fortuneo-auth:8001`. Compose does not publish its
port. Its API is attached to `fortuneo-auth-net`, an internal network shared
only with the application, while `fortuneo-egress` gives the sidecar outbound
provider access without exposing that API to the other containers. Custom
remote sidecar URLs must use HTTPS; HTTP is accepted only for this isolated
service name or loopback development.

### Entrypoint (`docker/entrypoint.sh`)

On first boot, auto-generates three secrets into `/data/.secrets/` (mounted named volume):
- `JWT_SECRET` — 48-byte base64
- `CRYPTO_ENCRYPTION_KEY` — 32-byte base64
- `POSTGRES_PASSWORD` — 24-byte base64

On subsequent boots, re-reads from the files. If the env var is already set by the operator, it is respected and written to the file for consistency. Secrets are **never** regenerated once created — doing so would invalidate JWTs and corrupt all encrypted data in the DB.

### TLS — the optional `tls` compose profile

The app container serves **plain HTTP on 8080 only**; it never terminates TLS itself. HTTPS comes
from a terminator in front, and the stack ships an optional one: a `caddy:2-alpine` service named
`proxy`, gated behind `profiles: ["tls"]` so it is inert unless explicitly requested
(`docker compose --profile tls up -d`). It stays opt-in because publishing `:80`/`:443`
unconditionally would collide with an ingress proxy the operator already runs.

This is not cosmetic: **Enable Banking rejects `http://` callback URLs for PRODUCTION applications**,
and PRODUCTION is the only mode listing real banks — so bank sync is unreachable without TLS. See
[bank-sync.md](./bank-sync.md).

`docker/Caddyfile` is a single site block, `{$PICSOU_DOMAIN:picsou.localhost}` →
`reverse_proxy app:8080`. Caddy resolves the certificate strategy at **runtime** from the hostname
(not at config-adapt time — the adapted JSON shows no explicit issuer for either case):

| `PICSOU_DOMAIN` | Runtime issuer | Trust |
|---|---|---|
| Public FQDN | ACME (Let's Encrypt / ZeroSSL) | Public, zero manual steps |
| `.localhost` / `.local` / `.internal` / bare IP | `local` (Caddy's built-in CA) | Root must be installed per device, from `/data/caddy/pki/authorities/local/root.crt` in the `caddy_data` volume |

Caddy sets `X-Forwarded-Proto`/`-Host`/`-For` by default, which is exactly what `nginx.conf`'s
`map` block preserves and what `forward-headers-strategy: framework` needs — so no backend change
was required for the *proxying* itself. (HSTS did require one — see below.)

#### TLS is a first-boot decision, not an add-on

The setup wizard derives three values from the origin the operator happens to be visiting:
`EBStep2Credentials.tsx` proposes `${window.location.origin}/sync/callback`, CORS is auto-detected
from the browser origin, and `app.secure-cookies` from the protocol. All three are then written to
`app_setting`.

Because `EnableBankingConfigProvider.resolve()` (and the CORS/cookie equivalents) read the
**database first** and fall back to env only when the row is absent, those values become sticky the
moment setup completes:

| Order | Result |
|---|---|
| TLS on **before** the wizard | Everything derives to `https://…` on its own. Zero manual configuration |
| TLS on **after** the wizard | Three rows still hold the HTTP values, and `.env` cannot override them — they must be corrected via Admin → Integrations and Admin → Security |

Concretely, an install set up over HTTP leaves rows like
`enablebanking.redirect-uri = http://localhost:8080/sync/callback` and
`cors.allowed-origins = http://localhost:8080,…`, plus `app.secure-cookies = false`. The operator
symptom is a `REDIRECT_URI_NOT_ALLOWED` that "ignores" the corrected `ENABLEBANKING_REDIRECT_URI`
in `.env`. README step 3f documents the recovery path.

### HSTS is opt-in (`HSTS_ENABLED`, default off)

The header has **two independent emitters**, and both must be gated or the flag is a lie:

1. **nginx**, for SPA/static responses. `nginx.conf` includes
   `/etc/nginx/snippets/picsou-hsts.conf` in the server block and the static-asset `location`
   block; `entrypoint.sh` rewrites that snippet on every boot (header line when enabled, empty file
   otherwise). `Dockerfile` creates it empty at build time so `nginx -t` is valid without the
   entrypoint.
2. **Spring Security**, for `/api/*` and `/actuator`. `SecurityConfig` reads
   `app.hsts-enabled` (`${HSTS_ENABLED:false}`) and calls `hsts.disable()` when off.

**Accepted values** (both gates, identical by construction): `true`, `1`, `yes`, `on` —
case-insensitive, surrounding whitespace tolerated. Anything else disables the header, and
`entrypoint.sh` logs `WARNING: HSTS_ENABLED=... not understood` so a typo is visible rather than
silent. Interior whitespace is deliberately *not* stripped, so `t rue` warns instead of enabling.
`SecurityConfigHstsParsingTest` parses the token list out of `entrypoint.sh` and asserts it equals
`SecurityConfig.HSTS_TRUTHY`, so the Java and shell parsers cannot drift apart unnoticed.

Gating only nginx is **not sufficient** — a mistake worth not repeating. Spring Security's
`HstsHeaderWriter` fires on any request where `isSecure()` is true, and
`forward-headers-strategy: framework` makes that true from `X-Forwarded-Proto: https`, which is
exactly what the proxy sends. Since the SPA calls the API on load, an ungated backend pins the
policy on the very first page view no matter what nginx was told.

**Which emitter the browser actually sees, per deployment:**

| Deployment | Browser-visible emitter |
|---|---|
| All-in-one image | **nginx only.** Its `add_header` covers proxied responses too, and `proxy_hide_header Strict-Transport-Security` on `/api` and `/actuator` discards the backend's copy so it is never sent twice |
| Split stack / bare metal behind an operator's proxy | **Spring only.** `frontend/nginx.conf` sends no HSTS and hides nothing |

So in the all-in-one image the Spring gate is defence in depth rather than the operative control —
verifying it requires querying the backend directly on `:9090`, because nginx strips its header on
the way out. Both gates read the same `HSTS_ENABLED`, so the two never disagree in practice.

It was previously emitted unconditionally, which is a **lockout trap** once a locally-issued
certificate is in play: the browser stores the HSTS policy, then refuses to offer the "proceed
anyway" bypass for the untrusted cert — leaving no in-app recovery, only clearing HSTS state in
browser internals. Enable it only behind a publicly-trusted certificate.

Because `HSTS_ENABLED` only reaches the app through `env_file`, changing it may not recreate the
container. Use `up -d --force-recreate app` so the entrypoint re-runs and Spring re-reads the
property. **`restart app` does not work** — it reuses the existing container, whose environment was
fixed at create time, so the new value is never seen.

> **Behavior change:** deployments already behind a TLS proxy stop receiving HSTS until they set
> `HSTS_ENABLED=true`. Intentional — the header only helps when the certificate is already trusted,
> and defaulting it on is what created the trap.

### Key files

- `docker/Dockerfile` — main image, 3-stage build
- `docker/docker-compose.yml` — orchestration (app + proxy + connector sidecars + PostgreSQL + volumes)
- `docker/Caddyfile` — optional TLS terminator (profile `tls`)
- `services/tr-auth/Dockerfile` — tr-auth sidecar image
- `services/bourse-direct-auth/Dockerfile` — Bourse Direct sidecar image
- `services/fortuneo-auth/Dockerfile` — Fortuneo sidecar image
- `docker/nginx.conf` — Nginx reverse proxy config
- `docker/supervisord.conf` — supervisor (nginx + backend)
- `docker/entrypoint.sh` — secret bootstrap + HSTS snippet + exec supervisord

### Flow

```
docker compose -f docker/docker-compose.yml up
  → picsou:latest  (nginx:8080 → backend:9090)
  → docker-tr-auth (uvicorn:8001)
  → bourse-direct-auth (uvicorn:8001, internal only)
  → amundi-auth (uvicorn:8001, internal only)
  → bourso-auth (uvicorn:8001, internal only)
  → fortuneo-auth (uvicorn:8001, internal only)
  → postgres:16-alpine (:5432)
```

### Building a release archive

```bash
docker compose -f docker/docker-compose.yml build
docker save ghcr.io/zoeille/picsou-finance:latest \
  ghcr.io/zoeille/picsou-finance/tr-auth:latest \
  ghcr.io/zoeille/picsou-finance/bourse-direct-auth:latest \
  ghcr.io/zoeille/picsou-finance/amundi-auth:latest \
  ghcr.io/zoeille/picsou-finance/bourso-auth:latest \
  ghcr.io/zoeille/picsou-finance/fortuneo-auth:latest \
  | gzip > picsou-release.tar.gz
# On target machine:
docker load < picsou-release.tar.gz
```

### Pulling from GHCR

All six project images are published by `.github/workflows/docker.yml` on every push
(matrix build, one entry per image). To deploy from the registry instead of
building or loading a tar.gz:

```bash
# Replace 1.0.0 with the desired tag (nightly, branch name, or semver).
docker pull ghcr.io/zoeille/picsou-finance:1.0.0
docker pull ghcr.io/zoeille/picsou-finance/tr-auth:1.0.0
docker pull ghcr.io/zoeille/picsou-finance/bourse-direct-auth:1.0.0
docker pull ghcr.io/zoeille/picsou-finance/amundi-auth:1.0.0
docker pull ghcr.io/zoeille/picsou-finance/bourso-auth:1.0.0
docker pull ghcr.io/zoeille/picsou-finance/fortuneo-auth:1.0.0
```

Tag scheme:
- `main` push → `nightly`
- other branch push → branch name (e.g. `1.0.0`, `feature-foo`)
- version tag (`1.0.0` or `v1.0.0`) → `latest` + semver (`1.0.0`, `1.0`, `1`)

### Build version shown in the app

The published Docker workflow computes `APP_VERSION` from the Git ref and passes
it to the main image build. Version-tag builds display the normalized tag value
in Settings → About (for example both `1.0.13` and `v1.0.13` tags display
`1.0.13`), `main` builds display `nightly-<short-sha>`, and branch builds display
`<branch-name>-<short-sha>`.

Local source builds fall back to `frontend/package.json` for the frontend About
screen. Backend runtime metadata (`/actuator/info` and the embedded MCP server
version) uses `APP_VERSION` when it is set and otherwise falls back to `dev`.
A single `APP_VERSION` build arg feeds both the frontend and backend. For a
local release-style build, pass it explicitly:

```bash
docker build -f docker/Dockerfile --build-arg APP_VERSION=1.0.13 .
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Bun for frontend build | Project uses bun exclusively (`bun.lock`) | npm (would need a separate lock file) |
| Browser connectors as separate containers | Keeps the main image slim (JRE only, no Python/Playwright) and isolates unofficial browser protocols | Embed browser automation in the main image via supervisord |
| `python:3.12-slim` + Chromium only | Only Chromium is used (`p.chromium.launch()`); base image is ~5× smaller than `mcr.microsoft.com/playwright/python` which pre-installs all three browsers | `mcr.microsoft.com/playwright/python:v1.44.0-jammy` — included Firefox + WebKit unnecessarily (+~1.5GB uncompressed) |
| Auto-generated secrets on first boot | Zero-config install: user runs `docker compose up` with no pre-configuration | Require operator to set secrets manually before first boot |
| `.dockerignore` excludes `docker/Dockerfile` | Prevents the Dockerfile from being part of its own build context | No exclusion (harmless but unnecessary) |

## Gotchas / Pitfalls

- **`.dockerignore` must NOT exclude `docker/` entirely.** The runtime stage copies `docker/nginx.conf`, `docker/supervisord.conf`, and `docker/entrypoint.sh` from the build context.
- **Frontend lock file is `bun.lock`.** The Dockerfile must use `oven/bun` and `bun install --frozen-lockfile`. npm will fail.
- **`VITE_DEMO_MODE` build arg** defaults to `false`. Pass `--build-arg VITE_DEMO_MODE=true` for a demo build.
- **Nginx listens on 8080**, backend on 9090. The backend port is set via `SERVER_PORT` in `entrypoint.sh`, not `application.yml`.
- **`caddy_data` must persist.** It holds issued certificates *and the internal CA's root key*. Deleting the volume regenerates the root, invalidating the certificate every device was told to trust — everyone has to re-install it.
- **Closing the plain-HTTP `:8080` publish uses an overlay file**, `docker/docker-compose.no-http.yml` (`ports: !reset []`), not an env var. Compose cannot vary `ports` by profile, and two env-var forms were tried and rejected: `${VAR:-0.0.0.0}:8080:8080` binds IPv4 only and silently drops the `[::]` binding the unqualified short form gives (breaking IPv6 clients on *every* deployment), while the prefix form `${VAR:-}8080:8080` turns a value missing its trailing colon into `127.0.0.18080:8080` — an opaque "invalid published port" that aborts the whole stack, db and tr-auth included. Long-syntax `host_ip: ""` is rejected as an invalid IP. Requires Compose v2.24+ for `!reset`.
- **`picsou.localhost` (the `PICSOU_DOMAIN` default) only resolves on the Docker host itself** — browsers map `*.localhost` to loopback. Fine for verifying the profile works; set a LAN IP or a real domain for access from other devices. Enable Banking's portal may also refuse to register a `.localhost` redirect URL.
- **Enabling the `tls` profile against a pre-built GHCR image does not get the HSTS fix.** `HSTS_ENABLED` gating lives in `nginx.conf` + `entrypoint.sh`, so an image built before that change still sends HSTS unconditionally — which is precisely the lockout combination with an internal-CA certificate. Pull a current image, or rebuild with `--build`.
- **`TR_AUTH_URL` default in entrypoint is `http://127.0.0.1:8001`** (legacy single-container fallback). In docker-compose it is overridden to `http://tr-auth:8001` via the `environment:` block.
- **Bourse Direct 2FA state is process-local.** Keep `bourse-direct-auth` at one replica unless request affinity or shared pending state is added.
- **Secrets are never regenerated.** If `/data/.secrets/jwt_secret` exists, it is reused. Deleting it will log out all users and invalidate all encrypted secrets in the DB.
- **Spring Boot env var naming:** Properties under `app.*` require the `APP_` prefix. `app.finary.email` → `APP_FINARY_EMAIL`. Variables like `JWT_SECRET` work because `application.yml` maps them explicitly.
- **Stale env vars removed (2026-04-19):** `TR_PHONE_NUMBER`, `TR_PIN`, `FINARY_TOTP`, `POWENS_*`, `FINARY_EMAIL`, `FINARY_PASSWORD`. Do not re-add them.

## Tests

- No dedicated Docker integration tests. Build validation is manual: `docker build -f docker/Dockerfile .`.
- Backend unit tests run separately via `./mvnw test` (not in Docker build — skipped with `-DskipTests`).

## Links

- Related: [Trade Republic feature](./trade-republic.md) (tr-auth microservice)
