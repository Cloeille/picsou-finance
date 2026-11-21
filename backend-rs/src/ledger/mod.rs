//! The ledger shared by every domain track: accounts, holdings, transactions, balance
//! snapshots, FX reads, ownership shares and the ingestion primitives (track L).
//! It serves no endpoint; see README.md beside this file for the API each track uses.

pub mod access;
pub mod accounts;
pub mod bank_import;
pub mod categorize;
pub mod decimal;
pub mod holding_dedup;
pub mod holdings;
pub mod instrument;
pub mod liabilities;
pub mod loans;
pub mod locks;
pub mod members;
pub mod merchants;
pub mod model;
pub mod peer_loans;
pub mod prices;
pub mod providers;
pub mod sidecar;
pub mod sidecar_import;
pub mod snapshots;
pub mod sync_values;
pub mod transactions;
pub mod transfers;
pub mod valuation;

use std::sync::Arc;

use sqlx::PgPool;
use tokio::sync::OnceCell;

use self::locks::{ConnectionLocks, MemberRowLock};
use self::merchants::KnowledgeBase;
use self::prices::{PriceRead, RecordedPrices};
use self::transfers::{DividendClassifier, NoDividendClassifier};
use crate::routes::Routes;

pub fn register(_routes: &mut Routes) {}

/// The ledger's plug-in points, held by AppState as state.ledger. Each starts with the
/// behavior Java has when the provider is absent; the owning track swaps in its own.
pub struct Ledger {
    /// Track T4 replaces it with the provider-backed price service.
    pub prices: Arc<dyn PriceRead>,
    /// Track T4 replaces it with DividendReceiptService.classifyRecent.
    pub dividends: Arc<dyn DividendClassifier>,
    /// Tracks T5/T6 replace it if their syncs lock more than the member row.
    pub connection_locks: Arc<dyn ConnectionLocks>,
    knowledge_base: OnceCell<Arc<KnowledgeBase>>,
}

impl Ledger {
    pub fn new(db: PgPool) -> Self {
        Self {
            prices: Arc::new(RecordedPrices::new(db)),
            dividends: Arc::new(NoDividendClassifier),
            connection_locks: Arc::new(MemberRowLock),
            knowledge_base: OnceCell::new(),
        }
    }

    /// The merchant knowledge base, loaded once (MerchantKnowledgeBase's @PostConstruct).
    pub async fn knowledge_base(&self, db: &PgPool) -> sqlx::Result<Arc<KnowledgeBase>> {
        self.knowledge_base
            .get_or_try_init(|| async { KnowledgeBase::load(db).await.map(Arc::new) })
            .await
            .cloned()
    }
}
