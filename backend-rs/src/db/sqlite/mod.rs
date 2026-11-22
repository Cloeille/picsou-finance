//! Embedded SQLite database foundation and async runtime engine for Picsou.
//!
//! Configures WAL mode, strict foreign-key enforcement, 5-second busy timeout, `synchronous =
//! NORMAL`, and registers exact `rust_decimal::Decimal` functions plus a UTC `now()` UDF on every
//! connection.
//!
//! Concurrency model:
//! - Writes and transactions (`BEGIN IMMEDIATE`, plus nested `SAVEPOINT`s) are serialized cleanly
//!   through an async Tokio mutex (`writer_gate`) so Tokio worker threads never block waiting on
//!   the writer connection, while blocking SQLite I/O runs on Tokio's blocking pool.
//! - For file-backed databases (WAL mode), pure `SELECT` queries outside a transaction execute
//!   concurrently on a lazily-populated pool of read-only WAL connections.
//! - For in-memory databases (`:memory:`), all queries share the single connection safely through
//!   the writer gate.

pub mod decimal;
pub mod dialect;
pub mod error;
pub mod export_pg;
pub mod migrate;
pub mod seed;

use std::marker::PhantomData;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};

use chrono::{SecondsFormat, Utc};
use rusqlite::functions::FunctionFlags;
use rusqlite::types::Value as SqliteValue;
use rusqlite::{Connection, OpenFlags, params_from_iter};
use tokio::sync::{OwnedMutexGuard, Semaphore};

/// Default `PRAGMA busy_timeout` in milliseconds.
pub const DEFAULT_BUSY_TIMEOUT_MS: u32 = 5_000;

/// Maximum number of concurrent read-only WAL connections for file-backed SQLite databases.
const MAX_READ_CONNECTIONS: usize = 4;

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

/// Materialized SQLite result row that safely crosses `spawn_blocking` boundaries.
#[derive(Debug, Clone)]
pub struct SqliteRow {
    columns: Arc<Vec<String>>,
    values: Vec<SqliteValue>,
}

impl SqliteRow {
    pub fn len(&self) -> usize {
        self.values.len()
    }

    pub fn is_empty(&self) -> bool {
        self.values.is_empty()
    }

    pub fn column_names(&self) -> &[String] {
        &self.columns
    }

    pub fn value_at(&self, idx: usize) -> Result<&SqliteValue, sqlx::Error> {
        self.values
            .get(idx)
            .ok_or(sqlx::Error::ColumnIndexOutOfBounds {
                index: idx,
                len: self.values.len(),
            })
    }

    pub fn column_index(&self, name: &str) -> Result<usize, sqlx::Error> {
        self.columns
            .iter()
            .position(|c| c == name)
            .or_else(|| {
                self.columns
                    .iter()
                    .position(|c| c.eq_ignore_ascii_case(name))
            })
            .ok_or_else(|| sqlx::Error::ColumnNotFound(name.to_owned()))
    }
}

/// Lazily-populated pool of read-only WAL connections for concurrent reads on file-backed SQLite.
struct SqliteReadPool {
    path: PathBuf,
    semaphore: Arc<Semaphore>,
    idle: Mutex<Vec<Connection>>,
}

impl SqliteReadPool {
    fn new(path: PathBuf) -> Self {
        Self {
            path,
            semaphore: Arc::new(Semaphore::new(MAX_READ_CONNECTIONS)),
            idle: Mutex::new(Vec::with_capacity(MAX_READ_CONNECTIONS)),
        }
    }

    fn open_reader(path: &Path) -> Result<Connection, SqliteError> {
        let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
        let conn = Connection::open_with_flags(path, flags)?;
        conn.pragma_update(None, "busy_timeout", DEFAULT_BUSY_TIMEOUT_MS)?;
        conn.pragma_update(None, "temp_store", "MEMORY")?;
        decimal::register_decimal_functions(&conn)?;
        register_now_function(&conn)?;
        Ok(conn)
    }

    async fn run<F, T>(self: &Arc<Self>, f: F) -> Result<T, sqlx::Error>
    where
        F: FnOnce(&Connection) -> Result<T, sqlx::Error> + Send + 'static,
        T: Send + 'static,
    {
        let permit = Arc::clone(&self.semaphore)
            .acquire_owned()
            .await
            .map_err(|_| sqlx::Error::PoolClosed)?;
        let pool = Arc::clone(self);
        tokio::task::spawn_blocking(move || {
            let _permit = permit;
            let conn = {
                let mut idle = pool
                    .idle
                    .lock()
                    .map_err(|_| sqlx::Error::Protocol("SQLite read pool mutex poisoned".into()))?;
                idle.pop()
            };
            let conn = match conn {
                Some(c) => c,
                None => Self::open_reader(&pool.path).map_err(error::sqlite_err_to_sqlx)?,
            };
            let result = f(&conn);
            if let Ok(mut idle) = pool.idle.lock() {
                idle.push(conn);
            }
            result
        })
        .await
        .map_err(|e| sqlx::Error::Protocol(format!("blocking SQLite read task failed: {e}")))?
    }

    fn drain(&self) {
        if let Ok(mut idle) = self.idle.lock() {
            idle.clear();
        }
    }
}

/// Thread-safe, async-friendly handle to a configured SQLite database.
///
/// Uses `rusqlite` (already bundled with `backend-rs`) so zero extra crates are pulled into the
/// desktop or server dependency graphs. Blocking SQLite calls execute on Tokio's blocking pool via
/// [`SqliteDb::interact`], [`SqliteDb::begin`], and the unified [`crate::db::query`] helpers.
#[derive(Clone)]
pub struct SqliteDb {
    conn: Arc<Mutex<Connection>>,
    writer_gate: Arc<tokio::sync::Mutex<()>>,
    read_pool: Option<Arc<SqliteReadPool>>,
    file_path: Option<PathBuf>,
}

impl std::fmt::Debug for SqliteDb {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SqliteDb")
            .field("file_path", &self.file_path)
            .finish()
    }
}

impl SqliteDb {
    /// Opens (or creates) a file-backed SQLite database with Picsou's standard PRAGMAs, exact
    /// decimal functions, UTC `now()` UDF, and `0o600` Unix permissions.
    pub fn open(path: &Path) -> Result<Self, SqliteError> {
        if let Some(parent) = path.parent()
            && !parent.as_os_str().is_empty()
        {
            std::fs::create_dir_all(parent)?;
        }
        #[cfg(unix)]
        ensure_private_file(path)?;
        let flags = OpenFlags::SQLITE_OPEN_READ_WRITE
            | OpenFlags::SQLITE_OPEN_CREATE
            | OpenFlags::SQLITE_OPEN_NO_MUTEX;
        let conn = Connection::open_with_flags(path, flags)?;
        configure_connection(&conn, DEFAULT_BUSY_TIMEOUT_MS, true)?;
        #[cfg(unix)]
        restrict_sqlite_permissions(path)?;
        let owned_path = path.to_path_buf();
        Ok(Self {
            conn: Arc::new(Mutex::new(conn)),
            writer_gate: Arc::new(tokio::sync::Mutex::new(())),
            read_pool: Some(Arc::new(SqliteReadPool::new(owned_path.clone()))),
            file_path: Some(owned_path),
        })
    }

    /// Opens an in-memory SQLite database with Picsou's standard PRAGMAs, exact decimal functions,
    /// and UTC `now()` UDF registered.
    pub fn open_in_memory() -> Result<Self, SqliteError> {
        let conn = Connection::open_in_memory()?;
        configure_connection(&conn, DEFAULT_BUSY_TIMEOUT_MS, false)?;
        Ok(Self {
            conn: Arc::new(Mutex::new(conn)),
            writer_gate: Arc::new(tokio::sync::Mutex::new(())),
            read_pool: None,
            file_path: None,
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

    /// Ensures the reference seed catalog (`app_setting`, `crypto_asset`, `merchant_brand`,
    /// `merchant_alias`) is present idempotently.
    pub fn ensure_seeded(&self) -> Result<(), SqliteError> {
        seed::ensure_seeded(self)
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

    /// Starts an exclusive write transaction (`BEGIN IMMEDIATE`) serialized across async tasks.
    ///
    /// If the returned [`SqliteTx`] is dropped without calling [`SqliteTx::commit`], it rolls
    /// back automatically without panicking or deadlocking.
    pub async fn begin(&self) -> Result<SqliteTx<'_>, sqlx::Error> {
        let gate = self.acquire_writer_gate().await;
        let conn = Arc::clone(&self.conn);
        run_on_writer(Arc::clone(&conn), |c| {
            c.execute_batch("BEGIN IMMEDIATE")
                .map_err(error::rusqlite_to_sqlx)
        })
        .await?;
        Ok(SqliteTx {
            conn,
            depth: 0,
            kind: Some(SqliteTxKind::Root {
                _gate: gate,
                _marker: PhantomData,
            }),
            finished: false,
        })
    }

    /// Acquires exclusive serialized access to the SQLite writer handle (analogous to
    /// `PgPool::acquire()`).
    pub async fn acquire(&self) -> Result<SqliteConn, sqlx::Error> {
        let gate = self.acquire_writer_gate().await;
        Ok(SqliteConn {
            conn: Arc::clone(&self.conn),
            _gate: gate,
        })
    }

    /// Drains pooled WAL reader connections and runs `PRAGMA wal_checkpoint(TRUNCATE)` for a clean
    /// shutdown.
    pub async fn close(&self) {
        let _gate = self.acquire_writer_gate().await;
        if let Some(pool) = &self.read_pool {
            pool.drain();
        }
        if self.file_path.is_some() {
            let conn = Arc::clone(&self.conn);
            let _ = tokio::task::spawn_blocking(move || {
                if let Ok(c) = conn.lock() {
                    let _ = c.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);");
                }
            })
            .await;
        }
    }

    pub(crate) async fn acquire_writer_gate(&self) -> OwnedMutexGuard<()> {
        Arc::clone(&self.writer_gate).lock_owned().await
    }

    pub(crate) fn lock_connection(&self) -> Result<MutexGuard<'_, Connection>, SqliteError> {
        self.conn.lock().map_err(|_| SqliteError::MutexPoisoned)
    }

    pub(crate) async fn fetch_all(
        &self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<Vec<SqliteRow>, sqlx::Error> {
        let translated = dialect::translate_cached(sql);
        if dialect::is_read_only_sql(&translated)
            && let Some(pool) = &self.read_pool
        {
            return pool
                .run(move |conn| query_rows(conn, &translated, &params))
                .await;
        }
        let _gate = self.acquire_writer_gate().await;
        run_on_writer(Arc::clone(&self.conn), move |conn| {
            query_rows(conn, &translated, &params)
        })
        .await
    }

    pub(crate) async fn fetch_optional(
        &self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<Option<SqliteRow>, sqlx::Error> {
        let mut rows = self.fetch_all(sql, params).await?;
        if rows.is_empty() {
            Ok(None)
        } else {
            Ok(Some(rows.swap_remove(0)))
        }
    }

    pub(crate) async fn fetch_one(
        &self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<SqliteRow, sqlx::Error> {
        self.fetch_optional(sql, params)
            .await?
            .ok_or(sqlx::Error::RowNotFound)
    }

    pub(crate) async fn execute(
        &self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<u64, sqlx::Error> {
        let translated = dialect::translate_cached(sql);
        let _gate = self.acquire_writer_gate().await;
        run_on_writer(Arc::clone(&self.conn), move |conn| {
            execute_stmt(conn, &translated, &params)
        })
        .await
    }
}

/// Exclusive handle to a SQLite connection acquired via [`SqliteDb::acquire`].
pub struct SqliteConn {
    conn: Arc<Mutex<Connection>>,
    _gate: OwnedMutexGuard<()>,
}

impl SqliteConn {
    pub(crate) async fn fetch_all(
        &mut self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<Vec<SqliteRow>, sqlx::Error> {
        let translated = dialect::translate_cached(sql);
        run_on_writer(Arc::clone(&self.conn), move |conn| {
            query_rows(conn, &translated, &params)
        })
        .await
    }

    pub(crate) async fn fetch_optional(
        &mut self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<Option<SqliteRow>, sqlx::Error> {
        let mut rows = self.fetch_all(sql, params).await?;
        if rows.is_empty() {
            Ok(None)
        } else {
            Ok(Some(rows.swap_remove(0)))
        }
    }

    pub(crate) async fn fetch_one(
        &mut self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<SqliteRow, sqlx::Error> {
        self.fetch_optional(sql, params)
            .await?
            .ok_or(sqlx::Error::RowNotFound)
    }

    pub(crate) async fn execute(
        &mut self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<u64, sqlx::Error> {
        let translated = dialect::translate_cached(sql);
        run_on_writer(Arc::clone(&self.conn), move |conn| {
            execute_stmt(conn, &translated, &params)
        })
        .await
    }

    /// Starts a `BEGIN IMMEDIATE` transaction on this acquired connection.
    pub async fn begin(&mut self) -> Result<SqliteTx<'_>, sqlx::Error> {
        let conn = Arc::clone(&self.conn);
        run_on_writer(Arc::clone(&conn), |c| {
            c.execute_batch("BEGIN IMMEDIATE")
                .map_err(error::rusqlite_to_sqlx)
        })
        .await?;
        Ok(SqliteTx {
            conn,
            depth: 0,
            kind: Some(SqliteTxKind::BorrowedRoot {
                _parent: PhantomData,
            }),
            finished: false,
        })
    }

    pub(crate) fn handle(&mut self) -> SqliteHandle<'_> {
        SqliteHandle {
            conn: &self.conn,
            tx_depth: None,
        }
    }
}

/// Borrowed mutable handle to either an acquired [`SqliteConn`] or an active [`SqliteTx`].
pub struct SqliteHandle<'a> {
    conn: &'a Arc<Mutex<Connection>>,
    tx_depth: Option<usize>,
}

impl<'a> SqliteHandle<'a> {
    pub(crate) fn reborrow(&mut self) -> SqliteHandle<'_> {
        SqliteHandle {
            conn: self.conn,
            tx_depth: self.tx_depth,
        }
    }

    pub(crate) async fn fetch_all(
        &mut self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<Vec<SqliteRow>, sqlx::Error> {
        let translated = dialect::translate_cached(sql);
        run_on_writer(Arc::clone(self.conn), move |conn| {
            query_rows(conn, &translated, &params)
        })
        .await
    }

    pub(crate) async fn fetch_optional(
        &mut self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<Option<SqliteRow>, sqlx::Error> {
        let mut rows = self.fetch_all(sql, params).await?;
        if rows.is_empty() {
            Ok(None)
        } else {
            Ok(Some(rows.swap_remove(0)))
        }
    }

    pub(crate) async fn fetch_one(
        &mut self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<SqliteRow, sqlx::Error> {
        self.fetch_optional(sql, params)
            .await?
            .ok_or(sqlx::Error::RowNotFound)
    }

    pub(crate) async fn execute(
        &mut self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<u64, sqlx::Error> {
        let translated = dialect::translate_cached(sql);
        run_on_writer(Arc::clone(self.conn), move |conn| {
            execute_stmt(conn, &translated, &params)
        })
        .await
    }

    /// Starts a transaction (if not yet inside one) or a nested `SAVEPOINT` (if already in one).
    pub async fn begin(&mut self) -> Result<SqliteTx<'_>, sqlx::Error> {
        let conn = Arc::clone(self.conn);
        match self.tx_depth {
            None => {
                run_on_writer(Arc::clone(&conn), |c| {
                    c.execute_batch("BEGIN IMMEDIATE")
                        .map_err(error::rusqlite_to_sqlx)
                })
                .await?;
                Ok(SqliteTx {
                    conn,
                    depth: 0,
                    kind: Some(SqliteTxKind::BorrowedRoot {
                        _parent: PhantomData,
                    }),
                    finished: false,
                })
            }
            Some(depth) => {
                let next_depth = depth + 1;
                let name = format!("picsou_sp_{next_depth}");
                let sp_sql = format!("SAVEPOINT {name}");
                run_on_writer(Arc::clone(&conn), move |c| {
                    c.execute_batch(&sp_sql).map_err(error::rusqlite_to_sqlx)
                })
                .await?;
                Ok(SqliteTx {
                    conn,
                    depth: next_depth,
                    kind: Some(SqliteTxKind::Savepoint {
                        name,
                        _parent: PhantomData,
                    }),
                    finished: false,
                })
            }
        }
    }
}

enum SqliteTxKind<'a> {
    Root {
        _gate: OwnedMutexGuard<()>,
        _marker: PhantomData<&'a ()>,
    },
    BorrowedRoot {
        _parent: PhantomData<&'a mut ()>,
    },
    Savepoint {
        name: String,
        _parent: PhantomData<&'a mut ()>,
    },
}

/// Active SQLite transaction (`BEGIN IMMEDIATE`) or nested savepoint (`SAVEPOINT`).
///
/// Rolls back automatically on `Drop` if [`SqliteTx::commit`] was not called.
pub struct SqliteTx<'a> {
    conn: Arc<Mutex<Connection>>,
    depth: usize,
    kind: Option<SqliteTxKind<'a>>,
    finished: bool,
}

impl<'a> SqliteTx<'a> {
    pub(crate) fn handle(&mut self) -> SqliteHandle<'_> {
        SqliteHandle {
            conn: &self.conn,
            tx_depth: Some(self.depth),
        }
    }

    /// Starts a nested `SAVEPOINT` inside this transaction.
    pub async fn begin(&mut self) -> Result<SqliteTx<'_>, sqlx::Error> {
        let next_depth = self.depth + 1;
        let name = format!("picsou_sp_{next_depth}");
        let sp_sql = format!("SAVEPOINT {name}");
        let conn = Arc::clone(&self.conn);
        run_on_writer(Arc::clone(&conn), move |c| {
            c.execute_batch(&sp_sql).map_err(error::rusqlite_to_sqlx)
        })
        .await?;
        Ok(SqliteTx {
            conn,
            depth: next_depth,
            kind: Some(SqliteTxKind::Savepoint {
                name,
                _parent: PhantomData,
            }),
            finished: false,
        })
    }

    /// Commits the transaction (or releases the nested savepoint).
    pub async fn commit(mut self) -> Result<(), sqlx::Error> {
        let sql = match self.kind.as_ref() {
            Some(SqliteTxKind::Savepoint { name, .. }) => format!("RELEASE SAVEPOINT {name}"),
            _ => "COMMIT".to_owned(),
        };
        let conn = Arc::clone(&self.conn);
        run_on_writer(conn, move |c| {
            c.execute_batch(&sql).map_err(error::rusqlite_to_sqlx)
        })
        .await?;
        self.finished = true;
        Ok(())
    }

    /// Explicitly rolls back the transaction (or rolls back and releases the nested savepoint).
    pub async fn rollback(mut self) -> Result<(), sqlx::Error> {
        let sql = match self.kind.as_ref() {
            Some(SqliteTxKind::Savepoint { name, .. }) => {
                format!("ROLLBACK TO SAVEPOINT {name}; RELEASE SAVEPOINT {name};")
            }
            _ => "ROLLBACK".to_owned(),
        };
        let conn = Arc::clone(&self.conn);
        self.finished = true;
        run_on_writer(conn, move |c| {
            c.execute_batch(&sql).map_err(error::rusqlite_to_sqlx)
        })
        .await
    }

    pub(crate) async fn fetch_all(
        &mut self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<Vec<SqliteRow>, sqlx::Error> {
        let translated = dialect::translate_cached(sql);
        run_on_writer(Arc::clone(&self.conn), move |conn| {
            query_rows(conn, &translated, &params)
        })
        .await
    }

    pub(crate) async fn fetch_optional(
        &mut self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<Option<SqliteRow>, sqlx::Error> {
        let mut rows = self.fetch_all(sql, params).await?;
        if rows.is_empty() {
            Ok(None)
        } else {
            Ok(Some(rows.swap_remove(0)))
        }
    }

    pub(crate) async fn fetch_one(
        &mut self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<SqliteRow, sqlx::Error> {
        self.fetch_optional(sql, params)
            .await?
            .ok_or(sqlx::Error::RowNotFound)
    }

    pub(crate) async fn execute(
        &mut self,
        sql: &str,
        params: Vec<SqliteValue>,
    ) -> Result<u64, sqlx::Error> {
        let translated = dialect::translate_cached(sql);
        run_on_writer(Arc::clone(&self.conn), move |conn| {
            execute_stmt(conn, &translated, &params)
        })
        .await
    }
}

impl Drop for SqliteTx<'_> {
    fn drop(&mut self) {
        if self.finished {
            return;
        }
        let sql = match self.kind.as_ref() {
            Some(SqliteTxKind::Savepoint { name, .. }) => {
                format!("ROLLBACK TO SAVEPOINT {name}; RELEASE SAVEPOINT {name};")
            }
            _ => "ROLLBACK".to_owned(),
        };
        // `self` holds exclusive ownership of the writer gate (or an exclusive borrow of the
        // parent transaction holding it), so locking `self.conn` here cannot deadlock and ensures
        // rollback completes strictly BEFORE the writer gate is released when `self.kind` drops.
        if let Ok(conn) = self.conn.lock() {
            let _ = conn.execute_batch(&sql);
        }
        self.finished = true;
    }
}

async fn run_on_writer<F, T>(conn: Arc<Mutex<Connection>>, f: F) -> Result<T, sqlx::Error>
where
    F: FnOnce(&mut Connection) -> Result<T, sqlx::Error> + Send + 'static,
    T: Send + 'static,
{
    tokio::task::spawn_blocking(move || {
        let mut guard = conn
            .lock()
            .map_err(|_| sqlx::Error::Protocol("SQLite connection mutex poisoned".into()))?;
        f(&mut guard)
    })
    .await
    .map_err(|e| sqlx::Error::Protocol(format!("blocking SQLite task failed: {e}")))?
}

fn query_rows(
    conn: &Connection,
    sql: &str,
    params: &[SqliteValue],
) -> Result<Vec<SqliteRow>, sqlx::Error> {
    let mut stmt = conn.prepare_cached(sql).map_err(error::rusqlite_to_sqlx)?;
    let col_count = stmt.column_count();
    let columns = Arc::new(
        stmt.column_names()
            .into_iter()
            .map(str::to_owned)
            .collect::<Vec<_>>(),
    );
    let mut raw_rows = stmt
        .query(params_from_iter(params.iter()))
        .map_err(error::rusqlite_to_sqlx)?;
    let mut out = Vec::new();
    while let Some(row) = raw_rows.next().map_err(error::rusqlite_to_sqlx)? {
        let mut values = Vec::with_capacity(col_count);
        for idx in 0..col_count {
            let val_ref = row.get_ref(idx).map_err(error::rusqlite_to_sqlx)?;
            values.push(SqliteValue::from(val_ref));
        }
        out.push(SqliteRow {
            columns: Arc::clone(&columns),
            values,
        });
    }
    Ok(out)
}

fn execute_stmt(conn: &Connection, sql: &str, params: &[SqliteValue]) -> Result<u64, sqlx::Error> {
    let mut stmt = conn.prepare_cached(sql).map_err(error::rusqlite_to_sqlx)?;
    let changed = stmt
        .execute(params_from_iter(params.iter()))
        .map_err(error::rusqlite_to_sqlx)?;
    Ok(u64::try_from(changed).unwrap_or(0))
}

/// Registers the `now()` SQLite scalar UDF returning an RFC 3339 UTC microsecond timestamp string.
pub fn register_now_function(conn: &Connection) -> rusqlite::Result<()> {
    let flags = FunctionFlags::SQLITE_UTF8 | FunctionFlags::SQLITE_INNOCUOUS;
    conn.create_scalar_function("now", 0, flags, |_ctx| {
        Ok(Utc::now().to_rfc3339_opts(SecondsFormat::Micros, true))
    })
}

/// Applies Picsou's mandatory PRAGMAs and registers all exact decimal SQLite functions plus `now()`.
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
    register_now_function(conn)?;
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
