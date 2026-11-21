//! SimpleFIN Bridge sync over HTTP: SimplefinControllerTest, SimplefinSyncServiceTest and
//! SimplefinClientTest, with the bridge stubbed by wiremock behind the production host checks.

use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use chrono::{Days, NaiveDate};
use picsou::ledger::sidecar::SyncStatus;
use picsou::testing::{ADMIN_USERNAME, Client, TestApp};
use reqwest::StatusCode;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{header, method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

const BRIDGE: &str = "beta-bridge.simplefin.org";
const USERNAME: &str = "usr98765";
const PASSWORD: &str = "s3cr3tPassw0rd";
const MASK: &str = "\u{2022}\u{2022}\u{2022}\u{2022}";

fn access_url() -> String {
    format!("https://{USERNAME}:{PASSWORD}@{BRIDGE}/simplefin")
}

fn token() -> String {
    STANDARD.encode(format!("https://{BRIDGE}/simplefin/claim/abc"))
}

/// 2026-01-10 at noon UTC, five days before the TestApp epoch.
const POSTED: i64 = 1_768_046_400;

struct Fixture {
    app: TestApp,
    bridge: MockServer,
    admin: Client,
    member_id: i64,
}

impl Fixture {
    async fn new() -> Self {
        let bridge = MockServer::start().await;
        let app = TestApp::with_admin().await;
        app.state.sync.simplefin.route_bridge_to(&bridge.uri());
        let admin = app.login_admin().await;
        let member_id = picsou::auth::users::find_by_username(&app.state.db, ADMIN_USERNAME)
            .await
            .unwrap()
            .unwrap()
            .member_id;
        Self {
            app,
            bridge,
            admin,
            member_id,
        }
    }

    async fn claim_answers(&self, status: u16, body: &str) {
        Mock::given(method("POST"))
            .and(path("/simplefin/claim/abc"))
            .respond_with(ResponseTemplate::new(status).set_body_string(body))
            .mount(&self.bridge)
            .await;
    }

    async fn connect(&self) {
        self.claim_answers(200, &format!("{}\n", access_url()))
            .await;
        let response = self.post_connect(&self.admin, &token()).await;
        assert_eq!(
            response.status,
            StatusCode::NO_CONTENT,
            "{}",
            response.text()
        );
    }

    async fn post_connect(&self, client: &Client, token: &str) -> picsou::testing::Response {
        client
            .post("/api/simplefin/connect")
            .json(&json!({ "token": token }))
            .send()
            .await
    }

    async fn accounts_answer(&self, status: u16, body: Value) {
        self.bridge.reset().await;
        Mock::given(method("GET"))
            .and(path("/simplefin/accounts"))
            .respond_with(ResponseTemplate::new(status).set_body_json(body))
            .mount(&self.bridge)
            .await;
    }

    async fn sync(&self) -> picsou::testing::Response {
        // Connect and sync share six calls a minute; every test step gets a fresh budget.
        self.app.clock.advance(Duration::from_secs(61));
        self.admin.post("/api/simplefin/sync").send().await
    }

    async fn status(&self) -> Value {
        let response = self.admin.get("/api/simplefin/status").send().await;
        assert_eq!(response.status, StatusCode::OK);
        response.json()
    }

    async fn scalar_i64(&self, sql: &str) -> i64 {
        sqlx::query_scalar(sql)
            .fetch_one(&self.app.state.db)
            .await
            .unwrap()
    }
}

fn account(id: &str, currency: &str, balance: &str) -> Value {
    json!({
        "id": id,
        "conn_id": "CONN1",
        "name": "Checking",
        "currency": currency,
        "balance": balance,
        "transactions": [
            { "id": "TX1", "posted": POSTED, "amount": "-12.34", "description": "Coffee" },
            { "id": "TX2", "posted": POSTED, "amount": "-5.00", "description": "Pending", "pending": true },
        ],
    })
}

fn account_set(accounts: Vec<Value>) -> Value {
    json!({
        "errors": [],
        "connections": [
            { "conn_id": "CONN1", "name": "Jane at Chase", "org_name": "Chase", "org_url": "https://www.chase.com/" }
        ],
        "accounts": accounts,
    })
}

/// ticker, quantity, average_buy_in, current_price, provider_value_eur, quote_currency
type HoldingRow = (
    String,
    Decimal,
    Option<Decimal>,
    Option<Decimal>,
    Option<Decimal>,
    Option<String>,
);

fn dec(value: &Value) -> Decimal {
    value.to_string().parse().unwrap()
}

// ── status, validation, member scoping ────────────────────────────────────────

#[tokio::test]
async fn status_without_a_connection_omits_the_connection_fields() {
    let f = Fixture::new().await;
    assert_eq!(f.status().await, json!({ "connected": false }));
}

#[tokio::test]
async fn connect_validates_the_token_before_any_request() {
    let f = Fixture::new().await;
    let blank = f.post_connect(&f.admin, "   ").await;
    assert_eq!(blank.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert!(blank.json()["errors"]["token"].is_string());

    let huge = "A".repeat(4097);
    let long = f.post_connect(&f.admin, &huge).await;
    assert_eq!(long.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert!(long.json()["errors"]["token"].is_string());
    assert!(!long.text().contains(&huge));

    let no_body = f
        .admin
        .post("/api/simplefin/connect")
        .header("Content-Type", "application/json")
        .send()
        .await;
    assert_eq!(no_body.status, StatusCode::BAD_REQUEST);
    assert!(f.bridge.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn a_foreign_host_is_refused_before_any_request_and_never_echoed() {
    let f = Fixture::new().await;
    let secret = "SECRET-CLAIM-VALUE-12345";
    for url in [
        format!("https://evil.example/claim/{secret}"),
        format!("https://{BRIDGE}:8443/claim/{secret}"),
        format!("https://u:p@{BRIDGE}/claim/{secret}"),
        format!("http://{BRIDGE}/claim/{secret}"),
    ] {
        let response = f.post_connect(&f.admin, &STANDARD.encode(&url)).await;
        assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY, "{url}");
        assert!(!response.text().contains(secret));
    }
    let refused = f
        .post_connect(&f.admin, &STANDARD.encode("https://10.0.0.5/claim/x"))
        .await
        .json();
    assert_eq!(
        refused["detail"],
        format!("Picsou only connects to SimpleFIN Bridge ({BRIDGE}).")
    );
    assert!(refused.get("code").is_none());
    assert!(f.bridge.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn connect_claims_once_and_stores_the_access_url_encrypted() {
    let f = Fixture::new().await;
    f.connect().await;

    let requests = f.bridge.received_requests().await.unwrap();
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].method.as_str(), "POST");
    assert!(!requests[0].headers.contains_key("authorization"));

    let stored: String = sqlx::query_scalar("SELECT access_url FROM simplefin_connection")
        .fetch_one(&f.app.state.db)
        .await
        .unwrap();
    assert!(!stored.contains(PASSWORD) && !stored.contains(BRIDGE));
    assert_eq!(f.app.state.crypto.decrypt(&stored).unwrap(), access_url());

    let status = f.status().await;
    assert_eq!(status["connected"], true);
    assert_eq!(status["status"], "CONNECTED");
    assert_eq!(status["maskedToken"], format!("{MASK}8765"));
    assert!(status.get("lastSyncedAt").is_none());
    assert!(!status.to_string().contains(PASSWORD));
}

#[tokio::test]
async fn claim_answers_map_to_their_problems() {
    for (status, body, detail) in [
        (403, String::new(), "already been used"),
        (302, String::new(), "redirected"),
        (500, String::new(), "(HTTP 500)"),
        (200, "a".repeat(8193), "too large"),
        (
            200,
            format!("https://{USERNAME}:{PASSWORD}@10.0.0.5:8443/simplefin"),
            BRIDGE,
        ),
        (
            200,
            format!("\"https://{USERNAME}@{BRIDGE}/simplefin\""),
            "missing its credentials",
        ),
    ] {
        let f = Fixture::new().await;
        f.claim_answers(status, &body).await;
        let response = f.post_connect(&f.admin, &token()).await;
        assert_eq!(
            response.status,
            StatusCode::UNPROCESSABLE_ENTITY,
            "{status}"
        );
        let problem = response.json();
        assert!(
            problem["detail"].as_str().unwrap().contains(detail),
            "{status}: {problem}"
        );
        assert!(!response.text().contains(PASSWORD));
        assert_eq!(f.bridge.received_requests().await.unwrap().len(), 1);
        assert_eq!(f.status().await, json!({ "connected": false }));
    }
}

#[tokio::test]
async fn a_failed_claim_leaves_the_stored_connection_untouched() {
    let f = Fixture::new().await;
    f.connect().await;
    let before: String = sqlx::query_scalar("SELECT access_url FROM simplefin_connection")
        .fetch_one(&f.app.state.db)
        .await
        .unwrap();
    f.bridge.reset().await;
    f.claim_answers(403, "").await;
    f.app.clock.advance(Duration::from_secs(61));
    let response = f.post_connect(&f.admin, &token()).await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    let after: String = sqlx::query_scalar("SELECT access_url FROM simplefin_connection")
        .fetch_one(&f.app.state.db)
        .await
        .unwrap();
    assert_eq!(before, after);
}

#[tokio::test]
async fn each_member_sees_only_their_own_connection() {
    let f = Fixture::new().await;
    f.connect().await;
    f.app.create_member("bob", "bob-password-123").await;
    let bob = f.app.login("bob", "bob-password-123").await;

    let status = bob.get("/api/simplefin/status").send().await.json();
    assert_eq!(status, json!({ "connected": false }));
    let sync = bob.post("/api/simplefin/sync").send().await;
    assert_eq!(sync.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        sync.json()["detail"],
        "No SimpleFIN connection. Connect with a setup token first."
    );
    let delete = bob.delete("/api/simplefin/connection").send().await;
    assert_eq!(delete.status, StatusCode::NO_CONTENT);
    assert_eq!(f.status().await["connected"], true);
}

#[tokio::test]
async fn disconnect_removes_the_connection_and_keeps_the_accounts() {
    let f = Fixture::new().await;
    f.connect().await;
    f.accounts_answer(200, account_set(vec![account("A1", "EUR", "100.00")]))
        .await;
    assert_eq!(f.sync().await.status, StatusCode::OK);

    for _ in 0..2 {
        let response = f.admin.delete("/api/simplefin/connection").send().await;
        assert_eq!(response.status, StatusCode::NO_CONTENT);
    }
    assert_eq!(f.status().await, json!({ "connected": false }));
    assert_eq!(f.scalar_i64("SELECT count(*) FROM account").await, 1);
}

#[tokio::test]
async fn connect_and_sync_share_one_per_ip_budget() {
    let f = Fixture::new().await;
    for _ in 0..3 {
        assert_eq!(
            f.post_connect(&f.admin, "   ").await.status,
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation runs before the limiter"
        );
    }
    for _ in 0..3 {
        f.post_connect(&f.admin, &STANDARD.encode("https://evil.example/x"))
            .await;
        f.admin.post("/api/simplefin/sync").send().await;
    }
    let rejected = f.admin.post("/api/simplefin/sync").send().await;
    assert_eq!(rejected.status, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        rejected.json()["detail"],
        "Too many SimpleFIN requests. Please wait a moment before trying again."
    );
    let rejected = f
        .post_connect(&f.admin, &STANDARD.encode("https://evil.example/x"))
        .await;
    assert_eq!(rejected.status, StatusCode::TOO_MANY_REQUESTS);
    // Status and disconnect never reach the bridge, so they stay available.
    assert_eq!(f.status().await["connected"], false);
    let delete = f.admin.delete("/api/simplefin/connection").send().await;
    assert_eq!(delete.status, StatusCode::NO_CONTENT);

    f.app.clock.advance(Duration::from_secs(61));
    assert_eq!(
        f.admin.post("/api/simplefin/sync").send().await.status,
        StatusCode::UNPROCESSABLE_ENTITY
    );
}

// ── sync ─────────────────────────────────────────────────────────────────────

#[tokio::test]
async fn sync_creates_a_checking_account_with_its_bank_balance_and_transactions() {
    let f = Fixture::new().await;
    f.connect().await;
    // 89 days back on the UTC date, as midnight UTC: the bridge refuses a 90-day span.
    let start = NaiveDate::from_ymd_opt(2026, 1, 15)
        .unwrap()
        .checked_sub_days(Days::new(89))
        .unwrap();
    let epoch = start.and_hms_opt(0, 0, 0).unwrap().and_utc().timestamp();
    let basic = format!(
        "Basic {}",
        STANDARD.encode(format!("{USERNAME}:{PASSWORD}"))
    );
    Mock::given(method("GET"))
        .and(path("/simplefin/accounts"))
        .and(query_param("version", "2"))
        .and(query_param("start-date", epoch.to_string()))
        .and(header("authorization", basic.as_str()))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(account_set(vec![account("A1", "eur", "1234.50")])),
        )
        .mount(&f.bridge)
        .await;

    let response = f.sync().await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    let body = response.json();
    let synced = &body[0];
    assert_eq!(body.as_array().unwrap().len(), 1);
    assert_eq!(synced["name"], "Chase — Checking");
    assert_eq!(synced["type"], "CHECKING");
    assert_eq!(synced["provider"], "SimpleFIN");
    assert_eq!(synced["currency"], "EUR");
    assert_eq!(synced["color"], "#0d9488");
    assert_eq!(synced["isManual"], false);
    assert_eq!(synced["currentBalance"].to_string(), "1234.50");
    assert_eq!(synced["currentBalanceEur"].to_string(), "1234.50");
    assert_eq!(
        synced["institution"],
        json!({ "name": "Chase", "hasLogo": true })
    );
    assert!(synced.get("cashBalance").is_none());

    let id = synced["id"].as_i64().unwrap();
    let external: String =
        sqlx::query_scalar("SELECT external_account_id FROM account WHERE id = $1")
            .bind(id)
            .fetch_one(&f.app.state.db)
            .await
            .unwrap();
    assert_eq!(external, "sfin_CONN1_A1");
    let rows: Vec<(String, Decimal, String)> = sqlx::query_as(
        "SELECT description, amount, external_transaction_id FROM transaction WHERE account_id = $1",
    )
    .bind(id)
    .fetch_all(&f.app.state.db)
    .await
    .unwrap();
    assert_eq!(rows.len(), 1, "the pending row is dropped");
    assert_eq!(rows[0].0, "Coffee");
    assert_eq!(rows[0].1, "-12.34".parse::<Decimal>().unwrap());
    assert_eq!(rows[0].2, "TX1");
    let snapshot: Decimal =
        sqlx::query_scalar("SELECT balance FROM balance_snapshot WHERE account_id = $1")
            .bind(id)
            .fetch_one(&f.app.state.db)
            .await
            .unwrap();
    assert_eq!(snapshot, "1234.50".parse::<Decimal>().unwrap());

    let status = f.status().await;
    assert_eq!(status["status"], "CONNECTED");
    assert!(status["lastSyncedAt"].as_str().unwrap().ends_with('Z'));
}

#[tokio::test]
async fn a_resync_keeps_the_members_type_and_inserts_nothing_new() {
    let f = Fixture::new().await;
    f.connect().await;
    f.accounts_answer(200, account_set(vec![account("A1", "EUR", "100.00")]))
        .await;
    let id = f.sync().await.json()[0]["id"].as_i64().unwrap();
    sqlx::query("UPDATE account SET type = 'SAVINGS' WHERE id = $1")
        .bind(id)
        .execute(&f.app.state.db)
        .await
        .unwrap();

    f.accounts_answer(200, account_set(vec![account("A1", "EUR", "250.75")]))
        .await;
    let body = f.sync().await.json();
    assert_eq!(body[0]["id"], id);
    assert_eq!(body[0]["type"], "SAVINGS");
    assert_eq!(dec(&body[0]["currentBalance"]), "250.75".parse().unwrap());
    assert_eq!(
        f.scalar_i64("SELECT count(*) FROM transaction").await,
        1,
        "the same posted row is stored once"
    );
    assert_eq!(f.scalar_i64("SELECT count(*) FROM account").await, 1);
}

#[tokio::test]
async fn unusable_accounts_are_skipped_and_the_others_still_import() {
    let f = Fixture::new().await;
    f.connect().await;
    let mut no_currency = account("A3", "", "10");
    no_currency.as_object_mut().unwrap().remove("currency");
    f.accounts_answer(
        200,
        account_set(vec![
            account("A1", "XAU", "10"),
            account("A2", "USD", "1000000000000"),
            no_currency,
            account("A4", "USD", "999999999999.99"),
        ]),
    )
    .await;
    let body = f.sync().await.json();
    assert_eq!(body.as_array().unwrap().len(), 1);
    assert_eq!(body[0]["currency"], "USD");
    // No USD rate: the balance comes back unconverted, as Java's toEur does.
    assert_eq!(body[0]["currentBalanceEur"].to_string(), "999999999999.99");
}

#[tokio::test]
async fn a_soft_deleted_account_is_not_resurrected() {
    let f = Fixture::new().await;
    f.connect().await;
    f.accounts_answer(200, account_set(vec![account("A1", "EUR", "100")]))
        .await;
    f.sync().await;
    sqlx::query("UPDATE account SET deleted_at = now()")
        .execute(&f.app.state.db)
        .await
        .unwrap();
    let body = f.sync().await.json();
    assert_eq!(body, json!([]));
    assert_eq!(f.scalar_i64("SELECT count(*) FROM account").await, 1);
}

fn brokerage(holdings: Value) -> Value {
    json!({
        "id": "HSA", "conn_id": "CONN1", "name": "Brokerage", "currency": "EUR",
        "balance": "5000.00", "holdings": holdings,
    })
}

#[tokio::test]
async fn positions_make_a_brokerage_account_and_a_sweep_becomes_cash() {
    let f = Fixture::new().await;
    f.connect().await;
    f.accounts_answer(
        200,
        account_set(vec![brokerage(json!([
            { "symbol": "vti", "description": "Total Market", "shares": "10", "market_value": "3000",
              "cost_basis": "0.00", "purchase_price": "250" },
            { "symbol": "SPAXX", "description": "Money market", "shares": "500", "market_value": "500" },
        ]))]),
    )
    .await;
    let body = f.sync().await.json();
    let synced = &body[0];
    assert_eq!(synced["type"], "BROKERAGE");
    // The balance beyond the positions, sweep included: 5000 - 3000.
    assert_eq!(synced["cashBalance"].to_string(), "2000.00000000");

    let holdings: Vec<HoldingRow> =
        sqlx::query_as(
            "SELECT ticker, quantity, average_buy_in, current_price, provider_value_eur, quote_currency \
             FROM account_holding ORDER BY ticker",
        )
        .fetch_all(&f.app.state.db)
        .await
        .unwrap();
    assert_eq!(holdings.len(), 1, "the sweep is cash, not a holding");
    let (ticker, quantity, buy_in, price, value, currency) = &holdings[0];
    assert_eq!(ticker, "VTI");
    assert_eq!(*quantity, Decimal::from(10));
    assert_eq!(
        *buy_in,
        Some(Decimal::from(250)),
        "a zero cost basis falls back to the purchase price"
    );
    assert_eq!(*price, Some(Decimal::from(300)));
    assert_eq!(*value, Some(Decimal::from(3000)));
    assert_eq!(currency.as_deref(), Some("EUR"));

    // A hand-entered buy-in survives a bridge that reports no cost; an empty or partial list
    // never wipes the stored positions.
    sqlx::query("UPDATE account_holding SET average_buy_in = 111")
        .execute(&f.app.state.db)
        .await
        .unwrap();
    f.accounts_answer(
        200,
        account_set(vec![brokerage(json!([
            { "symbol": "VTI", "shares": "12", "market_value": "3600" },
        ]))]),
    )
    .await;
    f.sync().await;
    let (quantity, buy_in): (Decimal, Option<Decimal>) =
        sqlx::query_as("SELECT quantity, average_buy_in FROM account_holding")
            .fetch_one(&f.app.state.db)
            .await
            .unwrap();
    assert_eq!(quantity, Decimal::from(12));
    assert_eq!(buy_in, Some(Decimal::from(111)));

    for holdings in [
        json!([]),
        json!([{ "symbol": "VTI", "shares": "1", "market_value": "1" }, { "shares": "x" }]),
        json!([{ "symbol": "VTI", "shares": "-3", "market_value": "1" }]),
    ] {
        f.accounts_answer(200, account_set(vec![brokerage(holdings)]))
            .await;
        assert_eq!(f.sync().await.status, StatusCode::OK);
        assert_eq!(
            f.scalar_i64("SELECT count(*) FROM account_holding").await,
            1
        );
        let quantity: Decimal = sqlx::query_scalar("SELECT quantity FROM account_holding")
            .fetch_one(&f.app.state.db)
            .await
            .unwrap();
        assert_eq!(quantity, Decimal::from(12));
    }

    // A ticker no longer reported is deleted, a new one inserted.
    f.accounts_answer(
        200,
        account_set(vec![brokerage(json!([
            { "symbol": "VXUS", "shares": "2", "market_value": "100" },
        ]))]),
    )
    .await;
    f.sync().await;
    let tickers: Vec<String> = sqlx::query_scalar("SELECT ticker FROM account_holding")
        .fetch_all(&f.app.state.db)
        .await
        .unwrap();
    assert_eq!(tickers, ["VXUS"]);
}

#[tokio::test]
async fn positions_in_a_currency_without_a_rate_leave_everything_as_it_was() {
    let f = Fixture::new().await;
    f.connect().await;
    let mut usd = brokerage(json!([{ "symbol": "VTI", "shares": "1", "market_value": "300" }]));
    usd["currency"] = json!("USD");
    f.accounts_answer(200, account_set(vec![usd])).await;
    let body = f.sync().await.json();
    assert_eq!(body[0]["type"], "BROKERAGE");
    assert!(body[0].get("cashBalance").is_none());
    assert_eq!(
        f.scalar_i64("SELECT count(*) FROM account_holding").await,
        0
    );
}

#[tokio::test]
async fn a_checking_account_never_imports_positions() {
    let f = Fixture::new().await;
    f.connect().await;
    f.accounts_answer(
        200,
        account_set(vec![brokerage(json!([
            { "symbol": "SPAXX", "shares": "500", "market_value": "500" },
        ]))]),
    )
    .await;
    let body = f.sync().await.json();
    assert_eq!(
        body[0]["type"], "CHECKING",
        "a sweep-only account stays checking"
    );
    assert_eq!(
        f.scalar_i64("SELECT count(*) FROM account_holding").await,
        0
    );
}

// ── failures ─────────────────────────────────────────────────────────────────

#[tokio::test]
async fn a_revoked_access_says_session_expired_and_marks_the_connection_error() {
    let f = Fixture::new().await;
    f.connect().await;
    f.accounts_answer(403, json!({})).await;

    let response = f.sync().await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    let problem = response.json();
    assert_eq!(problem["code"], "SESSION_EXPIRED");
    assert!(problem["detail"].as_str().unwrap().contains("revoked"));
    assert!(!response.text().contains(PASSWORD) && !response.text().contains(USERNAME));
    assert_eq!(f.status().await["status"], "ERROR");

    let result = picsou::sync::simplefin::resync_reporting(&f.app.state, f.member_id).await;
    assert_eq!(result.status, SyncStatus::NeedsReauth);
    assert_eq!(result.source, "simplefin");
    assert_eq!(result.message, "Reauthentication required");
}

#[tokio::test]
async fn upstream_failures_map_to_uncoded_problems() {
    let f = Fixture::new().await;
    f.connect().await;
    for (status, body, detail) in [
        (
            402,
            json!({}),
            "SimpleFIN requires payment before it will return accounts.",
        ),
        (
            503,
            json!({}),
            "SimpleFIN could not complete the request (HTTP 503).",
        ),
        (
            301,
            json!({}),
            "The SimpleFIN server redirected the request, which Picsou refuses.",
        ),
        (
            200,
            json!({ "errors": ["Bank\nlogin   required"], "accounts": [] }),
            "Bank login required",
        ),
        (
            200,
            json!([1, 2]),
            "SimpleFIN returned a response Picsou could not read.",
        ),
    ] {
        f.accounts_answer(status, body).await;
        let response = f.sync().await;
        assert_eq!(
            response.status,
            StatusCode::UNPROCESSABLE_ENTITY,
            "{status}"
        );
        let problem = response.json();
        assert_eq!(problem["detail"], detail);
        assert!(problem.get("code").is_none());
    }
    let result = picsou::sync::simplefin::resync_reporting(&f.app.state, f.member_id).await;
    assert_eq!(result.status, SyncStatus::Failed);
    assert_eq!(result.message, "Sync failed");
}

#[tokio::test]
async fn resync_reporting_skips_a_member_without_a_connection_and_reports_success() {
    let f = Fixture::new().await;
    let skipped = picsou::sync::simplefin::resync_reporting(&f.app.state, f.member_id).await;
    assert_eq!(skipped.status, SyncStatus::SkippedNotConnected);
    assert_eq!(skipped.message, "No connection");

    f.connect().await;
    f.accounts_answer(200, account_set(vec![account("A1", "EUR", "1")]))
        .await;
    let synced = picsou::sync::simplefin::resync_reporting(&f.app.state, f.member_id).await;
    assert_eq!(synced.status, SyncStatus::Synced);
    assert_eq!(f.scalar_i64("SELECT count(*) FROM account").await, 1);
}

#[tokio::test]
async fn an_undecryptable_access_shows_error_and_a_fixed_mask() {
    let f = Fixture::new().await;
    sqlx::query(
        "INSERT INTO simplefin_connection (member_id, access_url) VALUES ($1, 'not-ciphertext')",
    )
    .bind(f.member_id)
    .execute(&f.app.state.db)
    .await
    .unwrap();
    let status = f.status().await;
    assert_eq!(status["status"], "ERROR");
    assert_eq!(status["maskedToken"], MASK);

    let response = f.sync().await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert!(
        response.json()["detail"]
            .as_str()
            .unwrap()
            .starts_with("Could not decrypt")
    );
    let stored: String = sqlx::query_scalar("SELECT status FROM simplefin_connection")
        .fetch_one(&f.app.state.db)
        .await
        .unwrap();
    assert_eq!(stored, "ERROR");
    assert!(f.bridge.received_requests().await.unwrap().is_empty());
}
