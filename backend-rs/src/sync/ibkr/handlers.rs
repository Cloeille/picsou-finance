//! IbkrController: connect, status, sync and clear under /api/ibkr.

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::Deserialize;

use super::service;
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IbkrConnectRequest {
    token: Option<String>,
    query_id: Option<String>,
}

impl Validate for IbkrConnectRequest {
    fn validate(&self, errors: &mut Errors) {
        errors
            .not_blank("token", self.token.as_deref())
            .not_blank("queryId", self.query_id.as_deref());
    }
}

pub async fn connect(
    State(state): State<AppState>,
    auth: Auth,
    Json(request): Json<IbkrConnectRequest>,
) -> AppResult<Response> {
    service::connect(
        &state,
        request.token.as_deref().unwrap_or_default(),
        request.query_id.as_deref().unwrap_or_default(),
        auth.member_id,
    )
    .await?;
    Ok(StatusCode::NO_CONTENT.into_response())
}

pub async fn status(State(state): State<AppState>, auth: Auth) -> AppResult<Response> {
    let status = service::connection_status(&state, auth.member_id).await?;
    Ok(axum::Json(status).into_response())
}

pub async fn sync(State(state): State<AppState>, auth: Auth, ip: ClientIp) -> AppResult<Response> {
    if !state
        .limits
        .try_consume(&policies::IBKR_SYNC, &ip.as_java_string())
    {
        return Err(AppError::status(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many sync requests. Please wait a moment before trying again.",
        ));
    }
    let accounts = service::sync(&state, auth.member_id).await?;
    Ok(axum::Json(accounts).into_response())
}

pub async fn clear_connection(State(state): State<AppState>, auth: Auth) -> AppResult<Response> {
    service::delete_connection(&state, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT.into_response())
}
