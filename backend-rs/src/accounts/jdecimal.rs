//! Jackson writes a BigDecimal with BigDecimal.toString(): plain notation unless the adjusted
//! exponent is below -6, so a zero read from numeric(20,8) is `0E-8`, not `0.00000000`. Response
//! fields use `#[serde(with = "crate::accounts::jdecimal")]` (or `::option`) to match it byte for byte.

use rust_decimal::Decimal;
use serde::Serializer;
use serde::ser::SerializeStruct;

/// BigDecimal.toString() of the same unscaled value and scale.
pub fn to_java_string(value: &Decimal) -> String {
    let scale = value.scale() as i64;
    let unscaled = value.mantissa();
    let negative = unscaled < 0;
    let digits = unscaled.unsigned_abs().to_string();
    let adjusted = digits.len() as i64 - 1 - scale;
    let sign = if negative { "-" } else { "" };
    if scale == 0 {
        return format!("{sign}{digits}");
    }
    if adjusted >= -6 {
        let point = digits.len() as i64 - scale;
        return if point > 0 {
            let (int, frac) = digits.split_at(point as usize);
            format!("{sign}{int}.{frac}")
        } else {
            format!("{sign}0.{}{digits}", "0".repeat((-point) as usize))
        };
    }
    let (first, rest) = digits.split_at(1);
    let mantissa = if rest.is_empty() {
        first.to_owned()
    } else {
        format!("{first}.{rest}")
    };
    format!("{sign}{mantissa}E{adjusted}")
}

/// Writes the number token itself (serde_json's arbitrary_precision), as rust_decimal does.
pub fn serialize<S: Serializer>(value: &Decimal, serializer: S) -> Result<S::Ok, S::Error> {
    let mut s = serializer.serialize_struct("$serde_json::private::Number", 1)?;
    s.serialize_field("$serde_json::private::Number", &to_java_string(value))?;
    s.end()
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

/// A Java `double` field (percentages Java computes in floating point and rounds to a few
/// places): Double.toString prints at least one fractional digit, so 100 is `100.0`.
pub mod double {
    use rust_decimal::Decimal;
    use serde::Serializer;

    pub fn to_java_double_string(value: &Decimal) -> String {
        let n = value.normalize();
        if n.scale() == 0 {
            format!("{n}.0")
        } else {
            n.to_string()
        }
    }

    pub fn serialize<S: Serializer>(value: &Decimal, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeStruct;
        let mut s = serializer.serialize_struct("$serde_json::private::Number", 1)?;
        s.serialize_field(
            "$serde_json::private::Number",
            &to_java_double_string(value),
        )?;
        s.end()
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
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::str::FromStr;

    #[test]
    fn matches_big_decimal_to_string() {
        for (input, java) in [
            ("0.00000000", "0E-8"),
            ("0", "0"),
            ("1250.50000000", "1250.50000000"),
            ("-200.00000000", "-200.00000000"),
            ("0.00000001", "1E-8"),
            ("0.0000012", "0.0000012"),
            ("0.000001", "0.000001"),
            ("0.0000001", "1E-7"),
            ("-0.00000012", "-1.2E-7"),
            ("1500.75", "1500.75"),
            ("0.0", "0.0"),
        ] {
            assert_eq!(
                to_java_string(&Decimal::from_str(input).unwrap()),
                java,
                "{input}"
            );
        }
        assert_eq!(
            double::to_java_double_string(&Decimal::from_str("100.00").unwrap()),
            "100.0"
        );
        assert_eq!(
            double::to_java_double_string(&Decimal::from_str("15.22").unwrap()),
            "15.22"
        );
    }
}
