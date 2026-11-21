//! HoldingComputeService: the positions of a manual investment account, rebuilt from its BUY and
//! SELL rows (net quantity, and a fee-inclusive volume-weighted buy-in, the French PMP).

use std::collections::{BTreeMap, HashMap};

use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::decimal::{HALF_UP, divide};
use super::holdings;
use super::model::{HoldingDraft, Transaction, TransactionType};
use super::transactions;

/// One ticker's position as the trades add up.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NetPosition {
    /// BUY quantities minus SELL quantities; zero or less means the position is closed.
    pub quantity: Decimal,
    /// Σ(qty × price + fees) / Σ(qty) over the BUY rows at scale 8, None without any BUY.
    pub average_buy_in: Option<Decimal>,
    /// The name of the newest trade carrying one.
    pub name: Option<String>,
}

#[derive(Default)]
struct Accumulator {
    quantity: Decimal,
    numerator: Decimal,
    denominator: Decimal,
    traded: bool,
    name: Option<String>,
}

/// Folds trades, oldest first, into one position per ticker. A row without a ticker is skipped;
/// one without a quantity only contributes its name. A missing price or fee counts as zero.
pub fn net_positions(trades: &[Transaction]) -> BTreeMap<String, NetPosition> {
    let mut by_ticker: HashMap<&str, Accumulator> = HashMap::new();
    for tx in trades {
        let Some(ticker) = tx
            .ticker
            .as_deref()
            .filter(|t| !crate::ledger::sync_values::java_is_blank(t))
        else {
            continue;
        };
        let acc = by_ticker.entry(ticker).or_default();
        if tx.name.is_some() {
            acc.name.clone_from(&tx.name);
        }
        let Some(qty) = tx.quantity else {
            continue;
        };
        acc.traded = true;
        if tx.tx_type == Some(TransactionType::Buy) {
            acc.quantity += qty;
            let price = tx.price_per_unit.unwrap_or(Decimal::ZERO);
            let fees = tx.fees.unwrap_or(Decimal::ZERO);
            acc.numerator += qty * price + fees;
            acc.denominator += qty;
        } else {
            acc.quantity -= qty;
        }
    }
    by_ticker
        .into_iter()
        // A ticker seen only on quantity-less rows never entered Java's quantity map.
        .filter(|(_, acc)| acc.traded)
        .map(|(ticker, acc)| {
            let average_buy_in = (acc.denominator > Decimal::ZERO)
                .then(|| divide(acc.numerator, acc.denominator, 8, HALF_UP));
            (
                ticker.to_owned(),
                NetPosition {
                    quantity: acc.quantity,
                    average_buy_in,
                    name: acc.name,
                },
            )
        })
        .collect()
}

async fn delete_holding(conn: &mut PgConnection, id: i64) -> sqlx::Result<()> {
    sqlx::query("DELETE FROM account_holding WHERE id = $1")
        .bind(id)
        .execute(conn)
        .await?;
    Ok(())
}

/// HoldingComputeService.recomputeHoldings: deletes the holdings of closed positions, updates
/// the open ones in place (keeping their other columns, and their name unless a trade carries
/// one) and creates the missing ones. Holdings of tickers no trade mentions are left alone.
pub async fn recompute_holdings(
    conn: &mut PgConnection,
    account_id: i64,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    let trades = transactions::find_by_account_and_tx_types(
        &mut *conn,
        account_id,
        &[TransactionType::Buy, TransactionType::Sell],
    )
    .await?;
    let positions = net_positions(&trades);
    let existing: HashMap<String, _> = holdings::find_by_account(&mut *conn, account_id)
        .await?
        .into_iter()
        .map(|h| (h.ticker.clone(), h))
        .collect();

    let mut to_save = Vec::new();
    for (ticker, position) in positions {
        let held = existing.get(&ticker);
        if position.quantity <= Decimal::ZERO {
            if let Some(holding) = held {
                delete_holding(&mut *conn, holding.id).await?;
            }
            continue;
        }
        let mut draft = held
            .map(HoldingDraft::from)
            .unwrap_or_else(|| HoldingDraft {
                ticker: ticker.clone(),
                ..HoldingDraft::default()
            });
        draft.quantity = position.quantity;
        draft.average_buy_in = position.average_buy_in;
        if position.name.is_some() {
            draft.name = position.name;
        }
        to_save.push((held.map(|h| h.id), draft));
    }
    for (id, draft) in to_save {
        match id {
            Some(id) => {
                holdings::update(&mut *conn, id, &draft, now).await?;
            }
            None => {
                holdings::insert(&mut *conn, account_id, &draft, now).await?;
            }
        }
    }
    Ok(())
}
