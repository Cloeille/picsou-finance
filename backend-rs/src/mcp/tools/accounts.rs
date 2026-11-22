//! AccountTools (12 tools): accounts, holdings, balance snapshots, deletion impact, SCPI position.

use axum::extract::{RawQuery, State};
use serde_json::{Map, Value, json};

use crate::AppState;
use crate::accounts::meta::ScpiPositionRequest;
use crate::accounts::service::{AccountRequest, SnapshotRequest};
use crate::accounts::validation::ValidJson;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::Path;
use crate::ledger::model::{AccountType, HoldingDraft};
use crate::ledger::{accounts, holdings};
use crate::mcp::tools::{
    ToolSpec, opt_date, opt_decimal, opt_str, prop_date, prop_enum, prop_int, prop_num, prop_str,
    req_date, req_decimal, req_enum, req_i64, req_str, schema_empty, schema_obj, to_json_text,
    validate_at,
};

const ACCOUNT_TYPES: &[&str] = &[
    "CHECKING",
    "SAVINGS",
    "LEP",
    "LIVRET_A",
    "LDDS",
    "LIVRET_JEUNE",
    "PEL",
    "CEL",
    "PEA",
    "COMPTE_TITRES",
    "CRYPTO",
    "REAL_ESTATE",
    "SCPI",
    "LOAN",
    "CREDIT_CARD",
    "PEER_LOAN",
    "EMPLOYEE_SAVINGS",
    "ASSURANCE_VIE",
    "PER",
    "PRECIOUS_METALS",
    "BROKERAGE",
    "OTHER",
];

const ACCOUNT_TYPE_DESC: &str = "Account type: CHECKING, SAVINGS, LEP, LIVRET_A, LDDS, LIVRET_JEUNE, PEL, CEL, PEA, COMPTE_TITRES, CRYPTO, REAL_ESTATE, SCPI, LOAN, CREDIT_CARD, PEER_LOAN, EMPLOYEE_SAVINGS, ASSURANCE_VIE, PER, PRECIOUS_METALS, BROKERAGE or OTHER";

pub fn specs() -> Vec<ToolSpec> {
    vec![
        ToolSpec {
            name: "list_accounts",
            description: "List all of the authenticated member's accounts with their current balances and metadata.",
            scope: "accounts:read",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_account",
            description: "Get a single account of the authenticated member by its id.",
            scope: "accounts:read",
            input_schema: schema_obj(vec![("accountId", prop_int("The account id"), true)]),
        },
        ToolSpec {
            name: "get_account_holdings",
            description: "List the holdings (positions) of an investment/crypto account, with quantity, price and PnL.",
            scope: "accounts:read",
            input_schema: schema_obj(vec![("accountId", prop_int("The account id"), true)]),
        },
        ToolSpec {
            name: "get_account_balance_history",
            description: "Get an account's recorded balance snapshots, optionally bounded by a from/to date (ISO yyyy-MM-dd).",
            scope: "accounts:read",
            input_schema: schema_obj(vec![
                ("accountId", prop_int("The account id"), true),
                (
                    "from",
                    prop_date("Earliest date (inclusive), ISO yyyy-MM-dd; omit for no lower bound"),
                    false,
                ),
                (
                    "to",
                    prop_date("Latest date (inclusive), ISO yyyy-MM-dd; omit for no upper bound"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "get_account_deletion_impact",
            description: "Ask before a destructive delete_account; previews what connection would be removed (if any) but does not delete. A prediction: delete_account returns what was actually removed.",
            scope: "accounts:read",
            input_schema: schema_obj(vec![("accountId", prop_int("The account id"), true)]),
        },
        ToolSpec {
            name: "create_manual_account",
            description: "Create a new MANUAL account for the authenticated member (a non-synced account whose balance you maintain yourself). Returns the created account.",
            scope: "accounts:write",
            input_schema: schema_obj(vec![
                ("name", prop_str("Account name"), true),
                ("type", prop_enum(ACCOUNT_TYPE_DESC, ACCOUNT_TYPES), true),
                (
                    "currency",
                    prop_str("ISO currency code, e.g. EUR, USD"),
                    true,
                ),
                (
                    "currentBalance",
                    prop_num("Opening balance; defaults to 0 when omitted"),
                    false,
                ),
                ("color", prop_str("Optional hex colour like #1a2b3c"), false),
                (
                    "ticker",
                    prop_str("Optional ticker for single-asset accounts"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "update_account",
            description: "Update an existing account of the authenticated member. Returns the updated account.",
            scope: "accounts:write",
            input_schema: schema_obj(vec![
                ("accountId", prop_int("The account id"), true),
                ("name", prop_str("Account name"), true),
                ("type", prop_enum(ACCOUNT_TYPE_DESC, ACCOUNT_TYPES), true),
                (
                    "currency",
                    prop_str("ISO currency code, e.g. EUR, USD"),
                    true,
                ),
                (
                    "currentBalance",
                    prop_num("Balance; omit to leave the service default"),
                    false,
                ),
                ("color", prop_str("Optional hex colour like #1a2b3c"), false),
                (
                    "ticker",
                    prop_str("Optional ticker for single-asset accounts"),
                    false,
                ),
            ]),
        },
        ToolSpec {
            name: "delete_account",
            description: "Delete (soft-delete) an account of the authenticated member. This also supports synced accounts. If it was the last account on its connection, saved sessions/credentials and that connection are removed; this can delete an IBKR, SimpleFIN or Wise connection or Enable Banking requisition. Returns the actual DeletionImpact: removesConnection is true only when something was removed (otherwise false with a null label).",
            scope: "accounts:write",
            input_schema: schema_obj(vec![("accountId", prop_int("The account id"), true)]),
        },
        ToolSpec {
            name: "add_balance_snapshot",
            description: "Record a manual balance snapshot for an account on a given date (ISO yyyy-MM-dd).",
            scope: "accounts:write",
            input_schema: schema_obj(vec![
                ("accountId", prop_int("The account id"), true),
                ("balance", prop_num("The balance to record"), true),
                ("date", prop_date("Snapshot date, ISO yyyy-MM-dd"), true),
            ]),
        },
        ToolSpec {
            name: "upsert_holding",
            description: "Create or update a holding (position) on an account by ticker, then return the account's holdings.",
            scope: "accounts:write",
            input_schema: schema_obj(vec![
                ("accountId", prop_int("The account id"), true),
                ("ticker", prop_str("Ticker symbol, e.g. AAPL or BTC"), true),
                ("name", prop_str("Display name of the asset"), true),
                ("quantity", prop_num("Quantity held"), true),
                (
                    "currentPriceEur",
                    prop_num("Current price per unit in EUR"),
                    true,
                ),
            ]),
        },
        ToolSpec {
            name: "delete_holding",
            description: "Delete a holding from an account by its ticker.",
            scope: "accounts:write",
            input_schema: schema_obj(vec![
                ("accountId", prop_int("The account id"), true),
                ("ticker", prop_str("Ticker symbol to remove"), true),
            ]),
        },
        ToolSpec {
            name: "set_scpi_position",
            description: "Set a SCPI account's shares, EUR prices per share and distribution. Balance = withdrawal price x shares; with shares but no withdrawal price it is kept (PRICE_INCOMPLETE). Replaces every field except the distribution (omitted = kept, cannot be cleared), so read get_account first. Never changes CORUM/Sofidy/Louve Invest links.",
            scope: "accounts:write",
            input_schema: schema_obj(vec![
                ("accountId", prop_int("SCPI account id"), true),
                (
                    "shareCount",
                    prop_num("Shares held, may be fractional"),
                    true,
                ),
                (
                    "subscriptionPriceEur",
                    prop_num("Subscription price"),
                    false,
                ),
                ("withdrawalPriceEur", prop_num("Withdrawal price"), false),
                ("managementCompany", prop_str("Management company"), false),
                ("isin", prop_str("ISIN"), false),
                (
                    "dividendPolicy",
                    prop_enum("Default CASH", &["CASH", "REINVEST"]),
                    false,
                ),
                (
                    "jouissanceDate",
                    prop_date("Jouissance date, yyyy-MM-dd"),
                    false,
                ),
                (
                    "annualDistributionPerShareEur",
                    prop_num("Annual cash per share, with distributionFrequency"),
                    false,
                ),
                (
                    "distributionFrequency",
                    prop_enum(
                        "With annualDistributionPerShareEur",
                        &["MONTHLY", "QUARTERLY"],
                    ),
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
        "list_accounts" => {
            let axum::Json(list) = crate::accounts::service::find_all(
                State(state.clone()),
                auth.clone(),
                RawQuery(None),
            )
            .await?;
            to_json_text(&list)
        }
        "get_account" => {
            let id = req_i64(args, "accountId")?;
            let axum::Json(account) =
                crate::accounts::service::find_by_id(State(state.clone()), auth.clone(), Path(id))
                    .await?;
            to_json_text(&account)
        }
        "get_account_holdings" => {
            let id = req_i64(args, "accountId")?;
            let mut conn = state.db.pool_handle();
            let lines = crate::accounts::holdings::owned_holdings(
                &mut conn,
                &*state.ledger.prices,
                &*state.accounts.crypto_logos,
                id,
                auth.member_id,
                state.clock.today(),
            )
            .await?;
            to_json_text(&lines)
        }
        "get_account_balance_history" => {
            let id = req_i64(args, "accountId")?;
            let from = opt_date(args, "from")?;
            let to = opt_date(args, "to")?;
            let mut pairs = Vec::new();
            if let Some(f) = from {
                pairs.push(format!("from={f}"));
            }
            if let Some(t) = to {
                pairs.push(format!("to={t}"));
            }
            let query = (!pairs.is_empty()).then(|| pairs.join("&"));
            let axum::Json(snaps) = crate::accounts::service::get_history(
                State(state.clone()),
                auth.clone(),
                Path(id),
                RawQuery(query),
            )
            .await?;
            to_json_text(&snaps)
        }
        "get_account_deletion_impact" => {
            let id = req_i64(args, "accountId")?;
            let axum::Json(impact) = crate::accounts::connection::deletion_impact(
                State(state.clone()),
                auth.clone(),
                Path(id),
            )
            .await?;
            to_json_text(&impact)
        }
        "create_manual_account" => {
            let req = parse_account_request(args)?;
            validate_at(&req, state.clock.today())?;
            let (_, axum::Json(created)) = crate::accounts::service::create(
                State(state.clone()),
                auth.clone(),
                ValidJson(req),
            )
            .await?;
            to_json_text(&created)
        }
        "update_account" => {
            let id = req_i64(args, "accountId")?;
            let req = parse_account_request(args)?;
            validate_at(&req, state.clock.today())?;
            let axum::Json(updated) = crate::accounts::service::update(
                State(state.clone()),
                auth.clone(),
                Path(id),
                ValidJson(req),
            )
            .await?;
            to_json_text(&updated)
        }
        "delete_account" => {
            let id = req_i64(args, "accountId")?;
            let impact =
                crate::accounts::connection::delete_account_with_impact(state, auth.member_id, id)
                    .await?;
            to_json_text(&impact)
        }
        "add_balance_snapshot" => {
            let id = req_i64(args, "accountId")?;
            let balance = req_decimal(args, "balance")?;
            let date = req_date(args, "date")?;
            let req: SnapshotRequest = serde_json::from_value(json!({
                "balance": balance.to_string(),
                "date": date.to_string(),
            }))
            .map_err(|e| AppError::BadRequest(e.to_string()))?;
            validate_at(&req, state.clock.today())?;
            let (_, axum::Json(snap)) = crate::accounts::service::add_snapshot(
                State(state.clone()),
                auth.clone(),
                Path(id),
                ValidJson(req),
            )
            .await?;
            to_json_text(&snap)
        }
        "upsert_holding" => {
            let account_id = req_i64(args, "accountId")?;
            let ticker = req_str(args, "ticker")?;
            let asset_name = req_str(args, "name")?;
            let quantity = req_decimal(args, "quantity")?;
            let current_price_eur = req_decimal(args, "currentPriceEur")?;
            let now = state.clock.now();
            let mut tx = state.db.begin().await?;
            accounts::get_or_throw(&mut tx, account_id, auth.member_id).await?;
            if let Some(existing) =
                holdings::find_by_account_and_ticker(&mut tx, account_id, &ticker).await?
            {
                let mut draft = HoldingDraft::from(&existing);
                draft.quantity = quantity;
                draft.current_price = Some(current_price_eur);
                draft.last_synced_at = Some(now);
                // Keep average_buy_in unchanged — it's the cost basis from first sync.
                holdings::update(&mut tx, existing.id, &draft, now).await?;
            } else {
                let draft = HoldingDraft {
                    ticker: ticker.clone(),
                    name: Some(asset_name),
                    quantity,
                    average_buy_in: Some(current_price_eur),
                    current_price: Some(current_price_eur),
                    last_synced_at: Some(now),
                    ..HoldingDraft::default()
                };
                holdings::insert(&mut tx, account_id, &draft, now).await?;
            }
            tx.commit().await?;
            let mut conn = state.db.pool_handle();
            let lines = crate::accounts::holdings::owned_holdings(
                &mut conn,
                &*state.ledger.prices,
                &*state.accounts.crypto_logos,
                account_id,
                auth.member_id,
                state.clock.today(),
            )
            .await?;
            to_json_text(&lines)
        }
        "delete_holding" => {
            let account_id = req_i64(args, "accountId")?;
            let ticker = req_str(args, "ticker")?;
            let mut tx = state.db.begin().await?;
            accounts::get_or_throw(&mut tx, account_id, auth.member_id).await?;
            let holding = holdings::find_by_account_and_ticker(&mut tx, account_id, &ticker)
                .await?
                .ok_or_else(|| AppError::NotFound("Holding not found".to_owned()))?;
            crate::db::query("DELETE FROM account_holding WHERE id = $1")
                .bind(holding.id)
                .execute(&mut tx)
                .await?;
            tx.commit().await?;
            Ok(format!(
                "Deleted holding {ticker} from account {account_id}"
            ))
        }
        "set_scpi_position" => {
            let account_id = req_i64(args, "accountId")?;
            let share_count = req_decimal(args, "shareCount")?;
            let subscription_price = opt_decimal(args, "subscriptionPriceEur")?;
            let withdrawal_price = opt_decimal(args, "withdrawalPriceEur")?;
            let management_company = opt_str(args, "managementCompany")?;
            let isin = opt_str(args, "isin")?;
            let dividend_policy = opt_str(args, "dividendPolicy")?;
            let jouissance_date = opt_date(args, "jouissanceDate")?;
            let annual_dist = opt_decimal(args, "annualDistributionPerShareEur")?;
            let dist_freq = opt_str(args, "distributionFrequency")?;

            // Build JSON without null distribution fields so omitted/null keeps stored values,
            // and never send provider fund codes (corum/sofidy/louve).
            let mut payload = Map::new();
            payload.insert(
                "shareCount".to_owned(),
                Value::String(share_count.to_string()),
            );
            if let Some(v) = subscription_price {
                payload.insert(
                    "subscriptionPriceEur".to_owned(),
                    Value::String(v.to_string()),
                );
            }
            if let Some(v) = withdrawal_price {
                payload.insert(
                    "withdrawalPriceEur".to_owned(),
                    Value::String(v.to_string()),
                );
            }
            if let Some(v) = management_company {
                payload.insert("managementCompany".to_owned(), Value::String(v));
            }
            if let Some(v) = isin {
                payload.insert("isin".to_owned(), Value::String(v));
            }
            if let Some(v) = dividend_policy {
                payload.insert("dividendPolicy".to_owned(), Value::String(v));
            }
            if let Some(v) = jouissance_date {
                payload.insert("jouissanceDate".to_owned(), Value::String(v.to_string()));
            }
            if let Some(v) = annual_dist {
                payload.insert(
                    "annualDistributionPerShareEur".to_owned(),
                    Value::String(v.to_string()),
                );
            }
            if let Some(v) = dist_freq {
                payload.insert("distributionFrequency".to_owned(), Value::String(v));
            }
            let req: ScpiPositionRequest = serde_json::from_value(Value::Object(payload))
                .map_err(|e| AppError::BadRequest(e.to_string()))?;
            validate_at(&req, state.clock.today())?;
            let axum::Json(saved) = crate::accounts::meta::update_scpi(
                State(state.clone()),
                auth.clone(),
                Path(account_id),
                ValidJson(req),
            )
            .await?;
            to_json_text(&saved)
        }
        other => Err(AppError::BadRequest(format!(
            "Unknown account tool: {other}"
        ))),
    }
}

fn parse_account_request(args: &Map<String, Value>) -> AppResult<AccountRequest> {
    let name = req_str(args, "name")?;
    let account_type: AccountType = req_enum(args, "type")?;
    let currency = req_str(args, "currency")?;
    let current_balance = opt_decimal(args, "currentBalance")?;
    let color = opt_str(args, "color")?;
    let ticker = opt_str(args, "ticker")?;
    Ok(AccountRequest {
        name: Some(name),
        account_type: Some(account_type),
        provider: None,
        currency: Some(currency),
        current_balance,
        is_manual: true,
        color,
        ticker,
        logo_key: None,
        institution_id: None,
        opened_at: None,
    })
}
