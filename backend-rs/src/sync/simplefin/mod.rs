//! SimpleFIN Bridge sync: SimplefinController, SimplefinSyncService and the SimpleFIN client.
//!
//! The member pastes a one-time setup token; Picsou claims it for an access URL (stored
//! encrypted) and later reads balances, positions and posted transactions with it. Only
//! https://beta-bridge.simplefin.org is ever called.

mod client;
mod connection;
mod currency;
mod holdings;
mod java_uri;
mod json;
pub mod response;
mod service;
mod urls;

use std::sync::Arc;
use std::time::Duration;

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::Deserialize;
use url::Url;

use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;
use crate::ledger::sidecar::SourceSyncResult;
use crate::routes::Routes;

pub use response::{AccountResponses, LedgerAccountResponses};
pub use service::{ConnectionStatus, PROVIDER, SOURCE};

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/simplefin/connect", connect)
        .get("/api/simplefin/status", status)
        .post("/api/simplefin/sync", sync)
        .delete("/api/simplefin/connection", clear_connection);
}

/// SyncException: a 422 whose message is meant for the member, with an optional stable code.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncError {
    pub message: String,
    pub code: Option<&'static str>,
}

impl SyncError {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            code: None,
        }
    }

    fn coded(message: impl Into<String>, code: &'static str) -> Self {
        Self {
            message: message.into(),
            code: Some(code),
        }
    }
}

impl From<SyncError> for AppError {
    fn from(error: SyncError) -> Self {
        tracing::warn!("Sync error: {}", error.message);
        match error.code {
            Some(code) => AppError::coded(StatusCode::UNPROCESSABLE_ENTITY, code, error.message),
            None => AppError::status(StatusCode::UNPROCESSABLE_ENTITY, error.message),
        }
    }
}

/// The SimpleFIN plug-in state, held as state.sync.simplefin.
pub struct Hooks {
    /// AccountService.toResponse, owned by the accounts track.
    pub accounts: Arc<dyn AccountResponses>,
    /// Redirects refused: the next URL would be the remote server's choice.
    http: reqwest::Client,
    /// Tests only: where requests for the validated bridge URL are delivered.
    #[cfg(feature = "testing")]
    bridge_origin: std::sync::OnceLock<Url>,
}

impl Default for Hooks {
    fn default() -> Self {
        Self::new()
    }
}

impl Hooks {
    pub fn new() -> Self {
        let http = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(30))
            .build()
            .expect("the HTTP client's TLS backend initializes");
        Self {
            accounts: Arc::new(LedgerAccountResponses),
            http,
            #[cfg(feature = "testing")]
            bridge_origin: std::sync::OnceLock::new(),
        }
    }

    /// Delivers every bridge request to origin (a wiremock server) once the URL has passed the
    /// production host checks. The seam Java's tests get from a fake transport.
    #[cfg(feature = "testing")]
    pub fn route_bridge_to(&self, origin: &str) {
        let origin = Url::parse(origin).expect("an origin URL");
        assert!(
            self.bridge_origin.set(origin).is_ok(),
            "the bridge is routed once per app"
        );
    }

    #[cfg(feature = "testing")]
    fn dispatch(&self, url: Url) -> Url {
        let Some(origin) = self.bridge_origin.get() else {
            return url;
        };
        let mut routed = origin.clone();
        routed.set_path(url.path());
        routed.set_query(url.query());
        routed
    }

    #[cfg(not(feature = "testing"))]
    fn dispatch(&self, url: Url) -> Url {
        url
    }
}

/// SimplefinSyncService.resyncReporting: the scheduler, Sync All and MCP entry point.
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    service::resync_reporting(state, member_id).await
}

/// SimplefinSyncService.deleteConnection, for account deletion (AccountConnectionService):
/// true when a connection was removed.
pub async fn delete_connection(state: &AppState, member_id: i64) -> AppResult<bool> {
    service::delete_connection(state, member_id).await
}

/// SimplefinSyncService.getConnectionStatus, also read by the MCP sync status.
pub async fn connection_status(state: &AppState, member_id: i64) -> AppResult<ConnectionStatus> {
    service::connection_status(state, member_id).await
}

#[derive(Debug, Deserialize)]
struct ConnectRequest {
    token: Option<String>,
}

impl Validate for ConnectRequest {
    fn validate(&self, e: &mut Errors) {
        let token = self.token.as_deref();
        e.not_blank("token", token)
            .size("token", token, 0, urls::MAX_TOKEN_CHARS);
    }
}

/// Connect and sync share one per-IP budget; status and disconnect never reach the bridge.
fn check_rate_limit(state: &AppState, ip: &ClientIp) -> AppResult<()> {
    if state
        .limits
        .try_consume(&policies::SIMPLEFIN_REQUEST, &ip.as_java_string())
    {
        return Ok(());
    }
    Err(AppError::status(
        StatusCode::TOO_MANY_REQUESTS,
        "Too many SimpleFIN requests. Please wait a moment before trying again.",
    ))
}

async fn connect(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<ConnectRequest>,
) -> AppResult<Response> {
    check_rate_limit(&state, &ip)?;
    service::connect(
        &state,
        req.token.as_deref().unwrap_or_default(),
        auth.member_id,
    )
    .await?;
    Ok(StatusCode::NO_CONTENT.into_response())
}

async fn status(State(state): State<AppState>, auth: Auth) -> AppResult<Response> {
    let status = service::connection_status(&state, auth.member_id).await?;
    Ok(axum::Json(status).into_response())
}

async fn sync(State(state): State<AppState>, auth: Auth, ip: ClientIp) -> AppResult<Response> {
    check_rate_limit(&state, &ip)?;
    let accounts = service::sync(&state, auth.member_id).await?;
    Ok(axum::Json(accounts).into_response())
}

async fn clear_connection(State(state): State<AppState>, auth: Auth) -> AppResult<Response> {
    service::delete_connection(&state, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT.into_response())
}
