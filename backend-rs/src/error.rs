//! AppError and its RFC 7807 rendering, identical to the Java GlobalExceptionHandler and
//! Spring's ResponseEntityExceptionHandler bodies.
//!
//! A handler returns an AppError; its response carries the problem as an extension and the
//! problem layer (http::problem_layer) writes the body, adding the request path as instance.

use std::collections::BTreeMap;

use axum::http::{HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde_json::Value;

pub const PROBLEM_JSON: &str = "application/problem+json";

/// A ProblemDetail before rendering: Spring writes type, title, status, detail, instance, then
/// every extra property in insertion order.
#[derive(Debug, Clone)]
pub struct Problem {
    pub status: StatusCode,
    pub title: Option<String>,
    pub detail: Option<String>,
    pub properties: Vec<(String, Value)>,
}

impl Problem {
    pub fn new(status: StatusCode) -> Self {
        Self {
            status,
            title: None,
            detail: None,
            properties: Vec::new(),
        }
    }

    pub fn with_detail(status: StatusCode, detail: impl Into<String>) -> Self {
        Self {
            detail: Some(detail.into()),
            ..Self::new(status)
        }
    }

    pub fn title(mut self, title: impl Into<String>) -> Self {
        self.title = Some(title.into());
        self
    }

    pub fn property(mut self, name: impl Into<String>, value: impl Into<Value>) -> Self {
        self.properties.push((name.into(), value.into()));
        self
    }

    pub fn code(self, code: impl Into<String>) -> Self {
        self.property("code", Value::String(code.into()))
    }

    /// The JSON body with the given request path as instance.
    pub fn render(&self, instance: &str) -> String {
        let title = self
            .title
            .clone()
            .unwrap_or_else(|| self.status.canonical_reason().unwrap_or("").to_owned());
        let mut out = String::from("{\"type\":\"about:blank\",\"title\":");
        out.push_str(&Value::String(title).to_string());
        out.push_str(",\"status\":");
        out.push_str(&self.status.as_u16().to_string());
        if let Some(detail) = &self.detail {
            out.push_str(",\"detail\":");
            out.push_str(&Value::String(detail.clone()).to_string());
        }
        out.push_str(",\"instance\":");
        out.push_str(&Value::String(instance.to_owned()).to_string());
        for (name, value) in &self.properties {
            out.push(',');
            out.push_str(&Value::String(name.clone()).to_string());
            out.push(':');
            out.push_str(&value.to_string());
        }
        out.push('}');
        out
    }
}

/// Marker extension: the problem layer turns it into the body.
#[derive(Debug, Clone)]
pub struct PendingProblem(pub Problem);

impl IntoResponse for Problem {
    fn into_response(self) -> Response {
        let mut response = self.status.into_response();
        response
            .headers_mut()
            .insert(header::CONTENT_TYPE, HeaderValue::from_static(PROBLEM_JSON));
        response.extensions_mut().insert(PendingProblem(self));
        response
    }
}

/// Field name to message, as the 422 "errors" map.
pub type FieldErrors = BTreeMap<String, String>;

#[derive(Debug, thiserror::Error)]
pub enum AppError {
    /// ResourceNotFoundException: 404 with the message as detail.
    #[error("not found: {0}")]
    NotFound(String),
    /// IllegalArgumentException, MfaException: 400 with the message as detail.
    #[error("bad request: {0}")]
    BadRequest(String),
    /// BadCredentialsException: always "Invalid credentials".
    #[error("bad credentials")]
    BadCredentials,
    /// AccessDeniedException, MissingScopeException, TotpRequiredException: 403.
    #[error("forbidden: {0}")]
    Forbidden(String),
    /// ResponseStatusException: any status with its reason as detail.
    #[error("{status}: {detail}")]
    Status { status: StatusCode, detail: String },
    /// SignInMethodException, SyncException: a stable machine-readable code.
    #[error("{status} {code}: {detail}")]
    Coded {
        status: StatusCode,
        code: String,
        detail: String,
    },
    /// MethodArgumentNotValidException: 422 "Validation failed" with the errors map.
    #[error("validation failed")]
    Validation(FieldErrors),
    /// Any other ProblemDetail a handler builds itself.
    #[error("problem {}", .0.status)]
    Problem(Problem),
    /// An endpoint not ported yet.
    #[error("not implemented")]
    NotImplemented,
    /// Anything unexpected: logged, never exposed.
    #[error("internal error: {0}")]
    Internal(String),
}

impl AppError {
    pub fn internal(error: impl std::fmt::Display) -> Self {
        Self::Internal(error.to_string())
    }

    pub fn status(status: StatusCode, detail: impl Into<String>) -> Self {
        Self::Status {
            status,
            detail: detail.into(),
        }
    }

    pub fn coded(status: StatusCode, code: impl Into<String>, detail: impl Into<String>) -> Self {
        Self::Coded {
            status,
            code: code.into(),
            detail: detail.into(),
        }
    }

    pub fn problem(&self) -> Problem {
        match self {
            Self::NotFound(detail) => Problem::with_detail(StatusCode::NOT_FOUND, detail.clone()),
            Self::BadRequest(detail) => {
                Problem::with_detail(StatusCode::BAD_REQUEST, detail.clone())
            }
            Self::BadCredentials => {
                Problem::with_detail(StatusCode::UNAUTHORIZED, "Invalid credentials")
            }
            Self::Forbidden(detail) => Problem::with_detail(StatusCode::FORBIDDEN, detail.clone()),
            Self::Status { status, detail } => Problem::with_detail(*status, detail.clone()),
            Self::Coded {
                status,
                code,
                detail,
            } => Problem::with_detail(*status, detail.clone()).code(code.clone()),
            Self::Validation(errors) => Problem::new(StatusCode::UNPROCESSABLE_ENTITY)
                .title("Validation failed")
                .property(
                    "errors",
                    serde_json::to_value(errors).unwrap_or(Value::Null),
                ),
            Self::Problem(problem) => problem.clone(),
            Self::NotImplemented => {
                Problem::with_detail(StatusCode::NOT_IMPLEMENTED, "Not implemented yet")
                    .code("NOT_IMPLEMENTED")
            }
            Self::Internal(_) => Problem::with_detail(
                StatusCode::INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
            ),
        }
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        if let Self::Internal(message) = &self {
            tracing::error!("Unhandled exception: {message}");
        }
        self.problem().into_response()
    }
}

impl From<sqlx::Error> for AppError {
    fn from(error: sqlx::Error) -> Self {
        Self::Internal(format!("database: {error}"))
    }
}

impl From<crate::crypto::CryptoError> for AppError {
    fn from(error: crate::crypto::CryptoError) -> Self {
        Self::Internal(error.to_string())
    }
}

pub type AppResult<T> = Result<T, AppError>;

/// The fixed bodies the Java security chain writes by hand, byte for byte (some carry the
/// leading indentation and trailing newline of their Java text blocks).
pub mod raw {
    use super::*;

    pub const ISO_PROBLEM_JSON: &str = "application/problem+json;charset=ISO-8859-1";

    pub fn response(status: StatusCode, body: &'static str) -> Response {
        let mut response = (status, body).into_response();
        response.headers_mut().insert(
            header::CONTENT_TYPE,
            HeaderValue::from_static(ISO_PROBLEM_JSON),
        );
        response
    }

    pub const UNAUTHORIZED: &str =
        "{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"Authentication required\"}\n";
    pub const CROSS_SITE: &str =
        "{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"Cross-site request rejected\"}\n";
    pub const SETUP_REQUIRED: &str = "            {\"status\":503,\"title\":\"Setup Required\",\"code\":\"setup_required\",\"detail\":\"Picsou is not configured yet. Complete the setup wizard at /setup.\"}\n";
    pub const PAYLOAD_TOO_LARGE: &str =
        "{\"status\":413,\"title\":\"Payload Too Large\",\"code\":\"payload_too_large\"}\n";
    pub const ACCESS_KEY_RATE_LIMITED: &str = "{\"status\":429,\"title\":\"Too Many Requests\",\"detail\":\"Rate limit exceeded for this access key\"}\n";
}
