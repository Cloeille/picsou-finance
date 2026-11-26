//! The BigDecimal idioms the analysis services share.

use rust_decimal::Decimal;
use rust_decimal::prelude::ToPrimitive;

use crate::ledger::decimal::{HALF_UP, divide, set_scale};

/// BigDecimal.max: the receiver when equal, so its scale survives.
pub fn jmax(a: Decimal, b: Decimal) -> Decimal {
    if a >= b { a } else { b }
}

/// BigDecimal.min: the receiver when equal.
pub fn jmin(a: Decimal, b: Decimal) -> Decimal {
    if a <= b { a } else { b }
}

pub fn scale2(value: Decimal) -> Decimal {
    set_scale(value, 2, HALF_UP)
}

/// value / total at 8 places, times 100; zero for a zero total.
pub fn percent_of(value: Decimal, total: Decimal) -> Decimal {
    if total.is_zero() {
        return Decimal::ZERO;
    }
    divide(value, total, 8, HALF_UP) * Decimal::ONE_HUNDRED
}

/// setScale(0, HALF_UP).intValue().
pub fn rounded_int(value: Decimal) -> i64 {
    set_scale(value, 0, HALF_UP).to_i64().unwrap_or_default()
}

pub use crate::ledger::decimal::jmul;
