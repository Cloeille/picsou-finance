# Feature: First-launch Setup Wizard

> Last updated: 2026-07-19 (HSTS is opt-in, no longer an always-on nginx header)

## Context

A fresh Picsou install used to require hand-editing `.env` with 13+ values — including
`openssl rand` runs and PKCS8 key juggling — and a malformed `APP_PASSWORD_HASH` hard-
failed Spring at startup with an unfriendly stack trace. The wizard replaces all of that
with a guided, web-based flow the first time the app is opened.

## How it works

The bootstrap runs on two levels.

- **Invisible (Docker entrypoint)** auto-generates `JWT_SECRET`, `CRYPTO_ENCRYPTION_KEY`,
  `POSTGRES_PASSWORD` into `/data/.secrets/` when the corresponding env vars are unset.
  This happens before `supervisord` starts Spring, because those three secrets are
  consumed by bean constructors (`JwtUtil`, `CryptoEncryption`) and by Flyway before any
  DB-backed config is available.
- **Visible (web wizard)** is served at `/setup` while `setup.state != COMPLETE`. It
  walks the user through: admin account → CORS & secure cookies → integration picker →
  per-integration sub-flows → Done (with confetti + auto-login).

`SetupFilter` short-circuits every non-setup request with `503 setup_required` before
setup is complete, and with `410 Gone` after — so a stale browser tab can never re-POST
admin creation.

### Key files

Backend:
- `backend/src/main/java/com/picsou/controller/SetupController.java` — all `/api/setup/*`
  endpoints (public, rate-limited by Bucket4j).
- `backend/src/main/java/com/picsou/service/SetupService.java` — state-machine
  transitions (`PENDING_ADMIN → IN_PROGRESS → COMPLETE`) and admin seeding.
- `backend/src/main/java/com/picsou/service/SetupAuditService.java` — append-only
  `setup_audit` table writes; swallows its own errors so audit failure never blocks a
  controller response.
- `backend/src/main/java/com/picsou/service/EnableBankingKeyPairService.java` —
  idempotent RSA-2048 PEM generation at `/data/keys/enablebanking-private.pem` (POSIX
  `0600`).
- `backend/src/main/java/com/picsou/service/CryptoKeyGeneratorService.java` —
  idempotent AES-256 key checker/writer. If `CRYPTO_ENCRYPTION_KEY` is already present
  in the running process (the normal bare-metal `.env.local` flow), the wizard treats
  that as configured and does not touch Docker's `/data` path. Otherwise it writes the
  same `/data/.secrets/crypto_key` path as the Docker entrypoint.
- `backend/src/main/java/com/picsou/config/SetupFilter.java` — 503/410 gate.
- `backend/src/main/java/com/picsou/config/DynamicCorsConfigurationSource.java` — reads
  `cors.allowed-origins` from `app_setting` so the wizard's Security step takes effect
  without a container restart.
- `backend/src/main/resources/db/migration/V25__setup_state.sql` — conditional seed:
  `setup.state='COMPLETE'` for existing installs (admin already present), else
  `'PENDING_ADMIN'`.
- `backend/scripts/picsou-init.sh` — bare-metal equivalent of the Docker entrypoint;
  prints an `.env.local` to stdout.

Frontend:
- `frontend/src/pages/setup/SetupLayout.tsx` — the centered hero layout, top progress
  bar, language switcher, step pill, font preload.
- `frontend/src/pages/setup/HelloGreeting.tsx` — 12-greeting multilingual opener in
  Homemade Apple font, `prefers-reduced-motion` aware.
- `frontend/src/pages/setup/SetupStep{Intro,Admin,Security,Integrations,Complete}.tsx`
  — the main-line steps.
- `frontend/src/pages/setup/integrations/SetupStepEnableBanking.tsx` + 5 substep
  components under `enablebanking/` — the guided EB flow.
- `frontend/src/pages/setup/integrations/SetupStep{BoursoBank,BourseDirect,TradeRepublic,Finary,Crypto}.tsx`
  — the other integration substeps.
- `frontend/src/features/setup/{api,hooks,schemas,guards}.tsx` — dedicated axios
  client (no 401 interceptor), react-query hooks, zod schemas, route guards.
- `frontend/src/stores/setup-flow-store.ts` — Zustand (persisted to sessionStorage) for
  selected integrations, EB draft, admin display name.
- `frontend/src/stores/setup-credentials.ts` — in-memory-only admin credentials for
  post-setup auto-login. Deliberately not persisted.

### Flow

```
First boot
    │
    ▼
docker entrypoint.sh  ──► writes /data/.secrets/{jwt_secret,crypto_key,postgres_password}
                              (only if env vars unset — idempotent)
    │
    ▼
Spring boots → Flyway V25 seeds setup.state='PENDING_ADMIN'
    │
    ▼
User opens http://host:8080  ──► RequireSetup redirects /login → /setup
    │
    ▼
Hello greeting → Admin → Security → Integration picker
    │                                    │
    │                                    ├─ Enable Banking: 5 substeps
    │                                    ├─ BoursoBank: sidecar ping
    │                                    ├─ Bourse Direct: post-setup login acknowledgement
    │                                    ├─ Trade Republic: ack
    │                                    ├─ Finary: ack
    │                                    └─ Crypto: ensure key exists
    │
    ▼
Done → POST /api/setup/complete → auto-login → /
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Two-layer bootstrap (entrypoint + web) | `JWT_SECRET` and `CRYPTO_ENCRYPTION_KEY` are consumed in bean constructors before the DB is available, so they can't live in DB. | Storing every secret in DB (wouldn't boot). |
| `setup.state` as a DB row | Survives container restarts; single source of truth; Flyway can conditionally seed it. | Env var (ephemeral); file on disk (hard to CAS). |
| SERIALIZABLE + CAS on admin creation | Two concurrent POST /admin calls on a fresh install can't both succeed. | Optimistic Hibernate version (doesn't block the second transaction). |
| In-memory stash for auto-login | Password must never hit sessionStorage; refresh loses auto-login but never leaks credentials. | Persisting credentials in the Zustand store. |
| Pure-CSS confetti | Zero new dep, gzip-free celebration, trivial `prefers-reduced-motion` guard. | `canvas-confetti` (~7kB, yet another supply-chain concern). |
| Dedicated axios client for `/api/setup/*` | The main `api` instance has a 401 → refresh → `/login` interceptor that would bounce unauthenticated wizard calls. | Sharing the main client and special-casing 401 (fragile). |
| Idempotent EB keypair | Re-generating the public PEM from an existing private key never invalidates what the user already uploaded to Enable Banking. | Overwriting on every call (silent data loss). |
| Self-hosted Homemade Apple font | Picsou never phones home to fonts.googleapis.com. | Google Fonts CDN link (exfils user IP + user-agent on every fresh install). |
| Nginx-level CSP + security headers | Applies to the index.html that serves the SPA — backend API-only CSP would have no browser effect. | Spring-level CSP (wouldn't apply since SPAs load HTML from nginx). |

## Gotchas / Pitfalls

- **`CryptoKeyGeneratorService` must not write Docker's `/data` path when
  `CRYPTO_ENCRYPTION_KEY` is already loaded from `.env.local`.** Bare-metal macOS
  installs often cannot create `/data`; the runtime env key is already sufficient for
  encryption. If a file fallback is needed, `CryptoKeyGeneratorService` and the Docker
  entrypoint still share `/data/.secrets/crypto_key`. Covered by
  `CryptoKeyGeneratorServiceTest.ensureKey_treatsConfiguredRuntimeKeyAsExistingWithoutWritingFile`
  and `ensureKey_isIdempotent_neverOverwritesExistingKey`.
- **V25 migration seeding logic** reads `EXISTS(SELECT 1 FROM app_user)` and seeds
  `setup.state='COMPLETE'` for existing installs. If you rename `app_user` don't
  forget this query.
- **`SetupFilter` returns 410 Gone (not 404) after setup completes**, per the plan's
  production-hardening section. The frontend interceptor recognizes both 503 (redirect
  to `/setup`) and 410 (redirect to `/`).
- **`SetupAuditService.record()` swallows its own exceptions**. A DB hiccup during
  audit must never block the controller response — audit is observability, not
  correctness.
- **The EB keypair endpoint is idempotent but the underlying file is the source of
  truth.** Deleting `/data/keys/enablebanking-private.pem` out-of-band and re-running
  the wizard will hand the user a new public PEM and invalidate what they uploaded to
  Enable Banking. A proper key-rotation flow is explicitly out of scope for this
  wizard.
- **Auto-login at the Done screen is best-effort.** If the user refreshes mid-wizard,
  the in-memory credentials are lost and the Done CTA falls back to the login page.
  setup.state is COMPLETE either way; no data is lost.
- **The "pick integrations" step writes nothing to the DB.** Flags only flip when the
  corresponding sub-step completion endpoint is hit (e.g., `/enablebanking/test`
  returning `ok: true`). This is deliberate: ticking EB but then skipping the substeps
  should not leave the app thinking EB is configured.
- **Client-side admin validation errors are i18n keys.** `setupAdminSchema` uses
  `auth.validation.*` messages; every new key must exist in both locale files or the
  wizard will show raw translation keys under the form fields. Inline errors should
  only be shown after the user has typed into the field; empty untouched fields keep
  their neutral helper copy.
- **Setup loading is intentionally blank.** The generic dashboard `LoadingSkeleton`
  must not be used for `/setup` route guards or lazy-route fallbacks; it flashes
  dashboard-shaped grey placeholders before the wizard appears.
- **Setup form scale is intentionally consistent.** Wizard steps use `max-w-2xl`,
  `h-10` inputs / primary CTAs, rounded field controls, and no decorative uppercase
  surtitles. Multiple primary choices in a wizard step should stack vertically instead
  of sitting side-by-side. New wizard form steps should keep that scale unless the page
  has a distinct reason.
- **`SetupFilter` must anchor its `addFilterBefore(...)` to a Spring-Security-registered
  filter class (e.g. `UsernamePasswordAuthenticationFilter`), not to a custom one like
  `JwtAuthenticationFilter`.** Passing a custom class as the anchor throws
  `IllegalArgumentException: "... does not have a registered order"` at context-load
  time because Spring Security's `FilterOrderRegistration` only knows the order of its
  own filters. No unit/slice test in the current suite instantiates `SecurityConfig`'s
  real `filterChain` bean, so this class of bug only fails at container boot — verify
  wiring changes with a live `docker compose up`.

## Accessibility

Manually verified for the touched components (no axe-core in CI yet — documented as
future work):

- Every interactive element meets `4.5:1` contrast against its background.
- Full keyboard traversal works on every step (Tab / Shift+Tab / Enter / Space / Esc).
- The admin password field includes a keyboard-focusable visibility toggle with a
  translated `aria-label`; toggling it only changes the input type (`password`/`text`).
- `role="switch"` on the `IntegrationCard` with `aria-checked`; the whole card is the
  hit target.
- `aria-live="polite"` on the greeting announcer, the integrations-selected counter,
  and the EB test result card.
- `prefers-reduced-motion` honored by: HelloGreeting (collapses to a static render),
  step transitions (`animate-setup-slide-in` → no-op), confetti (pieces hidden).
- Focus styling uses the global `:focus-visible` ring from `index.css`.
- Skip-to-content link at the top of `SetupLayout`; it is visually hidden by default
  and becomes visible when focused.

## Security posture

- **Rate limiting** on `/api/setup/*` via Bucket4j (10 rpm / IP). 429 on breach.
- **Headers** (set by nginx, app-wide):
  `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy:
  strict-origin-when-cross-origin`, `Permissions-Policy: camera=(), microphone=(), geolocation=()`.
- **`Strict-Transport-Security` is opt-in**, not app-wide: it is only sent when
  `HSTS_ENABLED=true` (see [docker-deployment.md](./docker-deployment.md)). Enabling it
  with a locally-issued certificate locks users out — the browser drops the
  "proceed anyway" bypass. Do not assume HSTS is enforced on a deployment.
- **CSP** (nginx):
  `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src
  'self' data: https:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'`.
  `'unsafe-inline'` is only in `style-src` because Tailwind's JIT injects inline
  styles; there is no inline script allowance.
- **Audit trail** in the `setup_audit` table for `setup.admin.created`,
  `setup.integration.enabled`, `setup.security.updated`, `setup.completed` — valuable
  forensics for a self-host admin who wants to know "who ran setup on this machine".
- **Single-account seeding.** The wizard seeds exactly one account. `SetupService.seedAdmin`
  rejects any call once one user already exists (`AppUserRepository.count() > 0`),
  regardless of `setup.state` — so a second, differently-named `role=ADMIN` account can no
  longer be planted through `POST /api/setup/admin` during the `IN_PROGRESS` window (after
  the first admin is seeded but before `/api/setup/complete` is called). Further accounts
  are created from the authenticated Family admin UI, never from the wizard.

## Running without Docker

Bare-metal installs (`mvn spring-boot:run`) need the secrets bootstrap done manually
on first boot:

```bash
./backend/scripts/picsou-init.sh > .env.local
set -a && . .env.local && set +a
mvn -pl backend spring-boot:run
```

The wizard itself works identically — only the secrets-generation step differs.

## Privacy

The wizard makes zero outbound requests on first load:

- Homemade Apple is bundled under `frontend/public/fonts/` — no Google Fonts CDN hit.
- No analytics, no telemetry, no Sentry-style error reporter.
- The only outbound call is the user-initiated "Test connection" for Enable Banking,
  which goes to EB's official API.

## Tests

- `SetupServiceTest` — state transitions (`PENDING_ADMIN → IN_PROGRESS → COMPLETE`),
  SERIALIZABLE CAS on admin claim, bcrypt-hash rejection, idempotent seed when user
  already exists, rejection when any account already exists (second-admin backdoor),
  CSV origin persistence, empty-origin rejection, consistent integration-key formatting.
- `SetupControllerTest` — endpoint-level: admin seed returns 410 after completion,
  EB keypair regenerate-flag on first call vs. idempotent subsequent calls, EB
  `test` only flips the integration flag on success, BoursoBank health / Bourse Direct / TR / Finary
  acknowledge flows, crypto-key flagging for existing vs. freshly generated, rate
  limit returns 429 after bucket drain.
- `EnableBankingKeyPairServiceTest` — first-call persistence of private PEM, idempotent
  second call returning the same public half, POSIX 0600 perms on the private-key file.
- `CryptoKeyGeneratorServiceTest` — Base64 AES-256 shape on first call, never
  overwrites on re-run, `exists()` reports absence then presence.

`IntegrationsHealthService` is tested indirectly through `SetupControllerTest` mocks
— no dedicated unit test today, since its logic is a thin HTTP-client wrapper whose
failure modes are better exercised at the controller boundary.

Frontend coverage is smoke-tested via `bun run typecheck` + `bun run build` + manual
flow verification until a Playwright e2e suite is added (tracked as future work).

## Links

- Related ADR: [`docs/decisions/2026-04-23-first-launch-wizard.md`](../decisions/2026-04-23-first-launch-wizard.md)
- Encryption-at-rest ADR (context for CryptoKeyGeneratorService): [`docs/decisions/2026-04-08-mandatory-encryption-key.md`](../decisions/2026-04-08-mandatory-encryption-key.md)
- Docker deployment feature: [`docs/features/docker-deployment.md`](./docker-deployment.md)
