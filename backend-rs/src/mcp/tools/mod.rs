//! Curated MCP tool catalog (93 tools across 10 tool groups), JSON schema builders,
//! argument helpers, scope enforcement, and dispatch.

pub mod accounts;
pub mod ai;
pub mod analysis;
pub mod budget;
pub mod goals;
pub mod insights;
pub mod oauth2;
pub mod peer_loans;
pub mod sync;
pub mod transactions;

use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::{Map, Value, json};

use crate::AppState;
use crate::accounts::validation::{Check, ValidateAt};
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Lang, Validate};

#[derive(Debug, Clone)]
pub struct ToolSpec {
    pub name: &'static str,
    pub description: &'static str,
    pub scope: &'static str,
    pub input_schema: Value,
}

#[derive(Debug)]
pub enum ToolError {
    UnknownTool(String),
    Execution(String),
}

impl From<AppError> for ToolError {
    fn from(err: AppError) -> Self {
        Self::Execution(format_app_error(err))
    }
}

pub type ToolResult = Result<String, ToolError>;

pub fn catalog() -> Vec<ToolSpec> {
    let mut all = Vec::with_capacity(93);
    all.extend(accounts::specs());
    all.extend(transactions::specs());
    all.extend(goals::specs());
    all.extend(insights::specs());
    all.extend(sync::specs());
    all.extend(oauth2::specs());
    all.extend(budget::specs());
    all.extend(analysis::specs());
    all.extend(ai::specs());
    all.extend(peer_loans::specs());
    all
}

pub fn list_json() -> Vec<Value> {
    catalog()
        .into_iter()
        .map(|spec| {
            json!({
                "name": spec.name,
                "description": spec.description,
                "inputSchema": spec.input_schema,
            })
        })
        .collect()
}

pub async fn call(state: &AppState, auth: &Auth, name: &str, args: Value) -> ToolResult {
    let all = catalog();
    let Some(spec) = all.iter().find(|s| s.name == name) else {
        return Err(ToolError::UnknownTool(name.to_owned()));
    };
    if !auth.has_scope(spec.scope) {
        return Err(ToolError::Execution(format!(
            "Missing required scope: {}",
            spec.scope
        )));
    }
    let obj = match args {
        Value::Object(map) => map,
        Value::Null => Map::new(),
        _ => {
            return Err(ToolError::Execution(
                "Tool arguments must be a JSON object".to_owned(),
            ));
        }
    };
    let res = match name {
        "list_accounts"
        | "get_account"
        | "get_account_holdings"
        | "get_account_balance_history"
        | "get_account_deletion_impact"
        | "create_manual_account"
        | "update_account"
        | "delete_account"
        | "add_balance_snapshot"
        | "upsert_holding"
        | "delete_holding"
        | "set_scpi_position" => accounts::call(state, auth, name, &obj).await,

        "list_account_transactions"
        | "add_transaction"
        | "update_transaction"
        | "delete_transaction" => transactions::call(state, auth, name, &obj).await,

        "list_goals"
        | "get_goal"
        | "get_goal_monthly_entries"
        | "create_goal"
        | "update_goal"
        | "create_recurring_investment"
        | "delete_goal"
        | "set_goal_month_contribution" => goals::call(state, auth, name, &obj).await,

        "get_dashboard"
        | "get_net_worth_history"
        | "get_profit_and_loss"
        | "get_family_dashboard"
        | "get_price" => insights::call(state, auth, name, &obj).await,

        "trigger_full_sync"
        | "trigger_bank_sync"
        | "trigger_broker_sync"
        | "trigger_crypto_exchange_sync"
        | "trigger_crypto_wallet_sync"
        | "get_sync_status" => sync::call(state, auth, name, &obj).await,

        "get_oauth2_configuration" | "get_oauth2_session_status" => {
            oauth2::call(state, auth, name, &obj).await
        }

        "list_budget_categories"
        | "get_budget_category"
        | "create_budget_category"
        | "update_budget_category"
        | "delete_budget_category"
        | "list_budget_rules"
        | "get_budget_rule"
        | "create_budget_rule"
        | "update_budget_rule"
        | "delete_budget_rule"
        | "apply_rule_to_transactions"
        | "preview_budget_rule"
        | "list_budget_transactions"
        | "update_budget_transaction"
        | "list_recurring_series"
        | "get_recurring_series"
        | "get_recurring_activity"
        | "get_recurring_calendar"
        | "confirm_recurring_series"
        | "ignore_recurring_series"
        | "undo_recurring_series_change"
        | "create_recurring_series"
        | "update_recurring_series"
        | "delete_recurring_series"
        | "detect_recurring_series"
        | "list_budget_envelopes"
        | "get_budget_envelope"
        | "create_budget_envelope"
        | "update_budget_envelope"
        | "delete_budget_envelope"
        | "set_envelope_allocation"
        | "get_budget_dashboard"
        | "get_spending_by_category"
        | "get_spending_category_detail"
        | "get_cashflow"
        | "get_cashflow_flow" => budget::call(state, auth, name, &obj).await,

        "get_allocation"
        | "get_wealth_pyramid"
        | "get_portfolio_diversification"
        | "get_wealth_projection"
        | "get_allocation_targets"
        | "get_essential_expense_estimate"
        | "get_savings_suggestions"
        | "get_real_estate_summary"
        | "get_savings_interest"
        | "get_property_valuations"
        | "get_loan_summary"
        | "get_realized_pnl"
        | "get_exchange_positions"
        | "get_security_insight" => analysis::call(state, auth, name, &obj).await,

        "start_ai_categorization" | "get_ai_categorization_status" => {
            ai::call(state, auth, name, &obj).await
        }

        "get_peer_loan"
        | "add_peer_loan_repayment"
        | "get_peer_loans_overview"
        | "close_peer_loan" => peer_loans::call(state, auth, name, &obj).await,

        other => return Err(ToolError::UnknownTool(other.to_owned())),
    };
    res.map_err(ToolError::from)
}

pub(crate) fn format_app_error(err: AppError) -> String {
    match err {
        AppError::NotFound(msg)
        | AppError::BadRequest(msg)
        | AppError::Forbidden(msg)
        | AppError::Status { detail: msg, .. }
        | AppError::Coded { detail: msg, .. } => msg,
        AppError::BadCredentials => "Invalid credentials".to_owned(),
        AppError::Validation(fields) => {
            let joined: Vec<String> = fields
                .into_iter()
                .map(|(k, v)| format!("{k}: {v}"))
                .collect();
            if joined.is_empty() {
                "Validation failed".to_owned()
            } else {
                format!("Validation failed: {}", joined.join(", "))
            }
        }
        AppError::Problem(p) => p
            .detail
            .or(p.title)
            .unwrap_or_else(|| "Request failed".to_owned()),
        AppError::NotImplemented => "Not implemented".to_owned(),
        AppError::Internal(msg) => {
            tracing::error!("MCP tool internal error: {msg}");
            "An unexpected error occurred".to_owned()
        }
    }
}

pub(crate) fn to_json_text<T: Serialize>(value: &T) -> AppResult<String> {
    serde_json::to_string(value).map_err(AppError::internal)
}

pub(crate) fn validate<T: Validate>(req: &T) -> AppResult<()> {
    let mut errors = Errors::new(Lang::English);
    req.validate(&mut errors);
    errors.into_result()
}

pub(crate) fn validate_at<T: ValidateAt>(req: &T, today: NaiveDate) -> AppResult<()> {
    let mut errors = Errors::new(Lang::English);
    let mut check = Check {
        errors: &mut errors,
        lang: Lang::English,
        today,
    };
    req.validate_at(&mut check);
    errors.into_result()
}

pub(crate) fn schema_empty() -> Value {
    json!({
        "type": "object",
        "properties": {},
    })
}

pub(crate) fn schema_obj(props: Vec<(&str, Value, bool)>) -> Value {
    let mut properties = Map::new();
    let mut required = Vec::new();
    for (name, prop_schema, is_req) in props {
        properties.insert(name.to_owned(), prop_schema);
        if is_req {
            required.push(Value::String(name.to_owned()));
        }
    }
    let mut out = Map::new();
    out.insert("type".to_owned(), Value::String("object".to_owned()));
    out.insert("properties".to_owned(), Value::Object(properties));
    if !required.is_empty() {
        out.insert("required".to_owned(), Value::Array(required));
    }
    Value::Object(out)
}

pub(crate) fn prop_int(description: &str) -> Value {
    json!({ "type": "integer", "description": description })
}

pub(crate) fn prop_num(description: &str) -> Value {
    json!({ "type": "number", "description": description })
}

pub(crate) fn prop_str(description: &str) -> Value {
    json!({ "type": "string", "description": description })
}

pub(crate) fn prop_date(description: &str) -> Value {
    json!({ "type": "string", "format": "date", "description": description })
}

pub(crate) fn prop_bool(description: &str) -> Value {
    json!({ "type": "boolean", "description": description })
}

pub(crate) fn prop_enum(description: &str, values: &[&str]) -> Value {
    json!({ "type": "string", "enum": values, "description": description })
}

pub(crate) fn prop_int_array(description: &str) -> Value {
    json!({ "type": "array", "items": { "type": "integer" }, "description": description })
}

// ─── Argument extraction helpers ────────────────────────────────────────────

fn missing(field: &str) -> AppError {
    AppError::BadRequest(format!("Missing required parameter: {field}"))
}

fn invalid(field: &str, detail: impl std::fmt::Display) -> AppError {
    AppError::BadRequest(format!("Invalid parameter '{field}': {detail}"))
}

pub(crate) fn req_i64(args: &Map<String, Value>, key: &str) -> AppResult<i64> {
    opt_i64(args, key)?.ok_or_else(|| missing(key))
}

pub(crate) fn opt_i64(args: &Map<String, Value>, key: &str) -> AppResult<Option<i64>> {
    match args.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Number(n)) => n
            .as_i64()
            .map(Some)
            .ok_or_else(|| invalid(key, "expected integer")),
        Some(Value::String(s)) => s
            .trim()
            .parse::<i64>()
            .map(Some)
            .map_err(|_| invalid(key, "expected integer")),
        Some(_) => Err(invalid(key, "expected integer")),
    }
}

pub(crate) fn opt_i32(args: &Map<String, Value>, key: &str) -> AppResult<Option<i32>> {
    opt_i64(args, key)?
        .map(|v| i32::try_from(v).map_err(|_| invalid(key, "integer out of range")))
        .transpose()
}

pub(crate) fn req_str(args: &Map<String, Value>, key: &str) -> AppResult<String> {
    opt_str(args, key)?.ok_or_else(|| missing(key))
}

pub(crate) fn opt_str(args: &Map<String, Value>, key: &str) -> AppResult<Option<String>> {
    match args.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(s)) => Ok(Some(s.clone())),
        Some(_) => Err(invalid(key, "expected string")),
    }
}

pub(crate) fn opt_bool(args: &Map<String, Value>, key: &str) -> AppResult<Option<bool>> {
    match args.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Bool(b)) => Ok(Some(*b)),
        Some(Value::String(s)) => match s.trim().to_ascii_lowercase().as_str() {
            "true" => Ok(Some(true)),
            "false" => Ok(Some(false)),
            _ => Err(invalid(key, "expected boolean")),
        },
        Some(_) => Err(invalid(key, "expected boolean")),
    }
}

pub(crate) fn req_decimal(args: &Map<String, Value>, key: &str) -> AppResult<Decimal> {
    opt_decimal(args, key)?.ok_or_else(|| missing(key))
}

pub(crate) fn opt_decimal(args: &Map<String, Value>, key: &str) -> AppResult<Option<Decimal>> {
    match args.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Number(n)) => {
            let s = n.to_string();
            s.parse::<Decimal>()
                .or_else(|_| Decimal::from_scientific(&s))
                .map(Some)
                .map_err(|_| invalid(key, "expected decimal number"))
        }
        Some(Value::String(s)) => {
            let trimmed = s.trim();
            if trimmed.is_empty() {
                return Ok(None);
            }
            trimmed
                .parse::<Decimal>()
                .or_else(|_| Decimal::from_scientific(trimmed))
                .map(Some)
                .map_err(|_| invalid(key, "expected decimal number"))
        }
        Some(_) => Err(invalid(key, "expected decimal number")),
    }
}

pub(crate) fn req_date(args: &Map<String, Value>, key: &str) -> AppResult<NaiveDate> {
    opt_date(args, key)?.ok_or_else(|| missing(key))
}

pub(crate) fn opt_date(args: &Map<String, Value>, key: &str) -> AppResult<Option<NaiveDate>> {
    match args.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(s)) => {
            let trimmed = s.trim();
            if trimmed.is_empty() {
                return Ok(None);
            }
            NaiveDate::parse_from_str(trimmed, "%Y-%m-%d")
                .map(Some)
                .map_err(|_| invalid(key, "expected ISO date yyyy-MM-dd"))
        }
        Some(_) => Err(invalid(key, "expected ISO date yyyy-MM-dd")),
    }
}

pub(crate) fn req_enum<T: DeserializeOwned>(args: &Map<String, Value>, key: &str) -> AppResult<T> {
    opt_enum(args, key)?.ok_or_else(|| missing(key))
}

pub(crate) fn opt_enum<T: DeserializeOwned>(
    args: &Map<String, Value>,
    key: &str,
) -> AppResult<Option<T>> {
    match args.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(val) => serde_json::from_value::<T>(val.clone())
            .map(Some)
            .map_err(|e| invalid(key, e)),
    }
}

pub(crate) fn opt_period(
    args: &Map<String, Value>,
    key: &str,
) -> AppResult<crate::budget::cycle::CashflowPeriod> {
    match opt_str(args, key)? {
        None => Ok(crate::budget::cycle::CashflowPeriod::Cycle),
        Some(raw) => raw
            .parse::<crate::budget::cycle::CashflowPeriod>()
            .map_err(|()| invalid(key, "expected CYCLE or YTD")),
    }
}

pub(crate) fn req_i64_vec(args: &Map<String, Value>, key: &str) -> AppResult<Vec<i64>> {
    opt_i64_vec(args, key)?.ok_or_else(|| missing(key))
}

pub(crate) fn opt_i64_vec(args: &Map<String, Value>, key: &str) -> AppResult<Option<Vec<i64>>> {
    match args.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Array(items)) => {
            let mut out = Vec::with_capacity(items.len());
            for item in items {
                match item {
                    Value::Number(n) => {
                        out.push(
                            n.as_i64()
                                .ok_or_else(|| invalid(key, "expected integers"))?,
                        );
                    }
                    Value::String(s) => {
                        out.push(
                            s.trim()
                                .parse::<i64>()
                                .map_err(|_| invalid(key, "expected integers"))?,
                        );
                    }
                    _ => return Err(invalid(key, "expected array of integers")),
                }
            }
            Ok(Some(out))
        }
        Some(_) => Err(invalid(key, "expected array of integers")),
    }
}
