//! IbkrSyncService: stores the encrypted Flex credentials, pulls the open positions and maps
//! them onto one COMPTE_TITRES account per IBKR account, holdings replaced wholesale.

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde_json::Value;

use super::connection::{self, IbkrConnection};
use super::flex::{self, IbkrAccountData, IbkrPosition};
use super::response::IbkrConnectionStatusResponse;
use super::{SOURCE, masked_account, sync_error, sync_error_message};
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::holding_dedup::{HoldingAgg, vwap_merge};
use crate::ledger::instrument::{TickerResult, is_isin};
use crate::ledger::model::{Account, AccountType, HoldingDraft};
use crate::ledger::prices::PriceNeeds;
use crate::ledger::sidecar::{SourceSyncResult, SyncStatus};
use crate::ledger::sync_values::{java_is_blank, java_trim};
use crate::ledger::valuation::{self, SnapshotContext};
use crate::ledger::{holdings, members};

/// account_holding.ticker is VARCHAR(30), name VARCHAR(100).
const MAX_TICKER_LEN: usize = 30;
const MAX_NAME_LEN: usize = 100;
/// The asset categories priced and cost-based correctly; derivatives ignore the contract
/// multiplier in their per-share cost basis and rarely price.
const REPORTABLE_ASSET_CATEGORIES: [&str; 4] = ["STK", "FUND", "BOND", "CRYPTO"];
const NOT_CONNECTED: &str =
    "No Interactive Brokers connection. Please connect from the Interactive Brokers page.";

fn member_not_found() -> AppError {
    AppError::NotFound("Family member not found".to_owned())
}

/// Stores (or replaces) the member's Flex token and query id, encrypted at rest.
pub async fn connect(
    state: &AppState,
    token: &str,
    query_id: &str,
    member_id: i64,
) -> AppResult<()> {
    if java_is_blank(token) || java_is_blank(query_id) {
        return Err(AppError::BadRequest(
            "Both the Flex token and the query id are required.".to_owned(),
        ));
    }
    members::find_by_id(&state.db, member_id)
        .await?
        .ok_or_else(member_not_found)?;
    let token = state.crypto.encrypt(java_trim(token))?;
    let query_id = state.crypto.encrypt(java_trim(query_id))?;
    connection::upsert_credentials(&state.db, member_id, &token, &query_id, state.clock.now())
        .await?;
    tracing::info!("IBKR Flex credentials stored for member {member_id}");
    Ok(())
}

/// Connected?, last sync and the masked token. A token that no longer decrypts reads as ERROR
/// rather than failing the page that always calls this.
pub async fn connection_status(
    state: &AppState,
    member_id: i64,
) -> AppResult<IbkrConnectionStatusResponse> {
    let Some(connection) = connection::find_by_member(&state.db, member_id).await? else {
        return Ok(IbkrConnectionStatusResponse {
            connected: false,
            connection_id: None,
            status: None,
            last_synced_at: None,
            masked_token: None,
        });
    };
    let (status, masked_token) = match state.crypto.decrypt(&connection.token) {
        Ok(token) => (connection.status, mask_token(&token)),
        Err(error) => {
            tracing::error!(
                "IBKR: cannot decrypt stored token for connection {}: {error}",
                connection.id
            );
            ("ERROR".to_owned(), "••••".to_owned())
        }
    };
    Ok(IbkrConnectionStatusResponse {
        connected: true,
        connection_id: Some(connection.id),
        status: Some(status),
        last_synced_at: connection.last_synced_at,
        masked_token: Some(masked_token),
    })
}

/// Clears the member's connection; whether one was there to delete.
pub async fn delete_connection(state: &AppState, member_id: i64) -> AppResult<bool> {
    let deleted = connection::delete_by_member(&state.db, member_id).await?;
    tracing::info!("IBKR connection cleared for member {member_id}");
    Ok(deleted)
}

/// Manual sync with the stored connection: the AccountResponse of every account it updated.
pub async fn sync(state: &AppState, member_id: i64) -> AppResult<Vec<Value>> {
    let connection = connection::find_by_member(&state.db, member_id)
        .await?
        .ok_or_else(|| sync_error(NOT_CONNECTED))?;
    sync_with_connection(state, &connection, member_id).await
}

/// The daily and member-wide sync: SKIPPED_NOT_CONNECTED without a connection, and every
/// failure reported rather than raised.
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    let outcome = async {
        match connection::find_by_member(&state.db, member_id).await? {
            None => Ok(false),
            Some(connection) => sync_with_connection(state, &connection, member_id)
                .await
                .map(|_| true),
        }
    }
    .await;
    match outcome {
        Ok(false) => result(SyncStatus::SkippedNotConnected, "No connection"),
        Ok(true) => result(SyncStatus::Synced, ""),
        Err(error) => match sync_error_message(&error) {
            Some(message) => SourceSyncResult::from_sync_exception(SOURCE, None, Some(message)),
            None => {
                tracing::error!(
                    "IBKR scheduled sync failed unexpectedly for member {member_id}: {error}"
                );
                result(SyncStatus::Failed, "Unexpected sync error")
            }
        },
    }
}

fn result(status: SyncStatus, message: &str) -> SourceSyncResult {
    SourceSyncResult {
        source: SOURCE.to_owned(),
        status,
        message: message.to_owned(),
    }
}

/// Any failure leaves the connection in ERROR, written outside the sync's (rolled back)
/// transaction, as IbkrStatusWriter's REQUIRES_NEW does.
async fn sync_with_connection(
    state: &AppState,
    connection: &IbkrConnection,
    member_id: i64,
) -> AppResult<Vec<Value>> {
    match do_sync(state, connection, member_id).await {
        Ok(responses) => Ok(responses),
        Err(error) => {
            tracing::error!(
                "IBKR sync failed for connection {}, marking ERROR status: {error}",
                connection.id
            );
            match connection::mark_error(&state.db, connection.id, state.clock.now()).await {
                Ok(true) => {}
                Ok(false) => tracing::error!(
                    "Cannot mark IBKR connection {} as ERROR: row not found (connection deleted \
                     mid-sync?), the user will not see an error status",
                    connection.id
                ),
                Err(write) => tracing::error!(
                    "Cannot mark IBKR connection {} as ERROR: {write}",
                    connection.id
                ),
            }
            Err(error)
        }
    }
}

async fn do_sync(
    state: &AppState,
    connection: &IbkrConnection,
    member_id: i64,
) -> AppResult<Vec<Value>> {
    let credentials = state
        .crypto
        .decrypt(&connection.token)
        .and_then(|token| Ok((token, state.crypto.decrypt(&connection.query_id)?)));
    let Ok((token, query_id)) = credentials else {
        return Err(sync_error(
            "Could not decrypt the stored IBKR credentials — the encryption key may have changed. \
             Please reconnect with a fresh token.",
        ));
    };

    let statement = flex::fetch_open_positions(state, &token, &query_id).await?;

    let now = state.clock.now();
    let today = state.clock.today();
    // Asked before the transaction opens: OpenFIGI for the ticker of each position, then the
    // quotes of those tickers, which the valuation written below reads. Inside the transaction
    // these calls would keep SQLite's only writer for as long as the providers take to answer.
    let mut resolved = Vec::with_capacity(statement.len());
    let mut needs = PriceNeeds::default();
    let mut conn = state.db.pool_handle();
    for data in &statement {
        // An account in another base currency is refused below (or skipped, when deleted here):
        // nothing is asked for it.
        let tickers = if require_eur_base_currency(data).is_ok() {
            resolve_tickers(state, data).await
        } else {
            Vec::new()
        };
        needs.account(
            AccountType::CompteTitres,
            "EUR",
            None,
            tickers
                .iter()
                .flatten()
                .filter_map(|position| position.ticker.as_deref()),
        );
        needs.merge(
            valuation::needs_of_external(
                &mut conn,
                member_id,
                &format!("ibkr_{}", data.account_id),
            )
            .await?,
        );
        resolved.push(tickers);
    }
    state.ledger.prices.prefetch(&needs, today).await;

    let mut tx = state.db.begin().await?;
    let mut responses = Vec::with_capacity(statement.len());
    for (data, tickers) in statement.iter().zip(resolved) {
        if let Some(response) = upsert_account(
            state,
            &mut tx.as_handle(),
            data,
            tickers,
            member_id,
            now,
            today,
        )
        .await?
        {
            responses.push(response);
        }
    }
    connection::mark_synced(&mut tx, connection.id, now).await?;
    tx.commit().await?;
    tracing::info!(
        "IBKR sync complete for member {member_id}: {} account(s) updated",
        responses.len()
    );
    Ok(responses)
}

/// `tickers` is resolve_tickers' answer for `data`.
async fn upsert_account(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    data: &IbkrAccountData,
    tickers: Vec<Option<Resolved>>,
    member_id: i64,
    now: DateTime<Utc>,
    today: NaiveDate,
) -> AppResult<Option<Value>> {
    let external_id = format!("ibkr_{}", data.account_id);
    let existing =
        accounts::find_by_external_id_and_member(&mut *conn, &external_id, member_id).await?;
    if existing.is_none()
        && accounts::exists_soft_deleted_by_external_id(&mut *conn, &external_id, member_id).await?
    {
        tracing::info!(
            "IBKR: skipping resurrection of soft-deleted account {} for member {member_id}",
            masked_account(&data.account_id)
        );
        return Ok(None);
    }

    require_eur_base_currency(data)?;

    let mut account = match existing {
        Some(mut account) => {
            account.last_synced_at = Some(now);
            accounts::update(&mut *conn, &account, now).await?
        }
        None => {
            members::find_by_id(&mut *conn, member_id)
                .await?
                .ok_or_else(member_not_found)?;
            let mut draft = NewAccount::new(
                member_id,
                format!("IBKR {}", data.account_id),
                AccountType::CompteTitres,
            );
            draft.provider = Some("Interactive Brokers".to_owned());
            draft.current_balance = Decimal::ZERO;
            draft.last_synced_at = Some(now);
            draft.external_account_id = Some(external_id);
            draft.is_manual = false;
            draft.color = "#d81222".to_owned();
            accounts::insert(&mut *conn, &draft, now).await?
        }
    };

    // The statement is the full current picture.
    holdings::delete_by_account(&mut *conn, account.id).await?;

    let mut merged: Vec<(String, HoldingAgg)> = Vec::new();
    let mut isin_by_ticker: Vec<(String, String)> = Vec::new();
    for (position, resolved) in data.positions.iter().zip(tickers) {
        // None for a position that is not reportable.
        let Some(resolved) = resolved else {
            continue;
        };
        let Some(ticker) = resolved.ticker.filter(|t| !java_is_blank(t)) else {
            continue;
        };
        if let Some(isin) = position.isin.as_deref().filter(|isin| is_isin(isin)) {
            match isin_by_ticker.iter_mut().find(|(t, _)| *t == ticker) {
                Some(entry) => entry.1 = isin.to_owned(),
                None => isin_by_ticker.push((ticker.clone(), isin.to_owned())),
            }
        }
        let length = ticker.encode_utf16().count();
        if length > MAX_TICKER_LEN {
            // Options and futures carry long symbols that overflow the column and never price.
            tracing::warn!(
                "IBKR: skipping position with over-long ticker '{ticker}' ({length} chars)"
            );
            continue;
        }
        let quantity = position.position.unwrap_or_default();
        let next = HoldingAgg {
            quantity,
            average_buy_in: in_base_currency(position, position.cost_basis_price)?,
            current_price: in_base_currency(position, position.mark_price)?,
            name: resolved.name,
        };
        match merged.iter_mut().find(|(t, _)| *t == ticker) {
            Some((_, aggregate)) => *aggregate = vwap_merge(aggregate, &next),
            None => merged.push((ticker, next)),
        }
    }
    state
        .sync
        .ibkr
        .security_identities
        .record_on(&mut *conn, &isin_by_ticker)
        .await;

    let mut persisted = 0;
    for (ticker, aggregate) in merged {
        if aggregate.quantity.is_zero() {
            // Opposite legs can net to exactly zero: a flat position, not a holding.
            continue;
        }
        let draft = HoldingDraft {
            ticker,
            name: aggregate.name.map(|name| clip(&name, MAX_NAME_LEN)),
            quantity: aggregate.quantity,
            average_buy_in: aggregate.average_buy_in,
            current_price: aggregate.current_price,
            last_synced_at: Some(now),
            ..HoldingDraft::default()
        };
        holdings::insert(&mut *conn, account.id, &draft, now).await?;
        persisted += 1;
    }

    let prices = &*state.ledger.prices;
    // With nothing held the live path would fall back to the stored, now stale balance; a
    // holdings-driven account with no holding is worth zero (cash lines are not modeled).
    let live_eur = if persisted == 0 {
        Decimal::ZERO
    } else {
        valuation::live_balance_eur(&mut *conn, prices, &account, today).await?
    };
    account.current_balance = live_eur;
    let mut account: Account = accounts::update(&mut *conn, &account, now).await?;
    // The response carries the balance as computed, not as the column rounds it.
    account.current_balance = live_eur;
    let ctx = SnapshotContext { prices, today, now };
    valuation::upsert_snapshot(&mut *conn, &ctx, &account, live_eur, today).await?;

    let response = state
        .sync
        .ibkr
        .account_responses
        .to_response(&mut *conn, prices, &account, today)
        .await?;
    Ok(Some(response))
}

/// The cost basis is converted with fxRateToBase into the account's IBKR base currency, so it
/// is right only for a EUR-based account; a statement without AccountInformation assumes EUR.
fn require_eur_base_currency(data: &IbkrAccountData) -> AppResult<()> {
    match data.base_currency.as_deref() {
        None => {
            tracing::warn!(
                "IBKR: account {} statement has no AccountInformation/baseCurrency; assuming EUR",
                masked_account(&data.account_id)
            );
            Ok(())
        }
        Some(currency) if currency.eq_ignore_ascii_case("EUR") => Ok(()),
        Some(currency) => Err(sync_error(format!(
            "IBKR account base currency is {currency}; Picsou currently supports EUR-based \
             accounts — change the base currency in IBKR Account Settings, or reconnect after \
             conversion support lands."
        ))),
    }
}

/// Non-zero, summary-level (LOT rows duplicate it), not cash, an allowlisted (or absent) asset
/// category, and an ISIN or symbol to name it by.
fn is_reportable(position: &IbkrPosition) -> bool {
    if position.position.is_none_or(|q| q.is_zero()) {
        return false;
    }
    if position
        .level_of_detail
        .as_deref()
        .is_some_and(|level| level.eq_ignore_ascii_case("LOT"))
    {
        return false;
    }
    if let Some(category) = position.asset_category.as_deref() {
        if category.eq_ignore_ascii_case("CASH") {
            return false;
        }
        if !REPORTABLE_ASSET_CATEGORIES.contains(&category.to_uppercase().as_str()) {
            tracing::warn!(
                "IBKR: skipping unsupported assetCategory '{category}' for symbol '{}'",
                position.symbol.as_deref().unwrap_or("null")
            );
            return false;
        }
    }
    let present = |value: &Option<String>| value.as_deref().is_some_and(|v| !java_is_blank(v));
    present(&position.isin) || present(&position.symbol)
}

/// A ticker and display name, possibly absent.
struct Resolved {
    ticker: Option<String>,
    name: Option<String>,
}

/// The ticker and name of each position of the account, None for one that is not reportable.
async fn resolve_tickers(state: &AppState, data: &IbkrAccountData) -> Vec<Option<Resolved>> {
    let mut resolved = Vec::with_capacity(data.positions.len());
    for position in &data.positions {
        resolved.push(if is_reportable(position) {
            Some(resolve_ticker(state, position).await)
        } else {
            None
        });
    }
    resolved
}

/// Prefers the ISIN (so the holding prices); falls back to the IBKR symbol and description when
/// there is no usable ISIN or OpenFIGI only echoes it back.
async fn resolve_ticker(state: &AppState, position: &IbkrPosition) -> Resolved {
    match position.isin.as_deref().filter(|isin| is_isin(isin)) {
        Some(isin) => {
            let result = state.sync.ibkr.isin_resolver.resolve(isin).await;
            fall_back_to_symbol(position, result)
        }
        None => Resolved {
            ticker: position.symbol.clone(),
            name: position.description.clone(),
        },
    }
}

fn fall_back_to_symbol(position: &IbkrPosition, result: TickerResult) -> Resolved {
    if !is_isin(&result.ticker) {
        return Resolved {
            ticker: Some(result.ticker),
            name: result.name,
        };
    }
    Resolved {
        ticker: position.symbol.clone().or(Some(result.ticker)),
        name: result.name.or_else(|| position.description.clone()),
    }
}

/// A per-unit amount in the account base currency; None when unknown, including a non-EUR
/// position without fxRateToBase (taking 1 would store a USD price as EUR).
fn in_base_currency(
    position: &IbkrPosition,
    amount: Option<Decimal>,
) -> AppResult<Option<Decimal>> {
    let Some(amount) = amount else {
        return Ok(None);
    };
    let rate = match position.fx_rate_to_base {
        Some(rate) => rate,
        None => match position.currency.as_deref() {
            Some(currency) if !currency.eq_ignore_ascii_case("EUR") => {
                tracing::warn!(
                    "IBKR: position {} is in {currency} but the statement has no fxRateToBase; \
                     enable the 'FX Rate to Base' column on the Flex Query; storing cost basis as \
                     unknown",
                    position.symbol.as_deref().unwrap_or("null")
                );
                return Ok(None);
            }
            _ => Decimal::ONE,
        },
    };
    amount
        .checked_mul(rate)
        .map(Some)
        .ok_or_else(|| AppError::internal("IBKR amount overflows a decimal"))
}

/// The first max UTF-16 units, as String.substring.
fn clip(value: &str, max: usize) -> String {
    let mut units = 0;
    for (index, c) in value.char_indices() {
        units += c.len_utf16();
        if units > max {
            return value[..index].to_owned();
        }
    }
    value.to_owned()
}

/// Only the last four characters: "••••••3F29".
fn mask_token(token: &str) -> String {
    let units: Vec<u16> = token.encode_utf16().collect();
    if units.len() <= 4 {
        return "••••".to_owned();
    }
    format!(
        "••••••{}",
        String::from_utf16_lossy(&units[units.len() - 4..])
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(value: &str) -> Decimal {
        value.parse().expect("decimal")
    }

    fn position(isin: Option<&str>, symbol: &str, category: Option<&str>) -> IbkrPosition {
        IbkrPosition {
            account_id: Some("U1".into()),
            isin: isin.map(str::to_owned),
            symbol: Some(symbol.to_owned()),
            description: Some(format!("{symbol} INC")),
            currency: Some("USD".into()),
            asset_category: category.map(str::to_owned),
            level_of_detail: Some("SUMMARY".into()),
            position: Some(d("10")),
            mark_price: Some(d("200")),
            cost_basis_price: Some(d("150")),
            fx_rate_to_base: Some(d("0.92")),
        }
    }

    #[test]
    fn keeps_only_summary_rows_of_priceable_categories() {
        assert!(is_reportable(&position(None, "AAPL", Some("STK"))));
        assert!(is_reportable(&position(None, "AAPL", Some("fund"))));
        assert!(is_reportable(&position(None, "AAPL", None)));
        for category in ["OPT", "FUT", "FOP", "CFD", "WAR", "CASH"] {
            assert!(
                !is_reportable(&position(None, "X", Some(category))),
                "{category}"
            );
        }
        let mut lot = position(None, "AAPL", Some("STK"));
        lot.level_of_detail = Some("lot".into());
        assert!(!is_reportable(&lot));
        let mut flat = position(None, "AAPL", Some("STK"));
        flat.position = Some(d("0"));
        assert!(!is_reportable(&flat));
        let mut nameless = position(None, " ", Some("STK"));
        nameless.symbol = None;
        assert!(!is_reportable(&nameless));
    }

    #[test]
    fn an_openfigi_hit_wins_and_a_miss_falls_back_to_the_symbol() {
        let p = position(Some("US0378331005"), "AAPL", Some("STK"));
        let hit = fall_back_to_symbol(
            &p,
            TickerResult {
                ticker: "AAPL".into(),
                name: Some("Apple".into()),
            },
        );
        assert_eq!(hit.ticker.as_deref(), Some("AAPL"));
        assert_eq!(hit.name.as_deref(), Some("Apple"));
        let miss = fall_back_to_symbol(
            &p,
            TickerResult {
                ticker: "US0378331005".into(),
                name: None,
            },
        );
        assert_eq!(miss.ticker.as_deref(), Some("AAPL"));
        assert_eq!(miss.name.as_deref(), Some("AAPL INC"));
    }

    #[test]
    fn converts_to_the_base_currency_only_with_a_known_rate() {
        let p = position(None, "AAPL", Some("STK"));
        assert_eq!(
            in_base_currency(&p, Some(d("150"))).unwrap(),
            Some(d("138.00"))
        );
        let mut no_rate = p.clone();
        no_rate.fx_rate_to_base = None;
        assert_eq!(in_base_currency(&no_rate, Some(d("40"))).unwrap(), None);
        no_rate.currency = Some("eur".into());
        assert_eq!(
            in_base_currency(&no_rate, Some(d("40"))).unwrap(),
            Some(d("40"))
        );
        no_rate.currency = None;
        assert_eq!(
            in_base_currency(&no_rate, Some(d("40"))).unwrap(),
            Some(d("40"))
        );
    }

    #[test]
    fn masks_and_clips_like_java() {
        assert_eq!(mask_token("abcd"), "••••");
        assert_eq!(mask_token("123456783F29"), "••••••3F29");
        assert_eq!(clip("abcdef", 4), "abcd");
        assert_eq!(clip("ab😀cd", 3), "ab");
        assert_eq!(clip("abc", 4), "abc");
    }
}
