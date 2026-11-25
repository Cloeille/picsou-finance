//! EUR prices by ISIN (#700, part of #698): the instrument's own identity rather than a ticker that
//! names one of its listings. Two sources, justETF then Yahoo on the ISIN's reference listing;
//! `IsinPriceService` asks them in that order and the first answer wins. Nothing reads it yet.
//!
//! A source never fails on an expected upstream problem (timeout, 429, unknown ISIN, an unlisted
//! fund): it answers None or an empty series and the next source tries.

use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use chrono::{DateTime, NaiveDate, Utc};
use percent_encoding::utf8_percent_encode;
use rust_decimal::Decimal;
use serde_json::Value;

use super::num::json_decimal;
use super::openfigi::is_isin;
use super::yahoo::{PATH_SEGMENT, YahooFinance, safe, transport_message};
use crate::clock::AppClock;

const JUSTETF: &str = "justETF";
const YAHOO: &str = "Yahoo";
const JUSTETF_TIMEOUT: Duration = Duration::from_secs(8);
/// How long a justETF 429 pauses that source.
pub const RATE_LIMIT_PAUSE: chrono::Duration = chrono::Duration::minutes(5);
/// How many of Yahoo's matches are probed for the reference currency, in its own order.
pub const MAX_PROBED: usize = 3;
pub const QUOTE_TTL: chrono::Duration = chrono::Duration::minutes(15);
pub const MISS_TTL: chrono::Duration = chrono::Duration::minutes(5);

const EURO_AREA: [&str; 20] = [
    "AT", "BE", "CY", "DE", "EE", "ES", "FI", "FR", "GR", "HR", "IE", "IT", "LT", "LU", "LV", "MT",
    "NL", "PT", "SI", "SK",
];
const MARKET_CURRENCY: [(&str, &str); 10] = [
    ("US", "USD"),
    ("GB", "GBP"),
    ("CH", "CHF"),
    ("CA", "CAD"),
    ("JP", "JPY"),
    ("HK", "HKD"),
    ("AU", "AUD"),
    ("SE", "SEK"),
    ("DK", "DKK"),
    ("NO", "NOK"),
];

/// A quote in EUR, dated by the source.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IsinQuote {
    pub price_eur: Decimal,
    pub as_of: Option<NaiveDate>,
    pub source: &'static str,
}

/// A price source keyed by ISIN, in EUR.
#[async_trait]
pub trait IsinPriceSource: Send + Sync {
    fn name(&self) -> &'static str;

    async fn quote(&self, isin: &str) -> Option<IsinQuote>;

    /// Daily closes in EUR between `from` and `to` inclusive, empty when unknown.
    async fn daily_history(
        &self,
        isin: &str,
        from: NaiveDate,
        to: NaiveDate,
    ) -> BTreeMap<NaiveDate, Decimal>;
}

/// justETF's quote and chart endpoints, by ISIN, in EUR. Covers listed shares and ETFs; an
/// unlisted fund answers `latestQuote: null`. The API is undocumented, which is why the service
/// has a second source behind it. A 429 pauses this source for `RATE_LIMIT_PAUSE`.
pub struct JustEtfIsinPriceSource {
    http: reqwest::Client,
    base_url: String,
    clock: AppClock,
    paused_until: Mutex<DateTime<Utc>>,
}

impl JustEtfIsinPriceSource {
    pub fn new(http: reqwest::Client, base_url: &str, clock: AppClock) -> Self {
        Self {
            http,
            base_url: base_url.trim_end_matches('/').to_owned(),
            clock,
            paused_until: Mutex::new(DateTime::<Utc>::UNIX_EPOCH),
        }
    }

    async fn get(&self, isin: &str, endpoint: &str) -> Option<Value> {
        if self.clock.now() < *self.paused_until.lock().unwrap_or_else(|e| e.into_inner()) {
            return None;
        }
        let url = format!(
            "{}/api/etfs/{}/{endpoint}",
            self.base_url,
            utf8_percent_encode(isin, PATH_SEGMENT)
        );
        let response = match self
            .http
            .get(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .timeout(JUSTETF_TIMEOUT)
            .send()
            .await
        {
            Ok(response) => response,
            Err(e) => {
                tracing::debug!(
                    "justETF request failed for {}: {}",
                    safe(isin),
                    transport_message(&e)
                );
                return None;
            }
        };
        let status = response.status();
        if status.as_u16() == 429 {
            *self.paused_until.lock().unwrap_or_else(|e| e.into_inner()) =
                self.clock.now() + RATE_LIMIT_PAUSE;
            tracing::warn!("justETF rate-limited, pausing it for {RATE_LIMIT_PAUSE}");
            return None;
        }
        if !status.is_success() {
            tracing::debug!("justETF answered {status} for {}", safe(isin));
            return None;
        }
        response.json::<Value>().await.ok()
    }
}

fn raw_price(node: Option<&Value>) -> Option<Decimal> {
    node?
        .get("raw")
        .and_then(json_decimal)
        .filter(|p| *p > Decimal::ZERO)
}

fn date_of(node: Option<&Value>) -> Option<NaiveDate> {
    NaiveDate::parse_from_str(node?.as_str()?, "%Y-%m-%d").ok()
}

#[async_trait]
impl IsinPriceSource for JustEtfIsinPriceSource {
    fn name(&self) -> &'static str {
        JUSTETF
    }

    async fn quote(&self, isin: &str) -> Option<IsinQuote> {
        let body = self
            .get(
                isin,
                &format!("quote?locale=en&currency=EUR&isin={}", query_value(isin)),
            )
            .await?;
        Some(IsinQuote {
            price_eur: raw_price(body.get("latestQuote"))?,
            as_of: date_of(body.get("latestQuoteDate")),
            source: JUSTETF,
        })
    }

    async fn daily_history(
        &self,
        isin: &str,
        from: NaiveDate,
        to: NaiveDate,
    ) -> BTreeMap<NaiveDate, Decimal> {
        let endpoint = format!(
            "performance-chart?locale=en&currency=EUR&valuesType=MARKET_VALUE\
             &reduceData=false&includeDividends=false&dateFrom={from}&dateTo={to}"
        );
        let Some(body) = self.get(isin, &endpoint).await else {
            return BTreeMap::new();
        };
        body.get("series")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(|point| {
                let date = date_of(point.get("date"))?;
                let price = raw_price(point.get("value"))?;
                (date >= from && date <= to).then_some((date, price))
            })
            .collect()
    }
}

fn query_value(value: &str) -> String {
    utf8_percent_encode(value, super::yahoo::QUERY_VALUE).to_string()
}

/// The currency the ISIN's own market trades in, None when it is not known here.
pub fn reference_currency(isin: &str) -> Option<&'static str> {
    let country = isin.get(..2)?.to_uppercase();
    if EURO_AREA.contains(&country.as_str()) {
        return Some("EUR");
    }
    MARKET_CURRENCY
        .iter()
        .find(|(code, _)| *code == country)
        .map(|(_, currency)| *currency)
}

/// Whether a listing quoted in `listing` is on the market of `reference`. London quotes in pence
/// (`GBp`, `GBX`): still the GBP market.
pub fn trades_in(listing: Option<&str>, reference: &str) -> bool {
    let Some(listing) = listing else {
        return false;
    };
    if reference == "GBP" && (listing == "GBp" || listing.eq_ignore_ascii_case("GBX")) {
        return true;
    }
    reference.eq_ignore_ascii_case(listing)
}

/// Yahoo, asked by ISIN: its own search maps the ISIN to the symbols it lists, and the price is
/// read from the ISIN's reference listing, the one trading in the currency of the ISIN's market
/// (`US` in USD, a euro-area ISIN including `LU` and `IE` in EUR). A listing in another currency
/// is never taken: that is how `LU1829219390` was priced through the grey-market USD line `MULYF`
/// at 376 EUR for a 4.41 EUR share (#683). An ISIN whose market currency is unknown here, or
/// whose reference listing Yahoo does not carry, gets no Yahoo price.
pub struct YahooIsinPriceSource {
    yahoo: Arc<YahooFinance>,
    /// ISIN to the reference symbol found for it, so history and later quotes skip the search.
    symbol_by_isin: Mutex<HashMap<String, String>>,
}

impl YahooIsinPriceSource {
    pub fn new(yahoo: Arc<YahooFinance>) -> Self {
        Self {
            yahoo,
            symbol_by_isin: Mutex::new(HashMap::new()),
        }
    }

    fn known_symbol(&self, isin: &str) -> Option<String> {
        self.symbol_by_isin
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(isin)
            .cloned()
    }

    fn remember(&self, isin: &str, symbol: Option<String>) {
        let mut symbols = self
            .symbol_by_isin
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        match symbol {
            Some(symbol) => symbols.insert(isin.to_owned(), symbol),
            None => symbols.remove(isin),
        };
    }

    async fn quote_of(&self, symbol: &str, reference: &str) -> Option<IsinQuote> {
        let listing = self.yahoo.listing_quote(symbol).await?;
        if !trades_in(listing.currency.as_deref(), reference) {
            return None;
        }
        let fx = self
            .yahoo
            .fx_rate_to_eur(listing.currency.as_deref())
            .await?;
        Some(IsinQuote {
            price_eur: listing.price * fx,
            as_of: listing.as_of,
            source: YAHOO,
        })
    }
}

#[async_trait]
impl IsinPriceSource for YahooIsinPriceSource {
    fn name(&self) -> &'static str {
        YAHOO
    }

    async fn quote(&self, isin: &str) -> Option<IsinQuote> {
        let reference = reference_currency(isin)?;
        if let Some(known) = self.known_symbol(isin) {
            if let Some(quote) = self.quote_of(&known, reference).await {
                return Some(quote);
            }
            self.remember(isin, None);
        }
        for found in self
            .yahoo
            .search_symbols(isin)
            .await
            .iter()
            .take(MAX_PROBED)
        {
            if let Some(quote) = self.quote_of(&found.symbol, reference).await {
                self.remember(isin, Some(found.symbol.clone()));
                tracing::debug!(
                    "ISIN {} priced on Yahoo through its {reference} listing {}",
                    safe(isin),
                    safe(&found.symbol)
                );
                return Some(quote);
            }
        }
        None
    }

    async fn daily_history(
        &self,
        isin: &str,
        from: NaiveDate,
        to: NaiveDate,
    ) -> BTreeMap<NaiveDate, Decimal> {
        if self.known_symbol(isin).is_none() && self.quote(isin).await.is_none() {
            return BTreeMap::new();
        }
        let Some(symbol) = self.known_symbol(isin) else {
            return BTreeMap::new();
        };
        self.yahoo
            .historical_prices_eur(&symbol, from, to)
            .await
            .into_iter()
            .collect()
    }
}

type CachedQuote = (Option<IsinQuote>, DateTime<Utc>);

/// EUR prices by ISIN from the sources in order, the first answer winning. Nothing here falls
/// back to a ticker. Quotes are cached for `QUOTE_TTL`, misses for `MISS_TTL`, so a portfolio
/// page does not cost one request per line per render and an unlisted fund is not asked every
/// time.
pub struct IsinPriceService {
    sources: Vec<Arc<dyn IsinPriceSource>>,
    clock: AppClock,
    cache: Mutex<HashMap<String, CachedQuote>>,
}

impl IsinPriceService {
    pub fn new(sources: Vec<Arc<dyn IsinPriceSource>>, clock: AppClock) -> Self {
        Self {
            sources,
            clock,
            cache: Mutex::new(HashMap::new()),
        }
    }

    /// The production order: justETF, then Yahoo on the reference listing.
    pub fn standard(
        http: reqwest::Client,
        justetf_base_url: &str,
        yahoo: Arc<YahooFinance>,
        clock: AppClock,
    ) -> Self {
        Self::new(
            vec![
                Arc::new(JustEtfIsinPriceSource::new(
                    http,
                    justetf_base_url,
                    clock.clone(),
                )),
                Arc::new(YahooIsinPriceSource::new(yahoo)),
            ],
            clock,
        )
    }

    /// The EUR quote for `isin`, None when no source has one (an unlisted fund, say).
    pub async fn quote(&self, isin: &str) -> Option<IsinQuote> {
        let key = normalize(isin)?;
        let now = self.clock.now();
        if let Some((quote, at)) = self
            .cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&key)
        {
            let ttl = if quote.is_some() { QUOTE_TTL } else { MISS_TTL };
            if now < *at + ttl {
                return quote.clone();
            }
        }
        let mut found = None;
        for source in &self.sources {
            found = source.quote(&key).await;
            if found.is_some() {
                break;
            }
        }
        self.cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(key, (found.clone(), now));
        found
    }

    /// Daily EUR closes for `isin`, from the first source that has any.
    pub async fn daily_history(
        &self,
        isin: &str,
        from: NaiveDate,
        to: NaiveDate,
    ) -> BTreeMap<NaiveDate, Decimal> {
        let Some(key) = normalize(isin) else {
            return BTreeMap::new();
        };
        if from > to {
            return BTreeMap::new();
        }
        for source in &self.sources {
            let history = source.daily_history(&key, from, to).await;
            if !history.is_empty() {
                return history;
            }
        }
        BTreeMap::new()
    }
}

fn normalize(isin: &str) -> Option<String> {
    is_isin(isin).then(|| isin.trim().to_uppercase())
}
