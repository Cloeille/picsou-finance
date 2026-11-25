//! Value helpers shared by the browser-sidecar sync services: string normalization, cost-basis
//! arithmetic over possibly-unknown figures, and the reconciliation tolerance that decides whether
//! an upstream total agrees with its own lines.
//!
//! The tolerance lives here so every provider accepts or rejects the same rounding drift;
//! otherwise the same 5-cent discrepancy would erase a portfolio on one integration only.

use rust_decimal::Decimal;

use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode};

/// Rounding drift always tolerated, whatever the amount.
const ABSOLUTE_RECONCILIATION_TOLERANCE: Decimal = Decimal::from_parts(5, 0, 0, false, 2);
/// Additional tolerance proportional to the expected amount (0.1%).
const RELATIVE_RECONCILIATION_TOLERANCE: Decimal = Decimal::from_parts(1, 0, 0, false, 3);
/// Rounding drift of one amount rounded to the cent on its own.
const PER_LINE_RECONCILIATION_TOLERANCE: Decimal = Decimal::from_parts(1, 0, 0, false, 2);
/// Scale of derived per-unit figures, matching HoldingComputeService.
const UNIT_PRICE_SCALE: u32 = 8;

/// Java's String.trim(): strips every char up to U+0020, control characters included.
pub(crate) fn java_trim(value: &str) -> &str {
    value.trim_matches(|c: char| c <= ' ')
}

/// Java's String.strip(): strips characters matched by Character.isWhitespace at both ends.
pub(crate) fn java_strip(value: &str) -> &str {
    value.trim_matches(|c: char| java_is_blank(c.encode_utf8(&mut [0; 4])))
}

/// Java's String.isBlank(): every char is Character.isWhitespace, which, unlike Rust's
/// char::is_whitespace, excludes the no-break spaces and U+0085 but includes U+001C..U+001F.
pub(crate) fn java_is_blank(value: &str) -> bool {
    value.chars().all(|c| {
        matches!(
            c,
            '\t'..='\r'
                | '\u{1C}'..='\u{20}'
                | '\u{1680}'
                | '\u{2000}'..='\u{2006}'
                | '\u{2008}'..='\u{200A}'
                | '\u{2028}'
                | '\u{2029}'
                | '\u{205F}'
                | '\u{3000}'
        )
    })
}

/// Trimmed value, or None when blank: upstream absence and "  " mean the same thing.
pub fn clean(value: Option<&str>) -> Option<String> {
    let value = value?;
    if java_is_blank(value) {
        return None;
    }
    Some(java_trim(value).to_owned())
}

/// [`clean`], falling back to `fallback` and truncated to `max_length` UTF-16 code units.
pub fn limit(value: Option<&str>, max_length: usize, fallback: &str) -> String {
    let cleaned = clean(value).unwrap_or_else(|| fallback.to_owned());
    let mut units = 0;
    for (index, c) in cleaned.char_indices() {
        units += c.len_utf16();
        if units > max_length {
            return cleaned[..index].to_owned();
        }
    }
    cleaned
}

/// Quantity-weighted mean of two per-unit figures, at scale 8 half-up. None when either side is
/// unknown or the merged quantity is flat: a cost basis from half the lots would be wrong, not
/// missing.
pub fn weighted_average(
    left: Option<Decimal>,
    left_quantity: Decimal,
    right: Option<Decimal>,
    right_quantity: Decimal,
    total_quantity: Decimal,
) -> Option<Decimal> {
    let (left, right) = (left?, right?);
    if total_quantity.is_zero() {
        return None;
    }
    Some(divide(
        left * left_quantity + right * right_quantity,
        total_quantity,
        UNIT_PRICE_SCALE,
        HALF_UP,
    ))
}

/// Sum that stays unknown when either side is: a partial total is not a total.
pub fn sum_complete(left: Option<Decimal>, right: Option<Decimal>) -> Option<Decimal> {
    Some(left? + right?)
}

/// Whether two amounts agree within the reconciliation tolerance: the absolute floor, or 0.1% of
/// the expected amount for large portfolios.
pub fn money_close(actual: Decimal, expected: Decimal) -> bool {
    let tolerance =
        ABSOLUTE_RECONCILIATION_TOLERANCE.max(expected.abs() * RELATIVE_RECONCILIATION_TOLERANCE);
    (actual - expected).abs() <= tolerance
}

/// Whether `lines` amounts, each rounded to the cent, add up to `expected`. Unlike
/// [`money_close`], the allowance never grows with the total, so a support missing from a large
/// contract cannot hide inside a relative tolerance.
pub fn lines_close(actual: Decimal, expected: Decimal, lines: i32) -> bool {
    let tolerance = ABSOLUTE_RECONCILIATION_TOLERANCE
        .max(PER_LINE_RECONCILIATION_TOLERANCE * Decimal::from(lines));
    (actual - expected).abs() <= tolerance
}

/// Maps a SyncException's free-form `code` onto the bank's declared error codes, falling back
/// when the sidecar reports something unknown or undeclared: the bank's session table only accepts
/// its own codes.
pub fn error_code(
    code: Option<&str>,
    bank: SidecarBank,
    fallback: SidecarErrorCode,
) -> SidecarErrorCode {
    bank.parse(code).unwrap_or(fallback)
}
