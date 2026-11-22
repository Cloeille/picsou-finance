//! InsightTools (5 tools): net-worth dashboard, net-worth history, PnL, family dashboard, price.

use std::collections::HashSet;

use axum::extract::{RawQuery, State};
use serde_json::{Map, Value};

use crate::AppState;
use crate::auth::Auth;
use crate::db::AsDbHandle;
use crate::error::{AppError, AppResult};
use crate::mcp::tools::{
    ToolSpec, opt_date, opt_i32, opt_i64_vec, opt_str, prop_date, prop_int, prop_int_array,
    prop_str, req_str, schema_empty, schema_obj, to_json_text,
};

const DEFAULT_HISTORY_MONTHS: i32 = 12;

pub fn specs() -> Vec<ToolSpec> {
    vec![
        ToolSpec {
            name: "get_dashboard",
            description: "Get the authenticated member's net-worth dashboard: totals, assets vs liabilities, allocation and per-account breakdown. Optional range filters the included history.",
            scope: "dashboard:read",
            input_schema: schema_obj(vec![(
                "range",
                prop_str("Optional range filter, e.g. 1M, 3M, 6M, 1Y, ALL; omit for the default"),
                false,
            )]),
        },
        ToolSpec {
            name: "get_net_worth_history",
            description: "Get the member's net-worth over time as monthly points. accountIds defaults to ALL of the member's accounts (a pocket whose wallet is listed counts through the wallet); months defaults to 12.",
            scope: "dashboard:read",
            input_schema: schema_obj(vec![
                (
                    "accountIds",
                    prop_int_array("Account ids to include; omit for all of the member's accounts"),
                    false,
                ),
                (
                    "months",
                    prop_int("Number of months of history; defaults to 12"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "get_profit_and_loss",
            description: "Get profit-and-loss across the member's investment accounts (total, invested, PnL and optionally the change since a 'from' date). accountIds defaults to ALL of the member's accounts (a pocket whose wallet is listed counts through the wallet).",
            scope: "dashboard:read",
            input_schema: schema_obj(vec![
                (
                    "accountIds",
                    prop_int_array("Account ids to include; omit for all of the member's accounts"),
                    false,
                ),
                (
                    "fromDate",
                    prop_date(
                        "Optional start date (ISO yyyy-MM-dd) for the range PnL; omit for inception-to-date",
                    ),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "get_family_dashboard",
            description: "Get the shared family dashboard visible to the authenticated member: aggregated net worth across members who share with them. Only data the member is permitted to see is returned.",
            scope: "family:read",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_price",
            description: "Get the latest known price in EUR for an asset ticker (e.g. AAPL, BTC). Global market data, not member-specific.",
            scope: "prices:read",
            input_schema: schema_obj(vec![(
                "ticker",
                prop_str("Asset ticker, e.g. AAPL or BTC"),
                true,
            )]),
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
        "get_dashboard" => {
            let range = opt_str(args, "range")?;
            let query = range.map(|r| {
                format!(
                    "range={}",
                    url::form_urlencoded::byte_serialize(r.as_bytes()).collect::<String>()
                )
            });
            let axum::Json(dash) = crate::accounts::dashboard::get_dashboard(
                State(state.clone()),
                auth.clone(),
                RawQuery(query),
            )
            .await?;
            to_json_text(&dash)
        }
        "get_net_worth_history" => {
            let account_ids = opt_i64_vec(args, "accountIds")?;
            let months = opt_i32(args, "months")?.unwrap_or(DEFAULT_HISTORY_MONTHS);
            let ids = resolve_account_ids(state, auth, account_ids).await?;
            let mut conn = state.db.acquire().await?;
            let points = crate::accounts::history::build_history(
                state,
                &mut conn.as_handle(),
                &ids,
                months,
                false,
                auth.member_id,
            )
            .await?;
            to_json_text(&points)
        }
        "get_profit_and_loss" => {
            let account_ids = opt_i64_vec(args, "accountIds")?;
            let from_date = opt_date(args, "fromDate")?;
            let ids = resolve_account_ids(state, auth, account_ids).await?;
            if ids.is_empty() {
                let empty = crate::accounts::history::PnlResponse {
                    total: rust_decimal::Decimal::ZERO,
                    invested: Some(rust_decimal::Decimal::ZERO),
                    pnl: Some(rust_decimal::Decimal::ZERO),
                    pnl_percent: None,
                    value_at_from: None,
                    range_pnl: None,
                    range_pnl_percent: None,
                };
                return to_json_text(&empty);
            }
            let joined = ids.iter().map(i64::to_string).collect::<Vec<_>>().join(",");
            let query = match from_date {
                Some(from) => format!("accountIds={joined}&from={from}"),
                None => format!("accountIds={joined}"),
            };
            let axum::Json(pnl) = crate::accounts::history::get_pnl(
                State(state.clone()),
                auth.clone(),
                RawQuery(Some(query)),
            )
            .await?;
            to_json_text(&pnl)
        }
        "get_family_dashboard" => {
            let axum::Json(dash) =
                crate::family::view::dashboard(State(state.clone()), auth.clone()).await?;
            to_json_text(&dash)
        }
        "get_price" => {
            let ticker = req_str(args, "ticker")?;
            let price = state
                .ledger
                .prices
                .quote(&ticker, false, state.clock.today())
                .await
                .map(|q| q.price);
            match price {
                Some(p) => Ok(p.normalize().to_string()),
                None => Ok("null".to_owned()),
            }
        }
        other => Err(AppError::BadRequest(format!(
            "Unknown insight tool: {other}"
        ))),
    }
}

/// Uses explicit account ids, or defaults to all of the member's visible accounts excluding
/// pockets whose parent wallet is also listed (matching InsightTools.resolveAccountIds).
async fn resolve_account_ids(
    state: &AppState,
    auth: &Auth,
    explicit: Option<Vec<i64>>,
) -> AppResult<Vec<i64>> {
    if let Some(ids) = explicit
        && !ids.is_empty()
    {
        return Ok(ids);
    }
    let axum::Json(accounts) =
        crate::accounts::service::find_all(State(state.clone()), auth.clone(), RawQuery(None))
            .await?;
    let listed: HashSet<i64> = accounts.iter().map(|a| a.id).collect();
    Ok(accounts
        .into_iter()
        .filter(|a| a.parent_account_id.is_none_or(|p| !listed.contains(&p)))
        .map(|a| a.id)
        .collect())
}
