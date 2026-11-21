//! The price service and /api/prices: Yahoo and CoinGecko stubbed, cache, fallback, snapshots.

use std::collections::BTreeSet;

use picsou::testing::TestApp;
use rust_decimal::Decimal;
use serde_json::json;
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

fn d(value: &str) -> Decimal {
    value.parse().unwrap()
}

fn chart(currency: &str, price: f64) -> serde_json::Value {
    json!({"chart": {"result": [{
        "meta": {"currency": currency, "regularMarketPrice": price},
        "timestamp": [1768471200],
        "indicators": {"quote": [{"close": [price]}]}
    }], "error": null}})
}

async fn app(yahoo: &MockServer, coingecko: &MockServer) -> TestApp {
    TestApp::builder()
        .with_admin()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .env("COINGECKO_BASE_URL", coingecko.uri())
        .spawn()
        .await
}

async fn stub_chart(server: &MockServer, symbol: &str, currency: &str, price: f64, times: u64) {
    Mock::given(method("GET"))
        .and(path(format!("/v8/finance/chart/{symbol}")))
        .respond_with(ResponseTemplate::new(200).set_body_json(chart(currency, price)))
        .expect(times)
        .mount(server)
        .await;
}

#[tokio::test]
async fn get_prices_converts_to_eur_caches_and_records_todays_price() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    stub_chart(&yahoo, "AAPL", "USD", 100.0, 1).await;
    stub_chart(&yahoo, "USDEUR=X", "EUR", 0.9, 1).await;
    let app = app(&yahoo, &coingecko).await;
    let admin = app.login_admin().await;

    let response = admin.get("/api/prices?tickers=aapl,%20EUR,,").send().await;
    assert_eq!(response.status, 200, "{}", response.text());
    let body = response.json();
    assert_eq!(d(&body["AAPL"].to_string()), d("90"));
    assert_eq!(body["EUR"].to_string(), "1");

    // Served from the cache: the stubs expect one call each.
    let again = admin.get("/api/prices?tickers=AAPL").send().await.json();
    assert_eq!(d(&again["AAPL"].to_string()), d("90"));

    let recorded: Vec<(String, String)> =
        sqlx::query_as("SELECT ticker, price_eur::text FROM price_snapshot ORDER BY ticker")
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(recorded.len(), 1);
    assert_eq!(recorded[0].0, "AAPL");
    assert_eq!(d(&recorded[0].1), d("90"));

    let history = admin.get("/api/prices/aapl/history").send().await.json();
    assert_eq!(history[0]["date"], "2026-01-15");
    assert_eq!(d(&history[0]["priceEur"].to_string()), d("90"));
}

#[tokio::test]
async fn get_prices_requires_its_tickers() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    let app = app(&yahoo, &coingecko).await;
    let admin = app.login_admin().await;
    let response = admin.get("/api/prices").send().await;
    assert_eq!(response.status, 400);
    assert_eq!(response.json()["detail"], "Required parameter 'tickers' is not present.");
    let response = admin.get("/api/prices/AAPL/history?months=x").send().await;
    assert_eq!(response.status, 400);
}

#[tokio::test]
async fn a_provider_failure_falls_back_to_the_last_price_of_the_week() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&yahoo)
        .await;
    let app = app(&yahoo, &coingecko).await;
    for (ticker, date, price) in [("CW8", "2026-01-12", "500.5"), ("OLD", "2026-01-01", "10")] {
        sqlx::query("INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ($1, $2::date, $3::numeric, now())")
            .bind(ticker)
            .bind(date)
            .bind(price)
            .execute(&app.state.db)
            .await
            .unwrap();
    }
    let today = app.state.clock.today();
    let tickers = BTreeSet::from(["CW8".to_owned(), "OLD".to_owned(), "EUR".to_owned()]);
    let quotes = app.state.ledger.prices.quotes(&tickers, false, today).await;
    let cw8 = quotes["CW8"];
    assert!(!cw8.live);
    assert_eq!(cw8.price, d("500.5"));
    assert_eq!(cw8.as_of.to_string(), "2026-01-12");
    assert!(!quotes.contains_key("OLD"), "older than seven days");
    assert!(quotes["EUR"].live);
    assert_eq!(app.state.ledger.prices.fx_rate_eur("USD").await, None);
}

#[tokio::test]
async fn crypto_quotes_come_from_coingecko_and_never_from_an_equity_source() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/simple/price"))
        .and(query_param("vs_currencies", "eur"))
        .respond_with(ResponseTemplate::new(200).set_body_raw(r#"{"bitcoin":{"eur":50000.12}}"#, "application/json"))
        .mount(&coingecko)
        .await;
    let app = app(&yahoo, &coingecko).await;
    sqlx::query(
        "INSERT INTO crypto_asset (ticker, coingecko_id, name, source) VALUES ('BTC', 'bitcoin', 'Bitcoin', 'CURATED') \
         ON CONFLICT (ticker) DO UPDATE SET coingecko_id = 'bitcoin'",
    )
    .execute(&app.state.db)
    .await
    .unwrap();
    let today = app.state.clock.today();
    let tickers = BTreeSet::from(["BTC".to_owned(), "AAPL".to_owned()]);
    let quotes = app.state.ledger.prices.quotes(&tickers, true, today).await;
    assert_eq!(quotes.len(), 1);
    assert_eq!(quotes["BTC"].price.to_string(), "50000.12");
    assert!(yahoo.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn a_coingecko_429_pauses_the_next_calls() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(429).insert_header("Retry-After", "120"))
        .expect(1)
        .mount(&coingecko)
        .await;
    let app = app(&yahoo, &coingecko).await;
    sqlx::query(
        "INSERT INTO crypto_asset (ticker, coingecko_id, name, source) VALUES ('ZZZ', 'zzz-coin', 'Zzz', 'AUTO') ON CONFLICT DO NOTHING",
    )
    .execute(&app.state.db)
    .await
    .unwrap();
    let admin = app.login_admin().await;
    let first = admin.get("/api/prices?tickers=ZZZ").send().await.json();
    assert_eq!(first, json!({}));
    app.clock.advance(std::time::Duration::from_secs(90));
    let second = admin.get("/api/prices?tickers=ZZZ").send().await.json();
    assert_eq!(second, json!({}));
}

#[tokio::test]
async fn the_precious_metals_catalogue_is_listed_in_registry_order() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    let app = app(&yahoo, &coingecko).await;
    let body = app.login_admin().await.get("/api/prices/precious-metals").send().await.json();
    assert_eq!(body.as_array().unwrap().len(), 16);
    assert_eq!(body[0], json!({"ticker": "GOLD:GRAM", "name": "Gold (fine gram)", "unit": "GRAM"}));
}
