//! IbkrControllerTest, IbkrSyncServiceTest and IbkrStatusWriterTest over HTTP, with the Flex Web
//! Service stubbed by wiremock. Synthetic account ids and tokens only.

use std::str::FromStr;

use http::StatusCode;
use picsou::ledger::sidecar::SyncStatus;
use picsou::sync::ibkr;
use picsou::testing::{ADMIN_USERNAME, Client, TestApp};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

use crate::writer_free::{Slow, stays_writable_while};

const TOKEN: &str = "123456789012345678901234";
const QUERY: &str = "987654";

fn dec(value: &str) -> Decimal {
    Decimal::from_str(value).unwrap()
}

struct Fixture {
    app: TestApp,
    server: MockServer,
    admin: Client,
    member_id: i64,
}

impl Fixture {
    async fn new() -> Self {
        Self::with_openfigi(None).await
    }

    /// The ISIN resolver points at `openfigi` when given one; it is unreachable otherwise.
    async fn with_openfigi(openfigi: Option<&MockServer>) -> Self {
        let server = MockServer::start().await;
        let mut builder = TestApp::builder().env("IBKR_FLEX_URL", server.uri());
        if let Some(openfigi) = openfigi {
            builder = builder.env("OPENFIGI_BASE_URL", openfigi.uri());
        }
        let app = builder.with_admin().spawn().await;
        let admin = app.login_admin().await;
        let member_id = picsou::auth::users::find_by_username(&app.state.db, ADMIN_USERNAME)
            .await
            .unwrap()
            .unwrap()
            .member_id;
        Self {
            app,
            server,
            admin,
            member_id,
        }
    }

    async fn connect(&self) {
        let response = self
            .admin
            .post("/api/ibkr/connect")
            .json(&json!({"token": format!("  {TOKEN} "), "queryId": format!(" {QUERY}")}))
            .send()
            .await;
        assert_eq!(
            response.status,
            StatusCode::NO_CONTENT,
            "{}",
            response.text()
        );
    }

    async fn status(&self) -> Value {
        let response = self.admin.get("/api/ibkr/status").send().await;
        assert_eq!(response.status, StatusCode::OK);
        response.json()
    }

    async fn sync(&self) -> (StatusCode, Value) {
        let response = self.admin.post("/api/ibkr/sync").send().await;
        (response.status, response.json())
    }

    /// SendRequest answers a reference code; GetStatement serves the bodies in order, the last
    /// one for every further poll.
    async fn flex(&self, statements: &[&str]) {
        Mock::given(method("GET"))
            .and(path("/SendRequest"))
            .and(query_param("t", TOKEN))
            .and(query_param("q", QUERY))
            .and(query_param("v", "3"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                "<FlexStatementResponse timestamp=\"x\"><Status>Success</Status>\
                 <ReferenceCode>5550001</ReferenceCode><Url>https://example.invalid</Url>\
                 </FlexStatementResponse>",
            ))
            .mount(&self.server)
            .await;
        for (index, body) in statements.iter().enumerate() {
            let mock = Mock::given(method("GET"))
                .and(path("/GetStatement"))
                .and(query_param("t", TOKEN))
                .and(query_param("q", "5550001"))
                .respond_with(ResponseTemplate::new(200).set_body_string(*body));
            let mock = if index + 1 < statements.len() {
                mock.up_to_n_times(1)
            } else {
                mock
            };
            mock.mount(&self.server).await;
        }
    }

    async fn price(&self, ticker: &str, price: &str) {
        picsou::db::query(
            "INSERT INTO price_snapshot (ticker, date, price_eur) VALUES ($1, $2, $3)",
        )
        .bind(ticker)
        .bind(self.app.state.clock.today())
        .bind(dec(price))
        .execute(&self.app.state.db)
        .await
        .unwrap();
    }

    async fn holdings(&self, account_id: i64) -> Vec<(String, Decimal, Option<Decimal>)> {
        picsou::db::query_as(
            "SELECT ticker, quantity, average_buy_in FROM account_holding WHERE account_id = $1 \
             ORDER BY ticker",
        )
        .bind(account_id)
        .fetch_all(&self.app.state.db)
        .await
        .unwrap()
    }

    async fn account_count(&self) -> i64 {
        picsou::db::query_scalar(
            "SELECT count(*) FROM account WHERE external_account_id LIKE 'ibkr_%'",
        )
        .fetch_one(&self.app.state.db)
        .await
        .unwrap()
    }
}

fn statement(account_information: &str, positions: &str) -> String {
    format!(
        "<FlexQueryResponse queryName=\"OpenPositions\" type=\"AF\"><FlexStatements count=\"1\">\
         <FlexStatement accountId=\"U1234567\" period=\"LastBusinessDay\">{account_information}\
         <OpenPositions>{positions}</OpenPositions></FlexStatement></FlexStatements></FlexQueryResponse>"
    )
}

/// AAPL in USD (150 x 0.92 = 138 EUR cost basis) with its LOT duplicate, an EUR ETF, a cash line,
/// a zero line, an option, and a USD line without fxRateToBase.
const MIXED: &str = r#"
    <OpenPosition accountId="U1234567" currency="USD" fxRateToBase="0.92" assetCategory="STK"
        symbol="AAPL" description="APPLE INC" isin="US0378331005" position="10" markPrice="200.00"
        costBasisPrice="150.00" levelOfDetail="SUMMARY" />
    <OpenPosition accountId="U1234567" currency="USD" fxRateToBase="0.92" assetCategory="STK"
        symbol="AAPL" description="APPLE INC" isin="US0378331005" position="10" markPrice="200.00"
        costBasisPrice="150.00" levelOfDetail="LOT" />
    <OpenPosition accountId="U1234567" currency="EUR" fxRateToBase="1" assetCategory="STK"
        symbol="IWDA" description="ISHARES CORE MSCI WORLD" isin="IE00B4L5Y983" position="5"
        markPrice="90.00" costBasisPrice="80.00" levelOfDetail="SUMMARY" />
    <OpenPosition accountId="U1234567" currency="USD" assetCategory="CASH" symbol="USD.CASH"
        position="500" markPrice="1" levelOfDetail="SUMMARY" />
    <OpenPosition accountId="U1234567" currency="USD" fxRateToBase="0.92" assetCategory="STK"
        symbol="ZRO" position="0" markPrice="5" levelOfDetail="SUMMARY" />
    <OpenPosition accountId="U1234567" currency="USD" fxRateToBase="0.92" assetCategory="OPT"
        symbol="SPY 240119C00470000" position="1" markPrice="2" levelOfDetail="SUMMARY" />
    <OpenPosition accountId="U1234567" currency="USD" assetCategory="STK" symbol="PLTR"
        description="PALANTIR" position="8" markPrice="45" costBasisPrice="40" levelOfDetail="SUMMARY" />
"#;

#[tokio::test]
async fn status_reads_not_connected_until_a_connection_is_stored() {
    let f = Fixture::new().await;
    assert_eq!(f.status().await, json!({"connected": false}));

    f.connect().await;
    let status = f.status().await;
    assert_eq!(status["connected"], json!(true));
    assert_eq!(status["status"], json!("CONNECTED"));
    assert_eq!(status["maskedToken"], json!("••••••1234"));
    assert!(status["connectionId"].is_i64());
    assert!(status.get("lastSyncedAt").is_none());
}

#[tokio::test]
async fn credentials_are_trimmed_and_encrypted_at_rest() {
    let f = Fixture::new().await;
    f.connect().await;
    let (token, query_id): (String, String) =
        picsou::db::query_as("SELECT token, query_id FROM ibkr_connection WHERE member_id = $1")
            .bind(f.member_id)
            .fetch_one(&f.app.state.db)
            .await
            .unwrap();
    assert!(!token.contains(TOKEN) && !query_id.contains(QUERY));
    assert_eq!(f.app.state.crypto.decrypt(&token).unwrap(), TOKEN);
    assert_eq!(f.app.state.crypto.decrypt(&query_id).unwrap(), QUERY);

    // Reconnecting replaces the row rather than adding one.
    f.connect().await;
    let rows: i64 = picsou::db::query_scalar("SELECT count(*) FROM ibkr_connection")
        .fetch_one(&f.app.state.db)
        .await
        .unwrap();
    assert_eq!(rows, 1);
}

#[tokio::test]
async fn connect_validates_both_fields() {
    let f = Fixture::new().await;
    let response = f
        .admin
        .post("/api/ibkr/connect")
        .json(&json!({"token": " ", "unknown": 1}))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    let body = response.json();
    assert_eq!(body["title"], json!("Validation failed"));
    assert_eq!(
        body["errors"],
        json!({"token": "must not be blank", "queryId": "must not be blank"})
    );
    let unreadable = f
        .admin
        .post("/api/ibkr/connect")
        .body("application/json", "{")
        .send()
        .await;
    assert_eq!(unreadable.status, StatusCode::BAD_REQUEST);
    assert_eq!(f.status().await, json!({"connected": false}));
}

#[tokio::test]
async fn endpoints_require_a_signed_in_user() {
    let f = Fixture::new().await;
    let anonymous = f.app.client();
    assert_eq!(
        anonymous.get("/api/ibkr/status").send().await.status,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        anonymous.post("/api/ibkr/sync").send().await.status,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn connections_are_scoped_to_the_member() {
    let f = Fixture::new().await;
    f.connect().await;
    f.app.create_member("bob", "password-1234").await;
    let bob = f.app.login("bob", "password-1234").await;

    let status = bob.get("/api/ibkr/status").send().await.json();
    assert_eq!(status, json!({"connected": false}));
    let sync = bob.post("/api/ibkr/sync").send().await;
    assert_eq!(sync.status, StatusCode::UNPROCESSABLE_ENTITY);
    let deleted = bob.delete("/api/ibkr/connection").send().await;
    assert_eq!(deleted.status, StatusCode::NO_CONTENT);

    assert_eq!(f.status().await["connected"], json!(true));
}

#[tokio::test]
async fn delete_clears_the_connection_and_answers_204_when_there_is_none() {
    let f = Fixture::new().await;
    f.connect().await;
    assert!(
        ibkr::delete_connection(&f.app.state, f.member_id)
            .await
            .unwrap()
    );
    assert!(
        !ibkr::delete_connection(&f.app.state, f.member_id)
            .await
            .unwrap()
    );
    f.connect().await;
    for _ in 0..2 {
        let response = f.admin.delete("/api/ibkr/connection").send().await;
        assert_eq!(response.status, StatusCode::NO_CONTENT);
        assert!(response.body.is_empty());
    }
    assert_eq!(f.status().await, json!({"connected": false}));
}

#[tokio::test]
async fn sync_without_a_connection_is_a_sync_error() {
    let f = Fixture::new().await;
    let (status, body) = f.sync().await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(body["title"], json!("Unprocessable Entity"));
    assert_eq!(
        body["detail"],
        json!(
            "No Interactive Brokers connection. Please connect from the Interactive Brokers page."
        )
    );
    assert_eq!(body["instance"], json!("/api/ibkr/sync"));
    assert!(body.get("code").is_none());
}

#[tokio::test]
async fn sync_maps_positions_onto_one_account_with_converted_cost_basis() {
    let f = Fixture::new().await;
    f.connect().await;
    f.price("AAPL", "180").await;
    f.price("IWDA", "90").await;
    f.flex(&[&statement("", MIXED)]).await;

    let (status, body) = f.sync().await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let accounts = body.as_array().unwrap();
    assert_eq!(accounts.len(), 1);
    let account = &accounts[0];
    assert_eq!(account["name"], json!("IBKR U1234567"));
    assert_eq!(account["type"], json!("COMPTE_TITRES"));
    assert_eq!(account["provider"], json!("Interactive Brokers"));
    assert_eq!(account["currency"], json!("EUR"));
    assert_eq!(account["color"], json!("#d81222"));
    assert_eq!(account["isManual"], json!(false));
    assert_eq!(account["hidden"], json!(false));
    assert_eq!(account["listCollapsed"], json!(false));
    assert_eq!(account["lastSyncedAt"], json!("2026-01-15T10:00:00Z"));
    // PLTR is valued at nothing: no recorded price. 10 x 180 + 5 x 90.
    let balance = dec(&account["currentBalance"].to_string());
    assert_eq!(balance, dec("2250"));
    assert_eq!(dec(&account["currentBalanceEur"].to_string()), dec("2250"));

    let id = account["id"].as_i64().unwrap();
    let holdings = f.holdings(id).await;
    let tickers: Vec<&str> = holdings.iter().map(|h| h.0.as_str()).collect();
    assert_eq!(tickers, ["AAPL", "IWDA", "PLTR"]);
    assert_eq!(holdings[0].1, dec("10"));
    assert_eq!(holdings[0].2, Some(dec("138")));
    assert_eq!(holdings[1].2, Some(dec("80")));
    // A USD line without fxRateToBase keeps an unknown cost basis, not 40 "EUR".
    assert_eq!(holdings[2].2, None);

    let (balance, external): (Decimal, String) = picsou::db::query_as(
        "SELECT current_balance, external_account_id FROM account WHERE id = $1",
    )
    .bind(id)
    .fetch_one(&f.app.state.db)
    .await
    .unwrap();
    assert_eq!(balance, dec("2250"));
    assert_eq!(external, "ibkr_U1234567");
    let snapshot: Decimal = picsou::db::query_scalar(
        "SELECT balance FROM balance_snapshot WHERE account_id = $1 AND date = $2",
    )
    .bind(id)
    .bind(f.app.state.clock.today())
    .fetch_one(&f.app.state.db)
    .await
    .unwrap();
    assert_eq!(snapshot, dec("2250"));

    let status = f.status().await;
    assert_eq!(status["status"], json!("CONNECTED"));
    assert_eq!(status["lastSyncedAt"], json!("2026-01-15T10:00:00Z"));
}

#[tokio::test]
async fn a_resync_replaces_holdings_and_an_emptied_account_is_worth_zero() {
    let f = Fixture::new().await;
    f.connect().await;
    f.price("AAPL", "180").await;
    f.flex(&[&statement("", MIXED)]).await;
    let (_, first) = f.sync().await;
    let id = first[0]["id"].as_i64().unwrap();

    f.server.reset().await;
    f.flex(&[&statement(
        "<AccountInformation accountId=\"U1234567\" currency=\"EUR\" />",
        "",
    )])
    .await;
    let (status, second) = f.sync().await;
    assert_eq!(status, StatusCode::OK, "{second}");
    assert_eq!(second[0]["id"].as_i64(), Some(id));
    assert_eq!(dec(&second[0]["currentBalance"].to_string()), Decimal::ZERO);
    assert!(f.holdings(id).await.is_empty());
    assert_eq!(f.account_count().await, 1);
}

#[tokio::test]
async fn opposite_legs_netting_to_zero_persist_no_holding() {
    let f = Fixture::new().await;
    f.connect().await;
    let legs = r#"
        <OpenPosition accountId="U1234567" currency="EUR" assetCategory="STK" symbol="ABC"
            position="5" markPrice="10" costBasisPrice="9" levelOfDetail="SUMMARY" />
        <OpenPosition accountId="U1234567" currency="EUR" assetCategory="STK" symbol="ABC"
            position="-5" markPrice="10" costBasisPrice="11" levelOfDetail="SUMMARY" />"#;
    f.flex(&[&statement("", legs)]).await;
    let (status, body) = f.sync().await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert!(f.holdings(body[0]["id"].as_i64().unwrap()).await.is_empty());
}

#[tokio::test]
async fn a_non_eur_base_currency_fails_the_sync_and_persists_nothing() {
    let f = Fixture::new().await;
    f.connect().await;
    f.flex(&[&statement(
        "<AccountInformation accountId=\"U1234567\" currency=\"USD\" />",
        MIXED,
    )])
    .await;
    let (status, body) = f.sync().await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    let detail = body["detail"].as_str().unwrap();
    assert!(
        detail.contains("IBKR account base currency is USD"),
        "{detail}"
    );
    assert!(detail.contains("EUR-based accounts"), "{detail}");
    assert_eq!(f.account_count().await, 0);
    // The ERROR status survives the rolled-back sync.
    assert_eq!(f.status().await["status"], json!("ERROR"));
}

#[tokio::test]
async fn an_expired_token_surfaces_ibkr_message_and_needs_reauth() {
    let f = Fixture::new().await;
    f.connect().await;
    Mock::given(method("GET"))
        .and(path("/SendRequest"))
        .respond_with(ResponseTemplate::new(200).set_body_string(
            "<FlexStatementResponse><Status>Fail</Status><ErrorCode>1012</ErrorCode>\
             <ErrorMessage>Token has expired.</ErrorMessage></FlexStatementResponse>",
        ))
        .mount(&f.server)
        .await;
    let (status, body) = f.sync().await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        body["detail"],
        json!("Interactive Brokers: Token has expired. (code 1012)")
    );
    assert_eq!(f.status().await["status"], json!("ERROR"));

    let result = ibkr::resync_reporting(&f.app.state, f.member_id).await;
    assert_eq!(result.source, "ibkr");
    assert_eq!(result.status, SyncStatus::NeedsReauth);
    assert_eq!(result.message, "Reauthentication required");
}

#[tokio::test]
async fn upstream_failures_map_to_sync_errors() {
    let f = Fixture::new().await;
    f.connect().await;
    let cases = [
        (
            ResponseTemplate::new(500).set_body_string("<oops/>"),
            "Interactive Brokers returned HTTP 500. Please check your token and try again.",
        ),
        (
            ResponseTemplate::new(200).set_body_string("  "),
            "Interactive Brokers returned an empty response. Please try again.",
        ),
        (
            ResponseTemplate::new(200).set_body_string("<html><body>Maintenance<br></body></html>"),
            "Interactive Brokers returned an unexpected (non-XML) response.",
        ),
        (
            ResponseTemplate::new(200).set_body_string(
                "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/hosts\">]><r>&x;</r>",
            ),
            "Interactive Brokers returned an unexpected (non-XML) response.",
        ),
        (
            ResponseTemplate::new(200).set_body_string(
                "<FlexStatementResponse><Status>Success</Status></FlexStatementResponse>",
            ),
            "IBKR did not return a reference code. Check your token and query id.",
        ),
        (
            ResponseTemplate::new(200).set_body_string(
                "<FlexStatementResponse><Status>Warn</Status></FlexStatementResponse>",
            ),
            "Interactive Brokers: IBKR rejected the Flex request (root=FlexStatementResponse, status=Warn)",
        ),
    ];
    for (template, expected) in cases {
        f.server.reset().await;
        Mock::given(method("GET"))
            .and(path("/SendRequest"))
            .respond_with(template)
            .mount(&f.server)
            .await;
        let (status, body) = f.sync().await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
        assert_eq!(body["detail"], json!(expected));
        // Never echoes the token, whatever failed.
        assert!(!body.to_string().contains(TOKEN));
        f.app.clock.advance(std::time::Duration::from_secs(60));
    }
}

#[tokio::test]
async fn an_unreachable_service_is_a_sync_error_without_the_token() {
    // Nothing listens on the discard port.
    let app = TestApp::builder()
        .env("IBKR_FLEX_URL", "http://127.0.0.1:9")
        .with_admin()
        .spawn()
        .await;
    let admin = app.login_admin().await;
    let connect = admin
        .post("/api/ibkr/connect")
        .json(&json!({"token": TOKEN, "queryId": QUERY}))
        .send()
        .await;
    assert_eq!(connect.status, StatusCode::NO_CONTENT);
    let response = admin.post("/api/ibkr/sync").send().await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    let detail = response.json()["detail"].as_str().unwrap().to_owned();
    assert!(
        detail.starts_with("Unable to reach Interactive Brokers: "),
        "{detail}"
    );
    assert!(!detail.contains(TOKEN), "{detail}");
}

#[tokio::test]
async fn a_statement_still_generating_is_polled_again() {
    let f = Fixture::new().await;
    f.connect().await;
    f.flex(&[
        "<FlexStatementResponse><Status>Warn</Status><ErrorCode>1019</ErrorCode>\
         <ErrorMessage>Statement generation in progress. Please try again shortly.</ErrorMessage>\
         </FlexStatementResponse>",
        &statement("", MIXED),
    ])
    .await;
    let (status, body) = f.sync().await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body.as_array().unwrap().len(), 1);
}

#[tokio::test]
async fn a_statement_error_on_get_statement_is_reported() {
    let f = Fixture::new().await;
    f.connect().await;
    f.flex(&[
        "<FlexStatementResponse><Status>Fail</Status><ErrorCode>1015</ErrorCode>\
              <ErrorMessage>Token is invalid.</ErrorMessage></FlexStatementResponse>",
    ])
    .await;
    let (status, body) = f.sync().await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        body["detail"],
        json!("Interactive Brokers: Token is invalid. (code 1015)")
    );
}

#[tokio::test]
async fn several_sections_for_one_account_are_refused() {
    let f = Fixture::new().await;
    f.connect().await;
    f.flex(&["<FlexQueryResponse><FlexStatements count=\"2\">\
              <FlexStatement accountId=\"U1234567\"><OpenPositions/></FlexStatement>\
              <FlexStatement accountId=\"U1234567\"><OpenPositions/></FlexStatement>\
              </FlexStatements></FlexQueryResponse>"])
        .await;
    let (status, body) = f.sync().await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert!(
        body["detail"]
            .as_str()
            .unwrap()
            .contains("Last Business Day")
    );
    assert_eq!(f.account_count().await, 0);
}

#[tokio::test]
async fn undecryptable_credentials_read_as_error_and_ask_to_reconnect() {
    let f = Fixture::new().await;
    f.connect().await;
    picsou::db::query("UPDATE ibkr_connection SET token = 'not-a-ciphertext'")
        .execute(&f.app.state.db)
        .await
        .unwrap();
    let status = f.status().await;
    assert_eq!(status["connected"], json!(true));
    assert_eq!(status["status"], json!("ERROR"));
    assert_eq!(status["maskedToken"], json!("••••"));

    let (code, body) = f.sync().await;
    assert_eq!(code, StatusCode::UNPROCESSABLE_ENTITY);
    let detail = body["detail"].as_str().unwrap();
    assert!(
        detail.contains("decrypt") && detail.contains("reconnect"),
        "{detail}"
    );
    assert!(f.server.received_requests().await.unwrap().is_empty());
    let stored: String = picsou::db::query_scalar("SELECT status FROM ibkr_connection")
        .fetch_one(&f.app.state.db)
        .await
        .unwrap();
    assert_eq!(stored, "ERROR");

    let result = ibkr::resync_reporting(&f.app.state, f.member_id).await;
    assert_eq!(result.status, SyncStatus::Failed);
    assert_eq!(result.message, "Sync failed");
}

#[tokio::test]
async fn a_soft_deleted_account_is_not_resurrected() {
    let f = Fixture::new().await;
    f.connect().await;
    picsou::db::query(
        "INSERT INTO account (member_id, name, type, currency, current_balance, external_account_id, \
         is_manual, color, hidden, deleted_at, created_at, updated_at) VALUES ($1, 'Gone', \
         'COMPTE_TITRES', 'EUR', 0, 'ibkr_U1234567', false, '#d81222', false, now(), now(), now())",
    )
    .bind(f.member_id)
    .execute(&f.app.state.db)
    .await
    .unwrap();
    f.flex(&[&statement("", MIXED)]).await;
    let (status, body) = f.sync().await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, json!([]));
    let holdings: i64 = picsou::db::query_scalar("SELECT count(*) FROM account_holding")
        .fetch_one(&f.app.state.db)
        .await
        .unwrap();
    assert_eq!(holdings, 0);
    let status = f.status().await;
    assert_eq!(status["status"], json!("CONNECTED"));
    assert!(status["lastSyncedAt"].is_string());
}

#[tokio::test]
async fn sync_is_rate_limited_per_client_ip() {
    let f = Fixture::new().await;
    for _ in 0..6 {
        let (status, _) = f.sync().await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    }
    let (status, body) = f.sync().await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        body["detail"],
        json!("Too many sync requests. Please wait a moment before trying again.")
    );
    let other_ip = f
        .admin
        .post("/api/ibkr/sync")
        .header("X-Forwarded-For", "10.0.0.6")
        .send()
        .await;
    // The in-process peer is not a trusted proxy, so the header changes nothing.
    assert_eq!(other_ip.status, StatusCode::TOO_MANY_REQUESTS);
    f.app.clock.advance(std::time::Duration::from_secs(60));
    assert_eq!(f.sync().await.0, StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn resync_reporting_skips_without_connection_and_reports_success() {
    let f = Fixture::new().await;
    let skipped = ibkr::resync_reporting(&f.app.state, f.member_id).await;
    assert_eq!(skipped.status, SyncStatus::SkippedNotConnected);
    assert_eq!(skipped.message, "No connection");
    assert!(f.server.received_requests().await.unwrap().is_empty());

    f.connect().await;
    f.flex(&[&statement("", MIXED)]).await;
    let synced = ibkr::resync_reporting(&f.app.state, f.member_id).await;
    assert_eq!(synced.status, SyncStatus::Synced);
    assert_eq!(synced.message, "");
    assert_eq!(f.account_count().await, 1);
}

#[tokio::test]
async fn the_database_stays_writable_while_the_tickers_are_resolved() {
    let openfigi = MockServer::start().await;
    let (slow, asked) = Slow::answering(ResponseTemplate::new(200).set_body_json(json!([
        {"data": [{"ticker": "IWDA", "exchCode": "NA", "name": "ISHARES CORE MSCI WORLD"}]}
    ])));
    Mock::given(method("POST"))
        .and(path("/v3/mapping"))
        .respond_with(slow)
        .mount(&openfigi)
        .await;
    let f = Fixture::with_openfigi(Some(&openfigi)).await;
    f.connect().await;
    f.flex(&[&statement(
        "",
        r#"<OpenPosition accountId="U1234567" currency="EUR" fxRateToBase="1" assetCategory="STK"
            symbol="IWDA" description="ISHARES CORE MSCI WORLD" isin="IE00B4L5Y983" position="5"
            markPrice="90.00" costBasisPrice="80.00" levelOfDetail="SUMMARY" />"#,
    )])
    .await;

    let (status, body) = stays_writable_while(&f.app.state.db, &asked, f.sync()).await;

    assert_eq!(status, StatusCode::OK, "{body}");
    let id = body[0]["id"].as_i64().expect("the synced account");
    assert_eq!(f.holdings(id).await.len(), 1);
}
