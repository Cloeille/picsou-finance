//! CorumController and CorumSyncService over HTTP, the sidecar stubbed: the SCPI fund
//! reconciliation, the CORUM Life writer and its operations, the stored credentials and re-login.

use picsou::ledger::sidecar::SidecarBank;
use picsou::testing::{Client, TestApp};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use std::time::Duration;
use wiremock::matchers::{body_partial_json, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{
    admin, answer, app_with_sidecar, count, d, fail, member, n, session_of, settle, store_session,
};

const URL: &str = "CORUM_AUTH_URL";
const BANK: SidecarBank = SidecarBank::Corum;

fn fund(
    code: &str,
    label: &str,
    quantity: &str,
    withdrawal: Option<&str>,
    displayed: &str,
) -> Value {
    json!({
        "fundCode": code, "label": label, "quantity": n(quantity),
        "withdrawalPrice": withdrawal.map(n), "subscriptionPrice": n("200"),
        "displayedValueEur": n(displayed), "valuationDate": "2026-01-10"
    })
}

fn estate(holdings: Vec<Value>, total: &str) -> Value {
    json!({
        "contractCode": "RE-1", "propertyRightType": "FULL", "currency": "EUR",
        "totalValuationEur": n(total), "valuationDate": "2026-01-10",
        "snapshotComplete": true, "holdings": holdings
    })
}

fn life_line(
    code: &str,
    quantity: &str,
    price: &str,
    value: &str,
    invested: Option<&str>,
) -> Value {
    json!({
        "productCode": code, "label": format!("Fund {code}"), "quantity": n(quantity),
        "unitPrice": n(price), "valueEur": n(value), "netInvestedEur": invested.map(n),
        "valuationDate": "2026-01-10", "euroFund": false, "assetClass": "realEstate"
    })
}

fn operation(id: &str, kind: &str, date: &str, amount: &str) -> Value {
    json!({
        "operationId": id, "type": kind, "category": null, "date": date,
        "amountEur": n(amount), "fundCodes": null
    })
}

fn contract(
    code: &str,
    holdings: Vec<Value>,
    total: &str,
    invested: &str,
    operations: Value,
) -> Value {
    let lines = holdings.len();
    json!({
        "contractCode": code, "currency": "EUR", "totalValuationEur": n(total),
        "valuationDate": "2026-01-10", "valuationLineCount": lines,
        "netInvestedEur": n(invested), "paidInEur": n(invested), "openedAt": "2020-05-04",
        "scheduledPayment": { "amountEur": n("150"), "frequency": "monthly" },
        "snapshotComplete": true, "holdings": holdings, "operations": operations
    })
}

fn portfolio(real_estate: Value, life: Vec<Value>) -> Value {
    json!({ "realEstate": real_estate, "life": life })
}

/// Two funds, 10 shares each, quoted 180 to buy back and 200 to subscribe.
fn two_funds() -> Value {
    portfolio(
        estate(
            vec![
                fund("XL", "CORUM XL", "10", Some("180"), "2000"),
                fund("OR", "CORUM Origin", "10", Some("180"), "2000"),
            ],
            "4000",
        ),
        vec![],
    )
}

async fn login(app: &TestApp, client: &Client, remember: bool) -> picsou::testing::Response {
    let response = client
        .post("/api/corum/auth")
        .json(&json!({ "login": "C123", "password": "secret", "remember": remember }))
        .send()
        .await;
    settle(app).await;
    response
}

async fn status(client: &Client) -> Value {
    client.get("/api/corum/status").send().await.json()
}

async fn scpi_accounts(app: &TestApp) -> Vec<(String, Decimal, bool)> {
    sqlx::query_as(
        "SELECT name, current_balance, is_manual FROM account WHERE type = 'SCPI' AND deleted_at IS NULL ORDER BY name",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

async fn outcomes(app: &TestApp) -> Vec<(String, String)> {
    sqlx::query_as("SELECT fund_code, last_outcome FROM scpi_provider_fund ORDER BY fund_code")
        .fetch_all(&app.state.db)
        .await
        .unwrap()
}

async fn manual_scpi(app: &TestApp, member_id: i64, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, $2, 'SCPI', 'EUR', 1000, true, '#000') RETURNING id",
    )
    .bind(member_id)
    .bind(name)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

async fn sidecar_with(body: Value) -> MockServer {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/initiate", json!({ "sessionState": "state-1" })).await;
    answer(&sidecar, "/portfolio", body).await;
    sidecar
}

#[tokio::test]
async fn a_login_creates_one_scpi_account_per_fund_valued_at_the_withdrawal_price() {
    let sidecar = sidecar_with(two_funds()).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let response = login(&app, &client, false).await;

    assert_eq!(response.status, 200, "{}", response.text());
    let body = response.json();
    assert_eq!(body["syncStatus"], "QUEUED");
    assert_eq!(body["credentialsRemembered"], false);
    assert_eq!(body["lifeAmbiguousCount"], 0);
    assert!(!response.text().contains("secret"));
    let after = status(&client).await;
    assert_eq!(after["syncStatus"], "SUCCESS", "{after}");
    assert_eq!(after["isActive"], true);
    assert_eq!(
        scpi_accounts(&app).await,
        vec![
            ("CORUM Origin".to_owned(), d("1800"), true),
            ("CORUM XL".to_owned(), d("1800"), true),
        ]
    );
    assert_eq!(
        outcomes(&app).await,
        vec![
            ("OR".into(), "CREATED".into()),
            ("XL".into(), "CREATED".into())
        ]
    );
    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert!(session.encrypted_credentials.is_none());
    assert_eq!(
        app.state.crypto.decrypt(&session.session_state).unwrap(),
        "state-1"
    );
}

#[tokio::test]
async fn a_second_sync_updates_the_shares_and_creates_nothing() {
    let sidecar = sidecar_with(two_funds()).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    login(&app, &client, false).await;

    client.post("/api/corum/sync").send().await;
    settle(&app).await;

    assert_eq!(scpi_accounts(&app).await.len(), 2);
    assert_eq!(
        outcomes(&app).await,
        vec![
            ("OR".into(), "UPDATED".into()),
            ("XL".into(), "UPDATED".into())
        ]
    );
    let shares: Vec<Decimal> =
        sqlx::query_scalar("SELECT share_count FROM scpi_position ORDER BY id")
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(shares, vec![d("10"), d("10")]);
}

#[tokio::test]
async fn a_manual_account_named_like_a_fund_is_linked_and_two_are_ambiguous() {
    let sidecar = sidecar_with(two_funds()).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let linked = manual_scpi(&app, member_id, "corum-xl").await;
    manual_scpi(&app, member_id, "Corum Origin").await;
    manual_scpi(&app, member_id, "CORUM  ORIGIN").await;

    login(&app, &client, false).await;

    assert_eq!(
        outcomes(&app).await,
        vec![
            ("OR".into(), "AMBIGUOUS".into()),
            ("XL".into(), "LINKED".into())
        ]
    );
    let balance: Decimal = sqlx::query_scalar("SELECT current_balance FROM account WHERE id = $1")
        .bind(linked)
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(balance, d("1800"));
    assert_eq!(
        count(&app, "SELECT count(*) FROM account WHERE type = 'SCPI'").await,
        3
    );
}

#[tokio::test]
async fn an_ignored_fund_is_not_recreated_and_a_missing_one_is_reported_without_touching_it() {
    let sidecar = sidecar_with(portfolio(
        estate(
            vec![fund("XL", "CORUM XL", "10", Some("180"), "2000")],
            "2000",
        ),
        vec![],
    ))
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    sqlx::query("INSERT INTO scpi_provider_fund (member_id, provider, fund_code, label, ignored) VALUES ($1, 'CORUM', 'XL', 'CORUM XL', true)")
        .bind(member_id)
        .execute(&app.state.db)
        .await
        .unwrap();
    let gone = manual_scpi(&app, member_id, "CORUM Eurion").await;
    sqlx::query("INSERT INTO scpi_position (account_id, member_id, share_count, corum_fund_code) VALUES ($1, $2, 5, 'EU')")
        .bind(gone)
        .bind(member_id)
        .execute(&app.state.db)
        .await
        .unwrap();

    login(&app, &client, false).await;

    assert_eq!(
        outcomes(&app).await,
        vec![
            ("EU".into(), "MISSING_AT_PROVIDER".into()),
            ("XL".into(), "IGNORED".into())
        ]
    );
    assert_eq!(
        scpi_accounts(&app).await,
        vec![("CORUM Eurion".to_owned(), d("1000"), true)]
    );
}

#[tokio::test]
async fn a_fund_without_a_withdrawal_price_keeps_its_balance_and_reports_price_incomplete() {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/initiate", json!({ "sessionState": "state-1" })).await;
    Mock::given(method("POST"))
        .and(path("/portfolio"))
        .respond_with(ResponseTemplate::new(200).set_body_json(portfolio(
            estate(
                vec![fund("XL", "CORUM XL", "10", Some("180"), "2000")],
                "2000",
            ),
            vec![],
        )))
        .up_to_n_times(1)
        .mount(&sidecar)
        .await;
    answer(
        &sidecar,
        "/portfolio",
        portfolio(
            estate(vec![fund("XL", "CORUM XL", "12", None, "2400")], "2400"),
            vec![],
        ),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    login(&app, &client, false).await;

    client.post("/api/corum/sync").send().await;
    settle(&app).await;

    assert_eq!(
        outcomes(&app).await,
        vec![("XL".into(), "PRICE_INCOMPLETE".into())]
    );
    assert_eq!(scpi_accounts(&app).await[0].1, d("1800"));
    let (shares, status): (Decimal, String) =
        sqlx::query_as("SELECT share_count, valuation_status FROM scpi_position")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!((shares, status.as_str()), (d("12"), "PRICE_INCOMPLETE"));
}

#[tokio::test]
async fn portfolios_that_cannot_be_written_fail_the_sync_and_write_nothing() {
    let mut incomplete = two_funds();
    incomplete["realEstate"]["snapshotComplete"] = json!(false);
    let mut off_total = two_funds();
    off_total["realEstate"]["totalValuationEur"] = n("4000.02");
    let mut dollars = two_funds();
    dollars["realEstate"]["currency"] = json!("USD");
    let mut duplicate = two_funds();
    duplicate["realEstate"]["holdings"][1]["fundCode"] = json!("XL");
    let mut negative = two_funds();
    negative["realEstate"]["holdings"][0]["quantity"] = n("-1");
    let cases = [
        (incomplete, "PORTFOLIO_INCOMPLETE"),
        (off_total, "PORTFOLIO_INCOMPLETE"),
        (dollars, "INVALID_DATA"),
        (duplicate, "INVALID_DATA"),
        (negative, "INVALID_DATA"),
        (portfolio(Value::Null, vec![]), "PORTFOLIO_INCOMPLETE"),
        (json!({ "realEstate": null }), "INVALID_DATA"),
    ];
    for (body, code) in cases {
        let sidecar = sidecar_with(body.clone()).await;
        let app = app_with_sidecar(URL, &sidecar).await;
        let (client, _) = admin(&app).await;

        login(&app, &client, false).await;

        let after = status(&client).await;
        assert_eq!(after["syncStatus"], "FAILED", "{body}");
        assert_eq!(after["lastSyncError"], code, "{body}");
        assert_eq!(
            count(&app, "SELECT count(*) FROM account").await,
            0,
            "{body}"
        );
    }
}

#[tokio::test]
async fn remembered_credentials_are_stored_encrypted_and_never_shown() {
    let sidecar = sidecar_with(two_funds()).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let response = login(&app, &client, true).await;

    assert_eq!(response.json()["credentialsRemembered"], true);
    assert!(!response.text().contains("secret"));
    let session = session_of(&app, BANK, member_id).await.unwrap();
    let stored = session.encrypted_credentials.unwrap();
    assert!(!stored.contains("secret"));
    let plain: Value = serde_json::from_str(&app.state.crypto.decrypt(&stored).unwrap()).unwrap();
    assert_eq!(plain, json!({ "login": "C123", "password": "secret" }));
}

#[tokio::test]
async fn a_login_without_remember_drops_the_credentials_an_earlier_one_stored() {
    let sidecar = sidecar_with(two_funds()).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    login(&app, &client, true).await;

    let response = client
        .post("/api/corum/auth")
        .json(&json!({ "login": "C123", "password": "secret" }))
        .send()
        .await;
    settle(&app).await;

    assert_eq!(response.json()["credentialsRemembered"], false);
    assert!(
        session_of(&app, BANK, member_id)
            .await
            .unwrap()
            .encrypted_credentials
            .is_none()
    );
}

/// /portfolio answers SESSION_EXPIRED to state-1 and the portfolio to state-2.
async fn expiring_sidecar(relogin: ResponseTemplate) -> MockServer {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "sessionState": "state-1" })),
        )
        .up_to_n_times(1)
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_partial_json(
            json!({ "login": "C123", "password": "secret" }),
        ))
        .respond_with(relogin)
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/portfolio"))
        .and(body_partial_json(json!({ "sessionState": "state-1" })))
        .respond_with(ResponseTemplate::new(401))
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/portfolio"))
        .and(body_partial_json(json!({ "sessionState": "state-2" })))
        .respond_with(ResponseTemplate::new(200).set_body_json(two_funds()))
        .mount(&sidecar)
        .await;
    sidecar
}

#[tokio::test]
async fn an_expired_session_logs_in_again_with_the_remembered_credentials() {
    let sidecar = expiring_sidecar(
        ResponseTemplate::new(200).set_body_json(json!({ "sessionState": "state-2" })),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    login(&app, &client, true).await;

    let after = status(&client).await;
    assert_eq!(after["syncStatus"], "SUCCESS", "{after}");
    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert_eq!(
        app.state.crypto.decrypt(&session.session_state).unwrap(),
        "state-2"
    );
    assert_eq!(scpi_accounts(&app).await.len(), 2);
}

#[tokio::test]
async fn without_remembered_credentials_an_expired_session_waits_for_the_member() {
    let sidecar = expiring_sidecar(
        ResponseTemplate::new(200).set_body_json(json!({ "sessionState": "state-2" })),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    login(&app, &client, false).await;

    let after = status(&client).await;
    assert_eq!(after["isActive"], false);
    assert_eq!(after["lastSyncError"], "SESSION_EXPIRED");
}

#[tokio::test]
async fn a_rejected_remembered_password_is_cleared_and_ends_the_session() {
    let sidecar = expiring_sidecar(
        ResponseTemplate::new(401).set_body_json(json!({ "detail": "INVALID_CREDENTIALS" })),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    login(&app, &client, true).await;

    let after = status(&client).await;
    assert_eq!(after["isActive"], false);
    assert_eq!(after["lastSyncError"], "INVALID_CREDENTIALS");
    assert_eq!(after["credentialsRemembered"], false);
    assert!(
        session_of(&app, BANK, member_id)
            .await
            .unwrap()
            .encrypted_credentials
            .is_none()
    );
}

#[tokio::test]
async fn unreadable_remembered_credentials_are_cleared() {
    let sidecar = MockServer::start().await;
    fail(
        &sidecar,
        "/portfolio",
        401,
        json!({ "detail": "SESSION_EXPIRED" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "state-1").await;
    let damaged = app.state.crypto.encrypt(r#"{"login":"C123"}"#).unwrap();
    sqlx::query("UPDATE sidecar_session SET encrypted_credentials = $1")
        .bind(damaged)
        .execute(&app.state.db)
        .await
        .unwrap();

    client.post("/api/corum/sync").send().await;
    settle(&app).await;

    let after = status(&client).await;
    assert_eq!(after["lastSyncError"], "SESSION_EXPIRED");
    assert_eq!(after["credentialsRemembered"], false);
    assert!(
        sidecar
            .received_requests()
            .await
            .unwrap()
            .iter()
            .all(|r| r.url.path() == "/portfolio")
    );
}

#[tokio::test]
async fn re_logins_past_the_member_budget_keep_the_session_and_the_credentials() {
    let sidecar = MockServer::start().await;
    fail(
        &sidecar,
        "/portfolio",
        401,
        json!({ "detail": "SESSION_EXPIRED" }),
    )
    .await;
    answer(&sidecar, "/initiate", json!({ "sessionState": "state-1" })).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "state-1").await;
    let credentials = app
        .state
        .crypto
        .encrypt(r#"{"login":"C123","password":"secret"}"#)
        .unwrap();
    sqlx::query("UPDATE sidecar_session SET encrypted_credentials = $1")
        .bind(credentials)
        .execute(&app.state.db)
        .await
        .unwrap();

    // Each run re-logs in, then the new session expires too; the sixth run finds the budget spent.
    for _ in 0..6 {
        sqlx::query(
            "UPDATE sidecar_session SET is_active = true, sync_status = 'IDLE', last_sync_error = NULL",
        )
        .execute(&app.state.db)
        .await
        .unwrap();
        picsou::connectors::corum::queue_sync(&app.state, member_id)
            .await
            .unwrap();
        settle(&app).await;
    }

    let after = status(&client).await;
    assert_eq!(after["lastSyncError"], "UPSTREAM_UNAVAILABLE");
    assert_eq!(after["isActive"], true);
    assert_eq!(after["credentialsRemembered"], true);
    let logins = sidecar
        .received_requests()
        .await
        .unwrap()
        .iter()
        .filter(|r| r.url.path() == "/initiate")
        .count();
    assert_eq!(logins, 5);
}

#[tokio::test]
async fn a_disconnect_while_the_login_waits_on_corum_stores_nothing() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({ "sessionState": "state-1" }))
                .set_delay(Duration::from_millis(500)),
        )
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let pending = client
        .post("/api/corum/auth")
        .json(&json!({ "login": "C123", "password": "secret", "remember": true }))
        .send();
    let disconnect = async {
        tokio::time::sleep(Duration::from_millis(150)).await;
        client.delete("/api/corum/session").send().await
    };
    let (response, deleted) = tokio::join!(pending, disconnect);

    assert_eq!(deleted.status, 204);
    assert_eq!(response.status, 422);
    assert_eq!(response.json()["code"], "SESSION_EXPIRED");
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

#[tokio::test]
async fn the_body_is_validated_before_the_rate_limit_and_the_sixth_login_is_refused() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/initiate", 401, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let invalid = client
        .post("/api/corum/auth")
        .json(&json!({ "login": "x".repeat(101), "password": " " }))
        .send()
        .await;
    assert_eq!(invalid.status, 422);
    assert_eq!(
        invalid.json()["errors"]["login"],
        "size must be between 0 and 100"
    );
    assert_eq!(invalid.json()["errors"]["password"], "must not be blank");
    for _ in 0..5 {
        let rejected = client
            .post("/api/corum/auth")
            .json(&json!({ "login": "C123", "password": "wrong" }))
            .send()
            .await;
        assert_eq!(rejected.status, 422);
        assert_eq!(rejected.json()["code"], "INVALID_CREDENTIALS");
    }
    let limited = client
        .post("/api/corum/auth")
        .json(&json!({ "login": "C123", "password": "wrong" }))
        .send()
        .await;

    assert_eq!(limited.status, 429);
    assert_eq!(
        limited.json()["detail"],
        "Too many CORUM authentication attempts. Please wait before retrying."
    );
    assert_eq!(sidecar.received_requests().await.unwrap().len(), 5);
}

#[tokio::test]
async fn sync_without_a_session_asks_to_reconnect_and_sessions_stay_per_member() {
    let sidecar = sidecar_with(two_funds()).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let (other, _) = member(&app, "bob").await;
    login(&app, &client, true).await;

    let foreign_sync = other.post("/api/corum/sync").send().await;
    let foreign_status = other.get("/api/corum/status").send().await.json();
    let foreign_delete = other.delete("/api/corum/session").send().await;

    assert_eq!(foreign_sync.status, 422);
    assert_eq!(foreign_sync.json()["code"], "SESSION_EXPIRED");
    assert_eq!(
        foreign_status,
        json!({ "isActive": false, "syncStatus": "IDLE", "lifeAmbiguousCount": 0, "credentialsRemembered": false })
    );
    assert_eq!(foreign_delete.status, 204);
    assert!(session_of(&app, BANK, member_id).await.is_some());
    assert_eq!(client.delete("/api/corum/session").send().await.status, 204);
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

fn life_portfolio(operations: Value) -> Value {
    portfolio(
        Value::Null,
        vec![contract(
            "AV-1",
            vec![
                life_line("CXL", "10", "100", "1000", Some("900")),
                json!({
                    "productCode": "EURO", "label": "Fonds euros", "quantity": n("500"),
                    "unitPrice": n("1"), "valueEur": n("500"), "netInvestedEur": n("500"),
                    "valuationDate": "2026-01-10", "euroFund": true, "assetClass": "euroLife"
                }),
            ],
            "1500",
            "1400",
            operations,
        )],
    )
}

fn life_operations() -> Value {
    json!([
        operation("1", "scheduledPayment", "2026-01-05", "150"),
        operation("2", "administrativeManagementCosts", "2026-01-06", "-3.50"),
        { "operationId": "3", "type": "couponDetachment", "category": null, "date": "2026-01-07",
          "amountEur": n("12"), "fundCodes": ["cxl"] },
        operation("4", "somethingNew", "2026-01-08", "1"),
    ])
}

#[tokio::test]
async fn a_life_contract_becomes_one_assurance_vie_account_with_its_lines_and_operations() {
    let sidecar = sidecar_with(life_portfolio(life_operations())).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    login(&app, &client, false).await;
    client.post("/api/corum/sync").send().await;
    settle(&app).await;

    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
    let account: (
        String,
        String,
        Decimal,
        bool,
        Decimal,
        Option<String>,
        String,
    ) = sqlx::query_as(
        "SELECT name, external_account_id, current_balance, is_manual, provider_invested_eur, \
         scheduled_payment_cadence, opened_at::text FROM account",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        account,
        (
            "CORUM Life".into(),
            "corum_life_AV-1".into(),
            d("1500"),
            false,
            d("1400"),
            Some("MONTHLY".into()),
            "2020-05-04".into()
        )
    );
    type HoldingRow = (
        String,
        Decimal,
        Option<Decimal>,
        Option<Decimal>,
        Option<String>,
    );
    let holdings: Vec<HoldingRow> = sqlx::query_as(
        "SELECT ticker, provider_value_eur, average_buy_in, provider_pnl_eur, provider_wealth_tier \
             FROM account_holding ORDER BY ticker",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        holdings,
        vec![
            (
                "CORUM-LIFE-CXL".into(),
                d("1000"),
                Some(d("90")),
                Some(d("100")),
                Some("REAL_ESTATE".into())
            ),
            (
                "CORUM-LIFE-EURO".into(),
                d("500"),
                Some(d("1")),
                Some(d("0")),
                None
            ),
        ]
    );
    type TxRow = (
        String,
        Option<String>,
        Option<String>,
        bool,
        Option<String>,
        bool,
    );
    let rows: Vec<TxRow> = sqlx::query_as(
        "SELECT t.external_id, t.tx_type, t.ticker, t.budget_neutral, c.slug, t.is_manual \
         FROM \"transaction\" t LEFT JOIN category c ON c.id = t.category_id ORDER BY t.external_id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (
                "corum:1".into(),
                Some("DEPOSIT".into()),
                None,
                false,
                Some("investissement".into()),
                false
            ),
            (
                "corum:2".into(),
                Some("FEE".into()),
                None,
                true,
                None,
                false
            ),
            (
                "corum:3".into(),
                Some("DIVIDEND".into()),
                Some("CORUM-LIFE-CXL".into()),
                true,
                None,
                false
            ),
            ("corum:4".into(), None, None, true, None, false),
        ]
    );
    let snapshot: (Decimal, Decimal) =
        sqlx::query_as("SELECT balance, invested_amount FROM balance_snapshot")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(snapshot, (d("1500"), d("1400")));
}

#[tokio::test]
async fn a_resync_keeps_the_member_category_and_drops_operations_corum_no_longer_reports() {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/initiate", json!({ "sessionState": "state-1" })).await;
    Mock::given(method("POST"))
        .and(path("/portfolio"))
        .respond_with(ResponseTemplate::new(200).set_body_json(life_portfolio(life_operations())))
        .up_to_n_times(1)
        .mount(&sidecar)
        .await;
    answer(
        &sidecar,
        "/portfolio",
        life_portfolio(json!([
            operation("1", "scheduledPayment", "2026-01-05", "150"),
            operation("2", "administrativeManagementCosts", "2026-01-06", "-3.50"),
        ])),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    login(&app, &client, false).await;
    sqlx::query("UPDATE \"transaction\" SET category_id = NULL, category_manual = true WHERE external_id = 'corum:1'")
        .execute(&app.state.db)
        .await
        .unwrap();

    client.post("/api/corum/sync").send().await;
    settle(&app).await;

    let rows: Vec<(String, Option<i64>, bool)> = sqlx::query_as(
        "SELECT external_id, category_id, category_manual FROM \"transaction\" ORDER BY external_id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            ("corum:1".into(), None, true),
            ("corum:2".into(), None, false)
        ]
    );
}

#[tokio::test]
async fn a_manual_corum_life_account_is_adopted_unless_it_holds_lines_typed_by_hand() {
    for with_holding in [false, true] {
        let sidecar = sidecar_with(life_portfolio(Value::Null)).await;
        let app = app_with_sidecar(URL, &sidecar).await;
        let (client, member_id) = admin(&app).await;
        let manual: i64 = sqlx::query_scalar(
            "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
             VALUES ($1, 'Corum life', 'ASSURANCE_VIE', 'EUR', 0, true, '#000') RETURNING id",
        )
        .bind(member_id)
        .fetch_one(&app.state.db)
        .await
        .unwrap();
        if with_holding {
            sqlx::query(
                "INSERT INTO account_holding (account_id, ticker, quantity) VALUES ($1, 'MINE', 1)",
            )
            .bind(manual)
            .execute(&app.state.db)
            .await
            .unwrap();
        }

        login(&app, &client, false).await;

        let after = status(&client).await;
        assert_eq!(after["syncStatus"], "SUCCESS");
        let external: Option<String> =
            sqlx::query_scalar("SELECT external_account_id FROM account WHERE id = $1")
                .bind(manual)
                .fetch_one(&app.state.db)
                .await
                .unwrap();
        if with_holding {
            assert_eq!(after["lifeAmbiguousCount"], 1);
            assert_eq!(external, None);
            assert_eq!(count(&app, "SELECT count(*) FROM account").await, 1);
        } else {
            assert_eq!(after["lifeAmbiguousCount"], 0);
            assert_eq!(external.as_deref(), Some("corum_life_AV-1"));
        }
    }
}

#[tokio::test]
async fn a_deleted_life_account_is_not_brought_back() {
    let sidecar = sidecar_with(life_portfolio(Value::Null)).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    login(&app, &client, false).await;
    sqlx::query("UPDATE account SET deleted_at = now()")
        .execute(&app.state.db)
        .await
        .unwrap();

    client.post("/api/corum/sync").send().await;
    settle(&app).await;

    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 1);
    assert_eq!(
        count(
            &app,
            "SELECT count(*) FROM account WHERE deleted_at IS NULL"
        )
        .await,
        0
    );
}

#[tokio::test]
async fn a_refused_life_contract_rolls_back_the_scpi_funds_of_the_same_run() {
    let mut body = life_portfolio(Value::Null);
    body["realEstate"] = two_funds()["realEstate"].clone();
    body["life"][0]["holdings"][1]["unitPrice"] = n("1.01");
    let sidecar = sidecar_with(body).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    login(&app, &client, false).await;

    let after = status(&client).await;
    assert_eq!(after["lastSyncError"], "INVALID_DATA");
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 0);
    assert_eq!(
        count(&app, "SELECT count(*) FROM scpi_provider_fund").await,
        0
    );
}

#[tokio::test]
async fn line_costs_that_miss_the_contract_cost_are_dropped() {
    let mut body = life_portfolio(Value::Null);
    body["life"][0]["netInvestedEur"] = n("1300");
    let sidecar = sidecar_with(body).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    login(&app, &client, false).await;

    let costs: Vec<(Option<Decimal>, Option<Decimal>)> =
        sqlx::query_as("SELECT average_buy_in, provider_pnl_eur FROM account_holding")
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(costs, vec![(None, None), (None, None)]);
    let invested: Decimal = sqlx::query_scalar("SELECT invested_amount FROM balance_snapshot")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(invested, d("1300"));
}

#[tokio::test]
async fn the_daily_sync_queues_an_active_session_and_reports_an_inactive_one() {
    let sidecar = sidecar_with(two_funds()).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (_, member_id) = admin(&app).await;

    let none = picsou::connectors::corum::resync_reporting(&app.state, member_id).await;
    store_session(&app, BANK, member_id, "state-1").await;
    let queued = picsou::connectors::corum::resync_reporting(&app.state, member_id).await;
    settle(&app).await;

    assert_eq!(none.message, "No active session");
    assert_eq!(queued.source, "corum");
    assert_eq!(queued.message, "");
    assert_eq!(scpi_accounts(&app).await.len(), 2);
}
