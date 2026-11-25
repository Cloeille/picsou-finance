//! Local Desktop Mode endpoints: zero-friction quick-start onboarding, loopback auto-login,
//! and optional password lock management.
//!
//! Every handler in this module requires `DesktopLoopback`, which returns 404 unless
//! `APP_DESKTOP_MODE=true` and 403 unless both the raw TCP peer and the resolved client IP are
//! loopback (`127.0.0.1` / `::1`).

use std::sync::atomic::Ordering;

use axum::extract::{FromRequestParts, Query, State};
use axum::http::request::Parts;
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Serialize};
use subtle::ConstantTimeEq;

use crate::AppState;
use crate::auth::{AppUser, Auth, Role, cookies, password, users};
use crate::db::AsDbHandle;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::{ClientIp, PeerIp};
use crate::http::json::{Errors, Json, Lang, Validate};
use crate::http::rate_limit::policies;
use crate::ledger::categorize;
use crate::setup_auth::auth::{RequestInfo, cookie_jar, issue_session, user_payload};
use crate::setup_auth::{audit, mfa, settings, setup};

/// HttpOnly, SameSite=Strict gate cookie issued once per launch by `GET /api/auth/desktop-bootstrap`.
pub const DESKTOP_GATE_COOKIE: &str = "picsou_desktop_gate";

/// Alternative direct header carrying the per-launch `APP_DESKTOP_TOKEN` for native clients/tests.
pub const DESKTOP_TOKEN_HEADER: &str = "x-picsou-desktop-token";

fn constant_time_str_eq(a: &str, b: &str) -> bool {
    a.len() == b.len() && bool::from(a.as_bytes().ct_eq(b.as_bytes()))
}

/// Guard extractor that runs before any body parser: 404 when Desktop Mode is off, 403 when the
/// request did not originate from loopback.
pub struct DesktopLoopback;

impl FromRequestParts<AppState> for DesktopLoopback {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        if !state.config.desktop_mode {
            return Err(AppError::status(StatusCode::NOT_FOUND, "Not found"));
        }
        let client = ClientIp::from_request_parts(parts, state)
            .await
            .expect("infallible ClientIp");
        let peer = PeerIp::from_request_parts(parts, state)
            .await
            .expect("infallible PeerIp");
        if !client.is_loopback() || !peer.is_loopback() {
            return Err(AppError::status(
                StatusCode::FORBIDDEN,
                "Desktop endpoints are restricted to local loopback connections.",
            ));
        }
        Ok(Self)
    }
}

/// Returns `true` when `headers` carry valid Desktop session authorization:
/// - `APP_DESKTOP_TOKEN` is configured (fail-closed when unset), AND
/// - either the `picsou_desktop_gate` cookie matches `desktop_gate_cookie_secret`, OR
/// - the `X-Picsou-Desktop-Token` header matches `APP_DESKTOP_TOKEN` while the single-use
///   bootstrap exchange has not yet been consumed.
pub(crate) fn has_valid_desktop_gate(headers: &HeaderMap, state: &AppState) -> bool {
    let Some(expected_token) = state.config.desktop_token.as_deref() else {
        return false;
    };
    let cookie_ok = cookies::read(headers, DESKTOP_GATE_COOKIE)
        .is_some_and(|v| constant_time_str_eq(&v, &state.desktop_gate_cookie_secret));
    if cookie_ok {
        return true;
    }
    if state.desktop_bootstrap_consumed.load(Ordering::Acquire) {
        return false;
    }
    headers
        .get(DESKTOP_TOKEN_HEADER)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| constant_time_str_eq(v, expected_token))
}

/// Whether autologin would sign `user` in with no password, the gate of the app's window
/// granted: the password lock is off and `user` is the account autologin gives, the primary one
/// without two-factor. Where that holds, a session of `user` proves no more than the window
/// itself. Mirrors the checks of `autologin`.
pub(crate) async fn autologin_admits(state: &AppState, user: &AppUser) -> AppResult<bool> {
    if settings::desktop_lock_enabled(state).await? {
        return Ok(false);
    }
    let primary = users::find_primary_active(&state.db).await?;
    Ok(primary.is_some_and(|primary| primary.id == user.id)
        && !mfa::is_enabled(&state.db, user.id).await?)
}

/// Fail-closed extractor for unauthenticated Desktop onboarding & auto-login endpoints.
///
/// Requires `DesktopLoopback` AND `APP_DESKTOP_TOKEN` configured AND either:
/// - Cookie `picsou_desktop_gate` matching the per-process secret in constant time, OR
/// - Header `X-Picsou-Desktop-Token` matching `APP_DESKTOP_TOKEN` in constant time (only until
///   `/api/auth/desktop-bootstrap` consumes the single-use launch token).
pub struct DesktopSessionGate;

impl FromRequestParts<AppState> for DesktopSessionGate {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        DesktopLoopback::from_request_parts(parts, state).await?;
        if state.config.desktop_token.is_none() {
            return Err(AppError::status(
                StatusCode::FORBIDDEN,
                "Desktop session token is not configured.",
            ));
        }
        if !has_valid_desktop_gate(&parts.headers, state) {
            return Err(AppError::status(
                StatusCode::FORBIDDEN,
                "Desktop session authorization required.",
            ));
        }
        Ok(Self)
    }
}

#[derive(Debug, Deserialize)]
pub struct BootstrapQuery {
    token: Option<String>,
}

/// GET /api/auth/desktop-bootstrap?token=... — single-use per-launch token exchange that hands
/// Tauri's main WebView an `HttpOnly; SameSite=Strict; Path=/api` gate cookie and redirects to `/`.
pub async fn bootstrap(
    State(state): State<AppState>,
    _guard: DesktopLoopback,
    Query(query): Query<BootstrapQuery>,
) -> AppResult<Response> {
    let Some(expected) = state.config.desktop_token.as_deref() else {
        return Err(AppError::status(
            StatusCode::FORBIDDEN,
            "Desktop session token is not configured.",
        ));
    };
    let Some(provided) = query.token.as_deref() else {
        return Err(AppError::status(
            StatusCode::FORBIDDEN,
            "Invalid desktop bootstrap token.",
        ));
    };
    if !constant_time_str_eq(provided, expected) {
        return Err(AppError::status(
            StatusCode::FORBIDDEN,
            "Invalid desktop bootstrap token.",
        ));
    }
    if state
        .desktop_bootstrap_consumed
        .swap(true, Ordering::AcqRel)
    {
        return Err(AppError::status(
            StatusCode::FORBIDDEN,
            "Desktop bootstrap token has already been used.",
        ));
    }
    let cookie_value = format!(
        "{DESKTOP_GATE_COOKIE}={}; HttpOnly; SameSite=Strict; Path=/api",
        state.desktop_gate_cookie_secret
    );
    let mut response = StatusCode::SEE_OTHER.into_response();
    let headers = response.headers_mut();
    headers.insert(header::LOCATION, HeaderValue::from_static("/"));
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    headers.insert(
        header::SET_COOKIE,
        HeaderValue::from_str(&cookie_value)
            .map_err(|_| AppError::Internal("invalid desktop gate cookie".into()))?,
    );
    Ok(response)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DesktopQuickStartRequest {
    display_name: Option<String>,
}

impl Validate for DesktopQuickStartRequest {
    fn validate(&self, e: &mut Errors) {
        e.size("displayName", self.display_name.as_deref(), 0, 80);
    }
}

fn sanitize_display_name(raw: Option<&str>, headers: &HeaderMap) -> String {
    let cleaned: String = raw
        .unwrap_or_default()
        .chars()
        .filter(|c| !c.is_control())
        .collect();
    let trimmed = cleaned.trim();
    if !trimmed.is_empty() {
        return trimmed.chars().take(80).collect();
    }
    let lang = Lang::from_accept_language(
        headers
            .get(header::ACCEPT_LANGUAGE)
            .and_then(|v| v.to_str().ok()),
    );
    match lang {
        Lang::French => "Mon espace".to_owned(),
        Lang::English => "My Space".to_owned(),
    }
}

/// Derives a valid `[a-zA-Z0-9._-]` username (3..=50 chars) from the display name, falling back
/// to `"local"` when fewer than 3 ASCII characters remain.
fn derive_username(display_name: &str) -> String {
    let mut out = String::new();
    let mut last_sep = true;
    for ch in display_name.chars() {
        let mapped = match ch.to_lowercase().next().unwrap_or(ch) {
            'a'..='z' | '0'..='9' => Some(ch.to_ascii_lowercase().to_string()),
            'à' | 'á' | 'â' | 'ã' | 'ä' | 'å' => Some("a".into()),
            'ç' => Some("c".into()),
            'è' | 'é' | 'ê' | 'ë' => Some("e".into()),
            'ì' | 'í' | 'î' | 'ï' => Some("i".into()),
            'ñ' => Some("n".into()),
            'ò' | 'ó' | 'ô' | 'õ' | 'ö' => Some("o".into()),
            'ù' | 'ú' | 'û' | 'ü' => Some("u".into()),
            'ý' | 'ÿ' => Some("y".into()),
            'ß' => Some("ss".into()),
            '.' | '_' | '-' => {
                if last_sep {
                    None
                } else {
                    last_sep = true;
                    out.push(ch);
                    None
                }
            }
            _ => {
                if !last_sep {
                    last_sep = true;
                    out.push('-');
                }
                None
            }
        };
        if let Some(s) = mapped {
            out.push_str(&s);
            last_sep = false;
        }
        if out.len() >= 50 {
            break;
        }
    }
    let trimmed = out.trim_matches(['-', '.', '_']);
    if trimmed.len() < 3 {
        "local".to_owned()
    } else {
        trimmed.chars().take(50).collect()
    }
}

/// POST /api/setup/desktop-quick-start — 1-step local onboarding for Desktop Mode.
pub async fn quick_start(
    State(state): State<AppState>,
    _guard: DesktopSessionGate,
    ip: ClientIp,
    headers: HeaderMap,
    Json(req): Json<DesktopQuickStartRequest>,
) -> AppResult<Response> {
    let ip_str = ip.as_java_string();
    if !state.limits.try_consume(&policies::SETUP, &ip_str) {
        return Ok(setup::rate_limited());
    }
    setup::require_not_complete(&state).await?;

    let display_name = sanitize_display_name(req.display_name.as_deref(), &headers);
    let username = derive_username(&display_name);

    // Generate a 32-byte cryptographically random secret so no weak default password ever exists.
    let secret_bytes: [u8; 32] = rand::random();
    let random_secret = hex::encode(secret_bytes);
    let hash = password::hash(&random_secret).await?;

    let mut tx = state.db.begin().await?;
    if !crate::db::DbExec::is_sqlite(&&state.db) {
        crate::db::query("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE")
            .execute(&mut tx)
            .await?;
    }
    let user = setup::seed_admin_on(
        &mut tx.as_handle(),
        &username,
        &hash,
        Some(&display_name),
        None,
    )
    .await?;
    settings::upsert(&mut tx, settings::SETUP_STATE, "COMPLETE").await?;
    categorize::ensure_seeded_on(&mut tx.as_handle(), user.member_id, state.clock.now()).await?;
    settings::upsert(&mut tx, settings::DESKTOP_LOCK_ENABLED, "false").await?;
    tx.commit().await?;
    tracing::info!("setup.admin.created role=ADMIN");
    setup::mark_complete_cached(&state);

    audit::record(
        &state,
        "setup.desktop.quick_start",
        Some(&user.username),
        &setup::audit_ctx(&ip_str, &headers),
        None,
    )
    .await;

    let mut jar = cookie_jar(&state).await;
    issue_session(
        &state,
        &mut jar,
        &user,
        false,
        false,
        None,
        RequestInfo {
            ip: &ip,
            headers: &headers,
        },
    )
    .await?;
    Ok((jar, user_payload(&user)).into_response())
}

/// POST /api/auth/desktop-autologin — seamless loopback sign-in when Desktop lock is disabled.
pub async fn autologin(
    State(state): State<AppState>,
    _guard: DesktopSessionGate,
    ip: ClientIp,
    headers: HeaderMap,
) -> AppResult<Response> {
    let ip_str = ip.as_java_string();
    if !state
        .limits
        .try_consume(&policies::DESKTOP_AUTOLOGIN, &ip_str)
    {
        return Err(AppError::status(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many requests. Please wait before trying again.",
        ));
    }
    if settings::desktop_lock_enabled(&state).await? {
        return Err(AppError::coded(
            StatusCode::FORBIDDEN,
            "DESKTOP_LOCK_ENABLED",
            "Password lock is enabled; sign in with your password.",
        ));
    }
    let Some(user) = users::find_primary_active(&state.db).await? else {
        return Err(AppError::status(
            StatusCode::NOT_FOUND,
            "No active local account found.",
        ));
    };
    if mfa::is_enabled(&state.db, user.id).await? {
        return Err(AppError::coded(
            StatusCode::FORBIDDEN,
            "DESKTOP_LOCK_ENABLED",
            "Two-factor authentication is enabled; sign in with your password.",
        ));
    }
    let mut jar = cookie_jar(&state).await;
    issue_session(
        &state,
        &mut jar,
        &user,
        false,
        false,
        None,
        RequestInfo {
            ip: &ip,
            headers: &headers,
        },
    )
    .await?;
    Ok((jar, user_payload(&user)).into_response())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DesktopLockResponse {
    enabled: bool,
}

fn require_admin_session(auth: &Auth) -> AppResult<()> {
    if auth.role() != Some(Role::Admin) {
        return Err(AppError::status(
            StatusCode::FORBIDDEN,
            "Admin access required.",
        ));
    }
    Ok(())
}

/// GET /api/desktop/lock — returns whether Desktop password lock is enabled.
pub async fn lock_status(
    State(state): State<AppState>,
    _guard: DesktopLoopback,
    auth: Auth,
) -> AppResult<axum::Json<impl Serialize>> {
    require_admin_session(&auth)?;
    let enabled = settings::desktop_lock_enabled(&state).await?;
    Ok(axum::Json(DesktopLockResponse { enabled }))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DesktopLockRequest {
    enabled: bool,
    new_password: Option<String>,
    current_password: Option<String>,
}

impl Validate for DesktopLockRequest {
    fn validate(&self, e: &mut Errors) {
        if self.enabled {
            let p = self.new_password.as_deref();
            e.not_blank("newPassword", p).size("newPassword", p, 8, 128);
        } else {
            e.not_blank("currentPassword", self.current_password.as_deref());
        }
    }
}

/// PUT /api/desktop/lock — enable (with a strong new password) or disable (verifying current
/// password) Desktop password lock.
pub async fn update_lock(
    State(state): State<AppState>,
    _guard: DesktopLoopback,
    auth: Auth,
    ip: ClientIp,
    headers: HeaderMap,
    Json(req): Json<DesktopLockRequest>,
) -> AppResult<Response> {
    require_admin_session(&auth)?;
    let ip_str = ip.as_java_string();
    if !state.limits.try_consume(&policies::DESKTOP_LOCK, &ip_str) {
        return Err(AppError::status(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many attempts. Please wait before trying again.",
        ));
    }
    let mut user = auth.user;

    if req.enabled {
        if settings::desktop_lock_enabled(&state).await? {
            return Err(AppError::coded(
                StatusCode::CONFLICT,
                "DESKTOP_LOCK_ALREADY_ENABLED",
                "Password lock is already enabled.",
            ));
        }
        let hash = password::hash(&req.new_password.unwrap_or_default()).await?;
        let mut tx = state.db.begin().await?;
        if settings::desktop_lock_enabled_with(&mut tx).await? {
            return Err(AppError::coded(
                StatusCode::CONFLICT,
                "DESKTOP_LOCK_ALREADY_ENABLED",
                "Password lock is already enabled.",
            ));
        }
        user.token_version = crate::db::query_scalar(
            "UPDATE app_user SET password_hash = $1, token_version = token_version + 1, updated_at = now() \
             WHERE id = $2 RETURNING token_version",
        )
        .bind(&hash)
        .bind(user.id)
        .fetch_one(&mut tx)
        .await?;
        crate::db::query(
            "UPDATE persistent_session SET revoked_at = $1 WHERE user_id = $2 AND revoked_at IS NULL",
        )
        .bind(state.clock.now())
        .bind(user.id)
        .execute(&mut tx)
        .await?;
        settings::upsert(&mut tx, settings::DESKTOP_LOCK_ENABLED, "true").await?;
        tx.commit().await?;

        audit::record(
            &state,
            "desktop.lock.enabled",
            Some(&user.username),
            &setup::audit_ctx(&ip_str, &headers),
            None,
        )
        .await;

        let mut jar = cookie_jar(&state).await;
        jar.access_and_refresh(
            &state.jwt,
            &state.jwt.access_token(&user, None),
            &state.jwt.refresh_token(&user, None, None),
            false,
        );
        jar.clear_persistent();
        return Ok((jar, axum::Json(DesktopLockResponse { enabled: true })).into_response());
    }

    if !password::matches(
        &req.current_password.unwrap_or_default(),
        &user.password_hash,
    )
    .await
    {
        return Err(AppError::coded(
            StatusCode::UNAUTHORIZED,
            "REAUTH_FAILED",
            "Invalid current password.",
        ));
    }
    let mut tx = state.db.begin().await?;
    let mut mfa_active = mfa::is_enabled(&mut tx, user.id).await?;
    if !mfa_active
        && let Some(primary) = users::find_primary_active(&mut tx).await?
        && primary.id != user.id
    {
        mfa_active = mfa::is_enabled(&mut tx, primary.id).await?;
    }
    if mfa_active {
        return Err(AppError::coded(
            StatusCode::CONFLICT,
            "MFA_ACTIVE",
            "Disable two-factor authentication before turning off Desktop password lock.",
        ));
    }
    settings::upsert(&mut tx, settings::DESKTOP_LOCK_ENABLED, "false").await?;
    tx.commit().await?;
    audit::record(
        &state,
        "desktop.lock.disabled",
        Some(&user.username),
        &setup::audit_ctx(&ip_str, &headers),
        None,
    )
    .await;

    Ok(axum::Json(DesktopLockResponse { enabled: false }).into_response())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn derives_clean_usernames_with_local_fallback() {
        assert_eq!(derive_username("Camille"), "camille");
        assert_eq!(derive_username("Éléonore Dupont"), "eleonore-dupont");
        assert_eq!(derive_username("Mon espace"), "mon-espace");
        assert_eq!(derive_username("Jo"), "local");
        assert_eq!(derive_username("🎉🚀"), "local");
        assert_eq!(derive_username("  Alice_42  "), "alice_42");
    }
}
