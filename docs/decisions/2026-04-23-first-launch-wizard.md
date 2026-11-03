# ADR: Two-layer bootstrap for first-launch Setup Wizard

> Date: 2026-04-23
> Status: ✅ Active

## Context

Up to this point, a self-hosted Picsou install required editing 13+ env vars by hand
before the container would boot — including `JWT_SECRET` (base64-48), `CRYPTO_ENCRYPTION_KEY`
(base64-32), `POSTGRES_PASSWORD`, `APP_USERNAME`, `APP_PASSWORD_HASH` (bcrypt cost-12),
`ALLOWED_ORIGINS`, `ENABLEBANKING_*`, etc. A malformed bcrypt hash or a missing secret
hard-failed Spring at startup with a stack trace most users couldn't diagnose, and the
onboarding drop-off was measurable (see community issues around Enable Banking
configuration specifically).

The design goal was to replace this with a single web wizard at first launch, good
enough that a user who's never touched a YAML file can go from `docker compose up` to
a working dashboard in under 2 minutes.

The architectural question was: *can we put every value in the wizard?*

## Decision

**No — we split the bootstrap into two layers.**

- **Layer 0 (invisible, Docker entrypoint)** auto-generates `JWT_SECRET`,
  `CRYPTO_ENCRYPTION_KEY`, and `POSTGRES_PASSWORD` on first boot, writes them to
  `/data/.secrets/` (persisted volume), and exports them as env vars before exec-ing
  `supervisord`. Idempotent — never overwrites existing values.

- **Layer 1 (visible, web wizard)** at `/setup` handles everything with product
  meaning: admin credentials, CORS origins, secure-cookies toggle, optional
  integrations and their per-integration config.

The state machine is a single DB row (`app_setting.setup.state`) with values
`PENDING_ADMIN → IN_PROGRESS → COMPLETE`, enforced by a `SetupFilter` that returns
`503 setup_required` before `COMPLETE` and `410 Gone` after.

## Alternatives considered

### Alternative A: single-layer wizard (everything in the web form)

- **Pros**: Pure-web UX, nothing on the shell side, looks neat on a feature sheet.
- **Cons**: Physically impossible. `JwtUtil` reads `JWT_SECRET` in its bean
  constructor; `CryptoEncryption` reads `CRYPTO_ENCRYPTION_KEY` in its. Flyway reads
  `POSTGRES_*` before the application context boots. None of these can come from a
  DB that Spring hasn't initialized yet. Any design that puts them in the wizard
  requires either a restart-on-save (brittle) or making those beans lazy (invasive
  refactor of auth and crypto hot paths).

### Alternative B: require the operator to pre-set every secret via env, wizard only for admin/CORS

- **Pros**: No entrypoint script, no new concepts.
- **Cons**: This is essentially what we had before the wizard. The point of the
  project was to remove the "edit 13 env vars" friction, not to paper over it with a
  small form on top.

### Alternative C: single giant "init" container that migrates the DB, seeds an admin from env, and hands off to the app container

- **Pros**: Follows the conventional K8s init-container pattern.
- **Cons**: Doesn't actually solve the problem — an init container still needs the
  admin creds at the moment it runs, so we'd be back to hand-editing env. Also adds
  orchestration complexity for a project whose main target is hobbyist
  docker-compose users on a home server.

## Reasoning

The two-layer split respects the real constraint — some secrets *must* be present
before Spring boots — while keeping the user-facing UX as a single web flow.

Key design properties we got by accepting the split:

- **Bare-metal support stays trivial**: a small shell script
  (`backend/scripts/picsou-init.sh`) does the equivalent of the entrypoint and prints
  an `.env.local` the operator `source`s.
- **Idempotency is the right invariant**: both layers check for an existing value
  (env var, file on disk, DB row) before writing. Re-running the entrypoint on an
  existing install is a no-op; so is re-visiting `/setup/integrations/crypto` when
  the key already exists.
- **Upgrade path is free**: V25's migration seeds `setup.state='COMPLETE'` if an
  `app_user` row already exists, so existing installs never see the wizard.

## Trade-offs accepted

- **Two bootstrap paths to document and test.** We compensate with explicit tests
  (`CryptoKeyGeneratorServiceTest.ensureKey_isIdempotent_neverOverwritesExistingKey`,
  `test-entrypoint.sh` in the Docker image CI) and a "Running without Docker"
  section in the feature doc.
- **The `/data` volume is load-bearing** — wiping it *and* keeping the container
  would regenerate secrets and invalidate active sessions/encrypted blobs. Operators
  are expected to treat `/data` with the same care as any Postgres data volume;
  documented in the Docker deployment doc.
- **Auto-login at the Done screen is best-effort** because we refuse to persist the
  admin password. If the user refreshes mid-wizard, the in-memory credential stash
  is lost and they land on `/login` manually instead. The wizard always completes
  successfully either way.

## Consequences

- New Flyway migration `V25__setup_state.sql` — the app now depends on the
  `app_setting` table. All legacy install paths must run this migration before the
  new code deploys.
- `DataSeeder` is now gated behind "both `APP_USERNAME` and `APP_PASSWORD_HASH`
  present" — removing the env-only path for new installs is a clean follow-up but
  not required by this ADR.
- All `/api/setup/*` endpoints are **unauthenticated**. This is safe because
  `SetupFilter` returns `410 Gone` for every one of them once `setup.state` is
  `COMPLETE`, and they're rate-limited at 10 rpm/IP before that via Bucket4j.
- Security headers (`CSP`, `X-Frame-Options: DENY`, `Permissions-Policy`) are set
  by nginx for the HTML served at `/` and `/setup` — this is the only place they
  can take browser effect, since the Java backend only responds with JSON.
- A `setup_audit` table accumulates forensic events (`setup.admin.created`,
  `setup.integration.enabled`, etc.). Append-only; never truncated.

## Supersedes

Implicitly replaces the "edit 13 env vars by hand" onboarding documented previously
in [`docs/features/docker-deployment.md`](../features/docker-deployment.md); that
document has been updated to point to the new wizard path as the default.

## Update 2026-11-03: secrets bootstrap moved into the app

Layer 0 is no longer a shell script. `SecretBootstrap`, a Spring Boot `EnvironmentPostProcessor`,
resolves `JWT_SECRET`, `CRYPTO_ENCRYPTION_KEY` and `POSTGRES_PASSWORD` from `PICSOU_SECRETS_DIR`
(`/data/.secrets` in the Docker image) with the same rules as before: an explicit env var wins,
else the existing file, else a generated value, never regenerated once the file exists.
`backend/scripts/picsou-init.sh` is removed; bare-metal installs set `PICSOU_SECRETS_DIR`
instead. The text above is kept as written at the time.
