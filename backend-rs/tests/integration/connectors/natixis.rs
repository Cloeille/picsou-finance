//! NatixisController and NatixisSyncService over HTTP, the sidecar stubbed.

use picsou::ledger::sidecar::SidecarBank;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{body_json, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{
    admin, answer, app_with_sidecar, count, d, n, session_of, settle, store_session,
};

const URL: &str = "NATIXIS_AUTH_URL";
const BANK: SidecarBank = SidecarBank::Natixis;

fn positions(operations: Value) -> Value {
    json!({
        "sessionState": "renewed-state",
        "plans": [{
            "externalId": "pee", "name": "PEE", "employer": "ACME", "balanceEur": n("100"), "snapshotComplete": true,
            "positions": [{ "label": "Fonds Monétaire", "quantity": n("10"), "unitValue": n("10"), "valueEur": n("100"),
                            "pnlEur": n("5") }]
        }],
        "operationsComplete": true, "operationsFrom": "2020-01-01", "operations": operations
    })
}

fn operation(id: &str, kind: &str, date: &str) -> Value {
    json!({
        "externalId": id, "planExternalId": "pee", "date": date, "type": kind, "label": null,
        "fundLabel": "Fonds Monétaire", "quantity": n("2"), "amountEur": n("20"), "feesEur": n("1")
    })
}

async fn login(app: &picsou::testing::TestApp, sidecar: &MockServer) -> picsou::testing::Client {
    answer(
        sidecar,
        "/initiate",
        json!({ "processId": "p-1", "mfaRequired": true, "mfaType": "email" }),
    )
    .await;
    answer(sidecar, "/complete", json!({ "sessionState": "state-1" })).await;
    let (client, _) = admin(app).await;
    let initiated = client
        .post("/api/natixis/auth/initiate")
        .json(&json!({ "email": "a@b.c", "password": "secret" }))
        .send()
        .await;
    assert_eq!(
        initiated.json(),
        json!({ "processId": "p-1", "mfaRequired": true, "mfaType": "email" })
    );
    let completed = client
        .post("/api/natixis/auth/complete")
        .json(&json!({ "processId": "p-1", "code": "123456" }))
        .send()
        .await;
    assert_eq!(completed.status, 200, "{}", completed.text());
    settle(app).await;
    client
}

#[tokio::test]
async fn a_login_imports_plans_and_operations_and_keeps_the_renewed_state() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/positions",
        positions(json!([
            operation("op1", "SELL", "2024-02-01"),
            operation("op2", "BUY", "2024-01-01")
        ])),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let client = login(&app, &sidecar).await;
    let (_, member_id) = admin(&app).await;

    assert_eq!(
        client.get("/api/natixis/status").send().await.json()["syncStatus"],
        "SUCCESS"
    );
    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert_eq!(
        app.state.crypto.decrypt(&session.session_state).unwrap(),
        "renewed-state"
    );
    let ticker: String = sqlx::query_scalar("SELECT ticker FROM account_holding")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert!(
        ticker.starts_with("FONDS-MONETAIRE-") || ticker.starts_with("FONDS-MON"),
        "{ticker}"
    );
    assert_eq!(
        ticker.len(),
        "FONDS-MON-TAIRE".len() + 9,
        "label plus an 8-digit hash: {ticker}"
    );
    let rows: Vec<(String, String, Decimal, bool, String)> = sqlx::query_as(
        "SELECT external_id, tx_type, amount, budget_neutral, description FROM \"transaction\" ORDER BY date",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (
                "natixis_op2".into(),
                "BUY".into(),
                d("-21"),
                true,
                "Investissement".into()
            ),
            (
                "natixis_op1".into(),
                "SELL".into(),
                d("19"),
                true,
                "Désinvestissement".into()
            ),
        ]
    );
}

#[tokio::test]
async fn a_second_import_updates_operations_in_place_and_drops_settled_vanished_ones() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/positions"))
        .and(body_json(json!({ "sessionState": "state-1" })))
        .respond_with(ResponseTemplate::new(200).set_body_json(positions(json!([
            operation("old", "BUY", "2019-06-01"),
            operation("recent", "BUY", "2024-06-01"),
            operation("kept", "BUY", "2024-07-01"),
        ]))))
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/positions"))
        .and(body_json(json!({ "sessionState": "renewed-state" })))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(positions(json!([operation(
                "kept",
                "SELL",
                "2024-07-01"
            )]))),
        )
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let client = login(&app, &sidecar).await;

    assert_eq!(client.post("/api/natixis/sync").send().await.status, 202);
    settle(&app).await;

    let rows: Vec<(String, String)> =
        sqlx::query_as("SELECT external_id, tx_type FROM \"transaction\" ORDER BY date")
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(
        rows,
        vec![
            ("natixis_old".into(), "BUY".into()),
            ("natixis_kept".into(), "SELL".into())
        ],
        "inside the settlement margin a vanished row stays; past it, it goes"
    );
}

#[tokio::test]
async fn an_unreadable_history_keeps_the_stored_operations_but_imports_the_plans() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/positions",
        positions(json!([{ "externalId": "x", "type": "SWAP" }])),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let client = login(&app, &sidecar).await;

    assert_eq!(
        client.get("/api/natixis/status").send().await.json()["syncStatus"],
        "SUCCESS"
    );
    assert_eq!(count(&app, "SELECT count(*) FROM \"transaction\"").await, 0);
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 1);
}

#[tokio::test]
async fn a_login_without_a_code_step_is_refused() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "mfaRequired": false, "sessionState": "s" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let response = client
        .post("/api/natixis/auth/initiate")
        .json(&json!({ "email": "a@b.c", "password": "secret" }))
        .send()
        .await;

    assert_eq!(response.json()["code"], "INVALID_DATA");
    assert_eq!(
        response.json()["detail"],
        "Natixis did not return a login attempt"
    );
}

#[tokio::test]
async fn a_disconnect_cancels_the_pending_login_and_voids_it() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "p-1", "mfaRequired": true }),
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/cancel"))
        .and(body_json(json!({ "processId": "p-1" })))
        .respond_with(ResponseTemplate::new(204))
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    client
        .post("/api/natixis/auth/initiate")
        .json(&json!({ "email": "a@b.c", "password": "secret" }))
        .send()
        .await;

    assert_eq!(
        client.delete("/api/natixis/session").send().await.status,
        204
    );
    let complete = client
        .post("/api/natixis/auth/complete")
        .json(&json!({ "processId": "p-1", "code": "1234" }))
        .send()
        .await;

    assert_eq!(complete.json()["code"], "AUTH_ATTEMPT_EXPIRED");
}

#[tokio::test]
async fn the_code_is_required_and_numeric() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let missing = client
        .post("/api/natixis/auth/complete")
        .json(&json!({ "processId": "p" }))
        .send()
        .await;
    let letters = client
        .post("/api/natixis/auth/complete")
        .json(&json!({ "processId": "p", "code": "12ab" }))
        .send()
        .await;

    assert_eq!(missing.json()["errors"]["code"], "must not be null");
    assert_eq!(letters.json()["errors"]["code"], r#"must match "\d{4,12}""#);
}

#[tokio::test]
async fn the_stale_sweep_fails_only_rows_no_job_touched_for_ten_minutes() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let id = store_session(&app, BANK, member_id, "s").await;
    sqlx::query(
        "UPDATE sidecar_session SET sync_status = 'RUNNING', updated_at = $2 WHERE id = $1",
    )
    .bind(id)
    .bind(app.state.clock.now() - chrono::Duration::minutes(5))
    .execute(&app.state.db)
    .await
    .unwrap();

    assert_eq!(
        picsou::connectors::natixis::recover_stale_syncs(&app.state)
            .await
            .unwrap(),
        0
    );
    app.clock.advance(std::time::Duration::from_secs(6 * 60));
    assert_eq!(
        picsou::connectors::natixis::recover_stale_syncs(&app.state)
            .await
            .unwrap(),
        1
    );
    assert_eq!(
        client.get("/api/natixis/status").send().await.json()["lastSyncError"],
        "INTERNAL_ERROR"
    );
}
