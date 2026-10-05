# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| `1.0.x` | Yes       |
| < `1.0` | No        |

Security patches are applied to the latest release on the `main` branch.

## Reporting a Vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Report privately using one of:

- [GitHub Security Advisory](https://github.com/Cloeille/picsou-finance/security/advisories/new) (preferred)
- Email the maintainer directly (see GitHub profile)

Please include:

- A clear description of the vulnerability
- Steps to reproduce
- Potential impact (e.g., data exposure, auth bypass, injection)
- A suggested fix if you have one

You will receive an initial response within **72 hours**. Critical
vulnerabilities are targeted for a patch within **7 days**.

## Threat Model

Picsou is designed for **self-hosted deployment** by an individual or a small
family. As of 1.0.0 the app supports **multiple authenticated members per
instance**, but the underlying trust boundary is still the host: every member
shares the same database, encryption key, and process. The app is **not**
multi-tenant in the SaaS sense.

**In scope:**
- Protection of financial data at rest (database, API secrets, bank session
  tokens)
- Authenticated, member-scoped access — one member must not be able to read or
  modify another member's accounts/holdings/transactions/goals/debts unless
  explicitly shared via the sharing settings
- Secure authentication (JWT in HttpOnly cookies, optional TOTP 2FA, persistent
  "Remember Me" sessions with rotation and theft detection)
- Input validation and injection prevention
- Secret management — all credentials via environment variables or the setup
  wizard's encrypted store, never hardcoded
- Stateless JWT invalidation on password change (`token_version` claim)
- CORS / cookie hardening (no wildcard origins with credentials, environment-
  aware `Secure` flag)
- GDPR-style data export gated behind step-up re-authentication and rate-
  limited

**Out of scope:**
- Hardening against a compromised host, hypervisor, or trusted local network
  operator
- DDoS / volumetric attacks (assumed to be handled by the operator's reverse
  proxy or hosting provider)
- Defending one *administrator* member from another administrator on the same
  instance — admins can reset passwords and 2FA for other members by design
- Side-channel attacks against the JVM, Postgres, or the host OS

## Security Measures

| Measure                            | Implementation                                                                                                          |
| ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| Authentication                     | JWT (`access_token` + `refresh_token`) in `HttpOnly; Secure; SameSite=Lax` cookies                                      |
| Two-factor authentication          | TOTP (RFC 6238) with single-use recovery codes, anti-replay window, MFA-challenge JWT distinct from the access token    |
| Persistent sessions ("Remember Me")| Opaque rotating tokens with theft detection; admins can force-disable sessions for any member                           |
| Stateless JWT invalidation         | `token_version` claim incremented on password change; old access/refresh tokens reject immediately                      |
| Refresh token rotation             | Refresh token rotated on every `/api/auth/refresh` call                                                                 |
| Setup wizard hardening             | First-launch wizard rejects wildcard CORS origins, generates a strong `CRYPTO_ENCRYPTION_KEY`, and audits each step     |
| CORS                               | `Access-Control-Allow-Origin` defaults to empty (no cross-origin); never combined with `*` when credentials are allowed |
| Cookies                            | `SecureCookieProvider` sets `Secure` flag based on environment; `SameSite=Lax` for Safari/iOS compatibility             |
| Member-scoped authorization        | Every service/controller scopes queries by `memberId` from `UserContext`; family-shared resources gated by `SharingSettings` + `SharedResource`    |
| Rate limiting                      | Bucket4j on login (5/15 min), MFA challenge, sync endpoints, GDPR export, and password reset                            |
| Encryption at rest                 | AES-256-GCM for crypto-exchange API secrets, bank session tokens, and Finary credentials; key required at startup       |
| SQL injection                      | JPA/Hibernate parameterized queries, no raw SQL                                                                         |
| XSS                                | React's built-in escaping; CSP headers via Nginx in the Docker image                                                    |
| CSRF                               | `SameSite=Lax` cookies + Spring's CSRF token on state-changing endpoints                                                |
| Secrets                            | All credentials via environment variables or the wizard's `AppSetting` store (encrypted); never in source code          |
| Bank credentials                   | Enable Banking tokens session-scoped and never persisted in clear; PEM private key mounted read-only                    |
| GDPR export                        | Step-up re-authentication required; download is read-only-transactional and rate-limited                                |
| Audit log                          | `setup_audit` records every wizard action with actor, IP, and timestamp                                                 |
| Logging                            | No PII, secrets, or financial balances in application logs; CORS rejection logs are redacted                            |
| Dependencies                       | GitHub Dependabot enabled for automated vulnerability alerts                                                            |

## Deployment Security Checklist

- [ ] Run the first-launch **setup wizard** to configure admin credentials,
      CORS origins, and encryption key — *do not* skip and edit `.env`
      manually unless you know what you're doing.
- [ ] Set `ALLOWED_ORIGINS` to your **exact** front-end origin(s) — wildcards
      (`*`) are rejected when credentials are enabled.
- [ ] Generate a unique `JWT_SECRET` with `openssl rand -base64 48`.
- [ ] Generate a `CRYPTO_ENCRYPTION_KEY` with `openssl rand -base64 32` *or*
      let the wizard generate one (stored in `/data/.secrets/`).
- [ ] Enable **2FA** on every member account from Settings → Security; print
      and store the recovery codes offline.
- [ ] Keep `SECURE_COOKIES=true` in production (default).
- [ ] Keep the `.env` and `/data/.secrets/` directories out of version control
      and out of backups that travel off-host.
- [ ] Do not expose the app on the public internet without a reverse proxy
      with TLS (Caddy, Traefik, or Nginx).
- [ ] Restrict PostgreSQL access to the Docker network (default in
      `docker-compose.yml`).
- [ ] Regularly update Docker images and dependencies (`docker compose pull`
      + `docker compose up -d`).
- [ ] Review `setup_audit` periodically to spot unexpected configuration
      changes.

## Disabled / experimental integrations

The following integrations ship in 1.0.0 but are **disabled by default** in
the UI because they are not yet ready for production:

- **Powens / Budget Insight** — `PowensBankConnector` ships in the codebase
  but has **not** been tested end-to-end against a real Powens tenant. The
  `@Primary` annotation was removed so Enable Banking stays injected as the
  canonical `BankConnectorPort` even when `POWENS_CLIENT_ID` is set. Do
  **not** enable on a production instance until the adapter has been
  validated.
