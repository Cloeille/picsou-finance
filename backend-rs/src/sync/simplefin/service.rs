//! SimplefinSyncService: claims a setup token, then maps the account set onto Picsou accounts,
//! positions and posted transactions. One connection per member.

use std::collections::{HashMap, HashSet};

use chrono::{DateTime, Days, NaiveDate, Utc};
use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::connection::{self, Connection};
use super::holdings::{self, Mapped, Position};
use super::json::{SimplefinAccount, SimplefinTransaction};
use super::{SyncError, client, currency, urls};
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::bank_import::{self, DEFAULT_INITIAL_HISTORY_DAYS, TransactionData, clip};
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::holdings as holding_rows;
use crate::ledger::model::{Account, AccountType, HoldingDraft};
use crate::ledger::prices::PriceRead;
use crate::ledger::sidecar::{SourceSyncResult, SyncStatus};
use crate::ledger::sync_values::{java_is_blank, java_trim};
use crate::ledger::valuation::{self, SnapshotContext};

/// Stamped on every imported account so Sync All and deletion can find the connection.
pub const PROVIDER: &str = "SimpleFIN";
/// The source name in the member sync and the MCP sync tools.
pub const SOURCE: &str = "simplefin";

const MAX_NAME_LEN: usize = 100;
const COLOR: &str = "#0d9488";

/// SimplefinConnectionStatusResponse. The access URL itself is never returned.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionStatus {
    pub connected: bool,
    pub connection_id: Option<i64>,
    pub status: Option<String>,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub masked_token: Option<String>,
}

/// A sync failure before it is reported: a SyncException, or anything else Java would catch
/// as a RuntimeException.
enum Failure {
    Sync(SyncError),
    Other(String),
}

impl From<SyncError> for Failure {
    fn from(error: SyncError) -> Self {
        Self::Sync(error)
    }
}

impl From<sqlx::Error> for Failure {
    fn from(error: sqlx::Error) -> Self {
        Self::Other(format!("database: {error}"))
    }
}

async fn member_exists(state: &AppState, member_id: i64) -> sqlx::Result<bool> {
    crate::db::query_scalar("SELECT EXISTS(SELECT 1 FROM family_member WHERE id = $1)")
        .bind(member_id)
        .fetch_one(&state.db)
        .await
}

fn member_not_found() -> AppError {
    AppError::NotFound("Family member not found".into())
}

/// Claims the token and stores the access URL. The member is checked first, so a request for
/// an unknown member never spends the single-use token.
pub async fn connect(state: &AppState, setup_token: &str, member_id: i64) -> AppResult<()> {
    if !member_exists(state, member_id).await? {
        return Err(member_not_found());
    }
    let access_url = client::claim(&state.sync.simplefin, setup_token).await?;
    let encrypted = state.crypto.encrypt(&access_url)?;
    connection::save_access(&state.db, member_id, &encrypted, state.clock.now()).await?;
    tracing::info!("SimpleFIN access stored for member {member_id}");
    Ok(())
}

pub async fn connection_status(state: &AppState, member_id: i64) -> AppResult<ConnectionStatus> {
    let Some(stored) = connection::find_by_member(&state.db, member_id).await? else {
        return Ok(ConnectionStatus {
            connected: false,
            connection_id: None,
            status: None,
            last_synced_at: None,
            masked_token: None,
        });
    };
    let (status, masked) = match state.crypto.decrypt(&stored.access_url) {
        Ok(plain) => (stored.status, urls::mask(&plain)),
        Err(_) => {
            tracing::error!(
                "SimpleFIN: cannot decrypt stored access for connection {}",
                stored.id
            );
            (
                "ERROR".to_owned(),
                "\u{2022}\u{2022}\u{2022}\u{2022}".to_owned(),
            )
        }
    };
    Ok(ConnectionStatus {
        connected: true,
        connection_id: Some(stored.id),
        status: Some(status),
        last_synced_at: stored.last_synced_at,
        masked_token: Some(masked),
    })
}

/// Clears the stored connection; imported accounts stay. True when there was one.
pub async fn delete_connection(state: &AppState, member_id: i64) -> AppResult<bool> {
    let deleted = connection::delete_by_member(&state.db, member_id).await?;
    tracing::info!("SimpleFIN connection cleared for member {member_id}");
    Ok(deleted)
}

/// The manual sync: the accounts it updated, as AccountResponse JSON.
pub async fn sync(state: &AppState, member_id: i64) -> AppResult<Vec<serde_json::Value>> {
    let Some(stored) = connection::find_by_member(&state.db, member_id).await? else {
        return Err(
            SyncError::new("No SimpleFIN connection. Connect with a setup token first.").into(),
        );
    };
    Ok(sync_with_connection(state, &stored, member_id).await?)
}

/// The scheduler and Sync All entry point: reports failures instead of returning them.
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    let stored = match connection::find_by_member(&state.db, member_id).await {
        Ok(Some(stored)) => stored,
        Ok(None) => {
            return SourceSyncResult {
                source: SOURCE.to_owned(),
                status: SyncStatus::SkippedNotConnected,
                message: "No connection".to_owned(),
            };
        }
        Err(error) => {
            tracing::error!(
                "SimpleFIN auto-sync hit an unexpected error for member {member_id}: {error}"
            );
            return SourceSyncResult {
                source: SOURCE.to_owned(),
                status: SyncStatus::Failed,
                message: "Unexpected sync error".to_owned(),
            };
        }
    };
    match sync_with_connection(state, &stored, member_id).await {
        Ok(_) => SourceSyncResult {
            source: SOURCE.to_owned(),
            status: SyncStatus::Synced,
            message: String::new(),
        },
        Err(error) => {
            tracing::warn!(
                "SimpleFIN scheduled sync failed for member {member_id} (code={}): {}",
                error.code.unwrap_or("null"),
                error.message
            );
            SourceSyncResult::from_sync_exception(SOURCE, error.code, Some(&error.message))
        }
    }
}

/// Runs the sync in one transaction; on any failure it is rolled back and the connection is
/// marked ERROR on its own, so the failure stays visible.
async fn sync_with_connection(
    state: &AppState,
    stored: &Connection,
    member_id: i64,
) -> Result<Vec<serde_json::Value>, SyncError> {
    let failure = match do_sync(state, stored, member_id).await {
        Ok(responses) => return Ok(responses),
        Err(failure) => failure,
    };
    let reason = match &failure {
        Failure::Sync(error) => error.message.clone(),
        Failure::Other(message) => message.clone(),
    };
    tracing::error!(
        "SimpleFIN sync failed for connection {} — marking ERROR status: {reason}",
        stored.id
    );
    match connection::mark_error(&state.db, stored.id, state.clock.now()).await {
        Ok(true) => {}
        Ok(false) => tracing::error!(
            "Cannot mark SimpleFIN connection {} as ERROR: row not found",
            stored.id
        ),
        Err(error) => tracing::error!(
            "Cannot mark SimpleFIN connection {} as ERROR: {error}",
            stored.id
        ),
    }
    Err(match failure {
        Failure::Sync(error) => error,
        Failure::Other(_) => SyncError::new("SimpleFIN sync failed. Try again in a moment."),
    })
}

async fn do_sync(
    state: &AppState,
    stored: &Connection,
    member_id: i64,
) -> Result<Vec<serde_json::Value>, Failure> {
    let access_url = state.crypto.decrypt(&stored.access_url).map_err(|_| {
        SyncError::new(
            "Could not decrypt the stored SimpleFIN access. The encryption key may have changed. \
             Connect again with a new setup token.",
        )
    })?;
    let today = state.clock.today();
    let start = bridge_start(
        bank_import::shared_history_start(today, DEFAULT_INITIAL_HISTORY_DAYS),
        state.clock.now().date_naive(),
    );
    let set = client::fetch_accounts(&state.sync.simplefin, &access_url, start).await?;
    if !set.errors.is_empty() {
        tracing::warn!(
            "SimpleFIN reported partial error(s) for member {member_id}: {}",
            log_safe(&set.errors.join("; "))
        );
    }
    if !member_exists(state, member_id).await? {
        return Err(Failure::Other("Family member not found".into()));
    }

    let mut tx = state.db.begin().await?;
    let mut responses = Vec::new();
    for data in &set.accounts {
        if !is_iso_currency(data.currency.as_deref()) {
            tracing::info!(
                "SimpleFIN: skipping account {} — currency is not ISO 4217",
                data.external_id
            );
            continue;
        }
        if !bank_import::fits_ledger_amount(data.balance) {
            tracing::info!(
                "SimpleFIN: skipping account {} — balance does not fit the ledger",
                data.external_id
            );
            continue;
        }
        if let Some(response) = upsert_account(state, &mut tx, data, member_id).await? {
            responses.push(response);
        }
    }
    connection::mark_synced(&mut tx, stored.id, state.clock.now()).await?;
    tx.commit().await?;
    tracing::info!(
        "SimpleFIN sync complete for member {member_id}: {} account(s) updated",
        responses.len()
    );
    Ok(responses)
}

async fn upsert_account(
    state: &AppState,
    conn: &mut PgConnection,
    data: &SimplefinAccount,
    member_id: i64,
) -> Result<Option<serde_json::Value>, Failure> {
    let existing =
        accounts::find_by_external_id_and_member(&mut *conn, &data.external_id, member_id).await?;
    if existing.is_none()
        && accounts::exists_soft_deleted_by_external_id(&mut *conn, &data.external_id, member_id)
            .await?
    {
        tracing::info!(
            "SimpleFIN: skipping resurrection of soft-deleted account {} for member {member_id}",
            data.external_id
        );
        return Ok(None);
    }
    let currency = data.currency.as_deref().unwrap_or_default().to_uppercase();
    // Mapped once: a new account's type depends on it, and the write below reuses it.
    let mapped = holdings::map(&data.holdings, &currency);
    let today = state.clock.today();
    let now = state.clock.now();

    let mut account = match existing {
        Some(mut account) => {
            account.current_balance = data.balance;
            account.currency = currency;
            account.last_synced_at = Some(now);
            // Backfills accounts synced before the institution was recorded. The type is the
            // member's, whatever the bridge reports.
            apply_institution(
                &mut account.institution_name,
                &mut account.institution_domain,
                data,
            );
            accounts::update(&mut *conn, &account, now).await?
        }
        None => {
            // A new account holding a position is a brokerage account; one whose only holding
            // is a sweep fund stays a checking account.
            let account_type = if mapped.positions.is_empty() {
                AccountType::Checking
            } else {
                AccountType::Brokerage
            };
            let mut new = NewAccount::new(
                member_id,
                account_name(data.connection_name.as_deref(), Some(&data.name)),
                account_type,
            );
            new.provider = Some(PROVIDER.to_owned());
            new.currency = currency;
            new.current_balance = data.balance;
            new.last_synced_at = Some(now);
            new.external_account_id = Some(data.external_id.clone());
            new.is_manual = false;
            new.color = COLOR.to_owned();
            apply_institution(&mut new.institution_name, &mut new.institution_domain, data);
            accounts::insert(&mut *conn, &new, now).await?
        }
    };
    // Java answers with the figure it was handed, not the column's 8-place rendering.
    account.current_balance = data.balance;

    let prices = &*state.ledger.prices;
    // Holdings first: the snapshot below derives the day's invested amount from them.
    if replace_holdings(conn, prices, &mut account, data, &mapped, today, now).await? {
        let saved = accounts::update(&mut *conn, &account, now).await?;
        account.cash_balance = saved.cash_balance;
    }
    let ctx = SnapshotContext { prices, today, now };
    valuation::upsert_snapshot_from_native(&mut *conn, &ctx, &account, data.balance, today).await?;
    let rows = to_transactions(&data.transactions, &account.currency);
    bank_import::import_provided(
        &mut *conn,
        &account,
        &rows,
        today,
        DEFAULT_INITIAL_HISTORY_DAYS,
        now,
    )
    .await?;
    let response = state
        .sync
        .simplefin
        .accounts
        .to_response(&mut *conn, prices, &account, today)
        .await?;
    Ok(Some(response))
}

struct EurLine<'a> {
    position: &'a Position,
    value_eur: Option<Decimal>,
    buy_in_eur: Option<Decimal>,
}

/// Brings the positions and cash in line with the bridge's, or leaves all of it as it was:
/// an empty list never wipes, a list read in part never replaces, and a currency without a
/// EUR rate or a converted figure past the column changes nothing. True when the account's
/// cash balance was set and needs saving.
async fn replace_holdings(
    conn: &mut PgConnection,
    prices: &dyn PriceRead,
    account: &mut Account,
    data: &SimplefinAccount,
    mapped: &Mapped,
    today: NaiveDate,
    now: DateTime<Utc>,
) -> Result<bool, Failure> {
    if !account.account_type.is_investment() {
        if !data.holdings.is_empty() {
            tracing::info!(
                "SimpleFIN: account {} reports positions but is typed {}; positions not imported",
                account.id,
                account.account_type
            );
        }
        return Ok(false);
    }
    if !data.holdings_complete {
        tracing::warn!(
            "SimpleFIN: account {} sent a holdings list that was cut or had unreadable rows; \
             holdings and cash left as they were",
            account.id
        );
        return Ok(false);
    }
    if !mapped.complete {
        tracing::warn!(
            "SimpleFIN: account {} holds a position Picsou cannot store (short, mixed currencies, \
             no symbol or a figure past the column); holdings and cash left as they were",
            account.id
        );
        return Ok(false);
    }
    if mapped.positions.is_empty() && mapped.sweep_by_currency.is_empty() {
        return Ok(false);
    }
    let Some((cash, lines)) = in_eur(prices, account, mapped, data.balance).await else {
        return Ok(false);
    };
    account.cash_balance = Some(cash);

    let stored: HashMap<String, _> = holding_rows::find_by_account(&mut *conn, account.id)
        .await?
        .into_iter()
        .map(|h| (h.ticker.clone(), h))
        .collect();
    let reported: HashSet<&str> = lines.iter().map(|l| l.position.ticker.as_str()).collect();
    // Only unreported tickers are deleted and every other one is written in place, so the
    // unique (account_id, ticker) key cannot clash.
    let unreported: Vec<String> = stored
        .keys()
        .filter(|t| !reported.contains(t.as_str()))
        .cloned()
        .collect();
    if !unreported.is_empty() {
        holding_rows::delete_by_accounts_and_tickers(&mut *conn, &[account.id], &unreported)
            .await?;
    }
    for line in &lines {
        let p = line.position;
        let existing = stored.get(&p.ticker);
        let mut draft = existing
            .map(HoldingDraft::from)
            .unwrap_or_else(|| HoldingDraft {
                ticker: p.ticker.clone(),
                ..HoldingDraft::default()
            });
        if p.name.is_some() {
            draft.name.clone_from(&p.name);
        }
        draft.quantity = p.quantity;
        // The bridge's buy-in, or else whatever is stored (the member's own entry).
        if line.buy_in_eur.is_some() {
            draft.average_buy_in = line.buy_in_eur;
        }
        draft.current_price = p.current_price();
        draft.quote_currency = Some(p.currency.clone());
        draft.provider_value_eur = line.value_eur;
        draft.provider_price_date = line.value_eur.map(|_| today);
        draft.last_synced_at = Some(now);
        match existing {
            Some(row) => holding_rows::update(&mut *conn, row.id, &draft, now).await?,
            None => holding_rows::insert(&mut *conn, account.id, &draft, now).await?,
        };
    }
    Ok(true)
}

/// The portfolio in EUR, or None (logged) when a currency it needs has no rate or a converted
/// figure would not fit numeric(20,8). When every position has a market value, the cash is the
/// balance beyond them (never below zero); otherwise only the sweep, since the remainder may be
/// the unvalued position's own worth.
async fn in_eur<'a>(
    prices: &dyn PriceRead,
    account: &Account,
    mapped: &'a Mapped,
    balance: Decimal,
) -> Option<(Decimal, Vec<EurLine<'a>>)> {
    let residual = mapped.positions.iter().all(|p| p.market_value.is_some());
    let mut currencies: Vec<String> = Vec::new();
    let mut add = |c: &str| {
        if !currencies.iter().any(|known| known == c) {
            currencies.push(c.to_owned());
        }
    };
    mapped.sweep_by_currency.iter().for_each(|(c, _)| add(c));
    mapped.positions.iter().for_each(|p| add(&p.currency));
    if residual {
        add(&account.currency);
    }
    let mut rates: HashMap<String, Decimal> = HashMap::new();
    for currency in currencies {
        let rate = if currency == "EUR" {
            Some(Decimal::ONE)
        } else {
            prices.fx_rate_eur(&currency).await
        };
        let Some(rate) = rate else {
            tracing::warn!(
                "SimpleFIN: no EUR rate for {currency}; holdings and cash of account {} left as they were",
                account.id
            );
            return None;
        };
        rates.insert(currency, rate);
    }

    let mut fits = true;
    let mut held = Decimal::ZERO;
    let mut lines = Vec::new();
    for p in &mapped.positions {
        let rate = rates[p.currency.as_str()];
        let mut convert = |figure: Option<Decimal>| match figure.map(|v| eur(v, rate)) {
            None => None,
            Some(Some(v)) => {
                fits &= holdings::fits(Some(v));
                Some(v)
            }
            Some(None) => {
                fits = false;
                None
            }
        };
        let value_eur = convert(p.market_value);
        let buy_in_eur = convert(p.average_buy_in);
        if let Some(value) = value_eur {
            held = held.checked_add(value).unwrap_or(held);
        }
        lines.push(EurLine {
            position: p,
            value_eur,
            buy_in_eur,
        });
    }
    let cash = if residual {
        eur(balance, rates[account.currency.as_str()])
            .and_then(|b| b.checked_sub(held))
            .map(|c| c.max(Decimal::ZERO))
    } else {
        tracing::info!(
            "SimpleFIN: account {} has a position without a market value, so only the sweep \
             counts as cash; any cash the bridge left out of the list is not counted",
            account.id
        );
        mapped
            .sweep_by_currency
            .iter()
            .try_fold(Decimal::ZERO, |sum, (c, amount)| {
                sum.checked_add(eur(*amount, rates[c.as_str()])?)
            })
    };
    match cash {
        Some(cash) if fits && holdings::fits(Some(cash)) => {
            Some((set_scale(cash, 8, HALF_UP), lines))
        }
        _ => {
            tracing::warn!(
                "SimpleFIN: a converted figure of account {} does not fit the ledger; holdings and \
                 cash left as they were",
                account.id
            );
            None
        }
    }
}

/// amount x rate at 8 places, None when the product overflows (it would not fit anyway).
fn eur(amount: Decimal, rate: Decimal) -> Option<Decimal> {
    amount
        .checked_mul(rate)
        .map(|product| set_scale(product, 8, HALF_UP))
}

/// Records the bank the bridge names. A value the bridge stops sending is kept: a missing field
/// is more likely a partial response than a bank that stopped existing.
fn apply_institution(
    name: &mut Option<String>,
    domain: &mut Option<String>,
    data: &SimplefinAccount,
) {
    let bank = java_trim(data.connection_name.as_deref().unwrap_or_default());
    if !bank.is_empty() {
        *name = Some(clip(bank, MAX_NAME_LEN));
    }
    if data.institution_domain.is_some() {
        domain.clone_from(&data.institution_domain);
    }
}

fn to_transactions(rows: &[SimplefinTransaction], currency: &str) -> Vec<TransactionData> {
    rows.iter()
        .map(|tx| TransactionData {
            external_id: tx.external_id.clone(),
            date: Some(tx.date),
            description: Some(if java_is_blank(&tx.description) {
                "Transaction".to_owned()
            } else {
                tx.description.clone()
            }),
            amount: Some(tx.amount),
            currency: Some(currency.to_owned()),
            category: None,
        })
        .collect()
}

/// The bridge rejects an inclusive 90-day span: a request starting 89 days ago is the longest
/// window it accepts.
pub fn bridge_start(requested: NaiveDate, today_utc: NaiveDate) -> NaiveDate {
    let limit = today_utc
        .checked_sub_days(Days::new(89))
        .unwrap_or(NaiveDate::MIN);
    requested.max(limit)
}

/// The bank's name before the account's, unless the account's already says it.
pub fn account_name(connection_name: Option<&str>, account_name: Option<&str>) -> String {
    let account = match account_name {
        Some(name) if !java_is_blank(name) => java_trim(name),
        _ => "Account",
    };
    let bank = java_trim(connection_name.unwrap_or_default());
    let combined = if bank.is_empty() || account.to_lowercase().contains(&bank.to_lowercase()) {
        account.to_owned()
    } else {
        format!("{bank} — {account}")
    };
    clip(&combined, MAX_NAME_LEN)
}

/// Real currencies only: XAU, XDR, XXX and the like have no minor unit and are skipped.
pub fn is_iso_currency(code: Option<&str>) -> bool {
    code.is_some_and(|c| {
        c.encode_utf16().count() == 3 && currency::has_minor_unit(&c.to_uppercase())
    })
}

/// LogSanitizer.safe: at most 500 characters, line breaks and control characters but TAB as '?'.
fn log_safe(value: &str) -> String {
    let mut text: String = value.chars().take(500).collect();
    if value.chars().count() > 500 {
        text.push_str("...");
    }
    text.chars()
        .map(|c| {
            if c != '\t' && (c.is_control() || matches!(c, '\u{2028}' | '\u{2029}')) {
                '?'
            } else {
                c
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn date(y: i32, m: u32, d: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, d).unwrap()
    }

    #[test]
    fn bridge_start_lands_exactly_89_days_back() {
        assert_eq!(
            bridge_start(date(2025, 1, 1), date(2026, 3, 1)),
            date(2025, 12, 2)
        );
        assert_eq!(
            bridge_start(date(2023, 1, 1), date(2024, 3, 1)),
            date(2023, 12, 3)
        );
        assert_eq!(
            bridge_start(date(2026, 2, 1), date(2026, 3, 1)),
            date(2026, 2, 1)
        );
        assert_eq!(
            bridge_start(date(2026, 4, 1), date(2026, 3, 1)),
            date(2026, 4, 1)
        );
    }

    #[test]
    fn iso_currencies_need_a_minor_unit() {
        for code in ["USD", "usd", "Eur", "JPY"] {
            assert!(is_iso_currency(Some(code)), "{code}");
        }
        for code in [
            None,
            Some(""),
            Some("US"),
            Some("USDT"),
            Some("XAU"),
            Some("XDR"),
            Some("XXX"),
            Some("ABC"),
            Some("https://x"),
        ] {
            assert!(!is_iso_currency(code), "{code:?}");
        }
    }

    #[test]
    fn account_names_combine_without_repeating_the_bank() {
        assert_eq!(
            account_name(Some("Chase"), Some("Checking")),
            "Chase — Checking"
        );
        assert_eq!(
            account_name(Some("Chase"), Some("CHASE Checking")),
            "CHASE Checking"
        );
        assert_eq!(account_name(Some("  "), Some(" Savings ")), "Savings");
        assert_eq!(account_name(None, None), "Account");
        assert_eq!(account_name(Some("Bank"), Some(" ")), "Bank — Account");
        let emoji = "\u{1F600}";
        let at_limit = format!("{}{emoji}", "a".repeat(MAX_NAME_LEN - 2));
        assert_eq!(account_name(None, Some(&at_limit)), at_limit);
        let straddling = format!("{}{emoji}", "a".repeat(MAX_NAME_LEN - 8));
        let name = account_name(Some("Bank"), Some(&straddling));
        assert_eq!(name, format!("Bank — {}", "a".repeat(MAX_NAME_LEN - 8)));
    }

    #[test]
    fn log_safe_keeps_one_line() {
        assert_eq!(log_safe("a\nb\tc\u{1B}"), "a?b\tc?");
        assert_eq!(log_safe(&"x".repeat(600)).len(), 503);
    }
}
