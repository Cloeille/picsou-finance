//! The import part of FinaryPersistenceHelper: the balance history rebuilt from the file's
//! transactions, and the account's synced transactions replaced by the file's.

use std::collections::{HashMap, VecDeque};

use crate::db::DbHandle;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;

use super::java_equals_ignore_case;
use super::parse::{ParsedAccount, ParsedTransaction};
use crate::error::AppResult;
use crate::ledger::manual_ledger::{plan_reconstruction, to_eur_or_fail};
use crate::ledger::model::{NewTransaction, SnapshotSource, Transaction, TransactionType};
use crate::ledger::prices::PriceRead;
use crate::ledger::snapshots::{self, NewSnapshot};
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::transactions;

fn of_account<'a>(
    account: &'a ParsedAccount,
    all: &'a [ParsedTransaction],
) -> impl Iterator<Item = &'a ParsedTransaction> {
    all.iter()
        .filter(move |tx| java_equals_ignore_case(&tx.account_name, &account.name))
}

fn history_floor() -> NaiveDate {
    NaiveDate::from_ymd_opt(2000, 1, 1).unwrap_or(NaiveDate::MIN)
}

/// reconstructSnapshots: walks back from the file balance (in EUR) through the account's
/// transactions. Hand-entered statements are kept, their dates skipped, and nothing is derived
/// before the oldest one reached. Every amount is converted before the history is touched.
pub async fn reconstruct_snapshots(
    conn: &mut DbHandle<'_>,
    prices: &dyn PriceRead,
    account_id: i64,
    finary: &ParsedAccount,
    all: &[ParsedTransaction],
    today: NaiveDate,
    now: DateTime<Utc>,
) -> AppResult<usize> {
    let mut account_tx: Vec<&ParsedTransaction> = of_account(finary, all).collect();
    account_tx.sort_by_key(|tx| std::cmp::Reverse(tx.date));
    let anchor = to_eur_or_fail(prices, finary.balance.value, &finary.currency).await?;
    let stored = snapshots::find_recent_by_account(&mut *conn, account_id, history_floor()).await?;

    if account_tx.is_empty() {
        if stored
            .iter()
            .any(|s| s.source == SnapshotSource::Manual && s.date == today)
        {
            return Ok(0);
        }
        if let Some(existing) =
            snapshots::find_by_account_and_date(&mut *conn, account_id, today).await?
        {
            snapshots::delete(&mut *conn, existing.id).await?;
        }
        snapshots::insert(
            &mut *conn,
            &NewSnapshot::derived(account_id, today, anchor, anchor),
            now,
        )
        .await?;
        return Ok(1);
    }

    let mut ledger_eur = Vec::with_capacity(account_tx.len());
    for tx in account_tx {
        ledger_eur.push((
            tx.date,
            to_eur_or_fail(prices, tx.amount, &tx.currency).await?,
        ));
    }
    let plan = plan_reconstruction(anchor, &ledger_eur, &stored, today);
    for id in &plan.delete_ids {
        snapshots::delete(&mut *conn, *id).await?;
    }
    for (date, balance) in &plan.inserts {
        snapshots::insert(
            &mut *conn,
            &NewSnapshot::derived(account_id, *date, *balance, *balance),
            now,
        )
        .await?;
    }
    Ok(plan.inserts.len())
}

/// DividendReceiptService.Recognition: what a deleted row knew about a dividend.
struct Recognition {
    ticker: String,
    name: Option<String>,
    category_id: Option<i64>,
    category_manual: bool,
}

type RowKey = (NaiveDate, Decimal, String);

fn row_key(date: NaiveDate, amount: Decimal, description: &str) -> RowKey {
    (date, amount.normalize(), description.to_owned())
}

/// Recognitions.of: the dividends among the rows about to be deleted, by date, amount and label.
fn recognitions(deleted: &[Transaction]) -> HashMap<RowKey, VecDeque<Recognition>> {
    let mut out: HashMap<RowKey, VecDeque<Recognition>> = HashMap::new();
    for row in deleted {
        let Some(ticker) = row.ticker.as_deref().filter(|t| !java_is_blank(t)) else {
            continue;
        };
        if row.tx_type != Some(TransactionType::Dividend) {
            continue;
        }
        out.entry(row_key(row.date, row.amount, &row.description))
            .or_default()
            .push_back(Recognition {
                ticker: ticker.to_owned(),
                name: row.name.clone(),
                category_id: row.category_id,
                category_manual: row.category_manual,
            });
    }
    out
}

/// importTransactions: the account's synced rows are replaced by the file's; manual rows stay.
/// A file row carries no type, ticker or category, so a recognition always applies in full.
pub async fn import_transactions(
    conn: &mut DbHandle<'_>,
    account_id: i64,
    finary: &ParsedAccount,
    all: &[ParsedTransaction],
    now: DateTime<Utc>,
) -> AppResult<usize> {
    let deleted = transactions::find_synced_by_account(&mut *conn, account_id, None).await?;
    let mut recognised = recognitions(&deleted);
    transactions::delete_synced_by_account(&mut *conn, account_id, None).await?;
    let mut count = 0;
    for tx in of_account(finary, all) {
        let mut row = NewTransaction::new(account_id, tx.date, tx.description.clone(), tx.amount);
        row.kind = Some(tx.kind.clone());
        row.category = Some(tx.category.clone());
        row.native_currency = tx.currency.clone();
        if let Some(previous) = recognised
            .get_mut(&row_key(tx.date, tx.amount, &tx.description))
            .and_then(VecDeque::pop_front)
        {
            row.tx_type = Some(TransactionType::Dividend);
            row.ticker = Some(previous.ticker);
            row.name = previous.name;
            row.category_id = previous.category_id;
            row.category_manual = previous.category_manual;
        }
        transactions::insert(&mut *conn, &row, now).await?;
        count += 1;
    }
    Ok(count)
}
