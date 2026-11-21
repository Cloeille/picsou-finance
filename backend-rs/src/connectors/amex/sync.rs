//! AmexSyncService and AmexAdapter: the sidecar calls, the strict validation of a card snapshot
//! (all or nothing), and its write over the member's credit-card account.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, LazyLock, Mutex, MutexGuard};
use std::time::Duration;

use async_trait::async_trait;
use chrono::{DateTime, NaiveDate, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use sqlx::PgConnection;

use super::history::{self, PreparedTransaction};
use crate::AppState;
use crate::connectors::sidecar::template::error;
use crate::connectors::sidecar::{
    AuthInitResponse, JobResult, SessionStatusResponse, SidecarClient, SidecarHttp,
    SidecarInitiate, SidecarSession, SidecarSessionState, SidecarSyncStatus, SyncError, SyncJob,
    SyncStrategy, SyncTemplate,
};
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::members;
use crate::ledger::model::AccountType;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode, SourceSyncResult, SyncStatus};
use crate::ledger::sync_values::{clean, limit};
use crate::ledger::valuation::{self, SnapshotContext};

const BANK: SidecarBank = SidecarBank::Amex;
const PROVIDER: &str = "American Express";
const SOURCE: &str = "amex";
const EXTERNAL_ID_PREFIX: &str = "amex_";
/// Browser start, up to two 20 s waits for the client-rendered login form, typing, then up to
/// 30 s for the login outcome and the code channel step.
const AUTH_TIMEOUT: Duration = Duration::from_secs(90);
const VALIDATION_TIMEOUT: Duration = Duration::from_secs(40);
const ACCOUNTS_TIMEOUT: Duration = Duration::from_secs(90);
const HISTORY_TIMEOUT: Duration = Duration::from_secs(4 * 90);
const WEBVIEW_TIMEOUT: Duration = Duration::from_secs(30);
const WEBVIEW_ATTEMPT_SECONDS: i64 = 360;
const NO_SESSION: &str = "American Express did not return a session";

pub fn webview_supported(state: &AppState) -> bool {
    state.config.desktop.eq_ignore_ascii_case("true")
}

#[derive(Debug, Clone)]
struct PendingWebview {
    member_id: i64,
    deadline: DateTime<Utc>,
    generation: u64,
}

#[derive(Default)]
struct WebviewMaps {
    pending: HashMap<String, PendingWebview>,
    generations: HashMap<i64, u64>,
}

#[derive(Default)]
struct WebviewLogins {
    maps: Mutex<WebviewMaps>,
    write: tokio::sync::Mutex<()>,
}

impl WebviewLogins {
    fn maps(&self) -> MutexGuard<'_, WebviewMaps> {
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
    ) -> Result<PendingWebview, SyncError> {
        self.maps()
            .pending
            .get(process_id)
            .filter(|p| p.member_id == member_id && p.deadline > now)
            .cloned()
            .ok_or_else(|| {
                error(
                    SidecarErrorCode::AuthAttemptExpired,
                    "American Express sign-in attempt expired",
                )
            })
    }

    fn require_generation(&self, member_id: i64, generation: u64) -> Result<(), SyncError> {
        if self.generation(member_id) != generation {
            return Err(error(
                SidecarErrorCode::AuthAttemptExpired,
                "The American Express connection was removed during the login",
            ));
        }
        Ok(())
    }

    fn forget_member(&self, member_id: i64) {
        let mut maps = self.maps();
        *maps.generations.entry(member_id).or_insert(0) += 1;
        maps.pending.retain(|_, p| p.member_id != member_id);
    }
}

fn webview_logins(state: &AppState) -> Arc<WebviewLogins> {
    state.connectors.jobs.local::<WebviewLogins>("amex-webview")
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
            "American Express interactive WebView login is only available in Picsou Desktop"
                .to_owned(),
        ))
    }
}

static ISO_DATE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^\d{4}-\d{2}-\d{2}$").expect("regex"));

/// The sidecar's TransactionPayload spells the label and amount `description` and `amount`;
/// `status` is the feed the row came from, "posted" or "pending".
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawTransaction {
    external_id: Option<String>,
    date: Option<String>,
    #[serde(alias = "description")]
    label: Option<String>,
    #[serde(alias = "amount")]
    amount_eur: Option<Decimal>,
    status: Option<String>,
}

impl RawTransaction {
    fn pending(&self) -> bool {
        self.status
            .as_deref()
            .is_some_and(|s| s.eq_ignore_ascii_case("pending"))
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AccountData {
    external_id: Option<String>,
    name: Option<String>,
    balance_eur: Option<Decimal>,
    #[serde(alias = "amountDue")]
    payment_due_amount: Option<Decimal>,
    due_date: Option<String>,
    reward_points: Option<i64>,
    transactions: Option<Vec<Option<RawTransaction>>>,
    /// False when the sidecar's pending feed failed: the pending rows are then unknown.
    pending_complete: Option<bool>,
    snapshot_complete: Option<bool>,
}

pub struct PreparedAccount {
    external_id: String,
    name: String,
    balance_eur: Decimal,
    payment_due_amount: Option<Decimal>,
    payment_due_date: Option<NaiveDate>,
    reward_points: Option<i64>,
    transactions: Vec<PreparedTransaction>,
    pending_complete: bool,
}

fn client(state: &AppState) -> SidecarClient {
    SidecarClient::new(state, BANK, SidecarErrorCode::AuthAttemptExpired)
}

fn template() -> SyncTemplate<Strategy> {
    SyncTemplate::new(BANK, Strategy, false)
}

pub struct Strategy;

#[async_trait]
impl SyncStrategy for Strategy {
    type Prepared = Vec<PreparedAccount>;

    async fn fetch(&self, state: &AppState, job: &SyncJob) -> JobResult<Vec<PreparedAccount>> {
        let mut body = Map::new();
        body.insert(
            "sessionState".into(),
            Value::String(job.plain_state.clone()),
        );
        if job.history {
            body.insert("history".into(), Value::Bool(true));
        }
        let (timeout, message) = if job.history {
            (
                HISTORY_TIMEOUT,
                "Could not recover Amex transaction history",
            )
        } else {
            (ACCOUNTS_TIMEOUT, "Could not fetch Amex accounts")
        };
        let fetched: Vec<Option<AccountData>> = client(state)
            .post_for_list(
                "/accounts",
                &Value::Object(body),
                timeout,
                message,
                SidecarErrorCode::InvalidData,
                "Amex returned no account",
            )
            .await?;
        Ok(prepare_accounts(fetched)?)
    }

    async fn commit(
        &self,
        state: &AppState,
        conn: &mut PgConnection,
        _session: &mut SidecarSession,
        job: &SyncJob,
        prepared: &Vec<PreparedAccount>,
        synced_at: DateTime<Utc>,
    ) -> JobResult<()> {
        if members::find_by_id(&mut *conn, job.member_id)
            .await?
            .is_none()
        {
            return Err(AppError::NotFound("Family member not found".to_owned()).into());
        }
        for data in prepared {
            upsert_account(state, conn, data, job.member_id, synced_at, job.history).await?;
        }
        Ok(())
    }

    fn describe(&self, prepared: &Vec<PreparedAccount>) -> String {
        format!("accounts={}", prepared.len())
    }
}

pub async fn initiate_auth(
    state: &AppState,
    login: &str,
    password: &str,
    method: &str,
    member_id: i64,
) -> AppResult<AuthInitResponse> {
    let result: SidecarInitiate = client(state)
        .post(
            "/initiate",
            &json!({ "userId": login, "password": password, "method": method }),
            AUTH_TIMEOUT,
            "Could not initiate Amex authentication",
            Some(SidecarErrorCode::InvalidCredentials),
        )
        .await?;
    if !result.mfa_required {
        let session_state = result
            .session_state
            .filter(|s| !s.trim().is_empty())
            .ok_or_else(|| error(SidecarErrorCode::InvalidData, NO_SESSION))?;
        store_session_and_queue(state, session_state, member_id).await?;
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
    otp: &str,
    member_id: i64,
) -> AppResult<SessionStatusResponse> {
    let response: SidecarSessionState = client(state)
        .post(
            "/complete",
            &json!({ "processId": process_id, "otp": otp }),
            VALIDATION_TIMEOUT,
            "Could not complete Amex authentication",
            Some(SidecarErrorCode::InvalidOtp),
        )
        .await?;
    let plain_state = response
        .session_state
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| error(SidecarErrorCode::InvalidData, NO_SESSION))?;
    store_session_and_queue(state, plain_state, member_id).await
}

pub async fn queue_sync(state: &AppState, member_id: i64) -> AppResult<SessionStatusResponse> {
    Ok(
        SessionStatusResponse::of(&template().queue(state, member_id, false).await?)
            .with_webview_supported(webview_supported(state)),
    )
}

pub async fn queue_history_recovery(
    state: &AppState,
    member_id: i64,
) -> AppResult<SessionStatusResponse> {
    Ok(
        SessionStatusResponse::of(&template().queue(state, member_id, true).await?)
            .with_webview_supported(webview_supported(state)),
    )
}

/// The first import after a login fetches the full provider history: the connect flow is already
/// the heavy operation, routine syncs keep the 90-day window.
async fn store_session_and_queue(
    state: &AppState,
    plain_state: String,
    member_id: i64,
) -> AppResult<SessionStatusResponse> {
    let session = template()
        .replace_and_queue(state, member_id, plain_state, true, |_| Ok(()))
        .await?;
    Ok(SessionStatusResponse::of(&session).with_webview_supported(webview_supported(state)))
}

pub async fn get_status(state: &AppState, member_id: i64) -> AppResult<SessionStatusResponse> {
    let supported = webview_supported(state);
    Ok(template()
        .find(state, member_id)
        .await?
        .map_or_else(SessionStatusResponse::inactive, |s| {
            SessionStatusResponse::of(&s)
        })
        .with_webview_supported(supported))
}

pub async fn clear_session(state: &AppState, member_id: i64) -> AppResult<bool> {
    let logins = webview_logins(state);
    let _write = logins.write.lock().await;
    logins.forget_member(member_id);
    template().clear(state, member_id).await
}

pub async fn start_webview(state: &AppState, member_id: i64) -> AppResult<WebviewStartResponse> {
    require_webview(state)?;
    let logins = webview_logins(state);
    let generation = logins.generation(member_id);
    let started: BridgeWebviewStart = client(state)
        .post(
            "/webview/start",
            &json!({}),
            WEBVIEW_TIMEOUT,
            "Could not start the American Express WebView login",
            None,
        )
        .await?;
    let Some(process_id) = started.process_id.filter(|s| clean(Some(s)).is_some()) else {
        return Err(error(
            SidecarErrorCode::UpstreamFormatChanged,
            "American Express WebView bridge did not return a process identifier",
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
            PendingWebview {
                member_id,
                deadline: now + chrono::Duration::seconds(WEBVIEW_ATTEMPT_SECONDS),
                generation,
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
    let logins = webview_logins(state);
    let current = logins.owned_attempt(process_id, member_id, state.clock.now())?;
    let c = client(state);
    let http = SidecarHttp::new(state, BANK.service_name(), BANK.label());
    let path = format!("/webview/status/{process_id}");
    let raw = http
        .send::<()>(http::Method::GET, &path, None, WEBVIEW_TIMEOUT)
        .await
        .map_err(|e| c.map_call_error("Could not read American Express WebView status", e))?;
    if !raw.is_success() {
        let mapped = c.map_status("Could not read American Express WebView status", &raw, None);
        if raw.status == http::StatusCode::GONE || raw.status == http::StatusCode::NOT_FOUND {
            logins.remove(process_id);
        }
        return Err(mapped.into());
    }
    let bridge: BridgeWebviewStatus = c
        .decode("Could not decode American Express WebView status", &raw)?
        .ok_or_else(|| {
            c.coded(
                SidecarErrorCode::UpstreamUnavailable,
                "Could not decode American Express WebView status",
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
                    "American Express WebView login did not return a session",
                )
                .into());
            };
            let tpl = template();
            let job = {
                let _write = logins.write.lock().await;
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
                let job = tpl
                    .insert_queued(state, &mut tx, member_id, plain_state, true, |_| Ok(()))
                    .await?;
                tx.commit().await?;
                job
            };
            let session = get_status(state, member_id).await?;
            tpl.submit(state, job)?;
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
                "American Express WebView bridge returned an unexpected status",
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
    let logins = webview_logins(state);
    let removed = {
        let mut maps = logins.maps();
        if maps
            .pending
            .get(process_id)
            .is_some_and(|p| p.member_id == member_id)
        {
            maps.pending.remove(process_id);
            true
        } else {
            false
        }
    };
    if !removed {
        return Ok(WebviewCancelResponse { cancelled: false });
    }
    let http = SidecarHttp::new(state, BANK.service_name(), BANK.label());
    let path = format!("/webview/cancel/{process_id}");
    let _ = http
        .send::<Value>(http::Method::POST, &path, Some(&json!({})), WEBVIEW_TIMEOUT)
        .await;
    Ok(WebviewCancelResponse { cancelled: true })
}

pub async fn recover_interrupted_syncs(state: &AppState) -> sqlx::Result<u64> {
    template().recover_interrupted(state).await
}

/// The daily scheduler's entry (track T8): queues an import when the session can run, and reads
/// the queued status back.
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    let outcome = async {
        let status = get_status(state, member_id).await?;
        if !status.is_active {
            return Ok(match status.last_sync_error {
                Some(SidecarErrorCode::SessionExpired) => {
                    result(SyncStatus::NeedsReauth, "SESSION_EXPIRED")
                }
                Some(code) => result(SyncStatus::Failed, code.as_str()),
                None => result(SyncStatus::SkippedNotConnected, "No active session"),
            });
        }
        let queued = queue_sync(state, member_id).await?;
        Ok::<_, AppError>(match queued.sync_status {
            SidecarSyncStatus::Queued | SidecarSyncStatus::Running => {
                result(SyncStatus::Queued, "")
            }
            SidecarSyncStatus::Success => result(SyncStatus::Synced, ""),
            _ if queued.last_sync_error == Some(SidecarErrorCode::SessionExpired) => {
                result(SyncStatus::NeedsReauth, "SESSION_EXPIRED")
            }
            SidecarSyncStatus::Failed => result(
                SyncStatus::Failed,
                queued.last_sync_error.map_or("Sync failed", |c| c.as_str()),
            ),
            SidecarSyncStatus::Idle => result(SyncStatus::Skipped, "Sync already complete"),
        })
    }
    .await;
    match outcome {
        Ok(outcome) => outcome,
        Err(AppError::NotFound(_)) => {
            tracing::debug!(
                "Member disappeared before scheduled American Express sync (member={member_id})"
            );
            result(SyncStatus::SkippedNotConnected, "Member not found")
        }
        Err(AppError::Internal(detail)) if detail.starts_with("database") => {
            tracing::error!(
                "Database error during scheduled American Express sync (member={member_id})"
            );
            result(SyncStatus::Failed, "Database error")
        }
        Err(AppError::Coded { code, detail, .. }) => {
            tracing::warn!(
                "Could not queue scheduled American Express sync (member={member_id}; code={code})"
            );
            SourceSyncResult::from_sync_exception(SOURCE, Some(&code), Some(&detail))
        }
        Err(AppError::Status { status, detail })
            if status == http::StatusCode::UNPROCESSABLE_ENTITY =>
        {
            tracing::warn!(
                "Could not queue scheduled American Express sync (member={member_id}; code=UPSTREAM_UNAVAILABLE)"
            );
            SourceSyncResult::from_sync_exception(SOURCE, None, Some(&detail))
        }
        Err(e) => {
            tracing::error!(
                "Unexpected scheduled American Express sync failure (member={member_id}): {e}"
            );
            result(SyncStatus::Failed, "Unexpected sync error")
        }
    }
}

fn result(status: SyncStatus, message: &str) -> SourceSyncResult {
    SourceSyncResult {
        source: SOURCE.to_owned(),
        status,
        message: message.to_owned(),
    }
}

fn invalid(message: &str) -> SyncError {
    error(SidecarErrorCode::InvalidData, message)
}

fn prepare_accounts(fetched: Vec<Option<AccountData>>) -> Result<Vec<PreparedAccount>, SyncError> {
    if fetched.is_empty() {
        return Err(invalid("American Express returned no account"));
    }
    let mut external_ids = HashSet::new();
    let mut prepared = Vec::with_capacity(fetched.len());
    for account in fetched {
        let Some(account) = account.filter(|a| a.snapshot_complete == Some(true)) else {
            return Err(invalid("American Express returned an incomplete snapshot"));
        };
        let external_id = stable_external_id(account.external_id.as_deref())?;
        if !external_ids.insert(external_id.clone()) {
            return Err(invalid("American Express returned duplicate accounts"));
        }
        let Some(balance_eur) = account.balance_eur else {
            return Err(invalid(
                "American Express returned an incomplete account balance",
            ));
        };
        prepared.push(PreparedAccount {
            external_id,
            name: limit(account.name.as_deref(), 100, "American Express"),
            balance_eur,
            payment_due_amount: account.payment_due_amount,
            payment_due_date: parse_optional_date(account.due_date.as_deref()),
            reward_points: account.reward_points,
            transactions: prepare_transactions(account.transactions)?,
            pending_complete: account.pending_complete == Some(true),
        });
    }
    Ok(prepared)
}

/// LocalDate.parse: exactly yyyy-MM-dd.
fn parse_iso_date(value: &str) -> Option<NaiveDate> {
    if !ISO_DATE.is_match(value) {
        return None;
    }
    NaiveDate::parse_from_str(value, "%Y-%m-%d").ok()
}

/// The first ten characters as a date; anything unreadable is dropped, not fatal.
fn parse_optional_date(value: Option<&str>) -> Option<NaiveDate> {
    let value = value?;
    if value.encode_utf16().count() < 10 {
        return None;
    }
    let head: String = value.chars().take(10).collect();
    parse_iso_date(&head)
}

/// Validates the raw transaction list and parses each date, rejecting the whole sync on a
/// malformed row rather than silently dropping money movements.
fn prepare_transactions(
    raw: Option<Vec<Option<RawTransaction>>>,
) -> Result<Vec<PreparedTransaction>, SyncError> {
    let raw = raw.unwrap_or_default();
    let mut prepared = Vec::with_capacity(raw.len());
    let mut occurrences = std::collections::HashMap::<String, usize>::new();
    for tx in raw {
        let Some((tx, date, amount)) = tx.and_then(|t| {
            let (date, amount) = (t.date.clone()?, t.amount_eur?);
            Some((t, date, amount))
        }) else {
            return Err(invalid(
                "American Express returned an incomplete transaction",
            ));
        };
        let date = parse_iso_date(&date)
            .ok_or_else(|| invalid("American Express returned an invalid transaction date"))?;
        let label = limit(tx.label.as_deref(), 255, "American Express transaction");
        // Two identical purchases on the same day are two rows: each repeat of a tuple is numbered
        // in response order, so a re-sync of the same data maps back onto the same ids.
        let external_id = match tx.external_id.clone() {
            Some(id) => id,
            None => {
                let pending = tx.pending();
                let base = history::identity(pending, date, &label, amount, 0);
                let seen = occurrences.entry(base).or_default();
                *seen += 1;
                history::identity(pending, date, &label, amount, *seen - 1)
            }
        };
        prepared.push(PreparedTransaction {
            external_id,
            date,
            label,
            amount_eur: amount,
        });
    }
    Ok(prepared)
}

async fn upsert_account(
    state: &AppState,
    conn: &mut PgConnection,
    data: &PreparedAccount,
    member_id: i64,
    synced_at: DateTime<Utc>,
    history: bool,
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
                    "American Express skipped a soft-deleted account (member={member_id})"
                );
                return Ok(());
            }
            let mut draft = NewAccount::new(member_id, data.name.clone(), AccountType::CreditCard);
            draft.provider = Some(PROVIDER.to_owned());
            draft.external_account_id = Some(data.external_id.clone());
            draft.is_manual = false;
            draft.color = "#2563eb".to_owned();
            draft.current_balance = data.balance_eur;
            draft.last_synced_at = Some(synced_at);
            accounts::insert(&mut *conn, &draft, synced_at).await?
        }
    };
    account.name = data.name.clone();
    account.account_type = AccountType::CreditCard;
    account.provider = Some(PROVIDER.to_owned());
    account.currency = "EUR".to_owned();
    account.is_manual = false;
    account.current_balance = data.balance_eur;
    // AMEX enrichment is sparse: an absent value keeps the latest known one.
    if let Some(amount) = data.payment_due_amount {
        account.payment_due_amount = Some(amount);
    }
    if let Some(date) = data.payment_due_date {
        account.payment_due_date = Some(date);
    }
    if let Some(points) = data.reward_points {
        account.reward_points = Some(points);
    }
    account.last_synced_at = Some(synced_at);
    let account = accounts::update(&mut *conn, &account, synced_at).await?;

    let today = state.clock.today();
    let ctx = SnapshotContext {
        prices: &*state.ledger.prices,
        today,
        now: synced_at,
    };
    valuation::upsert_snapshot(&mut *conn, &ctx, &account, data.balance_eur, today).await?;
    history::sync_transactions(
        conn,
        account.id,
        &data.transactions,
        data.pending_complete,
        history,
        today,
        synced_at,
    )
    .await?;
    Ok(())
}

fn stable_external_id(raw: Option<&str>) -> Result<String, SyncError> {
    let cleaned = clean(raw)
        .ok_or_else(|| invalid("American Express returned an invalid account identifier"))?;
    let external_id = if cleaned.starts_with(EXTERNAL_ID_PREFIX) {
        cleaned
    } else {
        format!("{EXTERNAL_ID_PREFIX}{cleaned}")
    };
    if external_id.encode_utf16().count() > 100 {
        return Err(invalid(
            "American Express returned an invalid account identifier",
        ));
    }
    Ok(external_id)
}
