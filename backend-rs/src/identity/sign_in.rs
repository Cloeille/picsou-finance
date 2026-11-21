//! SignInMethodsController, SignInMethodsService and ReAuthService: the SSO info, the caller's
//! sign-in methods, the re-authentication proof, and turning password login on and off.

use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::Connection;

use crate::AppState;
use crate::auth::cookies::Cookies;
use crate::auth::{AppUser, Auth, password, users};
use crate::error::{AppError, AppResult, Problem};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Path, Validate};
use crate::http::rate_limit::policies;
use crate::identity::common::{audit, reauth_failed, require_proof};
use crate::identity::sso;
use crate::setup_auth::auth::{RequestInfo, current_sso_at, issue_session};
use crate::setup_auth::{mfa, settings};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SsoInfoResponse {
    enabled: bool,
    provider_name: String,
}

pub async fn sso_info(State(state): State<AppState>) -> axum::Json<SsoInfoResponse> {
    let sso = sso::Settings::new(&state);
    axum::Json(SsoInfoResponse {
        enabled: sso.enabled(),
        provider_name: sso.provider_name(),
    })
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LinkedIdentity {
    id: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    email: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    display_name: Option<String>,
    created_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    last_login_at: Option<DateTime<Utc>>,
    current_issuer: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SignInMethodsResponse {
    has_password: bool,
    mfa_enabled: bool,
    identities: Vec<LinkedIdentity>,
    sso_enabled: bool,
    provider_name: String,
    admin_group_managed: bool,
}

#[derive(sqlx::FromRow)]
struct IdentityRow {
    id: i64,
    issuer: String,
    email: Option<String>,
    display_name: Option<String>,
    created_at: DateTime<Utc>,
    last_login_at: Option<DateTime<Utc>>,
}

fn has_password(user: &AppUser) -> bool {
    !user.password_hash.trim().is_empty()
}

pub async fn sign_in_methods(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<SignInMethodsResponse>> {
    let sso = sso::Settings::new(&state);
    let issuer = sso.issuer_uri();
    let rows: Vec<IdentityRow> = sqlx::query_as(
        "SELECT id, issuer, email, display_name, created_at, last_login_at FROM user_identity \
         WHERE user_id = $1 ORDER BY created_at ASC",
    )
    .bind(auth.user.id)
    .fetch_all(&state.db)
    .await?;
    Ok(axum::Json(SignInMethodsResponse {
        has_password: has_password(&auth.user),
        mfa_enabled: mfa::is_enabled(&state.db, auth.user.id).await?,
        identities: rows
            .into_iter()
            .map(|r| LinkedIdentity {
                id: r.id,
                current_issuer: r.issuer == issuer,
                email: r.email,
                display_name: r.display_name,
                created_at: r.created_at,
                last_login_at: r.last_login_at,
            })
            .collect(),
        sso_enabled: sso.enabled(),
        provider_name: sso.provider_name(),
        admin_group_managed: sso.admin_group_managed(),
    }))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReAuthRequest {
    password: Option<String>,
    totp_code: Option<String>,
}

impl Validate for ReAuthRequest {
    fn validate(&self, _: &mut Errors) {}
}

/// ReAuthService.verifyPasswordAndTotp: the password always, plus a TOTP code when 2FA is on.
async fn verify_password_and_totp(
    state: &AppState,
    user: &AppUser,
    request: &ReAuthRequest,
) -> AppResult<()> {
    let password = request.password.as_deref().unwrap_or_default();
    if password.trim().is_empty() {
        return Err(reauth_failed("password required"));
    }
    if !has_password(user) || !password::matches(password, &user.password_hash).await {
        return Err(reauth_failed("invalid password"));
    }
    if mfa::is_enabled(&state.db, user.id).await? {
        let code = request.totp_code.as_deref().unwrap_or_default();
        if code.trim().is_empty() {
            return Err(reauth_failed("totp required"));
        }
        if !mfa::verify_totp(state, user, code).await? {
            return Err(reauth_failed("invalid totp"));
        }
    }
    Ok(())
}

pub async fn reauth(
    State(state): State<AppState>,
    auth: Auth,
    Json(request): Json<ReAuthRequest>,
) -> AppResult<Response> {
    if !state
        .limits
        .try_consume(&policies::REAUTH, &auth.user.id.to_string())
    {
        return Ok(Problem::with_detail(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many re-authentication attempts. Try again in 15 minutes.",
        )
        .code("RATE_LIMITED")
        .into_response());
    }
    verify_password_and_totp(&state, &auth.user, &request).await?;
    let mut jar = Cookies::new(settings::secure_cookies(&state).await);
    jar.reauth_proof(&state.jwt, &state.jwt.reauth_proof(&auth.user));
    Ok((jar, StatusCode::NO_CONTENT).into_response())
}

async fn lock_user(
    tx: &mut sqlx::PgConnection,
    user_id: i64,
) -> AppResult<AppUser> {
    sqlx::query("SELECT id FROM app_user WHERE id = $1 FOR UPDATE")
        .bind(user_id)
        .execute(&mut *tx)
        .await?;
    users::find_by_id(&mut *tx, user_id)
        .await?
        .ok_or_else(|| AppError::NotFound("User not found".into()))
}

async fn delete_mfa(tx: &mut sqlx::PgConnection, user_id: i64) -> Result<(), sqlx::Error> {
    sqlx::query(
        "DELETE FROM user_mfa_recovery_code WHERE user_mfa_id IN (SELECT id FROM user_mfa WHERE user_id = $1)",
    )
    .bind(user_id)
    .execute(&mut *tx)
    .await?;
    sqlx::query("DELETE FROM user_mfa WHERE user_id = $1")
        .bind(user_id)
        .execute(&mut *tx)
        .await?;
    Ok(())
}

/// The password change's aftermath: a new session for this browser only, every series revoked.
async fn reissue(
    state: &AppState,
    user_id: i64,
    sso_at: Option<DateTime<Utc>>,
    ip: &ClientIp,
    headers: &HeaderMap,
) -> AppResult<Cookies> {
    let user = users::find_by_id(&state.db, user_id)
        .await?
        .ok_or_else(|| AppError::NotFound("User not found".into()))?;
    let mut jar = Cookies::new(settings::secure_cookies(state).await);
    issue_session(state, &mut jar, &user, false, false, sso_at, RequestInfo { ip, headers }).await?;
    // Every series was just revoked; a surviving persistent cookie would answer "Session revoked".
    jar.clear_persistent();
    Ok(jar)
}

pub async fn remove_password(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    headers: HeaderMap,
) -> AppResult<Response> {
    let sso = sso::Settings::new(&state);
    let issuer = sso.issuer_uri();
    let mut conn = state.db.acquire().await?;
    let mut tx = conn.begin().await?;
    let user = lock_user(&mut tx, auth.user.id).await?;
    require_proof(&state, &user, &headers)?;
    if !sso.enabled() {
        return Err(AppError::coded(
            StatusCode::CONFLICT,
            "SSO_DISABLED",
            "Single sign-on is not available, so password login cannot be turned off",
        ));
    }
    let used: bool = sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM user_identity WHERE user_id = $1 AND issuer = $2 AND last_login_at IS NOT NULL)",
    )
    .bind(user.id)
    .bind(&issuer)
    .fetch_one(&mut *tx)
    .await?;
    if !used {
        return Err(AppError::coded(
            StatusCode::CONFLICT,
            "IDENTITY_NOT_USED_YET",
            "Sign in once with single sign-on before turning password login off",
        ));
    }
    if !has_password(&user) {
        tx.commit().await?;
        return Ok(StatusCode::NO_CONTENT.into_response());
    }
    delete_mfa(&mut tx, user.id).await?;
    sqlx::query(
        "UPDATE app_user SET password_hash = '', token_version = token_version + 1, updated_at = now() WHERE id = $1",
    )
    .bind(user.id)
    .execute(&mut *tx)
    .await?;
    sqlx::query("UPDATE persistent_session SET revoked_at = $2 WHERE user_id = $1 AND revoked_at IS NULL")
        .bind(user.id)
        .bind(state.clock.now())
        .execute(&mut *tx)
        .await?;
    let latest: Option<DateTime<Utc>> = sqlx::query_scalar(
        "SELECT max(last_login_at) FROM user_identity WHERE user_id = $1 AND issuer = $2",
    )
    .bind(user.id)
    .bind(&issuer)
    .fetch_one(&mut *tx)
    .await?;
    tx.commit().await?;
    let jar = reissue(&state, user.id, latest, &ip, &headers).await?;
    audit(&state, "auth.password.removed", Some(&user.username), &ip, &headers, None).await;
    Ok((jar, StatusCode::NO_CONTENT).into_response())
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SetPasswordRequest {
    new_password: Option<String>,
}

impl Validate for SetPasswordRequest {
    fn validate(&self, e: &mut Errors) {
        let p = self.new_password.as_deref();
        e.not_blank("newPassword", p).size("newPassword", p, 8, 128);
    }
}

pub async fn set_password(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    headers: HeaderMap,
    Json(request): Json<SetPasswordRequest>,
) -> AppResult<Response> {
    let mut conn = state.db.acquire().await?;
    let mut tx = conn.begin().await?;
    let user = lock_user(&mut tx, auth.user.id).await?;
    require_proof(&state, &user, &headers)?;
    if has_password(&user) {
        return Err(AppError::coded(
            StatusCode::CONFLICT,
            "PASSWORD_ALREADY_SET",
            "This account already has a password",
        ));
    }
    let hash = password::hash(&request.new_password.unwrap_or_default()).await?;
    sqlx::query(
        "UPDATE app_user SET password_hash = $2, token_version = token_version + 1, updated_at = now() WHERE id = $1",
    )
    .bind(user.id)
    .bind(hash)
    .execute(&mut *tx)
    .await?;
    sqlx::query("UPDATE persistent_session SET revoked_at = $2 WHERE user_id = $1 AND revoked_at IS NULL")
        .bind(user.id)
        .bind(state.clock.now())
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    let sso_at = current_sso_at(&state, &headers).await?;
    let jar = reissue(&state, user.id, sso_at, &ip, &headers).await?;
    audit(&state, "auth.password.set", Some(&user.username), &ip, &headers, None).await;
    Ok((jar, StatusCode::NO_CONTENT).into_response())
}

pub async fn unlink_identity(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> AppResult<StatusCode> {
    let issuer = sso::Settings::new(&state).issuer_uri();
    let mut conn = state.db.acquire().await?;
    let mut tx = conn.begin().await?;
    let user = lock_user(&mut tx, auth.user.id).await?;
    require_proof(&state, &user, &headers)?;
    let identity_issuer: Option<String> =
        sqlx::query_scalar("SELECT issuer FROM user_identity WHERE id = $1 AND user_id = $2")
            .bind(id)
            .bind(user.id)
            .fetch_optional(&mut *tx)
            .await?;
    let Some(identity_issuer) = identity_issuer else {
        return Err(AppError::NotFound("Identity not found".into()));
    };
    if !has_password(&user) {
        let same_issuer: i64 =
            sqlx::query_scalar("SELECT count(*) FROM user_identity WHERE user_id = $1 AND issuer = $2")
                .bind(user.id)
                .bind(&issuer)
                .fetch_one(&mut *tx)
                .await?;
        let left = same_issuer - i64::from(identity_issuer == issuer);
        if left <= 0 {
            return Err(AppError::coded(
                StatusCode::CONFLICT,
                "LAST_SIGN_IN_METHOD",
                "Set a password before removing your last single sign-on identity",
            ));
        }
    }
    sqlx::query("DELETE FROM user_identity WHERE id = $1")
        .bind(id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    let details = format!("identityId={id}");
    audit(&state, "sso.identity.unlinked", Some(&user.username), &ip, &headers, Some(&details)).await;
    Ok(StatusCode::NO_CONTENT)
}

