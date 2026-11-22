//! Native Rust port of `services/degiro-auth/` (DEGIRO authentication & portfolio fetching).
//!
//! Endpoints:
//! - `GET /health` -> `{"status": "ok"}`
//! - `POST /initiate` `{username, password}` -> `{processId, totpRequired, sessionBlob?}`
//! - `POST /complete` `{processId, code}` -> `{sessionBlob}`
//! - `POST /portfolio` `{sessionBlob}` -> `{cashEur, positions: [...]}`

use std::collections::HashMap;
use std::sync::LazyLock;
use std::time::Duration;

use async_trait::async_trait;
use http::{Method, StatusCode};
use regex::Regex;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use url::Url;

use super::common::{
    MAX_RESPONSE_BYTES, MemoryCookieJar, PendingStore, build_http_client, normalize_base_url,
    read_capped_bytes,
};
use super::{NativeSidecar, NativeSidecarError};

pub const DEFAULT_BASE_URL: &str = "https://trader.degiro.nl";

const USER_AGENT: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) \
    AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
const PENDING_TTL: Duration = Duration::from_secs(300);
const MAX_PENDING: usize = 16;

static SESSION_ID_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Za-z0-9._~!*+=,:-]{1,256}$").expect("static regex"));

struct PendingCredentials {
    username: String,
    password: String,
}

pub struct NativeDegiro {
    base_url: Url,
    pending: PendingStore<PendingCredentials>,
}

impl Default for NativeDegiro {
    fn default() -> Self {
        Self::with_base_url(DEFAULT_BASE_URL)
    }
}

impl NativeDegiro {
    pub fn with_base_url(base_url: &str) -> Self {
        let normalized = normalize_base_url(base_url);
        let url = Url::parse(&normalized).expect("valid DEGIRO base URL");
        Self {
            base_url: url,
            pending: PendingStore::new(PENDING_TTL, Some(MAX_PENDING)),
        }
    }

    fn new_client(
        &self,
    ) -> Result<(reqwest::Client, std::sync::Arc<MemoryCookieJar>), NativeSidecarError> {
        let jar = MemoryCookieJar::new();
        let client = build_http_client(
            jar.clone(),
            USER_AGENT,
            REQUEST_TIMEOUT,
            &[("Content-Type", "application/json")],
        )?;
        Ok((client, jar))
    }

    async fn login(
        &self,
        client: &reqwest::Client,
        jar: &MemoryCookieJar,
        username: &str,
        password: &str,
        totp: Option<&str>,
    ) -> Result<LoginOutcome, NativeSidecarError> {
        let path = if totp.is_some() {
            "/login/secure/login/totp"
        } else {
            "/login/secure/login"
        };
        let url = self.base_url.join(path).map_err(|_| {
            NativeSidecarError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
        })?;
        let mut body = json!({
            "username": username,
            "password": password,
            "isPassCodeReset": false,
            "isRedirectToMobile": false,
        });
        if let Some(code) = totp {
            body["oneTimePassword"] = Value::String(code.to_owned());
        }

        let resp = client.post(url).json(&body).send().await.map_err(|_| {
            tracing::warn!("DEGIRO login HTTP request failed");
            NativeSidecarError::new(StatusCode::BAD_GATEWAY, "DEGIRO login request failed")
        })?;
        let status = resp.status();
        let session_id = jar.get_case_insensitive("JSESSIONID");
        let payload: Value = read_capped_bytes(resp, MAX_RESPONSE_BYTES)
            .await
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_else(|| json!({}));

        tracing::info!(
            "DEGIRO login response: HTTP {}, session_cookie={}",
            status.as_u16(),
            session_id.is_some()
        );

        let status_num = payload.get("status").and_then(Value::as_i64);
        let status_text = payload.get("statusText").and_then(Value::as_str);
        let needs_totp = totp.is_none()
            && (status_num == Some(6)
                || matches!(status_text, Some("totpNeeded" | "totpRequired")));
        if needs_totp {
            return Ok(LoginOutcome::NeedsTotp);
        }

        if let Some(sid) = session_id {
            return Ok(LoginOutcome::Session(sid));
        }

        if matches!(status, StatusCode::BAD_REQUEST | StatusCode::UNAUTHORIZED) {
            return Err(NativeSidecarError::new(
                StatusCode::UNAUTHORIZED,
                "Invalid DEGIRO credentials or TOTP code",
            ));
        }

        Err(NativeSidecarError::new(
            StatusCode::BAD_GATEWAY,
            format!(
                "Unexpected response from DEGIRO login (HTTP {})",
                status.as_u16()
            ),
        ))
    }

    async fn fetch_int_account(
        &self,
        client: &reqwest::Client,
        session_id: &str,
    ) -> Result<i64, NativeSidecarError> {
        let url = self.base_url.join("/pa/secure/client").map_err(|_| {
            NativeSidecarError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
        })?;
        let resp = client
            .get(url)
            .query(&[("sessionId", session_id)])
            .send()
            .await
            .map_err(|_| {
                NativeSidecarError::new(
                    StatusCode::BAD_GATEWAY,
                    "Could not resolve DEGIRO account after login",
                )
            })?;
        let status = resp.status();
        let payload: Value = read_capped_bytes(resp, MAX_RESPONSE_BYTES)
            .await
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_else(|| json!({}));
        tracing::info!(
            "DEGIRO /pa/secure/client response: HTTP {}",
            status.as_u16()
        );

        if status != StatusCode::OK {
            return Err(NativeSidecarError::new(
                StatusCode::BAD_GATEWAY,
                "Could not resolve DEGIRO account after login",
            ));
        }
        let raw_account = payload
            .get("data")
            .and_then(|d| d.get("intAccount"))
            .filter(|v| !v.is_null())
            .ok_or_else(|| {
                NativeSidecarError::new(
                    StatusCode::BAD_GATEWAY,
                    "DEGIRO login succeeded but no account was returned",
                )
            })?;
        parse_int_account(raw_account).ok_or_else(|| {
            NativeSidecarError::new(
                StatusCode::BAD_GATEWAY,
                "DEGIRO returned an invalid account number",
            )
        })
    }

    async fn fetch_portfolio(
        &self,
        client: &reqwest::Client,
        session_id: &str,
        int_account: i64,
    ) -> Result<PortfolioOutput, NativeSidecarError> {
        let path = format!("/trading/secure/v5/update/{int_account};jsessionid={session_id}");
        let url = self.base_url.join(&path).map_err(|_| {
            NativeSidecarError::new(StatusCode::BAD_REQUEST, "Invalid sessionBlob format")
        })?;
        let resp = client
            .get(url)
            .query(&[
                ("portfolio", "0"),
                ("totalPortfolio", "0"),
                ("cashFunds", "0"),
            ])
            .send()
            .await
            .map_err(|_| {
                NativeSidecarError::new(StatusCode::BAD_GATEWAY, "Could not fetch DEGIRO portfolio")
            })?;
        if resp.status() == StatusCode::UNAUTHORIZED {
            return Err(NativeSidecarError::new(
                StatusCode::UNAUTHORIZED,
                "DEGIRO session expired",
            ));
        }
        if resp.status() != StatusCode::OK {
            return Err(NativeSidecarError::new(
                StatusCode::BAD_GATEWAY,
                "Could not fetch DEGIRO portfolio",
            ));
        }
        let bytes = read_capped_bytes(resp, MAX_RESPONSE_BYTES)
            .await
            .map_err(|_| {
                NativeSidecarError::new(StatusCode::BAD_GATEWAY, "Could not fetch DEGIRO portfolio")
            })?;
        let data: Value = serde_json::from_slice(&bytes).map_err(|_| {
            NativeSidecarError::new(StatusCode::BAD_GATEWAY, "Could not fetch DEGIRO portfolio")
        })?;
        let cash_rows = data
            .get("cashFunds")
            .and_then(|v| v.get("value"))
            .and_then(Value::as_array)
            .map(Vec::as_slice)
            .unwrap_or(&[]);
        let portfolio_rows = data
            .get("portfolio")
            .and_then(|v| v.get("value"))
            .and_then(Value::as_array)
            .map(Vec::as_slice)
            .unwrap_or(&[]);

        let cash_eur = parse_cash_eur(cash_rows);
        let raw_positions = parse_raw_positions(portfolio_rows);
        let product_ids: Vec<String> = raw_positions
            .iter()
            .filter_map(|p| p.product_id.clone())
            .collect();

        let products = if product_ids.is_empty() {
            HashMap::new()
        } else {
            self.fetch_product_info(client, session_id, int_account, &product_ids)
                .await
        };
        let positions = build_positions(&raw_positions, &products);
        Ok(PortfolioOutput {
            cash_eur,
            positions,
        })
    }

    async fn fetch_product_info(
        &self,
        client: &reqwest::Client,
        session_id: &str,
        int_account: i64,
        product_ids: &[String],
    ) -> HashMap<String, ProductInfo> {
        let Ok(url) = self
            .base_url
            .join("/product_search/secure/v5/products/info")
        else {
            return HashMap::new();
        };
        let account_str = int_account.to_string();
        let resp = match client
            .post(url)
            .query(&[
                ("intAccount", account_str.as_str()),
                ("sessionId", session_id),
            ])
            .json(product_ids)
            .send()
            .await
        {
            Ok(resp) if resp.status() == StatusCode::OK => resp,
            _ => {
                tracing::warn!(
                    "DEGIRO product info lookup failed, falling back to raw productId labels"
                );
                return HashMap::new();
            }
        };
        let Ok(bytes) = read_capped_bytes(resp, MAX_RESPONSE_BYTES).await else {
            return HashMap::new();
        };
        let Ok(payload) = serde_json::from_slice::<Value>(&bytes) else {
            return HashMap::new();
        };
        let Some(data) = payload.get("data").and_then(Value::as_object) else {
            return HashMap::new();
        };
        build_product_info_map(data)
    }

    async fn handle_initiate(&self, body: Value) -> Result<Value, NativeSidecarError> {
        #[derive(Deserialize)]
        struct Req {
            username: String,
            password: String,
        }
        let req: Req = serde_json::from_value(body).map_err(|_| {
            NativeSidecarError::new(StatusCode::UNPROCESSABLE_ENTITY, "Invalid request")
        })?;

        self.pending.expire().await;
        tracing::info!("DEGIRO auth initiate");

        let (client, jar) = self.new_client()?;
        let outcome = self
            .login(&client, &jar, &req.username, &req.password, None)
            .await?;

        let process_id = uuid::Uuid::new_v4().to_string();
        match outcome {
            LoginOutcome::NeedsTotp => {
                self.pending
                    .put_or_err(
                        process_id.clone(),
                        PendingCredentials {
                            username: req.username,
                            password: req.password,
                        },
                        "UPSTREAM_UNAVAILABLE",
                    )
                    .await?;
                Ok(json!({ "processId": process_id, "totpRequired": true }))
            }
            LoginOutcome::Session(session_id) => {
                let int_account = self.fetch_int_account(&client, &session_id).await?;
                let blob =
                    json!({ "sessionId": session_id, "intAccount": int_account }).to_string();
                Ok(json!({
                    "processId": process_id,
                    "totpRequired": false,
                    "sessionBlob": blob
                }))
            }
        }
    }

    async fn handle_complete(&self, body: Value) -> Result<Value, NativeSidecarError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct Req {
            process_id: String,
            code: String,
        }
        let req: Req = serde_json::from_value(body).map_err(|_| {
            NativeSidecarError::new(StatusCode::UNPROCESSABLE_ENTITY, "Invalid request")
        })?;

        let Some(state) = self.pending.take(&req.process_id).await else {
            return Err(NativeSidecarError::new(
                StatusCode::NOT_FOUND,
                "processId not found or expired — please re-authenticate",
            ));
        };

        tracing::info!("DEGIRO TOTP complete");
        let (client, jar) = self.new_client()?;
        let outcome = self
            .login(
                &client,
                &jar,
                &state.username,
                &state.password,
                Some(&req.code),
            )
            .await?;
        let session_id = match outcome {
            LoginOutcome::NeedsTotp => {
                return Err(NativeSidecarError::new(
                    StatusCode::UNAUTHORIZED,
                    "TOTP code was rejected",
                ));
            }
            LoginOutcome::Session(sid) => sid,
        };
        let int_account = self.fetch_int_account(&client, &session_id).await?;
        let blob = json!({ "sessionId": session_id, "intAccount": int_account }).to_string();
        Ok(json!({ "sessionBlob": blob }))
    }

    async fn handle_portfolio(&self, body: Value) -> Result<Value, NativeSidecarError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct Req {
            session_blob: String,
        }
        let req: Req = serde_json::from_value(body).map_err(|_| {
            NativeSidecarError::new(StatusCode::UNPROCESSABLE_ENTITY, "Invalid request")
        })?;

        let bad_blob =
            || NativeSidecarError::new(StatusCode::BAD_REQUEST, "Invalid sessionBlob format");
        let parsed: Value = serde_json::from_str(&req.session_blob).map_err(|_| bad_blob())?;
        let obj = parsed.as_object().ok_or_else(bad_blob)?;
        let session_id = obj
            .get("sessionId")
            .and_then(Value::as_str)
            .filter(|s| SESSION_ID_RE.is_match(s))
            .ok_or_else(bad_blob)?;
        let int_account = obj
            .get("intAccount")
            .and_then(parse_int_account)
            .ok_or_else(bad_blob)?;

        let (client, jar) = self.new_client()?;
        let host = self.base_url.host_str().unwrap_or("trader.degiro.nl");
        jar.set("JSESSIONID", session_id, host, "/");
        let portfolio = self
            .fetch_portfolio(&client, session_id, int_account)
            .await?;
        Ok(serde_json::to_value(portfolio).expect("serialize DEGIRO portfolio"))
    }
}

enum LoginOutcome {
    NeedsTotp,
    Session(String),
}

fn parse_int_account(value: &Value) -> Option<i64> {
    match value {
        Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                Some(i)
            } else {
                // Handle serde_json arbitrary_precision numbers cleanly without floats.
                let s = n.to_string();
                s.parse::<i64>().ok()
            }
        }
        Value::String(s) => s.trim().parse::<i64>().ok(),
        _ => None,
    }
}

#[async_trait]
impl NativeSidecar for NativeDegiro {
    async fn call(
        &self,
        method: Method,
        path: &str,
        body: Value,
    ) -> Result<Value, NativeSidecarError> {
        match (method, path) {
            (Method::GET, "/health") => Ok(json!({ "status": "ok" })),
            (Method::POST, "/initiate") => self.handle_initiate(body).await,
            (Method::POST, "/complete") => self.handle_complete(body).await,
            (Method::POST, "/portfolio") => self.handle_portfolio(body).await,
            _ => Err(NativeSidecarError::new(StatusCode::NOT_FOUND, "Not Found")),
        }
    }
}

// ─── Pure portfolio parser (port of services/degiro-auth/portfolio_parser.py) ─

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProductInfo {
    pub isin: Option<String>,
    pub symbol: Option<String>,
    pub name: Option<String>,
    pub close_price: Option<Decimal>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawPosition {
    pub product_id: Option<String>,
    pub size: Decimal,
    pub price: Decimal,
    pub break_even_price: Decimal,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PositionOutput {
    pub isin: Option<String>,
    pub symbol: String,
    pub name: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub quantity: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub buying_price: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub current_price: Decimal,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PortfolioOutput {
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub cash_eur: Decimal,
    pub positions: Vec<PositionOutput>,
}

fn sanitize_string(value: Option<&Value>) -> Option<String> {
    let val = value?;
    match val {
        Value::Null => None,
        Value::String(s) => {
            let trimmed = s.trim();
            if trimmed.is_empty() || trimmed.eq_ignore_ascii_case("NULL") {
                None
            } else {
                Some(s.clone())
            }
        }
        other => Some(other.to_string()),
    }
}

fn parse_decimal_opt(value: Option<&Value>) -> Option<Decimal> {
    let val = value?;
    match val {
        Value::Null => None,
        Value::Number(n) => {
            let s = n.to_string();
            s.parse::<Decimal>()
                .ok()
                .or_else(|| Decimal::from_scientific(&s).ok())
        }
        Value::String(s) => {
            let trimmed = s.trim();
            if trimmed.is_empty() || trimmed.eq_ignore_ascii_case("NULL") {
                return None;
            }
            trimmed
                .parse::<Decimal>()
                .ok()
                .or_else(|| Decimal::from_scientific(trimmed).ok())
        }
        _ => None,
    }
}

fn parse_decimal_or_zero(value: Option<&Value>) -> Decimal {
    parse_decimal_opt(value).unwrap_or(Decimal::ZERO)
}

pub fn sanitize_product_info(info: &Value) -> ProductInfo {
    ProductInfo {
        isin: sanitize_string(info.get("isin")),
        symbol: sanitize_string(info.get("symbol")),
        name: sanitize_string(info.get("name")),
        close_price: parse_decimal_opt(info.get("closePrice")),
    }
}

pub fn value_pairs_to_dict(item: &Value) -> Map<String, Value> {
    if let Some(arr) = item.get("value").and_then(Value::as_array) {
        let mut map = Map::new();
        for pair in arr {
            if let Some(obj) = pair.as_object()
                && let Some(name) = obj.get("name").and_then(Value::as_str)
            {
                let val = obj.get("value").cloned().unwrap_or(Value::Null);
                map.insert(name.to_owned(), val);
            }
        }
        return map;
    }
    item.as_object().cloned().unwrap_or_default()
}

pub fn parse_cash_eur(cash_funds_rows: &[Value]) -> Decimal {
    for row in cash_funds_rows {
        let flat = value_pairs_to_dict(row);
        if flat.get("currencyCode").and_then(Value::as_str) == Some("EUR") {
            return parse_decimal_or_zero(flat.get("value"));
        }
    }
    Decimal::ZERO
}

pub fn is_real_product_id(product_id: Option<&Value>) -> bool {
    let Some(val) = product_id else {
        return false;
    };
    let s = match val {
        Value::Null => return false,
        Value::String(s) => s.trim().to_owned(),
        Value::Number(n) => n.to_string(),
        _ => return false,
    };
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())
}

fn product_id_string(val: &Value) -> Option<String> {
    match val {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

pub fn parse_raw_positions(portfolio_rows: &[Value]) -> Vec<RawPosition> {
    let mut positions = Vec::new();
    for row in portfolio_rows {
        let flat = value_pairs_to_dict(row);
        let size = parse_decimal_or_zero(flat.get("size"));
        if size.is_zero() {
            continue;
        }
        let pid_val = flat
            .get("id")
            .filter(|v| !v.is_null() && v.as_str() != Some(""))
            .or_else(|| row.get("id"));
        if !is_real_product_id(pid_val) {
            continue;
        }
        let price = parse_decimal_or_zero(flat.get("price"));
        let break_even = parse_decimal_opt(flat.get("breakEvenPrice"))
            .filter(|d| !d.is_zero())
            .unwrap_or(price);
        positions.push(RawPosition {
            product_id: pid_val.and_then(product_id_string),
            size,
            price,
            break_even_price: break_even,
        });
    }
    positions
}

pub fn build_product_info_map(data: &Map<String, Value>) -> HashMap<String, ProductInfo> {
    let mut products = HashMap::new();
    for (pid, info) in data {
        let key_val = Value::String(pid.clone());
        if !is_real_product_id(Some(&key_val)) {
            continue;
        }
        products.insert(pid.clone(), sanitize_product_info(info));
    }
    products
}

pub fn build_positions(
    raw_positions: &[RawPosition],
    products: &HashMap<String, ProductInfo>,
) -> Vec<PositionOutput> {
    raw_positions
        .iter()
        .map(|p| {
            let info = p.product_id.as_deref().and_then(|id| products.get(id));
            let fallback_id = p.product_id.clone().unwrap_or_else(|| "None".to_owned());
            let current_price = info
                .and_then(|i| i.close_price)
                .filter(|d| !d.is_zero())
                .unwrap_or(p.price);
            PositionOutput {
                isin: info.and_then(|i| i.isin.clone()),
                symbol: info
                    .and_then(|i| i.symbol.clone())
                    .unwrap_or_else(|| fallback_id.clone()),
                name: info.and_then(|i| i.name.clone()).unwrap_or(fallback_id),
                quantity: p.size,
                buying_price: p.break_even_price,
                current_price,
            }
        })
        .collect()
}
