//! Java number and time renderings the providers reproduce: BigDecimal.valueOf(double), the
//! Retry-After clamp and LocalDateTime.toString.

use std::str::FromStr;
use std::time::Duration;

use chrono::{NaiveDateTime, Timelike};
use rust_decimal::Decimal;
use serde_json::Value;

/// Applied when a 429 carries no usable Retry-After.
pub const DEFAULT_COOLDOWN: Duration = Duration::from_secs(60);
/// Ceiling on a server-supplied Retry-After.
pub const MAX_COOLDOWN: Duration = Duration::from_secs(15 * 60);

/// The pause a 429 buys: the delta-seconds Retry-After when sane, a minute otherwise, never more
/// than fifteen.
pub fn retry_after(header: Option<&str>) -> Duration {
    let Some(header) = header.map(str::trim).filter(|h| !h.is_empty()) else {
        return DEFAULT_COOLDOWN;
    };
    match header.parse::<i64>() {
        Ok(seconds) if seconds > 0 => {
            Duration::from_secs(seconds.unsigned_abs().min(MAX_COOLDOWN.as_secs()))
        }
        _ => DEFAULT_COOLDOWN,
    }
}

/// BigDecimal.valueOf(double) for a JSON number Jackson read as a double: the digits of
/// Double.toString, so at least one fraction digit ("100" is 100.0) and, in the scientific range
/// (below 10^-3, from 10^7), the scale the mantissa's digits give. A JSON writer prints the
/// shortest round-trip form, so the source text already holds Double.toString's digits; reading
/// it as text keeps floats out of the money path. A value Java would give a negative scale
/// (1.0E7) keeps scale 0, which rust_decimal cannot go below.
pub fn java_double(raw: &str) -> Option<Decimal> {
    let raw = raw.trim();
    let value = if raw.contains(['e', 'E']) {
        Decimal::from_scientific(raw).ok()?
    } else {
        Decimal::from_str(raw).ok()?
    };
    let value = value.normalize();
    if value.is_zero() {
        return Some(Decimal::new(0, 1));
    }
    let abs = value.abs();
    if abs >= Decimal::new(1, 3) && abs < Decimal::from(10_000_000) {
        let mut out = value;
        if out.scale() == 0 {
            out.rescale(1);
        }
        return Some(out);
    }
    let mut mantissa = value.mantissa().unsigned_abs();
    let mut scale = i64::from(value.scale());
    while mantissa % 10 == 0 {
        mantissa /= 10;
        scale -= 1;
    }
    let digits = i64::try_from(mantissa.to_string().len()).unwrap_or(1);
    let exponent = (digits - 1) - scale;
    let fraction = (digits - 1).max(1);
    let target = fraction - exponent;
    let mut out = value;
    if (0..=28).contains(&target) {
        out.rescale(u32::try_from(target).unwrap_or(0));
    }
    Some(out)
}

/// java_double over a JSON value; None for anything but a number.
pub fn json_double(value: &Value) -> Option<Decimal> {
    match value {
        Value::Number(n) => java_double(&n.to_string()),
        _ => None,
    }
}

/// A JSON integer (Jackson's Long), None for anything else.
pub fn json_i64(value: &Value) -> Option<i64> {
    match value {
        Value::Number(n) => n.as_i64().or_else(|| n.to_string().parse::<i64>().ok()),
        _ => None,
    }
}

/// The exact decimal of a JSON number (Jackson's BigDecimal), None for anything else.
pub fn json_decimal(value: &Value) -> Option<Decimal> {
    match value {
        Value::Number(n) => {
            let text = n.to_string();
            if text.contains(['e', 'E']) {
                Decimal::from_scientific(&text).ok()
            } else {
                Decimal::from_str(&text).ok()
            }
        }
        _ => None,
    }
}

/// LocalDateTime.toString: seconds only when not zero, then the fraction in groups of three.
pub fn java_local_date_time(at: NaiveDateTime) -> String {
    let mut out = at.format("%Y-%m-%dT%H:%M").to_string();
    let nanos = at.nanosecond() % 1_000_000_000;
    if at.second() != 0 || nanos != 0 {
        out.push_str(&format!(":{:02}", at.second()));
        if nanos != 0 {
            if nanos.is_multiple_of(1_000_000) {
                out.push_str(&format!(".{:03}", nanos / 1_000_000));
            } else if nanos.is_multiple_of(1_000) {
                out.push_str(&format!(".{:06}", nanos / 1_000));
            } else {
                out.push_str(&format!(".{nanos:09}"));
            }
        }
    }
    out
}

/// An ordered map with LinkedHashMap's put: a repeated key keeps its first position and takes the
/// last value.
pub fn put<K: PartialEq>(series: &mut Vec<(K, Decimal)>, key: K, value: Decimal) {
    if let Some(slot) = series.iter_mut().find(|(k, _)| *k == key) {
        slot.1 = value;
    } else {
        series.push((key, value));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn java_double_matches_double_to_string() {
        let s = |raw: &str| java_double(raw).map(|d| d.to_string());
        assert_eq!(s("100").as_deref(), Some("100.0"));
        assert_eq!(s("123.4500").as_deref(), Some("123.45"));
        assert_eq!(s("0").as_deref(), Some("0.0"));
        assert_eq!(s("0.00001").as_deref(), Some("0.000010"));
        assert_eq!(s("1.234e-5").as_deref(), Some("0.00001234"));
        assert_eq!(s("12345678.9").as_deref(), Some("12345678.9"));
        assert_eq!(s("0.001").as_deref(), Some("0.001"));
    }

    #[test]
    fn retry_after_clamps() {
        assert_eq!(retry_after(None), DEFAULT_COOLDOWN);
        assert_eq!(retry_after(Some("0")), DEFAULT_COOLDOWN);
        assert_eq!(retry_after(Some("abc")), DEFAULT_COOLDOWN);
        assert_eq!(retry_after(Some(" 30 ")), Duration::from_secs(30));
        assert_eq!(retry_after(Some("86400")), MAX_COOLDOWN);
    }

    #[test]
    fn local_date_time_omits_zero_seconds() {
        let at = chrono::NaiveDate::from_ymd_opt(2026, 3, 14)
            .unwrap()
            .and_hms_opt(9, 30, 0)
            .unwrap();
        assert_eq!(java_local_date_time(at), "2026-03-14T09:30");
        let at = at.with_second(5).unwrap();
        assert_eq!(java_local_date_time(at), "2026-03-14T09:30:05");
    }
}
