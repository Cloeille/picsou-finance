//! LbpController (track T6): La Banque Postale. Login is three calls (`/auth/initiate`,
//! `/auth/keypad`, `/auth/complete`) and no password ever reaches Picsou: initiate answers
//! with the bank's keypad, the user clicks his digits in his browser, then approves the sign-in
//! in the bank's mobile app. The sync is on demand only.

mod session;
mod sync;

use std::sync::LazyLock;

use axum::Json as AxumJson;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use regex::Regex;
use serde::Serialize;

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
        .post("/api/lbp/auth/initiate", initiate)
        .post("/api/lbp/auth/keypad", keypad)
        .post("/api/lbp/auth/complete", complete)
        .post("/api/lbp/sync", sync_now)
        .get("/api/lbp/status", status)
        .delete("/api/lbp/session", clear);
}

const DELETE_MESSAGE: &str = "The stored session was deleted from Picsou. The La Banque Postale session itself is not \
     revoked and may remain active until it expires.";
/// What a body these endpoints cannot accept answers, whatever was in it.
const INVALID_BODY: &str = "The La Banque Postale request is not valid";
/// 10 digits for a retail account; a small range keeps a typo from spending a sign-in attempt.
static CUSTOMER_ID: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[0-9]{6,12}$").expect("regex"));

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct KeypadResponse<'a> {
    process_id: &'a str,
    status: &'static str,
}

/// Throttled at 3 per 15 minutes per IP: a wrong pad costs a bank attempt. The body is validated
/// first, so a malformed request spends neither an attempt nor a sidecar slot.
async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(AnyBody(body)): Json<AnyBody>,
) -> AppResult<Response> {
    let body = StrictBody::new(&body, INVALID_BODY, &["customerId", "rememberIdentifier"])?;
    let customer_id = body.text("customerId")?;
    if !CUSTOMER_ID.is_match(customer_id) {
        return Err(body.invalid());
    }
    let remember_identifier = body.flag("rememberIdentifier")?;
    if let Some(refused) = spend_token(
        &state,
        &policies::LBP_AUTH,
        &ip,
        "Too many La Banque Postale sign-in attempts. Please wait before retrying.",
    ) {
        return Ok(refused);
    }
    let started =
        sync::initiate_auth(&state, customer_id, remember_identifier, auth.member_id).await?;
    Ok(no_store(started))
}

/// Its own bucket, so it never eats one of the 3 login attempts. The positions are never echoed.
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
        &policies::LBP_KEYPAD,
        &ip,
        "Too many La Banque Postale keypad requests. Please wait before retrying.",
    ) {
        return Ok(refused);
    }
    sync::submit_keypad(&state, process_id, &positions, auth.member_id).await?;
    Ok(no_store(KeypadResponse {
        process_id,
        status: "APPROVAL_PENDING",
    }))
}

/// Blocks while the customer approves in the bank's app. No rate limit: the pending login is taken
/// out before the sidecar is called, so a process id is single use and a replay just expires.
async fn complete(
    State(state): State<AppState>,
    auth: Auth,
    Json(AnyBody(body)): Json<AnyBody>,
) -> AppResult<Response> {
    let body = StrictBody::new(&body, INVALID_BODY, &["processId"])?;
    let process_id = body.process_id()?;
    Ok(no_store(
        sync::complete_auth(&state, process_id, auth.member_id).await?,
    ))
}

async fn sync_now(State(state): State<AppState>, auth: Auth, ip: ClientIp) -> AppResult<Response> {
    consume(
        &state,
        &policies::SYNC,
        &ip,
        "Too many La Banque Postale synchronization requests. Please wait before retrying.",
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
