//! HTTP transports for `/mcp` and `/mcp/message`: legacy SSE (`MCP_PROTOCOL=SSE`, default) and
//! Streamable HTTP (`MCP_PROTOCOL=STREAMABLE`, protocol 2025-11-25).

use std::collections::HashMap;
use std::convert::Infallible;
use std::sync::atomic::{AtomicU64, Ordering};

use axum::body::Bytes;
use axum::extract::{Query, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::sse::{Event, Sse};
use axum::response::{IntoResponse, Response};
use futures::stream::Stream;
use tokio::sync::mpsc;
use uuid::Uuid;

use crate::AppState;
use crate::auth::Auth;
use crate::error::Problem;
use crate::http::not_found;
use crate::mcp::protocol::{self, ERR_INVALID_REQUEST, IncomingMessage, JsonRpcResponse};
use crate::mcp::{ProtocolMode, parse_keep_alive};

const HEADER_SESSION_ID: &str = "mcp-session-id";
const HEADER_PROTOCOL_VERSION: &str = "mcp-protocol-version";

static PING_COUNTER: AtomicU64 = AtomicU64::new(1);

fn protocol_mode(state: &AppState) -> ProtocolMode {
    ProtocolMode::from_config(&state.config.mcp_protocol)
}

fn json_response(status: StatusCode, payload: &JsonRpcResponse) -> Response {
    let body = serde_json::to_vec(payload).unwrap_or_else(|_| b"{}".to_vec());
    let mut response = (status, body).into_response();
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json"),
    );
    response
}

fn single_sse_response(payload: &JsonRpcResponse) -> Response {
    let data = serde_json::to_string(payload).unwrap_or_else(|_| "{}".to_owned());
    let body = format!("event: message\ndata: {data}\n\n");
    let mut response = (StatusCode::OK, body).into_response();
    let headers = response.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/event-stream;charset=UTF-8"),
    );
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-cache"));
    headers.insert("x-accel-buffering", HeaderValue::from_static("no"));
    response
}

fn accepts_sse(headers: &HeaderMap) -> bool {
    headers
        .get(header::ACCEPT)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| v.contains("text/event-stream"))
}

fn session_id_header(headers: &HeaderMap) -> Option<&str> {
    headers
        .get(HEADER_SESSION_ID)
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|s| !s.is_empty())
}

fn method_not_allowed(method: &str) -> Response {
    Problem::with_detail(
        StatusCode::METHOD_NOT_ALLOWED,
        format!("Method '{method}' is not supported."),
    )
    .into_response()
}

fn ping_payload() -> String {
    let n = PING_COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("{{\"jsonrpc\":\"2.0\",\"id\":\"keepalive-{n}\",\"method\":\"ping\"}}")
}

/// Removes an SSE session when the client disconnects.
struct SseCleanup {
    state: AppState,
    session_id: String,
    streamable_sse_id: Option<u64>,
}

impl Drop for SseCleanup {
    fn drop(&mut self) {
        if let Some(sse_id) = self.streamable_sse_id {
            self.state
                .mcp
                .detach_streamable_sse(&self.session_id, sse_id);
        } else {
            self.state.mcp.remove_sse(&self.session_id);
        }
    }
}

fn sse_stream(
    initial: Option<Event>,
    mut rx: mpsc::UnboundedReceiver<String>,
    keep_alive: Option<std::time::Duration>,
    cleanup: SseCleanup,
) -> impl Stream<Item = Result<Event, Infallible>> + Send + 'static {
    async_stream(initial, move |tx| async move {
        let _cleanup = cleanup;
        match keep_alive {
            Some(period) => {
                let mut ticker = tokio::time::interval(period);
                ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
                // First tick fires immediately; consume it so the first ping waits one full period.
                ticker.tick().await;
                loop {
                    tokio::select! {
                        _ = tx.closed() => break,
                        msg = rx.recv() => {
                            let Some(data) = msg else { break; };
                            if tx.send(Ok(Event::default().event("message").data(data))).await.is_err() {
                                break;
                            }
                        }
                        _ = ticker.tick() => {
                            let ping = ping_payload();
                            if tx.send(Ok(Event::default().event("message").data(ping))).await.is_err() {
                                break;
                            }
                        }
                    }
                }
            }
            None => loop {
                tokio::select! {
                    _ = tx.closed() => break,
                    msg = rx.recv() => {
                        let Some(data) = msg else { break; };
                        if tx
                            .send(Ok(Event::default().event("message").data(data)))
                            .await
                            .is_err()
                        {
                            break;
                        }
                    }
                }
            },
        }
    })
}

fn async_stream<F, Fut>(
    initial: Option<Event>,
    producer: F,
) -> impl Stream<Item = Result<Event, Infallible>> + Send + 'static
where
    F: FnOnce(mpsc::Sender<Result<Event, Infallible>>) -> Fut + Send + 'static,
    Fut: std::future::Future<Output = ()> + Send + 'static,
{
    let (tx, rx) = mpsc::channel(64);
    tokio::spawn(async move {
        if let Some(event) = initial
            && tx.send(Ok(event)).await.is_err()
        {
            return;
        }
        producer(tx).await;
    });
    futures::stream::unfold(rx, |mut rx| async move {
        rx.recv().await.map(|item| (item, rx))
    })
}

fn with_sse_headers(mut response: Response) -> Response {
    let headers = response.headers_mut();
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-cache"));
    headers.insert("x-accel-buffering", HeaderValue::from_static("no"));
    response
}

/// GET /mcp:
/// - In SSE mode: opens a new MCP SSE session and immediately emits the `endpoint` event.
/// - In Streamable HTTP mode: opens the optional server-to-client SSE stream for an existing
///   `Mcp-Session-Id`.
pub async fn get_mcp(State(state): State<AppState>, auth: Auth, headers: HeaderMap) -> Response {
    if !state.config.mcp_enabled {
        return not_found("/mcp");
    }
    let keep_alive = parse_keep_alive(&state.config.mcp_keep_alive_interval);
    match protocol_mode(&state) {
        ProtocolMode::Sse => {
            let session_id = Uuid::new_v4().to_string();
            let (tx, rx) = mpsc::unbounded_channel();
            state.mcp.insert_sse(session_id.clone(), auth.user.id, tx);
            let endpoint = format!("/mcp/message?sessionId={session_id}");
            let initial = Event::default().event("endpoint").data(endpoint);
            let stream = sse_stream(
                Some(initial),
                rx,
                keep_alive,
                SseCleanup {
                    state,
                    session_id,
                    streamable_sse_id: None,
                },
            );
            with_sse_headers(Sse::new(stream).into_response())
        }
        ProtocolMode::Streamable => {
            let Some(session_id) = session_id_header(&headers) else {
                return json_response(
                    StatusCode::BAD_REQUEST,
                    &JsonRpcResponse::err(
                        serde_json::Value::Null,
                        ERR_INVALID_REQUEST,
                        "Missing Mcp-Session-Id header",
                    ),
                );
            };
            let (tx, rx) = mpsc::unbounded_channel();
            let now = state.clock.now();
            let Some(sse_id) = state
                .mcp
                .attach_streamable_sse(session_id, auth.user.id, tx, now)
            else {
                return StatusCode::NOT_FOUND.into_response();
            };
            let stream = sse_stream(
                None,
                rx,
                keep_alive,
                SseCleanup {
                    state,
                    session_id: session_id.to_owned(),
                    streamable_sse_id: Some(sse_id),
                },
            );
            with_sse_headers(Sse::new(stream).into_response())
        }
    }
}

/// POST /mcp: Streamable HTTP JSON-RPC endpoint (405 in SSE mode).
pub async fn post_mcp(
    State(state): State<AppState>,
    auth: Auth,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    if !state.config.mcp_enabled {
        return not_found("/mcp");
    }
    if protocol_mode(&state) != ProtocolMode::Streamable {
        return method_not_allowed("POST");
    }
    let message = match protocol::parse_message(&body) {
        Ok(msg) => msg,
        Err(err_resp) => return json_response(StatusCode::BAD_REQUEST, &err_resp),
    };
    let now = state.clock.now();
    match message {
        IncomingMessage::Request { id, method, params } if method == "initialize" => {
            let (resp, version) =
                protocol::handle_initialize(&state, id, &params, ProtocolMode::Streamable);
            let session_id = Uuid::new_v4().to_string();
            state
                .mcp
                .insert_streamable(session_id.clone(), auth.user.id, now);
            let mut http_resp = json_response(StatusCode::OK, &resp);
            if let Ok(val) = HeaderValue::from_str(&session_id) {
                http_resp.headers_mut().insert(HEADER_SESSION_ID, val);
            }
            http_resp
                .headers_mut()
                .insert(HEADER_PROTOCOL_VERSION, HeaderValue::from_static(version));
            http_resp
        }
        IncomingMessage::Notification { method } => {
            let Some(session_id) = session_id_header(&headers) else {
                return json_response(
                    StatusCode::BAD_REQUEST,
                    &JsonRpcResponse::err(
                        serde_json::Value::Null,
                        ERR_INVALID_REQUEST,
                        "Missing Mcp-Session-Id header",
                    ),
                );
            };
            if method == "notifications/initialized" {
                if !state
                    .mcp
                    .mark_streamable_initialized(session_id, auth.user.id, now)
                {
                    return StatusCode::NOT_FOUND.into_response();
                }
            } else if !state.mcp.has_streamable(session_id, auth.user.id, now) {
                return StatusCode::NOT_FOUND.into_response();
            }
            StatusCode::ACCEPTED.into_response()
        }
        IncomingMessage::ClientResponse => {
            let Some(session_id) = session_id_header(&headers) else {
                return json_response(
                    StatusCode::BAD_REQUEST,
                    &JsonRpcResponse::err(
                        serde_json::Value::Null,
                        ERR_INVALID_REQUEST,
                        "Missing Mcp-Session-Id header",
                    ),
                );
            };
            if !state.mcp.has_streamable(session_id, auth.user.id, now) {
                return StatusCode::NOT_FOUND.into_response();
            }
            StatusCode::ACCEPTED.into_response()
        }
        IncomingMessage::Request { id, method, params } => {
            let Some(session_id) = session_id_header(&headers) else {
                return json_response(
                    StatusCode::BAD_REQUEST,
                    &JsonRpcResponse::err(id, ERR_INVALID_REQUEST, "Missing Mcp-Session-Id header"),
                );
            };
            if !state.mcp.has_streamable(session_id, auth.user.id, now) {
                return StatusCode::NOT_FOUND.into_response();
            }
            let is_tool_call = method == "tools/call";
            let resp = protocol::handle_request(
                &state,
                &auth,
                id,
                &method,
                params,
                ProtocolMode::Streamable,
            )
            .await;
            let mut http_resp = if is_tool_call && accepts_sse(&headers) {
                single_sse_response(&resp)
            } else {
                json_response(StatusCode::OK, &resp)
            };
            if let Ok(val) = HeaderValue::from_str(session_id) {
                http_resp.headers_mut().insert(HEADER_SESSION_ID, val);
            }
            http_resp
        }
    }
}

/// DELETE /mcp: closes a Streamable HTTP session (405 in SSE mode).
pub async fn delete_mcp(State(state): State<AppState>, auth: Auth, headers: HeaderMap) -> Response {
    if !state.config.mcp_enabled {
        return not_found("/mcp");
    }
    if protocol_mode(&state) != ProtocolMode::Streamable {
        return method_not_allowed("DELETE");
    }
    let Some(session_id) = session_id_header(&headers) else {
        return json_response(
            StatusCode::BAD_REQUEST,
            &JsonRpcResponse::err(
                serde_json::Value::Null,
                ERR_INVALID_REQUEST,
                "Missing Mcp-Session-Id header",
            ),
        );
    };
    if !state.mcp.remove_streamable(session_id, auth.user.id) {
        return StatusCode::NOT_FOUND.into_response();
    }
    StatusCode::OK.into_response()
}

/// POST /mcp/message?sessionId=<uuid>: SSE transport JSON-RPC message endpoint (404 in Streamable
/// HTTP mode).
pub async fn post_message(
    State(state): State<AppState>,
    auth: Auth,
    Query(query): Query<HashMap<String, String>>,
    body: Bytes,
) -> Response {
    if !state.config.mcp_enabled || protocol_mode(&state) != ProtocolMode::Sse {
        return not_found("/mcp/message");
    }
    let Some(session_id) = query
        .get("sessionId")
        .map(String::as_str)
        .filter(|s| !s.is_empty())
    else {
        return json_response(
            StatusCode::BAD_REQUEST,
            &JsonRpcResponse::err(
                serde_json::Value::Null,
                ERR_INVALID_REQUEST,
                "Missing sessionId query parameter",
            ),
        );
    };
    let Some(tx) = state.mcp.sse_sender(session_id, auth.user.id) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let message = match protocol::parse_message(&body) {
        Ok(msg) => msg,
        Err(err_resp) => return json_response(StatusCode::BAD_REQUEST, &err_resp),
    };
    match message {
        IncomingMessage::Notification { .. } | IncomingMessage::ClientResponse => {
            StatusCode::ACCEPTED.into_response()
        }
        IncomingMessage::Request { id, method, params } => {
            let resp =
                protocol::handle_request(&state, &auth, id, &method, params, ProtocolMode::Sse)
                    .await;
            if let Ok(serialized) = serde_json::to_string(&resp)
                && tx.send(serialized).is_err()
            {
                state.mcp.remove_sse(session_id);
                return StatusCode::NOT_FOUND.into_response();
            }
            StatusCode::OK.into_response()
        }
    }
}
