//! Pending login attempts parked between `/initiate` and `/complete`.
//!
//! Mirrors `services/base/picsou_sidecar/pending.py`:
//! - Per-attempt isolated `reqwest::Client` (own cookie jar)
//! - TTL eviction (600 s default) using an injectable `Clock`
//! - Capacity limit (`MAX_PENDING = 16` default); reclaims expired entries when full
//! - Reparking on wrong OTP up to `MAX_CODE_ATTEMPTS`

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use chrono::{DateTime, Utc};
use http::StatusCode;

use crate::clock::Clock;
use crate::native_sidecars::NativeError;

/// Completed `/initiate` state waiting for `/complete`.
#[derive(Debug, Clone)]
pub(crate) struct LoginState {
    pub verifier: String,
    pub oauth_state: String,
    pub csrf: String,
    pub device_id: String,
    pub verification_url: String,
    pub code_attempts: usize,
}

/// One parked login attempt, either still initiating (`ready = false`) or waiting for an OTP.
pub(crate) struct PendingEntry {
    pub client: reqwest::Client,
    pub created_at: DateTime<Utc>,
    pub ready: bool,
    pub state: Option<LoginState>,
}

impl std::fmt::Debug for PendingEntry {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PendingEntry")
            .field("created_at", &self.created_at)
            .field("ready", &self.ready)
            .finish()
    }
}

impl PendingEntry {
    pub fn initiating(client: reqwest::Client, created_at: DateTime<Utc>) -> Self {
        Self {
            client,
            created_at,
            ready: false,
            state: None,
        }
    }
}

#[derive(Debug)]
pub(crate) struct PendingStore {
    ttl: Duration,
    max_size: usize,
    clock: Arc<dyn Clock>,
    entries: Mutex<HashMap<String, PendingEntry>>,
}

impl PendingStore {
    pub fn new(ttl: Duration, max_size: usize, clock: Arc<dyn Clock>) -> Self {
        Self {
            ttl,
            max_size,
            clock,
            entries: Mutex::new(HashMap::new()),
        }
    }

    fn cutoff(&self) -> DateTime<Utc> {
        let ttl = chrono::Duration::from_std(self.ttl).unwrap_or(chrono::Duration::MAX);
        self.clock.now() - ttl
    }

    fn sweep_locked(entries: &mut HashMap<String, PendingEntry>, cutoff: DateTime<Utc>) {
        entries.retain(|_, entry| entry.created_at >= cutoff);
    }

    /// Park immediately if there is room, reclaiming expired entries only when full.
    pub fn put(&self, process_id: String, entry: PendingEntry) -> bool {
        let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        if entries.len() < self.max_size {
            entries.insert(process_id, entry);
            return true;
        }
        let cutoff = self.cutoff();
        Self::sweep_locked(&mut entries, cutoff);
        if entries.len() >= self.max_size {
            return false;
        }
        entries.insert(process_id, entry);
        true
    }

    /// Park a login or answer `503 UPSTREAM_UNAVAILABLE`.
    pub fn put_or_raise(&self, process_id: String, entry: PendingEntry) -> Result<(), NativeError> {
        if self.put(process_id, entry) {
            Ok(())
        } else {
            Err(NativeError::new(
                StatusCode::SERVICE_UNAVAILABLE,
                "UPSTREAM_UNAVAILABLE",
            ))
        }
    }

    /// Re-park a retry; if parking fails, the entry is dropped immediately.
    pub fn repark(&self, process_id: String, entry: PendingEntry) -> Result<(), NativeError> {
        self.put_or_raise(process_id, entry)
    }

    /// Mark an initiating slot as ready once `/initiate` finishes sending the code.
    pub fn mark_ready(&self, process_id: &str, state: LoginState) -> bool {
        let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = entries.get_mut(process_id) {
            entry.state = Some(state);
            entry.ready = true;
            true
        } else {
            false
        }
    }

    /// Transfer a live entry to its caller, dropping an expired one instead of returning it.
    pub fn take(&self, process_id: &str) -> Option<PendingEntry> {
        let entry = {
            let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
            entries.remove(process_id)
        };
        let entry = entry?;
        if entry.created_at < self.cutoff() {
            return None;
        }
        Some(entry)
    }

    /// Drop a pending entry, freeing its slot at once. Idempotent.
    pub fn close(&self, process_id: &str) {
        let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        entries.remove(process_id);
    }

    /// Reclaim all expired entries.
    pub fn expire(&self) {
        let cutoff = self.cutoff();
        let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        Self::sweep_locked(&mut entries, cutoff);
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.entries.lock().unwrap_or_else(|e| e.into_inner()).len()
    }
}
