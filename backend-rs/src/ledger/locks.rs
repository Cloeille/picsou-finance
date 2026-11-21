//! ConnectionLocks: the member lock an account deletion takes before writing, in the order the
//! CORUM and Louve syncs take it, so a deletion and a running sync cannot deadlock.

use async_trait::async_trait;
use sqlx::PgConnection;

use super::members;
use crate::error::AppResult;

/// The connection an account came from, when its deletion must serialize with that sync.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LockedConnection {
    Corum,
    Louve,
}

#[async_trait]
pub trait ConnectionLocks: Send + Sync {
    /// lockMemberForAccountDeletion: joins the caller's transaction; held until it ends.
    async fn lock_member_for_account_deletion(
        &self,
        conn: &mut PgConnection,
        connection: LockedConnection,
        member_id: i64,
    ) -> AppResult<()>;
}

/// Both Java syncs lock the family_member row (findByIdForUpdate), so this is their behavior;
/// tracks T5/T6 replace it only if their sync locks something else.
#[derive(Debug, Clone, Copy, Default)]
pub struct MemberRowLock;

#[async_trait]
impl ConnectionLocks for MemberRowLock {
    async fn lock_member_for_account_deletion(
        &self,
        conn: &mut PgConnection,
        _connection: LockedConnection,
        member_id: i64,
    ) -> AppResult<()> {
        members::lock_or_throw(conn, member_id).await?;
        Ok(())
    }
}
