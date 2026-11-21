//! BanquePopulaireController over HTTP, the sidecar stubbed: what it adds to the shared BPCE
//! engine (tested with Caisse d'Epargne) is the regional bank, sent at login, stored on the
//! session and stamped on the imported accounts.

use picsou::ledger::sidecar::SidecarBank;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{body_json, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::caisse_epargne::{account, keypad, mount_keypad_and_complete, positions, tx};
use super::support::{admin, answer, app_with_sidecar, d, session_of, settle};

const URL: &str = "BANQUE_POPULAIRE_AUTH_URL";
const BANK: SidecarBank = SidecarBank::BanquePopulaire;
const BASE: &str = "/api/banque-populaire";

async fn mount_initiate(sidecar: &MockServer, body: Value) {
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_json(body))
        .respond_with(ResponseTemplate::new(200).set_body_json(
            json!({ "processId": "p-1", "keypad": keypad(10), "expiresInSeconds": 120 }),
        ))
        .expect(1)
        .mount(sidecar)
        .await;
}

#[tokio::test]
async fn a_login_at_a_regional_bank_stores_its_region_and_the_sync_names_the_accounts_after_it() {
    let sidecar = MockServer::start().await;
    mount_initiate(
        &sidecar,
        json!({ "customerId": "A1234567", "region": "RIVES_DE_PARIS" }),
    )
    .await;
    mount_keypad_and_complete(&sidecar).await;
    answer(&sidecar, "/accounts", json!({ "accounts": [
        account("1", "CURRENT_ACCOUNT", "10", json!({ "transactions": [tx("t1", "2026-01-10", "-1", "", json!({}))] })),
        account("bp_2", "COOPERATIVE_SHARES", "20", json!({})),
    ] })).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let started = client
        .post(&format!("{BASE}/auth/initiate"))
        .json(&json!({ "customerId": "A1234567", "region": "RIVES_DE_PARIS" }))
        .send()
        .await;
    assert_eq!(started.status, 200, "{}", started.text());
    client
        .post(&format!("{BASE}/auth/keypad"))
        .json(&positions())
        .send()
        .await;
    let completed = client
        .post(&format!("{BASE}/auth/complete"))
        .json(&json!({ "processId": "p-1" }))
        .send()
        .await;
    assert_eq!(completed.json(), json!({ "connected": true }));
    let session = session_of(&app, BANK, member_id).await.expect("session");
    assert_eq!(session.region.as_deref(), Some("RIVES_DE_PARIS"));

    assert_eq!(
        client.post(&format!("{BASE}/sync")).send().await.status,
        202
    );
    settle(&app).await;
    let status: Value = client.get(&format!("{BASE}/status")).send().await.json();
    assert_eq!(status["syncStatus"], "SUCCESS", "{status}");
    assert_eq!(status["remembered"], true);
    let accounts: Vec<(String, String, String, Decimal, Option<String>)> = sqlx::query_as(
        "SELECT external_account_id, name, provider, current_balance, logo_url FROM account ORDER BY external_account_id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    let provider = "Banque Populaire Rives de Paris".to_owned();
    assert_eq!(
        accounts,
        vec![
            (
                "bp_1".into(),
                "Banque Populaire account".into(),
                provider.clone(),
                d("10"),
                None
            ),
            (
                "bp_2".into(),
                "Parts sociales".into(),
                provider,
                d("20"),
                None
            ),
        ]
    );
    let label: String = sqlx::query_scalar("SELECT description FROM \"transaction\"")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(label, "Banque Populaire transaction");
}

#[tokio::test]
async fn an_empty_identifier_reuses_the_remembered_one_at_its_own_region() {
    let sidecar = MockServer::start().await;
    mount_initiate(
        &sidecar,
        json!({ "customerId": "B7654321", "region": "BRED" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let credentials = app
        .state
        .crypto
        .encrypt(r#"{"customerId":"B7654321"}"#)
        .unwrap();
    sqlx::query(
        "INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, region, last_validated_at, \
         is_active, sync_status) VALUES ('BANQUE_POPULAIRE', $1, 'x', $2, 'BRED', now(), true, 'IDLE')",
    )
    .bind(member_id)
    .bind(credentials)
    .execute(&app.state.db)
    .await
    .unwrap();

    let started = client
        .post(&format!("{BASE}/auth/initiate"))
        .json(&json!({ "customerId": "", "region": "NORD" }))
        .send()
        .await;
    assert_eq!(started.status, 200, "{}", started.text());
}

#[tokio::test]
async fn the_region_is_read_by_its_exact_name_before_any_attempt_is_spent() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    for body in [
        json!({ "customerId": "A1234567", "region": "bred" }),
        json!({ "customerId": "A1234567", "region": "ATLANTIS" }),
        json!({ "customerId": "A1234567" }),
        json!({ "customerId": "A12-4567", "region": "BRED" }),
        json!({ "customerId": "", "region": 1 }),
    ] {
        let response = client
            .post(&format!("{BASE}/auth/initiate"))
            .json(&body)
            .send()
            .await;
        assert_eq!(response.status, 400, "{body}");
        assert_eq!(
            response.json()["detail"],
            "The Banque Populaire request is not valid"
        );
    }
    let status: Value = client.get(&format!("{BASE}/status")).send().await.json();
    assert_eq!(
        status,
        json!({ "isActive": false, "syncStatus": "IDLE", "unsupported": [], "remembered": false, "unsupportedCount": 0 })
    );
    assert!(
        sidecar
            .received_requests()
            .await
            .unwrap_or_default()
            .is_empty()
    );
}
