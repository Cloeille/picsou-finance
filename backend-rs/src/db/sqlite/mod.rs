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
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use chrono::{SecondsFormat, Utc};
use rusqlite::functions::FunctionFlags;
use rusqlite::types::Value as SqliteValue;
use rusqlite::{Connection, OpenFlags, params_from_iter};
use rust_decimal::Decimal;
use tokio::sync::{OwnedMutexGuard, OwnedSemaphorePermit, Semaphore};

/// Default `PRAGMA busy_timeout` in milliseconds.
pub const DEFAULT_BUSY_TIMEOUT_MS: u32 = 5_000;

/// Maximum time a task waits to acquire the serialized SQLite `writer_gate` before failing fast
/// with [`sqlx::Error::PoolTimedOut`] instead of hanging indefinitely on a re-entrant write lock.
pub const WRITER_GATE_TIMEOUT: Duration = Duration::from_secs(30);

/// Maximum number of concurrent read-only WAL connections for file-backed SQLite databases.
const MAX_READ_CONNECTIONS: usize = 4;
/// Read snapshots open at once (Db::begin_read). They last as long as their reader takes, so
/// they have slots of their own: a slow one never takes a connection from the read pool.
const MAX_READ_SNAPSHOTS: usize = 4;

/// Errors produced by the embedded SQLite engine wrapper.
#[derive(Debug, thiserror::Error)]
pub enum SqliteError {
    #[error("SQLite error: {0}")]
    Rusqlite(#[from] rusqlite::Error),
    #[error("SQLite foreign_keys pragma did not activate")]
    ForeignKeysNotEnabled,
    #[error("SQLite connection mutex poisoned")]
    MutexPoisoned,
    #[error("SQLite writer gate timed out after {0} ms")]
    WriterGateTimeout(u64),
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
    snapshots: Arc<Semaphore>,
    idle: Mutex<Vec<Connection>>,
}

impl SqliteReadPool {
    fn new(path: PathBuf) -> Self {
        Self {
            path,
            semaphore: Arc::new(Semaphore::new(MAX_READ_CONNECTIONS)),
            snapshots: Arc::new(Semaphore::new(MAX_READ_SNAPSHOTS)),
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
        register_pg_extrema_functions(&conn)?;
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

    async fn drain_async(&self) {
        // Acquire all reader permits first so every in-flight WAL reader finishes and returns
        // its connection before we close the semaphore and drop all pooled connections. This
        // guarantees zero open readers hold a WAL snapshot during shutdown checkpointing.
        let _all = self
            .semaphore
            .acquire_many(MAX_READ_CONNECTIONS as u32)
            .await;
        let _snapshots = self.snapshots.acquire_many(MAX_READ_SNAPSHOTS as u32).await;
        self.semaphore.close();
        self.snapshots.close();
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
    ///
    /// **Startup / exclusive-access only**: bypasses the async `writer_gate`, so callers must
    /// only use this during single-owner startup, migrations, exports, or tests when no
    /// [`SqliteTx`] can be active concurrently. Use [`SqliteDb::interact`] for runtime operations.
    pub fn with_conn<F, T>(&self, f: F) -> Result<T, SqliteError>
    where
        F: FnOnce(&mut Connection) -> Result<T, SqliteError>,
    {
        let mut guard = self.lock_connection()?;
        f(&mut guard)
    }

    /// Runs a blocking SQLite operation on Tokio's blocking thread pool under the writer gate so
    /// async handlers never stall the runtime and can never interleave statements into an active
    /// [`SqliteTx`].
    pub async fn interact<F, T>(&self, f: F) -> Result<T, SqliteError>
    where
        F: FnOnce(&mut Connection) -> Result<T, SqliteError> + Send + 'static,
        T: Send + 'static,
    {
        let _gate = self.acquire_writer_gate().await.map_err(|_| {
            SqliteError::WriterGateTimeout(
                u64::try_from(WRITER_GATE_TIMEOUT.as_millis()).unwrap_or(u64::MAX),
            )
        })?;
        let conn = Arc::clone(&self.conn);
        let caller = CallerPresence::default();
        let gone = caller.flag();
        tokio::task::spawn_blocking(move || {
            let mut guard = conn.lock().map_err(|_| SqliteError::MutexPoisoned)?;
            if gone.load(Ordering::SeqCst) {
                return Err(SqliteError::Join(ABANDONED.into()));
            }
            f(&mut guard)
        })
        .await
        .map_err(|e| SqliteError::Join(e.to_string()))?
    }

    /// Applies all pending SQLite schema migrations idempotently and ensures owner-only file
    /// permissions (`0o600`) on file-backed databases and their WAL/SHM sidecars.
    pub fn migrate(&self) -> Result<usize, SqliteError> {
        let applied = self.with_conn(|conn| Ok(migrate::migrate(conn)?))?;
        #[cfg(unix)]
        if let Some(path) = &self.file_path {
            restrict_sqlite_permissions(path)?;
        }
        Ok(applied)
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
        let gate = self.acquire_writer_gate().await?;
        let conn = Arc::clone(&self.conn);
        open_transaction(&conn, BEGIN.into(), ROLLBACK.into()).await?;
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

    /// Starts a read-only transaction on a WAL reader of its own: it sees the database as it was
    /// at its first read, however long it stays open, and never takes the writer. An in-memory
    /// database has one connection, so there it is a transaction of the writer.
    pub async fn begin_read(&self) -> Result<SqliteTx<'_>, sqlx::Error> {
        let Some(pool) = &self.read_pool else {
            return self.begin().await;
        };
        let permit = Arc::clone(&pool.snapshots)
            .acquire_owned()
            .await
            .map_err(|_| sqlx::Error::PoolClosed)?;
        let path = pool.path.clone();
        let reader = tokio::task::spawn_blocking(move || SqliteReadPool::open_reader(&path))
            .await
            .map_err(|e| sqlx::Error::Protocol(format!("blocking SQLite read task failed: {e}")))?
            .map_err(error::sqlite_err_to_sqlx)?;
        let conn = Arc::new(Mutex::new(reader));
        open_transaction(&conn, "BEGIN".into(), ROLLBACK.into()).await?;
        Ok(SqliteTx {
            conn,
            depth: 0,
            kind: Some(SqliteTxKind::Snapshot {
                _reader: permit,
                _marker: PhantomData,
            }),
            finished: false,
        })
    }

    /// Acquires exclusive serialized access to the SQLite writer handle (analogous to
    /// `PgPool::acquire()`).
    pub async fn acquire(&self) -> Result<SqliteConn, sqlx::Error> {
        let gate = self.acquire_writer_gate().await?;
        Ok(SqliteConn {
            conn: Arc::clone(&self.conn),
            _gate: gate,
        })
    }

    /// Executes a raw SQL script (translating PostgreSQL syntax via `dialect`) under the writer gate.
    pub async fn execute_batch(&self, sql: &str) -> Result<(), sqlx::Error> {
        let translated = dialect::translate_pg_to_sqlite(sql);
        let _gate = self.acquire_writer_gate().await?;
        run_on_writer(Arc::clone(&self.conn), move |conn| {
            conn.execute_batch(&translated)
                .map_err(error::rusqlite_to_sqlx)
        })
        .await
    }

    /// Returns a stable identifier for this SQLite database (file path or unique in-memory address).
    pub fn identifier(&self) -> String {
        match &self.file_path {
            Some(path) => path.to_string_lossy().into_owned(),
            None => format!("sqlite:memory:{:p}", Arc::as_ptr(&self.conn)),
        }
    }

    /// Drains all in-flight and pooled WAL reader connections and runs
    /// `PRAGMA wal_checkpoint(TRUNCATE)` for a clean shutdown.
    pub async fn close(&self) {
        let gate = match self.acquire_writer_gate().await {
            Ok(gate) => Some(gate),
            Err(err) => {
                tracing::warn!(
                    "SQLite close() could not acquire writer_gate ({err}); draining WAL readers without checkpoint"
                );
                None
            }
        };
        if let Some(pool) = &self.read_pool {
            pool.drain_async().await;
        }
        if gate.is_some() && self.file_path.is_some() {
            let conn = Arc::clone(&self.conn);
            let _ = tokio::task::spawn_blocking(move || {
                if let Ok(c) = conn.lock() {
                    let _ = c.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);");
                }
            })
            .await;
        }
    }

    pub(crate) async fn acquire_writer_gate(&self) -> Result<OwnedMutexGuard<()>, sqlx::Error> {
        self.acquire_writer_gate_with_timeout(WRITER_GATE_TIMEOUT)
            .await
    }

    pub(crate) async fn acquire_writer_gate_with_timeout(
        &self,
        timeout: Duration,
    ) -> Result<OwnedMutexGuard<()>, sqlx::Error> {
        match tokio::time::timeout(timeout, Arc::clone(&self.writer_gate).lock_owned()).await {
            Ok(guard) => Ok(guard),
            Err(_) => {
                tracing::error!(
                    "SQLite writer_gate timed out after {} ms — possible re-entrant write lock or long-running transaction",
                    timeout.as_millis()
                );
                Err(sqlx::Error::PoolTimedOut)
            }
        }
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
        let _gate = self.acquire_writer_gate().await?;
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
        let _gate = self.acquire_writer_gate().await?;
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
        open_transaction(&conn, BEGIN.into(), ROLLBACK.into()).await?;
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
                open_transaction(&conn, BEGIN.into(), ROLLBACK.into()).await?;
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
                open_transaction(&conn, format!("SAVEPOINT {name}"), undo_savepoint(&name)).await?;
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
    /// A read-only transaction on its own reader. It holds one of the snapshot slots, which a
    /// shutdown waits for before it checkpoints.
    Snapshot {
        _reader: OwnedSemaphorePermit,
        _marker: PhantomData<&'a ()>,
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
        let conn = Arc::clone(&self.conn);
        open_transaction(&conn, format!("SAVEPOINT {name}"), undo_savepoint(&name)).await?;
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
        let res = run_on_writer(Arc::clone(&self.conn), move |c| {
            c.execute_batch(&sql).map_err(error::rusqlite_to_sqlx)
        })
        .await;
        if res.is_ok() {
            self.finished = true;
        }
        res
    }

    /// Explicitly rolls back the transaction (or rolls back and releases the nested savepoint).
    pub async fn rollback(mut self) -> Result<(), sqlx::Error> {
        let sql = match self.kind.as_ref() {
            Some(SqliteTxKind::Savepoint { name, .. }) => undo_savepoint(name),
            _ => ROLLBACK.to_owned(),
        };
        let res = run_on_writer(Arc::clone(&self.conn), move |c| {
            c.execute_batch(&sql).map_err(error::rusqlite_to_sqlx)
        })
        .await;
        self.finished = true;
        res
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
            Some(SqliteTxKind::Savepoint { name, .. }) => undo_savepoint(name),
            _ => ROLLBACK.to_owned(),
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

const BEGIN: &str = "BEGIN IMMEDIATE";
const ROLLBACK: &str = "ROLLBACK";
const ABANDONED: &str = "SQLite statement abandoned: its caller went away before it ran";

fn undo_savepoint(name: &str) -> String {
    format!("ROLLBACK TO SAVEPOINT {name}; RELEASE SAVEPOINT {name};")
}

/// Tells a statement queued on the blocking pool that the future awaiting it was dropped (a
/// client that disconnected, a timeout, a shutdown).
#[derive(Default)]
struct CallerPresence(Arc<AtomicBool>);

impl CallerPresence {
    fn flag(&self) -> Arc<AtomicBool> {
        Arc::clone(&self.0)
    }
}

impl Drop for CallerPresence {
    fn drop(&mut self) {
        self.0.store(true, Ordering::SeqCst);
    }
}

/// Runs `f` on the blocking pool with the writer connection. A statement whose caller went away
/// before it reached the connection is skipped: run later, it would land after the rollback of
/// its transaction, or inside the transaction of whoever took the writer gate next.
async fn run_on_writer<F, T>(conn: Arc<Mutex<Connection>>, f: F) -> Result<T, sqlx::Error>
where
    F: FnOnce(&mut Connection) -> Result<T, sqlx::Error> + Send + 'static,
    T: Send + 'static,
{
    let caller = CallerPresence::default();
    let gone = caller.flag();
    tokio::task::spawn_blocking(move || {
        let mut guard = conn
            .lock()
            .map_err(|_| sqlx::Error::Protocol("SQLite connection mutex poisoned".into()))?;
        if gone.load(Ordering::SeqCst) {
            return Err(sqlx::Error::Protocol(ABANDONED.into()));
        }
        f(&mut guard)
    })
    .await
    .map_err(|e| sqlx::Error::Protocol(format!("blocking SQLite task failed: {e}")))?
}

/// Undoes a `BEGIN` or a `SAVEPOINT` whose caller went away before it received its
/// [`SqliteTx`]. Left open on the one writer connection, it would refuse every later
/// transaction and keep every later write uncommitted.
struct UndoUnlessHandedOver {
    conn: Arc<Mutex<Connection>>,
    opened: Arc<AtomicBool>,
    undo: String,
    handed_over: bool,
}

impl Drop for UndoUnlessHandedOver {
    fn drop(&mut self) {
        if self.handed_over {
            return;
        }
        // The statement sets `opened` while it holds the connection, so once the lock is ours
        // the flag says whether there is something to undo.
        if let Ok(conn) = self.conn.lock()
            && self.opened.load(Ordering::SeqCst)
        {
            let _ = conn.execute_batch(&self.undo);
        }
    }
}

async fn open_transaction(
    conn: &Arc<Mutex<Connection>>,
    open: String,
    undo: String,
) -> Result<(), sqlx::Error> {
    let opened = Arc::new(AtomicBool::new(false));
    let mut guard = UndoUnlessHandedOver {
        conn: Arc::clone(conn),
        opened: Arc::clone(&opened),
        undo,
        handed_over: false,
    };
    let result = run_on_writer(Arc::clone(conn), move |c| {
        c.execute_batch(&open).map_err(error::rusqlite_to_sqlx)?;
        opened.store(true, Ordering::SeqCst);
        Ok(())
    })
    .await;
    guard.handed_over = true;
    result
}

fn query_rows(
    conn: &Connection,
    sql: &str,
    params: &[SqliteValue],
) -> Result<Vec<SqliteRow>, sqlx::Error> {
    let mut stmt = conn.prepare_cached(sql).map_err(error::rusqlite_to_sqlx)?;
    let col_count = stmt.column_count();
    let mut col_names = Vec::with_capacity(col_count);
    let mut col_scales = Vec::with_capacity(col_count);
    for name in stmt.column_names() {
        let scale = decimal::pg_numeric_column_scale(sql, name);
        col_names.push(name.to_owned());
        col_scales.push(scale);
    }
    let columns = Arc::new(col_names);
    let mut raw_rows = stmt
        .query(params_from_iter(params.iter()))
        .map_err(error::rusqlite_to_sqlx)?;
    let mut out = Vec::new();
    while let Some(row) = raw_rows.next().map_err(error::rusqlite_to_sqlx)? {
        let mut values = Vec::with_capacity(col_count);
        for (idx, scale) in col_scales.iter().enumerate() {
            let val_ref = row.get_ref(idx).map_err(error::rusqlite_to_sqlx)?;
            let mut val = SqliteValue::from(val_ref);
            if let Some(s) = *scale {
                match &val {
                    SqliteValue::Text(raw) => {
                        if let Some(formatted) = decimal::format_pg_numeric_scale(raw, s) {
                            val = SqliteValue::Text(formatted);
                        }
                    }
                    SqliteValue::Integer(n) => {
                        if let Some(formatted) = decimal::format_pg_numeric_scale(&n.to_string(), s)
                        {
                            val = SqliteValue::Text(formatted);
                        }
                    }
                    _ => {}
                }
            }
            values.push(val);
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
    if stmt.column_count() > 0 {
        let mut rows = stmt
            .query(params_from_iter(params.iter()))
            .map_err(error::rusqlite_to_sqlx)?;
        while rows.next().map_err(error::rusqlite_to_sqlx)?.is_some() {}
        return Ok(conn.changes());
    }
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

/// Registers PostgreSQL-compatible `pg_least(...)` and `pg_greatest(...)` variadic scalar UDFs.
///
/// Unlike SQLite's built-in scalar `MIN(a, b)` / `MAX(a, b)`, PostgreSQL `LEAST` and `GREATEST`
/// ignore `NULL` arguments (returning `NULL` only when every argument is `NULL`) and compare
/// exact `NUMERIC` values numerically rather than lexicographically.
pub fn register_pg_extrema_functions(conn: &Connection) -> rusqlite::Result<()> {
    let flags = FunctionFlags::SQLITE_UTF8
        | FunctionFlags::SQLITE_INNOCUOUS
        | FunctionFlags::SQLITE_DETERMINISTIC;
    conn.create_scalar_function("pg_least", -1, flags, |ctx| eval_pg_extrema(ctx, true))?;
    conn.create_scalar_function("pg_greatest", -1, flags, |ctx| eval_pg_extrema(ctx, false))?;
    Ok(())
}

fn eval_pg_extrema(
    ctx: &rusqlite::functions::Context<'_>,
    pick_min: bool,
) -> rusqlite::Result<SqliteValue> {
    let mut best: Option<SqliteValue> = None;
    for i in 0..ctx.len() {
        let val = SqliteValue::from(ctx.get_raw(i));
        if matches!(val, SqliteValue::Null) {
            continue;
        }
        best = match best {
            None => Some(val),
            Some(current) => {
                let ord = compare_pg_values(&val, &current);
                let replace = if pick_min { ord.is_lt() } else { ord.is_gt() };
                Some(if replace { val } else { current })
            }
        };
    }
    Ok(best.unwrap_or(SqliteValue::Null))
}

fn compare_pg_values(a: &SqliteValue, b: &SqliteValue) -> std::cmp::Ordering {
    match (a, b) {
        (SqliteValue::Integer(x), SqliteValue::Integer(y)) => x.cmp(y),
        (SqliteValue::Text(x), SqliteValue::Text(y)) => {
            if let (Ok(dx), Ok(dy)) = (x.parse::<Decimal>(), y.parse::<Decimal>()) {
                dx.cmp(&dy)
            } else {
                x.cmp(y)
            }
        }
        (SqliteValue::Integer(x), SqliteValue::Text(y)) => {
            if let Ok(dy) = y.parse::<Decimal>() {
                Decimal::from(*x).cmp(&dy)
            } else {
                x.to_string().cmp(y)
            }
        }
        (SqliteValue::Text(x), SqliteValue::Integer(y)) => {
            if let Ok(dx) = x.parse::<Decimal>() {
                dx.cmp(&Decimal::from(*y))
            } else {
                x.cmp(&y.to_string())
            }
        }
        (SqliteValue::Real(x), SqliteValue::Real(y)) => x.total_cmp(y),
        (SqliteValue::Integer(x), SqliteValue::Real(y)) => {
            Decimal::try_from(*y).map_or(std::cmp::Ordering::Equal, |dy| Decimal::from(*x).cmp(&dy))
        }
        (SqliteValue::Real(x), SqliteValue::Integer(y)) => {
            Decimal::try_from(*x).map_or(std::cmp::Ordering::Equal, |dx| dx.cmp(&Decimal::from(*y)))
        }
        (SqliteValue::Blob(x), SqliteValue::Blob(y)) => x.cmp(y),
        _ => std::cmp::Ordering::Equal,
    }
}

/// Applies Picsou's mandatory PRAGMAs and registers all exact decimal SQLite functions plus `now()`
/// and `pg_least`/`pg_greatest`.
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
    register_pg_extrema_functions(conn)?;
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

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn writer_gate_times_out_with_pool_timed_out_when_tx_is_held() {
        let db = SqliteDb::open_in_memory().expect("open in-memory SQLite");
        db.migrate().expect("migrate");

        let tx = db.begin().await.expect("begin tx");
        let started = std::time::Instant::now();
        let err = db
            .acquire_writer_gate_with_timeout(Duration::from_millis(50))
            .await
            .expect_err("second writer gate acquisition must time out while tx is held");
        assert!(
            matches!(err, sqlx::Error::PoolTimedOut),
            "expected PoolTimedOut, got {err:?}"
        );
        assert!(
            started.elapsed() < Duration::from_secs(2),
            "timeout should fail promptly, took {:?}",
            started.elapsed()
        );

        drop(tx);
        let _guard = db
            .acquire_writer_gate_with_timeout(Duration::from_millis(50))
            .await
            .expect("writer gate must be acquirable after tx finishes");
    }

    /// Keeps the writer connection busy from another thread, so the next statement stays queued
    /// behind it. The connection is let go a moment after `release` gets a message.
    fn hold_connection(
        db: &SqliteDb,
    ) -> (std::sync::mpsc::Sender<()>, std::thread::JoinHandle<()>) {
        let conn = Arc::clone(&db.conn);
        let (held, is_held) = std::sync::mpsc::channel();
        let (release, released) = std::sync::mpsc::channel::<()>();
        let holder = std::thread::spawn(move || {
            let _guard = conn.lock().expect("lock the connection");
            held.send(()).expect("report the lock");
            let _ = released.recv();
            std::thread::sleep(Duration::from_millis(50));
        });
        is_held.recv().expect("the connection is held");
        (release, holder)
    }

    /// Every queued statement keeps a handle on the connection until it has run or been skipped.
    async fn queued_statements_have_run(db: &SqliteDb) {
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while Arc::strong_count(&db.conn) > 1 {
            assert!(
                std::time::Instant::now() < deadline,
                "a queued statement never ran"
            );
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    }

    #[tokio::test]
    async fn a_begin_whose_caller_went_away_leaves_no_transaction_open() {
        let db = SqliteDb::open_in_memory().expect("open in-memory SQLite");
        let (release, holder) = hold_connection(&db);
        {
            let begin = db.begin();
            tokio::pin!(begin);
            assert!(futures::poll!(begin.as_mut()).is_pending());
            release.send(()).expect("release the connection");
        }
        holder.join().expect("join the holder");
        queued_statements_have_run(&db).await;

        assert!(
            db.with_conn(|conn| Ok(conn.is_autocommit()))
                .expect("read the connection state"),
            "the abandoned BEGIN left the writer connection inside a transaction"
        );
        db.begin()
            .await
            .expect("a later transaction opens")
            .commit()
            .await
            .expect("and commits");
    }

    #[tokio::test]
    async fn a_begin_that_ran_before_its_caller_went_away_is_rolled_back() {
        let db = SqliteDb::open_in_memory().expect("open in-memory SQLite");
        let in_transaction = || {
            !db.with_conn(|conn| Ok(conn.is_autocommit()))
                .expect("read the connection state")
        };
        {
            let begin = db.begin();
            tokio::pin!(begin);
            assert!(futures::poll!(begin.as_mut()).is_pending());
            // BEGIN IMMEDIATE runs on the blocking pool; the future is not polled again, so
            // nobody ever receives the transaction it opened.
            let deadline = std::time::Instant::now() + Duration::from_secs(5);
            while !in_transaction() {
                assert!(std::time::Instant::now() < deadline, "BEGIN never ran");
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        }

        assert!(
            !in_transaction(),
            "the transaction nobody received stayed open"
        );
        db.begin()
            .await
            .expect("a later transaction opens")
            .commit()
            .await
            .expect("and commits");
    }

    #[tokio::test]
    async fn a_savepoint_nobody_received_is_released() {
        let db = SqliteDb::open_in_memory().expect("open in-memory SQLite");
        let mut tx = db.begin().await.expect("begin");
        {
            let nested = tx.begin();
            tokio::pin!(nested);
            assert!(futures::poll!(nested.as_mut()).is_pending());
            tokio::time::sleep(Duration::from_millis(50)).await;
        }

        let leftover = tx
            .execute("RELEASE SAVEPOINT picsou_sp_1", Vec::new())
            .await;
        assert!(leftover.is_err(), "the abandoned savepoint is still open");
        tx.commit().await.expect("the outer transaction commits");
    }

    #[tokio::test]
    async fn a_statement_whose_caller_went_away_never_runs_after_the_rollback() {
        let db = SqliteDb::open_in_memory().expect("open in-memory SQLite");
        db.execute_batch("CREATE TABLE cancelled (id INTEGER)")
            .await
            .expect("create the table");
        let mut tx = db.begin().await.expect("begin");
        let (release, holder) = hold_connection(&db);
        {
            let insert = tx.execute("INSERT INTO cancelled (id) VALUES (1)", Vec::new());
            tokio::pin!(insert);
            assert!(futures::poll!(insert.as_mut()).is_pending());
        }
        release.send(()).expect("release the connection");
        drop(tx);
        holder.join().expect("join the holder");
        queued_statements_have_run(&db).await;

        let rows: i64 = db
            .with_conn(|conn| {
                Ok(conn.query_row("SELECT count(*) FROM cancelled", [], |r| r.get(0))?)
            })
            .expect("count the rows");
        assert_eq!(
            rows, 0,
            "the abandoned INSERT was written outside its transaction"
        );
    }
}
