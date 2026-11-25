//! CryptoExchangePort: what the sync service asks of an exchange adapter, and the lookup that
//! replaces Spring's list of adapter beans.

use async_trait::async_trait;
use rust_decimal::Decimal;
use serde_json::Value;

use super::binance::Binance;
use super::meria::Meria;
use super::model::{ExchangeCredentials, ExchangePosition, ExchangeType};
use super::okx::Okx;
use crate::AppState;

/// Sync and Coded carry safe user-facing diagnostics. Unexpected is logged and replaced
/// by a generic message.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FetchError {
    Sync(String),
    Coded { code: &'static str, detail: String },
    Unexpected(String),
}

#[async_trait]
pub trait ExchangePort: Send + Sync {
    /// Whether the exchange authenticates with a key and a secret.
    fn requires_api_secret(&self) -> bool {
        true
    }

    /// Whether the exchange requires an API passphrase.
    fn requires_api_passphrase(&self) -> bool {
        false
    }

    /// Upper-case region codes the user must choose from; empty when there is no choice.
    fn regions(&self) -> &'static [&'static str] {
        &[]
    }

    /// Every non-zero position, one per (product, asset). Fails rather than return a partial
    /// list: the caller writes the sum into a daily snapshot.
    async fn fetch_positions(
        &self,
        credentials: &ExchangeCredentials,
    ) -> Result<Vec<ExchangePosition>, FetchError>;

    /// Whether these credentials can read the account. A refusal may carry a diagnostic.
    async fn test_connection(&self, credentials: &ExchangeCredentials) -> Result<bool, FetchError>;
}

/// The adapter of an exchange type, None for an exchange Java has no adapter for (KRAKEN).
pub fn adapter(state: &AppState, exchange: ExchangeType) -> Option<Box<dyn ExchangePort + '_>> {
    match exchange {
        ExchangeType::Binance => Some(Box::new(Binance::new(state))),
        ExchangeType::Meria => Some(Box::new(Meria::new(state))),
        ExchangeType::Okx => Some(Box::new(Okx::new(state))),
        ExchangeType::Kraken => None,
    }
}

/// new BigDecimal(text): plain or scientific notation, None when it is not a number.
pub(super) fn parse_big_decimal(text: &str) -> Option<Decimal> {
    if text.is_empty() || text.chars().any(char::is_whitespace) {
        return None;
    }
    if text.contains(['e', 'E']) {
        Decimal::from_scientific(text).ok()
    } else {
        text.parse().ok()
    }
}

/// Jackson's String coercion of a scalar: a string as is, a number or boolean as its text.
/// Ok(None) for null; Err for an array or an object.
pub(super) fn scalar_text(value: &Value) -> Result<Option<String>, ()> {
    match value {
        Value::Null => Ok(None),
        Value::String(s) => Ok(Some(s.clone())),
        Value::Number(n) => Ok(Some(n.to_string())),
        Value::Bool(b) => Ok(Some(b.to_string())),
        Value::Array(_) | Value::Object(_) => Err(()),
    }
}

/// Jackson's BigDecimal reading: a number, or a numeric string; null and "" are null.
pub(super) fn json_decimal(value: Option<&Value>) -> Result<Option<Decimal>, ()> {
    match value {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Number(n)) => parse_big_decimal(&n.to_string()).map(Some).ok_or(()),
        Some(Value::String(s)) if s.trim().is_empty() => Ok(None),
        Some(Value::String(s)) => parse_big_decimal(s.trim()).map(Some).ok_or(()),
        Some(_) => Err(()),
    }
}

/// Whether a response's Content-Type is one Jackson's decoder reads.
pub(super) fn is_json(response: &reqwest::Response) -> bool {
    response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .map(|v| {
            let essence = v
                .split(';')
                .next()
                .unwrap_or_default()
                .trim()
                .to_ascii_lowercase();
            essence == "application/json"
                || (essence.starts_with("application/") && essence.ends_with("+json"))
        })
        .unwrap_or(false)
}

/// The body for a log line: at most 200 characters of its first 800 bytes.
pub(super) fn truncate(body: &[u8]) -> String {
    if body.is_empty() {
        return "<empty body>".to_owned();
    }
    let slice = body.len().min(800);
    let text = String::from_utf8_lossy(&body[..slice]);
    if text.trim().is_empty() {
        return "<empty body>".to_owned();
    }
    if slice == body.len() && text.chars().count() <= 200 {
        return text.into_owned();
    }
    let cut: String = text.chars().take(200).collect();
    format!("{cut}... (truncated)")
}

/// Character.isWhitespace over every byte: nothing but ASCII whitespace.
pub(super) fn is_blank(body: &[u8]) -> bool {
    body.iter()
        .all(|b| matches!(b, b'\t'..=b'\r' | 0x1C..=0x20))
}

/// A transport failure for a log line, without the URL (Binance's carries the signed query).
pub(super) fn describe(error: &reqwest::Error) -> String {
    let kind = if error.is_timeout() {
        "timed out"
    } else if error.is_connect() {
        "connection failed"
    } else if error.is_body() || error.is_decode() {
        "could not read the response body"
    } else {
        "request failed"
    };
    match std::error::Error::source(error) {
        Some(source) => format!("{kind}: {source}"),
        None => kind.to_owned(),
    }
}
