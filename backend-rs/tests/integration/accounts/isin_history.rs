//! History by ISIN (#702): the backfill and the hourly refresh record prices under the ISIN, the
//! 24H chart holds an ISIN line at the bank's value, and the range P&L reads ISIN prices on both
//! sides. justETF is stubbed; Yahoo quotes the ticker the line kept, which must never be read.

use picsou::market::runners::price_backfill;
use picsou::testing::TestApp;
use serde_json::json;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::analysis_support::{Acct, account, dec, get_json, signed_in, sql};

const LU: &str = "LU1829219390";
/// 2026-01-15T10:00:00Z, the instant every TestApp clock starts at.
const NOW: i64 = 1_768_471_200;

async fn justetf() -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path(format!("/api/etfs/{LU}/quote")))
        .respond_with(ResponseTemplate::new(200).set_body_raw(
            r#"{"latestQuote":{"raw":4.41,"localized":"4.41"},"latestQuoteDate":"2026-01-14"}"#,
            "application/json",
        ))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path(format!("/api/etfs/{LU}/performance-chart")))
        .respond_with(ResponseTemplate::new(200).set_body_raw(
            r#"{"series":[{"date":"2026-01-13","value":{"raw":4.30}},{"date":"2026-01-14","value":{"raw":4.41}}]}"#,
            "application/json",
        ))
        .mount(&server)
        .await;
    server
}

/// Yahoo quotes the kept ticker MULYF at 4.80 EUR every hour and every day: close enough to the
/// bank's 4.41 that the #690 guard lets it through, so only keying by ISIN keeps it out.
async fn yahoo_mulyf() -> MockServer {
    let server = MockServer::start().await;
    let timestamps: Vec<i64> = (0..=24).map(|h| NOW - 3600 * (24 - h)).collect();
    let closes = vec![4.80; timestamps.len()];
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/MULYF"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({"chart": {"result": [{
                "meta": {"currency": "EUR", "regularMarketPrice": 4.80},
                "timestamp": timestamps,
                "indicators": {"quote": [{"close": closes}]}
            }], "error": null}})),
        )
        .mount(&server)
        .await;
    server
}

async fn app(justetf: &MockServer, yahoo: &MockServer) -> TestApp {
    TestApp::builder()
        .with_admin()
        .env("JUSTETF_BASE_URL", justetf.uri())
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .spawn()
        .await
}

/// A PEA line of 80 units carrying its ISIN and the grey-market ticker it was once resolved to.
async fn pea(app: &TestApp, member: i64, provider_value: Option<&str>) -> i64 {
    let pea = account(app, Acct::new(member, "PEA", "453.08")).await;
    let (provider, value) = match provider_value {
        Some(v) => ("'BoursoBank'", v),
        None => ("NULL", "NULL"),
    };
    sql(
        app,
        &format!(
            "UPDATE account SET provider = {provider}, is_manual = {manual}, cash_balance = 100 \
             WHERE id = {pea};\
             INSERT INTO account_holding (account_id, ticker, isin, name, quantity, average_buy_in, \
             provider_value_eur, provider_pnl_eur, provider_price_date, created_at, updated_at) \
             VALUES ({pea}, 'MULYF', '{LU}', 'Amundi Euro Stoxx Banks', 80, 3.81, {value}, \
             {pnl}, '2026-01-10', now(), now());",
            manual = provider_value.is_none(),
            pnl = if provider_value.is_some() { "48.43" } else { "NULL" },
        ),
    )
    .await;
    pea
}

async fn recorded(app: &TestApp, key: &str) -> Vec<(String, String)> {
    picsou::db::query_as(
        "SELECT date::text, price_eur::text FROM price_snapshot WHERE ticker = $1 ORDER BY date",
    )
    .bind(key)
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn the_backfill_records_the_isin_daily_history_under_the_isin() {
    let (justetf, yahoo) = (justetf().await, yahoo_mulyf().await);
    let app = app(&justetf, &yahoo).await;
    let (alice, _) = signed_in(&app, "alice").await;
    pea(&app, alice, Some("353.08")).await;

    price_backfill(&app.state).await.unwrap();

    assert_eq!(
        recorded(&app, LU).await,
        vec![
            ("2026-01-13".to_owned(), "4.30000000".to_owned()),
            ("2026-01-14".to_owned(), "4.41000000".to_owned()),
        ]
    );
}

#[tokio::test]
async fn the_hourly_refresh_records_the_isin_quote_on_its_own_date() {
    let (justetf, yahoo) = (justetf().await, yahoo_mulyf().await);
    let app = app(&justetf, &yahoo).await;
    let (alice, _) = signed_in(&app, "alice").await;
    pea(&app, alice, Some("353.08")).await;

    picsou::jobs::scheduler::refresh_prices(&app.state)
        .await
        .unwrap();

    assert_eq!(
        recorded(&app, LU).await,
        vec![("2026-01-14".to_owned(), "4.41000000".to_owned())]
    );
}

#[tokio::test]
async fn intraday_holds_an_isin_line_at_its_isin_value_not_its_tickers_hourly_series() {
    let (justetf, yahoo) = (justetf().await, yahoo_mulyf().await);
    let app = app(&justetf, &yahoo).await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = pea(&app, alice, Some("353.08")).await;

    let points = get_json(
        &client,
        &format!("/api/history/net-worth/intraday?accountIds={pea}"),
    )
    .await;

    let points = points.as_array().unwrap();
    assert_eq!(points.len(), 25);
    for p in points {
        // 80 x 4.41 + 100, as the daily chart values it. MULYF's hourly closes give 484.
        assert_eq!(dec(&p["total"]), dec(&json!(452.8)), "{p}");
    }
}

#[tokio::test]
async fn intraday_holds_an_isin_line_with_no_price_at_the_bank_value() {
    let (justetf, yahoo) = (MockServer::start().await, MockServer::start().await);
    let app = app(&justetf, &yahoo).await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = pea(&app, alice, Some("353.08")).await;

    let points = get_json(
        &client,
        &format!("/api/history/net-worth/intraday?accountIds={pea}"),
    )
    .await;

    let points = points.as_array().unwrap();
    assert_eq!(points.len(), 25);
    for p in points {
        assert_eq!(dec(&p["total"]), dec(&json!(453.08)), "{p}");
    }
}

#[tokio::test]
async fn range_pnl_of_an_isin_line_reads_isin_prices_on_both_sides() {
    let (justetf, yahoo) = (justetf().await, yahoo_mulyf().await);
    let app = app(&justetf, &yahoo).await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = pea(&app, alice, None).await;
    sql(
        &app,
        &format!(
            "INSERT INTO price_snapshot (ticker, date, price_eur) VALUES \
             ('{LU}', '2025-12-16', 4.00), ('MULYF', '2025-12-16', 3.00);"
        ),
    )
    .await;

    let body = get_json(
        &client,
        &format!("/api/history/pnl?accountIds={pea}&from=2025-12-16"),
    )
    .await;

    // 80 x (4.41 - 4.00). Through the ticker it would be 80 x (4.80 - 3.00) = 144.
    assert_eq!(dec(&body["valueAtFrom"]), dec(&json!(320)));
    assert_eq!(dec(&body["rangePnl"]), dec(&json!(32.80)));
}

#[tokio::test]
async fn an_isin_line_with_no_isin_price_at_from_stays_out_of_the_range_pnl() {
    let (justetf, yahoo) = (justetf().await, yahoo_mulyf().await);
    let app = app(&justetf, &yahoo).await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = pea(&app, alice, None).await;
    sql(
        &app,
        "INSERT INTO price_snapshot (ticker, date, price_eur) VALUES ('MULYF', '2025-12-16', 3.00);",
    )
    .await;

    let body = get_json(
        &client,
        &format!("/api/history/pnl?accountIds={pea}&from=2025-12-16"),
    )
    .await;

    assert!(body.get("rangePnl").is_none(), "{body}");
}

#[tokio::test]
async fn the_holding_carries_its_isin_and_its_price_history_is_read_under_it() {
    let (justetf, yahoo) = (justetf().await, yahoo_mulyf().await);
    let app = app(&justetf, &yahoo).await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = pea(&app, alice, Some("353.08")).await;
    price_backfill(&app.state).await.unwrap();

    let lines = get_json(&client, &format!("/api/accounts/{pea}/holdings")).await;
    let history = get_json(&client, &format!("/api/prices/{LU}/history")).await;
    let intraday = get_json(&client, &format!("/api/prices/{LU}/intraday")).await;

    assert_eq!(lines[0]["isin"], LU);
    assert_eq!(history.as_array().unwrap().len(), 2);
    assert_eq!(intraday, json!([]));
}

#[tokio::test]
async fn intraday_follows_the_isin_quote_over_a_frozen_import_value_as_the_daily_chart_does() {
    let (justetf, yahoo) = (justetf().await, yahoo_mulyf().await);
    let app = app(&justetf, &yahoo).await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = pea(&app, alice, None).await;
    // A manual line carrying the value an import froze: the live ISIN quote wins over it.
    sql(
        &app,
        &format!("UPDATE account_holding SET provider_value_eur = 353.08 WHERE account_id = {pea}"),
    )
    .await;

    let points = get_json(
        &client,
        &format!("/api/history/net-worth/intraday?accountIds={pea}"),
    )
    .await;

    for p in points.as_array().unwrap() {
        // 80 x 4.41 + 100 cash.
        assert_eq!(dec(&p["total"]), dec(&json!(452.8)), "{p}");
    }
}
