//! Prices, FX, metals and instrument data providers (track T4).

pub mod cerema;
pub mod geocoding;
pub mod insee;
pub mod nasdaq;

use std::sync::Arc;

use sqlx::PgPool;

use crate::clock::AppClock;
use crate::config::Config;
use crate::ledger::prices::{PriceRead, RecordedPrices};
use crate::routes::Routes;

pub fn register(_routes: &mut Routes) {}

/// The market domain's process state (provider clients, caches, cooldowns), held by AppState as
/// state.market.
pub struct Market {
    prices: Arc<RecordedPrices>,
}

impl Market {
    pub fn new(_config: &Config, db: &PgPool, _http: &reqwest::Client, _clock: &AppClock) -> Self {
        Self {
            prices: Arc::new(RecordedPrices::new(db.clone())),
        }
    }

    /// The price service the ledger's valuation reads through.
    pub fn price_read(&self) -> Arc<dyn PriceRead> {
        self.prices.clone()
    }
}
