//! OKX balance reads for trading, funding, Simple Earn, On-chain Earn and ETH/SOL staking.

use std::collections::{BTreeMap, HashSet};
use std::time::Duration;

use async_trait::async_trait;
use base64::{Engine, engine::general_purpose::STANDARD};
use chrono::SecondsFormat;
use hmac::{Hmac, KeyInit, Mac};
use reqwest::Url;
use reqwest::header::{HeaderMap, HeaderValue};
use rust_decimal::Decimal;
use serde::de::{self, Deserialize, Deserializer, MapAccess, SeqAccess, Visitor};
use serde_json::{Map, Value};
use sha2::Sha256;

use super::model::{ExchangeCredentials, ExchangePosition, Product};
use super::port::{ExchangePort, FetchError};
use crate::AppState;
use crate::clock::AppClock;
use crate::config::Config;
use crate::ledger::sync_values::{java_is_blank, java_strip};
use crate::native_sidecars::common::{ReadBodyError, read_capped_bytes};

const TIMEOUT: Duration = Duration::from_secs(10);
const MAX_RESPONSE_BYTES: usize = 256 * 1024;
const REGIONS: &[&str] = &["GLOBAL", "EEA", "US"];
const TRADING: &str = "/api/v5/account/balance";
const FUNDING: &str = "/api/v5/asset/balances";
const SAVINGS: &str = "/api/v5/finance/savings/balance";
const ORDERS: &str = "/api/v5/finance/staking-defi/orders-active";

struct Receipt {
    path: &'static str,
    symbol: &'static str,
    underlying: &'static str,
}

const RECEIPTS: [Receipt; 2] = [
    Receipt {
        path: "/api/v5/finance/staking-defi/eth/balance",
        symbol: "BETH",
        underlying: "ETH",
    },
    Receipt {
        path: "/api/v5/finance/staking-defi/sol/balance",
        symbol: "OKSOL",
        underlying: "SOL",
    },
];

type Balances = BTreeMap<String, Decimal>;

pub(super) fn http_client() -> reqwest::Client {
    // Custom authentication headers must never be forwarded to a redirect target.
    reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .retry(reqwest::retry::never())
        .user_agent("Picsou")
        .timeout(TIMEOUT)
        .build()
        .expect("the OKX HTTP client builds with the default TLS backend")
}

pub struct Okx<'a> {
    http: &'a reqwest::Client,
    config: &'a Config,
    clock: &'a AppClock,
}

impl<'a> Okx<'a> {
    pub fn new(state: &'a AppState) -> Self {
        Self {
            http: &state.sync.exchanges.okx_http,
            config: &state.config,
            clock: &state.clock,
        }
    }

    async fn fetch(
        &self,
        base_url: &Url,
        path: &str,
        credentials: &ExchangeCredentials,
    ) -> Result<Vec<Value>, FetchError> {
        let url = format!("{}{path}", base_url.as_str().trim_end_matches('/'));
        let response = self
            .http
            .get(url)
            .headers(signed_headers(self.clock, credentials, path)?)
            .send()
            .await
            .map_err(|e| transport_failure(path, &e))?;
        let status = response.status().as_u16();
        let body = read_capped_bytes(response, MAX_RESPONSE_BYTES)
            .await
            .map_err(|err| match err {
                ReadBodyError::Transport(e) => transport_failure(path, &e),
                ReadBodyError::TooLarge { .. } => unexpected_client_failure(path, "body too large"),
            })?;
        parse_envelope(path, status, &body)
    }
}

fn host_for_region<'a>(config: &'a Config, region: Option<&str>) -> Result<&'a str, FetchError> {
    match region {
        Some("GLOBAL") => Ok(&config.okx_global_base_url),
        Some("EEA") => Ok(&config.okx_eea_base_url),
        Some("US") => Ok(&config.okx_us_base_url),
        _ => Err(FetchError::Sync(
            "Missing or unsupported OKX region.".into(),
        )),
    }
}

#[async_trait]
impl ExchangePort for Okx<'_> {
    fn requires_api_passphrase(&self) -> bool {
        true
    }

    fn regions(&self) -> &'static [&'static str] {
        REGIONS
    }

    async fn test_connection(&self, credentials: &ExchangeCredentials) -> Result<bool, FetchError> {
        // Refuse an account we cannot value before the service saves the connection.
        self.fetch_positions(credentials).await.map(|_| true)
    }

    async fn fetch_positions(
        &self,
        credentials: &ExchangeCredentials,
    ) -> Result<Vec<ExchangePosition>, FetchError> {
        let host = host_for_region(self.config, credentials.region.as_deref())?;
        let base_url = Url::parse(host)
            .map_err(|_| unexpected_client_failure(TRADING, "invalid request URL"))?;
        let mut spot = trading_balances(&self.fetch(&base_url, TRADING, credentials).await?)?;
        for (currency, quantity) in currency_balances(
            &self.fetch(&base_url, FUNDING, credentials).await?,
            "bal",
            FUNDING,
        )? {
            add_balance(&mut spot, currency, quantity, FUNDING)?;
        }
        let lending = currency_balances(
            &self.fetch(&base_url, SAVINGS, credentials).await?,
            "amt",
            SAVINGS,
        )?;
        let mut staking = order_balances(&self.fetch(&base_url, ORDERS, credentials).await?)?;
        for receipt in &RECEIPTS {
            receipt.check_earn(&lending, &staking)?;
            let rows = self.fetch(&base_url, receipt.path, credentials).await?;
            receipt.reconcile(&rows, &mut spot, &mut staking)?;
        }
        let positions = positions(spot, staking, lending)?;
        tracing::info!("OKX: fetched {} non-zero positions", positions.len());
        Ok(positions)
    }
}

fn signed_headers(
    clock: &AppClock,
    credentials: &ExchangeCredentials,
    path: &str,
) -> Result<HeaderMap, FetchError> {
    let secret = credentials
        .api_secret
        .as_deref()
        .filter(|secret| !secret.is_empty())
        .ok_or_else(|| unexpected_client_failure(path, "missing API secret"))?;
    let passphrase = credentials
        .api_passphrase
        .as_deref()
        .ok_or_else(|| unexpected_client_failure(path, "missing API passphrase"))?;
    let timestamp = clock.now().to_rfc3339_opts(SecondsFormat::Millis, true);
    let mut mac =
        Hmac::<Sha256>::new_from_slice(secret.as_bytes()).expect("HMAC takes any key length");
    mac.update(format!("{timestamp}GET{path}").as_bytes());
    let signature = STANDARD.encode(mac.finalize().into_bytes());
    let mut headers = HeaderMap::new();
    headers.insert("accept", HeaderValue::from_static("application/json"));
    for (name, value) in [
        ("ok-access-key", credentials.api_key.as_str()),
        ("ok-access-sign", &signature),
        ("ok-access-timestamp", &timestamp),
        ("ok-access-passphrase", passphrase),
    ] {
        let mut value = HeaderValue::from_str(value)
            .map_err(|_| unexpected_client_failure(path, "invalid header value"))?;
        value.set_sensitive(true);
        headers.insert(name, value);
    }
    Ok(headers)
}

fn transport_failure(path: &str, error: &reqwest::Error) -> FetchError {
    if error.is_timeout() {
        return FetchError::Sync(format!("OKX request for {path} timed out."));
    }
    if error.is_connect() || error.is_request() {
        return FetchError::Sync(format!("Could not reach OKX for {path}."));
    }
    let kind = if error.is_builder() {
        "request builder"
    } else if error.is_body() || error.is_decode() {
        "response body"
    } else {
        "client"
    };
    unexpected_client_failure(path, kind)
}

fn unexpected_client_failure(path: &str, kind: &str) -> FetchError {
    // Client error sources can contain credentials. Pass only a local category to the service.
    FetchError::Unexpected(format!("OKX request for {path} failed with {kind}"))
}

fn parse_envelope(path: &str, status: u16, body: &[u8]) -> Result<Vec<Value>, FetchError> {
    let body = body.strip_prefix(&[0xEF, 0xBB, 0xBF]).unwrap_or(body);
    let StrictJson(mut envelope) = serde_json::from_slice(body).map_err(|_| {
        FetchError::Sync(format!(
            "OKX returned unreadable JSON for {path} (HTTP {status})."
        ))
    })?;
    let code = envelope.get("code").and_then(Value::as_str);
    if let Some(code) = code.filter(|code| *code != "0") {
        return Err(api_failure(path, status, code));
    }
    if status != 200 {
        return Err(api_failure(path, status, ""));
    }
    if code.is_none() {
        return Err(invalid_shape(path, "code"));
    }
    match envelope.as_object_mut().and_then(|map| map.remove("data")) {
        Some(Value::Array(rows)) => Ok(rows),
        _ => Err(invalid_shape(path, "data")),
    }
}

fn api_failure(path: &str, status: u16, code: &str) -> FetchError {
    // The upstream message and unrecognised codes can echo credentials.
    if code == "50119" {
        return FetchError::Coded {
            code: "OKX_REGION_MISMATCH",
            detail: "The OKX API key does not exist in the selected region (50119). Choose the region where the key was created.".into(),
        };
    }
    let message = match code {
        "50100" => "The OKX API key is frozen (50100).",
        "50102" => "OKX rejected the request timestamp (50102). Check the server clock.",
        "50105" => "The OKX API passphrase is incorrect (50105).",
        "50110" => "The request IP address is not on the API key's allowlist (50110).",
        "50111" => "OKX rejected the API key (50111).",
        "50113" => "OKX rejected the request signature (50113).",
        "50011" => "OKX rate-limited the sync (50011). Please try again later.",
        "51024" => {
            "OKX refused the request for this account (51024): check the account's status and region on OKX."
        }
        _ if status == 429 => {
            return FetchError::Sync(format!(
                "OKX rate-limited {path} (HTTP 429). Please try again later."
            ));
        }
        _ => return FetchError::Sync(format!("OKX rejected {path} (HTTP {status}).")),
    };
    FetchError::Sync(message.into())
}

fn trading_balances(rows: &[Value]) -> Result<Balances, FetchError> {
    let [account] = rows else {
        return Err(invalid_shape(TRADING, "data"));
    };
    let details = array(account, "details", TRADING)?;
    let total = amount(account, "totalEq", TRADING)?;
    let balances = currency_balances(details, "eq", TRADING)?;
    if balances.is_empty() && !matches!(total, Amount::Zero) {
        return Err(FetchError::Sync(format!(
            "OKX returned no non-zero details with non-zero totalEq for {TRADING}."
        )));
    }
    Ok(balances)
}

fn currency_balances(rows: &[Value], field: &str, path: &str) -> Result<Balances, FetchError> {
    let mut balances = Balances::new();
    for row in rows {
        let symbol = currency(row, path)?;
        let quantity = balance(row, field, path)?;
        if balances.insert(symbol, quantity).is_some() {
            return Err(invalid_shape(path, "duplicate ccy"));
        }
    }
    balances.retain(|_, quantity| !quantity.is_zero());
    Ok(balances)
}

fn order_balances(rows: &[Value]) -> Result<Balances, FetchError> {
    let mut balances = Balances::new();
    let mut order_ids = HashSet::new();
    for row in rows {
        let id = text(row, "ordId")
            .filter(|id| !java_is_blank(id))
            .ok_or_else(|| invalid_shape(ORDERS, "ordId"))?;
        if !order_ids.insert(id) {
            return Err(invalid_shape(ORDERS, "duplicate ordId"));
        }
        let investments = array(row, "investData", ORDERS)?;
        if investments.is_empty() {
            return Err(invalid_shape(ORDERS, "investData"));
        }
        for investment in investments {
            let symbol = currency(investment, ORDERS)?;
            let quantity = balance(investment, "amt", ORDERS)?;
            add_balance(&mut balances, symbol, quantity, ORDERS)?;
        }
    }
    Ok(balances)
}

impl Receipt {
    fn check_earn(&self, lending: &Balances, staking: &Balances) -> Result<(), FetchError> {
        let receipt = self.symbol;
        if lending.contains_key(receipt) || staking.contains_key(receipt) {
            return Err(FetchError::Coded {
                code: "OKX_STAKING_RECEIPT_IN_EARN",
                detail: format!(
                    "OKX reported {receipt} in Simple Earn or On-chain Earn. Picsou cannot tell whether the dedicated staking balance already includes it. Redeem {receipt} from Earn to your funding account, then reconnect or sync again."
                ),
            });
        }
        Ok(())
    }

    fn reconcile(
        &self,
        rows: &[Value],
        spot: &mut Balances,
        staking: &mut Balances,
    ) -> Result<(), FetchError> {
        let quantity = match rows {
            [] => Decimal::ZERO,
            [row] => {
                if currency(row, self.path)? != self.symbol {
                    return Err(invalid_shape(self.path, "ccy"));
                }
                balance(row, "amt", self.path)?
            }
            _ => return Err(invalid_shape(self.path, "multiple staking balances")),
        };
        // The dedicated balance already includes receipts held in trading and funding.
        if spot.remove(self.symbol).is_some_and(|spot| spot > quantity) {
            return Err(FetchError::Sync(format!(
                "Picsou cannot reconcile OKX {} balances. The combined trading and funding balance exceeds the dedicated staking balance.",
                self.symbol
            )));
        }
        add_balance(staking, self.underlying.into(), quantity, self.path)
    }
}

fn positions(
    spot: Balances,
    staking: Balances,
    lending: Balances,
) -> Result<Vec<ExchangePosition>, FetchError> {
    let positions: Vec<_> = [
        (Product::Spot, spot),
        (Product::Staking, staking),
        (Product::Lending, lending),
    ]
    .into_iter()
    .flat_map(|(product, balances)| {
        balances
            .into_iter()
            .map(move |(symbol, quantity)| ExchangePosition {
                product,
                symbol,
                quantity,
                principal: None,
                interest: None,
            })
    })
    .collect();
    // Positions and holdings are stored as NUMERIC(20,8).
    let quantity_limit = Decimal::from(1_000_000_000_000_u64);
    let mut totals = BTreeMap::<&str, Decimal>::new();
    for position in &positions {
        if position.quantity >= quantity_limit {
            return Err(FetchError::Sync(
                "OKX returned a position quantity outside the supported range.".into(),
            ));
        }
        // With both operands below the storage limit, this sum fits in Decimal.
        let total = totals.entry(&position.symbol).or_default();
        *total += position.quantity;
        if *total >= quantity_limit {
            return Err(FetchError::Sync(
                "OKX returned an asset total outside the supported quantity range.".into(),
            ));
        }
    }
    Ok(positions)
}

fn add_balance(
    balances: &mut Balances,
    currency: String,
    quantity: Decimal,
    path: &str,
) -> Result<(), FetchError> {
    if !quantity.is_zero() {
        let total = balances.entry(currency).or_default();
        *total = exact_sum(*total, quantity).ok_or_else(|| invalid_shape(path, "balance total"))?;
    }
    Ok(())
}

// port::parse_big_decimal rounds beyond 28 significant digits. OKX needs exact
// quantities and sums, and must identify liabilities by sign before conversion.
fn exact_sum(left: Decimal, right: Decimal) -> Option<Decimal> {
    // Both quantities are nonnegative. Align their coefficients without Decimal's rounding.
    let scale = left.scale().max(right.scale());
    let lhs = left
        .mantissa()
        .checked_mul(10_i128.pow(scale - left.scale()))?;
    let rhs = right
        .mantissa()
        .checked_mul(10_i128.pow(scale - right.scale()))?;
    exact_decimal(lhs.checked_add(rhs)?, i64::from(scale))
}

fn exact_decimal(mut coefficient: i128, mut scale: i64) -> Option<Decimal> {
    if coefficient == 0 {
        return Some(Decimal::ZERO);
    }
    while scale > 0 && coefficient % 10 == 0 {
        coefficient /= 10;
        scale -= 1;
    }
    if scale < 0 {
        let factor = 10_i128.checked_pow(u32::try_from(scale.checked_neg()?).ok()?)?;
        coefficient = coefficient.checked_mul(factor)?;
        scale = 0;
    }
    Decimal::try_from_i128_with_scale(coefficient, u32::try_from(scale).ok()?).ok()
}

enum Amount {
    Zero,
    Negative,
    Positive(Option<Decimal>),
}

fn parse_amount(text: &str) -> Option<Amount> {
    // Check the sign before conversion. Trading totalEq only needs its sign, and a
    // liability still needs its diagnostic even if Decimal cannot represent it exactly.
    let (mantissa, exponent) = match text.split_once(['e', 'E']) {
        Some((mantissa, exponent)) => (mantissa, i64::from(exponent.parse::<i32>().ok()?)),
        None => (text, 0),
    };
    let digits = mantissa.strip_prefix(['+', '-']).unwrap_or(mantissa);
    let (whole, fraction) = digits.split_once('.').unwrap_or((digits, ""));
    if (whole.is_empty() && fraction.is_empty())
        || !whole
            .bytes()
            .chain(fraction.bytes())
            .all(|b| b.is_ascii_digit())
    {
        return None;
    }
    let digits = format!("{whole}{fraction}");
    let scale = i64::from(i32::try_from(i64::try_from(fraction.len()).ok()? - exponent).ok()?);
    let significant = digits.trim_end_matches('0');
    if significant.is_empty() {
        return Some(Amount::Zero);
    }
    if mantissa.starts_with('-') {
        return Some(Amount::Negative);
    }
    let trailing_zeros = i64::try_from(digits.len() - significant.len()).ok()?;
    let quantity = significant
        .parse::<i128>()
        .ok()
        .and_then(|coefficient| exact_decimal(coefficient, scale - trailing_zeros));
    Some(Amount::Positive(quantity))
}

fn text<'a>(row: &'a Value, field: &str) -> Option<&'a str> {
    row.get(field).and_then(Value::as_str)
}

fn amount(row: &Value, field: &str, path: &str) -> Result<Amount, FetchError> {
    text(row, field)
        .and_then(parse_amount)
        .ok_or_else(|| invalid_shape(path, field))
}

fn balance(row: &Value, field: &str, path: &str) -> Result<Decimal, FetchError> {
    match amount(row, field, path)? {
        Amount::Zero => Ok(Decimal::ZERO),
        Amount::Positive(Some(quantity)) => Ok(quantity),
        Amount::Positive(None) => Err(invalid_shape(path, field)),
        Amount::Negative if path == TRADING => Err(FetchError::Coded {
            code: "OKX_NEGATIVE_BALANCE",
            detail: format!(
                "OKX returned a negative {field} for {path}. Picsou does not support exchange liabilities. Repay the borrowed balance on OKX, then reconnect or sync again."
            ),
        }),
        Amount::Negative => Err(invalid_shape(path, &format!("negative {field}"))),
    }
}

fn currency(row: &Value, path: &str) -> Result<String, FetchError> {
    text(row, "ccy")
        .map(java_strip)
        .filter(|currency| !currency.is_empty())
        .map(str::to_uppercase)
        .ok_or_else(|| invalid_shape(path, "ccy"))
}

fn array<'a>(row: &'a Value, field: &str, path: &str) -> Result<&'a [Value], FetchError> {
    row.get(field)
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .ok_or_else(|| invalid_shape(path, field))
}

fn invalid_shape(path: &str, field: &str) -> FetchError {
    FetchError::Sync(format!("OKX returned unexpected {field} for {path}."))
}

/// Read the full JSON tree to reject duplicate names, including in unused upstream fields.
/// With arbitrary precision, serde_json visits in-range integers as scalars and other numbers
/// via its private map representation. Neither is usable as an OKX string field.
struct StrictJson(Value);

impl<'de> Deserialize<'de> for StrictJson {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        deserializer.deserialize_any(StrictVisitor).map(Self)
    }
}

struct StrictVisitor;

impl<'de> Visitor<'de> for StrictVisitor {
    type Value = Value;

    fn expecting(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("JSON with unique object keys")
    }

    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Value, A::Error> {
        let mut result = Map::new();
        while let Some((key, StrictJson(value))) = map.next_entry::<String, StrictJson>()? {
            if result.insert(key, value).is_some() {
                return Err(de::Error::custom("duplicate JSON key"));
            }
        }
        Ok(Value::Object(result))
    }

    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Value, A::Error> {
        let mut result = Vec::new();
        while let Some(StrictJson(value)) = seq.next_element()? {
            result.push(value);
        }
        Ok(Value::Array(result))
    }

    fn visit_str<E: de::Error>(self, value: &str) -> Result<Value, E> {
        Ok(Value::String(value.to_owned()))
    }

    fn visit_i64<E: de::Error>(self, _: i64) -> Result<Value, E> {
        Ok(Value::Null)
    }

    fn visit_u64<E: de::Error>(self, _: u64) -> Result<Value, E> {
        Ok(Value::Null)
    }

    fn visit_bool<E: de::Error>(self, _: bool) -> Result<Value, E> {
        Ok(Value::Null)
    }

    fn visit_unit<E: de::Error>(self) -> Result<Value, E> {
        Ok(Value::Null)
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use chrono_tz::Asia::Tokyo;
    use serde_json::json;

    use super::*;
    use crate::clock::FixedClock;
    use crate::config::Env;
    use crate::sync::exchanges::model::ExchangeType;

    #[test]
    fn signs_trading_and_funding_paths_with_utc_milliseconds_including_zero() {
        let fixed = Arc::new(FixedClock::new("2020-12-08T09:08:57.123Z".parse().unwrap()));
        let clock = AppClock::new(fixed.clone(), Tokyo);
        let credentials = ExchangeCredentials {
            api_key: "synthetic-key".into(),
            api_secret: Some("synthetic-secret".into()),
            api_passphrase: Some("synthetic-passphrase".into()),
            region: Some("EEA".into()),
        };
        for (path, timestamp, signature) in [
            (
                TRADING,
                "2020-12-08T09:08:57.123Z",
                "+O+VqOpuRsHqS13jsN4I0KR2xjidreyFK9lb/mTz1GU=",
            ),
            (
                TRADING,
                "2020-12-08T09:08:57.000Z",
                "W6szVeElG/gmzYB7Hu6LFaYZrtkDBBgE6G/03yzd3Pw=",
            ),
            (
                FUNDING,
                "2020-12-08T09:08:57.000Z",
                "rjKcENZ3sH9mkggaRjujOXtZJtpS6dh098WsJ4XkaYg=",
            ),
        ] {
            fixed.set(timestamp.parse().unwrap());
            let headers = signed_headers(&clock, &credentials, path).unwrap();
            assert_eq!(headers["ok-access-timestamp"], timestamp);
            assert_eq!(headers["ok-access-sign"], signature);
            assert!(headers["ok-access-key"].is_sensitive());
            assert!(headers["ok-access-sign"].is_sensitive());
            assert!(headers["ok-access-passphrase"].is_sensitive());
        }
    }

    #[test]
    fn okx_ordinal_and_production_hosts() {
        let config = Config::load(&Env::from_map([
            ("JWT_SECRET", "0123456789abcdef0123456789abcdef"),
            ("CRYPTO_ENCRYPTION_KEY", "0123456789abcdef0123456789abcdef"),
        ]))
        .unwrap();
        let hosts: Vec<_> = REGIONS
            .iter()
            .map(|region| host_for_region(&config, Some(region)).unwrap())
            .collect();
        assert_eq!(
            hosts,
            [
                "https://www.okx.com",
                "https://eea.okx.com",
                "https://us.okx.com"
            ]
        );
        assert_eq!(REGIONS, ["GLOBAL", "EEA", "US"]);
        for region in [None, Some("UNKNOWN")] {
            assert!(host_for_region(&config, region).is_err());
        }
        for (ordinal, name) in ["BINANCE", "KRAKEN", "MERIA", "OKX"]
            .into_iter()
            .enumerate()
        {
            let exchange: ExchangeType = serde_json::from_value(json!(ordinal)).unwrap();
            assert_eq!(exchange.as_str(), name);
            assert_eq!(serde_json::to_value(exchange).unwrap(), name);
        }
    }

    #[test]
    fn aggregates_normalized_balances_per_product() {
        let trading =
            json!({"totalEq":"10","details":[{"ccy":"coin","eq":"10"},{"ccy":"ZERO","eq":"0"}]});
        let mut spot = trading_balances(&[trading]).unwrap();
        for (symbol, quantity) in
            currency_balances(&[json!({"ccy":" coin ","bal":"5"})], "bal", FUNDING).unwrap()
        {
            add_balance(&mut spot, symbol, quantity, FUNDING).unwrap();
        }
        let lending = currency_balances(
            &[json!({"ccy":"COIN","amt":"9","earnings":"2"})],
            "amt",
            SAVINGS,
        )
        .unwrap();
        let staking = order_balances(&[
            json!({"ordId":"one","investData":[{"ccy":"Coin","amt":"1"},{"ccy":"COIN","amt":"2"}],"earningData":[{"ccy":"COIN","amt":"9"}]}),
            json!({"ordId":"two","investData":[{"ccy":"COIN","amt":"3"}]}),
        ]).unwrap();
        let actual = positions(spot, staking, lending).unwrap();
        assert_eq!(
            actual
                .iter()
                .map(|p| (p.product, p.symbol.as_str(), p.quantity))
                .collect::<Vec<_>>(),
            [
                (Product::Spot, "COIN", 15),
                (Product::Staking, "COIN", 6),
                (Product::Lending, "COIN", 9)
            ]
            .map(|(product, symbol, quantity)| (
                product,
                symbol,
                Decimal::from(quantity)
            ))
        );
        assert!(
            actual
                .iter()
                .all(|p| p.principal.is_none() && p.interest.is_none())
        );
    }

    #[test]
    fn rejects_a_position_quantity_at_or_above_the_storage_limit() {
        for quantity in [Decimal::from(1_000_000_000_000_u64), Decimal::MAX] {
            let spot = Balances::from([("COIN".into(), quantity)]);
            assert!(matches!(
                positions(spot, Balances::new(), Balances::new()),
                Err(FetchError::Sync(detail)) if detail.contains("position quantity")
            ));
        }
    }

    #[test]
    fn rejects_a_holding_total_that_crosses_the_storage_limit_across_products() {
        let spot = Balances::from([("COIN".into(), Decimal::from(999_999_999_999_u64))]);
        let lending = Balances::from([("COIN".into(), Decimal::from(2))]);
        assert!(matches!(
            positions(spot, Balances::new(), lending),
            Err(FetchError::Sync(detail)) if detail.contains("asset total")
        ));
    }

    #[test]
    fn reconciles_both_receipts_against_dedicated_staking_and_keeps_native_spot() {
        for receipt in &RECEIPTS {
            for (trading, funding, dedicated, accepted) in [
                (0, 4, Some(4), true),
                (3, 0, Some(4), true),
                (2, 2, Some(4), true),
                (0, 0, None, true),
                (0, 4, Some(3), false),
                (2, 2, Some(3), false),
                (0, 2, Some(0), false),
                (2, 0, None, false),
            ] {
                let mut spot = Balances::from([(receipt.underlying.into(), Decimal::from(2))]);
                let mut staking = Balances::from([(receipt.underlying.into(), Decimal::ONE)]);
                add_balance(&mut spot, receipt.symbol.into(), trading.into(), TRADING).unwrap();
                add_balance(&mut spot, receipt.symbol.into(), funding.into(), FUNDING).unwrap();
                let rows: Vec<_> = dedicated
                    .into_iter()
                    .map(|amt| json!({"ccy": receipt.symbol, "amt": amt.to_string()}))
                    .collect();
                let result = receipt.reconcile(&rows, &mut spot, &mut staking);
                if accepted {
                    result.unwrap();
                    assert_eq!(
                        spot,
                        Balances::from([(receipt.underlying.into(), Decimal::from(2))])
                    );
                    assert_eq!(
                        staking[receipt.underlying],
                        Decimal::from(1 + dedicated.unwrap_or(0))
                    );
                } else {
                    assert!(
                        matches!(result, Err(FetchError::Sync(detail)) if detail.contains("cannot reconcile") && detail.contains(receipt.symbol))
                    );
                }
            }
        }
    }

    #[test]
    fn validates_exact_decimal_strings_and_preserves_the_sign_of_tiny_liabilities() {
        for (raw, expected) in [
            ("+1.25e+2", "125"),
            ("125e-2", "1.25"),
            (".2", "0.2"),
            ("2.", "2"),
            ("-0.00", "0"),
            ("1.00000000000000000000000000000000000000", "1"),
        ] {
            assert_eq!(
                balance(&json!({"eq": raw}), "eq", TRADING).unwrap(),
                expected.parse::<Decimal>().unwrap()
            );
        }
        for raw in [
            "1e-29", "", ".", "+", "1_0", " 1", "1 ", "1e", "1.2.3", "1e2e3", "0x01",
        ] {
            assert_eq!(
                balance(&json!({"eq": raw}), "eq", TRADING),
                Err(invalid_shape(TRADING, "eq"))
            );
        }
        assert!(matches!(
            balance(&json!({"eq":"-1e-29"}), "eq", TRADING),
            Err(FetchError::Coded {
                code: "OKX_NEGATIVE_BALANCE",
                ..
            })
        ));
        assert!(exact_sum(Decimal::MAX, Decimal::ONE).is_none());
        assert!(exact_sum(Decimal::MAX, Decimal::new(1, 9)).is_none());
        assert_eq!(
            exact_sum(Decimal::new(9, 1), Decimal::new(1, 1)),
            Some(Decimal::ONE)
        );
    }

    #[test]
    fn rejects_duplicate_keys_in_unknown_fields_and_trailing_values() {
        for body in [
            r#"{"code":"0","data":[],"extra":{"ignored":[{"x":0,"x":1}]}}"#,
            r#"{"code":"0","data":[{"ccy":"COIN","\u0063cy":"BETH"}]}"#,
            r#"{"code":"0","data":[]} null"#,
        ] {
            assert!(
                matches!(parse_envelope(FUNDING, 200, body.as_bytes()), Err(FetchError::Sync(detail)) if detail.contains("unreadable JSON"))
            );
        }
        assert!(
            parse_envelope(
                FUNDING,
                200,
                br#"{"code":"0","data":[],"extra":[1,-1,1.25,-0,1e100,true,null]}"#
            )
            .unwrap()
            .is_empty()
        );
        let rows = parse_envelope(
            FUNDING,
            200,
            br#"{"code":"0","data":[{"ccy":"COIN","bal":1.25}]}"#,
        )
        .unwrap();
        assert_eq!(
            currency_balances(&rows, "bal", FUNDING),
            Err(invalid_shape(FUNDING, "bal"))
        );
    }
}
