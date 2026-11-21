//! SyncException and the failures a sync job can end with.

use http::StatusCode;

use crate::error::AppError;
use crate::ledger::sidecar::SidecarErrorCode;

/// SyncException: a user-facing message and an optional stable code; renders as a 422.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("{message}")]
pub struct SyncError {
    pub message: String,
    pub code: Option<String>,
}

impl SyncError {
    pub fn new(code: SidecarErrorCode, message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            code: Some(code.as_str().to_owned()),
        }
    }

    pub fn with_code(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            code: Some(code.into()),
        }
    }

    pub fn uncoded(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            code: None,
        }
    }

    pub fn code(&self) -> Option<&str> {
        self.code.as_deref()
    }
}

impl From<SyncError> for AppError {
    fn from(error: SyncError) -> Self {
        tracing::warn!("Sync error: {}", error.message);
        match error.code {
            Some(code) => AppError::Coded {
                status: StatusCode::UNPROCESSABLE_ENTITY,
                code,
                detail: error.message,
            },
            None => AppError::Status {
                status: StatusCode::UNPROCESSABLE_ENTITY,
                detail: error.message,
            },
        }
    }
}

/// How a sync job or a step of it failed: a SyncException, a database error, or anything else.
#[derive(Debug, thiserror::Error)]
pub enum JobError {
    #[error(transparent)]
    Sync(#[from] SyncError),
    #[error("database error: {0}")]
    Db(#[from] sqlx::Error),
    #[error(transparent)]
    App(#[from] AppError),
}

impl From<crate::crypto::CryptoError> for JobError {
    fn from(error: crate::crypto::CryptoError) -> Self {
        JobError::App(error.into())
    }
}

impl From<JobError> for AppError {
    fn from(error: JobError) -> Self {
        match error {
            JobError::Sync(sync) => sync.into(),
            JobError::Db(db) => db.into(),
            JobError::App(app) => app,
        }
    }
}

pub type JobResult<T> = Result<T, JobError>;
