//! Maps Natixis Interépargne `savings` and `products` responses to Picsou plans.
//!
//! Faithful port of `services/natixis-auth/savings_parser.py`.

use std::collections::{HashMap, HashSet};
use std::fmt;
use std::str::FromStr;

use rust_decimal::Decimal;
use serde_json::{Map, Value};

use super::contract::{PlanPayload, PositionPayload};

pub const FORMAT_CHANGED: &str = "UPSTREAM_FORMAT_CHANGED";
pub const PORTFOLIO_INCOMPLETE: &str = "PORTFOLIO_INCOMPLETE";

// Same tolerance as the backend's SyncValues.moneyClose.
const MONEY_ABSOLUTE_TOLERANCE: Decimal = Decimal::from_parts(5, 0, 0, false, 2); // 0.05
const MONEY_RELATIVE_TOLERANCE: Decimal = Decimal::from_parts(1, 0, 0, false, 3); // 0.001

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SavingsFormatError {
    pub code: &'static str,
    pub message: String,
}

impl SavingsFormatError {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

impl fmt::Display for SavingsFormatError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for SavingsFormatError {}

pub fn money_close(actual: Decimal, expected: Decimal) -> bool {
    let tolerance = MONEY_ABSOLUTE_TOLERANCE.max(expected.abs() * MONEY_RELATIVE_TOLERANCE);
    (actual - expected).abs() <= tolerance
}

fn parse_decimal_str(raw: &str, what: &str) -> Result<Decimal, SavingsFormatError> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            format!("{what} is not a number"),
        ));
    }
    Decimal::from_str(trimmed)
        .or_else(|_| Decimal::from_scientific(trimmed))
        .map_err(|_| SavingsFormatError::new(FORMAT_CHANGED, format!("{what} is not a number")))
}

pub(crate) fn parse_decimal_opt(
    value: Option<&Value>,
    what: &str,
    required: bool,
) -> Result<Option<Decimal>, SavingsFormatError> {
    match value {
        None | Some(Value::Null) => {
            if required {
                Err(SavingsFormatError::new(
                    FORMAT_CHANGED,
                    format!("{what} is not a number"),
                ))
            } else {
                Ok(None)
            }
        }
        Some(Value::Number(n)) => parse_decimal_str(&n.to_string(), what).map(Some),
        Some(Value::String(s)) => parse_decimal_str(s, what).map(Some),
        Some(_) => Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            format!("{what} is not a number"),
        )),
    }
}

pub(crate) fn parse_decimal(
    value: Option<&Value>,
    what: &str,
) -> Result<Decimal, SavingsFormatError> {
    parse_decimal_opt(value, what, true)?
        .ok_or_else(|| SavingsFormatError::new(FORMAT_CHANGED, format!("{what} is not a number")))
}

pub(crate) fn parse_amount_opt(
    holder: &Map<String, Value>,
    key: &str,
    what: &str,
    required: bool,
) -> Result<Option<Decimal>, SavingsFormatError> {
    let Some(node) = holder.get(key) else {
        if required {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                format!("{what} is missing"),
            ));
        }
        return Ok(None);
    };
    if node.is_null() {
        if required {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                format!("{what} is missing"),
            ));
        }
        return Ok(None);
    }
    let Some(obj) = node.as_object() else {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            format!("{what} is not an amount"),
        ));
    };
    let value = obj.get("value");
    if matches!(value, None | Some(Value::Null)) && !required {
        return Ok(None);
    }
    // Natixis tags every amount with its currency. The backend books everything
    // in EUR, so any other or a missing code fails closed instead of being
    // stored as euros.
    if obj.get("currencyCode").and_then(Value::as_str) != Some("EUR") {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            format!("{what} is not in EUR"),
        ));
    }
    parse_decimal_opt(value, what, required)
}

pub(crate) fn parse_amount(
    holder: &Map<String, Value>,
    key: &str,
    what: &str,
) -> Result<Decimal, SavingsFormatError> {
    parse_amount_opt(holder, key, what, true)?
        .ok_or_else(|| SavingsFormatError::new(FORMAT_CHANGED, format!("{what} is missing")))
}

pub(crate) fn parse_text_opt(
    value: Option<&Value>,
    what: &str,
    required: bool,
) -> Result<Option<String>, SavingsFormatError> {
    if let Some(Value::String(s)) = value {
        let trimmed = s.trim();
        if !trimmed.is_empty() {
            return Ok(Some(trimmed.to_owned()));
        }
    }
    if required {
        Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            format!("{what} is missing"),
        ))
    } else {
        Ok(None)
    }
}

pub(crate) fn parse_text(value: Option<&Value>, what: &str) -> Result<String, SavingsFormatError> {
    parse_text_opt(value, what, true)?
        .ok_or_else(|| SavingsFormatError::new(FORMAT_CHANGED, format!("{what} is missing")))
}

fn items<'a>(payload: &'a Value, what: &str) -> Result<&'a Vec<Value>, SavingsFormatError> {
    payload
        .as_object()
        .and_then(|obj| obj.get("items"))
        .and_then(Value::as_array)
        .ok_or_else(|| SavingsFormatError::new(FORMAT_CHANGED, format!("{what} has no item list")))
}

fn collect_compartments<'a>(
    holder: &'a Map<String, Value>,
    depth: usize,
    out: &mut Vec<&'a Map<String, Value>>,
) -> Result<(), SavingsFormatError> {
    if depth > 5 {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            "Compartments nest too deep",
        ));
    }
    let key = if depth == 0 {
        "compartments"
    } else {
        "subCompartments"
    };
    let Some(node) = holder.get(key) else {
        return Ok(());
    };
    if node.is_null() {
        return Ok(());
    }
    let Some(list) = node.as_array() else {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            "A compartment is not an object",
        ));
    };
    for item in list {
        let Some(compartment) = item.as_object() else {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                "A compartment is not an object",
            ));
        };
        out.push(compartment);
        collect_compartments(compartment, depth + 1, out)?;
    }
    Ok(())
}

fn collect_options(
    plan: &Map<String, Value>,
) -> Result<Vec<&Map<String, Value>>, SavingsFormatError> {
    let mut compartments = Vec::new();
    collect_compartments(plan, 0, &mut compartments)?;
    let mut options = Vec::new();
    for compartment in compartments {
        let Some(node) = compartment.get("investmentOptions") else {
            continue;
        };
        if node.is_null() {
            continue;
        }
        let Some(list) = node.as_array() else {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                "An investment option is not an object",
            ));
        };
        for item in list {
            let Some(option) = item.as_object() else {
                return Err(SavingsFormatError::new(
                    FORMAT_CHANGED,
                    "An investment option is not an object",
                ));
            };
            options.push(option);
        }
    }
    Ok(options)
}

/// Every fund the plans hold, once, in the order they first appear.
pub fn fund_ids(savings: &Value) -> Result<Vec<i64>, SavingsFormatError> {
    let mut seen = HashSet::new();
    let mut ordered = Vec::new();
    for plan_val in items(savings, "savings")? {
        let Some(plan) = plan_val.as_object() else {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                "A plan is not an object",
            ));
        };
        for option in collect_options(plan)? {
            if let Some(fund_id) = option.get("fundId").and_then(Value::as_i64)
                && seen.insert(fund_id)
            {
                ordered.push(fund_id);
            }
        }
    }
    Ok(ordered)
}

/// The fund's AMF code (`QS…`) when Natixis lists one; FCPE have no ISIN.
pub fn fund_code(detail: &Value) -> Option<String> {
    let identifiers = detail.as_object()?.get("identifiers")?.as_array()?;
    for identifier in identifiers {
        let Some(obj) = identifier.as_object() else {
            continue;
        };
        if obj.get("type").and_then(Value::as_str) == Some("QS")
            && let Some(value) = obj.get("value").and_then(Value::as_str)
        {
            let len = value.chars().count();
            if len > 0 && len <= 20 {
                return Some(value.to_owned());
            }
        }
    }
    None
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
enum PositionKey {
    FundId(i64),
    Label(String),
}

fn parse_positions(
    plan: &Map<String, Value>,
    codes: &HashMap<i64, Option<String>>,
) -> Result<Vec<PositionPayload>, SavingsFormatError> {
    let mut index_by_key: HashMap<PositionKey, usize> = HashMap::new();
    let mut merged: Vec<PositionPayload> = Vec::new();

    for option in collect_options(plan)? {
        match option.get("type") {
            None | Some(Value::Null) => {}
            Some(Value::String(s)) if s == "FUND" => {}
            _ => {
                return Err(SavingsFormatError::new(
                    FORMAT_CHANGED,
                    "An investment option is not a fund",
                ));
            }
        }
        let fund_id = option.get("fundId").and_then(Value::as_i64);
        let label = parse_text(option.get("label"), "Fund label")?;
        let key = match fund_id {
            Some(id) => PositionKey::FundId(id),
            None => {
                let k = PositionKey::Label(label.clone());
                if index_by_key.contains_key(&k) {
                    return Err(SavingsFormatError::new(
                        FORMAT_CHANGED,
                        "Two fund lines share a label and have no fund id",
                    ));
                }
                k
            }
        };
        let quantity = parse_decimal(option.get("units"), "Fund units")?;
        let value = parse_amount(option, "amount", "Fund amount")?;
        let pnl = parse_amount_opt(option, "gainsOrLossesAmount", "Fund gains", false)?;
        let unit_value = match option.get("lastValue").and_then(Value::as_object) {
            Some(last_value) => parse_amount_opt(last_value, "amount", "Fund unit value", false)?,
            None => None,
        };

        if let Some(&idx) = index_by_key.get(&key) {
            let line = &mut merged[idx];
            line.quantity += quantity;
            line.value_eur += value;
            line.pnl_eur = match (line.pnl_eur, pnl) {
                (Some(existing), Some(added)) => Some(existing + added),
                _ => None,
            };
        } else {
            let resolved_fund_code = fund_id.map(|id| {
                codes
                    .get(&id)
                    .and_then(|c| c.as_deref())
                    .filter(|c| !c.is_empty())
                    .map(str::to_owned)
                    .unwrap_or_else(|| format!("NIE-{id}"))
            });
            let idx = merged.len();
            merged.push(PositionPayload {
                isin: None,
                fund_code: resolved_fund_code,
                label,
                quantity,
                unit_value,
                value_eur: value,
                pnl_eur: pnl,
            });
            index_by_key.insert(key, idx);
        }
    }
    Ok(merged)
}

fn is_truthy_json(v: &Value) -> bool {
    match v {
        Value::Null => false,
        Value::Bool(b) => *b,
        Value::String(s) => !s.is_empty(),
        Value::Array(a) => !a.is_empty(),
        Value::Object(m) => !m.is_empty(),
        Value::Number(n) => n.to_string() != "0",
    }
}

pub fn parse_plans(
    savings: &Value,
    products: &Value,
    employee_id: &str,
    codes: &HashMap<i64, Option<String>>,
) -> Result<Vec<PlanPayload>, SavingsFormatError> {
    let mut plans = Vec::new();
    let mut seen_ids = HashSet::new();

    for plan_val in items(savings, "savings")? {
        let Some(plan) = plan_val.as_object() else {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                "A plan is not an object",
            ));
        };
        let plan_id = parse_text(plan.get("id"), "Plan id")?;
        let balance = parse_amount(plan, "amount", "Plan amount")?;
        let positions = parse_positions(plan, codes)?;
        let lines_total: Decimal = positions.iter().map(|p| p.value_eur).sum();
        if !money_close(lines_total, balance) {
            return Err(SavingsFormatError::new(
                PORTFOLIO_INCOMPLETE,
                "Fund lines do not add up to the plan amount",
            ));
        }
        let external_id = format!("natixis_{employee_id}_{plan_id}");
        if external_id.chars().count() > 100 {
            return Err(SavingsFormatError::new(FORMAT_CHANGED, "Plan id too long"));
        }
        if !seen_ids.insert(external_id.clone()) {
            return Err(SavingsFormatError::new(
                FORMAT_CHANGED,
                "Two plans share an id",
            ));
        }
        let name = match parse_text_opt(plan.get("displayableProductLabel"), "Plan label", false)? {
            Some(label) => label,
            None => parse_text(plan.get("family"), "Plan family")?,
        };
        let plan_kind = parse_text_opt(plan.get("family"), "Plan family", false)?;
        let employer = parse_text_opt(plan.get("conventionDenomination"), "Employer", false)?;
        plans.push(PlanPayload {
            external_id,
            name,
            plan_kind,
            employer,
            balance_eur: balance,
            positions,
            snapshot_complete: true,
        });
    }

    // `products` is computed separately: the plans must account for its whole total.
    let Some(info) = products
        .as_object()
        .and_then(|p| p.get("additionalInformation"))
        .and_then(Value::as_object)
    else {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            "Products carry no totals",
        ));
    };
    let managed = info
        .get("internalAmountsManagedByNIE")
        .filter(|v| is_truthy_json(v))
        .or_else(|| info.get("internalAmounts"))
        .and_then(Value::as_object);
    let Some(managed) = managed else {
        return Err(SavingsFormatError::new(
            FORMAT_CHANGED,
            "Products carry no Natixis total",
        ));
    };
    let products_total = parse_amount(managed, "total", "Products total")?;

    // Totals alone let a small plan go missing inside the tolerance, and the backend
    // closes a plan it no longer sees: the plan count must match too.
    let natixis_products = items(products, "products")?
        .iter()
        .filter(|item| {
            item.as_object()
                .and_then(|o| o.get("internalAccount"))
                .and_then(Value::as_bool)
                == Some(true)
        })
        .count();
    if natixis_products != plans.len() {
        return Err(SavingsFormatError::new(
            PORTFOLIO_INCOMPLETE,
            "Plans and products list a different number of plans",
        ));
    }
    let plans_total: Decimal = plans.iter().map(|p| p.balance_eur).sum();
    if !money_close(plans_total, products_total) {
        return Err(SavingsFormatError::new(
            PORTFOLIO_INCOMPLETE,
            "Plans do not add up to the products total",
        ));
    }
    Ok(plans)
}
