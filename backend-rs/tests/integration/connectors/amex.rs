//! AmexController and AmexSyncService over HTTP, the sidecar stubbed.

use picsou::ledger::sidecar::{SidecarBank, SyncStatus};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{body_json, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{
    admin, answer, app_with_sidecar, count, d, fail, member, n, session_of, settle, store_session,
};

const URL: &str = "AMEX_AUTH_URL";
const BANK: SidecarBank = SidecarBank::Amex;

fn tx(date: &str, description: &str, amount: &str, status: &str) -> Value {
    json!({ "date": date, "description": description, "amount": n(amount), "status": status })
}

fn card(transactions: Vec<Value>, pending_complete: bool) -> Value {
    json!({
        "externalId": "card-1", "name": "Platinum", "type": "CREDIT_CARD", "balanceEur": n("-1234.50"),
        "amountDue": n("400.10"), "dueDate": "2026-02-01", "rewardPoints": 12000,
        "transactions": transactions, "pendingComplete": pending_complete, "snapshotComplete": true
    })
}

async fn mount_accounts(sidecar: &MockServer, accounts: Value) {
    sidecar.reset().await;
    answer(sidecar, "/accounts", accounts).await;
}

async fn status(client: &picsou::testing::Client) -> Value {
    client.get("/api/amex/status").send().await.json()
}

async fn sync(app: &picsou::testing::TestApp, client: &picsou::testing::Client) -> Value {
    let queued = client.post("/api/amex/sync").send().await;
    assert_eq!(queued.status, 202, "{}", queued.text());
    settle(app).await;
    status(client).await
}

/// The card's rows, oldest first, as (description, amount, external id).
async fn rows(app: &picsou::testing::TestApp, member_id: i64) -> Vec<(String, Decimal, String)> {
    picsou::db::query_as(
        "SELECT t.description, t.amount, COALESCE(t.external_id, '') FROM \"transaction\" t JOIN account a ON a.id = t.account_id \
         WHERE a.member_id = $1 AND a.external_account_id = 'amex_card-1' ORDER BY t.date, t.id",
    )
    .bind(member_id)
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

async fn some_category(app: &picsou::testing::TestApp, member_id: i64) -> i64 {
    picsou::ledger::categorize::ensure_seeded(&app.state.db, member_id, app.state.clock.now())
        .await
        .unwrap();
    picsou::db::query_scalar("SELECT id FROM category WHERE member_id = $1 ORDER BY id LIMIT 1")
        .bind(member_id)
        .fetch_one(&app.state.db)
        .await
        .unwrap()
}

async fn categorise(app: &picsou::testing::TestApp, description: &str, category: i64) {
    picsou::db::query("UPDATE \"transaction\" SET category_id = $1 WHERE description = $2")
        .bind(category)
        .bind(description)
        .execute(&app.state.db)
        .await
        .unwrap();
}

async fn category_of(app: &picsou::testing::TestApp, description: &str) -> Option<i64> {
    picsou::db::query_scalar("SELECT category_id FROM \"transaction\" WHERE description = $1")
        .bind(description)
        .fetch_one(&app.state.db)
        .await
        .unwrap()
}

#[tokio::test]
async fn status_without_a_session_is_inactive_and_idle() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    assert_eq!(
        status(&client).await,
        json!({ "isActive": false, "syncStatus": "IDLE" })
    );
}

#[tokio::test]
async fn initiate_sends_the_chosen_code_channel_and_relays_the_challenge() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_json(
            json!({ "userId": "jdoe", "password": "secret", "method": "email" }),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "processId": "p-1", "mfaRequired": true, "mfaType": "OTP", "sessionState": null
        })))
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let response = client
        .post("/api/amex/auth/initiate")
        .json(&json!({ "login": "jdoe", "password": "secret", "method": "email" }))
        .send()
        .await;

    assert_eq!(response.status, 200);
    assert_eq!(
        response.json(),
        json!({ "processId": "p-1", "mfaRequired": true, "mfaType": "OTP" })
    );
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

#[tokio::test]
async fn initiate_and_complete_validate_before_calling_the_sidecar() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let push = client
        .post("/api/amex/auth/initiate")
        .json(&json!({ "login": "jdoe", "password": "secret", "method": "push" }))
        .send()
        .await;
    let otp = client
        .post("/api/amex/auth/complete")
        .json(&json!({ "processId": "p-1", "otp": "12ab" }))
        .send()
        .await;
    let blank = client
        .post("/api/amex/auth/initiate")
        .json(&json!({ "login": " ", "password": "x".repeat(101) }))
        .send()
        .await;

    assert_eq!(push.status, 422);
    assert_eq!(push.json()["errors"]["method"], "must match \"sms|email\"");
    assert_eq!(otp.status, 422);
    assert_eq!(otp.json()["errors"]["otp"], "must match \"\\d{4,10}\"");
    assert_eq!(blank.status, 422);
    let errors = &blank.json()["errors"];
    assert_eq!(errors["login"], "must not be blank");
    assert_eq!(errors["password"], "size must be between 0 and 100");
    assert_eq!(errors["method"], "must not be blank");
    assert!(sidecar.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn complete_maps_a_rejected_code_to_invalid_otp() {
    let sidecar = MockServer::start().await;
    fail(
        &sidecar,
        "/complete",
        401,
        json!({ "detail": "unauthorized" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let response = client
        .post("/api/amex/auth/complete")
        .json(&json!({ "processId": "p-1", "otp": "000000" }))
        .send()
        .await;

    assert_eq!(response.status, 422);
    assert_eq!(response.json()["code"], "INVALID_OTP");
    assert_eq!(
        response.json()["detail"],
        "Amex rejected the verification code"
    );
}

#[tokio::test]
async fn sixth_authentication_attempt_from_the_same_ip_is_rate_limited() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "p", "mfaRequired": true, "mfaType": "OTP" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    let body = json!({ "login": "jdoe", "password": "secret", "method": "sms" });

    for _ in 0..5 {
        let ok = client
            .post("/api/amex/auth/initiate")
            .json(&body)
            .send()
            .await;
        assert_eq!(ok.status, 200);
    }
    let limited = client
        .post("/api/amex/auth/initiate")
        .json(&body)
        .send()
        .await;

    assert_eq!(limited.status, 429);
    assert_eq!(
        limited.json()["detail"],
        "Too many American Express authentication attempts. Please wait before retrying."
    );
    assert_eq!(sidecar.received_requests().await.unwrap().len(), 5);
}

#[tokio::test]
async fn completion_imports_the_full_history_of_the_card() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/complete"))
        .and(body_json(json!({ "processId": "p-1", "otp": "123456" })))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "sessionState": "plain-state" })),
        )
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/accounts"))
        .and(body_json(
            json!({ "sessionState": "plain-state", "history": true }),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([card(
            vec![
                tx("2025-06-01", "OLD PURCHASE", "-10.00", "posted"),
                tx("2026-01-10", "COFFEE", "-3.50", "posted"),
            ],
            true
        )])))
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let response = client
        .post("/api/amex/auth/complete")
        .json(&json!({ "processId": "p-1", "otp": "123456" }))
        .send()
        .await;
    settle(&app).await;

    assert_eq!(response.status, 200);
    assert_eq!(response.json()["isActive"], true);
    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
    let (name, kind, provider, color, balance, due, due_date, points): (
        String,
        String,
        String,
        String,
        Decimal,
        Decimal,
        chrono::NaiveDate,
        i64,
    ) = picsou::db::query_as(
        "SELECT name, type::text, provider, color, current_balance, payment_due_amount, payment_due_date, \
         reward_points FROM account WHERE external_account_id = 'amex_card-1' AND member_id = $1",
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
            color.as_str()
        ),
        ("Platinum", "CREDIT_CARD", "American Express", "#2563eb")
    );
    assert_eq!((balance, due, points), (d("-1234.50"), d("400.10"), 12000));
    assert_eq!(due_date.to_string(), "2026-02-01");
    let stored = rows(&app, member_id).await;
    assert_eq!(stored.len(), 2, "the history beyond 90 days is imported");
    assert!(
        stored
            .iter()
            .all(|(_, _, id)| id.starts_with("amex_tx_") && id.len() == 40)
    );
    let snapshots: Vec<(String, Decimal, Decimal)> = picsou::db::query_as(
        "SELECT date::text, balance, invested_amount FROM balance_snapshot ORDER BY date",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    let today = app.state.clock.today().to_string();
    assert_eq!(
        snapshots,
        vec![
            ("2025-06-01".to_string(), d("-1231"), d("-1231")),
            ("2026-01-10".to_string(), d("-1234.5"), d("-1234.5")),
            (today, d("-1234.5"), d("-1234.5")),
        ],
        "the imported ledger is back-propagated into the history, anchored on today's balance"
    );
}

#[tokio::test]
async fn routine_sync_updates_rows_in_place_and_keeps_what_the_page_no_longer_returns() {
    let sidecar = MockServer::start().await;
    mount_accounts(
        &sidecar,
        json!([card(
            vec![
                tx("2025-12-20", "BOOKSHOP", "-20.00", "posted"),
                tx("2026-01-10", "COFFEE", "-3.50", "posted"),
                tx("2026-01-10", "COFFEE", "-3.50", "posted"),
            ],
            true
        )]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;

    assert_eq!(sync(&app, &client).await["syncStatus"], "SUCCESS");
    let first = rows(&app, member_id).await;
    mount_accounts(
        &sidecar,
        json!([card(
            vec![
                tx("2026-01-10", "COFFEE", "-3.5", "posted"),
                tx("2026-01-10", "COFFEE", "-3.50", "posted"),
            ],
            true
        )]),
    )
    .await;
    assert_eq!(sync(&app, &client).await["syncStatus"], "SUCCESS");

    let second = rows(&app, member_id).await;
    assert_eq!(first.len(), 3, "two identical purchases stay two rows");
    assert_eq!(second, first, "nothing duplicated, nothing deleted");
    let bodies = sidecar.received_requests().await.unwrap();
    let body: Value = serde_json::from_slice(&bodies[0].body).unwrap();
    assert_eq!(body, json!({ "sessionState": "plain-state" }));
}

#[tokio::test]
async fn a_pending_charge_is_dropped_once_an_answered_feed_stops_reporting_it() {
    let sidecar = MockServer::start().await;
    mount_accounts(
        &sidecar,
        json!([card(
            vec![
                tx("2026-01-12", "HOTEL HOLD", "-90.00", "pending"),
                tx("2026-01-13", "TAXI", "-15.00", "pending"),
            ],
            true
        )]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    sync(&app, &client).await;
    let stored = rows(&app, member_id).await;
    assert!(stored.iter().all(|(_, _, id)| id.starts_with("amex_txp_")));

    mount_accounts(
        &sidecar,
        json!([card(
            vec![tx("2026-01-05", "OTHER", "-1.00", "posted")],
            false
        )]),
    )
    .await;
    sync(&app, &client).await;
    assert_eq!(
        rows(&app, member_id).await.len(),
        3,
        "a failed pending feed proves nothing"
    );

    mount_accounts(
        &sidecar,
        json!([card(
            vec![tx("2026-01-13", "TAXI", "-15.00", "pending")],
            true
        )]),
    )
    .await;
    sync(&app, &client).await;
    let left: Vec<String> = rows(&app, member_id)
        .await
        .into_iter()
        .map(|(description, _, _)| description)
        .collect();
    assert_eq!(left, ["OTHER", "TAXI"]);
}

#[tokio::test]
async fn a_settled_pending_charge_hands_its_category_to_the_posted_row() {
    let sidecar = MockServer::start().await;
    mount_accounts(
        &sidecar,
        json!([card(
            vec![
                tx("2026-01-12", "RESTAURANT", "-42.00", "pending"),
                tx("2026-01-11", "GROCER", "-30.00", "pending"),
            ],
            true
        )]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    sync(&app, &client).await;
    let category = some_category(&app, member_id).await;
    categorise(&app, "RESTAURANT", category).await;
    categorise(&app, "GROCER", category).await;

    mount_accounts(
        &sidecar,
        json!([card(
            vec![
                tx("2026-01-12", "RESTAURANT", "-42.00", "posted"),
                tx("2026-01-13", "GROCER STORE 12", "-30.00", "posted"),
            ],
            true
        )]),
    )
    .await;
    sync(&app, &client).await;

    let stored = rows(&app, member_id).await;
    assert_eq!(stored.len(), 2, "both pendings were purged");
    assert!(stored.iter().all(|(_, _, id)| id.starts_with("amex_tx_")));
    assert_eq!(category_of(&app, "RESTAURANT").await, Some(category));
    assert_eq!(
        category_of(&app, "GROCER STORE 12").await,
        Some(category),
        "a relabelled settlement two days later is paired on its amount"
    );
}

#[tokio::test]
async fn a_relabelled_charge_takes_over_the_stored_row_and_keeps_its_category() {
    let sidecar = MockServer::start().await;
    let page = |label: &str| {
        json!([card(
            vec![
                tx("2025-12-20", "BOOKSHOP", "-20.00", "posted"),
                tx("2026-01-10", label, "-21.60", "posted"),
            ],
            true
        )])
    };
    mount_accounts(&sidecar, page("ANTHROPIC PBC")).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    sync(&app, &client).await;
    let category = some_category(&app, member_id).await;
    categorise(&app, "ANTHROPIC PBC", category).await;

    mount_accounts(&sidecar, page("ANTHROPIC* CLAUDE SUB SAN FRANCISCO")).await;
    sync(&app, &client).await;

    let stored = rows(&app, member_id).await;
    assert_eq!(
        stored.len(),
        2,
        "the relabelled charge is not inserted again"
    );
    assert_eq!(
        category_of(&app, "ANTHROPIC* CLAUDE SUB SAN FRANCISCO").await,
        Some(category)
    );
}

#[tokio::test]
async fn history_recovery_merges_without_touching_existing_rows() {
    let sidecar = MockServer::start().await;
    mount_accounts(
        &sidecar,
        json!([card(
            vec![tx("2026-01-10", "COFFEE", "-3.50", "posted")],
            true
        )]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    sync(&app, &client).await;
    picsou::db::query("INSERT INTO \"transaction\" (account_id, date, description, amount, native_currency, created_at, is_manual) \
                 SELECT id, DATE '2026-01-09', 'CASH NOTE', -1, 'EUR', now(), true FROM account WHERE external_account_id = 'amex_card-1'")
        .execute(&app.state.db)
        .await
        .unwrap();

    sidecar.reset().await;
    Mock::given(method("POST"))
        .and(path("/accounts"))
        .and(body_json(
            json!({ "sessionState": "plain-state", "history": true }),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([card(
            vec![
                tx("2024-03-01", "ANCIENT", "-8.00", "posted"),
                tx("2026-01-10", "COFFEE", "-3.50", "posted"),
            ],
            true
        )])))
        .expect(1)
        .mount(&sidecar)
        .await;
    let queued = client.post("/api/amex/history-recovery").send().await;
    settle(&app).await;

    assert_eq!(queued.status, 202);
    assert_eq!(queued.json()["syncStatus"], "QUEUED");
    let descriptions: Vec<String> = rows(&app, member_id)
        .await
        .into_iter()
        .map(|(description, _, _)| description)
        .collect();
    assert_eq!(descriptions, ["ANCIENT", "CASH NOTE", "COFFEE"]);
}

#[tokio::test]
async fn the_history_back_propagates_a_manual_row_entered_before_the_sync() {
    let sidecar = MockServer::start().await;
    mount_accounts(&sidecar, json!([card(vec![], true)])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    sync(&app, &client).await;

    // A manual row entered before the next sync moved the balance the card feed does not report.
    picsou::db::query("INSERT INTO \"transaction\" (account_id, date, description, amount, native_currency, created_at, is_manual) \
             SELECT id, DATE '2026-01-09', 'CASH NOTE', -1, 'EUR', now(), true FROM account WHERE external_account_id = 'amex_card-1'")
        .execute(&app.state.db)
        .await
        .unwrap();

    mount_accounts(
        &sidecar,
        json!([card(
            vec![tx("2026-01-10", "COFFEE", "-3.50", "posted")],
            true
        )]),
    )
    .await;
    sync(&app, &client).await;

    let snapshots: Vec<(String, Decimal, Decimal)> = picsou::db::query_as(
        "SELECT date::text, balance, invested_amount FROM balance_snapshot ORDER BY date",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    let today = app.state.clock.today().to_string();
    assert_eq!(
        snapshots,
        vec![
            ("2026-01-09".to_string(), d("-1231"), d("-1231")),
            ("2026-01-10".to_string(), d("-1234.5"), d("-1234.5")),
            (today, d("-1234.5"), d("-1234.5")),
        ],
        "the pre-sync manual row is subtracted while walking back from today's balance"
    );
}

#[tokio::test]
async fn an_earlier_id_format_is_re_keyed_once() {
    let sidecar = MockServer::start().await;
    mount_accounts(
        &sidecar,
        json!([card(
            vec![tx("2025-12-01", "BOOKSHOP", "-20.00", "posted")],
            true
        )]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    sync(&app, &client).await;
    picsou::db::query("UPDATE \"transaction\" SET external_id = 'amex_tx_1x2y3z'")
        .execute(&app.state.db)
        .await
        .unwrap();
    let category = some_category(&app, member_id).await;
    categorise(&app, "BOOKSHOP", category).await;

    sync(&app, &client).await;
    sync(&app, &client).await;

    let stored = rows(&app, member_id).await;
    assert_eq!(stored.len(), 1);
    assert!(stored[0].2.starts_with("amex_tx_") && stored[0].2.len() == 40);
    assert_eq!(category_of(&app, "BOOKSHOP").await, Some(category));
}

#[tokio::test]
async fn an_incomplete_snapshot_fails_the_sync_and_writes_nothing() {
    let sidecar = MockServer::start().await;
    let mut incomplete = card(vec![], true);
    incomplete["snapshotComplete"] = json!(false);
    mount_accounts(&sidecar, json!([incomplete])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;

    let after = sync(&app, &client).await;

    assert_eq!(after["syncStatus"], "FAILED");
    assert_eq!(after["lastSyncError"], "INVALID_DATA");
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 0);
}

#[tokio::test]
async fn a_malformed_transaction_date_fails_the_sync_but_a_bad_due_date_is_dropped() {
    let sidecar = MockServer::start().await;
    let mut bad_due = card(vec![], true);
    bad_due["dueDate"] = json!("soon");
    mount_accounts(&sidecar, json!([bad_due])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;

    assert_eq!(sync(&app, &client).await["syncStatus"], "SUCCESS");
    let due: Option<chrono::NaiveDate> =
        picsou::db::query_scalar("SELECT payment_due_date FROM account WHERE member_id = $1")
            .bind(member_id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(due, None);

    mount_accounts(
        &sidecar,
        json!([card(
            vec![tx("10/01/2026", "COFFEE", "-3.50", "posted")],
            true
        )]),
    )
    .await;
    let after = sync(&app, &client).await;
    assert_eq!(after["syncStatus"], "FAILED");
    assert_eq!(after["lastSyncError"], "INVALID_DATA");
}

#[tokio::test]
async fn a_soft_deleted_card_is_not_resurrected() {
    let sidecar = MockServer::start().await;
    mount_accounts(&sidecar, json!([card(vec![], true)])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    sync(&app, &client).await;
    picsou::db::query("UPDATE account SET deleted_at = now()")
        .execute(&app.state.db)
        .await
        .unwrap();

    assert_eq!(sync(&app, &client).await["syncStatus"], "SUCCESS");
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
async fn sync_and_history_recovery_share_the_sync_budget() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    for path in ["/api/amex/sync", "/api/amex/history-recovery"]
        .iter()
        .cycle()
        .take(10)
    {
        let response = client.post(path).send().await;
        assert_eq!(response.status, 422, "no session yet");
    }
    let limited = client.post("/api/amex/history-recovery").send().await;

    assert_eq!(limited.status, 429);
    assert_eq!(
        limited.json()["detail"],
        "Too many American Express history recovery requests. Please wait before retrying."
    );
}

#[tokio::test]
async fn sessions_are_scoped_to_their_member() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (_, admin_id) = admin(&app).await;
    let (other, _) = member(&app, "dominique").await;
    store_session(&app, BANK, admin_id, "plain-state").await;

    assert_eq!(status(&other).await["isActive"], false);
    let sync = other.post("/api/amex/sync").send().await;
    assert_eq!(sync.status, 422);
    assert_eq!(sync.json()["code"], "SESSION_EXPIRED");
    assert_eq!(other.delete("/api/amex/session").send().await.status, 204);
    assert!(session_of(&app, BANK, admin_id).await.is_some());
}

#[tokio::test]
async fn the_scheduler_entry_reports_queued_or_not_connected() {
    let sidecar = MockServer::start().await;
    mount_accounts(&sidecar, json!([card(vec![], true)])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (_, member_id) = admin(&app).await;

    let disconnected = picsou::connectors::amex::resync_reporting(&app.state, member_id).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    let queued = picsou::connectors::amex::resync_reporting(&app.state, member_id).await;
    settle(&app).await;

    assert_eq!(disconnected.status, SyncStatus::SkippedNotConnected);
    assert_eq!(disconnected.message, "No active session");
    assert_eq!(queued.status, SyncStatus::Queued);
    assert_eq!(queued.source, "amex");
}

#[tokio::test]
async fn webview_endpoints_are_desktop_only_and_drive_full_sync_on_desktop() {
    // Web/Docker mode (PICSOU_DESKTOP unset): status omits webviewLoginSupported and routes 404.
    let sidecar = MockServer::start().await;
    let web_app = app_with_sidecar(URL, &sidecar).await;
    let (web_client, _) = admin(&web_app).await;
    let web_st = status(&web_client).await;
    assert!(web_st.get("webviewLoginSupported").is_none());
    assert_eq!(
        web_client
            .post("/api/amex/webview/start")
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
    let (client, member_id) = admin(&desktop_app).await;
    let (other, _) = member(&desktop_app, "other-amex").await;
    assert_eq!(status(&client).await["webviewLoginSupported"], true);

    answer(
        &sidecar,
        "/webview/start",
        json!({ "processId": "wv-amex-1", "status": "WAITING_LOGIN" }),
    )
    .await;
    let started = client.post("/api/amex/webview/start").send().await;
    assert_eq!(started.status, 200, "{}", started.text());
    assert_eq!(started.header("cache-control"), Some("no-store"));
    assert_eq!(started.json()["processId"], "wv-amex-1");

    // Another member cannot poll or cancel the attempt.
    assert_eq!(
        other
            .get("/api/amex/webview/status/wv-amex-1")
            .send()
            .await
            .json()["code"],
        "AUTH_ATTEMPT_EXPIRED"
    );
    assert_eq!(
        other
            .post("/api/amex/webview/cancel/wv-amex-1")
            .send()
            .await
            .json()["cancelled"],
        false
    );

    Mock::given(method("GET"))
        .and(path("/webview/status/wv-amex-1"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "status": "WAITING_LOGIN" })),
        )
        .up_to_n_times(1)
        .mount(&sidecar)
        .await;
    assert_eq!(
        client
            .get("/api/amex/webview/status/wv-amex-1")
            .send()
            .await
            .json()["status"],
        "WAITING_LOGIN"
    );

    Mock::given(method("GET"))
        .and(path("/webview/status/wv-amex-1"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "status": "DONE",
            "sessionState": "amex-desktop-jar-1"
        })))
        .mount(&sidecar)
        .await;
    answer(
        &sidecar,
        "/accounts",
        json!([card(
            vec![tx("2026-01-15", "Cafe", "-4.50", "posted")],
            true
        )]),
    )
    .await;

    let done = client
        .get("/api/amex/webview/status/wv-amex-1")
        .send()
        .await;
    assert_eq!(done.status, 200, "{}", done.text());
    let done_json = done.json();
    assert_eq!(done_json["status"], "SYNCING");
    assert!(done_json.get("sessionState").is_none());

    settle(&desktop_app).await;
    let final_st = status(&client).await;
    assert_eq!(final_st["isActive"], true);
    assert_eq!(final_st["syncStatus"], "SUCCESS");
    assert_eq!(final_st["webviewLoginSupported"], true);

    let row = session_of(&desktop_app, BANK, member_id).await.unwrap();
    assert_eq!(
        desktop_app
            .state
            .crypto
            .decrypt(&row.session_state)
            .unwrap(),
        "amex-desktop-jar-1"
    );

    // Cancel flow removes pending login and calls bridge cancel.
    sidecar.reset().await;
    answer(
        &sidecar,
        "/webview/start",
        json!({ "processId": "wv-amex-2", "status": "WAITING_LOGIN" }),
    )
    .await;
    answer(
        &sidecar,
        "/webview/cancel/wv-amex-2",
        json!({ "cancelled": true }),
    )
    .await;
    assert_eq!(
        client.post("/api/amex/webview/start").send().await.status,
        200
    );
    let cancelled = client
        .post("/api/amex/webview/cancel/wv-amex-2")
        .send()
        .await;
    assert_eq!(cancelled.status, 200);
    assert_eq!(cancelled.json()["cancelled"], true);
    assert_eq!(
        client
            .get("/api/amex/webview/status/wv-amex-2")
            .send()
            .await
            .json()["code"],
        "AUTH_ATTEMPT_EXPIRED"
    );
}

#[tokio::test]
async fn webview_status_poll_racing_a_disconnect_returns_expired_not_syncing() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/webview/start",
        json!({ "processId": "wv-amex-disconnect", "status": "WAITING_LOGIN" }),
    )
    .await;
    let in_flight = std::sync::Arc::new(tokio::sync::Notify::new());
    Mock::given(method("GET"))
        .and(path("/webview/status/wv-amex-disconnect"))
        .respond_with(super::support::DelayedNotify {
            in_flight: in_flight.clone(),
            delay: std::time::Duration::from_millis(300),
            body: json!({ "status": "DONE", "sessionState": "amex-jar-race" }),
        })
        .mount(&sidecar)
        .await;
    let app = picsou::testing::TestApp::builder()
        .with_admin()
        .env(URL, sidecar.uri())
        .env("PICSOU_DESKTOP", "true")
        .spawn()
        .await;
    let (client, member_id) = admin(&app).await;

    assert_eq!(
        client.post("/api/amex/webview/start").send().await.status,
        200
    );

    let (polled, disconnected) = tokio::join!(
        client
            .get("/api/amex/webview/status/wv-amex-disconnect")
            .send(),
        async {
            tokio::time::timeout(std::time::Duration::from_secs(5), in_flight.notified())
                .await
                .expect("status poll reached bridge");
            client.delete("/api/amex/session").send().await
        }
    );

    assert_eq!(disconnected.status, 204, "{}", disconnected.text());
    assert_eq!(polled.status, 422, "{}", polled.text());
    assert_eq!(polled.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    assert!(session_of(&app, BANK, member_id).await.is_none());
}

#[tokio::test]
async fn start_webview_racing_a_disconnect_cancels_the_opened_bank_window() {
    let sidecar = MockServer::start().await;
    let in_flight = std::sync::Arc::new(tokio::sync::Notify::new());
    Mock::given(method("POST"))
        .and(path("/webview/start"))
        .respond_with(super::support::DelayedNotify {
            in_flight: in_flight.clone(),
            delay: std::time::Duration::from_millis(300),
            body: json!({ "processId": "wv-amex-race-cancel", "status": "WAITING_LOGIN" }),
        })
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/webview/cancel/wv-amex-race-cancel"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "cancelled": true })))
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = picsou::testing::TestApp::builder()
        .with_admin()
        .env(URL, sidecar.uri())
        .env("PICSOU_DESKTOP", "true")
        .spawn()
        .await;
    let (client, _) = admin(&app).await;

    let (started, disconnected) =
        tokio::join!(client.post("/api/amex/webview/start").send(), async {
            tokio::time::timeout(std::time::Duration::from_secs(5), in_flight.notified())
                .await
                .expect("start_webview reached bridge");
            client.delete("/api/amex/session").send().await
        });

    assert_eq!(disconnected.status, 204, "{}", disconnected.text());
    assert_eq!(started.status, 422, "{}", started.text());
    assert_eq!(started.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    sidecar.verify().await;

    // Disconnecting while a WebView login is already pending also cancels it on the bridge.
    sidecar.reset().await;
    answer(
        &sidecar,
        "/webview/start",
        json!({ "processId": "wv-amex-open-cancel", "status": "WAITING_LOGIN" }),
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/webview/cancel/wv-amex-open-cancel"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "cancelled": true })))
        .expect(1)
        .mount(&sidecar)
        .await;
    assert_eq!(
        client.post("/api/amex/webview/start").send().await.status,
        200
    );
    assert_eq!(client.delete("/api/amex/session").send().await.status, 204);
    sidecar.verify().await;
}
