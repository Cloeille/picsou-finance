//! PeerLoanTools (4 tools): peer loan detail, repayments, overview, and closure.

use serde_json::{Map, Value};

use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::ledger::model::PeerLoanRepaymentKind;
use crate::mcp::tools::{
    ToolSpec, opt_bool, opt_date, opt_enum, opt_i32, opt_str, prop_bool, prop_date, prop_enum,
    prop_int, prop_num, prop_str, req_date, req_decimal, req_i64, schema_obj, to_json_text,
    validate,
};
use crate::wealth::peer_loans::{PeerLoanRepaymentRequest, service};

pub fn specs() -> Vec<ToolSpec> {
    vec![
        ToolSpec {
            name: "get_peer_loan",
            description: "Get a peer loan (a PEER_LOAN account) of the authenticated member: terms, remaining capital, accrued and received interest, planned-vs-actual forecast, and the dated repayment ledger with each row's interest/capital split.",
            scope: "accounts:read",
            input_schema: schema_obj(vec![(
                "accountId",
                prop_int("The peer-loan account id"),
                true,
            )]),
        },
        ToolSpec {
            name: "add_peer_loan_repayment",
            description: "Record a repayment received on a peer loan of the authenticated member. The amount is allocated interest first, then capital. On a manual linked cash account the matching deposit is written by the service; a synced account is left untouched. The loan must be configured and open. Returns the ledger row; call get_peer_loan for the updated summary.",
            scope: "accounts:write",
            input_schema: schema_obj(vec![
                ("accountId", prop_int("The peer-loan account id"), true),
                ("paidOn", prop_date("Payment date, ISO yyyy-MM-dd"), true),
                (
                    "amount",
                    prop_num("Amount received, greater than zero"),
                    true,
                ),
                (
                    "kind",
                    prop_enum(
                        "PRINCIPAL (default) or INTEREST; both allocate interest first, then capital",
                        &["PRINCIPAL", "INTEREST", "WRITE_OFF"],
                    ),
                    false,
                ),
                (
                    "note",
                    prop_str("Optional note, at most 255 characters"),
                    false,
                ),
                (
                    "withCashLeg",
                    prop_bool(
                        "Set false to record a compensation/netting with no bank movement (the receivable falls, the cash account is untouched); defaults to true",
                    ),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "get_peer_loans_overview",
            description: "Get every configured peer loan of the authenticated member as one view: total outstanding, open and closed counts, accrued interest, the outstanding curve, and one row per loan with remaining capital, repayment percentage, plan drift, last payment and closure date. Closed loans are included, so an advisor can reason from the full history.",
            scope: "accounts:read",
            input_schema: schema_obj(vec![(
                "months",
                prop_int("History window in months, 1 to 24; defaults to 12"),
                false,
            )]),
        },
        ToolSpec {
            name: "close_peer_loan",
            description: "Close a peer loan of the authenticated member: writes off the remaining capital on the given date, if any, and marks the loan closed. Idempotent on an already-closed loan. Returns the updated detail; call get_peer_loans_overview for the new totals.",
            scope: "accounts:write",
            input_schema: schema_obj(vec![
                ("accountId", prop_int("The peer-loan account id"), true),
                (
                    "closedAt",
                    prop_date("Closure date, ISO yyyy-MM-dd; defaults to today"),
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
        "get_peer_loan" => {
            let account_id = req_i64(args, "accountId")?;
            let detail = service::get(state, account_id, auth.member_id).await?;
            to_json_text(&detail)
        }
        "add_peer_loan_repayment" => {
            let account_id = req_i64(args, "accountId")?;
            let paid_on = req_date(args, "paidOn")?;
            let amount = req_decimal(args, "amount")?;
            let kind: Option<PeerLoanRepaymentKind> = opt_enum(args, "kind")?;
            let note = opt_str(args, "note")?;
            let with_cash_leg = opt_bool(args, "withCashLeg")?;
            if kind == Some(PeerLoanRepaymentKind::WriteOff) {
                return Err(AppError::BadRequest(
                    "WRITE_OFF is not available through this tool; close the loan instead"
                        .to_owned(),
                ));
            }
            let req = PeerLoanRepaymentRequest {
                paid_on: Some(paid_on),
                amount: Some(amount),
                kind,
                note,
                with_cash_leg,
            };
            validate(&req)?;
            let row = service::add_repayment(state, account_id, auth.member_id, &req).await?;
            to_json_text(&row)
        }
        "get_peer_loans_overview" => {
            let months = opt_i32(args, "months")?.unwrap_or(12);
            let overview = service::overview(state, auth.member_id, months).await?;
            to_json_text(&overview)
        }
        "close_peer_loan" => {
            let account_id = req_i64(args, "accountId")?;
            let closed_at = opt_date(args, "closedAt")?;
            let detail = service::close(state, account_id, auth.member_id, closed_at).await?;
            to_json_text(&detail)
        }
        other => Err(AppError::BadRequest(format!(
            "Unknown peer loan tool: {other}"
        ))),
    }
}
