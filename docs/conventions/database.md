# Convention: Database

## Schema ownership

**Migrations own the schema.** The backend never creates or alters a table outside a migration.
It applies the pending migrations at startup, as Flyway did for the Java backend: same file
naming, same checksums, same `flyway_schema_history` rows (`backend-rs/src/db/flyway.rs`).

Two engines, two sets of migrations:

- **PostgreSQL**: `backend-rs/migrations/V{n}__description.sql`.
- **SQLite** (desktop app, single-container Docker): `backend-rs/src/db/sqlite/schema.sql` is the
  frozen baseline, equal to PostgreSQL `V1..V155`. Later changes go in
  `backend-rs/src/db/sqlite/migrations/`
  ([how to add one](../../backend-rs/src/db/sqlite/migrations/README.md)).

A schema change needs both, and `backend-rs/src/db/schema.tsv`, the schema the release CLI
(`picsou verify`) expects. A test keeps that file equal to what the migrations build.

## Migrations

PostgreSQL migration files live in `backend-rs/migrations/`.

### Naming

```
V{n}__description.sql
```

- `V` prefix for versioned migrations.
- `{n}` is a sequential integer (1, 2, ..., 13 currently).
- Double underscore before the description.
- Description in snake_case.

### Existing migrations (V1-V32)

| File | Content |
|------|---------|
| `V1__init_schema.sql` | Core tables: `app_user`, `account`, `balance_snapshot`, `account_type` enum |
| `V2__goals.sql` | `goal` table + `goal_account` join table |
| `V3__requisitions.sql` | `requisition` table for bank connections |
| `V4__trade_republic_session.sql` | `trade_republic_session` (dropped by V149; sessions moved to `sidecar_session`) |
| `V5__tr_refresh_token.sql` | Refresh token column for TR sessions |
| `V6__rename_gocardless_column.sql` | Rename `gocardless_account_id` to `external_account_id` |
| `V7__goal_month_override.sql` | `goal_month_override` table |
| `V9__crypto_exchange_session.sql` | `crypto_exchange_session` table |
| `V10__wallet_address.sql` | `wallet_address` table |
| `V11__account_holding.sql` | `account_holding` table |
| `V12__transactions.sql` | `transaction` table |
| `V13__goal_manual_contribution.sql` | `goal_manual_contribution` table |
| `V14__price_snapshot.sql` | `price_snapshot` table (price cache history) |
| `V15__widen_encrypted_columns.sql` | Widen encrypted secret columns for AES-GCM payloads |
| `V16__finary_session.sql` | `finary_session` table |
| `V17__requisition_last_synced_at.sql` | `last_synced_at` column on `requisition` |
| `V18__snapshot_invested_amount.sql` | `invested_amount` column on `balance_snapshot` |
| `V19__real_estate_and_debts.sql` | Real-estate accounts + `debt` linkage |
| `V20__create_family_system.sql` | `family_member`, `sharing_settings`, `shared_resource` |
| `V21__migrate_existing_data.sql` | Backfill `member_id` on all existing financial rows |
| `V22__make_member_id_not_null.sql` | Enforce `member_id NOT NULL` after backfill |
| `V23__bourso_session.sql` | `bourso_session` table (BoursoBank sidecar), folded into `sidecar_session` by V142 |
| `V24__manual_transactions.sql` | Manual transaction support |
| `V25__setup_state.sql` | First-launch Setup Wizard state |
| `V26__setup_audit.sql` | Setup audit log |
| `V27__loan_extra_fields.sql` | Loan amortization fields on `account` |
| `V28__mfa_and_persistent_sessions.sql` | TOTP 2FA + rotating Remember-Me tokens |
| `V29__app_user_token_version.sql` | `token_version` claim column for stateless invalidation |
| `V30__account_soft_delete.sql` | Soft-delete (`deleted_at`) on `account` |
| `V31__price_cleanup_gate.sql` | Gate column controlling price-snapshot cleanup |
| `V32__goal_history_start.sql` | `history_start` column anchoring goal trajectory charts |
| … | Later numbered migrations continue sequentially (V37+) |
| `V119__widen_holding_ticker.sql` | `account_holding.ticker` 30→100, `name` 100→255 (Finary RealT codes) |
| `V121__cic_session.sql` | CIC session, encrypted credentials, and persisted sync status |
| `V122__crypto_asset.sql` | `crypto_asset` registry: curated CoinGecko tickers, plus coins learned at runtime |
| `V142__sidecar_session.sql` | One `sidecar_session` table replaces the ten sidecar session tables (after V124–V127 of CORUM Life and V128 of SSO); rows copied as stored, CORUM stored login and Life ambiguous count included (inconsistent CORUM/Sofidy failure states repaired), per-bank error-code CHECK generated from `SidecarBank` |
| `V145__caisse_epargne_stored_identifier.sql` | `sidecar_session.encrypted_credentials` is also allowed for `CAISSE_EPARGNE`: the encrypted Caisse d'Epargne identifier (never the password) |
| `V146__natixis_sidecar_session.sql` | Natixis Interépargne joins `sidecar_session`: its bank and its error-code branch are added to the CHECKs; no table of its own, so no copy |
| `V147__banque_populaire_sidecar_session.sql` | Banque Populaire joins `sidecar_session`: `region` column with `ck_sidecar_session_region` (set for `BANQUE_POPULAIRE`, null otherwise), bank added to the bank, unsupported-contracts and error-code CHECKs; `banque_populaire_session` (V134) rows copied as stored, then dropped |
| `V148__banque_populaire_stored_identifier.sql` | `sidecar_session.encrypted_credentials` is also allowed for `BANQUE_POPULAIRE`: the encrypted Banque Populaire identifier of the last successful login (never the password) |
| `V149__trade_republic_sidecar_session.sql` | Trade Republic joins `sidecar_session` (bank and error-code CHECKs widened, Banque Populaire kept); `trade_republic_session` dropped, members reconnect once |
| `V150__bnp_sidecar_session.sql` | BNP Paribas / Hello bank! joins `sidecar_session` (bank and error-code CHECKs rebuilt from V149 with `BNP` added); no table of its own, so nothing to copy |
| `V153__louve_invest_sync.sql` | `louve_connection` (one row per member: encrypted token set, status, grant generation, last sync, pending OAuth attempt), like `wise_connection`; `scpi_position.louve_fund_code` + per-member unique index; `LOUVE` provider and `UNSUPPORTED_OWNERSHIP` outcome on `scpi_provider_fund`. The instance's Louve client ids live in `app_setting` (`louve.client-id:<sha-256 of the redirect URI>`), not in a table |

Note: V8 is absent by design (skipped — never rolled into another migration). Numbering after V32 is not contiguous (V33–V36 unused).

### Writing a new migration

1. Create `V{next}__descriptive_name.sql` in `backend-rs/migrations/`, where `{next}` is the next free
   version on an up-to-date target branch, counting `main` and every open `release/*` branch. Another open pull request may take the same number; the one
   that merges second renumbers its own, still unmerged, files after syncing with the target branch, along
   with every test or doc that names them. Keep their relative order and number them after the
   latest version on the target branch: a later migration may depend on an earlier one. A local database that
   already applied the old numbers has to be recreated, which deletes its data and encryption key
   ([how to do it safely](../../CONTRIBUTING.md#before-you-open-a-pull-request)).
   The `every_migration_has_a_unique_version` test fails on a duplicate version.
2. Use plain SQL.
3. Include constraints, indexes, and foreign keys explicitly.
4. For new enums, use `CREATE TYPE ... AS ENUM (...)` at the top of the file.
5. Add the SQLite counterpart and update `schema.tsv` (see above). `make test-backend` runs the
   tests on both engines.

**Never rename or renumber a migration that is on `main` or a release branch, or that any deployed database may have
applied**, even to fix a version collision. Flyway resolves applied rows by version: the old row stops matching any file
and the renamed file runs a second time. When it happened anyway (commit 2839c94 moved 1.1.0's
V80, V81 and V86–V88 to V93–V99), the repair is a startup step that rewrites those history rows
before validation, matching old version, description and checksum together. Merging main into
1.1.0 did it again to the 1.0.x releases' V38 and V54–V64 (#265), and also edited comments in
applied files: a comment change alters the checksum just like a SQL change. See
the legacy renumbering in `backend-rs/src/db/flyway.rs` and
[the upgrade note](../features/docker-deployment.md#upgrading-a-110-install-that-applied-the-old-v80v88-numbering).

Example:

```sql
-- V13__goal_manual_contribution.sql
CREATE TABLE goal_manual_contribution (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    goal_id BIGINT NOT NULL REFERENCES goal(id) ON DELETE CASCADE,
    year_month VARCHAR(7) NOT NULL,
    amount NUMERIC(20, 2) NOT NULL,
    CONSTRAINT uk_goal_manual_contribution_goal_year UNIQUE (goal_id, year_month)
);
```

## Rows and queries

A table row is a plain struct that derives `DbFromRow` (`backend-rs/src/db/`), kept next to the
queries that read and write it:

```rust
#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct Account {
    pub id: i64,
    pub member_id: i64,
    pub name: String,
    #[sqlx(rename = "type")]
    pub account_type: AccountType,
    pub currency: String,
    pub current_balance: Decimal,
    pub last_synced_at: Option<DateTime<Utc>>,
    // …
}
```

Queries are SQL text with `$n` placeholders, run through `crate::db::query`, `query_as` and
`query_scalar`. Those wrappers serve both engines: the SQLite dialect layer
(`backend-rs/src/db/sqlite/dialect.rs`) translates the PostgreSQL syntax, so a query is written
once.

### Key patterns

| Pattern | Convention |
|---------|-----------|
| Monetary values | `NUMERIC(20, 8)` in SQL, `rust_decimal::Decimal` in Rust. `f32` and `f64` fail a test (`no_floats.rs`) |
| Timestamps | `TIMESTAMPTZ` in SQL, `chrono::DateTime<Utc>` in Rust, taken from `state.clock`, never from the system clock |
| Dates | `DATE` in SQL, `chrono::NaiveDate` in Rust |
| Enums | PostgreSQL native enums (`CREATE TYPE ... AS ENUM`), a `string_enum!` type in Rust, cast in the query (`$4::account_type`) |
| Nullable columns | `Option<T>` |
| `created_at`, `updated_at` | set by the query, from the `now` the caller passes |
| Foreign keys | Always `ON DELETE CASCADE` where appropriate |
| Unique constraints | Named: `CONSTRAINT uk_{table}_{columns}` |
| Indexes | Named: `idx_{table}_{columns}`; partial indexes with `WHERE` clause for nullable columns |

## Connection

- **PostgreSQL 16**: an sqlx pool, from `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`
  and `SPRING_DATASOURCE_PASSWORD` (the names the Java backend read).
- **SQLite**: `DATABASE_URL=sqlite:///path/picsou.db`. One file in WAL mode with foreign keys on;
  writes go through one writer at a time.

### One writer on SQLite

`db.begin()` takes the only writer connection until the transaction commits or rolls back.
Reads outside a transaction use the WAL reader pool and are not affected.

- **Fetch first, then write.** Do the outbound calls (prices, FX rates, a bank or broker API)
  before `db.begin()`. Inside a transaction they keep every other write of the instance waiting,
  and a write that waits 30 s fails with `503 DB_BUSY`. Several services ported from
  `@Transactional` Java methods still fetch inside their transaction (the account write
  handlers, the Enable Banking, IBKR, Finary, exchange, SimpleFIN and Wise syncs, the dividend
  history refresh, the data export stream): moving their calls out is tracked as follow-up work.
  `sync/wallets` and `sync/louve` show the pattern.
- **A dropped request is safe.** When the future awaiting a statement is dropped (the client went
  away, a timeout fired), a statement that had not reached the connection is skipped and a
  `BEGIN` or `SAVEPOINT` that had is undone (`db/sqlite/mod.rs`, `run_on_writer` and
  `open_transaction`).

## JSON serialization

Response types carry `#[serde_with::skip_serializing_none]` (a `null` field is omitted) and
`#[serde(rename_all = "camelCase")]`. Dates and timestamps are ISO-8601 strings.

## Don'ts

- **Never change the schema outside a migration**, and never edit a migration that shipped.
- **Never use `f32` or `f64` for monetary values** — always `Decimal` / `NUMERIC(20, 8)`.
- **Never read the system clock** in a query or a handler — use `state.clock`.
- **Never skip `ON DELETE CASCADE`** on child table FKs unless orphan rows are intentional.
- **Never use unnamed constraints** — always `CONSTRAINT uk_{table}_{columns}` / `idx_{table}_{columns}`.
