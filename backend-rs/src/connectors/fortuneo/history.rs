//! BalanceHistoryReconstructor: rebuilds an account's past snapshots from the ledger a sync just
//! imported, since a sync writes one point dated today. It never overwrites an existing snapshot:
//! a same-date row is better evidence than a reconstruction, so it only fills holes.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};

use chrono::{DateTime, Days, NaiveDate, Utc};
use rust_decimal::Decimal;
use sqlx::PgConnection;

use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::model::{Account, AccountType, Transaction, TransactionType};
use crate::ledger::snapshots::{self, NewSnapshot};
use crate::ledger::{prices, transactions};

/// Inside the caller's sync transaction: a failed reconstruction rolls back with the import.
/// Returns how many snapshots were created.
pub async fn reconstruct(
    conn: &mut PgConnection,
    account: &Account,
    balance_today: Decimal,
    today: NaiveDate,
    now: DateTime<Utc>,
) -> sqlx::Result<usize> {
    let ledger = transactions::find_synced_by_account(&mut *conn, account.id, None).await?;
    if ledger.is_empty() {
        return Ok(0);
    }
    let created = if matches!(
        account.account_type,
        AccountType::Pea | AccountType::CompteTitres
    ) {
        securities(conn, account, &ledger, today).await?
    } else {
        cash(conn, account, &ledger, balance_today, today).await?
    };
    persist(conn, account, &created, now).await
}

/// Walks a cash ledger backwards from today's balance: the balance at the end of a day is today's
/// minus everything booked after it, exact as long as the ledger is complete.
async fn cash(
    conn: &mut PgConnection,
    account: &Account,
    ledger: &[Transaction],
    balance_today: Decimal,
    today: NaiveDate,
) -> sqlx::Result<Vec<NewSnapshot>> {
    let mut daily_movement: BTreeMap<NaiveDate, Decimal> = BTreeMap::new();
    for tx in ledger.iter().filter(|tx| tx.date <= today) {
        *daily_movement.entry(tx.date).or_default() += tx.amount;
    }
    let Some(oldest) = daily_movement.keys().next().copied() else {
        return Ok(Vec::new());
    };
    let mut existing = existing_dates(conn, account.id, oldest, today).await?;
    let mut created = Vec::new();
    let mut running = balance_today;
    for (day, movement) in daily_movement.iter().rev() {
        // A cash account has no cost basis distinct from its balance.
        add_if_absent(&mut created, account, *day, running, running, &mut existing);
        running -= *movement;
    }
    Ok(created)
}

/// A position as the replay knows it; the cost basis is None once a purchase came without an
/// execution price, rather than a zero that would print the whole line as gain.
#[derive(Debug, Clone)]
struct Position {
    quantity: Decimal,
    cost_basis: Option<Decimal>,
}

/// Values the securities held day by day: quantities and cost replayed from the BUY/SELL stream,
/// prices from price_snapshot. A day where one held line has no price is skipped, and the cash
/// pocket is left out since this ledger never records what funded the account.
async fn securities(
    conn: &mut PgConnection,
    account: &Account,
    ledger: &[Transaction],
    today: NaiveDate,
) -> sqlx::Result<Vec<NewSnapshot>> {
    let mut trades: Vec<&Transaction> = ledger
        .iter()
        .filter(|tx| {
            matches!(
                tx.tx_type,
                Some(TransactionType::Buy | TransactionType::Sell)
            )
        })
        .filter(|tx| tx.ticker.is_some() && tx.quantity.is_some() && tx.date <= today)
        .collect();
    trades.sort_by_key(|tx| tx.date);
    let Some(first_day) = trades.first().map(|tx| tx.date) else {
        return Ok(Vec::new());
    };
    if first_day >= today {
        return Ok(Vec::new());
    }
    let last_day = today - Days::new(1);
    let tickers: Vec<String> = trades
        .iter()
        .filter_map(|tx| tx.ticker.clone())
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect();
    let prices: HashMap<(String, NaiveDate), Decimal> =
        prices::price_history(&mut *conn, &tickers, first_day, last_day)
            .await?
            .into_iter()
            .map(|p| ((p.ticker, p.date), p.price_eur))
            .collect();
    let mut existing = existing_dates(conn, account.id, first_day, last_day).await?;

    let mut created = Vec::new();
    let mut held: BTreeMap<String, Position> = BTreeMap::new();
    let mut next = 0;
    let mut day = first_day;
    while day < today {
        while next < trades.len() && trades[next].date <= day {
            apply(&mut held, trades[next]);
            next += 1;
        }
        value_on(&mut created, account, day, &held, &prices, &mut existing);
        day = day + Days::new(1);
    }
    Ok(created)
}

/// A purchase adds what it cost, fees included; a sale removes cost at the position's average.
fn apply(held: &mut BTreeMap<String, Position>, tx: &Transaction) {
    let (Some(ticker), Some(quantity)) = (tx.ticker.as_ref(), tx.quantity) else {
        return;
    };
    let current = held.get(ticker).cloned().unwrap_or(Position {
        quantity: Decimal::ZERO,
        cost_basis: Some(Decimal::ZERO),
    });
    let quantity = quantity.abs();
    let next = if tx.tx_type == Some(TransactionType::Buy) {
        let cost = tx.price_per_unit.map(|price| {
            let cost = quantity * price;
            tx.fees.map_or(cost, |fees| cost + fees.abs())
        });
        Position {
            quantity: current.quantity + quantity,
            cost_basis: current.cost_basis.zip(cost).map(|(a, b)| a + b),
        }
    } else {
        let remaining = current.quantity - quantity;
        Position {
            quantity: remaining,
            cost_basis: basis_after_sale(&current, remaining),
        }
    };
    held.insert(ticker.clone(), next);
}

/// The same average per share over the shares still held; zero once nothing is, so a later
/// purchase can establish a fresh basis.
fn basis_after_sale(current: &Position, remaining: Decimal) -> Option<Decimal> {
    if remaining <= Decimal::ZERO || current.quantity <= Decimal::ZERO {
        return Some(Decimal::ZERO);
    }
    let basis = current.cost_basis?;
    Some(divide(basis * remaining, current.quantity, 8, HALF_UP))
}

/// Values the day and the basis of the very same lines; an unknown basis falls back to the value,
/// so the day claims no gain rather than a wrong one.
fn value_on(
    created: &mut Vec<NewSnapshot>,
    account: &Account,
    date: NaiveDate,
    held: &BTreeMap<String, Position>,
    prices: &HashMap<(String, NaiveDate), Decimal>,
    existing: &mut HashSet<NaiveDate>,
) {
    let mut value = Decimal::ZERO;
    let mut basis = Some(Decimal::ZERO);
    for (ticker, position) in held {
        if position.quantity <= Decimal::ZERO {
            continue;
        }
        let Some(price) = prices.get(&(ticker.clone(), date)) else {
            return;
        };
        value += position.quantity * price;
        basis = basis.zip(position.cost_basis).map(|(a, b)| a + b);
    }
    if value > Decimal::ZERO {
        add_if_absent(
            created,
            account,
            date,
            value,
            basis.unwrap_or(value),
            existing,
        );
    }
}

async fn existing_dates(
    conn: &mut PgConnection,
    account_id: i64,
    from: NaiveDate,
    to: NaiveDate,
) -> sqlx::Result<HashSet<NaiveDate>> {
    Ok(
        snapshots::find_by_account_between(conn, account_id, from, to)
            .await?
            .into_iter()
            .map(|s| s.date)
            .collect(),
    )
}

fn add_if_absent(
    created: &mut Vec<NewSnapshot>,
    account: &Account,
    date: NaiveDate,
    balance: Decimal,
    invested: Decimal,
    existing: &mut HashSet<NaiveDate>,
) {
    if existing.insert(date) {
        created.push(NewSnapshot::derived(account.id, date, balance, invested));
    }
}

async fn persist(
    conn: &mut PgConnection,
    account: &Account,
    created: &[NewSnapshot],
    now: DateTime<Utc>,
) -> sqlx::Result<usize> {
    if created.is_empty() {
        return Ok(0);
    }
    for snapshot in created {
        snapshots::insert(&mut *conn, snapshot, now).await?;
    }
    tracing::info!(
        "Reconstructed {} balance snapshot(s) from the ledger (account={})",
        created.len(),
        account.id
    );
    Ok(created.len())
}
