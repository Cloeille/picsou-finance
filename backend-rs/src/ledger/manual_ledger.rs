//! The manual-ledger part of FinaryPersistenceHelper: the balance of a hand-kept account,
//! anchored on its latest hand-entered statement, and the balance history rebuilt from its
//! transactions around those statements.

use std::collections::{BTreeMap, HashSet};

use chrono::{DateTime, NaiveDate, Utc};
use http::StatusCode;
use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::decimal::{HALF_UP, divide};
use super::model::{Account, BalanceSnapshot, SnapshotSource};
use super::prices::PriceRead;
use super::snapshots::{self, NewSnapshot};
use super::sync_values::java_is_blank;
use super::transactions;
use crate::error::{AppError, AppResult};

/// SyncException without a code: 422 with the message as detail.
fn sync_error(message: String) -> AppError {
    AppError::status(StatusCode::UNPROCESSABLE_ENTITY, message)
}

fn is_eur(currency: &str) -> bool {
    java_is_blank(currency) || currency.eq_ignore_ascii_case("EUR")
}

/// toEurOrFail: a native amount in EUR; a missing rate aborts rather than writing an
/// unconverted amount as EUR.
pub async fn to_eur_or_fail(
    prices: &dyn PriceRead,
    amount: Decimal,
    currency: &str,
) -> AppResult<Decimal> {
    if is_eur(currency) {
        return Ok(amount);
    }
    match prices.fx_rate_eur(&currency.to_uppercase()).await {
        Some(rate) => Ok(amount * rate),
        None => Err(sync_error(format!(
            "No EUR rate for {currency} -- cannot rebuild the balance history, try again once the rate is available"
        ))),
    }
}

/// toNativeOrFail: a EUR snapshot value back in the account's currency, at scale 8 half-up.
pub async fn to_native_or_fail(
    prices: &dyn PriceRead,
    eur_amount: Decimal,
    currency: &str,
) -> AppResult<Decimal> {
    if is_eur(currency) {
        return Ok(eur_amount);
    }
    match prices.fx_rate_eur(&currency.to_uppercase()).await {
        Some(rate) if !rate.is_zero() => Ok(divide(eur_amount, rate, 8, HALF_UP)),
        _ => Err(sync_error(format!(
            "No EUR rate for {currency} -- cannot anchor the balance on the hand-entered statement, try again once the rate is available"
        ))),
    }
}

/// computeManualLedgerBalance: the latest statement dated on or before today (its stored
/// native figure, or its EUR value converted back for legacy rows) plus the transactions booked
/// after it; same-day rows count when created after the statement was typed. Without any
/// reached statement, the sum of every transaction.
pub async fn compute_manual_ledger_balance(
    conn: &mut PgConnection,
    account: &Account,
    prices: &dyn PriceRead,
    today: NaiveDate,
) -> AppResult<Decimal> {
    let anchor = snapshots::find_latest_on_or_before(
        &mut *conn,
        account.id,
        today,
        Some(SnapshotSource::Manual),
    )
    .await?;
    let Some(statement) = anchor else {
        return Ok(transactions::sum_amount(&mut *conn, account.id).await?);
    };
    let anchor_native = match statement.native_balance {
        Some(native) => native,
        None => to_native_or_fail(prices, statement.balance, &account.currency).await?,
    };
    // A DERIVED row recycled into a statement keeps its old created_at: order on the entry.
    let entered_at = statement
        .statement_entered_at
        .unwrap_or(statement.created_at);
    let after = transactions::sum_amount_after_statement(
        &mut *conn,
        account.id,
        statement.date,
        entered_at,
    )
    .await?;
    Ok(anchor_native + after)
}

/// What a rebuild writes: the derived rows to delete, then the rows to insert in order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Reconstruction {
    pub delete_ids: Vec<i64>,
    /// (date, EUR balance), today's anchor first, then the transaction days newest first.
    pub inserts: Vec<(NaiveDate, Decimal)>,
}

/// The rebuild of reconstructSnapshotsFromDb, given the current balance and the ledger
/// (newest first) already in EUR. Each transaction day records its end-of-day balance; MANUAL
/// rows are never touched, their dates are skipped, and no derived row predates the oldest
/// statement reached by today (a future statement does not cut the past).
pub fn plan_reconstruction(
    current_balance_eur: Decimal,
    ledger_newest_first: &[(NaiveDate, Decimal)],
    stored: &[BalanceSnapshot],
    today: NaiveDate,
) -> Reconstruction {
    let manual_dates: HashSet<NaiveDate> = stored
        .iter()
        .filter(|s| s.source == SnapshotSource::Manual)
        .map(|s| s.date)
        .collect();
    let oldest_manual = manual_dates.iter().filter(|d| **d <= today).min().copied();

    let mut by_day: BTreeMap<NaiveDate, Decimal> = BTreeMap::new();
    let mut running = current_balance_eur;
    for (date, amount_eur) in ledger_newest_first {
        by_day.entry(*date).or_insert(running);
        running -= *amount_eur;
    }

    let mut inserts = Vec::new();
    if !manual_dates.contains(&today) {
        inserts.push((today, current_balance_eur));
    }
    inserts.extend(by_day.into_iter().rev().filter(|(date, _)| {
        *date != today
            && !manual_dates.contains(date)
            && oldest_manual.is_none_or(|oldest| *date >= oldest)
    }));
    Reconstruction {
        delete_ids: stored
            .iter()
            .filter(|s| s.source != SnapshotSource::Manual)
            .map(|s| s.id)
            .collect(),
        inserts,
    }
}

/// reconstructSnapshotsFromDb: rebuilds the account's DERIVED balance history from its
/// persisted transactions, anchored on account.current_balance today. Every amount is
/// converted to EUR before the stored history is touched, so a missing rate leaves it as it
/// was. Returns the number of rows written.
pub async fn reconstruct_snapshots_from_db(
    conn: &mut PgConnection,
    account: &Account,
    prices: &dyn PriceRead,
    today: NaiveDate,
    now: DateTime<Utc>,
) -> AppResult<usize> {
    let ledger = transactions::find_by_account_newest_first(&mut *conn, account.id).await?;
    let current = to_eur_or_fail(prices, account.current_balance, &account.currency).await?;
    let stored =
        snapshots::find_recent_by_account(&mut *conn, account.id, java_epoch_floor()).await?;
    let mut ledger_eur = Vec::with_capacity(ledger.len());
    for tx in &ledger {
        ledger_eur.push((
            tx.date,
            to_eur_or_fail(prices, tx.amount, &tx.native_currency).await?,
        ));
    }

    let plan = plan_reconstruction(current, &ledger_eur, &stored, today);
    for id in &plan.delete_ids {
        snapshots::delete(&mut *conn, *id).await?;
    }
    for (date, balance) in &plan.inserts {
        snapshots::insert(
            &mut *conn,
            &NewSnapshot::derived(account.id, *date, *balance, *balance),
            now,
        )
        .await?;
    }
    Ok(plan.inserts.len())
}

/// The LocalDate.of(2000, 1, 1) lower bound of the Java history read.
fn java_epoch_floor() -> NaiveDate {
    NaiveDate::from_ymd_opt(2000, 1, 1).unwrap_or(NaiveDate::MIN)
}
