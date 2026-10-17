# ADR: Flyway Schema Ownership

> Date: 2026-01-01
> Status: ✅ Active

## Context

Picsou uses PostgreSQL 16 for persistence with JPA/Hibernate entities. The database schema evolves over time as new features are added (new entities, columns, indexes). Schema changes must be explicit, versioned, and reversible to prevent data loss and ensure consistency across environments.

## Decision

Use Flyway for database schema migrations. Hibernate's `ddl-auto` is set to `validate` only -- Hibernate verifies that the schema matches entities but never creates or modifies tables. All schema changes are made via Flyway migration files in `backend/src/main/resources/db/migration/`.

Migration naming convention: `V{n}__description.sql` (e.g., `V1__initial_schema.sql`, `V13__add_goal_month_override.sql`).

## Alternatives considered

### Hibernate auto-ddl (`ddl-auto: update`)

- **Pros**: Zero-effort schema management; entities define the schema
- **Cons**: No versioning; cannot rename columns or tables; no rollback; can drop data in edge cases; unreliable for production

### Liquibase

- **Pros**: XML/YAML/JSON migrations; rollback support; change log parameters
- **Cons**: Heavier configuration; XML verbosity; Flyway is simpler for SQL-native teams

### Manual SQL scripts (no migration tool)

- **Pros**: Full control; no framework dependency
- **Cons**: No version tracking; no automatic validation; easy to forget running a script

## Reasoning

Flyway provides the right balance of simplicity and control. Plain SQL migrations are easy to write and review. The versioned approach ensures all environments (dev, staging, production) apply migrations in the same order. Setting `ddl-auto: validate` catches entity-schema mismatches at startup, providing a safety net without risking data.

## Trade-offs accepted

- Every schema change requires a new migration file (even small additions like a single column)
- Migrations are one-directional; rollback requires writing a new migration (Flyway Community edition does not support undo)
- Developers must remember to create migrations instead of relying on auto-DDL

## Consequences

- 13 migration files exist as of now (V1 through V13)
- New columns/tables always require a new `V{n}__description.sql` file
- `application.yml` sets `spring.jpa.hibernate.ddl-auto: validate`
- Flyway runs automatically on application startup. Production upgrades now rehearse the same
  packaged migrations before app replacement; see [the release deployment ADR](./2026-10-08-rehearse-release-migrations.md).
- Enums are defined as PostgreSQL enums via migrations (e.g., `CREATE TYPE account_type AS ENUM (...)`)

Note: This decision was made during initial development. This ADR is documented retroactively.
