<div align="center">

# Picsou

**Self-hosted personal finance dashboard**

Track bank accounts, brokerage, crypto, and net worth — all in one place.

[![License: Apache 2.0 + Commons Clause](https://img.shields.io/badge/License-Apache%202.0%20%2B%20Commons%20Clause-blue.svg)](LICENSE)

[Getting started](#getting-started) · [Features](#features) · [Development](#development) · [Security](SECURITY.md)

</div>

---

## Disclaimer

> **Picsou is designed for personal, local use.**
>
> It stores sensitive financial data (balances, transactions, bank session tokens). It supports multi-member families, optional TOTP 2FA, and audit logging of setup/admin actions, but it has **not** undergone a professional security audit.
>
> **Do not expose it on the public internet.** Use it on your local machine or home network behind a firewall. If you choose to expose it, you do so at your own risk.

---

## Features

- **Account aggregation** — Bank accounts (LEP, PEA, Livret, current), brokerage, crypto wallets, on-chain addresses, debts/loans
- **Bank sync** — Enable Banking (PSD2/OAuth, 2000+ EU banks).
- **BoursoBank** — Current accounts, livrets and the PEA/CTO with its cash and positions, via a local read-only sidecar. Reaches the securities account PSD2 cannot.
- **Brokerage sync** — Trade Republic via WebSocket or CSV import, and Bourse Direct PEA/CTO positions via a local read-only sidecar
- **Employee savings** — Amundi Épargne Salariale plans (PEE/PEG, PERCO, PER) and their FCPE lines, via a local read-only sidecar
- **Crypto** — Binance and Meria exchange sync, on-chain BTC/ETH/SOL address tracking
- **Live prices** — CoinGecko (crypto), Yahoo Finance (stocks/ETFs)
- **Real estate** — Automatic monthly valuation of French property from open data (DGFiP transactions via Cerema, IGN geocoding, INSEE price index). No API key, no subscription. Ownership shares between family members, mortgages linked to the property they finance, and gross/net property equity.
- **Security insight** — Per-holding asset-type detection and ETF composition (top holdings, country & sector breakdowns) in the holding detail modal
- **Net worth tracking** — Historical snapshots, stacked area charts, per-account breakdown
- **Savings goals** — Targets with deadlines, progress tracking across accounts
- **Multi-member family** — One admin manages multiple profiles (children, spouse). Per-resource sharing (`NONE` / `ALL` / `MANUAL`), optional activation links to upgrade a managed profile to a full login.
- **2FA + Remember Me** — Opt-in TOTP per user, 10 single-use recovery codes, 90-day "Remember Me" cookie with rotating tokens, "Trust this device" to skip TOTP, per-session revocation from settings.
- **GDPR data export** — Self-service ZIP export (JSON + per-entity CSV) gated by re-authentication, rate-limited to 5/hour.
- **Finary import** — CSV import or direct API sync
- **i18n** — English and French
- **Dark mode** — System/light/dark with flash-free theme switching

## Architecture

```
┌──────────────────┐     ┌───────────────────────┐     ┌────────────┐
│  React Frontend  │────▶│  Spring Boot Backend   │────▶│ PostgreSQL │
│   (Vite/Bun)     │◀────│     (Tomcat :8080)     │     │  (:5432)   │
└──────────────────┘     └───────────┬────────────┘     └────────────┘
                                     │
                      ┌──────────────┼──────────────┬──────────────┐
                      ▼              ▼               ▼              ▼
               Enable Banking   CoinGecko      Yahoo Finance   Trade Republic
               (PSD2/OAuth)     (crypto)       (stocks/ETF)    (WebSocket)
```

- **Ports & Adapters** — `BankConnectorPort`, `PriceProviderPort`, `TradeRepublicPort`, `BoursoPort`, etc. Swap providers without touching business logic.
- **Two-tier identity** — `AppUser` (auth) → `FamilyMember` (domain). Every entity is scoped by `member_id`; admins can act on behalf of a managed profile via `?memberId=X`.
- **Flyway** — Versioned database migrations
- **JWT auth** — HttpOnly cookies, SameSite=Lax (Safari iOS compatibility), refresh token rotation
- **2FA (TOTP)** — Opt-in, with hashed recovery codes and trusted-device cookies
- **AES-256-GCM** — Mandatory encryption for API secrets at rest (Binance, TOTP secrets, bank session tokens)
- **Rate limiting** — Bucket4j on login, MFA challenge, sync endpoints, and data export

## Tech stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 3.4, Maven |
| Frontend | React 19, TypeScript 5.9, Vite 7, Tailwind v4, Bun |
| Database | PostgreSQL 16, Flyway |
| Runtime | Docker (Nginx + Spring Boot + supervisor) |

## Getting started

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) & Docker Compose v2
- (Optional) An [Enable Banking](https://enablebanking.com/) account for bank sync

### 1. Clone

```bash
git clone https://github.com/Zoeille/picsou-finance.git
cd picsou-finance
```

### 2. Run (zero-config)

Picsou publishes pre-built, multi-arch (amd64/arm64) images to the GitHub Container Registry, so there is nothing to compile:

| Image | Package |
|-------|---------|
| `ghcr.io/zoeille/picsou-finance` | [picsou-finance](https://github.com/users/Zoeille/packages/container/package/picsou-finance) — app (frontend + backend) |
| `ghcr.io/zoeille/picsou-finance/tr-auth` | [picsou-finance/tr-auth](https://github.com/users/Zoeille/packages/container/package/picsou-finance%2Ftr-auth) — Trade Republic auth sidecar |
| `ghcr.io/zoeille/picsou-finance/bourse-direct-auth` | Bourse Direct login/2FA sidecar |
| `ghcr.io/zoeille/picsou-finance/amundi-auth` | Amundi Épargne Salariale login/2FA sidecar |
| `ghcr.io/zoeille/picsou-finance/bourso-auth` | BoursoBank login/2FA sidecar |
| `ghcr.io/zoeille/picsou-finance/fortuneo-auth` | Fortuneo login/2FA and portfolio sidecar |

```bash
docker compose -f docker/docker-compose.yml pull    # fetch the published images from GHCR
docker compose -f docker/docker-compose.yml up -d
```

> The compose file pins `:latest`. To follow the bleeding edge instead, override with `:nightly` (built on every `main` push), or pin a release such as `:1.0.0`.
>
> Building from source instead of pulling? Run `docker compose -f docker/docker-compose.yml up --build` — the `build:` sections are kept for contributors.

On first launch the entrypoint auto-generates `JWT_SECRET`, `CRYPTO_ENCRYPTION_KEY`, and `POSTGRES_PASSWORD` (persisted to the `picsou_data` volume under `/data/.secrets/`).

> [!IMPORTANT]
> **Planning to sync bank accounts? Set up HTTPS now, before opening the wizard — jump to
> [step 3](#3-https-decide-before-the-first-launch).**
> Enable Banking refuses plain-HTTP callback URLs, and the wizard permanently stores values derived
> from the address you open it on. Run it over HTTP and you will have to correct three settings by
> hand afterwards; run it over HTTPS and they are all derived correctly with nothing to type.

Otherwise, open http://localhost:8080 — the **setup wizard** walks you through admin credentials, CORS, and (optionally) Enable Banking.

### 3. HTTPS (decide before the first launch)

**Required for bank sync.** Enable Banking rejects plain-HTTP callback URLs for PRODUCTION
applications, and PRODUCTION is the only mode that lists real banks — so an HTTP-only install can
never complete a bank connection. Everything else in Picsou works fine over HTTP.

The stack ships an optional [Caddy](https://caddyserver.com/) TLS terminator, **off by default** so
it cannot collide with an ingress proxy you already run.

#### 3a. Choose a hostname

Caddy picks the certificate strategy from `PICSOU_DOMAIN` alone — there is no issuer to configure:

| `PICSOU_DOMAIN` | Certificate | What you must do |
|---|---|---|
| A real domain (`picsou.example.com`) resolving to this host, `:80`+`:443` reachable | Let's Encrypt, publicly trusted | **Nothing.** Issued and renewed automatically |
| A LAN IP (`192.168.1.50`) or a `.local` / `.internal` name | Caddy's built-in CA | Install its root once per device (3c) |
| `picsou.localhost` (the default) | Caddy's built-in CA | Resolves **only on the Docker host** — fine for a smoke test, useless from a phone |

A real domain is worth the effort: it is the only option with no per-device step. It does not need
to be publicly reachable for day-to-day use — only during certificate issuance.

#### 3b. Start the stack with TLS

This replaces the `up -d` from step 2 — it starts the same services plus the proxy, so on a fresh
install run this instead:

```bash
# Start from the commented reference file if you don't have a .env yet
[ -f docker/.env ] || cp docker/.env.example docker/.env

# printf's leading \n guards against a .env that lacks a trailing newline —
# plain `echo >>` would concatenate onto the last line and corrupt both values.
printf '\nPICSOU_DOMAIN=picsou.example.com\n' >> docker/.env

docker compose -f docker/docker-compose.yml --profile tls pull
docker compose -f docker/docker-compose.yml --profile tls up -d
```

> [!IMPORTANT]
> **The `pull` is not optional on an existing install.** Compose will not re-fetch `:latest` if the
> image already exists locally, so you would keep an older build that sends HSTS unconditionally —
> which, combined with the internal-CA certificate from 3c, is precisely the lockout this setup
> exists to avoid. Building from source? Use `up -d --build` instead.

Note that plain HTTP stays published on `:8080` at this point. That is deliberate — it is your way
back in if the certificate is not trusted yet. Close it in 3d, once HTTPS is confirmed working.

Verify before going further:

```bash
curl -sk -o /dev/null -w 'https=%{http_code}\n' https://picsou.example.com/
curl -s  -o /dev/null -w 'http=%{http_code} -> %{redirect_url}\n' http://picsou.example.com/
```

Expect `https=200` and an `http=308` redirect. **Now** open `https://picsou.example.com` and run the
setup wizard. It reads your browser's origin, so the callback URL, allowed origins, and secure-cookie
flag all land on the HTTPS values with nothing to type.

Finally, register `https://picsou.example.com/sync/callback` in the Enable Banking portal — it must
match byte-for-byte, or auth initiation fails with `REDIRECT_URI_NOT_ALLOWED`.

#### 3c. No domain? Trust the internal CA

Certificate trust cannot be established remotely — each device must be told once that your CA is
legitimate. Export the root:

```bash
docker compose -f docker/docker-compose.yml cp \
  proxy:/data/caddy/pki/authorities/local/root.crt ./picsou-root-ca.crt
```

Install it per device: Keychain Access (macOS), Settings → Security → Encryption & credentials
(Android), `update-ca-certificates` (Linux), certmgr.msc (Windows). Firefox keeps its own store —
Settings → Privacy & Security → Certificates.

Only *your* devices need this. Enable Banking never fetches the callback URL; the redirect happens
entirely inside the user's browser, so an internal CA is perfectly sufficient for bank sync.

#### 3d. Hardening, once HTTPS works

Two steps, once HTTPS works from every device you use.

**Close the plain-HTTP port.** Until now `:8080` has served the whole app in cleartext to the LAN,
credentials included. Add the overlay file to remove that publish — the container still listens on
8080 inside the Docker network, which is how Caddy reaches it:

```bash
docker compose -f docker/docker-compose.yml \
               -f docker/docker-compose.no-http.yml \
               --profile tls up -d
```

Keep passing both `-f` flags from then on, or the publish comes back. (Requires Compose v2.24+ for
`!reset`; on older versions comment out the `ports:` block in `docker/docker-compose.yml` instead.)

**Enable HSTS** — **only with a publicly-trusted
(Let's Encrypt) certificate**:

```bash
printf '\nHSTS_ENABLED=true\n' >> docker/.env
docker compose -f docker/docker-compose.yml \
               -f docker/docker-compose.no-http.yml \
               --profile tls up -d --force-recreate app
```

Both `-f` flags again — dropping the overlay here would re-publish the `:8080` port you just closed,
and `--force-recreate` would make it happen immediately.

`HSTS_ENABLED` sends `Strict-Transport-Security`, telling browsers to refuse plain HTTP for this
host for a year. It is off by default, so **if you run a public HTTPS deployment you should turn it
on** — otherwise nothing stops a browser being downgraded to the `:8080` port if it is exposed.

`--force-recreate app` is needed because this value reaches the container only through `env_file`,
which does not always change the rendered service config — a plain `up -d` can report the container
as up-to-date and leave the old setting in place. Verify it took effect:

```bash
curl -sI https://picsou.example.com/ | grep -i strict-transport
```

Exactly one `Strict-Transport-Security` line when enabled, none when disabled.

In this image nginx is the only emitter the browser ever sees: it adds the header to every response
including proxied ones, and strips the backend's copy on `/api` and `/actuator`
(`proxy_hide_header`) so it is never sent twice. The backend has its own gate on the same variable,
which is what the split-stack deployment relies on — to check that one, ask the backend directly,
bypassing nginx:

```bash
docker compose -f docker/docker-compose.yml exec app \
  curl -sI -H 'X-Forwarded-Proto: https' http://127.0.0.1:9090/actuator/health \
  | grep -i strict-transport
```

> [!WARNING]
> Never set `HSTS_ENABLED=true` with an internal-CA or mkcert certificate. The browser remembers the
> policy and then refuses to offer the "proceed anyway" bypass for a certificate it does not trust,
> locking you out with no in-app recovery — only clearing HSTS state in browser internals
> (`chrome://net-internals/#hsts`) gets you back. It is off by default for this reason.

#### 3e. Already running your own reverse proxy?

Skip the profile entirely and point Traefik / Nginx Proxy Manager / a Cloudflare Tunnel at the app
container's `:8080`. Your proxy **must** forward `X-Forwarded-Proto: https` (all of the above do by
default). Picsou honors that header to know it is served over HTTPS; without it the backend treats
same-origin HTTPS requests as cross-origin and login / the wizard's **Origins** step fail with
**403**. For a same-origin deployment leave `ALLOWED_ORIGINS` blank and let the wizard handle it,
and keep `SECURE_COOKIES=true`.

#### 3f. Adding HTTPS to an install that is already set up

If the wizard already ran over HTTP, three values are stored in the **database**, which takes
precedence over `.env` — editing environment variables will not change them.

Start TLS as in 3b. Do **not** add the `no-http` overlay from 3d yet — you want plain HTTP on
`:8080` to stay reachable as a fallback until HTTPS is confirmed from the machines you actually use:

```bash
[ -f docker/.env ] || cp docker/.env.example docker/.env
printf '\nPICSOU_DOMAIN=picsou.example.com\n' >> docker/.env
docker compose -f docker/docker-compose.yml --profile tls pull
docker compose -f docker/docker-compose.yml --profile tls up -d
```

Then log in over HTTPS and fix the three settings in the UI:

| Setting | Where | New value |
|---|---|---|
| Redirect URI | Admin → Integrations → Enable Banking | `https://<domain>/sync/callback` |
| Allowed origins | Admin → Security | add `https://<domain>` |
| Secure cookies | Admin → Security | `true` — **set this last**, after confirming HTTPS login works |

For allowed origins there is a shortcut: set `ALLOWED_ORIGINS` in `docker/.env`, restart, then press
**Reload from environment** in Admin → Security. That is the one place where env is allowed to
overwrite the stored value. It covers CORS only — the redirect URI and cookie flag still have to be
edited directly.

Then update the redirect URI in the Enable Banking portal to match. Once HTTPS works from every
device you care about, close the HTTP door with the `no-http` overlay from 3d.

You may not need the origins change at all: Caddy forwards `X-Forwarded-Host`/`-Proto`, so the
backend should recognise the request as same-origin and skip CORS entirely. Only add the origin if
you actually hit a **403**.

> [!WARNING]
> **Once Secure cookies is `true`, plain HTTP is no longer a usable fallback.** Auth cookies are
> issued with the `Secure` flag, which browsers discard over HTTP — login returns 200 and then
> bounces straight back to the login screen with no error. If TLS later breaks (expired certificate,
> lost `caddy_data` volume, DNS change) and you need to get back in over HTTP, clear the flag
> directly in the database and restart:
>
> ```bash
> docker compose -f docker/docker-compose.yml exec db \
>   psql -U picsou -d picsou -c \
>   "update app_setting set value='false' where setting_key='app.secure-cookies';"
> docker compose -f docker/docker-compose.yml restart app
> ```

### 4. Advanced configuration (optional)

If you prefer to seed everything up front (CI, external secret managers, etc.):

```bash
cp docker/.env.example docker/.env
```

| Variable | When to set | Description |
|----------|-------------|-------------|
| `POSTGRES_PASSWORD` | Override auto-gen | Strong random password |
| `JWT_SECRET` | Override auto-gen | `openssl rand -base64 48` |
| `CRYPTO_ENCRYPTION_KEY` | Override auto-gen | `openssl rand -base64 32` |
| `APP_USERNAME` / `APP_PASSWORD_HASH` | Skip wizard | `htpasswd -bnBC 12 "" YOUR_PASSWORD \| tr -d ':\r\n'` |
| `ALLOWED_ORIGINS` | Non-localhost | e.g. `https://picsou.example.com` — leave blank for a same-origin deployment |
| `SECURE_COOKIES` | Plain HTTP | `false` if no TLS in front; keep `true` behind HTTPS |
| `ENABLEBANKING_*` | Skip wizard | From your [Enable Banking dashboard](https://enablebanking.com/). The redirect URI must be `https://` |
| `BOURSO_AUTH_URL` | Custom sidecar | Defaults to `http://bourso-auth:8001` |
| `BOURSE_DIRECT_AUTH_URL` | Custom sidecar | Defaults to `http://bourse-direct-auth:8001` |
| `FORTUNEO_AUTH_URL` | Custom sidecar | Defaults to `http://fortuneo-auth:8001` |
| `PICSOU_DOMAIN` | TLS profile | Hostname Caddy serves — see [step 3](#3-https-decide-before-the-first-launch) |
| `HSTS_ENABLED` | Trusted cert | `true` only with a publicly-trusted certificate |

> **Note:** The bcrypt hash contains `$` characters. In `.env`, write it as-is without quotes. Never export it in a shell without single quotes: `export APP_PASSWORD_HASH='$2a$12$...'`.

> **Settings the wizard already stored win over `.env`.** `ENABLEBANKING_REDIRECT_URI`,
> `ALLOWED_ORIGINS`, and `SECURE_COOKIES` resolve **database-first**, so once setup has completed,
> changing them here has no effect — use the Admin page instead (see [3f](#3f-adding-https-to-an-install-that-is-already-set-up)).

### 5. Enable Banking key setup (optional)

```bash
mkdir -p docker/secrets
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out docker/secrets/enablebanking.pem
openssl rsa -pubout -in docker/secrets/enablebanking.pem -out enablebanking_public.pem
```

Upload `enablebanking_public.pem` to your Enable Banking dashboard.

## Development

### Backend

```bash
cd backend
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn spring-boot:run -Dspring-boot.run.profiles=dev   # Requires PostgreSQL on :5432
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test                                              # Run tests
```

Backend Maven runs enforce Java 21 during `validate`; set `JAVA_HOME` to a JDK 21 installation before running backend commands locally.

### Frontend

```bash
cd frontend
bun install        # Install dependencies
bun run dev        # HTTPS dev server on https://localhost:5173 (proxies /api/* → localhost:8080)
bun run build      # TypeScript check + Vite build
bunx vitest run    # Unit tests
```

#### 🔒 HTTPS in Development (Hybrid Mode)

To comply with the strict security requirements of certain banking integrations, the Vite development server is configured to run over **HTTPS**. The project utilizes a hybrid approach to make setup seamless for all environments:

* **Premium Mode (Recommended for your host machine):** Install `mkcert` on your system, then generate your local certificates at the root of the `frontend/` directory:
  ```bash
  mkdir -p .local/certs
  mkcert -cert-file .local/certs/picsou-local-cert.pem -key-file .local/certs/picsou-local-key.pem localhost 127.0.0.1 ::1
  ```
  *Benefit: You will get a natively trusted green padlock in your browser with zero security warnings.*

* **Fallback Mode (Zero-Config / Dev Container):** If the `.pem` files are not detected inside `.local/certs/`, the `@vitejs/plugin-basic-ssl` plugin will automatically take over.
  *Note: The development server will still start over HTTPS, but you will need to bypass your browser's security warning on first access. Automation can instead opt in to ignoring the self-signed certificate.*

## Contributing

Contributions are welcome — bug fixes, features, translations, or documentation.

1. Fork the repository
2. Create a feature branch (`feat/xxx`, `fix/xxx`)
3. Write conventional commits
4. Open a pull request against `main`

Please read the relevant [feature docs](docs/features/) and [conventions](docs/conventions/) before touching existing code.

## Security

See [SECURITY.md](SECURITY.md) for the vulnerability reporting policy.

## License

[Apache 2.0 + Commons Clause](LICENSE) — free for personal use and managed hosting. Commercial SaaS use is prohibited without permission.
