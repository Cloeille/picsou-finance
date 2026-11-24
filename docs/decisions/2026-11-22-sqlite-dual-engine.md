# ADR: Dual-Engine Database Architecture (Embedded SQLite + PostgreSQL)

> Date: 2026-11-22
> Status: 🟢 Accepted (Implemented)

## Context

In [Rewrite the backend in Rust](2026-11-21-rust-backend.md), Picsou chose **PostgreSQL everywhere** so the Java-to-Rust switchover remained invisible and reversible while preserving Flyway history and all 108 HTTP contract scenarios. With the Rust backend now at 108/108 contract parity and 1,345/1,345 tests green, two deployment constraints motivate adding an **embedded SQLite engine** alongside PostgreSQL:

1. **Desktop app footprint and startup**: Today `desktop/src-tauri` still bundles and spawns a PostgreSQL child process (~1 s startup + bundled binaries + local TCP port lifecycle). Embedding SQLite inside the single Rust binary shrinks the desktop bundle toward ~50 MB and eliminates the external database process entirely.
2. **Zero-dependency single-container web deployment**: Solo self-hosters who do not want to manage a separate PostgreSQL container should be able to launch Picsou with a single mounted volume and `DATABASE_URL=sqlite:///data/picsou.db`.

At the same time, **PostgreSQL MUST remain a first-class, zero-regression engine** for existing Docker Compose installs, Kubernetes/Helm deployments with external databases, and concurrent multi-member households. No existing PostgreSQL database or Flyway migration may break.

## Measured Inventory of PostgreSQL-Specific SQL in `backend-rs/src/`

An automated scan of all **394 `.rs` files** in `backend-rs/src/` (**595 `sqlx::query*` call sites** across **119 files**, **836 `PgPool`/`PgConnection`/`PgExecutor` references** across **157 files**) shows that the PostgreSQL dialect surface is smaller and cleaner than expected:

| Construct | Occurrences | Files | SQLite Compatibility / Strategy |
| --- | ---: | ---: | --- |
| `ANY($n)` array bindings | 53 | 28 | Rewrite via `json_each(?)` or `QueryBuilder` `IN (?, ...)` helper |
| `RETURNING` clause | 52 | 36 | **Native in SQLite >= 3.35** (bundled SQLite is 3.46+) |
| `NOW()` | 43 | 17 | Pass `AppClock::now()` parameter (preferred) or register `now()` UDF |
| `ON CONFLICT ... DO UPDATE/NOTHING` | 36 | 26 | **Native in SQLite >= 3.24** |
| `FOR UPDATE` / `LOCK TABLE` | 15 | 13 | 14 `FOR UPDATE` + 1 `LOCK TABLE` (`db/flyway.rs`). Strip in SQLite (`BEGIN IMMEDIATE` serializes writers) |
| PG type casts (`::type`) | 88 | 27 | 62 `::text`, 14 PG enum casts (`::category_kind`, `::account_type`, `::recurring_cadence`, `::recurring_status`), 8 `::bigint`, 2 `::timestamptz`, 1 `::date`, 1 `::varchar`, **0 `::numeric`** — strip or normalize in dialect helper |
| `pg_advisory_lock` / `information_schema` / `pg_type` / `pg_enum` | 7 | 2 | Isolated to `db/flyway.rs` and `db/schema.rs` (replaced by `db/sqlite/migrate.rs`) |
| SQL `SUM()` on amounts | 5 (+1 int) | 4 | Only 5 decimal `SUM(amount)` queries (`ledger/transactions.rs` ×3, `family/view.rs` ×1, `budget/repo.rs` ×1) — map directly to `dec_sum(amount)` |
| `DISTINCT ON` | 1 | 1 | `ledger/prices.rs` only — rewrite with `ROW_NUMBER() OVER (PARTITION BY ticker ORDER BY date DESC, id DESC) = 1` (works on both PG and SQLite) |
| `INTERVAL` arithmetic | **0** | **0** | Already computed in Rust via `chrono` + `AppClock` |
| `JSONB` / `::jsonb` | **0** | **0** | Zero JSONB columns in the entire 67-table schema |
| `ILIKE` | **0** | **0** | Not used |

Query volume by module (top modules): `ledger` (98 queries / 13 files), `sync` (79 / 16), `wealth` (74 / 13), `identity` (47 / 7), `accounts` (44 / 15), `connectors` (41 / 10), `budget` (38 / 9), `family` (24 / 3), `setup_auth` (24 / 6), `auth` (20 / 3), `transfer` (20 / 5), `db` (17 / 2), `market` (16 / 3), `mcp` (13 / 3), `wiring.rs` (12 / 1), `admin` (11 / 5), `jobs` (4 / 2), `actuator.rs` (1), `ai` (1).

## Decision

### 1. Dual-Engine `Db` Pool Abstraction

Introduce a dual-engine database boundary behind `backend-rs/src/db/` selected at startup from configuration (`DATABASE_URL=sqlite://...` vs `SPRING_DATASOURCE_URL=jdbc:postgresql://...` / `postgres://...`):

- **PostgreSQL engine**: keeps `sqlx::PgPool`, `db::flyway` (`V1..V155+` in `flyway_schema_history`), and `db::schema::check` completely intact.
- **SQLite engine (`db::sqlite::SqliteDb`)**: built on `rusqlite` (`features = ["bundled", "limits", "functions"]`), which is **already** a workspace dependency (zero new crates, zero `Cargo.lock` churn on `backend-rs` or `desktop/src-tauri`). Every connection configures:
  - `PRAGMA journal_mode = WAL` (concurrent readers alongside single writer)
  - `PRAGMA foreign_keys = ON` (verified on open)
  - `PRAGMA busy_timeout = 5000`
  - `PRAGMA synchronous = NORMAL`
  - `PRAGMA temp_store = MEMORY`
  - Blocking SQLite I/O runs on Tokio's blocking pool via `SqliteDb::interact`.
- **Dual-pool runtime enum (`Db`)** (Phase 1): `pub enum Db { Postgres(PgPool), Sqlite(SqliteDb) }` with engine-aware helpers for transactions (`BEGIN` vs `BEGIN IMMEDIATE`), `ANY($n)` vs `IN (...)` / `json_each`, and `SUM(col)` vs `dec_sum(col)`.

### 2. Exact Financial Precision in SQLite (Zero Binary Floats)

Picsou enforces zero `f32`/`f64` for money, quantities, prices, and rates (`foundation::no_floats`). SQLite has no native fixed-point decimal type (`REAL` is IEEE-754 binary float64 and introduces drift such as `0.1 + 0.2 != 0.3` and `14.1 + 14.2 + 14.3 + 57.4 != 100`).

Therefore, in SQLite:

1. **Every PostgreSQL `NUMERIC` column (84 `NUMERIC` column instances across 71 distinct column names in the 67 tables) is stored as canonical `TEXT`** preserving exact `rust_decimal::Decimal` precision and scale (`to_sqlite_text` / `from_sqlite_text`). SQLite `REAL` columns are strictly forbidden and rejected by both the schema parity verifier and the decimal codec (`DecimalCodecError::FloatValueForbidden`).
2. **Exact `rust_decimal::Decimal` UDFs are registered on every connection** with `SQLITE_UTF8 | SQLITE_DETERMINISTIC | SQLITE_INNOCUOUS` flags so they are valid in queries, views, and `CHECK` constraints:
   - Scalars: `dec_add(a, b)`, `dec_sub(a, b)`, `dec_mul(a, b)`, `dec_div(a, b, scale)` (plus 2-arg exact `dec_div(a, b)`), `dec_cmp(a, b)`, `dec_abs(a)`, `dec_neg(a)`
   - Aggregate: `dec_sum(x)` (matching PostgreSQL `SUM(numeric)` NULL semantics)
   - Write rounding: PostgreSQL rounds a value to its `NUMERIC(p, s)` scale when it stores it. The
     writer connection gets one `TEMP` trigger per table (`decimal::install_write_rounding`, run by
     `SqliteDb::migrate`) that rewrites a value with more decimals through `dec_round(x, s)`, half
     up, so sums and checks add the same rounded values on both engines. A value that is not a
     decimal is left as it is. The triggers belong to the connection, not to the file.
3. **Physical type mapping** across all 739 columns:
   - `int8`, `int4`, `int2` → `INTEGER` (`INTEGER PRIMARY KEY AUTOINCREMENT` for `BIGSERIAL` / `IDENTITY` PKs)
   - `bool` → `INTEGER` with `CHECK (col IN (0, 1))`
   - `numeric` → `TEXT` (canonical `Decimal`)
   - `timestamptz`, `timestamp` → `TEXT` (RFC 3339 UTC microsecond string)
   - `date` → `TEXT` (`YYYY-MM-DD`)
   - `uuid` → `TEXT` (canonical lowercase hyphenated UUID)
   - `bytea` → `BLOB`
   - `varchar`, `text`, and all 8 PostgreSQL enums (46 labels) → `TEXT` (with `CHECK (col IN (...))` for enums)

### 3. Schema Parity & Migration Strategy

Replaying 151 historical PostgreSQL-dialect Flyway scripts (`V1..V155`, including `CREATE TYPE ... AS ENUM`, `DO $$ ... $$`, `ALTER COLUMN TYPE`, and legacy renumbering) directly on SQLite is brittle and unnecessary.

Instead:

1. **Consolidated SQLite baseline (`V1`, `backend-rs/src/db/sqlite/schema.sql`)**: a clean, hand-verified SQLite DDL covering **all 67 application tables and 739 columns** from `backend-rs/src/db/schema.tsv`, plus all 66 primary keys, 80 foreign keys (with `ON DELETE` actions), 40 `UNIQUE` constraints, 79 `CHECK` constraints (using SQLite `GLOB` for regex checks and `dec_cmp`/`dec_add` for exact numeric checks), 8 enum `CHECK` constraints, and all partial/expression indexes.
2. **Idempotent SQLite migrator (`db::sqlite::migrate`)**: tracks applied scripts in `picsou_sqlite_migrations` with normalized SHA-256 checksums, validates checksums on startup, and exposes `verify()` to enforce 100% table/column parity against `schema.tsv` and zero `REAL` columns.
3. **Forward migrations (`V156+`)**: once SQLite serves live traffic, every new schema migration ships a paired PostgreSQL Flyway file (`backend/src/main/resources/db/migration/V<n>__*.sql`) and SQLite migration (`backend-rs/src/db/sqlite/migrations/V<n>__*.sql`). Both CI gates — `foundation::migrations::the_expected_schema_is_the_one_the_migrations_build` and `sqlite_foundation::schema_parity_matches_postgresql_schema_tsv` — enforce 100% parity on every PR.
4. **Reference seed separation**: the 4 reference tables seeded by Flyway (`app_setting`, `crypto_asset`, `merchant_brand`, `merchant_alias`) remain separate from `schema.sql` DDL so `export-sqlite` mirrors any PostgreSQL database 1:1 without seed collisions, while fresh SQLite bootstrap (Phase 2) loads the shared seed catalog idempotently.

### 4. Lossless PostgreSQL → SQLite Exporter (`picsou export-sqlite <output.db>`)

Existing desktop and Docker users must be able to migrate their data from PostgreSQL to SQLite without losing a cent or breaking a foreign key:

- Implemented in `db::sqlite::export_pg::export_pg_to_sqlite(pg, path)` and exposed via `picsou export-sqlite <output.db>`.
- Reads all 67 tables inside a single PostgreSQL `REPEATABLE READ, READ ONLY` transaction (consistent MVCC snapshot across all tables).
- Converts every `NUMERIC` via `col::text` validated through `rust_decimal::Decimal` (lossless precision & scale).
- Loads into a temporary staging file in one atomic transaction (`PRAGMA foreign_keys = OFF` during load for arbitrary/self-referential FK graphs, then `PRAGMA foreign_keys = ON`).
- Before promoting the staging file to `<output.db>`, verifies:
  1. `PRAGMA foreign_key_check` returns **0 violations**,
  2. `PRAGMA integrity_check` returns **`"ok"`**,
  3. Every single one of the 67 tables has an exact **1:1 row count** match with PostgreSQL,
  4. `migrate::verify` passes,
  5. `PRAGMA wal_checkpoint(TRUNCATE); PRAGMA journal_mode = DELETE;` produces a clean single-file `.db` with no orphan `-wal`/`-shm` sidecars.
- Refuses to overwrite an existing output file and removes staging files on any error.

### 5. Phased Rollout Plan (Zero Regressions Across 1,345 Tests & 108 Contracts)

- **Phase 0 — SQLite Foundation (this ADR & slice)**: `db::sqlite` (`SqliteDb`, exact `Decimal` codec + UDFs, 67-table `schema.sql`, idempotent migrator + parity verifier, live PG→SQLite exporter + CLI, integration test suite). **Zero changes to `state.db: PgPool` or the 119 query-bearing domain files.** All 1,345 PostgreSQL tests remain untouched and green.
- **Phase 1 — Dual `Db` Abstraction & Dialect Normalization**: introduce `Db` enum + query helper primitives; rewrite the 1 `DISTINCT ON` query (`ledger/prices.rs`), replace SQL `NOW()` calls with `AppClock` bindings, and normalize the 5 decimal `SUM()` calls. Keep all 1,345 PG tests green at every commit.
- **Phase 2 — Domain-by-Domain Dual-Engine Enablement**: migrate repositories module-by-module in small PRs (starting from smallest: `ai`/`actuator`/`jobs`/`admin` → `auth`/`setup_auth`/`identity`/`family` → `budget`/`market`/`transfer`/`mcp` → `accounts`/`wealth`/`connectors`/`sync`/`ledger`), adding dual-engine test coverage per domain plus idempotent reference seed bootstrap for fresh SQLite DBs.
- **Phase 3 — Desktop & Single-Container Cutover**: enable `DATABASE_URL=sqlite://...` in `Config::load`, auto-migrate existing desktop PostgreSQL clusters via `export_pg_to_sqlite` (keeping a verified PG backup until user confirmation), and drop the bundled PostgreSQL binaries from `desktop/src-tauri`.
- **Phase 4 — Dual-Engine CI Matrix**: run the full integration suite and all 108 HTTP contract scenarios against both PostgreSQL and SQLite on every PR.

## Alternatives Considered

### Store amounts in SQLite as `REAL` (IEEE-754 float64) or scaled `INTEGER` (micro-units)

- **Pros**: Native SQLite `SUM()` and `<`/`>` operators without custom functions.
- **Cons**: `REAL` violates Picsou's core financial invariant (`foundation::no_floats`) and breaks exact decimal checks (`14.1 + 14.2 + 14.3 + 57.4 == 100`). Fixed-scale `INTEGER` overflows 64-bit signed integers on large `NUMERIC(19,8)` crypto/portfolio balances and loses per-column scale fidelity required by the 108 contract tests.

### `sqlx::AnyPool` for runtime SQLite + PostgreSQL dispatch

- **Pros**: Single `sqlx` trait surface.
- **Cons**: `sqlx::Any` drops native `rust_decimal::Decimal`, `uuid::Uuid`, `chrono::DateTime`, and PostgreSQL enum/array bindings, pulls in `libsqlite3-sys` via `sqlx-sqlite` (duplicating `rusqlite`'s bundled SQLite and bloating compile times), and still cannot register custom `rust_decimal::Decimal` UDFs inside `CHECK` constraints cleanly.

### Drop PostgreSQL and move to SQLite everywhere

- **Pros**: One database engine to maintain.
- **Cons**: Breaks existing Docker Compose production volumes, breaks the Helm chart's external PostgreSQL option, and hurts concurrent multi-user web deployments.

### Rewrite queries on top of an ORM (Diesel / SeaORM)

- **Pros**: Abstracts SQL dialect differences automatically.
- **Cons**: Massive rewrite across 595 hand-tuned SQL queries (many with subtle Hibernate/contract-parity ordering, locking, and scale semantics), high risk of regressions across 108 contracts, and heavy compile-time bloat.

## Reasoning

Using the already-bundled `rusqlite` crate with canonical `TEXT` storage and deterministic/innocuous `rust_decimal::Decimal` UDFs gives **exact mathematical parity with PostgreSQL `NUMERIC`** at zero extra crate cost, while the phased rollout and automated `schema.tsv` parity gate guarantee zero regressions on PostgreSQL throughout the transition.

## Trade-offs Accepted

- Two migration tracks from `V156+` onward (PostgreSQL Flyway SQL + SQLite SQL), mitigated by automated schema parity tests on every CI run.
- Tables with numeric `CHECK` constraints (`account_ownership`, `budget`, `goal_allocation`, `member_allocation_profile`, `member_profile`, `peer_loan_repayment`, `peer_loan_terms`, `scpi_position`) require connections with `dec_cmp`/`dec_add` registered for writes and `PRAGMA integrity_check`; read-only `SELECT` inspection from external `sqlite3` CLI tools works without UDFs.
- SQLite write concurrency is single-writer (serialized via WAL + `busy_timeout = 5000` + `BEGIN IMMEDIATE`), which is ideal for desktop and single-family installs while PostgreSQL remains recommended for high-concurrency multi-user deployments.

## Consequences

- `backend-rs/src/db/sqlite/` provides the production SQLite foundation (`SqliteDb`, `decimal`, `migrate`, `schema.sql`, `export_pg`).
- `picsou export-sqlite <output.db>` is available in the server binary for lossless PostgreSQL-to-SQLite migration.
- `backend-rs/tests/integration/sqlite_foundation.rs` enforces 67-table / 739-column parity against `schema.tsv`, exact `Decimal` precision, and live PostgreSQL-to-SQLite round-trip fidelity on every test run.
- Existing PostgreSQL code paths, `state.db: PgPool`, Flyway history, and all 1,345 PostgreSQL tests remain completely untouched in Phase 0.
