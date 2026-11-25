//! The price seam: PriceService.refreshPrices, owned by track T4 (market).

use std::collections::{BTreeSet, HashMap};

use async_trait::async_trait;
use chrono::NaiveDate;
use rust_decimal::Decimal;

#[async_trait]
pub trait WalletPrices: Send + Sync {
    /// PriceService.refreshPrices: live EUR prices by upper-cased ticker (cache, then providers),
    /// recording today's price_snapshot for what it fetched. A ticker nothing prices is absent.
    async fn refresh_prices(
        &self,
        tickers: &BTreeSet<String>,
        today: NaiveDate,
    ) -> HashMap<String, Decimal>;
}

/// refreshPrices with every provider unreachable and an empty cache: only EUR has a price.
#[derive(Debug, Clone, Copy, Default)]
pub struct NoLivePrices;

#[async_trait]
impl WalletPrices for NoLivePrices {
    async fn refresh_prices(
        &self,
        tickers: &BTreeSet<String>,
        _today: NaiveDate,
    ) -> HashMap<String, Decimal> {
        tickers
            .iter()
            .map(|t| t.to_uppercase())
            .filter(|t| t == "EUR")
            .map(|t| (t, Decimal::ONE))
            .collect()
    }
}
