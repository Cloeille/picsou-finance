//! The calls IbkrSyncService makes into other tracks, each behind a narrow trait whose default
//! is what Java does when that side has nothing to offer. The coordinator wires the real ones.

use std::sync::Arc;

use async_trait::async_trait;
use chrono::NaiveDate;
use serde_json::Value;
use sqlx::PgConnection;

use super::response;
use crate::error::AppResult;
use crate::ledger::instrument::TickerResult;
use crate::ledger::model::Account;
use crate::ledger::prices::PriceRead;
use crate::ledger::sync_values::java_trim;

/// OpenFigiIsinConverter.resolve (track T4): the price-able ticker and display name of an ISIN.
#[async_trait]
pub trait IsinResolver: Send + Sync {
    async fn resolve(&self, isin: &str) -> TickerResult;
}

/// OpenFIGI unreachable or without a mapping: the normalized ISIN comes back as its own ticker,
/// without a name, and the sync falls back to the IBKR symbol.
pub struct UnresolvedIsins;

#[async_trait]
impl IsinResolver for UnresolvedIsins {
    async fn resolve(&self, isin: &str) -> TickerResult {
        TickerResult {
            ticker: java_trim(isin).to_uppercase(),
            name: None,
        }
    }
}

/// SecurityIdentityService.record (track T4): remembers which ISIN a ticker came from. Never
/// fails the sync.
#[async_trait]
pub trait SecurityIdentities: Send + Sync {
    async fn record(&self, isin_by_ticker: &[(String, String)]);
}

/// No security registry: nothing is remembered.
pub struct NoSecurityIdentities;

#[async_trait]
impl SecurityIdentities for NoSecurityIdentities {
    async fn record(&self, _isin_by_ticker: &[(String, String)]) {}
}

/// AccountService.toResponse(account) (track T2): the AccountResponse JSON of a synced account,
/// read inside the sync's transaction.
#[async_trait]
pub trait AccountResponses: Send + Sync {
    async fn to_response(
        &self,
        conn: &mut PgConnection,
        prices: &dyn PriceRead,
        account: &Account,
        today: NaiveDate,
    ) -> AppResult<Value>;
}

/// The response from the ledger alone: AccountResponse.from with the live EUR balance and the
/// savings configuration, the only enrichment a broker account can carry.
pub struct LedgerAccountResponses;

#[async_trait]
impl AccountResponses for LedgerAccountResponses {
    async fn to_response(
        &self,
        conn: &mut PgConnection,
        prices: &dyn PriceRead,
        account: &Account,
        today: NaiveDate,
    ) -> AppResult<Value> {
        response::account_response(conn, prices, account, today).await
    }
}

/// IBKR's plug-in points, held as state.sync.ibkr.
pub struct Hooks {
    pub isin_resolver: Arc<dyn IsinResolver>,
    pub security_identities: Arc<dyn SecurityIdentities>,
    pub account_responses: Arc<dyn AccountResponses>,
}

impl Default for Hooks {
    fn default() -> Self {
        Self {
            isin_resolver: Arc::new(UnresolvedIsins),
            security_identities: Arc::new(NoSecurityIdentities),
            account_responses: Arc::new(LedgerAccountResponses),
        }
    }
}
