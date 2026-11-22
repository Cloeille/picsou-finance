//! SidecarSyncTemplate: the initiate / queue / execute / commit cycle every sidecar bank shares.
//! A sync runs in three short transactions fenced on the session row (QUEUED, RUNNING, then the
//! commit or the failure); the sidecar call runs between them outside any transaction, and a
//! result whose session was disconnected or replaced meanwhile is discarded.

use std::fmt;
use std::sync::Arc;

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use async_trait::async_trait;
use chrono::{DateTime, Utc};

use super::error::{JobError, JobResult, SyncError};
use super::jobs::Pool;
use super::session::{self, NewSession, SidecarSession};
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::ledger::members;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode, SourceSyncResult, SyncStatus};
use crate::ledger::sync_values;

/// One queued import: the session row it is fenced to, its member, the decrypted session state,
/// and whether it fetches the provider's full history instead of the routine window.
#[derive(Clone)]
pub struct SyncJob {
    pub session_id: i64,
    pub member_id: i64,
    pub plain_state: String,
    pub history: bool,
}

impl fmt::Debug for SyncJob {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SyncJob")
            .field("session_id", &self.session_id)
            .field("member_id", &self.member_id)
            .field("history", &self.history)
            .finish_non_exhaustive()
    }
}

/// SidecarSyncStrategy: what differs between two banks once a job runs.
#[async_trait]
pub trait SyncStrategy: Send + Sync + 'static {
    /// The prepared data, built outside any transaction.
    type Prepared: Send + Sync + 'static;

    /// Calls the sidecar and prepares its answer; may be slow.
    async fn fetch(&self, state: &AppState, job: &SyncJob) -> JobResult<Self::Prepared>;

    /// Writes the prepared data, in the job's transaction, once the template checked the session
    /// is still the active running one and before it marks it successful.
    async fn commit(
        &self,
        state: &AppState,
        conn: &mut DbHandle<'_>,
        session: &mut SidecarSession,
        job: &SyncJob,
        prepared: &Self::Prepared,
        synced_at: DateTime<Utc>,
    ) -> JobResult<()>;

    /// Work after the commit that must not fail the import.
    async fn after_commit(&self, _state: &AppState, _job: &SyncJob, _prepared: &Self::Prepared) {}

    /// A short count for the completion log line, such as "accounts=3".
    fn describe(&self, _prepared: &Self::Prepared) -> String {
        String::new()
    }

    /// Runs before a failure is recorded on the session, inside the same transaction.
    async fn on_failure(
        &self,
        _conn: &mut DbHandle<'_>,
        _session: &mut SidecarSession,
        _code: SidecarErrorCode,
    ) -> JobResult<()> {
        Ok(())
    }

    /// Whether an unexpected failure is logged by type only, because its message may carry payload.
    fn redacts_unexpected_failures(&self) -> bool {
        false
    }
}

/// The facts the daily scheduler reads from a bank's status (SidecarSessionView).
pub trait SessionView {
    fn is_active(&self) -> bool;
    fn last_sync_error(&self) -> Option<SidecarErrorCode>;
}

pub struct SyncTemplate<S: SyncStrategy> {
    bank: SidecarBank,
    strategy: Arc<S>,
    /// Every write first locks the member row, so creation and deletion serialize even when no
    /// session row exists yet (CORUM, Sofidy).
    locks_member: bool,
    pool: Pool,
    /// The bank's name in the queue's own messages, when it differs from its label (Natixis).
    queue_label: &'static str,
}

impl<S: SyncStrategy> Clone for SyncTemplate<S> {
    fn clone(&self) -> Self {
        Self {
            bank: self.bank,
            strategy: self.strategy.clone(),
            locks_member: self.locks_member,
            pool: self.pool,
            queue_label: self.queue_label,
        }
    }
}

pub fn error(code: SidecarErrorCode, message: impl Into<String>) -> SyncError {
    SyncError::new(code, message)
}

impl<S: SyncStrategy> SyncTemplate<S> {
    pub fn new(bank: SidecarBank, strategy: S, locks_member: bool) -> Self {
        Self {
            bank,
            strategy: Arc::new(strategy),
            locks_member,
            pool: Pool::SINGLE_WORKER,
            queue_label: bank.label(),
        }
    }

    /// Names the bank `label` in the messages queue() answers with.
    pub fn with_queue_label(mut self, label: &'static str) -> Self {
        self.queue_label = label;
        self
    }

    /// Runs the jobs on a pool other than the single worker.
    pub fn with_pool(mut self, pool: Pool) -> Self {
        self.pool = pool;
        self
    }

    pub fn bank(&self) -> SidecarBank {
        self.bank
    }

    pub fn strategy(&self) -> &S {
        &self.strategy
    }

    pub async fn find(
        &self,
        state: &AppState,
        member_id: i64,
    ) -> AppResult<Option<SidecarSession>> {
        Ok(session::find(&state.db, self.bank, member_id).await?)
    }

    async fn lock_member_if_needed(
        &self,
        conn: &mut DbHandle<'_>,
        member_id: i64,
    ) -> AppResult<()> {
        if self.locks_member {
            members::lock_or_throw(&mut *conn, member_id).await?;
        }
        Ok(())
    }

    /// Queues an import of the stored session and returns the session as it stands; an import
    /// already in flight is left alone. An undecryptable session is recorded as failed for the
    /// banks that declare INVALID_SESSION_STATE.
    pub async fn queue(
        &self,
        state: &AppState,
        member_id: i64,
        history: bool,
    ) -> AppResult<SidecarSession> {
        let label = self.queue_label;
        let mut tx = state.db.begin().await?;
        self.lock_member_if_needed(&mut tx.as_handle(), member_id)
            .await?;
        let mut current = session::find_for_update(&mut tx, self.bank, member_id)
            .await?
            .ok_or_else(|| {
                error(
                    SidecarErrorCode::SessionExpired,
                    format!("No active {label} session. Please reconnect."),
                )
            })?;
        if !current.active {
            return Err(error(
                SidecarErrorCode::SessionExpired,
                format!("The {label} session expired. Please reconnect."),
            )
            .into());
        }
        if current.is_sync_in_flight() {
            return Ok(current);
        }
        let plain_state = match state.crypto.decrypt(&current.session_state) {
            Ok(plain) => plain,
            Err(e) => {
                if !self.bank.declares(SidecarErrorCode::InvalidSessionState) {
                    return Err(e.into());
                }
                let now = state.clock.now();
                current.mark_queued()?;
                current.mark_failed(SidecarErrorCode::InvalidSessionState, now)?;
                session::save(&mut tx, &current, now).await?;
                tx.commit().await?;
                tracing::warn!(
                    "Stored {label} session is unreadable (member={member_id}; type=CryptoError)"
                );
                return Err(error(
                    SidecarErrorCode::InvalidSessionState,
                    format!("The stored {label} session is unusable. Please reconnect."),
                )
                .into());
            }
        };
        let now = state.clock.now();
        current.mark_queued()?;
        current.updated_at = now;
        session::save(&mut tx, &current, now).await?;
        tx.commit().await?;
        self.submit(
            state,
            SyncJob {
                session_id: current.id,
                member_id,
                plain_state,
                history,
            },
        )?;
        Ok(current)
    }

    /// Replaces the member's session with a freshly authenticated one and queues its first import;
    /// `prepare` sets the bank-specific columns before the row is written. Returns the queued
    /// session captured before job submission so callers never race with `mark_running`.
    pub async fn replace_and_queue(
        &self,
        state: &AppState,
        member_id: i64,
        plain_state: String,
        history: bool,
        prepare: impl FnOnce(&mut NewSession) -> AppResult<()>,
    ) -> AppResult<SidecarSession> {
        let mut tx = state.db.begin().await?;
        let job = self
            .insert_queued(
                state,
                &mut tx.as_handle(),
                member_id,
                plain_state,
                history,
                prepare,
            )
            .await?;
        let stored = session::find(&mut tx, self.bank, member_id)
            .await?
            .ok_or_else(|| AppError::internal("queued sidecar session missing after insert"))?;
        tx.commit().await?;
        self.submit(state, job)?;
        Ok(stored)
    }

    /// As replace_and_queue, inside the caller's transaction; the caller submits the job once it
    /// commits.
    pub async fn insert_queued(
        &self,
        state: &AppState,
        conn: &mut DbHandle<'_>,
        member_id: i64,
        plain_state: String,
        history: bool,
        prepare: impl FnOnce(&mut NewSession) -> AppResult<()>,
    ) -> AppResult<SyncJob> {
        if self.locks_member {
            members::lock_or_throw(&mut *conn, member_id).await?;
        } else if members::find_by_id(&mut *conn, member_id).await?.is_none() {
            return Err(AppError::NotFound("Family member not found".to_owned()));
        }
        if let Some(existing) = session::find_for_update(&mut *conn, self.bank, member_id).await? {
            session::delete(&mut *conn, existing.id).await?;
        }
        let now = state.clock.now();
        let mut draft = NewSession {
            bank: self.bank,
            member_id,
            session_state: state.crypto.encrypt(&plain_state)?,
            encrypted_credentials: None,
            unsupported_contracts: None,
            region: None,
            validated_at: now,
        };
        prepare(&mut draft)?;
        let mut stored = session::insert(&mut *conn, &draft, now).await?;
        stored.mark_queued()?;
        session::save(&mut *conn, &stored, now).await?;
        Ok(SyncJob {
            session_id: stored.id,
            member_id,
            plain_state,
            history,
        })
    }

    /// Whether a stored session was there to delete.
    pub async fn clear(&self, state: &AppState, member_id: i64) -> AppResult<bool> {
        let mut tx = state.db.begin().await?;
        self.lock_member_if_needed(&mut tx.as_handle(), member_id)
            .await?;
        let existing = session::find_for_update(&mut tx, self.bank, member_id).await?;
        if let Some(existing) = &existing {
            session::delete(&mut tx, existing.id).await?;
        }
        tx.commit().await?;
        Ok(existing.is_some())
    }

    /// Browser-backed jobs cannot survive a restart: persisted QUEUED/RUNNING states become a
    /// retryable failure instead of leaving the UI polling forever.
    pub async fn recover_interrupted(&self, state: &AppState) -> sqlx::Result<u64> {
        let recovered =
            session::mark_interrupted_failed(&state.db, self.bank, state.clock.now()).await?;
        if recovered > 0 {
            tracing::warn!(
                "Recovered {recovered} interrupted {} sync job(s)",
                self.bank.label()
            );
        }
        Ok(recovered)
    }

    /// The daily scheduler's entry: queues an import when the session can run, and says why not.
    pub async fn resync_reporting<V, FQ, Q>(
        &self,
        member_id: i64,
        source: &str,
        status: impl Future<Output = AppResult<V>>,
        queue: FQ,
    ) -> SourceSyncResult
    where
        V: SessionView,
        FQ: FnOnce() -> Q,
        Q: Future<Output = AppResult<()>>,
    {
        let label = self.bank.label();
        let result = async {
            let current = status.await?;
            if !current.is_active() {
                if let Some(error) = current.last_sync_error() {
                    let outcome = if needs_reauth(source, error) {
                        SyncStatus::NeedsReauth
                    } else {
                        SyncStatus::Failed
                    };
                    return Ok(result_of(source, outcome, error.as_str()));
                }
                return Ok(result_of(
                    source,
                    SyncStatus::SkippedNotConnected,
                    "No active session",
                ));
            }
            queue().await?;
            Ok::<_, AppError>(result_of(source, SyncStatus::Queued, ""))
        }
        .await;
        match result {
            Ok(outcome) => outcome,
            Err(AppError::NotFound(_)) => {
                result_of(source, SyncStatus::SkippedNotConnected, "Member not found")
            }
            Err(AppError::Coded { code, .. }) => {
                tracing::warn!(
                    "Could not queue scheduled {label} sync (member={member_id}; code={code})"
                );
                let classified = SourceSyncResult::from_sync_exception(source, Some(&code), None);
                let message = if classified.status == SyncStatus::NeedsReauth {
                    code
                } else {
                    "Sync failed".to_owned()
                };
                SourceSyncResult {
                    message,
                    ..classified
                }
            }
            Err(AppError::Status { status, .. })
                if status == http::StatusCode::UNPROCESSABLE_ENTITY =>
            {
                result_of(source, SyncStatus::Failed, "Sync failed")
            }
            Err(AppError::Internal(detail)) if detail.starts_with("database") => {
                tracing::error!(
                    "Database error during scheduled {label} sync (member={member_id})"
                );
                result_of(source, SyncStatus::Failed, "Database error")
            }
            Err(e) => {
                tracing::error!(
                    "{label} scheduled sync failed unexpectedly for member {member_id}: {e}"
                );
                result_of(source, SyncStatus::Failed, "Unexpected sync error")
            }
        }
    }

    /// Hands a queued job to the bank's executor; a job it refuses is recorded as failed.
    pub fn submit(&self, state: &AppState, job: SyncJob) -> AppResult<()> {
        let template = self.clone();
        let task_state = state.clone();
        let task_job = job.clone();
        let submitted = state
            .connectors
            .jobs
            .submit(self.bank.as_str(), self.pool, async move {
                template.execute(&task_state, task_job).await;
            });
        if submitted.is_err() {
            tracing::warn!(
                "{} sync executor refused a job (member={})",
                self.bank.label(),
                job.member_id
            );
            let template = self.clone();
            let state = state.clone();
            tokio::spawn(async move {
                template
                    .mark_failed(&state, &job, SidecarErrorCode::InternalError)
                    .await
            });
            return Err(error(
                SidecarErrorCode::InternalError,
                format!(
                    "Could not schedule the {} synchronization",
                    self.bank.label()
                ),
            )
            .into());
        }
        Ok(())
    }

    async fn execute(&self, state: &AppState, job: SyncJob) {
        let label = self.bank.label();
        match self.mark_running(state, &job).await {
            Ok(true) => {}
            Ok(false) => return,
            Err(e) => {
                tracing::error!(
                    "{label} sync could not start (member={}): {e}",
                    job.member_id
                );
                return;
            }
        }
        let outcome = async {
            let prepared = self.strategy.fetch(state, &job).await?;
            if self.commit(state, &job, &prepared).await? {
                tracing::info!(
                    "{label} sync completed (member={}; {})",
                    job.member_id,
                    self.strategy.describe(&prepared)
                );
                self.strategy.after_commit(state, &job, &prepared).await;
                crate::ledger::transfers::after_import(
                    &state.db,
                    &*state.ledger.dividends,
                    job.member_id,
                    state.clock.today(),
                    state.clock.now(),
                )
                .await;
            } else {
                tracing::info!(
                    "Discarded stale {label} sync result (member={})",
                    job.member_id
                );
            }
            Ok::<_, JobError>(())
        }
        .await;
        match outcome {
            Ok(()) => {}
            Err(JobError::Sync(sync)) => {
                let code = self.code_of(&sync);
                self.mark_failed(state, &job, code).await;
                tracing::warn!(
                    "{label} sync failed (member={}; code={code})",
                    job.member_id
                );
            }
            Err(other) => {
                self.mark_failed(state, &job, SidecarErrorCode::InternalError)
                    .await;
                if self.strategy.redacts_unexpected_failures() {
                    tracing::error!(
                        "{label} sync failed unexpectedly (member={}; type={})",
                        job.member_id,
                        kind(&other)
                    );
                } else {
                    tracing::error!(
                        "{label} sync failed unexpectedly (member={}): {other}",
                        job.member_id
                    );
                }
            }
        }
    }

    async fn mark_running(&self, state: &AppState, job: &SyncJob) -> AppResult<bool> {
        let label = self.bank.label();
        let mut tx = state.db.begin().await?;
        self.lock_member_if_needed(&mut tx.as_handle(), job.member_id)
            .await?;
        let Some(mut current) =
            session::find_by_id_for_update(&mut tx, job.session_id, job.member_id).await?
        else {
            tracing::info!(
                "{label} sync session disappeared before execution (member={})",
                job.member_id
            );
            return Ok(false);
        };
        if !current.active || !current.is_queued() {
            tracing::warn!(
                "{label} sync cannot start from state {} (member={}; active={})",
                current.sync_status,
                job.member_id,
                current.active
            );
            return Ok(false);
        }
        let now = state.clock.now();
        current.mark_running(now)?;
        session::save(&mut tx, &current, now).await?;
        tx.commit().await?;
        Ok(true)
    }

    async fn commit(
        &self,
        state: &AppState,
        job: &SyncJob,
        prepared: &S::Prepared,
    ) -> JobResult<bool> {
        let label = self.bank.label();
        let mut tx = state.db.begin().await?;
        self.lock_member_if_needed(&mut tx.as_handle(), job.member_id)
            .await?;
        let Some(mut current) =
            session::find_by_id_for_update(&mut tx, job.session_id, job.member_id).await?
        else {
            tracing::info!(
                "{label} sync session disappeared before commit (member={})",
                job.member_id
            );
            return Ok(false);
        };
        if !current.active || !current.is_running() {
            tracing::warn!(
                "{label} sync cannot commit from state {} (member={}; active={})",
                current.sync_status,
                job.member_id,
                current.active
            );
            return Ok(false);
        }
        let synced_at = state.clock.now();
        self.strategy
            .commit(
                state,
                &mut tx.as_handle(),
                &mut current,
                job,
                prepared,
                synced_at,
            )
            .await?;
        current.mark_successful(synced_at)?;
        session::save(&mut tx, &current, synced_at).await?;
        tx.commit().await?;
        Ok(true)
    }

    /// Records a failure on the job's own session, unless it was replaced or finished meanwhile.
    pub async fn mark_failed(&self, state: &AppState, job: &SyncJob, code: SidecarErrorCode) {
        let label = self.bank.label();
        let outcome = async {
            let mut tx = state.db.begin().await?;
            self.lock_member_if_needed(&mut tx.as_handle(), job.member_id)
                .await?;
            let Some(mut current) =
                session::find_by_id_for_update(&mut tx, job.session_id, job.member_id).await?
            else {
                tracing::info!(
                    "{label} sync session disappeared before failure was recorded (member={})",
                    job.member_id
                );
                return Ok(());
            };
            if !current.is_sync_in_flight() {
                tracing::warn!(
                    "{label} sync failure ignored from state {} (member={}; code={code})",
                    current.sync_status,
                    job.member_id
                );
                return Ok(());
            }
            self.strategy
                .on_failure(&mut tx.as_handle(), &mut current, code)
                .await?;
            let now = state.clock.now();
            current.mark_failed(code, now)?;
            session::save(&mut tx, &current, now).await?;
            tx.commit().await?;
            Ok::<_, JobError>(())
        }
        .await;
        if let Err(e) = outcome {
            if self.strategy.redacts_unexpected_failures() {
                tracing::error!(
                    "Could not persist {label} sync failure (member={}; code={code}; type={})",
                    job.member_id,
                    kind(&e)
                );
            } else {
                tracing::error!(
                    "Could not persist {label} sync failure (member={}; code={code}): {e}",
                    job.member_id
                );
            }
        }
    }

    /// The bank's code for a SyncException, UPSTREAM_UNAVAILABLE when it names none of them.
    pub fn code_of(&self, error: &SyncError) -> SidecarErrorCode {
        sync_values::error_code(
            error.code(),
            self.bank,
            SidecarErrorCode::UpstreamUnavailable,
        )
    }
}

fn kind(error: &JobError) -> &'static str {
    match error {
        JobError::Sync(_) => "SyncException",
        JobError::Db(_) => "DataAccessException",
        JobError::App(_) => "RuntimeException",
    }
}

fn needs_reauth(source: &str, error: SidecarErrorCode) -> bool {
    SourceSyncResult::from_sync_exception(source, Some(error.as_str()), Some(error.as_str())).status
        == SyncStatus::NeedsReauth
}

fn result_of(source: &str, status: SyncStatus, message: &str) -> SourceSyncResult {
    SourceSyncResult {
        source: source.to_owned(),
        status,
        message: message.to_owned(),
    }
}
