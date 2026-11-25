//! java.math.BigDecimal semantics the ledger relies on, over rust_decimal::Decimal.
//!
//! Decimal::round_dp never widens the scale, while setScale does (5 becomes 5.00); Jackson
//! writes the scale, so every Java setScale goes through set_scale here.

use rust_decimal::{Decimal, RoundingStrategy};

/// BigDecimal.ROUND_HALF_UP.
pub const HALF_UP: RoundingStrategy = RoundingStrategy::MidpointAwayFromZero;
/// BigDecimal.ROUND_HALF_EVEN.
pub const HALF_EVEN: RoundingStrategy = RoundingStrategy::MidpointNearestEven;
/// BigDecimal.ROUND_CEILING.
pub const CEILING: RoundingStrategy = RoundingStrategy::ToPositiveInfinity;
/// BigDecimal.ROUND_FLOOR.
pub const FLOOR: RoundingStrategy = RoundingStrategy::ToNegativeInfinity;
/// BigDecimal.ROUND_DOWN.
pub const DOWN: RoundingStrategy = RoundingStrategy::ToZero;

/// value.setScale(scale, mode): rounds, or pads with zeros, to exactly scale digits.
pub fn set_scale(value: Decimal, scale: u32, mode: RoundingStrategy) -> Decimal {
    let mut rounded = value.round_dp_with_strategy(scale, mode);
    rounded.rescale(scale);
    rounded
}

/// a.divide(b, scale, mode). Panics on a zero divisor, as Java throws ArithmeticException.
pub fn divide(a: Decimal, b: Decimal, scale: u32, mode: RoundingStrategy) -> Decimal {
    set_scale(a / b, scale, mode)
}

/// Rounds to 16 significant digits, half-even: MathContext.DECIMAL64.
pub fn decimal64(value: Decimal) -> Decimal {
    round_significant(value, 16, HALF_EVEN)
}

/// a.divide(b, MathContext.DECIMAL64).
pub fn divide64(a: Decimal, b: Decimal) -> Decimal {
    decimal64(a / b)
}

/// a.multiply(b, MathContext.DECIMAL64).
pub fn multiply64(a: Decimal, b: Decimal) -> Decimal {
    decimal64(a * b)
}

/// Rounds to digits significant digits; BigDecimal then strips no zeros, nor does this.
pub fn round_significant(value: Decimal, digits: u32, mode: RoundingStrategy) -> Decimal {
    if value.is_zero() {
        return value;
    }
    let mantissa_digits = count_digits(value.mantissa().unsigned_abs());
    if mantissa_digits <= digits {
        return value;
    }
    let drop = mantissa_digits - digits;
    let scale = value.scale();
    if drop <= scale {
        return value.round_dp_with_strategy(scale - drop, mode);
    }
    // More integer digits than the precision: round at a power of ten above the units.
    let factor = Decimal::from_i128_with_scale(10_i128.pow(drop - scale), 0);
    (value / factor).round_dp_with_strategy(0, mode) * factor
}

fn count_digits(mut n: u128) -> u32 {
    let mut digits = 1;
    while n >= 10 {
        n /= 10;
        digits += 1;
    }
    digits
}

/// A value as mantissa × 10^exponent, the mantissa in [1, 10) unless the value is zero.
///
/// BigDecimal has no upper bound, so (1 + r)^n stays finite in Java where it leaves Decimal's
/// range (a 1 000 %/year rate over 1 200 months); the exponent carries what Decimal cannot.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Scaled {
    pub mantissa: Decimal,
    pub exponent: i32,
}

impl Scaled {
    fn normalize(mut mantissa: Decimal, mut exponent: i32) -> Self {
        if mantissa.is_zero() {
            return Self {
                mantissa,
                exponent: 0,
            };
        }
        while mantissa.abs() >= Decimal::TEN {
            mantissa /= Decimal::TEN;
            exponent += 1;
        }
        while mantissa.abs() < Decimal::ONE {
            mantissa *= Decimal::TEN;
            exponent -= 1;
        }
        Self { mantissa, exponent }
    }

    fn multiply(self, other: Self, digits: u32) -> Self {
        let product = round_significant(self.mantissa * other.mantissa, digits, HALF_EVEN);
        Self::normalize(product, self.exponent + other.exponent)
    }

    /// The value as a Decimal, or None above Decimal's range. Below 10^-28 it reads as zero,
    /// which no money figure downstream can tell apart from Java's tiny power.
    pub fn to_decimal(self) -> Option<Decimal> {
        match self.exponent {
            e if e < -28 => Some(Decimal::ZERO),
            e if e < 0 => self.mantissa.checked_mul(Decimal::new(1, e.unsigned_abs())),
            e if e <= 28 => self.mantissa.checked_mul(Decimal::from_i128_with_scale(
                10_i128.pow(e.unsigned_abs()),
                0,
            )),
            _ => None,
        }
    }
}

/// base.pow(n, MathContext.DECIMAL64): Java's X3.274 square-and-multiply at 16 + digits(n) + 1
/// working digits, then rounded to 16 half-even.
pub fn pow64(base: Decimal, n: u32) -> Scaled {
    let working = 16 + count_digits(u128::from(n)) + 1;
    let base = Scaled::normalize(base, 0);
    let mut acc = Scaled::normalize(Decimal::ONE, 0);
    let mut seen = false;
    for bit in (0..31).rev() {
        if n >> bit & 1 == 1 {
            seen = true;
            acc = acc.multiply(base, working);
        }
        if bit > 0 && seen {
            acc = acc.multiply(acc, working);
        }
    }
    Scaled::normalize(round_significant(acc.mantissa, 16, HALF_EVEN), acc.exponent)
}

/// BigDecimal.compareTo(other) == 0, ignoring scale (Decimal's == already does).
pub fn same(a: Decimal, b: Decimal) -> bool {
    a == b
}

/// BigDecimal.multiply: the scale is the sum of the scales, a zero product included (Decimal
/// drops it).
pub fn jmul(a: Decimal, b: Decimal) -> Decimal {
    let product = a * b;
    if product.is_zero() {
        Decimal::new(0, (a.scale() + b.scale()).min(28))
    } else {
        product
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::str::FromStr;

    fn d(s: &str) -> Decimal {
        Decimal::from_str(s).unwrap()
    }

    #[test]
    fn set_scale_pads_and_rounds_half_up() {
        assert_eq!(set_scale(d("5"), 2, HALF_UP).to_string(), "5.00");
        assert_eq!(set_scale(d("2.345"), 2, HALF_UP).to_string(), "2.35");
        assert_eq!(set_scale(d("-2.345"), 2, HALF_UP).to_string(), "-2.35");
    }

    #[test]
    fn decimal64_keeps_sixteen_significant_digits() {
        assert_eq!(
            divide64(d("0.035"), d("12")).to_string(),
            "0.002916666666666667"
        );
        assert_eq!(divide64(d("1"), d("3")).to_string(), "0.3333333333333333");
        assert_eq!(
            decimal64(d("12345678901234567.5")).to_string(),
            "12345678901234570"
        );
        assert_eq!(decimal64(d("1.5")).to_string(), "1.5");
    }

    #[test]
    fn pow64_matches_java_pow_with_decimal64() {
        assert_eq!(
            pow64(d("1.0025"), 240).to_decimal(),
            Some(d("1.820754995316485"))
        );
        assert_eq!(
            pow64(d("1.002916666666666667"), 37).to_decimal(),
            Some(d("1.113779953302819"))
        );
        assert_eq!(pow64(d("1.1"), 0).to_decimal(), Some(Decimal::ONE));
        let huge = pow64(d("84.33333333333333"), 1200);
        assert_eq!(
            (huge.mantissa, huge.exponent),
            (d("1.581684094608726"), 2311)
        );
        assert_eq!(huge.to_decimal(), None);
        let tiny = pow64(d("0.5"), 100);
        assert_eq!(
            (tiny.mantissa, tiny.exponent),
            (d("7.888609052210118"), -31)
        );
        assert_eq!(tiny.to_decimal(), Some(Decimal::ZERO));
    }
}
