//! CorumController and CorumSyncService (track T6): a CORUM client space, its real-estate funds
//! mapped onto SCPI accounts through the \`scpi_funds\` seam and each CORUM Life contract onto its
//! own account. CORUM asks for no second factor, so a login stores the session and queues the
//! import at once. With \`remember\`, the credentials are stored encrypted and a sync that finds
//! its session expired logs in again with them, once, before reading.
//!
//! The account deletion lock (lockMemberForAccountDeletion) is the family_member row lock this
//! sync takes, which is the ledger's default \`MemberRowLock\`.

pub mod adapter;
pub mod life;
pub mod operations;
pub mod scpi_funds;

use std::collections::{HashMap, HashSet};
use std::sync::{LazyLock, Mutex};

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use async_trait::async_trait;
use axum::Json as AxumJson;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use self::adapter::{Portfolio, Snapshot};
use self::life::Outcome;
use super::seams::{ScpiFundOutcome, ScpiProvider, SyncedFund};
use super::sidecar::flow::non_blank;
use super::sidecar::session;
use super::sidecar::template::{SessionView, error};
use super::sidecar::web::consume;
use super::sidecar::{
    JobResult, SidecarSession, SidecarSyncStatus, SyncError, SyncJob, SyncStrategy, SyncTemplate,
};
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate, null_as_false};
use crate::http::rate_limit::policies;
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::members;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode, SourceSyncResult};
use crate::routes::Routes;

const BANK: SidecarBank = SidecarBank::Corum;
const SOURCE: &str = "corum";
const AUTH_RATE_LIMITED: &str =
    "Too many CORUM authentication attempts. Please wait before retrying.";
const SYNC_RATE_LIMITED: &str =
    "Too many CORUM synchronization requests. Please wait before retrying.";

/// Bumped by a disconnect: a login still waiting on CORUM when the connection was removed carries
/// an older generation and stores nothing. Keyed by the app instance as well, since several apps
/// share one process in tests.
static GENERATIONS: LazyLock<Mutex<HashMap<(usize, i64), u64>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/corum/auth", authenticate_handler)
        .post("/api/corum/sync", sync_now)
        .get("/api/corum/status", status)
        .delete("/api/corum/session", clear);
}

/// \`login\` is a CORUM client id, not an e-mail address; \`remember\` (false when absent) stores
/// the credentials for the unattended re-login.
#[derive(Deserialize)]
struct AuthenticateRequest {
    login: Option<String>,
    password: Option<String>,
    #[serde(default, deserialize_with = "null_as_false")]
    remember: bool,
}

impl Validate for AuthenticateRequest {
    fn validate(&self, e: &mut Errors) {
        let (login, password) = (self.login.as_deref(), self.password.as_deref());
        e.not_blank("login", login).size("login", login, 0, 100);
        e.not_blank("password", password)
            .size("password", password, 0, 100);
    }
}

/// CorumSyncService.SessionStatusResponse: never the credentials, only whether they are stored.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusResponse {
    pub is_active: bool,
    pub sync_status: SidecarSyncStatus,
    pub last_sync_started_at: Option<DateTime<Utc>>,
    pub last_sync_completed_at: Option<DateTime<Utc>>,
    pub last_sync_error: Option<SidecarErrorCode>,
    /// CORUM Life contracts the last successful sync left unwritten (AMBIGUOUS).
    pub life_ambiguous_count: i32,
    pub credentials_remembered: bool,
}

impl StatusResponse {
    fn inactive() -> Self {
        Self {
            is_active: false,
            sync_status: SidecarSyncStatus::Idle,
            last_sync_started_at: None,
            last_sync_completed_at: None,
            last_sync_error: None,
            life_ambiguous_count: 0,
            credentials_remembered: false,
        }
    }

    fn of(session: &SidecarSession) -> Self {
        Self {
            is_active: session.active,
            sync_status: session.sync_status,
            last_sync_started_at: session.last_sync_started_at,
            last_sync_completed_at: session.last_sync_completed_at,
            last_sync_error: session.last_sync_error,
            life_ambiguous_count: session.last_sync_life_ambiguous,
            credentials_remembered: session.encrypted_credentials.is_some(),
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

fn template() -> SyncTemplate<Strategy> {
    SyncTemplate::new(BANK, Strategy, true)
}

fn generation_key(state: &AppState, member_id: i64) -> (usize, i64) {
    (std::ptr::from_ref(&state.connectors) as usize, member_id)
}

fn generations() -> std::sync::MutexGuard<'static, HashMap<(usize, i64), u64>> {
    GENERATIONS
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

fn generation(state: &AppState, member_id: i64) -> u64 {
    generations()
        .get(&generation_key(state, member_id))
        .copied()
        .unwrap_or(0)
}

async fn authenticate_handler(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<AuthenticateRequest>,
) -> AppResult<AxumJson<StatusResponse>> {
    consume(&state, &policies::CORUM_AUTH, &ip, AUTH_RATE_LIMITED)?;
    let response = authenticate(
        &state,
        &req.login.unwrap_or_default(),
        &req.password.unwrap_or_default(),
        req.remember,
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

async fn status(State(state): State<AppState>, auth: Auth) -> AppResult<AxumJson<StatusResponse>> {
    Ok(AxumJson(get_status(&state, auth.member_id).await?))
}

async fn clear(State(state): State<AppState>, auth: Auth) -> AppResult<StatusCode> {
    clear_session(&state, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

/// Logs in and queues the import. Without \`remember\` no credentials are stored, and any an
/// earlier login stored go with the session this one replaces.
pub async fn authenticate(
    state: &AppState,
    login: &str,
    password: &str,
    remember: bool,
    member_id: i64,
) -> AppResult<StatusResponse> {
    let started = generation(state, member_id);
    let plain_state = non_blank(
        adapter::authenticate(state, login, password).await?,
        "CORUM did not return a session",
    )?;
    let credentials = if remember {
        Some(state.crypto.encrypt(&credentials_json(login, password))?)
    } else {
        None
    };
    let mut tx = state.db.begin().await?;
    members::lock_or_throw(&mut tx, member_id).await?;
    if generation(state, member_id) != started {
        return Err(error(
            SidecarErrorCode::SessionExpired,
            "The CORUM connection was removed during the login",
        )
        .into());
    }
    let job = template()
        .insert_queued(
            state,
            &mut tx.as_handle(),
            member_id,
            plain_state,
            false,
            |session| {
                session.encrypted_credentials = credentials;
                Ok(())
            },
        )
        .await?;
    tx.commit().await?;
    let status = get_status(state, member_id).await?;
    template().submit(state, job)?;
    Ok(status)
}

/// The login and password as the JSON \`parse_credentials\` reads back; encrypted before storage.
fn credentials_json(login: &str, password: &str) -> String {
    json!({ "login": login, "password": password }).to_string()
}

/// A damaged value (malformed, blank login or password) is refused, never replayed as empty.
fn parse_credentials(plain: &str) -> Option<(String, String)> {
    let node: Value = serde_json::from_str(plain).ok()?;
    let text = |key: &str| match node.get(key) {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Null) | None => String::new(),
        Some(other) => other.to_string(),
    };
    let (login, password) = (text("login"), text("password"));
    (!login.trim().is_empty() && !password.trim().is_empty()).then_some((login, password))
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

pub async fn recover_interrupted_syncs(state: &AppState) -> sqlx::Result<u64> {
    template().recover_interrupted(state).await
}

/// Deletes the session and its stored credentials; a login still waiting on CORUM stores
/// nothing once it lands. Whether there was a session to delete.
pub async fn clear_session(state: &AppState, member_id: i64) -> AppResult<bool> {
    let mut tx = state.db.begin().await?;
    members::lock_or_throw(&mut tx, member_id).await?;
    *generations()
        .entry(generation_key(state, member_id))
        .or_insert(0) += 1;
    let existing = session::find_for_update(&mut tx, BANK, member_id).await?;
    if let Some(existing) = &existing {
        session::delete(&mut tx, existing.id).await?;
    }
    tx.commit().await?;
    Ok(existing.is_some())
}

/// The daily sync's entry (track T8): an active session is queued, and logs in again by itself
/// when it expired with credentials stored; an inactive one is reported, never retried.
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    template()
        .resync_reporting(member_id, SOURCE, get_status(state, member_id), || async {
            queue_sync(state, member_id).await.map(|_| ())
        })
        .await
}

fn expired(message: &str) -> SyncError {
    error(SidecarErrorCode::SessionExpired, message)
}

/// The stored session expired: with stored credentials the sync logs in again, once, replaces
/// the session and reads with the new one; without them the run ends as SESSION_EXPIRED.
/// Credentials that no longer decrypt or parse are cleared, since no run could use them. A
/// member's re-logins draw on their own budget; once spent the run ends as UPSTREAM_UNAVAILABLE,
/// which keeps the session and the credentials for a later run.
async fn reauthenticate_and_fetch(state: &AppState, job: &SyncJob) -> JobResult<Portfolio> {
    let current = {
        let mut tx = state.db.begin().await?;
        let found = session::find_by_id_for_update(&mut tx, job.session_id, job.member_id).await?;
        tx.commit().await?;
        found
    };
    let Some(encrypted) = current
        .filter(|s| s.active && s.sync_status == SidecarSyncStatus::Running)
        .and_then(|s| s.encrypted_credentials)
    else {
        return Err(expired("CORUM session expired").into());
    };
    let decrypted = state.crypto.decrypt(&encrypted);
    let Some((login, password)) = decrypted.as_deref().ok().and_then(parse_credentials) else {
        // Only the type: neither the stored value nor what it decrypted to belongs in a log.
        let kind = if decrypted.is_err() {
            "CryptoException"
        } else {
            "IllegalArgumentException"
        };
        tracing::warn!(
            "CORUM stored credentials are unreadable and were cleared (member={}; type={kind})",
            job.member_id
        );
        clear_stored_credentials(state, job).await?;
        return Err(expired("CORUM session expired").into());
    };
    if !state
        .limits
        .try_consume(&policies::CORUM_RELOGIN, &job.member_id.to_string())
    {
        tracing::warn!("CORUM re-login throttled (member={})", job.member_id);
        return Err(error(
            SidecarErrorCode::UpstreamUnavailable,
            "Too many CORUM re-logins. Please try again later.",
        )
        .into());
    }
    let new_state = adapter::authenticate(state, &login, &password)
        .await?
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| error(SidecarErrorCode::InvalidData, "CORUM returned no session"))?;
    let encrypted_state = state.crypto.encrypt(&new_state)?;
    let mut tx = state.db.begin().await?;
    members::lock_or_throw(&mut tx, job.member_id).await?;
    let updated =
        match session::find_by_id_for_update(&mut tx, job.session_id, job.member_id).await? {
            Some(mut current)
                if current.active && current.sync_status == SidecarSyncStatus::Running =>
            {
                let now = state.clock.now();
                current.update_session_state(encrypted_state, now);
                session::save(&mut tx, &current, now).await?;
                true
            }
            _ => false,
        };
    tx.commit().await?;
    if !updated {
        return Err(expired("CORUM session changed").into());
    }
    tracing::info!(
        "CORUM re-logged in with stored credentials (member={})",
        job.member_id
    );
    Ok(adapter::fetch_portfolio(state, &new_state).await?)
}

/// Clears the job's stored credentials, under the member lock, if its session is still in flight.
async fn clear_stored_credentials(state: &AppState, job: &SyncJob) -> JobResult<()> {
    let mut tx = state.db.begin().await?;
    members::lock_or_throw(&mut tx, job.member_id).await?;
    if let Some(mut current) =
        session::find_by_id_for_update(&mut tx, job.session_id, job.member_id).await?
        && current.is_sync_in_flight()
    {
        current.encrypted_credentials = None;
        session::save(&mut tx, &current, state.clock.now()).await?;
    }
    tx.commit().await?;
    Ok(())
}

/// Refuses a portfolio that cannot be written as it is, before anything is: the real-estate part
/// is optional but holds at least one fund when present, the life part goes through
/// [\`life::validate\`], and a login holding neither is refused.
fn validate_portfolio(portfolio: &Portfolio) -> Result<(), SyncError> {
    life::validate(portfolio.life.as_deref())?;
    match &portfolio.real_estate {
        None if portfolio.life.as_ref().is_none_or(Vec::is_empty) => Err(error(
            SidecarErrorCode::PortfolioIncomplete,
            "CORUM returned neither a real-estate nor a life contract",
        )),
        None => Ok(()),
        Some(snapshot) => validate_real_estate(snapshot),
    }
}

fn validate_real_estate(snapshot: &Snapshot) -> Result<(), SyncError> {
    if !snapshot.snapshot_complete {
        return Err(error(
            SidecarErrorCode::PortfolioIncomplete,
            "CORUM returned an incomplete portfolio",
        ));
    }
    let holdings = snapshot.holdings.as_deref().unwrap_or_default();
    if holdings.is_empty() {
        return Err(error(
            SidecarErrorCode::PortfolioIncomplete,
            "CORUM returned no fund holding",
        ));
    }
    let mut seen = HashSet::new();
    for holding in holdings {
        let Some((code, quantity)) = holding
            .as_ref()
            .and_then(|h| Some((h.fund_code.as_deref()?, h.quantity?)))
        else {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CORUM returned an incomplete fund",
            ));
        };
        if !seen.insert(code) {
            // Two lines for one fund would write the quantity twice.
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CORUM returned a duplicate fund",
            ));
        }
        if quantity < Decimal::ZERO {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CORUM returned a negative share count",
            ));
        }
    }
    check_declared_total(snapshot, holdings)?;
    // Every SCPI account is EUR and the figures are written as they come.
    if snapshot.currency.as_deref() != Some("EUR") {
        return Err(error(
            SidecarErrorCode::InvalidData,
            "CORUM returned a portfolio not in euros",
        ));
    }
    Ok(())
}

/// The funds against the contract total, repeating the sidecar's own check because a stale
/// sidecar image is what would skip it. The displayed value is compared, not the withdrawal
/// value: entry fees sit between the two.
fn check_declared_total(
    snapshot: &Snapshot,
    holdings: &[Option<adapter::Holding>],
) -> Result<(), SyncError> {
    let declared = snapshot
        .total_valuation_eur
        .filter(|d| *d >= Decimal::ZERO)
        .ok_or_else(|| {
            error(
                SidecarErrorCode::PortfolioIncomplete,
                "CORUM returned no valid portfolio total",
            )
        })?;
    let mut computed = Decimal::ZERO;
    for holding in holdings.iter().flatten() {
        let Some(displayed) = holding.displayed_value_eur else {
            return Err(error(
                SidecarErrorCode::PortfolioIncomplete,
                "CORUM returned a fund without a displayed value",
            ));
        };
        computed += displayed;
    }
    if (computed - declared).abs() > Decimal::new(1, 2) {
        return Err(error(
            SidecarErrorCode::PortfolioIncomplete,
            format!(
                "The CORUM funds add up to {} but the snapshot declares {}",
                set_scale(computed, 2, HALF_UP),
                set_scale(declared, 2, HALF_UP)
            ),
        ));
    }
    Ok(())
}

/// The validated real-estate funds onto the member's SCPI accounts; a holding without a
/// withdrawal price still updates the share count but leaves the balance alone.
async fn apply_real_estate(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    member_id: i64,
    snapshot: &Snapshot,
    synced_at: DateTime<Utc>,
) -> JobResult<Vec<(String, ScpiFundOutcome)>> {
    let funds: Vec<SyncedFund> = snapshot
        .holdings
        .iter()
        .flatten()
        .flatten()
        .map(|h| SyncedFund {
            fund_code: h.fund_code.clone().unwrap_or_default(),
            label: h.label.clone(),
            quantity: h.quantity.unwrap_or_default(),
            subscription_price: h.subscription_price,
            withdrawal_price: h.withdrawal_price,
        })
        .collect();
    Ok(state
        .connectors
        .seams
        .scpi_funds
        .reconcile(conn, member_id, ScpiProvider::Corum, &funds, synced_at)
        .await?)
}

/// Java's Map.toString of the outcomes, for the completion log.
fn describe_outcomes<T: AsRef<str>>(entries: impl Iterator<Item = (T, String)>) -> String {
    let parts: Vec<String> = entries
        .map(|(key, value)| format!("{}={value}", key.as_ref()))
        .collect();
    format!("{{{}}}", parts.join(", "))
}

pub struct Strategy;

#[async_trait]
impl SyncStrategy for Strategy {
    type Prepared = Portfolio;

    /// Reads the portfolio, logging in again once if the session expired, and validates both
    /// parts before the commit writes either.
    async fn fetch(&self, state: &AppState, job: &SyncJob) -> JobResult<Portfolio> {
        let portfolio = match adapter::fetch_portfolio(state, &job.plain_state).await {
            Ok(portfolio) => portfolio,
            Err(e) if template().code_of(&e) == SidecarErrorCode::SessionExpired => {
                // One re-login per run, never a loop: its own failure ends the run.
                reauthenticate_and_fetch(state, job).await?
            }
            Err(e) => return Err(e.into()),
        };
        validate_portfolio(&portfolio)?;
        Ok(portfolio)
    }

    /// Both parts in the template's one transaction, so a refused life contract leaves the SCPI
    /// accounts as they were, and the reverse.
    async fn commit(
        &self,
        state: &AppState,
        conn: &mut DbHandle<'_>,
        session: &mut SidecarSession,
        job: &SyncJob,
        portfolio: &Portfolio,
        synced_at: DateTime<Utc>,
    ) -> JobResult<()> {
        let funds = match &portfolio.real_estate {
            Some(snapshot) => {
                apply_real_estate(state, conn, job.member_id, snapshot, synced_at).await?
            }
            None => Vec::new(),
        };
        let contracts = life::write(
            state,
            conn,
            job.member_id,
            portfolio.life.as_deref().unwrap_or_default(),
            synced_at,
        )
        .await?;
        // Outcome counts only: the contracts are keyed by number, which stays out of the logs.
        let counts: Vec<(Outcome, usize)> = Outcome::ALL
            .iter()
            .map(|o| (*o, contracts.iter().filter(|(_, c)| c == o).count()))
            .filter(|(_, n)| *n > 0)
            .collect();
        let ambiguous = counts
            .iter()
            .find(|(o, _)| *o == Outcome::Ambiguous)
            .map_or(0, |(_, n)| *n);
        // The panel reads this count: a contract written nowhere must not show as a success.
        session.record_life_ambiguous(i32::try_from(ambiguous).unwrap_or(i32::MAX))?;
        tracing::info!(
            "CORUM outcomes (member={}; funds={}; lifeContracts={})",
            job.member_id,
            describe_outcomes(funds.iter().map(|(code, o)| (code.as_str(), o.to_string()))),
            describe_outcomes(counts.iter().map(|(o, n)| (o.as_str(), n.to_string())))
        );
        Ok(())
    }

    fn describe(&self, portfolio: &Portfolio) -> String {
        format!(
            "funds={}; lifeContracts={}",
            portfolio
                .real_estate
                .as_ref()
                .and_then(|s| s.holdings.as_ref())
                .map_or(0, Vec::len),
            portfolio.life.as_ref().map_or(0, Vec::len)
        )
    }

    /// A rejected password must not be replayed by every scheduled sync: the site may lock it.
    async fn on_failure(
        &self,
        _conn: &mut DbHandle<'_>,
        session: &mut SidecarSession,
        code: SidecarErrorCode,
    ) -> JobResult<()> {
        if code == SidecarErrorCode::InvalidCredentials {
            session.encrypted_credentials = None;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn credentials_round_trip_and_damaged_ones_are_refused() {
        let stored = credentials_json("C123", "pa\"ss");
        assert_eq!(
            parse_credentials(&stored),
            Some(("C123".to_owned(), "pa\"ss".to_owned()))
        );
        assert_eq!(parse_credentials("not json"), None);
        assert_eq!(
            parse_credentials(r#"{"login":"C123","password":" "}"#),
            None
        );
        assert_eq!(parse_credentials(r#"{"password":"x"}"#), None);
    }
}
