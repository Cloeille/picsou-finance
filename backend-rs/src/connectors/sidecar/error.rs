//! SyncException and the failures a sync job can end with.

use std::future::Future;
use std::panic::AssertUnwindSafe;

use futures::FutureExt;
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

impl JobError {
    /// Runs the body of a background job. A panic becomes the job's error, as an uncaught
    /// exception did in Java: the caller then records the terminal state, where an unwound
    /// task would leave the session RUNNING until the next restart.
    pub async fn caught<T>(body: impl Future<Output = Result<T, JobError>>) -> Result<T, JobError> {
        AssertUnwindSafe(body)
            .catch_unwind()
            .await
            .unwrap_or_else(|_| Err(AppError::Internal("the job panicked".into()).into()))
    }
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

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn a_job_that_panics_ends_with_an_error() {
        let outcome = JobError::caught(async {
            if true {
                panic!("overflow");
            }
            Ok(())
        })
        .await;
        assert!(matches!(outcome, Err(JobError::App(AppError::Internal(_)))));
        assert!(
            JobError::caught(async { Ok(7) })
                .await
                .is_ok_and(|n| n == 7)
        );
    }
}
