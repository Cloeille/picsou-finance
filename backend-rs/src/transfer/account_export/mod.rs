//! Per-account XLSX export (AccountExportController): one sheet per chosen account, a summary
//! and a debt sheet, throttled per user.

mod cell;
pub mod labels;
mod sheet;
mod workbook;

use axum::extract::State;
use axum::response::{IntoResponse, Response};
use http::{HeaderValue, StatusCode, header};
use serde::Deserialize;

use self::labels::SheetLabels;
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;
use crate::routes::Routes;

pub const XLSX_MIME: &str = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

pub fn register(routes: &mut Routes) {
    routes.post("/api/accounts/export", export);
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportRequest {
    account_ids: Option<Vec<i64>>,
    labels: Option<serde_json::Map<String, serde_json::Value>>,
}

impl Validate for ExportRequest {
    fn validate(&self, errors: &mut Errors) {
        errors.not_empty("accountIds", self.account_ids.as_deref());
        if self.account_ids.as_ref().is_some_and(|ids| ids.len() > 200) {
            errors.add("accountIds", "cannot export more than 200 accounts at once");
        }
    }
}

async fn export(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<ExportRequest>,
) -> AppResult<Response> {
    let user_id = auth.user.id;
    if !state
        .limits
        .try_consume(&policies::ACCOUNT_EXPORT, &user_id.to_string())
    {
        tracing::warn!(
            "accounts_export.rate_limited userId={user_id} ip={}",
            ip.as_java_string()
        );
        return Ok(StatusCode::TOO_MANY_REQUESTS.into_response());
    }
    let ids = req.account_ids.unwrap_or_default();
    tracing::warn!(
        "accounts_export.requested userId={user_id} memberId={} accountIds={ids:?} ip={}",
        auth.member_id,
        ip.as_java_string()
    );
    let labels = SheetLabels::of(req.labels.as_ref());
    let filename = format!(
        "picsou-comptes-{}.xlsx",
        state.clock.now().format("%Y%m%d-%H%M%S")
    );
    // Java streams the workbook after the headers are written, so a failure while gathering
    // (an account outside the perimeter included) reaches the client as an empty 500 that
    // still carries the spreadsheet headers.
    let mut response = match workbook::build(&state, &ids, auth.member_id, &labels).await {
        Ok(bytes) => bytes.into_response(),
        Err(e) => {
            tracing::error!("accounts_export.stream_failed userId={user_id} msg={e}");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    };
    let headers = response.headers_mut();
    headers.insert(header::CONTENT_TYPE, HeaderValue::from_static(XLSX_MIME));
    if let Ok(value) = HeaderValue::from_str(&format!("attachment; filename=\"{filename}\"")) {
        headers.insert(header::CONTENT_DISPOSITION, value);
    }
    Ok(response)
}
