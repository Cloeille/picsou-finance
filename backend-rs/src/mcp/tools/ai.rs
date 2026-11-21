//! AiCategorizationTools (2 tools): start the member's AI categorization job and read aggregate
//! counters (never leaking provider error text).

use serde::Serialize;
use serde_json::{Map, Value};

use crate::AppState;
use crate::auth::Auth;
use crate::budget::ai_job::{self, AiJobStatus};
use crate::error::{AppError, AppResult};
use crate::mcp::tools::{ToolSpec, schema_empty, to_json_text};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum JobStateKind {
    Idle,
    Running,
    Completed,
    Failed,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AiCategorizationProgress {
    state: JobStateKind,
    total: i32,
    processed: i32,
    applied: i32,
    suggested: i32,
    unresolved: i32,
}

impl From<AiJobStatus> for AiCategorizationProgress {
    fn from(s: AiJobStatus) -> Self {
        let state = if s.running {
            JobStateKind::Running
        } else if s.done && s.error.is_some() {
            JobStateKind::Failed
        } else if s.done {
            JobStateKind::Completed
        } else {
            JobStateKind::Idle
        };
        let unresolved = (s.processed - s.applied - s.suggested).max(0);
        Self {
            state,
            total: s.total,
            processed: s.processed,
            applied: s.applied,
            suggested: s.suggested,
            unresolved,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AiCategorizationStart {
    started: bool,
    job: AiCategorizationProgress,
}

pub fn specs() -> Vec<ToolSpec> {
    vec![
        ToolSpec {
            name: "start_ai_categorization",
            description: "Start the same background job as the inbox 'Categorize with AI' button over the authenticated member's uncategorized transactions, using the instance's configured provider, mode, threshold and concurrency. Rules are applied when transactions are imported; only transactions still without a category are sent, so transactions with a pending suggestion are sent again on every run. Never starts a second job while one runs (started=false, returns the running job). Returns immediately; while state is RUNNING, poll get_ai_categorization_status until COMPLETED or FAILED. started=false with state COMPLETED and total 0 means there was nothing to do (AI categorization disabled for the member, no categories, or nothing uncategorized): no job exists, do not poll. started is advisory; the running job is the source of truth.",
            scope: "budget:ai-write",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_ai_categorization_status",
            description: "Read the progress of the authenticated member's AI categorization job: state (IDLE, RUNNING, COMPLETED, FAILED) and aggregate counters only (total, processed, applied, suggested, unresolved). unresolved counts processed transactions that got neither a category nor a suggestion: the model gave no usable answer, or the transaction or its category changed while the job ran. No transaction labels, amounts, prompts, model responses or provider error text. COMPLETED or FAILED describes the last job until the next one starts.",
            scope: "budget:ai-write",
            input_schema: schema_empty(),
        },
    ]
}

pub async fn call(
    state: &AppState,
    auth: &Auth,
    name: &str,
    _args: &Map<String, Value>,
) -> AppResult<String> {
    match name {
        "start_ai_categorization" => {
            let already_running = ai_job::status(state, auth.member_id).running;
            let status = ai_job::start(state, auth.member_id).await?;
            let started = !already_running && status.running;
            to_json_text(&AiCategorizationStart {
                started,
                job: AiCategorizationProgress::from(status),
            })
        }
        "get_ai_categorization_status" => {
            let status = ai_job::status(state, auth.member_id);
            to_json_text(&AiCategorizationProgress::from(status))
        }
        other => Err(AppError::BadRequest(format!("Unknown AI tool: {other}"))),
    }
}
