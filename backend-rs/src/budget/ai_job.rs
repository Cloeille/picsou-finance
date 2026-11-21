//! AiCategorizationJobService: AI categorization of the member's uncategorized rows as a
//! background job, at most one running per member, in chunks of the configured concurrency,
//! every model call written to ai_call_log.
//!
//! The model itself is track T8's: it plugs in through [AiCategorizer]. Without a provider
//! (the default, [NoAiProvider]) every call abstains with status DISABLED, as Java's
//! NoopCategorizer does, so a job still runs, logs its calls and applies nothing.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Arc, Mutex};

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use rust_decimal::Decimal;
use serde::Serialize;

use super::repo;
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::ledger::categorize::{self, budget_settings_of_member};
use crate::ledger::model::AiCategorizationMode;

/// How many recent categorized rows the model gets as few-shot examples.
const FEW_SHOT_LIMIT: i64 = 8;
/// ai_call_log keeps this many newest rows.
const LOG_RETENTION: i64 = 2000;

#[derive(Debug, Clone, PartialEq)]
pub struct CategorizationInput {
    pub merchant_label: String,
    pub description: String,
    pub amount: Decimal,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CategoryOption {
    pub slug: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Example {
    pub merchant_label: Option<String>,
    pub category_slug: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CategorySuggestion {
    pub category_slug: String,
    /// In [0, 1]; values outside are clamped.
    pub confidence: Decimal,
}

/// TransactionCategorizerPort.CategorizationResult.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct CategorizationResult {
    pub suggestion: Option<CategorySuggestion>,
    pub prompt: Option<String>,
    pub response: Option<String>,
    pub prompt_tokens: Option<i32>,
    pub completion_tokens: Option<i32>,
    pub total_tokens: Option<i32>,
    pub latency_ms: i32,
    pub status: String,
    pub error: Option<String>,
}

impl CategorizationResult {
    pub fn empty(status: &str) -> Self {
        Self {
            status: status.to_owned(),
            ..Self::default()
        }
    }
}

/// The configured AI provider (AiConfigProvider + TransactionCategorizerPort), owned by T8.
#[async_trait::async_trait]
pub trait AiCategorizer: Send + Sync {
    /// AiConfigProvider.maxConcurrency: 1 to 16.
    async fn max_concurrency(&self) -> usize;
    /// The provider's lower-case name and effective model; ("none", None) without a provider.
    async fn provider(&self) -> (String, Option<String>);
    async fn categorize(
        &self,
        input: &CategorizationInput,
        categories: &[CategoryOption],
        examples: &[Example],
    ) -> CategorizationResult;
}

/// No provider configured: every call abstains (NoopCategorizer), four calls at a time (the
/// AI_MAX_CONCURRENCY default).
pub struct NoAiProvider;

#[async_trait::async_trait]
impl AiCategorizer for NoAiProvider {
    async fn max_concurrency(&self) -> usize {
        4
    }

    async fn provider(&self) -> (String, Option<String>) {
        ("none".to_owned(), None)
    }

    async fn categorize(
        &self,
        _: &CategorizationInput,
        _: &[CategoryOption],
        _: &[Example],
    ) -> CategorizationResult {
        CategorizationResult::empty("DISABLED")
    }
}

/// AiJobStatus: a snapshot of the member's job.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AiJobStatus {
    pub running: bool,
    pub total: i32,
    pub processed: i32,
    pub applied: i32,
    pub suggested: i32,
    pub done: bool,
    pub error: Option<String>,
}

fn idle(done: bool) -> AiJobStatus {
    AiJobStatus {
        running: false,
        total: 0,
        processed: 0,
        applied: 0,
        suggested: 0,
        done,
        error: None,
    }
}

pub(crate) struct JobState {
    running: AtomicBool,
    done: AtomicBool,
    error: Mutex<Option<String>>,
    total: i32,
    processed: AtomicI32,
    applied: AtomicI32,
    suggested: AtomicI32,
}

impl JobState {
    fn new(total: i32) -> Self {
        Self {
            running: AtomicBool::new(true),
            done: AtomicBool::new(false),
            error: Mutex::new(None),
            total,
            processed: AtomicI32::new(0),
            applied: AtomicI32::new(0),
            suggested: AtomicI32::new(0),
        }
    }

    fn status(&self) -> AiJobStatus {
        AiJobStatus {
            running: self.running.load(Ordering::SeqCst),
            total: self.total,
            processed: self.processed.load(Ordering::SeqCst),
            applied: self.applied.load(Ordering::SeqCst),
            suggested: self.suggested.load(Ordering::SeqCst),
            done: self.done.load(Ordering::SeqCst),
            error: self.error.lock().unwrap_or_else(|e| e.into_inner()).clone(),
        }
    }
}

/// The latest job of each member.
#[derive(Default)]
pub struct Jobs(Mutex<HashMap<i64, Arc<JobState>>>);

/// CategorizationService.AiContext: what one job run needs, loaded once.
struct AiContext {
    options: Vec<CategoryOption>,
    examples: Vec<Example>,
    category_id_by_slug: HashMap<String, i64>,
    mode: AiCategorizationMode,
    threshold: i32,
}

/// loadAiContext: None when the member has no settings row or AI categorization is off.
async fn load_context(state: &AppState, member_id: i64) -> AppResult<Option<AiContext>> {
    let Some(settings) = budget_settings_of_member(&state.db, member_id).await? else {
        return Ok(None);
    };
    if !settings.ai_categorization_enabled {
        return Ok(None);
    }
    let by_slug = categorize::categories_by_slug(&state.db, member_id, state.clock.now()).await?;
    let options = by_slug
        .iter()
        .map(|(slug, c)| CategoryOption {
            slug: slug.clone(),
            name: c.name.clone(),
        })
        .collect();
    let category_id_by_slug = by_slug
        .iter()
        .map(|(slug, c)| (slug.clone(), c.id))
        .collect();
    let recent = repo::recent_categorized(&state.db, member_id, FEW_SHOT_LIMIT).await?;
    let ids: Vec<i64> = recent.iter().filter_map(|t| t.category_id).collect();
    let categories = repo::categories_by_ids(&state.db, &ids).await?;
    let examples = recent
        .iter()
        .filter_map(|t| {
            let slug = t
                .category_id
                .and_then(|id| categories.get(&id))?
                .slug
                .clone()?;
            Some(Example {
                merchant_label: t.merchant_label.clone(),
                category_slug: slug,
            })
        })
        .collect();
    Ok(Some(AiContext {
        options,
        examples,
        category_id_by_slug,
        mode: settings.ai_mode,
        threshold: settings.ai_confidence_threshold,
    }))
}

/// start: the running job's status, or a new job; done at once when AI is off, the member has
/// no categories or nothing is uncategorized.
pub async fn start(state: &AppState, member_id: i64) -> AppResult<AiJobStatus> {
    if let Some(job) = state.budget.ai_jobs.running(member_id) {
        return Ok(job.status());
    }
    let Some(ctx) = load_context(state, member_id).await? else {
        return Ok(idle(true));
    };
    if ctx.options.is_empty() {
        return Ok(idle(true));
    }
    let ids: Vec<i64> = repo::uncategorized(&state.db, member_id)
        .await?
        .iter()
        .map(|t| t.id)
        .collect();
    if ids.is_empty() {
        return Ok(idle(true));
    }
    let fresh = Arc::new(JobState::new(i32::try_from(ids.len()).unwrap_or(i32::MAX)));
    let winner = state.budget.ai_jobs.claim(member_id, fresh.clone());
    if !Arc::ptr_eq(&winner, &fresh) {
        return Ok(winner.status());
    }
    let status = fresh.status();
    let job_state = state.clone();
    tokio::spawn(async move {
        if let Err(e) = run_job(&job_state, member_id, &ids, &ctx, &fresh).await {
            *fresh.error.lock().unwrap_or_else(|e| e.into_inner()) = Some(e.to_string());
            fresh.done.store(true, Ordering::SeqCst);
        }
        fresh.running.store(false, Ordering::SeqCst);
    });
    Ok(status)
}

impl Jobs {
    fn running(&self, member_id: i64) -> Option<Arc<JobState>> {
        let jobs = self.0.lock().unwrap_or_else(|e| e.into_inner());
        jobs.get(&member_id)
            .filter(|j| j.running.load(Ordering::SeqCst))
            .cloned()
    }

    /// Installs the job unless one is running, and returns whichever is current.
    fn claim(&self, member_id: i64, fresh: Arc<JobState>) -> Arc<JobState> {
        let mut jobs = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(current) = jobs.get(&member_id)
            && current.running.load(Ordering::SeqCst)
        {
            return current.clone();
        }
        jobs.insert(member_id, fresh.clone());
        fresh
    }

    fn get(&self, member_id: i64) -> Option<Arc<JobState>> {
        self.0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&member_id)
            .cloned()
    }
}

pub fn status(state: &AppState, member_id: i64) -> AiJobStatus {
    state
        .budget
        .ai_jobs
        .get(member_id)
        .map_or_else(|| idle(false), |j| j.status())
}

fn confidence_percent(confidence: Decimal) -> i32 {
    let clamped = confidence.clamp(Decimal::ZERO, Decimal::ONE);
    (clamped * Decimal::ONE_HUNDRED)
        .round_dp_with_strategy(0, rust_decimal::RoundingStrategy::MidpointAwayFromZero)
        .try_into()
        .unwrap_or(0)
}

/// applyAiResults: per row, the category when the mode and threshold say so, else a pending
/// suggestion. Unknown slugs and rows categorized meanwhile are skipped. true = applied.
async fn apply_results(
    state: &AppState,
    results: &[(i64, CategorySuggestion)],
    ctx: &AiContext,
    member_id: i64,
) -> AppResult<HashMap<i64, bool>> {
    let mut out = HashMap::new();
    for (id, suggestion) in results {
        let Some(category_id) = ctx
            .category_id_by_slug
            .get(&suggestion.category_slug)
            .copied()
        else {
            continue;
        };
        let pct = confidence_percent(suggestion.confidence);
        let auto = match ctx.mode {
            AiCategorizationMode::AutoAll => true,
            AiCategorizationMode::AutoHighConfidence => pct >= ctx.threshold,
            AiCategorizationMode::Suggest => false,
        };
        let Some(mut tx) =
            crate::ledger::transactions::find_by_id_and_member(&state.db, *id, member_id).await?
        else {
            continue;
        };
        if tx.category_id.is_some() {
            continue;
        }
        if auto {
            if repo::category_of_member(&state.db, category_id, member_id)
                .await?
                .is_none()
            {
                continue;
            }
            tx.category_id = Some(category_id);
            tx.ai_suggested_category_id = None;
            tx.ai_confidence = None;
        } else {
            tx.ai_suggested_category_id = Some(category_id);
            tx.ai_confidence = Some(pct);
        }
        repo::save_categorization(&state.db, &tx).await?;
        out.insert(*id, auto);
    }
    Ok(out)
}

async fn run_job(
    state: &AppState,
    member_id: i64,
    ids: &[i64],
    ctx: &AiContext,
    job: &JobState,
) -> AppResult<()> {
    let ai = state.budget.ai.get();
    let chunk_size = ai.max_concurrency().await.clamp(1, 16);
    let batch = uuid::Uuid::new_v4();
    let (provider, model) = ai.provider().await;
    for chunk in ids.chunks(chunk_size) {
        let rows = repo::member_rows_by_ids(&state.db, chunk, member_id).await?;
        let inputs: HashMap<i64, CategorizationInput> = rows
            .iter()
            .map(|tx| {
                (
                    tx.id,
                    CategorizationInput {
                        merchant_label: tx
                            .merchant_label
                            .clone()
                            .unwrap_or_else(|| tx.description.clone()),
                        description: tx.description.clone(),
                        amount: tx.amount,
                    },
                )
            })
            .collect();
        let calls = chunk
            .iter()
            .filter_map(|id| inputs.get(id).map(|input| (*id, input)))
            .map(|(id, input)| {
                let ai = ai.clone();
                async move { (id, ai.categorize(input, &ctx.options, &ctx.examples).await) }
            });
        let results: HashMap<i64, CategorizationResult> =
            futures::future::join_all(calls).await.into_iter().collect();
        let suggestions: Vec<(i64, CategorySuggestion)> = results
            .iter()
            .filter_map(|(id, r)| r.suggestion.clone().map(|s| (*id, s)))
            .collect();
        let decisions = apply_results(state, &suggestions, ctx, member_id).await?;
        log_calls(
            state,
            member_id,
            batch,
            &provider,
            model.as_deref(),
            chunk,
            &inputs,
            &results,
            &decisions,
        )
        .await?;
        let applied = decisions.values().filter(|a| **a).count();
        let suggested = decisions.len() - applied;
        job.applied
            .fetch_add(i32::try_from(applied).unwrap_or(0), Ordering::SeqCst);
        job.suggested
            .fetch_add(i32::try_from(suggested).unwrap_or(0), Ordering::SeqCst);
        job.processed
            .fetch_add(i32::try_from(chunk.len()).unwrap_or(0), Ordering::SeqCst);
    }
    sqlx::query(
        "DELETE FROM ai_call_log WHERE id NOT IN (SELECT id FROM ai_call_log ORDER BY created_at DESC LIMIT $1)",
    )
    .bind(LOG_RETENTION)
    .execute(&state.db)
    .await?;
    job.done.store(true, Ordering::SeqCst);
    Ok(())
}

#[allow(clippy::too_many_arguments)]
async fn log_calls(
    state: &AppState,
    member_id: i64,
    batch: uuid::Uuid,
    provider: &str,
    model: Option<&str>,
    chunk: &[i64],
    inputs: &HashMap<i64, CategorizationInput>,
    results: &HashMap<i64, CategorizationResult>,
    decisions: &HashMap<i64, bool>,
) -> AppResult<()> {
    let now = state.clock.now();
    let mut tx = state.db.begin().await?;
    for id in chunk {
        let Some(r) = results.get(id) else {
            continue;
        };
        sqlx::query(
            "INSERT INTO ai_call_log (created_at, member_id, transaction_id, merchant_label, batch_id, provider, model, \
             prompt, response, prompt_tokens, completion_tokens, total_tokens, latency_ms, status, error, chosen_slug, \
             confidence, applied) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18)",
        )
        .bind(now)
        .bind(member_id)
        .bind(id)
        .bind(inputs.get(id).map(|i| i.merchant_label.clone()))
        .bind(batch)
        .bind(provider)
        .bind(model)
        .bind(&r.prompt)
        .bind(&r.response)
        .bind(r.prompt_tokens)
        .bind(r.completion_tokens)
        .bind(r.total_tokens)
        .bind(r.latency_ms)
        .bind(&r.status)
        .bind(&r.error)
        .bind(r.suggestion.as_ref().map(|s| s.category_slug.clone()))
        .bind(r.suggestion.as_ref().map(|s| confidence_percent(s.confidence)))
        .bind(decisions.get(id).copied().unwrap_or(false))
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    Ok(())
}

pub async fn start_handler(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<impl IntoResponse> {
    Ok((
        StatusCode::ACCEPTED,
        axum::Json(start(&state, auth.member_id).await?),
    ))
}

pub async fn status_handler(State(state): State<AppState>, auth: Auth) -> axum::Json<AiJobStatus> {
    axum::Json(status(&state, auth.member_id))
}
