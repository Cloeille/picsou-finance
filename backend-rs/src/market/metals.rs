//! PreciousMetal, the gold products a PRECIOUS_METALS holding can carry, and their prices
//! (PreciousMetalPriceProvider): the fine gram from the gold future (GC=F) per troy ounce, the
//! coins from the Loomis FXGS quote page.

use std::collections::{BTreeSet, HashMap};
use std::sync::{Arc, LazyLock};
use std::time::Duration;

use chrono::{NaiveDate, NaiveDateTime, Timelike};
use regex::Regex;
use rust_decimal::Decimal;
use serde::Serialize;

use super::yahoo::{YahooFinance, transport_message};
use crate::clock::AppClock;
use crate::ledger::decimal::{HALF_UP, divide};

const QUOTES_PATH: &str = "/fr/or/cotation-produits/";
const TIMEOUT: Duration = Duration::from_secs(15);
const SPOT_SYMBOL: &str = "GC=F";
const GRAMS_PER_TROY_OUNCE: Decimal = Decimal::from_parts(311_034_768, 0, 0, false, 7);
const PRICE_SCALE: u32 = 8;
const MAX_QUOTE_AGE_DAYS: u64 = 7;

static PRODUCT_LINK: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("/produit/([a-z0-9-]+)/").expect("regex"));
static PRICE_CELL: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?s)class="col col_price">(.*?)</div>"#).expect("regex"));
static QUOTE_DATE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"Cotation du\s*<span[^>]*>\s*(\d{2}/\d{2}/\d{4})\s*</span>").expect("regex")
});
static ENTITY_SPACE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new("(?i)&(?:nbsp|#160|#xa0|#8239|#x202f|thinsp|#8201);").expect("regex")
});
static FRENCH_AMOUNT: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^\d+(?:,\d+)?$").expect("regex"));

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Unit {
    Gram,
    Coin,
}

/// (ticker, display name, unit, Loomis FXGS product slug), in registry order.
pub const CATALOGUE: [(&str, &str, Unit, &str); 16] = [
    ("GOLD:GRAM", "Gold (fine gram)", Unit::Gram, ""),
    (
        "GOLD:NAPOLEON-20F",
        "Napoléon 20 F",
        Unit::Coin,
        "20-francs-napoleon",
    ),
    (
        "GOLD:NAPOLEON-10F",
        "Napoléon 10 F",
        Unit::Coin,
        "10-francs-napoleon",
    ),
    (
        "GOLD:SWISS-20F",
        "20 Francs Suisse",
        Unit::Coin,
        "20-francs-suisse",
    ),
    (
        "GOLD:LATIN-UNION-20F",
        "Union latine 20 F",
        Unit::Coin,
        "union-latine",
    ),
    (
        "GOLD:TUNISIA-20F",
        "20 Francs Tunisie",
        Unit::Coin,
        "20-francs-tunisie",
    ),
    ("GOLD:SOVEREIGN", "Souverain", Unit::Coin, "souverain"),
    (
        "GOLD:SOVEREIGN-EII",
        "Souverain Elizabeth II",
        Unit::Coin,
        "souverain-elisabeth-ii",
    ),
    (
        "GOLD:HALF-SOVEREIGN",
        "Demi-souverain",
        Unit::Coin,
        "demi-souverain",
    ),
    (
        "GOLD:KRUGERRAND",
        "Krugerrand",
        Unit::Coin,
        "krugerrand-sud-afrique",
    ),
    ("GOLD:USD-20", "20 Dollars US", Unit::Coin, "20-dollars"),
    ("GOLD:USD-10", "10 Dollars US", Unit::Coin, "10-dollars"),
    ("GOLD:USD-5", "5 Dollars US", Unit::Coin, "5-dollars-us"),
    ("GOLD:PESO-50", "50 Pesos", Unit::Coin, "50-pesos"),
    (
        "GOLD:FLORIN-10",
        "10 Florins",
        Unit::Coin,
        "10-florins-hollandais",
    ),
    (
        "GOLD:REICHSMARK-20",
        "20 Reichsmark",
        Unit::Coin,
        "20-reich-mark",
    ),
];

fn product(ticker: &str) -> Option<&'static (&'static str, &'static str, Unit, &'static str)> {
    let upper = ticker.trim().to_uppercase();
    CATALOGUE.iter().find(|(t, _, _, _)| *t == upper)
}

/// PreciousMetal.fromTicker, case-insensitive.
pub fn supports(ticker: &str) -> bool {
    product(ticker).is_some()
}

/// PreciousMetalProductResponse.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ProductResponse {
    pub ticker: &'static str,
    pub name: &'static str,
    pub unit: Unit,
}

pub fn products() -> Vec<ProductResponse> {
    CATALOGUE
        .iter()
        .map(|(ticker, name, unit, _)| ProductResponse {
            ticker,
            name,
            unit: *unit,
        })
        .collect()
}

fn per_gram(per_ounce: Decimal) -> Decimal {
    divide(per_ounce, GRAMS_PER_TROY_OUNCE, PRICE_SCALE, HALF_UP)
}

/// A French price cell ("1 234,56 €") as a positive amount.
pub fn parse_french_amount(cell: &str) -> Option<Decimal> {
    let text: String = ENTITY_SPACE
        .replace_all(cell, "")
        .chars()
        .filter(|c| !c.is_whitespace() && !matches!(c, '\u{00A0}' | '\u{202F}' | '\u{2009}' | '€'))
        .collect();
    if !FRENCH_AMOUNT.is_match(&text) {
        return None;
    }
    text.replace(',', ".")
        .parse::<Decimal>()
        .ok()
        .filter(|a| *a > Decimal::ZERO)
}

/// The quote page's prices by product slug; a quote older than a week is ignored.
pub fn parse_quotes(html: &str, today: NaiveDate) -> HashMap<String, Decimal> {
    let mut quotes = HashMap::new();
    let mut any_priced = false;
    let oldest = today - chrono::Days::new(MAX_QUOTE_AGE_DAYS);
    for row in html.split("<div class=\"raterow\">").skip(1) {
        let (Some(link), Some(price)) = (PRODUCT_LINK.captures(row), PRICE_CELL.captures(row))
        else {
            continue;
        };
        let Some(amount) = parse_french_amount(&price[1]) else {
            continue;
        };
        any_priced = true;
        let quoted = QUOTE_DATE
            .captures(row)
            .and_then(|c| NaiveDate::parse_from_str(&c[1], "%d/%m/%Y").ok());
        if quoted.is_some_and(|q| q < oldest) {
            tracing::warn!(
                "Loomis FXGS quote for {} is older than {MAX_QUOTE_AGE_DAYS} days: ignoring it",
                &link[1]
            );
            continue;
        }
        quotes.entry(link[1].to_owned()).or_insert(amount);
    }
    if !any_priced {
        tracing::warn!(
            "Loomis FXGS quote page has no readable quote: markup change or maintenance page?"
        );
    }
    quotes
}

pub struct PreciousMetals {
    http: reqwest::Client,
    base_url: String,
    yahoo: Arc<YahooFinance>,
    clock: AppClock,
}

impl PreciousMetals {
    pub fn new(
        http: reqwest::Client,
        base_url: &str,
        yahoo: Arc<YahooFinance>,
        clock: AppClock,
    ) -> Self {
        Self {
            http,
            base_url: base_url.trim_end_matches('/').to_owned(),
            yahoo,
            clock,
        }
    }

    async fn quotes_page(&self) -> Option<String> {
        let response = self
            .http
            .get(format!("{}{QUOTES_PATH}", self.base_url))
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept-Language", "fr-FR")
            .timeout(TIMEOUT)
            .send()
            .await;
        match response {
            Ok(r) if r.status().is_success() => {
                r.text().await.ok().filter(|b| !b.trim().is_empty())
            }
            Ok(r) => {
                tracing::warn!(
                    "Loomis FXGS quote page fetch failed: HTTP {}",
                    r.status().as_u16()
                );
                None
            }
            Err(e) => {
                tracing::warn!(
                    "Loomis FXGS quote page fetch failed: {}",
                    transport_message(&e)
                );
                None
            }
        }
    }

    /// getPricesEur, keyed by the strings in `tickers` as received (matching Java).
    pub async fn prices_eur(&self, tickers: &BTreeSet<String>) -> HashMap<String, Decimal> {
        let products: Vec<_> = tickers.iter().filter_map(|t| product(t)).collect();
        let mut by_canonical = HashMap::new();
        if products.iter().any(|p| p.2 == Unit::Gram) {
            let spot = self
                .yahoo
                .prices_eur(&BTreeSet::from([SPOT_SYMBOL.to_owned()]))
                .await;
            if let Some(ounce) = spot.get(SPOT_SYMBOL).filter(|o| **o > Decimal::ZERO) {
                by_canonical.insert("GOLD:GRAM", per_gram(*ounce));
            }
        }
        if products.iter().any(|p| p.2 == Unit::Coin) {
            let by_slug = match self.quotes_page().await {
                Some(html) => parse_quotes(&html, self.clock.today()),
                None => HashMap::new(),
            };
            for (ticker, _, unit, slug) in &products {
                if *unit == Unit::Coin
                    && let Some(price) = by_slug.get(*slug)
                {
                    by_canonical.insert(*ticker, *price);
                }
            }
        }
        let mut result = HashMap::new();
        for ticker in tickers {
            if let Some(p) = product(ticker)
                && let Some(price) = by_canonical.get(p.0)
            {
                result.insert(ticker.clone(), *price);
            }
        }
        result
    }

    /// The gram's daily series from the gold future; the coins have none.
    pub async fn historical_prices_eur(
        &self,
        ticker: &str,
        from: NaiveDate,
        to: NaiveDate,
    ) -> Vec<(NaiveDate, Decimal)> {
        if product(ticker).is_none_or(|p| p.2 != Unit::Gram) {
            return Vec::new();
        }
        self.yahoo
            .historical_prices_eur(SPOT_SYMBOL, from, to)
            .await
            .into_iter()
            .filter(|(_, v)| *v > Decimal::ZERO)
            .map(|(d, v)| (d, per_gram(v)))
            .collect()
    }

    /// The gram hour by hour from the future; a coin's single quote repeated on every hour.
    pub async fn intraday_prices_eur(
        &self,
        ticker: &str,
        from: NaiveDateTime,
        to: NaiveDateTime,
    ) -> Vec<(NaiveDateTime, Decimal)> {
        let Some((canonical, _, unit, _)) = product(ticker) else {
            return Vec::new();
        };
        if *unit == Unit::Coin {
            let Some(quote) = self
                .prices_eur(&BTreeSet::from([(*canonical).to_owned()]))
                .await
                .remove(*canonical)
            else {
                return Vec::new();
            };
            let mut series = Vec::new();
            let mut hour = from
                .with_minute(0)
                .and_then(|h| h.with_second(0))
                .and_then(|h| h.with_nanosecond(0))
                .unwrap_or(from);
            while hour <= to {
                series.push((hour, quote));
                hour += chrono::Duration::hours(1);
            }
            return series;
        }
        self.yahoo
            .intraday_prices_eur(SPOT_SYMBOL, from, to)
            .await
            .into_iter()
            .filter(|(_, v)| *v > Decimal::ZERO)
            .map(|(at, v)| (at, per_gram(v)))
            .collect()
    }
}
