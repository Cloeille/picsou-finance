//! Provider-specific HTTP transport for OpenAI, OpenRouter, Anthropic, and Ollama over reqwest.

use std::time::Duration;

use serde_json::{Value, json};

use crate::admin::settings::{AiConfig, AiProvider};

/// Caps thinking + answer for Anthropic Messages API (which requires max_tokens).
/// Matches Java's AiChatModelFactory.ANTHROPIC_MAX_TOKENS.
pub(crate) const ANTHROPIC_MAX_TOKENS: u32 = 4096;
pub(crate) const ANTHROPIC_VERSION: &str = "2023-06-01";
pub(crate) const DEFAULT_TIMEOUT: Duration = Duration::from_secs(60);

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
    let base = cfg.effective_base_url();
    let base = base.trim_end_matches('/');
    let model = cfg.effective_model();
    let key = cfg.api_key.as_deref().unwrap_or("");

    let request = match cfg.provider {
        AiProvider::OpenAi | AiProvider::OpenRouter => http
            .post(format!("{base}/v1/chat/completions"))
            .bearer_auth(key)
            .json(&json!({
                "model": model,
                "messages": [
                    {"role": "system", "content": system},
                    {"role": "user", "content": user_prompt},
                ],
            })),
        AiProvider::Anthropic => http
            .post(format!("{base}/v1/messages"))
            .header("x-api-key", key)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .json(&json!({
                "model": model,
                "max_tokens": ANTHROPIC_MAX_TOKENS,
                "system": system,
                "messages": [
                    {"role": "user", "content": user_prompt},
                ],
            })),
        AiProvider::Ollama => http.post(format!("{base}/api/chat")).json(&json!({
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
        return Err(format!("The provider answered HTTP {}", status.as_u16()));
    }

    let body: Value = response
        .json()
        .await
        .map_err(|_| "The provider returned an unreadable answer".to_owned())?;

    Ok(extract_response(cfg.provider, &body))
}

pub(crate) fn extract_response(provider: AiProvider, body: &Value) -> ProviderResponse {
    match provider {
        AiProvider::OpenAi | AiProvider::OpenRouter => {
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
        AiProvider::Anthropic => {
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
        AiProvider::Ollama => {
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
}
