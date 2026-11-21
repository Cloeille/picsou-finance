//! The startup runners of the price domain as functions track T8 schedules: the twelve-month
//! price backfill (PriceBackfillRunner) and the one-off FX purge (PriceFxCleanupRunner).

use std::collections::BTreeSet;

use async_trait::async_trait;
use chrono::Months;
use sqlx::PgPool;

use crate::AppState;
use crate::error::AppResult;
use crate::ledger::holdings;
use crate::ledger::model::AccountType;

/// The app_setting reads and writes the runners need (AppSettingRepository, track T1's).
#[async_trait]
pub trait AppSettings: Send + Sync {
    async fn value(&self, key: &str) -> AppResult<Option<String>>;
    /// Sets the value only if it is still expected; returns the rows changed.
    async fn compare_and_set(&self, key: &str, expected: &str, value: &str) -> AppResult<u64>;
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
}

const FX_CLEANUP_FLAG: &str = "price.fx_fix_cleanup_done";

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
