//! Louve Invest over its official MCP server: the OAuth connection, the sync and the disconnect
//! over HTTP, Louve stubbed with wiremock. Synthetic codes, tokens and figures only.

use std::collections::HashMap;
use std::time::Duration;

use picsou::clock::Clock;
use picsou::ledger::sidecar::SyncStatus;
use picsou::sync::louve;
use picsou::testing::{Client, TestApp};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use wiremock::matchers::{body_partial_json, body_string_contains, method, path};
use wiremock::{Mock, MockServer, Request, Respond, ResponseTemplate};

const ORIGIN: &str = "https://picsou.example";
const REDIRECT: &str = "https://picsou.example/sync/louve/callback";
const ISSUER: &str = "https://app.louveinvest.com";

fn d(value: &str) -> Decimal {
    value.parse().expect("decimal literal")
}

async fn app(server: &MockServer) -> TestApp {
    TestApp::builder()
        .env("LOUVE_ISSUER_URL", server.uri())
        .env(
            "ALLOWED_ORIGINS",
            format!("{ORIGIN}, https://money.example:8443, http://192.0.2.10:8080"),
        )
        .with_admin()
        .spawn()
        .await
}

async fn admin_member(app: &TestApp) -> i64 {
    picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .expect("admin member")
}

/// The MCP endpoint: answers each JSON-RPC call with its own id.
struct Mcp {
    portfolio: Value,
    sheets: HashMap<String, Value>,
}

impl Respond for Mcp {
    fn respond(&self, request: &Request) -> ResponseTemplate {
        let body: Value = serde_json::from_slice(&request.body).expect("json-rpc");
        let id = body["id"].clone();
        let result = match body["method"].as_str() {
            Some("initialize") => json!({"protocolVersion": "2025-06-18", "capabilities": {}}),
            Some("notifications/initialized") => return ResponseTemplate::new(202),
            Some("tools/call") => match body["params"]["name"].as_str() {
                Some("get_portfolio") => json!({"structuredContent": self.portfolio}),
                Some("get_scpi") => {
                    let code = body["params"]["arguments"]["scpi"]
                        .as_str()
                        .unwrap_or_default();
                    match self.sheets.get(code) {
                        // Text content, the other shape Louve may answer with.
                        Some(sheet) => {
                            json!({"content": [{"type": "text", "text": sheet.to_string()}]})
                        }
                        None => json!({"isError": true, "content": []}),
                    }
                }
                _ => json!({"isError": true}),
            },
            _ => return ResponseTemplate::new(400),
        };
        ResponseTemplate::new(200)
            .insert_header("Mcp-Session-Id", "session-1")
            .set_body_json(json!({"jsonrpc": "2.0", "id": id, "result": result}))
    }
}

fn line(code: &str, name: &str, shares: &str, total: &str) -> Value {
    json!({"scpiUrlId": code, "scpiName": name, "nbShares": shares.parse::<Value>().unwrap(),
        "sellingPrice": total.parse::<Value>().unwrap(), "ownership": ["Pleine propriété"]})
}

fn sheet(code: &str, withdrawal: &str) -> Value {
    json!({"urlId": code, "name": "Aurore", "isinCode": "FR0011631522", "managementCompany": "Synthetic REIM",
        "buyingPriceByShare": 100, "sellingPriceByShare": withdrawal.parse::<Value>().unwrap(),
        "distributionRate": 0.065, "rentFrequency": "quarterly"})
}

/// Twelve Aurore shares at 80, sheet included.
fn aurore() -> Mcp {
    Mcp {
        portfolio: json!({"scpi": {"sellingPrice": 960, "positions": [line("aurore", "Aurore", "12", "960")]}}),
        sheets: HashMap::from([("aurore".to_owned(), sheet("aurore", "80"))]),
    }
}

async fn mount_mcp(server: &MockServer, mcp: Mcp) {
    Mock::given(method("POST"))
        .and(path("/mcp"))
        .respond_with(mcp)
        .mount(server)
        .await;
}

async fn mount_registration(server: &MockServer) {
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/register"))
        .respond_with(ResponseTemplate::new(201).set_body_json(json!({"client_id": "client-1"})))
        .mount(server)
        .await;
}

fn tokens(access: &str, refresh: &str) -> ResponseTemplate {
    ResponseTemplate::new(200)
        .set_body_json(json!({"access_token": access, "refresh_token": refresh,
        "token_type": "Bearer", "expires_in": 3600, "scope": "portfolio:read"}))
}

async fn mount_exchange(server: &MockServer) {
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/token"))
        .and(body_string_contains("grant_type=authorization_code"))
        .respond_with(tokens("access-1", "refresh-1"))
        .mount(server)
        .await;
}

async fn mount_revocation(server: &MockServer) {
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/revoke"))
        .respond_with(ResponseTemplate::new(200))
        .mount(server)
        .await;
}

async fn start(client: &Client, origin: &str) -> picsou::testing::Response {
    client
        .post("/api/louve/auth/start")
        .header("Origin", origin)
        .send()
        .await
}

fn query(url: &str) -> HashMap<String, String> {
    url::Url::parse(url)
        .expect("url")
        .query_pairs()
        .into_owned()
        .collect()
}

/// A full consent: start, callback with Louve's issuer, first sync.
async fn connect(server: &MockServer, client: &Client) -> Value {
    let started = start(client, ORIGIN).await;
    assert_eq!(started.status, 200, "{}", started.text());
    let state = query(started.json()["authorizeUrl"].as_str().unwrap())["state"].clone();
    let done = client
        .post("/api/louve/auth/complete")
        .json(&json!({"code": "code-1", "state": state, "iss": ISSUER}))
        .send()
        .await;
    assert_eq!(done.status, 200, "{}", done.text());
    let _ = server;
    done.json()
}

async fn scalar<T: picsou::db::DbDecode>(app: &TestApp, sql: &str) -> T {
    picsou::db::query_scalar(sql)
        .fetch_one(&app.state.db)
        .await
        .expect(sql)
}

async fn connected_server() -> MockServer {
    let server = MockServer::start().await;
    mount_registration(&server).await;
    mount_exchange(&server).await;
    mount_revocation(&server).await;
    server
}

/// Stores a token set expiring at expires_at (epoch seconds) on a CONNECTED row of the member.
async fn store_tokens(app: &TestApp, member_id: i64, access: &str, refresh: &str, expires_at: i64) {
    let plain =
        json!({"v": 1, "clientId": "client-1", "redirectUri": REDIRECT, "accessToken": access,
        "refreshToken": refresh, "expiresAt": expires_at})
        .to_string();
    let encrypted = app.state.crypto.encrypt(&plain).expect("encrypt");
    picsou::db::query(
        "INSERT INTO louve_connection (member_id, token_state, status) VALUES ($1, $2, 'CONNECTED') \
         ON CONFLICT (member_id) DO UPDATE SET token_state = EXCLUDED.token_state",
    )
    .bind(member_id)
    .bind(encrypted)
    .execute(&app.state.db)
    .await
    .expect("tokens");
}

fn stored_state(app: &TestApp, encrypted: &str) -> Value {
    serde_json::from_str(&app.state.crypto.decrypt(encrypted).expect("decrypt")).expect("state")
}

// -- status, origins, validation ---------------------------------------------------------------

#[tokio::test]
async fn the_status_of_a_member_who_never_connected_has_only_connected_false() {
    let server = MockServer::start().await;
    let app = app(&server).await;
    let client = app.login_admin().await;

    let status = client.get("/api/louve/status").send().await;

    assert_eq!(status.status, 200);
    assert_eq!(status.json(), json!({"connected": false}));
    let anonymous = app.client().get("/api/louve/status").send().await;
    assert_eq!(anonymous.status, 401);
}

#[tokio::test]
async fn an_origin_outside_the_list_or_plain_http_off_loopback_is_refused_with_its_code() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/register"))
        .respond_with(ResponseTemplate::new(500))
        .expect(0)
        .mount(&server)
        .await;
    let app = app(&server).await;
    let client = app.login_admin().await;

    // A foreign origin never reaches the controller: the CORS filter refuses it first.
    assert_eq!(start(&client, "https://evil.example").await.status, 403);
    // The instance's own address, missing from the list.
    let unlisted = start(&client, &app.base_url).await;
    assert_eq!(unlisted.status, 400);
    assert_eq!(unlisted.json()["code"], "ORIGIN_NOT_ALLOWED");
    let none = client.post("/api/louve/auth/start").send().await;
    assert_eq!(none.json()["code"], "ORIGIN_NOT_ALLOWED");
    let plain = start(&client, "http://192.0.2.10:8080").await;
    assert_eq!(plain.status, 400);
    assert_eq!(plain.json()["code"], "ORIGIN_NOT_SECURE");
    assert_eq!(
        plain.json()["detail"],
        "Louve Invest needs Picsou to be reached over HTTPS."
    );
}

#[tokio::test]
async fn the_redirect_uri_is_the_configured_origin_with_its_port_and_one_registration_each() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/register"))
        .and(body_partial_json(
            json!({"redirect_uris": ["https://money.example:8443/sync/louve/callback"],
            "token_endpoint_auth_method": "none", "scope": "portfolio:read"}),
        ))
        .respond_with(ResponseTemplate::new(201).set_body_json(json!({"client_id": "client-9"})))
        .expect(1)
        .mount(&server)
        .await;
    let app = app(&server).await;
    let client = app.login_admin().await;

    for _ in 0..2 {
        let started = start(&client, "https://money.example:8443").await;
        assert_eq!(started.status, 200, "{}", started.text());
        let url = started.json()["authorizeUrl"].as_str().unwrap().to_owned();
        assert!(url.starts_with(&format!("{}/mcp/oauth/authorize?", server.uri())));
        let q = query(&url);
        assert_eq!(
            q["redirect_uri"],
            "https://money.example:8443/sync/louve/callback"
        );
        assert_eq!(q["client_id"], "client-9");
    }
    let key = format!(
        "louve.client-id:{}",
        hex::encode(Sha256::digest(
            b"https://money.example:8443/sync/louve/callback"
        ))
    );
    let stored: String =
        picsou::db::query_scalar("SELECT value FROM app_setting WHERE setting_key = $1")
            .bind(&key)
            .fetch_one(&app.state.db)
            .await
            .expect("registration");
    assert_eq!(stored, "client-9");
    assert_eq!(key.len(), 80);
}

#[tokio::test]
async fn a_failed_registration_is_upstream_unavailable_and_records_nothing() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/register"))
        .respond_with(ResponseTemplate::new(503))
        .mount(&server)
        .await;
    let app = app(&server).await;
    let client = app.login_admin().await;

    let started = start(&client, ORIGIN).await;

    assert_eq!(started.status, 422);
    assert_eq!(started.json()["code"], "UPSTREAM_UNAVAILABLE");
    assert_eq!(
        scalar::<i64>(&app, "SELECT count(*) FROM louve_connection").await,
        0
    );
}

#[tokio::test]
async fn a_callback_body_is_validated_before_anything() {
    let server = MockServer::start().await;
    let app = app(&server).await;
    let client = app.login_admin().await;

    let blank = client
        .post("/api/louve/auth/complete")
        .json(&json!({"code": " ", "state": "x".repeat(513)}))
        .send()
        .await;

    assert_eq!(blank.status, 422);
    assert_eq!(
        blank.json()["errors"],
        json!({"code": "must not be blank", "iss": "must not be blank",
            "state": "size must be between 0 and 512"})
    );
}

#[tokio::test]
async fn start_and_callback_share_ten_calls_per_fifteen_minutes() {
    let server = MockServer::start().await;
    let app = app(&server).await;
    let client = app.login_admin().await;

    for _ in 0..10 {
        assert_eq!(start(&client, &app.base_url).await.status, 400);
    }
    let limited = client
        .post("/api/louve/auth/complete")
        .json(&json!({"code": "c", "state": "s", "iss": ISSUER}))
        .send()
        .await;
    assert_eq!(limited.status, 429);
    assert_eq!(
        limited.json()["detail"],
        "Too many Louve Invest connection attempts. Please wait before retrying."
    );
    app.clock.advance(Duration::from_secs(15 * 60));
    assert_eq!(start(&client, &app.base_url).await.status, 400);
}

// -- connection -------------------------------------------------------------------------------

#[tokio::test]
async fn a_consent_stores_encrypted_tokens_and_the_first_sync_creates_the_scpi_account() {
    let server = connected_server().await;
    mount_mcp(&server, aurore()).await;
    let app = app(&server).await;
    let client = app.login_admin().await;

    let started = start(&client, ORIGIN).await;
    let url = started.json()["authorizeUrl"].as_str().unwrap().to_owned();
    let q = query(&url);
    assert_eq!(q["scope"], "portfolio:read");
    assert_eq!(q["code_challenge_method"], "S256");
    assert_eq!(q["resource"], "https://app.louveinvest.com/mcp");
    assert_eq!(q["redirect_uri"], REDIRECT);
    let verifier: String = scalar(&app, "SELECT oauth_verifier FROM louve_connection").await;
    let verifier = app.state.crypto.decrypt(&verifier).expect("verifier");
    use base64::Engine;
    let challenge = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .encode(Sha256::digest(verifier.as_bytes()));
    assert_eq!(q["code_challenge"], challenge);
    assert_eq!(
        client.get("/api/louve/status").send().await.json(),
        json!({"connected": false, "status": "PENDING"})
    );

    Mock::given(method("POST"))
        .and(path("/mcp/oauth/token"))
        .and(body_string_contains(format!("code_verifier={verifier}")))
        .and(body_string_contains(
            "resource=https%3A%2F%2Fapp.louveinvest.com%2Fmcp",
        ))
        .respond_with(tokens("access-1", "refresh-1"))
        .expect(1)
        .with_priority(1)
        .mount(&server)
        .await;
    let done = client
        .post("/api/louve/auth/complete")
        .json(&json!({"code": "code-1", "state": q["state"], "iss": ISSUER}))
        .send()
        .await;

    assert_eq!(done.status, 200, "{}", done.text());
    assert_eq!(
        done.json(),
        json!({"connected": true, "status": "CONNECTED", "lastSyncedAt": "2026-01-15T10:00:00Z"})
    );
    let encrypted: String = scalar(&app, "SELECT token_state FROM louve_connection").await;
    assert!(!encrypted.contains("access-1"));
    assert_eq!(stored_state(&app, &encrypted)["refreshToken"], "refresh-1");
    assert_eq!(
        scalar::<Option<String>>(&app, "SELECT oauth_state FROM louve_connection").await,
        None
    );
    let (name, provider, balance): (String, String, Decimal) = picsou::db::query_as(
        "SELECT name, provider, current_balance FROM account WHERE type = 'SCPI'",
    )
    .fetch_one(&app.state.db)
    .await
    .expect("account");
    assert_eq!(
        (name.as_str(), provider.as_str()),
        ("Aurore", "Louve Invest")
    );
    assert_eq!(balance, d("960.00000000"));
    let (code, isin, company, annual, frequency): (String, String, String, Decimal, String) =
        picsou::db::query_as(
            "SELECT louve_fund_code, isin, management_company, annual_distribution_per_share_eur, \
             distribution_frequency FROM scpi_position",
        )
        .fetch_one(&app.state.db)
        .await
        .expect("position");
    assert_eq!(code, "aurore");
    assert_eq!(isin, "FR0011631522");
    assert_eq!(company, "Synthetic REIM");
    assert_eq!(annual, d("6.50"));
    assert_eq!(frequency, "QUARTERLY");
    assert_eq!(
        scalar::<String>(&app, "SELECT last_outcome FROM scpi_provider_fund").await,
        "CREATED"
    );
}

#[tokio::test]
async fn a_wrong_issuer_a_forged_state_an_expired_or_replayed_attempt_are_refused() {
    let server = connected_server().await;
    mount_mcp(&server, aurore()).await;
    let app = app(&server).await;
    let client = app.login_admin().await;
    let complete = |state: String, iss: &'static str| {
        let client = &client;
        async move {
            client
                .post("/api/louve/auth/complete")
                .json(&json!({"code": "code-1", "state": state, "iss": iss}))
                .send()
                .await
        }
    };
    let state = query(
        start(&client, ORIGIN).await.json()["authorizeUrl"]
            .as_str()
            .unwrap(),
    )["state"]
        .clone();

    let evil = complete(state.clone(), "https://evil.example").await;
    assert_eq!(evil.status, 422);
    assert_eq!(evil.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    assert_eq!(
        evil.json()["detail"],
        "This answer did not come from Louve Invest. Start the connection again."
    );
    let forged = complete("forged".to_owned(), ISSUER).await;
    assert_eq!(forged.json()["code"], "AUTH_ATTEMPT_EXPIRED");

    app.clock.advance(Duration::from_secs(11 * 60));
    assert_eq!(
        complete(state, ISSUER).await.json()["code"],
        "AUTH_ATTEMPT_EXPIRED"
    );

    let state = query(
        start(&client, ORIGIN).await.json()["authorizeUrl"]
            .as_str()
            .unwrap(),
    )["state"]
        .clone();
    assert_eq!(complete(state.clone(), ISSUER).await.status, 200);
    let replay = complete(state, ISSUER).await;
    assert_eq!(replay.status, 422);
    assert_eq!(replay.json()["code"], "AUTH_ATTEMPT_EXPIRED");
}

#[tokio::test]
async fn an_unknown_client_on_the_exchange_drops_that_registration() {
    let server = MockServer::start().await;
    mount_registration(&server).await;
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/token"))
        .respond_with(ResponseTemplate::new(400).set_body_json(json!({"error": "invalid_client"})))
        .mount(&server)
        .await;
    let app = app(&server).await;
    let client = app.login_admin().await;
    let state = query(
        start(&client, ORIGIN).await.json()["authorizeUrl"]
            .as_str()
            .unwrap(),
    )["state"]
        .clone();

    let refused = client
        .post("/api/louve/auth/complete")
        .json(&json!({"code": "code-1", "state": state, "iss": ISSUER}))
        .send()
        .await;

    assert_eq!(refused.status, 422);
    assert_eq!(refused.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    assert_eq!(
        scalar::<i64>(
            &app,
            "SELECT count(*) FROM app_setting WHERE setting_key LIKE 'louve.client-id:%'"
        )
        .await,
        0
    );
}

#[tokio::test]
async fn a_new_consent_revokes_the_grant_it_replaces_and_a_failing_first_sync_stays_connected() {
    let server = connected_server().await;
    Mock::given(method("POST"))
        .and(path("/mcp"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/revoke"))
        .and(body_string_contains("token=old-refresh"))
        .respond_with(ResponseTemplate::new(200))
        .expect(1)
        .with_priority(1)
        .mount(&server)
        .await;
    let app = app(&server).await;
    let client = app.login_admin().await;
    store_tokens(
        &app,
        admin_member(&app).await,
        "old-access",
        "old-refresh",
        0,
    )
    .await;

    let status = connect(&server, &client).await;

    assert_eq!(status, json!({"connected": true, "status": "ERROR"}));
    let encrypted: String = scalar(&app, "SELECT token_state FROM louve_connection").await;
    assert_eq!(stored_state(&app, &encrypted)["accessToken"], "access-1");
    assert_eq!(
        scalar::<i64>(&app, "SELECT grant_generation FROM louve_connection").await,
        1
    );
}

// -- sync ------------------------------------------------------------------------------------

#[tokio::test]
async fn a_sync_without_a_connection_is_session_expired() {
    let server = MockServer::start().await;
    let app = app(&server).await;
    let client = app.login_admin().await;

    let sync = client.post("/api/louve/sync").send().await;

    assert_eq!(sync.status, 422);
    assert_eq!(sync.json()["code"], "SESSION_EXPIRED");
    assert_eq!(
        sync.json()["detail"],
        "No Louve Invest connection. Connect first."
    );
    let member = admin_member(&app).await;
    assert_eq!(
        louve::resync_reporting(&app.state, member).await.status,
        SyncStatus::SkippedNotConnected
    );
}

#[tokio::test]
async fn an_expiring_token_is_refreshed_and_stored_before_it_is_used() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/token"))
        .and(body_string_contains("grant_type=refresh_token"))
        .and(body_string_contains("refresh_token=refresh-1"))
        .respond_with(tokens("access-2", "refresh-2"))
        .expect(1)
        .mount(&server)
        .await;
    mount_mcp(&server, aurore()).await;
    let app = app(&server).await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    // Expires 30 s from now: inside the 60 s margin.
    store_tokens(
        &app,
        member,
        "access-1",
        "refresh-1",
        app.clock.now().timestamp() + 30,
    )
    .await;

    let sync = client.post("/api/louve/sync").send().await;

    assert_eq!(sync.status, 200, "{}", sync.text());
    let encrypted: String = scalar(&app, "SELECT token_state FROM louve_connection").await;
    let state = stored_state(&app, &encrypted);
    assert_eq!(state["accessToken"], "access-2");
    assert_eq!(state["refreshToken"], "refresh-2");
    assert_eq!(state["expiresAt"], app.clock.now().timestamp() + 3600);
    assert_eq!(
        scalar::<i64>(&app, "SELECT grant_generation FROM louve_connection").await,
        0
    );
    let requests = server.received_requests().await.unwrap();
    let mcp_bearer = requests
        .iter()
        .find(|r| r.url.path() == "/mcp")
        .and_then(|r| r.headers.get("authorization"))
        .and_then(|v| v.to_str().ok())
        .map(str::to_owned);
    assert_eq!(mcp_bearer.as_deref(), Some("Bearer access-2"));
}

#[tokio::test]
async fn a_refused_access_token_is_refreshed_once_then_read_again() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/mcp"))
        .and(wiremock::matchers::header("authorization", "Bearer stale"))
        .respond_with(ResponseTemplate::new(401))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/token"))
        .respond_with(tokens("access-2", "refresh-2"))
        .expect(1)
        .mount(&server)
        .await;
    mount_mcp(&server, aurore()).await;
    let app = app(&server).await;
    let client = app.login_admin().await;
    store_tokens(
        &app,
        admin_member(&app).await,
        "stale",
        "refresh-1",
        app.clock.now().timestamp() + 3000,
    )
    .await;

    let sync = client.post("/api/louve/sync").send().await;

    assert_eq!(sync.status, 200, "{}", sync.text());
    assert_eq!(sync.json()["status"], "CONNECTED");
}

#[tokio::test]
async fn a_revoked_grant_asks_for_a_new_consent_and_the_daily_run_stops_calling_louve() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/token"))
        .respond_with(ResponseTemplate::new(400).set_body_json(json!({"error": "invalid_grant"})))
        .expect(1)
        .mount(&server)
        .await;
    let app = app(&server).await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    store_tokens(&app, member, "access-1", "refresh-1", 0).await;

    let sync = client.post("/api/louve/sync").send().await;

    assert_eq!(sync.status, 422);
    assert_eq!(sync.json()["code"], "SESSION_EXPIRED");
    assert_eq!(
        client.get("/api/louve/status").send().await.json(),
        json!({"connected": true, "status": "REAUTH_REQUIRED"})
    );
    let daily = louve::resync_reporting(&app.state, member).await;
    assert_eq!(daily.status, SyncStatus::NeedsReauth);
    assert_eq!(daily.message, "SESSION_EXPIRED");
}

#[tokio::test]
async fn a_client_refused_on_refresh_is_forgotten_but_a_newer_registration_is_kept() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/token"))
        .respond_with(ResponseTemplate::new(401).set_body_json(json!({"error": "invalid_client"})))
        .mount(&server)
        .await;
    let app = app(&server).await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let key = format!(
        "louve.client-id:{}",
        hex::encode(Sha256::digest(REDIRECT.as_bytes()))
    );
    picsou::db::query("INSERT INTO app_setting (setting_key, value) VALUES ($1, 'client-1')")
        .bind(&key)
        .execute(&app.state.db)
        .await
        .unwrap();
    store_tokens(&app, member, "access-1", "refresh-1", 0).await;

    let sync = client.post("/api/louve/sync").send().await;
    assert_eq!(sync.json()["code"], "SESSION_EXPIRED");
    assert_eq!(
        scalar::<i64>(
            &app,
            "SELECT count(*) FROM app_setting WHERE setting_key LIKE 'louve.client-id:%'"
        )
        .await,
        0
    );

    picsou::db::query("INSERT INTO app_setting (setting_key, value) VALUES ($1, 'client-2')")
        .bind(&key)
        .execute(&app.state.db)
        .await
        .unwrap();
    store_tokens(&app, member, "access-1", "refresh-1", 0).await;
    client.post("/api/louve/sync").send().await;
    assert_eq!(
        scalar::<String>(
            &app,
            "SELECT value FROM app_setting WHERE setting_key LIKE 'louve.client-id:%'"
        )
        .await,
        "client-2"
    );
}

#[tokio::test]
async fn an_unreadable_stored_state_asks_for_a_new_consent() {
    let server = MockServer::start().await;
    let app = app(&server).await;
    let client = app.login_admin().await;
    picsou::db::query("INSERT INTO louve_connection (member_id, token_state, status) VALUES ($1, 'garbage', 'CONNECTED')")
        .bind(admin_member(&app).await)
        .execute(&app.state.db)
        .await
        .unwrap();

    let sync = client.post("/api/louve/sync").send().await;

    assert_eq!(sync.status, 422);
    assert_eq!(sync.json()["code"], "INVALID_SESSION_STATE");
    assert_eq!(
        scalar::<String>(&app, "SELECT status FROM louve_connection").await,
        "REAUTH_REQUIRED"
    );
}

async fn synced(mcp: Mcp) -> (MockServer, TestApp, Client, picsou::testing::Response) {
    let server = MockServer::start().await;
    mount_mcp(&server, mcp).await;
    let app = app(&server).await;
    let client = app.login_admin().await;
    store_tokens(
        &app,
        admin_member(&app).await,
        "access-1",
        "refresh-1",
        app.clock.now().timestamp() + 3600,
    )
    .await;
    let sync = client.post("/api/louve/sync").send().await;
    (server, app, client, sync)
}

#[tokio::test]
async fn lines_that_do_not_add_up_to_the_declared_total_write_nothing() {
    let mut mcp = aurore();
    mcp.portfolio["scpi"]["sellingPrice"] = json!(5000);
    let (_server, app, _client, sync) = synced(mcp).await;

    assert_eq!(sync.status, 422);
    assert_eq!(sync.json()["code"], "PORTFOLIO_INCOMPLETE");
    assert_eq!(scalar::<i64>(&app, "SELECT count(*) FROM account").await, 0);
    assert_eq!(
        scalar::<String>(&app, "SELECT status FROM louve_connection").await,
        "ERROR"
    );
}

#[tokio::test]
async fn when_the_sheet_and_the_line_disagree_the_lower_withdrawal_price_wins() {
    let mut mcp = aurore();
    mcp.sheets
        .insert("aurore".to_owned(), sheet("aurore", "85"));
    let (_server, app, _client, sync) = synced(mcp).await;

    assert_eq!(sync.status, 200, "{}", sync.text());
    assert_eq!(
        scalar::<Decimal>(&app, "SELECT withdrawal_price_eur FROM scpi_position").await,
        d("80.00000000")
    );
    assert_eq!(
        scalar::<Decimal>(&app, "SELECT current_balance FROM account").await,
        d("960.00000000")
    );
}

#[tokio::test]
async fn without_a_sheet_the_line_gives_the_price_rounded_to_eight_places() {
    let mcp = Mcp {
        portfolio: json!({"scpi": {"sellingPrice": "1000.00", "positions": [line("aurore", "Aurore", "3", "1000.00")]}}),
        sheets: HashMap::new(),
    };
    let (_server, app, _client, sync) = synced(mcp).await;

    assert_eq!(sync.status, 200, "{}", sync.text());
    assert_eq!(
        scalar::<Decimal>(&app, "SELECT withdrawal_price_eur FROM scpi_position").await,
        d("333.33333333")
    );
    assert_eq!(
        scalar::<Decimal>(&app, "SELECT current_balance FROM account").await,
        d("999.99999999")
    );
}

#[tokio::test]
async fn a_dismembered_line_is_recorded_but_never_valued() {
    let mut nue = line("aurore", "Aurore", "12", "960");
    nue["ownership"] = json!(["Nue-propriété"]);
    let mcp = Mcp {
        portfolio: json!({"scpi": {"sellingPrice": 960, "positions": [nue]}}),
        sheets: HashMap::from([("aurore".to_owned(), sheet("aurore", "80"))]),
    };
    let (_server, app, _client, sync) = synced(mcp).await;

    assert_eq!(sync.status, 200, "{}", sync.text());
    assert_eq!(scalar::<i64>(&app, "SELECT count(*) FROM account").await, 0);
    assert_eq!(
        scalar::<String>(&app, "SELECT last_outcome FROM scpi_provider_fund").await,
        "UNSUPPORTED_OWNERSHIP"
    );
}

#[tokio::test]
async fn a_portfolio_without_its_scpi_side_is_a_format_change() {
    let mcp = Mcp {
        portfolio: json!({"lifeInsurance": []}),
        sheets: HashMap::new(),
    };
    let (_server, _app, _client, sync) = synced(mcp).await;

    assert_eq!(sync.status, 422);
    assert_eq!(sync.json()["code"], "UPSTREAM_FORMAT_CHANGED");
}

#[tokio::test]
async fn a_manual_account_named_after_the_distributor_and_the_fund_is_adopted() {
    let server = MockServer::start().await;
    mount_mcp(&server, aurore()).await;
    let app = app(&server).await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let mut draft = picsou::ledger::accounts::NewAccount::new(
        member,
        "Louve Invest - Aurore",
        picsou::ledger::model::AccountType::Scpi,
    );
    draft.current_balance = d("10");
    let account = picsou::ledger::accounts::insert(&app.state.db, &draft, app.clock.now())
        .await
        .unwrap();
    store_tokens(
        &app,
        member,
        "access-1",
        "refresh-1",
        app.clock.now().timestamp() + 3600,
    )
    .await;

    let sync = client.post("/api/louve/sync").send().await;

    assert_eq!(sync.status, 200, "{}", sync.text());
    assert_eq!(scalar::<i64>(&app, "SELECT count(*) FROM account").await, 1);
    let linked: i64 = scalar(
        &app,
        "SELECT account_id FROM scpi_position WHERE louve_fund_code = 'aurore'",
    )
    .await;
    assert_eq!(linked, account.id);
    assert_eq!(
        scalar::<String>(&app, "SELECT last_outcome FROM scpi_provider_fund").await,
        "LINKED"
    );

    let again = client.post("/api/louve/sync").send().await;
    assert_eq!(again.status, 200);
    assert_eq!(
        scalar::<String>(&app, "SELECT last_outcome FROM scpi_provider_fund").await,
        "UPDATED"
    );
}

#[tokio::test]
async fn a_fund_no_longer_listed_is_reported_missing_and_left_untouched() {
    let (server, app, client, sync) = synced(aurore()).await;
    assert_eq!(sync.status, 200);
    server.reset().await;
    mount_mcp(
        &server,
        Mcp {
            portfolio: json!({"scpi": null}),
            sheets: HashMap::new(),
        },
    )
    .await;

    let again = client.post("/api/louve/sync").send().await;

    assert_eq!(again.status, 200, "{}", again.text());
    assert_eq!(
        scalar::<String>(&app, "SELECT last_outcome FROM scpi_provider_fund").await,
        "MISSING_AT_PROVIDER"
    );
    assert_eq!(
        scalar::<Decimal>(&app, "SELECT current_balance FROM account").await,
        d("960.00000000")
    );
}

// -- disconnect, isolation ----------------------------------------------------------------------

#[tokio::test]
async fn disconnecting_revokes_both_tokens_whatever_the_status_and_twice_is_harmless() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/revoke"))
        .respond_with(ResponseTemplate::new(200))
        .expect(2)
        .mount(&server)
        .await;
    let app = app(&server).await;
    let client = app.login_admin().await;
    store_tokens(&app, admin_member(&app).await, "access-1", "refresh-1", 0).await;
    picsou::db::query("UPDATE louve_connection SET status = 'REAUTH_REQUIRED'")
        .execute(&app.state.db)
        .await
        .unwrap();

    let gone = client.delete("/api/louve/connection").send().await;
    assert_eq!(gone.status, 204);
    assert!(gone.body.is_empty());
    assert_eq!(
        scalar::<i64>(&app, "SELECT count(*) FROM louve_connection").await,
        0
    );
    assert_eq!(
        client.delete("/api/louve/connection").send().await.status,
        204
    );
    let hints: Vec<String> = server
        .received_requests()
        .await
        .unwrap()
        .iter()
        .map(|r| String::from_utf8_lossy(&r.body).into_owned())
        .collect();
    assert!(
        hints[0].contains("token=refresh-1") && hints[0].contains("token_type_hint=refresh_token")
    );
    assert!(hints[1].contains("token=access-1"));
}

#[tokio::test]
async fn a_members_connection_is_their_own() {
    let server = MockServer::start().await;
    mount_revocation(&server).await;
    let app = app(&server).await;
    let admin = app.login_admin().await;
    let (_, other) = app.create_member("dominique", "password-1234").await;
    let member = app.login("dominique", "password-1234").await;
    store_tokens(&app, admin_member(&app).await, "access-1", "refresh-1", 0).await;

    assert_eq!(
        member.get("/api/louve/status").send().await.json(),
        json!({"connected": false})
    );
    assert_eq!(
        member.post("/api/louve/sync").send().await.json()["code"],
        "SESSION_EXPIRED"
    );
    assert_eq!(
        member.delete("/api/louve/connection").send().await.status,
        204
    );
    assert_eq!(
        admin.get("/api/louve/status").send().await.json(),
        json!({"connected": true, "status": "CONNECTED"})
    );
    assert_eq!(
        louve::resync_reporting(&app.state, other).await.status,
        SyncStatus::SkippedNotConnected
    );
}
