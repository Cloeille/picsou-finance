//! In-process native Rust replacements for the pure-HTTP Python sidecars.
//!
//! Each bank exposes an async `call(method, path, body)` method matching the
//! HTTP JSON contract of its Python sidecar (`{"detail": "<CODE>"}` on error).

pub mod bourso;
pub mod natixis;

use http::{Method, StatusCode};
use serde_json::Value;

/// Successful JSON response from a native sidecar route.
#[derive(Debug, Clone)]
pub struct NativeResponse {
    pub status: StatusCode,
    pub body: Value,
}

impl NativeResponse {
    pub fn ok(body: Value) -> Self {
        Self {
            status: StatusCode::OK,
            body,
        }
    }

    pub fn no_content() -> Self {
        Self {
            status: StatusCode::NO_CONTENT,
            body: Value::Null,
        }
    }
}

/// Structured error from a native sidecar route, matching FastAPI's `{"detail": code}` contract.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NativeError {
    pub status: StatusCode,
    pub detail: String,
}

impl NativeError {
    pub fn new(status: StatusCode, detail: impl Into<String>) -> Self {
        Self {
            status,
            detail: detail.into(),
        }
    }

    pub fn into_tuple(self) -> (StatusCode, String) {
        (self.status, self.detail)
    }

    pub fn json_body(&self) -> Value {
        serde_json::json!({ "detail": self.detail })
    }
}

impl From<NativeError> for (StatusCode, String) {
    fn from(value: NativeError) -> Self {
        value.into_tuple()
    }
}

/// Common dispatch result for native sidecars.
pub type NativeCallResult = Result<Value, (StatusCode, String)>;

/// Standard error when a known path is called with an unsupported HTTP method.
pub fn method_not_allowed(_method: &Method) -> (StatusCode, String) {
    (
        StatusCode::METHOD_NOT_ALLOWED,
        "METHOD_NOT_ALLOWED".to_owned(),
    )
}

/// Standard error when an unknown path is called on a native sidecar.
pub fn not_found() -> (StatusCode, String) {
    (StatusCode::NOT_FOUND, "NOT_FOUND".to_owned())
}
