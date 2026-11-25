//! SyncException and how it reaches the client: 422 with the message as detail and an optional code.

use http::StatusCode;

use crate::error::AppError;

pub const NOT_CONFIGURED: &str = "ENABLE_BANKING_NOT_CONFIGURED";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncError {
    pub message: String,
    pub code: Option<&'static str>,
}

/// A failure of an Enable Banking operation: a SyncException, or anything else Java would let
/// propagate (a database error, an unreadable key file, a malformed amount).
#[derive(Debug)]
pub enum EbError {
    Sync(SyncError),
    Other(AppError),
}

impl EbError {
    pub fn sync(message: impl Into<String>) -> Self {
        Self::Sync(SyncError {
            message: message.into(),
            code: None,
        })
    }

    pub fn not_configured(message: impl Into<String>) -> Self {
        Self::Sync(SyncError {
            message: message.into(),
            code: Some(NOT_CONFIGURED),
        })
    }

    /// The text Java's exception message carries, for the logs and the replay check.
    pub fn message(&self) -> String {
        match self {
            Self::Sync(e) => e.message.clone(),
            Self::Other(e) => e.to_string(),
        }
    }
}

impl From<AppError> for EbError {
    fn from(error: AppError) -> Self {
        Self::Other(error)
    }
}

impl From<sqlx::Error> for EbError {
    fn from(error: sqlx::Error) -> Self {
        Self::Other(error.into())
    }
}

impl From<EbError> for AppError {
    fn from(error: EbError) -> Self {
        match error {
            EbError::Sync(e) => {
                tracing::warn!("Sync error: {}", e.message);
                match e.code {
                    Some(code) => {
                        AppError::coded(StatusCode::UNPROCESSABLE_ENTITY, code, e.message)
                    }
                    None => AppError::status(StatusCode::UNPROCESSABLE_ENTITY, e.message),
                }
            }
            EbError::Other(e) => e,
        }
    }
}
