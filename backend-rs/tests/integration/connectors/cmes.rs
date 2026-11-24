//! CmesController and CmesSyncService over HTTP, the sidecar stubbed.

use picsou::ledger::sidecar::SidecarBank;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{body_partial_json, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{admin, answer, app_with_sidecar, count, d, member, n, session_of, settle};

const URL: &str = "CMES_AUTH_URL";
const BANK: SidecarBank = SidecarBank::Cmes;

fn plan(id: &str, legacy: &[&str], balance: &str) -> Value {
    json!({
        "externalId": id, "legacyExternalIds": legacy, "name": "PEE", "employer": "ACME",
        "balanceEur": n(balance), "snapshotComplete": true,
        "positions": [{ "fundCode": "F1", "label": "Fonds", "quantity": n("10"), "unitValue": n("10"),
                        "valueEur": n(balance), "pnlEur": n("0") }]
    })
}

async fn login(
    sidecar: &MockServer,
    app: &picsou::testing::TestApp,
    client: &picsou::testing::Client,
) {
    answer(
        sidecar,
        "/initiate",
        json!({ "processId": "p-1", "mfaRequired": true, "mfaType": "sms" }),
    )
    .await;
    answer(sidecar, "/complete", json!({ "sessionState": "state-1" })).await;
    let initiated = client
        .post("/api/cmes/auth/initiate")
        .json(&json!({ "login": "jdoe", "password": "secret" }))
        .send()
        .await;
    assert_eq!(initiated.status, 200, "{}", initiated.text());
    let completed = client
        .post("/api/cmes/auth/complete")
        .json(&json!({ "processId": "p-1", "code": "1234" }))
        .send()
        .await;
    assert_eq!(completed.status, 200, "{}", completed.text());
    settle(app).await;
}

#[tokio::test]
async fn a_completed_login_stores_the_credentials_encrypted_and_imports_the_plans() {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/positions", json!([plan("pee-1", &[], "100")])).await;
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
    assert_eq!(parsed, json!({ "login": "jdoe", "password": "secret" }));
    assert_eq!(
        client.get("/api/cmes/status").send().await.json()["syncStatus"],
        "SUCCESS"
    );
    let ticker: String = picsou::db::query_scalar("SELECT ticker FROM account_holding")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(ticker, "CMES-F1");
}

#[tokio::test]
async fn completing_an_unknown_or_foreign_attempt_is_refused() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "p-1", "mfaRequired": true }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    let (other, _) = member(&app, "bob").await;
    client
        .post("/api/cmes/auth/initiate")
        .json(&json!({ "login": "jdoe", "password": "secret" }))
        .send()
        .await;

    let foreign = other
        .post("/api/cmes/auth/complete")
        .json(&json!({ "processId": "p-1", "code": "1234" }))
        .send()
        .await;
    let unknown = client
        .post("/api/cmes/auth/complete")
        .json(&json!({ "processId": "nope", "code": "1234" }))
        .send()
        .await;

    for response in [foreign, unknown] {
        assert_eq!(response.status, 422);
        assert_eq!(response.json()["code"], "AUTH_ATTEMPT_EXPIRED");
        assert_eq!(
            response.json()["detail"],
            "CMES authentication attempt expired"
        );
    }
}

#[tokio::test]
async fn a_disconnect_during_the_login_voids_the_pending_attempt() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "p-1", "mfaRequired": true }),
    )
    .await;
    answer(&sidecar, "/complete", json!({ "sessionState": "s" })).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    client
        .post("/api/cmes/auth/initiate")
        .json(&json!({ "login": "jdoe", "password": "secret" }))
        .send()
        .await;

    client.delete("/api/cmes/session").send().await;
    let response = client
        .post("/api/cmes/auth/complete")
        .json(&json!({ "processId": "p-1", "code": "1234" }))
        .send()
        .await;

    assert_eq!(response.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

#[tokio::test]
async fn an_expired_session_is_renewed_unattended_with_the_stored_login() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/positions"))
        .and(body_partial_json(json!({ "sessionState": "state-1" })))
        .respond_with(
            ResponseTemplate::new(401).set_body_json(json!({ "detail": "SESSION_EXPIRED" })),
        )
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/positions"))
        .and(body_partial_json(json!({ "sessionState": "state-2" })))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([plan("pee-1", &[], "100")])))
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_partial_json(
            json!({ "unattended": true, "login": "jdoe", "sessionState": "state-1" }),
        ))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({ "mfaRequired": false, "sessionState": "state-2" })),
        )
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    login(&sidecar, &app, &client).await;

    assert_eq!(
        client.get("/api/cmes/status").send().await.json()["syncStatus"],
        "SUCCESS"
    );
    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert_eq!(
        app.state.crypto.decrypt(&session.session_state).unwrap(),
        "state-2"
    );
}

#[tokio::test]
async fn a_renewal_that_wants_a_new_second_factor_ends_the_session() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_partial_json(json!({ "unattended": true })))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({ "mfaRequired": true, "processId": "p-2" })),
        )
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/positions"))
        .respond_with(
            ResponseTemplate::new(401).set_body_json(json!({ "detail": "SESSION_EXPIRED" })),
        )
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    login(&sidecar, &app, &client).await;

    let status = client.get("/api/cmes/status").send().await.json();
    assert_eq!(status["isActive"], false);
    assert_eq!(status["lastSyncError"], "SESSION_EXPIRED");
}

#[tokio::test]
async fn rejected_stored_credentials_are_forgotten() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/positions"))
        .respond_with(
            ResponseTemplate::new(401).set_body_json(json!({ "detail": "INVALID_CREDENTIALS" })),
        )
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    login(&sidecar, &app, &client).await;

    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert!(session.encrypted_credentials.is_none());
    assert!(!session.active);
}

#[tokio::test]
async fn a_renamed_plan_inherits_its_account_and_an_unlisted_one_closes_at_zero() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/positions",
        json!([plan("pee-new", &["pee-old"], "100")]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    for (id, name) in [("cmes_pee-old", "PEE — ACME"), ("cmes_gone", "PERCO")] {
        picsou::db::query(
            "INSERT INTO account (member_id, name, type, provider, currency, current_balance, external_account_id, \
             is_manual, color) VALUES ($1, $2, 'EMPLOYEE_SAVINGS', 'Crédit Mutuel Épargne Salariale', 'EUR', 50, $3, \
             false, '#000')",
        )
        .bind(member_id)
        .bind(name)
        .bind(id)
        .execute(&app.state.db)
        .await
        .unwrap();
    }

    login(&sidecar, &app, &client).await;

    let rows: Vec<(String, Decimal)> = picsou::db::query_as(
        "SELECT external_account_id, current_balance FROM account ORDER BY id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            ("cmes_pee-new".into(), d("100")),
            ("cmes_gone".into(), d("0"))
        ]
    );
    assert_eq!(count(&app, "SELECT count(*) FROM account_holding").await, 1);
}

#[tokio::test]
async fn validation_and_separate_budgets() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let bad = client
        .post("/api/cmes/auth/complete")
        .json(&json!({ "processId": "p", "code": "12" }))
        .send()
        .await;
    assert_eq!(bad.json()["errors"]["code"], r#"must match "\d{4,8}""#);
    for _ in 0..10 {
        client
            .post("/api/cmes/auth/complete")
            .json(&json!({ "processId": "p" }))
            .send()
            .await;
    }
    let limited = client
        .post("/api/cmes/auth/complete")
        .json(&json!({ "processId": "p" }))
        .send()
        .await;
    assert_eq!(limited.status, 429);
    let initiate = client
        .post("/api/cmes/auth/initiate")
        .json(&json!({ "login": "a", "password": "b" }))
        .send()
        .await;
    assert_eq!(initiate.status, 422, "the login budget is separate");
}
