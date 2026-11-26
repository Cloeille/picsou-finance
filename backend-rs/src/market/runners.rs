//! The startup runners of the price domain as functions track T8 schedules: the twelve-month
//! price backfill (PriceBackfillRunner), the one-off FX purge (PriceFxCleanupRunner), and the
//! raw-ISIN manual-transaction repair pass (IsinTickerRepairRunner), which also keeps the ISIN as
//! the identity of the ticker it resolves to, and the link of manual lines to known ISINs (#703).

use std::collections::{BTreeMap, BTreeSet};

use crate::db::AsDbHandle;
use crate::db::Db;
use async_trait::async_trait;
use chrono::Months;

use super::openfigi::is_isin;
use crate::AppState;
use crate::error::AppResult;
use crate::ledger::instrument::TickerResult;
use crate::ledger::model::AccountType;
use crate::ledger::{accounts, holding_compute, holdings, prices, snapshots, valuation};

/// The app_setting reads and writes the runners need (AppSettingRepository, track T1's).
#[async_trait]
pub trait AppSettings: Send + Sync {
    async fn value(&self, key: &str) -> AppResult<Option<String>>;
    /// Sets the value only if it is still expected; returns the rows changed.
    async fn compare_and_set(&self, key: &str, expected: &str, value: &str) -> AppResult<u64>;
    /// Inserts or replaces the setting's value.
    async fn upsert(&self, key: &str, value: &str) -> AppResult<()>;
}

/// AppSettingRepository's own queries on app_setting.
pub struct SqlAppSettings(pub Db);

#[async_trait]
impl AppSettings for SqlAppSettings {
    async fn value(&self, key: &str) -> AppResult<Option<String>> {
        Ok(crate::db::query_scalar::<String>(
            "SELECT value FROM app_setting WHERE setting_key = $1",
        )
        .bind(key)
        .fetch_optional(&self.0)
        .await?)
    }

    async fn compare_and_set(&self, key: &str, expected: &str, value: &str) -> AppResult<u64> {
        Ok(crate::db::query(
            "UPDATE app_setting SET value = $3 WHERE setting_key = $1 AND value = $2",
        )
        .bind(key)
        .bind(expected)
        .bind(value)
        .execute(&self.0)
        .await?
        .rows_affected())
    }

    async fn upsert(&self, key: &str, value: &str) -> AppResult<()> {
        crate::db::query(
            "INSERT INTO app_setting (setting_key, value) VALUES ($1, $2) \
             ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value",
        )
        .bind(key)
        .bind(value)
        .execute(&self.0)
        .await?;
        Ok(())
    }
}

const FX_CLEANUP_FLAG: &str = "price.fx_fix_cleanup_done";
const ISIN_CURSOR_KEY: &str = "isin_repair.cursor";
const ISIN_HISTORY_REWRITE_PREFIX: &str = "isin_history.rewrite_done.";
const MAX_ISINS_PER_BOOT: usize = 25;
const MAX_REPAIR_DURATION: chrono::Duration = chrono::Duration::seconds(60);

fn upper_cased(tickers: Vec<String>) -> BTreeSet<String> {
    tickers
        .into_iter()
        .filter(|t| !t.trim().is_empty())
        .map(|t| t.to_uppercase())
        .collect()
}

/// PriceBackfillRunner: twelve months of daily prices for every held ticker, crypto ones on the
/// crypto route, and for every held ISIN under the ISIN (#702); returns the snapshots written.
pub async fn price_backfill(state: &AppState) -> AppResult<usize> {
    let crypto = upper_cased(
        holdings::distinct_tickers(&state.db, Some((AccountType::Crypto, false))).await?,
    );
    let other: BTreeSet<String> =
        upper_cased(holdings::distinct_market_priced_tickers(&state.db).await?)
            .difference(&crypto)
            .cloned()
            .collect();
    let isins: BTreeSet<String> = holdings::distinct_isins(&state.db)
        .await?
        .into_iter()
        .collect();
    if crypto.is_empty() && other.is_empty() && isins.is_empty() {
        tracing::debug!("No holding tickers found — skipping price backfill");
        return Ok(0);
    }
    let today = state.clock.today();
    let from = today.checked_sub_months(Months::new(12)).unwrap_or(today);
    let prices = state.market.prices();
    let mut saved = 0;
    if !crypto.is_empty() {
        saved += prices.backfill_historical_prices(&crypto, from, true).await;
    }
    if !other.is_empty() {
        saved += prices.backfill_historical_prices(&other, from, false).await;
    }
    if !isins.is_empty() {
        saved += prices.backfill_isin_history(&isins, from).await;
    }
    tracing::info!(
        "Price backfill complete: {saved} snapshots saved for {} tickers ({} crypto) and {} ISINs",
        crypto.len() + other.len(),
        crypto.len(),
        isins.len()
    );
    Ok(saved)
}

/// PriceFxCleanupRunner: once, purges the price history stored before prices were converted to
/// EUR, gated by the app_setting flag; returns the rows purged.
pub async fn price_fx_cleanup(state: &AppState, settings: &dyn AppSettings) -> AppResult<u64> {
    match settings.value(FX_CLEANUP_FLAG).await? {
        None => {
            tracing::debug!("Cleanup gate flag '{FX_CLEANUP_FLAG}' missing, skipping");
            return Ok(0);
        }
        Some(done) if done.eq_ignore_ascii_case("true") => return Ok(0),
        Some(_) => {}
    }
    tracing::warn!(
        "Purging price_snapshot for FX fix — prices stored before the FX fix are in native currency, not EUR"
    );
    let purged = crate::db::query("DELETE FROM price_snapshot")
        .execute(&state.db)
        .await?
        .rows_affected();
    state.market.prices().clear_cache();
    if settings
        .compare_and_set(FX_CLEANUP_FLAG, "false", "true")
        .await?
        == 0
    {
        tracing::info!(
            "Cleanup flag was already flipped by another instance; purged {purged} rows anyway"
        );
    } else {
        tracing::info!(
            "Purged {purged} price_snapshot rows; the backfill rebuilds history with FX-corrected values"
        );
    }
    Ok(purged)
}

/// Links the lines of manual accounts that have no ISIN to their own ISIN ticker or to the one
/// recorded for their ticker (#703): trades entered before ISINs were kept. Idempotent; returns
/// the lines linked.
pub async fn link_manual_holding_isins(state: &AppState) -> AppResult<u64> {
    let linked = holdings::link_known_isins(&state.db, None).await?;
    if linked > 0 {
        tracing::info!("Linked {linked} manual holding(s) to their ISIN");
    }
    Ok(linked)
}

/// IsinTickerRepairRunner: re-resolves manual transactions still carrying a raw ISIN as their
/// ticker, bounded to 25 ISINs and 60s per pass with a persistent cursor in app_setting, applying
/// each ISIN in its own short transaction and recomputing derived holdings on investment accounts.
/// Returns the number of transactions repaired.
pub async fn isin_ticker_repair(state: &AppState, settings: &dyn AppSettings) -> AppResult<usize> {
    let rows: Vec<(i64, String)> = crate::db::query_as::<(i64, String)>(
        "SELECT id, ticker FROM \"transaction\" \
         WHERE is_manual = true AND ticker IS NOT NULL AND length(ticker) = 12",
    )
    .fetch_all(&state.db)
    .await?;
    if rows.is_empty() {
        return Ok(0);
    }
    let mut by_isin: BTreeMap<String, Vec<i64>> = BTreeMap::new();
    for (id, ticker) in rows {
        if is_isin(&ticker) {
            by_isin.entry(ticker).or_default().push(id);
        }
    }
    if by_isin.is_empty() {
        return Ok(0);
    }
    tracing::info!(
        "Found {} unresolved ISIN ticker(s) on manual transactions, re-resolving",
        by_isin.len()
    );

    let candidates: Vec<String> = by_isin.keys().cloned().collect();
    let start = resume_position(&candidates, settings).await;
    let deadline = state.clock.now() + MAX_REPAIR_DURATION;
    let mut repaired = 0usize;
    let mut last_attempted: Option<String> = None;

    for (attempted, i) in (0..candidates.len()).enumerate() {
        if attempted == MAX_ISINS_PER_BOOT || state.clock.now() > deadline {
            tracing::info!(
                "Stopping after {attempted} ISIN(s) ({}); the next start resumes after {:?}",
                if attempted == MAX_ISINS_PER_BOOT {
                    "per-boot limit"
                } else {
                    "time budget spent"
                },
                last_attempted
            );
            break;
        }
        let isin = &candidates[(start + i) % candidates.len()];
        last_attempted = Some(isin.clone());

        let resolved = state.market.openfigi().resolve(isin).await;
        if resolved.ticker.eq_ignore_ascii_case(isin) {
            tracing::debug!(
                "ISIN {isin} still does not resolve to a ticker, leaving it for a later boot"
            );
            continue;
        }
        let ids = &by_isin[isin];
        match apply_repair(state, isin, ids, &resolved).await {
            Ok(count) => repaired += count,
            Err(e) => {
                tracing::error!(
                    "Repair of ISIN {isin} failed and was rolled back; the rest of the pass continues: {e}"
                );
            }
        }
    }

    if let Some(cursor) = last_attempted
        && let Err(e) = settings.upsert(ISIN_CURSOR_KEY, &cursor).await
    {
        tracing::warn!("Could not persist the ISIN repair cursor at {cursor}: {e}");
    }
    Ok(repaired)
}

async fn resume_position(candidates: &[String], settings: &dyn AppSettings) -> usize {
    let cursor = match settings.value(ISIN_CURSOR_KEY).await {
        Ok(Some(c)) => c,
        _ => return 0,
    };
    let mut position = 0;
    while position < candidates.len() && candidates[position] <= cursor {
        position += 1;
    }
    if position == candidates.len() {
        0
    } else {
        position
    }
}

async fn apply_repair(
    state: &AppState,
    isin: &str,
    tx_ids: &[i64],
    resolved: &TickerResult,
) -> AppResult<usize> {
    let mut tx = state.db.begin().await?;
    let account_ids: Vec<i64> = crate::db::query_scalar::<i64>(
        "SELECT DISTINCT account_id FROM \"transaction\" WHERE is_manual = true AND ticker = $1",
    )
    .bind(isin)
    .fetch_all(&mut tx)
    .await?;

    let updated = crate::db::query(
        "UPDATE \"transaction\" SET ticker = $2, name = COALESCE(name, $3) WHERE id = ANY($1)",
    )
    .bind(tx_ids)
    .bind(&resolved.ticker)
    .bind(&resolved.name)
    .execute(&mut tx)
    .await?
    .rows_affected();

    tracing::info!(
        "Repaired {updated} transaction(s): ISIN {isin} -> {}",
        resolved.ticker
    );

    let affected_accounts = accounts::find_by_ids(&mut tx, &account_ids).await?;
    let investment_ids: Vec<i64> = affected_accounts
        .iter()
        .filter(|a| a.account_type.is_investment())
        .map(|a| a.id)
        .collect();

    // The ISIN stays the instrument's identity once its rows carry the resolved ticker (#703),
    // with the same guards as a manual entry: a real ISIN, held on a non-crypto investment
    // account, and resolved to a ticker other than itself.
    let held_as_security = affected_accounts
        .iter()
        .any(|a| a.account_type.is_investment() && a.account_type != AccountType::Crypto);
    if let Some(isin) = crate::ledger::instrument::normalize_isin(isin)
        .filter(|i| !i.starts_with("XF000") && *i != resolved.ticker.to_uppercase())
        && held_as_security
    {
        holdings::record_ticker_isin(&mut tx, &resolved.ticker, &isin, state.clock.now()).await?;
    }
    if !investment_ids.is_empty() {
        crate::db::query("DELETE FROM account_holding WHERE account_id = ANY($1) AND ticker = $2")
            .bind(&investment_ids)
            .bind(isin)
            .execute(&mut tx)
            .await?;
        let now = state.clock.now();
        for account_id in &investment_ids {
            holding_compute::recompute_holdings(&mut tx.as_handle(), *account_id, now).await?;
        }
        tracing::debug!(
            "Recomputed holdings for {} account(s) after repairing {isin}",
            investment_ids.len()
        );
    }

    tx.commit().await?;
    Ok(usize::try_from(updated).unwrap_or(0))
}

/// One line whose past values may have gone through a wrong ticker (#705).
#[derive(Debug, crate::db::DbFromRow)]
struct IsinLine {
    account_id: i64,
    ticker: String,
    isin: String,
    quantity: rust_decimal::Decimal,
}

/// Rewrites the past DERIVED balance snapshots of accounts with ISIN lines that were valued
/// through a wrong ticker (#683, #705).
///
/// On a past day where a line's ticker price contradicts its ISIN price (the #690 factor of two),
/// swapping the line's contribution, `quantity x (isin - ticker)`, gives a candidate balance. The
/// day is rewritten only if its recorded balance is at least twice off its own invested amount,
/// either way, and the candidate is back within twice of it. Only that day's figures are read,
/// so a later change of the account does not sway it. A day with an ordinary gain or loss is never
/// moved, and a corrected day is no longer suspect, so a rerun changes nothing.
///
/// Left alone: MANUAL snapshots, today's snapshot (written by ISIN since #701), crypto accounts,
/// never-market-priced providers (#809), days without both prices and days with no positive
/// invested amount. Each account is flagged once done (app_setting
/// `isin_history.rewrite_done.<id>`); one whose ISIN has no recorded history yet is retried at the
/// next boot. Today's quantity is used for every past day: a snapshot keeps no per-line
/// quantities. Returns the snapshots changed.
pub async fn rewrite_isin_history(
    state: &AppState,
    settings: &dyn AppSettings,
) -> AppResult<usize> {
    let lines: Vec<IsinLine> = crate::db::query_as(
        "SELECT h.account_id, h.ticker, h.isin, h.quantity FROM account_holding h \
         JOIN account a ON a.id = h.account_id \
         WHERE h.isin IS NOT NULL AND a.deleted_at IS NULL AND a.type <> $1::account_type \
         AND h.quantity > 0 AND UPPER(TRIM(h.ticker)) <> h.isin ORDER BY h.account_id, h.id",
    )
    .bind(AccountType::Crypto)
    .fetch_all(&state.db)
    .await?;
    let mut by_account: BTreeMap<i64, Vec<IsinLine>> = BTreeMap::new();
    for line in lines {
        by_account.entry(line.account_id).or_default().push(line);
    }
    let ids: Vec<i64> = by_account.keys().copied().collect();
    let mut rewritten = 0;
    for account in accounts::find_by_ids(&state.db, &ids).await? {
        if crate::ledger::providers::is_never_market_priced(&account) {
            continue;
        }
        let flag = format!("{ISIN_HISTORY_REWRITE_PREFIX}{}", account.id);
        if settings.value(&flag).await?.is_some() {
            continue;
        }
        let lines = &by_account[&account.id];
        let (changed, complete) = rewrite_account(state, &account, lines).await?;
        rewritten += changed;
        if complete {
            settings.upsert(&flag, &changed.to_string()).await?;
        }
    }
    if rewritten > 0 {
        tracing::info!("Rewrote {rewritten} balance snapshot(s) valued through a wrong ticker");
    }
    Ok(rewritten)
}

type Prices = BTreeMap<(String, chrono::NaiveDate), rust_decimal::Decimal>;

/// How far `value` is from `invested`, as a ratio of at least one; `None` if either is not
/// positive.
fn spread(
    value: rust_decimal::Decimal,
    invested: rust_decimal::Decimal,
) -> Option<rust_decimal::Decimal> {
    if value <= rust_decimal::Decimal::ZERO || invested <= rust_decimal::Decimal::ZERO {
        return None;
    }
    let ratio = value.checked_div(invested)?;
    if ratio >= rust_decimal::Decimal::ONE {
        Some(ratio)
    } else {
        rust_decimal::Decimal::ONE.checked_div(ratio)
    }
}

async fn rewrite_account(
    state: &AppState,
    account: &crate::ledger::model::Account,
    lines: &[IsinLine],
) -> AppResult<(usize, bool)> {
    use crate::ledger::model::SnapshotSource;
    use rust_decimal::Decimal;
    let today = state.clock.today();
    let mut tx = state.db.begin().await?;
    let past: Vec<_> = snapshots::find_by_account(&mut tx, account.id)
        .await?
        .into_iter()
        .filter(|s| s.source == SnapshotSource::Derived && s.date < today)
        .collect();
    let Some(first) = past.first() else {
        return Ok((0, false));
    };
    let keys: Vec<String> = lines
        .iter()
        .flat_map(|l| [l.ticker.trim().to_uppercase(), l.isin.clone()])
        .collect();
    let mut by_key: Prices = BTreeMap::new();
    for p in prices::price_history(&mut tx, &keys, first.date, today).await? {
        by_key.insert((p.ticker, p.date), p.price_eur);
    }
    let complete = lines
        .iter()
        .all(|l| by_key.keys().any(|(k, _)| *k == l.isin));

    let mut changed = 0;
    for snap in &past {
        let mut candidate = snap.balance;
        for line in lines {
            let ticker = line.ticker.trim().to_uppercase();
            let (Some(wrong), Some(right)) = (
                by_key.get(&(ticker, snap.date)),
                by_key.get(&(line.isin.clone(), snap.date)),
            ) else {
                continue;
            };
            let (Some(wrong_value), Some(right_value)) = (
                line.quantity.checked_mul(*wrong),
                line.quantity.checked_mul(*right),
            ) else {
                continue;
            };
            if valuation::quote_contradicts_provider_value(line.quantity, *wrong, Some(right_value))
            {
                let Some(next) = candidate
                    .checked_sub(wrong_value)
                    .and_then(|c| c.checked_add(right_value))
                else {
                    continue;
                };
                candidate = next;
            }
        }
        if candidate == snap.balance {
            continue;
        }
        let (Some(before), Some(after)) = (
            spread(snap.balance, snap.invested_amount),
            spread(candidate, snap.invested_amount),
        ) else {
            continue;
        };
        // Only a day off its cost by the #690 factor is suspect, and only a candidate back within
        // it is taken: a day with an ordinary gain or loss is never moved, and a corrected day is
        // no longer suspect, so a rerun leaves it alone.
        if before < Decimal::TWO || after >= Decimal::TWO {
            continue;
        }
        snapshots::update_amounts(&mut tx, snap.id, candidate, snap.invested_amount).await?;
        changed += 1;
    }
    tx.commit().await?;
    Ok((changed, complete))
}
