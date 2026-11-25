//! LouvePortfolioCodes: the identifiers Louve Invest hands out, and which Picsou stores as what.

use std::sync::LazyLock;

use regex::Regex;

use crate::ledger::instrument::normalize_isin;

/// A Louve scpiUrlId is a URL slug (aurore, horizon-pierre) that fits louve_fund_code.
static FUND_CODE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[a-z0-9][a-z0-9._-]{0,39}$").expect("valid regex"));

pub fn is_fund_code(code: &str) -> bool {
    FUND_CODE.is_match(code)
}

/// isin_code when it is a real ISIN (shape and ISO 6166 check digit), else None. Louve also puts
/// internal references there (SCPI99990001): the shape of an ISIN, not its check digit.
pub fn isin_or_none(isin_code: Option<&str>) -> Option<String> {
    normalize_isin(isin_code?)
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
