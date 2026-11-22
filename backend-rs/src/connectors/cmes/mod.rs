//! CmesController and CmesSyncService (track T6): Crédit Mutuel Épargne Salariale login with an
//! optional second factor; the typed login is stored encrypted so an expired session can be
//! renewed unattended during a sync.

use std::collections::{HashMap, HashSet};
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
use serde::Deserialize;
use serde_json::{Map, Value, json};
use sqlx::PgConnection;

use super::sidecar::flow::non_blank;
use super::sidecar::pending::{Pending, PendingLogins};
use super::sidecar::plans::{PlanData, PlanRules, PreparedPlan};
use super::sidecar::portfolio::{self, PortfolioAccount};
use super::sidecar::session;
use super::sidecar::template::error;
use super::sidecar::web::{SidecarInitiate, SidecarSessionState, consume};
use super::sidecar::{
    AuthInitResponse, JobError, JobResult, SessionStatusResponse, SidecarClient, SidecarSession,
    SidecarSyncStatus, SyncError, SyncJob, SyncStrategy, SyncTemplate,
};
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;
use crate::ledger::accounts;
use crate::ledger::members;
use crate::ledger::model::{Account, AccountType};
use crate::ledger::providers::CMES as PROVIDER;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode, SourceSyncResult};
use crate::routes::Routes;

const BANK: SidecarBank = SidecarBank::Cmes;
const ACCOUNT_COLOR: &str = "#8b5cf6";
const EXTERNAL_ID_PREFIX: &str = "cmes_";
const PENDING_CREDENTIALS_SECONDS: i64 = 600;
const AUTH_RATE_LIMITED: &str = "Too many Crédit Mutuel Épargne Salariale authentication attempts. Please wait before retrying.";
const SYNC_RATE_LIMITED: &str = "Too many Crédit Mutuel Épargne Salariale synchronization requests. Please wait before retrying.";
const AUTH_TIMEOUT: Duration = Duration::from_secs(45);
const VALIDATION_TIMEOUT: Duration = Duration::from_secs(150);
const POSITIONS_TIMEOUT: Duration = Duration::from_secs(120);
const RULES: PlanRules = PlanRules {
    label: "CMES",
    plan_word: "plan",
    external_id_prefix: EXTERNAL_ID_PREFIX,
    default_name: "Épargne salariale",
    fund_code_prefix: Some("CMES-"),
    drops_empty_merged_lines: false,
    allows_no_plan: false,
    hashes_label_fallback: false,
};

static CODE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\d{4,8}$").expect("regex"));

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/cmes/auth/initiate", initiate)
        .post("/api/cmes/auth/complete", complete)
        .post("/api/cmes/sync", sync_now)
        .get("/api/cmes/status", status)
        .delete("/api/cmes/session", clear);
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

fn client(state: &AppState) -> SidecarClient {
    SidecarClient::new(state, BANK, SidecarErrorCode::AuthAttemptExpired)
}

fn template() -> SyncTemplate<Strategy> {
    SyncTemplate::new(BANK, Strategy, false)
}

fn logins(state: &AppState) -> &PendingLogins {
    &state.connectors.cmes_logins
}

async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<InitiateRequest>,
) -> AppResult<AxumJson<AuthInitResponse>> {
    consume(&state, &policies::CMES_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let response = initiate_auth(
        &state,
        &req.login.unwrap_or_default(),
        &req.password.unwrap_or_default(),
        auth.member_id,
    )
    .await?;
    Ok(AxumJson(response))
}

async fn complete(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<CompleteRequest>,
) -> AppResult<AxumJson<SessionStatusResponse>> {
    consume(&state, &policies::CMES_COMPLETE, &ip, AUTH_RATE_LIMITED)?;
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

async fn status(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<AxumJson<SessionStatusResponse>> {
    Ok(AxumJson(get_status(&state, auth.member_id).await?))
}

async fn clear(State(state): State<AppState>, auth: Auth) -> AppResult<StatusCode> {
    let _write = logins(&state).write_lock().await;
    logins(&state).forget_member(auth.member_id);
    template().clear(&state, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT)
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
            "Could not initiate Crédit Mutuel Épargne Salariale authentication",
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
    let previous = previous_plain_state(state, member_id).await;
    let result = post_initiate(
        state,
        &login_body(login, password, previous.as_deref(), false),
    )
    .await?;
    if !result.mfa_required {
        let session_state = non_blank(result.session_state, "CMES did not return a session")?;
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

pub async fn complete_auth(
    state: &AppState,
    process_id: &str,
    code: Option<String>,
    member_id: i64,
) -> AppResult<SessionStatusResponse> {
    let credentials = logins(state)
        .get(process_id)
        .filter(|p| p.member_id == member_id && p.expires_at > state.clock.now())
        .ok_or_else(|| {
            error(
                SidecarErrorCode::AuthAttemptExpired,
                "CMES authentication attempt expired",
            )
        })?;
    let response: SidecarSessionState = client(state)
        .post(
            "/complete",
            &json!({ "processId": process_id, "code": code }),
            VALIDATION_TIMEOUT,
            "Could not complete Crédit Mutuel Épargne Salariale authentication",
            Some(SidecarErrorCode::AppValidationTimeout),
        )
        .await?;
    logins(state).remove(process_id);
    let plain_state = non_blank(response.session_state, "CMES did not return a session")?;
    store_session_and_queue(
        state,
        plain_state,
        member_id,
        &credentials.login,
        &credentials.password,
        credentials.generation,
    )
    .await
}

fn require_generation(state: &AppState, member_id: i64, generation: u64) -> Result<(), SyncError> {
    if logins(state).generation(member_id) != generation {
        return Err(error(
            SidecarErrorCode::AuthAttemptExpired,
            "The CMES connection was removed during the login",
        ));
    }
    Ok(())
}

async fn previous_plain_state(state: &AppState, member_id: i64) -> Option<String> {
    let stored = session::find(&state.db, BANK, member_id)
        .await
        .ok()
        .flatten()?;
    match state.crypto.decrypt(&stored.session_state) {
        Ok(plain) => Some(plain),
        Err(_) => {
            tracing::info!("Could not reuse the previous CMES session (member={member_id})");
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
) -> AppResult<SessionStatusResponse> {
    let job = {
        let _write = logins(state).write_lock().await;
        require_generation(state, member_id, generation)?;
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
        job
    };
    let status = get_status(state, member_id).await?;
    template().submit(state, job)?;
    Ok(status)
}

pub async fn queue_sync(state: &AppState, member_id: i64) -> AppResult<SessionStatusResponse> {
    Ok(SessionStatusResponse::of(
        &template().queue(state, member_id, false).await?,
    ))
}

pub async fn get_status(state: &AppState, member_id: i64) -> AppResult<SessionStatusResponse> {
    Ok(template()
        .find(state, member_id)
        .await?
        .map_or_else(SessionStatusResponse::inactive, |s| {
            SessionStatusResponse::of(&s)
        }))
}

pub async fn recover_interrupted_syncs(state: &AppState) -> sqlx::Result<u64> {
    template().recover_interrupted(state).await
}

/// The daily scheduler's entry (track T8).
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    template()
        .resync_reporting(member_id, "cmes", get_status(state, member_id), || async {
            queue_sync(state, member_id).await.map(|_| ())
        })
        .await
}

async fn fetch_plans(
    state: &AppState,
    plain_state: &str,
) -> Result<Vec<Option<PlanData>>, SyncError> {
    client(state)
        .post_for_list(
            "/positions",
            &json!({ "sessionState": plain_state }),
            POSITIONS_TIMEOUT,
            "Could not fetch Crédit Mutuel Épargne Salariale plans",
            SidecarErrorCode::PortfolioIncomplete,
            "Crédit Mutuel Épargne Salariale returned no complete plan",
        )
        .await
}

fn expired(message: &str) -> SyncError {
    error(SidecarErrorCode::SessionExpired, message)
}

/// The stored login replayed unattended, the renewed session written over the running one.
async fn reauthenticate_and_fetch(
    state: &AppState,
    job: &SyncJob,
) -> JobResult<Vec<Option<PlanData>>> {
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
        return Err(expired("CMES session expired").into());
    };
    let (login, password) = state
        .crypto
        .decrypt(&credentials)
        .ok()
        .and_then(|plain| parse_credentials(&plain))
        .ok_or_else(|| expired("CMES session expired"))?;
    let result = post_initiate(
        state,
        &login_body(&login, &password, Some(&job.plain_state), true),
    )
    .await?;
    if result.mfa_required {
        tracing::info!(
            "CMES asks for a new second factor (member={}; type={})",
            job.member_id,
            result.mfa_type.as_deref().unwrap_or("null")
        );
        return Err(expired("CMES requires a new second factor").into());
    }
    let new_state = result
        .session_state
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| error(SidecarErrorCode::InvalidData, "CMES returned no session"))?;
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
        return Err(expired("CMES session changed").into());
    }
    tracing::info!(
        "CMES re-logged in with stored credentials (member={})",
        job.member_id
    );
    Ok(fetch_plans(state, &new_state).await?)
}

fn parse_credentials(plain: &str) -> Option<(String, String)> {
    let node: Value = serde_json::from_str(plain).ok()?;
    let text = |key: &str| {
        node.get(key)
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned()
    };
    let (login, password) = (text("login"), text("password"));
    (!login.trim().is_empty() && !password.trim().is_empty()).then_some((login, password))
}

pub struct Strategy;

#[async_trait]
impl SyncStrategy for Strategy {
    type Prepared = Vec<PreparedPlan>;

    async fn fetch(&self, state: &AppState, job: &SyncJob) -> JobResult<Vec<PreparedPlan>> {
        let fetched = match fetch_plans(state, &job.plain_state).await {
            Ok(fetched) => fetched,
            Err(e) if template().code_of(&e) == SidecarErrorCode::SessionExpired => {
                reauthenticate_and_fetch(state, job).await?
            }
            Err(e) => return Err(e.into()),
        };
        Ok(RULES.prepare(fetched)?)
    }

    async fn commit(
        &self,
        state: &AppState,
        conn: &mut PgConnection,
        _session: &mut SidecarSession,
        job: &SyncJob,
        prepared: &Vec<PreparedPlan>,
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
        let mut inherited = inherited_accounts(prepared, &imported)?;
        let inherited_ids: HashSet<i64> = inherited.values().map(|a| a.id).collect();
        for plan in prepared {
            upsert(
                state,
                conn,
                plan,
                inherited.remove(&plan.external_id),
                job.member_id,
                synced_at,
            )
            .await?;
        }
        close_unlisted_plans(
            state,
            conn,
            prepared,
            &imported,
            &inherited_ids,
            job.member_id,
            synced_at,
        )
        .await
    }

    fn describe(&self, prepared: &Vec<PreparedPlan>) -> String {
        format!("plans={}", prepared.len())
    }

    async fn on_failure(
        &self,
        _conn: &mut PgConnection,
        session: &mut SidecarSession,
        code: SidecarErrorCode,
    ) -> JobResult<()> {
        if code == SidecarErrorCode::InvalidCredentials {
            session.encrypted_credentials = None;
        }
        Ok(())
    }
}

/// Accounts a plan inherits from an earlier identifier it reports, matched by name too.
fn inherited_accounts(
    prepared: &[PreparedPlan],
    imported: &[Account],
) -> Result<HashMap<String, Account>, JobError> {
    let current: HashSet<&str> = prepared.iter().map(|p| p.external_id.as_str()).collect();
    let mut inherited: HashMap<String, Account> = HashMap::new();
    for account in imported {
        let Some(external_id) = account.external_account_id.as_deref() else {
            continue;
        };
        if current.contains(external_id) {
            continue;
        }
        let claimants: Vec<&PreparedPlan> = prepared
            .iter()
            .filter(|p| {
                p.legacy_external_ids.iter().any(|id| id == external_id) && p.name == account.name
            })
            .collect();
        if claimants.len() > 1 {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "Several CMES plans match one earlier account",
            )
            .into());
        }
        if let Some(plan) = claimants.first() {
            match inherited.get(&plan.external_id) {
                Some(kept) if kept.last_synced_at >= account.last_synced_at => {}
                _ => {
                    inherited.insert(plan.external_id.clone(), account.clone());
                }
            }
        }
    }
    Ok(inherited)
}

async fn upsert(
    state: &AppState,
    conn: &mut PgConnection,
    plan: &PreparedPlan,
    inherited: Option<Account>,
    member_id: i64,
    synced_at: DateTime<Utc>,
) -> JobResult<()> {
    let data = PortfolioAccount {
        external_id: &plan.external_id,
        name: &plan.name,
        account_type: AccountType::EmployeeSavings,
        provider: PROVIDER,
        color: ACCOUNT_COLOR,
        balance_eur: plan.balance_eur,
        cash_balance: None,
        invested_eur: plan.invested_amount_eur,
    };
    let existing =
        accounts::find_by_external_id_and_member(&mut *conn, &plan.external_id, member_id).await?;
    if existing.is_none()
        && (accounts::exists_soft_deleted_by_external_id(&mut *conn, &plan.external_id, member_id)
            .await?
            || (inherited.is_none()
                && !plan.legacy_external_ids.is_empty()
                && accounts::exists_soft_deleted_by_external_ids_and_name(
                    &mut *conn,
                    &plan.legacy_external_ids,
                    &plan.name,
                    member_id,
                )
                .await?))
    {
        tracing::info!("CMES skipped a soft-deleted account (member={member_id})");
        return Ok(());
    }
    let saved = match existing.or(inherited) {
        Some(account) => portfolio::refresh(state, conn, account, &data, synced_at).await?,
        None => portfolio::create(state, conn, member_id, &data, synced_at).await?,
    };
    portfolio::replace_holdings(
        state,
        conn,
        saved.id,
        &plan.holdings(synced_at),
        plan.balance_eur,
        plan.invested_amount_eur,
    )
    .await
}

/// A plan CMES stopped listing is closed at zero rather than left at its last balance.
async fn close_unlisted_plans(
    state: &AppState,
    conn: &mut PgConnection,
    prepared: &[PreparedPlan],
    imported: &[Account],
    inherited_ids: &HashSet<i64>,
    member_id: i64,
    synced_at: DateTime<Utc>,
) -> JobResult<()> {
    let listed: HashSet<&str> = prepared.iter().map(|p| p.external_id.as_str()).collect();
    for account in imported {
        let Some(external_id) = account.external_account_id.as_deref() else {
            continue;
        };
        if !external_id.starts_with(EXTERNAL_ID_PREFIX)
            || listed.contains(external_id)
            || inherited_ids.contains(&account.id)
            || account.current_balance.is_zero()
        {
            continue;
        }
        tracing::info!(
            "CMES no longer lists an imported plan, closing it at zero (member={member_id}; account={})",
            account.id
        );
        let mut closed = account.clone();
        closed.current_balance = Decimal::ZERO;
        closed.cash_balance = None;
        closed.last_synced_at = Some(synced_at);
        let saved = accounts::update(&mut *conn, &closed, state.clock.now()).await?;
        portfolio::replace_holdings(state, conn, saved.id, &[], Decimal::ZERO, Decimal::ZERO)
            .await?;
    }
    Ok(())
}
