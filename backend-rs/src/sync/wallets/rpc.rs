//! What the adapters share: the error that fails a wallet sync, JSON-RPC envelope validation
//! (JsonRpcResponse.requireResult) and Jackson's lenient JsonNode reads.

use std::time::Duration;

use rust_decimal::Decimal;
use rust_decimal::prelude::ToPrimitive;
use serde_json::Value;

use crate::AppState;

/// Every upstream call is bounded by this, as the Java adapters' WebClient timeouts.
pub const CALL_TIMEOUT: Duration = Duration::from_secs(10);

/// A failed balance fetch. expected is WalletRpcException's "routine sync problem" (a WARN);
/// anything else is logged as a bug (an ERROR). Both reach the user as the same 422.
#[derive(Debug)]
pub struct WalletError {
    pub message: String,
    pub expected: bool,
}

impl WalletError {
    pub fn rpc(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            expected: true,
        }
    }

    pub fn unexpected(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            expected: false,
        }
    }
}

/// One asset held at an address.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WalletBalance {
    pub symbol: String,
    pub amount: Decimal,
}

impl WalletBalance {
    pub fn new(symbol: &str, amount: Decimal) -> Self {
        Self {
            symbol: symbol.to_owned(),
            amount,
        }
    }
}

/// A usable JSON-RPC 2.0 response carries a result and no error; a legitimate zero result is
/// kept apart from a missing one, which must never read as a 0 balance.
pub fn require_result<'a>(response: &'a Value, context: &str) -> Result<&'a Value, WalletError> {
    if let Some(error) = response.get("error").filter(|e| !e.is_null()) {
        return Err(WalletError::rpc(format!(
            "{context}: RPC error {} - {}",
            text_or(error.get("code"), "?"),
            text_or(error.get("message"), "unknown")
        )));
    }
    match response.get("result") {
        Some(result) if !result.is_null() => Ok(result),
        _ => Err(WalletError::rpc(format!(
            "{context}: RPC response missing 'result'"
        ))),
    }
}

/// The upstream failure of one HTTP exchange: transport, status or an unparseable body.
#[derive(Debug)]
pub struct CallError(pub String);

/// Sends the request and parses the body; an empty body is None, as WebClient's bodyToMono
/// completes empty.
pub async fn send(request: reqwest::RequestBuilder) -> Result<Option<Value>, CallError> {
    let response = request
        .timeout(CALL_TIMEOUT)
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .map_err(|e| CallError(without_url(&e)))?;
    let body = response
        .bytes()
        .await
        .map_err(|e| CallError(without_url(&e)))?;
    if body.is_empty() {
        return Ok(None);
    }
    serde_json::from_slice(&body)
        .map(Some)
        .map_err(|e| CallError(format!("unreadable body: {e}")))
}

/// Bitcoin URLs carry the address in their path, so they stay out of the logs.
fn without_url(error: &reqwest::Error) -> String {
    let kind = if error.is_timeout() {
        "timeout"
    } else if error.is_connect() {
        "connection failed"
    } else if let Some(status) = error.status() {
        return format!("HTTP {status}");
    } else {
        "request failed"
    };
    kind.to_owned()
}

/// A POST of a JSON-RPC request.
pub fn post_json(state: &AppState, url: &str, request: &Value) -> reqwest::RequestBuilder {
    state.http.post(url).json(request)
}

/// JsonNode.asText(): a string as is, a number or boolean as its text, null as "null", a
/// container as "".
pub fn as_text(value: &Value) -> String {
    match value {
        Value::String(s) => s.clone(),
        Value::Number(n) => n.to_string(),
        Value::Bool(b) => b.to_string(),
        Value::Null => "null".to_owned(),
        Value::Array(_) | Value::Object(_) => String::new(),
    }
}

/// JsonNode.path(..).asText(default): the default for a missing or null node.
pub fn text_or(value: Option<&Value>, default: &str) -> String {
    match value {
        None | Some(Value::Null) => default.to_owned(),
        Some(value) => as_text(value),
    }
}

/// JsonNode.path(..).asLong(0): numbers truncated toward zero, numeric text parsed, true as 1.
pub fn as_long(value: Option<&Value>) -> i64 {
    let truncated = |text: &str| -> Option<i64> {
        text.parse::<i64>()
            .ok()
            .or_else(|| parse_decimal(text).and_then(|d| d.trunc().to_i64()))
    };
    match value {
        Some(Value::Number(n)) => truncated(&n.to_string()).unwrap_or(0),
        Some(Value::String(s)) => truncated(s.trim()).unwrap_or(0),
        Some(Value::Bool(b)) => i64::from(*b),
        _ => 0,
    }
}

/// new BigDecimal(text): plain or scientific notation.
pub fn parse_decimal(text: &str) -> Option<Decimal> {
    text.parse::<Decimal>()
        .ok()
        .or_else(|| Decimal::from_scientific(text).ok())
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[test]
    fn require_result_keeps_legitimate_zeros() {
        assert_eq!(
            require_result(&json!({"result": "0x0"}), "c").unwrap(),
            &json!("0x0")
        );
        assert_eq!(
            require_result(&json!({"result": 0}), "c").unwrap(),
            &json!(0)
        );
        assert_eq!(
            require_result(&json!({"result": []}), "c").unwrap(),
            &json!([])
        );
        assert_eq!(
            require_result(&json!({"result": "0x1", "error": null}), "c").unwrap(),
            &json!("0x1")
        );
    }

    #[test]
    fn require_result_rejects_errors_and_missing_results() {
        let error = require_result(
            &json!({"error": {"code": -32000, "message": "boom"}}),
            "Ethereum eth_getBalance",
        )
        .unwrap_err();
        assert_eq!(
            error.message,
            "Ethereum eth_getBalance: RPC error -32000 - boom"
        );
        assert!(error.expected);
        assert!(require_result(&json!({}), "c").is_err());
        assert!(require_result(&json!({"result": null}), "c").is_err());
        assert!(require_result(&Value::Null, "c").is_err());
    }

    #[test]
    fn as_long_reads_like_jackson() {
        assert_eq!(as_long(Some(&json!(42))), 42);
        assert_eq!(as_long(Some(&json!("17"))), 17);
        assert_eq!(as_long(Some(&json!(1.9))), 1);
        assert_eq!(as_long(Some(&json!({}))), 0);
        assert_eq!(as_long(None), 0);
    }
}
