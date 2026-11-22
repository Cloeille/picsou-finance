//! Embedded SQLite database foundation for Picsou.
//!
//! Configures WAL mode, strict foreign-key enforcement, 5-second busy timeout, `synchronous =
//! NORMAL`, and registers exact `rust_decimal::Decimal` SQLite functions on every connection.

pub mod decimal;
pub mod export_pg;
pub mod migrate;

use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};

use rusqlite::{Connection, OpenFlags};

/// Default `PRAGMA busy_timeout` in milliseconds.
pub const DEFAULT_BUSY_TIMEOUT_MS: u32 = 5_000;

/// Errors produced by the embedded SQLite engine wrapper.
#[derive(Debug, thiserror::Error)]
pub enum SqliteError {
    #[error("SQLite error: {0}")]
    Rusqlite(#[from] rusqlite::Error),
    #[error("SQLite foreign_keys pragma did not activate")]
    ForeignKeysNotEnabled,
    #[error("SQLite connection mutex poisoned")]
    MutexPoisoned,
    #[error("blocking SQLite task failed: {0}")]
    Join(String),
    #[error("SQLite migration error: {0}")]
    Migration(#[from] migrate::SqliteMigrationError),
    #[error("SQLite filesystem I/O error: {0}")]
    Io(#[from] std::io::Error),
}

impl SqliteError {
    pub(crate) fn from_export_migration(err: migrate::SqliteMigrationError) -> Self {
        Self::Migration(err)
    }
}

/// One row reported by `PRAGMA foreign_key_check`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ForeignKeyViolation {
    pub table: String,
    pub rowid: Option<i64>,
    pub parent: String,
    pub fkid: i64,
}

/// Physical and referential integrity report for a SQLite database.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IntegrityReport {
    pub integrity_ok: bool,
    pub integrity_messages: Vec<String>,
    pub foreign_key_violations: Vec<ForeignKeyViolation>,
}

/// Thread-safe, async-friendly handle to a configured SQLite connection.
///
/// Uses `rusqlite` (already bundled with `backend-rs`) so zero extra crates are pulled into the
/// desktop or server dependency graphs. Blocking SQLite calls execute on Tokio's blocking pool via
/// [`SqliteDb::interact`].
#[derive(Clone)]
pub struct SqliteDb {
    conn: Arc<Mutex<Connection>>,
}

impl SqliteDb {
    /// Opens (or creates) a file-backed SQLite database with Picsou's standard PRAGMAs and exact
    /// decimal functions registered.
    pub fn open(path: &Path) -> Result<Self, SqliteError> {
        #[cfg(unix)]
        ensure_private_file(path)?;
        let flags = OpenFlags::SQLITE_OPEN_READ_WRITE
            | OpenFlags::SQLITE_OPEN_CREATE
            | OpenFlags::SQLITE_OPEN_NO_MUTEX;
        let conn = Connection::open_with_flags(path, flags)?;
        configure_connection(&conn, DEFAULT_BUSY_TIMEOUT_MS, true)?;
        #[cfg(unix)]
        restrict_sqlite_permissions(path)?;
        Ok(Self {
            conn: Arc::new(Mutex::new(conn)),
        })
    }

    /// Opens an in-memory SQLite database with Picsou's standard PRAGMAs and exact decimal
    /// functions registered.
    pub fn open_in_memory() -> Result<Self, SqliteError> {
        let conn = Connection::open_in_memory()?;
        configure_connection(&conn, DEFAULT_BUSY_TIMEOUT_MS, false)?;
        Ok(Self {
            conn: Arc::new(Mutex::new(conn)),
        })
    }

    /// Runs a synchronous closure with exclusive access to the underlying `rusqlite::Connection`.
    pub fn with_conn<F, T>(&self, f: F) -> Result<T, SqliteError>
    where
        F: FnOnce(&mut Connection) -> Result<T, SqliteError>,
    {
        let mut guard = self.lock_connection()?;
        f(&mut guard)
    }

    /// Runs a blocking SQLite operation on Tokio's blocking thread pool so async handlers never
    /// stall the async runtime.
    pub async fn interact<F, T>(&self, f: F) -> Result<T, SqliteError>
    where
        F: FnOnce(&mut Connection) -> Result<T, SqliteError> + Send + 'static,
        T: Send + 'static,
    {
        let conn = Arc::clone(&self.conn);
        tokio::task::spawn_blocking(move || {
            let mut guard = conn.lock().map_err(|_| SqliteError::MutexPoisoned)?;
            f(&mut guard)
        })
        .await
        .map_err(|e| SqliteError::Join(e.to_string()))?
    }

    /// Applies all pending SQLite schema migrations idempotently.
    pub fn migrate(&self) -> Result<usize, SqliteError> {
        self.with_conn(|conn| Ok(migrate::migrate(conn)?))
    }

    /// Verifies migration history and 100% table/column parity with `schema.tsv`.
    pub fn verify_schema(&self) -> Result<migrate::SchemaVerificationReport, SqliteError> {
        self.with_conn(|conn| Ok(migrate::verify(conn)?))
    }

    /// Runs `PRAGMA integrity_check` and `PRAGMA foreign_key_check`.
    pub fn check_integrity(&self) -> Result<IntegrityReport, SqliteError> {
        self.with_conn(|conn| {
            let integrity_messages = query_integrity_messages(conn)?;
            let foreign_key_violations = query_foreign_key_violations(conn)?;
            Ok(IntegrityReport {
                integrity_ok: integrity_messages == ["ok"],
                integrity_messages,
                foreign_key_violations,
            })
        })
    }

    pub(crate) fn lock_connection(&self) -> Result<MutexGuard<'_, Connection>, SqliteError> {
        self.conn.lock().map_err(|_| SqliteError::MutexPoisoned)
    }
}

/// Applies Picsou's mandatory PRAGMAs and registers all exact decimal SQLite functions.
pub fn configure_connection(
    conn: &Connection,
    busy_timeout_ms: u32,
    enable_wal: bool,
) -> Result<(), SqliteError> {
    if enable_wal {
        let _: String = conn.pragma_update_and_check(None, "journal_mode", "WAL", |r| r.get(0))?;
    }
    conn.pragma_update(None, "foreign_keys", "ON")?;
    conn.pragma_update(None, "busy_timeout", busy_timeout_ms)?;
    conn.pragma_update(None, "synchronous", "NORMAL")?;
    conn.pragma_update(None, "temp_store", "MEMORY")?;

    let fk_enabled: i64 = conn.pragma_query_value(None, "foreign_keys", |r| r.get(0))?;
    if fk_enabled != 1 {
        return Err(SqliteError::ForeignKeysNotEnabled);
    }

    decimal::register_decimal_functions(conn)?;
    Ok(())
}

#[cfg(unix)]
fn ensure_private_file(path: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::OpenOptionsExt;
    match std::fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .mode(0o600)
        .open(path)
    {
        Ok(_) => Ok(()),
        Err(err) if err.kind() == std::io::ErrorKind::AlreadyExists => Ok(()),
        Err(err) => Err(err),
    }
}

/// Restricts the SQLite database file and any `-wal`/`-shm` sidecars to owner-only (`0o600`).
#[cfg(unix)]
pub(crate) fn restrict_sqlite_permissions(path: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mode = std::fs::Permissions::from_mode(0o600);
    if path.exists() {
        std::fs::set_permissions(path, mode.clone())?;
    }
    for suffix in ["-wal", "-shm"] {
        let mut sidecar = path.as_os_str().to_os_string();
        sidecar.push(suffix);
        let sidecar_path = std::path::PathBuf::from(sidecar);
        if sidecar_path.exists() {
            std::fs::set_permissions(&sidecar_path, mode.clone())?;
        }
    }
    Ok(())
}

pub(crate) fn query_integrity_messages(conn: &Connection) -> Result<Vec<String>, rusqlite::Error> {
    let mut stmt = conn.prepare("PRAGMA integrity_check")?;
    let rows = stmt.query_map([], |r| r.get::<_, String>(0))?;
    rows.collect()
}

pub(crate) fn query_foreign_key_violations(
    conn: &Connection,
) -> Result<Vec<ForeignKeyViolation>, rusqlite::Error> {
    let mut stmt = conn.prepare("PRAGMA foreign_key_check")?;
    let rows = stmt.query_map([], |r| {
        Ok(ForeignKeyViolation {
            table: r.get(0)?,
            rowid: r.get(1)?,
            parent: r.get(2)?,
            fkid: r.get(3)?,
        })
    })?;
    rows.collect()
}
