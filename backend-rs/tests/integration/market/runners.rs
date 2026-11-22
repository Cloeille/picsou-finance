//! The price runners: backfill over Yahoo, the one-off FX purge gated by app_setting, and the
//! raw-ISIN manual-transaction repair pass (IsinTickerRepairRunner).

use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::model::AccountType;
use picsou::market::runners::{
    AppSettings, SqlAppSettings, isin_ticker_repair, price_backfill, price_fx_cleanup,
};
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
    let member: i64 =
        picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
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
    picsou::db::query("INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) VALUES ($1, 'cw8.pa', 3, now(), now())")
        .bind(pea.id)
        .execute(&app.state.db)
        .await
        .unwrap();
    assert_eq!(price_backfill(&app.state).await.unwrap(), 2);
    let rows: Vec<(String, String)> =
        picsou::db::query_as("SELECT ticker, price_eur::text FROM price_snapshot ORDER BY date")
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(rows[0], ("CW8.PA".to_owned(), "498.50000000".to_owned()));
    // A series without holes is not fetched again (the stub expects one call).
    let mut day = chrono::NaiveDate::from_ymd_opt(2025, 1, 15).unwrap();
    let end = chrono::NaiveDate::from_ymd_opt(2026, 1, 12).unwrap();
    while day <= end {
        picsou::db::query(
            "INSERT INTO price_snapshot (ticker, date, price_eur, created_at) \
             VALUES ('CW8.PA', $1, 500, now()) ON CONFLICT DO NOTHING",
        )
        .bind(day)
        .execute(&app.state.db)
        .await
        .unwrap();
        day += chrono::TimeDelta::days(5);
    }
    assert_eq!(price_backfill(&app.state).await.unwrap(), 0);
}

#[tokio::test]
async fn the_fx_cleanup_purges_once_behind_its_flag() {
    let app = TestApp::with_admin().await;
    let settings = SqlAppSettings(app.state.db.clone());
    picsou::db::query("INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ('AAPL', '2020-01-02', 100, now())")
        .execute(&app.state.db)
        .await
        .unwrap();
    picsou::db::query("INSERT INTO app_setting (setting_key, value) VALUES ('price.fx_fix_cleanup_done', 'false') \
                 ON CONFLICT (setting_key) DO UPDATE SET value = 'false'")
        .execute(&app.state.db)
        .await
        .unwrap();
    assert_eq!(price_fx_cleanup(&app.state, &settings).await.unwrap(), 1);
    picsou::db::query("INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ('AAPL', '2020-01-02', 100, now())")
        .execute(&app.state.db)
        .await
        .unwrap();
    assert_eq!(price_fx_cleanup(&app.state, &settings).await.unwrap(), 0);
}

#[tokio::test]
async fn isin_repair_rewrites_transactions_drops_orphan_holdings_and_preserves_user_names() {
    let openfigi = MockServer::start().await;
    let yahoo = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v3/mapping"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([
            {"data": [{"ticker": "MWRD", "exchCode": "FP", "name": "AMUNDI CORE MSCI WORLD"}]}
        ])))
        .mount(&openfigi)
        .await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/MWRD.PA"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "chart": {"result": [{"meta": {"currency": "EUR", "regularMarketPrice": 125.0}}]}
        })))
        .mount(&yahoo)
        .await;

    let app = TestApp::builder()
        .with_admin()
        .env("OPENFIGI_BASE_URL", openfigi.uri())
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .spawn()
        .await;
    let member: i64 =
        picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
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
    let checking = accounts::insert(
        &app.state.db,
        &NewAccount::new(member, "Checking", AccountType::Checking),
        app.state.clock.now(),
    )
    .await
    .unwrap();

    // Two manual BUY rows on PEA (one unnamed, one with user-typed name), plus a stray row on
    // checking (must repair ticker without deriving holdings) and a non-ISIN 12-char ticker.
    picsou::db::query(
        "INSERT INTO transaction (account_id, date, amount, tx_type, is_manual, ticker, name, quantity, price_per_unit, created_at) VALUES \
         ($1, '2026-01-05', -1000, 'BUY', true, 'IE000BI8OT95', NULL, 8, 125, now()), \
         ($1, '2026-01-10', -500, 'BUY', true, 'IE000BI8OT95', 'Mon ETF Monde', 4, 125, now()), \
         ($2, '2026-01-10', -100, 'BUY', true, 'IE000BI8OT95', NULL, 1, 100, now()), \
         ($1, '2026-01-10', -100, 'BUY', true, '123456789012', NULL, 1, 100, now())",
    )
    .bind(pea.id)
    .bind(checking.id)
    .execute(&app.state.db)
    .await
    .unwrap();
    picsou::db::query(
        "INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) VALUES ($1, 'IE000BI8OT95', 12, now(), now())",
    )
    .bind(pea.id)
    .execute(&app.state.db)
    .await
    .unwrap();

    let settings = SqlAppSettings(app.state.db.clone());
    assert_eq!(isin_ticker_repair(&app.state, &settings).await.unwrap(), 3);

    // PEA transactions now carry MWRD.PA; unnamed got OpenFIGI's name, named kept user's name.
    let names: Vec<(String, Option<String>)> = picsou::db::query_as(
        "SELECT ticker, name FROM transaction WHERE account_id = $1 ORDER BY id",
    )
    .bind(pea.id)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        names,
        vec![
            (
                "MWRD.PA".to_owned(),
                Some("AMUNDI CORE MSCI WORLD".to_owned())
            ),
            ("MWRD.PA".to_owned(), Some("Mon ETF Monde".to_owned())),
            ("123456789012".to_owned(), None),
        ]
    );

    // Old ISIN holding dropped; new MWRD.PA holding computed with quantity 12.
    let pea_holdings: Vec<(String, String)> = picsou::db::query_as(
        "SELECT ticker, quantity::text FROM account_holding WHERE account_id = $1 ORDER BY ticker",
    )
    .bind(pea.id)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        pea_holdings,
        vec![
            ("123456789012".to_owned(), "1.00000000".to_owned()),
            ("MWRD.PA".to_owned(), "12.00000000".to_owned()),
        ]
    );

    // Checking account gets no derived holdings.
    let checking_holdings: i64 =
        picsou::db::query_scalar("SELECT count(*) FROM account_holding WHERE account_id = $1")
            .bind(checking.id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(checking_holdings, 0);

    // Cursor recorded in app_setting.
    assert_eq!(
        settings
            .value("isin_repair.cursor")
            .await
            .unwrap()
            .as_deref(),
        Some("IE000BI8OT95")
    );
}

#[tokio::test]
async fn isin_repair_respects_per_boot_limit_and_resumes_past_cursor() {
    let openfigi = MockServer::start().await;
    let yahoo = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v3/mapping"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([{"error": "No identifier"}])))
        .mount(&openfigi)
        .await;
    Mock::given(method("GET"))
        .and(path("/v1/finance/search"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"quotes": []})))
        .mount(&yahoo)
        .await;

    let app = TestApp::builder()
        .with_admin()
        .env("OPENFIGI_BASE_URL", openfigi.uri())
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .spawn()
        .await;
    let member: i64 =
        picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
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

    for i in 0..30 {
        let isin = format!("IE00000000{i:02}");
        picsou::db::query(
            "INSERT INTO transaction (account_id, date, amount, tx_type, is_manual, ticker, created_at) \
             VALUES ($1, '2026-01-10', -100, 'BUY', true, $2, now())",
        )
        .bind(pea.id)
        .bind(isin)
        .execute(&app.state.db)
        .await
        .unwrap();
    }

    let settings = SqlAppSettings(app.state.db.clone());
    // Seed cursor at IE0000000023 -> next boot starts at IE0000000024, wraps around through 00..18 (25 total).
    settings
        .upsert("isin_repair.cursor", "IE0000000023")
        .await
        .unwrap();
    assert_eq!(isin_ticker_repair(&app.state, &settings).await.unwrap(), 0);
    assert_eq!(
        settings
            .value("isin_repair.cursor")
            .await
            .unwrap()
            .as_deref(),
        Some("IE0000000018")
    );
}
