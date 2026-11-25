//! SyncProgressService for Revolut: the live progress of a member's background discovery, and the
//! harvested accounts and credentials held in memory between the discovery and its confirm. Not
//! durable across a restart by design: a confirm with nothing pending fails clearly.

use std::collections::HashMap;
use std::fmt;
use std::sync::{Mutex, MutexGuard, PoisonError};

use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use serde::Serialize;

use super::adapter::RevolutAccountData;

/// DiscoveredRevolutAccount: one harvested wallet, pocket or vault offered for selection.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveredRevolutAccount {
    pub external_id: Option<String>,
    pub name: Option<String>,
    #[serde(rename = "type")]
    pub account_type: String,
    pub currency: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub balance: Decimal,
    pub parent_external_id: Option<String>,
    pub already_imported: bool,
    pub transaction_count: usize,
}

/// SyncProgress: the snapshot GET /api/revolut/sync/progress answers.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncProgress {
    pub running: bool,
    pub phase: Option<String>,
    pub elapsed_seconds: Option<i64>,
    pub remaining_seconds: Option<i32>,
    pub accounts_found: Option<i32>,
    pub done: bool,
    pub error: Option<String>,
    pub error_code: Option<String>,
    pub discovered: Vec<DiscoveredRevolutAccount>,
}

/// The phone number and passcode of a discovery, kept for the confirm's remember opt-in.
#[derive(Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Credentials {
    pub phone: String,
    pub passcode: String,
}

impl fmt::Debug for Credentials {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Credentials(..)")
    }
}

struct Job {
    running: bool,
    done: bool,
    error: Option<String>,
    error_code: Option<String>,
    phase: Option<String>,
    remaining_seconds: Option<i32>,
    accounts_found: Option<i32>,
    /// The elapsed seconds the sidecar last reported; until then, the time since started_at.
    elapsed_override: Option<i32>,
    started_at: DateTime<Utc>,
    pending_discovery: Option<Vec<RevolutAccountData>>,
    preview: Option<Vec<DiscoveredRevolutAccount>>,
}

impl Job {
    fn new(started_at: DateTime<Utc>) -> Self {
        Self {
            running: true,
            done: false,
            error: None,
            error_code: None,
            phase: None,
            remaining_seconds: None,
            accounts_found: None,
            elapsed_override: None,
            started_at,
            pending_discovery: None,
            preview: None,
        }
    }

    fn snapshot(&self, now: DateTime<Utc>) -> SyncProgress {
        let elapsed = self
            .elapsed_override
            .map_or_else(|| (now - self.started_at).num_seconds(), i64::from);
        SyncProgress {
            running: self.running,
            phase: self.phase.clone(),
            elapsed_seconds: Some(elapsed),
            remaining_seconds: self.remaining_seconds,
            accounts_found: self.accounts_found,
            done: self.done,
            error: self.error.clone(),
            error_code: self.error_code.clone(),
            discovered: self.preview.clone().unwrap_or_default(),
        }
    }
}

/// A phase update from the sidecar; None leaves that field unchanged.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct PhaseUpdate {
    pub phase: String,
    pub remaining_seconds: Option<i32>,
    pub elapsed_seconds: Option<i32>,
    pub accounts_found: Option<i32>,
}

#[derive(Default)]
struct Inner {
    jobs: HashMap<i64, Job>,
    credentials: HashMap<i64, Credentials>,
}

#[derive(Default)]
pub struct RevolutProgress {
    inner: Mutex<Inner>,
}

impl RevolutProgress {
    fn inner(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Starts a job for the member unless one is running; true when this call should submit it.
    pub fn start_if_idle(&self, member_id: i64, now: DateTime<Utc>) -> bool {
        let mut inner = self.inner();
        if inner.jobs.get(&member_id).is_some_and(|job| job.running) {
            return false;
        }
        inner.jobs.insert(member_id, Job::new(now));
        true
    }

    /// The member's job, or the idle default when none was ever started.
    pub fn status(&self, member_id: i64, now: DateTime<Utc>) -> SyncProgress {
        self.inner().jobs.get(&member_id).map_or_else(
            || SyncProgress {
                running: false,
                phase: None,
                elapsed_seconds: None,
                remaining_seconds: None,
                accounts_found: None,
                done: false,
                error: None,
                error_code: None,
                discovered: Vec::new(),
            },
            |job| job.snapshot(now),
        )
    }

    pub fn phase(&self, member_id: i64, update: PhaseUpdate) {
        if let Some(job) = self.inner().jobs.get_mut(&member_id) {
            job.phase = Some(update.phase);
            if update.remaining_seconds.is_some() {
                job.remaining_seconds = update.remaining_seconds;
            }
            if update.elapsed_seconds.is_some() {
                job.elapsed_override = update.elapsed_seconds;
            }
            if update.accounts_found.is_some() {
                job.accounts_found = update.accounts_found;
            }
        }
    }

    /// A finished discovery: the preview the DTO shows, the raw accounts the confirm persists,
    /// and the credentials it may remember.
    pub fn discovered(
        &self,
        member_id: i64,
        preview: Vec<DiscoveredRevolutAccount>,
        raw: Vec<RevolutAccountData>,
        credentials: Credentials,
    ) {
        let mut inner = self.inner();
        inner.credentials.insert(member_id, credentials);
        if let Some(job) = inner.jobs.get_mut(&member_id) {
            job.accounts_found = i32::try_from(raw.len()).ok();
            job.preview = Some(preview);
            job.pending_discovery = Some(raw);
            job.done = true;
            job.running = false;
        }
    }

    /// Takes the pending discovery (empty when none) and the credentials captured with it.
    pub fn take_pending(&self, member_id: i64) -> (Vec<RevolutAccountData>, Option<Credentials>) {
        let mut inner = self.inner();
        let raw = inner
            .jobs
            .get_mut(&member_id)
            .and_then(|job| job.pending_discovery.take())
            .unwrap_or_default();
        let credentials = if raw.is_empty() {
            None
        } else {
            inner.credentials.remove(&member_id)
        };
        (raw, credentials)
    }

    /// `code` is the stable code the UI translates, None when the failure has none.
    pub fn error(&self, member_id: i64, message: &str, code: Option<&str>) {
        if let Some(job) = self.inner().jobs.get_mut(&member_id) {
            job.error = Some(message.to_owned());
            job.error_code = code.map(str::to_owned);
            job.done = true;
            job.running = false;
        }
    }
}
