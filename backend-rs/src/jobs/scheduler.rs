//! SchedulerService and per-connector cleanup jobs, each exposed as a testable async function
//! using the injected `AppClock`.

use std::collections::{BTreeSet, HashSet};
use std::panic::AssertUnwindSafe;

use futures::FutureExt;

use crate::AppState;
use crate::error::AppResult;
use crate::ledger::model::AccountType;
use crate::ledger::sidecar::SyncStatus;
use crate::ledger::snapshots::NewSnapshot;
use crate::ledger::{accounts, holdings, providers, snapshots, valuation};

/// Max security profiles refreshed in one weekly pass (rate-limited unofficial sources).
pub const SECURITY_PROFILE_BATCH: usize = 40;
/// Max dividend histories refreshed in one weekly pass (one chart call per ticker).
pub const DIVIDEND_HISTORY_BATCH: usize = 40;

async fn member_ids(state: &AppState) -> sqlx::Result<Vec<i64>> {
    crate::db::query_scalar("SELECT id FROM family_member ORDER BY created_at ASC")
        .fetch_all(&state.db)
        .await
}

/// Monthly on the 1st at 03:30: re-value every property from open data across all members.
/// Returns the total number of properties revalued.
pub async fn monthly_property_valuation(state: &AppState) -> AppResult<usize> {
    tracing::info!("Refreshing property valuations for all members");
    let members = member_ids(state).await?;
    let mut total = 0usize;
    for member_id in members {
        let run = AssertUnwindSafe(
            crate::wealth::real_estate::valuation::refresh_all_for_member(state, member_id),
        )
        .catch_unwind();
        match run.await {
            Ok(refreshed) => {
                total += refreshed;
                if refreshed > 0 {
                    tracing::info!("Revalued {refreshed} propertie(s) for member {member_id}");
                }
            }
            Err(_) => {
                tracing::error!(
                    "Property valuation panicked for member {member_id} -- skipping it"
                );
            }
        }
    }
    Ok(total)
}

/// Daily at 08:00: sync every member's connected sources via MemberSyncService.resyncScheduled
/// (which skips unattended DEGIRO sync).
pub async fn daily_bank_sync(state: &AppState) {
    tracing::info!("Starting daily bank sync for all members");
    let members = match member_ids(state).await {
        Ok(m) => m,
        Err(e) => {
            tracing::error!("Could not list members for daily bank sync: {e}");
            return;
        }
    };
    for member_id in members {
        tracing::info!("Syncing member {member_id}");
        let run = AssertUnwindSafe(crate::sync::member_sync::resync_scheduled(state, member_id))
            .catch_unwind();
        match run.await {
            Ok(results) => {
                for r in results {
                    if matches!(r.status, SyncStatus::Failed | SyncStatus::NeedsReauth) {
                        tracing::warn!(
                            "Daily sync {} for member {member_id}: {}",
                            r.source,
                            r.message
                        );
                    }
                }
            }
            Err(_) => {
                tracing::error!("Daily sync failed for member {member_id} -- skipping it");
            }
        }
    }
}

/// Daily at 08:05: record today's balance snapshot for every account of every member.
/// Returns the number of snapshots inserted or updated.
pub async fn daily_snapshots(state: &AppState) -> AppResult<usize> {
    tracing::info!("Taking daily snapshots for all accounts");
    let today = state.clock.today();
    let now = state.clock.now();
    let members = member_ids(state).await?;
    let mut written = 0usize;

    for member_id in members {
        let member_accounts = match accounts::find_all_by_member(&state.db, member_id).await {
            Ok(list) => list,
            Err(e) => {
                tracing::error!(
                    "Daily snapshot could not list accounts for member {member_id} -- skipping it: {e}"
                );
                continue;
            }
        };
        for account in member_accounts {
            let existing = match snapshots::find_by_account_and_date(&state.db, account.id, today)
                .await
            {
                Ok(found) => found,
                Err(e) => {
                    tracing::error!(
                        "Daily snapshot lookup failed for account {} (member {member_id}) -- skipping it: {e}",
                        account.id
                    );
                    continue;
                }
            };
            let refresh_insurer = providers::is_insurer_contract(&account) && existing.is_some();
            if existing.is_some() && !refresh_insurer {
                continue;
            }
            let val = {
                let mut conn = match state.db.acquire().await {
                    Ok(c) => c,
                    Err(e) => {
                        tracing::error!(
                            "Daily snapshot could not acquire connection for account {} (member {member_id}) -- skipping it: {e}",
                            account.id
                        );
                        continue;
                    }
                };
                match valuation::valuation(&mut conn, &*state.ledger.prices, &account, today).await
                {
                    Ok(v) => v,
                    Err(e) => {
                        tracing::error!(
                            "Daily snapshot failed for account {} (member {member_id}) -- skipping it: {e}",
                            account.id
                        );
                        continue;
                    }
                }
            };
            if !val.any_priced {
                tracing::warn!(
                    "No EUR price for any holding of account {} (member {member_id}) -- \
                     skipping today's snapshot rather than recording a zero",
                    account.id
                );
                continue;
            }
            let write = match existing {
                Some(snap) => {
                    snapshots::update_amounts(&state.db, snap.id, val.live_eur, val.invested_eur)
                        .await
                }
                None => {
                    snapshots::insert(
                        &state.db,
                        &NewSnapshot::derived(account.id, today, val.live_eur, val.invested_eur),
                        now,
                    )
                    .await
                }
            };
            match write {
                Ok(_) => written += 1,
                Err(e) => {
                    tracing::error!(
                        "Daily snapshot save failed for account {} (member {member_id}) -- skipping it: {e}",
                        account.id
                    );
                }
            }
        }
    }
    tracing::debug!("Snapshots taken for all members ({written} written)");
    Ok(written)
}

/// Hourly (fixedDelay = 3_600_000 ms): refresh live EUR prices for every held or tracked ticker,
/// keeping crypto tickers strictly separated from equities/ETFs.
pub async fn refresh_prices(state: &AppState) -> AppResult<()> {
    let mut crypto_tickers: BTreeSet<String> =
        holdings::distinct_tickers(&state.db, Some((AccountType::Crypto, false)))
            .await?
            .into_iter()
            .collect();
    crypto_tickers
        .extend(accounts::distinct_tickers_by_type(&state.db, AccountType::Crypto, false).await?);

    let mut other_tickers: BTreeSet<String> =
        accounts::distinct_tickers_by_type(&state.db, AccountType::Crypto, true)
            .await?
            .into_iter()
            .collect();
    other_tickers.extend(holdings::distinct_tickers(&state.db, None).await?);
    other_tickers.retain(|t| !crypto_tickers.contains(t));

    if crypto_tickers.is_empty() && other_tickers.is_empty() {
        return Ok(());
    }

    tracing::debug!(
        "Refreshing prices for {} crypto and {} other tickers",
        crypto_tickers.len(),
        other_tickers.len()
    );
    let prices = state.market.prices();
    if !crypto_tickers.is_empty() {
        prices.refresh_crypto_prices(&crypto_tickers).await;
    }
    if !other_tickers.is_empty() {
        prices.refresh_prices(&other_tickers).await;
    }
    Ok(())
}

/// Weekly on Sunday at 03:45: refresh stale security_profile rows (capped at 40 per pass).
pub async fn refresh_security_profiles(state: &AppState) -> AppResult<usize> {
    let tickers: BTreeSet<String> = holdings::distinct_tickers(&state.db, None)
        .await?
        .into_iter()
        .collect();
    if tickers.is_empty() {
        return Ok(0);
    }
    let refreshed = state
        .accounts
        .analysis
        .profile_runner
        .refresh_stale(&state.db, &tickers, SECURITY_PROFILE_BATCH)
        .await;
    tracing::info!(
        "Security profiles: refreshed {refreshed} of {} known tickers",
        tickers.len()
    );
    Ok(refreshed)
}

/// Weekly on Sunday at 04:15: runs the dividend history refresh pass inside the cancellable
/// scheduler task so shutdown stops it cleanly without leaking detached tasks.
pub async fn refresh_dividend_history(state: &AppState) -> AppResult<usize> {
    refresh_dividend_history_pass(state).await
}

/// Synchronous/awaitable body of `refresh_dividend_history`: refreshes up to 40 non-crypto (or
/// ISIN-identified) held tickers via `wealth::dividends::history::refresh_stale`.
pub async fn refresh_dividend_history_pass(state: &AppState) -> AppResult<usize> {
    let held = holdings::distinct_tickers(&state.db, None).await?;
    if held.is_empty() {
        return Ok(0);
    }
    let outside_crypto: HashSet<String> =
        upper_set(holdings::distinct_tickers(&state.db, Some((AccountType::Crypto, true))).await?);
    let wanted: Vec<String> = upper_set(held.clone()).into_iter().collect();
    let with_isin: HashSet<String> = if wanted.is_empty() {
        HashSet::new()
    } else {
        let rows: Vec<String> = crate::db::query_scalar(
            "SELECT ticker FROM security_profile WHERE isin IS NOT NULL AND upper(ticker) = ANY($1)",
        )
        .bind(&wanted)
        .fetch_all(&state.db)
        .await?;
        upper_set(rows)
    };

    let mut tickers: BTreeSet<String> = BTreeSet::new();
    for ticker in held {
        if ticker.trim().is_empty() {
            continue;
        }
        let key = ticker.to_uppercase();
        if outside_crypto.contains(&key) || with_isin.contains(&key) {
            tickers.insert(ticker);
        }
    }
    if tickers.is_empty() {
        return Ok(0);
    }

    let list: Vec<String> = tickers.into_iter().collect();
    let refreshed =
        crate::wealth::dividends::history::refresh_stale(state, &list, DIVIDEND_HISTORY_BATCH)
            .await?;
    tracing::info!(
        "Dividend history: refreshed {refreshed} of {} known tickers",
        list.len()
    );
    Ok(refreshed)
}

fn upper_set(values: impl IntoIterator<Item = String>) -> HashSet<String> {
    values
        .into_iter()
        .filter(|v| !v.trim().is_empty())
        .map(|v| v.to_uppercase())
        .collect()
}

/// Every 60 s: evicts expired import previews, Finary API sync previews, pending bank logins,
/// and idle MCP Streamable HTTP sessions.
pub fn run_60s_cleanups(state: &AppState) {
    let now = state.clock.now();
    state.transfer.purge_expired();
    state.sync.finary.purge_expired(now);
    state.mcp.evict_idle_streamable(now);
    crate::connectors::cic::evict_expired_pending_credentials(state);
    crate::connectors::lbp::evict_expired_pending_logins(state);
    crate::connectors::bnp::evict_expired_pending_logins(state);
    state.connectors.cmes_logins.evict_expired(now);
    state.connectors.natixis_logins.evict_expired(now);
}

/// Every 300 s: marks Natixis sync jobs stuck in QUEUED/RUNNING past their stale threshold as failed.
pub async fn run_300s_stale_recovery(state: &AppState) -> sqlx::Result<u64> {
    crate::connectors::natixis::recover_stale_syncs(state).await
}
