//! CategoryController and CategoryService: the member's categories, seeded with the defaults on
//! first read, in a strict one-level tree. Removal is archiving, cascading to direct children.

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::PgExecutor;

use super::repo;
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Json, Path, Validate};
use crate::ledger::categorize;
use crate::ledger::model::{CATEGORY_COLUMNS, Category, CategoryKind};
use crate::ledger::sync_values::java_is_blank;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/categories", find_all)
        .post("/api/categories", create)
        .put("/api/categories/{id}", update)
        .delete("/api/categories/{id}", archive)
        .post("/api/categories/{id}/unarchive", unarchive);
}

pub fn category_not_found() -> AppError {
    AppError::NotFound("Category not found".into())
}

/// CategoryService.getOrThrow / requireCategory: the member's category or 404.
pub async fn require<'e>(db: impl PgExecutor<'e>, id: i64, member_id: i64) -> AppResult<Category> {
    repo::category_of_member(db, id, member_id)
        .await?
        .ok_or_else(category_not_found)
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CategoryResponse {
    pub id: i64,
    pub name: String,
    pub kind: CategoryKind,
    pub color: String,
    pub icon: Option<String>,
    pub is_default: bool,
    pub archived: bool,
    pub sort_order: i32,
    pub parent_id: Option<i64>,
}

impl From<&Category> for CategoryResponse {
    fn from(c: &Category) -> Self {
        Self {
            id: c.id,
            name: c.name.clone(),
            kind: c.kind,
            color: c.color.clone(),
            icon: c.icon.clone(),
            is_default: c.is_default,
            archived: c.archived,
            sort_order: c.sort_order,
            parent_id: c.parent_id,
        }
    }
}

async fn find_all(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Vec<CategoryResponse>>> {
    categorize::ensure_seeded(&state.db, auth.member_id, state.clock.now()).await?;
    let all = categorize::categories_of_member(&state.db, auth.member_id, true).await?;
    Ok(axum::Json(all.iter().map(CategoryResponse::from).collect()))
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CategoryRequest {
    name: Option<String>,
    kind: Option<CategoryKind>,
    color: Option<String>,
    icon: Option<String>,
    sort_order: Option<i32>,
    parent_id: Option<i64>,
}

impl Validate for CategoryRequest {
    fn validate(&self, errors: &mut Errors) {
        errors
            .not_blank("name", self.name.as_deref())
            .size("name", self.name.as_deref(), 0, 100)
            .not_null("kind", self.kind.as_ref());
    }
}

impl CategoryRequest {
    fn name(&self) -> String {
        self.name.clone().unwrap_or_default()
    }

    fn kind(&self) -> CategoryKind {
        self.kind.unwrap_or(CategoryKind::Expense)
    }

    fn color(&self) -> Option<&str> {
        self.color.as_deref().filter(|c| !java_is_blank(c))
    }
}

/// resolveParent: the parent must be the member's, a root, and of the child's kind.
async fn resolve_parent(
    state: &AppState,
    parent_id: Option<i64>,
    child_id: Option<i64>,
    kind: CategoryKind,
    member_id: i64,
) -> AppResult<Option<Category>> {
    let Some(parent_id) = parent_id else {
        return Ok(None);
    };
    if Some(parent_id) == child_id {
        return Err(AppError::BadRequest(
            "A category cannot be its own parent".into(),
        ));
    }
    let parent = require(&state.db, parent_id, member_id).await?;
    if parent.parent_id.is_some() {
        return Err(AppError::BadRequest(
            "Categories support a single level of nesting".into(),
        ));
    }
    if parent.kind != kind {
        return Err(AppError::BadRequest(
            "A sub-category must share its parent's kind".into(),
        ));
    }
    Ok(Some(parent))
}

async fn next_sort_order(state: &AppState, member_id: i64) -> AppResult<i32> {
    let max: Option<i32> =
        sqlx::query_scalar("SELECT MAX(sort_order) FROM category WHERE member_id = $1")
            .bind(member_id)
            .fetch_one(&state.db)
            .await?;
    Ok(max.unwrap_or(-1) + 1)
}

async fn create(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<CategoryRequest>,
) -> AppResult<impl IntoResponse> {
    let member_id = auth.member_id;
    let parent = resolve_parent(&state, req.parent_id, None, req.kind(), member_id).await?;
    let sort_order = match req.sort_order {
        Some(order) => order,
        None => next_sort_order(&state, member_id).await?,
    };
    let now = state.clock.now();
    let category: Category = sqlx::query_as(&format!(
        "WITH c AS (INSERT INTO category (member_id, parent_id, name, kind, color, icon, is_default, archived, \
         sort_order, created_at, updated_at) VALUES ($1, $2, $3, $4::category_kind, $5, $6, false, false, $7, $8, $8) \
         RETURNING *) SELECT {CATEGORY_COLUMNS} FROM c"
    ))
    .bind(member_id)
    .bind(parent.map(|p| p.id))
    .bind(req.name())
    .bind(req.kind())
    .bind(req.color().unwrap_or("#6366f1"))
    .bind(&req.icon)
    .bind(sort_order)
    .bind(now)
    .fetch_one(&state.db)
    .await?;
    Ok((
        StatusCode::CREATED,
        axum::Json(CategoryResponse::from(&category)),
    ))
}

async fn save<'e>(db: impl PgExecutor<'e>, c: &Category, now: DateTime<Utc>) -> sqlx::Result<()> {
    sqlx::query(
        "UPDATE category SET parent_id = $2, name = $3, kind = $4::category_kind, color = $5, icon = $6, \
         archived = $7, sort_order = $8, updated_at = $9 WHERE id = $1",
    )
    .bind(c.id)
    .bind(c.parent_id)
    .bind(&c.name)
    .bind(c.kind)
    .bind(&c.color)
    .bind(&c.icon)
    .bind(c.archived)
    .bind(c.sort_order)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}

async fn update(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    Json(req): Json<CategoryRequest>,
) -> AppResult<axum::Json<CategoryResponse>> {
    let member_id = auth.member_id;
    let mut category = require(&state.db, id, member_id).await?;
    let has_children: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM category WHERE member_id = $1 AND parent_id = $2)",
    )
    .bind(member_id)
    .bind(id)
    .fetch_one(&state.db)
    .await?;
    if has_children && req.parent_id.is_some() {
        return Err(AppError::BadRequest(
            "A category with sub-categories cannot become a sub-category".into(),
        ));
    }
    if has_children && category.kind != req.kind() {
        return Err(AppError::BadRequest(
            "A category with sub-categories cannot change kind".into(),
        ));
    }
    let parent = resolve_parent(&state, req.parent_id, Some(id), req.kind(), member_id).await?;
    category.name = req.name();
    category.kind = req.kind();
    category.parent_id = parent.map(|p| p.id);
    if let Some(color) = req.color() {
        color.clone_into(&mut category.color);
    }
    category.icon.clone_from(&req.icon);
    if let Some(order) = req.sort_order {
        category.sort_order = order;
    }
    save(&state.db, &category, state.clock.now()).await?;
    Ok(axum::Json(CategoryResponse::from(&category)))
}

/// Flips archived on the category and its direct children.
async fn set_archived_cascading(
    state: &AppState,
    category: &mut Category,
    member_id: i64,
    archived: bool,
) -> AppResult<()> {
    let now = state.clock.now();
    let mut tx = state.db.begin().await?;
    category.archived = archived;
    save(&mut *tx, category, now).await?;
    for mut child in repo::children(&mut *tx, member_id, category.id).await? {
        child.archived = archived;
        save(&mut *tx, &child, now).await?;
    }
    tx.commit().await?;
    Ok(())
}

async fn archive(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<StatusCode> {
    let mut category = require(&state.db, id, auth.member_id).await?;
    set_archived_cascading(&state, &mut category, auth.member_id, true).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn unarchive(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<CategoryResponse>> {
    let mut category = require(&state.db, id, auth.member_id).await?;
    set_archived_cascading(&state, &mut category, auth.member_id, false).await?;
    Ok(axum::Json(CategoryResponse::from(&category)))
}
