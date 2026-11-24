//! BourseDirectController (track T6): login with an e-mailed code, then a queued import of the
//! PEA and securities accounts with their positions.

mod sync;

use axum::Json as AxumJson;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use regex::Regex;
use serde::Deserialize;
use std::sync::LazyLock;

pub use sync::{recover_interrupted_syncs, resync_reporting};

use super::sidecar::web::{AuthInitResponse, SessionStatusResponse, consume};
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/bourse-direct/auth/initiate", initiate)
        .post("/api/bourse-direct/auth/complete", complete)
        .post("/api/bourse-direct/sync", sync_now)
        .get("/api/bourse-direct/status", status)
        .delete("/api/bourse-direct/session", clear);
}

const RATE_LIMITED: &str =
    "Too many Bourse Direct authentication attempts. Please wait before retrying.";
static SIX_DIGITS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\d{6}$").expect("regex"));

#[derive(Debug, Deserialize)]
struct InitiateRequest {
    login: Option<String>,
    password: Option<String>,
}

impl Validate for InitiateRequest {
    fn validate(&self, e: &mut Errors) {
        let (login, password) = (self.login.as_deref(), self.password.as_deref());
        e.not_blank("login", login).size("login", login, 0, 100);
        e.not_blank("password", password)
            .size("password", password, 0, 100);
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CompleteRequest {
    process_id: Option<String>,
    code: Option<String>,
}

impl Validate for CompleteRequest {
    fn validate(&self, e: &mut Errors) {
        let (process_id, code) = (self.process_id.as_deref(), self.code.as_deref());
        e.not_blank("processId", process_id)
            .size("processId", process_id, 0, 100);
        e.not_blank("code", code)
            .pattern_default("code", code, &SIX_DIGITS, r"\d{6}");
    }
}

async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<InitiateRequest>,
) -> AppResult<AxumJson<AuthInitResponse>> {
    consume(&state, &policies::BOURSE_DIRECT_AUTH, &ip, RATE_LIMITED)?;
    let login = req.login.unwrap_or_default();
    let password = req.password.unwrap_or_default();
    Ok(AxumJson(
        sync::initiate_auth(&state, &login, &password, auth.member_id).await?,
    ))
}

async fn complete(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<CompleteRequest>,
) -> AppResult<AxumJson<SessionStatusResponse>> {
    consume(&state, &policies::BOURSE_DIRECT_AUTH, &ip, RATE_LIMITED)?;
    let process_id = req.process_id.unwrap_or_default();
    let code = req.code.unwrap_or_default();
    Ok(AxumJson(
        sync::complete_auth(&state, &process_id, &code, auth.member_id).await?,
    ))
}

async fn sync_now(State(state): State<AppState>, auth: Auth) -> AppResult<Response> {
    let status = sync::queue_sync(&state, auth.member_id).await?;
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
