//! The bean-validation constraints of the wealth DTOs that crate::http::json::Errors lacks:
//! @DecimalMin, @DecimalMax, @Digits, @Min, @Max and @Positive, with Hibernate's messages.

use rust_decimal::Decimal;

use crate::http::json::Errors;

/// @DecimalMin(value) inclusive, with its default message; null is valid.
pub fn decimal_min(errors: &mut Errors, field: &str, value: Option<Decimal>, min: &str) {
    decimal_min_msg(errors, field, value, min, None);
}

/// @DecimalMin(value, message); null is valid.
pub fn decimal_min_msg(
    errors: &mut Errors,
    field: &str,
    value: Option<Decimal>,
    min: &str,
    message: Option<&str>,
) {
    let bound: Decimal = min.parse().expect("a decimal bound");
    if value.is_some_and(|v| v < bound) {
        let message = message.map_or_else(
            || {
                errors.default_message(
                    &format!("must be greater than or equal to {min}"),
                    &format!("doit être supérieur ou égal à {min}"),
                )
            },
            str::to_owned,
        );
        errors.add(field, message);
    }
}

/// @DecimalMax(value, message) inclusive; null is valid.
pub fn decimal_max_msg(
    errors: &mut Errors,
    field: &str,
    value: Option<Decimal>,
    max: &str,
    message: Option<&str>,
) {
    let bound: Decimal = max.parse().expect("a decimal bound");
    if value.is_some_and(|v| v > bound) {
        let message = message.map_or_else(
            || {
                errors.default_message(
                    &format!("must be less than or equal to {max}"),
                    &format!("doit être inférieur ou égal à {max}"),
                )
            },
            str::to_owned,
        );
        errors.add(field, message);
    }
}

/// @Digits(integer, fraction, message), counted on the value without trailing zeros as
/// Hibernate's DigitsValidatorForNumber does; null is valid.
pub fn digits(
    errors: &mut Errors,
    field: &str,
    value: Option<Decimal>,
    integer: u32,
    fraction: u32,
    message: Option<&str>,
) {
    let Some(value) = value else {
        return;
    };
    let stripped = value.normalize();
    let scale = stripped.scale();
    let digits = stripped.mantissa().unsigned_abs().to_string().len() as u32;
    let integer_len = digits.saturating_sub(scale);
    if integer_len > integer || scale > fraction {
        let message = message.map_or_else(
            || {
                errors.default_message(
                    &format!(
                        "numeric value out of bounds (<{integer} digits>.<{fraction} digits> expected)"
                    ),
                    &format!(
                        "valeur numérique hors limites (<{integer} chiffres>.<{fraction} chiffres> attendu)"
                    ),
                )
            },
            str::to_owned,
        );
        errors.add(field, message);
    }
}

/// @Min(value); null is valid.
pub fn min(errors: &mut Errors, field: &str, value: Option<i64>, min: i64) {
    if value.is_some_and(|v| v < min) {
        let message = errors.default_message(
            &format!("must be greater than or equal to {min}"),
            &format!("doit être supérieur ou égal à {min}"),
        );
        errors.add(field, message);
    }
}

/// @Max(value); null is valid.
pub fn max(errors: &mut Errors, field: &str, value: Option<i64>, max: i64) {
    if value.is_some_and(|v| v > max) {
        let message = errors.default_message(
            &format!("must be less than or equal to {max}"),
            &format!("doit être inférieur ou égal à {max}"),
        );
        errors.add(field, message);
    }
}
