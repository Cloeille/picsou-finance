//! Startup runners (SidecarSyncRecovery, LbpSyncRecovery, NatixisSyncRecovery,
//! PriceFxCleanupRunner, IsinTickerRepairRunner, PriceBackfillRunner, StartupSyncService).
//!
//! `DataSeeder` (`setup_auth::setup::seed_from_environment`), `seedIosClientRunner`
//! (`identity::on_startup`), and `AdminRecoveryRunner` (`admin::on_startup`) already run earlier
//! in `picsou::start`.

use super::catch_up::DAILY_BANK_SYNC;
use crate::AppState;
use crate::market::runners::{
    SqlAppSettings, isin_ticker_repair, link_manual_holding_isins, price_backfill,
    price_fx_cleanup, rewrite_isin_history, schedule_security_profile_recheck,
};

/// Synchronous startup maintenance runners executed during `picsou::start`:
/// 1. `connectors::recover_interrupted_syncs` — aborts startup on DB failure, as Java does.
/// 2. `market::runners::price_fx_cleanup` (`@Order(0)`) — one-shot FX purge behind `app_setting`.
/// 3. `market::runners::isin_ticker_repair` (`@Order(2)`) — BEFORE `price_backfill`.
/// 4. `market::runners::link_manual_holding_isins` — manual lines take their ticker's known ISIN
///    (#703), BEFORE `price_backfill` so their history is fetched by ISIN.
/// 5. `market::runners::price_backfill` (lowest precedence) — 12-month price history gap fill.
/// 6. `market::runners::rewrite_isin_history` — AFTER the backfill, which records the ISIN daily
///    history it reads: past snapshots valued through a wrong ticker are corrected (#705).
///
/// Steps 2–6 log and continue on error so a maintenance pass never prevents the server from
/// starting. On a fresh database with no manual ISIN rows or holdings they make zero external
/// network calls.
pub async fn on_startup(state: &AppState) -> anyhow::Result<()> {
    crate::connectors::recover_interrupted_syncs(state).await?;

    let settings = SqlAppSettings(state.db.clone());
    if let Err(e) = price_fx_cleanup(state, &settings).await {
        tracing::error!("Price FX cleanup failed: {e}");
    }
    if let Err(e) = schedule_security_profile_recheck(state).await {
        tracing::error!("Security profile recheck scheduling failed: {e}");
    }
    if let Err(e) = isin_ticker_repair(state, &settings).await {
        tracing::error!("ISIN ticker repair failed: {e}");
    }
    if let Err(e) = link_manual_holding_isins(state).await {
        tracing::error!("Linking manual holdings to their ISIN failed: {e}");
    }
    if let Err(e) = price_backfill(state).await {
        tracing::error!("Price backfill failed: {e}");
    }
    if let Err(e) = rewrite_isin_history(state, &settings).await {
        tracing::error!("Rewriting history valued through a wrong ticker failed: {e}");
    }
    Ok(())
}

/// StartupSyncService (`@Order(1)`): when `APP_STARTUP_SYNC_ENABLED=true`, triggers an initial
/// `daily-bank-sync` through `CatchUpScheduler::run_now` so the run is recorded and the first
/// catch-up tick does not repeat it. Returns `true` if a sync ran.
pub async fn run_startup_sync(state: &AppState) -> bool {
    if !state.config.startup_sync_enabled {
        tracing::info!("Initial startup sync disabled");
        return false;
    }
    tracing::info!("Starting initial sync of all accounts at startup");
    match state
        .jobs
        .catch_up
        .run_now(Some(state), DAILY_BANK_SYNC)
        .await
    {
        Ok(true) => {
            tracing::info!("Initial startup sync finished");
            true
        }
        Ok(false) => {
            tracing::info!("Initial startup sync skipped: the daily sync is already running");
            false
        }
        Err(e) => {
            tracing::error!("Initial startup sync failed: {e}");
            false
        }
    }
}
