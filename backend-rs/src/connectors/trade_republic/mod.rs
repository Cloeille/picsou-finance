//! TradeRepublicController (track T6): phone and PIN login with an in-app confirmation or a code,
//! a PIN-only reconnect, a queued import of the portfolio over TR's WebSocket and of TR's activity
//! export, and the CSV fallbacks.

pub mod adapter;
mod csv;
mod multipart;
mod sync;
pub mod ws;

use std::collections::HashMap;
use std::sync::{LazyLock, Mutex, RwLock};

use axum::Json as AxumJson;
use axum::extract::{Request, State};
use axum::http::StatusCode;
use regex::Regex;
use serde::Deserialize;

pub use csv::ImportResult;
pub use sync::{
    AuthInitResponse, SessionStatusResponse, mask_phone, recover_interrupted_syncs,
    resync_reporting,
};

use super::sidecar::web::consume;
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Path, Validate};
use crate::http::rate_limit::policies;
use crate::ledger::sidecar::SidecarErrorCode;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/tr/auth/initiate", initiate)
        .post("/api/tr/auth/reconnect", reconnect)
        .post("/api/tr/auth/complete", complete)
        .post("/api/tr/sync", sync_now)
        .get("/api/tr/status", status)
        .post("/api/tr/import", import_accounts)
        .post(
            "/api/tr/accounts/{id}/transactions/import-csv",
            import_transactions,
        )
        .delete("/api/tr/session", clear);
}

/// Trade Republic's process-local state: where the portfolio WebSocket lives, and the login
/// generation every disconnect bumps so a login that was waiting meanwhile stores nothing.
pub struct Runtime {
    ws_url: RwLock<String>,
    auth_generations: Mutex<HashMap<i64, u64>>,
    pub(crate) session_write: tokio::sync::Mutex<()>,
}

impl Default for Runtime {
    fn default() -> Self {
        Self {
            ws_url: RwLock::new(adapter::DEFAULT_WS_URL.to_owned()),
            auth_generations: Mutex::new(HashMap::new()),
            session_write: tokio::sync::Mutex::new(()),
        }
    }
}

impl Runtime {
    pub fn ws_url(&self) -> String {
        self.ws_url.read().expect("ws url lock").clone()
    }

    /// Points the portfolio read at another WebSocket server (a local stub in tests).
    pub fn set_ws_url(&self, url: impl Into<String>) {
        *self.ws_url.write().expect("ws url lock") = url.into();
    }

    fn generation(&self, member_id: i64) -> u64 {
        self.auth_generations
            .lock()
            .expect("generation lock")
            .get(&member_id)
            .copied()
            .unwrap_or(0)
    }

    pub(crate) fn bump_generation(&self, member_id: i64) {
        *self
            .auth_generations
            .lock()
            .expect("generation lock")
            .entry(member_id)
            .or_insert(0) += 1;
    }
}

const AUTH_RATE_LIMITED: &str =
    "Too many authentication attempts. Please wait before trying again.";
const SYNC_RATE_LIMITED: &str =
    "Too many Trade Republic synchronization requests. Please wait before retrying.";
static TAN: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^(?:\d{4}|\d{6})$").expect("regex"));

/// The PIN is a 4-digit code or a password: any non-empty string up to 128 characters.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct InitiateRequest {
    phone_number: Option<String>,
    pin: Option<String>,
}

impl Validate for InitiateRequest {
    fn validate(&self, e: &mut Errors) {
        let phone = self.phone_number.as_deref();
        e.not_blank("phoneNumber", phone)
            .size("phoneNumber", phone, 0, 32);
        e.not_null("pin", self.pin.as_ref())
            .size("pin", self.pin.as_deref(), 1, 128);
    }
}

#[derive(Deserialize)]
struct ReconnectRequest {
    pin: Option<String>,
}

impl Validate for ReconnectRequest {
    fn validate(&self, e: &mut Errors) {
        e.not_null("pin", self.pin.as_ref())
            .size("pin", self.pin.as_deref(), 1, 128);
    }
}

/// `tan`: a 4-digit code, a 6-digit authenticator code, or absent for the in-app confirmation.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CompleteRequest {
    process_id: Option<String>,
    tan: Option<String>,
}

impl Validate for CompleteRequest {
    fn validate(&self, e: &mut Errors) {
        let process_id = self.process_id.as_deref();
        e.not_blank("processId", process_id)
            .size("processId", process_id, 0, 100);
        e.pattern_default("tan", self.tan.as_deref(), &TAN, r"\d{4}|\d{6}");
    }
}

async fn initiate(
    State(state): State<AppState>,
    ip: ClientIp,
    Json(req): Json<InitiateRequest>,
) -> AppResult<AxumJson<AuthInitResponse>> {
    consume(&state, &policies::TR_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let phone = req.phone_number.unwrap_or_default();
    let pin = req.pin.unwrap_or_default();
    Ok(AxumJson(sync::initiate_auth(&state, &phone, &pin).await?))
}

async fn reconnect(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<ReconnectRequest>,
) -> AppResult<AxumJson<AuthInitResponse>> {
    consume(&state, &policies::TR_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let Some(phone) = sync::stored_phone(&state, auth.member_id).await? else {
        return Err(AppError::coded(
            StatusCode::CONFLICT,
            SidecarErrorCode::InvalidSessionState.as_str(),
            "No phone number is stored for this Trade Republic session. Please sign in with your phone number.",
        ));
    };
    let pin = req.pin.unwrap_or_default();
    Ok(AxumJson(sync::initiate_auth(&state, &phone, &pin).await?))
}

async fn complete(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<CompleteRequest>,
) -> AppResult<AxumJson<SessionStatusResponse>> {
    let process_id = req.process_id.unwrap_or_default();
    Ok(AxumJson(
        sync::complete_auth(&state, &process_id, req.tan.as_deref(), auth.member_id).await?,
    ))
}

async fn sync_now(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
) -> AppResult<AxumJson<SessionStatusResponse>> {
    consume(&state, &policies::SYNC, &ip, SYNC_RATE_LIMITED)?;
    Ok(AxumJson(sync::queue_sync(&state, auth.member_id).await?))
}

async fn status(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<AxumJson<SessionStatusResponse>> {
    Ok(AxumJson(sync::get_status(&state, auth.member_id).await?))
}

async fn import_accounts(
    State(state): State<AppState>,
    auth: Auth,
    request: Request,
) -> AppResult<AxumJson<Vec<serde_json::Value>>> {
    let csv = multipart::file_part(request, "file").await?;
    let saved = csv::import_accounts(&state, &csv, auth.member_id).await?;
    let mut responses = Vec::with_capacity(saved.len());
    for account in &saved {
        responses.push(
            state
                .connectors
                .seams
                .accounts
                .to_response(&state, account)
                .await?,
        );
    }
    Ok(AxumJson(responses))
}

async fn import_transactions(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    request: Request,
) -> AppResult<AxumJson<ImportResult>> {
    let csv = multipart::file_part(request, "file").await?;
    Ok(AxumJson(
        csv::import_transactions_csv(&state, id, &csv, auth.member_id).await?,
    ))
}

async fn clear(State(state): State<AppState>, auth: Auth) -> AppResult<StatusCode> {
    sync::clear_session(&state, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT)
}
