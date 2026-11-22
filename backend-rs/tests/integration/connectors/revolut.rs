//! RevolutController and RevolutSyncService over HTTP, the sidecar stubbed.

use picsou::connectors::revolut;
use picsou::ledger::sidecar::SyncStatus;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{body_partial_json, header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{admin, answer, app_with_sidecar, count, d, member, n, settle};

const URL: &str = "REVOLUT_AUTH_URL";
const IBAN: &str = "FR7630006000011234567890189";
const PHONE: &str = "+33612345678";
const PASSCODE: &str = "123456";

async fn mount_sync(sidecar: &MockServer, accounts: Value) {
    sidecar.reset().await;
    answer(sidecar, "/sync", json!({ "accounts": accounts })).await;
}

async fn discover(
    app: &picsou::testing::TestApp,
    client: &picsou::testing::Client,
    body: Value,
) -> Value {
    let accepted = client.post("/api/revolut/sync").json(&body).send().await;
    assert_eq!(accepted.status, 202, "{}", accepted.text());
    settle(app).await;
    client.get("/api/revolut/sync/progress").send().await.json()
}

#[tokio::test]
async fn status_and_progress_without_a_session_are_disconnected_and_idle() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    assert_eq!(
        client.get("/api/revolut/status").send().await.json(),
        json!({ "connected": false, "remembered": false })
    );
    assert_eq!(
        client.get("/api/revolut/sync/progress").send().await.json(),
        json!({ "running": false, "done": false, "discovered": [] })
    );
    assert_eq!(
        client.delete("/api/revolut/session").send().await.status,
        204
    );
}

#[tokio::test]
async fn discover_then_confirm_persists_selected_subset_parents_first_and_money_box_as_savings() {
    let sidecar = MockServer::start().await;
    // Pocket listed before its parent wallet on purpose.
    mount_sync(
        &sidecar,
        json!([
            {
                "externalId": "pocket-1", "name": "Pocket Holiday", "type": "CHECKING",
                "balance": n("50.00"), "currency": "EUR", "parentExternalId": "wallet-1",
                "transactions": []
            },
            {
                "externalId": "wallet-1", "name": "Revolut EUR", "type": "CHECKING",
                "iban": IBAN, "balance": n("100.00"), "currency": "EUR", "transactions": [
                    { "externalId": "tx-1", "date": "2026-06-01", "description": "Coffee", "amount": n("-3.50") }
                ]
            },
            {
                "externalId": "vault-1", "name": "Voyage", "type": "SAVINGS",
                "balance": n("200.00"), "currency": "EUR", "transactions": []
            },
            {
                "externalId": "skipped-1", "name": "Not Selected", "type": "CHECKING",
                "balance": n("99.00"), "currency": "EUR", "transactions": []
            }
        ]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let progress = discover(
        &app,
        &client,
        json!({ "phoneNumber": PHONE, "passcode": PASSCODE }),
    )
    .await;
    assert_eq!(progress["running"], false);
    assert_eq!(progress["done"], true);
    assert_eq!(progress["discovered"].as_array().unwrap().len(), 4);
    assert_eq!(progress["discovered"][1]["externalId"], "wallet-1");
    assert_eq!(progress["discovered"][1]["alreadyImported"], false);
    assert_eq!(progress["discovered"][1]["transactionCount"], 1);
    // Discovery writes nothing until confirm.
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 0);

    let confirm = client
        .post("/api/revolut/sync/confirm")
        .json(&json!({
            "selectedExternalIds": ["pocket-1", "wallet-1", "vault-1"],
            "remember": false,
            "voluntary": true
        }))
        .send()
        .await;
    assert_eq!(confirm.status, 204, "{}", confirm.text());

    // Confirming again with nothing pending fails clearly.
    let again = client
        .post("/api/revolut/sync/confirm")
        .json(
            &json!({ "selectedExternalIds": ["wallet-1"], "remember": false, "voluntary": false }),
        )
        .send()
        .await;
    assert_eq!(again.status, 422);
    assert!(
        again.json()["detail"]
            .as_str()
            .unwrap()
            .contains("pending import")
    );

    type AccountRow = (i64, String, String, Option<i64>, String);
    let accounts: Vec<AccountRow> = picsou::db::query_as(
        "SELECT id, external_account_id, type::text, parent_account_id, provider \
         FROM account WHERE member_id = $1 ORDER BY external_account_id",
    )
    .bind(member_id)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(accounts.len(), 3);
    let wallet_id = accounts.iter().find(|a| a.1 == "wallet-1").unwrap().0;
    let pocket = accounts.iter().find(|a| a.1 == "pocket-1").unwrap();
    let vault = accounts.iter().find(|a| a.1 == "vault-1").unwrap();
    assert_eq!(pocket.3, Some(wallet_id));
    assert_eq!(vault.2, "SAVINGS");
    assert_eq!(vault.4, "Revolut");

    // Even with remember=false, the marker row exists so status shows connected + lastSyncedAt.
    let st = client.get("/api/revolut/status").send().await.json();
    assert_eq!(st["connected"], true);
    assert_eq!(st["remembered"], false);
    assert!(st["lastSyncedAt"].is_string());
}

#[tokio::test]
async fn remember_stores_credentials_blank_sync_reuses_them_and_remember_false_clears_them() {
    let sidecar = MockServer::start().await;
    mount_sync(
        &sidecar,
        json!([
            { "externalId": "w-1", "name": "Revolut EUR", "type": "CHECKING", "balance": n("10"), "currency": "EUR", "transactions": [] }
        ]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    // Blank credentials with none stored fails cleanly in progress.
    let failed = discover(&app, &client, json!({ "phoneNumber": "", "passcode": "" })).await;
    assert_eq!(failed["running"], false);
    assert!(
        failed["error"]
            .as_str()
            .unwrap()
            .contains("No saved Revolut credentials")
    );

    // Explicit login + confirm with remember=true stores encrypted credentials.
    let done = discover(
        &app,
        &client,
        json!({ "phoneNumber": PHONE, "passcode": PASSCODE }),
    )
    .await;
    assert_eq!(done["done"], true);
    assert_eq!(
        client
            .post("/api/revolut/sync/confirm")
            .json(&json!({ "selectedExternalIds": ["w-1"], "remember": true, "voluntary": false }))
            .send()
            .await
            .status,
        204
    );
    let st = client.get("/api/revolut/status").send().await.json();
    assert_eq!(st["connected"], true);
    assert_eq!(st["remembered"], true);

    // Blank sync now succeeds by decrypting stored credentials.
    let blank_done = discover(&app, &client, json!({})).await;
    assert_eq!(blank_done["done"], true);
    assert_eq!(blank_done["discovered"][0]["alreadyImported"], true);
    // Confirming with remember=false clears stored credentials while keeping the marker row.
    assert_eq!(
        client
            .post("/api/revolut/sync/confirm")
            .json(&json!({ "selectedExternalIds": ["w-1"], "remember": false, "voluntary": false }))
            .send()
            .await
            .status,
        204
    );
    let cleared = client.get("/api/revolut/status").send().await.json();
    assert_eq!(cleared["connected"], true);
    assert_eq!(cleared["remembered"], false);
    assert!(cleared["lastSyncedAt"].is_string());
}

#[tokio::test]
async fn voluntary_confirm_lifts_tombstone_while_non_voluntary_respects_soft_delete() {
    let sidecar = MockServer::start().await;
    mount_sync(
        &sidecar,
        json!([
            { "externalId": "w-del", "name": "Revolut EUR", "type": "CHECKING", "balance": n("50"), "currency": "EUR", "transactions": [] }
        ]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    discover(
        &app,
        &client,
        json!({ "phoneNumber": PHONE, "passcode": PASSCODE }),
    )
    .await;
    client
        .post("/api/revolut/sync/confirm")
        .json(&json!({ "selectedExternalIds": ["w-del"], "remember": false, "voluntary": true }))
        .send()
        .await;
    picsou::db::query("UPDATE account SET deleted_at = now() WHERE member_id = $1")
        .bind(member_id)
        .execute(&app.state.db)
        .await
        .unwrap();

    // Non-voluntary confirm leaves the tombstone alone.
    discover(
        &app,
        &client,
        json!({ "phoneNumber": PHONE, "passcode": PASSCODE }),
    )
    .await;
    assert_eq!(
        client
            .post("/api/revolut/sync/confirm")
            .json(
                &json!({ "selectedExternalIds": ["w-del"], "remember": false, "voluntary": false })
            )
            .send()
            .await
            .status,
        204
    );
    assert_eq!(
        count(
            &app,
            "SELECT count(*) FROM account WHERE deleted_at IS NULL"
        )
        .await,
        0
    );

    // Voluntary confirm lifts the tombstone and updates the restored account.
    discover(
        &app,
        &client,
        json!({ "phoneNumber": PHONE, "passcode": PASSCODE }),
    )
    .await;
    assert_eq!(
        client
            .post("/api/revolut/sync/confirm")
            .json(
                &json!({ "selectedExternalIds": ["w-del"], "remember": false, "voluntary": true })
            )
            .send()
            .await
            .status,
        204
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

#[tokio::test]
async fn iban_match_adopts_existing_account_keeping_provenance_and_filters_fx_and_transfers() {
    let sidecar = MockServer::start().await;
    mount_sync(
        &sidecar,
        json!([
            {
                "externalId": "rev-a", "name": "Revolut EUR", "type": "CHECKING", "iban": IBAN,
                "balance": n("1500.00"), "currency": "EUR",
                "transactions": [
                    { "externalId": "tx-mirror", "date": "2026-09-20", "description": "To Pocket", "amount": n("-25.00"), "kind": "TRANSFER" },
                    { "externalId": "tx-unpaired", "date": "2026-09-20", "description": "External Transfer", "amount": n("-4.00"), "kind": "TRANSFER" },
                    { "externalId": "tx-fx-btc", "date": "2026-09-20", "description": "Exchanged to EUR", "amount": n("-0.05"), "currency": "BTC" },
                    { "externalId": "tx-fx-implicit", "date": "2026-09-20", "description": "Exchanged to EUR", "amount": n("-50000") },
                    { "externalId": "tx-fx-eur", "date": "2026-09-20", "description": "Exchanged from BTC", "amount": n("3000"), "currency": "EUR" },
                    { "externalId": "tx-coffee", "date": "2026-09-20", "description": "Coffee", "amount": n("-3.50") }
                ]
            },
            {
                "externalId": "rev-b", "name": "Pocket", "type": "CHECKING",
                "balance": n("25.00"), "currency": "EUR",
                "transactions": [
                    { "externalId": "tx-mirror", "date": "2026-09-20", "description": "From Main", "amount": n("25.00"), "kind": "TRANSFER" }
                ]
            }
        ]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    // Seed an existing Enable Banking account with the same IBAN.
    let existing_id: i64 = picsou::db::query_scalar(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, iban, external_account_id, is_manual, color, created_at) \
         VALUES ($1, 'Compte Courant', 'CHECKING', 'Enable Banking', 'EUR', 1000, $2, 'eb-uid-1', false, '#6366f1', now()) RETURNING id",
    )
    .bind(member_id)
    .bind(IBAN)
    .fetch_one(&app.state.db)
    .await
    .unwrap();

    discover(
        &app,
        &client,
        json!({ "phoneNumber": PHONE, "passcode": PASSCODE }),
    )
    .await;
    assert_eq!(
        client
            .post("/api/revolut/sync/confirm")
            .json(&json!({ "selectedExternalIds": ["rev-a", "rev-b"], "remember": false, "voluntary": false }))
            .send()
            .await
            .status,
        204
    );

    let adopted: (i64, String, String, Decimal) = picsou::db::query_as(
        "SELECT id, external_account_id, provider, current_balance FROM account WHERE id = $1",
    )
    .bind(existing_id)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(adopted.1, "rev-a");
    assert_eq!(adopted.2, "Enable Banking");
    assert_eq!(adopted.3, d("1500.00"));

    // Foreign-currency BTC leg and implicit currency-less foreign leg are both dropped;
    // mirrored transfer pair gets TRANSFER category while unpaired transfer does not.
    let txns: Vec<(String, Option<String>)> = picsou::db::query_as(
        "SELECT t.external_id, c.kind::text FROM \"transaction\" t \
         LEFT JOIN category c ON c.id = t.category_id \
         ORDER BY t.external_id, t.amount",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        txns,
        vec![
            ("tx-coffee".into(), None),
            ("tx-fx-eur".into(), None),
            ("tx-mirror".into(), Some("TRANSFER".into())),
            ("tx-mirror".into(), Some("TRANSFER".into())),
            ("tx-unpaired".into(), None),
        ]
    );
}

#[tokio::test]
async fn sidecar_error_statuses_map_to_their_codes_and_friendly_messages() {
    let cases = [
        (
            403,
            Some("HUMAN_VERIFICATION_REQUIRED"),
            "human verification",
        ),
        (
            424,
            Some("UPSTREAM_FORMAT_CHANGED"),
            "Check your phone number",
        ),
        (401, None, "session has expired"),
        (408, None, "mobile approval"),
        (409, None, "already running"),
        (503, None, "browser service"),
    ];
    for (status, expected_code, expected_text) in cases {
        let sidecar = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/sync"))
            .respond_with(ResponseTemplate::new(status).set_body_json(json!({ "error": "x" })))
            .mount(&sidecar)
            .await;
        let app = app_with_sidecar(URL, &sidecar).await;
        let (client, _) = admin(&app).await;

        let progress = discover(
            &app,
            &client,
            json!({ "phoneNumber": PHONE, "passcode": PASSCODE }),
        )
        .await;
        assert_eq!(progress["done"], true);
        assert_eq!(progress["running"], false);
        assert_eq!(
            progress.get("errorCode").and_then(Value::as_str),
            expected_code,
            "status {status}: {progress}"
        );
        assert!(
            progress["error"].as_str().unwrap().contains(expected_text),
            "status {status}: {progress}"
        );
    }
}

#[tokio::test]
async fn resync_reporting_sends_allow_login_false_and_skips_without_remembered_creds() {
    let sidecar = MockServer::start().await;
    let app = picsou::testing::TestApp::builder()
        .with_admin()
        .env(URL, sidecar.uri())
        .env("APP_SIDECAR_API_KEY", "test-key")
        .spawn()
        .await;
    let (client, member_id) = admin(&app).await;

    let skipped = revolut::resync_reporting(&app.state, member_id).await;
    assert_eq!(skipped.status, SyncStatus::SkippedNotConnected);

    // Connect with remember=true first.
    mount_sync(
        &sidecar,
        json!([
            { "externalId": "w-sched", "name": "Revolut EUR", "type": "CHECKING", "balance": n("10"), "currency": "EUR", "transactions": [] }
        ]),
    )
    .await;
    discover(
        &app,
        &client,
        json!({ "phoneNumber": PHONE, "passcode": PASSCODE }),
    )
    .await;
    client
        .post("/api/revolut/sync/confirm")
        .json(&json!({ "selectedExternalIds": ["w-sched"], "remember": true, "voluntary": true }))
        .send()
        .await;

    // Soft-delete the account; scheduled resync must pass allowLogin=false, X-API-Key, and NOT resurrect it.
    picsou::db::query("UPDATE account SET deleted_at = now() WHERE member_id = $1")
        .bind(member_id)
        .execute(&app.state.db)
        .await
        .unwrap();
    sidecar.reset().await;
    Mock::given(method("POST"))
        .and(path("/sync"))
        .and(header("X-Picsou-Sidecar-Key", "test-key"))
        .and(body_partial_json(json!({ "allowLogin": false })))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "accounts": [
                { "externalId": "w-sched", "name": "Revolut EUR", "type": "CHECKING", "balance": n("20"), "currency": "EUR", "transactions": [] }
            ]
        })))
        .expect(1)
        .mount(&sidecar)
        .await;

    let synced = revolut::resync_reporting(&app.state, member_id).await;
    assert_eq!(synced.status, SyncStatus::Synced);
    assert_eq!(
        count(
            &app,
            "SELECT count(*) FROM account WHERE deleted_at IS NULL"
        )
        .await,
        0
    );

    // Challenged 401 maps to SIDECAR_AUTH_FAILED (not SESSION_EXPIRED).
    sidecar.reset().await;
    Mock::given(method("POST"))
        .and(path("/sync"))
        .respond_with(
            ResponseTemplate::new(401)
                .insert_header("WWW-Authenticate", "Picsou-Sidecar-Key")
                .set_body_json(json!({ "detail": "UNAUTHORIZED" })),
        )
        .mount(&sidecar)
        .await;
    let auth_err = revolut::resync_reporting(&app.state, member_id).await;
    assert_eq!(auth_err.status, SyncStatus::Failed);
}

#[tokio::test]
async fn rate_limit_stops_the_sixth_sync_and_members_are_isolated() {
    let sidecar = MockServer::start().await;
    mount_sync(&sidecar, json!([])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    let (other, _) = member(&app, "dominique").await;

    // 5 attempts allowed per IP; sixth is 429.
    for _ in 0..5 {
        let res = client
            .post("/api/revolut/sync")
            .json(&json!({ "phoneNumber": PHONE, "passcode": PASSCODE }))
            .send()
            .await;
        assert_eq!(res.status, 202);
        settle(&app).await;
    }
    let limited = client
        .post("/api/revolut/sync")
        .json(&json!({ "phoneNumber": PHONE, "passcode": PASSCODE }))
        .send()
        .await;
    assert_eq!(limited.status, 429);

    // Other member's progress and status remain untouched.
    assert_eq!(
        other.get("/api/revolut/sync/progress").send().await.json()["done"],
        false
    );
    assert_eq!(
        other.get("/api/revolut/status").send().await.json()["connected"],
        false
    );
}

#[tokio::test]
async fn webview_endpoints_are_desktop_only_and_drive_full_sync_on_desktop() {
    // Web/Docker mode (PICSOU_DESKTOP unset): status omits webviewLoginSupported and routes 404.
    let sidecar = MockServer::start().await;
    let web_app = app_with_sidecar(URL, &sidecar).await;
    let (web_client, _) = admin(&web_app).await;
    let web_st = web_client.get("/api/revolut/status").send().await.json();
    assert!(web_st.get("webviewLoginSupported").is_none());
    assert_eq!(
        web_client
            .post("/api/revolut/webview/start")
            .send()
            .await
            .status,
        404
    );

    // Desktop mode (PICSOU_DESKTOP=true): status advertises webviewLoginSupported = true.
    let desktop_app = picsou::testing::TestApp::builder()
        .with_admin()
        .env(URL, sidecar.uri())
        .env("PICSOU_DESKTOP", "true")
        .spawn()
        .await;
    let (client, _) = admin(&desktop_app).await;
    let (other, _) = member(&desktop_app, "other-revolut").await;
    assert_eq!(
        client.get("/api/revolut/status").send().await.json()["webviewLoginSupported"],
        true
    );

    answer(
        &sidecar,
        "/webview/start",
        json!({ "processId": "wv-revo-1", "status": "WAITING_LOGIN" }),
    )
    .await;
    let started = client.post("/api/revolut/webview/start").send().await;
    assert_eq!(started.status, 200, "{}", started.text());
    assert_eq!(started.header("cache-control"), Some("no-store"));
    assert_eq!(started.json()["processId"], "wv-revo-1");

    // Another member cannot poll or cancel the attempt.
    assert_eq!(
        other
            .get("/api/revolut/webview/status/wv-revo-1")
            .send()
            .await
            .json()["code"],
        "AUTH_ATTEMPT_EXPIRED"
    );
    assert_eq!(
        other
            .post("/api/revolut/webview/cancel/wv-revo-1")
            .send()
            .await
            .json()["cancelled"],
        false
    );

    Mock::given(method("GET"))
        .and(path("/webview/status/wv-revo-1"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "status": "WAITING_LOGIN" })),
        )
        .up_to_n_times(1)
        .mount(&sidecar)
        .await;
    assert_eq!(
        client
            .get("/api/revolut/webview/status/wv-revo-1")
            .send()
            .await
            .json()["status"],
        "WAITING_LOGIN"
    );

    Mock::given(method("GET"))
        .and(path("/webview/status/wv-revo-1"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "status": "DONE" })))
        .mount(&sidecar)
        .await;
    answer(
        &sidecar,
        "/sync",
        json!({
            "accounts": [{
                "externalId": "wallet-wv",
                "name": "Revolut",
                "type": "CHECKING",
                "iban": IBAN,
                "balance": n("150.00"),
                "currency": "EUR",
                "transactions": []
            }]
        }),
    )
    .await;

    let done = client
        .get("/api/revolut/webview/status/wv-revo-1")
        .send()
        .await;
    assert_eq!(done.status, 200, "{}", done.text());
    let done_json = done.json();
    assert_eq!(done_json["status"], "SYNCING");
    assert_eq!(done_json["session"]["connected"], true);
    assert_eq!(done_json["session"]["webviewLoginSupported"], true);

    settle(&desktop_app).await;
    let progress = client.get("/api/revolut/sync/progress").send().await.json();
    assert_eq!(progress["done"], true);
    assert_eq!(progress["discovered"].as_array().unwrap().len(), 1);
    assert_eq!(progress["discovered"][0]["externalId"], "wallet-wv");

    // Confirming persists the discovered account.
    let confirm = client
        .post("/api/revolut/sync/confirm")
        .json(&json!({
            "selectedExternalIds": ["wallet-wv"],
            "remember": false,
            "voluntary": false
        }))
        .send()
        .await;
    assert_eq!(confirm.status, 204, "{}", confirm.text());
    assert_eq!(count(&desktop_app, "SELECT count(*) FROM account").await, 1);

    // Cancel flow removes pending login and calls bridge cancel.
    sidecar.reset().await;
    answer(
        &sidecar,
        "/webview/start",
        json!({ "processId": "wv-revo-2", "status": "WAITING_LOGIN" }),
    )
    .await;
    answer(
        &sidecar,
        "/webview/cancel/wv-revo-2",
        json!({ "cancelled": true }),
    )
    .await;
    assert_eq!(
        client
            .post("/api/revolut/webview/start")
            .send()
            .await
            .status,
        200
    );
    let cancelled = client
        .post("/api/revolut/webview/cancel/wv-revo-2")
        .send()
        .await;
    assert_eq!(cancelled.status, 200);
    assert_eq!(cancelled.json()["cancelled"], true);
    assert_eq!(
        client
            .get("/api/revolut/webview/status/wv-revo-2")
            .send()
            .await
            .json()["code"],
        "AUTH_ATTEMPT_EXPIRED"
    );

    // Disconnect in Desktop mode clears the Desktop WebView bridge's in-memory session.
    sidecar.reset().await;
    let member_id: i64 =
        picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
            .fetch_one(&desktop_app.state.db)
            .await
            .unwrap();
    Mock::given(method("DELETE"))
        .and(path(format!("/session/{member_id}")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "cleared": true })))
        .expect(1)
        .mount(&sidecar)
        .await;
    let disconnected = client.delete("/api/revolut/session").send().await;
    assert_eq!(disconnected.status, 204, "{}", disconnected.text());
    sidecar.verify().await;
}
