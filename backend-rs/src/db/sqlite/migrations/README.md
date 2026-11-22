# SQLite V2+ Migrations

`../schema.sql` is the frozen **V1** baseline schema (matching PostgreSQL Flyway `V1..V155`).
Once released, **never edit `schema.sql`** — existing user databases have recorded its SHA-256
checksum in `picsou_sqlite_migrations`, and `v1_baseline_checksum_is_frozen` will fail CI on any
modification.

## Adding a new SQLite migration (V2+)

1. Create an append-only SQL file in this directory named `V<n>__<slug>.sql` (for example,
   `V2__add_example_column.sql`), where `<n>` is the next sequential integer.
2. Register the migration at the end of `migrations()` in `../migrate.rs` using
   `include_str!("migrations/V<n>__<slug>.sql")` and choose the appropriate [`MigrationMode`]:
   - **`MigrationMode::Transactional`**: use for standard additive DDL (`CREATE TABLE`,
     `CREATE INDEX`, `ALTER TABLE ... ADD COLUMN`, data backfills). The migrator wraps the script
     and the `picsou_sqlite_migrations` insert in a single `BEGIN IMMEDIATE ... COMMIT` transaction.
   - **`MigrationMode::TableRebuild`**: use whenever SQLite requires the 12-step table-rebuild
     procedure (altering column types, nullability, `CHECK` constraints, defaults, or dropping/renaming
     columns referenced by foreign keys). The migrator:
     1. Runs `PRAGMA foreign_keys = OFF;` **outside** the transaction, guarded so
        `PRAGMA foreign_keys = ON;` is unconditionally restored even on error or panic.
     2. Opens `BEGIN IMMEDIATE` and executes the migration script.
     3. Runs `PRAGMA foreign_key_check;` inside the transaction; if any violation is found, it rolls
        back the transaction and returns `SqliteMigrationError::Validation`.
     4. Records the migration in `picsou_sqlite_migrations`, commits, and restores
        `PRAGMA foreign_keys = ON;`.
3. Never use `REAL`, `FLOAT`, or `DOUBLE` for monetary/numeric columns — use `TEXT` with exact
   `dec_*` helpers, matching `schema.sql`.
4. Keep the corresponding PostgreSQL Flyway migration and `backend-rs/src/db/schema.tsv` updated in
   lockstep so `SqliteDb::verify_schema()` passes with zero drift.
