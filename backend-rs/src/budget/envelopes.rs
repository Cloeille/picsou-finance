//! BudgetController and BudgetService: one monthly cap per category, scored against the active
//! payday cycle. Spent is derived on read (outflows are negative, so spent = -sum), and a parent
//! envelope covers its whole subtree.

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use sqlx::FromRow;

use super::categories::{self, category_not_found};
use super::cycle::{self, CycleRange};
use super::{jdec, repo, settings};
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Json, Path, Validate};
use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::model::{Category, CategoryKind};
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/budgets", find_all)
        .post("/api/budgets", create)
        .put("/api/budgets/{id}", update)
        .delete("/api/budgets/{id}", delete);
}

#[derive(Debug, Clone, FromRow)]
struct BudgetRow {
    id: i64,
    category_id: i64,
    monthly_limit: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BudgetResponse {
    pub id: i64,
    pub category_id: i64,
    pub category_name: String,
    pub category_kind: CategoryKind,
    pub category_color: String,
    pub category_icon: Option<String>,
    #[serde(with = "jdec")]
    pub monthly_limit: Decimal,
    #[serde(with = "jdec")]
    pub spent: Decimal,
    #[serde(with = "jdec")]
    pub remaining: Decimal,
    #[serde(with = "jdec")]
    pub percent: Decimal,
    pub over_budget: bool,
    pub rollup: bool,
    pub cycle_start: NaiveDate,
    pub cycle_end: NaiveDate,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BudgetRequest {
    category_id: Option<i64>,
    monthly_limit: Option<Decimal>,
}

impl Validate for BudgetRequest {
    fn validate(&self, errors: &mut Errors) {
        errors
            .not_null("categoryId", self.category_id.as_ref())
            .not_null("monthlyLimit", self.monthly_limit.as_ref())
            .positive_or_zero("monthlyLimit", self.monthly_limit.as_ref());
    }
}

async fn current_cycle(state: &AppState, member_id: i64) -> AppResult<CycleRange> {
    let day = settings::cycle_start_day(&state.db, member_id).await?;
    Ok(cycle::cycle_for(state.clock.today(), day))
}

async fn response(
    state: &AppState,
    id: i64,
    category: &Category,
    limit: Decimal,
    member_id: i64,
    cycle: CycleRange,
) -> AppResult<BudgetResponse> {
    let children = repo::children(&state.db, member_id, category.id).await?;
    let rollup = !children.is_empty();
    let mut ids = vec![category.id];
    ids.extend(children.iter().map(|c| c.id));
    let signed = repo::sum_by_categories_between(&state.db, &ids, cycle.start, cycle.end).await?;
    let spent = jdec::negate(signed);
    let percent = if limit > Decimal::ZERO {
        jdec::multiply(divide(spent, limit, 4, HALF_UP), Decimal::ONE_HUNDRED)
    } else {
        Decimal::ZERO
    };
    Ok(BudgetResponse {
        id,
        category_id: category.id,
        category_name: category.name.clone(),
        category_kind: category.kind,
        category_color: category.color.clone(),
        category_icon: category.icon.clone(),
        monthly_limit: limit,
        spent,
        remaining: limit - spent,
        percent,
        over_budget: spent > limit,
        rollup,
        cycle_start: cycle.start,
        cycle_end: cycle.end,
    })
}

async fn find_all(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Vec<BudgetResponse>>> {
    let cycle = current_cycle(&state, auth.member_id).await?;
    let rows: Vec<BudgetRow> = sqlx::query_as(
        "SELECT id, category_id, monthly_limit FROM budget WHERE member_id = $1 ORDER BY id",
    )
    .bind(auth.member_id)
    .fetch_all(&state.db)
    .await?;
    let mut out = Vec::with_capacity(rows.len());
    for row in rows {
        let category = repo::categories_by_ids(&state.db, &[row.category_id])
            .await?
            .remove(&row.category_id)
            .ok_or_else(category_not_found)?;
        out.push(
            response(
                &state,
                row.id,
                &category,
                repo::column_scale(row.monthly_limit, 2),
                auth.member_id,
                cycle,
            )
            .await?,
        );
    }
    Ok(axum::Json(out))
}

async fn exists_for_category(
    state: &AppState,
    member_id: i64,
    category_id: i64,
) -> AppResult<bool> {
    Ok(sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM budget WHERE member_id = $1 AND category_id = $2)",
    )
    .bind(member_id)
    .bind(category_id)
    .fetch_one(&state.db)
    .await?)
}

/// One budget per category, and never both a parent and one of its children (the child's spend
/// would count twice across the two cards).
async fn assert_assignable(state: &AppState, category: &Category, member_id: i64) -> AppResult<()> {
    if exists_for_category(state, member_id, category.id).await? {
        return Err(AppError::BadRequest(
            "A budget already exists for this category".into(),
        ));
    }
    if let Some(parent_id) = category.parent_id
        && exists_for_category(state, member_id, parent_id).await?
    {
        return Err(AppError::BadRequest(
            "This category's parent already has a budget".into(),
        ));
    }
    for child in repo::children(&state.db, member_id, category.id).await? {
        if exists_for_category(state, member_id, child.id).await? {
            return Err(AppError::BadRequest(
                "A sub-category of this category already has a budget".into(),
            ));
        }
    }
    Ok(())
}

async fn create(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<BudgetRequest>,
) -> AppResult<impl IntoResponse> {
    let member_id = auth.member_id;
    let category =
        categories::require(&state.db, req.category_id.unwrap_or_default(), member_id).await?;
    assert_assignable(&state, &category, member_id).await?;
    let limit = req.monthly_limit.unwrap_or_default();
    let now = state.clock.now();
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO budget (member_id, category_id, monthly_limit, created_at, updated_at) \
         VALUES ($1, $2, $3, $4, $4) RETURNING id",
    )
    .bind(member_id)
    .bind(category.id)
    .bind(limit)
    .bind(now)
    .fetch_one(&state.db)
    .await?;
    let cycle = current_cycle(&state, member_id).await?;
    let body = response(&state, id, &category, limit, member_id, cycle).await?;
    Ok((StatusCode::CREATED, axum::Json(body)))
}

fn budget_not_found() -> AppError {
    AppError::NotFound("Budget not found".into())
}

async fn get_or_throw(state: &AppState, id: i64, member_id: i64) -> AppResult<BudgetRow> {
    sqlx::query_as(
        "SELECT id, category_id, monthly_limit FROM budget WHERE id = $1 AND member_id = $2",
    )
    .bind(id)
    .bind(member_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or_else(budget_not_found)
}

async fn update(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    Json(req): Json<BudgetRequest>,
) -> AppResult<axum::Json<BudgetResponse>> {
    let member_id = auth.member_id;
    let budget = get_or_throw(&state, id, member_id).await?;
    let requested = req.category_id.unwrap_or_default();
    let category = if budget.category_id == requested {
        repo::categories_by_ids(&state.db, &[budget.category_id])
            .await?
            .remove(&budget.category_id)
            .ok_or_else(category_not_found)?
    } else {
        let category = categories::require(&state.db, requested, member_id).await?;
        assert_assignable(&state, &category, member_id).await?;
        category
    };
    let limit = req.monthly_limit.unwrap_or_default();
    sqlx::query(
        "UPDATE budget SET category_id = $2, monthly_limit = $3, updated_at = $4 WHERE id = $1",
    )
    .bind(id)
    .bind(category.id)
    .bind(limit)
    .bind(state.clock.now())
    .execute(&state.db)
    .await?;
    let cycle = current_cycle(&state, member_id).await?;
    Ok(axum::Json(
        response(&state, id, &category, limit, member_id, cycle).await?,
    ))
}

async fn delete(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<StatusCode> {
    get_or_throw(&state, id, auth.member_id).await?;
    sqlx::query("DELETE FROM budget WHERE id = $1")
        .bind(id)
        .execute(&state.db)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
