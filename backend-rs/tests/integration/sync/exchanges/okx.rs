//! OKX through the exchange HTTP routes and the database, with synthetic upstream replies.

use std::io::{self, Write};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use picsou::testing::Response;

use super::*;

const PASSPHRASE: &str = "synthetic-passphrase";
const TRADING: &str = "/api/v5/account/balance";
const FUNDING: &str = "/api/v5/asset/balances";
const SAVINGS: &str = "/api/v5/finance/savings/balance";
const ORDERS: &str = "/api/v5/finance/staking-defi/orders-active";
const ETH: &str = "/api/v5/finance/staking-defi/eth/balance";
const SOL: &str = "/api/v5/finance/staking-defi/sol/balance";
const PATHS: [&str; 6] = [TRADING, FUNDING, SAVINGS, ORDERS, ETH, SOL];
const FIXTURES: [&str; 6] = [
    include_str!("../fixtures/okx/trading.json"),
    include_str!("../fixtures/okx/funding.json"),
    include_str!("../fixtures/okx/savings.json"),
    include_str!("../fixtures/okx/orders.json"),
    include_str!("../fixtures/okx/eth-staking.json"),
    include_str!("../fixtures/okx/sol-staking.json"),
];
const EMPTY: &str = r#"{"code":"0","data":[]}"#;

fn credentials(region: &str) -> Value {
    json!({"type": "OKX", "apiKey": KEY, "apiSecret": SECRET, "apiPassphrase": PASSPHRASE, "region": region})
}

async fn app_for_okx(server: &MockServer) -> TestApp {
    TestApp::builder()
        .env("OKX_EEA_BASE_URL", server.uri())
        .with_admin()
        .spawn()
        .await
}

async fn mount_balances(server: &MockServer) {
    mount_replies(server, FIXTURES, None).await;
}

async fn mount_replies(
    server: &MockServer,
    fixtures: [&str; 6],
    replacement: Option<(&str, ResponseTemplate)>,
) {
    server.reset().await;
    for (endpoint, body) in PATHS.into_iter().zip(fixtures) {
        let reply = match &replacement {
            Some((path, reply)) if *path == endpoint => reply.clone(),
            _ => ResponseTemplate::new(200).set_body_string(body),
        };
        Mock::given(method("GET"))
            .and(path(endpoint))
            .respond_with(reply)
            .mount(server)
            .await;
    }
}

async fn session_id(app: &TestApp) -> i64 {
    picsou::db::query_scalar("SELECT id FROM crypto_exchange_session WHERE exchange_type = 'OKX'")
        .fetch_one(&app.state.db)
        .await
        .unwrap()
}

async fn sync_okx(client: &Client, id: i64) -> Response {
    client.post(&format!("{EXCHANGE}/{id}/sync")).send().await
}

async fn assert_no_okx_writes(app: &TestApp) {
    assert_eq!(session_count(app).await, 0);
    let count: i64 = picsou::db::query_scalar(
        "SELECT count(*) FROM account WHERE external_account_id = 'crypto_exchange_okx'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(count, 0);
}

fn assert_redacted(text: &str) {
    for credential in [KEY, SECRET, PASSPHRASE] {
        assert!(!text.contains(credential), "a credential was exposed");
    }
}

fn assert_problem(response: &Response, code: Option<&str>, diagnostic: &str) {
    assert_eq!(response.status, 422, "{}", response.text());
    assert_eq!(response.json()["code"].as_str(), code);
    assert!(
        detail(response).contains(diagnostic),
        "expected {diagnostic:?} in {}",
        response.text()
    );
    assert_redacted(&response.text());
}

#[tokio::test]
async fn signed_probe_and_sync_route_to_each_region_and_save_encrypted_credentials() {
    let global = MockServer::start().await;
    let eea = MockServer::start().await;
    let us = MockServer::start().await;
    for server in [&global, &eea, &us] {
        mount_balances(server).await;
    }
    let app = TestApp::builder()
        .env("OKX_GLOBAL_BASE_URL", global.uri())
        .env("OKX_EEA_BASE_URL", eea.uri())
        .env("OKX_US_BASE_URL", us.uri())
        .with_admin()
        .spawn()
        .await;
    for ticker in ["COIN", "ETH", "SOL"] {
        price(&app, ticker, "10").await;
    }
    let client = app.login_admin().await;
    let regions = [
        (" global ", "GLOBAL", &global),
        ("eea", "EEA", &eea),
        ("US", "US", &us),
    ];
    let signatures = [
        "NQtqjC6/irR9Mkzqxo8t3CqPfnosLL2jW0MiuYI8mdM=",
        "o871SHB1LDcxnq9l1f/BIMs6b0BW87eDElHAJ1e5QVs=",
        "/6Z4rwLZgOMb1rCbNBjj7diP49f3cN9Z+Yhthcf3M28=",
        "Dwbm09gVHVy9bgf7X0MMqjsNbvCGGwE89fz2a7bU0+c=",
        "Dx1n/HGh3OahOuDTOcSpvE/qMPX0tOT0qD0ItEGh4zA=",
        "KTLa32IMJd1tKTg8SHt32fyctzqsNXo54pE97H5ND70=",
    ];
    let mut previous = None;
    for (supplied_region, region, server) in regions {
        assert!(server.received_requests().await.unwrap().is_empty());
        let mut body = credentials(supplied_region);
        let passphrase = format!("{PASSPHRASE}-{region}");
        body["apiKey"] = json!(format!(" {KEY} "));
        body["apiSecret"] = json!(format!(" {SECRET} "));
        body["apiPassphrase"] = json!(passphrase);
        let response = add(&client, body).await;
        assert_eq!(response.status, 200, "{}", response.text());
        assert_redacted(&response.text());
        let account = response.json();
        assert_eq!(account["provider"], "OKX");
        assert_eq!(account["name"], "OKX Crypto");
        assert_eq!(account["currentBalance"].to_string(), "400.00");
        let account_id = account["id"].as_i64().unwrap();
        let id = session_id(&app).await;
        if let Some(previous) = previous {
            assert_eq!((id, account_id), previous);
        }
        previous = Some((id, account_id));
        assert_eq!(session_count(&app).await, 1);

        let row: (String, String, String, String) = picsou::db::query_as(
            "SELECT api_key, api_secret, api_passphrase, region FROM crypto_exchange_session WHERE id = $1"
        ).bind(id).fetch_one(&app.state.db).await.unwrap();
        assert_eq!(row.3, region);
        for (ciphertext, plaintext) in [(row.0, KEY), (row.1, SECRET), (row.2, passphrase.as_str())]
        {
            assert_ne!(ciphertext, plaintext);
            assert_eq!(app.state.crypto.decrypt(&ciphertext).unwrap(), plaintext);
        }

        let status = client.get(&format!("{EXCHANGE}/status")).send().await;
        assert_redacted(&status.text());
        assert_eq!(status.json()[0]["exchangeType"], "OKX");
        assert_eq!(status.json()[0]["region"], region);
        assert_eq!(status.json()[0]["status"], "CONNECTED");
        assert_eq!(
            server.received_requests().await.unwrap().len(),
            12,
            "probe and first sync"
        );
        assert_eq!(sync_okx(&client, id).await.status, 200);
        let requests = server.received_requests().await.unwrap();
        assert_eq!(requests.len(), 18, "explicit sync routes by stored region");
        for (index, request) in requests.iter().enumerate() {
            let index = index % PATHS.len();
            assert_eq!(request.method, "GET");
            assert_eq!(request.url.path(), PATHS[index]);
            assert_eq!(request.url.query(), None);
            assert!(request.body.is_empty());
            for (name, expected) in [
                ("ok-access-key", KEY),
                ("ok-access-sign", signatures[index]),
                ("ok-access-timestamp", "2026-01-15T10:00:00.000Z"),
                ("ok-access-passphrase", passphrase.as_str()),
                ("user-agent", "Picsou"),
                ("accept", "application/json"),
            ] {
                assert_eq!(request.headers.get_all(name).iter().count(), 1, "{name}");
                assert_eq!(request.headers[name], expected, "{name}");
            }
            for forbidden in ["authorization", "x-mbx-apikey", "api-key"] {
                assert!(!request.headers.contains_key(forbidden));
            }
        }
    }
    let (_, account_id) = previous.unwrap();
    assert_eq!(
        holdings(&app, account_id).await,
        [
            ("COIN".into(), d("27")),
            ("ETH".into(), d("6")),
            ("SOL".into(), d("7"))
        ]
    );
    let response = client
        .get(&format!("/api/accounts/{account_id}/positions"))
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    let mut actual: Vec<_> = response
        .json()
        .as_array()
        .unwrap()
        .iter()
        .map(|line| {
            assert!(line.get("principal").is_none() && line.get("interest").is_none());
            (
                line["product"].as_str().unwrap().to_owned(),
                line["ticker"].as_str().unwrap().to_owned(),
                line["quantity"].to_string().parse::<Decimal>().unwrap(),
            )
        })
        .collect();
    actual.sort();
    assert_eq!(
        actual,
        [
            ("LENDING", "COIN", 9),
            ("SPOT", "COIN", 15),
            ("STAKING", "COIN", 3),
            ("STAKING", "ETH", 6),
            ("STAKING", "SOL", 7),
        ]
        .map(|(product, ticker, quantity)| (
            product.into(),
            ticker.into(),
            Decimal::from(quantity)
        ))
    );
}

fn rows(data: Value) -> String {
    json!({"code":"0","data":data}).to_string()
}

fn balance_rows(endpoint: &str, rows_json: Value) -> String {
    rows(match endpoint {
        TRADING => json!([{"totalEq":"0","details":rows_json}]),
        ORDERS => json!([{"ordId":"1","investData":rows_json}]),
        _ => rows_json,
    })
}

#[derive(Clone, Default)]
struct LogBuffer(Arc<Mutex<Vec<u8>>>);

impl Write for LogBuffer {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.0.lock().unwrap().write(bytes)
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[tokio::test]
async fn add_and_sync_keep_okx_diagnostics_and_codes_and_redact_credentials() {
    let logs = LogBuffer::default();
    let writer = logs.clone();
    let subscriber = tracing_subscriber::fmt()
        .with_ansi(false)
        .with_writer(move || writer.clone())
        .finish();
    let _capture = tracing::subscriber::set_default(subscriber);
    let server = MockServer::start().await;
    mount_balances(&server).await;
    let app = app_for_okx(&server).await;
    let client = app.login_admin().await;

    let mut cases = Vec::new();
    for (status, code, diagnostic) in [
        (400, "50100", "API key is frozen"),
        (200, "50102", "Check the server clock"),
        (401, "50105", "API passphrase is incorrect"),
        (401, "50110", "not on the API key's allowlist"),
        (200, "50111", "rejected the API key"),
        (200, "50113", "rejected the request signature"),
        (200, "50011", "rate-limited the sync"),
        (200, "51024", "check the account's status and region on OKX"),
        (200, "50119", "Choose the region where the key was created"),
        (401, "50119", "Choose the region where the key was created"),
        (429, KEY, "HTTP 429"),
    ] {
        let stable_code = (code == "50119").then_some("OKX_REGION_MISMATCH");
        let body = json!({"code":code,"data":[],"msg":format!("{KEY} {SECRET} {PASSPHRASE}")});
        cases.push((TRADING, status, body.to_string(), stable_code, diagnostic));
    }
    cases.push((
        TRADING,
        200,
        balance_rows(TRADING, json!([{"ccy":"COIN","eq":"-2"}])),
        Some("OKX_NEGATIVE_BALANCE"),
        "Repay the borrowed balance on OKX",
    ));
    for receipt in ["BETH", "OKSOL"] {
        for earn in [SAVINGS, ORDERS] {
            cases.push((
                earn,
                200,
                balance_rows(earn, json!([{"ccy":receipt,"amt":"1"}])),
                Some("OKX_STAKING_RECEIPT_IN_EARN"),
                "to your funding account",
            ));
        }
    }

    for (endpoint, status, body, code, diagnostic) in &cases {
        mount_replies(
            &server,
            FIXTURES,
            Some((
                endpoint,
                ResponseTemplate::new(*status).set_body_string(body),
            )),
        )
        .await;
        let response = add(&client, credentials("EEA")).await;
        assert_problem(&response, *code, diagnostic);
        assert_no_okx_writes(&app).await;
    }
    for (field, value, diagnostic) in [
        ("apiPassphrase", json!(null), "requires an API passphrase"),
        ("apiSecret", json!(null), "requires an API secret"),
        ("region", json!(null), "requires a region"),
        ("region", json!(KEY), "Allowed regions: GLOBAL, EEA, US"),
        (
            "apiPassphrase",
            json!(PASSPHRASE.repeat(14)),
            "Validation failed",
        ),
        ("apiPassphrase", json!("界".repeat(116)), "347 UTF-8 bytes"),
    ] {
        let before = server.received_requests().await.unwrap().len();
        let mut body = credentials("EEA");
        body[field] = value;
        let response = add(&client, body).await;
        assert_eq!(
            response.status.as_u16(),
            if diagnostic == "Validation failed" {
                422
            } else {
                400
            }
        );
        assert!(response.text().contains(diagnostic));
        assert_redacted(&response.text());
        assert_eq!(server.received_requests().await.unwrap().len(), before);
    }

    let mut invalid_header = credentials("EEA");
    invalid_header["apiPassphrase"] = json!(format!("{PASSPHRASE}\ninvalid"));
    let before = server.received_requests().await.unwrap().len();
    assert_problem(
        &add(&client, invalid_header).await,
        None,
        "Could not read /api/v5/account/balance from OKX",
    );
    assert_eq!(server.received_requests().await.unwrap().len(), before);
    assert_no_okx_writes(&app).await;

    mount_balances(&server).await;
    price(&app, "COIN", "10").await;
    let account = add(&client, credentials("EEA")).await;
    assert_eq!(account.status, 200, "{}", account.text());
    let id = session_id(&app).await;
    let account_id = account.json()["id"].as_i64().unwrap();
    let initial_holdings = holdings(&app, account_id).await;
    let read_session = || async {
        picsou::db::query_as::<(String, String, String, String, String)>(
            "SELECT api_key, api_secret, api_passphrase, region, status FROM crypto_exchange_session WHERE id = $1"
        ).bind(id).fetch_one(&app.state.db).await.unwrap()
    };
    for (endpoint, status, body, code, diagnostic) in &cases {
        mount_replies(
            &server,
            FIXTURES,
            Some((
                endpoint,
                ResponseTemplate::new(*status).set_body_string(body),
            )),
        )
        .await;
        let mut replacement = credentials("EEA");
        replacement["apiKey"] = json!("synthetic-replacement");
        let before_probe = read_session().await;
        assert_problem(&add(&client, replacement).await, *code, diagnostic);
        assert_eq!(read_session().await, before_probe);
        assert_problem(&sync_okx(&client, id).await, *code, diagnostic);
        assert_eq!(session_status(&app, id).await, "ERROR");
    }
    assert_eq!(holdings(&app, account_id).await, initial_holdings);
    let output = logs.0.lock().unwrap();
    let output = std::str::from_utf8(&output).unwrap();
    assert!(
        output.contains("OKX request for /api/v5/account/balance failed with invalid header value")
    );
    assert!(output.contains(
        "Crypto exchange sync failed for OKX: The OKX API passphrase is incorrect (50105)."
    ));
    assert_redacted(output);
}

#[tokio::test]
async fn any_invalid_endpoint_fails_the_sync_without_overwriting_the_snapshot() {
    let server = MockServer::start().await;
    mount_balances(&server).await;
    let app = app_for_okx(&server).await;
    price(&app, "COIN", "10").await;
    let client = app.login_admin().await;
    let response = add(&client, credentials("EEA")).await;
    assert_eq!(response.status, 200, "{}", response.text());
    let account_id = response.json()["id"].as_i64().unwrap();
    let id = session_id(&app).await;
    let initial_holdings = holdings(&app, account_id).await;
    let positions_url = format!("/api/accounts/{account_id}/positions");
    let initial_positions = client.get(&positions_url).send().await.json();
    let snapshot = || async {
        picsou::db::query_scalar::<Decimal>(
            "SELECT balance FROM balance_snapshot WHERE account_id = $1",
        )
        .bind(account_id)
        .fetch_one(&app.state.db)
        .await
        .unwrap()
    };
    let initial_balance = snapshot().await;
    // Give sync a new trading balance before each failure farther down the chain.
    let changed_trading = rows(json!([{"totalEq":"99","details":[{"ccy":"COIN","eq":"99"}]}]));
    let mut fixtures = FIXTURES;
    fixtures[0] = &changed_trading;

    for (index, endpoint) in PATHS.into_iter().enumerate() {
        let (symbol, field) = match endpoint {
            TRADING => ("COIN", "eq"),
            FUNDING => ("COIN", "bal"),
            ETH => ("BETH", "amt"),
            SOL => ("OKSOL", "amt"),
            _ => ("COIN", "amt"),
        };
        let mut cases = vec![
            (206, EMPTY.to_owned(), "HTTP 206"),
            (401, EMPTY.to_owned(), "HTTP 401"),
            (429, EMPTY.to_owned(), "HTTP 429"),
            (500, EMPTY.to_owned(), "HTTP 500"),
            (200, r#"{"code":"99999","data":[]}"#.into(), "rejected"),
            (
                200,
                "<html>synthetic edge error</html>".into(),
                "unreadable JSON",
            ),
            (200, r#"{"data":[]}"#.into(), "unexpected code"),
            (200, r#"{"code":0,"data":[]}"#.into(), "unexpected code"),
            (200, r#"{"code":"0"}"#.into(), "unexpected data"),
            (200, r#"{"code":"0","data":{}}"#.into(), "unexpected data"),
            (
                200,
                r#"{"code":"0","data":[{}],"data":[]}"#.into(),
                "unreadable JSON",
            ),
            (200, format!("{EMPTY}{EMPTY}"), "unreadable JSON"),
            (
                200,
                balance_rows(endpoint, json!([{field:"0"}])),
                "unexpected ccy",
            ),
            (
                200,
                balance_rows(endpoint, json!([{"ccy":" ","amt":"0"}])),
                "unexpected ccy",
            ),
        ];
        for malformed in [
            Value::Null,
            json!("  "),
            json!("bad"),
            json!(2),
            json!(1.25),
        ] {
            cases.push((
                200,
                balance_rows(endpoint, json!([{"ccy":symbol,field:malformed}])),
                field,
            ));
        }
        if endpoint == TRADING {
            for (account, diagnostic) in [
                (json!([]), "unexpected data"),
                (json!([{}, {}]), "unexpected data"),
                (json!([null]), "unexpected details"),
                (json!([{"details":[]}]), "unexpected totalEq"),
                (json!([{"totalEq":"1"}]), "unexpected details"),
                (
                    json!([{"totalEq":"bad","details":[]}]),
                    "unexpected totalEq",
                ),
                (
                    json!([{"details":[{"ccy":"COIN","eq":"1"}]}]),
                    "unexpected totalEq",
                ),
                (
                    json!([{"totalEq":"bad","details":[{"ccy":"COIN","eq":"1"}]}]),
                    "unexpected totalEq",
                ),
                (
                    json!([{"totalEq":"1","details":[{"ccy":"COIN","eq":"0"}]}]),
                    "no non-zero details",
                ),
                (
                    json!([{"totalEq":"1","details":[{"ccy":"COIN"}]}]),
                    "unexpected eq",
                ),
                (json!([{"totalEq":"1","details":[]}]), "no non-zero details"),
                (
                    json!([{"totalEq":"-1","details":[]}]),
                    "no non-zero details",
                ),
            ] {
                cases.push((200, rows(account), diagnostic));
            }
        } else {
            cases.push((
                200,
                balance_rows(endpoint, json!([{"ccy":symbol,field:"-2"}])),
                "unexpected negative",
            ));
        }
        if [TRADING, FUNDING, SAVINGS].contains(&endpoint) {
            cases.push((
                200,
                balance_rows(
                    endpoint,
                    json!([{"ccy":"COIN",field:"2"},{"ccy":" coin ",field:"0"}]),
                ),
                "duplicate ccy",
            ));
        }
        if endpoint == ORDERS {
            let investments = json!([{"ccy":"COIN","amt":"1"}]);
            let order = json!({"ordId":"1","investData":investments});
            for (orders, diagnostic) in [
                (json!([{"ordId":"1"}]), "unexpected investData"),
                (
                    json!([{"ordId":"1","investData":[]}]),
                    "unexpected investData",
                ),
                (json!([{"investData":investments}]), "unexpected ordId"),
                (
                    json!([{"ordId":" ","investData":investments}]),
                    "unexpected ordId",
                ),
                (json!([order, order]), "duplicate ordId"),
            ] {
                cases.push((200, rows(orders), diagnostic));
            }
        }
        if [ETH, SOL].contains(&endpoint) {
            let row = json!({"ccy":symbol,"amt":"8"});
            for (stake, diagnostic) in [
                (json!([row, row]), "multiple staking balances"),
                (json!([{"ccy":"COIN","amt":"0"}]), "unexpected ccy"),
                (json!([{"ccy":symbol,"amt":"1"}]), "cannot reconcile"),
                (json!([{"ccy":symbol,"amt":"0"}]), "cannot reconcile"),
                (json!([]), "cannot reconcile"),
            ] {
                cases.push((200, rows(stake), diagnostic));
            }
        }

        for (status, body, diagnostic) in cases {
            mount_replies(
                &server,
                fixtures,
                Some((
                    endpoint,
                    ResponseTemplate::new(status).set_body_string(body),
                )),
            )
            .await;
            let failure = sync_okx(&client, id).await;
            assert_problem(&failure, None, diagnostic);
            let requests = server.received_requests().await.unwrap();
            let queried: Vec<_> = requests.iter().map(|request| request.url.path()).collect();
            assert_eq!(queried, PATHS[..=index]);
        }

        mount_replies(
            &server,
            fixtures,
            Some((
                endpoint,
                ResponseTemplate::new(200)
                    .insert_header("content-encoding", "gzip")
                    .set_body_string("broken compression"),
            )),
        )
        .await;
        assert_problem(&sync_okx(&client, id).await, None, endpoint);
    }
    assert_eq!(session_status(&app, id).await, "ERROR");
    assert_eq!(holdings(&app, account_id).await, initial_holdings);
    assert_eq!(
        client.get(&positions_url).send().await.json(),
        initial_positions
    );
    assert_eq!(snapshot().await, initial_balance);
}

#[tokio::test]
async fn each_endpoint_can_time_out_without_saving_a_connection() {
    let server = MockServer::start().await;
    mount_balances(&server).await;
    let app = app_for_okx(&server).await;
    let client = app.login_admin().await;
    for (index, endpoint) in PATHS.into_iter().enumerate() {
        Mock::given(method("GET"))
            .and(path(endpoint))
            .and(header("ok-access-key", format!("{KEY}-{index}")))
            .respond_with(ResponseTemplate::new(200).set_delay(Duration::from_secs(11)))
            .with_priority(1)
            .mount(&server)
            .await;
    }
    futures::future::join_all(PATHS.into_iter().enumerate().map(|(index, endpoint)| {
        let client = &client;
        async move {
            let mut body = credentials("EEA");
            body["apiKey"] = json!(format!("{KEY}-{index}"));
            assert_problem(
                &add(client, body).await,
                None,
                &format!("OKX request for {endpoint} timed out."),
            );
        }
    }))
    .await;
    let requests = server.received_requests().await.unwrap();
    for index in 0..PATHS.len() {
        let key = format!("{KEY}-{index}");
        let queried: Vec<_> = requests
            .iter()
            .filter(|r| r.headers["ok-access-key"] == key)
            .map(|r| r.url.path())
            .collect();
        assert_eq!(queried, PATHS[..=index]);
    }
    assert_no_okx_writes(&app).await;
}

#[tokio::test]
async fn empty_balances_connect_and_http_redirects_do_not_forward_credentials() {
    let server = MockServer::start().await;
    let other_host = MockServer::start().await;
    let app = TestApp::builder()
        .env("OKX_EEA_BASE_URL", server.uri())
        .env("OKX_GLOBAL_BASE_URL", "http://127.0.0.1:0")
        .with_admin()
        .spawn()
        .await;
    let client = app.login_admin().await;
    // Even a redirect with a valid envelope is a failure and must stay on this host.
    let redirect = Mock::given(method("GET"))
        .and(path(TRADING))
        .respond_with(
            ResponseTemplate::new(302)
                .insert_header("location", format!("{}{TRADING}", other_host.uri()))
                .set_body_string(EMPTY),
        )
        .mount_as_scoped(&server)
        .await;
    assert_problem(&add(&client, credentials("EEA")).await, None, "HTTP 302");
    assert!(other_host.received_requests().await.unwrap().is_empty());
    assert_problem(
        &add(&client, credentials("GLOBAL")).await,
        None,
        "Could not reach OKX",
    );
    assert_no_okx_writes(&app).await;
    drop(redirect);
    for endpoint in PATHS {
        let body = if endpoint == TRADING {
            rows(json!([{"totalEq":"0.00","details":[]}]))
        } else {
            EMPTY.into()
        };
        Mock::given(method("GET"))
            .and(path(endpoint))
            .respond_with(ResponseTemplate::new(200).set_body_string(body))
            .mount(&server)
            .await;
    }
    let response = add(&client, credentials("EEA")).await;
    assert_eq!(response.status, 200, "{}", response.text());
    assert!(
        holdings(&app, response.json()["id"].as_i64().unwrap())
            .await
            .is_empty()
    );
}
