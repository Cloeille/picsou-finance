//! Small Java-compatible helpers for the Enable Banking payloads.

use std::str::FromStr;

use rust_decimal::Decimal;
use serde::{Deserialize, Deserializer};
use sha2::{Digest, Sha256};

use crate::ledger::sync_values::{java_is_blank, java_trim};

/// The first value that is not blank, trimmed.
pub fn first_non_blank(values: &[Option<&str>]) -> Option<String> {
    values
        .iter()
        .flatten()
        .find(|v| !java_is_blank(v))
        .map(|v| java_trim(v).to_owned())
}

/// A JSON string, or a number read as its text (Jackson coerces a number into a String field).
pub fn lenient_string<'de, D: Deserializer<'de>>(
    deserializer: D,
) -> Result<Option<String>, D::Error> {
    Ok(
        match Option::<serde_json::Value>::deserialize(deserializer)? {
            Some(serde_json::Value::String(s)) => Some(s),
            Some(serde_json::Value::Number(n)) => Some(n.to_string()),
            Some(serde_json::Value::Bool(b)) => Some(b.to_string()),
            _ => None,
        },
    )
}

/// new BigDecimal(text): an optional sign, digits with at most one point, an optional exponent.
pub fn parse_big_decimal(text: &str) -> Option<Decimal> {
    let body = text.strip_prefix(['+', '-']).unwrap_or(text);
    let (mantissa, exponent) = match body.find(['e', 'E']) {
        Some(i) => (&body[..i], Some(&body[i + 1..])),
        None => (body, None),
    };
    let digits = mantissa.chars().filter(|c| c.is_ascii_digit()).count();
    let points = mantissa.chars().filter(|c| *c == '.').count();
    if digits == 0 || points > 1 || digits + points != mantissa.len() {
        return None;
    }
    if let Some(exp) = exponent {
        let exp = exp.strip_prefix(['+', '-']).unwrap_or(exp);
        if exp.is_empty() || !exp.chars().all(|c| c.is_ascii_digit()) {
            return None;
        }
        return Decimal::from_scientific(text).ok();
    }
    Decimal::from_str(text).ok()
}

/// String.substring(0, max) on UTF-16 units, never splitting a character.
pub fn truncate_utf16(value: &str, max: usize) -> String {
    let mut units = 0;
    let mut out = String::new();
    for c in value.chars() {
        units += c.len_utf16();
        if units > max {
            break;
        }
        out.push(c);
    }
    out
}

/// LogSanitizer.fingerprint: enough to correlate log lines, far too little to reverse.
pub fn fingerprint(value: Option<&str>) -> String {
    match value {
        Some(v) if !java_is_blank(v) => {
            let digest = Sha256::digest(v.as_bytes());
            format!("sha256:{}", hex::encode(&digest[..4]))
        }
        _ => "<none>".to_owned(),
    }
}

/// String.split("::"): trailing empty segments dropped.
pub fn java_split(value: &str) -> Vec<&str> {
    let mut parts: Vec<&str> = value.split("::").collect();
    while parts.len() > 1 && parts.last().is_some_and(|p| p.is_empty()) {
        parts.pop();
    }
    if parts.len() == 1 && parts[0].is_empty() && !value.is_empty() {
        parts.clear();
    }
    parts
}

/// LogSanitizer.safe: at most 500 characters, line breaks and control characters but TAB as "?".
pub fn safe(value: &str) -> String {
    let mut text: String = value.chars().take(500).collect();
    if value.chars().count() > 500 {
        text.push_str("...");
    }
    text.chars()
        .map(|c| {
            if c.is_control() && c != '\t' || matches!(c, '\u{2028}' | '\u{2029}') {
                '?'
            } else {
                c
            }
        })
        .collect()
}
