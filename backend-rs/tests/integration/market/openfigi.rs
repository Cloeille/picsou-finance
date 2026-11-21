//! ISIN to ticker: the home listing Yahoo quotes, Yahoo's search as the fallback, the cache,
//! Trade Republic's crypto pseudo-ISINs.

use picsou::market::openfigi::{is_isin, is_tr_crypto_isin, pick_best};
use picsou::testing::TestApp;
use serde_json::json;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

#[test]
fn the_home_exchange_wins_then_a_us_listing_then_europe() {
    let entries = vec![
        json!({"ticker": "TTE", "exchCode": "US", "name": "TOTALENERGIES SE-SPON ADR"}),
        json!({"ticker": "TTE", "exchCode": "FP", "name": "TOTALENERGIES SE"}),
        json!({"ticker": "TOTB", "exchCode": "GY", "name": "TOTALENERGIES SE"}),
    ];
    assert_eq!(
        pick_best("FR0000120271", &entries).unwrap().ticker,
        "TTE.PA"
    );
    assert_eq!(pick_best("LU0000000000", &entries).unwrap().ticker, "TTE");
    let europe = vec![
        json!({"ticker": "X", "exchCode": "ZZ"}),
        json!({"ticker": "IWDA", "exchCode": "NA"}),
    ];
    assert_eq!(
        pick_best("IE00B4L5Y983", &europe).unwrap().ticker,
        "IWDA.AS"
    );
    let unmapped = vec![
        json!({"ticker": "bad ticker!", "exchCode": "ZZ"}),
        json!({"ticker": "abc", "exchCode": "ZZ"}),
    ];
    assert_eq!(pick_best("IE00B4L5Y983", &unmapped).unwrap().ticker, "ABC");
    assert!(is_isin(" fr0000120271 "));
    assert!(!is_isin("TTE.PA"));
    assert!(is_tr_crypto_isin("XF000BTC0017"));
}

#[tokio::test]
async fn an_isin_resolves_once_to_a_quoted_listing() {
    let figi = MockServer::start().await;
    let yahoo = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v3/mapping"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([{"data": [
            {"ticker": "TTE", "exchCode": "FP", "name": "TOTALENERGIES SE"}
        ]}])))
        .expect(1)
        .mount(&figi)
        .await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/TTE.PA"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({"chart": {"result": [{
                "meta": {"currency": "EUR", "regularMarketPrice": 55.2}
            }]}})),
        )
        .mount(&yahoo)
        .await;
    let app = TestApp::builder()
        .env("OPENFIGI_BASE_URL", figi.uri())
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .spawn()
        .await;
    let openfigi = app.state.market.openfigi();
    let first = openfigi.resolve("fr0000120271").await;
    assert_eq!(first.ticker, "TTE.PA");
    assert_eq!(first.name.as_deref(), Some("TOTALENERGIES SE"));
    assert_eq!(openfigi.resolve("FR0000120271").await, first);

    sqlx::query("INSERT INTO crypto_asset (ticker, coingecko_id, name, source) VALUES ('BTC', 'bitcoin', 'Bitcoin', 'CURATED') \
                 ON CONFLICT (ticker) DO UPDATE SET name = 'Bitcoin'")
        .execute(&app.state.db)
        .await
        .unwrap();
    let btc = openfigi.resolve("XF000BTC0017").await;
    assert_eq!(
        (btc.ticker.as_str(), btc.name.as_deref()),
        ("BTC", Some("Bitcoin"))
    );
}

#[tokio::test]
async fn an_unmapped_isin_falls_back_to_itself() {
    let figi = MockServer::start().await;
    let yahoo = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!([{"warning": "No identifier found."}])),
        )
        .mount(&figi)
        .await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"quotes": []})))
        .mount(&yahoo)
        .await;
    let app = TestApp::builder()
        .env("OPENFIGI_BASE_URL", figi.uri())
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .spawn()
        .await;
    let result = app.state.market.openfigi().resolve("XS1234567890").await;
    assert_eq!(result.ticker, "XS1234567890");
    assert!(result.name.is_none());
}
