//! BinanceAdapter: GET /api/v3/account signed with HMAC-SHA256 over the query string. Spot
//! only: that endpoint does not cover Binance Earn.

use std::time::Duration;

use async_trait::async_trait;
use hmac::{Hmac, KeyInit, Mac};
use rust_decimal::Decimal;
use serde_json::Value;
use sha2::Sha256;

use super::model::{ExchangeCredentials, ExchangePosition};
use super::port::{
    ExchangePort, FetchError, describe, is_blank, is_json, parse_big_decimal, scalar_text,
};
use crate::AppState;
use crate::clock::AppClock;
use crate::native_sidecars::common::{ReadBodyError, read_capped_bytes};

const TIMEOUT: Duration = Duration::from_secs(10);
const ACCOUNT_PATH: &str = "/api/v3/account";
const MAX_RESPONSE_BYTES: usize = 256 * 1024;

pub struct Binance<'a> {
    http: &'a reqwest::Client,
    base_url: &'a str,
    clock: &'a AppClock,
}

impl<'a> Binance<'a> {
    pub fn new(state: &'a AppState) -> Self {
        Self {
            http: &state.http,
            base_url: state.config.sync_url("binance"),
            clock: &state.clock,
        }
    }

    /// The signed account read: Ok(None) for an empty body.
    async fn account(
        &self,
        credentials: &ExchangeCredentials,
    ) -> Result<Option<Value>, FetchError> {
        let secret = credentials.api_secret.as_deref().ok_or_else(|| {
            FetchError::Unexpected("HMAC-SHA256 signing failed: no API secret".into())
        })?;
        let query = format!("timestamp={}", self.clock.now().timestamp_millis());
        let signature = sign(secret, &query);
        let url = format!(
            "{}{ACCOUNT_PATH}?{query}&signature={signature}",
            self.base_url.trim_end_matches('/')
        );
        let response = self
            .http
            .get(url)
            .header("X-MBX-APIKEY", &credentials.api_key)
            .timeout(TIMEOUT)
            .send()
            .await
            .map_err(|e| FetchError::Unexpected(describe(&e)))?;
        let status = response.status();
        if !status.is_success() {
            return Err(FetchError::Unexpected(format!(
                "{status} from GET {ACCOUNT_PATH}"
            )));
        }
        let json = is_json(&response);
        let body = read_capped_bytes(response, MAX_RESPONSE_BYTES)
            .await
            .map_err(|e| match e {
                ReadBodyError::Transport(err) => FetchError::Unexpected(describe(&err)),
                ReadBodyError::TooLarge { .. } => {
                    FetchError::Unexpected(format!("unreadable body from GET {ACCOUNT_PATH}"))
                }
            })?;
        if is_blank(&body) {
            return Ok(None);
        }
        if !json {
            return Err(FetchError::Unexpected(format!(
                "unsupported content type from GET {ACCOUNT_PATH}"
            )));
        }
        serde_json::from_slice(&body)
            .map(Some)
            .map_err(|_| FetchError::Unexpected(format!("unreadable body from GET {ACCOUNT_PATH}")))
    }
}

/// HMAC-SHA256 of data under the secret, lower-case hex.
pub fn sign(secret: &str, data: &str) -> String {
    let mut mac =
        Hmac::<Sha256>::new_from_slice(secret.as_bytes()).expect("HMAC takes any key length");
    mac.update(data.as_bytes());
    hex::encode(mac.finalize().into_bytes())
}

/// JsonNode.asText(default): a missing or null node reads as the default, a container as "".
fn as_text(node: Option<&Value>, default: &str) -> String {
    match node {
        None | Some(Value::Null) => default.to_owned(),
        Some(value) => scalar_text(value).ok().flatten().unwrap_or_default(),
    }
}

fn amount(node: Option<&Value>) -> Result<Decimal, FetchError> {
    let text = as_text(node, "0");
    parse_big_decimal(&text).ok_or_else(|| {
        FetchError::Unexpected("Binance returned a balance that is not a number".into())
    })
}

#[async_trait]
impl ExchangePort for Binance<'_> {
    async fn fetch_positions(
        &self,
        credentials: &ExchangeCredentials,
    ) -> Result<Vec<ExchangePosition>, FetchError> {
        // Never an empty list for an unreadable response: "no body" read as "no coins" would
        // write a EUR 0 cliff into the net-worth history.
        let Some(response) = self.account(credentials).await? else {
            return Err(FetchError::Sync(format!(
                "Binance returned an empty response for {ACCOUNT_PATH}."
            )));
        };
        let mut holdings = Vec::new();
        if let Some(Value::Array(balances)) = response.get("balances") {
            for balance in balances {
                let asset = as_text(balance.get("asset"), "");
                let total = amount(balance.get("free"))? + amount(balance.get("locked"))?;
                if total > Decimal::ZERO {
                    holdings.push(ExchangePosition::spot(asset.to_uppercase(), total));
                }
            }
        }
        tracing::info!("Binance: fetched {} non-zero holdings", holdings.len());
        Ok(holdings)
    }

    async fn test_connection(&self, credentials: &ExchangeCredentials) -> Result<bool, FetchError> {
        match self.account(credentials).await {
            Ok(_) => Ok(true),
            Err(FetchError::Sync(message) | FetchError::Unexpected(message)) => {
                tracing::warn!("Binance connection test failed: {message}");
                Ok(false)
            }
            Err(error @ FetchError::Coded { .. }) => Err(error),
        }
    }
}
