//! CryptoExchangeSessionRepository and CryptoExchangePositionRepository.

use chrono::{DateTime, Utc};

use super::model::{ExchangePosition, PositionRow, SessionRow};
use crate::db::{self, DbExec, DbHandle};

const SESSION_COLUMNS: &str = "id, member_id, exchange_type, api_key, api_secret, api_passphrase, region, status, \
    last_synced_at";

/// findAllByMemberId, in creation order.
pub async fn sessions_of_member<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Vec<SessionRow>> {
    db::query_as(&format!(
        "SELECT {SESSION_COLUMNS} FROM crypto_exchange_session WHERE member_id = $1 ORDER BY id"
    ))
    .bind(member_id)
    .fetch_all(db)
    .await
}

/// findByIdAndMemberId.
pub async fn session_of_member<'e>(
    db: impl DbExec<'e>,
    id: i64,
    member_id: i64,
) -> sqlx::Result<Option<SessionRow>> {
    db::query_as(&format!(
        "SELECT {SESSION_COLUMNS} FROM crypto_exchange_session WHERE id = $1 AND member_id = $2"
    ))
    .bind(id)
    .bind(member_id)
    .fetch_optional(db)
    .await
}

/// findByExchangeTypeAndMemberId.
pub async fn session_by_type<'e>(
    db: impl DbExec<'e>,
    exchange_type: &str,
    member_id: i64,
) -> sqlx::Result<Option<SessionRow>> {
    db::query_as(&format!(
        "SELECT {SESSION_COLUMNS} FROM crypto_exchange_session WHERE exchange_type = $1 AND member_id = $2 \
         ORDER BY id LIMIT 1"
    ))
    .bind(exchange_type)
    .bind(member_id)
    .fetch_optional(db)
    .await
}

/// The encrypted credentials of a connection to save.
pub struct SessionDraft<'a> {
    pub member_id: i64,
    pub exchange_type: &'a str,
    pub api_key: &'a str,
    pub api_secret: Option<&'a str>,
    pub api_passphrase: Option<&'a str>,
    pub region: Option<&'a str>,
}

/// Rewrites the credentials of the existing session, or inserts one; status CONNECTED. The id.
pub async fn save_session<'e>(
    db: impl DbExec<'e>,
    existing: Option<i64>,
    draft: &SessionDraft<'_>,
    now: DateTime<Utc>,
) -> sqlx::Result<i64> {
    match existing {
        Some(id) => {
            db::query(
                "UPDATE crypto_exchange_session SET api_key = $2, api_secret = $3, api_passphrase = $4, \
                 region = $5, status = 'CONNECTED', updated_at = $6 WHERE id = $1",
            )
            .bind(id)
            .bind(draft.api_key)
            .bind(draft.api_secret)
            .bind(draft.api_passphrase)
            .bind(draft.region)
            .bind(now)
            .execute(db)
            .await?;
            Ok(id)
        }
        None => db::query_scalar(
            "INSERT INTO crypto_exchange_session (member_id, exchange_type, api_key, api_secret, \
                 api_passphrase, region, status, created_at, updated_at) \
                 VALUES ($1, $2, $3, $4, $5, $6, 'CONNECTED', $7, $7) RETURNING id",
        )
        .bind(draft.member_id)
        .bind(draft.exchange_type)
        .bind(draft.api_key)
        .bind(draft.api_secret)
        .bind(draft.api_passphrase)
        .bind(draft.region)
        .bind(now)
        .fetch_one(db)
        .await,
    }
}

/// The last write of a successful sync.
pub async fn mark_connected<'e>(
    db: impl DbExec<'e>,
    id: i64,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query(
        "UPDATE crypto_exchange_session SET status = 'CONNECTED', last_synced_at = $2, updated_at = $2 \
         WHERE id = $1",
    )
    .bind(id)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

/// CryptoExchangeStatusWriter.markError: a session not committed or already gone is a no-op.
pub async fn mark_error<'e>(db: impl DbExec<'e>, id: i64, now: DateTime<Utc>) -> sqlx::Result<()> {
    db::query("UPDATE crypto_exchange_session SET status = 'ERROR', updated_at = $2 WHERE id = $1")
        .bind(id)
        .bind(now)
        .execute(db)
        .await?;
    Ok(())
}

pub async fn delete_session<'e>(db: impl DbExec<'e>, id: i64) -> sqlx::Result<()> {
    db::query("DELETE FROM crypto_exchange_session WHERE id = $1")
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}

/// findByAccountIdOrderByProductAscTickerAsc.
pub async fn positions_of_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
) -> sqlx::Result<Vec<PositionRow>> {
    db::query_as(
        "SELECT product, ticker, quantity, principal, interest FROM crypto_exchange_position \
         WHERE account_id = $1 ORDER BY product, ticker",
    )
    .bind(account_id)
    .fetch_all(db)
    .await
}

/// Rewrites the account's per-product breakdown wholesale: derived display data with no cost
/// basis to preserve, and no product line can linger after the user unstakes everything.
pub async fn replace_positions(
    conn: &mut DbHandle<'_>,
    account_id: i64,
    positions: &[ExchangePosition],
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query("DELETE FROM crypto_exchange_position WHERE account_id = $1")
        .bind(account_id)
        .execute(&mut *conn)
        .await?;
    for position in positions {
        db::query(
            "INSERT INTO crypto_exchange_position (account_id, product, ticker, quantity, principal, interest, \
             last_synced_at, created_at, updated_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $7, $7)",
        )
        .bind(account_id)
        .bind(position.product.as_str())
        .bind(position.symbol.to_uppercase())
        .bind(position.quantity)
        .bind(position.principal)
        .bind(position.interest)
        .bind(now)
        .execute(&mut *conn)
        .await?;
    }
    Ok(())
}

/// Stamps account.deleted_at, a TIMESTAMP column (V30), with the UTC wall-clock time.
/// ledger::accounts::update cannot do it: its RETURNING decodes deleted_at as TIMESTAMPTZ.
pub async fn soft_delete_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query(
        "UPDATE account SET deleted_at = ($2::timestamptz AT TIME ZONE 'UTC'), updated_at = $2 WHERE id = $1",
    )
    .bind(account_id)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}
