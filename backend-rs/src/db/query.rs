//! Unified dual-engine query builders (`DbQuery`, `DbQueryAs`, `DbQueryScalar`) and the
//! [`DbExec`] executor trait.

use std::marker::PhantomData;

use futures::future::BoxFuture;
use sqlx::pool::PoolConnection;
use sqlx::{PgConnection, PgPool, Postgres, Transaction};

use super::args::{DbArgs, DbBind};
use super::row::{DbDecode, DbFromRow, DbRow};
use super::{Db, DbConn, DbHandle, DbTx};

/// Result metadata returned by [`DbQuery::execute`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DbQueryResult {
    rows_affected: u64,
}

impl DbQueryResult {
    pub const fn new(rows_affected: u64) -> Self {
        Self { rows_affected }
    }

    pub const fn rows_affected(&self) -> u64 {
        self.rows_affected
    }
}

/// Unified database executor implemented for `&Db`, `&PgPool`, `&mut DbTx<'_>`, `&mut DbConn`,
/// `&mut PgConnection`, and `&mut sqlx::Transaction<'_, Postgres>`.
///
/// Repository functions accept `exec: impl DbExec<'_>` (for single-statement helpers) or `&Db` /
/// `&mut DbTx<'_>` (for multi-statement helpers).
pub trait DbExec<'e>: Send {
    fn is_sqlite(&self) -> bool;

    fn fetch_all_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Vec<DbRow>, sqlx::Error>>
    where
        Self: 'f;

    fn fetch_optional_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Option<DbRow>, sqlx::Error>>
    where
        Self: 'f;

    fn fetch_one_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbRow, sqlx::Error>>
    where
        Self: 'f;

    fn execute_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbQueryResult, sqlx::Error>>
    where
        Self: 'f;
}

macro_rules! impl_pg_exec {
    ($ty:ty, $($lt:lifetime),+ $(,)?) => {
        impl<$($lt),+> DbExec<'e> for $ty {
            fn is_sqlite(&self) -> bool {
                false
            }

            fn fetch_all_raw<'f>(
                self,
                sql: &'f str,
                args: DbArgs,
            ) -> BoxFuture<'f, Result<Vec<DbRow>, sqlx::Error>>
            where
                Self: 'f,
            {
                Box::pin(async move {
                    let pg_args = args.into_pg()?;
                    let rows = sqlx::query_with(sqlx::AssertSqlSafe(sql), pg_args)
                        .fetch_all(self)
                        .await?;
                    Ok(rows.into_iter().map(DbRow::Postgres).collect())
                })
            }

            fn fetch_optional_raw<'f>(
                self,
                sql: &'f str,
                args: DbArgs,
            ) -> BoxFuture<'f, Result<Option<DbRow>, sqlx::Error>>
            where
                Self: 'f,
            {
                Box::pin(async move {
                    let pg_args = args.into_pg()?;
                    let row = sqlx::query_with(sqlx::AssertSqlSafe(sql), pg_args)
                        .fetch_optional(self)
                        .await?;
                    Ok(row.map(DbRow::Postgres))
                })
            }

            fn fetch_one_raw<'f>(
                self,
                sql: &'f str,
                args: DbArgs,
            ) -> BoxFuture<'f, Result<DbRow, sqlx::Error>>
            where
                Self: 'f,
            {
                Box::pin(async move {
                    let pg_args = args.into_pg()?;
                    let row = sqlx::query_with(sqlx::AssertSqlSafe(sql), pg_args)
                        .fetch_one(self)
                        .await?;
                    Ok(DbRow::Postgres(row))
                })
            }

            fn execute_raw<'f>(
                self,
                sql: &'f str,
                args: DbArgs,
            ) -> BoxFuture<'f, Result<DbQueryResult, sqlx::Error>>
            where
                Self: 'f,
            {
                Box::pin(async move {
                    let pg_args = args.into_pg()?;
                    let res = sqlx::query_with(sqlx::AssertSqlSafe(sql), pg_args)
                        .execute(self)
                        .await?;
                    Ok(DbQueryResult::new(res.rows_affected()))
                })
            }
        }
    };
}

impl_pg_exec!(&'e PgPool, 'e);
impl_pg_exec!(&'e mut PgConnection, 'e);

impl<'e, 'tx> DbExec<'e> for &'e mut Transaction<'tx, Postgres> {
    fn is_sqlite(&self) -> bool {
        false
    }

    fn fetch_all_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Vec<DbRow>, sqlx::Error>>
    where
        Self: 'f,
    {
        Box::pin(async move {
            let pg_args = args.into_pg()?;
            let rows = sqlx::query_with(sqlx::AssertSqlSafe(sql), pg_args)
                .fetch_all(&mut **self)
                .await?;
            Ok(rows.into_iter().map(DbRow::Postgres).collect())
        })
    }

    fn fetch_optional_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Option<DbRow>, sqlx::Error>>
    where
        Self: 'f,
    {
        Box::pin(async move {
            let pg_args = args.into_pg()?;
            let row = sqlx::query_with(sqlx::AssertSqlSafe(sql), pg_args)
                .fetch_optional(&mut **self)
                .await?;
            Ok(row.map(DbRow::Postgres))
        })
    }

    fn fetch_one_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbRow, sqlx::Error>>
    where
        Self: 'f,
    {
        Box::pin(async move {
            let pg_args = args.into_pg()?;
            let row = sqlx::query_with(sqlx::AssertSqlSafe(sql), pg_args)
                .fetch_one(&mut **self)
                .await?;
            Ok(DbRow::Postgres(row))
        })
    }

    fn execute_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbQueryResult, sqlx::Error>>
    where
        Self: 'f,
    {
        Box::pin(async move {
            let pg_args = args.into_pg()?;
            let res = sqlx::query_with(sqlx::AssertSqlSafe(sql), pg_args)
                .execute(&mut **self)
                .await?;
            Ok(DbQueryResult::new(res.rows_affected()))
        })
    }
}

impl<'e> DbExec<'e> for &'e Db {
    fn is_sqlite(&self) -> bool {
        Db::is_sqlite(self)
    }

    fn fetch_all_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Vec<DbRow>, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            Db::Postgres(pool) => pool.fetch_all_raw(sql, args),
            Db::Sqlite(sq) => Box::pin(async move {
                let rows = sq.fetch_all(sql, args.into_sqlite()).await?;
                Ok(rows.into_iter().map(DbRow::Sqlite).collect())
            }),
        }
    }

    fn fetch_optional_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Option<DbRow>, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            Db::Postgres(pool) => pool.fetch_optional_raw(sql, args),
            Db::Sqlite(sq) => Box::pin(async move {
                let row = sq.fetch_optional(sql, args.into_sqlite()).await?;
                Ok(row.map(DbRow::Sqlite))
            }),
        }
    }

    fn fetch_one_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbRow, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            Db::Postgres(pool) => pool.fetch_one_raw(sql, args),
            Db::Sqlite(sq) => Box::pin(async move {
                let row = sq.fetch_one(sql, args.into_sqlite()).await?;
                Ok(DbRow::Sqlite(row))
            }),
        }
    }

    fn execute_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbQueryResult, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            Db::Postgres(pool) => pool.execute_raw(sql, args),
            Db::Sqlite(sq) => Box::pin(async move {
                let n = sq.execute(sql, args.into_sqlite()).await?;
                Ok(DbQueryResult::new(n))
            }),
        }
    }
}

impl<'e, 'tx> DbExec<'e> for &'e mut DbTx<'tx> {
    fn is_sqlite(&self) -> bool {
        matches!(self, DbTx::Sqlite(_))
    }

    fn fetch_all_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Vec<DbRow>, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            DbTx::Postgres(tx) => tx.fetch_all_raw(sql, args),
            DbTx::Sqlite(tx) => Box::pin(async move {
                let rows = tx.fetch_all(sql, args.into_sqlite()).await?;
                Ok(rows.into_iter().map(DbRow::Sqlite).collect())
            }),
        }
    }

    fn fetch_optional_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Option<DbRow>, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            DbTx::Postgres(tx) => tx.fetch_optional_raw(sql, args),
            DbTx::Sqlite(tx) => Box::pin(async move {
                let row = tx.fetch_optional(sql, args.into_sqlite()).await?;
                Ok(row.map(DbRow::Sqlite))
            }),
        }
    }

    fn fetch_one_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbRow, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            DbTx::Postgres(tx) => tx.fetch_one_raw(sql, args),
            DbTx::Sqlite(tx) => Box::pin(async move {
                let row = tx.fetch_one(sql, args.into_sqlite()).await?;
                Ok(DbRow::Sqlite(row))
            }),
        }
    }

    fn execute_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbQueryResult, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            DbTx::Postgres(tx) => tx.execute_raw(sql, args),
            DbTx::Sqlite(tx) => Box::pin(async move {
                let n = tx.execute(sql, args.into_sqlite()).await?;
                Ok(DbQueryResult::new(n))
            }),
        }
    }
}

impl<'e> DbExec<'e> for &'e mut DbConn {
    fn is_sqlite(&self) -> bool {
        matches!(self, DbConn::Sqlite(_))
    }

    fn fetch_all_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Vec<DbRow>, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            DbConn::Postgres(conn) => (&mut **conn).fetch_all_raw(sql, args),
            DbConn::Sqlite(conn) => Box::pin(async move {
                let rows = conn.fetch_all(sql, args.into_sqlite()).await?;
                Ok(rows.into_iter().map(DbRow::Sqlite).collect())
            }),
        }
    }

    fn fetch_optional_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Option<DbRow>, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            DbConn::Postgres(conn) => (&mut **conn).fetch_optional_raw(sql, args),
            DbConn::Sqlite(conn) => Box::pin(async move {
                let row = conn.fetch_optional(sql, args.into_sqlite()).await?;
                Ok(row.map(DbRow::Sqlite))
            }),
        }
    }

    fn fetch_one_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbRow, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            DbConn::Postgres(conn) => (&mut **conn).fetch_one_raw(sql, args),
            DbConn::Sqlite(conn) => Box::pin(async move {
                let row = conn.fetch_one(sql, args.into_sqlite()).await?;
                Ok(DbRow::Sqlite(row))
            }),
        }
    }

    fn execute_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbQueryResult, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            DbConn::Postgres(conn) => (&mut **conn).execute_raw(sql, args),
            DbConn::Sqlite(conn) => Box::pin(async move {
                let n = conn.execute(sql, args.into_sqlite()).await?;
                Ok(DbQueryResult::new(n))
            }),
        }
    }
}

impl<'e> DbExec<'e> for &'e mut PoolConnection<Postgres> {
    fn is_sqlite(&self) -> bool {
        false
    }

    fn fetch_all_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Vec<DbRow>, sqlx::Error>>
    where
        Self: 'f,
    {
        (&mut **self).fetch_all_raw(sql, args)
    }

    fn fetch_optional_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Option<DbRow>, sqlx::Error>>
    where
        Self: 'f,
    {
        (&mut **self).fetch_optional_raw(sql, args)
    }

    fn fetch_one_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbRow, sqlx::Error>>
    where
        Self: 'f,
    {
        (&mut **self).fetch_one_raw(sql, args)
    }

    fn execute_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbQueryResult, sqlx::Error>>
    where
        Self: 'f,
    {
        (&mut **self).execute_raw(sql, args)
    }
}

impl<'e, 'h> DbExec<'e> for &'e mut DbHandle<'h> {
    fn is_sqlite(&self) -> bool {
        DbHandle::is_sqlite(self)
    }

    fn fetch_all_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Vec<DbRow>, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            DbHandle::Postgres(conn) => (&mut **conn).fetch_all_raw(sql, args),
            DbHandle::Sqlite(handle) => Box::pin(async move {
                let rows = handle.fetch_all(sql, args.into_sqlite()).await?;
                Ok(rows.into_iter().map(DbRow::Sqlite).collect())
            }),
            DbHandle::Pool(db) => (*db).fetch_all_raw(sql, args),
        }
    }

    fn fetch_optional_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<Option<DbRow>, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            DbHandle::Postgres(conn) => (&mut **conn).fetch_optional_raw(sql, args),
            DbHandle::Sqlite(handle) => Box::pin(async move {
                let row = handle.fetch_optional(sql, args.into_sqlite()).await?;
                Ok(row.map(DbRow::Sqlite))
            }),
            DbHandle::Pool(db) => (*db).fetch_optional_raw(sql, args),
        }
    }

    fn fetch_one_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbRow, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            DbHandle::Postgres(conn) => (&mut **conn).fetch_one_raw(sql, args),
            DbHandle::Sqlite(handle) => Box::pin(async move {
                let row = handle.fetch_one(sql, args.into_sqlite()).await?;
                Ok(DbRow::Sqlite(row))
            }),
            DbHandle::Pool(db) => (*db).fetch_one_raw(sql, args),
        }
    }

    fn execute_raw<'f>(
        self,
        sql: &'f str,
        args: DbArgs,
    ) -> BoxFuture<'f, Result<DbQueryResult, sqlx::Error>>
    where
        Self: 'f,
    {
        match self {
            DbHandle::Postgres(conn) => (&mut **conn).execute_raw(sql, args),
            DbHandle::Sqlite(handle) => Box::pin(async move {
                let n = handle.execute(sql, args.into_sqlite()).await?;
                Ok(DbQueryResult::new(n))
            }),
            DbHandle::Pool(db) => (*db).execute_raw(sql, args),
        }
    }
}

/// Untyped dual-engine SQL query returning [`DbRow`]s or [`DbQueryResult`].
pub struct DbQuery<'q> {
    sql: &'q str,
    args: DbArgs,
}

impl<'q> DbQuery<'q> {
    pub fn new(sql: &'q str) -> Self {
        Self {
            sql,
            args: DbArgs::new(),
        }
    }

    pub fn bind<T: DbBind>(mut self, value: T) -> Self {
        self.args.push(value);
        self
    }

    pub async fn fetch_all<'e>(self, exec: impl DbExec<'e>) -> Result<Vec<DbRow>, sqlx::Error> {
        exec.fetch_all_raw(self.sql, self.args).await
    }

    pub async fn fetch_optional<'e>(
        self,
        exec: impl DbExec<'e>,
    ) -> Result<Option<DbRow>, sqlx::Error> {
        exec.fetch_optional_raw(self.sql, self.args).await
    }

    pub async fn fetch_one<'e>(self, exec: impl DbExec<'e>) -> Result<DbRow, sqlx::Error> {
        exec.fetch_one_raw(self.sql, self.args).await
    }

    pub async fn execute<'e>(self, exec: impl DbExec<'e>) -> Result<DbQueryResult, sqlx::Error> {
        exec.execute_raw(self.sql, self.args).await
    }
}

/// Typed dual-engine SQL query mapping each row via [`DbFromRow`].
pub struct DbQueryAs<'q, T> {
    sql: &'q str,
    args: DbArgs,
    _marker: PhantomData<fn() -> T>,
}

impl<'q, T: DbFromRow> DbQueryAs<'q, T> {
    pub fn new(sql: &'q str) -> Self {
        Self {
            sql,
            args: DbArgs::new(),
            _marker: PhantomData,
        }
    }

    pub fn bind<V: DbBind>(mut self, value: V) -> Self {
        self.args.push(value);
        self
    }

    pub async fn fetch_all<'e>(self, exec: impl DbExec<'e>) -> Result<Vec<T>, sqlx::Error> {
        let rows = exec.fetch_all_raw(self.sql, self.args).await?;
        rows.iter().map(T::from_row).collect()
    }

    pub async fn fetch_optional<'e>(self, exec: impl DbExec<'e>) -> Result<Option<T>, sqlx::Error> {
        let row = exec.fetch_optional_raw(self.sql, self.args).await?;
        row.as_ref().map(T::from_row).transpose()
    }

    pub async fn fetch_one<'e>(self, exec: impl DbExec<'e>) -> Result<T, sqlx::Error> {
        let row = exec.fetch_one_raw(self.sql, self.args).await?;
        T::from_row(&row)
    }
}

/// Single-column dual-engine SQL query decoding column 0 via [`DbDecode`].
pub struct DbQueryScalar<'q, T> {
    sql: &'q str,
    args: DbArgs,
    _marker: PhantomData<fn() -> T>,
}

impl<'q, T: DbDecode> DbQueryScalar<'q, T> {
    pub fn new(sql: &'q str) -> Self {
        Self {
            sql,
            args: DbArgs::new(),
            _marker: PhantomData,
        }
    }

    pub fn bind<V: DbBind>(mut self, value: V) -> Self {
        self.args.push(value);
        self
    }

    pub async fn fetch_all<'e>(self, exec: impl DbExec<'e>) -> Result<Vec<T>, sqlx::Error> {
        let rows = exec.fetch_all_raw(self.sql, self.args).await?;
        rows.iter().map(|r| r.try_get(0usize)).collect()
    }

    pub async fn fetch_optional<'e>(self, exec: impl DbExec<'e>) -> Result<Option<T>, sqlx::Error> {
        let row = exec.fetch_optional_raw(self.sql, self.args).await?;
        row.as_ref().map(|r| r.try_get(0usize)).transpose()
    }

    pub async fn fetch_one<'e>(self, exec: impl DbExec<'e>) -> Result<T, sqlx::Error> {
        let row = exec.fetch_one_raw(self.sql, self.args).await?;
        row.try_get(0usize)
    }
}
