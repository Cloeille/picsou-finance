//! LouveConnectionRepository: the member's louve_connection row. Locked reads bound their wait
//! with SET LOCAL lock_timeout (five seconds, the rest of the transaction included), so slow
//! refreshes cannot pile up waiters that each hold a pooled connection.

use chrono::{DateTime, Utc};

use crate::db::{self, DbExec, DbFromRow, DbHandle};

pub const STATUS_ERROR: &str = "ERROR";
/// Louve refused the grant (revoked, expired, unknown client): only a new consent helps.
pub const STATUS_REAUTH_REQUIRED: &str = "REAUTH_REQUIRED";

#[derive(Debug, Clone, DbFromRow)]
pub struct Connection {
    pub id: i64,
    /// The OAuth token set (JSON), AES-256-GCM encrypted. None until the first consent.
    pub token_state: Option<String>,
    pub status: String,
    pub last_synced_at: Option<DateTime<Utc>>,
    /// A new consent increments it; a refresh-token rotation within the same grant does not.
    pub grant_generation: i64,
    pub oauth_state: Option<String>,
    pub oauth_verifier: Option<String>,
    pub oauth_redirect_uri: Option<String>,
    pub oauth_client_id: Option<String>,
    pub oauth_started_at: Option<DateTime<Utc>>,
}

const COLUMNS: &str = "id, token_state, status, last_synced_at, grant_generation, oauth_state, \
    oauth_verifier, oauth_redirect_uri, oauth_client_id, oauth_started_at";

pub async fn find_by_member<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Option<Connection>> {
    db::query_as(&format!(
        "SELECT {COLUMNS} FROM louve_connection WHERE member_id = $1"
    ))
    .bind(member_id)
    .fetch_optional(db)
    .await
}

/// findByMemberIdForUpdate: the member's row, locked until the transaction ends.
pub async fn lock_by_member(
    conn: &mut DbHandle<'_>,
    member_id: i64,
) -> sqlx::Result<Option<Connection>> {
    if !conn.is_sqlite() {
        db::query("SET LOCAL lock_timeout = '5s'")
            .execute(&mut *conn)
            .await?;
    }
    db::query_as(&format!(
        "SELECT {COLUMNS} FROM louve_connection WHERE member_id = $1 FOR UPDATE"
    ))
    .bind(member_id)
    .fetch_optional(&mut *conn)
    .await
}

pub struct Attempt<'a> {
    pub state: &'a str,
    pub verifier: &'a str,
    pub redirect_uri: &'a str,
    pub client_id: &'a str,
}

/// Records a pending attempt, creating a PENDING row on first use; stored tokens are kept.
pub async fn save_attempt<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    existing: Option<i64>,
    attempt: &Attempt<'_>,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    let query = match existing {
        Some(id) => db::query(
            "UPDATE louve_connection SET oauth_state = $2, oauth_verifier = $3, oauth_redirect_uri = $4, \
             oauth_client_id = $5, oauth_started_at = $6, updated_at = $6 WHERE id = $1",
        )
        .bind(id),
        None => db::query(
            "INSERT INTO louve_connection (member_id, oauth_state, oauth_verifier, oauth_redirect_uri, \
             oauth_client_id, oauth_started_at, status, created_at, updated_at) \
             VALUES ($1, $2, $3, $4, $5, $6, 'PENDING', $6, $6)",
        )
        .bind(member_id),
    };
    query
        .bind(attempt.state)
        .bind(attempt.verifier)
        .bind(attempt.redirect_uri)
        .bind(attempt.client_id)
        .bind(now)
        .execute(db)
        .await?;
    Ok(())
}

/// Clears the pending attempt: spent, abandoned or superseded.
pub async fn clear_attempt<'e>(
    db: impl DbExec<'e>,
    id: i64,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query(
        "UPDATE louve_connection SET oauth_state = NULL, oauth_verifier = NULL, oauth_redirect_uri = NULL, \
         oauth_client_id = NULL, oauth_started_at = NULL, updated_at = $2 WHERE id = $1",
    )
    .bind(id)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

/// A new consent: the token set, the next generation, CONNECTED.
pub async fn store_grant<'e>(
    db: impl DbExec<'e>,
    id: i64,
    token_state: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query(
        "UPDATE louve_connection SET token_state = $2, grant_generation = grant_generation + 1, \
         status = 'CONNECTED', updated_at = $3 WHERE id = $1",
    )
    .bind(id)
    .bind(token_state)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

/// A rotation within the same grant: only the token set changes.
pub async fn store_tokens<'e>(
    db: impl DbExec<'e>,
    id: i64,
    token_state: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query("UPDATE louve_connection SET token_state = $2, updated_at = $3 WHERE id = $1")
        .bind(id)
        .bind(token_state)
        .bind(now)
        .execute(db)
        .await?;
    Ok(())
}

pub async fn set_status<'e>(
    db: impl DbExec<'e>,
    id: i64,
    status: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query("UPDATE louve_connection SET status = $2, updated_at = $3 WHERE id = $1")
        .bind(id)
        .bind(status)
        .bind(now)
        .execute(db)
        .await?;
    Ok(())
}

pub async fn set_synced<'e>(
    db: impl DbExec<'e>,
    id: i64,
    synced_at: DateTime<Utc>,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query(
        "UPDATE louve_connection SET status = 'CONNECTED', last_synced_at = $2, updated_at = $3 WHERE id = $1",
    )
    .bind(id)
    .bind(synced_at)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

pub async fn delete<'e>(db: impl DbExec<'e>, id: i64) -> sqlx::Result<()> {
    db::query("DELETE FROM louve_connection WHERE id = $1")
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}
