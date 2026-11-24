//! AnalysisTools (14 tools): whole-wealth analysis (`analysis:read`), per-account analysis
//! (`accounts:read`), and security reference insight (`prices:read`).

use axum::extract::State;
use serde_json::{Map, Value};

use crate::AppState;
use crate::accounts::analysis::{diversification, expenses, projection, pyramid, targets};
use crate::accounts::realized_pnl;
use crate::accounts::security;
use crate::auth::Auth;
use crate::budget::allocation;
use crate::error::{AppError, AppResult};
use crate::http::json::Path;
use crate::ledger::model::TransactionType;
use crate::ledger::{accounts, transactions};
use crate::mcp::tools::{
    ToolSpec, opt_date, opt_i32, opt_period, opt_str, prop_date, prop_enum, prop_int, prop_str,
    req_i64, req_str, schema_empty, schema_obj, to_json_text,
};

const DEFAULT_PROJECTION_YEARS: i32 = 20;

pub fn specs() -> Vec<ToolSpec> {
    vec![
        // ── analysis:read ───────────────────────────────────────────────────
        ToolSpec {
            name: "get_allocation",
            description: "Read-only. Current wealth by asset class, plus the contributions that flowed into each class over the budget cycle (CYCLE) or year to date (YTD).",
            scope: "analysis:read",
            input_schema: schema_obj(vec![
                (
                    "period",
                    prop_enum(
                        "CYCLE (current pay cycle, default) or YTD",
                        &["CYCLE", "YTD"],
                    ),
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
            name: "get_wealth_pyramid",
            description: "Read-only. The five wealth-pyramid tiers, their weights against the member's allocation targets, and the resulting score.",
            scope: "analysis:read",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_portfolio_diversification",
            description: "Read-only. How the equity sleeve spreads across sectors and regions; holdings with no known profile are listed as unclassified.",
            scope: "analysis:read",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_wealth_projection",
            description: "Read-only. The investable portfolio projected forward under four return assumptions, fed by recurring investment plans. Excludes property and loans.",
            scope: "analysis:read",
            input_schema: schema_obj(vec![(
                "years",
                prop_int(
                    "Horizon in years, 1 to 40 (out-of-range values are clamped); defaults to 20",
                ),
                false,
            )]),
        },
        ToolSpec {
            name: "get_allocation_targets",
            description: "Read-only. The member's target allocation percentages, essential monthly expenses and safety-net months, or the shipped defaults when never set.",
            scope: "analysis:read",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_essential_expense_estimate",
            description: "Read-only. What the member's own transactions suggest they spend monthly on essentials. A suggestion; nothing is stored.",
            scope: "analysis:read",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_savings_suggestions",
            description: "Read-only. Suggested savings-book (livret) interest configs for synced accounts that have none yet.",
            scope: "analysis:read",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_real_estate_summary",
            description: "Read-only. Gross property value, outstanding mortgage debt and net equity, weighted by the member's ownership shares.",
            scope: "analysis:read",
            input_schema: schema_empty(),
        },
        // ── accounts:read ───────────────────────────────────────────────────
        ToolSpec {
            name: "get_savings_interest",
            description: "Read-only. Year-to-date and full-year interest projection for a savings account. Not found when the account has no savings config.",
            scope: "accounts:read",
            input_schema: schema_obj(vec![("accountId", prop_int("The account id"), true)]),
        },
        ToolSpec {
            name: "get_property_valuations",
            description: "Read-only. Stored value estimates for one property account, newest first. Readable by co-owners.",
            scope: "accounts:read",
            input_schema: schema_obj(vec![(
                "accountId",
                prop_int("The property (REAL_ESTATE) account id"),
                true,
            )]),
        },
        ToolSpec {
            name: "get_loan_summary",
            description: "Read-only. Amortization of a LOAN account: a summary plus the monthly installment schedule. Not found when the loan details are not set.",
            scope: "accounts:read",
            input_schema: schema_obj(vec![("accountId", prop_int("The loan account id"), true)]),
        },
        ToolSpec {
            name: "get_realized_pnl",
            description: "Read-only. Realized profit and loss of an investment account per ticker, from its BUY/SELL transactions at average cost.",
            scope: "accounts:read",
            input_schema: schema_obj(vec![("accountId", prop_int("The account id"), true)]),
        },
        ToolSpec {
            name: "get_exchange_positions",
            description: "Read-only. Per-product breakdown (spot, staking, lending) of a crypto exchange account; empty for any other account.",
            scope: "accounts:read",
            input_schema: schema_obj(vec![("accountId", prop_int("The account id"), true)]),
        },
        // ── prices:read ─────────────────────────────────────────────────────
        ToolSpec {
            name: "get_security_insight",
            description: "Read-only. Asset type of a ticker and, for an ETF, its composition (companies, countries, sectors). Market data, not member data.",
            scope: "prices:read",
            input_schema: schema_obj(vec![
                (
                    "ticker",
                    prop_str("Ticker symbol, e.g. CW8.PA or AAPL"),
                    true,
                ),
                (
                    "name",
                    prop_str("Optional security name; helps identify the ETF issuer"),
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
        "get_allocation" => {
            let period = opt_period(args, "period")?;
            let anchor = opt_date(args, "anchor")?.unwrap_or_else(|| state.clock.today());
            let resp = allocation::compute(state, auth.member_id, period, anchor).await?;
            to_json_text(&resp)
        }
        "get_wealth_pyramid" => {
            let mut conn = state.db.pool_handle();
            let resp = pyramid::pyramid(
                &mut conn,
                &*state.ledger.prices,
                &*state.accounts.crypto_logos,
                auth.member_id,
                state.clock.today(),
            )
            .await?;
            to_json_text(&resp)
        }
        "get_portfolio_diversification" => {
            let mut conn = state.db.pool_handle();
            let resp = diversification::diversification(
                &mut conn,
                &*state.ledger.prices,
                &*state.accounts.crypto_logos,
                auth.member_id,
                state.clock.today(),
            )
            .await?;
            to_json_text(&resp)
        }
        "get_wealth_projection" => {
            let years = i64::from(opt_i32(args, "years")?.unwrap_or(DEFAULT_PROJECTION_YEARS));
            let today = state.clock.today();
            let mut conn = state.db.pool_handle();
            let pyr = pyramid::pyramid(
                &mut conn,
                &*state.ledger.prices,
                &*state.accounts.crypto_logos,
                auth.member_id,
                today,
            )
            .await?;
            let resp = projection::project(&mut conn, &pyr, auth.member_id, years, today).await?;
            to_json_text(&resp)
        }
        "get_allocation_targets" => {
            let axum::Json(resp) = targets::get(State(state.clone()), auth.clone()).await?;
            to_json_text(&resp)
        }
        "get_essential_expense_estimate" => {
            let mut conn = state.db.pool_handle();
            let resp = expenses::estimate(
                &mut conn,
                &*state.ledger.prices,
                auth.member_id,
                state.clock.today(),
            )
            .await?;
            to_json_text(&resp)
        }
        "get_savings_suggestions" => {
            let axum::Json(resp) =
                crate::wealth::savings::suggestions(State(state.clone()), auth.clone()).await?;
            to_json_text(&resp)
        }
        "get_real_estate_summary" => {
            let resp = crate::wealth::real_estate::summarize(state, auth.member_id, true).await?;
            to_json_text(&resp)
        }
        "get_savings_interest" => {
            let account_id = req_i64(args, "accountId")?;
            let axum::Json(resp) = crate::wealth::savings::projection(
                State(state.clone()),
                auth.clone(),
                Path(account_id),
            )
            .await?;
            to_json_text(&resp)
        }
        "get_property_valuations" => {
            let account_id = req_i64(args, "accountId")?;
            let axum::Json(resp) = crate::wealth::real_estate::valuations(
                State(state.clone()),
                auth.clone(),
                Path(account_id),
            )
            .await?;
            to_json_text(&resp)
        }
        "get_loan_summary" => {
            let account_id = req_i64(args, "accountId")?;
            let axum::Json(resp) = crate::accounts::meta::loan_summary(
                State(state.clone()),
                auth.clone(),
                Path(account_id),
            )
            .await?;
            to_json_text(&resp)
        }
        "get_realized_pnl" => {
            let account_id = req_i64(args, "accountId")?;
            let account = accounts::get_or_throw(&state.db, account_id, auth.member_id).await?;
            let stream = transactions::find_by_account_and_tx_types(
                &state.db,
                account_id,
                &[TransactionType::Buy, TransactionType::Sell],
            )
            .await?;
            let resp = realized_pnl::compute(account.currency, &stream);
            to_json_text(&resp)
        }
        "get_exchange_positions" => {
            let account_id = req_i64(args, "accountId")?;
            let axum::Json(resp) = crate::accounts::positions::positions(
                State(state.clone()),
                auth.clone(),
                Path(account_id),
            )
            .await?;
            to_json_text(&resp)
        }
        "get_security_insight" => {
            let ticker = req_str(args, "ticker")?;
            let security_name = opt_str(args, "name")?;
            let resp = security::get_insight(
                &state.db,
                &state.accounts.analysis,
                &ticker,
                security_name.as_deref(),
                None,
                state.clock.now(),
            )
            .await?;
            to_json_text(&resp)
        }
        other => Err(AppError::BadRequest(format!(
            "Unknown analysis tool: {other}"
        ))),
    }
}
