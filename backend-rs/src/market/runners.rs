//! The startup runners of the price domain as functions track T8 schedules: the twelve-month
//! price backfill (PriceBackfillRunner), the one-off FX purge (PriceFxCleanupRunner), and the
//! raw-ISIN manual-transaction repair pass (IsinTickerRepairRunner).

use std::collections::{BTreeMap, BTreeSet};

use async_trait::async_trait;
use chrono::Months;
use sqlx::PgPool;

use super::openfigi::is_isin;
use crate::AppState;
use crate::error::AppResult;
use crate::ledger::instrument::TickerResult;
use crate::ledger::model::AccountType;
use crate::ledger::{accounts, holding_compute, holdings};

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
pub struct SqlAppSettings(pub PgPool);

#[async_trait]
impl AppSettings for SqlAppSettings {
    async fn value(&self, key: &str) -> AppResult<Option<String>> {
        Ok(
            sqlx::query_scalar("SELECT value FROM app_setting WHERE setting_key = $1")
                .bind(key)
                .fetch_optional(&self.0)
                .await?,
        )
    }

    async fn compare_and_set(&self, key: &str, expected: &str, value: &str) -> AppResult<u64> {
        Ok(
            sqlx::query("UPDATE app_setting SET value = $3 WHERE setting_key = $1 AND value = $2")
                .bind(key)
                .bind(expected)
                .bind(value)
                .execute(&self.0)
                .await?
                .rows_affected(),
        )
    }

    async fn upsert(&self, key: &str, value: &str) -> AppResult<()> {
        sqlx::query(
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
/// crypto route; returns the snapshots written.
pub async fn price_backfill(state: &AppState) -> AppResult<usize> {
    let crypto = upper_cased(
        holdings::distinct_tickers(&state.db, Some((AccountType::Crypto, false))).await?,
    );
    let other: BTreeSet<String> = upper_cased(holdings::distinct_tickers(&state.db, None).await?)
        .difference(&crypto)
        .cloned()
        .collect();
    if crypto.is_empty() && other.is_empty() {
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
    tracing::info!(
        "Price backfill complete: {saved} snapshots saved for {} tickers ({} crypto)",
        crypto.len() + other.len(),
        crypto.len()
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
    let purged = sqlx::query("DELETE FROM price_snapshot")
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

/// IsinTickerRepairRunner: re-resolves manual transactions still carrying a raw ISIN as their
/// ticker, bounded to 25 ISINs and 60s per pass with a persistent cursor in app_setting, applying
/// each ISIN in its own short transaction and recomputing derived holdings on investment accounts.
/// Returns the number of transactions repaired.
pub async fn isin_ticker_repair(state: &AppState, settings: &dyn AppSettings) -> AppResult<usize> {
    let rows: Vec<(i64, String)> = sqlx::query_as(
        "SELECT id, ticker FROM transaction \
         WHERE is_manual = true AND ticker IS NOT NULL AND char_length(ticker) = 12",
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
    let account_ids: Vec<i64> = sqlx::query_scalar(
        "SELECT DISTINCT account_id FROM transaction WHERE is_manual = true AND ticker = $1",
    )
    .bind(isin)
    .fetch_all(&mut *tx)
    .await?;

    let updated = sqlx::query(
        "UPDATE transaction SET ticker = $2, name = COALESCE(name, $3) WHERE id = ANY($1)",
    )
    .bind(tx_ids)
    .bind(&resolved.ticker)
    .bind(&resolved.name)
    .execute(&mut *tx)
    .await?
    .rows_affected();

    tracing::info!(
        "Repaired {updated} transaction(s): ISIN {isin} -> {}",
        resolved.ticker
    );

    let investment_ids: Vec<i64> = accounts::find_by_ids(&mut *tx, &account_ids)
        .await?
        .into_iter()
        .filter(|a| a.account_type.is_investment())
        .map(|a| a.id)
        .collect();
    if !investment_ids.is_empty() {
        sqlx::query("DELETE FROM account_holding WHERE account_id = ANY($1) AND ticker = $2")
            .bind(&investment_ids)
            .bind(isin)
            .execute(&mut *tx)
            .await?;
        let now = state.clock.now();
        for account_id in &investment_ids {
            holding_compute::recompute_holdings(&mut tx, *account_id, now).await?;
        }
        tracing::debug!(
            "Recomputed holdings for {} account(s) after repairing {isin}",
            investment_ids.len()
        );
    }

    tx.commit().await?;
    Ok(usize::try_from(updated).unwrap_or(0))
}
