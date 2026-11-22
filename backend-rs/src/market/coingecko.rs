//! CoinGeckoPriceProvider + CryptoAssetRegistry: spot EUR prices, daily & intraday history,
//! coin logo URLs, and dominance-gated symbol learning over CoinGecko's public API.

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::sync::Arc;
use std::time::Duration;

use chrono::{DateTime, NaiveDate, NaiveDateTime, TimeZone};
use rust_decimal::Decimal;
use serde_json::Value;
use sqlx::PgPool;

use super::cooldown::YahooCooldown;
use super::num::{json_decimal, json_i64, put};
use super::yahoo::{QUERY_VALUE, safe, transport_message};
use crate::clock::AppClock;
use crate::ledger::decimal::{HALF_UP, divide, set_scale};

const TIMEOUT: Duration = Duration::from_secs(10);
const HISTORY_TIMEOUT: Duration = Duration::from_secs(15);
const SYMBOL_LOOKUP_PAGE: usize = 250;
const SYMBOL_LOOKUP_MAX_PAGES: usize = 4;
const SYMBOL_LOOKUP_BATCH: usize = 50;
const DOMINANCE_RATIO: Decimal = Decimal::TEN;
/// CoinGecko's free public v3 API rejects `market_chart/range` queries older than 365 days with
/// HTTP 401 (error 10012). Keep a 24 h margin (364 days) for clock skew, leap years, and local
/// midnight offsets so a 12-month startup backfill never trips the cutoff.
const PUBLIC_HISTORY_MAX_LOOKBACK_SECS: i64 = 364 * 86_400;

/// A CoinGecko coin id and display name stored in crypto_asset.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Coin {
    pub id: String,
    pub name: String,
}

/// Outcome of resolve_by_symbol: `answered` is false if CoinGecko could not be asked (cooldown,
/// outage, timeout, or crowded pages) so undecided tickers are retried soon rather than remembered.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Resolution {
    pub accepted: BTreeMap<String, Coin>,
    pub answered: bool,
}

#[derive(Debug)]
struct MarketEntry {
    id: Option<String>,
    image: Option<String>,
    symbol: Option<String>,
    name: Option<String>,
    rank: Option<i64>,
    market_cap: Option<Decimal>,
}

pub struct CoinGecko {
    http: reqwest::Client,
    base_url: String,
    api_key: Option<String>,
    db: PgPool,
    clock: AppClock,
    /// Armed by a 429; while active nothing is asked.
    cooldown: Arc<YahooCooldown>,
}

impl CoinGecko {
    pub fn new(
        http: reqwest::Client,
        base_url: &str,
        db: PgPool,
        clock: AppClock,
        cooldown: Arc<YahooCooldown>,
    ) -> Self {
        Self {
            http,
            base_url: base_url.trim_end_matches('/').to_owned(),
            api_key: None,
            db,
            clock,
            cooldown,
        }
    }

    pub fn with_api_key(mut self, api_key: Option<String>) -> Self {
        self.api_key = api_key
            .map(|k| k.trim().to_owned())
            .filter(|k| !k.is_empty());
        self
    }

    /// The registry's display name of a known coin (BTC to "Bitcoin").
    pub async fn display_name(&self, ticker: &str) -> Option<String> {
        sqlx::query_scalar("SELECT name FROM crypto_asset WHERE upper(ticker) = upper($1)")
            .bind(ticker)
            .fetch_optional(&self.db)
            .await
            .ok()
            .flatten()
    }

    /// The CoinGecko ids of the upper-cased tickers the registry knows.
    pub async fn coin_ids(&self, tickers: &BTreeSet<String>) -> HashMap<String, String> {
        if tickers.is_empty() {
            return HashMap::new();
        }
        let tickers: Vec<&str> = tickers.iter().map(String::as_str).collect();
        let rows: Result<Vec<(String, String)>, _> = sqlx::query_as(
            "SELECT upper(ticker), coingecko_id FROM crypto_asset WHERE upper(ticker) = ANY($1)",
        )
        .bind(&tickers)
        .fetch_all(&self.db)
        .await;
        match rows {
            Ok(rows) => rows.into_iter().collect(),
            Err(e) => {
                tracing::warn!("Could not read the crypto registry: {e}");
                HashMap::new()
            }
        }
    }

    async fn coin_id(&self, ticker: &str) -> Option<String> {
        sqlx::query_scalar("SELECT coingecko_id FROM crypto_asset WHERE upper(ticker) = upper($1)")
            .bind(ticker)
            .fetch_optional(&self.db)
            .await
            .ok()
            .flatten()
    }

    /// CryptoAssetRegistry.learn: persists a learned coin if absent and drops any Yahoo-era
    /// price_snapshot rows for that ticker in the same transaction.
    pub async fn learn(&self, ticker: &str, id: &str, name: &str) -> sqlx::Result<bool> {
        let key = ticker.trim().to_uppercase();
        let mut tx = self.db.begin().await?;
        let inserted = sqlx::query(
            "INSERT INTO crypto_asset (ticker, coingecko_id, name, source) \
             VALUES ($1, $2, $3, 'AUTO') ON CONFLICT (ticker) DO NOTHING",
        )
        .bind(&key)
        .bind(id)
        .bind(name)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        if inserted > 0 {
            let dropped = sqlx::query("DELETE FROM price_snapshot WHERE ticker = $1")
                .bind(&key)
                .execute(&mut *tx)
                .await?
                .rows_affected();
            if dropped > 0 {
                tracing::info!(
                    "Learned crypto ticker {}: dropped {dropped} price snapshots written while it was priced as a stock",
                    safe(&key)
                );
            }
        }
        tx.commit().await?;
        Ok(inserted > 0)
    }

    /// getPricesEur over the registry's coins; a failure is no prices, a 429 arms the cooldown.
    pub async fn prices_eur(&self, tickers: &BTreeSet<String>) -> HashMap<String, Decimal> {
        let ids = self.coin_ids(tickers).await;
        if ids.is_empty() || self.cooldown.active() {
            return HashMap::new();
        }
        let joined: BTreeSet<&str> = ids.values().map(String::as_str).collect();
        let joined = joined.into_iter().collect::<Vec<_>>().join(",");
        let url = format!(
            "{}/simple/price?ids={}&vs_currencies=eur",
            self.base_url,
            percent_encoding::utf8_percent_encode(&joined, QUERY_VALUE)
        );
        let Some(body) = self.get_json(&url, TIMEOUT, "spot prices").await else {
            return HashMap::new();
        };
        let mut result = HashMap::new();
        for (ticker, id) in &ids {
            if let Some(price) = body
                .get(id)
                .and_then(|p| p.get("eur"))
                .and_then(json_decimal)
            {
                result.insert(ticker.clone(), price);
            }
        }
        let missing: Vec<String> = ids
            .keys()
            .filter(|t| !result.contains_key(*t))
            .map(|t| safe(t))
            .collect();
        if !missing.is_empty() {
            tracing::warn!("CoinGecko had no EUR price for {missing:?}");
        }
        result
    }

    /// getLogoUrls over the registry's coins (LogoProviderPort).
    pub async fn logo_urls(&self, tickers: &BTreeSet<String>) -> HashMap<String, String> {
        let upper: BTreeSet<String> = tickers.iter().map(|t| t.to_uppercase()).collect();
        let ids = self.coin_ids(&upper).await;
        if ids.is_empty() || self.cooldown.active() {
            return HashMap::new();
        }
        let joined: BTreeSet<&str> = ids.values().map(String::as_str).collect();
        let joined = joined.into_iter().collect::<Vec<_>>().join(",");
        let url = format!(
            "{}/coins/markets?vs_currency=eur&ids={}&per_page={}",
            self.base_url,
            percent_encoding::utf8_percent_encode(&joined, QUERY_VALUE),
            ids.len().max(1)
        );
        let Some(body) = self.get_json(&url, TIMEOUT, "logos").await else {
            return HashMap::new();
        };
        let by_id: HashMap<String, String> = parse_market_entries(&body)
            .into_iter()
            .filter_map(|e| {
                let id = e.id?;
                let image = e.image.filter(|s| !s.trim().is_empty())?;
                Some((id, image))
            })
            .collect();
        if by_id.is_empty() {
            return HashMap::new();
        }
        let mut result = HashMap::new();
        for (ticker, id) in &ids {
            if let Some(url) = by_id.get(id) {
                result.insert(ticker.clone(), url.clone());
            }
        }
        result
    }

    /// getHistoricalPricesEur: daily closes from /coins/{id}/market_chart/range.
    pub async fn historical_prices_eur(
        &self,
        ticker: &str,
        from: NaiveDate,
        to: NaiveDate,
    ) -> Vec<(NaiveDate, Decimal)> {
        let zone = self.clock.zone();
        let from_epoch = local_start_of_day_epoch(zone, from);
        let to_epoch = local_start_of_day_epoch(zone, to);
        self.prices_in_range(
            "historical prices",
            ticker,
            from_epoch,
            to_epoch,
            |ms| DateTime::from_timestamp_millis(ms).map(|dt| dt.with_timezone(&zone).date_naive()),
            |d| *d >= from && *d <= to,
        )
        .await
    }

    /// getIntradayPricesEur: hourly prices from /coins/{id}/market_chart/range.
    pub async fn intraday_prices_eur(
        &self,
        ticker: &str,
        from: NaiveDateTime,
        to: NaiveDateTime,
    ) -> Vec<(NaiveDateTime, Decimal)> {
        let zone = self.clock.zone();
        let from_epoch = local_datetime_epoch(zone, from);
        let to_epoch = local_datetime_epoch(zone, to);
        self.prices_in_range(
            "intraday prices",
            ticker,
            from_epoch,
            to_epoch,
            |ms| {
                DateTime::from_timestamp_millis(ms).map(|dt| dt.with_timezone(&zone).naive_local())
            },
            |dt| *dt >= from && *dt <= to,
        )
        .await
    }

    async fn prices_in_range<K: PartialEq>(
        &self,
        operation: &str,
        ticker: &str,
        from_epoch: i64,
        to_epoch: i64,
        key_of: impl Fn(i64) -> Option<K>,
        in_range: impl Fn(&K) -> bool,
    ) -> Vec<(K, Decimal)> {
        let Some(coin_id) = self.coin_id(ticker).await else {
            return Vec::new();
        };
        if self.cooldown.active() {
            return Vec::new();
        }
        let Some((from_epoch, to_epoch)) = clamp_public_lookback(
            from_epoch,
            to_epoch,
            self.clock.now().timestamp(),
            self.has_pro_history_access(),
        ) else {
            tracing::debug!(
                "CoinGecko {operation} for {} is outside the 365-day public API window -- skipping",
                safe(ticker)
            );
            return Vec::new();
        };
        let url = format!(
            "{}/coins/{}/market_chart/range?vs_currency=eur&from={from_epoch}&to={to_epoch}",
            self.base_url,
            percent_encoding::utf8_percent_encode(&coin_id, QUERY_VALUE)
        );
        let Some(body) = self.get_json(&url, HISTORY_TIMEOUT, operation).await else {
            return Vec::new();
        };
        let Some(raw_prices) = body.get("prices").and_then(Value::as_array) else {
            tracing::warn!(
                "CoinGecko returned a non-list 'prices' field for {operation} of {}",
                safe(ticker)
            );
            return Vec::new();
        };
        let mut series = Vec::new();
        let mut skipped = 0usize;
        for entry in raw_prices {
            let Some(pair) = entry.as_array().filter(|p| p.len() >= 2) else {
                skipped += 1;
                continue;
            };
            let (Some(ts), Some(price)) = (json_i64(&pair[0]), json_decimal(&pair[1])) else {
                skipped += 1;
                continue;
            };
            if price <= Decimal::ZERO {
                continue;
            }
            if let Some(key) = key_of(ts)
                && in_range(&key)
            {
                put(&mut series, key, set_scale(price, 8, HALF_UP));
            }
        }
        if skipped > 0 {
            tracing::warn!(
                "CoinGecko returned {skipped} malformed price points (of {}) for {} -- skipped",
                raw_prices.len(),
                safe(ticker)
            );
        }
        series
    }

    /// resolveBySymbol: looks up candidate tickers by symbol on /coins/markets and returns coins
    /// that pass the market-cap-rank dominance rule.
    pub async fn resolve_by_symbol(&self, tickers: &BTreeSet<String>) -> Resolution {
        let wanted: Vec<String> = tickers
            .iter()
            .map(|t| t.trim().to_uppercase())
            .filter(|t| !t.is_empty())
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect();
        if wanted.is_empty() {
            return Resolution {
                accepted: BTreeMap::new(),
                answered: true,
            };
        }
        let deadline = tokio::time::Instant::now() + TIMEOUT;
        let mut accepted = BTreeMap::new();
        for chunk in wanted.chunks(SYMBOL_LOOKUP_BATCH) {
            let Some(found) = self.resolve_group(chunk, deadline).await else {
                return Resolution {
                    accepted,
                    answered: false,
                };
            };
            accepted.extend(found);
        }
        Resolution {
            accepted,
            answered: true,
        }
    }

    async fn resolve_group(
        &self,
        group: &[String],
        deadline: tokio::time::Instant,
    ) -> Option<BTreeMap<String, Coin>> {
        let symbols = group
            .iter()
            .map(|t| t.to_ascii_lowercase())
            .collect::<Vec<_>>()
            .join(",");
        let mut markets = Vec::new();
        for page in 1..=SYMBOL_LOOKUP_MAX_PAGES {
            if self.cooldown.active() {
                return None;
            }
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() {
                tracing::warn!(
                    "CoinGecko symbol lookup ran out of its budget after {} pages -- learning nothing",
                    page - 1
                );
                return None;
            }
            let url = format!(
                "{}/coins/markets?vs_currency=eur&symbols={}&include_tokens=all&order=market_cap_desc&per_page={SYMBOL_LOOKUP_PAGE}&page={page}",
                self.base_url,
                percent_encoding::utf8_percent_encode(&symbols, QUERY_VALUE)
            );
            let body = self.get_json(&url, remaining, "symbol lookup").await?;
            let batch = parse_market_entries(&body);
            let short = batch.len() < SYMBOL_LOOKUP_PAGE;
            let uncapped = ends_uncapped(&batch);
            markets.extend(batch);
            if short || uncapped {
                break;
            }
            if page == SYMBOL_LOOKUP_MAX_PAGES {
                tracing::warn!(
                    "CoinGecko symbol lookup still lists ranked coins after {SYMBOL_LOOKUP_MAX_PAGES} pages -- learning nothing"
                );
                return None;
            }
        }
        let mut accepted = BTreeMap::new();
        for ticker in group {
            let mut ranked: Vec<&MarketEntry> = markets
                .iter()
                .filter(|e| {
                    e.id.is_some()
                        && e.rank.is_some()
                        && e.symbol
                            .as_deref()
                            .is_some_and(|s| s.eq_ignore_ascii_case(ticker))
                })
                .collect();
            ranked.sort_by_key(|e| e.rank.unwrap_or(i64::MAX));
            if let Some(coin) = pick_dominant(ticker, &ranked) {
                accepted.insert(ticker.clone(), coin);
            }
        }
        Some(accepted)
    }

    fn is_pro_host(&self) -> bool {
        url::Url::parse(&self.base_url)
            .ok()
            .and_then(|u| {
                u.host_str()
                    .map(|h| h.eq_ignore_ascii_case("pro-api.coingecko.com"))
            })
            .unwrap_or(false)
    }

    fn has_pro_history_access(&self) -> bool {
        self.api_key.is_some() && self.is_pro_host()
    }

    async fn get_json(&self, url: &str, timeout: Duration, operation: &str) -> Option<Value> {
        let mut req = self
            .http
            .get(url)
            .header("Accept", "application/json")
            .timeout(timeout);
        if let Some(key) = &self.api_key {
            let header = if self.is_pro_host() {
                "x-cg-pro-api-key"
            } else {
                "x-cg-demo-api-key"
            };
            req = req.header(header, key);
        }
        let response = match req.send().await {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!("CoinGecko {operation} failed: {}", transport_message(&e));
                return None;
            }
        };
        if response.status().as_u16() == 429 {
            let retry_after = response
                .headers()
                .get(reqwest::header::RETRY_AFTER)
                .and_then(|v| v.to_str().ok());
            self.cooldown.arm(retry_after);
            tracing::warn!("CoinGecko rate limit hit on {operation} -- cooling down");
            return None;
        }
        if !response.status().is_success() {
            tracing::warn!("CoinGecko {operation} answered {}", response.status());
            return None;
        }
        match response.json::<Value>().await {
            Ok(v) => Some(v),
            Err(_) => {
                tracing::warn!("CoinGecko returned an unreadable body for {operation}");
                None
            }
        }
    }
}

fn parse_market_entries(body: &Value) -> Vec<MarketEntry> {
    body.as_array()
        .map(|arr| {
            arr.iter()
                .map(|entry| MarketEntry {
                    id: entry.get("id").and_then(Value::as_str).map(str::to_owned),
                    image: entry
                        .get("image")
                        .and_then(Value::as_str)
                        .map(str::to_owned),
                    symbol: entry
                        .get("symbol")
                        .and_then(Value::as_str)
                        .map(str::to_owned),
                    name: entry.get("name").and_then(Value::as_str).map(str::to_owned),
                    rank: entry.get("market_cap_rank").and_then(json_i64),
                    market_cap: entry.get("market_cap").and_then(json_decimal),
                })
                .collect()
        })
        .unwrap_or_default()
}

fn ends_uncapped(page: &[MarketEntry]) -> bool {
    match page.last() {
        None => true,
        Some(last) => last.market_cap.is_none_or(|c| c <= Decimal::ZERO),
    }
}

fn pick_dominant(ticker: &str, ranked: &[&MarketEntry]) -> Option<Coin> {
    let top = *ranked.first()?;
    if ranked.len() > 1 {
        let top_cap = top.market_cap;
        let next_cap = ranked[1].market_cap;
        match (top_cap, next_cap) {
            (Some(t), Some(n)) if n > Decimal::ZERO && t >= n * DOMINANCE_RATIO => {}
            (Some(t), Some(n)) if n > Decimal::ZERO => {
                let ratio = divide(t, n, 1, HALF_UP);
                tracing::warn!(
                    "CoinGecko symbol {} is ambiguous: {} coins share it and the best ranked is only {}x the next -- not learning it",
                    safe(ticker),
                    ranked.len(),
                    ratio
                );
                return None;
            }
            _ => {
                tracing::warn!(
                    "CoinGecko symbol {} is ambiguous (missing market cap) -- not learning it",
                    safe(ticker)
                );
                return None;
            }
        }
    }
    let id = top.id.clone()?;
    let name = top
        .name
        .as_deref()
        .filter(|n| !n.trim().is_empty())
        .unwrap_or(ticker)
        .to_owned();
    tracing::info!(
        "CoinGecko symbol {} resolves to {} ({}), market cap rank {:?}",
        safe(ticker),
        safe(&id),
        safe(&name),
        top.rank
    );
    Some(Coin { id, name })
}

fn local_start_of_day_epoch(zone: chrono_tz::Tz, date: NaiveDate) -> i64 {
    let naive = date.and_hms_opt(0, 0, 0).expect("midnight");
    local_datetime_epoch(zone, naive)
}

fn local_datetime_epoch(zone: chrono_tz::Tz, naive: NaiveDateTime) -> i64 {
    zone.from_local_datetime(&naive)
        .earliest()
        .or_else(|| zone.from_local_datetime(&naive).latest())
        .map(|dt| dt.timestamp())
        .unwrap_or_else(|| naive.and_utc().timestamp())
}

/// Clamps `[from_epoch, to_epoch]` to CoinGecko's public v3 365-day lookback window (with a 24 h
/// safety margin) unless a paid Pro API endpoint+key is configured. Returns `None` if the entire
/// requested window falls before the allowed cutoff.
fn clamp_public_lookback(
    from_epoch: i64,
    to_epoch: i64,
    now_epoch: i64,
    has_pro_history: bool,
) -> Option<(i64, i64)> {
    if has_pro_history {
        return (from_epoch <= to_epoch).then_some((from_epoch, to_epoch));
    }
    let min_from = now_epoch.saturating_sub(PUBLIC_HISTORY_MAX_LOOKBACK_SECS);
    let clamped_from = from_epoch.max(min_from);
    (clamped_from <= to_epoch).then_some((clamped_from, to_epoch))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn public_lookback_clamps_twelve_month_backfill_within_365_day_limit() {
        // 2026-11-21 23:00 UTC; midnight 12 months earlier is 365d + 23h ago -> would trigger 401.
        let now = 1_795_302_000_i64;
        let twelve_months_ago_midnight = now - (365 * 86_400 + 23 * 3600);
        let today_midnight = now - 23 * 3600;

        let (from, to) =
            clamp_public_lookback(twelve_months_ago_midnight, today_midnight, now, false).unwrap();
        assert_eq!(from, now - PUBLIC_HISTORY_MAX_LOOKBACK_SECS);
        assert_eq!(to, today_midnight);
        assert!(now - from < 365 * 86_400);

        // Short range inside the window is untouched.
        let week_ago = now - 7 * 86_400;
        assert_eq!(
            clamp_public_lookback(week_ago, now, now, false),
            Some((week_ago, now))
        );

        // Range completely older than 364 days is skipped without an HTTP call.
        assert_eq!(
            clamp_public_lookback(now - 500 * 86_400, now - 400 * 86_400, now, false),
            None
        );

        // Paid Pro access keeps the full requested range.
        assert_eq!(
            clamp_public_lookback(twelve_months_ago_midnight, today_midnight, now, true),
            Some((twelve_months_ago_midnight, today_midnight))
        );
    }
}
