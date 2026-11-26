//! TradeRepublicController, TradeRepublicSyncService and TradeRepublicAdapter over HTTP: the
//! tr-auth sidecar stubbed with wiremock, TR's WebSocket with a local stub server.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use picsou::connectors::trade_republic::ws::{self, Message};
use picsou::ledger::sidecar::SidecarBank;
use picsou::testing::{Client, TestApp};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use wiremock::matchers::{body_json, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{admin, answer, app_with_sidecar, count, d, fail, member, session_of, settle};

const URL: &str = "TR_AUTH_URL";
const BANK: SidecarBank = SidecarBank::TradeRepublic;

/// What the stub WebSocket answers each subscription with.
#[derive(Clone, Default)]
struct TrSocket {
    /// Frame after the id ("A {...}" or "E {...}") per secAccNo.
    portfolios: HashMap<String, String>,
    /// instrument answer per ISIN; an absent one answers {"exchangeIds":["LSX"]}.
    instruments: HashMap<String, String>,
    /// last price per ticker id (ISIN.venue); an absent one answers FORBIDDEN.
    prices: HashMap<String, String>,
    /// availableCash answer per cash account number, the global one under "".
    cash: HashMap<String, String>,
    auth_error: bool,
}

impl TrSocket {
    fn answer(&self, message: &str) -> Option<String> {
        if message.starts_with("connect ") {
            return Some("connected".to_owned());
        }
        let mut parts = message.splitn(3, ' ');
        let (_, id, payload) = (parts.next()?, parts.next()?, parts.next()?);
        let sub: Value = serde_json::from_str(payload).ok()?;
        if self.auth_error {
            return Some(format!(
                r#"{id} E {{"errors":[{{"errorCode":"AUTHENTICATION_ERROR"}}]}}"#
            ));
        }
        Some(match sub["type"].as_str()? {
            "availableCash" => {
                let key = sub["accountNumber"].as_str().unwrap_or("");
                let answer = self.cash.get(key)?;
                if answer.starts_with("A ") || answer.starts_with("E ") {
                    format!("{id} {answer}")
                } else {
                    format!("{id} A {answer}")
                }
            }
            "compactPortfolioByType" => {
                format!("{id} {}", self.portfolios.get(sub["secAccNo"].as_str()?)?)
            }
            "instrument" => {
                let isin = sub["id"].as_str()?;
                let answer = self
                    .instruments
                    .get(isin)
                    .cloned()
                    .unwrap_or_else(|| r#"{"exchangeIds":["LSX"]}"#.to_owned());
                format!("{id} A {answer}")
            }
            "ticker" => match self.prices.get(sub["id"].as_str()?) {
                Some(price) => format!(r#"{id} A {{"last":{{"price":"{price}"}}}}"#),
                None => format!(r#"{id} E {{"errors":[{{"errorCode":"FORBIDDEN"}}]}}"#),
            },
            _ => return None,
        })
    }
}

/// Serves \`socket\` on a local port; returns its ws:// URL and the handshake Cookie headers seen.
async fn serve(socket: TrSocket) -> (String, Arc<Mutex<Vec<String>>>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("ws://{}/", listener.local_addr().unwrap());
    let cookies = Arc::new(Mutex::new(Vec::new()));
    let seen = cookies.clone();
    tokio::spawn(async move {
        loop {
            let Ok((mut stream, _)) = listener.accept().await else {
                return;
            };
            let socket = socket.clone();
            let seen = seen.clone();
            tokio::spawn(async move {
                let mut head = Vec::new();
                let mut byte = [0u8; 1];
                while !head.ends_with(b"\r\n\r\n") {
                    if stream.read_exact(&mut byte).await.is_err() {
                        return;
                    }
                    head.push(byte[0]);
                }
                let head = String::from_utf8_lossy(&head).to_string();
                let header = |name: &str| {
                    head.lines().find_map(|l| {
                        let (k, v) = l.split_once(':')?;
                        k.trim()
                            .eq_ignore_ascii_case(name)
                            .then(|| v.trim().to_owned())
                    })
                };
                seen.lock()
                    .unwrap()
                    .push(header("cookie").unwrap_or_default());
                let key = header("sec-websocket-key").unwrap_or_default();
                let response = format!(
                    "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {}\r\n\r\n",
                    ws::accept_key(&key)
                );
                if stream.write_all(response.as_bytes()).await.is_err() {
                    return;
                }
                while let Ok(message) = ws::read_message(&mut stream).await {
                    let Message::Text(text) = message else {
                        return;
                    };
                    if let Some(reply) = socket.answer(&text)
                        && ws::write_frame(&mut stream, ws::OP_TEXT, reply.as_bytes(), false)
                            .await
                            .is_err()
                    {
                        return;
                    }
                }
            });
        }
    });
    (url, cookies)
}

fn claims(owner: Value) -> Value {
    json!({ "act": { "acc": { "owner": owner } } })
}

async fn mount_session(sidecar: &MockServer, owner: Value) {
    answer(
        sidecar,
        "/session",
        json!({ "sessionToken": "live-token", "claims": claims(owner) }),
    )
    .await;
}

async fn mount_export(sidecar: &MockServer, csv: &str) {
    Mock::given(method("POST"))
        .and(path("/transactions"))
        .respond_with(ResponseTemplate::new(200).set_body_raw(csv.to_owned(), "text/csv"))
        .mount(sidecar)
        .await;
}

const HEADER: &str = "datetime,date,account_type,category,type,asset_class,name,symbol,shares,price,amount,fee,tax,currency,original_amount,original_currency,fx_rate,description,transaction_id";

fn export(rows: &[&str]) -> String {
    let mut lines = vec![HEADER.to_owned()];
    lines.extend(rows.iter().map(|r| (*r).to_owned()));
    lines.join("\n")
}

async fn app(sidecar: &MockServer, socket: TrSocket) -> (TestApp, Arc<Mutex<Vec<String>>>) {
    let app = app_with_sidecar(URL, sidecar).await;
    let (url, cookies) = serve(socket).await;
    app.state.connectors.trade_republic.set_ws_url(url);
    (app, cookies)
}

async fn store_state(
    app: &TestApp,
    member_id: i64,
    plain: &str,
    active: bool,
    status: &str,
) -> i64 {
    let encrypted = app.state.crypto.encrypt(plain).unwrap();
    picsou::db::query_scalar(
        "INSERT INTO sidecar_session (bank, member_id, session_state, last_validated_at, is_active, sync_status) \
         VALUES ('TRADE_REPUBLIC', $1, $2, now(), $3, $4) RETURNING id",
    )
    .bind(member_id)
    .bind(encrypted)
    .bind(active)
    .bind(status)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

async fn sync(app: &TestApp, client: &Client) -> Value {
    let queued = client.post("/api/tr/sync").send().await;
    assert_eq!(queued.status, 200, "{}", queued.text());
    settle(app).await;
    client.get("/api/tr/status").send().await.json()
}

async fn account(
    app: &TestApp,
    member_id: i64,
    external_id: &str,
) -> Option<(i64, String, String, Decimal, Option<Decimal>)> {
    picsou::db::query_as(
        "SELECT id, name, type::text, current_balance, cash_balance FROM account \
         WHERE external_account_id = $1 AND member_id = $2 AND deleted_at IS NULL",
    )
    .bind(external_id)
    .bind(member_id)
    .fetch_optional(&app.state.db)
    .await
    .unwrap()
}

async fn holdings(
    app: &TestApp,
    account_id: i64,
) -> Vec<(
    String,
    Decimal,
    Option<Decimal>,
    Option<Decimal>,
    Option<Decimal>,
)> {
    picsou::db::query_as(
        "SELECT ticker, quantity, average_buy_in, current_price, provider_value_eur FROM account_holding \
         WHERE account_id = $1 ORDER BY ticker",
    )
    .bind(account_id)
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

fn multipart(csv: &str) -> (&'static str, String) {
    (
        "multipart/form-data; boundary=XyZ",
        format!(
            "--XyZ\r\nContent-Disposition: form-data; name=\"file\"; filename=\"export.csv\"\r\nContent-Type: text/csv\r\n\r\n{csv}\r\n--XyZ--\r\n"
        ),
    )
}

async fn upload(client: &Client, path: &str, csv: &str) -> picsou::testing::Response {
    let (content_type, body) = multipart(csv);
    client.post(path).body(content_type, body).send().await
}

fn securities_socket(portfolio: &str) -> TrSocket {
    TrSocket {
        portfolios: HashMap::from([("SEC1".to_owned(), portfolio.to_owned())]),
        cash: HashMap::from([(
            String::new(),
            r#"[{"accountNumber":"CASH1","currencyId":"EUR","amount":100}]"#.to_owned(),
        )]),
        ..TrSocket::default()
    }
}

fn default_owner() -> Value {
    json!({ "default": { "sec": ["SEC1"], "cash": ["CASH1"] } })
}

#[tokio::test]
async fn status_without_a_session_is_inactive_and_idle() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let response = client.get("/api/tr/status").send().await;

    assert_eq!(response.status, 200);
    assert_eq!(
        response.json(),
        json!({ "isActive": false, "syncStatus": "IDLE" })
    );
}

#[tokio::test]
async fn initiate_relays_the_flow_and_the_countdown() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_json(
            json!({ "phoneNumber": "+33612345678", "pin": "a long password!!" }),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "processId": "p-1", "flow": "confirmInApp", "countdownInSeconds": 120
        })))
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let response = client
        .post("/api/tr/auth/initiate")
        .json(&json!({ "phoneNumber": "+33612345678", "pin": "a long password!!" }))
        .send()
        .await;

    assert_eq!(response.status, 200);
    assert_eq!(
        response.json(),
        json!({ "processId": "p-1", "flow": "confirmInApp", "countdownInSeconds": 120 })
    );
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

#[tokio::test]
async fn initiate_maps_the_sidecar_codes() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/initiate", 401, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let response = client
        .post("/api/tr/auth/initiate")
        .json(&json!({ "phoneNumber": "+33612345678", "pin": "1234" }))
        .send()
        .await;

    assert_eq!(response.status, 422);
    assert_eq!(response.json()["code"], "INVALID_CREDENTIALS");
}

#[tokio::test]
async fn a_blank_process_id_is_a_format_change() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": " ", "flow": "otp" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let response = client
        .post("/api/tr/auth/initiate")
        .json(&json!({ "phoneNumber": "+33612345678", "pin": "1234" }))
        .send()
        .await;

    assert_eq!(response.status, 422);
    assert_eq!(response.json()["code"], "UPSTREAM_FORMAT_CHANGED");
}

#[tokio::test]
async fn reconnect_uses_the_stored_phone_of_a_deactivated_session_and_status_masks_it() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_json(
            json!({ "phoneNumber": "+33612345699", "pin": "4321" }),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "processId": "p-2", "flow": "authenticator", "countdownInSeconds": 30
        })))
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_state(
        &app,
        member_id,
        r#"{"refresh":"r","phone":" +33612345699 "}"#,
        false,
        "IDLE",
    )
    .await;

    let status = client.get("/api/tr/status").send().await.json();
    let response = client
        .post("/api/tr/auth/reconnect")
        .json(&json!({ "pin": "4321" }))
        .send()
        .await;

    assert_eq!(status["maskedPhone"], "+33 * ** ** ** 99");
    assert_eq!(response.status, 200, "{}", response.text());
    assert_eq!(response.json()["flow"], "authenticator");
}

#[tokio::test]
async fn reconnect_without_a_stored_phone_is_a_409() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_state(&app, member_id, r#"{"refresh":"r"}"#, true, "IDLE").await;

    let response = client
        .post("/api/tr/auth/reconnect")
        .json(&json!({ "pin": "1234" }))
        .send()
        .await;

    assert_eq!(response.status, 409);
    assert_eq!(response.json()["code"], "INVALID_SESSION_STATE");
    assert!(
        client
            .get("/api/tr/status")
            .send()
            .await
            .json()
            .get("maskedPhone")
            .is_none()
    );
}

#[tokio::test]
async fn validation_runs_before_the_auth_budget_which_initiate_and_reconnect_share() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    for _ in 0..5 {
        let invalid = client
            .post("/api/tr/auth/initiate")
            .json(&json!({ "phoneNumber": "", "pin": "" }))
            .send()
            .await;
        assert_eq!(invalid.status, 422);
        assert!(invalid.json()["errors"].is_object());
    }
    for _ in 0..2 {
        let counted = client
            .post("/api/tr/auth/initiate")
            .json(&json!({ "phoneNumber": "+33612345678", "pin": "1234" }))
            .send()
            .await;
        assert_eq!(counted.status, 422);
    }
    assert_eq!(
        client
            .post("/api/tr/auth/reconnect")
            .json(&json!({ "pin": "1234" }))
            .send()
            .await
            .status,
        409
    );

    let limited = client
        .post("/api/tr/auth/reconnect")
        .json(&json!({ "pin": "1234" }))
        .send()
        .await;

    assert_eq!(limited.status, 429);
    assert_eq!(
        limited.json()["detail"],
        "Too many authentication attempts. Please wait before trying again."
    );
}

#[tokio::test]
async fn complete_accepts_four_or_six_digits_or_no_code_only() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/complete", 410, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    for tan in [json!(null), json!("1234"), json!("123456")] {
        let response = client
            .post("/api/tr/auth/complete")
            .json(&json!({ "processId": "p-1", "tan": tan }))
            .send()
            .await;
        assert_eq!(response.status, 422);
        assert_eq!(response.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    }
    for tan in ["12345", "12a4", "1234567"] {
        let response = client
            .post("/api/tr/auth/complete")
            .json(&json!({ "processId": "p-1", "tan": tan }))
            .send()
            .await;
        assert_eq!(
            response.json()["errors"]["tan"],
            r#"must match "\d{4}|\d{6}""#
        );
    }
}

#[tokio::test]
async fn ten_syncs_are_permitted_then_the_eleventh_is_rate_limited() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    for _ in 0..10 {
        assert_eq!(client.post("/api/tr/sync").send().await.status, 422);
    }

    let limited = client.post("/api/tr/sync").send().await;

    assert_eq!(limited.status, 429);
    assert_eq!(
        limited.json()["detail"],
        "Too many Trade Republic synchronization requests. Please wait before retrying."
    );
}

#[tokio::test]
async fn complete_stores_the_session_lifts_tombstones_and_runs_the_first_import() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/complete"))
        .and(body_json(json!({ "processId": "p-1", "tan": null })))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "sessionState": "plain-state" })),
        )
        .expect(1)
        .mount(&sidecar)
        .await;
    mount_session(&sidecar, default_owner()).await;
    mount_export(
        &sidecar,
        &export(&["2025-12-01T10:00:00Z,2025-12-01,DEFAULT,TRADING,BUY,STOCK,Acme,ACM,2,100,-200.00,0,0,EUR,,,,,tx-1"]),
    )
    .await;
    let socket = securities_socket(
        r#"A {"categories":[{"categoryType":"stocksAndETFs","positions":[{"isin":"US0000000001","netSize":"2","averageBuyIn":"90"}]}]}"#,
    );
    let socket = TrSocket {
        prices: HashMap::from([("US0000000001.LSX".to_owned(), "100".to_owned())]),
        ..socket
    };
    let (app, cookies) = app(&sidecar, socket).await;
    let (client, member_id) = admin(&app).await;
    picsou::db::query(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, external_account_id, \
         is_manual, color, created_at, updated_at, deleted_at) VALUES ($1, 'TR Titres', 'COMPTE_TITRES', \
         'Trade Republic', 'EUR', 0, 'tr_securities', false, '#3b82f6', now(), now(), now())",
    )
    .bind(member_id)
    .execute(&app.state.db)
    .await
    .unwrap();

    let response = client
        .post("/api/tr/auth/complete")
        .json(&json!({ "processId": "p-1" }))
        .send()
        .await;
    settle(&app).await;

    assert_eq!(response.status, 200, "{}", response.text());
    assert_eq!(response.json()["isActive"], true);
    let status = client.get("/api/tr/status").send().await.json();
    assert_eq!(status["syncStatus"], "SUCCESS", "{status}");
    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert_eq!(
        app.state.crypto.decrypt(&session.session_state).unwrap(),
        "plain-state"
    );
    assert_eq!(
        cookies.lock().unwrap().as_slice(),
        ["tr_session=live-token"]
    );
    let (securities, name, _, balance, _) = account(&app, member_id, "tr_securities")
        .await
        .expect("restored");
    assert_eq!((name.as_str(), balance), ("TR Titres", d("200")));
    assert_eq!(
        holdings(&app, securities).await,
        vec![(
            "US0000000001".to_owned(),
            d("2"),
            Some(d("90")),
            Some(d("100")),
            Some(d("200"))
        )]
    );
    let (cash, _, kind, cash_balance, _) = account(&app, member_id, "tr_cash").await.unwrap();
    assert_eq!((kind.as_str(), cash_balance), ("CHECKING", d("100")));
    assert_eq!(
        count(
            &app,
            &format!("SELECT count(*) FROM \"transaction\" WHERE account_id = {cash}")
        )
        .await,
        1
    );
    let sec_amounts: Vec<Decimal> =
        picsou::db::query_scalar("SELECT amount FROM \"transaction\" WHERE account_id = $1")
            .bind(securities)
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(
        sec_amounts.into_iter().filter(|a| *a == d("200")).count(),
        1
    );
    assert_eq!(
        count(
            &app,
            &format!("SELECT count(*) FROM balance_snapshot WHERE account_id = {securities}")
        )
        .await,
        1
    );
}

#[tokio::test]
async fn a_disconnect_during_the_wait_stores_nothing() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/complete"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({ "sessionState": "plain-state" }))
                .set_delay(Duration::from_millis(700)),
        )
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let login = client
        .post("/api/tr/auth/complete")
        .json(&json!({ "processId": "p-1", "tan": "1234" }))
        .send();
    let disconnect = async {
        tokio::time::sleep(Duration::from_millis(200)).await;
        client.delete("/api/tr/session").send().await
    };
    let (login, disconnect) = tokio::join!(login, disconnect);

    assert_eq!(disconnect.status, 204);
    assert_eq!(login.status, 422);
    assert_eq!(login.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

#[tokio::test]
async fn a_rejected_second_factor_stores_nothing() {
    let sidecar = MockServer::start().await;
    fail(
        &sidecar,
        "/complete",
        422,
        json!({ "detail": "INVALID_OTP" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let response = client
        .post("/api/tr/auth/complete")
        .json(&json!({ "processId": "p-1", "tan": "1234" }))
        .send()
        .await;

    assert_eq!(response.json()["code"], "INVALID_OTP");
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

#[tokio::test]
async fn a_session_the_sidecar_reports_expired_is_deactivated_not_deleted() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/session", 401, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    let status = sync(&app, &client).await;

    assert_eq!(status["isActive"], false);
    assert_eq!(status["lastSyncError"], "SESSION_EXPIRED");
    assert!(session_of(&app, BANK, member_id).await.is_some());
}

#[tokio::test]
async fn a_transient_sidecar_failure_keeps_the_session_connected() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/session", 503, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    let status = sync(&app, &client).await;

    assert_eq!(status["isActive"], true);
    assert_eq!(status["lastSyncError"], "UPSTREAM_UNAVAILABLE");
}

#[tokio::test]
async fn a_token_trade_republic_refuses_fails_the_sync_but_keeps_the_session() {
    let sidecar = MockServer::start().await;
    mount_session(&sidecar, default_owner()).await;
    let socket = TrSocket {
        auth_error: true,
        ..TrSocket::default()
    };
    let (app, _) = app(&sidecar, socket).await;
    let (client, member_id) = admin(&app).await;
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    let status = sync(&app, &client).await;

    assert_eq!(
        (status["isActive"].clone(), status["lastSyncError"].clone()),
        (json!(true), json!("UPSTREAM_UNAVAILABLE"))
    );
    assert!(account(&app, member_id, "tr_cash").await.is_none());
}

#[tokio::test]
async fn sync_merges_duplicate_tickers_scales_bonds_splits_crypto_and_stores_the_pea_pocket() {
    let sidecar = MockServer::start().await;
    mount_session(
        &sidecar,
        json!({
            "default": { "sec": ["SEC1"], "cash": ["CASH1"] },
            "tax_wrapper_fr": { "sec": ["PEA1"], "cash": ["PCASH"] }
        }),
    )
    .await;
    fail(&sidecar, "/transactions", 503, json!({})).await;
    let socket = TrSocket {
        portfolios: HashMap::from([
            (
                "SEC1".to_owned(),
                r#"A {"categories":[
                    {"categoryType":"stocksAndETFs","positions":[
                        {"isin":"IE0000000001","netSize":"10","averageBuyIn":"10"},
                        {"isin":"IE0000000002","netSize":"30","averageBuyIn":"20"}]},
                    {"categoryType":"bonds","positions":[{"isin":"DE0000000003","netSize":"1000","averageBuyIn":"0.95"}]},
                    {"categoryType":"cryptos","positions":[{"isin":"XF000BTC0017","netSize":"0.5","averageBuyIn":"30000"}]}
                ]}"#
                    .replace('\n', ""),
            ),
            ("PEA1".to_owned(), r#"A {"categories":[]}"#.to_owned()),
        ]),
        instruments: HashMap::from([
            ("XF000BTC0017".to_owned(), r#"{"exchangeIds":["BHS"]}"#.to_owned()),
            ("DE0000000003".to_owned(), r#"{"exchangeIds":["LSX"],"bondInfo":{"currency":"EUR"}}"#.to_owned()),
        ]),
        prices: HashMap::from([
            ("IE0000000001.LSX".to_owned(), "12".to_owned()),
            ("DE0000000003.LSX".to_owned(), "98.25".to_owned()),
            ("XF000BTC0017.BHS".to_owned(), "60000".to_owned()),
        ]),
        cash: HashMap::from([
            (String::new(), r#"[{"amount":"55.5"}]"#.to_owned()),
            ("PCASH".to_owned(), r#"{"value":"250"}"#.to_owned()),
        ]),
        ..TrSocket::default()
    };
    let (app, _) = app(&sidecar, socket).await;
    let (client, member_id) = admin(&app).await;
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    let status = sync(&app, &client).await;

    assert_eq!(status["syncStatus"], "SUCCESS", "{status}");
    let (securities, _, _, balance, _) = account(&app, member_id, "tr_securities").await.unwrap();
    // 10 x 12 priced + 30 x 20 at cost + 1000 x 0.9825 bond.
    assert_eq!(balance, d("1702.50"));
    let lines = holdings(&app, securities).await;
    assert_eq!(lines.len(), 3, "{lines:?}");
    assert_eq!(
        lines[0],
        (
            "DE0000000003".to_owned(),
            d("1000"),
            Some(d("0.95")),
            Some(d("0.9825")),
            Some(d("982.50"))
        )
    );
    assert_eq!(
        lines[2],
        (
            "IE0000000002".to_owned(),
            d("30"),
            Some(d("20")),
            None,
            Some(d("600.00"))
        )
    );
    let isins: Vec<(String, Option<String>)> = picsou::db::query_as(
        "SELECT ticker, isin FROM account_holding WHERE account_id = $1 ORDER BY ticker",
    )
    .bind(securities)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        isins,
        vec![
            ("DE0000000003".into(), Some("DE0000000003".into())),
            ("IE0000000001".into(), Some("IE0000000001".into())),
            ("IE0000000002".into(), Some("IE0000000002".into())),
        ]
    );
    let (crypto, name, kind, crypto_balance, _) =
        account(&app, member_id, "tr_crypto").await.unwrap();
    assert_eq!(
        (name.as_str(), kind.as_str(), crypto_balance),
        ("TR Crypto", "CRYPTO", d("30000.00"))
    );
    assert_eq!(holdings(&app, crypto).await[0].3, Some(d("60000")));
    let crypto_isins: Vec<Option<String>> = picsou::db::query_scalar(
        "SELECT isin FROM account_holding WHERE account_id = $1 ORDER BY ticker",
    )
    .bind(crypto)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        crypto_isins,
        vec![None],
        "TR crypto pseudo-ISINs must never be stored on crypto holdings"
    );
    let (_, pea_name, _, pea_balance, pea_cash) = account(&app, member_id, "tr_pea").await.unwrap();
    assert_eq!(
        (pea_name.as_str(), pea_balance, pea_cash),
        ("TR PEA", d("250"), Some(d("250")))
    );
    assert_eq!(
        account(&app, member_id, "tr_cash").await.unwrap().3,
        d("55.5")
    );
}

#[tokio::test]
async fn duplicate_ticker_merge_keeps_the_first_valid_isin() {
    let sidecar = MockServer::start().await;
    let figi = MockServer::start().await;
    let yahoo = MockServer::start().await;
    mount_session(&sidecar, default_owner()).await;
    fail(&sidecar, "/transactions", 503, json!({})).await;
    Mock::given(method("POST"))
        .and(path("/v3/mapping"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([
            {"data": [{"ticker": "TTE", "exchCode": "FP", "name": "TOTALENERGIES SE"}]}
        ])))
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
    let socket = securities_socket(
        r#"A {"categories":[{"categoryType":"stocksAndETFs","positions":[
            {"isin":"FR0000120271","netSize":"2","averageBuyIn":"50"},
            {"isin":"TTE.PA","netSize":"3","averageBuyIn":"60"}
        ]}]}"#,
    );
    let app = TestApp::builder()
        .env(URL, sidecar.uri())
        .env("OPENFIGI_BASE_URL", figi.uri())
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .with_admin()
        .spawn()
        .await;
    let (url, _) = serve(socket).await;
    app.state.connectors.trade_republic.set_ws_url(url);
    let (client, member_id) = admin(&app).await;
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    let status = sync(&app, &client).await;

    assert_eq!(status["syncStatus"], "SUCCESS", "{status}");
    let (securities, ..) = account(&app, member_id, "tr_securities").await.unwrap();
    let rows: Vec<(String, Decimal, Option<String>)> = picsou::db::query_as(
        "SELECT ticker, quantity, isin FROM account_holding WHERE account_id = $1 ORDER BY ticker",
    )
    .bind(securities)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![("TTE.PA".to_owned(), d("5"), Some("FR0000120271".to_owned()))]
    );
}

#[tokio::test]
async fn an_errored_portfolio_frame_keeps_the_previous_holdings() {
    let sidecar = MockServer::start().await;
    mount_session(&sidecar, default_owner()).await;
    fail(&sidecar, "/transactions", 503, json!({})).await;
    let (app, _) = app(
        &sidecar,
        securities_socket(r#"E {"errors":[{"errorCode":"INTERNAL"}]}"#),
    )
    .await;
    let (client, member_id) = admin(&app).await;
    let account_id: i64 = picsou::db::query_scalar(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, external_account_id, \
         is_manual, color, created_at, updated_at) VALUES ($1, 'TR Titres', 'COMPTE_TITRES', 'Trade Republic', \
         'EUR', 500, 'tr_securities', false, '#3b82f6', now(), now()) RETURNING id",
    )
    .bind(member_id)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    picsou::db::query("INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) VALUES ($1, 'KEEP', 5, now(), now())")
        .bind(account_id)
        .execute(&app.state.db)
        .await
        .unwrap();
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    let status = sync(&app, &client).await;

    assert_eq!(status["syncStatus"], "SUCCESS", "{status}");
    assert_eq!(
        account(&app, member_id, "tr_securities").await.unwrap().3,
        d("500")
    );
    assert_eq!(holdings(&app, account_id).await.len(), 1);
}

#[tokio::test]
async fn an_emptied_portfolio_clears_the_holdings_and_an_empty_titres_is_never_created() {
    let sidecar = MockServer::start().await;
    mount_session(&sidecar, default_owner()).await;
    fail(&sidecar, "/transactions", 503, json!({})).await;
    let (app, _) = app(&sidecar, securities_socket(r#"A {"categories":[]}"#)).await;
    let (client, member_id) = admin(&app).await;
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    sync(&app, &client).await;

    assert!(account(&app, member_id, "tr_securities").await.is_none());
    assert!(account(&app, member_id, "tr_cash").await.is_some());
}

#[tokio::test]
async fn the_export_window_starts_three_years_back_then_two_weeks_before_the_newest_entry() {
    let sidecar = MockServer::start().await;
    mount_session(&sidecar, default_owner()).await;
    let today = picsou::testing::epoch().date_naive();
    Mock::given(method("POST"))
        .and(path("/transactions"))
        .and(body_json(json!({ "sessionState": "plain-state", "from": "2023-01-15", "to": today.to_string() })))
        .respond_with(ResponseTemplate::new(200).set_body_raw(
            export(&["2025-12-20T10:00:00Z,2025-12-20,DEFAULT,CASH,INTEREST,,,,,,1.10,0,0,EUR,,,,Interest,tx-9"]),
            "text/csv",
        ))
        .up_to_n_times(1)
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/transactions"))
        .and(body_json(
            json!({ "sessionState": "plain-state", "from": "2025-12-06", "to": today.to_string() }),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_raw(export(&[]), "text/csv"))
        .expect(1)
        .mount(&sidecar)
        .await;
    let (app, _) = app(&sidecar, securities_socket(r#"A {"categories":[]}"#)).await;
    let (client, member_id) = admin(&app).await;
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    sync(&app, &client).await;
    let second = sync(&app, &client).await;

    assert_eq!(second["syncStatus"], "SUCCESS");
    let (cash, ..) = account(&app, member_id, "tr_cash").await.unwrap();
    assert_eq!(
        count(
            &app,
            &format!("SELECT count(*) FROM \"transaction\" WHERE account_id = {cash}")
        )
        .await,
        1
    );
}

#[tokio::test]
async fn an_expired_session_on_the_export_still_fails_the_sync() {
    let sidecar = MockServer::start().await;
    mount_session(&sidecar, default_owner()).await;
    fail(&sidecar, "/transactions", 401, json!({})).await;
    let (app, _) = app(&sidecar, securities_socket(r#"A {"categories":[]}"#)).await;
    let (client, member_id) = admin(&app).await;
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    let status = sync(&app, &client).await;

    assert_eq!(status["lastSyncError"], "SESSION_EXPIRED");
    assert!(account(&app, member_id, "tr_cash").await.is_none());
}

#[tokio::test]
async fn the_accounts_csv_creates_then_updates_by_name() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let first = upload(
        &client,
        "/api/tr/import",
        "name,type,balance\r\nPEA TR,PEA,15000.50\r\nOdd,WHATEVER,3\r\nbroken\r\nBad,PEA,x",
    )
    .await;
    let second = upload(&client, "/api/tr/import", "PEA TR,PEA,16000").await;

    assert_eq!(first.status, 200, "{}", first.text());
    let body = first.json();
    assert_eq!(body.as_array().unwrap().len(), 2);
    assert_eq!(body[0]["name"], "PEA TR");
    assert_eq!(body[0]["provider"], "Trade Republic");
    assert_eq!(body[0]["color"], "#10b981");
    assert_eq!(body[1]["type"], "OTHER");
    assert_eq!(second.json()[0]["id"], body[0]["id"]);
    assert_eq!(
        account(&app, member_id, "tr_csv_pea_tr").await.unwrap().3,
        d("16000")
    );
}

#[tokio::test]
async fn an_upload_without_the_file_part_is_a_400() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let response = client
        .post("/api/tr/import")
        .body(
            "multipart/form-data; boundary=XyZ",
            "--XyZ\r\nContent-Disposition: form-data; name=\"other\"\r\n\r\nx\r\n--XyZ--\r\n",
        )
        .send()
        .await;

    assert_eq!(response.status, 400);
    assert_eq!(
        response.json()["detail"],
        "Required part 'file' is not present."
    );
}

#[tokio::test]
async fn the_transactions_csv_books_both_legs_once_and_only_for_the_owner() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let (other, _) = member(&app, "dominique").await;
    let imported = upload(
        &client,
        "/api/tr/import",
        "Cash TR,CHECKING,10\nPEA TR,PEA,100\nCTO TR,COMPTE_TITRES,50",
    )
    .await
    .json();
    let cash = imported[0]["id"].as_i64().unwrap();
    let pea = imported[1]["id"].as_i64().unwrap();
    let csv = export(&[
        r#"2024-03-01T10:00:00Z,2024-03-01,PEA,TRADING,BUY,ETF,World,IWDA,2,100,"-200,50",0,0,EUR,,,,,tx-1"#,
        "2024-03-02T10:00:00Z,2024-03-02,DEFAULT,CASH,INTEREST,,,,,,1.10,0,0,EUR,,,,Interest,tx-2",
        "2024-03-03T10:00:00Z,2024-03-03,DEFAULT,CASH,TRANSFER_IN,,,,,,100,0,0,EUR,,,,Deposit,tx-3",
        "2024-03-04T10:00:00Z,2024-03-04,DEFAULT,CASH,CARD_PAYMENT,,,,,,-5,0,0,USD,,,,Shop,tx-4",
    ]);
    let path = format!("/api/tr/accounts/{cash}/transactions/import-csv");

    let first = upload(&client, &path, &csv).await;
    let again = upload(&client, &path, &csv).await;
    let spoofed = upload(&other, &path, &csv).await;

    assert_eq!(
        first.json(),
        json!({ "inserted": 3, "skipped": 0, "nonEur": 1 })
    );
    assert_eq!(
        again.json(),
        json!({ "inserted": 0, "skipped": 2, "nonEur": 1 })
    );
    assert_eq!(spoofed.status, 404);
    let (cash_amount, cash_kind, cash_category): (Decimal, String, String) = picsou::db::query_as(
        "SELECT t.amount, t.tx_type::text, c.kind::text FROM \"transaction\" t JOIN category c ON c.id = t.category_id \
         WHERE t.external_id = 'tx-1_cash'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        (cash_amount, cash_kind.as_str(), cash_category.as_str()),
        (d("-200.50"), "WITHDRAWAL", "EXPENSE")
    );
    let (inv_account, inv_amount, inv_kind, description): (i64, Decimal, String, String) = picsou::db::query_as(
        "SELECT account_id, amount, tx_type::text, description FROM \"transaction\" WHERE external_id = 'tx-1_inv'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        (
            inv_account,
            inv_amount,
            inv_kind.as_str(),
            description.as_str()
        ),
        (pea, d("200.50"), "DEPOSIT", "BUY World")
    );
    assert_eq!(
        count(&app, &format!("SELECT count(*) FROM category WHERE slug = 'investissement-titres' AND member_id = {member_id}")).await,
        1
    );
    assert_eq!(count(&app, "SELECT count(*) FROM \"transaction\" WHERE external_id = 'tx-2_cash' AND category_id IS NULL").await, 1);
}

#[tokio::test]
async fn multiple_tax_wrapper_fr_entries_consolidate_into_one_pea_account() {
    let sidecar = MockServer::start().await;
    mount_session(
        &sidecar,
        json!({
            "default": { "sec": ["SEC1"], "cash": ["CASH1"] },
            "tax_wrapper_fr": {
                "sec": ["PEA1", "PEA2", "PEA3"],
                "cash": ["PCASH1", "PCASH2", "PCASH3"]
            }
        }),
    )
    .await;
    mount_export(
        &sidecar,
        &export(&[
            "2025-11-10T10:00:00Z,2025-11-10,PEA,TRADING,BUY,ETF,World ETF,CW8,2,500,-1000.00,0,0,EUR,,,,,pea-tx-1",
            "2025-12-10T10:00:00Z,2025-12-10,PEA,TRADING,BUY,ETF,World ETF,CW8,1,520,-520.00,0,0,EUR,,,,,pea-tx-2",
        ]),
    )
    .await;
    let socket = TrSocket {
        portfolios: HashMap::from([
            (
                "SEC1".to_owned(),
                r#"A {"categories":[{"categoryType":"stocksAndETFs","positions":[{"isin":"US0000000001","netSize":"1","averageBuyIn":"100"}]}]}"#
                    .to_owned(),
            ),
            ("PEA1".to_owned(), r#"A {"categories":[]}"#.to_owned()),
            ("PEA2".to_owned(), r#"A {"categories":[]}"#.to_owned()),
            (
                "PEA3".to_owned(),
                r#"A {"categories":[{"categoryType":"stocksAndETFs","positions":[{"isin":"LU0000000001","netSize":"3","averageBuyIn":"506.67"}]}]}"#
                    .to_owned(),
            ),
        ]),
        prices: HashMap::from([
            ("US0000000001.LSX".to_owned(), "110".to_owned()),
            ("LU0000000001.LSX".to_owned(), "550".to_owned()),
        ]),
        cash: HashMap::from([
            (
                String::new(),
                r#"[{"accountNumber":"CASH1","currencyId":"EUR","amount":42.5}]"#.to_owned(),
            ),
            (
                "PCASH1".to_owned(),
                r#"[{"accountNumber":"PCASH1","currencyId":"EUR","amount":0}]"#.to_owned(),
            ),
            (
                "PCASH2".to_owned(),
                r#"[{"accountNumber":"PCASH2","currencyId":"EUR","amount":0}]"#.to_owned(),
            ),
            (
                "PCASH3".to_owned(),
                r#"[{"accountNumber":"PCASH3","currencyId":"EUR","amount":80}]"#.to_owned(),
            ),
        ]),
        ..TrSocket::default()
    };
    let (app, _) = app(&sidecar, socket).await;
    let (client, member_id) = admin(&app).await;
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    let status = sync(&app, &client).await;

    assert_eq!(status["syncStatus"], "SUCCESS", "{status}");
    assert!(account(&app, member_id, "tr_pea_2").await.is_none());
    assert!(account(&app, member_id, "tr_pea_3").await.is_none());
    let (pea_id, pea_name, pea_type, pea_balance, pea_cash) = account(&app, member_id, "tr_pea")
        .await
        .expect("single tr_pea");
    // 3 * 550 (securities) + 80 (PEA cash pocket) = 1730.
    assert_eq!(
        (pea_name.as_str(), pea_type.as_str(), pea_balance, pea_cash),
        ("TR PEA", "PEA", d("1730"), Some(d("80")))
    );
    assert_eq!(
        holdings(&app, pea_id).await,
        vec![(
            "LU0000000001".to_owned(),
            d("3"),
            Some(d("506.67")),
            Some(d("550")),
            Some(d("1650"))
        )]
    );
    assert_eq!(
        count(
            &app,
            &format!("SELECT count(*) FROM \"transaction\" WHERE account_id = {pea_id}")
        )
        .await,
        2
    );
    // TR Titres and TR Cash remain intact.
    assert_eq!(
        account(&app, member_id, "tr_securities").await.unwrap().3,
        d("110")
    );
    assert_eq!(
        account(&app, member_id, "tr_cash").await.unwrap().3,
        d("42.5")
    );
}

#[tokio::test]
async fn tax_wrapper_fr_with_three_sec_and_one_cash_merges_positions_and_cash() {
    let sidecar = MockServer::start().await;
    mount_session(
        &sidecar,
        json!({
            "default": { "sec": ["SEC1"], "cash": ["CASH1"] },
            "tax_wrapper_fr": {
                "sec": ["PEA1", "PEA2", "PEA3"],
                "cash": ["PCASH1"]
            }
        }),
    )
    .await;
    fail(&sidecar, "/transactions", 503, json!({})).await;
    let socket = TrSocket {
        portfolios: HashMap::from([
            ("SEC1".to_owned(), r#"A {"categories":[]}"#.to_owned()),
            ("PEA1".to_owned(), r#"A {"categories":[]}"#.to_owned()),
            ("PEA2".to_owned(), r#"A {"categories":[]}"#.to_owned()),
            (
                "PEA3".to_owned(),
                r#"A {"categories":[{"categoryType":"stocksAndETFs","positions":[{"isin":"LU0000000001","netSize":"2","averageBuyIn":"100"}]}]}"#
                    .to_owned(),
            ),
        ]),
        prices: HashMap::from([("LU0000000001.LSX".to_owned(), "120".to_owned())]),
        cash: HashMap::from([
            (
                String::new(),
                r#"[{"accountNumber":"CASH1","currencyId":"EUR","amount":10}]"#.to_owned(),
            ),
            (
                "PCASH1".to_owned(),
                r#"[{"accountNumber":"PCASH1","currencyId":"EUR","amount":75}]"#.to_owned(),
            ),
        ]),
        ..TrSocket::default()
    };
    let (app, _) = app(&sidecar, socket).await;
    let (client, member_id) = admin(&app).await;
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    let status = sync(&app, &client).await;

    assert_eq!(status["syncStatus"], "SUCCESS", "{status}");
    assert!(account(&app, member_id, "tr_pea_2").await.is_none());
    assert!(account(&app, member_id, "tr_pea_3").await.is_none());
    let (pea_id, _, _, pea_balance, pea_cash) = account(&app, member_id, "tr_pea").await.unwrap();
    // 2 * 120 + 75 = 315.
    assert_eq!((pea_balance, pea_cash), (d("315"), Some(d("75"))));
    assert_eq!(holdings(&app, pea_id).await.len(), 1);
}

#[tokio::test]
async fn tax_wrapper_fr_errored_active_sub_account_keeps_previous_holdings() {
    let sidecar = MockServer::start().await;
    mount_session(
        &sidecar,
        json!({
            "default": { "sec": ["SEC1"], "cash": ["CASH1"] },
            "tax_wrapper_fr": {
                "sec": ["PEA1", "PEA2", "PEA3"],
                "cash": ["PCASH1", "PCASH2", "PCASH3"]
            }
        }),
    )
    .await;
    fail(&sidecar, "/transactions", 503, json!({})).await;
    let socket = TrSocket {
        portfolios: HashMap::from([
            ("SEC1".to_owned(), r#"A {"categories":[]}"#.to_owned()),
            ("PEA1".to_owned(), r#"A {"categories":[]}"#.to_owned()),
            ("PEA2".to_owned(), r#"A {"categories":[]}"#.to_owned()),
            (
                "PEA3".to_owned(),
                r#"E {"errors":[{"errorCode":"INTERNAL"}]}"#.to_owned(),
            ),
        ]),
        cash: HashMap::from([
            (String::new(), r#"[{"amount":10}]"#.to_owned()),
            ("PCASH1".to_owned(), r#"[{"amount":0}]"#.to_owned()),
            ("PCASH2".to_owned(), r#"[{"amount":0}]"#.to_owned()),
            ("PCASH3".to_owned(), r#"[{"amount":80}]"#.to_owned()),
        ]),
        ..TrSocket::default()
    };
    let (app, _) = app(&sidecar, socket).await;
    let (client, member_id) = admin(&app).await;
    let pea_id: i64 = picsou::db::query_scalar(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, external_account_id, \
         is_manual, color, created_at, updated_at) VALUES ($1, 'TR PEA', 'PEA', 'Trade Republic', \
         'EUR', 1500, 'tr_pea', false, '#10b981', now(), now()) RETURNING id",
    )
    .bind(member_id)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    picsou::db::query(
        "INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) VALUES ($1, 'KEEP', 3, now(), now())",
    )
    .bind(pea_id)
    .execute(&app.state.db)
    .await
    .unwrap();
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    let status = sync(&app, &client).await;

    assert_eq!(status["syncStatus"], "SUCCESS", "{status}");
    assert_eq!(
        account(&app, member_id, "tr_pea").await.unwrap().3,
        d("1500")
    );
    assert_eq!(holdings(&app, pea_id).await.len(), 1);
}

#[tokio::test]
async fn tax_wrapper_fr_closed_sub_account_error_does_not_block_active_positions_or_cash() {
    let sidecar = MockServer::start().await;
    mount_session(
        &sidecar,
        json!({
            "default": { "sec": ["SEC1"], "cash": ["CASH1"] },
            "tax_wrapper_fr": {
                "sec": ["PEA1", "PEA3"],
                "cash": ["PCASH1", "PCASH3"]
            }
        }),
    )
    .await;
    fail(&sidecar, "/transactions", 503, json!({})).await;
    let socket = TrSocket {
        portfolios: HashMap::from([
            ("SEC1".to_owned(), r#"A {"categories":[]}"#.to_owned()),
            (
                "PEA1".to_owned(),
                r#"E {"errors":[{"errorCode":"FORBIDDEN"}]}"#.to_owned(),
            ),
            (
                "PEA3".to_owned(),
                r#"A {"categories":[{"categoryType":"stocksAndETFs","positions":[{"isin":"LU0000000001","netSize":"4","averageBuyIn":"100"}]}]}"#
                    .to_owned(),
            ),
        ]),
        prices: HashMap::from([("LU0000000001.LSX".to_owned(), "125".to_owned())]),
        cash: HashMap::from([
            (String::new(), r#"[{"amount":10}]"#.to_owned()),
            (
                "PCASH1".to_owned(),
                r#"E {"errors":[{"errorCode":"FORBIDDEN"}]}"#.to_owned(),
            ),
            (
                "PCASH3".to_owned(),
                r#"[{"accountNumber":"PCASH3","currencyId":"EUR","amount":50}]"#.to_owned(),
            ),
        ]),
        ..TrSocket::default()
    };
    let (app, _) = app(&sidecar, socket).await;
    let (client, member_id) = admin(&app).await;
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    let status = sync(&app, &client).await;

    assert_eq!(status["syncStatus"], "SUCCESS", "{status}");
    let (pea_id, _, _, pea_balance, pea_cash) = account(&app, member_id, "tr_pea").await.unwrap();
    // 4 * 125 + 50 = 550.
    assert_eq!((pea_balance, pea_cash), (d("550"), Some(d("50"))));
    assert_eq!(holdings(&app, pea_id).await.len(), 1);
}

#[tokio::test]
async fn tax_wrapper_fr_all_empty_sub_accounts_clear_stale_holdings() {
    let sidecar = MockServer::start().await;
    mount_session(
        &sidecar,
        json!({
            "default": { "sec": ["SEC1"], "cash": ["CASH1"] },
            "tax_wrapper_fr": {
                "sec": ["PEA1", "PEA2", "PEA3"],
                "cash": ["PCASH1", "PCASH2", "PCASH3"]
            }
        }),
    )
    .await;
    fail(&sidecar, "/transactions", 503, json!({})).await;
    let socket = TrSocket {
        portfolios: HashMap::from([
            ("SEC1".to_owned(), r#"A {"categories":[]}"#.to_owned()),
            ("PEA1".to_owned(), r#"A {"categories":[]}"#.to_owned()),
            ("PEA2".to_owned(), r#"A {"categories":[]}"#.to_owned()),
            ("PEA3".to_owned(), r#"A {"categories":[]}"#.to_owned()),
        ]),
        cash: HashMap::from([
            (String::new(), r#"[{"amount":10}]"#.to_owned()),
            ("PCASH1".to_owned(), r#"[{"amount":0}]"#.to_owned()),
            ("PCASH2".to_owned(), r#"[{"amount":0}]"#.to_owned()),
            ("PCASH3".to_owned(), r#"[{"amount":25}]"#.to_owned()),
        ]),
        ..TrSocket::default()
    };
    let (app, _) = app(&sidecar, socket).await;
    let (client, member_id) = admin(&app).await;
    let pea_id: i64 = picsou::db::query_scalar(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, external_account_id, \
         is_manual, color, created_at, updated_at) VALUES ($1, 'TR PEA', 'PEA', 'Trade Republic', \
         'EUR', 1500, 'tr_pea', false, '#10b981', now(), now()) RETURNING id",
    )
    .bind(member_id)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    picsou::db::query(
        "INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) VALUES ($1, 'SOLD', 3, now(), now())",
    )
    .bind(pea_id)
    .execute(&app.state.db)
    .await
    .unwrap();
    store_state(&app, member_id, "plain-state", true, "IDLE").await;

    let status = sync(&app, &client).await;

    assert_eq!(status["syncStatus"], "SUCCESS", "{status}");
    let (_, _, _, pea_balance, pea_cash) = account(&app, member_id, "tr_pea").await.unwrap();
    assert_eq!((pea_balance, pea_cash), (d("25"), Some(d("25"))));
    assert!(holdings(&app, pea_id).await.is_empty());
}
