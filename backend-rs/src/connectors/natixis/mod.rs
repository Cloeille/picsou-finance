//! NatixisController and NatixisSyncService (track T6): Natixis Interépargne login (always a code),
//! then a queued import of the employee savings plans and their buy/sell operations. The sidecar
//! may hand back a refreshed session state with each import.

use std::collections::{BTreeMap, HashSet};
use std::sync::LazyLock;
use std::time::Duration;

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use async_trait::async_trait;
use axum::Json as AxumJson;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, NaiveDate, Utc};
use http::Method;
use regex::Regex;
use rust_decimal::Decimal;
use serde::Deserialize;
use serde_json::json;

use super::sidecar::flow::non_blank;
use super::sidecar::pending::{Pending, PendingLogins};
use super::sidecar::plans::{PlanData, PlanRules, PreparedPlan};
use super::sidecar::portfolio::{self, PortfolioAccount};
use super::sidecar::session;
use super::sidecar::template::error;
use super::sidecar::web::{SidecarInitiate, SidecarSessionState, consume};
use super::sidecar::{
    AuthInitResponse, JobResult, SessionStatusResponse, SidecarClient, SidecarHttp, SidecarSession,
    SyncError, SyncJob, SyncStrategy, SyncTemplate,
};
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;
use crate::ledger::accounts;
use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::members;
use crate::ledger::model::{AccountType, NewTransaction, TransactionType};
use crate::ledger::providers::NATIXIS as PROVIDER;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode, SourceSyncResult};
use crate::ledger::sync_values::{clean, limit};
use crate::ledger::transactions;
use crate::routes::Routes;

const BANK: SidecarBank = SidecarBank::Natixis;
const EXTERNAL_ID_PREFIX: &str = "natixis_";
const ACCOUNT_COLOR: &str = "#8b5cf6";
const PENDING_ATTEMPT_SECONDS: i64 = 600;
/// A job's row is touched at every step; one untouched this long has no live worker.
const STALE_SYNC_AFTER_MINUTES: i64 = 10;
const SETTLEMENT_MARGIN_DAYS: u64 = 90;
const AUTH_TIMEOUT: Duration = Duration::from_secs(45);
const POSITIONS_TIMEOUT: Duration = Duration::from_secs(120);
const CANCEL_TIMEOUT: Duration = Duration::from_secs(10);
const AUTH_RATE_LIMITED: &str =
    "Too many Natixis Interépargne authentication attempts. Please wait before retrying.";
const SYNC_RATE_LIMITED: &str =
    "Too many Natixis Interépargne synchronization requests. Please wait before retrying.";
const RULES: PlanRules = PlanRules {
    label: "Natixis",
    plan_word: "plan",
    external_id_prefix: EXTERNAL_ID_PREFIX,
    default_name: "Épargne salariale",
    fund_code_prefix: Some("NATIXIS-"),
    drops_empty_merged_lines: false,
    allows_no_plan: true,
    hashes_label_fallback: true,
};

static CODE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\d{4,12}$").expect("regex"));

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/natixis/auth/initiate", initiate)
        .post("/api/natixis/auth/complete", complete)
        .post("/api/natixis/sync", sync_now)
        .get("/api/natixis/status", status)
        .delete("/api/natixis/session", clear);
}

#[derive(Debug, Deserialize)]
struct InitiateRequest {
    email: Option<String>,
    password: Option<String>,
}

impl Validate for InitiateRequest {
    fn validate(&self, e: &mut Errors) {
        let (email, password) = (self.email.as_deref(), self.password.as_deref());
        e.not_blank("email", email).size("email", email, 0, 254);
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
        e.not_null("code", self.code.as_ref())
            .pattern_default("code", code, &CODE, r"\d{4,12}");
    }
}

fn client(state: &AppState) -> SidecarClient {
    SidecarClient::new(state, BANK, SidecarErrorCode::AuthAttemptExpired)
}

fn template() -> SyncTemplate<Strategy> {
    SyncTemplate::new(BANK, Strategy, false).with_queue_label("Natixis")
}

fn logins(state: &AppState) -> &PendingLogins {
    &state.connectors.natixis_logins
}

async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<InitiateRequest>,
) -> AppResult<AxumJson<AuthInitResponse>> {
    consume(&state, &policies::NATIXIS_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let member_id = auth.member_id;
    let generation = logins(&state).generation(member_id);
    let result: SidecarInitiate = client(&state)
        .post(
            "/initiate",
            &json!({ "email": req.email, "password": req.password }),
            AUTH_TIMEOUT,
            "Could not initiate Natixis Interépargne authentication",
            Some(SidecarErrorCode::InvalidCredentials),
        )
        .await?;
    let process_id = match result
        .process_id
        .filter(|p| result.mfa_required && !p.trim().is_empty())
    {
        Some(process_id) => process_id,
        None => {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "Natixis did not return a login attempt",
            )
            .into());
        }
    };
    let now = state.clock.now();
    logins(&state).evict_expired(now);
    let removed_meanwhile = {
        let _write = logins(&state).write_lock().await;
        let removed = logins(&state).generation(member_id) != generation;
        if !removed {
            logins(&state).put(
                process_id.clone(),
                Pending {
                    login: String::new(),
                    password: String::new(),
                    expires_at: now + chrono::Duration::seconds(PENDING_ATTEMPT_SECONDS),
                    member_id,
                    generation,
                },
            );
        }
        removed
    };
    if removed_meanwhile {
        cancel_auth(&state, &process_id).await;
        return Err(error(
            SidecarErrorCode::AuthAttemptExpired,
            "The Natixis connection was removed during the login",
        )
        .into());
    }
    Ok(AxumJson(AuthInitResponse {
        process_id: Some(process_id),
        mfa_required: true,
        mfa_type: result.mfa_type,
    }))
}

async fn complete(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<CompleteRequest>,
) -> AppResult<AxumJson<SessionStatusResponse>> {
    consume(&state, &policies::NATIXIS_COMPLETE, &ip, AUTH_RATE_LIMITED)?;
    let member_id = auth.member_id;
    let process_id = req.process_id.unwrap_or_default();
    let attempt = logins(&state)
        .get(&process_id)
        .filter(|p| p.member_id == member_id && p.expires_at > state.clock.now())
        .ok_or_else(|| {
            error(
                SidecarErrorCode::AuthAttemptExpired,
                "Natixis authentication attempt expired",
            )
        })?;
    let response: SidecarSessionState = client(&state)
        .post(
            "/complete",
            &json!({ "processId": process_id, "code": req.code }),
            AUTH_TIMEOUT,
            "Could not complete Natixis Interépargne authentication",
            Some(SidecarErrorCode::InvalidOtp),
        )
        .await?;
    logins(&state).remove(&process_id);
    let plain_state = non_blank(response.session_state, "Natixis did not return a session")?;
    let job = {
        let _write = logins(&state).write_lock().await;
        if logins(&state).generation(member_id) != attempt.generation {
            return Err(error(
                SidecarErrorCode::AuthAttemptExpired,
                "The Natixis connection was removed during the login",
            )
            .into());
        }
        let mut tx = state.db.begin().await?;
        let job = template()
            .insert_queued(
                &state,
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
    let status = get_status(&state, member_id).await?;
    template().submit(&state, job)?;
    Ok(AxumJson(status))
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
    let dropped = {
        let _write = logins(&state).write_lock().await;
        let dropped = logins(&state).member_process_ids(auth.member_id);
        logins(&state).forget_member(auth.member_id);
        template().clear(&state, auth.member_id).await?;
        dropped
    };
    for process_id in dropped {
        cancel_auth(&state, &process_id).await;
    }
    Ok(StatusCode::NO_CONTENT)
}

/// Best effort: the sidecar drops its pending browser; a failure only costs it a timeout.
async fn cancel_auth(state: &AppState, process_id: &str) {
    let http = SidecarHttp::new(state, BANK.service_name(), BANK.label());
    let outcome = http
        .send(
            Method::POST,
            "/cancel",
            Some(&json!({ "processId": process_id })),
            CANCEL_TIMEOUT,
        )
        .await;
    match outcome {
        Ok(response) if response.is_success() => {}
        Ok(response) => tracing::warn!(
            "Could not cancel a pending Natixis Interépargne login (status {})",
            response.status.as_u16()
        ),
        Err(e) => tracing::warn!("Could not cancel a pending Natixis Interépargne login ({e:?})"),
    }
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

/// NatixisSyncRecovery, at startup: every in-flight row left by the previous process.
pub async fn recover_interrupted_syncs(state: &AppState) -> sqlx::Result<u64> {
    let now = state.clock.now();
    recover_before(state, now).await
}

/// The five-minute sweep (track T8): rows no live job touched for ten minutes.
pub async fn recover_stale_syncs(state: &AppState) -> sqlx::Result<u64> {
    let stale_before = state.clock.now() - chrono::Duration::minutes(STALE_SYNC_AFTER_MINUTES);
    recover_before(state, stale_before).await
}

async fn recover_before(state: &AppState, stale_before: DateTime<Utc>) -> sqlx::Result<u64> {
    let recovered =
        session::mark_interrupted_failed_before(&state.db, BANK, state.clock.now(), stale_before)
            .await?;
    if recovered > 0 {
        tracing::warn!("Recovered {recovered} interrupted Natixis sync job(s)");
    }
    Ok(recovered)
}

/// The daily scheduler's entry (track T8).
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    template()
        .resync_reporting(
            member_id,
            "natixis",
            get_status(state, member_id),
            || async { queue_sync(state, member_id).await.map(|_| ()) },
        )
        .await
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Operation {
    external_id: Option<String>,
    plan_external_id: Option<String>,
    date: Option<String>,
    #[serde(rename = "type")]
    kind: Option<String>,
    label: Option<String>,
    fund_label: Option<String>,
    fund_code: Option<String>,
    quantity: Option<Decimal>,
    unit_value: Option<Decimal>,
    amount_eur: Option<Decimal>,
    fees_eur: Option<Decimal>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PositionsData {
    plans: Option<Vec<Option<PlanData>>>,
    session_state: Option<String>,
    operations_complete: Option<bool>,
    operations: Option<Vec<Option<Operation>>>,
    operations_from: Option<String>,
}

pub struct Prepared {
    plans: Vec<PreparedPlan>,
    operations: Option<OperationsHistory>,
}

struct OperationsHistory {
    operations: Vec<PreparedOperation>,
    deletable_from: Option<NaiveDate>,
}

struct PreparedOperation {
    external_id: String,
    plan_external_id: String,
    date: NaiveDate,
    tx_type: TransactionType,
    description: String,
    name: String,
    ticker: String,
    quantity: Decimal,
    unit_value: Decimal,
    fees: Option<Decimal>,
    amount: Decimal,
}

pub struct Strategy;

#[async_trait]
impl SyncStrategy for Strategy {
    type Prepared = Prepared;

    async fn fetch(&self, state: &AppState, job: &SyncJob) -> JobResult<Prepared> {
        let fetched: Option<PositionsData> = {
            let client = client(state);
            let message = "Could not fetch Natixis Interépargne plans";
            let response = client
                .post_raw(
                    "/positions",
                    &json!({ "sessionState": job.plain_state }),
                    POSITIONS_TIMEOUT,
                )
                .await
                .map_err(|e| client.map_call_error(message, e))?;
            if !response.is_success() {
                return Err(client
                    .map_status(message, &response, Some(SidecarErrorCode::SessionExpired))
                    .into());
            }
            client.decode(message, &response)?
        };
        let fetched = fetched
            .ok_or_else(|| error(SidecarErrorCode::InvalidData, "Natixis returned no answer"))?;
        store_refreshed_state(state, job, fetched.session_state.as_deref()).await?;
        let plans = fetched.plans.clone().ok_or_else(|| {
            error(
                SidecarErrorCode::PortfolioIncomplete,
                "Natixis returned no complete plan",
            )
        })?;
        let plans = RULES.prepare(plans)?;
        Ok(Prepared {
            plans,
            operations: prepare_operations(&fetched),
        })
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
        let imported = accounts::find_all_by_member_and_provider_for_update(
            &mut *conn,
            job.member_id,
            PROVIDER,
        )
        .await?;
        if members::find_by_id(&mut *conn, job.member_id)
            .await?
            .is_none()
        {
            return Err(AppError::NotFound("Family member not found".to_owned()).into());
        }
        let listed: HashSet<&str> = prepared
            .plans
            .iter()
            .map(|p| p.external_id.as_str())
            .collect();
        for plan in &prepared.plans {
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
            portfolio::write(
                state,
                conn,
                job.member_id,
                &data,
                &plan.holdings(synced_at),
                synced_at,
            )
            .await?;
        }
        for account in &imported {
            let Some(external_id) = account.external_account_id.as_deref() else {
                continue;
            };
            if !external_id.starts_with(EXTERNAL_ID_PREFIX)
                || listed.contains(external_id)
                || account.current_balance.is_zero()
            {
                continue;
            }
            tracing::info!(
                "Natixis no longer lists an imported plan, closing it at zero (member={}; account={})",
                job.member_id,
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
        if let Some(operations) = &prepared.operations {
            reconcile_operations(state, conn, operations, job.member_id).await?;
        }
        Ok(())
    }

    fn describe(&self, prepared: &Prepared) -> String {
        format!("plans={}", prepared.plans.len())
    }
}

/// Keeps the renewed refresh token the sidecar handed back, even if the import then fails.
async fn store_refreshed_state(
    state: &AppState,
    job: &SyncJob,
    plain: Option<&str>,
) -> JobResult<()> {
    let Some(plain) = plain.filter(|s| !s.trim().is_empty()) else {
        tracing::warn!(
            "Natixis returned no session state, keeping the stored one (member={})",
            job.member_id
        );
        return Ok(());
    };
    let encrypted = state.crypto.encrypt(plain)?;
    let mut tx = state.db.begin().await?;
    if let Some(mut current) =
        session::find_by_id_for_update(&mut tx, job.session_id, job.member_id).await?
        && current.active
    {
        let now = state.clock.now();
        current.update_session_state(encrypted, now);
        session::save(&mut tx, &current, now).await?;
    }
    tx.commit().await?;
    Ok(())
}

/// The operations history, or None when it is missing or unreadable: the stored one is kept.
fn prepare_operations(fetched: &PositionsData) -> Option<OperationsHistory> {
    if fetched.operations_complete != Some(true) {
        return None;
    }
    let operations = fetched.operations.as_ref()?;
    let mut prepared = Vec::with_capacity(operations.len());
    for operation in operations {
        match prepare_operation(operation.as_ref()) {
            Ok(op) => prepared.push(op),
            Err(_) => {
                tracing::warn!(
                    "Natixis returned an unreadable operations history, keeping the stored one"
                );
                return None;
            }
        }
    }
    let deletable_from = fetched
        .operations_from
        .as_deref()
        .and_then(|from| from.parse::<NaiveDate>().ok())
        .and_then(|from| from.checked_add_days(chrono::Days::new(SETTLEMENT_MARGIN_DAYS)));
    Some(OperationsHistory {
        operations: prepared,
        deletable_from,
    })
}

fn prepare_operation(operation: Option<&Operation>) -> Result<PreparedOperation, SyncError> {
    let incomplete = || {
        error(
            SidecarErrorCode::InvalidData,
            "Natixis returned an incomplete operation",
        )
    };
    let operation = operation.ok_or_else(incomplete)?;
    let quantity = operation
        .quantity
        .filter(|q| *q > Decimal::ZERO)
        .ok_or_else(incomplete)?;
    let amount_eur = operation
        .amount_eur
        .filter(|a| !a.is_sign_negative() || a.is_zero())
        .ok_or_else(incomplete)?;
    if operation.fees_eur.is_some_and(|f| f < Decimal::ZERO) {
        return Err(incomplete());
    }
    let unidentified = || {
        error(
            SidecarErrorCode::InvalidData,
            "Natixis returned an operation without identifiers",
        )
    };
    let external_id = clean(operation.external_id.as_deref())
        .map(|id| {
            if id.starts_with(EXTERNAL_ID_PREFIX) {
                id
            } else {
                format!("{EXTERNAL_ID_PREFIX}{id}")
            }
        })
        .filter(|id| id.encode_utf16().count() <= 100)
        .ok_or_else(unidentified)?;
    let plan_id = clean(operation.plan_external_id.as_deref()).ok_or_else(unidentified)?;
    let date = clean(operation.date.as_deref()).ok_or_else(unidentified)?;
    let tx_type = match operation.kind.as_deref() {
        Some("BUY") => TransactionType::Buy,
        Some("SELL") => TransactionType::Sell,
        _ => {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "Natixis returned an unknown operation type",
            ));
        }
    };
    let ticker = RULES.operation_ticker(
        operation.fund_code.as_deref(),
        operation.fund_label.as_deref(),
    )?;
    let fees = operation.fees_eur;
    let net = fees.unwrap_or(Decimal::ZERO);
    let unit_value = operation
        .unit_value
        .unwrap_or_else(|| divide(amount_eur, quantity, 8, HALF_UP));
    let amount = if tx_type == TransactionType::Buy {
        -(amount_eur + net)
    } else {
        amount_eur - net
    };
    let date: NaiveDate = date.parse().map_err(|_| incomplete())?;
    let fallback = if tx_type == TransactionType::Buy {
        "Investissement"
    } else {
        "Désinvestissement"
    };
    Ok(PreparedOperation {
        external_id,
        plan_external_id: if plan_id.starts_with(EXTERNAL_ID_PREFIX) {
            plan_id
        } else {
            format!("{EXTERNAL_ID_PREFIX}{plan_id}")
        },
        date,
        tx_type,
        description: limit(operation.label.as_deref(), 255, fallback),
        name: limit(operation.fund_label.as_deref(), 100, &ticker),
        ticker,
        quantity,
        unit_value,
        fees,
        amount,
    })
}

/// Upserts the reported operations by external id; a stored one the history no longer lists is
/// deleted only past the settlement margin of the history's start.
async fn reconcile_operations(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    history: &OperationsHistory,
    member_id: i64,
) -> JobResult<()> {
    let mut by_account: BTreeMap<String, (i64, Vec<&PreparedOperation>)> = BTreeMap::new();
    for account in
        accounts::find_all_by_member_and_provider(&mut *conn, member_id, PROVIDER).await?
    {
        if let Some(id) = account
            .external_account_id
            .filter(|id| id.starts_with(EXTERNAL_ID_PREFIX))
        {
            by_account.insert(id, (account.id, Vec::new()));
        }
    }
    let mut unmatched = 0;
    for operation in &history.operations {
        match by_account.get_mut(&operation.plan_external_id) {
            Some((_, reported)) => {
                reported.retain(|r| r.external_id != operation.external_id);
                reported.push(operation);
            }
            None => unmatched += 1,
        }
    }
    if unmatched > 0 {
        tracing::info!(
            "Natixis skipped {unmatched} operation(s) of plans without an account (member={member_id})"
        );
    }
    for (account_id, mut reported) in by_account.into_values() {
        let mut stored_by_id = BTreeMap::new();
        for row in transactions::find_synced_by_account(&mut *conn, account_id, None).await? {
            let Some(external_id) = row
                .external_id
                .clone()
                .filter(|id| id.starts_with(EXTERNAL_ID_PREFIX))
            else {
                continue;
            };
            if reported.iter().any(|r| r.external_id == external_id) {
                stored_by_id.insert(external_id, row);
            } else if history.deletable_from.is_some_and(|from| row.date >= from) {
                transactions::delete(&mut *conn, row.id).await?;
            }
        }
        reported.sort_by_key(|op| (op.date, op.tx_type == TransactionType::Sell));
        for operation in reported {
            match stored_by_id.remove(&operation.external_id) {
                Some(mut row) => {
                    row.date = operation.date;
                    row.tx_type = Some(operation.tx_type);
                    row.description = operation.description.clone();
                    row.name = Some(operation.name.clone());
                    row.ticker = Some(operation.ticker.clone());
                    row.quantity = Some(operation.quantity);
                    row.price_per_unit = Some(operation.unit_value);
                    row.fees = operation.fees;
                    row.amount = operation.amount;
                    row.native_currency = "EUR".to_owned();
                    row.budget_neutral = true;
                    transactions::update(&mut *conn, &row).await?;
                }
                None => {
                    let mut row = NewTransaction::new(
                        account_id,
                        operation.date,
                        operation.description.clone(),
                        operation.amount,
                    );
                    row.external_id = Some(operation.external_id.clone());
                    row.is_manual = false;
                    row.tx_type = Some(operation.tx_type);
                    row.name = Some(operation.name.clone());
                    row.ticker = Some(operation.ticker.clone());
                    row.quantity = Some(operation.quantity);
                    row.price_per_unit = Some(operation.unit_value);
                    row.fees = operation.fees;
                    row.native_currency = "EUR".to_owned();
                    row.budget_neutral = true;
                    transactions::insert(&mut *conn, &row, state.clock.now()).await?;
                }
            }
        }
    }
    Ok(())
}
