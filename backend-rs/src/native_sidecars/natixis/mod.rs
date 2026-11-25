//! Native Rust port of `services/natixis-auth`.
//!
//! Read-only Natixis Interépargne authentication and positions sidecar over plain HTTPS:
//! - `GET /health`
//! - `POST /initiate`  (`{email, password}` -> `{processId, mfaRequired: true, mfaType}`)
//! - `POST /complete`  (`{processId, code}` -> `{sessionState}`)
//! - `POST /cancel`    (`{processId}` -> 204)
//! - `POST /positions` (`{sessionState}` -> `{sessionState, plans, operationsComplete, operationsFrom, operations}`)

pub mod contract;
pub mod operations_parser;
mod pending;
pub mod savings_parser;

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, LazyLock};
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use futures::future::try_join_all;
use http::header::{
    ACCEPT, ACCEPT_LANGUAGE, AUTHORIZATION, HeaderMap, HeaderValue, ORIGIN, REFERER,
    USER_AGENT as USER_AGENT_HEADER,
};
use http::{Method, StatusCode};
use rand::RngExt;
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use tokio::sync::Semaphore;
use tokio::task::JoinSet;
use url::Url;
use uuid::Uuid;

use self::contract::{
    CancelRequest, CompleteRequest, InitiateRequest, InitiateResponse, NatixisError,
    OperationPayload, PlanPayload, PositionsRequest, PositionsResponse, SessionResponse,
};
use self::operations_parser::{
    DEFAULT_API_ORIGIN, FinishedDemand, finished_demands_with_origin, operation_fund_ids,
    parse_operations, quote_path_segment,
};
use self::pending::{LoginState, PendingEntry, PendingStore};
use self::savings_parser::{SavingsFormatError, fund_code, fund_ids, parse_plans};
use crate::clock::{Clock, SystemClock};
use crate::native_sidecars::common::{
    MAX_RESPONSE_BYTES, ReadBodyError, read_capped_bytes, read_capped_text,
};
use crate::native_sidecars::{
    NativeCallResult, NativeError, NativeResponse, method_not_allowed, not_found,
};

pub const CLIENT_ID: &str = "ad7e6480-cb40-4b44-9149-1738a24460fc";
pub const DEFAULT_REDIRECT_URI: &str = "https://epargnants.interepargne.natixis.fr/";
pub const SCOPE: &str = "readActiris readUsers readDistribution readInvestment manageAccount readSaving readCms readAccount readProfitSharing readTransfer readFunds readSaverGuide readConfidential offline_access";
pub const DEFAULT_OAUTH_BASE: &str = "https://nie-auth.api.natixis.com/api/oauth";
pub const DEFAULT_IDP_BASE: &str = "https://auth-nie.natixis.com";
pub const DEFAULT_API_BASE: &str = "https://nie.api.natixis.com";

pub const TRUSTED_HOSTS: &[&str] = &[
    "nie-auth.api.natixis.com",
    "auth-nie.natixis.com",
    "epargnants.interepargne.natixis.fr",
];

const OTP_MODES: &[&str] = &["SMS", "EMAIL", "VOICE"];
pub const PENDING_TTL_SECONDS: u64 = 600;
pub const MAX_CODE_ATTEMPTS: usize = 5;
pub const MAX_PENDING: usize = 16;
pub const REQUEST_TIMEOUT_SECONDS: u64 = 15;
pub const ROUTE_DEADLINE_SECONDS: u64 = 40;
pub const FUND_DETAIL_BUDGET_SECONDS: u64 = 10;
pub const POSITIONS_DEADLINE_SECONDS: u64 = 100;
pub const OPERATIONS_BUDGET_SECONDS: u64 = 30;
pub const OPERATIONS_HISTORY_YEARS: i64 = 5;
pub const FUND_DETAIL_CONCURRENCY: usize = 4;
pub const SESSION_STATE_VERSION: i64 = 1;

const USER_AGENT: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";

static API_PATH_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(concat!(
        r"\A(?:",
        r"/auth/v3/users/me",
        r"|/saving/v1/employees/[0-9A-Za-z-]{1,40}/(?:savings|products)",
        r"|/transaction/v1/employees/[0-9A-Za-z-]{1,40}/transactions",
        r"|/transaction/v1/employees/[0-9A-Za-z-]{1,40}/(?:investmentDemands|paymentDemands)/[0-9A-Za-z_-]{1,120}",
        r"|/fund/v1/funds/[0-9]{1,18}",
        r")\z",
    ))
    .expect("valid API path regex")
});

static EMPLOYEE_ID_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\A[0-9A-Za-z-]{1,40}\z").expect("valid employee id regex"));
static FORM_ACTION_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?i)<form[^>]*\baction="([^"]+)""#).expect("valid form regex"));
static INPUT_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?i)<input[^>]*\bname="([^"]+)"[^>]*\bvalue="([^"]*)""#)
        .expect("valid input regex")
});
static CSRF_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?i)<meta\s+name="_csrf"\s+content="([^"]+)""#).expect("valid csrf regex")
});

/// Returns true iff `path` is one of the exact Natixis API shapes the sidecar is allowed to call.
pub fn api_path_allowed(path: &str) -> bool {
    API_PATH_RE.is_match(path)
}

/// Decoded Natixis session state envelope (`v = 1`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionState {
    pub v: i64,
    pub refresh_token: String,
    pub device_id: String,
    pub employee_id: String,
}

pub fn serialize_session(refresh_token: &str, device_id: &str, employee_id: &str) -> String {
    serde_json::to_string(&SessionState {
        v: SESSION_STATE_VERSION,
        refresh_token: refresh_token.to_owned(),
        device_id: device_id.to_owned(),
        employee_id: employee_id.to_owned(),
    })
    .expect("SessionState serialization is infallible")
}

pub fn parse_session(raw: &str) -> Result<SessionState, NativeError> {
    let invalid = || NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA");
    let value: Value = serde_json::from_str(raw).map_err(|_| invalid())?;
    let Some(map) = value.as_object() else {
        return Err(invalid());
    };
    if map.get("v").and_then(Value::as_i64) != Some(SESSION_STATE_VERSION) {
        return Err(invalid());
    }
    let Some(refresh_token) = map
        .get("refreshToken")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
    else {
        return Err(invalid());
    };
    let Some(device_id) = map
        .get("deviceId")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
    else {
        return Err(invalid());
    };
    let Some(employee_id) = map
        .get("employeeId")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty() && EMPLOYEE_ID_RE.is_match(s))
    else {
        return Err(invalid());
    };
    Ok(SessionState {
        v: SESSION_STATE_VERSION,
        refresh_token: refresh_token.to_owned(),
        device_id: device_id.to_owned(),
        employee_id: employee_id.to_owned(),
    })
}

/// Configuration for [`NativeNatixis`]. Production defaults strictly enforce HTTPS and
/// [`TRUSTED_HOSTS`]; test constructors allow local wiremock origins while keeping untrusted
/// hosts rejected.
#[derive(Debug, Clone)]
pub struct NatixisConfig {
    pub oauth_base: String,
    pub idp_base: String,
    pub api_base: String,
    pub redirect_uri: String,
    pub trusted_hosts: HashSet<String>,
    pub allow_http: bool,
    pub api_origin: String,
    pub pending_ttl: Duration,
    pub max_pending: usize,
    pub request_timeout: Duration,
    pub route_deadline: Duration,
    pub positions_deadline: Duration,
    pub operations_budget: Duration,
    pub fund_detail_budget: Duration,
    pub clock: Arc<dyn Clock>,
}

impl Default for NatixisConfig {
    fn default() -> Self {
        Self {
            oauth_base: DEFAULT_OAUTH_BASE.to_owned(),
            idp_base: DEFAULT_IDP_BASE.to_owned(),
            api_base: DEFAULT_API_BASE.to_owned(),
            redirect_uri: DEFAULT_REDIRECT_URI.to_owned(),
            trusted_hosts: TRUSTED_HOSTS.iter().map(|s| (*s).to_owned()).collect(),
            allow_http: false,
            api_origin: DEFAULT_API_ORIGIN.to_owned(),
            pending_ttl: Duration::from_secs(PENDING_TTL_SECONDS),
            max_pending: MAX_PENDING,
            request_timeout: Duration::from_secs(REQUEST_TIMEOUT_SECONDS),
            route_deadline: Duration::from_secs(ROUTE_DEADLINE_SECONDS),
            positions_deadline: Duration::from_secs(POSITIONS_DEADLINE_SECONDS),
            operations_budget: Duration::from_secs(OPERATIONS_BUDGET_SECONDS),
            fund_detail_budget: Duration::from_secs(FUND_DETAIL_BUDGET_SECONDS),
            clock: Arc::new(SystemClock),
        }
    }
}

fn extract_origin(base_url: &str) -> String {
    if let Ok(url) = Url::parse(base_url)
        && let Some(host) = url.host_str()
    {
        return match url.port() {
            Some(port) => format!("{}://{host}:{port}", url.scheme()),
            None => format!("{}://{host}", url.scheme()),
        };
    }
    base_url.trim_end_matches('/').to_owned()
}

impl NatixisConfig {
    /// Builds a config pointing OAuth, IdP and API at custom base URLs (e.g. wiremock servers).
    /// Automatically trusts only the hosts of the configured URLs (plus `redirect_uri`) and
    /// permits `http` only if one of the configured base URLs uses `http`.
    pub fn with_base_urls(
        oauth_base: impl Into<String>,
        idp_base: impl Into<String>,
        api_base: impl Into<String>,
        redirect_uri: impl Into<String>,
    ) -> Self {
        let oauth_base = oauth_base.into().trim_end_matches('/').to_owned();
        let idp_base = idp_base.into().trim_end_matches('/').to_owned();
        let api_base = api_base.into().trim_end_matches('/').to_owned();
        let redirect_uri = redirect_uri.into();

        let mut trusted_hosts = HashSet::new();
        let mut allow_http = false;
        for raw in [&oauth_base, &idp_base, &api_base, &redirect_uri] {
            if let Ok(parsed) = Url::parse(raw) {
                if parsed.scheme() == "http" {
                    allow_http = true;
                }
                if let Some(host) = parsed.host_str() {
                    trusted_hosts.insert(host.to_owned());
                }
            }
        }
        let api_origin = extract_origin(&api_base);
        Self {
            oauth_base,
            idp_base,
            api_base,
            redirect_uri,
            trusted_hosts,
            allow_http,
            api_origin,
            ..Self::default()
        }
    }

    /// Convenience constructor pointing OAuth (`/api/oauth`), IdP, and API at one mock server.
    pub fn for_mock_server(base_url: &str) -> Self {
        let base = base_url.trim_end_matches('/');
        Self::with_base_urls(
            format!("{base}/api/oauth"),
            base.to_owned(),
            base.to_owned(),
            DEFAULT_REDIRECT_URI,
        )
    }

    pub fn with_trusted_hosts<I, S>(mut self, hosts: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.trusted_hosts = hosts.into_iter().map(Into::into).collect();
        self
    }

    pub fn with_clock(mut self, clock: Arc<dyn Clock>) -> Self {
        self.clock = clock;
        self
    }

    pub fn with_pending_ttl(mut self, ttl: Duration) -> Self {
        self.pending_ttl = ttl;
        self
    }

    pub fn with_max_pending(mut self, max_pending: usize) -> Self {
        self.max_pending = max_pending;
        self
    }

    pub fn with_request_timeout(mut self, timeout: Duration) -> Self {
        self.request_timeout = timeout;
        self
    }

    pub fn with_route_deadline(mut self, deadline: Duration) -> Self {
        self.route_deadline = deadline;
        self
    }

    pub fn with_positions_deadline(mut self, deadline: Duration) -> Self {
        self.positions_deadline = deadline;
        self
    }

    pub fn with_operations_budget(mut self, budget: Duration) -> Self {
        self.operations_budget = budget;
        self
    }

    pub fn with_fund_detail_budget(mut self, budget: Duration) -> Self {
        self.fund_detail_budget = budget;
        self
    }
}

#[derive(Debug)]
struct Inner {
    config: NatixisConfig,
    pending: PendingStore,
}

struct PendingSlotGuard<'a> {
    store: &'a PendingStore,
    process_id: Option<String>,
}

impl Drop for PendingSlotGuard<'_> {
    fn drop(&mut self) {
        if let Some(id) = self.process_id.take() {
            self.store.close(&id);
        }
    }
}

/// In-process native Rust implementation of `services/natixis-auth`.
#[derive(Debug, Clone)]
pub struct NativeNatixis {
    inner: Arc<Inner>,
}

impl Default for NativeNatixis {
    fn default() -> Self {
        Self::new()
    }
}

impl NativeNatixis {
    pub fn new() -> Self {
        Self::with_config(NatixisConfig::default())
    }

    pub fn with_config(config: NatixisConfig) -> Self {
        let pending =
            PendingStore::new(config.pending_ttl, config.max_pending, config.clock.clone());
        Self {
            inner: Arc::new(Inner { config, pending }),
        }
    }

    pub fn config(&self) -> &NatixisConfig {
        &self.inner.config
    }

    /// Dispatches one HTTP-equivalent sidecar call and returns the simplified `NativeCallResult`.
    pub async fn call(&self, method: Method, path: &str, body: Option<Value>) -> NativeCallResult {
        self.call_raw(method, path, body)
            .await
            .map(|resp| resp.body)
            .map_err(NativeError::into_tuple)
    }

    /// Dispatches one HTTP-equivalent sidecar call and returns status + JSON body or structured error.
    pub async fn call_raw(
        &self,
        method: Method,
        path: &str,
        body: Option<Value>,
    ) -> Result<NativeResponse, NativeError> {
        match path {
            "/health" => {
                if method != Method::GET {
                    let (status, detail) = method_not_allowed(&method);
                    return Err(NativeError::new(status, detail));
                }
                Ok(NativeResponse::ok(json!({ "status": "ok" })))
            }
            "/initiate" => {
                if method != Method::POST {
                    let (status, detail) = method_not_allowed(&method);
                    return Err(NativeError::new(status, detail));
                }
                self.handle_initiate(body).await
            }
            "/complete" => {
                if method != Method::POST {
                    let (status, detail) = method_not_allowed(&method);
                    return Err(NativeError::new(status, detail));
                }
                self.handle_complete(body).await
            }
            "/cancel" => {
                if method != Method::POST {
                    let (status, detail) = method_not_allowed(&method);
                    return Err(NativeError::new(status, detail));
                }
                self.handle_cancel(body).await
            }
            "/positions" => {
                if method != Method::POST {
                    let (status, detail) = method_not_allowed(&method);
                    return Err(NativeError::new(status, detail));
                }
                self.handle_positions(body).await
            }
            _ => {
                let (status, detail) = not_found();
                Err(NativeError::new(status, detail))
            }
        }
    }

    fn new_http_client(&self) -> Result<reqwest::Client, NatixisError> {
        let mut headers = HeaderMap::new();
        headers.insert(USER_AGENT_HEADER, HeaderValue::from_static(USER_AGENT));
        headers.insert(ACCEPT_LANGUAGE, HeaderValue::from_static("fr-FR,fr;q=0.9"));
        reqwest::Client::builder()
            .default_headers(headers)
            .cookie_store(true)
            .redirect(reqwest::redirect::Policy::none())
            .timeout(self.inner.config.request_timeout)
            .build()
            .map_err(|_| {
                NatixisError::new(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR",
                    "failed to build HTTP client",
                )
            })
    }

    fn trusted(&self, raw_url: &str) -> Result<String, NatixisError> {
        let parsed = Url::parse(raw_url)
            .map_err(|_| NatixisError::format_changed("Login sent to an unparseable URL"))?;
        let scheme_ok = parsed.scheme() == "https"
            || (self.inner.config.allow_http && parsed.scheme() == "http");
        let port_ok = self.inner.config.allow_http || matches!(parsed.port(), None | Some(443));
        let host_ok = parsed
            .host_str()
            .is_some_and(|h| self.inner.config.trusted_hosts.contains(h));
        let no_creds = parsed.username().is_empty() && parsed.password().is_none();
        if !scheme_ok || !port_ok || !host_ok || !no_creds {
            return Err(NatixisError::format_changed(
                "Login sent to an unexpected host",
            ));
        }
        Ok(raw_url.to_owned())
    }

    fn idp_headers(&self, csrf: &str, device_id: &str) -> Result<HeaderMap, NatixisError> {
        let mut headers = HeaderMap::new();
        headers.insert(
            "x-csrf-token",
            HeaderValue::from_str(csrf)
                .map_err(|_| NatixisError::format_changed("invalid CSRF header value"))?,
        );
        headers.insert(
            "x-device-id",
            HeaderValue::from_str(device_id)
                .map_err(|_| NatixisError::format_changed("invalid device id header value"))?,
        );
        let origin = self.inner.config.idp_base.trim_end_matches('/');
        headers.insert(
            ORIGIN,
            HeaderValue::from_str(origin)
                .map_err(|_| NatixisError::format_changed("invalid Origin header value"))?,
        );
        headers.insert(
            REFERER,
            HeaderValue::from_str(&format!("{origin}/"))
                .map_err(|_| NatixisError::format_changed("invalid Referer header value"))?,
        );
        headers.insert(
            ACCEPT,
            HeaderValue::from_static("application/json, text/plain, */*"),
        );
        Ok(headers)
    }

    async fn run_with_deadline<T, Fut>(
        step: &'static str,
        deadline: Duration,
        fut: Fut,
    ) -> Result<T, NativeError>
    where
        Fut: std::future::Future<Output = Result<T, NatixisError>>,
    {
        match tokio::time::timeout(deadline, fut).await {
            Ok(Ok(value)) => Ok(value),
            Ok(Err(err)) => {
                tracing::warn!("Natixis {step} failed code={}: {}", err.code, err.reason);
                Err(err.into_native())
            }
            Err(_) => {
                tracing::warn!("Natixis {step} exceeded deadline");
                Err(NativeError::new(
                    StatusCode::BAD_GATEWAY,
                    "UPSTREAM_UNAVAILABLE",
                ))
            }
        }
    }

    async fn handle_initiate(&self, body: Option<Value>) -> Result<NativeResponse, NativeError> {
        let req = InitiateRequest::parse(body)?;
        self.inner.pending.expire();
        let process_id = Uuid::new_v4().to_string();
        let client = self.new_http_client().map_err(NatixisError::into_native)?;
        let entry = PendingEntry::initiating(client.clone(), self.inner.config.clock.now());
        // Reserve the slot before Natixis is asked to send a code.
        self.inner.pending.put_or_raise(process_id.clone(), entry)?;
        // Release the reserved slot if this future is cancelled or returns an error.
        let mut slot_guard = PendingSlotGuard {
            store: &self.inner.pending,
            process_id: Some(process_id.clone()),
        };

        let result = Self::run_with_deadline("login", self.inner.config.route_deadline, async {
            let mut state = self.open_login(&client).await?;
            self.password_login(&client, &state, &req.email, &req.password)
                .await?;
            let mode = self.send_code(&client, &mut state).await?;
            Ok((state, mode))
        })
        .await;

        match result {
            Ok((state, mode)) => {
                self.inner.pending.mark_ready(&process_id, state);
                slot_guard.process_id = None;
                let payload = InitiateResponse {
                    process_id,
                    mfa_required: true,
                    mfa_type: mode.to_owned(),
                };
                Ok(NativeResponse::ok(
                    serde_json::to_value(payload).expect("InitiateResponse serializes"),
                ))
            }
            Err(err) => Err(err),
        }
    }

    async fn handle_complete(&self, body: Option<Value>) -> Result<NativeResponse, NativeError> {
        let req = CompleteRequest::parse(body)?;
        let Some(mut entry) = self.inner.pending.take(&req.process_id) else {
            return Err(NativeError::new(StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED"));
        };
        if !entry.ready {
            // Still initiating: hand the attempt back untouched.
            self.inner.pending.repark(req.process_id, entry)?;
            return Err(NativeError::new(StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED"));
        }
        let Some(mut state) = entry.state.take() else {
            return Err(NativeError::new(StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED"));
        };
        let client = entry.client.clone();
        let process_id = req.process_id.clone();

        let outcome =
            Self::run_with_deadline("code check", self.inner.config.route_deadline, async {
                match self.verify_code(&client, &state, &req.code).await {
                    Ok(()) => {}
                    Err(err) if err.code == "INVALID_OTP" => {
                        state.code_attempts += 1;
                        if state.code_attempts >= MAX_CODE_ATTEMPTS {
                            return Err(NatixisError::new(
                                StatusCode::GONE,
                                "AUTH_ATTEMPT_EXPIRED",
                                "too many wrong codes",
                            ));
                        }
                        entry.state = Some(state);
                        if let Err(park_err) = self.inner.pending.repark(process_id, entry) {
                            return Err(NatixisError::new(
                                park_err.status,
                                "UPSTREAM_UNAVAILABLE",
                                "could not repark login attempt",
                            ));
                        }
                        return Err(err);
                    }
                    Err(err) => return Err(err),
                }
                let finished = self.finish_login(&client, &state).await?;
                Ok(serialize_session(
                    &finished.refresh_token,
                    &state.device_id,
                    &finished.employee_id,
                ))
            })
            .await?;

        Ok(NativeResponse::ok(
            serde_json::to_value(SessionResponse {
                session_state: outcome,
            })
            .expect("SessionResponse serializes"),
        ))
    }

    async fn handle_cancel(&self, body: Option<Value>) -> Result<NativeResponse, NativeError> {
        let req = CancelRequest::parse(body)?;
        self.inner.pending.close(&req.process_id);
        Ok(NativeResponse::no_content())
    }

    async fn handle_positions(&self, body: Option<Value>) -> Result<NativeResponse, NativeError> {
        let req = PositionsRequest::parse(body)?;
        let session = parse_session(&req.session_state)?;
        let client = self.new_http_client().map_err(NatixisError::into_native)?;

        let payload = Self::run_with_deadline(
            "positions",
            self.inner.config.positions_deadline,
            async {
                let tokens = self
                    .token_grant(
                        &client,
                        &[
                            ("grant_type", "refresh_token"),
                            ("refresh_token", session.refresh_token.as_str()),
                        ],
                        "SESSION_EXPIRED",
                    )
                    .await?;
                let collected = match self
                    .collect_plans(&client, &tokens.access_token, &session.employee_id)
                    .await
                {
                    Ok(read) => read,
                    Err(err) => {
                        if tokens.refresh_token != session.refresh_token {
                            tracing::warn!(
                                "Natixis rotated the refresh token and the read failed: the session will need a new code"
                            );
                        }
                        return Err(err);
                    }
                };
                for plan in &collected.plans {
                    if let Err(msg) = plan.validate() {
                        return Err(NatixisError::format_changed(msg));
                    }
                }
                let state = serialize_session(
                    &tokens.refresh_token,
                    &session.device_id,
                    &session.employee_id,
                );
                let operations_complete = collected.operations.is_some();
                Ok(PositionsResponse {
                    session_state: state,
                    plans: collected.plans,
                    operations_complete,
                    operations_from: collected.operations_from,
                    operations: collected.operations.unwrap_or_default(),
                })
            },
        )
        .await?;

        Ok(NativeResponse::ok(
            serde_json::to_value(payload).expect("PositionsResponse serializes"),
        ))
    }

    // ─── Login ───────────────────────────────────────────────────────────────

    async fn open_login(&self, client: &reqwest::Client) -> Result<LoginState, NatixisError> {
        let (verifier, challenge) = pkce();
        let oauth_state = random_urlsafe(24);
        let url = format!("{}/authorize", self.inner.config.oauth_base);
        let response = client
            .get(&url)
            .query(&[
                ("response_type", "code"),
                ("client_id", CLIENT_ID),
                ("state", oauth_state.as_str()),
                ("redirect_uri", self.inner.config.redirect_uri.as_str()),
                ("scope", SCOPE),
                ("code_challenge", challenge.as_str()),
                ("code_challenge_method", "S256"),
                ("additionalData", "language=fr"),
            ])
            .send()
            .await
            .map_err(|err| classify_transport("authorize", &err))?;

        let status = response.status();
        let response_url = response.url().clone();
        check_upstream(status, "authorize")?;
        let text = read_capped_text(response, MAX_RESPONSE_BYTES)
            .await
            .map_err(|err| classify_body_err("authorize", err))?;

        let action = FORM_ACTION_RE
            .captures(&text)
            .and_then(|c| c.get(1))
            .map(|m| html_unescape(m.as_str()));
        let mut fields: Vec<(String, String)> = Vec::new();
        let mut has_saml_request = false;
        for cap in INPUT_RE.captures_iter(&text) {
            let name = cap[1].to_owned();
            let value = html_unescape(&cap[2]);
            if name == "SAMLRequest" {
                has_saml_request = true;
            }
            fields.push((name, value));
        }
        let Some(action) = action else {
            return Err(NatixisError::format_changed(
                "authorize did not hand out a SAML request",
            ));
        };
        if status != StatusCode::OK || !has_saml_request {
            return Err(NatixisError::format_changed(
                "authorize did not hand out a SAML request",
            ));
        }

        let resolved = response_url
            .join(&action)
            .map_err(|_| NatixisError::format_changed("Login sent to an unparseable URL"))?;
        let target = self.trusted(resolved.as_str())?;

        let response = client
            .post(&target)
            .form(&fields)
            .send()
            .await
            .map_err(|err| classify_transport("SAML SSO", &err))?;
        let status = response.status();
        check_upstream(status, "SAML SSO")?;
        let text = read_capped_text(response, MAX_RESPONSE_BYTES)
            .await
            .map_err(|err| classify_body_err("SAML SSO", err))?;
        let csrf = CSRF_RE
            .captures(&text)
            .and_then(|c| c.get(1))
            .map(|m| m.as_str().to_owned());
        let Some(csrf) = csrf else {
            return Err(NatixisError::format_changed(
                "SAML SSO page carries no CSRF token",
            ));
        };
        if status != StatusCode::OK {
            return Err(NatixisError::format_changed(
                "SAML SSO page carries no CSRF token",
            ));
        }

        Ok(LoginState {
            verifier,
            oauth_state,
            csrf,
            device_id: Uuid::new_v4().to_string(),
            verification_url: String::new(),
            code_attempts: 0,
        })
    }

    async fn password_login(
        &self,
        client: &reqwest::Client,
        state: &LoginState,
        email: &str,
        password: &str,
    ) -> Result<(), NatixisError> {
        let headers = self.idp_headers(&state.csrf, &state.device_id)?;
        let modes_url = format!(
            "{}/auth/v1/authenticationModes/search",
            self.inner.config.idp_base
        );
        let response = client
            .post(&modes_url)
            .headers(headers.clone())
            .json(&json!({ "emailOrServerCode": email }))
            .send()
            .await
            .map_err(|err| classify_transport("login modes", &err))?;
        let status = response.status();
        check_upstream(status, "login modes")?;
        if status.is_client_error() {
            return Err(NatixisError::new(
                StatusCode::UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                format!("login modes answered {}", status.as_u16()),
            ));
        }
        let body = read_json(response, "login modes").await?;
        let Some(obj) = body.as_object() else {
            return Err(NatixisError::format_changed(
                "login modes answer is not an object",
            ));
        };
        let modes: HashSet<&str> = obj
            .get("items")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(|item| item.as_object()?.get("mode")?.as_str())
            .collect();
        if modes.is_empty() {
            return Err(NatixisError::new(
                StatusCode::UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "No login mode for this e-mail",
            ));
        }
        if !modes.contains("PASSWORD") {
            return Err(NatixisError::new(
                StatusCode::NOT_IMPLEMENTED,
                "MFA_TYPE_UNSUPPORTED",
                "This account has no password login",
            ));
        }

        let auth_url = format!("{}/auth/v1/authenticate", self.inner.config.idp_base);
        let response = client
            .post(&auth_url)
            .headers(headers)
            .json(&json!({ "password": password }))
            .send()
            .await
            .map_err(|err| classify_transport("password", &err))?;
        let status = response.status();
        check_upstream(status, "password")?;
        if matches!(
            status,
            StatusCode::UNAUTHORIZED
                | StatusCode::FORBIDDEN
                | StatusCode::BAD_REQUEST
                | StatusCode::NOT_FOUND
        ) {
            return Err(NatixisError::new(
                StatusCode::UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                format!("password answered {}", status.as_u16()),
            ));
        }
        if status == StatusCode::LOCKED {
            return Err(NatixisError::new(
                StatusCode::TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "account locked",
            ));
        }
        if !matches!(status, StatusCode::OK | StatusCode::CREATED) {
            return Err(NatixisError::format_changed(format!(
                "password answered {}",
                status.as_u16()
            )));
        }
        Ok(())
    }

    async fn send_code(
        &self,
        client: &reqwest::Client,
        state: &mut LoginState,
    ) -> Result<&'static str, NatixisError> {
        let headers = self.idp_headers(&state.csrf, &state.device_id)?;
        let mfa_url = format!(
            "{}/auth/v1/multiFactorsAuthenticate",
            self.inner.config.idp_base
        );
        let response = client
            .post(&mfa_url)
            .headers(headers.clone())
            .send()
            .await
            .map_err(|err| classify_transport("second factor check", &err))?;
        let status = response.status();
        check_upstream(status, "second factor check")?;
        let codes = error_codes(response).await;
        if status != StatusCode::FORBIDDEN
            || !codes.iter().any(|c| c == "strong_authentication_required")
        {
            return Err(NatixisError::format_changed(format!(
                "second factor check answered {}",
                status.as_u16()
            )));
        }

        let strong_modes_url = format!(
            "{}/auth/v1/strongAuthenticationModes",
            self.inner.config.idp_base
        );
        let response = client
            .get(&strong_modes_url)
            .headers(headers.clone())
            .send()
            .await
            .map_err(|err| classify_transport("second factor modes", &err))?;
        let status = response.status();
        check_upstream(status, "second factor modes")?;
        if status != StatusCode::OK {
            return Err(NatixisError::format_changed(format!(
                "second factor modes answered {}",
                status.as_u16()
            )));
        }
        let body = read_json(response, "second factor modes").await?;
        let Some(obj) = body.as_object() else {
            return Err(NatixisError::format_changed(
                "second factor modes is not an object",
            ));
        };
        let offered: HashSet<&str> = obj
            .get("items")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(|item| {
                let obj = item.as_object()?;
                if obj.get("status").and_then(Value::as_str) == Some("VALID") {
                    obj.get("id").and_then(Value::as_str)
                } else {
                    None
                }
            })
            .collect();
        let Some(&mode) = OTP_MODES.iter().find(|&&m| offered.contains(m)) else {
            return Err(NatixisError::new(
                StatusCode::NOT_IMPLEMENTED,
                "MFA_TYPE_UNSUPPORTED",
                "No SMS, e-mail or voice second factor",
            ));
        };

        let strong_url = format!(
            "{}/auth/v1/strongAuthentications",
            self.inner.config.idp_base
        );
        let response = client
            .post(&strong_url)
            .headers(headers)
            .json(&json!({ "mode": mode }))
            .send()
            .await
            .map_err(|err| classify_transport("code request", &err))?;
        let status = response.status();
        check_upstream(status, "code request")?;
        if !matches!(status, StatusCode::OK | StatusCode::CREATED) {
            return Err(NatixisError::format_changed(format!(
                "code request answered {}",
                status.as_u16()
            )));
        }
        let body = read_json(response, "code request").await?;
        let Some(href) = body
            .as_object()
            .and_then(|o| o.get("_links"))
            .and_then(Value::as_object)
            .and_then(|l| l.get("verification"))
            .and_then(Value::as_object)
            .and_then(|v| v.get("href"))
            .and_then(Value::as_str)
        else {
            return Err(NatixisError::format_changed(
                "code request carries no verification link",
            ));
        };
        let base = Url::parse(&format!(
            "{}/",
            self.inner.config.idp_base.trim_end_matches('/')
        ))
        .map_err(|_| NatixisError::format_changed("invalid IdP base URL"))?;
        let resolved = base
            .join(href)
            .map_err(|_| NatixisError::format_changed("invalid verification URL"))?;
        state.verification_url = self.trusted(resolved.as_str())?;
        Ok(mode)
    }

    async fn verify_code(
        &self,
        client: &reqwest::Client,
        state: &LoginState,
        code: &str,
    ) -> Result<(), NatixisError> {
        let mut headers = self.idp_headers(&state.csrf, &state.device_id)?;
        let response = client
            .post(&state.verification_url)
            .headers(headers.clone())
            .json(&json!({ "code": code }))
            .send()
            .await
            .map_err(|err| classify_transport("code check", &err))?;
        let status = response.status();
        check_upstream(status, "code check")?;
        if status == StatusCode::GONE {
            return Err(NatixisError::new(
                StatusCode::GONE,
                "AUTH_ATTEMPT_EXPIRED",
                "code expired",
            ));
        }
        if status == StatusCode::LOCKED {
            return Err(NatixisError::new(
                StatusCode::TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "too many codes",
            ));
        }
        if status.is_client_error() {
            return Err(NatixisError::new(
                StatusCode::BAD_REQUEST,
                "INVALID_OTP",
                format!("code check answered {}", status.as_u16()),
            ));
        }
        if !matches!(status, StatusCode::OK | StatusCode::CREATED) {
            return Err(NatixisError::format_changed(format!(
                "code check answered {}",
                status.as_u16()
            )));
        }
        let body = read_json(response, "code check").await?;
        let Some(strong) = body
            .as_object()
            .and_then(|o| o.get("id"))
            .and_then(Value::as_str)
        else {
            return Err(NatixisError::format_changed(
                "code check answer carries no id",
            ));
        };

        headers.insert(
            "strong-authentication",
            HeaderValue::from_str(strong).map_err(|_| {
                NatixisError::format_changed("invalid strong-authentication header value")
            })?,
        );
        let mfa_url = format!(
            "{}/auth/v1/multiFactorsAuthenticate",
            self.inner.config.idp_base
        );
        let response = client
            .post(&mfa_url)
            .headers(headers)
            .send()
            .await
            .map_err(|err| classify_transport("second factor", &err))?;
        let status = response.status();
        check_upstream(status, "second factor")?;
        if !matches!(status, StatusCode::OK | StatusCode::CREATED) {
            return Err(NatixisError::format_changed(format!(
                "second factor answered {}",
                status.as_u16()
            )));
        }
        Ok(())
    }

    async fn finish_login(
        &self,
        client: &reqwest::Client,
        state: &LoginState,
    ) -> Result<FinishedLogin, NatixisError> {
        let headers = self.idp_headers(&state.csrf, &state.device_id)?;
        let profile_url = format!(
            "{}/auth/v1/profiles/me/verifications",
            self.inner.config.idp_base
        );
        let response = client
            .post(&profile_url)
            .headers(headers)
            .send()
            .await
            .map_err(|err| classify_transport("profile check", &err))?;
        let status = response.status();
        check_upstream(status, "profile check")?;
        if !matches!(status, StatusCode::OK | StatusCode::CREATED) {
            return Err(NatixisError::format_changed(format!(
                "profile check answered {}",
                status.as_u16()
            )));
        }
        let body = read_json(response, "profile check").await?;
        let Some(saml) = body
            .as_object()
            .and_then(|o| o.get("saml"))
            .and_then(Value::as_object)
        else {
            return Err(NatixisError::format_changed(
                "profile check carries no saml object",
            ));
        };
        let Some(sp_url) = saml.get("serviceProviderUrl").and_then(Value::as_str) else {
            return Err(NatixisError::format_changed(
                "profile check carries no serviceProviderUrl",
            ));
        };
        let Some(encoded_value) = saml.get("encodedValue").and_then(Value::as_str) else {
            return Err(NatixisError::format_changed(
                "profile check carries no encodedValue",
            ));
        };
        // Mirror the SPA: absent/null/empty RelayState is posted as "undefined".
        let relay_state = saml
            .get("relayState")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .unwrap_or("undefined");

        let trusted_sp = self.trusted(sp_url)?;
        let response = client
            .post(&trusted_sp)
            .form(&[("SAMLResponse", encoded_value), ("RelayState", relay_state)])
            .send()
            .await
            .map_err(|err| classify_transport("SAML response", &err))?;
        let status = response.status();
        check_upstream(status, "SAML response")?;

        let location = response
            .headers()
            .get(http::header::LOCATION)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        if !matches!(status, StatusCode::FOUND | StatusCode::SEE_OTHER)
            || !location.starts_with(&self.inner.config.redirect_uri)
        {
            return Err(NatixisError::format_changed(
                "SAML response gave no authorization code",
            ));
        }
        let parsed_location = Url::parse(location)
            .map_err(|_| NatixisError::format_changed("SAML response gave an invalid redirect"))?;
        let mut auth_code: Option<String> = None;
        let mut returned_state: Option<String> = None;
        for (k, v) in parsed_location.query_pairs() {
            if k == "code" && auth_code.is_none() && !v.is_empty() {
                auth_code = Some(v.into_owned());
            } else if k == "state" && returned_state.is_none() && !v.is_empty() {
                returned_state = Some(v.into_owned());
            }
        }
        let Some(auth_code) = auth_code else {
            return Err(NatixisError::format_changed(
                "SAML response gave no authorization code",
            ));
        };
        if returned_state.as_deref() != Some(state.oauth_state.as_str()) {
            return Err(NatixisError::format_changed("Authorization state mismatch"));
        }

        let tokens = self
            .token_grant(
                client,
                &[
                    ("grant_type", "authorization_code"),
                    ("code", auth_code.as_str()),
                    ("redirect_uri", self.inner.config.redirect_uri.as_str()),
                    ("code_verifier", state.verifier.as_str()),
                ],
                "AUTH_ATTEMPT_EXPIRED",
            )
            .await?;
        let employee_id = self.fetch_employee_id(client, &tokens.access_token).await?;
        Ok(FinishedLogin {
            refresh_token: tokens.refresh_token,
            employee_id,
        })
    }

    async fn token_grant(
        &self,
        client: &reqwest::Client,
        grant: &[(&str, &str)],
        refused: &'static str,
    ) -> Result<Tokens, NatixisError> {
        let mut form: Vec<(&str, &str)> = Vec::with_capacity(grant.len() + 1);
        form.extend_from_slice(grant);
        form.push(("client_id", CLIENT_ID));

        let url = format!("{}/token", self.inner.config.oauth_base);
        let response = client
            .post(&url)
            .header(ACCEPT, "application/json")
            .form(&form)
            .send()
            .await
            .map_err(|err| classify_transport("token", &err))?;
        let status = response.status();
        check_upstream(status, "token")?;
        if matches!(status, StatusCode::BAD_REQUEST | StatusCode::UNAUTHORIZED) {
            let err_status = if refused == "SESSION_EXPIRED" {
                StatusCode::UNAUTHORIZED
            } else {
                StatusCode::GONE
            };
            return Err(NatixisError::new(
                err_status,
                refused,
                format!("token grant refused ({})", status.as_u16()),
            ));
        }
        if status != StatusCode::OK {
            return Err(NatixisError::format_changed(format!(
                "token answered {}",
                status.as_u16()
            )));
        }
        let body = read_json(response, "token").await?;
        let Some(obj) = body.as_object() else {
            return Err(NatixisError::format_changed(
                "token answer is not an object",
            ));
        };
        let Some(access_token) = obj.get("access_token").and_then(Value::as_str) else {
            return Err(NatixisError::format_changed("token answer lacks a token"));
        };
        let Some(refresh_token) = obj.get("refresh_token").and_then(Value::as_str) else {
            return Err(NatixisError::format_changed("token answer lacks a token"));
        };
        Ok(Tokens {
            access_token: access_token.to_owned(),
            refresh_token: refresh_token.to_owned(),
        })
    }

    /// Performs a GET against the Natixis API after enforcing the strict path allowlist.
    /// Exposed for testing the network guard directly.
    pub async fn api_get_checked(
        &self,
        access_token: &str,
        path: &str,
        params: &[(&str, &str)],
    ) -> Result<Value, NativeError> {
        let client = self.new_http_client().map_err(NatixisError::into_native)?;
        self.api_get(&client, access_token, path, params)
            .await
            .map_err(NatixisError::into_native)
    }

    async fn api_get(
        &self,
        client: &reqwest::Client,
        access_token: &str,
        path: &str,
        params: &[(&str, &str)],
    ) -> Result<Value, NatixisError> {
        if !api_path_allowed(path) {
            // Nothing reaches the network: the path came out of a shape Picsou did not expect.
            return Err(NatixisError::format_changed(
                "API path outside the allowed shapes",
            ));
        }
        let url = format!("{}{path}", self.inner.config.api_base);
        let mut req = client
            .get(&url)
            .header(AUTHORIZATION, format!("Bearer {access_token}"))
            .header(ACCEPT, "application/json");
        if !params.is_empty() {
            req = req.query(params);
        }
        let response = req
            .send()
            .await
            .map_err(|err| classify_transport("API", &err))?;
        let status = response.status();
        check_upstream(status, "API")?;
        if status == StatusCode::UNAUTHORIZED {
            return Err(NatixisError::new(
                StatusCode::UNAUTHORIZED,
                "SESSION_EXPIRED",
                "API answered 401",
            ));
        }
        if status != StatusCode::OK {
            return Err(NatixisError::format_changed(format!(
                "API answered {}",
                status.as_u16()
            )));
        }
        read_json(response, "API").await
    }

    async fn fetch_employee_id(
        &self,
        client: &reqwest::Client,
        access_token: &str,
    ) -> Result<String, NatixisError> {
        let me = self
            .api_get(client, access_token, "/auth/v3/users/me", &[])
            .await?;
        let Some(employee_id) = me
            .as_object()
            .and_then(|o| o.get("id"))
            .and_then(Value::as_str)
            .filter(|id| EMPLOYEE_ID_RE.is_match(id))
        else {
            return Err(NatixisError::format_changed(
                "users/me carries no usable id",
            ));
        };
        Ok(employee_id.to_owned())
    }

    // ─── Positions & Operations ──────────────────────────────────────────────

    async fn collect_plans(
        &self,
        client: &reqwest::Client,
        access_token: &str,
        employee_id: &str,
    ) -> Result<CollectedPlans, NatixisError> {
        let emp_segment = employee_path(employee_id)?;
        let base = format!("/saving/v1/employees/{emp_segment}");
        let savings = self
            .api_get(
                client,
                access_token,
                &format!("{base}/savings"),
                &[("gainsOrLosses", "true")],
            )
            .await?;
        let products = self
            .api_get(
                client,
                access_token,
                &format!("{base}/products"),
                &[
                    ("includeGainsOrLosses", "true"),
                    ("includeExternalAccounts", "true"),
                ],
            )
            .await?;
        let held = fund_ids(&savings).map_err(savings_err_to_natixis)?;
        let history = self
            .operation_details(client, access_token, employee_id)
            .await;

        let held_set: HashSet<i64> = held.iter().copied().collect();
        let mut all_ids = held;
        if let Some((_, details, _)) = &history {
            for id in operation_fund_ids(details) {
                if !held_set.contains(&id) {
                    all_ids.push(id);
                }
            }
        }
        let codes = self.fund_codes(client, access_token, &all_ids).await;
        let plans = parse_plans(&savings, &products, employee_id, &codes)
            .map_err(savings_err_to_natixis)?;

        let mut operations: Option<Vec<OperationPayload>> = None;
        let mut operations_from: Option<String> = None;
        if let Some((demands, details, since)) = history {
            // Validated here, not in the route: a history Picsou cannot take is dropped
            // whole and the plans still sync, never the other way round.
            match parse_operations(&demands, &details, employee_id, &codes) {
                Ok(parsed) => {
                    let invalid = parsed.iter().find_map(|op| op.validate().err());
                    if let Some(reason) = invalid {
                        tracing::warn!(
                            "Natixis operations not imported error_type=ValidationError reason={reason}"
                        );
                    } else {
                        operations = Some(parsed);
                        operations_from = Some(since);
                    }
                }
                Err(err) => {
                    tracing::warn!("Natixis operations not imported code={}", err.code);
                }
            }
        }

        Ok(CollectedPlans {
            plans,
            operations,
            operations_from,
        })
    }

    async fn operation_details(
        &self,
        client: &reqwest::Client,
        access_token: &str,
        employee_id: &str,
    ) -> Option<(Vec<FinishedDemand>, Vec<Value>, String)> {
        let now = self.inner.config.clock.now();
        let today = now.date_naive().format("%Y-%m-%d").to_string();
        let window_days = OPERATIONS_HISTORY_YEARS * 365 - 1;
        let since = (now - chrono::Duration::days(window_days))
            .date_naive()
            .format("%Y-%m-%d")
            .to_string();
        let emp_segment = employee_path(employee_id).ok()?;
        let listing_path = format!("/transaction/v1/employees/{emp_segment}/transactions");

        let fut = async {
            let listing = self
                .api_get(
                    client,
                    access_token,
                    &listing_path,
                    &[
                        ("fromDate", since.as_str()),
                        ("toDate", today.as_str()),
                        ("status", ""),
                    ],
                )
                .await?;
            let demands =
                finished_demands_with_origin(&listing, employee_id, &self.inner.config.api_origin)
                    .map_err(savings_err_to_natixis)?;

            let gate = Arc::new(Semaphore::new(FUND_DETAIL_CONCURRENCY));
            let reads = demands.iter().map(|demand| {
                let gate = gate.clone();
                let path = demand.path.clone();
                async move {
                    let _permit = gate
                        .acquire_owned()
                        .await
                        .map_err(|_| NatixisError::unavailable("semaphore closed"))?;
                    self.api_get(client, access_token, &path, &[]).await
                }
            });
            // All-or-none: first failure cancels every remaining in-flight detail read.
            let details = try_join_all(reads).await?;
            Ok::<_, NatixisError>((demands, details, since))
        };

        match tokio::time::timeout(self.inner.config.operations_budget, fut).await {
            Ok(Ok(result)) => Some(result),
            Ok(Err(err)) => {
                tracing::warn!("Natixis operations not read code={}", err.code);
                None
            }
            Err(_) => {
                tracing::warn!("Natixis operations not read error_type=TimeoutError");
                None
            }
        }
    }

    async fn fund_codes(
        &self,
        client: &reqwest::Client,
        access_token: &str,
        ids: &[i64],
    ) -> HashMap<i64, Option<String>> {
        if ids.is_empty() {
            return HashMap::new();
        }
        let gate = Arc::new(Semaphore::new(FUND_DETAIL_CONCURRENCY));
        let mut set: JoinSet<(i64, Option<String>)> = JoinSet::new();

        for &fund_id in ids {
            let this = self.clone();
            let client = client.clone();
            let token = access_token.to_owned();
            let gate = gate.clone();
            set.spawn(async move {
                let Ok(_permit) = gate.acquire_owned().await else {
                    return (fund_id, None);
                };
                let path = format!("/fund/v1/funds/{fund_id}");
                match this.api_get(&client, &token, &path, &[]).await {
                    Ok(detail) => (fund_id, fund_code(&detail)),
                    Err(_) => (fund_id, None),
                }
            });
        }

        let deadline = tokio::time::Instant::now() + self.inner.config.fund_detail_budget;
        let mut codes: HashMap<i64, Option<String>> = ids.iter().map(|&id| (id, None)).collect();
        let mut remaining = ids.len();

        while remaining > 0 {
            match tokio::time::timeout_at(deadline, set.join_next()).await {
                Ok(Some(Ok((fund_id, code)))) => {
                    codes.insert(fund_id, code);
                    remaining -= 1;
                }
                Ok(Some(Err(_))) => {
                    remaining -= 1;
                }
                Ok(None) => break,
                Err(_) => {
                    set.abort_all();
                    tracing::warn!(
                        "Natixis fund details: {} of {} not back in time",
                        remaining,
                        ids.len()
                    );
                    break;
                }
            }
        }
        codes
    }
}

struct Tokens {
    access_token: String,
    refresh_token: String,
}

struct FinishedLogin {
    refresh_token: String,
    employee_id: String,
}

struct CollectedPlans {
    plans: Vec<PlanPayload>,
    operations: Option<Vec<OperationPayload>>,
    operations_from: Option<String>,
}

fn savings_err_to_natixis(err: SavingsFormatError) -> NatixisError {
    NatixisError::new(StatusCode::BAD_GATEWAY, err.code, err.message)
}

fn employee_path(employee_id: &str) -> Result<String, NatixisError> {
    if !EMPLOYEE_ID_RE.is_match(employee_id) {
        return Err(NatixisError::new(
            StatusCode::BAD_REQUEST,
            "INVALID_DATA",
            "invalid employee id",
        ));
    }
    Ok(quote_path_segment(employee_id))
}

fn check_upstream(status: StatusCode, what: &str) -> Result<(), NatixisError> {
    if status == StatusCode::TOO_MANY_REQUESTS {
        return Err(NatixisError::new(
            StatusCode::TOO_MANY_REQUESTS,
            "RATE_LIMITED",
            format!("{what} throttled"),
        ));
    }
    if status.is_server_error() {
        return Err(NatixisError::unavailable(format!(
            "{what} answered {}",
            status.as_u16()
        )));
    }
    Ok(())
}

fn classify_transport(what: &str, err: &reqwest::Error) -> NatixisError {
    let kind = if err.is_timeout() {
        "timeout"
    } else if err.is_connect() {
        "connect"
    } else {
        "http"
    };
    NatixisError::unavailable(format!("{what} unreachable ({kind})"))
}

fn classify_body_err(what: &str, err: ReadBodyError) -> NatixisError {
    match err {
        ReadBodyError::Transport(e) => classify_transport(what, &e),
        ReadBodyError::TooLarge { limit } => {
            NatixisError::format_changed(format!("{what} response exceeded {limit} bytes"))
        }
    }
}

async fn read_json(response: reqwest::Response, what: &str) -> Result<Value, NatixisError> {
    let bytes = read_capped_bytes(response, MAX_RESPONSE_BYTES)
        .await
        .map_err(|err| classify_body_err(what, err))?;
    serde_json::from_slice(&bytes)
        .map_err(|_| NatixisError::format_changed(format!("{what} is not JSON")))
}

async fn error_codes(response: reqwest::Response) -> Vec<String> {
    let Ok(bytes) = read_capped_bytes(response, MAX_RESPONSE_BYTES).await else {
        return Vec::new();
    };
    let Ok(value) = serde_json::from_slice::<Value>(&bytes) else {
        return Vec::new();
    };
    value
        .as_object()
        .and_then(|o| o.get("errors"))
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|e| e.as_object()?.get("code")?.as_str().map(str::to_owned))
        .collect()
}

fn random_urlsafe(byte_len: usize) -> String {
    let mut bytes = vec![0_u8; byte_len];
    rand::rng().fill(&mut bytes[..]);
    URL_SAFE_NO_PAD.encode(bytes)
}

fn pkce() -> (String, String) {
    let verifier = random_urlsafe(32);
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    (verifier, challenge)
}

/// Unescapes HTML character references (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`,
/// decimal `&#NN;`, and hex `&#xHH;`).
pub(crate) fn html_unescape(input: &str) -> String {
    if !input.contains('&') {
        return input.to_owned();
    }
    let mut out = String::with_capacity(input.len());
    let mut rest = input;
    while let Some(amp) = rest.find('&') {
        out.push_str(&rest[..amp]);
        let after_amp = &rest[amp + 1..];
        if let Some(semi) = after_amp.find(';') {
            let entity = &after_amp[..semi];
            let decoded = match entity {
                "amp" => Some('&'),
                "lt" => Some('<'),
                "gt" => Some('>'),
                "quot" => Some('"'),
                "apos" => Some('\''),
                _ if entity.starts_with("#x") || entity.starts_with("#X") => {
                    u32::from_str_radix(&entity[2..], 16)
                        .ok()
                        .and_then(char::from_u32)
                }
                _ if entity.starts_with('#') => {
                    entity[1..].parse::<u32>().ok().and_then(char::from_u32)
                }
                _ => None,
            };
            if let Some(ch) = decoded {
                out.push(ch);
                rest = &after_amp[semi + 1..];
                continue;
            }
        }
        out.push('&');
        rest = after_amp;
    }
    out.push_str(rest);
    out
}

#[cfg(test)]
mod unit_tests {
    use super::*;
    use crate::clock::FixedClock;
    use chrono::TimeZone;

    #[test]
    fn html_unescape_handles_named_decimal_and_hex_entities() {
        assert_eq!(html_unescape("request&#x3d;"), "request=");
        assert_eq!(
            html_unescape("a&amp;b&lt;c&gt;&quot;d&quot;&apos;e&#61;"),
            "a&b<c>\"d\"'e="
        );
        assert_eq!(html_unescape("plain text"), "plain text");
        assert_eq!(
            html_unescape("unknown &foo; & bare"),
            "unknown &foo; & bare"
        );
    }

    #[test]
    fn pkce_challenge_matches_sha256_of_verifier() {
        let (verifier, challenge) = pkce();
        assert!(!verifier.is_empty());
        assert!(!verifier.contains('='));
        assert!(!challenge.contains('='));
        let expected = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
        assert_eq!(challenge, expected);
    }

    #[test]
    fn pending_store_enforces_ttl_and_capacity() {
        let clock = Arc::new(FixedClock::new(
            chrono::Utc.with_ymd_and_hms(2025, 6, 15, 10, 0, 0).unwrap(),
        ));
        let store = PendingStore::new(Duration::from_secs(600), 2, clock.clone());
        let client = reqwest::Client::new();

        assert!(store.put(
            "p1".to_owned(),
            PendingEntry::initiating(client.clone(), clock.now())
        ));
        assert!(store.put(
            "p2".to_owned(),
            PendingEntry::initiating(client.clone(), clock.now())
        ));
        assert_eq!(store.len(), 2);
        // Full -> 503
        let err = store
            .put_or_raise(
                "p3".to_owned(),
                PendingEntry::initiating(client.clone(), clock.now()),
            )
            .unwrap_err();
        assert_eq!(err.status, StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(err.detail, "UPSTREAM_UNAVAILABLE");

        // Advance past TTL -> put reclaims expired entries and succeeds
        clock.advance(Duration::from_secs(601));
        assert!(store.put(
            "p3".to_owned(),
            PendingEntry::initiating(client, clock.now())
        ));
        assert_eq!(store.len(), 1);
        assert!(store.take("p1").is_none());
        assert!(store.take("p3").is_some());
    }

    #[test]
    fn trusted_host_production_defaults_are_strict() {
        let sidecar = NativeNatixis::new();
        assert!(
            sidecar
                .trusted("https://auth-nie.natixis.com/auth/nie-gateway/saml2/idp/sso")
                .is_ok()
        );
        assert!(
            sidecar
                .trusted("https://nie-auth.api.natixis.com/api/oauth/authorize")
                .is_ok()
        );
        assert!(
            sidecar
                .trusted("http://auth-nie.natixis.com/auth/nie-gateway/saml2/idp/sso")
                .is_err()
        );
        assert!(sidecar.trusted("https://example.org/collect").is_err());
        assert!(
            sidecar
                .trusted("https://user@auth-nie.natixis.com/auth/sso")
                .is_err()
        );
    }
}
