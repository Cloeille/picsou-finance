//! Reusable native Tauri WebView bank connector bridge (`127.0.0.1` loopback HTTP).
//!
//! Hosts interactive WebView bank connectors inside `picsou-desktop` (starting with BNP Paribas /
//! Hello bank!, ready for Amex/Revolut next). Every route except `GET /health` and the
//! per-process bearer-authenticated `POST /webview/callback/{processId}` requires the shared
//! `X-Picsou-Sidecar-Key` (`APP_SIDECAR_API_KEY`) verified in constant time.

pub mod bnp;
pub mod bnp_parsers;

use std::io;
use std::net::TcpListener;
use std::sync::Arc;

use axum::Json;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use serde::Deserialize;
use serde_json::{Value, json};
use subtle::ConstantTimeEq;
use tauri::AppHandle;
use tokio::sync::oneshot;

use crate::supervisor::LOOPBACK;
use bnp::{ApiError, BnpWebviewManager};

/// Sidecar services natively served by this WebView bridge on Desktop (removed from unavailable).
pub const PROVIDED_SERVICES: &[&str] = &["bnp-auth"];

const API_KEY_HEADER: &str = "X-Picsou-Sidecar-Key";
const AUTH_CHALLENGE: &str = "Picsou-Sidecar-Key";

struct BridgeState {
    app: AppHandle,
    api_key: String,
    bnp: Arc<BnpWebviewManager>,
}

/// Running loopback WebView bridge; dropping or calling `stop` shuts down the HTTP server and
/// closes any open bank login windows.
pub struct WebviewBridge {
    url: String,
    app: AppHandle,
    bnp: Arc<BnpWebviewManager>,
    shutdown: Option<oneshot::Sender<()>>,
}

impl WebviewBridge {
    pub fn url(&self) -> &str {
        &self.url
    }

    pub fn stop(&mut self) {
        self.bnp.close_all(&self.app);
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
    let state = Arc::new(BridgeState {
        app: app.clone(),
        api_key,
        bnp: Arc::clone(&bnp),
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
        shutdown: Some(shutdown_tx),
    })
}

fn build_router(state: Arc<BridgeState>) -> axum::Router {
    axum::Router::new()
        .route("/health", get(health))
        .route("/webview/start", post(webview_start))
        .route("/webview/status/{process_id}", get(webview_status))
        .route("/webview/cancel/{process_id}", post(webview_cancel))
        .route("/webview/callback/{process_id}", post(webview_callback))
        .route("/accounts", post(accounts))
        .with_state(state)
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

async fn health() -> impl IntoResponse {
    Json(json!({ "status": "ok", "webview": true }))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct StartRequest {
    #[serde(default = "default_brand")]
    brand: String,
}

fn default_brand() -> String {
    "bnp".to_owned()
}

async fn webview_start(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Json(body): Json<StartRequest>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    match state.bnp.start_login(state.app.clone(), &body.brand).await {
        Ok(reply) => (StatusCode::OK, Json(reply)).into_response(),
        Err(err) => error_response(err),
    }
}

async fn webview_status(
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

async fn webview_cancel(
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

async fn webview_callback(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Path(process_id): Path<String>,
    Json(payload): Json<Value>,
) -> Response {
    let token = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .unwrap_or("");
    match state.bnp.receive_callback(&process_id, token, payload) {
        Ok(()) => (StatusCode::OK, Json(json!({ "received": true }))).into_response(),
        Err(err) => error_response(err),
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AccountsRequest {
    session_state: String,
}

async fn accounts(
    State(state): State<Arc<BridgeState>>,
    headers: HeaderMap,
    Json(body): Json<AccountsRequest>,
) -> Response {
    if !sidecar_key_valid(&headers, &state.api_key) {
        return unauthorized_response();
    }
    match state.bnp.accounts(&body.session_state).await {
        Ok(reply) => (StatusCode::OK, Json(reply)).into_response(),
        Err(err) => error_response(err),
    }
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
}
