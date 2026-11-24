//! The read side of PriceService behind PriceRead: quotes with their freshness, FX rates and
//! toEur. Track T4 implements it over the price providers and their cache; RecordedPrices is
//! the database-only implementation, which answers as Java does when every provider is down.

use std::collections::{BTreeSet, HashMap};

use crate::db::Db;
use async_trait::async_trait;
use chrono::{Days, NaiveDate};
use rust_decimal::Decimal;

use super::model::{Account, AccountHolding, AccountType, PriceSnapshot};
use super::sync_values::java_is_blank;
use crate::db::DbExec;

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

/// What a write is about to price, for PriceRead::prefetch.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct PriceNeeds {
    currencies: BTreeSet<String>,
    tickers: BTreeSet<String>,
    crypto_tickers: BTreeSet<String>,
}

impl PriceNeeds {
    /// What valuing one account reads: the rate of its currency, its own ticker, and the
    /// quotes of the tickers it will hold once the write is done (crypto-only on a crypto
    /// account, as its valuation asks them).
    pub fn account<T: AsRef<str>>(
        &mut self,
        kind: AccountType,
        currency: &str,
        ticker: Option<&str>,
        held: impl IntoIterator<Item = T>,
    ) -> &mut Self {
        self.currency(currency);
        if let Some(ticker) = ticker.filter(|t| !java_is_blank(t)) {
            self.tickers.insert(ticker.to_uppercase());
        }
        let into = if kind == AccountType::Crypto {
            &mut self.crypto_tickers
        } else {
            &mut self.tickers
        };
        into.extend(
            held.into_iter()
                .filter(|t| !java_is_blank(t.as_ref()))
                .map(|t| t.as_ref().to_uppercase()),
        );
        self
    }

    /// The EUR rate of a currency an amount is about to be converted from.
    pub fn currency(&mut self, currency: &str) -> &mut Self {
        if !java_is_blank(currency) && !currency.eq_ignore_ascii_case("EUR") {
            self.currencies.insert(currency.to_owned());
        }
        self
    }

    /// The quote of a ticker an account is about to hold.
    pub fn ticker(&mut self, ticker: &str) -> &mut Self {
        if !java_is_blank(ticker) {
            self.tickers.insert(ticker.to_uppercase());
        }
        self
    }

    /// The same, for an account as it stands and the holdings it has.
    pub fn of(account: &Account, holdings: &[AccountHolding]) -> Self {
        let mut needs = Self::default();
        needs.account(
            account.account_type,
            &account.currency,
            account.ticker.as_deref(),
            holdings.iter().map(|h| h.ticker.as_str()),
        );
        needs
    }
}

#[async_trait]
pub trait PriceRead: Send + Sync {
    /// Asks the providers, before a write transaction opens, for what it is about to price.
    /// The live implementation remembers each answer, a miss included, so the same lookups
    /// made inside the transaction are answered from memory: no provider is awaited while
    /// SQLite's only writer is held (docs/conventions/database.md, "One writer on SQLite").
    async fn prefetch(&self, needs: &PriceNeeds, today: NaiveDate) {
        for currency in &needs.currencies {
            self.fx_rate_eur(currency).await;
        }
        if !needs.tickers.is_empty() {
            self.quotes(&needs.tickers, false, today).await;
        }
        if !needs.crypto_tickers.is_empty() {
            self.quotes(&needs.crypto_tickers, true, today).await;
        }
    }

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
    db: Db,
}

impl RecordedPrices {
    pub fn new(db: Db) -> Self {
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
    db: impl DbExec<'e>,
    tickers: &BTreeSet<String>,
) -> sqlx::Result<BTreeSet<String>> {
    let tickers: Vec<&str> = tickers.iter().map(String::as_str).collect();
    let rows: Vec<String> = crate::db::query_scalar(
        "SELECT DISTINCT upper(ticker) FROM crypto_asset WHERE upper(ticker) = ANY($1)",
    )
    .bind(tickers.as_slice())
    .fetch_all(db)
    .await?;
    Ok(rows.into_iter().collect())
}

/// The newest price_snapshot row of each ticker dated within the fallback window ending today.
pub async fn last_known_prices<'e>(
    db: impl DbExec<'e>,
    tickers: &BTreeSet<String>,
    today: NaiveDate,
) -> sqlx::Result<HashMap<String, PriceSnapshot>> {
    let from = today
        .checked_sub_days(Days::new(MAX_FALLBACK_AGE_DAYS))
        .unwrap_or(today);
    let tickers: Vec<&str> = tickers.iter().map(String::as_str).collect();
    let rows: Vec<PriceSnapshot> = crate::db::query_as(
        "SELECT id, ticker, date, price_eur, created_at \
         FROM (SELECT id, ticker, date, price_eur, created_at, \
               ROW_NUMBER() OVER (PARTITION BY ticker ORDER BY date DESC, id DESC) AS rn \
               FROM price_snapshot WHERE ticker = ANY($1) AND date BETWEEN $2 AND $3) ranked \
         WHERE rn = 1 ORDER BY ticker",
    )
    .bind(tickers.as_slice())
    .bind(from)
    .bind(today)
    .fetch_all(db)
    .await?;
    Ok(rows.into_iter().map(|r| (r.ticker.clone(), r)).collect())
}

/// findLatestByTickerBeforeOrOnDate.
pub async fn latest_price_on_or_before<'e>(
    db: impl DbExec<'e>,
    ticker: &str,
    date: NaiveDate,
) -> sqlx::Result<Option<PriceSnapshot>> {
    crate::db::query_as(
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
    db: impl DbExec<'e>,
    tickers: &[String],
    from: NaiveDate,
    to: NaiveDate,
) -> sqlx::Result<Vec<PriceSnapshot>> {
    crate::db::query_as(
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
    db: impl DbExec<'e>,
    tickers: &[String],
) -> sqlx::Result<Vec<String>> {
    crate::db::query_scalar(
        "SELECT DISTINCT ticker FROM price_snapshot WHERE ticker = ANY($1) ORDER BY ticker",
    )
    .bind(tickers)
    .fetch_all(db)
    .await
}
