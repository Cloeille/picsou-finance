//! IbkrConnection and IbkrConnectionRepository: one row per member holding the encrypted Flex
//! token and query id, the last sync outcome and instant.

use chrono::{DateTime, Utc};

use crate::db::{self, DbExec, DbFromRow};

#[derive(Debug, Clone, PartialEq, Eq, DbFromRow)]
pub struct IbkrConnection {
    pub id: i64,
    pub member_id: i64,
    /// CryptoEncryption ciphertext of the Flex Web Service token.
    pub token: String,
    /// CryptoEncryption ciphertext of the Flex Query id.
    pub query_id: String,
    /// "CONNECTED" or "ERROR" (the last sync outcome).
    pub status: String,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

const COLUMNS: &str =
    "id, member_id, token, query_id, status, last_synced_at, created_at, updated_at";

pub async fn find_by_member<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Option<IbkrConnection>> {
    db::query_as(&format!(
        "SELECT {COLUMNS} FROM ibkr_connection WHERE member_id = $1"
    ))
    .bind(member_id)
    .fetch_optional(db)
    .await
}

/// Stores or replaces the member's credentials (already encrypted) with status CONNECTED; the
/// last sync instant of a replaced row is kept.
pub async fn upsert_credentials<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    token: &str,
    query_id: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query(
        "INSERT INTO ibkr_connection (member_id, token, query_id, status, created_at, updated_at) \
         VALUES ($1, $2, $3, 'CONNECTED', $4, $4) \
         ON CONFLICT (member_id) DO UPDATE SET token = EXCLUDED.token, query_id = EXCLUDED.query_id, \
         status = 'CONNECTED', updated_at = EXCLUDED.updated_at",
    )
    .bind(member_id)
    .bind(token)
    .bind(query_id)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

/// Records a successful sync.
pub async fn mark_synced<'e>(db: impl DbExec<'e>, id: i64, now: DateTime<Utc>) -> sqlx::Result<()> {
    db::query(
        "UPDATE ibkr_connection SET status = 'CONNECTED', last_synced_at = $2, updated_at = $2 \
         WHERE id = $1",
    )
    .bind(id)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

/// IbkrStatusWriter.markError: false when the row is gone (deleted mid-sync).
pub async fn mark_error<'e>(
    db: impl DbExec<'e>,
    id: i64,
    now: DateTime<Utc>,
) -> sqlx::Result<bool> {
    let result =
        db::query("UPDATE ibkr_connection SET status = 'ERROR', updated_at = $2 WHERE id = $1")
            .bind(id)
            .bind(now)
            .execute(db)
            .await?;
    Ok(result.rows_affected() > 0)
}

/// Whether a stored connection was there to delete.
pub async fn delete_by_member<'e>(db: impl DbExec<'e>, member_id: i64) -> sqlx::Result<bool> {
    let result = db::query("DELETE FROM ibkr_connection WHERE member_id = $1")
        .bind(member_id)
        .execute(db)
        .await?;
    Ok(result.rows_affected() > 0)
}
