//! FortuneoTransactionWriter, and the dividend recognitions an import must carry over
//! (DividendReceiptService.Recognition): a recognised dividend row the provider rewrites keeps its
//! ticker, name and category, since the classifier only revisits recent rows.

use std::collections::{HashMap, HashSet, VecDeque};

use chrono::NaiveDate;
use rust_decimal::Decimal;
use sqlx::PgConnection;

use crate::ledger::model::{NewTransaction, Transaction, TransactionType};
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::transactions;

/// DividendReceiptService.CATEGORY_SLUG: the automatic category of a recognised dividend.
const DIVIDEND_CATEGORY_SLUG: &str = "revenus-capital";

/// The fields a recognition reads and writes, on a stored row or one about to be inserted.
pub trait RecognisedRow {
    fn date(&self) -> NaiveDate;
    fn amount(&self) -> Decimal;
    fn description(&self) -> &str;
    fn tx_type(&self) -> Option<TransactionType>;
    fn set_tx_type(&mut self, value: Option<TransactionType>);
    fn ticker(&self) -> Option<&str>;
    fn set_ticker(&mut self, value: Option<String>);
    fn name(&self) -> Option<&str>;
    fn set_name(&mut self, value: Option<String>);
    fn category_id(&self) -> Option<i64>;
    fn set_category(&mut self, id: Option<i64>, manual: bool);
    fn category_manual(&self) -> bool;
}

macro_rules! recognised_row {
    ($row:ty) => {
        impl RecognisedRow for $row {
            fn date(&self) -> NaiveDate {
                self.date
            }
            fn amount(&self) -> Decimal {
                self.amount
            }
            fn description(&self) -> &str {
                &self.description
            }
            fn tx_type(&self) -> Option<TransactionType> {
                self.tx_type
            }
            fn set_tx_type(&mut self, value: Option<TransactionType>) {
                self.tx_type = value;
            }
            fn ticker(&self) -> Option<&str> {
                self.ticker.as_deref()
            }
            fn set_ticker(&mut self, value: Option<String>) {
                self.ticker = value;
            }
            fn name(&self) -> Option<&str> {
                self.name.as_deref()
            }
            fn set_name(&mut self, value: Option<String>) {
                self.name = value;
            }
            fn category_id(&self) -> Option<i64> {
                self.category_id
            }
            fn set_category(&mut self, id: Option<i64>, manual: bool) {
                self.category_id = id;
                self.category_manual = manual;
            }
            fn category_manual(&self) -> bool {
                self.category_manual
            }
        }
    };
}

recognised_row!(Transaction);
recognised_row!(NewTransaction);

fn blank(value: Option<&str>) -> bool {
    value.is_none_or(java_is_blank)
}

/// The member's categories carrying the dividend slug.
pub async fn dividend_categories(
    conn: &mut PgConnection,
    member_id: i64,
) -> sqlx::Result<HashSet<i64>> {
    let ids: Vec<i64> =
        sqlx::query_scalar("SELECT id FROM category WHERE member_id = $1 AND slug = $2")
            .bind(member_id)
            .bind(DIVIDEND_CATEGORY_SLUG)
            .fetch_all(conn)
            .await?;
    Ok(ids.into_iter().collect())
}

/// What a recognised dividend row carries beyond the provider's fields.
#[derive(Debug, Clone)]
pub struct Recognition {
    ticker: String,
    name: Option<String>,
    category_id: Option<i64>,
    category_manual: bool,
}

impl Recognition {
    /// The recognition a stored row carries, or None when it is not a dividend.
    pub fn of(row: &impl RecognisedRow) -> Option<Self> {
        if row.tx_type() != Some(TransactionType::Dividend) || blank(row.ticker()) {
            return None;
        }
        Some(Self {
            ticker: row.ticker()?.to_owned(),
            name: row.name().map(str::to_owned),
            category_id: row.category_id(),
            category_manual: row.category_manual(),
        })
    }

    /// Restores it on the provider's version of the row, unless the provider types or names it
    /// otherwise; then the automatic dividend category a row updated in place still carries goes.
    pub fn restore_on(&self, row: &mut impl RecognisedRow, dividend_categories: &HashSet<i64>) {
        let provider_typed = row
            .tx_type()
            .is_some_and(|t| t != TransactionType::Deposit && t != TransactionType::Dividend);
        let provider_named = row
            .ticker()
            .is_some_and(|t| !java_is_blank(t) && t.to_uppercase() != self.ticker.to_uppercase());
        if provider_typed || provider_named {
            if !row.category_manual()
                && row
                    .category_id()
                    .is_some_and(|id| dividend_categories.contains(&id))
            {
                row.set_category(None, false);
            }
            return;
        }
        row.set_tx_type(Some(TransactionType::Dividend));
        if blank(row.ticker()) {
            row.set_ticker(Some(self.ticker.clone()));
        }
        if blank(row.name()) {
            row.set_name(self.name.clone());
        }
        if row.category_id().is_none() {
            row.set_category(self.category_id, self.category_manual);
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct RowKey {
    date: NaiveDate,
    amount: Decimal,
    description: String,
}

impl RowKey {
    fn of(row: &impl RecognisedRow) -> Self {
        Self {
            date: row.date(),
            amount: row.amount().normalize(),
            description: row.description().to_owned(),
        }
    }
}

/// Recognitions carried by rows an import is about to delete. A re-imported copy has no id to
/// match on, so it is found again by date, amount and label; each one restores once.
#[derive(Debug, Default)]
pub struct Recognitions {
    by_row: HashMap<RowKey, VecDeque<Recognition>>,
}

impl Recognitions {
    pub fn of<'a, R: RecognisedRow + 'a>(deleted: impl IntoIterator<Item = &'a R>) -> Self {
        let mut recognitions = Self::default();
        for row in deleted {
            if let Some(recognition) = Recognition::of(row) {
                recognitions
                    .by_row
                    .entry(RowKey::of(row))
                    .or_default()
                    .push_back(recognition);
            }
        }
        recognitions
    }

    pub fn restore_on(
        &mut self,
        copy: &mut impl RecognisedRow,
        dividend_categories: &HashSet<i64>,
    ) {
        if let Some(previous) = self
            .by_row
            .get_mut(&RowKey::of(copy))
            .and_then(VecDeque::pop_front)
        {
            previous.restore_on(copy, dividend_categories);
        }
    }
}

/// One row of a full-history reconciliation: a stored row updated in place, or a new one.
pub enum Upsert {
    Update(Box<Transaction>),
    Insert(Box<NewTransaction>),
}

/// replaceRecentTransactions: deletes the synced rows from `cutoff` on, then inserts their
/// replacements, inside the caller's transaction.
pub async fn replace_recent_transactions(
    conn: &mut PgConnection,
    account_id: i64,
    cutoff: NaiveDate,
    replacements: &[NewTransaction],
    now: chrono::DateTime<chrono::Utc>,
) -> sqlx::Result<()> {
    transactions::delete_synced_by_account(&mut *conn, account_id, Some(cutoff)).await?;
    for row in replacements {
        transactions::insert(&mut *conn, row, now).await?;
    }
    Ok(())
}

/// reconcileHistory: drops the obsolete rows first, so a replaced row releases its
/// (account_id, external_id) slot, then writes every reported row; all inside the caller's
/// transaction, so a failure leaves the account's history as it was.
pub async fn reconcile_history(
    conn: &mut PgConnection,
    obsolete: &[Transaction],
    upserts: &[Upsert],
    now: chrono::DateTime<chrono::Utc>,
) -> sqlx::Result<()> {
    for row in obsolete {
        transactions::delete(&mut *conn, row.id).await?;
    }
    for upsert in upserts {
        match upsert {
            Upsert::Update(row) => {
                transactions::update(&mut *conn, row).await?;
            }
            Upsert::Insert(row) => {
                transactions::insert(&mut *conn, row, now).await?;
            }
        }
    }
    Ok(())
}
