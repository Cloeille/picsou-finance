//! Nasdaq dividend rows: dates, amounts with dollar signs, N/A, error codes.

use picsou::market::nasdaq::parse;

#[test]
fn rows_parse_with_their_quirks() {
    let body = r#"{"data":{"dividends":{"rows":[
        {"exOrEffDate":"02/14/2026","paymentDate":"03/12/2026","recordDate":"02/14/2026","declarationDate":"01/29/2026","amount":"$0.26","currency":"USD"},
        {"exOrEffDate":"N/A","amount":"$0.25"},
        {"exOrEffDate":"11/08/2025","paymentDate":"N/A","amount":"$1,000.5"},
        {"exOrEffDate":"08/08/2025","amount":"N/A"}
    ]}},"status":{"rCode":200}}"#;
    let rows = parse(body).unwrap();
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0].ex_date.to_string(), "2026-02-14");
    assert_eq!(rows[0].payment_date.unwrap().to_string(), "2026-03-12");
    assert_eq!(rows[0].amount.to_string(), "0.26");
    assert_eq!(rows[0].currency.as_deref(), Some("USD"));
    assert_eq!(rows[1].amount.to_string(), "1000.5");
    assert!(rows[1].payment_date.is_none());
}

#[test]
fn an_error_code_or_a_broken_body_is_an_error_and_no_rows_is_empty() {
    assert!(parse(r#"{"status":{"rCode":400}}"#).is_err());
    assert!(parse("<html>").is_err());
    assert!(parse("  ").is_err());
    assert!(
        parse(r#"{"data":{"dividends":{"rows":null}},"status":{"rCode":200}}"#)
            .unwrap()
            .is_empty()
    );
}
