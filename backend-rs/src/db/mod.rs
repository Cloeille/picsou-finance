//! Dual-engine database layer (`PostgreSQL` + embedded `SQLite`), unified query builders, and the
//! Flyway-compatible migrator.

pub mod args;
pub mod flyway;
pub mod query;
pub mod release_cli;
pub mod row;
pub mod schema;
pub mod sqlite;

pub use picsou_db_macros::{DbEnum, DbFromRow};

pub use self::args::{DbArgs, DbBind};
pub use self::query::{DbExec, DbQuery, DbQueryAs, DbQueryResult, DbQueryScalar};
pub use self::row::{DbDecode, DbFromRow, DbIndex, DbRow};

use sqlx::pool::PoolConnection;
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use sqlx::{PgConnection, PgPool, Postgres, Transaction};

use crate::config::{Config, DatabaseConfig, Datasource, SqliteTarget};
use crate::db::sqlite::error::sqlite_err_to_sqlx;
use crate::db::sqlite::{SqliteConn, SqliteDb, SqliteHandle, SqliteTx};

/// Dual-engine database handle supporting both PostgreSQL and embedded SQLite transparently.
#[derive(Clone, Debug)]
pub enum Db {
    Postgres(PgPool),
    Sqlite(SqliteDb),
}

impl From<PgPool> for Db {
    fn from(pool: PgPool) -> Self {
        Self::Postgres(pool)
    }
}

impl From<SqliteDb> for Db {
    fn from(db: SqliteDb) -> Self {
        Self::Sqlite(db)
    }
}

impl Db {
    /// Returns `true` when backed by embedded SQLite.
    pub fn is_sqlite(&self) -> bool {
        matches!(self, Self::Sqlite(_))
    }

    /// Returns `true` when backed by PostgreSQL.
    pub fn is_postgres(&self) -> bool {
        matches!(self, Self::Postgres(_))
    }

    /// Borrows the underlying `PgPool` when backed by PostgreSQL.
    pub fn as_pg(&self) -> Option<&PgPool> {
        match self {
            Self::Postgres(pool) => Some(pool),
            Self::Sqlite(_) => None,
        }
    }

    /// Borrows the underlying `PgPool`, panicking if backed by SQLite (bridge for PG-only tools
    /// such as Flyway and `export_pg` and incremental migration).
    pub fn expect_pg(&self) -> &PgPool {
        self.as_pg()
            .expect("expected PostgreSQL Db engine, found SQLite")
    }

    /// Borrows the underlying [`SqliteDb`] when backed by SQLite.
    pub fn as_sqlite(&self) -> Option<&SqliteDb> {
        match self {
            Self::Sqlite(sq) => Some(sq),
            Self::Postgres(_) => None,
        }
    }

    /// Starts a database transaction (`BEGIN` on PostgreSQL, `BEGIN IMMEDIATE` on SQLite).
    pub async fn begin(&self) -> Result<DbTx<'_>, sqlx::Error> {
        match self {
            Self::Postgres(pool) => Ok(DbTx::Postgres(pool.begin().await?)),
            Self::Sqlite(sq) => Ok(DbTx::Sqlite(sq.begin().await?)),
        }
    }

    /// Starts a read-only transaction: one consistent view for a read that lasts (the data
    /// export). On SQLite it is a WAL snapshot on a reader, which leaves the writer to everyone
    /// else for as long as it stays open.
    pub async fn begin_read(&self) -> Result<DbTx<'_>, sqlx::Error> {
        match self {
            Self::Postgres(pool) => {
                let mut tx = pool.begin().await?;
                sqlx::query("SET TRANSACTION READ ONLY")
                    .execute(&mut *tx)
                    .await?;
                Ok(DbTx::Postgres(tx))
            }
            Self::Sqlite(sq) => Ok(DbTx::Sqlite(sq.begin_read().await?)),
        }
    }

    /// Acquires a single connection handle from the pool (`PoolConnection<Postgres>` on PostgreSQL,
    /// serialized writer handle on SQLite).
    pub async fn acquire(&self) -> Result<DbConn, sqlx::Error> {
        match self {
            Self::Postgres(pool) => Ok(DbConn::Postgres(pool.acquire().await?)),
            Self::Sqlite(sq) => Ok(DbConn::Sqlite(sq.acquire().await?)),
        }
    }

    /// Returns a pooled [`DbHandle`] that dispatches each statement through the pool without
    /// pinning a connection (or acquiring SQLite's `writer_gate`) across awaits or external I/O.
    ///
    /// Prefer this over [`Db::acquire`] in read-only handlers and helpers that call external HTTP
    /// services or re-entrant `&Db` readers (such as `PriceRead` / `IntradayPrices`).
    pub fn pool_handle(&self) -> DbHandle<'_> {
        DbHandle::Pool(self)
    }

    /// Executes a raw SQL script (possibly containing multiple semicolon-separated statements) on
    /// either PostgreSQL or SQLite (translating PostgreSQL syntax via `db::sqlite::dialect`).
    pub async fn execute_batch(&self, sql: &str) -> Result<(), sqlx::Error> {
        match self {
            Self::Postgres(pool) => {
                sqlx::raw_sql(sql).execute(pool).await?;
                Ok(())
            }
            Self::Sqlite(sq) => sq.execute_batch(sql).await,
        }
    }

    /// Returns a stable identifier for this database instance (database name on PostgreSQL,
    /// file path or in-memory identity on SQLite).
    pub fn identifier(&self) -> String {
        match self {
            Self::Postgres(pool) => pool
                .connect_options()
                .get_database()
                .unwrap_or_default()
                .to_owned(),
            Self::Sqlite(sq) => sq.identifier(),
        }
    }

    /// Gracefully closes the pool (on SQLite, drains WAL readers and checkpoints WAL).
    pub async fn close(&self) {
        match self {
            Self::Postgres(pool) => pool.close().await,
            Self::Sqlite(sq) => sq.close().await,
        }
    }
}

/// Active dual-engine transaction or nested savepoint.
///
/// Rolls back automatically on `Drop` if [`DbTx::commit`] was not called.
pub enum DbTx<'a> {
    Postgres(Transaction<'a, Postgres>),
    Sqlite(SqliteTx<'a>),
}

impl<'a> DbTx<'a> {
    /// Returns `true` when backed by embedded SQLite.
    pub fn is_sqlite(&self) -> bool {
        matches!(self, Self::Sqlite(_))
    }

    /// Starts a nested savepoint inside this transaction (`SAVEPOINT` on both engines).
    pub async fn begin(&mut self) -> Result<DbTx<'_>, sqlx::Error> {
        match self {
            Self::Postgres(tx) => {
                use sqlx::Acquire;
                Ok(DbTx::Postgres(tx.begin().await?))
            }
            Self::Sqlite(tx) => Ok(DbTx::Sqlite(tx.begin().await?)),
        }
    }

    /// Commits the transaction or releases the nested savepoint.
    pub async fn commit(self) -> Result<(), sqlx::Error> {
        match self {
            Self::Postgres(tx) => tx.commit().await,
            Self::Sqlite(tx) => tx.commit().await,
        }
    }

    /// Explicitly rolls back the transaction or rolls back to the nested savepoint.
    pub async fn rollback(self) -> Result<(), sqlx::Error> {
        match self {
            Self::Postgres(tx) => tx.rollback().await,
            Self::Sqlite(tx) => tx.rollback().await,
        }
    }
}

/// Acquired dual-engine connection handle returned by [`Db::acquire`].
pub enum DbConn {
    Postgres(PoolConnection<Postgres>),
    Sqlite(SqliteConn),
}

impl DbConn {
    /// Returns `true` when backed by embedded SQLite.
    pub fn is_sqlite(&self) -> bool {
        matches!(self, Self::Sqlite(_))
    }

    /// Starts a transaction on this acquired connection.
    pub async fn begin(&mut self) -> Result<DbTx<'_>, sqlx::Error> {
        match self {
            Self::Postgres(conn) => {
                use sqlx::Connection;
                Ok(DbTx::Postgres(conn.begin().await?))
            }
            Self::Sqlite(conn) => Ok(DbTx::Sqlite(conn.begin().await?)),
        }
    }
}

/// Borrowed mutable handle to any active dual-engine connection or transaction (`&mut DbConn`,
/// `&mut DbTx`, `&mut PgConnection`, `&mut sqlx::Transaction<'_, Postgres>`).
///
/// Drop-in dual-engine replacement for `&mut PgConnection` in multi-statement repository helpers
/// and `#[async_trait]` seams: pass `&mut *conn` to nested helpers or `db::query*`, and call
/// `conn.begin().await?` for nested transactions / savepoints.
pub enum DbHandle<'a> {
    Postgres(&'a mut PgConnection),
    Sqlite(SqliteHandle<'a>),
    Pool(&'a Db),
}

impl<'a> DbHandle<'a> {
    /// Returns `true` when backed by embedded SQLite.
    pub fn is_sqlite(&self) -> bool {
        match self {
            Self::Sqlite(_) => true,
            Self::Postgres(_) => false,
            Self::Pool(db) => db.is_sqlite(),
        }
    }

    /// Starts a transaction (if not yet inside one) or a nested `SAVEPOINT` (if already inside
    /// a transaction) on both PostgreSQL and SQLite.
    pub async fn begin(&mut self) -> Result<DbTx<'_>, sqlx::Error> {
        match self {
            Self::Postgres(conn) => {
                use sqlx::Connection;
                Ok(DbTx::Postgres(conn.begin().await?))
            }
            Self::Sqlite(handle) => Ok(DbTx::Sqlite(handle.begin().await?)),
            Self::Pool(db) => db.begin().await,
        }
    }
}

/// Converts a mutable connection or transaction into a borrowed [`DbHandle`] uniformly across
/// both PostgreSQL (`PgConnection`, `PoolConnection<Postgres>`, `Transaction<'_, Postgres>`) and
/// dual-engine (`DbConn`, `DbTx`, `DbHandle`) handles.
pub trait AsDbHandle {
    fn as_handle(&mut self) -> DbHandle<'_>;
}

impl AsDbHandle for DbConn {
    fn as_handle(&mut self) -> DbHandle<'_> {
        match self {
            Self::Postgres(conn) => DbHandle::Postgres(&mut *conn),
            Self::Sqlite(conn) => DbHandle::Sqlite(conn.handle()),
        }
    }
}

impl AsDbHandle for DbTx<'_> {
    fn as_handle(&mut self) -> DbHandle<'_> {
        match self {
            Self::Postgres(tx) => DbHandle::Postgres(&mut *tx),
            Self::Sqlite(tx) => DbHandle::Sqlite(tx.handle()),
        }
    }
}

impl AsDbHandle for DbHandle<'_> {
    fn as_handle(&mut self) -> DbHandle<'_> {
        match self {
            Self::Postgres(conn) => DbHandle::Postgres(conn),
            Self::Sqlite(handle) => DbHandle::Sqlite(SqliteHandle::reborrow(handle)),
            Self::Pool(db) => DbHandle::Pool(db),
        }
    }
}

impl AsDbHandle for PgConnection {
    fn as_handle(&mut self) -> DbHandle<'_> {
        DbHandle::Postgres(self)
    }
}

impl AsDbHandle for PoolConnection<Postgres> {
    fn as_handle(&mut self) -> DbHandle<'_> {
        DbHandle::Postgres(&mut *self)
    }
}

impl AsDbHandle for Transaction<'_, Postgres> {
    fn as_handle(&mut self) -> DbHandle<'_> {
        DbHandle::Postgres(&mut *self)
    }
}

/// Constructs an untyped dual-engine SQL query.
pub fn query(sql: &str) -> DbQuery<'_> {
    DbQuery::new(sql)
}

/// Constructs a typed dual-engine SQL query mapping rows via [`DbFromRow`].
pub fn query_as<T: DbFromRow>(sql: &str) -> DbQueryAs<'_, T> {
    DbQueryAs::new(sql)
}

/// Constructs a single-column dual-engine SQL query decoding column 0 via [`DbDecode`].
pub fn query_scalar<T: DbDecode>(sql: &str) -> DbQueryScalar<'_, T> {
    DbQueryScalar::new(sql)
}

/// Returns `true` if `err` is a UNIQUE or PRIMARY KEY constraint violation on either PostgreSQL
/// (`23505`) or SQLite (`SQLITE_CONSTRAINT_UNIQUE` / `SQLITE_CONSTRAINT_PRIMARYKEY`).
pub fn is_unique_violation(err: &sqlx::Error) -> bool {
    err.as_database_error()
        .is_some_and(|e| e.is_unique_violation() || e.code().as_deref() == Some("23505"))
}

/// Returns `true` if `err` is a FOREIGN KEY constraint violation on either PostgreSQL (`23503`) or
/// SQLite (`SQLITE_CONSTRAINT_FOREIGNKEY`).
pub fn is_foreign_key_violation(err: &sqlx::Error) -> bool {
    err.as_database_error()
        .is_some_and(|e| e.is_foreign_key_violation() || e.code().as_deref() == Some("23503"))
}

/// Connects to the PostgreSQL pool using the legacy `Datasource` configuration.
pub fn connect_options(ds: &Datasource) -> PgConnectOptions {
    let mut options = PgConnectOptions::new()
        .host(&ds.host)
        .port(ds.port)
        .database(&ds.database)
        .username(&ds.username)
        .password(&ds.password)
        .application_name("picsou");
    // Config::load only keeps a mode the driver knows.
    if let Some(mode) = ds.ssl_mode.as_deref().and_then(|m| m.parse().ok()) {
        options = options.ssl_mode(mode);
    }
    if let Some(ca) = &ds.ssl_root_cert {
        options = options.ssl_root_cert(ca);
    }
    options
}

/// Connects to PostgreSQL directly, returning `PgPool`.
pub async fn connect(ds: &Datasource) -> Result<PgPool, sqlx::Error> {
    PgPoolOptions::new()
        .max_connections(ds.max_connections)
        .acquire_timeout(ds.connect_timeout)
        .connect_with(connect_options(ds))
        .await
}

/// Connects to whichever database engine (`PostgreSQL` or `SQLite`) is configured on [`Config`].
pub async fn db_connect(config: &Config) -> Result<Db, sqlx::Error> {
    connect_db(&config.database).await
}

/// Connects to whichever database engine is specified by [`DatabaseConfig`].
pub async fn connect_db(db_cfg: &DatabaseConfig) -> Result<Db, sqlx::Error> {
    match db_cfg {
        DatabaseConfig::Postgres(ds) => Ok(Db::Postgres(connect(ds).await?)),
        DatabaseConfig::Sqlite(target) => {
            let sq = match target {
                SqliteTarget::InMemory => SqliteDb::open_in_memory().map_err(sqlite_err_to_sqlx)?,
                SqliteTarget::File(path) => {
                    let path = path.clone();
                    tokio::task::spawn_blocking(move || SqliteDb::open(&path))
                        .await
                        .map_err(|e| {
                            sqlx::Error::Protocol(format!("blocking SQLite open failed: {e}"))
                        })?
                        .map_err(sqlite_err_to_sqlx)?
                }
            };
            Ok(Db::Sqlite(sq))
        }
    }
}

/// Helper macro implementing [`DbBind`] (owned & borrowed) and [`DbDecode`] for string-backed
/// enums that already implement `as_str(self) -> &'static str`, `FromStr`, and `sqlx` PostgreSQL
/// `Type`/`Encode`/`Decode` (such as `ledger::model::string_enum!`).
#[macro_export]
macro_rules! impl_db_string_enum {
    ($name:ty) => {
        impl $crate::db::DbBind for $name {
            fn bind_pg(
                self,
                args: &mut ::sqlx::postgres::PgArguments,
            ) -> ::core::result::Result<(), ::sqlx::error::BoxDynError> {
                ::sqlx::Arguments::add(args, self)
            }

            fn bind_null_pg(
                args: &mut ::sqlx::postgres::PgArguments,
            ) -> ::core::result::Result<(), ::sqlx::error::BoxDynError> {
                ::sqlx::Arguments::add(args, ::core::option::Option::<$name>::None)
            }

            fn to_sqlite_value(&self) -> ::rusqlite::types::Value {
                ::rusqlite::types::Value::Text(self.as_str().to_owned())
            }
        }

        impl $crate::db::DbBind for &$name {
            fn bind_pg(
                self,
                args: &mut ::sqlx::postgres::PgArguments,
            ) -> ::core::result::Result<(), ::sqlx::error::BoxDynError> {
                ::sqlx::Arguments::add(args, *self)
            }

            fn bind_null_pg(
                args: &mut ::sqlx::postgres::PgArguments,
            ) -> ::core::result::Result<(), ::sqlx::error::BoxDynError> {
                ::sqlx::Arguments::add(args, ::core::option::Option::<$name>::None)
            }

            fn to_sqlite_value(&self) -> ::rusqlite::types::Value {
                ::rusqlite::types::Value::Text(self.as_str().to_owned())
            }
        }

        impl $crate::db::DbDecode for $name {
            fn decode_pg<I>(
                row: &::sqlx::postgres::PgRow,
                index: I,
            ) -> ::core::result::Result<Self, ::sqlx::Error>
            where
                I: ::sqlx::ColumnIndex<::sqlx::postgres::PgRow>,
            {
                ::sqlx::Row::try_get::<Self, _>(row, index)
            }

            fn decode_pg_opt<I>(
                row: &::sqlx::postgres::PgRow,
                index: I,
            ) -> ::core::result::Result<::core::option::Option<Self>, ::sqlx::Error>
            where
                I: ::sqlx::ColumnIndex<::sqlx::postgres::PgRow>,
            {
                ::sqlx::Row::try_get::<::core::option::Option<Self>, _>(row, index)
            }

            fn decode_sqlite(
                value: &::rusqlite::types::Value,
            ) -> ::core::result::Result<Self, ::sqlx::Error> {
                match value {
                    ::rusqlite::types::Value::Text(raw) => raw
                        .parse::<$name>()
                        .map_err(|err| ::sqlx::Error::Decode(::std::boxed::Box::new(err))),
                    other => ::core::result::Result::Err(::sqlx::Error::Decode(
                        ::std::boxed::Box::new(::std::io::Error::new(
                            ::std::io::ErrorKind::InvalidData,
                            ::std::format!(
                                "expected SQLite TEXT for {}, got {:?}",
                                ::core::stringify!($name),
                                other
                            ),
                        )),
                    )),
                }
            }
        }
    };
}

pub use crate::impl_db_string_enum;

#[cfg(test)]
mod tests {
    use sqlx::postgres::PgSslMode;

    use super::*;

    #[test]
    fn the_connection_asks_for_the_tls_mode_of_the_url() {
        let strict = Datasource {
            ssl_mode: Some("verify-full".to_owned()),
            ..Datasource::default()
        };
        assert!(matches!(
            connect_options(&strict).get_ssl_mode(),
            PgSslMode::VerifyFull
        ));
        assert!(matches!(
            connect_options(&Datasource::default()).get_ssl_mode(),
            PgSslMode::Prefer
        ));
    }
}
