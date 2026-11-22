//! BourseDirectController and BourseDirectSyncService over HTTP, the sidecar stubbed.

use picsou::ledger::sidecar::SidecarBank;
use serde_json::{Value, json};
use std::time::Duration;
use wiremock::matchers::{body_json, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{admin, app_with_sidecar, d, member, session_of, settle, store_session};
use rust_decimal::Decimal;

const URL: &str = "BOURSE_DIRECT_AUTH_URL";
const BANK: SidecarBank = SidecarBank::BourseDirect;

fn n(number: &str) -> Value {
    serde_json::from_str(number).expect("JSON number")
}

fn position(isin: Option<&str>, symbol: &str, quantity: &str, value: &str) -> Value {
    json!({
        "isin": isin, "symbol": symbol, "label": format!("{symbol} SA"), "quantity": n(quantity),
        "buyingPriceEur": 80, "currentPrice": 100, "quoteCurrency": "EUR",
        "currentValueEur": n(value), "pnlEur": 200
    })
}

fn account(positions: Vec<Value>, balance: &str, cash: &str) -> Value {
    json!({
        "externalId": "pea-123", "name": "PEA", "type": "PEA", "balanceEur": n(balance),
        "cashBalance": n(cash), "positions": positions, "snapshotComplete": true
    })
}

async fn mount_accounts(sidecar: &MockServer, body: Value) {
    Mock::given(method("POST"))
        .and(path("/accounts"))
        .respond_with(ResponseTemplate::new(200).set_body_json(body))
        .mount(sidecar)
        .await;
}

async fn status(client: &picsou::testing::Client) -> Value {
    client.get("/api/bourse-direct/status").send().await.json()
}

#[tokio::test]
async fn status_without_a_session_is_inactive_and_idle() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let response = client.get("/api/bourse-direct/status").send().await;

    assert_eq!(response.status, 200);
    assert_eq!(
        response.json(),
        json!({ "isActive": false, "syncStatus": "IDLE" })
    );
}

#[tokio::test]
async fn initiate_with_mfa_relays_the_challenge_and_stores_nothing() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_json(json!({ "login": "jdoe", "password": "secret" })))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "processId": "p-1", "mfaRequired": true, "mfaType": "email"
        })))
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let response = client
        .post("/api/bourse-direct/auth/initiate")
        .json(&json!({ "login": "jdoe", "password": "secret" }))
        .send()
        .await;

    assert_eq!(response.status, 200);
    assert_eq!(
        response.json(),
        json!({ "processId": "p-1", "mfaRequired": true, "mfaType": "email" })
    );
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

#[tokio::test]
async fn initiate_without_mfa_stores_the_session_and_runs_the_first_import() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "mfaRequired": false, "sessionState": "plain-state"
        })))
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/accounts"))
        .and(body_json(json!({ "sessionState": "plain-state" })))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([account(
            vec![position(Some("FR0000120271"), "ACME", "10", "1000")],
            "1250",
            "250"
        )])))
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let response = client
        .post("/api/bourse-direct/auth/initiate")
        .json(&json!({ "login": "jdoe", "password": "secret" }))
        .send()
        .await;
    settle(&app).await;

    assert_eq!(response.status, 200);
    assert_eq!(response.json(), json!({ "mfaRequired": false }));
    let session = session_of(&app, BANK, member_id).await.expect("session");
    assert_ne!(session.session_state, "plain-state", "stored encrypted");
    assert_eq!(
        app.state.crypto.decrypt(&session.session_state).unwrap(),
        "plain-state"
    );
    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
}

#[tokio::test]
async fn complete_writes_the_portfolio_holdings_and_snapshot() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/complete"))
        .and(body_json(json!({ "processId": "p-1", "code": "123456" })))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "sessionState": "plain-state" })),
        )
        .mount(&sidecar)
        .await;
    mount_accounts(
        &sidecar,
        json!([account(
            vec![position(Some("FR0000120271"), "ACME", "10", "1000")],
            "1250",
            "250"
        )]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let response = client
        .post("/api/bourse-direct/auth/complete")
        .json(&json!({ "processId": "p-1", "code": "123456" }))
        .send()
        .await;
    settle(&app).await;

    assert_eq!(response.status, 200);
    assert_eq!(response.json()["isActive"], true);
    let after = status(&client).await;
    assert_eq!(after["syncStatus"], "SUCCESS");
    assert!(
        after["lastSyncCompletedAt"]
            .as_str()
            .unwrap()
            .ends_with('Z')
    );
    let (name, kind, provider, manual, balance, cash, color): (
        String,
        String,
        String,
        bool,
        Decimal,
        Decimal,
        String,
    ) = picsou::db::query_as(
        "SELECT name, type::text, provider, is_manual, current_balance, cash_balance, color \
             FROM account WHERE external_account_id = 'bd_pea-123' AND member_id = $1",
    )
    .bind(member_id)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        (
            name.as_str(),
            kind.as_str(),
            provider.as_str(),
            manual,
            color.as_str()
        ),
        ("PEA", "PEA", "Bourse Direct", false, "#10b981")
    );
    assert_eq!((balance, cash), (d("1250"), d("250")));
    let holding: (String, String, Decimal, String, Decimal, Decimal) = picsou::db::query_as(
        "SELECT ticker, name, average_buy_in, quote_currency, provider_value_eur, provider_pnl_eur \
         FROM account_holding",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        holding,
        (
            "FR0000120271".into(),
            "ACME SA".into(),
            d("80"),
            "EUR".into(),
            d("1000"),
            d("200")
        ),
        "without OpenFIGI the ISIN is the ticker"
    );
    let snapshot: (Decimal, Decimal) =
        picsou::db::query_as("SELECT balance, invested_amount FROM balance_snapshot")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(snapshot, (d("1250"), d("1050")), "cash plus 10 x 80");
}

#[tokio::test]
async fn a_rejected_code_maps_to_invalid_otp_with_the_banks_wording() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/complete"))
        .respond_with(ResponseTemplate::new(401))
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let response = client
        .post("/api/bourse-direct/auth/complete")
        .json(&json!({ "processId": "p-1", "code": "123456" }))
        .send()
        .await;

    assert_eq!(response.status, 422);
    let body = response.json();
    assert_eq!(body["code"], "INVALID_OTP");
    assert_eq!(
        body["detail"],
        SidecarBank::BourseDirect
            .message(picsou::ledger::sidecar::SidecarErrorCode::InvalidOtp)
            .unwrap()
    );
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

#[tokio::test]
async fn payload_validation_rejects_an_oversized_login_and_a_malformed_code() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let initiate = client
        .post("/api/bourse-direct/auth/initiate")
        .json(&json!({ "login": "x".repeat(101), "password": "secret" }))
        .send()
        .await;
    let complete = client
        .post("/api/bourse-direct/auth/complete")
        .json(&json!({ "processId": "p-1", "code": "12a456" }))
        .send()
        .await;

    assert_eq!(initiate.status, 422);
    assert_eq!(
        initiate.json()["errors"]["login"],
        "size must be between 0 and 100"
    );
    assert_eq!(complete.status, 422);
    assert_eq!(complete.json()["errors"]["code"], r#"must match "\d{6}""#);
    assert!(sidecar.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn the_sixth_authentication_attempt_from_one_ip_is_rate_limited() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .respond_with(ResponseTemplate::new(401))
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    let attempt = || {
        client
            .post("/api/bourse-direct/auth/initiate")
            .json(&json!({ "login": "jdoe", "password": "wrong" }))
            .send()
    };

    for _ in 0..5 {
        let response = attempt().await;
        assert_eq!(response.status, 422);
        assert_eq!(response.json()["code"], "INVALID_CREDENTIALS");
    }
    let limited = attempt().await;

    assert_eq!(limited.status, 429);
    assert_eq!(
        limited.json()["detail"],
        "Too many Bourse Direct authentication attempts. Please wait before retrying."
    );
    assert_eq!(sidecar.received_requests().await.unwrap().len(), 5);
}

#[tokio::test]
async fn sync_without_a_session_asks_to_reconnect() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let response = client.post("/api/bourse-direct/sync").send().await;

    assert_eq!(response.status, 422);
    let body = response.json();
    assert_eq!(body["code"], "SESSION_EXPIRED");
    assert_eq!(
        body["detail"],
        "No active Bourse Direct session. Please reconnect."
    );
}

#[tokio::test]
async fn sync_answers_202_with_the_queued_status_then_succeeds() {
    let sidecar = MockServer::start().await;
    mount_accounts(
        &sidecar,
        json!([account(
            vec![position(None, "ACME", "10", "1000")],
            "1000",
            "0"
        )]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;

    let response = client.post("/api/bourse-direct/sync").send().await;
    settle(&app).await;

    assert_eq!(response.status, 202);
    assert_eq!(response.json()["isActive"], true);
    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
}

#[tokio::test]
async fn status_and_clear_use_only_the_current_member() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (admin_client, admin_id) = admin(&app).await;
    let (other, other_id) = member(&app, "bob").await;
    store_session(&app, BANK, admin_id, "admin-state").await;

    assert_eq!(status(&other).await["isActive"], false);
    let cleared = other.delete("/api/bourse-direct/session").send().await;

    assert_eq!(cleared.status, 204);
    assert!(session_of(&app, BANK, admin_id).await.is_some());
    assert!(session_of(&app, BANK, other_id).await.is_none());
    assert_eq!(status(&admin_client).await["isActive"], true);
    let mine = admin_client
        .delete("/api/bourse-direct/session")
        .send()
        .await;
    assert_eq!(mine.status, 204);
    assert!(session_of(&app, BANK, admin_id).await.is_none());
}

async fn failed_sync(body: Value) -> (Value, i64, picsou::testing::TestApp) {
    let sidecar = MockServer::start().await;
    mount_accounts(&sidecar, body).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    client.post("/api/bourse-direct/sync").send().await;
    settle(&app).await;
    (status(&client).await, member_id, app)
}

#[tokio::test]
async fn an_incomplete_snapshot_fails_without_touching_existing_holdings() {
    let mut incomplete = account(vec![position(None, "ACME", "10", "1000")], "1000", "0");
    incomplete["snapshotComplete"] = json!(false);
    let (after, _, _) = failed_sync(json!([incomplete])).await;
    assert_eq!(after["syncStatus"], "FAILED");
    assert_eq!(after["lastSyncError"], "PORTFOLIO_INCOMPLETE");
    assert_eq!(
        after["isActive"], true,
        "a transient failure keeps the session"
    );
}

#[tokio::test]
async fn every_invalid_snapshot_is_rejected_whole() {
    let mut no_currency = position(None, "ACME", "10", "1000");
    no_currency["quoteCurrency"] = Value::Null;
    let mut unsupported = account(vec![], "0", "0");
    unsupported["type"] = json!("CHECKING");
    let duplicate = account(vec![], "0", "0");
    let cases = [
        (
            json!([account(vec![no_currency], "1000", "0")]),
            "INVALID_DATA",
        ),
        (
            json!([account(
                vec![position(None, "ACME", "10", "1000")],
                "1500",
                "0"
            )]),
            "PORTFOLIO_INCOMPLETE",
        ),
        (json!([unsupported]), "INVALID_DATA"),
        (json!([duplicate.clone(), duplicate]), "INVALID_DATA"),
        (
            json!([account(
                vec![position(Some("NOT-AN-ISIN"), "ACME", "10", "1000")],
                "1000",
                "0"
            )]),
            "INVALID_DATA",
        ),
        (
            json!([account(vec![position(None, "", "10", "1000")], "1000", "0")]),
            "INVALID_DATA",
        ),
        (json!([]), "PORTFOLIO_INCOMPLETE"),
    ];
    for (body, code) in cases {
        let (after, _, app) = failed_sync(body.clone()).await;
        assert_eq!(after["lastSyncError"], code, "{body}");
        let accounts: i64 = picsou::db::query_scalar("SELECT count(*) FROM account")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
        assert_eq!(accounts, 0, "{body}");
    }
}

#[tokio::test]
async fn an_expired_session_is_deactivated_and_stays_observable() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/accounts"))
        .respond_with(
            ResponseTemplate::new(401).set_body_json(json!({ "detail": "SESSION_EXPIRED" })),
        )
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;

    client.post("/api/bourse-direct/sync").send().await;
    settle(&app).await;

    let after = status(&client).await;
    assert_eq!(after["isActive"], false);
    assert_eq!(after["syncStatus"], "FAILED");
    assert_eq!(after["lastSyncError"], "SESSION_EXPIRED");
    let again = client.post("/api/bourse-direct/sync").send().await;
    assert_eq!(again.status, 422);
    assert_eq!(
        again.json()["detail"],
        "The Bourse Direct session expired. Please reconnect."
    );
}

#[tokio::test]
async fn duplicate_positions_merge_with_weighted_prices_and_zero_lines_are_dropped() {
    let mut first = position(None, "ACME", "10", "1000");
    first["buyingPriceEur"] = json!(80);
    let mut second = position(None, "ACME", "30", "3000");
    second["buyingPriceEur"] = json!(100);
    let zero = position(None, "ZERO", "0", "0");
    let sidecar = MockServer::start().await;
    mount_accounts(
        &sidecar,
        json!([account(vec![first, second, zero], "4000", "0")]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;

    client.post("/api/bourse-direct/sync").send().await;
    settle(&app).await;

    let rows: Vec<(String, Decimal, Decimal, Decimal)> = picsou::db::query_as(
        "SELECT ticker, quantity, average_buy_in, provider_pnl_eur FROM account_holding",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(rows, vec![("ACME".into(), d("40"), d("95"), d("400"))]);
}

#[tokio::test]
async fn a_merged_line_with_one_unknown_price_keeps_it_unknown() {
    let mut first = position(None, "ACME", "10", "1000");
    first["buyingPriceEur"] = Value::Null;
    let second = position(None, "ACME", "30", "3000");
    let sidecar = MockServer::start().await;
    mount_accounts(&sidecar, json!([account(vec![first, second], "4000", "0")])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;

    client.post("/api/bourse-direct/sync").send().await;
    settle(&app).await;

    let average: Option<String> =
        picsou::db::query_scalar("SELECT average_buy_in::text FROM account_holding")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(average, None);
}

#[tokio::test]
async fn an_oversized_symbol_falls_back_to_the_isin() {
    let sidecar = MockServer::start().await;
    mount_accounts(
        &sidecar,
        json!([account(
            vec![position(
                Some("fr0000120271"),
                &"S".repeat(40),
                "10",
                "1000"
            )],
            "1000",
            "0"
        )]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;

    client.post("/api/bourse-direct/sync").send().await;
    settle(&app).await;

    let ticker: String = picsou::db::query_scalar("SELECT ticker FROM account_holding")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(ticker, "FR0000120271");
    let isin: String =
        picsou::db::query_scalar("SELECT isin FROM security_profile WHERE ticker = 'FR0000120271'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(
        isin, "FR0000120271",
        "the ticker to ISIN pair is remembered"
    );
}

#[tokio::test]
async fn a_soft_deleted_account_is_not_resurrected() {
    let sidecar = MockServer::start().await;
    mount_accounts(&sidecar, json!([account(vec![], "0", "0")])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    picsou::db::query(
        "INSERT INTO account (member_id, name, type, currency, current_balance, external_account_id, is_manual, \
         color, deleted_at) VALUES ($1, 'Gone', 'PEA', 'EUR', 0, 'bd_pea-123', false, '#fff', now())",
    )
    .bind(member_id)
    .execute(&app.state.db)
    .await
    .unwrap();
    store_session(&app, BANK, member_id, "plain-state").await;

    client.post("/api/bourse-direct/sync").send().await;
    settle(&app).await;

    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
    let live: i64 =
        picsou::db::query_scalar("SELECT count(*) FROM account WHERE deleted_at IS NULL")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(live, 0);
}

#[tokio::test]
async fn a_replaced_session_keeps_an_old_job_from_committing() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/accounts"))
        .and(body_json(json!({ "sessionState": "old-state" })))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!([account(vec![], "0", "0")]))
                .set_delay(Duration::from_millis(600)),
        )
        .mount(&sidecar)
        .await;
    let mut fresh = account(vec![], "0", "0");
    fresh["externalId"] = json!("fresh");
    Mock::given(method("POST"))
        .and(path("/accounts"))
        .and(body_json(json!({ "sessionState": "new-state" })))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([fresh])))
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/complete"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "sessionState": "new-state" })),
        )
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "old-state").await;

    client.post("/api/bourse-direct/sync").send().await;
    tokio::time::sleep(Duration::from_millis(150)).await;
    let relogin = client
        .post("/api/bourse-direct/auth/complete")
        .json(&json!({ "processId": "p-1", "code": "123456" }))
        .send()
        .await;
    settle(&app).await;

    assert_eq!(relogin.status, 200);
    let ids: Vec<String> =
        picsou::db::query_scalar("SELECT external_account_id FROM account ORDER BY id")
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(ids, vec!["bd_fresh".to_owned()]);
    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
}

#[tokio::test]
async fn startup_recovery_fails_in_flight_syncs() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let id = store_session(&app, BANK, member_id, "plain-state").await;
    picsou::db::query("UPDATE sidecar_session SET sync_status = 'RUNNING' WHERE id = $1")
        .bind(id)
        .execute(&app.state.db)
        .await
        .unwrap();

    picsou::connectors::recover_interrupted_syncs(&app.state)
        .await
        .unwrap();

    let after = status(&client).await;
    assert_eq!(after["syncStatus"], "FAILED");
    assert_eq!(after["lastSyncError"], "INTERNAL_ERROR");
    assert_eq!(after["isActive"], true);
}
