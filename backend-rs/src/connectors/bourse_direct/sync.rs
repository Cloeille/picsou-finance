//! BourseDirectSyncService and BourseDirectAdapter: the sidecar calls, the strict validation of a
//! portfolio snapshot (all or nothing), and its write over the member's accounts.

use std::collections::{BTreeMap, HashSet};
use std::sync::LazyLock;
use std::time::Duration;

use async_trait::async_trait;
use chrono::{DateTime, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use serde::Deserialize;
use serde_json::json;
use sqlx::PgConnection;

use crate::AppState;
use crate::connectors::sidecar::portfolio::{self, PortfolioAccount};
use crate::connectors::sidecar::template::error;
use crate::connectors::sidecar::{
    AuthInitResponse, JobResult, SessionStatusResponse, SidecarClient, SidecarInitiate,
    SidecarSession, SidecarSessionState, SyncError, SyncJob, SyncStrategy, SyncTemplate,
};
use crate::error::{AppError, AppResult};
use crate::ledger::members;
use crate::ledger::model::{AccountType, HoldingDraft};
use crate::ledger::providers::BOURSE_DIRECT as PROVIDER;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode, SourceSyncResult};
use crate::ledger::sync_values::{clean, limit, money_close, sum_complete, weighted_average};

const BANK: SidecarBank = SidecarBank::BourseDirect;
const AUTH_TIMEOUT: Duration = Duration::from_secs(45);
const PORTFOLIO_TIMEOUT: Duration = Duration::from_secs(120);

static ISIN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Z]{2}[A-Z0-9]{10}$").expect("regex"));
static CURRENCY: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[A-Z]{3}$").expect("regex"));

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
    cash_balance: Option<Decimal>,
    positions: Option<Vec<Option<Position>>>,
    #[serde(default)]
    snapshot_complete: bool,
}

pub struct PreparedAccount {
    external_id: String,
    name: String,
    account_type: AccountType,
    balance_eur: Decimal,
    cash_balance: Decimal,
    invested_amount_eur: Decimal,
    positions: Vec<PreparedPosition>,
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
        let fetched: Vec<Option<AccountData>> = client(state)
            .post_for_list(
                "/accounts",
                &json!({ "sessionState": job.plain_state }),
                PORTFOLIO_TIMEOUT,
                "Could not fetch Bourse Direct portfolio",
                SidecarErrorCode::PortfolioIncomplete,
                "Bourse Direct returned no complete portfolio accounts",
            )
            .await?;
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
        for data in prepared {
            upsert_account(state, conn, data, job.member_id, synced_at).await?;
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
    member_id: i64,
) -> AppResult<AuthInitResponse> {
    let result: SidecarInitiate = client(state)
        .post(
            "/initiate",
            &json!({ "login": login, "password": password }),
            AUTH_TIMEOUT,
            "Could not initiate Bourse Direct authentication",
            Some(SidecarErrorCode::InvalidCredentials),
        )
        .await?;
    if !result.mfa_required {
        let session_state = result
            .session_state
            .filter(|s| !s.trim().is_empty())
            .ok_or_else(|| {
                error(
                    SidecarErrorCode::InvalidData,
                    "Bourse Direct did not return a session",
                )
            })?;
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
    code: &str,
    member_id: i64,
) -> AppResult<SessionStatusResponse> {
    let response: SidecarSessionState = client(state)
        .post(
            "/complete",
            &json!({ "processId": process_id, "code": code }),
            AUTH_TIMEOUT,
            "Could not complete Bourse Direct authentication",
            Some(SidecarErrorCode::InvalidOtp),
        )
        .await?;
    let plain_state = response
        .session_state
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| {
            error(
                SidecarErrorCode::InvalidData,
                "Bourse Direct did not return a session",
            )
        })?;
    store_session_and_queue(state, plain_state, member_id).await
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
) -> AppResult<SessionStatusResponse> {
    let session = template()
        .replace_and_queue(state, member_id, plain_state, false, |_| Ok(()))
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

/// Whether a stored session was there to delete.
pub async fn clear_session(state: &AppState, member_id: i64) -> AppResult<bool> {
    template().clear(state, member_id).await
}

pub async fn recover_interrupted_syncs(state: &AppState) -> sqlx::Result<u64> {
    template().recover_interrupted(state).await
}

/// The daily scheduler's entry (track T8).
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    template()
        .resync_reporting(
            member_id,
            "bourse-direct",
            get_status(state, member_id),
            || async { queue_sync(state, member_id).await.map(|_| ()) },
        )
        .await
}

fn invalid(message: &str) -> SyncError {
    error(SidecarErrorCode::InvalidData, message)
}

fn incomplete(message: &str) -> SyncError {
    error(SidecarErrorCode::PortfolioIncomplete, message)
}

async fn prepare_accounts(
    state: &AppState,
    fetched: Vec<Option<AccountData>>,
) -> Result<Vec<PreparedAccount>, SyncError> {
    if fetched.is_empty() {
        return Err(incomplete(
            "Bourse Direct returned no complete portfolio accounts",
        ));
    }
    let mut external_ids = HashSet::new();
    let mut prepared = Vec::with_capacity(fetched.len());
    for account in fetched {
        let Some(account) = account.filter(|a| a.snapshot_complete) else {
            return Err(incomplete("Bourse Direct returned an incomplete portfolio"));
        };
        let external_id = stable_external_id(account.external_id.as_deref())?;
        if !external_ids.insert(external_id.clone()) {
            return Err(invalid("Bourse Direct returned duplicate accounts"));
        }
        let account_type = match account.account_type {
            Some(t @ (AccountType::Pea | AccountType::CompteTitres)) => t,
            _ => {
                return Err(invalid(
                    "Bourse Direct returned an unsupported account type",
                ));
            }
        };
        let (Some(balance_eur), Some(cash_balance), Some(raw_positions)) =
            (account.balance_eur, account.cash_balance, account.positions)
        else {
            return Err(invalid("Bourse Direct returned incomplete account values"));
        };
        let positions = prepare_positions(state, raw_positions).await?;
        let position_value: Decimal = positions.iter().map(|p| p.current_value_eur).sum();
        if !money_close(position_value, balance_eur - cash_balance) {
            return Err(incomplete("Bourse Direct returned an incomplete portfolio"));
        }
        let invested_amount_eur = invested_amount(balance_eur, cash_balance, &positions);
        prepared.push(PreparedAccount {
            external_id,
            name: limit(account.name.as_deref(), 100, "Bourse Direct account"),
            account_type,
            balance_eur,
            cash_balance,
            invested_amount_eur,
            positions,
        });
    }
    Ok(prepared)
}

async fn prepare_positions(
    state: &AppState,
    raw: Vec<Option<Position>>,
) -> Result<Vec<PreparedPosition>, SyncError> {
    let mut positions: Vec<PreparedPosition> = Vec::new();
    let mut isin_by_ticker = BTreeMap::new();
    for position in raw {
        let Some((position, quantity, current_value_eur)) = position.and_then(|p| {
            let (quantity, value) = (p.quantity?, p.current_value_eur?);
            Some((p, quantity, value))
        }) else {
            return Err(invalid("Bourse Direct returned an incomplete position"));
        };
        if quantity.is_zero() {
            continue;
        }
        let currency = normalize_currency(position.quote_currency.as_deref())?;
        if position.current_price.is_some() && currency.is_none() {
            return Err(invalid(
                "Bourse Direct returned a quote without its currency",
            ));
        }
        let ticker = resolve_ticker(state, &position, &mut isin_by_ticker).await?;
        let resolved = PreparedPosition {
            name: limit(position.label.as_deref(), 100, &ticker),
            ticker: ticker.clone(),
            quantity,
            average_buy_in_eur: position.buying_price_eur,
            current_price: position.current_price,
            quote_currency: currency,
            current_value_eur,
            pnl_eur: position.pnl_eur,
        };
        match positions.iter_mut().find(|p| p.ticker == ticker) {
            Some(existing) => *existing = merge_positions(existing, resolved)?,
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

async fn resolve_ticker(
    state: &AppState,
    position: &Position,
    isin_by_ticker: &mut BTreeMap<String, String>,
) -> Result<String, SyncError> {
    let mut ticker = clean(position.symbol.as_deref());
    let isin = normalize_isin(position.isin.as_deref())?;
    if let Some(isin) = &isin {
        let resolved = state.connectors.seams.isin.resolve(isin).await;
        if !resolved.ticker.trim().is_empty() {
            ticker = Some(resolved.ticker.trim().to_owned());
        }
    }
    let too_long = |t: &Option<String>| t.as_ref().is_none_or(|t| t.encode_utf16().count() > 30);
    if too_long(&ticker)
        && let Some(isin) = isin.as_ref().filter(|i| i.len() <= 30)
    {
        ticker = Some(isin.clone());
    }
    if too_long(&ticker) {
        return Err(invalid(
            "Bourse Direct returned an invalid instrument identifier",
        ));
    }
    let ticker = ticker.unwrap_or_default();
    if let Some(isin) = isin {
        isin_by_ticker.insert(ticker.clone(), isin);
    }
    Ok(ticker)
}

fn normalize_isin(raw: Option<&str>) -> Result<Option<String>, SyncError> {
    let Some(isin) = clean(raw) else {
        return Ok(None);
    };
    let isin = isin.to_uppercase();
    if !ISIN.is_match(&isin) {
        return Err(invalid("Bourse Direct returned an invalid ISIN"));
    }
    Ok(Some(isin))
}

fn normalize_currency(raw: Option<&str>) -> Result<Option<String>, SyncError> {
    let Some(currency) = clean(raw) else {
        return Ok(None);
    };
    let currency = currency.to_uppercase();
    if !CURRENCY.is_match(&currency) {
        return Err(invalid("Bourse Direct returned an invalid quote currency"));
    }
    Ok(Some(currency))
}

fn merge_positions(
    left: &PreparedPosition,
    right: PreparedPosition,
) -> Result<PreparedPosition, SyncError> {
    if left.quote_currency != right.quote_currency {
        return Err(invalid(
            "Bourse Direct returned conflicting quote currencies",
        ));
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

/// Cash plus each line's cost basis; the balance itself as soon as one line has none.
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

async fn upsert_account(
    state: &AppState,
    conn: &mut PgConnection,
    data: &PreparedAccount,
    member_id: i64,
    synced_at: DateTime<Utc>,
) -> JobResult<()> {
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
    let account = PortfolioAccount {
        external_id: &data.external_id,
        name: &data.name,
        account_type: data.account_type,
        provider: PROVIDER,
        color: if data.account_type == AccountType::Pea {
            "#10b981"
        } else {
            "#3b82f6"
        },
        balance_eur: data.balance_eur,
        cash_balance: Some(data.cash_balance),
        invested_eur: data.invested_amount_eur,
    };
    portfolio::write(state, conn, member_id, &account, &drafts, synced_at).await?;
    Ok(())
}

fn stable_external_id(raw: Option<&str>) -> Result<String, SyncError> {
    let cleaned = clean(raw)
        .ok_or_else(|| invalid("Bourse Direct returned an invalid account identifier"))?;
    let external_id = if cleaned.starts_with("bd_") {
        cleaned
    } else {
        format!("bd_{cleaned}")
    };
    if external_id.encode_utf16().count() > 100 {
        return Err(invalid(
            "Bourse Direct returned an invalid account identifier",
        ));
    }
    Ok(external_id)
}
