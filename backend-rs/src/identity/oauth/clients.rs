//! The oauth2_registered_client rows: the seeded picsou-ios client, RFC 7591 dynamic registration
//! for MCP clients (DynamicClientRegistrationController) and the consent screen's client lookup
//! (OAuthConsentController).

use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use chrono::NaiveDateTime;
use serde::{Deserialize, Serialize};
use serde_with::skip_serializing_none;
use uuid::Uuid;

use super::{MCP_CLIENT_SETTING, jackson, to_local};
use crate::AppState;
use crate::error::{AppError, AppResult, Problem};
use crate::http::client_ip::ClientIp;
use crate::http::json::read_json;
use crate::http::rate_limit::policies;
use crate::identity::scopes;

const CLIENT_NAME_MAX_LENGTH: usize = 200;
const DELIMITED_COLUMN_MAX_LENGTH: usize = 1000;

#[derive(Debug, Clone)]
pub struct RegisteredClient {
    pub id: String,
    pub client_id: String,
    pub client_name: String,
    pub authentication_methods: Vec<String>,
    pub grant_types: Vec<String>,
    pub redirect_uris: Vec<String>,
    pub scopes: Vec<String>,
    pub require_proof_key: bool,
    pub require_consent: bool,
    pub mcp: bool,
    pub access_token_ttl: i64,
}

#[derive(sqlx::FromRow)]
struct ClientRow {
    id: String,
    client_id: String,
    client_name: String,
    client_authentication_methods: String,
    authorization_grant_types: String,
    redirect_uris: Option<String>,
    scopes: String,
    client_settings: String,
    token_settings: String,
}

fn split(csv: &str) -> Vec<String> {
    csv.split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
        .collect()
}

impl From<ClientRow> for RegisteredClient {
    fn from(r: ClientRow) -> Self {
        Self {
            require_proof_key: jackson::setting_bool(
                &r.client_settings,
                "settings.client.require-proof-key",
            ),
            require_consent: jackson::setting_bool(
                &r.client_settings,
                "settings.client.require-authorization-consent",
            ),
            mcp: jackson::setting_bool(&r.client_settings, MCP_CLIENT_SETTING),
            access_token_ttl: jackson::setting_seconds(
                &r.token_settings,
                "settings.token.access-token-time-to-live",
            )
            .unwrap_or(300),
            id: r.id,
            client_id: r.client_id,
            client_name: r.client_name,
            authentication_methods: split(&r.client_authentication_methods),
            grant_types: split(&r.authorization_grant_types),
            redirect_uris: split(r.redirect_uris.as_deref().unwrap_or_default()),
            scopes: split(&r.scopes),
        }
    }
}

const COLUMNS: &str = "id, client_id, client_name, client_authentication_methods, authorization_grant_types, \
    redirect_uris, scopes, client_settings, token_settings";

pub async fn find_by_client_id(
    state: &AppState,
    client_id: &str,
) -> Result<Option<RegisteredClient>, sqlx::Error> {
    let row: Option<ClientRow> = sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM oauth2_registered_client WHERE client_id = $1"
    ))
    .bind(client_id)
    .fetch_optional(&state.db)
    .await?;
    Ok(row.map(Into::into))
}

pub async fn find_by_id(
    state: &AppState,
    id: &str,
) -> Result<Option<RegisteredClient>, sqlx::Error> {
    let row: Option<ClientRow> = sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM oauth2_registered_client WHERE id = $1"
    ))
    .bind(id)
    .fetch_optional(&state.db)
    .await?;
    Ok(row.map(Into::into))
}

struct NewClient<'a> {
    client_id: &'a str,
    issued_at: NaiveDateTime,
    client_name: &'a str,
    redirect_uris: &'a [String],
    scopes: &'a [String],
    require_consent: bool,
    mcp: bool,
}

async fn insert(state: &AppState, client: NewClient<'_>) -> Result<String, sqlx::Error> {
    let id = Uuid::new_v4().to_string();
    let oauth = &state.config.oauth;
    sqlx::query(
        "INSERT INTO oauth2_registered_client (id, client_id, client_id_issued_at, client_secret, \
         client_secret_expires_at, client_name, client_authentication_methods, authorization_grant_types, \
         redirect_uris, post_logout_redirect_uris, scopes, client_settings, token_settings) \
         VALUES ($1, $2, $3, NULL, NULL, $4, 'none', 'refresh_token,authorization_code', $5, '', $6, $7, $8)",
    )
    .bind(&id)
    .bind(client.client_id)
    .bind(client.issued_at)
    .bind(client.client_name)
    .bind(client.redirect_uris.join(","))
    .bind(client.scopes.join(","))
    .bind(jackson::client_settings(client.require_consent, client.mcp))
    .bind(jackson::token_settings(
        oauth.access_token_ttl.as_secs(),
        oauth.refresh_token_ttl.as_secs(),
    ))
    .execute(&state.db)
    .await?;
    Ok(id)
}

/// seedIosClientRunner: the first-party public client, inserted once, never reset.
pub async fn seed_ios_client(state: &AppState) -> Result<(), sqlx::Error> {
    let client_id = state.config.oauth.client_id.clone();
    if find_by_client_id(state, &client_id).await?.is_some() {
        return Ok(());
    }
    let id = Uuid::new_v4().to_string();
    let issued_at = to_local(state, state.clock.now());
    insert(
        state,
        NewClient {
            client_id: &client_id,
            issued_at,
            client_name: &id,
            redirect_uris: std::slice::from_ref(&state.config.oauth.redirect_uri),
            scopes: &["read".to_owned(), "write".to_owned()],
            require_consent: false,
            mcp: false,
        },
    )
    .await?;
    // Spring's builder names an unnamed client after its id.
    sqlx::query("UPDATE oauth2_registered_client SET client_name = id WHERE client_id = $1")
        .bind(&client_id)
        .execute(&state.db)
        .await?;
    Ok(())
}

#[derive(Debug, Deserialize)]
pub struct RegistrationRequest {
    client_name: Option<String>,
    redirect_uris: Option<Vec<String>>,
    scope: Option<String>,
}

#[derive(Serialize)]
struct RegistrationResponse {
    client_id: String,
    client_id_issued_at: i64,
    redirect_uris: Vec<String>,
    token_endpoint_auth_method: &'static str,
    grant_types: [&'static str; 2],
    scope: String,
}

/// RFC 8252: https, or loopback http on 127.0.0.1 or localhost; never a custom scheme.
fn validate_redirect_uris(uris: Option<Vec<String>>) -> AppResult<Vec<String>> {
    let uris = uris.unwrap_or_default();
    if uris.is_empty() {
        return Err(AppError::BadRequest(
            "redirect_uris must contain at least one URI".into(),
        ));
    }
    for uri in &uris {
        let parsed = url::Url::parse(uri)
            .ok()
            .filter(|u| u.fragment().is_none() && !uri.trim().is_empty());
        let Some(parsed) = parsed else {
            return Err(AppError::BadRequest(format!(
                "Malformed redirect_uris entry: {uri}"
            )));
        };
        let allowed = match parsed.scheme() {
            "https" => true,
            "http" => parsed
                .host_str()
                .is_some_and(|h| h == "127.0.0.1" || h.eq_ignore_ascii_case("localhost")),
            _ => false,
        };
        if !allowed {
            return Err(AppError::BadRequest(format!(
                "redirect_uris entry must be https:// or a loopback http://127.0.0.1 / http://localhost URI: {uri}"
            )));
        }
    }
    Ok(uris)
}

fn resolve_scopes(requested: Option<&str>) -> AppResult<Vec<String>> {
    let Some(raw) = requested.filter(|s| !s.trim().is_empty()) else {
        return Ok(scopes::default_read()
            .into_iter()
            .map(str::to_owned)
            .collect());
    };
    let mut out: Vec<String> = Vec::new();
    for scope in raw.split_whitespace() {
        if !out.iter().any(|s| s == scope) {
            out.push(scope.to_owned());
        }
    }
    if let Some(unknown) = out.iter().find(|s| !scopes::is_known(s)) {
        return Err(AppError::BadRequest(format!("Unknown scope: {unknown}")));
    }
    Ok(out)
}

pub async fn register(
    State(state): State<AppState>,
    ip: ClientIp,
    request: axum::extract::Request,
) -> AppResult<Response> {
    if !state
        .limits
        .try_consume(&policies::OAUTH_REGISTER, &ip.as_java_string())
    {
        return Ok(Problem::with_detail(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many client registrations. Try again later.",
        )
        .into_response());
    }
    let body: RegistrationRequest = read_json(request, &()).await?;
    let redirect_uris = validate_redirect_uris(body.redirect_uris)?;
    let scopes = resolve_scopes(body.scope.as_deref())?;
    let client_name = body
        .client_name
        .filter(|n| !n.trim().is_empty())
        .unwrap_or_else(|| "Remote MCP client".to_owned());
    if client_name.encode_utf16().count() > CLIENT_NAME_MAX_LENGTH {
        return Err(AppError::BadRequest(format!(
            "client_name exceeds the maximum length of {CLIENT_NAME_MAX_LENGTH}"
        )));
    }
    if redirect_uris.join(",").encode_utf16().count() > DELIMITED_COLUMN_MAX_LENGTH {
        return Err(AppError::BadRequest(format!(
            "redirect_uris exceeds the maximum combined length of {DELIMITED_COLUMN_MAX_LENGTH}"
        )));
    }
    if scopes.join(",").encode_utf16().count() > DELIMITED_COLUMN_MAX_LENGTH {
        return Err(AppError::BadRequest(format!(
            "scope exceeds the maximum combined length of {DELIMITED_COLUMN_MAX_LENGTH}"
        )));
    }
    let client_id = Uuid::new_v4().to_string();
    let issued_at = state.clock.now();
    insert(
        &state,
        NewClient {
            client_id: &client_id,
            issued_at: to_local(&state, issued_at),
            client_name: &client_name,
            redirect_uris: &redirect_uris,
            scopes: &scopes,
            require_consent: true,
            mcp: true,
        },
    )
    .await?;
    Ok((
        StatusCode::CREATED,
        axum::Json(RegistrationResponse {
            client_id,
            client_id_issued_at: issued_at.timestamp(),
            redirect_uris,
            token_endpoint_auth_method: "none",
            grant_types: ["authorization_code", "refresh_token"],
            scope: scopes.join(" "),
        }),
    )
        .into_response())
}

#[derive(Debug, Deserialize)]
pub struct ConsentInfoQuery {
    client_id: Option<String>,
    scope: Option<String>,
    state: Option<String>,
}

#[skip_serializing_none]
#[derive(Serialize)]
pub struct ConsentInfoResponse {
    client_name: String,
    requested_scopes: Vec<String>,
    state: Option<String>,
}

/// The consent screen's pending request: the client's name and the requested scopes it was
/// registered for (the scope parameter is browser state, not trusted).
pub async fn consent_info(
    State(state): State<AppState>,
    Query(query): Query<ConsentInfoQuery>,
) -> AppResult<axum::Json<ConsentInfoResponse>> {
    let Some(client_id) = query.client_id else {
        return Err(AppError::BadRequest(
            "Required parameter 'client_id' is not present.".into(),
        ));
    };
    let client = find_by_client_id(&state, &client_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Unknown OAuth client".into()))?;
    let mut requested: Vec<String> = Vec::new();
    for scope in query
        .scope
        .as_deref()
        .unwrap_or_default()
        .split_whitespace()
    {
        if client.scopes.iter().any(|s| s == scope) && !requested.iter().any(|s| s == scope) {
            requested.push(scope.to_owned());
        }
    }
    Ok(axum::Json(ConsentInfoResponse {
        client_name: client.client_name,
        requested_scopes: requested,
        state: query.state,
    }))
}
