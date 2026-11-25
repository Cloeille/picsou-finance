//! The normalized source both decoders produce (ParsedHomeBankData) and the Java value
//! semantics they share: BigDecimal text, UTF-16 lengths and name-based UUIDs.

use chrono::NaiveDate;
use md5::{Digest, Md5};
use rust_decimal::Decimal;

#[derive(Debug, Clone, PartialEq)]
pub struct SourceAccount {
    pub id: String,
    pub name: String,
    pub institution: Option<String>,
    pub source_type: String,
    pub currency: String,
    pub initial_balance: Decimal,
    pub closed: bool,
}

/// kind_inferred: income was guessed from the signs of the exported amounts (QIF categories
/// without an I/E declaration) rather than declared by the source.
#[derive(Debug, Clone, PartialEq)]
pub struct SourceCategory {
    pub id: String,
    pub name: String,
    pub parent_id: Option<String>,
    pub income: bool,
    pub kind_inferred: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SourceTransaction {
    pub id: String,
    pub account_id: String,
    pub date: NaiveDate,
    pub amount: Decimal,
    pub currency: String,
    pub payee: Option<String>,
    pub notes: Option<String>,
    pub category_id: Option<String>,
    pub transfer_account_id: Option<String>,
    pub forecast: bool,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct ParsedHomeBankData {
    pub accounts: Vec<SourceAccount>,
    pub categories: Vec<SourceCategory>,
    pub transactions: Vec<SourceTransaction>,
}

/// String.length(): UTF-16 code units.
pub fn jlen(value: &str) -> usize {
    value.encode_utf16().count()
}

/// A BigDecimal as new BigDecimal(text) reads it: unscaled digits and a scale that may be
/// negative (1.5E+2 is 15 with scale -1).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BigDec {
    negative: bool,
    /// Unscaled digits without leading zeros; empty for zero.
    digits: String,
    pub scale: i64,
}

impl BigDec {
    /// Sign, digits with an optional fraction, then an optional exponent; None when the text
    /// is not a number or its scale leaves the int range.
    pub fn parse(text: &str) -> Option<Self> {
        let (negative, rest) = match text.as_bytes().first() {
            Some(b'-') => (true, &text[1..]),
            Some(b'+') => (false, &text[1..]),
            _ => (false, text),
        };
        let (mantissa, exponent) = match rest.find(['e', 'E']) {
            Some(at) => (&rest[..at], Some(&rest[at + 1..])),
            None => (rest, None),
        };
        let (int_part, frac_part) = mantissa.split_once('.').unwrap_or((mantissa, ""));
        let all_digits = |s: &str| s.bytes().all(|b| b.is_ascii_digit());
        if int_part.len() + frac_part.len() == 0 || !all_digits(int_part) || !all_digits(frac_part)
        {
            return None;
        }
        let exponent: i64 = match exponent {
            Some(e) => {
                let digits = e.strip_prefix(['+', '-']).unwrap_or(e);
                if digits.is_empty() || !all_digits(digits) {
                    return None;
                }
                e.parse().ok()?
            }
            None => 0,
        };
        let scale = i64::try_from(frac_part.len()).ok()?.checked_sub(exponent)?;
        if i32::try_from(scale).is_err() {
            return None;
        }
        let digits = format!("{int_part}{frac_part}")
            .trim_start_matches('0')
            .to_owned();
        Some(Self {
            negative: negative && !digits.is_empty(),
            digits,
            scale,
        })
    }

    pub fn precision(&self) -> i64 {
        i64::try_from(self.digits.len().max(1)).unwrap_or(i64::MAX)
    }

    /// The value with its scale kept; a negative scale is multiplied out. None past
    /// rust_decimal's 28 digits, which every caller's limits keep well clear of.
    pub fn to_decimal(&self) -> Option<Decimal> {
        let (digits, scale) = if self.scale < 0 {
            let zeros = usize::try_from(-self.scale).ok()?;
            if self.digits.is_empty() {
                (String::new(), 0)
            } else {
                (format!("{}{}", self.digits, "0".repeat(zeros)), 0)
            }
        } else {
            (self.digits.clone(), u32::try_from(self.scale).ok()?)
        };
        if digits.len() > 28 || scale > 28 {
            return None;
        }
        let mut mantissa: i128 = if digits.is_empty() {
            0
        } else {
            digits.parse().ok()?
        };
        if self.negative {
            mantissa = -mantissa;
        }
        Decimal::try_from_i128_with_scale(mantissa, scale).ok()
    }
}

/// UUID.nameUUIDFromBytes: an MD5, version 3 UUID, lower-case.
pub fn name_uuid(bytes: &[u8]) -> String {
    let mut hash: [u8; 16] = Md5::digest(bytes).into();
    hash[6] = (hash[6] & 0x0f) | 0x30;
    hash[8] = (hash[8] & 0x3f) | 0x80;
    uuid::Uuid::from_bytes(hash).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dec(text: &str) -> BigDec {
        BigDec::parse(text).expect(text)
    }

    #[test]
    fn reads_scale_and_precision_like_big_decimal() {
        assert_eq!(
            (dec("1234.56789012").precision(), dec("1234.56789012").scale),
            (12, 8)
        );
        assert_eq!((dec("1.5E+2").precision(), dec("1.5E+2").scale), (2, -1));
        assert_eq!(dec("1.5E+2").to_decimal().unwrap().to_string(), "150");
        assert_eq!((dec("0.00").precision(), dec("0.00").scale), (1, 2));
        assert_eq!(dec("-0.0").to_decimal().unwrap().to_string(), "0.0");
        assert_eq!(dec("25.00").to_decimal().unwrap().to_string(), "25.00");
        assert_eq!(dec("1.").to_decimal().unwrap().to_string(), "1");
        assert_eq!(dec(".5").to_decimal().unwrap().to_string(), "0.5");
        assert!(BigDec::parse("1e").is_none());
        assert!(BigDec::parse(".").is_none());
        assert!(BigDec::parse("1e99999999999").is_none());
    }

    #[test]
    fn name_uuids_match_java() {
        assert_eq!(name_uuid(b"abc"), "90015098-3cd2-3fb0-9696-3f7d28e17f72");
    }
}
