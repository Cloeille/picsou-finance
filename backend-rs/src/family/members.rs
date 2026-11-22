//! FamilyController's member management (admin only): list, create, rename, delete, and the
//! activation and password-reset links.

use axum::extract::State;
use axum::response::IntoResponse;
use chrono::Duration;
use http::StatusCode;
use rand::RngExt;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use super::username;
use crate::AppState;
use crate::auth::Auth;
use crate::auth::users::{self, Role};
use crate::db::AsDbHandle;
use crate::db::{self, DbExec, DbFromRow};
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Json, Path, Validate};
use crate::ledger::sync_values::java_is_blank;
use crate::routes::Routes;

const DEFAULT_AVATAR_COLOR: &str = "#6366f1";
const TOKEN_LIFETIME_DAYS: i64 = 7;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/family/members", list)
        .post("/api/family/members", create)
        .put("/api/family/members/{id}", update)
        .delete("/api/family/members/{id}", delete)
        .post("/api/family/members/{id}/activate", activate)
        .post("/api/family/members/{id}/reset-password", reset_password);
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FamilyMemberResponse {
    pub id: i64,
    pub display_name: String,
    pub avatar_color: String,
    pub managed: bool,
    pub has_login: bool,
    pub activated: bool,
    pub login_name: Option<String>,
    pub mfa_enabled: bool,
    pub has_password: bool,
    pub sso_linked: bool,
}

/// A member with its login, MFA state and whether an SSO identity is linked.
#[derive(DbFromRow)]
struct MemberRow {
    id: i64,
    display_name: String,
    avatar_color: String,
    is_managed: bool,
    user_id: Option<i64>,
    username: Option<String>,
    password_hash: Option<String>,
    is_activated: Option<bool>,
    mfa_enabled: bool,
    sso_linked: bool,
}

impl From<MemberRow> for FamilyMemberResponse {
    fn from(r: MemberRow) -> Self {
        let has_login = r.user_id.is_some();
        Self {
            id: r.id,
            display_name: r.display_name,
            avatar_color: r.avatar_color,
            managed: r.is_managed,
            has_login,
            activated: r.is_activated.unwrap_or(false),
            login_name: r.username,
            mfa_enabled: r.mfa_enabled,
            has_password: r.password_hash.is_some_and(|h| !java_is_blank(&h)),
            sso_linked: r.sso_linked,
        }
    }
}

const MEMBER_SELECT: &str = "SELECT m.id, m.display_name, m.avatar_color, m.is_managed, \
    u.id AS user_id, u.username, u.password_hash, u.is_activated, \
    COALESCE(mfa.enabled, false) AS mfa_enabled, \
    (u.id IS NOT NULL AND EXISTS (SELECT 1 FROM user_identity i WHERE i.user_id = u.id)) AS sso_linked \
    FROM family_member m \
    LEFT JOIN app_user u ON u.member_id = m.id \
    LEFT JOIN user_mfa mfa ON mfa.user_id = u.id";

fn require_admin(auth: &Auth) -> AppResult<()> {
    if auth.is_admin() {
        Ok(())
    } else {
        Err(AppError::status(
            StatusCode::FORBIDDEN,
            "Admin access required",
        ))
    }
}

fn member_not_found() -> AppError {
    AppError::status(StatusCode::NOT_FOUND, "Member not found")
}

async fn list(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Vec<FamilyMemberResponse>>> {
    require_admin(&auth)?;
    let rows: Vec<MemberRow> =
        db::query_as(&format!("{MEMBER_SELECT} ORDER BY m.created_at, m.id"))
            .fetch_all(&state.db)
            .await?;
    Ok(axum::Json(rows.into_iter().map(Into::into).collect()))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FamilyMemberRequest {
    display_name: Option<String>,
    avatar_color: Option<String>,
}

impl Validate for FamilyMemberRequest {
    fn validate(&self, errors: &mut Errors) {
        errors
            .not_blank("displayName", self.display_name.as_deref())
            .size("displayName", self.display_name.as_deref(), 0, 100);
    }
}

async fn create(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<FamilyMemberRequest>,
) -> AppResult<impl IntoResponse> {
    require_admin(&auth)?;
    let avatar = req
        .avatar_color
        .unwrap_or_else(|| DEFAULT_AVATAR_COLOR.to_owned());
    let row: (i64, String, String, bool) = db::query_as(
        "INSERT INTO family_member (display_name, avatar_color, is_managed) VALUES ($1, $2, true) \
         RETURNING id, display_name, avatar_color, is_managed",
    )
    .bind(req.display_name.unwrap_or_default())
    .bind(avatar)
    .fetch_one(&state.db)
    .await?;
    let response = FamilyMemberResponse {
        id: row.0,
        display_name: row.1,
        avatar_color: row.2,
        managed: row.3,
        has_login: false,
        activated: false,
        login_name: None,
        mfa_enabled: false,
        has_password: false,
        sso_linked: false,
    };
    Ok((StatusCode::CREATED, axum::Json(response)))
}

/// The body of PUT /members/{id}, a Map<String, String>: Jackson turns a scalar into its text
/// and refuses an object or an array.
#[derive(Debug, Deserialize)]
pub struct DisplayNameBody(serde_json::Map<String, Value>);

impl Validate for DisplayNameBody {
    fn validate(&self, _: &mut Errors) {}
}

impl DisplayNameBody {
    fn display_name(&self) -> AppResult<Option<String>> {
        match self.0.get("displayName") {
            None | Some(Value::Null) => Ok(None),
            Some(Value::String(s)) => Ok(Some(s.clone())),
            Some(Value::Number(n)) => Ok(Some(n.to_string())),
            Some(Value::Bool(b)) => Ok(Some(b.to_string())),
            Some(_) => Err(AppError::BadRequest("Failed to read request".into())),
        }
    }
}

async fn update(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    Json(body): Json<DisplayNameBody>,
) -> AppResult<axum::Json<FamilyMemberResponse>> {
    require_admin(&auth)?;
    let display_name = body
        .display_name()?
        .filter(|n| !java_is_blank(n))
        .ok_or_else(|| AppError::status(StatusCode::BAD_REQUEST, "displayName is required"))?;
    let updated =
        db::query("UPDATE family_member SET display_name = $2, updated_at = $3 WHERE id = $1")
            .bind(id)
            .bind(&display_name)
            .bind(state.clock.now())
            .execute(&state.db)
            .await?;
    if updated.rows_affected() == 0 {
        return Err(member_not_found());
    }
    let row: MemberRow = db::query_as(&format!("{MEMBER_SELECT} WHERE m.id = $1"))
        .bind(id)
        .fetch_one(&state.db)
        .await?;
    Ok(axum::Json(row.into()))
}

async fn member_exists(db: impl DbExec<'_>, id: i64) -> sqlx::Result<bool> {
    db::query_scalar("SELECT EXISTS (SELECT 1 FROM family_member WHERE id = $1)")
        .bind(id)
        .fetch_one(db)
        .await
}

/// Deletes the member, their login and, by the ON DELETE CASCADE of every owner table, all
/// their data. An admin cannot delete themselves, and the last administrator stays.
async fn delete(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<StatusCode> {
    require_admin(&auth)?;
    let mut tx = state.db.begin().await?;
    if !member_exists(&mut tx, id).await? {
        return Err(member_not_found());
    }
    if id == auth.member_id {
        return Err(AppError::status(
            StatusCode::FORBIDDEN,
            "Cannot delete your own account",
        ));
    }
    if let Some(user) = users::find_by_member_id(&mut tx, id).await? {
        if user.role == Role::Admin {
            let admins: Vec<i64> =
                db::query_scalar("SELECT id FROM app_user WHERE role = 'ADMIN' FOR UPDATE")
                    .fetch_all(&mut tx)
                    .await?;
            if admins.len() <= 1 {
                return Err(AppError::status(
                    StatusCode::FORBIDDEN,
                    "Cannot delete the last administrator",
                ));
            }
        }
        db::query("DELETE FROM app_user WHERE id = $1")
            .bind(user.id)
            .execute(&mut tx)
            .await?;
    }
    db::query("DELETE FROM family_member WHERE id = $1")
        .bind(id)
        .execute(&mut tx)
        .await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}

fn new_token() -> String {
    let bytes: [u8; 32] = rand::rng().random();
    hex::encode(bytes)
}

/// The login of a managed profile: a new MEMBER user with a derived username, or a fresh token
/// on the existing one while it is not activated.
async fn activate(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<Value>> {
    require_admin(&auth)?;
    let mut tx = state.db.begin().await?;
    let display_name: String =
        db::query_scalar("SELECT display_name FROM family_member WHERE id = $1")
            .bind(id)
            .fetch_optional(&mut tx)
            .await?
            .ok_or_else(member_not_found)?;
    let existing = users::find_by_member_id(&mut tx, id).await?;
    if existing.as_ref().is_some_and(|u| u.activated) {
        return Err(AppError::status(
            StatusCode::BAD_REQUEST,
            "This account already has an active login.",
        ));
    }
    let token = new_token();
    let expires = state.clock.now() + Duration::days(TOKEN_LIFETIME_DAYS);
    match existing {
        Some(user) => {
            db::query("UPDATE app_user SET activation_token = $2, activation_token_expires = $3 WHERE id = $1")
                .bind(user.id)
                .bind(&token)
                .bind(expires)
                .execute(&mut tx)
                .await?;
        }
        None => {
            let username = username::derive(&mut tx.as_handle(), &display_name).await?;
            db::query(
                "INSERT INTO app_user (username, password_hash, member_id, role, is_activated, \
                 activation_token, activation_token_expires, acknowledged_warning) \
                 VALUES ($1, '', $2, 'MEMBER', false, $3, $4, false)",
            )
            .bind(username)
            .bind(id)
            .bind(&token)
            .bind(expires)
            .execute(&mut tx)
            .await?;
        }
    }
    tx.commit().await?;
    Ok(axum::Json(
        json!({ "activationLink": format!("/activate/{token}") }),
    ))
}

/// A fresh activation token on an existing login; the current password keeps working until
/// the link is used.
async fn reset_password(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<Value>> {
    require_admin(&auth)?;
    let mut conn = state.db.acquire().await?;
    if !member_exists(&mut conn, id).await? {
        return Err(member_not_found());
    }
    let user = users::find_by_member_id(&mut conn, id)
        .await?
        .ok_or_else(|| AppError::status(StatusCode::BAD_REQUEST, "Member has no login to reset"))?;
    let token = new_token();
    db::query(
        "UPDATE app_user SET activation_token = $2, activation_token_expires = $3 WHERE id = $1",
    )
    .bind(user.id)
    .bind(&token)
    .bind(state.clock.now() + Duration::days(TOKEN_LIFETIME_DAYS))
    .execute(&mut conn)
    .await?;
    Ok(axum::Json(
        json!({ "resetLink": format!("/activate/{token}") }),
    ))
}
