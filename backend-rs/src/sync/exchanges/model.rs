//! The exchange port's vocabulary (CryptoExchangePort, ExchangeType) and the rows and
//! responses of the exchange connections.

use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;
use serde_with::skip_serializing_none;
use sqlx::FromRow;

/// ExchangeType, stored and serialized by name. KRAKEN has no adapter in Java either.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize)]
pub enum ExchangeType {
    #[serde(rename = "BINANCE")]
    Binance,
    #[serde(rename = "KRAKEN")]
    Kraken,
    #[serde(rename = "MERIA")]
    Meria,
}

impl ExchangeType {
    /// In declaration order: Jackson also reads an enum from its ordinal.
    pub const ALL: [ExchangeType; 3] = [Self::Binance, Self::Kraken, Self::Meria];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Binance => "BINANCE",
            Self::Kraken => "KRAKEN",
            Self::Meria => "MERIA",
        }
    }

    pub fn parse(name: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|t| t.as_str() == name)
    }

    /// The account key of the exchange's single account per member.
    pub fn external_id(self) -> String {
        format!("crypto_exchange_{}", self.as_str().to_ascii_lowercase())
    }
}

impl std::fmt::Display for ExchangeType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

/// Jackson's enum reading: the exact constant name, or its ordinal as a JSON integer.
impl<'de> Deserialize<'de> for ExchangeType {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        use serde::de::Error;
        match Value::deserialize(deserializer)? {
            Value::String(name) => {
                Self::parse(&name).ok_or_else(|| D::Error::custom("unknown exchange type"))
            }
            Value::Number(n) => n
                .as_u64()
                .and_then(|i| Self::ALL.get(usize::try_from(i).ok()?).copied())
                .ok_or_else(|| D::Error::custom("unknown exchange type ordinal")),
            _ => Err(D::Error::custom("not an exchange type")),
        }
    }
}

/// CryptoExchangePort.Product, persisted by name.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Product {
    Spot,
    Staking,
    Lending,
}

impl Product {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Spot => "SPOT",
            Self::Staking => "STAKING",
            Self::Lending => "LENDING",
        }
    }

    pub fn parse(name: &str) -> Option<Self> {
        [Self::Spot, Self::Staking, Self::Lending]
            .into_iter()
            .find(|p| p.as_str() == name)
    }
}

/// An API key with the credentials the adapter requires; secret and passphrase are None exactly
/// when the adapter does not use them. Debug never shows them.
#[derive(Clone, PartialEq, Eq)]
pub struct ExchangeCredentials {
    pub api_key: String,
    pub api_secret: Option<String>,
    pub api_passphrase: Option<String>,
    pub region: Option<String>,
}

impl std::fmt::Debug for ExchangeCredentials {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("ExchangeCredentials[redacted]")
    }
}

/// One asset held under one product. interest is a decomposition of quantity (principal +
/// interest = quantity), never an addition; both are None when the exchange does not split it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExchangePosition {
    pub product: Product,
    pub symbol: String,
    pub quantity: Decimal,
    pub principal: Option<Decimal>,
    pub interest: Option<Decimal>,
}

impl ExchangePosition {
    pub fn spot(symbol: impl Into<String>, quantity: Decimal) -> Self {
        Self {
            product: Product::Spot,
            symbol: symbol.into(),
            quantity,
            principal: None,
            interest: None,
        }
    }
}

/// A crypto_exchange_session row; the credentials are AES-GCM ciphertexts.
#[derive(Debug, Clone, FromRow)]
pub struct SessionRow {
    pub id: i64,
    pub member_id: i64,
    pub exchange_type: String,
    pub api_key: String,
    pub api_secret: Option<String>,
    pub api_passphrase: Option<String>,
    pub region: Option<String>,
    pub status: String,
    pub last_synced_at: Option<DateTime<Utc>>,
}

/// A crypto_exchange_position row.
#[derive(Debug, Clone, FromRow)]
pub struct PositionRow {
    pub product: String,
    pub ticker: String,
    pub quantity: Decimal,
    pub principal: Option<Decimal>,
    pub interest: Option<Decimal>,
}

/// CryptoExchangeSyncService.ExchangeStatusResponse.
#[skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExchangeStatusResponse {
    pub id: i64,
    pub exchange_type: String,
    pub status: String,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub region: Option<String>,
}

/// ExchangePositionResponse: one line of an exchange account's per-product breakdown.
#[skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExchangePositionResponse {
    pub product: String,
    pub ticker: String,
    pub logo_url: Option<String>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub quantity: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub principal: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub interest: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub average_buy_in: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub current_price_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub current_value_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub cost_basis_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub pnl_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub pnl_percent: Option<Decimal>,
    pub price_as_of: Option<NaiveDate>,
    pub price_stale: bool,
}
