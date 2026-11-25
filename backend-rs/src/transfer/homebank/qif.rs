//! GNU HomeBank desktop QIF exports (HomeBankQifParser): bounded, uncompressed, one currency
//! chosen by the user, deterministic source ids from row content.

use std::collections::{HashMap, HashSet};

use chrono::NaiveDate;
use regex::Regex;
use rust_decimal::Decimal;

use super::currencies::is_java_currency;
use super::java_hash::JavaHashSet;
use super::source::{
    BigDec, ParsedHomeBankData, SourceAccount, SourceCategory, SourceTransaction, jlen, name_uuid,
};
use crate::ledger::sync_values::java_is_blank;

const MAX_ACCOUNTS: usize = 100;
const MAX_CATEGORIES: usize = 200;
const MAX_TRANSACTIONS: usize = 25_000;
const MAX_LINES: usize = 500_000;
const MAX_TEXT: usize = 255;
const MAX_ACCOUNT_CATEGORY_TEXT: usize = 100;
const NAMESPACE: &str = "8a26b45a-967b-5c12-89d1-a9d775f19dc4";
pub const AMBIGUOUS_DATES: &str =
    "QIF dates are ambiguous between day/month/year and month/day/year";
pub const UNSUPPORTED_DATES: &str =
    "QIF dates must all use one format: yyyy/MM/dd, dd/MM/yyyy or MM/dd/yyyy";
const INVALID: &str = "Invalid or unsupported QIF file";
const TRANSFER_LABEL: &str = "Transfer: ";

type Fail = String;

fn invalid() -> Fail {
    INVALID.to_owned()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Section {
    None,
    Account,
    Category,
    Transactions,
}

struct RawTransaction {
    account: String,
    date_text: String,
    amount: Decimal,
    payee: Option<String>,
    memo: Option<String>,
    category: Option<String>,
    transfer_target: Option<String>,
    splits: Vec<Split>,
}

struct Split {
    category: String,
    memo: Option<String>,
    amount: Decimal,
}

struct Normalized<'r> {
    source: &'r RawTransaction,
    date: NaiveDate,
    amount: Decimal,
    category: Option<String>,
    memo: Option<String>,
    transfer_target: Option<String>,
}

/// BufferedReader.readLine: lines end at \n, \r or \r\n; a final line needs no terminator.
fn java_lines(source: &str) -> impl Iterator<Item = &str> {
    let mut rest = source;
    std::iter::from_fn(move || {
        if rest.is_empty() {
            return None;
        }
        match rest.find(['\n', '\r']) {
            Some(at) => {
                let line = &rest[..at];
                let skip = if rest[at..].starts_with("\r\n") { 2 } else { 1 };
                rest = &rest[at + skip..];
                Some(line)
            }
            None => {
                let line = rest;
                rest = "";
                Some(line)
            }
        }
    })
}

/// Character.isLetter on the first UTF-16 unit: a supplementary character is not a letter.
fn starts_with_letter(line: &str) -> bool {
    static LETTER: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    let letter = LETTER.get_or_init(|| Regex::new(r"^\p{L}").expect("a valid pattern"));
    line.chars()
        .next()
        .is_some_and(|c| u32::from(c) <= 0xFFFF && letter.is_match(line))
}

fn tag(field: &str) -> char {
    field.chars().next().unwrap_or('\0')
}

fn value_of(field: &str) -> &str {
    let mut chars = field.chars();
    chars.next();
    chars.as_str()
}

pub fn parse(file: &[u8], currency: Option<&str>) -> Result<ParsedHomeBankData, Fail> {
    let currency = currency
        .filter(|c| {
            c.len() == 3 && c.bytes().all(|b| b.is_ascii_uppercase()) && is_java_currency(c)
        })
        .ok_or_else(invalid)?;
    let source = std::str::from_utf8(file).map_err(|_| invalid())?;
    let source = source.strip_prefix('\u{FEFF}').unwrap_or(source);

    let mut accounts: Vec<SourceAccount> = Vec::new();
    let mut explicit = JavaHashMap::default();
    let mut negative = JavaHashMap::default();
    let mut positive = JavaHashMap::default();
    let mut category_paths: HashSet<String> = HashSet::new();
    let mut raw: Vec<RawTransaction> = Vec::new();
    let mut normalized_rows = 0usize;
    let mut current_account: Option<String> = None;
    let mut section = Section::None;
    let mut account_fields: Option<Vec<&str>> = None;
    let mut category_fields: Option<Vec<&str>> = None;
    let mut transaction_fields: Option<Vec<&str>> = None;
    for (index, line) in java_lines(source).enumerate() {
        if index + 1 > MAX_LINES || jlen(line) > MAX_TEXT + 1 {
            return Err(invalid());
        }
        if line.is_empty() {
            continue;
        }
        if line.starts_with('!') {
            if category_fields.as_ref().is_some_and(Vec::is_empty) {
                category_fields = None;
            }
            if account_fields.is_some() || category_fields.is_some() || transaction_fields.is_some()
            {
                return Err(invalid());
            }
            match line {
                "!Account" => {
                    section = Section::Account;
                    account_fields = Some(Vec::new());
                }
                "!Type:Cat" => {
                    section = Section::Category;
                    category_fields = Some(Vec::new());
                }
                "!Type:Bank" | "!Type:Cash" | "!Type:CCard" => {
                    if current_account.is_none() {
                        return Err(invalid());
                    }
                    section = Section::Transactions;
                }
                _ => return Err(invalid()),
            }
            continue;
        }
        if line == "^" {
            if let Some(fields) = account_fields.take() {
                current_account = Some(add_account(&fields, &mut accounts, currency)?);
            } else if let Some(fields) = category_fields.take() {
                add_category_declaration(&fields, &mut explicit, &mut category_paths)?;
            } else if let Some(fields) = transaction_fields.take() {
                if normalized_rows >= MAX_TRANSACTIONS {
                    return Err(invalid());
                }
                let account = current_account.clone().ok_or_else(invalid)?;
                let transaction =
                    parse_transaction(&fields, account, MAX_TRANSACTIONS - normalized_rows)?;
                normalized_rows += transaction.splits.len().max(1);
                raw.push(transaction);
                if raw.len() > MAX_TRANSACTIONS {
                    return Err(invalid());
                }
            } else {
                return Err(invalid());
            }
            continue;
        }
        let opens_record = (section == Section::Transactions && transaction_fields.is_none())
            || (section == Section::Category && category_fields.is_none());
        if account_fields.is_none()
            && category_fields.is_none()
            && transaction_fields.is_none()
            && !opens_record
        {
            return Err(invalid());
        }
        if !starts_with_letter(line) && !line.starts_with('$') {
            return Err(invalid());
        }
        if section == Section::Category && category_fields.is_none() {
            category_fields = Some(Vec::new());
        }
        if section == Section::Transactions && transaction_fields.is_none() {
            transaction_fields = Some(Vec::new());
        }
        if let Some(fields) = account_fields
            .as_mut()
            .or(category_fields.as_mut())
            .or(transaction_fields.as_mut())
        {
            fields.push(line);
        }
    }
    if account_fields.is_some() || category_fields.is_some() || transaction_fields.is_some() {
        return Err(invalid());
    }
    if accounts.is_empty() {
        return Err(invalid());
    }

    let dates = resolve_dates(&raw)?;
    let mut normalized: Vec<Normalized<'_>> = Vec::new();
    for (transaction, date) in raw.iter().zip(dates) {
        if transaction.splits.is_empty() {
            let category = if transaction.transfer_target.is_none() {
                transaction.category.clone()
            } else {
                None
            };
            note_category(
                category.as_deref(),
                transaction.amount,
                &mut negative,
                &mut positive,
                &mut category_paths,
            )?;
            normalized.push(Normalized {
                source: transaction,
                date,
                amount: transaction.amount,
                category,
                memo: transaction.memo.clone(),
                transfer_target: transaction.transfer_target.clone(),
            });
        } else {
            let mut sum = Decimal::ZERO;
            for split in &transaction.splits {
                sum += split.amount;
                normalized.push(Normalized {
                    source: transaction,
                    date,
                    amount: split.amount,
                    category: Some(split.category.clone()),
                    memo: combined_memo(transaction.memo.as_deref(), split.memo.as_deref())?,
                    transfer_target: None,
                });
                note_category(
                    Some(&split.category),
                    split.amount,
                    &mut negative,
                    &mut positive,
                    &mut category_paths,
                )?;
            }
            if sum != transaction.amount
                || transaction.category.is_some()
                || transaction.transfer_target.is_some()
            {
                return Err(invalid());
            }
        }
    }
    if normalized.len() > MAX_TRANSACTIONS {
        return Err(invalid());
    }

    let categories = build_categories(&explicit, &negative, &positive)?;
    let transactions = build_transactions(&normalized, &accounts, &categories, currency);
    Ok(ParsedHomeBankData {
        accounts,
        categories: categories.into_iter().map(|(_, c)| c).collect(),
        transactions,
    })
}

/// A HashMap<String, _> keyed by path, iterated in Java's order.
#[derive(Default)]
struct JavaHashMap {
    keys: JavaHashSet,
    values: HashMap<String, bool>,
}

impl JavaHashMap {
    fn put_if_absent(&mut self, key: &str, value: bool) -> bool {
        if self.values.contains_key(key) {
            return false;
        }
        self.keys.add(key);
        self.values.insert(key.to_owned(), value);
        true
    }

    fn put(&mut self, key: &str, value: bool) {
        self.keys.add(key);
        self.values.insert(key.to_owned(), value);
    }

    fn get(&self, key: &str) -> Option<bool> {
        self.values.get(key).copied()
    }

    fn len(&self) -> usize {
        self.values.len()
    }
}

fn add_account(
    fields: &[&str],
    accounts: &mut Vec<SourceAccount>,
    currency: &str,
) -> Result<String, Fail> {
    if fields.iter().any(|f| !matches!(tag(f), 'N' | 'T')) {
        return Err(invalid());
    }
    let name = singleton(fields, 'N', true)?.ok_or_else(invalid)?;
    let account_type = singleton(fields, 'T', true)?.ok_or_else(invalid)?;
    let source_type = if account_type.eq_ignore_ascii_case("Bank") {
        "bank"
    } else if account_type.eq_ignore_ascii_case("Cash") {
        "cash"
    } else if account_type.eq_ignore_ascii_case("CCard") {
        "creditcard"
    } else {
        return Err(invalid());
    };
    if java_is_blank(name)
        || jlen(name) > MAX_ACCOUNT_CATEGORY_TEXT
        || accounts.iter().any(|a| a.name == name)
        || accounts.len() >= MAX_ACCOUNTS
    {
        return Err(invalid());
    }
    accounts.push(SourceAccount {
        id: stable_id(&format!("account:{name}")),
        name: name.to_owned(),
        institution: None,
        source_type: source_type.to_owned(),
        currency: currency.to_owned(),
        initial_balance: Decimal::ZERO,
        closed: false,
    });
    Ok(name.to_owned())
}

fn add_category_declaration(
    fields: &[&str],
    types: &mut JavaHashMap,
    paths: &mut HashSet<String>,
) -> Result<(), Fail> {
    if types.len() >= MAX_CATEGORIES {
        return Err(invalid());
    }
    let name = singleton(fields, 'N', true)?.ok_or_else(invalid)?;
    let mut income: Option<bool> = None;
    for field in fields {
        match tag(field) {
            'I' | 'E' => {
                if field.len() != 1 || income.is_some() {
                    return Err(invalid());
                }
                income = Some(tag(field) == 'I');
            }
            'N' | 'D' | 'T' => {}
            _ => return Err(invalid()),
        }
    }
    let income = income.ok_or_else(invalid)?;
    let path = validate_category(name)?;
    if !types.put_if_absent(&path, income) {
        return Err(invalid());
    }
    add_category_paths(&path, paths)
}

fn amount(text: &str) -> Result<Decimal, Fail> {
    let body = text.strip_prefix(['+', '-']).unwrap_or(text);
    let (int_part, frac_part) = body.split_once('.').unwrap_or((body, ""));
    let digits = |s: &str| s.bytes().all(|b| b.is_ascii_digit());
    let well_formed = digits(int_part)
        && digits(frac_part)
        && (!int_part.is_empty() || !frac_part.is_empty())
        && (body.contains('.') || !int_part.is_empty());
    if !well_formed || body == "." {
        return Err(invalid());
    }
    let value = BigDec::parse(text).ok_or_else(invalid)?;
    if value.precision() > 20 || value.scale > 8 || value.scale < -8 {
        return Err(invalid());
    }
    value.to_decimal().ok_or_else(invalid)
}

fn parse_transaction(
    fields: &[&str],
    account: String,
    max_normalized_rows: usize,
) -> Result<RawTransaction, Fail> {
    if fields.iter().any(|f| {
        !matches!(
            tag(f),
            'D' | 'T' | 'P' | 'M' | 'L' | 'C' | 'N' | 'S' | 'E' | '$'
        )
    }) {
        return Err(invalid());
    }
    let date_text = singleton(fields, 'D', true)?.ok_or_else(invalid)?;
    let amount_text = singleton(fields, 'T', true)?.ok_or_else(invalid)?;
    let payee = singleton(fields, 'P', false)?;
    let memo = singleton(fields, 'M', false)?;
    let mut category = singleton(fields, 'L', false)?.map(str::to_owned);
    // Cleared status (C) and check number (N) are deliberately ignored and excluded from identity.
    singleton(fields, 'C', false)?;
    singleton(fields, 'N', false)?;
    let amount = amount(amount_text)?;
    let mut transfer = None;
    if let Some(label) = category.as_deref()
        && label.starts_with('[')
        && label.ends_with(']')
        && label.len() >= 2
    {
        let target = &label[1..label.len() - 1];
        if java_is_blank(target) {
            return Err(invalid());
        }
        transfer = Some(target.to_owned());
        category = None;
    }
    let category = category.as_deref().map(validate_category).transpose()?;
    let splits = parse_splits(fields, max_normalized_rows)?;
    Ok(RawTransaction {
        account,
        date_text: date_text.to_owned(),
        amount,
        payee: payee.map(str::to_owned),
        memo: memo.map(str::to_owned),
        category,
        transfer_target: transfer,
        splits,
    })
}

fn parse_dmy(year: &str, month: &str, day: &str) -> Option<NaiveDate> {
    NaiveDate::from_ymd_opt(year.parse().ok()?, month.parse().ok()?, day.parse().ok()?)
}

/// The year-first and year-last shapes, the two separators equal (Java's \\2 backreference).
fn split_date(text: &str) -> Option<(bool, [&str; 3])> {
    static SHAPE: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    let shape = SHAPE.get_or_init(|| {
        Regex::new(r"^(?:([0-9]{4})([/.-])([0-9]{1,2})([/.-])([0-9]{1,2})|([0-9]{1,2})([/.-])([0-9]{1,2})([/.-])([0-9]{4}))$")
            .expect("a valid pattern")
    });
    let caps = shape.captures(text)?;
    let part = |i: usize| caps.get(i).map_or("", |m| m.as_str());
    if caps.get(1).is_some() {
        (part(2) == part(4)).then(|| (true, [part(1), part(3), part(5)]))
    } else {
        (part(7) == part(9)).then(|| (false, [part(6), part(8), part(10)]))
    }
}

/// Picks one date format for the whole file (yyyy/MM/dd, dd/MM/yyyy or MM/dd/yyyy) so that
/// day/month order is never guessed row by row. Messages are fixed: never echo file content.
fn resolve_dates(rows: &[RawTransaction]) -> Result<Vec<NaiveDate>, Fail> {
    let mut year_first = Vec::with_capacity(rows.len());
    let mut day_first = Vec::with_capacity(rows.len());
    let mut month_first = Vec::with_capacity(rows.len());
    let (mut year_first_ok, mut day_first_ok, mut month_first_ok) = (true, true, true);
    for row in rows {
        match split_date(&row.date_text) {
            Some((true, [year, month, day])) => {
                day_first_ok = false;
                month_first_ok = false;
                let date = parse_dmy(year, month, day);
                year_first_ok &= date.is_some();
                year_first.push(date);
            }
            Some((false, [first, second, year])) => {
                year_first_ok = false;
                let day = parse_dmy(year, second, first);
                let month = parse_dmy(year, first, second);
                day_first_ok &= day.is_some();
                month_first_ok &= month.is_some();
                day_first.push(day);
                month_first.push(month);
            }
            None => return Err(UNSUPPORTED_DATES.to_owned()),
        }
    }
    let all = |dates: Vec<Option<NaiveDate>>| dates.into_iter().flatten().collect();
    if rows.is_empty() || year_first_ok {
        return Ok(all(year_first));
    }
    if day_first_ok && month_first_ok {
        if day_first == month_first {
            return Ok(all(day_first));
        }
        return Err(AMBIGUOUS_DATES.to_owned());
    }
    if day_first_ok {
        return Ok(all(day_first));
    }
    if month_first_ok {
        return Ok(all(month_first));
    }
    Err(UNSUPPORTED_DATES.to_owned())
}

fn parse_splits(fields: &[&str], max_splits: usize) -> Result<Vec<Split>, Fail> {
    let mut splits = Vec::new();
    let mut split_category: Option<String> = None;
    let mut split_memo: Option<String> = None;
    let mut started = false;
    let mut has_amount = false;
    for field in fields {
        match tag(field) {
            'S' => {
                if started && !has_amount {
                    return Err(invalid());
                }
                split_category = Some(validate_category(required_value(field)?)?);
                split_memo = None;
                started = true;
                has_amount = false;
            }
            'E' => {
                if !started || has_amount || split_memo.is_some() {
                    return Err(invalid());
                }
                split_memo = Some(value_of(field).to_owned());
            }
            '$' => {
                if !started || has_amount || splits.len() >= max_splits {
                    return Err(invalid());
                }
                splits.push(Split {
                    category: split_category.clone().ok_or_else(invalid)?,
                    memo: split_memo.clone(),
                    amount: amount(required_value(field)?)?,
                });
                has_amount = true;
            }
            _ => {}
        }
    }
    if started && !has_amount {
        return Err(invalid());
    }
    Ok(splits)
}

fn build_categories(
    explicit: &JavaHashMap,
    negative: &JavaHashMap,
    positive: &JavaHashMap,
) -> Result<Vec<(String, SourceCategory)>, Fail> {
    let mut paths = JavaHashSet::with_initial(explicit.keys.iter_java());
    for key in negative.keys.iter_java() {
        paths.add(&key);
    }
    for key in positive.keys.iter_java() {
        paths.add(&key);
    }
    for path in paths.iter_java() {
        let mut parent = parent_of(&path);
        while let Some(p) = parent {
            paths.add(p);
            parent = parent_of(p);
        }
    }
    if paths.len() > MAX_CATEGORIES {
        return Err(invalid());
    }
    let mut sorted = paths.iter_java();
    sorted.sort_by_key(|path| depth(path));
    let mut result: Vec<(String, SourceCategory)> = Vec::with_capacity(sorted.len());
    let mut id_by_path: HashMap<String, String> = HashMap::new();
    for path in sorted {
        let id = stable_id(&format!("category:{path}"));
        let declared = explicit.get(&path);
        let inferred = declared.is_none();
        let income = declared.unwrap_or_else(|| negative.get(&path) != Some(true));
        let parent_id = parent_of(&path).and_then(|p| id_by_path.get(p).cloned());
        result.push((
            path.clone(),
            SourceCategory {
                id: id.clone(),
                name: leaf(&path).to_owned(),
                parent_id,
                income,
                kind_inferred: inferred,
            },
        ));
        id_by_path.insert(path, id);
    }
    Ok(result)
}

fn build_transactions(
    normalized: &[Normalized<'_>],
    accounts: &[SourceAccount],
    categories: &[(String, SourceCategory)],
    currency: &str,
) -> Vec<SourceTransaction> {
    let account_id = |name: &str| {
        accounts
            .iter()
            .find(|a| a.name == name)
            .map(|a| a.id.clone())
    };
    let mut occurrences: HashMap<String, usize> = HashMap::new();
    normalized
        .iter()
        .map(|row| {
            let transfer_id = row.transfer_target.as_deref().and_then(account_id);
            let category_id = row.category.as_deref().and_then(|path| {
                categories
                    .iter()
                    .find(|(p, _)| p == path)
                    .map(|(_, c)| c.id.clone())
            });
            // The fingerprint always uses the raw memo and target so ids never depend on the display notes.
            let fingerprint = identity(&[
                Some(&row.source.account),
                Some(&row.date.format("%Y-%m-%d").to_string()),
                Some(&row.amount.to_string()),
                row.source.payee.as_ref(),
                row.memo.as_ref(),
                row.category.as_ref(),
                row.transfer_target.as_ref(),
            ]);
            let counter = occurrences.entry(fingerprint.clone()).or_insert(0);
            let occurrence = *counter;
            *counter += 1;
            // A transfer to an account absent from this file is imported as a plain uncategorised row.
            let notes = match (&row.transfer_target, &transfer_id) {
                (Some(target), None) => with_transfer_label(row.memo.as_deref(), target),
                _ => row.memo.clone(),
            };
            SourceTransaction {
                id: stable_id(&format!("transaction:{fingerprint}\u{1f}{occurrence}")),
                account_id: account_id(&row.source.account).unwrap_or_default(),
                date: row.date,
                amount: row.amount,
                currency: currency.to_owned(),
                payee: row.source.payee.clone(),
                notes,
                category_id,
                transfer_account_id: transfer_id,
                forecast: false,
            }
        })
        .collect()
}

/// Keeps the missing transfer target visible; drops the label rather than exceeding the text limit.
fn with_transfer_label(memo: Option<&str>, target: &str) -> Option<String> {
    let label = format!("{TRANSFER_LABEL}{target}");
    let notes = match memo {
        None | Some("") => label,
        Some(memo) => format!("{memo} — {label}"),
    };
    if jlen(&notes) > MAX_TEXT {
        memo.map(str::to_owned)
    } else {
        Some(notes)
    }
}

fn note_category(
    category: Option<&str>,
    amount: Decimal,
    negative: &mut JavaHashMap,
    positive: &mut JavaHashMap,
    paths: &mut HashSet<String>,
) -> Result<(), Fail> {
    let Some(category) = category else {
        return Ok(());
    };
    add_category_paths(category, paths)?;
    let mut path = Some(category);
    while let Some(p) = path {
        if amount.is_sign_negative() && !amount.is_zero() {
            negative.put(p, true);
        } else if !amount.is_zero() {
            positive.put(p, true);
        }
        path = parent_of(p);
    }
    Ok(())
}

fn add_category_paths(category: &str, paths: &mut HashSet<String>) -> Result<(), Fail> {
    let mut path = Some(category);
    while let Some(p) = path {
        paths.insert(p.to_owned());
        if paths.len() > MAX_CATEGORIES {
            return Err(invalid());
        }
        path = parent_of(p);
    }
    Ok(())
}

fn combined_memo(parent: Option<&str>, split: Option<&str>) -> Result<Option<String>, Fail> {
    let parent = parent.filter(|m| !m.is_empty());
    let split = split.filter(|m| !m.is_empty());
    let Some(parent) = parent else {
        return Ok(split.map(str::to_owned));
    };
    match split {
        None => Ok(Some(parent.to_owned())),
        Some(split) if split == parent => Ok(Some(parent.to_owned())),
        Some(split) => {
            let combined = format!("{parent} — {split}");
            if jlen(&combined) > MAX_TEXT {
                return Err(invalid());
            }
            Ok(Some(combined))
        }
    }
}

/// The one value of a tag; empty reads as absent. A required tag must be present once, non-empty.
fn singleton<'f>(
    fields: &[&'f str],
    wanted: char,
    required: bool,
) -> Result<Option<&'f str>, Fail> {
    let mut value = None;
    let mut count = 0;
    for field in fields {
        if tag(field) == wanted {
            count += 1;
            value = Some(value_of(field));
        }
    }
    if count > 1 || (required && (count != 1 || value.is_some_and(str::is_empty))) {
        return Err(invalid());
    }
    if value.is_some_and(|v| jlen(v) > MAX_TEXT) {
        return Err(invalid());
    }
    Ok(value.filter(|v| !v.is_empty()))
}

fn validate_category(value: &str) -> Result<String, Fail> {
    if java_is_blank(value) || jlen(value) > MAX_TEXT {
        return Err(invalid());
    }
    let parts: Vec<&str> = value.split(':').collect();
    if parts.len() > 16
        || parts
            .iter()
            .any(|p| java_is_blank(p) || jlen(p) > MAX_ACCOUNT_CATEGORY_TEXT)
    {
        return Err(invalid());
    }
    Ok(parts.join(":"))
}

fn required_value(field: &str) -> Result<&str, Fail> {
    let value = value_of(field);
    if java_is_blank(value) || jlen(value) > MAX_TEXT {
        return Err(invalid());
    }
    Ok(value)
}

fn depth(path: &str) -> usize {
    1 + path.matches(':').count()
}

fn parent_of(path: &str) -> Option<&str> {
    path.rfind(':').map(|at| &path[..at])
}

fn leaf(path: &str) -> &str {
    path.rfind(':').map_or(path, |at| &path[at + 1..])
}

fn identity(values: &[Option<&String>]) -> String {
    let mut result = String::new();
    for value in values {
        match value {
            None => result.push_str("-1:"),
            Some(v) => {
                result.push_str(&jlen(v).to_string());
                result.push(':');
                result.push_str(v);
            }
        }
    }
    result
}

fn stable_id(value: &str) -> String {
    name_uuid(format!("{NAMESPACE}:{value}").as_bytes())
}
