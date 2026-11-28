//! SidecarSession and its repository: one encrypted sidecar session per member and bank in
//! `sidecar_session`, with the state of its last sync.

use std::fmt;
use std::str::FromStr;

use chrono::{DateTime, Utc};
use serde::Serialize;

use crate::db::{self, DbExec, DbFromRow};
use crate::error::{AppError, AppResult};
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode};

/// SidecarSyncStatus: the lifecycle of one sync, stored in `sync_status`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SidecarSyncStatus {
    Idle,
    Queued,
    Running,
    Success,
    Failed,
}

impl SidecarSyncStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Idle => "IDLE",
            Self::Queued => "QUEUED",
            Self::Running => "RUNNING",
            Self::Success => "SUCCESS",
            Self::Failed => "FAILED",
        }
    }
}

impl fmt::Display for SidecarSyncStatus {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl FromStr for SidecarSyncStatus {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        Ok(match value {
            "IDLE" => Self::Idle,
            "QUEUED" => Self::Queued,
            "RUNNING" => Self::Running,
            "SUCCESS" => Self::Success,
            "FAILED" => Self::Failed,
            other => return Err(format!("unknown sync status {other}")),
        })
    }
}

#[derive(DbFromRow)]
struct Row {
    id: i64,
    bank: String,
    member_id: i64,
    session_state: String,
    encrypted_credentials: Option<String>,
    unsupported_contracts: Option<String>,
    skipped_accounts: Option<String>,
    region: Option<String>,
    last_validated_at: Option<DateTime<Utc>>,
    is_active: bool,
    sync_status: String,
    last_sync_started_at: Option<DateTime<Utc>>,
    last_sync_completed_at: Option<DateTime<Utc>>,
    last_sync_error: Option<String>,
    last_sync_life_ambiguous: i32,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

/// One row of `sidecar_session`. The state machine methods are SidecarSession's; a refused
/// transition is an internal error, as Java's IllegalStateException.
#[derive(Clone, PartialEq)]
pub struct SidecarSession {
    pub id: i64,
    pub bank: SidecarBank,
    pub member_id: i64,
    /// Encrypted with CryptoEncryption; never exposed.
    pub session_state: String,
    pub encrypted_credentials: Option<String>,
    pub unsupported_contracts: Option<String>,
    /// BoursoBank only: what the last successful sync listed but did not import or refresh.
    pub skipped_accounts: Option<String>,
    /// Banque Populaire only: the regional bank's enum name.
    pub region: Option<String>,
    pub last_validated_at: Option<DateTime<Utc>>,
    pub active: bool,
    pub sync_status: SidecarSyncStatus,
    pub last_sync_started_at: Option<DateTime<Utc>>,
    pub last_sync_completed_at: Option<DateTime<Utc>>,
    pub last_sync_error: Option<SidecarErrorCode>,
    pub last_sync_life_ambiguous: i32,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl fmt::Debug for SidecarSession {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SidecarSession")
            .field("id", &self.id)
            .field("bank", &self.bank)
            .field("member_id", &self.member_id)
            .field("active", &self.active)
            .field("sync_status", &self.sync_status)
            .field("last_sync_error", &self.last_sync_error)
            .finish_non_exhaustive()
    }
}

impl TryFrom<Row> for SidecarSession {
    type Error = sqlx::Error;

    fn try_from(row: Row) -> Result<Self, Self::Error> {
        let decode = |e: String| sqlx::Error::Decode(e.into());
        Ok(Self {
            id: row.id,
            bank: row
                .bank
                .parse()
                .map_err(|_| decode(format!("unknown bank {}", row.bank)))?,
            member_id: row.member_id,
            session_state: row.session_state,
            encrypted_credentials: row.encrypted_credentials,
            unsupported_contracts: row.unsupported_contracts,
            skipped_accounts: row.skipped_accounts,
            region: row.region,
            last_validated_at: row.last_validated_at,
            active: row.is_active,
            sync_status: row.sync_status.parse().map_err(decode)?,
            last_sync_started_at: row.last_sync_started_at,
            last_sync_completed_at: row.last_sync_completed_at,
            last_sync_error: row
                .last_sync_error
                .map(|code| {
                    code.parse()
                        .map_err(|_| decode(format!("unknown error code {code}")))
                })
                .transpose()?,
            last_sync_life_ambiguous: row.last_sync_life_ambiguous,
            created_at: row.created_at,
            updated_at: row.updated_at,
        })
    }
}

fn refused(message: String) -> AppError {
    AppError::internal(message)
}

impl SidecarSession {
    pub fn is_queued(&self) -> bool {
        self.sync_status == SidecarSyncStatus::Queued
    }

    pub fn is_running(&self) -> bool {
        self.sync_status == SidecarSyncStatus::Running
    }

    pub fn is_sync_in_flight(&self) -> bool {
        matches!(
            self.sync_status,
            SidecarSyncStatus::Queued | SidecarSyncStatus::Running
        )
    }

    pub fn update_session_state(&mut self, encrypted_state: String, validated_at: DateTime<Utc>) {
        self.session_state = encrypted_state;
        self.last_validated_at = Some(validated_at);
        self.active = true;
    }

    /// CORUM: how many life contracts the sync being committed left unwritten.
    pub fn record_life_ambiguous(&mut self, count: i32) -> AppResult<()> {
        if self.bank != SidecarBank::Corum {
            return Err(refused(
                "Only a CORUM session counts life contracts".to_owned(),
            ));
        }
        if count < 0 {
            return Err(refused("lifeAmbiguous must not be negative".to_owned()));
        }
        self.last_sync_life_ambiguous = count;
        Ok(())
    }

    /// Queues a sync, clearing the previous run's timestamps, error and CORUM Life count.
    pub fn mark_queued(&mut self) -> AppResult<()> {
        let label = self.bank.label();
        if !self.active {
            return Err(refused(format!(
                "Inactive {label} sessions cannot be queued"
            )));
        }
        if self.is_sync_in_flight() {
            return Err(refused(format!(
                "{label} synchronization is already in progress"
            )));
        }
        self.sync_status = SidecarSyncStatus::Queued;
        self.last_sync_started_at = None;
        self.last_sync_completed_at = None;
        self.last_sync_error = None;
        self.last_sync_life_ambiguous = 0;
        Ok(())
    }

    pub fn mark_running(&mut self, started_at: DateTime<Utc>) -> AppResult<()> {
        if !self.active || self.sync_status != SidecarSyncStatus::Queued {
            return Err(refused(format!(
                "Only an active queued {} session can run",
                self.bank.label()
            )));
        }
        self.sync_status = SidecarSyncStatus::Running;
        self.last_sync_started_at = Some(started_at);
        self.last_sync_completed_at = None;
        self.last_sync_error = None;
        Ok(())
    }

    pub fn mark_successful(&mut self, completed_at: DateTime<Utc>) -> AppResult<()> {
        if !self.active || self.sync_status != SidecarSyncStatus::Running {
            return Err(refused(format!(
                "Only an active running {} session can succeed",
                self.bank.label()
            )));
        }
        self.sync_status = SidecarSyncStatus::Success;
        self.last_validated_at = Some(completed_at);
        self.last_sync_completed_at = Some(completed_at);
        self.last_sync_error = None;
        Ok(())
    }

    pub fn mark_failed(
        &mut self,
        code: SidecarErrorCode,
        completed_at: DateTime<Utc>,
    ) -> AppResult<()> {
        if !self.is_sync_in_flight() {
            return Err(refused(format!(
                "Only an in-flight {} synchronization can fail",
                self.bank.label()
            )));
        }
        let code = self
            .bank
            .require_declared(code)
            .map_err(|e| refused(e.to_string()))?;
        self.sync_status = SidecarSyncStatus::Failed;
        self.last_sync_completed_at = Some(completed_at);
        self.last_sync_error = Some(code);
        if self.ends_session(code) {
            self.active = false;
        }
        Ok(())
    }

    /// CIC: a rejected stored password must not be replayed, the site locks the access.
    pub fn reject_credentials(&mut self, completed_at: DateTime<Utc>) {
        self.encrypted_credentials = None;
        self.active = false;
        self.sync_status = SidecarSyncStatus::Failed;
        self.last_sync_completed_at = Some(completed_at);
        self.last_sync_error = Some(SidecarErrorCode::InvalidCredentials);
    }

    /// Whether a failure leaves the session unusable, so only a fresh login can replace it.
    fn ends_session(&self, error: SidecarErrorCode) -> bool {
        use SidecarErrorCode::{InvalidCredentials, InvalidSessionState, SessionExpired};
        match self.bank {
            SidecarBank::CaisseEpargne
            | SidecarBank::BanquePopulaire
            | SidecarBank::Bnp
            | SidecarBank::Lbp
            | SidecarBank::TradeRepublic => matches!(error, SessionExpired | InvalidSessionState),
            SidecarBank::Natixis | SidecarBank::Cic | SidecarBank::Cmes | SidecarBank::Corum => {
                matches!(error, SessionExpired | InvalidCredentials)
            }
            SidecarBank::Amex
            | SidecarBank::Amundi
            | SidecarBank::BourseDirect
            | SidecarBank::Bourso
            | SidecarBank::Fortuneo
            | SidecarBank::Sofidy => error == SessionExpired,
        }
    }
}

/// The columns of a new session (SidecarSession.create), set by the caller's `prepare`.
#[derive(Clone)]
pub struct NewSession {
    pub bank: SidecarBank,
    pub member_id: i64,
    pub session_state: String,
    pub encrypted_credentials: Option<String>,
    pub unsupported_contracts: Option<String>,
    pub region: Option<String>,
    pub validated_at: DateTime<Utc>,
}

const COLUMNS: &str = "id, bank, member_id, session_state, encrypted_credentials, unsupported_contracts, skipped_accounts, region, \
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error, \
    last_sync_life_ambiguous, created_at, updated_at";

fn decode(row: Option<Row>) -> sqlx::Result<Option<SidecarSession>> {
    row.map(SidecarSession::try_from).transpose()
}

pub async fn find<'e>(
    db: impl DbExec<'e>,
    bank: SidecarBank,
    member_id: i64,
) -> sqlx::Result<Option<SidecarSession>> {
    let sql = format!("SELECT {COLUMNS} FROM sidecar_session WHERE bank = $1 AND member_id = $2");
    decode(
        db::query_as(&sql)
            .bind(bank.as_str())
            .bind(member_id)
            .fetch_optional(db)
            .await?,
    )
}

/// findByBankAndMemberIdForUpdate.
pub async fn find_for_update<'e>(
    db: impl DbExec<'e>,
    bank: SidecarBank,
    member_id: i64,
) -> sqlx::Result<Option<SidecarSession>> {
    let sql = format!(
        "SELECT {COLUMNS} FROM sidecar_session WHERE bank = $1 AND member_id = $2 FOR UPDATE"
    );
    decode(
        db::query_as(&sql)
            .bind(bank.as_str())
            .bind(member_id)
            .fetch_optional(db)
            .await?,
    )
}

/// findByIdAndMemberIdForUpdate: the row a job is fenced to.
pub async fn find_by_id_for_update<'e>(
    db: impl DbExec<'e>,
    id: i64,
    member_id: i64,
) -> sqlx::Result<Option<SidecarSession>> {
    let sql = format!(
        "SELECT {COLUMNS} FROM sidecar_session WHERE id = $1 AND member_id = $2 FOR UPDATE"
    );
    decode(
        db::query_as(&sql)
            .bind(id)
            .bind(member_id)
            .fetch_optional(db)
            .await?,
    )
}

pub async fn insert<'e>(
    db: impl DbExec<'e>,
    session: &NewSession,
    now: DateTime<Utc>,
) -> sqlx::Result<SidecarSession> {
    let sql = format!(
        "INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, unsupported_contracts, \
         region, last_validated_at, is_active, sync_status, last_sync_life_ambiguous, created_at, updated_at) \
         VALUES ($1, $2, $3, $4, $5, $6, $7, true, 'IDLE', 0, $8, $8) RETURNING {COLUMNS}"
    );
    let row: Row = db::query_as(&sql)
        .bind(session.bank.as_str())
        .bind(session.member_id)
        .bind(&session.session_state)
        .bind(&session.encrypted_credentials)
        .bind(&session.unsupported_contracts)
        .bind(&session.region)
        .bind(session.validated_at)
        .bind(now)
        .fetch_one(db)
        .await?;
    row.try_into()
}

/// Writes every mutable column back (JPA save), stamping updated_at.
pub async fn save<'e>(
    db: impl DbExec<'e>,
    session: &SidecarSession,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query(
        "UPDATE sidecar_session SET session_state = $2, encrypted_credentials = $3, unsupported_contracts = $4, \
         last_validated_at = $5, is_active = $6, sync_status = $7, last_sync_started_at = $8, \
         last_sync_completed_at = $9, last_sync_error = $10, last_sync_life_ambiguous = $11, updated_at = $12, \
         skipped_accounts = $13 WHERE id = $1",
    )
    .bind(session.id)
    .bind(&session.session_state)
    .bind(&session.encrypted_credentials)
    .bind(&session.unsupported_contracts)
    .bind(session.last_validated_at)
    .bind(session.active)
    .bind(session.sync_status.as_str())
    .bind(session.last_sync_started_at)
    .bind(session.last_sync_completed_at)
    .bind(session.last_sync_error.map(SidecarErrorCode::as_str))
    .bind(session.last_sync_life_ambiguous)
    .bind(now)
    .bind(&session.skipped_accounts)
    .execute(db)
    .await?;
    Ok(())
}

pub async fn delete<'e>(db: impl DbExec<'e>, id: i64) -> sqlx::Result<()> {
    db::query("DELETE FROM sidecar_session WHERE id = $1")
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}

/// markInterruptedSyncsFailed: every QUEUED or RUNNING row of the bank becomes an INTERNAL_ERROR
/// failure. A bulk update, so updated_at is left as it was.
pub async fn mark_interrupted_failed<'e>(
    db: impl DbExec<'e>,
    bank: SidecarBank,
    completed_at: DateTime<Utc>,
) -> sqlx::Result<u64> {
    Ok(db::query(
        "UPDATE sidecar_session SET sync_status = 'FAILED', last_sync_completed_at = $2, \
         last_sync_error = 'INTERNAL_ERROR' WHERE bank = $1 AND sync_status IN ('QUEUED', 'RUNNING')",
    )
    .bind(bank.as_str())
    .bind(completed_at)
    .execute(db)
    .await?
    .rows_affected())
}

/// markInterruptedSyncsFailedBefore: only rows not touched since `stale_before`, since a live job
/// keeps updating its row.
pub async fn mark_interrupted_failed_before<'e>(
    db: impl DbExec<'e>,
    bank: SidecarBank,
    completed_at: DateTime<Utc>,
    stale_before: DateTime<Utc>,
) -> sqlx::Result<u64> {
    Ok(db::query(
        "UPDATE sidecar_session SET sync_status = 'FAILED', last_sync_completed_at = $2, \
         last_sync_error = 'INTERNAL_ERROR' WHERE bank = $1 AND sync_status IN ('QUEUED', 'RUNNING') \
         AND updated_at < $3",
    )
    .bind(bank.as_str())
    .bind(completed_at)
    .bind(stale_before)
    .execute(db)
    .await?
    .rows_affected())
}
