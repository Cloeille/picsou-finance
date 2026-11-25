//! The failures of the Louve Invest connector: Java's SyncException (a stable code and a message
//! with no figure, token or upstream body) and the subclasses the service branches on.

use http::StatusCode;

use crate::error::AppError;
use crate::ledger::sidecar::SidecarErrorCode;

/// What kind of SyncException this is; the service and the adapter branch on it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Kind {
    Plain,
    /// LouveMcpClient.Unauthorized: the access token was refused, the caller refreshes once.
    Unauthorized,
    /// LouveMcpClient.ToolFailed: the tool ran and reported isError.
    ToolFailed,
    /// LouveOAuthClient.InvalidClientException: Louve no longer knows the client id.
    InvalidClient {
        client_id: Option<String>,
        redirect_uri: Option<String>,
    },
    /// LouveSyncService.Transient: superseded or busy, nothing written.
    Transient(&'static str),
}

#[derive(Debug)]
pub enum Failure {
    Sync {
        code: SidecarErrorCode,
        message: String,
        kind: Kind,
    },
    /// Anything else (a database error, a missing member): never a SyncException.
    App(AppError),
}

pub type LouveResult<T> = Result<T, Failure>;

impl Failure {
    pub fn sync(code: SidecarErrorCode, message: impl Into<String>) -> Self {
        Self::Sync {
            code,
            message: message.into(),
            kind: Kind::Plain,
        }
    }

    pub fn format_changed(message: impl Into<String>) -> Self {
        Self::sync(SidecarErrorCode::UpstreamFormatChanged, message)
    }

    pub fn unavailable() -> Self {
        Self::sync(
            SidecarErrorCode::UpstreamUnavailable,
            "Louve Invest is temporarily unavailable",
        )
    }

    pub fn rate_limited() -> Self {
        Self::sync(
            SidecarErrorCode::RateLimited,
            "Louve Invest is limiting requests, try again later",
        )
    }

    pub fn unauthorized() -> Self {
        Self::Sync {
            code: SidecarErrorCode::SessionExpired,
            message: "The Louve Invest access was refused".to_owned(),
            kind: Kind::Unauthorized,
        }
    }

    pub fn transient(reason: &'static str, message: impl Into<String>) -> Self {
        Self::Sync {
            code: SidecarErrorCode::UpstreamUnavailable,
            message: message.into(),
            kind: Kind::Transient(reason),
        }
    }

    pub fn busy() -> Self {
        Self::transient(
            "busy",
            "Another Louve Invest operation is running. Try again in a moment.",
        )
    }

    pub fn kind(&self) -> Option<&Kind> {
        match self {
            Self::Sync { kind, .. } => Some(kind),
            Self::App(_) => None,
        }
    }

    pub fn code(&self) -> Option<SidecarErrorCode> {
        match self {
            Self::Sync { code, .. } => Some(*code),
            Self::App(_) => None,
        }
    }
}

impl From<AppError> for Failure {
    fn from(error: AppError) -> Self {
        Self::App(error)
    }
}

/// PessimisticLockingFailureException: a row lock not obtained within lock_timeout (55P03) or a
/// deadlock (40P01) is a temporary contention, not a failure of the connection.
impl From<sqlx::Error> for Failure {
    fn from(error: sqlx::Error) -> Self {
        let contended = error
            .as_database_error()
            .and_then(|e| e.code())
            .is_some_and(|code| code == "55P03" || code == "40P01");
        if contended {
            Self::busy()
        } else {
            Self::App(error.into())
        }
    }
}

impl From<crate::crypto::CryptoError> for Failure {
    fn from(error: crate::crypto::CryptoError) -> Self {
        Self::App(error.into())
    }
}

/// GlobalExceptionHandler.handleSync: 422 with the message and the code.
impl From<Failure> for AppError {
    fn from(failure: Failure) -> Self {
        match failure {
            Failure::Sync { code, message, .. } => {
                AppError::coded(StatusCode::UNPROCESSABLE_ENTITY, code.as_str(), message)
            }
            Failure::App(error) => error,
        }
    }
}
