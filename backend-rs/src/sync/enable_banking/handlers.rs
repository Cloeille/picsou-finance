//! SyncController: /api/sync.

use axum::extract::{Query, State};
use axum::response::{IntoResponse, Response};
use http::StatusCode;
use serde::Deserialize;

use super::connector::DEFAULT_COUNTRY;
use super::service;
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult, Problem};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Path, Validate};
use crate::http::rate_limit::policies;

/// Each endpoint has its own bucket (ip + endpoint), so a passive read like the country picker
/// cannot exhaust the budget of an explicit action like initiating a connection.
fn rate_limited(state: &AppState, ip: &ClientIp, endpoint: &str) -> Option<Response> {
    let key = format!("{}:{endpoint}", ip.as_java_string());
    (!state.limits.try_consume(&policies::SYNC, &key)).then(|| {
        Problem::with_detail(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many sync requests. Please wait a moment.",
        )
        .into_response()
    })
}

#[derive(Debug, Deserialize)]
pub struct InstitutionsQuery {
    query: Option<String>,
    country: Option<String>,
}

/// @RequestParam defaultValue also replaces an empty value.
fn or_default(value: Option<String>, default: &str) -> String {
    value
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| default.to_owned())
}

pub async fn institutions(
    State(state): State<AppState>,
    _auth: Auth,
    Query(params): Query<InstitutionsQuery>,
) -> AppResult<Response> {
    let query = or_default(params.query, "");
    let country = or_default(params.country, DEFAULT_COUNTRY);
    let found = service::search_institutions(&state, &query, &country).await?;
    Ok(axum::Json(found).into_response())
}

pub async fn countries(
    State(state): State<AppState>,
    _auth: Auth,
    ip: ClientIp,
) -> AppResult<Response> {
    if let Some(limited) = rate_limited(&state, &ip, "countries") {
        return Ok(limited);
    }
    Ok(axum::Json(service::list_countries(&state).await?).into_response())
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InitiateRequest {
    institution_id: Option<String>,
    institution_name: Option<String>,
}

impl Validate for InitiateRequest {
    fn validate(&self, errors: &mut Errors) {
        errors
            .not_blank("institutionId", self.institution_id.as_deref())
            .not_blank("institutionName", self.institution_name.as_deref());
    }
}

pub async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<InitiateRequest>,
) -> AppResult<Response> {
    if let Some(limited) = rate_limited(&state, &ip, "initiate") {
        return Ok(limited);
    }
    let response = service::initiate_connection(
        &state,
        req.institution_id.as_deref().unwrap_or_default(),
        req.institution_name.as_deref().unwrap_or_default(),
        auth.member_id,
    )
    .await?;
    Ok(axum::Json(response).into_response())
}

#[derive(Debug, Deserialize)]
pub struct CompleteRequest {
    code: Option<String>,
    state: Option<String>,
}

impl Validate for CompleteRequest {
    fn validate(&self, errors: &mut Errors) {
        errors.not_blank("code", self.code.as_deref());
    }
}

/// The bank redirects the browser to the SPA, which posts the code here: a POST keeps this
/// state-changing call under the cross-site request check.
pub async fn complete(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<CompleteRequest>,
) -> AppResult<Response> {
    if let Some(limited) = rate_limited(&state, &ip, "complete") {
        return Ok(limited);
    }
    let accounts = service::complete_connection(
        &state,
        req.code.as_deref().unwrap_or_default(),
        req.state.as_deref(),
        auth.member_id,
    )
    .await?;
    Ok(axum::Json(accounts).into_response())
}

pub async fn status(State(state): State<AppState>, auth: Auth) -> AppResult<Response> {
    let all = service::all_requisitions(&state, auth.member_id).await?;
    Ok(axum::Json(all).into_response())
}

pub async fn retry(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<Response> {
    let accounts = service::retry_sync(&state, id, auth.member_id).await?;
    Ok(axum::Json(accounts).into_response())
}

pub async fn reconnect(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Path(id): Path<i64>,
) -> AppResult<Response> {
    if let Some(limited) = rate_limited(&state, &ip, "reconnect") {
        return Ok(limited);
    }
    let response = service::reconnect(&state, id, auth.member_id)
        .await
        .map_err(AppError::from)?;
    Ok(axum::Json(response).into_response())
}

pub async fn delete(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<Response> {
    service::delete_requisition(&state, id, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT.into_response())
}
