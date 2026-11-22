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

/// Metadata table tracking applied SQLite schema migrations.
pub const HISTORY_TABLE: &str = "picsou_sqlite_migrations";

/// One embedded SQLite migration script.
#[derive(Debug, Clone)]
pub struct SqliteMigration {
    pub version: &'static str,
    pub description: &'static str,
    pub sql: &'static str,
    pub checksum: String,
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
    vec![SqliteMigration {
        version: "1",
        description: "baseline schema v155",
        sql: BASELINE_SCHEMA,
        checksum: sha256_checksum(BASELINE_SCHEMA),
    }]
}

/// Applies all pending SQLite migrations idempotently inside transactions and returns the number
/// of newly applied scripts.
pub fn migrate(conn: &mut Connection) -> Result<usize, SqliteMigrationError> {
    ensure_history_table(conn)?;
    let local = migrations();
    let applied = applied_map(conn)?;
    validate_applied(&local, &applied)?;

    let mut newly_applied = 0usize;
    for migration in &local {
        if applied.contains_key(migration.version) {
            continue;
        }
        let started = Instant::now();
        let tx = conn.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        tx.execute_batch(migration.sql)
            .map_err(|source| SqliteMigrationError::Failed {
                version: migration.version.to_owned(),
                description: migration.description.to_owned(),
                source,
            })?;
        let elapsed_ms = i64::try_from(started.elapsed().as_millis()).unwrap_or(i64::MAX);
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
        tx.commit()?;
        newly_applied += 1;
        tracing::info!(
            "Applied SQLite migration V{} ({}) in {} ms",
            migration.version,
            migration.description,
            elapsed_ms
        );
    }

    Ok(newly_applied)
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
