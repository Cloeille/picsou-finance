//! AI transaction categorization (track T8).
//!
//! Implements Java's `DynamicTransactionCategorizer` + `SpringAiCategorizer` +
//! `AiChatModelFactory` + `AiConfigProvider` over plain `reqwest::Client`, reading the active
//! provider configuration dynamically from `app_setting` so admin changes take effect without
//! a restart.

mod parser;
mod prompt;
pub(crate) mod provider;

use std::collections::HashMap;
use std::time::{Duration, Instant};

use crate::db::Db;

use crate::admin::settings::{
    AI_API_KEY, AI_BASE_URL, AI_MAX_CONCURRENCY, AI_MODEL, AI_PROVIDER, AiConfig, AiProvider,
};
use crate::budget::ai_job::{
    AiCategorizer, CategorizationInput, CategorizationResult, CategoryOption, Example,
};
use crate::crypto::CryptoEncryption;
use crate::routes::Routes;

pub fn register(_routes: &mut Routes) {}

/// Runtime-configured AI categorizer backed by `app_setting` and `reqwest::Client`.
///
/// Mirrors Java's `DynamicTransactionCategorizer` + `AiConfigProvider` + `SpringAiCategorizer`:
/// resolves the active provider on each call so `PUT /api/admin/settings/ai` takes effect
/// immediately, abstains with `"DISABLED"` when unconfigured or missing a required API key,
/// and never propagates provider or parsing failures into the categorization job.
pub struct DynamicCategorizer {
    db: Db,
    crypto: CryptoEncryption,
    http: reqwest::Client,
    timeout: Duration,
}

impl DynamicCategorizer {
    pub fn new(db: Db, crypto: CryptoEncryption, http: reqwest::Client) -> Self {
        Self {
            db,
            crypto,
            http,
            timeout: provider::DEFAULT_TIMEOUT,
        }
    }

    pub fn with_timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }

    async fn read_settings(&self) -> Result<HashMap<String, String>, sqlx::Error> {
        let keys: &[&str] = &[
            AI_PROVIDER,
            AI_MODEL,
            AI_BASE_URL,
            AI_API_KEY,
            AI_MAX_CONCURRENCY,
        ];
        let rows: Vec<(String, String)> = crate::db::query_as(
            "SELECT setting_key, value FROM app_setting WHERE setting_key = ANY($1)",
        )
        .bind(keys)
        .fetch_all(&self.db)
        .await?;
        Ok(rows.into_iter().collect())
    }

    /// Reads the stored AI provider configuration and decrypts the API key, matching
    /// `AiConfigProvider.current()`.
    pub async fn current_config(&self) -> Result<Option<AiConfig>, String> {
        let settings = self
            .read_settings()
            .await
            .map_err(|_| "Failed to read AI configuration".to_owned())?;
        let Some(provider) = AiProvider::from_key(settings.get(AI_PROVIDER).map(String::as_str))
        else {
            return Ok(None);
        };
        let api_key = match settings.get(AI_API_KEY).filter(|k| !k.trim().is_empty()) {
            Some(enc) => Some(
                self.crypto
                    .decrypt(enc)
                    .map_err(|_| "Failed to decrypt stored AI API key".to_owned())?,
            ),
            None => None,
        };
        Ok(Some(AiConfig {
            provider,
            api_key,
            model: settings.get(AI_MODEL).cloned().unwrap_or_default(),
            base_url: settings.get(AI_BASE_URL).cloned().unwrap_or_default(),
        }))
    }
}

fn is_configured(cfg: &AiConfig) -> bool {
    !cfg.provider.key_required() || cfg.api_key.as_deref().is_some_and(|k| !k.trim().is_empty())
}

fn elapsed_ms(started: Instant) -> i32 {
    i32::try_from(started.elapsed().as_millis()).unwrap_or(i32::MAX)
}

#[async_trait::async_trait]
impl AiCategorizer for DynamicCategorizer {
    async fn max_concurrency(&self) -> usize {
        let raw = crate::setup_auth::settings::read_with(&self.db, AI_MAX_CONCURRENCY)
            .await
            .ok()
            .flatten();
        let value = raw.map_or(4, |v| v.trim().parse::<i32>().unwrap_or(4));
        usize::try_from(value.clamp(1, 16)).unwrap_or(4)
    }

    async fn provider(&self) -> (String, Option<String>) {
        match self.current_config().await {
            Ok(Some(cfg)) => (
                cfg.provider.name().to_ascii_lowercase(),
                Some(cfg.effective_model()),
            ),
            Ok(None) => ("none".to_owned(), None),
            Err(err) => {
                tracing::warn!("Failed to resolve AI provider config: {err}");
                ("none".to_owned(), None)
            }
        }
    }

    async fn categorize(
        &self,
        input: &CategorizationInput,
        categories: &[CategoryOption],
        examples: &[Example],
    ) -> CategorizationResult {
        let cfg = match self.current_config().await {
            Ok(Some(cfg)) if is_configured(&cfg) => cfg,
            Ok(_) => return CategorizationResult::empty("DISABLED"),
            Err(err) => {
                tracing::warn!("AI categorization configuration error: {err}");
                return CategorizationResult {
                    status: "ERROR".to_owned(),
                    error: Some(err),
                    ..CategorizationResult::default()
                };
            }
        };

        if categories.is_empty() {
            return CategorizationResult::empty("EMPTY");
        }

        let (user_prompt, full_prompt) = prompt::build_prompts(input, categories, examples);
        let started = Instant::now();

        let resp = match provider::call_provider(
            &self.http,
            &cfg,
            prompt::SYSTEM_PROMPT,
            &user_prompt,
            self.timeout,
        )
        .await
        {
            Ok(resp) => resp,
            Err(err) => {
                let latency_ms = elapsed_ms(started);
                tracing::warn!(
                    provider = cfg.provider.name(),
                    "AI categorization failed: {err}"
                );
                return CategorizationResult {
                    suggestion: None,
                    prompt: Some(full_prompt),
                    response: None,
                    prompt_tokens: None,
                    completion_tokens: None,
                    total_tokens: None,
                    latency_ms,
                    status: "ERROR".to_owned(),
                    error: Some(err),
                };
            }
        };

        let latency_ms = elapsed_ms(started);
        let Some(raw_text) = resp.text else {
            return CategorizationResult {
                suggestion: None,
                prompt: Some(full_prompt),
                response: None,
                prompt_tokens: resp.prompt_tokens,
                completion_tokens: resp.completion_tokens,
                total_tokens: resp.total_tokens,
                latency_ms,
                status: "ERROR".to_owned(),
                error: Some("The model returned no text answer.".to_owned()),
            };
        };

        match parser::parse_answer(&raw_text) {
            Ok(suggestion) => {
                let status = if suggestion.is_some() { "OK" } else { "EMPTY" };
                CategorizationResult {
                    suggestion,
                    prompt: Some(full_prompt),
                    response: Some(raw_text),
                    prompt_tokens: resp.prompt_tokens,
                    completion_tokens: resp.completion_tokens,
                    total_tokens: resp.total_tokens,
                    latency_ms,
                    status: status.to_owned(),
                    error: None,
                }
            }
            Err(err) => {
                tracing::warn!(
                    provider = cfg.provider.name(),
                    "AI categorization answer parse failed: {err}"
                );
                CategorizationResult {
                    suggestion: None,
                    prompt: Some(full_prompt),
                    response: Some(raw_text),
                    prompt_tokens: resp.prompt_tokens,
                    completion_tokens: resp.completion_tokens,
                    total_tokens: resp.total_tokens,
                    latency_ms,
                    status: "ERROR".to_owned(),
                    error: Some(err),
                }
            }
        }
    }
}
