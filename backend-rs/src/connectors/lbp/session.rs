//! LbpSession and LbpSessionRepository: one La Banque Postale session per member in
//! `lbp_session`, its own table (V143). It holds the encrypted session state and, when the user
//! asked, the encrypted customer identifier; never a password nor the keypad positions.

use std::fmt;

use chrono::{DateTime, Utc};

use crate::connectors::sidecar::SidecarSyncStatus;
use crate::db::{self, DbExec, DbFromRow};
use crate::error::{AppError, AppResult};
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode};

#[derive(DbFromRow)]
struct Row {
    id: i64,
    member_id: i64,
    session_state: String,
    encrypted_identifier: Option<String>,
    last_validated_at: Option<DateTime<Utc>>,
    is_active: bool,
    sync_status: String,
    last_sync_started_at: Option<DateTime<Utc>>,
    last_sync_completed_at: Option<DateTime<Utc>>,
    last_sync_error: Option<String>,
    unsupported_products: Option<String>,
}

/// One row of `lbp_session` (LbpSyncStatus is the same five states as SidecarSyncStatus).
#[derive(Clone, PartialEq)]
pub struct LbpSession {
    pub id: i64,
    pub member_id: i64,
    /// Encrypted with CryptoEncryption; never exposed.
    pub session_state: String,
    /// The remembered customer identifier, encrypted; None when the user did not ask.
    pub encrypted_identifier: Option<String>,
    pub last_validated_at: Option<DateTime<Utc>>,
    pub active: bool,
    pub sync_status: SidecarSyncStatus,
    pub last_sync_started_at: Option<DateTime<Utc>>,
    pub last_sync_completed_at: Option<DateTime<Utc>>,
    pub last_sync_error: Option<SidecarErrorCode>,
    /// JSON array of {externalId, familyCode}.
    pub unsupported_products: Option<String>,
}

impl fmt::Debug for LbpSession {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LbpSession")
            .field("id", &self.id)
            .field("member_id", &self.member_id)
            .field("active", &self.active)
            .field("sync_status", &self.sync_status)
            .field("last_sync_error", &self.last_sync_error)
            .finish_non_exhaustive()
    }
}

impl TryFrom<Row> for LbpSession {
    type Error = sqlx::Error;

    fn try_from(row: Row) -> Result<Self, Self::Error> {
        let decode = |e: String| sqlx::Error::Decode(e.into());
        Ok(Self {
            id: row.id,
            member_id: row.member_id,
            session_state: row.session_state,
            encrypted_identifier: row.encrypted_identifier,
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
            unsupported_products: row.unsupported_products,
        })
    }
}

/// A refused transition is an internal error, as Java's IllegalStateException.
fn refused(message: &str) -> AppError {
    AppError::internal(message)
}

impl LbpSession {
    pub fn is_sync_in_flight(&self) -> bool {
        matches!(
            self.sync_status,
            SidecarSyncStatus::Queued | SidecarSyncStatus::Running
        )
    }

    pub fn mark_queued(&mut self) -> AppResult<()> {
        if !self.active {
            return Err(refused(
                "Inactive La Banque Postale sessions cannot be queued",
            ));
        }
        if self.is_sync_in_flight() {
            return Err(refused(
                "La Banque Postale synchronization is already in progress",
            ));
        }
        self.sync_status = SidecarSyncStatus::Queued;
        self.last_sync_started_at = None;
        self.last_sync_completed_at = None;
        self.last_sync_error = None;
        Ok(())
    }

    pub fn mark_running(&mut self, started_at: DateTime<Utc>) -> AppResult<()> {
        if !self.active || self.sync_status != SidecarSyncStatus::Queued {
            return Err(refused(
                "Only an active queued La Banque Postale session can run",
            ));
        }
        self.sync_status = SidecarSyncStatus::Running;
        self.last_sync_started_at = Some(started_at);
        self.last_sync_completed_at = None;
        self.last_sync_error = None;
        Ok(())
    }

    pub fn mark_successful(&mut self, completed_at: DateTime<Utc>) -> AppResult<()> {
        if !self.active || self.sync_status != SidecarSyncStatus::Running {
            return Err(refused(
                "Only an active running La Banque Postale session can succeed",
            ));
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
            return Err(refused(
                "Only an in-flight La Banque Postale synchronization can fail",
            ));
        }
        let code = SidecarBank::Lbp
            .require_declared(code)
            .map_err(|e| AppError::internal(e.to_string()))?;
        self.sync_status = SidecarSyncStatus::Failed;
        self.last_sync_completed_at = Some(completed_at);
        self.last_sync_error = Some(code);
        // No password is kept, so a session the bank rejected or Picsou cannot read is dead; a
        // transient failure leaves it usable.
        if matches!(
            code,
            SidecarErrorCode::SessionExpired | SidecarErrorCode::InvalidSessionState
        ) {
            self.active = false;
        }
        Ok(())
    }
}

const COLUMNS: &str = "id, member_id, session_state, encrypted_identifier, last_validated_at, is_active, \
    sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error, unsupported_products";

fn decode(row: Option<Row>) -> sqlx::Result<Option<LbpSession>> {
    row.map(LbpSession::try_from).transpose()
}

pub async fn find_by_member<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Option<LbpSession>> {
    let sql = format!("SELECT {COLUMNS} FROM lbp_session WHERE member_id = $1");
    decode(
        db::query_as(&sql)
            .bind(member_id)
            .fetch_optional(db)
            .await?,
    )
}

pub async fn find_by_member_for_update<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Option<LbpSession>> {
    let sql = format!("SELECT {COLUMNS} FROM lbp_session WHERE member_id = $1 FOR UPDATE");
    decode(
        db::query_as(&sql)
            .bind(member_id)
            .fetch_optional(db)
            .await?,
    )
}

/// The row a job is fenced to.
pub async fn find_by_id_and_member_for_update<'e>(
    db: impl DbExec<'e>,
    id: i64,
    member_id: i64,
) -> sqlx::Result<Option<LbpSession>> {
    let sql =
        format!("SELECT {COLUMNS} FROM lbp_session WHERE id = $1 AND member_id = $2 FOR UPDATE");
    decode(
        db::query_as(&sql)
            .bind(id)
            .bind(member_id)
            .fetch_optional(db)
            .await?,
    )
}

/// LbpSession.create: active, idle, validated now.
pub async fn insert<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    encrypted_state: &str,
    encrypted_identifier: Option<&str>,
    now: DateTime<Utc>,
) -> sqlx::Result<LbpSession> {
    let sql = format!(
        "INSERT INTO lbp_session (member_id, session_state, encrypted_identifier, last_validated_at, is_active, \
         sync_status, created_at, updated_at) VALUES ($1, $2, $3, $4, true, 'IDLE', $4, $4) RETURNING {COLUMNS}"
    );
    let row: Row = db::query_as(&sql)
        .bind(member_id)
        .bind(encrypted_state)
        .bind(encrypted_identifier)
        .bind(now)
        .fetch_one(db)
        .await?;
    row.try_into()
}

/// Writes every mutable column back (JPA save), stamping updated_at.
pub async fn save<'e>(
    db: impl DbExec<'e>,
    session: &LbpSession,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    db::query(
        "UPDATE lbp_session SET session_state = $2, encrypted_identifier = $3, last_validated_at = $4, \
         is_active = $5, sync_status = $6, last_sync_started_at = $7, last_sync_completed_at = $8, \
         last_sync_error = $9, unsupported_products = $10, updated_at = $11 WHERE id = $1",
    )
    .bind(session.id)
    .bind(&session.session_state)
    .bind(&session.encrypted_identifier)
    .bind(session.last_validated_at)
    .bind(session.active)
    .bind(session.sync_status.as_str())
    .bind(session.last_sync_started_at)
    .bind(session.last_sync_completed_at)
    .bind(session.last_sync_error.map(SidecarErrorCode::as_str))
    .bind(&session.unsupported_products)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

pub async fn delete<'e>(db: impl DbExec<'e>, id: i64) -> sqlx::Result<()> {
    db::query("DELETE FROM lbp_session WHERE id = $1")
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}

/// markInterruptedSyncsFailed: every QUEUED or RUNNING row becomes an INTERNAL_ERROR failure. A
/// bulk update, so updated_at is left as it was.
pub async fn mark_interrupted_failed<'e>(
    db: impl DbExec<'e>,
    completed_at: DateTime<Utc>,
) -> sqlx::Result<u64> {
    Ok(db::query(
        "UPDATE lbp_session SET sync_status = 'FAILED', last_sync_completed_at = $1, \
         last_sync_error = 'INTERNAL_ERROR' WHERE sync_status IN ('QUEUED', 'RUNNING')",
    )
    .bind(completed_at)
    .execute(db)
    .await?
    .rows_affected())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at() -> DateTime<Utc> {
        DateTime::from_timestamp(1_700_000_000, 0).expect("instant")
    }

    fn session() -> LbpSession {
        LbpSession {
            id: 1,
            member_id: 2,
            session_state: "encrypted".to_owned(),
            encrypted_identifier: None,
            last_validated_at: None,
            active: true,
            sync_status: SidecarSyncStatus::Idle,
            last_sync_started_at: None,
            last_sync_completed_at: None,
            last_sync_error: None,
            unsupported_products: None,
        }
    }

    #[test]
    fn happy_path_queued_running_success() {
        let now = at();
        let mut s = session();
        s.mark_queued().unwrap();
        s.mark_running(now).unwrap();
        s.mark_successful(now).unwrap();
        assert_eq!(s.sync_status, SidecarSyncStatus::Success);
        assert_eq!(s.last_validated_at, Some(now));
        assert!(s.mark_running(now).is_err(), "only a queued session runs");
    }

    #[test]
    fn cannot_queue_twice_or_while_inactive() {
        let mut s = session();
        s.mark_queued().unwrap();
        assert!(s.mark_queued().is_err());
        let mut inactive = session();
        inactive.active = false;
        assert!(inactive.mark_queued().is_err());
    }

    #[test]
    fn an_undeclared_code_is_refused_and_leaves_the_session_untouched() {
        let mut s = session();
        s.mark_queued().unwrap();
        let before = s.clone();
        assert!(s.mark_failed(SidecarErrorCode::InvalidOtp, at()).is_err());
        assert_eq!(s, before);
    }

    #[test]
    fn only_an_expired_or_unreadable_session_is_deactivated() {
        let now = at();
        for (code, stays_active) in [
            (SidecarErrorCode::UpstreamUnavailable, true),
            (SidecarErrorCode::InvalidCredentials, true),
            (SidecarErrorCode::KeypadChanged, true),
            (SidecarErrorCode::SessionExpired, false),
            (SidecarErrorCode::InvalidSessionState, false),
        ] {
            let mut s = session();
            s.mark_queued().unwrap();
            s.mark_failed(code, now).unwrap();
            assert_eq!(s.active, stays_active, "{code:?}");
            assert_eq!(s.last_sync_error, Some(code));
        }
    }

    #[test]
    fn queueing_clears_the_previous_outcome() {
        let now = at();
        let mut s = session();
        s.mark_queued().unwrap();
        s.mark_failed(SidecarErrorCode::UpstreamUnavailable, now)
            .unwrap();
        s.mark_queued().unwrap();
        assert_eq!((s.last_sync_error, s.last_sync_completed_at), (None, None));
    }
}
