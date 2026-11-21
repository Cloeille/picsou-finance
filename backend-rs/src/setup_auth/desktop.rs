//! Local Desktop Mode endpoints: zero-friction quick-start onboarding, loopback auto-login,
//! and optional password lock management.
//!
//! Every handler in this module requires `DesktopLoopback`, which returns 404 unless
//! `APP_DESKTOP_MODE=true` and 403 unless both the raw TCP peer and the resolved client IP are
//! loopback (`127.0.0.1` / `::1`).

use axum::extract::{FromRequestParts, State};
use axum::http::request::Parts;
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Serialize};

use crate::AppState;
use crate::auth::{Auth, password, persistent, users};
use crate::error::{AppError, AppResult};
use crate::http::client_ip::{ClientIp, PeerIp};
use crate::http::json::{Errors, Json, Lang, Validate};
use crate::http::rate_limit::policies;
use crate::ledger::categorize;
use crate::setup_auth::auth::{RequestInfo, cookie_jar, issue_session, user_payload};
use crate::setup_auth::{audit, settings, setup};

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
    _guard: DesktopLoopback,
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

    let user = setup::seed_admin(&state, &username, &hash, Some(&display_name), None).await?;
    setup::mark_complete(&state).await?;
    categorize::ensure_seeded(&state.db, user.member_id, state.clock.now()).await?;
    settings::upsert(&state.db, settings::DESKTOP_LOCK_ENABLED, "false").await?;

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
    _guard: DesktopLoopback,
    ip: ClientIp,
    headers: HeaderMap,
) -> AppResult<Response> {
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

/// GET /api/desktop/lock — returns whether Desktop password lock is enabled.
pub async fn lock_status(
    State(state): State<AppState>,
    _guard: DesktopLoopback,
    _auth: Auth,
) -> AppResult<axum::Json<impl Serialize>> {
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
    let ip_str = ip.as_java_string();
    let mut user = auth.user;

    if req.enabled {
        let hash = password::hash(&req.new_password.unwrap_or_default()).await?;
        user.token_version += 1;
        sqlx::query(
            "UPDATE app_user SET password_hash = $1, token_version = $2, updated_at = now() WHERE id = $3",
        )
        .bind(&hash)
        .bind(user.token_version)
        .bind(user.id)
        .execute(&state.db)
        .await?;
        persistent::revoke_all_for_user(&state, user.id).await?;
        settings::upsert(&state.db, settings::DESKTOP_LOCK_ENABLED, "true").await?;

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
        return Err(AppError::BadCredentials);
    }
    settings::upsert(&state.db, settings::DESKTOP_LOCK_ENABLED, "false").await?;
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
        assert_eq!(derive_username("Louis"), "louis");
        assert_eq!(derive_username("Éléonore Dupont"), "eleonore-dupont");
        assert_eq!(derive_username("Mon espace"), "mon-espace");
        assert_eq!(derive_username("Jo"), "local");
        assert_eq!(derive_username("🎉🚀"), "local");
        assert_eq!(derive_username("  Alice_42  "), "alice_42");
    }
}
