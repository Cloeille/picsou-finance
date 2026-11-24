//! SessionController: the user's remember-me sessions.

use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, Utc};
use serde::Serialize;
use serde_with::skip_serializing_none;

use crate::AppState;
use crate::auth::cookies;
use crate::auth::{Auth, persistent};
use crate::error::AppResult;
use crate::http::json::Path;

#[skip_serializing_none]
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SessionResponse {
    id: i64,
    user_agent: Option<String>,
    ip_prefix: Option<String>,
    created_at: DateTime<Utc>,
    last_used_at: DateTime<Utc>,
    expires_at: DateTime<Utc>,
    trusted_for_2fa: bool,
    current: bool,
}

fn current_series(headers: &HeaderMap) -> Option<uuid::Uuid> {
    cookies::read(headers, cookies::PERSISTENT).and_then(|c| persistent::series_from_cookie(&c))
}

pub async fn list(
    State(state): State<AppState>,
    auth: Auth,
    headers: HeaderMap,
) -> AppResult<Response> {
    let current = current_series(&headers);
    let sessions = persistent::list_active_for_user(&state.db, auth.user.id).await?;
    let body: Vec<SessionResponse> = sessions
        .into_iter()
        .map(|s| SessionResponse {
            id: s.id,
            current: current == Some(s.series_id),
            user_agent: s.user_agent,
            ip_prefix: s.ip_prefix,
            created_at: s.created_at,
            last_used_at: s.last_used_at,
            expires_at: s.expires_at,
            trusted_for_2fa: s.trusted_for_2fa,
        })
        .collect();
    Ok(axum::Json(body).into_response())
}

pub async fn revoke(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<Response> {
    // A missing session and another user's session both answer 404.
    if persistent::revoke(&state, id, auth.user.id).await? {
        Ok(StatusCode::NO_CONTENT.into_response())
    } else {
        Ok(StatusCode::NOT_FOUND.into_response())
    }
}

pub async fn revoke_all_except_current(
    State(state): State<AppState>,
    auth: Auth,
    headers: HeaderMap,
) -> AppResult<Response> {
    let current = current_series(&headers);
    let except = persistent::list_active_for_user(&state.db, auth.user.id)
        .await?
        .into_iter()
        .find(|s| current == Some(s.series_id))
        .map(|s| s.id);
    match except {
        Some(id) => persistent::revoke_all_for_user_except(&state, auth.user.id, id).await?,
        None => persistent::revoke_all_for_user(&state, auth.user.id).await?,
    };
    Ok(StatusCode::NO_CONTENT.into_response())
}
