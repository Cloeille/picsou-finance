//! SimplefinJson: an account-set document read into the port's records. Pending rows are dropped.

use std::collections::HashMap;
use std::sync::LazyLock;

use chrono::{DateTime, Datelike, NaiveDate};
use regex::Regex;
use rust_decimal::Decimal;
use serde_json::Value;

use super::SyncError;
use crate::ledger::bank_import::{clip, sha256_hex};
use crate::ledger::sync_values::java_trim;

/// Same VARCHAR(255) width as the account and transaction external ids.
const MAX_EXTERNAL_ID: usize = 255;
const MAX_ERROR_CHARS: usize = 300;
const MAX_HOLDINGS: usize = 500;
const MAX_SYMBOL: usize = 100;
const MAX_DESCRIPTION: usize = 255;
/// No amount needs more decimals or trailing zeros than this; a larger exponent is garbage.
const MAX_SCALE: i64 = 30;

const UNREADABLE: &str = "SimpleFIN returned a response Picsou could not read.";

#[derive(Debug, Clone, PartialEq)]
pub struct AccountSet {
    pub errors: Vec<String>,
    pub accounts: Vec<SimplefinAccount>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SimplefinAccount {
    pub external_id: String,
    pub connection_name: Option<String>,
    pub name: String,
    pub currency: Option<String>,
    pub balance: Decimal,
    pub transactions: Vec<SimplefinTransaction>,
    /// Bare website host of the bank behind the connection ("fidelity.com").
    pub institution_domain: Option<String>,
    pub holdings: Vec<SimplefinHolding>,
    /// False when a row was dropped or the row cap was reached: then the list is only part of
    /// the portfolio and must never replace the stored positions.
    pub holdings_complete: bool,
}

/// One position; every figure is optional and a zero means unknown.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct SimplefinHolding {
    pub symbol: Option<String>,
    pub description: Option<String>,
    pub currency: Option<String>,
    pub shares: Option<Decimal>,
    pub market_value: Option<Decimal>,
    pub cost_basis: Option<Decimal>,
    pub purchase_price: Option<Decimal>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SimplefinTransaction {
    pub external_id: Option<String>,
    pub date: NaiveDate,
    pub amount: Decimal,
    pub description: String,
}

pub fn parse(json: &str) -> Result<AccountSet, SyncError> {
    if java_trim(json).is_empty() {
        return Err(SyncError::new("SimpleFIN returned an empty response."));
    }
    // Jackson's readTree reads the first value and ignores what trails it.
    let root = serde_json::Deserializer::from_str(json)
        .into_iter::<Value>()
        .next()
        .and_then(Result::ok)
        .ok_or_else(|| SyncError::new(UNREADABLE))?;
    let Value::Object(_) = root else {
        return Err(SyncError::new(UNREADABLE));
    };

    let mut errors = Vec::new();
    collect_errors(root.get("errlist"), &mut errors);
    collect_errors(root.get("errors"), &mut errors);

    let mut institutions: HashMap<String, (Option<String>, Option<String>)> = HashMap::new();
    if let Some(Value::Array(connections)) = root.get("connections") {
        for connection in connections {
            let Some(id) = text(connection, "conn_id") else {
                continue;
            };
            let name = text(connection, "org_name").or_else(|| text(connection, "name"));
            let domain = host_of(text(connection, "org_url").as_deref())
                .or_else(|| host_of(text(connection, "url").as_deref()));
            institutions.insert(id, (name, domain));
        }
    }

    let mut accounts = Vec::new();
    if let Some(Value::Array(nodes)) = root.get("accounts") {
        for node in nodes {
            if let Some(account) = parse_account(node, &institutions) {
                accounts.push(account);
            }
        }
    }
    Ok(AccountSet { errors, accounts })
}

static ERROR_SPACE: LazyLock<Regex> = LazyLock::new(|| {
    // \p{Cc}, \p{Zl}, \p{Zp} and Java's ASCII \s.
    Regex::new(r"[\p{Cc}\p{Zl}\p{Zp} \t\n\x0B\f\r]+").expect("valid regex")
});

/// Bridge text reaches the UI and logs, so it is kept to one short line.
fn collect_errors(node: Option<&Value>, errors: &mut Vec<String>) {
    let Some(Value::Array(entries)) = node else {
        return;
    };
    for entry in entries {
        let message = if entry.is_string() {
            Some(entry)
        } else {
            entry.get("msg")
        };
        let Some(message) = message.filter(|m| !m.is_null()) else {
            continue;
        };
        let collapsed = ERROR_SPACE.replace_all(&as_text(message), " ").into_owned();
        let mut text = java_trim(&collapsed).to_owned();
        if text.is_empty() {
            continue;
        }
        if text.encode_utf16().count() > MAX_ERROR_CHARS {
            text = format!("{}\u{2026}", clip(&text, MAX_ERROR_CHARS));
        }
        errors.push(text);
    }
}

static HOST: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{1,62}$")
        .expect("valid regex")
});

/// The bare host of a bank's website, or None. It ends up in a request path to the logo
/// service, so only a plain public DNS name survives.
pub fn host_of(raw: Option<&str>) -> Option<String> {
    let value = java_trim(raw?).to_lowercase();
    let length = value.encode_utf16().count();
    if value.is_empty() || length > 300 {
        return None;
    }
    let mut host = if value.contains("://") {
        let uri = super::java_uri::parse(&value).ok()?;
        let scheme = uri.scheme.as_deref();
        if scheme != Some("https") && scheme != Some("http") {
            return None;
        }
        if uri.raw_user_info.is_some() {
            return None;
        }
        uri.host?
    } else {
        value
            .split(['/', '?', '#'])
            .next()
            .unwrap_or_default()
            .to_owned()
    };
    if let Some(stripped) = host.strip_suffix('.') {
        host = stripped.to_owned();
    }
    if let Some(stripped) = host.strip_prefix("www.") {
        host = stripped.to_owned();
    }
    let length = host.chars().count();
    ((4..=255).contains(&length) && HOST.is_match(&host)).then_some(host)
}

fn parse_account(
    node: &Value,
    institutions: &HashMap<String, (Option<String>, Option<String>)>,
) -> Option<SimplefinAccount> {
    let id = text(node, "id")?;
    let balance = decimal(text(node, "balance").as_deref())?;
    let conn_id = text(node, "conn_id");
    let name = text(node, "name");
    let transactions = match node.get("transactions") {
        Some(Value::Array(rows)) => rows.iter().filter_map(parse_transaction).collect(),
        _ => Vec::new(),
    };
    let institution = conn_id.as_ref().and_then(|c| institutions.get(c));
    let (holdings, holdings_complete) = parse_holdings(node.get("holdings"));
    Some(SimplefinAccount {
        external_id: external_account_id(conn_id.as_deref(), &id),
        connection_name: institution.and_then(|i| i.0.clone()),
        name: name.unwrap_or_else(|| "Account".to_owned()),
        currency: text(node, "currency"),
        balance,
        transactions,
        institution_domain: institution.and_then(|i| i.1.clone()),
        holdings,
        holdings_complete,
    })
}

/// Rows without a symbol or description, or without a numeric share count, are dropped: they
/// cannot be keyed or valued. A dropped row or a list past the cap makes the result incomplete.
fn parse_holdings(node: Option<&Value>) -> (Vec<SimplefinHolding>, bool) {
    let Some(Value::Array(rows)) = node else {
        return (Vec::new(), true);
    };
    let mut complete = rows.len() <= MAX_HOLDINGS;
    let mut holdings = Vec::new();
    for row in rows.iter().take(MAX_HOLDINGS) {
        let symbol = text(row, "symbol").map(|s| clip(&s, MAX_SYMBOL));
        let description = text(row, "description").map(|s| clip(&s, MAX_DESCRIPTION));
        let shares = decimal(text(row, "shares").as_deref());
        if shares.is_none() || (symbol.is_none() && description.is_none()) {
            complete = false;
            continue;
        }
        holdings.push(SimplefinHolding {
            symbol,
            description,
            currency: text(row, "currency"),
            shares,
            market_value: decimal(text(row, "market_value").as_deref()),
            cost_basis: decimal(text(row, "cost_basis").as_deref()),
            purchase_price: decimal(text(row, "purchase_price").as_deref()),
        });
    }
    (holdings, complete)
}

fn parse_transaction(node: &Value) -> Option<SimplefinTransaction> {
    if as_boolean(node.get("pending")) {
        return None;
    }
    let mut posted = as_long(node.get("posted"));
    if posted <= 0 {
        return None;
    }
    if posted > 10_000_000_000 {
        posted /= 1000;
    }
    let amount = decimal(text(node, "amount").as_deref())?;
    let description = text(node, "description").unwrap_or_else(|| "Transaction".to_owned());
    let date = DateTime::from_timestamp(posted, 0)?.date_naive();
    // PostgreSQL rejects a date past year 5874897; a bank posting outside this span is a bad
    // timestamp, including one the milliseconds guess did not fix.
    if !(1900..=2200).contains(&date.year()) {
        return None;
    }
    Some(SimplefinTransaction {
        external_id: fit(text(node, "id")),
        date,
        amount,
        description,
    })
}

/// The sfin_ prefix survives a hash: account deletion recognises the connection by it.
pub fn external_account_id(conn_id: Option<&str>, account_id: &str) -> String {
    let conn = conn_id
        .filter(|c| !c.trim().is_empty())
        .unwrap_or("account");
    let raw = format!("sfin_{conn}_{account_id}");
    if raw.encode_utf16().count() <= MAX_EXTERNAL_ID {
        return raw;
    }
    format!("sfin_{}", sha256_hex(&raw))
}

fn fit(value: Option<String>) -> Option<String> {
    let value = value?;
    if value.encode_utf16().count() <= MAX_EXTERNAL_ID {
        return Some(value);
    }
    Some(sha256_hex(&value))
}

/// JsonNode.asText(): a string as is, a number's text, a boolean's word, "" for containers.
fn as_text(value: &Value) -> String {
    match value {
        Value::String(s) => s.clone(),
        Value::Number(n) => n.to_string(),
        Value::Bool(b) => b.to_string(),
        Value::Null => "null".to_owned(),
        Value::Array(_) | Value::Object(_) => String::new(),
    }
}

/// The trimmed text of a field, None when absent, null or blank.
fn text(node: &Value, field: &str) -> Option<String> {
    let value = node.get(field).filter(|v| !v.is_null())?;
    let text = java_trim(&as_text(value)).to_owned();
    (!text.is_empty()).then_some(text)
}

/// JsonNode.path(f).asBoolean(false): true, a non-zero integer or the text "true".
fn as_boolean(value: Option<&Value>) -> bool {
    match value {
        Some(Value::Bool(b)) => *b,
        Some(Value::Number(n)) => n.as_i64().is_some_and(|v| v != 0),
        Some(Value::String(s)) => java_trim(s) == "true",
        _ => false,
    }
}

/// JsonNode.path(f).asLong(0): an integral number, a number's integer part, or a text that
/// parses as a long.
fn as_long(value: Option<&Value>) -> i64 {
    match value {
        Some(Value::Number(n)) => n
            .as_i64()
            .or_else(|| integer_part(&n.to_string()))
            .unwrap_or(0),
        Some(Value::String(s)) => java_trim(s).parse::<i64>().unwrap_or(0),
        Some(Value::Bool(b)) => i64::from(*b),
        _ => 0,
    }
}

/// The integer part of a JSON number's text, as a double's long value would truncate it.
fn integer_part(text: &str) -> Option<i64> {
    i64::try_from(decimal(Some(text))?.trunc()).ok()
}

/// new BigDecimal(text), None for anything that is not a plain figure or whose scale is past
/// MAX_SCALE: such an exponent would make every later divide or setScale fail.
pub fn decimal(value: Option<&str>) -> Option<Decimal> {
    let text = value?;
    let (mantissa, exponent) = match text.find(['e', 'E']) {
        Some(at) => (&text[..at], text[at + 1..].parse::<i64>().ok()?),
        None => (text, 0),
    };
    let (negative, digits) = match mantissa.as_bytes().first()? {
        b'-' => (true, &mantissa[1..]),
        b'+' => (false, &mantissa[1..]),
        _ => (false, mantissa),
    };
    let (int, frac) = digits.split_once('.').unwrap_or((digits, ""));
    if int.is_empty() && frac.is_empty()
        || !int.bytes().chain(frac.bytes()).all(|b| b.is_ascii_digit())
    {
        return None;
    }
    let scale = i64::try_from(frac.len()).ok()?.checked_sub(exponent)?;
    if scale.abs() > MAX_SCALE {
        return None;
    }
    let all_digits = format!("{int}{frac}");
    let significant = all_digits.trim_start_matches('0');
    if significant.len() > 38 {
        return None;
    }
    let mut unscaled: i128 = if significant.is_empty() {
        0
    } else {
        significant.parse().ok()?
    };
    let value = if scale >= 0 {
        let mut scale = u32::try_from(scale).ok()?;
        if scale > 28 {
            // rust_decimal holds 28 places: round the extra ones off, HALF_UP like setScale.
            let divisor = 10_i128.pow(scale - 28);
            let (quotient, remainder) = (unscaled / divisor, unscaled % divisor);
            unscaled = quotient + i128::from(remainder * 2 >= divisor);
            scale = 28;
        }
        Decimal::try_from_i128_with_scale(unscaled, scale).ok()?
    } else {
        let factor = 10_i128.checked_pow(u32::try_from(-scale).ok()?)?;
        Decimal::try_from_i128_with_scale(unscaled.checked_mul(factor)?, 0).ok()?
    };
    Some(if negative { -value } else { value })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn one(account: Value) -> Option<SimplefinAccount> {
        parse(&serde_json::json!({ "accounts": [account] }).to_string())
            .unwrap()
            .accounts
            .into_iter()
            .next()
    }

    #[test]
    fn decimals_parse_like_big_decimal() {
        for (text, expected) in [
            ("12.50", "12.50"),
            ("-3", "-3"),
            ("1e2", "100"),
            (".5", "0.5"),
            ("+1.", "1"),
        ] {
            assert_eq!(decimal(Some(text)).unwrap().to_string(), expected, "{text}");
        }
        for text in ["", "abc", "1,5", "NaN", "1e999999999", "1E-31", "--1", "."] {
            assert_eq!(decimal(Some(text)), None, "{text}");
        }
    }

    #[test]
    fn an_account_needs_an_id_and_a_balance() {
        let base = serde_json::json!({ "id": "A", "balance": "1.00", "currency": " usd " });
        let parsed = one(base.clone()).unwrap();
        assert_eq!(parsed.external_id, "sfin_account_A");
        assert_eq!(parsed.name, "Account");
        assert_eq!(parsed.currency.as_deref(), Some("usd"));
        assert_eq!(one(serde_json::json!({ "id": " ", "balance": "1" })), None);
        assert_eq!(one(serde_json::json!({ "id": "A", "balance": "x" })), None);
        assert!(one(serde_json::json!({ "id": "A", "balance": 12.5 })).is_some());
    }

    #[test]
    fn external_ids_keep_the_prefix_when_hashed() {
        let id = "x".repeat(300);
        let hashed = external_account_id(Some("C"), &id);
        assert!(hashed.starts_with("sfin_") && hashed.len() == 69);
        assert_eq!(hashed, external_account_id(Some("C"), &id));
        let at_limit = "x".repeat(255 - "sfin_C_".len());
        assert_eq!(
            external_account_id(Some("C"), &at_limit),
            format!("sfin_C_{at_limit}")
        );
    }

    #[test]
    fn transactions_drop_pending_and_unusable_rows() {
        let account = one(serde_json::json!({
            "id": "A", "balance": "1",
            "transactions": [
                { "id": "1", "posted": 1_768_046_400, "amount": "-1", "description": " " },
                { "id": "2", "posted": 1_768_046_400_000_i64, "amount": "-2" },
                { "id": "3", "posted": 1_768_046_400, "amount": "-3", "pending": true },
                { "id": "4", "posted": 0, "amount": "-4" },
                { "id": "5", "posted": 1_768_046_400, "amount": "x" },
                { "id": "6", "posted": -5, "amount": "-6" },
                { "id": "y".repeat(256), "posted": "1768046400", "amount": "-7" },
            ]
        }))
        .unwrap();
        let ids: Vec<_> = account
            .transactions
            .iter()
            .map(|t| t.external_id.clone().unwrap())
            .collect();
        assert_eq!(ids[..2], ["1".to_owned(), "2".to_owned()]);
        assert_eq!(ids.len(), 3);
        assert_eq!(ids[2].len(), 64, "an id past 255 characters is hashed");
        assert_eq!(account.transactions[0].description, "Transaction");
        assert_eq!(
            account.transactions[1].date,
            NaiveDate::from_ymd_opt(2026, 1, 10).unwrap()
        );
    }

    #[test]
    fn errors_are_collapsed_to_one_short_line() {
        let long = "é".repeat(400);
        let set = parse(
            &serde_json::json!({
                "errlist": [{ "msg": "a\n\tb" }, { "msg": null }, "  "],
                "errors": [long],
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(set.errors[0], "a b");
        assert_eq!(set.errors.len(), 2);
        assert_eq!(set.errors[1].chars().count(), 301);
        assert!(parse("  ").is_err() && parse("[1]").is_err() && parse("{x").is_err());
    }

    #[test]
    fn institution_hosts_keep_only_a_public_name() {
        assert_eq!(
            host_of(Some("https://www.Fidelity.com:443/login")).as_deref(),
            Some("fidelity.com")
        );
        assert_eq!(
            host_of(Some("chase.com/path")).as_deref(),
            Some("chase.com")
        );
        for raw in [
            "https://10.0.0.5/",
            "localhost",
            "ftp://bank.com",
            "https://u:p@bank.com",
            "",
            "a.b",
        ] {
            assert_eq!(host_of(Some(raw)), None, "{raw}");
        }
    }

    #[test]
    fn holdings_past_the_cap_or_unreadable_are_incomplete() {
        let rows: Vec<Value> = (0..501)
            .map(|i| serde_json::json!({ "symbol": format!("T{i}"), "shares": "1" }))
            .collect();
        let capped =
            one(serde_json::json!({ "id": "A", "balance": "1", "holdings": rows })).unwrap();
        assert_eq!(capped.holdings.len(), 500);
        assert!(!capped.holdings_complete);
        let unreadable = one(serde_json::json!({ "id": "A", "balance": "1", "holdings": [
            { "symbol": "A", "shares": 2, "market_value": 10.5 }, { "symbol": "B" }, { "shares": "1" }
        ] })).unwrap();
        assert_eq!(unreadable.holdings.len(), 1);
        assert_eq!(
            unreadable.holdings[0].market_value,
            Some("10.5".parse().unwrap())
        );
        assert!(!unreadable.holdings_complete);
    }
}
