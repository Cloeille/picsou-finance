//! Lossless PostgreSQL -> SQLite database exporter (`picsou export-sqlite <output.db>`).
//!
//! Reads every application table declared in `backend-rs/src/db/schema.tsv` from a live Picsou
//! PostgreSQL database, converts every PostgreSQL `NUMERIC` losslessly via exact text
//! representation (validated through `rust_decimal::Decimal`, zero binary floats), loads all rows
//! atomically into a staging SQLite database, and verifies:
//!   1. `PRAGMA foreign_key_check` reports zero violations,
//!   2. `PRAGMA integrity_check` returns `"ok"`,
//!   3. Every table's SQLite row count matches PostgreSQL 1:1.
//!
//! Only after all checks succeed is the staging file atomically renamed to the target path.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use chrono::{DateTime, NaiveDate, NaiveDateTime, SecondsFormat, Utc};
use rusqlite::Connection;
use rusqlite::types::Value as SqliteValue;
use sqlx::{PgPool, Row};
use uuid::Uuid;

use super::decimal::{self, DecimalCodecError};
use super::migrate::{self, SqliteMigrationError};
use super::{ForeignKeyViolation, SqliteDb, SqliteError};

/// Summary of a completed PostgreSQL -> SQLite export.
#[derive(Debug, Clone)]
pub struct ExportReport {
    pub tables_exported: usize,
    pub total_rows: u64,
    pub table_counts: BTreeMap<String, u64>,
    pub integrity_ok: bool,
    pub foreign_key_violations: usize,
}

#[derive(Debug, thiserror::Error)]
pub enum ExportError {
    #[error("output file already exists: {0}")]
    OutputExists(PathBuf),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("PostgreSQL error while exporting table [{table}]: {source}")]
    Postgres { table: String, source: sqlx::Error },
    #[error("SQLite error: {0}")]
    Sqlite(#[from] SqliteError),
    #[error("SQLite driver error: {0}")]
    Rusqlite(#[from] rusqlite::Error),
    #[error("SQLite migration error: {0}")]
    Migration(#[from] SqliteMigrationError),
    #[error("invalid numeric value in [{table}.{column}]: {source}")]
    InvalidDecimal {
        table: String,
        column: String,
        source: DecimalCodecError,
    },
    #[error("unsupported PostgreSQL column type [{udt}] on [{table}.{column}]")]
    UnsupportedType {
        table: String,
        column: String,
        udt: String,
    },
    #[error(
        "row count mismatch on table [{table}]: PostgreSQL had {pg_rows}, SQLite has {sqlite_rows}"
    )]
    RowCountMismatch {
        table: String,
        pg_rows: u64,
        sqlite_rows: u64,
    },
    #[error("SQLite foreign_key_check failed with {count} violation(s): {0:?}", count = .0.len())]
    ForeignKeyViolations(Vec<ForeignKeyViolation>),
    #[error("SQLite integrity_check failed: {0}")]
    IntegrityFailed(String),
}

/// Column decoding strategy derived directly from PostgreSQL `udt_name`.
#[derive(Debug, Clone, Copy)]
pub(crate) enum ColKind {
    Int64,
    Bool,
    Numeric,
    Timestamptz,
    Timestamp,
    Date,
    Uuid,
    Bytea,
    TextOrEnum,
}

impl ColKind {
    pub(crate) fn from_pg_udt(table: &str, column: &str, udt: &str) -> Result<Self, ExportError> {
        match udt {
            "int8" | "int4" | "int2" => Ok(Self::Int64),
            "bool" => Ok(Self::Bool),
            "numeric" => Ok(Self::Numeric),
            "timestamptz" => Ok(Self::Timestamptz),
            "timestamp" => Ok(Self::Timestamp),
            "date" => Ok(Self::Date),
            "uuid" => Ok(Self::Uuid),
            "bytea" => Ok(Self::Bytea),
            "varchar" | "text" | "jsonb" | "json" | "account_type" | "category_kind"
            | "rate_basis" | "recurring_cadence" | "recurring_status" | "requisition_status"
            | "savings_product" | "sharing_level" => Ok(Self::TextOrEnum),
            other => Err(ExportError::UnsupportedType {
                table: table.to_owned(),
                column: column.to_owned(),
                udt: other.to_owned(),
            }),
        }
    }

    fn pg_select_expr(self, quoted_col: &str) -> String {
        match self {
            Self::Int64 => format!("{quoted_col}::bigint"),
            Self::Numeric | Self::TextOrEnum => format!("{quoted_col}::text"),
            Self::Bool
            | Self::Timestamptz
            | Self::Timestamp
            | Self::Date
            | Self::Uuid
            | Self::Bytea => quoted_col.to_owned(),
        }
    }
}

struct StagedTable {
    name: String,
    columns: Vec<String>,
    rows: Vec<Vec<SqliteValue>>,
}

/// Exports every application table from `pg` into a brand-new SQLite database at `sqlite_path`.
pub async fn export_pg_to_sqlite(
    pg: &PgPool,
    sqlite_path: &Path,
) -> Result<ExportReport, ExportError> {
    if sqlite_path.exists() {
        return Err(ExportError::OutputExists(sqlite_path.to_path_buf()));
    }
    if let Some(parent) = sqlite_path.parent()
        && !parent.as_os_str().is_empty()
    {
        std::fs::create_dir_all(parent)?;
    }

    let schema = migrate::expected_schema_from_tsv();

    // 1. Read every table from PostgreSQL inside a REPEATABLE READ read-only transaction so all
    //    67 tables come from one consistent MVCC snapshot (no cross-table FK drift mid-export!).
    let mut pg_tx = pg.begin().await.map_err(|source| ExportError::Postgres {
        table: "<transaction>".into(),
        source,
    })?;
    sqlx::query("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
        .execute(&mut *pg_tx)
        .await
        .map_err(|source| ExportError::Postgres {
            table: "<transaction>".into(),
            source,
        })?;

    let mut staged = Vec::with_capacity(schema.len());
    for (table, cols) in &schema {
        let mut col_names = Vec::with_capacity(cols.len());
        let mut col_kinds = Vec::with_capacity(cols.len());
        let mut select_exprs = Vec::with_capacity(cols.len());

        for (col_name, udt) in cols {
            let kind = ColKind::from_pg_udt(table, col_name, udt)?;
            let quoted = quote_ident(col_name);
            select_exprs.push(kind.pg_select_expr(&quoted));
            col_names.push(col_name.clone());
            col_kinds.push(kind);
        }

        let sql = format!(
            "SELECT {} FROM \"public\".{}",
            select_exprs.join(", "),
            quote_ident(table)
        );
        let pg_rows = sqlx::query(sqlx::AssertSqlSafe(sql))
            .fetch_all(&mut *pg_tx)
            .await
            .map_err(|source| ExportError::Postgres {
                table: table.clone(),
                source,
            })?;

        let mut converted_rows = Vec::with_capacity(pg_rows.len());
        for row in &pg_rows {
            let mut vals = Vec::with_capacity(col_names.len());
            for (idx, kind) in col_kinds.iter().enumerate() {
                let val = decode_pg_value(row, idx, *kind, table, &col_names[idx])?;
                vals.push(val);
            }
            converted_rows.push(vals);
        }

        staged.push(StagedTable {
            name: table.clone(),
            columns: col_names,
            rows: converted_rows,
        });
    }

    pg_tx
        .commit()
        .await
        .map_err(|source| ExportError::Postgres {
            table: "<transaction>".into(),
            source,
        })?;

    // 2. Build the SQLite database in a temporary staging file in the same directory so the final
    //    rename is atomic and failures never leave a corrupt target file behind.
    let tmp_path = staging_path(sqlite_path);
    let tmp_for_blocking = tmp_path.clone();

    let load_result =
        tokio::task::spawn_blocking(move || load_into_sqlite(&tmp_for_blocking, &staged))
            .await
            .map_err(|e| ExportError::Io(std::io::Error::other(e.to_string())))?;

    let report = match load_result {
        Ok(report) => report,
        Err(err) => {
            cleanup_sqlite_files(&tmp_path);
            return Err(err);
        }
    };

    // 3. Atomically promote the verified, single-file SQLite database to `sqlite_path`.
    #[cfg(unix)]
    super::restrict_sqlite_permissions(&tmp_path)?;
    if let Err(err) = std::fs::rename(&tmp_path, sqlite_path) {
        cleanup_sqlite_files(&tmp_path);
        return Err(ExportError::Io(err));
    }
    #[cfg(unix)]
    super::restrict_sqlite_permissions(sqlite_path)?;

    Ok(report)
}

fn load_into_sqlite(tmp_path: &Path, staged: &[StagedTable]) -> Result<ExportReport, ExportError> {
    let db = SqliteDb::open(tmp_path)?;
    db.with_conn(|conn| {
        migrate::migrate(conn).map_err(SqliteError::from_export_migration)?;
        Ok(())
    })?;

    // Run the bulk load, integrity checks, and single-file checkpoint on the raw connection.
    let mut table_counts = BTreeMap::new();
    let mut total_rows = 0u64;

    let inner_res: Result<ExportReport, ExportError> = {
        let mut guard = db
            .lock_connection()
            .map_err(|_| ExportError::Io(std::io::Error::other("SQLite mutex poisoned")))?;
        let conn: &mut Connection = &mut guard;

        // Disable FK enforcement during bulk load so arbitrary table order & self-referential FKs
        // (e.g., account.parent_account_id, category.parent_id) insert cleanly in one transaction.
        conn.execute_batch("PRAGMA foreign_keys = OFF;")?;

        {
            let tx = conn.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
            for table in staged {
                if !table.rows.is_empty() {
                    let quoted_cols: Vec<String> =
                        table.columns.iter().map(|c| quote_ident(c)).collect();
                    let placeholders = vec!["?"; table.columns.len()].join(", ");
                    let insert_sql = format!(
                        "INSERT INTO {} ({}) VALUES ({})",
                        quote_ident(&table.name),
                        quoted_cols.join(", "),
                        placeholders
                    );
                    let mut stmt = tx.prepare(&insert_sql)?;
                    for row in &table.rows {
                        stmt.execute(rusqlite::params_from_iter(row.iter()))?;
                    }
                }
                let count = u64::try_from(table.rows.len()).unwrap_or(0);
                table_counts.insert(table.name.clone(), count);
                total_rows += count;
            }
            tx.commit()?;
        }

        // Re-enable FK enforcement and run full physical & referential verification.
        conn.execute_batch("PRAGMA foreign_keys = ON;")?;

        // 1. Verify zero foreign-key violations.
        let violations = super::query_foreign_key_violations(conn)?;
        if !violations.is_empty() {
            return Err(ExportError::ForeignKeyViolations(violations));
        }

        // 2. Verify physical B-tree + CHECK constraint integrity.
        let integrity = super::query_integrity_messages(conn)?;
        if integrity != ["ok"] {
            return Err(ExportError::IntegrityFailed(integrity.join("; ")));
        }

        // 3. Verify 1:1 row counts for every single table.
        for (table, expected_count) in &table_counts {
            let actual: i64 = conn.query_row(
                &format!("SELECT COUNT(*) FROM {}", quote_ident(table)),
                [],
                |r| r.get(0),
            )?;
            let actual_u64 = u64::try_from(actual).unwrap_or(0);
            if actual_u64 != *expected_count {
                return Err(ExportError::RowCountMismatch {
                    table: table.clone(),
                    pg_rows: *expected_count,
                    sqlite_rows: actual_u64,
                });
            }
        }

        // 4. Verify schema parity against schema.tsv.
        migrate::verify(conn)?;

        // 5. Flush WAL and switch to DELETE mode before closing so the exported file is a
        //    completely self-contained single file with no orphan `-wal`/`-shm` sidecars.
        conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE); PRAGMA journal_mode = DELETE;")?;

        Ok(ExportReport {
            tables_exported: table_counts.len(),
            total_rows,
            table_counts,
            integrity_ok: true,
            foreign_key_violations: 0,
        })
    };

    drop(db);
    inner_res
}

fn decode_pg_value(
    row: &sqlx::postgres::PgRow,
    idx: usize,
    kind: ColKind,
    table: &str,
    column: &str,
) -> Result<SqliteValue, ExportError> {
    let pg_err = |source| ExportError::Postgres {
        table: table.to_owned(),
        source,
    };
    match kind {
        ColKind::Int64 => {
            let v: Option<i64> = row.try_get(idx).map_err(pg_err)?;
            Ok(match v {
                Some(n) => SqliteValue::Integer(n),
                None => SqliteValue::Null,
            })
        }
        ColKind::Bool => {
            let v: Option<bool> = row.try_get(idx).map_err(pg_err)?;
            Ok(match v {
                Some(b) => SqliteValue::Integer(i64::from(b)),
                None => SqliteValue::Null,
            })
        }
        ColKind::Numeric => {
            let v: Option<String> = row.try_get(idx).map_err(pg_err)?;
            match v {
                None => Ok(SqliteValue::Null),
                Some(raw) => {
                    // Validate lossless round-trip through rust_decimal::Decimal without altering
                    // scale or precision.
                    let parsed = decimal::from_sqlite_text(&raw).map_err(|source| {
                        ExportError::InvalidDecimal {
                            table: table.to_owned(),
                            column: column.to_owned(),
                            source,
                        }
                    })?;
                    Ok(SqliteValue::Text(decimal::to_sqlite_text(parsed)))
                }
            }
        }
        ColKind::Timestamptz => {
            let v: Option<DateTime<Utc>> = row.try_get(idx).map_err(pg_err)?;
            Ok(match v {
                Some(dt) => SqliteValue::Text(dt.to_rfc3339_opts(SecondsFormat::Micros, true)),
                None => SqliteValue::Null,
            })
        }
        ColKind::Timestamp => {
            let v: Option<NaiveDateTime> = row.try_get(idx).map_err(pg_err)?;
            Ok(match v {
                Some(ndt) => SqliteValue::Text(ndt.format("%Y-%m-%dT%H:%M:%S%.6fZ").to_string()),
                None => SqliteValue::Null,
            })
        }
        ColKind::Date => {
            let v: Option<NaiveDate> = row.try_get(idx).map_err(pg_err)?;
            Ok(match v {
                Some(d) => SqliteValue::Text(d.format("%Y-%m-%d").to_string()),
                None => SqliteValue::Null,
            })
        }
        ColKind::Uuid => {
            let v: Option<Uuid> = row.try_get(idx).map_err(pg_err)?;
            Ok(match v {
                Some(u) => SqliteValue::Text(u.hyphenated().to_string()),
                None => SqliteValue::Null,
            })
        }
        ColKind::Bytea => {
            let v: Option<Vec<u8>> = row.try_get(idx).map_err(pg_err)?;
            Ok(match v {
                Some(bytes) => SqliteValue::Blob(bytes),
                None => SqliteValue::Null,
            })
        }
        ColKind::TextOrEnum => {
            let v: Option<String> = row.try_get(idx).map_err(pg_err)?;
            Ok(match v {
                Some(s) => SqliteValue::Text(s),
                None => SqliteValue::Null,
            })
        }
    }
}

fn quote_ident(ident: &str) -> String {
    format!("\"{}\"", ident.replace('"', "\"\""))
}

fn staging_path(target: &Path) -> PathBuf {
    let file_name = target
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("picsou.db");
    let unique = format!(
        ".{file_name}.export-{}-{}.tmp",
        std::process::id(),
        Uuid::new_v4().simple()
    );
    match target.parent() {
        Some(parent) if !parent.as_os_str().is_empty() => parent.join(unique),
        _ => PathBuf::from(unique),
    }
}

fn cleanup_sqlite_files(base: &Path) {
    let _ = std::fs::remove_file(base);
    let mut wal = base.as_os_str().to_os_string();
    wal.push("-wal");
    let _ = std::fs::remove_file(&wal);
    let mut shm = base.as_os_str().to_os_string();
    shm.push("-shm");
    let _ = std::fs::remove_file(&shm);
}
