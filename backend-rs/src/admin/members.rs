//! AdminMfaController and AdminIdentityController: an admin resets another member's second
//! factor or removes their SSO identities; never their own.

use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};

use crate::AppState;
use crate::auth::{Auth, users};
use crate::db::AsDbHandle;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::Path;
use crate::identity::common::audit;
use crate::identity::sso::account::delete_mfa_on;

pub async fn force_disable_mfa(
    State(state): State<AppState>,
    auth: Auth,
    Path(member_id): Path<i64>,
) -> AppResult<Response> {
    let target = users::find_by_member_id(&state.db, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Member not found".into()))?;
    if target.id == auth.user.id {
        return Ok(StatusCode::FORBIDDEN.into_response());
    }
    let mut tx = state.db.begin().await?;
    delete_mfa_on(&mut tx.as_handle(), target.id).await?;
    // Trusted devices were issued under the revoked MFA policy, so they go too.
    crate::db::query(
        "UPDATE persistent_session SET revoked_at = $1 WHERE user_id = $2 AND revoked_at IS NULL",
    )
    .bind(state.clock.now())
    .bind(target.id)
    .execute(&mut tx)
    .await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT.into_response())
}

pub async fn unlink_identities(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    headers: HeaderMap,
    Path(member_id): Path<i64>,
) -> AppResult<StatusCode> {
    let target = users::find_by_member_id(&state.db, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Member not found".into()))?;
    if target.id == auth.user.id {
        return Err(AppError::coded(
            StatusCode::FORBIDDEN,
            "SELF_UNLINK_FORBIDDEN",
            "Use your sign-in settings to remove your own single sign-on identities",
        ));
    }
    let mut tx = state.db.begin().await?;
    crate::db::query("DELETE FROM user_identity WHERE user_id = $1")
        .bind(target.id)
        .execute(&mut tx)
        .await?;
    crate::db::query(
        "UPDATE app_user SET token_version = token_version + 1, updated_at = now() WHERE id = $1",
    )
    .bind(target.id)
    .execute(&mut tx)
    .await?;
    crate::db::query(
        "UPDATE persistent_session SET revoked_at = $2 WHERE user_id = $1 AND revoked_at IS NULL",
    )
    .bind(target.id)
    .bind(state.clock.now())
    .execute(&mut tx)
    .await?;
    tx.commit().await?;
    let details = format!("targetUserId={}", target.id);
    audit(
        &state,
        "admin.sso.identities-unlinked",
        Some(&auth.user.username),
        &ip,
        &headers,
        Some(&details),
    )
    .await;
    Ok(StatusCode::NO_CONTENT)
}
