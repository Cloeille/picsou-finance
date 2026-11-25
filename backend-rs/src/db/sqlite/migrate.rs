//! Idempotent SQLite schema migrator and physical schema parity verifier.
//!
//! Tracks applied scripts in `picsou_sqlite_migrations` with SHA-256 checksums, applies the
//! consolidated baseline matching PostgreSQL Flyway `V1..V155`, and verifies that every single
//! table and column declared in `backend-rs/src/db/schema.tsv` exists with zero drift.

use std::collections::{BTreeMap, BTreeSet};
use std::time::Instant;

use rusqlite::{Connection, params};
use sha2::{Digest, Sha256};

/// The consolidated SQLite baseline schema matching PostgreSQL Flyway `V1..V155`.
pub const BASELINE_SCHEMA: &str = include_str!("schema.sql");

const V2_REAL_ESTATE_ENERGY_CLASS_CHECK: &str =
    include_str!("migrations/V2__real_estate_energy_class_check.sql");

const V3_ACCOUNT_HOLDING_ISIN: &str = include_str!("migrations/V3__account_holding_isin.sql");

/// Metadata table tracking applied SQLite schema migrations.
pub const HISTORY_TABLE: &str = "picsou_sqlite_migrations";

/// How a SQLite migration script is executed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MigrationMode {
    /// Standard DDL / data migration executed atomically inside `BEGIN IMMEDIATE ... COMMIT`
    /// with `PRAGMA foreign_keys = ON`.
    Transactional,
    /// SQLite 12-step table-rebuild migration: disables `PRAGMA foreign_keys = OFF` outside the
    /// transaction (restoring `ON` unconditionally via a drop-guard), executes the script inside
    /// `BEGIN IMMEDIATE`, verifies `PRAGMA foreign_key_check` before commit, and rolls back if any
    /// foreign-key violation is detected.
    TableRebuild,
}

/// One embedded SQLite migration script.
#[derive(Debug, Clone)]
pub struct SqliteMigration {
    pub version: &'static str,
    pub description: &'static str,
    pub sql: &'static str,
    pub checksum: String,
    pub mode: MigrationMode,
}

/// Summary returned by [`verify`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SchemaVerificationReport {
    pub tables_verified: usize,
    pub columns_verified: usize,
    pub migrations_applied: usize,
}

#[derive(Debug, thiserror::Error)]
pub enum SqliteMigrationError {
    #[error("SQLite migration validation failed: {0}")]
    Validation(String),
    #[error("SQLite migration {version} ({description}) failed: {source}")]
    Failed {
        version: String,
        description: String,
        source: rusqlite::Error,
    },
    #[error("SQLite schema verification failed: {0}")]
    Schema(String),
    #[error("SQLite error: {0}")]
    Sqlite(#[from] rusqlite::Error),
}

/// Computes the lowercase SHA-256 hex digest of a migration SQL script (normalizing CRLF to LF so
/// checkouts on any platform hash identically).
pub fn sha256_checksum(sql: &str) -> String {
    let normalized = sql.strip_prefix('\u{feff}').unwrap_or(sql);
    let mut hasher = Sha256::new();
    for line in normalized.lines() {
        hasher.update(line.as_bytes());
        hasher.update(b"\n");
    }
    hex::encode(hasher.finalize())
}

/// Returns all embedded SQLite migrations in application order.
pub fn migrations() -> Vec<SqliteMigration> {
    vec![
        SqliteMigration {
            version: "1",
            description: "baseline schema v155",
            sql: BASELINE_SCHEMA,
            checksum: sha256_checksum(BASELINE_SCHEMA),
            mode: MigrationMode::Transactional,
        },
        SqliteMigration {
            version: "2",
            description: "real estate energy_class 1-char check",
            sql: V2_REAL_ESTATE_ENERGY_CLASS_CHECK,
            checksum: sha256_checksum(V2_REAL_ESTATE_ENERGY_CLASS_CHECK),
            mode: MigrationMode::TableRebuild,
        },
        SqliteMigration {
            version: "3",
            description: "account_holding isin",
            sql: V3_ACCOUNT_HOLDING_ISIN,
            checksum: sha256_checksum(V3_ACCOUNT_HOLDING_ISIN),
            mode: MigrationMode::Transactional,
        },
    ]
}

/// Applies all pending embedded SQLite migrations idempotently and returns the number of newly
/// applied scripts.
pub fn migrate(conn: &mut Connection) -> Result<usize, SqliteMigrationError> {
    migrate_with(conn, &migrations())
}

/// Applies all pending migrations from `local` idempotently and returns the number of newly
/// applied scripts.
pub fn migrate_with(
    conn: &mut Connection,
    local: &[SqliteMigration],
) -> Result<usize, SqliteMigrationError> {
    ensure_history_table(conn)?;
    let applied = applied_map(conn)?;
    validate_applied(local, &applied)?;

    let mut newly_applied = 0usize;
    for migration in local {
        if applied.contains_key(migration.version) {
            continue;
        }
        let elapsed_ms = match migration.mode {
            MigrationMode::Transactional => apply_transactional(conn, migration)?,
            MigrationMode::TableRebuild => apply_table_rebuild(conn, migration)?,
        };
        newly_applied += 1;
        tracing::info!(
            "Applied SQLite migration V{} ({}, mode={:?}) in {} ms",
            migration.version,
            migration.description,
            migration.mode,
            elapsed_ms
        );
    }

    Ok(newly_applied)
}

/// Drop-guard that disables `PRAGMA foreign_keys` outside a transaction and unconditionally
/// restores `PRAGMA foreign_keys = ON` even on early error returns or panics.
struct ForeignKeysOffGuard<'a> {
    conn: &'a mut Connection,
    restored: bool,
}

impl<'a> ForeignKeysOffGuard<'a> {
    fn new(conn: &'a mut Connection) -> Result<Self, rusqlite::Error> {
        conn.pragma_update(None, "foreign_keys", "OFF")?;
        Ok(Self {
            conn,
            restored: false,
        })
    }

    fn restore(mut self) -> Result<(), rusqlite::Error> {
        self.conn.pragma_update(None, "foreign_keys", "ON")?;
        self.restored = true;
        Ok(())
    }
}

impl Drop for ForeignKeysOffGuard<'_> {
    fn drop(&mut self) {
        if !self.restored {
            let _ = self.conn.pragma_update(None, "foreign_keys", "ON");
        }
    }
}

fn apply_transactional(
    conn: &mut Connection,
    migration: &SqliteMigration,
) -> Result<i64, SqliteMigrationError> {
    let started = Instant::now();
    let tx = conn.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
    tx.execute_batch(migration.sql)
        .map_err(|source| SqliteMigrationError::Failed {
            version: migration.version.to_owned(),
            description: migration.description.to_owned(),
            source,
        })?;
    let elapsed_ms = i64::try_from(started.elapsed().as_millis()).unwrap_or(i64::MAX);
    record_migration(&tx, migration, elapsed_ms)?;
    tx.commit()?;
    Ok(elapsed_ms)
}

fn apply_table_rebuild(
    conn: &mut Connection,
    migration: &SqliteMigration,
) -> Result<i64, SqliteMigrationError> {
    let started = Instant::now();
    let fk_guard = ForeignKeysOffGuard::new(conn)?;
    let apply_res = (|| -> Result<i64, SqliteMigrationError> {
        let tx = fk_guard
            .conn
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        tx.execute_batch(migration.sql)
            .map_err(|source| SqliteMigrationError::Failed {
                version: migration.version.to_owned(),
                description: migration.description.to_owned(),
                source,
            })?;
        let violations = super::query_foreign_key_violations(&tx)?;
        if !violations.is_empty() {
            let details = violations
                .iter()
                .map(|v| {
                    format!(
                        "table={} rowid={:?} parent={} fkid={}",
                        v.table, v.rowid, v.parent, v.fkid
                    )
                })
                .collect::<Vec<_>>()
                .join("; ");
            tx.rollback()?;
            return Err(SqliteMigrationError::Validation(format!(
                "migration V{} ({}) failed PRAGMA foreign_key_check: {details}",
                migration.version, migration.description
            )));
        }
        let elapsed_ms = i64::try_from(started.elapsed().as_millis()).unwrap_or(i64::MAX);
        record_migration(&tx, migration, elapsed_ms)?;
        tx.commit()?;
        Ok(elapsed_ms)
    })();
    let restore_res = fk_guard.restore();
    let elapsed_ms = apply_res?;
    restore_res?;
    Ok(elapsed_ms)
}

fn record_migration(
    tx: &rusqlite::Transaction<'_>,
    migration: &SqliteMigration,
    elapsed_ms: i64,
) -> Result<(), rusqlite::Error> {
    tx.execute(
        "INSERT INTO picsou_sqlite_migrations (version, description, checksum, execution_time_ms) \
         VALUES (?1, ?2, ?3, ?4)",
        params![
            migration.version,
            migration.description,
            migration.checksum,
            elapsed_ms
        ],
    )?;
    Ok(())
}

/// Verifies that every embedded migration has been applied with matching checksum AND that the
/// physical SQLite schema matches `backend-rs/src/db/schema.tsv` 100% (0 missing tables, 0 missing
/// columns, 0 extra stray tables or columns, and 0 `REAL` columns).
pub fn verify(conn: &Connection) -> Result<SchemaVerificationReport, SqliteMigrationError> {
    let has_history: bool = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?1)",
        params![HISTORY_TABLE],
        |r| r.get(0),
    )?;
    if !has_history {
        return Err(SqliteMigrationError::Validation(
            "picsou_sqlite_migrations table does not exist".into(),
        ));
    }

    let local = migrations();
    let applied = applied_map(conn)?;
    validate_applied(&local, &applied)?;
    for m in &local {
        if !applied.contains_key(m.version) {
            return Err(SqliteMigrationError::Validation(format!(
                "migration V{} ({}) is pending",
                m.version, m.description
            )));
        }
    }

    let expected = expected_schema_from_tsv();
    let actual = introspect_sqlite_schema(conn)?;

    let mut problems = Vec::new();
    for (table, expected_cols) in &expected {
        match actual.get(table) {
            None => problems.push(format!("missing table [{table}]")),
            Some(actual_cols) => {
                for col in expected_cols.keys() {
                    if !actual_cols.contains_key(col) {
                        problems.push(format!("missing column [{col}] in table [{table}]"));
                    }
                }
                for (col, decl_type) in actual_cols {
                    if !expected_cols.contains_key(col) {
                        problems.push(format!(
                            "unexpected extra column [{col}] in table [{table}]"
                        ));
                    }
                    let upper = decl_type.to_ascii_uppercase();
                    if upper.contains("REAL") || upper.contains("FLOAT") || upper.contains("DOUBLE")
                    {
                        problems.push(format!(
                            "forbidden floating-point type [{decl_type}] on [{table}.{col}]"
                        ));
                    }
                }
            }
        }
    }
    for table in actual.keys() {
        if !expected.contains_key(table) {
            problems.push(format!("unexpected extra table [{table}]"));
        }
    }

    if !problems.is_empty() {
        return Err(SqliteMigrationError::Schema(problems.join("; ")));
    }

    let columns_verified = expected.values().map(BTreeMap::len).sum();
    Ok(SchemaVerificationReport {
        tables_verified: expected.len(),
        columns_verified,
        migrations_applied: applied.len(),
    })
}

/// Parses `crate::db::schema::EXPECTED` (`schema.tsv`) into `table -> (column -> pg_udt_name)`.
pub fn expected_schema_from_tsv() -> BTreeMap<String, BTreeMap<String, String>> {
    let mut tables: BTreeMap<String, BTreeMap<String, String>> = BTreeMap::new();
    for line in crate::db::schema::EXPECTED.lines() {
        let parts: Vec<&str> = line.split('\t').collect();
        if let ["column", table, column, udt] = parts.as_slice() {
            tables
                .entry((*table).to_owned())
                .or_default()
                .insert((*column).to_owned(), (*udt).to_owned());
        }
    }
    tables
}

/// Introspects all user tables (excluding `sqlite_%` and `picsou_sqlite_migrations`) into
/// `table -> (column -> declared_type)`.
pub fn introspect_sqlite_schema(
    conn: &Connection,
) -> Result<BTreeMap<String, BTreeMap<String, String>>, rusqlite::Error> {
    let mut stmt = conn.prepare(
        "SELECT name FROM sqlite_master \
         WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name <> ?1 \
         ORDER BY name",
    )?;
    let table_names: Vec<String> = stmt
        .query_map(params![HISTORY_TABLE], |r| r.get(0))?
        .collect::<Result<_, _>>()?;

    let mut out = BTreeMap::new();
    for table in table_names {
        let escaped = table.replace('"', "\"\"");
        let mut col_stmt = conn.prepare(&format!("PRAGMA table_info(\"{escaped}\")"))?;
        let cols: BTreeMap<String, String> = col_stmt
            .query_map([], |r| Ok((r.get::<_, String>(1)?, r.get::<_, String>(2)?)))?
            .collect::<Result<_, _>>()?;
        out.insert(table, cols);
    }
    Ok(out)
}

fn ensure_history_table(conn: &Connection) -> Result<(), rusqlite::Error> {
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS picsou_sqlite_migrations (
             version TEXT PRIMARY KEY,
             description TEXT NOT NULL,
             checksum TEXT NOT NULL,
             applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
             execution_time_ms INTEGER NOT NULL
         );",
    )
}

fn applied_map(conn: &Connection) -> Result<BTreeMap<String, (String, String)>, rusqlite::Error> {
    let mut stmt = conn.prepare(
        "SELECT version, description, checksum FROM picsou_sqlite_migrations ORDER BY version",
    )?;
    let rows = stmt.query_map([], |r| {
        Ok((
            r.get::<_, String>(0)?,
            (r.get::<_, String>(1)?, r.get::<_, String>(2)?),
        ))
    })?;
    rows.collect()
}

fn validate_applied(
    local: &[SqliteMigration],
    applied: &BTreeMap<String, (String, String)>,
) -> Result<(), SqliteMigrationError> {
    let local_by_ver: BTreeMap<&str, &SqliteMigration> =
        local.iter().map(|m| (m.version, m)).collect();
    let mut errors = Vec::new();
    let seen: BTreeSet<&str> = local_by_ver.keys().copied().collect();

    for (version, (desc, checksum)) in applied {
        if !seen.contains(version.as_str()) {
            errors.push(format!(
                "applied migration V{version} ({desc}) is not known to this binary"
            ));
            continue;
        }
        let expected = local_by_ver[version.as_str()];
        if expected.checksum != *checksum {
            errors.push(format!(
                "checksum mismatch for migration V{version}: database has {checksum}, binary expects {}",
                expected.checksum
            ));
        }
    }

    if errors.is_empty() {
        Ok(())
    } else {
        Err(SqliteMigrationError::Validation(errors.join("; ")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::sqlite::SqliteDb;

    /// Frozen SHA-256 digest of the V1 baseline `schema.sql`. Never modify `schema.sql` after
    /// release; add a new `V<n>__<slug>.sql` migration in `migrations/` instead.
    const FROZEN_V1_BASELINE_CHECKSUM: &str =
        "80be67f6d98fc0a44b557c88ae58ca45f4026268f7a88c910c34ff6a1ba3ee38";

    #[test]
    fn v1_baseline_checksum_is_frozen() {
        assert_eq!(
            sha256_checksum(BASELINE_SCHEMA),
            FROZEN_V1_BASELINE_CHECKSUM,
            "V1 baseline schema.sql is frozen! Add a new V2+ migration under \
             backend-rs/src/db/sqlite/migrations/ instead of editing schema.sql."
        );
        let list = migrations();
        assert_eq!(list[0].version, "1");
        assert_eq!(list[0].checksum, FROZEN_V1_BASELINE_CHECKSUM);
    }

    #[test]
    fn v1_to_v2_table_rebuild_migration_succeeds_and_checks_foreign_keys() {
        // 1. Happy path: built-in migrations -> synthetic TableRebuild migration preserves data &
        //    FKs, records it in picsou_sqlite_migrations, restores PRAGMA foreign_keys = ON, and
        //    is idempotent.
        let builtin = migrations();
        let builtin_versions: Vec<String> = builtin.iter().map(|m| m.version.to_owned()).collect();
        let db = SqliteDb::open_in_memory().expect("open in-memory SQLite");
        assert_eq!(
            db.migrate().expect("apply built-in migrations"),
            builtin.len()
        );

        db.with_conn(|conn| {
            conn.execute(
                "INSERT INTO family_member (id, display_name, is_managed) VALUES (1, 'Alice', 0)",
                [],
            )?;
            conn.execute(
                "INSERT INTO account (id, name, type, currency, current_balance, is_manual, member_id) \
                 VALUES (10, 'Checking', 'CHECKING', 'EUR', '100.00', 1, 1)",
                [],
            )?;

            let rebuild_sql = "\
                CREATE TABLE family_member_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    display_name TEXT NOT NULL,
                    avatar_color TEXT NOT NULL DEFAULT '#6366f1',
                    is_managed INTEGER NOT NULL DEFAULT 0 CHECK (is_managed IN (0, 1)),
                    notes TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
                    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now'))
                );
                INSERT INTO family_member_new
                    (id, display_name, avatar_color, is_managed, created_at, updated_at)
                SELECT id, display_name, avatar_color, is_managed, created_at, updated_at
                FROM family_member;
                DROP TABLE family_member;
                ALTER TABLE family_member_new RENAME TO family_member;";

            let mut plan = migrations();
            plan.push(SqliteMigration {
                version: "99",
                description: "rebuild family_member with notes column",
                sql: rebuild_sql,
                checksum: sha256_checksum(rebuild_sql),
                mode: MigrationMode::TableRebuild,
            });

            assert_eq!(migrate_with(conn, &plan)?, 1);
            // Re-running is strictly idempotent.
            assert_eq!(migrate_with(conn, &plan)?, 0);

            // PRAGMA foreign_keys must be ON after rebuild.
            let fk_on: i64 = conn.pragma_query_value(None, "foreign_keys", |r| r.get(0))?;
            assert_eq!(fk_on, 1, "PRAGMA foreign_keys must be restored to ON");

            // History table contains all built-in migrations plus synthetic 99.
            let versions: Vec<String> = conn
                .prepare("SELECT version FROM picsou_sqlite_migrations ORDER BY CAST(version AS INTEGER)")?
                .query_map([], |r| r.get(0))?
                .collect::<Result<_, _>>()?;
            let mut expected_versions = builtin_versions.clone();
            expected_versions.push("99".to_owned());
            assert_eq!(versions, expected_versions);

            // Existing data and foreign-key integrity are preserved.
            let (name, notes): (String, String) = conn.query_row(
                "SELECT display_name, notes FROM family_member WHERE id = 1",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )?;
            assert_eq!(name, "Alice");
            assert_eq!(notes, "");
            assert!(super::super::query_foreign_key_violations(conn)?.is_empty());

            Ok(())
        })
        .expect("happy-path table rebuild");

        // 2. Failure path: a broken TableRebuild that breaks a foreign key is rejected & rolled
        //    back cleanly, leaving PRAGMA foreign_keys = ON and the database at built-in versions.
        let broken_db = SqliteDb::open_in_memory().expect("open second in-memory SQLite");
        assert_eq!(
            broken_db.migrate().expect("apply built-in migrations"),
            builtin.len()
        );

        broken_db
            .with_conn(|conn| {
                conn.execute(
                    "INSERT INTO family_member (id, display_name, is_managed) VALUES (1, 'Alice', 0)",
                    [],
                )?;
                conn.execute(
                    "INSERT INTO account (id, name, type, currency, current_balance, is_manual, member_id) \
                     VALUES (10, 'Checking', 'CHECKING', 'EUR', '100.00', 1, 1)",
                    [],
                )?;

                // Drops the parent row while foreign_keys = OFF, leaving account.member_id dangling.
                let broken_sql = "DELETE FROM family_member WHERE id = 1;";
                let mut broken_plan = migrations();
                broken_plan.push(SqliteMigration {
                    version: "99",
                    description: "broken rebuild violating fk",
                    sql: broken_sql,
                    checksum: sha256_checksum(broken_sql),
                    mode: MigrationMode::TableRebuild,
                });

                let err = migrate_with(conn, &broken_plan)
                    .expect_err("broken TableRebuild migration must be rejected");
                assert!(
                    matches!(err, SqliteMigrationError::Validation(ref msg) if msg.contains("foreign_key_check")),
                    "expected Validation error mentioning foreign_key_check, got: {err}"
                );

                // PRAGMA foreign_keys must STILL be restored to ON even after error!
                let fk_on: i64 = conn.pragma_query_value(None, "foreign_keys", |r| r.get(0))?;
                assert_eq!(fk_on, 1, "PRAGMA foreign_keys must be restored to ON after failure");

                // Transaction rolled back cleanly: Alice still exists, and only built-in versions are recorded.
                let member_count: i64 =
                    conn.query_row("SELECT COUNT(*) FROM family_member WHERE id = 1", [], |r| {
                        r.get(0)
                    })?;
                assert_eq!(member_count, 1, "failed migration must roll back parent deletion");

                let versions: Vec<String> = conn
                    .prepare("SELECT version FROM picsou_sqlite_migrations ORDER BY CAST(version AS INTEGER)")?
                    .query_map([], |r| r.get(0))?
                    .collect::<Result<_, _>>()?;
                assert_eq!(versions, builtin_versions);
                assert!(super::super::query_foreign_key_violations(conn)?.is_empty());

                Ok(())
            })
            .expect("broken-fk rollback verification");
    }

    #[test]
    fn real_estate_energy_class_rejects_multi_character_values() {
        // Apply V1 only first, seed an existing real_estate_metadata row, then upgrade to V2
        // to prove V2 preserves existing data & indexes while tightening the CHECK constraint.
        let db = SqliteDb::open_in_memory().expect("open in-memory SQLite");
        let all = migrations();
        db.with_conn(|conn| {
            assert_eq!(migrate_with(conn, &all[..1])?, 1);
            conn.execute(
                "INSERT INTO family_member (id, display_name, is_managed) VALUES (1, 'Jane Doe', 0)",
                [],
            )?;
            conn.execute(
                "INSERT INTO account (id, name, type, currency, current_balance, is_manual, member_id) \
                 VALUES (10, 'Home', 'REAL_ESTATE', 'EUR', '250000.00', 1, 1)",
                [],
            )?;
            conn.execute(
                "INSERT INTO real_estate_metadata (account_id, member_id, purchase_price, insee_code, energy_class) \
                 VALUES (10, 1, '250000.00', '75101', 'C')",
                [],
            )?;

            assert_eq!(migrate_with(conn, &all)?, all.len() - 1);

            let (insee, energy): (Option<String>, Option<String>) = conn.query_row(
                "SELECT insee_code, energy_class FROM real_estate_metadata WHERE account_id = 10",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )?;
            assert_eq!(insee.as_deref(), Some("75101"));
            assert_eq!(energy.as_deref(), Some("C"));

            for valid in [Some("A"), Some("D"), Some("G"), None] {
                conn.execute(
                    "UPDATE real_estate_metadata SET energy_class = ?1 WHERE account_id = 10",
                    params![valid],
                )?;
            }

            for invalid in ["AB", "A+", "F9", "H", "a", ""] {
                let err = conn
                    .execute(
                        "UPDATE real_estate_metadata SET energy_class = ?1 WHERE account_id = 10",
                        params![invalid],
                    )
                    .expect_err(&format!(
                        "energy_class={invalid:?} must be rejected by CHECK constraint"
                    ));
                assert!(
                    err.to_string().contains("CHECK constraint failed"),
                    "expected CHECK constraint violation for {invalid:?}, got: {err}"
                );
            }

            Ok(())
        })
        .expect("energy_class check");

        db.verify_schema().expect("schema parity after V2");
        let integrity = db.check_integrity().expect("integrity check after V2");
        assert!(integrity.integrity_ok, "{:?}", integrity.integrity_messages);
        assert!(integrity.foreign_key_violations.is_empty());
    }

    #[test]
    fn v3_backfills_each_holding_with_the_isin_its_ticker_or_profile_knew() {
        let db = SqliteDb::open_in_memory().expect("open in-memory SQLite");
        let all = migrations();
        db.with_conn(|conn| {
            assert_eq!(migrate_with(conn, &all[..2])?, 2);
            conn.execute_batch(
                "INSERT INTO family_member (id, display_name, is_managed) VALUES (1, 'Doe', 0);
                 INSERT INTO account (id, name, type, currency, current_balance, is_manual, member_id)
                     VALUES (10, 'PEA', 'PEA', 'EUR', '0', 1, 1);
                 INSERT INTO security_profile (ticker, asset_type, isin) VALUES ('MULYF', 'ETF', 'LU1829219390');
                 INSERT INTO security_profile (ticker, asset_type, isin) VALUES ('CW8.PA', 'ETF', 'LU1681043599');
                 INSERT INTO account_holding (account_id, ticker, quantity) VALUES (10, 'MULYF', '80');
                 INSERT INTO account_holding (account_id, ticker, quantity) VALUES (10, 'fr0010135103', '3');
                 INSERT INTO account_holding (account_id, ticker, quantity) VALUES (10, 'cw8.pa', '2');
                 INSERT INTO account_holding (account_id, ticker, quantity) VALUES (10, 'BTC', '1');",
            )?;

            assert_eq!(migrate_with(conn, &all)?, all.len() - 2);

            let mut stmt =
                conn.prepare("SELECT ticker, isin FROM account_holding ORDER BY ticker")?;
            let rows: Vec<(String, Option<String>)> = stmt
                .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
                .collect::<Result<_, _>>()?;
            assert_eq!(
                rows,
                vec![
                    ("BTC".to_owned(), None),
                    ("MULYF".to_owned(), Some("LU1829219390".to_owned())),
                    ("cw8.pa".to_owned(), Some("LU1681043599".to_owned())),
                    ("fr0010135103".to_owned(), Some("FR0010135103".to_owned())),
                ]
            );
            Ok(())
        })
        .expect("v3 backfill");
        db.verify_schema().expect("schema parity after V3");
    }

    #[test]
    fn v3_refuses_a_malformed_isin() {
        let db = SqliteDb::open_in_memory().expect("open in-memory SQLite");
        assert_eq!(db.migrate().expect("migrate"), migrations().len());
        db.with_conn(|conn| {
            conn.execute_batch(
                "INSERT INTO family_member (id, display_name, is_managed) VALUES (1, 'Doe', 0);
                 INSERT INTO account (id, name, type, currency, current_balance, is_manual, member_id)
                     VALUES (10, 'PEA', 'PEA', 'EUR', '0', 1, 1);",
            )?;
            for bad in ["lu1829219390", "LU182921939A", "LU18292193", "LU1829219390X"] {
                let err = conn
                    .execute(
                        "INSERT INTO account_holding (account_id, ticker, quantity, isin) VALUES (10, 'X', '1', ?1)",
                        params![bad],
                    )
                    .expect_err(&format!("{bad:?} must be rejected"));
                assert!(
                    err.to_string().contains("CHECK constraint failed"),
                    "{bad:?}: {err}"
                );
            }
            Ok(())
        })
        .expect("malformed isin");
    }
}
