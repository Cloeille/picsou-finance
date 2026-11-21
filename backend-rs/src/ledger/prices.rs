//! The read side of PriceService behind PriceRead: quotes with their freshness, FX rates and
//! toEur. Track T4 implements it over the price providers and their cache; RecordedPrices is
//! the database-only implementation, which answers as Java does when every provider is down.

use std::collections::{BTreeSet, HashMap};

use async_trait::async_trait;
use chrono::{Days, NaiveDate};
use rust_decimal::Decimal;
use sqlx::{PgExecutor, PgPool};

use super::model::PriceSnapshot;
use super::sync_values::java_is_blank;

/// How old a recorded price may be and still stand in for a live one.
pub const MAX_FALLBACK_AGE_DAYS: u64 = 7;

/// A EUR price and how current it is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Quote {
    pub price: Decimal,
    /// Today for a live quote, the snapshot's date for a fallback.
    pub as_of: NaiveDate,
    /// False when the price is the last one ever recorded rather than the provider's.
    pub live: bool,
}

#[async_trait]
pub trait PriceRead: Send + Sync {
    /// getQuotes / getCryptoQuotes: keyed by upper-cased ticker; a ticker nothing can price is
    /// absent. crypto_only never falls through to an equity source.
    async fn quotes(
        &self,
        tickers: &BTreeSet<String>,
        crypto_only: bool,
        today: NaiveDate,
    ) -> HashMap<String, Quote>;

    /// EUR per unit of the currency (the {CURRENCY}EUR=X pair), None when unavailable.
    async fn fx_rate_eur(&self, currency: &str) -> Option<Decimal>;

    /// getQuote / getCryptoQuote: one ticker; a blank ticker or EUR is worth one euro.
    async fn quote(&self, ticker: &str, crypto_only: bool, today: NaiveDate) -> Option<Quote> {
        if java_is_blank(ticker) || ticker.eq_ignore_ascii_case("EUR") {
            return Some(Quote {
                price: Decimal::ONE,
                as_of: today,
                live: true,
            });
        }
        let upper = ticker.to_uppercase();
        self.quotes(&BTreeSet::from([upper.clone()]), crypto_only, today)
            .await
            .remove(&upper)
    }

    /// PriceService.toEur: an account balance in EUR, from its currency, or its ticker when it
    /// holds one. Without a rate the balance comes back unconverted, as Java does: throwing would
    /// fail every page, and zero would understate net worth just as silently.
    async fn to_eur(
        &self,
        balance: Option<Decimal>,
        currency: &str,
        ticker: Option<&str>,
        today: NaiveDate,
    ) -> Decimal {
        let Some(balance) = balance else {
            return Decimal::ZERO;
        };
        let ticker = ticker.filter(|t| !java_is_blank(t));
        let Some(ticker) = ticker else {
            if currency.eq_ignore_ascii_case("EUR") {
                return balance;
            }
            return match self.fx_rate_eur(currency).await {
                Some(rate) => balance * rate,
                None => {
                    tracing::error!(
                        "No EUR rate for {currency} -- returning the balance UNCONVERTED, so any total \
                         including it is wrong until the rate is available"
                    );
                    balance
                }
            };
        };
        match self.quote(ticker, false, today).await {
            Some(quote) => balance * quote.price,
            None => {
                tracing::error!(
                    "No EUR rate for {ticker} -- returning the balance UNCONVERTED, so any total \
                     including it is wrong until the rate is available"
                );
                balance
            }
        }
    }
}

/// Prices from price_snapshot only, as PriceService answers when every provider is down: no live
/// quote, no FX rate, and a crypto price only for a coin the registry knows.
#[derive(Debug, Clone)]
pub struct RecordedPrices {
    db: PgPool,
}

impl RecordedPrices {
    pub fn new(db: PgPool) -> Self {
        Self { db }
    }
}

#[async_trait]
impl PriceRead for RecordedPrices {
    async fn quotes(
        &self,
        tickers: &BTreeSet<String>,
        crypto_only: bool,
        today: NaiveDate,
    ) -> HashMap<String, Quote> {
        let mut resolved = HashMap::new();
        let mut pending = BTreeSet::new();
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
                pending.insert(upper);
            }
        }
        if crypto_only && !pending.is_empty() {
            // Only a coin the registry maps is crypto-priced; anything else must never read a
            // row a stock of the same symbol wrote.
            pending = match registered_coins(&self.db, &pending).await {
                Ok(coins) => coins,
                Err(e) => {
                    tracing::warn!("Could not read the crypto registry: {e}");
                    BTreeSet::new()
                }
            };
        }
        if pending.is_empty() {
            return resolved;
        }
        match last_known_prices(&self.db, &pending, today).await {
            Ok(last_known) => {
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

    async fn fx_rate_eur(&self, _currency: &str) -> Option<Decimal> {
        None
    }
}

/// The tickers among the upper-cased ones the crypto registry (crypto_asset) maps to a coin.
pub async fn registered_coins<'e>(
    db: impl PgExecutor<'e>,
    tickers: &BTreeSet<String>,
) -> sqlx::Result<BTreeSet<String>> {
    let tickers: Vec<&str> = tickers.iter().map(String::as_str).collect();
    let rows: Vec<String> = sqlx::query_scalar(
        "SELECT DISTINCT upper(ticker) FROM crypto_asset WHERE upper(ticker) = ANY($1)",
    )
    .bind(&tickers)
    .fetch_all(db)
    .await?;
    Ok(rows.into_iter().collect())
}

/// The newest price_snapshot row of each ticker dated within the fallback window ending today.
pub async fn last_known_prices<'e>(
    db: impl PgExecutor<'e>,
    tickers: &BTreeSet<String>,
    today: NaiveDate,
) -> sqlx::Result<HashMap<String, PriceSnapshot>> {
    let from = today
        .checked_sub_days(Days::new(MAX_FALLBACK_AGE_DAYS))
        .unwrap_or(today);
    let tickers: Vec<&str> = tickers.iter().map(String::as_str).collect();
    let rows: Vec<PriceSnapshot> = sqlx::query_as(
        "SELECT DISTINCT ON (ticker) id, ticker, date, price_eur, created_at FROM price_snapshot \
         WHERE ticker = ANY($1) AND date BETWEEN $2 AND $3 ORDER BY ticker, date DESC",
    )
    .bind(&tickers)
    .bind(from)
    .bind(today)
    .fetch_all(db)
    .await?;
    Ok(rows.into_iter().map(|r| (r.ticker.clone(), r)).collect())
}

/// findLatestByTickerBeforeOrOnDate.
pub async fn latest_price_on_or_before<'e>(
    db: impl PgExecutor<'e>,
    ticker: &str,
    date: NaiveDate,
) -> sqlx::Result<Option<PriceSnapshot>> {
    sqlx::query_as(
        "SELECT id, ticker, date, price_eur, created_at FROM price_snapshot WHERE ticker = $1 AND date <= $2 \
         ORDER BY date DESC LIMIT 1",
    )
    .bind(ticker)
    .bind(date)
    .fetch_optional(db)
    .await
}

/// findByTickerInAndDateBetween: the recorded series, by ticker then date.
pub async fn price_history<'e>(
    db: impl PgExecutor<'e>,
    tickers: &[String],
    from: NaiveDate,
    to: NaiveDate,
) -> sqlx::Result<Vec<PriceSnapshot>> {
    sqlx::query_as(
        "SELECT id, ticker, date, price_eur, created_at FROM price_snapshot WHERE ticker = ANY($1) \
         AND date BETWEEN $2 AND $3 ORDER BY ticker, date",
    )
    .bind(tickers)
    .bind(from)
    .bind(to)
    .fetch_all(db)
    .await
}

/// findPricedTickers: which tickers a provider has ever answered for.
pub async fn priced_tickers<'e>(
    db: impl PgExecutor<'e>,
    tickers: &[String],
) -> sqlx::Result<Vec<String>> {
    sqlx::query_scalar(
        "SELECT DISTINCT ticker FROM price_snapshot WHERE ticker = ANY($1) ORDER BY ticker",
    )
    .bind(tickers)
    .fetch_all(db)
    .await
}
