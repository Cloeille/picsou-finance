//! PriceService over CompositePriceProvider: live quotes cached 15 minutes (a miss 60 seconds),
//! the last recorded price within seven days as the fallback, price_snapshot writes on refresh,
//! the historical backfill, the intraday series, and Yahoo-guarded crypto ticker learning.
//! Implements the ledger's PriceRead.
//!
//! Routing as CompositePriceProvider: a precious-metals product, else a coin the crypto registry
//! maps (CoinGecko), else Yahoo Finance.

use std::collections::{BTreeSet, HashMap};
use std::sync::{Arc, Mutex};

use crate::db::Db;
use async_trait::async_trait;
use chrono::{DateTime, NaiveDate, NaiveDateTime, Utc};
use rust_decimal::Decimal;

use futures::stream::{self, StreamExt};

use super::coingecko::CoinGecko;
use super::isin_prices::{IsinPriceService, IsinQuote};
use super::metals::{self, PreciousMetals};
use super::yahoo::{QuoteProbe, YahooFinance};
use crate::clock::AppClock;
use crate::ledger::prices::{self, MAX_FALLBACK_AGE_DAYS, PriceRead, Quote};
use crate::ledger::sync_values::java_is_blank;

const CACHE_TTL: chrono::Duration = chrono::Duration::seconds(900);
const MISS_CACHE_TTL: chrono::Duration = chrono::Duration::seconds(60);
/// A recorded series with a hole wider than this is fetched again by the backfill.
const MAX_HISTORY_GAP_DAYS: i64 = 7;
/// A definitive answer (Yahoo quotes it, or CoinGecko refused it) is not re-asked for a day.
const DECIDED_RETRY: chrono::Duration = chrono::Duration::hours(24);
/// "Could not tell" (Yahoo or CoinGecko down or cooling) is retried soon, never remembered as a no.
const UNDECIDED_RETRY: chrono::Duration = chrono::Duration::minutes(15);
/// Wall-clock budget for serial Yahoo probes in one learn_crypto_tickers call.
const PROBE_BUDGET: std::time::Duration = std::time::Duration::from_secs(5);
/// ISIN lookups run side by side when a portfolio is priced, at most this many at once.
const ISIN_LOOKUPS_IN_FLIGHT: usize = 4;
/// Longest ticker crypto_asset.ticker and price_snapshot.ticker can hold (VARCHAR(30)).
const MAX_LEARNABLE_TICKER_LENGTH: usize = 30;

/// A price or a miss, and when it was fetched.
type CachedPrice = (Option<Decimal>, DateTime<Utc>);

pub struct PriceService {
    db: Db,
    clock: AppClock,
    yahoo: Arc<YahooFinance>,
    coingecko: Arc<CoinGecko>,
    metals: Arc<PreciousMetals>,
    isin: Arc<IsinPriceService>,
    cache: Mutex<HashMap<String, CachedPrice>>,
    retry_after: Mutex<HashMap<String, DateTime<Utc>>>,
}

impl PriceService {
    pub fn new(
        db: Db,
        clock: AppClock,
        yahoo: Arc<YahooFinance>,
        coingecko: Arc<CoinGecko>,
        metals: Arc<PreciousMetals>,
        isin: Arc<IsinPriceService>,
    ) -> Self {
        Self {
            db,
            clock,
            yahoo,
            coingecko,
            metals,
            isin,
            cache: Mutex::new(HashMap::new()),
            retry_after: Mutex::new(HashMap::new()),
        }
    }

    /// A fresh cache entry: Some(price) for a hit, Some(None) for a recent miss.
    fn cached(&self, ticker: &str) -> Option<Option<Decimal>> {
        let now = self.clock.now();
        let cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        cache.get(ticker).and_then(|(price, at)| {
            let ttl = if price.is_some() {
                CACHE_TTL
            } else {
                MISS_CACHE_TTL
            };
            (now <= *at + ttl).then_some(*price)
        })
    }

    /// clearPriceCache.
    pub fn clear_cache(&self) {
        self.cache.lock().unwrap_or_else(|e| e.into_inner()).clear();
    }

    fn remember(&self, ticker: &str, price: Option<Decimal>) {
        self.cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(ticker.to_owned(), (price, self.clock.now()));
    }

    /// The upper-cased tickers the crypto registry maps (CoinGeckoPriceProvider.supports).
    async fn crypto_of(&self, tickers: &BTreeSet<String>) -> BTreeSet<String> {
        self.coingecko.coin_ids(tickers).await.into_keys().collect()
    }

    /// CompositePriceProvider.getPricesEur.
    async fn provider_prices(&self, tickers: &BTreeSet<String>) -> HashMap<String, Decimal> {
        let crypto = self.crypto_of(tickers).await;
        let other: BTreeSet<String> = tickers
            .iter()
            .filter(|t| !metals::supports(t) && !crypto.contains(*t))
            .cloned()
            .collect();
        let metal: BTreeSet<String> = tickers
            .iter()
            .filter(|t| metals::supports(t))
            .cloned()
            .collect();
        let mut result = HashMap::new();
        if !metal.is_empty() {
            result.extend(self.metals.prices_eur(&metal).await);
        }
        if !crypto.is_empty() {
            result.extend(self.coingecko.prices_eur(&crypto).await);
        }
        if !other.is_empty() {
            result.extend(self.yahoo.prices_eur(&other).await);
        }
        result
    }

    /// CompositePriceProvider.learnCryptoTickers + PriceService.learnCryptoTickers: probes Yahoo
    /// first (refusing any listed equity symbol), then asks CoinGecko's dominance-gated lookup and
    /// persists accepted coins into crypto_asset while dropping stale stock snapshots and cache.
    pub async fn learn_crypto_tickers(&self, tickers: &BTreeSet<String>) {
        let now = self.clock.now();
        let raw_candidates: BTreeSet<String> = tickers
            .iter()
            .filter(|t| !java_is_blank(t))
            .map(|t| t.trim().to_uppercase())
            .filter(|t| t != "EUR" && t.len() <= MAX_LEARNABLE_TICKER_LENGTH)
            .collect();
        if raw_candidates.is_empty() {
            return;
        }
        let already_known = self.crypto_of(&raw_candidates).await;
        let to_probe: Vec<String> = {
            let mut retries = self.retry_after.lock().unwrap_or_else(|e| e.into_inner());
            retries.retain(|_, next| now < *next);
            raw_candidates
                .iter()
                .filter(|t| {
                    !already_known.contains(*t) && retries.get(*t).is_none_or(|next| now >= *next)
                })
                .cloned()
                .collect()
        };
        if to_probe.is_empty() {
            return;
        }
        let probe_deadline = tokio::time::Instant::now() + PROBE_BUDGET;
        let mut candidates = BTreeSet::new();
        for ticker in to_probe {
            if tokio::time::Instant::now() >= probe_deadline {
                break;
            }
            match self.yahoo.probe_quote(&ticker).await {
                QuoteProbe::Quoted => {
                    self.retry_after
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .insert(ticker, now + DECIDED_RETRY);
                }
                QuoteProbe::Unavailable => {
                    self.retry_after
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .insert(ticker, now + UNDECIDED_RETRY);
                }
                QuoteProbe::UnknownSymbol => {
                    candidates.insert(ticker);
                }
            }
        }
        if candidates.is_empty() {
            return;
        }
        let resolution = self.coingecko.resolve_by_symbol(&candidates).await;
        let wait = if resolution.answered {
            DECIDED_RETRY
        } else {
            UNDECIDED_RETRY
        };
        for ticker in candidates {
            let Some(coin) = resolution.accepted.get(&ticker) else {
                self.retry_after
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .insert(ticker, now + wait);
                continue;
            };
            match self.coingecko.learn(&ticker, &coin.id, &coin.name).await {
                Ok(_) => {
                    self.retry_after
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .remove(&ticker);
                    self.cache
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .remove(&ticker);
                }
                Err(e) => {
                    tracing::warn!("Could not store crypto asset {ticker}: {e}");
                    self.retry_after
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .insert(ticker, now + UNDECIDED_RETRY);
                }
            }
        }
    }

    /// refreshPrices (GET /api/prices): live prices, served from the cache when fresh, each
    /// fetched one written as today's price_snapshot.
    pub async fn refresh_prices(&self, tickers: &BTreeSet<String>) -> HashMap<String, Decimal> {
        self.refresh_prices_scoped(tickers, false).await
    }

    /// refreshCryptoPrices (WalletSyncService, CryptoExchangeSyncService): learns unknown crypto
    /// tickers first, then refreshes only crypto-supported tickers.
    pub async fn refresh_crypto_prices(
        &self,
        tickers: &BTreeSet<String>,
    ) -> HashMap<String, Decimal> {
        self.refresh_prices_scoped(tickers, true).await
    }

    async fn refresh_prices_scoped(
        &self,
        tickers: &BTreeSet<String>,
        crypto_only: bool,
    ) -> HashMap<String, Decimal> {
        if tickers.is_empty() {
            return HashMap::new();
        }
        if crypto_only {
            self.learn_crypto_tickers(tickers).await;
        }
        let upper_all: BTreeSet<String> = tickers.iter().map(|t| t.to_uppercase()).collect();
        let known_crypto = if crypto_only {
            self.crypto_of(&upper_all).await
        } else {
            BTreeSet::new()
        };
        let mut result = HashMap::new();
        let mut to_fetch = BTreeSet::new();
        for upper in upper_all {
            if upper == "EUR" {
                result.insert(upper, Decimal::ONE);
                continue;
            }
            if crypto_only && !known_crypto.contains(&upper) {
                continue;
            }
            match self.cached(&upper) {
                Some(Some(price)) => {
                    result.insert(upper, price);
                }
                Some(None) => {}
                None => {
                    to_fetch.insert(upper);
                }
            }
        }
        if to_fetch.is_empty() {
            return result;
        }
        let fetched = self.provider_prices(&to_fetch).await;
        let today = self.clock.today();
        for (ticker, price) in &fetched {
            self.remember(ticker, Some(*price));
            if let Err(e) = upsert_snapshot(&self.db, ticker, today, *price, self.clock.now()).await
            {
                tracing::warn!("Could not record the price of {ticker}: {e}");
            }
        }
        result.extend(fetched);
        result
    }

    /// backfillHistoricalPrices: fills price_snapshot from the provider's daily series for every
    /// ticker whose recorded series has a hole wider than a week; returns the rows written.
    pub async fn backfill_historical_prices(
        &self,
        tickers: &BTreeSet<String>,
        from: NaiveDate,
        crypto_only: bool,
    ) -> usize {
        if crypto_only {
            self.learn_crypto_tickers(tickers).await;
        }
        let to = self.clock.today();
        let upper: BTreeSet<String> = tickers
            .iter()
            .map(|t| t.to_uppercase())
            .filter(|t| t != "EUR")
            .collect();
        let crypto = self.crypto_of(&upper).await;
        let mut saved = 0;
        for ticker in upper {
            if crypto_only && !crypto.contains(&ticker) {
                continue;
            }
            match self.already_covered(&ticker, from, to).await {
                Ok(true) => continue,
                Ok(false) => {}
                Err(e) => {
                    tracing::error!(
                        "Historical price backfill failed for {ticker} -- skipping it: {e}"
                    );
                    continue;
                }
            }
            let series = self.historical_prices(&ticker, &crypto, from, to).await;
            for (date, price) in &series {
                match insert_snapshot_if_absent(&self.db, &ticker, *date, *price, self.clock.now())
                    .await
                {
                    Ok(true) => saved += 1,
                    Ok(false) => {}
                    Err(e) => {
                        tracing::error!("Could not record a historical price of {ticker}: {e}")
                    }
                }
            }
            tracing::info!("Backfilled {} prices for {ticker}", series.len());
        }
        saved
    }

    async fn historical_prices(
        &self,
        ticker: &str,
        crypto: &BTreeSet<String>,
        from: NaiveDate,
        to: NaiveDate,
    ) -> Vec<(NaiveDate, Decimal)> {
        if metals::supports(ticker) {
            return self.metals.historical_prices_eur(ticker, from, to).await;
        }
        if crypto.contains(ticker) {
            return self.coingecko.historical_prices_eur(ticker, from, to).await;
        }
        self.yahoo.historical_prices_eur(ticker, from, to).await
    }

    async fn already_covered(
        &self,
        ticker: &str,
        from: NaiveDate,
        to: NaiveDate,
    ) -> sqlx::Result<bool> {
        let rows = prices::price_history(&self.db, &[ticker.to_owned()], from, to).await?;
        if rows.is_empty() {
            return Ok(false);
        }
        let mut cursor = from;
        for row in rows {
            if (row.date - cursor).num_days() > MAX_HISTORY_GAP_DAYS {
                return Ok(false);
            }
            cursor = row.date;
        }
        Ok((to - cursor).num_days() <= MAX_HISTORY_GAP_DAYS)
    }

    /// getIntradayPricesEur: the provider's hourly closes within [from, to].
    pub async fn intraday_prices_eur(
        &self,
        ticker: &str,
        from: NaiveDateTime,
        to: NaiveDateTime,
    ) -> Vec<(NaiveDateTime, Decimal)> {
        if java_is_blank(ticker) || ticker.eq_ignore_ascii_case("EUR") {
            return Vec::new();
        }
        let upper = ticker.to_uppercase();
        let crypto = self.crypto_of(&BTreeSet::from([upper.clone()])).await;
        if metals::supports(&upper) {
            return self.metals.intraday_prices_eur(&upper, from, to).await;
        }
        if crypto.contains(&upper) {
            return self.coingecko.intraday_prices_eur(&upper, from, to).await;
        }
        self.yahoo.intraday_prices_eur(&upper, from, to).await
    }
}

#[async_trait]
impl PriceRead for PriceService {
    async fn quotes(
        &self,
        tickers: &BTreeSet<String>,
        crypto_only: bool,
        today: NaiveDate,
    ) -> HashMap<String, Quote> {
        let mut resolved = HashMap::new();
        let mut candidates = BTreeSet::new();
        for ticker in tickers.iter().filter(|t| !java_is_blank(t)) {
            let upper = ticker.to_uppercase();
            if upper == "EUR" {
                resolved.insert(
                    upper,
                    Quote {
                        price: Decimal::ONE,
                        as_of: today,
                        live: true,
                    },
                );
            } else {
                candidates.insert(upper);
            }
        }
        if crypto_only && !candidates.is_empty() {
            candidates = self.crypto_of(&candidates).await;
        }
        let mut fetchable = BTreeSet::new();
        let mut pending = BTreeSet::new();
        for ticker in candidates {
            match self.cached(&ticker) {
                Some(Some(price)) => {
                    resolved.insert(
                        ticker,
                        Quote {
                            price,
                            as_of: today,
                            live: true,
                        },
                    );
                }
                Some(None) => {
                    pending.insert(ticker);
                }
                None => {
                    fetchable.insert(ticker.clone());
                    pending.insert(ticker);
                }
            }
        }
        if pending.is_empty() {
            return resolved;
        }
        if !fetchable.is_empty() {
            let live = self.provider_prices(&fetchable).await;
            for ticker in &fetchable {
                let price = live.get(ticker).copied();
                self.remember(ticker, price);
                if let Some(price) = price {
                    resolved.insert(
                        ticker.clone(),
                        Quote {
                            price,
                            as_of: today,
                            live: true,
                        },
                    );
                }
            }
        }
        let unresolved: BTreeSet<String> = pending
            .into_iter()
            .filter(|t| !resolved.contains_key(t))
            .collect();
        if unresolved.is_empty() {
            return resolved;
        }
        match prices::last_known_prices(&self.db, &unresolved, today).await {
            Ok(last_known) => {
                let unknown: Vec<&String> = unresolved
                    .iter()
                    .filter(|t| !last_known.contains_key(*t))
                    .collect();
                if !last_known.is_empty() {
                    tracing::warn!(
                        "No live price for {:?} -- falling back to the last recorded one",
                        last_known.keys()
                    );
                }
                if !unknown.is_empty() {
                    tracing::warn!(
                        "No price at all for {unknown:?} -- not from the provider, and nothing recorded in the last {MAX_FALLBACK_AGE_DAYS} days"
                    );
                }
                for (ticker, snapshot) in last_known {
                    resolved.insert(
                        ticker,
                        Quote {
                            price: snapshot.price_eur,
                            as_of: snapshot.date,
                            live: false,
                        },
                    );
                }
            }
            Err(e) => tracing::warn!("Could not read the recorded prices: {e}"),
        }
        resolved
    }

    /// The ISIN sources first (justETF, then Yahoo on the reference listing), then a price
    /// recorded under the ISIN itself. Never a ticker.
    async fn isin_quotes(
        &self,
        isins: &BTreeSet<String>,
        today: NaiveDate,
    ) -> HashMap<String, Quote> {
        let live: Vec<(String, Option<IsinQuote>)> = stream::iter(isins.iter().cloned())
            .map(|isin| async move {
                let quote = self.isin.quote(&isin).await;
                (isin, quote)
            })
            .buffer_unordered(ISIN_LOOKUPS_IN_FLIGHT)
            .collect()
            .await;
        let mut resolved = HashMap::new();
        let mut missing = BTreeSet::new();
        for (isin, quote) in live {
            match quote {
                Some(q) => {
                    resolved.insert(
                        isin,
                        Quote {
                            price: q.price_eur,
                            as_of: q.as_of.unwrap_or(today),
                            live: true,
                        },
                    );
                }
                None => {
                    missing.insert(isin);
                }
            }
        }
        resolved.extend(prices::recorded_isin_quotes(&self.db, &missing, today).await);
        resolved
    }

    async fn fx_rate_eur(&self, currency: &str) -> Option<Decimal> {
        self.yahoo.fx_rate_to_eur(Some(currency)).await
    }
}

async fn upsert_snapshot(
    db: &Db,
    ticker: &str,
    date: NaiveDate,
    price: Decimal,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    let updated = crate::db::query(
        "UPDATE price_snapshot SET price_eur = $3 WHERE ticker = $1 AND date = $2",
    )
    .bind(ticker)
    .bind(date)
    .bind(price)
    .execute(db)
    .await?
    .rows_affected();
    if updated == 0 {
        insert_snapshot_if_absent(db, ticker, date, price, now).await?;
    }
    Ok(())
}

async fn insert_snapshot_if_absent(
    db: &Db,
    ticker: &str,
    date: NaiveDate,
    price: Decimal,
    now: DateTime<Utc>,
) -> sqlx::Result<bool> {
    let exists: bool = crate::db::query_scalar::<bool>(
        "SELECT EXISTS (SELECT 1 FROM price_snapshot WHERE ticker = $1 AND date = $2)",
    )
    .bind(ticker)
    .bind(date)
    .fetch_one(db)
    .await?;
    if exists {
        return Ok(false);
    }
    crate::db::query(
        "INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ($1, $2, $3, $4)",
    )
    .bind(ticker)
    .bind(date)
    .bind(price)
    .bind(now)
    .execute(db)
    .await?;
    Ok(true)
}
