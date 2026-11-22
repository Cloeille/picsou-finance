//! RequisitionRepository and the Requisition entity: one Enable Banking connection of a member.

use chrono::{DateTime, Utc};
use serde::Serialize;
use sqlx::{FromRow, PgExecutor};

use crate::error::AppError;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, sqlx::Type, crate::db::DbEnum)]
#[sqlx(type_name = "requisition_status", rename_all = "SCREAMING_SNAKE_CASE")]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RequisitionStatus {
    Created,
    Linked,
    Expired,
    Failed,
}

/// The row, and the JSON GET /api/sync/status lists (member and oauth_state are @JsonIgnore).
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, FromRow, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Requisition {
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub id: i64,
    #[serde(skip)]
    pub member_id: i64,
    /// The Enable Banking authorization id until the code exchange, the session id after it.
    pub requisition_id: String,
    /// Composite "name::country::psuType".
    pub institution_id: String,
    pub institution_name: Option<String>,
    pub logo_url: Option<String>,
    /// Set the first time a logo backfill is attempted, so a permanent miss is not retried.
    pub logo_backfill_attempted_at: Option<DateTime<Utc>>,
    pub status: RequisitionStatus,
    pub auth_link: Option<String>,
    /// The OAuth state nonce: cleared once the code is exchanged, kept when the exchange fails.
    #[serde(skip)]
    pub oauth_state: Option<String>,
    pub last_synced_at: Option<DateTime<Utc>>,
}

pub struct NewRequisition<'a> {
    pub member_id: i64,
    pub requisition_id: &'a str,
    pub institution_id: &'a str,
    pub institution_name: &'a str,
    pub logo_url: Option<String>,
    pub auth_link: &'a str,
    pub oauth_state: &'a str,
}

const COLUMNS: &str = "id, member_id, requisition_id, institution_id, institution_name, logo_url, \
    logo_backfill_attempted_at, status, auth_link, oauth_state, last_synced_at, created_at, updated_at";

pub fn not_found() -> AppError {
    AppError::NotFound("Requisition not found".to_owned())
}

pub async fn find_all_by_member<'e>(
    db: impl PgExecutor<'e>,
    member_id: i64,
) -> sqlx::Result<Vec<Requisition>> {
    sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM requisition WHERE member_id = $1"
    ))
    .bind(member_id)
    .fetch_all(db)
    .await
}

pub async fn find_by_id_and_member<'e>(
    db: impl PgExecutor<'e>,
    id: i64,
    member_id: i64,
) -> sqlx::Result<Option<Requisition>> {
    sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM requisition WHERE id = $1 AND member_id = $2"
    ))
    .bind(id)
    .bind(member_id)
    .fetch_optional(db)
    .await
}

/// Newest first; the id breaks ties between rows created in the same instant.
pub async fn find_by_status_and_member<'e>(
    db: impl PgExecutor<'e>,
    status: RequisitionStatus,
    member_id: i64,
) -> sqlx::Result<Vec<Requisition>> {
    sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM requisition WHERE status = $1 AND member_id = $2 \
         ORDER BY created_at DESC, id DESC"
    ))
    .bind(status)
    .bind(member_id)
    .fetch_all(db)
    .await
}

pub async fn find_by_status_member_and_institution<'e>(
    db: impl PgExecutor<'e>,
    status: RequisitionStatus,
    member_id: i64,
    institution_id: &str,
) -> sqlx::Result<Vec<Requisition>> {
    sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM requisition WHERE status = $1 AND member_id = $2 AND institution_id = $3 \
         ORDER BY created_at DESC, id DESC"
    ))
    .bind(status)
    .bind(member_id)
    .bind(institution_id)
    .fetch_all(db)
    .await
}

/// Deliberately not member-scoped: the random single-use nonce is the credential binding the
/// callback to its requisition, and the member comes from the row it resolves to.
pub async fn find_by_oauth_state<'e>(
    db: impl PgExecutor<'e>,
    oauth_state: &str,
) -> sqlx::Result<Option<Requisition>> {
    sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM requisition WHERE oauth_state = $1"
    ))
    .bind(oauth_state)
    .fetch_optional(db)
    .await
}

pub async fn insert<'e>(
    db: impl PgExecutor<'e>,
    new: &NewRequisition<'_>,
    now: DateTime<Utc>,
) -> sqlx::Result<Requisition> {
    sqlx::query_as(&format!(
        "INSERT INTO requisition (member_id, requisition_id, institution_id, institution_name, logo_url, status, \
         auth_link, oauth_state, created_at, updated_at) VALUES ($1, $2, $3, $4, $5, 'CREATED', $6, $7, $8, $8) \
         RETURNING {COLUMNS}"
    ))
    .bind(new.member_id)
    .bind(new.requisition_id)
    .bind(new.institution_id)
    .bind(new.institution_name)
    .bind(&new.logo_url)
    .bind(new.auth_link)
    .bind(new.oauth_state)
    .bind(now)
    .fetch_one(db)
    .await
}

/// Writes every mutable column back (JPA save of a loaded entity).
pub async fn save<'e>(
    db: impl PgExecutor<'e>,
    r: &Requisition,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    sqlx::query(
        "UPDATE requisition SET requisition_id = $2, institution_id = $3, institution_name = $4, logo_url = $5, \
         logo_backfill_attempted_at = $6, status = $7, auth_link = $8, oauth_state = $9, last_synced_at = $10, \
         updated_at = $11 WHERE id = $1",
    )
    .bind(r.id)
    .bind(&r.requisition_id)
    .bind(&r.institution_id)
    .bind(&r.institution_name)
    .bind(&r.logo_url)
    .bind(r.logo_backfill_attempted_at)
    .bind(r.status)
    .bind(&r.auth_link)
    .bind(&r.oauth_state)
    .bind(r.last_synced_at)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

pub async fn delete<'e>(db: impl PgExecutor<'e>, id: i64) -> sqlx::Result<()> {
    sqlx::query("DELETE FROM requisition WHERE id = $1")
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}

/// RequisitionLifecycleWriter.checkpointSession, in its own statement so it survives a rollback
/// of the account writes: the exchanged session id, and the spent nonce cleared.
pub async fn checkpoint_session<'e>(
    db: impl PgExecutor<'e>,
    id: i64,
    member_id: i64,
    session_id: &str,
    now: DateTime<Utc>,
) -> Result<(), AppError> {
    let updated = sqlx::query(
        "UPDATE requisition SET requisition_id = $3, oauth_state = NULL, updated_at = $4 \
         WHERE id = $1 AND member_id = $2",
    )
    .bind(id)
    .bind(member_id)
    .bind(session_id)
    .bind(now)
    .execute(db)
    .await?;
    if updated.rows_affected() == 0 {
        return Err(not_found());
    }
    Ok(())
}

/// RequisitionLifecycleWriter.markFailed, in its own statement.
pub async fn mark_failed<'e>(
    db: impl PgExecutor<'e>,
    id: i64,
    member_id: i64,
    now: DateTime<Utc>,
) -> Result<(), AppError> {
    let updated = sqlx::query(
        "UPDATE requisition SET status = 'FAILED', updated_at = $3 WHERE id = $1 AND member_id = $2",
    )
    .bind(id)
    .bind(member_id)
    .bind(now)
    .execute(db)
    .await?;
    if updated.rows_affected() == 0 {
        return Err(not_found());
    }
    Ok(())
}
