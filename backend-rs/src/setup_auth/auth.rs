//! AuthController and SessionIssuer: password login with the MFA gate, MFA challenge, token
//! refresh, logout, username and password changes, invitation activation.

use std::sync::LazyLock;

use axum::extract::State;
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, Utc};
use regex::Regex;
use serde::{Deserialize, Serialize};
use sqlx::Connection;
use uuid::Uuid;

use crate::AppState;
use crate::auth::cookies::{self, Cookies};
use crate::auth::jwt::{Claims, TokenType};
use crate::auth::{AppUser, Auth, Role, SsoRevalidate, password, persistent, users};
use crate::error::{AppError, AppResult, Problem};
use crate::http::client_ip::{ClientIp, RequestOrigin};
use crate::http::json::{Errors, Json, Path, Validate, null_as_false};
use crate::http::rate_limit::policies;
use crate::setup_auth::audit::{self, AuditContext};
use crate::setup_auth::{mfa, settings};

static USERNAME: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[a-zA-Z0-9._-]+$").expect("static regex"));

/// The body every successful sign-in returns, in the Java HashMap's order.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UserPayload {
    role: &'static str,
    display_name: String,
    username: String,
    member_id: i64,
}

pub(crate) fn user_payload(user: &AppUser) -> axum::Json<UserPayload> {
    axum::Json(UserPayload {
        role: user.role.as_str(),
        display_name: user.display_name.clone(),
        username: user.username.clone(),
        member_id: user.member_id,
    })
}

pub(crate) async fn cookie_jar(state: &AppState) -> Cookies {
    Cookies::new(settings::secure_cookies(state).await)
}

fn bearer_token(headers: &HeaderMap) -> Option<String> {
    let value = headers.get(header::AUTHORIZATION)?.to_str().ok()?;
    let token = value.strip_prefix("Bearer ")?.trim();
    (!token.starts_with("psk_")).then(|| token.to_owned())
}

/// The request's access claims, from the cookie or a non-key Bearer token.
fn request_access_claims(state: &AppState, headers: &HeaderMap) -> Option<Claims> {
    let token = cookies::read(headers, cookies::ACCESS).or_else(|| bearer_token(headers))?;
    if token.trim().is_empty() {
        return None;
    }
    state
        .jwt
        .parse(&token)
        .ok()
        .filter(|c| c.is(TokenType::Access))
}

fn current_series(headers: &HeaderMap) -> Option<Uuid> {
    cookies::read(headers, cookies::PERSISTENT)
        .filter(|c| !c.trim().is_empty())
        .and_then(|c| persistent::series_from_cookie(&c))
}

/// SessionIssuer.currentSsoAt: the SSO sign-in time this browser's session carries, if any.
pub async fn current_sso_at(
    state: &AppState,
    headers: &HeaderMap,
) -> AppResult<Option<DateTime<Utc>>> {
    if let Some(refresh) = cookies::read(headers, cookies::REFRESH).filter(|r| !r.trim().is_empty())
        && let Ok(claims) = state.jwt.parse(&refresh)
        && claims.is(TokenType::Refresh)
        && let Some(at) = claims.sso_authenticated_at()
    {
        return Ok(Some(at));
    }
    if let Some(claims) =
        request_access_claims(state, headers).filter(|c| !c.is_authorization_server_token())
    {
        return Ok(claims.sso_authenticated_at());
    }
    match current_series(headers) {
        Some(series) => Ok(persistent::find_by_series(&state.db, series)
            .await?
            .and_then(|s| s.sso_authenticated_at)),
        None => Ok(None),
    }
}

async fn is_persistent_device(
    state: &AppState,
    headers: &HeaderMap,
    user: &AppUser,
) -> AppResult<bool> {
    match cookies::read(headers, cookies::PERSISTENT).filter(|c| !c.trim().is_empty()) {
        Some(cookie) => Ok(persistent::owner_user_id(state, &cookie).await? == Some(user.id)),
        None => Ok(false),
    }
}

fn rotated_refresh(
    state: &AppState,
    user: &AppUser,
    persistent: bool,
    series: Option<Uuid>,
    sso_at: Option<DateTime<Utc>>,
) -> String {
    state
        .jwt
        .refresh_token(user, if persistent { series } else { None }, sso_at)
}

fn requires_sso_revalidation(state: &AppState, sso_at: Option<DateTime<Utc>>) -> bool {
    let after =
        chrono::Duration::from_std(state.config.sso.session_revalidate_after).unwrap_or_default();
    sso_at.is_some_and(|at| !state.config.sso.enabled() || at + after < state.clock.now())
}

fn sso_revalidation_required(mut jar: Cookies) -> Response {
    jar.clear_all();
    (
        jar,
        Problem::with_detail(StatusCode::UNAUTHORIZED, "SSO re-validation required")
            .code("SSO_REVALIDATE"),
    )
        .into_response()
}

/// The client address and headers of the request a session is issued for.
#[derive(Clone, Copy)]
pub struct RequestInfo<'a> {
    pub ip: &'a ClientIp,
    pub headers: &'a HeaderMap,
}

/// SessionIssuer.issue: the cookies of a completed sign-in, with a remember-me series when asked.
pub async fn issue_session(
    state: &AppState,
    jar: &mut Cookies,
    user: &AppUser,
    issue_persistent: bool,
    trusted_for_2fa: bool,
    sso_at: Option<DateTime<Utc>>,
    request: RequestInfo<'_>,
) -> AppResult<()> {
    let RequestInfo { ip, headers } = request;
    if issue_persistent {
        let user_agent = headers
            .get(header::USER_AGENT)
            .and_then(|v| v.to_str().ok());
        let issued = persistent::issue(
            state,
            user,
            trusted_for_2fa,
            user_agent,
            &ip.as_java_string(),
            sso_at,
        )
        .await?;
        jar.access_and_refresh(
            &state.jwt,
            &state.jwt.access_token(user, sso_at),
            &state
                .jwt
                .refresh_token(user, Some(issued.session.series_id), sso_at),
            true,
        );
        let remaining = (issued.session.expires_at - state.clock.now())
            .num_seconds()
            .max(0);
        jar.persistent(&issued.cookie_value, u64::try_from(remaining).unwrap_or(0));
    } else {
        jar.access_and_refresh(
            &state.jwt,
            &state.jwt.access_token(user, sso_at),
            &state.jwt.refresh_token(user, None, sso_at),
            false,
        );
        // Keep this user's trusted device, drop another identity's cookie.
        if let Some(existing) =
            cookies::read(headers, cookies::PERSISTENT).filter(|c| !c.trim().is_empty())
            && persistent::owner_user_id(state, &existing).await? != Some(user.id)
        {
            jar.clear_persistent();
        }
    }
    Ok(())
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LoginRequest {
    username: Option<String>,
    password: Option<String>,
    #[serde(default, deserialize_with = "null_as_false")]
    remember_me: bool,
}

impl Validate for LoginRequest {
    fn validate(&self, e: &mut Errors) {
        let u = self.username.as_deref();
        e.not_blank("username", u).size("username", u, 0, 50);
        let p = self.password.as_deref();
        e.not_blank("password", p).size("password", p, 0, 128);
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MfaRequired {
    mfa_required: bool,
    username: String,
}

pub async fn login(
    State(state): State<AppState>,
    ip: ClientIp,
    headers: HeaderMap,
    Json(req): Json<LoginRequest>,
) -> AppResult<Response> {
    if state.config.login_rate_limit_enabled
        && !state
            .limits
            .try_consume(&policies::LOGIN, &ip.as_java_string())
    {
        return Ok(Problem::with_detail(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many login attempts. Try again in 15 minutes.",
        )
        .into_response());
    }
    let (username, password_plain) = (
        req.username.unwrap_or_default(),
        req.password.unwrap_or_default(),
    );
    let Some(user) = users::find_by_username(&state.db, &username).await? else {
        state.login_timing.burn(&password_plain).await;
        return Err(AppError::BadCredentials);
    };
    if state.config.admin_recovery_enabled && user.role == Role::Admin && !user.activated {
        return Ok(Problem::with_detail(
            StatusCode::FORBIDDEN,
            "Admin recovery mode is active: the password reset link was printed to the server console/logs — open it to set a new password. Remember to set ADMIN_RECOVERY_ENABLED=false before the next restart.",
        )
        .into_response());
    }
    if user.password_hash.trim().is_empty() {
        // matches() against an empty hash returns at once: equalize with the other failures.
        state.login_timing.burn(&password_plain).await;
        return Err(AppError::BadCredentials);
    }
    if !password::matches(&password_plain, &user.password_hash).await {
        return Err(AppError::BadCredentials);
    }
    if !user.activated {
        return Ok(Problem::with_detail(
            StatusCode::FORBIDDEN,
            "Account not activated. Use your activation link to set a password.",
        )
        .into_response());
    }
    let mut jar = cookie_jar(&state).await;
    if mfa::is_enabled(&state.db, user.id).await? {
        let trusted = match cookies::read(&headers, cookies::PERSISTENT) {
            Some(cookie) => persistent::is_trusted_device_for(&state, user.id, &cookie).await?,
            None => false,
        };
        if !trusted {
            // The second factor is outstanding: drop any other identity's session cookies.
            jar.clear_session();
            jar.mfa_challenge(
                &state.jwt,
                &state.jwt.mfa_challenge_token(&user, req.remember_me),
            );
            return Ok((
                jar,
                axum::Json(MfaRequired {
                    mfa_required: true,
                    username: user.username,
                }),
            )
                .into_response());
        }
    }
    issue_session(
        &state,
        &mut jar,
        &user,
        req.remember_me,
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

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MfaVerifyRequest {
    code: Option<String>,
    trust_device: Option<bool>,
    is_recovery_code: Option<bool>,
}

impl Validate for MfaVerifyRequest {
    fn validate(&self, e: &mut Errors) {
        let c = self.code.as_deref();
        e.not_blank("code", c).size("code", c, 0, 16);
    }
}

pub async fn mfa_verify(
    State(state): State<AppState>,
    ip: ClientIp,
    headers: HeaderMap,
    Json(req): Json<MfaVerifyRequest>,
) -> AppResult<Response> {
    let mut jar = cookie_jar(&state).await;
    if !state
        .limits
        .try_consume(&policies::MFA_VERIFY, &ip.as_java_string())
    {
        jar.clear_mfa_challenge();
        let problem = Problem::with_detail(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many verification attempts. Please log in again in 15 minutes.",
        );
        return Ok((jar, problem).into_response());
    }
    let Some(challenge) = cookies::read(&headers, cookies::MFA_CHALLENGE) else {
        return Ok(
            Problem::with_detail(StatusCode::UNAUTHORIZED, "No MFA challenge in progress")
                .into_response(),
        );
    };
    let Ok(claims) = state.jwt.parse(&challenge) else {
        jar.clear_mfa_challenge();
        return Ok((
            jar,
            Problem::with_detail(StatusCode::UNAUTHORIZED, "Invalid MFA challenge"),
        )
            .into_response());
    };
    if !claims.is(TokenType::MfaChallenge) {
        jar.clear_mfa_challenge();
        return Ok((jar, StatusCode::UNAUTHORIZED).into_response());
    }
    let user = match claims.uid {
        Some(uid) => users::find_by_id(&state.db, uid).await?,
        None => None,
    }
    .ok_or(AppError::BadCredentials)?;
    let recovery = req.is_recovery_code == Some(true);
    if !mfa::verify_totp_or_recovery(&state, &user, &req.code.unwrap_or_default(), recovery).await?
    {
        // 400, not 401: the challenge is still valid, only the code is wrong.
        return Ok(
            Problem::with_detail(StatusCode::BAD_REQUEST, "Invalid verification code")
                .into_response(),
        );
    }
    let remember_me = claims.remember_me == Some(true);
    let trust_device = req.trust_device == Some(true);
    issue_session(
        &state,
        &mut jar,
        &user,
        remember_me || trust_device,
        trust_device,
        None,
        RequestInfo {
            ip: &ip,
            headers: &headers,
        },
    )
    .await?;
    jar.clear_mfa_challenge();
    Ok((jar, user_payload(&user)).into_response())
}

fn session_revoked(mut jar: Cookies) -> Response {
    jar.clear_all();
    (
        jar,
        Problem::with_detail(StatusCode::UNAUTHORIZED, "Session revoked"),
    )
        .into_response()
}

pub async fn refresh(
    State(state): State<AppState>,
    revalidate: Option<axum::Extension<SsoRevalidate>>,
    principal: Option<Auth>,
    headers: HeaderMap,
) -> AppResult<Response> {
    let jar = cookie_jar(&state).await;
    if revalidate.is_some() {
        return Ok(sso_revalidation_required(jar));
    }
    let presented_series = current_series(&headers);
    if let Some(series) = presented_series
        && !persistent::is_series_active(&state, series).await?
    {
        return Ok(session_revoked(jar));
    }
    if let Some(refresh) = cookies::read(&headers, cookies::REFRESH)
        && let Ok(claims) = state.jwt.parse(&refresh)
        && claims.is(TokenType::Refresh)
    {
        // By the immutable uid, never by the username in sub, which can change or be reused.
        let user = match claims.uid {
            Some(uid) => users::find_by_id(&state.db, uid).await?,
            None => None,
        };
        if let Some(user) = user.filter(|u| claims.tv == Some(u.token_version) && u.activated) {
            let sso_at = claims.sso_authenticated_at();
            if requires_sso_revalidation(&state, sso_at) {
                return Ok(sso_revalidation_required(jar));
            }
            let bound = claims.series_id();
            if let Some(series) = bound
                && !persistent::is_series_active(&state, series).await?
            {
                return Ok(session_revoked(jar));
            }
            let persistent_device = is_persistent_device(&state, &headers, &user).await?;
            let series = bound.or(presented_series);
            let mut jar = jar;
            jar.access_and_refresh(
                &state.jwt,
                &state.jwt.access_token(&user, sso_at),
                &rotated_refresh(&state, &user, persistent_device, series, sso_at),
                persistent_device,
            );
            return Ok((jar, user_payload(&user)).into_response());
        }
    }
    let native_token = state.config.sso.enabled()
        && request_access_claims(&state, &headers)
            .is_some_and(|c| c.is_authorization_server_token());
    if let Some(auth) = principal.filter(|_| !native_token) {
        let user = auth.user;
        let sso_at = current_sso_at(&state, &headers).await?;
        if requires_sso_revalidation(&state, sso_at) {
            return Ok(sso_revalidation_required(jar));
        }
        let persistent_device = is_persistent_device(&state, &headers, &user).await?;
        let mut jar = jar;
        jar.access_and_refresh(
            &state.jwt,
            &state.jwt.access_token(&user, sso_at),
            &rotated_refresh(&state, &user, persistent_device, presented_series, sso_at),
            persistent_device,
        );
        return Ok((jar, user_payload(&user)).into_response());
    }
    let mut jar = jar;
    jar.clear_all();
    Ok((
        jar,
        Problem::with_detail(StatusCode::UNAUTHORIZED, "No refresh token"),
    )
        .into_response())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LogoutResponse {
    end_session_url: String,
}

pub async fn logout(
    State(state): State<AppState>,
    origin: RequestOrigin,
    headers: HeaderMap,
) -> AppResult<Response> {
    let sso_session = current_sso_at(&state, &headers).await?.is_some();
    if let Some(series) = cookies::read(&headers, cookies::PERSISTENT)
        .and_then(|c| persistent::series_from_cookie(&c))
    {
        persistent::revoke_by_series(&state, series).await?;
    }
    let mut jar = cookie_jar(&state).await;
    jar.clear_all();
    if state.config.sso.idp_logout
        && sso_session
        && let Some(endpoint) = crate::identity::sso_end_session_endpoint(&state).await
    {
        let redirect = format!("{}/login", origin.base_url());
        let mut url = url::Url::parse(&endpoint).map_err(AppError::internal)?;
        url.query_pairs_mut()
            .append_pair("client_id", &state.config.sso.client_id)
            .append_pair("post_logout_redirect_uri", &redirect);
        return Ok((
            jar,
            axum::Json(LogoutResponse {
                end_session_url: url.to_string(),
            }),
        )
            .into_response());
    }
    Ok((jar, StatusCode::NO_CONTENT).into_response())
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChangeUsernameRequest {
    new_username: Option<String>,
}

impl Validate for ChangeUsernameRequest {
    fn validate(&self, e: &mut Errors) {
        let u = self.new_username.as_deref();
        e.not_blank("newUsername", u)
            .size("newUsername", u, 3, 50)
            .pattern(
                "newUsername",
                u,
                &USERNAME,
                crate::setup_auth::setup::USERNAME_MESSAGE,
            );
    }
}

#[derive(Serialize)]
struct UsernameResponse {
    username: String,
}

pub async fn change_username(
    State(state): State<AppState>,
    auth: Auth,
    headers: HeaderMap,
    Json(req): Json<ChangeUsernameRequest>,
) -> AppResult<Response> {
    let mut user = auth.user;
    let new_username = req.new_username.unwrap_or_default().trim().to_owned();
    if new_username == user.username {
        return Ok(axum::Json(UsernameResponse {
            username: user.username,
        })
        .into_response());
    }
    if users::exists_by_username(&state.db, &new_username).await? {
        return Ok(
            Problem::with_detail(StatusCode::CONFLICT, "Username already taken").into_response(),
        );
    }
    let persistent_device = is_persistent_device(&state, &headers, &user).await?;
    sqlx::query("UPDATE app_user SET username = $1, updated_at = now() WHERE id = $2")
        .bind(&new_username)
        .bind(user.id)
        .execute(&state.db)
        .await?;
    user.username = new_username.clone();
    if state.config.sso.enabled()
        && request_access_claims(&state, &headers)
            .is_some_and(|c| c.is_authorization_server_token())
    {
        return Ok(axum::Json(UsernameResponse {
            username: new_username,
        })
        .into_response());
    }
    let sso_at = current_sso_at(&state, &headers).await?;
    let mut jar = cookie_jar(&state).await;
    jar.access_and_refresh(
        &state.jwt,
        &state.jwt.access_token(&user, sso_at),
        &rotated_refresh(
            &state,
            &user,
            persistent_device,
            current_series(&headers),
            sso_at,
        ),
        persistent_device,
    );
    Ok((
        jar,
        axum::Json(UsernameResponse {
            username: new_username,
        }),
    )
        .into_response())
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChangePasswordRequest {
    current_password: Option<String>,
    new_password: Option<String>,
}

impl Validate for ChangePasswordRequest {
    fn validate(&self, e: &mut Errors) {
        e.not_blank("currentPassword", self.current_password.as_deref());
        let n = self.new_password.as_deref();
        e.not_blank("newPassword", n).size("newPassword", n, 8, 128);
    }
}

#[derive(Serialize)]
struct MessageResponse {
    message: &'static str,
}

pub async fn change_password(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<ChangePasswordRequest>,
) -> AppResult<Response> {
    let mut user = auth.user;
    if !password::matches(
        &req.current_password.unwrap_or_default(),
        &user.password_hash,
    )
    .await
    {
        return Err(AppError::BadCredentials);
    }
    let hash = password::hash(&req.new_password.unwrap_or_default()).await?;
    user.token_version += 1;
    sqlx::query("UPDATE app_user SET password_hash = $1, token_version = $2, updated_at = now() WHERE id = $3")
        .bind(&hash)
        .bind(user.token_version)
        .bind(user.id)
        .execute(&state.db)
        .await?;
    persistent::revoke_all_for_user(&state, user.id).await?;
    let mut jar = cookie_jar(&state).await;
    jar.access_and_refresh(
        &state.jwt,
        &state.jwt.access_token(&user, None),
        &state.jwt.refresh_token(&user, None, None),
        false,
    );
    jar.clear_persistent();
    Ok((
        jar,
        axum::Json(MessageResponse {
            message: "Password updated successfully",
        }),
    )
        .into_response())
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivationRequest {
    password: Option<String>,
    #[serde(default, deserialize_with = "null_as_false")]
    acknowledged_warning: bool,
}

impl Validate for ActivationRequest {
    fn validate(&self, e: &mut Errors) {
        let p = self.password.as_deref();
        e.not_blank("password", p).size("password", p, 8, 128);
        e.assert_true("acknowledgedWarning", self.acknowledged_warning);
    }
}

#[derive(Serialize)]
struct ErrorBody {
    error: &'static str,
}

fn plain_error(message: &'static str) -> Response {
    (
        StatusCode::BAD_REQUEST,
        axum::Json(ErrorBody { error: message }),
    )
        .into_response()
}

pub async fn activate(
    State(state): State<AppState>,
    ip: ClientIp,
    headers: HeaderMap,
    Path(token): Path<String>,
    Json(req): Json<ActivationRequest>,
) -> AppResult<Response> {
    let user = users::find_by_activation_token(&state.db, &token, false)
        .await?
        .ok_or(AppError::BadCredentials)?;
    if user
        .activation_token_expires
        .is_some_and(|e| e < state.clock.now())
    {
        return Ok(plain_error("Activation token has expired"));
    }
    if !req.acknowledged_warning {
        return Ok(plain_error("You must acknowledge the data access warning"));
    }
    let hash = password::hash(&req.password.unwrap_or_default()).await?;
    let ctx = AuditContext {
        ip: &ip.as_java_string(),
        user_agent: headers
            .get(header::USER_AGENT)
            .and_then(|v| v.to_str().ok()),
    };
    match complete_invitation(&state, &token, &hash, &ctx).await? {
        Invitation::Activated => Ok(axum::Json(MessageResponse {
            message: "Account activated successfully",
        })
        .into_response()),
        Invitation::Expired => Ok(plain_error("Activation token has expired")),
        Invitation::Unknown => Err(AppError::BadCredentials),
    }
}

pub enum Invitation {
    Activated,
    Unknown,
    Expired,
}

/// ActivationService.completeInvitation: checks the token again on the locked row.
pub async fn complete_invitation(
    state: &AppState,
    token: &str,
    hash: &str,
    ctx: &AuditContext<'_>,
) -> AppResult<Invitation> {
    let mut conn = state.db.acquire().await?;
    let mut tx = conn.begin().await?;
    let Some(user) = users::find_by_activation_token(&mut *tx, token, true).await? else {
        return Ok(Invitation::Unknown);
    };
    if user
        .activation_token_expires
        .is_some_and(|e| e < state.clock.now())
    {
        return Ok(Invitation::Expired);
    }
    sqlx::query(
        "UPDATE app_user SET password_hash = $1, activation_token = NULL, activation_token_expires = NULL, \
         is_activated = true, acknowledged_warning = true, token_version = token_version + 1, updated_at = now() WHERE id = $2",
    )
    .bind(hash)
    .bind(user.id)
    .execute(&mut *tx)
    .await?;
    sqlx::query(
        "UPDATE persistent_session SET revoked_at = $1 WHERE user_id = $2 AND revoked_at IS NULL",
    )
    .bind(state.clock.now())
    .bind(user.id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    if user.role == Role::Admin {
        audit::record(
            state,
            "admin.recovery.completed",
            Some(&user.username),
            ctx,
            None,
        )
        .await;
    }
    Ok(Invitation::Activated)
}
