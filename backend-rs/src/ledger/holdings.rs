//! AccountHoldingRepository and AccountService.upsertHolding / pruneHoldings.

use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use sqlx::{PgConnection, PgExecutor};

use super::accounts;
use super::model::{AccountHolding, AccountType, HOLDING_COLUMNS, HoldingDraft};
use crate::error::AppResult;

fn select(filter: &str) -> String {
    format!("SELECT {HOLDING_COLUMNS} FROM account_holding h WHERE {filter}")
}

/// findByAccount_Id, in id order.
pub async fn find_by_account<'e>(
    db: impl PgExecutor<'e>,
    account_id: i64,
) -> sqlx::Result<Vec<AccountHolding>> {
    sqlx::query_as(&format!("{} ORDER BY h.id", select("h.account_id = $1")))
        .bind(account_id)
        .fetch_all(db)
        .await
}

/// findByAccountIdOrderByCurrentPriceDesc (nulls first, as PostgreSQL sorts DESC).
pub async fn find_by_account_by_price_desc<'e>(
    db: impl PgExecutor<'e>,
    account_id: i64,
) -> sqlx::Result<Vec<AccountHolding>> {
    sqlx::query_as(&format!(
        "{} ORDER BY h.current_price DESC, h.id",
        select("h.account_id = $1")
    ))
    .bind(account_id)
    .fetch_all(db)
    .await
}

pub async fn find_by_account_and_ticker<'e>(
    db: impl PgExecutor<'e>,
    account_id: i64,
    ticker: &str,
) -> sqlx::Result<Option<AccountHolding>> {
    sqlx::query_as(&format!(
        "{} ORDER BY h.id LIMIT 1",
        select("h.account_id = $1 AND h.ticker = $2")
    ))
    .bind(account_id)
    .bind(ticker)
    .fetch_optional(db)
    .await
}

/// Positions with a positive quantity on live accounts among account_ids.
pub async fn find_positive_by_accounts<'e>(
    db: impl PgExecutor<'e>,
    account_ids: &[i64],
) -> sqlx::Result<Vec<AccountHolding>> {
    sqlx::query_as(&format!(
        "SELECT {HOLDING_COLUMNS} FROM account_holding h JOIN account a ON a.id = h.account_id \
         WHERE a.id = ANY($1) AND a.deleted_at IS NULL AND h.quantity > 0 ORDER BY h.id"
    ))
    .bind(account_ids)
    .fetch_all(db)
    .await
}

/// Whether the account has a holding and the member owns or co-owns it.
pub async fn exists_for_readable_account<'e>(
    db: impl PgExecutor<'e>,
    account_id: i64,
    member_id: i64,
) -> sqlx::Result<bool> {
    sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM account_holding h JOIN account a ON a.id = h.account_id \
         WHERE h.account_id = $1 AND a.deleted_at IS NULL AND (a.member_id = $2 OR EXISTS (SELECT 1 FROM account_ownership o \
         WHERE o.account_id = $1 AND o.member_id = $2)))",
    )
    .bind(account_id)
    .bind(member_id)
    .fetch_one(db)
    .await
}

/// Distinct tickers held on live accounts: all, of one type, or outside one type.
pub async fn distinct_tickers<'e>(
    db: impl PgExecutor<'e>,
    account_type: Option<(AccountType, bool)>,
) -> sqlx::Result<Vec<String>> {
    let filter = match account_type {
        None => String::new(),
        Some((_, false)) => " AND a.type = $1::account_type".to_owned(),
        Some((_, true)) => " AND a.type <> $1::account_type".to_owned(),
    };
    let sql = format!(
        "SELECT DISTINCT h.ticker FROM account_holding h JOIN account a ON a.id = h.account_id \
         WHERE h.ticker IS NOT NULL AND a.deleted_at IS NULL{filter} ORDER BY h.ticker"
    );
    let query = sqlx::query_scalar(&sql);
    match account_type {
        Some((t, _)) => query.bind(t).fetch_all(db).await,
        None => query.fetch_all(db).await,
    }
}

pub async fn insert<'e>(
    db: impl PgExecutor<'e>,
    account_id: i64,
    holding: &HoldingDraft,
    now: DateTime<Utc>,
) -> sqlx::Result<AccountHolding> {
    sqlx::query_as(&format!(
        "WITH h AS (INSERT INTO account_holding (account_id, ticker, name, quantity, average_buy_in, current_price, \
         quote_currency, provider_value_eur, provider_price_date, provider_pnl_eur, last_synced_at, \
         provider_wealth_tier, created_at, updated_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, \
         $13, $13) RETURNING *) SELECT {HOLDING_COLUMNS} FROM h"
    ))
    .bind(account_id)
    .bind(&holding.ticker)
    .bind(&holding.name)
    .bind(holding.quantity)
    .bind(holding.average_buy_in)
    .bind(holding.current_price)
    .bind(&holding.quote_currency)
    .bind(holding.provider_value_eur)
    .bind(holding.provider_price_date)
    .bind(holding.provider_pnl_eur)
    .bind(holding.last_synced_at)
    .bind(holding.provider_wealth_tier)
    .bind(now)
    .fetch_one(db)
    .await
}

/// Writes every column of the draft over the holding id.
pub async fn update<'e>(
    db: impl PgExecutor<'e>,
    id: i64,
    holding: &HoldingDraft,
    now: DateTime<Utc>,
) -> sqlx::Result<AccountHolding> {
    sqlx::query_as(&format!(
        "WITH h AS (UPDATE account_holding SET ticker = $2, name = $3, quantity = $4, average_buy_in = $5, \
         current_price = $6, quote_currency = $7, provider_value_eur = $8, provider_price_date = $9, \
         provider_pnl_eur = $10, last_synced_at = $11, provider_wealth_tier = $12, updated_at = $13 \
         WHERE id = $1 RETURNING *) SELECT {HOLDING_COLUMNS} FROM h"
    ))
    .bind(id)
    .bind(&holding.ticker)
    .bind(&holding.name)
    .bind(holding.quantity)
    .bind(holding.average_buy_in)
    .bind(holding.current_price)
    .bind(&holding.quote_currency)
    .bind(holding.provider_value_eur)
    .bind(holding.provider_price_date)
    .bind(holding.provider_pnl_eur)
    .bind(holding.last_synced_at)
    .bind(holding.provider_wealth_tier)
    .bind(now)
    .fetch_one(db)
    .await
}

pub async fn delete_by_account<'e>(db: impl PgExecutor<'e>, account_id: i64) -> sqlx::Result<u64> {
    Ok(
        sqlx::query("DELETE FROM account_holding WHERE account_id = $1")
            .bind(account_id)
            .execute(db)
            .await?
            .rows_affected(),
    )
}

pub async fn delete_by_account_except<'e>(
    db: impl PgExecutor<'e>,
    account_id: i64,
    keep_tickers: &[String],
) -> sqlx::Result<u64> {
    Ok(
        sqlx::query("DELETE FROM account_holding WHERE account_id = $1 AND NOT (ticker = ANY($2))")
            .bind(account_id)
            .bind(keep_tickers)
            .execute(db)
            .await?
            .rows_affected(),
    )
}

pub async fn delete_by_accounts_and_tickers<'e>(
    db: impl PgExecutor<'e>,
    account_ids: &[i64],
    tickers: &[String],
) -> sqlx::Result<u64> {
    Ok(
        sqlx::query("DELETE FROM account_holding WHERE account_id = ANY($1) AND ticker = ANY($2)")
            .bind(account_ids)
            .bind(tickers)
            .execute(db)
            .await?
            .rows_affected(),
    )
}

/// Replaces the account's holdings wholesale (delete, then insert each), the sidecar syncs' write.
pub async fn replace_all(
    conn: &mut PgConnection,
    account_id: i64,
    holdings: &[HoldingDraft],
    now: DateTime<Utc>,
) -> sqlx::Result<Vec<AccountHolding>> {
    delete_by_account(&mut *conn, account_id).await?;
    let mut saved = Vec::with_capacity(holdings.len());
    for holding in holdings {
        saved.push(insert(&mut *conn, account_id, holding, now).await?);
    }
    Ok(saved)
}

/// AccountService.upsertHolding: updates quantity and price of the ticker's holding on the
/// member's account and keeps its cost basis, or creates it with the price as its baseline cost
/// (no P&L at first sync). 404 when the member does not own the account.
#[allow(clippy::too_many_arguments)]
pub async fn upsert_holding(
    conn: &mut PgConnection,
    account_id: i64,
    member_id: i64,
    ticker: &str,
    name: Option<&str>,
    quantity: Decimal,
    current_price_eur: Option<Decimal>,
    now: DateTime<Utc>,
) -> AppResult<AccountHolding> {
    accounts::get_or_throw(&mut *conn, account_id, member_id).await?;
    if let Some(existing) = find_by_account_and_ticker(&mut *conn, account_id, ticker).await? {
        let mut draft = HoldingDraft::from(&existing);
        draft.quantity = quantity;
        draft.current_price = current_price_eur;
        draft.last_synced_at = Some(now);
        return Ok(update(&mut *conn, existing.id, &draft, now).await?);
    }
    let draft = HoldingDraft {
        ticker: ticker.to_owned(),
        name: name.map(str::to_owned),
        quantity,
        average_buy_in: current_price_eur,
        current_price: current_price_eur,
        last_synced_at: Some(now),
        ..HoldingDraft::default()
    };
    Ok(insert(&mut *conn, account_id, &draft, now).await?)
}

/// AccountService.pruneHoldings: drops the holdings whose ticker the latest sync no longer
/// reports as held; an empty keep set clears them all.
pub async fn prune_holdings<'e>(
    db: impl PgExecutor<'e>,
    account_id: i64,
    keep_tickers: &[String],
) -> sqlx::Result<u64> {
    if keep_tickers.is_empty() {
        delete_by_account(db, account_id).await
    } else {
        delete_by_account_except(db, account_id, keep_tickers).await
    }
}
