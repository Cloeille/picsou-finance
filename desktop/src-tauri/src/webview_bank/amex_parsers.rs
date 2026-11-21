//! Pure parsers for American Express France JSON (1:1 port of `services/amex-auth/main.py`).
//!
//! No I/O and no browser here: `amex.rs` fetches, these functions turn responses into the
//! normalized snapshot `backend-rs/src/connectors/amex/sync.rs` expects. All monetary values use
//! `rust_decimal::Decimal` end to end with zero binary floats, and diagnostic helpers never emit
//! financial values, tokens, or account identifiers.

use std::collections::HashSet;
use std::str::FromStr;
use std::sync::LazyLock;

use regex::Regex;
use rust_decimal::Decimal;
use serde::Serialize;
use serde_json::Value;

static ISO_DATE_PREFIX: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^\d{4}-\d{2}-\d{2}").expect("iso date regex"));

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParseError(pub &'static str);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedBalance {
    pub external_id: String,
    pub name: String,
    pub balance_eur: Decimal,
    pub statement_balance: Option<Decimal>,
    pub amount_due: Option<Decimal>,
    pub due_date: Option<String>,
    pub minimum_payment: Option<Decimal>,
    pub reward_points: Option<i64>,
    pub account_token: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawParsedTransaction {
    pub date: String,
    pub description: String,
    pub amount: Decimal,
    pub status: Option<String>,
    pub category: Option<String>,
    pub identifier: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TransactionPayload {
    pub date: String,
    pub description: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub amount: Decimal,
    pub status: Option<String>,
    pub category: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountPayload {
    pub external_id: String,
    pub name: String,
    #[serde(rename = "type")]
    pub account_type: &'static str,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub balance_eur: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub statement_balance: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub amount_due: Option<Decimal>,
    pub due_date: Option<String>,
    pub reward_points: Option<i64>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub minimum_payment: Option<Decimal>,
    pub transactions: Vec<TransactionPayload>,
    pub pending_complete: bool,
    pub snapshot_complete: bool,
}

/// Parses JSON keeping decimal numbers exact (via `serde_json`'s `arbitrary_precision`).
pub fn loads(input: &str) -> Result<Value, ParseError> {
    serde_json::from_str(input).map_err(|_| ParseError("UPSTREAM_FORMAT_CHANGED"))
}

/// Extracts the first non-empty `account_token` from `/api/servicing/v1/member`.
pub fn account_token_from_member_payload(payload: &Value) -> Option<String> {
    let accounts = payload.as_object()?.get("accounts")?.as_array()?;
    for entry in accounts {
        let Some(token) = entry.as_object().and_then(|o| o.get("account_token")) else {
            continue;
        };
        let s = value_to_non_empty_string(token);
        if s.is_some() {
            return s;
        }
    }
    None
}

fn value_to_non_empty_string(value: &Value) -> Option<String> {
    let s = match value {
        Value::String(s) => s.clone(),
        Value::Number(n) => n.to_string(),
        _ => return None,
    };
    (!s.trim().is_empty()).then_some(s)
}

fn first<'a>(entry: &'a serde_json::Map<String, Value>, keys: &[&str]) -> Option<&'a Value> {
    for &key in keys {
        if let Some(val) = entry.get(key) {
            if !val.is_null() {
                return Some(val);
            }
        }
    }
    None
}

/// Unwraps AMEX's occasional `{ "amount" | "value" | "total": x }` wrapper one level.
fn unwrap_amount(value: Option<&Value>) -> Option<&Value> {
    let value = value?;
    if value.is_null() {
        return None;
    }
    if let Some(map) = value.as_object() {
        for key in ["amount", "value", "total"] {
            if let Some(inner) = map.get(key) {
                return (!inner.is_null()).then_some(inner);
            }
        }
        return None;
    }
    Some(value)
}

pub fn parse_decimal(value: &Value) -> Result<Decimal, ParseError> {
    let raw = match value {
        Value::Number(n) => n.to_string(),
        Value::String(s) => s.trim().to_owned(),
        _ => return Err(ParseError("UPSTREAM_FORMAT_CHANGED")),
    };
    if raw.is_empty() {
        return Err(ParseError("UPSTREAM_FORMAT_CHANGED"));
    }
    Decimal::from_str(&raw)
        .or_else(|_| Decimal::from_scientific(&raw))
        .map_err(|_| ParseError("UPSTREAM_FORMAT_CHANGED"))
}

fn parse_optional_i64(value: Option<&Value>) -> Option<i64> {
    let value = value?;
    match value {
        Value::Number(n) => n.as_i64().or_else(|| {
            let dec = Decimal::from_str(&n.to_string())
                .or_else(|_| Decimal::from_scientific(&n.to_string()))
                .ok()?;
            i64::try_from(dec.trunc().mantissa()).ok()
        }),
        Value::String(s) => s.trim().parse::<i64>().ok(),
        _ => None,
    }
}

/// Parses AMEX `/api/servicing/v1/financials/balances` response into a normalized card balance.
///
/// Follows the exact statement-payment semantics (issues #196 and #245):
/// - When `statement_balance_amount` or `total_debits_balance_amount` is present:
///   - If `remaining_statement_balance_amount` is present, it already nets payments/credits
///     applied to the statement: `applied = statement - remaining` (or 0 when statement is absent),
///     `unapplied = max(0, credits - applied)`, `current = remaining + debits - unapplied`.
///   - Otherwise (legacy): `current = statement + debits - credits`.
/// - Otherwise falls back to plain `totalBalance` / `total_balance_amount` / `currentBalance` / `balance`.
/// - Negates the amount for `balance_eur` (Picsou represents credit-card liabilities as negative).
pub fn parse_balance(
    payload: &Value,
    account_token: Option<&str>,
) -> Result<ParsedBalance, ParseError> {
    let accounts = match payload {
        Value::Array(list) => list.as_slice(),
        Value::Object(map) => {
            let candidate = map
                .get("accounts")
                .or_else(|| map.get("financialAccountSummaries"))
                .or_else(|| map.get("balances"))
                .and_then(Value::as_array)
                .ok_or(ParseError("UPSTREAM_FORMAT_CHANGED"))?;
            candidate.as_slice()
        }
        _ => return Err(ParseError("UPSTREAM_FORMAT_CHANGED")),
    };
    if accounts.is_empty() {
        return Err(ParseError("UPSTREAM_FORMAT_CHANGED"));
    }

    let mut chosen: Option<&serde_json::Map<String, Value>> = None;
    if let Some(wanted) = account_token {
        for candidate in accounts {
            let Some(obj) = candidate.as_object() else {
                continue;
            };
            if let Some(tok) = first(obj, &["accountToken", "account_token"])
                .and_then(value_to_non_empty_string)
            {
                if tok == wanted {
                    chosen = Some(obj);
                    break;
                }
            }
        }
    }
    let entry = chosen
        .or_else(|| accounts[0].as_object())
        .ok_or(ParseError("UPSTREAM_FORMAT_CHANGED"))?;

    let statement_balance_val = unwrap_amount(first(
        entry,
        &[
            "statementBalance",
            "statement_balance_amount",
            "statement_balance",
        ],
    ));
    let statement_balance = match statement_balance_val {
        Some(v) => Some(parse_decimal(v)?),
        None => None,
    };

    let has_statement_or_debits = entry
        .get("statement_balance_amount")
        .is_some_and(|v| !v.is_null())
        || entry
            .get("total_debits_balance_amount")
            .is_some_and(|v| !v.is_null());

    let current_balance = if has_statement_or_debits {
        let statement_amt = statement_balance.unwrap_or(Decimal::ZERO);
        let debits_amt = match unwrap_amount(entry.get("total_debits_balance_amount")) {
            Some(v) => parse_decimal(v)?,
            None => Decimal::ZERO,
        };
        let credits_amt = match unwrap_amount(entry.get("total_payments_credits_amount")) {
            Some(v) => parse_decimal(v)?,
            None => Decimal::ZERO,
        };
        if let Some(remaining_val) = unwrap_amount(entry.get("remaining_statement_balance_amount"))
        {
            let remaining_dec = parse_decimal(remaining_val)?;
            let applied = match statement_balance {
                None => Decimal::ZERO,
                Some(_) => statement_amt - remaining_dec,
            };
            let unapplied = (credits_amt - applied).max(Decimal::ZERO);
            remaining_dec + debits_amt - unapplied
        } else {
            statement_amt + debits_amt - credits_amt
        }
    } else {
        let val = unwrap_amount(first(
            entry,
            &[
                "totalBalance",
                "total_balance_amount",
                "total_balance",
                "currentBalance",
                "current_balance",
                "balance",
            ],
        ))
        .ok_or(ParseError("UPSTREAM_FORMAT_CHANGED"))?;
        parse_decimal(val)?
    };

    let amount_due = match unwrap_amount(entry.get("remaining_statement_balance_amount")) {
        Some(v) => Some(parse_decimal(v)?),
        None => None,
    };
    let minimum_payment = match unwrap_amount(first(
        entry,
        &[
            "minimumPaymentDue",
            "minimum_payment_due",
            "minimum_payment_due_amount",
        ],
    )) {
        Some(v) => Some(parse_decimal(v)?),
        None => None,
    };
    let reward_points = parse_optional_i64(unwrap_amount(first(
        entry,
        &["rewardsBalance", "rewards_balance"],
    )));
    let entry_token =
        first(entry, &["accountToken", "account_token"]).and_then(value_to_non_empty_string);
    let external_id = entry_token
        .clone()
        .or_else(|| first(entry, &["accountId", "account_id"]).and_then(value_to_non_empty_string))
        .unwrap_or_else(|| "amex_card".to_owned());
    let name = first(
        entry,
        &[
            "productDescription",
            "accountName",
            "product_description",
            "product_name",
        ],
    )
    .and_then(value_to_non_empty_string)
    .unwrap_or_else(|| "American Express".to_owned());
    let due_date = first(entry, &["payment_due_date", "due_date", "paymentDueDate"])
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
        .map(str::to_owned);

    Ok(ParsedBalance {
        external_id,
        name,
        balance_eur: -current_balance,
        statement_balance,
        amount_due,
        due_date,
        minimum_payment,
        reward_points,
        account_token: entry_token,
    })
}

pub fn normalize_category(value: Option<&Value>) -> Option<String> {
    let value = value?;
    if let Some(s) = value.as_str() {
        return (!s.is_empty()).then(|| s.to_owned());
    }
    if let Some(map) = value.as_object() {
        for key in [
            "category_name",
            "categoryName",
            "name",
            "subcategory_name",
            "subcategoryName",
            "category",
        ] {
            if let Some(s) = map.get(key).and_then(Value::as_str) {
                if !s.is_empty() {
                    return Some(s.to_owned());
                }
            }
        }
    }
    None
}

pub fn parse_transactions(
    payload: &Value,
    status_default: Option<&str>,
) -> Result<Vec<RawParsedTransaction>, ParseError> {
    let raw = match payload {
        Value::Object(map) => match map.get("transactions") {
            None | Some(Value::Null) => &[][..],
            Some(Value::Array(list)) => list.as_slice(),
            Some(_) => return Err(ParseError("UPSTREAM_FORMAT_CHANGED")),
        },
        Value::Array(list) => list.as_slice(),
        _ => return Err(ParseError("UPSTREAM_FORMAT_CHANGED")),
    };

    let mut out = Vec::with_capacity(raw.len());
    for entry in raw {
        let obj = entry
            .as_object()
            .ok_or(ParseError("UPSTREAM_FORMAT_CHANGED"))?;
        let extended = obj
            .get("extendedDetails")
            .or_else(|| obj.get("extended_details"))
            .and_then(Value::as_object);
        let merchant_name = extended
            .and_then(|e| e.get("merchant"))
            .and_then(Value::as_object)
            .and_then(|m| m.get("name"))
            .and_then(value_to_non_empty_string);
        let category = normalize_category(extended.and_then(|e| e.get("category")))
            .or_else(|| normalize_category(obj.get("category")));

        let Some(date_raw) = first(obj, &["chargeDate", "charge_date", "date", "transactionDate"])
            .and_then(value_to_non_empty_string)
        else {
            continue;
        };
        let Some(description) = first(obj, &["description"])
            .and_then(value_to_non_empty_string)
            .or(merchant_name)
        else {
            continue;
        };
        let Some(amount_val) = unwrap_amount(first(
            obj,
            &["amount", "billingAmount", "billing_amount"],
        )) else {
            continue;
        };

        let date: String = date_raw.chars().take(10).collect();
        let amount = -parse_decimal(amount_val)?;
        let status = status_default.map(str::to_owned).or_else(|| {
            obj.get("status")
                .and_then(Value::as_str)
                .map(str::to_owned)
        });
        let identifier = obj
            .get("identifier")
            .and_then(value_to_non_empty_string);

        out.push(RawParsedTransaction {
            date,
            description: description.chars().take(255).collect(),
            amount,
            status,
            category,
            identifier,
        });
    }
    Ok(out)
}

/// Concatenates posted and pending transactions, deduplicating by `identifier` (posted wins).
pub fn merge_transactions(
    posted: Vec<RawParsedTransaction>,
    pending: Vec<RawParsedTransaction>,
) -> Vec<TransactionPayload> {
    let seen: HashSet<String> = posted
        .iter()
        .filter_map(|t| t.identifier.clone())
        .collect();
    let mut merged = Vec::with_capacity(posted.len() + pending.len());
    for tx in posted {
        merged.push(TransactionPayload {
            date: tx.date,
            description: tx.description,
            amount: tx.amount,
            status: tx.status,
            category: tx.category,
        });
    }
    for tx in pending {
        if let Some(ref id) = tx.identifier {
            if seen.contains(id) {
                continue;
            }
        }
        merged.push(TransactionPayload {
            date: tx.date,
            description: tx.description,
            amount: tx.amount,
            status: tx.status,
            category: tx.category,
        });
    }
    merged
}

pub fn parse_scheduled_due_date(payload: &Value, account_token: Option<&str>) -> Option<String> {
    let list = payload.as_array()?;
    let entries: Vec<&serde_json::Map<String, Value>> =
        list.iter().filter_map(Value::as_object).collect();
    let matched = account_token.and_then(|wanted| {
        entries.iter().copied().find(|item| {
            item.get("account_token")
                .and_then(value_to_non_empty_string)
                .is_some_and(|tok| tok == wanted)
        })
    });
    let entry = match matched {
        Some(e) => e,
        None if entries.len() == 1 && list.len() == 1 => entries[0],
        None => return None,
    };
    for (group, key) in [
        ("direct_debit_details", "direct_debit_date"),
        ("payment_due_details", "payment_due_date"),
    ] {
        if let Some(val) = entry
            .get(group)
            .and_then(Value::as_object)
            .and_then(|d| d.get(key))
            .and_then(Value::as_str)
        {
            if ISO_DATE_PREFIX.is_match(val) {
                return Some(val.chars().take(10).collect());
            }
        }
    }
    None
}

#[cfg(test)]
pub fn parse_loyalty_earned(payload: &Value) -> Option<i64> {
    let totals = payload.as_object()?.get("totals")?.as_array()?;
    for item in totals {
        let Some(obj) = item.as_object() else {
            continue;
        };
        if obj.get("type").and_then(Value::as_str) != Some("EARNED") {
            continue;
        }
        let val = obj.get("total").and_then(Value::as_object)?.get("value")?;
        if val.is_boolean() {
            return None;
        }
        if let Value::Number(_) = val {
            return parse_optional_i64(Some(val));
        }
        return None;
    }
    None
}

pub fn parse_dashboard_enrichment(payload: &Value) -> (Option<String>, Option<i64>) {
    const DATE_KEYS: &[&str] = &[
        "payment_due_date",
        "due_date",
        "paymentduedate",
        "duedate",
        "direct_debit_date",
        "directdebitdate",
        "nextdebitdate",
    ];
    const POINT_KEYS: &[&str] = &[
        "points_balance",
        "reward_points",
        "rewards_points",
        "loyalty_balance",
    ];

    fn walk(value: &Value, due_date: &mut Option<String>, points: &mut Option<i64>) {
        match value {
            Value::Object(map) => {
                for (key, child) in map {
                    let normalized = key.to_ascii_lowercase().replace('-', "_");
                    let compact = normalized.replace('_', "");
                    if due_date.is_none()
                        && (DATE_KEYS.contains(&normalized.as_str())
                            || DATE_KEYS.contains(&compact.as_str()))
                    {
                        if let Some(s) = child.as_str() {
                            if ISO_DATE_PREFIX.is_match(s) {
                                *due_date = Some(s.chars().take(10).collect());
                            }
                        }
                    }
                    if points.is_none()
                        && POINT_KEYS.contains(&normalized.as_str())
                        && child.is_number()
                    {
                        *points = parse_optional_i64(Some(child));
                    }
                    walk(child, due_date, points);
                }
            }
            Value::Array(list) => {
                for child in list {
                    walk(child, due_date, points);
                }
            }
            _ => {}
        }
    }

    let mut due_date = None;
    let mut points = None;
    walk(payload, &mut due_date, &mut points);
    (due_date, points)
}

pub fn build_account_payload(
    balance: ParsedBalance,
    scheduled_due: Option<String>,
    enrichment_due: Option<String>,
    enrichment_points: Option<i64>,
    transactions: Vec<TransactionPayload>,
    pending_complete: bool,
) -> AccountPayload {
    let due_date = scheduled_due.or(balance.due_date).or(enrichment_due);
    let reward_points = balance.reward_points.or(enrichment_points);
    AccountPayload {
        external_id: balance.external_id,
        name: balance.name,
        account_type: "CREDIT_CARD",
        balance_eur: balance.balance_eur,
        statement_balance: balance.statement_balance,
        amount_due: balance.amount_due,
        due_date,
        reward_points,
        minimum_payment: balance.minimum_payment,
        transactions,
        pending_complete,
        snapshot_complete: true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn dec(s: &str) -> Decimal {
        Decimal::from_str(s).unwrap()
    }

    #[test]
    fn member_dashboard_schedule_and_loyalty_parsers() {
        assert_eq!(
            account_token_from_member_payload(&json!({"accounts": [{"account_token": "abc123"}]})),
            Some("abc123".to_owned())
        );
        assert_eq!(
            account_token_from_member_payload(&json!({"accounts": []})),
            None
        );
        assert_eq!(account_token_from_member_payload(&json!({})), None);

        assert_eq!(
            parse_dashboard_enrichment(
                &json!({"nested": {"paymentDueDate": "2026-03-04", "points_balance": 350}})
            ),
            (Some("2026-03-04".to_owned()), Some(350))
        );
        // Generic "balance" must never be mistaken for reward points.
        assert_eq!(
            parse_dashboard_enrichment(&loads(r#"{"balance": 123.45}"#).unwrap()),
            (None, None)
        );
        assert_eq!(
            parse_scheduled_due_date(
                &json!([{
                    "account_token": "x",
                    "direct_debit_details": {"direct_debit_date": "2026-04-05T00:00:00Z"},
                    "payment_due_details": {"payment_due_date": "2026-04-06"}
                }]),
                Some("x")
            ),
            Some("2026-04-05".to_owned())
        );
        assert_eq!(
            parse_scheduled_due_date(
                &json!([{"account_token": "x", "payment_due_details": {"payment_due_date": "2026-04-06"}}]),
                Some("x")
            ),
            Some("2026-04-06".to_owned())
        );
        assert_eq!(
            parse_scheduled_due_date(
                &json!([{"account_token": "x"}, {"account_token": "y"}]),
                Some("y")
            ),
            None
        );
        assert_eq!(parse_scheduled_due_date(&json!({"junk": []}), None), None);

        assert_eq!(
            parse_loyalty_earned(&json!({
                "totals": [
                    {"type": "EARNED_BASE", "total": {"value": 4}},
                    {"type": "EARNED", "total": {"value": 13}}
                ]
            })),
            Some(13)
        );
        assert_eq!(parse_loyalty_earned(&json!({"totals": []})), None);
        assert_eq!(
            parse_loyalty_earned(&json!({"totals": [{"type": "EARNED", "total": {"value": "junk"}}]})),
            None
        );
    }

    #[test]
    fn balance_shapes_and_account_token_matching() {
        let list_balance = parse_balance(
            &json!([{
                "account_token": "tok-1",
                "total_balance_amount": "123.45",
                "minimum_payment_due": "25.00",
                "dueDate": "2026-01-15",
                "rewardPoints": 125,
                "product_name": "Gold Card"
            }]),
            None,
        )
        .unwrap();
        assert_eq!(list_balance.balance_eur, dec("-123.45"));
        assert_eq!(list_balance.account_token.as_deref(), Some("tok-1"));
        assert_eq!(list_balance.name, "Gold Card");

        let matched = parse_balance(
            &json!([
                {"account_token": "other", "total_balance_amount": "1.00"},
                {"account_token": "tok-2", "total_balance_amount": "2.00"}
            ]),
            Some("tok-2"),
        )
        .unwrap();
        assert_eq!(matched.balance_eur, dec("-2.00"));

        let dict_balance = parse_balance(
            &json!({"accounts": [{"accountToken": "tok-3", "totalBalance": 50, "accountName": "Card"}]}),
            None,
        )
        .unwrap();
        assert_eq!(dict_balance.balance_eur, dec("-50"));
        assert_eq!(dict_balance.name, "Card");
    }

    #[test]
    fn statement_payment_balance_semantics() {
        // Partial payment with remaining_statement_balance_amount.
        let partial = parse_balance(
            &loads(
                r#"[{
                    "account_token": "tok-real",
                    "last_statement_balance_amount": 100.0,
                    "remaining_statement_balance_amount": 63.50,
                    "statement_balance_amount": 100.0,
                    "total_debits_balance_amount": 0.0,
                    "total_payments_credits_amount": 36.50
                }, {"account_token": "tok-other", "total_debits_balance_amount": 999.0}]"#,
            )
            .unwrap(),
            Some("tok-real"),
        )
        .unwrap();
        assert_eq!(partial.balance_eur, dec("-63.50"));
        assert_eq!(partial.statement_balance, Some(dec("100.0")));
        assert_eq!(partial.amount_due, Some(dec("63.50")));

        // Legacy fallback without remaining_statement_balance_amount.
        let legacy = parse_balance(
            &loads(
                r#"[{
                    "account_token": "tok-real2",
                    "statement_balance_amount": 100.0,
                    "total_debits_balance_amount": 23.45,
                    "total_payments_credits_amount": 10.0
                }]"#,
            )
            .unwrap(),
            Some("tok-real2"),
        )
        .unwrap();
        assert_eq!(legacy.balance_eur, dec("-113.45"));

        // Issue #196: statement paid by direct debit (remaining = 0) with small refund in credits.
        let issue_196 = parse_balance(
            &loads(
                r#"[{
                    "account_token": "tok-issue196",
                    "statement_balance_amount": 2122.0,
                    "remaining_statement_balance_amount": 0.0,
                    "total_debits_balance_amount": 135.0,
                    "total_payments_credits_amount": 2.10
                }]"#,
            )
            .unwrap(),
            Some("tok-issue196"),
        )
        .unwrap();
        assert_eq!(issue_196.balance_eur, dec("-135.00"));
        assert_eq!(issue_196.amount_due, Some(dec("0.0")));

        // Issue #245: negative credits must not double-count debits.
        let issue_245 = parse_balance(
            &loads(
                r#"[{
                    "account_token": "tok-issue245",
                    "statement_balance_amount": 1000.0,
                    "remaining_statement_balance_amount": 0.0,
                    "total_debits_balance_amount": 40.0,
                    "total_payments_credits_amount": -40.0
                }]"#,
            )
            .unwrap(),
            Some("tok-issue245"),
        )
        .unwrap();
        assert_eq!(issue_245.balance_eur, dec("-40.0"));

        // Statement balance absent: credits reduce remaining + debits.
        let no_stmt_credit = parse_balance(
            &loads(
                r#"[{
                    "account_token": "tok-nostmt",
                    "remaining_statement_balance_amount": 500.0,
                    "total_debits_balance_amount": 20.0,
                    "total_payments_credits_amount": 30.0
                }]"#,
            )
            .unwrap(),
            Some("tok-nostmt"),
        )
        .unwrap();
        assert_eq!(no_stmt_credit.balance_eur, dec("-490.0"));

        // Refund/overpayment clamped at 0 -> positive credit balance (+15).
        let refund = parse_balance(
            &loads(
                r#"[{
                    "account_token": "tok-refund",
                    "statement_balance_amount": 100.0,
                    "remaining_statement_balance_amount": 0.0,
                    "total_debits_balance_amount": 5.0,
                    "total_payments_credits_amount": 120.0
                }]"#,
            )
            .unwrap(),
            Some("tok-refund"),
        )
        .unwrap();
        assert_eq!(refund.balance_eur, dec("15.0"));

        // Unclamped negative remaining (-20) -> same +15.
        let refund_unclamped = parse_balance(
            &loads(
                r#"[{
                    "account_token": "tok-refund-u",
                    "statement_balance_amount": 100.0,
                    "remaining_statement_balance_amount": -20.0,
                    "total_debits_balance_amount": 5.0,
                    "total_payments_credits_amount": 120.0
                }]"#,
            )
            .unwrap(),
            Some("tok-refund-u"),
        )
        .unwrap();
        assert_eq!(refund_unclamped.balance_eur, dec("15.0"));
    }

    #[test]
    fn transactions_categories_and_merge_by_identifier() {
        let parsed = parse_transactions(
            &json!({
                "transactions": [{
                    "charge_date": "2026-01-02T00:00:00Z",
                    "description": "Coffee Shop",
                    "amount": {"amount": "4.50"},
                    "extended_details": {"category": {"category_name": "Restaurant", "subcategory_name": "x"}},
                    "identifier": "id-1"
                }]
            }),
            Some("posted"),
        )
        .unwrap();
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].amount, dec("-4.50"));
        assert_eq!(parsed[0].date, "2026-01-02");
        assert_eq!(parsed[0].category.as_deref(), Some("Restaurant"));
        assert_eq!(parsed[0].status.as_deref(), Some("posted"));

        let pending = parse_transactions(
            &json!([
                {"charge_date": "2026-01-02", "description": "Coffee Shop", "amount": "4.50", "identifier": "id-1"},
                {"charge_date": "2026-01-03", "description": "Bakery", "amount": "2.00", "identifier": "id-2"}
            ]),
            Some("pending"),
        )
        .unwrap();
        let merged = merge_transactions(parsed, pending);
        assert_eq!(merged.len(), 2);
        assert_eq!(merged[0].status.as_deref(), Some("posted"));
        assert_eq!(merged[1].description, "Bakery");
        assert_eq!(merged[1].status.as_deref(), Some("pending"));

        // Verify JSON contract emits exact numbers (compatible with backend-rs Amex sync).
        let payload = build_account_payload(
            parse_balance(
                &json!([{"account_token": "tok-1", "total_balance_amount": "123.45"}]),
                Some("tok-1"),
            )
            .unwrap(),
            Some("2026-03-04".to_owned()),
            None,
            Some(350),
            merged,
            true,
        );
        let serialized = serde_json::to_value(&payload).unwrap();
        assert!(serialized["balanceEur"].is_number());
        assert_eq!(serialized["type"], "CREDIT_CARD");
        assert_eq!(serialized["snapshotComplete"], true);
        assert_eq!(serialized["pendingComplete"], true);
        assert_eq!(serialized["dueDate"], "2026-03-04");
        assert_eq!(serialized["rewardPoints"], 350);
    }
}
