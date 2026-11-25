//! TradeRepublicSyncService: the login, the session status, the queued import of the portfolio
//! and of TR's activity export, and the write over the member's accounts.

use std::collections::{BTreeMap, HashMap};

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use async_trait::async_trait;
use chrono::{DateTime, Months, NaiveDate, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use serde::Serialize;
use std::sync::LazyLock;

use super::adapter::{self, EXTERNAL_ID_CRYPTO, EXTERNAL_ID_SECURITIES, TrAccountData};
use super::csv;
use crate::AppState;
use crate::connectors::sidecar::template::error;
use crate::connectors::sidecar::{
    JobResult, SessionView, SidecarSession, SidecarSyncStatus, SyncJob, SyncStrategy, SyncTemplate,
};
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::holding_dedup::{HoldingAgg, vwap_merge};
use crate::ledger::instrument::TickerResult;
use crate::ledger::model::{Account, AccountType, HoldingDraft, normalize_isin};
use crate::ledger::prices::PriceNeeds;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode, SourceSyncResult};
use crate::ledger::sync_values::java_trim;
use crate::ledger::valuation::{self, SnapshotContext};
use crate::ledger::{holdings, members, transactions};

const BANK: SidecarBank = SidecarBank::TradeRepublic;
pub const PROVIDER: &str = "Trade Republic";
const SOURCE: &str = "trade-republic";
const FIRST_IMPORT_YEARS: u32 = 3;
const OVERLAP_DAYS: u64 = 14;
/// The longest window tr-auth's /transactions accepts (EXPORT_MAX_DAYS there).
const EXPORT_MAX_DAYS: u64 = 1830;

static INTERNATIONAL_PHONE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^\+[0-9]{6,15}$").expect("regex"));
static PHONE_SEPARATORS: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"[ \t\n\x0B\x0C\r.\-()]").expect("regex"));

/// SessionStatusResponse of Trade Republic: no expiry, the masked phone instead.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionStatusResponse {
    pub is_active: bool,
    pub sync_status: SidecarSyncStatus,
    pub last_sync_started_at: Option<DateTime<Utc>>,
    pub last_sync_completed_at: Option<DateTime<Utc>>,
    pub last_sync_error: Option<SidecarErrorCode>,
    pub masked_phone: Option<String>,
}

impl SessionStatusResponse {
    fn inactive() -> Self {
        Self {
            is_active: false,
            sync_status: SidecarSyncStatus::Idle,
            last_sync_started_at: None,
            last_sync_completed_at: None,
            last_sync_error: None,
            masked_phone: None,
        }
    }
}

impl SessionView for SessionStatusResponse {
    fn is_active(&self) -> bool {
        self.is_active
    }

    fn last_sync_error(&self) -> Option<SidecarErrorCode> {
        self.last_sync_error
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthInitResponse {
    pub process_id: Option<String>,
    pub flow: Option<String>,
    pub countdown_in_seconds: Option<i32>,
}

/// What a sync fetched: the accounts, their resolved tickers (so the commit makes no network
/// call), and TR's CSV export of the activity (None when it was not fetched).
pub struct Prepared {
    accounts: Vec<TrAccountData>,
    tickers: HashMap<String, TickerResult>,
    csv: Option<String>,
}

fn template() -> SyncTemplate<Strategy> {
    SyncTemplate::new(BANK, Strategy, false)
}

pub struct Strategy;

#[async_trait]
impl SyncStrategy for Strategy {
    type Prepared = Prepared;

    async fn fetch(&self, state: &AppState, job: &SyncJob) -> JobResult<Prepared> {
        let session = adapter::open_session(state, &job.plain_state).await?;
        let token = session.session_token.clone().unwrap_or_default();
        let accounts = adapter::fetch_accounts(state, &token, session.claims.as_ref()).await?;
        // OpenFIGI can take seconds per ISIN: resolved here, outside the commit.
        let mut tickers = HashMap::new();
        for data in &accounts {
            for position in &data.positions {
                if !tickers.contains_key(&position.isin) {
                    let resolved = state.connectors.seams.isin.resolve(&position.isin).await;
                    tickers.insert(position.isin.clone(), resolved);
                }
            }
        }
        let csv = fetch_transactions_csv(state, job).await?;
        Ok(Prepared {
            accounts,
            tickers,
            csv,
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
        for data in &prepared.accounts {
            upsert_account(
                state,
                conn,
                data,
                job.member_id,
                Some(&prepared.tickers),
                synced_at,
            )
            .await?;
        }
        // Native crypto lives in the default portfolio: when it answered with none left, an
        // existing TR Crypto is cleared, but an empty one is never created.
        let default_answered = prepared
            .accounts
            .iter()
            .any(|a| a.external_id == EXTERNAL_ID_SECURITIES);
        let crypto_returned = prepared
            .accounts
            .iter()
            .any(|a| a.external_id == EXTERNAL_ID_CRYPTO);
        if default_answered
            && !crypto_returned
            && let Some(existing) = accounts::find_by_external_id_and_member(
                &mut *conn,
                EXTERNAL_ID_CRYPTO,
                job.member_id,
            )
            .await?
        {
            let cleared = TrAccountData {
                external_id: EXTERNAL_ID_CRYPTO.to_owned(),
                name: existing.name.clone(),
                account_type: AccountType::Crypto,
                balance_eur: Decimal::ZERO,
                positions: Vec::new(),
                cash_eur: None,
            };
            upsert_account(
                state,
                conn,
                &cleared,
                job.member_id,
                Some(&prepared.tickers),
                synced_at,
            )
            .await?;
        }
        Ok(())
    }

    /// In its own transaction: a row the importer cannot write rolls back the transactions only,
    /// never the balances and holdings just synced.
    async fn after_commit(&self, state: &AppState, job: &SyncJob, prepared: &Prepared) {
        let Some(csv) = &prepared.csv else {
            return;
        };
        let outcome = async {
            let mut tx = state.db.begin().await?;
            let result =
                csv::import_transactions(state, &mut tx.as_handle(), csv, job.member_id).await?;
            tx.commit().await?;
            Ok::<_, AppError>(result)
        }
        .await;
        match outcome {
            Ok(result) => tracing::info!(
                "Trade Republic transactions imported (member={}; tx={}; skipped={}; nonEur={})",
                job.member_id,
                result.inserted,
                result.skipped,
                result.non_eur
            ),
            Err(e) => tracing::warn!(
                "Trade Republic transactions import failed (member={}; type={})",
                job.member_id,
                kind(&e)
            ),
        }
    }

    fn price_needs(&self, prepared: &Prepared) -> PriceNeeds {
        let mut needs = PriceNeeds::default();
        for account in &prepared.accounts {
            needs.account(
                account.account_type,
                "EUR",
                None,
                account
                    .positions
                    .iter()
                    .filter_map(|position| prepared.tickers.get(&position.isin))
                    .map(|resolved| resolved.ticker.as_str()),
            );
        }
        needs
    }

    fn describe(&self, prepared: &Prepared) -> String {
        format!(
            "accounts={}{}",
            prepared.accounts.len(),
            if prepared.csv.is_some() {
                "; transactions=fetched"
            } else {
                ""
            }
        )
    }
}

fn kind(error: &AppError) -> &'static str {
    match error {
        AppError::Internal(detail) if detail.starts_with("database") => "DataAccessException",
        AppError::Coded { .. } | AppError::Status { .. } => "SyncException",
        _ => "RuntimeException",
    }
}

/// The export since the last import, or None when the sidecar cannot render it: the balances are
/// worth syncing without the history. An expired session still fails the sync.
async fn fetch_transactions_csv(state: &AppState, job: &SyncJob) -> JobResult<Option<String>> {
    let today = state
        .clock
        .now()
        .with_timezone(&chrono_tz::Europe::Paris)
        .date_naive();
    let start = match transactions_window_start(state, job.member_id, today).await {
        Ok(start) => start,
        Err(_) => {
            tracing::warn!(
                "Trade Republic transactions export unavailable (member={}; type=DataAccessException)",
                job.member_id
            );
            return Ok(None);
        }
    };
    match adapter::fetch_transactions_csv(state, &job.plain_state, start, today).await {
        Ok(csv) => Ok(Some(csv)),
        Err(e) if e.code() == Some(SidecarErrorCode::SessionExpired.as_str()) => Err(e.into()),
        Err(e) => {
            tracing::warn!(
                "Trade Republic transactions export unavailable (member={}; code={})",
                job.member_id,
                e.code().unwrap_or("null")
            );
            Ok(None)
        }
    }
}

/// The newest imported TR Cash entry minus a safety overlap, three years back for a first import,
/// never further back than one export reaches.
async fn transactions_window_start(
    state: &AppState,
    member_id: i64,
    today: NaiveDate,
) -> sqlx::Result<NaiveDate> {
    let cash = accounts::find_all_by_member(&state.db, member_id)
        .await?
        .into_iter()
        .find(|a| {
            a.provider.as_deref() == Some(PROVIDER) && a.account_type == AccountType::Checking
        });
    let latest = match cash {
        Some(account) => transactions::latest_synced_date(&state.db, account.id).await?,
        None => None,
    };
    let start = match latest {
        Some(latest) => latest - chrono::Days::new(OVERLAP_DAYS),
        None => today
            .checked_sub_months(Months::new(12 * FIRST_IMPORT_YEARS))
            .unwrap_or(today),
    };
    let oldest = today - chrono::Days::new(EXPORT_MAX_DAYS);
    Ok(start.max(oldest))
}

pub async fn initiate_auth(
    state: &AppState,
    phone_number: &str,
    pin: &str,
) -> AppResult<AuthInitResponse> {
    let result = adapter::initiate_auth(state, phone_number, pin).await?;
    Ok(AuthInitResponse {
        process_id: result.process_id,
        flow: result.flow,
        countdown_in_seconds: result.countdown_in_seconds,
    })
}

/// The phone kept in the member's stored session (active or not), for a PIN-only reconnect.
pub async fn stored_phone(state: &AppState, member_id: i64) -> AppResult<Option<String>> {
    Ok(template()
        .find(state, member_id)
        .await?
        .and_then(|s| phone_of(state, &s)))
}

/// Waits for the second factor, stores the session and queues the first import. A disconnect
/// during the (up to two minutes) wait wins over the login.
pub async fn complete_auth(
    state: &AppState,
    process_id: &str,
    tan: Option<&str>,
    member_id: i64,
) -> AppResult<SessionStatusResponse> {
    let runtime = &state.connectors.trade_republic;
    let generation = runtime.generation(member_id);
    let plain_state = adapter::complete_auth(state, process_id, tan).await?;
    let template = template();
    let job = {
        let _guard = runtime.session_write.lock().await;
        if runtime.generation(member_id) != generation {
            return Err(error(
                SidecarErrorCode::AuthAttemptExpired,
                "The Trade Republic connection was removed during the login",
            )
            .into());
        }
        let mut tx = state.db.begin().await?;
        let job = template
            .insert_queued(
                state,
                &mut tx.as_handle(),
                member_id,
                plain_state,
                false,
                |_| Ok(()),
            )
            .await?;
        // The member re-authenticated on purpose: their deleted TR accounts come back, so the
        // first sync updates rather than skips them.
        accounts::restore_soft_deleted_by_provider(&mut tx, member_id, PROVIDER).await?;
        tx.commit().await?;
        job
    };
    let status = get_status(state, member_id).await?;
    template.submit(state, job)?;
    tracing::info!("Trade Republic session stored for member {member_id}, import queued");
    Ok(status)
}

pub async fn queue_sync(state: &AppState, member_id: i64) -> AppResult<SessionStatusResponse> {
    let session = template().queue(state, member_id, false).await?;
    Ok(to_status(state, &session))
}

pub async fn get_status(state: &AppState, member_id: i64) -> AppResult<SessionStatusResponse> {
    Ok(template()
        .find(state, member_id)
        .await?
        .map_or_else(SessionStatusResponse::inactive, |s| to_status(state, &s)))
}

/// Whether a stored session was there to delete.
pub async fn clear_session(state: &AppState, member_id: i64) -> AppResult<bool> {
    let runtime = &state.connectors.trade_republic;
    let removed = {
        let _guard = runtime.session_write.lock().await;
        runtime.bump_generation(member_id);
        template().clear(state, member_id).await?
    };
    tracing::info!("Trade Republic session cleared for member {member_id}");
    Ok(removed)
}

/// Persisted in-flight syncs cannot survive a restart: they become retryable failures.
pub async fn recover_interrupted_syncs(state: &AppState) -> sqlx::Result<u64> {
    template().recover_interrupted(state).await
}

/// The daily scheduler's entry (track T8).
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    template()
        .resync_reporting(member_id, SOURCE, get_status(state, member_id), || async {
            queue_sync(state, member_id).await.map(|_| ())
        })
        .await
}

fn to_status(state: &AppState, session: &SidecarSession) -> SessionStatusResponse {
    SessionStatusResponse {
        is_active: session.active,
        sync_status: session.sync_status,
        last_sync_started_at: session.last_sync_started_at,
        last_sync_completed_at: session.last_sync_completed_at,
        last_sync_error: session.last_sync_error,
        masked_phone: mask_phone(phone_of(state, session).as_deref()),
    }
}

/// The phone in the session's decrypted state; None when absent, unreadable or not JSON. Never
/// logged.
fn phone_of(state: &AppState, session: &SidecarSession) -> Option<String> {
    let plain = state.crypto.decrypt(&session.session_state).ok()?;
    let root: serde_json::Value = serde_json::from_str(&plain).ok()?;
    let phone = root.get("phone")?.as_str()?;
    let trimmed = java_trim(phone);
    (!trimmed.is_empty()).then(|| trimmed.to_owned())
}

/// Keeps the country prefix and the last two digits: +33612345699 becomes +33 * ** ** ** 99.
/// Separators are ignored; None for anything that is not an international number.
pub fn mask_phone(phone: Option<&str>) -> Option<String> {
    let phone = PHONE_SEPARATORS.replace_all(phone?, "").into_owned();
    if !INTERNATIONAL_PHONE.is_match(&phone) {
        return None;
    }
    let (prefix, national) = phone.split_at(3);
    if national.len() <= 2 {
        return None;
    }
    let masked = format!(
        "{}{}",
        "*".repeat(national.len() - 2),
        &national[national.len() - 2..]
    );
    let head = masked.len() % 2;
    let mut grouped = masked[..head].to_owned();
    let mut i = head;
    while i < masked.len() {
        if !grouped.is_empty() {
            grouped.push(' ');
        }
        grouped.push_str(&masked[i..i + 2]);
        i += 2;
    }
    Some(format!("{prefix} {grouped}"))
}

fn color_for(account_type: AccountType) -> &'static str {
    match account_type {
        AccountType::Pea => "#10b981",
        AccountType::CompteTitres => "#3b82f6",
        AccountType::Crypto => "#f59e0b",
        AccountType::Savings => "#8b5cf6",
        _ => "#6366f1",
    }
}

/// Upserts one TR account. `tickers` is the resolved ticker of every position's ISIN, or None
/// for a CSV import that keeps the account's holdings as they are. None when skipped.
pub async fn upsert_account(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    data: &TrAccountData,
    member_id: i64,
    tickers: Option<&HashMap<String, TickerResult>>,
    now: DateTime<Utc>,
) -> AppResult<Option<Account>> {
    let existing =
        accounts::find_by_external_id_and_member(&mut *conn, &data.external_id, member_id).await?;
    if existing.is_none()
        && accounts::exists_soft_deleted_by_external_id(&mut *conn, &data.external_id, member_id)
            .await?
    {
        tracing::info!(
            "TR: skipping resurrection of soft-deleted account externalId={} member={member_id}",
            data.external_id
        );
        return Ok(None);
    }
    // No brand-new empty TR Titres for a member holding only crypto, a PEA or cash.
    if existing.is_none()
        && tickers.is_some()
        && data.external_id == EXTERNAL_ID_SECURITIES
        && data.positions.is_empty()
        && data.balance_eur.is_zero()
        && data.cash_eur.is_none()
    {
        tracing::info!("TR: skipping creation of empty TR Titres account for member {member_id}");
        return Ok(None);
    }
    let account = match existing {
        Some(mut account) => {
            account.current_balance = data.balance_eur;
            // Null means the pocket is unknown this time: the last known one stays.
            if data.cash_eur.is_some() {
                account.cash_balance = data.cash_eur;
            }
            account.last_synced_at = Some(now);
            accounts::update(&mut *conn, &account, now).await?
        }
        None => {
            if members::find_by_id(&mut *conn, member_id).await?.is_none() {
                return Err(AppError::NotFound("Family member not found".to_owned()));
            }
            let mut draft = NewAccount::new(member_id, data.name.clone(), data.account_type);
            draft.provider = Some(PROVIDER.to_owned());
            draft.current_balance = data.balance_eur;
            draft.cash_balance = data.cash_eur;
            draft.last_synced_at = Some(now);
            draft.external_account_id = Some(data.external_id.clone());
            draft.is_manual = false;
            draft.color = color_for(data.account_type).to_owned();
            accounts::insert(&mut *conn, &draft, now).await?
        }
    };
    // As the saved entity Java answers with: the figures as given, not re-read at the column's scale.
    let mut account = account;
    account.current_balance = data.balance_eur;
    if data.cash_eur.is_some() {
        account.cash_balance = data.cash_eur;
    }
    // The snapshot comes after the holdings are replaced: its invested amount is read from them.
    if let Some(tickers) = tickers {
        holdings::delete_by_account(&mut *conn, account.id).await?;
        write_holdings(state, conn, &account, data, tickers, now).await?;
    }
    let ctx = SnapshotContext {
        prices: &*state.ledger.prices,
        today: state.clock.today(),
        now,
    };
    valuation::upsert_snapshot(&mut *conn, &ctx, &account, data.balance_eur, ctx.today).await?;
    Ok(Some(account))
}

/// The positions merged by ticker (VWAP), each with the broker's value at cents per position.
async fn write_holdings(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    account: &Account,
    data: &TrAccountData,
    tickers: &HashMap<String, TickerResult>,
    now: DateTime<Utc>,
) -> AppResult<()> {
    if data.positions.is_empty() {
        return Ok(());
    }
    let mut deduped: Vec<(String, HoldingAgg)> = Vec::new();
    let mut provider_values: HashMap<String, Decimal> = HashMap::new();
    let mut incomplete: Vec<String> = Vec::new();
    let mut isin_by_ticker = BTreeMap::new();
    for position in &data.positions {
        let resolved = tickers
            .get(&position.isin)
            .cloned()
            .unwrap_or(TickerResult {
                ticker: position.isin.clone(),
                name: None,
            });
        let ticker = resolved.ticker;
        isin_by_ticker.insert(ticker.clone(), position.isin.clone());
        match provider_value_eur(position) {
            Some(value) => *provider_values.entry(ticker.clone()).or_default() += value,
            None => incomplete.push(ticker.clone()),
        }
        let agg = HoldingAgg {
            quantity: position.quantity,
            average_buy_in: Some(position.average_buy_in),
            current_price: position.current_price,
            name: resolved.name,
        };
        match deduped.iter_mut().find(|(t, _)| *t == ticker) {
            Some(entry) => entry.1 = vwap_merge(&entry.1, &agg),
            None => deduped.push((ticker, agg)),
        }
    }
    state
        .connectors
        .seams
        .security_identities
        .record_on(&mut *conn, &isin_by_ticker, now)
        .await;
    for (ticker, agg) in deduped {
        // vwap_merge can net two positions to exactly zero: a flat position, not a holding.
        if agg.quantity.is_zero() {
            continue;
        }
        let provider_value_eur = if incomplete.contains(&ticker) {
            None
        } else {
            provider_values
                .get(&ticker)
                .map(|v| set_scale(*v, 8, HALF_UP))
        };
        let draft = HoldingDraft {
            isin: normalize_isin(isin_by_ticker.get(&ticker).map(String::as_str)),
            ticker,
            name: agg.name,
            quantity: agg.quantity,
            average_buy_in: agg.average_buy_in,
            current_price: agg.current_price,
            quote_currency: Some("EUR".to_owned()),
            provider_value_eur,
            last_synced_at: Some(now),
            ..HoldingDraft::default()
        };
        holdings::insert(&mut *conn, account.id, &draft, now).await?;
    }
    Ok(())
}

/// The adapter's own rounding of each position to cents, so the stored value matches the total.
fn provider_value_eur(position: &adapter::TrPosition) -> Option<Decimal> {
    let price = match position.current_price {
        Some(price) if price > Decimal::ZERO => price,
        _ => position.average_buy_in,
    };
    (price > Decimal::ZERO).then(|| set_scale(price * position.quantity, 2, HALF_UP))
}

#[cfg(test)]
mod tests {
    use super::mask_phone;

    #[test]
    fn a_phone_keeps_its_prefix_and_last_two_digits() {
        assert_eq!(
            mask_phone(Some("+33612345699")).as_deref(),
            Some("+33 * ** ** ** 99")
        );
        assert_eq!(
            mask_phone(Some("+491701234567")).as_deref(),
            Some("+49 ** ** ** ** 67")
        );
        assert_eq!(
            mask_phone(Some("+33 6 12.34-56 (99)")).as_deref(),
            Some("+33 * ** ** ** 99")
        );
        assert_eq!(mask_phone(Some("0612345699")), None);
        assert_eq!(mask_phone(Some("+12345")), None);
        assert_eq!(mask_phone(None), None);
    }
}
