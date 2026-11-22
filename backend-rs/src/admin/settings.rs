//! The app_setting keys the admin page writes, with the typed accessors other modules read, and
//! AdminController's settings endpoints.

use std::collections::HashMap;

use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Serialize};

use crate::AppState;
use crate::auth::Auth;
use crate::crypto::CryptoEncryption;
use crate::error::{AppError, AppResult, Problem};
use crate::http::json::{Errors, Json, Path, Validate, null_as_false};
use crate::setup_auth::enable_banking::{self, EnableBankingSettings};
use crate::setup_auth::settings;
use crate::setup_auth::setup::INTEGRATIONS;

pub const AI_PROVIDER: &str = "ai.provider";
pub const AI_MODEL: &str = "ai.model";
pub const AI_BASE_URL: &str = "ai.base-url";
pub const AI_API_KEY: &str = "ai.api-key";
pub const AI_MAX_CONCURRENCY: &str = "ai.max-concurrency";

/// The configurable AI providers (AiProvider): default base URL, default model, key required.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AiProvider {
    OpenAi,
    OpenRouter,
    Anthropic,
    Ollama,
}

impl AiProvider {
    /// fromKey: blank or "none" is no provider; matching ignores case.
    pub fn from_key(key: Option<&str>) -> Option<Self> {
        match key?.trim().to_ascii_uppercase().as_str() {
            "OPENAI" => Some(Self::OpenAi),
            "OPENROUTER" => Some(Self::OpenRouter),
            "ANTHROPIC" => Some(Self::Anthropic),
            "OLLAMA" => Some(Self::Ollama),
            _ => None,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Self::OpenAi => "OPENAI",
            Self::OpenRouter => "OPENROUTER",
            Self::Anthropic => "ANTHROPIC",
            Self::Ollama => "OLLAMA",
        }
    }

    pub fn default_base_url(self) -> &'static str {
        match self {
            Self::OpenAi => "https://api.openai.com",
            Self::OpenRouter => "https://openrouter.ai/api",
            Self::Anthropic => "https://api.anthropic.com",
            Self::Ollama => "http://ollama:11434",
        }
    }

    pub fn default_model(self) -> &'static str {
        match self {
            Self::OpenAi => "gpt-4o-mini",
            Self::OpenRouter => "anthropic/claude-3.5-haiku",
            Self::Anthropic => "claude-haiku-4-5",
            Self::Ollama => "qwen3:0.6b",
        }
    }

    pub fn key_required(self) -> bool {
        self != Self::Ollama
    }
}

/// AiProviderConfig: the stored provider with its decrypted key.
#[derive(Clone)]
pub struct AiConfig {
    pub provider: AiProvider,
    pub api_key: Option<String>,
    pub model: String,
    pub base_url: String,
}

impl std::fmt::Debug for AiConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AiConfig")
            .field("provider", &self.provider)
            .field("api_key", &self.api_key.as_ref().map(|_| "[REDACTED]"))
            .field("model", &self.model)
            .field("base_url", &self.base_url)
            .finish()
    }
}

impl AiConfig {
    pub fn effective_base_url(&self) -> String {
        if self.base_url.trim().is_empty() {
            self.provider.default_base_url().to_owned()
        } else {
            self.base_url.trim().to_owned()
        }
    }

    pub fn effective_model(&self) -> String {
        if self.model.trim().is_empty() {
            self.provider.default_model().to_owned()
        } else {
            self.model.trim().to_owned()
        }
    }
}

async fn read_or_empty(state: &AppState, key: &str) -> Result<String, sqlx::Error> {
    Ok(settings::read(state, key).await?.unwrap_or_default())
}

/// AiConfigProvider.current: the stored AI configuration, None while AI is off.
pub async fn ai_config(state: &AppState) -> AppResult<Option<AiConfig>> {
    let Some(provider) = AiProvider::from_key(settings::read(state, AI_PROVIDER).await?.as_deref())
    else {
        return Ok(None);
    };
    let api_key = match settings::read(state, AI_API_KEY)
        .await?
        .filter(|k| !k.trim().is_empty())
    {
        Some(enc) => Some(state.crypto.decrypt(&enc)?),
        None => None,
    };
    Ok(Some(AiConfig {
        provider,
        api_key,
        model: read_or_empty(state, AI_MODEL).await?,
        base_url: read_or_empty(state, AI_BASE_URL).await?,
    }))
}

/// The AI job concurrency: 4 by default, clamped to 1..16.
pub async fn ai_max_concurrency(state: &AppState) -> Result<i32, sqlx::Error> {
    let value = settings::read(state, AI_MAX_CONCURRENCY)
        .await?
        .map_or(4, |v| v.trim().parse::<i32>().unwrap_or(4));
    Ok(value.clamp(1, 16))
}

fn integration_key(name: &str) -> String {
    format!("integration.{name}.enabled")
}

/// IntegrationsService.isEnabled: the stored wizard flag.
pub async fn integration_enabled(state: &AppState, key: &str) -> Result<bool, sqlx::Error> {
    Ok(settings::read(state, &integration_key(key))
        .await?
        .is_some_and(|v| settings::parse_bool(&v)))
}

async fn count(
    state: &AppState,
    sql: &str,
    bind: Option<&str>,
    member: Option<i64>,
) -> Result<i64, sqlx::Error> {
    let mut query = crate::db::query_scalar::<i64>(sql);
    if let Some(b) = bind {
        query = query.bind(b.to_owned());
    }
    if let Some(m) = member {
        query = query.bind(m);
    }
    query.fetch_one(&state.db).await
}

/// IntegrationsService.isEffectivelyEnabled: the stored flag, or a live configuration found
/// without any network call (env credentials, a login session).
pub async fn integration_effectively_enabled(
    state: &AppState,
    key: &str,
    member_id: i64,
) -> AppResult<bool> {
    if integration_enabled(state, key).await? {
        return Ok(true);
    }
    let sidecar_active = "SELECT count(*) FROM sidecar_session WHERE bank = $1 AND is_active";
    Ok(match key {
        "enablebanking" => {
            let eb = EnableBankingSettings::new(state);
            eb.application_id().await?.is_some()
                && eb.key_id().await?.is_some()
                && eb.redirect_uri().await?.is_some()
                && eb.private_key_present()
        }
        "traderepublic" => {
            count(
                state,
                "SELECT count(*) FROM sidecar_session WHERE bank = $1",
                Some("TRADE_REPUBLIC"),
                None,
            )
            .await?
                > 0
        }
        "finary" => count(state, "SELECT count(*) FROM finary_session", None, None).await? > 0,
        "boursedirect" => count(state, sidecar_active, Some("BOURSE_DIRECT"), None).await? > 0,
        "boursobank" => count(state, sidecar_active, Some("BOURSO"), None).await? > 0,
        "fortuneo" => count(
            state,
            "SELECT count(*) FROM sidecar_session WHERE bank = $1 AND member_id = $2 AND is_active",
            Some("FORTUNEO"),
            Some(member_id),
        )
        .await?
            > 0,
        "lbp" => {
            count(
                state,
                "SELECT count(*) FROM lbp_session WHERE member_id = $1 AND is_active",
                None,
                Some(member_id),
            )
            .await?
                > 0
        }
        _ => false,
    })
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SecuritySettings {
    allowed_origins: Vec<String>,
    secure_cookies: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct EnableBankingView {
    application_id: String,
    redirect_uri: String,
    private_key_present: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AiSettings {
    provider: String,
    model: String,
    base_url: String,
    api_key_present: bool,
    max_concurrency: i32,
}

#[derive(Serialize)]
struct TelemetrySettings {
    available: bool,
    consent: &'static str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsResponse {
    security: SecuritySettings,
    enable_banking: EnableBankingView,
    integrations: serde_json::Map<String, serde_json::Value>,
    ai: AiSettings,
    telemetry: TelemetrySettings,
}

pub async fn get_settings(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<SettingsResponse>> {
    let origins = settings::read(&state, settings::CORS_ALLOWED_ORIGINS)
        .await?
        .map(|s| s.split(',').map(str::to_owned).collect())
        .unwrap_or_default();
    let secure_cookies = settings::read(&state, settings::SECURE_COOKIES)
        .await?
        .is_some_and(|v| settings::parse_bool(&v));
    let eb = EnableBankingSettings::new(&state);
    let mut integrations = serde_json::Map::new();
    for name in INTEGRATIONS {
        integrations.insert(
            (*name).to_owned(),
            integration_effectively_enabled(&state, name, auth.member_id)
                .await?
                .into(),
        );
    }
    let provider = settings::read(&state, AI_PROVIDER)
        .await?
        .filter(|p| !p.trim().is_empty())
        .unwrap_or_else(|| "none".to_owned());
    Ok(axum::Json(SettingsResponse {
        security: SecuritySettings {
            allowed_origins: origins,
            secure_cookies,
        },
        enable_banking: EnableBankingView {
            application_id: eb.application_id().await?.unwrap_or_default(),
            redirect_uri: eb.redirect_uri().await?.unwrap_or_default(),
            private_key_present: eb.private_key_present(),
        },
        integrations,
        ai: AiSettings {
            provider,
            model: read_or_empty(&state, AI_MODEL).await?,
            base_url: read_or_empty(&state, AI_BASE_URL).await?,
            api_key_present: settings::read(&state, AI_API_KEY)
                .await?
                .is_some_and(|k| !k.trim().is_empty()),
            max_concurrency: ai_max_concurrency(&state).await?,
        },
        telemetry: TelemetrySettings {
            available: super::telemetry::is_available(&state),
            consent: super::telemetry::consent(&state).await?.as_str(),
        },
    }))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SecurityRequest {
    allowed_origins: Option<Vec<Option<String>>>,
    #[serde(default, deserialize_with = "null_as_false")]
    secure_cookies: bool,
}

impl Validate for SecurityRequest {
    fn validate(&self, e: &mut Errors) {
        match &self.allowed_origins {
            None => {
                e.not_null::<Vec<Option<String>>>("allowedOrigins", None);
            }
            Some(list) if list.is_empty() => {
                let message = e.default_message(
                    "size must be between 1 and 2147483647",
                    "la taille doit être comprise entre 1 et 2147483647",
                );
                e.add("allowedOrigins", message);
            }
            Some(_) => {}
        }
    }
}

/// SetupService.writeSecurity.
pub async fn update_security(
    State(state): State<AppState>,
    Json(req): Json<SecurityRequest>,
) -> AppResult<StatusCode> {
    let origins = req.allowed_origins.unwrap_or_default();
    if origins.is_empty() {
        return Err(AppError::BadRequest(
            "At least one allowed origin is required.".into(),
        ));
    }
    let Some(origins) = origins
        .into_iter()
        .collect::<Option<Vec<String>>>()
        .filter(|l| !l.iter().any(|o| o.contains('*')))
    else {
        return Err(AppError::BadRequest(
            "Wildcard origins are not allowed with credentialed CORS; list explicit origins."
                .into(),
        ));
    };
    settings::upsert(
        &state.db,
        settings::CORS_ALLOWED_ORIGINS,
        &origins.join(","),
    )
    .await?;
    settings::upsert(
        &state.db,
        settings::SECURE_COOKIES,
        if req.secure_cookies { "true" } else { "false" },
    )
    .await?;
    tracing::info!(
        "setup.security.updated origins={} secure={}",
        origins.len(),
        req.secure_cookies
    );
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Deserialize)]
pub struct TelemetryRequest {
    enabled: Option<bool>,
}

impl Validate for TelemetryRequest {
    fn validate(&self, e: &mut Errors) {
        e.not_null("enabled", self.enabled.as_ref());
    }
}

pub async fn update_telemetry(
    State(state): State<AppState>,
    Json(req): Json<TelemetryRequest>,
) -> AppResult<Response> {
    if !super::telemetry::set_enabled(&state, req.enabled.unwrap_or(false)).await? {
        return Ok(Problem::with_detail(
            StatusCode::CONFLICT,
            "Telemetry is not configured on this instance",
        )
        .into_response());
    }
    Ok(StatusCode::NO_CONTENT.into_response())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CorsReloaded {
    allowed_origins: Vec<String>,
}

/// SetupService.reloadCorsFromEnv: ALLOWED_ORIGINS wins once over the stored origins.
pub async fn reload_cors_from_env(
    State(state): State<AppState>,
) -> AppResult<axum::Json<CorsReloaded>> {
    let env = state.config.cors_allowed_origins.clone();
    if env.trim().is_empty() {
        return Err(AppError::BadRequest(
            "ALLOWED_ORIGINS env var is empty — set it before reloading.".into(),
        ));
    }
    let parsed: Vec<String> = env
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
        .collect();
    if parsed.is_empty() {
        return Err(AppError::BadRequest(
            "ALLOWED_ORIGINS env var contains no usable origins.".into(),
        ));
    }
    if parsed.iter().any(|o| o.contains('*')) {
        return Err(AppError::BadRequest(
            "Wildcard origins are not allowed with credentialed CORS.".into(),
        ));
    }
    settings::upsert(&state.db, settings::CORS_ALLOWED_ORIGINS, &parsed.join(",")).await?;
    tracing::info!(
        "setup.security.cors-reloaded-from-env origins={}",
        parsed.len()
    );
    Ok(axum::Json(CorsReloaded {
        allowed_origins: parsed,
    }))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnableBankingRequest {
    application_id: Option<String>,
    redirect_uri: Option<String>,
}

impl Validate for EnableBankingRequest {
    fn validate(&self, e: &mut Errors) {
        e.not_blank("applicationId", self.application_id.as_deref())
            .not_blank("redirectUri", self.redirect_uri.as_deref());
    }
}

pub async fn update_enable_banking(
    State(state): State<AppState>,
    Json(req): Json<EnableBankingRequest>,
) -> AppResult<StatusCode> {
    let app_id = req.application_id.unwrap_or_default();
    settings::upsert(&state.db, settings::ENABLEBANKING_APP_ID, &app_id).await?;
    settings::upsert(&state.db, settings::ENABLEBANKING_KEY_ID, &app_id).await?;
    settings::upsert(
        &state.db,
        settings::ENABLEBANKING_REDIRECT_URI,
        &req.redirect_uri.unwrap_or_default(),
    )
    .await?;
    tracing::info!("setup.integration.enablebanking.configured");
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct KeypairResponse {
    public_key_pem: String,
    regenerated: bool,
}

/// The public PEM of the stored pair, generated only when none exists (no setup guard here).
pub async fn generate_keypair(
    State(state): State<AppState>,
) -> AppResult<axum::Json<KeypairResponse>> {
    let (public_key_pem, regenerated) = enable_banking::get_or_generate_public_pem(&state).await?;
    Ok(axum::Json(KeypairResponse {
        public_key_pem,
        regenerated,
    }))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportRequest {
    private_pem: Option<String>,
}

impl Validate for ImportRequest {
    fn validate(&self, e: &mut Errors) {
        e.not_blank("privatePem", self.private_pem.as_deref());
    }
}

pub async fn import_private_key(
    State(state): State<AppState>,
    Json(req): Json<ImportRequest>,
) -> AppResult<Response> {
    match enable_banking::import_private_key(&state, &req.private_pem.unwrap_or_default())? {
        Ok(public_key_pem) => Ok(axum::Json(KeypairResponse {
            public_key_pem,
            regenerated: false,
        })
        .into_response()),
        Err(detail) => {
            Ok(Problem::with_detail(StatusCode::UNPROCESSABLE_ENTITY, detail).into_response())
        }
    }
}

/// Spring's boolean conversion of a request parameter.
pub fn parse_spring_bool(value: &str) -> Option<bool> {
    match value.trim().to_ascii_lowercase().as_str() {
        "true" | "on" | "yes" | "1" => Some(true),
        "false" | "off" | "no" | "0" => Some(false),
        _ => None,
    }
}

pub async fn toggle_integration(
    State(state): State<AppState>,
    Path(key): Path<String>,
    Query(query): Query<HashMap<String, String>>,
) -> AppResult<StatusCode> {
    let Some(raw) = query.get("enabled") else {
        return Err(AppError::BadRequest(
            "Required parameter 'enabled' is not present.".into(),
        ));
    };
    let Some(enabled) = parse_spring_bool(raw) else {
        return Err(AppError::BadRequest(format!(
            "Failed to convert 'enabled' with value: '{raw}'"
        )));
    };
    if !INTEGRATIONS.contains(&key.as_str()) {
        return Err(AppError::BadRequest(format!("Unknown integration: {key}")));
    }
    settings::upsert(
        &state.db,
        &integration_key(&key),
        if enabled { "true" } else { "false" },
    )
    .await?;
    tracing::info!(
        "setup.integration.{} key={key}",
        if enabled { "enabled" } else { "disabled" }
    );
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AiRequest {
    pub provider: Option<String>,
    pub model: Option<String>,
    pub base_url: Option<String>,
    pub api_key: Option<String>,
    pub max_concurrency: Option<i32>,
}

impl Validate for AiRequest {
    fn validate(&self, e: &mut Errors) {
        e.not_blank("provider", self.provider.as_deref());
        if let Some(n) = self.max_concurrency {
            if n < 1 {
                let message = e.default_message(
                    "must be greater than or equal to 1",
                    "doit être supérieur ou égal à 1",
                );
                e.add("maxConcurrency", message);
            } else if n > 16 {
                let message = e.default_message(
                    "must be less than or equal to 16",
                    "doit être inférieur ou égal à 16",
                );
                e.add("maxConcurrency", message);
            }
        }
    }
}

fn encrypt(crypto: &CryptoEncryption, raw: &str) -> AppResult<String> {
    Ok(crypto.encrypt(raw)?)
}

/// AiConfigProvider.save: a blank key keeps the stored one; turning AI off clears it.
pub async fn update_ai(
    State(state): State<AppState>,
    Json(req): Json<AiRequest>,
) -> AppResult<StatusCode> {
    let provider = req.provider.unwrap_or_default();
    let disabling = AiProvider::from_key(Some(&provider)).is_none();
    let key = if disabling {
        Some(String::new())
    } else {
        match req.api_key.as_deref().filter(|k| !k.trim().is_empty()) {
            Some(raw) => Some(encrypt(&state.crypto, raw)?),
            None => None,
        }
    };
    settings::upsert(&state.db, AI_PROVIDER, &provider).await?;
    settings::upsert(
        &state.db,
        AI_MODEL,
        req.model.as_deref().unwrap_or_default(),
    )
    .await?;
    settings::upsert(
        &state.db,
        AI_BASE_URL,
        req.base_url.as_deref().unwrap_or_default(),
    )
    .await?;
    if let Some(key) = key {
        settings::upsert(&state.db, AI_API_KEY, &key).await?;
    }
    if let Some(n) = req.max_concurrency {
        settings::upsert(&state.db, AI_MAX_CONCURRENCY, &n.to_string()).await?;
    }
    tracing::info!(
        "setup.ai.configured provider={}",
        crate::identity::common::log_safe(&provider)
    );
    Ok(StatusCode::NO_CONTENT)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ai_config_debug_redacts_api_key() {
        let with_key = AiConfig {
            provider: AiProvider::OpenAi,
            api_key: Some("sk-super-secret-value".to_owned()),
            model: "gpt-4o-mini".to_owned(),
            base_url: "https://api.openai.com".to_owned(),
        };
        let rendered = format!("{with_key:?}");
        assert!(!rendered.contains("sk-super-secret-value"));
        assert!(rendered.contains("Some(\"[REDACTED]\")"));

        let without_key = AiConfig {
            api_key: None,
            ..with_key
        };
        let rendered_none = format!("{without_key:?}");
        assert!(rendered_none.contains("api_key: None"));
    }
}
