//! SetupController and SetupService: the first-run wizard.

use std::sync::LazyLock;
use std::sync::atomic::Ordering;
use std::time::Duration;

use axum::extract::State;
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use regex::Regex;
use serde::{Deserialize, Serialize, Serializer};
use serde_with::skip_serializing_none;
use sqlx::Connection;

use crate::AppState;
use crate::auth::{password, users};
use crate::error::{AppError, AppResult, Problem};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Path, Validate, null_as_false};
use crate::http::rate_limit::policies;
use crate::setup_auth::audit::{self, AuditContext};
use crate::setup_auth::{enable_banking, settings};

pub const INTEGRATIONS: &[&str] = &[
    "enablebanking",
    "boursobank",
    "boursedirect",
    "fortuneo",
    "traderepublic",
    "lbp",
    "finary",
    "crypto",
];
const ACKNOWLEDGEABLE: &[&str] = &["traderepublic", "finary", "boursedirect", "fortuneo", "lbp"];
const BOURSO_TIMEOUT: Duration = Duration::from_secs(3);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SetupState {
    PendingAdmin,
    InProgress,
    Complete,
}

impl SetupState {
    fn parse(raw: &str) -> AppResult<Self> {
        match raw {
            "PENDING_ADMIN" => Ok(Self::PendingAdmin),
            "IN_PROGRESS" => Ok(Self::InProgress),
            "COMPLETE" => Ok(Self::Complete),
            other => Err(AppError::Internal(format!(
                "No enum constant SetupState.{other}"
            ))),
        }
    }
}

pub async fn current_state(state: &AppState) -> AppResult<SetupState> {
    match settings::read(state, settings::SETUP_STATE).await? {
        Some(raw) => SetupState::parse(&raw),
        None => Ok(SetupState::PendingAdmin),
    }
}

pub async fn is_complete(state: &AppState) -> AppResult<bool> {
    Ok(current_state(state).await? == SetupState::Complete)
}

/// SetupFilter's flag: once complete, never asked again.
pub async fn is_complete_cached(state: &AppState) -> bool {
    if state.setup_complete.load(Ordering::Relaxed) {
        return true;
    }
    let complete = is_complete(state).await.unwrap_or(false);
    if complete {
        state.setup_complete.store(true, Ordering::Relaxed);
    }
    complete
}

fn integration_key(name: &str) -> String {
    format!("integration.{name}.enabled")
}

pub async fn enable_integration(state: &AppState, key: &str) -> AppResult<()> {
    if !INTEGRATIONS.contains(&key) {
        return Err(AppError::BadRequest(format!("Unknown integration: {key}")));
    }
    settings::upsert(&state.db, &integration_key(key), "true").await?;
    tracing::info!("setup.integration.enabled key={key}");
    Ok(())
}

/// Integration flags in their declaration order.
struct Integrations(Vec<(&'static str, bool)>);

impl Serialize for Integrations {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeMap;
        let mut map = serializer.serialize_map(Some(self.0.len()))?;
        for (k, v) in &self.0 {
            map.serialize_entry(k, v)?;
        }
        map.end()
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SsoInfo {
    enabled: bool,
    provider_name: String,
    admin_bootstrap: bool,
}

#[skip_serializing_none]
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StatusResponse {
    state: SetupState,
    needs_setup: bool,
    integrations: Integrations,
    sso: SsoInfo,
    desktop_mode: Option<bool>,
    desktop_lock_enabled: Option<bool>,
}

pub async fn status(State(state): State<AppState>) -> AppResult<axum::Json<impl Serialize>> {
    let current = current_state(&state).await?;
    let mut flags = Vec::new();
    for name in INTEGRATIONS {
        let on = settings::read(&state, &integration_key(name))
            .await?
            .is_some_and(|v| settings::parse_bool(&v));
        flags.push((*name, on));
    }
    let sso = &state.config.sso;
    let (desktop_mode, desktop_lock_enabled) = if state.config.desktop_mode {
        (
            Some(true),
            Some(settings::desktop_lock_enabled(&state).await?),
        )
    } else {
        (None, None)
    };
    Ok(axum::Json(StatusResponse {
        state: current,
        needs_setup: current != SetupState::Complete,
        integrations: Integrations(flags),
        sso: SsoInfo {
            enabled: sso.enabled(),
            provider_name: sso.provider_name.clone(),
            admin_bootstrap: sso.admin_group_configured(),
        },
        desktop_mode,
        desktop_lock_enabled,
    }))
}

pub(crate) fn rate_limited() -> Response {
    Problem::with_detail(
        StatusCode::TOO_MANY_REQUESTS,
        "Too many setup requests. Try again in a minute.",
    )
    .into_response()
}

pub(crate) async fn require_not_complete(state: &AppState) -> AppResult<()> {
    if is_complete(state).await? {
        return Err(AppError::status(
            StatusCode::GONE,
            "Setup is already complete; use the admin UI to manage users and security.",
        ));
    }
    Ok(())
}

pub(crate) fn audit_ctx<'a>(ip: &'a str, headers: &'a HeaderMap) -> AuditContext<'a> {
    AuditContext {
        ip,
        user_agent: headers
            .get(header::USER_AGENT)
            .and_then(|v| v.to_str().ok()),
    }
}

static USERNAME: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[a-zA-Z0-9._-]+$").expect("static regex"));
static AVATAR_COLOR: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^#[0-9a-fA-F]{6}$").expect("static regex"));
static ORIGIN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^https?://[A-Za-z0-9.\-]+(:\d{1,5})?$").expect("static regex"));
static APPLICATION_ID: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[0-9a-fA-F-]{32,36}$").expect("static regex"));
static HTTP_URI: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^https?://.+$").expect("static regex"));

pub const USERNAME_MESSAGE: &str =
    "Username may only contain letters, digits, dots, underscores and hyphens";

pub fn validate_username(errors: &mut Errors, field: &str, value: Option<&str>) {
    errors
        .not_blank(field, value)
        .size(field, value, 3, 50)
        .pattern(field, value, &USERNAME, USERNAME_MESSAGE);
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdminRequest {
    username: Option<String>,
    password: Option<String>,
    display_name: Option<String>,
    avatar_color: Option<String>,
}

impl Validate for AdminRequest {
    fn validate(&self, e: &mut Errors) {
        validate_username(e, "username", self.username.as_deref());
        e.not_blank("password", self.password.as_deref()).size(
            "password",
            self.password.as_deref(),
            8,
            128,
        );
        e.size("displayName", self.display_name.as_deref(), 0, 80);
        e.pattern(
            "avatarColor",
            self.avatar_color.as_deref(),
            &AVATAR_COLOR,
            "Avatar color must be a hex color like #6366f1",
        );
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AdminResponse {
    username: String,
    display_name: String,
}

/// SetupService.seedAdmin: one SERIALIZABLE transaction claiming PENDING_ADMIN -> IN_PROGRESS.
pub async fn seed_admin(
    state: &AppState,
    username: &str,
    bcrypt_hash: &str,
    display_name: Option<&str>,
    avatar_color: Option<&str>,
) -> AppResult<users::AppUser> {
    let mut conn = state.db.acquire().await?;
    let mut tx = conn.begin().await?;
    sqlx::query("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE")
        .execute(&mut *tx)
        .await?;
    if users::exists_by_username(&mut *tx, username).await? {
        tracing::info!("Admin already exists, skipping seed");
        let existing = users::find_by_username(&mut *tx, username).await?;
        tx.commit().await?;
        return existing.ok_or_else(|| AppError::Internal("admin vanished".into()));
    }
    let current = match settings::read_with(&mut *tx, settings::SETUP_STATE).await? {
        Some(raw) => SetupState::parse(&raw)?,
        None => SetupState::PendingAdmin,
    };
    if current == SetupState::Complete {
        return Err(AppError::Internal(
            "Setup is already complete; use the admin UI to manage users.".into(),
        ));
    }
    if users::count(&mut *tx).await? > 0 {
        return Err(AppError::Internal(
            "An account already exists; additional members are created from the Family admin UI."
                .into(),
        ));
    }
    let claimed = sqlx::query("UPDATE app_setting SET value = 'IN_PROGRESS' WHERE setting_key = $1 AND value = 'PENDING_ADMIN'")
        .bind(settings::SETUP_STATE)
        .execute(&mut *tx)
        .await?
        .rows_affected();
    if claimed == 0 && current == SetupState::PendingAdmin {
        tracing::warn!(
            "Setup: concurrent admin-seeding attempt lost the setup-state CAS (state={current:?})"
        );
        return Err(AppError::Internal(
            "Another setup session is already in progress.".into(),
        ));
    }
    if !bcrypt_hash.starts_with("$2") {
        return Err(AppError::BadRequest(
            "Password hash must be a valid bcrypt hash starting with $2a$, $2b$, or $2y$.".into(),
        ));
    }
    let member_id: i64 = sqlx::query_scalar(
        "INSERT INTO family_member (display_name, avatar_color, is_managed) VALUES ($1, $2, false) RETURNING id",
    )
    .bind(display_name.unwrap_or(username))
    .bind(avatar_color.unwrap_or("#6366f1"))
    .fetch_one(&mut *tx)
    .await?;
    let user_id: i64 = sqlx::query_scalar(
        "INSERT INTO app_user (username, password_hash, member_id, role, is_activated, acknowledged_warning) \
         VALUES ($1, $2, $3, 'ADMIN', true, true) RETURNING id",
    )
    .bind(username)
    .bind(bcrypt_hash)
    .bind(member_id)
    .fetch_one(&mut *tx)
    .await?;
    tx.commit().await?;
    tracing::info!("setup.admin.created role=ADMIN");
    users::find_by_id(&state.db, user_id)
        .await?
        .ok_or_else(|| AppError::Internal("admin vanished".into()))
}

pub async fn mark_complete(state: &AppState) -> AppResult<()> {
    settings::upsert(&state.db, settings::SETUP_STATE, "COMPLETE").await?;
    state.setup_complete.store(true, Ordering::Relaxed);
    tracing::info!("setup.completed");
    Ok(())
}

/// DataSeeder: the historical APP_USERNAME / APP_PASSWORD_HASH bootstrap.
pub async fn seed_from_environment(state: &AppState) -> AppResult<()> {
    let (username, hash) = (&state.config.app_username, &state.config.app_password_hash);
    if username.trim().is_empty() || hash.trim().is_empty() {
        tracing::info!(
            "APP_USERNAME or APP_PASSWORD_HASH not set — skipping auto-seed, setup wizard will take over"
        );
        return Ok(());
    }
    if state.config.sso.admin_group_configured() {
        tracing::warn!(
            "APP_USERNAME and APP_PASSWORD_HASH are set together with SSO_ADMIN_GROUP; the environment admin wins"
        );
    }
    seed_admin(state, username, hash, Some(username), None).await?;
    mark_complete(state).await
}

pub async fn admin(
    State(state): State<AppState>,
    ip: ClientIp,
    headers: HeaderMap,
    Json(req): Json<AdminRequest>,
) -> AppResult<Response> {
    let ip = ip.as_java_string();
    if !state.limits.try_consume(&policies::SETUP, &ip) {
        return Ok(rate_limited());
    }
    if state.config.sso.admin_group_configured() {
        return Err(AppError::coded(
            StatusCode::CONFLICT,
            "SSO_ADMIN_REQUIRED",
            "The first admin must sign in through the configured SSO admin group.",
        ));
    }
    require_not_complete(&state).await?;
    let username = req.username.unwrap_or_default();
    let hash = password::hash(&req.password.unwrap_or_default()).await?;
    let user = seed_admin(
        &state,
        &username,
        &hash,
        req.display_name.as_deref(),
        req.avatar_color.as_deref(),
    )
    .await?;
    audit::record(
        &state,
        "setup.admin.created",
        Some(&user.username),
        &audit_ctx(&ip, &headers),
        None,
    )
    .await;
    Ok(axum::Json(AdminResponse {
        username: user.username,
        display_name: user.display_name,
    })
    .into_response())
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
                e.not_empty("allowedOrigins", Some(list.as_slice()));
            }
            Some(list) => {
                for (i, origin) in list.iter().enumerate() {
                    let field = format!("allowedOrigins[{i}]");
                    e.pattern(
                        &field,
                        origin.as_deref(),
                        &ORIGIN,
                        "Origin must look like http(s)://host[:port]",
                    );
                    e.size(&field, origin.as_deref(), 0, 200);
                }
            }
        }
    }
}

pub async fn security(
    State(state): State<AppState>,
    ip: ClientIp,
    headers: HeaderMap,
    Json(req): Json<SecurityRequest>,
) -> AppResult<Response> {
    let ip = ip.as_java_string();
    if !state.limits.try_consume(&policies::SETUP, &ip) {
        return Ok(rate_limited());
    }
    require_not_complete(&state).await?;
    let origins = req.allowed_origins.unwrap_or_default();
    if origins.is_empty() {
        return Err(AppError::BadRequest(
            "At least one allowed origin is required.".into(),
        ));
    }
    let origins: Vec<String> = match origins.into_iter().collect::<Option<Vec<_>>>() {
        Some(list) if !list.iter().any(|o| o.contains('*')) => list,
        _ => {
            return Err(AppError::BadRequest(
                "Wildcard origins are not allowed with credentialed CORS; list explicit origins."
                    .into(),
            ));
        }
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
    let details = format!("origins={} secure={}", origins.len(), req.secure_cookies);
    audit::record(
        &state,
        "setup.security.updated",
        None,
        &audit_ctx(&ip, &headers),
        Some(&details),
    )
    .await;
    Ok(StatusCode::NO_CONTENT.into_response())
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnableBankingConfigRequest {
    application_id: Option<String>,
    redirect_uri: Option<String>,
}

impl Validate for EnableBankingConfigRequest {
    fn validate(&self, e: &mut Errors) {
        let app = self.application_id.as_deref();
        e.not_blank("applicationId", app).pattern(
            "applicationId",
            app,
            &APPLICATION_ID,
            "Application ID must look like a UUID (32-36 hex characters)",
        );
        let uri = self.redirect_uri.as_deref();
        e.not_blank("redirectUri", uri)
            .size("redirectUri", uri, 0, 500)
            .pattern(
                "redirectUri",
                uri,
                &HTTP_URI,
                "Redirect URI must start with http:// or https://",
            );
    }
}

pub async fn enable_banking_config(
    State(state): State<AppState>,
    ip: ClientIp,
    Json(req): Json<EnableBankingConfigRequest>,
) -> AppResult<Response> {
    if !state
        .limits
        .try_consume(&policies::SETUP, &ip.as_java_string())
    {
        return Ok(rate_limited());
    }
    require_not_complete(&state).await?;
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
    Ok(StatusCode::NO_CONTENT.into_response())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct KeypairResponse {
    public_key_pem: String,
    regenerated: bool,
}

pub async fn enable_banking_keypair(
    State(state): State<AppState>,
    ip: ClientIp,
) -> AppResult<Response> {
    if !state
        .limits
        .try_consume(&policies::SETUP, &ip.as_java_string())
    {
        return Ok(rate_limited());
    }
    require_not_complete(&state).await?;
    let (public_key_pem, regenerated) = enable_banking::get_or_generate_public_pem(&state).await?;
    Ok(axum::Json(KeypairResponse {
        public_key_pem,
        regenerated,
    })
    .into_response())
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

pub async fn enable_banking_import(
    State(state): State<AppState>,
    ip: ClientIp,
    Json(req): Json<ImportRequest>,
) -> AppResult<Response> {
    if !state
        .limits
        .try_consume(&policies::SETUP, &ip.as_java_string())
    {
        return Ok(rate_limited());
    }
    require_not_complete(&state).await?;
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

pub async fn enable_banking_test(
    State(state): State<AppState>,
    ip: ClientIp,
    headers: HeaderMap,
) -> AppResult<Response> {
    let ip = ip.as_java_string();
    if !state.limits.try_consume(&policies::SETUP, &ip) {
        return Ok(StatusCode::TOO_MANY_REQUESTS.into_response());
    }
    require_not_complete(&state).await?;
    let response = enable_banking::test_connection(&state).await?;
    if response.ok {
        enable_integration(&state, "enablebanking").await?;
        audit::record(
            &state,
            "setup.integration.enabled",
            None,
            &audit_ctx(&ip, &headers),
            Some("key=enablebanking"),
        )
        .await;
    }
    Ok(axum::Json(response).into_response())
}

#[skip_serializing_none]
#[derive(Serialize)]
struct BoursoHealthResponse {
    ok: bool,
    url: String,
    hint: Option<String>,
}

pub async fn bourso_health(
    State(state): State<AppState>,
    ip: ClientIp,
    headers: HeaderMap,
) -> AppResult<Response> {
    let ip = ip.as_java_string();
    if !state.limits.try_consume(&policies::SETUP, &ip) {
        return Ok(StatusCode::TOO_MANY_REQUESTS.into_response());
    }
    require_not_complete(&state).await?;
    let url = match settings::read(&state, settings::BOURSO_AUTH_URL)
        .await?
        .filter(|v| !v.trim().is_empty())
    {
        Some(url) => url,
        None => state
            .config
            .sidecar_url("bourso-auth")
            .unwrap_or_default()
            .to_owned(),
    };
    let reached = if crate::native_sidecars::is_native_url(&url) {
        state
            .connectors
            .native
            .resolve("bourso-auth", &url)
            .is_some()
    } else {
        state
            .http
            .get(format!("{}/health", url.trim_end_matches('/')))
            .timeout(BOURSO_TIMEOUT)
            .send()
            .await
            .is_ok_and(|r| r.status().is_success())
    };
    let response = if reached {
        enable_integration(&state, "boursobank").await?;
        audit::record(
            &state,
            "setup.integration.enabled",
            None,
            &audit_ctx(&ip, &headers),
            Some("key=boursobank"),
        )
        .await;
        BoursoHealthResponse {
            ok: true,
            url,
            hint: None,
        }
    } else {
        tracing::info!("setup.integration.boursobank.health_failed url={url}");
        let hint = format!(
            "Sidecar at {url} did not respond within {}s. Make sure the bourso-auth container is running, or override the URL below.",
            BOURSO_TIMEOUT.as_secs()
        );
        BoursoHealthResponse {
            ok: false,
            url,
            hint: Some(hint),
        }
    };
    Ok(axum::Json(response).into_response())
}

#[derive(Serialize)]
struct CryptoKeyResponse {
    existed: bool,
    path: String,
}

/// CryptoKeyGeneratorService: a runtime CRYPTO_ENCRYPTION_KEY is the key; otherwise the key file
/// at APP_CRYPTO_KEY_PATH is written once and never replaced.
fn ensure_crypto_key(state: &AppState) -> AppResult<(bool, String)> {
    if !state.config.crypto_key.trim().is_empty() {
        return Ok((true, "CRYPTO_ENCRYPTION_KEY".into()));
    }
    let path = &state.config.crypto_key_path;
    let location = path.display().to_string();
    if path.exists() {
        return Ok((true, location));
    }
    let failed = |_| AppError::Internal(format!("Failed to generate crypto key at {location}"));
    if let Some(parent) = path.parent()
        && !parent.exists()
    {
        std::fs::create_dir_all(parent).map_err(failed)?;
    }
    let key: [u8; 32] = rand::random();
    use base64::Engine;
    std::fs::write(path, base64::engine::general_purpose::STANDARD.encode(key)).map_err(failed)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
    }
    tracing::info!("setup.crypto.key.generated path={location}");
    Ok((false, location))
}

pub async fn crypto_generate_key(
    State(state): State<AppState>,
    ip: ClientIp,
    headers: HeaderMap,
) -> AppResult<Response> {
    let ip = ip.as_java_string();
    if !state.limits.try_consume(&policies::SETUP, &ip) {
        return Ok(StatusCode::TOO_MANY_REQUESTS.into_response());
    }
    require_not_complete(&state).await?;
    let (existed, path) = ensure_crypto_key(&state)?;
    enable_integration(&state, "crypto").await?;
    let details = format!("key=crypto existed={existed}");
    audit::record(
        &state,
        "setup.integration.enabled",
        None,
        &audit_ctx(&ip, &headers),
        Some(&details),
    )
    .await;
    Ok(axum::Json(CryptoKeyResponse { existed, path }).into_response())
}

pub async fn acknowledge(
    State(state): State<AppState>,
    ip: ClientIp,
    headers: HeaderMap,
    Path(key): Path<String>,
) -> AppResult<Response> {
    let ip = ip.as_java_string();
    if !state.limits.try_consume(&policies::SETUP, &ip) {
        return Ok(rate_limited());
    }
    require_not_complete(&state).await?;
    if !ACKNOWLEDGEABLE.contains(&key.as_str()) {
        return Err(AppError::status(
            StatusCode::BAD_REQUEST,
            "This step only applies to integrations configured after signing in.",
        ));
    }
    enable_integration(&state, &key).await?;
    audit::record(
        &state,
        "setup.integration.enabled",
        None,
        &audit_ctx(&ip, &headers),
        Some(&format!("key={key}")),
    )
    .await;
    Ok(StatusCode::NO_CONTENT.into_response())
}

pub async fn complete(
    State(state): State<AppState>,
    ip: ClientIp,
    headers: HeaderMap,
) -> AppResult<Response> {
    let ip = ip.as_java_string();
    if !state.limits.try_consume(&policies::SETUP, &ip) {
        return Ok(rate_limited());
    }
    require_not_complete(&state).await?;
    mark_complete(&state).await?;
    audit::record(
        &state,
        "setup.completed",
        None,
        &audit_ctx(&ip, &headers),
        None,
    )
    .await;
    Ok(StatusCode::NO_CONTENT.into_response())
}
