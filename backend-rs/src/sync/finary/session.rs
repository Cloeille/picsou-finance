//! FinarySessionRepository: one row per member, email and password encrypted at rest.

use chrono::{DateTime, Utc};

use crate::db::{self, DbExec, DbFromRow};

#[derive(Debug, Clone, DbFromRow)]
pub struct FinarySession {
    pub id: i64,
    pub email: String,
    pub password: String,
    pub status: String,
    pub last_synced_at: Option<DateTime<Utc>>,
}

pub const CONNECTED: &str = "CONNECTED";
pub const TOTP_REQUIRED: &str = "TOTP_REQUIRED";

pub async fn find_by_member<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Option<FinarySession>> {
    db::query_as(
        "SELECT id, email, password, status, last_synced_at FROM finary_session WHERE member_id = $1 ORDER BY id LIMIT 1",
    )
    .bind(member_id)
    .fetch_optional(db)
    .await
}

pub async fn insert<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    email: &str,
    password: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query(
        "INSERT INTO finary_session (member_id, email, password, status, created_at, updated_at) \
         VALUES ($1, $2, $3, 'CONNECTED', $4, $4)",
    )
    .bind(member_id)
    .bind(email)
    .bind(password)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

pub async fn replace_credentials<'e>(
    db: impl DbExec<'e>,
    id: i64,
    email: &str,
    password: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query(
        "UPDATE finary_session SET email = $2, password = $3, status = 'CONNECTED', updated_at = $4 WHERE id = $1",
    )
    .bind(id)
    .bind(email)
    .bind(password)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

/// Sets the status, and the last sync instant when given.
pub async fn update_status<'e>(
    db: impl DbExec<'e>,
    id: i64,
    status: &str,
    synced_at: Option<DateTime<Utc>>,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query(
        "UPDATE finary_session SET status = $2, last_synced_at = COALESCE($3, last_synced_at), updated_at = $4 WHERE id = $1",
    )
    .bind(id)
    .bind(status)
    .bind(synced_at)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

pub async fn delete<'e>(db: impl DbExec<'e>, id: i64) -> sqlx::Result<()> {
    db::query("DELETE FROM finary_session WHERE id = $1")
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}
