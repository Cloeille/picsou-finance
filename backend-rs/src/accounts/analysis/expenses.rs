//! EssentialExpenseEstimator: what the member's current accounts suggest they spend monthly,
//! internal transfers and investment legs removed. Offered, never stored.

use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

use crate::db::DbHandle;
use chrono::{Datelike, Months, NaiveDate};
use regex::Regex;
use rust_decimal::Decimal;
use rust_decimal::prelude::ToPrimitive;
use serde::Serialize;

use crate::db;
use crate::ledger::access::{self, weigh};
use crate::ledger::decimal::{HALF_UP, divide, set_scale};
use crate::ledger::model::{
    Account, AccountType, TRANSACTION_COLUMNS, Transaction, TransactionType,
};
use crate::ledger::prices::PriceRead;
use crate::ledger::sync_values::java_is_blank;

const WINDOW_MONTHS: u32 = 6;
const TRANSFER_MATCH_DAYS: i64 = 3;

/// Narrow on purpose: label matching is the last resort after counterparty matching. ASCII
/// case folding and ASCII whitespace, as java.util.regex without UNICODE flags.
static TRANSFER_LABELS: LazyLock<Vec<Regex>> = LazyLock::new(|| {
    [
        r"VIR(EMENT)?\s+INTERNE",
        r"VIR(EMENT)?\s+DE\s+COMPTE\s+A\s+COMPTE",
        r"VERS\s+(LE\s+)?LIVRET",
        r"EPARGNE\s+AUTO",
        r"TRANSFER\s+TO\s+(MY|SELF)",
        r"INTERNAL\s+TRANSFER",
        // A Revolut exchange between pockets; the code stays upper-case only.
        r"^EXCHANGED\s+(TO|FROM)\s+(?-i:[A-Z]{3,5})$",
    ]
    .iter()
    .map(|p| Regex::new(&format!("(?i-u){p}")).expect("transfer label regex"))
    .collect()
});

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EssentialExpenseEstimateResponse {
    #[serde(with = "crate::accounts::jdecimal::option")]
    estimate: Option<Decimal>,
    months_observed: usize,
    excluded_transfer_count: usize,
}

fn cents(amount: Decimal) -> i64 {
    (set_scale(amount, 2, HALF_UP) * Decimal::ONE_HUNDRED)
        .to_i64()
        .unwrap_or_default()
}

pub async fn estimate(
    conn: &mut DbHandle<'_>,
    prices: &dyn PriceRead,
    member_id: i64,
    today: NaiveDate,
) -> sqlx::Result<EssentialExpenseEstimateResponse> {
    let readable = access::readable_accounts(&mut *conn, member_id).await?;
    if readable.is_empty() {
        return Ok(EssentialExpenseEstimateResponse {
            estimate: None,
            months_observed: 0,
            excluded_transfer_count: 0,
        });
    }
    let shares = access::shares_for(&mut *conn, &readable, member_id).await?;
    // The current month is left out: a partial one would under-size the safety net.
    let this_month = today.with_day(1).expect("first of month");
    let from = this_month - Months::new(WINDOW_MONTHS);
    let to = this_month - chrono::Days::new(1);
    let by_id: HashMap<i64, &Account> = readable.iter().map(|a| (a.id, a)).collect();
    let ids: Vec<i64> = readable.iter().map(|a| a.id).collect();
    let all: Vec<Transaction> = db::query_as(&format!(
        "SELECT {TRANSACTION_COLUMNS} FROM \"transaction\" t WHERE t.account_id = ANY($1) \
         AND t.date BETWEEN $2 AND $3 ORDER BY t.date, t.id"
    ))
    .bind(&ids)
    .bind(from)
    .bind(to)
    .fetch_all(&mut *conn)
    .await?;

    // Credits on every readable account: the other leg of a top-up lands on the livret.
    let mut credits_by_cents: HashMap<i64, Vec<&Transaction>> = HashMap::new();
    let mut credits_by_external_id: HashMap<&str, Vec<&Transaction>> = HashMap::new();
    for t in &all {
        if t.budget_neutral || t.amount <= Decimal::ZERO {
            continue;
        }
        credits_by_cents.entry(cents(t.amount)).or_default().push(t);
        if let Some(id) = t.external_id.as_deref().filter(|id| !java_is_blank(id)) {
            credits_by_external_id.entry(id).or_default().push(t);
        }
    }
    let mut consumed: HashSet<i64> = HashSet::new();
    let mut monthly: HashMap<(i32, u32), Decimal> = HashMap::new();
    let mut excluded = 0;
    for t in &all {
        let Some(account) = by_id.get(&t.account_id) else {
            continue;
        };
        if account.account_type != AccountType::Checking
            || t.amount >= Decimal::ZERO
            || t.budget_neutral
            || t.ticker.as_deref().is_some_and(|tk| !java_is_blank(tk))
            || t.tx_type.is_some_and(|k| {
                matches!(
                    k,
                    TransactionType::Buy | TransactionType::Sell | TransactionType::Deposit
                )
            })
        {
            continue;
        }
        if match_shared_id(t, &by_id, &credits_by_external_id, &mut consumed)
            || match_counterparty(t, &credits_by_cents, &mut consumed)
        {
            excluded += 1;
            continue;
        }
        if TRANSFER_LABELS.iter().any(|p| p.is_match(&t.description))
            && !java_is_blank(&t.description)
        {
            excluded += 1;
            continue;
        }
        let eur = prices
            .to_eur(Some(t.amount.abs()), &t.native_currency, None, today)
            .await;
        let weighted = weigh(eur, shares.get(&account.id).copied().unwrap_or_default());
        // Only months that fed the total count in the divisor.
        *monthly
            .entry((t.date.year(), t.date.month()))
            .or_insert(Decimal::ZERO) += weighted;
    }
    let months_observed = monthly.len();
    if months_observed == 0 {
        return Ok(EssentialExpenseEstimateResponse {
            estimate: None,
            months_observed: 0,
            excluded_transfer_count: excluded,
        });
    }
    let total = monthly.values().fold(Decimal::ZERO, |acc, v| acc + *v);
    Ok(EssentialExpenseEstimateResponse {
        estimate: Some(divide(total, Decimal::from(months_observed), 2, HALF_UP)),
        months_observed,
        excluded_transfer_count: excluded,
    })
}

fn within_match_window(a: NaiveDate, b: NaiveDate) -> bool {
    (a - b).num_days().abs() <= TRANSFER_MATCH_DAYS
}

/// A credit of the same amount on another account within a few days; consumed once matched.
fn match_counterparty(
    debit: &Transaction,
    credits: &HashMap<i64, Vec<&Transaction>>,
    consumed: &mut HashSet<i64>,
) -> bool {
    let Some(candidates) = credits.get(&cents(debit.amount.abs())) else {
        return false;
    };
    for credit in candidates {
        if consumed.contains(&credit.id)
            || credit.account_id == debit.account_id
            || !within_match_window(debit.date, credit.date)
        {
            continue;
        }
        consumed.insert(credit.id);
        return true;
    }
    false
}

/// A credit on another account of the same known provider carrying the debit's provider id.
fn match_shared_id(
    debit: &Transaction,
    accounts: &HashMap<i64, &Account>,
    credits: &HashMap<&str, Vec<&Transaction>>,
    consumed: &mut HashSet<i64>,
) -> bool {
    let Some(id) = debit.external_id.as_deref().filter(|id| !java_is_blank(id)) else {
        return false;
    };
    let Some(candidates) = credits.get(id) else {
        return false;
    };
    let provider = accounts
        .get(&debit.account_id)
        .and_then(|a| a.provider.as_deref());
    for credit in candidates {
        if consumed.contains(&credit.id) || credit.account_id == debit.account_id {
            continue;
        }
        let credit_provider = accounts
            .get(&credit.account_id)
            .and_then(|a| a.provider.as_deref());
        if provider.is_none() || provider != credit_provider {
            continue;
        }
        if !within_match_window(debit.date, credit.date) {
            continue;
        }
        consumed.insert(credit.id);
        return true;
    }
    false
}
