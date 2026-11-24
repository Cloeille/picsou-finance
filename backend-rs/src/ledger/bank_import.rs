//! BankTransactionImportService without the connector fetch: the deduplicating insert of bank
//! transactions shared by Enable Banking (importFor, EUR rows only) and SimpleFIN
//! (importProvided, native currency kept). It never edits an existing row, so a manual
//! category survives every re-sync.

use std::collections::{HashMap, HashSet, VecDeque};

use chrono::{DateTime, Days, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::Deserialize;
use sha2::{Digest, Sha256};

use super::decimal::{HALF_UP, set_scale};
use super::model::{Account, NewTransaction, Transaction};
use super::sync_values::{java_is_blank, java_trim};
use super::transactions;
use crate::db::{AsDbHandle, DbExec, DbHandle};

pub const DEFAULT_INITIAL_HISTORY_DAYS: u64 = 90;
pub const OVERLAP_DAYS: u64 = 7;
const DEFAULT_CURRENCY: &str = "EUR";
const FINGERPRINT_PREFIX: &str = "fp:";
const HASHED_ID_PREFIX: &str = "id:";
const EXTERNAL_TRANSACTION_ID_LIMIT: usize = 128;
const TEXT_LIMIT: usize = 255;
const AMOUNT_SCALE: u32 = 8;
const CATEGORY_MAX: usize = 100;

/// BankConnectorPort.TransactionData: one transaction as the bank reports it.
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransactionData {
    pub external_id: Option<String>,
    pub date: Option<NaiveDate>,
    pub description: Option<String>,
    pub amount: Option<Decimal>,
    pub currency: Option<String>,
    pub category: Option<String>,
}

/// The first day to fetch: the newest synced row minus an overlap, or the initial history for
/// an account without one, or when the newest row is dated in the future (a value-dated entry
/// must not stall the import).
pub async fn window_start(
    conn: &mut DbHandle<'_>,
    account_id: i64,
    today: NaiveDate,
    initial_history_days: u64,
) -> sqlx::Result<NaiveDate> {
    let initial = minus_days(today, initial_history_days);
    let Some(latest) = transactions::latest_synced_date(conn, account_id).await? else {
        return Ok(initial);
    };
    let incremental = minus_days(latest, OVERLAP_DAYS);
    Ok(if incremental > today {
        initial
    } else {
        incremental
    })
}

/// sharedHistoryStart: how far back a first sync reaches.
pub fn shared_history_start(today: NaiveDate, initial_history_days: u64) -> NaiveDate {
    minus_days(today, initial_history_days)
}

fn minus_days(date: NaiveDate, days: u64) -> NaiveDate {
    date.checked_sub_days(Days::new(days))
        .unwrap_or(NaiveDate::MIN)
}

/// importFor after the fetch: keeps the EUR rows only (the budget ledger is EUR-only) and
/// inserts the ones not stored yet. window_start is the date the rows were fetched from.
pub async fn import_fetched(
    conn: &mut DbHandle<'_>,
    account: &Account,
    window_start: NaiveDate,
    fetched: &[TransactionData],
    now: DateTime<Utc>,
) -> sqlx::Result<usize> {
    if account.external_account_id.is_none() || fetched.is_empty() {
        return Ok(0);
    }
    let eur_only: Vec<TransactionData> = fetched
        .iter()
        .filter(|d| java_trim(currency_of(d, account)).eq_ignore_ascii_case(DEFAULT_CURRENCY))
        .cloned()
        .collect();
    let to_insert = select_new(&mut *conn, account, window_start, &eur_only).await?;
    save_all(
        &mut conn.as_handle(),
        account,
        &to_insert,
        fetched.len(),
        window_start,
        now,
    )
    .await
}

/// importProvided: rows a connector already fetched (SimpleFIN), any currency.
pub async fn import_provided(
    conn: &mut DbHandle<'_>,
    account: &Account,
    fetched: &[TransactionData],
    today: NaiveDate,
    initial_history_days: u64,
    now: DateTime<Utc>,
) -> sqlx::Result<usize> {
    if fetched.is_empty() {
        return Ok(0);
    }
    let start = window_start(&mut *conn, account.id, today, initial_history_days).await?;
    let to_insert = select_new(&mut *conn, account, start, fetched).await?;
    save_all(
        &mut conn.as_handle(),
        account,
        &to_insert,
        fetched.len(),
        start,
        now,
    )
    .await
}

async fn save_all(
    conn: &mut DbHandle<'_>,
    account: &Account,
    rows: &[NewTransaction],
    fetched: usize,
    since: NaiveDate,
    now: DateTime<Utc>,
) -> sqlx::Result<usize> {
    if rows.is_empty() {
        tracing::debug!(
            "No new transactions for account {} ({fetched} fetched, all known)",
            account.id
        );
        return Ok(0);
    }
    // saveAll: all rows or none (a savepoint inside the caller's transaction).
    let mut batch = conn.begin().await?;
    for row in rows {
        transactions::insert(&mut batch, row, now).await?;
    }
    batch.commit().await?;
    tracing::info!(
        "Imported {} new transactions for account {} ({fetched} fetched since {since})",
        rows.len(),
        account.id
    );
    Ok(rows.len())
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct Fingerprint {
    date: NaiveDate,
    amount: String,
    description: String,
}

/// The rows of fetched not stored yet. A stable bank id plus content matches one stored row
/// one-for-one; the rest overlaps by content only with rows that carry no stable id (legacy and
/// sidecar rows), each stored row consuming at most one incoming row.
async fn select_new<'e>(
    db: impl DbExec<'e>,
    account: &Account,
    window_start: NaiveDate,
    fetched: &[TransactionData],
) -> sqlx::Result<Vec<NewTransaction>> {
    if fetched.is_empty() {
        return Ok(Vec::new());
    }
    let comparison_from = fetched
        .iter()
        .filter_map(|d| d.date)
        .min()
        .filter(|oldest| *oldest < window_start)
        .unwrap_or(window_start);
    let existing =
        transactions::find_synced_by_account(db, account.id, Some(comparison_from)).await?;
    Ok(select_against(account, &existing, fetched))
}

fn select_against(
    account: &Account,
    existing: &[Transaction],
    fetched: &[TransactionData],
) -> Vec<NewTransaction> {
    let mut consumed_existing = vec![false; existing.len()];
    let mut consumed_fetched = vec![false; fetched.len()];
    let mut by_stable: HashMap<(String, Fingerprint), VecDeque<usize>> = HashMap::new();
    let mut by_fingerprint: HashMap<Fingerprint, VecDeque<usize>> = HashMap::new();
    let mut overlap_by_fingerprint: HashMap<Fingerprint, VecDeque<usize>> = HashMap::new();

    for (index, stored) in existing.iter().enumerate() {
        let stored_id = clean_external_id(stored.external_transaction_id.as_deref());
        let legacy_id = clean_external_id(stored.external_id.as_deref());
        let mut stable_ids = HashSet::new();
        if let Some(id) = stored_id.filter(|id| is_bank_stable_key(id)) {
            stable_ids.insert(bounded_external_transaction_id(id));
        }
        if !stored_id.is_some_and(is_sidecar_key)
            && let Some(id) = legacy_id.filter(|id| is_bank_stable_key(id))
        {
            stable_ids.insert(bounded_external_transaction_id(id));
        }
        let fingerprint =
            content_fingerprint(stored.date, stored.amount, Some(&stored.description));
        for id in &stable_ids {
            by_stable
                .entry((id.clone(), fingerprint.clone()))
                .or_default()
                .push_back(index);
        }
        by_fingerprint
            .entry(fingerprint.clone())
            .or_default()
            .push_back(index);
        if stable_ids.is_empty() {
            overlap_by_fingerprint
                .entry(fingerprint)
                .or_default()
                .push_back(index);
        }
    }

    for (index, data) in fetched.iter().enumerate() {
        let Some(external_id) = clean_external_id(data.external_id.as_deref()) else {
            continue;
        };
        let (Some(date), Some(amount)) = (data.date, data.amount) else {
            continue;
        };
        let key = (
            bounded_external_transaction_id(external_id),
            content_fingerprint(date, amount, data.description.as_deref()),
        );
        if let Some(stored) = consume_next(by_stable.get_mut(&key), &consumed_existing) {
            consumed_existing[stored] = true;
            consumed_fetched[index] = true;
        }
    }

    for (index, data) in fetched.iter().enumerate() {
        if consumed_fetched[index] {
            continue;
        }
        let (Some(date), Some(amount)) = (data.date, data.amount) else {
            continue;
        };
        let fingerprint = content_fingerprint(date, amount, data.description.as_deref());
        let candidates = if clean_external_id(data.external_id.as_deref()).is_none() {
            by_fingerprint.get_mut(&fingerprint)
        } else {
            overlap_by_fingerprint.get_mut(&fingerprint)
        };
        if let Some(stored) = consume_next(candidates, &consumed_existing) {
            consumed_existing[stored] = true;
            consumed_fetched[index] = true;
        }
    }

    let mut to_insert = Vec::new();
    for (index, data) in fetched.iter().enumerate() {
        if consumed_fetched[index] {
            continue;
        }
        let Some(amount) = data.amount.filter(|a| fits_ledger_amount(*a)) else {
            tracing::warn!(
                "Skipping a transaction on account {} — amount does not fit the ledger",
                account.id
            );
            continue;
        };
        // Java hands a dateless row to saveAll, where the NOT NULL column rejects the batch.
        let Some(date) = data.date else {
            continue;
        };
        let mut row = NewTransaction::new(
            account.id,
            date,
            bounded_text(data.description.as_deref().unwrap_or_default()),
            normalize_amount(amount),
        );
        row.category = data.category.as_deref().map(|c| clip(c, CATEGORY_MAX));
        row.native_currency = currency_of(data, account).to_owned();
        row.external_transaction_id = Some(dedup_key(data));
        row.budget_neutral = account.account_type.is_investment();
        to_insert.push(row);
    }
    to_insert
}

fn consume_next(candidates: Option<&mut VecDeque<usize>>, consumed: &[bool]) -> Option<usize> {
    let candidates = candidates?;
    while let Some(candidate) = candidates.pop_front() {
        if !consumed[candidate] {
            return Some(candidate);
        }
    }
    None
}

fn is_bank_stable_key(key: &str) -> bool {
    !key.starts_with(FINGERPRINT_PREFIX) && !is_sidecar_key(key)
}

fn is_sidecar_key(key: &str) -> bool {
    key.starts_with("bourso:") || key.starts_with("revolut:")
}

fn clean_external_id(id: Option<&str>) -> Option<&str> {
    id.filter(|id| !java_is_blank(id)).map(java_trim)
}

fn bounded_external_transaction_id(id: &str) -> String {
    if id.chars().count() <= EXTERNAL_TRANSACTION_ID_LIMIT {
        id.to_owned()
    } else {
        format!("{HASHED_ID_PREFIX}{}", sha256_hex(id))
    }
}

fn normalize_amount(amount: Decimal) -> Decimal {
    set_scale(amount, AMOUNT_SCALE, HALF_UP)
}

/// BigDecimal.stripTrailingZeros().toPlainString() of the 8-place amount.
fn canonical_amount(amount: Decimal) -> String {
    normalize_amount(amount).normalize().to_string()
}

fn bounded_text(text: &str) -> String {
    text.chars().take(TEXT_LIMIT).collect()
}

fn content_fingerprint(date: NaiveDate, amount: Decimal, description: Option<&str>) -> Fingerprint {
    let bounded = bounded_text(description.unwrap_or_default());
    Fingerprint {
        date,
        amount: canonical_amount(amount),
        description: collapse_whitespace(java_trim(&bounded)).to_lowercase(),
    }
}

/// Pattern.compile("\\s+").matcher(s).replaceAll(" "), with Java's ASCII \s.
pub(crate) fn collapse_whitespace(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut in_space = false;
    for c in value.chars() {
        if matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r') {
            if !in_space {
                out.push(' ');
            }
            in_space = true;
        } else {
            out.push(c);
            in_space = false;
        }
    }
    out
}

/// The stored dedup key: the bank's id (hashed past 128 characters), else a content fingerprint.
pub fn dedup_key(data: &TransactionData) -> String {
    if let Some(id) = clean_external_id(data.external_id.as_deref()) {
        return bounded_external_transaction_id(id);
    }
    let canonical = format!(
        "{}|{}|{}",
        data.date
            .map(|d| d.to_string())
            .unwrap_or_else(|| "null".to_owned()),
        data.amount.map(canonical_amount).unwrap_or_default(),
        data.description
            .as_deref()
            .map(java_trim)
            .unwrap_or_default()
    );
    format!("{FINGERPRINT_PREFIX}{}", sha256_hex(&canonical))
}

/// Whether the amount fits numeric(20, 8): strictly under 10^12 in absolute value.
pub fn fits_ledger_amount(value: Decimal) -> bool {
    value.abs() < Decimal::from(1_000_000_000_000_i64)
}

/// Cuts to max UTF-16 units without splitting a surrogate pair, as the Java clip does.
pub fn clip(value: &str, max: usize) -> String {
    let mut units = 0;
    let mut out = String::new();
    for c in value.chars() {
        let len = c.len_utf16();
        if units + len > max {
            break;
        }
        units += len;
        out.push(c);
    }
    out
}

pub(crate) fn sha256_hex(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}

fn currency_of<'a>(data: &'a TransactionData, account: &'a Account) -> &'a str {
    if let Some(c) = data.currency.as_deref().filter(|c| !java_is_blank(c)) {
        return c;
    }
    if !java_is_blank(&account.currency) {
        return &account.currency;
    }
    DEFAULT_CURRENCY
}
