//! The authorization server endpoints: authorize (and its consent POST), token, revoke,
//! introspect, the device endpoints, the RFC 8414 and RFC 9728 metadata and the JWKS.

use std::collections::HashMap;

use axum::body::Bytes;
use axum::extract::State;
use axum::http::{HeaderMap, HeaderValue, Method, StatusCode, Uri, header};
use axum::response::{IntoResponse, Response};
use base64::Engine;
use base64::engine::general_purpose::{URL_SAFE, URL_SAFE_NO_PAD};
use chrono::{DateTime, NaiveDateTime, Utc};
use jsonwebtoken::{Algorithm, EncodingKey, Header};
use rand::RngExt;
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};
use uuid::Uuid;

use super::clients::{self, RegisteredClient};
use super::jackson::{self, AuthorizationRequest, PrincipalSnapshot};
use super::{AUTHORIZATION_CODE_TTL, encode_component, encode_query_value, from_local, to_local};
use crate::AppState;
use crate::auth::cookies;
use crate::auth::{AppUser, layer};
use crate::error::{AppResult, raw};
use crate::http::client_ip::RequestOrigin;
use crate::identity::common::{java_url_encode, redirect};
use crate::identity::scopes;

const PKCE_ERROR_URI: &str = "https://datatracker.ietf.org/doc/html/rfc7636#section-4.4.1";
const AUTHZ_ERROR_URI: &str = "https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1";
const TOKEN_ERROR_URI: &str = "https://datatracker.ietf.org/doc/html/rfc6749#section-5.2";
const JSON_UTF8: &str = "application/json;charset=UTF-8";
const AUTH_METHODS: [&str; 6] = [
    "client_secret_basic",
    "client_secret_post",
    "client_secret_jwt",
    "private_key_jwt",
    "tls_client_auth",
    "self_signed_tls_client_auth",
];

fn random_bytes<const N: usize>() -> [u8; N] {
    let mut bytes = [0u8; N];
    rand::rng().fill(&mut bytes);
    bytes
}

/// Repeated parameters keep every value, in order (query string first, then the form body).
fn parameters(uri: &Uri, headers: &HeaderMap, body: &Bytes) -> HashMap<String, Vec<String>> {
    let mut out: HashMap<String, Vec<String>> = HashMap::new();
    let form = headers
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|c| c.starts_with("application/x-www-form-urlencoded"));
    let sources: [&[u8]; 2] = [uri.query().unwrap_or_default().as_bytes(), if form { body } else { &[] }];
    for source in sources {
        for (k, v) in url::form_urlencoded::parse(source) {
            out.entry(k.into_owned()).or_default().push(v.into_owned());
        }
    }
    out
}

fn single<'a>(params: &'a HashMap<String, Vec<String>>, name: &str) -> Option<&'a str> {
    params
        .get(name)
        .and_then(|v| v.first())
        .map(String::as_str)
        .filter(|v| !v.is_empty())
}

/// The cookie bridge: only the access_token cookie authenticates the authorization endpoint,
/// and a GET with an SSO session past its revalidation window stays anonymous.
async fn cookie_principal(state: &AppState, headers: &HeaderMap, method: &Method) -> Option<AppUser> {
    let token = cookies::read(headers, cookies::ACCESS)?;
    if *method == Method::GET
        && let Ok(claims) = state.jwt.parse(&token)
        && let Some(sso_at) = claims.sso_authenticated_at()
    {
        let window = chrono::Duration::from_std(state.config.sso.session_revalidate_after)
            .unwrap_or_default();
        if !crate::identity::sso::Settings::new(state).enabled() || sso_at + window < state.clock.now() {
            return None;
        }
    }
    layer::authenticate_access_token(state, &token)
        .await
        .map(|p| p.user)
}

/// The authorization server chain's entry point: the SPA login, carrying the request.
fn login_redirect(origin: &RequestOrigin, uri: &Uri) -> Response {
    let target = match uri.query() {
        Some(q) => format!("{}?{q}", uri.path()),
        None => uri.path().to_owned(),
    };
    redirect(origin, &format!("/login?redirect={}", java_url_encode(&target)))
}

/// Spring Boot's /error body for an authorization request it cannot redirect.
fn bad_request(state: &AppState, path: &str) -> Response {
    let timestamp = state
        .clock
        .now()
        .with_timezone(&state.clock.zone())
        .format("%Y-%m-%dT%H:%M:%S%.3f%:z")
        .to_string();
    let body = json!({"timestamp": timestamp, "status": 400, "error": "Bad Request", "path": path});
    (
        StatusCode::BAD_REQUEST,
        [(header::CONTENT_TYPE, "application/json")],
        body.to_string(),
    )
        .into_response()
}

fn error_redirect(redirect_uri: &str, error: &str, parameter: &str, error_uri: &str, state: Option<&str>) -> Response {
    let mut location = format!(
        "{redirect_uri}{}error={error}&error_description={}&error_uri={}",
        if redirect_uri.contains('?') { '&' } else { '?' },
        encode_component(&format!("OAuth 2.0 Parameter: {parameter}")),
        encode_component(error_uri),
    );
    if let Some(state) = state {
        location.push_str("&state=");
        location.push_str(&encode_component(state));
    }
    to_client(&location)
}

/// A redirect to the client's own URI, which may be a custom scheme: never made absolute.
fn to_client(location: &str) -> Response {
    let mut response = StatusCode::FOUND.into_response();
    if let Ok(value) = HeaderValue::from_str(location) {
        response.headers_mut().insert(header::LOCATION, value);
    }
    response
}

fn is_loopback(host: &str) -> bool {
    host == "[::1]"
        || host == "[0:0:0:0:0:0:0:1]"
        || host
            .strip_prefix("127.")
            .is_some_and(|rest| rest.split('.').count() == 3 && rest.split('.').all(|o| o.parse::<u8>().is_ok()))
}

/// The redirect URI an authorization request may use: the registered one when it has none,
/// an exact match, or a loopback IP literal whose port differs (RFC 8252).
fn resolve_redirect(client: &RegisteredClient, requested: Option<&str>) -> Option<String> {
    let Some(requested) = requested else {
        return (client.redirect_uris.len() == 1).then(|| client.redirect_uris[0].clone());
    };
    let parsed = url::Url::parse(requested).ok()?;
    if parsed.fragment().is_some() {
        return None;
    }
    let host = parsed.host_str().unwrap_or_default();
    if host.eq_ignore_ascii_case("localhost") {
        return None;
    }
    if !is_loopback(host) {
        return client
            .redirect_uris
            .iter()
            .any(|r| r == requested)
            .then(|| requested.to_owned());
    }
    let same_but_port = |registered: &String| {
        url::Url::parse(registered).is_ok_and(|r| {
            r.scheme() == parsed.scheme()
                && r.host_str() == parsed.host_str()
                && r.path() == parsed.path()
                && r.query() == parsed.query()
        })
    };
    client
        .redirect_uris
        .iter()
        .any(same_but_port)
        .then(|| requested.to_owned())
}

fn principal_snapshot(user: &AppUser) -> PrincipalSnapshot {
    PrincipalSnapshot {
        id: user.id,
        username: user.username.clone(),
        role: user.role.as_str().to_owned(),
        token_version: user.token_version,
    }
}

async fn consented_scopes(state: &AppState, client: &RegisteredClient, principal: &str) -> Result<Option<Vec<String>>, sqlx::Error> {
    let authorities: Option<String> = sqlx::query_scalar(
        "SELECT authorities FROM oauth2_authorization_consent WHERE registered_client_id = $1 AND principal_name = $2",
    )
    .bind(&client.id)
    .bind(principal)
    .fetch_optional(&state.db)
    .await?;
    Ok(authorities.map(|a| {
        a.split(',')
            .filter_map(|x| x.trim().strip_prefix("SCOPE_").map(str::to_owned))
            .collect()
    }))
}

struct CodeGrant<'a> {
    client: &'a RegisteredClient,
    user: PrincipalSnapshot,
    request: AuthorizationRequest,
    scopes: Vec<String>,
}

/// Saves an authorization holding a fresh code and redirects the browser back to the client.
async fn issue_code(state: &AppState, grant: CodeGrant<'_>, existing: Option<&str>, redirect_uri: &str) -> AppResult<Response> {
    let code = URL_SAFE_NO_PAD.encode(random_bytes::<96>());
    let now = state.clock.now();
    let attributes = jackson::attributes(&grant.user, &grant.request, None);
    let scopes = grant.scopes.join(",");
    let id = existing.map_or_else(|| Uuid::new_v4().to_string(), str::to_owned);
    sqlx::query(
        "INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, authorization_grant_type, \
         authorized_scopes, attributes, state, authorization_code_value, authorization_code_issued_at, \
         authorization_code_expires_at, authorization_code_metadata) \
         VALUES ($1, $2, $3, 'authorization_code', $4, $5, NULL, $6, $7, $8, $9) \
         ON CONFLICT (id) DO UPDATE SET authorized_scopes = EXCLUDED.authorized_scopes, attributes = EXCLUDED.attributes, \
         state = NULL, authorization_code_value = EXCLUDED.authorization_code_value, \
         authorization_code_issued_at = EXCLUDED.authorization_code_issued_at, \
         authorization_code_expires_at = EXCLUDED.authorization_code_expires_at, \
         authorization_code_metadata = EXCLUDED.authorization_code_metadata",
    )
    .bind(&id)
    .bind(&grant.client.id)
    .bind(&grant.user.username)
    .bind((!scopes.is_empty()).then_some(scopes))
    .bind(attributes)
    .bind(&code)
    .bind(to_local(state, now))
    .bind(to_local(state, now + AUTHORIZATION_CODE_TTL))
    .bind(jackson::code_metadata(false))
    .execute(&state.db)
    .await?;
    let mut location = format!(
        "{redirect_uri}{}code={code}",
        if redirect_uri.contains('?') { '&' } else { '?' }
    );
    if let Some(s) = &grant.request.state {
        location.push_str("&state=");
        location.push_str(&encode_component(s));
    }
    Ok(to_client(&location))
}

/// GET (or POST with response_type) /oauth2/authorize, and the consent POST.
#[allow(clippy::too_many_arguments)]
pub async fn authorize(
    State(state): State<AppState>,
    origin: RequestOrigin,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> AppResult<Response> {
    let params = parameters(&uri, &headers, &body);
    if method == Method::POST && !params.contains_key("response_type") {
        return consent(&state, &headers, &params).await;
    }
    let path = uri.path();
    let duplicated = ["response_type", "client_id", "redirect_uri", "scope", "state", "code_challenge", "code_challenge_method"]
        .iter()
        .any(|p| params.get(*p).is_some_and(|v| v.len() > 1));
    let Some(client_id) = single(&params, "client_id") else {
        return Ok(bad_request(&state, path));
    };
    if duplicated || single(&params, "response_type") != Some("code") {
        return Ok(bad_request(&state, path));
    }
    let Some(client) = clients::find_by_client_id(&state, client_id).await? else {
        return Ok(bad_request(&state, path));
    };
    let requested_redirect = single(&params, "redirect_uri");
    let Some(redirect_uri) = resolve_redirect(&client, requested_redirect) else {
        return Ok(bad_request(&state, path));
    };
    let state_param = single(&params, "state");
    if !client.grant_types.iter().any(|g| g == "authorization_code") {
        return Ok(error_redirect(&redirect_uri, "unauthorized_client", "client_id", AUTHZ_ERROR_URI, state_param));
    }
    let mut requested_scopes: Vec<String> = Vec::new();
    for scope in single(&params, "scope").unwrap_or_default().split_whitespace() {
        if !requested_scopes.iter().any(|s| s == scope) {
            requested_scopes.push(scope.to_owned());
        }
    }
    if requested_scopes.iter().any(|s| !client.scopes.contains(s)) {
        return Ok(error_redirect(&redirect_uri, "invalid_scope", "scope", AUTHZ_ERROR_URI, state_param));
    }
    let challenge = single(&params, "code_challenge");
    let challenge_method = single(&params, "code_challenge_method");
    if challenge.is_some() {
        if challenge_method != Some("S256") {
            return Ok(error_redirect(&redirect_uri, "invalid_request", "code_challenge_method", PKCE_ERROR_URI, state_param));
        }
    } else if client.require_proof_key {
        return Ok(error_redirect(&redirect_uri, "invalid_request", "code_challenge", PKCE_ERROR_URI, state_param));
    }
    let Some(user) = cookie_principal(&state, &headers, &method).await else {
        return Ok(login_redirect(&origin, &uri));
    };
    let request = AuthorizationRequest {
        authorization_uri: format!("{}{path}", origin.base_url()),
        client_id: client.client_id.clone(),
        redirect_uri: params.get("redirect_uri").and_then(|v| v.first()).cloned(),
        scopes: requested_scopes.clone(),
        state: state_param.map(str::to_owned),
        code_challenge: challenge.map(str::to_owned),
        code_challenge_method: challenge_method.map(str::to_owned),
    };
    let snapshot = principal_snapshot(&user);
    let needs_consent = client.require_consent
        && !(requested_scopes.len() == 1 && requested_scopes[0] == "openid")
        && !consented_scopes(&state, &client, &user.username)
            .await?
            .is_some_and(|c| requested_scopes.iter().all(|s| c.contains(s)));
    if needs_consent {
        let consent_state = URL_SAFE.encode(random_bytes::<32>());
        sqlx::query(
            "INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, authorization_grant_type, \
             attributes, state) VALUES ($1, $2, $3, 'authorization_code', $4, $5)",
        )
        .bind(Uuid::new_v4().to_string())
        .bind(&client.id)
        .bind(&user.username)
        .bind(jackson::attributes(&snapshot, &request, Some(&consent_state)))
        .bind(&consent_state)
        .execute(&state.db)
        .await?;
        let location = format!(
            "{}/consent?scope={}&client_id={}&state={}",
            origin.base_url(),
            encode_query_value(&requested_scopes.join(" ")),
            encode_query_value(&client.client_id),
            encode_query_value(&consent_state),
        );
        return Ok(to_client(&location));
    }
    issue_code(
        &state,
        CodeGrant {
            client: &client,
            user: snapshot,
            request,
            scopes: requested_scopes,
        },
        None,
        &redirect_uri,
    )
    .await
}

#[derive(sqlx::FromRow)]
struct PendingConsent {
    id: String,
    registered_client_id: String,
    principal_name: String,
    attributes: Option<String>,
}

/// OAuth2AuthorizationConsentAuthenticationProvider: the approved scopes, plus those consented
/// before, become the authorization; none at all is a denial.
async fn consent(
    state: &AppState,
    headers: &HeaderMap,
    params: &HashMap<String, Vec<String>>,
) -> AppResult<Response> {
    let unauthorized = || raw::response(StatusCode::UNAUTHORIZED, raw::UNAUTHORIZED);
    let Some(user) = cookie_principal(state, headers, &Method::POST).await else {
        return Ok(unauthorized());
    };
    let (Some(client_id), Some(consent_state)) = (single(params, "client_id"), single(params, "state")) else {
        return Ok(unauthorized());
    };
    let pending: Option<PendingConsent> = sqlx::query_as(
        "SELECT id, registered_client_id, principal_name, attributes FROM oauth2_authorization WHERE state = $1",
    )
    .bind(consent_state)
    .fetch_optional(&state.db)
    .await?;
    let Some(pending) = pending.filter(|p| p.principal_name == user.username) else {
        return Ok(unauthorized());
    };
    let Some(client) = clients::find_by_id(state, &pending.registered_client_id)
        .await?
        .filter(|c| c.client_id == client_id)
    else {
        return Ok(unauthorized());
    };
    let Some((snapshot, request)) = pending.attributes.as_deref().and_then(jackson::parse_attributes) else {
        return Ok(unauthorized());
    };
    let redirect_uri = resolve_redirect(&client, request.redirect_uri.as_deref().filter(|r| !r.is_empty()))
        .unwrap_or_default();
    let submitted: Vec<String> = params.get("scope").cloned().unwrap_or_default();
    if submitted.iter().any(|s| !request.scopes.contains(s)) {
        return Ok(error_redirect(&redirect_uri, "invalid_scope", "scope", AUTHZ_ERROR_URI, request.state.as_deref()));
    }
    let previous = consented_scopes(state, &client, &user.username).await?;
    let mut authorized: Vec<String> = Vec::new();
    for scope in submitted
        .iter()
        .chain(previous.iter().flatten().filter(|s| request.scopes.contains(s)))
    {
        if !authorized.contains(scope) {
            authorized.push(scope.clone());
        }
    }
    let mut authorities: Vec<String> = previous.unwrap_or_default();
    for scope in &authorized {
        if !authorities.contains(scope) {
            authorities.push(scope.clone());
        }
    }
    // Earlier consents count: only a client never consented to, approved nothing, is denied.
    if authorities.is_empty() {
        sqlx::query("DELETE FROM oauth2_authorization WHERE id = $1")
            .bind(&pending.id)
            .execute(&state.db)
            .await?;
        return Ok(error_redirect(&redirect_uri, "access_denied", "client_id", AUTHZ_ERROR_URI, request.state.as_deref()));
    }
    let authorities: Vec<String> = authorities.iter().map(|s| format!("SCOPE_{s}")).collect();
    sqlx::query(
        "INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities) VALUES ($1, $2, $3) \
         ON CONFLICT (registered_client_id, principal_name) DO UPDATE SET authorities = EXCLUDED.authorities",
    )
    .bind(&client.id)
    .bind(&user.username)
    .bind(authorities.join(","))
    .execute(&state.db)
    .await?;
    issue_code(
        state,
        CodeGrant {
            client: &client,
            user: snapshot,
            request,
            scopes: authorized,
        },
        Some(&pending.id),
        &redirect_uri,
    )
    .await
}

fn token_error(status: StatusCode, error: &str, description: Option<&str>) -> Response {
    let mut body = Map::new();
    body.insert("error".into(), error.into());
    if let Some(d) = description {
        body.insert("error_description".into(), d.into());
        body.insert("error_uri".into(), TOKEN_ERROR_URI.into());
    }
    (status, [(header::CONTENT_TYPE, JSON_UTF8)], Value::Object(body).to_string()).into_response()
}

fn invalid_client() -> Response {
    token_error(StatusCode::UNAUTHORIZED, "invalid_client", None)
}

fn invalid_grant() -> Response {
    token_error(StatusCode::BAD_REQUEST, "invalid_grant", None)
}

/// Client authentication of the token, revocation, introspection and device endpoints: a
/// secret or an assertion is never valid (every client is public), no credential at all leaves
/// the request anonymous.
enum ClientAuth {
    Rejected,
    Anonymous,
}

fn confidential_attempt(headers: &HeaderMap, params: &HashMap<String, Vec<String>>) -> ClientAuth {
    let basic = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| v.len() >= 6 && v[..6].eq_ignore_ascii_case("basic "));
    if basic || params.contains_key("client_secret") || params.contains_key("client_assertion") {
        ClientAuth::Rejected
    } else {
        ClientAuth::Anonymous
    }
}

#[derive(sqlx::FromRow)]
struct CodeAuthorization {
    id: String,
    registered_client_id: String,
    authorized_scopes: Option<String>,
    attributes: Option<String>,
    authorization_code_expires_at: Option<NaiveDateTime>,
    authorization_code_metadata: Option<String>,
    access_token_metadata: Option<String>,
}

fn s256(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

fn sign(state: &AppState, claims: &Map<String, Value>) -> String {
    let header = Header {
        kid: Some("picsou-hs256".to_owned()),
        typ: None,
        ..Header::new(Algorithm::HS256)
    };
    EncodingKey::from_secret(state.config.jwt.secret.as_bytes())
        .and_then(|key| jsonwebtoken::encode(&header, claims, &key))
        .unwrap_or_default()
}

/// The claims JwtGenerator and the token customizer produce: the first-party shape, or the MCP
/// one (type=mcp, aud=picsou-mcp, space-joined scope, no role).
fn access_claims(
    origin: &RequestOrigin,
    client: &RegisteredClient,
    user: &PrincipalSnapshot,
    scopes: &[String],
    issued: DateTime<Utc>,
    expires: DateTime<Utc>,
) -> Map<String, Value> {
    let mut c = Map::new();
    c.insert("sub".into(), user.username.clone().into());
    let audience = if client.mcp { crate::auth::MCP_AUDIENCE.to_owned() } else { client.client_id.clone() };
    c.insert("aud".into(), audience.into());
    c.insert("uid".into(), user.id.into());
    c.insert("tv".into(), user.token_version.into());
    c.insert("nbf".into(), issued.timestamp().into());
    if client.mcp {
        c.insert("scope".into(), scopes.join(" ").into());
    } else {
        c.insert("role".into(), user.role.clone().into());
        if !scopes.is_empty() {
            c.insert("scope".into(), scopes.to_vec().into());
        }
    }
    c.insert("iss".into(), origin.base_url().into());
    c.insert("exp".into(), expires.timestamp().into());
    c.insert("type".into(), if client.mcp { "mcp" } else { "access" }.into());
    c.insert("iat".into(), issued.timestamp().into());
    c.insert("jti".into(), Uuid::new_v4().to_string().into());
    c
}

/// POST /oauth2/token. Only the PKCE authorization-code request of a public client
/// authenticates; Java mints no refresh token for a public client, so a refresh request never
/// authenticates either.
pub async fn token(
    State(state): State<AppState>,
    origin: RequestOrigin,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> AppResult<Response> {
    let params = parameters(&uri, &headers, &body);
    if let ClientAuth::Rejected = confidential_attempt(&headers, &params) {
        return Ok(invalid_client());
    }
    let (grant_type, code, verifier) = (
        single(&params, "grant_type"),
        single(&params, "code"),
        single(&params, "code_verifier"),
    );
    let (Some("authorization_code"), Some(code), Some(verifier)) = (grant_type, code, verifier) else {
        return Ok(login_redirect(&origin, &uri));
    };
    let client_id = match params.get("client_id") {
        Some(values) if values.len() == 1 && !values[0].is_empty() => values[0].clone(),
        _ => return Ok(token_error(StatusCode::BAD_REQUEST, "invalid_request", None)),
    };
    let Some(client) = clients::find_by_client_id(&state, &client_id).await? else {
        return Ok(invalid_client());
    };
    if !client.authentication_methods.iter().any(|m| m == "none") {
        return Ok(invalid_client());
    }
    let found: Option<CodeAuthorization> = sqlx::query_as(
        "SELECT id, registered_client_id, principal_name, authorized_scopes, attributes, \
         authorization_code_expires_at, authorization_code_metadata, access_token_metadata \
         FROM oauth2_authorization WHERE authorization_code_value = $1",
    )
    .bind(code)
    .fetch_optional(&state.db)
    .await?;
    let Some(found) = found else {
        return Ok(invalid_grant());
    };
    let Some((snapshot, request)) = found.attributes.as_deref().and_then(jackson::parse_attributes) else {
        return Ok(invalid_grant());
    };
    let pkce_ok = match request.code_challenge.as_deref() {
        Some(challenge) => {
            request.code_challenge_method.as_deref().is_none_or(|m| m == "S256") && s256(verifier) == challenge
        }
        None => !client.require_proof_key,
    };
    if !pkce_ok || found.registered_client_id != client.id {
        return Ok(invalid_grant());
    }
    if let Some(expected) = request.redirect_uri.as_deref().filter(|r| !r.is_empty())
        && single(&params, "redirect_uri") != Some(expected)
    {
        return Ok(invalid_grant());
    }
    let now = state.clock.now();
    if jackson::token_metadata_invalidated(found.authorization_code_metadata.as_deref()) {
        // A replayed code revokes what it minted.
        if let Some(meta) = &found.access_token_metadata {
            sqlx::query("UPDATE oauth2_authorization SET access_token_metadata = $2 WHERE id = $1")
                .bind(&found.id)
                .bind(jackson::invalidate(meta))
                .execute(&state.db)
                .await?;
        }
        return Ok(invalid_grant());
    }
    if found
        .authorization_code_expires_at
        .is_some_and(|e| from_local(&state, e) <= now)
    {
        return Ok(invalid_grant());
    }
    let scopes: Vec<String> = found
        .authorized_scopes
        .as_deref()
        .unwrap_or_default()
        .split(',')
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
        .collect();
    let expires = now + chrono::Duration::seconds(client.access_token_ttl);
    let claims = access_claims(&origin, &client, &snapshot, &scopes, now, expires);
    let access_token = sign(&state, &claims);
    sqlx::query(
        "UPDATE oauth2_authorization SET authorization_code_metadata = $2, access_token_value = $3, \
         access_token_issued_at = $4, access_token_expires_at = $5, access_token_metadata = $6, \
         access_token_type = 'Bearer', access_token_scopes = $7 WHERE id = $1",
    )
    .bind(&found.id)
    .bind(jackson::code_metadata(true))
    .bind(&access_token)
    .bind(to_local(&state, now))
    .bind(to_local(&state, expires))
    .bind(jackson::access_metadata(&claims, false))
    .bind((!scopes.is_empty()).then(|| scopes.join(",")))
    .execute(&state.db)
    .await?;
    let mut body = Map::new();
    body.insert("access_token".into(), access_token.into());
    if !scopes.is_empty() {
        body.insert("scope".into(), scopes.join(" ").into());
    }
    body.insert("token_type".into(), "Bearer".into());
    body.insert("expires_in".into(), (expires - state.clock.now()).num_seconds().into());
    Ok((StatusCode::OK, [(header::CONTENT_TYPE, JSON_UTF8)], Value::Object(body).to_string()).into_response())
}

/// Revocation, introspection and device authorization need a client secret or assertion no
/// client has: a credential is invalid_client, none sends the browser to the login page.
pub async fn client_authenticated(
    origin: RequestOrigin,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    match confidential_attempt(&headers, &parameters(&uri, &headers, &body)) {
        ClientAuth::Rejected => invalid_client(),
        ClientAuth::Anonymous => login_redirect(&origin, &uri),
    }
}

/// GET /oauth2/device_verification: no device code can exist, so a signed-in browser finds
/// nothing to verify.
pub async fn device_verification(
    State(state): State<AppState>,
    origin: RequestOrigin,
    uri: Uri,
    headers: HeaderMap,
) -> Response {
    match cookie_principal(&state, &headers, &Method::GET).await {
        Some(_) => crate::http::not_found(uri.path()),
        None => login_redirect(&origin, &uri),
    }
}

/// RFC 8414 metadata, plus the registration endpoint Spring has no built-in for.
pub async fn authorization_server_metadata(origin: RequestOrigin) -> Response {
    let issuer = origin.base_url();
    let endpoint = |path: &str| format!("{issuer}{path}");
    let body = json!({
        "issuer": issuer,
        "authorization_endpoint": endpoint("/oauth2/authorize"),
        "device_authorization_endpoint": endpoint("/oauth2/device_authorization"),
        "token_endpoint": endpoint("/oauth2/token"),
        "token_endpoint_auth_methods_supported": AUTH_METHODS,
        "jwks_uri": endpoint("/oauth2/jwks"),
        "response_types_supported": ["code"],
        "grant_types_supported": ["authorization_code", "client_credentials", "refresh_token",
            "urn:ietf:params:oauth:grant-type:device_code", "urn:ietf:params:oauth:grant-type:token-exchange"],
        "revocation_endpoint": endpoint("/oauth2/revoke"),
        "revocation_endpoint_auth_methods_supported": AUTH_METHODS,
        "introspection_endpoint": endpoint("/oauth2/introspect"),
        "introspection_endpoint_auth_methods_supported": AUTH_METHODS,
        "code_challenge_methods_supported": ["S256"],
        "tls_client_certificate_bound_access_tokens": true,
        "dpop_signing_alg_values_supported": ["RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512"],
        "registration_endpoint": endpoint("/oauth2/register"),
    });
    ([(header::CONTENT_TYPE, "application/json")], body.to_string()).into_response()
}

/// The HS256 key has no public half, so the set is empty.
pub async fn jwks() -> Response {
    (
        [(header::CONTENT_TYPE, "application/json;charset=ISO-8859-1")],
        "{\"keys\":[]}",
    )
        .into_response()
}

/// RFC 9728 metadata for the /mcp resource, from the request's own origin.
pub async fn protected_resource_metadata(origin: RequestOrigin) -> Response {
    let base = origin.base_url();
    let body = json!({
        "resource": format!("{base}/mcp"),
        "authorization_servers": [base],
        "scopes_supported": scopes::sorted(),
        "bearer_methods_supported": ["header"],
    });
    ([(header::CONTENT_TYPE, "application/json")], body.to_string()).into_response()
}
