//! CoinGeckoPriceProvider's spot prices: EUR prices of the coins the crypto registry
//! (crypto_asset) maps, from /simple/price. Symbol learning and the price history are not ported.

use std::collections::{BTreeSet, HashMap};
use std::sync::Arc;
use std::time::Duration;

use rust_decimal::Decimal;
use serde_json::Value;
use sqlx::PgPool;

use super::cooldown::YahooCooldown;
use super::num::json_decimal;
use super::yahoo::{QUERY_VALUE, safe, transport_message};

const TIMEOUT: Duration = Duration::from_secs(10);

pub struct CoinGecko {
    http: reqwest::Client,
    base_url: String,
    db: PgPool,
    /// Armed by a 429; while active nothing is asked.
    cooldown: Arc<YahooCooldown>,
}

impl CoinGecko {
    pub fn new(
        http: reqwest::Client,
        base_url: &str,
        db: PgPool,
        cooldown: Arc<YahooCooldown>,
    ) -> Self {
        Self {
            http,
            base_url: base_url.trim_end_matches('/').to_owned(),
            db,
            cooldown,
        }
    }

    /// The CoinGecko ids of the upper-cased tickers the registry knows.
    pub async fn coin_ids(&self, tickers: &BTreeSet<String>) -> HashMap<String, String> {
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
        let response = match self.http.get(url).timeout(TIMEOUT).send().await {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!("CoinGecko spot prices failed: {}", transport_message(&e));
                return HashMap::new();
            }
        };
        if response.status().as_u16() == 429 {
            let retry_after = response
                .headers()
                .get(reqwest::header::RETRY_AFTER)
                .and_then(|v| v.to_str().ok());
            self.cooldown.arm(retry_after);
            tracing::warn!("CoinGecko rate limit hit on spot prices -- cooling down");
            return HashMap::new();
        }
        if !response.status().is_success() {
            tracing::warn!("CoinGecko spot prices answered {}", response.status());
            return HashMap::new();
        }
        let Ok(body) = response.json::<Value>().await else {
            tracing::warn!("CoinGecko returned an unreadable body for spot prices");
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
}
