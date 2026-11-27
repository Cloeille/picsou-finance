//! The security insight endpoint: crypto registry, quote source, and persisted ISIN forwarding.

use picsou::testing::TestApp;
use serde_json::json;
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::analysis_support::{get_json, signed_in};

#[tokio::test]
async fn a_registered_coin_is_crypto_and_anything_else_unknown_without_a_quote_source() {
    let app = TestApp::with_admin().await;
    let (_, client) = signed_in(&app, "alice").await;
    assert_eq!(
        get_json(&client, "/api/securities/eth/insight").await,
        json!({"ticker": "ETH", "assetType": "CRYPTO"})
    );
    assert_eq!(
        get_json(&client, "/api/securities/PSUNKNOWN/insight?name=Amundi").await,
        json!({"ticker": "PSUNKNOWN", "assetType": "UNKNOWN"})
    );
    assert_eq!(
        app.client()
            .get("/api/securities/ETH/insight")
            .send()
            .await
            .status,
        401
    );
}

#[tokio::test]
async fn insight_endpoint_reads_stored_isin_from_security_profile() {
    let yahoo = MockServer::start().await;
    let boursorama = MockServer::start().await;
    let justetf = MockServer::start().await;

    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/CW8.PA"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "chart": {"result": [{"meta": {"instrumentType": "ETF"}}], "error": null}
        })))
        .mount(&yahoo)
        .await;
    // Boursorama resolves via the stored ISIN rather than bare ticker.
    Mock::given(method("GET"))
        .and(path("/recherche/"))
        .and(query_param("query", "LU1681043599"))
        .respond_with(ResponseTemplate::new(302).insert_header("Location", "/cours/1rTCW8/"))
        .mount(&boursorama)
        .await;
    let html = r#"
        <script>
        {"id":"regional","amChartData":[{"name":"États-Unis","value":70.00}]}
        {"id":"sector","amChartData":[{"name":"Technologie","value":25.00}]}
        </script>
    "#;
    Mock::given(method("GET"))
        .and(path("/bourse/trackers/cours/composition/1rTCW8/"))
        .respond_with(ResponseTemplate::new(200).set_body_string(html))
        .mount(&boursorama)
        .await;

    let app = TestApp::builder()
        .with_admin()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .env("BOURSORAMA_BASE_URL", boursorama.uri())
        .env("JUSTETF_BASE_URL", justetf.uri())
        .spawn()
        .await;
    let (_, client) = signed_in(&app, "alice").await;
    picsou::db::query(
        "INSERT INTO security_profile (ticker, asset_type, isin, status, created_at, updated_at) \
         VALUES ('CW8.PA', 'ETF', 'LU1681043599', 'NEVER_FETCHED', now(), now())",
    )
    .execute(&app.state.db)
    .await
    .unwrap();

    let resp = get_json(&client, "/api/securities/cw8.pa/insight").await;
    assert_eq!(resp["assetType"], "ETF");
    assert_eq!(resp["composition"]["countries"][0]["label"], "US");
    assert_eq!(resp["composition"]["sectors"][0]["label"], "technology");
}
