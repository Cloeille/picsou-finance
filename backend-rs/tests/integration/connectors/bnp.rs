//! BnpController and BnpSyncService over HTTP, the sidecar stubbed: strict keypad login for
//! BNP Paribas and Hello bank!, SMS code vs app approval, separate rate-limit buckets,
//! securities accounts, IBAN adoption, closing unlisted accounts, and session rotation.

use std::time::Duration;

use picsou::connectors::bnp;
use picsou::ledger::sidecar::{SidecarBank, SyncStatus};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::MockServer;

use super::support::{
    admin, answer, app_with_sidecar, count, d, fail, member, n, session_of, settle, store_session,
};

const URL: &str = "BNP_AUTH_URL";
const BANK: SidecarBank = SidecarBank::Bnp;
const KEYS: [&str; 10] = ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9"];
const IBAN: &str = "FR7630006000011234567890189";

fn keypad_body(process_id: &str, keys: &[&str], columns: i32, expires: i32) -> Value {
    json!({
        "processId": process_id,
        "keypad": { "keys": keys, "columns": columns },
        "expiresInSeconds": expires,
    })
}

fn acct(
    id: &str,
    kind: &str,
    name: &str,
    balance: &str,
    cash: Option<&str>,
    iban: Option<&str>,
    positions: Vec<Value>,
) -> Value {
    json!({
        "externalId": id, "kind": kind, "name": name, "balance": n(balance),
        "cashBalance": cash.map(n), "currency": "EUR", "iban": iban,
        "transactions": [], "positions": positions, "snapshotComplete": true
    })
}

fn pos(isin: &str, label: &str, qty: &str, value: &str, pnl: Option<&str>) -> Value {
    json!({
        "isin": isin, "label": label, "quantity": n(qty), "value": n(value),
        "pnl": pnl.map(n)
    })
}

async fn status(client: &picsou::testing::Client) -> Value {
    client.get("/api/bnp/status").send().await.json()
}

async fn sync(app: &picsou::testing::TestApp, client: &picsou::testing::Client) -> Value {
    let queued = client.post("/api/bnp/sync").send().await;
    assert_eq!(queued.status, 202, "{}", queued.text());
    settle(app).await;
    status(client).await
}

#[tokio::test]
async fn strict_validation_refuses_malformed_bodies_before_calling_sidecar() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    for body in [
        json!({ "customerId": "12ab" }),
        json!({ "customerId": "123456" }),
        json!({ "customerId": "12345678901" }),
        json!({ "customerId": "1234567890", "brand": "other" }),
        json!({ "customerId": "1234567890", "password": "secret" }),
        json!({ "customerId": 1234567890 }),
        json!([]),
    ] {
        assert_eq!(
            client
                .post("/api/bnp/auth/initiate")
                .json(&body)
                .send()
                .await
                .status,
            400,
            "initiate {body}"
        );
    }
    for body in [
        json!({ "processId": "p", "positions": [0, 1, 2, 3, 4] }),
        json!({ "processId": "p", "positions": [0, 1, 2, 3, 4, 10] }),
        json!({ "processId": "p", "positions": [0, 1, 2, 3, 4, -1] }),
        json!({ "processId": "p", "positions": [0, 1, 2, 3, 4, "5"] }),
        json!({ "processId": "p", "positions": [0, 1, 2, 3, 4, 5], "code": "123456" }),
        json!({ "positions": [0, 1, 2, 3, 4, 5] }),
    ] {
        assert_eq!(
            client
                .post("/api/bnp/auth/keypad")
                .json(&body)
                .send()
                .await
                .status,
            400,
            "keypad {body}"
        );
    }
    assert_eq!(
        client
            .post("/api/bnp/auth/complete")
            .json(&json!({ "processId": "p", "code": "12ab" }))
            .send()
            .await
            .status,
        400
    );
    assert!(sidecar.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn sms_login_allows_retrying_a_mistyped_code_and_app_approval_takes_no_code() {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/initiate", keypad_body("sms-1", &KEYS, 5, 300)).await;
    answer(&sidecar, "/keypad", json!({ "status": "CODE_REQUIRED" })).await;
    fail(
        &sidecar,
        "/complete",
        401,
        json!({ "detail": "INVALID_OTP" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    let (other, _) = member(&app, "dominique").await;

    let init = client
        .post("/api/bnp/auth/initiate")
        .json(&json!({ "customerId": "1234567890", "brand": "hellobank" }))
        .send()
        .await;
    assert_eq!(init.status, 200, "{}", init.text());
    assert_eq!(init.header("cache-control"), Some("no-store"));

    // Another member cannot use the attempt.
    assert_eq!(
        other
            .post("/api/bnp/auth/keypad")
            .json(&json!({ "processId": "sms-1", "positions": [0, 1, 2, 3, 4, 5] }))
            .send()
            .await
            .json()["code"],
        "AUTH_ATTEMPT_EXPIRED"
    );

    let kp = client
        .post("/api/bnp/auth/keypad")
        .json(&json!({ "processId": "sms-1", "positions": [0, 1, 2, 3, 4, 5] }))
        .send()
        .await;
    assert_eq!(kp.status, 200);
    assert_eq!(kp.json()["status"], "CODE_REQUIRED");

    // Omitting the SMS code fails with INVALID_OTP without calling sidecar.
    let no_code = client
        .post("/api/bnp/auth/complete")
        .json(&json!({ "processId": "sms-1" }))
        .send()
        .await;
    assert_eq!(no_code.status, 422);
    assert_eq!(no_code.json()["code"], "INVALID_OTP");

    // A wrong SMS code returns INVALID_OTP and keeps the attempt alive for another try.
    let wrong = client
        .post("/api/bnp/auth/complete")
        .json(&json!({ "processId": "sms-1", "code": "000000" }))
        .send()
        .await;
    assert_eq!(wrong.status, 422);
    assert_eq!(wrong.json()["code"], "INVALID_OTP");

    // Succeeding on the second try stores the session and queues the first sync.
    sidecar.reset().await;
    answer(&sidecar, "/complete", json!({ "sessionState": "jar-1" })).await;
    answer(
        &sidecar,
        "/accounts",
        json!({
            "brand": "hellobank",
            "accounts": [acct("c1", "CURRENT_ACCOUNT", "Compte", "10.00", None, None, vec![])],
            "unsupported": []
        }),
    )
    .await;
    let ok = client
        .post("/api/bnp/auth/complete")
        .json(&json!({ "processId": "sms-1", "code": "123456" }))
        .send()
        .await;
    assert_eq!(ok.status, 200, "{}", ok.text());
    settle(&app).await;
    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
    let provider: String = sqlx::query_scalar("SELECT provider FROM account LIMIT 1")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(provider, "Hello bank!");
}

#[tokio::test]
async fn separate_rate_limit_buckets_isolate_initiate_keypad_and_code() {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/initiate", keypad_body("p1", &KEYS, 5, 300)).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    // 3 initiates allowed, 4th is 429 with Retry-After.
    for _ in 0..3 {
        assert_eq!(
            client
                .post("/api/bnp/auth/initiate")
                .json(&json!({ "customerId": "1234567890" }))
                .send()
                .await
                .status,
            200
        );
    }
    let fourth = client
        .post("/api/bnp/auth/initiate")
        .json(&json!({ "customerId": "1234567890" }))
        .send()
        .await;
    assert_eq!(fourth.status, 429);
    assert!(fourth.header("retry-after").is_some());

    // Exhausted initiate bucket does not block keypad (5 allowed, 6th is 429).
    for _ in 0..5 {
        let res = client
            .post("/api/bnp/auth/keypad")
            .json(&json!({ "processId": "unknown", "positions": [0, 1, 2, 3, 4, 5] }))
            .send()
            .await;
        assert_eq!(res.status, 422);
    }
    assert_eq!(
        client
            .post("/api/bnp/auth/keypad")
            .json(&json!({ "processId": "unknown", "positions": [0, 1, 2, 3, 4, 5] }))
            .send()
            .await
            .status,
        429
    );

    // Exhausted keypad bucket does not block code attempts (10 allowed, 11th is 429).
    // App-approval completes (without code) do not spend the code bucket.
    for _ in 0..12 {
        assert_eq!(
            client
                .post("/api/bnp/auth/complete")
                .json(&json!({ "processId": "unknown" }))
                .send()
                .await
                .status,
            422
        );
    }
    for _ in 0..10 {
        assert_eq!(
            client
                .post("/api/bnp/auth/complete")
                .json(&json!({ "processId": "unknown", "code": "000000" }))
                .send()
                .await
                .status,
            422
        );
    }
    assert_eq!(
        client
            .post("/api/bnp/auth/complete")
            .json(&json!({ "processId": "unknown", "code": "000000" }))
            .send()
            .await
            .status,
        429
    );
}

#[tokio::test]
async fn sync_imports_products_securities_rotates_jar_adopts_verified_iban_and_closes_removed() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/accounts",
        json!({
            "brand": "bnp",
            "accounts": [
                acct("c1", "CURRENT_ACCOUNT", "Compte", "1200.50", None, Some(IBAN), vec![]),
                acct("l1", "LOAN", "Pret", "-10000.00", None, None, vec![]),
                acct("av1", "ASSURANCE_VIE", "Contrat AV", "9000.00", None, None, vec![]),
                acct("pea1", "PEA", "PEA", "1500.00", Some("500.00"), None, vec![
                    pos("FR0000000001", "Action A", "10", "800.00", Some("100.00")),
                    pos("FR0000000002", "Action B", "5", "200.00", None),
                ]),
            ],
            "unsupported": [{ "externalId": "t1", "familyCode": "3" }],
            "sessionState": "rotated-jar"
        }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    // Seed an existing Enable Banking Hello bank account with the same valid IBAN (adopted, not duplicated).
    let eb_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, iban, external_account_id, is_manual, color, created_at) \
         VALUES ($1, 'Hello bank', 'CHECKING', 'Hello bank', 'EUR', 100, $2, 'eb-id', false, '#6366f1', now()) RETURNING id",
    )
    .bind(member_id)
    .bind(IBAN)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    // Seed a previously imported BNP savings account that is no longer listed -> closed at 0.
    let gone_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, external_account_id, is_manual, color, created_at) \
         VALUES ($1, 'Old Livret', 'SAVINGS', 'BNP Paribas', 'EUR', 800, 'bnp_gone', false, '#6366f1', now()) RETURNING id",
    )
    .bind(member_id)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    // Seed an account that turned unsupported -> must NOT be closed.
    let unsup_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, external_account_id, is_manual, color, created_at) \
         VALUES ($1, 'Turned Unsup', 'SAVINGS', 'BNP Paribas', 'EUR', 700, 'bnp_t1', false, '#6366f1', now()) RETURNING id",
    )
    .bind(member_id)
    .fetch_one(&app.state.db)
    .await
    .unwrap();

    let st = sync(&app, &client).await;
    assert_eq!(st["syncStatus"], "SUCCESS");
    assert_eq!(st["unsupportedCount"], 1);
    assert_eq!(st["unsupported"][0]["externalId"], "t1");

    // Cookie jar rotated.
    let row = session_of(&app, BANK, member_id).await.unwrap();
    assert_eq!(
        app.state.crypto.decrypt(&row.session_state).unwrap(),
        "rotated-jar"
    );

    // Enable Banking account adopted in place.
    let adopted: (String, Decimal) =
        sqlx::query_as("SELECT external_account_id, current_balance FROM account WHERE id = $1")
            .bind(eb_id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(adopted, ("bnp_c1".into(), d("1200.50")));

    // Loan stored as positive liability.
    let loan_bal: Decimal = sqlx::query_scalar(
        "SELECT current_balance FROM account WHERE external_account_id = 'bnp_l1'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(loan_bal, d("10000.00"));

    // PEA has cashBalance + 2 holdings.
    let pea_cash: Option<Decimal> = sqlx::query_scalar(
        "SELECT cash_balance FROM account WHERE external_account_id = 'bnp_pea1'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(pea_cash, Some(d("500.00")));
    assert_eq!(count(&app, "SELECT count(*) FROM account_holding").await, 2);

    // Unlisted BNP account closed at 0; unsupported account kept intact.
    let gone_bal: Decimal = sqlx::query_scalar("SELECT current_balance FROM account WHERE id = $1")
        .bind(gone_id)
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(gone_bal, d("0"));
    let unsup_bal: Decimal =
        sqlx::query_scalar("SELECT current_balance FROM account WHERE id = $1")
            .bind(unsup_id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(unsup_bal, d("700"));
}

#[tokio::test]
async fn invalid_snapshots_refuse_whole_and_expired_session_reports_needs_reauth() {
    let cases = [
        (
            json!({ "brand": "bnp", "accounts": [acct("pea1", "PEA", "PEA", "1500.00", Some("500.00"), None, vec![pos("FR0000000001", "A", "10", "100.00", None)])], "unsupported": [] }),
            "PORTFOLIO_INCOMPLETE",
        ),
        (
            json!({ "brand": "bnp", "accounts": [acct("av1", "ASSURANCE_VIE", "AV", "9000.00", None, None, vec![pos("FR0000000001", "S", "1", "9000.00", None)])], "unsupported": [] }),
            "INVALID_DATA",
        ),
        (
            json!({ "brand": "bnp", "accounts": [acct("x1", "SOMETHING_NEW", "N", "10.00", None, None, vec![])], "unsupported": [] }),
            "UPSTREAM_FORMAT_CHANGED",
        ),
        (
            json!({ "brand": "bnp", "accounts": [acct("s1", "LIVRET_A", "L", "-5.00", None, None, vec![])], "unsupported": [] }),
            "INVALID_DATA",
        ),
    ];
    for (body, expected_code) in cases {
        let sidecar = MockServer::start().await;
        answer(&sidecar, "/accounts", body).await;
        let app = app_with_sidecar(URL, &sidecar).await;
        let (client, member_id) = admin(&app).await;
        store_session(&app, BANK, member_id, "plain-state").await;

        let st = sync(&app, &client).await;
        assert_eq!(st["syncStatus"], "FAILED");
        assert_eq!(st["lastSyncError"], expected_code);
        assert_eq!(count(&app, "SELECT count(*) FROM account").await, 0);
    }

    // Expired session -> inactive + NeedsReauth on scheduled resync.
    let sidecar = MockServer::start().await;
    fail(
        &sidecar,
        "/accounts",
        401,
        json!({ "detail": "SESSION_EXPIRED" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;

    let st = sync(&app, &client).await;
    assert_eq!(st["isActive"], false);
    assert_eq!(st["lastSyncError"], "SESSION_EXPIRED");
    let sched = bnp::resync_reporting(&app.state, member_id).await;
    assert_eq!(sched.status, SyncStatus::NeedsReauth);

    // Pending login eviction sweeps expired attempts.
    sidecar.reset().await;
    answer(&sidecar, "/initiate", keypad_body("p-stale", &KEYS, 5, 30)).await;
    assert_eq!(
        client
            .post("/api/bnp/auth/initiate")
            .json(&json!({ "customerId": "1234567890" }))
            .send()
            .await
            .status,
        200
    );
    app.clock.advance(Duration::from_secs(31));
    bnp::evict_expired_pending_logins(&app.state);
    assert_eq!(
        client
            .post("/api/bnp/auth/keypad")
            .json(&json!({ "processId": "p-stale", "positions": [0, 1, 2, 3, 4, 5] }))
            .send()
            .await
            .json()["code"],
        "AUTH_ATTEMPT_EXPIRED"
    );
}
