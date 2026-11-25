//! TransactionTools (4 tools): list account transactions and CRUD manual transactions.

use axum::extract::State;
use serde_json::{Map, Value};

use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::{Json, Path};
use crate::ledger::manual_transactions::TransactionRequest;
use crate::ledger::model::TransactionType;
use crate::mcp::tools::{
    ToolSpec, opt_decimal, opt_enum, opt_str, prop_date, prop_enum, prop_int, prop_num, prop_str,
    req_date, req_decimal, req_i64, req_str, schema_obj, to_json_text, validate,
};

const TX_TYPES: &[&str] = &["DEPOSIT", "WITHDRAWAL", "BUY", "SELL", "DIVIDEND", "FEE"];

pub fn specs() -> Vec<ToolSpec> {
    vec![
        ToolSpec {
            name: "list_account_transactions",
            description: "List the transactions of one of the authenticated member's accounts.",
            scope: "transactions:read",
            input_schema: schema_obj(vec![("accountId", prop_int("The account id"), true)]),
        },
        ToolSpec {
            name: "add_transaction",
            description: "Add a manual transaction to an account. amount is signed (negative for outflow). txType is one of DEPOSIT, WITHDRAWAL, BUY, SELL, DIVIDEND, FEE. For BUY/SELL you may also pass ticker, name, quantity and pricePerUnit.",
            scope: "transactions:write",
            input_schema: schema_obj(vec![
                ("accountId", prop_int("The account id"), true),
                ("date", prop_date("Transaction date, ISO yyyy-MM-dd"), true),
                ("description", prop_str("Human-readable description"), true),
                (
                    "amount",
                    prop_num("Signed amount (negative for an outflow)"),
                    true,
                ),
                (
                    "txType",
                    prop_enum("DEPOSIT, WITHDRAWAL, BUY, SELL, DIVIDEND or FEE", TX_TYPES),
                    false,
                ),
                (
                    "ticker",
                    prop_str("Asset ticker (for BUY/SELL/DIVIDEND)"),
                    false,
                ),
                ("name", prop_str("Asset display name (for BUY/SELL)"), false),
                ("quantity", prop_num("Quantity (for BUY/SELL)"), false),
                (
                    "pricePerUnit",
                    prop_num("Price per unit (for BUY/SELL)"),
                    false,
                ),
                ("currency", prop_str("ISO currency code, e.g. EUR"), false),
            ]),
        },
        ToolSpec {
            name: "update_transaction",
            description: "Update an existing manual transaction on an account.",
            scope: "transactions:write",
            input_schema: schema_obj(vec![
                ("accountId", prop_int("The account id"), true),
                ("transactionId", prop_int("The transaction id"), true),
                ("date", prop_date("Transaction date, ISO yyyy-MM-dd"), true),
                ("description", prop_str("Human-readable description"), true),
                (
                    "amount",
                    prop_num("Signed amount (negative for an outflow)"),
                    true,
                ),
                (
                    "txType",
                    prop_enum("DEPOSIT, WITHDRAWAL, BUY, SELL, DIVIDEND or FEE", TX_TYPES),
                    false,
                ),
                (
                    "ticker",
                    prop_str("Asset ticker (for BUY/SELL/DIVIDEND)"),
                    false,
                ),
                ("name", prop_str("Asset display name (for BUY/SELL)"), false),
                ("quantity", prop_num("Quantity (for BUY/SELL)"), false),
                (
                    "pricePerUnit",
                    prop_num("Price per unit (for BUY/SELL)"),
                    false,
                ),
                ("currency", prop_str("ISO currency code, e.g. EUR"), false),
            ]),
        },
        ToolSpec {
            name: "delete_transaction",
            description: "Delete a manual transaction from an account.",
            scope: "transactions:write",
            input_schema: schema_obj(vec![
                ("accountId", prop_int("The account id"), true),
                ("transactionId", prop_int("The transaction id"), true),
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
        "list_account_transactions" => {
            let account_id = req_i64(args, "accountId")?;
            let axum::Json(rows) = crate::accounts::service::get_transactions(
                State(state.clone()),
                auth.clone(),
                Path(account_id),
            )
            .await?;
            to_json_text(&rows)
        }
        "add_transaction" => {
            let account_id = req_i64(args, "accountId")?;
            let req = parse_tx_request(args)?;
            validate(&req)?;
            let (_, axum::Json(tx)) = crate::accounts::service::add_transaction(
                State(state.clone()),
                auth.clone(),
                Path(account_id),
                Json(req),
            )
            .await?;
            to_json_text(&tx)
        }
        "update_transaction" => {
            let account_id = req_i64(args, "accountId")?;
            let tx_id = req_i64(args, "transactionId")?;
            let req = parse_tx_request(args)?;
            validate(&req)?;
            let axum::Json(tx) = crate::accounts::service::update_transaction(
                State(state.clone()),
                auth.clone(),
                Path((account_id, tx_id)),
                Json(req),
            )
            .await?;
            to_json_text(&tx)
        }
        "delete_transaction" => {
            let account_id = req_i64(args, "accountId")?;
            let tx_id = req_i64(args, "transactionId")?;
            crate::accounts::service::delete_transaction(
                State(state.clone()),
                auth.clone(),
                Path((account_id, tx_id)),
            )
            .await?;
            Ok(format!(
                "Deleted transaction {tx_id} from account {account_id}"
            ))
        }
        other => Err(AppError::BadRequest(format!(
            "Unknown transaction tool: {other}"
        ))),
    }
}

fn parse_tx_request(args: &Map<String, Value>) -> AppResult<TransactionRequest> {
    let date = req_date(args, "date")?;
    let description = req_str(args, "description")?;
    let amount = req_decimal(args, "amount")?;
    let tx_type: Option<TransactionType> = opt_enum(args, "txType")?;
    let ticker = opt_str(args, "ticker")?;
    let name = opt_str(args, "name")?;
    let quantity = opt_decimal(args, "quantity")?;
    let price_per_unit = opt_decimal(args, "pricePerUnit")?;
    let currency = opt_str(args, "currency")?;
    Ok(TransactionRequest {
        date: Some(date),
        description: Some(description),
        amount: Some(amount),
        tx_type,
        ticker,
        name,
        quantity,
        price_per_unit,
        currency,
        category_id: None,
        fees: None,
    })
}
