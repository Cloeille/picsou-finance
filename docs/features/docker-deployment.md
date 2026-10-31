# Feature: Docker deployment

> Last updated: 2026-10-31 (Crédit Mutuel / CIC Épargne Salariale sidecar)

## Context

Picsou deploys as the app and twelve authentication sidecars orchestrated by `docker/docker-compose.yml`:
- **`picsou:latest`** — main app: frontend (Nginx) + backend (Spring Boot), no Python. Published to GHCR as `ghcr.io/cloeille/picsou-finance`.
- **`docker-tr-auth`** — Trade Republic auth sidecar: headless Chromium + Python/uvicorn. Published to GHCR as `ghcr.io/cloeille/picsou-finance/tr-auth`.
- **`bourse-direct-auth`** — isolated Bourse Direct login/2FA sidecar, published to GHCR as `ghcr.io/cloeille/picsou-finance/bourse-direct-auth` and reachable only on the Compose network.
- **`amundi-auth`** — Amundi login/2FA sidecar, published to GHCR as `ghcr.io/cloeille/picsou-finance/amundi-auth`.
- **`bourso-auth`** — BoursoBank login/2FA sidecar, published to GHCR as `ghcr.io/cloeille/picsou-finance/bourso-auth`.
- **`caisse-epargne-auth`** — browserless Caisse d'Epargne read sidecar (session replay, no in-app login yet), published to GHCR as `ghcr.io/cloeille/picsou-finance/caisse-epargne-auth`.
- **`fortuneo-auth`** — Fortuneo login/2FA, positions and transaction-history sidecar, published to GHCR as `ghcr.io/cloeille/picsou-finance/fortuneo-auth`.
- **`revolut-auth`**, **`corum-auth`**, **`sofidy-auth`** — provider sidecars published under the same GHCR repository prefix.
- **`cmes-auth`** — browserless Crédit Mutuel / CIC Épargne Salariale login/2FA and plans sidecar, on the shared `sidecar-python` base, published as `ghcr.io/cloeille/picsou-finance/cmes-auth`. The backend reaches it at `CMES_AUTH_URL` (Compose sets `http://cmes-auth:8001`); see [`cmes-epargne-salariale.md`](cmes-epargne-salariale.md).
- **`amex-auth`** — American Express login and account sidecar, built with Camoufox/Firefox, published as `ghcr.io/cloeille/picsou-finance/amex-auth`.
- **`degiro-auth`** — browserless DEGIRO authentication and portfolio sidecar, published as `ghcr.io/cloeille/picsou-finance/degiro-auth`.

All sidecars use the Docker workflow's shared `linux/amd64,linux/arm64` platforms
and tag strategy. AMEX pins Camoufox `152.0.4-beta.30` and selects the Linux
archive matching the container architecture (`x86_64` or `arm64`).
AMEX and Revolut (each through its `pin_camoufox.py`) download the GeoIP
database from the upstream release's direct `GeoLite2-City.mmdb` asset URL,
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

`supervisord` manages **two** processes in the main container:

| Process | Port | Role |
|---------|------|------|
| Nginx | 8080 (public) | Serves SPA, proxies `/api/*` and `/actuator` to backend |
| Java (Spring Boot) | 9090 (internal) | Backend API |

### Shared sidecar bases — `services/base/Dockerfile`

Every sidecar except amex-auth and revolut-auth (Camoufox) builds `FROM` one of
two shared bases, built from one pin set (`services/base/requirements*.txt`):
`python` (Python 3.12 slim bookworm, fastapi, uvicorn, pydantic, httpx) and
`chromium` (the same plus Playwright 1.52 and Chromium in
`/opt/playwright-browsers`). A sidecar Dockerfile only adds its user and code.
The publish workflow pushes `sidecar-python` and `sidecar-chromium` first and
builds every sidecar from them by digest, so a pull stores Chromium once. With
`docker compose up --build`, the build-only services `sidecar-python` and
`sidecar-chromium` (`scale: 0`, never started) are built first; this
needs Compose v2.33+ (`additional_contexts: service:`), tested on 5.6.0.
`docker compose pull` needs no particular version. It also pulls the two bases
by name (`ghcr.io/cloeille/picsou-finance/sidecar-*:latest`); their layers are
the ones the published sidecars are built from, so nothing is downloaded twice. See
[ADR 2026-10-21](../decisions/2026-10-21-shared-sidecar-base-image.md).
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
  parsing or any upstream work.
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
- `docker/nginx.conf` — Nginx reverse proxy config (`/api`, `/actuator`, `/mcp` → backend)
- `docker/supervisord.conf` — supervisor (nginx + backend)
- `docker/entrypoint.sh` — secret bootstrap + HSTS snippet + exec supervisord

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
and the workflow inventory. `ci.yml` calls that workflow on a successful `push` to `main` or a
version tag — not on pull requests, so a failing test never produces `nightly` / `latest` /
semver. `docker.yml` has no `workflow_dispatch`: a manual run would publish without that gate.

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

- `python3 -m pytest docker/tests/test_deploy.py -v` exercises deployment ordering and failure
  isolation against command doubles. CI runs it independently of the application tests.
- `ReleaseMigrationCliTest` checks strict migration history and idempotency on real PostgreSQL.
- CI builds the release image and runs `test_deploy_integration.py` against real PostgreSQL,
  the packaged JAR, the normal application entrypoint, and its Nginx API route.
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

## Links

- Related: [Trade Republic feature](./trade-republic.md) (tr-auth microservice)
