//! Provider-specific HTTP transport for AI chat and model listing over reqwest.

use std::time::Duration;

use serde_json::{Value, json};

use crate::admin::settings::{AiConfig, AiProtocol, AiProvider};

/// Caps thinking + answer for Anthropic Messages API (which requires max_tokens).
/// Matches Java's AiChatModelFactory.ANTHROPIC_MAX_TOKENS.
pub(crate) const ANTHROPIC_MAX_TOKENS: u32 = 4096;
pub(crate) const ANTHROPIC_VERSION: &str = "2023-06-01";
pub(crate) const DEFAULT_TIMEOUT: Duration = Duration::from_secs(60);
pub(crate) const MODELS_TIMEOUT: Duration = Duration::from_secs(15);
/// Hard cap on upstream AI provider JSON response bodies (1 MiB) before parsing.
pub(crate) const MAX_RESPONSE_BYTES: usize = 1024 * 1024;
/// Bounded retries for transient upstream rate-limit / overload responses (HTTP 429 & 529).
const MAX_RATE_LIMIT_RETRIES: usize = 2;
const MAX_RETRY_AFTER_SECS: u64 = 10;

/// Builds a dedicated HTTP client for AI provider calls that never follows redirects so
/// `x-api-key` and `Authorization` headers cannot be forwarded to another host.
pub(crate) fn build_http_client() -> reqwest::Client {
    reqwest::Client::builder()
        .user_agent(concat!("picsou/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(Duration::from_secs(10))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .expect("AI provider HTTP client builds")
}

fn is_retryable_status(status: reqwest::StatusCode) -> bool {
    status == reqwest::StatusCode::TOO_MANY_REQUESTS || status.as_u16() == 529
}

fn retry_delay(attempt: usize, retry_after_header: Option<&str>) -> Duration {
    if let Some(secs) = retry_after_header.and_then(|v| v.trim().parse::<u64>().ok()) {
        return Duration::from_secs(secs.clamp(1, MAX_RETRY_AFTER_SECS));
    }
    match attempt {
        0 => Duration::from_millis(1500),
        _ => Duration::from_millis(3500),
    }
}

/// Reads an upstream AI HTTP response body up to `MAX_RESPONSE_BYTES` and parses it as JSON.
pub(crate) async fn read_json_capped(mut response: reqwest::Response) -> Result<Value, String> {
    let unreadable = || "The provider returned an unreadable answer".to_owned();
    if response
        .content_length()
        .is_some_and(|len| len > MAX_RESPONSE_BYTES as u64)
    {
        return Err(unreadable());
    }
    let mut buf = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| unreadable())? {
        if buf.len().saturating_add(chunk.len()) > MAX_RESPONSE_BYTES {
            return Err(unreadable());
        }
        buf.extend_from_slice(&chunk);
    }
    serde_json::from_slice(&buf).map_err(|_| unreadable())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ProviderResponse {
    pub text: Option<String>,
    pub prompt_tokens: Option<i32>,
    pub completion_tokens: Option<i32>,
    pub total_tokens: Option<i32>,
}

/// Sends a chat completion request to the configured provider and extracts text + token usage.
/// Upstream error bodies and credentials are never returned in error messages.
pub(crate) async fn call_provider(
    http: &reqwest::Client,
    cfg: &AiConfig,
    system: &str,
    user_prompt: &str,
    timeout: Duration,
) -> Result<ProviderResponse, String> {
    let root = cfg.api_root();
    let model = cfg.effective_model();
    let key = cfg
        .api_key
        .as_deref()
        .map(str::trim)
        .filter(|k| !k.is_empty());

    for attempt in 0..=MAX_RATE_LIMIT_RETRIES {
        let request = match cfg.provider.protocol() {
            AiProtocol::OpenAiChat => {
                let req = http.post(format!("{root}/chat/completions"));
                let req = match key {
                    Some(k) => req.bearer_auth(k),
                    None => req,
                };
                req.json(&json!({
                    "model": model,
                    "messages": [
                        {"role": "system", "content": system},
                        {"role": "user", "content": user_prompt},
                    ],
                }))
            }
            AiProtocol::AnthropicMessages => http
                .post(format!("{root}/messages"))
                .header("x-api-key", key.unwrap_or(""))
                .header("anthropic-version", ANTHROPIC_VERSION)
                .json(&json!({
                    "model": model,
                    "max_tokens": ANTHROPIC_MAX_TOKENS,
                    "system": system,
                    "messages": [
                        {"role": "user", "content": user_prompt},
                    ],
                })),
            AiProtocol::Ollama => http.post(format!("{root}/chat")).json(&json!({
                "model": model,
                "messages": [
                    {"role": "system", "content": system},
                    {"role": "user", "content": user_prompt},
                ],
                "stream": false,
            })),
        };

        let response = request
            .timeout(timeout)
            .send()
            .await
            .map_err(|e| format!("Connection failed: {}", e.without_url()))?;

        let status = response.status();
        if !status.is_success() {
            if is_retryable_status(status) && attempt < MAX_RATE_LIMIT_RETRIES {
                let retry_after = response
                    .headers()
                    .get(reqwest::header::RETRY_AFTER)
                    .and_then(|v| v.to_str().ok());
                let delay = retry_delay(attempt, retry_after);
                tokio::time::sleep(delay).await;
                continue;
            }
            return Err(format!("The provider answered HTTP {}", status.as_u16()));
        }

        let body = read_json_capped(response).await?;
        return Ok(extract_response(cfg.provider, &body));
    }

    Err("The provider answered HTTP 429".to_owned())
}

/// Fetches and normalizes the list of model identifiers offered by the configured provider.
/// Upstream error bodies, URLs, and credentials are never returned in error messages.
pub(crate) async fn list_models(
    http: &reqwest::Client,
    cfg: &AiConfig,
    timeout: Duration,
) -> Result<Vec<String>, String> {
    let root = cfg.api_root();
    let key = cfg
        .api_key
        .as_deref()
        .map(str::trim)
        .filter(|k| !k.is_empty());

    let request = match cfg.provider.protocol() {
        AiProtocol::OpenAiChat => {
            let req = http.get(format!("{root}/models"));
            match key {
                Some(k) => req.bearer_auth(k),
                None => req,
            }
        }
        AiProtocol::AnthropicMessages => http
            .get(format!("{root}/models?limit=1000"))
            .header("x-api-key", key.unwrap_or(""))
            .header("anthropic-version", ANTHROPIC_VERSION),
        AiProtocol::Ollama => http.get(format!("{root}/tags")),
    };

    let response = request
        .timeout(timeout)
        .send()
        .await
        .map_err(|e| format!("Connection failed: {}", e.without_url()))?;

    let status = response.status();
    if !status.is_success() {
        return Err(format!("The provider answered HTTP {}", status.as_u16()));
    }

    let body = read_json_capped(response).await?;
    extract_models(cfg.provider.protocol(), &body)
}

pub(crate) fn extract_models(protocol: AiProtocol, body: &Value) -> Result<Vec<String>, String> {
    let unreadable = || "The provider returned an unreadable answer".to_owned();
    let (list_key, id_key) = match protocol {
        AiProtocol::OpenAiChat | AiProtocol::AnthropicMessages => ("data", "id"),
        AiProtocol::Ollama => ("models", "name"),
    };
    let items = body
        .get(list_key)
        .and_then(Value::as_array)
        .ok_or_else(unreadable)?;
    let mut models: Vec<String> = items
        .iter()
        .filter_map(|item| {
            item.get(id_key)
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_owned)
        })
        .collect();
    models.sort_by(|a, b| {
        a.to_ascii_lowercase()
            .cmp(&b.to_ascii_lowercase())
            .then_with(|| a.cmp(b))
    });
    models.dedup();
    Ok(models)
}

pub(crate) fn extract_response(provider: AiProvider, body: &Value) -> ProviderResponse {
    match provider.protocol() {
        AiProtocol::OpenAiChat => {
            let text = body
                .get("choices")
                .and_then(Value::as_array)
                .and_then(|choices| {
                    choices.iter().find_map(|c| {
                        c.pointer("/message/content")
                            .and_then(Value::as_str)
                            .filter(|s| !s.trim().is_empty())
                    })
                })
                .map(str::to_owned);
            let pt = body.pointer("/usage/prompt_tokens").and_then(json_as_i32);
            let ct = body
                .pointer("/usage/completion_tokens")
                .and_then(json_as_i32);
            let tt = resolve_total(
                body.pointer("/usage/total_tokens").and_then(json_as_i32),
                pt,
                ct,
            );
            ProviderResponse {
                text,
                prompt_tokens: pt,
                completion_tokens: ct,
                total_tokens: tt,
            }
        }
        AiProtocol::AnthropicMessages => {
            // Skip thinking / redacted_thinking blocks: take the first text block with non-blank text.
            let text = body
                .get("content")
                .and_then(Value::as_array)
                .and_then(|blocks| {
                    blocks.iter().find_map(|b| {
                        if b.get("type").and_then(Value::as_str) != Some("text") {
                            return None;
                        }
                        b.get("text")
                            .and_then(Value::as_str)
                            .filter(|s| !s.trim().is_empty())
                    })
                })
                .map(str::to_owned);
            let pt = body.pointer("/usage/input_tokens").and_then(json_as_i32);
            let ct = body.pointer("/usage/output_tokens").and_then(json_as_i32);
            let tt = resolve_total(
                body.pointer("/usage/total_tokens").and_then(json_as_i32),
                pt,
                ct,
            );
            ProviderResponse {
                text,
                prompt_tokens: pt,
                completion_tokens: ct,
                total_tokens: tt,
            }
        }
        AiProtocol::Ollama => {
            let text = body
                .pointer("/message/content")
                .and_then(Value::as_str)
                .filter(|s| !s.trim().is_empty())
                .map(str::to_owned);
            let pt = body.get("prompt_eval_count").and_then(json_as_i32);
            let ct = body.get("eval_count").and_then(json_as_i32);
            let tt = resolve_total(None, pt, ct);
            ProviderResponse {
                text,
                prompt_tokens: pt,
                completion_tokens: ct,
                total_tokens: tt,
            }
        }
    }
}

fn json_as_i32(v: &Value) -> Option<i32> {
    v.as_i64()
        .and_then(|n| i32::try_from(n).ok())
        .or_else(|| v.as_u64().and_then(|n| i32::try_from(n).ok()))
}

fn resolve_total(explicit: Option<i32>, pt: Option<i32>, ct: Option<i32>) -> Option<i32> {
    explicit.or_else(|| {
        if pt.is_some() || ct.is_some() {
            Some(pt.unwrap_or(0).saturating_add(ct.unwrap_or(0)))
        } else {
            None
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_anthropic_text_past_thinking_and_redacted_blocks() {
        let body = json!({
            "content": [
                {"type": "thinking", "thinking": "Looks like groceries", "signature": "sig-1"},
                {"type": "redacted_thinking", "data": "opaque"},
                {"type": "text", "text": "{\"categorySlug\": \"groceries\", \"confidence\": 0.9}"}
            ],
            "usage": {"input_tokens": 120, "output_tokens": 30}
        });
        let r = extract_response(AiProvider::Anthropic, &body);
        assert_eq!(
            r,
            ProviderResponse {
                text: Some(r#"{"categorySlug": "groceries", "confidence": 0.9}"#.to_owned()),
                prompt_tokens: Some(120),
                completion_tokens: Some(30),
                total_tokens: Some(150),
            }
        );
    }

    #[test]
    fn anthropic_thinking_only_returns_no_text_while_preserving_usage() {
        let body = json!({
            "content": [
                {"type": "thinking", "thinking": "ran out of budget", "signature": "sig-1"}
            ],
            "usage": {"input_tokens": 80, "output_tokens": 4096}
        });
        let r = extract_response(AiProvider::Anthropic, &body);
        assert_eq!(r.text, None);
        assert_eq!(r.prompt_tokens, Some(80));
        assert_eq!(r.completion_tokens, Some(4096));
        assert_eq!(r.total_tokens, Some(4176));
    }

    #[test]
    fn extracts_openai_and_ollama_usage() {
        let openai = json!({
            "choices": [
                {"message": {"content": "   "}},
                {"message": {"content": "{\"categorySlug\": \"dining\", \"confidence\": 0.8}"}}
            ],
            "usage": {"prompt_tokens": 50, "completion_tokens": 12, "total_tokens": 62}
        });
        let r = extract_response(AiProvider::OpenAi, &openai);
        assert_eq!(
            r.text.as_deref(),
            Some(r#"{"categorySlug": "dining", "confidence": 0.8}"#)
        );
        assert_eq!(r.total_tokens, Some(62));

        let ollama = json!({
            "message": {"role": "assistant", "content": "{\"categorySlug\": \"transport\", \"confidence\": 0.75}"},
            "prompt_eval_count": 90,
            "eval_count": 15
        });
        let r = extract_response(AiProvider::Ollama, &ollama);
        assert_eq!(
            r.text.as_deref(),
            Some(r#"{"categorySlug": "transport", "confidence": 0.75}"#)
        );
        assert_eq!(r.prompt_tokens, Some(90));
        assert_eq!(r.completion_tokens, Some(15));
        assert_eq!(r.total_tokens, Some(105));
    }

    #[test]
    fn retries_only_429_and_529_with_bounded_backoff() {
        assert!(is_retryable_status(reqwest::StatusCode::TOO_MANY_REQUESTS));
        assert!(is_retryable_status(
            reqwest::StatusCode::from_u16(529).unwrap()
        ));
        assert!(!is_retryable_status(
            reqwest::StatusCode::INTERNAL_SERVER_ERROR
        ));
        assert!(!is_retryable_status(reqwest::StatusCode::UNAUTHORIZED));

        assert_eq!(retry_delay(0, None), Duration::from_millis(1500));
        assert_eq!(retry_delay(1, None), Duration::from_millis(3500));
        assert_eq!(retry_delay(0, Some("3")), Duration::from_secs(3));
        assert_eq!(retry_delay(0, Some("120")), Duration::from_secs(10));
        assert_eq!(retry_delay(0, Some("0")), Duration::from_secs(1));
    }

    #[test]
    fn extracts_deduplicates_and_sorts_models_case_insensitively() {
        let openai = json!({
            "data": [
                {"id": "gpt-4o"},
                {"id": "Ada"},
                {"id": "gpt-4o"},
                {"id": "   "},
                {"id": "claude-3"},
                {"id": "b-model"}
            ]
        });
        assert_eq!(
            extract_models(AiProtocol::OpenAiChat, &openai).unwrap(),
            vec!["Ada", "b-model", "claude-3", "gpt-4o"]
        );

        let anthropic = json!({
            "data": [
                {"id": "claude-sonnet-4-5"},
                {"id": "claude-haiku-4-5"},
                {"id": "claude-haiku-4-5"}
            ]
        });
        assert_eq!(
            extract_models(AiProtocol::AnthropicMessages, &anthropic).unwrap(),
            vec!["claude-haiku-4-5", "claude-sonnet-4-5"]
        );

        let ollama = json!({
            "models": [
                {"name": "Qwen3:0.6b"},
                {"name": "llama3.2:latest"},
                {"name": "Qwen3:0.6b"}
            ]
        });
        assert_eq!(
            extract_models(AiProtocol::Ollama, &ollama).unwrap(),
            vec!["llama3.2:latest", "Qwen3:0.6b"]
        );

        assert!(extract_models(AiProtocol::OpenAiChat, &json!({})).is_err());
        assert!(extract_models(AiProtocol::Ollama, &json!({"data": []})).is_err());
    }
}
