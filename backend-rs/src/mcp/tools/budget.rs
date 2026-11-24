//! BudgetTools (36 tools): categories, categorization rules, budgeted transactions, recurring
//! series, spending envelopes, and budget dashboard/spending/cashflow reads.

use axum::extract::State;
use serde::Serialize;
use serde_json::{Map, Value, json};

use crate::AppState;
use crate::auth::Auth;
use crate::budget::cashflow::{self, CashflowResponse};
use crate::budget::categories::{self, CategoryRequest};
use crate::budget::categorization::{self, CategorizeRequest};
use crate::budget::cycle::CashflowPeriod;
use crate::budget::detection;
use crate::budget::envelopes::{self, BudgetRequest};
use crate::budget::flow::{self, CategorySpend};
use crate::budget::recurring::{
    self, RecurringOccurrenceResponse, RecurringSeriesRequest, RecurringStatus,
};
use crate::budget::repo::{self, Refs};
use crate::budget::rules::{self, CategorizationRuleRequest, RulePreviewRequest};
use crate::error::{AppError, AppResult};
use crate::http::json::{Json, Path};
use crate::ledger::model::{CategoryKind, RecurringCadence, RuleMatchType};
use crate::mcp::tools::{
    ToolSpec, opt_bool, opt_date, opt_enum, opt_i32, opt_i64, opt_period, opt_str, prop_bool,
    prop_date, prop_enum, prop_int, prop_num, prop_str, req_date, req_decimal, req_enum, req_i64,
    req_str, schema_empty, schema_obj, to_json_text, validate,
};

const CATEGORY_KINDS: &[&str] = &["INCOME", "EXPENSE", "TRANSFER"];
const MATCH_TYPES: &[&str] = &["COUNTERPARTY", "KEYWORD", "KEYWORDS_ALL", "KEYWORDS_ANY"];
const CADENCES: &[&str] = &["WEEKLY", "BIWEEKLY", "MONTHLY", "QUARTERLY", "YEARLY"];
const RECURRING_STATUSES: &[&str] = &["SUGGESTED", "CONFIRMED", "IGNORED"];
const PERIODS: &[&str] = &["CYCLE", "YTD"];

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct BudgetDashboard {
    cashflow: CashflowResponse,
    top_categories: Vec<CategorySpend>,
    upcoming_subscriptions: Vec<RecurringOccurrenceResponse>,
}

pub fn specs() -> Vec<ToolSpec> {
    vec![
        // ── Categories (5) ──────────────────────────────────────────────────
        ToolSpec {
            name: "list_budget_categories",
            description: "List the authenticated member's managed budget categories (including seeded defaults), tree-shaped via parentId.",
            scope: "budget:categories-read",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_budget_category",
            description: "Get a single budget category of the authenticated member by its id.",
            scope: "budget:categories-read",
            input_schema: schema_obj(vec![("categoryId", prop_int("The category id"), true)]),
        },
        ToolSpec {
            name: "create_budget_category",
            description: "Create a budget category for the authenticated member. kind is INCOME, EXPENSE or TRANSFER. parentId (optional) nests it one level under an existing root category of the same kind.",
            scope: "budget:categories-write",
            input_schema: schema_obj(vec![
                ("name", prop_str("Category name"), true),
                (
                    "kind",
                    prop_enum("INCOME, EXPENSE or TRANSFER", CATEGORY_KINDS),
                    true,
                ),
                ("color", prop_str("Optional hex colour like #1a2b3c"), false),
                ("icon", prop_str("Optional lucide-react icon name"), false),
                (
                    "parentId",
                    prop_int("Optional parent category id for a sub-category"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "update_budget_category",
            description: "Update an existing budget category of the authenticated member.",
            scope: "budget:categories-write",
            input_schema: schema_obj(vec![
                ("categoryId", prop_int("The category id"), true),
                ("name", prop_str("Category name"), true),
                (
                    "kind",
                    prop_enum("INCOME, EXPENSE or TRANSFER", CATEGORY_KINDS),
                    true,
                ),
                ("color", prop_str("Optional hex colour like #1a2b3c"), false),
                ("icon", prop_str("Optional lucide-react icon name"), false),
                (
                    "parentId",
                    prop_int("Optional parent category id for a sub-category"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "delete_budget_category",
            description: "Archive (soft-delete) a budget category of the authenticated member; archiving a parent cascades to its sub-categories.",
            scope: "budget:categories-write",
            input_schema: schema_obj(vec![("categoryId", prop_int("The category id"), true)]),
        },
        // ── Rules (7) ───────────────────────────────────────────────────────
        ToolSpec {
            name: "list_budget_rules",
            description: "List the authenticated member's categorization rules (USER and learned AUTO ones), highest priority first.",
            scope: "budget:rules-read",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_budget_rule",
            description: "Get a single categorization rule of the authenticated member by its id.",
            scope: "budget:rules-read",
            input_schema: schema_obj(vec![("ruleId", prop_int("The rule id"), true)]),
        },
        ToolSpec {
            name: "create_budget_rule",
            description: "Create a categorization rule for the authenticated member. matchType is COUNTERPARTY, KEYWORD, KEYWORDS_ALL or KEYWORDS_ANY; higher priority wins on conflict.",
            scope: "budget:rules-write",
            input_schema: schema_obj(vec![
                (
                    "matchType",
                    prop_enum(
                        "COUNTERPARTY, KEYWORD, KEYWORDS_ALL or KEYWORDS_ANY",
                        MATCH_TYPES,
                    ),
                    true,
                ),
                (
                    "pattern",
                    prop_str(
                        "The pattern to match against counterparty/description/merchant label",
                    ),
                    true,
                ),
                ("categoryId", prop_int("Target category id"), true),
                (
                    "priority",
                    prop_int("Priority; higher wins on conflict, defaults to 0"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "update_budget_rule",
            description: "Update an existing categorization rule of the authenticated member.",
            scope: "budget:rules-write",
            input_schema: schema_obj(vec![
                ("ruleId", prop_int("The rule id"), true),
                (
                    "matchType",
                    prop_enum(
                        "COUNTERPARTY, KEYWORD, KEYWORDS_ALL or KEYWORDS_ANY",
                        MATCH_TYPES,
                    ),
                    true,
                ),
                (
                    "pattern",
                    prop_str(
                        "The pattern to match against counterparty/description/merchant label",
                    ),
                    true,
                ),
                ("categoryId", prop_int("Target category id"), true),
                (
                    "priority",
                    prop_int("Priority; higher wins on conflict"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "delete_budget_rule",
            description: "Delete a categorization rule of the authenticated member.",
            scope: "budget:rules-write",
            input_schema: schema_obj(vec![("ruleId", prop_int("The rule id"), true)]),
        },
        ToolSpec {
            name: "apply_rule_to_transactions",
            description: "Re-run the authenticated member's full categorization pipeline (rules, then the offline merchant knowledge base) over every still-uncategorized transaction. Never overrides an existing category. Returns how many transactions were assigned a category.",
            scope: "budget:rules-write",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "preview_budget_rule",
            description: "Dry run of a categorization rule before create_budget_rule: returns how many of the authenticated member's transactions it would categorize (uncategorized ones or ones not categorized by hand) and up to 200 of them. Requires budget:transactions-read because any pattern is a search over transaction counterparty, description and merchant label. Writes nothing: no rule is saved and no transaction changes.",
            scope: "budget:transactions-read",
            input_schema: schema_obj(vec![
                (
                    "matchType",
                    prop_enum(
                        "COUNTERPARTY, KEYWORD, KEYWORDS_ALL or KEYWORDS_ANY",
                        MATCH_TYPES,
                    ),
                    true,
                ),
                (
                    "pattern",
                    prop_str(
                        "The pattern to match against counterparty/description/merchant label",
                    ),
                    true,
                ),
            ]),
        },
        // ── Transactions (2) ────────────────────────────────────────────────
        ToolSpec {
            name: "list_budget_transactions",
            description: "List the authenticated member's transactions in a date range, optionally filtered by category (omit for all categories) or restricted to only-uncategorized ones. Includes merchant label/brand and the current category.",
            scope: "budget:transactions-read",
            input_schema: schema_obj(vec![
                (
                    "from",
                    prop_date("Earliest date (inclusive), ISO yyyy-MM-dd"),
                    true,
                ),
                (
                    "to",
                    prop_date("Latest date (inclusive), ISO yyyy-MM-dd"),
                    true,
                ),
                (
                    "categoryId",
                    prop_int("Restrict to this category id; omit for every category"),
                    false,
                ),
                (
                    "uncategorizedOnly",
                    prop_bool(
                        "When true, ignore categoryId and return only uncategorized transactions",
                    ),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "update_budget_transaction",
            description: "Set or change a transaction's category. Optionally learn a rule from this assignment (createRule) and retro-apply it to the member's other matching uncategorized transactions.",
            scope: "budget:transactions-write",
            input_schema: schema_obj(vec![
                ("transactionId", prop_int("The transaction id"), true),
                ("categoryId", prop_int("The category id to assign"), true),
                (
                    "createRule",
                    prop_bool("Whether to learn a rule from this assignment"),
                    false,
                ),
            ]),
        },
        // ── Recurring (11) ──────────────────────────────────────────────────
        ToolSpec {
            name: "list_recurring_series",
            description: "List the authenticated member's recurring series (subscriptions/direct debits/salaries), optionally filtered by status (SUGGESTED, CONFIRMED, IGNORED).",
            scope: "budget:recurring-read",
            input_schema: schema_obj(vec![(
                "status",
                prop_enum(
                    "SUGGESTED, CONFIRMED or IGNORED; omit for all",
                    RECURRING_STATUSES,
                ),
                false,
            )]),
        },
        ToolSpec {
            name: "get_recurring_series",
            description: "Get a single recurring series of the authenticated member by its id, with detection confidence and runtime status.",
            scope: "budget:recurring-read",
            input_schema: schema_obj(vec![(
                "seriesId",
                prop_int("The recurring series id"),
                true,
            )]),
        },
        ToolSpec {
            name: "get_recurring_activity",
            description: "The authenticated member's recurring 'what changed' feed, newest first: series the detector silently auto-confirmed or whose price stepped within the last 60 days. Each entry can be reversed with undo_recurring_series_change.",
            scope: "budget:recurring-read",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_recurring_calendar",
            description: "Projected charges of the authenticated member's confirmed recurring series from today through the next horizonDays days, soonest first. Credit-card payments due (card account name, amount due, reward points) are included only when the key also holds accounts:read.",
            scope: "budget:recurring-read",
            input_schema: schema_obj(vec![(
                "horizonDays",
                prop_int("How many days ahead to project; defaults to 60"),
                false,
            )]),
        },
        ToolSpec {
            name: "confirm_recurring_series",
            description: "Mark one of the authenticated member's recurring series as CONFIRMED, so it appears in the calendar and upcoming subscriptions. Idempotent. Returns the updated series.",
            scope: "budget:recurring-write",
            input_schema: schema_obj(vec![(
                "seriesId",
                prop_int("The recurring series id"),
                true,
            )]),
        },
        ToolSpec {
            name: "ignore_recurring_series",
            description: "Mark one of the authenticated member's recurring series as IGNORED. The series is kept, not deleted, so detection never suggests it again. Idempotent; confirm_recurring_series reverses it. Returns the updated series.",
            scope: "budget:recurring-write",
            input_schema: schema_obj(vec![(
                "seriesId",
                prop_int("The recurring series id"),
                true,
            )]),
        },
        ToolSpec {
            name: "undo_recurring_series_change",
            description: "Reverse a get_recurring_activity entry. A price change is acknowledged: the new amount stays and the alert is cleared. A silent auto-confirm is rejected: the series becomes IGNORED so detection will not re-confirm it. A series with neither is returned unchanged. Returns the updated series.",
            scope: "budget:recurring-write",
            input_schema: schema_obj(vec![(
                "seriesId",
                prop_int("The recurring series id"),
                true,
            )]),
        },
        ToolSpec {
            name: "create_recurring_series",
            description: "Declare a recurring series the detector missed for the authenticated member. It starts CONFIRMED. expectedAmount is signed like a transaction: negative for a debit, positive for income. Returns the created series.",
            scope: "budget:recurring-write",
            input_schema: schema_obj(vec![
                (
                    "label",
                    prop_str("Display label, at most 255 characters"),
                    true,
                ),
                (
                    "expectedAmount",
                    prop_num("Signed expected amount, negative for a debit"),
                    true,
                ),
                (
                    "cadence",
                    prop_enum("WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY or YEARLY", CADENCES),
                    true,
                ),
                (
                    "counterparty",
                    prop_str("Optional counterparty, at most 255 characters"),
                    false,
                ),
                (
                    "nextDueDate",
                    prop_date("Optional next due date, ISO yyyy-MM-dd"),
                    false,
                ),
                (
                    "categoryId",
                    prop_int("Optional category id of the member"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "update_recurring_series",
            description: "Replace the label, counterparty, amount, cadence, next due date and category of one of the authenticated member's recurring series. Omitted optional fields are cleared. Status is unchanged. Returns the updated series.",
            scope: "budget:recurring-write",
            input_schema: schema_obj(vec![
                ("seriesId", prop_int("The recurring series id"), true),
                (
                    "label",
                    prop_str("Display label, at most 255 characters"),
                    true,
                ),
                (
                    "expectedAmount",
                    prop_num("Signed expected amount, negative for a debit"),
                    true,
                ),
                (
                    "cadence",
                    prop_enum("WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY or YEARLY", CADENCES),
                    true,
                ),
                (
                    "counterparty",
                    prop_str("Optional counterparty, at most 255 characters"),
                    false,
                ),
                (
                    "nextDueDate",
                    prop_date("Optional next due date, ISO yyyy-MM-dd"),
                    false,
                ),
                (
                    "categoryId",
                    prop_int("Optional category id of the member"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "delete_recurring_series",
            description: "Permanently delete one of the authenticated member's recurring series. Detection may suggest it again from future transactions; use ignore_recurring_series to dismiss it for good.",
            scope: "budget:recurring-write",
            input_schema: schema_obj(vec![(
                "seriesId",
                prop_int("The recurring series id"),
                true,
            )]),
        },
        ToolSpec {
            name: "detect_recurring_series",
            description: "Re-run recurring detection over the authenticated member's transactions of the last 400 days. Creates new SUGGESTED series, refreshes amounts and due dates of existing ones, and may auto-confirm a high-confidence suggestion. Never changes a CONFIRMED or IGNORED decision. Returns how many series were created or refreshed.",
            scope: "budget:recurring-write",
            input_schema: schema_empty(),
        },
        // ── Envelopes (6) ───────────────────────────────────────────────────
        ToolSpec {
            name: "list_budget_envelopes",
            description: "List the authenticated member's budget envelopes with current-cycle progress; a parent envelope's spent rolls up its subtree.",
            scope: "budget:envelopes-read",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_budget_envelope",
            description: "Get a single budget envelope of the authenticated member by its id.",
            scope: "budget:envelopes-read",
            input_schema: schema_obj(vec![(
                "envelopeId",
                prop_int("The envelope (budget) id"),
                true,
            )]),
        },
        ToolSpec {
            name: "create_budget_envelope",
            description: "Create a monthly budget envelope on one of the authenticated member's categories. A parent and its children can never both hold an envelope (double-counting guard).",
            scope: "budget:envelopes-write",
            input_schema: schema_obj(vec![
                (
                    "categoryId",
                    prop_int("The category id this envelope caps"),
                    true,
                ),
                ("monthlyLimit", prop_num("Monthly limit amount"), true),
            ]),
        },
        ToolSpec {
            name: "update_budget_envelope",
            description: "Update an existing budget envelope's category or monthly limit.",
            scope: "budget:envelopes-write",
            input_schema: schema_obj(vec![
                ("envelopeId", prop_int("The envelope (budget) id"), true),
                (
                    "categoryId",
                    prop_int("The category id this envelope caps"),
                    true,
                ),
                ("monthlyLimit", prop_num("Monthly limit amount"), true),
            ]),
        },
        ToolSpec {
            name: "delete_budget_envelope",
            description: "Delete a budget envelope of the authenticated member.",
            scope: "budget:envelopes-write",
            input_schema: schema_obj(vec![(
                "envelopeId",
                prop_int("The envelope (budget) id"),
                true,
            )]),
        },
        ToolSpec {
            name: "set_envelope_allocation",
            description: "Change a budget envelope's monthly money limit, keeping its category unchanged. The Budget model supports a money limit only, not a percentage allocation.",
            scope: "budget:envelopes-write",
            input_schema: schema_obj(vec![
                ("envelopeId", prop_int("The envelope (budget) id"), true),
                ("monthlyLimit", prop_num("New monthly limit amount"), true),
            ]),
        },
        // ── Dashboard & Cashflow (5) ────────────────────────────────────────
        ToolSpec {
            name: "get_budget_dashboard",
            description: "Get the authenticated member's budget overview for the current pay cycle: income/expense/net cashflow, the top spending categories, and subscriptions due in the next 30 days — the same data as the Overview page.",
            scope: "budget:dashboard-read",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_spending_by_category",
            description: "The authenticated member's expenses ranked by category for a period: CYCLE (the pay cycle containing anchor) or YTD (Jan 1 of anchor's year to anchor).",
            scope: "budget:dashboard-read",
            input_schema: schema_obj(vec![
                (
                    "period",
                    prop_enum("CYCLE or YTD; defaults to CYCLE", PERIODS),
                    false,
                ),
                (
                    "anchor",
                    prop_date("Reference date, ISO yyyy-MM-dd; defaults to today"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "get_spending_category_detail",
            description: "Drill into one of the authenticated member's categories for a period: total, per-sub-category split and the transactions of the category and its sub-categories. Requires budget:transactions-read because it returns transaction rows, not only aggregates.",
            scope: "budget:transactions-read",
            input_schema: schema_obj(vec![
                ("categoryId", prop_int("The category id"), true),
                (
                    "period",
                    prop_enum("CYCLE or YTD; defaults to CYCLE", PERIODS),
                    false,
                ),
                (
                    "anchor",
                    prop_date("Reference date, ISO yyyy-MM-dd; defaults to today"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "get_cashflow",
            description: "The authenticated member's income, expenses and net for a period: CYCLE (the pay cycle containing anchor) or YTD (bucketed by pay cycle).",
            scope: "budget:dashboard-read",
            input_schema: schema_obj(vec![
                (
                    "period",
                    prop_enum("CYCLE or YTD; defaults to CYCLE", PERIODS),
                    false,
                ),
                (
                    "anchor",
                    prop_date("Reference date, ISO yyyy-MM-dd; defaults to today"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "get_cashflow_flow",
            description: "The authenticated member's income → budget → spending money-flow graph (Sankey nodes and links) for a period: CYCLE or YTD. Sources: income categories, WITHDRAWAL (net taken out of a savings/investment account), TRANSFER_IN (transfers from accounts not linked in Picsou) and a SHORTFALL for any remaining gap. Sinks: expense categories, one SAVINGS node per savings/investment account with a net inflow (assetClass SAVINGS or INVESTMENT; savings are counted net per account), TRANSFER_OUT (transfers to accounts not linked in Picsou) and UNSPENT for income left over.",
            scope: "budget:dashboard-read",
            input_schema: schema_obj(vec![
                (
                    "period",
                    prop_enum("CYCLE or YTD; defaults to CYCLE", PERIODS),
                    false,
                ),
                (
                    "anchor",
                    prop_date("Reference date, ISO yyyy-MM-dd; defaults to today"),
                    false,
                ),
            ]),
        },
    ]
}

pub async fn call(
    state: &AppState,
    auth: &Auth,
    name: &str,
    args: &Map<String, Value>,
) -> AppResult<String> {
    match name {
        // ── Categories ──────────────────────────────────────────────────────
        "list_budget_categories" => {
            let axum::Json(list) = categories::find_all(State(state.clone()), auth.clone()).await?;
            to_json_text(&list)
        }
        "get_budget_category" => {
            let category_id = req_i64(args, "categoryId")?;
            let axum::Json(list) = categories::find_all(State(state.clone()), auth.clone()).await?;
            let found = list
                .into_iter()
                .find(|c| c.id == category_id)
                .ok_or_else(categories::category_not_found)?;
            to_json_text(&found)
        }
        "create_budget_category" => {
            let req = parse_category_request(args)?;
            validate(&req)?;
            let (_, axum::Json(created)) =
                categories::create(State(state.clone()), auth.clone(), Json(req)).await?;
            to_json_text(&created)
        }
        "update_budget_category" => {
            let category_id = req_i64(args, "categoryId")?;
            let req = parse_category_request(args)?;
            validate(&req)?;
            let axum::Json(updated) = categories::update(
                State(state.clone()),
                auth.clone(),
                Path(category_id),
                Json(req),
            )
            .await?;
            to_json_text(&updated)
        }
        "delete_budget_category" => {
            let category_id = req_i64(args, "categoryId")?;
            categories::archive(State(state.clone()), auth.clone(), Path(category_id)).await?;
            Ok(format!("Archived category {category_id}"))
        }
        // ── Rules ───────────────────────────────────────────────────────────
        "list_budget_rules" => {
            let axum::Json(list) = rules::find_all(State(state.clone()), auth.clone()).await?;
            to_json_text(&list)
        }
        "get_budget_rule" => {
            let rule_id = req_i64(args, "ruleId")?;
            let axum::Json(list) = rules::find_all(State(state.clone()), auth.clone()).await?;
            let found = list
                .into_iter()
                .find(|r| r.id == rule_id)
                .ok_or_else(|| AppError::NotFound("Categorization rule not found".to_owned()))?;
            to_json_text(&found)
        }
        "create_budget_rule" => {
            let req = parse_rule_request(args)?;
            validate(&req)?;
            let (_, axum::Json(created)) =
                rules::create(State(state.clone()), auth.clone(), Json(req)).await?;
            to_json_text(&created)
        }
        "update_budget_rule" => {
            let rule_id = req_i64(args, "ruleId")?;
            let req = parse_rule_request(args)?;
            validate(&req)?;
            let axum::Json(updated) =
                rules::update(State(state.clone()), auth.clone(), Path(rule_id), Json(req)).await?;
            to_json_text(&updated)
        }
        "delete_budget_rule" => {
            let rule_id = req_i64(args, "ruleId")?;
            rules::delete(State(state.clone()), auth.clone(), Path(rule_id)).await?;
            Ok(format!("Deleted rule {rule_id}"))
        }
        "apply_rule_to_transactions" => {
            let assigned = rules::recategorize_uncategorized(state, auth.member_id).await?;
            Ok(format!("Categorized {assigned} transaction(s)"))
        }
        "preview_budget_rule" => {
            let match_type: RuleMatchType = req_enum(args, "matchType")?;
            let pattern = req_str(args, "pattern")?;
            let req: RulePreviewRequest = serde_json::from_value(json!({
                "matchType": match_type,
                "pattern": pattern,
            }))
            .map_err(|e| AppError::BadRequest(e.to_string()))?;
            validate(&req)?;
            let axum::Json(result) =
                rules::preview(State(state.clone()), auth.clone(), Json(req)).await?;
            to_json_text(&result)
        }
        // ── Transactions ────────────────────────────────────────────────────
        "list_budget_transactions" => {
            let from = req_date(args, "from")?;
            let to = req_date(args, "to")?;
            let category_id = opt_i64(args, "categoryId")?;
            let uncategorized_only = opt_bool(args, "uncategorizedOnly")?.unwrap_or(false);
            if uncategorized_only {
                let axum::Json(rows) =
                    categorization::uncategorized(State(state.clone()), auth.clone()).await?;
                let filtered: Vec<_> = rows
                    .into_iter()
                    .filter(|t| t.date >= from && t.date <= to)
                    .collect();
                to_json_text(&filtered)
            } else {
                let rows =
                    repo::search(&state.db, auth.member_id, from, to, None, category_id).await?;
                let refs = Refs::load(&state.db, &rows).await?;
                let out: Vec<_> = rows.iter().filter_map(|tx| refs.response(tx)).collect();
                to_json_text(&out)
            }
        }
        "update_budget_transaction" => {
            let tx_id = req_i64(args, "transactionId")?;
            let category_id = req_i64(args, "categoryId")?;
            let create_rule = opt_bool(args, "createRule")?.unwrap_or(false);
            let req: CategorizeRequest = serde_json::from_value(json!({
                "categoryId": category_id,
                "createRule": create_rule,
            }))
            .map_err(|e| AppError::BadRequest(e.to_string()))?;
            validate(&req)?;
            categorization::categorize(State(state.clone()), auth.clone(), Path(tx_id), Json(req))
                .await?;
            Ok(format!("Categorized transaction {tx_id}"))
        }
        // ── Recurring ───────────────────────────────────────────────────────
        "list_recurring_series" => {
            let status: Option<RecurringStatus> = opt_enum(args, "status")?;
            let list = recurring::list_for(state, auth.member_id, status).await?;
            to_json_text(&list)
        }
        "get_recurring_series" => {
            let series_id = req_i64(args, "seriesId")?;
            let list = recurring::list_for(state, auth.member_id, None).await?;
            let found = list
                .into_iter()
                .find(|s| s.id == series_id)
                .ok_or_else(|| AppError::NotFound("Recurring series not found".to_owned()))?;
            to_json_text(&found)
        }
        "get_recurring_activity" => {
            let axum::Json(feed) = recurring::activity(State(state.clone()), auth.clone()).await?;
            to_json_text(&feed)
        }
        "get_recurring_calendar" => {
            let horizon = opt_i32(args, "horizonDays")?.unwrap_or(60);
            let upcoming =
                recurring::upcoming(state, auth.member_id, state.clock.today(), horizon).await?;
            let filtered = if auth.has_scope("accounts:read") {
                upcoming
            } else {
                upcoming
                    .into_iter()
                    .filter(|o| !o.credit_card_payment)
                    .collect()
            };
            to_json_text(&filtered)
        }
        "confirm_recurring_series" => {
            let series_id = req_i64(args, "seriesId")?;
            let axum::Json(resp) =
                recurring::confirm(State(state.clone()), auth.clone(), Path(series_id)).await?;
            to_json_text(&resp)
        }
        "ignore_recurring_series" => {
            let series_id = req_i64(args, "seriesId")?;
            let axum::Json(resp) =
                recurring::ignore(State(state.clone()), auth.clone(), Path(series_id)).await?;
            to_json_text(&resp)
        }
        "undo_recurring_series_change" => {
            let series_id = req_i64(args, "seriesId")?;
            let axum::Json(resp) =
                recurring::undo(State(state.clone()), auth.clone(), Path(series_id)).await?;
            to_json_text(&resp)
        }
        "create_recurring_series" => {
            let req = parse_recurring_request(args)?;
            validate(&req)?;
            let (_, axum::Json(created)) =
                recurring::create(State(state.clone()), auth.clone(), Json(req)).await?;
            to_json_text(&created)
        }
        "update_recurring_series" => {
            let series_id = req_i64(args, "seriesId")?;
            let req = parse_recurring_request(args)?;
            validate(&req)?;
            let axum::Json(updated) = recurring::update(
                State(state.clone()),
                auth.clone(),
                Path(series_id),
                Json(req),
            )
            .await?;
            to_json_text(&updated)
        }
        "delete_recurring_series" => {
            let series_id = req_i64(args, "seriesId")?;
            recurring::delete(State(state.clone()), auth.clone(), Path(series_id)).await?;
            Ok(format!("Deleted recurring series {series_id}"))
        }
        "detect_recurring_series" => {
            let detected = detection::detect(state, auth.member_id, state.clock.today()).await?;
            Ok(format!("Detected or refreshed {detected} recurring series"))
        }
        // ── Envelopes ───────────────────────────────────────────────────────
        "list_budget_envelopes" => {
            let axum::Json(list) = envelopes::find_all(State(state.clone()), auth.clone()).await?;
            to_json_text(&list)
        }
        "get_budget_envelope" => {
            let envelope_id = req_i64(args, "envelopeId")?;
            let axum::Json(list) = envelopes::find_all(State(state.clone()), auth.clone()).await?;
            let found = list
                .into_iter()
                .find(|b| b.id == envelope_id)
                .ok_or_else(|| AppError::NotFound("Budget not found".to_owned()))?;
            to_json_text(&found)
        }
        "create_budget_envelope" => {
            let category_id = req_i64(args, "categoryId")?;
            let monthly_limit = req_decimal(args, "monthlyLimit")?;
            let req: BudgetRequest = serde_json::from_value(json!({
                "categoryId": category_id,
                "monthlyLimit": monthly_limit.to_string(),
            }))
            .map_err(|e| AppError::BadRequest(e.to_string()))?;
            validate(&req)?;
            let (_, axum::Json(created)) =
                envelopes::create(State(state.clone()), auth.clone(), Json(req)).await?;
            to_json_text(&created)
        }
        "update_budget_envelope" => {
            let envelope_id = req_i64(args, "envelopeId")?;
            let category_id = req_i64(args, "categoryId")?;
            let monthly_limit = req_decimal(args, "monthlyLimit")?;
            let req: BudgetRequest = serde_json::from_value(json!({
                "categoryId": category_id,
                "monthlyLimit": monthly_limit.to_string(),
            }))
            .map_err(|e| AppError::BadRequest(e.to_string()))?;
            validate(&req)?;
            let axum::Json(updated) = envelopes::update(
                State(state.clone()),
                auth.clone(),
                Path(envelope_id),
                Json(req),
            )
            .await?;
            to_json_text(&updated)
        }
        "delete_budget_envelope" => {
            let envelope_id = req_i64(args, "envelopeId")?;
            envelopes::delete(State(state.clone()), auth.clone(), Path(envelope_id)).await?;
            Ok(format!("Deleted envelope {envelope_id}"))
        }
        "set_envelope_allocation" => {
            let envelope_id = req_i64(args, "envelopeId")?;
            let monthly_limit = req_decimal(args, "monthlyLimit")?;
            let axum::Json(list) = envelopes::find_all(State(state.clone()), auth.clone()).await?;
            let current = list
                .into_iter()
                .find(|b| b.id == envelope_id)
                .ok_or_else(|| AppError::NotFound("Budget not found".to_owned()))?;
            let req: BudgetRequest = serde_json::from_value(json!({
                "categoryId": current.category_id,
                "monthlyLimit": monthly_limit.to_string(),
            }))
            .map_err(|e| AppError::BadRequest(e.to_string()))?;
            validate(&req)?;
            let axum::Json(updated) = envelopes::update(
                State(state.clone()),
                auth.clone(),
                Path(envelope_id),
                Json(req),
            )
            .await?;
            to_json_text(&updated)
        }
        // ── Dashboard / Spending / Cashflow ─────────────────────────────────
        "get_budget_dashboard" => {
            let today = state.clock.today();
            let cf = cashflow::compute(state, auth.member_id, CashflowPeriod::Cycle, today).await?;
            let mut cats =
                flow::spending_by_category(state, auth.member_id, CashflowPeriod::Cycle, today)
                    .await?
                    .categories;
            cats.sort_by_key(|b| std::cmp::Reverse(b.amount));
            cats.truncate(5);
            let upcoming = recurring::upcoming(state, auth.member_id, today, 30).await?;
            to_json_text(&BudgetDashboard {
                cashflow: cf,
                top_categories: cats,
                upcoming_subscriptions: upcoming,
            })
        }
        "get_spending_by_category" => {
            let period = opt_period(args, "period")?;
            let anchor = opt_date(args, "anchor")?.unwrap_or_else(|| state.clock.today());
            let resp = flow::spending_by_category(state, auth.member_id, period, anchor).await?;
            to_json_text(&resp)
        }
        "get_spending_category_detail" => {
            let category_id = req_i64(args, "categoryId")?;
            let period = opt_period(args, "period")?;
            let anchor = opt_date(args, "anchor")?.unwrap_or_else(|| state.clock.today());
            let resp =
                flow::category_detail(state, auth.member_id, category_id, period, anchor).await?;
            to_json_text(&resp)
        }
        "get_cashflow" => {
            let period = opt_period(args, "period")?;
            let anchor = opt_date(args, "anchor")?.unwrap_or_else(|| state.clock.today());
            let resp = cashflow::compute(state, auth.member_id, period, anchor).await?;
            to_json_text(&resp)
        }
        "get_cashflow_flow" => {
            let period = opt_period(args, "period")?;
            let anchor = opt_date(args, "anchor")?.unwrap_or_else(|| state.clock.today());
            let resp = flow::flow(state, auth.member_id, period, anchor).await?;
            to_json_text(&resp)
        }
        other => Err(AppError::BadRequest(format!(
            "Unknown budget tool: {other}"
        ))),
    }
}

fn parse_category_request(args: &Map<String, Value>) -> AppResult<CategoryRequest> {
    let name = req_str(args, "name")?;
    let kind: CategoryKind = req_enum(args, "kind")?;
    let color = opt_str(args, "color")?;
    let icon = opt_str(args, "icon")?;
    let parent_id = opt_i64(args, "parentId")?;
    serde_json::from_value(json!({
        "name": name,
        "kind": kind,
        "color": color,
        "icon": icon,
        "parentId": parent_id,
    }))
    .map_err(|e| AppError::BadRequest(e.to_string()))
}

fn parse_rule_request(args: &Map<String, Value>) -> AppResult<CategorizationRuleRequest> {
    let match_type: RuleMatchType = req_enum(args, "matchType")?;
    let pattern = req_str(args, "pattern")?;
    let category_id = req_i64(args, "categoryId")?;
    let priority = opt_i32(args, "priority")?;
    serde_json::from_value(json!({
        "matchType": match_type,
        "pattern": pattern,
        "categoryId": category_id,
        "priority": priority,
    }))
    .map_err(|e| AppError::BadRequest(e.to_string()))
}

fn parse_recurring_request(args: &Map<String, Value>) -> AppResult<RecurringSeriesRequest> {
    let label = req_str(args, "label")?;
    let expected_amount = req_decimal(args, "expectedAmount")?;
    let cadence: RecurringCadence = req_enum(args, "cadence")?;
    let counterparty = opt_str(args, "counterparty")?;
    let next_due_date = opt_date(args, "nextDueDate")?;
    let category_id = opt_i64(args, "categoryId")?;
    serde_json::from_value(json!({
        "label": label,
        "counterparty": counterparty,
        "expectedAmount": expected_amount.to_string(),
        "cadence": cadence,
        "nextDueDate": next_due_date.map(|d| d.to_string()),
        "categoryId": category_id,
    }))
    .map_err(|e| AppError::BadRequest(e.to_string()))
}
