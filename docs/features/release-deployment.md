# Feature: Migration-safe release deployment

> Last updated: 2026-10-16

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

The script refuses to run unless the addressed Compose project already has a `db` and an `app`
container, and prints the project name and database container it backs up. A forgotten `-p` or
`COMPOSE_PROJECT_NAME` would otherwise address a new, empty project: back up and migrate an empty
database, start a second app, and possibly probe the untouched production URL. For a first
installation only, put `--fresh` before the image to allow missing containers:
`bash docker/deploy.sh --fresh IMAGE URL BACKUP_DIR [flags...]`.

Preserve the existing Compose project name and all its flags. Additional Compose flags follow the
three required arguments, for example `--profile tls` and `-f docker/docker-compose.no-http.yml`.
Supply the real origin of this application, not another deployment behind the same proxy.
Do not include credentials, query parameters, or fragments in that URL; the probe rejects them
and disables curl's default configuration so it cannot silently inherit authentication settings.
The image reference is resolved once to an immutable local ID; every phase uses that same ID.
An already-built `sha256:` image ID can be supplied directly without a registry pull.
All deployments of one project must use the same backup directory so its lock serializes them.
The backup directory must be private: a missing one is created with mode 0700, and an existing one
with any group or other permission is refused rather than changed. Pass a dedicated subdirectory
such as `/var/backups/picsou`, not a shared directory like `/var/backups` or `$HOME`.

## Ordered gates

1. Lock deployment, require the existing `db` and `app` containers (unless `--fresh`), and resolve
   the candidate image to a runnable local image ID. Start only the database if needed.
2. Check free space: the backup directory and the PostgreSQL data directory must each have at
   least the current database size free. Then take a full custom-format PostgreSQL backup. The
   archive is retained on success and failure, with mode 0600 in a private directory. Store it on
   encrypted storage and manage retention: it includes sensitive financial data and encrypted bank
   sessions. Backups are never pruned by the script. The image ID of the app that is running before
   the upgrade is written next to it as `picsou-<stamp>-<pid>.previous-image` and printed as
   `Previous app image:`.
3. Restore it into a uniquely named temporary database on the same PostgreSQL server. Run the
   candidate image's standalone `ReleaseMigrationCli migrate`, then `verify`, against that copy.
   The candidate also validates every ORM-mapped table/column, with Spring Boot's naming
   strategies, and the labels of every PostgreSQL enum an entity maps (for example
   `account_type` with `CREDIT_CARD`). A missing physical object fails even if history claims success.
   No Spring server, scheduler, banking request, or secret-bootstrap entrypoint starts.
4. Only after clone migration and verification pass, migrate and verify the live database with
   the same image. Flyway executes packaged SQL, with strict validation, out-of-order migrations,
   and the existing narrowly matched legacy-renumbering callback. No baseline, clean, or broad
   history repair is attempted.
5. Recreate only `app` with the pinned image. Require that this project's `app` container is
   running the candidate image ID, then probe `/api/auth/me` without credentials. A bounded wait
   must receive HTTP **401**. Static HTML 200, redirects, gateway 502, and connection failure never
   report success.

On every exit, success or failure, an `EXIT` trap drops the temporary database and removes the
temporary credentials/configuration. The backup is retained. A `SIGKILL` or power loss skips the
trap: it can leave `<backup dir>/.deploy-*/override.yml` (0600, contains the database password)
and a `picsou_deploy_*` database behind. Remove both after such an interruption.

`verify` is read-only and checks the physical ORM schema and enum labels as well as every packaged
migration against successful applied history. It
rejects pending, ignored, failed, missing, future, and baselined-away migrations, not just
`MAX(version)`. Recording V101–V106 without V100 cannot conceal the gap. `migrate` fills missing
migrations through Flyway; rerunning it does not execute already-applied SQL. A clone migration also
catches untracked manual objects that collide with the authoritative SQL. A schema with falsely
recorded history requires an independent audit: checksums cannot prove correctness of manual DDL.

On failure the CLI prints a redacted diagnosis after the generic error: the command, the exception
class chain, the failing migration version and script, Flyway error codes such as
`CHECKSUM_MISMATCH`, the Hibernate schema-validation message (table and column names), missing enum
labels, and the SQLState. It never prints the JDBC URL, user name, password, SQL text or row data;
Flyway and Hibernate logging stays off because it can contain SQL.

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
archive does not preserve the crypto key needed to decrypt stored credentials. The script records
the previous app image ID in the backup directory; keep that image locally and do not prune it
during the upgrade.

The cutover passes the database user and password to the new `app` container through a temporary
Compose override, so they appear in its environment (`docker inspect`) the same way as values from
`.env`. They differ from the checked-in Compose specification only in form; a later plain
`docker compose up` recreates `app` from `.env` as usual. Anyone allowed to run `docker inspect`
already has full control of the host's containers. No automatic production restore
is attempted, because it would silently discard writes made since backup.

- **Preflight failure:** leave the old API running. Investigate and reconcile drift on a restored
  copy. Rerun without bypassing the gate or disabling Hibernate validation.
- **Live migration failure:** retain the backup and inspect migration history with an approved
  diagnostic plan. Do not assume earlier migrations were rolled back or blindly retry manual SQL.
- **API failure after cutover:** restore the image in `picsou-<stamp>-<pid>.previous-image` only if that binary is compatible
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
  doubles, checking ordering, failure isolation, cleanup, image pinning, real API readiness gates,
  refusal of an unknown Compose project, the post-cutover image check, the backup-directory mode,
  the previous-image record, and the free-space check. CI also runs `shellcheck -x docker/deploy.sh`.
- `ReleaseMigrationCliConfigurationTest` (no Docker): Spring Boot naming-strategy parity, the
  discovered named enums, and redaction of an unreachable connection.
- `ReleaseMigrationCliTest`: real PostgreSQL migration chain, repeated migration, lower-version gaps,
  incompatible history, and drift. Backend CI requires Docker so this coverage cannot silently skip.
- `PICSOU_RELEASE_TEST_IMAGE=<built-image> python3 -m pytest docker/tests/test_deploy_integration.py -v -s`:
  full isolated production Compose smoke test using synthetic data. It covers refusal of an unknown
  project without `--fresh`, the packaged JAR via
  Spring Boot's `PropertiesLauncher`, normal entrypoint/API 401, repeat deployment with data preserved,
  missing physical schema and future-history failures that leave the running API untouched. CI builds
  the actual release image and runs it. With no image supplied, only this expensive integration test skips.
- Migration CLI errors return nonzero with an allow-listed diagnosis; tests assert that it names the
  table, column, migration or enum while never containing the JDBC URL, user name or password.
- Not covered: an image pulled from a registry under the containerd image store. The script checks
  that the resolved ID is runnable and fails early otherwise; pass the local `sha256:` image ID then.

Related: [Docker deployment](./docker-deployment.md), [Flyway schema ownership](../decisions/2026-01-01-flyway-schema-ownership.md).
