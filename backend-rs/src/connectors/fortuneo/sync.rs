//! FortuneoSyncService and FortuneoAdapter: the sidecar calls, the strict validation of a snapshot
//! (all or nothing), and its write: holdings, the ledger (reconciled by id when every row has one,
//! else the trailing window), the day's snapshot and the history reconstructed before it.

use std::collections::{BTreeSet, HashMap, HashSet};
use std::sync::LazyLock;
use std::time::Duration;

use crate::db::DbHandle;
use async_trait::async_trait;
use chrono::{DateTime, Days, Months, NaiveDate, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use serde::Deserialize;
use serde_json::json;

use super::writer::{self, Recognition, Recognitions, Upsert};
use crate::AppState;
use crate::connectors::sidecar::flow::{self, Login};
use crate::connectors::sidecar::portfolio::{self, PortfolioAccount};
use crate::connectors::sidecar::template::error;
use crate::connectors::sidecar::{
    AuthInitResponse, JobResult, SessionStatusResponse, SidecarClient, SidecarSession, SyncError,
    SyncJob, SyncStrategy, SyncTemplate,
};
use crate::error::{AppError, AppResult};
use crate::ledger::model::{Account, AccountType, HoldingDraft, NewTransaction, TransactionType};
use crate::ledger::providers::FORTUNEO as PROVIDER;
use crate::ledger::reconstruct;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode, SourceSyncResult};
use crate::ledger::sync_values::{
    clean, java_trim, limit, money_close, sum_complete, weighted_average,
};
use crate::ledger::{holdings, members, snapshots, transactions};

const BANK: SidecarBank = SidecarBank::Fortuneo;
const PORTFOLIO_TIMEOUT: Duration = Duration::from_secs(120);
const TRANSACTION_WINDOW_DAYS: u64 = 90;
const MAX_TICKER_LENGTH: usize = 30;
const LOGIN: Login = Login {
    timeout: Duration::from_secs(45),
    initiate_message: "Could not initiate Fortuneo authentication",
    complete_message: "Could not complete Fortuneo authentication",
    no_session: "Fortuneo did not return a session",
    complete_failure: Some(SidecarErrorCode::InvalidOtp),
};

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

/// One cash-ledger entry; externalId is None when this response shape carries none.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Transaction {
    date: Option<NaiveDate>,
    label: Option<String>,
    amount: Option<Decimal>,
    category: Option<String>,
    external_id: Option<String>,
    #[serde(rename = "type")]
    kind: Option<String>,
    tx_type: Option<String>,
    quantity: Option<Decimal>,
    unit_price: Option<Decimal>,
    fees: Option<Decimal>,
    isin: Option<String>,
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
    transactions: Option<Vec<Option<Transaction>>>,
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
    transactions: Vec<PreparedTransaction>,
}

#[derive(Debug, Clone)]
struct PreparedPosition {
    ticker: String,
    isin: Option<String>,
    name: String,
    quantity: Decimal,
    average_buy_in_eur: Option<Decimal>,
    current_price: Option<Decimal>,
    quote_currency: Option<String>,
    current_value_eur: Decimal,
    pnl_eur: Option<Decimal>,
}

/// A validated ledger entry, its instrument already resolved to the ticker its holding uses.
#[derive(Debug, Clone)]
struct PreparedTransaction {
    date: NaiveDate,
    label: Option<String>,
    amount: Decimal,
    category: Option<String>,
    external_id: Option<String>,
    kind: Option<String>,
    tx_type: Option<TransactionType>,
    quantity: Option<Decimal>,
    unit_price: Option<Decimal>,
    fees: Option<Decimal>,
    isin: Option<String>,
    ticker: Option<String>,
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
                "Could not fetch Fortuneo portfolio",
                SidecarErrorCode::PortfolioIncomplete,
                "Fortuneo returned no complete portfolio accounts",
            )
            .await?;
        let prepared = prepare_accounts(state, fetched).await?;
        backfill_prices(state, &prepared).await;
        Ok(prepared)
    }

    async fn commit(
        &self,
        state: &AppState,
        conn: &mut DbHandle<'_>,
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
    flow::initiate(
        state,
        &client(state),
        &template(),
        &LOGIN,
        &json!({ "login": login, "password": password }),
        member_id,
    )
    .await
}

pub async fn complete_auth(
    state: &AppState,
    process_id: &str,
    code: &str,
    member_id: i64,
) -> AppResult<SessionStatusResponse> {
    let session = flow::complete(
        state,
        &client(state),
        &template(),
        &LOGIN,
        LOGIN.timeout,
        &json!({ "processId": process_id, "code": code }),
        member_id,
    )
    .await?;
    Ok(SessionStatusResponse::of(&session))
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
            "fortuneo",
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
            "Fortuneo returned no complete portfolio accounts",
        ));
    }
    let mut external_ids = HashSet::new();
    let mut prepared = Vec::with_capacity(fetched.len());
    for account in fetched {
        let Some(account) = account.filter(|a| a.snapshot_complete) else {
            return Err(incomplete("Fortuneo returned an incomplete portfolio"));
        };
        let external_id = stable_external_id(account.external_id.as_deref())?;
        if !external_ids.insert(external_id.clone()) {
            return Err(invalid("Fortuneo returned duplicate accounts"));
        }
        let Some(account_type) = account.account_type.filter(|t| {
            matches!(
                t,
                AccountType::Pea
                    | AccountType::CompteTitres
                    | AccountType::Checking
                    | AccountType::Savings
            )
        }) else {
            return Err(invalid("Fortuneo returned an unsupported account type"));
        };
        let (Some(balance_eur), Some(cash_balance), Some(raw_positions)) =
            (account.balance_eur, account.cash_balance, account.positions)
        else {
            return Err(invalid("Fortuneo returned incomplete account values"));
        };
        let positions = prepare_positions(state, raw_positions).await?;
        let invested_amount_eur =
            if matches!(account_type, AccountType::Checking | AccountType::Savings) {
                // The whole balance is cash: nothing to reconcile against positions.
                if !positions.is_empty() {
                    return Err(invalid("Fortuneo returned positions for a cash account"));
                }
                if !money_close(cash_balance, balance_eur) {
                    return Err(invalid(
                        "Fortuneo returned a cash account whose balance does not match its cash",
                    ));
                }
                balance_eur
            } else {
                let position_value: Decimal = positions.iter().map(|p| p.current_value_eur).sum();
                if !money_close(position_value, balance_eur - cash_balance) {
                    return Err(incomplete("Fortuneo returned an incomplete portfolio"));
                }
                invested_amount(balance_eur, cash_balance, &positions)
            };
        let transactions = prepare_transactions(state, account.transactions).await?;
        prepared.push(PreparedAccount {
            external_id,
            name: limit(account.name.as_deref(), 100, "Fortuneo account"),
            account_type,
            balance_eur,
            cash_balance,
            invested_amount_eur,
            positions,
            transactions,
        });
    }
    Ok(prepared)
}

async fn prepare_transactions(
    state: &AppState,
    raw: Option<Vec<Option<Transaction>>>,
) -> Result<Vec<PreparedTransaction>, SyncError> {
    let raw = raw.unwrap_or_default();
    let mut prepared = Vec::with_capacity(raw.len());
    for tx in raw {
        let Some((tx, date, amount)) = tx.and_then(|t| {
            let (date, amount) = (t.date?, t.amount?);
            Some((t, date, amount))
        }) else {
            return Err(invalid("Fortuneo returned an incomplete transaction"));
        };
        let external_id = normalize_optional(tx.external_id.as_deref());
        let kind = normalize_optional(tx.kind.as_deref());
        let tx_type = validated_tx_type(tx.tx_type.as_deref())?;
        let isin = normalize_isin(tx.isin.as_deref())?;
        let ticker = match &isin {
            Some(isin) => Some(ticker_of(state, isin).await),
            None => None,
        };
        prepared.push(PreparedTransaction {
            date,
            label: tx.label,
            amount,
            category: tx.category,
            external_id,
            kind,
            tx_type,
            quantity: tx.quantity,
            unit_price: tx.unit_price,
            fees: tx.fees,
            isin,
            ticker,
        });
    }
    Ok(prepared)
}

/// None is a legitimate type (the sidecar leaves an unrecognised operation untyped); an unknown
/// value means the two sides disagree on the contract.
fn validated_tx_type(raw: Option<&str>) -> Result<Option<TransactionType>, SyncError> {
    normalize_optional(raw)
        .map(|value| {
            value
                .parse::<TransactionType>()
                .map_err(|_| invalid("Fortuneo returned an unknown transaction type"))
        })
        .transpose()
}

/// Trims an optional provider string; blank or longer than 100 means absent, never a placeholder.
fn normalize_optional(value: Option<&str>) -> Option<String> {
    let trimmed = java_trim(value?);
    if trimmed.is_empty() || trimmed.encode_utf16().count() > 100 {
        return None;
    }
    Some(trimmed.to_owned())
}

/// The ticker the ISIN resolves to, as the positions get it, so a trade and the holding it built
/// land on the same ticker; the ISIN itself when nothing resolves.
async fn ticker_of(state: &AppState, isin: &str) -> String {
    let resolved = state.connectors.seams.isin.resolve(isin).await;
    if resolved.ticker.trim().is_empty() {
        limit(Some(isin), MAX_TICKER_LENGTH, isin)
    } else {
        limit(Some(resolved.ticker.trim()), MAX_TICKER_LENGTH, isin)
    }
}

async fn prepare_positions(
    state: &AppState,
    raw: Vec<Option<Position>>,
) -> Result<Vec<PreparedPosition>, SyncError> {
    let mut positions: Vec<PreparedPosition> = Vec::new();
    for position in raw {
        let Some((position, quantity, current_value_eur)) = position.and_then(|p| {
            let (quantity, value) = (p.quantity?, p.current_value_eur?);
            Some((p, quantity, value))
        }) else {
            return Err(invalid("Fortuneo returned an incomplete position"));
        };
        if quantity.is_zero() {
            continue;
        }
        let currency = normalize_currency(position.quote_currency.as_deref())?;
        if position.current_price.is_some() && currency.is_none() {
            return Err(invalid("Fortuneo returned a quote without its currency"));
        }
        let ticker = resolve_ticker(state, &position).await?;
        let resolved = PreparedPosition {
            name: limit(position.label.as_deref(), 100, &ticker),
            ticker: ticker.clone(),
            isin: crate::ledger::model::normalize_isin(position.isin.as_deref()),
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
    positions.retain(|p| !p.quantity.is_zero());
    Ok(positions)
}

async fn resolve_ticker(state: &AppState, position: &Position) -> Result<String, SyncError> {
    let mut ticker = clean(position.symbol.as_deref());
    let isin = normalize_isin(position.isin.as_deref())?;
    if let Some(isin) = &isin {
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
        ticker = Some(isin);
    }
    if too_long(&ticker) {
        return Err(invalid(
            "Fortuneo returned an invalid instrument identifier",
        ));
    }
    Ok(ticker.unwrap_or_default())
}

fn normalize_isin(raw: Option<&str>) -> Result<Option<String>, SyncError> {
    let Some(isin) = clean(raw) else {
        return Ok(None);
    };
    let isin = isin.to_uppercase();
    if !ISIN.is_match(&isin) {
        return Err(invalid("Fortuneo returned an invalid ISIN"));
    }
    Ok(Some(isin))
}

fn normalize_currency(raw: Option<&str>) -> Result<Option<String>, SyncError> {
    let Some(currency) = clean(raw) else {
        return Ok(None);
    };
    let currency = currency.to_uppercase();
    if !CURRENCY.is_match(&currency) {
        return Err(invalid("Fortuneo returned an invalid quote currency"));
    }
    Ok(Some(currency))
}

fn merge_positions(
    left: &PreparedPosition,
    right: PreparedPosition,
) -> Result<PreparedPosition, SyncError> {
    if left.quote_currency != right.quote_currency {
        return Err(invalid("Fortuneo returned conflicting quote currencies"));
    }
    let quantity = left.quantity + right.quantity;
    Ok(PreparedPosition {
        ticker: left.ticker.clone(),
        isin: left.isin.clone().or(right.isin),
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

/// A year of daily prices for every instrument the import holds or traded, sold-out lines
/// included, so the reconstruction has something to value its days with. Outside any
/// transaction, and never fatal: the prices draw the history, they are not the import.
async fn backfill_prices(state: &AppState, prepared: &[PreparedAccount]) {
    let mut tickers = BTreeSet::new();
    let mut add = |ticker: Option<&str>| {
        if let Some(ticker) = ticker.filter(|t| !t.trim().is_empty()) {
            tickers.insert(ticker.trim().to_owned());
        }
    };
    for account in prepared {
        for position in &account.positions {
            add(Some(&position.ticker));
        }
        for tx in &account.transactions {
            add(tx.ticker.as_deref());
        }
    }
    if tickers.is_empty() {
        return;
    }
    let today = state.clock.today();
    let from = today.checked_sub_months(Months::new(12)).unwrap_or(today);
    match state
        .connectors
        .seams
        .price_backfill
        .backfill(&tickers, from)
        .await
    {
        Ok(saved) => tracing::info!(
            "Fortuneo backfilled {saved} price(s) for {} instrument(s)",
            tickers.len()
        ),
        Err(e) => {
            tracing::warn!(
                "Fortuneo price backfill failed; the reconstructed history will be shorter: {e}"
            );
        }
    }
}

fn color_for(account_type: AccountType) -> &'static str {
    match account_type {
        AccountType::Pea => "#10b981",
        AccountType::CompteTitres => "#3b82f6",
        AccountType::Checking => "#f59e0b",
        AccountType::Savings => "#8b5cf6",
        _ => "#6b7280",
    }
}

async fn upsert_account(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    data: &PreparedAccount,
    member_id: i64,
    synced_at: DateTime<Utc>,
) -> JobResult<()> {
    let account = PortfolioAccount {
        external_id: &data.external_id,
        name: &data.name,
        account_type: data.account_type,
        provider: PROVIDER,
        color: color_for(data.account_type),
        balance_eur: data.balance_eur,
        cash_balance: Some(data.cash_balance),
        invested_eur: data.invested_amount_eur,
    };
    let Some(saved) =
        portfolio::upsert_account(state, conn, member_id, &account, synced_at).await?
    else {
        return Ok(());
    };
    let now = state.clock.now();
    let today = state.clock.today();
    let drafts: Vec<HoldingDraft> = data
        .positions
        .iter()
        .map(|p| HoldingDraft {
            ticker: p.ticker.clone(),
            isin: p.isin.clone(),
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
    holdings::replace_all(&mut *conn, saved.id, &drafts, now).await?;
    sync_transactions(conn, &saved, &data.transactions, today, now).await?;
    snapshots::upsert(
        &mut *conn,
        saved.id,
        data.balance_eur,
        data.invested_amount_eur,
        today,
        now,
    )
    .await?;
    // A sync writes one point dated today; the ledger just imported establishes the days before.
    reconstruct::reconstruct(
        conn,
        &saved,
        &*state.ledger.prices,
        data.balance_eur,
        today,
        now,
    )
    .await?;
    Ok(())
}

/// Reconciled by id when every row carries Fortuneo's stable identifier; the rolling window
/// otherwise, since without ids a re-sent row cannot be told from a new one. An empty answer
/// leaves the stored history alone: it far more likely means nothing was fetched.
async fn sync_transactions(
    conn: &mut DbHandle<'_>,
    account: &Account,
    rows: &[PreparedTransaction],
    today: NaiveDate,
    now: DateTime<Utc>,
) -> JobResult<()> {
    if rows.is_empty() {
        return Ok(());
    }
    let dividend_categories = writer::dividend_categories(&mut *conn, account.member_id).await?;
    if rows.iter().all(|tx| tx.external_id.is_some()) {
        reconcile_full_history(conn, account, rows, today, now, &dividend_categories).await
    } else {
        tracing::info!(
            "Fortuneo returned transactions without stable ids; falling back to the {TRANSACTION_WINDOW_DAYS}-day window import"
        );
        replace_recent_transactions(conn, account, rows, today, now, &dividend_categories).await
    }
}

fn description_of(tx: &PreparedTransaction) -> String {
    limit(tx.label.as_deref(), 255, "Fortuneo transaction")
}

/// The provider's own label for the instrument, kept only where there is an instrument.
fn instrument_name_of(tx: &PreparedTransaction) -> Option<String> {
    tx.isin
        .as_ref()
        .map(|_| limit(tx.label.as_deref(), 100, "Fortuneo instrument"))
}

fn new_row(account: &Account, tx: &PreparedTransaction) -> NewTransaction {
    let mut row = NewTransaction::new(account.id, tx.date, description_of(tx), tx.amount);
    row.external_id.clone_from(&tx.external_id);
    row.category.clone_from(&tx.category);
    row.kind.clone_from(&tx.kind);
    row.tx_type = tx.tx_type;
    row.ticker.clone_from(&tx.ticker);
    row.name = instrument_name_of(tx);
    row.quantity = tx.quantity;
    row.price_per_unit = tx.unit_price;
    row.fees = tx.fees;
    row
}

/// Reconciles the reported history with the stored one by id. Deletion is bounded to the dates
/// the response covers, from its oldest entry to today: the feed is authoritative up to now, so a
/// newer stored row is one the provider stopped showing, while older history stays.
async fn reconcile_full_history(
    conn: &mut DbHandle<'_>,
    account: &Account,
    rows: &[PreparedTransaction],
    today: NaiveDate,
    now: DateTime<Utc>,
    dividend_categories: &HashSet<i64>,
) -> JobResult<()> {
    // A repeated id is not a reason to fail: the last occurrence wins, in the first one's place.
    let mut order: Vec<&str> = Vec::new();
    let mut reported: HashMap<&str, &PreparedTransaction> = HashMap::new();
    for tx in rows {
        let id = tx.external_id.as_deref().unwrap_or_default();
        if reported.insert(id, tx).is_none() {
            order.push(id);
        }
    }
    let from = rows.iter().map(|tx| tx.date).min().unwrap_or(today);
    let stored = transactions::find_synced_by_account(&mut *conn, account.id, None).await?;
    let mut stored_by_id = HashMap::new();
    let mut obsolete = Vec::new();
    for row in stored {
        if let Some(id) = row.external_id.clone()
            && reported.contains_key(id.as_str())
        {
            stored_by_id.insert(id, row);
            continue;
        }
        if row.date >= from && row.date <= today {
            obsolete.push(row);
        }
    }
    // An obsolete row is often the same payment re-sent with an id it lacked before.
    let mut recognised = Recognitions::of(&obsolete);
    let mut upserts = Vec::with_capacity(order.len());
    for id in order {
        let tx = reported[id];
        match stored_by_id.remove(id) {
            None => {
                let mut row = new_row(account, tx);
                recognised.restore_on(&mut row, dividend_categories);
                upserts.push(Upsert::Insert(Box::new(row)));
            }
            Some(mut row) => {
                let recognition = Recognition::of(&row);
                row.date = tx.date;
                row.description = description_of(tx);
                row.amount = tx.amount;
                row.category.clone_from(&tx.category);
                row.kind.clone_from(&tx.kind);
                row.tx_type = tx.tx_type;
                row.ticker.clone_from(&tx.ticker);
                row.name = instrument_name_of(tx);
                row.quantity = tx.quantity;
                row.price_per_unit = tx.unit_price;
                row.fees = tx.fees;
                if let Some(recognition) = recognition {
                    recognition.restore_on(&mut row, dividend_categories);
                }
                upserts.push(Upsert::Update(Box::new(row)));
            }
        }
    }
    writer::reconcile_history(conn, &obsolete, &upserts, now).await?;
    Ok(())
}

/// Replaces the trailing window of synced rows, keeping older history and manual rows; only what
/// falls inside the window is inserted, or every older row would be appended again each sync.
async fn replace_recent_transactions(
    conn: &mut DbHandle<'_>,
    account: &Account,
    rows: &[PreparedTransaction],
    today: NaiveDate,
    now: DateTime<Utc>,
    dividend_categories: &HashSet<i64>,
) -> JobResult<()> {
    let cutoff = today - Days::new(TRANSACTION_WINDOW_DAYS);
    let replaced =
        transactions::find_synced_by_account(&mut *conn, account.id, Some(cutoff)).await?;
    let mut recognised = Recognitions::of(&replaced);
    let mut to_insert = Vec::new();
    for tx in rows.iter().filter(|tx| tx.date >= cutoff) {
        let mut row = new_row(account, tx);
        row.external_id = None;
        recognised.restore_on(&mut row, dividend_categories);
        to_insert.push(row);
    }
    writer::replace_recent_transactions(conn, account.id, cutoff, &to_insert, now).await?;
    Ok(())
}

fn stable_external_id(raw: Option<&str>) -> Result<String, SyncError> {
    let cleaned =
        clean(raw).ok_or_else(|| invalid("Fortuneo returned an invalid account identifier"))?;
    let external_id = if cleaned.starts_with("ft_") {
        cleaned
    } else {
        format!("ft_{cleaned}")
    };
    if external_id.encode_utf16().count() > 100 {
        return Err(invalid("Fortuneo returned an invalid account identifier"));
    }
    Ok(external_id)
}
