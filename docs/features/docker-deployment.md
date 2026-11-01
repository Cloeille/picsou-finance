# Feature: Docker deployment

> Last updated: 2026-10-31 (shared Camoufox base; explicit proxy trust for client addresses)

## Context

Picsou deploys as the app and thirteen authentication sidecars orchestrated by `docker/docker-compose.yml`:
- **`picsou:latest`** — main app: frontend (Nginx) + backend (Spring Boot), no Python. Published to GHCR as `ghcr.io/cloeille/picsou-finance`.
- **`docker-tr-auth`** — Trade Republic auth sidecar: headless Chromium + Python/uvicorn. Published to GHCR as `ghcr.io/cloeille/picsou-finance/tr-auth`.
- **`bourse-direct-auth`** — isolated Bourse Direct login/2FA sidecar, published to GHCR as `ghcr.io/cloeille/picsou-finance/bourse-direct-auth` and reachable only on the Compose network.
- **`amundi-auth`** — Amundi login/2FA sidecar, published to GHCR as `ghcr.io/cloeille/picsou-finance/amundi-auth`.
- **`bourso-auth`** — BoursoBank login/2FA sidecar, published to GHCR as `ghcr.io/cloeille/picsou-finance/bourso-auth`.
- **`caisse-epargne-auth`** — browserless Caisse d'Epargne read sidecar (session replay, no in-app login yet), published to GHCR as `ghcr.io/cloeille/picsou-finance/caisse-epargne-auth`.
- **`fortuneo-auth`** — Fortuneo login/2FA, positions and transaction-history sidecar, published to GHCR as `ghcr.io/cloeille/picsou-finance/fortuneo-auth`.
- **`revolut-auth`**, **`corum-auth`**, **`sofidy-auth`** — provider sidecars published under the same GHCR repository prefix.
- **`cmes-auth`** — browserless Crédit Mutuel / CIC Épargne Salariale login/2FA and plans sidecar, on the shared `sidecar-python` base, published as `ghcr.io/cloeille/picsou-finance/cmes-auth`. The backend reaches it at `CMES_AUTH_URL` (Compose sets `http://cmes-auth:8001`); see [`cmes-epargne-salariale.md`](cmes-epargne-salariale.md).
- **`cic-auth`** — browserless CIC online-banking sidecar on the shared `sidecar-python` base, published as `ghcr.io/cloeille/picsou-finance/cic-auth`; see [`cic-banking.md`](cic-banking.md).
- **`amex-auth`** — American Express login and account sidecar, built with Camoufox/Firefox, published as `ghcr.io/cloeille/picsou-finance/amex-auth`.
- **`degiro-auth`** — browserless DEGIRO authentication and portfolio sidecar, published as `ghcr.io/cloeille/picsou-finance/degiro-auth`.

All sidecars use the Docker workflow's shared `linux/amd64,linux/arm64` platforms
and tag strategy. The shared AMEX/Revolut base pins Camoufox `152.0.4-beta.30`
and selects the Linux archive matching the container architecture (`x86_64` or
`arm64`). Its `services/base/pin_camoufox.py` downloads the GeoIP database once
from the upstream release's direct `GeoLite2-City.mmdb` asset URL,
without querying the unauthenticated GitHub releases API, whose per-IP quota
shared CI runners exhaust. Download failures still fail the build; no token is
baked into the image and GeoIP support is retained.
A push to `1.1.0` publishes `:1.1.0`; `:latest` follows main
or version-tag pushes, not release-branch pushes. The deployment Compose file
offers both `image:` and `build:` for AMEX and DEGIRO, like the other sidecars.
For a pinned release deployment, override their image values with
`ghcr.io/cloeille/picsou-finance/amex-auth:1.1.0` and
`ghcr.io/cloeille/picsou-finance/degiro-auth:1.1.0` and pull before starting.

New GHCR packages may initially be private. After the first upstream build,
the repository owner must check both packages' visibility and make them public
if needed. Before that build, the workflow change alone does not prove public
availability. Verify from a Docker client with an empty authentication config:

```sh
DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_CONFIG
docker pull ghcr.io/cloeille/picsou-finance/amex-auth:1.1.0
docker pull ghcr.io/cloeille/picsou-finance/degiro-auth:1.1.0
rmdir "$DOCKER_CONFIG"
```

CI builds both Dockerfiles and runs credential-free tests inside their images,
with the server command overridden by Python unittest. No real provider login
or banking credentials are used.

PostgreSQL 16 and the optional Caddy TLS proxy use upstream images.

## How it works

### Main image — `docker/Dockerfile` (3-stage build, context: project root)

1. **`frontend-build`** — `oven/bun:alpine`. `bun install --frozen-lockfile`, `bun run build`. Output: `dist/`.
2. **`backend-build`** — `maven:3.9-eclipse-temurin-21-alpine`. `mvn dependency:go-offline` (layer cache), then `mvn package -DskipTests`. Output: fat JAR.
3. **Runtime** — `eclipse-temurin:21-jre-jammy`. Installs Nginx + supervisor + openssl. Copies `dist/` and JAR from build stages plus `docker/{nginx.conf,supervisord.conf,entrypoint.sh}`.

The two build stages run on `--platform=$BUILDPLATFORM`: `dist/` and the JAR are the same on
every architecture, so the multi-platform publish builds them once, natively, and only the
runtime stage runs per target (arm64 under QEMU). `APP_VERSION` and `VITE_*` are declared after
the dependency installs, so a new `APP_VERSION` reuses the bun, Maven and apt layers (an edit to
`frontend/package.json` still reinstalls the bun dependencies). The
Dockerfile therefore needs BuildKit (the default builder once `docker buildx` is installed);
the legacy builder rejects the empty `$BUILDPLATFORM`.

`supervisord` manages **two** processes in the main container:

| Process | Port | Role |
|---------|------|------|
| Nginx | 8080 (public) | Serves SPA, proxies `/api/*` and `/actuator` to backend |
| Java (Spring Boot) | 9090 (internal) | Backend API |

### Shared sidecar bases — `services/base/Dockerfile`

Every sidecar builds `FROM` one of three shared bases, built from one common pin
set (`services/base/requirements*.txt`): `python` (Python 3.12 slim bookworm,
fastapi, uvicorn, pydantic, httpx), `chromium` (the same plus Playwright 1.52 and
Chromium in `/opt/playwright-browsers`), and `camoufox` (the same Python base plus
Playwright 1.55.0, Camoufox 0.4.11, Firefox 152.0.4-beta.30 and Xvfb).
AMEX and Revolut use `camoufox`, including the target CPU's x86_64 or arm64 browser.
Their shared browser/addons live in `/opt/camoufox-cache/camoufox`, readable by
both service users; GeoIP is downloaded once into the Camoufox package directory.
A sidecar Dockerfile only adds its user and code.
The base build context excludes local bytecode and test helpers (`picsou_sidecar/contract.py`
and `euro_information/testing.py`). CI mounts the helpers read-only for image tests;
they are absent from published images. The shared packages (`picsou_sidecar`,
`euro_information`) live in the `python` base, so `chromium`, `camoufox` and every
sidecar built on them get them.

The publish workflow pushes `sidecar-python`, `sidecar-chromium` and
`sidecar-camoufox` first, then passes their immutable digests to the sidecar
builds. AMEX and Revolut therefore share exactly the same base layers. With
`docker compose up --build`, the three build-only base services (`scale: 0`,
never started) are built first; this needs Compose v2.33+
(`additional_contexts: service:`), tested on 5.6.0. `docker compose pull` needs
no particular version and also pulls the bases by name
(`ghcr.io/cloeille/picsou-finance/sidecar-*:latest`); their layers are already
shared with the sidecars. The new `sidecar-camoufox` package must be public on
GHCR after its first publication, as must the other bases. See
[ADR 2026-10-21](../decisions/2026-10-21-shared-sidecar-base-image.md).

The Camoufox entrypoint preserves AMEX's port 8001/display :98/screen 1366x768x24
and Revolut's port 8002/display :99/screen 1280x1024x24, with a 65-second keep-alive.
It clears stale display locks on restart and fails explicitly if Xvfb exits before
its display is ready or has not become ready within five seconds. Xvfb is not
supervised after that: if it exits later, the API keeps running and the next
headful login fails until the container restarts. Revolut profiles still persist on the
`revolut_profiles` volume. Its `revoauth` user stays at UID 999 so upgrades retain
read/write access to existing volumes and their mode-700 member directories.
The group is automatically allocated, and CI checks the built Revolut image's UID.
On both native ARM64 and AMD64, CI runs both derived images as non-root and
starts headless/headful Firefox against offline HTML without network access; the
AMD64 jobs also check `PICSOU_SIDECAR_BASE` in each built image;
login compatibility requires a separate release check.

A sidecar whose base is missing, or still on a pre-base image, is reported to the admin
by the [instance-status alert](./admin-instance-status.md).

### tr-auth sidecar — `services/tr-auth/Dockerfile`

Built on the shared `chromium` base (Chromium only, no Firefox/WebKit). The sidecar exposes port 8001 and is reached by the backend via `TR_AUTH_URL=http://tr-auth:8001`.

### Bourse Direct sidecar — `services/bourse-direct-auth/Dockerfile`

Built on the shared `chromium` base. It runs as a
dedicated non-root user and is reached by the backend at
`BOURSE_DIRECT_AUTH_URL=http://bourse-direct-auth:8001`. Compose does not
publish its port, so login, 2FA and portfolio endpoints remain internal.

### Fortuneo sidecar — `services/fortuneo-auth/Dockerfile`

Built on the shared `chromium` base. It runs as a
dedicated non-root user and is reached by the backend at
`FORTUNEO_AUTH_URL=http://fortuneo-auth:8001`. Compose does not publish its
port. Its API is attached to `fortuneo-auth-net`, an internal network shared
only with the application, while `fortuneo-egress` gives the sidecar outbound
provider access without exposing that API to the other containers. Custom
remote sidecar URLs must use HTTPS; HTTP is accepted only for a single-label
Compose service name or loopback development, the rule every sidecar adapter
applies through `SidecarBaseUrl`.

### Sidecar shared secret — `APP_SIDECAR_API_KEY`

The backend and every `*-auth` sidecar (Trade Republic, Revolut, BoursoBank,
Bourse Direct, Amundi, Fortuneo, DEGIRO, AMEX, CORUM, Sofidy, Crédit Mutuel / CIC
Épargne Salariale) share one secret.
Generate it with `openssl rand -base64 32`. Both Compose files forward it
explicitly to the app and to every sidecar service they define, and refuse to
start when it is missing or empty. The root `docker-compose.yml` defines all
twelve sidecars, as does `docker/docker-compose.yml`. Both explicitly set
`DEGIRO_AUTH_URL` on the backend rather than relying on its application default.
Both AMEX service definitions use `init: true` so an init process reaps orphaned
browser children during repeated logins. The entrypoint does not generate the
shared secret: the sidecars run in separate containers and
cannot read the app's `/data/.secrets/` volume.

- The backend sends it as `X-Picsou-Sidecar-Key` on every sidecar call, through
  `SidecarWebClientFactory`, and refuses to start when it is blank.
- Each sidecar refuses to start when it is missing or whitespace-only. Every
  route except the exact `/health` path, including `/docs` and
  `/openapi.json`, requires the header. A constant-time comparison
  (`secrets.compare_digest`) runs in an HTTP middleware, before routing, body
  parsing or any upstream work. That middleware lives once, in the shared
  `services/base/picsou_sidecar` package, and every sidecar's
  `test_sidecar_auth.py` runs the same contract (`picsou_sidecar.contract`)
  against its own app.
- A missing or wrong key returns HTTP 401 `{"detail": "UNAUTHORIZED"}` with
  `WWW-Authenticate: Picsou-Sidecar-Key`. The backend matches on that challenge
  and raises `SidecarAuthenticationException` (`SIDECAR_UNAUTHORIZED`), so a key
  mismatch never reads as a bank-side 401 such as `INVALID_CREDENTIALS` or
  `SESSION_EXPIRED`.
- The key is never logged. After rotating it, recreate the backend and every
  sidecar together.

### Entrypoint (`docker/entrypoint.sh`)

On first boot, auto-generates three secrets into `/data/.secrets/` (mounted named volume):
- `JWT_SECRET` — 48-byte base64
- `CRYPTO_ENCRYPTION_KEY` — 32-byte base64
- `POSTGRES_PASSWORD` — 24-byte base64

On subsequent boots, re-reads from the files. If the env var is already set by the operator, it is respected and written to the file for consistency. Secrets are **never** regenerated once created — doing so would invalidate JWTs and corrupt all encrypted data in the DB.

After resolving these secrets, the entrypoint exports `SPRING_DATASOURCE_PASSWORD`
from `POSTGRES_PASSWORD`, so Spring uses the supplied, persisted or generated
password rather than its literal `picsou` fallback. An explicit non-empty
`SPRING_DATASOURCE_PASSWORD` takes precedence; an empty value uses the resolved
PostgreSQL password.

This does not synchronize or rotate the database container's password. Set
`POSTGRES_PASSWORD` in `docker/.env` to the actual database password before
starting the stack. Compose explicitly passes `${POSTGRES_PASSWORD:-picsou}`
to both the app and PostgreSQL, so an unset or empty password retains their
shared `picsou` default rather than generating a different app-side secret.
Standalone image runs still generate a password when none is supplied; that
password must match the external database. Changing an environment variable
does not change the password of an already initialized database volume.

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
`reverse_proxy app-tls:8080`. Caddy resolves the certificate strategy at **runtime** from the hostname
(not at config-adapt time — the adapted JSON shows no explicit issuer for either case):

| `PICSOU_DOMAIN` | Runtime issuer | Trust |
|---|---|---|
| Public FQDN | ACME (Let's Encrypt / ZeroSSL) | Public, zero manual steps |
| `.localhost` / `.local` / `.internal` / bare IP | `local` (Caddy's built-in CA) | Root must be installed per device, from `/data/caddy/pki/authorities/local/root.crt` in the `caddy_data` volume |

Caddy sets `X-Forwarded-Proto`/`-Host`/`-For` by default. nginx resolves the client from the
known Caddy peer, normalizes the address header and forwards the public origin to Tomcat's native
forwarding support. See [trusted proxy client addresses](#trusted-proxy-client-addresses).

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
`forward-headers-strategy: native` makes that true from `X-Forwarded-Proto: https` sent by a
configured proxy. Since the SPA calls the API on load, an ungated backend pins the
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

### Trusted proxy client addresses

The bundled path is `client -> Caddy -> nginx -> Tomcat`. The optional `proxy` service has
one exact IPv4 address on the dedicated internal `tls-proxy` bridge, with its default network
retained for ACME egress. It reaches the app through the bridge-only `app-tls` alias. No sidecar
joins this bridge. nginx's real-IP module trusts only the address the entrypoint writes from
`PICSOU_PROXY_IP`, and replaces outgoing client headers with the resolved address. Tomcat uses
native forwarding and trusts only the local nginx loopback hop. Rate limiters, session metadata
and audit logs share the resolved remote address.

Defaults in `docker/docker-compose.yml` are `PICSOU_PROXY_SUBNET=172.30.253.0/29`,
`PICSOU_PROXY_IP=172.30.253.2` for Caddy, and `PICSOU_PROXY_IP_RANGE=172.30.253.4/30`
for Docker's automatic address allocation. The app has no fixed TLS address, so `deploy.sh`
can run one-off migration containers while the live app remains up. Caddy stays outside the
allocation range, preventing the app from taking its address when it starts first. Set all
three in `docker/.env` if this subnet overlaps an existing network. The range must be inside
the subnet and exclude Caddy's address and the gateway; Caddy needs a usable host address
inside the subnet. Allow at least two usable addresses in the range for the live app and
one migration container. Compose also passes `PICSOU_PROXY_IP` to the app as nginx's trusted
peer. The entrypoint rejects proxy values other than a plain IPv4 literal. Standalone image
runs with `PICSOU_PROXY_IP` unset trust no upstream client-address header.

The app always joins `tls-proxy`, even when the `tls` profile is disabled. Check for subnet
overlap before upgrading non-TLS installs too. If Docker reports `networks have overlapping IPv4`,
`docker compose up -d` and `deploy.sh` cannot proceed. Set all three variables above in your existing
`docker/.env` to non-conflicting values before upgrading.

For a custom proxy, set `PICSOU_PROXY_IP` to that proxy's exact address as seen by nginx and
recreate the app.
Do not use a shared Docker subnet or all private networks as a trust list.

When upgrading an existing bundled TLS deployment, use the
[release deployment script](./release-deployment.md). After checking the new app container,
it force-recreates `proxy` only if it was running when deployment started. Recreation uses
`--no-deps` and preserves Caddy's certificate volumes. This applies the new `tls-proxy` attachment
and loads the `app-tls` upstream. The script requires Compose v2.17+ for
`--wait --wait-timeout 60` and waits for Caddy's health check before the API readiness probe.
Proxy recreation or health-wait failure exits nonzero with a clear error, even when the API URL
points directly at the app's published HTTP port. Restarting Caddy alone
does not apply Compose network changes. Stopped or absent proxies remain untouched, even when
the `tls` profile is enabled. Migration or app cutover failures leave the proxy untouched.

The root split Compose stack uses a separate `picsou-ingress` bridge (`172.30.254.0/29`). nginx
is `172.30.254.2`, the backend is `172.30.254.3`, and the ingress-only `ingress-backend` alias
ensures the backend connection uses that path. Only the frontend's exact address is in
`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`. If the subnet conflicts, change its subnet, both
addresses and that regex together in a Compose override. For an additional proxy before the
split frontend, mount a real-IP snippet using `frontend/picsou-realip.conf` as the template;
the default snippet trusts no upstream peer. Other backend deployments must set
`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` to the exact nginx peer expression.

Both nginx configurations retain the public scheme/host and derive `X-Forwarded-Port` from
an explicit public host port or an upstream port header, otherwise Tomcat uses the scheme
default. This preserves same-origin requests on published HTTP and custom HTTPS ports.
See [CORS & cookie security](./security-cors-cookies.md) and the
[proxy address ADR](../decisions/2026-10-31-trusted-proxy-client-address.md).

`docker/tests/test_proxy_client_ip.py` starts fresh isolated Compose projects with real Caddy
and a lightweight app stand-in. It preserves the shipped network attachments and startup
dependency, checks default and custom addresses, waits for Caddy's health check, and probes
the `app-tls` upstream. It also runs a one-off app container alongside the live app to cover
repeat deployments. The nginx fixtures create explicit randomized subnets for static test
addresses, retrying subnet overlaps for compatibility with Docker versions used in CI.
The Compose tests publish no host ports and remove their test containers, networks and volumes.
They assert the rendered default TLS addresses but reserve an unused private subnet for that
live case, so an existing install can keep the default bridge. Network allocation retries across
multiple private `/16`s on overlap; a timed-out Docker availability check skips rather than
failing test collection.
`docker/tests/test_deploy_integration.py` also upgrades a running Caddy from the old
default-network-only layout and verifies the new network, active upstream, preserved CA
and HTTPS API response through the deployment script.

### Key files

- `docker/Dockerfile` — main image, 3-stage build
- `docker/docker-compose.yml` — orchestration (app + proxy + connector sidecars + PostgreSQL + volumes)
- `docker/Caddyfile` — optional TLS terminator (profile `tls`)
- `services/tr-auth/Dockerfile` — tr-auth sidecar image
- `services/bourse-direct-auth/Dockerfile` — Bourse Direct sidecar image
- `services/fortuneo-auth/Dockerfile` — Fortuneo sidecar image
- `docker/nginx.conf` — Nginx reverse proxy config (`/api`, `/actuator`, `/mcp` → backend)
- `docker/supervisord.conf` — supervisor (nginx + backend)
- `docker/entrypoint.sh` — secret bootstrap + trusted-proxy/HSTS snippets + exec supervisord

### Flow

```
docker compose -f docker/docker-compose.yml up
  → picsou:latest  (nginx:8080 → backend:9090 for /api, /actuator, /mcp)
  → docker-tr-auth (uvicorn:8001)
  → revolut-auth (uvicorn:8002, internal only)
  → bourse-direct-auth (uvicorn:8001, internal only)
  → amundi-auth (uvicorn:8001, internal only)
  → bourso-auth (uvicorn:8001, internal only)
  → caisse-epargne-auth (uvicorn:8001, internal only)
  → fortuneo-auth (uvicorn:8001, internal only)
  → corum-auth (uvicorn:8001, internal only)
  → sofidy-auth (uvicorn:8001, internal only)
  → cmes-auth (uvicorn:8001, internal only)
  → amex-auth (uvicorn:8001, internal only)
  → degiro-auth (uvicorn:8001, internal only)
  → postgres:16-alpine (:5432)
```

`/mcp` is the embedded MCP HTTP+SSE transport (access-key auth). It must be proxied like
`/api`; if nginx only has SPA `try_files`, MCP clients get HTML instead of the protocol.

### Moving the stack to another machine

Git has the images’ source, not the live install. Postgres lives in the `pgdata` volume;
JWT / encryption / DB password live in `picsou_data` (`/data/.secrets/`). Copy **both**
or Finary and bank secrets in the database will not decrypt. With the `tls` profile, also copy
`caddy_data` and `docker/.env` (`PICSOU_DOMAIN`): the volume holds the internal CA's root key, and
a new one makes every device that trusted the old root reject the certificate. Commands, volume names, and
the “build this branch, do not `compose pull` GHCR `:latest`” warning are in the root
[README — Another machine](../../README.md#another-machine).

### Building a release archive

```bash
docker compose -f docker/docker-compose.yml build
docker save ghcr.io/cloeille/picsou-finance:latest \
  ghcr.io/cloeille/picsou-finance/tr-auth:latest \
  ghcr.io/cloeille/picsou-finance/revolut-auth:latest \
  ghcr.io/cloeille/picsou-finance/bourse-direct-auth:latest \
  ghcr.io/cloeille/picsou-finance/amundi-auth:latest \
  ghcr.io/cloeille/picsou-finance/bourso-auth:latest \
  ghcr.io/cloeille/picsou-finance/caisse-epargne-auth:latest \
  ghcr.io/cloeille/picsou-finance/fortuneo-auth:latest \
  ghcr.io/cloeille/picsou-finance/corum-auth:latest \
  ghcr.io/cloeille/picsou-finance/sofidy-auth:latest \
  ghcr.io/cloeille/picsou-finance/cmes-auth:latest \
  ghcr.io/cloeille/picsou-finance/amex-auth:latest \
  ghcr.io/cloeille/picsou-finance/degiro-auth:latest \
  | gzip > picsou-release.tar.gz
# On target machine:
docker load < picsou-release.tar.gz
```

### Pulling from GHCR

The app and all twelve sidecar images are published by `.github/workflows/docker.yml`
(matrix build, one entry per image) **only after CI is green**: backend tests, frontend
lint/typecheck/unit, Playwright e2e, every sidecar's image tests, the Compose sidecar build
and the workflow inventory, then the `ci-ok` gate. `ci.yml` calls that workflow on a successful `push` to `main` or a
version tag — not on pull requests, so a failing test never produces `nightly` / `latest` /
semver. `docker.yml` has no `workflow_dispatch`: a manual run would publish without that gate.

On a pull request, the `changes` job selects only the jobs the changed paths can affect
(`dorny/paths-filter`; any `.github/**` change selects all of them), and a new push cancels
the stale run. Pushes to `main`, tags and pull requests of 3000 files or more run every job.
`ci-ok` fails when a selected job did not succeed or when any job failed or was cancelled,
so it is the one check branch protection needs.

To deploy from the registry instead of building or loading a tar.gz:

```bash
# Replace 1.1.0 with the desired tag (nightly, latest, or semver).
docker pull ghcr.io/cloeille/picsou-finance:1.1.0
docker pull ghcr.io/cloeille/picsou-finance/tr-auth:1.1.0
docker pull ghcr.io/cloeille/picsou-finance/revolut-auth:1.1.0
docker pull ghcr.io/cloeille/picsou-finance/bourse-direct-auth:1.1.0
docker pull ghcr.io/cloeille/picsou-finance/amundi-auth:1.1.0
docker pull ghcr.io/cloeille/picsou-finance/bourso-auth:1.1.0
docker pull ghcr.io/cloeille/picsou-finance/caisse-epargne-auth:1.1.0
docker pull ghcr.io/cloeille/picsou-finance/fortuneo-auth:1.1.0
docker pull ghcr.io/cloeille/picsou-finance/corum-auth:1.1.0
docker pull ghcr.io/cloeille/picsou-finance/sofidy-auth:1.1.0
docker pull ghcr.io/cloeille/picsou-finance/cmes-auth:1.1.0
docker pull ghcr.io/cloeille/picsou-finance/amex-auth:1.1.0
docker pull ghcr.io/cloeille/picsou-finance/degiro-auth:1.1.0
```

Tag scheme:
- `main` push (CI green) → `nightly` + `latest`
- version tag (`1.0.0` or `v1.0.0`, CI green) → `latest` + semver (`1.0.0`, `1.0`, `1`)
- feature-branch pushes no longer publish an image (open a PR to run CI; build
  locally if you need a one-off image)

### Upgrading a 1.0.x database to 1.1.0

Merging main into 1.1.0 (1906d3a) moved migrations that the 1.0.11–1.0.18 releases had already
applied: V38 to V94, V54–V63 to V56–V65, V64 to V96. It also added comments to V51 and V55–V58,
which changes their checksum but not their SQL. The 1.1.0 image then stopped at boot with
`Migration checksum mismatch` on up to 13 versions
([#265](https://github.com/Cloeille/picsou-finance/issues/265)). Release 1.0.6 had applied today's
V36 `transaction security name` as V33 (1.0.7 renamed it, see the CHANGELOG entry for 1.0.7);
today's V33 is `budget foundation`, so a 1.0.6 database failed validation too. It is handled the
same way.

The same callback as below now handles these rows. It moves each one to its new version and, for
the five edited files, stores the new checksum. A row moves only when its old version,
description and checksum all match the 1.0.x file, and only when no other row already holds the
new version. Flyway then applies the 1.1.0 migrations that sat behind the old versions (V33–V35,
V38–V49, V54, V55, V72, V82–V85, V88–V93, V95, V97 and later) out of order. The INFO line lists
the moves:

```
Renumbered 13 legacy Flyway schema-history row(s) (issues #174, #265): V64 'backfill trade republic valuations' -> V96, ...
```

Do not run `flyway repair` on such a database: it would mark `budget categorization foundation`
(V38), `oauth2 authorization server` (V54), `account hidden` (V55) and others as applied
although they never ran. Take a backup and use the [safe release deployment](#safe-release-deployment).
If you started the 1.1.0 image with `SPRING_FLYWAY_ENABLED=false` to get past the error, follow
[the steps below](#upgrading-a-110-install-that-applied-the-old-v80v88-numbering) as well.

**Check for duplicate external account ids first.** Among the migrations a 1.0.x database has
never run, V47 `account external unique` deletes every active account that shares its
`external_account_id` and member with another active account, keeping the highest id, whatever
their provider and even for manual accounts. Their holdings, transactions and snapshots go with
them (`ON DELETE CASCADE`). V72 narrows the index to provider and non-manual accounts afterwards,
too late for the rows V47 deleted. 1.0.x never enforced this. The backend refuses to migrate
while V47 is pending and this query returns rows (`N group(s) of active accounts share an
external_account_id and member`), and nothing is changed. It checks again just before V47, with
writes to `account` blocked until V47 commits, because the running 1.0.x instance can still sync
during the migration. A duplicate that appears only then stops the upgrade at V47; the 1.1.0
migrations before V47 stay applied, and the next start resumes at V47. Check before upgrading:

```sql
SELECT member_id, external_account_id, count(*)
FROM account
WHERE deleted_at IS NULL AND external_account_id IS NOT NULL
GROUP BY member_id, external_account_id
HAVING count(*) > 1;
```

An empty result means V47 deletes nothing. Otherwise, in each group, soft-delete the accounts
you no longer need (`UPDATE account SET deleted_at = now() WHERE id = ...`), or give each account
you want to keep its own `external_account_id`. Renaming an account does not help: the query, and
V47, ignore the name. Re-run the query and upgrade only once it returns no rows. Rehearse on a
restored copy.

Covered: every 1.0.x release, including 1.0.6, whose V33 is moved to V36 (1.0.6 has no git tag;
1.0.2–1.0.5 applied no migration beyond 1.0.1's V32). `nightly` images of main between two tags
are matched row by row, so a build between 1.0.15 and 1.0.16 that applied only one of V51 and
V64 is handled too.
Builds of main from before cfc70930 shipped two V57 files and could not boot, so they left no
history to repair. A row whose checksum matches no released file is left alone, and Flyway still
rejects it.

### Upgrading a 1.1.0 install that applied the old V80–V88 numbering

Commit 2839c94 renumbered seven 1.1.0 migrations that some databases had already applied
(V80, V81 and V86–V88 became V93–V99). On those databases the 1.1.0 image failed at boot with a
Flyway validation error ([#174](https://github.com/Cloeille/picsou-finance/issues/174)), and some
operators started it with `SPRING_FLYWAY_ENABLED=false` as a workaround.

The backend now repairs the history itself. Before Flyway validates, `LegacyMigrationRenumberingCallback`
moves each legacy row to its new version, inside Flyway's transaction. A row is moved only when its
old version, description and checksum all match one of the renamed files, and only when the new
version is not in the history yet. On a fresh or already repaired database it does nothing. When it
moves rows it logs them once at INFO:

```
Renumbered 2 legacy Flyway schema-history row(s) (issues #174, #265): V80 'backfill tr crypto transaction tickers' -> V94, V81 'backfill trade republic valuations' -> V96
```

Flyway then applies the migrations the database has not run yet (out-of-order is enabled), without
re-running V93–V99.

**If you disabled Flyway as a workaround:**

1. **Remove `SPRING_FLYWAY_ENABLED=false`** from your environment. With
   Flyway off, no new migration ever runs, and Hibernate refuses to boot as soon as an entity needs
   a column a skipped migration adds (for example `bourso_session.encrypted_credentials`, V103).
2. Use the [safe release deployment](#safe-release-deployment) below. It takes a backup and
   rehearses the callback and migrations on a restored database before replacing the running app.
   The renumbering line above is expected. A failed rehearsal leaves the current app untouched.

The callback only renames history rows. It does not reconcile a schema that changed while Flyway
was off. If objects exist that the history does not record (a table or column created by hand, or
by an image that ran with Flyway disabled), the first migration that creates them again fails with
`already exists` and the application does not start. Flyway commits each migration on its own, so
only the failing one is rolled back. The renumbered rows and every migration that succeeded earlier
in that start stay applied and recorded. For example, with a history that stops at the old V80/V81
rows, V80 `widen tr and degiro session tokens` is applied and committed before V82 fails. The
release CLI's error output names the failing script and version (the redacted diagnosis in the
release runbook); at application startup the Flyway log names it. Reconcile on a restored copy first, with a reviewed, incident-specific
plan: restore a known-good pre-drift database or prove that every migration effect matches before
repairing its history. Do not simply insert the highest version, run `baseline`/`repair`, or copy
the incident's ad-hoc SQL. Issue #195's hand-created tables were missing CHECK constraints and V100's
`CREDIT_CARD` enum value despite successful Hibernate validation. History cannot prove correctness
of unrecorded or falsely recorded manual changes. Do not keep `SPRING_FLYWAY_ENABLED=false` as a
lasting fix. The release workflow deliberately refuses to guess how to reconcile that drift.

### Safe release deployment

Follow [the production release runbook](./release-deployment.md). `docker/deploy.sh` is the
authoritative upgrade path: back up, restore and rehearse the candidate image's complete Flyway
chain on a temporary database, migrate and verify the live database, then replace only the app
with that same pinned image and require a real unauthenticated `/api/auth/me` HTTP 401.
Do not use a bare `compose up` for a production upgrade.

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

- `uv run --with pytest pytest docker/tests/test_proxy_client_ip.py -v` runs the real nginx
  configuration in Docker: exact trusted proxy resolution, independent forwarded clients,
  ignored direct client-address headers, public origin ports and Compose address consistency.
- `python3 -m unittest discover -s docker/tests -p 'test_entrypoint.py' -v` also checks empty
  standalone proxy trust, exact configured addresses and rejection of malformed configuration.

- `python3 -m pytest docker/tests/test_deploy.py -v` exercises deployment ordering and failure
  isolation against command doubles. CI runs it independently of the application tests.
- `ReleaseMigrationCliTest` checks strict migration history and idempotency on real PostgreSQL.
- CI builds the release image and runs `test_deploy_integration.py` against real PostgreSQL,
  the packaged JAR, the normal application entrypoint, and its Nginx API route. The build goes
  through Buildx with the GitHub Actions layer cache (its own `release-smoke` scope, plus the
  release build's `app` scope from `docker.yml`), so Maven Central and the npm registry are only
  contacted when `backend/pom.xml`, `frontend/package.json` or `frontend/bun.lock` changes, or
  when the cached layers are missing (evicted or cache unavailable). Cold builds from shared runners
  were rejected by Maven Central with `429 Too Many Requests`.
- Build validation: `docker build -f docker/Dockerfile .`.
- Backend unit tests run separately via `./mvnw test` (not in Docker build — skipped with `-DskipTests`).
- `LegacyMigrationRenumberingTest` (Testcontainers) upgrades databases built under the old V80–V88
  numbering and a fresh database to head, and checks that a second start changes nothing. It also
  builds 1.0.18, 1.0.15, 1.0.11 and 1.0.6 databases from those releases' files (the five
  comment-edited 1.0.18 files are kept verbatim under `src/test/resources/db/legacy-1.0.18`),
  upgrades them, and compares the resulting schema with a fresh database. It also checks that a
  1.0.18 database with duplicate external account ids stops before V47 without losing an account,
  also when a second connection commits the duplicate after the first check, and that account
  writes wait for V47. With a Flyway target below V47, or a baseline at or above V47 (`47.0`
  included), the check is skipped.

### Shared pending-login lifecycle

`services/base/picsou_sidecar.PendingStore` enforces each sidecar's TTL when a
completion takes an attempt. Expired states are disposed and returned as missing,
without waiting for the background sweep. Parking inserts immediately when there is
room, so retries do not wait for unrelated expired browsers to close. Only a full
store expires old entries and rechecks capacity under the lock, since another
request may fill a reclaimed slot during disposal. Routes use `put_or_raise()` so
a still-full store answers 503 with the provider's existing capacity detail;
callers dispose resources if initial parking
fails. Retrying routes transfer ownership to `repark()`, which disposes the state
if parking fails or is cancelled. A failed background sweep logs only the exception
type, keeping upstream URLs and exception messages out of logs, and continues
with the next sweep.
A retryable OTP whose state cannot be parked also answers 503 instead of promising
a retry for an attempt that no longer exists. Initiation also sweeps before
opening a new upstream client or browser.

## Links

- Related: [Trade Republic feature](./trade-republic.md) (tr-auth microservice)
