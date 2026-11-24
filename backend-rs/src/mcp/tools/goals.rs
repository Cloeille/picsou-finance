//! GoalTools (8 tools): savings goals, recurring investment plans, and monthly contributions.

use axum::extract::State;
use serde_json::{Map, Value};

use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Json, Lang, Path};
use crate::mcp::tools::{
    ToolSpec, opt_date, opt_decimal, prop_date, prop_int, prop_int_array, prop_num, prop_str,
    req_date, req_decimal, req_i64, req_i64_vec, req_str, schema_empty, schema_obj, to_json_text,
    validate,
};
use crate::wealth::goals::GoalBody;
use crate::wealth::goals::dto::{GoalRequest, GoalType, MonthAmountRequest};

pub fn specs() -> Vec<ToolSpec> {
    vec![
        ToolSpec {
            name: "list_goals",
            description: "List the authenticated member's savings goals with progress, monthly-needed and on-track status.",
            scope: "goals:read",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_goal",
            description: "Get a single savings goal of the authenticated member by its id, with progress.",
            scope: "goals:read",
            input_schema: schema_obj(vec![("goalId", prop_int("The goal id"), true)]),
        },
        ToolSpec {
            name: "get_goal_monthly_entries",
            description: "Get a goal's month-by-month entries: objective, actual (from snapshots), manual declaration and effective value.",
            scope: "goals:read",
            input_schema: schema_obj(vec![("goalId", prop_int("The goal id"), true)]),
        },
        ToolSpec {
            name: "create_goal",
            description: "Create a savings goal for the authenticated member, tracked across one or more of the member's accounts.",
            scope: "goals:write",
            input_schema: schema_obj(vec![
                ("name", prop_str("Goal name"), true),
                ("targetAmount", prop_num("Target amount to reach"), true),
                (
                    "deadline",
                    prop_date("Deadline, ISO yyyy-MM-dd; must be in the future"),
                    true,
                ),
                (
                    "accountIds",
                    prop_int_array(
                        "Ids of the member's accounts whose balances count toward this goal (at least one)",
                    ),
                    true,
                ),
            ]),
        },
        ToolSpec {
            name: "update_goal",
            description: "Update an existing savings goal of the authenticated member.",
            scope: "goals:write",
            input_schema: schema_obj(vec![
                ("goalId", prop_int("The goal id"), true),
                ("name", prop_str("Goal name"), true),
                ("targetAmount", prop_num("Target amount to reach"), true),
                (
                    "deadline",
                    prop_date("Deadline, ISO yyyy-MM-dd; must be in the future"),
                    true,
                ),
                (
                    "accountIds",
                    prop_int_array(
                        "Ids of the member's accounts whose balances count toward this goal (at least one)",
                    ),
                    true,
                ),
            ]),
        },
        ToolSpec {
            name: "create_recurring_investment",
            description: "Create a recurring monthly investment plan for the authenticated member (e.g. 300 EUR into the PEA every month). Unlike a savings goal it has no target amount and no deadline; it feeds the wealth projection.",
            scope: "goals:write",
            input_schema: schema_obj(vec![
                ("name", prop_str("Plan name"), true),
                (
                    "monthlyAmount",
                    prop_num("Amount invested every month"),
                    true,
                ),
                (
                    "accountId",
                    prop_int("Id of the member's account the money goes into"),
                    true,
                ),
                (
                    "expectedReturn",
                    prop_num("Expected annual return in percent, optional"),
                    false,
                ),
                (
                    "startDate",
                    prop_date("First month, ISO yyyy-MM-dd; null means already running"),
                    false,
                ),
                (
                    "endDate",
                    prop_date("Last month, ISO yyyy-MM-dd; null means open-ended"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "delete_goal",
            description: "Delete a savings goal of the authenticated member.",
            scope: "goals:write",
            input_schema: schema_obj(vec![("goalId", prop_int("The goal id"), true)]),
        },
        ToolSpec {
            name: "set_goal_month_contribution",
            description: "Set the manual contribution declared for a goal in a given month (yearMonth is \"yyyy-MM\", e.g. 2026-03).",
            scope: "goals:write",
            input_schema: schema_obj(vec![
                ("goalId", prop_int("The goal id"), true),
                (
                    "yearMonth",
                    prop_str("Target month as \"yyyy-MM\", e.g. 2026-03"),
                    true,
                ),
                (
                    "amount",
                    prop_num("The contribution amount to record for that month"),
                    true,
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
        "list_goals" => {
            let axum::Json(list) =
                crate::wealth::goals::find_all(State(state.clone()), auth.clone()).await?;
            to_json_text(&list)
        }
        "get_goal" => {
            let id = req_i64(args, "goalId")?;
            let axum::Json(goal) =
                crate::wealth::goals::find_by_id(State(state.clone()), auth.clone(), Path(id))
                    .await?;
            to_json_text(&goal)
        }
        "get_goal_monthly_entries" => {
            let id = req_i64(args, "goalId")?;
            let axum::Json(entries) =
                crate::wealth::goals::monthly_entries(State(state.clone()), auth.clone(), Path(id))
                    .await?;
            to_json_text(&entries)
        }
        "create_goal" => {
            let req = parse_savings_target(args)?;
            validate_goal(&req, state)?;
            let (_, axum::Json(created)) =
                crate::wealth::goals::create(State(state.clone()), auth.clone(), GoalBody(req))
                    .await?;
            to_json_text(&created)
        }
        "update_goal" => {
            let id = req_i64(args, "goalId")?;
            let req = parse_savings_target(args)?;
            validate_goal(&req, state)?;
            let axum::Json(updated) = crate::wealth::goals::update(
                State(state.clone()),
                auth.clone(),
                Path(id),
                GoalBody(req),
            )
            .await?;
            to_json_text(&updated)
        }
        "create_recurring_investment" => {
            let name = req_str(args, "name")?;
            let monthly_amount = req_decimal(args, "monthlyAmount")?;
            let account_id = req_i64(args, "accountId")?;
            let expected_return = opt_decimal(args, "expectedReturn")?;
            let start_date = opt_date(args, "startDate")?;
            let end_date = opt_date(args, "endDate")?;
            let req = GoalRequest {
                name: Some(name),
                goal_type: Some(GoalType::RecurringInvestment),
                target_amount: None,
                deadline: None,
                monthly_amount: Some(monthly_amount),
                expected_return,
                start_date,
                end_date,
                allocations: None,
                account_ids: Some(vec![account_id]),
            };
            validate_goal(&req, state)?;
            let (_, axum::Json(created)) =
                crate::wealth::goals::create(State(state.clone()), auth.clone(), GoalBody(req))
                    .await?;
            to_json_text(&created)
        }
        "delete_goal" => {
            let id = req_i64(args, "goalId")?;
            crate::wealth::goals::delete(State(state.clone()), auth.clone(), Path(id)).await?;
            Ok(format!("Deleted goal {id}"))
        }
        "set_goal_month_contribution" => {
            let id = req_i64(args, "goalId")?;
            let year_month = req_str(args, "yearMonth")?;
            let amount = req_decimal(args, "amount")?;
            let req = MonthAmountRequest {
                amount: Some(amount),
            };
            validate(&req)?;
            let axum::Json(entry) = crate::wealth::goals::set_manual_contribution(
                State(state.clone()),
                auth.clone(),
                Path((id, year_month)),
                Json(req),
            )
            .await?;
            to_json_text(&entry)
        }
        other => Err(AppError::BadRequest(format!("Unknown goal tool: {other}"))),
    }
}

fn parse_savings_target(args: &Map<String, Value>) -> AppResult<GoalRequest> {
    let name = req_str(args, "name")?;
    let target_amount = req_decimal(args, "targetAmount")?;
    let deadline = req_date(args, "deadline")?;
    let account_ids = req_i64_vec(args, "accountIds")?;
    Ok(GoalRequest {
        name: Some(name),
        goal_type: Some(GoalType::SavingsTarget),
        target_amount: Some(target_amount),
        deadline: Some(deadline),
        monthly_amount: None,
        expected_return: None,
        start_date: None,
        end_date: None,
        allocations: None,
        account_ids: Some(account_ids),
    })
}

fn validate_goal(req: &GoalRequest, state: &AppState) -> AppResult<()> {
    let mut errors = Errors::new(Lang::English);
    req.validate(&mut errors, state.clock.today());
    errors.into_result()
}
