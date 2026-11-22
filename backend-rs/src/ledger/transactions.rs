//! The ingestion, deduplication and valuation subset of TransactionRepository. Budget and
//! cashflow aggregates belong to track T3.

use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;

use super::model::{
    NewTransaction, TRANSACTION_COLUMNS, TRANSACTION_RETURNING, Transaction, TransactionType,
};
use crate::db::{DbExec, DbFromRow};

fn select(filter: &str) -> String {
    format!("SELECT {TRANSACTION_COLUMNS} FROM \"transaction\" t WHERE {filter}")
}

pub async fn find_by_id_and_account<'e>(
    db: impl DbExec<'e>,
    id: i64,
    account_id: i64,
) -> sqlx::Result<Option<Transaction>> {
    crate::db::query_as(&select("t.id = $1 AND t.account_id = $2"))
        .bind(id)
        .bind(account_id)
        .fetch_optional(db)
        .await
}

/// A transaction on any account the member owns.
pub async fn find_by_id_and_member<'e>(
    db: impl DbExec<'e>,
    id: i64,
    member_id: i64,
) -> sqlx::Result<Option<Transaction>> {
    crate::db::query_as(&select(
        "t.id = $1 AND EXISTS (SELECT 1 FROM account a WHERE a.id = t.account_id AND a.member_id = $2 AND a.deleted_at IS NULL)",
    ))
    .bind(id)
    .bind(member_id)
    .fetch_optional(db)
    .await
}

pub async fn find_by_ids_and_member<'e>(
    db: impl DbExec<'e>,
    ids: &[i64],
    member_id: i64,
) -> sqlx::Result<Vec<Transaction>> {
    crate::db::query_as(&format!(
        "{} ORDER BY t.id",
        select(
            "t.id = ANY($1) AND EXISTS (SELECT 1 FROM account a WHERE a.id = t.account_id AND a.member_id = $2 AND a.deleted_at IS NULL)"
        )
    ))
    .bind(ids)
    .bind(member_id)
    .fetch_all(db)
    .await
}

/// The account's ledger, newest first (ties by id, newest first).
pub async fn find_by_account_newest_first<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
) -> sqlx::Result<Vec<Transaction>> {
    crate::db::query_as(&format!(
        "{} ORDER BY t.date DESC, t.id DESC",
        select("t.account_id = $1")
    ))
    .bind(account_id)
    .fetch_all(db)
    .await
}

/// The synced (non-manual) rows of the account, optionally dated from a day on.
pub async fn find_synced_by_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    from: Option<NaiveDate>,
) -> sqlx::Result<Vec<Transaction>> {
    crate::db::query_as(&format!(
        "{} ORDER BY t.id",
        select("t.account_id = $1 AND t.is_manual = false AND ($2::date IS NULL OR t.date >= $2)")
    ))
    .bind(account_id)
    .bind(from)
    .fetch_all(db)
    .await
}

/// Rows of the accounts dated within [from, to], oldest first.
pub async fn find_by_accounts_between<'e>(
    db: impl DbExec<'e>,
    account_ids: &[i64],
    from: NaiveDate,
    to: NaiveDate,
) -> sqlx::Result<Vec<Transaction>> {
    crate::db::query_as(&format!(
        "{} ORDER BY t.date, t.id",
        select("t.account_id = ANY($1) AND t.date BETWEEN $2 AND $3")
    ))
    .bind(account_ids)
    .bind(from)
    .bind(to)
    .fetch_all(db)
    .await
}

/// The account's trade rows of the given types, oldest first.
pub async fn find_by_account_and_tx_types<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    types: &[TransactionType],
) -> sqlx::Result<Vec<Transaction>> {
    let types: Vec<&str> = types.iter().map(|t| t.as_str()).collect();
    crate::db::query_as(&format!(
        "{} ORDER BY t.date, t.id",
        select("t.account_id = $1 AND t.tx_type = ANY($2)")
    ))
    .bind(account_id)
    .bind(types.as_slice())
    .fetch_all(db)
    .await
}

/// The date of the newest synced row of the account.
pub async fn latest_synced_date<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
) -> sqlx::Result<Option<NaiveDate>> {
    crate::db::query_scalar(
        "SELECT MAX(date) FROM \"transaction\" WHERE account_id = $1 AND is_manual = false",
    )
    .bind(account_id)
    .fetch_one(db)
    .await
}

pub async fn exists_by_account_and_external_id<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    external_id: &str,
) -> sqlx::Result<bool> {
    crate::db::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM \"transaction\" WHERE account_id = $1 AND external_id = $2)",
    )
    .bind(account_id)
    .bind(external_id)
    .fetch_one(db)
    .await
}

/// The sum of every row of the account (zero without any).
pub async fn sum_amount<'e>(db: impl DbExec<'e>, account_id: i64) -> sqlx::Result<Decimal> {
    let sum = if db.is_sqlite() {
        "COALESCE(dec_sum(amount), '0')"
    } else {
        "COALESCE(SUM(amount), 0)"
    };
    crate::db::query_scalar(&format!(
        "SELECT {sum} FROM \"transaction\" WHERE account_id = $1"
    ))
    .bind(account_id)
    .fetch_one(db)
    .await
}

/// The sum of the rows booked after a statement typed on date at entered_at: later days, and
/// the same day's rows created after it.
pub async fn sum_amount_after_statement<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    date: NaiveDate,
    entered_at: DateTime<Utc>,
) -> sqlx::Result<Decimal> {
    let sum = if db.is_sqlite() {
        "COALESCE(dec_sum(amount), '0')"
    } else {
        "COALESCE(SUM(amount), 0)"
    };
    crate::db::query_scalar(&format!(
        "SELECT {sum} FROM \"transaction\" WHERE account_id = $1 \
         AND (date > $2 OR (date = $2 AND created_at > $3))"
    ))
    .bind(account_id)
    .bind(date)
    .bind(entered_at)
    .fetch_one(db)
    .await
}

/// The sum of the rows dated after date.
pub async fn sum_amount_after<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    date: NaiveDate,
) -> sqlx::Result<Decimal> {
    let sum = if db.is_sqlite() {
        "COALESCE(dec_sum(amount), '0')"
    } else {
        "COALESCE(SUM(amount), 0)"
    };
    crate::db::query_scalar(&format!(
        "SELECT {sum} FROM \"transaction\" WHERE account_id = $1 AND date > $2"
    ))
    .bind(account_id)
    .bind(date)
    .fetch_one(db)
    .await
}

/// A stored external id of one of the member's rows (findStoredExternalIds*).
#[derive(Debug, Clone, PartialEq, Eq, DbFromRow)]
pub struct StoredExternalId {
    pub id: i64,
    pub external_id: String,
    pub account_id: i64,
}

/// The member's rows carrying one of the external ids.
pub async fn stored_external_ids<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    external_ids: &[String],
) -> sqlx::Result<Vec<StoredExternalId>> {
    crate::db::query_as(
        "SELECT t.id, t.external_id, t.account_id FROM \"transaction\" t JOIN account a ON a.id = t.account_id \
         WHERE a.member_id = $1 AND a.deleted_at IS NULL AND t.external_id = ANY($2) ORDER BY t.id",
    )
    .bind(member_id)
    .bind(external_ids)
    .fetch_all(db)
    .await
}

pub async fn insert<'e>(
    db: impl DbExec<'e>,
    tx: &NewTransaction,
    now: DateTime<Utc>,
) -> sqlx::Result<Transaction> {
    crate::db::query_as(&format!(
        "INSERT INTO \"transaction\" (account_id, date, description, amount, type, category, category_id, \
         category_manual, counterparty, merchant_label, merchant_brand_id, external_id, native_currency, created_at, \
         is_manual, budget_neutral, external_transaction_id, tx_type, ticker, name, quantity, price_per_unit, fees) \
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, \
         $23) RETURNING {TRANSACTION_RETURNING}"
    ))
    .bind(tx.account_id)
    .bind(tx.date)
    .bind(&tx.description)
    .bind(tx.amount)
    .bind(&tx.kind)
    .bind(&tx.category)
    .bind(tx.category_id)
    .bind(tx.category_manual)
    .bind(&tx.counterparty)
    .bind(&tx.merchant_label)
    .bind(tx.merchant_brand_id)
    .bind(&tx.external_id)
    .bind(&tx.native_currency)
    .bind(now)
    .bind(tx.is_manual)
    .bind(tx.budget_neutral)
    .bind(&tx.external_transaction_id)
    .bind(tx.tx_type)
    .bind(&tx.ticker)
    .bind(&tx.name)
    .bind(tx.quantity)
    .bind(tx.price_per_unit)
    .bind(tx.fees)
    .fetch_one(db)
    .await
}

/// Writes every mutable column of a loaded row back (JPA save).
pub async fn update<'e>(db: impl DbExec<'e>, tx: &Transaction) -> sqlx::Result<Transaction> {
    crate::db::query_as(&format!(
        "UPDATE \"transaction\" SET account_id = $2, date = $3, description = $4, amount = $5, type = $6, \
         category = $7, category_id = $8, category_manual = $9, counterparty = $10, merchant_label = $11, \
         merchant_brand_id = $12, ai_suggested_category_id = $13, ai_confidence = $14, external_id = $15, \
         recurring_series_id = $16, native_currency = $17, is_manual = $18, budget_neutral = $19, \
         external_transaction_id = $20, tx_type = $21, ticker = $22, name = $23, quantity = $24, \
         price_per_unit = $25, fees = $26 WHERE id = $1 RETURNING {TRANSACTION_RETURNING}"
    ))
    .bind(tx.id)
    .bind(tx.account_id)
    .bind(tx.date)
    .bind(&tx.description)
    .bind(tx.amount)
    .bind(&tx.kind)
    .bind(&tx.category)
    .bind(tx.category_id)
    .bind(tx.category_manual)
    .bind(&tx.counterparty)
    .bind(&tx.merchant_label)
    .bind(tx.merchant_brand_id)
    .bind(tx.ai_suggested_category_id)
    .bind(tx.ai_confidence)
    .bind(&tx.external_id)
    .bind(tx.recurring_series_id)
    .bind(&tx.native_currency)
    .bind(tx.is_manual)
    .bind(tx.budget_neutral)
    .bind(&tx.external_transaction_id)
    .bind(tx.tx_type)
    .bind(&tx.ticker)
    .bind(&tx.name)
    .bind(tx.quantity)
    .bind(tx.price_per_unit)
    .bind(tx.fees)
    .fetch_one(db)
    .await
}

pub async fn delete<'e>(db: impl DbExec<'e>, id: i64) -> sqlx::Result<()> {
    crate::db::query("DELETE FROM \"transaction\" WHERE id = $1")
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}

/// Deletes the account's synced rows (all, or dated from a day on), keeping manual ones.
pub async fn delete_synced_by_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    from: Option<NaiveDate>,
) -> sqlx::Result<u64> {
    Ok(crate::db::query(
        "DELETE FROM \"transaction\" WHERE account_id = $1 AND is_manual = false AND ($2::date IS NULL OR date >= $2)",
    )
    .bind(account_id)
    .bind(from)
    .execute(db)
    .await?
    .rows_affected())
}
