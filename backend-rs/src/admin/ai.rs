//! The admin's "Test" button (AiConfigProvider.test) and the AI call log (AiCallLogService).

use std::collections::HashMap;
use std::time::Duration;

use axum::extract::{Query, State};
use chrono::{DateTime, Utc};
use serde::Serialize;
use serde_json::{Value, json};
use serde_with::skip_serializing_none;

use super::settings::{AiConfig, AiProvider, AiRequest, ai_config};
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::http::json::Json;

const TEST_PROMPT: &str = "Reply with the single word: OK";
const ANTHROPIC_MAX_TOKENS: u32 = 4096;
const TIMEOUT: Duration = Duration::from_secs(60);

#[derive(Serialize)]
pub struct AiTestResponse {
    ok: bool,
    message: String,
}

fn result(ok: bool, message: impl Into<String>) -> axum::Json<AiTestResponse> {
    axum::Json(AiTestResponse {
        ok,
        message: message.into(),
    })
}

/// The text answer of one chat call, or the reason it failed. Provider bodies are never shown.
async fn chat(state: &AppState, cfg: &AiConfig) -> Result<Option<String>, String> {
    let base = cfg.effective_base_url();
    let base = base.trim_end_matches('/');
    let model = cfg.effective_model();
    let key = cfg.api_key.clone().unwrap_or_default();
    let messages = json!([{"role": "user", "content": TEST_PROMPT}]);
    let request = match cfg.provider {
        AiProvider::OpenAi | AiProvider::OpenRouter => state
            .http
            .post(format!("{base}/v1/chat/completions"))
            .bearer_auth(&key)
            .json(&json!({"model": model, "messages": messages})),
        AiProvider::Anthropic => state
            .http
            .post(format!("{base}/v1/messages"))
            .header("x-api-key", &key)
            .header("anthropic-version", "2023-06-01")
            .json(
                &json!({"model": model, "max_tokens": ANTHROPIC_MAX_TOKENS, "messages": messages}),
            ),
        AiProvider::Ollama => state
            .http
            .post(format!("{base}/api/chat"))
            .json(&json!({"model": model, "messages": messages, "stream": false})),
    };
    let response = request
        .timeout(TIMEOUT)
        .send()
        .await
        .map_err(|e| format!("Connection failed: {}", e.without_url()))?;
    let status = response.status();
    if !status.is_success() {
        return Err(format!("The provider answered HTTP {}", status.as_u16()));
    }
    let body = crate::ai::provider::read_json_capped(response).await?;
    let text = match cfg.provider {
        AiProvider::OpenAi | AiProvider::OpenRouter => body
            .pointer("/choices/0/message/content")
            .and_then(Value::as_str)
            .map(str::to_owned),
        // A thinking model puts its thinking first: the answer is the first text block.
        AiProvider::Anthropic => body
            .get("content")
            .and_then(Value::as_array)
            .and_then(|blocks| {
                blocks
                    .iter()
                    .filter(|b| b.get("type").and_then(Value::as_str) == Some("text"))
                    .find_map(|b| b.get("text").and_then(Value::as_str))
            })
            .map(str::to_owned),
        AiProvider::Ollama => body
            .pointer("/message/content")
            .and_then(Value::as_str)
            .map(str::to_owned),
    };
    Ok(text.filter(|t| !t.trim().is_empty()))
}

/// Tests the submitted settings without saving them; a blank key reuses the stored one.
pub async fn test(
    State(state): State<AppState>,
    Json(req): Json<AiRequest>,
) -> AppResult<axum::Json<AiTestResponse>> {
    let Some(provider) = AiProvider::from_key(req.provider.as_deref()) else {
        return Ok(result(false, "No provider selected."));
    };
    let key = match req.api_key.filter(|k| !k.trim().is_empty()) {
        Some(k) => Some(k),
        None => ai_config(&state).await?.and_then(|c| c.api_key),
    };
    if provider.key_required() && key.as_deref().is_none_or(|k| k.trim().is_empty()) {
        return Ok(result(
            false,
            format!("An API key is required for {}.", provider.name()),
        ));
    }
    let cfg = AiConfig {
        provider,
        api_key: key,
        model: req.model.unwrap_or_default(),
        base_url: req.base_url.unwrap_or_default(),
    };
    Ok(match chat(&state, &cfg).await {
        Ok(Some(reply)) => result(true, format!("Connected. Model replied: {}", reply.trim())),
        Ok(None) => result(false, "Connected, but the model returned no text answer."),
        Err(message) => result(false, message),
    })
}

#[skip_serializing_none]
#[derive(Serialize, sqlx::FromRow)]
#[serde(rename_all = "camelCase")]
pub struct AiCallLogResponse {
    id: i64,
    created_at: DateTime<Utc>,
    member_id: Option<i64>,
    transaction_id: Option<i64>,
    merchant_label: Option<String>,
    batch_id: Option<String>,
    provider: String,
    model: Option<String>,
    prompt: Option<String>,
    response: Option<String>,
    prompt_tokens: Option<i32>,
    completion_tokens: Option<i32>,
    total_tokens: Option<i32>,
    latency_ms: Option<i32>,
    status: String,
    error: Option<String>,
    chosen_slug: Option<String>,
    confidence: Option<i32>,
    applied: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AiCallLogPage {
    items: Vec<AiCallLogResponse>,
    total: i64,
    total_tokens: i64,
}

fn int_param(query: &HashMap<String, String>, name: &str, default: i32) -> AppResult<i32> {
    match query.get(name) {
        None => Ok(default),
        Some(raw) => raw.trim().parse().map_err(|_| {
            AppError::BadRequest(format!("Failed to convert '{name}' with value: '{raw}'"))
        }),
    }
}

pub async fn calls(
    State(state): State<AppState>,
    Query(query): Query<HashMap<String, String>>,
) -> AppResult<axum::Json<AiCallLogPage>> {
    let limit = int_param(&query, "limit", 50)?.clamp(1, 200);
    let offset = int_param(&query, "offset", 0)?.max(0);
    let page = offset / limit;
    let items: Vec<AiCallLogResponse> = sqlx::query_as(
        "SELECT id, created_at, member_id, transaction_id, merchant_label, batch_id::text AS batch_id, provider, model, prompt, \
         response, prompt_tokens, completion_tokens, total_tokens, latency_ms, status, error, chosen_slug, \
         confidence, applied FROM ai_call_log ORDER BY created_at DESC LIMIT $1 OFFSET $2",
    )
    .bind(i64::from(limit))
    .bind(i64::from(page) * i64::from(limit))
    .fetch_all(&state.db)
    .await?;
    let (total, total_tokens): (i64, i64) =
        sqlx::query_as("SELECT count(*), COALESCE(sum(total_tokens), 0)::bigint FROM ai_call_log")
            .fetch_one(&state.db)
            .await?;
    Ok(axum::Json(AiCallLogPage {
        items,
        total,
        total_tokens,
    }))
}
