//! OAuth2Tools (2 tools): static OAuth2 authorization server discovery and own session status.

use chrono::{DateTime, Utc};
use serde::Serialize;
use serde_json::{Map, Value};

use crate::AppState;
use crate::auth::{Auth, AuthMethod};
use crate::error::{AppError, AppResult};
use crate::mcp::tools::{ToolSpec, schema_empty, to_json_text};

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct OAuth2Configuration {
    issuer: Option<String>,
    authorization_endpoint: &'static str,
    token_endpoint: &'static str,
    jwk_set_endpoint: &'static str,
    client_id: String,
    pkce_required: bool,
    supported_scopes: Vec<&'static str>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct OAuth2SessionStatus {
    key_id: Option<i64>,
    key_name: Option<String>,
    scopes: Vec<String>,
    created_at: Option<DateTime<Utc>>,
    last_used_at: Option<DateTime<Utc>>,
    expires_at: Option<DateTime<Utc>>,
    mfa_enabled: bool,
    source: &'static str,
}

#[derive(sqlx::FromRow)]
struct KeyStatusRow {
    id: i64,
    name: String,
    scopes: String,
    created_at: DateTime<Utc>,
    last_used_at: Option<DateTime<Utc>>,
    expires_at: Option<DateTime<Utc>>,
}

pub fn specs() -> Vec<ToolSpec> {
    vec![
        ToolSpec {
            name: "get_oauth2_configuration",
            description: "Get the Picsou OAuth2 authorization server's discovery metadata: issuer, authorize/token/JWKS endpoint paths, the registered client id, and whether PKCE is required. Read-only, no secrets. Every key can call this regardless of its other scopes.",
            scope: "oauth2:discover",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_oauth2_session_status",
            description: "Get the status of the session this MCP call is authenticated with (an access-key or an OAuth2 remote-MCP token): its name (access-keys only), granted scopes, creation/last-used/expiry timestamps (access-keys only), whether the owning member has MFA enabled, and a source marker distinguishing the two. A caller can only ever see its own status.",
            scope: "oauth2:session-status",
            input_schema: schema_empty(),
        },
    ]
}

pub async fn call(
    state: &AppState,
    auth: &Auth,
    name: &str,
    _args: &Map<String, Value>,
) -> AppResult<String> {
    match name {
        "get_oauth2_configuration" => {
            let config = OAuth2Configuration {
                issuer: None,
                authorization_endpoint: "/oauth2/authorize",
                token_endpoint: "/oauth2/token",
                jwk_set_endpoint: "/oauth2/jwks",
                client_id: state.config.oauth.client_id.clone(),
                pkce_required: true,
                supported_scopes: vec!["read", "write"],
            };
            to_json_text(&config)
        }
        "get_oauth2_session_status" => {
            let mfa_enabled = crate::setup_auth::mfa::is_enabled(&state.db, auth.user.id).await?;
            match &auth.method {
                AuthMethod::Session => Err(AppError::BadRequest(
                    "MCP tools must run under an access-key session".to_owned(),
                )),
                AuthMethod::OAuthToken { scopes } => {
                    let status = OAuth2SessionStatus {
                        key_id: None,
                        key_name: None,
                        scopes: scopes.clone(),
                        created_at: None,
                        last_used_at: None,
                        expires_at: None,
                        mfa_enabled,
                        source: "oauth2",
                    };
                    to_json_text(&status)
                }
                AuthMethod::AccessKey { key_id, .. } => {
                    let row: KeyStatusRow = sqlx::query_as(
                        "SELECT id, name, scopes, created_at, last_used_at, expires_at \
                         FROM access_key WHERE id = $1 AND member_id = $2",
                    )
                    .bind(key_id)
                    .bind(auth.member_id)
                    .fetch_optional(&state.db)
                    .await?
                    .ok_or_else(|| {
                        AppError::BadRequest(
                            "Access key not found for the current session".to_owned(),
                        )
                    })?;
                    let mut scopes: Vec<String> =
                        row.scopes.split_whitespace().map(str::to_owned).collect();
                    scopes.sort();
                    scopes.dedup();
                    let status = OAuth2SessionStatus {
                        key_id: Some(row.id),
                        key_name: Some(row.name),
                        scopes,
                        created_at: Some(row.created_at),
                        last_used_at: row.last_used_at,
                        expires_at: row.expires_at,
                        mfa_enabled,
                        source: "access_key",
                    };
                    to_json_text(&status)
                }
            }
        }
        other => Err(AppError::BadRequest(format!(
            "Unknown OAuth2 tool: {other}"
        ))),
    }
}
