//! BoursoSyncService and BoursoAdapter: the sidecar calls, the stored credentials that let an
//! expired session log in again, the strict validation of a snapshot (all or nothing) and its
//! write over the member's accounts.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use chrono::{DateTime, TimeDelta, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use serde::Deserialize;
use serde_json::json;
use sqlx::PgConnection;

use crate::AppState;
use crate::connectors::sidecar::pending::{Pending, PendingLogins};
use crate::connectors::sidecar::session;
use crate::connectors::sidecar::template::error;
use crate::connectors::sidecar::{
    AuthInitResponse, JobError, JobResult, SessionStatusResponse, SidecarClient, SidecarInitiate,
    SidecarSession, SidecarSessionState, SidecarSyncStatus, SyncError, SyncJob, SyncStrategy,
    SyncTemplate,
};
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::categorize::{self, CategorizationContext};
use crate::ledger::model::{Account, AccountType, HoldingDraft};
use crate::ledger::providers::BOURSO as PROVIDER;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode, SidecarTransaction, SourceSyncResult};
use crate::ledger::sync_values::{
    clean, error_code, limit, lines_close, money_close, sum_complete, weighted_average,
};
use crate::ledger::valuation::{self, SnapshotContext};
use crate::ledger::{holdings, members, sidecar_import, snapshots, transfers};

const BANK: SidecarBank = SidecarBank::Bourso;
const EXTERNAL_ID_PREFIX: &str = "bourso_";
const PROVIDER_FAMILY: [&str; 3] = [PROVIDER, "Boursorama", "Boursorama Banque"];
const MAX_TICKER_LENGTH: usize = 30;
const AUTH_TIMEOUT: Duration = Duration::from_secs(45);
/// An app push waits on a human unlocking their phone; the sidecar caps that wait at 120 s.
const VALIDATION_TIMEOUT: Duration = Duration::from_secs(150);
/// One home fetch, one dashboard fetch, and one trading call per securities account.
const ACCOUNTS_TIMEOUT: Duration = Duration::from_secs(90);
/// How long the credentials typed at /initiate wait for the app push to be approved.
const PENDING_TTL: TimeDelta = TimeDelta::seconds(600);

/// Loans are out of scope.
const SUPPORTED_TYPES: [AccountType; 12] = [
    AccountType::Checking,
    AccountType::Savings,
    AccountType::Lep,
    AccountType::LivretA,
    AccountType::Ldds,
    AccountType::LivretJeune,
    AccountType::Pel,
    AccountType::Cel,
    AccountType::Pea,
    AccountType::CompteTitres,
    AccountType::AssuranceVie,
    AccountType::Per,
];

static ISIN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Z]{2}[A-Z0-9]{10}$").expect("regex"));
static CURRENCY: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[A-Z]{3}$").expect("regex"));
static IBAN_SHAPE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$").expect("regex"));

/// Logins waiting for their app push, per application instance (BoursoSyncService's
/// pendingCredentials map, which lives as long as the service).
static PENDING: LazyLock<Mutex<HashMap<usize, Arc<PendingLogins>>>> = LazyLock::new(Mutex::default);

fn pending(state: &AppState) -> Arc<PendingLogins> {
    let key = std::ptr::from_ref::<crate::Inner>(state).addr();
    PENDING
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .entry(key)
        .or_default()
        .clone()
}

/// One open position. currentPrice is in quoteCurrency; the other amounts are EUR.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Position {
    isin: Option<String>,
    symbol: Option<String>,
    label: Option<String>,
    quantity: Option<Decimal>,
    buying_price_eur: Option<Decimal>,
    current_price: Option<Decimal>,
    quote_currency: Option<String>,
    current_value_eur: Option<Decimal>,
    pnl_eur: Option<Decimal>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AccountData {
    external_id: Option<String>,
    name: Option<String>,
    #[serde(rename = "type")]
    account_type: Option<AccountType>,
    balance_eur: Option<Decimal>,
    /// Securities accounts and life contracts only; a livret has no cash leg.
    cash_balance: Option<Decimal>,
    positions: Option<Vec<Option<Position>>>,
    #[serde(default)]
    snapshot_complete: bool,
    iban: Option<String>,
    transactions: Option<Vec<SidecarTransaction>>,
    /// Line-valued accounts only: the rows the sidecar reconciled, euro-fund rows included.
    valuation_line_count: Option<i32>,
}

pub struct PreparedAccount {
    external_id: String,
    name: String,
    account_type: AccountType,
    balance_eur: Decimal,
    cash_balance: Option<Decimal>,
    /// None for a cash account: the snapshot derives it as for every balance-only connector.
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
    current_price: Option<Decimal>,
    quote_currency: Option<String>,
    current_value_eur: Decimal,
    pnl_eur: Option<Decimal>,
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
        let fetched = match fetch_accounts(state, &job.plain_state).await {
            Ok(fetched) => fetched,
            Err(e) if code_of(&e) == SidecarErrorCode::SessionExpired => {
                reauthenticate_and_fetch(state, job).await?
            }
            Err(e) => return Err(e.into()),
        };
        Ok(prepare_accounts(state, fetched).await?)
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
        let knowledge_base = state.ledger.knowledge_base(&state.db).await?;
        let ctx =
            categorize::load_context(&state.db, knowledge_base, job.member_id, state.clock.now())
                .await?;
        for data in prepared {
            upsert_account(state, conn, data, job.member_id, synced_at, &ctx).await?;
        }
        Ok(())
    }

    async fn after_commit(
        &self,
        state: &AppState,
        job: &SyncJob,
        _prepared: &Vec<PreparedAccount>,
    ) {
        transfers::after_import(
            &state.db,
            &*state.ledger.dividends,
            job.member_id,
            state.clock.today(),
            state.clock.now(),
        )
        .await;
    }

    fn describe(&self, prepared: &Vec<PreparedAccount>) -> String {
        format!("accounts={}", prepared.len())
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

fn code_of(error: &SyncError) -> SidecarErrorCode {
    error_code(error.code(), BANK, SidecarErrorCode::UpstreamUnavailable)
}

async fn sidecar_initiate(
    state: &AppState,
    customer_id: &str,
    password: &str,
) -> Result<SidecarInitiate, SyncError> {
    client(state)
        .post(
            "/initiate",
            &json!({ "customerId": customer_id, "password": password }),
            AUTH_TIMEOUT,
            "Could not initiate BoursoBank authentication",
            Some(SidecarErrorCode::InvalidCredentials),
        )
        .await
}

async fn fetch_accounts(
    state: &AppState,
    session_state: &str,
) -> Result<Vec<Option<AccountData>>, SyncError> {
    client(state)
        .post_for_list(
            "/accounts",
            &json!({ "sessionState": session_state }),
            ACCOUNTS_TIMEOUT,
            "Could not fetch BoursoBank accounts",
            SidecarErrorCode::PortfolioIncomplete,
            "BoursoBank returned no account",
        )
        .await
}

pub async fn initiate_auth(
    state: &AppState,
    customer_id: &str,
    password: &str,
    member_id: i64,
) -> AppResult<AuthInitResponse> {
    let result = sidecar_initiate(state, customer_id, password).await?;
    if !result.mfa_required {
        let session_state = result
            .session_state
            .filter(|s| !s.trim().is_empty())
            .ok_or_else(|| {
                error(
                    SidecarErrorCode::InvalidData,
                    "BoursoBank did not return a session",
                )
            })?;
        store_session_and_queue(
            state,
            session_state,
            member_id,
            Some((customer_id, password)),
        )
        .await?;
    } else if let Some(process_id) = &result.process_id {
        let now = state.clock.now();
        let logins = pending(state);
        logins.evict_expired(now);
        logins.put(
            process_id.clone(),
            Pending {
                login: customer_id.to_owned(),
                password: password.to_owned(),
                expires_at: now + PENDING_TTL,
                member_id,
                generation: 0,
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
    member_id: i64,
) -> AppResult<SessionStatusResponse> {
    // The sidecar forbids unknown fields but declares "code", so it carries an explicit null.
    let response: SidecarSessionState = client(state)
        .post(
            "/complete",
            &json!({ "processId": process_id, "code": null }),
            VALIDATION_TIMEOUT,
            "Could not complete BoursoBank authentication",
            Some(SidecarErrorCode::AppValidationTimeout),
        )
        .await?;
    let logins = pending(state);
    let credentials = logins.get(process_id);
    logins.remove(process_id);
    if credentials
        .as_ref()
        .is_some_and(|c| c.member_id != member_id)
    {
        return Err(error(
            SidecarErrorCode::AuthAttemptExpired,
            "BoursoBank authentication attempt expired",
        )
        .into());
    }
    let plain_state = response
        .session_state
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| {
            error(
                SidecarErrorCode::InvalidData,
                "BoursoBank did not return a session",
            )
        })?;
    let now = state.clock.now();
    let valid = credentials.filter(|c| c.expires_at > now);
    store_session_and_queue(
        state,
        plain_state,
        member_id,
        valid
            .as_ref()
            .map(|c| (c.login.as_str(), c.password.as_str())),
    )
    .await
}

pub async fn queue_sync(state: &AppState, member_id: i64) -> AppResult<SessionStatusResponse> {
    Ok(SessionStatusResponse::of(
        &template().queue(state, member_id, false).await?,
    ))
}

async fn store_session_and_queue(
    state: &AppState,
    plain_state: String,
    member_id: i64,
    credentials: Option<(&str, &str)>,
) -> AppResult<SessionStatusResponse> {
    let encrypted = credentials
        .map(|(customer_id, password)| {
            state
                .crypto
                .encrypt(&serialize_credentials(customer_id, password))
        })
        .transpose()?;
    let session = template()
        .replace_and_queue(state, member_id, plain_state, false, |session| {
            session.encrypted_credentials = encrypted;
            Ok(())
        })
        .await?;
    Ok(SessionStatusResponse::of(&session))
}

pub async fn get_status(state: &AppState, member_id: i64) -> AppResult<SessionStatusResponse> {
    Ok(template()
        .find(state, member_id)
        .await?
        .map_or_else(SessionStatusResponse::inactive, |s| {
            SessionStatusResponse::of(&s)
        }))
}

/// Whether a stored session was there to delete. A pending login is dropped too but does not
/// count: it never became a connection.
pub async fn clear_session(state: &AppState, member_id: i64) -> AppResult<bool> {
    let removed = template().clear(state, member_id).await?;
    let logins = pending(state);
    for process_id in logins.member_process_ids(member_id) {
        logins.remove(&process_id);
    }
    Ok(removed)
}

pub async fn recover_interrupted_syncs(state: &AppState) -> sqlx::Result<u64> {
    template().recover_interrupted(state).await
}

/// The daily scheduler's entry (track T8).
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    template()
        .resync_reporting(
            member_id,
            "bourso",
            get_status(state, member_id),
            || async { queue_sync(state, member_id).await.map(|_| ()) },
        )
        .await
}

fn serialize_credentials(customer_id: &str, password: &str) -> String {
    json!({ "customerId": customer_id, "password": password }).to_string()
}

struct Credentials {
    customer_id: String,
    password: String,
}

fn parse_credentials(value: &str) -> Option<Credentials> {
    let node: serde_json::Value = serde_json::from_str(value).ok()?;
    let text = |key: &str| match node.get(key) {
        None => String::new(),
        Some(serde_json::Value::String(s)) => s.clone(),
        Some(other) => other.to_string(),
    };
    let (customer_id, password) = (text("customerId"), text("password"));
    if customer_id.trim().is_empty() || password.trim().is_empty() {
        return None;
    }
    Some(Credentials {
        customer_id,
        password,
    })
}

fn expired(message: &str) -> SyncError {
    error(SidecarErrorCode::SessionExpired, message)
}

/// The stored session expired: logs in again with the stored credentials, once, and fetches with
/// the fresh session. A login that asks for the app push cannot run unattended.
async fn reauthenticate_and_fetch(
    state: &AppState,
    job: &SyncJob,
) -> JobResult<Vec<Option<AccountData>>> {
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
        return Err(expired("BoursoBank session expired").into());
    };
    let credentials = state
        .crypto
        .decrypt(&encrypted)
        .ok()
        .and_then(|plain| parse_credentials(&plain))
        .ok_or_else(|| expired("BoursoBank session expired"))?;
    let result =
        match sidecar_initiate(state, &credentials.customer_id, &credentials.password).await {
            Ok(result) => result,
            Err(e) => {
                if code_of(&e) == SidecarErrorCode::InvalidCredentials {
                    clear_stored_credentials(state, job).await?;
                }
                return Err(e.into());
            }
        };
    if result.mfa_required {
        return Err(expired("BoursoBank requires interactive validation").into());
    }
    let new_state = result
        .session_state
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| {
            error(
                SidecarErrorCode::InvalidData,
                "BoursoBank returned no session",
            )
        })?;
    let encrypted_state = state.crypto.encrypt(&new_state)?;
    let mut tx = state.db.begin().await?;
    let updated = match session::find_by_id_for_update(&mut tx, job.session_id, job.member_id)
        .await?
        .filter(|s| s.active && s.sync_status == SidecarSyncStatus::Running)
    {
        Some(mut current) => {
            let now = state.clock.now();
            current.update_session_state(encrypted_state, now);
            session::save(&mut tx, &current, now).await?;
            true
        }
        None => false,
    };
    tx.commit().await?;
    if !updated {
        return Err(expired("BoursoBank session changed").into());
    }
    Ok(fetch_accounts(state, &new_state).await?)
}

async fn clear_stored_credentials(state: &AppState, job: &SyncJob) -> Result<(), JobError> {
    let mut tx = state.db.begin().await?;
    if let Some(mut current) =
        session::find_by_id_for_update(&mut tx, job.session_id, job.member_id).await?
    {
        current.encrypted_credentials = None;
        session::save(&mut tx, &current, state.clock.now()).await?;
    }
    tx.commit().await?;
    Ok(())
}

fn invalid(message: &str) -> SyncError {
    error(SidecarErrorCode::InvalidData, message)
}

fn incomplete(message: &str) -> SyncError {
    error(SidecarErrorCode::PortfolioIncomplete, message)
}

/// An assurance vie and a PER are read from a page that rounds each support line to the cent, so
/// they reconcile within a cent per line.
fn is_line_valued(account_type: AccountType) -> bool {
    matches!(account_type, AccountType::AssuranceVie | AccountType::Per)
}

/// A missing, incomplete, duplicate or unsupported account refuses the whole sync, and an
/// investment account must reconcile with its balance so a truncated position list never
/// overwrites a correct portfolio.
async fn prepare_accounts(
    state: &AppState,
    fetched: Vec<Option<AccountData>>,
) -> Result<Vec<PreparedAccount>, SyncError> {
    if fetched.is_empty() {
        return Err(incomplete("BoursoBank returned no account"));
    }
    let mut external_ids = HashSet::new();
    let mut prepared = Vec::with_capacity(fetched.len());
    for account in fetched {
        let Some(account) = account.filter(|a| a.snapshot_complete) else {
            return Err(incomplete("BoursoBank returned an incomplete snapshot"));
        };
        let external_id = stable_external_id(account.external_id.as_deref())?;
        if !external_ids.insert(external_id.clone()) {
            return Err(invalid("BoursoBank returned duplicate accounts"));
        }
        let Some(account_type) = account.account_type.filter(|t| SUPPORTED_TYPES.contains(t))
        else {
            return Err(invalid("BoursoBank returned an unsupported account type"));
        };
        let (Some(balance_eur), Some(raw_positions)) = (account.balance_eur, account.positions)
        else {
            return Err(invalid("BoursoBank returned incomplete account values"));
        };
        let holds_positions = account_type.is_investment();
        if !holds_positions && !raw_positions.is_empty() {
            return Err(invalid("BoursoBank reported positions on a cash account"));
        }
        let raw_count = raw_positions.len();
        let positions = prepare_positions(state, raw_positions).await?;
        let (cash_balance, invested_amount_eur) = if holds_positions {
            let Some(cash_balance) = account.cash_balance else {
                return Err(invalid(
                    "BoursoBank returned no cash balance for a securities account",
                ));
            };
            // Re-run the sidecar's reconciliation rather than trust it.
            let position_value: Decimal = positions.iter().map(|p| p.current_value_eur).sum();
            let expected = balance_eur - cash_balance;
            let reconciled = if is_line_valued(account_type) {
                let lines = account
                    .valuation_line_count
                    .filter(|&lines| usize::try_from(lines).is_ok_and(|l| l >= raw_count))
                    .ok_or_else(|| {
                        invalid("BoursoBank returned no valid line count for a line-valued account")
                    })?;
                lines_close(position_value, expected, lines)
            } else {
                money_close(position_value, expected)
            };
            if !reconciled {
                return Err(incomplete("BoursoBank returned an incomplete portfolio"));
            }
            let invested = invested_amount(balance_eur, cash_balance, &positions);
            (Some(cash_balance), Some(invested))
        } else {
            // A livret has one number and no cash leg; 0 would report a phantom cash balance.
            (None, None)
        };
        prepared.push(PreparedAccount {
            external_id,
            name: limit(account.name.as_deref(), 100, "BoursoBank account"),
            account_type,
            balance_eur,
            cash_balance,
            invested_amount_eur,
            positions,
            iban: account.iban,
            transactions: account.transactions.unwrap_or_default(),
        });
    }
    Ok(prepared)
}

async fn prepare_positions(
    state: &AppState,
    raw: Vec<Option<Position>>,
) -> Result<Vec<PreparedPosition>, SyncError> {
    let mut positions: Vec<PreparedPosition> = Vec::new();
    let mut isin_by_ticker: BTreeMap<String, String> = BTreeMap::new();
    for position in raw {
        let Some((position, quantity, current_value_eur)) = position.and_then(|p| {
            let (quantity, value) = (p.quantity?, p.current_value_eur?);
            Some((p, quantity, value))
        }) else {
            return Err(invalid("BoursoBank returned an incomplete position"));
        };
        if quantity.is_zero() {
            continue;
        }
        let currency = normalize_currency(position.quote_currency.as_deref())?;
        if position.current_price.is_some() && currency.is_none() {
            return Err(invalid("BoursoBank returned a quote without its currency"));
        }
        let isin = normalize_isin(position.isin.as_deref())?;
        let ticker = resolve_ticker(state, &position, isin.as_deref()).await?;
        let existing = positions.iter().position(|p| p.ticker == ticker);
        // Only lines carrying the same ISIN may merge: two share classes OpenFIGI gives one
        // symbol would otherwise become one holding filed under a single ISIN.
        if existing.is_some()
            && isin
                .as_ref()
                .is_none_or(|isin| isin_by_ticker.get(&ticker) != Some(isin))
        {
            return Err(invalid(
                "BoursoBank returned two instruments under one ticker",
            ));
        }
        if let Some(isin) = isin {
            isin_by_ticker.insert(ticker.clone(), isin);
        }
        let resolved = PreparedPosition {
            name: limit(position.label.as_deref(), 100, &ticker),
            ticker,
            quantity,
            average_buy_in_eur: position.buying_price_eur,
            current_price: position.current_price,
            quote_currency: currency,
            current_value_eur,
            pnl_eur: position.pnl_eur,
        };
        match existing {
            Some(index) => positions[index] = merge_positions(&positions[index], resolved)?,
            None => positions.push(resolved),
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

/// The trading board exposes BoursoBank's own symbol; a position without a resolvable ISIN keeps
/// it, and is then valued by BoursoBank rather than read as zero.
async fn resolve_ticker(
    state: &AppState,
    position: &Position,
    isin: Option<&str>,
) -> Result<String, SyncError> {
    let mut ticker = clean(position.symbol.as_deref());
    if let Some(isin) = isin {
        let resolved = state.connectors.seams.isin.resolve(isin).await;
        if !resolved.ticker.trim().is_empty() {
            ticker = Some(resolved.ticker.trim().to_owned());
        }
    }
    let too_long = |t: &Option<String>| {
        t.as_ref()
            .is_none_or(|t| t.encode_utf16().count() > MAX_TICKER_LENGTH)
    };
    if too_long(&ticker)
        && let Some(isin) = isin.filter(|i| i.len() <= MAX_TICKER_LENGTH)
    {
        ticker = Some(isin.to_owned());
    }
    match ticker {
        Some(ticker) if !too_long(&Some(ticker.clone())) => Ok(ticker),
        _ => Err(invalid(
            "BoursoBank returned an invalid instrument identifier",
        )),
    }
}

fn normalize_isin(raw: Option<&str>) -> Result<Option<String>, SyncError> {
    let Some(isin) = clean(raw) else {
        return Ok(None);
    };
    let isin = isin.to_uppercase();
    if !ISIN.is_match(&isin) {
        return Err(invalid("BoursoBank returned an invalid ISIN"));
    }
    Ok(Some(isin))
}

fn normalize_currency(raw: Option<&str>) -> Result<Option<String>, SyncError> {
    let Some(currency) = clean(raw) else {
        return Ok(None);
    };
    let currency = currency.to_uppercase();
    if !CURRENCY.is_match(&currency) {
        return Err(invalid("BoursoBank returned an invalid quote currency"));
    }
    Ok(Some(currency))
}

/// Not HoldingDedup.vwapMerge: that one drops the provider valuation an unpriceable holding needs.
fn merge_positions(
    left: &PreparedPosition,
    right: PreparedPosition,
) -> Result<PreparedPosition, SyncError> {
    if left.quote_currency != right.quote_currency {
        return Err(invalid("BoursoBank returned conflicting quote currencies"));
    }
    let quantity = left.quantity + right.quantity;
    Ok(PreparedPosition {
        ticker: left.ticker.clone(),
        name: right.name,
        quantity,
        average_buy_in_eur: weighted_average(
            left.average_buy_in_eur,
            left.quantity,
            right.average_buy_in_eur,
            right.quantity,
            quantity,
        ),
        current_price: weighted_average(
            left.current_price,
            left.quantity,
            right.current_price,
            right.quantity,
            quantity,
        ),
        quote_currency: left.quote_currency.clone(),
        current_value_eur: left.current_value_eur + right.current_value_eur,
        pnl_eur: sum_complete(left.pnl_eur, right.pnl_eur),
    })
}

/// Cash plus each line's cost basis; the balance itself as soon as one line has none, because a
/// partial basis next to a full valuation reports a gain the size of the missing lines.
fn invested_amount(
    balance_eur: Decimal,
    cash_balance: Decimal,
    positions: &[PreparedPosition],
) -> Decimal {
    let mut invested = cash_balance;
    for position in positions {
        let cost_basis = match (position.average_buy_in_eur, position.pnl_eur) {
            (Some(average), _) => average * position.quantity,
            (None, Some(pnl)) => position.current_value_eur - pnl,
            (None, None) => return balance_eur,
        };
        invested += cost_basis;
    }
    invested
}

/// An IBAN of a valid shape whose mod-97 checksum holds.
fn is_verified_iban(iban: Option<&str>) -> bool {
    let Some(iban) = iban else {
        return false;
    };
    let normalized: String = iban
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect::<String>()
        .to_uppercase();
    if !IBAN_SHAPE.is_match(&normalized) {
        return false;
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
    remainder == 1
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
        _ => "#ec4899",
    }
}

/// The account found by external id, else (with a verified IBAN) the member's account of the
/// BoursoBank family with that IBAN, so a legacy or Enable Banking connection is taken over.
/// None when the member deleted it.
async fn find_target(
    conn: &mut PgConnection,
    data: &PreparedAccount,
    member_id: i64,
) -> sqlx::Result<Option<Option<Account>>> {
    let verified = is_verified_iban(data.iban.as_deref());
    let mut existing =
        accounts::find_by_external_id_and_member(&mut *conn, &data.external_id, member_id).await?;
    if existing.is_none()
        && verified
        && let Some(iban) = data.iban.as_deref()
    {
        existing = accounts::find_first_by_iban_member_and_providers(
            &mut *conn,
            iban,
            member_id,
            &PROVIDER_FAMILY,
        )
        .await?;
    }
    if existing.is_some() {
        return Ok(Some(existing));
    }
    let deleted =
        accounts::exists_soft_deleted_by_external_id(&mut *conn, &data.external_id, member_id)
            .await?
            || match data.iban.as_deref().filter(|_| verified) {
                Some(iban) => {
                    accounts::exists_soft_deleted_by_iban(
                        &mut *conn,
                        iban,
                        member_id,
                        None,
                        Some(&PROVIDER_FAMILY),
                    )
                    .await?
                }
                None => false,
            };
    Ok(if deleted { None } else { Some(None) })
}

async fn upsert_account(
    state: &AppState,
    conn: &mut PgConnection,
    data: &PreparedAccount,
    member_id: i64,
    synced_at: DateTime<Utc>,
    ctx: &CategorizationContext,
) -> JobResult<()> {
    let Some(existing) = find_target(conn, data, member_id).await? else {
        tracing::info!("BoursoBank skipped a soft-deleted account (member={member_id})");
        return Ok(());
    };
    let now = state.clock.now();
    let iban = data.iban.clone().filter(|i| !i.trim().is_empty());
    let account = match existing {
        Some(mut account) => {
            account.name = data.name.clone();
            account.account_type = data.account_type;
            account.provider = Some(PROVIDER.to_owned());
            account.currency = "EUR".to_owned();
            account.is_manual = false;
            account.external_account_id = Some(data.external_id.clone());
            if iban.is_some() {
                account.iban = iban;
            }
            account.current_balance = data.balance_eur;
            account.cash_balance = data.cash_balance;
            account.last_synced_at = Some(synced_at);
            accounts::update(&mut *conn, &account, now).await?
        }
        None => {
            let mut draft = NewAccount::new(member_id, data.name.clone(), data.account_type);
            draft.external_account_id = Some(data.external_id.clone());
            draft.provider = Some(PROVIDER.to_owned());
            draft.is_manual = false;
            draft.color = color_for(data.account_type).to_owned();
            draft.iban = iban;
            draft.current_balance = data.balance_eur;
            draft.cash_balance = data.cash_balance;
            draft.last_synced_at = Some(synced_at);
            accounts::insert(&mut *conn, &draft, now).await?
        }
    };
    sidecar_import::import(&mut *conn, &account, &data.transactions, ctx, "bourso", now).await?;
    let drafts: Vec<HoldingDraft> = data
        .positions
        .iter()
        .map(|p| HoldingDraft {
            ticker: p.ticker.clone(),
            name: Some(p.name.clone()),
            quantity: p.quantity,
            average_buy_in: p.average_buy_in_eur,
            current_price: p.current_price,
            quote_currency: p.quote_currency.clone(),
            provider_value_eur: Some(p.current_value_eur),
            provider_pnl_eur: p.pnl_eur,
            last_synced_at: Some(synced_at),
            ..HoldingDraft::default()
        })
        .collect();
    holdings::replace_all(&mut *conn, account.id, &drafts, now).await?;
    let today = state.clock.today();
    match data.invested_amount_eur {
        Some(invested) => {
            snapshots::upsert(
                &mut *conn,
                account.id,
                data.balance_eur,
                invested,
                today,
                now,
            )
            .await?;
        }
        None => {
            let ctx = SnapshotContext {
                prices: &*state.ledger.prices,
                today,
                now,
            };
            valuation::upsert_snapshot(&mut *conn, &ctx, &account, data.balance_eur, today).await?;
        }
    }
    Ok(())
}

fn stable_external_id(raw: Option<&str>) -> Result<String, SyncError> {
    let cleaned =
        clean(raw).ok_or_else(|| invalid("BoursoBank returned an invalid account identifier"))?;
    let external_id = if cleaned.starts_with(EXTERNAL_ID_PREFIX) {
        cleaned
    } else {
        format!("{EXTERNAL_ID_PREFIX}{cleaned}")
    };
    if external_id.encode_utf16().count() > 100 {
        return Err(invalid("BoursoBank returned an invalid account identifier"));
    }
    Ok(external_id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_iban_is_verified_by_its_checksum() {
        assert!(is_verified_iban(Some("FR76 3000 6000 0112 3456 7890 189")));
        assert!(!is_verified_iban(Some("FR7630006000011234567890188")));
        assert!(!is_verified_iban(Some("not an iban")));
        assert!(!is_verified_iban(None));
    }

    #[test]
    fn stored_credentials_round_trip_and_blank_ones_are_refused() {
        let parsed = parse_credentials(&serialize_credentials("12345678", "pass")).unwrap();
        assert_eq!(
            (parsed.customer_id.as_str(), parsed.password.as_str()),
            ("12345678", "pass")
        );
        assert!(parse_credentials(r#"{"customerId":"1","password":" "}"#).is_none());
        assert!(parse_credentials("garbage").is_none());
    }
}
