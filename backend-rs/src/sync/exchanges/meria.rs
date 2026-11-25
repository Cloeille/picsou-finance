//! MeriaAdapter: a single read-only key in an API-KEY header, no secret, no signing. A balance
//! is spread over GET /wallets, /stakings and /lendings; every call must succeed or the whole
//! read fails, since the three sum into one account balance written to a daily snapshot.

use std::collections::BTreeMap;
use std::time::Duration;

use async_trait::async_trait;
use rust_decimal::Decimal;
use serde_json::{Map, Value};

use super::model::{ExchangeCredentials, ExchangePosition, Product};
use super::port::{
    ExchangePort, FetchError, describe, is_blank, is_json, json_decimal, scalar_text, truncate,
};
use crate::AppState;
use crate::ledger::sync_values::java_is_blank;
use crate::native_sidecars::common::{ReadBodyError, read_capped_bytes};

const TIMEOUT: Duration = Duration::from_secs(10);
const API_KEY_HEADER: &str = "API-KEY";
/// /stakings carries every contract's full reward history: megabytes for an old account.
const MAX_RESPONSE_BYTES: usize = 16 * 1024 * 1024;
const STATUS_MAX_RESPONSE_BYTES: usize = 256 * 1024;

pub struct Meria<'a> {
    http: &'a reqwest::Client,
    base_url: &'a str,
}

/// One entry of /wallets, /stakings or /lendings: the currency and the amount held.
struct Entry {
    currency_code: Option<String>,
    quantity: Option<Decimal>,
    reward: Option<Decimal>,
}

#[derive(Clone, Copy)]
enum Shape {
    Wallet,
    Contract,
}

fn unreadable(path: &str) -> FetchError {
    FetchError::Sync(format!("Meria returned an unreadable response for {path}."))
}

impl<'a> Meria<'a> {
    pub fn new(state: &'a AppState) -> Self {
        Self {
            http: &state.http,
            base_url: &state.config.meria_base_url,
        }
    }

    fn url(&self, path: &str) -> String {
        format!("{}{path}", self.base_url.trim_end_matches('/'))
    }

    /// One Meria collection, or a SyncException. Never an empty list standing in for a failure.
    async fn fetch(
        &self,
        path: &str,
        api_key: &str,
        shape: Shape,
    ) -> Result<Vec<Entry>, FetchError> {
        let response = self
            .http
            .get(self.url(path))
            .header(API_KEY_HEADER, api_key)
            .header(reqwest::header::ACCEPT, "application/json")
            .timeout(TIMEOUT)
            .send()
            .await
            .map_err(|e| transport_failure(path, &e))?;
        let status = response.status().as_u16();
        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("<none>")
            .to_owned();
        let body = read_limited(response, path).await?;

        if !(200..300).contains(&status) {
            tracing::warn!(
                "Meria answered HTTP {status} for {path} (content-type {content_type}) -- failing the sync: {}",
                truncate(&body)
            );
            return Err(FetchError::Sync(match status {
                401 | 403 => "Meria rejected the API key.".to_owned(),
                429 => "Meria rate-limited the sync. Please try again later.".to_owned(),
                _ => format!("Meria answered HTTP {status} for {path}."),
            }));
        }
        if is_blank(&body) {
            // Meria answers a product the user has not subscribed to with 200 and no body.
            tracing::info!(
                "Meria returned an empty body for {path} on HTTP {status} (content-type {content_type}) -- reading it as no entries"
            );
            return Ok(Vec::new());
        }
        let Ok(envelope) = serde_json::from_slice::<Value>(&body) else {
            tracing::warn!(
                "Could not parse Meria's response for {path} -- failing the sync: {}",
                truncate(&body)
            );
            return Err(unreadable(path));
        };
        let envelope = match envelope {
            Value::Null => {
                return Err(FetchError::Sync(format!(
                    "Meria returned an empty response for {path}."
                )));
            }
            Value::Object(map) => map,
            _ => {
                tracing::warn!(
                    "Could not parse Meria's response for {path} -- failing the sync: {}",
                    truncate(&body)
                );
                return Err(unreadable(path));
            }
        };
        let parsed = parse_envelope(&envelope, shape).ok_or_else(|| {
            tracing::warn!(
                "Could not parse Meria's response for {path} -- failing the sync: {}",
                truncate(&body)
            );
            unreadable(path)
        })?;
        // Gate on the envelope, never on the status: Meria can answer success:false on HTTP 200.
        if parsed.success != Some(true) {
            return Err(FetchError::Sync(format!(
                "Meria rejected {path}: {}.",
                parsed.error
            )));
        }
        parsed
            .data
            .into_iter()
            .map(|entry| {
                entry.ok_or_else(|| {
                    FetchError::Unexpected(format!("Meria returned a null entry for {path}"))
                })
            })
            .collect()
    }
}

/// A body read in full, refused past MAX_RESPONSE_BYTES.
async fn read_limited(response: reqwest::Response, path: &str) -> Result<Vec<u8>, FetchError> {
    read_capped_bytes(response, MAX_RESPONSE_BYTES)
        .await
        .map_err(|e| match e {
            ReadBodyError::TooLarge { limit } => {
                tracing::error!(
                    "Meria request for {path} failed unexpectedly (body over {limit} bytes) -- failing the sync"
                );
                FetchError::Sync(format!("Could not read {path} from Meria."))
            }
            ReadBodyError::Transport(err) => transport_failure(path, &err),
        })
}

/// A failed call, always a SyncException; never logs the API key, only the path.
fn transport_failure(path: &str, error: &reqwest::Error) -> FetchError {
    if error.is_timeout() {
        tracing::warn!(
            "Meria request for {path} timed out after {}s -- failing the sync",
            TIMEOUT.as_secs()
        );
        return FetchError::Sync("Meria took too long to answer. Please try again later.".into());
    }
    if error.is_connect() || error.is_request() {
        tracing::warn!(
            "Meria request for {path} could not reach the API ({}) -- failing the sync",
            describe(error)
        );
        return FetchError::Sync("Meria is unreachable. Please try again later.".into());
    }
    tracing::error!(
        "Meria request for {path} failed unexpectedly ({}) -- failing the sync",
        describe(error)
    );
    FetchError::Sync(format!("Could not read {path} from Meria."))
}

struct Envelope {
    success: Option<bool>,
    data: Vec<Option<Entry>>,
    error: String,
}

/// Jackson's Boolean: true, false, or their names as a string.
fn json_bool(value: Option<&Value>) -> Option<Option<bool>> {
    match value {
        None | Some(Value::Null) => Some(None),
        Some(Value::Bool(b)) => Some(Some(*b)),
        Some(Value::String(s)) => match s.as_str() {
            "true" | "True" | "TRUE" => Some(Some(true)),
            "false" | "False" | "FALSE" => Some(Some(false)),
            "" => Some(None),
            _ => None,
        },
        Some(_) => None,
    }
}

fn json_string(value: Option<&Value>) -> Option<Option<String>> {
    match value {
        None => Some(None),
        Some(v) => scalar_text(v).ok(),
    }
}

fn json_integer(value: Option<&Value>) -> Option<Option<i64>> {
    match value {
        None | Some(Value::Null) => Some(None),
        Some(Value::Number(n)) => n.as_i64().map(Some),
        Some(Value::String(s)) if s.trim().is_empty() => Some(None),
        Some(Value::String(s)) => s.trim().parse().ok().map(Some),
        Some(_) => None,
    }
}

/// MeriaEnvelope<T>: success, data, error; None when a field cannot be bound.
fn parse_envelope(envelope: &Map<String, Value>, shape: Shape) -> Option<Envelope> {
    let success = json_bool(envelope.get("success"))?;
    let data = match envelope.get("data") {
        None | Some(Value::Null) => Vec::new(),
        Some(Value::Array(items)) => items
            .iter()
            .map(|item| match item {
                Value::Null => Some(None),
                Value::Object(entry) => parse_entry(entry, shape).map(Some),
                _ => None,
            })
            .collect::<Option<Vec<_>>>()?,
        Some(_) => return None,
    };
    let error = match envelope.get("error") {
        None | Some(Value::Null) => "no error detail".to_owned(),
        Some(Value::Object(error)) => {
            let code = json_integer(error.get("code"))?
                .map_or_else(|| "null".to_owned(), |c| c.to_string());
            match json_string(error.get("message"))? {
                None => format!("code {code}"),
                Some(message) => format!("{message} (code {code})"),
            }
        }
        Some(_) => return None,
    };
    Some(Envelope {
        success,
        data,
        error,
    })
}

fn parse_entry(entry: &Map<String, Value>, shape: Shape) -> Option<Entry> {
    let currency_code = json_string(entry.get("currencyCode"))?;
    match shape {
        Shape::Wallet => Some(Entry {
            currency_code,
            quantity: json_decimal(entry.get("balance")).ok()?,
            reward: None,
        }),
        Shape::Contract => {
            let quantity = json_decimal(entry.get("amount")).ok()?;
            let reward = json_decimal(entry.get("reward")).ok()?;
            // Bound but unused: whether it sits inside amount could not be established.
            json_decimal(entry.get("lockedReward")).ok()?;
            Some(Entry {
                currency_code,
                quantity,
                reward,
            })
        }
    }
}

/// The ticker for a Meria currency code, or None if it cannot be used.
fn symbol_of(currency_code: Option<&str>) -> Option<String> {
    match currency_code {
        Some(code) if !java_is_blank(code) => {
            Some(code.trim_matches(|c: char| c <= ' ').to_uppercase())
        }
        _ => {
            tracing::warn!("Meria returned an entry with no currencyCode -- skipped");
            None
        }
    }
}

/// Staking or lending contracts as one position per currency. The reward is the interest
/// already inside amount; outside [0, amount] it cannot decompose it and both halves are dropped.
fn contract_positions(product: Product, contracts: Vec<Entry>) -> Vec<ExchangePosition> {
    let mut by_currency: BTreeMap<String, ExchangePosition> = BTreeMap::new();
    for contract in contracts {
        let Some(symbol) = symbol_of(contract.currency_code.as_deref()) else {
            continue;
        };
        let quantity = contract.quantity.unwrap_or(Decimal::ZERO);
        if quantity <= Decimal::ZERO {
            continue;
        }
        let reward = contract.reward.unwrap_or(Decimal::ZERO);
        let decomposable = reward >= Decimal::ZERO && reward <= quantity;
        let position = ExchangePosition {
            product,
            symbol: symbol.clone(),
            quantity,
            principal: decomposable.then(|| quantity - reward),
            interest: decomposable.then_some(reward),
        };
        match by_currency.remove(&symbol) {
            Some(existing) => {
                by_currency.insert(symbol, merge(existing, position));
            }
            None => {
                by_currency.insert(symbol, position);
            }
        }
    }
    by_currency.into_values().collect()
}

/// One undecomposable side makes the merged position undecomposable.
fn merge(a: ExchangePosition, b: ExchangePosition) -> ExchangePosition {
    let quantity = a.quantity + b.quantity;
    let (principal, interest) = match (a.principal, b.principal) {
        (Some(pa), Some(pb)) => (
            Some(pa + pb),
            Some(a.interest.unwrap_or(Decimal::ZERO) + b.interest.unwrap_or(Decimal::ZERO)),
        ),
        _ => (None, None),
    };
    ExchangePosition {
        quantity,
        principal,
        interest,
        ..a
    }
}

#[async_trait]
impl ExchangePort for Meria<'_> {
    fn requires_api_secret(&self) -> bool {
        false
    }

    async fn fetch_positions(
        &self,
        credentials: &ExchangeCredentials,
    ) -> Result<Vec<ExchangePosition>, FetchError> {
        let api_key = credentials.api_key.as_str();
        let mut spot: BTreeMap<String, Decimal> = BTreeMap::new();
        for wallet in self.fetch("/wallets", api_key, Shape::Wallet).await? {
            let Some(symbol) = symbol_of(wallet.currency_code.as_deref()) else {
                continue;
            };
            match wallet.quantity {
                Some(quantity) if quantity > Decimal::ZERO => {
                    *spot.entry(symbol).or_insert(Decimal::ZERO) += quantity;
                }
                _ => {}
            }
        }
        let mut positions: Vec<ExchangePosition> = spot
            .into_iter()
            .map(|(symbol, quantity)| ExchangePosition::spot(symbol, quantity))
            .collect();
        let stakings = self.fetch("/stakings", api_key, Shape::Contract).await?;
        positions.extend(contract_positions(Product::Staking, stakings));
        let lendings = self.fetch("/lendings", api_key, Shape::Contract).await?;
        positions.extend(contract_positions(Product::Lending, lendings));
        tracing::info!(
            "Meria: fetched {} non-zero positions across wallets, stakings and lendings",
            positions.len()
        );
        Ok(positions)
    }

    async fn test_connection(&self, credentials: &ExchangeCredentials) -> Result<bool, FetchError> {
        // /status is documented as the key check; only the envelope flag is asserted.
        let response = match self
            .http
            .get(self.url("/status"))
            .header(API_KEY_HEADER, &credentials.api_key)
            .header(reqwest::header::ACCEPT, "application/json")
            .timeout(TIMEOUT)
            .send()
            .await
        {
            Ok(response) => response,
            Err(e) => {
                tracing::warn!("Meria connection test failed: {}", describe(&e));
                return Ok(false);
            }
        };
        let status = response.status();
        if !status.is_success() {
            tracing::warn!("Meria connection test failed: {status} from GET /status");
            return Ok(false);
        }
        let json = is_json(&response);
        let body = match read_capped_bytes(response, STATUS_MAX_RESPONSE_BYTES).await {
            Ok(body) => body,
            Err(ReadBodyError::Transport(e)) => {
                tracing::warn!("Meria connection test failed: {}", describe(&e));
                return Ok(false);
            }
            Err(ReadBodyError::TooLarge { .. }) => {
                tracing::warn!("Meria connection test failed: unreadable body from GET /status");
                return Ok(false);
            }
        };
        if is_blank(&body) {
            return Ok(false);
        }
        if !json {
            tracing::warn!(
                "Meria connection test failed: unsupported content type from GET /status"
            );
            return Ok(false);
        }
        match serde_json::from_slice::<Value>(&body) {
            Ok(body) => Ok(as_boolean(body.get("success"))),
            Err(_) => {
                tracing::warn!("Meria connection test failed: unreadable body from GET /status");
                Ok(false)
            }
        }
    }
}

/// JsonNode.asBoolean(false).
fn as_boolean(node: Option<&Value>) -> bool {
    match node {
        Some(Value::Bool(b)) => *b,
        Some(Value::Number(n)) => n.as_i64().is_some_and(|i| i != 0),
        Some(Value::String(s)) => s.trim() == "true",
        _ => false,
    }
}
