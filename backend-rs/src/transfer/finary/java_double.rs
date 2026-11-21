//! Java's double for an XLSX numeric cell, computed without binary floats: POI reads the cell
//! with Double.parseDouble, FinaryImportService keeps BigDecimal.valueOf(that double) and the DTOs
//! print it back with Double.toString (Jackson writes a double that way).

use std::cmp::Ordering;

use num_bigint::BigUint;
use rust_decimal::Decimal;
use serde::Serialize;
use serde::ser::{SerializeStruct, Serializer};

/// A finite double: its Double.toString text and the BigDecimal.valueOf value.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JavaDouble {
    pub text: String,
    pub value: Decimal,
}

impl JavaDouble {
    /// (long) d: truncated toward zero, saturating.
    pub fn as_long(&self) -> i64 {
        i64::try_from(self.value.trunc().mantissa()).unwrap_or(if self.value.is_sign_negative() {
            i64::MIN
        } else {
            i64::MAX
        })
    }
}

impl Serialize for JavaDouble {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        // serde_json's arbitrary_precision hook: the text is written as a bare JSON number.
        let mut s = serializer.serialize_struct("$serde_json::private::Number", 1)?;
        s.serialize_field("$serde_json::private::Number", &self.text)?;
        s.end()
    }
}

/// A binary double as q * 2^-s, q < 2^53.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Binary {
    q: BigUint,
    s: i64,
}

fn pow10(n: u32) -> BigUint {
    BigUint::from(10u32).pow(n)
}

/// The exact rational m * 10^e as num / den.
fn rational(m: &BigUint, e: i64) -> (BigUint, BigUint) {
    if e >= 0 {
        (m * pow10(e as u32), BigUint::from(1u32))
    } else {
        (m.clone(), pow10((-e) as u32))
    }
}

/// The double nearest num / den (ties to even), or None past Double.MAX_VALUE.
fn nearest(num: &BigUint, den: &BigUint) -> Option<Binary> {
    if *num == BigUint::ZERO {
        return Some(Binary {
            q: BigUint::ZERO,
            s: 0,
        });
    }
    let mut s: i64 = 53 - (num.bits() as i64 - den.bits() as i64);
    let scaled = |s: i64| -> (BigUint, BigUint) {
        if s >= 0 {
            (num << (s as usize), den.clone())
        } else {
            (num.clone(), den << ((-s) as usize))
        }
    };
    let two52 = BigUint::from(1u64) << 52usize;
    let two53 = BigUint::from(1u64) << 53usize;
    loop {
        let (n, d) = scaled(s);
        let q = &n / &d;
        if q >= two53 {
            s -= 1;
        } else if q < two52 {
            s += 1;
        } else {
            break;
        }
    }
    // Subnormals keep the 2^-1074 floor.
    s = s.min(1074);
    let (n, d) = scaled(s);
    let mut q = &n / &d;
    let twice_rem = (&n - &q * &d) << 1usize;
    match twice_rem.cmp(&d) {
        Ordering::Greater => q += 1u32,
        Ordering::Equal if q.bit(0) => q += 1u32,
        _ => {}
    }
    if q == two53 {
        q >>= 1usize;
        s -= 1;
    }
    // Largest exponent: q * 2^-s < 2^1024.
    if 52 - s > 1023 {
        return None;
    }
    Some(Binary { q, s })
}

/// Double.parseDouble of a plain decimal literal, sign apart; None when Java would throw.
fn parse_literal(text: &str) -> Option<(bool, BigUint, i64)> {
    let t = text.trim_matches(|c: char| c <= ' ');
    let t = t.strip_suffix(['d', 'D', 'f', 'F']).unwrap_or(t);
    let (neg, body) = match t.as_bytes().first()? {
        b'-' => (true, &t[1..]),
        b'+' => (false, &t[1..]),
        _ => (false, t),
    };
    let (mantissa, exponent) = match body.find(['e', 'E']) {
        Some(i) => (&body[..i], Some(&body[i + 1..])),
        None => (body, None),
    };
    let (int, frac) = match mantissa.find('.') {
        Some(i) => (&mantissa[..i], &mantissa[i + 1..]),
        None => (mantissa, ""),
    };
    if int.is_empty() && frac.is_empty() {
        return None;
    }
    if !int.bytes().chain(frac.bytes()).all(|b| b.is_ascii_digit()) {
        return None;
    }
    let mut e: i64 = match exponent {
        Some(raw) => {
            let digits = raw.strip_prefix(['+', '-']).unwrap_or(raw);
            if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
                return None;
            }
            let magnitude: i64 = digits.parse().unwrap_or(i64::MAX / 4).min(100_000);
            if raw.starts_with('-') {
                -magnitude
            } else {
                magnitude
            }
        }
        None => 0,
    };
    e -= frac.len() as i64;
    let digits = format!("{int}{frac}");
    let m = BigUint::parse_bytes(digits.as_bytes(), 10)?;
    Some((neg, m, e))
}

/// v = q * 2^-s as num / den.
fn binary_rational(b: &Binary) -> (BigUint, BigUint) {
    if b.s >= 0 {
        (b.q.clone(), BigUint::from(1u32) << (b.s as usize))
    } else {
        (&b.q << ((-b.s) as usize), BigUint::from(1u32))
    }
}

/// The shortest decimal digits (no trailing zeros) and exponent k with v = 0.d1d2... * 10^k, as
/// Double.toString (JDK 19+) selects them.
fn shortest(b: &Binary) -> (String, i64) {
    let (num, den) = binary_rational(b);
    // k: 10^(k-1) <= v < 10^k.
    let mut k = num.to_string().len() as i64 - den.to_string().len() as i64;
    loop {
        let (n, d) = rational(&BigUint::from(1u32), k - 1);
        if &num * &d < &n * &den {
            k -= 1;
            continue;
        }
        let (n, d) = rational(&BigUint::from(1u32), k);
        if &num * &d >= &n * &den {
            k += 1;
            continue;
        }
        break;
    }
    for p in 1..=17i64 {
        // With a one-digit answer Java picks the closest among one- and two-digit decimals.
        if let Some(best) = closest_round_trip(b, &num, &den, p, k) {
            let (digits, exp) = if p == 1 {
                closest_round_trip(b, &num, &den, 2, k).unwrap_or(best)
            } else {
                best
            };
            let mut text = digits.to_string();
            let mut exp = exp;
            while text.len() > 1 && text.ends_with('0') {
                text.pop();
            }
            // D * 10^exp with D of p digits: v = 0.D * 10^(exp + len).
            exp += digits.to_string().len() as i64;
            return (text, exp);
        }
    }
    unreachable!("17 significant digits always round-trip a double")
}

/// Among the p-digit decimals bracketing v (as D * 10^(k-p)), the closest one that reads back as
/// the same double, ties to the even D.
fn closest_round_trip(
    b: &Binary,
    num: &BigUint,
    den: &BigUint,
    p: i64,
    k: i64,
) -> Option<(BigUint, i64)> {
    let exp = k - p;
    // floor(v / 10^exp)
    let (fn_, fd) = if exp >= 0 {
        (num.clone(), den * pow10(exp as u32))
    } else {
        (num * pow10((-exp) as u32), den.clone())
    };
    let floor = &fn_ / &fd;
    let exact = &floor * &fd == fn_;
    let mut candidates = vec![floor.clone()];
    if !exact {
        candidates.push(&floor + 1u32);
    }
    let mut best: Option<(BigUint, BigUint)> = None;
    for c in candidates {
        let (cn, cd) = rational(&c, exp);
        if nearest(&cn, &cd).as_ref() != Some(b) {
            continue;
        }
        // |c * 10^exp - v| scaled by fd.
        let scaled = &c * &fd;
        let distance = if scaled >= fn_ {
            &scaled - &fn_
        } else {
            &fn_ - &scaled
        };
        let better = match &best {
            None => true,
            Some((bc, bd)) => match distance.cmp(bd) {
                Ordering::Less => true,
                Ordering::Equal => !c.bit(0) && bc.bit(0),
                Ordering::Greater => false,
            },
        };
        if better {
            best = Some((c, distance));
        }
    }
    best.map(|(c, _)| (c, exp))
}

fn format_java(neg: bool, digits: &str, k: i64) -> String {
    let sign = if neg { "-" } else { "" };
    // Scientific exponent: v = d1.d2... * 10^(k-1).
    let e10 = k - 1;
    if (-3..7).contains(&e10) {
        if e10 >= 0 {
            let int_len = (e10 + 1) as usize;
            let (int, frac) = if digits.len() > int_len {
                (digits[..int_len].to_owned(), digits[int_len..].to_owned())
            } else {
                (format!("{digits:0<int_len$}"), "0".to_owned())
            };
            format!("{sign}{int}.{frac}")
        } else {
            let zeros = "0".repeat((-e10 - 1) as usize);
            format!("{sign}0.{zeros}{digits}")
        }
    } else {
        let frac = if digits.len() > 1 { &digits[1..] } else { "0" };
        format!("{sign}{}.{frac}E{e10}", &digits[..1])
    }
}

/// Double.parseDouble then BigDecimal.valueOf / Double.toString. None when Java throws, the value
/// is infinite, or Decimal cannot hold it.
pub fn parse(text: &str) -> Option<JavaDouble> {
    let (neg, m, e) = parse_literal(text)?;
    if e > 400 && m != BigUint::ZERO {
        return None;
    }
    if e < -400 || m == BigUint::ZERO {
        return Some(JavaDouble {
            text: "0.0".to_owned(),
            value: Decimal::ZERO,
        });
    }
    let (num, den) = rational(&m, e);
    let b = nearest(&num, &den)?;
    if b.q == BigUint::ZERO {
        return Some(JavaDouble {
            text: "0.0".to_owned(),
            value: Decimal::ZERO,
        });
    }
    let (digits, k) = shortest(&b);
    let text = format_java(neg, &digits, k);
    // value = digits * 10^(k - len)
    let scale = digits.len() as i64 - k;
    let mantissa: i128 = digits.parse().ok()?;
    let mantissa = if neg { -mantissa } else { mantissa };
    let value = if scale >= 0 {
        if scale > 28 {
            scaled_down(mantissa, scale)?
        } else {
            Decimal::try_from_i128_with_scale(mantissa, scale as u32).ok()?
        }
    } else {
        let factor = 10i128.checked_pow((-scale) as u32)?;
        Decimal::try_from_i128_with_scale(mantissa.checked_mul(factor)?, 0).ok()?
    };
    Some(JavaDouble { text, value })
}

/// mantissa * 10^-scale for a scale past Decimal's 28, rounded half-even at 28 places.
fn scaled_down(mantissa: i128, scale: i64) -> Option<Decimal> {
    let Some(divisor) = 10i128.checked_pow(u32::try_from(scale - 28).ok()?) else {
        return Some(Decimal::ZERO);
    };
    let mut q = mantissa / divisor;
    let twice_rem = (mantissa % divisor).abs() * 2;
    if twice_rem > divisor || (twice_rem == divisor && q % 2 != 0) {
        q += mantissa.signum();
    }
    Decimal::try_from_i128_with_scale(q, 28).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text(raw: &str) -> String {
        parse(raw).map(|d| d.text).unwrap_or_else(|| "none".into())
    }

    #[test]
    fn prints_as_double_to_string() {
        assert_eq!(text("100"), "100.0");
        assert_eq!(text("1250.5"), "1250.5");
        assert_eq!(text("-42"), "-42.0");
        assert_eq!(text("12345678"), "1.2345678E7");
        assert_eq!(text("10000000"), "1.0E7");
        assert_eq!(text("9999999"), "9999999.0");
        assert_eq!(text("0.001"), "0.001");
        assert_eq!(text("0.0001"), "1.0E-4");
        assert_eq!(text("0.1"), "0.1");
        assert_eq!(text("1234.5600000000001"), "1234.5600000000002");
        assert_eq!(text("0.30000000000000004"), "0.30000000000000004");
        assert_eq!(text("1.00000000000000001"), "1.0");
        assert_eq!(text("2E-4"), "2.0E-4");
        assert_eq!(text("1E23"), "1.0E23");
        assert_eq!(text("4.9E-324"), "4.9E-324");
        assert_eq!(text("-0"), "0.0");
        assert_eq!(text("abc"), "none");
        assert_eq!(text("1E400"), "none");
    }

    #[test]
    fn keeps_the_big_decimal_value() {
        let d = parse("1234.5600000000001").unwrap();
        assert_eq!(d.value, "1234.5600000000002".parse::<Decimal>().unwrap());
        assert_eq!(parse("12345678").unwrap().value, Decimal::from(12_345_678));
        assert_eq!(parse("45000.9").unwrap().as_long(), 45000);
        assert_eq!(parse("-3.7").unwrap().as_long(), -3);
    }
}
