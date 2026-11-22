//! Reusable native Tauri WebView bank connector bridge (`127.0.0.1` loopback HTTP).
//!
//! Hosts interactive WebView bank connectors inside `picsou-desktop` for BNP Paribas / Hello bank!,
//! American Express, and Revolut. Every route except health checks requires the shared
//! `X-Picsou-Sidecar-Key` (`APP_SIDECAR_API_KEY`) verified in constant time.

pub mod amex;
pub mod amex_parsers;
pub mod bnp;
pub mod bnp_parsers;
pub mod revolut;
pub mod revolut_parsers;

use std::io;
use std::net::TcpListener;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use axum::extract::{Path, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post};
use axum::Json;
use serde::Deserialize;
use serde_json::json;
use subtle::ConstantTimeEq;
use tauri::{AppHandle, Url, WebviewWindow};
use tokio::sync::oneshot;

use crate::supervisor::LOOPBACK;
use amex::AmexWebviewManager;
use bnp::{ApiError, BnpWebviewManager};
use revolut::RevolutWebviewManager;

/// Sidecar services natively served by this WebView bridge on Desktop (removed from unavailable).
pub const PROVIDED_SERVICES: &[&str] = &["bnp-auth", "amex-auth", "revolut-auth"];

const API_KEY_HEADER: &str = "X-Picsou-Sidecar-Key";
const AUTH_CHALLENGE: &str = "Picsou-Sidecar-Key";

struct BridgeState {
    app: AppHandle,
    api_key: String,
    bnp: Arc<BnpWebviewManager>,
    amex: Arc<AmexWebviewManager>,
    revolut: Arc<RevolutWebviewManager>,
}

/// Running loopback WebView bridge; dropping or calling `stop` shuts down the HTTP server and
/// closes any open bank login windows.
pub struct WebviewBridge {
    url: String,
    app: AppHandle,
    bnp: Arc<BnpWebviewManager>,
    amex: Arc<AmexWebviewManager>,
    revolut: Arc<RevolutWebviewManager>,
    shutdown: Option<oneshot::Sender<()>>,
}

impl WebviewBridge {
    pub fn url(&self) -> &str {
        &self.url
    }

    pub fn amex_url(&self) -> String {
        format!("{}/amex", self.url)
    }

    pub fn revolut_url(&self) -> String {
        format!("{}/revolut", self.url)
    }

    pub fn stop(&mut self) {
        self.bnp.close_all(&self.app);
        self.amex.close_all(&self.app);
        self.revolut.close_all(&self.app);
        if let Some(tx) = self.shutdown.take() {
            let _ = tx.send(());
        }
    }
}

/// Binds strictly to `127.0.0.1:0` and spawns the WebView bridge HTTP server on its own runtime.
pub fn start(app: AppHandle, api_key: String) -> io::Result<WebviewBridge> {
    let listener = TcpListener::bind((LOOPBACK, 0))?;
    let port = listener.local_addr()?.port();
    listener.set_nonblocking(true)?;

    let bnp = Arc::new(BnpWebviewManager::new());
    let amex = Arc::new(AmexWebviewManager::new());
    let revolut = Arc::new(RevolutWebviewManager::new());
    let state = Arc::new(BridgeState {
        app: app.clone(),
        api_key,
        bnp: Arc::clone(&bnp),
        amex: Arc::clone(&amex),
        revolut: Arc::clone(&revolut),
    });

    let (shutdown_tx, shutdown_rx) = oneshot::channel();
    std::thread::Builder::new()
        .name("picsou-webview-bridge".to_owned())
        .spawn(move || {
            let Ok(runtime) = tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()
            else {
                tracing::error!("webview bank bridge runtime failed to build");
                return;
            };
            runtime.block_on(async move {
                let Ok(tcp) = tokio::net::TcpListener::from_std(listener) else {
                    tracing::error!("webview bank bridge listener conversion failed");
                    return;
                };
                let router = build_router(state);
                let served = axum::serve(tcp, router)
                    .with_graceful_shutdown(async move {
                        let _ = shutdown_rx.await;
                    })
                    .await;
                if let Err(err) = served {
                    tracing::error!("webview bank bridge stopped: {err}");
                }
            });
        })?;

    Ok(WebviewBridge {
        url: format!("http://{LOOPBACK}:{port}"),
        app,
        bnp,
        amex,
        revolut,
        shutdown: Some(shutdown_tx),
    })
}

fn build_router(state: Arc<BridgeState>) -> axum::Router {
    axum::Router::new()
        // BNP Paribas / Hello bank! (root prefix for backwards compatibility)
        .route("/health", get(health))
        .route("/webview/start", post(bnp_webview_start))
        .route("/webview/status/{process_id}", get(bnp_webview_status))
        .route("/webview/cancel/{process_id}", post(bnp_webview_cancel))
        .route("/accounts", post(bnp_accounts))
        // American Express
        .route("/amex/health", get(health))
        .route("/amex/webview/start", post(amex_webview_start))
        .route(
            "/amex/webview/status/{process_id}",
            get(amex_webview_status),
        )
        .route(
            "/amex/webview/cancel/{process_id}",
            post(amex_webview_cancel),
        )
        .route("/amex/accounts", post(amex_accounts))
        // Revolut
        .route("/revolut/health", get(health))
        .route("/revolut/webview/start", post(revolut_webview_start))
        .route(
            "/revolut/webview/status/{process_id}",
            get(revolut_webview_status),
        )
        .route(
            "/revolut/webview/cancel/{process_id}",
            post(revolut_webview_cancel),
        )
        .route("/revolut/sync", post(revolut_sync))
        .route("/revolut/progress/{member_id}", get(revolut_progress))
        .route("/revolut/accounts", post(revolut_accounts))
        .route(
            "/revolut/session/{member_id}",
            delete(revolut_delete_session),
        )
        .with_state(state)
}

/// Returns `true` when `url` is `about:blank`, or an `https` URL on default port 443 with no
/// embedded userinfo whose lowercased host satisfies `host_allowed`.
pub(crate) fn is_allowed_https_navigation(
    url: &Url,
    host_allowed: impl FnOnce(&str) -> bool,
) -> bool {
    if url.as_str() == "about:blank" {
        return true;
    }
    if url.scheme() != "https"
        || !url.username().is_empty()
        || url.password().is_some()
        || !matches!(url.port(), None | Some(443))
    {
        return false;
    }
    let Some(host) = url.host_str() else {
        return false;
    };
    host_allowed(&host.to_ascii_lowercase())
}

/// Returns `true` when `host_lower` equals `domain` or is a proper subdomain (`*.domain`).
pub(crate) fn is_domain_or_subdomain(host_lower: &str, domain: &str) -> bool {
    host_lower == domain
        || host_lower
            .strip_suffix(domain)
            .is_some_and(|prefix| prefix.ends_with('.') && prefix.len() > 1)
}

pub(crate) const PENDING_SENTINEL: &str = "__PENDING__";
pub(crate) const CONTEXT_LOST_SENTINEL: &str = "__LOST__";

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum SlotPollState {
    /// `window[slot]` still holds `"__PENDING__"` (fetch in flight).
    Pending,
    /// `window[slot]` disappeared (`undefined`/`null`/empty) because the page navigated or reloaded.
    ContextLost,
    /// `window[slot]` resolved to the serialized JSON payload.
    Ready(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum EvalPollError {
    EvalFailed,
    ContextLost,
    Timeout,
}

impl EvalPollError {
    pub(crate) fn as_reason(self) -> &'static str {
        match self {
            Self::EvalFailed => "eval_failed",
            Self::ContextLost => "context_lost",
            Self::Timeout => "timeout",
        }
    }
}

/// Builds the polling JS snippet for an ephemeral `window[slot]` initialized to `"__PENDING__"`.
/// Distinguishes in-flight (`"__PENDING__"`) from navigation/reload (`undefined`/`null` -> `"__LOST__"`).
pub(crate) fn build_slot_read_js(slot: &str) -> String {
    format!(
        r#"(function(){{ var k = "{slot}"; var v = window[k]; if (v === undefined || v === null) {{ return "{CONTEXT_LOST_SENTINEL}"; }} if (v === "{PENDING_SENTINEL}") {{ return "{PENDING_SENTINEL}"; }} delete window[k]; return v; }})()"#
    )
}

/// Classifies the raw string returned by `eval_with_callback` on `build_slot_read_js`.
pub(crate) fn classify_eval_poll(raw: &str) -> SlotPollState {
    match bnp::unwrap_eval_string(raw) {
        Some(s) if s == PENDING_SENTINEL => SlotPollState::Pending,
        Some(s) if s == CONTEXT_LOST_SENTINEL || s == "undefined" => SlotPollState::ContextLost,
        Some(payload) => SlotPollState::Ready(payload),
        None => SlotPollState::ContextLost,
    }
}

/// Builds the cleanup JS snippet that removes an ephemeral `window[slot]` on timeout so a
/// late-resolving `fetch()` cannot retain an orphaned payload on `window`.
pub(crate) fn build_slot_delete_js(slot: &str) -> String {
    let slot_js = serde_json::to_string(slot).unwrap_or_else(|_| "\"\"".to_owned());
    format!(r#"(function(){{ delete window[{slot_js}]; }})()"#)
}

/// Polls an ephemeral `window[slot]` until it resolves to a JSON payload, loses JS context
/// (e.g. SPA redirect/reload wipes `window[slot]` -> immediate `ContextLost`), or hits `timeout`.
pub(crate) async fn poll_webview_slot(
    window: &WebviewWindow,
    slot: &str,
    timeout: Duration,
    poll_interval: Duration,
) -> Result<String, EvalPollError> {
    let read_js = build_slot_read_js(slot);
    let delete_js = build_slot_delete_js(slot);
    let deadline = Instant::now() + timeout;
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            let _ = window.eval(&delete_js);
            return Err(EvalPollError::Timeout);
        }
        let (tx, rx) = oneshot::channel::<String>();
        let sender = Arc::new(Mutex::new(Some(tx)));
        if window
            .eval_with_callback(&read_js, move |val| {
                if let Some(tx) = sender.lock().unwrap_or_else(|e| e.into_inner()).take() {
                    let _ = tx.send(val);
                }
            })
            .is_err()
        {
            return Err(EvalPollError::EvalFailed);
        }
        let step_wait = remaining.min(Duration::from_secs(2));
        match tokio::time::timeout(step_wait, rx).await {
            Ok(Ok(res)) => match classify_eval_poll(&res) {
                SlotPollState::Ready(payload) => return Ok(payload),
                SlotPollState::ContextLost => return Err(EvalPollError::ContextLost),
                SlotPollState::Pending => {}
            },
            Ok(Err(_)) => return Err(EvalPollError::ContextLost),
            Err(_) => {
                if Instant::now() >= deadline {
                    let _ = window.eval(&delete_js);
                    return Err(EvalPollError::Timeout);
                }
            }
        }
        let sleep_for = poll_interval.min(deadline.saturating_duration_since(Instant::now()));
        if sleep_for.is_zero() {
            let _ = window.eval(&delete_js);
            return Err(EvalPollError::Timeout);
        }
        tokio::time::sleep(sleep_for).await;
    }
}

/// Returns `true` only once the active document has reached `document.readyState === "complete"`.
pub(crate) async fn document_ready_complete(window: &WebviewWindow) -> bool {
    let (tx, rx) = oneshot::channel::<String>();
    let sender = Arc::new(Mutex::new(Some(tx)));
    let js = r#"(function(){ return document.readyState || ""; })()"#;
    if window
        .eval_with_callback(js, move |val| {
            if let Some(tx) = sender.lock().unwrap_or_else(|e| e.into_inner()).take() {
                let _ = tx.send(val);
            }
        })
        .is_err()
    {
        return false;
    }
    match tokio::time::timeout(Duration::from_secs(1), rx).await {
        Ok(Ok(raw)) => bnp::unwrap_eval_string(&raw).as_deref() == Some("complete"),
        _ => false,
    }
}

pub(crate) enum ReadBodyError {
    Transport,
    TooLarge,
}

/// Streams an upstream HTTP response body chunk-by-chunk, aborting immediately if total bytes
/// exceed `max_bytes`.
pub(crate) async fn read_capped_bytes(
    mut resp: reqwest::Response,
    max_bytes: usize,
) -> Result<Vec<u8>, ReadBodyError> {
    if let Some(len) = resp.content_length() {
        if len > max_bytes as u64 {
            return Err(ReadBodyError::TooLarge);
        }
    }
    let mut buf = Vec::new();
    while let Some(chunk) = resp.chunk().await.map_err(|_| ReadBodyError::Transport)? {
        if buf.len().saturating_add(chunk.len()) > max_bytes {
            return Err(ReadBodyError::TooLarge);
        }
        buf.extend_from_slice(&chunk);
    }
    Ok(buf)
}

pub(crate) async fn read_capped_text(
    resp: reqwest::Response,
    max_bytes: usize,
) -> Result<String, ReadBodyError> {
    let bytes = read_capped_bytes(resp, max_bytes).await?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

fn sidecar_key_valid(headers: &HeaderMap, expected: &str) -> bool {
    let provided = headers
        .get(API_KEY_HEADER)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    !expected.is_empty() && bool::from(provided.as_bytes().ct_eq(expected.as_bytes()))
}

fn unauthorized_response() -> Response {
    let mut resp = (
        StatusCode::UNAUTHORIZED,
        Json(json!({ "detail": "UNAUTHORIZED" })),
    )
        .into_response();
    resp.headers_mut().insert(
        header::WWW_AUTHENTICATE,
        HeaderValue::from_static(AUTH_CHALLENGE),
    );
    resp
}

fn error_response(err: ApiError) -> Response {
    (err.status, Json(json!({ "detail": err.detail }))).into_response()
}

fn revolut_error_response(err: ApiError) -> Response {
    (err.status, Json(json!({ "error": err.detail }))).into_response()
}

async fn health() -> impl IntoResponse {
    Json(json!({ "status": "ok", "webview": true }))
}

// ─── BNP routes ──────────────────────────────────────────────────────────────

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct BnpStartRequest {
    #[serde(default = "default_brand")]
    brand: String,
}

fn default_brand() -> String {
    "bnp".to_owned()
}

async fn bnp_webview_start(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Json(body): Json<BnpStartRequest>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    match state.bnp.start_login(state.app.clone(), &body.brand).await {
        Ok(reply) => (StatusCode::OK, Json(reply)).into_response(),
        Err(err) => error_response(err),
    }
}

async fn bnp_webview_status(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Path(process_id): Path<String>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    match state.bnp.get_status(&process_id) {
        Ok(reply) => (StatusCode::OK, Json(reply)).into_response(),
        Err(err) => error_response(err),
    }
}

async fn bnp_webview_cancel(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Path(process_id): Path<String>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    let cancelled = state.bnp.cancel(&state.app, &process_id);
    (StatusCode::OK, Json(json!({ "cancelled": cancelled }))).into_response()
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct BnpAccountsRequest {
    session_state: String,
}

async fn bnp_accounts(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Json(body): Json<BnpAccountsRequest>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    match state.bnp.accounts(&body.session_state).await {
        Ok(reply) => (StatusCode::OK, Json(reply)).into_response(),
        Err(err) => error_response(err),
    }
}

// ─── Amex routes ─────────────────────────────────────────────────────────────

async fn amex_webview_start(State(state): State<Arc<BridgeState>>, headers: HeaderMap) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    match state.amex.start_login(state.app.clone()).await {
        Ok(reply) => (StatusCode::OK, Json(reply)).into_response(),
        Err(err) => error_response(err),
    }
}

async fn amex_webview_status(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Path(process_id): Path<String>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    match state.amex.get_status(&process_id) {
        Ok(reply) => (StatusCode::OK, Json(reply)).into_response(),
        Err(err) => error_response(err),
    }
}

async fn amex_webview_cancel(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Path(process_id): Path<String>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    let cancelled = state.amex.cancel(&state.app, &process_id);
    (StatusCode::OK, Json(json!({ "cancelled": cancelled }))).into_response()
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AmexAccountsRequest {
    session_state: String,
    #[serde(default)]
    history: bool,
}

async fn amex_accounts(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Json(body): Json<AmexAccountsRequest>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    match state.amex.accounts(&body.session_state, body.history).await {
        Ok(reply) => (StatusCode::OK, Json(reply)).into_response(),
        Err(err) => error_response(err),
    }
}

// ─── Revolut routes ──────────────────────────────────────────────────────────

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
struct RevolutStartRequest {
    member_id: String,
}

async fn revolut_webview_start(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    let req: RevolutStartRequest = if body.iter().all(u8::is_ascii_whitespace) {
        RevolutStartRequest::default()
    } else {
        match serde_json::from_slice(&body) {
            Ok(parsed) => parsed,
            Err(_) => {
                return error_response(ApiError::new(StatusCode::BAD_REQUEST, "INVALID_REQUEST"));
            }
        }
    };
    match state
        .revolut
        .start_login(state.app.clone(), &req.member_id)
        .await
    {
        Ok(reply) => (StatusCode::OK, Json(reply)).into_response(),
        Err(err) => error_response(err),
    }
}

async fn revolut_webview_status(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Path(process_id): Path<String>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    match state.revolut.get_status(&process_id) {
        Ok(reply) => (StatusCode::OK, Json(reply)).into_response(),
        Err(err) => error_response(err),
    }
}

async fn revolut_webview_cancel(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Path(process_id): Path<String>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    let cancelled = state.revolut.cancel(&state.app, &process_id);
    (StatusCode::OK, Json(json!({ "cancelled": cancelled }))).into_response()
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RevolutSyncRequest {
    #[serde(default)]
    member_id: String,
}

async fn revolut_sync(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Json(body): Json<RevolutSyncRequest>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    match state.revolut.sync_member(&body.member_id).await {
        Ok(reply) => (StatusCode::OK, Json(reply)).into_response(),
        Err(err) => revolut_error_response(err),
    }
}

async fn revolut_accounts(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Json(body): Json<RevolutSyncRequest>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    match state.revolut.sync_member(&body.member_id).await {
        Ok(reply) => (StatusCode::OK, Json(reply)).into_response(),
        Err(err) => revolut_error_response(err),
    }
}

async fn revolut_progress(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Path(member_id): Path<String>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    let snap = state.revolut.get_progress(&member_id);
    (StatusCode::OK, Json(snap)).into_response()
}

async fn revolut_delete_session(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Path(member_id): Path<String>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    let cleared = state.revolut.clear_member(&member_id);
    (StatusCode::OK, Json(json!({ "cleared": cleared }))).into_response()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sidecar_key_check_requires_exact_match_and_sets_challenge() {
        let mut headers = HeaderMap::new();
        assert!(!sidecar_key_valid(&headers, "secret-key"));
        let missing = unauthorized_response();
        assert_eq!(missing.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(
            missing.headers().get(header::WWW_AUTHENTICATE).unwrap(),
            AUTH_CHALLENGE
        );

        headers.insert(API_KEY_HEADER, HeaderValue::from_static("wrong"));
        assert!(!sidecar_key_valid(&headers, "secret-key"));

        headers.insert(API_KEY_HEADER, HeaderValue::from_static("secret-key"));
        assert!(sidecar_key_valid(&headers, "secret-key"));
    }

    #[test]
    fn provided_services_covers_all_three_webview_banks() {
        assert!(PROVIDED_SERVICES.contains(&"bnp-auth"));
        assert!(PROVIDED_SERVICES.contains(&"amex-auth"));
        assert!(PROVIDED_SERVICES.contains(&"revolut-auth"));
    }

    #[test]
    fn slot_poll_state_distinguishes_pending_context_lost_and_json_result() {
        // Pending sentinel (both JSON-quoted from eval_with_callback and raw).
        assert_eq!(
            classify_eval_poll("\"__PENDING__\""),
            SlotPollState::Pending
        );
        assert_eq!(classify_eval_poll("__PENDING__"), SlotPollState::Pending);

        // Context-lost indicators: explicit __LOST__, undefined, null, empty strings.
        assert_eq!(
            classify_eval_poll("\"__LOST__\""),
            SlotPollState::ContextLost
        );
        assert_eq!(classify_eval_poll("__LOST__"), SlotPollState::ContextLost);
        assert_eq!(
            classify_eval_poll("\"undefined\""),
            SlotPollState::ContextLost
        );
        assert_eq!(classify_eval_poll("undefined"), SlotPollState::ContextLost);
        assert_eq!(classify_eval_poll("null"), SlotPollState::ContextLost);
        assert_eq!(classify_eval_poll("\"\""), SlotPollState::ContextLost);
        assert_eq!(classify_eval_poll(""), SlotPollState::ContextLost);

        // Resolved JSON payload (JSON-escaped string as returned by WKWebView / WebView2).
        let payload = r#"{"ok":true,"status":200,"text":"{}"}"#;
        let quoted = serde_json::to_string(payload).unwrap();
        assert_eq!(
            classify_eval_poll(&quoted),
            SlotPollState::Ready(payload.to_owned())
        );
        assert_eq!(
            classify_eval_poll(payload),
            SlotPollState::Ready(payload.to_owned())
        );

        // Generated read_js must check undefined/null and __PENDING__ and delete the slot.
        let read_js = build_slot_read_js("__picsou_test_slot");
        assert!(read_js.contains("v === undefined || v === null"));
        assert!(read_js.contains(CONTEXT_LOST_SENTINEL));
        assert!(read_js.contains(PENDING_SENTINEL));
        assert!(read_js.contains("delete window[k]"));

        // Timeout cleanup JS must safely JSON-escape the slot name and delete it.
        let delete_js = build_slot_delete_js("__picsou_test_slot");
        assert_eq!(
            delete_js,
            r#"(function(){ delete window["__picsou_test_slot"]; })()"#
        );
    }
}
