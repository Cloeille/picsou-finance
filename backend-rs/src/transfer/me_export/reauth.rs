//! ReAuthService.verify: a valid reauth_proof cookie for this user and token version, else the
//! inline TOTP when 2FA is on, else the password.

use axum::http::HeaderMap;
use http::StatusCode;
use serde::Deserialize;

use crate::AppState;
use crate::auth::jwt::TokenType;
use crate::auth::{AppUser, cookies, password};
use crate::error::{AppError, AppResult, Problem};
use crate::ledger::sync_values::java_is_blank;
use crate::setup_auth::{desktop, mfa, settings};

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReAuth {
    password: Option<String>,
    totp_code: Option<String>,
}

/// GlobalExceptionHandler.handleReAuthFailed.
fn failed(message: &str) -> AppError {
    AppError::Problem(
        Problem::with_detail(StatusCode::UNAUTHORIZED, message)
            .title("REAUTH_FAILED")
            .code("REAUTH_FAILED"),
    )
}

fn present(value: Option<&str>) -> Option<&str> {
    value.filter(|v| !java_is_blank(v))
}

pub(crate) fn has_valid_proof(state: &AppState, user: &AppUser, headers: &HeaderMap) -> bool {
    let Some(token) = cookies::read(headers, cookies::REAUTH_PROOF) else {
        return false;
    };
    if java_is_blank(&token) {
        return false;
    }
    let Ok(claims) = state.jwt.parse(&token) else {
        return false;
    };
    claims.is(TokenType::Reauth)
        && claims.uid == Some(user.id)
        && claims.tv == Some(user.token_version)
}

/// The desktop app without its password lock: quick start gave the account a random password
/// nobody knows, and the gate cookie of the app's own window is what signed the member in. It is
/// the only proof there is, so it stands for the password here too. With the lock on, the
/// password is asked like anywhere else.
async fn signed_in_by_the_desktop_app(state: &AppState, headers: &HeaderMap) -> AppResult<bool> {
    Ok(state.config.desktop_mode
        && desktop::has_valid_desktop_gate(headers, state)
        && !settings::desktop_lock_enabled(state).await?)
}

pub(crate) async fn verify(
    state: &AppState,
    user: &AppUser,
    re_auth: Option<&ReAuth>,
    headers: &HeaderMap,
) -> AppResult<()> {
    if has_valid_proof(state, user, headers) || signed_in_by_the_desktop_app(state, headers).await?
    {
        return Ok(());
    }
    let Some(re_auth) = re_auth else {
        return Err(failed("re-auth payload missing"));
    };
    if mfa::is_enabled(&state.db, user.id).await? {
        let Some(code) = present(re_auth.totp_code.as_deref()) else {
            return Err(failed("totp required"));
        };
        if !mfa::verify_totp(state, user, code).await? {
            return Err(failed("invalid totp"));
        }
    } else {
        let Some(plain) = present(re_auth.password.as_deref()) else {
            return Err(failed("password required"));
        };
        if !password::matches(plain, &user.password_hash).await {
            return Err(failed("invalid password"));
        }
    }
    Ok(())
}
