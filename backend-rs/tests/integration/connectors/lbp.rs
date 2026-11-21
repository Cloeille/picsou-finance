//! LbpController and LbpSyncService over HTTP, the sidecar stubbed: strict keypad login,
//! remembered identifier, all-or-nothing sync, regulated savings auto-config, closed accounts,
//! unsupported product tracking, and recovery.

use std::time::Duration;

use picsou::connectors::lbp;
use picsou::ledger::sidecar::SyncStatus;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{admin, answer, app_with_sidecar, count, d, fail, member, n, settle};

const URL: &str = "LBP_AUTH_URL";
const KEYS: [&str; 10] = ["5", "3", "9", "8", "6", "1", "0", "7", "2", "4"];

fn keypad_body(process_id: &str, keys: &[&str], columns: i32, expires: i32) -> Value {
    json!({
        "processId": process_id,
        "keypad": { "keys": keys, "columns": columns },
        "expiresInSeconds": expires,
    })
}

fn acct(id: &str, kind: &str, name: Option<&str>, balance: &str) -> Value {
    json!({
        "externalId": id, "kind": kind, "name": name, "balance": balance,
        "currency": "EUR", "transactions": [], "snapshotComplete": true
    })
}

fn tx(id: &str, date: &str, amount: &str, label: &str) -> Value {
    json!({ "externalId": id, "date": date, "amount": amount, "currency": "EUR", "label": label })
}

async fn store_lbp_session(app: &picsou::testing::TestApp, member_id: i64, plain: &str) -> i64 {
    let enc = app.state.crypto.encrypt(plain).unwrap();
    sqlx::query_scalar(
        "INSERT INTO lbp_session (member_id, session_state, last_validated_at, is_active, sync_status) \
         VALUES ($1, $2, now(), true, 'IDLE') RETURNING id",
    )
    .bind(member_id)
    .bind(enc)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

async fn status(client: &picsou::testing::Client) -> Value {
    client.get("/api/lbp/status").send().await.json()
}

async fn sync(app: &picsou::testing::TestApp, client: &picsou::testing::Client) -> Value {
    let queued = client.post("/api/lbp/sync").send().await;
    assert_eq!(queued.status, 202, "{}", queued.text());
    settle(app).await;
    status(client).await
}

#[tokio::test]
async fn status_and_clear_without_a_session_and_strict_body_validation() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let st = status(&client).await;
    assert_eq!(st["isActive"], false);
    assert_eq!(st["syncStatus"], "IDLE");
    assert_eq!(st["unsupportedCount"], 0);

    // Unexpected field (password), invalid customerId, non-boolean rememberIdentifier -> 400.
    for body in [
        json!({ "customerId": "1234567890", "password": "secret" }),
        json!({ "customerId": "1234567890", "rememberIdentifier": "yes" }),
        json!({ "customerId": "abc" }),
        json!({ "customerId": "12345" }),
        json!({ "customerId": "1234567890123" }),
        json!({}),
    ] {
        let res = client
            .post("/api/lbp/auth/initiate")
            .json(&body)
            .send()
            .await;
        assert_eq!(res.status, 400, "initiate {body}: {}", res.text());
    }
    for body in [
        json!({ "processId": "p", "positions": [0, 1, 2] }),
        json!({ "processId": "p", "positions": [0, 1, 2, 3, 4, 10] }),
        json!({ "processId": "p", "positions": ["0", 1, 2, 3, 4, 5] }),
        json!({ "processId": "p", "positions": [0, 1, 2, 3, 4, 5], "password": "x" }),
    ] {
        let res = client.post("/api/lbp/auth/keypad").json(&body).send().await;
        assert_eq!(res.status, 400, "keypad {body}: {}", res.text());
    }
    for body in [json!({ "processId": "p", "code": "123456" }), json!({})] {
        let res = client
            .post("/api/lbp/auth/complete")
            .json(&body)
            .send()
            .await;
        assert_eq!(res.status, 400, "complete {body}: {}", res.text());
    }

    let del = client.delete("/api/lbp/session").send().await;
    assert_eq!(del.status, 200);
    assert_eq!(del.json()["removed"], false);
    assert_eq!(del.json()["bankSessionRevoked"], false);
}

#[tokio::test]
async fn full_keypad_login_flow_remembers_identifier_and_enforces_owner_and_single_use() {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/initiate", keypad_body("proc-1", &KEYS, 5, 300)).await;
    answer(&sidecar, "/keypad", json!({ "status": "APPROVAL_PENDING" })).await;
    answer(
        &sidecar,
        "/complete",
        json!({ "sessionState": "cookie-jar-1" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let (other, _) = member(&app, "dominique").await;

    let init = client
        .post("/api/lbp/auth/initiate")
        .json(&json!({ "customerId": "1234567890", "rememberIdentifier": true }))
        .send()
        .await;
    assert_eq!(init.status, 200, "{}", init.text());
    assert_eq!(init.header("cache-control"), Some("no-store"));
    assert_eq!(init.json()["processId"], "proc-1");
    assert_eq!(init.json()["keypad"]["columns"], 5);

    // Another member cannot drive or burn the owner's attempt.
    let foreign = other
        .post("/api/lbp/auth/complete")
        .json(&json!({ "processId": "proc-1" }))
        .send()
        .await;
    assert_eq!(foreign.status, 422);
    assert_eq!(foreign.json()["code"], "AUTH_ATTEMPT_EXPIRED");

    // Keypad works once, then a duplicate keypad call fails without losing the approval entry.
    let kp = client
        .post("/api/lbp/auth/keypad")
        .json(&json!({ "processId": "proc-1", "positions": [7, 3, 0, 9, 4, 1] }))
        .send()
        .await;
    assert_eq!(kp.status, 200, "{}", kp.text());
    assert_eq!(kp.header("cache-control"), Some("no-store"));
    assert_eq!(kp.json()["status"], "APPROVAL_PENDING");
    let kp_dup = client
        .post("/api/lbp/auth/keypad")
        .json(&json!({ "processId": "proc-1", "positions": [7, 3, 0, 9, 4, 1] }))
        .send()
        .await;
    assert_eq!(kp_dup.status, 422);
    assert_eq!(kp_dup.json()["code"], "AUTH_ATTEMPT_EXPIRED");

    // Complete stores session + encrypted identifier (masked/returned per status contract).
    let completed = client
        .post("/api/lbp/auth/complete")
        .json(&json!({ "processId": "proc-1" }))
        .send()
        .await;
    assert_eq!(completed.status, 200, "{}", completed.text());
    assert_eq!(completed.header("cache-control"), Some("no-store"));
    assert_eq!(completed.json()["isActive"], true);
    assert_eq!(completed.json()["savedIdentifier"], "1234567890");

    // Replay of complete is refused as expired.
    let replay = client
        .post("/api/lbp/auth/complete")
        .json(&json!({ "processId": "proc-1" }))
        .send()
        .await;
    assert_eq!(replay.status, 422);
    assert_eq!(replay.json()["code"], "AUTH_ATTEMPT_EXPIRED");

    // Delete removes only the caller's session.
    let other_del = other.delete("/api/lbp/session").send().await;
    assert_eq!(other_del.json()["removed"], false);
    assert_eq!(status(&client).await["isActive"], true);
    let own_del = client.delete("/api/lbp/session").send().await;
    assert_eq!(own_del.json()["removed"], true);
    assert_eq!(
        count(
            &app,
            &format!("SELECT count(*) FROM lbp_session WHERE member_id = {member_id}")
        )
        .await,
        0
    );
}

#[tokio::test]
async fn broken_keypad_and_expired_windows_are_refused() {
    let sidecar = MockServer::start().await;
    let bad_cases = [
        keypad_body(
            "p1",
            &["5", "5", "9", "8", "6", "1", "0", "7", "2", "4"],
            5,
            300,
        ),
        keypad_body(
            "p1",
            &["5", "3", "9", "8", "6", "1", "0", "7", "2", "x"],
            5,
            300,
        ),
        keypad_body("p1", &["5", "3", "9", "8", "6", "1", "0", "7", "2"], 5, 300),
        keypad_body("p1", &KEYS, 4, 300),
    ];
    for body in bad_cases {
        sidecar.reset().await;
        answer(&sidecar, "/initiate", body).await;
        let app = app_with_sidecar(URL, &sidecar).await;
        let (client, _) = admin(&app).await;
        let res = client
            .post("/api/lbp/auth/initiate")
            .json(&json!({ "customerId": "1234567890" }))
            .send()
            .await;
        assert_eq!(res.status, 422);
        assert_eq!(res.json()["code"], "KEYPAD_CHANGED");
    }

    // Approval window expires after 180 seconds; eviction sweeps stale attempts.
    sidecar.reset().await;
    answer(&sidecar, "/initiate", keypad_body("p-exp", &KEYS, 5, 300)).await;
    answer(&sidecar, "/keypad", json!({ "status": "APPROVAL_PENDING" })).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    assert_eq!(
        client
            .post("/api/lbp/auth/initiate")
            .json(&json!({ "customerId": "1234567890" }))
            .send()
            .await
            .status,
        200
    );
    assert_eq!(
        client
            .post("/api/lbp/auth/keypad")
            .json(&json!({ "processId": "p-exp", "positions": [0, 1, 2, 3, 4, 5] }))
            .send()
            .await
            .status,
        200
    );
    app.clock.advance(Duration::from_secs(181));
    lbp::evict_expired_pending_logins(&app.state);
    let expired = client
        .post("/api/lbp/auth/complete")
        .json(&json!({ "processId": "p-exp" }))
        .send()
        .await;
    assert_eq!(expired.status, 422);
    assert_eq!(expired.json()["code"], "AUTH_ATTEMPT_EXPIRED");
}

#[tokio::test]
async fn rate_limits_stop_fourth_initiate_with_retry_after() {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/initiate", keypad_body("p", &KEYS, 5, 300)).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    for _ in 0..3 {
        let res = client
            .post("/api/lbp/auth/initiate")
            .json(&json!({ "customerId": "1234567890" }))
            .send()
            .await;
        assert_eq!(res.status, 200);
    }
    let fourth = client
        .post("/api/lbp/auth/initiate")
        .json(&json!({ "customerId": "1234567890" }))
        .send()
        .await;
    assert_eq!(fourth.status, 429);
    assert!(fourth.header("retry-after").is_some());
}

#[tokio::test]
async fn sync_maps_all_kinds_configures_livrets_updates_txns_and_closes_removed_accounts() {
    let sidecar = MockServer::start().await;
    let mut current = acct("1001", "CURRENT_ACCOUNT", Some("CCP"), "1234.56");
    current["transactions"] = json!([
        tx("t1", "2026-11-01", "-12.30", "SUPERMARCHE"),
        tx("t1", "2026-11-01", "-15.00", "SUPERMARCHE CORRIGE"),
        tx("t2", "2026-11-02", "1500.00", "VIREMENT RECU"),
    ]);
    let card = json!({
        "externalId": "2001", "kind": "CARD", "name": "Carte Visa", "balance": "-87.10",
        "currency": "EUR", "cardNature": "DEFERRED_DEBIT", "nextDueDate": "2026-11-30",
        "transactions": [], "snapshotComplete": true
    });
    let loan = acct("3001", "LOAN", Some("Pret"), "9000.00");
    answer(
        &sidecar,
        "/accounts",
        json!({
            "accounts": [
                current,
                acct("1002", "LIVRET_A", Some("Livret A"), "5000.00"),
                acct("1003", "LDDS", Some("LDDS"), "2000.00"),
                acct("1004", "LEP", None, "1000.00"),
                card,
                loan,
            ],
            "unsupported": [
                { "externalId": "9001", "familyCode": "ASSURANCE_VIE" }
            ]
        }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_lbp_session(&app, member_id, "plain-state").await;

    let st = sync(&app, &client).await;
    assert_eq!(st["syncStatus"], "SUCCESS");
    assert_eq!(st["unsupportedCount"], 1);
    assert_eq!(st["unsupported"][0]["familyCode"], "ASSURANCE_VIE");

    // Check types, balances, card fields, fallback LEP label, regulated savings auto-config.
    type Row = (String, String, String, Decimal, Option<Decimal>);
    let accounts: Vec<Row> = sqlx::query_as(
        "SELECT external_account_id, type::text, name, current_balance, payment_due_amount \
         FROM account WHERE member_id = $1 ORDER BY external_account_id",
    )
    .bind(member_id)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(accounts.len(), 6);
    assert_eq!(
        accounts[0],
        (
            "lbp_1001".into(),
            "CHECKING".into(),
            "CCP".into(),
            d("1234.56"),
            None
        )
    );
    assert_eq!(
        accounts[3],
        (
            "lbp_1004".into(),
            "LEP".into(),
            "LEP".into(),
            d("1000.00"),
            None
        )
    );
    assert_eq!(
        accounts[4],
        (
            "lbp_2001".into(),
            "CREDIT_CARD".into(),
            "Carte Visa".into(),
            d("-87.10"),
            Some(d("87.10"))
        )
    );
    assert_eq!(
        accounts[5],
        (
            "lbp_3001".into(),
            "LOAN".into(),
            "Pret".into(),
            d("9000.00"),
            None
        )
    );

    // Regulated livrets got default savings_interest_config rows.
    assert_eq!(
        count(&app, "SELECT count(*) FROM savings_interest_config").await,
        3
    );

    // Duplicate t1 kept the last occurrence.
    let txns: Vec<(String, String, Decimal)> = sqlx::query_as(
        "SELECT external_id, description, amount FROM \"transaction\" ORDER BY external_id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        txns,
        vec![
            ("lbp_t1".into(), "SUPERMARCHE CORRIGE".into(), d("-15.00")),
            ("lbp_t2".into(), "VIREMENT RECU".into(), d("1500.00")),
        ]
    );

    // Next sync drops the card and Livret A -> closed at 0, card due cleared.
    sidecar.reset().await;
    answer(
        &sidecar,
        "/accounts",
        json!({ "accounts": [acct("1001", "CURRENT_ACCOUNT", Some("CCP"), "1100.00")], "unsupported": [] }),
    )
    .await;
    let st2 = sync(&app, &client).await;
    assert_eq!(st2["syncStatus"], "SUCCESS");
    let closed_card: (Decimal, Option<Decimal>, Option<chrono::NaiveDate>) = sqlx::query_as(
        "SELECT current_balance, payment_due_amount, payment_due_date FROM account WHERE external_account_id = 'lbp_2001'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(closed_card, (d("0"), Some(d("0")), None));
}

#[tokio::test]
async fn invalid_snapshots_fail_whole_and_recovery_marks_in_flight_failed() {
    let bad_snapshots = [
        json!({ "accounts": [], "unsupported": [] }),
        json!({ "accounts": [acct("1", "UNKNOWN_KIND", Some("X"), "10")], "unsupported": [] }),
        json!({ "accounts": [acct("1", "CURRENT_ACCOUNT", Some("A"), "10"), acct("1", "LIVRET_A", Some("B"), "10")], "unsupported": [] }),
        json!({ "accounts": [{ "externalId": "1", "kind": "CURRENT_ACCOUNT", "name": "A", "balance": "10", "currency": "USD", "transactions": [], "snapshotComplete": true }], "unsupported": [] }),
        json!({ "accounts": [{ "externalId": "1", "kind": "CARD", "name": "C", "balance": "12.00", "currency": "EUR", "cardNature": "DEFERRED_DEBIT", "transactions": [], "snapshotComplete": true }], "unsupported": [] }),
        json!({ "accounts": [{ "externalId": "1", "kind": "CURRENT_ACCOUNT", "name": "A", "balance": "10", "currency": "EUR", "transactions": [], "snapshotComplete": false }], "unsupported": [] }),
    ];
    for snap in bad_snapshots {
        let sidecar = MockServer::start().await;
        answer(&sidecar, "/accounts", snap).await;
        let app = app_with_sidecar(URL, &sidecar).await;
        let (client, member_id) = admin(&app).await;
        store_lbp_session(&app, member_id, "plain-state").await;

        let st = sync(&app, &client).await;
        assert_eq!(st["syncStatus"], "FAILED");
        assert_eq!(st["lastSyncError"], "UPSTREAM_FORMAT_CHANGED");
        assert_eq!(count(&app, "SELECT count(*) FROM account").await, 0);
    }

    // Recovery runner marks QUEUED/RUNNING sessions FAILED with INTERNAL_ERROR.
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let id = store_lbp_session(&app, member_id, "plain-state").await;
    sqlx::query("UPDATE lbp_session SET sync_status = 'RUNNING' WHERE id = $1")
        .bind(id)
        .execute(&app.state.db)
        .await
        .unwrap();
    let recovered = lbp::recover_interrupted_syncs(&app.state).await.unwrap();
    assert_eq!(recovered, 1);
    let st = status(&client).await;
    assert_eq!(st["syncStatus"], "FAILED");
    assert_eq!(st["lastSyncError"], "INTERNAL_ERROR");

    // On-demand resync_reporting returns SkippedNotConnected when inactive/missing, or NeedsReauth on expired session.
    fail(
        &sidecar,
        "/accounts",
        401,
        json!({ "detail": "SESSION_EXPIRED" }),
    )
    .await;
    let after_expired = sync(&app, &client).await;
    assert_eq!(after_expired["isActive"], false);
    assert_eq!(after_expired["lastSyncError"], "SESSION_EXPIRED");
    let sched = lbp::resync_reporting(&app.state, member_id).await;
    assert_eq!(sched.status, SyncStatus::NeedsReauth);
    let _ = Mock::given(method("GET"))
        .and(path("/unused"))
        .respond_with(ResponseTemplate::new(200));
    let _ = n("0");
}
