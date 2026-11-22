//! MfaService and MfaController: TOTP enrollment, verification with anti-replay, single-use
//! recovery codes.

use std::collections::HashSet;
use std::io::Cursor;
use std::sync::LazyLock;

use crate::db::Db;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use chrono::{DateTime, Utc};
use rand::RngExt;
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_with::skip_serializing_none;
use totp_rs::{Builder, Secret};

use crate::AppState;
use crate::auth::{AppUser, Auth, password, persistent};
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;

const PERIOD: u64 = 30;
const RECOVERY_CODE_COUNT: usize = 10;

static SIX_DIGITS: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^\d{6}$").expect("static regex"));

fn mfa_error(message: &str) -> AppError {
    AppError::BadRequest(message.to_owned())
}

async fn require_desktop_lock_for_mfa(state: &AppState) -> AppResult<()> {
    if state.config.desktop_mode
        && !crate::setup_auth::settings::desktop_lock_enabled(state).await?
    {
        return Err(mfa_error(
            "Enable Desktop password lock before configuring two-factor authentication.",
        ));
    }
    Ok(())
}

#[derive(crate::db::DbFromRow)]
struct UserMfa {
    id: i64,
    enabled: bool,
    totp_secret_enc: String,
    last_used_step: Option<i64>,
    enrolled_at: Option<DateTime<Utc>>,
}

async fn find<'e>(
    db: impl crate::db::DbExec<'e>,
    user_id: i64,
) -> Result<Option<UserMfa>, sqlx::Error> {
    crate::db::query_as("SELECT id, enabled, totp_secret_enc, last_used_step, enrolled_at FROM user_mfa WHERE user_id = $1")
        .bind(user_id)
        .fetch_optional(db)
        .await
}

pub async fn is_enabled(db: &Db, user_id: i64) -> Result<bool, sqlx::Error> {
    crate::db::query_scalar("SELECT EXISTS (SELECT 1 FROM user_mfa WHERE user_id = $1 AND enabled)")
        .bind(user_id)
        .fetch_one(db)
        .await
}

pub async fn require_reauth(user: &AppUser, current_password: &str) -> AppResult<()> {
    if password::matches(current_password, &user.password_hash).await {
        Ok(())
    } else {
        Err(mfa_error("Current password is incorrect"))
    }
}

fn code_at(secret: &str, step: i64) -> Option<String> {
    let secret = Secret::try_from_base32(secret.trim_end_matches('=').to_ascii_uppercase()).ok()?;
    let totp = Builder::new()
        .with_secret(secret)
        .with_digits(6)
        .with_step_duration(PERIOD)
        .build_noncompliant();
    Some(
        totp.generate(u64::try_from(step).ok()? * PERIOD)
            .to_string(),
    )
}

fn constant_time_eq(a: &str, b: &str) -> bool {
    use subtle::ConstantTimeEq;
    a.len() == b.len() && bool::from(a.as_bytes().ct_eq(b.as_bytes()))
}

/// The matched time step, trying the current one first, then one either side; steps at or
/// below min_step are rejected (anti-replay).
fn match_step(state: &AppState, secret: &str, code: &str, min_step: Option<i64>) -> Option<i64> {
    if code.chars().count() != 6 {
        return None;
    }
    let current = state.clock.now().timestamp() / PERIOD as i64;
    for delta in [0, -1, 1] {
        let step = current + delta;
        if min_step.is_some_and(|min| step <= min) {
            continue;
        }
        let Some(expected) = code_at(secret, step) else {
            tracing::warn!("TOTP code generation failed");
            return None;
        };
        if constant_time_eq(&expected, code) {
            return Some(step);
        }
    }
    None
}

pub async fn verify_totp(state: &AppState, user: &AppUser, code: &str) -> AppResult<bool> {
    let Some(mfa) = find(&state.db, user.id).await?.filter(|m| m.enabled) else {
        return Ok(false);
    };
    let secret = state.crypto.decrypt(&mfa.totp_secret_enc)?;
    let Some(step) = match_step(state, &secret, code, mfa.last_used_step) else {
        return Ok(false);
    };
    crate::db::query("UPDATE user_mfa SET last_used_step = $1, updated_at = now() WHERE id = $2")
        .bind(step)
        .bind(mfa.id)
        .execute(&state.db)
        .await?;
    Ok(true)
}

pub async fn verify_recovery_code(state: &AppState, user: &AppUser, code: &str) -> AppResult<bool> {
    let Some(mfa) = find(&state.db, user.id).await?.filter(|m| m.enabled) else {
        return Ok(false);
    };
    let codes: Vec<(i64, String)> =
        crate::db::query_as("SELECT id, code_hash FROM user_mfa_recovery_code WHERE user_mfa_id = $1 AND used_at IS NULL")
            .bind(mfa.id)
            .fetch_all(&state.db)
            .await?;
    for (id, hash) in codes {
        if password::matches(code, &hash).await {
            crate::db::query("UPDATE user_mfa_recovery_code SET used_at = $1 WHERE id = $2")
                .bind(state.clock.now())
                .bind(id)
                .execute(&state.db)
                .await?;
            return Ok(true);
        }
    }
    Ok(false)
}

pub async fn verify_totp_or_recovery(
    state: &AppState,
    user: &AppUser,
    code: &str,
    recovery: bool,
) -> AppResult<bool> {
    if recovery {
        verify_recovery_code(state, user, code).await
    } else {
        verify_totp(state, user, code).await
    }
}

async fn generate_recovery_codes(state: &AppState, mfa_id: i64) -> AppResult<Vec<String>> {
    let mut plain = HashSet::new();
    let mut ordered = Vec::new();
    while ordered.len() < RECOVERY_CODE_COUNT {
        let code = format!("{:08}", rand::rng().random_range(0..100_000_000u32));
        if plain.insert(code.clone()) {
            ordered.push(code);
        }
    }
    let hashes = futures::future::try_join_all(ordered.iter().map(|c| password::hash(c))).await?;
    for hash in hashes {
        crate::db::query(
            "INSERT INTO user_mfa_recovery_code (user_mfa_id, code_hash) VALUES ($1, $2)",
        )
        .bind(mfa_id)
        .bind(hash)
        .execute(&state.db)
        .await?;
    }
    Ok(ordered)
}

/// samstevens QrData.getUri with URLEncoder semantics (+ written as %20).
fn otpauth_uri(issuer: &str, account: &str, secret: &str) -> String {
    let enc = |s: &str| {
        url::form_urlencoded::byte_serialize(s.as_bytes())
            .collect::<String>()
            .replace('+', "%20")
    };
    format!(
        "otpauth://totp/{}?secret={}&issuer={}&algorithm=SHA1&digits=6&period={PERIOD}",
        enc(&format!("{issuer}:{account}")),
        enc(secret),
        enc(issuer)
    )
}

fn qr_data_uri(uri: &str) -> AppResult<String> {
    let code =
        qrcode::QrCode::new(uri.as_bytes()).map_err(|_| mfa_error("Failed to generate QR code"))?;
    let image = code
        .render::<image::Luma<u8>>()
        .min_dimensions(350, 350)
        .build();
    let mut png = Vec::new();
    image
        .write_to(&mut Cursor::new(&mut png), image::ImageFormat::Png)
        .map_err(|_| mfa_error("Failed to generate QR code"))?;
    Ok(format!("data:image/png;base64,{}", STANDARD.encode(png)))
}

#[skip_serializing_none]
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StatusResponse {
    enabled: bool,
    enrolled_at: Option<DateTime<Utc>>,
    remaining_recovery_codes: i64,
}

pub async fn status(State(state): State<AppState>, auth: Auth) -> AppResult<Response> {
    let response = match find(&state.db, auth.user.id).await?.filter(|m| m.enabled) {
        Some(mfa) => StatusResponse {
            enabled: true,
            enrolled_at: mfa.enrolled_at,
            remaining_recovery_codes: crate::db::query_scalar(
                "SELECT count(*) FROM user_mfa_recovery_code WHERE user_mfa_id = $1 AND used_at IS NULL",
            )
            .bind(mfa.id)
            .fetch_one(&state.db)
            .await?,
        },
        None => StatusResponse { enabled: false, enrolled_at: None, remaining_recovery_codes: 0 },
    };
    Ok(axum::Json(response).into_response())
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PasswordRequest {
    current_password: Option<String>,
}

impl Validate for PasswordRequest {
    fn validate(&self, e: &mut Errors) {
        let p = self.current_password.as_deref();
        e.not_blank("currentPassword", p)
            .size("currentPassword", p, 0, 128);
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct EnrollInitResponse {
    qr_code_data_uri: String,
    secret: String,
}

pub async fn enroll_init(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<PasswordRequest>,
) -> AppResult<Response> {
    if !state
        .limits
        .try_consume(&policies::MFA_ENROLL, &ip.as_java_string())
    {
        return Ok(StatusCode::TOO_MANY_REQUESTS.into_response());
    }
    require_desktop_lock_for_mfa(&state).await?;
    require_reauth(&auth.user, &req.current_password.unwrap_or_default()).await?;
    let existing = find(&state.db, auth.user.id).await?;
    if existing.as_ref().is_some_and(|m| m.enabled) {
        return Err(mfa_error("MFA is already enabled"));
    }
    let mut raw = [0u8; 20];
    rand::rng().fill(&mut raw[..]);
    let secret = Secret::from(raw).to_base32();
    let encrypted = state.crypto.encrypt(&secret)?;
    crate::db::query(
        "INSERT INTO user_mfa (user_id, enabled, totp_secret_enc, last_used_step, enrolled_at) VALUES ($1, false, $2, NULL, NULL) \
         ON CONFLICT (user_id) DO UPDATE SET enabled = false, totp_secret_enc = EXCLUDED.totp_secret_enc, \
         last_used_step = NULL, enrolled_at = NULL, updated_at = now()",
    )
    .bind(auth.user.id)
    .bind(encrypted)
    .execute(&state.db)
    .await?;
    let qr = qr_data_uri(&otpauth_uri(
        &state.config.mfa_issuer,
        &auth.user.username,
        &secret,
    ))?;
    Ok(axum::Json(EnrollInitResponse {
        qr_code_data_uri: qr,
        secret,
    })
    .into_response())
}

#[derive(Debug, Deserialize)]
pub struct CodeRequest {
    code: Option<String>,
}

impl Validate for CodeRequest {
    fn validate(&self, e: &mut Errors) {
        let c = self.code.as_deref();
        e.not_blank("code", c)
            .pattern("code", c, &SIX_DIGITS, "TOTP code must be 6 digits");
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RecoveryCodesResponse {
    recovery_codes: Vec<String>,
}

pub async fn enroll_verify(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<CodeRequest>,
) -> AppResult<Response> {
    require_desktop_lock_for_mfa(&state).await?;
    let mfa = find(&state.db, auth.user.id)
        .await?
        .ok_or_else(|| mfa_error("MFA enrollment not started"))?;
    if mfa.enabled {
        return Err(mfa_error("MFA is already enabled"));
    }
    let secret = state.crypto.decrypt(&mfa.totp_secret_enc)?;
    let step = match_step(&state, &secret, &req.code.unwrap_or_default(), None)
        .ok_or_else(|| mfa_error("Invalid verification code"))?;
    crate::db::query("UPDATE user_mfa SET enabled = true, enrolled_at = $1, last_used_step = $2, updated_at = now() WHERE id = $3")
        .bind(state.clock.now())
        .bind(step)
        .bind(mfa.id)
        .execute(&state.db)
        .await?;
    let codes = generate_recovery_codes(&state, mfa.id).await?;
    // Remembered devices predate the second factor and no longer satisfy the policy.
    persistent::revoke_all_for_user(&state, auth.user.id).await?;
    Ok(axum::Json(RecoveryCodesResponse {
        recovery_codes: codes,
    })
    .into_response())
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DisableRequest {
    current_password: Option<String>,
    code: Option<String>,
    is_recovery_code: Option<bool>,
}

impl Validate for DisableRequest {
    fn validate(&self, e: &mut Errors) {
        let p = self.current_password.as_deref();
        e.not_blank("currentPassword", p)
            .size("currentPassword", p, 0, 128);
        let c = self.code.as_deref();
        e.not_blank("code", c).size("code", c, 0, 16);
    }
}

pub async fn disable(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<DisableRequest>,
) -> AppResult<Response> {
    require_reauth(&auth.user, &req.current_password.unwrap_or_default()).await?;
    let recovery = req.is_recovery_code == Some(true);
    if !verify_totp_or_recovery(&state, &auth.user, &req.code.unwrap_or_default(), recovery).await?
    {
        return Err(mfa_error("Invalid verification code"));
    }
    if let Some(mfa) = find(&state.db, auth.user.id).await? {
        crate::db::query("DELETE FROM user_mfa_recovery_code WHERE user_mfa_id = $1")
            .bind(mfa.id)
            .execute(&state.db)
            .await?;
        crate::db::query("DELETE FROM user_mfa WHERE id = $1")
            .bind(mfa.id)
            .execute(&state.db)
            .await?;
    }
    persistent::revoke_all_for_user(&state, auth.user.id).await?;
    Ok(StatusCode::NO_CONTENT.into_response())
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RegenerateRequest {
    current_password: Option<String>,
    code: Option<String>,
}

impl Validate for RegenerateRequest {
    fn validate(&self, e: &mut Errors) {
        let p = self.current_password.as_deref();
        e.not_blank("currentPassword", p)
            .size("currentPassword", p, 0, 128);
        let c = self.code.as_deref();
        e.not_blank("code", c)
            .pattern("code", c, &SIX_DIGITS, "TOTP code must be 6 digits");
    }
}

pub async fn regenerate(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<RegenerateRequest>,
) -> AppResult<Response> {
    require_reauth(&auth.user, &req.current_password.unwrap_or_default()).await?;
    // Only TOTP: a consumed recovery code must not spawn ten fresh ones.
    if !verify_totp(&state, &auth.user, &req.code.unwrap_or_default()).await? {
        return Err(mfa_error("Invalid verification code"));
    }
    let mfa = find(&state.db, auth.user.id)
        .await?
        .filter(|m| m.enabled)
        .ok_or_else(|| mfa_error("MFA is not enabled"))?;
    crate::db::query("DELETE FROM user_mfa_recovery_code WHERE user_mfa_id = $1")
        .bind(mfa.id)
        .execute(&state.db)
        .await?;
    let codes = generate_recovery_codes(&state, mfa.id).await?;
    Ok(axum::Json(RecoveryCodesResponse {
        recovery_codes: codes,
    })
    .into_response())
}

/// The current TOTP code of a base32 secret at the clock's time; tests use it to answer challenges.
pub fn current_code(state: &AppState, secret: &str) -> Option<String> {
    code_at(secret, state.clock.now().timestamp() / PERIOD as i64)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rfc6238_vector() {
        let secret = Secret::from(b"12345678901234567890".to_vec()).to_base32();
        assert_eq!(code_at(&secret, 59 / 30).as_deref(), Some("287082"));
    }

    #[test]
    fn otpauth_uri_like_samstevens() {
        assert_eq!(
            otpauth_uri("Picsou", "ad min", "ABC"),
            "otpauth://totp/Picsou%3Aad%20min?secret=ABC&issuer=Picsou&algorithm=SHA1&digits=6&period=30"
        );
    }
}
