//! SofidyController and SofidySyncService over HTTP, the sidecar stubbed: the two-step login
//! bound to its member by an encrypted reservation, and the snapshot written onto SCPI accounts.

use picsou::ledger::sidecar::SidecarBank;
use picsou::testing::{Client, TestApp};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use std::time::Duration;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{
    admin, answer, app_with_sidecar, count, d, member, n, session_of, settle, store_session,
};

const URL: &str = "SOFIDY_AUTH_URL";
const BANK: SidecarBank = SidecarBank::Sofidy;

fn holding(code: &str, label: &str, quantity: &str, price: Option<&str>) -> Value {
    json!({
        "fundCode": code, "label": label, "quantity": n(quantity),
        "withdrawalPrice": price.map(n), "totalEur": n("0")
    })
}

fn snapshot(holdings: Vec<Value>, total: &str) -> Value {
    json!({
        "currency": "EUR", "totalEur": n(total), "valuationDate": "2026-01-10",
        "snapshotComplete": true, "holdings": holdings
    })
}

async fn pending_sidecar() -> MockServer {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "p-1", "mfaRequired": true, "mfaType": "email" }),
    )
    .await;
    answer(&sidecar, "/complete", json!({ "sessionState": "state-1" })).await;
    sidecar
}

async fn initiate(client: &Client) -> picsou::testing::Response {
    client
        .post("/api/sofidy/auth/initiate")
        .json(&json!({ "associateCode": "123456", "password": "secret" }))
        .send()
        .await
}

async fn complete(client: &Client, process_id: &str) -> picsou::testing::Response {
    client
        .post("/api/sofidy/auth/complete")
        .json(&json!({ "processId": process_id, "code": "654321" }))
        .send()
        .await
}

async fn status(client: &Client) -> Value {
    client.get("/api/sofidy/status").send().await.json()
}

async fn complete_calls(sidecar: &MockServer) -> usize {
    sidecar
        .received_requests()
        .await
        .unwrap()
        .iter()
        .filter(|r| r.url.path() == "/complete")
        .count()
}

/// A Sofidy-linked SCPI account holding \`shares\` of \`code\`, valued by hand.
async fn linked_position(
    app: &TestApp,
    member_id: i64,
    code: &str,
    shares: &str,
    subscription: Option<&str>,
) -> i64 {
    let account: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, $2, 'SCPI', 'EUR', 5000, true, '#000') RETURNING id",
    )
    .bind(member_id)
    .bind(format!("Fund {code}"))
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO scpi_position (account_id, member_id, share_count, subscription_price_eur, withdrawal_price_eur, \
         sofidy_fund_code, valuation_status) VALUES ($1, $2, $3::numeric, $4::numeric, 250, $5, 'OK')",
    )
    .bind(account)
    .bind(member_id)
    .bind(shares)
    .bind(subscription)
    .bind(code)
    .execute(&app.state.db)
    .await
    .unwrap();
    account
}

async fn sync_with(
    app: &TestApp,
    client: &Client,
    member_id: i64,
    body: Value,
    sidecar: &MockServer,
) -> Value {
    answer(sidecar, "/positions", body).await;
    store_session(app, BANK, member_id, "state-1").await;
    let queued = client.post("/api/sofidy/sync").send().await;
    assert_eq!(queued.status, 202, "{}", queued.text());
    settle(app).await;
    status(client).await
}

async fn balance(app: &TestApp, account: i64) -> Decimal {
    sqlx::query_scalar("SELECT current_balance FROM account WHERE id = $1")
        .bind(account)
        .fetch_one(&app.state.db)
        .await
        .unwrap()
}

#[tokio::test]
async fn the_login_asks_for_the_emailed_code_and_its_completion_imports_the_portfolio() {
    let sidecar = pending_sidecar().await;
    answer(
        &sidecar,
        "/positions",
        snapshot(vec![holding("DY", "SOFIDYNAMIC", "4", Some("250"))], "1000"),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let started = initiate(&client).await;
    assert_eq!(started.status, 200, "{}", started.text());
    assert_eq!(
        started.json(),
        json!({ "processId": "p-1", "mfaRequired": true, "mfaType": "email", "isActive": false, "syncStatus": "IDLE" })
    );
    assert_eq!(status(&client).await["isActive"], false);
    let completed = complete(&client, "p-1").await;
    settle(&app).await;

    assert_eq!(completed.status, 200, "{}", completed.text());
    assert_eq!(completed.json()["syncStatus"], "QUEUED");
    let after = status(&client).await;
    assert_eq!(after["syncStatus"], "SUCCESS", "{after}");
    let account: (String, Decimal, String) =
        sqlx::query_as("SELECT name, current_balance, provider FROM account")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(account, ("SOFIDYNAMIC".into(), d("1000"), "Sofidy".into()));
    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert_eq!(
        app.state.crypto.decrypt(&session.session_state).unwrap(),
        "state-1"
    );
}

#[tokio::test]
async fn another_members_process_is_refused_before_the_sidecar_is_called() {
    let sidecar = pending_sidecar().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let (other, _) = member(&app, "bob").await;
    initiate(&client).await;

    let foreign = complete(&other, "p-1").await;

    assert_eq!(foreign.status, 422);
    assert_eq!(foreign.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    assert_eq!(
        foreign.json()["detail"],
        "This Sofidy login attempt is no longer valid. Please reconnect."
    );
    assert_eq!(complete_calls(&sidecar).await, 0);
    assert_eq!(complete(&client, "p-1").await.status, 200);
    settle(&app).await;
    assert!(session_of(&app, BANK, member_id).await.unwrap().active);
    let consumed = complete(&client, "p-1").await;
    assert_eq!(consumed.json()["code"], "AUTH_ATTEMPT_EXPIRED");
}

#[tokio::test]
async fn a_disconnected_replaced_or_stale_attempt_is_refused_before_the_sidecar() {
    let sidecar = pending_sidecar().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    initiate(&client).await;
    client.delete("/api/sofidy/session").send().await;
    let disconnected = complete(&client, "p-1").await;
    initiate(&client).await;
    let wrong_process = complete(&client, "p-0").await;
    app.clock.advance(Duration::from_secs(6 * 60));
    let stale = complete(&client, "p-1").await;

    for response in [disconnected, wrong_process, stale] {
        assert_eq!(response.status, 422);
        assert_eq!(response.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    }
    assert_eq!(complete_calls(&sidecar).await, 0);
}

#[tokio::test]
async fn a_portal_that_asks_for_no_code_stores_the_session_at_once() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "mfaRequired": false, "sessionState": "state-1" }),
    )
    .await;
    answer(&sidecar, "/positions", snapshot(vec![], "0")).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let started = initiate(&client).await;
    settle(&app).await;

    assert_eq!(
        started.json(),
        json!({ "mfaRequired": false, "isActive": true, "syncStatus": "QUEUED" })
    );
    assert!(session_of(&app, BANK, member_id).await.unwrap().active);
    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
}

#[tokio::test]
async fn a_code_without_a_process_or_a_session_without_a_code_is_invalid_data() {
    for body in [
        json!({ "mfaRequired": true }),
        json!({ "mfaRequired": false, "sessionState": " " }),
    ] {
        let sidecar = MockServer::start().await;
        answer(&sidecar, "/initiate", body.clone()).await;
        let app = app_with_sidecar(URL, &sidecar).await;
        let (client, _) = admin(&app).await;

        let response = initiate(&client).await;

        assert_eq!(response.status, 422, "{body}");
        assert_eq!(response.json()["code"], "INVALID_DATA", "{body}");
    }
}

#[tokio::test]
async fn validation_runs_before_the_rate_limit_and_the_sixth_attempt_is_refused() {
    let sidecar = pending_sidecar().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let bad_code = client
        .post("/api/sofidy/auth/initiate")
        .json(&json!({ "associateCode": "12345", "password": "x".repeat(101) }))
        .send()
        .await;
    let bad_complete = client
        .post("/api/sofidy/auth/complete")
        .json(&json!({ "processId": "", "code": "12a456" }))
        .send()
        .await;
    assert_eq!(bad_code.status, 422);
    assert_eq!(
        bad_code.json()["errors"]["associateCode"],
        r#"must match "\d{6}""#
    );
    assert_eq!(
        bad_code.json()["errors"]["password"],
        "size must be between 0 and 100"
    );
    assert_eq!(
        bad_complete.json()["errors"]["code"],
        r#"must match "\d{6}""#
    );
    assert_eq!(
        bad_complete.json()["errors"]["processId"],
        "must not be blank"
    );
    for _ in 0..3 {
        assert_eq!(initiate(&client).await.status, 200);
    }
    assert_eq!(complete(&client, "nope").await.status, 422);
    assert_eq!(complete(&client, "nope").await.status, 422);
    let limited = initiate(&client).await;

    assert_eq!(limited.status, 429);
    assert_eq!(
        limited.json()["detail"],
        "Too many Sofidy authentication attempts. Please wait before retrying."
    );
}

#[tokio::test]
async fn a_linked_fund_no_longer_listed_is_closed_at_zero_and_a_listed_one_updated() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let kept = linked_position(&app, member_id, "DY", "4", Some("300")).await;
    let sold = linked_position(&app, member_id, "EF", "8", None).await;

    let after = sync_with(
        &app,
        &client,
        member_id,
        snapshot(vec![holding("DY", "SOFIDYNAMIC", "5", Some("260"))], "1300"),
        &sidecar,
    )
    .await;

    assert_eq!(after["syncStatus"], "SUCCESS", "{after}");
    assert_eq!(balance(&app, kept).await, d("1300"));
    assert_eq!(balance(&app, sold).await, d("0"));
    let positions: Vec<(String, Decimal, Option<Decimal>)> = sqlx::query_as(
        "SELECT sofidy_fund_code, share_count, subscription_price_eur FROM scpi_position ORDER BY sofidy_fund_code",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        positions,
        vec![
            ("DY".into(), d("5"), Some(d("300"))),
            ("EF".into(), d("0"), None)
        ]
    );
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 2);
}

#[tokio::test]
async fn a_complete_empty_portfolio_is_a_full_exit_even_without_a_price() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let sold = linked_position(&app, member_id, "DY", "4", None).await;
    sqlx::query("UPDATE scpi_position SET withdrawal_price_eur = NULL")
        .execute(&app.state.db)
        .await
        .unwrap();

    let after = sync_with(&app, &client, member_id, snapshot(vec![], "0"), &sidecar).await;

    assert_eq!(after["syncStatus"], "SUCCESS", "{after}");
    assert_eq!(balance(&app, sold).await, d("0"));
    let status: String = sqlx::query_scalar("SELECT valuation_status FROM scpi_position")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(status, "OK");
}

#[tokio::test]
async fn snapshots_that_cannot_be_written_close_nothing() {
    let mut incomplete = snapshot(vec![], "0");
    incomplete["snapshotComplete"] = json!(false);
    let cases = [
        (incomplete, "PORTFOLIO_INCOMPLETE"),
        (
            snapshot(vec![holding("DY", "D", "5", Some("260"))], "1299.98"),
            "PORTFOLIO_INCOMPLETE",
        ),
        (
            json!({ "currency": "EUR", "totalEur": n("0"), "snapshotComplete": true }),
            "PORTFOLIO_INCOMPLETE",
        ),
        (
            snapshot(
                vec![json!({ "fundCode": "DY", "label": "D", "quantity": null })],
                "0",
            ),
            "INVALID_DATA",
        ),
        (
            snapshot(
                vec![holding("DY", "D", "1", None), holding("DY", "D", "1", None)],
                "0",
            ),
            "INVALID_DATA",
        ),
        (
            json!({ "currency": "USD", "totalEur": n("0"), "snapshotComplete": true, "holdings": [] }),
            "INVALID_DATA",
        ),
    ];
    for (body, code) in cases {
        let sidecar = MockServer::start().await;
        let app = app_with_sidecar(URL, &sidecar).await;
        let (client, member_id) = admin(&app).await;
        let linked = linked_position(&app, member_id, "EF", "8", None).await;

        let after = sync_with(&app, &client, member_id, body.clone(), &sidecar).await;

        assert_eq!(after["lastSyncError"], code, "{body}");
        assert_eq!(balance(&app, linked).await, d("5000"), "{body}");
    }
}

#[tokio::test]
async fn an_expired_session_ends_and_sync_without_one_asks_to_reconnect() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/positions"))
        .respond_with(ResponseTemplate::new(401))
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let none = client.post("/api/sofidy/sync").send().await;
    store_session(&app, BANK, member_id, "state-1").await;
    client.post("/api/sofidy/sync").send().await;
    settle(&app).await;
    let again = client.post("/api/sofidy/sync").send().await;

    assert_eq!(none.status, 422);
    assert_eq!(
        none.json()["detail"],
        "No active Sofidy session. Please reconnect."
    );
    let after = status(&client).await;
    assert_eq!(after["isActive"], false);
    assert_eq!(after["lastSyncError"], "SESSION_EXPIRED");
    assert_eq!(
        again.json()["detail"],
        "The Sofidy session expired. Please reconnect."
    );
}

#[tokio::test]
async fn a_pending_login_reads_as_disconnected_and_cannot_be_synced() {
    let sidecar = pending_sidecar().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    initiate(&client).await;

    let current = status(&client).await;
    let sync = client.post("/api/sofidy/sync").send().await;

    assert_eq!(current, json!({ "isActive": false, "syncStatus": "IDLE" }));
    assert_eq!(sync.status, 422);
    assert_eq!(sync.json()["code"], "SESSION_EXPIRED");
}
