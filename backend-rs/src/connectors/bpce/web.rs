//! The controller half the BPCE banks share: strict body reading (an unexpected field, a
//! password typically, is refused, never ignored), the per-IP budgets with their Retry-After, and
//! the keypad, complete, sync, status and session endpoints.

use axum::Json as AxumJson;
use axum::extract::Request;
use axum::http::{HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::Serialize;
use serde_json::{Map, Value};

use super::Bank;
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::read_json;
use crate::http::rate_limit::{Policy, Probe, policies};

const POSITIONS_MIN: usize = 6;
const POSITIONS_MAX: usize = 12;
const PAD_SIZE: i64 = 10;
const PROCESS_ID_MAX: usize = 100;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct KeypadResponse {
    process_id: String,
    status: &'static str,
}

#[derive(Serialize)]
struct CompleteResponse {
    connected: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DeleteResponse {
    removed: bool,
    bank_session_revoked: bool,
    message: String,
}

/// A body-level refusal. The message is fixed: nothing of the input is ever echoed.
pub fn invalid_body(bank: &Bank) -> AppError {
    AppError::BadRequest(format!("The {} request is not valid", bank.provider()))
}

/// The JSON body, which must be an object carrying exactly these fields.
pub async fn read_only_fields(
    bank: &Bank,
    request: Request,
    allowed: &[&str],
) -> AppResult<Map<String, Value>> {
    let body: Value = read_json(request, &()).await?;
    match body {
        Value::Object(fields)
            if fields.len() == allowed.len() && allowed.iter().all(|a| fields.contains_key(*a)) =>
        {
            Ok(fields)
        }
        _ => Err(invalid_body(bank)),
    }
}

pub fn text<'a>(bank: &Bank, fields: &'a Map<String, Value>, field: &str) -> AppResult<&'a str> {
    fields
        .get(field)
        .and_then(Value::as_str)
        .ok_or_else(|| invalid_body(bank))
}

fn process_id(bank: &Bank, fields: &Map<String, Value>) -> AppResult<String> {
    let process_id = text(bank, fields, "processId")?;
    if process_id.trim().is_empty() || process_id.encode_utf16().count() > PROCESS_ID_MAX {
        return Err(invalid_body(bank));
    }
    Ok(process_id.to_owned())
}

/// 6 to 12 positions, each an index on the ten-key pad, in the user's click order.
fn positions(bank: &Bank, value: Option<&Value>) -> AppResult<Vec<i32>> {
    let Some(Value::Array(items)) = value else {
        return Err(invalid_body(bank));
    };
    if !(POSITIONS_MIN..=POSITIONS_MAX).contains(&items.len()) {
        return Err(invalid_body(bank));
    }
    items
        .iter()
        .map(|item| {
            item.as_i64()
                .filter(|p| (0..PAD_SIZE).contains(p))
                .and_then(|p| i32::try_from(p).ok())
                .ok_or_else(|| invalid_body(bank))
        })
        .collect()
}

/// Takes one token of the IP's budget; when there is none, the 429 to answer, with its Retry-After.
pub fn limited(
    state: &AppState,
    policy: &Policy,
    ip: &ClientIp,
    detail: String,
) -> Option<Response> {
    let refused = state.limits.probe(policy, &ip.as_java_string());
    if refused == Probe::Consumed {
        return None;
    }
    let mut response = AppError::status(StatusCode::TOO_MANY_REQUESTS, detail).into_response();
    response.headers_mut().insert(
        header::RETRY_AFTER,
        HeaderValue::from(refused.retry_after_seconds()),
    );
    Some(response)
}

pub fn sign_in_limited(bank: &Bank) -> String {
    format!(
        "Too many {} sign-in attempts. Please wait before retrying.",
        bank.provider()
    )
}

/// 200 with Cache-Control: no-store.
pub fn no_store(body: impl Serialize) -> Response {
    let mut response = AxumJson(body).into_response();
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

pub async fn keypad(
    state: &AppState,
    bank: &Bank,
    member_id: i64,
    ip: &ClientIp,
    request: Request,
) -> AppResult<Response> {
    let fields = read_only_fields(bank, request, &["processId", "positions"]).await?;
    let process_id = process_id(bank, &fields)?;
    let positions = positions(bank, fields.get("positions"))?;
    let detail = format!(
        "Too many {} keypad requests. Please wait before retrying.",
        bank.provider()
    );
    if let Some(response) = limited(state, bank.keypad_policy, ip, detail) {
        return Ok(response);
    }
    super::submit_keypad(state, bank, &process_id, &positions, member_id).await?;
    // The positions are never echoed back, here or in an error.
    Ok(no_store(KeypadResponse {
        process_id,
        status: "SECURPASS_PENDING",
    }))
}

/// Takes no password and draws from no budget: it cannot spend a bank attempt.
pub async fn complete(
    state: &AppState,
    bank: &Bank,
    member_id: i64,
    request: Request,
) -> AppResult<Response> {
    let fields = read_only_fields(bank, request, &["processId"]).await?;
    let process_id = process_id(bank, &fields)?;
    super::complete(state, bank, &process_id, member_id).await?;
    Ok(AxumJson(CompleteResponse { connected: true }).into_response())
}

pub async fn sync(
    state: &AppState,
    bank: &'static Bank,
    member_id: i64,
    ip: &ClientIp,
) -> AppResult<Response> {
    if !state
        .limits
        .try_consume(&policies::SYNC, &ip.as_java_string())
    {
        return Err(AppError::status(
            StatusCode::TOO_MANY_REQUESTS,
            format!(
                "Too many {} synchronization requests. Please wait before retrying.",
                bank.provider()
            ),
        ));
    }
    let status = super::queue_sync(state, bank, member_id).await?;
    Ok((StatusCode::ACCEPTED, AxumJson(status)).into_response())
}

pub async fn status(state: &AppState, bank: &Bank, member_id: i64) -> AppResult<Response> {
    Ok(AxumJson(super::get_status(state, bank, member_id).await?).into_response())
}

/// Forgets the stored session in Picsou only; the answer never claims the bank session ended.
pub async fn clear(state: &AppState, bank: &'static Bank, member_id: i64) -> AppResult<Response> {
    let removed = super::clear_session(state, bank, member_id).await?;
    Ok(AxumJson(DeleteResponse {
        removed,
        bank_session_revoked: false,
        message: format!(
            "The stored session was deleted from Picsou. The {} session itself is not revoked and may \
             remain active until it expires.",
            bank.provider()
        ),
    })
    .into_response())
}
