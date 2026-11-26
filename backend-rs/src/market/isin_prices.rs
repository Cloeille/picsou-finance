//! EUR prices by ISIN (#700, part of #698): the instrument's own identity rather than a ticker that
//! names one of its listings. Three sources, justETF, then Yahoo on the ISIN's reference listing,
//! then Boursorama for unlisted funds (#704); `IsinPriceService` asks them in that order and the
//! first answer wins.
//!
//! A source never fails on an expected upstream problem (timeout, 429, unknown ISIN, an unlisted
//! fund): it answers None or an empty series and the next source tries.

use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use chrono::Days;
use chrono::{DateTime, NaiveDate, Utc};
use percent_encoding::utf8_percent_encode;
use regex::Regex;
use rust_decimal::Decimal;
use serde_json::Value;
use std::sync::LazyLock;

use super::boursorama::symbol_from_location;

use super::num::json_decimal;
use super::openfigi::is_isin;
use super::yahoo::{PATH_SEGMENT, YahooFinance, safe, transport_message};
use crate::clock::AppClock;

const JUSTETF: &str = "justETF";
const YAHOO: &str = "Yahoo";
const BOURSORAMA: &str = "Boursorama";
const BOURSORAMA_HOST: &str = "https://www.boursorama.com";
const JUSTETF_TIMEOUT: Duration = Duration::from_secs(8);
const BOURSORAMA_TIMEOUT: Duration = Duration::from_secs(8);
/// The windows, in days, Boursorama's end-of-day series accepts: any other length answers `[]`.
pub const BOURSORAMA_WINDOWS: [u64; 9] = [30, 90, 180, 365, 730, 1095, 1825, 3650, 7300];
/// How long a justETF 429, or a Boursorama 429 or 403, pauses that source.
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
        let isin = normalize(isin)?;
        let body = self
            .get(
                &isin,
                &format!("quote?locale=en&currency=EUR&isin={}", query_value(&isin)),
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
        let Some(isin) = normalize(isin) else {
            return BTreeMap::new();
        };
        let endpoint = format!(
            "performance-chart?locale=en&currency=EUR&valuesType=MARKET_VALUE\
             &reduceData=false&includeDividends=false&dateFrom={from}&dateTo={to}"
        );
        let Some(body) = self.get(&isin, &endpoint).await else {
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
            price_eur: listing.price.checked_mul(fx)?,
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
        let isin = normalize(isin)?;
        let reference = reference_currency(&isin)?;
        if let Some(known) = self.known_symbol(&isin) {
            if let Some(quote) = self.quote_of(&known, reference).await {
                return Some(quote);
            }
            self.remember(&isin, None);
        }
        for found in self
            .yahoo
            .search_symbols(&isin)
            .await
            .iter()
            .take(MAX_PROBED)
        {
            if let Some(quote) = self.quote_of(&found.symbol, reference).await {
                self.remember(&isin, Some(found.symbol.clone()));
                tracing::debug!(
                    "ISIN {} priced on Yahoo through its {reference} listing {}",
                    safe(&isin),
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
        let Some(isin) = normalize(isin) else {
            return BTreeMap::new();
        };
        if self.known_symbol(&isin).is_none() && self.quote(&isin).await.is_none() {
            return BTreeMap::new();
        }
        let Some(symbol) = self.known_symbol(&isin) else {
            return BTreeMap::new();
        };
        self.yahoo
            .historical_prices_eur(&symbol, from, to)
            .await
            .into_iter()
            .collect()
    }
}

static SEARCH_LI: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?is)<li\b[^>]*>(.*?)</li>").expect("regex"));
static SEARCH_ROW: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)href="[^"]*/cours/([^/"?]+)/".*?class="last">([^<]*)<"#).expect("regex")
});

/// Boursorama, asked by ISIN (#704, ADR 2026-11-22): the only source found for the daily NAV of
/// an unlisted fund (OPCVM), which justETF and Yahoo do not carry. Three unauthenticated calls:
///
/// 1. `GET /recherche/?query=<ISIN>`, not followed: Boursorama redirects to `/cours/<symbol>/`
///    only when it knows the ISIN, and answers 200 otherwise. The symbol never comes from the
///    holding's ticker.
/// 2. `GET /recherche/ajax?query=<ISIN>`: the result row of that symbol must show its last price
///    in euros. Any other currency gets no price.
/// 3. `GET /bourse/action/graph/ws/GetTicksEOD?symbol=<symbol>&length=<days>`, as an XHR (a plain
///    request answers 410): `QuoteTab` holds one point per day, `d` days after 1970-01-01, close
///    `c`. Only the windows in [`BOURSORAMA_WINDOWS`] answer.
///
/// A 429 or 403 pauses the source for `RATE_LIMIT_PAUSE`. The symbol found is remembered.
pub struct BoursoramaIsinPriceSource {
    http: reqwest::Client,
    base_url: String,
    clock: AppClock,
    paused_until: Mutex<DateTime<Utc>>,
    symbol_by_isin: Mutex<HashMap<String, String>>,
}

impl BoursoramaIsinPriceSource {
    pub fn new(base_url: &str, clock: AppClock) -> Self {
        let http = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(BOURSORAMA_TIMEOUT)
            .build()
            .unwrap_or_default();
        Self {
            http,
            base_url: base_url.trim_end_matches('/').to_owned(),
            clock,
            paused_until: Mutex::new(DateTime::<Utc>::UNIX_EPOCH),
            symbol_by_isin: Mutex::new(HashMap::new()),
        }
    }

    async fn get(&self, path_and_query: &str, xhr: bool) -> Option<reqwest::Response> {
        if self.clock.now() < *self.paused_until.lock().unwrap_or_else(|e| e.into_inner()) {
            return None;
        }
        let mut request = self
            .http
            .get(format!("{}{path_and_query}", self.base_url))
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept-Language", "fr-FR");
        if xhr {
            request = request.header("X-Requested-With", "XMLHttpRequest");
        }
        let response = match request.send().await {
            Ok(response) => response,
            Err(e) => {
                tracing::debug!("Boursorama request failed: {}", transport_message(&e));
                return None;
            }
        };
        if matches!(response.status().as_u16(), 403 | 429) {
            *self.paused_until.lock().unwrap_or_else(|e| e.into_inner()) =
                self.clock.now() + RATE_LIMIT_PAUSE;
            tracing::warn!(
                "Boursorama answered {}, pausing it for {RATE_LIMIT_PAUSE}",
                response.status()
            );
            return None;
        }
        Some(response)
    }

    /// Boursorama's symbol for the ISIN, when it knows the ISIN and quotes it in euros.
    async fn symbol(&self, isin: &str) -> Option<String> {
        if let Some(known) = self
            .symbol_by_isin
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(isin)
        {
            return Some(known.clone());
        }
        let found = self
            .get(&format!("/recherche/?query={}", query_value(isin)), false)
            .await?;
        if !found.status().is_redirection() {
            return None;
        }
        let symbol = found
            .headers()
            .get(reqwest::header::LOCATION)
            .and_then(|v| v.to_str().ok())
            .and_then(symbol_from_location)?;
        let rows = self
            .get(
                &format!("/recherche/ajax?query={}&searchId=", query_value(isin)),
                false,
            )
            .await?;
        if !rows.status().is_success() {
            return None;
        }
        let html = rows.text().await.ok()?;
        if !quoted_in_euros(&html, &symbol) {
            tracing::debug!(
                "Boursorama does not quote {} in euros, no price taken",
                safe(isin)
            );
            return None;
        }
        self.symbol_by_isin
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(isin.to_owned(), symbol.clone());
        Some(symbol)
    }

    async fn closes(&self, symbol: &str, days: u64) -> BTreeMap<NaiveDate, Decimal> {
        let path = format!(
            "/bourse/action/graph/ws/GetTicksEOD?symbol={}&length={days}&period=0&guid=",
            query_value(symbol)
        );
        let Some(response) = self
            .get(&path, true)
            .await
            .filter(|r| r.status().is_success())
        else {
            return BTreeMap::new();
        };
        match response.json::<Value>().await {
            Ok(body) => end_of_day_closes(&body),
            Err(_) => BTreeMap::new(),
        }
    }
}

/// Whether a search row (`<li>…</li>`) linking to `symbol` shows a price in euros (`827,91 €`).
pub fn quoted_in_euros(search_html: &str, symbol: &str) -> bool {
    SEARCH_LI.captures_iter(search_html).any(|li| {
        SEARCH_ROW
            .captures_iter(&li[1])
            .any(|c| &c[1] == symbol && c[2].trim().ends_with('€'))
    })
}

/// The closes of a `GetTicksEOD` body, by day. Anything but the expected shape is no series.
pub fn end_of_day_closes(body: &Value) -> BTreeMap<NaiveDate, Decimal> {
    let epoch = NaiveDate::from_ymd_opt(1970, 1, 1).expect("valid date");
    body.pointer("/d/QuoteTab")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|point| {
            let day = epoch.checked_add_days(Days::new(point.get("d")?.as_u64()?))?;
            let close = point.get("c").and_then(json_decimal)?;
            (close > Decimal::ZERO).then_some((day, close))
        })
        .collect()
}

/// The shortest window Boursorama accepts that reaches back `days`, the longest one otherwise.
pub fn boursorama_window(days: u64) -> u64 {
    BOURSORAMA_WINDOWS
        .into_iter()
        .find(|w| *w >= days)
        .unwrap_or(BOURSORAMA_WINDOWS[BOURSORAMA_WINDOWS.len() - 1])
}

#[async_trait]
impl IsinPriceSource for BoursoramaIsinPriceSource {
    fn name(&self) -> &'static str {
        BOURSORAMA
    }

    async fn quote(&self, isin: &str) -> Option<IsinQuote> {
        let isin = normalize(isin)?;
        let symbol = self.symbol(&isin).await?;
        let (day, close) = self
            .closes(&symbol, BOURSORAMA_WINDOWS[0])
            .await
            .pop_last()?;
        Some(IsinQuote {
            price_eur: close,
            as_of: Some(day),
            source: BOURSORAMA,
        })
    }

    async fn daily_history(
        &self,
        isin: &str,
        from: NaiveDate,
        to: NaiveDate,
    ) -> BTreeMap<NaiveDate, Decimal> {
        let Some(isin) = normalize(isin) else {
            return BTreeMap::new();
        };
        if from > to {
            return BTreeMap::new();
        }
        let Some(symbol) = self.symbol(&isin).await else {
            return BTreeMap::new();
        };
        // The window ends today, so it has to reach back to `from`.
        let back = (self.clock.today() - from).num_days().max(1) as u64;
        let mut closes = self.closes(&symbol, boursorama_window(back)).await;
        closes.retain(|day, _| *day >= from && *day <= to);
        closes
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

    /// The production order: justETF, then Yahoo on the reference listing, then Boursorama.
    pub fn standard(
        http: reqwest::Client,
        justetf_base_url: &str,
        yahoo: Arc<YahooFinance>,
        clock: AppClock,
    ) -> Self {
        // As Market does for ETF composition: offline tests point justETF at a stub, and then
        // Boursorama is not asked either.
        let boursorama_base_url = if justetf_base_url.contains("justetf.com") {
            BOURSORAMA_HOST
        } else {
            ""
        };
        Self::new(
            vec![
                Arc::new(JustEtfIsinPriceSource::new(
                    http,
                    justetf_base_url,
                    clock.clone(),
                )),
                Arc::new(YahooIsinPriceSource::new(yahoo)),
                Arc::new(BoursoramaIsinPriceSource::new(
                    boursorama_base_url,
                    clock.clone(),
                )),
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
