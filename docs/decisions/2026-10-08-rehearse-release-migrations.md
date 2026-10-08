# ADR: Rehearse release migrations before replacing the production API

> Date: 2026-10-08
> Status: Active

## Context

Issue #195 replaced the application with code requiring migrations that production had not applied.
Flyway had been disabled as a workaround for old 1.1.0 migration renumbering. Frontend HTTP 200 hid
backend HTTP 502. A manual recovery later omitted V100's enum value and CHECK constraints.
Startup/Hibernate validation and a maximum history version are each insufficient on their own.

## Decision

Keep [Flyway schema ownership](./2026-01-01-flyway-schema-ownership.md). Introduce a standalone,
Spring-context-free migration/verification entry point in the application JAR, and make
`docker/deploy.sh` the authoritative production Compose upgrade path.

Before app cutover, take a retained PostgreSQL custom-format backup, restore it into a temporary
database, and rehearse the candidate image's migration chain there. Validate its complete applied
history, ORM-mapped schema, and required native account-type enum labels. Only then migrate and
verify production and replace the app with the same immutable image ID. Readiness requires an
unauthenticated HTTP 401 from `/api/auth/me` through the real proxy.

Application-startup Flyway remains enabled as defence in depth. The explicit CLI never cleans,
baselines, or broadly repairs history. Existing narrowly matched legacy renumbering remains
supported. Manual drift needs a reviewed reconciliation on a restored copy, not automatic guesses.

## Alternatives considered

- **Boot-only migrations:** failure is discovered after replacing the working API.
- **Maximum version / Hibernate-only gate:** misses lower-version gaps or enum labels respectively.
- **Manual recovery SQL:** not authoritative, and previously missed constraints and an enum value.
- **Automatic production restore on failure:** loses writes since backup without operator approval.
- **Full application boot on the clone:** starts schedules/provider integrations against copied data;
  a standalone ORM validation instead checks mappings without an application context.

## Consequences

- Upgrades need backup/clone space and database create/drop privileges.
- Physical validation checks mapped schema and required enum labels, not every business-data invariant
  or every index/CHECK constraint; falsified history still requires audit.
- Clone rehearsal does not lock concurrent production writes. Live migrations must be compatible
  with the old binary; destructive changes need planned downtime.
- Successful Flyway migrations may commit before a later live migration fails. No blanket rollback
  is implied. Restoring a backup is an explicit downtime/data-loss decision.
- Sidecar rollout and secret synchronization remain separate operator responsibilities.
- CI tests the shell gate and the actual packaged release image against real PostgreSQL and Nginx.

See [the release runbook](../features/release-deployment.md) for backup, rollback and verification.
