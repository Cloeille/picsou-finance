//! The SCPI fund catalog a provider sync reported, and the member's tombstones on it
//! (ScpiFundController, ScpiFundService), and the reconciliation the provider syncs run.

pub mod reconciler;

use axum::extract::State;
use axum::http::StatusCode;
use chrono::{DateTime, Utc};
use serde::Serialize;

use crate::AppState;
use crate::auth::Auth;
use crate::db::{DbExec, DbFromRow};
use crate::error::{AppError, AppResult};
use crate::http::json::Path;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/scpi/funds", list)
        .post("/api/scpi/funds/{provider}/{fundCode}/ignore", ignore)
        .post("/api/scpi/funds/{provider}/{fundCode}/restore", restore);
}

/// ScpiProvider, stored and serialized by name.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Provider {
    Corum,
    Sofidy,
    Louve,
}

impl Provider {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Corum => "CORUM",
            Self::Sofidy => "SOFIDY",
            Self::Louve => "LOUVE",
        }
    }

    fn parse(value: &str) -> AppResult<Self> {
        match value {
            "CORUM" => Ok(Self::Corum),
            "SOFIDY" => Ok(Self::Sofidy),
            "LOUVE" => Ok(Self::Louve),
            _ => Err(AppError::BadRequest(format!(
                "Failed to convert 'provider' with value: '{value}'"
            ))),
        }
    }

    /// The scpi_position column linking an account to this provider's fund.
    fn fund_code_column(self) -> &'static str {
        match self {
            Self::Corum => "corum_fund_code",
            Self::Sofidy => "sofidy_fund_code",
            Self::Louve => "louve_fund_code",
        }
    }
}

/// ScpiFundResponse.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScpiFundResponse {
    pub provider: String,
    pub fund_code: String,
    pub label: Option<String>,
    pub ignored: bool,
    pub last_outcome: Option<String>,
    pub last_seen_at: Option<DateTime<Utc>>,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub account_id: Option<i64>,
    pub account_name: Option<String>,
}

#[derive(DbFromRow)]
struct FundRow {
    provider: String,
    fund_code: String,
    label: Option<String>,
    ignored: bool,
    last_outcome: Option<String>,
    last_seen_at: Option<DateTime<Utc>>,
    last_synced_at: Option<DateTime<Utc>>,
}

/// The position (id, account id, account name) linked to the provider's fund, if any.
async fn linked(
    db: impl DbExec<'_>,
    member_id: i64,
    provider: Provider,
    fund_code: &str,
) -> sqlx::Result<Option<(i64, Option<i64>, Option<String>)>> {
    crate::db::query_as::<(i64, Option<i64>, Option<String>)>(&format!(
        "SELECT p.id, a.id, a.name FROM scpi_position p LEFT JOIN account a ON a.id = p.account_id \
         AND a.deleted_at IS NULL WHERE p.member_id = $1 AND p.{} = $2 ORDER BY p.id LIMIT 1",
        provider.fund_code_column()
    ))
    .bind(member_id)
    .bind(fund_code)
    .fetch_optional(db)
    .await
}

fn require_code(fund_code: &str) -> AppResult<String> {
    if fund_code.trim().is_empty() || fund_code.encode_utf16().count() > 40 {
        return Err(AppError::BadRequest("Invalid fund code".to_owned()));
    }
    Ok(fund_code.trim().to_owned())
}

async fn list(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Vec<ScpiFundResponse>>> {
    let mut conn = state.db.pool_handle();
    let rows: Vec<FundRow> = crate::db::query_as::<FundRow>(
        "SELECT provider, fund_code, label, ignored, last_outcome, last_seen_at, last_synced_at \
         FROM scpi_provider_fund WHERE member_id = $1 ORDER BY provider, label",
    )
    .bind(auth.member_id)
    .fetch_all(&mut conn)
    .await?;
    let mut out = Vec::with_capacity(rows.len());
    for row in rows {
        let link = match Provider::parse(&row.provider) {
            Ok(provider) => linked(&mut conn, auth.member_id, provider, &row.fund_code).await?,
            Err(_) => None,
        };
        let (account_id, account_name) = link.map(|(_, id, name)| (id, name)).unwrap_or_default();
        out.push(ScpiFundResponse {
            provider: row.provider,
            fund_code: row.fund_code,
            label: row.label,
            ignored: row.ignored,
            last_outcome: row.last_outcome,
            last_seen_at: row.last_seen_at,
            last_synced_at: row.last_synced_at,
            account_id,
            account_name,
        });
    }
    Ok(axum::Json(out))
}

/// Unlinks the fund's account (its data stays, it simply stops syncing) and tombstones the fund
/// so the next sync neither recreates nor relinks it.
async fn ignore(
    State(state): State<AppState>,
    auth: Auth,
    Path((provider, fund_code)): Path<(String, String)>,
) -> AppResult<StatusCode> {
    let provider = Provider::parse(&provider)?;
    let code = require_code(&fund_code)?;
    let mut tx = state.db.begin().await?;
    if let Some((position_id, _, _)) = linked(&mut tx, auth.member_id, provider, &code).await? {
        crate::db::query(&format!(
            "UPDATE scpi_position SET {} = NULL, updated_at = $2 WHERE id = $1",
            provider.fund_code_column()
        ))
        .bind(position_id)
        .bind(state.clock.now())
        .execute(&mut tx)
        .await?;
    }
    tombstone(&mut tx, auth.member_id, provider, &code, state.clock.now()).await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn restore(
    State(state): State<AppState>,
    auth: Auth,
    Path((provider, fund_code)): Path<(String, String)>,
) -> AppResult<StatusCode> {
    let provider = Provider::parse(&provider)?;
    let code = require_code(&fund_code)?;
    let updated = crate::db::query(
        "UPDATE scpi_provider_fund SET ignored = false, updated_at = $4 \
         WHERE member_id = $1 AND provider = $2 AND fund_code = $3",
    )
    .bind(auth.member_id)
    .bind(provider.as_str())
    .bind(&code)
    .bind(state.clock.now())
    .execute(&state.db)
    .await?
    .rows_affected();
    if updated == 0 {
        return Err(AppError::NotFound("SCPI fund not found".to_owned()));
    }
    Ok(StatusCode::NO_CONTENT)
}

/// ScpiFundService.tombstone: the member's "never sync this fund again"; the syncs of tracks
/// T5/T6 call it too.
pub async fn tombstone(
    db: impl DbExec<'_>,
    member_id: i64,
    provider: Provider,
    fund_code: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    if fund_code.trim().is_empty() {
        return Ok(());
    }
    crate::db::query(
        "INSERT INTO scpi_provider_fund (member_id, provider, fund_code, ignored, created_at, updated_at) \
         VALUES ($1, $2, $3, true, $4, $4) ON CONFLICT (member_id, provider, fund_code) \
         DO UPDATE SET ignored = true, updated_at = $4",
    )
    .bind(member_id)
    .bind(provider.as_str())
    .bind(fund_code)
    .bind(now)
    .execute(db)
    .await
    .map(|_| ())
}

/// ScpiFundService.clearTombstone: a sync that sees the member relink a fund lifts the tombstone.
pub async fn clear_tombstone(
    db: impl DbExec<'_>,
    member_id: i64,
    provider: Provider,
    fund_code: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    if fund_code.trim().is_empty() {
        return Ok(());
    }
    crate::db::query(
        "UPDATE scpi_provider_fund SET ignored = false, updated_at = $4 \
         WHERE member_id = $1 AND provider = $2 AND fund_code = $3 AND ignored",
    )
    .bind(member_id)
    .bind(provider.as_str())
    .bind(fund_code)
    .bind(now)
    .execute(db)
    .await
    .map(|_| ())
}
