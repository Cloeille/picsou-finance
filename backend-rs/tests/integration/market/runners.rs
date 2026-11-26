//! The price runners: backfill over Yahoo, the one-off FX purge gated by app_setting, and the
//! raw-ISIN manual-transaction repair pass (IsinTickerRepairRunner).

use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::model::AccountType;
use picsou::market::runners::{
    AppSettings, SqlAppSettings, isin_ticker_repair, price_backfill, price_fx_cleanup,
    schedule_security_profile_recheck,
};
use picsou::testing::TestApp;
use serde_json::json;
use wiremock::matchers::{body_string_contains, method, path};
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
    // A CMES fund code is quoted nowhere: it is never asked for.
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/CMES-1613"))
        .respond_with(ResponseTemplate::new(404))
        .expect(0)
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
    let mut cmes = NewAccount::new(member, "PEE", AccountType::EmployeeSavings);
    cmes.provider = Some(picsou::ledger::providers::CMES.to_owned());
    let cmes = accounts::insert(&app.state.db, &cmes, app.state.clock.now())
        .await
        .unwrap();
    picsou::db::query("INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) VALUES ($1, 'cw8.pa', 3, now(), now()), ($2, 'CMES-1613', 10, now(), now())")
        .bind(pea.id)
        .bind(cmes.id)
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

#[tokio::test]
async fn isin_repair_records_isin_only_for_non_crypto_investment_accounts() {
    let openfigi = MockServer::start().await;
    let yahoo = MockServer::start().await;
    for (isin, ticker, exch, name, yahoo_symbol) in [
        (
            "IE000BI8OT95",
            "MWRD",
            "FP",
            "AMUNDI CORE MSCI WORLD",
            "MWRD.PA",
        ),
        ("US0378331005", "AAPL", "US", "APPLE INC", "AAPL"),
        ("FR0000120271", "TTE", "FP", "TOTALENERGIES", "TTE.PA"),
    ] {
        Mock::given(method("POST"))
            .and(path("/v3/mapping"))
            .and(body_string_contains(isin))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!([
                {"data": [{"ticker": ticker, "exchCode": exch, "name": name}]}
            ])))
            .mount(&openfigi)
            .await;
        Mock::given(method("GET"))
            .and(path(format!("/v8/finance/chart/{yahoo_symbol}")))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "chart": {"result": [{"meta": {"currency": "EUR", "regularMarketPrice": 100.0}}]}
            })))
            .mount(&yahoo)
            .await;
    }

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
    let crypto = accounts::insert(
        &app.state.db,
        &NewAccount::new(member, "Crypto", AccountType::Crypto),
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

    picsou::db::query(
        "INSERT INTO transaction (account_id, date, amount, tx_type, is_manual, ticker, quantity, price_per_unit, created_at) VALUES \
         ($1, '2026-01-10', -1000, 'BUY', true, 'IE000BI8OT95', 8, 125, now()), \
         ($2, '2026-01-10', -1000, 'BUY', true, 'IE000BI8OT95', 8, 125, now()), \
         ($2, '2026-01-10', -500, 'BUY', true, 'US0378331005', 2, 250, now()), \
         ($3, '2026-01-10', -100, 'BUY', true, 'FR0000120271', 1, 100, now())",
    )
    .bind(pea.id)
    .bind(crypto.id)
    .bind(checking.id)
    .execute(&app.state.db)
    .await
    .unwrap();

    let settings = SqlAppSettings(app.state.db.clone());
    assert_eq!(isin_ticker_repair(&app.state, &settings).await.unwrap(), 4);

    let pea_holdings: Vec<(String, Option<String>)> = picsou::db::query_as(
        "SELECT ticker, isin FROM account_holding WHERE account_id = $1 ORDER BY ticker",
    )
    .bind(pea.id)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        pea_holdings,
        vec![("MWRD.PA".to_owned(), Some("IE000BI8OT95".to_owned()))]
    );

    let crypto_holdings: Vec<(String, Option<String>)> = picsou::db::query_as(
        "SELECT ticker, isin FROM account_holding WHERE account_id = $1 ORDER BY ticker",
    )
    .bind(crypto.id)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        crypto_holdings,
        vec![("AAPL".to_owned(), None), ("MWRD.PA".to_owned(), None)]
    );

    let checking_holdings: Vec<(String, Option<String>)> = picsou::db::query_as(
        "SELECT ticker, isin FROM account_holding WHERE account_id = $1 ORDER BY ticker",
    )
    .bind(checking.id)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert!(checking_holdings.is_empty());

    let profiles: Vec<(String, Option<String>)> =
        picsou::db::query_as("SELECT ticker, isin FROM security_profile ORDER BY ticker")
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(
        profiles,
        vec![("MWRD.PA".to_owned(), Some("IE000BI8OT95".to_owned()))]
    );
}

#[tokio::test]
async fn profile_recheck_staggers_affected_profiles_with_99_threshold_once() {
    let app = TestApp::with_admin().await;
    // TestApp runs startup runners on the empty database; clear the flag to simulate an upgrade
    // from a pre-fix database carrying persisted profiles.
    picsou::db::query(
        "DELETE FROM app_setting WHERE setting_key = 'security_profile.composition_fix_v1_done'",
    )
    .execute(&app.state.db)
    .await
    .unwrap();
    let now = app.state.clock.now();
    picsou::db::query(
        "INSERT INTO security_profile (id, ticker, asset_type, isin, sector_key, country_key, replication, status, refreshed_at, created_at, updated_at) VALUES \
         (1, 'ETF_PARTIAL', 'ETF', 'FR0011550185', NULL, NULL, 'PHYSICAL', 'OK', $1, $1, $1), \
         (2, 'ETF_SYNTH', 'ETF', 'FR0011550193', NULL, NULL, 'SYNTHETIC', 'OK', $1, $1, $1), \
         (3, 'ETF_99_5', 'ETF', 'IE00B4L5Y983', NULL, NULL, 'PHYSICAL', 'OK', $1, $1, $1), \
         (4, 'STOCK_NOC', 'STOCK', NULL, 'technology', NULL, NULL, 'OK', $1, $1, $1), \
         (5, 'STOCK_OK', 'STOCK', 'US0378331005', 'technology', 'US', NULL, 'OK', $1, $1, $1), \
         (6, 'RAW_ISIN', 'UNKNOWN', 'FR0013412020', NULL, NULL, NULL, 'NO_DATA', $1, $1, $1)",
    )
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();
    // Add 8 extra affected countryless stocks so we span 2 batches (12 affected total: 10 in batch 0, 2 in batch 1).
    for i in 0..8_i64 {
        picsou::db::query(
            "INSERT INTO security_profile (id, ticker, asset_type, sector_key, country_key, status, refreshed_at, created_at, updated_at) \
             VALUES ($1, $2, 'STOCK', 'technology', NULL, 'OK', $3, $3, $3)",
        )
        .bind(10 + i)
        .bind(format!("STOCK_EXTRA_{i:02}"))
        .bind(now)
        .execute(&app.state.db)
        .await
        .unwrap();
    }
    // ETF_PARTIAL has 98.5 % (< 99 % -> affected); ETF_99_5 has 99.5 % (>= 99 % -> unaffected).
    picsou::db::query(
        "INSERT INTO security_composition_slice (profile_id, kind, label, percent) VALUES \
         (1, 'SECTOR', 'technology', 98.500), (1, 'COUNTRY', 'US', 99.520), \
         (2, 'SECTOR', 'financial_services', 100.000), (2, 'COUNTRY', 'FR', 100.000), \
         (3, 'SECTOR', 'technology', 99.500), (3, 'COUNTRY', 'US', 99.520)",
    )
    .execute(&app.state.db)
    .await
    .unwrap();

    assert_eq!(
        schedule_security_profile_recheck(&app.state).await.unwrap(),
        12
    );

    let rows: Vec<(String, String, Option<chrono::DateTime<chrono::Utc>>)> = picsou::db::query_as(
        "SELECT ticker, status, refreshed_at FROM security_profile ORDER BY id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    let by_ticker: std::collections::HashMap<_, _> =
        rows.into_iter().map(|(t, s, r)| (t, (s, r))).collect();

    // Unaffected rows keep their exact original refreshed_at (99.5 % is >= 99 threshold).
    assert_eq!(by_ticker["ETF_99_5"], ("OK".to_owned(), Some(now)));
    assert_eq!(by_ticker["STOCK_OK"], ("OK".to_owned(), Some(now)));

    // Batch 0 (ids 1, 2, 4, 6, 10..15): staggered to now - 31d (due immediately, not NULL).
    let batch0 = now - chrono::Duration::days(31);
    for t in [
        "ETF_PARTIAL",
        "ETF_SYNTH",
        "STOCK_NOC",
        "STOCK_EXTRA_00",
        "STOCK_EXTRA_05",
    ] {
        assert_eq!(by_ticker[t], ("OK".to_owned(), Some(batch0)), "for {t}");
    }
    assert_eq!(by_ticker["RAW_ISIN"], ("NO_DATA".to_owned(), Some(batch0)));

    // Batch 1 (ids 16, 17): staggered to now - 24d (due in ~6 days on next weekly pass).
    let batch1 = now - chrono::Duration::days(24);
    for t in ["STOCK_EXTRA_06", "STOCK_EXTRA_07"] {
        assert_eq!(by_ticker[t], ("OK".to_owned(), Some(batch1)), "for {t}");
    }

    // Second run is a no-op once the atomic app_setting flag is claimed.
    assert_eq!(
        schedule_security_profile_recheck(&app.state).await.unwrap(),
        0
    );
}
