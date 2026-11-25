//! RevolutController (track T6): a manual sync is a background discovery the frontend polls for
//! live progress (phase, approval countdown, accounts found); the member then picks the accounts
//! to import and confirms. The scheduler's unattended resync imports everything at once.

mod adapter;
pub mod progress;
mod sync;

use axum::Json as AxumJson;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::Deserialize;

pub use progress::RevolutProgress;
pub use sync::resync_reporting;

use super::sidecar::jobs::Pool;
use super::sidecar::keypad::no_store;
use super::sidecar::web::consume;
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/revolut/sync", sync_now)
        .get("/api/revolut/sync/progress", sync_progress)
        .post("/api/revolut/sync/confirm", confirm_sync)
        .post("/api/revolut/webview/start", webview_start)
        .get("/api/revolut/webview/status/{processId}", webview_status)
        .post("/api/revolut/webview/cancel/{processId}", webview_cancel)
        .get("/api/revolut/status", status)
        .delete("/api/revolut/session", disconnect);
}

const INVALID_WEBVIEW_ID: &str = "The Revolut request is not valid";

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

const RATE_LIMITED: &str = "Too many sync attempts. Please wait before trying again.";
/// Each job may launch a heavy headful browser in the sidecar: never more than a few at once.
const POOL: Pool = Pool {
    workers: 2,
    queue: 20,
};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SyncRequest {
    phone_number: Option<String>,
    passcode: Option<String>,
}

impl Validate for SyncRequest {
    fn validate(&self, _: &mut Errors) {}
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ConfirmSyncRequest {
    selected_external_ids: Option<Vec<Option<String>>>,
    remember: Option<bool>,
    voluntary: Option<bool>,
}

impl Validate for ConfirmSyncRequest {
    fn validate(&self, _: &mut Errors) {}
}

/// Starts a discovery in the background (blank credentials fall back to the remembered ones) and
/// answers 202 with its progress; a discovery already running is only reported.
async fn sync_now(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<SyncRequest>,
) -> AppResult<Response> {
    consume(&state, &policies::REVOLUT_AUTH, &ip, RATE_LIMITED)?;
    let member_id = auth.member_id;
    let progress = &state.connectors.seams.revolut_progress;
    if progress.start_if_idle(member_id, state.clock.now()) {
        let task_state = state.clone();
        let submitted = state.connectors.jobs.submit("REVOLUT", POOL, async move {
            sync::discover(&task_state, member_id, req.phone_number, req.passcode).await;
        });
        if submitted.is_err() {
            tracing::warn!("Revolut sync executor refused a job (member={member_id})");
            progress.error(member_id, adapter::GENERIC_FAILURE, None);
            return Err(AppError::internal("the Revolut sync executor is full"));
        }
    }
    Ok((
        StatusCode::ACCEPTED,
        AxumJson(progress.status(member_id, state.clock.now())),
    )
        .into_response())
}

async fn sync_progress(
    State(state): State<AppState>,
    auth: Auth,
) -> AxumJson<progress::SyncProgress> {
    AxumJson(
        state
            .connectors
            .seams
            .revolut_progress
            .status(auth.member_id, state.clock.now()),
    )
}

/// Persists the selection of the last discovery; `remember` stores the credentials, `voluntary`
/// (an explicit Add-account) lifts the selected accounts' soft-delete tombstones.
async fn confirm_sync(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<ConfirmSyncRequest>,
) -> AppResult<StatusCode> {
    sync::confirm_sync(
        &state,
        auth.member_id,
        &req.selected_external_ids.unwrap_or_default(),
        req.remember.unwrap_or(false),
        req.voluntary.unwrap_or(false),
    )
    .await?;
    Ok(StatusCode::NO_CONTENT)
}

/// Opens an interactive OS WebView login on Picsou Desktop (never handles credentials).
async fn webview_start(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
) -> AppResult<Response> {
    consume(&state, &policies::REVOLUT_WEBVIEW, &ip, RATE_LIMITED)?;
    Ok(no_store(sync::start_webview(&state, auth.member_id).await?))
}

async fn webview_status(
    State(state): State<AppState>,
    auth: Auth,
    Path(process_id): Path<String>,
) -> AppResult<Response> {
    validate_process_id(&process_id)?;
    Ok(no_store(
        sync::poll_webview_status(&state, &process_id, auth.member_id, POOL).await?,
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

async fn status(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<AxumJson<sync::StatusResponse>> {
    Ok(AxumJson(sync::get_status(&state, auth.member_id).await?))
}

async fn disconnect(State(state): State<AppState>, auth: Auth) -> AppResult<StatusCode> {
    sync::disconnect(&state, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT)
}
