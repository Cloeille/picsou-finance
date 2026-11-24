//! BnpController (track T6): BNP Paribas and Hello bank!. Login is three calls
//! (`/auth/initiate`, `/auth/keypad`, `/auth/complete`); no password ever reaches Picsou: the
//! bank's keypad is clicked in the member's browser and only the tile positions come back, then
//! the member confirms the strong authentication (the bank's app, or the code it sent by SMS).
//! The sync is on demand and daily.

mod sync;

use std::sync::LazyLock;

use axum::Json as AxumJson;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use regex::Regex;
use serde_json::Value;

pub use sync::{
    SessionStatusResponse, evict_expired_pending_logins, recover_interrupted_syncs,
    resync_reporting,
};

use super::sidecar::keypad::{AnyBody, DeleteResponse, StrictBody, no_store, spend_token};
use super::sidecar::web::consume;
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::client_ip::ClientIp;
use crate::http::json::Json;
use crate::http::rate_limit::policies;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/bnp/auth/initiate", initiate)
        .post("/api/bnp/auth/keypad", keypad)
        .post("/api/bnp/auth/complete", complete)
        .post("/api/bnp/webview/start", webview_start)
        .get("/api/bnp/webview/status/{processId}", webview_status)
        .post("/api/bnp/webview/cancel/{processId}", webview_cancel)
        .post("/api/bnp/sync", sync_now)
        .get("/api/bnp/status", status)
        .delete("/api/bnp/session", clear);
}

const DELETE_MESSAGE: &str = "The stored session was deleted from Picsou. The BNP Paribas session itself is not \
     revoked and may remain active until it expires.";
/// What a body these endpoints cannot accept answers, whatever was in it.
const INVALID_BODY: &str = "The BNP Paribas request is not valid";
/// The login page takes 7 to 10 digits; refusing anything else keeps a typo from spending an attempt.
static CUSTOMER_ID: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[0-9]{7,10}$").expect("regex"));
static CODE: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[0-9]{4,10}$").expect("regex"));
const BRANDS: &[&str] = &["bnp", "hellobank"];

/// Throttled at 3 per 15 minutes per IP: a wrong keypad costs a bank attempt. The body is
/// validated first, so a malformed request spends neither an attempt nor a sidecar slot.
async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(AnyBody(body)): Json<AnyBody>,
) -> AppResult<Response> {
    let body = StrictBody::new(&body, INVALID_BODY, &["customerId", "brand"])?;
    let customer_id = body.text("customerId")?;
    if !CUSTOMER_ID.is_match(customer_id) {
        return Err(body.invalid());
    }
    let brand = match body.get("brand") {
        Some(_) => body.text("brand")?,
        None => "bnp",
    };
    if !BRANDS.contains(&brand) {
        return Err(body.invalid());
    }
    if let Some(refused) = spend_token(
        &state,
        &policies::BNP_AUTH,
        &ip,
        "Too many BNP Paribas sign-in attempts. Please wait before retrying.",
    ) {
        return Ok(refused);
    }
    Ok(no_store(
        sync::initiate_auth(&state, customer_id, brand, auth.member_id).await?,
    ))
}

/// Its own bucket, so it never eats one of the 3 login attempts or a code attempt. The positions
/// are never echoed back, here or in an error.
async fn keypad(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(AnyBody(body)): Json<AnyBody>,
) -> AppResult<Response> {
    let body = StrictBody::new(&body, INVALID_BODY, &["processId", "positions"])?;
    let process_id = body.process_id()?;
    let positions = body.positions()?;
    if let Some(refused) = spend_token(
        &state,
        &policies::BNP_KEYPAD,
        &ip,
        "Too many BNP Paribas keypad requests. Please wait before retrying.",
    ) {
        return Ok(refused);
    }
    Ok(no_store(
        sync::submit_keypad(&state, process_id, &positions, auth.member_id).await?,
    ))
}

/// Without a `code` it waits for the approval in the bank's app; with one it submits the SMS
/// code, from a bucket of its own so a login whose keypad budget is spent can still finish.
async fn complete(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(AnyBody(body)): Json<AnyBody>,
) -> AppResult<Response> {
    let body = StrictBody::new(&body, INVALID_BODY, &["processId", "code"])?;
    let process_id = body.process_id()?;
    let code = match body.get("code") {
        None | Some(Value::Null) => None,
        Some(_) => {
            let code = body.text("code")?;
            if !CODE.is_match(code) {
                return Err(body.invalid());
            }
            if let Some(refused) = spend_token(
                &state,
                &policies::BNP_COMPLETE,
                &ip,
                "Too many BNP Paribas code attempts. Please wait before retrying.",
            ) {
                return Ok(refused);
            }
            Some(code)
        }
    };
    Ok(no_store(
        sync::complete_auth(&state, process_id, code, auth.member_id).await?,
    ))
}

/// Opens an interactive OS WebView login on Picsou Desktop (never handles credentials).
async fn webview_start(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    body: axum::body::Bytes,
) -> AppResult<Response> {
    let brand = if body.iter().all(u8::is_ascii_whitespace) {
        "bnp".to_owned()
    } else {
        let raw: Value = serde_json::from_slice(&body)
            .map_err(|_| crate::error::AppError::BadRequest(INVALID_BODY.to_owned()))?;
        let strict = StrictBody::new(&raw, INVALID_BODY, &["brand"])?;
        let chosen = match strict.get("brand") {
            Some(_) => strict.text("brand")?,
            None => "bnp",
        };
        if !BRANDS.contains(&chosen) {
            return Err(strict.invalid());
        }
        chosen.to_owned()
    };
    if let Some(refused) = spend_token(
        &state,
        &policies::BNP_WEBVIEW,
        &ip,
        "Too many BNP Paribas sign-in attempts. Please wait before retrying.",
    ) {
        return Ok(refused);
    }
    Ok(no_store(
        sync::start_webview(&state, &brand, auth.member_id).await?,
    ))
}

async fn webview_status(
    State(state): State<AppState>,
    auth: Auth,
    Path(process_id): Path<String>,
) -> AppResult<Response> {
    if process_id.is_empty()
        || process_id.len() > 128
        || !process_id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        return Err(crate::error::AppError::BadRequest(INVALID_BODY.to_owned()));
    }
    Ok(no_store(
        sync::poll_webview_status(&state, &process_id, auth.member_id).await?,
    ))
}

async fn webview_cancel(
    State(state): State<AppState>,
    auth: Auth,
    Path(process_id): Path<String>,
) -> AppResult<Response> {
    if process_id.is_empty()
        || process_id.len() > 128
        || !process_id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        return Err(crate::error::AppError::BadRequest(INVALID_BODY.to_owned()));
    }
    Ok(no_store(
        sync::cancel_webview(&state, &process_id, auth.member_id).await?,
    ))
}

async fn sync_now(State(state): State<AppState>, auth: Auth, ip: ClientIp) -> AppResult<Response> {
    consume(
        &state,
        &policies::SYNC,
        &ip,
        "Too many BNP Paribas synchronization requests. Please wait before retrying.",
    )?;
    let status = sync::queue_sync(&state, auth.member_id).await?;
    Ok((StatusCode::ACCEPTED, AxumJson(status)).into_response())
}

async fn status(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<AxumJson<SessionStatusResponse>> {
    Ok(AxumJson(sync::get_status(&state, auth.member_id).await?))
}

/// Forgets the stored session in Picsou only; the answer never claims the bank session ended.
async fn clear(State(state): State<AppState>, auth: Auth) -> AppResult<AxumJson<DeleteResponse>> {
    let removed = sync::clear_session(&state, auth.member_id).await?;
    Ok(AxumJson(DeleteResponse::new(removed, DELETE_MESSAGE)))
}
