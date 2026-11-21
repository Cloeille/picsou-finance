//! SimplefinConnection and its repository: one row per member, the access URL encrypted.

use chrono::{DateTime, Utc};
use sqlx::{FromRow, PgExecutor};

#[derive(Debug, Clone, PartialEq, Eq, FromRow)]
pub struct Connection {
    pub id: i64,
    pub member_id: i64,
    /// AES-GCM ciphertext of the access URL, which embeds HTTP Basic credentials.
    pub access_url: String,
    pub status: String,
    pub last_synced_at: Option<DateTime<Utc>>,
}

const COLUMNS: &str = "id, member_id, access_url, status, last_synced_at";

pub async fn find_by_member<'e>(
    db: impl PgExecutor<'e>,
    member_id: i64,
) -> sqlx::Result<Option<Connection>> {
    sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM simplefin_connection WHERE member_id = $1"
    ))
    .bind(member_id)
    .fetch_optional(db)
    .await
}

/// Stores a new access URL as CONNECTED, in place when the member already has a row.
pub async fn save_access<'e>(
    db: impl PgExecutor<'e>,
    member_id: i64,
    encrypted_access_url: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    sqlx::query(
        "INSERT INTO simplefin_connection (member_id, access_url, status, created_at, updated_at) \
         VALUES ($1, $2, 'CONNECTED', $3, $3) ON CONFLICT (member_id) DO UPDATE SET \
         access_url = EXCLUDED.access_url, status = 'CONNECTED', updated_at = EXCLUDED.updated_at",
    )
    .bind(member_id)
    .bind(encrypted_access_url)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

pub async fn mark_synced<'e>(
    db: impl PgExecutor<'e>,
    id: i64,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    sqlx::query(
        "UPDATE simplefin_connection SET status = 'CONNECTED', last_synced_at = $2, updated_at = $2 \
         WHERE id = $1",
    )
    .bind(id)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

/// SimplefinStatusWriter.markError: false when the row is gone.
pub async fn mark_error<'e>(
    db: impl PgExecutor<'e>,
    id: i64,
    now: DateTime<Utc>,
) -> sqlx::Result<bool> {
    let updated = sqlx::query(
        "UPDATE simplefin_connection SET status = 'ERROR', updated_at = $2 WHERE id = $1",
    )
    .bind(id)
    .bind(now)
    .execute(db)
    .await?;
    Ok(updated.rows_affected() > 0)
}

pub async fn delete_by_member<'e>(db: impl PgExecutor<'e>, member_id: i64) -> sqlx::Result<bool> {
    let deleted = sqlx::query("DELETE FROM simplefin_connection WHERE member_id = $1")
        .bind(member_id)
        .execute(db)
        .await?;
    Ok(deleted.rows_affected() > 0)
}
