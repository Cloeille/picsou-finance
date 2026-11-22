//! BalanceSnapshotRepository and the snapshot writes that need no valuation. The writes that
//! derive the invested amount or convert a native balance are in valuation.rs.

use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::model::{BalanceSnapshot, SNAPSHOT_COLUMNS, SNAPSHOT_RETURNING, SnapshotSource};
use crate::db::{DbExec, DbFromRow};

fn select(filter: &str) -> String {
    format!("SELECT {SNAPSHOT_COLUMNS} FROM balance_snapshot s WHERE {filter}")
}

pub async fn find_by_account_and_date<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    date: NaiveDate,
) -> sqlx::Result<Option<BalanceSnapshot>> {
    crate::db::query_as(&select("s.account_id = $1 AND s.date = $2"))
        .bind(account_id)
        .bind(date)
        .fetch_optional(db)
        .await
}

pub async fn find_by_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
) -> sqlx::Result<Vec<BalanceSnapshot>> {
    crate::db::query_as(&format!("{} ORDER BY s.date", select("s.account_id = $1")))
        .bind(account_id)
        .fetch_all(db)
        .await
}

/// Rows of the account dated within [from, to], oldest first.
pub async fn find_by_account_between<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    from: NaiveDate,
    to: NaiveDate,
) -> sqlx::Result<Vec<BalanceSnapshot>> {
    crate::db::query_as(&format!(
        "{} ORDER BY s.date",
        select("s.account_id = $1 AND s.date BETWEEN $2 AND $3")
    ))
    .bind(account_id)
    .bind(from)
    .bind(to)
    .fetch_all(db)
    .await
}

/// Rows of the account dated from on, oldest first.
pub async fn find_recent_by_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    from: NaiveDate,
) -> sqlx::Result<Vec<BalanceSnapshot>> {
    crate::db::query_as(&format!(
        "{} ORDER BY s.date",
        select("s.account_id = $1 AND s.date >= $2")
    ))
    .bind(account_id)
    .bind(from)
    .fetch_all(db)
    .await
}

pub async fn find_latest_by_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
) -> sqlx::Result<Option<BalanceSnapshot>> {
    crate::db::query_as(&format!(
        "{} ORDER BY s.date DESC LIMIT 1",
        select("s.account_id = $1")
    ))
    .bind(account_id)
    .fetch_optional(db)
    .await
}

/// The newest row dated on or before date, optionally of one source only.
pub async fn find_latest_on_or_before<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    date: NaiveDate,
    source: Option<SnapshotSource>,
) -> sqlx::Result<Option<BalanceSnapshot>> {
    crate::db::query_as(&format!(
        "{} ORDER BY s.date DESC LIMIT 1",
        select("s.account_id = $1 AND s.date <= $2 AND ($3::text IS NULL OR s.source = $3)")
    ))
    .bind(account_id)
    .bind(date)
    .bind(source)
    .fetch_optional(db)
    .await
}

/// One account's day in the forward-fill series (findForwardFillData*).
#[derive(Debug, Clone, PartialEq, DbFromRow)]
pub struct SnapshotPoint {
    pub account_id: i64,
    pub date: NaiveDate,
    pub balance: Decimal,
    pub invested_amount: Decimal,
}

/// Every row dated from on, of the given accounts or of all, ordered by account then date.
pub async fn forward_fill_data<'e>(
    db: impl DbExec<'e>,
    from: NaiveDate,
    account_ids: Option<&[i64]>,
) -> sqlx::Result<Vec<SnapshotPoint>> {
    crate::db::query_as(
        "SELECT account_id, date, balance, invested_amount FROM balance_snapshot \
         WHERE date >= $1 AND ($2::bigint[] IS NULL OR account_id = ANY($2)) ORDER BY account_id, date",
    )
    .bind(from)
    .bind(account_ids)
    .fetch_all(db)
    .await
}

/// Writes balance and invested amount over an existing row, leaving its source alone.
pub async fn update_amounts<'e>(
    db: impl DbExec<'e>,
    id: i64,
    balance: Decimal,
    invested_amount: Decimal,
) -> sqlx::Result<BalanceSnapshot> {
    crate::db::query_as(&format!(
        "UPDATE balance_snapshot SET balance = $2, invested_amount = $3 WHERE id = $1 \
         RETURNING {SNAPSHOT_RETURNING}"
    ))
    .bind(id)
    .bind(balance)
    .bind(invested_amount)
    .fetch_one(db)
    .await
}

/// The columns of a new row; the entity defaults are DERIVED with no native balance.
#[derive(Debug, Clone, PartialEq)]
pub struct NewSnapshot {
    pub account_id: i64,
    pub date: NaiveDate,
    pub balance: Decimal,
    pub invested_amount: Decimal,
    pub source: SnapshotSource,
    pub native_balance: Option<Decimal>,
    pub statement_entered_at: Option<DateTime<Utc>>,
}

impl NewSnapshot {
    pub fn derived(account_id: i64, date: NaiveDate, balance: Decimal, invested: Decimal) -> Self {
        Self {
            account_id,
            date,
            balance,
            invested_amount: invested,
            source: SnapshotSource::Derived,
            native_balance: None,
            statement_entered_at: None,
        }
    }
}

pub async fn insert<'e>(
    db: impl DbExec<'e>,
    snapshot: &NewSnapshot,
    now: DateTime<Utc>,
) -> sqlx::Result<BalanceSnapshot> {
    crate::db::query_as(&format!(
        "INSERT INTO balance_snapshot (account_id, date, balance, invested_amount, created_at, source, \
         native_balance, statement_entered_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8) \
         RETURNING {SNAPSHOT_RETURNING}"
    ))
    .bind(snapshot.account_id)
    .bind(snapshot.date)
    .bind(snapshot.balance)
    .bind(snapshot.invested_amount)
    .bind(now)
    .bind(snapshot.source)
    .bind(snapshot.native_balance)
    .bind(snapshot.statement_entered_at)
    .fetch_one(db)
    .await
}

/// Writes the native figure of a MANUAL row and the instant it was typed.
pub async fn set_native_balance<'e>(
    db: impl DbExec<'e>,
    id: i64,
    native_balance: Decimal,
    entered_at: DateTime<Utc>,
) -> sqlx::Result<BalanceSnapshot> {
    crate::db::query_as(&format!(
        "UPDATE balance_snapshot SET native_balance = $2, statement_entered_at = $3 WHERE id = $1 \
         RETURNING {SNAPSHOT_RETURNING}"
    ))
    .bind(id)
    .bind(native_balance)
    .bind(entered_at)
    .fetch_one(db)
    .await
}

pub async fn delete<'e>(db: impl DbExec<'e>, id: i64) -> sqlx::Result<()> {
    crate::db::query("DELETE FROM balance_snapshot WHERE id = $1")
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}

/// AccountService.upsertSnapshot(account, balance, invested, date): updates the day's row in
/// place, whatever its source, or creates a DERIVED one.
pub async fn upsert(
    conn: &mut PgConnection,
    account_id: i64,
    balance: Decimal,
    invested_amount: Decimal,
    date: NaiveDate,
    now: DateTime<Utc>,
) -> sqlx::Result<BalanceSnapshot> {
    match find_by_account_and_date(&mut *conn, account_id, date).await? {
        Some(existing) => update_amounts(&mut *conn, existing.id, balance, invested_amount).await,
        None => {
            insert(
                &mut *conn,
                &NewSnapshot::derived(account_id, date, balance, invested_amount),
                now,
            )
            .await
        }
    }
}
