//! RecurringController and RecurringSeriesService: the member's recurring series (list, triage,
//! manual add and edit, delete), the "what changed" activity feed with its undo, and the
//! upcoming-payments calendar. Detection is in [super::detection].

use axum::extract::State;
use axum::http::StatusCode;
use chrono::{DateTime, Days, Months, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use super::categories::require;
use super::params::Params;
use super::repo::column_scale;
use super::{detection, jdec};
use crate::AppState;
use crate::auth::Auth;
use crate::db::{DbExec, DbFromRow};
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Json, Path, Validate};
use crate::ledger::accounts;
use crate::ledger::model::{AccountType, Category, RecurringCadence};
use crate::ledger::sync_values::java_trim;
use crate::routes::Routes;

/// How far back the activity feed looks for auto-confirms and price steps.
const ACTIVITY_LOOKBACK_DAYS: u64 = 60;
/// A due date within this many days is "due soon".
const DUE_SOON_DAYS: u64 = 7;
/// Missed periods that make a series stale.
const STALE_MISSED_PERIODS: u32 = 2;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/recurring", find_all)
        .post("/api/recurring", create)
        .get("/api/recurring/activity", activity)
        .get("/api/recurring/calendar", calendar)
        .post("/api/recurring/detect", detect)
        .put("/api/recurring/{id}", update)
        .delete("/api/recurring/{id}", delete)
        .post("/api/recurring/{id}/confirm", confirm)
        .post("/api/recurring/{id}/ignore", ignore)
        .post("/api/recurring/{id}/undo", undo);
}

#[derive(
    Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, sqlx::Type, crate::db::DbEnum,
)]
#[sqlx(type_name = "recurring_status", rename_all = "SCREAMING_SNAKE_CASE")]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RecurringStatus {
    Suggested,
    Confirmed,
    Ignored,
}

impl std::str::FromStr for RecurringStatus {
    type Err = ();

    fn from_str(value: &str) -> Result<Self, ()> {
        match value {
            "SUGGESTED" => Ok(Self::Suggested),
            "CONFIRMED" => Ok(Self::Confirmed),
            "IGNORED" => Ok(Self::Ignored),
            _ => Err(()),
        }
    }
}

/// RecurringCadence.next: one period later, calendar-aware for months and years.
pub fn next(cadence: RecurringCadence, from: NaiveDate) -> NaiveDate {
    let moved = match cadence {
        RecurringCadence::Weekly => from.checked_add_days(Days::new(7)),
        RecurringCadence::Biweekly => from.checked_add_days(Days::new(14)),
        RecurringCadence::Monthly => from.checked_add_months(Months::new(1)),
        RecurringCadence::Quarterly => from.checked_add_months(Months::new(3)),
        RecurringCadence::Yearly => from.checked_add_months(Months::new(12)),
    };
    moved.unwrap_or(NaiveDate::MAX)
}

/// A recurring_series row with its category.
#[derive(Debug, Clone, DbFromRow)]
pub struct Series {
    pub id: i64,
    pub label: String,
    pub counterparty: Option<String>,
    pub expected_amount: Decimal,
    pub cadence: RecurringCadence,
    pub status: RecurringStatus,
    pub next_due_date: Option<NaiveDate>,
    pub last_seen_date: Option<NaiveDate>,
    pub category_id: Option<i64>,
    pub category_name: Option<String>,
    pub category_color: Option<String>,
    pub category_icon: Option<String>,
    pub confidence: Option<Decimal>,
    pub amount_min: Option<Decimal>,
    pub amount_max: Option<Decimal>,
    pub is_variable: bool,
    pub previous_amount: Option<Decimal>,
    pub price_changed_at: Option<NaiveDate>,
    pub auto_confirmed: bool,
}

const SELECT: &str = "SELECT s.id, s.label, s.counterparty, s.expected_amount, s.cadence::text AS cadence, s.status, \
    s.next_due_date, s.last_seen_date, s.category_id, c.name AS category_name, c.color AS category_color, \
    c.icon AS category_icon, s.confidence, s.amount_min, s.amount_max, s.is_variable, s.previous_amount, \
    s.price_changed_at, s.auto_confirmed FROM recurring_series s LEFT JOIN category c ON c.id = s.category_id";

impl Series {
    fn set_category(&mut self, category: Option<&Category>) {
        self.category_id = category.map(|c| c.id);
        self.category_name = category.map(|c| c.name.clone());
        self.category_color = category.map(|c| c.color.clone());
        self.category_icon = category.and_then(|c| c.icon.clone());
    }
}

/// The member's series, optionally of one status, soonest due first (nulls last).
pub async fn series_of_member<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    status: Option<RecurringStatus>,
) -> sqlx::Result<Vec<Series>> {
    crate::db::query_as::<Series>(&format!(
        "{SELECT} WHERE s.member_id = $1 AND ($2::recurring_status IS NULL OR s.status = $2) ORDER BY s.next_due_date ASC NULLS LAST"
    ))
    .bind(member_id)
    .bind(status)
    .fetch_all(db)
    .await
    .map(|rows: Vec<Series>| rows.into_iter().map(with_column_scales).collect())
}

/// Matches in Rust via [`str::to_lowercase`] instead of SQL `upper()` because SQLite's built-in
/// `upper()` is ASCII-only (which would treat `"Élodie"` and `"élodie"` as distinct series).
pub async fn find_by_label<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    label: &str,
) -> sqlx::Result<Option<Series>> {
    let target = label.to_lowercase();
    let rows: Vec<Series> =
        crate::db::query_as::<Series>(&format!("{SELECT} WHERE s.member_id = $1 ORDER BY s.id"))
            .bind(member_id)
            .fetch_all(db)
            .await?;
    Ok(rows
        .into_iter()
        .find(|s| s.label.to_lowercase() == target)
        .map(with_column_scales))
}

/// Restores the NUMERIC(20, 2) scale of zero amounts, as JDBC reads them.
fn with_column_scales(mut s: Series) -> Series {
    s.expected_amount = column_scale(s.expected_amount, 2);
    for v in [&mut s.amount_min, &mut s.amount_max, &mut s.previous_amount]
        .into_iter()
        .flatten()
    {
        *v = column_scale(*v, 2);
    }
    s
}

fn series_not_found() -> AppError {
    AppError::NotFound("Recurring series not found".into())
}

async fn require_series(state: &AppState, id: i64, member_id: i64) -> AppResult<Series> {
    crate::db::query_as::<Series>(&format!("{SELECT} WHERE s.id = $1 AND s.member_id = $2"))
        .bind(id)
        .bind(member_id)
        .fetch_optional(&state.db)
        .await?
        .map(with_column_scales)
        .ok_or_else(series_not_found)
}

/// Inserts (id 0) or updates the row; returns its id.
pub async fn save<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    s: &Series,
    now: DateTime<Utc>,
) -> sqlx::Result<i64> {
    let sql = if s.id == 0 {
        "INSERT INTO recurring_series (member_id, label, counterparty, expected_amount, cadence, next_due_date, \
         last_seen_date, category_id, status, confidence, amount_min, amount_max, is_variable, previous_amount, \
         price_changed_at, auto_confirmed, created_at, updated_at) VALUES ($2, $3, $4, $5, $6::recurring_cadence, $7, \
         $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $18) RETURNING id"
    } else {
        "UPDATE recurring_series SET label = $3, counterparty = $4, expected_amount = $5, \
         cadence = $6::recurring_cadence, next_due_date = $7, last_seen_date = $8, category_id = $9, status = $10, \
         confidence = $11, amount_min = $12, amount_max = $13, is_variable = $14, previous_amount = $15, \
         price_changed_at = $16, auto_confirmed = $17, updated_at = $18 WHERE id = $1 AND member_id = $2 RETURNING id"
    };
    crate::db::query_scalar::<i64>(sql)
        .bind(s.id)
        .bind(member_id)
        .bind(&s.label)
        .bind(&s.counterparty)
        .bind(s.expected_amount)
        .bind(s.cadence)
        .bind(s.next_due_date)
        .bind(s.last_seen_date)
        .bind(s.category_id)
        .bind(s.status)
        .bind(s.confidence)
        .bind(s.amount_min)
        .bind(s.amount_max)
        .bind(s.is_variable)
        .bind(s.previous_amount)
        .bind(s.price_changed_at)
        .bind(s.auto_confirmed)
        .bind(now)
        .fetch_one(db)
        .await
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RuntimeStatus {
    Stale,
    Late,
    DueSoon,
    Scheduled,
}

/// Whether at least STALE_MISSED_PERIODS expected occurrences passed since the due date.
pub fn is_stale(
    due: Option<NaiveDate>,
    today: Option<NaiveDate>,
    cadence: RecurringCadence,
) -> bool {
    let (Some(due), Some(today)) = (due, today) else {
        return false;
    };
    if due >= today {
        return false;
    }
    let mut missed = 0;
    let mut step = due;
    while missed < STALE_MISSED_PERIODS {
        step = next(cadence, step);
        if step >= today {
            break;
        }
        missed += 1;
    }
    missed >= STALE_MISSED_PERIODS
}

fn runtime_status(
    due: Option<NaiveDate>,
    today: Option<NaiveDate>,
    cadence: RecurringCadence,
) -> RuntimeStatus {
    let (Some(due_date), Some(today_date)) = (due, today) else {
        return RuntimeStatus::Scheduled;
    };
    if is_stale(due, today, cadence) {
        return RuntimeStatus::Stale;
    }
    if due_date < today_date {
        return RuntimeStatus::Late;
    }
    let soon = today_date
        .checked_add_days(Days::new(DUE_SOON_DAYS))
        .unwrap_or(NaiveDate::MAX);
    if due_date <= soon {
        RuntimeStatus::DueSoon
    } else {
        RuntimeStatus::Scheduled
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecurringSeriesResponse {
    pub id: i64,
    pub label: String,
    pub counterparty: Option<String>,
    #[serde(with = "jdec")]
    pub expected_amount: Decimal,
    pub cadence: RecurringCadence,
    pub status: RecurringStatus,
    pub next_due_date: Option<NaiveDate>,
    pub last_seen_date: Option<NaiveDate>,
    pub category_id: Option<i64>,
    pub category_name: Option<String>,
    pub category_color: Option<String>,
    pub category_icon: Option<String>,
    #[serde(with = "jdec::option")]
    pub confidence: Option<Decimal>,
    #[serde(with = "jdec::option")]
    pub amount_min: Option<Decimal>,
    #[serde(with = "jdec::option")]
    pub amount_max: Option<Decimal>,
    pub variable: bool,
    #[serde(with = "jdec::option")]
    pub previous_amount: Option<Decimal>,
    pub price_changed_at: Option<NaiveDate>,
    pub auto_confirmed: bool,
    pub runtime_status: RuntimeStatus,
}

/// RecurringSeriesResponse.from: without a reference date the urgency is SCHEDULED.
fn response(s: &Series, today: Option<NaiveDate>) -> RecurringSeriesResponse {
    RecurringSeriesResponse {
        id: s.id,
        label: s.label.clone(),
        counterparty: s.counterparty.clone(),
        expected_amount: s.expected_amount,
        cadence: s.cadence,
        status: s.status,
        next_due_date: s.next_due_date,
        last_seen_date: s.last_seen_date,
        category_id: s.category_id,
        category_name: s.category_name.clone(),
        category_color: s.category_color.clone(),
        category_icon: s.category_icon.clone(),
        confidence: s.confidence,
        amount_min: s.amount_min,
        amount_max: s.amount_max,
        variable: s.is_variable,
        previous_amount: s.previous_amount,
        price_changed_at: s.price_changed_at,
        auto_confirmed: s.auto_confirmed,
        runtime_status: runtime_status(s.next_due_date, today, s.cadence),
    }
}

pub async fn list_for(
    state: &AppState,
    member_id: i64,
    status: Option<RecurringStatus>,
) -> AppResult<Vec<RecurringSeriesResponse>> {
    let today = state.clock.today();
    let series = series_of_member(&state.db, member_id, status).await?;
    Ok(series.iter().map(|s| response(s, Some(today))).collect())
}

async fn find_all(
    State(state): State<AppState>,
    auth: Auth,
    params: Params,
) -> AppResult<axum::Json<Vec<RecurringSeriesResponse>>> {
    let status = params.optional::<RecurringStatus>("status")?;
    Ok(axum::Json(list_for(&state, auth.member_id, status).await?))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ActivityType {
    AutoConfirmed,
    PriceChange,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecurringActivityResponse {
    pub series_id: i64,
    pub label: String,
    #[serde(rename = "type")]
    pub activity_type: ActivityType,
    pub occurred_on: Option<NaiveDate>,
    #[serde(with = "jdec")]
    pub expected_amount: Decimal,
    #[serde(with = "jdec::option")]
    pub previous_amount: Option<Decimal>,
    pub cadence: RecurringCadence,
    pub category_id: Option<i64>,
    pub category_name: Option<String>,
    pub category_color: Option<String>,
    pub category_icon: Option<String>,
}

/// The one entry a series contributes: a recent price change before a recent silent confirm.
fn activity_entry(s: &Series, since: NaiveDate) -> Option<RecurringActivityResponse> {
    let (activity_type, occurred_on, previous_amount) =
        if s.price_changed_at.is_some_and(|d| d >= since) {
            (
                ActivityType::PriceChange,
                s.price_changed_at,
                s.previous_amount,
            )
        } else if s.auto_confirmed
            && s.status == RecurringStatus::Confirmed
            && s.last_seen_date.is_some_and(|d| d >= since)
        {
            (ActivityType::AutoConfirmed, s.last_seen_date, None)
        } else {
            return None;
        };
    Some(RecurringActivityResponse {
        series_id: s.id,
        label: s.label.clone(),
        activity_type,
        occurred_on,
        expected_amount: s.expected_amount,
        previous_amount,
        cadence: s.cadence,
        category_id: s.category_id,
        category_name: s.category_name.clone(),
        category_color: s.category_color.clone(),
        category_icon: s.category_icon.clone(),
    })
}

pub async fn activity(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Vec<RecurringActivityResponse>>> {
    let today = state.clock.today();
    let since = today
        .checked_sub_days(Days::new(ACTIVITY_LOOKBACK_DAYS))
        .unwrap_or(NaiveDate::MIN);
    let mut feed: Vec<RecurringActivityResponse> =
        series_of_member(&state.db, auth.member_id, None)
            .await?
            .iter()
            .filter_map(|s| activity_entry(s, since))
            .collect();
    // Newest first; Comparator.nullsLast(naturalOrder()).reversed() puts nulls first.
    feed.sort_by(|a, b| match (a.occurred_on, b.occurred_on) {
        (None, None) => std::cmp::Ordering::Equal,
        (None, Some(_)) => std::cmp::Ordering::Less,
        (Some(_), None) => std::cmp::Ordering::Greater,
        (Some(x), Some(y)) => y.cmp(&x),
    });
    Ok(axum::Json(feed))
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecurringOccurrenceResponse {
    pub series_id: i64,
    pub label: String,
    pub counterparty: Option<String>,
    #[serde(with = "jdec")]
    pub expected_amount: Decimal,
    pub due_date: NaiveDate,
    pub category_id: Option<i64>,
    pub category_name: Option<String>,
    pub category_color: Option<String>,
    pub category_icon: Option<String>,
    pub reward_points: Option<i64>,
    pub credit_card_payment: bool,
}

/// Projects confirmed, non-stale series from today through today + horizon, plus credit-card
/// payments due in the window.
pub async fn upcoming(
    state: &AppState,
    member_id: i64,
    today: NaiveDate,
    horizon_days: i32,
) -> AppResult<Vec<RecurringOccurrenceResponse>> {
    let end = if horizon_days >= 0 {
        today.checked_add_days(Days::new(horizon_days.unsigned_abs().into()))
    } else {
        today.checked_sub_days(Days::new(horizon_days.unsigned_abs().into()))
    }
    .unwrap_or(if horizon_days >= 0 {
        NaiveDate::MAX
    } else {
        NaiveDate::MIN
    });
    let mut occurrences = Vec::new();
    for s in series_of_member(&state.db, member_id, Some(RecurringStatus::Confirmed)).await? {
        let Some(mut due) = s.next_due_date else {
            continue;
        };
        if is_stale(Some(due), Some(today), s.cadence) {
            continue;
        }
        while due < today {
            due = next(s.cadence, due);
        }
        let mut guard = 0;
        while due <= end && guard < 400 {
            occurrences.push(RecurringOccurrenceResponse {
                series_id: s.id,
                label: s.label.clone(),
                counterparty: s.counterparty.clone(),
                expected_amount: s.expected_amount,
                due_date: due,
                category_id: s.category_id,
                category_name: s.category_name.clone(),
                category_color: s.category_color.clone(),
                category_icon: s.category_icon.clone(),
                reward_points: None,
                credit_card_payment: false,
            });
            if due == NaiveDate::MAX {
                break;
            }
            due = next(s.cadence, due);
            guard += 1;
        }
    }
    for account in accounts::find_all_by_member(&state.db, member_id).await? {
        let (Some(due), Some(amount)) = (account.payment_due_date, account.payment_due_amount)
        else {
            continue;
        };
        if account.account_type == AccountType::CreditCard
            && due >= today
            && due <= end
            && amount > Decimal::ZERO
        {
            occurrences.push(RecurringOccurrenceResponse {
                series_id: -account.id,
                label: account.name.clone(),
                counterparty: Some(account.name.clone()),
                expected_amount: jdec::negate(amount),
                due_date: due,
                category_id: None,
                category_name: None,
                category_color: None,
                category_icon: None,
                reward_points: account.reward_points,
                credit_card_payment: true,
            });
        }
    }
    occurrences.sort_by_key(|o| o.due_date);
    Ok(occurrences)
}

async fn calendar(
    State(state): State<AppState>,
    auth: Auth,
    params: Params,
) -> AppResult<axum::Json<Vec<RecurringOccurrenceResponse>>> {
    let horizon = params.or_default("horizonDays", 60)?;
    Ok(axum::Json(
        upcoming(&state, auth.member_id, state.clock.today(), horizon).await?,
    ))
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecurringSeriesRequest {
    label: Option<String>,
    counterparty: Option<String>,
    expected_amount: Option<Decimal>,
    cadence: Option<RecurringCadence>,
    next_due_date: Option<NaiveDate>,
    category_id: Option<i64>,
}

impl Validate for RecurringSeriesRequest {
    fn validate(&self, errors: &mut Errors) {
        errors
            .not_blank("label", self.label.as_deref())
            .size("label", self.label.as_deref(), 0, 255)
            .size("counterparty", self.counterparty.as_deref(), 0, 255)
            .not_null("expectedAmount", self.expected_amount.as_ref())
            .not_null("cadence", self.cadence.as_ref());
    }
}

impl RecurringSeriesRequest {
    async fn apply(&self, state: &AppState, member_id: i64, s: &mut Series) -> AppResult<()> {
        s.label = java_trim(self.label.as_deref().unwrap_or_default()).to_owned();
        s.counterparty = self
            .counterparty
            .as_deref()
            .map(|c| java_trim(c).to_owned());
        s.expected_amount = self.expected_amount.unwrap_or_default();
        s.cadence = self.cadence.unwrap_or(RecurringCadence::Monthly);
        s.next_due_date = self.next_due_date;
        let category = match self.category_id {
            Some(id) => Some(require(&state.db, id, member_id).await?),
            None => None,
        };
        s.set_category(category.as_ref());
        Ok(())
    }
}

pub async fn create(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<RecurringSeriesRequest>,
) -> AppResult<(StatusCode, axum::Json<RecurringSeriesResponse>)> {
    let mut s = Series {
        id: 0,
        label: String::new(),
        counterparty: None,
        expected_amount: Decimal::ZERO,
        cadence: RecurringCadence::Monthly,
        status: RecurringStatus::Confirmed,
        next_due_date: None,
        last_seen_date: None,
        category_id: None,
        category_name: None,
        category_color: None,
        category_icon: None,
        confidence: None,
        amount_min: None,
        amount_max: None,
        is_variable: false,
        previous_amount: None,
        price_changed_at: None,
        auto_confirmed: false,
    };
    req.apply(&state, auth.member_id, &mut s).await?;
    s.id = save(&state.db, auth.member_id, &s, state.clock.now()).await?;
    Ok((StatusCode::CREATED, axum::Json(response(&s, None))))
}

pub async fn update(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    Json(req): Json<RecurringSeriesRequest>,
) -> AppResult<axum::Json<RecurringSeriesResponse>> {
    let mut s = require_series(&state, id, auth.member_id).await?;
    req.apply(&state, auth.member_id, &mut s).await?;
    save(&state.db, auth.member_id, &s, state.clock.now()).await?;
    Ok(axum::Json(response(&s, None)))
}

async fn set_status(
    state: &AppState,
    id: i64,
    member_id: i64,
    status: RecurringStatus,
) -> AppResult<RecurringSeriesResponse> {
    let mut s = require_series(state, id, member_id).await?;
    s.status = status;
    save(&state.db, member_id, &s, state.clock.now()).await?;
    Ok(response(&s, None))
}

pub async fn confirm(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<RecurringSeriesResponse>> {
    Ok(axum::Json(
        set_status(&state, id, auth.member_id, RecurringStatus::Confirmed).await?,
    ))
}

/// Ignored series are kept so detection never resurrects them.
pub async fn ignore(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<RecurringSeriesResponse>> {
    Ok(axum::Json(
        set_status(&state, id, auth.member_id, RecurringStatus::Ignored).await?,
    ))
}

/// Acknowledges a price change (the new amount stays) or rejects a silent auto-confirm.
pub async fn undo(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<RecurringSeriesResponse>> {
    let mut s = require_series(&state, id, auth.member_id).await?;
    if s.price_changed_at.is_some() {
        s.previous_amount = None;
        s.price_changed_at = None;
    } else if s.auto_confirmed {
        s.status = RecurringStatus::Ignored;
        s.auto_confirmed = false;
    }
    save(&state.db, auth.member_id, &s, state.clock.now()).await?;
    Ok(axum::Json(response(&s, Some(state.clock.today()))))
}

pub async fn delete(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<StatusCode> {
    let s = require_series(&state, id, auth.member_id).await?;
    crate::db::query("DELETE FROM recurring_series WHERE id = $1")
        .bind(s.id)
        .execute(&state.db)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Serialize)]
struct Detected {
    detected: i32,
}

async fn detect(State(state): State<AppState>, auth: Auth) -> AppResult<axum::Json<Detected>> {
    Ok(axum::Json(Detected {
        detected: detection::detect(&state, auth.member_id, state.clock.today()).await?,
    }))
}
