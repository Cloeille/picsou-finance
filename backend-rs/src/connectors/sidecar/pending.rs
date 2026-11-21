//! Logins waiting for their second factor, held in memory as Java's CmesSyncService and
//! CicSyncService do: the typed credentials are kept until /complete so the session can store
//! them, and a disconnect bumps the member's generation so a login racing it cannot resurrect it.

use std::collections::HashMap;
use std::fmt;

use chrono::{DateTime, Utc};
use tokio::sync::{Mutex as AsyncMutex, MutexGuard};

#[derive(Clone)]
pub struct Pending {
    pub login: String,
    pub password: String,
    pub expires_at: DateTime<Utc>,
    pub member_id: i64,
    pub generation: u64,
}

impl fmt::Debug for Pending {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Pending")
            .field("member_id", &self.member_id)
            .field("expires_at", &self.expires_at)
            .finish_non_exhaustive()
    }
}

#[derive(Default)]
struct Inner {
    pending: HashMap<String, Pending>,
    generations: HashMap<i64, u64>,
}

#[derive(Default)]
pub struct PendingLogins {
    inner: std::sync::Mutex<Inner>,
    /// sessionWriteLock: serializes a session write with a disconnect of the same bank.
    write: AsyncMutex<()>,
}

impl PendingLogins {
    fn inner(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    pub async fn write_lock(&self) -> MutexGuard<'_, ()> {
        self.write.lock().await
    }

    pub fn generation(&self, member_id: i64) -> u64 {
        self.inner()
            .generations
            .get(&member_id)
            .copied()
            .unwrap_or(0)
    }

    pub fn evict_expired(&self, now: DateTime<Utc>) {
        self.inner().pending.retain(|_, p| p.expires_at >= now);
    }

    pub fn put(&self, process_id: String, pending: Pending) {
        self.inner().pending.insert(process_id, pending);
    }

    pub fn get(&self, process_id: &str) -> Option<Pending> {
        self.inner().pending.get(process_id).cloned()
    }

    pub fn remove(&self, process_id: &str) {
        self.inner().pending.remove(process_id);
    }

    /// A disconnect: bumps the generation and drops the member's pending logins.
    pub fn forget_member(&self, member_id: i64) {
        let mut inner = self.inner();
        *inner.generations.entry(member_id).or_insert(0) += 1;
        inner.pending.retain(|_, p| p.member_id != member_id);
    }
}
