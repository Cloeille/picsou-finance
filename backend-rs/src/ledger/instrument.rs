//! Resolves the ticker, display name and language-neutral description of a transaction carrying
//! a ticker or ISIN. Shared by manual entry and the CSV importer, so an ISIN row and the equivalent
//! ticker row always collapse to the same position.

use crate::ledger::sync_values::{java_is_blank, java_trim};

/// What the OpenFIGI lookup of an ISIN yields; its ticker already falls back to the ISIN itself.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TickerResult {
    pub ticker: String,
    pub name: Option<String>,
}

/// Canonical instrument fields ready for persistence.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolvedInstrument {
    pub ticker: String,
    /// The user-supplied or resolved display name, when available.
    pub name: Option<String>,
    /// The display name, or the canonical ticker when no name is available.
    pub description: String,
}

/// Whether `value` looks like an ISIN: a 2-letter country code, 9 alphanumerics and a check
/// character, case-insensitive, surrounding whitespace ignored (OpenFigiIsinConverter.isIsin).
pub fn is_isin(value: &str) -> bool {
    let upper = java_trim(value).to_uppercase();
    let bytes = upper.as_bytes();
    bytes.len() == 12
        && bytes[..2].iter().all(u8::is_ascii_uppercase)
        && bytes[2..]
            .iter()
            .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit())
}

/// The canonical ISIN (trimmed, uppercased) when `raw` has the ISIN shape and the ISO 6166 check
/// digit holds, else None. Stricter than `is_isin`, which only checks the shape.
pub fn normalize_isin(raw: &str) -> Option<String> {
    let candidate = raw.trim().to_uppercase();
    let bytes = candidate.as_bytes();
    let shaped = bytes.len() == 12
        && bytes[..2].iter().all(u8::is_ascii_uppercase)
        && bytes[2..11]
            .iter()
            .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit())
        && bytes[11].is_ascii_digit();
    (shaped && check_digit_holds(&candidate)).then_some(candidate)
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

/// Resolves the instrument fields, calling `lookup` (OpenFIGI) only for an ISIN. A non-blank
/// `user_name` wins over the resolved one. None when the input is blank: a cash transaction,
/// for which the caller keeps its own description, ticker and name.
pub async fn resolve(
    ticker_or_isin: Option<&str>,
    user_name: Option<&str>,
    lookup: impl AsyncFnOnce(&str) -> TickerResult,
) -> Option<ResolvedInstrument> {
    let input = ticker_or_isin.filter(|value| !java_is_blank(value))?;
    let (ticker, resolved_name) = if is_isin(input) {
        let result = lookup(input).await;
        (result.ticker, result.name)
    } else {
        (java_trim(input).to_uppercase(), None)
    };
    let name = match user_name {
        Some(user) if !java_is_blank(user) => Some(java_trim(user).to_owned()),
        _ => resolved_name,
    };
    let description = name.clone().unwrap_or_else(|| ticker.clone());
    Some(ResolvedInstrument {
        ticker,
        name,
        description,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_isin_accepts_valid_and_rejects_bad_check_digits() {
        assert_eq!(
            normalize_isin(" ie00b4l5y983 "),
            Some("IE00B4L5Y983".to_owned())
        );
        assert_eq!(
            normalize_isin("US0378331005"),
            Some("US0378331005".to_owned())
        );
        assert_eq!(normalize_isin("IE00B4L5Y984"), None);
        assert_eq!(normalize_isin("IE00B4L5Y98"), None);
        assert_eq!(normalize_isin(""), None);
        assert_eq!(normalize_isin("SCPI99990001"), None);
    }
}
