//! SSO sign-in with an OIDC provider: SsoProperties, the lazy discovery of
//! SsoClientRegistrationRepository, SsoStartGuardFilter, the authorization-code handshake with
//! PKCE and nonce, and the success and failure handlers. The IdP only authenticates: its tokens
//! are dropped once the ID token is checked, and Picsou issues its own cookies.

pub mod account;

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::body::Bytes;
use axum::extract::State;
use axum::http::{HeaderMap, HeaderValue, Method, StatusCode, Uri, header};
use axum::response::Response;
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use chrono::{DateTime, Utc};
use jsonwebtoken::jwk::JwkSet;
use jsonwebtoken::{Algorithm, DecodingKey, Validation};
use rand::RngExt;
use serde::Deserialize;
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};

use crate::AppState;
use crate::auth::cookies::{self, Cookies};
use crate::auth::{AppUser, Auth, Principal, users};
use crate::config::SsoConfig;
use crate::error::raw;
use crate::http::client_ip::{ClientIp, RequestOrigin};
use crate::http::rate_limit::policies;
use crate::identity::common::{has_valid_proof, java_url_encode, log_safe, redirect};
use crate::setup_auth::setup::{self, SetupState};
use crate::single_flight::SingleFlight;

pub const REGISTRATION_ID: &str = "oidc";
const START_PATH: &str = "/oauth2/sso/authorization/oidc";
const CODE_PREFIX: &str = "/oauth2/sso/code/";
const RETRY_BACKOFF: chrono::Duration = chrono::Duration::seconds(60);
const MAX_PROVIDER_NAME: usize = 40;
const IDP_TIMEOUT: Duration = Duration::from_secs(10);
/// The servlet container's session timeout, which bounds a handshake.
const HANDSHAKE_TTL: chrono::Duration = chrono::Duration::minutes(30);
const CLOCK_SKEW: i64 = 60;
pub const SESSION_COOKIE: &str = "JSESSIONID";

/// SsoProperties over the SSO_* configuration; evaluate() is the single "is SSO on" rule.
pub struct Settings<'a> {
    cfg: &'a SsoConfig,
}

impl<'a> Settings<'a> {
    pub fn new(state: &'a AppState) -> Self {
        Self {
            cfg: &state.config.sso,
        }
    }

    pub fn enabled(&self) -> bool {
        self.cfg.enabled()
    }

    pub fn issuer_uri(&self) -> String {
        self.cfg.issuer_uri.trim().to_owned()
    }

    pub fn client_id(&self) -> String {
        self.cfg.client_id.trim().to_owned()
    }

    fn client_secret(&self) -> String {
        self.cfg.client_secret.trim().to_owned()
    }

    pub fn provider_name(&self) -> String {
        let name = self.cfg.provider_name.trim();
        if name.is_empty() {
            return "SSO".to_owned();
        }
        name.chars().take(MAX_PROVIDER_NAME).collect()
    }

    pub fn groups_claim(&self) -> String {
        if self.cfg.groups_claim.trim().is_empty() {
            "groups".to_owned()
        } else {
            self.cfg.groups_claim.clone()
        }
    }

    pub fn admin_group(&self) -> String {
        self.cfg.admin_group.trim().to_owned()
    }

    pub fn admin_group_managed(&self) -> bool {
        self.enabled() && !self.admin_group().is_empty()
    }

    /// openid first, then the configured scopes, trimmed and de-duplicated.
    fn effective_scopes(&self) -> Vec<String> {
        let mut scopes = vec!["openid".to_owned()];
        for scope in &self.cfg.scopes {
            for part in scope.split(',') {
                let part = part.trim();
                if !part.is_empty() && !scopes.iter().any(|s| s == part) {
                    scopes.push(part.to_owned());
                }
            }
        }
        scopes
    }
}

/// The provider's discovered metadata, kept for good once a discovery succeeds.
pub struct Provider {
    issuer: String,
    authorization_endpoint: String,
    token_endpoint: String,
    jwks_uri: String,
    userinfo_endpoint: Option<String>,
    end_session_endpoint: Option<String>,
    jwks: tokio::sync::Mutex<Option<JwkSet>>,
}

#[derive(Deserialize)]
struct DiscoveryDocument {
    issuer: Option<String>,
    authorization_endpoint: Option<String>,
    token_endpoint: Option<String>,
    jwks_uri: Option<String>,
    userinfo_endpoint: Option<String>,
    end_session_endpoint: Option<String>,
}

/// The single registration, discovered on first use: a failure is remembered for 60 s, and a
/// lookup while another discovery runs answers "unavailable" at once.
#[derive(Default)]
pub struct Discovery {
    provider: Mutex<Option<Arc<Provider>>>,
    last_failure: Mutex<Option<DateTime<Utc>>>,
    discovering: SingleFlight,
}

/// One pending authorization request per browser session, as the HTTP session holds it.
#[derive(Default)]
pub struct Handshakes(Mutex<HashMap<String, Handshake>>);

#[derive(Clone)]
struct Handshake {
    state: String,
    nonce: String,
    verifier: String,
    redirect_uri: String,
    intent: Intent,
    created: DateTime<Utc>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Login,
    Link,
    Reauth,
    Activate,
    Setup,
}

/// SsoIntent: what the round trip is for, and where it goes back to.
#[derive(Debug, Clone)]
pub struct Intent {
    pub kind: Kind,
    pub redirect: String,
    pub remember_me: bool,
    pub user_id: Option<i64>,
    pub activation_token: Option<String>,
    pub started_at: DateTime<Utc>,
}

pub fn login_error(code: &str) -> String {
    format!("/login?ssoError={code}")
}

/// Appends to the query string, before any fragment, so the browser still sends it.
pub fn with_query_parameter(url: &str, parameter: &str) -> String {
    let (before, fragment) = match url.find('#') {
        Some(i) => (&url[..i], &url[i..]),
        None => (url, ""),
    };
    let separator = if before.contains('?') { '&' } else { '?' };
    format!("{before}{separator}{parameter}{fragment}")
}

impl Intent {
    pub fn error_redirect(&self, code: &str) -> String {
        match self.kind {
            Kind::Login => {
                let mut target = login_error(code);
                if self.redirect != "/" {
                    target.push_str("&redirect=");
                    target.push_str(&java_url_encode(&self.redirect));
                }
                target
            }
            Kind::Link | Kind::Reauth => {
                with_query_parameter(&self.redirect, &format!("ssoError={code}"))
            }
            Kind::Activate => format!(
                "/activate/{}?ssoError={code}",
                java_url_encode(self.activation_token.as_deref().unwrap_or_default())
            ),
            Kind::Setup => format!("/setup/admin?ssoError={code}"),
        }
    }
}

/// SafeRedirect.sanitize: a same-site path, else the fallback.
pub fn safe_redirect(raw: Option<&str>, fallback: &str) -> String {
    match raw {
        Some(r)
            if r.starts_with('/')
                && !r.starts_with("//")
                && !r.starts_with("/\\")
                && r.encode_utf16().count() <= 2048
                && !r.chars().any(char::is_control) =>
        {
            r.to_owned()
        }
        _ => fallback.to_owned(),
    }
}

fn random_token() -> String {
    let mut bytes = [0u8; 32];
    rand::rng().fill(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

fn s256(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

impl Discovery {
    async fn discover_unless_backing_off(&self, state: &AppState) -> Option<Arc<Provider>> {
        let now = state.clock.now();
        let backing_off = self
            .last_failure
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .is_some_and(|at| now < at + RETRY_BACKOFF);
        if backing_off {
            return None;
        }
        let issuer = Settings::new(state).issuer_uri();
        match discover(state, &issuer).await {
            Ok(provider) => {
                let provider = Arc::new(provider);
                *self.provider.lock().unwrap_or_else(|e| e.into_inner()) = Some(provider.clone());
                *self.last_failure.lock().unwrap_or_else(|e| e.into_inner()) = None;
                Some(provider)
            }
            Err(e) => {
                tracing::warn!(
                    "SSO discovery failed for issuer {}: {}",
                    log_safe(&issuer),
                    log_safe(&e)
                );
                *self.last_failure.lock().unwrap_or_else(|e| e.into_inner()) = Some(now);
                None
            }
        }
    }
}

async fn discover(state: &AppState, issuer: &str) -> Result<Provider, String> {
    let url = format!(
        "{}/.well-known/openid-configuration",
        issuer.trim_end_matches('/')
    );
    let response = state
        .http
        .get(&url)
        .timeout(IDP_TIMEOUT)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !response.status().is_success() {
        return Err(format!("discovery answered {}", response.status()));
    }
    let doc: DiscoveryDocument = response.json().await.map_err(|e| e.to_string())?;
    if doc.issuer.as_deref() != Some(issuer) {
        return Err("the discovered issuer does not match the configured one".into());
    }
    let required = |v: Option<String>, name: &str| v.ok_or_else(|| format!("{name} missing"));
    Ok(Provider {
        issuer: issuer.to_owned(),
        authorization_endpoint: required(doc.authorization_endpoint, "authorization_endpoint")?,
        token_endpoint: required(doc.token_endpoint, "token_endpoint")?,
        jwks_uri: required(doc.jwks_uri, "jwks_uri")?,
        userinfo_endpoint: doc.userinfo_endpoint.filter(|u| !u.trim().is_empty()),
        end_session_endpoint: doc.end_session_endpoint,
        jwks: tokio::sync::Mutex::new(None),
    })
}

/// The discovered provider, or None while SSO is off or the IdP cannot be reached.
pub async fn provider(state: &AppState) -> Option<Arc<Provider>> {
    if !Settings::new(state).enabled() {
        return None;
    }
    let discovery = &state.identity.discovery;
    if let Some(p) = discovery
        .provider
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
    {
        return Some(p);
    }
    let _discovering = discovery.discovering.try_enter()?;
    discovery.discover_unless_backing_off(state).await
}

/// The end_session_endpoint for RP-initiated logout, resolved lazily like the registration.
pub async fn end_session_endpoint(state: &AppState) -> Option<String> {
    provider(state).await?.end_session_endpoint.clone()
}

impl Handshakes {
    fn put(&self, id: String, handshake: Handshake, now: DateTime<Utc>) {
        let mut map = self.0.lock().unwrap_or_else(|e| e.into_inner());
        map.retain(|_, h| now - h.created < HANDSHAKE_TTL);
        map.insert(id, handshake);
    }

    fn contains(&self, id: &str) -> bool {
        self.0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .contains_key(id)
    }

    fn take(&self, id: &str, now: DateTime<Utc>) -> Option<Handshake> {
        self.0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(id)
            .filter(|h| now - h.created < HANDSHAKE_TTL)
    }
}

fn params(uri: &Uri, headers: &HeaderMap, body: &Bytes) -> HashMap<String, String> {
    let mut out = HashMap::new();
    let form = headers
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|c| c.starts_with("application/x-www-form-urlencoded"));
    let query = uri.query().unwrap_or_default().as_bytes();
    let sources: [&[u8]; 2] = [query, if form { body } else { &[] }];
    for source in sources {
        for (k, v) in url::form_urlencoded::parse(source) {
            out.entry(k.into_owned()).or_insert_with(|| v.into_owned());
        }
    }
    out
}

/// CrossSiteCookieRequestMatcher without its cookie condition: a cross-site POST is refused
/// even from an anonymous browser, unless its origin is on the CORS allow-list.
async fn is_rejected_cross_site(
    state: &AppState,
    headers: &HeaderMap,
    origin: &RequestOrigin,
) -> bool {
    let value = |name: &str| headers.get(name).and_then(|v| v.to_str().ok());
    let cross = match value("sec-fetch-site") {
        Some(site) => site != "same-origin" && site != "none",
        None => match value("origin").or_else(|| value("referer")) {
            Some(source) => !same_origin(origin, source),
            None => false,
        },
    };
    if !cross {
        return false;
    }
    match value("origin") {
        Some(o) => !crate::http::cors_origins(state).await.is_some_and(|list| {
            list.iter().any(|a| {
                a.trim_end_matches('/')
                    .eq_ignore_ascii_case(o.trim_end_matches('/'))
            })
        }),
        None => true,
    }
}

fn same_origin(origin: &RequestOrigin, url: &str) -> bool {
    let Ok(parsed) = url::Url::parse(url) else {
        return false;
    };
    parsed.scheme().eq_ignore_ascii_case(origin.scheme)
        && parsed.host_str().is_some_and(|h| {
            h.eq_ignore_ascii_case(origin.host.trim_start_matches('[').trim_end_matches(']'))
        })
        && parsed.port_or_known_default() == Some(origin.port)
}

/// Everything under /oauth2/sso/**: the SSO chain when SSO is on, the API chain's answer when off.
#[allow(clippy::too_many_arguments)]
pub async fn chain(
    State(state): State<AppState>,
    auth: Option<Auth>,
    origin: RequestOrigin,
    ip: ClientIp,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let path = uri.path().to_owned();
    if !Settings::new(&state).enabled() {
        return match auth {
            Some(_) => crate::http::not_found(&path),
            None => raw::response(StatusCode::UNAUTHORIZED, raw::UNAUTHORIZED),
        };
    }
    if method != Method::POST {
        return redirect(&origin, "/login");
    }
    if is_rejected_cross_site(&state, &headers, &origin).await {
        return raw::response(StatusCode::FORBIDDEN, raw::CROSS_SITE);
    }
    let params = params(&uri, &headers, &body);
    if path.starts_with(CODE_PREFIX) {
        return callback(&state, &origin, &ip, &headers, &path, &params).await;
    }
    if path != START_PATH {
        return redirect(&origin, "/login");
    }
    start(&state, &origin, &ip, &headers, &params).await
}

async fn signed_in_user_from_cookie(state: &AppState, headers: &HeaderMap) -> Option<AppUser> {
    let token = cookies::read(headers, cookies::ACCESS)?;
    crate::auth::layer::authenticate_access_token(state, &token)
        .await
        .map(|p: Principal| p.user)
}

async fn start(
    state: &AppState,
    origin: &RequestOrigin,
    ip: &ClientIp,
    headers: &HeaderMap,
    params: &HashMap<String, String>,
) -> Response {
    if !state
        .limits
        .try_consume(&policies::SSO_START, &ip.as_java_string())
    {
        return redirect(origin, &login_error("RATE_LIMITED"));
    }
    let kind = match params.get("intent").map(String::as_str).unwrap_or("login") {
        "login" => Kind::Login,
        "link" => Kind::Link,
        "reauth" => Kind::Reauth,
        "activate" => Kind::Activate,
        "setup" => Kind::Setup,
        _ => return redirect(origin, &login_error("IDP_ERROR")),
    };
    let fallback = match kind {
        Kind::Login | Kind::Activate => "/",
        Kind::Link | Kind::Reauth => "/settings",
        Kind::Setup => "/setup/admin",
    };
    let mut intent = Intent {
        kind,
        redirect: safe_redirect(params.get("redirect").map(String::as_str), fallback),
        remember_me: params.get("rememberMe").is_some_and(|v| v == "1"),
        user_id: None,
        activation_token: (kind == Kind::Activate)
            .then(|| params.get("token").cloned().unwrap_or_default()),
        started_at: state.clock.now(),
    };
    let Some(provider) = provider(state).await else {
        return redirect(origin, &intent.error_redirect("IDP_UNAVAILABLE"));
    };
    match kind {
        Kind::Login => {
            if !setup::is_complete(state).await.unwrap_or(false) {
                return redirect(origin, "/setup");
            }
        }
        Kind::Link | Kind::Reauth => {
            let Some(user) = signed_in_user_from_cookie(state, headers).await else {
                return redirect(origin, "/login");
            };
            intent.user_id = Some(user.id);
            if kind == Kind::Link && !has_valid_proof(state, &user, headers) {
                return redirect(origin, &intent.error_redirect("REAUTH_REQUIRED"));
            }
        }
        Kind::Activate => {
            let token = intent.activation_token.clone().unwrap_or_default();
            let invited = if params.get("ack").is_some_and(|v| v == "1") {
                users::find_by_activation_token(&state.db, &token, false)
                    .await
                    .ok()
                    .flatten()
            } else {
                None
            };
            let valid = invited.is_some_and(|u| {
                !u.activated
                    && u.activation_token_expires
                        .is_none_or(|e| e >= state.clock.now())
            });
            if !valid {
                return redirect(origin, &intent.error_redirect("ACTIVATION_INVALID"));
            }
        }
        Kind::Setup => {
            let pending = setup::current_state(state).await.ok() == Some(SetupState::PendingAdmin);
            if !pending || !Settings::new(state).admin_group_managed() {
                return redirect(origin, "/setup/admin?ssoError=SETUP_ALREADY_CLAIMED");
            }
        }
    }
    let sso = Settings::new(state);
    let handshake = Handshake {
        state: random_token(),
        nonce: s256(&random_token()),
        verifier: random_token(),
        redirect_uri: format!("{}/login/sso/callback", origin.base_url()),
        intent,
        created: state.clock.now(),
    };
    let mut query = url::form_urlencoded::Serializer::new(String::new());
    query
        .append_pair("response_type", "code")
        .append_pair("client_id", &sso.client_id())
        .append_pair("scope", &sso.effective_scopes().join(" "))
        .append_pair("state", &handshake.state)
        .append_pair("redirect_uri", &handshake.redirect_uri)
        .append_pair("nonce", &handshake.nonce)
        .append_pair("code_challenge", &s256(&handshake.verifier))
        .append_pair("code_challenge_method", "S256");
    if handshake.intent.kind == Kind::Reauth {
        query
            .append_pair("prompt", "login")
            .append_pair("max_age", "0");
    }
    let separator = if provider.authorization_endpoint.contains('?') {
        '&'
    } else {
        '?'
    };
    let location = format!(
        "{}{separator}{}",
        provider.authorization_endpoint,
        query.finish().replace('+', "%20")
    );
    let existing =
        cookies::read(headers, SESSION_COOKIE).filter(|id| state.identity.handshakes.contains(id));
    let session = existing
        .clone()
        .unwrap_or_else(|| hex::encode_upper(rand::random::<[u8; 16]>()));
    state
        .identity
        .handshakes
        .put(session.clone(), handshake, state.clock.now());
    let mut response = redirect(origin, &location);
    if existing.is_none() {
        let secure = if origin.is_secure() { "; Secure" } else { "" };
        if let Ok(value) = HeaderValue::from_str(&format!(
            "{SESSION_COOKIE}={session}; Path=/{secure}; HttpOnly"
        )) {
            response.headers_mut().append(header::SET_COOKIE, value);
        }
    }
    response
}

/// SsoFlowException: a refusal inside the flow, turned into an ssoError redirect.
pub struct Refused(pub &'static str);

/// The identity an IdP vouched for: ID token claims merged with userinfo.
pub struct SsoIdentity {
    pub issuer: String,
    pub subject: String,
    pub email: Option<String>,
    pub display_name: Option<String>,
    pub preferred_username: Option<String>,
    pub auth_time: Option<DateTime<Utc>>,
    pub groups: Option<Vec<String>>,
}

/// GroupsClaimParser: a list of strings, one string, or an object whose keys are the groups.
pub fn parse_groups(value: Option<&Value>) -> Option<Vec<String>> {
    match value? {
        Value::Array(items) => items
            .iter()
            .map(|i| i.as_str().map(str::to_owned))
            .collect::<Option<Vec<_>>>(),
        Value::String(group) => Some(vec![group.clone()]),
        Value::Object(map) => Some(map.keys().cloned().collect()),
        _ => None,
    }
}

#[derive(Deserialize)]
struct TokenResponse {
    id_token: Option<String>,
    access_token: Option<String>,
}

async fn jwks(state: &AppState, provider: &Provider, refresh: bool) -> Result<JwkSet, String> {
    let mut cached = provider.jwks.lock().await;
    if !refresh && let Some(set) = cached.as_ref() {
        return Ok(set.clone());
    }
    let response = state
        .http
        .get(&provider.jwks_uri)
        .timeout(IDP_TIMEOUT)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !response.status().is_success() {
        return Err(format!("jwks answered {}", response.status()));
    }
    let set: JwkSet = response.json().await.map_err(|e| e.to_string())?;
    *cached = Some(set.clone());
    Ok(set)
}

fn audience_contains(aud: Option<&Value>, client_id: &str) -> (bool, usize) {
    match aud {
        Some(Value::String(a)) => (a == client_id, 1),
        Some(Value::Array(list)) => (
            list.iter().any(|a| a.as_str() == Some(client_id)),
            list.len(),
        ),
        _ => (false, 0),
    }
}

/// OidcIdTokenDecoderFactory and OidcIdTokenValidator: an RS256 signature by a key of the
/// provider's JWKS, the issuer, the audience (and azp), exp and iat with 60 s of skew, the nonce.
async fn validate_id_token(
    state: &AppState,
    provider: &Provider,
    token: &str,
    nonce: &str,
) -> Result<Map<String, Value>, String> {
    let head = jsonwebtoken::decode_header(token).map_err(|e| e.to_string())?;
    if head.alg != Algorithm::RS256 {
        return Err("unexpected ID token algorithm".into());
    }
    let mut set = jwks(state, provider, false).await?;
    if head
        .kid
        .as_deref()
        .is_some_and(|kid| set.find(kid).is_none())
    {
        set = jwks(state, provider, true).await?;
    }
    let jwk = match head.kid.as_deref() {
        Some(kid) => set.find(kid),
        None => set.keys.first(),
    }
    .ok_or("no signing key for the ID token")?;
    let key = DecodingKey::from_jwk(jwk).map_err(|e| e.to_string())?;
    let mut validation = Validation::new(Algorithm::RS256);
    validation.validate_exp = false;
    validation.validate_nbf = false;
    validation.validate_aud = false;
    validation.required_spec_claims.clear();
    let claims = jsonwebtoken::decode::<Map<String, Value>>(token, &key, &validation)
        .map_err(|e| e.to_string())?
        .claims;
    let now = state.clock.now().timestamp();
    if claims.get("iss").and_then(Value::as_str) != Some(provider.issuer.as_str()) {
        return Err("ID token issuer mismatch".into());
    }
    let client_id = Settings::new(state).client_id();
    let (contains, audiences) = audience_contains(claims.get("aud"), &client_id);
    if !contains {
        return Err("ID token audience mismatch".into());
    }
    let azp = claims.get("azp").and_then(Value::as_str);
    if (audiences > 1 && azp.is_none()) || azp.is_some_and(|a| a != client_id) {
        return Err("ID token authorized party mismatch".into());
    }
    let exp = claims
        .get("exp")
        .and_then(Value::as_i64)
        .ok_or("ID token without exp")?;
    let iat = claims
        .get("iat")
        .and_then(Value::as_i64)
        .ok_or("ID token without iat")?;
    if now - CLOCK_SKEW > exp {
        return Err("ID token expired".into());
    }
    if iat > now + CLOCK_SKEW {
        return Err("ID token issued in the future".into());
    }
    if claims
        .get("sub")
        .and_then(Value::as_str)
        .is_none_or(str::is_empty)
    {
        return Err("ID token without subject".into());
    }
    if claims.get("nonce").and_then(Value::as_str) != Some(nonce) {
        return Err("ID token nonce mismatch".into());
    }
    Ok(claims)
}

async fn exchange(
    state: &AppState,
    provider: &Provider,
    handshake: &Handshake,
    code: &str,
) -> Result<SsoIdentity, String> {
    let sso = Settings::new(state);
    let mut form = vec![
        ("grant_type", "authorization_code".to_owned()),
        ("code", code.to_owned()),
        ("redirect_uri", handshake.redirect_uri.clone()),
        ("code_verifier", handshake.verifier.clone()),
    ];
    let mut request = state
        .http
        .post(&provider.token_endpoint)
        .timeout(IDP_TIMEOUT);
    if sso.client_secret().is_empty() {
        form.push(("client_id", sso.client_id()));
    } else {
        let credentials = format!(
            "{}:{}",
            java_url_encode(&sso.client_id()),
            java_url_encode(&sso.client_secret())
        );
        request = request.header(
            header::AUTHORIZATION,
            format!(
                "Basic {}",
                base64::engine::general_purpose::STANDARD.encode(credentials)
            ),
        );
    }
    let response = request
        .header(header::ACCEPT, "application/json")
        .form(&form)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !response.status().is_success() {
        return Err(format!("token endpoint answered {}", response.status()));
    }
    let tokens: TokenResponse = response.json().await.map_err(|e| e.to_string())?;
    let id_token = tokens.id_token.ok_or("no ID token")?;
    let mut claims = validate_id_token(state, provider, &id_token, &handshake.nonce).await?;
    let issuer = claims
        .get("iss")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned();
    let subject = claims
        .get("sub")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned();
    let auth_time = claims
        .get("auth_time")
        .and_then(Value::as_i64)
        .and_then(|t| DateTime::from_timestamp(t, 0));
    if let Some(userinfo) = &provider.userinfo_endpoint {
        let access = tokens.access_token.ok_or("no access token for userinfo")?;
        let response = state
            .http
            .get(userinfo)
            .bearer_auth(access)
            .timeout(IDP_TIMEOUT)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        if !response.status().is_success() {
            return Err(format!("userinfo answered {}", response.status()));
        }
        let info: Map<String, Value> = response.json().await.map_err(|e| e.to_string())?;
        if info.get("sub").and_then(Value::as_str) != Some(subject.as_str()) {
            return Err("userinfo subject mismatch".into());
        }
        claims.extend(info);
    }
    let text = |name: &str| claims.get(name).and_then(Value::as_str).map(str::to_owned);
    Ok(SsoIdentity {
        issuer,
        subject,
        email: text("email"),
        display_name: text("name"),
        preferred_username: text("preferred_username"),
        auth_time,
        groups: parse_groups(claims.get(&sso.groups_claim())),
    })
}

async fn callback(
    state: &AppState,
    origin: &RequestOrigin,
    ip: &ClientIp,
    headers: &HeaderMap,
    path: &str,
    params: &HashMap<String, String>,
) -> Response {
    let session = cookies::read(headers, SESSION_COOKIE).unwrap_or_default();
    let has_response = params.contains_key("state")
        && (params.contains_key("code") || params.contains_key("error"));
    if !has_response {
        state.identity.handshakes.take(&session, state.clock.now());
        return redirect(origin, &login_error("IDP_ERROR"));
    }
    let handshake = state
        .identity
        .handshakes
        .take(&session, state.clock.now())
        .filter(|h| params.get("state") == Some(&h.state));
    let Some(handshake) = handshake else {
        return redirect(origin, &login_error("SESSION_EXPIRED"));
    };
    let intent = handshake.intent.clone();
    let identity = match (
        path.strip_prefix(CODE_PREFIX) == Some(REGISTRATION_ID),
        params.get("code"),
        provider(state).await,
    ) {
        (true, Some(code), Some(provider)) if !params.contains_key("error") => {
            match exchange(state, &provider, &handshake, code).await {
                Ok(identity) => identity,
                Err(e) => {
                    tracing::warn!("SSO authentication failed: {}", log_safe(&e));
                    return redirect(origin, &intent.error_redirect("IDP_ERROR"));
                }
            }
        }
        _ => {
            tracing::warn!("SSO authentication failed: the IdP returned no code");
            return redirect(origin, &intent.error_redirect("IDP_ERROR"));
        }
    };
    let request = account::Request { ip, headers };
    let target = match account::complete(state, &intent, &identity, request).await {
        Ok((target, jar)) => return with_cookies(redirect(origin, &target), jar),
        Err(Refused(code)) => intent.error_redirect(code),
    };
    redirect(origin, &target)
}

fn with_cookies(mut response: Response, jar: Cookies) -> Response {
    for header in jar.headers() {
        if let Ok(value) = HeaderValue::from_str(header) {
            response.headers_mut().append(header::SET_COOKIE, value);
        }
    }
    response
}
