//! Maps Natixis Interépargne operations to Picsou BUY / SELL lines.
//!
//! Faithful port of `services/natixis-auth/operations_parser.py`.

use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

use percent_encoding::{AsciiSet, CONTROLS, utf8_percent_encode};
use regex::Regex;
use rust_decimal::Decimal;
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};

use super::contract::OperationPayload;
use super::savings_parser::{
    FORMAT_CHANGED, SavingsFormatError, parse_amount, parse_amount_opt, parse_decimal,
    parse_decimal_opt, parse_text, parse_text_opt,
};

pub const INVESTMENT: &str = "INVESTMENT_DEMANDS";
pub const PAYMENT: &str = "PAYMENT_DEMANDS";
pub const DEFAULT_API_ORIGIN: &str = "https://nie.api.natixis.com";
pub const EMPLOYER_TOP_UP: &str = "Abondement employeur";
const DEFAULT_PAYMENT_LABEL: &str = "Remboursement";

static DEMAND_KEY_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[0-9A-Za-z_-]{1,120}$").expect("valid demand key regex"));

/// Matches Python's `urllib.parse.quote(s, safe="")`: leaves RFC 3986 unreserved
/// characters (`A-Z a-z 0-9 _ . - ~`) unencoded and percent-encodes everything else.
const QUOTE_ALL: &AsciiSet = &CONTROLS
    .add(b' ')
    .add(b'!')
    .add(b'"')
    .add(b'#')
    .add(b'$')
    .add(b'%')
    .add(b'&')
    .add(b'\'')
    .add(b'(')
    .add(b')')
    .add(b'*')
    .add(b'+')
    .add(b',')
    .add(b'/')
    .add(b':')
    .add(b';')
    .add(b'<')
    .add(b'=')
    .add(b'>')
    .add(b'?')
    .add(b'@')
    .add(b'[')
    .add(b'\\')
    .add(b']')
    .add(b'^')
    .add(b'`')
    .add(b'{')
    .add(b'|')
    .add(b'}');

pub(crate) fn quote_path_segment(segment: &str) -> String {
    utf8_percent_encode(segment, QUOTE_ALL).to_string()
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FinishedDemand {
    pub kind: String,
    pub id: String,
    pub path: String,
}

fn detail_collection(kind: &str) -> Option<&'static str> {
    match kind {
        INVESTMENT => Some("investmentDemands"),
        PAYMENT => Some("paymentDemands"),
        _ => None,
    }
}

/// Validates and rebuilds a demand detail path from an upstream link.
///
/// Refuses any whitespace, control or non-ASCII byte, query/fragment, wrong origin/scheme/port/userinfo,
/// traversal, or non-segment characters before returning the normalized path.
pub fn detail_path(
    href: &str,
    prefix: &str,
    api_origin: &str,
) -> Result<String, SavingsFormatError> {
    if href.is_empty()
        || href.len() > 512
        || !href.bytes().all(|b| (0x21..=0x7e).contains(&b))
        || href.contains('?')
        || href.contains('#')
    {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            "Operation detail outside the saver's space",
        ));
    }

    let abs_prefix = format!("{api_origin}{prefix}");
    let key = if let Some(rest) = href.strip_prefix(prefix) {
        rest
    } else if let Some(rest) = href.strip_prefix(&abs_prefix) {
        rest
    } else {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            "Operation detail outside the saver's space",
        ));
    };

    if !DEMAND_KEY_RE.is_match(key) {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            "Operation detail outside the saver's space",
        ));
    }
    Ok(format!("{prefix}{key}"))
}

/// The finished operations Picsou reads, using production `https://nie.api.natixis.com` origin.
pub fn finished_demands(
    transactions: &Value,
    employee_id: &str,
) -> Result<Vec<FinishedDemand>, SavingsFormatError> {
    finished_demands_with_origin(transactions, employee_id, DEFAULT_API_ORIGIN)
}

/// Like [`finished_demands`], with an explicit allowed API origin (for wiremock tests).
pub fn finished_demands_with_origin(
    transactions: &Value,
    employee_id: &str,
    api_origin: &str,
) -> Result<Vec<FinishedDemand>, SavingsFormatError> {
    let Some(items) = transactions
        .as_object()
        .and_then(|t| t.get("items"))
        .and_then(Value::as_array)
    else {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            "Transactions have no item list",
        ));
    };
    let base = format!(
        "/transaction/v1/employees/{}",
        quote_path_segment(employee_id)
    );
    let mut demands = Vec::new();
    for item_val in items {
        let Some(item) = item_val.as_object() else {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                "A transaction is not an object",
            ));
        };
        let Some(kind) = item.get("type").and_then(Value::as_str) else {
            continue;
        };
        let Some(collection) = detail_collection(kind) else {
            continue;
        };
        if item.get("finished").and_then(Value::as_bool) != Some(true) {
            continue;
        }
        let demand_id = parse_text(item.get("id"), "Transaction id")?;
        let href = item
            .get("_links")
            .and_then(Value::as_object)
            .and_then(|links| links.get("self"))
            .and_then(Value::as_object)
            .and_then(|self_link| self_link.get("href"))
            .and_then(Value::as_str);
        let Some(href) = href else {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                "A transaction has no detail link",
            ));
        };
        let prefix = format!("{base}/{collection}/");
        let path = detail_path(href, &prefix, api_origin)?;
        demands.push(FinishedDemand {
            kind: kind.to_owned(),
            id: demand_id,
            path,
        });
    }
    Ok(demands)
}

/// Stable per line across syncs; hashed when the readable form would not fit.
pub fn external_id(employee_id: &str, parts: &[&str]) -> String {
    let mut all = Vec::with_capacity(parts.len() + 2);
    all.push("natixis");
    all.push(employee_id);
    all.extend_from_slice(parts);
    let readable = all.join("_");
    if readable.chars().count() <= 100 {
        return readable;
    }
    let digest = hex::encode(Sha256::digest(readable.as_bytes()));
    format!("natixis_{employee_id}_{}", &digest[..32])
}

fn resolve_fund_code(
    fund_id: Option<&Value>,
    codes: &HashMap<i64, Option<String>>,
) -> Option<String> {
    let id = fund_id.and_then(Value::as_i64)?;
    Some(
        codes
            .get(&id)
            .and_then(|c| c.as_deref())
            .filter(|c| !c.is_empty())
            .map(str::to_owned)
            .unwrap_or_else(|| format!("NIE-{id}")),
    )
}

fn plan_id(employee_id: &str, product: Option<&Value>) -> Result<String, SavingsFormatError> {
    let id_val = match product {
        None | Some(Value::Null) => None,
        Some(Value::Object(obj)) => obj.get("id"),
        Some(_) => {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                "Operation plan id is missing",
            ));
        }
    };
    let id = parse_text(id_val, "Operation plan id")?;
    Ok(format!("natixis_{employee_id}_{id}"))
}

fn is_truthy(v: &Value) -> bool {
    match v {
        Value::Null => false,
        Value::Bool(b) => *b,
        Value::String(s) => !s.is_empty(),
        Value::Array(a) => !a.is_empty(),
        Value::Object(m) => !m.is_empty(),
        Value::Number(n) => n.to_string() != "0",
    }
}

fn first_truthy<'a>(primary: Option<&'a Value>, fallback: Option<&'a Value>) -> Option<&'a Value> {
    primary.filter(|v| is_truthy(v)).or(fallback)
}

fn build_buy(
    employee_id: &str,
    demand_id: &str,
    line: &Map<String, Value>,
    part: &Map<String, Value>,
    suffix: &str,
    label: &str,
    codes: &HashMap<i64, Option<String>>,
) -> Result<Option<OperationPayload>, SavingsFormatError> {
    match part.get("status") {
        None | Some(Value::Null) => {}
        Some(Value::String(s)) if s == "INVESTED" => {}
        _ => return Ok(None),
    }
    let quantity = parse_decimal(part.get("units"), "Invested units")?;
    if quantity.is_zero() {
        return Ok(None);
    }
    let fees = parse_amount_opt(part, "fees", "Investment fees", false)?;
    let amount = match parse_amount_opt(part, "netInvested", "Invested amount", false)? {
        Some(net_invested) => net_invested,
        None => parse_amount(part, "net", "Invested amount")?,
    };
    let line_id = parse_text(line.get("id"), "Investment line id")?;
    let product = first_truthy(part.get("product"), line.get("product"));
    let plan_external_id = plan_id(employee_id, product)?;
    let date_val = first_truthy(part.get("investmentDate"), line.get("investmentDate"));
    let date = parse_text(date_val, "Investment date")?;
    let fund_label_val = first_truthy(part.get("fundLabel"), line.get("fundLabel"));
    let fund_label = parse_text(fund_label_val, "Fund label")?;
    let fund_id_val = if part.contains_key("fundId") {
        part.get("fundId")
    } else {
        line.get("fundId")
    };
    let fund_code = resolve_fund_code(fund_id_val, codes);
    let unit_value = parse_decimal_opt(part.get("unitValue"), "Unit value", false)?;

    Ok(Some(OperationPayload {
        external_id: external_id(employee_id, &[demand_id, &line_id, suffix]),
        plan_external_id,
        date,
        kind: "BUY".to_owned(),
        label: label.to_owned(),
        fund_label,
        fund_code,
        quantity,
        unit_value,
        amount_eur: amount,
        fees_eur: fees.map(|f| f.abs()),
    }))
}

pub fn investment_operations(
    detail: &Value,
    employee_id: &str,
    codes: &HashMap<i64, Option<String>>,
) -> Result<Vec<OperationPayload>, SavingsFormatError> {
    let Some(detail_obj) = detail.as_object() else {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            "An operation detail is not an object",
        ));
    };
    let demand_id = parse_text(detail_obj.get("id"), "Investment demand id")?;
    let label = parse_text_opt(detail_obj.get("originLabel"), "Investment label", false)?
        .unwrap_or_else(|| "Versement".to_owned());
    let Some(lines) = detail_obj.get("investments").and_then(Value::as_array) else {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            "Investment demand has no lines",
        ));
    };

    let mut operations = Vec::new();
    for line_val in lines {
        let Some(line) = line_val.as_object() else {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                "An investment line is not an object",
            ));
        };
        match line.get("type") {
            None | Some(Value::Null) => {}
            Some(Value::String(s)) if s == "FUND" => {}
            _ => {
                return Err(SavingsFormatError::new(
                    FORMAT_CHANGED,
                    "An investment line is not a fund",
                ));
            }
        }
        if let Some(own) = build_buy(employee_id, &demand_id, line, line, "E", &label, codes)? {
            operations.push(own);
        }
        if let Some(top_up) = line.get("additionalInvestment").and_then(Value::as_object)
            && let Some(employer) = build_buy(
                employee_id,
                &demand_id,
                line,
                top_up,
                "A",
                EMPLOYER_TOP_UP,
                codes,
            )?
        {
            operations.push(employer);
        }
    }
    Ok(operations)
}

fn payment_label(kind: Option<&str>) -> &'static str {
    match kind {
        Some("EARLY_RELEASE") => "Déblocage anticipé",
        Some("MATURITY") => "Déblocage à échéance",
        _ => DEFAULT_PAYMENT_LABEL,
    }
}

pub fn payment_operations(
    detail: &Value,
    employee_id: &str,
    codes: &HashMap<i64, Option<String>>,
) -> Result<Vec<OperationPayload>, SavingsFormatError> {
    let Some(detail_obj) = detail.as_object() else {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            "An operation detail is not an object",
        ));
    };
    let _demand_id = parse_text(detail_obj.get("id"), "Payment demand id")?;
    let label = payment_label(detail_obj.get("type").and_then(Value::as_str));
    let Some(payments) = detail_obj.get("payments").and_then(Value::as_array) else {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            "Payment demand has no payments",
        ));
    };

    let mut operations = Vec::new();
    for payment_val in payments {
        let Some(payment) = payment_val.as_object() else {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                "A payment is not an object",
            ));
        };
        let payment_id = parse_text(payment.get("id"), "Payment id")?;
        let products = match payment.get("products") {
            None | Some(Value::Null) => continue,
            Some(Value::Array(list)) => list,
            Some(_) => {
                return Err(SavingsFormatError::new(
                    FORMAT_CHANGED,
                    "A paid product is not an object",
                ));
            }
        };
        for product_val in products {
            let Some(product) = product_val.as_object() else {
                return Err(SavingsFormatError::new(
                    FORMAT_CHANGED,
                    "A paid product is not an object",
                ));
            };
            let options = match product.get("investmentOptions") {
                None | Some(Value::Null) => continue,
                Some(Value::Array(list)) => list,
                Some(_) => {
                    return Err(SavingsFormatError::new(
                        FORMAT_CHANGED,
                        "A paid line is not a fund",
                    ));
                }
            };
            for option_val in options {
                let Some(option) = option_val.as_object() else {
                    return Err(SavingsFormatError::new(
                        FORMAT_CHANGED,
                        "A paid line is not a fund",
                    ));
                };
                match option.get("type") {
                    None | Some(Value::Null) => {}
                    Some(Value::String(s)) if s == "FUND" => {}
                    _ => {
                        return Err(SavingsFormatError::new(
                            FORMAT_CHANGED,
                            "A paid line is not a fund",
                        ));
                    }
                }
                let quantity = parse_decimal(option.get("units"), "Sold units")?;
                if quantity.is_zero() {
                    continue;
                }
                let disinvestment = option.get("disvestmentValue").and_then(Value::as_object);
                let line_id = parse_text(option.get("id"), "Paid line id")?;
                let date_val = first_truthy(
                    disinvestment.and_then(|d| d.get("date")),
                    payment.get("date"),
                );
                let date = parse_text(date_val, "Disinvestment date")?;
                let unit_value = match disinvestment {
                    Some(d) => parse_amount_opt(d, "amount", "Disinvestment unit value", false)?,
                    None => None,
                };
                let gross = parse_amount(option, "gross", "Sold amount")?;

                operations.push(OperationPayload {
                    external_id: external_id(employee_id, &[&payment_id, &line_id]),
                    plan_external_id: plan_id(employee_id, Some(product_val))?,
                    date,
                    kind: "SELL".to_owned(),
                    label: label.to_owned(),
                    fund_label: parse_text(option.get("label"), "Fund label")?,
                    fund_code: resolve_fund_code(option.get("fundId"), codes),
                    quantity: quantity.abs(),
                    unit_value,
                    amount_eur: gross.abs(),
                    fees_eur: None,
                });
            }
        }
    }
    Ok(operations)
}

/// Funds the operations name, sold-out ones included, in first-seen order.
pub fn operation_fund_ids(details: &[Value]) -> Vec<i64> {
    fn visit(value: &Value, seen: &mut HashSet<i64>, ordered: &mut Vec<i64>) {
        match value {
            Value::Object(map) => {
                if let Some(id) = map.get("fundId").and_then(Value::as_i64)
                    && seen.insert(id)
                {
                    ordered.push(id);
                }
                for v in map.values() {
                    visit(v, seen, ordered);
                }
            }
            Value::Array(list) => {
                for v in list {
                    visit(v, seen, ordered);
                }
            }
            _ => {}
        }
    }

    let mut seen = HashSet::new();
    let mut ordered = Vec::new();
    for detail in details {
        visit(detail, &mut seen, &mut ordered);
    }
    ordered
}

pub fn parse_operations(
    demands: &[FinishedDemand],
    details: &[Value],
    employee_id: &str,
    codes: &HashMap<i64, Option<String>>,
) -> Result<Vec<OperationPayload>, SavingsFormatError> {
    let mut operations = Vec::new();
    for (demand, detail) in demands.iter().zip(details.iter()) {
        if !detail.is_object() {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                "An operation detail is not an object",
            ));
        }
        if demand.kind == INVESTMENT {
            operations.extend(investment_operations(detail, employee_id, codes)?);
        } else {
            operations.extend(payment_operations(detail, employee_id, codes)?);
        }
    }

    let mut seen_ids = HashSet::with_capacity(operations.len());
    for op in &operations {
        if !seen_ids.insert(op.external_id.as_str()) {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                "Two operations share an id",
            ));
        }
        if op.amount_eur < Decimal::ZERO || op.quantity <= Decimal::ZERO {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                "An operation has a negative amount",
            ));
        }
    }
    Ok(operations)
}
