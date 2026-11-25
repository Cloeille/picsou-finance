//! LouvePortfolioCodes: the identifiers Louve Invest hands out, and which Picsou stores as what.

use std::sync::LazyLock;

use regex::Regex;

/// A Louve scpiUrlId is a URL slug (aurore, horizon-pierre) that fits louve_fund_code.
static FUND_CODE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[a-z0-9][a-z0-9._-]{0,39}$").expect("valid regex"));
static ISIN_SHAPE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Z]{2}[A-Z0-9]{9}[0-9]$").expect("valid regex"));

pub fn is_fund_code(code: &str) -> bool {
    FUND_CODE.is_match(code)
}

/// isin_code when it is a real ISIN (shape and ISO 6166 check digit), else None. Louve also puts
/// internal references there (SCPI99990001): the shape of an ISIN, not its check digit.
pub fn isin_or_none(isin_code: Option<&str>) -> Option<String> {
    let candidate = isin_code?.trim().to_uppercase();
    (ISIN_SHAPE.is_match(&candidate) && check_digit_holds(&candidate)).then_some(candidate)
}

/// Luhn over the digits of the first eleven characters, letters expanded to 10..35.
fn check_digit_holds(isin: &str) -> bool {
    let digits: String = isin
        .chars()
        .take(11)
        .filter_map(|c| c.to_digit(36))
        .map(|d| d.to_string())
        .collect();
    let mut sum = 0;
    let mut twice = true;
    for c in digits.chars().rev() {
        let mut n = c.to_digit(10).unwrap_or(0);
        if twice {
            n *= 2;
            if n > 9 {
                n -= 9;
            }
        }
        sum += n;
        twice = !twice;
    }
    let check = isin.chars().nth(11).and_then(|c| c.to_digit(10));
    check == Some((10 - sum % 10) % 10)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_valid_check_digit_is_an_isin() {
        assert_eq!(
            isin_or_none(Some(" fr0011631522 ")),
            Some("FR0011631522".to_owned())
        );
        assert_eq!(isin_or_none(Some("FR0011631523")), None);
        assert_eq!(isin_or_none(Some("SCPI99990001")), None);
        assert!(is_fund_code("horizon-pierre"));
        assert!(!is_fund_code("Aurore"));
        assert!(!is_fund_code(&"a".repeat(41)));
    }
}
