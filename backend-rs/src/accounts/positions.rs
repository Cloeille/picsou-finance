//! CryptoExchangeSyncService.getPositions: a crypto exchange account's per-product breakdown
//! (spot, staking, lending...) from crypto_exchange_position, valued at crypto prices. Empty
//! for every other account and for spot-only exchanges, which keep the flat holdings table.

use std::collections::{BTreeSet, HashMap};

use async_trait::async_trait;
use axum::extract::State;
use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Serialize;
use sqlx::FromRow;

use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::json::Path;
use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::{accounts, holdings};
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes.get("/api/accounts/{id}/positions", positions);
}

/// CryptoLogoService.getLogoUrls: a coin's image URL keyed by upper-cased ticker, absent when
/// the provider has none. Track T4 implements it over CoinGecko and its cache.
#[async_trait]
pub trait CryptoLogoRead: Send + Sync {
    async fn logo_urls(&self, tickers: &BTreeSet<String>) -> HashMap<String, String>;
}

/// What CryptoLogoService answers with no provider reachable: no mark for any coin.
pub struct NoCryptoLogos;

#[async_trait]
impl CryptoLogoRead for NoCryptoLogos {
    async fn logo_urls(&self, _: &BTreeSet<String>) -> HashMap<String, String> {
        HashMap::new()
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExchangePositionResponse {
    pub product: String,
    pub ticker: String,
    pub logo_url: Option<String>,
    #[serde(with = "crate::accounts::jdecimal")]
    pub quantity: Decimal,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub principal: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub interest: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub average_buy_in: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub current_price_eur: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub current_value_eur: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub cost_basis_eur: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub pnl_eur: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub pnl_percent: Option<Decimal>,
    pub price_as_of: Option<NaiveDate>,
    pub price_stale: bool,
}

#[derive(FromRow)]
struct PositionRow {
    product: String,
    ticker: String,
    quantity: Decimal,
    principal: Option<Decimal>,
    interest: Option<Decimal>,
}

pub async fn positions(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<Vec<ExchangePositionResponse>>> {
    accounts::get_or_throw(&state.db, id, auth.member_id).await?;
    // Cost is tracked per asset on the holding, not per product: each line reuses the asset's
    // unit cost, so the lines still add up to the holding's cost and P&L.
    let mut unit_cost: HashMap<String, Decimal> = HashMap::new();
    for holding in holdings::find_by_account(&state.db, id).await? {
        if let Some(average) = holding.average_buy_in {
            unit_cost.entry(holding.ticker).or_insert(average);
        }
    }
    let rows: Vec<PositionRow> = sqlx::query_as(
        "SELECT product, ticker, quantity, principal, interest FROM crypto_exchange_position \
         WHERE account_id = $1 ORDER BY product, ticker",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await?;
    if rows.iter().all(|p| p.product == "SPOT") {
        return Ok(axum::Json(Vec::new()));
    }

    let tickers: BTreeSet<String> = rows.iter().map(|p| p.ticker.clone()).collect();
    let quotes = state
        .ledger
        .prices
        .quotes(&tickers, true, state.clock.today())
        .await;
    let logos = state.accounts.crypto_logos.logo_urls(&tickers).await;

    let lines = rows
        .into_iter()
        .map(|position| {
            let key = position.ticker.to_uppercase();
            let quote = quotes.get(&key);
            let price = quote.map(|q| q.price);
            let average_buy_in = unit_cost.get(&position.ticker).copied();
            let value = price.map(|p| p * position.quantity);
            let cost_basis = average_buy_in.map(|a| a * position.quantity);
            let pnl = value.zip(cost_basis).map(|(v, c)| v - c);
            // abs(): the percentage keeps the sign of the P&L itself.
            let pnl_percent = pnl
                .zip(cost_basis)
                .filter(|(_, c)| !c.is_zero())
                .map(|(p, c)| divide(p, c.abs(), 4, HALF_UP) * Decimal::ONE_HUNDRED);
            ExchangePositionResponse {
                logo_url: logos.get(&key).cloned(),
                product: position.product,
                ticker: position.ticker,
                quantity: position.quantity,
                principal: position.principal,
                interest: position.interest,
                average_buy_in,
                current_price_eur: price,
                current_value_eur: value,
                cost_basis_eur: cost_basis,
                pnl_eur: pnl,
                pnl_percent,
                price_as_of: quote.map(|q| q.as_of),
                price_stale: quote.is_some_and(|q| !q.live),
            }
        })
        .collect();
    Ok(axum::Json(lines))
}
