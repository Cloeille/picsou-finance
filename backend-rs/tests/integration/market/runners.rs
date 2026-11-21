//! The price runners: backfill over Yahoo, the one-off FX purge gated by app_setting.

use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::model::AccountType;
use picsou::market::runners::{SqlAppSettings, price_backfill, price_fx_cleanup};
use picsou::testing::TestApp;
use serde_json::json;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

#[tokio::test]
async fn the_backfill_records_the_daily_series_of_held_tickers_once() {
    let yahoo = MockServer::start().await;
    let day = |d: i64| 1_768_478_400 - d * 86_400;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/CW8.PA"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({"chart": {"result": [{
                "meta": {"currency": "EUR", "regularMarketPrice": 500.0},
                "timestamp": [day(2), day(1)],
                "indicators": {"quote": [{"close": [498.5, 501.25]}]}
            }]}})),
        )
        .expect(1)
        .mount(&yahoo)
        .await;
    let app = TestApp::builder()
        .with_admin()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .spawn()
        .await;
    let member: i64 = sqlx::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    let pea = accounts::insert(
        &app.state.db,
        &NewAccount::new(member, "PEA", AccountType::Pea),
        app.state.clock.now(),
    )
    .await
    .unwrap();
    sqlx::query("INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) VALUES ($1, 'cw8.pa', 3, now(), now())")
        .bind(pea.id)
        .execute(&app.state.db)
        .await
        .unwrap();
    assert_eq!(price_backfill(&app.state).await.unwrap(), 2);
    let rows: Vec<(String, String)> =
        sqlx::query_as("SELECT ticker, price_eur::text FROM price_snapshot ORDER BY date")
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(rows[0], ("CW8.PA".to_owned(), "498.50000000".to_owned()));
    // A series without holes is not fetched again (the stub expects one call).
    sqlx::query("INSERT INTO price_snapshot (ticker, date, price_eur, created_at) SELECT 'CW8.PA', d::date, 500, now() \
                 FROM generate_series(DATE '2025-01-15', DATE '2026-01-12', INTERVAL '5 days') d ON CONFLICT DO NOTHING")
        .execute(&app.state.db)
        .await
        .unwrap();
    assert_eq!(price_backfill(&app.state).await.unwrap(), 0);
}

#[tokio::test]
async fn the_fx_cleanup_purges_once_behind_its_flag() {
    let app = TestApp::with_admin().await;
    let settings = SqlAppSettings(app.state.db.clone());
    sqlx::query("INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ('AAPL', '2020-01-02', 100, now())")
        .execute(&app.state.db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO app_setting (setting_key, value) VALUES ('price.fx_fix_cleanup_done', 'false') \
                 ON CONFLICT (setting_key) DO UPDATE SET value = 'false'")
        .execute(&app.state.db)
        .await
        .unwrap();
    assert_eq!(price_fx_cleanup(&app.state, &settings).await.unwrap(), 1);
    sqlx::query("INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ('AAPL', '2020-01-02', 100, now())")
        .execute(&app.state.db)
        .await
        .unwrap();
    assert_eq!(price_fx_cleanup(&app.state, &settings).await.unwrap(), 0);
}
