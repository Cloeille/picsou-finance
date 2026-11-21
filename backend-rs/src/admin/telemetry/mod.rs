//! TelemetryService and TelemetryController: opt-in anonymous telemetry. Effective only with a
//! DSN configured and the admin's consent, both re-read on every call. Browser envelopes come
//! through the tunnel, re-scrubbed and forwarded from the server so no client address reaches
//! the collector.

pub mod envelope;
pub mod scrubber;

use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::body::Bytes;
use axum::extract::State;
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::Serialize;
use tokio::sync::Semaphore;
use tokio::task::AbortHandle;

use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppResult, Problem};
use crate::http::rate_limit::policies;
use crate::setup_auth::settings;
use envelope::Dsn;

pub const KEY_CONSENT: &str = "telemetry.consent";
pub const MAX_ENVELOPE_BYTES: usize = 200 * 1024;
const MAX_IN_FLIGHT_SENDS: usize = 16;
const TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Consent {
    Enabled,
    Disabled,
    Unset,
}

impl Consent {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Enabled => "ENABLED",
            Self::Disabled => "DISABLED",
            Self::Unset => "UNSET",
        }
    }
}

/// The bounded in-flight forwards, cancelled when consent is withdrawn.
pub struct Sends {
    permits: Arc<Semaphore>,
    pending: Mutex<Vec<AbortHandle>>,
}

impl Default for Sends {
    fn default() -> Self {
        Self {
            permits: Arc::new(Semaphore::new(MAX_IN_FLIGHT_SENDS)),
            pending: Mutex::new(Vec::new()),
        }
    }
}

impl Sends {
    fn cancel_all(&self) {
        for handle in self.pending.lock().unwrap_or_else(|e| e.into_inner()).drain(..) {
            handle.abort();
        }
    }
}

fn dsn(state: &AppState) -> Option<Dsn> {
    Dsn::parse(&state.config.telemetry_dsn)
}

pub fn is_available(state: &AppState) -> bool {
    dsn(state).is_some()
}

pub async fn consent(state: &AppState) -> Result<Consent, sqlx::Error> {
    Ok(match settings::read(state, KEY_CONSENT).await?.as_deref() {
        Some("ENABLED") => Consent::Enabled,
        Some("DISABLED") => Consent::Disabled,
        _ => Consent::Unset,
    })
}

pub async fn is_enabled(state: &AppState) -> Result<bool, sqlx::Error> {
    Ok(is_available(state) && consent(state).await? == Consent::Enabled)
}

fn environment(state: &AppState) -> String {
    let e = state.config.telemetry_environment.trim();
    if e.is_empty() { "production".to_owned() } else { e.to_owned() }
}

fn release(state: &AppState) -> String {
    let r = state.config.app_version.trim();
    if r.is_empty() { "dev".to_owned() } else { r.to_owned() }
}

/// setEnabled: enabling without a DSN is refused, so a consent never waits for a collector.
pub async fn set_enabled(state: &AppState, enabled: bool) -> Result<bool, sqlx::Error> {
    if enabled && !is_available(state) {
        return Ok(false);
    }
    settings::upsert(&state.db, KEY_CONSENT, if enabled { "ENABLED" } else { "DISABLED" }).await?;
    if !enabled {
        state.admin.telemetry.cancel_all();
    }
    tracing::info!("telemetry.consent.changed enabled={enabled}");
    Ok(true)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConfigResponse {
    enabled: bool,
    /// Null unless telemetry is effectively enabled; always present.
    dsn: Option<String>,
    environment: String,
    release: String,
    available: bool,
    consent: Consent,
}

pub async fn config(State(state): State<AppState>, _auth: Auth) -> AppResult<axum::Json<ConfigResponse>> {
    let available = is_available(&state);
    let consent = consent(&state).await?;
    let enabled = available && consent == Consent::Enabled;
    Ok(axum::Json(ConfigResponse {
        enabled,
        dsn: if enabled { dsn(&state).map(|d| d.raw) } else { None },
        environment: environment(&state),
        release: release(&state),
        available,
        consent,
    }))
}

fn too_many_requests() -> Response {
    let mut response = Problem::with_detail(
        StatusCode::TOO_MANY_REQUESTS,
        "Too many telemetry requests. Please wait before retrying.",
    )
    .into_response();
    response
        .headers_mut()
        .insert(header::RETRY_AFTER, HeaderValue::from_static("60"));
    response
}

fn too_large() -> Response {
    Problem::with_detail(StatusCode::PAYLOAD_TOO_LARGE, "Envelope too large").into_response()
}

/// Sanitizes and queues one bounded asynchronous forward; false only when the in-flight
/// limit is full.
async fn forward(state: &AppState, raw: &[u8]) -> bool {
    let Some(target) = dsn(state) else { return true };
    if raw.is_empty() {
        return true;
    }
    let Some(body) = envelope::sanitize(raw, &target.raw) else {
        return true;
    };
    if !is_enabled(state).await.unwrap_or(false) {
        return true;
    }
    let Ok(permit) = state.admin.telemetry.permits.clone().try_acquire_owned() else {
        return false;
    };
    let request = state
        .http
        .post(target.envelope_url())
        .timeout(TIMEOUT)
        .header(header::CONTENT_TYPE, "application/x-sentry-envelope")
        .header("X-Sentry-Auth", target.auth_header())
        .body(body);
    let task = tokio::spawn(async move {
        let _permit = permit;
        match request.send().await {
            Ok(response) => {
                tracing::debug!("telemetry.tunnel.forwarded status={}", response.status().as_u16());
            }
            Err(e) => tracing::error!("telemetry.tunnel.failed: {}", e.without_url()),
        }
    });
    let mut pending = state.admin.telemetry.pending.lock().unwrap_or_else(|e| e.into_inner());
    pending.retain(|h| !h.is_finished());
    pending.push(task.abort_handle());
    true
}

/// The Sentry envelope tunnel: 204 unless rate limited or too large.
pub async fn tunnel(
    State(state): State<AppState>,
    auth: Auth,
    headers: HeaderMap,
    body: Bytes,
) -> AppResult<Response> {
    if !is_enabled(&state).await? {
        return Ok(StatusCode::NO_CONTENT.into_response());
    }
    if !state.limits.try_consume(&policies::TELEMETRY, &auth.user.username) {
        return Ok(too_many_requests());
    }
    let declared = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<usize>().ok());
    if declared.is_some_and(|l| l > MAX_ENVELOPE_BYTES) || body.len() > MAX_ENVELOPE_BYTES {
        return Ok(too_large());
    }
    if !forward(&state, &body).await {
        return Ok(too_many_requests());
    }
    Ok(StatusCode::NO_CONTENT.into_response())
}
