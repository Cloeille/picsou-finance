//! The narrow calls the connectors make into services other tracks own. Each default is what
//! Java does when that side has nothing; the coordinator swaps in the real implementation.

use std::collections::BTreeMap;
use std::sync::Arc;

use async_trait::async_trait;
use chrono::{DateTime, Utc};
use sqlx::PgPool;

use crate::ledger::instrument::{TickerResult, is_isin};
use crate::ledger::sync_values::clean;

/// OpenFigiIsinConverter.resolve (track T4): an ISIN to a quotable ticker and display name.
#[async_trait]
pub trait IsinResolver: Send + Sync {
    async fn resolve(&self, isin: &str) -> TickerResult;

    /// resolveIsinOrSymbol: the ISIN wins, the broker's symbol is the fallback, and the broker's
    /// label is kept unless the lookup knows a name.
    async fn resolve_isin_or_symbol(
        &self,
        isin: Option<&str>,
        symbol: Option<&str>,
        provider_name: Option<&str>,
    ) -> (Option<String>, Option<String>) {
        match isin.and_then(|i| clean(Some(i))) {
            None => (symbol.map(str::to_owned), provider_name.map(str::to_owned)),
            Some(isin) => {
                let resolved = self.resolve(&isin).await;
                (
                    Some(resolved.ticker),
                    resolved.name.or_else(|| provider_name.map(str::to_owned)),
                )
            }
        }
    }
}

/// What OpenFIGI answers when it cannot be reached: the ISIN itself, normalized, and no name.
#[derive(Debug, Default, Clone, Copy)]
pub struct UnresolvedIsins;

#[async_trait]
impl IsinResolver for UnresolvedIsins {
    async fn resolve(&self, isin: &str) -> TickerResult {
        TickerResult {
            ticker: isin.trim().to_uppercase(),
            name: None,
        }
    }
}

/// SecurityIdentityService.record (track T2): remembers which ISIN a ticker came from.
#[async_trait]
pub trait SecurityIdentities: Send + Sync {
    /// Never fails the caller: reference data the next sync supplies again.
    async fn record(&self, isin_by_ticker: &BTreeMap<String, String>, now: DateTime<Utc>);
}

/// SecurityIdentityService's own write on security_profile: a new row seeded with its identity
/// only, or the ISIN filled in on a row that has none.
pub struct RecordedSecurityIdentities {
    db: PgPool,
}

impl RecordedSecurityIdentities {
    pub fn new(db: PgPool) -> Self {
        Self { db }
    }
}

#[async_trait]
impl SecurityIdentities for RecordedSecurityIdentities {
    async fn record(&self, isin_by_ticker: &BTreeMap<String, String>, now: DateTime<Utc>) {
        for (ticker, isin) in isin_by_ticker {
            if ticker.trim().is_empty() || !is_isin(isin) {
                continue;
            }
            let outcome = sqlx::query(
                "INSERT INTO security_profile (ticker, asset_type, isin, status, created_at, updated_at) \
                 VALUES ($1, 'UNKNOWN', $2, 'NEVER_FETCHED', $3, $3) \
                 ON CONFLICT (ticker) DO UPDATE SET isin = EXCLUDED.isin, updated_at = EXCLUDED.updated_at \
                 WHERE security_profile.isin IS NULL",
            )
            .bind(ticker.to_uppercase())
            .bind(isin)
            .bind(now)
            .execute(&self.db)
            .await;
            if let Err(e) = outcome {
                tracing::debug!("Could not record ISIN {isin} for {ticker}: {e}");
            }
        }
    }
}

/// The connectors' plug-in points, held by state.connectors.seams.
pub struct Seams {
    pub isin: Arc<dyn IsinResolver>,
    pub security_identities: Arc<dyn SecurityIdentities>,
}

impl Seams {
    pub fn new(db: PgPool) -> Self {
        Self {
            isin: Arc::new(UnresolvedIsins),
            security_identities: Arc::new(RecordedSecurityIdentities::new(db)),
        }
    }
}
