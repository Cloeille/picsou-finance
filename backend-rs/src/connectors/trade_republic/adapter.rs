//! TradeRepublicAdapter: the tr-auth sidecar calls (login, session, CSV export) and the portfolio
//! read over Trade Republic's own WebSocket API (protocol 31), which needs no WAF token.

use std::collections::{HashMap, HashSet};
use std::str::FromStr;
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Deserialize;
use serde_json::{Value, json};

use super::ws;
use crate::AppState;
use crate::connectors::sidecar::error::{JobError, JobResult};
use crate::connectors::sidecar::{SidecarClient, SidecarSessionState, SyncError};
use crate::error::AppError;
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::model::AccountType;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode};

const BANK: SidecarBank = SidecarBank::TradeRepublic;
const WS_VERSION: u32 = 31;
/// Lang & Schwarz Exchange, TR's home venue for equities and ETFs.
const DEFAULT_EXCHANGE: &str = "LSX";
pub const EXTERNAL_ID_SECURITIES: &str = "tr_securities";
pub const EXTERNAL_ID_CRYPTO: &str = "tr_crypto";
pub const EXTERNAL_ID_PEA: &str = "tr_pea";
pub const EXTERNAL_ID_CASH: &str = "tr_cash";
pub const DEFAULT_WS_URL: &str = "wss://api.traderepublic.com/";
const AUTH_TIMEOUT: Duration = Duration::from_secs(45);
/// Above the sidecar's 120 s wait for the in-app confirmation, or a login about to succeed
/// would time out here.
const COMPLETE_TIMEOUT: Duration = Duration::from_secs(150);
const SESSION_TIMEOUT: Duration = Duration::from_secs(45);
/// TR renders the export on demand, which takes a while over a long window.
const TRANSACTIONS_TIMEOUT: Duration = Duration::from_secs(120);
/// Total time the WebSocket read may take before the sync goes on with what it has; under the
/// hard timeout around the whole exchange, which fails the sync.
const WS_WAIT_CAP: Duration = Duration::from_secs(30);
const WS_HARD_TIMEOUT: Duration = Duration::from_secs(45);

/// Answer to /initiate: `flow` is confirmInApp, otp or authenticator.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct TrInitiate {
    pub process_id: Option<String>,
    pub flow: Option<String>,
    pub countdown_in_seconds: Option<i32>,
}

/// A live tr_session cookie value and the claims TR issued with it.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct TrSession {
    pub session_token: Option<String>,
    pub claims: Option<Value>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct TrPosition {
    pub isin: String,
    pub quantity: Decimal,
    pub average_buy_in: Decimal,
    /// None when TR sent no price.
    pub current_price: Option<Decimal>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct TrAccountData {
    pub external_id: String,
    pub name: String,
    pub account_type: AccountType,
    pub balance_eur: Decimal,
    pub positions: Vec<TrPosition>,
    /// The PEA's cash pocket, already counted in balance_eur; None when unknown or absent.
    pub cash_eur: Option<Decimal>,
}

fn client(state: &AppState) -> SidecarClient {
    SidecarClient::new(state, BANK, SidecarErrorCode::AuthAttemptExpired)
}

/// Step 1: phone number and PIN to TR through the sidecar, which answers once TR asked for the
/// second factor. Nothing is stored.
pub async fn initiate_auth(
    state: &AppState,
    phone_number: &str,
    pin: &str,
) -> Result<TrInitiate, SyncError> {
    tracing::info!("Delegating TR auth initiation to tr-auth sidecar");
    let sidecar = client(state);
    let response: TrInitiate = sidecar
        .post(
            "/initiate",
            &json!({ "phoneNumber": phone_number, "pin": pin }),
            AUTH_TIMEOUT,
            "Could not initiate Trade Republic authentication",
            Some(SidecarErrorCode::InvalidCredentials),
        )
        .await?;
    if response
        .process_id
        .as_deref()
        .is_none_or(|p| p.trim().is_empty())
    {
        return Err(sidecar.coded(
            SidecarErrorCode::UpstreamFormatChanged,
            "Trade Republic did not return an authentication attempt",
        ));
    }
    Ok(response)
}

/// Step 2: waits for the in-app confirmation (or checks the code) and returns the opaque session
/// state. The sidecar declares `tan`, so it is sent as an explicit null when absent.
pub async fn complete_auth(
    state: &AppState,
    process_id: &str,
    tan: Option<&str>,
) -> Result<String, SyncError> {
    tracing::info!("Delegating TR login completion to tr-auth sidecar");
    let sidecar = client(state);
    let response: SidecarSessionState = sidecar
        .post(
            "/complete",
            &json!({ "processId": process_id, "tan": tan }),
            COMPLETE_TIMEOUT,
            "Could not complete Trade Republic authentication",
            Some(SidecarErrorCode::InvalidOtp),
        )
        .await?;
    response
        .session_state
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| {
            sidecar.coded(
                SidecarErrorCode::UpstreamFormatChanged,
                "Trade Republic did not return a session",
            )
        })
}

/// The stored state turned into a live tr_session cookie value and its decoded claims.
pub async fn open_session(state: &AppState, session_state: &str) -> Result<TrSession, SyncError> {
    tracing::info!("Opening TR session via tr-auth sidecar");
    let sidecar = client(state);
    let session: TrSession = sidecar
        .post(
            "/session",
            &json!({ "sessionState": session_state }),
            SESSION_TIMEOUT,
            "Could not open the Trade Republic session",
            Some(SidecarErrorCode::SessionExpired),
        )
        .await?;
    if session
        .session_token
        .as_deref()
        .is_none_or(|t| t.trim().is_empty())
    {
        return Err(sidecar.coded(
            SidecarErrorCode::UpstreamFormatChanged,
            "Trade Republic returned no session token",
        ));
    }
    Ok(session)
}

/// TR's own CSV export of the activity between two dates (inclusive), untouched. It carries
/// personal data, so it is never logged.
pub async fn fetch_transactions_csv(
    state: &AppState,
    session_state: &str,
    from: NaiveDate,
    to: NaiveDate,
) -> Result<String, SyncError> {
    tracing::info!("Fetching TR transactions export via tr-auth sidecar");
    const MESSAGE: &str = "Could not fetch the Trade Republic transactions";
    let sidecar = client(state);
    let response = sidecar
        .post_raw(
            "/transactions",
            &json!({ "sessionState": session_state, "from": from.to_string(), "to": to.to_string() }),
            TRANSACTIONS_TIMEOUT,
        )
        .await
        .map_err(|e| sidecar.map_call_error(MESSAGE, e))?;
    if !response.is_success() {
        return Err(sidecar.map_status(MESSAGE, &response, Some(SidecarErrorCode::SessionExpired)));
    }
    if response.body.is_empty() {
        return Err(sidecar.coded(SidecarErrorCode::UpstreamUnavailable, MESSAGE));
    }
    Ok(String::from_utf8_lossy(&response.body).into_owned())
}

#[derive(Debug, Clone)]
struct SecAccount {
    wrapper: String,
    account_numbers: Vec<String>,
    cash_account_numbers: Vec<String>,
    external_id: String,
    name: String,
    account_type: AccountType,
}

/// Jackson's asText(default): a string's text, a number's or boolean's literal, "" for a
/// container, the default for null or missing.
fn as_text(node: Option<&Value>, default: &str) -> String {
    match node {
        None | Some(Value::Null) => default.to_owned(),
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::Bool(b)) => b.to_string(),
        Some(_) => String::new(),
    }
}

/// asText(null): None for null or missing.
fn as_text_or_none(node: Option<&Value>) -> Option<String> {
    match node {
        None | Some(Value::Null) => None,
        other => Some(as_text(other, "")),
    }
}

/// The elements Jackson iterates: an array's items, an object's values, nothing otherwise.
fn elements(node: Option<&Value>) -> Vec<&Value> {
    match node {
        Some(Value::Array(items)) => items.iter().collect(),
        Some(Value::Object(fields)) => fields.values().collect(),
        _ => Vec::new(),
    }
}

/// new BigDecimal(text): plain or scientific notation, no surrounding whitespace.
fn big_decimal(text: &str) -> Option<Decimal> {
    if text.is_empty() || text.trim() != text {
        return None;
    }
    Decimal::from_str_exact(text)
        .or_else(|_| Decimal::from_str(text))
        .or_else(|_| Decimal::from_scientific(text))
        .ok()
}

fn is_blank(text: &str) -> bool {
    text.trim().is_empty()
}

/// Exact match on TR's native crypto classification; crypto ETFs/ETCs arrive as
/// stocksAndETFs / fund and stay in the securities account.
pub fn is_native_crypto(category_type: &str, instrument_type: &str) -> bool {
    category_type.eq_ignore_ascii_case("cryptos") || instrument_type.eq_ignore_ascii_case("crypto")
}

/// Exact match on TR's bond classification, never a substring: a bond ETF is quoted per unit.
pub fn is_bond(kind: &str) -> bool {
    kind.eq_ignore_ascii_case("bond") || kind.eq_ignore_ascii_case("bonds")
}

/// TR quotes a bond's ticker as a percentage of its nominal.
pub fn ticker_price_in_eur(quoted: Decimal, bond: bool) -> Decimal {
    if !bond {
        return quoted;
    }
    let mut scaled = quoted;
    if scaled.scale() + 2 <= 28 {
        scaled
            .set_scale(scaled.scale() + 2)
            .expect("scale within bounds");
        scaled
    } else {
        quoted / Decimal::ONE_HUNDRED
    }
}

/// Whether TR's instrument answer names EUR as the nominal currency; an absent or other
/// currency fails closed.
pub fn is_eur_nominal(instrument_payload: &str) -> bool {
    let Ok(root) = serde_json::from_str::<Value>(instrument_payload) else {
        tracing::warn!("Unparsable TR instrument answer, nominal currency unknown");
        return false;
    };
    if let Some(Value::String(currency)) = root.get("bondInfo").and_then(|b| b.get("currency"))
        && !is_blank(currency)
    {
        return currency.eq_ignore_ascii_case("EUR");
    }
    for field in ["currencyId", "currency"] {
        if let Some(value) = root.get(field).filter(|v| !v.is_null()) {
            return as_text(Some(value), "").eq_ignore_ascii_case("EUR");
        }
    }
    if let Some(Value::Array(currencies)) = root.get("currencies")
        && let Some(first) = currencies.first()
    {
        let text = if first.is_string() {
            as_text(Some(first), "")
        } else {
            as_text(first.get("id"), "")
        };
        return text.eq_ignore_ascii_case("EUR");
    }
    false
}

/// The venue to price an instrument on: LSX whenever listed, else the first venue, else LSX.
fn ticker_exchange(instrument_payload: &str) -> String {
    let Ok(root) = serde_json::from_str::<Value>(instrument_payload) else {
        tracing::warn!("Unparsable TR instrument answer, pricing on {DEFAULT_EXCHANGE}");
        return DEFAULT_EXCHANGE.to_owned();
    };
    let mut first = None;
    for id in elements(root.get("exchangeIds")) {
        let exchange = as_text(Some(id), "");
        if exchange == DEFAULT_EXCHANGE {
            return exchange;
        }
        if first.is_none() && !is_blank(&exchange) {
            first = Some(exchange);
        }
    }
    first.unwrap_or_else(|| DEFAULT_EXCHANGE.to_owned())
}

/// Whether a securities account's answer this sync is a portfolio to persist: an error frame
/// with no positions says nothing and leaves the previous holdings; a genuinely emptied
/// portfolio that parsed still persists.
pub fn should_persist(total: Decimal, no_positions: bool, answered: bool, errored: bool) -> bool {
    if errored && no_positions {
        return false;
    }
    total > Decimal::ZERO || !no_positions || answered
}

/// A frame for the log: its id, its type and its size, never its payload.
pub fn describe_frame(text: &str) -> String {
    let frame = text.trim();
    let length = text.encode_utf16().count();
    let Some(first) = frame.find(' ') else {
        return if frame.encode_utf16().count() <= 16 {
            frame.to_owned()
        } else {
            format!("({length} chars)")
        };
    };
    let rest = &frame[first + 1..];
    let id = &frame[..first];
    let kind = rest.find(' ').map_or(rest, |second| &rest[..second]);
    if id.is_empty() || !id.chars().all(|c| c.is_ascii_digit()) || kind.chars().count() != 1 {
        return format!("({length} chars)");
    }
    format!("{id} {kind} ({length} chars)")
}

fn extract_ws_id(text: &str) -> i64 {
    match text.find(' ') {
        Some(space) if space > 0 => text[..space].parse::<i32>().map_or(-1, i64::from),
        _ => -1,
    }
}

fn extract_ws_payload(text: &str) -> &str {
    let Some(first) = text.find(' ') else {
        return text;
    };
    match text[first + 1..].find(' ') {
        Some(second) => &text[first + 1 + second + 1..],
        None => &text[first + 1..],
    }
}

fn extract_ws_type(text: &str) -> &str {
    let Some(first) = text.find(' ') else {
        return "";
    };
    let rest = &text[first + 1..];
    rest.find(' ').map_or(rest, |second| &rest[..second])
}

fn decimal_of(node: Option<&Value>) -> Result<Decimal, String> {
    let text = match node {
        None | Some(Value::Null) => return Ok(Decimal::ZERO),
        Some(Value::Object(fields)) => as_text(fields.get("value"), "0"),
        other => as_text(other, "0"),
    };
    if is_blank(&text) {
        return Ok(Decimal::ZERO);
    }
    big_decimal(&text).ok_or_else(|| "NumberFormatException".to_owned())
}

fn extract_value(node: &Value) -> Result<Decimal, ()> {
    let text = if let Some(value) = node.get("value") {
        as_text(Some(value), "0")
    } else if let Some(amount) = node.get("amount") {
        as_text(Some(amount), "0")
    } else if node.is_number() {
        as_text(Some(node), "0")
    } else {
        return Ok(Decimal::ZERO);
    };
    big_decimal(&text).ok_or(())
}

fn parse_cash_value(json: &str) -> Decimal {
    if is_blank(json) {
        return Decimal::ZERO;
    }
    let read = || -> Result<Option<Decimal>, ()> {
        let root: Value = serde_json::from_str(json).map_err(|_| ())?;
        let array = if root.is_array() {
            &root
        } else {
            root.get("availableCash").unwrap_or(&root)
        };
        match array {
            Value::Array(items) => {
                for item in items {
                    let value = extract_value(item)?;
                    if value >= Decimal::ZERO {
                        return Ok(Some(value));
                    }
                }
            }
            Value::Object(_) => {
                let value = extract_value(array)?;
                if value >= Decimal::ZERO {
                    return Ok(Some(value));
                }
            }
            _ => {}
        }
        Ok(None)
    };
    match read() {
        Ok(value) => value.unwrap_or(Decimal::ZERO),
        Err(()) => {
            tracing::error!("Failed to parse TR availableCash");
            Decimal::ZERO
        }
    }
}

/// Reads the balance of one scoped cash pocket. When TR answers with an array or object carrying
/// `accountNumber` entries, only the matching pocket is read so an unscoped response can never
/// leak another account's balance or double-count across multiple pockets.
fn parse_scoped_cash_value(json: &str, account_number: &str) -> Decimal {
    if is_blank(json) {
        return Decimal::ZERO;
    }
    if let Ok(root) = serde_json::from_str::<Value>(json) {
        let target = if root.is_array() {
            &root
        } else {
            root.get("availableCash").unwrap_or(&root)
        };
        match target {
            Value::Array(items)
                if items.iter().any(|item| {
                    as_text_or_none(item.get("accountNumber")).is_some_and(|n| !is_blank(&n))
                }) =>
            {
                for item in items {
                    if as_text_or_none(item.get("accountNumber")).as_deref() == Some(account_number)
                        && let Ok(value) = extract_value(item)
                        && value >= Decimal::ZERO
                    {
                        return value;
                    }
                }
                return Decimal::ZERO;
            }
            Value::Object(obj)
                if as_text_or_none(obj.get("accountNumber")).is_some_and(|n| !is_blank(&n)) =>
            {
                if as_text_or_none(obj.get("accountNumber")).as_deref() == Some(account_number)
                    && let Ok(value) = extract_value(target)
                    && value >= Decimal::ZERO
                {
                    return value;
                }
                return Decimal::ZERO;
            }
            _ => {}
        }
    }
    parse_cash_value(json)
}

struct Valued {
    total_eur: Decimal,
    positions: Vec<TrPosition>,
    priced: usize,
}

fn value_portfolio(
    positions_by_isin: &[(String, Value)],
    ticker_prices: &HashMap<String, Decimal>,
    cash_pocket: Option<Decimal>,
) -> Result<Valued, String> {
    let mut total = cash_pocket.unwrap_or(Decimal::ZERO);
    let mut positions = Vec::new();
    let mut priced = 0;
    for (isin, position) in positions_by_isin {
        let size = decimal_of(position.get("netSize"))?;
        if size <= Decimal::ZERO {
            continue;
        }
        let average_buy_in = decimal_of(position.get("averageBuyIn"))?;
        let current_price = ticker_prices.get(isin).copied();
        match current_price {
            Some(price) if price > Decimal::ZERO => {
                total += set_scale(price * size, 2, HALF_UP);
                priced += 1;
            }
            _ if average_buy_in > Decimal::ZERO => {
                total += set_scale(average_buy_in * size, 2, HALF_UP);
                tracing::warn!(
                    "TR price unavailable for {isin}: counted at averageBuyIn in the account total, its current price left empty"
                );
            }
            _ => {}
        }
        positions.push(TrPosition {
            isin: isin.clone(),
            quantity: size,
            average_buy_in,
            current_price,
        });
    }
    Ok(Valued {
        total_eur: total,
        positions,
        priced,
    })
}

fn account_type_for_wrapper(wrapper: &str) -> AccountType {
    match wrapper {
        "tax_wrapper_fr" => AccountType::Pea,
        "tax_wrapper_pl_ike" => AccountType::Brokerage,
        _ => AccountType::CompteTitres,
    }
}

fn external_id_for_wrapper(wrapper: &str) -> String {
    match wrapper {
        "default" => EXTERNAL_ID_SECURITIES.to_owned(),
        "tax_wrapper_fr" => EXTERNAL_ID_PEA.to_owned(),
        _ => {
            let mut id = String::from("tr_");
            let mut in_run = false;
            for c in wrapper.chars() {
                if c.is_ascii_alphanumeric() {
                    id.push(c.to_ascii_lowercase());
                    in_run = false;
                } else if !in_run {
                    id.push('_');
                    in_run = true;
                }
            }
            id
        }
    }
}

fn name_for_wrapper(wrapper: &str) -> &'static str {
    match wrapper {
        "tax_wrapper_fr" => "TR PEA",
        "tax_wrapper_pl_ike" => "TR IKE",
        _ => "TR Titres",
    }
}

fn distinct_non_blank(items: &[Value]) -> Vec<String> {
    let mut result = Vec::new();
    let mut seen = HashSet::new();
    for item in items {
        if let Some(text) = as_text_or_none(Some(item)).filter(|a| !is_blank(a))
            && seen.insert(text.clone())
        {
            result.push(text);
        }
    }
    result
}

fn next_unique_identity(
    used: &mut HashSet<String>,
    base_external_id: &str,
    base_name: &str,
    initial_suffix: usize,
) -> (String, String) {
    let mut suffix = initial_suffix;
    let mut external_id = if suffix == 0 {
        base_external_id.to_owned()
    } else {
        format!("{base_external_id}_{}", suffix + 1)
    };
    while !used.insert(external_id.clone()) {
        suffix += 1;
        external_id = format!("{base_external_id}_{}", suffix + 1);
    }
    let name = if suffix == 0 {
        base_name.to_owned()
    } else {
        format!("{base_name} {}", suffix + 1)
    };
    (external_id, name)
}

fn add_sec_accounts_from_wrapper(
    result: &mut Vec<SecAccount>,
    used: &mut HashSet<String>,
    wrapper: &str,
    node: Option<&Value>,
) {
    let Some(Value::Array(sec)) = node.and_then(|n| n.get("sec")) else {
        return;
    };
    let account_type = account_type_for_wrapper(wrapper);
    let base_external_id = external_id_for_wrapper(wrapper);
    let base_name = name_for_wrapper(wrapper);
    let cash = node.and_then(|n| n.get("cash")).and_then(Value::as_array);

    // French law limits an individual owner to a single PEA (Code monetaire et financier,
    // art. L221-30), and TR's CSV export carries no sub-account id beyond `account_type=PEA`.
    // TR's `tax_wrapper_fr` claims can list multiple `sec`/`cash` entries (for example empty or
    // historical sub-accounts beside the active one), so all of them consolidate into one `tr_pea`.
    if wrapper == "tax_wrapper_fr" {
        let account_numbers = distinct_non_blank(sec);
        if account_numbers.is_empty() {
            return;
        }
        let cash_account_numbers = cash.map_or_else(Vec::new, |c| distinct_non_blank(c));
        let (external_id, name) = next_unique_identity(used, &base_external_id, base_name, 0);
        result.push(SecAccount {
            wrapper: wrapper.to_owned(),
            account_numbers,
            cash_account_numbers,
            external_id,
            name,
            account_type,
        });
        return;
    }

    let mut position = 0usize;
    for account in sec {
        let Some(account_number) = as_text_or_none(Some(account)).filter(|a| !is_blank(a)) else {
            continue;
        };
        let cash_account_numbers = cash
            .and_then(|c| c.get(position))
            .and_then(|c| as_text_or_none(Some(c)))
            .filter(|c| !is_blank(c))
            .into_iter()
            .collect();
        // The dedup suffix is separate from `position` so a collision never shifts the cash pairing.
        let (external_id, name) =
            next_unique_identity(used, &base_external_id, base_name, position);
        result.push(SecAccount {
            wrapper: wrapper.to_owned(),
            account_numbers: vec![account_number],
            cash_account_numbers,
            external_id,
            name,
            account_type,
        });
        position += 1;
    }
}

fn jwt_payload(token: &str) -> Option<Value> {
    let part = token.split('.').nth(1)?;
    let bytes = URL_SAFE_NO_PAD.decode(part.trim_end_matches('=')).ok()?;
    serde_json::from_slice(&bytes).ok()
}

fn extract_sec_accounts(session_token: &str, claims: Option<&Value>) -> Vec<SecAccount> {
    let root = match claims {
        Some(claims) if claims.get("act").is_some_and(Value::is_object) => Some(claims.clone()),
        _ => jwt_payload(session_token),
    };
    let Some(root) = root else {
        if session_token.split('.').count() >= 2 {
            tracing::warn!("Failed to extract sec account numbers from the session claims");
        }
        return Vec::new();
    };
    let Some(Value::Object(owner)) = root
        .get("act")
        .and_then(|a| a.get("acc"))
        .and_then(|a| a.get("owner"))
    else {
        return Vec::new();
    };
    let mut result = Vec::new();
    let mut used = HashSet::new();
    add_sec_accounts_from_wrapper(&mut result, &mut used, "default", owner.get("default"));
    for (wrapper, node) in owner {
        if wrapper != "default" {
            add_sec_accounts_from_wrapper(&mut result, &mut used, wrapper, Some(node));
        }
    }
    result
}

fn connect_message() -> String {
    let payload = json!({
        "locale": "fr",
        "platformId": "webtrading",
        "platformVersion": "chrome - 125.0.0",
        "clientId": "app.traderepublic.com",
        "clientVersion": "3.151.3",
    });
    format!("connect {WS_VERSION} {payload}")
}

fn sub(id: i64, payload: &Value) -> String {
    format!("sub {id} {payload}")
}

/// What the subscriptions answered so far, and which subscription is which.
struct Exchange<'a> {
    token: &'a str,
    sec_accounts: &'a [SecAccount],
    next_id: i64,
    cash_json: Option<String>,
    /// Non-error `availableCash` payload keyed by subscription ws_id.
    scoped_cash_json: HashMap<i64, String>,
    /// Positions keyed by `(sec_account_index, sec_acc_no)` so multiple sub-accounts consolidated
    /// under one wrapper do not overwrite each other.
    positions_by_sec_acc: HashMap<(usize, String), Vec<(String, Value)>>,
    crypto_positions: Vec<(String, Value)>,
    ticker_prices: HashMap<String, Decimal>,
    bond_isins: HashSet<String>,
    unpriced_bond_isins: HashSet<String>,
    ticker_subs: HashMap<i64, String>,
    instrument_subs: HashMap<i64, String>,
    resolving_isins: HashSet<String>,
    /// ws_id -> (sec_account_index, sec_acc_no)
    portfolio_subs: HashMap<i64, (usize, String)>,
    /// ws_id -> (sec_account_index, cash_account_number)
    scoped_cash_subs: HashMap<i64, (usize, String)>,
    received_portfolio_subs: HashSet<i64>,
    errored_portfolio_subs: HashSet<i64>,
    received_scoped_cash_subs: HashSet<i64>,
    auth_expired: bool,
    expected_tickers: i64,
    answered_ticker_subs: HashSet<i64>,
    total_portfolio_subs: usize,
    total_scoped_cash_subs: usize,
}

fn put_position(list: &mut Vec<(String, Value)>, isin: &str, position: &Value) {
    match list.iter_mut().find(|(i, _)| i == isin) {
        Some(entry) => entry.1 = position.clone(),
        None => list.push((isin.to_owned(), position.clone())),
    }
}

impl<'a> Exchange<'a> {
    fn new(token: &'a str, sec_accounts: &'a [SecAccount]) -> Self {
        let total_portfolio_subs = sec_accounts.iter().map(|a| a.account_numbers.len()).sum();
        let total_scoped_cash_subs = sec_accounts
            .iter()
            .filter(|a| a.account_type == AccountType::Pea)
            .map(|a| a.cash_account_numbers.len())
            .sum();
        Self {
            token,
            sec_accounts,
            next_id: 0,
            cash_json: None,
            scoped_cash_json: HashMap::new(),
            positions_by_sec_acc: HashMap::new(),
            crypto_positions: Vec::new(),
            ticker_prices: HashMap::new(),
            bond_isins: HashSet::new(),
            unpriced_bond_isins: HashSet::new(),
            ticker_subs: HashMap::new(),
            instrument_subs: HashMap::new(),
            resolving_isins: HashSet::new(),
            portfolio_subs: HashMap::new(),
            scoped_cash_subs: HashMap::new(),
            received_portfolio_subs: HashSet::new(),
            errored_portfolio_subs: HashSet::new(),
            received_scoped_cash_subs: HashSet::new(),
            auth_expired: false,
            expected_tickers: -1,
            answered_ticker_subs: HashSet::new(),
            total_portfolio_subs,
            total_scoped_cash_subs,
        }
    }

    fn id(&mut self) -> i64 {
        self.next_id += 1;
        self.next_id
    }

    fn expect_no_tickers_yet(&mut self) {
        if self.expected_tickers == -1 {
            self.expected_tickers = 0;
        }
    }

    /// The frame's effect; returns the subscriptions to send in answer.
    fn on_frame(&mut self, text: &str) -> Vec<String> {
        tracing::info!("TR WS <-- {}", describe_frame(text));
        if text.trim() == "connected" {
            return self.on_connected();
        }
        let ws_id = extract_ws_id(text);
        let payload = extract_ws_payload(text);
        if payload.contains("AUTHENTICATION_ERROR") {
            tracing::warn!("TR WS: session expired (AUTHENTICATION_ERROR)");
            self.auth_expired = true;
            return Vec::new();
        }
        if ws_id == 1 {
            self.cash_json = Some(payload.to_owned());
        } else if self.scoped_cash_subs.contains_key(&ws_id) {
            self.received_scoped_cash_subs.insert(ws_id);
            if extract_ws_type(text) != "E" {
                self.scoped_cash_json.insert(ws_id, payload.to_owned());
            }
        } else if let Some((index, sec_no)) = self.portfolio_subs.get(&ws_id).cloned() {
            return self.on_portfolio(ws_id, index, &sec_no, text, payload);
        } else if let Some(isin) = self.instrument_subs.remove(&ws_id) {
            if self.bond_isins.contains(&isin) && !is_eur_nominal(payload) {
                self.unpriced_bond_isins.insert(isin.clone());
            }
            let ticker_id = format!("{isin}.{}", ticker_exchange(payload));
            let tid = self.id();
            self.ticker_subs.insert(tid, isin);
            tracing::info!("TR WS --> sub {tid} ticker {ticker_id}");
            return vec![sub(
                tid,
                &json!({ "type": "ticker", "id": ticker_id, "token": self.token }),
            )];
        } else if let Some(isin) = self.ticker_subs.get(&ws_id).cloned()
            && self.answered_ticker_subs.insert(ws_id)
        {
            self.on_ticker(&isin, payload);
        }
        Vec::new()
    }

    fn on_connected(&mut self) -> Vec<String> {
        let mut messages = Vec::new();
        let id = self.id();
        messages.push(sub(
            id,
            &json!({ "type": "availableCash", "token": self.token }),
        ));
        tracing::info!("TR WS --> sub {id} availableCash");
        for (index, account) in self.sec_accounts.iter().enumerate() {
            if account.account_type == AccountType::Pea {
                for cash_no in &account.cash_account_numbers {
                    self.next_id += 1;
                    let id = self.next_id;
                    self.scoped_cash_subs.insert(id, (index, cash_no.clone()));
                    messages.push(sub(
                        id,
                        &json!({
                            "type": "availableCash",
                            "accountNumber": cash_no,
                            "token": self.token,
                        }),
                    ));
                    tracing::info!("TR WS --> sub {id} availableCash account={}", account.name);
                }
            }
        }
        if self.total_portfolio_subs == 0 {
            tracing::info!("TR WS: no securities account in JWT, skipping portfolio subscription");
            self.expected_tickers = 0;
        } else {
            for (index, account) in self.sec_accounts.iter().enumerate() {
                for sec_no in &account.account_numbers {
                    self.next_id += 1;
                    let id = self.next_id;
                    self.portfolio_subs.insert(id, (index, sec_no.clone()));
                    messages.push(sub(
                        id,
                        &json!({
                            "type": "compactPortfolioByType",
                            "secAccNo": sec_no,
                            "token": self.token,
                        }),
                    ));
                    tracing::info!(
                        "TR WS --> sub {id} compactPortfolioByType account={}",
                        account.name
                    );
                }
            }
        }
        messages
    }

    fn on_portfolio(
        &mut self,
        ws_id: i64,
        index: usize,
        sec_no: &str,
        text: &str,
        payload: &str,
    ) -> Vec<String> {
        let account = &self.sec_accounts[index];
        // Counted once per subscription: a later frame must not complete the wait while another
        // subscription is still silent.
        self.received_portfolio_subs.insert(ws_id);
        tracing::info!(
            "TR compactPortfolioByType [{}] answered ({} chars)",
            account.name,
            payload.encode_utf16().count()
        );
        if extract_ws_type(text) == "E" {
            self.errored_portfolio_subs.insert(ws_id);
            self.expect_no_tickers_yet();
            tracing::warn!(
                "TR compactPortfolioByType [{}] answered with an error frame -- keeping the previous sync's holdings",
                account.name
            );
            return Vec::new();
        }
        let Ok(root) = serde_json::from_str::<Value>(payload) else {
            tracing::error!("Failed to parse compactPortfolioByType [{}]", account.name);
            self.errored_portfolio_subs.insert(ws_id);
            self.expect_no_tickers_yet();
            return Vec::new();
        };
        self.errored_portfolio_subs.remove(&ws_id);
        self.positions_by_sec_acc
            .remove(&(index, sec_no.to_owned()));
        let mut positions: Vec<(String, &Value)> = Vec::new();
        match root.get("categories") {
            Some(Value::Array(categories)) => {
                for category in categories {
                    let category_type = as_text(category.get("categoryType"), "");
                    let bond_category = is_bond(&category_type);
                    for position in elements(category.get("positions")) {
                        if bond_category {
                            let isin = position_isin(position);
                            if !isin.is_empty() {
                                self.bond_isins.insert(isin);
                            }
                        }
                        positions.push((category_type.clone(), position));
                    }
                }
            }
            _ => {
                let array = if root.is_array() {
                    Some(&root)
                } else {
                    root.get("positions")
                };
                if let Some(Value::Array(items)) = array {
                    positions.extend(items.iter().map(|p| (String::new(), p)));
                }
            }
        }
        if positions.is_empty() {
            self.expect_no_tickers_yet();
            tracing::info!(
                "TR compactPortfolioByType [{}]: no positions found",
                account.name
            );
            return Vec::new();
        }
        let split_native_crypto = account.wrapper == "default";
        let name = account.name.clone();
        let mut instrument_messages = Vec::new();
        for (category_type, position) in &positions {
            let isin = position_isin(position);
            if isin.is_empty() {
                continue;
            }
            let instrument_type = as_text(position.get("instrumentType"), "");
            if is_bond(&instrument_type) {
                self.bond_isins.insert(isin.clone());
            }
            if split_native_crypto && is_native_crypto(category_type, &instrument_type) {
                put_position(&mut self.crypto_positions, &isin, position);
            } else {
                put_position(
                    self.positions_by_sec_acc
                        .entry((index, sec_no.to_owned()))
                        .or_default(),
                    &isin,
                    position,
                );
            }
            if instrument_type == "privateFund" {
                // Not publicly traded: TR never sends a price tick for them.
                tracing::info!(
                    "TR compactPortfolioByType [{name}]: skipping ticker subscription for privateFund {isin}"
                );
            } else if self.resolving_isins.insert(isin.clone()) {
                // The instrument answer names the venue the ticker is subscribed on.
                let id = self.id();
                self.instrument_subs.insert(id, isin.clone());
                instrument_messages.push(sub(
                    id,
                    &json!({ "type": "instrument", "id": isin, "token": self.token }),
                ));
            }
        }
        self.expected_tickers =
            self.expected_tickers.max(0) + i64::try_from(instrument_messages.len()).unwrap_or(0);
        tracing::info!(
            "TR compactPortfolioByType [{name}]: {} positions, resolving {} instruments",
            positions.len(),
            instrument_messages.len()
        );
        instrument_messages
    }

    fn on_ticker(&mut self, isin: &str, payload: &str) {
        let Ok(root) = serde_json::from_str::<Value>(payload) else {
            tracing::warn!("Failed to parse ticker for {isin}");
            return;
        };
        let price = as_text_or_none(root.get("last").and_then(|l| l.get("price")));
        match price {
            Some(_) if self.unpriced_bond_isins.contains(isin) => {
                tracing::warn!(
                    "TR bond {isin}: nominal currency is not EUR or unknown -- current price left empty"
                );
            }
            Some(text) => match big_decimal(&text) {
                Some(quoted) => {
                    let bond = self.bond_isins.contains(isin);
                    self.ticker_prices
                        .insert(isin.to_owned(), ticker_price_in_eur(quoted, bond));
                }
                None => tracing::warn!("Failed to parse ticker for {isin}"),
            },
            None => tracing::warn!("TR ticker for {isin} -- no last.price"),
        }
    }

    /// None when the account has no cash pocket, when any pocket timed out, or when every pocket
    /// answered with an error frame (so the sync keeps the last known cash balance).
    fn pea_cash_for(&self, index: usize) -> Option<Decimal> {
        let subs: Vec<(i64, &str)> = self
            .scoped_cash_subs
            .iter()
            .filter_map(|(&ws_id, (sub_index, cash_no))| {
                (*sub_index == index).then_some((ws_id, cash_no.as_str()))
            })
            .collect();
        if subs.is_empty()
            || !subs
                .iter()
                .all(|(ws_id, _)| self.received_scoped_cash_subs.contains(ws_id))
        {
            return None;
        }
        let mut any_ok = false;
        let mut total = Decimal::ZERO;
        for (ws_id, cash_no) in subs {
            if let Some(payload) = self.scoped_cash_json.get(&ws_id) {
                any_ok = true;
                total += parse_scoped_cash_value(payload, cash_no);
            }
        }
        any_ok.then_some(total)
    }

    fn all_answered(&self) -> bool {
        if self.auth_expired {
            return true;
        }
        let cash_done = self.cash_json.is_some()
            && self.received_scoped_cash_subs.len() >= self.total_scoped_cash_subs;
        let all_portfolios_in = self.received_portfolio_subs.len() >= self.total_portfolio_subs;
        let tickers_done = all_portfolios_in
            && self.expected_tickers >= 0
            && i64::try_from(self.answered_ticker_subs.len()).unwrap_or(i64::MAX)
                >= self.expected_tickers;
        cash_done && tickers_done
    }
}

/// new API: "isin"; legacy: "instrumentId".
fn position_isin(position: &Value) -> String {
    let fallback = as_text(position.get("instrumentId"), "");
    as_text(position.get("isin"), &fallback)
}

/// Reads frames until everything expected has answered or the cap elapsed; a dropped socket or a
/// failed send ends the read with the frames received so far.
async fn read_portfolio<S>(client: &mut ws::Client<S>, exchange: &mut Exchange<'_>)
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    let deadline = tokio::time::Instant::now() + WS_WAIT_CAP;
    loop {
        let text = match tokio::time::timeout_at(deadline, client.next_text()).await {
            Err(_) => return,
            Ok(Ok(Some(text))) => text,
            Ok(Ok(None)) => return,
            Ok(Err(e)) => {
                tracing::warn!(
                    "TR WebSocket read ended early ({:?}), continuing with the frames received so far",
                    e.kind()
                );
                return;
            }
        };
        for message in exchange.on_frame(&text) {
            if let Err(e) = client.send_text(&message).await {
                tracing::warn!(
                    "TR WebSocket read ended early ({:?}), continuing with the frames received so far",
                    e.kind()
                );
                return;
            }
        }
        if exchange.all_answered() {
            return;
        }
    }
}

/// Connects to the WebSocket and fetches the current balances of every sub-portfolio.
pub async fn fetch_accounts(
    state: &AppState,
    session_token: &str,
    claims: Option<&Value>,
) -> JobResult<Vec<TrAccountData>> {
    tracing::info!("Fetching TR portfolio via WebSocket (protocol v{WS_VERSION})");
    let sec_accounts = extract_sec_accounts(session_token, claims);
    tracing::info!(
        "TR JWT sec accounts: {:?}",
        sec_accounts
            .iter()
            .map(|a| format!("{}:{}", a.wrapper, a.name))
            .collect::<Vec<_>>()
    );
    let mut exchange = Exchange::new(session_token, &sec_accounts);
    let url = state.connectors.trade_republic.ws_url();
    let cookie = format!("tr_session={session_token}");
    let read = async {
        let mut client = ws::connect(
            &state.http,
            &url,
            &[
                ("Origin", "https://app.traderepublic.com"),
                ("Cookie", cookie.as_str()),
            ],
        )
        .await?;
        client.send_text(&connect_message()).await?;
        read_portfolio(&mut client, &mut exchange).await;
        Ok::<_, std::io::Error>(())
    };
    match tokio::time::timeout(WS_HARD_TIMEOUT, read).await {
        Ok(Ok(())) => {}
        Ok(Err(e)) => {
            return Err(AppError::Internal(format!("TR WebSocket failed: {:?}", e.kind())).into());
        }
        Err(_) => return Err(AppError::Internal("TR WebSocket timed out".to_owned()).into()),
    }
    build_accounts(&exchange)
}

fn build_accounts(exchange: &Exchange<'_>) -> JobResult<Vec<TrAccountData>> {
    if exchange.auth_expired {
        // A token the sidecar just minted was refused: not proof the stored session is dead.
        return Err(SyncError::new(
            SidecarErrorCode::UpstreamUnavailable,
            "Trade Republic refused the session token. Please try again.",
        )
        .into());
    }
    let invalid = |e: String| JobError::App(AppError::Internal(format!("TR portfolio: {e}")));
    let mut accounts = Vec::new();
    for (index, account) in exchange.sec_accounts.iter().enumerate() {
        let mut positions = Vec::new();
        for sec_no in &account.account_numbers {
            if let Some(sub_positions) = exchange.positions_by_sec_acc.get(&(index, sec_no.clone()))
            {
                positions.extend(sub_positions.iter().cloned());
            }
        }
        // None when the pocket is unknown, not zero: the sync keeps the last known one.
        let pea_cash = if account.account_type == AccountType::Pea {
            exchange.pea_cash_for(index)
        } else {
            None
        };
        let valued =
            value_portfolio(&positions, &exchange.ticker_prices, pea_cash).map_err(invalid)?;
        tracing::info!(
            "TR portfolio [{}]: {} positions, {} with live prices",
            account.name,
            positions.len(),
            valued.priced
        );
        let sub_ids: Vec<i64> = exchange
            .portfolio_subs
            .iter()
            .filter_map(|(&ws_id, (sub_index, _))| (*sub_index == index).then_some(ws_id))
            .collect();
        let answered = !sub_ids.is_empty()
            && sub_ids
                .iter()
                .all(|ws_id| exchange.received_portfolio_subs.contains(ws_id));
        let any_errored = sub_ids
            .iter()
            .any(|ws_id| exchange.errored_portfolio_subs.contains(ws_id));
        let errored = any_errored || (sub_ids.len() > 1 && !answered);
        if !should_persist(
            valued.total_eur,
            valued.positions.is_empty(),
            answered,
            errored,
        ) {
            if errored {
                tracing::warn!(
                    "TR portfolio [{}]: no usable portfolio answer this sync -- the account keeps its previous holdings and balance",
                    account.name
                );
            }
            continue;
        }
        accounts.push(TrAccountData {
            external_id: account.external_id.clone(),
            name: account.name.clone(),
            account_type: account.account_type,
            balance_eur: valued.total_eur,
            positions: valued.positions,
            cash_eur: pea_cash,
        });
    }
    if !exchange.crypto_positions.is_empty() {
        let valued = value_portfolio(&exchange.crypto_positions, &exchange.ticker_prices, None)
            .map_err(invalid)?;
        tracing::info!(
            "TR portfolio [TR Crypto]: {} positions, {} with live prices",
            exchange.crypto_positions.len(),
            valued.priced
        );
        if !valued.positions.is_empty() {
            accounts.push(TrAccountData {
                external_id: EXTERNAL_ID_CRYPTO.to_owned(),
                name: "TR Crypto".to_owned(),
                account_type: AccountType::Crypto,
                balance_eur: valued.total_eur,
                positions: valued.positions,
                cash_eur: None,
            });
        }
    }
    if let Some(cash) = &exchange.cash_json
        && accounts.iter().all(|a| a.external_id != EXTERNAL_ID_CASH)
    {
        let value = parse_cash_value(cash);
        if value >= Decimal::ZERO {
            accounts.push(TrAccountData {
                external_id: EXTERNAL_ID_CASH.to_owned(),
                name: "TR Cash".to_owned(),
                account_type: AccountType::Checking,
                balance_eur: value,
                positions: Vec::new(),
                cash_eur: None,
            });
        }
    }
    if accounts.is_empty() {
        return Err(SyncError::uncoded(
            "No portfolio data received from Trade Republic. Please try again later.",
        )
        .into());
    }
    tracing::info!("TR portfolio fetched: {} account(s)", accounts.len());
    Ok(accounts)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(s: &str) -> Decimal {
        s.parse().unwrap()
    }

    #[test]
    fn should_persist_skips_an_errored_portfolio_without_positions() {
        assert!(!should_persist(d("500"), true, true, true));
        assert!(should_persist(Decimal::ZERO, true, true, false));
        assert!(should_persist(Decimal::ZERO, false, true, true));
        assert!(!should_persist(Decimal::ZERO, true, false, false));
    }

    #[test]
    fn bond_and_crypto_classifications_are_exact() {
        assert!(is_bond("bond") && is_bond("BONDS"));
        assert!(!is_bond("bondEtf") && !is_bond("fund"));
        assert!(is_native_crypto("cryptos", "") && is_native_crypto("", "crypto"));
        assert!(!is_native_crypto("stocksAndETFs", "fund"));
    }

    #[test]
    fn a_bond_ticker_is_a_percentage_of_its_nominal() {
        assert_eq!(ticker_price_in_eur(d("98.25"), true).to_string(), "0.9825");
        assert_eq!(ticker_price_in_eur(d("98.25"), false), d("98.25"));
    }

    #[test]
    fn the_nominal_currency_fails_closed() {
        assert!(is_eur_nominal(r#"{"bondInfo":{"currency":"EUR"}}"#));
        assert!(!is_eur_nominal(
            r#"{"bondInfo":{"currency":"USD"},"currencyId":"EUR"}"#
        ));
        assert!(is_eur_nominal(r#"{"currencyId":"eur"}"#));
        assert!(is_eur_nominal(r#"{"currencies":[{"id":"EUR"}]}"#));
        assert!(!is_eur_nominal(r#"{}"#));
        assert!(!is_eur_nominal("not json"));
    }

    #[test]
    fn lsx_wins_then_the_first_listed_venue() {
        assert_eq!(ticker_exchange(r#"{"exchangeIds":["BHS","LSX"]}"#), "LSX");
        assert_eq!(ticker_exchange(r#"{"exchangeIds":["BHS"]}"#), "BHS");
        assert_eq!(ticker_exchange(r#"{"errors":[]}"#), "LSX");
    }

    #[test]
    fn a_frame_is_described_without_its_payload() {
        assert_eq!(describe_frame("connected"), "connected");
        assert_eq!(describe_frame(r#"3 A {"iban":"FR76"}"#), "3 A (19 chars)");
        assert_eq!(
            describe_frame("something long without spaces at all"),
            "(36 chars)"
        );
    }

    #[test]
    fn sec_accounts_come_from_the_claims_default_wrapper_first() {
        let claims = json!({"act": {"acc": {"owner": {
            "tax_wrapper_fr": {"sec": ["PEA1"], "cash": ["PCASH"]},
            "default": {"sec": ["SEC1", "SEC2"], "cash": ["CASH1"]},
            "tax_wrapper_pl_ike": {"sec": ["IKE1"]}
        }}}});
        let accounts = extract_sec_accounts("opaque", Some(&claims));
        let ids: Vec<_> = accounts
            .iter()
            .map(|a| (a.external_id.as_str(), a.name.as_str(), a.account_type))
            .collect();
        assert_eq!(
            ids,
            vec![
                ("tr_securities", "TR Titres", AccountType::CompteTitres),
                ("tr_securities_2", "TR Titres 2", AccountType::CompteTitres),
                ("tr_pea", "TR PEA", AccountType::Pea),
                ("tr_tax_wrapper_pl_ike", "TR IKE", AccountType::Brokerage),
            ]
        );
        assert_eq!(accounts[0].cash_account_numbers, vec!["CASH1"]);
        assert!(accounts[1].cash_account_numbers.is_empty());
        assert_eq!(accounts[2].account_numbers, vec!["PEA1"]);
        assert_eq!(accounts[2].cash_account_numbers, vec!["PCASH"]);
    }

    #[test]
    fn tax_wrapper_fr_consolidates_multiple_sec_and_cash_entries_into_one_pea() {
        let claims = json!({"act": {"acc": {"owner": {
            "default": {"sec": ["SEC1", "SEC2"], "cash": ["CASH1"]},
            "tax_wrapper_fr": {
                "sec": ["PEA1", " ", "PEA2", "PEA2", "PEA3"],
                "cash": ["PCASH1", "PCASH2", "PCASH2", "PCASH3"]
            }
        }}}});
        let accounts = extract_sec_accounts("opaque", Some(&claims));
        let ids: Vec<_> = accounts
            .iter()
            .map(|a| (a.external_id.as_str(), a.name.as_str(), a.account_type))
            .collect();
        assert_eq!(
            ids,
            vec![
                ("tr_securities", "TR Titres", AccountType::CompteTitres),
                ("tr_securities_2", "TR Titres 2", AccountType::CompteTitres),
                ("tr_pea", "TR PEA", AccountType::Pea),
            ]
        );
        assert_eq!(accounts[2].account_numbers, vec!["PEA1", "PEA2", "PEA3"]);
        assert_eq!(
            accounts[2].cash_account_numbers,
            vec!["PCASH1", "PCASH2", "PCASH3"]
        );
    }

    #[test]
    fn scoped_cash_matches_requested_account_number_when_present() {
        let multi =
            r#"[{"accountNumber":"CASH1","amount":500},{"accountNumber":"PCASH3","amount":80}]"#;
        assert_eq!(parse_scoped_cash_value(multi, "PCASH3"), d("80"));
        assert_eq!(parse_scoped_cash_value(multi, "PCASH1"), Decimal::ZERO);
        assert_eq!(
            parse_scoped_cash_value(r#"{"accountNumber":"CASH1","value":"500"}"#, "PCASH1"),
            Decimal::ZERO
        );
        assert_eq!(
            parse_scoped_cash_value(r#"{"accountNumber":"PCASH1","value":"250"}"#, "PCASH1"),
            d("250")
        );
        assert_eq!(
            parse_scoped_cash_value(r#"{"value":"250"}"#, "PCASH1"),
            d("250")
        );
        assert_eq!(
            parse_scoped_cash_value(r#"[{"amount":12.5}]"#, "PCASH1"),
            d("12.5")
        );
    }

    #[test]
    fn cash_reads_value_or_amount_and_an_unreadable_answer_is_zero() {
        assert_eq!(parse_cash_value(r#"[{"amount":100}]"#), d("100"));
        assert_eq!(
            parse_cash_value(r#"[{"value":"-1"},{"value":"12.5"}]"#),
            d("12.5")
        );
        assert_eq!(parse_cash_value(r#"{"value":7}"#), d("7"));
        assert_eq!(parse_cash_value(r#"{"errors":[]}"#), Decimal::ZERO);
        assert_eq!(parse_cash_value("garbage"), Decimal::ZERO);
    }
}
