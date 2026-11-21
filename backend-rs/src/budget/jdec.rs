//! Decimals written as Jackson writes a BigDecimal: BigDecimal.toString(), so the scale is kept
//! and a value whose adjusted exponent is below -6 (a zero of scale 8) is written "0E-8".

use rust_decimal::Decimal;
use serde::Serializer;

/// BigDecimal.toString() of a value with the same unscaled value and scale.
pub fn java_string(value: &Decimal) -> String {
    let scale = i64::from(value.scale());
    let mantissa = value.mantissa();
    let negative = mantissa < 0;
    let digits = mantissa.unsigned_abs().to_string();
    let sign = if negative { "-" } else { "" };
    if scale == 0 {
        return format!("{sign}{digits}");
    }
    let adjusted = -scale + i64::try_from(digits.len()).unwrap_or(i64::MAX) - 1;
    if adjusted >= -6 {
        let len = i64::try_from(digits.len()).unwrap_or(i64::MAX);
        if len > scale {
            let point = usize::try_from(len - scale).unwrap_or(0);
            format!("{sign}{}.{}", &digits[..point], &digits[point..])
        } else {
            let zeros = "0".repeat(usize::try_from(scale - len).unwrap_or(0));
            format!("{sign}0.{zeros}{digits}")
        }
    } else {
        let (first, rest) = digits.split_at(1);
        let fraction = if rest.is_empty() {
            String::new()
        } else {
            format!(".{rest}")
        };
        format!("{sign}{first}{fraction}E{adjusted}")
    }
}

pub fn serialize<S: Serializer>(value: &Decimal, serializer: S) -> Result<S::Ok, S::Error> {
    use serde::Serialize;
    use std::str::FromStr;
    serde_json::Number::from_str(&java_string(value))
        .map_err(serde::ser::Error::custom)?
        .serialize(serializer)
}

pub mod option {
    use rust_decimal::Decimal;
    use serde::Serializer;

    pub fn serialize<S: Serializer>(
        value: &Option<Decimal>,
        serializer: S,
    ) -> Result<S::Ok, S::Error> {
        match value {
            Some(v) => super::serialize(v, serializer),
            None => serializer.serialize_none(),
        }
    }
}

/// -x without the negative zero rust_decimal keeps (BigDecimal.negate of a zero is a zero).
pub fn negate(value: Decimal) -> Decimal {
    if value.is_zero() {
        Decimal::from_i128_with_scale(0, value.scale())
    } else {
        -value
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(v: &str) -> String {
        java_string(&v.parse().unwrap())
    }

    #[test]
    fn matches_big_decimal_to_string() {
        assert_eq!(s("0.00000000"), "0E-8");
        assert_eq!(s("0.0000"), "0.0000");
        assert_eq!(s("123.45000000"), "123.45000000");
        assert_eq!(s("-0.5"), "-0.5");
        assert_eq!(s("400"), "400");
        assert_eq!(s("0.00000001"), "1E-8");
        assert_eq!(s("0.00000123"), "0.00000123");
        assert_eq!(s("-0.0000000120"), "-1.20E-8");
        assert_eq!(s("27.4000"), "27.4000");
    }
}
