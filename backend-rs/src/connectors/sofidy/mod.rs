//! SofidyController and SofidySyncService (track T6): a Sofidy Espace Associé portfolio mapped
//! onto SCPI accounts through the \`scpi_funds\` seam. Sofidy always sends a verification code by
//! e-mail after the password, so a login is two calls and the session only exists once the code
//! is typed. An inactive, encrypted reservation row binds each pending login to its member;
//! replacing or deleting it voids an authentication answer still in flight.

use std::collections::HashSet;
use std::sync::LazyLock;
use std::time::Duration;

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use async_trait::async_trait;
use axum::Json as AxumJson;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::json;

use super::seams::{ScpiProvider, SyncedFund};
use super::sidecar::flow::non_blank;
use super::sidecar::session;
use super::sidecar::template::error;
use super::sidecar::web::consume;
use super::sidecar::{
    JobResult, SessionStatusResponse, SidecarClient, SidecarInitiate, SidecarSession,
    SidecarSessionState, SidecarSyncStatus, SyncError, SyncJob, SyncStrategy, SyncTemplate,
};
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate, null_as_false};
use crate::http::rate_limit::policies;
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::members;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode};
use crate::routes::Routes;

const BANK: SidecarBank = SidecarBank::Sofidy;
const AUTH_RATE_LIMITED: &str =
    "Too many Sofidy authentication attempts. Please wait before retrying.";
const SYNC_RATE_LIMITED: &str =
    "Too many Sofidy synchronization requests. Please wait before retrying.";
const AUTH_START_PREFIX: &str = "sofidy-auth-start:";
const AUTH_PROCESS_PREFIX: &str = "sofidy-auth-process:";
const AUTH_TTL_MINUTES: i64 = 5;
const AUTH_TIMEOUT: Duration = Duration::from_secs(45);
/// Above the sidecar's own pending-login TTL, so the sidecar gives up first.
const VALIDATION_TIMEOUT: Duration = Duration::from_secs(150);
const POSITIONS_TIMEOUT: Duration = Duration::from_secs(90);

/// The six digits printed on Sofidy's paper mail (and of the e-mailed code).
static SIX_DIGITS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\d{6}$").expect("regex"));

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/sofidy/auth/initiate", initiate)
        .post("/api/sofidy/auth/complete", complete)
        .post("/api/sofidy/sync", sync_now)
        .get("/api/sofidy/status", status)
        .delete("/api/sofidy/session", clear);
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct InitiateRequest {
    associate_code: Option<String>,
    password: Option<String>,
}

impl Validate for InitiateRequest {
    fn validate(&self, e: &mut Errors) {
        let (code, password) = (self.associate_code.as_deref(), self.password.as_deref());
        e.not_blank("associateCode", code).pattern_default(
            "associateCode",
            code,
            &SIX_DIGITS,
            r"\d{6}",
        );
        e.not_blank("password", password)
            .size("password", password, 0, 100);
    }
}

#[derive(Deserialize)]
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
        e.not_blank("code", code)
            .pattern_default("code", code, &SIX_DIGITS, r"\d{6}");
    }
}

/// What the panel shows after /auth/initiate, with the session status folded in: the panel
/// writes this answer straight into its status cache.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InitiateResponse {
    pub process_id: Option<String>,
    pub mfa_required: bool,
    pub mfa_type: Option<String>,
    pub is_active: bool,
    pub sync_status: SidecarSyncStatus,
    pub last_sync_error: Option<SidecarErrorCode>,
    pub last_sync_started_at: Option<DateTime<Utc>>,
    pub last_sync_completed_at: Option<DateTime<Utc>>,
}

/// The portfolio as the sidecar reads it; everything is EUR.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    pub currency: Option<String>,
    pub total_eur: Option<Decimal>,
    #[serde(default, deserialize_with = "null_as_false")]
    pub snapshot_complete: bool,
    pub holdings: Option<Vec<Option<Holding>>>,
}

/// One fund line. \`withdrawal_price\` is the redemption value, the only price that may become a
/// balance; None means Sofidy is not quoting the fund today, not a zero.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Holding {
    pub fund_code: Option<String>,
    pub label: Option<String>,
    pub quantity: Option<Decimal>,
    pub withdrawal_price: Option<Decimal>,
}

impl Holding {
    fn withdrawal_value(&self) -> Option<Decimal> {
        Some(self.quantity? * self.withdrawal_price?)
    }
}

/// The reservation a login holds: its row and the encrypted marker it was written with.
#[derive(Debug, Clone)]
struct AuthAttempt {
    session_id: i64,
    encrypted_state: String,
}

fn client(state: &AppState) -> SidecarClient {
    SidecarClient::new(state, BANK, SidecarErrorCode::AuthAttemptExpired)
}

fn template() -> SyncTemplate<Strategy> {
    SyncTemplate::new(BANK, Strategy, true)
}

pub async fn recover_interrupted_syncs(state: &AppState) -> sqlx::Result<u64> {
    template().recover_interrupted(state).await
}

async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<InitiateRequest>,
) -> AppResult<AxumJson<InitiateResponse>> {
    consume(&state, &policies::SOFIDY_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let response = initiate_auth(
        &state,
        &req.associate_code.unwrap_or_default(),
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
    consume(&state, &policies::SOFIDY_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let response = complete_auth(
        &state,
        &req.process_id.unwrap_or_default(),
        &req.code.unwrap_or_default(),
        auth.member_id,
    )
    .await?;
    Ok(AxumJson(response))
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
    template().clear(&state, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

fn expired_attempt() -> SyncError {
    error(
        SidecarErrorCode::AuthAttemptExpired,
        "This Sofidy login attempt is no longer valid. Please reconnect.",
    )
}

fn is_current(session: &SidecarSession, now: DateTime<Utc>) -> bool {
    session
        .last_validated_at
        .is_some_and(|at| at + chrono::Duration::minutes(AUTH_TTL_MINUTES) > now)
}

/// An inactive session row holding \`marker\`, encrypted: the pending login's reservation.
async fn insert_pending<'e>(
    state: &AppState,
    db: impl crate::db::DbExec<'e>,
    member_id: i64,
    marker: &str,
) -> AppResult<AuthAttempt> {
    let encrypted_state = state.crypto.encrypt(marker)?;
    let now = state.clock.now();
    let session_id: i64 = crate::db::query_scalar(
        "INSERT INTO sidecar_session (bank, member_id, session_state, last_validated_at, is_active, sync_status, \
         last_sync_life_ambiguous, created_at, updated_at) VALUES ($1, $2, $3, $4, false, 'IDLE', 0, $4, $4) \
         RETURNING id",
    )
    .bind(BANK.as_str())
    .bind(member_id)
    .bind(&encrypted_state)
    .bind(now)
    .fetch_one(db)
    .await?;
    Ok(AuthAttempt {
        session_id,
        encrypted_state,
    })
}

/// The member's reservation, if it is still the one \`attempt\` wrote and has not expired.
async fn current_attempt<'e>(
    state: &AppState,
    db: impl crate::db::DbExec<'e>,
    member_id: i64,
    attempt: &AuthAttempt,
) -> AppResult<SidecarSession> {
    let now = state.clock.now();
    Ok(
        session::find_by_id_for_update(db, attempt.session_id, member_id)
            .await?
            .filter(|s| {
                !s.active
                    && !s.is_sync_in_flight()
                    && is_current(s, now)
                    && s.session_state == attempt.encrypted_state
            })
            .ok_or_else(expired_attempt)?,
    )
}

/// Replaces whatever the member had with a fresh reservation.
async fn reserve_auth_attempt(state: &AppState, member_id: i64) -> AppResult<AuthAttempt> {
    let mut tx = state.db.begin().await?;
    members::lock_or_throw(&mut tx, member_id).await?;
    if let Some(existing) = session::find_for_update(&mut tx, BANK, member_id).await? {
        session::delete(&mut tx, existing.id).await?;
    }
    let marker = format!("{AUTH_START_PREFIX}{}", uuid::Uuid::new_v4());
    let attempt = insert_pending(state, &mut tx, member_id, &marker).await?;
    tx.commit().await?;
    Ok(attempt)
}

/// Starts a login. Sofidy always answers with a pending process; a session opened without a code
/// is stored at once, its status folded into the answer.
pub async fn initiate_auth(
    state: &AppState,
    associate_code: &str,
    password: &str,
    member_id: i64,
) -> AppResult<InitiateResponse> {
    let attempt = reserve_auth_attempt(state, member_id).await?;
    let result: SidecarInitiate = client(state)
        .post(
            "/initiate",
            &json!({ "associateCode": associate_code, "password": password }),
            AUTH_TIMEOUT,
            "Could not initiate Sofidy authentication",
            Some(SidecarErrorCode::InvalidCredentials),
        )
        .await?;
    if !result.mfa_required {
        let plain = non_blank(
            result.session_state,
            "Sofidy opened no session and asked for no code",
        )?;
        let status = store_session(state, member_id, plain, &attempt).await?;
        return Ok(InitiateResponse {
            process_id: None,
            mfa_required: false,
            mfa_type: None,
            is_active: status.is_active,
            sync_status: status.sync_status,
            last_sync_error: status.last_sync_error,
            last_sync_started_at: status.last_sync_started_at,
            last_sync_completed_at: status.last_sync_completed_at,
        });
    }
    let process_id = non_blank(
        result.process_id,
        "Sofidy asked for a code without giving an attempt to complete",
    )?;
    let mut tx = state.db.begin().await?;
    members::lock_or_throw(&mut tx, member_id).await?;
    let current = current_attempt(state, &mut tx, member_id, &attempt).await?;
    session::delete(&mut tx, current.id).await?;
    insert_pending(
        state,
        &mut tx,
        member_id,
        &format!("{AUTH_PROCESS_PREFIX}{process_id}"),
    )
    .await?;
    tx.commit().await?;
    Ok(InitiateResponse {
        process_id: Some(process_id),
        mfa_required: true,
        mfa_type: result.mfa_type,
        is_active: false,
        sync_status: SidecarSyncStatus::Idle,
        last_sync_error: None,
        last_sync_started_at: None,
        last_sync_completed_at: None,
    })
}

/// Finishes the login with the code from the inbox, then queues the import. Another member's
/// process, a replaced or expired one, or a disconnect is refused before the sidecar is called.
pub async fn complete_auth(
    state: &AppState,
    process_id: &str,
    code: &str,
    member_id: i64,
) -> AppResult<SessionStatusResponse> {
    let attempt = {
        let mut tx = state.db.begin().await?;
        members::lock_or_throw(&mut tx, member_id).await?;
        let now = state.clock.now();
        let pending = session::find_for_update(&mut tx, BANK, member_id)
            .await?
            .filter(|s| !s.active && !s.is_sync_in_flight() && is_current(s, now));
        let pending = match pending {
            Some(s)
                if !process_id.trim().is_empty()
                    && state.crypto.decrypt(&s.session_state)?
                        == format!("{AUTH_PROCESS_PREFIX}{process_id}") =>
            {
                s
            }
            _ => return Err(expired_attempt().into()),
        };
        tx.commit().await?;
        AuthAttempt {
            session_id: pending.id,
            encrypted_state: pending.session_state,
        }
    };
    let response: SidecarSessionState = client(state)
        .post(
            "/complete",
            &json!({ "processId": process_id, "code": code }),
            VALIDATION_TIMEOUT,
            "Could not complete Sofidy authentication",
            Some(SidecarErrorCode::MfaInvalid),
        )
        .await?;
    let plain = non_blank(response.session_state, "Sofidy did not return a session")?;
    store_session(state, member_id, plain, &attempt).await
}

/// The reservation, if still current, becomes the member's active session and its first import
/// is queued.
async fn store_session(
    state: &AppState,
    member_id: i64,
    plain_state: String,
    attempt: &AuthAttempt,
) -> AppResult<SessionStatusResponse> {
    let mut tx = state.db.begin().await?;
    members::lock_or_throw(&mut tx, member_id).await?;
    current_attempt(state, &mut tx, member_id, attempt).await?;
    let job = template()
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

async fn fetch_snapshot(state: &AppState, plain_state: &str) -> Result<Snapshot, SyncError> {
    let snapshot: Snapshot = client(state)
        .post(
            "/positions",
            &json!({ "sessionState": plain_state }),
            POSITIONS_TIMEOUT,
            "Could not fetch the Sofidy portfolio",
            // A bare 401 here can only mean the session died: the code was checked at login.
            Some(SidecarErrorCode::SessionExpired),
        )
        .await?;
    if !snapshot.snapshot_complete {
        // The sidecar refuses lines that miss Sofidy's printed total, so an incomplete flag
        // means the read is not trustworthy either.
        return Err(error(
            SidecarErrorCode::PortfolioIncomplete,
            "Sofidy returned an incomplete portfolio",
        ));
    }
    Ok(snapshot)
}

/// Refuses a snapshot that cannot be written as it is; the funds it lists, validated.
fn validate(snapshot: &Snapshot) -> Result<Vec<&Holding>, SyncError> {
    if !snapshot.snapshot_complete {
        return Err(error(
            SidecarErrorCode::PortfolioIncomplete,
            "Sofidy returned an incomplete portfolio",
        ));
    }
    let Some(lines) = &snapshot.holdings else {
        return Err(error(
            SidecarErrorCode::PortfolioIncomplete,
            "Sofidy returned no fund holding",
        ));
    };
    let mut seen = HashSet::new();
    let mut holdings = Vec::with_capacity(lines.len());
    for line in lines {
        let Some(holding) = line
            .as_ref()
            .filter(|h| h.fund_code.is_some() && h.quantity.is_some())
        else {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "Sofidy returned an incomplete fund",
            ));
        };
        if !seen.insert(holding.fund_code.as_deref()) {
            // Two lines for one fund would write the quantity twice.
            return Err(error(
                SidecarErrorCode::InvalidData,
                "Sofidy returned a duplicate fund",
            ));
        }
        if holding.quantity.is_some_and(|q| q < Decimal::ZERO) {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "Sofidy returned a negative share count",
            ));
        }
        holdings.push(holding);
    }
    check_declared_total(snapshot, &holdings)?;
    // Every SCPI account is EUR and the figures are written as they come.
    if snapshot.currency.as_deref() != Some("EUR") {
        return Err(error(
            SidecarErrorCode::InvalidData,
            "Sofidy returned a portfolio not in euros",
        ));
    }
    Ok(holdings)
}

/// The funds against the total the snapshot declares, repeating the sidecar's check because a
/// stale sidecar image is what would skip it; a cent of rounding is tolerated.
fn check_declared_total(snapshot: &Snapshot, holdings: &[&Holding]) -> Result<(), SyncError> {
    let declared = snapshot
        .total_eur
        .filter(|d| *d >= Decimal::ZERO)
        .ok_or_else(|| {
            error(
                SidecarErrorCode::PortfolioIncomplete,
                "Sofidy returned no valid portfolio total",
            )
        })?;
    let computed: Decimal = holdings.iter().filter_map(|h| h.withdrawal_value()).sum();
    if (computed - declared).abs() > Decimal::new(1, 2) {
        return Err(error(
            SidecarErrorCode::PortfolioIncomplete,
            format!(
                "The Sofidy funds add up to {} but the snapshot declares {}",
                set_scale(computed, 2, HALF_UP),
                set_scale(declared, 2, HALF_UP)
            ),
        ));
    }
    Ok(())
}

pub struct Strategy;

#[async_trait]
impl SyncStrategy for Strategy {
    type Prepared = Snapshot;

    async fn fetch(&self, state: &AppState, job: &SyncJob) -> JobResult<Snapshot> {
        Ok(fetch_snapshot(state, &job.plain_state).await?)
    }

    /// Sofidy quotes no subscription price, so a price typed by hand survives; a linked fund a
    /// complete snapshot no longer lists was sold and is closed at zero shares, its account kept.
    async fn commit(
        &self,
        state: &AppState,
        conn: &mut DbHandle<'_>,
        _session: &mut SidecarSession,
        job: &SyncJob,
        snapshot: &Snapshot,
        synced_at: DateTime<Utc>,
    ) -> JobResult<()> {
        let holdings = validate(snapshot)?;
        let funds: Vec<SyncedFund> = holdings
            .iter()
            .map(|h| SyncedFund {
                fund_code: h.fund_code.clone().unwrap_or_default(),
                label: h.label.clone(),
                quantity: h.quantity.unwrap_or_default(),
                subscription_price: None,
                withdrawal_price: h.withdrawal_price,
            })
            .collect();
        let seam = &state.connectors.seams.scpi_funds;
        let outcomes = seam
            .reconcile(conn, job.member_id, ScpiProvider::Sofidy, &funds, synced_at)
            .await?;
        let listed: HashSet<String> = funds.iter().map(|f| f.fund_code.clone()).collect();
        seam.close_unlisted(
            conn,
            job.member_id,
            ScpiProvider::Sofidy,
            &listed,
            synced_at,
        )
        .await?;
        let described: Vec<String> = outcomes
            .iter()
            .map(|(code, outcome)| format!("{code}={outcome}"))
            .collect();
        tracing::info!(
            "Sofidy fund outcomes (member={}; funds={{{}}})",
            job.member_id,
            described.join(", ")
        );
        Ok(())
    }

    fn describe(&self, snapshot: &Snapshot) -> String {
        format!("funds={}", snapshot.holdings.as_ref().map_or(0, Vec::len))
    }
}
