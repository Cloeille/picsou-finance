//! Sharing settings (any signed-in member): what of their accounts and goals the rest of the
//! family sees, as NONE, ALL or MANUAL with the chosen resource ids.

use std::collections::HashSet;
use std::sync::LazyLock;

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use axum::extract::{RawQuery, State};
use http::StatusCode;
use regex::Regex;
use serde::{Deserialize, Serialize};

use crate::AppState;
use crate::auth::Auth;
use crate::db::{self, DbExec};
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Json, Validate};
use crate::ledger::accounts;
use crate::routes::Routes;

pub const ACCOUNT: &str = "ACCOUNT";
pub const GOAL: &str = "GOAL";

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/family/sharing", get_settings)
        .put("/api/family/sharing", update_settings);
}

#[derive(
    Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, sqlx::Type, crate::db::DbEnum,
)]
#[sqlx(type_name = "sharing_level")]
#[serde(rename_all = "UPPERCASE")]
#[sqlx(rename_all = "UPPERCASE")]
pub enum SharingLevel {
    All,
    None,
    Manual,
}

/// The member's level for a resource type; NONE without a row.
pub async fn level<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    resource_type: &str,
) -> sqlx::Result<SharingLevel> {
    let level: Option<SharingLevel> = db::query_scalar(
        "SELECT sharing_level FROM sharing_settings WHERE member_id = $1 AND resource_type = $2",
    )
    .bind(member_id)
    .bind(resource_type)
    .fetch_optional(db)
    .await?;
    Ok(level.unwrap_or(SharingLevel::None))
}

/// The ids the member shares by hand for a resource type, in the order they were stored.
pub async fn shared_ids<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    resource_type: &str,
) -> sqlx::Result<Vec<i64>> {
    db::query_scalar(
        "SELECT resource_id FROM shared_resource WHERE owner_member_id = $1 AND resource_type = $2 ORDER BY id",
    )
    .bind(member_id)
    .bind(resource_type)
    .fetch_all(db)
    .await
}

pub async fn is_shared<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    resource_type: &str,
    resource_id: i64,
) -> sqlx::Result<bool> {
    db::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM shared_resource WHERE owner_member_id = $1 AND resource_type = $2 AND resource_id = $3)",
    )
    .bind(member_id)
    .bind(resource_type)
    .bind(resource_id)
    .fetch_one(db)
    .await
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SharingSettingsResponse {
    resource_type: String,
    sharing_level: SharingLevel,
    shared_resource_ids: Vec<i64>,
}

fn required_param(query: Option<&str>, name: &str) -> AppResult<String> {
    url::form_urlencoded::parse(query.unwrap_or_default().as_bytes())
        .find(|(k, _)| k == name)
        .map(|(_, v)| v.into_owned())
        .ok_or_else(|| AppError::BadRequest(format!("Required parameter '{name}' is not present.")))
}

async fn get_settings(
    State(state): State<AppState>,
    auth: Auth,
    RawQuery(query): RawQuery,
) -> AppResult<axum::Json<SharingSettingsResponse>> {
    let resource_type = required_param(query.as_deref(), "resourceType")?;
    let mut conn = state.db.pool_handle();
    let sharing_level = level(&mut conn, auth.member_id, &resource_type).await?;
    let shared_resource_ids = match sharing_level {
        SharingLevel::Manual => shared_ids(&mut conn, auth.member_id, &resource_type).await?,
        // The client reads [-1] as "everything".
        SharingLevel::All => vec![-1],
        SharingLevel::None => Vec::new(),
    };
    Ok(axum::Json(SharingSettingsResponse {
        resource_type,
        sharing_level,
        shared_resource_ids,
    }))
}

static RESOURCE_TYPE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^(?:ACCOUNT|GOAL)$").expect("regex"));

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SharingSettingsRequest {
    resource_type: Option<String>,
    sharing_level: Option<SharingLevel>,
    shared_resource_ids: Option<Vec<Option<i64>>>,
}

impl Validate for SharingSettingsRequest {
    fn validate(&self, errors: &mut Errors) {
        errors
            .not_blank("resourceType", self.resource_type.as_deref())
            .pattern(
                "resourceType",
                self.resource_type.as_deref(),
                &RESOURCE_TYPE,
                "must match \"ACCOUNT|GOAL\"",
            )
            .not_null("sharingLevel", self.sharing_level.as_ref());
        for (i, id) in self.shared_resource_ids.iter().flatten().enumerate() {
            errors.not_null(&format!("sharedResourceIds[{i}]"), id.as_ref());
        }
    }
}

/// The requested ids without duplicates, every one owned by the member, or the 400 before
/// anything is written.
async fn validated_ids(
    conn: &mut DbHandle<'_>,
    resource_type: &str,
    member_id: i64,
    ids: Option<&[Option<i64>]>,
) -> AppResult<Vec<i64>> {
    let mut seen = HashSet::new();
    let mut distinct = Vec::new();
    for id in ids.unwrap_or_default() {
        let id = id.ok_or_else(|| {
            AppError::BadRequest("Shared resource IDs must not contain null".into())
        })?;
        if seen.insert(id) {
            distinct.push(id);
        }
    }
    if distinct.is_empty() {
        return Ok(distinct);
    }
    let resolved = match resource_type {
        ACCOUNT => accounts::find_by_ids_and_member(&mut *conn, &distinct, member_id)
            .await?
            .len() as i64,
        _ => {
            db::query_scalar::<i64>(
                "SELECT count(*) FROM goal WHERE id = ANY($1) AND member_id = $2",
            )
            .bind(&distinct)
            .bind(member_id)
            .fetch_one(&mut *conn)
            .await?
        }
    };
    if resolved != distinct.len() as i64 {
        return Err(AppError::BadRequest(
            "One or more shared resource IDs not found".into(),
        ));
    }
    Ok(distinct)
}

async fn update_settings(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<SharingSettingsRequest>,
) -> AppResult<StatusCode> {
    let resource_type = req
        .resource_type
        .as_deref()
        .filter(|t| *t == ACCOUNT || *t == GOAL)
        .ok_or_else(|| AppError::BadRequest("Unsupported resource type".into()))?;
    let sharing_level = req
        .sharing_level
        .ok_or_else(|| AppError::BadRequest("Sharing level is required".into()))?;
    let member_id = auth.member_id;
    let mut tx = state.db.begin().await?;
    let manual_ids = if sharing_level == SharingLevel::Manual {
        validated_ids(
            &mut tx.as_handle(),
            resource_type,
            member_id,
            req.shared_resource_ids.as_deref(),
        )
        .await?
    } else {
        Vec::new()
    };
    db::query(
        "INSERT INTO sharing_settings (member_id, resource_type, sharing_level) VALUES ($1, $2, $3) \
         ON CONFLICT (member_id, resource_type) DO UPDATE SET sharing_level = EXCLUDED.sharing_level",
    )
    .bind(member_id)
    .bind(resource_type)
    .bind(sharing_level)
    .execute(&mut tx)
    .await?;
    db::query("DELETE FROM shared_resource WHERE owner_member_id = $1 AND resource_type = $2")
        .bind(member_id)
        .bind(resource_type)
        .execute(&mut tx)
        .await?;
    let now = state.clock.now();
    for id in manual_ids {
        db::query(
            "INSERT INTO shared_resource (owner_member_id, resource_type, resource_id, created_at) VALUES ($1, $2, $3, $4)",
        )
        .bind(member_id)
        .bind(resource_type)
        .bind(id)
        .bind(now)
        .execute(&mut tx)
        .await?;
    }
    tx.commit().await?;
    Ok(StatusCode::OK)
}
