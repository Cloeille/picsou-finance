//! AccountDisplayPrefRepository: each viewer's own list position and collapsed state of an
//! account (hidden is a column of the account itself).

use sqlx::PgExecutor;

use super::model::AccountDisplayPref;

const COLUMNS: &str = "id, account_id, member_id, list_position, list_collapsed";

pub async fn find_by_member<'e>(
    db: impl PgExecutor<'e>,
    member_id: i64,
) -> sqlx::Result<Vec<AccountDisplayPref>> {
    sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM account_display_pref WHERE member_id = $1 ORDER BY id"
    ))
    .bind(member_id)
    .fetch_all(db)
    .await
}

pub async fn find_by_member_and_account<'e>(
    db: impl PgExecutor<'e>,
    member_id: i64,
    account_id: i64,
) -> sqlx::Result<Option<AccountDisplayPref>> {
    sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM account_display_pref WHERE member_id = $1 AND account_id = $2"
    ))
    .bind(member_id)
    .bind(account_id)
    .fetch_optional(db)
    .await
}

/// Writes the given fields of the viewer's row, creating it (not collapsed) when absent; a None
/// field keeps its stored value.
pub async fn upsert<'e>(
    db: impl PgExecutor<'e>,
    account_id: i64,
    member_id: i64,
    position: Option<i32>,
    collapsed: Option<bool>,
) -> sqlx::Result<()> {
    sqlx::query(
        "INSERT INTO account_display_pref (account_id, member_id, list_position, list_collapsed, created_at, updated_at) \
         VALUES ($1, $2, $3, COALESCE($4, FALSE), NOW(), NOW()) \
         ON CONFLICT (account_id, member_id) DO UPDATE SET \
         list_position = COALESCE($3, account_display_pref.list_position), \
         list_collapsed = COALESCE($4, account_display_pref.list_collapsed), updated_at = NOW()",
    )
    .bind(account_id)
    .bind(member_id)
    .bind(position)
    .bind(collapsed)
    .execute(db)
    .await?;
    Ok(())
}

/// Forgets every list position of the viewer.
pub async fn clear_positions<'e>(db: impl PgExecutor<'e>, member_id: i64) -> sqlx::Result<()> {
    sqlx::query(
        "UPDATE account_display_pref SET list_position = NULL, updated_at = NOW() WHERE member_id = $1",
    )
    .bind(member_id)
    .execute(db)
    .await?;
    Ok(())
}
