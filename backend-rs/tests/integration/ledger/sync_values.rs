use std::str::FromStr;

use picsou::ledger::sidecar::{SidecarBank, SidecarErrorCode};
use picsou::ledger::sync_values::{
    clean, error_code, limit, lines_close, money_close, sum_complete, weighted_average,
};
use rust_decimal::Decimal;

fn d(s: &str) -> Decimal {
    Decimal::from_str(s).unwrap()
}

#[test]
fn lines_close_allows_one_cent_per_rounded_line_above_the_absolute_floor() {
    for (actual, expected, lines, close) in [
        // the absolute floor covers a few lines
        ("100.05", "100.00", 3, true),
        ("100.06", "100.00", 3, false),
        // one cent per line beyond that, in both directions
        ("99.90", "100.00", 10, true),
        ("100.11", "100.00", 10, false),
        // never relative to the total: a large contract gets no extra room
        ("1000001.00", "1000000.00", 10, false),
    ] {
        assert_eq!(
            lines_close(d(actual), d(expected), lines),
            close,
            "{actual} vs {expected}"
        );
    }
}

#[test]
fn money_close_grows_with_the_expected_amount() {
    assert!(money_close(d("10.05"), d("10")));
    assert!(!money_close(d("10.06"), d("10")));
    assert!(money_close(d("1001000"), d("1000000")));
    assert!(!money_close(d("1001000.01"), d("1000000")));
}

#[test]
fn weighted_average_is_unknown_when_a_side_or_the_total_is() {
    assert_eq!(
        weighted_average(Some(d("10")), d("1"), Some(d("20")), d("2"), d("3"))
            .unwrap()
            .to_string(),
        "16.66666667"
    );
    assert_eq!(
        weighted_average(None, d("1"), Some(d("20")), d("2"), d("3")),
        None
    );
    assert_eq!(
        weighted_average(Some(d("10")), d("1"), Some(d("20")), d("-1"), d("0")),
        None
    );
    assert_eq!(
        sum_complete(Some(d("1.5")), Some(d("2.25")))
            .unwrap()
            .to_string(),
        "3.75"
    );
    assert_eq!(sum_complete(Some(d("1")), None), None);
}

#[test]
fn clean_and_limit_follow_java_trim_and_is_blank() {
    assert_eq!(clean(None), None);
    assert_eq!(clean(Some(" \t\u{2003} ")), None);
    assert_eq!(clean(Some("  a b \u{1} ")).as_deref(), Some("a b"));
    // A no-break space is not blank in Java and survives trim().
    assert_eq!(clean(Some("\u{a0}")).as_deref(), Some("\u{a0}"));
    assert_eq!(limit(Some("  abcdef "), 3, "x"), "abc");
    assert_eq!(limit(Some("  "), 3, "fallback"), "fal");
    assert_eq!(limit(Some("é€"), 5, "x"), "é€");
}

#[test]
fn error_code_falls_back_on_unknown_or_undeclared_codes() {
    let fallback = SidecarErrorCode::InternalError;
    assert_eq!(
        error_code(Some("SESSION_EXPIRED"), SidecarBank::Amex, fallback),
        SidecarErrorCode::SessionExpired
    );
    assert_eq!(
        error_code(Some("CAPTCHA_BLOCKED"), SidecarBank::Amex, fallback),
        fallback
    );
    assert_eq!(
        error_code(Some("whatever"), SidecarBank::Amex, fallback),
        fallback
    );
    assert_eq!(error_code(None, SidecarBank::Amex, fallback), fallback);
}
