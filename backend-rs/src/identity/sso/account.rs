//! SsoAuthenticationSuccessHandler, SsoAccountService, SsoRoleSyncService, UsernameDeriver and
//! SetupService.seedSsoAdmin: what a verified identity does for each intent.

use axum::http::HeaderMap;
use chrono::{DateTime, Utc};
use sqlx::Connection;
use unicode_normalization::UnicodeNormalization;

use super::{Intent, Kind, Refused, Settings, SsoIdentity, with_query_parameter};
use crate::AppState;
use crate::auth::cookies::{self, Cookies};
use crate::auth::{AppUser, Role, users};
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::identity::common::{audit, has_valid_proof, java_url_encode, log_safe, user_agent};
use crate::setup_auth::audit::AuditContext;
use crate::setup_auth::auth::{Invitation, RequestInfo, complete_invitation, issue_session};
use crate::setup_auth::settings;

const COMPLETE_TARGET: &str = "/login/sso-complete?redirect=";
const DEFAULT_DISPLAY_NAME: &str = "SSO user";
const MAX_MEMBER_NAME: usize = 100;
const MAX_IDENTITY_NAME: usize = 255;
const MAX_EMAIL: usize = 320;
const USERNAME_COLUMN_LENGTH: usize = 50;

#[derive(Clone, Copy)]
pub struct Request<'a> {
    pub ip: &'a ClientIp,
    pub headers: &'a HeaderMap,
}

/// String.substring(0, max) on UTF-16 units.
fn truncate(value: Option<&str>, max: usize) -> Option<String> {
    value.map(|v| {
        let units: Vec<u16> = v.encode_utf16().collect();
        if units.len() <= max {
            v.to_owned()
        } else {
            String::from_utf16_lossy(&units[..max])
        }
    })
}

fn internal(e: impl std::fmt::Display) -> Refused {
    tracing::error!("SSO account resolution failed: {}", log_safe(&e.to_string()));
    Refused("IDP_ERROR")
}

/// Dispatches on the intent once the IdP vouched for the identity.
pub async fn complete(
    state: &AppState,
    intent: &Intent,
    identity: &SsoIdentity,
    request: Request<'_>,
) -> Result<(String, Cookies), Refused> {
    let mut jar = Cookies::new(settings::secure_cookies(state).await);
    let target = match intent.kind {
        Kind::Login => login(state, intent, identity, request, &mut jar).await?,
        Kind::Reauth | Kind::Link => signed_in(state, intent, identity, request, &mut jar).await?,
        Kind::Activate => activate(state, intent, identity, request, &mut jar).await?,
        Kind::Setup => setup(state, intent, identity, request, &mut jar).await?,
    };
    Ok((target, jar))
}

async fn issue(
    state: &AppState,
    jar: &mut Cookies,
    user: &AppUser,
    remember: bool,
    request: Request<'_>,
) -> Result<(), Refused> {
    issue_session(
        state,
        jar,
        user,
        remember,
        false,
        Some(state.clock.now()),
        RequestInfo {
            ip: request.ip,
            headers: request.headers,
        },
    )
    .await
    .map_err(internal)
}

fn complete_target(redirect: &str) -> String {
    format!("{COMPLETE_TARGET}{}", java_url_encode(redirect))
}

async fn login(
    state: &AppState,
    intent: &Intent,
    identity: &SsoIdentity,
    request: Request<'_>,
    jar: &mut Cookies,
) -> Result<String, Refused> {
    let resolved = match resolve_login(state, identity, request).await {
        Ok(r) => r,
        Err(LoginError::Refused(code)) => return Err(Refused(code)),
        // A concurrent first sign-in won a unique key: a second attempt sees the winner.
        Err(LoginError::Conflict) => match resolve_login(state, identity, request).await {
            Ok(r) => r,
            Err(LoginError::Refused(code)) => return Err(Refused(code)),
            Err(LoginError::Conflict) => return Err(Refused("IDP_ERROR")),
            Err(LoginError::Internal(e)) => return Err(internal(e)),
        },
        Err(LoginError::Internal(e)) => return Err(internal(e)),
    };
    let (mut user, provisioned) = resolved;
    if !user.activated {
        let code = if state.config.admin_recovery_enabled && user.role == Role::Admin {
            "ADMIN_RECOVERY_ACTIVE"
        } else {
            "ACCOUNT_INACTIVE"
        };
        return Err(Refused(code));
    }
    sync_role(state, &mut user, identity.groups.as_deref(), request)
        .await
        .map_err(internal)?;
    issue(state, jar, &user, intent.remember_me, request).await?;
    tracing::info!(
        "SSO login user={} provisioned={provisioned}",
        log_safe(&user.username)
    );
    Ok(if intent.redirect.starts_with("/oauth2/") {
        intent.redirect.clone()
    } else {
        complete_target(&intent.redirect)
    })
}

enum LoginError {
    Refused(&'static str),
    Conflict,
    Internal(String),
}

impl From<sqlx::Error> for LoginError {
    fn from(e: sqlx::Error) -> Self {
        let unique = e
            .as_database_error()
            .and_then(|d| d.code())
            .is_some_and(|c| c == "23505");
        if unique {
            Self::Conflict
        } else {
            Self::Internal(e.to_string())
        }
    }
}

async fn linked_user_id(state: &AppState, identity: &SsoIdentity) -> Result<Option<i64>, sqlx::Error> {
    sqlx::query_scalar("SELECT user_id FROM user_identity WHERE issuer = $1 AND subject = $2")
        .bind(&identity.issuer)
        .bind(&identity.subject)
        .fetch_optional(&state.db)
        .await
}

fn in_admin_group(state: &AppState, identity: &SsoIdentity) -> bool {
    let group = Settings::new(state).admin_group();
    identity
        .groups
        .as_ref()
        .is_some_and(|g| g.iter().any(|x| *x == group))
}

/// SsoAccountService.resolveLogin: bound by (issuer, subject) only, never by email or username.
async fn resolve_login(
    state: &AppState,
    identity: &SsoIdentity,
    request: Request<'_>,
) -> Result<(AppUser, bool), LoginError> {
    if let Some(user_id) = linked_user_id(state, identity).await? {
        let user = users::find_by_id(&state.db, user_id)
            .await?
            .ok_or_else(|| LoginError::Internal("linked user vanished".into()))?;
        touch_login(state, identity).await?;
        return Ok((user, false));
    }
    let complete = crate::setup_auth::setup::is_complete(state)
        .await
        .map_err(|e| LoginError::Internal(e.to_string()))?;
    if Settings::new(state).admin_group_managed() && in_admin_group(state, identity) && complete {
        return Ok((provision(state, identity, request).await?, true));
    }
    Err(LoginError::Refused("NOT_LINKED"))
}

pub fn display_name_for(identity: &SsoIdentity) -> String {
    let name = [identity.display_name.as_deref(), identity.preferred_username.as_deref()]
        .into_iter()
        .flatten()
        .find(|v| !v.trim().is_empty())
        .map(str::trim)
        .unwrap_or(DEFAULT_DISPLAY_NAME);
    truncate(Some(name), MAX_MEMBER_NAME).unwrap_or_default()
}

/// UsernameDeriver.derive: accents stripped, separator runs to a dot, a numeric suffix on collision.
pub async fn derive_username<'e>(
    db: impl sqlx::PgExecutor<'e> + Copy,
    display_name: &str,
) -> Result<String, sqlx::Error> {
    let stripped: String = display_name
        .nfd()
        .filter(|c| !unicode_normalization::char::is_combining_mark(*c))
        .collect::<String>()
        .to_lowercase();
    let mut base = String::new();
    let mut pending_dot = false;
    for c in stripped.chars() {
        if c.is_ascii_lowercase() || c.is_ascii_digit() {
            if pending_dot && !base.is_empty() {
                base.push('.');
            }
            pending_dot = false;
            base.push(c);
        } else {
            pending_dot = true;
        }
    }
    if base.is_empty() {
        base = "user".to_owned();
    }
    base.truncate(45);
    let mut candidate = base.clone();
    let mut n = 2;
    while users::exists_by_username(db, &candidate).await? {
        let suffix = format!(".{n}");
        n += 1;
        let keep = base.len().min(USERNAME_COLUMN_LENGTH - suffix.len());
        candidate = format!("{}{suffix}", &base[..keep]);
    }
    Ok(candidate)
}

pub async fn username_for(state: &AppState, identity: &SsoIdentity) -> Result<String, sqlx::Error> {
    match identity.preferred_username.as_deref().filter(|p| !p.trim().is_empty()) {
        Some(preferred) => {
            let local = preferred.split_once('@').map_or(preferred, |(l, _)| l);
            derive_username(&state.db, local).await
        }
        None => derive_username(&state.db, &display_name_for(identity)).await,
    }
}

async fn insert_account(
    tx: &mut sqlx::PgConnection,
    username: &str,
    display_name: &str,
) -> Result<i64, sqlx::Error> {
    let member_id: i64 = sqlx::query_scalar(
        "INSERT INTO family_member (display_name, is_managed) VALUES ($1, false) RETURNING id",
    )
    .bind(display_name)
    .fetch_one(&mut *tx)
    .await?;
    sqlx::query_scalar(
        "INSERT INTO app_user (username, password_hash, member_id, role, is_activated, acknowledged_warning) \
         VALUES ($1, '', $2, 'ADMIN', true, true) RETURNING id",
    )
    .bind(username)
    .bind(member_id)
    .fetch_one(&mut *tx)
    .await
}

async fn insert_identity(
    tx: &mut sqlx::PgConnection,
    user_id: i64,
    identity: &SsoIdentity,
    last_login: Option<DateTime<Utc>>,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO user_identity (user_id, issuer, subject, email, display_name, last_login_at) \
         VALUES ($1, $2, $3, $4, $5, $6)",
    )
    .bind(user_id)
    .bind(&identity.issuer)
    .bind(&identity.subject)
    .bind(truncate(identity.email.as_deref(), MAX_EMAIL))
    .bind(truncate(identity.display_name.as_deref(), MAX_IDENTITY_NAME))
    .bind(last_login)
    .execute(&mut *tx)
    .await?;
    Ok(())
}

async fn provision(state: &AppState, identity: &SsoIdentity, request: Request<'_>) -> Result<AppUser, LoginError> {
    let username = username_for(state, identity).await?;
    let mut conn = state.db.acquire().await?;
    let mut tx = conn.begin().await?;
    let user_id = insert_account(&mut tx, &username, &display_name_for(identity)).await?;
    insert_identity(&mut tx, user_id, identity, Some(state.clock.now())).await?;
    tx.commit().await?;
    audit(
        state,
        "sso.user.provisioned",
        Some(&username),
        request.ip,
        request.headers,
        Some("role=ADMIN"),
    )
    .await;
    tracing::info!("sso.user.provisioned username={}", log_safe(&username));
    Ok(users::find_by_id(&state.db, user_id)
        .await?
        .ok_or_else(|| LoginError::Internal("provisioned user vanished".into()))?)
}

async fn touch_login(state: &AppState, identity: &SsoIdentity) -> Result<(), sqlx::Error> {
    sqlx::query(
        "UPDATE user_identity SET last_login_at = $3, email = $4, display_name = $5, updated_at = $3 \
         WHERE issuer = $1 AND subject = $2",
    )
    .bind(&identity.issuer)
    .bind(&identity.subject)
    .bind(state.clock.now())
    .bind(truncate(identity.email.as_deref(), MAX_EMAIL))
    .bind(truncate(identity.display_name.as_deref(), MAX_IDENTITY_NAME))
    .execute(&state.db)
    .await?;
    Ok(())
}

/// SsoRoleSyncService.sync: the admin group decides the role; the last admin is never demoted.
async fn sync_role(
    state: &AppState,
    user: &mut AppUser,
    groups: Option<&[String]>,
    request: Request<'_>,
) -> AppResult<()> {
    let sso = Settings::new(state);
    if !sso.admin_group_managed() {
        return Ok(());
    }
    if groups.is_none() {
        tracing::warn!("sso.groups-claim-missing user={}", log_safe(&user.username));
    }
    let group = sso.admin_group();
    let in_group = groups.is_some_and(|g| g.iter().any(|x| *x == group));
    if in_group && user.role == Role::Member {
        sqlx::query("UPDATE app_user SET role = 'ADMIN', updated_at = now() WHERE id = $1")
            .bind(user.id)
            .execute(&state.db)
            .await?;
        user.role = Role::Admin;
        audit(state, "sso.role.promoted", Some(&user.username), request.ip, request.headers, None).await;
    } else if !in_group && user.role == Role::Admin {
        let mut conn = state.db.acquire().await?;
        let mut tx = conn.begin().await?;
        let admins: Vec<i64> =
            sqlx::query_scalar("SELECT id FROM app_user WHERE role = 'ADMIN' ORDER BY id FOR UPDATE")
                .fetch_all(&mut *tx)
                .await?;
        if !admins.contains(&user.id) {
            return Ok(());
        }
        if admins.len() == 1 {
            tx.commit().await?;
            tracing::warn!(
                "sso.role.demotion-skipped-last-admin user={}",
                log_safe(&user.username)
            );
            audit(
                state,
                "sso.role.demotion-skipped-last-admin",
                Some(&user.username),
                request.ip,
                request.headers,
                None,
            )
            .await;
            return Ok(());
        }
        sqlx::query("UPDATE app_user SET role = 'MEMBER', updated_at = now() WHERE id = $1")
            .bind(user.id)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        user.role = Role::Member;
        audit(state, "sso.role.demoted", Some(&user.username), request.ip, request.headers, None).await;
    }
    Ok(())
}

async fn signed_in(
    state: &AppState,
    intent: &Intent,
    identity: &SsoIdentity,
    request: Request<'_>,
    jar: &mut Cookies,
) -> Result<String, Refused> {
    let user = match cookies::read(request.headers, cookies::ACCESS) {
        Some(token) => crate::auth::layer::authenticate_access_token(state, &token)
            .await
            .map(|p| p.user),
        None => None,
    };
    let Some(mut user) = user else {
        return Err(Refused("SESSION_EXPIRED"));
    };
    if Some(user.id) != intent.user_id {
        return Err(Refused("REAUTH_WRONG_ACCOUNT"));
    }
    let linked = linked_user_id(state, identity).await.map_err(internal)?;
    match intent.kind {
        Kind::Reauth => {
            if linked != Some(user.id) {
                return Err(Refused("REAUTH_WRONG_ACCOUNT"));
            }
            let fresh_enough = identity
                .auth_time
                .is_some_and(|t| t >= intent.started_at - chrono::Duration::seconds(60));
            if !fresh_enough {
                return Err(Refused("REAUTH_TOO_OLD"));
            }
            touch_login(state, identity).await.map_err(internal)?;
            sync_role(state, &mut user, identity.groups.as_deref(), request)
                .await
                .map_err(internal)?;
            jar.reauth_proof(&state.jwt, &state.jwt.reauth_proof(&user));
            Ok(intent.redirect.clone())
        }
        _ => {
            if !has_valid_proof(state, &user, request.headers) {
                return Err(Refused("REAUTH_REQUIRED"));
            }
            link(state, user.id, identity, linked).await?;
            sync_role(state, &mut user, identity.groups.as_deref(), request)
                .await
                .map_err(internal)?;
            audit(state, "sso.identity.linked", Some(&user.username), request.ip, request.headers, Some("")).await;
            Ok(with_query_parameter(&intent.redirect, "sso=linked"))
        }
    }
}

/// SsoAccountService.link: a new identity row, or a refreshed one already bound to this user.
async fn link(
    state: &AppState,
    user_id: i64,
    identity: &SsoIdentity,
    linked: Option<i64>,
) -> Result<(), Refused> {
    match linked {
        Some(owner) if owner != user_id => Err(Refused("IDENTITY_IN_USE")),
        Some(_) => {
            sqlx::query(
                "UPDATE user_identity SET email = $3, display_name = $4, updated_at = now() WHERE issuer = $1 AND subject = $2",
            )
            .bind(&identity.issuer)
            .bind(&identity.subject)
            .bind(truncate(identity.email.as_deref(), MAX_EMAIL))
            .bind(truncate(identity.display_name.as_deref(), MAX_IDENTITY_NAME))
            .execute(&state.db)
            .await
            .map_err(internal)?;
            Ok(())
        }
        None => {
            let mut conn = state.db.acquire().await.map_err(internal)?;
            insert_identity(&mut conn, user_id, identity, None)
                .await
                .map_err(internal)
        }
    }
}

/// SsoAccountService.activateWithIdentity: only a never-activated invitation qualifies.
async fn activate(
    state: &AppState,
    intent: &Intent,
    identity: &SsoIdentity,
    request: Request<'_>,
    jar: &mut Cookies,
) -> Result<String, Refused> {
    let token = intent.activation_token.clone().unwrap_or_default();
    let invited = users::find_by_activation_token(&state.db, &token, false)
        .await
        .map_err(internal)?
        .filter(|u| !u.activated)
        .filter(|u| u.activation_token_expires.is_none_or(|e| e >= state.clock.now()))
        .ok_or(Refused("ACTIVATION_INVALID"))?;
    let linked = linked_user_id(state, identity).await.map_err(internal)?;
    if linked.is_some_and(|owner| owner != invited.id) {
        return Err(Refused("IDENTITY_IN_USE"));
    }
    let ip = request.ip.as_java_string();
    let ctx = AuditContext {
        ip: &ip,
        user_agent: user_agent(request.headers),
    };
    match complete_invitation(state, &token, "", &ctx).await.map_err(internal)? {
        Invitation::Activated => {}
        Invitation::Unknown | Invitation::Expired => return Err(Refused("ACTIVATION_INVALID")),
    }
    // Picsou's TOTP only protects a password, and an SSO-activated account has none.
    disable_mfa(state, invited.id).await.map_err(internal)?;
    link(state, invited.id, identity, linked).await?;
    touch_login(state, identity).await.map_err(internal)?;
    let mut user = users::find_by_id(&state.db, invited.id)
        .await
        .map_err(internal)?
        .ok_or(Refused("ACTIVATION_INVALID"))?;
    sync_role(state, &mut user, identity.groups.as_deref(), request)
        .await
        .map_err(internal)?;
    issue(state, jar, &user, intent.remember_me, request).await?;
    if !intent.remember_me {
        // Activation revoked every series of the account: drop this browser's stale cookie.
        jar.clear_persistent();
    }
    tracing::info!("SSO activation user={}", log_safe(&user.username));
    Ok(complete_target(&intent.redirect))
}

pub async fn disable_mfa(state: &AppState, user_id: i64) -> Result<(), sqlx::Error> {
    let mut conn = state.db.acquire().await?;
    let mut tx = conn.begin().await?;
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
    tx.commit().await
}

/// SetupService.seedSsoAdmin: the same claim on PENDING_ADMIN as the password seed.
async fn seed_sso_admin(state: &AppState, identity: &SsoIdentity) -> AppResult<AppUser> {
    let username = username_for(state, identity).await?;
    let display_name = display_name_for(identity);
    let mut conn = state.db.acquire().await?;
    let mut tx = conn.begin().await?;
    sqlx::query("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE")
        .execute(&mut *tx)
        .await?;
    let current: Option<String> = settings::read_with(&mut *tx, settings::SETUP_STATE).await?;
    if current.as_deref() == Some("COMPLETE") {
        return Err(AppError::Internal("Setup is already complete".into()));
    }
    if users::count(&mut *tx).await? > 0 {
        return Err(AppError::Internal("An account already exists".into()));
    }
    let claimed = sqlx::query(
        "UPDATE app_setting SET value = 'IN_PROGRESS' WHERE setting_key = $1 AND value = 'PENDING_ADMIN'",
    )
    .bind(settings::SETUP_STATE)
    .execute(&mut *tx)
    .await?
    .rows_affected();
    if claimed == 0 && current.as_deref().is_none_or(|s| s == "PENDING_ADMIN") {
        tracing::warn!("Setup: concurrent admin-seeding attempt lost the setup-state CAS");
        return Err(AppError::Internal("Another setup session is already in progress.".into()));
    }
    let user_id = insert_account(&mut tx, &username, &display_name).await?;
    insert_identity(&mut tx, user_id, identity, Some(state.clock.now())).await?;
    tx.commit().await?;
    tracing::info!("setup.admin.created username={} role=ADMIN method=sso", log_safe(&username));
    users::find_by_id(&state.db, user_id)
        .await?
        .ok_or_else(|| AppError::Internal("admin vanished".into()))
}

async fn setup(
    state: &AppState,
    _intent: &Intent,
    identity: &SsoIdentity,
    request: Request<'_>,
    jar: &mut Cookies,
) -> Result<String, Refused> {
    if !in_admin_group(state, identity) {
        return Err(Refused("NOT_IN_ADMIN_GROUP"));
    }
    let user = seed_sso_admin(state, identity)
        .await
        .map_err(|_| Refused("SETUP_ALREADY_CLAIMED"))?;
    audit(state, "setup.admin.created", Some(&user.username), request.ip, request.headers, Some("method=sso")).await;
    issue(state, jar, &user, false, request).await?;
    Ok("/setup/security".to_owned())
}
