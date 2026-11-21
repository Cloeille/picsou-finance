//! CicController and CicSyncService over HTTP, the sidecar stubbed.

use picsou::ledger::sidecar::SidecarBank;
use picsou::testing::{Client, TestApp};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{body_partial_json, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{
    admin, answer, app_with_sidecar, count, d, fail, member, n, session_of, settle,
};

const URL: &str = "CIC_AUTH_URL";
const BANK: SidecarBank = SidecarBank::Cic;
const LOGIN: &str = "12345678";

fn account(id: &str, kind: &str, balance: &str) -> Value {
    json!({ "externalId": id, "name": format!("Compte {id}"), "type": kind,
            "balanceEur": n(balance), "snapshotComplete": true })
}

fn listing(accounts: Vec<Value>, categories: &[&str]) -> Value {
    json!({ "accounts": accounts, "categoriesRead": categories })
}

async fn status(client: &Client) -> Value {
    client.get("/api/cic/status").send().await.json()
}

/// A typed login answered by a mobile confirmation, then the first import.
async fn login(sidecar: &MockServer, app: &TestApp, client: &Client) {
    answer(
        sidecar,
        "/initiate",
        json!({ "processId": "p-1", "mfaRequired": true, "mfaType": "APP_PUSH" }),
    )
    .await;
    answer(sidecar, "/complete", json!({ "sessionState": "state-1" })).await;
    let initiated = client
        .post("/api/cic/auth/initiate")
        .json(&json!({ "login": LOGIN, "password": "secret" }))
        .send()
        .await;
    assert_eq!(initiated.status, 200, "{}", initiated.text());
    assert_eq!(
        initiated.json(),
        json!({ "processId": "p-1", "mfaRequired": true, "mfaType": "APP_PUSH" })
    );
    let completed = client
        .post("/api/cic/auth/complete")
        .json(&json!({ "processId": "p-1" }))
        .send()
        .await;
    assert_eq!(completed.status, 200, "{}", completed.text());
    settle(app).await;
}

async fn insert_account(app: &TestApp, member_id: i64, id: &str, kind: &str, balance: &str) {
    sqlx::query(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, external_account_id, \
         is_manual, color) VALUES ($1, $2, $3::account_type, 'CIC', 'EUR', $4, $5, false, '#000')",
    )
    .bind(member_id)
    .bind(id)
    .bind(kind)
    .bind(d(balance))
    .bind(id)
    .execute(&app.state.db)
    .await
    .unwrap();
}

async fn balances(app: &TestApp) -> Vec<(String, Decimal)> {
    sqlx::query_as(
        "SELECT external_account_id, current_balance FROM account ORDER BY external_account_id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

/// Stores a session with stored credentials, as a completed login leaves it.
async fn store_with_credentials(app: &TestApp, member_id: i64, plain_state: &str) {
    let state = app.state.crypto.encrypt(plain_state).unwrap();
    let credentials = app
        .state
        .crypto
        .encrypt(&json!({ "login": LOGIN, "password": "secret" }).to_string())
        .unwrap();
    sqlx::query(
        "INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, last_validated_at, \
         is_active, sync_status) VALUES ('CIC', $1, $2, $3, now(), true, 'IDLE')",
    )
    .bind(member_id)
    .bind(state)
    .bind(credentials)
    .execute(&app.state.db)
    .await
    .unwrap();
}

#[tokio::test]
async fn status_without_a_session_is_inactive_and_cannot_reconnect() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    assert_eq!(
        status(&client).await,
        json!({ "isActive": false, "syncStatus": "IDLE", "canReconnect": false })
    );
}

#[tokio::test]
async fn a_confirmed_login_stores_the_credentials_and_imports_each_account_with_its_type() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/accounts/listing",
        listing(
            vec![
                account("1001", "CHECKING", "1234.56"),
                account("cic_2002", "LIVRET_A", "5000.00"),
            ],
            &["CHECKING", "SAVINGS"],
        ),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    login(&sidecar, &app, &client).await;

    let session = session_of(&app, BANK, member_id).await.expect("session");
    let credentials = app
        .state
        .crypto
        .decrypt(session.encrypted_credentials.as_deref().unwrap())
        .unwrap();
    let parsed: Value = serde_json::from_str(&credentials).unwrap();
    assert_eq!(parsed, json!({ "login": LOGIN, "password": "secret" }));
    let current = status(&client).await;
    assert_eq!(current["syncStatus"], "SUCCESS");
    assert_eq!(current["canReconnect"], true);
    let rows: Vec<(String, String, String, Decimal, Option<Decimal>, bool)> = sqlx::query_as(
        "SELECT external_account_id, type::text, color, current_balance, cash_balance, is_manual FROM account \
         ORDER BY external_account_id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (
                "cic_1001".into(),
                "CHECKING".into(),
                "#ec4899".into(),
                d("1234.56"),
                None,
                false
            ),
            (
                "cic_2002".into(),
                "LIVRET_A".into(),
                "#f59e0b".into(),
                d("5000.00"),
                None,
                false
            ),
        ]
    );
    assert_eq!(
        count(&app, "SELECT count(*) FROM balance_snapshot").await,
        2
    );
}

#[tokio::test]
async fn an_account_type_outside_current_and_savings_fails_the_import() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/accounts/listing",
        listing(vec![account("1", "PEA", "10")], &["SAVINGS"]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    login(&sidecar, &app, &client).await;

    let current = status(&client).await;
    assert_eq!(current["syncStatus"], "FAILED");
    assert_eq!(current["lastSyncError"], "INVALID_DATA");
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 0);
}

#[tokio::test]
async fn every_invalid_listing_is_refused_whole() {
    let cases = [
        (
            json!({ "accounts": [], "categoriesRead": ["SAVINGS"] }),
            "PORTFOLIO_INCOMPLETE",
        ),
        (
            json!({ "categoriesRead": ["SAVINGS"] }),
            "PORTFOLIO_INCOMPLETE",
        ),
        (
            listing(
                vec![
                    json!({ "externalId": "1", "name": "A", "type": "CHECKING", "balanceEur": n("1"),
                             "snapshotComplete": false }),
                ],
                &["CHECKING"],
            ),
            "PORTFOLIO_INCOMPLETE",
        ),
        (
            listing(
                vec![
                    account("1", "CHECKING", "1"),
                    account("cic_1", "CHECKING", "2"),
                ],
                &["CHECKING"],
            ),
            "INVALID_DATA",
        ),
        (
            listing(
                vec![
                    json!({ "externalId": " ", "type": "CHECKING", "balanceEur": n("1"),
                                 "snapshotComplete": true }),
                ],
                &[],
            ),
            "INVALID_DATA",
        ),
        (
            listing(
                vec![json!({ "externalId": "1", "type": "CHECKING", "snapshotComplete": true })],
                &[],
            ),
            "INVALID_DATA",
        ),
        (
            listing(
                vec![
                    json!({ "externalId": "1", "type": "NOT_A_TYPE", "balanceEur": n("1"),
                                 "snapshotComplete": true }),
                ],
                &[],
            ),
            "INVALID_DATA",
        ),
        (json!({ "accounts": [null] }), "INTERNAL_ERROR"),
    ];
    for (body, code) in cases {
        let sidecar = MockServer::start().await;
        answer(&sidecar, "/accounts/listing", body.clone()).await;
        let app = app_with_sidecar(URL, &sidecar).await;
        let (client, member_id) = admin(&app).await;
        insert_account(&app, member_id, "cic_old", "CHECKING", "42").await;

        login(&sidecar, &app, &client).await;

        let current = status(&client).await;
        assert_eq!(current["lastSyncError"], code, "{body}");
        assert_eq!(
            balances(&app).await,
            vec![("cic_old".into(), d("42"))],
            "{body}"
        );
    }
}

#[tokio::test]
async fn an_older_sidecar_without_the_listing_route_is_read_from_its_bare_list() {
    let sidecar = MockServer::start().await;
    fail(
        &sidecar,
        "/accounts/listing",
        404,
        json!({ "detail": "Not Found" }),
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/accounts"))
        .and(body_partial_json(json!({ "sessionState": "state-1" })))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!([account("1", "LIVRET_A", "10")])),
        )
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    // No tables reported: the categories of the returned accounts decide what may close.
    insert_account(&app, member_id, "cic_gone", "LDDS", "20").await;
    insert_account(&app, member_id, "cic_checking", "CHECKING", "30").await;

    login(&sidecar, &app, &client).await;

    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
    assert_eq!(
        balances(&app).await,
        vec![
            ("cic_1".into(), d("10")),
            ("cic_checking".into(), d("30")),
            ("cic_gone".into(), d("0")),
        ]
    );
}

#[tokio::test]
async fn another_error_status_of_the_listing_does_not_fall_back() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/accounts/listing", 503, json!({})).await;
    Mock::given(method("POST"))
        .and(path("/accounts"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([])))
        .expect(0)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    login(&sidecar, &app, &client).await;

    assert_eq!(
        status(&client).await["lastSyncError"],
        "UPSTREAM_UNAVAILABLE"
    );
}

#[tokio::test]
async fn an_unlisted_account_closes_at_zero_only_when_its_table_was_read() {
    let sidecar = MockServer::start().await;
    // The savings table was read and is empty; the current-account table is missing.
    answer(
        &sidecar,
        "/accounts/listing",
        listing(vec![account("1", "CHECKING", "10")], &["SAVINGS"]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    insert_account(&app, member_id, "cic_livret", "LIVRET_A", "500").await;
    insert_account(&app, member_id, "cic_other_checking", "CHECKING", "70").await;
    insert_account(&app, member_id, "cic_zero", "LEP", "0").await;
    insert_account(&app, member_id, "foreign", "SAVINGS", "9").await;

    login(&sidecar, &app, &client).await;

    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
    assert_eq!(
        balances(&app).await,
        vec![
            ("cic_1".into(), d("10")),
            ("cic_livret".into(), d("0")),
            ("cic_other_checking".into(), d("70")),
            ("cic_zero".into(), d("0")),
            ("foreign".into(), d("9")),
        ]
    );
    let closed_snapshot: Decimal = sqlx::query_scalar(
        "SELECT s.balance FROM balance_snapshot s JOIN account a ON a.id = s.account_id \
         WHERE a.external_account_id = 'cic_livret'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(closed_snapshot, Decimal::ZERO);
    assert_eq!(
        count(&app, "SELECT count(*) FROM balance_snapshot").await,
        2,
        "the zero balance already closed is left alone"
    );
}

#[tokio::test]
async fn a_soft_deleted_account_is_not_resurrected() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/accounts/listing",
        listing(vec![account("1", "CHECKING", "10")], &["CHECKING"]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    insert_account(&app, member_id, "cic_1", "CHECKING", "5").await;
    sqlx::query("UPDATE account SET deleted_at = now()")
        .execute(&app.state.db)
        .await
        .unwrap();

    login(&sidecar, &app, &client).await;

    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
    assert_eq!(balances(&app).await, vec![("cic_1".into(), d("5"))]);
}

#[tokio::test]
async fn an_expired_session_logs_in_again_with_the_stored_credentials_and_the_previous_session() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/accounts/listing"))
        .and(body_partial_json(json!({ "sessionState": "state-1" })))
        .respond_with(
            ResponseTemplate::new(401).set_body_json(json!({ "detail": "SESSION_EXPIRED" })),
        )
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/accounts/listing"))
        .and(body_partial_json(json!({ "sessionState": "state-2" })))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(listing(vec![account("1", "CHECKING", "10")], &["CHECKING"])),
        )
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_partial_json(json!({
            "unattended": true, "login": LOGIN, "password": "secret", "sessionState": "state-1"
        })))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({ "mfaRequired": false, "sessionState": "state-2" })),
        )
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_with_credentials(&app, member_id, "state-1").await;

    let queued = client.post("/api/cic/sync").send().await;
    assert_eq!(queued.status, 202, "{}", queued.text());
    assert_eq!(queued.json()["syncStatus"], "QUEUED");
    settle(&app).await;

    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert_eq!(
        app.state.crypto.decrypt(&session.session_state).unwrap(),
        "state-2"
    );
}

#[tokio::test]
async fn a_re_login_that_asks_for_a_new_confirmation_asks_the_saver_to_reconnect() {
    let sidecar = MockServer::start().await;
    fail(
        &sidecar,
        "/accounts/listing",
        401,
        json!({ "detail": "SESSION_EXPIRED" }),
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_partial_json(json!({ "unattended": true })))
        .respond_with(ResponseTemplate::new(200).set_body_json(
            json!({ "mfaRequired": true, "processId": "p-2", "mfaType": "APP_PUSH" }),
        ))
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_with_credentials(&app, member_id, "state-1").await;

    client.post("/api/cic/sync").send().await;
    settle(&app).await;

    let current = status(&client).await;
    assert_eq!(current["isActive"], false);
    assert_eq!(current["lastSyncError"], "SESSION_EXPIRED");
    assert_eq!(
        current["canReconnect"], true,
        "the saver can reconnect from the stored login"
    );
}

#[tokio::test]
async fn an_expired_session_without_stored_credentials_does_not_try_to_log_in() {
    let sidecar = MockServer::start().await;
    fail(
        &sidecar,
        "/accounts/listing",
        401,
        json!({ "detail": "SESSION_EXPIRED" }),
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
        .expect(0)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    super::support::store_session(&app, BANK, member_id, "state-1").await;

    client.post("/api/cic/sync").send().await;
    settle(&app).await;

    assert_eq!(status(&client).await["lastSyncError"], "SESSION_EXPIRED");
}

#[tokio::test]
async fn a_rejected_stored_password_is_forgotten_so_it_is_not_replayed_daily() {
    let sidecar = MockServer::start().await;
    fail(
        &sidecar,
        "/accounts/listing",
        401,
        json!({ "detail": "INVALID_CREDENTIALS" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_with_credentials(&app, member_id, "state-1").await;

    client.post("/api/cic/sync").send().await;
    settle(&app).await;

    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert!(session.encrypted_credentials.is_none());
    assert!(!session.active);
    assert_eq!(status(&client).await["canReconnect"], false);
}

#[tokio::test]
async fn a_first_login_sends_no_previous_session_and_a_later_one_replays_it() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "p-1", "mfaRequired": true, "mfaType": "SMS" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let body = json!({ "login": LOGIN, "password": "secret" });

    client
        .post("/api/cic/auth/initiate")
        .json(&body)
        .send()
        .await;
    super::support::store_session(&app, BANK, member_id, "{\"cookies\":[]}").await;
    client
        .post("/api/cic/auth/initiate")
        .json(&body)
        .send()
        .await;

    let requests = sidecar.received_requests().await.unwrap();
    let bodies: Vec<Value> = requests.iter().map(|r| r.body_json().unwrap()).collect();
    assert_eq!(bodies[0], json!({ "login": LOGIN, "password": "secret" }));
    assert_eq!(
        bodies[1],
        json!({ "login": LOGIN, "password": "secret", "sessionState": "{\"cookies\":[]}" })
    );
}

#[tokio::test]
async fn a_login_the_device_is_remembered_for_stores_the_session_at_once() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": null, "mfaRequired": false, "mfaType": null, "sessionState": "s" }),
    )
    .await;
    answer(
        &sidecar,
        "/accounts/listing",
        listing(vec![account("1", "CHECKING", "10")], &["CHECKING"]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let response = client
        .post("/api/cic/auth/initiate")
        .json(&json!({ "login": LOGIN, "password": "secret" }))
        .send()
        .await;
    settle(&app).await;

    assert_eq!(response.json(), json!({ "mfaRequired": false }));
    let current = status(&client).await;
    assert_eq!(current["syncStatus"], "SUCCESS");
    assert_eq!(current["canReconnect"], true);
}

#[tokio::test]
async fn a_reconnection_uses_the_stored_credentials_and_the_previous_session() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_partial_json(
            json!({ "login": LOGIN, "password": "secret", "sessionState": "state-1" }),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(
            json!({ "processId": "p1", "mfaRequired": true, "mfaType": "APP_PUSH" }),
        ))
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_with_credentials(&app, member_id, "state-1").await;

    let response = client.post("/api/cic/auth/reconnect").send().await;

    assert_eq!(response.status, 200, "{}", response.text());
    assert_eq!(
        response.json(),
        json!({ "processId": "p1", "mfaRequired": true, "mfaType": "APP_PUSH" })
    );
    let sent: Value = sidecar.received_requests().await.unwrap()[0]
        .body_json()
        .unwrap();
    assert!(
        sent.get("unattended").is_none(),
        "the saver is there to approve"
    );
}

#[tokio::test]
async fn a_reconnection_without_stored_credentials_asks_the_saver_to_type_them() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let none = client.post("/api/cic/auth/reconnect").send().await;
    super::support::store_session(&app, BANK, member_id, "state-1").await;
    let without = client.post("/api/cic/auth/reconnect").send().await;
    sqlx::query("UPDATE sidecar_session SET encrypted_credentials = 'garbage'")
        .execute(&app.state.db)
        .await
        .unwrap();
    let unreadable = client.post("/api/cic/auth/reconnect").send().await;

    for (response, detail) in [
        (none, "No stored CIC credentials"),
        (without, "No stored CIC credentials"),
        (unreadable, "Stored CIC credentials are unreadable"),
    ] {
        assert_eq!(response.status, 422);
        assert_eq!(response.json()["code"], "SESSION_EXPIRED");
        assert_eq!(response.json()["detail"], detail);
    }
    assert!(sidecar.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn a_password_rejected_during_a_reconnection_requires_new_credentials() {
    for active in [true, false] {
        let sidecar = MockServer::start().await;
        fail(
            &sidecar,
            "/initiate",
            401,
            json!({ "detail": "INVALID_CREDENTIALS" }),
        )
        .await;
        let app = app_with_sidecar(URL, &sidecar).await;
        let (client, member_id) = admin(&app).await;
        store_with_credentials(&app, member_id, "state-1").await;
        if !active {
            sqlx::query(
                "UPDATE sidecar_session SET is_active = false, sync_status = 'FAILED', \
                 last_sync_error = 'SESSION_EXPIRED'",
            )
            .execute(&app.state.db)
            .await
            .unwrap();
        }

        let rejected = client.post("/api/cic/auth/reconnect").send().await;

        assert_eq!(rejected.json()["code"], "INVALID_CREDENTIALS");
        assert_eq!(
            rejected.json()["detail"],
            "CIC rejected the customer number or password"
        );
        let current = status(&client).await;
        assert_eq!(current["isActive"], false);
        assert_eq!(current["canReconnect"], false);
        assert_eq!(current["syncStatus"], "FAILED");
        assert_eq!(current["lastSyncError"], "INVALID_CREDENTIALS");
        let again = client.post("/api/cic/auth/reconnect").send().await;
        assert_eq!(again.json()["code"], "SESSION_EXPIRED");
        assert_eq!(sidecar.received_requests().await.unwrap().len(), 1);
    }
}

#[tokio::test]
async fn a_transient_reconnection_failure_keeps_the_stored_credentials() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/initiate", 503, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_with_credentials(&app, member_id, "state-1").await;

    let response = client.post("/api/cic/auth/reconnect").send().await;

    assert_eq!(response.json()["code"], "UPSTREAM_UNAVAILABLE");
    let current = status(&client).await;
    assert_eq!(current["canReconnect"], true);
    assert_eq!(current["isActive"], true);
}

#[tokio::test]
async fn a_mistyped_sms_code_keeps_the_attempt_for_the_next_try() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "p4", "mfaRequired": true, "mfaType": "SMS" }),
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/complete"))
        .and(body_partial_json(
            json!({ "processId": "p4", "code": "000000" }),
        ))
        .respond_with(ResponseTemplate::new(401).set_body_json(json!({ "detail": "INVALID_OTP" })))
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/complete"))
        .and(body_partial_json(
            json!({ "processId": "p4", "code": "123456" }),
        ))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "sessionState": "cookies" })),
        )
        .mount(&sidecar)
        .await;
    fail(&sidecar, "/accounts/listing", 503, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    client
        .post("/api/cic/auth/initiate")
        .json(&json!({ "login": LOGIN, "password": "secret" }))
        .send()
        .await;

    let wrong = client
        .post("/api/cic/auth/complete")
        .json(&json!({ "processId": "p4", "code": "000000" }))
        .send()
        .await;
    assert_eq!(wrong.json()["code"], "INVALID_OTP");
    assert_eq!(wrong.json()["detail"], "CIC rejected the SMS code");
    assert!(session_of(&app, BANK, member_id).await.is_none());
    let right = client
        .post("/api/cic/auth/complete")
        .json(&json!({ "processId": "p4", "code": "123456" }))
        .send()
        .await;
    settle(&app).await;

    assert_eq!(right.status, 200, "{}", right.text());
    assert_eq!(right.json()["canReconnect"], true);
    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert!(session.encrypted_credentials.is_some());
    let replay = client
        .post("/api/cic/auth/complete")
        .json(&json!({ "processId": "p4", "code": "123456" }))
        .send()
        .await;
    assert_eq!(
        replay.json()["code"],
        "AUTH_ATTEMPT_EXPIRED",
        "an attempt completes only once"
    );
}

#[tokio::test]
async fn completing_an_unknown_foreign_cleared_or_expired_attempt_never_reaches_the_sidecar() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "p-1", "mfaRequired": true, "mfaType": "APP_PUSH" }),
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/complete"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "sessionState": "s" })))
        .expect(0)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let (other, _) = member(&app, "bob").await;
    let initiate = || async {
        client
            .post("/api/cic/auth/initiate")
            .json(&json!({ "login": LOGIN, "password": "secret" }))
            .send()
            .await;
    };
    let complete = |who: &Client, process_id: &str| {
        who.post("/api/cic/auth/complete")
            .json(&json!({ "processId": process_id }))
            .send()
    };

    initiate().await;
    let foreign = complete(&other, "p-1").await;
    let unknown = complete(&client, "nope").await;
    other.delete("/api/cic/session").send().await;
    client.delete("/api/cic/session").send().await;
    let cleared = complete(&client, "p-1").await;
    initiate().await;
    app.clock.advance(std::time::Duration::from_secs(600));
    let expired = complete(&client, "p-1").await;

    for response in [foreign, unknown, cleared, expired] {
        assert_eq!(response.status, 422);
        assert_eq!(response.json()["code"], "AUTH_ATTEMPT_EXPIRED");
        assert_eq!(
            response.json()["detail"],
            "CIC authentication attempt expired"
        );
    }
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

#[tokio::test]
async fn sync_without_a_session_or_with_an_expired_one_asks_to_reconnect() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let none = client.post("/api/cic/sync").send().await;
    store_with_credentials(&app, member_id, "s").await;
    sqlx::query("UPDATE sidecar_session SET is_active = false")
        .execute(&app.state.db)
        .await
        .unwrap();
    let expired = client.post("/api/cic/sync").send().await;

    assert_eq!(none.status, 422);
    assert_eq!(none.json()["code"], "SESSION_EXPIRED");
    assert_eq!(
        none.json()["detail"],
        "No active CIC session. Please reconnect."
    );
    assert_eq!(
        expired.json()["detail"],
        "The CIC session expired. Please reconnect."
    );
}

#[tokio::test]
async fn status_reconnect_and_clear_use_only_the_current_member() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let (other, _) = member(&app, "bob").await;
    store_with_credentials(&app, member_id, "s").await;

    assert_eq!(status(&other).await["canReconnect"], false);
    assert_eq!(
        other.post("/api/cic/auth/reconnect").send().await.json()["code"],
        "SESSION_EXPIRED"
    );
    assert_eq!(other.delete("/api/cic/session").send().await.status, 204);
    assert_eq!(status(&client).await["canReconnect"], true);
    assert_eq!(client.delete("/api/cic/session").send().await.status, 204);
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

#[tokio::test]
async fn the_scheduled_resync_asks_for_reauthentication_once_the_session_is_gone() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (_, member_id) = admin(&app).await;
    store_with_credentials(&app, member_id, "s").await;
    sqlx::query(
        "UPDATE sidecar_session SET is_active = false, sync_status = 'FAILED', last_sync_error = 'SESSION_EXPIRED'",
    )
    .execute(&app.state.db)
    .await
    .unwrap();

    let result = picsou::connectors::cic::resync_reporting(&app.state, member_id).await;

    assert_eq!(
        result.status,
        picsou::ledger::sidecar::SyncStatus::NeedsReauth
    );
}

#[tokio::test]
async fn validation_and_the_three_budgets() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let bad_code = client
        .post("/api/cic/auth/complete")
        .json(&json!({ "processId": "p", "code": "12a4" }))
        .send()
        .await;
    assert_eq!(bad_code.status, 422);
    assert_eq!(bad_code.json()["errors"]["code"], r#"must match "\d{4,8}""#);
    let bad_login = client
        .post("/api/cic/auth/initiate")
        .json(&json!({ "login": "x".repeat(101), "password": " " }))
        .send()
        .await;
    assert_eq!(bad_login.status, 422);
    assert_eq!(
        bad_login.json()["errors"]["login"],
        "size must be between 0 and 100"
    );
    assert_eq!(bad_login.json()["errors"]["password"], "must not be blank");

    // Initiate and reconnect share the five-attempt budget; validation spends none of it.
    for attempt in 0..5 {
        let response = if attempt % 2 == 0 {
            client
                .post("/api/cic/auth/initiate")
                .json(&json!({ "login": LOGIN, "password": "secret" }))
                .send()
                .await
        } else {
            client.post("/api/cic/auth/reconnect").send().await
        };
        assert_eq!(response.status, 422, "attempt {attempt}");
    }
    let initiate = client
        .post("/api/cic/auth/initiate")
        .json(&json!({ "login": LOGIN, "password": "secret" }))
        .send()
        .await;
    let reconnect = client.post("/api/cic/auth/reconnect").send().await;
    for response in [initiate, reconnect] {
        assert_eq!(response.status, 429);
        assert_eq!(
            response.json()["detail"],
            "Too many CIC authentication attempts. Please wait before retrying."
        );
    }
    for _ in 0..10 {
        let response = client
            .post("/api/cic/auth/complete")
            .json(&json!({ "processId": "p" }))
            .send()
            .await;
        assert_eq!(response.status, 422);
    }
    let limited = client
        .post("/api/cic/auth/complete")
        .json(&json!({ "processId": "p" }))
        .send()
        .await;
    assert_eq!(limited.status, 429);
    assert_eq!(
        client.post("/api/cic/sync").send().await.status,
        422,
        "the sync budget is separate"
    );
}

#[tokio::test]
async fn startup_recovery_fails_in_flight_syncs() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_with_credentials(&app, member_id, "s").await;
    sqlx::query("UPDATE sidecar_session SET sync_status = 'RUNNING'")
        .execute(&app.state.db)
        .await
        .unwrap();

    let recovered = picsou::connectors::cic::recover_interrupted_syncs(&app.state)
        .await
        .unwrap();

    assert_eq!(recovered, 1);
    let current = status(&client).await;
    assert_eq!(current["syncStatus"], "FAILED");
    assert_eq!(current["lastSyncError"], "INTERNAL_ERROR");
}
