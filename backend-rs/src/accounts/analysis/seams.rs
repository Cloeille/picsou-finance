//! The narrow interfaces this slice calls on other tracks (T4 market data), each with the
//! behavior Java has when that side has nothing: no instrument type, no ETF
//! composition source, no profile provider. The crypto logo seam is positions::CryptoLogoRead.

use std::collections::BTreeSet;
use std::sync::Arc;

use crate::db::Db;
use async_trait::async_trait;

use crate::accounts::analysis::profiles::RefreshRunner;
use crate::accounts::security::{EtfComposition, InsightCache, SecurityRef};

/// YahooFinancePriceProvider.getInstrumentType.
#[async_trait]
pub trait InstrumentTypeSource: Send + Sync {
    async fn instrument_type(&self, upper_ticker: &str) -> Option<String>;
}

pub struct NoInstrumentTypes;

#[async_trait]
impl InstrumentTypeSource for NoInstrumentTypes {
    async fn instrument_type(&self, _upper_ticker: &str) -> Option<String> {
        None
    }
}

/// EtfCompositionProvider: one issuer's look-through.
#[async_trait]
pub trait EtfCompositionProvider: Send + Sync {
    fn supports(&self, security: &SecurityRef) -> bool;
    async fn fetch(&self, security: &SecurityRef) -> Option<EtfComposition>;

    /// Fetches the breakdown of a physical reference ETF ISIN representing the index tracked by a
    /// synthetic ETF whose primary provider returned its collateral/substitution basket instead.
    async fn fetch_index_proxy(&self, _proxy_isin: &str) -> Option<EtfComposition> {
        None
    }
}

/// SecurityProfileService.refreshStale: resolves the stale profiles among the tickers, at most
/// limit of them, and returns how many it refreshed.
#[async_trait]
pub trait SecurityProfileRefresher: Send + Sync {
    async fn refresh_stale(&self, db: &Db, tickers: &BTreeSet<String>, limit: usize) -> usize;
}

/// Without a profile provider a pass has nothing to fetch.
pub struct NoProfileProviders;

#[async_trait]
impl SecurityProfileRefresher for NoProfileProviders {
    async fn refresh_stale(&self, _db: &Db, _tickers: &BTreeSet<String>, _limit: usize) -> usize {
        0
    }
}

/// Every seam of the holdings, analysis and security endpoints.
pub struct AnalysisSeams {
    pub instrument_types: Arc<dyn InstrumentTypeSource>,
    pub etf_compositions: Vec<Arc<dyn EtfCompositionProvider>>,
    /// The one refresh pass at a time, over the installed SecurityProfileRefresher.
    pub profile_runner: Arc<RefreshRunner>,
    pub insights: InsightCache,
}

impl Default for AnalysisSeams {
    fn default() -> Self {
        Self {
            instrument_types: Arc::new(NoInstrumentTypes),
            etf_compositions: Vec::new(),
            profile_runner: Arc::new(RefreshRunner::new(Arc::new(NoProfileProviders))),
            insights: InsightCache::default(),
        }
    }
}
