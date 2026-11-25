//! RealizedPnlService: realized profit and loss from the ordered BUY/SELL stream, on a
//! moving-average cost basis, in the account's own currency. Computed on every read.

use std::collections::{HashMap, HashSet};

use axum::extract::State;
use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Serialize;

use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::json::Path;
use crate::ledger::decimal::{HALF_UP, divide, set_scale};
use crate::ledger::model::{Transaction, TransactionType};
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::{accounts, transactions};
use crate::routes::Routes;

const SCALE: u32 = 8;

pub fn register(routes: &mut Routes) {
    routes.get("/api/accounts/{id}/realized-pnl", realized_pnl);
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RealizedPnlResponse {
    pub currency: String,
    #[serde(with = "crate::accounts::jdecimal")]
    pub realized_total: Decimal,
    pub by_ticker: Vec<TickerRealized>,
    pub lots: Vec<ClosedLot>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TickerRealized {
    pub ticker: String,
    pub name: Option<String>,
    #[serde(with = "crate::accounts::jdecimal")]
    pub realized: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub quantity_sold: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub proceeds: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub cost_basis: Decimal,
    pub warning: bool,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClosedLot {
    pub ticker: String,
    pub name: Option<String>,
    pub date: NaiveDate,
    #[serde(with = "crate::accounts::jdecimal")]
    pub quantity: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub avg_cost: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub proceeds: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub realized: Decimal,
}

/// Java's v.signum() < 0 ? ZERO : v, which keeps a zero's scale.
fn non_negative(v: Decimal) -> Decimal {
    if v < Decimal::ZERO { Decimal::ZERO } else { v }
}

fn scale(v: Decimal) -> Decimal {
    set_scale(v, SCALE, HALF_UP)
}

#[derive(Default)]
struct Realized {
    realized: Decimal,
    quantity_sold: Decimal,
    proceeds: Decimal,
    cost: Decimal,
}

/// The moving-average pass. Each BUY adds qty x price + fees to the ticker's pool; each SELL
/// realizes (qty x price - fees) - avgCost x qty. An over-sell costs only what was held and
/// flags the ticker.
pub fn compute(currency: String, stream: &[Transaction]) -> RealizedPnlResponse {
    let mut held: HashMap<&str, Decimal> = HashMap::new();
    let mut pool: HashMap<&str, Decimal> = HashMap::new();
    let mut latest_name: HashMap<&str, String> = HashMap::new();
    // First-sell order, for a stable response.
    let mut order: Vec<&str> = Vec::new();
    let mut by_ticker: HashMap<&str, Realized> = HashMap::new();
    let mut warnings: HashSet<&str> = HashSet::new();
    let mut lots = Vec::new();

    for tx in stream {
        let Some(ticker) = tx.ticker.as_deref().filter(|t| !java_is_blank(t)) else {
            continue;
        };
        let Some(qty) = tx.quantity.filter(|q| *q > Decimal::ZERO) else {
            continue;
        };
        if let Some(name) = &tx.name {
            latest_name.insert(ticker, name.clone());
        }
        let price = tx.price_per_unit.unwrap_or_default();
        let fees = tx.fees.unwrap_or_default();

        if tx.tx_type == Some(TransactionType::Buy) {
            *pool.entry(ticker).or_default() += qty * price + fees;
            *held.entry(ticker).or_default() += qty;
            continue;
        }

        let held_qty = held.get(ticker).copied().unwrap_or_default();
        let pool_value = pool.get(ticker).copied().unwrap_or_default();
        let avg_cost = if held_qty > Decimal::ZERO {
            divide(pool_value, held_qty, SCALE, HALF_UP)
        } else {
            Decimal::ZERO
        };
        let over_sell = qty > held_qty;
        let costed = match (over_sell, held_qty < Decimal::ZERO) {
            (false, _) => qty,
            (true, false) => held_qty,
            (true, true) => Decimal::ZERO,
        };
        let cost_out = scale(avg_cost * costed);
        let proceeds = scale(qty * price - fees);
        let realized = scale(proceeds - cost_out);
        if over_sell {
            warnings.insert(ticker);
        }
        held.insert(ticker, non_negative(held_qty - qty));
        pool.insert(ticker, non_negative(pool_value - cost_out));

        lots.push(ClosedLot {
            ticker: ticker.to_owned(),
            name: latest_name.get(ticker).cloned(),
            date: tx.date,
            quantity: qty,
            avg_cost,
            proceeds,
            realized,
        });
        let entry = by_ticker.entry(ticker).or_insert_with(|| {
            order.push(ticker);
            Realized::default()
        });
        entry.realized += realized;
        entry.quantity_sold += qty;
        entry.proceeds += proceeds;
        entry.cost += cost_out;
    }

    let mut total = Decimal::ZERO;
    let by_ticker: Vec<TickerRealized> = order
        .into_iter()
        .map(|ticker| {
            let r = &by_ticker[ticker];
            total += r.realized;
            TickerRealized {
                ticker: ticker.to_owned(),
                name: latest_name.get(ticker).cloned(),
                realized: r.realized,
                quantity_sold: r.quantity_sold,
                proceeds: r.proceeds,
                cost_basis: r.cost,
                warning: warnings.contains(ticker),
            }
        })
        .collect();
    RealizedPnlResponse {
        currency,
        realized_total: scale(total),
        by_ticker,
        lots,
    }
}

async fn realized_pnl(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<RealizedPnlResponse>> {
    let account = accounts::get_or_throw(&state.db, id, auth.member_id).await?;
    let stream = transactions::find_by_account_and_tx_types(
        &state.db,
        id,
        &[TransactionType::Buy, TransactionType::Sell],
    )
    .await?;
    Ok(axum::Json(compute(account.currency, &stream)))
}
