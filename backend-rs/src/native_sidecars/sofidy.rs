//! Native Rust port of `services/sofidy-auth/` (Sofidy Espace Associé SCPI portal).
//!
//! Endpoints:
//! - `GET /health` -> `{"status": "ok"}`
//! - `POST /initiate` `{associateCode, password}` -> `{processId, mfaRequired, mfaType, sessionState}`
//! - `POST /complete` `{processId, code}` -> `{sessionState}`
//! - `POST /positions` `{sessionState}` -> `{currency, totalEur, valuationDate, holdings, snapshotComplete}`

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, LazyLock};
use std::time::Duration;

use async_trait::async_trait;
use http::{Method, StatusCode};
use regex::Regex;
use rust_decimal::{Decimal, RoundingStrategy};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use url::Url;

use super::common::{
    MAX_RESPONSE_BYTES, MemoryCookieJar, PendingStore, build_http_client, normalize_base_url,
    read_capped_text, safe_log, same_origin_url, unescape_then_strip_html_text,
};
use super::{NativeSidecar, NativeSidecarError};

pub const DEFAULT_BASE_URL: &str = "https://moncompte.sofidy.com";
pub const HOME_PATH: &str = "/1,accueil.html";
pub const LOGIN_PATH: &str = "/scripts/ajax/connex.php";
pub const VERIFY_2FA_PATH: &str = "/scripts/ajax/verify2fa.php";
pub const PORTFOLIO_PATH: &str = "/3,clients.html";
pub const LOGIN_FORM_MARKER: &str = "connexion_espace_partenaire";

const USER_AGENT: &str = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 \
    (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
const PENDING_TTL: Duration = Duration::from_secs(300);
const MAX_PENDING: usize = 16;
const MAX_REDIRECTS: usize = 5;

static ASSOCIATE_CODE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[0-9]{6}$").expect("static regex"));
static OTP_CODE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[0-9]{4,12}$").expect("static regex"));

struct PendingSofidy {
    client: reqwest::Client,
    jar: Arc<MemoryCookieJar>,
    associate_code: String,
    password: String,
}

pub struct NativeSofidy {
    base_url: Url,
    pending: PendingStore<PendingSofidy>,
}

impl Default for NativeSofidy {
    fn default() -> Self {
        Self::with_base_url(DEFAULT_BASE_URL)
    }
}

impl NativeSofidy {
    pub fn with_base_url(base_url: &str) -> Self {
        let normalized = normalize_base_url(base_url);
        let url = Url::parse(&normalized).expect("valid Sofidy base URL");
        Self {
            base_url: url,
            pending: PendingStore::new(PENDING_TTL, Some(MAX_PENDING)),
        }
    }

    fn new_client(&self) -> Result<(reqwest::Client, Arc<MemoryCookieJar>), NativeSidecarError> {
        let jar = MemoryCookieJar::new();
        let client = build_http_client(
            jar.clone(),
            USER_AGENT,
            REQUEST_TIMEOUT,
            &[("Accept-Language", "fr-FR,fr;q=0.9")],
        )?;
        Ok((client, jar))
    }

    /// Sends a GET or form POST, following up to `MAX_REDIRECTS` same-origin redirects.
    /// Rejects any cross-origin, wrong-scheme, wrong-port, or credentialed redirect BEFORE
    /// sending a request (matching `_check_request_origin` in Python).
    async fn send_same_origin(
        &self,
        client: &reqwest::Client,
        initial_method: Method,
        path: &str,
        form: Option<&HashMap<&str, &str>>,
    ) -> Result<(StatusCode, String), NativeSidecarError> {
        let mut method = initial_method;
        let mut url = self.base_url.join(path).map_err(|_| {
            NativeSidecarError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE")
        })?;
        // Validate initial URL too.
        if same_origin_url(&self.base_url, &self.base_url, url.as_str()).is_none() {
            return Err(NativeSidecarError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_UNAVAILABLE",
            ));
        }

        let mut keep_body = form.is_some();
        for hop in 0..=MAX_REDIRECTS {
            let mut req = client.request(method.clone(), url.clone());
            if keep_body && let Some(data) = form {
                req = req.form(data);
            }
            let resp = req.send().await.map_err(|_| {
                tracing::warn!("Sofidy upstream HTTP call failed");
                NativeSidecarError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE")
            })?;
            let status = resp.status();
            if status.is_redirection()
                && let Some(loc_val) = resp.headers().get(http::header::LOCATION)
            {
                if hop == MAX_REDIRECTS {
                    return Err(NativeSidecarError::new(
                        StatusCode::BAD_GATEWAY,
                        "UPSTREAM_UNAVAILABLE",
                    ));
                }
                let Ok(location) = loc_val.to_str() else {
                    return Err(NativeSidecarError::new(
                        StatusCode::BAD_GATEWAY,
                        "UPSTREAM_UNAVAILABLE",
                    ));
                };
                // Never log rejected target URLs (may carry query secrets/tokens).
                url = same_origin_url(&self.base_url, &url, location).ok_or_else(|| {
                    NativeSidecarError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE")
                })?;
                if matches!(
                    status,
                    StatusCode::MOVED_PERMANENTLY | StatusCode::FOUND | StatusCode::SEE_OTHER
                ) {
                    method = Method::GET;
                    keep_body = false;
                }
                continue;
            }
            let text = read_capped_text(resp, MAX_RESPONSE_BYTES)
                .await
                .map_err(|_| {
                    NativeSidecarError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE")
                })?;
            return Ok((status, text));
        }
        Err(NativeSidecarError::new(
            StatusCode::BAD_GATEWAY,
            "UPSTREAM_UNAVAILABLE",
        ))
    }

    async fn fetch_home(&self, client: &reqwest::Client) -> Result<String, NativeSidecarError> {
        let (status, text) = self
            .send_same_origin(client, Method::GET, HOME_PATH, None)
            .await?;
        if status != StatusCode::OK {
            return Err(NativeSidecarError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_UNAVAILABLE",
            ));
        }
        Ok(text)
    }

    async fn post_portal(
        &self,
        client: &reqwest::Client,
        path: &str,
        data: &HashMap<&str, &str>,
    ) -> Result<String, NativeSidecarError> {
        let (status, text) = self
            .send_same_origin(client, Method::POST, path, Some(data))
            .await?;
        if status.is_server_error() || status == StatusCode::TOO_MANY_REQUESTS {
            return Err(NativeSidecarError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_UNAVAILABLE",
            ));
        }
        if status != StatusCode::OK {
            tracing::warn!(
                "Sofidy request refused (path={}; status={})",
                safe_log(path, 120),
                status.as_u16()
            );
            return Err(NativeSidecarError::new(
                StatusCode::UNAUTHORIZED,
                "INVALID_CREDENTIALS",
            ));
        }
        Ok(text.trim().to_owned())
    }

    async fn handle_initiate(&self, body: Value) -> Result<Value, NativeSidecarError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct Req {
            associate_code: String,
            password: String,
        }
        let req: Req = serde_json::from_value(body)
            .map_err(|_| NativeSidecarError::new(StatusCode::BAD_REQUEST, "INVALID_REQUEST"))?;
        let associate_code = req.associate_code.trim().to_owned();
        let password = req.password.trim().to_owned();
        if !ASSOCIATE_CODE_RE.is_match(&associate_code)
            || password.is_empty()
            || password.len() > 200
        {
            return Err(NativeSidecarError::new(
                StatusCode::BAD_REQUEST,
                "INVALID_REQUEST",
            ));
        }

        self.pending.expire().await;
        let (client, jar) = self.new_client()?;
        self.fetch_home(&client).await?;

        let mut form = HashMap::new();
        form.insert("user", associate_code.as_str());
        form.insert("pass", password.as_str());
        let raw = self.post_portal(&client, LOGIN_PATH, &form).await?;

        if let Some((detail, status)) = classify_login(&raw) {
            tracing::warn!("Sofidy login rejected (code={detail})");
            return Err(NativeSidecarError::new(status, detail));
        }

        if raw.starts_with("2FA|") {
            let process_id = uuid::Uuid::new_v4().to_string();
            self.pending
                .put_or_err(
                    process_id.clone(),
                    PendingSofidy {
                        client,
                        jar,
                        associate_code,
                        password,
                    },
                    "TOO_MANY_PENDING",
                )
                .await?;
            return Ok(json!({
                "processId": process_id,
                "mfaRequired": true,
                "mfaType": "EMAIL_CODE",
                "sessionState": Value::Null,
            }));
        }

        if !is_logged_in(&self.fetch_home(&client).await?) {
            tracing::warn!("Sofidy login answered without opening a session");
            return Err(NativeSidecarError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_FORMAT_CHANGED",
            ));
        }

        Ok(json!({
            "processId": Value::Null,
            "mfaRequired": false,
            "mfaType": Value::Null,
            "sessionState": jar.serialize_json(),
        }))
    }

    async fn handle_complete(&self, body: Value) -> Result<Value, NativeSidecarError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct Req {
            process_id: String,
            code: String,
        }
        let req: Req = serde_json::from_value(body)
            .map_err(|_| NativeSidecarError::new(StatusCode::BAD_REQUEST, "INVALID_REQUEST"))?;
        let process_id = req.process_id.trim().to_owned();
        let code = req.code.trim().to_owned();
        if process_id.is_empty() || process_id.len() > 64 || !OTP_CODE_RE.is_match(&code) {
            return Err(NativeSidecarError::new(
                StatusCode::BAD_REQUEST,
                "INVALID_REQUEST",
            ));
        }

        let Some(state) = self.pending.take(&process_id).await else {
            return Err(NativeSidecarError::new(
                StatusCode::GONE,
                "AUTH_ATTEMPT_EXPIRED",
            ));
        };

        let mut form = HashMap::new();
        form.insert("user", state.associate_code.as_str());
        form.insert("pass", state.password.as_str());
        form.insert("code", code.as_str());
        let raw = self
            .post_portal(&state.client, VERIFY_2FA_PATH, &form)
            .await?
            .trim()
            .to_ascii_lowercase();

        if raw != "fauxyes" && raw != "firstyes" {
            self.pending
                .put_or_err(process_id, state, "TOO_MANY_PENDING")
                .await?;
            return Err(NativeSidecarError::new(
                StatusCode::UNAUTHORIZED,
                "MFA_INVALID",
            ));
        }

        if !is_logged_in(&self.fetch_home(&state.client).await?) {
            tracing::warn!("Sofidy verification succeeded without opening a session");
            return Err(NativeSidecarError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_FORMAT_CHANGED",
            ));
        }

        Ok(json!({ "sessionState": state.jar.serialize_json() }))
    }

    async fn handle_positions(&self, body: Value) -> Result<Value, NativeSidecarError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct Req {
            session_state: String,
        }
        let req: Req = serde_json::from_value(body)
            .map_err(|_| NativeSidecarError::new(StatusCode::BAD_REQUEST, "INVALID_REQUEST"))?;
        let session_state = req.session_state.trim();
        if session_state.is_empty() {
            return Err(NativeSidecarError::new(
                StatusCode::BAD_REQUEST,
                "INVALID_REQUEST",
            ));
        }

        let (client, jar) = self.new_client()?;
        jar.restore_json(session_state, "INVALID_REQUEST")?;

        let home = self.fetch_home(&client).await?;
        if !is_logged_in(&home) {
            return Err(NativeSidecarError::new(
                StatusCode::UNAUTHORIZED,
                "SESSION_EXPIRED",
            ));
        }

        let (status, html) = self
            .send_same_origin(&client, Method::GET, PORTFOLIO_PATH, None)
            .await?;
        if matches!(status, StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN) {
            return Err(NativeSidecarError::new(
                StatusCode::UNAUTHORIZED,
                "SESSION_EXPIRED",
            ));
        }
        if status == StatusCode::TOO_MANY_REQUESTS || status.is_server_error() {
            return Err(NativeSidecarError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_UNAVAILABLE",
            ));
        }
        if status != StatusCode::OK {
            return Err(NativeSidecarError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_FORMAT_CHANGED",
            ));
        }
        if !is_logged_in(&html) {
            return Err(NativeSidecarError::new(
                StatusCode::UNAUTHORIZED,
                "SESSION_EXPIRED",
            ));
        }

        let snapshot = parse_portfolio(&html).map_err(|exc| {
            tracing::warn!("Sofidy payload rejected (code={})", exc.code);
            NativeSidecarError::new(StatusCode::BAD_GATEWAY, exc.code)
        })?;
        Ok(serde_json::to_value(snapshot).expect("serialize Sofidy snapshot"))
    }
}

fn is_logged_in(page: &str) -> bool {
    !page.contains(LOGIN_FORM_MARKER)
}

fn classify_login(raw: &str) -> Option<(&'static str, StatusCode)> {
    let text = raw.trim();
    if text.starts_with("2FA|") {
        return None;
    }
    if text.starts_with('{') || text.starts_with('[') {
        return Some(("RATE_LIMITED", StatusCode::TOO_MANY_REQUESTS));
    }
    match text {
        "faux" => Some(("INVALID_CREDENTIALS", StatusCode::UNAUTHORIZED)),
        "compteur_4" | "compteur5" | "24_mail" => {
            Some(("RATE_LIMITED", StatusCode::TOO_MANY_REQUESTS))
        }
        "inactif" => Some(("ACCOUNT_INACTIVE", StatusCode::FORBIDDEN)),
        "mailko" => Some(("EMAIL_UNREACHABLE", StatusCode::FORBIDDEN)),
        "firstyes" => Some(("FIRST_VISIT_PENDING", StatusCode::CONFLICT)),
        _ => None,
    }
}

#[async_trait]
impl NativeSidecar for NativeSofidy {
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
            (Method::POST, "/positions") => self.handle_positions(body).await,
            _ => Err(NativeSidecarError::new(StatusCode::NOT_FOUND, "Not Found")),
        }
    }
}

// ─── Portfolio parser (port of services/sofidy-auth/positions_parser.py) ────

#[derive(Debug, Clone)]
pub struct PositionsFormatError {
    pub code: &'static str,
    pub message: String,
}

impl PositionsFormatError {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HoldingPayload {
    pub fund_code: String,
    pub label: String,
    pub quantity: Decimal,
    pub withdrawal_price: Option<Decimal>,
    pub total_eur: Decimal,
    pub snapshot_complete: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotPayload {
    pub currency: &'static str,
    pub total_eur: Decimal,
    pub valuation_date: Option<String>,
    pub holdings: Vec<HoldingPayload>,
    pub snapshot_complete: bool,
}

static ROW_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?is)<tr\b[^>]*>(.*?)</tr>").expect("static regex"));
static CELL_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?is)<td[^>]*>(.*?)</td>").expect("static regex"));
static CODE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"Code_Produit=([A-Za-z0-9_-]+)").expect("static regex"));
static VALUATION_DATE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)Portefeuille\s+SCPI\s+au\s+(\d{2}/\d{2}/\d{4})").expect("static regex")
});
static HEADER_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)Nombre\s+de\s+parts").expect("static regex"));
static TABLE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?is)<table\b[^>]*>.*?</table>").expect("static regex"));
static TOTAL_ROW_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?is)<td>\s*Total\s*</td>(.*?)</tr>").expect("static regex"));

fn cell_texts(row_html: &str) -> Vec<String> {
    CELL_RE
        .captures_iter(row_html)
        .map(|cap| unescape_then_strip_html_text(&cap[1]))
        .collect()
}

fn parse_decimal(raw: &str, field: &str) -> Result<Decimal, PositionsFormatError> {
    let value = raw.trim().replace(['€', '\u{00A0}', ' '], "");
    if value.is_empty() {
        return Err(PositionsFormatError::new(
            "INVALID_DATA",
            format!("{field} is empty"),
        ));
    }
    if value.contains(',') {
        return Err(PositionsFormatError::new(
            "UPSTREAM_FORMAT_CHANGED",
            format!("{field} is comma-formatted"),
        ));
    }
    value
        .parse::<Decimal>()
        .map_err(|_| PositionsFormatError::new("INVALID_DATA", format!("{field} is not a number")))
}

fn parse_optional_price(raw: &str) -> Result<Option<Decimal>, PositionsFormatError> {
    if !raw.chars().any(|c| c.is_ascii_digit()) {
        return Ok(None);
    }
    parse_decimal(raw, "withdrawal price").map(Some)
}

fn parse_holding(row_html: &str) -> Result<HoldingPayload, PositionsFormatError> {
    let cells = cell_texts(row_html);
    if cells.len() < 5 {
        return Err(PositionsFormatError::new(
            "UPSTREAM_FORMAT_CHANGED",
            "portfolio row has too few cells",
        ));
    }
    let label = &cells[0];
    let parts = &cells[1];
    let unit_value = &cells[3];
    let total = &cells[4];
    if label.is_empty() {
        return Err(PositionsFormatError::new(
            "INVALID_DATA",
            "fund label is empty",
        ));
    }
    let code = CODE_RE
        .captures(row_html)
        .map(|c| c[1].to_owned())
        .ok_or_else(|| {
            PositionsFormatError::new(
                "UPSTREAM_FORMAT_CHANGED",
                "fund row carries no Code_Produit",
            )
        })?;
    Ok(HoldingPayload {
        fund_code: code,
        label: label.clone(),
        quantity: parse_decimal(parts, "share count")?,
        withdrawal_price: parse_optional_price(unit_value)?,
        total_eur: parse_decimal(total, "total")?,
        snapshot_complete: true,
    })
}

fn parse_valuation_date(html: &str) -> Option<String> {
    let cap = VALUATION_DATE_RE.captures(html)?;
    let mut parts = cap[1].split('/');
    let day = parts.next()?;
    let month = parts.next()?;
    let year = parts.next()?;
    Some(format!("{year}-{month}-{day}"))
}

fn portfolio_table(html: &str) -> Result<&str, PositionsFormatError> {
    for m in TABLE_RE.find_iter(html) {
        if HEADER_RE.is_match(m.as_str()) {
            return Ok(m.as_str());
        }
    }
    Err(PositionsFormatError::new(
        "UPSTREAM_FORMAT_CHANGED",
        "no portfolio table on the page",
    ))
}

fn declared_total(table: &str) -> Result<Decimal, PositionsFormatError> {
    let cap = TOTAL_ROW_RE.captures(table).ok_or_else(|| {
        PositionsFormatError::new(
            "UPSTREAM_FORMAT_CHANGED",
            "the portfolio page carries fund rows but no total row",
        )
    })?;
    let cells = cell_texts(&cap[1]);
    let values: Vec<&str> = cells
        .iter()
        .map(String::as_str)
        .filter(|c| !c.trim().is_empty())
        .collect();
    if values.len() < 2 {
        return Err(PositionsFormatError::new(
            "UPSTREAM_FORMAT_CHANGED",
            "the portfolio total row has no value",
        ));
    }
    parse_decimal(values[values.len() - 1], "total row")
}

fn check_totals(table: &str, holdings: &[HoldingPayload]) -> Result<Decimal, PositionsFormatError> {
    let declared = declared_total(table)?;
    let computed: Decimal = holdings
        .iter()
        .map(|h| h.total_eur)
        .sum::<Decimal>()
        .round_dp_with_strategy(2, RoundingStrategy::MidpointNearestEven);
    if (computed - declared).abs() > Decimal::new(1, 2) {
        return Err(PositionsFormatError::new(
            "PORTFOLIO_INCOMPLETE",
            format!("funds total {computed} but the page declares {declared}"),
        ));
    }
    Ok(declared)
}

pub fn parse_portfolio(html: &str) -> Result<SnapshotPayload, PositionsFormatError> {
    if html.contains(LOGIN_FORM_MARKER) {
        return Err(PositionsFormatError::new(
            "SESSION_EXPIRED",
            "page is the login form",
        ));
    }
    let table = portfolio_table(html)?;

    let mut candidates = Vec::new();
    for cap in ROW_RE.captures_iter(table) {
        let row = cap.get(1).expect("row capture").as_str();
        let cells = cell_texts(row);
        if cells.is_empty() || cells.iter().all(|c| c.trim().is_empty()) {
            continue;
        }
        if cells[0].trim().eq_ignore_ascii_case("total") {
            continue;
        }
        if cells.len() < 5 {
            return Err(PositionsFormatError::new(
                "UPSTREAM_FORMAT_CHANGED",
                "portfolio row has too few cells",
            ));
        }
        candidates.push(row);
    }

    if candidates.is_empty() {
        if !declared_total(table)?.is_zero() {
            return Err(PositionsFormatError::new(
                "PORTFOLIO_INCOMPLETE",
                "no funds but a nonzero total",
            ));
        }
        return Ok(SnapshotPayload {
            currency: "EUR",
            total_eur: Decimal::ZERO,
            valuation_date: parse_valuation_date(html),
            holdings: Vec::new(),
            snapshot_complete: true,
        });
    }

    let mut holdings = Vec::with_capacity(candidates.len());
    let mut seen = HashSet::with_capacity(candidates.len());
    let mut duplicates = Vec::new();
    for row in candidates {
        let holding = parse_holding(row)?;
        if !seen.insert(holding.fund_code.clone()) {
            duplicates.push(holding.fund_code.clone());
        }
        holdings.push(holding);
    }
    if !duplicates.is_empty() {
        duplicates.sort();
        duplicates.dedup();
        return Err(PositionsFormatError::new(
            "UPSTREAM_FORMAT_CHANGED",
            format!("duplicate fund codes: {}", duplicates.join(", ")),
        ));
    }

    let total_eur = check_totals(table, &holdings)?;
    Ok(SnapshotPayload {
        currency: "EUR",
        total_eur,
        valuation_date: parse_valuation_date(html),
        holdings,
        snapshot_complete: true,
    })
}
