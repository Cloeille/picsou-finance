//! Full member export as a ZIP of data.json, one CSV per entity and a README
//! (MeExportController, DataExportService and its EntityExporters).

mod archive;
mod exporters;
mod json;
pub(crate) mod reauth;

use axum::body::Body;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, Utc};
use http::{StatusCode, header};
use serde::Deserialize;

use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;
use crate::routes::Routes;
use crate::setup_auth::desktop::DesktopSessionGate;

use self::reauth::ReAuth;

pub fn register(routes: &mut Routes) {
    routes.post("/api/me/export", export);
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ExportRequest {
    re_auth: Option<ReAuth>,
    include_balance_snapshots: Option<bool>,
}

impl Validate for ExportRequest {
    fn validate(&self, _: &mut Errors) {}
}

/// The account whose data is exported: always the signed-in user's own, never the managed
/// profile an admin selected with ?memberId=.
#[derive(Debug, Clone)]
pub(crate) struct Exporting {
    pub user_id: i64,
    pub username: String,
    pub member_id: i64,
}

async fn export(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    headers: HeaderMap,
    own_window: Result<DesktopSessionGate, AppError>,
    Json(req): Json<ExportRequest>,
) -> AppResult<Response> {
    let user = &auth.user;
    let include_balance_snapshots = req.include_balance_snapshots.unwrap_or(false);
    if !state
        .limits
        .try_consume(&policies::EXPORT, &user.id.to_string())
    {
        tracing::warn!(user_id = user.id, ip = %ip.as_java_string(), "export.rate_limited");
        return Ok(StatusCode::TOO_MANY_REQUESTS.into_response());
    }

    reauth::verify(
        &state,
        user,
        req.re_auth.as_ref(),
        &headers,
        own_window.is_ok(),
    )
    .await?;

    tracing::warn!(
        user_id = user.id,
        username = ?user.username,
        ip = %ip.as_java_string(),
        include_balance_snapshots,
        "export.requested"
    );

    let now = state.clock.now();
    let filename = format!(
        "picsou-export-{}-{}.zip",
        filename_safe(&user.username),
        filename_timestamp(now)
    );
    let exporting = Exporting {
        user_id: user.id,
        username: user.username.clone(),
        member_id: user.member_id,
    };
    let chunks = archive::spawn(state.db.clone(), exporting, include_balance_snapshots, now);
    Ok((
        [
            (header::CONTENT_TYPE, "application/zip".to_owned()),
            (
                header::CONTENT_DISPOSITION,
                format!("attachment; filename=\"{filename}\""),
            ),
        ],
        Body::from_stream(chunks),
    )
        .into_response())
}

/// Usernames are user-chosen and renamable, so anything outside [A-Za-z0-9._-] is replaced
/// before it reaches the quoted Content-Disposition filename.
fn filename_safe(username: &str) -> String {
    username
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') {
                c
            } else {
                '_'
            }
        })
        .collect()
}

fn filename_timestamp(now: DateTime<Utc>) -> String {
    now.format("%Y%m%d-%H%M%S").to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn filename_safe_replaces_everything_outside_the_portable_set() {
        assert_eq!(
            filename_safe("alice\r\nexport.requested userId=1"),
            "alice__export.requested_userId_1"
        );
        assert_eq!(filename_safe("chlo\u{e9}\"x"), "chlo__x");
        assert_eq!(filename_safe("a.b_c-D9"), "a.b_c-D9");
    }

    #[test]
    fn filename_timestamp_is_utc_compact() {
        let at = DateTime::parse_from_rfc3339("2026-03-15T23:30:05+02:00")
            .unwrap()
            .with_timezone(&Utc);
        assert_eq!(filename_timestamp(at), "20260315-213005");
    }
}
