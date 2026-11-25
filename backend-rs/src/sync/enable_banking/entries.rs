//! The mapping of Enable Banking transaction entries to BankConnectorPort.TransactionData.

use chrono::NaiveDate;
use serde::Deserialize;

use super::wire::{lenient_string, parse_big_decimal, truncate_utf16};
use crate::ledger::bank_import::TransactionData;
use crate::ledger::sync_values::{java_is_blank, java_trim};

/// transaction.description is VARCHAR(255) and category VARCHAR(100).
const MAX_DESCRIPTION_LENGTH: usize = 255;
const MAX_CATEGORY_LENGTH: usize = 100;
/// PSD2 entry statuses: only a booked entry is final enough to store.
const STATUS_BOOKED: &str = "BOOK";
const INDICATOR_DEBIT: &str = "DBIT";

#[derive(Debug, Clone, Default, Deserialize)]
pub struct TransactionsResponse {
    pub transactions: Option<Vec<Option<TransactionItem>>>,
    pub continuation_key: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct TransactionItem {
    pub entry_reference: Option<String>,
    pub transaction_id: Option<String>,
    pub transaction_amount: Option<TransactionAmount>,
    pub credit_debit_indicator: Option<String>,
    pub status: Option<String>,
    pub booking_date: Option<String>,
    pub value_date: Option<String>,
    pub transaction_date: Option<String>,
    pub remittance_information: Option<Vec<Option<String>>>,
    pub creditor: Option<Party>,
    pub debtor: Option<Party>,
    pub bank_transaction_code: Option<BankTransactionCode>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct TransactionAmount {
    #[serde(default, deserialize_with = "lenient_string")]
    pub amount: Option<String>,
    pub currency: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Party {
    pub name: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct BankTransactionCode {
    pub description: Option<String>,
}

/// Booked entries with a usable date and amount; pending ones are skipped (they carry no stable
/// reference and come back booked once settled).
pub fn to_transactions(items: Option<Vec<Option<TransactionItem>>>) -> Vec<TransactionData> {
    let mut result = Vec::new();
    for item in items.unwrap_or_default().into_iter().flatten() {
        if item
            .status
            .as_deref()
            .is_some_and(|s| !s.eq_ignore_ascii_case(STATUS_BOOKED))
        {
            continue;
        }
        let (Some(date), Some(amount)) = (transaction_date(&item), signed_amount(&item)) else {
            tracing::debug!("Skipping an Enable Banking entry without a usable date or amount");
            continue;
        };
        result.push(TransactionData {
            external_id: external_id(&item),
            date: Some(date),
            description: Some(truncate_utf16(&describe(&item), MAX_DESCRIPTION_LENGTH)),
            amount: Some(amount),
            currency: item
                .transaction_amount
                .as_ref()
                .and_then(|a| a.currency.clone()),
            category: category(&item).map(|c| truncate_utf16(&c, MAX_CATEGORY_LENGTH)),
        });
    }
    result
}

/// booking_date matches the balance the account shows; the other two are fallbacks.
fn transaction_date(item: &TransactionItem) -> Option<NaiveDate> {
    [&item.booking_date, &item.value_date, &item.transaction_date]
        .into_iter()
        .flatten()
        .filter(|raw| !java_is_blank(raw))
        .find_map(|raw| NaiveDate::parse_from_str(java_trim(raw), "%Y-%m-%d").ok())
}

/// Negative is money out. The magnitude is taken before the indicator applies: some ASPSPs sign
/// the amount string as well as the indicator.
fn signed_amount(item: &TransactionItem) -> Option<rust_decimal::Decimal> {
    let raw = item.transaction_amount.as_ref()?.amount.as_deref()?;
    let magnitude = parse_big_decimal(java_trim(raw))?.abs();
    Some(if is_debit(item) {
        -magnitude
    } else {
        magnitude
    })
}

fn is_debit(item: &TransactionItem) -> bool {
    item.credit_debit_indicator
        .as_deref()
        .is_some_and(|i| i.eq_ignore_ascii_case(INDICATOR_DEBIT))
}

/// entry_reference first: it is the ASPSP's stable ledger reference.
fn external_id(item: &TransactionItem) -> Option<String> {
    [&item.entry_reference, &item.transaction_id]
        .into_iter()
        .flatten()
        .find(|v| !java_is_blank(v))
        .map(|v| java_trim(v).to_owned())
}

/// What the holder wrote, then the counterparty, then the bank's own category: never blank.
fn describe(item: &TransactionItem) -> String {
    if let Some(lines) = &item.remittance_information {
        let joined = lines
            .iter()
            .flatten()
            .filter(|l| !java_is_blank(l))
            .map(|l| java_trim(l))
            .collect::<Vec<_>>()
            .join(" ");
        if !java_is_blank(&joined) {
            return joined;
        }
    }
    let counterparty = if is_debit(item) {
        &item.creditor
    } else {
        &item.debtor
    };
    if let Some(name) = counterparty.as_ref().and_then(|p| p.name.as_deref())
        && !java_is_blank(name)
    {
        return java_trim(name).to_owned();
    }
    category(item).unwrap_or_else(|| "Transaction".to_owned())
}

fn category(item: &TransactionItem) -> Option<String> {
    item.bank_transaction_code
        .as_ref()
        .and_then(|c| c.description.as_deref())
        .filter(|d| !java_is_blank(d))
        .map(|d| java_trim(d).to_owned())
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    fn map(items: serde_json::Value) -> Vec<TransactionData> {
        to_transactions(serde_json::from_value(items).unwrap())
    }

    #[test]
    fn the_indicator_signs_the_magnitude_whatever_the_amount_string_says() {
        let rows = map(json!([
            {"transaction_amount": {"amount": "-12.34", "currency": "EUR"}, "credit_debit_indicator": "DBIT", "booking_date": "2026-01-02"},
            {"transaction_amount": {"amount": "-5.00"}, "credit_debit_indicator": "CRDT", "booking_date": "2026-01-02"}
        ]));
        assert_eq!(rows[0].amount, Some("-12.34".parse().unwrap()));
        assert_eq!(rows[1].amount, Some("5.00".parse().unwrap()));
        assert_eq!(rows[0].description.as_deref(), Some("Transaction"));
    }

    #[test]
    fn unusable_entries_are_dropped_and_ids_prefer_the_entry_reference() {
        let rows = map(json!([
            {"transaction_amount": {"amount": "x"}, "booking_date": "2026-01-02"},
            {"transaction_amount": {"amount": "1"}, "booking_date": "02/01/2026"},
            {"transaction_amount": {"amount": "1"}, "status": "PDNG", "booking_date": "2026-01-02"},
            null,
            {"entry_reference": " ", "transaction_id": " T9 ", "transaction_amount": {"amount": "1"},
             "booking_date": "bad", "value_date": "2026-01-03",
             "bank_transaction_code": {"description": " Virement "}}
        ]));
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].external_id.as_deref(), Some("T9"));
        assert_eq!(rows[0].date, NaiveDate::from_ymd_opt(2026, 1, 3));
        assert_eq!(rows[0].description.as_deref(), Some("Virement"));
        assert_eq!(rows[0].category.as_deref(), Some("Virement"));
    }

    #[test]
    fn the_counterparty_is_the_side_that_is_not_the_holder() {
        let rows = map(json!([
            {"transaction_amount": {"amount": "1"}, "credit_debit_indicator": "DBIT", "booking_date": "2026-01-02",
             "creditor": {"name": "Shop"}, "debtor": {"name": "Me"}, "remittance_information": [" ", null]}
        ]));
        assert_eq!(rows[0].description.as_deref(), Some("Shop"));
    }
}
