//! The admin's "Test" button (AiConfigProvider.test) and the AI call log (AiCallLogService).

use std::collections::HashMap;
use std::time::Duration;

use axum::extract::{Query, State};
use chrono::{DateTime, Utc};
use serde::Serialize;
use serde_json::json;
use serde_with::skip_serializing_none;

use super::settings::{AiConfig, AiProtocol, AiProvider, AiRequest, ai_config};
use crate::AppState;
use crate::ai::provider::{ANTHROPIC_MAX_TOKENS, ANTHROPIC_VERSION, MODELS_TIMEOUT};
use crate::error::{AppError, AppResult};
use crate::http::json::Json;

const TEST_PROMPT: &str = "Reply with the single word: OK";
const TIMEOUT: Duration = Duration::from_secs(60);

#[derive(Serialize)]
pub struct AiTestResponse {
    ok: bool,
    message: String,
}

#[skip_serializing_none]
#[derive(Serialize)]
pub struct AiModelsResponse {
    ok: bool,
    message: Option<String>,
    models: Vec<String>,
}

fn result(ok: bool, message: impl Into<String>) -> axum::Json<AiTestResponse> {
    axum::Json(AiTestResponse {
        ok,
        message: message.into(),
    })
}

fn models_ok(models: Vec<String>) -> axum::Json<AiModelsResponse> {
    axum::Json(AiModelsResponse {
        ok: true,
        message: None,
        models,
    })
}

fn models_err(message: impl Into<String>) -> axum::Json<AiModelsResponse> {
    axum::Json(AiModelsResponse {
        ok: false,
        message: Some(message.into()),
        models: Vec::new(),
    })
}

/// Resolves the API key to send for an admin probe (/ai/test or /ai/models):
/// the submitted key if non-blank, else the stored key only when the provider matches.
async fn resolve_api_key(
    state: &AppState,
    provider: AiProvider,
    submitted: Option<&str>,
) -> AppResult<Option<String>> {
    if let Some(key) = submitted.map(str::trim).filter(|k| !k.is_empty()) {
        return Ok(Some(key.to_owned()));
    }
    let stored = ai_config(state)
        .await?
        .filter(|cfg| cfg.provider == provider)
        .and_then(|cfg| cfg.api_key)
        .map(|k| k.trim().to_owned())
        .filter(|k| !k.is_empty());
    Ok(stored)
}

/// The text answer of one chat call, or the reason it failed. Provider bodies are never shown.
async fn chat(state: &AppState, cfg: &AiConfig) -> Result<Option<String>, String> {
    let root = cfg.api_root();
    let model = cfg.effective_model();
    let key = cfg
        .api_key
        .as_deref()
        .map(str::trim)
        .filter(|k| !k.is_empty());
    let messages = json!([{"role": "user", "content": TEST_PROMPT}]);
    let request = match cfg.provider.protocol() {
        AiProtocol::OpenAiChat => {
            let req = state.http.post(format!("{root}/chat/completions"));
            let req = match key {
                Some(k) => req.bearer_auth(k),
                None => req,
            };
            req.json(&json!({"model": model, "messages": messages}))
        }
        AiProtocol::AnthropicMessages => state
            .http
            .post(format!("{root}/messages"))
            .header("x-api-key", key.unwrap_or(""))
            .header("anthropic-version", ANTHROPIC_VERSION)
            .json(
                &json!({"model": model, "max_tokens": ANTHROPIC_MAX_TOKENS, "messages": messages}),
            ),
        AiProtocol::Ollama => state
            .http
            .post(format!("{root}/chat"))
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
    Ok(crate::ai::provider::extract_response(cfg.provider, &body).text)
}

/// Tests the submitted settings without saving them; a blank key reuses the stored one only for the same provider.
pub async fn test(
    State(state): State<AppState>,
    Json(req): Json<AiRequest>,
) -> AppResult<axum::Json<AiTestResponse>> {
    let Some(provider) = AiProvider::from_key(req.provider.as_deref()) else {
        return Ok(result(false, "No provider selected."));
    };
    let key = resolve_api_key(&state, provider, req.api_key.as_deref()).await?;
    if provider.key_required() && key.is_none() {
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
    if cfg.effective_base_url().is_empty() {
        return Ok(result(
            false,
            format!("A base URL is required for {}.", provider.name()),
        ));
    }
    if cfg.effective_model().is_empty() {
        return Ok(result(
            false,
            format!("A model is required for {}.", provider.name()),
        ));
    }
    Ok(match chat(&state, &cfg).await {
        Ok(Some(reply)) => result(true, format!("Connected. Model replied: {}", reply.trim())),
        Ok(None) => result(false, "Connected, but the model returned no text answer."),
        Err(message) => result(false, message),
    })
}

/// Lists the models offered by the submitted provider without saving settings.
pub async fn models(
    State(state): State<AppState>,
    Json(req): Json<AiRequest>,
) -> AppResult<axum::Json<AiModelsResponse>> {
    let Some(provider) = AiProvider::from_key(req.provider.as_deref()) else {
        return Ok(models_err("No provider selected."));
    };
    let key = resolve_api_key(&state, provider, req.api_key.as_deref()).await?;
    if provider.key_required() && key.is_none() {
        return Ok(models_err(format!(
            "An API key is required for {}.",
            provider.name()
        )));
    }
    let cfg = AiConfig {
        provider,
        api_key: key,
        model: String::new(),
        base_url: req.base_url.unwrap_or_default(),
    };
    if cfg.effective_base_url().is_empty() {
        return Ok(models_err(format!(
            "A base URL is required for {}.",
            provider.name()
        )));
    }
    Ok(
        match crate::ai::provider::list_models(&state.admin.ai_http, &cfg, MODELS_TIMEOUT).await {
            Ok(list) => models_ok(list),
            Err(message) => models_err(message),
        },
    )
}

#[skip_serializing_none]
#[derive(Serialize, crate::db::DbFromRow)]
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
    let items: Vec<AiCallLogResponse> = crate::db::query_as(
        "SELECT id, created_at, member_id, transaction_id, merchant_label, batch_id::text AS batch_id, provider, model, prompt, \
         response, prompt_tokens, completion_tokens, total_tokens, latency_ms, status, error, chosen_slug, \
         confidence, applied FROM ai_call_log ORDER BY created_at DESC LIMIT $1 OFFSET $2",
    )
    .bind(i64::from(limit))
    .bind(i64::from(page) * i64::from(limit))
    .fetch_all(&state.db)
    .await?;
    let (total, total_tokens): (i64, i64) = crate::db::query_as(
        "SELECT count(*), COALESCE(sum(total_tokens), 0)::bigint FROM ai_call_log",
    )
    .fetch_one(&state.db)
    .await?;
    Ok(axum::Json(AiCallLogPage {
        items,
        total,
        total_tokens,
    }))
}
