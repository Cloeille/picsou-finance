//! ConnectedAppsController: the caller's own oauth2_authorization rows that hold an access token.

use axum::extract::State;
use axum::http::StatusCode;
use chrono::{DateTime, NaiveDateTime, Utc};
use serde::Serialize;
use serde_with::skip_serializing_none;

use super::from_local;
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::Path;

#[skip_serializing_none]
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectedApp {
    id: String,
    client_name: String,
    scopes: Vec<String>,
    issued_at: Option<DateTime<Utc>>,
    /// The JDBC schema keeps no last-use time; the field stays for the frontend contract.
    last_used_at: Option<DateTime<Utc>>,
}

#[derive(crate::db::DbFromRow)]
struct Row {
    id: String,
    client_name: String,
    scopes: Option<String>,
    issued_at: Option<NaiveDateTime>,
}

pub async fn list(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Vec<ConnectedApp>>> {
    let rows: Vec<Row> = crate::db::query_as(
        "SELECT a.id, c.client_name, a.authorized_scopes AS scopes, a.access_token_issued_at AS issued_at \
         FROM oauth2_authorization a JOIN oauth2_registered_client c ON c.id = a.registered_client_id \
         WHERE a.principal_name = $1 AND a.access_token_value IS NOT NULL \
         ORDER BY a.access_token_issued_at DESC",
    )
    .bind(&auth.user.username)
    .fetch_all(&state.db)
    .await?;
    Ok(axum::Json(
        rows.into_iter()
            .map(|r| {
                let mut scopes: Vec<String> = Vec::new();
                for s in r
                    .scopes
                    .as_deref()
                    .unwrap_or_default()
                    .split(',')
                    .map(str::trim)
                {
                    if !s.is_empty() && !scopes.iter().any(|x| x == s) {
                        scopes.push(s.to_owned());
                    }
                }
                ConnectedApp {
                    id: r.id,
                    client_name: r.client_name,
                    scopes,
                    issued_at: r.issued_at.map(|t| from_local(&state, t)),
                    last_used_at: None,
                }
            })
            .collect(),
    ))
}

pub async fn revoke(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<String>,
) -> AppResult<StatusCode> {
    let removed =
        crate::db::query("DELETE FROM oauth2_authorization WHERE id = $1 AND principal_name = $2")
            .bind(&id)
            .bind(&auth.user.username)
            .execute(&state.db)
            .await?
            .rows_affected();
    if removed == 0 {
        return Err(AppError::NotFound("Connected app not found".into()));
    }
    Ok(StatusCode::NO_CONTENT)
}
