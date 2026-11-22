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
use std::sync::Arc;

use axum::Json;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post};
use serde::Deserialize;
use serde_json::json;
use subtle::ConstantTimeEq;
use tauri::AppHandle;
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
        .route("/amex/webview/status/{process_id}", get(amex_webview_status))
        .route("/amex/webview/cancel/{process_id}", post(amex_webview_cancel))
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
        .route("/revolut/session/{member_id}", delete(revolut_delete_session))
        .with_state(state)
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

async fn amex_webview_start(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
) -> Response {
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
                return error_response(ApiError::new(
                    StatusCode::BAD_REQUEST,
                    "INVALID_REQUEST",
                ));
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
}
