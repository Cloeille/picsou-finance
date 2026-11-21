//! What the sidecar controllers share: the session status body, the initiate answer, the
//! per-IP authentication budget and the request validation helpers.

use chrono::{DateTime, Utc};
use http::StatusCode;
use serde::Serialize;

use super::session::{SidecarSession, SidecarSyncStatus};
use super::template::SessionView;
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::rate_limit::Policy;
use crate::ledger::sidecar::SidecarErrorCode;

/// SessionStatusResponse of most sidecar banks.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionStatusResponse {
    pub is_active: bool,
    pub expires_at: Option<DateTime<Utc>>,
    pub sync_status: SidecarSyncStatus,
    pub last_sync_started_at: Option<DateTime<Utc>>,
    pub last_sync_completed_at: Option<DateTime<Utc>>,
    pub last_sync_error: Option<SidecarErrorCode>,
    /// Present and `true` only on Picsou Desktop for connectors with native OS WebView login.
    pub webview_login_supported: Option<bool>,
}

impl SessionStatusResponse {
    pub fn inactive() -> Self {
        Self {
            is_active: false,
            expires_at: None,
            sync_status: SidecarSyncStatus::Idle,
            last_sync_started_at: None,
            last_sync_completed_at: None,
            last_sync_error: None,
            webview_login_supported: None,
        }
    }

    pub fn of(session: &SidecarSession) -> Self {
        Self {
            is_active: session.active,
            expires_at: None,
            sync_status: session.sync_status,
            last_sync_started_at: session.last_sync_started_at,
            last_sync_completed_at: session.last_sync_completed_at,
            last_sync_error: session.last_sync_error,
            webview_login_supported: None,
        }
    }

    pub fn with_webview_supported(mut self, supported: bool) -> Self {
        self.webview_login_supported = supported.then_some(true);
        self
    }
}

impl SessionView for SessionStatusResponse {
    fn is_active(&self) -> bool {
        self.is_active
    }

    fn last_sync_error(&self) -> Option<SidecarErrorCode> {
        self.last_sync_error
    }
}

/// AuthInitResponse: the pending challenge, if the login needs a second factor.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthInitResponse {
    pub process_id: Option<String>,
    pub mfa_required: bool,
    pub mfa_type: Option<String>,
}

/// SidecarInitiate: the sidecar's answer to /initiate.
#[derive(Debug, Clone, Default, serde::Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SidecarInitiate {
    pub process_id: Option<String>,
    pub mfa_required: bool,
    pub mfa_type: Option<String>,
    pub session_state: Option<String>,
}

/// SidecarSessionState: the sidecar's answer to /complete.
#[derive(Debug, Clone, Default, serde::Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SidecarSessionState {
    pub session_state: Option<String>,
}

/// Takes one token of the client IP's authentication budget, or the 429 the controllers answer
/// with `detail`.
pub fn consume(state: &AppState, policy: &Policy, ip: &ClientIp, detail: &str) -> AppResult<()> {
    if state.limits.try_consume(policy, &ip.as_java_string()) {
        Ok(())
    } else {
        Err(AppError::Status {
            status: StatusCode::TOO_MANY_REQUESTS,
            detail: detail.to_owned(),
        })
    }
}
