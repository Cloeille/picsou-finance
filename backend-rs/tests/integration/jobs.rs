//! Integration tests for Track T8: cross-track seam wiring, startup runners, CatchUpScheduler,
//! SchedulerService jobs, and periodic cleanup jobs.

use std::collections::HashSet;
use std::time::Duration;

use chrono::{DateTime, Utc};
use picsou::clock::Clock;
use picsou::jobs::catch_up::{
    CatchUpScheduler, DAILY_BANK_SYNC, DAILY_SNAPSHOTS, KEY_PREFIX, MONTHLY_PROPERTY_VALUATION,
    WEEKLY_DIVIDEND_HISTORY, WEEKLY_SECURITY_PROFILES,
};
use picsou::jobs::{scheduler, spawn_schedulers, startup};
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::model::AccountType;
use picsou::ledger::sidecar::SyncStatus;
use picsou::sync::member_sync;
use picsou::testing::TestApp;
use rust_decimal::Decimal;
use serde_json::json;
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

fn d(s: &str) -> Decimal {
    s.parse().expect("decimal")
}

async fn admin_member_id(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .expect("admin member")
}

// ---------------------------------------------------------------------------
// 1. End-to-end cross-track seam wiring (zero 501s!)
// ---------------------------------------------------------------------------

#[tokio::test]
async fn savings_config_goal_history_and_dashboard_goals_work_end_to_end() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let member = admin_member_id(&app).await;

    // Create a savings account via T2 POST /api/accounts.
    let create = admin
        .post("/api/accounts")
        .json(&json!({
            "name": "Livret A",
            "type": "LIVRET_A",
            "currency": "EUR",
            "currentBalance": 2500
        }))
        .send()
        .await;
    assert_eq!(create.status, 201, "{}", create.text());
    let account_id = create.json()["id"].as_i64().unwrap();

    // T4 PUT /api/accounts/{id}/savings-config -> T2 AccountService.toResponse seam.
    let put_cfg = admin
        .put(&format!("/api/accounts/{account_id}/savings-config"))
        .json(&json!({
            "product": "LIVRET_A",
            "annualRate": 2.4,
            "rateBasis": "NET",
            "ceiling": 22950
        }))
        .send()
        .await;
    assert_eq!(put_cfg.status, 200, "{}", put_cfg.text());
    let cfg_body = put_cfg.json();
    assert_eq!(cfg_body["id"], account_id);
    assert_eq!(cfg_body["savingsConfig"]["product"], "LIVRET_A");
    assert_eq!(d(&cfg_body["currentBalanceEur"].to_string()), d("2500"));

    // Seed a historical balance snapshot so GET /api/goals/{id}/history has real data.
    sqlx::query(
        "INSERT INTO balance_snapshot (account_id, date, balance, invested_amount, created_at, source) \
         VALUES ($1, '2026-01-01', 2000, 2000, now(), 'DERIVED')",
    )
    .bind(account_id)
    .execute(&app.state.db)
    .await
    .unwrap();

    // Create both a SAVINGS_TARGET goal and a RECURRING_INVESTMENT goal.
    let goal_resp = admin
        .post("/api/goals")
        .json(&json!({
            "name": "Apport",
            "type": "SAVINGS_TARGET",
            "targetAmount": 10000,
            "deadline": "2027-01-31",
            "accountIds": [account_id]
        }))
        .send()
        .await;
    assert_eq!(goal_resp.status, 201, "{}", goal_resp.text());
    let goal_id = goal_resp.json()["id"].as_i64().unwrap();
    assert_eq!(goal_resp.json()["accounts"][0]["id"], account_id);

    let recurring_resp = admin
        .post("/api/goals")
        .json(&json!({
            "name": "DCA",
            "type": "RECURRING_INVESTMENT",
            "monthlyAmount": 300,
            "accountIds": [account_id]
        }))
        .send()
        .await;
    assert_eq!(recurring_resp.status, 201, "{}", recurring_resp.text());

    // T4 GET /api/goals/{id}/history -> T2 HistoryService.buildHistory seam (zero 501!).
    let history = admin
        .get(&format!("/api/goals/{goal_id}/history?months=12"))
        .send()
        .await;
    assert_eq!(history.status, 200, "{}", history.text());
    let points = history.json();
    let arr = points.as_array().unwrap();
    assert!(!arr.is_empty(), "expected history points: {points}");
    assert_eq!(arr[0]["date"], "2026-01-01");

    // T2 GET /api/dashboard -> T4 wealth::goals::dashboard_summaries seam (excludes RECURRING_INVESTMENT).
    let dash = admin.get("/api/dashboard").send().await;
    assert_eq!(dash.status, 200, "{}", dash.text());
    let dash_body = dash.json();
    let goal_summaries = dash_body["goalSummaries"].as_array().unwrap();
    assert_eq!(goal_summaries.len(), 1, "dashboard goals: {dash_body}");
    assert_eq!(goal_summaries[0]["id"], goal_id);
    assert_eq!(goal_summaries[0]["name"], "Apport");
    assert!(member > 0);
}

#[tokio::test]
async fn account_deletion_removes_t5_and_t6_connections_without_deadlocking() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let member = admin_member_id(&app).await;
    let now = app.state.clock.now();

    // 1. BoursoBank sidecar session + single linked account.
    sqlx::query(
        "INSERT INTO sidecar_session (bank, member_id, session_state, is_active, sync_status, \
         last_sync_life_ambiguous, created_at, updated_at) \
         VALUES ('BOURSO', $1, 'enc', true, 'IDLE', 0, $2, $2)",
    )
    .bind(member)
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();
    let mut bourso_acct = NewAccount::new(member, "Bourso", AccountType::Checking);
    bourso_acct.external_account_id = Some("bourso_123".to_owned());
    let bourso_id = accounts::insert(&app.state.db, &bourso_acct, now)
        .await
        .unwrap()
        .id;

    // 2. CORUM sidecar session + SCPI account linked via scpi_position.corum_fund_code (tests member row lock!).
    sqlx::query(
        "INSERT INTO sidecar_session (bank, member_id, session_state, is_active, sync_status, \
         last_sync_life_ambiguous, created_at, updated_at) \
         VALUES ('CORUM', $1, 'enc', true, 'IDLE', 0, $2, $2)",
    )
    .bind(member)
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();
    let mut corum_acct = NewAccount::new(member, "CORUM Origin", AccountType::Scpi);
    corum_acct.is_manual = true;
    let corum_id = accounts::insert(&app.state.db, &corum_acct, now)
        .await
        .unwrap()
        .id;
    sqlx::query(
        "INSERT INTO scpi_position (account_id, member_id, share_count, corum_fund_code, valuation_status, created_at, updated_at) \
         VALUES ($1, $2, 10, 'ORIGIN', 'OK', $3, $3)",
    )
    .bind(corum_id)
    .bind(member)
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();

    // 3. Wise connection + single Wise jar account.
    sqlx::query(
        "INSERT INTO wise_connection (member_id, api_token, status, created_at, updated_at) \
         VALUES ($1, 'enc', 'CONNECTED', $2, $2)",
    )
    .bind(member)
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();
    let mut wise_acct = NewAccount::new(member, "Wise Jar", AccountType::Savings);
    wise_acct.external_account_id = Some("wise_jar_99".to_owned());
    let wise_id = accounts::insert(&app.state.db, &wise_acct, now)
        .await
        .unwrap()
        .id;

    // 4. On-chain wallet + single wallet account.
    let wallet_id: i64 = sqlx::query_scalar(
        "INSERT INTO wallet_address (member_id, chain, address, created_at) \
         VALUES ($1, 'BITCOIN', 'bc1qexampleaddress', $2) RETURNING id",
    )
    .bind(member)
    .bind(now)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    let mut wallet_acct = NewAccount::new(member, "BTC Wallet", AccountType::Crypto);
    wallet_acct.external_account_id = Some(format!("wallet_bitcoin_{wallet_id}"));
    let wallet_acct_id = accounts::insert(&app.state.db, &wallet_acct, now)
        .await
        .unwrap()
        .id;

    // Delete all four accounts via HTTP and verify every connection row is removed atomically.
    for id in [bourso_id, corum_id, wise_id, wallet_acct_id] {
        let resp = admin.delete(&format!("/api/accounts/{id}")).send().await;
        assert_eq!(resp.status, 204, "delete account {id}: {}", resp.text());
    }

    let bourso_left: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM sidecar_session WHERE bank = 'BOURSO' AND member_id = $1",
    )
    .bind(member)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    let corum_left: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM sidecar_session WHERE bank = 'CORUM' AND member_id = $1",
    )
    .bind(member)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    let wise_left: i64 =
        sqlx::query_scalar("SELECT count(*) FROM wise_connection WHERE member_id = $1")
            .bind(member)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    let wallet_left: i64 =
        sqlx::query_scalar("SELECT count(*) FROM wallet_address WHERE member_id = $1")
            .bind(member)
            .fetch_one(&app.state.db)
            .await
            .unwrap();

    assert_eq!(bourso_left, 0);
    assert_eq!(corum_left, 0);
    assert_eq!(wise_left, 0);
    assert_eq!(wallet_left, 0);
}

#[tokio::test]
async fn member_sync_invokes_wired_t6_sidecars_and_skips_degiro_when_scheduled() {
    let app = TestApp::with_admin().await;
    let member = admin_member_id(&app).await;
    let now = app.state.clock.now();

    // Seed an inactive Bourso session with SESSION_EXPIRED so T6's real resync_reporting returns
    // NeedsReauth("SESSION_EXPIRED") instead of NoSidecarSessions's SkippedNotConnected!
    sqlx::query(
        "INSERT INTO sidecar_session (bank, member_id, session_state, is_active, sync_status, \
         last_sync_error, last_sync_life_ambiguous, created_at, updated_at) \
         VALUES ('BOURSO', $1, 'enc', false, 'FAILED', 'SESSION_EXPIRED', 0, $2, $2)",
    )
    .bind(member)
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();

    // Seed a DEGIRO session with REAUTH_REQUIRED so resync_for_user hits degiro::user_sync_reporting.
    sqlx::query(
        "INSERT INTO degiro_session (member_id, session_blob, status, created_at, updated_at) \
         VALUES ($1, 'enc', 'REAUTH_REQUIRED', $2, $2)",
    )
    .bind(member)
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();

    let scheduled = member_sync::resync_scheduled(&app.state, member).await;
    let bourso = scheduled.iter().find(|r| r.source == "bourso").unwrap();
    assert_eq!(bourso.status, SyncStatus::NeedsReauth);
    let degiro = scheduled.iter().find(|r| r.source == "degiro").unwrap();
    assert_eq!(degiro.status, SyncStatus::Skipped);
    assert_eq!(degiro.message, "Unattended DEGIRO sync is disabled");

    let only_degiro = HashSet::from(["degiro".to_owned()]);
    let user_sync = member_sync::resync_for_user(&app.state, member, Some(&only_degiro)).await;
    assert_eq!(user_sync.len(), 1);
    assert_eq!(user_sync[0].source, "degiro");
    assert_eq!(user_sync[0].status, SyncStatus::NeedsReauth);
}

#[tokio::test]
async fn openfigi_and_analysis_seams_wire_market_providers_into_t2_t6_and_t7() {
    let openfigi = MockServer::start().await;
    let yahoo = MockServer::start().await;
    let justetf = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/v3/mapping"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([
            {"data": [{"ticker": "CW8", "exchCode": "FP", "name": "AMUNDI MSCI WORLD"}]}
        ])))
        .mount(&openfigi)
        .await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/CW8.PA"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "chart": {"result": [{"meta": {
                "currency": "EUR",
                "regularMarketPrice": 510.0,
                "instrumentType": "ETF"
            }}]}
        })))
        .mount(&yahoo)
        .await;
    let justetf_html = r#"
        <span data-testid="etf-profile-header_isin-value">LU1681043599</span>
        <td data-testid="tl_etf-basics_value_ter">0.38% p.a.</td>
        <td data-testid="tl_etf-basics_value_distribution-policy">Accumulating</td>
        <td data-testid="tl_etf-basics_value_replication">Synthetic</td>
        <td data-testid="tl_etf-basics_value_domicile-country">Luxembourg</td>
        <span data-testid="tl_etf-holdings_countries_value_name">United States</span>
        <span data-testid="tl_etf-holdings_countries_value_percentage">70.50%</span>
        <span data-testid="tl_etf-holdings_sectors_value_name">Technology</span>
        <span data-testid="tl_etf-holdings_sectors_value_percentage">25.00%</span>
    "#;
    Mock::given(method("GET"))
        .and(path("/en/etf-profile.html"))
        .and(query_param("isin", "LU1681043599"))
        .respond_with(ResponseTemplate::new(200).set_body_string(justetf_html))
        .mount(&justetf)
        .await;

    let app = TestApp::builder()
        .with_admin()
        .env("OPENFIGI_BASE_URL", openfigi.uri())
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .env("JUSTETF_BASE_URL", justetf.uri())
        .spawn()
        .await;
    let member = admin_member_id(&app).await;

    // T7 transfer.isin, T6 connectors.seams.isin, T2 accounts.isin all resolve via OpenFIGI.
    let t7 = app.state.transfer.isin.resolve("LU1681043599").await;
    assert_eq!(t7.ticker, "CW8.PA");
    let t6 = app
        .state
        .connectors
        .seams
        .isin
        .resolve("LU1681043599")
        .await;
    assert_eq!(t6.ticker, "CW8.PA");
    assert!(
        app.state
            .connectors
            .seams
            .symbol_catalog
            .has_quote("CW8.PA")
            .await
    );

    // Seed a PEA holding + ISIN in security_profile, then run weekly security profile refresh!
    let pea = accounts::insert(
        &app.state.db,
        &NewAccount::new(member, "PEA", AccountType::Pea),
        app.state.clock.now(),
    )
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) \
         VALUES ($1, 'CW8.PA', 2, now(), now())",
    )
    .bind(pea.id)
    .execute(&app.state.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO security_profile (ticker, asset_type, isin, status, created_at, updated_at) \
         VALUES ('CW8.PA', 'UNKNOWN', 'LU1681043599', 'NEVER_FETCHED', now(), now())",
    )
    .execute(&app.state.db)
    .await
    .unwrap();

    let refreshed = scheduler::refresh_security_profiles(&app.state)
        .await
        .unwrap();
    assert_eq!(refreshed, 1);

    let (asset_type, status, domicile): (String, String, Option<String>) = sqlx::query_as(
        "SELECT asset_type, status, domicile_country FROM security_profile WHERE ticker = 'CW8.PA'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(asset_type, "ETF");
    assert_eq!(status, "OK");
    assert_eq!(domicile.as_deref(), Some("LU"));

    let slice_count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM security_composition_slice s \
         JOIN security_profile p ON p.id = s.profile_id WHERE p.ticker = 'CW8.PA'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(slice_count, 2);
}

// ---------------------------------------------------------------------------
// 2. Startup runners (sync recovery, ISIN repair -> backfill order, StartupSync)
// ---------------------------------------------------------------------------

#[tokio::test]
async fn startup_recovers_interrupted_syncs_and_runs_isin_repair_before_price_backfill() {
    let openfigi = MockServer::start().await;
    let yahoo = MockServer::start().await;
    let day = |d: i64| 1_768_478_400 - d * 86_400;

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
            "chart": {"result": [{
                "meta": {"currency": "EUR", "regularMarketPrice": 125.0},
                "timestamp": [day(2), day(1)],
                "indicators": {"quote": [{"close": [124.0, 125.5]}]}
            }]}
        })))
        .mount(&yahoo)
        .await;

    let app = TestApp::builder()
        .with_admin()
        .env("OPENFIGI_BASE_URL", openfigi.uri())
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .spawn()
        .await;
    let member = admin_member_id(&app).await;
    let now = app.state.clock.now();

    // Seed stuck QUEUED/RUNNING sidecar, LBP, and Natixis sessions.
    sqlx::query(
        "INSERT INTO sidecar_session (bank, member_id, session_state, is_active, sync_status, \
         last_sync_started_at, last_sync_life_ambiguous, created_at, updated_at) \
         VALUES ('BOURSO', $1, 'enc', true, 'RUNNING', $2, 0, $2, $2), \
                ('NATIXIS', $1, 'enc', true, 'QUEUED', $2, 0, $2, $2)",
    )
    .bind(member)
    .bind(now - chrono::Duration::minutes(1))
    .execute(&app.state.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO lbp_session (member_id, session_state, is_active, sync_status, created_at, updated_at) \
         VALUES ($1, 'enc', true, 'QUEUED', $2, $2)",
    )
    .bind(member)
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();

    // Seed a manual PEA transaction carrying a raw ISIN: on_startup must repair ISIN -> MWRD.PA
    // BEFORE running price_backfill so MWRD.PA gets historical price_snapshot rows in one boot!
    let pea = accounts::insert(
        &app.state.db,
        &NewAccount::new(member, "PEA", AccountType::Pea),
        now,
    )
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO transaction (account_id, date, amount, tx_type, is_manual, ticker, quantity, price_per_unit, created_at) \
         VALUES ($1, '2026-01-10', -1000, 'BUY', true, 'IE000BI8OT95', 8, 125, $2)",
    )
    .bind(pea.id)
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();

    picsou::jobs::on_startup(&app.state).await.unwrap();

    // All three stuck sessions recovered to FAILED / INTERNAL_ERROR.
    let stuck_sidecars: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM sidecar_session WHERE sync_status IN ('QUEUED', 'RUNNING')",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    let lbp_status: (String, Option<String>) =
        sqlx::query_as("SELECT sync_status, last_sync_error FROM lbp_session WHERE member_id = $1")
            .bind(member)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(stuck_sidecars, 0);
    assert_eq!(
        lbp_status,
        ("FAILED".to_owned(), Some("INTERNAL_ERROR".to_owned()))
    );

    // ISIN repaired AND price_backfill immediately populated MWRD.PA snapshots!
    let repaired_ticker: String =
        sqlx::query_scalar("SELECT ticker FROM transaction WHERE account_id = $1")
            .bind(pea.id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(repaired_ticker, "MWRD.PA");

    let snap_count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM price_snapshot WHERE ticker = 'MWRD.PA'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(snap_count, 2);
}

#[tokio::test]
async fn startup_sync_respects_flag_and_records_run_in_catch_up_scheduler() {
    // Disabled by default in TestApp::builder().
    let disabled_app = TestApp::with_admin().await;
    assert!(!startup::run_startup_sync(&disabled_app.state).await);
    let stored: Option<String> =
        sqlx::query_scalar("SELECT value FROM app_setting WHERE setting_key = $1")
            .bind(format!("{KEY_PREFIX}{DAILY_BANK_SYNC}"))
            .fetch_optional(&disabled_app.state.db)
            .await
            .unwrap();
    assert!(stored.is_none());

    // Enabled -> runs through CatchUpScheduler::run_now and persists scheduler.last-run.daily-bank-sync.
    let enabled_app = TestApp::builder()
        .with_admin()
        .env("APP_STARTUP_SYNC_ENABLED", "true")
        .spawn()
        .await;
    assert!(startup::run_startup_sync(&enabled_app.state).await);
    let stored: String = sqlx::query_scalar("SELECT value FROM app_setting WHERE setting_key = $1")
        .bind(format!("{KEY_PREFIX}{DAILY_BANK_SYNC}"))
        .fetch_one(&enabled_app.state.db)
        .await
        .unwrap();
    assert_eq!(
        DateTime::parse_from_rfc3339(&stored)
            .unwrap()
            .with_timezone(&Utc),
        enabled_app.clock.now()
    );
}

// ---------------------------------------------------------------------------
// 3. SchedulerService jobs, CatchUpScheduler persistence, cleanups & shutdown
// ---------------------------------------------------------------------------

#[tokio::test]
async fn daily_snapshots_skips_unpriced_holdings_and_refreshes_synced_insurer_contract() {
    let app = TestApp::with_admin().await;
    let member = admin_member_id(&app).await;
    let now = app.state.clock.now();
    let today = app.state.clock.today();

    // 1. Plain checking account -> gets a snapshot.
    let mut checking = NewAccount::new(member, "Checking", AccountType::Checking);
    checking.current_balance = d("1500");
    let checking_id = accounts::insert(&app.state.db, &checking, now)
        .await
        .unwrap()
        .id;

    // 2. PEA account with an unpriced holding -> skipped (no zero snapshot!).
    let pea_id = accounts::insert(
        &app.state.db,
        &NewAccount::new(member, "PEA", AccountType::Pea),
        now,
    )
    .await
    .unwrap()
    .id;
    sqlx::query(
        "INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) \
         VALUES ($1, 'UNKNOWN.ZZ', 5, $2, $2)",
    )
    .bind(pea_id)
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();

    // 3. Synced Caisse d'Epargne ASSURANCE_VIE with an existing same-day snapshot -> refreshed!
    let mut av_synced = NewAccount::new(member, "CE AV", AccountType::AssuranceVie);
    av_synced.provider = Some("Caisse d'Epargne".to_owned());
    av_synced.is_manual = false;
    av_synced.current_balance = d("1200");
    let av_synced_id = accounts::insert(&app.state.db, &av_synced, now)
        .await
        .unwrap()
        .id;
    sqlx::query(
        "INSERT INTO balance_snapshot (account_id, date, balance, invested_amount, created_at, source) \
         VALUES ($1, $2, 900, 900, $3, 'DERIVED')",
    )
    .bind(av_synced_id)
    .bind(today)
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();

    // 4. Manual Caisse d'Epargne ASSURANCE_VIE with an existing same-day snapshot -> NOT overwritten!
    let mut av_manual = NewAccount::new(member, "CE AV Manual", AccountType::AssuranceVie);
    av_manual.provider = Some("Caisse d'Epargne".to_owned());
    av_manual.is_manual = true;
    av_manual.current_balance = d("1200");
    let av_manual_id = accounts::insert(&app.state.db, &av_manual, now)
        .await
        .unwrap()
        .id;
    sqlx::query(
        "INSERT INTO balance_snapshot (account_id, date, balance, invested_amount, created_at, source) \
         VALUES ($1, $2, 700, 700, $3, 'DERIVED')",
    )
    .bind(av_manual_id)
    .bind(today)
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();

    let written = scheduler::daily_snapshots(&app.state).await.unwrap();
    assert_eq!(written, 2, "checking inserted + synced AV updated");

    let checking_bal: Decimal = sqlx::query_scalar(
        "SELECT balance FROM balance_snapshot WHERE account_id = $1 AND date = $2",
    )
    .bind(checking_id)
    .bind(today)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(checking_bal, d("1500"));

    let pea_snaps: i64 =
        sqlx::query_scalar("SELECT count(*) FROM balance_snapshot WHERE account_id = $1")
            .bind(pea_id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(pea_snaps, 0);

    let av_synced_bal: Decimal = sqlx::query_scalar(
        "SELECT balance FROM balance_snapshot WHERE account_id = $1 AND date = $2",
    )
    .bind(av_synced_id)
    .bind(today)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(av_synced_bal, d("1200"));

    let av_manual_bal: Decimal = sqlx::query_scalar(
        "SELECT balance FROM balance_snapshot WHERE account_id = $1 AND date = $2",
    )
    .bind(av_manual_id)
    .bind(today)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(av_manual_bal, d("700"));
}

#[tokio::test]
async fn refresh_prices_separates_crypto_from_equities_and_records_snapshots() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;

    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/AAPL"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "chart": {"result": [{"meta": {"currency": "EUR", "regularMarketPrice": 200.0}}]}
        })))
        .expect(1)
        .mount(&yahoo)
        .await;
    // Crypto ticker BTC must NEVER be sent to Yahoo.
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/BTC"))
        .respond_with(ResponseTemplate::new(200))
        .expect(0)
        .mount(&yahoo)
        .await;
    Mock::given(method("GET"))
        .and(path("/simple/price"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "bitcoin": {"eur": 80000.0}
        })))
        .mount(&coingecko)
        .await;

    let app = TestApp::builder()
        .with_admin()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .env("COINGECKO_BASE_URL", coingecko.uri())
        .spawn()
        .await;
    let member = admin_member_id(&app).await;
    let now = app.state.clock.now();

    let crypto = accounts::insert(
        &app.state.db,
        &NewAccount::new(member, "Crypto", AccountType::Crypto),
        now,
    )
    .await
    .unwrap();
    let pea = accounts::insert(
        &app.state.db,
        &NewAccount::new(member, "PEA", AccountType::Pea),
        now,
    )
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) VALUES \
         ($1, 'BTC', 0.5, $3, $3), ($2, 'AAPL', 2, $3, $3)",
    )
    .bind(crypto.id)
    .bind(pea.id)
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();

    scheduler::refresh_prices(&app.state).await.unwrap();

    let tickers: Vec<String> =
        sqlx::query_scalar("SELECT ticker FROM price_snapshot ORDER BY ticker")
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(tickers, vec!["AAPL", "BTC"]);
}

#[tokio::test]
async fn refresh_dividend_history_skips_crypto_without_isin_and_refreshes_stocks() {
    let yahoo = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/TTE.PA"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "chart": {"result": [{
                "meta": {"currency": "EUR"},
                "events": {"dividends": {
                    "1735689600": {"amount": 0.79, "date": 1735689600}
                }}
            }]}
        })))
        .expect(1)
        .mount(&yahoo)
        .await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/BTC"))
        .respond_with(ResponseTemplate::new(200))
        .expect(0)
        .mount(&yahoo)
        .await;

    let app = TestApp::builder()
        .with_admin()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .spawn()
        .await;
    let member = admin_member_id(&app).await;
    let now = app.state.clock.now();

    let pea = accounts::insert(
        &app.state.db,
        &NewAccount::new(member, "PEA", AccountType::Pea),
        now,
    )
    .await
    .unwrap();
    let crypto = accounts::insert(
        &app.state.db,
        &NewAccount::new(member, "Crypto", AccountType::Crypto),
        now,
    )
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) VALUES \
         ($1, 'TTE.PA', 10, $3, $3), ($2, 'BTC', 1, $3, $3)",
    )
    .bind(pea.id)
    .bind(crypto.id)
    .bind(now)
    .execute(&app.state.db)
    .await
    .unwrap();

    let refreshed = scheduler::refresh_dividend_history_pass(&app.state)
        .await
        .unwrap();
    assert_eq!(refreshed, 1);

    let sources: Vec<(String, String)> =
        sqlx::query_as("SELECT ticker, status FROM dividend_source ORDER BY ticker")
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(sources, vec![("TTE.PA".to_owned(), "OK".to_owned())]);
}

#[tokio::test]
async fn catch_up_scheduler_persists_last_runs_in_postgres_and_runs_missed_snapshots() {
    let app = TestApp::with_admin().await;
    let member = admin_member_id(&app).await;
    let now = app.state.clock.now();

    let mut acct = NewAccount::new(member, "Cash", AccountType::Checking);
    acct.current_balance = d("420");
    let acct_id = accounts::insert(&app.state.db, &acct, now)
        .await
        .unwrap()
        .id;

    // Seed last-run of daily-snapshots to yesterday 08:05 UTC (missed today's 08:05 Europe/Paris!).
    // Seed the other 4 jobs to `now` so only daily-snapshots is due.
    let yesterday =
        (now - chrono::Duration::hours(26)).to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true);
    let current = now.to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true);
    for (job, value) in [
        (DAILY_BANK_SYNC, &current),
        (DAILY_SNAPSHOTS, &yesterday),
        (MONTHLY_PROPERTY_VALUATION, &current),
        (WEEKLY_SECURITY_PROFILES, &current),
        (WEEKLY_DIVIDEND_HISTORY, &current),
    ] {
        sqlx::query(
            "INSERT INTO app_setting (setting_key, value) VALUES ($1, $2) \
             ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value",
        )
        .bind(format!("{KEY_PREFIX}{job}"))
        .bind(value)
        .execute(&app.state.db)
        .await
        .unwrap();
    }

    app.state.jobs.catch_up.tick(Some(&app.state)).await;

    let snap: Decimal = sqlx::query_scalar(
        "SELECT balance FROM balance_snapshot WHERE account_id = $1 AND date = $2",
    )
    .bind(acct_id)
    .bind(app.state.clock.today())
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(snap, d("420"));

    // Simulate a process restart with a fresh CatchUpScheduler reading from the same DB:
    // today's slot was recorded, so a second tick does not re-run.
    let restarted = CatchUpScheduler::new(app.state.db.clone(), app.state.clock.clone());
    sqlx::query("UPDATE balance_snapshot SET balance = 999 WHERE account_id = $1")
        .bind(acct_id)
        .execute(&app.state.db)
        .await
        .unwrap();
    restarted.tick(Some(&app.state)).await;
    let unchanged: Decimal = sqlx::query_scalar(
        "SELECT balance FROM balance_snapshot WHERE account_id = $1 AND date = $2",
    )
    .bind(acct_id)
    .bind(app.state.clock.today())
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(unchanged, d("999"));
}

#[tokio::test]
async fn cleanup_jobs_evict_stale_natixis_syncs_and_schedulers_shut_down_cleanly() {
    let app = TestApp::with_admin().await;
    let member = admin_member_id(&app).await;
    let (_, other_member) = app.create_member("other", "password-1234").await;
    let now = app.state.clock.now();

    // Seed one stale Natixis sync (15 min old) and one fresh running sync (2 min old).
    sqlx::query(
        "INSERT INTO sidecar_session (bank, member_id, session_state, is_active, sync_status, \
         last_sync_started_at, last_sync_life_ambiguous, created_at, updated_at) VALUES \
         ('NATIXIS', $1, 'enc', true, 'RUNNING', $3, 0, $3, $3), \
         ('NATIXIS', $2, 'enc', true, 'RUNNING', $4, 0, $4, $4)",
    )
    .bind(member)
    .bind(other_member)
    .bind(now - chrono::Duration::minutes(15))
    .bind(now - chrono::Duration::minutes(2))
    .execute(&app.state.db)
    .await
    .unwrap();

    // Run 60s cleanups (exercises transfer, finary, cic, lbp, bnp, cmes, natixis eviction hooks).
    scheduler::run_60s_cleanups(&app.state);

    // Run 300s stale Natixis sweep: only the 15-minute-old row is recovered.
    let recovered = scheduler::run_300s_stale_recovery(&app.state)
        .await
        .unwrap();
    assert_eq!(recovered, 1);

    let stale_status: String = sqlx::query_scalar(
        "SELECT sync_status FROM sidecar_session WHERE bank = 'NATIXIS' AND member_id = $1",
    )
    .bind(member)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    let fresh_status: String = sqlx::query_scalar(
        "SELECT sync_status FROM sidecar_session WHERE bank = 'NATIXIS' AND member_id = $1",
    )
    .bind(other_member)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(stale_status, "FAILED");
    assert_eq!(fresh_status, "RUNNING");

    // Verify spawn_schedulers cancels cleanly and promptly when the watch signal fires.
    let (stop_tx, stop_rx) = tokio::sync::watch::channel(false);
    let handle = spawn_schedulers(app.state.clone(), stop_rx);
    tokio::task::yield_now().await;
    stop_tx.send(true).unwrap();
    let started = std::time::Instant::now();
    handle.shutdown(Duration::from_secs(2)).await;
    assert!(
        started.elapsed() < Duration::from_secs(1),
        "schedulers must stop promptly on cancellation"
    );

    // Also verify monthly_property_valuation runs cleanly with valuation enabled and no properties.
    let revalued = scheduler::monthly_property_valuation(&app.state)
        .await
        .unwrap();
    assert_eq!(revalued, 0);
}

#[tokio::test]
async fn real_estate_valuation_refresh_and_connector_savings_auto_config_seams_work() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let member = admin_member_id(&app).await;
    let now = app.state.clock.now();

    // 1. T2 POST /api/accounts/{id}/valuation/refresh -> T4 PropertyValuationService.estimate.
    let create = admin
        .post("/api/accounts")
        .json(&json!({
            "name": "Appartement Strasbourg",
            "type": "REAL_ESTATE",
            "currency": "EUR",
            "currentBalance": 250000
        }))
        .send()
        .await;
    assert_eq!(create.status, 201, "{}", create.text());
    let prop_id = create.json()["id"].as_i64().unwrap();

    // Save real estate metadata in Alsace (67 = unsupported department in DV3F -> T4 returns UNSUPPORTED_AREA,
    // proving T4's real PropertyValuationService ran rather than T2's ValuationDisabled default!).
    let meta = admin
        .put(&format!("/api/accounts/{prop_id}/real-estate"))
        .json(&json!({
            "purchasePrice": 250000,
            "propertyType": "APARTMENT",
            "postalCode": "67000",
            "city": "Strasbourg",
            "country": "FR",
            "inseeCode": "67482",
            "surfaceArea": 70,
            "rooms": 3
        }))
        .send()
        .await;
    assert_eq!(meta.status, 200, "{}", meta.text());

    sqlx::query("UPDATE real_estate_metadata SET insee_code = '67482' WHERE account_id = $1")
        .bind(prop_id)
        .execute(&app.state.db)
        .await
        .unwrap();

    let val = admin
        .post(&format!("/api/accounts/{prop_id}/valuation/refresh"))
        .send()
        .await;
    assert_eq!(val.status, 200, "{}", val.text());
    assert_eq!(val.json()["status"], "UNSUPPORTED_AREA");

    // 2. T6 connectors.seams.savings -> T4 wealth::savings::model regulated defaults.
    let livret = accounts::insert(
        &app.state.db,
        &NewAccount::new(member, "Livret A", AccountType::LivretA),
        now,
    )
    .await
    .unwrap();
    let mut conn = app.state.db.acquire().await.unwrap();
    let inserted = app
        .state
        .connectors
        .seams
        .savings
        .configure_if_missing(&mut conn, livret.id, AccountType::LivretA)
        .await
        .unwrap();
    assert!(inserted);
    let second = app
        .state
        .connectors
        .seams
        .savings
        .configure_if_missing(&mut conn, livret.id, AccountType::LivretA)
        .await
        .unwrap();
    assert!(!second, "existing config must not be overwritten");
}
