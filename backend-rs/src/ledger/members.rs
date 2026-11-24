//! FamilyMemberRepository: member lookups and the per-member write lock syncs serialize on.

use super::model::FamilyMember;
use crate::db::DbExec;
use crate::error::{AppError, AppResult};

const COLUMNS: &str = "id, display_name, avatar_color, is_managed, created_at";

pub async fn find_by_id<'e>(db: impl DbExec<'e>, id: i64) -> sqlx::Result<Option<FamilyMember>> {
    crate::db::query_as(&format!(
        "SELECT {COLUMNS} FROM family_member WHERE id = $1"
    ))
    .bind(id)
    .fetch_optional(db)
    .await
}

/// Every member, oldest first.
pub async fn find_all<'e>(db: impl DbExec<'e>) -> sqlx::Result<Vec<FamilyMember>> {
    crate::db::query_as(&format!(
        "SELECT {COLUMNS} FROM family_member ORDER BY created_at, id"
    ))
    .fetch_all(db)
    .await
}

/// Managed profiles (no login of their own), or independent members.
pub async fn find_by_managed<'e>(
    db: impl DbExec<'e>,
    managed: bool,
) -> sqlx::Result<Vec<FamilyMember>> {
    crate::db::query_as(&format!(
        "SELECT {COLUMNS} FROM family_member WHERE is_managed = $1 ORDER BY id"
    ))
    .bind(managed)
    .fetch_all(db)
    .await
}

/// SidecarSyncTemplate.lockMember: lock, or the 404 "Family member not found".
pub async fn lock_or_throw<'e>(db: impl DbExec<'e>, member_id: i64) -> AppResult<FamilyMember> {
    lock(db, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Family member not found".to_owned()))
}

/// findByIdForUpdate: row-locks the member until the transaction ends, so two syncs (or a sync
/// and an account deletion) of one member never interleave their writes.
pub async fn lock<'e>(db: impl DbExec<'e>, member_id: i64) -> sqlx::Result<Option<FamilyMember>> {
    crate::db::query_as(&format!(
        "SELECT {COLUMNS} FROM family_member WHERE id = $1 FOR UPDATE"
    ))
    .bind(member_id)
    .fetch_optional(db)
    .await
}
