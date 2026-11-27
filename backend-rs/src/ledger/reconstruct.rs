//! BalanceHistoryReconstructor: rebuilds an account's past snapshots from its ledger, since a
//! sync writes one point dated today. It never overwrites an existing snapshot: a same-date row
//! is better evidence than a reconstruction, so it only fills holes. The whole ledger is walked,
//! not just what the last sync imported: rows entered before the sync (manual ones among them)
//! moved the balance too, and the curve back-propagates them like any other movement.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};

use crate::db::{AsDbHandle, DbHandle};
use chrono::{DateTime, Days, NaiveDate, Utc};
use rust_decimal::Decimal;

use super::decimal::{HALF_UP, divide};
use super::manual_ledger::to_eur_or_fail;
use super::model::{Account, AccountType, Transaction, TransactionType};
use super::prices::PriceRead;
use super::snapshots::{self, NewSnapshot};
use super::transactions;
use crate::AppState;
use crate::error::AppResult;

/// Rebuilds the holes of one account's history inside the caller's transaction: a failed
/// reconstruction rolls back with the import. `balance_as_of_eur` is the balance recorded at
/// `as_of`; only ledger rows dated on or before `as_of` are walked back from it. Returns how many
/// snapshots were created.
pub async fn reconstruct(
    conn: &mut DbHandle<'_>,
    account: &Account,
    prices: &dyn PriceRead,
    balance_as_of_eur: Decimal,
    as_of: NaiveDate,
    now: DateTime<Utc>,
) -> AppResult<usize> {
    // Only a ledger-summed balance can be walked back from the anchor; position-valued accounts
    // are replayed from their trades (securities) or skipped entirely.
    let securities_replay = matches!(
        account.account_type,
        AccountType::Pea | AccountType::CompteTitres
    );
    if !securities_replay && !account.account_type.is_ledger() {
        return Ok(0);
    }
    let ledger = transactions::find_by_account_newest_first(&mut *conn, account.id).await?;
    if ledger.is_empty() {
        return Ok(0);
    }
    let created = if securities_replay {
        securities(conn, account, prices, &ledger, as_of).await?
    } else {
        cash(conn, account, prices, &ledger, balance_as_of_eur, as_of).await?
    };
    Ok(persist(conn, account, &created, now).await?)
}

/// Rebuilds the history holes of every synced (non-manual) account of a member, anchored on each
/// account's latest snapshot. Runs after an import, in its own transaction so the anchor and the
/// ledger are read from one committed state: a failure is logged and never fails the sync.
/// Returns how many snapshots were created.
pub async fn reconstruct_member_accounts(state: &AppState, member_id: i64) -> AppResult<usize> {
    let mut tx = state.db.begin().await?;
    let list = super::accounts::find_all_by_member(&mut tx.as_handle(), member_id).await?;
    let today = state.clock.today();
    let now = state.clock.now();
    let mut total = 0usize;
    for account in list.iter().filter(|a| !a.is_manual) {
        let anchor = match snapshots::find_latest_on_or_before(
            &mut tx.as_handle(),
            account.id,
            today,
            None,
        )
        .await
        {
            Ok(found) => found,
            Err(e) => {
                tracing::warn!(
                    "History reconstruction could not read the snapshots of account {} (member={member_id}) -- skipping it: {e}",
                    account.id
                );
                continue;
            }
        };
        let Some(anchor) = anchor else { continue };
        // Walk back from the anchor's own date and balance: movements after the anchor belong to
        // later snapshots, and counting them would understate every earlier hole.
        match reconstruct(
            &mut tx.as_handle(),
            account,
            &*state.ledger.prices,
            anchor.balance,
            anchor.date,
            now,
        )
        .await
        {
            Ok(n) => total += n,
            Err(e) => tracing::warn!(
                "History reconstruction failed for account {} (member={member_id}) -- leaving its stored history as it was: {e}",
                account.id
            ),
        }
    }
    tx.commit().await?;
    Ok(total)
}

/// Walks a cash ledger backwards from the balance at `as_of`: the balance at the end of a day is
/// that anchor minus everything booked after it, exact as long as the ledger is complete.
async fn cash(
    conn: &mut DbHandle<'_>,
    account: &Account,
    prices: &dyn PriceRead,
    ledger: &[Transaction],
    balance_as_of_eur: Decimal,
    as_of: NaiveDate,
) -> AppResult<Vec<NewSnapshot>> {
    let mut daily_movement: BTreeMap<NaiveDate, Decimal> = BTreeMap::new();
    for tx in ledger.iter().filter(|tx| tx.date <= as_of) {
        let amount_eur = to_eur_or_fail(prices, tx.amount, &tx.native_currency).await?;
        *daily_movement.entry(tx.date).or_default() += amount_eur;
    }
    let Some(oldest) = daily_movement.keys().next().copied() else {
        return Ok(Vec::new());
    };
    let mut existing = existing_dates(conn, account.id, oldest, as_of).await?;
    let mut created = Vec::new();
    let mut running = balance_as_of_eur;
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
    conn: &mut DbHandle<'_>,
    account: &Account,
    prices: &dyn PriceRead,
    ledger: &[Transaction],
    as_of: NaiveDate,
) -> AppResult<Vec<NewSnapshot>> {
    let mut trades: Vec<&Transaction> = ledger
        .iter()
        .filter(|tx| {
            matches!(
                tx.tx_type,
                Some(TransactionType::Buy | TransactionType::Sell)
            )
        })
        .filter(|tx| tx.ticker.is_some() && tx.quantity.is_some() && tx.date <= as_of)
        .collect();
    trades.sort_by_key(|tx| tx.date);
    let Some(first_day) = trades.first().map(|tx| tx.date) else {
        return Ok(Vec::new());
    };
    if first_day >= as_of {
        return Ok(Vec::new());
    }
    let last_day = as_of - Days::new(1);
    let tickers: Vec<String> = trades
        .iter()
        .filter_map(|tx| tx.ticker.clone())
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect();
    let prices_map: HashMap<(String, NaiveDate), Decimal> =
        super::prices::price_history(&mut *conn, &tickers, first_day, last_day)
            .await?
            .into_iter()
            .map(|p| ((p.ticker, p.date), p.price_eur))
            .collect();
    let mut existing = existing_dates(conn, account.id, first_day, last_day).await?;

    let mut created = Vec::new();
    let mut held: BTreeMap<String, Position> = BTreeMap::new();
    let mut next = 0;
    let mut day = first_day;
    while day < as_of {
        while next < trades.len() && trades[next].date <= day {
            apply(&mut held, trades[next], prices).await?;
            next += 1;
        }
        value_on(
            &mut created,
            account,
            day,
            &held,
            &prices_map,
            &mut existing,
        );
        day = day + Days::new(1);
    }
    Ok(created)
}

/// A purchase adds what it cost, fees included; a sale removes cost at the position's average.
async fn apply(
    held: &mut BTreeMap<String, Position>,
    tx: &Transaction,
    prices: &dyn PriceRead,
) -> AppResult<()> {
    let (Some(ticker), Some(quantity)) = (tx.ticker.as_ref(), tx.quantity) else {
        return Ok(());
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
        let cost_eur = match cost {
            Some(cost) => Some(to_eur_or_fail(prices, cost, &tx.native_currency).await?),
            None => None,
        };
        Position {
            quantity: current.quantity + quantity,
            cost_basis: current.cost_basis.zip(cost_eur).map(|(a, b)| a + b),
        }
    } else {
        let remaining = current.quantity - quantity;
        Position {
            quantity: remaining,
            cost_basis: basis_after_sale(&current, remaining),
        }
    };
    held.insert(ticker.clone(), next);
    Ok(())
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
    prices_map: &HashMap<(String, NaiveDate), Decimal>,
    existing: &mut HashSet<NaiveDate>,
) {
    let mut value = Decimal::ZERO;
    let mut basis = Some(Decimal::ZERO);
    for (ticker, position) in held {
        if position.quantity <= Decimal::ZERO {
            continue;
        }
        let Some(price) = prices_map.get(&(ticker.clone(), date)) else {
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
    conn: &mut DbHandle<'_>,
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
    conn: &mut DbHandle<'_>,
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
