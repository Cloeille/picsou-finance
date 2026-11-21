//! The Jackson documents Spring Authorization Server keeps in its JDBC columns (typed with
//! @class markers and [class, value] pairs), written so the Java backend reads Rust rows and
//! parsed leniently from Java rows.

use chrono::{DateTime, Utc};
use serde_json::{Map, Value, json};

use super::encode_query_value;

const UNMODIFIABLE_MAP: &str = "java.util.Collections$UnmodifiableMap";
const REQUEST_CLASS: &str = "org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest";
const PRINCIPAL_KEY: &str = "java.security.Principal";

/// The AppUser captured at authorization time; refreshed tokens would carry its token version.
#[derive(Debug, Clone)]
pub struct PrincipalSnapshot {
    pub id: i64,
    pub username: String,
    pub role: String,
    pub token_version: i64,
}

/// OAuth2AuthorizationRequest, as the authorization endpoint received it.
#[derive(Debug, Clone)]
pub struct AuthorizationRequest {
    pub authorization_uri: String,
    pub client_id: String,
    /// None when the request had no redirect_uri, Some("") when it was empty.
    pub redirect_uri: Option<String>,
    pub scopes: Vec<String>,
    pub state: Option<String>,
    pub code_challenge: Option<String>,
    pub code_challenge_method: Option<String>,
}

impl AuthorizationRequest {
    fn request_uri(&self) -> String {
        let mut parts = vec![
            "response_type=code".to_owned(),
            format!("client_id={}", encode_query_value(&self.client_id)),
        ];
        if !self.scopes.is_empty() {
            parts.push(format!("scope={}", encode_query_value(&self.scopes.join(" "))));
        }
        if let Some(state) = &self.state {
            parts.push(format!("state={}", encode_query_value(state)));
        }
        if let Some(redirect) = &self.redirect_uri {
            parts.push(format!("redirect_uri={}", encode_query_value(redirect)));
        }
        if let Some(c) = &self.code_challenge {
            parts.push(format!("code_challenge={}", encode_query_value(c)));
        }
        if let Some(m) = &self.code_challenge_method {
            parts.push(format!("code_challenge_method={}", encode_query_value(m)));
        }
        format!("{}?{}", self.authorization_uri, parts.join("&"))
    }

    fn to_json(&self) -> Value {
        let mut additional = Map::new();
        additional.insert("@class".into(), UNMODIFIABLE_MAP.into());
        if let Some(c) = &self.code_challenge {
            additional.insert("code_challenge".into(), c.clone().into());
        }
        if let Some(m) = &self.code_challenge_method {
            additional.insert("code_challenge_method".into(), m.clone().into());
        }
        json!({
            "@class": REQUEST_CLASS,
            "authorizationUri": self.authorization_uri,
            "authorizationGrantType": {"value": "authorization_code"},
            "responseType": {"value": "code"},
            "clientId": self.client_id,
            "redirectUri": self.redirect_uri,
            "scopes": ["java.util.Collections$UnmodifiableSet", self.scopes],
            "state": self.state,
            "additionalParameters": additional,
            "authorizationRequestUri": self.request_uri(),
            "attributes": {"@class": UNMODIFIABLE_MAP},
        })
    }
}

/// The attributes column: the authenticated principal, the authorization request and, while
/// consent is pending, the consent state.
pub fn attributes(principal: &PrincipalSnapshot, request: &AuthorizationRequest, state: Option<&str>) -> String {
    let mut map = Map::new();
    map.insert("@class".into(), UNMODIFIABLE_MAP.into());
    map.insert(
        PRINCIPAL_KEY.into(),
        json!({
            "@class": "org.springframework.security.authentication.UsernamePasswordAuthenticationToken",
            "authorities": ["java.util.Collections$UnmodifiableRandomAccessList", [{
                "@class": "org.springframework.security.core.authority.SimpleGrantedAuthority",
                "authority": format!("ROLE_{}", principal.role),
            }]],
            "details": null,
            "authenticated": true,
            "principal": {
                "@class": "com.picsou.model.AppUser",
                "id": principal.id,
                "username": principal.username,
                "role": principal.role,
                "tokenVersion": principal.token_version,
            },
            "credentials": null,
        }),
    );
    map.insert(REQUEST_CLASS.into(), request.to_json());
    if let Some(state) = state {
        map.insert("state".into(), state.into());
    }
    Value::Object(map).to_string()
}

/// A Jackson typed collection: [class, [items]].
fn typed_items(value: Option<&Value>) -> Vec<String> {
    match value {
        Some(Value::Array(pair)) => pair
            .get(1)
            .and_then(Value::as_array)
            .map(|items| items.iter().filter_map(Value::as_str).map(str::to_owned).collect())
            .unwrap_or_default(),
        _ => Vec::new(),
    }
}

/// A Jackson typed scalar: [class, value] or the bare value.
fn typed_i64(value: Option<&Value>) -> Option<i64> {
    match value? {
        Value::Array(pair) => pair.get(1).and_then(Value::as_i64),
        other => other.as_i64(),
    }
}

pub fn parse_attributes(raw: &str) -> Option<(PrincipalSnapshot, AuthorizationRequest)> {
    let root: Value = serde_json::from_str(raw).ok()?;
    let principal = root.get(PRINCIPAL_KEY)?.get("principal")?;
    let snapshot = PrincipalSnapshot {
        id: typed_i64(principal.get("id"))?,
        username: principal.get("username")?.as_str()?.to_owned(),
        role: principal.get("role").and_then(Value::as_str).unwrap_or("MEMBER").to_owned(),
        token_version: typed_i64(principal.get("tokenVersion")).unwrap_or(0),
    };
    let request = root.get(REQUEST_CLASS)?;
    let text = |name: &str| request.get(name).and_then(Value::as_str).map(str::to_owned);
    let additional = request.get("additionalParameters");
    let extra = |name: &str| {
        additional
            .and_then(|a| a.get(name))
            .and_then(Value::as_str)
            .map(str::to_owned)
    };
    Some((
        snapshot,
        AuthorizationRequest {
            authorization_uri: text("authorizationUri").unwrap_or_default(),
            client_id: text("clientId").unwrap_or_default(),
            redirect_uri: text("redirectUri"),
            scopes: typed_items(request.get("scopes")),
            state: text("state"),
            code_challenge: extra("code_challenge"),
            code_challenge_method: extra("code_challenge_method"),
        },
    ))
}

pub fn token_metadata_invalidated(raw: Option<&str>) -> bool {
    raw.and_then(|r| serde_json::from_str::<Value>(r).ok())
        .and_then(|v| v.get("metadata.token.invalidated").and_then(Value::as_bool))
        .unwrap_or(false)
}

pub fn code_metadata(invalidated: bool) -> String {
    json!({"@class": UNMODIFIABLE_MAP, "metadata.token.invalidated": invalidated}).to_string()
}

fn instant(at: DateTime<Utc>) -> Value {
    let text = format!("{}.{:09}", at.timestamp(), at.timestamp_subsec_nanos());
    let number: serde_json::Number = text.parse().unwrap_or_else(|_| at.timestamp().into());
    json!(["java.time.Instant", number])
}

/// The access_token_metadata column: the token's claims, typed as Jackson writes them.
pub fn access_metadata(claims: &Map<String, Value>, invalidated: bool) -> String {
    let mut typed = Map::new();
    typed.insert("@class".into(), UNMODIFIABLE_MAP.into());
    for (name, value) in claims {
        let converted = match name.as_str() {
            "uid" | "tv" => json!(["java.lang.Long", value]),
            "nbf" | "exp" | "iat" => value
                .as_i64()
                .and_then(|s| DateTime::from_timestamp(s, 0))
                .map_or(Value::Null, instant),
            "iss" => json!(["java.net.URL", value]),
            "aud" => match value {
                Value::String(a) if claims.get("type").and_then(Value::as_str) == Some("mcp") => {
                    json!(["java.util.ArrayList", [a]])
                }
                Value::String(a) => json!(["java.util.Collections$SingletonList", [a]]),
                other => other.clone(),
            },
            "scope" => match value {
                Value::Array(items) => json!(["java.util.Collections$UnmodifiableSet", items]),
                other => other.clone(),
            },
            _ => value.clone(),
        };
        typed.insert(name.clone(), converted);
    }
    json!({
        "@class": UNMODIFIABLE_MAP,
        "org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat": "self-contained",
        "metadata.token.claims": typed,
        "metadata.token.invalidated": invalidated,
    })
    .to_string()
}

/// The token_metadata of an access token after the code was replayed.
pub fn invalidate(raw: &str) -> String {
    match serde_json::from_str::<Value>(raw) {
        Ok(Value::Object(mut map)) => {
            map.insert("metadata.token.invalidated".into(), true.into());
            Value::Object(map).to_string()
        }
        _ => code_metadata(true),
    }
}

pub fn client_settings(require_consent: bool, mcp: bool) -> String {
    let mut map = Map::new();
    map.insert("@class".into(), UNMODIFIABLE_MAP.into());
    map.insert("settings.client.require-proof-key".into(), true.into());
    map.insert(
        "settings.client.require-authorization-consent".into(),
        require_consent.into(),
    );
    if mcp {
        map.insert(super::MCP_CLIENT_SETTING.into(), true.into());
    }
    Value::Object(map).to_string()
}

fn duration(seconds: u64) -> Value {
    let number: serde_json::Number = format!("{seconds}.000000000")
        .parse()
        .unwrap_or_else(|_| seconds.into());
    json!(["java.time.Duration", number])
}

pub fn token_settings(access_ttl: u64, refresh_ttl: u64) -> String {
    json!({
        "@class": UNMODIFIABLE_MAP,
        "settings.token.reuse-refresh-tokens": false,
        "settings.token.x509-certificate-bound-access-tokens": false,
        "settings.token.id-token-signature-algorithm": ["org.springframework.security.oauth2.jose.jws.SignatureAlgorithm", "RS256"],
        "settings.token.access-token-time-to-live": duration(access_ttl),
        "settings.token.access-token-format": {
            "@class": "org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat",
            "value": "self-contained",
        },
        "settings.token.refresh-token-time-to-live": duration(refresh_ttl),
        "settings.token.authorization-code-time-to-live": duration(300),
        "settings.token.device-code-time-to-live": duration(300),
    })
    .to_string()
}

pub fn setting_bool(raw: &str, key: &str) -> bool {
    serde_json::from_str::<Value>(raw)
        .ok()
        .and_then(|v| v.get(key).and_then(Value::as_bool))
        .unwrap_or(false)
}

/// A [java.time.Duration, seconds] setting, whole seconds.
pub fn setting_seconds(raw: &str, key: &str) -> Option<i64> {
    let root: Value = serde_json::from_str(raw).ok()?;
    let value = match root.get(key)? {
        Value::Array(pair) => pair.get(1)?.clone(),
        other => other.clone(),
    };
    let text = match value {
        Value::Number(n) => n.to_string(),
        _ => return None,
    };
    text.split('.').next()?.parse().ok()
}
