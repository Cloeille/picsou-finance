//! The hop to a Python sidecar (SidecarWebClientFactory) and the strict {"detail": "<CODE>"}
//! error contract most sidecars speak (SidecarClient).

use std::time::Duration;

use bytes::{Bytes, BytesMut};
use http::{HeaderMap, Method, StatusCode};
use serde::Serialize;
use serde::de::DeserializeOwned;

use super::error::SyncError;
use crate::AppState;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode};

/// Carries the shared secret; named, not Authorization, so no proxy strips or caches on it.
pub const API_KEY_HEADER: &str = "X-Picsou-Sidecar-Key";
/// The challenge a sidecar answers a missing or wrong key with.
pub const AUTH_CHALLENGE: &str = "Picsou-Sidecar-Key";
/// SidecarAuthenticationException.CODE, outside every per-bank error enum.
pub const SIDECAR_UNAUTHORIZED: &str = "SIDECAR_UNAUTHORIZED";
/// Largest sidecar body read in memory: a long account history is well past 256 KB.
pub const MAX_RESPONSE_BYTES: usize = 16 * 1024 * 1024;

/// A sidecar answer of any status.
#[derive(Debug, Clone)]
pub struct RawResponse {
    pub status: StatusCode,
    pub headers: HeaderMap,
    pub body: Bytes,
}

impl RawResponse {
    pub fn json<T: DeserializeOwned>(&self) -> serde_json::Result<T> {
        serde_json::from_slice(&self.body)
    }

    pub fn is_success(&self) -> bool {
        self.status.is_success()
    }
}

/// Why no answer came back.
#[derive(Debug)]
pub enum CallError {
    /// The sidecar refused the shared key: always a deployment fault.
    Unauthorized(SyncError),
    Timeout,
    Unreachable,
    /// Anything else on the way (an oversized or truncated body).
    Transport(String),
}

/// SidecarWebClientFactory.create: one sidecar's base URL, the shared key and the capped reader.
#[derive(Clone)]
pub struct SidecarHttp {
    http: reqwest::Client,
    base: String,
    key: Option<String>,
    sidecar_name: &'static str,
}

impl SidecarHttp {
    /// `service` is the compose service name the URL is configured under, `sidecar_name` the
    /// label error messages use.
    pub fn new(state: &AppState, service: &'static str, sidecar_name: &'static str) -> Self {
        Self {
            http: state.http.clone(),
            base: state.connectors.base_url(service).to_owned(),
            key: state.connectors.api_key().map(str::to_owned),
            sidecar_name,
        }
    }

    pub async fn send<B: Serialize + ?Sized>(
        &self,
        method: Method,
        path: &str,
        body: Option<&B>,
        timeout: Duration,
    ) -> Result<RawResponse, CallError> {
        let mut request = self
            .http
            .request(method, format!("{}{path}", self.base))
            .timeout(timeout);
        if let Some(key) = &self.key {
            request = request.header(API_KEY_HEADER, key);
        }
        if let Some(body) = body {
            request = request.json(body);
        }
        let mut response = request.send().await.map_err(classify)?;
        let status = response.status();
        let headers = response.headers().clone();
        if status == StatusCode::UNAUTHORIZED
            && headers
                .get_all(http::header::WWW_AUTHENTICATE)
                .iter()
                .any(|v| v.to_str().is_ok_and(|v| v.contains(AUTH_CHALLENGE)))
        {
            return Err(CallError::Unauthorized(unauthorized(self.sidecar_name)));
        }
        let mut body = BytesMut::new();
        while let Some(chunk) = response.chunk().await.map_err(classify)? {
            if body.len() + chunk.len() > MAX_RESPONSE_BYTES {
                return Err(CallError::Transport(format!(
                    "response exceeds {MAX_RESPONSE_BYTES} bytes"
                )));
            }
            body.extend_from_slice(&chunk);
        }
        Ok(RawResponse {
            status,
            headers,
            body: body.freeze(),
        })
    }
}

fn classify(error: reqwest::Error) -> CallError {
    if error.is_timeout() {
        CallError::Timeout
    } else if error.is_connect() || error.is_request() {
        CallError::Unreachable
    } else {
        CallError::Transport(error.without_url().to_string())
    }
}

/// SidecarAuthenticationException.
pub fn unauthorized(sidecar_name: &str) -> SyncError {
    SyncError::with_code(
        SIDECAR_UNAUTHORIZED,
        format!(
            "Picsou could not authenticate to the {sidecar_name} sidecar. The backend and the sidecar must share \
             the same APP_SIDECAR_API_KEY -- check that both containers were started with it."
        ),
    )
}

/// SidecarClient: posts to one bank's sidecar and maps its failures onto that bank's codes.
#[derive(Clone)]
pub struct SidecarClient {
    http: SidecarHttp,
    bank: SidecarBank,
    attempt_expired: SidecarErrorCode,
}

impl SidecarClient {
    /// `attempt_expired` is what a 410 means for this client: AUTH_ATTEMPT_EXPIRED when the
    /// pending login is gone, SESSION_EXPIRED when a stored session died.
    pub fn new(state: &AppState, bank: SidecarBank, attempt_expired: SidecarErrorCode) -> Self {
        Self {
            http: SidecarHttp::new(state, bank.service_name(), bank.label()),
            bank,
            attempt_expired,
        }
    }

    pub fn bank(&self) -> SidecarBank {
        self.bank
    }

    /// POSTs `body` and decodes one object. `auth_failure` is what a bare 401 means for this
    /// call (the bank rejected the secret the user typed), None when it means nothing special.
    pub async fn post<T: DeserializeOwned, B: Serialize + ?Sized>(
        &self,
        path: &str,
        body: &B,
        timeout: Duration,
        message: &str,
        auth_failure: Option<SidecarErrorCode>,
    ) -> Result<T, SyncError> {
        let response = self
            .post_raw(path, body, timeout)
            .await
            .map_err(|e| self.map_call_error(message, e))?;
        if !response.is_success() {
            return Err(self.map_status(message, &response, auth_failure));
        }
        self.decode(message, &response)?
            .ok_or_else(|| self.coded(SidecarErrorCode::UpstreamUnavailable, message))
    }

    /// POSTs `body` and decodes a non-empty array: these endpoints only answer once the sidecar
    /// has a complete snapshot, so an empty one means the scrape fell short.
    #[allow(clippy::too_many_arguments)]
    pub async fn post_for_list<T: DeserializeOwned, B: Serialize + ?Sized>(
        &self,
        path: &str,
        body: &B,
        timeout: Duration,
        message: &str,
        empty_code: SidecarErrorCode,
        empty_message: &str,
    ) -> Result<Vec<T>, SyncError> {
        let response = self
            .post_raw(path, body, timeout)
            .await
            .map_err(|e| self.map_call_error(message, e))?;
        if !response.is_success() {
            return Err(self.map_status(message, &response, None));
        }
        match self.decode::<Vec<T>>(message, &response)? {
            Some(list) if !list.is_empty() => Ok(list),
            _ => Err(self.coded(empty_code, empty_message)),
        }
    }

    /// The answer whatever its status, for a caller that reads a status itself.
    pub async fn post_raw<B: Serialize + ?Sized>(
        &self,
        path: &str,
        body: &B,
        timeout: Duration,
    ) -> Result<RawResponse, CallError> {
        self.http
            .send(Method::POST, path, Some(body), timeout)
            .await
    }

    /// The body as `T`; None for an empty body or a JSON null (Mono.empty()).
    pub fn decode<T: DeserializeOwned>(
        &self,
        message: &str,
        response: &RawResponse,
    ) -> Result<Option<T>, SyncError> {
        if response.body.iter().all(u8::is_ascii_whitespace) {
            return Ok(None);
        }
        let value: serde_json::Value = serde_json::from_slice(&response.body).map_err(|_| {
            tracing::error!(
                "{message}: the {} sidecar answered malformed JSON",
                self.bank.label()
            );
            self.coded(SidecarErrorCode::UpstreamUnavailable, message)
        })?;
        if value.is_null() {
            return Ok(None);
        }
        serde_json::from_value(value).map(Some).map_err(|e| {
            tracing::error!(
                "{message}: could not decode the {} sidecar answer: {e}",
                self.bank.label()
            );
            self.coded(SidecarErrorCode::UpstreamUnavailable, message)
        })
    }

    /// mapError for a request that got no answer.
    pub fn map_call_error(&self, message: &str, error: CallError) -> SyncError {
        match error {
            CallError::Unauthorized(error) => error,
            CallError::Timeout => {
                tracing::warn!("{message}: sidecar request timed out");
                self.coded(
                    SidecarErrorCode::UpstreamUnavailable,
                    &format!(
                        "{} took too long to respond. Please try again.",
                        self.bank.label()
                    ),
                )
            }
            CallError::Unreachable => {
                tracing::warn!("{message}: sidecar is unreachable");
                self.coded(SidecarErrorCode::UpstreamUnavailable, message)
            }
            CallError::Transport(detail) => {
                tracing::error!("{message}: {detail}");
                self.coded(SidecarErrorCode::UpstreamUnavailable, message)
            }
        }
    }

    /// mapError for an error status: the declared detail code first, then what the status means.
    pub fn map_status(
        &self,
        message: &str,
        response: &RawResponse,
        auth_failure: Option<SidecarErrorCode>,
    ) -> SyncError {
        if let Some(code) = self.response_code(response) {
            return self.declared(code, message);
        }
        if response.status == StatusCode::UNAUTHORIZED
            && let Some(code) = auth_failure
        {
            return self.declared(code, message);
        }
        if response.status == StatusCode::GONE {
            return self.declared(self.attempt_expired, message);
        }
        if response.status.is_server_error() {
            tracing::warn!(
                "{} sidecar returned status {}",
                self.bank.label(),
                response.status.as_u16()
            );
            return self.declared(SidecarErrorCode::UpstreamUnavailable, message);
        }
        tracing::error!("{message} (status {})", response.status.as_u16());
        self.coded(SidecarErrorCode::UpstreamUnavailable, message)
    }

    /// The code this bank declared in the body's `detail`; None when the body has none, is not
    /// JSON, or names a code the bank does not declare (its session table would refuse it).
    pub fn response_code(&self, response: &RawResponse) -> Option<SidecarErrorCode> {
        let Ok(body) = serde_json::from_slice::<serde_json::Value>(&response.body) else {
            tracing::warn!(
                "Could not parse {} sidecar error response (status={})",
                self.bank.label(),
                response.status.as_u16()
            );
            return None;
        };
        let detail = body.get("detail").and_then(serde_json::Value::as_str);
        let Some(detail) = detail.filter(|d| !d.trim().is_empty()) else {
            tracing::debug!(
                "{} error response has no textual detail code (status={})",
                self.bank.label(),
                response.status.as_u16()
            );
            return None;
        };
        let declared = self.bank.parse(Some(detail));
        if declared.is_none() {
            tracing::warn!(
                "{} sidecar returned unknown error code '{}'",
                self.bank.label(),
                detail.escape_debug()
            );
        }
        declared
    }

    pub fn coded(&self, code: SidecarErrorCode, message: &str) -> SyncError {
        SyncError::new(code, message)
    }

    /// The code with the bank's own wording for it.
    fn declared(&self, code: SidecarErrorCode, fallback: &str) -> SyncError {
        SyncError::new(code, self.bank.message(code).unwrap_or(fallback))
    }
}
