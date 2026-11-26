//! CryptoExchangeSyncService and CryptoExchangeStatusWriter.

use std::collections::{BTreeMap, BTreeSet, HashMap};

use crate::db::DbHandle;
use chrono::{DateTime, NaiveDate, Utc};
use http::StatusCode;
use rust_decimal::Decimal;
use serde_json::Value;

use super::model::{
    ExchangeCredentials, ExchangePosition, ExchangePositionResponse, ExchangeStatusResponse,
    ExchangeType, Product, SessionRow,
};
use super::port::{self, ExchangePort, FetchError};
use super::store::{self, SessionDraft};
use crate::AppState;
use crate::db::AsDbHandle;
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::model::{Account, AccountType};
use crate::ledger::sidecar::{SourceSyncResult, SyncStatus};
use crate::ledger::sync_values::{java_is_blank, java_strip, java_trim};
use crate::ledger::valuation::{self, SnapshotContext};
use crate::ledger::{holdings, members};

/// 500 plaintext bytes of varchar hold 347 bytes once AES-GCM encrypted and Base64 encoded.
const API_PASSPHRASE_MAX_BYTES: usize = 347;
const SOURCE: &str = "crypto-exchanges";

/// The credentials as the request carried them, before normalization.
#[derive(Debug, Default)]
pub struct SuppliedCredentials {
    pub api_key: Option<String>,
    pub api_secret: Option<String>,
    pub api_passphrase: Option<String>,
    pub region: Option<String>,
}

fn session_not_found() -> AppError {
    AppError::NotFound("Exchange session not found".to_owned())
}

fn sync_error(detail: impl Into<String>) -> AppError {
    AppError::status(StatusCode::UNPROCESSABLE_ENTITY, detail)
}

fn unsupported() -> AppError {
    sync_error("This exchange isn't supported yet.")
}

fn adapter_of(state: &AppState, exchange: ExchangeType) -> AppResult<Box<dyn ExchangePort + '_>> {
    port::adapter(state, exchange).ok_or_else(unsupported)
}

fn trim_to_null(value: Option<&str>) -> Option<String> {
    value
        .map(java_trim)
        .filter(|v| !v.is_empty())
        .map(str::to_owned)
}

fn validate_region(
    exchange: ExchangeType,
    supplied: Option<&str>,
    regions: &[&str],
) -> AppResult<Option<String>> {
    let region = java_strip(supplied.unwrap_or_default()).to_uppercase();
    if regions.is_empty() {
        if !region.is_empty() {
            return Err(AppError::BadRequest(format!(
                "{exchange} does not use a region."
            )));
        }
        return Ok(None);
    }
    if !regions.contains(&region.as_str()) {
        let requirement = if region.is_empty() {
            " requires a region."
        } else {
            " does not support the selected region."
        };
        return Err(AppError::BadRequest(format!(
            "{exchange}{requirement} Allowed regions: {}",
            regions.join(", ")
        )));
    }
    Ok(Some(region))
}

fn encrypt(state: &AppState, value: Option<&str>) -> AppResult<Option<String>> {
    Ok(value.map(|v| state.crypto.encrypt(v)).transpose()?)
}

fn decrypt(state: &AppState, value: Option<&str>) -> AppResult<Option<String>> {
    Ok(value.map(|v| state.crypto.decrypt(v)).transpose()?)
}

/// Validates the adapter's credential rules before any network call, tests the connection,
/// saves the encrypted session and runs its first sync, all or nothing.
pub async fn add_exchange(
    state: &AppState,
    exchange: ExchangeType,
    supplied: SuppliedCredentials,
    member_id: i64,
) -> AppResult<Value> {
    let adapter = adapter_of(state, exchange)?;
    let key = trim_to_null(supplied.api_key.as_deref());
    let secret = trim_to_null(supplied.api_secret.as_deref());
    let passphrase = supplied.api_passphrase.filter(|p| !java_is_blank(p));
    let Some(key) = key else {
        return Err(AppError::BadRequest("An API key is required.".into()));
    };
    if adapter.requires_api_secret() && secret.is_none() {
        return Err(AppError::BadRequest(format!(
            "{exchange} requires an API secret as well as an API key."
        )));
    }
    if !adapter.requires_api_secret() && secret.is_some() {
        return Err(AppError::BadRequest(format!(
            "{exchange} connects with a single read-only API key and takes no API secret."
        )));
    }
    if adapter.requires_api_passphrase() && passphrase.is_none() {
        return Err(AppError::BadRequest(format!(
            "{exchange} requires an API passphrase."
        )));
    }
    if !adapter.requires_api_passphrase() && passphrase.is_some() {
        return Err(AppError::BadRequest(format!(
            "{exchange} does not use an API passphrase."
        )));
    }
    if passphrase
        .as_ref()
        .is_some_and(|p| p.len() > API_PASSPHRASE_MAX_BYTES)
    {
        return Err(AppError::BadRequest(format!(
            "The API passphrase exceeds {API_PASSPHRASE_MAX_BYTES} UTF-8 bytes."
        )));
    }
    let region = validate_region(exchange, supplied.region.as_deref(), adapter.regions())?;
    let credentials = ExchangeCredentials {
        api_key: key,
        api_secret: secret,
        api_passphrase: passphrase,
        region,
    };
    match adapter.test_connection(&credentials).await {
        Ok(true) => {}
        Ok(false) => return Err(connection_error(exchange)),
        Err(FetchError::Sync(detail)) => {
            tracing::warn!("Crypto exchange connection test failed for {exchange}: {detail}");
            return Err(sync_error(detail));
        }
        Err(FetchError::Coded { code, detail }) => {
            tracing::warn!("Crypto exchange connection test failed for {exchange}: {detail}");
            return Err(coded_sync_error(code, detail));
        }
        Err(FetchError::Unexpected(cause)) => {
            tracing::error!("Crypto exchange connection test failed for {exchange}: {cause}");
            return Err(connection_error(exchange));
        }
    }
    members::find_by_id(&state.db, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Family member not found".into()))?;
    let existing = store::session_by_type(&state.db, exchange.as_str(), member_id)
        .await?
        .map(|s| s.id);
    let api_key = state.crypto.encrypt(&credentials.api_key)?;
    let api_secret = encrypt(state, credentials.api_secret.as_deref())?;
    let api_passphrase = encrypt(state, credentials.api_passphrase.as_deref())?;
    let draft = SessionDraft {
        member_id,
        exchange_type: exchange.as_str(),
        api_key: &api_key,
        api_secret: api_secret.as_deref(),
        api_passphrase: api_passphrase.as_deref(),
        region: credentials.region.as_deref(),
    };
    let account = run_sync(
        state,
        adapter.as_ref(),
        exchange,
        member_id,
        &credentials,
        Target::Save { existing, draft },
    )
    .await?;
    respond(state, &account).await
}

fn connection_error(exchange: ExchangeType) -> AppError {
    sync_error(format!(
        "Could not connect to {exchange}. Please check your API keys."
    ))
}

fn coded_sync_error(code: &'static str, detail: String) -> AppError {
    AppError::coded(StatusCode::UNPROCESSABLE_ENTITY, code, detail)
}

/// Syncs one of the member's sessions.
pub async fn sync(state: &AppState, session_id: i64, member_id: i64) -> AppResult<Value> {
    let session = store::session_of_member(&state.db, session_id, member_id)
        .await?
        .ok_or_else(session_not_found)?;
    let exchange = ExchangeType::parse(&session.exchange_type).ok_or_else(unsupported)?;
    let adapter = adapter_of(state, exchange)?;
    let credentials = credentials_of(state, &session)?;
    let account = run_sync(
        state,
        adapter.as_ref(),
        exchange,
        member_id,
        &credentials,
        Target::Existing(session.id),
    )
    .await?;
    respond(state, &account).await
}

fn credentials_of(state: &AppState, session: &SessionRow) -> AppResult<ExchangeCredentials> {
    Ok(ExchangeCredentials {
        api_key: state.crypto.decrypt(&session.api_key)?,
        api_secret: decrypt(state, session.api_secret.as_deref())?,
        api_passphrase: decrypt(state, session.api_passphrase.as_deref())?,
        region: session.region.clone(),
    })
}

async fn respond(state: &AppState, account: &Account) -> AppResult<Value> {
    state
        .sync
        .exchanges
        .accounts
        .account_response(state, account)
        .await
}

/// Which session a sync writes: one already saved, or credentials to save in the same
/// transaction (addExchange).
enum Target<'a> {
    Existing(i64),
    Save {
        existing: Option<i64>,
        draft: SessionDraft<'a>,
    },
}

/// What failed inside a sync. Specific messages name what to do and reach the user as-is;
/// anything else is logged and replaced by the generic "try again later".
enum Failure {
    Specific(String),
    Coded { code: &'static str, detail: String },
    Generic(String),
}

impl From<FetchError> for Failure {
    fn from(error: FetchError) -> Self {
        match error {
            FetchError::Sync(message) => Self::Specific(message),
            FetchError::Coded { code, detail } => Self::Coded { code, detail },
            FetchError::Unexpected(message) => Self::Generic(message),
        }
    }
}

impl From<sqlx::Error> for Failure {
    fn from(error: sqlx::Error) -> Self {
        Self::Generic(format!("database: {error}"))
    }
}

impl From<AppError> for Failure {
    fn from(error: AppError) -> Self {
        Self::Generic(error.to_string())
    }
}

/// Marks the session ERROR once the sync's transaction is over (a no-op for a session whose
/// insert was rolled back) and turns the failure into its 422.
async fn fail(
    state: &AppState,
    exchange: ExchangeType,
    session_id: Option<i64>,
    failure: Failure,
) -> AppError {
    if let Some(id) = session_id
        && let Err(e) = store::mark_error(&state.db, id, state.clock.now()).await
    {
        tracing::error!("Could not mark exchange session {id} as ERROR: {e}");
    }
    match failure {
        Failure::Specific(message) => {
            tracing::warn!("Crypto exchange sync failed for {exchange}: {message}");
            sync_error(message)
        }
        Failure::Coded { code, detail } => {
            tracing::warn!("Crypto exchange sync failed for {exchange}: {detail}");
            coded_sync_error(code, detail)
        }
        Failure::Generic(cause) => {
            tracing::error!("Crypto exchange sync failed for {exchange}: {cause}");
            sync_error(format!(
                "Could not sync your {exchange} account. Please try again later."
            ))
        }
    }
}

async fn run_sync(
    state: &AppState,
    adapter: &dyn ExchangePort,
    exchange: ExchangeType,
    member_id: i64,
    credentials: &ExchangeCredentials,
    target: Target<'_>,
) -> AppResult<Account> {
    let committed_id = match &target {
        Target::Existing(id) => Some(*id),
        Target::Save { existing, .. } => *existing,
    };
    let positions = match adapter.fetch_positions(credentials).await {
        Ok(positions) => positions,
        Err(e) => return Err(fail(state, exchange, committed_id, e.into()).await),
    };
    let now = state.clock.now();
    let today = state.clock.today();
    // Priced before the transaction opens: the snapshot it writes then finds the same quotes in
    // memory, and no provider is awaited while the writer is held.
    let valued = match value(state, exchange, &positions, today).await {
        Ok(valued) => valued,
        Err(e) => return Err(fail(state, exchange, committed_id, e.into()).await),
    };
    // The account is valued as stored too, with a ticker or a currency the member may have set.
    let stored = valuation::needs_of_external(
        &mut state.db.pool_handle(),
        member_id,
        &exchange.external_id(),
    )
    .await;
    match stored {
        Ok(needs) => state.ledger.prices.prefetch(&needs, today).await,
        Err(e) => return Err(fail(state, exchange, committed_id, e.into()).await),
    }
    let mut tx = match state.db.begin().await {
        Ok(tx) => tx,
        Err(e) => return Err(fail(state, exchange, committed_id, e.into()).await),
    };
    let outcome = async {
        let session_id = match &target {
            Target::Existing(id) => *id,
            Target::Save { existing, draft } => {
                store::save_session(&mut tx, *existing, draft, now).await?
            }
        };
        let account = apply(
            &mut tx.as_handle(),
            state,
            exchange,
            member_id,
            &valued,
            now,
            today,
        )
        .await?;
        store::mark_connected(&mut tx, session_id, now).await?;
        Ok::<_, Failure>(account)
    }
    .await;
    match outcome {
        Ok(account) => match tx.commit().await {
            Ok(()) => Ok(account),
            Err(e) => Err(fail(state, exchange, committed_id, e.into()).await),
        },
        Err(failure) => {
            if let Err(e) = tx.rollback().await {
                tracing::warn!("Rollback of the {exchange} sync failed: {e}");
            }
            Err(fail(state, exchange, committed_id, failure).await)
        }
    }
}

/// The positions, their sum per asset, and the EUR price of each asset that has one.
struct Valued<'a> {
    positions: &'a [ExchangePosition],
    quantities: BTreeMap<String, Decimal>,
    prices: HashMap<String, Decimal>,
    total: Decimal,
}

fn fits_numeric_20_8(amount: Decimal) -> bool {
    // NUMERIC(20,8) rounds ties away from zero before checking the 12 integer digits.
    amount.round_dp_with_strategy(8, HALF_UP).abs() < Decimal::new(1_000_000_000_000, 0)
}

fn quantities_in_range(
    exchange: ExchangeType,
    positions: &[ExchangePosition],
) -> Result<BTreeMap<String, Decimal>, FetchError> {
    let total_out_of_range = || {
        port::invalid_response(
            exchange,
            "A total quantity for one asset is outside the supported range.",
        )
    };
    let mut quantities: BTreeMap<String, Decimal> = BTreeMap::new();
    for position in positions {
        for (field, amount) in [
            ("quantity", Some(position.quantity)),
            ("principal", position.principal),
            ("interest", position.interest),
        ] {
            if amount.is_some_and(|amount| !fits_numeric_20_8(amount)) {
                return Err(port::invalid_response(
                    exchange,
                    &format!("A position {field} is outside the supported range."),
                ));
            }
        }
        let total = quantities
            .entry(position.symbol.to_uppercase())
            .or_insert(Decimal::ZERO);
        *total = total
            .checked_add(position.quantity)
            .ok_or_else(total_out_of_range)?;
    }
    if quantities
        .values()
        .any(|&amount| !fits_numeric_20_8(amount))
    {
        return Err(total_out_of_range());
    }
    Ok(quantities)
}

async fn value<'a>(
    state: &AppState,
    exchange: ExchangeType,
    positions: &'a [ExchangePosition],
    today: NaiveDate,
) -> Result<Valued<'a>, FetchError> {
    let quantities = quantities_in_range(exchange, positions)?;
    // Crypto-only: an unmapped coin must never be valued as the equity under the same symbol.
    let tickers: BTreeSet<String> = quantities.keys().cloned().collect();
    let quotes = state.ledger.prices.quotes(&tickers, true, today).await;
    let prices: HashMap<String, Decimal> = quotes
        .into_iter()
        .filter(|(ticker, _)| quantities.contains_key(ticker))
        .map(|(ticker, quote)| (ticker, quote.price))
        .collect();
    let mut total = Decimal::ZERO;
    let valuation_out_of_range = || {
        port::invalid_response(
            exchange,
            "The EUR valuation is outside the supported range.",
        )
    };
    for (ticker, quantity) in &quantities {
        match prices.get(ticker) {
            Some(&price) => {
                let line = quantity
                    .checked_mul(price)
                    .ok_or_else(valuation_out_of_range)?;
                total = total
                    .checked_add(set_scale(line, 2, HALF_UP))
                    .ok_or_else(valuation_out_of_range)?;
            }
            None => tracing::warn!("No EUR price for {ticker} -- skipping in total"),
        }
    }
    if !fits_numeric_20_8(total) {
        return Err(valuation_out_of_range());
    }
    Ok(Valued {
        positions,
        quantities,
        prices,
        total,
    })
}

/// Writes the account, its holdings (summed per asset), the per-product breakdown and today's
/// snapshot.
async fn apply(
    conn: &mut DbHandle<'_>,
    state: &AppState,
    exchange: ExchangeType,
    member_id: i64,
    valued: &Valued<'_>,
    now: DateTime<Utc>,
    today: NaiveDate,
) -> Result<Account, Failure> {
    let Valued {
        positions,
        quantities,
        prices,
        total,
    } = valued;
    let total = *total;
    let nothing_priced = !quantities.is_empty() && prices.is_empty();
    let external_id = exchange.external_id();

    // Nothing valued: refuse when there is a balance to overwrite (a zero snapshot is never
    // revisited); on the first sync create the account unvalued and write no snapshot.
    let existing =
        accounts::find_by_external_id_and_member(&mut *conn, &external_id, member_id).await?;
    if nothing_priced && existing.is_some() {
        return Err(Failure::Specific(format!(
            "No EUR price available for any asset held on this exchange -- refusing to record a zero \
             balance for {exchange}"
        )));
    }
    if nothing_priced {
        tracing::warn!(
            "No EUR price for any asset on the first {exchange} sync -- creating the account unvalued, \
             and recording no snapshot for today"
        );
    }

    let mut account = match existing {
        Some(mut account) => {
            account.current_balance = total;
            account.last_synced_at = Some(now);
            accounts::update(&mut *conn, &account, now).await?
        }
        None => {
            if accounts::exists_soft_deleted_by_external_id(&mut *conn, &external_id, member_id)
                .await?
            {
                tracing::info!(
                    "Crypto exchange: account externalId={external_id} was soft-deleted, skipping sync"
                );
                return Err(Failure::Specific(format!(
                    "This account's history was deleted. Remove the {exchange} session in Settings → Crypto \
                     to stop syncing it."
                )));
            }
            members::find_by_id(&mut *conn, member_id)
                .await?
                .ok_or_else(|| Failure::Generic("Family member not found".into()))?;
            let mut draft =
                NewAccount::new(member_id, format!("{exchange} Crypto"), AccountType::Crypto);
            draft.provider = Some(exchange.as_str().to_owned());
            draft.current_balance = total;
            draft.last_synced_at = Some(now);
            draft.external_account_id = Some(external_id);
            draft.is_manual = false;
            draft.color = "#f59e0b".to_owned();
            accounts::insert(&mut *conn, &draft, now).await?
        }
    };
    // The response reports the balance as computed, not at the column's scale.
    account.current_balance = total;

    for (ticker, quantity) in quantities {
        if let Some(price) = prices.get(ticker) {
            holdings::upsert_holding(
                &mut *conn,
                account.id,
                member_id,
                ticker,
                Some(ticker),
                *quantity,
                Some(*price),
                now,
            )
            .await?;
        }
    }
    // A coin sold or withdrawn is absent from the answer, and upsert never removes anything.
    let keep: Vec<String> = quantities.keys().cloned().collect();
    holdings::prune_holdings(&mut *conn, account.id, &keep).await?;
    store::replace_positions(&mut conn.as_handle(), account.id, positions, now).await?;
    if !nothing_priced {
        let ctx = SnapshotContext {
            prices: &*state.ledger.prices,
            today,
            now,
        };
        valuation::upsert_snapshot(&mut *conn, &ctx, &account, total, today).await?;
    }
    Ok(account)
}

/// Removes the session and soft-deletes its account: balance_snapshot cascades on account, so
/// a row delete would erase the exchange's whole net-worth history.
pub async fn remove_exchange(state: &AppState, session_id: i64, member_id: i64) -> AppResult<()> {
    let now = state.clock.now();
    let mut tx = state.db.begin().await?;
    let session = store::session_of_member(&mut tx, session_id, member_id)
        .await?
        .ok_or_else(session_not_found)?;
    let external_id = ExchangeType::parse(&session.exchange_type).map_or_else(
        || {
            format!(
                "crypto_exchange_{}",
                session.exchange_type.to_ascii_lowercase()
            )
        },
        ExchangeType::external_id,
    );
    if let Some(account) =
        accounts::find_by_external_id_and_member(&mut tx, &external_id, member_id).await?
    {
        store::soft_delete_account(&mut tx, account.id, now).await?;
    }
    store::delete_session(&mut tx, session.id).await?;
    tx.commit().await?;
    tracing::info!("Removed exchange session {session_id} and soft-deleted its account");
    Ok(())
}

/// Every session of the member, each in its own transaction; the failed exchanges are named,
/// never their error text, which may carry request details.
pub async fn resync_all_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    let result = |status, message: &str| SourceSyncResult {
        source: SOURCE.to_owned(),
        status,
        message: message.to_owned(),
    };
    let sessions = match store::sessions_of_member(&state.db, member_id).await {
        Ok(sessions) => sessions,
        Err(e) => {
            tracing::error!("Crypto exchange session lookup failed for member {member_id}: {e}");
            return result(SyncStatus::Failed, "Unexpected sync error");
        }
    };
    if sessions.is_empty() {
        return result(SyncStatus::SkippedNotConnected, "No connected exchange");
    }
    let mut failed = Vec::new();
    for session in sessions {
        if sync(state, session.id, member_id).await.is_err() {
            tracing::warn!(
                "Crypto exchange resync failed for {}",
                session.exchange_type
            );
            failed.push(session.exchange_type);
        }
    }
    if failed.is_empty() {
        result(SyncStatus::Synced, "")
    } else {
        result(SyncStatus::Failed, &failed.join(", "))
    }
}

/// The member's exchange connections.
pub async fn get_status(
    state: &AppState,
    member_id: i64,
) -> AppResult<Vec<ExchangeStatusResponse>> {
    Ok(store::sessions_of_member(&state.db, member_id)
        .await?
        .into_iter()
        .map(|s| ExchangeStatusResponse {
            id: s.id,
            exchange_type: s.exchange_type,
            status: s.status,
            last_synced_at: s.last_synced_at,
            region: s.region,
        })
        .collect())
}

/// The account's per-product breakdown at crypto prices (AccountController's
/// GET /api/accounts/{id}/positions). Empty for a spot-only exchange, which keeps the flat
/// holdings table and its edit and delete actions, and for any other account.
pub async fn get_positions(
    state: &AppState,
    account_id: i64,
    member_id: i64,
) -> AppResult<Vec<ExchangePositionResponse>> {
    accounts::get_or_throw(&state.db, account_id, member_id).await?;
    // Cost is tracked per asset: each line reuses the asset's unit cost on its own quantity.
    let mut unit_cost: HashMap<String, Decimal> = HashMap::new();
    for holding in holdings::find_by_account(&state.db, account_id).await? {
        if let Some(average) = holding.average_buy_in {
            unit_cost.entry(holding.ticker).or_insert(average);
        }
    }
    let rows = store::positions_of_account(&state.db, account_id).await?;
    let mut positions = Vec::with_capacity(rows.len());
    for row in rows {
        let product = Product::parse(&row.product)
            .ok_or_else(|| AppError::internal(format!("unknown product {}", row.product)))?;
        positions.push((product, row));
    }
    if positions
        .iter()
        .all(|(product, _)| *product == Product::Spot)
    {
        return Ok(Vec::new());
    }
    let tickers: BTreeSet<String> = positions.iter().map(|(_, r)| r.ticker.clone()).collect();
    let quotes = state
        .ledger
        .prices
        .quotes(&tickers, true, state.clock.today())
        .await;
    let logos = state.sync.exchanges.logos.logo_urls(&tickers).await;
    Ok(positions
        .into_iter()
        .map(|(product, row)| {
            let key = row.ticker.to_uppercase();
            let quote = quotes.get(&key);
            let price = quote.map(|q| q.price);
            let average_buy_in = unit_cost.get(&row.ticker).copied();
            let valued =
                valuation::position_valuation(account_id, row.quantity, price, average_buy_in);
            ExchangePositionResponse {
                product: product.as_str().to_owned(),
                logo_url: logos.get(&key).cloned(),
                ticker: row.ticker,
                quantity: row.quantity,
                principal: row.principal,
                interest: row.interest,
                average_buy_in,
                current_price_eur: price,
                current_value_eur: valued.value,
                cost_basis_eur: valued.cost_basis,
                pnl_eur: valued.pnl,
                pnl_percent: valued.pnl_percent,
                price_as_of: quote.map(|q| q.as_of),
                price_stale: quote.is_some_and(|q| !q.live),
            }
        })
        .collect())
}
