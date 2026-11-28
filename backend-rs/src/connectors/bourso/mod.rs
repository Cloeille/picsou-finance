//! BoursoController (track T6): login with the customer number and an app-push validation, then a
//! queued import of the current accounts, livrets, securities accounts and life contracts.

mod sync;

use axum::Json as AxumJson;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::Deserialize;

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
        .post("/api/bourso/auth/initiate", initiate)
        .post("/api/bourso/auth/complete", complete)
        .post("/api/bourso/sync", sync_now)
        .get("/api/bourso/status", status)
        .delete("/api/bourso/session", clear);
}

const AUTH_RATE_LIMITED: &str =
    "Too many BoursoBank authentication attempts. Please wait before retrying.";
const SYNC_RATE_LIMITED: &str =
    "Too many BoursoBank synchronization requests. Please wait before retrying.";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InitiateRequest {
    customer_id: Option<String>,
    password: Option<String>,
}

impl Validate for InitiateRequest {
    fn validate(&self, e: &mut Errors) {
        let (customer_id, password) = (self.customer_id.as_deref(), self.password.as_deref());
        e.not_blank("customerId", customer_id)
            .size("customerId", customer_id, 0, 100);
        e.not_blank("password", password)
            .size("password", password, 0, 100);
    }
}

/// No code: the app push is the only second factor this connector drives.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CompleteRequest {
    process_id: Option<String>,
}

impl Validate for CompleteRequest {
    fn validate(&self, e: &mut Errors) {
        let process_id = self.process_id.as_deref();
        e.not_blank("processId", process_id)
            .size("processId", process_id, 0, 100);
    }
}

async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<InitiateRequest>,
) -> AppResult<AxumJson<AuthInitResponse>> {
    consume(&state, &policies::BOURSO_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let customer_id = req.customer_id.unwrap_or_default();
    let password = req.password.unwrap_or_default();
    Ok(AxumJson(
        sync::initiate_auth(&state, &customer_id, &password, auth.member_id).await?,
    ))
}

/// Blocks while the customer approves the push on their phone, up to two minutes.
async fn complete(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<CompleteRequest>,
) -> AppResult<AxumJson<SessionStatusResponse>> {
    consume(&state, &policies::BOURSO_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let process_id = req.process_id.unwrap_or_default();
    Ok(AxumJson(
        sync::complete_auth(&state, &process_id, auth.member_id).await?,
    ))
}

/// Throttled like every other sync entry point: queueing takes a row lock and decrypts the session.
async fn sync_now(State(state): State<AppState>, auth: Auth, ip: ClientIp) -> AppResult<Response> {
    consume(&state, &policies::SYNC, &ip, SYNC_RATE_LIMITED)?;
    let status = sync::queue_sync(&state, auth.member_id).await?;
    Ok((StatusCode::ACCEPTED, AxumJson(status)).into_response())
}

async fn status(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<AxumJson<sync::StatusResponse>> {
    Ok(AxumJson(
        sync::get_status_with_skipped(&state, auth.member_id).await?,
    ))
}

async fn clear(State(state): State<AppState>, auth: Auth) -> AppResult<StatusCode> {
    sync::clear_session(&state, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT)
}
