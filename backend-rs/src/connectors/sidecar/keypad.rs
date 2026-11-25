//! What the keypad-login controllers (La Banque Postale, BNP Paribas) share: a body read
//! strictly (an unexpected field, a password typically, is refused, never ignored), the clicked
//! positions, the shuffled keypad check, the 429 with its Retry-After and the disconnect answer.

use axum::http::{HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Validate};
use crate::http::rate_limit::{Policy, Probe};
use crate::ledger::sync_values::java_is_blank;

/// The password is always 6 digits, clicked on a 10-key pad.
pub const POSITIONS_COUNT: usize = 6;
pub const PAD_SIZE: i64 = 10;
pub const PROCESS_ID_MAX: usize = 100;
const KEYPAD_KEY_COUNT: usize = 10;
const KEYPAD_COLUMNS: i32 = 5;

/// A @RequestBody JsonNode: any JSON, checked by hand by the controller.
#[derive(Debug, Deserialize)]
#[serde(transparent)]
pub struct AnyBody(pub Value);

impl Validate for AnyBody {
    fn validate(&self, _: &mut Errors) {}
}

/// A body that carries only the allowed fields; every refusal answers the same fixed 400, so
/// nothing of the input is ever echoed.
pub struct StrictBody<'a> {
    body: &'a serde_json::Map<String, Value>,
    invalid: &'static str,
}

impl<'a> StrictBody<'a> {
    pub fn new(body: &'a Value, invalid: &'static str, allowed: &[&str]) -> AppResult<Self> {
        let Some(body) = body.as_object() else {
            return Err(AppError::BadRequest(invalid.to_owned()));
        };
        if body.keys().any(|name| !allowed.contains(&name.as_str())) {
            return Err(AppError::BadRequest(invalid.to_owned()));
        }
        Ok(Self { body, invalid })
    }

    pub fn invalid(&self) -> AppError {
        AppError::BadRequest(self.invalid.to_owned())
    }

    pub fn get(&self, field: &str) -> Option<&'a Value> {
        self.body.get(field)
    }

    pub fn text(&self, field: &str) -> AppResult<&'a str> {
        self.get(field)
            .and_then(Value::as_str)
            .ok_or_else(|| self.invalid())
    }

    /// An optional boolean flag; present but not a boolean (null included) is a malformed body.
    pub fn flag(&self, field: &str) -> AppResult<bool> {
        match self.get(field) {
            None => Ok(false),
            Some(value) => value.as_bool().ok_or_else(|| self.invalid()),
        }
    }

    pub fn process_id(&self) -> AppResult<&'a str> {
        let process_id = self.text("processId")?;
        if java_is_blank(process_id) || process_id.encode_utf16().count() > PROCESS_ID_MAX {
            return Err(self.invalid());
        }
        Ok(process_id)
    }

    /// Exactly 6 positions, each an index on a ten-key pad, in the user's click order.
    pub fn positions(&self) -> AppResult<Vec<u8>> {
        let positions = self
            .get("positions")
            .and_then(Value::as_array)
            .filter(|p| p.len() == POSITIONS_COUNT)
            .ok_or_else(|| self.invalid())?;
        positions
            .iter()
            .map(|p| {
                p.as_i64()
                    .filter(|v| (0..PAD_SIZE).contains(v))
                    .and_then(|v| u8::try_from(v).ok())
                    .ok_or_else(|| self.invalid())
            })
            .collect()
    }
}

/// The bank's shuffled keypad as the sidecar sends it: ten digit labels in DOM order, laid out in
/// `columns` columns. The user clicks his own digits and only the positions come back.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct RawKeypad {
    pub keys: Option<Vec<Option<String>>>,
    pub columns: i32,
}

/// A keypad that can be shown as it is.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Keypad {
    pub keys: Vec<String>,
    pub columns: i32,
}

/// Ten distinct digit keys in five columns, or None: anything else means the page changed.
pub fn safe_keypad(keypad: &RawKeypad) -> Option<Keypad> {
    if keypad.columns != KEYPAD_COLUMNS {
        return None;
    }
    let keys = keypad.keys.as_ref()?;
    if keys.len() != KEYPAD_KEY_COUNT {
        return None;
    }
    let mut digits: Vec<String> = Vec::with_capacity(KEYPAD_KEY_COUNT);
    for key in keys {
        let key = key.as_ref()?;
        let is_digit = key.len() == 1 && key.as_bytes()[0].is_ascii_digit();
        if !is_digit || digits.contains(key) {
            return None;
        }
        digits.push(key.clone());
    }
    Some(Keypad {
        keys: digits,
        columns: keypad.columns,
    })
}

/// Takes one token of the client IP's budget; None when it had one, else the 429 to answer, with
/// a Retry-After in whole seconds.
pub fn spend_token(
    state: &AppState,
    policy: &Policy,
    ip: &ClientIp,
    detail: &str,
) -> Option<Response> {
    let probe = state.limits.probe(policy, &ip.as_java_string());
    if probe == Probe::Consumed {
        return None;
    }
    let mut response = AppError::status(StatusCode::TOO_MANY_REQUESTS, detail).into_response();
    response.headers_mut().insert(
        header::RETRY_AFTER,
        HeaderValue::from(probe.retry_after_seconds()),
    );
    Some(response)
}

/// A 200 that no cache may keep: the keypad and the login steps.
pub fn no_store<T: Serialize>(body: T) -> Response {
    let mut response = axum::Json(body).into_response();
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

/// The disconnect answer: only Picsou's stored copy is deleted, never the bank's session.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeleteResponse {
    pub removed: bool,
    pub bank_session_revoked: bool,
    pub message: &'static str,
}

impl DeleteResponse {
    pub fn new(removed: bool, message: &'static str) -> Self {
        Self {
            removed,
            bank_session_revoked: false,
            message,
        }
    }
}
