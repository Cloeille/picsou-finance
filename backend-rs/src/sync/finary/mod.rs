//! Finary API sync: FinaryApiSyncController (Clerk sign-in with an optional TOTP second factor,
//! two-phase preview and execute, auto-sync). The XLSX import is FinaryImportController's.

mod api;
mod client;
mod holdings;
mod matcher;
mod persistence;
mod service;
mod session;
mod upstream;

use std::sync::Arc;

use axum::extract::{FromRequest, RawQuery, Request, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};

use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::read_json;
pub use crate::ledger::instrument::TickerResult;
use crate::ledger::sync_values::java_trim;
use crate::routes::Routes;
pub use api::{AutoSyncStatus, FinaryAutoSyncResponse};
use api::{
    CheckTotpResponse, ConnectionStatusResponse, ExecuteRequest, ImportResultResponse,
    LoginRequest, PreviewResponse,
};

/// OpenFigiIsinConverter.resolve, owned by the market track: the ticker and name an ISIN
/// resolves to.
#[async_trait::async_trait]
pub trait IsinLookup: Send + Sync {
    async fn resolve(&self, isin: &str) -> TickerResult;
}

/// What resolve returns when OpenFIGI and Yahoo answer nothing: the normalized ISIN, no name.
pub struct UnresolvedIsin;

#[async_trait::async_trait]
impl IsinLookup for UnresolvedIsin {
    async fn resolve(&self, isin: &str) -> TickerResult {
        TickerResult {
            ticker: java_trim(isin).to_uppercase(),
            name: None,
        }
    }
}

/// The Finary sync's state in AppState: the preview cache and the ISIN seam.
pub struct Hooks {
    pub isin: Arc<dyn IsinLookup>,
    previews: service::PreviewCache,
}

impl Hooks {
    pub fn new() -> Self {
        Self {
            isin: Arc::new(UnresolvedIsin),
            previews: service::PreviewCache::default(),
        }
    }

    /// Evicts expired preview sessions (FinaryApiSyncService.cleanupExpiredCache).
    pub fn purge_expired(&self, now: chrono::DateTime<chrono::Utc>) {
        self.previews.purge_expired(now);
    }
}

impl Default for Hooks {
    fn default() -> Self {
        Self::new()
    }
}

/// The auto-sync MemberSyncService and the scheduler call: preview then execute when every
/// Finary account is already mapped.
pub async fn auto_sync(state: &AppState, member_id: i64) -> AppResult<FinaryAutoSyncResponse> {
    service::auto_sync(state, member_id).await
}

/// A @RequestBody as Spring MVC reads it, including the cases the shared JSON extractor answers
/// differently: an empty body without a Content-Type is the 400 of a missing body, a body without
/// one is application/octet-stream, and the 415 names the type with the UTF-8 charset Tomcat adds.
struct Body<T>(T);

impl<T: serde::de::DeserializeOwned, S: Send + Sync> FromRequest<S> for Body<T> {
    type Rejection = AppError;

    async fn from_request(request: Request, state: &S) -> Result<Self, AppError> {
        let unreadable = || AppError::BadRequest("Failed to read request".to_owned());
        let content_type = request
            .headers()
            .get(axum::http::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned);
        let (parts, body) = request.into_parts();
        // axum's default body limit, which read_json applies below: reading without it would
        // buffer a body of any size before refusing it.
        let bytes = axum::body::to_bytes(body, 2 * 1024 * 1024)
            .await
            .map_err(|_| unreadable())?;
        let json = content_type.as_deref().is_some_and(|ct| {
            let essence = ct
                .split(';')
                .next()
                .unwrap_or_default()
                .trim()
                .to_ascii_lowercase();
            essence == "application/json"
                || (essence.starts_with("application/") && essence.ends_with("+json"))
        });
        if !json {
            let Some(ct) = content_type else {
                if bytes.is_empty() {
                    return Err(unreadable());
                }
                return Err(unsupported("application/octet-stream"));
            };
            let named = if ct.to_ascii_lowercase().contains("charset") {
                ct
            } else {
                format!("{ct};charset=UTF-8")
            };
            return Err(unsupported(&named));
        }
        let request = Request::from_parts(parts, axum::body::Body::from(bytes));
        Ok(Self(read_json(request, state).await?))
    }
}

fn unsupported(content_type: &str) -> AppError {
    AppError::status(
        StatusCode::UNSUPPORTED_MEDIA_TYPE,
        format!("Content-Type '{content_type}' is not supported."),
    )
}

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/finary/status", status)
        .post("/api/finary/login", login)
        .post("/api/finary/check-totp", check_totp)
        .delete("/api/finary/session", delete_session)
        .post("/api/finary/api-sync/preview", preview)
        .post("/api/finary/api-sync/execute", execute)
        .post("/api/finary/api-sync/auto", auto);
}

async fn status(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<ConnectionStatusResponse>> {
    Ok(axum::Json(service::status(&state, auth.member_id).await?))
}

async fn login(
    State(state): State<AppState>,
    auth: Auth,
    Body(request): Body<LoginRequest>,
) -> AppResult<Response> {
    service::login(
        &state,
        auth.member_id,
        request.email.as_deref(),
        request.password.as_deref(),
    )
    .await?;
    Ok(StatusCode::OK.into_response())
}

async fn check_totp(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<CheckTotpResponse>> {
    Ok(axum::Json(
        service::check_totp(&state, auth.member_id).await?,
    ))
}

async fn delete_session(State(state): State<AppState>, auth: Auth) -> AppResult<Response> {
    service::delete_session(&state, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT.into_response())
}

/// @RequestParam String totp: repeated values joined with commas, as Spring binds them.
fn totp_param(query: Option<&str>) -> Option<String> {
    let values: Vec<String> = url::form_urlencoded::parse(query?.as_bytes())
        .filter(|(k, _)| k == "totp")
        .map(|(_, v)| v.into_owned())
        .collect();
    (!values.is_empty()).then(|| values.join(","))
}

async fn preview(
    State(state): State<AppState>,
    auth: Auth,
    RawQuery(query): RawQuery,
) -> AppResult<axum::Json<PreviewResponse>> {
    let totp = totp_param(query.as_deref());
    Ok(axum::Json(
        service::preview(&state, auth.member_id, totp.as_deref()).await?,
    ))
}

async fn execute(
    State(state): State<AppState>,
    auth: Auth,
    Body(request): Body<ExecuteRequest>,
) -> AppResult<axum::Json<ImportResultResponse>> {
    Ok(axum::Json(
        service::execute(
            &state,
            auth.member_id,
            request.sync_token.as_deref(),
            request.mappings.as_deref(),
        )
        .await?,
    ))
}

async fn auto(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<FinaryAutoSyncResponse>> {
    Ok(axum::Json(
        service::auto_sync(&state, auth.member_id).await?,
    ))
}
