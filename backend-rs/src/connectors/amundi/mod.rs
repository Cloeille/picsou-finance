//! AmundiController and AmundiSyncService (track T6): captcha-gated login with a mandatory second
//! factor (an e-mailed code or an app push), then a queued import of the employee savings plans.

use std::sync::LazyLock;
use std::time::Duration;

use crate::db::DbHandle;
use async_trait::async_trait;
use axum::Json as AxumJson;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, Utc};
use regex::Regex;
use serde::Deserialize;
use serde_json::json;

use super::sidecar::flow::{self, Login};
use super::sidecar::plans::{PlanData, PlanRules, PreparedPlan};
use super::sidecar::portfolio::{self, PortfolioAccount};
use super::sidecar::web::consume;
use super::sidecar::{
    AuthInitResponse, JobResult, SessionStatusResponse, SidecarClient, SidecarSession, SyncJob,
    SyncStrategy, SyncTemplate,
};
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;
use crate::ledger::members;
use crate::ledger::model::AccountType;
use crate::ledger::providers::AMUNDI as PROVIDER;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode, SourceSyncResult};
use crate::routes::Routes;

const BANK: SidecarBank = SidecarBank::Amundi;
const ACCOUNT_COLOR: &str = "#8b5cf6";
const AUTH_RATE_LIMITED: &str =
    "Too many Amundi authentication attempts. Please wait before retrying.";
const SYNC_RATE_LIMITED: &str =
    "Too many Amundi synchronization requests. Please wait before retrying.";
/// An app push waits on a human unlocking their phone; the sidecar caps that wait at 120 s.
const VALIDATION_TIMEOUT: Duration = Duration::from_secs(150);
const POSITIONS_TIMEOUT: Duration = Duration::from_secs(90);
const LOGIN: Login = Login {
    timeout: Duration::from_secs(45),
    initiate_message: "Could not initiate Amundi authentication",
    complete_message: "Could not complete Amundi authentication",
    no_session: "Amundi did not return a session",
    complete_failure: Some(SidecarErrorCode::InvalidOtp),
};

static SIX_DIGITS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\d{6}$").expect("regex"));

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/amundi/auth/initiate", initiate)
        .post("/api/amundi/auth/complete", complete)
        .post("/api/amundi/sync", sync_now)
        .get("/api/amundi/status", status)
        .delete("/api/amundi/session", clear);
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

/// `code` is absent for an app push: there is nothing to type, so it is checked only when present.
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
        e.pattern_default("code", code, &SIX_DIGITS, r"\d{6}");
    }
}

fn client(state: &AppState) -> SidecarClient {
    SidecarClient::new(state, BANK, SidecarErrorCode::AuthAttemptExpired)
}

fn template() -> SyncTemplate<Strategy> {
    SyncTemplate::new(BANK, Strategy, false)
}

async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<InitiateRequest>,
) -> AppResult<AxumJson<AuthInitResponse>> {
    consume(&state, &policies::AMUNDI_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let body = json!({ "login": req.login, "password": req.password });
    let response = flow::initiate(
        &state,
        &client(&state),
        &template(),
        &LOGIN,
        &body,
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
    consume(&state, &policies::AMUNDI_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let body = json!({ "processId": req.process_id, "code": req.code });
    let session = flow::complete(
        &state,
        &client(&state),
        &template(),
        &LOGIN,
        VALIDATION_TIMEOUT,
        &body,
        auth.member_id,
    )
    .await?;
    Ok(AxumJson(SessionStatusResponse::of(&session)))
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
        .resync_reporting(
            member_id,
            "amundi",
            get_status(state, member_id),
            || async { queue_sync(state, member_id).await.map(|_| ()) },
        )
        .await
}

const RULES: PlanRules = PlanRules {
    label: "Amundi",
    plan_word: "savings plan",
    external_id_prefix: "amundi_",
    default_name: "Amundi",
    fund_code_prefix: None,
    drops_empty_merged_lines: true,
    allows_no_plan: false,
    hashes_label_fallback: false,
};

pub struct Strategy;

#[async_trait]
impl SyncStrategy for Strategy {
    type Prepared = Vec<PreparedPlan>;

    async fn fetch(&self, state: &AppState, job: &SyncJob) -> JobResult<Vec<PreparedPlan>> {
        let fetched: Vec<Option<PlanData>> = client(state)
            .post_for_list(
                "/positions",
                &json!({ "sessionState": job.plain_state }),
                POSITIONS_TIMEOUT,
                "Could not fetch Amundi savings plans",
                SidecarErrorCode::PortfolioIncomplete,
                &RULES.no_plan_message(),
            )
            .await?;
        Ok(RULES.prepare(fetched)?)
    }

    async fn commit(
        &self,
        state: &AppState,
        conn: &mut DbHandle<'_>,
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
        for plan in prepared {
            let account = PortfolioAccount {
                external_id: &plan.external_id,
                name: &plan.name,
                account_type: AccountType::EmployeeSavings,
                provider: PROVIDER,
                color: ACCOUNT_COLOR,
                balance_eur: plan.balance_eur,
                cash_balance: None,
                invested_eur: plan.invested_amount_eur,
            };
            portfolio::write(
                state,
                conn,
                job.member_id,
                &account,
                &plan.holdings(synced_at),
                synced_at,
            )
            .await?;
        }
        Ok(())
    }

    fn describe(&self, prepared: &Vec<PreparedPlan>) -> String {
        format!("plans={}", prepared.len())
    }
}
