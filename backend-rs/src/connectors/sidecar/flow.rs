//! The login most sidecars share: /initiate with the user's secret, an optional /complete with a
//! one-time code, then the session stored encrypted and its first import queued.

use std::time::Duration;

use serde::Serialize;

use super::client::SidecarClient;
use super::template::{SyncStrategy, SyncTemplate, error};
use super::web::{AuthInitResponse, SidecarInitiate, SidecarSessionState};
use crate::AppState;
use crate::error::AppResult;
use crate::ledger::sidecar::SidecarErrorCode;

/// What one bank's login calls are named and mean.
pub struct Login {
    pub timeout: Duration,
    pub initiate_message: &'static str,
    pub complete_message: &'static str,
    /// "<Bank> did not return a session".
    pub no_session: &'static str,
    /// What a bare 401 on /complete means (usually INVALID_OTP).
    pub complete_failure: Option<SidecarErrorCode>,
}

/// POST /initiate; a login that needs no second factor stores its session at once.
pub async fn initiate<S: SyncStrategy, B: Serialize + ?Sized>(
    state: &AppState,
    client: &SidecarClient,
    template: &SyncTemplate<S>,
    login: &Login,
    body: &B,
    member_id: i64,
) -> AppResult<AuthInitResponse> {
    let result: SidecarInitiate = client
        .post(
            "/initiate",
            body,
            login.timeout,
            login.initiate_message,
            Some(SidecarErrorCode::InvalidCredentials),
        )
        .await?;
    if !result.mfa_required {
        let session = non_blank(result.session_state, login.no_session)?;
        template
            .replace_and_queue(state, member_id, session, false, |_| Ok(()))
            .await?;
    }
    Ok(AuthInitResponse {
        process_id: result.process_id,
        mfa_required: result.mfa_required,
        mfa_type: result.mfa_type,
    })
}

/// POST /complete with `timeout`, then the session stored and queued.
pub async fn complete<S: SyncStrategy, B: Serialize + ?Sized>(
    state: &AppState,
    client: &SidecarClient,
    template: &SyncTemplate<S>,
    login: &Login,
    timeout: Duration,
    body: &B,
    member_id: i64,
) -> AppResult<()> {
    let response: SidecarSessionState = client
        .post(
            "/complete",
            body,
            timeout,
            login.complete_message,
            login.complete_failure,
        )
        .await?;
    let session = non_blank(response.session_state, login.no_session)?;
    template
        .replace_and_queue(state, member_id, session, false, |_| Ok(()))
        .await
}

pub fn non_blank(value: Option<String>, message: &str) -> AppResult<String> {
    Ok(value
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| error(SidecarErrorCode::InvalidData, message))?)
}
