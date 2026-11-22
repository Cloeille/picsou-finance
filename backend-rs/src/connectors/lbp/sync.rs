//! LbpSyncService and LbpAdapter: the keypad login (identifier, clicked positions, approval in
//! the bank's mobile app), the queued import and its all-or-nothing write. No password ever
//! reaches Picsou and the sync is on demand: an expired session is renewed by the user only.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use super::session::{self, LbpSession};
use crate::AppState;
use crate::connectors::sidecar::jobs::Pool;
use crate::connectors::sidecar::keypad::{Keypad, RawKeypad, safe_keypad};
use crate::connectors::sidecar::{
    JobError, JobResult, SidecarClient, SidecarSessionState, SidecarSyncStatus, SyncError,
};
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::model::{Account, AccountType, CardNature, NewTransaction};
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode, SourceSyncResult, SyncStatus};
use crate::ledger::sync_values::{clean, java_is_blank, limit};
use crate::ledger::valuation::{self, SnapshotContext};
use crate::ledger::{members, transactions};

const BANK: SidecarBank = SidecarBank::Lbp;
pub const PROVIDER: &str = "La Banque Postale";
const SOURCE: &str = "lbp";
const EXTERNAL_ID_PREFIX: &str = "lbp_";
const MAX_EXTERNAL_ID_LENGTH: usize = 100;
const MAX_UNSUPPORTED: usize = 100;
/// How long the user has to approve on the phone once the keypad was submitted.
const APPROVAL_WINDOW: chrono::Duration = chrono::Duration::seconds(180);
const INITIATE_TIMEOUT: Duration = Duration::from_secs(120);
/// The sidecar gives /keypad 60 s (clicks, Valider, the mobile-approval page): stay above it.
const KEYPAD_TIMEOUT: Duration = Duration::from_secs(70);
/// The sidecar waits up to 150 s for the human to approve on the phone: stay above it.
const COMPLETE_TIMEOUT: Duration = Duration::from_secs(170);
const ACCOUNTS_TIMEOUT: Duration = Duration::from_secs(120);
const ATTEMPT_EXPIRED: &str = "This La Banque Postale sign-in attempt expired. Please start again.";

// --- pending logins ---------------------------------------------------------------

/// A login in flight: only one deadline is set, the keypad's while the user clicks, the approval
/// window's once the digits were sent. Holds the identifier only when the user asked to remember
/// it, never the password.
#[derive(Clone)]
struct PendingLogin {
    member_id: i64,
    keypad_expires_at: Option<DateTime<Utc>>,
    approval_expires_at: Option<DateTime<Utc>>,
    customer_id: Option<String>,
    remember_identifier: bool,
    generation: u64,
}

impl PendingLogin {
    fn deadline(&self) -> Option<DateTime<Utc>> {
        self.approval_expires_at.or(self.keypad_expires_at)
    }
}

#[derive(Default)]
struct LoginMaps {
    pending: HashMap<String, PendingLogin>,
    /// Bumped by a disconnect: a login waiting on the bank meanwhile stores nothing.
    generations: HashMap<i64, u64>,
}

/// Process-local on purpose: a restart drops the attempt and the user starts again.
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

    /// Drops the logins nobody can complete any more (evictExpiredPendingLogins).
    pub fn evict_expired(&self, now: DateTime<Utc>) {
        self.maps()
            .pending
            .retain(|_, p| p.deadline().is_some_and(|d| d > now));
    }

    /// Removes a pending login for its owner; anyone else probing it burns nothing.
    fn take(&self, process_id: &str, member_id: i64) -> Result<PendingLogin, SyncError> {
        let mut maps = self.maps();
        match maps.pending.get(process_id) {
            Some(pending) if pending.member_id == member_id => {}
            _ => return Err(expired()),
        }
        Ok(maps.pending.remove(process_id).expect("checked above"))
    }
}

fn logins(state: &AppState) -> Arc<Logins> {
    state.connectors.jobs.local::<Logins>("lbp-logins")
}

/// The scheduled sweep of abandoned keypad logins, for track T8 (every minute in Java).
pub fn evict_expired_pending_logins(state: &AppState) {
    logins(state).evict_expired(state.clock.now());
}

// --- responses ---------------------------------------------------------------------

/// The pending login to show: the bank's keypad and its lifetime.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InitiateResponse {
    pub process_id: String,
    pub keypad: Keypad,
    pub expires_in_seconds: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct UnsupportedProduct {
    pub external_id: String,
    pub family_code: String,
}

/// LbpSyncService.SessionStatusResponse.
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
    /// The identifier the user asked to remember, decrypted for the form. Never a password.
    pub saved_identifier: Option<String>,
}

impl SessionStatusResponse {
    fn inactive() -> Self {
        Self {
            is_active: false,
            sync_status: SidecarSyncStatus::Idle,
            last_sync_started_at: None,
            last_sync_completed_at: None,
            last_sync_error: None,
            unsupported: Vec::new(),
            unsupported_count: 0,
            saved_identifier: None,
        }
    }
}

fn to_status(state: &AppState, session: &LbpSession) -> SessionStatusResponse {
    let unsupported = parse_unsupported(session.unsupported_products.as_deref());
    SessionStatusResponse {
        is_active: session.active,
        sync_status: session.sync_status,
        last_sync_started_at: session.last_sync_started_at,
        last_sync_completed_at: session.last_sync_completed_at,
        last_sync_error: session.last_sync_error,
        unsupported_count: unsupported.len(),
        unsupported,
        saved_identifier: saved_identifier(state, session),
    }
}

/// An unreadable remembered identifier simply behaves as absent.
fn saved_identifier(state: &AppState, session: &LbpSession) -> Option<String> {
    let encrypted = session.encrypted_identifier.as_deref()?;
    match state.crypto.decrypt(encrypted) {
        Ok(identifier) => Some(identifier),
        Err(_) => {
            tracing::warn!("Stored La Banque Postale identifier is unreadable");
            None
        }
    }
}

fn parse_unsupported(json: Option<&str>) -> Vec<UnsupportedProduct> {
    let Some(json) = json.filter(|j| !java_is_blank(j)) else {
        return Vec::new();
    };
    serde_json::from_str(json).unwrap_or_else(|_| {
        tracing::warn!("Stored La Banque Postale unsupported-product list is unreadable");
        Vec::new()
    })
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
struct AccountsSnapshot {
    accounts: Option<Vec<AccountData>>,
    unsupported: Option<Vec<Unsupported>>,
}

/// Money comes as the sidecar's decimal strings. A card's balance is its outstanding (zero or
/// negative). The IBAN is carried for diagnosis only and never read here.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AccountData {
    external_id: Option<String>,
    kind: Option<String>,
    name: Option<String>,
    balance: Option<Decimal>,
    currency: Option<String>,
    card_nature: Option<String>,
    next_due_date: Option<NaiveDate>,
    transactions: Option<Vec<TransactionData>>,
    snapshot_complete: Option<bool>,
}

/// `amount` is signed: negative is an outflow.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TransactionData {
    external_id: Option<String>,
    date: Option<NaiveDate>,
    amount: Option<Decimal>,
    currency: Option<String>,
    label: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Unsupported {
    external_id: Option<String>,
    family_code: Option<String>,
}

fn error(code: SidecarErrorCode, message: &str) -> SyncError {
    SyncError::new(code, message)
}

fn expired() -> SyncError {
    error(SidecarErrorCode::AuthAttemptExpired, ATTEMPT_EXPIRED)
}

/// The code with the bank's own wording.
fn declared(code: SidecarErrorCode, fallback: &str) -> SyncError {
    error(code, BANK.message(code).unwrap_or(fallback))
}

/// The statuses only the login contract defines (408, 409, 422, 429) are resolved here,
/// everything else goes through the shared client. One request, never retried: a replayed login
/// step would spend a bank attempt. No body is ever logged or put in a message.
#[allow(clippy::too_many_arguments)]
async fn auth_post<T: DeserializeOwned>(
    state: &AppState,
    path: &str,
    body: &Value,
    timeout: Duration,
    message: &str,
    authentication_failure: SidecarErrorCode,
    timeout_code: SidecarErrorCode,
    unprocessable_code: Option<SidecarErrorCode>,
) -> Result<T, SyncError> {
    let client = SidecarClient::new(state, BANK, SidecarErrorCode::AuthAttemptExpired);
    let response = client
        .post_raw(path, body, timeout)
        .await
        .map_err(|e| client.map_call_error(message, e))?;
    if !response.is_success() {
        return Err(match (response.status.as_u16(), unprocessable_code) {
            (408, _) => declared(timeout_code, message),
            (422, Some(code)) => declared(code, message),
            (409, _) => declared(SidecarErrorCode::KeypadChanged, message),
            (429, _) => error(
                SidecarErrorCode::UpstreamUnavailable,
                "Too many La Banque Postale logins are pending. Please wait a few minutes.",
            ),
            _ => client.map_status(message, &response, Some(authentication_failure)),
        });
    }
    client
        .decode(message, &response)?
        .ok_or_else(|| client.coded(SidecarErrorCode::UpstreamUnavailable, message))
}

async fn fetch_accounts(
    state: &AppState,
    plain_state: &str,
) -> Result<AccountsSnapshot, SyncError> {
    SidecarClient::new(state, BANK, SidecarErrorCode::SessionExpired)
        .post(
            "/accounts",
            &json!({ "sessionState": plain_state }),
            ACCOUNTS_TIMEOUT,
            "Could not fetch La Banque Postale accounts",
            Some(SidecarErrorCode::SessionExpired),
        )
        .await
}

// --- login ---------------------------------------------------------------------------

/// Starts a login: one sidecar call, no retry. A failure leaves any stored session as it was.
pub async fn initiate_auth(
    state: &AppState,
    customer_id: &str,
    remember_identifier: bool,
    member_id: i64,
) -> AppResult<InitiateResponse> {
    let logins = logins(state);
    // Captured before the call: a disconnect during the browser launch cancels this attempt.
    let generation = logins.generation(member_id);
    let result: InitiateResult = auth_post(
        state,
        "/initiate",
        &json!({ "customerId": customer_id }),
        INITIATE_TIMEOUT,
        "Could not start the La Banque Postale login",
        SidecarErrorCode::InvalidCredentials,
        SidecarErrorCode::AppValidationTimeout,
        None,
    )
    .await?;
    let challenge = match (result.process_id, result.keypad, result.expires_in_seconds) {
        (Some(process_id), Some(keypad), Some(expires))
            if !java_is_blank(&process_id) && expires > 0 =>
        {
            Some((process_id, keypad, expires))
        }
        _ => None,
    };
    let Some((process_id, raw_keypad, expires_in_seconds)) = challenge else {
        return Err(error(
            SidecarErrorCode::UpstreamFormatChanged,
            "La Banque Postale did not return a usable sign-in challenge",
        )
        .into());
    };
    let keypad = safe_keypad(&raw_keypad).ok_or_else(|| {
        error(
            SidecarErrorCode::KeypadChanged,
            "La Banque Postale changed its login page. Nothing was sent.",
        )
    })?;
    let now = state.clock.now();
    {
        let _write = logins.write.lock().await;
        if generation != logins.generation(member_id) {
            return Err(expired().into());
        }
        logins.evict_expired(now);
        logins.maps().pending.insert(
            process_id.clone(),
            PendingLogin {
                member_id,
                keypad_expires_at: Some(
                    now + chrono::Duration::seconds(i64::from(expires_in_seconds)),
                ),
                approval_expires_at: None,
                customer_id: remember_identifier.then(|| customer_id.to_owned()),
                remember_identifier,
                generation,
            },
        );
    }
    Ok(InitiateResponse {
        process_id,
        keypad,
        expires_in_seconds,
    })
}

/// Clicks the digits the user chose. Single use: the attempt is taken out before the sidecar is
/// called, so a failure is never replayed. The positions are the password in disguise and are
/// never logged.
pub async fn submit_keypad(
    state: &AppState,
    process_id: &str,
    positions: &[u8],
    member_id: i64,
) -> AppResult<()> {
    let logins = logins(state);
    let pending = {
        let mut maps = logins.maps();
        match maps.pending.get(process_id) {
            Some(p) if p.member_id == member_id && p.approval_expires_at.is_none() => {}
            // Already sent, or someone else's: the approval entry stays for /complete.
            _ => return Err(expired().into()),
        }
        maps.pending.remove(process_id).expect("checked above")
    };
    if pending
        .keypad_expires_at
        .is_none_or(|deadline| deadline <= state.clock.now())
    {
        return Err(error(
            SidecarErrorCode::KeypadExpired,
            "The La Banque Postale keypad expired. Start again.",
        )
        .into());
    }
    let ack: KeypadAck = auth_post(
        state,
        "/keypad",
        &json!({ "processId": process_id, "positions": positions }),
        KEYPAD_TIMEOUT,
        "Could not send the La Banque Postale keypad choice",
        SidecarErrorCode::InvalidCredentials,
        SidecarErrorCode::KeypadExpired,
        Some(SidecarErrorCode::InvalidPositions),
    )
    .await?;
    if ack.status.as_deref() != Some("APPROVAL_PENDING") {
        return Err(declared(
            SidecarErrorCode::UpstreamFormatChanged,
            "Could not send the La Banque Postale keypad choice",
        )
        .into());
    }
    logins.maps().pending.insert(
        process_id.to_owned(),
        PendingLogin {
            keypad_expires_at: None,
            approval_expires_at: Some(state.clock.now() + APPROVAL_WINDOW),
            ..pending
        },
    );
    Ok(())
}

/// Finishes a login the user approved in the bank's mobile app, then stores the encrypted
/// session. It does not queue a sync: the user triggers it.
pub async fn complete_auth(
    state: &AppState,
    process_id: &str,
    member_id: i64,
) -> AppResult<SessionStatusResponse> {
    let logins = logins(state);
    let pending = logins.take(process_id, member_id)?;
    if pending
        .approval_expires_at
        .is_none_or(|deadline| deadline <= state.clock.now())
        || pending.generation != logins.generation(member_id)
    {
        return Err(expired().into());
    }
    let response: SidecarSessionState = auth_post(
        state,
        "/complete",
        &json!({ "processId": process_id }),
        COMPLETE_TIMEOUT,
        "Could not finish the La Banque Postale login",
        SidecarErrorCode::InvalidCredentials,
        SidecarErrorCode::AppValidationTimeout,
        None,
    )
    .await?;
    let Some(plain_state) = response.session_state.filter(|s| !java_is_blank(s)) else {
        return Err(error(
            SidecarErrorCode::UpstreamFormatChanged,
            "La Banque Postale did not return a session",
        )
        .into());
    };
    let identifier = pending
        .customer_id
        .filter(|id| pending.remember_identifier && !java_is_blank(id));
    store_session(
        state,
        &plain_state,
        member_id,
        identifier.as_deref(),
        pending.generation,
    )
    .await
}

/// Replaces the member's session, unless a disconnect bumped the generation meanwhile.
async fn store_session(
    state: &AppState,
    plain_state: &str,
    member_id: i64,
    identifier: Option<&str>,
    generation: u64,
) -> AppResult<SessionStatusResponse> {
    let encrypted_identifier = identifier.map(|id| state.crypto.encrypt(id)).transpose()?;
    let encrypted_state = state.crypto.encrypt(plain_state)?;
    let logins = logins(state);
    {
        let _write = logins.write.lock().await;
        if generation != logins.generation(member_id) {
            return Err(expired().into());
        }
        let mut tx = state.db.begin().await?;
        if members::find_by_id(&mut tx, member_id).await?.is_none() {
            return Err(AppError::NotFound("Family member not found".to_owned()));
        }
        if let Some(existing) = session::find_by_member_for_update(&mut tx, member_id).await? {
            session::delete(&mut tx, existing.id).await?;
        }
        session::insert(
            &mut tx,
            member_id,
            &encrypted_state,
            encrypted_identifier.as_deref(),
            state.clock.now(),
        )
        .await?;
        tx.commit().await?;
    }
    get_status(state, member_id).await
}

// --- sync ------------------------------------------------------------------------------

#[derive(Clone)]
struct SyncJob {
    session_id: i64,
    member_id: i64,
    plain_state: String,
}

/// Queues an import of the stored session; one already in flight is left alone.
pub async fn queue_sync(state: &AppState, member_id: i64) -> AppResult<SessionStatusResponse> {
    let mut tx = state.db.begin().await?;
    let mut current = session::find_by_member_for_update(&mut tx, member_id)
        .await?
        .ok_or_else(|| {
            error(
                SidecarErrorCode::SessionExpired,
                "No active La Banque Postale session. Please reconnect.",
            )
        })?;
    if !current.active {
        return Err(error(
            SidecarErrorCode::SessionExpired,
            "The La Banque Postale session expired. Please reconnect.",
        )
        .into());
    }
    if current.is_sync_in_flight() {
        return Ok(to_status(state, &current));
    }
    let now = state.clock.now();
    let Ok(plain_state) = state.crypto.decrypt(&current.session_state) else {
        // Unreadable (wrong key, corrupted): the session is dead, not a server fault.
        current.mark_queued()?;
        current.mark_failed(SidecarErrorCode::InvalidSessionState, now)?;
        session::save(&mut tx, &current, now).await?;
        tx.commit().await?;
        tracing::warn!(
            "Stored La Banque Postale session is unreadable (member={member_id}; type=CryptoError)"
        );
        return Err(error(
            SidecarErrorCode::InvalidSessionState,
            "The stored La Banque Postale session is unusable. Please reconnect.",
        )
        .into());
    };
    current.mark_queued()?;
    session::save(&mut tx, &current, now).await?;
    tx.commit().await?;
    submit(
        state,
        SyncJob {
            session_id: current.id,
            member_id,
            plain_state,
        },
    )
    .await?;
    get_status(state, member_id).await
}

async fn submit(state: &AppState, job: SyncJob) -> AppResult<()> {
    let task_state = state.clone();
    let task_job = job.clone();
    let submitted = state
        .connectors
        .jobs
        .submit(BANK.as_str(), Pool::SINGLE_WORKER, async move {
            execute(&task_state, task_job).await
        });
    if submitted.is_err() {
        mark_failed(state, &job, SidecarErrorCode::InternalError).await;
        return Err(error(
            SidecarErrorCode::InternalError,
            "Could not schedule the La Banque Postale synchronization",
        )
        .into());
    }
    Ok(())
}

fn kind(error: &JobError) -> &'static str {
    match error {
        JobError::Sync(_) => "SyncException",
        JobError::Db(_) => "DataAccessException",
        JobError::App(_) => "RuntimeException",
    }
}

async fn execute(state: &AppState, job: SyncJob) {
    let member_id = job.member_id;
    match mark_running(state, &job).await {
        Ok(true) => {}
        Ok(false) => return,
        Err(e) => {
            // A failed status update must not leave the session stuck QUEUED.
            tracing::error!(
                "La Banque Postale sync could not start (member={member_id}; type={})",
                kind(&e)
            );
            mark_failed(state, &job, SidecarErrorCode::InternalError).await;
            return;
        }
    }
    let outcome = async {
        let fetched = fetch_accounts(state, &job.plain_state).await?;
        let prepared = prepare(fetched)?;
        commit(state, &job, &prepared).await
    }
    .await;
    match outcome {
        Ok(Some(accounts)) => tracing::info!(
            "La Banque Postale sync completed (member={member_id}; accounts={accounts})"
        ),
        Ok(None) => {
            tracing::info!("Discarded stale La Banque Postale sync result (member={member_id})");
        }
        Err(JobError::Sync(sync)) => {
            let code = sync
                .code()
                .and_then(|c| c.parse().ok())
                .unwrap_or(SidecarErrorCode::UpstreamUnavailable);
            mark_failed(state, &job, code).await;
            tracing::warn!("La Banque Postale sync failed (member={member_id}; code={code})");
        }
        Err(other) => {
            mark_failed(state, &job, SidecarErrorCode::InternalError).await;
            // The type only: the message could carry a value from the payload.
            tracing::error!(
                "La Banque Postale sync failed unexpectedly (member={member_id}; type={})",
                kind(&other)
            );
        }
    }
}

async fn mark_running(state: &AppState, job: &SyncJob) -> JobResult<bool> {
    let mut tx = state.db.begin().await?;
    let Some(mut current) =
        session::find_by_id_and_member_for_update(&mut tx, job.session_id, job.member_id).await?
    else {
        tracing::info!(
            "La Banque Postale sync session disappeared before execution (member={})",
            job.member_id
        );
        return Ok(false);
    };
    if !current.active || current.sync_status != SidecarSyncStatus::Queued {
        tracing::warn!(
            "La Banque Postale sync cannot start from state {} (member={}; active={})",
            current.sync_status,
            job.member_id,
            current.active
        );
        return Ok(false);
    }
    let now = state.clock.now();
    current.mark_running(now)?;
    session::save(&mut tx, &current, now).await?;
    tx.commit().await?;
    Ok(true)
}

async fn mark_failed(state: &AppState, job: &SyncJob, code: SidecarErrorCode) {
    let outcome = async {
        let mut tx = state.db.begin().await?;
        let Some(mut current) =
            session::find_by_id_and_member_for_update(&mut tx, job.session_id, job.member_id)
                .await?
        else {
            tracing::info!(
                "La Banque Postale sync session disappeared before failure was recorded (member={})",
                job.member_id
            );
            return Ok(());
        };
        if !current.is_sync_in_flight() {
            tracing::warn!(
                "La Banque Postale sync failure ignored from state {} (member={}; code={code})",
                current.sync_status,
                job.member_id
            );
            return Ok(());
        }
        let now = state.clock.now();
        current.mark_failed(code, now)?;
        session::save(&mut tx, &current, now).await?;
        tx.commit().await?;
        Ok::<_, JobError>(())
    }
    .await;
    if outcome.is_err() {
        tracing::error!(
            "Could not persist La Banque Postale sync failure (member={}; code={code})",
            job.member_id
        );
    }
}

struct Prepared {
    accounts: Vec<PreparedAccount>,
    unsupported: Vec<UnsupportedProduct>,
}

struct PreparedAccount {
    external_id: String,
    name: String,
    account_type: AccountType,
    balance: Decimal,
    card_nature: Option<CardNature>,
    next_due_date: Option<NaiveDate>,
    transactions: Vec<PreparedTransaction>,
}

struct PreparedTransaction {
    external_id: String,
    date: NaiveDate,
    amount: Decimal,
    label: String,
}

fn invalid(what: &str) -> SyncError {
    error(
        SidecarErrorCode::UpstreamFormatChanged,
        &format!("La Banque Postale returned {what}"),
    )
}

/// Validates the whole snapshot before anything is written: one doubtful account refuses the
/// lot, so a partial list never overwrites a correct import.
fn prepare(fetched: AccountsSnapshot) -> Result<Prepared, SyncError> {
    let raw_accounts = fetched.accounts.unwrap_or_default();
    if raw_accounts.is_empty() {
        return Err(invalid("no account"));
    }
    let mut external_ids = HashSet::new();
    let mut accounts = Vec::with_capacity(raw_accounts.len());
    for account in raw_accounts {
        if account.snapshot_complete != Some(true) {
            return Err(invalid("an incomplete snapshot"));
        }
        let external_id = prefixed(account.external_id.as_deref())?;
        if !external_ids.insert(external_id.clone()) {
            return Err(invalid("duplicate accounts"));
        }
        let account_type = type_of(account.kind.as_deref())?;
        let Some(balance) = account.balance else {
            return Err(invalid("an account without a balance"));
        };
        if account.currency.as_deref() != Some("EUR") {
            return Err(invalid("an account that is not in EUR"));
        }
        if account_type == AccountType::CreditCard && balance > Decimal::ZERO {
            // A card balance is its outstanding: money owed, never a credit.
            return Err(invalid("a card with a positive outstanding"));
        }
        let card_nature = if account_type == AccountType::CreditCard {
            nature_of(account.card_nature.as_deref())?
        } else {
            None
        };
        accounts.push(PreparedAccount {
            external_id,
            name: limit(account.name.as_deref(), 100, fallback_name(account_type)),
            account_type,
            balance,
            card_nature,
            next_due_date: account.next_due_date,
            transactions: prepare_transactions(account.transactions.unwrap_or_default())?,
        });
    }
    let mut unsupported = Vec::new();
    for item in fetched.unsupported.unwrap_or_default() {
        let (Some(external_id), Some(family_code)) = (
            clean(item.external_id.as_deref()),
            clean(item.family_code.as_deref()),
        ) else {
            return Err(invalid("an invalid unsupported product list"));
        };
        if unsupported.len() >= MAX_UNSUPPORTED {
            // Informational only: a long list must not fail the import.
            tracing::warn!(
                "La Banque Postale unsupported-product list truncated at {MAX_UNSUPPORTED}"
            );
            break;
        }
        unsupported.push(UnsupportedProduct {
            external_id,
            family_code,
        });
    }
    Ok(Prepared {
        accounts,
        unsupported,
    })
}

/// A repeated id inside one account keeps its first place and its last values, as the next sync
/// would resolve it.
fn prepare_transactions(raw: Vec<TransactionData>) -> Result<Vec<PreparedTransaction>, SyncError> {
    let mut prepared: Vec<PreparedTransaction> = Vec::with_capacity(raw.len());
    let mut index_of: HashMap<String, usize> = HashMap::new();
    for tx in raw {
        let (Some(date), Some(amount)) = (tx.date, tx.amount) else {
            return Err(invalid("an incomplete transaction"));
        };
        if tx.currency.as_deref() != Some("EUR") {
            return Err(invalid("a transaction that is not in EUR"));
        }
        let line = PreparedTransaction {
            external_id: prefixed(tx.external_id.as_deref())?,
            date,
            amount,
            label: limit(tx.label.as_deref(), 255, "La Banque Postale transaction"),
        };
        match index_of.get(&line.external_id) {
            Some(&index) => prepared[index] = line,
            None => {
                index_of.insert(line.external_id.clone(), prepared.len());
                prepared.push(line);
            }
        }
    }
    Ok(prepared)
}

async fn commit(state: &AppState, job: &SyncJob, prepared: &Prepared) -> JobResult<Option<usize>> {
    let mut tx = state.db.begin().await?;
    let Some(mut current) =
        session::find_by_id_and_member_for_update(&mut tx, job.session_id, job.member_id).await?
    else {
        tracing::info!(
            "La Banque Postale sync session disappeared before commit (member={})",
            job.member_id
        );
        return Ok(None);
    };
    if !current.active {
        tracing::warn!(
            "La Banque Postale sync session became inactive before commit (member={})",
            job.member_id
        );
        return Ok(None);
    }
    if current.sync_status != SidecarSyncStatus::Running {
        tracing::warn!(
            "La Banque Postale sync cannot commit from state {} (member={})",
            current.sync_status,
            job.member_id
        );
        return Ok(None);
    }
    if members::find_by_id(&mut tx, job.member_id).await?.is_none() {
        return Err(AppError::NotFound("Family member not found".to_owned()).into());
    }
    let synced_at = state.clock.now();
    // Read before the upserts: only what a previous sync imported can be closed.
    let imported =
        accounts::find_all_by_member_and_provider(&mut tx, job.member_id, PROVIDER).await?;
    for data in &prepared.accounts {
        upsert_account(state, &mut tx.as_handle(), data, job.member_id, synced_at).await?;
    }
    close_unlisted_accounts(
        state,
        &mut tx.as_handle(),
        &prepared.accounts,
        imported,
        job.member_id,
        synced_at,
    )
    .await?;
    current.unsupported_products =
        Some(serde_json::to_string(&prepared.unsupported).map_err(AppError::internal)?);
    current.mark_successful(synced_at)?;
    session::save(&mut tx, &current, synced_at).await?;
    tx.commit().await?;
    Ok(Some(prepared.accounts.len()))
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
    member_id: i64,
    synced_at: DateTime<Utc>,
) -> JobResult<()> {
    let existing =
        accounts::find_by_external_id_and_member(&mut *conn, &data.external_id, member_id).await?;
    let mut account = match existing {
        Some(account) => account,
        None => {
            if accounts::exists_soft_deleted_by_external_id(
                &mut *conn,
                &data.external_id,
                member_id,
            )
            .await?
            {
                tracing::info!(
                    "La Banque Postale skipped a soft-deleted account (member={member_id})"
                );
                return Ok(());
            }
            let mut draft = NewAccount::new(member_id, data.name.clone(), data.account_type);
            draft.external_account_id = Some(data.external_id.clone());
            draft.provider = Some(PROVIDER.to_owned());
            draft.is_manual = false;
            draft.color = color_for(data.account_type).to_owned();
            accounts::insert(&mut *conn, &draft, state.clock.now()).await?
        }
    };
    account.name = data.name.clone();
    account.account_type = data.account_type;
    account.provider = Some(PROVIDER.to_owned());
    account.currency = "EUR".to_owned();
    account.is_manual = false;
    account.current_balance = data.balance;
    account.last_synced_at = Some(synced_at);
    if let Some(nature) = data.card_nature {
        // Same shape as the Amex card: what is still to be debited, and when. The card is never
        // given a parent: the dashboard skips any account with one.
        account.card_nature = Some(nature);
        account.payment_due_amount = Some(data.balance.abs());
        account.payment_due_date = data.next_due_date;
    }
    // The IBAN is deliberately not stored: the Enable Banking sync would adopt these accounts.
    let saved = accounts::update(&mut *conn, &account, state.clock.now()).await?;
    let ctx = snapshot_context(state);
    valuation::upsert_snapshot(&mut *conn, &ctx, &saved, data.balance, ctx.today).await?;
    // Regulated livrets get a usable savings configuration on first import.
    state
        .connectors
        .seams
        .savings
        .configure_if_missing(&mut *conn, saved.id, data.account_type)
        .await?;
    upsert_transactions(state, conn, &saved, &data.transactions).await?;
    Ok(())
}

/// An account a complete snapshot no longer lists (a closed livret) is closed at zero rather
/// than soft deleted: a soft-deleted account reads as removed by the user and would never be
/// imported again, while a bank-closed account can come back. Only a complete read gets here.
async fn close_unlisted_accounts(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    prepared: &[PreparedAccount],
    imported: Vec<Account>,
    member_id: i64,
    synced_at: DateTime<Utc>,
) -> JobResult<()> {
    let listed: HashSet<&str> = prepared.iter().map(|a| a.external_id.as_str()).collect();
    for mut account in imported {
        let in_scope = account
            .external_account_id
            .as_deref()
            .is_some_and(|id| id.starts_with(EXTERNAL_ID_PREFIX) && !listed.contains(id));
        if !in_scope || account.current_balance.is_zero() {
            continue;
        }
        tracing::info!(
            "La Banque Postale no longer lists an imported account, closing it at zero (member={member_id}; account={})",
            account.id
        );
        account.current_balance = Decimal::ZERO;
        if account.account_type == AccountType::CreditCard {
            // No outstanding left on a closed card.
            account.payment_due_amount = Some(Decimal::ZERO);
            account.payment_due_date = None;
        }
        account.last_synced_at = Some(synced_at);
        let saved = accounts::update(&mut *conn, &account, state.clock.now()).await?;
        let ctx = snapshot_context(state);
        valuation::upsert_snapshot(&mut *conn, &ctx, &saved, Decimal::ZERO, ctx.today).await?;
    }
    Ok(())
}

/// Upsert by (account, external id); rows are never deleted, manual rows never touched.
async fn upsert_transactions(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    account: &Account,
    lines: &[PreparedTransaction],
) -> JobResult<()> {
    if lines.is_empty() {
        return Ok(());
    }
    let mut stored = HashMap::new();
    for row in transactions::find_synced_by_account(&mut *conn, account.id, None).await? {
        if let Some(external_id) = row.external_id.clone() {
            stored.insert(external_id, row);
        }
    }
    for line in lines {
        match stored.get(&line.external_id) {
            Some(row) => {
                let mut row = row.clone();
                row.date = line.date;
                row.description = line.label.clone();
                row.amount = line.amount;
                transactions::update(&mut *conn, &row).await?;
            }
            None => {
                let mut row =
                    NewTransaction::new(account.id, line.date, line.label.clone(), line.amount);
                row.external_id = Some(line.external_id.clone());
                transactions::insert(&mut *conn, &row, state.clock.now()).await?;
            }
        }
    }
    Ok(())
}

fn nature_of(nature: Option<&str>) -> Result<Option<CardNature>, SyncError> {
    // Never guess: an unknown nature means the format moved under us.
    nature
        .map(|n| n.parse().map_err(|_| invalid("an unknown card nature")))
        .transpose()
}

fn type_of(kind: Option<&str>) -> Result<AccountType, SyncError> {
    let kind = kind.ok_or_else(|| invalid("an account without a kind"))?;
    Ok(match kind {
        "CURRENT_ACCOUNT" => AccountType::Checking,
        "LIVRET_A" => AccountType::LivretA,
        "LDDS" => AccountType::Ldds,
        "LEP" => AccountType::Lep,
        "CSL" => AccountType::Savings,
        "LIVRET_JEUNE" => AccountType::LivretJeune,
        "PEL" => AccountType::Pel,
        "CEL" => AccountType::Cel,
        "CARD" => AccountType::CreditCard,
        "LOAN" => AccountType::Loan,
        // Never guess: an unknown kind means the format moved under us.
        _ => return Err(invalid("an unknown account kind")),
    })
}

fn fallback_name(account_type: AccountType) -> &'static str {
    match account_type {
        AccountType::CreditCard => "La Banque Postale card",
        AccountType::LivretA => "Livret A",
        AccountType::Ldds => "LDDS",
        AccountType::Lep => "LEP",
        AccountType::LivretJeune => "Livret Jeune",
        AccountType::Pel => "PEL",
        AccountType::Cel => "CEL",
        AccountType::Savings => "Compte sur livret",
        _ => "La Banque Postale account",
    }
}

fn color_for(account_type: AccountType) -> &'static str {
    match account_type {
        // Same colour for every regulated savings book, as the Bourso connector does.
        AccountType::LivretA
        | AccountType::Ldds
        | AccountType::Lep
        | AccountType::LivretJeune
        | AccountType::Pel
        | AccountType::Cel
        | AccountType::Savings => "#f59e0b",
        AccountType::CreditCard => "#2563eb",
        _ => "#dc2626",
    }
}

fn prefixed(raw: Option<&str>) -> Result<String, SyncError> {
    let cleaned = clean(raw).ok_or_else(|| invalid("an invalid identifier"))?;
    let external_id = if cleaned.starts_with(EXTERNAL_ID_PREFIX) {
        cleaned
    } else {
        format!("{EXTERNAL_ID_PREFIX}{cleaned}")
    };
    if external_id.encode_utf16().count() > MAX_EXTERNAL_ID_LENGTH {
        return Err(invalid("an invalid identifier"));
    }
    Ok(external_id)
}

// --- status ----------------------------------------------------------------------------

pub async fn get_status(state: &AppState, member_id: i64) -> AppResult<SessionStatusResponse> {
    Ok(session::find_by_member(&state.db, member_id)
        .await?
        .map_or_else(SessionStatusResponse::inactive, |s| to_status(state, &s)))
}

/// Forgets the stored session in Picsou only (the bank session is not revoked); cancels any login
/// in flight. Whether a stored session was there to delete.
pub async fn clear_session(state: &AppState, member_id: i64) -> AppResult<bool> {
    let logins = logins(state);
    let _write = logins.write.lock().await;
    {
        let mut maps = logins.maps();
        *maps.generations.entry(member_id).or_insert(0) += 1;
        maps.pending.retain(|_, p| p.member_id != member_id);
    }
    let mut tx = state.db.begin().await?;
    let existing = session::find_by_member_for_update(&mut tx, member_id).await?;
    if let Some(existing) = &existing {
        session::delete(&mut tx, existing.id).await?;
    }
    tx.commit().await?;
    Ok(existing.is_some())
}

/// LbpSyncRecovery: queued jobs live in a process-local executor and cannot survive a restart.
pub async fn recover_interrupted_syncs(state: &AppState) -> sqlx::Result<u64> {
    let recovered = session::mark_interrupted_failed(&state.db, state.clock.now()).await?;
    if recovered > 0 {
        tracing::warn!("Recovered {recovered} interrupted La Banque Postale sync job(s)");
    }
    Ok(recovered)
}

/// MemberSyncService.lbpResyncReporting, for the daily scheduler (track T8): an active session is
/// queued, an inactive one is reported and never retried.
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    let result = |status, message: &str| SourceSyncResult {
        source: SOURCE.to_owned(),
        status,
        message: message.to_owned(),
    };
    let outcome = async {
        let status = get_status(state, member_id).await?;
        if !status.is_active {
            return Ok(match status.last_sync_error {
                Some(
                    code
                    @ (SidecarErrorCode::SessionExpired | SidecarErrorCode::InvalidCredentials),
                ) => result(SyncStatus::NeedsReauth, code.as_str()),
                Some(code) => result(SyncStatus::Failed, code.as_str()),
                None => result(SyncStatus::SkippedNotConnected, "No active session"),
            });
        }
        queue_sync(state, member_id).await?;
        Ok::<_, AppError>(result(SyncStatus::Queued, ""))
    }
    .await;
    match outcome {
        Ok(outcome) => outcome,
        Err(AppError::Internal(detail)) if detail.starts_with("database") => {
            tracing::error!(
                "Database error during scheduled La Banque Postale sync (member={member_id})"
            );
            result(SyncStatus::Failed, "Database error")
        }
        Err(AppError::Coded { code, detail, .. }) => {
            tracing::warn!(
                "Could not queue scheduled La Banque Postale sync (member={member_id}; code={code})"
            );
            let classified =
                SourceSyncResult::from_sync_exception(SOURCE, Some(&code), Some(&detail));
            let message = if classified.status == SyncStatus::NeedsReauth {
                code
            } else {
                "Sync failed".to_owned()
            };
            result(classified.status, &message)
        }
        Err(e) => {
            tracing::error!(
                "La Banque Postale scheduled sync failed unexpectedly for member {member_id}: {e}"
            );
            result(SyncStatus::Failed, "Unexpected sync error")
        }
    }
}
