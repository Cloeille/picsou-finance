//! BudgetSettingsController and BudgetSettingsService: the member's payday cycle start day and
//! the logo / AI categorization switches, created with the defaults on first read.

use axum::extract::State;
use chrono::{DateTime, NaiveDate, Utc};
use serde::{Deserialize, Serialize};
use sqlx::PgExecutor;

use super::cycle;
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::json::{Errors, Json, Validate};
use crate::ledger::categorize::budget_settings_of_member;
use crate::ledger::model::{AiCategorizationMode, BudgetSettings};
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/budget/settings", get)
        .put("/api/budget/settings", update);
}

/// The member's cycle start day, 1 when they have no settings row yet.
pub async fn cycle_start_day<'e>(db: impl PgExecutor<'e>, member_id: i64) -> sqlx::Result<u32> {
    Ok(budget_settings_of_member(db, member_id)
        .await?
        .map_or(1, |s| u32::try_from(s.cycle_start_day).unwrap_or(1)))
}

/// Whether the member opted into online logo fetching; false without a settings row. A pure read.
pub async fn logo_fetch_enabled<'e>(db: impl PgExecutor<'e>, member_id: i64) -> sqlx::Result<bool> {
    Ok(budget_settings_of_member(db, member_id)
        .await?
        .is_some_and(|s| s.logo_fetch_enabled))
}

/// getOrCreate: the member's row, inserted with the entity defaults when missing.
pub async fn get_or_create(
    db: &sqlx::PgPool,
    member_id: i64,
    now: DateTime<Utc>,
) -> sqlx::Result<BudgetSettings> {
    if let Some(settings) = budget_settings_of_member(db, member_id).await? {
        return Ok(settings);
    }
    sqlx::query(
        "INSERT INTO budget_settings (member_id, cycle_start_day, logo_fetch_enabled, ai_categorization_enabled, \
         ai_mode, ai_confidence_threshold, updated_at) VALUES ($1, 1, false, false, 'AUTO_HIGH_CONFIDENCE', 75, $2) \
         ON CONFLICT (member_id) DO NOTHING",
    )
    .bind(member_id)
    .bind(now)
    .execute(db)
    .await?;
    Ok(budget_settings_of_member(db, member_id)
        .await?
        .expect("the settings row just inserted"))
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BudgetSettingsResponse {
    pub cycle_start_day: i32,
    pub logo_fetch_enabled: bool,
    pub ai_categorization_enabled: bool,
    pub ai_mode: AiCategorizationMode,
    pub ai_confidence_threshold: i32,
    pub current_cycle_start: NaiveDate,
    pub current_cycle_end: NaiveDate,
}

fn response(settings: &BudgetSettings, on: NaiveDate) -> BudgetSettingsResponse {
    let day = i32::from(settings.cycle_start_day);
    let cycle = cycle::cycle_for(on, u32::try_from(day).unwrap_or(1));
    BudgetSettingsResponse {
        cycle_start_day: day,
        logo_fetch_enabled: settings.logo_fetch_enabled,
        ai_categorization_enabled: settings.ai_categorization_enabled,
        ai_mode: settings.ai_mode,
        ai_confidence_threshold: settings.ai_confidence_threshold,
        current_cycle_start: cycle.start,
        current_cycle_end: cycle.end,
    }
}

async fn get(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<BudgetSettingsResponse>> {
    let settings = get_or_create(&state.db, auth.member_id, state.clock.now()).await?;
    Ok(axum::Json(response(&settings, state.clock.today())))
}

/// BudgetSettingsRequest. Primitive fields read a missing or null value as 0 / false.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BudgetSettingsRequest {
    #[serde(default)]
    cycle_start_day: Option<i32>,
    #[serde(default)]
    logo_fetch_enabled: Option<bool>,
    #[serde(default)]
    ai_categorization_enabled: Option<bool>,
    ai_mode: Option<AiCategorizationMode>,
    #[serde(default)]
    ai_confidence_threshold: Option<i32>,
}

impl Validate for BudgetSettingsRequest {
    fn validate(&self, errors: &mut Errors) {
        let day = i64::from(self.cycle_start_day.unwrap_or(0));
        let threshold = i64::from(self.ai_confidence_threshold.unwrap_or(0));
        errors
            .min("cycleStartDay", Some(day), 1)
            .max("cycleStartDay", Some(day), 28)
            .not_null("aiMode", self.ai_mode.as_ref())
            .min("aiConfidenceThreshold", Some(threshold), 0)
            .max("aiConfidenceThreshold", Some(threshold), 100);
    }
}

async fn update(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<BudgetSettingsRequest>,
) -> AppResult<axum::Json<BudgetSettingsResponse>> {
    let now = state.clock.now();
    let mut settings = get_or_create(&state.db, auth.member_id, now).await?;
    settings.cycle_start_day = i16::try_from(req.cycle_start_day.unwrap_or(0)).unwrap_or(1);
    settings.logo_fetch_enabled = req.logo_fetch_enabled.unwrap_or(false);
    settings.ai_categorization_enabled = req.ai_categorization_enabled.unwrap_or(false);
    settings.ai_mode = req
        .ai_mode
        .unwrap_or(AiCategorizationMode::AutoHighConfidence);
    settings.ai_confidence_threshold = req.ai_confidence_threshold.unwrap_or(0);
    settings.updated_at = now;
    sqlx::query(
        "UPDATE budget_settings SET cycle_start_day = $2, logo_fetch_enabled = $3, ai_categorization_enabled = $4, \
         ai_mode = $5, ai_confidence_threshold = $6, updated_at = $7 WHERE id = $1",
    )
    .bind(settings.id)
    .bind(settings.cycle_start_day)
    .bind(settings.logo_fetch_enabled)
    .bind(settings.ai_categorization_enabled)
    .bind(settings.ai_mode)
    .bind(settings.ai_confidence_threshold)
    .bind(now)
    .execute(&state.db)
    .await?;
    Ok(axum::Json(response(&settings, state.clock.today())))
}
