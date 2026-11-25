//! Cash lines of a bank or card statement (LedgerRowMapper, and the cash half of
//! TransactionImportService): one row to an unsaved DEPOSIT or WITHDRAWAL, the header guesses of
//! a statement, and the one-for-one removal of the lines an account already holds.

use std::collections::HashMap;

use rust_decimal::Decimal;

use super::csv::{self, CsvDialect};
use super::{ColumnMapping, cell};
use chrono::NaiveDate;

use crate::ledger::decimal::HALF_UP;
use crate::ledger::model::{NewTransaction, TransactionType};
use crate::ledger::sync_values::{java_is_blank, java_trim};
use crate::sync::simplefin::currency;

const DESCRIPTION_MAX: usize = 255;
const PLACEHOLDER_LABEL: &str = "CSV import";
/// `amount` is NUMERIC(20,8): 8 decimal places and 12 integer digits. Rounding to 8 decimals
/// before checking keeps a value like `999999999999.999999995` from overflowing PostgreSQL.
const LEDGER_SCALE: u32 = 8;
const LEDGER_LIMIT: Decimal = Decimal::from_parts(0xD4A5_1000, 0xE8, 0, false, 0);

/// Java's `\s+` replaced by one space, then trimmed: ASCII whitespace only.
fn collapse(raw: &str) -> String {
    let words: Vec<&str> = raw
        .split([' ', '\t', '\n', '\x0B', '\x0C', '\r'])
        .filter(|w| !w.is_empty())
        .collect();
    java_trim(&words.join(" ")).to_owned()
}

fn description(raw: Option<&str>) -> String {
    match raw {
        Some(raw) if !java_is_blank(raw) => collapse(raw).chars().take(DESCRIPTION_MAX).collect(),
        _ => PLACEHOLDER_LABEL.to_owned(),
    }
}

/// An ISO code from the cell, else the account's currency: a symbol or a word would break FX
/// conversion, and a long one would fail the commit.
fn currency_of(raw: Option<&str>, fallback: &str) -> String {
    raw.filter(|r| !java_is_blank(r))
        .map(|r| java_trim(r).to_uppercase())
        .filter(|code| currency::is_known(code))
        .unwrap_or_else(|| fallback.to_owned())
}

fn decimal_cell(
    raw: Option<&str>,
    dialect: &CsvDialect,
    field: &str,
) -> Result<Option<Decimal>, String> {
    csv::parse_decimal(raw, dialect.decimal)
        .map_err(|_| format!("Invalid {field} '{}'", raw.unwrap_or("null")))
}

fn resolve_amount(
    row: &[String],
    mapping: &ColumnMapping,
    dialect: &CsvDialect,
) -> Result<Decimal, String> {
    const TOO_LARGE: &str = "Amount is too large";
    let amount = match decimal_cell(cell(row, mapping.amount).as_deref(), dialect, "amount")? {
        Some(amount) => amount,
        None => {
            let debit = decimal_cell(cell(row, mapping.debit).as_deref(), dialect, "debit")?;
            let credit = decimal_cell(cell(row, mapping.credit).as_deref(), dialect, "credit")?;
            if debit.is_none() && credit.is_none() {
                return Err("Missing amount (or debit/credit)".into());
            }
            credit
                .unwrap_or_default()
                .abs()
                .checked_sub(debit.unwrap_or_default().abs())
                .ok_or(TOO_LARGE)?
        }
    };
    let amount = amount.round_dp_with_strategy(LEDGER_SCALE, HALF_UP);
    if amount.is_zero() {
        return Err("Amount is zero".into());
    }
    if amount.abs() >= LEDGER_LIMIT {
        return Err(TOO_LARGE.into());
    }
    Ok(amount)
}

/// LedgerRowMapper.map: a signed amount, DEPOSIT when money comes in and WITHDRAWAL when it goes
/// out, the shape a hand-typed line has. A debit is an outflow whatever sign the bank printed;
/// `invert_sign` flips the result for exports that list purchases as positive figures.
pub fn map_row(
    row: &[String],
    mapping: &ColumnMapping,
    dialect: &CsvDialect,
    invert_sign: bool,
    account: (i64, &str),
) -> Result<NewTransaction, String> {
    let date_raw = cell(row, mapping.date);
    let date = csv::parse_date(date_raw.as_deref(), &dialect.date_format)
        .map_err(|_| format!("Invalid date '{}'", date_raw.as_deref().unwrap_or("null")))?
        .ok_or("Missing date")?;
    let mut amount = resolve_amount(row, mapping, dialect)?;
    if invert_sign {
        amount = -amount;
    }
    let (account_id, account_currency) = account;
    let mut tx = NewTransaction::new(
        account_id,
        date,
        description(cell(row, mapping.description).as_deref()),
        amount,
    );
    tx.tx_type = Some(if amount.is_sign_positive() {
        TransactionType::Deposit
    } else {
        TransactionType::Withdrawal
    });
    tx.is_manual = true;
    tx.native_currency = currency_of(cell(row, mapping.currency).as_deref(), account_currency);
    Ok(tx)
}

/// guessCashMapping. Debit is tested before amount because French exports title it "Montant
/// débit"; "date de valeur" loses to the first date column, the booking date in every export
/// seen so far.
pub fn guess_mapping(header: &[String]) -> ColumnMapping {
    let mut m = ColumnMapping::default();
    let has = |h: &str, words: &[&str]| words.iter().any(|w| h.contains(w));
    for (i, cell) in header.iter().enumerate() {
        let i = Some(i as i64);
        let h = java_trim(&cell.to_lowercase()).to_owned();
        if h.contains("date") {
            if m.date.is_none() {
                m.date = i;
            }
        } else if m.debit.is_none() && has(&h, &["débit", "debit", "sortie", "withdrawal"]) {
            m.debit = i;
        } else if m.credit.is_none() && has(&h, &["crédit", "credit", "entrée", "deposit"]) {
            m.credit = i;
        } else if m.currency.is_none() && has(&h, &["currency", "devise"]) {
            m.currency = i;
        } else if m.amount.is_none() && has(&h, &["amount", "montant"]) {
            m.amount = i;
        } else if m.description.is_none()
            && has(
                &h,
                &[
                    "libell",
                    "label",
                    "description",
                    "intitul",
                    "opération",
                    "operation",
                    "payee",
                    "bénéficiaire",
                ],
            )
        {
            m.description = i;
        }
    }
    if m.debit.is_some() || m.credit.is_some() {
        m.amount = None;
    }
    m
}

fn line_key(date: NaiveDate, amount: Decimal, label: &str) -> String {
    format!(
        "{date}|{}|{}",
        amount.normalize(),
        collapse(label).to_lowercase()
    )
}

/// The lines the account does not hold yet: same date, same amount, same label once case and
/// spacing are ignored. One for one: two identical coffees on one day in the file against one
/// already stored leave one to import, so a genuine repeat inside a statement is never lost.
pub fn without_held(
    mapped: Vec<NewTransaction>,
    existing: &[(NaiveDate, Decimal, String)],
) -> Vec<NewTransaction> {
    let mut held: HashMap<String, usize> = HashMap::new();
    for (date, amount, label) in existing {
        *held.entry(line_key(*date, *amount, label)).or_default() += 1;
    }
    mapped
        .into_iter()
        .filter(|tx| {
            let key = line_key(tx.date, tx.amount, &tx.description);
            match held.get_mut(&key) {
                Some(left) if *left > 0 => {
                    *left -= 1;
                    false
                }
                _ => true,
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::transfer::csv_import::csv::{DatePattern, DecimalStyle};

    fn french() -> CsvDialect {
        CsvDialect {
            delimiter: ';',
            decimal: DecimalStyle::Comma,
            date_format: DatePattern::compile("dd/MM/yyyy").unwrap(),
        }
    }

    fn account() -> (i64, &'static str) {
        (1, "EUR")
    }

    fn row(cells: &[&str]) -> Vec<String> {
        cells.iter().map(|c| (*c).to_owned()).collect()
    }

    fn signed() -> ColumnMapping {
        ColumnMapping {
            date: Some(0),
            description: Some(1),
            amount: Some(2),
            ..ColumnMapping::default()
        }
    }

    fn debit_credit() -> ColumnMapping {
        ColumnMapping {
            date: Some(0),
            description: Some(1),
            debit: Some(2),
            credit: Some(3),
            ..ColumnMapping::default()
        }
    }

    fn dec(text: &str) -> Decimal {
        text.parse().unwrap()
    }

    #[test]
    fn a_negative_signed_amount_is_a_withdrawal() {
        let tx = map_row(
            &row(&["02/11/2026", "CB CARREFOUR", "-45,30"]),
            &signed(),
            &french(),
            false,
            account(),
        )
        .unwrap();
        assert_eq!(tx.date, NaiveDate::from_ymd_opt(2026, 11, 2).unwrap());
        assert_eq!(tx.amount, dec("-45.30"));
        assert_eq!(tx.tx_type, Some(TransactionType::Withdrawal));
        assert_eq!(tx.description, "CB CARREFOUR");
        assert_eq!(tx.native_currency, "EUR");
        assert!(tx.is_manual);
    }

    #[test]
    fn a_debit_is_an_outflow_whatever_sign_the_bank_printed() {
        let unsigned = map_row(
            &row(&["02/11/2026", "PRLV EDF", "60,00", ""]),
            &debit_credit(),
            &french(),
            false,
            account(),
        )
        .unwrap();
        let printed_negative = map_row(
            &row(&["02/11/2026", "PRLV EDF", "-60,00", ""]),
            &debit_credit(),
            &french(),
            false,
            account(),
        )
        .unwrap();
        assert_eq!(unsigned.amount, dec("-60.00"));
        assert_eq!(printed_negative.amount, dec("-60.00"));
    }

    #[test]
    fn a_credit_is_a_deposit() {
        let tx = map_row(
            &row(&["03/11/2026", "VIR SALAIRE", "", "2 100,00"]),
            &debit_credit(),
            &french(),
            false,
            account(),
        )
        .unwrap();
        assert_eq!(tx.amount, dec("2100.00"));
        assert_eq!(tx.tx_type, Some(TransactionType::Deposit));
    }

    #[test]
    fn a_row_with_both_debit_and_credit_is_credit_minus_debit() {
        let tx = map_row(
            &row(&["03/11/2026", "REMBOURSEMENT", "10,00", "25,00"]),
            &debit_credit(),
            &french(),
            false,
            account(),
        )
        .unwrap();
        assert_eq!(tx.amount, dec("15.00"));
    }

    #[test]
    fn invert_sign_flips_the_amount() {
        let tx = map_row(
            &row(&["04/11/2026", "AMAZON", "19,99"]),
            &signed(),
            &french(),
            true,
            account(),
        )
        .unwrap();
        assert_eq!(tx.amount, dec("-19.99"));
        assert_eq!(tx.tx_type, Some(TransactionType::Withdrawal));
    }

    #[test]
    fn a_blank_label_gets_a_placeholder_and_a_long_one_is_cut_and_collapsed() {
        let blank = map_row(
            &row(&["04/11/2026", "  ", "-1,00"]),
            &signed(),
            &french(),
            false,
            account(),
        )
        .unwrap();
        assert_eq!(blank.description, "CSV import");

        let long = format!("CB   {}", "x".repeat(300));
        let cut = map_row(
            &row(&["04/11/2026", &long, "-1,00"]),
            &signed(),
            &french(),
            false,
            account(),
        )
        .unwrap();
        assert_eq!(cut.description.chars().count(), 255);
        assert!(cut.description.starts_with("CB x"));
    }

    #[test]
    fn the_currency_cell_keeps_an_iso_code_and_falls_back_on_a_symbol() {
        let mapping = ColumnMapping {
            currency: Some(3),
            ..signed()
        };
        let line = |currency: &str| {
            map_row(
                &row(&["04/11/2026", "X", "-1,00", currency]),
                &mapping,
                &french(),
                false,
                account(),
            )
            .unwrap()
            .native_currency
        };
        assert_eq!(line("usd"), "USD");
        assert_eq!(line("€"), "EUR");
        assert_eq!(line("euros"), "EUR");
        assert_eq!(line(""), "EUR");
    }

    #[test]
    fn bad_rows_report_a_user_safe_reason() {
        let fail = |cells: &[&str], mapping: &ColumnMapping| {
            map_row(&row(cells), mapping, &french(), false, account()).unwrap_err()
        };
        assert_eq!(
            fail(&["04/11/2026", "X", "-1000000000000,00"], &signed()),
            "Amount is too large"
        );
        assert_eq!(fail(&["", "X", "-1,00"], &signed()), "Missing date");
        assert_eq!(
            fail(&["32/11/2026", "X", "-1,00"], &signed()),
            "Invalid date '32/11/2026'"
        );
        assert_eq!(
            fail(&["04/11/2026", "X", "", ""], &debit_credit()),
            "Missing amount (or debit/credit)"
        );
        assert_eq!(
            fail(&["04/11/2026", "X", "0,00"], &signed()),
            "Amount is zero"
        );
        assert_eq!(
            fail(&["04/11/2026", "X", "abc"], &signed()),
            "Invalid amount 'abc'"
        );
        assert_eq!(
            fail(&["04/11/2026", "X", "", "abc"], &debit_credit()),
            "Invalid credit 'abc'"
        );
    }

    #[test]
    fn the_largest_accepted_figure_is_just_under_a_trillion() {
        let tx = map_row(
            &row(&["04/11/2026", "X", "999999999999,99"]),
            &signed(),
            &french(),
            false,
            account(),
        )
        .unwrap();
        assert_eq!(tx.amount, dec("999999999999.99"));
    }

    #[test]
    fn amounts_are_rounded_to_eight_decimals_before_zero_and_limit_checks() {
        let run = |raw: &str| {
            map_row(
                &row(&["04/11/2026", "X", raw]),
                &signed(),
                &french(),
                false,
                account(),
            )
        };
        assert_eq!(
            run("999999999999,999999995").unwrap_err(),
            "Amount is too large"
        );
        assert_eq!(
            run("-999999999999,999999995").unwrap_err(),
            "Amount is too large"
        );
        assert_eq!(
            run("999999999999,999999994").unwrap().amount,
            dec("999999999999.99999999")
        );
        assert_eq!(run("0,000000001").unwrap_err(), "Amount is zero");
        assert_eq!(run("-0,000000004").unwrap_err(), "Amount is zero");
        assert_eq!(run("0,000000005").unwrap().amount, dec("0.00000001"));
    }

    #[test]
    fn a_french_header_maps_label_debit_and_credit_and_clears_the_amount_guess() {
        let m = guess_mapping(&row(&[
            "Date",
            "Date de valeur",
            "Libellé",
            "Montant débit",
            "Montant crédit",
            "Montant",
        ]));
        assert_eq!(m.date, Some(0));
        assert_eq!(m.description, Some(2));
        assert_eq!(m.debit, Some(3));
        assert_eq!(m.credit, Some(4));
        assert_eq!(m.amount, None);
    }

    #[test]
    fn a_second_date_column_never_maps_to_debit_credit_or_description() {
        let card = guess_mapping(&row(&[
            "Date d'opération",
            "Date de débit",
            "Libellé",
            "Montant",
        ]));
        assert_eq!(card.date, Some(0));
        assert_eq!(card.debit, None);
        assert_eq!(card.credit, None);
        assert_eq!(card.description, Some(2));
        assert_eq!(card.amount, Some(3));

        let credit_date = guess_mapping(&row(&[
            "Date",
            "Date de crédit",
            "Date d'opération",
            "Libellé",
            "Montant",
        ]));
        assert_eq!(credit_date.date, Some(0));
        assert_eq!(credit_date.debit, None);
        assert_eq!(credit_date.credit, None);
        assert_eq!(credit_date.description, Some(3));
        assert_eq!(credit_date.amount, Some(4));
    }

    #[test]
    fn an_english_header_maps_a_signed_amount_and_a_currency() {
        let m = guess_mapping(&row(&["Date", "Description", "Amount", "Currency"]));
        assert_eq!(
            (m.date, m.description, m.amount, m.currency),
            (Some(0), Some(1), Some(2), Some(3))
        );
        assert_eq!((m.debit, m.credit), (None, None));
    }

    fn pending(date: (i32, u32, u32), label: &str, amount: &str) -> NewTransaction {
        NewTransaction::new(
            1,
            NaiveDate::from_ymd_opt(date.0, date.1, date.2).unwrap(),
            label,
            dec(amount),
        )
    }

    #[test]
    fn a_held_line_is_left_out_whatever_its_case_spacing_or_scale() {
        let held = (
            NaiveDate::from_ymd_opt(2026, 11, 2).unwrap(),
            dec("-45.3"),
            "CB  carrefour".to_owned(),
        );
        let kept = without_held(
            vec![
                pending((2026, 11, 2), "CB CARREFOUR", "-45.30"),
                pending((2026, 11, 3), "CB CARREFOUR", "-45.30"),
            ],
            &[held],
        );
        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].date, NaiveDate::from_ymd_opt(2026, 11, 3).unwrap());
    }

    #[test]
    fn matching_is_one_for_one_so_a_repeat_inside_a_file_is_kept() {
        let coffee = || pending((2026, 11, 4), "CB CAFE", "-2.50");
        assert_eq!(without_held(vec![coffee(), coffee()], &[]).len(), 2);

        let held = (
            NaiveDate::from_ymd_opt(2026, 11, 4).unwrap(),
            dec("-2.5"),
            "CB CAFE".to_owned(),
        );
        assert_eq!(without_held(vec![coffee(), coffee()], &[held]).len(), 1);
    }
}
