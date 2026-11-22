//! TransactionCategorizationController: the cross-account transaction search, the "to
//! categorize" inbox, manual categorization (optionally learning a rule and applying it to
//! matching rows) and the AI categorization job.

use std::collections::HashMap;

use axum::extract::State;
use axum::http::StatusCode;
use serde::Deserialize;

use super::ai_job;
use super::categories::require;
use super::params::Params;
use super::repo::{self, Refs};
use super::rules::{NewRule, insert_rule, rule_matches};
use crate::AppState;
use crate::auth::Auth;
use crate::db::{AsDbHandle, DbFromRow, DbHandle};
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Json, Path, Validate};
use crate::ledger::manual_transactions::{PeerLoanLink, PeerLoanLinkKind, TransactionResponse};
use crate::ledger::model::{CategorizationRule, RuleMatchType, RuleSource};
use crate::ledger::sync_values::{java_is_blank, java_trim};
use crate::ledger::transactions;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/transactions", search)
        .get("/api/transactions/uncategorized", uncategorized)
        .put("/api/transactions/{id}/category", categorize)
        .post("/api/transactions/categorize-ai", ai_job::start_handler)
        .get(
            "/api/transactions/categorize-ai/status",
            ai_job::status_handler,
        );
}

#[derive(DbFromRow)]
struct LinkRow {
    transaction_id: i64,
    account_id: i64,
    account_name: String,
}

/// PeerLoanTransactionLinks.linksFor: the lending outflow that funded a loan, or a repayment
/// linked from an existing row; the lending link wins when a row is both.
async fn peer_loan_links(state: &AppState, ids: &[i64]) -> AppResult<HashMap<i64, PeerLoanLink>> {
    let mut links = HashMap::new();
    if ids.is_empty() {
        return Ok(links);
    }
    let lending: Vec<LinkRow> = crate::db::query_as::<LinkRow>(
        "SELECT t.lending_transaction_id AS transaction_id, a.id AS account_id, a.name AS account_name \
         FROM peer_loan_terms t JOIN account a ON a.id = t.account_id AND a.deleted_at IS NULL \
         WHERE t.lending_transaction_id = ANY($1) ORDER BY t.id",
    )
    .bind(ids)
    .fetch_all(&state.db)
    .await?;
    let repayments: Vec<LinkRow> = crate::db::query_as::<LinkRow>(
        "SELECT r.cash_transaction_id AS transaction_id, a.id AS account_id, a.name AS account_name \
         FROM peer_loan_repayment r JOIN account a ON a.id = r.account_id AND a.deleted_at IS NULL \
         WHERE r.cash_transaction_id = ANY($1) ORDER BY r.id",
    )
    .bind(ids)
    .fetch_all(&state.db)
    .await?;
    let tagged = lending
        .into_iter()
        .map(|r| (r, PeerLoanLinkKind::Lending))
        .chain(
            repayments
                .into_iter()
                .map(|r| (r, PeerLoanLinkKind::Repayment)),
        );
    for (row, kind) in tagged {
        links.entry(row.transaction_id).or_insert(PeerLoanLink {
            kind,
            account_id: Some(row.account_id),
            account_name: Some(row.account_name),
        });
    }
    Ok(links)
}

async fn search(
    State(state): State<AppState>,
    auth: Auth,
    params: Params,
) -> AppResult<axum::Json<Vec<TransactionResponse>>> {
    let from = params.required_date("from")?;
    let to = params.required_date("to")?;
    let account_id = params.optional::<i64>("accountId")?;
    let category_id = params.optional::<i64>("categoryId")?;
    let rows = repo::search(&state.db, auth.member_id, from, to, account_id, category_id).await?;
    let refs = Refs::load(&state.db, &rows).await?;
    let ids: Vec<i64> = rows.iter().map(|t| t.id).collect();
    let mut links = peer_loan_links(&state, &ids).await?;
    Ok(axum::Json(
        rows.iter()
            .map(|tx| {
                let mut response = refs.response(tx);
                response.peer_loan_link = links.remove(&tx.id);
                response
            })
            .collect(),
    ))
}

pub async fn uncategorized(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Vec<TransactionResponse>>> {
    let rows = repo::uncategorized(&state.db, auth.member_id).await?;
    let refs = Refs::load(&state.db, &rows).await?;
    Ok(axum::Json(
        rows.iter().map(|tx| refs.response(tx)).collect(),
    ))
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CategorizeRequest {
    category_id: Option<i64>,
    #[serde(default, deserialize_with = "crate::http::json::null_as_false")]
    create_rule: bool,
    rule_pattern: Option<String>,
    rule_match_type: Option<RuleMatchType>,
    apply_to_transaction_ids: Option<Vec<Option<i64>>>,
}

impl Validate for CategorizeRequest {
    fn validate(&self, errors: &mut Errors) {
        errors.not_null("categoryId", self.category_id.as_ref());
    }
}

fn transaction_not_found() -> AppError {
    AppError::NotFound("Transaction not found".into())
}

/// learnRuleInternal: the member's rule for (matchType, pattern), compared without case, or a
/// new AUTO rule. An existing rule keeps its own category.
async fn learn_rule(
    conn: &mut DbHandle<'_>,
    state: &AppState,
    pattern: &str,
    match_type: RuleMatchType,
    category_id: i64,
    member_id: i64,
) -> AppResult<CategorizationRule> {
    let existing: Option<CategorizationRule> = crate::db::query_as::<CategorizationRule>(
        "SELECT id, member_id, match_type, pattern, category_id, priority, source, created_at \
         FROM categorization_rule WHERE member_id = $1 AND match_type = $2 AND upper(pattern) = upper($3) LIMIT 1",
    )
    .bind(member_id)
    .bind(match_type)
    .bind(pattern)
    .fetch_optional(&mut *conn)
    .await?;
    if let Some(rule) = existing {
        return Ok(rule);
    }
    let category = require(&mut *conn, category_id, member_id).await?;
    let new = NewRule {
        member_id,
        match_type,
        pattern,
        category_id: category.id,
        priority: 0,
        source: RuleSource::Auto,
    };
    Ok(insert_rule(&mut *conn, &new, state.clock.now()).await?)
}

/// retroApply: the rule's category on matching rows that were not categorized by hand. Without
/// explicit ids only uncategorized rows are considered; explicit ids may be re-categorized.
async fn retro_apply(
    conn: &mut DbHandle<'_>,
    rule: &CategorizationRule,
    explicit_ids: &[i64],
    member_id: i64,
) -> AppResult<()> {
    let cherry_pick = !explicit_ids.is_empty();
    let candidates = if cherry_pick {
        repo::member_rows_by_ids(&mut *conn, explicit_ids, member_id).await?
    } else {
        repo::uncategorized(&mut *conn, member_id).await?
    };
    for mut tx in candidates {
        if tx.category_manual || (tx.category_id.is_some() && !cherry_pick) {
            continue;
        }
        if rule_matches(rule, &tx) {
            tx.category_id = Some(rule.category_id);
            repo::save_categorization(&mut *conn, &tx).await?;
        }
    }
    Ok(())
}

pub async fn categorize(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    Json(req): Json<CategorizeRequest>,
) -> AppResult<StatusCode> {
    let member_id = auth.member_id;
    let mut conn = state.db.begin().await?;
    let mut tx = transactions::find_by_id_and_member(&mut conn, id, member_id)
        .await?
        .ok_or_else(transaction_not_found)?;
    let category = require(&mut conn, req.category_id.unwrap_or_default(), member_id).await?;
    tx.category_id = Some(category.id);
    tx.category_manual = true;
    repo::save_categorization(&mut conn, &tx).await?;
    if req.create_rule {
        let explicit = req
            .rule_pattern
            .as_deref()
            .filter(|p| !java_is_blank(p))
            .zip(req.rule_match_type);
        let learned = match explicit {
            Some((pattern, match_type)) => Some((java_trim(pattern).to_owned(), match_type)),
            None => tx
                .counterparty
                .as_deref()
                .filter(|c| !java_is_blank(c))
                .map(|c| (java_trim(c).to_owned(), RuleMatchType::Counterparty)),
        };
        if let Some((pattern, match_type)) = learned {
            let mut handle = conn.as_handle();
            let rule = learn_rule(
                &mut handle,
                &state,
                &pattern,
                match_type,
                category.id,
                member_id,
            )
            .await?;
            let ids: Vec<i64> = req
                .apply_to_transaction_ids
                .iter()
                .flatten()
                .flatten()
                .copied()
                .collect();
            retro_apply(&mut handle, &rule, &ids, member_id).await?;
        }
    }
    conn.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}
