//! CicController and CicSyncService (track T6): CIC current and savings accounts, balances only.
//! The typed login is stored encrypted: a sync whose session expired logs in again with it,
//! replaying the previous session for its remembered-device cookie, and the saver can reconnect
//! from it without typing it again.

use std::collections::HashSet;
use std::sync::LazyLock;
use std::time::Duration;

use async_trait::async_trait;
use axum::Json as AxumJson;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use sqlx::PgConnection;

use super::sidecar::flow::non_blank;
use super::sidecar::pending::{Pending, PendingLogins};
use super::sidecar::session;
use super::sidecar::template::error;
use super::sidecar::web::{SidecarInitiate, SidecarSessionState, consume};
use super::sidecar::{
    AuthInitResponse, JobError, JobResult, SessionView, SidecarClient, SidecarSession,
    SidecarSyncStatus, SyncError, SyncJob, SyncStrategy, SyncTemplate,
};
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::members;
use crate::ledger::model::{Account, AccountType};
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode, SourceSyncResult};
use crate::ledger::sync_values::{clean, limit};
use crate::ledger::valuation::{self, SnapshotContext};
use crate::routes::Routes;

const BANK: SidecarBank = SidecarBank::Cic;
const PROVIDER: &str = "CIC";
const EXTERNAL_ID_PREFIX: &str = "cic_";
/// What the sidecar may return; anything else is refused, not stored.
const SUPPORTED_TYPES: &[AccountType] = &[
    AccountType::Checking,
    AccountType::Savings,
    AccountType::LivretA,
    AccountType::Ldds,
    AccountType::Lep,
    AccountType::LivretJeune,
    AccountType::Pel,
    AccountType::Cel,
];
const PENDING_CREDENTIALS_SECONDS: i64 = 600;
const AUTH_RATE_LIMITED: &str =
    "Too many CIC authentication attempts. Please wait before retrying.";
const SYNC_RATE_LIMITED: &str =
    "Too many CIC synchronization requests. Please wait before retrying.";
const AUTH_TIMEOUT: Duration = Duration::from_secs(45);
/// Above the sidecar's 120 s wait for a mobile confirmation, which a human is approving.
const VALIDATION_TIMEOUT: Duration = Duration::from_secs(150);
const ACCOUNTS_TIMEOUT: Duration = Duration::from_secs(60);
const FETCH_MESSAGE: &str = "Could not fetch CIC accounts";
const NO_ACCOUNT: &str = "CIC returned no account";
const ATTEMPT_EXPIRED: &str = "CIC authentication attempt expired";

static CODE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\d{4,8}$").expect("regex"));

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/cic/auth/initiate", initiate)
        .post("/api/cic/auth/reconnect", reconnect_now)
        .post("/api/cic/auth/complete", complete)
        .post("/api/cic/sync", sync_now)
        .get("/api/cic/status", status)
        .delete("/api/cic/session", clear);
}

#[derive(Debug, Deserialize)]
struct InitiateRequest {
    login: Option<String>,
    password: Option<String>,
}

impl Validate for InitiateRequest {
    fn validate(&self, e: &mut Errors) {
        let (login, password) = (self.login.as_deref(), self.password.as_deref());
        e.not_blank("login", login).size("login", login, 0, 100);
        e.not_blank("password", password)
            .size("password", password, 0, 100);
    }
}

/// `code` is the SMS code; a mobile confirmation has none, so it is only checked when present.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CompleteRequest {
    process_id: Option<String>,
    code: Option<String>,
}

impl Validate for CompleteRequest {
    fn validate(&self, e: &mut Errors) {
        let (process_id, code) = (self.process_id.as_deref(), self.code.as_deref());
        e.not_blank("processId", process_id)
            .size("processId", process_id, 0, 100);
        e.pattern_default("code", code, &CODE, r"\d{4,8}");
    }
}

/// CicSyncService.SessionStatusResponse: the shared status plus whether stored credentials can
/// log in again.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusResponse {
    pub is_active: bool,
    pub sync_status: SidecarSyncStatus,
    pub last_sync_started_at: Option<DateTime<Utc>>,
    pub last_sync_completed_at: Option<DateTime<Utc>>,
    pub last_sync_error: Option<SidecarErrorCode>,
    pub can_reconnect: bool,
}

impl StatusResponse {
    fn inactive() -> Self {
        Self {
            is_active: false,
            sync_status: SidecarSyncStatus::Idle,
            last_sync_started_at: None,
            last_sync_completed_at: None,
            last_sync_error: None,
            can_reconnect: false,
        }
    }

    fn of(session: &SidecarSession) -> Self {
        Self {
            is_active: session.active,
            sync_status: session.sync_status,
            last_sync_started_at: session.last_sync_started_at,
            last_sync_completed_at: session.last_sync_completed_at,
            last_sync_error: session.last_sync_error,
            can_reconnect: session.encrypted_credentials.is_some(),
        }
    }
}

impl SessionView for StatusResponse {
    fn is_active(&self) -> bool {
        self.is_active
    }

    fn last_sync_error(&self) -> Option<SidecarErrorCode> {
        self.last_sync_error
    }
}

fn client(state: &AppState) -> SidecarClient {
    SidecarClient::new(state, BANK, SidecarErrorCode::AuthAttemptExpired)
}

fn template() -> SyncTemplate<Strategy> {
    SyncTemplate::new(BANK, Strategy, false)
}

fn logins(state: &AppState) -> &PendingLogins {
    &state.connectors.cic_logins
}

async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<InitiateRequest>,
) -> AppResult<AxumJson<AuthInitResponse>> {
    consume(&state, &policies::CIC_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let response = initiate_auth(
        &state,
        &req.login.unwrap_or_default(),
        &req.password.unwrap_or_default(),
        auth.member_id,
    )
    .await?;
    Ok(AxumJson(response))
}

async fn reconnect_now(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
) -> AppResult<AxumJson<AuthInitResponse>> {
    consume(&state, &policies::CIC_AUTH, &ip, AUTH_RATE_LIMITED)?;
    Ok(AxumJson(reconnect(&state, auth.member_id).await?))
}

async fn complete(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<CompleteRequest>,
) -> AppResult<AxumJson<StatusResponse>> {
    consume(&state, &policies::CIC_COMPLETE, &ip, AUTH_RATE_LIMITED)?;
    let process_id = req.process_id.unwrap_or_default();
    Ok(AxumJson(
        complete_auth(&state, &process_id, req.code, auth.member_id).await?,
    ))
}

async fn sync_now(State(state): State<AppState>, auth: Auth, ip: ClientIp) -> AppResult<Response> {
    consume(&state, &policies::SYNC, &ip, SYNC_RATE_LIMITED)?;
    let status = queue_sync(&state, auth.member_id).await?;
    Ok((StatusCode::ACCEPTED, AxumJson(status)).into_response())
}

async fn status(State(state): State<AppState>, auth: Auth) -> AppResult<AxumJson<StatusResponse>> {
    Ok(AxumJson(get_status(&state, auth.member_id).await?))
}

async fn clear(State(state): State<AppState>, auth: Auth) -> AppResult<StatusCode> {
    clear_session(&state, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

/// Whether a stored session was there to delete. A pending login is dropped too, and a login
/// still waiting on the bank is revoked by the generation bump.
pub async fn clear_session(state: &AppState, member_id: i64) -> AppResult<bool> {
    let _write = logins(state).write_lock().await;
    logins(state).forget_member(member_id);
    template().clear(state, member_id).await
}

fn login_body(
    login: &str,
    password: &str,
    previous_state: Option<&str>,
    unattended: bool,
) -> Value {
    let mut body = Map::new();
    if unattended {
        body.insert("unattended".into(), Value::Bool(true));
    }
    body.insert("login".into(), login.into());
    body.insert("password".into(), password.into());
    if let Some(previous) = previous_state.filter(|s| !s.trim().is_empty()) {
        body.insert("sessionState".into(), previous.into());
    }
    Value::Object(body)
}

async fn post_initiate(state: &AppState, body: &Value) -> Result<SidecarInitiate, SyncError> {
    client(state)
        .post(
            "/initiate",
            body,
            AUTH_TIMEOUT,
            "Could not initiate CIC authentication",
            Some(SidecarErrorCode::InvalidCredentials),
        )
        .await
}

pub async fn initiate_auth(
    state: &AppState,
    login: &str,
    password: &str,
    member_id: i64,
) -> AppResult<AuthInitResponse> {
    let generation = logins(state).generation(member_id);
    initiate_with(state, login, password, member_id, generation).await
}

async fn initiate_with(
    state: &AppState,
    login: &str,
    password: &str,
    member_id: i64,
    generation: u64,
) -> AppResult<AuthInitResponse> {
    require_generation(state, member_id, generation)?;
    // Replaying the remembered-device cookie spares a reconnection a new confirmation.
    let previous = previous_plain_state(state, member_id).await;
    let result = post_initiate(
        state,
        &login_body(login, password, previous.as_deref(), false),
    )
    .await?;
    if !result.mfa_required {
        let session_state = non_blank(result.session_state, "CIC did not return a session")?;
        store_session_and_queue(state, session_state, member_id, login, password, generation)
            .await?;
    } else if let Some(process_id) = &result.process_id {
        let now = state.clock.now();
        logins(state).evict_expired(now);
        let _write = logins(state).write_lock().await;
        require_generation(state, member_id, generation)?;
        logins(state).put(
            process_id.clone(),
            Pending {
                login: login.to_owned(),
                password: password.to_owned(),
                expires_at: now + chrono::Duration::seconds(PENDING_CREDENTIALS_SECONDS),
                member_id,
                generation,
            },
        );
    }
    Ok(AuthInitResponse {
        process_id: result.process_id,
        mfa_required: result.mfa_required,
        mfa_type: result.mfa_type,
    })
}

/// A reconnection the saver starts from the credentials stored at the last login. Someone is
/// there to approve, so it goes through the same second-factor steps as a typed login.
pub async fn reconnect(state: &AppState, member_id: i64) -> AppResult<AuthInitResponse> {
    let generation = logins(state).generation(member_id);
    let encrypted = session::find(&state.db, BANK, member_id)
        .await?
        .and_then(|s| s.encrypted_credentials)
        .ok_or_else(|| expired("No stored CIC credentials"))?;
    let (login, password) = state
        .crypto
        .decrypt(&encrypted)
        .ok()
        .and_then(|plain| parse_credentials(&plain))
        .ok_or_else(|| expired("Stored CIC credentials are unreadable"))?;
    let result = initiate_with(state, &login, &password, member_id, generation).await;
    if let Err(AppError::Coded { code, .. }) = &result
        && code == SidecarErrorCode::InvalidCredentials.as_str()
    {
        reject_stored_credentials(state, member_id, generation, &encrypted).await?;
    }
    result
}

/// A rejected password must not be replayed: the site locks the access after a few failures.
async fn reject_stored_credentials(
    state: &AppState,
    member_id: i64,
    generation: u64,
    encrypted: &str,
) -> AppResult<()> {
    let _write = logins(state).write_lock().await;
    if logins(state).generation(member_id) != generation {
        return Ok(());
    }
    let mut tx = state.db.begin().await?;
    // A newer login's password must survive an older rejection.
    if let Some(mut current) = session::find_for_update(&mut tx, BANK, member_id)
        .await?
        .filter(|s| s.encrypted_credentials.as_deref() == Some(encrypted))
    {
        let now = state.clock.now();
        current.reject_credentials(now);
        session::save(&mut tx, &current, now).await?;
    }
    tx.commit().await?;
    Ok(())
}

/// The scheduled sweep (every minute in Java) of logins abandoned before their second factor,
/// for track T8 to run.
pub fn evict_expired_pending_credentials(state: &AppState) {
    logins(state).evict_expired(state.clock.now());
}

fn attempt_expired() -> SyncError {
    error(SidecarErrorCode::AuthAttemptExpired, ATTEMPT_EXPIRED)
}

fn same_attempt(a: &Pending, b: &Pending) -> bool {
    a.member_id == b.member_id
        && a.generation == b.generation
        && a.expires_at == b.expires_at
        && a.login == b.login
        && a.password == b.password
}

/// Removes the attempt only if it is still the one read earlier; called under the write lock.
fn remove_attempt(state: &AppState, process_id: &str, attempt: &Pending) -> bool {
    let current = logins(state).get(process_id);
    if current.is_some_and(|p| same_attempt(&p, attempt)) {
        logins(state).remove(process_id);
        return true;
    }
    false
}

/// `code` is the SMS code, or None for a mobile confirmation.
pub async fn complete_auth(
    state: &AppState,
    process_id: &str,
    code: Option<String>,
    member_id: i64,
) -> AppResult<StatusResponse> {
    let credentials = {
        let _write = logins(state).write_lock().await;
        let found = logins(state)
            .get(process_id)
            .filter(|p| p.member_id == member_id)
            .ok_or_else(attempt_expired)?;
        if found.expires_at <= state.clock.now() {
            remove_attempt(state, process_id, &found);
            return Err(attempt_expired().into());
        }
        require_generation(state, member_id, found.generation)?;
        found
    };
    // A wrong SMS code fails here, before the attempt is dropped, so the next try can store it.
    let response: SidecarSessionState = client(state)
        .post(
            "/complete",
            &json!({ "processId": process_id, "code": code }),
            VALIDATION_TIMEOUT,
            "Could not complete CIC authentication",
            Some(SidecarErrorCode::AppValidationTimeout),
        )
        .await?;
    let plain_state = non_blank(response.session_state, "CIC did not return a session")?;
    let job = {
        let _write = logins(state).write_lock().await;
        require_generation(state, member_id, credentials.generation)?;
        if !remove_attempt(state, process_id, &credentials)
            || credentials.expires_at <= state.clock.now()
        {
            return Err(attempt_expired().into());
        }
        insert_session(
            state,
            plain_state,
            member_id,
            &credentials.login,
            &credentials.password,
        )
        .await?
    };
    let status = get_status(state, member_id).await?;
    template().submit(state, job)?;
    Ok(status)
}

fn require_generation(state: &AppState, member_id: i64, generation: u64) -> Result<(), SyncError> {
    if logins(state).generation(member_id) != generation {
        return Err(error(
            SidecarErrorCode::AuthAttemptExpired,
            "The CIC connection was removed during the login",
        ));
    }
    Ok(())
}

/// The stored session, even an expired one: only its remembered-device cookie is reused.
async fn previous_plain_state(state: &AppState, member_id: i64) -> Option<String> {
    let stored = session::find(&state.db, BANK, member_id)
        .await
        .ok()
        .flatten()?;
    match state.crypto.decrypt(&stored.session_state) {
        Ok(plain) => Some(plain),
        Err(_) => {
            tracing::info!("Could not reuse the previous CIC session (member={member_id})");
            None
        }
    }
}

async fn store_session_and_queue(
    state: &AppState,
    plain_state: String,
    member_id: i64,
    login: &str,
    password: &str,
    generation: u64,
) -> AppResult<()> {
    let job = {
        let _write = logins(state).write_lock().await;
        require_generation(state, member_id, generation)?;
        insert_session(state, plain_state, member_id, login, password).await?
    };
    template().submit(state, job)
}

/// The new session and its encrypted credentials in one transaction; the caller holds the write
/// lock and submits the job.
async fn insert_session(
    state: &AppState,
    plain_state: String,
    member_id: i64,
    login: &str,
    password: &str,
) -> AppResult<SyncJob> {
    let credentials = state
        .crypto
        .encrypt(&json!({ "login": login, "password": password }).to_string())?;
    let mut tx = state.db.begin().await?;
    let job = template()
        .insert_queued(state, &mut tx, member_id, plain_state, false, |session| {
            session.encrypted_credentials = Some(credentials);
            Ok(())
        })
        .await?;
    tx.commit().await?;
    Ok(job)
}

pub async fn queue_sync(state: &AppState, member_id: i64) -> AppResult<StatusResponse> {
    Ok(StatusResponse::of(
        &template().queue(state, member_id, false).await?,
    ))
}

pub async fn get_status(state: &AppState, member_id: i64) -> AppResult<StatusResponse> {
    Ok(template()
        .find(state, member_id)
        .await?
        .map_or_else(StatusResponse::inactive, |s| StatusResponse::of(&s)))
}

/// Turns this bank's persisted in-flight syncs into retryable failures at startup.
pub async fn recover_interrupted_syncs(state: &AppState) -> sqlx::Result<u64> {
    template().recover_interrupted(state).await
}

/// The daily scheduler's entry (track T8).
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    template()
        .resync_reporting(member_id, "cic", get_status(state, member_id), || async {
            queue_sync(state, member_id).await.map(|_| ())
        })
        .await
}

/// CicAdapter.AccountData, as the sidecar sends it.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AccountData {
    external_id: Option<String>,
    name: Option<String>,
    #[serde(rename = "type")]
    account_type: Option<AccountType>,
    balance_eur: Option<Decimal>,
    snapshot_complete: Option<bool>,
}

/// The accounts, and the in-scope tables the page carried (CHECKING, SAVINGS), an empty one
/// included; None when the sidecar does not say.
struct Listing {
    accounts: Vec<AccountData>,
    categories_read: Option<HashSet<String>>,
}

/// /accounts/listing, or the bare /accounts list of an older sidecar, which answers 404 for the
/// unknown route.
async fn fetch_accounts(state: &AppState, plain_state: &str) -> JobResult<Listing> {
    let client = client(state);
    let body = json!({ "sessionState": plain_state });
    let response = client
        .post_raw("/accounts/listing", &body, ACCOUNTS_TIMEOUT)
        .await
        .map_err(|e| client.map_call_error(FETCH_MESSAGE, e))?;
    if response.status == StatusCode::NOT_FOUND {
        let listed: Vec<Option<AccountData>> = client
            .post_for_list(
                "/accounts",
                &body,
                ACCOUNTS_TIMEOUT,
                FETCH_MESSAGE,
                SidecarErrorCode::PortfolioIncomplete,
                NO_ACCOUNT,
            )
            .await?;
        // Java's List.of refuses a null element, a failure the client maps as an outage.
        let accounts = listed
            .into_iter()
            .collect::<Option<Vec<_>>>()
            .ok_or_else(|| client.coded(SidecarErrorCode::UpstreamUnavailable, FETCH_MESSAGE))?;
        return Ok(Listing {
            accounts,
            categories_read: None,
        });
    }
    if !response.is_success() {
        return Err(client.map_status(FETCH_MESSAGE, &response, None).into());
    }
    let value: Value = client
        .decode(FETCH_MESSAGE, &response)?
        .ok_or_else(|| client.coded(SidecarErrorCode::UpstreamUnavailable, FETCH_MESSAGE))?;
    let (accounts, categories_read) = match value {
        Value::Array(_) => (value, None),
        Value::Object(mut fields) => {
            let categories = match fields.remove("categoriesRead") {
                Some(Value::Array(read)) => Some(read.iter().map(as_text).collect()),
                _ => None,
            };
            (fields.remove("accounts").unwrap_or(Value::Null), categories)
        }
        _ => (Value::Null, None),
    };
    if accounts.as_array().is_none_or(Vec::is_empty) {
        // Nothing back means the read fell short, not an empty portfolio.
        return Err(client
            .coded(SidecarErrorCode::PortfolioIncomplete, NO_ACCOUNT)
            .into());
    }
    let parsed: Vec<Option<AccountData>> = serde_json::from_value(accounts).map_err(|_| {
        client.coded(
            SidecarErrorCode::InvalidData,
            "CIC returned unreadable accounts",
        )
    })?;
    // Java's List.of throws on a null element outside the client: an unexpected failure.
    let accounts = parsed
        .into_iter()
        .collect::<Option<Vec<_>>>()
        .ok_or_else(|| AppError::internal("CIC returned a null account"))?;
    Ok(Listing {
        accounts,
        categories_read,
    })
}

/// JsonNode.asText.
fn as_text(node: &Value) -> String {
    match node {
        Value::String(text) => text.clone(),
        Value::Array(_) | Value::Object(_) => String::new(),
        other => other.to_string(),
    }
}

fn expired(message: &str) -> SyncError {
    error(SidecarErrorCode::SessionExpired, message)
}

/// The site's sessions last minutes, so nearly every scheduled sync lands here: the stored login
/// replayed unattended with the previous session, the renewed session written over the running
/// one. A login that still asks for a second factor cannot be finished and reads as expired.
async fn reauthenticate_and_fetch(state: &AppState, job: &SyncJob) -> JobResult<Listing> {
    let current = {
        let mut tx = state.db.begin().await?;
        let found = session::find_by_id_for_update(&mut tx, job.session_id, job.member_id).await?;
        tx.commit().await?;
        found
    };
    let Some(credentials) = current
        .filter(|s| s.active && s.sync_status == SidecarSyncStatus::Running)
        .and_then(|s| s.encrypted_credentials)
    else {
        return Err(expired("CIC session expired").into());
    };
    let (login, password) = state
        .crypto
        .decrypt(&credentials)
        .ok()
        .and_then(|plain| parse_credentials(&plain))
        .ok_or_else(|| expired("CIC session expired"))?;
    let result = post_initiate(
        state,
        &login_body(&login, &password, Some(&job.plain_state), true),
    )
    .await?;
    if result.mfa_required {
        tracing::info!(
            "CIC asks for a new second factor (member={}; type={})",
            job.member_id,
            result.mfa_type.as_deref().unwrap_or("null")
        );
        return Err(expired("CIC requires a new second factor").into());
    }
    let new_state = result
        .session_state
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| error(SidecarErrorCode::InvalidData, "CIC returned no session"))?;
    let encrypted = state.crypto.encrypt(&new_state)?;
    let mut tx = state.db.begin().await?;
    let updated =
        match session::find_by_id_for_update(&mut tx, job.session_id, job.member_id).await? {
            Some(mut current)
                if current.active && current.sync_status == SidecarSyncStatus::Running =>
            {
                let now = state.clock.now();
                current.update_session_state(encrypted, now);
                session::save(&mut tx, &current, now).await?;
                true
            }
            _ => false,
        };
    tx.commit().await?;
    if !updated {
        return Err(expired("CIC session changed").into());
    }
    tracing::info!(
        "CIC re-logged in with stored credentials (member={})",
        job.member_id
    );
    fetch_accounts(state, &new_state).await
}

fn parse_credentials(plain: &str) -> Option<(String, String)> {
    let node: Value = serde_json::from_str(plain).ok()?;
    let text = |key: &str| node.get(key).map(as_text).unwrap_or_default();
    let (login, password) = (text("login"), text("password"));
    (!login.trim().is_empty() && !password.trim().is_empty()).then_some((login, password))
}

pub struct PreparedAccount {
    external_id: String,
    name: String,
    account_type: AccountType,
    balance_eur: Decimal,
}

pub struct Fetched {
    accounts: Vec<PreparedAccount>,
    categories_read: Option<HashSet<String>>,
}

fn stable_external_id(raw: Option<&str>) -> Result<String, SyncError> {
    let invalid = || {
        error(
            SidecarErrorCode::InvalidData,
            "CIC returned an invalid account identifier",
        )
    };
    let cleaned = clean(raw).ok_or_else(invalid)?;
    let external_id = if cleaned.starts_with(EXTERNAL_ID_PREFIX) {
        cleaned
    } else {
        format!("{EXTERNAL_ID_PREFIX}{cleaned}")
    };
    if external_id.encode_utf16().count() > 100 {
        return Err(invalid());
    }
    Ok(external_id)
}

/// A partial read is refused whole rather than merged.
fn prepare_accounts(fetched: Vec<AccountData>) -> Result<Vec<PreparedAccount>, SyncError> {
    if fetched.is_empty() {
        return Err(error(SidecarErrorCode::PortfolioIncomplete, NO_ACCOUNT));
    }
    let mut external_ids = HashSet::new();
    let mut prepared = Vec::with_capacity(fetched.len());
    for account in fetched {
        if account.snapshot_complete != Some(true) {
            return Err(error(
                SidecarErrorCode::PortfolioIncomplete,
                "CIC returned an incomplete account list",
            ));
        }
        let external_id = stable_external_id(account.external_id.as_deref())?;
        if !external_ids.insert(external_id.clone()) {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CIC returned duplicate accounts",
            ));
        }
        let Some(account_type) = account.account_type.filter(|t| SUPPORTED_TYPES.contains(t))
        else {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CIC returned an unsupported account type",
            ));
        };
        let Some(balance_eur) = account.balance_eur else {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CIC returned an account without balance",
            ));
        };
        prepared.push(PreparedAccount {
            external_id,
            name: limit(account.name.as_deref(), 100, "CIC"),
            account_type,
            balance_eur,
        });
    }
    Ok(prepared)
}

/// The table an account type comes from on the page.
fn category_of(account_type: AccountType) -> &'static str {
    if account_type == AccountType::Checking {
        "CHECKING"
    } else {
        "SAVINGS"
    }
}

pub struct Strategy;

#[async_trait]
impl SyncStrategy for Strategy {
    type Prepared = Fetched;

    async fn fetch(&self, state: &AppState, job: &SyncJob) -> JobResult<Fetched> {
        let listing = match fetch_accounts(state, &job.plain_state).await {
            Ok(listing) => listing,
            Err(JobError::Sync(e))
                if template().code_of(&e) == SidecarErrorCode::SessionExpired =>
            {
                reauthenticate_and_fetch(state, job).await?
            }
            Err(e) => return Err(e),
        };
        Ok(Fetched {
            accounts: prepare_accounts(listing.accounts)?,
            categories_read: listing.categories_read,
        })
    }

    async fn commit(
        &self,
        state: &AppState,
        conn: &mut PgConnection,
        _session: &mut SidecarSession,
        job: &SyncJob,
        fetched: &Fetched,
        synced_at: DateTime<Utc>,
    ) -> JobResult<()> {
        if members::find_by_id(&mut *conn, job.member_id)
            .await?
            .is_none()
        {
            return Err(AppError::NotFound("Family member not found".to_owned()).into());
        }
        let imported =
            accounts::find_all_by_member_and_provider(&mut *conn, job.member_id, PROVIDER).await?;
        let ctx = SnapshotContext {
            prices: &*state.ledger.prices,
            today: state.clock.today(),
            now: state.clock.now(),
        };
        for data in &fetched.accounts {
            upsert_account(conn, &ctx, data, job.member_id, synced_at).await?;
        }
        close_unlisted_accounts(conn, &ctx, fetched, &imported, job.member_id, synced_at).await
    }

    fn describe(&self, fetched: &Fetched) -> String {
        format!("accounts={}", fetched.accounts.len())
    }

    async fn on_failure(
        &self,
        _conn: &mut PgConnection,
        session: &mut SidecarSession,
        code: SidecarErrorCode,
    ) -> JobResult<()> {
        // A rejected password must not be replayed by every scheduled sync.
        if code == SidecarErrorCode::InvalidCredentials {
            session.encrypted_credentials = None;
        }
        Ok(())
    }
}

async fn upsert_account(
    conn: &mut PgConnection,
    ctx: &SnapshotContext<'_>,
    data: &PreparedAccount,
    member_id: i64,
    synced_at: DateTime<Utc>,
) -> JobResult<()> {
    let existing =
        accounts::find_by_external_id_and_member(&mut *conn, &data.external_id, member_id).await?;
    let saved = match existing {
        Some(mut account) => {
            account.name = data.name.clone();
            account.account_type = data.account_type;
            account.provider = Some(PROVIDER.to_owned());
            account.currency = "EUR".to_owned();
            account.is_manual = false;
            account.current_balance = data.balance_eur;
            // A livret or a current account has one number and no cash leg.
            account.cash_balance = None;
            account.last_synced_at = Some(synced_at);
            accounts::update(&mut *conn, &account, ctx.now).await?
        }
        None => {
            if accounts::exists_soft_deleted_by_external_id(
                &mut *conn,
                &data.external_id,
                member_id,
            )
            .await?
            {
                tracing::info!("CIC skipped a soft-deleted account (member={member_id})");
                return Ok(());
            }
            let mut draft = NewAccount::new(member_id, data.name.clone(), data.account_type);
            draft.external_account_id = Some(data.external_id.clone());
            draft.provider = Some(PROVIDER.to_owned());
            draft.is_manual = false;
            draft.color = if data.account_type == AccountType::Checking {
                "#ec4899"
            } else {
                "#f59e0b"
            }
            .to_owned();
            draft.current_balance = data.balance_eur;
            draft.last_synced_at = Some(synced_at);
            accounts::insert(&mut *conn, &draft, ctx.now).await?
        }
    };
    valuation::upsert_snapshot(conn, ctx, &saved, data.balance_eur, ctx.today).await?;
    Ok(())
}

/// An account a complete fetch no longer lists (a closed livret) is closed at zero, not soft
/// deleted, so it can come back. Only the categories whose table was read close anything: a
/// table missing from the page leaves its accounts as they were. A sidecar that does not report
/// the tables falls back to the categories of the accounts it returned.
async fn close_unlisted_accounts(
    conn: &mut PgConnection,
    ctx: &SnapshotContext<'_>,
    fetched: &Fetched,
    imported: &[Account],
    member_id: i64,
    synced_at: DateTime<Utc>,
) -> JobResult<()> {
    let listed: HashSet<&str> = fetched
        .accounts
        .iter()
        .map(|a| a.external_id.as_str())
        .collect();
    let returned: HashSet<String> = fetched
        .accounts
        .iter()
        .map(|a| category_of(a.account_type).to_owned())
        .collect();
    let categories_read = fetched.categories_read.as_ref().unwrap_or(&returned);
    for account in imported {
        let Some(external_id) = account.external_account_id.as_deref() else {
            continue;
        };
        if !external_id.starts_with(EXTERNAL_ID_PREFIX) || listed.contains(external_id) {
            continue;
        }
        if !categories_read.contains(category_of(account.account_type)) {
            tracing::info!(
                "CIC returned no account of an imported account's category, keeping it (member={member_id}; account={})",
                account.id
            );
            continue;
        }
        if account.current_balance.is_zero() {
            continue;
        }
        tracing::info!(
            "CIC no longer lists an imported account, closing it at zero (member={member_id}; account={})",
            account.id
        );
        let mut closed = account.clone();
        closed.current_balance = Decimal::ZERO;
        closed.cash_balance = None;
        closed.last_synced_at = Some(synced_at);
        let saved = accounts::update(&mut *conn, &closed, ctx.now).await?;
        valuation::upsert_snapshot(conn, ctx, &saved, Decimal::ZERO, ctx.today).await?;
    }
    Ok(())
}
