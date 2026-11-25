//! FinaryPersistenceHelper.importTransactions: the account's synced rows are replaced, manual
//! ones kept, and a dividend recognised on a deleted row comes back on its re-imported copy.

use std::collections::{HashMap, VecDeque};

use crate::db::DbHandle;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;

use crate::ledger::model::{NewTransaction, Transaction, TransactionType};
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::transactions;

type RowKey = (NaiveDate, Decimal, String);

fn key(date: NaiveDate, amount: Decimal, description: &str) -> RowKey {
    (date, amount.normalize(), description.to_owned())
}

/// DividendReceiptService.Recognition: what a deleted dividend row carried.
struct Recognition {
    ticker: String,
    name: Option<String>,
    category_id: Option<i64>,
    category_manual: bool,
}

/// Recognitions keyed by date, amount and label; each restores once. A re-imported Finary row
/// carries no type, ticker or category, so a recognition always applies in full.
fn recognitions(deleted: &[Transaction]) -> HashMap<RowKey, VecDeque<Recognition>> {
    let mut by_row: HashMap<RowKey, VecDeque<Recognition>> = HashMap::new();
    for row in deleted {
        let Some(ticker) = row.ticker.as_deref().filter(|t| !java_is_blank(t)) else {
            continue;
        };
        if row.tx_type != Some(TransactionType::Dividend) {
            continue;
        }
        by_row
            .entry(key(row.date, row.amount, &row.description))
            .or_default()
            .push_back(Recognition {
                ticker: ticker.to_owned(),
                name: row.name.clone(),
                category_id: row.category_id,
                category_manual: row.category_manual,
            });
    }
    by_row
}

pub async fn import_transactions(
    conn: &mut DbHandle<'_>,
    account_id: i64,
    rows: Vec<NewTransaction>,
    now: DateTime<Utc>,
) -> sqlx::Result<usize> {
    let existing = transactions::find_synced_by_account(&mut *conn, account_id, None).await?;
    let mut recognised = recognitions(&existing);
    transactions::delete_synced_by_account(&mut *conn, account_id, None).await?;
    let count = rows.len();
    for mut row in rows {
        if let Some(recognition) = recognised
            .get_mut(&key(row.date, row.amount, &row.description))
            .and_then(VecDeque::pop_front)
        {
            row.tx_type = Some(TransactionType::Dividend);
            row.ticker = Some(recognition.ticker);
            row.name = recognition.name;
            row.category_id = recognition.category_id;
            row.category_manual = recognition.category_manual;
        }
        transactions::insert(&mut *conn, &row, now).await?;
    }
    Ok(count)
}
