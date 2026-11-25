//! OpenFigiIsinConverter: an ISIN to the Yahoo symbol that quotes it, through OpenFIGI's mapping
//! API, verified against Yahoo (falling back to Yahoo's own search), cached for the process.
//! A miss that follows a failed OpenFIGI call is not cached, so a later lookup asks again.
//! Trade Republic's crypto pseudo-ISINs (XF000BTC0017) map to the registry's coin.

use std::collections::HashMap;
use std::sync::{Arc, LazyLock, Mutex};
use std::time::{Duration, Instant};

use regex::Regex;
use serde_json::{Value, json};

use super::coingecko::CoinGecko;
use super::yahoo::{YahooFinance, safe, transport_message};
use crate::ledger::instrument::TickerResult;

const TIMEOUT: Duration = Duration::from_secs(5);
const MAX_VERIFIED_CANDIDATES: usize = 3;
const VERIFY_BUDGET: Duration = Duration::from_secs(10);

static ISIN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Z]{2}[A-Z0-9]{9}[A-Z0-9]$").expect("regex"));
static SYMBOL: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Z0-9][A-Z0-9.-]{0,14}$").expect("regex"));
static TR_CRYPTO: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^XF000([A-Z]+)[0-9]+$").expect("regex"));

/// OpenFIGI exchange code to Yahoo suffix.
const EXCHANGE_SUFFIX: &[(&str, &str)] = &[
    ("US", ""),
    ("UN", ""),
    ("UA", ""),
    ("UC", ""),
    ("UD", ""),
    ("UW", ""),
    ("NQ", ""),
    ("NY", ""),
    ("OQ", ""),
    ("PK", ""),
    ("PQ", ""),
    ("GR", ".DE"),
    ("GF", ".DE"),
    ("GD", ".DE"),
    ("GY", ".DE"),
    ("GS", ".DE"),
    ("GM", ".DE"),
    ("GT", ".DE"),
    ("GI", ".DE"),
    ("GH", ".DE"),
    ("GZ", ".DE"),
    ("TH", ".DE"),
    ("QT", ".DE"),
    ("FP", ".PA"),
    ("PA", ".PA"),
    ("NA", ".AS"),
    ("LN", ".L"),
    ("IM", ".MI"),
    ("BR", ".BR"),
    ("SW", ".SW"),
    ("SZ", ".SW"),
    ("SM", ".MC"),
    ("TO", ".TO"),
    ("TV", ".TO"),
    ("JT", ".T"),
    ("HK", ".HK"),
    ("AU", ".AX"),
    ("SG", ".SI"),
    ("IN", ".NS"),
    ("KS", ".KS"),
];
const EU_PREFERRED: [&str; 8] = ["NA", "FP", "GY", "GR", "GF", "LN", "IM", "BR"];
/// ISIN country to the exchange that lists it at home.
const HOME_EXCHANGE: &[(&str, &str)] = &[
    ("US", "US"),
    ("HK", "HK"),
    ("JP", "JT"),
    ("AU", "AU"),
    ("SG", "SG"),
    ("IN", "IN"),
    ("KR", "KS"),
    ("GB", "LN"),
    ("DE", "GY"),
    ("FR", "FP"),
    ("NL", "NA"),
    ("IT", "IM"),
    ("CH", "SW"),
    ("ES", "SM"),
    ("CA", "TO"),
];

pub fn is_isin(value: &str) -> bool {
    let upper = value.trim().to_uppercase();
    upper.chars().count() == 12 && ISIN.is_match(&upper)
}

pub fn is_tr_crypto_isin(value: &str) -> bool {
    !value.trim().is_empty() && TR_CRYPTO.is_match(&value.trim().to_uppercase())
}

pub struct OpenFigi {
    http: reqwest::Client,
    base_url: String,
    yahoo: Arc<YahooFinance>,
    coingecko: Arc<CoinGecko>,
    cache: Mutex<HashMap<String, TickerResult>>,
}

impl OpenFigi {
    pub fn new(
        http: reqwest::Client,
        base_url: &str,
        yahoo: Arc<YahooFinance>,
        coingecko: Arc<CoinGecko>,
    ) -> Self {
        Self {
            http,
            base_url: base_url.trim_end_matches('/').to_owned(),
            yahoo,
            coingecko,
            cache: Mutex::new(HashMap::new()),
        }
    }

    /// resolve: the quotable ticker and its name, or the ISIN itself when nothing quotes it.
    pub async fn resolve(&self, isin: &str) -> TickerResult {
        if isin.trim().is_empty() {
            return TickerResult {
                ticker: isin.to_owned(),
                name: None,
            };
        }
        let normalized = isin.trim().to_uppercase();
        if let Some(cached) = self
            .cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&normalized)
        {
            return cached.clone();
        }
        if let Some(symbol) = TR_CRYPTO.captures(&normalized).map(|c| c[1].to_owned()) {
            if let Some(name) = self.coingecko.display_name(&symbol).await {
                let result = TickerResult {
                    ticker: symbol,
                    name: Some(name),
                };
                self.remember(&normalized, &result);
                return result;
            }
            tracing::warn!(
                "TR-native crypto ISIN {normalized} has an unknown symbol, falling back to OpenFIGI"
            );
        }
        let fetched = self.fetch(&normalized).await;
        let failed = fetched.is_err();
        let figi = fetched.unwrap_or(None);
        let result = match self.priceable(&normalized, figi.clone()).await {
            Some(result) => result,
            None if failed => {
                // OpenFIGI did not answer: keep no verdict, the next lookup asks again.
                return TickerResult {
                    ticker: normalized,
                    name: None,
                };
            }
            None => {
                tracing::warn!(
                    "No Yahoo-quotable ticker for ISIN {normalized} (OpenFIGI: {}), will use ISIN as-is",
                    figi.as_ref()
                        .map_or("no result".to_owned(), |f| f.ticker.clone())
                );
                TickerResult {
                    ticker: normalized.clone(),
                    name: None,
                }
            }
        };
        self.remember(&normalized, &result);
        result
    }

    /// resolveIsinOrSymbol: the provider's own symbol and name when no ISIN is known.
    pub async fn resolve_isin_or_symbol(
        &self,
        isin: Option<&str>,
        symbol: &str,
        provider_name: Option<&str>,
    ) -> TickerResult {
        match isin.filter(|i| !i.trim().is_empty()) {
            None => TickerResult {
                ticker: symbol.to_owned(),
                name: provider_name.map(str::to_owned),
            },
            Some(isin) => {
                let resolved = self.resolve(isin).await;
                TickerResult {
                    ticker: resolved.ticker,
                    name: resolved.name.or_else(|| provider_name.map(str::to_owned)),
                }
            }
        }
    }

    fn remember(&self, isin: &str, result: &TickerResult) {
        self.cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(isin.to_owned(), result.clone());
    }

    /// OpenFIGI's pick if Yahoo quotes it, else the first of Yahoo's own matches that quotes.
    async fn priceable(&self, isin: &str, figi: Option<TickerResult>) -> Option<TickerResult> {
        let deadline = Instant::now() + VERIFY_BUDGET;
        if let Some(f) = &figi
            && self.yahoo.has_quote(&f.ticker).await
        {
            return figi;
        }
        let mut probed = 0;
        for candidate in self.yahoo.search_symbols(isin).await {
            if figi.as_ref().is_some_and(|f| f.ticker == candidate.symbol) {
                continue;
            }
            if probed == MAX_VERIFIED_CANDIDATES || Instant::now() > deadline {
                break;
            }
            probed += 1;
            if self.yahoo.has_quote(&candidate.symbol).await {
                let name = figi
                    .as_ref()
                    .and_then(|f| f.name.clone())
                    .or(candidate.name);
                return Some(TickerResult {
                    ticker: candidate.symbol,
                    name,
                });
            }
        }
        figi
    }

    /// OpenFIGI's listing for `isin`: `Ok(None)` when it answered without one, `Err` when the
    /// call failed (transport error, timeout, non-2xx status, unreadable body).
    async fn fetch(&self, isin: &str) -> Result<Option<TickerResult>, LookupFailed> {
        let response = self
            .http
            .post(format!("{}/v3/mapping", self.base_url))
            .header("Accept", "application/json")
            .json(&json!([{"idType": "ID_ISIN", "idValue": isin}]))
            .timeout(TIMEOUT)
            .send()
            .await;
        let response = match response {
            Ok(r) if r.status().is_success() => r,
            Ok(r) => {
                tracing::warn!(
                    "OpenFIGI API request failed for ISIN {isin}: HTTP {}",
                    r.status().as_u16()
                );
                return Err(LookupFailed);
            }
            Err(e) => {
                tracing::warn!(
                    "OpenFIGI API request failed for ISIN {isin}: {}",
                    transport_message(&e)
                );
                return Err(LookupFailed);
            }
        };
        let body: Value = response.json().await.map_err(|e| {
            tracing::warn!(
                "OpenFIGI API answer unreadable for ISIN {isin}: {}",
                transport_message(&e)
            );
            LookupFailed
        })?;
        let Some(first) = body.as_array().and_then(|a| a.first()) else {
            return Ok(None);
        };
        if let Some(error) = first.get("error") {
            tracing::warn!(
                "OpenFIGI error for ISIN {isin}: {}",
                safe(&error.to_string())
            );
            return Ok(None);
        }
        Ok(first
            .get("data")
            .and_then(Value::as_array)
            .and_then(|data| pick_best(isin, data)))
    }
}

/// An OpenFIGI call that did not get an answer.
struct LookupFailed;

fn symbol_of(entry: &Value) -> Option<String> {
    let normalized = entry.get("ticker")?.as_str()?.trim().to_uppercase();
    SYMBOL.is_match(&normalized).then_some(normalized)
}

/// The listing to quote: the home exchange, else a US one, else a preferred European one, else
/// the first mapped exchange, else any ticker.
pub fn pick_best(isin: &str, entries: &[Value]) -> Option<TickerResult> {
    let mut by_exchange: Vec<(&str, TickerResult)> = Vec::new();
    for entry in entries {
        let (Some(ticker), Some(exchange)) = (
            symbol_of(entry),
            entry.get("exchCode").and_then(Value::as_str),
        ) else {
            continue;
        };
        let name = entry.get("name").and_then(Value::as_str).map(str::to_owned);
        if let Some((code, suffix)) = EXCHANGE_SUFFIX.iter().find(|(c, _)| *c == exchange)
            && !by_exchange.iter().any(|(c, _)| c == code)
        {
            by_exchange.push((
                code,
                TickerResult {
                    ticker: format!("{ticker}{suffix}"),
                    name,
                },
            ));
        }
    }
    let find = |code: &str| {
        by_exchange
            .iter()
            .find(|(c, _)| *c == code)
            .map(|(_, r)| r.clone())
    };
    let country: String = isin.chars().take(2).collect::<String>().to_uppercase();
    if let Some((_, home)) = HOME_EXCHANGE.iter().find(|(c, _)| *c == country)
        && let Some(found) = find(home)
    {
        return Some(found);
    }
    if country != "US"
        && let Some(found) = ["US", "NY", "NQ", "OQ", "PQ"].into_iter().find_map(find)
    {
        return Some(found);
    }
    if let Some(found) = EU_PREFERRED.into_iter().find_map(find) {
        return Some(found);
    }
    if let Some((_, first)) = by_exchange.first() {
        return Some(first.clone());
    }
    entries.iter().find_map(|entry| {
        symbol_of(entry).map(|ticker| TickerResult {
            ticker,
            name: entry.get("name").and_then(Value::as_str).map(str::to_owned),
        })
    })
}
