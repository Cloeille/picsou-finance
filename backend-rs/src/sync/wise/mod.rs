//! Wise jars (WiseController, WiseSyncService, WiseClient): a member's personal API token reads
//! the SAVINGS balances of every Wise profile, imported as savings accounts. Wise current
//! balances keep coming from Enable Banking.

mod accounts;
mod client;
mod currency;
mod error;
mod json;
mod service;
mod store;

use std::sync::Arc;

use axum::extract::State;
use axum::http::StatusCode;
use rust_decimal::Decimal;
use serde::{Deserialize, Deserializer};
use serde_json::Value;

pub use accounts::{AccountView, BaseAccountView};
pub use error::SyncFailure;
pub use store::WiseConnection;

use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult, Problem};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;
use crate::ledger::sidecar::SourceSyncResult;
use crate::routes::Routes;

/// Stamped on every imported jar so Sync All and account deletion can find the connection.
pub const PROVIDER: &str = "Wise Jars";
/// Source name in MemberSyncService and the MCP sync tools.
pub const SOURCE: &str = "wise";
/// Prefix of every jar's external id; account deletion recognises the connection by it.
pub const EXTERNAL_ID_PREFIX: &str = "wise_jar_";
pub const STATUS_CONNECTED: &str = "CONNECTED";
pub const STATUS_ERROR: &str = "ERROR";
/// Wise refused the stored token; the member must paste a new one.
pub const STATUS_REAUTH_REQUIRED: &str = "REAUTH_REQUIRED";
/// WisePort.MAX_TOKEN_CHARS: checked by the request and again by the client.
pub const MAX_TOKEN_CHARS: usize = 512;

const MAX_NAME_LEN: usize = 100;
const COLOR: &str = "#9fe870";

/// One jar: a Wise balance of type SAVINGS. The balance id is unique across profiles; the total
/// worth is cash plus any invested part, in the jar's currency.
#[derive(Debug, Clone, PartialEq)]
pub struct WiseJar {
    pub balance_id: i64,
    pub business: bool,
    pub name: Option<String>,
    pub currency: String,
    pub total_worth: Decimal,
}

/// The Wise connector's plug-in state, held as state.sync.wise.
pub struct Hooks {
    /// AccountService.toResponse; track T2 replaces it with the account read model.
    pub accounts: Arc<dyn AccountView>,
    http: reqwest::Client,
}

impl Hooks {
    pub fn new() -> Self {
        Self {
            accounts: Arc::new(BaseAccountView),
            http: client::http_client(),
        }
    }
}

impl Default for Hooks {
    fn default() -> Self {
        Self::new()
    }
}

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/wise/connect", connect)
        .get("/api/wise/status", status)
        .post("/api/wise/sync", sync)
        .delete("/api/wise/connection", clear_connection);
}

/// WiseSyncService.resyncReporting, for the scheduler and Sync All.
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    service::resync_reporting(state, member_id).await
}

/// WiseSyncService.deleteConnection, for account deletion: whether a connection was removed.
pub async fn delete_connection<'e>(
    db: impl crate::db::DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<bool> {
    service::delete_connection(db, member_id).await
}

/// WiseConnectionRepository.findByMemberId, for the sync status lines.
pub async fn find_connection<'e>(
    db: impl crate::db::DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Option<WiseConnection>> {
    store::find_by_member(db, member_id).await
}

#[derive(Debug, Deserialize)]
struct ConnectRequest {
    #[serde(default, deserialize_with = "jackson_string")]
    token: Option<String>,
}

impl Validate for ConnectRequest {
    fn validate(&self, errors: &mut Errors) {
        errors.not_blank("token", self.token.as_deref()).size(
            "token",
            self.token.as_deref(),
            0,
            MAX_TOKEN_CHARS,
        );
    }
}

/// Jackson coerces a JSON number or boolean to a String field; an array or object is unreadable.
fn jackson_string<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<String>, D::Error> {
    match Value::deserialize(deserializer)? {
        Value::Null => Ok(None),
        Value::String(s) => Ok(Some(s)),
        Value::Number(n) => Ok(Some(n.to_string())),
        Value::Bool(b) => Ok(Some(b.to_string())),
        _ => Err(serde::de::Error::custom("not a string")),
    }
}

/// Checks the token against Wise, stores it and imports the jars.
async fn connect(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(request): Json<ConnectRequest>,
) -> AppResult<axum::Json<Vec<Value>>> {
    check_rate_limit(&state, &ip)?;
    let token = request.token.unwrap_or_default();
    Ok(axum::Json(
        service::connect(&state, &token, auth.member_id).await?,
    ))
}

async fn status(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<impl serde::Serialize>> {
    Ok(axum::Json(
        service::connection_status(&state, auth.member_id).await?,
    ))
}

/// Manual sync with the stored token; shares the per-IP limit with connect.
async fn sync(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
) -> AppResult<axum::Json<Vec<Value>>> {
    check_rate_limit(&state, &ip)?;
    Ok(axum::Json(service::sync(&state, auth.member_id).await?))
}

/// Forgets the stored token; imported jars stay.
async fn clear_connection(State(state): State<AppState>, auth: Auth) -> AppResult<StatusCode> {
    service::delete_connection(&state.db, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

fn check_rate_limit(state: &AppState, ip: &ClientIp) -> AppResult<()> {
    if state
        .limits
        .try_consume(&policies::WISE_REQUEST, &ip.as_java_string())
    {
        return Ok(());
    }
    Err(AppError::Problem(Problem::with_detail(
        StatusCode::TOO_MANY_REQUESTS,
        "Too many Wise requests. Please wait a moment before trying again.",
    )))
}
