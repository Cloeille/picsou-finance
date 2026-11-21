//! CaisseEpargneController (track T6).

use crate::AppState;
use crate::routes::Routes;

pub fn register(_routes: &mut Routes) {}

/// Turns this bank's persisted in-flight syncs into retryable failures at startup.
pub async fn recover_interrupted_syncs(state: &AppState) -> sqlx::Result<u64> {
    super::sidecar::session::mark_interrupted_failed(
        &state.db,
        crate::ledger::sidecar::SidecarBank::CaisseEpargne,
        state.clock.now(),
    )
    .await
}
