//! The wise_connection table (WiseConnectionRepository and WiseStatusWriter). Updates write only
//! the columns they change, as the entity's @DynamicUpdate: a sync's status write must not put
//! back a token a concurrent connect stored meanwhile.

use chrono::{DateTime, Utc};

use crate::db::{self, DbExec, DbFromRow};

/// One member's Wise connection. The encrypted token stays inside this module.
#[derive(Debug, Clone, PartialEq, DbFromRow)]
pub struct WiseConnection {
    pub id: i64,
    pub member_id: i64,
    pub(super) api_token: String,
    /// CONNECTED, ERROR or REAUTH_REQUIRED.
    pub status: String,
    pub last_synced_at: Option<DateTime<Utc>>,
}

const COLUMNS: &str = "id, member_id, api_token, status, last_synced_at";

pub(super) async fn find_by_member<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Option<WiseConnection>> {
    db::query_as(&format!(
        "SELECT {COLUMNS} FROM wise_connection WHERE member_id = $1"
    ))
    .bind(member_id)
    .fetch_optional(db)
    .await
}

/// Stores a token Wise just accepted, on the member's row or a new one, marked connected.
pub(super) async fn save_token<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    encrypted_token: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query(
        "INSERT INTO wise_connection (member_id, api_token, status, last_synced_at, created_at, updated_at) \
         VALUES ($1, $2, 'CONNECTED', $3, $3, $3) \
         ON CONFLICT (member_id) DO UPDATE SET api_token = EXCLUDED.api_token, status = 'CONNECTED', \
         last_synced_at = EXCLUDED.last_synced_at, updated_at = EXCLUDED.updated_at",
    )
    .bind(member_id)
    .bind(encrypted_token)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

pub(super) async fn mark_synced<'e>(
    db: impl DbExec<'e>,
    connection_id: i64,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query(
        "UPDATE wise_connection SET status = 'CONNECTED', last_synced_at = $2, updated_at = $2 WHERE id = $1",
    )
    .bind(connection_id)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

/// WiseStatusWriter: a failed sync's status, written outside the sync's rolled-back transaction.
pub(super) async fn mark<'e>(
    db: impl DbExec<'e>,
    connection_id: i64,
    status: &str,
    now: DateTime<Utc>,
) {
    let updated =
        db::query("UPDATE wise_connection SET status = $2, updated_at = $3 WHERE id = $1")
            .bind(connection_id)
            .bind(status)
            .bind(now)
            .execute(db)
            .await;
    match updated {
        Ok(done) if done.rows_affected() == 0 => {
            tracing::error!(
                "Cannot mark Wise connection {connection_id} as {status}: row not found"
            );
        }
        Ok(_) => {}
        Err(error) => {
            tracing::error!("Cannot mark Wise connection {connection_id} as {status}: {error}");
        }
    }
}

pub(super) async fn delete_by_member<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<bool> {
    let deleted = db::query("DELETE FROM wise_connection WHERE member_id = $1")
        .bind(member_id)
        .execute(db)
        .await?;
    Ok(deleted.rows_affected() > 0)
}
