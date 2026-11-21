//! HoldingClassificationService: the member's own verdict on what a holding is (tier, sector,
//! country), keyed on (member, ticker) so it outlives resyncs and covers every account.

use std::collections::HashMap;
use std::sync::LazyLock;

use axum::extract::State;
use regex::Regex;
use serde::{Deserialize, Serialize};
use sqlx::PgExecutor;

use crate::AppState;
use crate::accounts::analysis::profiles;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::json::{Errors, Json, Path, Validate};
use crate::ledger::access;
use crate::ledger::model::WealthTier;
use crate::ledger::sync_values::java_trim;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/accounts/{id}/holdings/{ticker}/classification", view)
        .put(
            "/api/accounts/{id}/holdings/{ticker}/classification",
            classify,
        );
}

/// A holding_classification row.
#[derive(Debug, Clone, PartialEq, sqlx::FromRow)]
pub struct HoldingClassification {
    pub id: i64,
    pub ticker: String,
    pub wealth_tier: Option<WealthTier>,
    pub sector_key: Option<String>,
    pub country_key: Option<String>,
}

const COLUMNS: &str = "id, ticker, wealth_tier, sector_key, country_key";

/// findByMemberId keyed by upper-cased ticker, as every reader looks it up.
pub async fn overrides_of<'e>(
    db: impl PgExecutor<'e>,
    member_id: i64,
) -> sqlx::Result<HashMap<String, HoldingClassification>> {
    let rows: Vec<HoldingClassification> = sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM holding_classification WHERE member_id = $1 ORDER BY id"
    ))
    .bind(member_id)
    .fetch_all(db)
    .await?;
    Ok(rows
        .into_iter()
        .map(|c| (c.ticker.to_uppercase(), c))
        .collect())
}

async fn find<'e>(
    db: impl PgExecutor<'e>,
    member_id: i64,
    ticker: &str,
) -> sqlx::Result<Option<HoldingClassification>> {
    sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM holding_classification WHERE member_id = $1 AND ticker = $2"
    ))
    .bind(member_id)
    .bind(ticker)
    .fetch_optional(db)
    .await
}

/// HoldingClassificationView: the override in force and, apart, what the providers inferred.
#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HoldingClassificationView {
    ticker: String,
    wealth_tier: Option<WealthTier>,
    sector_key: Option<String>,
    country_key: Option<String>,
    inferred_sector_key: Option<String>,
    inferred_country_key: Option<String>,
    profile_looked: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HoldingClassificationRequest {
    wealth_tier: Option<WealthTier>,
    sector_key: Option<String>,
    country_key: Option<String>,
}

static COUNTRY: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[A-Z]{2}$").expect("regex"));

impl Validate for HoldingClassificationRequest {
    fn validate(&self, errors: &mut Errors) {
        errors
            .size("sectorKey", self.sector_key.as_deref(), 0, 40)
            .pattern(
                "countryKey",
                self.country_key.as_deref(),
                &COUNTRY,
                "must be an ISO 3166-1 alpha-2 country code",
            );
    }
}

/// HoldingClassificationResponse: all three null means no override is in force.
#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HoldingClassificationResponse {
    ticker: String,
    wealth_tier: Option<WealthTier>,
    sector_key: Option<String>,
    country_key: Option<String>,
}

/// Readable, not owner-gated: looking at how a shared holding is classified is not a write.
async fn view(
    State(state): State<AppState>,
    auth: Auth,
    Path((id, ticker)): Path<(i64, String)>,
) -> AppResult<axum::Json<HoldingClassificationView>> {
    let mut conn = state.db.acquire().await?;
    access::require_readable(&mut conn, id, auth.member_id).await?;
    let upper = ticker.to_uppercase();
    let found = find(&mut *conn, auth.member_id, &upper).await?;
    let profile = profiles::load(&mut conn, [upper.as_str()])
        .await?
        .remove(&upper);
    Ok(axum::Json(HoldingClassificationView {
        wealth_tier: found.as_ref().and_then(|c| c.wealth_tier),
        sector_key: found.as_ref().and_then(|c| c.sector_key.clone()),
        country_key: found.as_ref().and_then(|c| c.country_key.clone()),
        inferred_sector_key: profile.as_ref().and_then(|p| p.sector_key.clone()),
        inferred_country_key: profile.as_ref().and_then(|p| p.country_key.clone()),
        profile_looked: profile
            .as_ref()
            .is_some_and(profiles::SecurityProfile::was_looked_up),
        ticker: upper,
    }))
}

/// Owner-gated: a co-owner must not rewrite how someone else's holdings are counted.
async fn classify(
    State(state): State<AppState>,
    auth: Auth,
    Path((id, ticker)): Path<(i64, String)>,
    Json(request): Json<HoldingClassificationRequest>,
) -> AppResult<axum::Json<HoldingClassificationResponse>> {
    let mut tx = state.db.begin().await?;
    access::require_owner(&mut tx, id, auth.member_id).await?;
    let upper = ticker.to_uppercase();
    let existing = find(&mut *tx, auth.member_id, &upper).await?;
    if request.wealth_tier.is_none()
        && request.sector_key.is_none()
        && request.country_key.is_none()
    {
        // Every field cleared means "stop overriding": drop the row rather than keep a blank.
        if let Some(row) = existing {
            sqlx::query("DELETE FROM holding_classification WHERE id = $1")
                .bind(row.id)
                .execute(&mut *tx)
                .await?;
        }
        tx.commit().await?;
        return Ok(axum::Json(HoldingClassificationResponse {
            ticker: upper,
            wealth_tier: None,
            sector_key: None,
            country_key: None,
        }));
    }
    let now = state.clock.now();
    // The entity trims and upper-cases on persist: the database constraint is case-sensitive.
    let stored_ticker = java_trim(&upper).to_uppercase();
    let saved: HoldingClassification = match existing {
        Some(row) => sqlx::query_as(&format!(
            "UPDATE holding_classification SET ticker = $2, wealth_tier = $3, sector_key = $4, \
             country_key = $5, updated_at = $6 WHERE id = $1 RETURNING {COLUMNS}"
        ))
        .bind(row.id)
        .bind(&stored_ticker)
        .bind(request.wealth_tier)
        .bind(&request.sector_key)
        .bind(&request.country_key)
        .bind(now)
        .fetch_one(&mut *tx)
        .await?,
        None => sqlx::query_as(&format!(
            "INSERT INTO holding_classification (member_id, ticker, wealth_tier, sector_key, country_key, \
             created_at, updated_at) VALUES ($1, $2, $3, $4, $5, $6, $6) RETURNING {COLUMNS}"
        ))
        .bind(auth.member_id)
        .bind(&stored_ticker)
        .bind(request.wealth_tier)
        .bind(&request.sector_key)
        .bind(&request.country_key)
        .bind(now)
        .fetch_one(&mut *tx)
        .await?,
    };
    tx.commit().await?;
    Ok(axum::Json(HoldingClassificationResponse {
        ticker: saved.ticker,
        wealth_tier: saved.wealth_tier,
        sector_key: saved.sector_key,
        country_key: saved.country_key,
    }))
}
