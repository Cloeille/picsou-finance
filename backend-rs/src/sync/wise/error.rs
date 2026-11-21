//! The SyncException of the Wise connector and its 422 rendering.

use http::StatusCode;

use crate::crypto::CryptoError;
use crate::error::AppError;

/// A SyncException: the message is meant for the member, the code is stable for the frontend.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncFailure {
    pub message: String,
    pub code: Option<&'static str>,
}

impl std::fmt::Display for SyncFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

/// GlobalExceptionHandler.handleSync: 422 with the message as detail and the code when set.
impl From<SyncFailure> for AppError {
    fn from(failure: SyncFailure) -> Self {
        tracing::warn!("Sync error: {}", failure.message);
        match failure.code {
            Some(code) => AppError::coded(StatusCode::UNPROCESSABLE_ENTITY, code, failure.message),
            None => AppError::status(StatusCode::UNPROCESSABLE_ENTITY, failure.message),
        }
    }
}

/// Either a SyncException or any other failure (database, crypto), which Java lets through as a
/// RuntimeException.
#[derive(Debug, thiserror::Error)]
pub(super) enum WiseError {
    #[error("{0}")]
    Sync(SyncFailure),
    #[error(transparent)]
    App(#[from] AppError),
}

impl WiseError {
    pub(super) fn sync(message: impl Into<String>) -> Self {
        Self::Sync(SyncFailure {
            message: message.into(),
            code: None,
        })
    }

    pub(super) fn coded(message: &str, code: &'static str) -> Self {
        Self::Sync(SyncFailure {
            message: message.to_owned(),
            code: Some(code),
        })
    }
}

impl From<sqlx::Error> for WiseError {
    fn from(error: sqlx::Error) -> Self {
        Self::App(error.into())
    }
}

impl From<CryptoError> for WiseError {
    fn from(error: CryptoError) -> Self {
        Self::App(error.into())
    }
}

impl From<WiseError> for AppError {
    fn from(error: WiseError) -> Self {
        match error {
            WiseError::Sync(failure) => failure.into(),
            WiseError::App(error) => error,
        }
    }
}
