//! LouveOAuthClient: dynamic client registration (RFC 7591), the authorization URL with PKCE
//! S256, the code exchange, the refresh and the revocation. Louve registers public clients
//! (token_endpoint_auth_method none), so there is no client secret anywhere.
//!
//! Only portfolio:read is ever requested. Nothing here logs a token, a code, a verifier or a
//! response body; failures are logged by HTTP status and OAuth error name only.

use std::sync::LazyLock;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use rand::RngExt;
use regex::Regex;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use super::error::{Failure, Kind, LouveResult};
use super::transport;
use crate::AppState;
use crate::ledger::sidecar::SidecarErrorCode;

/// The issuer Louve names on its callback (RFC 9207); fixed whatever base URL the calls use.
pub const ISSUER: &str = "https://app.louveinvest.com";
/// The protected resource the tokens are for: ISSUER + /mcp.
pub const RESOURCE: &str = "https://app.louveinvest.com/mcp";
pub const SCOPE: &str = "portfolio:read";

const CLIENT_NAME: &str = "Picsou";
const DEFAULT_EXPIRES_IN: i64 = 3600;
/// A client id is put in a URL and a form; anything outside this set is refused.
static CLIENT_ID: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Za-z0-9._~-]{1,255}$").expect("valid regex"));
static OAUTH_ERROR: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[a-z_]{1,40}$").expect("valid regex"));

/// What the token endpoint granted. refresh_token may be None when Louve keeps the old one.
pub struct Tokens {
    pub access_token: String,
    pub refresh_token: Option<String>,
    pub expires_in_seconds: i64,
}

fn endpoint(state: &AppState, path: &str) -> String {
    format!(
        "{}{path}",
        state.config.sync_url("louve").trim_end_matches('/')
    )
}

/// A random PKCE verifier or state: 32 bytes, base64url without padding (43 characters).
pub fn random_token() -> String {
    let mut bytes = [0u8; 32];
    rand::rng().fill(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

/// BASE64URL(SHA-256(verifier)), the S256 challenge.
pub fn challenge(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

fn form(pairs: &[(&str, &str)]) -> String {
    let mut serializer = url::form_urlencoded::Serializer::new(String::new());
    for (key, value) in pairs {
        serializer.append_pair(key, value);
    }
    serializer.finish()
}

async fn send(
    state: &AppState,
    url: &str,
    content_type: &str,
    body: String,
) -> LouveResult<transport::Response> {
    transport::post(
        &state.sync.louve.http,
        url,
        &[
            ("Content-Type", content_type),
            ("Accept", "application/json"),
        ],
        body,
    )
    .await
    .map_err(|_| Failure::unavailable())
}

/// Registers Picsou as a public client for redirect_uri and returns the client id.
pub async fn register(state: &AppState, redirect_uri: &str) -> LouveResult<String> {
    let body = json!({
        "client_name": CLIENT_NAME,
        "redirect_uris": [redirect_uri],
        "grant_types": ["authorization_code", "refresh_token"],
        "response_types": ["code"],
        "token_endpoint_auth_method": "none",
        "scope": SCOPE,
    });
    let response = send(
        state,
        &endpoint(state, "/mcp/oauth/register"),
        "application/json",
        body.to_string(),
    )
    .await?;
    if response.status == 429 {
        return Err(Failure::rate_limited());
    }
    if response.status >= 500 {
        return Err(Failure::unavailable());
    }
    if response.status != 200 && response.status != 201 {
        tracing::warn!(
            "Louve Invest refused the client registration (status={}; error={})",
            response.status,
            oauth_error(&response.body)
        );
        return Err(Failure::format_changed(
            "Louve Invest refused to register this Picsou address. Check the address Picsou is reached at.",
        ));
    }
    let client_id = text(&parse(&response.body)?, "client_id");
    match client_id {
        Some(id) if CLIENT_ID.is_match(&id) => Ok(id),
        _ => Err(Failure::format_changed(
            "Louve Invest returned no usable client id",
        )),
    }
}

/// The consent page the member's browser is sent to. Only portfolio:read is asked for.
pub fn authorize_uri(
    state: &AppState,
    client_id: &str,
    redirect_uri: &str,
    oauth_state: &str,
    code_challenge: &str,
) -> String {
    let query = form(&[
        ("response_type", "code"),
        ("client_id", client_id),
        ("redirect_uri", redirect_uri),
        ("scope", SCOPE),
        ("state", oauth_state),
        ("code_challenge", code_challenge),
        ("code_challenge_method", "S256"),
        ("resource", RESOURCE),
    ]);
    format!("{}?{query}", endpoint(state, "/mcp/oauth/authorize"))
}

/// A refused code (invalid_grant: used, expired, or for another verifier) is
/// AUTH_ATTEMPT_EXPIRED: the member starts again.
pub async fn exchange_code(
    state: &AppState,
    client_id: &str,
    redirect_uri: &str,
    code: &str,
    verifier: &str,
) -> LouveResult<Tokens> {
    let body = form(&[
        ("grant_type", "authorization_code"),
        ("code", code),
        ("redirect_uri", redirect_uri),
        ("client_id", client_id),
        ("code_verifier", verifier),
        ("resource", RESOURCE),
    ]);
    token(
        state,
        body,
        SidecarErrorCode::AuthAttemptExpired,
        "code exchange",
        client_id,
        Some(redirect_uri),
    )
    .await
}

/// A refused refresh token is SESSION_EXPIRED: only a new consent helps.
pub async fn refresh(
    state: &AppState,
    client_id: &str,
    refresh_token: &str,
) -> LouveResult<Tokens> {
    let body = form(&[
        ("grant_type", "refresh_token"),
        ("refresh_token", refresh_token),
        ("client_id", client_id),
        ("resource", RESOURCE),
    ]);
    token(
        state,
        body,
        SidecarErrorCode::SessionExpired,
        "refresh",
        client_id,
        None,
    )
    .await
}

/// Revokes a token (RFC 7009). Best effort: a failure is logged by status and never returned.
pub async fn revoke(state: &AppState, client_id: &str, token: &str, token_type_hint: &str) {
    let body = form(&[
        ("token", token),
        ("token_type_hint", token_type_hint),
        ("client_id", client_id),
    ]);
    let url = endpoint(state, "/mcp/oauth/revoke");
    match send(state, &url, "application/x-www-form-urlencoded", body).await {
        Ok(response) if response.status != 200 => tracing::info!(
            "Louve Invest did not confirm the token revocation (status={})",
            response.status
        ),
        Ok(_) => {}
        Err(_) => tracing::info!("Could not revoke the Louve Invest token (type=SyncException)"),
    }
}

async fn token(
    state: &AppState,
    body: String,
    refused: SidecarErrorCode,
    call: &str,
    client_id: &str,
    redirect_uri: Option<&str>,
) -> LouveResult<Tokens> {
    let url = endpoint(state, "/mcp/oauth/token");
    let response = send(state, &url, "application/x-www-form-urlencoded", body).await?;
    let status = response.status;
    if status == 429 {
        return Err(Failure::rate_limited());
    }
    if status >= 500 {
        return Err(Failure::unavailable());
    }
    if status != 200 {
        let error = oauth_error(&response.body);
        tracing::warn!("Louve Invest refused the token {call} (status={status}; error={error})");
        if error == "invalid_client" || error == "unauthorized_client" {
            return Err(Failure::Sync {
                code: refused,
                message: "Louve Invest no longer recognises Picsou. Connect again.".to_owned(),
                kind: Kind::InvalidClient {
                    client_id: Some(client_id.to_owned()),
                    redirect_uri: redirect_uri.map(str::to_owned),
                },
            });
        }
        if error == "invalid_grant" || status == 401 {
            let message = if refused == SidecarErrorCode::SessionExpired {
                "The Louve Invest access was revoked or expired. Connect again."
            } else {
                "Louve Invest refused this connection attempt. Start again."
            };
            return Err(Failure::sync(refused, message));
        }
        return Err(Failure::format_changed(
            "Louve Invest refused the token request",
        ));
    }
    let body = parse(&response.body)?;
    let access_token = text(&body, "access_token");
    let token_type = text(&body, "token_type");
    let (Some(access_token), Some(token_type)) = (access_token, token_type) else {
        return Err(no_access_token());
    };
    if !token_type.eq_ignore_ascii_case("bearer") {
        return Err(no_access_token());
    }
    let expires_in = body
        .get("expires_in")
        .and_then(whole_number)
        .filter(|seconds| *seconds > 0)
        .unwrap_or(DEFAULT_EXPIRES_IN);
    if let Some(scope) = text(&body, "scope")
        && scope != SCOPE
    {
        // Scope names are not secrets; a wider grant than asked for is worth knowing about.
        tracing::warn!("Louve Invest granted a different scope than requested (scope={scope})");
    }
    Ok(Tokens {
        access_token,
        refresh_token: text(&body, "refresh_token"),
        expires_in_seconds: expires_in,
    })
}

fn no_access_token() -> Failure {
    Failure::format_changed("Louve Invest returned no usable access token")
}

/// JsonNode.canConvertToLong + asLong: a JSON number within the long range, fraction dropped.
fn whole_number(value: &Value) -> Option<i64> {
    let number = value.as_number()?;
    number.as_i64().or_else(|| {
        let text = number.to_string();
        let decimal = text
            .parse::<rust_decimal::Decimal>()
            .ok()
            .or_else(|| rust_decimal::Decimal::from_scientific(&text).ok())?;
        i64::try_from(decimal.trunc()).ok()
    })
}

fn parse(body: &str) -> LouveResult<serde_json::Map<String, Value>> {
    match serde_json::from_str::<Value>(body) {
        Ok(Value::Object(map)) => Ok(map),
        _ => Err(Failure::format_changed(
            "Louve Invest returned an unreadable answer",
        )),
    }
}

/// The OAuth error name of an error body, or "unknown". Never the description.
fn oauth_error(body: &str) -> String {
    serde_json::from_str::<Value>(body)
        .ok()
        .and_then(|v| v.get("error").and_then(Value::as_str).map(str::to_owned))
        .filter(|name| !name.trim().is_empty() && OAUTH_ERROR.is_match(name))
        .unwrap_or_else(|| "unknown".to_owned())
}

fn text(node: &serde_json::Map<String, Value>, field: &str) -> Option<String> {
    node.get(field)
        .and_then(Value::as_str)
        .filter(|v| !v.trim().is_empty())
        .map(str::to_owned)
}
