//! WiseJson: the profile list and balance list responses.

use std::str::FromStr;

use rust_decimal::Decimal;
use rust_decimal::prelude::ToPrimitive;
use serde_json::{Map, Number, Value};

use super::WiseJar;
use super::error::WiseError;
use crate::ledger::sync_values::{java_is_blank, java_trim};

const UNREADABLE: &str = "Wise returned a response Picsou could not read.";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct Profile {
    pub id: i64,
    pub business: bool,
}

pub(super) fn profiles(json: &str) -> Result<Vec<Profile>, WiseError> {
    Ok(array(json)?
        .iter()
        .filter_map(Value::as_object)
        .filter_map(|node| {
            let id = node.get("id").and_then(as_long)?;
            let business = text(node, "type").is_some_and(|t| t.eq_ignore_ascii_case("BUSINESS"));
            Some(Profile { id, business })
        })
        .collect())
}

/// Jars only. A row without an id, a currency or a readable amount is skipped.
pub(super) fn jars(json: &str, profile: Profile) -> Result<Vec<WiseJar>, WiseError> {
    Ok(array(json)?
        .iter()
        .filter_map(Value::as_object)
        .filter(|node| text(node, "type").is_some_and(|t| t.eq_ignore_ascii_case("SAVINGS")))
        .filter_map(|node| {
            let total_worth =
                amount(node.get("totalWorth")).or_else(|| amount(node.get("amount")))?;
            let balance_id = node.get("id").and_then(as_long)?;
            let currency = text(node, "currency")?;
            Some(WiseJar {
                balance_id,
                business: profile.business,
                name: text(node, "name"),
                currency: currency.to_uppercase(),
                total_worth,
            })
        })
        .collect())
}

/// The first JSON value of the body, as Jackson's readTree reads it (trailing content ignored).
/// The parser's message is never kept: it quotes the body, which is account data.
fn array(json: &str) -> Result<Vec<Value>, WiseError> {
    match serde_json::Deserializer::from_str(json)
        .into_iter::<Value>()
        .next()
    {
        Some(Ok(Value::Array(items))) => Ok(items),
        _ => Err(WiseError::sync(UNREADABLE)),
    }
}

fn amount(money: Option<&Value>) -> Option<Decimal> {
    match money?.as_object()?.get("value")? {
        Value::Number(n) => decimal(n),
        _ => None,
    }
}

fn decimal(n: &Number) -> Option<Decimal> {
    let text = n.to_string();
    Decimal::from_str(&text)
        .or_else(|_| Decimal::from_scientific(&text))
        .ok()
}

/// JsonNode.canConvertToLong then asLong: an integral number in range, or a fractional one in
/// range truncated.
fn as_long(value: &Value) -> Option<i64> {
    let Value::Number(n) = value else {
        return None;
    };
    n.as_i64().or_else(|| decimal(n)?.trunc().to_i64())
}

fn text(node: &Map<String, Value>, field: &str) -> Option<String> {
    match node.get(field) {
        Some(Value::String(s)) if !java_is_blank(s) => Some(java_trim(s).to_owned()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PERSONAL: Profile = Profile {
        id: 11,
        business: false,
    };

    #[test]
    fn profiles_reads_ids_and_the_business_type() {
        let parsed = profiles(r#"[{"id": 11, "type": "PERSONAL"}, {"id": 22, "type": "business"}, {"type": "PERSONAL"}, {"id": "33"}]"#)
            .unwrap();
        assert_eq!(
            parsed,
            vec![
                Profile {
                    id: 11,
                    business: false
                },
                Profile {
                    id: 22,
                    business: true
                }
            ]
        );
    }

    #[test]
    fn jars_keep_savings_rows_with_total_worth_first() {
        let parsed = jars(
            r#"[
              {"id": 101, "currency": "EUR", "type": "STANDARD", "totalWorth": {"value": 3399.46}},
              {"id": 102, "currency": "EUR", "type": "SAVINGS", "name": " Coup dur ", "amount": {"value": 1}, "totalWorth": {"value": 2002.08}},
              {"id": 103, "currency": "usd", "type": "savings", "name": null, "amount": {"value": 10.00}},
              {"id": 104, "currency": "EUR", "type": "SAVINGS", "totalWorth": {"value": 1e3}}
            ]"#,
            PERSONAL,
        )
        .unwrap();
        assert_eq!(parsed.len(), 3);
        assert_eq!(parsed[0].name.as_deref(), Some("Coup dur"));
        assert_eq!(parsed[0].total_worth.to_string(), "2002.08");
        assert_eq!(parsed[1].currency, "USD");
        assert_eq!(parsed[1].name, None);
        assert_eq!(parsed[1].total_worth.to_string(), "10.00");
        assert_eq!(parsed[2].total_worth, Decimal::from(1000));
    }

    #[test]
    fn a_body_that_is_not_an_array_is_unreadable() {
        for body in ["", "{}", "not json", r#"{"profiles": []}"#, "null"] {
            let error = profiles(body).unwrap_err();
            assert_eq!(error.to_string(), UNREADABLE, "{body}");
        }
    }

    #[test]
    fn trailing_content_after_the_array_is_ignored_like_jackson() {
        assert_eq!(profiles(r#"[{"id": 1}] trailing"#).unwrap().len(), 1);
    }
}
