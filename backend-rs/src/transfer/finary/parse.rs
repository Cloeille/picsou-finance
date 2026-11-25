//! FinaryImportService.parseXlsx: the account sheets and the Transactions sheet of a Finary
//! export, row by row, a malformed row skipped as Java skips it.

use std::collections::BTreeMap;

use chrono::NaiveDate;
use rust_decimal::Decimal;

use super::java_double::JavaDouble;
use super::xlsx::{Cell, Workbook};
use crate::ledger::sync_values::{java_is_blank, java_trim};

pub const KNOWN_CATEGORIES: [&str; 10] = [
    "Checkings",
    "Savings",
    "Investments",
    "Real Estate",
    "Cryptos",
    "Fonds Euro",
    "Commodities",
    "Credits",
    "Other Assets",
    "Startups",
];

#[derive(Debug, Clone, PartialEq)]
pub struct ParsedAccount {
    pub name: String,
    pub institution: String,
    pub category: &'static str,
    pub balance: JavaDouble,
    pub currency: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ParsedTransaction {
    pub account_name: String,
    pub date: NaiveDate,
    pub description: String,
    pub amount: Decimal,
    pub kind: String,
    pub category: String,
    pub currency: String,
}

#[derive(Debug, Default)]
pub struct ParsedData {
    pub accounts: Vec<ParsedAccount>,
    pub transactions: Vec<ParsedTransaction>,
}

/// Reading the cell threw in POI: the row is skipped.
struct Throws;

type Row = BTreeMap<u32, Cell>;

/// cellString: a string cell's text, a numeric cell as (long) value, anything else null.
fn cell_string(row: &Row, col: u32) -> Result<Option<String>, Throws> {
    match row.get(&col) {
        Some(Cell::Text(Some(text))) => Ok(Some(text.clone())),
        Some(Cell::Number(Some(number))) => Ok(Some(number.as_long().to_string())),
        Some(Cell::Text(None) | Cell::Number(None)) => Err(Throws),
        Some(Cell::Other) | None => Ok(None),
    }
}

/// cellNumeric: a numeric cell as BigDecimal.valueOf(double), anything else null.
fn cell_numeric(row: &Row, col: u32) -> Result<Option<JavaDouble>, Throws> {
    match row.get(&col) {
        Some(Cell::Number(Some(number))) => Ok(Some(number.clone())),
        Some(Cell::Number(None)) => Err(Throws),
        _ => Ok(None),
    }
}

fn trimmed_or(value: Option<String>, fallback: &str) -> String {
    value.map_or_else(|| fallback.to_owned(), |v| java_trim(&v).to_owned())
}

/// LocalDate.parse(text, "yyyy-MM-dd") with the SMART resolver: a day past the month's end
/// (up to 31) is moved back to its last day.
pub fn parse_date(text: &str) -> Option<NaiveDate> {
    let b = text.as_bytes();
    if b.len() != 10 || b[4] != b'-' || b[7] != b'-' {
        return None;
    }
    let digits = |r: std::ops::Range<usize>| -> Option<u32> {
        let s = text.get(r)?;
        s.bytes()
            .all(|c| c.is_ascii_digit())
            .then(|| s.parse().ok())?
    };
    let (year, month, day) = (digits(0..4)?, digits(5..7)?, digits(8..10)?);
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return None;
    }
    let year = i32::try_from(year).ok()?;
    (0..4).find_map(|back| NaiveDate::from_ymd_opt(year, month, day.checked_sub(back)?))
}

fn account_row(row: &Row, category: &'static str) -> Result<Option<ParsedAccount>, Throws> {
    let name = cell_string(row, 0)?;
    let institution = cell_string(row, 1)?;
    let balance = cell_numeric(row, 2)?;
    let currency = cell_string(row, 3)?;
    Ok(match (name, balance) {
        (Some(name), Some(balance)) if !java_is_blank(&name) => Some(ParsedAccount {
            name: java_trim(&name).to_owned(),
            institution: trimmed_or(institution, ""),
            category,
            balance,
            currency: trimmed_or(currency, "EUR"),
        }),
        _ => None,
    })
}

fn transaction_row(row: &Row) -> Result<Option<ParsedTransaction>, Throws> {
    let category = cell_string(row, 0)?;
    let date = cell_string(row, 1)?;
    let name = cell_string(row, 2)?;
    let amount = cell_numeric(row, 3)?;
    let kind = cell_string(row, 4)?;
    let account_name = cell_string(row, 5)?;
    cell_string(row, 6)?;
    let currency = cell_string(row, 7)?;
    let (Some(date), Some(amount), Some(account_name)) = (date, amount, account_name) else {
        return Ok(None);
    };
    let Some(date) = parse_date(java_trim(&date)) else {
        tracing::warn!("Skipping transaction with unparseable date");
        return Ok(None);
    };
    Ok(Some(ParsedTransaction {
        account_name: java_trim(&account_name).to_owned(),
        date,
        description: trimmed_or(name, ""),
        amount: amount.value,
        kind: trimmed_or(kind, ""),
        category: trimmed_or(category, ""),
        currency: trimmed_or(currency, "EUR"),
    }))
}

pub fn parse(workbook: &Workbook) -> ParsedData {
    let mut data = ParsedData::default();
    for category in KNOWN_CATEGORIES {
        let Some(sheet) = workbook.sheet(category) else {
            continue;
        };
        for i in 1..=sheet.last_row() {
            let Some(row) = u32::try_from(i).ok().and_then(|i| sheet.row(i)) else {
                continue;
            };
            match account_row(row, category) {
                Ok(Some(account)) => data.accounts.push(account),
                Ok(None) => {}
                Err(Throws) => tracing::warn!("Skipping malformed account row in {category}"),
            }
        }
    }
    if let Some(sheet) = workbook.sheet("Transactions") {
        for i in 1..=sheet.last_row() {
            let Some(row) = u32::try_from(i).ok().and_then(|i| sheet.row(i)) else {
                continue;
            };
            match transaction_row(row) {
                Ok(Some(tx)) => data.transactions.push(tx),
                Ok(None) => {}
                Err(Throws) => tracing::warn!("Skipping malformed transaction row"),
            }
        }
    }
    data
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_dates_as_the_smart_resolver() {
        let d = |y, m, d| NaiveDate::from_ymd_opt(y, m, d);
        assert_eq!(parse_date("2024-03-05"), d(2024, 3, 5));
        assert_eq!(parse_date("2023-02-30"), d(2023, 2, 28));
        assert_eq!(parse_date("2024-02-31"), d(2024, 2, 29));
        assert_eq!(parse_date("2024-04-31"), d(2024, 4, 30));
        assert_eq!(parse_date("2024-02-32"), None);
        assert_eq!(parse_date("2024-13-01"), None);
        assert_eq!(parse_date("2024/01/01"), None);
        assert_eq!(parse_date("45000"), None);
        assert_eq!(parse_date("2024-1-01"), None);
    }
}
