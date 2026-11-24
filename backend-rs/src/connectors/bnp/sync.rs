//! BnpSyncService and BnpAdapter: BNP Paribas and Hello bank! (one platform, two base URLs).
//! The member types the customer number, clicks the six digits on the bank's shuffled keypad and
//! confirms the strong authentication; only the resulting cookie jar is kept, encrypted, in
//! `sidecar_session`. Fail closed: an incomplete snapshot never replaces what a previous sync
//! imported, and products the connector does not read are reported by id and family only.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::{Arc, LazyLock, Mutex, MutexGuard};
use std::time::Duration;

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use async_trait::async_trait;
use chrono::{DateTime, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::AppState;
use crate::connectors::sidecar::keypad::{Keypad, RawKeypad, safe_keypad};
use crate::connectors::sidecar::session::{self, SidecarSession, SidecarSyncStatus};
use crate::connectors::sidecar::template::SessionView;
use crate::connectors::sidecar::{
    JobResult, SidecarClient, SidecarHttp, SidecarSessionState, SyncError, SyncJob, SyncStrategy,
    SyncTemplate,
};
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::categorize::{self, CategorizationContext};
use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::model::{Account, AccountType, HoldingDraft};
use crate::ledger::prices::PriceNeeds;
use crate::ledger::providers::{BNP as PROVIDER, HELLO_BANK as HELLO_BANK_PROVIDER};
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode, SidecarTransaction, SourceSyncResult};
use crate::ledger::sync_values::{self, clean, limit, money_close, sum_complete, weighted_average};
use crate::ledger::valuation::{self, SnapshotContext};
use crate::ledger::{holdings, members, sidecar_import, snapshots};

const BANK: SidecarBank = SidecarBank::Bnp;
/// The names an Enable Banking link of the same bank may carry; matched by IBAN, never by name.
const PROVIDER_FAMILY: &[&str] = &[PROVIDER, HELLO_BANK_PROVIDER, "Hello bank", "Hello Bank"];
const SOURCE: &str = "bnp";
const EXTERNAL_ID_PREFIX: &str = "bnp_";
const MAX_EXTERNAL_ID_LENGTH: usize = 100;
const MAX_TICKER_LENGTH: usize = 30;
const MAX_UNSUPPORTED: usize = 100;
const MAX_ATTEMPT_SECONDS: i32 = 600;
const ACCOUNTS_TIMEOUT: Duration = Duration::from_secs(180);
const INITIATE_TIMEOUT: Duration = Duration::from_secs(120);
const KEYPAD_TIMEOUT: Duration = Duration::from_secs(90);
const COMPLETE_TIMEOUT: Duration = Duration::from_secs(200);
const WEBVIEW_TIMEOUT: Duration = Duration::from_secs(30);
const WEBVIEW_ATTEMPT_SECONDS: i64 = 360;
const APPROVAL_PENDING: &str = "APPROVAL_PENDING";
const CODE_REQUIRED: &str = "CODE_REQUIRED";

pub fn webview_supported(state: &AppState) -> bool {
    state.config.desktop.eq_ignore_ascii_case("true")
}

static ISIN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Z]{2}[A-Z0-9]{9}[0-9]$").expect("regex"));
static IBAN_SHAPE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$").expect("regex"));

// --- pending logins ---------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Stage {
    Keypad,
    Approval,
    Code,
    Webview,
}

/// A login in flight: its owner, its deadline and its step. Nothing the member typed is kept.
#[derive(Debug, Clone, PartialEq, Eq)]
struct PendingLogin {
    member_id: i64,
    deadline: DateTime<Utc>,
    generation: u64,
    stage: Stage,
}

#[derive(Default)]
struct LoginMaps {
    pending: HashMap<String, PendingLogin>,
    /// Bumped by a disconnect: a login waiting on the bank meanwhile stores nothing.
    generations: HashMap<i64, u64>,
}

#[derive(Default)]
pub struct Logins {
    maps: Mutex<LoginMaps>,
    /// sessionWriteLock: a session write and a disconnect never interleave.
    write: tokio::sync::Mutex<()>,
}

impl Logins {
    fn maps(&self) -> MutexGuard<'_, LoginMaps> {
        self.maps
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn generation(&self, member_id: i64) -> u64 {
        self.maps()
            .generations
            .get(&member_id)
            .copied()
            .unwrap_or(0)
    }

    fn evict_expired(&self, now: DateTime<Utc>) {
        self.maps().pending.retain(|_, p| p.deadline > now);
    }

    fn remove(&self, process_id: &str) {
        self.maps().pending.remove(process_id);
    }

    fn owned_attempt(
        &self,
        process_id: &str,
        member_id: i64,
        now: DateTime<Utc>,
    ) -> Result<PendingLogin, SyncError> {
        self.maps()
            .pending
            .get(process_id)
            .filter(|p| p.member_id == member_id && p.deadline > now)
            .cloned()
            .ok_or_else(|| {
                error(
                    SidecarErrorCode::AuthAttemptExpired,
                    "BNP Paribas sign-in attempt expired",
                )
            })
    }

    fn require_generation(&self, member_id: i64, generation: u64) -> Result<(), SyncError> {
        if self.generation(member_id) != generation {
            return Err(error(
                SidecarErrorCode::AuthAttemptExpired,
                "The BNP Paribas connection was removed during the login",
            ));
        }
        Ok(())
    }
}

fn logins(state: &AppState) -> Arc<Logins> {
    state.connectors.jobs.local::<Logins>("bnp-logins")
}

/// Informational only; rebuilt by every successful sync, so a restart shows none until the next.
#[derive(Default)]
struct UnsupportedByMember(Mutex<HashMap<i64, Vec<UnsupportedProduct>>>);

impl UnsupportedByMember {
    fn map(&self) -> MutexGuard<'_, HashMap<i64, Vec<UnsupportedProduct>>> {
        self.0
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

fn unsupported_by_member(state: &AppState) -> Arc<UnsupportedByMember> {
    state
        .connectors
        .jobs
        .local::<UnsupportedByMember>("bnp-unsupported")
}

/// The scheduled sweep of abandoned logins, for track T8 (every minute in Java).
pub fn evict_expired_pending_logins(state: &AppState) {
    logins(state).evict_expired(state.clock.now());
}

// --- responses ---------------------------------------------------------------------

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InitiateResponse {
    pub process_id: String,
    pub keypad: Keypad,
    pub expires_in_seconds: i32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct KeypadResponse {
    pub process_id: String,
    pub status: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UnsupportedProduct {
    pub external_id: String,
    pub family_code: String,
}

/// BnpSyncService.SessionStatusResponse.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionStatusResponse {
    pub is_active: bool,
    pub sync_status: SidecarSyncStatus,
    pub last_sync_started_at: Option<DateTime<Utc>>,
    pub last_sync_completed_at: Option<DateTime<Utc>>,
    pub last_sync_error: Option<SidecarErrorCode>,
    pub unsupported: Vec<UnsupportedProduct>,
    pub unsupported_count: usize,
    /// Present and `true` only on Picsou Desktop where the native WebView bridge is available.
    pub webview_login_supported: Option<bool>,
}

impl SessionStatusResponse {
    fn inactive(webview_login_supported: Option<bool>) -> Self {
        Self {
            is_active: false,
            sync_status: SidecarSyncStatus::Idle,
            last_sync_started_at: None,
            last_sync_completed_at: None,
            last_sync_error: None,
            unsupported: Vec::new(),
            unsupported_count: 0,
            webview_login_supported,
        }
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

fn to_status(state: &AppState, session: &SidecarSession) -> SessionStatusResponse {
    let unsupported = unsupported_by_member(state)
        .map()
        .get(&session.member_id)
        .cloned()
        .unwrap_or_default();
    SessionStatusResponse {
        is_active: session.active,
        sync_status: session.sync_status,
        last_sync_started_at: session.last_sync_started_at,
        last_sync_completed_at: session.last_sync_completed_at,
        last_sync_error: session.last_sync_error,
        unsupported_count: unsupported.len(),
        unsupported,
        webview_login_supported: webview_supported(state).then_some(true),
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WebviewStartResponse {
    pub process_id: String,
    pub status: String,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WebviewStatusResponse {
    pub status: String,
    pub error_code: Option<SidecarErrorCode>,
    pub session: Option<SessionStatusResponse>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WebviewCancelResponse {
    pub cancelled: bool,
}

// --- adapter -----------------------------------------------------------------------

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct InitiateResult {
    process_id: Option<String>,
    keypad: Option<RawKeypad>,
    expires_in_seconds: Option<i32>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct KeypadAck {
    status: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Position {
    isin: Option<String>,
    label: Option<String>,
    quantity: Option<Decimal>,
    value: Option<Decimal>,
    pnl: Option<Decimal>,
}

/// One product, in the sidecar's own vocabulary. `balance` is the total valuation; for a
/// securities account `cashBalance` is the cash leg and `positions` the lines. A loan's balance
/// is negative, as the bank shows it.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AccountData {
    external_id: Option<String>,
    kind: Option<String>,
    name: Option<String>,
    balance: Option<Decimal>,
    cash_balance: Option<Decimal>,
    currency: Option<String>,
    iban: Option<String>,
    transactions: Option<Vec<SidecarTransaction>>,
    positions: Option<Vec<Position>>,
    snapshot_complete: Option<bool>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Unsupported {
    external_id: Option<String>,
    family_code: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AccountsSnapshot {
    brand: Option<String>,
    accounts: Option<Vec<AccountData>>,
    unsupported: Option<Vec<Unsupported>>,
    session_state: Option<String>,
}

fn error(code: SidecarErrorCode, message: &str) -> SyncError {
    SyncError::new(code, message)
}

/// The code with the bank's own wording.
fn declared(code: SidecarErrorCode, fallback: &str) -> SyncError {
    error(code, BANK.message(code).unwrap_or(fallback))
}

/// One login step, never retried: a replayed keypad would burn a second attempt at the bank.
/// The sidecar answers 409 for a changed page but also for an action the bank wants on its site
/// or an unsupported second factor: a declared detail wins, the status is the fallback.
async fn auth_post<T: DeserializeOwned>(
    state: &AppState,
    path: &str,
    body: &Value,
    timeout: Duration,
    message: &str,
    authentication_failure: SidecarErrorCode,
    timeout_code: SidecarErrorCode,
) -> Result<T, SyncError> {
    let client = SidecarClient::new(state, BANK, SidecarErrorCode::AuthAttemptExpired);
    let response = client
        .post_raw(path, body, timeout)
        .await
        .map_err(|e| client.map_call_error(message, e))?;
    if !response.is_success() {
        return Err(match response.status.as_u16() {
            408 => declared(timeout_code, message),
            409 if client.response_code(&response).is_none() => {
                declared(SidecarErrorCode::KeypadChanged, message)
            }
            429 => declared(SidecarErrorCode::RateLimited, message),
            _ => client.map_status(message, &response, Some(authentication_failure)),
        });
    }
    client
        .decode(message, &response)?
        .ok_or_else(|| client.coded(SidecarErrorCode::UpstreamUnavailable, message))
}

// --- login ---------------------------------------------------------------------------

fn template() -> SyncTemplate<Strategy> {
    SyncTemplate::new(BANK, Strategy, false)
}

/// Starts the login and answers with the bank's keypad; the attempt is remembered for its member.
pub async fn initiate_auth(
    state: &AppState,
    customer_id: &str,
    brand: &str,
    member_id: i64,
) -> AppResult<InitiateResponse> {
    let logins = logins(state);
    let generation = logins.generation(member_id);
    let result: InitiateResult = auth_post(
        state,
        "/initiate",
        &json!({ "customerId": customer_id, "brand": brand }),
        INITIATE_TIMEOUT,
        "Could not start the BNP Paribas login",
        SidecarErrorCode::InvalidCredentials,
        SidecarErrorCode::UpstreamUnavailable,
    )
    .await?;
    let challenge = match (result.process_id, result.keypad, result.expires_in_seconds) {
        (Some(process_id), Some(keypad), Some(expires))
            if clean(Some(&process_id)).is_some() && expires > 0 =>
        {
            Some((process_id, keypad, expires))
        }
        _ => None,
    };
    let Some((process_id, raw_keypad, expires)) = challenge else {
        return Err(error(
            SidecarErrorCode::UpstreamFormatChanged,
            "BNP Paribas did not return a usable sign-in challenge",
        )
        .into());
    };
    let keypad = safe_keypad(&raw_keypad).ok_or_else(|| {
        error(
            SidecarErrorCode::KeypadChanged,
            "BNP Paribas changed its login page. Nothing was sent.",
        )
    })?;
    let now = state.clock.now();
    let seconds = expires.min(MAX_ATTEMPT_SECONDS);
    {
        let _write = logins.write.lock().await;
        if generation != logins.generation(member_id) {
            return Err(error(
                SidecarErrorCode::AuthAttemptExpired,
                "This BNP Paribas sign-in attempt expired. Please start again.",
            )
            .into());
        }
        logins.evict_expired(now);
        logins.maps().pending.insert(
            process_id.clone(),
            PendingLogin {
                member_id,
                deadline: now + chrono::Duration::seconds(i64::from(seconds)),
                generation,
                stage: Stage::Keypad,
            },
        );
    }
    Ok(InitiateResponse {
        process_id,
        keypad,
        expires_in_seconds: seconds,
    })
}

/// Sends the clicked tiles; the answer says whether a code is still expected.
pub async fn submit_keypad(
    state: &AppState,
    process_id: &str,
    positions: &[u8],
    member_id: i64,
) -> AppResult<KeypadResponse> {
    let logins = logins(state);
    let current = logins.owned_attempt(process_id, member_id, state.clock.now())?;
    if current.stage != Stage::Keypad {
        // The bank counts a second code against the lockout: a replay is refused here.
        return Err(error(
            SidecarErrorCode::AuthAttemptExpired,
            "The keypad was already sent for this sign-in",
        )
        .into());
    }
    let sent = async {
        let ack: KeypadAck = auth_post(
            state,
            "/keypad",
            &json!({ "processId": process_id, "positions": positions }),
            KEYPAD_TIMEOUT,
            "Could not send the BNP Paribas keypad choice",
            SidecarErrorCode::InvalidCredentials,
            SidecarErrorCode::AppValidationTimeout,
        )
        .await?;
        match ack.status {
            Some(status) if status == APPROVAL_PENDING || status == CODE_REQUIRED => Ok(status),
            _ => Err(declared(
                SidecarErrorCode::UpstreamFormatChanged,
                "Could not send the BNP Paribas keypad choice",
            )),
        }
    }
    .await;
    let status = match sent {
        Ok(status) => status,
        Err(e) => {
            // A refused or timed-out code ends the attempt: the member starts again.
            logins.remove(process_id);
            return Err(e.into());
        }
    };
    let next = if status == CODE_REQUIRED {
        Stage::Code
    } else {
        Stage::Approval
    };
    {
        let _write = logins.write.lock().await;
        logins.require_generation(member_id, current.generation)?;
        let mut maps = logins.maps();
        if maps.pending.get(process_id) == Some(&current) {
            maps.pending.insert(
                process_id.to_owned(),
                PendingLogin {
                    stage: next,
                    ..current
                },
            );
        }
    }
    Ok(KeypadResponse {
        process_id: process_id.to_owned(),
        status,
    })
}

/// Finishes the strong authentication, then stores the session and queues the first import.
/// Without a code it waits for the approval in the bank's app; with one it submits the SMS code.
pub async fn complete_auth(
    state: &AppState,
    process_id: &str,
    code: Option<&str>,
    member_id: i64,
) -> AppResult<SessionStatusResponse> {
    let logins = logins(state);
    let current = logins.owned_attempt(process_id, member_id, state.clock.now())?;
    if current.stage == Stage::Keypad {
        return Err(error(
            SidecarErrorCode::AuthAttemptExpired,
            "The keypad was not sent for this sign-in",
        )
        .into());
    }
    if current.stage == Stage::Webview {
        return Err(error(
            SidecarErrorCode::AuthAttemptExpired,
            "BNP Paribas sign-in attempt expired",
        )
        .into());
    }
    let code_required = current.stage == Stage::Code;
    if code_required != code.is_some() {
        return Err(if code_required {
            error(
                SidecarErrorCode::InvalidOtp,
                "BNP Paribas expects the code it sent",
            )
        } else {
            error(
                SidecarErrorCode::InvalidData,
                "No code is expected for this sign-in",
            )
        }
        .into());
    }
    let body = match code {
        Some(code) => json!({ "processId": process_id, "code": code }),
        None => json!({ "processId": process_id }),
    };
    let completed: Result<SidecarSessionState, SyncError> = auth_post(
        state,
        "/complete",
        &body,
        COMPLETE_TIMEOUT,
        "Could not finish the BNP Paribas login",
        if code.is_none() {
            SidecarErrorCode::InvalidCredentials
        } else {
            SidecarErrorCode::InvalidOtp
        },
        SidecarErrorCode::AppValidationTimeout,
    )
    .await;
    let response = match completed {
        Ok(response) => response,
        Err(e) => {
            // A mistyped code can be typed again; every other failure ends the attempt.
            let mistyped = code_required
                && sync_values::error_code(e.code(), BANK, SidecarErrorCode::InternalError)
                    == SidecarErrorCode::InvalidOtp;
            if !mistyped {
                logins.remove(process_id);
            }
            return Err(e.into());
        }
    };
    logins.remove(process_id);
    let Some(plain_state) = response.session_state.filter(|s| clean(Some(s)).is_some()) else {
        return Err(error(
            SidecarErrorCode::InvalidData,
            "BNP Paribas did not return a session",
        )
        .into());
    };
    let template = template();
    let job = {
        let _write = logins.write.lock().await;
        logins.require_generation(member_id, current.generation)?;
        let mut tx = state.db.begin().await?;
        let job = template
            .insert_queued(
                state,
                &mut tx.as_handle(),
                member_id,
                plain_state,
                false,
                |_| Ok(()),
            )
            .await?;
        tx.commit().await?;
        job
    };
    let status = get_status(state, member_id).await?;
    template.submit(state, job)?;
    Ok(status)
}

// --- webview login (Picsou Desktop) ----------------------------------------------------

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct BridgeWebviewStart {
    process_id: Option<String>,
    status: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct BridgeWebviewStatus {
    status: Option<String>,
    session_state: Option<String>,
    error_code: Option<String>,
}

fn require_webview(state: &AppState) -> AppResult<()> {
    if webview_supported(state) {
        Ok(())
    } else {
        Err(AppError::NotFound(
            "BNP Paribas interactive WebView login is only available in Picsou Desktop".to_owned(),
        ))
    }
}

pub async fn start_webview(
    state: &AppState,
    brand: &str,
    member_id: i64,
) -> AppResult<WebviewStartResponse> {
    require_webview(state)?;
    let logins = logins(state);
    let generation = logins.generation(member_id);
    let started: BridgeWebviewStart = auth_post(
        state,
        "/webview/start",
        &json!({ "brand": brand }),
        WEBVIEW_TIMEOUT,
        "Could not start the BNP Paribas WebView login",
        SidecarErrorCode::UpstreamUnavailable,
        SidecarErrorCode::UpstreamUnavailable,
    )
    .await?;
    let Some(process_id) = started.process_id.filter(|s| clean(Some(s)).is_some()) else {
        return Err(error(
            SidecarErrorCode::UpstreamFormatChanged,
            "BNP Paribas WebView bridge did not return a process identifier",
        )
        .into());
    };
    let status = started.status.unwrap_or_else(|| "WAITING_LOGIN".to_owned());
    let now = state.clock.now();
    {
        let _write = logins.write.lock().await;
        logins.require_generation(member_id, generation)?;
        logins.evict_expired(now);
        logins.maps().pending.insert(
            process_id.clone(),
            PendingLogin {
                member_id,
                deadline: now + chrono::Duration::seconds(WEBVIEW_ATTEMPT_SECONDS),
                generation,
                stage: Stage::Webview,
            },
        );
    }
    Ok(WebviewStartResponse { process_id, status })
}

pub async fn poll_webview_status(
    state: &AppState,
    process_id: &str,
    member_id: i64,
) -> AppResult<WebviewStatusResponse> {
    require_webview(state)?;
    let logins = logins(state);
    let current = logins.owned_attempt(process_id, member_id, state.clock.now())?;
    if current.stage != Stage::Webview {
        return Err(error(
            SidecarErrorCode::AuthAttemptExpired,
            "BNP Paribas WebView sign-in attempt expired",
        )
        .into());
    }
    let client = SidecarClient::new(state, BANK, SidecarErrorCode::AuthAttemptExpired);
    let http = SidecarHttp::new(state, BANK.service_name(), BANK.label());
    let path = format!("/webview/status/{process_id}");
    let raw = http
        .send::<()>(http::Method::GET, &path, None, WEBVIEW_TIMEOUT)
        .await
        .map_err(|e| client.map_call_error("Could not read BNP Paribas WebView status", e))?;
    if !raw.is_success() {
        let mapped = client.map_status("Could not read BNP Paribas WebView status", &raw, None);
        if raw.status == http::StatusCode::GONE || raw.status == http::StatusCode::NOT_FOUND {
            logins.remove(process_id);
        }
        return Err(mapped.into());
    }
    let bridge: BridgeWebviewStatus = client
        .decode("Could not decode BNP Paribas WebView status", &raw)?
        .ok_or_else(|| {
            client.coded(
                SidecarErrorCode::UpstreamUnavailable,
                "Could not decode BNP Paribas WebView status",
            )
        })?;
    let status = bridge.status.unwrap_or_else(|| "FAILED".to_owned());
    match status.as_str() {
        "WAITING_LOGIN" | "FETCHING" => Ok(WebviewStatusResponse {
            status,
            error_code: None,
            session: None,
        }),
        "CANCELLED" => {
            logins.remove(process_id);
            Ok(WebviewStatusResponse {
                status,
                error_code: None,
                session: None,
            })
        }
        "FAILED" => {
            logins.remove(process_id);
            let error_code = Some(
                BANK.parse(bridge.error_code.as_deref())
                    .unwrap_or(SidecarErrorCode::UpstreamUnavailable),
            );
            Ok(WebviewStatusResponse {
                status,
                error_code,
                session: None,
            })
        }
        "DONE" => {
            let Some(plain_state) = bridge.session_state.filter(|s| clean(Some(s)).is_some())
            else {
                logins.remove(process_id);
                return Err(error(
                    SidecarErrorCode::InvalidData,
                    "BNP Paribas WebView login did not return a session",
                )
                .into());
            };
            let template = template();
            let job = {
                let _write = logins.write.lock().await;
                // Ensure only one concurrent poll persists the session & starts the sync.
                let still_pending = logins.maps().pending.remove(process_id);
                if still_pending.is_none() {
                    let session = get_status(state, member_id).await?;
                    return Ok(WebviewStatusResponse {
                        status: "SYNCING".to_owned(),
                        error_code: None,
                        session: Some(session),
                    });
                }
                logins.require_generation(member_id, current.generation)?;
                let mut tx = state.db.begin().await?;
                let job = template
                    .insert_queued(
                        state,
                        &mut tx.as_handle(),
                        member_id,
                        plain_state,
                        false,
                        |_| Ok(()),
                    )
                    .await?;
                tx.commit().await?;
                job
            };
            let session = get_status(state, member_id).await?;
            template.submit(state, job)?;
            Ok(WebviewStatusResponse {
                status: "SYNCING".to_owned(),
                error_code: None,
                session: Some(session),
            })
        }
        _ => {
            logins.remove(process_id);
            Err(error(
                SidecarErrorCode::UpstreamFormatChanged,
                "BNP Paribas WebView bridge returned an unexpected status",
            )
            .into())
        }
    }
}

pub async fn cancel_webview(
    state: &AppState,
    process_id: &str,
    member_id: i64,
) -> AppResult<WebviewCancelResponse> {
    require_webview(state)?;
    let logins = logins(state);
    let owned = logins
        .maps()
        .pending
        .get(process_id)
        .is_some_and(|p| p.member_id == member_id && p.stage == Stage::Webview);
    if !owned {
        return Ok(WebviewCancelResponse { cancelled: false });
    }
    logins.remove(process_id);
    let http = SidecarHttp::new(state, BANK.service_name(), BANK.label());
    let path = format!("/webview/cancel/{process_id}");
    let _ = http
        .send::<Value>(http::Method::POST, &path, Some(&json!({})), WEBVIEW_TIMEOUT)
        .await;
    Ok(WebviewCancelResponse { cancelled: true })
}

// --- sync ------------------------------------------------------------------------------

pub async fn queue_sync(state: &AppState, member_id: i64) -> AppResult<SessionStatusResponse> {
    let session = template().queue(state, member_id, false).await?;
    Ok(to_status(state, &session))
}

pub struct Strategy;

pub struct Prepared {
    provider: &'static str,
    accounts: Vec<PreparedAccount>,
    unsupported: Vec<UnsupportedProduct>,
}

struct PreparedAccount {
    external_id: String,
    name: String,
    account_type: AccountType,
    balance_eur: Decimal,
    cash_balance: Option<Decimal>,
    invested_amount_eur: Option<Decimal>,
    positions: Vec<PreparedPosition>,
    iban: Option<String>,
    transactions: Vec<SidecarTransaction>,
}

#[derive(Debug, Clone)]
struct PreparedPosition {
    ticker: String,
    name: String,
    quantity: Decimal,
    average_buy_in_eur: Option<Decimal>,
    value_eur: Decimal,
    pnl_eur: Option<Decimal>,
}

#[async_trait]
impl SyncStrategy for Strategy {
    type Prepared = Prepared;

    async fn fetch(&self, state: &AppState, job: &SyncJob) -> JobResult<Prepared> {
        let snapshot: AccountsSnapshot =
            SidecarClient::new(state, BANK, SidecarErrorCode::SessionExpired)
                .post(
                    "/accounts",
                    &json!({ "sessionState": job.plain_state }),
                    ACCOUNTS_TIMEOUT,
                    "Could not fetch BNP Paribas accounts",
                    Some(SidecarErrorCode::SessionExpired),
                )
                .await?;
        let session_state = snapshot.session_state.clone();
        let prepared = prepare(state, snapshot).await?;
        store_refreshed_state(state, job, session_state).await?;
        Ok(prepared)
    }

    async fn commit(
        &self,
        state: &AppState,
        conn: &mut DbHandle<'_>,
        _session: &mut SidecarSession,
        job: &SyncJob,
        prepared: &Prepared,
        synced_at: DateTime<Utc>,
    ) -> JobResult<()> {
        if members::find_by_id(&mut *conn, job.member_id)
            .await?
            .is_none()
        {
            return Err(AppError::NotFound("Family member not found".to_owned()).into());
        }
        let knowledge_base = state.ledger.knowledge_base_on(&mut *conn).await?;
        let context = categorize::load_context_on(
            &mut *conn,
            knowledge_base,
            job.member_id,
            state.clock.now(),
        )
        .await?;
        // Read before the upserts: only what a previous sync imported can be closed.
        let imported =
            accounts::find_all_by_member_and_provider(&mut *conn, job.member_id, prepared.provider)
                .await?;
        for data in &prepared.accounts {
            upsert_account(
                state,
                conn,
                data,
                prepared.provider,
                job.member_id,
                synced_at,
                &context,
            )
            .await?;
        }
        close_unlisted_accounts(state, conn, prepared, imported, job.member_id, synced_at).await?;
        unsupported_by_member(state)
            .map()
            .insert(job.member_id, prepared.unsupported.clone());
        Ok(())
    }

    fn price_needs(&self, prepared: &Prepared) -> PriceNeeds {
        let mut needs = PriceNeeds::default();
        for account in &prepared.accounts {
            needs.account(
                account.account_type,
                "EUR",
                None,
                account.positions.iter().map(|p| p.ticker.as_str()),
            );
        }
        needs
    }

    fn describe(&self, prepared: &Prepared) -> String {
        format!(
            "accounts={}, unsupported={}",
            prepared.accounts.len(),
            prepared.unsupported.len()
        )
    }

    fn redacts_unexpected_failures(&self) -> bool {
        true
    }
}

/// The sidecar rotates the cookie jar on every read: the new one replaces the stored one while
/// the job still owns the session.
async fn store_refreshed_state(
    state: &AppState,
    job: &SyncJob,
    plain_state: Option<String>,
) -> JobResult<()> {
    let Some(plain_state) = plain_state.filter(|s| clean(Some(s)).is_some()) else {
        return Ok(());
    };
    let mut tx = state.db.begin().await?;
    if let Some(mut current) =
        session::find_by_id_for_update(&mut tx, job.session_id, job.member_id).await?
        && current.active
        && current.sync_status == SidecarSyncStatus::Running
    {
        let now = state.clock.now();
        current.update_session_state(state.crypto.encrypt(&plain_state)?, now);
        session::save(&mut tx, &current, now).await?;
    }
    tx.commit().await?;
    Ok(())
}

fn invalid(message: &str) -> SyncError {
    error(SidecarErrorCode::InvalidData, message)
}

async fn prepare(state: &AppState, snapshot: AccountsSnapshot) -> Result<Prepared, SyncError> {
    let raw_accounts = snapshot.accounts.unwrap_or_default();
    if raw_accounts.is_empty() {
        return Err(error(
            SidecarErrorCode::PortfolioIncomplete,
            "BNP Paribas returned no account",
        ));
    }
    let provider = if snapshot.brand.as_deref() == Some("hellobank") {
        HELLO_BANK_PROVIDER
    } else {
        PROVIDER
    };
    let mut external_ids = HashSet::new();
    let mut accounts = Vec::with_capacity(raw_accounts.len());
    for account in raw_accounts {
        if account.snapshot_complete != Some(true) {
            return Err(error(
                SidecarErrorCode::PortfolioIncomplete,
                "BNP Paribas returned an incomplete snapshot",
            ));
        }
        let external_id = stable_external_id(account.external_id.as_deref())?;
        if !external_ids.insert(external_id.clone()) {
            return Err(invalid("BNP Paribas returned duplicate accounts"));
        }
        accounts.push(prepare_account(state, account, external_id).await?);
    }
    let mut unsupported = Vec::new();
    for item in snapshot.unsupported.unwrap_or_default() {
        let (Some(external_id), Some(family_code)) = (
            clean(item.external_id.as_deref()),
            clean(item.family_code.as_deref()),
        ) else {
            return Err(invalid(
                "BNP Paribas returned an invalid unsupported product list",
            ));
        };
        if unsupported.len() >= MAX_UNSUPPORTED {
            tracing::warn!("BNP Paribas unsupported-product list truncated at {MAX_UNSUPPORTED}");
            break;
        }
        unsupported.push(UnsupportedProduct {
            external_id,
            family_code,
        });
    }
    Ok(Prepared {
        provider,
        accounts,
        unsupported,
    })
}

async fn prepare_account(
    state: &AppState,
    account: AccountData,
    external_id: String,
) -> Result<PreparedAccount, SyncError> {
    let account_type = type_of(account.kind.as_deref())?;
    let Some(mut balance) = account.balance else {
        return Err(invalid("BNP Paribas returned an account without a balance"));
    };
    let currency = clean(account.currency.as_deref());
    if !currency.is_some_and(|c| c.eq_ignore_ascii_case("EUR")) {
        return Err(invalid("BNP Paribas returned a non-euro account"));
    }
    if account_type == AccountType::Loan {
        if balance > Decimal::ZERO {
            return Err(invalid(
                "BNP Paribas returned a loan with a positive outstanding",
            ));
        }
        // Picsou stores a loan positive and applies the liability sign itself.
        balance = -balance;
    } else if account_type != AccountType::Checking && balance < Decimal::ZERO {
        return Err(invalid(
            "BNP Paribas returned a negative balance on a savings product",
        ));
    }
    let raw_positions = account.positions.unwrap_or_default();
    let securities = matches!(account_type, AccountType::Pea | AccountType::CompteTitres);
    if !securities && (!raw_positions.is_empty() || account.cash_balance.is_some()) {
        return Err(invalid(
            "BNP Paribas reported securities on another account type",
        ));
    }
    let (mut positions, mut cash, mut invested) = (Vec::new(), None, None);
    if securities {
        let Some(cash_balance) = account.cash_balance.filter(|c| *c >= Decimal::ZERO) else {
            return Err(invalid(
                "BNP Paribas returned no usable cash balance for a securities account",
            ));
        };
        positions = prepare_positions(state, raw_positions).await?;
        let lines: Decimal = positions.iter().map(|p| p.value_eur).sum();
        if !money_close(lines + cash_balance, balance) {
            return Err(error(
                SidecarErrorCode::PortfolioIncomplete,
                "BNP Paribas returned an incomplete portfolio",
            ));
        }
        invested = Some(invested_amount(balance, cash_balance, &positions));
        cash = Some(cash_balance);
    }
    Ok(PreparedAccount {
        external_id,
        name: limit(account.name.as_deref(), 100, "BNP Paribas account"),
        account_type,
        balance_eur: balance,
        cash_balance: cash,
        invested_amount_eur: invested,
        positions,
        iban: verified_iban(account.iban.as_deref()),
        transactions: account.transactions.unwrap_or_default(),
    })
}

fn type_of(kind: Option<&str>) -> Result<AccountType, SyncError> {
    let kind = kind.ok_or_else(|| invalid("BNP Paribas returned an account without a kind"))?;
    Ok(match kind {
        "CURRENT_ACCOUNT" => AccountType::Checking,
        "SAVINGS" => AccountType::Savings,
        "LIVRET_A" => AccountType::LivretA,
        "LDDS" => AccountType::Ldds,
        "LEP" => AccountType::Lep,
        "LIVRET_JEUNE" => AccountType::LivretJeune,
        "PEL" => AccountType::Pel,
        "CEL" => AccountType::Cel,
        "PEA" => AccountType::Pea,
        "COMPTE_TITRES" => AccountType::CompteTitres,
        "ASSURANCE_VIE" => AccountType::AssuranceVie,
        "PER" => AccountType::Per,
        "LOAN" => AccountType::Loan,
        // Never guess: an unknown kind means the format moved under us.
        _ => {
            return Err(error(
                SidecarErrorCode::UpstreamFormatChanged,
                "BNP Paribas returned an unknown account kind",
            ));
        }
    })
}

async fn prepare_positions(
    state: &AppState,
    raw: Vec<Position>,
) -> Result<Vec<PreparedPosition>, SyncError> {
    let mut positions: Vec<PreparedPosition> = Vec::new();
    let mut isin_by_ticker = BTreeMap::new();
    for position in raw {
        let (Some(quantity), Some(value)) = (position.quantity, position.value) else {
            return Err(invalid("BNP Paribas returned an incomplete position"));
        };
        if quantity.is_zero() {
            continue;
        }
        let isin = clean(position.isin.as_deref())
            .map(|i| i.to_uppercase())
            .filter(|i| ISIN.is_match(i))
            .ok_or_else(|| invalid("BNP Paribas returned a line without a valid ISIN"))?;
        let ticker = resolve_ticker(state, &isin).await;
        if positions.iter().any(|p| p.ticker == ticker)
            && isin_by_ticker.get(&ticker) != Some(&isin)
        {
            return Err(invalid(
                "BNP Paribas returned two instruments under one ticker",
            ));
        }
        isin_by_ticker.insert(ticker.clone(), isin);
        let average_buy_in_eur = position
            .pnl
            .map(|pnl| divide(value - pnl, quantity, 8, HALF_UP));
        let line = PreparedPosition {
            name: limit(position.label.as_deref(), 100, &ticker),
            ticker: ticker.clone(),
            quantity,
            average_buy_in_eur,
            value_eur: value,
            pnl_eur: position.pnl,
        };
        match positions.iter_mut().find(|p| p.ticker == ticker) {
            Some(existing) => *existing = merge(existing, &line),
            None => positions.push(line),
        }
    }
    state
        .connectors
        .seams
        .security_identities
        .record(&isin_by_ticker, state.clock.now())
        .await;
    positions.retain(|p| !p.quantity.is_zero());
    Ok(positions)
}

async fn resolve_ticker(state: &AppState, isin: &str) -> String {
    let resolved = state.connectors.seams.isin.resolve(isin).await;
    clean(Some(&resolved.ticker))
        .filter(|t| t.encode_utf16().count() <= MAX_TICKER_LENGTH)
        .unwrap_or_else(|| isin.to_owned())
}

fn merge(left: &PreparedPosition, right: &PreparedPosition) -> PreparedPosition {
    let quantity = left.quantity + right.quantity;
    PreparedPosition {
        ticker: left.ticker.clone(),
        name: left.name.clone(),
        quantity,
        average_buy_in_eur: weighted_average(
            left.average_buy_in_eur,
            left.quantity,
            right.average_buy_in_eur,
            right.quantity,
            quantity,
        ),
        value_eur: left.value_eur + right.value_eur,
        pnl_eur: sum_complete(left.pnl_eur, right.pnl_eur),
    }
}

/// Cash plus each line's cost basis; the balance itself as soon as one line has none.
fn invested_amount(balance: Decimal, cash: Decimal, positions: &[PreparedPosition]) -> Decimal {
    let mut invested = cash;
    for line in positions {
        invested += match (line.average_buy_in_eur, line.pnl_eur) {
            (Some(average), _) => average * line.quantity,
            (None, Some(pnl)) => line.value_eur - pnl,
            (None, None) => return balance,
        };
    }
    invested
}

/// The IBAN is a dedup key: anything that is not a valid IBAN is treated as absent.
fn verified_iban(raw: Option<&str>) -> Option<String> {
    let iban = clean(raw)?;
    let normalized: String = iban
        .chars()
        .filter(|c| !matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r'))
        .collect::<String>()
        .to_uppercase();
    if !IBAN_SHAPE.is_match(&normalized) {
        return None;
    }
    let rearranged = format!("{}{}", &normalized[4..], &normalized[..4]);
    let mut remainder: u32 = 0;
    for c in rearranged.bytes() {
        remainder = if c.is_ascii_digit() {
            (remainder * 10 + u32::from(c - b'0')) % 97
        } else {
            (remainder * 100 + u32::from(c - b'A') + 10) % 97
        };
    }
    (remainder == 1).then_some(normalized)
}

fn snapshot_context(state: &AppState) -> SnapshotContext<'_> {
    SnapshotContext {
        prices: &*state.ledger.prices,
        today: state.clock.today(),
        now: state.clock.now(),
    }
}

async fn upsert_account(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    data: &PreparedAccount,
    provider: &'static str,
    member_id: i64,
    synced_at: DateTime<Utc>,
    context: &CategorizationContext,
) -> JobResult<()> {
    let mut existing =
        accounts::find_by_external_id_and_member(&mut *conn, &data.external_id, member_id).await?;
    if existing.is_none()
        && let Some(iban) = &data.iban
    {
        // One account per IBAN: an Enable Banking link of the same bank is adopted, not duplicated.
        existing = accounts::find_first_by_iban_member_and_providers(
            &mut *conn,
            iban,
            member_id,
            PROVIDER_FAMILY,
        )
        .await?;
    }
    let mut account = match existing {
        Some(account) => account,
        None => {
            let deleted = accounts::exists_soft_deleted_by_external_id(
                &mut *conn,
                &data.external_id,
                member_id,
            )
            .await?
                || match &data.iban {
                    Some(iban) => {
                        accounts::exists_soft_deleted_by_iban(
                            &mut *conn,
                            iban,
                            member_id,
                            None,
                            Some(PROVIDER_FAMILY),
                        )
                        .await?
                    }
                    None => false,
                };
            if deleted {
                tracing::info!("BNP Paribas skipped a soft-deleted account (member={member_id})");
                return Ok(());
            }
            let mut draft = NewAccount::new(member_id, data.name.clone(), data.account_type);
            draft.external_account_id = Some(data.external_id.clone());
            draft.provider = Some(provider.to_owned());
            draft.is_manual = false;
            draft.color = color_for(data.account_type).to_owned();
            accounts::insert(&mut *conn, &draft, state.clock.now()).await?
        }
    };
    account.name = data.name.clone();
    account.account_type = data.account_type;
    account.provider = Some(provider.to_owned());
    account.currency = "EUR".to_owned();
    account.is_manual = false;
    account.external_account_id = Some(data.external_id.clone());
    if let Some(iban) = &data.iban {
        account.iban = Some(iban.clone());
    }
    account.current_balance = data.balance_eur;
    account.cash_balance = data.cash_balance;
    account.last_synced_at = Some(synced_at);
    let saved = accounts::update(&mut *conn, &account, state.clock.now()).await?;
    sidecar_import::import(
        &mut *conn,
        &saved,
        &data.transactions,
        context,
        SOURCE,
        state.clock.now(),
    )
    .await?;
    let drafts: Vec<HoldingDraft> = data
        .positions
        .iter()
        .map(|p| HoldingDraft {
            ticker: p.ticker.clone(),
            name: Some(p.name.clone()),
            quantity: p.quantity,
            average_buy_in: p.average_buy_in_eur,
            provider_value_eur: Some(p.value_eur),
            provider_pnl_eur: p.pnl_eur,
            last_synced_at: Some(synced_at),
            ..HoldingDraft::default()
        })
        .collect();
    holdings::replace_all(&mut *conn, saved.id, &drafts, state.clock.now()).await?;
    let ctx = snapshot_context(state);
    match data.invested_amount_eur {
        Some(invested) => {
            snapshots::upsert(
                &mut *conn,
                saved.id,
                data.balance_eur,
                invested,
                ctx.today,
                ctx.now,
            )
            .await?;
        }
        None => {
            valuation::upsert_snapshot(&mut *conn, &ctx, &saved, data.balance_eur, ctx.today)
                .await?;
        }
    }
    Ok(())
}

/// Zeroes the imported accounts a complete snapshot no longer lists: the customer closed or
/// transferred them (closed at zero, not soft deleted, which would block an account that
/// reopens). Only this snapshot's provider is in scope, so the two brands never close each
/// other, and only ids this connector wrote. A product listed as unsupported is still held and
/// keeps its balance; a capped list may hide one, so nothing is closed then.
async fn close_unlisted_accounts(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    prepared: &Prepared,
    imported: Vec<Account>,
    member_id: i64,
    synced_at: DateTime<Utc>,
) -> JobResult<()> {
    if prepared.unsupported.len() >= MAX_UNSUPPORTED {
        tracing::warn!(
            "BNP Paribas unsupported-product list is capped, keeping every unlisted account (member={member_id})"
        );
        return Ok(());
    }
    let mut kept: HashSet<String> = prepared
        .accounts
        .iter()
        .map(|a| a.external_id.clone())
        .collect();
    kept.extend(
        prepared
            .unsupported
            .iter()
            .map(|p| prefixed(&p.external_id)),
    );
    for mut account in imported {
        let in_scope = account
            .external_account_id
            .as_deref()
            .is_some_and(|id| id.starts_with(EXTERNAL_ID_PREFIX) && !kept.contains(id));
        if !in_scope || account.current_balance.is_zero() {
            continue;
        }
        tracing::info!(
            "BNP Paribas no longer lists an imported account, closing it at zero (member={member_id}; account={})",
            account.id
        );
        account.current_balance = Decimal::ZERO;
        account.cash_balance = None;
        account.last_synced_at = Some(synced_at);
        let saved = accounts::update(&mut *conn, &account, state.clock.now()).await?;
        holdings::delete_by_account(&mut *conn, saved.id).await?;
        snapshots::upsert(
            &mut *conn,
            saved.id,
            Decimal::ZERO,
            Decimal::ZERO,
            state.clock.today(),
            state.clock.now(),
        )
        .await?;
    }
    Ok(())
}

fn color_for(account_type: AccountType) -> &'static str {
    match account_type {
        AccountType::Pea => "#10b981",
        AccountType::CompteTitres => "#3b82f6",
        AccountType::AssuranceVie | AccountType::Per => "#8b5cf6",
        AccountType::Savings
        | AccountType::Lep
        | AccountType::LivretA
        | AccountType::Ldds
        | AccountType::LivretJeune
        | AccountType::Pel
        | AccountType::Cel => "#f59e0b",
        AccountType::Loan => "#64748b",
        _ => "#16a34a",
    }
}

fn stable_external_id(raw: Option<&str>) -> Result<String, SyncError> {
    clean(raw)
        .map(|cleaned| prefixed(&cleaned))
        .filter(|id| id.encode_utf16().count() <= MAX_EXTERNAL_ID_LENGTH)
        .ok_or_else(|| invalid("BNP Paribas returned an invalid account identifier"))
}

/// The form an id is stored under, shared by accounts and unsupported products.
fn prefixed(cleaned: &str) -> String {
    if cleaned.starts_with(EXTERNAL_ID_PREFIX) {
        cleaned.to_owned()
    } else {
        format!("{EXTERNAL_ID_PREFIX}{cleaned}")
    }
}

// --- status ----------------------------------------------------------------------------

pub async fn get_status(state: &AppState, member_id: i64) -> AppResult<SessionStatusResponse> {
    let webview = webview_supported(state).then_some(true);
    Ok(template().find(state, member_id).await?.map_or_else(
        || SessionStatusResponse::inactive(webview),
        |s| to_status(state, &s),
    ))
}

/// Forgets the stored session in Picsou only and cancels any login in flight.
pub async fn clear_session(state: &AppState, member_id: i64) -> AppResult<bool> {
    let logins = logins(state);
    {
        let _write = logins.write.lock().await;
        let mut maps = logins.maps();
        *maps.generations.entry(member_id).or_insert(0) += 1;
        maps.pending.retain(|_, p| p.member_id != member_id);
    }
    unsupported_by_member(state).map().remove(&member_id);
    template().clear(state, member_id).await
}

pub async fn recover_interrupted_syncs(state: &AppState) -> sqlx::Result<u64> {
    template().recover_interrupted(state).await
}

/// The daily scheduler's entry (track T8).
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    template()
        .resync_reporting(member_id, SOURCE, get_status(state, member_id), || async {
            queue_sync(state, member_id).await.map(|_| ())
        })
        .await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn verified_iban_accepts_only_a_valid_checksum() {
        assert_eq!(
            verified_iban(Some(" fr76 3000 6000 0112 3456 7890 189 ")).as_deref(),
            Some("FR7630006000011234567890189")
        );
        assert_eq!(verified_iban(Some("FR7630006000011234567890188")), None);
        assert_eq!(verified_iban(Some("not an iban")), None);
        assert_eq!(verified_iban(None), None);
    }
}
