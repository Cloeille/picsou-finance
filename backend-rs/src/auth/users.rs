//! The app_user row joined with its family_member, as AppUserRepository.findByIdWithMember loads it.

use chrono::{DateTime, Utc};
use serde::Serialize;

use crate::db::{DbExec, DbFromRow};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum Role {
    Admin,
    Member,
}

impl Role {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Admin => "ADMIN",
            Self::Member => "MEMBER",
        }
    }

    fn parse(raw: &str) -> Self {
        if raw == "ADMIN" {
            Self::Admin
        } else {
            Self::Member
        }
    }
}

#[derive(Debug, Clone)]
pub struct AppUser {
    pub id: i64,
    pub username: String,
    pub password_hash: String,
    pub role: Role,
    pub activated: bool,
    pub acknowledged_warning: bool,
    pub token_version: i64,
    pub activation_token: Option<String>,
    pub activation_token_expires: Option<DateTime<Utc>>,
    pub member_id: i64,
    pub display_name: String,
    pub avatar_color: String,
    pub managed: bool,
}

#[derive(DbFromRow)]
struct Row {
    id: i64,
    username: String,
    password_hash: String,
    role: String,
    is_activated: bool,
    acknowledged_warning: bool,
    token_version: i64,
    activation_token: Option<String>,
    activation_token_expires: Option<DateTime<Utc>>,
    member_id: i64,
    display_name: String,
    avatar_color: String,
    is_managed: bool,
}

impl From<Row> for AppUser {
    fn from(r: Row) -> Self {
        Self {
            id: r.id,
            username: r.username,
            password_hash: r.password_hash,
            role: Role::parse(&r.role),
            activated: r.is_activated,
            acknowledged_warning: r.acknowledged_warning,
            token_version: r.token_version,
            activation_token: r.activation_token,
            activation_token_expires: r.activation_token_expires,
            member_id: r.member_id,
            display_name: r.display_name,
            avatar_color: r.avatar_color,
            managed: r.is_managed,
        }
    }
}

const SELECT: &str = "SELECT u.id, u.username, u.password_hash, u.role, u.is_activated, u.acknowledged_warning, \
    u.token_version, u.activation_token, u.activation_token_expires, u.member_id, m.display_name, m.avatar_color, \
    m.is_managed FROM app_user u JOIN family_member m ON m.id = u.member_id";

pub async fn find_by_id<'e>(db: impl DbExec<'e>, id: i64) -> Result<Option<AppUser>, sqlx::Error> {
    let sql = format!("{SELECT} WHERE u.id = $1");
    Ok(crate::db::query_as::<Row>(&sql)
        .bind(id)
        .fetch_optional(db)
        .await?
        .map(Into::into))
}

pub async fn find_by_username<'e>(
    db: impl DbExec<'e>,
    username: &str,
) -> Result<Option<AppUser>, sqlx::Error> {
    let sql = format!("{SELECT} WHERE u.username = $1");
    Ok(crate::db::query_as::<Row>(&sql)
        .bind(username)
        .fetch_optional(db)
        .await?
        .map(Into::into))
}

pub async fn find_by_member_id<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> Result<Option<AppUser>, sqlx::Error> {
    let sql = format!("{SELECT} WHERE u.member_id = $1");
    Ok(crate::db::query_as::<Row>(&sql)
        .bind(member_id)
        .fetch_optional(db)
        .await?
        .map(Into::into))
}

pub async fn find_by_activation_token<'e>(
    db: impl DbExec<'e>,
    token: &str,
    for_update: bool,
) -> Result<Option<AppUser>, sqlx::Error> {
    let lock = if for_update { " FOR UPDATE OF u" } else { "" };
    let sql = format!("{SELECT} WHERE u.activation_token = $1{lock}");
    Ok(crate::db::query_as::<Row>(&sql)
        .bind(token)
        .fetch_optional(db)
        .await?
        .map(Into::into))
}

// Wave 3: switch to impl DbExec (called by src/identity/sso/account.rs with opaque impl PgExecutor)
pub async fn exists_by_username<'e>(
    db: impl sqlx::PgExecutor<'e>,
    username: &str,
) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar("SELECT EXISTS (SELECT 1 FROM app_user WHERE username = $1)")
        .bind(username)
        .fetch_one(db)
        .await
}

pub async fn count<'e>(db: impl DbExec<'e>) -> Result<i64, sqlx::Error> {
    crate::db::query_scalar("SELECT count(*) FROM app_user")
        .fetch_one(db)
        .await
}

/// Earliest created active user — used by local Desktop Mode auto-login.
pub async fn find_primary_active<'e>(db: impl DbExec<'e>) -> Result<Option<AppUser>, sqlx::Error> {
    let sql = format!("{SELECT} WHERE u.is_activated = true ORDER BY u.id ASC LIMIT 1");
    Ok(crate::db::query_as::<Row>(&sql)
        .fetch_optional(db)
        .await?
        .map(Into::into))
}
