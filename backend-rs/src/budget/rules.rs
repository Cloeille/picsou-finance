//! CategorizationRuleController and the rule half of CategorizationService: rule CRUD, the
//! preview of a rule before it exists, and the bulk re-run of the pipeline over everything still
//! uncategorized.

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use sqlx::PgExecutor;

use super::categories::require;
use super::{jdec, repo};
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Json, Path, Validate};
use crate::ledger::categorize::{self, Categorizable};
use crate::ledger::model::{CategorizationRule, Category, RuleMatchType, RuleSource, Transaction};
use crate::ledger::sync_values::java_trim;
use crate::routes::Routes;

/// How many matching rows the preview lists.
const PREVIEW_CAP: usize = 200;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/categorization-rules", find_all)
        .post("/api/categorization-rules", create)
        .put("/api/categorization-rules/{id}", update)
        .delete("/api/categorization-rules/{id}", delete)
        .post("/api/categorization-rules/recategorize", recategorize)
        .post("/api/categorization-rules/preview", preview);
}

const RULE_COLUMNS: &str =
    "id, member_id, match_type, pattern, category_id, priority, source, created_at";

fn rule_not_found() -> AppError {
    AppError::NotFound("Categorization rule not found".into())
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CategorizationRuleResponse {
    pub id: i64,
    pub match_type: RuleMatchType,
    pub pattern: String,
    pub category_id: i64,
    pub category_name: String,
    pub priority: i32,
    pub source: RuleSource,
}

fn response(rule: &CategorizationRule, category_name: &str) -> CategorizationRuleResponse {
    CategorizationRuleResponse {
        id: rule.id,
        match_type: rule.match_type,
        pattern: rule.pattern.clone(),
        category_id: rule.category_id,
        category_name: category_name.to_owned(),
        priority: rule.priority,
        source: rule.source,
    }
}

async fn find_all(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Vec<CategorizationRuleResponse>>> {
    let rules = categorize::rules_of_member(&state.db, auth.member_id).await?;
    let ids: Vec<i64> = rules.iter().map(|r| r.category_id).collect();
    let categories = repo::categories_by_ids(&state.db, &ids).await?;
    Ok(axum::Json(
        rules
            .iter()
            .map(|r| {
                let name = categories
                    .get(&r.category_id)
                    .map_or("", |c| c.name.as_str());
                response(r, name)
            })
            .collect(),
    ))
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CategorizationRuleRequest {
    match_type: Option<RuleMatchType>,
    pattern: Option<String>,
    category_id: Option<i64>,
    priority: Option<i32>,
}

impl Validate for CategorizationRuleRequest {
    fn validate(&self, errors: &mut Errors) {
        errors
            .not_null("matchType", self.match_type.as_ref())
            .not_blank("pattern", self.pattern.as_deref())
            .not_null("categoryId", self.category_id.as_ref());
    }
}

impl CategorizationRuleRequest {
    fn pattern(&self) -> String {
        java_trim(self.pattern.as_deref().unwrap_or_default()).to_owned()
    }
}

/// A rule to insert.
pub struct NewRule<'a> {
    pub member_id: i64,
    pub match_type: RuleMatchType,
    pub pattern: &'a str,
    pub category_id: i64,
    pub priority: i32,
    pub source: RuleSource,
}

/// Inserts a rule and returns it.
pub async fn insert_rule<'e>(
    db: impl PgExecutor<'e>,
    rule: &NewRule<'_>,
    now: DateTime<Utc>,
) -> sqlx::Result<CategorizationRule> {
    sqlx::query_as(&format!(
        "INSERT INTO categorization_rule (member_id, match_type, pattern, category_id, priority, source, created_at) \
         VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING {RULE_COLUMNS}"
    ))
    .bind(rule.member_id)
    .bind(rule.match_type)
    .bind(rule.pattern)
    .bind(rule.category_id)
    .bind(rule.priority)
    .bind(rule.source)
    .bind(now)
    .fetch_one(db)
    .await
}

async fn create(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<CategorizationRuleRequest>,
) -> AppResult<impl IntoResponse> {
    let category = require(
        &state.db,
        req.category_id.unwrap_or_default(),
        auth.member_id,
    )
    .await?;
    let pattern = req.pattern();
    let new = NewRule {
        member_id: auth.member_id,
        match_type: req.match_type.unwrap_or(RuleMatchType::Keyword),
        pattern: &pattern,
        category_id: category.id,
        priority: req.priority.unwrap_or(0),
        source: RuleSource::User,
    };
    let rule = insert_rule(&state.db, &new, state.clock.now()).await?;
    Ok((
        StatusCode::CREATED,
        axum::Json(response(&rule, &category.name)),
    ))
}

async fn find_rule(state: &AppState, id: i64, member_id: i64) -> AppResult<CategorizationRule> {
    sqlx::query_as(&format!(
        "SELECT {RULE_COLUMNS} FROM categorization_rule WHERE id = $1 AND member_id = $2"
    ))
    .bind(id)
    .bind(member_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or_else(rule_not_found)
}

async fn update(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    Json(req): Json<CategorizationRuleRequest>,
) -> AppResult<axum::Json<CategorizationRuleResponse>> {
    let mut rule = find_rule(&state, id, auth.member_id).await?;
    rule.match_type = req.match_type.unwrap_or(rule.match_type);
    rule.pattern = req.pattern();
    let category = require(
        &state.db,
        req.category_id.unwrap_or_default(),
        auth.member_id,
    )
    .await?;
    rule.category_id = category.id;
    if let Some(priority) = req.priority {
        rule.priority = priority;
    }
    sqlx::query(
        "UPDATE categorization_rule SET match_type = $2, pattern = $3, category_id = $4, priority = $5 WHERE id = $1",
    )
    .bind(rule.id)
    .bind(rule.match_type)
    .bind(&rule.pattern)
    .bind(rule.category_id)
    .bind(rule.priority)
    .execute(&state.db)
    .await?;
    Ok(axum::Json(response(&rule, &category.name)))
}

async fn delete(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<StatusCode> {
    let rule = find_rule(&state, id, auth.member_id).await?;
    sqlx::query("DELETE FROM categorization_rule WHERE id = $1")
        .bind(rule.id)
        .execute(&state.db)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

/// recategorizeUncategorized: enrich, rules, then the brand fallback over every uncategorized
/// row (no early-out without rules: the knowledge base alone categorizes). Enrichment is saved
/// even on rows that stay uncategorized, as Hibernate flushes every dirty row.
pub async fn recategorize_uncategorized(state: &AppState, member_id: i64) -> AppResult<i32> {
    let kb = state.ledger.knowledge_base(&state.db).await?;
    let ctx = categorize::load_context(&state.db, kb, member_id, state.clock.now()).await?;
    let mut assigned = 0;
    let mut tx = state.db.begin().await?;
    for mut row in repo::uncategorized(&mut *tx, member_id).await? {
        let before = row.clone();
        if categorize::auto_categorize(&mut row, &ctx) {
            assigned += 1;
        }
        if row != before {
            repo::save_categorization(&mut *tx, &row).await?;
        }
    }
    tx.commit().await?;
    Ok(assigned)
}

#[derive(Serialize)]
struct Categorized {
    categorized: i32,
}

async fn recategorize(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Categorized>> {
    Ok(axum::Json(Categorized {
        categorized: recategorize_uncategorized(&state, auth.member_id).await?,
    }))
}

/// Whether the rule matches the row, on the lower-cased counterparty, description and label.
pub fn rule_matches(rule: &CategorizationRule, tx: &Transaction) -> bool {
    let lower = |v: Option<&str>| v.map(str::to_lowercase).unwrap_or_default();
    categorize::matches(
        rule,
        &lower(tx.counterparty()),
        &lower(Some(tx.description())),
        &lower(tx.merchant_label()),
    )
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RulePreviewRequest {
    match_type: Option<RuleMatchType>,
    pattern: Option<String>,
}

impl Validate for RulePreviewRequest {
    fn validate(&self, errors: &mut Errors) {
        errors
            .not_null("matchType", self.match_type.as_ref())
            .not_blank("pattern", self.pattern.as_deref());
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreviewTransaction {
    pub id: i64,
    pub date: String,
    pub label: String,
    #[serde(with = "jdec")]
    pub amount: Decimal,
    pub current_category_name: Option<String>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RulePreviewResult {
    pub match_count: usize,
    pub transactions: Vec<PreviewTransaction>,
}

async fn preview(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<RulePreviewRequest>,
) -> AppResult<axum::Json<RulePreviewResult>> {
    let synthetic = CategorizationRule {
        id: 0,
        member_id: auth.member_id,
        match_type: req.match_type.unwrap_or(RuleMatchType::Keyword),
        pattern: java_trim(req.pattern.as_deref().unwrap_or_default()).to_owned(),
        category_id: 0,
        priority: 0,
        source: RuleSource::User,
        created_at: state.clock.now(),
    };
    let candidates = repo::changeable(&state.db, auth.member_id).await?;
    let matching: Vec<&Transaction> = candidates
        .iter()
        .filter(|tx| rule_matches(&synthetic, tx))
        .collect();
    let ids: Vec<i64> = matching.iter().filter_map(|tx| tx.category_id).collect();
    let categories = repo::categories_by_ids(&state.db, &ids).await?;
    let transactions = matching
        .iter()
        .take(PREVIEW_CAP)
        .map(|tx| PreviewTransaction {
            id: tx.id,
            date: tx.date.to_string(),
            label: tx
                .merchant_label
                .clone()
                .or_else(|| tx.counterparty.clone())
                .unwrap_or_else(|| tx.description.clone()),
            amount: tx.amount,
            current_category_name: tx
                .category_id
                .and_then(|id| categories.get(&id))
                .map(|c: &Category| c.name.clone()),
        })
        .collect();
    Ok(axum::Json(RulePreviewResult {
        match_count: matching.len(),
        transactions,
    }))
}
