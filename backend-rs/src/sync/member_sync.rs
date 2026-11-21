//! MemberSyncService: the per-member "sync everything" entry points that the scheduler, the
//! startup sync and the MCP sync tool run (track T8 schedules them). One source never stops the
//! others: each source always yields a result.

use std::collections::HashSet;
use std::panic::AssertUnwindSafe;

use async_trait::async_trait;
use futures::FutureExt;

use super::{enable_banking, exchanges, finary, ibkr, louve, simplefin, wallets, wise};
use crate::AppState;
use crate::ledger::sidecar::{SourceSyncResult, SyncStatus};
use crate::ledger::transfers;

/// The order every run follows. Revolut owns the IBAN row before Enable Banking: the Enable
/// Banking fallback matches by IBAN and only refreshes balance and uid.
pub const CANONICAL_ORDER: &[&str] = &[
    "revolut",
    "enable-banking",
    "enable-banking-retry",
    "trade-republic",
    "bourso",
    "bourse-direct",
    "amundi",
    "fortuneo",
    "amex",
    "ibkr",
    simplefin::SOURCE,
    wise::SOURCE,
    "crypto-exchanges",
    "wallets",
    "finary",
    "degiro",
    "cmes",
    "cic",
    "corum",
    "natixis",
    "lbp",
    "bnp",
    "louve",
];

/// The sources whose sync track T6 owns (the bank sidecars), each as MemberSyncService runs it:
/// resyncReporting, userSyncReporting for DEGIRO (the scheduled path never reaches it), and the
/// status-then-queue mapping for LBP.
#[async_trait]
pub trait SidecarSources: Send + Sync {
    async fn resync_reporting(
        &self,
        state: &AppState,
        source: &'static str,
        member_id: i64,
    ) -> SourceSyncResult;
}

/// What every sidecar service reports for a member without a session.
pub struct NoSidecarSessions;

#[async_trait]
impl SidecarSources for NoSidecarSessions {
    async fn resync_reporting(
        &self,
        _state: &AppState,
        source: &'static str,
        _member_id: i64,
    ) -> SourceSyncResult {
        result(source, SyncStatus::SkippedNotConnected, "No active session")
    }
}

/// resyncScheduled: every source except DEGIRO, whose unattended sync is disabled.
pub async fn resync_scheduled(state: &AppState, member_id: i64) -> Vec<SourceSyncResult> {
    let mut results = Vec::with_capacity(CANONICAL_ORDER.len());
    for &source in CANONICAL_ORDER {
        if source == "degiro" {
            results.push(result(
                source,
                SyncStatus::Skipped,
                "Unattended DEGIRO sync is disabled",
            ));
        } else {
            results.push(execute_source(state, source, member_id).await);
        }
    }
    after_import(state, member_id).await;
    results
}

/// resyncForUser: every source, or only those in `sources`, in the canonical order.
pub async fn resync_for_user(
    state: &AppState,
    member_id: i64,
    sources: Option<&HashSet<String>>,
) -> Vec<SourceSyncResult> {
    let mut results = Vec::new();
    for &source in CANONICAL_ORDER {
        if sources.is_none_or(|s| s.contains(source)) {
            results.push(execute_source(state, source, member_id).await);
        }
    }
    after_import(state, member_id).await;
    results
}

async fn after_import(state: &AppState, member_id: i64) {
    transfers::after_import(
        &state.db,
        &*state.ledger.dividends,
        member_id,
        state.clock.today(),
        state.clock.now(),
    )
    .await;
}

async fn execute_source(
    state: &AppState,
    source: &'static str,
    member_id: i64,
) -> SourceSyncResult {
    let run = AssertUnwindSafe(run_source(state, source, member_id)).catch_unwind();
    match run.await {
        Ok(result) => result,
        Err(_) => {
            tracing::error!("{source} sync failed unexpectedly for member {member_id}");
            result(source, SyncStatus::Failed, "Unexpected sync error")
        }
    }
}

async fn run_source(state: &AppState, source: &'static str, member_id: i64) -> SourceSyncResult {
    match source {
        "enable-banking" => enable_banking::resync_all_reporting(state, member_id).await,
        "enable-banking-retry" => enable_banking::retry_failed_reporting(state, member_id).await,
        "ibkr" => ibkr::resync_reporting(state, member_id).await,
        simplefin::SOURCE => simplefin::resync_reporting(state, member_id).await,
        wise::SOURCE => wise::resync_reporting(state, member_id).await,
        "crypto-exchanges" => exchanges::resync_all_reporting(state, member_id).await,
        "wallets" => wallets_result(&wallets::resync_all(state, member_id).await),
        "finary" => match finary::auto_sync(state, member_id).await {
            Ok(response) => finary_result(response.status),
            Err(e) => {
                tracing::error!("Finary sync failed unexpectedly for member {member_id}: {e}");
                result("finary", SyncStatus::Failed, "Unexpected sync error")
            }
        },
        "louve" => louve::resync_reporting(state, member_id).await,
        _ => {
            state
                .sync
                .sidecars()
                .resync_reporting(state, source, member_id)
                .await
        }
    }
}

fn wallets_result(summary: &wallets::ResyncSummary) -> SourceSyncResult {
    if summary.total == 0 {
        result("wallets", SyncStatus::SkippedNotConnected, "No wallets")
    } else if !summary.failed.is_empty() {
        result("wallets", SyncStatus::Failed, "Some wallet chains failed")
    } else {
        result("wallets", SyncStatus::Synced, "")
    }
}

fn finary_result(status: finary::AutoSyncStatus) -> SourceSyncResult {
    use finary::AutoSyncStatus::*;
    match status {
        Ok => result("finary", SyncStatus::Synced, ""),
        TotpRequired => result("finary", SyncStatus::NeedsReauth, "TOTP required"),
        NeedsMapping => result(
            "finary",
            SyncStatus::Synced,
            "Manual mapping still required",
        ),
        NotConnected => result(
            "finary",
            SyncStatus::SkippedNotConnected,
            "No connected Finary session",
        ),
    }
}

fn result(source: &str, status: SyncStatus, message: &str) -> SourceSyncResult {
    SourceSyncResult {
        source: source.to_owned(),
        status,
        message: message.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finary_statuses_map_as_member_sync_does() {
        use finary::AutoSyncStatus::*;
        assert_eq!(finary_result(Ok).status, SyncStatus::Synced);
        assert_eq!(
            finary_result(NeedsMapping).message,
            "Manual mapping still required"
        );
        assert_eq!(finary_result(TotpRequired).status, SyncStatus::NeedsReauth);
        assert_eq!(
            finary_result(NotConnected).status,
            SyncStatus::SkippedNotConnected
        );
    }

    #[test]
    fn wallet_summary_maps_to_one_result() {
        let summary = |total, failed: Vec<wallets::Chain>| wallets::ResyncSummary {
            total,
            succeeded: total - failed.len(),
            failed,
        };
        assert_eq!(wallets_result(&summary(0, vec![])).message, "No wallets");
        assert_eq!(
            wallets_result(&summary(2, vec![])).status,
            SyncStatus::Synced
        );
        let first = wallets::Chain::ALL[0];
        assert_eq!(
            wallets_result(&summary(2, vec![first])).message,
            "Some wallet chains failed"
        );
    }
}
