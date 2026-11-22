//! CaisseEpargneController and the shared BPCE engine over HTTP, the sidecar stubbed: the keypad
//! login and its guards, the remembered identifier, the budgets, and the import of a snapshot.

use std::time::Duration;

use chrono::NaiveDate;
use picsou::ledger::sidecar::SidecarBank;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{body_json, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{
    admin, answer, app_with_sidecar, count, d, fail, member, n, session_of, settle, store_session,
};
use picsou::testing::{Client, TestApp};

const URL: &str = "CAISSE_EPARGNE_AUTH_URL";
const BANK: SidecarBank = SidecarBank::CaisseEpargne;
const BASE: &str = "/api/caisse-epargne";

type AccountRow = (String, String, String, Decimal, Option<String>);
type CardRow = (
    Option<String>,
    Option<Decimal>,
    Option<NaiveDate>,
    Option<i64>,
    Option<String>,
);
type DebtRow = (
    Decimal,
    Decimal,
    Decimal,
    Option<Decimal>,
    String,
    NaiveDate,
    NaiveDate,
    String,
);

pub fn keypad(images: usize) -> Value {
    json!({ "images": vec!["data:image/png;base64,iVBORw0KGgo="; images], "columns": 5 })
}

pub async fn mount_initiate(sidecar: &MockServer, keypad: Value) {
    answer(
        sidecar,
        "/initiate",
        json!({ "processId": "p-1", "keypad": keypad, "expiresInSeconds": 120 }),
    )
    .await;
}

pub async fn mount_keypad_and_complete(sidecar: &MockServer) {
    answer(
        sidecar,
        "/keypad",
        json!({ "processId": "p-1", "status": "SECURPASS_PENDING" }),
    )
    .await;
    answer(
        sidecar,
        "/complete",
        json!({ "sessionState": "plain-state" }),
    )
    .await;
}

pub fn positions() -> Value {
    json!({ "processId": "p-1", "positions": [1, 2, 3, 4, 5, 6] })
}

async fn initiate(client: &Client, customer_id: &str) -> picsou::testing::Response {
    client
        .post(&format!("{BASE}/auth/initiate"))
        .json(&json!({ "customerId": customer_id }))
        .send()
        .await
}

async fn post(client: &Client, route: &str, body: Value) -> picsou::testing::Response {
    client
        .post(&format!("{BASE}{route}"))
        .json(&body)
        .send()
        .await
}

async fn status(client: &Client) -> Value {
    client.get(&format!("{BASE}/status")).send().await.json()
}

async fn received(sidecar: &MockServer, route: &str) -> usize {
    sidecar
        .received_requests()
        .await
        .unwrap_or_default()
        .iter()
        .filter(|r| r.url.path() == route)
        .count()
}

async fn app(sidecar: &MockServer) -> (TestApp, Client, i64) {
    let app = app_with_sidecar(URL, sidecar).await;
    let (client, member_id) = admin(&app).await;
    (app, client, member_id)
}

#[tokio::test]
async fn the_status_without_a_session_is_inactive_and_idle() {
    let sidecar = MockServer::start().await;
    let (_app, client, _) = app(&sidecar).await;
    assert_eq!(
        status(&client).await,
        json!({ "isActive": false, "syncStatus": "IDLE", "unsupported": [], "remembered": false, "unsupportedCount": 0 })
    );
}

#[tokio::test]
async fn the_full_login_stores_the_encrypted_session_and_identifier_without_syncing() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_json(json!({ "customerId": "12345678" })))
        .respond_with(ResponseTemplate::new(200).set_body_json(
            json!({ "processId": "p-1", "keypad": keypad(10), "expiresInSeconds": 120 }),
        ))
        .expect(1)
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/keypad"))
        .and(body_json(positions()))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({ "processId": "p-1", "status": "SECURPASS_PENDING" })),
        )
        .expect(1)
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/complete"))
        .and(body_json(json!({ "processId": "p-1" })))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "sessionState": "plain-state" })),
        )
        .expect(1)
        .mount(&sidecar)
        .await;
    let (app, client, member_id) = app(&sidecar).await;

    let started = initiate(&client, "12345678").await;
    assert_eq!(started.status, 200, "{}", started.text());
    assert_eq!(
        started.json(),
        json!({ "processId": "p-1", "keypad": keypad(10), "expiresInSeconds": 120 })
    );
    assert!(
        session_of(&app, BANK, member_id).await.is_none(),
        "nothing stored before approval"
    );

    let clicked = post(&client, "/auth/keypad", positions()).await;
    assert_eq!(clicked.status, 200);
    assert_eq!(
        clicked.json(),
        json!({ "processId": "p-1", "status": "SECURPASS_PENDING" })
    );
    assert!(
        !clicked.text().contains("[1,2"),
        "the positions are never echoed"
    );

    let completed = post(&client, "/auth/complete", json!({ "processId": "p-1" })).await;
    assert_eq!(completed.status, 200);
    assert_eq!(completed.json(), json!({ "connected": true }));
    settle(&app).await;

    let session = session_of(&app, BANK, member_id).await.expect("session");
    assert_eq!(
        app.state.crypto.decrypt(&session.session_state).unwrap(),
        "plain-state"
    );
    let identifier = app
        .state
        .crypto
        .decrypt(session.encrypted_credentials.as_deref().unwrap())
        .unwrap();
    assert_eq!(identifier, r#"{"customerId":"12345678"}"#);
    let current = status(&client).await;
    assert_eq!(
        current["syncStatus"], "IDLE",
        "a login does not sync by itself"
    );
    assert_eq!(current["remembered"], true);
    assert_eq!(received(&sidecar, "/accounts").await, 0);

    let again = post(&client, "/auth/complete", json!({ "processId": "p-1" })).await;
    assert_eq!(
        again.json()["code"],
        "AUTH_ATTEMPT_EXPIRED",
        "a process id is single use"
    );
}

#[tokio::test]
async fn an_unsafe_keypad_is_keypad_changed_and_never_registered() {
    for unsafe_keypad in [
        keypad(9),
        json!({ "images": vec!["data:image/png;base64,iVBORw0KGgo="; 10], "columns": 4 }),
        json!({ "images": vec!["data:image/png;base64,PHNjcmlwdD4=\"><script>"; 10], "columns": 5 }),
        json!({ "images": vec!["data:image/svg+xml;base64,PHN2Zz4="; 10], "columns": 5 }),
    ] {
        let sidecar = MockServer::start().await;
        mount_initiate(&sidecar, unsafe_keypad).await;
        mount_keypad_and_complete(&sidecar).await;
        let (_app, client, _) = app(&sidecar).await;

        let started = initiate(&client, "12345678").await;
        assert_eq!(started.status, 422);
        assert_eq!(started.json()["code"], "KEYPAD_CHANGED");
        assert_eq!(
            post(&client, "/auth/keypad", positions()).await.json()["code"],
            "AUTH_ATTEMPT_EXPIRED"
        );
        assert_eq!(received(&sidecar, "/keypad").await, 0);
    }
}

#[tokio::test]
async fn an_answer_without_a_process_or_a_lifetime_is_a_changed_format() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "p-1", "keypad": keypad(10), "expiresInSeconds": 0 }),
    )
    .await;
    let (_app, client, _) = app(&sidecar).await;
    let response = initiate(&client, "12345678").await;
    assert_eq!(response.json()["code"], "UPSTREAM_FORMAT_CHANGED");
    assert_eq!(
        response.json()["detail"],
        "Caisse d'Epargne did not return a usable sign-in challenge"
    );
}

#[tokio::test]
async fn another_members_process_is_refused_and_stays_usable_by_its_owner() {
    let sidecar = MockServer::start().await;
    mount_initiate(&sidecar, keypad(10)).await;
    mount_keypad_and_complete(&sidecar).await;
    let (app, client, _) = app(&sidecar).await;
    let (other, _) = member(&app, "dominique").await;
    assert_eq!(initiate(&client, "12345678").await.status, 200);

    let foreign = post(&other, "/auth/keypad", positions()).await;
    assert_eq!(foreign.status, 422);
    assert_eq!(foreign.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    assert_eq!(received(&sidecar, "/keypad").await, 0);
    assert_eq!(post(&client, "/auth/keypad", positions()).await.status, 200);
}

#[tokio::test]
async fn a_keypad_older_than_its_lifetime_expires_without_calling_the_sidecar() {
    let sidecar = MockServer::start().await;
    mount_initiate(&sidecar, keypad(10)).await;
    mount_keypad_and_complete(&sidecar).await;
    let (app, client, _) = app(&sidecar).await;
    initiate(&client, "12345678").await;
    app.clock.advance(Duration::from_secs(120));

    let response = post(&client, "/auth/keypad", positions()).await;
    assert_eq!(response.json()["code"], "KEYPAD_EXPIRED");
    assert_eq!(
        response.json()["detail"],
        "The Caisse d'Epargne keypad expired. Start again."
    );
    assert_eq!(received(&sidecar, "/keypad").await, 0);
}

#[tokio::test]
async fn complete_needs_the_keypad_first_and_an_approval_within_its_window() {
    let sidecar = MockServer::start().await;
    mount_initiate(&sidecar, keypad(10)).await;
    mount_keypad_and_complete(&sidecar).await;
    let (app, client, member_id) = app(&sidecar).await;
    initiate(&client, "12345678").await;
    let early = post(&client, "/auth/complete", json!({ "processId": "p-1" })).await;
    assert_eq!(
        early.json()["code"],
        "AUTH_ATTEMPT_EXPIRED",
        "no approval to wait for"
    );

    initiate(&client, "12345678").await;
    post(&client, "/auth/keypad", positions()).await;
    app.clock.advance(Duration::from_secs(180));
    let late = post(&client, "/auth/complete", json!({ "processId": "p-1" })).await;
    assert_eq!(late.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    assert_eq!(received(&sidecar, "/complete").await, 0);
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

#[tokio::test]
async fn the_keypad_step_is_single_use_even_when_the_sidecar_fails() {
    let sidecar = MockServer::start().await;
    mount_initiate(&sidecar, keypad(10)).await;
    fail(&sidecar, "/keypad", 422, json!({})).await;
    let (_app, client, _) = app(&sidecar).await;
    initiate(&client, "12345678").await;

    let first = post(&client, "/auth/keypad", positions()).await;
    assert_eq!(first.json()["code"], "INVALID_POSITIONS");
    assert!(!first.text().contains("[1,2,3"));
    let second = post(&client, "/auth/keypad", positions()).await;
    assert_eq!(second.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    assert_eq!(received(&sidecar, "/keypad").await, 1);
}

#[tokio::test]
async fn each_login_status_of_the_sidecar_becomes_its_code() {
    for (status, route, code) in [
        (408, "/initiate", "APP_VALIDATION_TIMEOUT"),
        (409, "/initiate", "KEYPAD_CHANGED"),
        (429, "/initiate", "UPSTREAM_UNAVAILABLE"),
        (401, "/initiate", "INVALID_CREDENTIALS"),
        (410, "/initiate", "AUTH_ATTEMPT_EXPIRED"),
        (408, "/keypad", "KEYPAD_EXPIRED"),
        (408, "/complete", "APP_VALIDATION_TIMEOUT"),
    ] {
        let sidecar = MockServer::start().await;
        if route == "/initiate" {
            fail(&sidecar, route, status, json!({})).await;
        } else {
            mount_initiate(&sidecar, keypad(10)).await;
            if route == "/complete" {
                answer(
                    &sidecar,
                    "/keypad",
                    json!({ "processId": "p-1", "status": "SECURPASS_PENDING" }),
                )
                .await;
            }
            fail(&sidecar, route, status, json!({})).await;
        }
        let (_app, client, _) = app(&sidecar).await;
        let mut response = initiate(&client, "12345678").await;
        if route != "/initiate" {
            response = post(&client, "/auth/keypad", positions()).await;
        }
        if route == "/complete" {
            response = post(&client, "/auth/complete", json!({ "processId": "p-1" })).await;
        }
        assert_eq!(response.status, 422, "{status} on {route}");
        assert_eq!(response.json()["code"], code, "{status} on {route}");
        assert_eq!(received(&sidecar, route).await, 1, "never retried");
    }
}

#[tokio::test]
async fn a_disconnect_cancels_a_login_waiting_on_the_phone() {
    let sidecar = MockServer::start().await;
    mount_initiate(&sidecar, keypad(10)).await;
    mount_keypad_and_complete(&sidecar).await;
    let (app, client, member_id) = app(&sidecar).await;
    initiate(&client, "12345678").await;
    post(&client, "/auth/keypad", positions()).await;

    let cleared = client.delete(&format!("{BASE}/session")).send().await;
    assert_eq!(cleared.json()["removed"], false);
    let response = post(&client, "/auth/complete", json!({ "processId": "p-1" })).await;
    assert_eq!(response.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

async fn remember(app: &TestApp, member_id: i64, customer_id: &str) -> String {
    store_session(app, BANK, member_id, "plain-state").await;
    let credentials = app
        .state
        .crypto
        .encrypt(&json!({ "customerId": customer_id }).to_string())
        .unwrap();
    picsou::db::query("UPDATE sidecar_session SET encrypted_credentials = $1 WHERE member_id = $2")
        .bind(&credentials)
        .bind(member_id)
        .execute(&app.state.db)
        .await
        .unwrap();
    credentials
}

#[tokio::test]
async fn an_empty_identifier_reuses_the_remembered_one() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_json(json!({ "customerId": "99887766" })))
        .respond_with(ResponseTemplate::new(200).set_body_json(
            json!({ "processId": "p-1", "keypad": keypad(10), "expiresInSeconds": 120 }),
        ))
        .expect(1)
        .mount(&sidecar)
        .await;
    let (app, client, member_id) = app(&sidecar).await;
    remember(&app, member_id, "99887766").await;
    assert_eq!(status(&client).await["remembered"], true);

    assert_eq!(initiate(&client, "").await.status, 200);
}

#[tokio::test]
async fn a_remembered_identifier_the_bank_rejects_is_forgotten_and_a_transient_failure_keeps_it() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/initiate", 503, json!({})).await;
    let (app, client, member_id) = app(&sidecar).await;
    remember(&app, member_id, "99887766").await;
    assert_eq!(
        initiate(&client, "").await.json()["code"],
        "UPSTREAM_UNAVAILABLE"
    );
    assert_eq!(status(&client).await["remembered"], true);

    sidecar.reset().await;
    fail(&sidecar, "/initiate", 401, json!({})).await;
    assert_eq!(
        initiate(&client, "").await.json()["code"],
        "INVALID_CREDENTIALS"
    );
    assert_eq!(status(&client).await["remembered"], false);
    let session = session_of(&app, BANK, member_id)
        .await
        .expect("session kept");
    assert!(session.encrypted_credentials.is_none());

    let none = initiate(&client, "").await;
    assert_eq!(
        none.json()["detail"],
        "No Caisse d'Epargne identifier is remembered. Please enter it."
    );
    assert_eq!(
        received(&sidecar, "/initiate").await,
        1,
        "no identifier, no bank call"
    );
}

#[tokio::test]
async fn an_unreadable_identifier_is_not_remembered() {
    let sidecar = MockServer::start().await;
    let (app, client, member_id) = app(&sidecar).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    picsou::db::query(
        "UPDATE sidecar_session SET encrypted_credentials = 'garbage' WHERE member_id = $1",
    )
    .bind(member_id)
    .execute(&app.state.db)
    .await
    .unwrap();
    assert_eq!(status(&client).await["remembered"], false);
    assert_eq!(
        initiate(&client, "").await.json()["code"],
        "INVALID_CREDENTIALS"
    );
    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert!(
        session.encrypted_credentials.is_none(),
        "an unreadable identifier is forgotten"
    );
}

#[tokio::test]
async fn invalid_bodies_are_refused_before_any_bank_call_or_attempt() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/initiate", 503, json!({})).await;
    let (_app, client, _) = app(&sidecar).await;
    for body in [
        json!({ "customerId": "12345678", "password": "secret" }),
        json!({ "customerId": "12ab" }),
        json!({ "customerId": 12345678 }),
        json!({}),
        json!([]),
    ] {
        let response = post(&client, "/auth/initiate", body.clone()).await;
        assert_eq!(response.status, 400, "{body}");
        assert_eq!(
            response.json()["detail"],
            "The Caisse d'Epargne request is not valid"
        );
        assert!(!response.text().contains("secret"));
    }
    for body in [
        json!({ "processId": "p-1", "positions": [1, 2, 3, 4, 5] }),
        json!({ "processId": "p-1", "positions": [1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3] }),
        json!({ "processId": "p-1", "positions": [1, 2, 3, 4, 5, -1] }),
        json!({ "processId": "p-1", "positions": [1, 2, 3, 4, 5, "6"] }),
        json!({ "processId": "x".repeat(101), "positions": [1, 2, 3, 4, 5, 6] }),
    ] {
        assert_eq!(
            post(&client, "/auth/keypad", body.clone()).await.status,
            400,
            "{body}"
        );
    }
    assert_eq!(received(&sidecar, "/initiate").await, 0);
    for _ in 0..3 {
        assert_eq!(initiate(&client, "12345678").await.status, 422);
    }
}

#[tokio::test]
async fn three_logins_per_15_minutes_per_ip_then_429_with_retry_after() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/initiate", 503, json!({})).await;
    let (app, client, _) = app(&sidecar).await;
    for _ in 0..3 {
        assert_eq!(initiate(&client, "12345678").await.status, 422);
    }
    let limited = initiate(&client, "12345678").await;
    assert_eq!(limited.status, 429);
    assert_eq!(limited.header("retry-after"), Some("900"));
    assert_eq!(
        limited.json()["detail"],
        "Too many Caisse d'Epargne sign-in attempts. Please wait before retrying."
    );
    assert_eq!(received(&sidecar, "/initiate").await, 3);

    let keypad = post(&client, "/auth/keypad", positions()).await;
    assert_eq!(
        keypad.json()["code"],
        "AUTH_ATTEMPT_EXPIRED",
        "the keypad has its own bucket"
    );
    let complete = post(&client, "/auth/complete", json!({ "processId": "p-1" })).await;
    assert_eq!(complete.status, 422, "complete draws from no bucket");
    app.clock.advance(Duration::from_secs(15 * 60));
    assert_eq!(initiate(&client, "12345678").await.status, 422);
}

pub fn account(external_id: &str, kind: &str, balance: &str, extra: Value) -> Value {
    let mut account = json!({
        "externalId": external_id, "kind": kind, "name": null, "balance": balance,
        "currency": "EUR", "snapshotComplete": true, "transactions": []
    });
    if let (Value::Object(target), Value::Object(more)) = (&mut account, extra) {
        target.extend(more);
    }
    account
}

pub fn tx(id: &str, date: &str, amount: &str, label: &str, extra: Value) -> Value {
    let mut tx = json!({ "externalId": id, "date": date, "amount": n(amount), "currency": "EUR", "label": label });
    if let (Value::Object(target), Value::Object(more)) = (&mut tx, extra) {
        target.extend(more);
    }
    tx
}

async fn sync_with(
    sidecar: &MockServer,
    app: &TestApp,
    client: &Client,
    member_id: i64,
    accounts: Value,
) -> Value {
    answer(sidecar, "/accounts", accounts).await;
    if session_of(app, BANK, member_id).await.is_none() {
        store_session(app, BANK, member_id, "plain-state").await;
    }
    let queued = client.post(&format!("{BASE}/sync")).send().await;
    assert_eq!(queued.status, 202, "{}", queued.text());
    settle(app).await;
    status(client).await
}

async fn accounts_of(app: &TestApp, member_id: i64) -> Vec<AccountRow> {
    picsou::db::query_as(
        "SELECT external_account_id, type::text, name, current_balance, provider FROM account \
         WHERE member_id = $1 ORDER BY external_account_id",
    )
    .bind(member_id)
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn a_sync_imports_every_kind_under_the_prefix_and_counts_unsupported_contracts() {
    let sidecar = MockServer::start().await;
    let (app, client, member_id) = app(&sidecar).await;
    let snapshot = json!({
        "accounts": [
            account("1", "CURRENT_ACCOUNT", "1250.50", json!({ "name": "Compte chèque", "iban": "FR7600000000000000000000001",
                "transactions": [tx("t1", "2026-01-10", "-42.10", "CB SUPERMARCHE", json!({})), tx("t1", "2026-01-11", "-42.20", "", json!({}))] })),
            account("2", "LIVRET_A", "3000", json!({})),
            account("ce_3", "TERM_DEPOSIT", "500", json!({})),
            account("4", "LOAN", "-8000", json!({})),
            account("5", "CARD", "-15", json!({ "cardNature": "IMMEDIATE_DEBIT", "nextDueDate": "2026-02-01" })),
        ],
        "unsupported": [{ "externalId": "99", "familyCode": "AV", "subFamilyCode": "x y" }]
    });
    let current = sync_with(&sidecar, &app, &client, member_id, snapshot).await;
    assert_eq!(current["syncStatus"], "SUCCESS", "{current}");
    assert_eq!(current["unsupportedCount"], 1);
    assert_eq!(
        current["unsupported"],
        json!([{ "externalId": "99", "familyCode": "AV", "subFamilyCode": "?" }])
    );

    let provider = Some("Caisse d'Epargne".to_owned());
    assert_eq!(
        accounts_of(&app, member_id).await,
        vec![
            (
                "ce_1".into(),
                "CHECKING".into(),
                "Compte chèque".into(),
                d("1250.50"),
                provider.clone()
            ),
            (
                "ce_2".into(),
                "LIVRET_A".into(),
                "Livret A".into(),
                d("3000"),
                provider.clone()
            ),
            (
                "ce_3".into(),
                "SAVINGS".into(),
                "Compte à terme".into(),
                d("500"),
                provider.clone()
            ),
            (
                "ce_4".into(),
                "LOAN".into(),
                "Caisse d'Epargne loan".into(),
                d("8000"),
                provider.clone()
            ),
            (
                "ce_5".into(),
                "CREDIT_CARD".into(),
                "Caisse d'Epargne card".into(),
                d("-15"),
                provider
            ),
        ]
    );
    let card: CardRow = picsou::db::query_as(
        "SELECT card_nature::text, payment_due_amount, payment_due_date, parent_account_id, iban FROM account WHERE external_account_id = 'ce_5'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(card.0.as_deref(), Some("IMMEDIATE_DEBIT"));
    assert_eq!(card.1, Some(d("15")));
    assert_eq!(card.2, "2026-02-01".parse().ok());
    assert_eq!(card.3, None);
    assert_eq!(
        count(&app, "SELECT count(*) FROM account WHERE iban IS NOT NULL").await,
        0,
        "the IBAN is never stored"
    );
    let rows: Vec<(String, Decimal, String)> = picsou::db::query_as(
        "SELECT external_id, amount, description FROM \"transaction\" ORDER BY id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![(
            "ce_t1".into(),
            d("-42.20"),
            "Caisse d'Epargne transaction".into()
        )],
        "the last occurrence wins"
    );
    assert_eq!(
        count(
            &app,
            "SELECT count(*) FROM balance_snapshot WHERE date = DATE '2026-01-15'"
        )
        .await,
        5
    );
}

#[tokio::test]
async fn a_second_sync_updates_rows_in_place_and_never_deletes_them() {
    let sidecar = MockServer::start().await;
    let (app, client, member_id) = app(&sidecar).await;
    let first = json!({ "accounts": [account("1", "CURRENT_ACCOUNT", "10", json!({ "transactions": [
        tx("t1", "2026-01-10", "-5", "OLD", json!({})), tx("t2", "2026-01-11", "-6", "KEPT", json!({}))
    ] }))] });
    sync_with(&sidecar, &app, &client, member_id, first).await;
    sidecar.reset().await;
    let second = json!({ "accounts": [account("1", "CURRENT_ACCOUNT", "11", json!({ "transactions": [
        tx("t1", "2026-01-10", "-5.50", "NEW", json!({}))
    ] }))] });
    assert_eq!(
        sync_with(&sidecar, &app, &client, member_id, second).await["syncStatus"],
        "SUCCESS"
    );
    let rows: Vec<(String, Decimal, String)> = picsou::db::query_as(
        "SELECT external_id, amount, description FROM \"transaction\" ORDER BY external_id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            ("ce_t1".into(), d("-5.50"), "NEW".into()),
            ("ce_t2".into(), d("-6"), "KEPT".into())
        ]
    );
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 1);
}

#[tokio::test]
async fn one_doubtful_account_refuses_the_whole_snapshot() {
    for (bad, detail) in [
        (
            account("2", "CRYPTO", "1", json!({})),
            "Caisse d'Epargne returned an unknown account kind",
        ),
        (
            account("2", "CARD", "5", json!({})),
            "Caisse d'Epargne returned a card with a positive outstanding",
        ),
        (
            account("2", "LOAN", "5", json!({})),
            "Caisse d'Epargne returned a loan with a positive outstanding",
        ),
        (
            account("2", "PER", "-1", json!({})),
            "Caisse d'Epargne returned an insurer contract with a negative balance",
        ),
        (
            account("2", "CARD", "-5", json!({ "cardNature": "GOLD" })),
            "Caisse d'Epargne returned an unknown card nature",
        ),
        (
            account("2", "PEL", "5", json!({ "currency": "USD" })),
            "Caisse d'Epargne returned an account that is not in EUR",
        ),
        (
            account("1", "SAVINGS", "5", json!({})),
            "Caisse d'Epargne returned duplicate accounts",
        ),
        (
            account("2", "SAVINGS", "5", json!({ "snapshotComplete": false })),
            "Caisse d'Epargne returned an incomplete snapshot",
        ),
        (
            account(&"9".repeat(98), "SAVINGS", "5", json!({})),
            "Caisse d'Epargne returned an invalid account identifier",
        ),
    ] {
        let sidecar = MockServer::start().await;
        let (app, client, member_id) = app(&sidecar).await;
        let snapshot =
            json!({ "accounts": [account("1", "CURRENT_ACCOUNT", "10", json!({})), bad] });
        let current = sync_with(&sidecar, &app, &client, member_id, snapshot).await;
        assert_eq!(current["syncStatus"], "FAILED", "{detail}");
        assert_eq!(
            current["lastSyncError"], "UPSTREAM_FORMAT_CHANGED",
            "{detail}"
        );
        assert_eq!(current["isActive"], true, "{detail}");
        assert_eq!(
            count(&app, "SELECT count(*) FROM account").await,
            0,
            "{detail}"
        );
    }
}

#[tokio::test]
async fn an_expired_session_is_deactivated_and_a_transient_failure_keeps_it() {
    let sidecar = MockServer::start().await;
    let (app, client, member_id) = app(&sidecar).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    fail(&sidecar, "/accounts", 503, json!({})).await;
    client.post(&format!("{BASE}/sync")).send().await;
    settle(&app).await;
    let current = status(&client).await;
    assert_eq!(
        (
            current["lastSyncError"].clone(),
            current["isActive"].clone()
        ),
        (json!("UPSTREAM_UNAVAILABLE"), json!(true))
    );

    sidecar.reset().await;
    fail(&sidecar, "/accounts", 410, json!({})).await;
    client.post(&format!("{BASE}/sync")).send().await;
    settle(&app).await;
    let current = status(&client).await;
    assert_eq!(
        (
            current["lastSyncError"].clone(),
            current["isActive"].clone()
        ),
        (json!("SESSION_EXPIRED"), json!(false))
    );
    let again = client.post(&format!("{BASE}/sync")).send().await;
    assert_eq!(
        again.json()["detail"],
        "The Caisse d'Epargne session expired. Please reconnect."
    );
}

#[tokio::test]
async fn a_sync_never_resurrects_an_account_the_user_deleted() {
    let sidecar = MockServer::start().await;
    let (app, client, member_id) = app(&sidecar).await;
    let snapshot = json!({ "accounts": [account("1", "CURRENT_ACCOUNT", "10", json!({})), account("2", "LDDS", "20", json!({}))] });
    sync_with(&sidecar, &app, &client, member_id, snapshot.clone()).await;
    picsou::db::query("UPDATE account SET deleted_at = now() WHERE external_account_id = 'ce_2'")
        .execute(&app.state.db)
        .await
        .unwrap();
    sidecar.reset().await;
    assert_eq!(
        sync_with(&sidecar, &app, &client, member_id, snapshot).await["syncStatus"],
        "SUCCESS"
    );
    assert_eq!(
        count(
            &app,
            "SELECT count(*) FROM account WHERE external_account_id = 'ce_2'"
        )
        .await,
        1
    );
    assert_eq!(
        count(
            &app,
            "SELECT count(*) FROM account WHERE deleted_at IS NULL"
        )
        .await,
        1
    );
}

async fn category_of(app: &TestApp, external_id: &str) -> Option<String> {
    picsou::db::query_scalar(
        "SELECT c.slug FROM \"transaction\" t LEFT JOIN category c ON c.id = t.category_id WHERE t.external_id = $1",
    )
    .bind(external_id)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn a_deferred_card_settlement_is_tagged_as_an_internal_transfer_once() {
    let sidecar = MockServer::start().await;
    let (app, client, member_id) = app(&sidecar).await;
    let snapshot = json!({ "accounts": [
        account("1", "CURRENT_ACCOUNT", "100", json!({ "transactions": [
            tx("s1", "2026-01-10", "-50.00", "PRLV CARTE", json!({ "typeCode": "04" })),
            tx("s2", "2026-01-10", "-50", "OTHER", json!({ "typeCode": 7 })),
            tx("s3", "2026-02-10", "-9", "NOT YET DUE", json!({})),
        ] })),
        account("5", "CARD", "-9", json!({ "cardNature": "DEFERRED_DEBIT", "parentExternalId": "1", "nextDueDate": "2026-02-10", "transactions": [
            tx("c1", "2025-12-20", "-30", "SHOP", json!({ "dueDate": "2026-01-10" })),
            tx("c2", "2025-12-22", "-20", "SHOP", json!({ "dueDate": "2026-01-10" })),
            tx("c3", "2026-01-12", "-9", "SHOP", json!({ "dueDate": "2026-02-10" })),
        ] })),
    ] });
    assert_eq!(
        sync_with(&sidecar, &app, &client, member_id, snapshot.clone()).await["syncStatus"],
        "SUCCESS"
    );
    assert_eq!(
        category_of(&app, "ce_s1").await.as_deref(),
        Some("virement-interne")
    );
    assert_eq!(
        category_of(&app, "ce_s2").await,
        None,
        "another type code is left alone"
    );
    assert_eq!(category_of(&app, "ce_s3").await, None);
    assert_eq!(
        category_of(&app, "ce_c1").await,
        None,
        "the card operations stay expenses"
    );

    picsou::db::query("UPDATE \"transaction\" SET category_id = NULL WHERE external_id = 'ce_s1'")
        .execute(&app.state.db)
        .await
        .unwrap();
    let chosen: i64 = picsou::db::query_scalar(
        "SELECT id FROM category WHERE member_id = $1 AND slug = 'credit'",
    )
    .bind(member_id)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    picsou::db::query("UPDATE \"transaction\" SET category_id = $1 WHERE external_id = 'ce_s1'")
        .bind(chosen)
        .execute(&app.state.db)
        .await
        .unwrap();
    sidecar.reset().await;
    sync_with(&sidecar, &app, &client, member_id, snapshot).await;
    assert_eq!(
        category_of(&app, "ce_s1").await.as_deref(),
        Some("credit"),
        "a chosen category is never overwritten"
    );
}

#[tokio::test]
async fn a_loan_gets_its_debt_and_its_disbursement_and_installments_are_tagged() {
    let sidecar = MockServer::start().await;
    let (app, client, member_id) = app(&sidecar).await;
    let iban = "FR7612345000000000000000001";
    let snapshot = json!({ "accounts": [
        account("1", "CURRENT_ACCOUNT", "100", json!({ "iban": iban, "transactions": [
            tx("d1", "2025-10-02", "10000", "VIR FINANCEMENT PRET", json!({})),
            tx("i1", "2025-11-07", "-300.01", "ECHEANCE PRET", json!({})),
            tx("i2", "2025-12-03", "-300", "ECHEANCE PRET", json!({})),
            tx("i3", "2025-12-04", "-300", "AUTRE", json!({})),
            tx("i4", "2025-10-20", "-300", "BEFORE THE FIRST", json!({})),
        ] })),
        account("4", "LOAN", "-9400", json!({ "loan": {
            "borrowedAmount": "10000", "interestRate": "0.03", "monthlyPayment": "300", "insuranceMonthly": "4.5",
            "signatureDate": "2025-10-01", "firstDueDate": "2025-11-05", "lastDueDate": "2028-10-05",
            "durationMonths": 36, "linkedIban": iban
        } })),
    ] });
    assert_eq!(
        sync_with(&sidecar, &app, &client, member_id, snapshot).await["syncStatus"],
        "SUCCESS"
    );
    let debt: DebtRow = picsou::db::query_as(
        "SELECT d.borrowed_amount, d.interest_rate, d.monthly_payment, d.insurance_monthly, d.lender_name, d.start_date, \
         d.end_date, l.external_account_id FROM debt d JOIN account l ON l.id = d.linked_account_id",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        debt,
        (
            d("10000"),
            d("0.03"),
            d("300"),
            Some(d("4.5")),
            "Caisse d'Epargne".into(),
            "2025-10-05".parse().unwrap(),
            "2028-10-05".parse().unwrap(),
            "ce_1".into()
        )
    );
    assert_eq!(
        category_of(&app, "ce_d1").await.as_deref(),
        Some("virement-interne")
    );
    assert_eq!(
        category_of(&app, "ce_i1").await.as_deref(),
        Some("credit"),
        "within a cent and five days"
    );
    assert_eq!(
        category_of(&app, "ce_i2").await,
        None,
        "two debits match the same due date"
    );
    assert_eq!(
        category_of(&app, "ce_i4").await,
        None,
        "before the first due date"
    );
}

#[tokio::test]
async fn unusable_loan_details_import_the_loan_but_leave_its_debt_alone() {
    let sidecar = MockServer::start().await;
    let (app, client, member_id) = app(&sidecar).await;
    let snapshot = json!({ "accounts": [account("4", "LOAN", "-100", json!({ "loan": {
        "borrowedAmount": "1000000000000", "interestRate": "0.03", "monthlyPayment": "300",
        "firstDueDate": "2025-11-05", "lastDueDate": "2028-10-05"
    } }))] });
    assert_eq!(
        sync_with(&sidecar, &app, &client, member_id, snapshot).await["syncStatus"],
        "SUCCESS"
    );
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 1);
    assert_eq!(count(&app, "SELECT count(*) FROM debt").await, 0);
}

#[tokio::test]
async fn a_per_keeps_its_euro_fund_as_cash_and_its_units_as_holdings() {
    let sidecar = MockServer::start().await;
    let (app, client, member_id) = app(&sidecar).await;
    let position = |compartment: i32, quantity: &str, value: &str| {
        json!({
            "isin": "FR0010135103", "name": "Carmignac Patrimoine", "quantity": n(quantity), "unitPrice": n("100"),
            "value": n(value), "priceDate": "2026-01-14", "compartmentId": compartment
        })
    };
    let snapshot = json!({ "accounts": [account("7", "PER", "1800", json!({ "per": {
        "balance": n("1800"), "euroFundBalance": n("300"), "valuationDate": "2026-01-14",
        "positions": [position(1, "10", "1000"), position(2, "5", "500")]
    } }))] });
    assert_eq!(
        sync_with(&sidecar, &app, &client, member_id, snapshot).await["syncStatus"],
        "SUCCESS"
    );
    let account: (Decimal, Option<Decimal>, String) =
        picsou::db::query_as("SELECT current_balance, cash_balance, color FROM account WHERE external_account_id = 'ce_7'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(account, (d("1800"), Some(d("300")), "#8b5cf6".into()));
    let holding: (String, Decimal, Option<Decimal>, Option<Decimal>) = picsou::db::query_as(
        "SELECT ticker, quantity, current_price, provider_value_eur FROM account_holding",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        holding,
        (
            "FR0010135103".into(),
            d("15"),
            Some(d("100")),
            Some(d("1500"))
        )
    );
    let snapshot: (Decimal, Decimal) = picsou::db::query_as(
        "SELECT balance, invested_amount FROM balance_snapshot WHERE date = DATE '2026-01-14'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        snapshot,
        (d("1800"), d("1800")),
        "the bank NAV pairs with itself"
    );
}

#[tokio::test]
async fn a_per_whose_units_do_not_add_up_refuses_the_snapshot() {
    let sidecar = MockServer::start().await;
    let (app, client, member_id) = app(&sidecar).await;
    let snapshot = json!({ "accounts": [account("7", "PER", "1800", json!({ "per": {
        "balance": n("1800"), "euroFundBalance": n("300"), "valuationDate": "2026-01-14",
        "positions": [{ "isin": "FR0010135103", "quantity": n("10"), "unitPrice": n("100"), "value": n("1000"),
            "priceDate": "2026-01-14", "compartmentId": 1 }]
    } }))] });
    let current = sync_with(&sidecar, &app, &client, member_id, snapshot).await;
    assert_eq!(current["lastSyncError"], "UPSTREAM_FORMAT_CHANGED");
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 0);
}

#[tokio::test]
async fn an_undecryptable_session_is_recorded_as_unusable() {
    let sidecar = MockServer::start().await;
    let (app, client, member_id) = app(&sidecar).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    picsou::db::query("UPDATE sidecar_session SET session_state = 'garbage'")
        .execute(&app.state.db)
        .await
        .unwrap();
    let response = client.post(&format!("{BASE}/sync")).send().await;
    assert_eq!(response.json()["code"], "INVALID_SESSION_STATE");
    let current = status(&client).await;
    assert_eq!(
        (current["syncStatus"].clone(), current["isActive"].clone()),
        (json!("FAILED"), json!(false))
    );
}

#[tokio::test]
async fn sync_is_rate_limited_and_delete_never_claims_the_bank_session_ended() {
    let sidecar = MockServer::start().await;
    let (app, client, member_id) = app(&sidecar).await;
    for _ in 0..10 {
        assert_eq!(
            client.post(&format!("{BASE}/sync")).send().await.status,
            422
        );
    }
    let limited = client.post(&format!("{BASE}/sync")).send().await;
    assert_eq!(limited.status, 429);
    store_session(&app, BANK, member_id, "plain-state").await;
    let deleted = client.delete(&format!("{BASE}/session")).send().await;
    assert_eq!(
        deleted.json(),
        json!({ "removed": true, "bankSessionRevoked": false, "message":
            "The stored session was deleted from Picsou. The Caisse d'Epargne session itself is not revoked and may remain active until it expires." })
    );
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

#[tokio::test]
async fn recovery_fails_only_this_banks_in_flight_syncs() {
    let sidecar = MockServer::start().await;
    let (app, _client, member_id) = app(&sidecar).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    store_session(&app, SidecarBank::Bourso, member_id, "plain-state").await;
    picsou::db::query("UPDATE sidecar_session SET sync_status = 'RUNNING'")
        .execute(&app.state.db)
        .await
        .unwrap();
    let recovered = picsou::connectors::caisse_epargne::recover_interrupted_syncs(&app.state)
        .await
        .unwrap();
    assert_eq!(recovered, 1);
    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert_eq!(
        session.last_sync_error.map(|c| c.as_str()),
        Some("INTERNAL_ERROR")
    );
}
