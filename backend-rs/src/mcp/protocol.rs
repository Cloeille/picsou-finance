//! JSON-RPC 2.0 and MCP protocol dispatch (`2025-11-25`, with backward compatibility for
//! `2025-06-18`, `2025-03-26`, and `2024-11-05`).

use serde::Serialize;
use serde_json::{Value, json};

use crate::AppState;
use crate::auth::Auth;
use crate::mcp::ProtocolMode;
use crate::mcp::tools::{self, ToolError};

pub const LATEST_PROTOCOL_VERSION: &str = "2025-11-25";
pub const LEGACY_SSE_PROTOCOL_VERSION: &str = "2024-11-05";

pub const SUPPORTED_PROTOCOL_VERSIONS: &[&str] =
    &["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];

pub const SERVER_NAME: &str = "picsou";

pub const SERVER_INSTRUCTIONS: &str = "Picsou personal-finance MCP server. Every tool operates only on the financial data of the member who owns the access-key used to authenticate: it can never read or modify another member's data, and it cannot perform authentication, credential, admin, member-management, MFA, or data-export operations. Monetary values are in each account's own currency unless the field name ends in \"Eur\".";

pub const ERR_PARSE: i64 = -32700;
pub const ERR_INVALID_REQUEST: i64 = -32600;
pub const ERR_METHOD_NOT_FOUND: i64 = -32601;
pub const ERR_INVALID_PARAMS: i64 = -32602;

#[derive(Debug, Clone)]
pub enum IncomingMessage {
    Request {
        id: Value,
        method: String,
        params: Value,
    },
    Notification {
        method: String,
    },
    /// Client response to a server-initiated request (such as keep-alive ping).
    ClientResponse,
}

#[derive(Debug, Serialize)]
pub struct JsonRpcResponse {
    pub jsonrpc: &'static str,
    pub id: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<JsonRpcError>,
}

#[derive(Debug, Serialize)]
pub struct JsonRpcError {
    pub code: i64,
    pub message: String,
}

impl JsonRpcResponse {
    pub fn ok(id: Value, result: Value) -> Self {
        Self {
            jsonrpc: "2.0",
            id,
            result: Some(result),
            error: None,
        }
    }

    pub fn err(id: Value, code: i64, message: impl Into<String>) -> Self {
        Self {
            jsonrpc: "2.0",
            id,
            result: None,
            error: Some(JsonRpcError {
                code,
                message: message.into(),
            }),
        }
    }
}

/// Parses a JSON-RPC 2.0 payload into a request, notification, or client response.
pub fn parse_message(raw: &[u8]) -> Result<IncomingMessage, Box<JsonRpcResponse>> {
    let value: Value = serde_json::from_slice(raw)
        .map_err(|_| Box::new(JsonRpcResponse::err(Value::Null, ERR_PARSE, "Parse error")))?;
    let Some(obj) = value.as_object() else {
        return Err(Box::new(JsonRpcResponse::err(
            Value::Null,
            ERR_INVALID_REQUEST,
            "Invalid Request",
        )));
    };
    if obj.get("jsonrpc").and_then(Value::as_str) != Some("2.0") {
        let id = obj.get("id").cloned().unwrap_or(Value::Null);
        return Err(Box::new(JsonRpcResponse::err(
            id,
            ERR_INVALID_REQUEST,
            "Invalid Request: jsonrpc must be \"2.0\"",
        )));
    }
    let method = obj.get("method").and_then(Value::as_str).map(str::to_owned);
    let has_id = obj.contains_key("id") && !obj["id"].is_null();
    match (method, has_id) {
        (Some(method), true) => Ok(IncomingMessage::Request {
            id: obj["id"].clone(),
            method,
            params: obj.get("params").cloned().unwrap_or_else(|| json!({})),
        }),
        (Some(method), false) => Ok(IncomingMessage::Notification { method }),
        (None, _) if obj.contains_key("result") || obj.contains_key("error") => {
            Ok(IncomingMessage::ClientResponse)
        }
        (None, _) => Err(Box::new(JsonRpcResponse::err(
            obj.get("id").cloned().unwrap_or(Value::Null),
            ERR_INVALID_REQUEST,
            "Invalid Request: missing method",
        ))),
    }
}

pub fn negotiate_version(requested: Option<&str>, mode: ProtocolMode) -> &'static str {
    if let Some(req) = requested
        && let Some(&matched) = SUPPORTED_PROTOCOL_VERSIONS.iter().find(|&&v| v == req)
    {
        return matched;
    }
    match mode {
        ProtocolMode::Streamable => LATEST_PROTOCOL_VERSION,
        ProtocolMode::Sse => LEGACY_SSE_PROTOCOL_VERSION,
    }
}

pub fn handle_initialize(
    state: &AppState,
    id: Value,
    params: &Value,
    mode: ProtocolMode,
) -> (JsonRpcResponse, &'static str) {
    let requested = params.get("protocolVersion").and_then(Value::as_str);
    let version = negotiate_version(requested, mode);
    let result = json!({
        "protocolVersion": version,
        "capabilities": {
            "tools": {
                "listChanged": false
            }
        },
        "serverInfo": {
            "name": SERVER_NAME,
            "version": state.config.app_version,
        },
        "instructions": SERVER_INSTRUCTIONS,
    });
    (JsonRpcResponse::ok(id, result), version)
}

pub async fn handle_request(
    state: &AppState,
    auth: &Auth,
    id: Value,
    method: &str,
    params: Value,
    mode: ProtocolMode,
) -> JsonRpcResponse {
    match method {
        "initialize" => handle_initialize(state, id, &params, mode).0,
        "ping" => JsonRpcResponse::ok(id, json!({})),
        "tools/list" => JsonRpcResponse::ok(id, json!({ "tools": tools::list_json() })),
        "tools/call" => handle_tools_call(state, auth, id, params).await,
        other => JsonRpcResponse::err(
            id,
            ERR_METHOD_NOT_FOUND,
            format!("Method not found: {other}"),
        ),
    }
}

async fn handle_tools_call(
    state: &AppState,
    auth: &Auth,
    id: Value,
    params: Value,
) -> JsonRpcResponse {
    let Some(name) = params.get("name").and_then(Value::as_str) else {
        return JsonRpcResponse::err(id, ERR_INVALID_PARAMS, "Missing required parameter: name");
    };
    let arguments = match params.get("arguments") {
        None | Some(Value::Null) => Value::Object(serde_json::Map::new()),
        Some(obj @ Value::Object(_)) => obj.clone(),
        Some(_) => {
            return JsonRpcResponse::err(
                id,
                ERR_INVALID_PARAMS,
                "Parameter 'arguments' must be an object",
            );
        }
    };
    let timeout = state.mcp.tool_call_timeout();
    let outcome =
        match tokio::time::timeout(timeout, tools::call(state, auth, name, arguments)).await {
            Ok(res) => res,
            Err(_) => {
                tracing::warn!(
                    tool = name,
                    timeout_ms = u64::try_from(timeout.as_millis()).unwrap_or(u64::MAX),
                    "MCP tool call timed out"
                );
                Err(ToolError::Execution(format!("Tool '{name}' timed out")))
            }
        };
    match outcome {
        Ok(text) => JsonRpcResponse::ok(
            id,
            json!({
                "content": [{ "type": "text", "text": text }],
                "isError": false,
            }),
        ),
        Err(ToolError::UnknownTool(unknown)) => {
            JsonRpcResponse::err(id, ERR_INVALID_PARAMS, format!("Unknown tool: {unknown}"))
        }
        Err(ToolError::Execution(message)) => JsonRpcResponse::ok(
            id,
            json!({
                "content": [{ "type": "text", "text": message }],
                "isError": true,
            }),
        ),
    }
}
