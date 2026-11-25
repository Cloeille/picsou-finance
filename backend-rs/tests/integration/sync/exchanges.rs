//! Crypto exchanges: CryptoExchangeController over HTTP, Binance and Meria stubbed with wiremock.

mod okx;

use chrono::NaiveDate;
use picsou::ledger::sidecar::SyncStatus;
use picsou::sync::exchanges;
use picsou::testing::{Client, TestApp, epoch};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{header, method, path, query_param};
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

const EXCHANGE: &str = "/api/crypto/exchange";
const KEY: &str = "synthetic-key";
const SECRET: &str = "synthetic-secret";

fn d(value: &str) -> Decimal {
    value.parse().expect("decimal")
}

async fn app_with(server: &MockServer) -> TestApp {
    TestApp::builder()
        .env("BINANCE_BASE_URL", server.uri())
        .env("MERIA_BASE_URL", server.uri())
        .with_admin()
        .spawn()
        .await
}

async fn admin_member(app: &TestApp) -> i64 {
    picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .unwrap()
}

/// A coin the crypto registry maps, with a price recorded the day before the test clock.
async fn price(app: &TestApp, ticker: &str, eur: &str) {
    picsou::db::query("DELETE FROM crypto_asset WHERE upper(ticker) = $1")
        .bind(ticker)
        .execute(&app.state.db)
        .await
        .unwrap();
    picsou::db::query(
        "INSERT INTO crypto_asset (ticker, coingecko_id, name, source, resolved_at) VALUES ($1, $2, $1, 'CURATED', now())",
    )
    .bind(ticker)
    .bind(format!("coin-{}", ticker.to_lowercase()))
    .execute(&app.state.db)
    .await
    .unwrap();
    picsou::db::query("INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ($1, $2, $3, now())")
        .bind(ticker)
        .bind(NaiveDate::from_ymd_opt(2026, 1, 14).unwrap())
        .bind(d(eur))
        .execute(&app.state.db)
        .await
        .unwrap();
}

fn envelope(data: Value) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_json(json!({"success": true, "data": data}))
}

async fn meria(server: &MockServer, endpoint: &str, response: ResponseTemplate) {
    Mock::given(method("GET"))
        .and(path(endpoint))
        .and(header("API-KEY", KEY))
        .respond_with(response)
        .mount(server)
        .await;
}

async fn meria_status_ok(server: &MockServer) {
    meria(
        server,
        "/status",
        ResponseTemplate::new(200).set_body_json(json!({"success": true})),
    )
    .await;
}

async fn binance(server: &MockServer, response: ResponseTemplate) {
    Mock::given(method("GET"))
        .and(path("/api/v3/account"))
        .and(header("X-MBX-APIKEY", KEY))
        .respond_with(response)
        .mount(server)
        .await;
}

fn balances(entries: Value) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_json(json!({"balances": entries}))
}

async fn add(client: &Client, body: Value) -> picsou::testing::Response {
    client.post(EXCHANGE).json(&body).send().await
}

async fn holdings(app: &TestApp, account_id: i64) -> Vec<(String, Decimal)> {
    picsou::db::query_as(
        "SELECT ticker, quantity FROM account_holding WHERE account_id = $1 ORDER BY ticker",
    )
    .bind(account_id)
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

async fn session_status(app: &TestApp, id: i64) -> String {
    picsou::db::query_scalar("SELECT status FROM crypto_exchange_session WHERE id = $1")
        .bind(id)
        .fetch_one(&app.state.db)
        .await
        .unwrap()
}

async fn session_count(app: &TestApp) -> i64 {
    picsou::db::query_scalar("SELECT count(*) FROM crypto_exchange_session")
        .fetch_one(&app.state.db)
        .await
        .unwrap()
}

fn detail(response: &picsou::testing::Response) -> String {
    response.json()["detail"]
        .as_str()
        .unwrap_or_default()
        .to_owned()
}

#[test]
fn binance_signs_the_query_with_hmac_sha256_hex() {
    // The example of Binance's API documentation.
    assert_eq!(
        exchanges::binance_signature(
            "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j",
            "symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1&price=0.1&recvWindow=5000&timestamp=1499827319559",
        ),
        "c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71"
    );
}

#[tokio::test]
async fn meria_connects_with_one_key_and_stores_the_breakdown_and_summed_holdings() {
    let server = MockServer::start().await;
    meria_status_ok(&server).await;
    meria(
        &server,
        "/wallets",
        envelope(json!([
            {"currencyCode": "eth", "balance": "0.5"},
            {"currencyCode": "BTC", "balance": 0.1},
            {"currencyCode": " ", "balance": 3},
            {"currencyCode": "SOL", "balance": 0}
        ])),
    )
    .await;
    meria(
        &server,
        "/stakings",
        envelope(json!([
            {"currencyCode": "ETH", "amount": 1, "reward": 0.1, "lockedReward": 0},
            {"currencyCode": "ATOM", "amount": 2, "reward": 5}
        ])),
    )
    .await;
    // A product the user has not subscribed to: HTTP 200 with no body at all.
    meria(&server, "/lendings", ResponseTemplate::new(200)).await;
    let app = app_with(&server).await;
    price(&app, "ETH", "100").await;
    price(&app, "BTC", "50000").await;
    let client = app.login_admin().await;

    let response = add(&client, json!({"type": "MERIA", "apiKey": format!("  {KEY} "), "apiSecret": "", "apiPassphrase": "\u{2003}", "region": " "})).await;

    assert_eq!(response.status, 200, "{}", response.text());
    let account = response.json();
    assert_eq!(account["name"], "MERIA Crypto");
    assert_eq!(account["type"], "CRYPTO");
    assert_eq!(account["provider"], "MERIA");
    assert_eq!(account["isManual"], false);
    // 1.5 ETH x 100 + 0.1 BTC x 50000, each line rounded to the cent; ATOM is unpriced.
    assert_eq!(account["currentBalance"].to_string(), "5150.00");
    let account_id = account["id"].as_i64().unwrap();
    assert_eq!(
        holdings(&app, account_id).await,
        vec![("BTC".to_owned(), d("0.1")), ("ETH".to_owned(), d("1.5"))]
    );

    let row: (
        String,
        Option<String>,
        Option<String>,
        Option<String>,
        String,
    ) = picsou::db::query_as(
        "SELECT api_key, api_secret, api_passphrase, region, status FROM crypto_exchange_session",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_ne!(row.0, KEY, "the key is encrypted at rest");
    assert_eq!(
        app.state.crypto.decrypt(&row.0).unwrap(),
        KEY,
        "trimmed, then encrypted"
    );
    assert_eq!((row.1, row.2, row.3), (None, None, None));
    assert_eq!(row.4, "CONNECTED");

    let status = client
        .get(&format!("{EXCHANGE}/status"))
        .send()
        .await
        .json();
    assert_eq!(status[0]["exchangeType"], "MERIA");
    assert_eq!(status[0]["status"], "CONNECTED");
    assert_eq!(status[0]["lastSyncedAt"], "2026-01-15T10:00:00Z");
    assert!(status[0].get("region").is_none());

    let member_id = admin_member(&app).await;
    picsou::db::query(
        "UPDATE account_holding SET average_buy_in = 80 WHERE account_id = $1 AND ticker = 'ETH'",
    )
    .bind(account_id)
    .execute(&app.state.db)
    .await
    .unwrap();
    let positions = exchanges::get_positions(&app.state, account_id, member_id)
        .await
        .unwrap();
    let lines: Vec<Value> = positions
        .iter()
        .map(|p| serde_json::to_value(p).unwrap())
        .collect();
    let summary: Vec<(String, String)> = lines
        .iter()
        .map(|l| {
            (
                l["product"].as_str().unwrap().to_owned(),
                l["ticker"].as_str().unwrap().to_owned(),
            )
        })
        .collect();
    assert_eq!(
        summary,
        [
            ("SPOT", "BTC"),
            ("SPOT", "ETH"),
            ("STAKING", "ATOM"),
            ("STAKING", "ETH")
        ]
        .map(|(p, t)| (p.to_owned(), t.to_owned()))
    );
    let staked_eth = &lines[3];
    assert_eq!(staked_eth["quantity"].to_string(), "1.00000000");
    assert_eq!(staked_eth["principal"].to_string(), "0.90000000");
    assert_eq!(staked_eth["interest"].to_string(), "0.10000000");
    assert_eq!(
        staked_eth["costBasisEur"].to_string(),
        "80.0000000000000000"
    );
    assert_eq!(staked_eth["pnlPercent"].to_string(), "25.0000");
    assert_eq!(staked_eth["priceAsOf"], "2026-01-14");
    assert_eq!(staked_eth["priceStale"], true);
    let atom = &lines[2];
    assert!(
        atom.get("principal").is_none(),
        "a reward above the amount decomposes nothing"
    );
    assert!(atom.get("interest").is_none());
    assert!(atom.get("currentPriceEur").is_none());
    assert_eq!(atom["priceStale"], false);

    let requests = server.received_requests().await.unwrap();
    for request in &requests {
        assert!(!request.headers.contains_key("authorization"));
        assert!(!request.headers.contains_key("x-mbx-apikey"));
        assert!(
            !request
                .url
                .query()
                .unwrap_or_default()
                .contains("signature")
        );
    }
}

#[tokio::test]
async fn binance_sends_a_signed_timestamp_and_a_spot_only_account_has_no_breakdown() {
    let server = MockServer::start().await;
    let timestamp = epoch().timestamp_millis().to_string();
    let signature = exchanges::binance_signature(SECRET, &format!("timestamp={timestamp}"));
    Mock::given(method("GET"))
        .and(path("/api/v3/account"))
        .and(header("X-MBX-APIKEY", KEY))
        .and(query_param("timestamp", timestamp.as_str()))
        .and(query_param("signature", signature.as_str()))
        .respond_with(balances(json!([
            {"asset": "btc", "free": "0.25000000", "locked": "0.25000000"},
            {"asset": "DOGE", "free": "0.00000000", "locked": "0.00000000"}
        ])))
        .mount(&server)
        .await;
    let app = app_with(&server).await;
    price(&app, "BTC", "40000").await;
    let client = app.login_admin().await;

    let response = add(
        &client,
        json!({"type": "BINANCE", "apiKey": KEY, "apiSecret": SECRET}),
    )
    .await;

    assert_eq!(response.status, 200, "{}", response.text());
    let account = response.json();
    assert_eq!(account["currentBalance"].to_string(), "20000.00");
    let account_id = account["id"].as_i64().unwrap();
    assert_eq!(
        holdings(&app, account_id).await,
        vec![("BTC".to_owned(), d("0.5"))]
    );
    let secret: String = picsou::db::query_scalar("SELECT api_secret FROM crypto_exchange_session")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(app.state.crypto.decrypt(&secret).unwrap(), SECRET);
    let snapshot: Decimal =
        picsou::db::query_scalar("SELECT balance FROM balance_snapshot WHERE account_id = $1")
            .bind(account_id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(snapshot, d("20000"));
    let member_id = admin_member(&app).await;
    assert!(
        exchanges::get_positions(&app.state, account_id, member_id)
            .await
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn an_unpriced_first_sync_creates_the_account_but_a_later_one_refuses_a_zero_balance() {
    let server = MockServer::start().await;
    binance(
        &server,
        balances(json!([{"asset": "STX", "free": "5", "locked": "0"}])),
    )
    .await;
    let app = app_with(&server).await;
    let client = app.login_admin().await;

    let first = add(
        &client,
        json!({"type": "BINANCE", "apiKey": KEY, "apiSecret": SECRET}),
    )
    .await;
    assert_eq!(first.status, 200, "{}", first.text());
    assert_eq!(first.json()["currentBalance"].to_string(), "0");
    let account_id = first.json()["id"].as_i64().unwrap();
    let snapshots: i64 =
        picsou::db::query_scalar("SELECT count(*) FROM balance_snapshot WHERE account_id = $1")
            .bind(account_id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(snapshots, 0, "no zero reaches the history");

    let session_id: i64 = picsou::db::query_scalar("SELECT id FROM crypto_exchange_session")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    let again = client
        .post(&format!("{EXCHANGE}/{session_id}/sync"))
        .send()
        .await;
    assert_eq!(again.status, 422);
    assert!(
        detail(&again).contains("refusing to record a zero balance for BINANCE"),
        "{}",
        again.text()
    );
    assert_eq!(session_status(&app, session_id).await, "ERROR");
}

#[tokio::test]
async fn a_sync_prunes_holdings_the_exchange_no_longer_reports_and_reconnects_the_session() {
    let server = MockServer::start().await;
    meria_status_ok(&server).await;
    meria(&server, "/stakings", envelope(json!([]))).await;
    meria(&server, "/lendings", envelope(json!(null))).await;
    let wallets = Mock::given(method("GET"))
        .and(path("/wallets"))
        .respond_with(envelope(
            json!([{"currencyCode": "ETH", "balance": 1}, {"currencyCode": "BTC", "balance": 1}]),
        ))
        .up_to_n_times(1)
        .mount_as_scoped(&server)
        .await;
    let app = app_with(&server).await;
    price(&app, "ETH", "100").await;
    price(&app, "BTC", "50000").await;
    let client = app.login_admin().await;
    let account_id = add(&client, json!({"type": "MERIA", "apiKey": KEY}))
        .await
        .json()["id"]
        .as_i64()
        .unwrap();
    drop(wallets);
    meria(
        &server,
        "/wallets",
        envelope(json!([{"currencyCode": "ETH", "balance": 2}])),
    )
    .await;
    let session_id: i64 = picsou::db::query_scalar("SELECT id FROM crypto_exchange_session")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    picsou::db::query("UPDATE crypto_exchange_session SET status = 'ERROR' WHERE id = $1")
        .bind(session_id)
        .execute(&app.state.db)
        .await
        .unwrap();

    let response = client
        .post(&format!("{EXCHANGE}/{session_id}/sync"))
        .send()
        .await;

    assert_eq!(response.status, 200, "{}", response.text());
    assert_eq!(
        response.json()["id"].as_i64(),
        Some(account_id),
        "the same account"
    );
    assert_eq!(response.json()["currentBalance"].to_string(), "200.00");
    assert_eq!(
        holdings(&app, account_id).await,
        vec![("ETH".to_owned(), d("2"))]
    );
    assert_eq!(session_status(&app, session_id).await, "CONNECTED");
}

#[tokio::test]
async fn a_meria_failure_on_any_endpoint_fails_the_sync_and_leaves_the_account_untouched() {
    let server = MockServer::start().await;
    meria_status_ok(&server).await;
    meria(
        &server,
        "/wallets",
        envelope(json!([{"currencyCode": "ETH", "balance": 1}])),
    )
    .await;
    meria(&server, "/lendings", envelope(json!([]))).await;
    let stakings = Mock::given(method("GET"))
        .and(path("/stakings"))
        .respond_with(envelope(json!([])))
        .mount_as_scoped(&server)
        .await;
    let app = app_with(&server).await;
    price(&app, "ETH", "100").await;
    let client = app.login_admin().await;
    let account_id = add(&client, json!({"type": "MERIA", "apiKey": KEY}))
        .await
        .json()["id"]
        .as_i64()
        .unwrap();
    let session_id: i64 = picsou::db::query_scalar("SELECT id FROM crypto_exchange_session")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    drop(stakings);

    for (response, expected) in [
        (ResponseTemplate::new(401), "Meria rejected the API key."),
        (
            ResponseTemplate::new(429),
            "Meria rate-limited the sync. Please try again later.",
        ),
        (
            ResponseTemplate::new(500).set_body_string("<html>cf</html>"),
            "Meria answered HTTP 500 for /stakings.",
        ),
        (
            ResponseTemplate::new(200).set_body_json(
                json!({"success": false, "error": {"code": 7, "message": "bad key"}}),
            ),
            "Meria rejected /stakings: bad key (code 7).",
        ),
        (
            ResponseTemplate::new(200).set_body_string("<html>challenge</html>"),
            "Meria returned an unreadable response for /stakings.",
        ),
    ] {
        let scoped = Mock::given(method("GET"))
            .and(path("/stakings"))
            .respond_with(response)
            .mount_as_scoped(&server)
            .await;
        let result = client
            .post(&format!("{EXCHANGE}/{session_id}/sync"))
            .send()
            .await;
        assert_eq!(result.status, 422);
        assert_eq!(detail(&result), expected);
        assert!(!result.text().contains(KEY));
        assert_eq!(session_status(&app, session_id).await, "ERROR");
        drop(scoped);
    }
    let balance: Decimal =
        picsou::db::query_scalar("SELECT current_balance FROM account WHERE id = $1")
            .bind(account_id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(balance, d("100"));
}

#[tokio::test]
async fn an_unexpected_binance_failure_reads_as_try_again_later() {
    let server = MockServer::start().await;
    binance(&server, balances(json!([]))).await;
    let app = app_with(&server).await;
    let client = app.login_admin().await;
    assert_eq!(
        add(
            &client,
            json!({"type": "BINANCE", "apiKey": KEY, "apiSecret": SECRET})
        )
        .await
        .status,
        200
    );
    let session_id: i64 = picsou::db::query_scalar("SELECT id FROM crypto_exchange_session")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    server.reset().await;
    binance(&server, ResponseTemplate::new(503)).await;

    let response = client
        .post(&format!("{EXCHANGE}/{session_id}/sync"))
        .send()
        .await;

    assert_eq!(response.status, 422);
    assert_eq!(
        detail(&response),
        "Could not sync your BINANCE account. Please try again later."
    );
    assert_eq!(session_status(&app, session_id).await, "ERROR");

    server.reset().await;
    binance(&server, ResponseTemplate::new(200)).await;
    let empty = client
        .post(&format!("{EXCHANGE}/{session_id}/sync"))
        .send()
        .await;
    assert_eq!(
        detail(&empty),
        "Binance returned an empty response for /api/v3/account."
    );
}

#[tokio::test]
async fn a_failed_first_sync_saves_no_session_and_a_failed_connection_test_names_the_exchange() {
    let server = MockServer::start().await;
    meria_status_ok(&server).await;
    meria(&server, "/wallets", ResponseTemplate::new(403)).await;
    let app = app_with(&server).await;
    let client = app.login_admin().await;

    let response = add(&client, json!({"type": "MERIA", "apiKey": KEY})).await;
    assert_eq!(response.status, 422);
    assert_eq!(detail(&response), "Meria rejected the API key.");
    assert_eq!(
        session_count(&app).await,
        0,
        "the insert is rolled back with the sync"
    );

    let rejected = add(&client, json!({"type": "MERIA", "apiKey": "another-key"})).await;
    assert_eq!(rejected.status, 422);
    assert_eq!(
        detail(&rejected),
        "Could not connect to MERIA. Please check your API keys."
    );
    let binance = add(
        &client,
        json!({"type": "BINANCE", "apiKey": KEY, "apiSecret": SECRET}),
    )
    .await;
    assert_eq!(
        detail(&binance),
        "Could not connect to BINANCE. Please check your API keys."
    );
    assert_eq!(session_count(&app).await, 0);
}

#[tokio::test]
async fn credentials_are_checked_before_any_network_call_and_never_echoed() {
    let server = MockServer::start().await;
    let app = app_with(&server).await;
    let client = app.login_admin().await;
    let passphrase = "test-passphrase".repeat(14);

    let oversized = add(
        &client,
        json!({"type": "BINANCE", "apiKey": KEY, "apiSecret": SECRET, "apiPassphrase": passphrase}),
    )
    .await;
    assert_eq!(oversized.status, 422);
    assert_eq!(
        oversized.json()["errors"]["apiPassphrase"],
        "size must be between 0 and 200"
    );
    assert!(!oversized.text().contains(&passphrase));

    for (body, status, message) in [
        (
            json!({"type": "BINANCE", "apiKey": " ", "apiSecret": SECRET}),
            400,
            "An API key is required.",
        ),
        (
            json!({"type": "BINANCE", "apiKey": KEY}),
            400,
            "BINANCE requires an API secret as well as an API key.",
        ),
        (
            json!({"type": "MERIA", "apiKey": KEY, "apiSecret": SECRET}),
            400,
            "MERIA connects with a single read-only API key and takes no API secret.",
        ),
        (
            json!({"type": "MERIA", "apiKey": KEY, "apiPassphrase": "words"}),
            400,
            "MERIA does not use an API passphrase.",
        ),
        (
            json!({"type": "BINANCE", "apiKey": KEY, "apiSecret": SECRET, "region": " eea "}),
            400,
            "BINANCE does not use a region.",
        ),
        (
            json!({"type": "KRAKEN", "apiKey": KEY, "apiSecret": SECRET}),
            422,
            "This exchange isn't supported yet.",
        ),
        (
            json!({"type": 1}),
            422,
            "This exchange isn't supported yet.",
        ),
    ] {
        let response = add(&client, body).await;
        assert_eq!(response.status, status, "{}", response.text());
        assert_eq!(detail(&response), message);
        assert!(!response.text().contains("words"));
    }
    assert_eq!(
        add(&client, json!({"type": "binance", "apiKey": KEY}))
            .await
            .status,
        400
    );
    assert!(server.received_requests().await.unwrap().is_empty());
    assert_eq!(session_count(&app).await, 0);
}

#[tokio::test]
async fn sessions_are_scoped_to_their_member_and_removal_soft_deletes_the_account() {
    let server = MockServer::start().await;
    meria_status_ok(&server).await;
    meria(
        &server,
        "/wallets",
        envelope(json!([{"currencyCode": "ETH", "balance": 1}])),
    )
    .await;
    meria(&server, "/stakings", envelope(json!([]))).await;
    meria(&server, "/lendings", envelope(json!([]))).await;
    let app = app_with(&server).await;
    price(&app, "ETH", "100").await;
    let admin = app.login_admin().await;
    let account_id = add(&admin, json!({"type": "MERIA", "apiKey": KEY}))
        .await
        .json()["id"]
        .as_i64()
        .unwrap();
    let session_id: i64 = picsou::db::query_scalar("SELECT id FROM crypto_exchange_session")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    let (_, other_member) = app.create_member("other", "password-1234").await;
    let other = app.login("other", "password-1234").await;

    assert_eq!(
        other.get(&format!("{EXCHANGE}/status")).send().await.json(),
        json!([])
    );
    let sync = other
        .post(&format!("{EXCHANGE}/{session_id}/sync"))
        .send()
        .await;
    assert_eq!(
        (sync.status.as_u16(), detail(&sync)),
        (404, "Exchange session not found".to_owned())
    );
    assert_eq!(
        other
            .delete(&format!("{EXCHANGE}/{session_id}"))
            .send()
            .await
            .status,
        404
    );
    assert!(
        exchanges::get_positions(&app.state, account_id, other_member)
            .await
            .is_err()
    );
    assert_eq!(
        exchanges::resync_all_reporting(&app.state, other_member)
            .await
            .status,
        SyncStatus::SkippedNotConnected
    );

    // The session outlives its deleted account: the sync says what to do instead of retrying.
    picsou::db::query("UPDATE account SET deleted_at = now() WHERE id = $1")
        .bind(account_id)
        .execute(&app.state.db)
        .await
        .unwrap();
    let orphan = admin
        .post(&format!("{EXCHANGE}/{session_id}/sync"))
        .send()
        .await;
    assert_eq!(orphan.status, 422);
    assert!(
        detail(&orphan).contains("Remove the MERIA session in Settings"),
        "{}",
        orphan.text()
    );
    picsou::db::query("UPDATE account SET deleted_at = NULL WHERE id = $1")
        .bind(account_id)
        .execute(&app.state.db)
        .await
        .unwrap();

    assert_eq!(
        admin
            .delete(&format!("{EXCHANGE}/{session_id}"))
            .send()
            .await
            .status,
        204
    );
    assert_eq!(session_count(&app).await, 0);
    let (deleted, snapshots): (bool, i64) = picsou::db::query_as(
        "SELECT deleted_at IS NOT NULL, (SELECT count(*) FROM balance_snapshot WHERE account_id = $1) FROM account WHERE id = $1",
    )
    .bind(account_id)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert!(deleted, "soft-deleted, not removed");
    assert_eq!(snapshots, 1, "the net-worth history survives");
}

#[tokio::test]
async fn resync_all_reporting_names_the_failed_exchanges_and_continues() {
    let server = MockServer::start().await;
    meria_status_ok(&server).await;
    meria(&server, "/wallets", envelope(json!([]))).await;
    meria(&server, "/stakings", envelope(json!([]))).await;
    meria(&server, "/lendings", envelope(json!([]))).await;
    binance(&server, balances(json!([]))).await;
    let app = app_with(&server).await;
    let client = app.login_admin().await;
    let member_id = admin_member(&app).await;
    assert_eq!(
        exchanges::resync_all_reporting(&app.state, member_id)
            .await
            .message,
        "No connected exchange"
    );
    assert_eq!(
        add(
            &client,
            json!({"type": "BINANCE", "apiKey": KEY, "apiSecret": SECRET})
        )
        .await
        .status,
        200
    );
    assert_eq!(
        add(&client, json!({"type": "MERIA", "apiKey": KEY}))
            .await
            .status,
        200
    );

    let synced = exchanges::resync_all_reporting(&app.state, member_id).await;
    assert_eq!(
        (
            synced.source.as_str(),
            synced.status,
            synced.message.as_str()
        ),
        ("crypto-exchanges", SyncStatus::Synced, "")
    );

    server.reset().await;
    binance(
        &server,
        ResponseTemplate::new(500).set_body_string("private diagnostic"),
    )
    .await;
    meria(&server, "/wallets", envelope(json!([]))).await;
    meria(&server, "/stakings", envelope(json!([]))).await;
    meria(&server, "/lendings", envelope(json!([]))).await;
    let failed = exchanges::resync_all_reporting(&app.state, member_id).await;
    assert_eq!(
        (failed.status, failed.message.as_str()),
        (SyncStatus::Failed, "BINANCE")
    );
    let meria_requests = server
        .received_requests()
        .await
        .unwrap()
        .iter()
        .filter(|r: &&Request| r.url.path() == "/lendings")
        .count();
    assert_eq!(meria_requests, 1, "MERIA still synced after BINANCE failed");
}

#[tokio::test]
async fn binance_oversized_response_body_fails_the_sync() {
    let server = MockServer::start().await;
    let body = format!("{}{}", r#"{"balances":[]}"#, " ".repeat(256 * 1024));
    binance(
        &server,
        ResponseTemplate::new(200).set_body_raw(body, "application/json"),
    )
    .await;
    let app = app_with(&server).await;
    let client = app.login_admin().await;

    let response = add(
        &client,
        json!({"type": "BINANCE", "apiKey": KEY, "apiSecret": SECRET}),
    )
    .await;
    assert_eq!(response.status, 422, "{}", response.text());
    assert_eq!(session_count(&app).await, 0);
}

#[tokio::test]
async fn meria_oversized_status_response_fails_connection_test() {
    let server = MockServer::start().await;
    let body = format!("{}{}", r#"{"success":true}"#, " ".repeat(256 * 1024));
    meria(
        &server,
        "/status",
        ResponseTemplate::new(200).set_body_raw(body, "application/json"),
    )
    .await;
    meria(&server, "/wallets", envelope(json!([]))).await;
    meria(&server, "/stakings", envelope(json!([]))).await;
    meria(&server, "/lendings", envelope(json!([]))).await;
    let app = app_with(&server).await;
    let client = app.login_admin().await;

    let response = add(&client, json!({"type": "MERIA", "apiKey": KEY})).await;
    assert_eq!(response.status, 422, "{}", response.text());
    assert_eq!(session_count(&app).await, 0);
}
