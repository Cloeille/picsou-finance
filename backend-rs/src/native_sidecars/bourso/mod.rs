//! Native Rust port of `services/bourso-auth` (BoursoBank authentication and accounts sidecar).
//!
//! Speaks BoursoBank's web front end and trading-board JSON API directly over HTTPS
//! using an isolated per-request / per-attempt `SessionCookieJar`.

pub mod accounts_parser;
pub mod cookies;
pub mod html;
pub mod virtual_pad;

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant};

use chrono::{Local, NaiveDate, TimeDelta};
use http::{Method, StatusCode};
use regex::Regex;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::sync::Mutex;
use url::Url;
use uuid::Uuid;

use self::accounts_parser::{
    AccountKind, AccountsFormatError, FORMAT_CHANGED, MovementTransaction, PositionPayload,
    choose_personal_identity_for_origin, guard_symbol_collisions, parse_dashboard, parse_iban_page,
    parse_life_insurance, parse_operations, parse_per, parse_trading_summary,
};
use self::cookies::SessionCookieJar;
use self::html::html_unescape;
use self::virtual_pad::{encode_password, extract_challenge, parse_virtual_pad};
use crate::native_sidecars::common;
use crate::native_sidecars::{
    NativeCallResult, NativeError, NativeResponse, method_not_allowed, not_found,
};

pub const DEFAULT_BASE_URL: &str = "https://clients.boursobank.com";
pub const DEFAULT_COOKIE_DOMAIN: &str = ".boursobank.com";

pub const LOGIN_PATH: &str = "/connexion/";
pub const VIRTUAL_PAD_PATH: &str = "/connexion/clavier-virtuel?_hinclude=1";
pub const PASSWORD_PATH: &str = "/connexion/saisie-mot-de-passe";
pub const SECURISATION_PATH: &str = "/securisation";
pub const FRAUD_EDUCATION_PATH: &str = "/infos-profil/pedagogie-fraude";
pub const VALIDATION_PATH: &str = "/securisation/validation";
pub const ACCOUNTS_PATH: &str =
    "/dashboard/liste-comptes?rumroute=dashboard.new_accounts&_hinclude=1";
pub const IDENTITY_SELECTOR_PATH: &str = "/connexion/lister-identites";
pub const PROFILE_NOTICE_PATH: &str = "/infos-profil/";
pub const DISMISS_NOTICE_PATH: &str = "/ignorer-bloquante/";

pub const PENDING_TTL_SECONDS: u64 = 600;
pub const APP_VALIDATION_TIMEOUT_SECONDS: u64 = 120;
pub const APP_VALIDATION_POLL_SECONDS: u64 = 2;
pub const REQUEST_TIMEOUT_SECONDS: u64 = 30;
pub const MAX_MOVEMENT_PAGES: usize = 20;
pub const ACCOUNTS_HISTORY_DEADLINE_SECONDS: u64 = 60;
pub const MAX_PENDING: usize = 16;
const MAX_REDIRECTS: usize = 10;

const USER_AGENT: &str = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 \
                          (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

const LOGGED_IN_MARKER: &str = r#"href="/se-deconnecter""#;
const BAD_CREDENTIALS_MARKERS: [&str; 3] = [
    "Identifiant ou mot de passe invalide",
    "Erreur d&#039;authentification",
    "Erreur d'authentification",
];

static BRS_MIT_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"__brs_mit=(?P<value>[^;"'\s]+)"#).unwrap());
static FORM_TOKEN_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r#"name="form\[_token\]"[^>]*?value="(?P<token>[^"]*)"|value="(?P<token_first>[^"]*)"[^>]*?name="form\[_token\]""#,
    )
    .unwrap()
});
static API_URL_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#""API_URL"\s*:\s*"(?P<url>[^"]+)""#).unwrap());
static USER_HASH_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#""USER_HASH"\s*:\s*"(?P<hash>[^"]+)""#).unwrap());
static STRONG_AUTH_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"data-strong-authentication-payload="(?P<payload>[^"]*)""#).unwrap()
});
static OTHER_OTP_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"brs-otp-(?P<kind>sms|email)").unwrap());
static HREF_DOUBLE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?i)href\s*=\s*"([^"]+)""#).unwrap());
static HREF_SINGLE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)href\s*=\s*'([^']+)'").unwrap());
static CURRENCY_CODE_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[A-Z]{3}$").unwrap());
static SAFE_PATH_SEGMENT_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Za-z0-9_-]{1,128}$").unwrap());

/// Configuration for `NativeBourso`, allowing tests to point at `wiremock` and tighten timeouts.
#[derive(Debug, Clone)]
pub struct BoursoConfig {
    pub base_url: String,
    pub bootstrap_cookie_domain: String,
    pub request_timeout: Duration,
    pub app_validation_timeout: Duration,
    pub app_validation_poll: Duration,
    pub accounts_history_deadline: Duration,
    pub pending_ttl: Duration,
    pub max_pending: usize,
    pub today_override: Option<NaiveDate>,
}

impl Default for BoursoConfig {
    fn default() -> Self {
        Self {
            base_url: DEFAULT_BASE_URL.to_owned(),
            bootstrap_cookie_domain: DEFAULT_COOKIE_DOMAIN.to_owned(),
            request_timeout: Duration::from_secs(REQUEST_TIMEOUT_SECONDS),
            app_validation_timeout: Duration::from_secs(APP_VALIDATION_TIMEOUT_SECONDS),
            app_validation_poll: Duration::from_secs(APP_VALIDATION_POLL_SECONDS),
            accounts_history_deadline: Duration::from_secs(ACCOUNTS_HISTORY_DEADLINE_SECONDS),
            pending_ttl: Duration::from_secs(PENDING_TTL_SECONDS),
            max_pending: MAX_PENDING,
            today_override: None,
        }
    }
}

impl BoursoConfig {
    /// Build a test configuration pointing at a custom base URL (e.g. a `wiremock::MockServer`).
    pub fn with_base_url(base_url: impl Into<String>) -> Self {
        let base_url = base_url.into().trim_end_matches('/').to_owned();
        let bootstrap_cookie_domain = Url::parse(&base_url)
            .ok()
            .and_then(|u| u.host_str().map(str::to_owned))
            .unwrap_or_else(|| DEFAULT_COOKIE_DOMAIN.to_owned());
        Self {
            base_url,
            bootstrap_cookie_domain,
            ..Self::default()
        }
    }
}

// ─── Wire contract types ────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InitiateResponse {
    pub process_id: Option<String>,
    pub mfa_required: bool,
    pub mfa_type: Option<String>,
    pub session_state: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionResponse {
    pub session_state: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountPayload {
    pub external_id: String,
    pub name: String,
    #[serde(rename = "type")]
    pub account_type: AccountKind,
    pub balance_eur: Decimal,
    pub cash_balance: Option<Decimal>,
    pub positions: Vec<PositionPayload>,
    pub valuation_line_count: Option<usize>,
    pub iban: Option<String>,
    pub transactions: Vec<MovementTransaction>,
    pub transaction_warning: Option<String>,
    pub snapshot_complete: bool,
}

impl AccountPayload {
    fn validate(&self) -> Result<(), NativeError> {
        let invalid = || NativeError::new(StatusCode::BAD_GATEWAY, "INVALID_DATA");
        if self.external_id.is_empty()
            || self.external_id.chars().count() > 100
            || self.name.is_empty()
            || self.name.chars().count() > 200
            || !self.snapshot_complete
        {
            return Err(invalid());
        }
        for pos in &self.positions {
            if pos.symbol.is_empty()
                || pos.symbol.chars().count() > 100
                || pos.label.is_empty()
                || pos.label.chars().count() > 200
            {
                return Err(invalid());
            }
            if let Some(ref isin) = pos.isin
                && isin.chars().count() > 12
            {
                return Err(invalid());
            }
            if let Some(ref cur) = pos.quote_currency
                && !CURRENCY_CODE_RE.is_match(cur)
            {
                return Err(invalid());
            }
        }
        Ok(())
    }
}

struct ParsedInitiate {
    customer_id: String,
    password: String,
}

struct ParsedComplete {
    process_id: String,
}

struct ParsedAccounts {
    session_state: String,
}

fn parse_initiate_request(body: Option<Value>) -> Result<ParsedInitiate, NativeError> {
    let invalid = || NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA");
    let obj = body
        .and_then(|v| v.as_object().cloned())
        .ok_or_else(invalid)?;
    if obj.keys().any(|k| k != "customerId" && k != "password") {
        return Err(invalid());
    }
    let customer_id = obj
        .get("customerId")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty() && s.chars().count() <= 100)
        .ok_or_else(invalid)?;
    let password = obj
        .get("password")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty() && s.chars().count() <= 100)
        .ok_or_else(invalid)?;
    Ok(ParsedInitiate {
        customer_id: customer_id.to_owned(),
        password: password.to_owned(),
    })
}

fn parse_complete_request(body: Option<Value>) -> Result<ParsedComplete, NativeError> {
    let invalid = || NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA");
    let obj = body
        .and_then(|v| v.as_object().cloned())
        .ok_or_else(invalid)?;
    if let Some(code_val) = obj.get("code")
        && !code_val.is_null()
    {
        // Only app push is supported; a submitted OTP code is refused explicitly.
        return Err(NativeError::new(
            StatusCode::BAD_REQUEST,
            "MFA_TYPE_UNSUPPORTED",
        ));
    }
    if obj.keys().any(|k| k != "processId" && k != "code") {
        return Err(invalid());
    }
    let process_id = obj
        .get("processId")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty() && s.chars().count() <= 100)
        .ok_or_else(invalid)?;
    Ok(ParsedComplete {
        process_id: process_id.to_owned(),
    })
}

fn parse_accounts_request(body: Option<Value>) -> Result<ParsedAccounts, NativeError> {
    let invalid = || NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA");
    let obj = body
        .and_then(|v| v.as_object().cloned())
        .ok_or_else(invalid)?;
    if obj.keys().any(|k| k != "sessionState") {
        return Err(invalid());
    }
    let session_state = obj
        .get("sessionState")
        .and_then(Value::as_str)
        .filter(|s| s.len() >= 2 && s.len() <= 2_000_000)
        .ok_or_else(invalid)?;
    Ok(ParsedAccounts {
        session_state: session_state.to_owned(),
    })
}

// ─── Isolated HTTP session client ───────────────────────────────────────────

struct SessionClient {
    no_redirect: reqwest::Client,
    cookies: Arc<SessionCookieJar>,
    base_url: String,
    base_origin: Url,
}

impl SessionClient {
    fn new(config: &BoursoConfig) -> Result<Self, NativeError> {
        let base_url = config.base_url.trim_end_matches('/').to_owned();
        let base_origin = Url::parse(&base_url)
            .map_err(|_| NativeError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"))?;
        let cookies = Arc::new(SessionCookieJar::new());
        let mut headers = http::HeaderMap::new();
        headers.insert(
            http::header::USER_AGENT,
            http::HeaderValue::from_static(USER_AGENT),
        );
        headers.insert(
            http::header::ACCEPT_LANGUAGE,
            http::HeaderValue::from_static("fr-FR,fr;q=0.9"),
        );

        let no_redirect = reqwest::Client::builder()
            .default_headers(headers)
            .cookie_provider(cookies.clone())
            .redirect(reqwest::redirect::Policy::none())
            .timeout(config.request_timeout)
            .build()
            .map_err(|_| NativeError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"))?;

        Ok(Self {
            no_redirect,
            cookies,
            base_url,
            base_origin,
        })
    }

    fn url(&self, path_or_url: &str) -> String {
        if path_or_url.starts_with("http://") || path_or_url.starts_with("https://") {
            path_or_url.to_owned()
        } else if path_or_url.starts_with('/') {
            format!("{}{path_or_url}", self.base_url)
        } else {
            format!("{}/{path_or_url}", self.base_url)
        }
    }

    async fn get(
        &self,
        path_or_url: &str,
        follow_redirects: bool,
    ) -> Result<reqwest::Response, NativeError> {
        let mut url = common::same_origin_url(&self.base_origin, &self.base_origin, path_or_url)
            .ok_or_else(|| NativeError::new(StatusCode::BAD_GATEWAY, FORMAT_CHANGED))?;
        let mut resp = self
            .no_redirect
            .get(url.clone())
            .send()
            .await
            .map_err(http_err)?;
        if !follow_redirects {
            return Ok(resp);
        }
        for hop in 0..=MAX_REDIRECTS {
            if !resp.status().is_redirection() {
                return Ok(resp);
            }
            let Some(loc_val) = resp.headers().get(http::header::LOCATION) else {
                return Ok(resp);
            };
            if hop == MAX_REDIRECTS {
                return Err(NativeError::new(StatusCode::BAD_GATEWAY, FORMAT_CHANGED));
            }
            let location = loc_val
                .to_str()
                .map_err(|_| NativeError::new(StatusCode::BAD_GATEWAY, FORMAT_CHANGED))?;
            url = common::same_origin_url(&self.base_origin, &url, location)
                .ok_or_else(|| NativeError::new(StatusCode::BAD_GATEWAY, FORMAT_CHANGED))?;
            resp = self
                .no_redirect
                .get(url.clone())
                .send()
                .await
                .map_err(http_err)?;
        }
        Err(NativeError::new(StatusCode::BAD_GATEWAY, FORMAT_CHANGED))
    }

    async fn get_with_header(
        &self,
        path_or_url: &str,
        header_name: &'static str,
        header_value: &'static str,
    ) -> Result<reqwest::Response, NativeError> {
        let url = common::same_origin_url(&self.base_origin, &self.base_origin, path_or_url)
            .ok_or_else(|| NativeError::new(StatusCode::BAD_GATEWAY, FORMAT_CHANGED))?;
        self.no_redirect
            .get(url)
            .header(header_name, header_value)
            .send()
            .await
            .map_err(http_err)
    }
}

// ─── Pending second-factor store ────────────────────────────────────────────

struct PendingState {
    session: SessionClient,
    api_url: String,
    user_hash: String,
    resource_id: String,
    form_state: String,
    validation_token: String,
    created_at: Instant,
}

#[derive(Default)]
struct PendingMap {
    states: HashMap<String, PendingState>,
}

// ─── Pure page extractors (public for unit/integration tests) ───────────────

pub fn extract_form_token(page: &str) -> Result<String, AccountsFormatError> {
    if let Some(cap) = FORM_TOKEN_RE.captures(page) {
        for name in ["token", "token_first"] {
            if let Some(mat) = cap.name(name) {
                let val = mat.as_str().trim();
                if !val.is_empty() {
                    return Ok(val.to_owned());
                }
            }
        }
    }
    Err(AccountsFormatError::new(
        FORMAT_CHANGED,
        "Login page carried no form token",
    ))
}

fn validate_path_segment(value: &str, label: &str) -> Result<(), AccountsFormatError> {
    if !SAFE_PATH_SEGMENT_RE.is_match(value) {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            format!("BoursoBank payload carried an invalid {label}"),
        ));
    }
    Ok(())
}

fn is_boursobank_host(host: Option<&str>) -> bool {
    let Some(host) = host else {
        return false;
    };
    let lowered = host.to_ascii_lowercase();
    if lowered == "boursobank.com" {
        return true;
    }
    let Some(prefix) = lowered.strip_suffix(".boursobank.com") else {
        return false;
    };
    !prefix.is_empty()
        && prefix.split('.').all(|label| {
            let b = label.as_bytes();
            !b.is_empty()
                && b.len() <= 63
                && b[0].is_ascii_alphanumeric()
                && b[b.len() - 1].is_ascii_alphanumeric()
                && b.iter()
                    .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-')
        })
}

pub fn validate_api_url(api_url: &str, base_url: &str) -> Result<String, AccountsFormatError> {
    let parsed = Url::parse(api_url).map_err(|_| {
        AccountsFormatError::new(FORMAT_CHANGED, "Home page carried an unparseable API_URL")
    })?;
    if !parsed.username().is_empty()
        || parsed.password().is_some()
        || parsed.query().is_some()
        || parsed.fragment().is_some()
    {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Home page API_URL carried forbidden credentials, query, or fragment",
        ));
    }
    let base = Url::parse(base_url).map_err(|_| {
        AccountsFormatError::new(FORMAT_CHANGED, "Invalid configured BoursoBank base URL")
    })?;
    let same_origin_as_base = parsed.scheme() == base.scheme()
        && parsed.host_str() == base.host_str()
        && parsed.port_or_known_default() == base.port_or_known_default();
    let allowlisted_bourso = parsed.scheme() == "https"
        && matches!(parsed.port(), None | Some(443))
        && is_boursobank_host(parsed.host_str());
    if !same_origin_as_base && !allowlisted_bourso {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Home page API_URL left the expected BoursoBank origin",
        ));
    }
    Ok(api_url.trim_end_matches('/').to_owned())
}

pub fn extract_brs_config(page: &str) -> Result<(String, String), AccountsFormatError> {
    let api_url = API_URL_RE
        .captures(page)
        .and_then(|c| c.name("url"))
        .map(|m| m.as_str().trim())
        .filter(|s| !s.is_empty());
    let user_hash = USER_HASH_RE
        .captures(page)
        .and_then(|c| c.name("hash"))
        .map(|m| m.as_str().trim())
        .filter(|s| !s.is_empty());
    let (Some(api_url), Some(user_hash)) = (api_url, user_hash) else {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Home page carried no API_URL/USER_HASH",
        ));
    };
    validate_path_segment(user_hash, "USER_HASH")?;
    let unescaped = api_url.replace("\\/", "/").trim_end_matches('/').to_owned();
    Ok((unescaped, user_hash.to_owned()))
}

pub fn is_fraud_education_page(home: &str) -> bool {
    home.contains(FRAUD_EDUCATION_PATH)
}

/// The strong-authentication page or a page below it. Decided on a final URL or a redirect
/// target, never on the text: that page carries the logout link too (#845), and a signed-in page
/// may link to `/securisation`.
fn is_securisation_path(path: &str) -> bool {
    path.trim_end_matches('/')
        .strip_prefix(SECURISATION_PATH)
        .is_some_and(|rest| rest.is_empty() || rest.starts_with('/'))
}

pub fn strong_auth_params(page: &str) -> Result<(String, String), AccountsFormatError> {
    let raw = STRONG_AUTH_RE
        .captures(page)
        .and_then(|c| c.name("payload"))
        .map(|m| m.as_str().trim())
        .filter(|s| !s.is_empty())
        .ok_or_else(|| {
            AccountsFormatError::new(
                FORMAT_CHANGED,
                "Securisation page carried no authentication payload",
            )
        })?;
    let payload: Value = serde_json::from_str(&html_unescape(raw)).map_err(|_| {
        AccountsFormatError::new(
            FORMAT_CHANGED,
            "Authentication challenge payload has an unknown shape",
        )
    })?;
    let params = payload
        .get("challenges")
        .and_then(Value::as_array)
        .and_then(|arr| arr.first())
        .and_then(|c| c.get("parameters"))
        .and_then(|p| p.get("formScreen"))
        .and_then(|f| f.get("actions"))
        .and_then(|a| a.get("check"))
        .and_then(|c| c.get("api"))
        .and_then(|a| a.get("params"))
        .ok_or_else(|| {
            AccountsFormatError::new(
                FORMAT_CHANGED,
                "Authentication challenge payload has an unknown shape",
            )
        })?;
    let resource_id = params
        .get("resourceId")
        .and_then(Value::as_str)
        .ok_or_else(|| {
            AccountsFormatError::new(
                FORMAT_CHANGED,
                "Authentication challenge is missing resourceId/formState",
            )
        })?;
    let form_state = params
        .get("formState")
        .and_then(Value::as_str)
        .ok_or_else(|| {
            AccountsFormatError::new(
                FORMAT_CHANGED,
                "Authentication challenge is missing resourceId/formState",
            )
        })?;
    validate_path_segment(resource_id, "resourceId")?;
    Ok((resource_id.to_owned(), form_state.to_owned()))
}

pub fn json_success_body(body: &str) -> bool {
    serde_json::from_str::<Value>(body)
        .ok()
        .and_then(|v| v.as_object()?.get("success")?.as_bool())
        == Some(true)
}

pub fn redirects_to_identity_selector(
    status: StatusCode,
    location: Option<&str>,
    base_url: &str,
) -> bool {
    if !status.is_redirection() {
        return false;
    }
    let Ok(origin) = Url::parse(base_url) else {
        return false;
    };
    let Some(target) = common::same_origin_url(&origin, &origin, location.unwrap_or("")) else {
        return false;
    };
    target.path().trim_end_matches('/') == IDENTITY_SELECTOR_PATH
}

pub fn redirects_to_profile_notice(
    status: StatusCode,
    location: Option<&str>,
    base_url: &str,
) -> bool {
    if !status.is_redirection() {
        return false;
    }
    let Ok(origin) = Url::parse(base_url) else {
        return false;
    };
    let Some(target) = common::same_origin_url(&origin, &origin, location.unwrap_or("")) else {
        return false;
    };
    target.path().starts_with(PROFILE_NOTICE_PATH)
}

fn redirects_to_securisation(status: StatusCode, location: Option<&str>, base_url: &str) -> bool {
    status.is_redirection()
        && Url::parse(base_url)
            .ok()
            .and_then(|origin| common::same_origin_url(&origin, &origin, location.unwrap_or("")))
            .is_some_and(|target| is_securisation_path(target.path()))
}

pub fn notice_dismiss_link(page: &str, base_url: &str) -> Option<String> {
    let origin = Url::parse(base_url).ok()?;
    for pat in [&*HREF_DOUBLE_RE, &*HREF_SINGLE_RE] {
        for cap in pat.captures_iter(page) {
            let link = html_unescape(cap.get(1).unwrap().as_str());
            if let Ok(resolved) = origin.join(&link)
                && resolved.path().starts_with(DISMISS_NOTICE_PATH)
            {
                return Some(link);
            }
        }
    }
    None
}

pub fn same_origin_url(value: &str, base: &str, expected_origin: &str) -> Result<String, String> {
    let base_parsed = Url::parse(base).map_err(|_| "Invalid base URL".to_owned())?;
    let origin = Url::parse(expected_origin).map_err(|_| "Invalid origin URL".to_owned())?;
    common::same_origin_url(&origin, &base_parsed, value)
        .map(String::from)
        .ok_or_else(|| "BoursoBank link left the expected origin".to_owned())
}

// ─── NativeBourso sidecar ───────────────────────────────────────────────────

#[derive(Clone)]
pub struct NativeBourso {
    config: BoursoConfig,
    pending: Arc<Mutex<PendingMap>>,
}

impl Default for NativeBourso {
    fn default() -> Self {
        Self::new()
    }
}

impl NativeBourso {
    pub fn new() -> Self {
        Self::with_config(BoursoConfig::default())
    }

    pub fn with_base_url(base_url: impl Into<String>) -> Self {
        Self::with_config(BoursoConfig::with_base_url(base_url))
    }

    pub fn with_config(config: BoursoConfig) -> Self {
        Self {
            config,
            pending: Arc::new(Mutex::new(PendingMap::default())),
        }
    }

    /// Number of parked MFA login attempts (for lifecycle tests & observability).
    pub async fn pending_len(&self) -> usize {
        self.pending.lock().await.states.len()
    }

    /// Reclaim expired parked login attempts.
    pub async fn expire_pending(&self) {
        let ttl = self.config.pending_ttl;
        let mut guard = self.pending.lock().await;
        guard
            .states
            .retain(|_, state| state.created_at.elapsed() < ttl);
    }

    /// Drop every parked login attempt (shutdown cleanup).
    pub async fn close_all_pending(&self) {
        self.pending.lock().await.states.clear();
    }

    /// Main dispatch entry point returning `Result<Value, (StatusCode, String)>`.
    pub async fn call(&self, method: Method, path: &str, body: Option<Value>) -> NativeCallResult {
        self.call_raw(method, path, body)
            .await
            .map(|resp| resp.body)
            .map_err(NativeError::into_tuple)
    }

    /// Structured dispatch entry point returning `Result<NativeResponse, NativeError>`.
    pub async fn call_raw(
        &self,
        method: Method,
        path: &str,
        body: Option<Value>,
    ) -> Result<NativeResponse, NativeError> {
        let route = path.split('?').next().unwrap_or(path);
        match (method.clone(), route) {
            (Method::GET, "/health") => Ok(NativeResponse::ok(json!({ "status": "ok" }))),
            (Method::POST, "/initiate") => {
                let req = parse_initiate_request(body)?;
                let resp = self.initiate(req).await?;
                let value = serde_json::to_value(resp).map_err(|_| {
                    NativeError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
                })?;
                Ok(NativeResponse::ok(value))
            }
            (Method::POST, "/complete") => {
                let req = parse_complete_request(body)?;
                let resp = self.complete(req).await?;
                let value = serde_json::to_value(resp).map_err(|_| {
                    NativeError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
                })?;
                Ok(NativeResponse::ok(value))
            }
            (Method::POST, "/accounts") => {
                let req = parse_accounts_request(body)?;
                let resp = self.accounts(req).await?;
                let value = serde_json::to_value(resp).map_err(|_| {
                    NativeError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
                })?;
                Ok(NativeResponse::ok(value))
            }
            (_, "/health" | "/initiate" | "/complete" | "/accounts") => {
                let (status, detail) = method_not_allowed(&method);
                Err(NativeError::new(status, detail))
            }
            _ => {
                let (status, detail) = not_found();
                Err(NativeError::new(status, detail))
            }
        }
    }

    // ─── Pending store helpers ──────────────────────────────────────────────

    async fn park_pending(
        &self,
        process_id: String,
        state: PendingState,
    ) -> Result<(), NativeError> {
        let ttl = self.config.pending_ttl;
        let max_size = self.config.max_pending;
        let mut guard = self.pending.lock().await;
        if guard.states.len() >= max_size {
            guard.states.retain(|_, s| s.created_at.elapsed() < ttl);
        }
        if guard.states.len() >= max_size {
            return Err(NativeError::new(
                StatusCode::SERVICE_UNAVAILABLE,
                "UPSTREAM_UNAVAILABLE",
            ));
        }
        guard.states.insert(process_id, state);
        Ok(())
    }

    async fn take_pending(&self, process_id: &str) -> Option<PendingState> {
        let ttl = self.config.pending_ttl;
        let state = self.pending.lock().await.states.remove(process_id)?;
        if state.created_at.elapsed() >= ttl {
            return None;
        }
        Some(state)
    }

    // ─── Auth flow ──────────────────────────────────────────────────────────

    async fn initiate(&self, req: ParsedInitiate) -> Result<InitiateResponse, NativeError> {
        self.expire_pending().await;
        let process_id = Uuid::new_v4().to_string();
        let session = SessionClient::new(&self.config)?;

        self.login(&session, &req.customer_id, &req.password)
            .await?;
        let home = self.home(&session).await?;

        if home.signed_in() {
            return Ok(InitiateResponse {
                process_id: None,
                mfa_required: false,
                mfa_type: None,
                session_state: Some(session.cookies.serialize_json()),
            });
        }

        // Without the logout link, a page that merely points at /securisation still asks for
        // the second factor, as it always has.
        if !home.landed_on_securisation && !home.body.contains(SECURISATION_PATH) {
            if is_fraud_education_page(&home.body) {
                return Err(NativeError::new(
                    StatusCode::UNAUTHORIZED,
                    "FRAUD_ACK_REQUIRED",
                ));
            }
            return Err(NativeError::new(
                StatusCode::UNAUTHORIZED,
                "INVALID_CREDENTIALS",
            ));
        }

        let app_push = self.start_app_push(&session).await?;
        self.park_pending(
            process_id.clone(),
            PendingState {
                session,
                api_url: app_push.api_url,
                user_hash: app_push.user_hash,
                resource_id: app_push.resource_id,
                form_state: app_push.form_state,
                validation_token: app_push.validation_token,
                created_at: Instant::now(),
            },
        )
        .await?;

        Ok(InitiateResponse {
            process_id: Some(process_id),
            mfa_required: true,
            mfa_type: Some("APP_PUSH".to_owned()),
            session_state: None,
        })
    }

    async fn complete(&self, req: ParsedComplete) -> Result<SessionResponse, NativeError> {
        let Some(state) = self.take_pending(&req.process_id).await else {
            return Err(NativeError::new(StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED"));
        };

        if !self.await_app_push(&state).await? {
            return Err(NativeError::new(
                StatusCode::REQUEST_TIMEOUT,
                "APP_VALIDATION_TIMEOUT",
            ));
        }

        self.confirm_validation(&state.session, &state.validation_token)
            .await?;

        let home = self.home(&state.session).await?;
        if !home.signed_in() {
            tracing::warn!(
                "BoursoBank home not signed in after the app validation (on securisation: {})",
                home.landed_on_securisation
            );
            return Err(NativeError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_FORMAT_CHANGED",
            ));
        }

        Ok(SessionResponse {
            session_state: state.session.cookies.serialize_json(),
        })
    }

    async fn accounts(&self, req: ParsedAccounts) -> Result<Vec<AccountPayload>, NativeError> {
        let session = SessionClient::new(&self.config)?;
        session.cookies.restore_json(&req.session_state)?;
        self.collect_accounts(&session).await
    }

    async fn bootstrap(&self, session: &SessionClient) -> Result<String, NativeError> {
        let response = session.get(LOGIN_PATH, false).await?;
        let body = read_text(response).await?;
        if body.contains(LOGGED_IN_MARKER) {
            return Ok(body);
        }

        let token = BRS_MIT_RE
            .captures(&body)
            .and_then(|c| c.name("value"))
            .map(|m| m.as_str().trim().to_owned())
            .filter(|s| !s.is_empty())
            .or_else(|| session.cookies.get("__brs_mit"));

        let Some(token) = token else {
            tracing::warn!("BoursoBank login page did not hand out a __brs_mit token");
            return Err(NativeError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_FORMAT_CHANGED",
            ));
        };

        session.cookies.set(
            "__brs_mit",
            token,
            &self.config.bootstrap_cookie_domain,
            "/",
        );
        session.cookies.set(
            "brsDomainMigration",
            "migrated",
            &self.config.bootstrap_cookie_domain,
            "/",
        );

        let second = session.get(LOGIN_PATH, false).await?;
        read_text(second).await
    }

    async fn login(
        &self,
        session: &SessionClient,
        customer_id: &str,
        password: &str,
    ) -> Result<(), NativeError> {
        let login_page = self.bootstrap(session).await?;
        let form_token = extract_form_token(&login_page).map_err(format_err)?;

        let pad_resp = session.get(VIRTUAL_PAD_PATH, false).await?;
        let pad_page = read_text(pad_resp).await?;

        let keys = parse_virtual_pad(&pad_page).map_err(|e| {
            tracing::warn!("BoursoBank virtual keyboard could not be decoded: {e}");
            NativeError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED")
        })?;
        let challenge = extract_challenge(&pad_page).map_err(|e| {
            tracing::warn!("BoursoBank virtual keyboard challenge missing: {e}");
            NativeError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED")
        })?;
        let encoded_password = encode_password(password, &keys).map_err(|e| {
            tracing::warn!("BoursoBank password encoding failed: {e}");
            NativeError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED")
        })?;

        let fake_password = "•".repeat(password.chars().count());
        let form = reqwest::multipart::Form::new()
            .text("form[clientNumber]", customer_id.to_owned())
            .text("form[password]", encoded_password)
            .text("form[matrixRandomChallenge]", challenge)
            .text("form[_token]", form_token)
            .text("form[fakePassword]", fake_password)
            .text("form[passwordAck]", r#"{"ry":[],"pt":[],"js":true}"#)
            .text("form[platformAuthenticatorAvailable]", "1")
            .text("form[ajx]", "1");

        let response = session
            .no_redirect
            .post(session.url(PASSWORD_PATH))
            .multipart(form)
            .send()
            .await
            .map_err(http_err)?;

        if response.status() != StatusCode::FOUND {
            let status = response.status();
            let body = read_text(response).await?;
            if BAD_CREDENTIALS_MARKERS
                .iter()
                .any(|marker| body.contains(marker))
            {
                return Err(NativeError::new(
                    StatusCode::UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                ));
            }
            tracing::warn!(
                "BoursoBank login returned HTTP {} instead of a redirect",
                status.as_u16()
            );
            return Err(NativeError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_FORMAT_CHANGED",
            ));
        }

        Ok(())
    }

    async fn home(&self, session: &SessionClient) -> Result<HomePage, NativeError> {
        let resp = session.get("/", true).await?;
        let on_securisation = is_securisation_path(resp.url().path());
        Ok(HomePage {
            body: read_text(resp).await?,
            landed_on_securisation: on_securisation,
        })
    }

    async fn start_app_push(
        &self,
        session: &SessionClient,
    ) -> Result<AppPushChallenge, NativeError> {
        let _ = session.get(SECURISATION_PATH, true).await?;
        let validation_resp = session.get(VALIDATION_PATH, true).await?;
        let page = read_text(validation_resp).await?;

        if !page.contains("brs-otp-webtoapp") {
            if let Some(cap) = OTHER_OTP_RE.captures(&page) {
                tracing::info!(
                    "BoursoBank asked for an unsupported second factor: {}",
                    cap.name("kind").unwrap().as_str()
                );
                return Err(NativeError::new(
                    StatusCode::NOT_IMPLEMENTED,
                    "MFA_TYPE_UNSUPPORTED",
                ));
            }
            tracing::warn!("BoursoBank securisation page offered no known second factor");
            return Err(NativeError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_FORMAT_CHANGED",
            ));
        }

        let (raw_api_url, user_hash) = extract_brs_config(&page).map_err(format_err)?;
        let api_url = validate_api_url(&raw_api_url, &self.config.base_url).map_err(format_err)?;
        let (resource_id, form_state) = strong_auth_params(&page).map_err(format_err)?;
        let validation_token = extract_form_token(&page).map_err(format_err)?;

        let url = format!(
            "{api_url}/fr-FR/_user_/_{user_hash}_/session/challenge/startwebtoapp/{resource_id}"
        );
        let payload =
            serde_json::to_string(&json!({ "formState": form_state })).expect("json serialization");
        let response = session
            .no_redirect
            .post(&url)
            .header(
                http::header::CONTENT_TYPE,
                "application/json; charset=utf-8",
            )
            .body(payload)
            .send()
            .await
            .map_err(http_err)?;

        let status = response.status();
        let text = read_text(response).await?;
        if status != StatusCode::OK || !json_success_body(&text) {
            tracing::warn!(
                "BoursoBank refused to send the app push (HTTP {})",
                status.as_u16()
            );
            return Err(NativeError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_UNAVAILABLE",
            ));
        }

        Ok(AppPushChallenge {
            api_url,
            user_hash,
            resource_id,
            form_state,
            validation_token,
        })
    }

    async fn await_app_push(&self, state: &PendingState) -> Result<bool, NativeError> {
        let deadline = Instant::now() + self.config.app_validation_timeout;
        let check_url = format!(
            "{}/_user_/_{}_/session/challenge/checkwebtoapp/{}",
            state.api_url, state.user_hash, state.resource_id
        );
        let payload = serde_json::to_string(&json!({ "formState": state.form_state }))
            .expect("json serialization");

        while Instant::now() < deadline {
            let response = state
                .session
                .no_redirect
                .post(&check_url)
                .header(
                    http::header::CONTENT_TYPE,
                    "application/json; charset=utf-8",
                )
                .body(payload.clone())
                .send()
                .await
                .map_err(http_err)?;

            let status = response.status();
            let text = read_text(response).await?;
            if status == StatusCode::OK && json_success_body(&text) {
                return Ok(true);
            }

            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                break;
            }
            tokio::time::sleep(self.config.app_validation_poll.min(remaining)).await;
        }
        Ok(false)
    }

    async fn confirm_validation(
        &self,
        session: &SessionClient,
        validation_token: &str,
    ) -> Result<(), NativeError> {
        let origin = self.config.base_url.trim_end_matches('/');
        let referer = format!("{origin}{VALIDATION_PATH}");
        let _ = session
            .no_redirect
            .post(session.url(VALIDATION_PATH))
            .header(http::header::ORIGIN, origin)
            .header(http::header::REFERER, referer)
            .form(&[("form[_token]", validation_token)])
            .send()
            .await
            .map_err(http_err)?;
        Ok(())
    }

    // ─── Accounts collection ────────────────────────────────────────────────

    async fn get_dashboard(
        &self,
        session: &SessionClient,
    ) -> Result<reqwest::Response, NativeError> {
        session
            .get_with_header(ACCOUNTS_PATH, "X-Requested-With", "XMLHttpRequest")
            .await
    }

    async fn dismiss_profile_notice(
        &self,
        session: &SessionClient,
        response: reqwest::Response,
    ) -> Result<reqwest::Response, NativeError> {
        let location = response
            .headers()
            .get(http::header::LOCATION)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        let target = same_origin_url(location, &self.config.base_url, &self.config.base_url)
            .map_err(|_| NativeError::new(StatusCode::BAD_GATEWAY, FORMAT_CHANGED))?;

        let page = session.get(&target, true).await?;
        let final_url = page.url().clone();
        let page_text = read_text(page).await?;

        let notice = final_url
            .path()
            .strip_prefix(PROFILE_NOTICE_PATH)
            .and_then(|rest| rest.split('/').next())
            .filter(|s| !s.is_empty())
            .unwrap_or("unknown");

        let Some(link) = notice_dismiss_link(&page_text, &self.config.base_url) else {
            return Err(format_err(AccountsFormatError::new(
                FORMAT_CHANGED,
                format!(
                    "BoursoBank served the '{notice}' notice in place of the dashboard and it cannot be postponed: open the BoursoBank website once to answer it"
                ),
            )));
        };

        let dismiss_url = same_origin_url(&link, final_url.as_str(), &self.config.base_url)
            .map_err(|_| NativeError::new(StatusCode::BAD_GATEWAY, FORMAT_CHANGED))?;
        let _ = session.get(&dismiss_url, true).await?;
        tracing::info!(
            "BoursoBank: postponed the '{notice}' notice served in place of the dashboard"
        );

        let next = self.get_dashboard(session).await?;
        let next_loc = next
            .headers()
            .get(http::header::LOCATION)
            .and_then(|v| v.to_str().ok());
        if redirects_to_profile_notice(next.status(), next_loc, &self.config.base_url) {
            return Err(format_err(AccountsFormatError::new(
                FORMAT_CHANGED,
                format!("BoursoBank served the '{notice}' notice again after postponing it"),
            )));
        }
        Ok(next)
    }

    async fn dashboard(&self, session: &SessionClient) -> Result<(String, bool), NativeError> {
        let mut response = self.get_dashboard(session).await?;
        let loc = response
            .headers()
            .get(http::header::LOCATION)
            .and_then(|v| v.to_str().ok());
        if redirects_to_profile_notice(response.status(), loc, &self.config.base_url) {
            response = self.dismiss_profile_notice(session, response).await?;
        }

        let loc = response
            .headers()
            .get(http::header::LOCATION)
            .and_then(|v| v.to_str().ok());
        // An account list sent to /securisation: only a new login can ask for the app push.
        if redirects_to_securisation(response.status(), loc, &self.config.base_url) {
            return Err(NativeError::new(
                StatusCode::UNAUTHORIZED,
                "SESSION_EXPIRED",
            ));
        }
        if !redirects_to_identity_selector(response.status(), loc, &self.config.base_url) {
            let text = read_text(response).await?;
            return Ok((text, false));
        }

        let selector_resp = session.get(IDENTITY_SELECTOR_PATH, true).await?;
        let selector = read_text(selector_resp).await?;
        let switch_path = choose_personal_identity_for_origin(&selector, &self.config.base_url)
            .map_err(format_err)?;
        let _ = session.get(&switch_path, true).await?;

        let after = self.get_dashboard(session).await?;
        let after_loc = after
            .headers()
            .get(http::header::LOCATION)
            .and_then(|v| v.to_str().ok());
        if redirects_to_securisation(after.status(), after_loc, &self.config.base_url) {
            return Err(NativeError::new(
                StatusCode::UNAUTHORIZED,
                "SESSION_EXPIRED",
            ));
        }
        if redirects_to_identity_selector(after.status(), after_loc, &self.config.base_url) {
            return Err(format_err(AccountsFormatError::new(
                FORMAT_CHANGED,
                "Identity selector was served again after switching identity",
            )));
        }
        tracing::info!(
            "BoursoBank: switched to the personal identity before reading the dashboard"
        );
        let text = read_text(after).await?;
        Ok((text, true))
    }

    async fn fetch_trading_account(
        &self,
        session: &SessionClient,
        api_url: &str,
        user_hash: &str,
        account_id: &str,
    ) -> Result<accounts_parser::TradingSummary, NativeError> {
        validate_path_segment(account_id, "accountId").map_err(format_err)?;
        let url = format!("{api_url}/_user_/_{user_hash}_/trading/accounts/summary/{account_id}");
        let response = session
            .no_redirect
            .get(&url)
            .query(&[
                ("_host", "tradingboard.boursobank.com"),
                ("position", "ACCOUNTING"),
                ("responseFormat", "true"),
            ])
            .header(http::header::ACCEPT, "application/json")
            .send()
            .await
            .map_err(http_err)?;

        let status = response.status();
        if status == StatusCode::UNAUTHORIZED || status == StatusCode::FORBIDDEN {
            return Err(NativeError::new(
                StatusCode::UNAUTHORIZED,
                "SESSION_EXPIRED",
            ));
        }
        if status != StatusCode::OK {
            let short = &account_id[..account_id.len().min(8)];
            tracing::warn!(
                "BoursoBank trading summary failed for account {short}… (HTTP {})",
                status.as_u16()
            );
            if status == StatusCode::TOO_MANY_REQUESTS || status.is_server_error() {
                return Err(NativeError::new(
                    StatusCode::BAD_GATEWAY,
                    "UPSTREAM_UNAVAILABLE",
                ));
            }
            return Err(NativeError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_FORMAT_CHANGED",
            ));
        }

        let body = read_text(response).await?;
        let payload: Value = serde_json::from_str(&body).map_err(|_| {
            format_err(AccountsFormatError::new(
                FORMAT_CHANGED,
                "Trading summary was not JSON",
            ))
        })?;
        parse_trading_summary(&payload, account_id).map_err(format_err)
    }

    async fn positions_page(
        &self,
        session: &SessionClient,
        account_href: &str,
        kind: &str,
    ) -> Result<(StatusCode, String), NativeError> {
        let account_url =
            same_origin_url(account_href, &self.config.base_url, &self.config.base_url)
                .map_err(|_| NativeError::new(StatusCode::BAD_GATEWAY, FORMAT_CHANGED))?;
        let base_with_slash = format!("{}/", account_url.trim_end_matches('/'));
        let positions_url =
            same_origin_url("positions", &base_with_slash, &self.config.base_url)
                .map_err(|_| NativeError::new(StatusCode::BAD_GATEWAY, FORMAT_CHANGED))?;

        let response = session.get(&positions_url, false).await?;
        let status = response.status();
        let location = response
            .headers()
            .get(http::header::LOCATION)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        if status == StatusCode::UNAUTHORIZED
            || status == StatusCode::FORBIDDEN
            || (status.is_redirection() && location.contains(LOGIN_PATH))
        {
            return Err(NativeError::new(
                StatusCode::UNAUTHORIZED,
                "SESSION_EXPIRED",
            ));
        }
        if status != StatusCode::OK {
            tracing::warn!("BoursoBank {kind} page failed (HTTP {})", status.as_u16());
            if status == StatusCode::TOO_MANY_REQUESTS || status.is_server_error() {
                return Err(NativeError::new(
                    StatusCode::BAD_GATEWAY,
                    "UPSTREAM_UNAVAILABLE",
                ));
            }
        }
        let text = read_text(response).await?;
        Ok((status, text))
    }

    async fn fetch_life_insurance(
        &self,
        session: &SessionClient,
        account_href: &str,
    ) -> Result<accounts_parser::LineValuedSummary, NativeError> {
        let (status, text) = self
            .positions_page(session, account_href, "assurance-vie")
            .await?;
        if status != StatusCode::OK {
            return Err(NativeError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_FORMAT_CHANGED",
            ));
        }
        parse_life_insurance(&text).map_err(format_err)
    }

    async fn read_per(
        &self,
        session: &SessionClient,
        account_id: &str,
        account_href: &str,
    ) -> Result<Option<accounts_parser::LineValuedSummary>, NativeError> {
        let (status, text) = self.positions_page(session, account_href, "PER").await?;
        let short = &account_id[..account_id.len().min(8)];
        if status != StatusCode::OK {
            tracing::warn!(
                "BoursoBank: PER {short}… unreadable, skipped (UPSTREAM_FORMAT_CHANGED: PER page answered HTTP {})",
                status.as_u16()
            );
            return Ok(None);
        }
        match parse_per(&text).and_then(|plan| {
            guard_symbol_collisions(&plan.positions)?;
            Ok(plan)
        }) {
            Ok(plan) => Ok(Some(plan)),
            Err(exc) => {
                tracing::warn!(
                    "BoursoBank: PER {short}… unreadable, skipped ({}: {})",
                    exc.code,
                    exc.message
                );
                Ok(None)
            }
        }
    }

    async fn fetch_iban(
        &self,
        session: &SessionClient,
        account_href: &str,
    ) -> Result<Option<String>, String> {
        let account_url =
            same_origin_url(account_href, &self.config.base_url, &self.config.base_url)?;
        // The RIB page answers on the untyped /compte/<id>/rib only: the card's typed
        // /compte/cav/<id>/rib, which /mouvements accepts, answers 404 (#772).
        let path = Url::parse(&account_url)
            .map_err(|_| "Invalid account URL".to_owned())?
            .path()
            .to_owned();
        let id = path
            .strip_prefix("/compte/")
            .and_then(|rest| rest.trim_end_matches('/').rsplit('/').next())
            .filter(|id| {
                id.len() == 32 && id.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
            })
            .ok_or_else(|| "Expected an account card URL".to_owned())?;
        let rib_url = same_origin_url(
            &format!("/compte/{id}/rib"),
            &account_url,
            &self.config.base_url,
        )?;
        let response = session
            .get(&rib_url, false)
            .await
            .map_err(|_| "IBAN request failed".to_owned())?;
        if !response.status().is_success() {
            return Err(format!("IBAN HTTP {}", response.status().as_u16()));
        }
        let text = read_text(response)
            .await
            .map_err(|_| "IBAN body read failed".to_owned())?;
        Ok(parse_iban_page(&text))
    }

    pub async fn fetch_operations_for_href(
        &self,
        session_state: &str,
        account_href: &str,
        today: Option<NaiveDate>,
    ) -> Result<(Vec<MovementTransaction>, bool), String> {
        let session = SessionClient::new(&self.config).map_err(|e| e.detail)?;
        session
            .cookies
            .restore_json(session_state)
            .map_err(|e| e.detail)?;
        self.fetch_operations(&session, account_href, today).await
    }

    async fn fetch_operations(
        &self,
        session: &SessionClient,
        account_href: &str,
        today: Option<NaiveDate>,
    ) -> Result<(Vec<MovementTransaction>, bool), String> {
        let today = today
            .or(self.config.today_override)
            .unwrap_or_else(|| Local::now().date_naive());
        let from_date = today - TimeDelta::days(90);

        let account_url =
            same_origin_url(account_href, &self.config.base_url, &self.config.base_url)?;
        let parsed_account =
            Url::parse(&account_url).map_err(|_| "Invalid account URL".to_owned())?;
        if parsed_account
            .path()
            .trim_end_matches('/')
            .ends_with("/mouvements")
        {
            return Err("Expected an account card URL".to_owned());
        }
        let base_with_slash = format!("{}/", account_url.trim_end_matches('/'));
        let movement_base = same_origin_url("mouvements", &base_with_slash, &self.config.base_url)?;
        let mut movement_url =
            Url::parse(&movement_base).map_err(|_| "Invalid movements URL".to_owned())?;
        movement_url
            .query_pairs_mut()
            .clear()
            .append_pair(
                "movementSearch[fromDate]",
                &from_date.format("%d/%m/%Y").to_string(),
            )
            .append_pair(
                "movementSearch[toDate]",
                &today.format("%d/%m/%Y").to_string(),
            )
            .append_pair("movementSearch[advanced]", "1");

        let mut collected: Vec<MovementTransaction> = Vec::new();
        let mut seen_ids: HashSet<String> = HashSet::new();
        let mut seen_tokens: HashSet<String> = HashSet::new();

        for _ in 0..MAX_MOVEMENT_PAGES {
            let response = session
                .get(movement_url.as_str(), false)
                .await
                .map_err(|_| "Movements request failed".to_owned())?;
            if !response.status().is_success() {
                return Err(format!("Movements HTTP {}", response.status().as_u16()));
            }
            let text = read_text(response)
                .await
                .map_err(|_| "Movements body read failed".to_owned())?;
            let (page_txs, next_token) =
                parse_operations(&text, from_date, today).map_err(|e| e.to_string())?;
            for tx in page_txs {
                if seen_ids.insert(tx.external_id.clone()) {
                    collected.push(tx);
                }
            }
            let Some(token) = next_token else {
                return Ok((collected, false));
            };
            if !seen_tokens.insert(token.clone()) {
                return Ok((collected, true));
            }

            let mut next_url = movement_url.clone();
            next_url.set_fragment(None);
            next_url
                .query_pairs_mut()
                .clear()
                .append_pair("rumroute", "accounts.bank.movements")
                .append_pair("continuationToken", &token);
            let verified = same_origin_url(
                next_url.as_str(),
                &self.config.base_url,
                &self.config.base_url,
            )?;
            movement_url =
                Url::parse(&verified).map_err(|_| "Invalid next movements URL".to_owned())?;
        }
        Ok((collected, true))
    }

    async fn read_history(&self, session: &SessionClient, entry: &mut AccountPayload, href: &str) {
        match self.fetch_iban(session, href).await {
            Ok(Some(iban)) => entry.iban = Some(iban),
            // Fixed code only: the URL and the page carry the account id and the IBAN.
            _ => tracing::warn!("BoursoBank IBAN: IBAN_UNAVAILABLE"),
        }
        match self.fetch_operations(session, href, None).await {
            Ok((txs, truncated)) => {
                entry.transactions = txs;
                if truncated {
                    entry.transaction_warning = Some("TRANSACTIONS_INCOMPLETE".to_owned());
                    tracing::warn!("BoursoBank transaction history: TRANSACTIONS_INCOMPLETE");
                }
            }
            Err(_) => {
                entry.transaction_warning = Some("TRANSACTIONS_UNAVAILABLE".to_owned());
                tracing::warn!("BoursoBank transaction history: TRANSACTIONS_UNAVAILABLE");
            }
        }
    }

    async fn collect_accounts(
        &self,
        session: &SessionClient,
    ) -> Result<Vec<AccountPayload>, NativeError> {
        let deadline = Instant::now() + self.config.accounts_history_deadline;

        let mut home = self.home(session).await?;
        // A session parked on /securisation waits for an app push nobody will approve: only a
        // new login can ask for one.
        if !home.signed_in() {
            return Err(NativeError::new(
                StatusCode::UNAUTHORIZED,
                "SESSION_EXPIRED",
            ));
        }

        let (dashboard_html, switched) = self.dashboard(session).await?;
        if switched {
            home = self.home(session).await?;
        }
        let (raw_api_url, user_hash) = extract_brs_config(&home.body).map_err(format_err)?;
        let api_url = validate_api_url(&raw_api_url, &self.config.base_url).map_err(format_err)?;

        let (accounts, third_party) = parse_dashboard(&dashboard_html).map_err(format_err)?;
        if third_party > 0 {
            tracing::info!(
                "BoursoBank: skipped {third_party} aggregated third-party account(s) -- out of scope"
            );
        }

        let mut payloads: Vec<AccountPayload> = Vec::with_capacity(accounts.len());
        let mut history_pending: Vec<(usize, String)> = Vec::new();
        let mut unreadable_per: usize = 0;

        for account in accounts {
            let mut entry = AccountPayload {
                external_id: format!("bourso_{}", account.id),
                name: account.name,
                account_type: account.kind,
                balance_eur: account.balance_eur,
                cash_balance: None,
                positions: Vec::new(),
                valuation_line_count: None,
                iban: None,
                transactions: Vec::new(),
                transaction_warning: None,
                snapshot_complete: true,
            };

            match account.kind {
                AccountKind::AssuranceVie => {
                    let contract = self.fetch_life_insurance(session, &account.href).await?;
                    guard_symbol_collisions(&contract.positions).map_err(format_err)?;
                    entry.balance_eur = contract.total_eur;
                    entry.cash_balance = Some(contract.cash_eur);
                    entry.positions = contract.positions;
                    entry.valuation_line_count = Some(contract.valuation_line_count);
                }
                AccountKind::Per => {
                    let Some(plan) = self.read_per(session, &account.id, &account.href).await?
                    else {
                        unreadable_per += 1;
                        continue;
                    };
                    entry.balance_eur = plan.total_eur;
                    entry.cash_balance = Some(plan.cash_eur);
                    entry.positions = plan.positions;
                    entry.valuation_line_count = Some(plan.valuation_line_count);
                }
                _ if account.section == "trading" => {
                    let summary = self
                        .fetch_trading_account(session, &api_url, &user_hash, &account.id)
                        .await?;
                    guard_symbol_collisions(&summary.positions).map_err(format_err)?;
                    entry.balance_eur = summary.total_eur;
                    entry.cash_balance = Some(summary.cash_eur);
                    entry.positions = summary.positions;
                }
                _ => {
                    history_pending.push((payloads.len(), account.href));
                }
            }
            payloads.push(entry);
        }

        if unreadable_per > 0 {
            tracing::warn!(
                "BoursoBank: skipped {unreadable_per} unreadable PER(s); they keep their last synced values"
            );
        }

        // Second pass: optional IBAN + movement history bounded by the overall deadline.
        let mut unread: usize = 0;
        for (idx, href) in history_pending {
            let now = Instant::now();
            if now < deadline {
                let remaining = deadline.saturating_duration_since(now);
                let timed = tokio::time::timeout(
                    remaining,
                    self.read_history(session, &mut payloads[idx], &href),
                )
                .await;
                if timed.is_ok() {
                    continue;
                }
            }
            payloads[idx].transaction_warning = Some("TRANSACTIONS_UNAVAILABLE".to_owned());
            unread += 1;
        }
        if unread > 0 {
            tracing::warn!(
                "BoursoBank transaction history: deadline reached, {unread} account(s) without history"
            );
        }

        let unresolved: usize = payloads
            .iter()
            .flat_map(|e| e.positions.iter())
            .filter(|p| p.isin.is_none())
            .count();
        if unresolved > 0 {
            tracing::info!("BoursoBank: {unresolved} instrument(s) kept their symbol as ticker");
        }

        for entry in &payloads {
            entry.validate()?;
        }

        Ok(payloads)
    }
}

struct HomePage {
    body: String,
    landed_on_securisation: bool,
}

impl HomePage {
    fn signed_in(&self) -> bool {
        self.body.contains(LOGGED_IN_MARKER) && !self.landed_on_securisation
    }
}

struct AppPushChallenge {
    api_url: String,
    user_hash: String,
    resource_id: String,
    form_state: String,
    validation_token: String,
}

fn format_err(exc: AccountsFormatError) -> NativeError {
    tracing::warn!(
        "BoursoBank payload rejected (code={}): {}",
        exc.code,
        exc.message
    );
    NativeError::new(StatusCode::BAD_GATEWAY, exc.code)
}

async fn read_text(response: reqwest::Response) -> Result<String, NativeError> {
    common::read_capped_text(response, common::MAX_RESPONSE_BYTES)
        .await
        .map_err(|err| match err {
            common::ReadBodyError::Transport(e) => http_err(e),
            common::ReadBodyError::TooLarge { limit } => {
                tracing::warn!("BoursoBank upstream response exceeded {limit} bytes");
                NativeError::new(StatusCode::BAD_GATEWAY, FORMAT_CHANGED)
            }
        })
}

fn http_err(err: reqwest::Error) -> NativeError {
    tracing::warn!(
        "BoursoBank upstream HTTP request failed (timeout={}, connect={})",
        err.is_timeout(),
        err.is_connect()
    );
    NativeError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE")
}
