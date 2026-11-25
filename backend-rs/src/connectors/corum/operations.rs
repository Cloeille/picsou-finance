//! CorumLifeOperationWriter: the operations of one CORUM Life contract as its account's
//! transactions. Only a payment moves money in from outside (a DEPOSIT filed under the member's
//! \`investissement\` transfer category); everything else happens inside the contract and is
//! budget-neutral. No row is BUY or SELL: the holdings are CORUM's own figures.
//!
//! The list is reconciled by CORUM's operation id: an update never touches the category, and the
//! synced rows CORUM no longer reports between its oldest date and today are deleted. A missing
//! or empty list keeps every stored row.

use std::collections::{HashMap, HashSet};

use crate::db::DbHandle;
use chrono::NaiveDate;
use rust_decimal::Decimal;

use super::adapter::{LifeHolding, LifeOperation};
use crate::AppState;
use crate::connectors::sidecar::JobResult;
use crate::ledger::model::{Account, CategoryKind, NewTransaction, Transaction, TransactionType};
use crate::ledger::sync_values::clean;
use crate::ledger::{categorize, transactions};

pub const EXTERNAL_ID_PREFIX: &str = "corum:";
const INVESTMENT_SLUG: &str = "investissement";
pub const TICKER_PREFIX: &str = "CORUM-LIFE-";
const DESCRIPTION_MAX: usize = 255;
const NAME_MAX: usize = 100;
const TICKER_MAX: usize = 30;
const PAYMENTS: [&str; 2] = ["scheduledPayment", "subscription"];
/// Neutral English fallbacks; the UI labels a row by its type.
const DESCRIPTIONS: [(&str, &str); 6] = [
    ("scheduledPayment", "Scheduled payment"),
    ("subscription", "Subscription payment"),
    ("couponDetachment", "Coupon"),
    ("administrativeManagementCosts", "Management fees"),
    ("profitParticipation", "Profit participation"),
    ("arbitrage", "Switch between funds"),
];
const UNKNOWN_DESCRIPTION: &str = "CORUM Life operation";

/// What one reconciliation did, as counts only: the rows carry the member's money.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ReconcileResult {
    pub inserted: usize,
    pub updated: usize,
    pub deleted: usize,
    pub unknown_types: usize,
}

fn description(kind: &str) -> Option<&'static str> {
    DESCRIPTIONS
        .iter()
        .find(|(k, _)| *k == kind)
        .map(|(_, d)| *d)
}

fn truncate(value: &str, max: usize) -> String {
    value.chars().take(max).collect()
}

fn id_of(operation: &LifeOperation) -> &str {
    operation.operation_id.as_deref().unwrap_or("").trim()
}

fn amount_of(operation: &LifeOperation) -> Decimal {
    operation.amount_eur.unwrap_or_default()
}

/// The stable key of each operation, in list order: \`corum:<id>\`, or \`corum:<id>:+\` /
/// \`corum:<id>:-\` when two operations of one list share an id (the two legs of a switch can).
/// None when a key still repeats: two rows cannot both own one key.
pub fn external_ids(operations: &[&LifeOperation]) -> Option<Vec<String>> {
    let mut id_counts: HashMap<&str, usize> = HashMap::new();
    for operation in operations {
        *id_counts.entry(id_of(operation)).or_default() += 1;
    }
    let mut keys = Vec::with_capacity(operations.len());
    let mut seen = HashSet::new();
    for operation in operations {
        let id = id_of(operation);
        let mut key = format!("{EXTERNAL_ID_PREFIX}{id}");
        if id_counts[id] > 1 {
            key.push_str(if amount_of(operation) < Decimal::ZERO {
                ":-"
            } else {
                ":+"
            });
        }
        if !seen.insert(key.clone()) {
            return None;
        }
        keys.push(key);
    }
    Some(keys)
}

/// The fields CORUM owns on a row; the category and its manual flag are never touched here.
struct Applied {
    date: NaiveDate,
    description: String,
    amount: Decimal,
    kind: String,
    tx_type: Option<TransactionType>,
    ticker: Option<String>,
    name: Option<String>,
    budget_neutral: bool,
}

fn applied(operation: &LifeOperation, kind: &str, fund_names: &HashMap<String, String>) -> Applied {
    let ticker = if kind == "couponDetachment" {
        coupon_ticker(operation.fund_codes.as_deref())
    } else {
        None
    };
    let name = ticker.as_ref().map(|t| {
        let fallback = format!("CORUM {}", &t[TICKER_PREFIX.len()..]);
        truncate(fund_names.get(t).unwrap_or(&fallback), NAME_MAX)
    });
    Applied {
        date: operation.date.unwrap_or_default(),
        description: truncate(
            description(kind).unwrap_or(UNKNOWN_DESCRIPTION),
            DESCRIPTION_MAX,
        ),
        amount: amount_of(operation),
        kind: kind.to_owned(),
        tx_type: tx_type_of(kind),
        ticker,
        name,
        budget_neutral: !PAYMENTS.contains(&kind),
    }
}

/// Payments are deposits, coupons dividends, management costs fees; a profit participation, a
/// switch leg or an unknown type moves nothing in or out.
fn tx_type_of(kind: &str) -> Option<TransactionType> {
    match kind {
        "scheduledPayment" | "subscription" => Some(TransactionType::Deposit),
        "couponDetachment" => Some(TransactionType::Dividend),
        "administrativeManagementCosts" => Some(TransactionType::Fee),
        _ => None,
    }
}

/// The holding a coupon belongs to when CORUM names exactly one fund; a coupon for several funds
/// stays unattributed rather than guessed.
fn coupon_ticker(fund_codes: Option<&[String]>) -> Option<String> {
    let [code] = fund_codes? else {
        return None;
    };
    let code = clean(Some(code))?;
    let ticker = format!("{TICKER_PREFIX}{}", code.to_uppercase());
    (ticker.chars().count() <= TICKER_MAX).then_some(ticker)
}

fn fund_names(holdings: &[&LifeHolding]) -> HashMap<String, String> {
    holdings
        .iter()
        .filter_map(|h| {
            let label = clean(h.label.as_deref())?;
            let code = h
                .product_code
                .as_deref()
                .unwrap_or("")
                .trim()
                .to_uppercase();
            Some((format!("{TICKER_PREFIX}{code}"), label))
        })
        .collect()
}

/// The stored single-key row a new \`:+\` / \`:-\` leg replaces, when its amount has the leg's
/// sign and the member had categorized it.
fn predecessor_of<'a>(
    key: &str,
    obsolete_by_id: &'a HashMap<String, Transaction>,
) -> Option<&'a Transaction> {
    let base = key.strip_suffix(":+").or_else(|| key.strip_suffix(":-"))?;
    let stored = obsolete_by_id.get(base)?;
    if stored.category_id.is_none() && !stored.category_manual {
        return None;
    }
    let stored_sign = if stored.amount < Decimal::ZERO {
        ":-"
    } else {
        ":+"
    };
    key.ends_with(stored_sign).then_some(stored)
}

/// The member's seeded transfer category for investments; an archived one, or one turned into
/// another kind, leaves the payment uncategorized.
async fn investment_category(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    member_id: i64,
) -> sqlx::Result<Option<i64>> {
    let category =
        categorize::find_or_create_default(conn, member_id, INVESTMENT_SLUG, state.clock.now())
            .await?
            .filter(|c| c.kind == CategoryKind::Transfer);
    if category.is_none() {
        tracing::info!(
            "CORUM Life payments left uncategorized: no investment category (member={member_id})"
        );
    }
    Ok(category.map(|c| c.id))
}

/// Reconciles the account's synced rows against \`operations\`, already validated.
pub async fn reconcile(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    member_id: i64,
    account: &Account,
    operations: Option<&[&LifeOperation]>,
    holdings: &[&LifeHolding],
) -> JobResult<ReconcileResult> {
    let Some(operations) = operations.filter(|ops| !ops.is_empty()) else {
        return Ok(ReconcileResult::default());
    };
    let Some(keys) = external_ids(operations) else {
        return Ok(ReconcileResult::default());
    };
    let reported: Vec<(&String, &LifeOperation)> =
        keys.iter().zip(operations.iter().copied()).collect();
    let reported_keys: HashSet<&str> = keys.iter().map(String::as_str).collect();
    let from = operations
        .iter()
        .filter_map(|o| o.date)
        .min()
        .unwrap_or_default();
    let to = state.clock.today();

    let mut stored_by_id: HashMap<String, Transaction> = HashMap::new();
    let mut obsolete_by_id: HashMap<String, Transaction> = HashMap::new();
    for row in transactions::find_synced_by_account(&mut *conn, account.id, None).await? {
        let Some(external_id) = row.external_id.clone() else {
            continue;
        };
        if reported_keys.contains(external_id.as_str()) {
            stored_by_id.insert(external_id, row);
        } else if external_id.starts_with(EXTERNAL_ID_PREFIX) && row.date >= from && row.date <= to
        {
            // Only rows CORUM wrote can be declared gone: an adopted account may carry synced
            // rows from another importer that CORUM never reported.
            obsolete_by_id.insert(external_id, row);
        }
    }

    let names = fund_names(holdings);
    let mut investment: Option<Option<i64>> = None;
    let mut inserts = Vec::new();
    let mut updates = Vec::new();
    let mut unknown = 0;
    for (key, operation) in reported {
        let kind = operation.kind.as_deref().unwrap_or("").trim();
        if description(kind).is_none() {
            unknown += 1;
        }
        let fields = applied(operation, kind, &names);
        match stored_by_id.remove(key) {
            Some(mut row) => {
                row.date = fields.date;
                row.description = fields.description;
                row.amount = fields.amount;
                row.kind = Some(fields.kind);
                row.tx_type = fields.tx_type;
                row.ticker = fields.ticker;
                row.name = fields.name;
                row.budget_neutral = fields.budget_neutral;
                updates.push(row);
            }
            None => {
                let mut row =
                    NewTransaction::new(account.id, fields.date, fields.description, fields.amount);
                row.external_id = Some(key.clone());
                row.external_transaction_id = Some(key.clone());
                row.native_currency = "EUR".to_owned();
                row.kind = Some(fields.kind);
                row.tx_type = fields.tx_type;
                row.ticker = fields.ticker;
                row.name = fields.name;
                row.budget_neutral = fields.budget_neutral;
                if PAYMENTS.contains(&kind) {
                    if investment.is_none() {
                        investment = Some(investment_category(state, conn, member_id).await?);
                    }
                    // Set on insert only: a category the member chose later is theirs.
                    row.category_id = investment.flatten();
                }
                if let Some(predecessor) = predecessor_of(key, &obsolete_by_id) {
                    row.category_id = predecessor.category_id;
                    row.category_manual = predecessor.category_manual;
                }
                inserts.push(row);
            }
        }
    }

    // The deleted rows release their (account, external id) slots before an insert reclaims one.
    for row in obsolete_by_id.values() {
        transactions::delete(&mut *conn, row.id).await?;
    }
    let now = state.clock.now();
    for row in &updates {
        transactions::update(&mut *conn, row).await?;
    }
    for row in &inserts {
        transactions::insert(&mut *conn, row, now).await?;
    }
    Ok(ReconcileResult {
        inserted: inserts.len(),
        updated: updates.len(),
        deleted: obsolete_by_id.len(),
        unknown_types: unknown,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn op(id: &str, amount: &str) -> LifeOperation {
        LifeOperation {
            operation_id: Some(id.to_owned()),
            kind: Some("arbitrage".to_owned()),
            date: NaiveDate::from_ymd_opt(2025, 1, 2),
            amount_eur: Some(amount.parse().unwrap()),
            fund_codes: None,
        }
    }

    #[test]
    fn two_operations_sharing_an_id_are_keyed_by_sign() {
        let (a, b, c) = (op("1", "-10"), op("1", "10"), op("2", "5"));
        assert_eq!(
            external_ids(&[&a, &b, &c]),
            Some(vec![
                "corum:1:-".to_owned(),
                "corum:1:+".to_owned(),
                "corum:2".to_owned()
            ])
        );
    }

    #[test]
    fn two_operations_still_sharing_a_key_have_no_keys() {
        let (a, b) = (op("1", "10"), op("1", "3"));
        assert_eq!(external_ids(&[&a, &b]), None);
    }

    #[test]
    fn a_coupon_for_several_funds_or_none_stays_unattributed() {
        assert_eq!(coupon_ticker(None), None);
        assert_eq!(coupon_ticker(Some(&["a".to_owned(), "b".to_owned()])), None);
        assert_eq!(
            coupon_ticker(Some(&[" cxl ".to_owned()])),
            Some("CORUM-LIFE-CXL".to_owned())
        );
        assert_eq!(coupon_ticker(Some(&["x".repeat(30)])), None);
    }
}
