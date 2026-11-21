//! SharedSidecarTransactionImportService: the EUR ledger import of the bank sidecars (Bnp,
//! BoursoBank, Revolut). Keys are "prefix:sourceId" (or a synthetic content key); a legacy
//! row of the same content is kept as it is, its category included, instead of a new row.

use std::collections::HashSet;

use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::bank_import::{collapse_whitespace, sha256_hex};
use super::categorize::{self, CategorizationContext, TRANSFER_SLUG};
use super::decimal::{HALF_UP, set_scale};
use super::model::{Account, CategoryKind, NewTransaction, Transaction};
use super::sidecar::SidecarTransaction;
use super::sync_values::{java_is_blank, java_trim};
use super::transactions;

const EUR: &str = "EUR";
const KEY_LIMIT: usize = 128;
const TEXT_LIMIT: usize = 255;
const AMOUNT_SCALE: u32 = 8;
const EXTERNAL_ID_LIMIT: usize = 255;

/// Imports the sidecar rows of an EUR account that are not stored yet; returns how many.
pub async fn import(
    conn: &mut PgConnection,
    account: &Account,
    rows: &[SidecarTransaction],
    ctx: &CategorizationContext,
    provider_key_prefix: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<usize> {
    if rows.is_empty() || currency_of(None, account) != EUR {
        return Ok(0);
    }
    let mut snapshot_ids = HashSet::new();
    for row in rows {
        if let Some(id) = clean_id(row.external_id.as_deref()) {
            snapshot_ids.insert(id.to_owned());
            snapshot_ids.insert(bounded_external_id(id));
        }
    }
    let existing = transactions::find_synced_by_account(&mut *conn, account.id, None).await?;
    let mut known_keys: HashSet<String> = HashSet::new();
    let mut legacy_rows: Vec<&Transaction> = Vec::new();
    for row in &existing {
        add_if_present(&mut known_keys, row.external_id.as_deref());
        add_if_present(&mut known_keys, row.external_transaction_id.as_deref());
        if !row
            .external_transaction_id
            .as_deref()
            .is_some_and(is_sidecar_key)
        {
            legacy_rows.push(row);
        }
    }
    let mut consumed_legacy: HashSet<usize> = legacy_rows
        .iter()
        .enumerate()
        .filter(|(_, legacy)| {
            legacy
                .external_id
                .as_ref()
                .is_some_and(|id| snapshot_ids.contains(id))
                || legacy
                    .external_transaction_id
                    .as_ref()
                    .is_some_and(|id| snapshot_ids.contains(id))
        })
        .map(|(i, _)| i)
        .collect();

    let mut batch_keys = HashSet::new();
    let mut inserted = 0;
    for (index, data) in rows.iter().enumerate() {
        let (Some(date), Some(amount)) = (data.date, data.amount) else {
            continue;
        };
        // The budget ledger is EUR-only: a foreign exchange leg listed on an EUR pocket would
        // inflate every flow view (#251).
        let currency = currency_of(Some(data), account);
        if currency != EUR {
            continue;
        }
        let source_id = clean_id(data.external_id.as_deref());
        let source_key = match source_id {
            Some(id) => id.to_owned(),
            None => synthetic_key(data, rows, index),
        };
        let external_id = bounded_external_id(source_id.unwrap_or(&source_key));
        let mut stored_key = format!("{provider_key_prefix}:{source_key}");
        if stored_key.encode_utf16().count() > KEY_LIMIT {
            stored_key = format!("{provider_key_prefix}:{}", sha256_hex(&source_key));
        }
        if !batch_keys.insert(stored_key.clone())
            || known_keys.contains(&external_id)
            || known_keys.contains(&stored_key)
        {
            continue;
        }
        if let Some(overlap) =
            find_legacy_overlap(&legacy_rows, &consumed_legacy, date, amount, data)
        {
            consumed_legacy.insert(overlap);
            continue;
        }
        let mut row = NewTransaction::new(
            account.id,
            date,
            bounded_text(data.description.as_deref().unwrap_or_default()),
            normalize_amount(amount),
        );
        row.counterparty = data.counterparty.as_deref().map(bounded_text);
        row.external_id = Some(external_id.clone());
        row.external_transaction_id = Some(stored_key.clone());
        row.native_currency = currency;
        categorize::auto_categorize(&mut row, ctx);
        if provider_key_prefix == "revolut"
            && data
                .kind
                .as_deref()
                .is_some_and(|k| k.eq_ignore_ascii_case("TRANSFER"))
            && let Some(transfer) = ctx
                .categories_by_slug
                .get(TRANSFER_SLUG)
                .filter(|c| c.kind == CategoryKind::Transfer)
        {
            row.category_id = Some(transfer.id);
        }
        transactions::insert(&mut *conn, &row, now).await?;
        known_keys.insert(external_id);
        known_keys.insert(stored_key);
        inserted += 1;
    }
    Ok(inserted)
}

fn find_legacy_overlap(
    legacy_rows: &[&Transaction],
    consumed: &HashSet<usize>,
    date: NaiveDate,
    amount: Decimal,
    incoming: &SidecarTransaction,
) -> Option<usize> {
    let description = normalize_description(incoming.description.as_deref());
    let amount = normalize_amount(amount);
    legacy_rows.iter().enumerate().position(|(i, old)| {
        !consumed.contains(&i)
            && old.date == date
            && normalize_amount(old.amount) == amount
            && normalize_description(Some(&old.description)) == description
    })
}

fn currency_of(data: Option<&SidecarTransaction>, account: &Account) -> String {
    let currency = data
        .and_then(|d| d.currency.as_deref())
        .filter(|c| !java_is_blank(c))
        .unwrap_or(&account.currency);
    if java_is_blank(currency) {
        EUR.to_owned()
    } else {
        java_trim(currency).to_uppercase()
    }
}

fn is_sidecar_key(key: &str) -> bool {
    key.starts_with("bourso:") || key.starts_with("revolut:")
}

fn normalize_description(description: Option<&str>) -> String {
    let bounded = bounded_text(description.unwrap_or_default());
    collapse_whitespace(java_trim(&bounded)).to_lowercase()
}

fn normalize_amount(amount: Decimal) -> Decimal {
    set_scale(amount, AMOUNT_SCALE, HALF_UP)
}

fn clean_id(id: Option<&str>) -> Option<&str> {
    id.filter(|id| !java_is_blank(id))
}

fn bounded_external_id(id: &str) -> String {
    if id.chars().count() <= EXTERNAL_ID_LIMIT {
        id.to_owned()
    } else {
        sha256_hex(id)
    }
}

fn bounded_text(value: &str) -> String {
    value.chars().take(TEXT_LIMIT).collect()
}

fn add_if_present(target: &mut HashSet<String>, key: Option<&str>) {
    if let Some(key) = key.filter(|k| !java_is_blank(k)) {
        target.insert(key.to_owned());
    }
}

/// A content key for a row without an id, numbered by how many identical id-less rows precede
/// it in the batch, so two real identical payments stay two rows.
fn synthetic_key(row: &SidecarTransaction, batch: &[SidecarTransaction], index: usize) -> String {
    let (Some(date), Some(amount)) = (row.date, row.amount) else {
        return String::new();
    };
    let amount = normalize_amount(amount);
    let description = normalize_description(row.description.as_deref());
    let fingerprint = sha256_hex(&format!("{date}|{}|{description}", amount.normalize()));
    let occurrence = batch[..index]
        .iter()
        .filter(|other| {
            clean_id(other.external_id.as_deref()).is_none()
                && other.date == Some(date)
                && other.amount.is_some_and(|a| normalize_amount(a) == amount)
                && normalize_description(other.description.as_deref()) == description
        })
        .count();
    format!("missing:{fingerprint}:{occurrence}")
}
