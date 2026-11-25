//! DegiroController and DegiroSyncService (track T6): login with an optional TOTP code, then a
//! synchronous portfolio import. The DEGIRO sidecar does not speak the detail-code contract, so
//! every failure is a SyncException with a fixed message and no code.

use std::collections::HashMap;
use std::sync::LazyLock;
use std::time::Duration;

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use axum::Json as AxumJson;
use axum::extract::State;
use axum::http::StatusCode;
use chrono::{DateTime, Utc};
use http::Method;
use regex::Regex;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use super::sidecar::client::CallError;
use super::sidecar::web::consume;
use super::sidecar::{SidecarHttp, SyncError};
use crate::AppState;
use crate::auth::Auth;
use crate::db::{self, DbExec, DbFromRow};
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::holding_dedup::{HoldingAgg, vwap_merge};
use crate::ledger::model::{AccountType, HoldingDraft, normalize_isin};
use crate::ledger::sidecar::{SourceSyncResult, SyncStatus};
use crate::ledger::valuation::{self, SnapshotContext};
use crate::ledger::{holdings, members};
use crate::routes::Routes;

const EXTERNAL_ACCOUNT_ID: &str = "degiro-portfolio";
const PROVIDER: &str = "DEGIRO";
const COLOR: &str = "#f97316";
const SIDECAR_TIMEOUT: Duration = Duration::from_secs(30);
const NO_RESPONSE: &str = "No response from the DEGIRO service. Please try again later.";
const EXPIRED: &str = "Your DEGIRO session has expired. Please reconnect from the DEGIRO page.";
const RATE_LIMITED: &str = "Too many DEGIRO authentication attempts. Please wait before retrying.";

static SIX_DIGITS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\d{6}$").expect("regex"));

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/degiro/auth/initiate", initiate)
        .post("/api/degiro/auth/complete", complete)
        .post("/api/degiro/sync", sync_now)
        .get("/api/degiro/status", status)
        .delete("/api/degiro/session", clear);
}

#[derive(Debug, Deserialize)]
struct InitiateRequest {
    username: Option<String>,
    password: Option<String>,
}

impl Validate for InitiateRequest {
    fn validate(&self, e: &mut Errors) {
        let (username, password) = (self.username.as_deref(), self.password.as_deref());
        e.not_blank("username", username)
            .size("username", username, 0, 100);
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
        e.not_blank("code", code)
            .pattern_default("code", code, &SIX_DIGITS, r"\d{6}");
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthInitResponse {
    process_id: Option<String>,
    totp_required: bool,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionStatusResponse {
    is_active: bool,
    status: Option<String>,
    last_synced_at: Option<DateTime<Utc>>,
}

#[derive(DbFromRow)]
struct DegiroSession {
    session_blob: String,
    status: String,
    last_synced_at: Option<DateTime<Utc>>,
}

async fn find_session<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Option<DegiroSession>> {
    db::query_as(
        "SELECT session_blob, status, last_synced_at FROM degiro_session WHERE member_id = $1",
    )
    .bind(member_id)
    .fetch_optional(db)
    .await
}

async fn set_status<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    status: &str,
    last_error: Option<&str>,
    synced_at: Option<DateTime<Utc>>,
    now: DateTime<Utc>,
) -> sqlx::Result<u64> {
    Ok(db::query(
        "UPDATE degiro_session SET status = $2, last_error = $3, \
         last_synced_at = COALESCE($4, last_synced_at), updated_at = $5 WHERE member_id = $1",
    )
    .bind(member_id)
    .bind(status)
    .bind(last_error)
    .bind(synced_at)
    .bind(now)
    .execute(db)
    .await?
    .rows_affected())
}

fn sidecar(state: &AppState) -> SidecarHttp {
    SidecarHttp::new(state, "degiro-auth", "DEGIRO")
}

/// The failure a DEGIRO call ends with: `rejected` for an error status, unless `on_401` says
/// what a 401 means.
enum Failure {
    Sync(SyncError),
    SessionExpired,
}

impl From<Failure> for AppError {
    fn from(failure: Failure) -> Self {
        match failure {
            Failure::Sync(e) => e.into(),
            Failure::SessionExpired => SyncError::uncoded(EXPIRED).into(),
        }
    }
}

async fn call(
    state: &AppState,
    path: &str,
    body: &Value,
    rejected: &str,
    expires_on_401: bool,
) -> Result<Value, Failure> {
    let response = match sidecar(state)
        .send(Method::POST, path, Some(body), SIDECAR_TIMEOUT)
        .await
    {
        Ok(response) => response,
        Err(CallError::Unauthorized(e)) => return Err(Failure::Sync(e)),
        Err(_) => return Err(Failure::Sync(SyncError::uncoded(NO_RESPONSE))),
    };
    if !response.is_success() {
        if expires_on_401 && response.status == StatusCode::UNAUTHORIZED {
            return Err(Failure::SessionExpired);
        }
        tracing::error!("degiro-auth {path} failed ({})", response.status.as_u16());
        return Err(Failure::Sync(SyncError::uncoded(rejected)));
    }
    match response.json::<Value>() {
        Ok(value) if !value.is_null() => Ok(value),
        Ok(_) => Err(Failure::Sync(SyncError::uncoded(NO_RESPONSE))),
        Err(_) => Err(Failure::Sync(SyncError::uncoded(rejected))),
    }
}

fn text(value: &Value, key: &str) -> Option<String> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
        .map(str::to_owned)
}

async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<InitiateRequest>,
) -> AppResult<AxumJson<AuthInitResponse>> {
    consume(&state, &policies::DEGIRO_AUTH, &ip, RATE_LIMITED)?;
    let response = call(
        &state,
        "/initiate",
        &json!({ "username": req.username, "password": req.password }),
        "DEGIRO authentication failed. Please check your credentials and try again.",
        false,
    )
    .await?;
    let process_id = text(&response, "processId").ok_or_else(|| {
        SyncError::uncoded("DEGIRO did not return a valid session. Please try again.")
    })?;
    if !response
        .get("totpRequired")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        let blob = text(&response, "sessionBlob").ok_or_else(|| {
            SyncError::uncoded("DEGIRO authentication did not complete. Please try again.")
        })?;
        store_session_and_sync(&state, &blob, auth.member_id).await?;
        return Ok(AxumJson(AuthInitResponse {
            process_id: None,
            totp_required: false,
        }));
    }
    Ok(AxumJson(AuthInitResponse {
        process_id: Some(process_id),
        totp_required: true,
    }))
}

async fn complete(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<CompleteRequest>,
) -> AppResult<AxumJson<SessionStatusResponse>> {
    consume(&state, &policies::DEGIRO_AUTH, &ip, RATE_LIMITED)?;
    let response = call(
        &state,
        "/complete",
        &json!({ "processId": req.process_id, "code": req.code }),
        "The verification code is invalid or has expired. Please request a new one.",
        false,
    )
    .await?;
    let blob = text(&response, "sessionBlob").ok_or_else(|| {
        SyncError::uncoded("DEGIRO verification did not complete. Please try again.")
    })?;
    Ok(AxumJson(
        store_session_and_sync(&state, &blob, auth.member_id).await?,
    ))
}

async fn sync_now(State(state): State<AppState>, auth: Auth) -> AppResult<AxumJson<Value>> {
    Ok(AxumJson(sync(&state, auth.member_id).await?))
}

async fn status(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<AxumJson<SessionStatusResponse>> {
    Ok(AxumJson(get_status(&state, auth.member_id).await?))
}

async fn clear(State(state): State<AppState>, auth: Auth) -> AppResult<StatusCode> {
    db::query("DELETE FROM degiro_session WHERE member_id = $1")
        .bind(auth.member_id)
        .execute(&state.db)
        .await?;
    tracing::info!("DEGIRO session cleared for member {}", auth.member_id);
    Ok(StatusCode::NO_CONTENT)
}

pub async fn get_status(state: &AppState, member_id: i64) -> AppResult<SessionStatusResponse> {
    Ok(match find_session(&state.db, member_id).await? {
        None => SessionStatusResponse {
            is_active: false,
            status: None,
            last_synced_at: None,
        },
        Some(session) => SessionStatusResponse {
            is_active: session.status == "ACTIVE",
            status: Some(session.status),
            last_synced_at: session.last_synced_at,
        },
    })
}

/// Stores the session, then imports at once; a failed first import still leaves it stored.
async fn store_session_and_sync(
    state: &AppState,
    plain_blob: &str,
    member_id: i64,
) -> AppResult<SessionStatusResponse> {
    let now = state.clock.now();
    let encrypted = state.crypto.encrypt(plain_blob)?;
    {
        let mut tx = state.db.begin().await?;
        if members::find_by_id(&mut tx, member_id).await?.is_none() {
            return Err(AppError::NotFound("Family member not found".to_owned()));
        }
        db::query(
            "INSERT INTO degiro_session (member_id, session_blob, status, last_error, created_at, updated_at) \
             VALUES ($1, $2, 'ACTIVE', NULL, $3, $3) ON CONFLICT (member_id) DO UPDATE SET \
             session_blob = EXCLUDED.session_blob, status = 'ACTIVE', last_error = NULL, updated_at = EXCLUDED.updated_at",
        )
        .bind(member_id)
        .bind(encrypted)
        .bind(now)
        .execute(&mut tx)
        .await?;
        tx.commit().await?;
    }
    tracing::info!("DEGIRO session stored for member {member_id}");

    // Run sidecar portfolio fetch and ISIN resolution completely outside any DB transaction so
    // SQLite's writer_gate is never held across external HTTP calls.
    match fetch_portfolio(state, plain_blob).await {
        Ok(data) => {
            let resolved = resolve_portfolio(state, &data, member_id).await?;
            let mut tx = state.db.begin().await?;
            if find_session(&mut tx, member_id).await?.is_some() {
                upsert_account(state, &mut tx.as_handle(), &resolved, member_id).await?;
                set_status(
                    &mut tx,
                    member_id,
                    "ACTIVE",
                    None,
                    Some(state.clock.now()),
                    state.clock.now(),
                )
                .await?;
            }
            tx.commit().await?;
        }
        Err(Failure::SessionExpired) => {
            tracing::warn!("DEGIRO session expired during the initial sync for member {member_id}");
            let mut tx = state.db.begin().await?;
            set_status(
                &mut tx,
                member_id,
                "REAUTH_REQUIRED",
                Some("SESSION_EXPIRED"),
                None,
                state.clock.now(),
            )
            .await?;
            tx.commit().await?;
        }
        Err(Failure::Sync(e)) => {
            tracing::warn!(
                "DEGIRO initial sync after auth failed for member {member_id}: {}",
                e.message
            );
        }
    }
    get_status(state, member_id).await
}

pub async fn sync(state: &AppState, member_id: i64) -> AppResult<Value> {
    let stored = find_session(&state.db, member_id).await?.ok_or_else(|| {
        SyncError::uncoded("No DEGIRO session. Please connect from the DEGIRO page.")
    })?;
    if stored.status == "REAUTH_REQUIRED" {
        return Err(SyncError::uncoded(EXPIRED).into());
    }
    let plain = state.crypto.decrypt(&stored.session_blob)?;
    let data = match fetch_portfolio(state, &plain).await {
        Ok(data) => data,
        Err(Failure::SessionExpired) => {
            tracing::warn!(
                "DEGIRO session expired for member {member_id} — marking REAUTH_REQUIRED"
            );
            if set_status(
                &state.db,
                member_id,
                "REAUTH_REQUIRED",
                Some("SESSION_EXPIRED"),
                None,
                state.clock.now(),
            )
            .await?
                == 0
            {
                tracing::error!(
                    "Cannot mark DEGIRO session for member {member_id} as REAUTH_REQUIRED: row not found"
                );
            }
            return Err(Failure::SessionExpired.into());
        }
        Err(e) => return Err(e.into()),
    };
    let resolved = resolve_portfolio(state, &data, member_id).await?;
    let mut tx = state.db.begin().await?;
    let account = upsert_account(state, &mut tx.as_handle(), &resolved, member_id).await?;
    set_status(
        &mut tx,
        member_id,
        "ACTIVE",
        None,
        Some(state.clock.now()),
        state.clock.now(),
    )
    .await?;
    tx.commit().await?;
    tracing::info!("DEGIRO sync complete for member {member_id}");
    state
        .connectors
        .seams
        .accounts
        .to_response(state, &account)
        .await
}

/// userSyncReporting, for the user-triggered sync of every source (track T8).
pub async fn user_sync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    let result = |status, message: &str| SourceSyncResult {
        source: "degiro".to_owned(),
        status,
        message: message.to_owned(),
    };
    match find_session(&state.db, member_id).await {
        Ok(None) => return result(SyncStatus::SkippedNotConnected, "No session"),
        Ok(Some(s)) if s.status == "REAUTH_REQUIRED" => {
            return result(SyncStatus::NeedsReauth, "Reauth required");
        }
        Ok(Some(s)) if s.status != "ACTIVE" => {
            return result(SyncStatus::SkippedNotConnected, "Session not active");
        }
        Ok(Some(_)) => {}
        Err(_) => return result(SyncStatus::Failed, "Unexpected sync error"),
    }
    match sync(state, member_id).await {
        Ok(_) => result(SyncStatus::Synced, ""),
        Err(AppError::Status { detail, .. }) if detail == EXPIRED => {
            result(SyncStatus::NeedsReauth, "Reauthentication required")
        }
        Err(e) => {
            tracing::error!("DEGIRO user sync failed for member {member_id}: {e}");
            result(SyncStatus::Failed, "Unexpected sync error")
        }
    }
}

struct Position {
    isin: Option<String>,
    symbol: String,
    name: String,
    quantity: Decimal,
    buying_price: Option<Decimal>,
    current_price: Option<Decimal>,
}

struct Portfolio {
    cash_eur: Decimal,
    positions: Vec<Position>,
}

/// Jackson's JsonNode.decimalValue(): a number as is, anything else zero.
fn decimal(node: Option<&Value>) -> Decimal {
    node.filter(|v| v.is_number())
        .and_then(|v| {
            v.to_string()
                .parse::<Decimal>()
                .ok()
                .or_else(|| Decimal::from_scientific(&v.to_string()).ok())
        })
        .unwrap_or(Decimal::ZERO)
}

async fn fetch_portfolio(state: &AppState, blob: &str) -> Result<Portfolio, Failure> {
    let response = call(
        state,
        "/portfolio",
        &json!({ "sessionBlob": blob }),
        "Could not fetch your DEGIRO portfolio. Please try again later.",
        true,
    )
    .await?;
    let as_text = |p: &Value, key: &str| match p.get(key) {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Null) | None => String::new(),
        Some(other) => other.to_string(),
    };
    let positions = response
        .get("positions")
        .and_then(Value::as_array)
        .map(|list| {
            list.iter()
                .map(|p| Position {
                    isin: Some(as_text(p, "isin")).filter(|s| !s.trim().is_empty()),
                    symbol: as_text(p, "symbol"),
                    name: as_text(p, "name"),
                    quantity: decimal(p.get("quantity")),
                    buying_price: Some(decimal(p.get("buyingPrice"))),
                    current_price: Some(decimal(p.get("currentPrice"))),
                })
                .collect()
        })
        .unwrap_or_default();
    Ok(Portfolio {
        cash_eur: decimal(response.get("cashEur")),
        positions,
    })
}

struct ResolvedPortfolio {
    cash_eur: Decimal,
    total: Decimal,
    deduped: HashMap<String, HoldingAgg>,
    isin_by_ticker: HashMap<String, String>,
}

async fn resolve_portfolio(
    state: &AppState,
    data: &Portfolio,
    member_id: i64,
) -> sqlx::Result<ResolvedPortfolio> {
    let mut deduped: HashMap<String, HoldingAgg> = HashMap::new();
    let mut isin_by_ticker: HashMap<String, String> = HashMap::new();
    for p in &data.positions {
        let (ticker, name) = state
            .connectors
            .seams
            .isin
            .resolve_isin_or_symbol(p.isin.as_deref(), Some(&p.symbol), Some(&p.name))
            .await;
        let next = HoldingAgg {
            quantity: p.quantity,
            average_buy_in: p.buying_price,
            current_price: p.current_price,
            name,
        };
        let ticker = ticker.unwrap_or_default();
        if let Some(isin) = normalize_isin(p.isin.as_deref()) {
            isin_by_ticker.entry(ticker.clone()).or_insert(isin);
        }
        let merged = match deduped.get(&ticker) {
            Some(prev) => vwap_merge(prev, &next),
            None => next,
        };
        deduped.insert(ticker, merged);
    }
    let positions_value: Decimal = deduped
        .values()
        .filter(|agg| !agg.quantity.is_zero())
        .map(|agg| agg.quantity * agg.current_price.unwrap_or(Decimal::ZERO))
        .sum();
    // The snapshot written with this portfolio values its holdings through the price seam:
    // asked here, outside any transaction, so the write finds the quotes in memory. The account
    // as stored too, with a ticker or a currency the member may have set.
    let mut needs =
        valuation::needs_of_external(&mut state.db.pool_handle(), member_id, EXTERNAL_ACCOUNT_ID)
            .await?;
    needs.account(
        AccountType::CompteTitres,
        "EUR",
        None,
        deduped
            .iter()
            .filter(|(_, agg)| !agg.quantity.is_zero())
            .map(|(ticker, _)| ticker.as_str()),
    );
    state
        .ledger
        .prices
        .prefetch(&needs, state.clock.today())
        .await;
    Ok(ResolvedPortfolio {
        cash_eur: data.cash_eur,
        total: data.cash_eur + positions_value,
        deduped,
        isin_by_ticker,
    })
}

async fn upsert_account(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    data: &ResolvedPortfolio,
    member_id: i64,
) -> AppResult<crate::ledger::model::Account> {
    let total = data.total;
    let now = state.clock.now();
    let account =
        match accounts::find_by_external_id_and_member(&mut *conn, EXTERNAL_ACCOUNT_ID, member_id)
            .await?
        {
            Some(mut account) => {
                account.current_balance = total;
                account.cash_balance = Some(data.cash_eur);
                account.last_synced_at = Some(now);
                accounts::update(&mut *conn, &account, now).await?
            }
            None => {
                if members::find_by_id(&mut *conn, member_id).await?.is_none() {
                    return Err(AppError::NotFound("Family member not found".to_owned()));
                }
                let mut draft = NewAccount::new(member_id, "DEGIRO", AccountType::CompteTitres);
                draft.provider = Some(PROVIDER.to_owned());
                draft.current_balance = total;
                draft.cash_balance = Some(data.cash_eur);
                draft.last_synced_at = Some(now);
                draft.external_account_id = Some(EXTERNAL_ACCOUNT_ID.to_owned());
                draft.is_manual = false;
                draft.color = COLOR.to_owned();
                accounts::insert(&mut *conn, &draft, now).await?
            }
        };
    let drafts: Vec<HoldingDraft> = data
        .deduped
        .iter()
        .filter(|(_, agg)| !agg.quantity.is_zero())
        .map(|(ticker, agg)| HoldingDraft {
            ticker: ticker.clone(),
            isin: data.isin_by_ticker.get(ticker).cloned(),
            name: agg.name.clone(),
            quantity: agg.quantity,
            average_buy_in: agg.average_buy_in,
            current_price: agg.current_price,
            last_synced_at: Some(now),
            ..HoldingDraft::default()
        })
        .collect();
    holdings::replace_all(&mut *conn, account.id, &drafts, now).await?;
    let ctx = SnapshotContext {
        prices: &*state.ledger.prices,
        today: state.clock.today(),
        now,
    };
    valuation::upsert_snapshot(&mut *conn, &ctx, &account, total, state.clock.today()).await?;
    Ok(account)
}
