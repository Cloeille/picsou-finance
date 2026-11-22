//! Savings goals, recurring investment plans and their monthly entries (GoalController,
//! GoalService).

pub mod dto;
pub mod months;
pub mod progress;
pub mod repo;

use std::collections::HashSet;

use axum::extract::{FromRequest, Request, State};
use axum::http::{StatusCode, header};
use rust_decimal::Decimal;
use sqlx::PgConnection;

use self::dto::{
    GoalMonthEntryResponse, GoalProgressResponse, GoalRequest, GoalType, MonthAmountRequest,
};
use self::months::YearMonth;
use self::progress::Ctx;
use self::repo::{Allocation, Goal, MonthTable, goal_not_found};
use crate::AppState;
use crate::auth::Auth;
use crate::db::{AsDbHandle, DbHandle};
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Json, Lang, Path, read_json};
use crate::ledger::{accounts, holdings};
use crate::routes::Routes;
use crate::wealth::history::NetWorthPoint;

pub fn register(routes: &mut Routes) {
    routes.get("/api/goals", find_all);
    routes.post("/api/goals", create);
    routes.get("/api/goals/{id}", find_by_id);
    routes.put("/api/goals/{id}", update);
    routes.delete("/api/goals/{id}", delete);
    routes.get("/api/goals/{id}/history", history);
    routes.get("/api/goals/{id}/months", monthly_entries);
    routes.post("/api/goals/{id}/history/extend", extend_history);
    routes.post(
        "/api/goals/{id}/history/extend/month",
        extend_history_by_month,
    );
    routes.put("/api/goals/{id}/months/{yearMonth}", set_month_override);
    routes.delete("/api/goals/{id}/months/{yearMonth}", delete_month_override);
    routes.put(
        "/api/goals/{id}/months/{yearMonth}/manual",
        set_manual_contribution,
    );
    routes.delete(
        "/api/goals/{id}/months/{yearMonth}/manual",
        delete_manual_contribution,
    );
}

/// A validated GoalRequest; @Future on the deadline reads the app clock.
pub struct GoalBody(pub GoalRequest);

impl FromRequest<AppState> for GoalBody {
    type Rejection = AppError;

    async fn from_request(request: Request, state: &AppState) -> Result<Self, Self::Rejection> {
        let lang = Lang::from_accept_language(
            request
                .headers()
                .get(header::ACCEPT_LANGUAGE)
                .and_then(|v| v.to_str().ok()),
        );
        let req: GoalRequest = read_json(request, state).await?;
        let mut errors = Errors::new(lang);
        req.validate(&mut errors, state.clock.today());
        errors.into_result()?;
        Ok(Self(req))
    }
}

async fn get_or_throw(conn: &mut DbHandle<'_>, id: i64, member_id: i64) -> AppResult<Goal> {
    repo::find(conn, id, member_id)
        .await?
        .ok_or_else(goal_not_found)
}

fn require_savings_target(goal: Goal) -> AppResult<Goal> {
    if goal.goal_type == GoalType::RecurringInvestment {
        return Err(progress::not_a_savings_target());
    }
    Ok(goal)
}

/// The goal as the request describes it: only the member's own accounts may be attached, and a
/// split may only name positions the funded account holds.
async fn apply_request(
    conn: &mut PgConnection,
    goal: &mut Goal,
    req: GoalRequest,
) -> AppResult<()> {
    let ids = req.ids();
    let owned = accounts::find_by_ids_and_member(&mut *conn, ids, goal.member_id).await?;
    if owned.len() != ids.len() {
        return Err(AppError::BadRequest(
            "One or more account IDs not found".to_owned(),
        ));
    }
    let mut allocations = Vec::new();
    if let Some(funded) = owned.first().filter(|_| !req.lines().is_empty()) {
        let held: HashSet<String> = holdings::find_by_account(&mut *conn, funded.id)
            .await?
            .into_iter()
            .map(|h| h.ticker)
            .collect();
        for line in req.lines() {
            let ticker = line.ticker.clone().unwrap_or_default();
            if !held.contains(&ticker) {
                return Err(AppError::BadRequest(format!(
                    "Position {ticker} is not held in the funded account"
                )));
            }
            allocations.push(Allocation {
                ticker,
                monthly_amount: line.monthly_amount.unwrap_or_default(),
            });
        }
    }
    goal.goal_type = req.kind();
    goal.name = req.name.unwrap_or_default();
    goal.target_amount = req.target_amount;
    goal.deadline = req.deadline;
    goal.monthly_amount = req.monthly_amount;
    goal.expected_return = req.expected_return;
    goal.start_date = req.start_date;
    goal.end_date = req.end_date;
    goal.accounts = owned;
    goal.allocations = allocations;
    Ok(())
}

pub async fn find_all(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Vec<GoalProgressResponse>>> {
    let mut conn = state.db.acquire().await?;
    let goals = repo::find_all(&mut conn.as_handle(), auth.member_id).await?;
    let mut out = Vec::with_capacity(goals.len());
    for goal in &goals {
        out.push(progress::to_response(&state, &mut conn, goal).await?);
    }
    Ok(axum::Json(out))
}

/// DashboardService.goalSummaries (track T2's DashboardGoals seam): every goal but the
/// recurring plans, oldest first.
pub async fn dashboard_summaries(
    state: &AppState,
    member_id: i64,
) -> AppResult<Vec<GoalProgressResponse>> {
    let mut conn = state.db.acquire().await?;
    let goals = repo::find_all(&mut conn.as_handle(), member_id).await?;
    let mut out = Vec::new();
    for goal in goals
        .iter()
        .filter(|g| g.goal_type != GoalType::RecurringInvestment)
    {
        out.push(progress::to_response(state, &mut conn, goal).await?);
    }
    Ok(out)
}

pub async fn find_by_id(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<GoalProgressResponse>> {
    let mut conn = state.db.acquire().await?;
    let goal = get_or_throw(&mut conn.as_handle(), id, auth.member_id).await?;
    Ok(axum::Json(
        progress::to_response(&state, &mut conn, &goal).await?,
    ))
}

pub async fn create(
    State(state): State<AppState>,
    auth: Auth,
    GoalBody(req): GoalBody,
) -> AppResult<(StatusCode, axum::Json<GoalProgressResponse>)> {
    let now = state.clock.now();
    let mut tx = state.db.begin().await?;
    let mut goal = Goal {
        id: 0,
        member_id: auth.member_id,
        name: String::new(),
        goal_type: GoalType::SavingsTarget,
        target_amount: None,
        deadline: None,
        monthly_amount: None,
        expected_return: None,
        start_date: None,
        end_date: None,
        history_start_month: None,
        created_at: now,
        accounts: Vec::new(),
        allocations: Vec::new(),
    };
    apply_request(&mut tx, &mut goal, req).await?;
    state.wealth.savings.account_responses()?;
    goal.id = repo::insert(&mut tx.as_handle(), &goal, now).await?;
    tx.commit().await?;
    let mut conn = state.db.acquire().await?;
    let response = progress::to_response(&state, &mut conn, &goal).await?;
    Ok((StatusCode::CREATED, axum::Json(response)))
}

pub async fn update(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    GoalBody(req): GoalBody,
) -> AppResult<axum::Json<GoalProgressResponse>> {
    let mut tx = state.db.begin().await?;
    let mut goal = get_or_throw(&mut tx.as_handle(), id, auth.member_id).await?;
    apply_request(&mut tx, &mut goal, req).await?;
    state.wealth.savings.account_responses()?;
    repo::update(&mut tx.as_handle(), &goal, state.clock.now()).await?;
    tx.commit().await?;
    let mut conn = state.db.acquire().await?;
    Ok(axum::Json(
        progress::to_response(&state, &mut conn, &goal).await?,
    ))
}

pub async fn delete(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<StatusCode> {
    let mut conn = state.db.acquire().await?;
    get_or_throw(&mut conn.as_handle(), id, auth.member_id).await?;
    repo::delete(&mut conn, id).await?;
    Ok(StatusCode::NO_CONTENT)
}

/// The daily history of the goal's accounts over twelve months, from the HistorySource seam.
async fn history(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<Vec<NetWorthPoint>>> {
    let mut conn = state.db.acquire().await?;
    let goal = get_or_throw(&mut conn.as_handle(), id, auth.member_id).await?;
    drop(conn);
    let ids: Vec<i64> = goal.accounts.iter().map(|a| a.id).collect();
    let points = state
        .wealth
        .history
        .build_history(&state, &ids, 12, false, auth.member_id)
        .await?;
    Ok(axum::Json(points))
}

/// The calendar's first month: the creation month, or an earlier backfilled one.
fn effective_start_month(ctx: &Ctx<'_>, goal: &Goal) -> YearMonth {
    let created = ctx.created_month(goal);
    goal.history_start_month
        .as_deref()
        .and_then(YearMonth::parse)
        .filter(|h| *h < created)
        .unwrap_or(created)
}

pub async fn monthly_entries(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<Vec<GoalMonthEntryResponse>>> {
    let mut conn = state.db.acquire().await?;
    let goal =
        require_savings_target(get_or_throw(&mut conn.as_handle(), id, auth.member_id).await?)?;
    let ctx = Ctx::of(&state);
    let objective = progress::monthly_needed(&mut conn, &ctx, &goal).await?;
    let overrides = repo::month_amounts(&mut conn, MonthTable::Override, id).await?;
    let manuals = repo::month_amounts(&mut conn, MonthTable::Manual, id).await?;
    let end = YearMonth::of(goal.deadline.ok_or_else(progress::not_a_savings_target)?);
    let mut entries = Vec::new();
    let mut month = effective_start_month(&ctx, &goal);
    while month <= end {
        let ym = month.to_string();
        let actual = progress::actual_for_month(&mut conn, &ctx, &goal, month).await?;
        let manual = manuals.get(&ym).copied();
        let override_amount = overrides.get(&ym).copied();
        entries.push(GoalMonthEntryResponse {
            objective: Some(override_amount.unwrap_or(objective)),
            effective: manual.or(actual),
            year_month: ym,
            actual,
            manual_actual: manual,
            override_amount,
        });
        month = month.plus_months(1);
    }
    Ok(axum::Json(entries))
}

/// Moves the calendar's start back by months; the on-track verdict stays anchored to creation.
async fn extend_by(
    state: &AppState,
    auth: &Auth,
    id: i64,
    months: i64,
) -> AppResult<GoalProgressResponse> {
    let mut conn = state.db.acquire().await?;
    let mut goal =
        require_savings_target(get_or_throw(&mut conn.as_handle(), id, auth.member_id).await?)?;
    state.wealth.savings.account_responses()?;
    let start = effective_start_month(&Ctx::of(state), &goal).plus_months(-months);
    repo::set_history_start(&mut conn, id, &start.to_string(), state.clock.now()).await?;
    goal.history_start_month = Some(start.to_string());
    progress::to_response(state, &mut conn, &goal).await
}

async fn extend_history(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<GoalProgressResponse>> {
    Ok(axum::Json(extend_by(&state, &auth, id, 12).await?))
}

async fn extend_history_by_month(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<GoalProgressResponse>> {
    Ok(axum::Json(extend_by(&state, &auth, id, 1).await?))
}

/// YearMonth.parse of the path variable; Java throws DateTimeParseException, a 500, and rolls
/// the write back, so nothing is written here before it parses.
fn parse_month(value: &str) -> AppResult<YearMonth> {
    YearMonth::parse(value).ok_or_else(|| AppError::internal("unparseable year-month"))
}

/// What a month amount write or delete answers: the month with its objective before overrides.
struct MonthChange {
    table: MonthTable,
    amount: Option<Decimal>,
}

async fn change_month(
    state: &AppState,
    auth: &Auth,
    id: i64,
    year_month: &str,
    change: MonthChange,
) -> AppResult<GoalMonthEntryResponse> {
    let mut conn = state.db.acquire().await?;
    let goal =
        require_savings_target(get_or_throw(&mut conn.as_handle(), id, auth.member_id).await?)?;
    let month = parse_month(year_month)?;
    if year_month.chars().count() > 7 {
        return Err(AppError::internal("year-month longer than its column"));
    }
    match change.amount {
        Some(amount) => {
            repo::set_month_amount(
                &mut conn,
                change.table,
                id,
                year_month,
                amount,
                auth.member_id,
            )
            .await?
        }
        None => repo::delete_month_amount(&mut conn, change.table, id, year_month).await?,
    }
    let ctx = Ctx::of(state);
    let objective = progress::monthly_needed(&mut conn, &ctx, &goal).await?;
    let actual = progress::actual_for_month(&mut conn, &ctx, &goal, month).await?;
    let other = match change.table {
        MonthTable::Override => MonthTable::Manual,
        MonthTable::Manual => MonthTable::Override,
    };
    let other_amount = repo::month_amount(&mut conn, other, id, year_month).await?;
    let (manual, override_amount) = match change.table {
        MonthTable::Override => (other_amount, change.amount),
        MonthTable::Manual => (change.amount, other_amount),
    };
    // Java's setters answer with the written amount (or the override over it) as effective,
    // unlike the calendar, which never shows an override as saved.
    let effective = match (change.table, change.amount) {
        (MonthTable::Override, Some(amount)) => Some(amount),
        (MonthTable::Override, None) => manual.or(actual),
        (MonthTable::Manual, Some(amount)) => Some(override_amount.unwrap_or(amount)),
        (MonthTable::Manual, None) => override_amount.or(actual),
    };
    Ok(GoalMonthEntryResponse {
        year_month: year_month.to_owned(),
        objective: Some(objective),
        actual,
        manual_actual: manual,
        override_amount,
        effective,
    })
}

async fn set_month_override(
    State(state): State<AppState>,
    auth: Auth,
    Path((id, year_month)): Path<(i64, String)>,
    Json(req): Json<MonthAmountRequest>,
) -> AppResult<axum::Json<GoalMonthEntryResponse>> {
    let change = MonthChange {
        table: MonthTable::Override,
        amount: req.amount,
    };
    Ok(axum::Json(
        change_month(&state, &auth, id, &year_month, change).await?,
    ))
}

async fn delete_month_override(
    State(state): State<AppState>,
    auth: Auth,
    Path((id, year_month)): Path<(i64, String)>,
) -> AppResult<axum::Json<GoalMonthEntryResponse>> {
    let change = MonthChange {
        table: MonthTable::Override,
        amount: None,
    };
    Ok(axum::Json(
        change_month(&state, &auth, id, &year_month, change).await?,
    ))
}

pub async fn set_manual_contribution(
    State(state): State<AppState>,
    auth: Auth,
    Path((id, year_month)): Path<(i64, String)>,
    Json(req): Json<MonthAmountRequest>,
) -> AppResult<axum::Json<GoalMonthEntryResponse>> {
    let change = MonthChange {
        table: MonthTable::Manual,
        amount: req.amount,
    };
    Ok(axum::Json(
        change_month(&state, &auth, id, &year_month, change).await?,
    ))
}

async fn delete_manual_contribution(
    State(state): State<AppState>,
    auth: Auth,
    Path((id, year_month)): Path<(i64, String)>,
) -> AppResult<axum::Json<GoalMonthEntryResponse>> {
    let change = MonthChange {
        table: MonthTable::Manual,
        amount: None,
    };
    Ok(axum::Json(
        change_month(&state, &auth, id, &year_month, change).await?,
    ))
}
