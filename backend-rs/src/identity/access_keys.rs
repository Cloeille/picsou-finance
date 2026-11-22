//! AccessKeyController: a member's own MCP access keys (psk_ + 32 base62 characters).

use std::collections::BTreeSet;

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, Utc};
use rand::RngExt;
use serde::{Deserialize, Serialize};
use serde_with::skip_serializing_none;

use crate::AppState;
use crate::auth::Auth;
use crate::auth::persistent::sha256_hex;
use crate::error::{AppError, AppResult, Problem};
use crate::http::json::{Errors, Path};
use crate::http::rate_limit::policies;
use crate::identity::common::JsonBody;
use crate::identity::scopes;

const BASE62: &[u8] = b"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
const BODY_LENGTH: usize = 32;
const PREFIX_LENGTH: usize = 12;

#[derive(crate::db::DbFromRow)]
struct KeyRow {
    id: i64,
    name: String,
    key_prefix: String,
    scopes: String,
    last_used_at: Option<DateTime<Utc>>,
    expires_at: Option<DateTime<Utc>>,
    revoked_at: Option<DateTime<Utc>>,
    created_at: DateTime<Utc>,
}

#[skip_serializing_none]
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccessKeyResponse {
    id: i64,
    name: String,
    key_prefix: String,
    scopes: Vec<String>,
    last_used_at: Option<DateTime<Utc>>,
    expires_at: Option<DateTime<Utc>>,
    revoked_at: Option<DateTime<Utc>>,
    created_at: DateTime<Utc>,
}

impl From<KeyRow> for AccessKeyResponse {
    fn from(row: KeyRow) -> Self {
        let mut scopes: Vec<String> = row.scopes.split_whitespace().map(str::to_owned).collect();
        scopes.sort();
        scopes.dedup();
        Self {
            id: row.id,
            name: row.name,
            key_prefix: row.key_prefix,
            scopes,
            last_used_at: row.last_used_at,
            expires_at: row.expires_at,
            revoked_at: row.revoked_at,
            created_at: row.created_at,
        }
    }
}

const COLUMNS: &str =
    "id, name, key_prefix, scopes, last_used_at, expires_at, revoked_at, created_at";

pub async fn list(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Vec<AccessKeyResponse>>> {
    let rows: Vec<KeyRow> = crate::db::query_as(&format!(
        "SELECT {COLUMNS} FROM access_key WHERE member_id = $1 ORDER BY created_at DESC"
    ))
    .bind(auth.member_id)
    .fetch_all(&state.db)
    .await?;
    Ok(axum::Json(rows.into_iter().map(Into::into).collect()))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateRequest {
    name: Option<String>,
    scopes: Option<BTreeSet<String>>,
    expires_at: Option<DateTime<Utc>>,
}

impl CreateRequest {
    fn validate(&self, now: DateTime<Utc>, e: &mut Errors) {
        let name = self.name.as_deref();
        e.not_blank("name", name).size("name", name, 0, 100);
        let scopes: Option<Vec<&String>> = self.scopes.as_ref().map(|s| s.iter().collect());
        e.not_empty("scopes", scopes.as_deref());
        if self.expires_at.is_some_and(|expires| expires <= now) {
            let message = e.default_message("must be a future date", "doit être dans le futur");
            e.add("expiresAt", message);
        }
    }
}

#[derive(Serialize)]
struct CreatedResponse {
    secret: String,
    key: AccessKeyResponse,
}

fn generate_body() -> String {
    let mut rng = rand::rng();
    (0..BODY_LENGTH)
        .map(|_| char::from(BASE62[rng.random_range(0..BASE62.len())]))
        .collect()
}

pub async fn create(
    State(state): State<AppState>,
    auth: Auth,
    body: JsonBody<CreateRequest>,
) -> AppResult<Response> {
    let mut errors = body.errors();
    body.value.validate(state.clock.now(), &mut errors);
    errors.into_result()?;
    let request = body.value;
    if !state
        .limits
        .try_consume(&policies::ACCESS_KEY_CREATE, &auth.member_id.to_string())
    {
        return Ok(Problem::with_detail(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many access-key creations. Try again later.",
        )
        .into_response());
    }
    let requested = request.scopes.unwrap_or_default();
    if let Some(unknown) = requested.iter().find(|s| !scopes::is_known(s)) {
        return Err(AppError::BadRequest(format!("Unknown scope: {unknown}")));
    }
    let raw = format!("psk_{}", generate_body());
    let row: KeyRow = crate::db::query_as(&format!(
        "INSERT INTO access_key (member_id, created_by, name, key_prefix, key_hash, scopes, expires_at, created_at, updated_at) \
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $8) RETURNING {COLUMNS}"
    ))
    .bind(auth.user.member_id)
    .bind(auth.user.id)
    .bind(request.name.unwrap_or_default())
    .bind(&raw[..PREFIX_LENGTH])
    .bind(sha256_hex(&raw))
    .bind(requested.into_iter().collect::<Vec<_>>().join(" "))
    .bind(request.expires_at)
    .bind(state.clock.now())
    .fetch_one(&state.db)
    .await?;
    Ok((
        StatusCode::CREATED,
        axum::Json(CreatedResponse {
            secret: raw,
            key: row.into(),
        }),
    )
        .into_response())
}

pub async fn delete(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<StatusCode> {
    let revoked = crate::db::query(
        "UPDATE access_key SET revoked_at = COALESCE(revoked_at, $3), updated_at = $3 WHERE id = $1 AND member_id = $2",
    )
    .bind(id)
    .bind(auth.member_id)
    .bind(state.clock.now())
    .execute(&state.db)
    .await?
    .rows_affected();
    if revoked == 0 {
        return Err(AppError::status(
            StatusCode::NOT_FOUND,
            "Access key not found",
        ));
    }
    Ok(StatusCode::NO_CONTENT)
}
