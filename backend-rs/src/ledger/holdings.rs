//! AccountHoldingRepository and AccountService.upsertHolding / pruneHoldings.

use std::cmp::Ordering;

use crate::db::DbHandle;
use chrono::{DateTime, Utc};
use rust_decimal::Decimal;

use super::accounts;
use super::model::{AccountHolding, AccountType, HOLDING_COLUMNS, HOLDING_RETURNING, HoldingDraft};
use crate::db::DbExec;
use crate::error::AppResult;

fn select(filter: &str) -> String {
    format!("SELECT {HOLDING_COLUMNS} FROM account_holding h WHERE {filter}")
}

/// findByAccount_Id, in id order.
pub async fn find_by_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
) -> sqlx::Result<Vec<AccountHolding>> {
    crate::db::query_as(&format!("{} ORDER BY h.id", select("h.account_id = $1")))
        .bind(account_id)
        .fetch_all(db)
        .await
}

/// Sorts holdings by `current_price DESC NULLS FIRST, id ASC` using exact `Decimal` order
/// (matching PostgreSQL's `ORDER BY h.current_price DESC, h.id` even when SQLite stores prices
/// as canonical `TEXT`).
fn sort_by_price_desc(rows: &mut [AccountHolding]) {
    rows.sort_by(|a, b| match (a.current_price, b.current_price) {
        (None, None) => a.id.cmp(&b.id),
        (None, Some(_)) => Ordering::Less,
        (Some(_), None) => Ordering::Greater,
        (Some(pa), Some(pb)) => pb.cmp(&pa).then_with(|| a.id.cmp(&b.id)),
    });
}

/// findByAccountIdOrderByCurrentPriceDesc (nulls first, as PostgreSQL sorts DESC).
pub async fn find_by_account_by_price_desc<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
) -> sqlx::Result<Vec<AccountHolding>> {
    let mut rows: Vec<AccountHolding> =
        crate::db::query_as(&format!("{} ORDER BY h.id", select("h.account_id = $1")))
            .bind(account_id)
            .fetch_all(db)
            .await?;
    sort_by_price_desc(&mut rows);
    Ok(rows)
}

pub async fn find_by_account_and_ticker<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    ticker: &str,
) -> sqlx::Result<Option<AccountHolding>> {
    crate::db::query_as(&format!(
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
    db: impl DbExec<'e>,
    account_ids: &[i64],
) -> sqlx::Result<Vec<AccountHolding>> {
    let qty_pred = if db.is_sqlite() {
        "dec_cmp(h.quantity, '0') > 0"
    } else {
        "h.quantity > 0"
    };
    crate::db::query_as(&format!(
        "SELECT {HOLDING_COLUMNS} FROM account_holding h JOIN account a ON a.id = h.account_id \
         WHERE a.id = ANY($1) AND a.deleted_at IS NULL AND {qty_pred} ORDER BY h.id"
    ))
    .bind(account_ids)
    .fetch_all(db)
    .await
}

/// Whether the account has a holding and the member owns or co-owns it.
pub async fn exists_for_readable_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    member_id: i64,
) -> sqlx::Result<bool> {
    crate::db::query_scalar(
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
    db: impl DbExec<'e>,
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
    let query = crate::db::query_scalar(&sql);
    match account_type {
        Some((t, _)) => query.bind(t).fetch_all(db).await,
        None => query.fetch_all(db).await,
    }
}

pub async fn insert<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    holding: &HoldingDraft,
    now: DateTime<Utc>,
) -> sqlx::Result<AccountHolding> {
    crate::db::query_as(&format!(
        "INSERT INTO account_holding (account_id, ticker, name, quantity, average_buy_in, current_price, \
         quote_currency, provider_value_eur, provider_price_date, provider_pnl_eur, last_synced_at, \
         provider_wealth_tier, created_at, updated_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, \
         $13, $13) RETURNING {HOLDING_RETURNING}"
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
    db: impl DbExec<'e>,
    id: i64,
    holding: &HoldingDraft,
    now: DateTime<Utc>,
) -> sqlx::Result<AccountHolding> {
    crate::db::query_as(&format!(
        "UPDATE account_holding SET ticker = $2, name = $3, quantity = $4, average_buy_in = $5, \
         current_price = $6, quote_currency = $7, provider_value_eur = $8, provider_price_date = $9, \
         provider_pnl_eur = $10, last_synced_at = $11, provider_wealth_tier = $12, updated_at = $13 \
         WHERE id = $1 RETURNING {HOLDING_RETURNING}"
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

pub async fn delete_by_account<'e>(db: impl DbExec<'e>, account_id: i64) -> sqlx::Result<u64> {
    Ok(
        crate::db::query("DELETE FROM account_holding WHERE account_id = $1")
            .bind(account_id)
            .execute(db)
            .await?
            .rows_affected(),
    )
}

pub async fn delete_by_account_except<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    keep_tickers: &[String],
) -> sqlx::Result<u64> {
    Ok(crate::db::query(
        "DELETE FROM account_holding WHERE account_id = $1 AND NOT (ticker = ANY($2))",
    )
    .bind(account_id)
    .bind(keep_tickers)
    .execute(db)
    .await?
    .rows_affected())
}

pub async fn delete_by_accounts_and_tickers<'e>(
    db: impl DbExec<'e>,
    account_ids: &[i64],
    tickers: &[String],
) -> sqlx::Result<u64> {
    Ok(crate::db::query(
        "DELETE FROM account_holding WHERE account_id = ANY($1) AND ticker = ANY($2)",
    )
    .bind(account_ids)
    .bind(tickers)
    .execute(db)
    .await?
    .rows_affected())
}

/// Replaces the account's holdings wholesale (delete, then insert each), the sidecar syncs' write.
pub async fn replace_all(
    conn: &mut DbHandle<'_>,
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
    conn: &mut DbHandle<'_>,
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
    db: impl DbExec<'e>,
    account_id: i64,
    keep_tickers: &[String],
) -> sqlx::Result<u64> {
    if keep_tickers.is_empty() {
        delete_by_account(db, account_id).await
    } else {
        delete_by_account_except(db, account_id, keep_tickers).await
    }
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use super::*;

    fn holding(id: i64, price: Option<&str>) -> AccountHolding {
        let ts = DateTime::from_timestamp(0, 0).expect("epoch");
        AccountHolding {
            id,
            account_id: 1,
            ticker: format!("T{id}"),
            name: None,
            quantity: Decimal::ONE,
            average_buy_in: None,
            current_price: price.map(|s| Decimal::from_str(s).expect("decimal")),
            quote_currency: None,
            provider_value_eur: None,
            provider_price_date: None,
            provider_pnl_eur: None,
            last_synced_at: None,
            provider_wealth_tier: None,
            created_at: ts,
            updated_at: ts,
        }
    }

    #[test]
    fn sort_by_price_desc_matches_pg_nulls_first_and_exact_decimal() {
        let mut rows = vec![
            holding(1, Some("9.99")),
            holding(2, None),
            holding(3, Some("100.5")),
            holding(4, Some("100.50")),
            holding(5, None),
            holding(6, Some("20")),
        ];
        sort_by_price_desc(&mut rows);
        let ids: Vec<i64> = rows.into_iter().map(|h| h.id).collect();
        // NULLs first (ordered by id ASC), then exact Decimal DESC (100.5 == 100.50 -> id 3, 4; then 20; then 9.99).
        assert_eq!(ids, vec![2, 5, 3, 4, 6, 1]);
    }
}
