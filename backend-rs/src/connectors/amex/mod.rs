//! AmexController (track T6): sign-in with a one-time code sent by SMS or e-mail, then a queued
//! import of the card balance, statement figures and transactions; an explicit history recovery
//! re-imports the provider's full transaction history.

mod history;
mod sync;

use std::sync::LazyLock;

use axum::Json as AxumJson;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use regex::Regex;
use serde::Deserialize;

pub use sync::{recover_interrupted_syncs, resync_reporting};

use super::sidecar::keypad::no_store;
use super::sidecar::web::{AuthInitResponse, SessionStatusResponse, consume};
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/amex/auth/initiate", initiate)
        .post("/api/amex/auth/complete", complete)
        .post("/api/amex/webview/start", webview_start)
        .get("/api/amex/webview/status/{processId}", webview_status)
        .post("/api/amex/webview/cancel/{processId}", webview_cancel)
        .post("/api/amex/sync", sync_now)
        .post("/api/amex/history-recovery", recover_history)
        .get("/api/amex/status", status)
        .delete("/api/amex/session", clear);
}

const AUTH_RATE_LIMITED: &str =
    "Too many American Express authentication attempts. Please wait before retrying.";
const SYNC_RATE_LIMITED: &str =
    "Too many American Express synchronization requests. Please wait before retrying.";
const HISTORY_RATE_LIMITED: &str =
    "Too many American Express history recovery requests. Please wait before retrying.";

static METHOD: LazyLock<Regex> = LazyLock::new(|| Regex::new("^(?:sms|email)$").expect("regex"));
static OTP: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\d{4,10}$").expect("regex"));

#[derive(Debug, Deserialize)]
struct InitiateRequest {
    login: Option<String>,
    password: Option<String>,
    method: Option<String>,
}

impl Validate for InitiateRequest {
    fn validate(&self, e: &mut Errors) {
        let (login, password, method) = (
            self.login.as_deref(),
            self.password.as_deref(),
            self.method.as_deref(),
        );
        e.not_blank("login", login).size("login", login, 0, 100);
        e.not_blank("password", password)
            .size("password", password, 0, 100);
        e.not_blank("method", method)
            .pattern_default("method", method, &METHOD, "sms|email");
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CompleteRequest {
    process_id: Option<String>,
    otp: Option<String>,
}

impl Validate for CompleteRequest {
    fn validate(&self, e: &mut Errors) {
        let (process_id, otp) = (self.process_id.as_deref(), self.otp.as_deref());
        e.not_blank("processId", process_id)
            .size("processId", process_id, 0, 100);
        e.not_blank("otp", otp)
            .pattern_default("otp", otp, &OTP, r"\d{4,10}");
    }
}

async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<InitiateRequest>,
) -> AppResult<AxumJson<AuthInitResponse>> {
    consume(&state, &policies::AMEX_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let login = req.login.unwrap_or_default();
    let password = req.password.unwrap_or_default();
    let method = req.method.unwrap_or_default();
    Ok(AxumJson(
        sync::initiate_auth(&state, &login, &password, &method, auth.member_id).await?,
    ))
}

async fn complete(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<CompleteRequest>,
) -> AppResult<AxumJson<SessionStatusResponse>> {
    consume(&state, &policies::AMEX_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let process_id = req.process_id.unwrap_or_default();
    let otp = req.otp.unwrap_or_default();
    Ok(AxumJson(
        sync::complete_auth(&state, &process_id, &otp, auth.member_id).await?,
    ))
}

const INVALID_WEBVIEW_ID: &str = "The American Express request is not valid";

fn validate_process_id(process_id: &str) -> AppResult<()> {
    if process_id.is_empty()
        || process_id.len() > 128
        || !process_id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        return Err(AppError::BadRequest(INVALID_WEBVIEW_ID.to_owned()));
    }
    Ok(())
}

/// Opens an interactive OS WebView login on Picsou Desktop (never handles credentials).
async fn webview_start(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
) -> AppResult<Response> {
    consume(&state, &policies::AMEX_WEBVIEW, &ip, AUTH_RATE_LIMITED)?;
    Ok(no_store(sync::start_webview(&state, auth.member_id).await?))
}

async fn webview_status(
    State(state): State<AppState>,
    auth: Auth,
    Path(process_id): Path<String>,
) -> AppResult<Response> {
    validate_process_id(&process_id)?;
    Ok(no_store(
        sync::poll_webview_status(&state, &process_id, auth.member_id).await?,
    ))
}

async fn webview_cancel(
    State(state): State<AppState>,
    auth: Auth,
    Path(process_id): Path<String>,
) -> AppResult<Response> {
    validate_process_id(&process_id)?;
    Ok(no_store(
        sync::cancel_webview(&state, &process_id, auth.member_id).await?,
    ))
}

async fn sync_now(State(state): State<AppState>, auth: Auth, ip: ClientIp) -> AppResult<Response> {
    consume(&state, &policies::SYNC, &ip, SYNC_RATE_LIMITED)?;
    let status = sync::queue_sync(&state, auth.member_id).await?;
    Ok((StatusCode::ACCEPTED, AxumJson(status)).into_response())
}

async fn recover_history(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
) -> AppResult<Response> {
    consume(&state, &policies::SYNC, &ip, HISTORY_RATE_LIMITED)?;
    let status = sync::queue_history_recovery(&state, auth.member_id).await?;
    Ok((StatusCode::ACCEPTED, AxumJson(status)).into_response())
}

async fn status(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<AxumJson<SessionStatusResponse>> {
    Ok(AxumJson(sync::get_status(&state, auth.member_id).await?))
}

async fn clear(State(state): State<AppState>, auth: Auth) -> AppResult<StatusCode> {
    sync::clear_session(&state, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT)
}
