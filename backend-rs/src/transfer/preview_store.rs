//! Bounded, owner-scoped storage for short-lived import previews (ImportPreviewStore).
//!
//! Two caps keep it bounded without letting one owner starve the others: per_scope unconsumed
//! previews per owner (the owner's oldest is evicted first) and capacity entries overall (the
//! oldest unconsumed preview of any owner is evicted). Consumed entries belong to a running
//! import and are never evicted, so restore always works after a rollback; only when the whole
//! store is consumed entries does put refuse, with the 429 of ImportPreviewCapacityException.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use chrono::{DateTime, Utc};
use http::StatusCode;

use crate::clock::AppClock;
use crate::error::AppError;

pub const CAPACITY_MESSAGE: &str =
    "Too many imports are in progress right now. Please try again in a few moments.";

/// One stored preview. Two entries are the same only when they come from the same put.
#[derive(Debug)]
pub struct Entry<T> {
    seq: u64,
    pub scope: i64,
    pub payload: Arc<T>,
    pub created_at: DateTime<Utc>,
}

impl<T> Clone for Entry<T> {
    fn clone(&self) -> Self {
        Self {
            seq: self.seq,
            scope: self.scope,
            payload: Arc::clone(&self.payload),
            created_at: self.created_at,
        }
    }
}

struct Inner<T> {
    next_seq: u64,
    entries: HashMap<String, Entry<T>>,
    consumed: HashMap<String, Entry<T>>,
}

pub struct PreviewStore<T> {
    clock: AppClock,
    ttl: chrono::Duration,
    capacity: usize,
    per_scope: usize,
    inner: Mutex<Inner<T>>,
}

impl<T> PreviewStore<T> {
    pub fn new(clock: AppClock, ttl: Duration, capacity: usize, per_scope: usize) -> Self {
        assert!(!ttl.is_zero(), "ttl must be positive");
        assert!(capacity >= 1, "capacity must be positive");
        assert!(
            (1..=capacity).contains(&per_scope),
            "perScope must be between 1 and capacity"
        );
        Self {
            clock,
            ttl: chrono::Duration::from_std(ttl).unwrap_or(chrono::Duration::MAX),
            capacity,
            per_scope,
            inner: Mutex::new(Inner {
                next_seq: 0,
                entries: HashMap::new(),
                consumed: HashMap::new(),
            }),
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner<T>> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn is_expired(&self, entry: &Entry<T>, now: DateTime<Utc>) -> bool {
        entry.created_at + self.ttl <= now
    }

    pub fn put(&self, scope: i64, payload: T) -> Result<String, AppError> {
        let now = self.clock.now();
        let mut inner = self.lock();
        Self::purge(&mut inner, |e| self.is_expired(e, now));
        let unconsumed = inner.entries.values().filter(|e| e.scope == scope).count();
        if unconsumed >= self.per_scope {
            Self::evict_oldest(&mut inner, Some(scope));
        }
        if inner.entries.len() + inner.consumed.len() >= self.capacity
            && !Self::evict_oldest(&mut inner, None)
        {
            tracing::warn!("Import preview store has no evictable slot left");
            return Err(AppError::status(
                StatusCode::TOO_MANY_REQUESTS,
                CAPACITY_MESSAGE,
            ));
        }
        let token = uuid::Uuid::new_v4().to_string();
        let seq = inner.next_seq;
        inner.next_seq += 1;
        inner.entries.insert(
            token.clone(),
            Entry {
                seq,
                scope,
                payload: Arc::new(payload),
                created_at: now,
            },
        );
        Ok(token)
    }

    /// The unconsumed, unexpired preview of this owner, if any.
    pub fn get(&self, token: &str, scope: i64) -> Option<Entry<T>> {
        let now = self.clock.now();
        let mut inner = self.lock();
        let entry = inner.entries.get(token)?.clone();
        if self.is_expired(&entry, now) {
            inner.entries.remove(token);
            return None;
        }
        (entry.scope == scope).then_some(entry)
    }

    /// Atomically removes a preview from the readable set, allowing only one consumer.
    pub fn consume(&self, token: &str, entry: &Entry<T>) -> bool {
        let now = self.clock.now();
        let mut inner = self.lock();
        if inner.entries.get(token).is_none_or(|e| e.seq != entry.seq) {
            return false;
        }
        let Some(taken) = inner.entries.remove(token) else {
            return false;
        };
        if self.is_expired(&taken, now) {
            return false;
        }
        inner.consumed.insert(token.to_owned(), taken);
        true
    }

    /// Drops the consumed entry and its payload after the owning operation succeeds.
    pub fn complete(&self, token: &str, entry: &Entry<T>) {
        let mut inner = self.lock();
        if inner
            .consumed
            .get(token)
            .is_some_and(|e| e.seq == entry.seq)
        {
            inner.consumed.remove(token);
        }
    }

    /// Restores a preview consumed by a failed transaction, if it has not expired.
    pub fn restore(&self, token: &str, entry: &Entry<T>) {
        let now = self.clock.now();
        let mut inner = self.lock();
        if inner.consumed.get(token).is_none_or(|e| e.seq != entry.seq) {
            return;
        }
        if let Some(taken) = inner.consumed.remove(token)
            && !self.is_expired(&taken, now)
        {
            inner.entries.insert(token.to_owned(), taken);
        }
    }

    pub fn purge_expired(&self) {
        let now = self.clock.now();
        let mut inner = self.lock();
        Self::purge(&mut inner, |e| self.is_expired(e, now));
    }

    fn purge(inner: &mut Inner<T>, expired: impl Fn(&Entry<T>) -> bool) {
        inner.entries.retain(|_, e| !expired(e));
        inner.consumed.retain(|_, e| !expired(e));
    }

    fn evict_oldest(inner: &mut Inner<T>, scope: Option<i64>) -> bool {
        let oldest = inner
            .entries
            .iter()
            .filter(|(_, e)| scope.is_none_or(|s| e.scope == s))
            .min_by_key(|(_, e)| (e.created_at, e.seq))
            .map(|(k, _)| k.clone());
        oldest.is_some_and(|k| inner.entries.remove(&k).is_some())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::FixedClock;

    fn store(capacity: usize, per_scope: usize) -> (Arc<FixedClock>, PreviewStore<&'static str>) {
        let fixed = Arc::new(FixedClock::new(
            DateTime::from_timestamp(1_700_000_000, 0).expect("a valid instant"),
        ));
        let clock = AppClock::new(fixed.clone(), chrono_tz::UTC);
        (
            fixed,
            PreviewStore::new(clock, Duration::from_secs(60), capacity, per_scope),
        )
    }

    #[test]
    fn get_is_owner_scoped_and_expires() {
        let (clock, store) = store(4, 4);
        let token = store.put(1, "a").unwrap();
        assert!(store.get(&token, 2).is_none());
        assert!(store.get(&token, 1).is_some());
        clock.advance(Duration::from_secs(60));
        assert!(store.get(&token, 1).is_none());
    }

    #[test]
    fn consume_once_then_restore_after_rollback() {
        let (_clock, store) = store(4, 4);
        let token = store.put(1, "a").unwrap();
        let entry = store.get(&token, 1).unwrap();
        assert!(store.consume(&token, &entry));
        assert!(!store.consume(&token, &entry));
        assert!(store.get(&token, 1).is_none());
        store.restore(&token, &entry);
        assert!(store.get(&token, 1).is_some());
    }

    #[test]
    fn evicts_the_owners_oldest_then_refuses_when_all_are_consumed() {
        let (clock, store) = store(2, 1);
        let first = store.put(1, "a").unwrap();
        clock.advance(Duration::from_secs(1));
        let second = store.put(1, "b").unwrap();
        assert!(store.get(&first, 1).is_none());
        let other = store.put(2, "c").unwrap();
        for (token, scope) in [(&second, 1), (&other, 2)] {
            let entry = store.get(token, scope).unwrap();
            assert!(store.consume(token, &entry));
        }
        assert!(store.put(3, "d").is_err());
    }
}
