# Feature: Migration-safe release deployment

> Last updated: 2026-10-08

## Context

Issue [#195](https://github.com/Cloeille/picsou-finance/issues/195) upgraded application code while
production Flyway was disabled. Missing schema caused API 502s; a later hand-written recovery missed
V100's `CREDIT_CARD` enum value despite successful Hibernate validation. Static frontend HTTP 200
and the highest history version are not proof that the packaged migrations have all been applied.

`docker/deploy.sh` is the authoritative upgrade path for `docker/docker-compose.yml`. Flyway remains
enabled during ordinary application startup as defence in depth. The explicit migration CLI runs
independently of Spring and does not honor `SPRING_FLYWAY_ENABLED=false`; that flag previously existed
only as a temporary workaround for issue #174, not a lasting production migration policy.

## Usage

Run from a checkout containing these tools on the Docker host. Requires Bash, Docker Compose v2,
`flock`, `curl`, Python 3 (private Compose override generation), PostgreSQL-owner privileges to create/drop a temporary database, and sufficient
space for a full backup plus a restored copy. Configure `docker/.env` and matching sidecars as usual.
No provider login is performed by preflight. Use an image built from this change or a later release;
earlier images do not contain the standalone CLI and fail before app replacement.
On a completely new installation, supply the documented bootstrap admin configuration or complete
the setup wizard before expecting API 401. While setup is incomplete, the real backend returns
503 `setup_required`; migrations may already have succeeded, but this tool intentionally does not
declare the application ready. Complete setup and rerun the idempotent workflow.

```sh
bash docker/deploy.sh ghcr.io/cloeille/picsou-finance:1.1.0 \
  https://picsou.example.internal "$HOME/picsou-backups" -p picsou_prod
```

Preserve the existing Compose project name and all its flags. Additional Compose flags follow the
three required arguments, for example `--profile tls` and `-f docker/docker-compose.no-http.yml`.
Supply the real origin of this application, not another deployment behind the same proxy.
Do not include credentials, query parameters, or fragments in that URL; the probe rejects them
and disables curl's default configuration so it cannot silently inherit authentication settings.
The image reference is resolved once to an immutable local ID; every phase uses that same ID.
An already-built `sha256:` image ID can be supplied directly without a registry pull.
All deployments of one project must use the same backup directory so its lock serializes them.

## Ordered gates

1. Lock deployment and resolve the candidate image. Start only the database if needed.
2. Take a full custom-format PostgreSQL backup. The archive is retained on success and failure,
   with mode 0600 in a private directory. Store it on encrypted storage and manage retention:
   it includes sensitive financial data and encrypted bank sessions.
3. Restore it into a uniquely named temporary database on the same PostgreSQL server. Run the
   candidate image's standalone `ReleaseMigrationCli migrate`, then `verify`, against that copy.
   The candidate also validates every ORM-mapped table/column and the required `account_type` enum
   labels, including `CREDIT_CARD`. A missing physical object fails even if history claims success.
   No Spring server, scheduler, banking request, or secret-bootstrap entrypoint starts.
4. Only after clone migration and verification pass, migrate and verify the live database with
   the same image. Flyway executes packaged SQL, with strict validation, out-of-order migrations,
   and the existing narrowly matched legacy-renumbering callback. No baseline, clean, or broad
   history repair is attempted.
5. Recreate only `app` with the pinned image and probe `/api/auth/me` without credentials. A bounded
   wait must receive HTTP **401**. Static HTML 200, redirects, gateway 502, and connection failure
   never report success.
6. Drop the temporary database and remove temporary credentials/configuration. Retain the backup.

`verify` is read-only and checks the physical ORM schema and enum labels as well as every packaged
migration against successful applied history. It
rejects pending, ignored, failed, missing, future, and baselined-away migrations, not just
`MAX(version)`. Recording V101–V106 without V100 cannot conceal the gap. `migrate` fills missing
migrations through Flyway; rerunning it does not execute already-applied SQL. A clone migration also
catches untracked manual objects that collide with the authoritative SQL. A schema with falsely
recorded history requires an independent audit: checksums cannot prove correctness of manual DDL.

Dump, restore, candidate CLI, or clone validation failures leave the current application running
and do not migrate the live schema. The rehearsal database contains sensitive production data too;
it is dropped on normal exit or interruption. After a host crash, inspect and remove stale copies.
Never publish dumps, complete container environment, SQL values, or banking credentials.

### Scope and limits

This is not a zero-downtime promise. Live migrations must remain compatible with the old running
binary (expand/contract); destructive changes require a planned offline upgrade. Flyway commits
successful migrations individually, so a later live failure may leave earlier changes applied even
though the old app was not replaced. The clone is a consistent snapshot, not a lock on ongoing
production writes. This tool changes only `app`; install/start matching sidecars separately and keep
`APP_SIDECAR_API_KEY` synchronized. A fresh installation still needs its sidecars and first-launch
configuration; the migration task itself starts no sidecars.

For manually patched issue #195 databases, rehearse a reviewed incident-specific reconciliation
on a restored copy. Do not copy the old ad-hoc SQL, insert only the highest history version, or
use `baseline`/`repair` as a shortcut. The incident's manual tables lacked CHECK constraints, and
V100 was absent even though startup succeeded. Restore a known-good pre-drift database or prove
all migration effects identical before recording their history. The workflow fails closed rather
than guessing that reconciliation.

## Backup, rollback, and API verification

Also securely back up `picsou_data` and operator configuration/key files before upgrading. A database
archive does not preserve the crypto key needed to decrypt stored credentials. Record the current
app image ID and retain it locally; do not prune it during upgrade. No automatic production restore
is attempted, because it would silently discard writes made since backup.

- **Preflight failure:** leave the old API running. Investigate and reconcile drift on a restored
  copy. Rerun without bypassing the gate or disabling Hibernate validation.
- **Live migration failure:** retain the backup and inspect migration history with an approved
  diagnostic plan. Do not assume earlier migrations were rolled back or blindly retry manual SQL.
- **API failure after cutover:** restore the recorded old app image only if that binary is compatible
  with the upgraded schema. Test this against the migrated clone first. Future-history validation
  may prevent arbitrary downgrade. Verify the API again.
- **Database restore needed:** schedule downtime, block writes and stop app/schedulers. Restore the
  retained custom-format archive into a clean database with `pg_restore --exit-on-error --no-owner
  --no-acl`, reconnect the matching old image, and preserve its crypto-key volume. Rehearse on a
  separate database first. Explicitly approve loss of writes after backup before reopening access.

Independent verification through the actual proxy:

```sh
curl --silent --show-error --max-time 10 --output /dev/null \
  --write-out '%{http_code}\n' https://picsou.example.internal/api/auth/me
# Expected without authentication: 401. Frontend-only 200 does not count.
```

Inspect history with `docker compose ... exec -T db psql`, resolving credentials inside the database
container. Do not put passwords in command arguments or print `compose config` into reports.

## Tests

- `python3 -m pytest docker/tests/test_deploy.py -v`: real shell script exercised against command
  doubles, checking ordering, failure isolation, cleanup, image pinning, and real API readiness gates.
- `ReleaseMigrationCliTest`: real PostgreSQL migration chain, repeated migration, lower-version gaps,
  incompatible history, and drift. Backend CI requires Docker so this coverage cannot silently skip.
- `PICSOU_RELEASE_TEST_IMAGE=<built-image> python3 -m pytest docker/tests/test_deploy_integration.py -v -s`:
  full isolated production Compose smoke test using synthetic data. It covers the packaged JAR via
  Spring Boot's `PropertiesLauncher`, normal entrypoint/API 401, repeat deployment with data preserved,
  missing physical schema and future-history failures that leave the running API untouched. CI builds
  the actual release image and runs it. With no image supplied, only this expensive integration test skips.
- Migration CLI errors return nonzero and redact raw JDBC/SQL details; sensitive diagnostics require
  a separate controlled investigation.

Related: [Docker deployment](./docker-deployment.md), [Flyway schema ownership](../decisions/2026-01-01-flyway-schema-ownership.md).
