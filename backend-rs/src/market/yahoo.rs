//! YahooFinancePriceProvider: stock and ETF quotes, FX pairs, daily and hourly series, dividend
//! history and the symbol catalog (SymbolCatalogPort), all from the unauthenticated chart and
//! search endpoints.
//!
//! Prices are converted to EUR with Yahoo's own {CURRENCY}EUR=X pair, cached 15 minutes (a miss
//! 60 seconds). London pence (GBp, GBX) is GBP/100.

use std::collections::{BTreeSet, HashMap};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

use chrono::{DateTime, NaiveDate, NaiveDateTime, Utc};
use percent_encoding::{AsciiSet, CONTROLS, NON_ALPHANUMERIC, utf8_percent_encode};
use regex::Regex;
use rust_decimal::Decimal;
use serde_json::Value;

use crate::clock::AppClock;
use crate::ledger::decimal::{HALF_UP, divide, set_scale};

use super::cooldown::YahooCooldown;
use super::num::{self, json_double, json_i64};

const TIMEOUT: Duration = Duration::from_secs(10);
/// The symbol-catalog calls run on write paths, where a verification is worth less than a price.
const VERIFY_TIMEOUT: Duration = Duration::from_secs(3);
const SERIES_TIMEOUT: Duration = Duration::from_secs(15);
/// Dividend events kept per ticker, newest ex-dates.
pub const MAX_DIVIDEND_EVENTS: usize = 200;
const FX_CACHE_TTL: chrono::Duration = chrono::Duration::minutes(15);
const FX_MISS_CACHE_TTL: chrono::Duration = chrono::Duration::seconds(60);

/// The tickers Yahoo defers to CoinGecko.
const CRYPTO_TICKERS: [&str; 10] = [
    "BTC", "ETH", "SOL", "BNB", "ADA", "XRP", "DOGE", "DOT", "MATIC", "AVAX",
];

static SYMBOL: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^(?:\^[A-Z0-9][A-Z0-9.=-]{0,18}|[A-Z0-9][A-Z0-9.=-]{0,19})$").expect("regex")
});
static ISIN_SHAPE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Z]{2}[A-Z0-9]{9}[A-Z0-9]$").expect("regex"));

/// What Spring's URI template encoding escapes in a path variable.
pub(crate) const PATH_SEGMENT: &AsciiSet = &CONTROLS
    .add(b' ')
    .add(b'"')
    .add(b'#')
    .add(b'%')
    .add(b'/')
    .add(b'<')
    .add(b'>')
    .add(b'?')
    .add(b'[')
    .add(b'\\')
    .add(b']')
    .add(b'^')
    .add(b'`')
    .add(b'{')
    .add(b'|')
    .add(b'}');
/// A query value: everything but the unreserved characters.
pub(crate) const QUERY_VALUE: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'.')
    .remove(b'_')
    .remove(b'~');

/// A rate or a miss, and when it was read.
type CachedRate = (Option<Decimal>, DateTime<Utc>);

/// A failed Yahoo call.
#[derive(Debug, thiserror::Error)]
pub enum YahooError {
    #[error("HTTP {status}")]
    Status {
        status: u16,
        retry_after: Option<String>,
    },
    #[error("{0}")]
    Transport(String),
}

impl YahooError {
    fn is_rate_limited(&self) -> bool {
        matches!(self, Self::Status { status: 429, .. })
    }
}

/// What Yahoo knows about a ticker's dividends: an empty list means the symbol pays nothing.
/// Amounts are in currency, not euros.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DividendHistory {
    pub currency: Option<String>,
    pub events: Vec<DividendPoint>,
}

/// One ex-date and its cash amount in the history's currency.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DividendPoint {
    pub ex_date: NaiveDate,
    pub amount: Decimal,
}

/// What a quote probe can conclude; Unavailable is "cannot tell", never "no".
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QuoteProbe {
    Quoted,
    UnknownSymbol,
    Unavailable,
}

/// A symbol Yahoo carries, with the name it displays for it (SymbolCatalogPort.SymbolMatch).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SymbolMatch {
    pub symbol: String,
    pub name: Option<String>,
}

/// A listing's last price in its own trading currency, as Yahoo reports it (ListingQuote).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ListingQuote {
    pub price: Decimal,
    pub currency: Option<String>,
    pub as_of: Option<NaiveDate>,
}

/// The instance's Yahoo Finance client (state.market.yahoo()).
#[derive(Debug)]
pub struct YahooFinance {
    http: reqwest::Client,
    base_url: String,
    cooldown: Arc<YahooCooldown>,
    clock: AppClock,
    fx_cache: Mutex<HashMap<String, CachedRate>>,
}

impl YahooFinance {
    pub fn new(
        http: reqwest::Client,
        base_url: &str,
        cooldown: Arc<YahooCooldown>,
        clock: AppClock,
    ) -> Self {
        Self {
            http,
            base_url: base_url.trim_end_matches('/').to_owned(),
            cooldown,
            clock,
            fx_cache: Mutex::new(HashMap::new()),
        }
    }

    /// The cooldown this client arms on a 429.
    pub fn cooldown(&self) -> &Arc<YahooCooldown> {
        &self.cooldown
    }

    /// Whether the ticker is a symbol Yahoo can be asked for: not a coin CoinGecko prices, not an
    /// ISIN, and within the chart endpoint's symbol syntax.
    pub fn supports(ticker: &str) -> bool {
        if ticker.trim().is_empty() {
            return false;
        }
        let upper = ticker.to_uppercase();
        if CRYPTO_TICKERS.contains(&upper.as_str()) {
            return false;
        }
        if upper.chars().count() == 12 && ISIN_SHAPE.is_match(&upper) {
            return false;
        }
        SYMBOL.is_match(&upper)
    }

    async fn get_json(&self, path_and_query: &str, timeout: Duration) -> Result<Value, YahooError> {
        let response = self
            .http
            .get(format!("{}{path_and_query}", self.base_url))
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0")
            .timeout(timeout)
            .send()
            .await
            .map_err(|e| YahooError::Transport(transport_message(&e)))?;
        let status = response.status();
        if !status.is_success() {
            let retry_after = response
                .headers()
                .get(reqwest::header::RETRY_AFTER)
                .and_then(|v| v.to_str().ok())
                .map(str::to_owned);
            return Err(YahooError::Status {
                status: status.as_u16(),
                retry_after,
            });
        }
        response
            .json::<Value>()
            .await
            .map_err(|_| YahooError::Transport("unreadable response body".to_owned()))
    }

    /// The first chart result, None when the payload carries none.
    async fn fetch_chart(
        &self,
        symbol: &str,
        query: &str,
        timeout: Duration,
    ) -> Result<Option<Value>, YahooError> {
        let path = format!(
            "/v8/finance/chart/{}?{query}",
            utf8_percent_encode(symbol, PATH_SEGMENT)
        );
        let body = self.get_json(&path, timeout).await?;
        Ok(body
            .get("chart")
            .and_then(|c| c.get("result"))
            .and_then(Value::as_array)
            .and_then(|r| r.first())
            .filter(|r| !r.is_null())
            .cloned())
    }

    /// getPricesEur: EUR prices of the supported tickers, one chart call each; a failure is a
    /// missing price, and a 429 arms the cooldown without the price path ever reading it.
    pub async fn prices_eur(&self, tickers: &BTreeSet<String>) -> HashMap<String, Decimal> {
        let supported: BTreeSet<String> = tickers
            .iter()
            .filter(|t| Self::supports(t))
            .map(|t| t.to_uppercase())
            .collect();
        let mut result = HashMap::new();
        for ticker in supported {
            match self.single_price(&ticker).await {
                Ok(Some(price)) => {
                    result.insert(ticker, price);
                }
                Ok(None) => {}
                Err(e) => {
                    if let YahooError::Status {
                        status: 429,
                        retry_after,
                    } = &e
                    {
                        self.cooldown.arm(retry_after.as_deref());
                    }
                    tracing::warn!(
                        "Yahoo Finance price fetch failed for {}: {e}",
                        safe(&ticker)
                    );
                }
            }
        }
        result
    }

    async fn single_price(&self, ticker: &str) -> Result<Option<Decimal>, YahooError> {
        let Some(chart) = self
            .fetch_chart(ticker, "range=1d&interval=1d", TIMEOUT)
            .await?
        else {
            return Ok(None);
        };
        let Some(meta) = chart.get("meta").filter(|m| m.is_object()) else {
            return Ok(None);
        };
        let Some(price) = market_price(meta).filter(|p| *p > Decimal::ZERO) else {
            return Ok(None);
        };
        let currency = meta.get("currency").and_then(Value::as_str);
        match self.fx_rate_to_eur(currency).await {
            Some(rate) => Ok(Some(price * rate)),
            None => {
                tracing::warn!(
                    "Skipping price {price} in {}: FX rate unavailable",
                    currency.unwrap_or("null")
                );
                Ok(None)
            }
        }
    }

    /// fetchDividendHistory: the dividends Yahoo published over the last two years, by ex-date.
    /// An event missing its amount or date is skipped. A transport failure, 429 included, is an
    /// error, and a 429 arms the cooldown so a weekly pass stops instead of asking forty times.
    pub async fn fetch_dividend_history(
        &self,
        ticker: &str,
    ) -> Result<DividendHistory, YahooError> {
        let chart = match self
            .fetch_chart(ticker, "range=2y&interval=1mo&events=div", SERIES_TIMEOUT)
            .await
        {
            Ok(chart) => chart,
            Err(e) => {
                if let YahooError::Status { retry_after, .. } = &e
                    && e.is_rate_limited()
                {
                    self.cooldown.arm(retry_after.as_deref());
                }
                return Err(e);
            }
        };
        let Some(chart) = chart else {
            return Ok(DividendHistory {
                currency: None,
                events: Vec::new(),
            });
        };
        let currency = chart
            .get("meta")
            .and_then(|m| m.get("currency"))
            .and_then(Value::as_str)
            .map(str::to_owned);
        let mut events = Vec::new();
        if let Some(dividends) = chart
            .get("events")
            .and_then(|e| e.get("dividends"))
            .and_then(Value::as_object)
        {
            for event in dividends.values() {
                let amount = event.get("amount").and_then(json_double);
                let date = event.get("date").and_then(json_i64);
                let (Some(amount), Some(date)) = (amount, date) else {
                    continue;
                };
                if amount <= Decimal::ZERO || date <= 0 {
                    continue;
                }
                // Epoch seconds read in UTC: a midnight-UTC ex-date must not move a day back.
                let Some(ex_date) = DateTime::from_timestamp(date, 0).map(|t| t.date_naive())
                else {
                    continue;
                };
                events.push(DividendPoint { ex_date, amount });
            }
        }
        events.sort_by_key(|p| p.ex_date);
        if events.len() > MAX_DIVIDEND_EVENTS {
            events.drain(..events.len() - MAX_DIVIDEND_EVENTS);
        }
        Ok(DividendHistory { currency, events })
    }

    /// hasQuote: whether Yahoo quotes the ticker; false on any failure.
    pub async fn has_quote(&self, ticker: &str) -> bool {
        Self::supports(ticker) && self.probe_quote(ticker).await == QuoteProbe::Quoted
    }

    /// probeQuote: Quoted, UnknownSymbol (404 or an empty chart) or Unavailable (could not ask,
    /// or no positive price). Nothing is sent while the cooldown is active; no supports() gate.
    pub async fn probe_quote(&self, ticker: &str) -> QuoteProbe {
        if self.cooldown.active() {
            return QuoteProbe::Unavailable;
        }
        match self
            .fetch_chart(ticker, "range=1d&interval=1d", VERIFY_TIMEOUT)
            .await
        {
            Ok(None) => QuoteProbe::UnknownSymbol,
            Ok(Some(chart)) => match chart.get("meta").filter(|m| m.is_object()) {
                None => QuoteProbe::Unavailable,
                Some(meta) => {
                    if market_price(meta).is_some_and(|p| p > Decimal::ZERO) {
                        QuoteProbe::Quoted
                    } else {
                        QuoteProbe::Unavailable
                    }
                }
            },
            Err(e) => {
                if let YahooError::Status {
                    status,
                    retry_after,
                } = &e
                {
                    if *status == 404 {
                        return QuoteProbe::UnknownSymbol;
                    }
                    if *status == 429 {
                        self.cooldown.arm(retry_after.as_deref());
                    }
                }
                tracing::debug!("Yahoo quote probe failed for {}: {e}", safe(ticker));
                QuoteProbe::Unavailable
            }
        }
    }

    async fn search(&self, query: &str) -> Result<Vec<Value>, YahooError> {
        let path = format!(
            "/v1/finance/search?q={}&quotesCount=6&newsCount=0&listsCount=0&enableFuzzyQuery=false",
            utf8_percent_encode(query, QUERY_VALUE)
        );
        let body = self.get_json(&path, VERIFY_TIMEOUT).await?;
        Ok(body
            .get("quotes")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default())
    }

    /// searchSymbols: Yahoo's own matches for a query (an ISIN), in its relevance order, limited
    /// to entries it indexes itself and symbols this client can request. Empty on failure.
    pub async fn search_symbols(&self, query: &str) -> Vec<SymbolMatch> {
        if query.trim().is_empty() {
            return Vec::new();
        }
        let quotes = match self.search(query).await {
            Ok(quotes) => quotes,
            Err(e) => {
                tracing::debug!("Yahoo symbol search failed for {}: {e}", safe(query));
                return Vec::new();
            }
        };
        quotes
            .iter()
            .filter(|q| q.get("isYahooFinance").and_then(Value::as_bool) == Some(true))
            .filter_map(|q| {
                let symbol = q.get("symbol").and_then(Value::as_str)?;
                if !Self::supports(symbol) {
                    return None;
                }
                let name = q
                    .get("longname")
                    .and_then(Value::as_str)
                    .or_else(|| q.get("shortname").and_then(Value::as_str))
                    .map(str::to_owned);
                Some(SymbolMatch {
                    symbol: symbol.to_uppercase(),
                    name,
                })
            })
            .collect()
    }

    /// listingQuote: the last price of `symbol` in its own trading currency. Unlike `prices_eur`
    /// the currency is returned rather than converted away, so a caller can check that a listing
    /// trades in the currency it expects before trusting it. None when Yahoo has no data; a 429
    /// arms the cooldown.
    pub async fn listing_quote(&self, symbol: &str) -> Option<ListingQuote> {
        if !Self::supports(symbol) {
            return None;
        }
        let chart = match self
            .fetch_chart(symbol, "range=1d&interval=1d", VERIFY_TIMEOUT)
            .await
        {
            Ok(chart) => chart?,
            Err(e) => {
                if let YahooError::Status {
                    status: 429,
                    retry_after,
                } = &e
                {
                    self.cooldown.arm(retry_after.as_deref());
                }
                tracing::debug!("Yahoo listing quote failed for {}: {e}", safe(symbol));
                return None;
            }
        };
        let meta = chart.get("meta").filter(|m| m.is_object())?;
        let price = market_price(meta).filter(|p| *p > Decimal::ZERO)?;
        let zone = self.clock.zone();
        let as_of = chart
            .get("timestamp")
            .and_then(Value::as_array)
            .and_then(|stamps| stamps.last())
            .and_then(json_i64)
            .and_then(|epoch| DateTime::from_timestamp(epoch, 0))
            .map(|at| at.with_timezone(&zone).date_naive());
        Some(ListingQuote {
            price,
            currency: meta
                .get("currency")
                .and_then(Value::as_str)
                .map(str::to_owned),
            as_of,
        })
    }

    /// EquityProfileProvider.fetch: the sector key (Yahoo's taxonomy, lower-cased and
    /// underscored) of the search quote whose symbol is the ticker itself, never quotes[0].
    /// The caller builds EquityProfile(sector, null, "Yahoo Finance", false) from it.
    pub async fn equity_sector(&self, ticker: &str) -> Option<String> {
        if !Self::supports(ticker) {
            return None;
        }
        let quotes = match self.search(ticker).await {
            Ok(quotes) => quotes,
            Err(e) => {
                tracing::debug!(
                    "Yahoo equity profile fetch failed for {}: {e}",
                    safe(ticker)
                );
                return None;
            }
        };
        quotes
            .iter()
            .filter(|q| {
                q.get("symbol")
                    .and_then(Value::as_str)
                    .is_some_and(|s| s.eq_ignore_ascii_case(ticker))
            })
            .filter_map(|q| q.get("sector").and_then(Value::as_str))
            .find(|s| !s.trim().is_empty())
            .map(|s| s.trim().to_lowercase().replace(' ', "_"))
    }

    /// getInstrumentType: "ETF", "EQUITY", "MUTUALFUND"... from the chart meta; None when
    /// unavailable.
    pub async fn instrument_type(&self, ticker: &str) -> Option<String> {
        if !Self::supports(ticker) {
            return None;
        }
        match self
            .fetch_chart(ticker, "range=1d&interval=1d", TIMEOUT)
            .await
        {
            Ok(chart) => chart?
                .get("meta")?
                .get("instrumentType")?
                .as_str()
                .filter(|s| !s.trim().is_empty())
                .map(str::to_owned),
            Err(e) => {
                tracing::debug!(
                    "Yahoo instrumentType fetch failed for {}: {e}",
                    safe(ticker)
                );
                None
            }
        }
    }

    /// getFxRateToEur: EUR per unit of the currency. One for EUR, a blank or absent currency;
    /// GBp and GBX are GBP/100 at scale 10 half-up; None when the pair cannot be read.
    pub async fn fx_rate_to_eur(&self, currency: Option<&str>) -> Option<Decimal> {
        let Some(currency) = currency.filter(|c| !c.trim().is_empty()) else {
            return Some(Decimal::ONE);
        };
        if currency.eq_ignore_ascii_case("EUR") {
            return Some(Decimal::ONE);
        }
        if currency == "GBp" || currency.eq_ignore_ascii_case("GBX") {
            let gbp = self.cached_fx("GBP").await?;
            return Some(divide(gbp, Decimal::from(100), 10, HALF_UP));
        }
        self.cached_fx(&currency.to_uppercase()).await
    }

    async fn cached_fx(&self, upper: &str) -> Option<Decimal> {
        let now = self.clock.now();
        {
            let cache = self.fx_cache.lock().unwrap_or_else(|e| e.into_inner());
            if let Some((rate, at)) = cache.get(upper) {
                let ttl = if rate.is_some() {
                    FX_CACHE_TTL
                } else {
                    FX_MISS_CACHE_TTL
                };
                if now < *at + ttl {
                    return *rate;
                }
            }
        }
        let rate = self.fetch_fx_rate(upper).await;
        self.fx_cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(upper.to_owned(), (rate, self.clock.now()));
        rate
    }

    async fn fetch_fx_rate(&self, currency: &str) -> Option<Decimal> {
        let pair = format!("{currency}EUR=X");
        match self
            .fetch_chart(&pair, "range=1d&interval=1d", TIMEOUT)
            .await
        {
            Ok(chart) => chart
                .as_ref()
                .and_then(|c| c.get("meta"))
                .filter(|m| m.is_object())
                .and_then(market_price)
                .filter(|r| *r > Decimal::ZERO),
            Err(e) => {
                tracing::debug!("FX fetch failed for {pair}: {e}");
                None
            }
        }
    }

    /// The chart's close series in EUR (today's rate for every point), scale 8 half-up; empty when
    /// the FX rate is unavailable.
    async fn close_series_eur<K: PartialEq>(
        &self,
        chart: Option<Value>,
        ticker: &str,
        series_name: &str,
        key_of: impl Fn(i64) -> Option<K>,
        in_range: impl Fn(&K) -> bool,
    ) -> Vec<(K, Decimal)> {
        let Some(chart) = chart else {
            return Vec::new();
        };
        let Some(timestamps) = chart.get("timestamp").and_then(Value::as_array) else {
            return Vec::new();
        };
        let Some(closes) = chart
            .get("indicators")
            .and_then(|i| i.get("quote"))
            .and_then(Value::as_array)
            .and_then(|q| q.first())
            .and_then(|q| q.get("close"))
            .and_then(Value::as_array)
        else {
            return Vec::new();
        };
        let meta = chart.get("meta").filter(|m| m.is_object());
        let fx = match meta {
            Some(meta) => {
                let currency = meta.get("currency").and_then(Value::as_str);
                let rate = self.fx_rate_to_eur(currency).await;
                if rate.is_none() {
                    tracing::warn!(
                        "Skipping {series_name} series for {}: FX rate unavailable for {}",
                        safe(ticker),
                        safe(currency.unwrap_or("null"))
                    );
                    return Vec::new();
                }
                rate
            }
            None => Some(Decimal::ONE),
        };
        let fx = fx.unwrap_or(Decimal::ONE);
        let mut prices = Vec::new();
        for (timestamp, close) in timestamps.iter().zip(closes.iter()) {
            let Some(close) = json_double(close).filter(|c| *c > Decimal::ZERO) else {
                continue;
            };
            let Some(key) = json_i64(timestamp).and_then(&key_of) else {
                continue;
            };
            if in_range(&key) {
                num::put(&mut prices, key, set_scale(close * fx, 8, HALF_UP));
            }
        }
        prices
    }

    /// getIntradayPricesEur: hourly closes of the last day within [from, to], in local time.
    pub async fn intraday_prices_eur(
        &self,
        ticker: &str,
        from: NaiveDateTime,
        to: NaiveDateTime,
    ) -> Vec<(NaiveDateTime, Decimal)> {
        if !Self::supports(ticker) {
            return Vec::new();
        }
        match self
            .fetch_chart(ticker, "range=1d&interval=1h", SERIES_TIMEOUT)
            .await
        {
            Ok(chart) => {
                let zone = self.clock.zone();
                self.close_series_eur(
                    chart,
                    ticker,
                    "intraday",
                    |epoch| {
                        DateTime::from_timestamp(epoch, 0)
                            .map(|t| t.with_timezone(&zone).naive_local())
                    },
                    |at| *at >= from && *at <= to,
                )
                .await
            }
            Err(e) => {
                tracing::warn!(
                    "Yahoo intraday price fetch failed for {}: {e}",
                    safe(ticker)
                );
                Vec::new()
            }
        }
    }

    /// getHistoricalPricesEur: daily closes within [from, to], from the smallest Yahoo range that
    /// covers them.
    pub async fn historical_prices_eur(
        &self,
        ticker: &str,
        from: NaiveDate,
        to: NaiveDate,
    ) -> Vec<(NaiveDate, Decimal)> {
        if !Self::supports(ticker) {
            return Vec::new();
        }
        let days = (to - from).num_days() + 1;
        let range = match days {
            ..=7 => "5d",
            8..=30 => "1mo",
            31..=90 => "3mo",
            91..=365 => "1y",
            _ => "5y",
        };
        match self
            .fetch_chart(
                ticker,
                &format!("range={range}&interval=1d"),
                SERIES_TIMEOUT,
            )
            .await
        {
            Ok(chart) => {
                let zone = self.clock.zone();
                self.close_series_eur(
                    chart,
                    ticker,
                    "historical",
                    |epoch| {
                        DateTime::from_timestamp(epoch, 0)
                            .map(|t| t.with_timezone(&zone).date_naive())
                    },
                    |date| *date >= from && *date <= to,
                )
                .await
            }
            Err(e) => {
                tracing::warn!(
                    "Yahoo historical price fetch failed for {}: {e}",
                    safe(ticker)
                );
                Vec::new()
            }
        }
    }
}

/// meta.regularMarketPrice as BigDecimal.valueOf(double); Jackson's primitive default is 0.
fn market_price(meta: &Value) -> Option<Decimal> {
    Some(
        meta.get("regularMarketPrice")
            .and_then(json_double)
            .unwrap_or(Decimal::ZERO),
    )
}

/// An upstream failure as a log line, without the URL (it can carry a user's ticker list).
pub(crate) fn transport_message(error: &reqwest::Error) -> String {
    if error.is_timeout() {
        "timed out".to_owned()
    } else if error.is_connect() {
        "could not connect".to_owned()
    } else {
        "request failed".to_owned()
    }
}

/// LogSanitizer.safe: control characters out of user data before it reaches a log line.
pub(crate) fn safe(value: &str) -> String {
    value
        .chars()
        .map(|c| if c.is_control() { '_' } else { c })
        .collect()
}
