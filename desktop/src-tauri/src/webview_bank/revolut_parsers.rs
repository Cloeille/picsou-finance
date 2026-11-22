//! Pure parsers for Revolut retail API JSON (1:1 port of `services/revolut-auth/main.py`).
//!
//! No I/O and no browser here: `revolut.rs` fetches, these functions turn responses into the
//! normalized harvest shape `backend-rs/src/connectors/revolut/adapter.rs` expects. All monetary
//! amounts use `rust_decimal::Decimal` end to end with zero binary floats, and serialize as JSON
//! numbers (`arbitrary_precision`) as `adapter.rs` requires.

use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::str::FromStr;
use std::sync::LazyLock;

use chrono::{DateTime, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use serde::Serialize;
use serde_json::Value;

pub const TRANSACTION_WINDOW_DAYS: i64 = 90;
pub const MAX_TRANSACTION_PAGES: usize = 500;
const DAY_MS: i64 = 24 * 3600 * 1000;
const NOISE_POCKET_TYPES: &[&str] = &["MERCHANT", "REVX_FIAT"];
const FIAT_FALLBACK: &[&str] = &[
    "EUR", "USD", "GBP", "CHF", "JPY", "CAD", "AUD", "SEK", "NOK", "DKK", "PLN", "CZK", "HUF",
    "RON", "BGN", "TRY", "ZAR", "SGD", "HKD", "NZD", "MXN", "ILS", "AED", "THB",
];

static CURRENCY_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Z0-9]{1,10}$").expect("currency regex"));
static FIAT_CODE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Z]{3}$").expect("fiat code regex"));

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParseError(pub &'static str);

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RevolutTxnPayload {
    pub external_id: String,
    pub date: String,
    pub description: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub amount: Decimal,
    pub currency: Option<String>,
    pub counterparty: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(skip)]
    pub source_type: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RevolutAccountPayload {
    pub external_id: String,
    pub name: String,
    #[serde(rename = "type")]
    pub account_type: String,
    pub iban: Option<String>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub balance: Decimal,
    pub currency: String,
    pub parent_external_id: Option<String>,
    pub transactions: Vec<RevolutTxnPayload>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct RevolutHarvestPayload {
    pub accounts: Vec<RevolutAccountPayload>,
}

pub fn loads(input: &str) -> Result<Value, ParseError> {
    serde_json::from_str(input).map_err(|_| ParseError("INVALID_DATA"))
}

pub fn fiat_fallback() -> HashSet<String> {
    FIAT_FALLBACK.iter().map(|s| (*s).to_owned()).collect()
}

fn pick<'a>(obj: &'a serde_json::Map<String, Value>, keys: &[&str]) -> Option<&'a Value> {
    for &key in keys {
        if let Some(val) = obj.get(key) {
            if !val.is_null() {
                return Some(val);
            }
        }
    }
    None
}

fn pick_str(obj: &serde_json::Map<String, Value>, keys: &[&str]) -> Option<String> {
    pick(obj, keys).and_then(Value::as_str).map(str::to_owned)
}

fn pick_non_empty_str(obj: &serde_json::Map<String, Value>, keys: &[&str]) -> Option<String> {
    pick_str(obj, keys).filter(|s| !s.is_empty())
}

/// Converts Revolut integer minor units (cents) into major units as an exact `Decimal`.
pub fn minor_to_major(value: Option<&Value>) -> Decimal {
    let Some(val) = value else {
        return Decimal::ZERO;
    };
    let parsed = match val {
        Value::Number(n) => Decimal::from_str(&n.to_string())
            .or_else(|_| Decimal::from_scientific(&n.to_string()))
            .ok(),
        Value::String(s) => Decimal::from_str(s.trim())
            .or_else(|_| Decimal::from_scientific(s.trim()))
            .ok(),
        _ => None,
    };
    parsed
        .map(|d| (d / Decimal::from(100)).normalize())
        .unwrap_or(Decimal::ZERO)
}

/// A leg's own currency. Absent means the pocket's; anything malformed fails closed.
pub fn transaction_currency(raw: Option<&Value>) -> Result<Option<String>, ParseError> {
    match raw {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(s)) => {
            let normalized = s.trim().to_ascii_uppercase();
            if CURRENCY_RE.is_match(&normalized) {
                Ok(Some(normalized))
            } else {
                Err(ParseError("INVALID_DATA"))
            }
        }
        Some(_) => Err(ParseError("INVALID_DATA")),
    }
}

/// Fiat legs are cents (100); crypto legs are 1e-8 units (100_000_000).
pub fn transaction_major(
    amount: Decimal,
    currency: Option<&str>,
    fiat: &HashSet<String>,
) -> Decimal {
    let divisor = match currency {
        None => Decimal::from(100),
        Some(c) if fiat.contains(c) => Decimal::from(100),
        Some(_) => Decimal::from(100_000_000i64),
    };
    (amount / divisor).normalize()
}

/// Strict millisecond timestamp: positive finite integer/decimal within chrono's UTC range.
pub fn timestamp_ms(value: Option<&Value>) -> Option<i64> {
    let Value::Number(n) = value? else {
        return None;
    };
    let ms = if let Some(i) = n.as_i64() {
        i
    } else {
        let dec = Decimal::from_str(&n.to_string())
            .or_else(|_| Decimal::from_scientific(&n.to_string()))
            .ok()?;
        let trunc = dec.trunc();
        i64::try_from(trunc.mantissa()).ok()?
    };
    if ms <= 0 {
        return None;
    }
    // Ensure chrono can format it as a valid calendar date.
    DateTime::<Utc>::from_timestamp_millis(ms)?;
    Some(ms)
}

fn format_utc_date(ts_ms: i64) -> Option<String> {
    let dt = DateTime::<Utc>::from_timestamp_millis(ts_ms)?;
    Some(dt.format("%Y-%m-%d").to_string())
}

/// Parses `/api/retail/currencies?type=fiat`; falls back to `FIAT_FALLBACK` unless `"EUR"` is present.
pub fn parse_fiat_currencies(data: Option<&Value>) -> HashSet<String> {
    let mut codes = HashSet::new();
    if let Some(val) = data {
        let items: Vec<&Value> = match val {
            Value::Array(arr) => arr.iter().collect(),
            Value::Object(map) => map.values().collect(),
            _ => Vec::new(),
        };
        for item in items {
            let candidate = match item {
                Value::String(s) => Some(s.as_str()),
                Value::Object(map) => {
                    pick(map, &["code", "currency", "isoCode", "id"]).and_then(Value::as_str)
                }
                _ => None,
            };
            if let Some(code) = candidate {
                let norm = code.trim().to_ascii_uppercase();
                if FIAT_CODE_RE.is_match(&norm) {
                    codes.insert(norm);
                }
            }
        }
    }
    if codes.contains("EUR") {
        codes
    } else {
        fiat_fallback()
    }
}

/// Extracts `(wallet_id, pockets)` entries from `/api/retail/wallets` (grouped object) or
/// `/api/retail/user/current/wallet` (single wallet object).
pub fn parse_wallets_grouped(data: &Value) -> Vec<(Option<String>, Vec<Value>)> {
    let mut out = Vec::new();
    let Some(map) = data.as_object() else {
        return out;
    };
    for group in map.values() {
        let Some(wallets) = group.as_array() else {
            continue;
        };
        for wallet in wallets {
            let Some(w) = wallet.as_object() else {
                continue;
            };
            let wallet_id = pick_non_empty_str(w, &["id", "walletId"]);
            let mut pockets = w
                .get("pockets")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            if let Some(shared) = w.get("sharedPockets").and_then(Value::as_array) {
                pockets.extend(shared.iter().cloned());
            }
            out.push((wallet_id, pockets));
        }
    }
    out
}

pub fn parse_single_wallet(data: &Value) -> Vec<(Option<String>, Vec<Value>)> {
    let Some(w) = data.as_object() else {
        return Vec::new();
    };
    let Some(pockets) = w.get("pockets").and_then(Value::as_array) else {
        return Vec::new();
    };
    if pockets.is_empty() {
        return Vec::new();
    }
    vec![(pick_non_empty_str(w, &["id", "walletId"]), pockets.clone())]
}

/// Deduplicates money-box objects across personal / joint / default queries.
pub fn collect_money_boxes(responses: &[Value]) -> Vec<Value> {
    let mut seen = HashSet::new();
    let mut boxes = Vec::new();
    for data in responses {
        let list = match data {
            Value::Array(arr) => arr.as_slice(),
            Value::Object(map) => map
                .get("moneyBoxes")
                .and_then(Value::as_array)
                .map_or(&[][..], Vec::as_slice),
            _ => &[][..],
        };
        for mb in list {
            let Some(obj) = mb.as_object() else {
                continue;
            };
            let Some(mb_id) = pick_non_empty_str(obj, &["id"]) else {
                continue;
            };
            if seen.insert(mb_id) {
                boxes.push(mb.clone());
            }
        }
    }
    boxes
}

pub fn parse_iban(data: &Value) -> Option<String> {
    let obj = data.as_object()?;
    pick_non_empty_str(obj, &["iban", "IBAN"])
}

pub fn parse_transactions_page(data: &Value) -> Result<&[Value], ParseError> {
    match data {
        Value::Array(arr) => Ok(arr.as_slice()),
        Value::Object(map) => map
            .get("transactions")
            .and_then(Value::as_array)
            .map(Vec::as_slice)
            .ok_or(ParseError("INVALID_DATA")),
        _ => Err(ParseError("INVALID_DATA")),
    }
}

/// Pages `/api/retail/user/current/transactions/last` over the trailing 90-day window.
pub async fn fetch_transactions_with<F, Fut>(
    mut fetch_page: F,
    now_ms: i64,
    fiat: &HashSet<String>,
) -> Result<Vec<RevolutTxnPayload>, ParseError>
where
    F: FnMut(i64) -> Fut,
    Fut: Future<Output = Result<Value, ParseError>>,
{
    let cutoff = now_ms - TRANSACTION_WINDOW_DAYS * DAY_MS;
    let mut out = Vec::new();
    let mut seen: HashSet<(String, Option<String>)> = HashSet::new();
    let mut to = now_ms;
    let mut largest_page: usize = 0;
    let mut paged_on_booking_date = false;

    for page_number in 0..MAX_TRANSACTION_PAGES {
        let raw_page = fetch_page(to).await?;
        let batch = parse_transactions_page(&raw_page)?;
        if batch.is_empty() {
            break;
        }
        largest_page = largest_page.max(batch.len());
        let mut new_count: usize = 0;
        let mut oldest: i64 = i64::MAX;

        for t in batch {
            let obj = t.as_object().ok_or(ParseError("INVALID_DATA"))?;
            let tid = match obj.get("id") {
                Some(Value::String(s)) if !s.trim().is_empty() => s.clone(),
                _ => return Err(ParseError("INVALID_DATA")),
            };
            let ts = timestamp_ms(pick(obj, &["completedDate", "startedDate"]))
                .ok_or(ParseError("INVALID_DATA"))?;
            let date = format_utc_date(ts).ok_or(ParseError("INVALID_DATA"))?;

            let created = match obj.get("createdDate") {
                None | Some(Value::Null) => {
                    paged_on_booking_date = true;
                    ts
                }
                Some(v) => timestamp_ms(Some(v)).ok_or(ParseError("INVALID_DATA"))?,
            };
            oldest = oldest.min(created);

            let merchant_name = match obj.get("merchant") {
                None | Some(Value::Null) => None,
                Some(Value::Object(m)) => pick_str(m, &["name"]),
                Some(_) => return Err(ParseError("INVALID_DATA")),
            };

            let raw_amount = match obj.get("amount") {
                None | Some(Value::Null) => Decimal::ZERO,
                Some(Value::Number(n)) => Decimal::from_str(&n.to_string())
                    .or_else(|_| Decimal::from_scientific(&n.to_string()))
                    .map_err(|_| ParseError("INVALID_DATA"))?,
                Some(_) => return Err(ParseError("INVALID_DATA")),
            };

            let currency = transaction_currency(obj.get("currency"))?;
            let key = (tid.clone(), currency.clone());
            if !seen.insert(key) {
                continue;
            }
            new_count += 1;
            if created < cutoff {
                continue;
            }
            let description = pick_non_empty_str(obj, &["description"])
                .or_else(|| merchant_name.clone())
                .unwrap_or_default();
            out.push(RevolutTxnPayload {
                external_id: tid,
                date,
                description,
                amount: transaction_major(raw_amount, currency.as_deref(), fiat),
                currency,
                counterparty: merchant_name,
                kind: None,
                source_type: pick_str(obj, &["type"]),
            });
        }

        if oldest <= cutoff {
            break;
        }
        if new_count == 0 {
            let probe_raw = fetch_page(oldest).await?;
            let probe = parse_transactions_page(&probe_raw)?;
            if probe.is_empty() {
                break;
            }
            return Err(ParseError("PORTFOLIO_INCOMPLETE"));
        }
        if oldest >= to {
            return Err(ParseError("PORTFOLIO_INCOMPLETE"));
        }
        if page_number == MAX_TRANSACTION_PAGES - 1 {
            return Err(ParseError("PORTFOLIO_INCOMPLETE"));
        }
        to = oldest + if largest_page > 1 { 1 } else { 0 };
    }

    if paged_on_booking_date {
        tracing::warn!("Revolut transactions without createdDate: paged on the booking date");
    }
    Ok(out)
}

/// Synthesizes one parent checking account per wallet and nests same-currency children under it.
pub fn with_wallet_parents(
    accounts: Vec<RevolutAccountPayload>,
    wallet_currencies: &HashMap<String, String>,
    ibans: &HashMap<String, Option<String>>,
) -> Vec<RevolutAccountPayload> {
    let existing_ids: HashSet<String> = accounts.iter().map(|a| a.external_id.clone()).collect();
    let mut sums: HashMap<String, Decimal> = wallet_currencies
        .keys()
        .map(|id| (id.clone(), Decimal::ZERO))
        .collect();
    let mut out = Vec::with_capacity(accounts.len() + wallet_currencies.len());

    for mut acc in accounts {
        if let Some(ref wallet_id) = acc.parent_external_id {
            match wallet_currencies.get(wallet_id) {
                None => {
                    acc.parent_external_id = None;
                }
                Some(wallet_ccy) if *wallet_ccy != acc.currency => {
                    acc.parent_external_id = None;
                }
                Some(_) => {
                    if let Some(total) = sums.get_mut(wallet_id) {
                        *total += acc.balance;
                    }
                }
            }
        }
        out.push(acc);
    }

    let mut sorted_wallets: Vec<(&String, &String)> = wallet_currencies.iter().collect();
    sorted_wallets.sort_by(|a, b| a.0.cmp(b.0));

    for (wallet_id, ccy) in sorted_wallets {
        if existing_ids.contains(wallet_id) {
            continue;
        }
        let balance = sums.get(wallet_id).copied().unwrap_or(Decimal::ZERO);
        out.push(RevolutAccountPayload {
            external_id: wallet_id.clone(),
            name: "Revolut".to_owned(),
            account_type: "CHECKING".to_owned(),
            iban: ibans.get(wallet_id).cloned().flatten(),
            balance,
            currency: ccy.clone(),
            parent_external_id: None,
            transactions: Vec::new(),
        });
    }
    out
}

/// Classifies only deterministic cross-pocket mirror transfer legs (exact opposite amounts,
/// same currency, same date, distinct accounts, both marked `TRANSFER` by Revolut).
pub fn classify_internal_transfers(accounts: &mut [RevolutAccountPayload]) {
    let mut by_tx_id: HashMap<String, Vec<(usize, usize)>> = HashMap::new();
    for (ai, acc) in accounts.iter().enumerate() {
        for (ti, tx) in acc.transactions.iter().enumerate() {
            if !tx.external_id.is_empty() {
                by_tx_id
                    .entry(tx.external_id.clone())
                    .or_default()
                    .push((ai, ti));
            }
        }
    }

    let mut to_mark: Vec<(usize, usize)> = Vec::new();
    for entries in by_tx_id.values() {
        let &[(ai_a, ti_a), (ai_b, ti_b)] = entries.as_slice() else {
            continue;
        };
        let acc_a = &accounts[ai_a];
        let acc_b = &accounts[ai_b];
        let tx_a = &acc_a.transactions[ti_a];
        let tx_b = &acc_b.transactions[ti_b];
        if acc_a.external_id != acc_b.external_id
            && acc_a.currency == acc_b.currency
            && tx_a.date == tx_b.date
            && tx_a.source_type.as_deref() == Some("TRANSFER")
            && tx_b.source_type.as_deref() == Some("TRANSFER")
            && !tx_a.amount.is_zero()
            && tx_a.amount + tx_b.amount == Decimal::ZERO
        {
            to_mark.push((ai_a, ti_a));
            to_mark.push((ai_b, ti_b));
        }
    }

    for (ai, ti) in to_mark {
        accounts[ai].transactions[ti].kind = Some("TRANSFER".to_owned());
    }
    for acc in accounts.iter_mut() {
        for tx in &mut acc.transactions {
            tx.source_type = None;
        }
    }
}

/// Builds the initial accounts list (money-boxes + filtered fiat pockets) along with the
/// pocket-id map for transaction fetching and each wallet's dominant currency.
pub fn assemble_accounts_and_wallets(
    money_boxes: &[Value],
    wallets: &[(Option<String>, Vec<Value>)],
    fiat: &HashSet<String>,
) -> (
    Vec<RevolutAccountPayload>,
    HashMap<String, String>,
    HashMap<String, String>,
) {
    let mut accounts: Vec<RevolutAccountPayload> = Vec::new();
    let mut transaction_pocket_ids: HashMap<String, String> = HashMap::new();
    let mut mb_pocket_ids: HashSet<String> = HashSet::new();

    for mb in money_boxes {
        let Some(obj) = mb.as_object() else {
            continue;
        };
        let Some(mb_id) = pick_non_empty_str(obj, &["id"]) else {
            continue;
        };
        if let Some(mb_pocket) = obj.get("pocket").and_then(Value::as_object) {
            if let Some(pid) = pick_non_empty_str(mb_pocket, &["id"]) {
                mb_pocket_ids.insert(pid.clone());
                transaction_pocket_ids.insert(mb_id.clone(), pid);
            }
        }
        let bal_field = obj.get("balance");
        let (balance, bal_ccy) = match bal_field.and_then(Value::as_object) {
            Some(bal_obj) => (
                minor_to_major(bal_obj.get("amount")),
                pick_non_empty_str(bal_obj, &["currency"]),
            ),
            None => (minor_to_major(bal_field), None),
        };
        let currency = bal_ccy
            .or_else(|| pick_non_empty_str(obj, &["currency", "currencyCode"]))
            .unwrap_or_else(|| "EUR".to_owned());
        accounts.push(RevolutAccountPayload {
            external_id: mb_id,
            name: pick_non_empty_str(obj, &["name"]).unwrap_or_else(|| "Vault".to_owned()),
            account_type: "SAVINGS".to_owned(),
            iban: None,
            balance,
            currency,
            parent_external_id: pick_non_empty_str(obj, &["accountId", "walletId", "podId"]),
            transactions: Vec::new(),
        });
    }

    let mut current_candidates: HashMap<String, Vec<(String, Decimal)>> = HashMap::new();
    for (wallet_id, pockets) in wallets {
        for pocket in pockets {
            let Some(obj) = pocket.as_object() else {
                continue;
            };
            let Some(pid) = pick_non_empty_str(obj, &["id"]) else {
                continue;
            };
            let closed = obj.get("closed").and_then(Value::as_bool).unwrap_or(false);
            let ptype = pick_str(obj, &["type"]).unwrap_or_default();
            let currency = pick_non_empty_str(obj, &["currency", "currencyCode"])
                .unwrap_or_else(|| "EUR".to_owned());
            if closed
                || mb_pocket_ids.contains(&pid)
                || NOISE_POCKET_TYPES.contains(&ptype.as_str())
                || !fiat.contains(&currency)
            {
                continue;
            }
            let parent = wallet_id.as_ref().filter(|wid| *wid != &pid).cloned();
            let balance = minor_to_major(pick(obj, &["balance", "amount"]));
            let name = match pick_non_empty_str(obj, &["name"]) {
                Some(explicit) if !explicit.eq_ignore_ascii_case("CURRENT") => explicit,
                _ if ptype.eq_ignore_ascii_case("CURRENT") => format!("Revolut {currency}"),
                _ => pick_non_empty_str(obj, &["type"]).unwrap_or_else(|| "Revolut".to_owned()),
            };
            accounts.push(RevolutAccountPayload {
                external_id: pid.clone(),
                name,
                account_type: "CHECKING".to_owned(),
                iban: None,
                balance,
                currency: currency.clone(),
                parent_external_id: parent,
                transactions: Vec::new(),
            });
            transaction_pocket_ids.insert(pid.clone(), pid);
            if let Some(wid) = wallet_id {
                if ptype == "CURRENT" {
                    current_candidates
                        .entry(wid.clone())
                        .or_default()
                        .push((currency, balance));
                }
            }
        }
    }

    let wallet_ids: HashSet<String> = wallets.iter().filter_map(|(wid, _)| wid.clone()).collect();
    let mut wallet_currencies: HashMap<String, String> = HashMap::new();
    for wallet_id in wallet_ids {
        // Count sibling accounts (pockets + vaults) and sum balances per currency for this wallet.
        let mut counts: HashMap<String, usize> = HashMap::new();
        let mut totals: HashMap<String, Decimal> = HashMap::new();
        for acc in &accounts {
            if acc.parent_external_id.as_deref() == Some(wallet_id.as_str()) {
                *counts.entry(acc.currency.clone()).or_insert(0) += 1;
                *totals.entry(acc.currency.clone()).or_insert(Decimal::ZERO) += acc.balance;
            }
        }
        // Prefer the dominant CURRENT pocket currency (most sibling accounts -> highest total
        // balance -> EUR -> alphabetical), rather than letting an empty trailing USD pocket
        // overwrite the user's primary EUR wallet.
        let chosen = if let Some(cands) = current_candidates.get(&wallet_id) {
            let unique_ccys: HashSet<&str> = cands.iter().map(|(c, _)| c.as_str()).collect();
            unique_ccys
                .into_iter()
                .max_by(|a, b| {
                    let count_a = counts.get(*a).copied().unwrap_or(0);
                    let count_b = counts.get(*b).copied().unwrap_or(0);
                    let bal_a = totals.get(*a).copied().unwrap_or(Decimal::ZERO);
                    let bal_b = totals.get(*b).copied().unwrap_or(Decimal::ZERO);
                    count_a
                        .cmp(&count_b)
                        .then_with(|| bal_a.cmp(&bal_b))
                        .then_with(|| (*a == "EUR").cmp(&(*b == "EUR")))
                        .then_with(|| b.cmp(a))
                })
                .map(str::to_owned)
        } else {
            None
        };
        let dominant = chosen.unwrap_or_else(|| {
            counts
                .into_iter()
                .max_by(|a, b| {
                    let bal_a = totals.get(&a.0).copied().unwrap_or(Decimal::ZERO);
                    let bal_b = totals.get(&b.0).copied().unwrap_or(Decimal::ZERO);
                    a.1.cmp(&b.1)
                        .then_with(|| bal_a.cmp(&bal_b))
                        .then_with(|| (a.0 == "EUR").cmp(&(b.0 == "EUR")))
                        .then_with(|| b.0.cmp(&a.0))
                })
                .map(|(ccy, _)| ccy)
                .unwrap_or_else(|| "EUR".to_owned())
        });
        wallet_currencies.insert(wallet_id, dominant);
    }

    (accounts, transaction_pocket_ids, wallet_currencies)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;
    use serde_json::json;
    use std::cell::RefCell;

    fn dec(s: &str) -> Decimal {
        Decimal::from_str(s).unwrap()
    }

    fn pocket(
        external_id: &str,
        balance: &str,
        currency: &str,
        parent: Option<&str>,
    ) -> RevolutAccountPayload {
        RevolutAccountPayload {
            external_id: external_id.to_owned(),
            name: "Pocket".to_owned(),
            account_type: "CHECKING".to_owned(),
            iban: None,
            balance: dec(balance),
            currency: currency.to_owned(),
            parent_external_id: parent.map(str::to_owned),
            transactions: Vec::new(),
        }
    }

    fn fixed_now_ms() -> i64 {
        Utc.with_ymd_and_hms(2026, 6, 15, 12, 0, 0)
            .unwrap()
            .timestamp_millis()
    }

    fn row(
        id: &str,
        created_days_ago_tenths: i64,
        started_tenths: Option<i64>,
        completed_tenths: Option<i64>,
        currency: &str,
    ) -> Value {
        let now = fixed_now_ms();
        let tenth_day = DAY_MS / 10;
        let created = now - created_days_ago_tenths * tenth_day;
        let started = now - started_tenths.unwrap_or(created_days_ago_tenths) * tenth_day;
        let completed = now
            - completed_tenths.unwrap_or(started_tenths.unwrap_or(created_days_ago_tenths))
                * tenth_day;
        json!({
            "id": id,
            "createdDate": created,
            "startedDate": started,
            "completedDate": completed,
            "description": id,
            "amount": -100,
            "currency": currency,
            "type": "CARD_PAYMENT"
        })
    }

    #[test]
    fn wallet_parent_synthesized_with_same_currency_children_summed() {
        let accounts = vec![
            pocket("p1", "100.0", "EUR", Some("W")),
            pocket("p2", "50.0", "EUR", Some("W")),
            pocket("p3", "20.0", "GBP", Some("W")),
        ];
        let wallet_ccys = HashMap::from([("W".to_owned(), "EUR".to_owned())]);
        let ibans = HashMap::from([(
            "W".to_owned(),
            Some("FR7630006000011234567890189".to_owned()),
        )]);

        let result = with_wallet_parents(accounts, &wallet_ccys, &ibans);
        let by_id: HashMap<&str, &RevolutAccountPayload> =
            result.iter().map(|a| (a.external_id.as_str(), a)).collect();
        assert_eq!(result.len(), 4);

        let parent = by_id["W"];
        assert_eq!(parent.parent_external_id, None);
        assert_eq!(parent.account_type, "CHECKING");
        assert_eq!(parent.currency, "EUR");
        assert_eq!(parent.balance, dec("150.0"));
        assert_eq!(parent.iban.as_deref(), Some("FR7630006000011234567890189"));
        assert!(parent.transactions.is_empty());

        assert_eq!(by_id["p1"].parent_external_id.as_deref(), Some("W"));
        assert_eq!(by_id["p2"].parent_external_id.as_deref(), Some("W"));
        assert_eq!(by_id["p3"].parent_external_id, None);
        assert_eq!(by_id["p3"].balance, dec("20.0"));
    }

    #[test]
    fn unresolvable_parent_demoted_empty_wallet_gets_zero_parent_and_duplicate_id_skipped() {
        let wallet_ccys = HashMap::from([("W".to_owned(), "EUR".to_owned())]);
        let no_ibans = HashMap::from([("W".to_owned(), None)]);

        let res = with_wallet_parents(
            vec![pocket("vault1", "30.0", "EUR", Some("not-a-real-wallet"))],
            &wallet_ccys,
            &no_ibans,
        );
        let by_id: HashMap<&str, &RevolutAccountPayload> =
            res.iter().map(|a| (a.external_id.as_str(), a)).collect();
        assert_eq!(by_id["vault1"].parent_external_id, None);
        assert_eq!(by_id["W"].balance, Decimal::ZERO);

        let empty_res = with_wallet_parents(vec![], &wallet_ccys, &no_ibans);
        assert_eq!(empty_res.len(), 1);
        assert_eq!(empty_res[0].external_id, "W");
        assert_eq!(empty_res[0].balance, Decimal::ZERO);

        let dup_res = with_wallet_parents(
            vec![pocket("W", "40.0", "EUR", None)],
            &wallet_ccys,
            &HashMap::from([("W".to_owned(), Some("FR76...".to_owned()))]),
        );
        assert_eq!(dup_res.len(), 1);
        assert_eq!(dup_res[0].balance, dec("40.0"));
    }

    #[test]
    fn noise_pockets_closed_pockets_and_non_fiat_are_filtered_out() {
        let money_boxes = vec![json!({
            "id": "vault-1",
            "name": "Holiday",
            "balance": {"amount": 1000, "currency": "EUR"},
            "pocket": {"id": "vault-pocket"},
            "accountId": "wallet"
        })];
        let wallets = vec![(
            Some("wallet".to_owned()),
            vec![
                json!({"id": "p-cur", "type": "CURRENT", "currency": "EUR", "balance": 5000}),
                json!({"id": "vault-pocket", "type": "SAVINGS", "currency": "EUR", "balance": 1000}),
                json!({"id": "p-closed", "type": "CURRENT", "currency": "EUR", "balance": 0, "closed": true}),
                json!({"id": "p-merchant", "type": "MERCHANT", "currency": "EUR", "balance": 100}),
                json!({"id": "p-revx", "type": "REVX_FIAT", "currency": "EUR", "balance": 100}),
                json!({"id": "p-btc", "type": "CURRENT", "currency": "BTC", "balance": 100}),
            ],
        )];
        let fiat = HashSet::from(["EUR".to_owned(), "USD".to_owned()]);
        let (accounts, tx_map, wallet_ccys) =
            assemble_accounts_and_wallets(&money_boxes, &wallets, &fiat);

        let ids: Vec<&str> = accounts.iter().map(|a| a.external_id.as_str()).collect();
        assert_eq!(ids, vec!["vault-1", "p-cur"]);
        assert_eq!(accounts[0].account_type, "SAVINGS");
        assert_eq!(accounts[0].balance, dec("10"));
        assert_eq!(accounts[1].account_type, "CHECKING");
        assert_eq!(accounts[1].balance, dec("50"));
        assert_eq!(
            tx_map.get("vault-1").map(String::as_str),
            Some("vault-pocket")
        );
        assert_eq!(tx_map.get("p-cur").map(String::as_str), Some("p-cur"));
        assert_eq!(wallet_ccys.get("wallet").map(String::as_str), Some("EUR"));
    }

    #[test]
    fn internal_transfer_classification_requires_unique_opposite_mirror() {
        let mut accounts = vec![
            RevolutAccountPayload {
                external_id: "src".to_owned(),
                name: "Src".to_owned(),
                account_type: "CHECKING".to_owned(),
                iban: None,
                balance: dec("100"),
                currency: "EUR".to_owned(),
                parent_external_id: None,
                transactions: vec![
                    RevolutTxnPayload {
                        external_id: "mirrored".to_owned(),
                        date: "2026-06-01".to_owned(),
                        description: "Transfer".to_owned(),
                        amount: dec("-10"),
                        currency: Some("EUR".to_owned()),
                        counterparty: None,
                        kind: None,
                        source_type: Some("TRANSFER".to_owned()),
                    },
                    RevolutTxnPayload {
                        external_id: "external".to_owned(),
                        date: "2026-06-01".to_owned(),
                        description: "External".to_owned(),
                        amount: dec("-10"),
                        currency: Some("EUR".to_owned()),
                        counterparty: None,
                        kind: None,
                        source_type: Some("TRANSFER".to_owned()),
                    },
                ],
            },
            RevolutAccountPayload {
                external_id: "dst".to_owned(),
                name: "Dst".to_owned(),
                account_type: "CHECKING".to_owned(),
                iban: None,
                balance: dec("50"),
                currency: "EUR".to_owned(),
                parent_external_id: None,
                transactions: vec![RevolutTxnPayload {
                    external_id: "mirrored".to_owned(),
                    date: "2026-06-01".to_owned(),
                    description: "Transfer".to_owned(),
                    amount: dec("10"),
                    currency: Some("EUR".to_owned()),
                    counterparty: None,
                    kind: None,
                    source_type: Some("TRANSFER".to_owned()),
                }],
            },
        ];

        classify_internal_transfers(&mut accounts);
        assert_eq!(
            accounts[0].transactions[0].kind.as_deref(),
            Some("TRANSFER")
        );
        assert_eq!(
            accounts[1].transactions[0].kind.as_deref(),
            Some("TRANSFER")
        );
        assert_eq!(accounts[0].transactions[1].kind, None);
    }

    #[tokio::test]
    async fn pagination_follows_created_date_and_preserves_same_ms_exchange_legs() {
        let rows = [
            row("t1", 20, None, None, "EUR"),
            row("t2", 30, None, None, "EUR"),
            row("exchange", 40, None, None, "EUR"),
            row("exchange", 40, None, None, "USD"),
            row("t5", 50, None, None, "EUR"),
        ];
        let fiat = HashSet::from(["EUR".to_owned(), "USD".to_owned()]);
        let bounds = RefCell::new(Vec::new());

        let txns = fetch_transactions_with(
            |to| {
                bounds.borrow_mut().push(to);
                let page: Vec<Value> = rows
                    .iter()
                    .filter(|r| r["createdDate"].as_i64().unwrap() < to)
                    .take(3)
                    .cloned()
                    .collect();
                async move { Ok(Value::Array(page)) }
            },
            fixed_now_ms(),
            &fiat,
        )
        .await
        .unwrap();

        let pairs: Vec<(&str, Option<&str>)> = txns
            .iter()
            .map(|t| (t.external_id.as_str(), t.currency.as_deref()))
            .collect();
        assert_eq!(
            pairs,
            vec![
                ("t1", Some("EUR")),
                ("t2", Some("EUR")),
                ("exchange", Some("EUR")),
                ("exchange", Some("USD")),
                ("t5", Some("EUR")),
            ]
        );
    }

    #[tokio::test]
    async fn exchange_crypto_and_fiat_scales_and_json_number_contract() {
        let now = fixed_now_ms();
        let crypto = json!({
            "id": "ex-1",
            "completedDate": now - 1000,
            "description": "Exchanged to EUR",
            "amount": -5_000_000,
            "currency": "BTC",
            "type": "EXCHANGE"
        });
        let eur = json!({
            "id": "ex-1",
            "completedDate": now - 1000,
            "description": "Exchanged from BTC",
            "amount": 300_000,
            "currency": "EUR",
            "type": "EXCHANGE"
        });
        let fiat = HashSet::from(["EUR".to_owned()]);
        let calls = RefCell::new(0usize);

        let txns = fetch_transactions_with(
            |_to| {
                let mut c = calls.borrow_mut();
                *c += 1;
                let batch = if *c == 1 {
                    vec![crypto.clone(), eur.clone()]
                } else {
                    vec![]
                };
                async move { Ok(Value::Array(batch)) }
            },
            now,
            &fiat,
        )
        .await
        .unwrap();

        assert_eq!(txns.len(), 2);
        assert_eq!(txns[0].currency.as_deref(), Some("BTC"));
        assert_eq!(txns[0].amount, dec("-0.05"));
        assert_eq!(txns[1].currency.as_deref(), Some("EUR"));
        assert_eq!(txns[1].amount, dec("3000"));

        // Verify JSON serialization emits Numbers for balance and amount (required by adapter.rs).
        let harvest = RevolutHarvestPayload {
            accounts: vec![RevolutAccountPayload {
                external_id: "w1".to_owned(),
                name: "Revolut".to_owned(),
                account_type: "CHECKING".to_owned(),
                iban: None,
                balance: dec("123.45"),
                currency: "EUR".to_owned(),
                parent_external_id: None,
                transactions: txns,
            }],
        };
        let serialized = serde_json::to_value(&harvest).unwrap();
        assert!(serialized["accounts"][0]["balance"].is_number());
        assert!(serialized["accounts"][0]["transactions"][0]["amount"].is_number());
    }

    #[tokio::test]
    async fn malformed_rows_and_burst_overflow_fail_closed() {
        let now = fixed_now_ms();
        let fiat = fiat_fallback();

        // Malformed currency fails closed.
        let err = fetch_transactions_with(
            |_to| async move {
                Ok(json!([{
                    "id": "bad",
                    "completedDate": now - 1000,
                    "amount": -100,
                    "currency": "EURO-LONG-CODE"
                }]))
            },
            now,
            &fiat,
        )
        .await
        .unwrap_err();
        assert_eq!(err, ParseError("INVALID_DATA"));

        // Burst larger than page size at identical millisecond fails closed as PORTFOLIO_INCOMPLETE.
        let burst: Vec<Value> = (0..5)
            .map(|n| row(&format!("b-{n}"), 40, None, None, "EUR"))
            .collect();
        let mut rows = vec![
            row("t1", 20, None, None, "EUR"),
            row("t2", 30, None, None, "EUR"),
        ];
        rows.extend(burst);
        rows.push(row("old", 60, None, None, "EUR"));

        let err = fetch_transactions_with(
            |to| {
                let page: Vec<Value> = rows
                    .iter()
                    .filter(|r| r["createdDate"].as_i64().unwrap() < to)
                    .take(3)
                    .cloned()
                    .collect();
                async move { Ok(Value::Array(page)) }
            },
            now,
            &fiat,
        )
        .await
        .unwrap_err();
        assert_eq!(err, ParseError("PORTFOLIO_INCOMPLETE"));
    }

    #[test]
    fn fiat_list_normalization_and_fallback() {
        assert_eq!(
            parse_fiat_currencies(Some(&json!([{"code": "eur"}, {"code": "usd"}]))),
            HashSet::from(["EUR".to_owned(), "USD".to_owned()])
        );
        assert_eq!(
            parse_fiat_currencies(Some(&json!(["USD", "GBP"]))),
            fiat_fallback()
        );
        assert_eq!(parse_fiat_currencies(Some(&json!([]))), fiat_fallback());
    }

    #[test]
    fn trailing_empty_usd_current_pocket_never_hijacks_eur_wallet_parent() {
        let money_boxes = vec![json!({
            "id": "mb-eur",
            "name": "Compte d'épargne",
            "accountId": "w-1",
            "balance": {"amount": 0, "currency": "EUR"},
            "pocket": {"id": "p-sav"}
        })];
        let wallets = vec![(
            Some("w-1".to_owned()),
            vec![
                json!({"id": "p-eur", "type": "CURRENT", "currency": "EUR", "balance": 254}),
                json!({"id": "p-usd", "type": "CURRENT", "currency": "USD", "balance": 0}),
            ],
        )];
        let fiat = fiat_fallback();
        let (accounts, _, wallet_currencies) =
            assemble_accounts_and_wallets(&money_boxes, &wallets, &fiat);
        assert_eq!(
            wallet_currencies.get("w-1").map(String::as_str),
            Some("EUR")
        );

        let final_accounts = with_wallet_parents(accounts, &wallet_currencies, &HashMap::new());
        let parent = final_accounts
            .iter()
            .find(|a| a.external_id == "w-1")
            .unwrap();
        assert_eq!(parent.currency, "EUR");
        assert_eq!(parent.balance, dec("2.54"));

        let eur_pocket = final_accounts
            .iter()
            .find(|a| a.external_id == "p-eur")
            .unwrap();
        assert_eq!(eur_pocket.name, "Revolut EUR");
        assert_eq!(eur_pocket.parent_external_id.as_deref(), Some("w-1"));

        let usd_pocket = final_accounts
            .iter()
            .find(|a| a.external_id == "p-usd")
            .unwrap();
        assert_eq!(usd_pocket.name, "Revolut USD");
        assert_eq!(usd_pocket.parent_external_id, None);
    }
}
