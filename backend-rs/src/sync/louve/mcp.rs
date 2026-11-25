//! LouveMcpClient: a minimal JSON-RPC client for Louve Invest's MCP server over Streamable HTTP
//! (protocol 2025-06-18): initialize, notifications/initialized, then tools/call. Only read-only
//! tools are ever called.
//!
//! An answer comes as plain JSON or as a Server-Sent Events stream, split on \n only: the payload
//! may carry characters (U+2028, U+0085...) a universal-newline split would cut through. A
//! 502/503/504 or an unreachable host is retried once after a short pause; a 401 is
//! Unauthorized, so the caller can refresh the access token once.

use std::sync::LazyLock;
use std::time::Duration;

use regex::Regex;
use serde_json::{Map, Value, json};

use super::error::{Failure, Kind, LouveResult};
use super::transport;
use crate::AppState;
use crate::ledger::sidecar::SidecarErrorCode;

pub const PROTOCOL_VERSION: &str = "2025-06-18";
const RETRY_PAUSE: Duration = Duration::from_secs(2);
static SESSION_ID: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[\\x21-\\x7E]{1,200}$").expect("valid regex"));

/// One MCP session; a sync uses it for one run.
pub struct Session<'a> {
    state: &'a AppState,
    access_token: String,
    session_id: Option<String>,
    next_id: i64,
}

/// Opens an MCP session: initialize, then the initialized notification.
pub async fn open<'a>(state: &'a AppState, access_token: &str) -> LouveResult<Session<'a>> {
    let mut session = Session {
        state,
        access_token: access_token.to_owned(),
        session_id: None,
        next_id: 1,
    };
    let (id, request) = session.request(
        "initialize",
        json!({
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "picsou", "version": "1"},
        }),
    );
    let response = session.post(&request, true).await?;
    if let Some(sid) = response
        .session_id
        .as_deref()
        .filter(|s| SESSION_ID.is_match(s))
    {
        session.session_id = Some(sid.to_owned());
    }
    result(&response, id)?;
    let initialized = json!({"jsonrpc": "2.0", "method": "notifications/initialized"});
    let ack = session.post(&initialized, false).await?;
    if !matches!(ack.status, 200 | 202 | 204) {
        return Err(status_error(ack.status));
    }
    Ok(session)
}

impl Session<'_> {
    /// Calls a tool and returns its JSON result: structuredContent when the server sends it,
    /// else the JSON object held by the first text content item.
    pub async fn call_tool(
        &mut self,
        tool: &str,
        arguments: Value,
    ) -> LouveResult<Map<String, Value>> {
        let (id, request) =
            self.request("tools/call", json!({"name": tool, "arguments": arguments}));
        let response = self.post(&request, true).await?;
        let result = result(&response, id)?;
        if result.get("isError").is_some_and(as_boolean) {
            tracing::warn!("Louve Invest tool reported an error (tool={tool})");
            return Err(Failure::Sync {
                code: SidecarErrorCode::UpstreamUnavailable,
                message: format!("Louve Invest could not answer {tool}"),
                kind: Kind::ToolFailed,
            });
        }
        if let Some(Value::Object(structured)) = result.get("structuredContent") {
            return Ok(structured.clone());
        }
        let first = result
            .get("content")
            .and_then(|c| c.get(0))
            .cloned()
            .unwrap_or(Value::Null);
        let text = match (first.get("type").and_then(Value::as_str), first.get("text")) {
            (Some("text"), Some(Value::String(text))) => text.clone(),
            _ => {
                return Err(Failure::format_changed(format!(
                    "Louve Invest answered {tool} without a text result"
                )));
            }
        };
        match serde_json::from_str::<Value>(&text) {
            Ok(Value::Object(parsed)) => Ok(parsed),
            // "[]" or a bare value would otherwise read as an empty, valid answer.
            Ok(_) => Err(Failure::format_changed(format!(
                "Louve Invest answered {tool} with something else than an object"
            ))),
            Err(_) => Err(Failure::format_changed(format!(
                "Louve Invest answered {tool} with unreadable data"
            ))),
        }
    }

    fn request(&mut self, method: &str, params: Value) -> (i64, Value) {
        let id = self.next_id;
        self.next_id += 1;
        (
            id,
            json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params}),
        )
    }

    async fn post(
        &self,
        message: &Value,
        expects_answer: bool,
    ) -> LouveResult<transport::Response> {
        let authorization = format!("Bearer {}", self.access_token);
        let mut headers = vec![
            ("Authorization", authorization.as_str()),
            ("Content-Type", "application/json"),
            ("Accept", "application/json, text/event-stream"),
            ("MCP-Protocol-Version", PROTOCOL_VERSION),
        ];
        if let Some(sid) = &self.session_id {
            headers.push(("Mcp-Session-Id", sid.as_str()));
        }
        let response = send_with_one_retry(self.state, &headers, message.to_string()).await?;
        if response.status == 401 {
            return Err(Failure::unauthorized());
        }
        if expects_answer && response.status != 200 {
            return Err(status_error(response.status));
        }
        Ok(response)
    }
}

/// The MCP endpoint is the protected resource: base URL + /mcp.
fn endpoint(state: &AppState) -> String {
    format!(
        "{}/mcp",
        state.config.sync_url("louve").trim_end_matches('/')
    )
}

async fn send_with_one_retry(
    state: &AppState,
    headers: &[(&str, &str)],
    body: String,
) -> LouveResult<transport::Response> {
    let url = endpoint(state);
    let mut attempt = 1;
    loop {
        match transport::post(&state.sync.louve.http, &url, headers, body.clone()).await {
            Ok(response) => {
                if !matches!(response.status, 502..=504) || attempt == 2 {
                    return Ok(response);
                }
                tracing::info!(
                    "Louve Invest answered a transient error, retrying once (status={})",
                    response.status
                );
            }
            Err(_) if attempt == 2 => return Err(Failure::unavailable()),
            Err(_) => {
                tracing::info!(
                    "Louve Invest could not be reached, retrying once (type=Unreachable)"
                );
            }
        }
        attempt += 1;
        tokio::time::sleep(RETRY_PAUSE).await;
    }
}

fn result(response: &transport::Response, id: i64) -> LouveResult<Map<String, Value>> {
    let message = answer_to(response, id)?;
    if let Some(error) = message.get("error") {
        let code = error.get("code").map(json_text).unwrap_or_default();
        tracing::warn!("Louve Invest answered a JSON-RPC error (code={code})");
        return Err(Failure::format_changed("Louve Invest refused the request"));
    }
    match message.get("result") {
        Some(Value::Object(result)) => Ok(result.clone()),
        _ => Err(Failure::format_changed(
            "Louve Invest answered without a result",
        )),
    }
}

/// JsonNode.asText: a scalar's text, empty for containers and null.
fn json_text(value: &Value) -> String {
    match value {
        Value::String(s) => s.clone(),
        Value::Number(n) => n.to_string(),
        Value::Bool(b) => b.to_string(),
        _ => String::new(),
    }
}

/// JsonNode.asBoolean(false): true, a non-zero number or the text "true".
fn as_boolean(value: &Value) -> bool {
    match value {
        Value::Bool(b) => *b,
        Value::Number(n) => n.as_i64().is_some_and(|v| v != 0),
        Value::String(s) => s.trim() == "true",
        _ => false,
    }
}

/// The JSON-RPC message answering id, from a JSON body or an SSE stream.
fn answer_to(response: &transport::Response, id: i64) -> LouveResult<Value> {
    let payloads = if response
        .content_type
        .to_ascii_lowercase()
        .starts_with("text/event-stream")
    {
        sse_data(&response.body)
    } else {
        vec![response.body.clone()]
    };
    for payload in payloads {
        if payload.trim().is_empty() {
            continue;
        }
        let node: Value = serde_json::from_str(&payload)
            .map_err(|_| Failure::format_changed("Louve Invest sent an unreadable message"))?;
        let messages = match node {
            Value::Array(items) => items,
            other => vec![other],
        };
        if let Some(message) = messages
            .into_iter()
            .find(|m| m.get("id").and_then(Value::as_i64) == Some(id))
        {
            return Ok(message);
        }
    }
    Err(Failure::format_changed(
        "Louve Invest did not answer the request",
    ))
}

/// The data of each event of an SSE body, multi-line data joined with \n. Lines are split on \n
/// only, with one trailing \r dropped (CRLF streams).
fn sse_data(body: &str) -> Vec<String> {
    let mut events = Vec::new();
    let mut data: Option<String> = None;
    for raw in body.split('\n') {
        let line = raw.strip_suffix('\r').unwrap_or(raw);
        if line.is_empty() {
            if let Some(event) = data.take() {
                events.push(event);
            }
            continue;
        }
        if line.starts_with(':') {
            continue;
        }
        let (field, value) = line.split_once(':').unwrap_or((line, ""));
        let value = value.strip_prefix(' ').unwrap_or(value);
        if field == "data" {
            match &mut data {
                Some(existing) => {
                    existing.push('\n');
                    existing.push_str(value);
                }
                None => data = Some(value.to_owned()),
            }
        }
    }
    events.extend(data);
    events
}

fn status_error(status: u16) -> Failure {
    tracing::warn!("Louve Invest MCP request failed (status={status})");
    match status {
        // insufficient_scope: the grant no longer covers the portfolio, only a new consent helps.
        403 => Failure::sync(
            SidecarErrorCode::SessionExpired,
            "Louve Invest no longer grants access to the portfolio",
        ),
        429 => Failure::rate_limited(),
        // 404 on a session id means Louve dropped the MCP session; the next run opens a new one.
        404 | 500.. => Failure::unavailable(),
        _ => Failure::format_changed("Louve Invest refused the request"),
    }
}

#[cfg(test)]
mod tests {
    use super::sse_data;

    #[test]
    fn sse_data_splits_on_newlines_only_and_joins_multi_line_data() {
        let body = ": comment\r\nevent: message\r\ndata: {\"a\":\r\ndata: \"x\u{2028}y\"}\r\n\r\ndata: last";
        assert_eq!(
            sse_data(body),
            vec!["{\"a\":\n\"x\u{2028}y\"}".to_owned(), "last".to_owned()]
        );
    }
}
