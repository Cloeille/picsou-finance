//! A bank-valued line overrules a market quote it contradicts (#683): the holding detail, the 24H
//! chart and the range P&L, with Yahoo stubbed to quote the grey-market MULYF at 376.28 EUR.

use picsou::testing::TestApp;
use serde_json::json;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::analysis_support::{Acct, account, dec, get_json, signed_in, sql};

/// 2026-01-15T10:00:00Z, the instant every TestApp clock starts at.
const NOW: i64 = 1_768_471_200;

/// Yahoo answers every request for MULYF with 376.28 EUR, hourly over the last 24 hours.
async fn yahoo_quotes_mulyf_at_376() -> MockServer {
    let yahoo = MockServer::start().await;
    let timestamps: Vec<i64> = (0..=24).map(|h| NOW - 3600 * (24 - h)).collect();
    let closes = vec![376.28; timestamps.len()];
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/MULYF"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({"chart": {"result": [{
            "meta": {"currency": "EUR", "regularMarketPrice": 376.28},
            "timestamp": timestamps,
            "indicators": {"quote": [{"close": closes}]}
        }], "error": null}})),
        )
        .mount(&yahoo)
        .await;
    yahoo
}

/// The BoursoBank PEA of #683: 80 units valued 353.08 by the bank, cash pocket 100.
async fn bourso_pea(app: &TestApp, member: i64) -> i64 {
    let pea = account(app, Acct::new(member, "PEA", "453.08")).await;
    sql(
        app,
        &format!(
            "UPDATE account SET provider = 'BoursoBank', is_manual = false, cash_balance = 100 \
             WHERE id = {pea};\
             INSERT INTO account_holding (account_id, ticker, name, quantity, average_buy_in, \
             provider_value_eur, provider_pnl_eur, provider_price_date, created_at, updated_at) \
             VALUES ({pea}, 'MULYF', 'Amundi Euro Stoxx Banks', 80, 3.81, 353.08, 48.43, \
             '2026-01-10', now(), now());"
        ),
    )
    .await;
    pea
}

async fn app(yahoo: &MockServer) -> TestApp {
    TestApp::builder()
        .with_admin()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .spawn()
        .await
}

#[tokio::test]
async fn a_quote_the_bank_contradicts_shows_the_bank_value_stale_and_dated_by_the_provider() {
    let yahoo = yahoo_quotes_mulyf_at_376().await;
    let app = app(&yahoo).await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = bourso_pea(&app, alice).await;

    let lines = get_json(&client, &format!("/api/accounts/{pea}/holdings")).await;

    let line = &lines[0];
    assert_eq!(dec(&line["currentValueEur"]), dec(&json!(353.08)));
    assert_eq!(dec(&line["pnlEur"]), dec(&json!(48.43)));
    assert_eq!(line["priceStale"], true);
    assert_eq!(line["priceAsOf"], "2026-01-10");
}

#[tokio::test]
async fn intraday_keeps_the_bank_value_when_the_hourly_quote_contradicts_it() {
    let yahoo = yahoo_quotes_mulyf_at_376().await;
    let app = app(&yahoo).await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = bourso_pea(&app, alice).await;

    let points = get_json(
        &client,
        &format!("/api/history/net-worth/intraday?accountIds={pea}"),
    )
    .await;

    let points = points.as_array().unwrap();
    assert_eq!(points.len(), 25);
    for p in points {
        assert_eq!(dec(&p["total"]), dec(&json!(453.08)), "{p}");
        assert_eq!(dec(&p["invested"]), dec(&json!(404.65)), "{p}");
    }
}

#[tokio::test]
async fn intraday_never_asks_yahoo_for_cmes_or_natixis_and_keeps_the_provider_value() {
    let yahoo = MockServer::start().await;
    let timestamps: Vec<i64> = (0..=24).map(|h| NOW - 3600 * (24 - h)).collect();
    let closes = vec![376.28; timestamps.len()];
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/CMES-1613"))
        .respond_with(ResponseTemplate::new(404))
        .expect(0)
        .mount(&yahoo)
        .await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/NATIXIS-1613"))
        .respond_with(ResponseTemplate::new(404))
        .expect(0)
        .mount(&yahoo)
        .await;
    // 80 x 376.28 = 30102.40 is within the x2 tolerance of 30000, so only the never-market-priced
    // guard stops Yahoo from being queried and overruling the provider value.
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/MULYF"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({"chart": {"result": [{
                "meta": {"currency": "EUR", "regularMarketPrice": 376.28},
                "timestamp": timestamps,
                "indicators": {"quote": [{"close": closes}]}
            }], "error": null}})),
        )
        .expect(0)
        .mount(&yahoo)
        .await;

    let app = app(&yahoo).await;
    let (alice, client) = signed_in(&app, "alice").await;

    let cases = [
        (
            "Crédit Mutuel Épargne Salariale",
            "CMES-1613",
            "10",
            "1200",
            31_200,
            30_600,
        ),
        (
            "Natixis Interépargne",
            "NATIXIS-1613",
            "5",
            "600",
            30_600,
            30_100,
        ),
    ];

    for (provider, code, code_qty, code_val, expected_total, expected_invested) in cases {
        let pee = account(
            &app,
            Acct::new(alice, "EMPLOYEE_SAVINGS", &expected_total.to_string()),
        )
        .await;
        sql(
            &app,
            &format!(
                "UPDATE account SET provider = '{provider}', is_manual = false WHERE id = {pee};\
                 INSERT INTO account_holding (account_id, ticker, name, quantity, average_buy_in, \
                 provider_value_eur, created_at, updated_at) VALUES \
                 ({pee}, '{code}', 'Employer fund', {code_qty}, 100, {code_val}, now(), now()), \
                 ({pee}, 'MULYF', 'Slug matching a listed ticker', 80, 370, 30000, now(), now());"
            ),
        )
        .await;

        let points = get_json(
            &client,
            &format!("/api/history/net-worth/intraday?accountIds={pee}"),
        )
        .await;

        let points = points.as_array().unwrap();
        assert_eq!(points.len(), 25, "{provider}");
        for p in points {
            assert_eq!(
                dec(&p["total"]),
                dec(&json!(expected_total)),
                "{provider}: {p}"
            );
            assert_eq!(
                dec(&p["invested"]),
                dec(&json!(expected_invested)),
                "{provider}: {p}"
            );
        }
    }
}

#[tokio::test]
async fn pnl_range_leaves_out_a_quote_the_bank_contradicts() {
    let yahoo = yahoo_quotes_mulyf_at_376().await;
    let app = app(&yahoo).await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = bourso_pea(&app, alice).await;
    sql(
        &app,
        "INSERT INTO price_snapshot (ticker, date, price_eur) VALUES ('MULYF', '2025-12-16', 370);",
    )
    .await;

    let body = get_json(
        &client,
        &format!("/api/history/pnl?accountIds={pea}&from=2025-12-16"),
    )
    .await;

    assert_eq!(dec(&body["total"]), dec(&json!(453.08)));
    assert_eq!(dec(&body["pnl"]), dec(&json!(48.43)));
    // 80 x (376.28 - 370) = +502.40 of a move the position never made, without the guard.
    assert!(body.get("rangePnl").is_none(), "{body}");
}

#[tokio::test]
async fn pnl_range_leaves_out_a_never_market_priced_cmes_line_even_with_a_matching_quote() {
    let yahoo = yahoo_quotes_mulyf_at_376().await;
    let app = app(&yahoo).await;
    let (alice, client) = signed_in(&app, "alice").await;
    // The site's value agrees with the quote, so only the CMES guard keeps the line out.
    let pee = account(&app, Acct::new(alice, "EMPLOYEE_SAVINGS", "30102.40")).await;
    sql(
        &app,
        &format!(
            "UPDATE account SET provider = 'Crédit Mutuel Épargne Salariale', is_manual = false \
             WHERE id = {pee};\
             INSERT INTO account_holding (account_id, ticker, name, quantity, average_buy_in, \
             provider_value_eur, created_at, updated_at) \
             VALUES ({pee}, 'MULYF', 'Fund', 80, 370, 30102.40, now(), now());\
             INSERT INTO price_snapshot (ticker, date, price_eur) VALUES ('MULYF', '2025-12-16', 370);"
        ),
    )
    .await;

    let body = get_json(
        &client,
        &format!("/api/history/pnl?accountIds={pee}&from=2025-12-16"),
    )
    .await;

    assert_eq!(dec(&body["total"]), dec(&json!(30102.40)));
    // 80 x (376.28 - 370) = +502.40 without the guard.
    assert!(body.get("rangePnl").is_none(), "{body}");
}
