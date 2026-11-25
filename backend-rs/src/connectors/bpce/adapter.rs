//! BpceSidecarAdapter: the client half every BPCE sidecar shares. The three login calls are plain
//! single-shot requests, never replayed: a wrong password consumes a bank attempt and can lock
//! the account. Nothing of a request or an answer body is logged.

use std::time::Duration;

use http::StatusCode;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::json;

use super::Bank;
use super::prepare::AccountsSnapshot;
use crate::AppState;
use crate::connectors::sidecar::{SidecarClient, SyncError};
use crate::ledger::sidecar::SidecarErrorCode;

const ACCOUNTS_TIMEOUT: Duration = Duration::from_secs(120);
/// The sidecar waits up to 150 s for the human to approve the push: stay above it.
const INITIATE_TIMEOUT: Duration = Duration::from_secs(90);
const COMPLETE_TIMEOUT: Duration = Duration::from_secs(170);
/// The sidecar gives /keypad 60 s (clicks, Valider, Sécur'Pass page): stay above it.
const KEYPAD_TIMEOUT: Duration = Duration::from_secs(70);
const SECURPASS_PENDING: &str = "SECURPASS_PENDING";

/// The sidecar's answer to /initiate: the keypad to show and how long the keypad step lasts.
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct InitiateResult {
    pub process_id: Option<String>,
    pub keypad: Option<Keypad>,
    pub expires_in_seconds: Option<i32>,
}

/// Ten data:image/png;base64 images in DOM order, laid out in \`columns\` columns.
#[derive(Debug, Default, Deserialize)]
#[serde(default)]
pub struct Keypad {
    pub images: Option<Vec<Option<String>>>,
    pub columns: Option<i32>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct KeypadAck {
    status: Option<String>,
}

/// The state is a cookie jar: never printed.
#[derive(Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct SessionBody {
    session_state: Option<String>,
}

/// The positions are the user's password in disguise: never printed.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct KeypadBody<'a> {
    process_id: &'a str,
    positions: &'a [i32],
}

fn auth_client(state: &AppState, bank: &Bank) -> SidecarClient {
    SidecarClient::new(state, bank.codes, SidecarErrorCode::AuthAttemptExpired)
}

fn worded(bank: &Bank, code: SidecarErrorCode, fallback: &str) -> SyncError {
    SyncError::new(code, bank.codes.message(code).unwrap_or(fallback))
}

/// POST /initiate with the bank's own body. One attempt, no retry.
pub async fn initiate<B: Serialize + Sync>(
    state: &AppState,
    bank: &Bank,
    body: &B,
) -> Result<InitiateResult, SyncError> {
    auth_post(
        state,
        bank,
        "/initiate",
        body,
        INITIATE_TIMEOUT,
        &format!("Could not start the {} login", bank.provider()),
        SidecarErrorCode::AppValidationTimeout,
        None,
    )
    .await
}

/// POST /keypad: clicks the positions and waits for the Sécur'Pass page.
pub async fn submit_keypad(
    state: &AppState,
    bank: &Bank,
    process_id: &str,
    positions: &[i32],
) -> Result<(), SyncError> {
    let message = format!("Could not send the {} keypad choice", bank.provider());
    let ack: KeypadAck = auth_post(
        state,
        bank,
        "/keypad",
        &KeypadBody {
            process_id,
            positions,
        },
        KEYPAD_TIMEOUT,
        &message,
        SidecarErrorCode::KeypadExpired,
        Some(SidecarErrorCode::InvalidPositions),
    )
    .await?;
    if ack.status.as_deref() != Some(SECURPASS_PENDING) {
        return Err(worded(
            bank,
            SidecarErrorCode::UpstreamFormatChanged,
            &message,
        ));
    }
    Ok(())
}

/// POST /complete: blocks until the human approved on the phone, then the session state.
pub async fn complete(
    state: &AppState,
    bank: &Bank,
    process_id: &str,
) -> Result<Option<String>, SyncError> {
    let body: SessionBody = auth_post(
        state,
        bank,
        "/complete",
        &json!({ "processId": process_id }),
        COMPLETE_TIMEOUT,
        &format!("Could not finish the {} login", bank.provider()),
        SidecarErrorCode::AppValidationTimeout,
        None,
    )
    .await?;
    Ok(body.session_state)
}

/// POST /accounts: every importable contract with its transactions, all or nothing.
pub async fn fetch_accounts(
    state: &AppState,
    bank: &Bank,
    plain_state: &str,
) -> Result<AccountsSnapshot, SyncError> {
    SidecarClient::new(state, bank.codes, SidecarErrorCode::SessionExpired)
        .post(
            "/accounts",
            &json!({ "sessionState": plain_state }),
            ACCOUNTS_TIMEOUT,
            &format!("Could not fetch {} accounts", bank.provider()),
            Some(SidecarErrorCode::SessionExpired),
        )
        .await
}

/// One request, no retry. The statuses only the login contract defines (408, 409, 422, 429) are
/// resolved here, everything else by the shared client.
#[allow(clippy::too_many_arguments)]
async fn auth_post<T: DeserializeOwned, B: Serialize + Sync + ?Sized>(
    state: &AppState,
    bank: &Bank,
    path: &str,
    body: &B,
    timeout: Duration,
    message: &str,
    timeout_code: SidecarErrorCode,
    unprocessable_code: Option<SidecarErrorCode>,
) -> Result<T, SyncError> {
    let client = auth_client(state, bank);
    let response = client
        .post_raw(path, body, timeout)
        .await
        .map_err(|e| client.map_call_error(message, e))?;
    if !response.is_success() {
        return Err(match (response.status, unprocessable_code) {
            (StatusCode::REQUEST_TIMEOUT, _) => worded(bank, timeout_code, message),
            (StatusCode::UNPROCESSABLE_ENTITY, Some(code)) => worded(bank, code, message),
            (StatusCode::CONFLICT, _) => worded(bank, SidecarErrorCode::KeypadChanged, message),
            (StatusCode::TOO_MANY_REQUESTS, _) => SyncError::new(
                SidecarErrorCode::UpstreamUnavailable,
                format!(
                    "Too many {} logins are pending. Please wait a few minutes.",
                    bank.provider()
                ),
            ),
            _ => client.map_status(
                message,
                &response,
                Some(SidecarErrorCode::InvalidCredentials),
            ),
        });
    }
    client
        .decode(message, &response)?
        .ok_or_else(|| client.coded(SidecarErrorCode::UpstreamUnavailable, message))
}
