//! Login, MFA, and session client for Euro-Information banking sites (CMES, CIC).
//!
//! Faithful port of `services/base/euro_information/{forms.py,client.py}`:
//! plain HTML login form (`bloc_ident`), SMS OTP or mobile app confirmation polling,
//! remembered-device cookie (`auth_client_state`) replay, and strict same-origin redirects.

use std::collections::{BTreeSet, HashMap};
use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant};

use http::StatusCode;
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use url::Url;

use super::NativeSidecarError;
use super::common::{
    MAX_RESPONSE_BYTES, MemoryCookieJar, PendingStore, build_http_client, html_text,
    normalize_base_url, read_capped_text, safe_log, same_origin_url, unescape_html,
};

pub const LOGIN_FORM: &str = "bloc_ident";
pub const TRUST_COOKIE: &str = "auth_client_state";
pub const OTP_FIELD: &str = "otp_password";

pub const MFA_PATH_MARKERS: &[&str] = &["validation.aspx", "authentification-forte"];
pub const SKIP_PHRASES: &[&str] = &["passer cette etape", "passer cette étape"];

pub const PENDING_TTL_SECONDS: u64 = 600;
pub const MAX_PENDING: usize = 16;
pub const APP_VALIDATION_TIMEOUT_SECONDS: u64 = 120;
pub const APP_VALIDATION_POLL_SECONDS: u64 = 2;
pub const REQUEST_TIMEOUT_SECONDS: u64 = 30;
pub const MAX_REDIRECTS: usize = 10;

const USER_AGENT: &str = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 \
    (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

const BAD_CREDENTIALS_MARKERS: &[&str] = &[
    "mot de passe est faux",
    "mot de passe est révoqué",
    "identifiant ou mot de passe est incorrect",
    "votre code d'accès n'est pas reconnu",
    "devez renseigner votre identifiant",
];
const LOCKED_MARKERS: &[&str] = &["bloqué", "pas autorisé à accéder à ce service"];

// ─── HTML Form parser (euro_information/forms.py) ───────────────────────────

static FORM_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?is)<form\b(?P<attrs>[^>]*)>(?P<body>.*?)</form>").expect("static regex")
});
static INPUT_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)<input\b(?P<attrs>[^>]*)/?>").expect("static regex"));
static ATTR_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?P<name>[\w:-]+)\s*=\s*(?:"(?P<dq>[^"]*)"|'(?P<sq>[^']*)')"#)
        .expect("static regex")
});
static QUOTED_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#""[^"]*"|'[^']*'"#).expect("static regex"));

#[derive(Debug, Clone, Default)]
pub struct Form {
    pub action: String,
    pub method: String,
    pub name: String,
    pub id: String,
    pub fields: HashMap<String, String>,
    pub buttons: HashMap<String, String>,
}

fn parse_attrs(raw: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    for cap in ATTR_RE.captures_iter(raw) {
        let name = cap["name"].to_ascii_lowercase();
        let val = cap
            .name("dq")
            .or_else(|| cap.name("sq"))
            .map(|m| unescape_html(m.as_str()))
            .unwrap_or_default();
        map.insert(name, val);
    }
    map
}

fn has_checked_attr(raw_attrs: &str) -> bool {
    let stripped = QUOTED_RE.replace_all(raw_attrs, "\"\"");
    let lower = stripped.to_ascii_lowercase();
    let bytes = lower.as_bytes();
    let needle = b"checked";
    let mut pos = 0;
    while let Some(idx) = lower[pos..].find("checked") {
        let start = pos + idx;
        let end = start + needle.len();
        let before_ok = start == 0 || !is_attr_char(bytes[start - 1]);
        let after_ok = end == bytes.len() || !is_attr_char(bytes[end]);
        if before_ok && after_ok {
            return true;
        }
        pos = end;
    }
    false
}

fn is_attr_char(b: u8) -> bool {
    b.is_ascii_alphanumeric() || matches!(b, b'_' | b':' | b'-')
}

pub fn parse_forms(page: &str) -> Vec<Form> {
    let mut forms = Vec::new();
    for cap in FORM_RE.captures_iter(page) {
        let attrs = parse_attrs(&cap["attrs"]);
        let mut form = Form {
            action: attrs.get("action").cloned().unwrap_or_default(),
            method: attrs
                .get("method")
                .map(|m| m.to_ascii_lowercase())
                .unwrap_or_else(|| "get".to_owned()),
            name: attrs.get("name").cloned().unwrap_or_default(),
            id: attrs.get("id").cloned().unwrap_or_default(),
            fields: HashMap::new(),
            buttons: HashMap::new(),
        };
        for field_cap in INPUT_RE.captures_iter(&cap["body"]) {
            let raw_attrs = &field_cap["attrs"];
            let field_attrs = parse_attrs(raw_attrs);
            let Some(name) = field_attrs.get("name").filter(|n| !n.is_empty()).cloned() else {
                continue;
            };
            let kind = field_attrs
                .get("type")
                .map(|t| t.to_ascii_lowercase())
                .unwrap_or_default();
            match kind.as_str() {
                "submit" => {
                    form.buttons
                        .insert(name, field_attrs.get("value").cloned().unwrap_or_default());
                }
                "checkbox" | "radio" => {
                    if has_checked_attr(raw_attrs) {
                        form.fields.insert(
                            name,
                            field_attrs
                                .get("value")
                                .cloned()
                                .unwrap_or_else(|| "on".to_owned()),
                        );
                    }
                }
                "hidden" | "text" | "password" | "email" | "tel" | "number" | "" => {
                    form.fields
                        .insert(name, field_attrs.get("value").cloned().unwrap_or_default());
                }
                _ => {}
            }
        }
        forms.push(form);
    }
    forms
}

pub fn find_form_by_name(page: &str, name: &str) -> Option<Form> {
    parse_forms(page).into_iter().find(|f| f.name == name)
}

pub fn find_form_by_button(page: &str, button: &str) -> Option<Form> {
    parse_forms(page)
        .into_iter()
        .find(|f| f.buttons.contains_key(button))
}

/// Names of every form field on a page, for diagnostics. Never returns values.
pub fn field_names(page: &str) -> Vec<String> {
    let mut set = BTreeSet::new();
    for form in parse_forms(page) {
        for name in form.fields.into_keys().chain(form.buttons.into_keys()) {
            set.insert(name);
        }
    }
    set.into_iter().collect()
}

// ─── Client regexes ─────────────────────────────────────────────────────────

static ERROR_BLOCK_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r#"(?s)<div\b[^>]*class="[^"]*\bblocmsg\s+(?:err|alerte)\b[^"]*"[^>]*>(?P<text>.*?)</div>"#,
    )
    .expect("static regex")
});
static OTP_ERROR_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<div\b[^>]*class="[^"]*\bbloctxt\s+err\b[^"]*"[^>]*>(?P<text>.*?)</div>"#)
        .expect("static regex")
});
static TRANSACTION_ID_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"transactionId\s*:\s*'(?P<id>[^']+)'").expect("static regex"));
static STATE_URL_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"['"](?P<url>[^'"\s]*SOSD_OTP_GetTransactionState[^'"\s]*)['"]"#)
        .expect("static regex")
});
static CANCEL_URL_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"['"](?P<url>[^'"\s]*SOSD_OTP_CancelTransaction[^'"\s]*)['"]"#)
        .expect("static regex")
});
static TRANSACTION_STATE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)<transactionState>\s*(?P<state>\w+)\s*</transactionState>")
        .expect("static regex")
});
static LINK_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?is)<a\b(?P<attrs>[^>]*)>(?P<text>.*?)</a>").expect("static regex")
});
static HREF_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"href="(?P<href>[^"]+)""#).expect("static regex"));
static OTP_CODE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^\d{4,8}$").expect("static regex"));

// ─── Site configuration & error mapping ─────────────────────────────────────

#[derive(Debug, Clone)]
pub struct Site {
    pub name: &'static str,
    pub base_url: String,
    pub login_path: &'static str,
    pub logged_in_marker: &'static str,
    pub action_needed_path_markers: &'static [&'static str],
    pub skip_phrases: &'static [&'static str],
    pub postpone_button: Option<&'static str>,
    pub default_state_path: Option<&'static str>,
    pub default_cancel_path: Option<&'static str>,
    pub replayed_cookies: Option<&'static [&'static str]>,
    pub unreadable_poll_expires: bool,
    pub app_validation_timeout: Duration,
    pub app_validation_poll: Duration,
}

#[derive(Debug, Clone)]
pub struct UpstreamError {
    pub code: &'static str,
    pub message: String,
}

impl UpstreamError {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    pub fn into_sidecar_error(self) -> NativeSidecarError {
        let status = match self.code {
            "INVALID_CREDENTIALS"
            | "ACTION_REQUIRED"
            | "SESSION_EXPIRED"
            | "APP_VALIDATION_CANCELLED" => StatusCode::UNAUTHORIZED,
            "MFA_TYPE_UNSUPPORTED" => StatusCode::NOT_IMPLEMENTED,
            "APP_VALIDATION_TIMEOUT" => StatusCode::REQUEST_TIMEOUT,
            "INVALID_OTP" => StatusCode::BAD_REQUEST,
            "AUTH_ATTEMPT_EXPIRED" => StatusCode::GONE,
            _ => StatusCode::BAD_GATEWAY,
        };
        NativeSidecarError::new(status, self.code)
    }
}

pub struct HttpPage {
    pub status: StatusCode,
    pub url: Url,
    pub headers: http::HeaderMap,
    pub text: String,
}

impl HttpPage {
    pub fn is_redirect(&self) -> bool {
        self.status.is_redirection() && self.headers.contains_key(http::header::LOCATION)
    }

    pub fn location(&self) -> &str {
        self.headers
            .get(http::header::LOCATION)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
    }
}

// ─── Pending MFA state ──────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum MfaKind {
    Sms,
    AppPush,
}

struct PendingMfa {
    client: reqwest::Client,
    jar: Arc<MemoryCookieJar>,
    kind: MfaKind,
    final_url: Url,
    final_fields: HashMap<String, String>,
    page_url: Url,
    transaction_id: Option<String>,
    state_url: Option<Url>,
    cancel_url: Option<Url>,
}

// ─── Request / Response contracts ───────────────────────────────────────────

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct InitiateRequest {
    login: String,
    password: String,
    #[serde(default)]
    session_state: Option<String>,
    #[serde(default)]
    unattended: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CompleteRequest {
    process_id: String,
    #[serde(default)]
    code: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct InitiateResponse {
    process_id: Option<String>,
    mfa_required: bool,
    mfa_type: Option<&'static str>,
    session_state: Option<String>,
}

// ─── EuroInfoClient ─────────────────────────────────────────────────────────

pub struct EuroInfoClient {
    site: Site,
    base_url: Url,
    pending: PendingStore<PendingMfa>,
}

impl EuroInfoClient {
    pub fn new(mut site: Site) -> Self {
        site.base_url = normalize_base_url(&site.base_url);
        let base_url = Url::parse(&site.base_url).expect("valid Euro-Information base URL");
        Self {
            site,
            base_url,
            pending: PendingStore::new(Duration::from_secs(PENDING_TTL_SECONDS), Some(MAX_PENDING)),
        }
    }

    pub fn site(&self) -> &Site {
        &self.site
    }

    pub fn base_url(&self) -> &Url {
        &self.base_url
    }

    pub fn new_session_client(
        &self,
    ) -> Result<(reqwest::Client, Arc<MemoryCookieJar>), NativeSidecarError> {
        let jar = MemoryCookieJar::new();
        let client = build_http_client(
            jar.clone(),
            USER_AGENT,
            Duration::from_secs(REQUEST_TIMEOUT_SECONDS),
            &[("Accept-Language", "fr-FR,fr;q=0.9")],
        )?;
        Ok((client, jar))
    }

    pub fn same_origin(&self, current: &Url, target: &str) -> Result<Url, UpstreamError> {
        same_origin_url(&self.base_url, current, target).ok_or_else(|| {
            UpstreamError::new("UPSTREAM_FORMAT_CHANGED", "A link left the bank's site")
        })
    }

    pub async fn get_url(
        &self,
        client: &reqwest::Client,
        url: Url,
    ) -> Result<HttpPage, NativeSidecarError> {
        let resp = client.get(url.clone()).send().await.map_err(|_| {
            tracing::warn!("{} HTTP GET failed", self.site.name);
            NativeSidecarError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE")
        })?;
        read_page(resp, self.site.name).await
    }

    pub async fn post_form(
        &self,
        client: &reqwest::Client,
        url: Url,
        fields: &HashMap<String, String>,
        referer: Option<&str>,
    ) -> Result<HttpPage, NativeSidecarError> {
        let mut req = client
            .post(url)
            .header("Origin", &self.site.base_url)
            .form(fields);
        if let Some(referer) = referer {
            req = req.header("Referer", referer);
        }
        let resp = req.send().await.map_err(|_| {
            tracing::warn!("{} HTTP POST failed", self.site.name);
            NativeSidecarError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE")
        })?;
        read_page(resp, self.site.name).await
    }

    pub async fn follow(
        &self,
        client: &reqwest::Client,
        mut page: HttpPage,
    ) -> Result<HttpPage, NativeSidecarError> {
        for _ in 0..MAX_REDIRECTS {
            if !page.is_redirect() {
                return Ok(page);
            }
            let target = self
                .same_origin(&page.url, page.location())
                .map_err(UpstreamError::into_sidecar_error)?;
            page = self.get_url(client, target).await?;
        }
        Err(
            UpstreamError::new("UPSTREAM_FORMAT_CHANGED", "Too many redirects")
                .into_sidecar_error(),
        )
    }

    pub fn is_mfa_page(page: &HttpPage) -> bool {
        let path = page.url.path();
        MFA_PATH_MARKERS.iter().any(|m| path.contains(m))
    }

    pub fn is_logged_in(&self, page: &HttpPage) -> bool {
        !Self::is_mfa_page(page) && page.text.contains(self.site.logged_in_marker)
    }

    pub fn diagnose(&self, stage: &str, page: &HttpPage) {
        let names = field_names(&page.text);
        let truncated: Vec<&str> = names.iter().take(40).map(String::as_str).collect();
        tracing::warn!(
            "{} {stage}: unexpected page (status={}; path={}; forms={:?})",
            self.site.name,
            page.status.as_u16(),
            safe_log(page.url.path(), 200),
            truncated
        );
    }

    fn require_logged_in(&self, stage: &str, page: &HttpPage) -> Result<(), UpstreamError> {
        if !self.is_logged_in(page) {
            self.diagnose(stage, page);
            return Err(UpstreamError::new(
                "UPSTREAM_FORMAT_CHANGED",
                "Did not reach the customer space",
            ));
        }
        Ok(())
    }

    pub async fn fetch_page(
        &self,
        client: &reqwest::Client,
        path: &str,
        label: &str,
    ) -> Result<String, NativeSidecarError> {
        let url = self.base_url.join(path).map_err(|_| {
            NativeSidecarError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
        })?;
        let mut page = self.get_url(client, url).await?;
        if page.is_redirect() {
            let loc = page.location();
            if let Ok(resolved) = page.url.join(loc) {
                let s = resolved.as_str();
                if s.contains(self.site.login_path) || s.contains("identification") {
                    return Err(UpstreamError::new("SESSION_EXPIRED", "Session expired")
                        .into_sidecar_error());
                }
            }
            page = self.follow(client, page).await?;
        }
        if matches!(
            page.status,
            StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN
        ) || page.url.path().contains(self.site.login_path)
        {
            return Err(
                UpstreamError::new("SESSION_EXPIRED", "Session expired").into_sidecar_error()
            );
        }
        if page.status != StatusCode::OK {
            // A timeout (408) is the site being unavailable, like 429 and 5xx.
            let code = if page.status == StatusCode::TOO_MANY_REQUESTS
                || page.status == StatusCode::REQUEST_TIMEOUT
                || page.status.is_server_error()
            {
                "UPSTREAM_UNAVAILABLE"
            } else {
                "UPSTREAM_FORMAT_CHANGED"
            };
            return Err(UpstreamError::new(
                code,
                format!("{label} page answered HTTP {}", page.status.as_u16()),
            )
            .into_sidecar_error());
        }
        if !self.is_logged_in(&page) {
            return Err(
                UpstreamError::new("SESSION_EXPIRED", "Session expired").into_sidecar_error()
            );
        }
        Ok(page.text)
    }

    // ─── Login endpoints ────────────────────────────────────────────────────

    pub async fn handle_initiate(&self, body: Value) -> Result<Value, NativeSidecarError> {
        let req: InitiateRequest = serde_json::from_value(body)
            .map_err(|_| NativeSidecarError::new(StatusCode::BAD_REQUEST, "INVALID_DATA"))?;
        if req.login.is_empty()
            || req.login.len() > 100
            || req.password.is_empty()
            || req.password.len() > 100
            || req
                .session_state
                .as_ref()
                .is_some_and(|s| s.len() > 2_000_000)
        {
            return Err(NativeSidecarError::new(
                StatusCode::BAD_REQUEST,
                "INVALID_DATA",
            ));
        }

        self.pending.expire().await;
        let process_id = uuid::Uuid::new_v4().to_string();
        let (client, jar) = self.new_session_client()?;

        let trusted = jar.restore_previous(
            req.session_state.as_deref(),
            self.site.replayed_cookies,
            TRUST_COOKIE,
        );

        let login_landing = self
            .submit_login(&client, &req.login, &req.password)
            .await?;
        let mut landing = self.skip_notices(&client, login_landing).await?;

        if Self::is_mfa_page(&landing)
            && let Some(bypassed) = self.bypass_second_factor(&client, &landing).await?
        {
            landing = self.skip_notices(&client, bypassed).await?;
        }

        if !Self::is_mfa_page(&landing) {
            self.require_logged_in("login landing", &landing)
                .map_err(UpstreamError::into_sidecar_error)?;
            return Ok(serde_json::to_value(InitiateResponse {
                process_id: None,
                mfa_required: false,
                mfa_type: None,
                session_state: Some(jar.serialize_json()),
            })
            .expect("serialize InitiateResponse"));
        }

        if let Some(sms_form) = otp_form(&landing.text) {
            if req.unattended {
                tracing::info!(
                    "{}: unattended re-login stopped at an SMS code",
                    self.site.name
                );
                return Err(UpstreamError::new(
                    "SESSION_EXPIRED",
                    "Unattended re-login needs an SMS code",
                )
                .into_sidecar_error());
            }
            let pending = self
                .build_sms_pending(client, jar, &landing, sms_form)
                .map_err(UpstreamError::into_sidecar_error)?;
            self.pending
                .put_or_err(process_id.clone(), pending, "UPSTREAM_UNAVAILABLE")
                .await?;
            return Ok(serde_json::to_value(InitiateResponse {
                process_id: Some(process_id),
                mfa_required: true,
                mfa_type: Some("SMS"),
                session_state: None,
            })
            .expect("serialize InitiateResponse"));
        }

        if let Some(unsupported) = unsupported_factor(&landing.text) {
            tracing::info!(
                "{} asked for an unsupported second factor: {unsupported}",
                self.site.name
            );
            return Err(
                UpstreamError::new("MFA_TYPE_UNSUPPORTED", "Unsupported second factor")
                    .into_sidecar_error(),
            );
        }
        if trusted {
            tracing::info!(
                "{}: remembered-device cookie no longer spares the confirmation",
                self.site.name
            );
        }

        let pending = self
            .build_mobile_pending(client, jar, &landing)
            .map_err(UpstreamError::into_sidecar_error)?;
        if req.unattended {
            self.withdraw_confirmation(&pending).await;
            return Err(UpstreamError::new(
                "SESSION_EXPIRED",
                "Unattended re-login needs a mobile confirmation",
            )
            .into_sidecar_error());
        }
        self.pending
            .put_or_err(process_id.clone(), pending, "UPSTREAM_UNAVAILABLE")
            .await?;
        Ok(serde_json::to_value(InitiateResponse {
            process_id: Some(process_id),
            mfa_required: true,
            mfa_type: Some("APP_PUSH"),
            session_state: None,
        })
        .expect("serialize InitiateResponse"))
    }

    pub async fn handle_complete(&self, body: Value) -> Result<Value, NativeSidecarError> {
        // Check `code` validity specifically so malformed OTP returns INVALID_OTP (mirroring otp_field_detail).
        if let Some(obj) = body.as_object()
            && let Some(code_val) = obj.get("code")
            && !code_val.is_null()
        {
            match code_val.as_str() {
                Some(s) if OTP_CODE_RE.is_match(s) => {}
                _ => {
                    return Err(NativeSidecarError::new(
                        StatusCode::BAD_REQUEST,
                        "INVALID_OTP",
                    ));
                }
            }
        }
        let req: CompleteRequest = serde_json::from_value(body)
            .map_err(|_| NativeSidecarError::new(StatusCode::BAD_REQUEST, "INVALID_DATA"))?;
        if req.process_id.is_empty() || req.process_id.len() > 100 {
            return Err(NativeSidecarError::new(
                StatusCode::BAD_REQUEST,
                "INVALID_DATA",
            ));
        }

        let Some(mut lease) = self.pending.take_lease(&req.process_id).await else {
            return Err(NativeSidecarError::new(
                StatusCode::GONE,
                "AUTH_ATTEMPT_EXPIRED",
            ));
        };

        let landing = match lease.value.kind {
            MfaKind::Sms => {
                let Some(code) = req.code.as_deref() else {
                    self.pending
                        .repark_or_err(req.process_id, lease, "UPSTREAM_UNAVAILABLE")
                        .await?;
                    return Err(NativeSidecarError::new(
                        StatusCode::BAD_REQUEST,
                        "INVALID_OTP",
                    ));
                };
                match self.submit_sms_code(&mut lease.value, code).await {
                    Ok(page) => page,
                    Err(err) if err.detail == "INVALID_OTP" => {
                        self.pending
                            .repark_or_err(req.process_id, lease, "UPSTREAM_UNAVAILABLE")
                            .await?;
                        return Err(err);
                    }
                    Err(err) => return Err(err),
                }
            }
            MfaKind::AppPush => {
                if req.code.is_some() {
                    return Err(UpstreamError::new(
                        "MFA_TYPE_UNSUPPORTED",
                        "A mobile confirmation takes no code",
                    )
                    .into_sidecar_error());
                }
                self.await_confirmation(&lease.value).await?;
                self.finish_confirmation(&lease.value).await?
            }
        };

        self.require_logged_in("after second factor", &landing)
            .map_err(UpstreamError::into_sidecar_error)?;
        Ok(json!({ "sessionState": lease.value.jar.serialize_json() }))
    }

    // ─── Internal login steps ───────────────────────────────────────────────

    async fn submit_login(
        &self,
        client: &reqwest::Client,
        login: &str,
        password: &str,
    ) -> Result<HttpPage, NativeSidecarError> {
        let login_url = self.base_url.join(self.site.login_path).map_err(|_| {
            NativeSidecarError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
        })?;
        let first = self.get_url(client, login_url).await?;
        let login_page = self.follow(client, first).await?;
        let Some(form) = find_form_by_name(&login_page.text, LOGIN_FORM) else {
            self.diagnose("login page", &login_page);
            return Err(
                UpstreamError::new("UPSTREAM_FORMAT_CHANGED", "No login form").into_sidecar_error(),
            );
        };
        let mut fields = form.fields;
        fields.insert("_cm_user".to_owned(), login.to_owned());
        fields.insert("_cm_pwd".to_owned(), password.to_owned());
        fields.insert("_charset_".to_owned(), "UTF-8".to_owned());
        let action = if form.action.is_empty() {
            login_page.url.as_str()
        } else {
            &form.action
        };
        let target = self
            .same_origin(&login_page.url, action)
            .map_err(UpstreamError::into_sidecar_error)?;
        let response = self
            .post_form(client, target, &fields, Some(login_page.url.as_str()))
            .await?;
        if !response.is_redirect() {
            let code = match login_error(&response.text) {
                Some(code) => code,
                None => {
                    self.diagnose("login", &response);
                    "UPSTREAM_FORMAT_CHANGED"
                }
            };
            return Err(UpstreamError::new(code, "Login refused").into_sidecar_error());
        }
        let landing = self.follow(client, response).await?;
        if landing.url.path() == self.site.login_path {
            let code = login_error(&landing.text).unwrap_or("INVALID_CREDENTIALS");
            return Err(UpstreamError::new(code, "Login refused").into_sidecar_error());
        }
        Ok(landing)
    }

    async fn skip_notices(
        &self,
        client: &reqwest::Client,
        mut page: HttpPage,
    ) -> Result<HttpPage, NativeSidecarError> {
        for _ in 0..3 {
            let path = page.url.path();
            if !self
                .site
                .action_needed_path_markers
                .iter()
                .any(|m| path.contains(m))
            {
                return Ok(page);
            }
            if let Some(button) = self.site.postpone_button
                && let Some(later) = find_form_by_button(&page.text, button)
            {
                let mut fields = later.fields;
                if let Some(btn_val) = later.buttons.get(button) {
                    fields.insert(button.to_owned(), btn_val.clone());
                }
                let action = if later.action.is_empty() {
                    page.url.as_str()
                } else {
                    &later.action
                };
                let target = self
                    .same_origin(&page.url, action)
                    .map_err(UpstreamError::into_sidecar_error)?;
                let resp = self.post_form(client, target, &fields, None).await?;
                page = self.follow(client, resp).await?;
                continue;
            }
            if let Some(skip) = link_with(&page.text, self.site.skip_phrases) {
                let target = self
                    .same_origin(&page.url, &skip)
                    .map_err(UpstreamError::into_sidecar_error)?;
                let resp = self.get_url(client, target).await?;
                page = self.follow(client, resp).await?;
                continue;
            }
            tracing::info!(
                "{}: login parked on a notice that needs the customer (path={})",
                self.site.name,
                safe_log(page.url.path(), 200)
            );
            return Err(UpstreamError::new(
                "ACTION_REQUIRED",
                "The site needs the customer to act on its website",
            )
            .into_sidecar_error());
        }
        Err(UpstreamError::new("UPSTREAM_FORMAT_CHANGED", "Too many notices").into_sidecar_error())
    }

    async fn bypass_second_factor(
        &self,
        client: &reqwest::Client,
        page: &HttpPage,
    ) -> Result<Option<HttpPage>, NativeSidecarError> {
        let mut link = link_with(
            &page.text,
            &["sans confirmation mobile", "accéder à votre espace client"],
        );
        if link.is_none() && page.text.contains("confirmer votre identité plus tard") {
            link = LINK_RE.captures_iter(&page.text).find_map(|cap| {
                let href = HREF_RE.captures(&cap["attrs"])?.name("href")?.as_str();
                if href.to_ascii_lowercase().contains("bypass") {
                    Some(href.replace("&amp;", "&"))
                } else {
                    None
                }
            });
        }
        let Some(link) = link else {
            return Ok(None);
        };
        tracing::info!(
            "{}: second factor skipped by the site's own link",
            self.site.name
        );
        let target = self
            .same_origin(&page.url, &link)
            .map_err(UpstreamError::into_sidecar_error)?;
        let resp = self.get_url(client, target).await?;
        let followed = self.follow(client, resp).await?;
        Ok(Some(followed))
    }

    fn build_sms_pending(
        &self,
        client: reqwest::Client,
        jar: Arc<MemoryCookieJar>,
        page: &HttpPage,
        form: Form,
    ) -> Result<PendingMfa, UpstreamError> {
        let btn_names: Vec<&str> = form.buttons.keys().take(10).map(String::as_str).collect();
        tracing::info!(
            "{}: code sent by SMS (form buttons={:?})",
            self.site.name,
            btn_names
        );
        let action = if form.action.is_empty() {
            page.url.as_str()
        } else {
            &form.action
        };
        let final_url = self.same_origin(&page.url, action)?;
        let final_fields = sms_fields(&form);
        Ok(PendingMfa {
            client,
            jar,
            kind: MfaKind::Sms,
            final_url,
            final_fields,
            page_url: page.url.clone(),
            transaction_id: None,
            state_url: None,
            cancel_url: None,
        })
    }

    fn build_mobile_pending(
        &self,
        client: reqwest::Client,
        jar: Arc<MemoryCookieJar>,
        page: &HttpPage,
    ) -> Result<PendingMfa, UpstreamError> {
        let tx = TRANSACTION_ID_RE
            .captures(&page.text)
            .map(|c| c["id"].to_owned());
        let form = confirmation_form(&page.text);
        let state_url_cap = STATE_URL_RE
            .captures(&page.text)
            .map(|c| c["url"].to_owned());
        let state_path = state_url_cap.as_deref().or(self.site.default_state_path);
        let (Some(transaction_id), Some(form), Some(state_path)) = (tx, form, state_path) else {
            self.diagnose("mobile confirmation", page);
            return Err(UpstreamError::new(
                "UPSTREAM_FORMAT_CHANGED",
                "Unknown confirmation page",
            ));
        };
        let cancel_url_cap = CANCEL_URL_RE
            .captures(&page.text)
            .map(|c| c["url"].to_owned());
        let cancel_path = cancel_url_cap.as_deref().or(self.site.default_cancel_path);
        let state_url = self.same_origin(&page.url, state_path)?;
        let cancel_url = match cancel_path {
            Some(path) => Some(self.same_origin(&page.url, path)?),
            None => None,
        };
        let action = if form.action.is_empty() {
            page.url.as_str()
        } else {
            &form.action
        };
        let final_url = self.same_origin(&page.url, action)?;
        let mut final_fields = form.fields;
        final_fields.extend(form.buttons);
        Ok(PendingMfa {
            client,
            jar,
            kind: MfaKind::AppPush,
            final_url,
            final_fields,
            page_url: page.url.clone(),
            transaction_id: Some(transaction_id),
            state_url: Some(state_url),
            cancel_url,
        })
    }

    async fn submit_sms_code(
        &self,
        state: &mut PendingMfa,
        code: &str,
    ) -> Result<HttpPage, NativeSidecarError> {
        let mut payload = state.final_fields.clone();
        payload.insert(OTP_FIELD.to_owned(), code.to_owned());
        let resp = self
            .post_form(
                &state.client,
                state.final_url.clone(),
                &payload,
                Some(state.page_url.as_str()),
            )
            .await?;
        let landing = self.follow(&state.client, resp).await?;
        if landing.url.path().contains(self.site.login_path) {
            return Err(UpstreamError::new(
                "AUTH_ATTEMPT_EXPIRED",
                "The SMS code is no longer valid",
            )
            .into_sidecar_error());
        }
        if let Some(retry) = otp_form(&landing.text) {
            let message = OTP_ERROR_RE
                .captures(&landing.text)
                .map(|c| html_text(&c["text"]).to_lowercase())
                .unwrap_or_default();
            if message.contains("bloqué") || message.contains("erreurs de saisie") {
                return Err(UpstreamError::new(
                    "ACTION_REQUIRED",
                    "Too many wrong codes, access locked",
                )
                .into_sidecar_error());
            }
            state.final_fields = sms_fields(&retry);
            state.page_url = landing.url;
            return Err(UpstreamError::new("INVALID_OTP", "Wrong SMS code").into_sidecar_error());
        }
        if !state.jar.has_cookie(TRUST_COOKIE) {
            tracing::info!(
                "{}: no remembered-device cookie after the SMS code; every login will ask again",
                self.site.name
            );
        }
        self.skip_notices(&state.client, landing).await
    }

    async fn withdraw_confirmation(&self, state: &PendingMfa) {
        let (Some(cancel_url), Some(tx_id)) = (&state.cancel_url, &state.transaction_id) else {
            return;
        };
        let mut fields = HashMap::new();
        fields.insert("transactionId".to_owned(), tx_id.clone());
        match state
            .client
            .post(cancel_url.clone())
            .form(&fields)
            .send()
            .await
        {
            Ok(_) => tracing::info!("{}: withdrew the confirmation request", self.site.name),
            Err(_) => tracing::info!(
                "{} could not withdraw the confirmation request",
                self.site.name
            ),
        }
    }

    fn unreadable_poll(&self, page: &HttpPage) -> UpstreamError {
        let status = page.status;
        if status == StatusCode::TOO_MANY_REQUESTS || status.is_server_error() {
            return UpstreamError::new(
                "UPSTREAM_UNAVAILABLE",
                "Confirmation polling is unavailable",
            );
        }
        let mut sent_elsewhere = page.is_redirect()
            || matches!(status, StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN);
        if status == StatusCode::OK {
            sent_elsewhere =
                page.url.path().contains(self.site.login_path) || page.text.contains(LOGIN_FORM);
        }
        if sent_elsewhere || self.site.unreadable_poll_expires {
            UpstreamError::new(
                "AUTH_ATTEMPT_EXPIRED",
                "Confirmation request no longer exists",
            )
        } else {
            UpstreamError::new(
                "UPSTREAM_FORMAT_CHANGED",
                "Unreadable confirmation polling response",
            )
        }
    }

    async fn await_confirmation(&self, state: &PendingMfa) -> Result<(), NativeSidecarError> {
        let Some(state_url) = &state.state_url else {
            return Err(
                UpstreamError::new("UPSTREAM_FORMAT_CHANGED", "Missing polling URL")
                    .into_sidecar_error(),
            );
        };
        let Some(tx_id) = &state.transaction_id else {
            return Err(
                UpstreamError::new("UPSTREAM_FORMAT_CHANGED", "Missing transactionId")
                    .into_sidecar_error(),
            );
        };
        let deadline = Instant::now() + self.site.app_validation_timeout;
        let mut fields = HashMap::new();
        fields.insert("transactionId".to_owned(), tx_id.clone());

        while Instant::now() < deadline {
            let page = match state
                .client
                .post(state_url.clone())
                .form(&fields)
                .send()
                .await
            {
                Ok(resp) => match read_page(resp, self.site.name).await {
                    Ok(p) => p,
                    Err(e) => {
                        self.withdraw_confirmation(state).await;
                        return Err(e);
                    }
                },
                Err(_) => {
                    self.withdraw_confirmation(state).await;
                    tracing::warn!("{} confirmation polling network failure", self.site.name);
                    return Err(NativeSidecarError::new(
                        StatusCode::BAD_GATEWAY,
                        "UPSTREAM_UNAVAILABLE",
                    ));
                }
            };
            let current = if page.status == StatusCode::OK {
                TRANSACTION_STATE_RE
                    .captures(&page.text)
                    .map(|c| c["state"].to_ascii_uppercase())
            } else {
                None
            };
            let Some(current) = current else {
                self.withdraw_confirmation(state).await;
                tracing::info!(
                    "{} confirmation polling answered without a state (status={})",
                    self.site.name,
                    page.status.as_u16()
                );
                return Err(self.unreadable_poll(&page).into_sidecar_error());
            };
            match current.as_str() {
                "VALIDATED" => return Ok(()),
                "CANCELLED" | "NONE" => {
                    return Err(UpstreamError::new(
                        "APP_VALIDATION_CANCELLED",
                        "Confirmation refused on the phone",
                    )
                    .into_sidecar_error());
                }
                "PENDING" => {
                    if !self.site.app_validation_poll.is_zero() {
                        tokio::time::sleep(self.site.app_validation_poll).await;
                    } else {
                        tokio::task::yield_now().await;
                    }
                }
                _ => {
                    self.withdraw_confirmation(state).await;
                    tracing::warn!(
                        "{} confirmation polling returned an unknown state",
                        self.site.name
                    );
                    return Err(UpstreamError::new(
                        "UPSTREAM_FORMAT_CHANGED",
                        "Unknown confirmation state",
                    )
                    .into_sidecar_error());
                }
            }
        }
        self.withdraw_confirmation(state).await;
        Err(UpstreamError::new(
            "APP_VALIDATION_TIMEOUT",
            "Confirmation not approved in time",
        )
        .into_sidecar_error())
    }

    async fn finish_confirmation(
        &self,
        state: &PendingMfa,
    ) -> Result<HttpPage, NativeSidecarError> {
        let resp = self
            .post_form(
                &state.client,
                state.final_url.clone(),
                &state.final_fields,
                Some(state.page_url.as_str()),
            )
            .await?;
        if !state.jar.has_cookie(TRUST_COOKIE) {
            tracing::info!(
                "{}: no remembered-device cookie after confirmation; every login will ask again",
                self.site.name
            );
        }
        let followed = self.follow(&state.client, resp).await?;
        self.skip_notices(&state.client, followed).await
    }
}

async fn read_page(
    resp: reqwest::Response,
    site_name: &str,
) -> Result<HttpPage, NativeSidecarError> {
    let status = resp.status();
    let url = resp.url().clone();
    let headers = resp.headers().clone();
    let text = read_capped_text(resp, MAX_RESPONSE_BYTES)
        .await
        .map_err(|_| {
            tracing::warn!("{site_name} HTTP response read failed");
            NativeSidecarError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE")
        })?;
    Ok(HttpPage {
        status,
        url,
        headers,
        text,
    })
}

fn login_error(page: &str) -> Option<&'static str> {
    let cap = ERROR_BLOCK_RE.captures(page)?;
    let message = html_text(&cap["text"]).to_lowercase();
    if BAD_CREDENTIALS_MARKERS.iter().any(|m| message.contains(m)) {
        return Some("INVALID_CREDENTIALS");
    }
    if LOCKED_MARKERS.iter().any(|m| message.contains(m)) {
        return Some("INVALID_CREDENTIALS");
    }
    Some("UPSTREAM_FORMAT_CHANGED")
}

pub fn link_with(page: &str, phrases: &[&str]) -> Option<String> {
    for cap in LINK_RE.captures_iter(page) {
        let text = html_text(&cap["text"]).to_lowercase();
        if phrases.iter().any(|p| text.contains(p))
            && let Some(href_cap) = HREF_RE.captures(&cap["attrs"])
        {
            let href = &href_cap["href"];
            if !href.starts_with('#') && !href.starts_with("javascript:") {
                return Some(href.replace("&amp;", "&"));
            }
        }
    }
    None
}

fn otp_form(page: &str) -> Option<Form> {
    parse_forms(page)
        .into_iter()
        .find(|f| f.fields.contains_key(OTP_FIELD))
}

fn sms_fields(form: &Form) -> HashMap<String, String> {
    let mut fields: HashMap<String, String> = form
        .fields
        .iter()
        .filter(|(k, _)| k.as_str() != OTP_FIELD)
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    for (k, v) in &form.buttons {
        fields.insert(k.clone(), v.clone());
    }
    fields
}

fn unsupported_factor(page: &str) -> Option<&'static str> {
    let lowered = page.to_lowercase();
    if lowered.contains("lecteur digipass") {
        Some("DIGIPASS")
    } else if lowered.contains("safetrans") {
        Some("SAFETRANS")
    } else if lowered.contains("aucun moyen pour confirmer") {
        Some("NONE_ENROLLED")
    } else {
        None
    }
}

fn confirmation_form(page: &str) -> Option<Form> {
    parse_forms(page)
        .into_iter()
        .find(|f| f.method == "post" && f.name != LOGIN_FORM && !f.action.contains("search"))
}
