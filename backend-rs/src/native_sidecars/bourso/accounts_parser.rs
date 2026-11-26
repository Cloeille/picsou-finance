//! Turns BoursoBank's dashboard HTML, trading JSON, assurance-vie/PER pages,
//! RIB pages, and movement pages into the sidecar contract.
//!
//! Faithful port of `services/bourso-auth/accounts_parser.py`. Nothing is ever
//! coerced to zero and no row is skipped to "recover".

use std::collections::{BTreeMap, HashMap, HashSet};
use std::str::FromStr;
use std::sync::LazyLock;

use chrono::NaiveDate;
use regex::Regex;
use rust_decimal::prelude::ToPrimitive;
use rust_decimal::{Decimal, RoundingStrategy};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use url::Url;

use super::html::{clean_text, deaccent, html_unescape, plain_text};

pub const FORMAT_CHANGED: &str = "UPSTREAM_FORMAT_CHANGED";
pub const INCOMPLETE: &str = "PORTFOLIO_INCOMPLETE";
pub const INVALID_DATA: &str = "INVALID_DATA";
pub const IDENTITY_UNSUPPORTED: &str = "IDENTITY_SELECTION_UNSUPPORTED";

pub const LIFE_INSURANCE_PATH: &str = "/compte/assurance-vie/";
pub const PER_PATH: &str = "/compte/per/";
pub const IDENTITY_SWITCH_PATH: &str = "/connexion/changer-identite/";

pub const MAX_ACCOUNTS: usize = 60;
const LI_MAX_FALLBACK_SYMBOL: usize = 30;
const LI_DIGEST_LENGTH: usize = 8;
const LI_LABEL_PREFIX: usize = LI_MAX_FALLBACK_SYMBOL - LI_DIGEST_LENGTH - 1;
const PER_CELLS: usize = 7;

const OWN_BANK_LABELS: [&str; 3] = ["BOURSOBANK", "BOURSORAMA", "BOURSORAMA BANQUE"];

fn money_absolute_tolerance() -> Decimal {
    Decimal::new(5, 2) // 0.05
}

fn money_relative_tolerance() -> Decimal {
    Decimal::new(1, 3) // 0.001
}

fn money_per_line_tolerance() -> Decimal {
    Decimal::new(1, 2) // 0.01
}

#[derive(Debug, Clone, thiserror::Error)]
#[error("{message}")]
pub struct AccountsFormatError {
    pub code: &'static str,
    pub message: String,
}

impl AccountsFormatError {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

#[derive(Debug, Clone, thiserror::Error)]
pub enum OperationsParseError {
    #[error("{0}")]
    Format(#[from] AccountsFormatError),
    #[error("{0}")]
    Invalid(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AccountKind {
    Checking,
    Savings,
    Lep,
    LivretA,
    Ldds,
    LivretJeune,
    Pel,
    Cel,
    Pea,
    CompteTitres,
    AssuranceVie,
    Per,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DashboardAccount {
    pub id: String,
    pub name: String,
    pub kind: AccountKind,
    pub balance_eur: Decimal,
    pub section: &'static str,
    pub href: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PositionPayload {
    pub isin: Option<String>,
    pub symbol: String,
    pub label: String,
    pub quantity: Decimal,
    pub buying_price_eur: Option<Decimal>,
    pub current_price: Option<Decimal>,
    pub quote_currency: Option<String>,
    pub current_value_eur: Decimal,
    pub pnl_eur: Option<Decimal>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TradingSummary {
    pub cash_eur: Decimal,
    pub total_eur: Decimal,
    pub positions: Vec<PositionPayload>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LineValuedSummary {
    pub cash_eur: Decimal,
    pub total_eur: Decimal,
    pub positions: Vec<PositionPayload>,
    pub valuation_line_count: usize,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MovementTransaction {
    pub external_id: String,
    pub date: String,
    pub amount: Decimal,
    pub description: String,
    pub counterparty: Option<String>,
    pub kind: String,
}

// ─── Value helpers ──────────────────────────────────────────────────────────

/// Parse money from JSON without going through binary floats.
pub fn decimal_value(value: &Value) -> Option<Decimal> {
    match value {
        Value::Null | Value::Bool(_) | Value::Array(_) | Value::Object(_) => None,
        Value::Number(num) => parse_decimal_exact(&num.to_string()),
        Value::String(s) => parse_amount(s),
    }
}

fn parse_decimal_exact(raw: &str) -> Option<Decimal> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    Decimal::from_str(trimmed)
        .or_else(|_| Decimal::from_scientific(trimmed))
        .ok()
}

/// Parse a French-formatted amount: `'11 010,00'`, `'-1 234,5'`, `'−9 495,82'`.
pub fn parse_amount(raw: &str) -> Option<Decimal> {
    let cleaned = raw
        .trim()
        .replace(['\u{00a0}', '\u{202f}', ' ', '€'], "")
        .replace('\u{2212}', "-")
        .replace(',', ".");
    if cleaned.is_empty() || cleaned == "-" || cleaned == "." {
        return None;
    }
    parse_decimal_exact(&cleaned)
}

/// Strip tags, unescape HTML entities once, normalize whitespace, and truncate.
pub fn text_value(value: Option<&Value>, limit: usize) -> Option<String> {
    let raw = value?.as_str()?;
    clean_text(raw, limit)
}

pub fn money_close(actual: Decimal, expected: Decimal) -> bool {
    let Some(difference) = actual.checked_sub(expected).map(|d| d.abs()) else {
        return false;
    };
    if difference <= money_absolute_tolerance() {
        return true;
    }
    expected
        .abs()
        .checked_mul(money_relative_tolerance())
        .is_some_and(|tol| difference <= tol)
}

pub fn lines_close(actual: Decimal, expected: Decimal, lines: usize) -> bool {
    let Some(difference) = actual.checked_sub(expected).map(|d| d.abs()) else {
        return false;
    };
    let Some(per_line) = money_per_line_tolerance().checked_mul(Decimal::from(lines)) else {
        return false;
    };
    let allowance = money_absolute_tolerance().max(per_line);
    difference <= allowance
}

fn checked_add(a: Decimal, b: Decimal) -> Result<Decimal, AccountsFormatError> {
    a.checked_add(b)
        .ok_or_else(|| AccountsFormatError::new(INVALID_DATA, "amount out of range"))
}

fn checked_sum(iter: impl IntoIterator<Item = Decimal>) -> Result<Decimal, AccountsFormatError> {
    iter.into_iter().try_fold(Decimal::ZERO, checked_add)
}

/// Shape-only description of a JSON value: containers and key names, never values.
pub fn describe_payload(payload: &Value, depth: usize) -> String {
    match payload {
        Value::Array(items) => {
            if items.is_empty() {
                return "list[0]".to_owned();
            }
            let inner = items
                .iter()
                .take(3)
                .map(|item| describe_payload(item, depth + 1))
                .collect::<Vec<_>>()
                .join("; ");
            let suffix = if items.len() > 3 { ";…" } else { "" };
            format!("list[{}]({inner}{suffix})", items.len())
        }
        Value::Object(map) => {
            if depth >= 2 {
                let keys = map
                    .keys()
                    .take(12)
                    .map(String::as_str)
                    .collect::<Vec<_>>()
                    .join(",");
                return format!("{{{keys}}}");
            }
            let mut parts = Vec::new();
            for (key, value) in map.iter().take(24) {
                match value {
                    Value::Array(_) | Value::Object(_) => {
                        parts.push(format!("{key}:{}", describe_payload(value, depth + 1)));
                    }
                    Value::Null => parts.push(format!("{key}:null")),
                    _ => parts.push(key.clone()),
                }
            }
            format!("{{{}}}", parts.join(","))
        }
        Value::Null => "null".to_owned(),
        Value::Bool(_) => "bool".to_owned(),
        Value::Number(_) => "number".to_owned(),
        Value::String(_) => "str".to_owned(),
    }
}

/// Ratio of the gap between two amounts, safe for diagnostics (never logs an amount).
pub fn relative_difference(actual: Decimal, expected: Decimal) -> String {
    if expected.is_zero() {
        return if actual.is_zero() { "n/a" } else { "inf" }.to_owned();
    }
    let Some(ratio) = actual
        .checked_sub(expected)
        .and_then(|diff| diff.abs().checked_div(expected.abs()))
    else {
        return "inf".to_owned();
    };
    let rounded = ratio.round_dp_with_strategy(4, RoundingStrategy::MidpointNearestEven);
    format!("{rounded:.4}")
}

// ─── Dashboard ──────────────────────────────────────────────────────────────

static SECTION_PATTERNS: LazyLock<[(&'static str, Regex); 6]> = LazyLock::new(|| {
    [
        (
            "banking",
            Regex::new(r"(?s)data-summary-bank>(.*?)</div>").unwrap(),
        ),
        (
            "savings",
            Regex::new(r"(?s)data-summary-savings>(.*?)</ul>").unwrap(),
        ),
        (
            "trading",
            Regex::new(r"(?s)data-summary-trading>(.*?)</div>").unwrap(),
        ),
        (
            "loans",
            Regex::new(r"(?s)data-summary-loan>(.*?)</div>").unwrap(),
        ),
        (
            "insurance",
            Regex::new(r"(?s)data-summary-(?:insurance|assurance)>(.*?)</div>").unwrap(),
        ),
        (
            "children",
            Regex::new(r"(?s)data-summary-child>(.*?)</div>").unwrap(),
        ),
    ]
});

static ACCOUNT_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r#"(?s)/compte/(.*?)?/?(?P<id>[a-f0-9]{32})/?"(.*?)Solde\s:\s(?P<balance>[\d\s\u{00a0}\u{2212}-]+,?\d{0,2})\s€.+?c-info-box__account-label.+?>(?P<name>.+?)</span>.+?c-info-box__account-sub-label.+?>(?P<bank>.+?)</span>"#,
    )
    .expect("account regex")
});

static ACCOUNT_LINK_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"href="(?P<href>[^"]*?/compte/[^"]*?(?P<id>[a-f0-9]{32})/?)""#)
        .expect("account link regex")
});

static PEA_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\bPEA\b|\bPEA-?PME\b").expect("pea regex"));

static SAVINGS_PATTERNS: LazyLock<[(AccountKind, Regex); 6]> = LazyLock::new(|| {
    [
        (
            AccountKind::Lep,
            Regex::new(r"\bLEP\b|EPARGNE POPULAIRE").unwrap(),
        ),
        (AccountKind::LivretA, Regex::new(r"\bLIVRET A\b").unwrap()),
        (
            AccountKind::Ldds,
            Regex::new(r"\bLDDS?\b|DEVELOPPEMENT DURABLE").unwrap(),
        ),
        (
            AccountKind::LivretJeune,
            Regex::new(r"\bLIVRET JEUNE\b").unwrap(),
        ),
        (
            AccountKind::Pel,
            Regex::new(r"\bPEL\b|PLAN\b.{0,3}EPARGNE LOGEMENT").unwrap(),
        ),
        (
            AccountKind::Cel,
            Regex::new(r"\bCEL\b|COMPTE\b.{0,3}EPARGNE LOGEMENT").unwrap(),
        ),
    ]
});

pub fn account_type(section: &str, name: &str, href: &str) -> AccountKind {
    let label = deaccent(name);
    if section == "trading" && href.contains(LIFE_INSURANCE_PATH) {
        return AccountKind::AssuranceVie;
    }
    if section == "trading" && href.contains(PER_PATH) {
        return AccountKind::Per;
    }
    if section == "trading" {
        return if PEA_RE.is_match(&label) {
            AccountKind::Pea
        } else {
            AccountKind::CompteTitres
        };
    }
    if section == "savings" {
        for (kind, pattern) in SAVINGS_PATTERNS.iter() {
            if pattern.is_match(&label) {
                return *kind;
            }
        }
        return AccountKind::Savings;
    }
    AccountKind::Checking
}

pub fn is_own_account(bank_label: &str) -> bool {
    let normalized = deaccent(bank_label);
    OWN_BANK_LABELS.contains(&normalized.trim())
}

/// Parse `/dashboard/liste-comptes` HTML. Returns `(in_scope_accounts, skipped_third_party_count)`.
pub fn parse_dashboard(html: &str) -> Result<(Vec<DashboardAccount>, usize), AccountsFormatError> {
    if !SECTION_PATTERNS
        .iter()
        .any(|(_, pattern)| pattern.is_match(html))
    {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Dashboard carried no account section",
        ));
    }

    let mut linked: HashMap<String, String> = HashMap::new();
    for cap in ACCOUNT_LINK_RE.captures_iter(html) {
        let id = cap.name("id").expect("id").as_str().to_owned();
        let href = cap.name("href").expect("href").as_str().to_owned();
        linked.insert(id, href);
    }

    let mut accounts = Vec::new();
    let mut accounted_ids: HashSet<String> = HashSet::new();
    let mut third_party: usize = 0;

    for &(section, ref pattern) in SECTION_PATTERNS.iter() {
        for block_cap in pattern.captures_iter(html) {
            let block = block_cap.get(1).expect("section block").as_str();
            for cap in ACCOUNT_RE.captures_iter(block) {
                let account_id = cap.name("id").expect("id").as_str().to_owned();
                let name = clean_text(cap.name("name").expect("name").as_str(), 200);
                let bank = clean_text(cap.name("bank").expect("bank").as_str(), 200);
                let balance = parse_amount(cap.name("balance").expect("balance").as_str());
                let (Some(name), Some(bank), Some(balance)) = (name, bank, balance) else {
                    return Err(AccountsFormatError::new(
                        FORMAT_CHANGED,
                        format!(
                            "Account card {}… is missing a required field",
                            &account_id[..account_id.len().min(8)]
                        ),
                    ));
                };

                if !accounted_ids.insert(account_id.clone()) {
                    continue;
                }

                if matches!(section, "children" | "loans" | "insurance") {
                    continue;
                }
                if !is_own_account(&bank) {
                    third_party += 1;
                    continue;
                }

                let Some(href) = linked.get(&account_id).cloned() else {
                    return Err(AccountsFormatError::new(
                        FORMAT_CHANGED,
                        format!(
                            "Account card {}… has no matching href",
                            &account_id[..account_id.len().min(8)]
                        ),
                    ));
                };
                let kind = account_type(section, &name, &href);
                accounts.push(DashboardAccount {
                    id: account_id,
                    name,
                    kind,
                    balance_eur: balance,
                    section,
                    href,
                });
            }
        }
    }

    let missing = linked
        .keys()
        .filter(|id| !accounted_ids.contains(*id))
        .count();
    if missing > 0 {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            format!("{missing} account card(s) on the dashboard did not parse"),
        ));
    }

    if accounts.is_empty() {
        return Err(AccountsFormatError::new(
            INCOMPLETE,
            "Dashboard held no BoursoBank account",
        ));
    }
    if accounts.len() > MAX_ACCOUNTS {
        return Err(AccountsFormatError::new(
            INCOMPLETE,
            "Dashboard held more accounts than supported",
        ));
    }

    Ok((accounts, third_party))
}

// ─── Operations & RIB ───────────────────────────────────────────────────────

static UL_TAG_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)<ul\b[^>]*>").unwrap());
static CLASS_ATTR_DOUBLE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?i)\bclass\s*=\s*"([^"]*)""#).unwrap());
static CLASS_ATTR_SINGLE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)\bclass\s*=\s*'([^']*)'").unwrap());
static LIST_MOVEMENT_WORD_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)\blist__movement\b").unwrap());

static MOVEMENT_ROW_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?si)<li\b(?P<attrs>[^>]*)>(?P<body>.*?)</li>").unwrap());
static ATTR_DOUBLE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?i)\b(?P<name>data-id|data-custom-id|data-operations-next-pagination)\s*=\s*"(?P<value>[^"]*)""#)
        .unwrap()
});
static ATTR_SINGLE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)\b(?P<name>data-id|data-custom-id|data-operations-next-pagination)\s*=\s*'(?P<value>[^']*)'")
        .unwrap()
});
static AMOUNT_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?si)<[^>]*class="[^"]*list-operation-item__amount[^"]*"[^>]*>(?P<value>.*?)</"#)
        .unwrap()
});
static DESCRIPTION_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r#"(?si)<(?:span|div)[^>]*class="[^"]*(?:list__movement--label-(?:initial|user)|list-operation-item__label-name)[^"]*"[^>]*>(?P<value>.*?)</(?:span|div)>"#,
    )
    .unwrap()
});
static MOVEMENT_DATE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?P<day>\d{1,2})\s+(?P<month>[a-zéû]+)\s+(?P<year>\d{4})").unwrap()
});
static VIR_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^(?:VIR|VIREMENT)\b").unwrap());

fn has_movement_container(html: &str) -> bool {
    for mat in UL_TAG_RE.find_iter(html) {
        let tag = mat.as_str();
        for pat in [&*CLASS_ATTR_DOUBLE_RE, &*CLASS_ATTR_SINGLE_RE] {
            if let Some(cap) = pat.captures(tag)
                && LIST_MOVEMENT_WORD_RE.is_match(cap.get(1).unwrap().as_str())
            {
                return true;
            }
        }
    }
    false
}

fn french_month(month: &str) -> Option<u32> {
    match month {
        "janvier" => Some(1),
        "février" | "fevrier" => Some(2),
        "mars" => Some(3),
        "avril" => Some(4),
        "mai" => Some(5),
        "juin" => Some(6),
        "juillet" => Some(7),
        "août" | "aout" => Some(8),
        "septembre" => Some(9),
        "octobre" => Some(10),
        "novembre" => Some(11),
        "décembre" | "decembre" => Some(12),
        _ => None,
    }
}

fn parse_movement_date(raw: &str) -> Option<NaiveDate> {
    let text = plain_text(raw).to_lowercase();
    let cap = MOVEMENT_DATE_RE.captures(&text)?;
    let day: u32 = cap.name("day")?.as_str().parse().ok()?;
    let month = french_month(cap.name("month")?.as_str())?;
    let year: i32 = cap.name("year")?.as_str().parse().ok()?;
    NaiveDate::from_ymd_opt(year, month, day)
}

fn movement_kind(description: &str) -> &'static str {
    let upper = deaccent(description);
    if VIR_RE.is_match(&upper) {
        return "OTHER";
    }
    if upper.contains("CARTE") || upper.starts_with("CB ") || upper.starts_with("PAIEMENT ") {
        return "CARD";
    }
    if upper.starts_with("PRLV") || upper.starts_with("PRELEVEMENT") {
        return "DIRECT_DEBIT";
    }
    if upper.starts_with("CHQ") || upper.starts_with("CHEQUE") {
        return "CHECK";
    }
    "OTHER"
}

fn extract_li_attributes(attrs: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    for pat in [&*ATTR_DOUBLE_RE, &*ATTR_SINGLE_RE] {
        for cap in pat.captures_iter(attrs) {
            let name = cap.name("name").unwrap().as_str().to_ascii_lowercase();
            let value = html_unescape(cap.name("value").unwrap().as_str());
            map.insert(name, value);
        }
    }
    map
}

/// Parse a BoursoBank `/mouvements` HTML page. Returns `(transactions, next_pagination_token)`.
pub fn parse_operations(
    html: &str,
    from_date: NaiveDate,
    to_date: NaiveDate,
) -> Result<(Vec<MovementTransaction>, Option<String>), OperationsParseError> {
    if from_date > to_date {
        return Err(OperationsParseError::Invalid(
            "Invalid movements date range".to_owned(),
        ));
    }
    if !has_movement_container(html) {
        return Err(OperationsParseError::Format(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Movements page omitted the expected history container",
        )));
    }

    let mut transactions = Vec::new();
    let mut current_date: Option<NaiveDate> = None;
    let mut next_token: Option<String> = None;

    for cap in MOVEMENT_ROW_RE.captures_iter(html) {
        let attrs = cap.name("attrs").unwrap().as_str();
        let body = cap.name("body").unwrap().as_str();

        if attrs.contains("date-line") {
            current_date = parse_movement_date(body);
            continue;
        }
        let attributes = extract_li_attributes(attrs);
        if let Some(token) = attributes.get("data-operations-next-pagination") {
            next_token = if token.is_empty() {
                None
            } else {
                Some(token.clone())
            };
            continue;
        }
        if attrs.contains("list__movement__line--deffered")
            || body.contains("list__movement__line--block__split")
        {
            continue;
        }
        let external_id = attributes
            .get("data-id")
            .filter(|s| !s.is_empty())
            .or_else(|| attributes.get("data-custom-id").filter(|s| !s.is_empty()))
            .cloned();
        let Some(external_id) = external_id else {
            if attrs.contains("list__movement__line")
                || body.contains("list-operation-item__amount")
            {
                return Err(OperationsParseError::Invalid(
                    "Movement row omitted its upstream identifier".to_owned(),
                ));
            }
            continue;
        };

        let amount = AMOUNT_RE
            .captures(body)
            .and_then(|c| parse_amount(&plain_text(c.name("value").unwrap().as_str())));
        let description = DESCRIPTION_RE
            .captures(body)
            .map(|c| plain_text(c.name("value").unwrap().as_str()))
            .unwrap_or_default();

        let (Some(date), Some(amount)) = (current_date, amount) else {
            return Err(OperationsParseError::Invalid(
                "Movement row omitted required fields".to_owned(),
            ));
        };
        if description.is_empty() {
            return Err(OperationsParseError::Invalid(
                "Movement row omitted required fields".to_owned(),
            ));
        }
        if date < from_date || date > to_date {
            continue;
        }
        transactions.push(MovementTransaction {
            external_id,
            date: date.format("%Y-%m-%d").to_string(),
            amount,
            kind: movement_kind(&description).to_owned(),
            description,
            counterparty: None,
        });
    }

    Ok((transactions, next_token))
}

static IBAN_PAGE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r#"(?si)<div\b[^>]*>\s*<strong\b[^>]*>\s*IBAN\s*</strong>\s*<div\b[^>]*class="[^"]*\bdefinition\b[^"]*"[^>]*>\s*<p\b[^>]*>(?P<iban>.*?)</p>"#,
    )
    .expect("iban page regex")
});

static IBAN_FULL_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$").expect("iban full regex"));

pub fn parse_iban_page(html: &str) -> Option<String> {
    let cap = IBAN_PAGE_RE.captures(html)?;
    let raw = plain_text(cap.name("iban")?.as_str());
    let compact: String = raw
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect::<String>()
        .to_uppercase();
    if IBAN_FULL_RE.is_match(&compact) {
        Some(compact)
    } else {
        None
    }
}

// ─── Identity selector ──────────────────────────────────────────────────────

static ANY_IDENTITY_LINK_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?i)/connexion/changer-identite/(?P<token>[^"'\s<>?#]+)"#)
        .expect("any identity link regex")
});

static IDENTITY_TOKEN_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^[A-Za-z0-9._~=-]+(?:/[A-Za-z0-9._~=-]+)*$").expect("identity token regex")
});

static BUSINESS_IDENTITY_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"\b(?:PROS?|PROFESSIONNEL(?:LE)?S?|ENTREPRISES?|ENTREPRENEURS?|ENTREPRENEUSES?|INDEPENDANTE?S?|EIR?L?|SOCIETES?|SAS|SASU|SARL|EURL|SCI|SIRE[NT]|COMMERCANTE?S?|ARTISANE?S?|LIBERALE?S?|LIBERAUX|ASSOCIATIONS?)\b|\bE\.I\.",
    )
    .expect("business identity regex")
});

/// Return the switch path (`/connexion/changer-identite/<token>`) of the sole personal identity.
pub fn choose_personal_identity(html: &str) -> Result<String, AccountsFormatError> {
    choose_personal_identity_for_origin(html, "https://clients.boursobank.com")
}

/// Origin-aware variant so wiremock tests running on `http://127.0.0.1:<port>` can also test
/// absolute identity-switch links while keeping production strictly bound to `clients.boursobank.com`.
pub fn choose_personal_identity_for_origin(
    html: &str,
    base_url: &str,
) -> Result<String, AccountsFormatError> {
    let origin = base_url.trim_end_matches('/');
    let origin_alt = if origin == "https://clients.boursobank.com" {
        regex::escape(origin)
    } else {
        format!(
            "(?:{}|{})",
            regex::escape("https://clients.boursobank.com"),
            regex::escape(origin)
        )
    };
    let anchor_re = Regex::new(&format!(
        r#"(?si)<a\b[^>]*?href="(?:{origin_alt})?/connexion/changer-identite/(?P<token>[^"]*)"[^>]*>(?P<body>.*?)</a>"#
    ))
    .expect("identity anchor regex");

    // Preserve insertion order with Vec + HashMap.
    let mut order: Vec<String> = Vec::new();
    let mut labels: HashMap<String, Vec<String>> = HashMap::new();
    for cap in anchor_re.captures_iter(html) {
        let token = html_unescape(cap.name("token").unwrap().as_str());
        let text = clean_text(cap.name("body").unwrap().as_str(), 200).unwrap_or_default();
        if !labels.contains_key(&token) {
            order.push(token.clone());
        }
        labels.entry(token).or_default().push(text);
    }

    let linked: HashSet<String> = ANY_IDENTITY_LINK_RE
        .captures_iter(html)
        .map(|cap| html_unescape(cap.name("token").unwrap().as_str()))
        .collect();
    let parsed_keys: HashSet<String> = labels.keys().cloned().collect();
    if labels.is_empty() || linked != parsed_keys {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Identity selector links did not parse",
        ));
    }
    if labels
        .keys()
        .any(|token| !IDENTITY_TOKEN_RE.is_match(token) || token.contains(".."))
    {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Identity selector carried a malformed link",
        ));
    }

    let business: HashSet<&str> = labels
        .iter()
        .filter(|(_, texts)| BUSINESS_IDENTITY_RE.is_match(&deaccent(&texts.join(" "))))
        .map(|(token, _)| token.as_str())
        .collect();

    let personal: Vec<&str> = order
        .iter()
        .map(String::as_str)
        .filter(|token| !business.contains(token))
        .collect();

    if personal.len() != 1 || business.is_empty() {
        return Err(AccountsFormatError::new(
            IDENTITY_UNSUPPORTED,
            format!(
                "Identity selector listed {} identities, {} without a business marker",
                labels.len(),
                personal.len()
            ),
        ));
    }
    Ok(format!("{IDENTITY_SWITCH_PATH}{}", personal[0]))
}

// ─── Trading board ──────────────────────────────────────────────────────────

static ISIN_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Z]{2}[A-Z0-9]{9}\d$").expect("isin regex"));

fn summary_money(
    raw: Option<&Value>,
    field: &str,
    required: bool,
) -> Result<Option<Decimal>, AccountsFormatError> {
    match raw {
        None | Some(Value::Null) if !required => Ok(None),
        Some(Value::Object(map)) => {
            let parsed = map.get("value").and_then(decimal_value);
            if parsed.is_none() && required {
                return Err(AccountsFormatError::new(
                    FORMAT_CHANGED,
                    format!("Trading field {field} has no usable value"),
                ));
            }
            Ok(parsed)
        }
        _ => Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            format!("Trading field {field} is not an object"),
        )),
    }
}

fn summary_currency(raw: Option<&Value>) -> Option<String> {
    let map = raw?.as_object()?;
    let currency = text_value(map.get("currency"), 3)?;
    Some(currency.to_uppercase())
}

fn position_isin(raw: &serde_json::Map<String, Value>) -> Option<String> {
    let isin = text_value(raw.get("isin"), 12)?.to_uppercase();
    if ISIN_RE.is_match(&isin) {
        Some(isin)
    } else {
        None
    }
}

fn parse_position(raw: &Value) -> Result<Option<PositionPayload>, AccountsFormatError> {
    let Some(map) = raw.as_object() else {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Trading position is not an object",
        ));
    };

    let isin = position_isin(map);
    let symbol = text_value(map.get("symbol"), 100).or_else(|| isin.clone());
    let label = text_value(map.get("label"), 200);
    let quantity = summary_money(map.get("quantity"), "quantity", true)?
        .expect("required summary_money returns Some");
    let (Some(symbol), Some(label)) = (symbol, label) else {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Trading position is missing symbol or label",
        ));
    };

    if quantity.is_zero() {
        return Ok(None);
    }

    let value_node = map.get("amount");
    let value_eur =
        summary_money(value_node, "amount", true)?.expect("required summary_money returns Some");
    let value_currency = summary_currency(value_node);
    if let Some(ref cur) = value_currency
        && cur != "EUR"
    {
        return Err(AccountsFormatError::new(
            INVALID_DATA,
            format!("Position {symbol} is valued in {cur}, not EUR"),
        ));
    }

    let gain_node = map.get("gainLoss");
    let gain_currency = summary_currency(gain_node);
    if let Some(ref cur) = gain_currency
        && cur != "EUR"
    {
        return Err(AccountsFormatError::new(
            INVALID_DATA,
            format!("Position {symbol} reports its gain in {cur}, not EUR"),
        ));
    }

    let last_node = map.get("last");
    let buying_node = map.get("buyingPrice");
    let buying_currency = summary_currency(buying_node);
    let buying_price_eur = if buying_currency.as_deref().is_none_or(|c| c == "EUR") {
        summary_money(buying_node, "buyingPrice", false)?
    } else {
        None
    };

    let quote_currency = summary_currency(last_node)
        .or_else(|| text_value(map.get("currency"), 3).map(|c| c.to_uppercase()));

    Ok(Some(PositionPayload {
        isin,
        symbol,
        label,
        quantity,
        buying_price_eur,
        current_price: summary_money(last_node, "last", false)?,
        quote_currency,
        current_value_eur: value_eur,
        pnl_eur: summary_money(gain_node, "gainLoss", false)?,
    }))
}

fn parse_single_fund(
    sections: &[&serde_json::Map<String, Value>],
    account: &serde_json::Map<String, Value>,
    account_id: &str,
) -> Result<TradingSummary, AccountsFormatError> {
    let mixed = ["cash", "valuation", "total"]
        .iter()
        .any(|field| account.contains_key(*field));
    let has_non_empty_lines = sections.iter().any(|section| {
        ["positions", "funds"].into_iter().any(|key| {
            section
                .get(key)
                .is_some_and(|v| !v.is_null() && v.as_array().is_none_or(|arr| !arr.is_empty()))
        })
    });
    if mixed || has_non_empty_lines {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Trading summary mixes a fund contract and a securities account",
        ));
    }

    let funds: Vec<&Value> = sections
        .iter()
        .filter_map(|section| section.get("fund"))
        .collect();
    if funds.len() != 1 {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            format!(
                "Fund contract carries {} fund nodes instead of one",
                funds.len()
            ),
        ));
    }
    let Some(fund) = funds[0].as_object() else {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Fund contract's fund node is not an object",
        ));
    };

    let short_id = &account_id[..account_id.len().min(8)];
    let balance_node = account.get("balance");
    let total =
        summary_money(balance_node, "balance", true)?.expect("required summary_money returns Some");
    if total.is_sign_negative() {
        return Err(AccountsFormatError::new(
            INVALID_DATA,
            format!("Fund contract {short_id}… reports a negative balance"),
        ));
    }
    for currency in [
        summary_currency(balance_node),
        text_value(account.get("currency"), 3),
    ]
    .into_iter()
    .flatten()
    {
        if !currency.eq_ignore_ascii_case("EUR") {
            return Err(AccountsFormatError::new(
                INVALID_DATA,
                format!("Fund contract {short_id}… is denominated in {currency}"),
            ));
        }
    }

    let gain_node = account.get("gainLoss");
    if let Some(gain_currency) = summary_currency(gain_node)
        && gain_currency != "EUR"
    {
        return Err(AccountsFormatError::new(
            INVALID_DATA,
            format!("Fund contract {short_id}… reports its gain in {gain_currency}"),
        ));
    }

    let isin = position_isin(fund);
    let label = text_value(fund.get("label"), 200);
    let quantity = fund.get("quantity").and_then(decimal_value);
    let (Some(isin), Some(label), Some(quantity)) = (isin, label, quantity) else {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Fund contract is missing its fund's ISIN, label or quantity",
        ));
    };
    if quantity.is_sign_negative() {
        return Err(AccountsFormatError::new(
            INVALID_DATA,
            "Fund contract reports a negative quantity",
        ));
    }

    let price_node = fund.get("price");
    let mut positions = Vec::new();
    if !quantity.is_zero() {
        positions.push(PositionPayload {
            symbol: isin.clone(),
            isin: Some(isin),
            label,
            quantity,
            buying_price_eur: None,
            current_price: summary_money(price_node, "price", false)?,
            quote_currency: Some(summary_currency(price_node).unwrap_or_else(|| "EUR".to_owned())),
            current_value_eur: total,
            pnl_eur: summary_money(gain_node, "gainLoss", false)?,
        });
    }

    let empty_balance = money_close(Decimal::ZERO, total);
    if positions.is_empty() && !empty_balance {
        return Err(AccountsFormatError::new(
            INCOMPLETE,
            format!("Fund contract {short_id}… has a balance but no units"),
        ));
    }
    if !positions.is_empty() && empty_balance {
        return Err(AccountsFormatError::new(
            INCOMPLETE,
            format!("Fund contract {short_id}… holds units but no balance"),
        ));
    }

    Ok(TradingSummary {
        cash_eur: Decimal::ZERO,
        total_eur: total,
        positions,
    })
}

pub fn parse_trading_summary(
    payload: &Value,
    account_id: &str,
) -> Result<TradingSummary, AccountsFormatError> {
    let single_slice = std::slice::from_ref(payload);
    let items: &[Value] = match payload {
        Value::Array(arr) => arr.as_slice(),
        _ => single_slice,
    };
    let sections: Vec<&serde_json::Map<String, Value>> =
        items.iter().filter_map(Value::as_object).collect();
    if sections.is_empty() {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Trading summary is empty",
        ));
    }

    let account = sections
        .iter()
        .find_map(|section| section.get("account").and_then(Value::as_object))
        .ok_or_else(|| {
            AccountsFormatError::new(FORMAT_CHANGED, "Trading summary has no account node")
        })?;

    if sections.iter().any(|section| section.contains_key("fund")) {
        return parse_single_fund(&sections, account, account_id);
    }

    let cash = summary_money(account.get("cash"), "cash", true)?.expect("cash");
    let valuation = summary_money(account.get("valuation"), "valuation", true)?.expect("valuation");
    let total = summary_money(account.get("total"), "total", true)?.expect("total");

    let short_id = &account_id[..account_id.len().min(8)];
    if let Some(currency) = summary_currency(account.get("total"))
        && currency != "EUR"
    {
        return Err(AccountsFormatError::new(
            INVALID_DATA,
            format!("Trading account {short_id}… is denominated in {currency}"),
        ));
    }

    // Collect both `positions` and `funds` lists across sections: managed/profiled
    // PEA accounts return an empty `positions` list and their holdings under `funds` (#398).
    let position_lists: Vec<&Vec<Value>> = sections
        .iter()
        .flat_map(|section| {
            ["positions", "funds"]
                .into_iter()
                .filter_map(|key| section.get(key).and_then(Value::as_array))
        })
        .collect();

    // Pick the first list with maximum length (matches Python's `max(position_lists, key=len)`).
    let mut raw_count: usize = 0;
    let mut positions: Vec<PositionPayload> = Vec::new();
    if let Some(richest) = position_lists
        .iter()
        .copied()
        .fold(None::<&Vec<Value>>, |best, cur| match best {
            None => Some(cur),
            Some(b) if cur.len() > b.len() => Some(cur),
            Some(b) => Some(b),
        })
    {
        raw_count = richest.len();
        for item in richest {
            if let Some(parsed) = parse_position(item)? {
                positions.push(parsed);
            }
        }
    }

    let mut lines_total = checked_sum(positions.iter().map(|p| p.current_value_eur))?;

    if position_lists.len() > 1 && !money_close(lines_total, valuation) {
        let union_raw_len: usize = position_lists.iter().map(|l| l.len()).sum();
        let mut unique_order: Vec<(u8, String)> = Vec::new();
        let mut unique: HashMap<(u8, String), PositionPayload> = HashMap::new();
        let mut symbol_has_isin: HashMap<String, bool> = HashMap::new();

        for list in &position_lists {
            for item in *list {
                let Some(parsed) = parse_position(item)? else {
                    continue;
                };
                let symbol_upper = parsed.symbol.to_uppercase();
                let has_isin = parsed.isin.is_some();
                if let Some(&prev) = symbol_has_isin.get(&symbol_upper)
                    && prev != has_isin
                {
                    return Err(AccountsFormatError::new(
                        INVALID_DATA,
                        "Trading summary has mixed ISIN availability for one symbol",
                    ));
                }
                symbol_has_isin.insert(symbol_upper.clone(), has_isin);

                let key = match &parsed.isin {
                    Some(isin) => (0u8, isin.clone()),
                    None => (1u8, symbol_upper),
                };
                if let Some(existing) = unique.get(&key) {
                    if existing != &parsed {
                        return Err(AccountsFormatError::new(
                            INVALID_DATA,
                            "Trading summary has conflicting duplicate positions",
                        ));
                    }
                } else {
                    unique_order.push(key.clone());
                    unique.insert(key, parsed);
                }
            }
        }

        let union: Vec<PositionPayload> = unique_order
            .into_iter()
            .filter_map(|k| unique.remove(&k))
            .collect();
        let union_total = checked_sum(union.iter().map(|p| p.current_value_eur))?;
        if money_close(union_total, valuation) {
            raw_count = union_raw_len;
            positions = union;
            lines_total = union_total;
        }
    }

    let cash_plus_valuation = checked_add(cash, valuation)?;
    let mut shape = format!(
        "account {short_id}…; {} of {raw_count} line(s); cash+valuation vs total={}; lines vs valuation={}",
        positions.len(),
        relative_difference(cash_plus_valuation, total),
        relative_difference(lines_total, valuation),
    );
    if raw_count == 0 {
        shape.push_str(&format!("; payload={}", describe_payload(payload, 0)));
    }
    if !money_close(cash_plus_valuation, total) {
        return Err(AccountsFormatError::new(
            INCOMPLETE,
            format!("Trading account total does not reconcile ({shape})"),
        ));
    }
    if !money_close(lines_total, valuation) {
        return Err(AccountsFormatError::new(
            INCOMPLETE,
            format!("Trading positions do not reconcile with the valuation ({shape})"),
        ));
    }

    Ok(TradingSummary {
        cash_eur: cash,
        total_eur: total,
        positions,
    })
}

/// Refuse two ISIN-less lines that would collapse onto the same ticker downstream.
pub fn guard_symbol_collisions(positions: &[PositionPayload]) -> Result<(), AccountsFormatError> {
    let mut fallbacks: HashSet<String> = HashSet::new();
    for position in positions {
        if position.isin.is_some() {
            continue;
        }
        let fallback = position.symbol.to_uppercase();
        if !fallbacks.insert(fallback.clone()) {
            return Err(AccountsFormatError::new(
                INVALID_DATA,
                format!("Two positions without an ISIN share the symbol {fallback}"),
            ));
        }
    }
    Ok(())
}

// ─── Assurance vie ─────────────────────────────────────────────────────────

static LI_VALUATION_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r#"(?s)c-databox__name">\s*Valorisation au\s*(?P<date>\d{2}/\d{2}/\d{4})\s*</div>\s*<div class="c-databox__value[^"]*">\s*(?P<amount>[^<]+?)\s*</div>"#,
    )
    .expect("li valuation regex")
});
static LI_TABLE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?s)<table\b[^>]*data-table-trading-operations[^>]*>(?P<body>.*?)</table>")
        .expect("li table regex")
});
static LI_TBODY_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?s)<tbody\b[^>]*>(?P<rows>.*?)</tbody>").unwrap());
static LI_ROW_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?s)<tr\b[^>]*>(?P<row>.*?)</tr>").unwrap());
static LI_CELL_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?s)<td\b[^>]*>(?P<cell>.*?)</td>").unwrap());
static LI_LABEL_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)class="c-link__label[^"]*">(?P<label>.*?)</span>"#).unwrap()
});
static LI_MENTION_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)class="c-table__mention[^"]*">(?P<mention>.*?)</span>"#).unwrap()
});
static LI_HREF_DOUBLE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?s)href="(?P<url>[^"]*)""#).unwrap());
static LI_HREF_SINGLE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?s)href='(?P<url>[^']*)'").unwrap());
static LI_EURO_FUND_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\bEUROS?\b").unwrap());

fn java_length(text: &str) -> usize {
    text.encode_utf16().count()
}

fn li_symbol(quote_symbol: Option<&str>, isin: Option<&str>, label: &str) -> String {
    if let Some(sym) = quote_symbol
        && !sym.is_empty()
        && java_length(sym) <= LI_MAX_FALLBACK_SYMBOL
    {
        return sym.to_owned();
    }
    if let Some(isin) = isin {
        return isin.to_owned();
    }
    if java_length(label) <= LI_MAX_FALLBACK_SYMBOL {
        return label.trim_end().to_owned();
    }
    let mut prefix = String::new();
    for ch in label.chars() {
        let next_len = java_length(&prefix) + ch.len_utf16();
        if next_len > LI_LABEL_PREFIX {
            break;
        }
        prefix.push(ch);
    }
    let digest = hex::encode(Sha256::digest(label.as_bytes()));
    format!("{}-{}", prefix.trim_end(), &digest[..LI_DIGEST_LENGTH])
}

/// Match `(?:/(?!cours/)[^/]*)*/cours/(?P<symbol>[^/]+)/?` against the URL path alone.
fn extract_cours_symbol_from_path(path: &str) -> Option<String> {
    if !path.starts_with('/') {
        return None;
    }
    let trimmed = path.strip_suffix('/').unwrap_or(path);
    let segments: Vec<&str> = trimmed.split('/').skip(1).collect();
    // Find the first occurrence of "cours".
    let cours_idx = segments.iter().position(|&s| s == "cours")?;
    // There must be exactly one segment after "cours" and it must be non-empty.
    if cours_idx + 2 != segments.len() {
        return None;
    }
    let symbol = segments[cours_idx + 1];
    if symbol.is_empty() {
        return None;
    }
    Some(symbol.to_owned())
}

fn url_path_of(raw_url: &str) -> String {
    let trimmed = raw_url.trim();
    if let Ok(parsed) = Url::parse(trimmed) {
        return parsed.path().to_owned();
    }
    if let Some(without_scheme_rel) = trimmed.strip_prefix("//")
        && let Ok(parsed) = Url::parse(&format!("https://{without_scheme_rel}"))
    {
        return parsed.path().to_owned();
    }
    // Relative URL: strip query and fragment.
    let before_frag = trimmed.split('#').next().unwrap_or(trimmed);
    let before_query = before_frag.split('?').next().unwrap_or(before_frag);
    before_query.to_owned()
}

fn li_quote_symbol(cell: &str, subject: &str) -> Result<Option<String>, AccountsFormatError> {
    let mut symbols: HashSet<String> = HashSet::new();
    for pat in [&*LI_HREF_DOUBLE_RE, &*LI_HREF_SINGLE_RE] {
        for cap in pat.captures_iter(cell) {
            let unescaped = html_unescape(cap.name("url").unwrap().as_str());
            let path = url_path_of(&unescaped);
            if let Some(sym) = extract_cours_symbol_from_path(&path) {
                symbols.insert(sym);
            }
        }
    }
    if symbols.len() > 1 {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            format!("{subject} line has quote links naming different symbols"),
        ));
    }
    if symbols.is_empty() && html_unescape(cell).contains("/cours/") {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            format!("{subject} line has a quote link without a readable symbol"),
        ));
    }
    Ok(symbols.into_iter().next())
}

fn li_amount(
    cell: &str,
    field: &str,
    required: bool,
    subject: &str,
) -> Result<Option<Decimal>, AccountsFormatError> {
    let text = clean_text(cell, 100);
    let Some(text) = text.filter(|t| t != "-" && t != "—") else {
        if required {
            return Err(AccountsFormatError::new(
                FORMAT_CHANGED,
                format!("{subject} line has no {field}"),
            ));
        }
        return Ok(None);
    };
    let stripped = text.replace(['€', '%'], "");
    let Some(value) = parse_amount(&stripped) else {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            format!("{subject} line has an unreadable {field}"),
        ));
    };
    Ok(Some(value))
}

pub fn parse_life_insurance(html: &str) -> Result<LineValuedSummary, AccountsFormatError> {
    let (Some(valuation), Some(table)) =
        (LI_VALUATION_RE.captures(html), LI_TABLE_RE.captures(html))
    else {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Assurance-vie page has no valuation or no supports table",
        ));
    };
    let total_text = clean_text(valuation.name("amount").unwrap().as_str(), 100);
    let total = total_text
        .as_deref()
        .and_then(|t| parse_amount(&t.replace('€', "")))
        .ok_or_else(|| {
            AccountsFormatError::new(FORMAT_CHANGED, "Assurance-vie valuation is unreadable")
        })?;

    let body_html = LI_TBODY_RE
        .captures(table.name("body").unwrap().as_str())
        .map(|c| c.name("rows").unwrap().as_str())
        .unwrap_or("");
    let rows: Vec<&str> = LI_ROW_RE
        .captures_iter(body_html)
        .map(|c| c.name("row").unwrap().as_str())
        .collect();

    let mut positions = Vec::new();
    let mut cash = Decimal::ZERO;

    for row in &rows {
        let cells: Vec<&str> = LI_CELL_RE
            .captures_iter(row)
            .map(|c| c.name("cell").unwrap().as_str())
            .collect();
        if cells.len() < 7 {
            return Err(AccountsFormatError::new(
                FORMAT_CHANGED,
                format!("Assurance-vie line has {} cells, expected 8", cells.len()),
            ));
        }
        let label_source = LI_LABEL_RE
            .captures(cells[0])
            .map(|c| c.name("label").unwrap().as_str())
            .unwrap_or(cells[0]);
        let full_label =
            clean_text(label_source, label_source.chars().count()).ok_or_else(|| {
                AccountsFormatError::new(FORMAT_CHANGED, "Assurance-vie line has no label")
            })?;
        let isin = LI_MENTION_RE
            .captures(cells[0])
            .and_then(|c| clean_text(c.name("mention").unwrap().as_str(), 20))
            .filter(|m| ISIN_RE.is_match(m));
        let symbol = li_quote_symbol(cells[0], "Assurance-vie")?;
        let value = li_amount(cells[5], "amount", true, "Assurance-vie")?.expect("amount");
        let quantity = li_amount(cells[2], "quantity", false, "Assurance-vie")?;
        let price = li_amount(cells[4], "price", false, "Assurance-vie")?;
        let label: String = full_label.chars().take(200).collect();

        if quantity.is_none_or(|q| q.is_zero()) {
            let is_euro_fund = isin.is_none()
                && symbol.is_none()
                && price.is_none_or(|p| p.is_zero())
                && LI_EURO_FUND_RE.is_match(&deaccent(&label));
            if is_euro_fund {
                cash = checked_add(cash, value)?;
                continue;
            }
            if quantity.is_none() || !value.is_zero() {
                return Err(AccountsFormatError::new(
                    FORMAT_CHANGED,
                    "Assurance-vie line has no units and is not a euro fund",
                ));
            }
        }

        let resolved_symbol = li_symbol(symbol.as_deref(), isin.as_deref(), &full_label);
        positions.push(PositionPayload {
            isin,
            symbol: resolved_symbol,
            label,
            quantity: quantity.unwrap_or(Decimal::ZERO),
            buying_price_eur: li_amount(cells[3], "buying price", false, "Assurance-vie")?,
            current_price: price,
            quote_currency: Some("EUR".to_owned()),
            current_value_eur: value,
            pnl_eur: li_amount(cells[6], "gain", false, "Assurance-vie")?,
        });
    }

    let lines_total = positions
        .iter()
        .map(|p| p.current_value_eur)
        .try_fold(cash, checked_add)?;
    if !lines_close(lines_total, total, rows.len()) {
        return Err(AccountsFormatError::new(
            INCOMPLETE,
            format!(
                "Assurance-vie lines do not reconcile with the valuation ({} line(s); lines vs valuation={})",
                positions.len(),
                relative_difference(lines_total, total)
            ),
        ));
    }

    Ok(LineValuedSummary {
        cash_eur: cash,
        total_eur: total,
        positions,
        valuation_line_count: rows.len(),
    })
}

// ─── PER ───────────────────────────────────────────────────────────────────

static PER_SOLDE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"\bSolde au \d{2}/\d{2}/\d{4} (?P<amount>[-\u{2212}]?\d[\d ]*,\d{2}) ?€")
        .expect("per solde regex")
});
static PER_HEADER_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?s)<th\b[^>]*>(?P<head>.*?)</th>").unwrap());
static PER_TOTAL_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\s*TOTAL\s*:?\s*$").unwrap());

const PER_HEADER: [&str; PER_CELLS] = ["VALEUR", "QUANTIT", "PX", "COURS", "+/-", "+/-", "MONTANT"];

fn per_pending(cell: &str) -> bool {
    let text = clean_text(cell, 30).unwrap_or_default();
    deaccent(&text).trim() == "EN COURS"
}

fn per_compartment(table: &str) -> bool {
    let heads: Vec<String> = PER_HEADER_RE
        .captures_iter(table)
        .map(|cap| {
            let text = clean_text(cap.name("head").unwrap().as_str(), 40).unwrap_or_default();
            deaccent(&text).trim().to_owned()
        })
        .collect();
    heads.len() == PER_CELLS
        && heads
            .iter()
            .zip(PER_HEADER.iter())
            .all(|(h, want)| h.starts_with(want))
}

fn per_amount(
    cell: &str,
    field: &str,
    required: bool,
) -> Result<Option<Decimal>, AccountsFormatError> {
    li_amount(cell, field, required, "PER")
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
enum PerKey {
    Isin(String),
    Unique(usize),
}

fn per_merge(
    merged: &mut BTreeMap<PerKey, PositionPayload>,
    order: &mut Vec<PerKey>,
    unique_seq: &mut usize,
    line: PositionPayload,
) -> Result<(), AccountsFormatError> {
    let key = match &line.isin {
        Some(isin) => PerKey::Isin(isin.clone()),
        None => {
            *unique_seq += 1;
            PerKey::Unique(*unique_seq)
        }
    };
    let Some(held) = merged.get_mut(&key) else {
        order.push(key.clone());
        merged.insert(key, line);
        return Ok(());
    };
    let quantity = held
        .quantity
        .checked_add(line.quantity)
        .ok_or_else(|| AccountsFormatError::new(INVALID_DATA, "amount out of range"))?;
    held.buying_price_eur = match (held.buying_price_eur, line.buying_price_eur) {
        (Some(left), Some(right)) if !quantity.is_zero() => {
            let left_cost = left
                .checked_mul(held.quantity)
                .ok_or_else(|| AccountsFormatError::new(INVALID_DATA, "amount out of range"))?;
            let right_cost = right
                .checked_mul(line.quantity)
                .ok_or_else(|| AccountsFormatError::new(INVALID_DATA, "amount out of range"))?;
            let total_cost = left_cost
                .checked_add(right_cost)
                .ok_or_else(|| AccountsFormatError::new(INVALID_DATA, "amount out of range"))?;
            let avg = total_cost
                .checked_div(quantity)
                .ok_or_else(|| AccountsFormatError::new(INVALID_DATA, "amount out of range"))?;
            Some(avg.round_dp_with_strategy(8, RoundingStrategy::MidpointAwayFromZero))
        }
        _ => None,
    };
    if held.current_price.is_none() {
        held.current_price = line.current_price;
    }
    held.pnl_eur = match (held.pnl_eur, line.pnl_eur) {
        (Some(a), Some(b)) => Some(
            a.checked_add(b)
                .ok_or_else(|| AccountsFormatError::new(INVALID_DATA, "amount out of range"))?,
        ),
        _ => None,
    };
    held.quantity = quantity;
    held.current_value_eur = held
        .current_value_eur
        .checked_add(line.current_value_eur)
        .ok_or_else(|| AccountsFormatError::new(INVALID_DATA, "amount out of range"))?;
    Ok(())
}

pub fn parse_per(html: &str) -> Result<LineValuedSummary, AccountsFormatError> {
    let plain = plain_text(html);
    let soldes: HashSet<Option<Decimal>> = PER_SOLDE_RE
        .captures_iter(&plain)
        .map(|cap| parse_amount(cap.name("amount").unwrap().as_str()))
        .collect();
    let solde = if soldes.len() == 1 {
        soldes.into_iter().next().flatten()
    } else {
        None
    }
    .ok_or_else(|| {
        AccountsFormatError::new(FORMAT_CHANGED, "PER page has no single readable Solde")
    })?;

    let tables: Vec<&str> = LI_TABLE_RE
        .captures_iter(html)
        .map(|c| c.name("body").unwrap().as_str())
        .filter(|body| per_compartment(body))
        .collect();
    if tables.is_empty() {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "PER page has no supports table",
        ));
    }

    let mut merged: BTreeMap<PerKey, PositionPayload> = BTreeMap::new();
    let mut order: Vec<PerKey> = Vec::new();
    let mut unique_seq: usize = 0;
    let mut cash = Decimal::ZERO;
    let mut line_count: usize = 0;
    let mut pending_lines: usize = 0;
    let mut lines_sum = Decimal::ZERO;
    let mut totals = Decimal::ZERO;

    for (idx, table) in tables.iter().enumerate() {
        let number = idx + 1;
        let mut compartment_total: Option<Decimal> = None;
        let mut compartment_lines = Decimal::ZERO;
        let mut lines: usize = 0;
        let mut pending: usize = 0;

        for row_cap in LI_ROW_RE.captures_iter(table) {
            let cells: Vec<&str> = LI_CELL_RE
                .captures_iter(row_cap.name("row").unwrap().as_str())
                .map(|c| c.name("cell").unwrap().as_str())
                .collect();
            if cells.is_empty() {
                continue;
            }
            if compartment_total.is_some() {
                return Err(AccountsFormatError::new(
                    FORMAT_CHANGED,
                    "PER table has a row after its total",
                ));
            }
            let first_cell_text = clean_text(cells[0], 30).unwrap_or_default();
            if PER_TOTAL_RE.is_match(&deaccent(&first_cell_text)) {
                compartment_total = per_amount(cells[cells.len() - 1], "total", true)?;
                continue;
            }
            if cells.len() != PER_CELLS {
                return Err(AccountsFormatError::new(
                    FORMAT_CHANGED,
                    format!("PER line has {} cells, expected {PER_CELLS}", cells.len()),
                ));
            }
            let value = per_amount(cells[6], "amount", true)?.expect("amount");
            lines += 1;
            compartment_lines = checked_add(compartment_lines, value)?;

            if per_pending(cells[1]) {
                per_amount(cells[3], "price", false)?;
                per_amount(cells[4], "gain", false)?;
                if !per_pending(cells[2]) {
                    per_amount(cells[2], "buying price", false)?;
                }
                if !per_pending(cells[5]) {
                    per_amount(cells[5], "gain percentage", false)?;
                }
                pending += 1;
                cash = checked_add(cash, value)?;
                continue;
            }

            let label_source = LI_LABEL_RE
                .captures(cells[0])
                .map(|c| c.name("label").unwrap().as_str())
                .unwrap_or(cells[0]);
            let full_label = clean_text(label_source, label_source.chars().count())
                .ok_or_else(|| AccountsFormatError::new(FORMAT_CHANGED, "PER line has no label"))?;
            let isin = LI_MENTION_RE
                .captures(cells[0])
                .and_then(|c| clean_text(c.name("mention").unwrap().as_str(), 20))
                .filter(|m| ISIN_RE.is_match(m));
            let quantity = per_amount(cells[1], "quantity", false)?;
            let buying = per_amount(cells[2], "buying price", false)?;
            let price = per_amount(cells[3], "price", false)?;
            let gain = per_amount(cells[4], "gain", false)?;
            per_amount(cells[5], "gain percentage", false)?;
            let symbol = li_quote_symbol(cells[0], "PER")?;

            let Some(quantity) = quantity else {
                if isin.is_none()
                    && symbol.is_none()
                    && price.is_none_or(|p| p.is_zero())
                    && LI_EURO_FUND_RE.is_match(&deaccent(&full_label))
                {
                    cash = checked_add(cash, value)?;
                    continue;
                }
                return Err(AccountsFormatError::new(
                    FORMAT_CHANGED,
                    "PER line has no units and is not a euro fund",
                ));
            };
            if quantity.is_sign_negative() {
                return Err(AccountsFormatError::new(
                    INVALID_DATA,
                    "PER line reports a negative quantity",
                ));
            }
            if quantity.is_zero() {
                if !value.is_zero() {
                    return Err(AccountsFormatError::new(
                        FORMAT_CHANGED,
                        "PER line has a value but no units",
                    ));
                }
                continue;
            }

            let resolved_symbol = li_symbol(symbol.as_deref(), isin.as_deref(), &full_label);
            per_merge(
                &mut merged,
                &mut order,
                &mut unique_seq,
                PositionPayload {
                    isin,
                    symbol: resolved_symbol,
                    label: full_label.chars().take(200).collect(),
                    quantity,
                    buying_price_eur: buying,
                    current_price: price,
                    quote_currency: Some("EUR".to_owned()),
                    current_value_eur: value,
                    pnl_eur: gain,
                },
            )?;
        }

        let Some(compartment_total) = compartment_total else {
            return Err(AccountsFormatError::new(
                FORMAT_CHANGED,
                "PER table has no total row",
            ));
        };
        if !lines_close(compartment_lines, compartment_total, lines) {
            return Err(AccountsFormatError::new(
                INCOMPLETE,
                format!(
                    "PER compartment {number} of {} does not reconcile with its total ({lines} line(s), {pending} pending; lines vs total={})",
                    tables.len(),
                    relative_difference(compartment_lines, compartment_total)
                ),
            ));
        }
        totals = checked_add(totals, compartment_total)?;
        line_count += lines;
        pending_lines += pending;
        lines_sum = checked_add(lines_sum, compartment_lines)?;
    }

    let shape = format!(
        "{} compartment(s), {line_count} line(s), {pending_lines} pending",
        tables.len()
    );
    if !lines_close(totals, solde, tables.len()) {
        return Err(AccountsFormatError::new(
            INCOMPLETE,
            format!(
                "PER compartment totals do not reconcile with the Solde ({shape}; totals vs Solde={})",
                relative_difference(totals, solde)
            ),
        ));
    }
    if !lines_close(lines_sum, solde, line_count) {
        return Err(AccountsFormatError::new(
            INCOMPLETE,
            format!(
                "PER lines do not reconcile with the Solde ({shape}; lines vs Solde={})",
                relative_difference(lines_sum, solde)
            ),
        ));
    }

    let positions: Vec<PositionPayload> = order
        .into_iter()
        .filter_map(|k| merged.remove(&k))
        .collect();
    let distinct_symbols: HashSet<String> =
        positions.iter().map(|p| p.symbol.to_uppercase()).collect();
    if distinct_symbols.len() != positions.len() {
        return Err(AccountsFormatError::new(
            INVALID_DATA,
            "Two PER holdings share a ticker",
        ));
    }

    // Sanity check `line_count` fits in i32 for downstream `valuationLineCount`.
    let _ = i32::try_from(line_count)
        .ok()
        .and_then(|v| Decimal::from(v).to_i32())
        .ok_or_else(|| AccountsFormatError::new(FORMAT_CHANGED, "PER line count overflow"))?;

    Ok(LineValuedSummary {
        cash_eur: cash,
        total_eur: solde,
        positions,
        valuation_line_count: line_count,
    })
}
