//! CmesController and CmesSyncService over HTTP, the sidecar stubbed.

use chrono::{DateTime, NaiveDate, Utc};
use picsou::ledger::sidecar::SidecarBank;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{body_partial_json, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{admin, answer, app_with_sidecar, count, d, member, n, session_of, settle};

const URL: &str = "CMES_AUTH_URL";
const BANK: SidecarBank = SidecarBank::Cmes;

fn plan(id: &str, legacy: &[&str], balance: &str) -> Value {
    named_plan(id, legacy, "PEE", balance)
}

fn named_plan(id: &str, legacy: &[&str], name: &str, balance: &str) -> Value {
    json!({
        "externalId": id, "legacyExternalIds": legacy, "name": name, "employer": "ACME",
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

async fn insert_account(
    app: &picsou::testing::TestApp,
    member_id: i64,
    id: &str,
    name: &str,
    deleted: bool,
) {
    picsou::db::query(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, external_account_id, \
         is_manual, color) VALUES ($1, $2, 'EMPLOYEE_SAVINGS', 'Crédit Mutuel Épargne Salariale', \
         'EUR', 50, $3, false, '#000')",
    )
    .bind(member_id)
    .bind(name)
    .bind(id)
    .execute(&app.state.db)
    .await
    .unwrap();
    if deleted {
        picsou::db::query(
            "UPDATE account SET deleted_at = CURRENT_TIMESTAMP WHERE external_account_id = $1",
        )
        .bind(id)
        .execute(&app.state.db)
        .await
        .unwrap();
    }
}

#[tokio::test]
async fn an_account_whose_id_carries_the_saver_number_moves_to_the_id_without_it() {
    // The site no longer shows the saver number earlier builds put in the id, and the
    // employer in the name now comes from another page: the id alone is enough.
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/positions",
        json!([plan("cmes_pee-0123456789abcdef", &["cmes_PEE"], "100")]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    insert_account(
        &app,
        member_id,
        "cmes_12345_pee-0123456789abcdef",
        "PEE — ACME SA",
        false,
    )
    .await;
    let original_id: i64 = picsou::db::query_scalar(
        "SELECT id FROM account WHERE external_account_id = 'cmes_12345_pee-0123456789abcdef'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();

    login(&sidecar, &app, &client).await;

    // The same row, so its history stays with it.
    let rows: Vec<(i64, String, Decimal)> = picsou::db::query_as(
        "SELECT id, external_account_id, current_balance FROM account ORDER BY id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![(original_id, "cmes_pee-0123456789abcdef".into(), d("100"))]
    );
}

#[tokio::test]
async fn a_plan_deleted_under_a_numbered_legacy_id_is_not_imported_again() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/positions",
        json!([plan("cmes_pee-0123456789abcdef", &["cmes_PEE"], "100")]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    insert_account(&app, member_id, "cmes_12345_PEE", "PEE — ACME", true).await;

    login(&sidecar, &app, &client).await;

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
async fn a_plan_deleted_under_its_numbered_id_stays_deleted_whatever_its_name() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/positions",
        json!([plan("cmes_pee-0123456789abcdef", &["cmes_PEE"], "100")]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    insert_account(
        &app,
        member_id,
        "cmes_12345_pee-0123456789abcdef",
        "PEE — ACME SA",
        true,
    )
    .await;

    login(&sidecar, &app, &client).await;

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
async fn a_legacy_id_under_another_label_is_not_inherited() {
    // `cmes_<n>_PEE` may have been another plan of the family: it needs the label.
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/positions",
        json!([plan("cmes_pee-0123456789abcdef", &["cmes_PEE"], "100")]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    insert_account(&app, member_id, "cmes_12345_PEE", "PEI — ACME", false).await;

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
            ("cmes_12345_PEE".into(), d("0")),
            ("cmes_pee-0123456789abcdef".into(), d("100"))
        ]
    );
}

async fn balances(app: &picsou::testing::TestApp) -> Vec<(String, Decimal)> {
    picsou::db::query_as(
        "SELECT external_account_id, current_balance FROM account WHERE deleted_at IS NULL \
         ORDER BY id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

async fn sync_one_plan(legacy: &[&str], accounts: &[(&str, &str, bool)]) -> Vec<(String, Decimal)> {
    sync_plans(
        json!([plan("cmes_pee-0123456789abcdef", legacy, "100")]),
        accounts,
    )
    .await
}

async fn sync_plans(plans: Value, accounts: &[(&str, &str, bool)]) -> Vec<(String, Decimal)> {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/positions", plans).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    for (id, name, deleted) in accounts {
        insert_account(&app, member_id, id, name, *deleted).await;
    }
    login(&sidecar, &app, &client).await;
    balances(&app).await
}

#[tokio::test]
async fn a_legacy_id_under_another_employer_wording_is_inherited() {
    // The employer now comes from the dashboard title, worded otherwise than the old header.
    let rows = sync_one_plan(&["cmes_PEE"], &[("cmes_12345_PEE", "PEE — ACME SA", false)]).await;
    assert_eq!(rows, vec![("cmes_pee-0123456789abcdef".into(), d("100"))]);
}

#[tokio::test]
async fn a_legacy_id_cut_at_100_characters_is_inherited() {
    // Both sides cut at 100 characters, the stored id with the number in it.
    let legacy: String = format!("cmes_PEE-{}", "a".repeat(95))
        .chars()
        .take(100)
        .collect();
    let stored: String = format!("cmes_12345_PEE-{}", "a".repeat(95))
        .chars()
        .take(100)
        .collect();
    let rows = sync_one_plan(&[&legacy], &[(&stored, "PEE — ACME", false)]).await;
    assert_eq!(rows, vec![("cmes_pee-0123456789abcdef".into(), d("100"))]);
}

#[tokio::test]
async fn a_deletion_under_another_saver_number_does_not_freeze_a_live_plan() {
    let rows = sync_one_plan(
        &["cmes_PEE"],
        &[
            ("cmes_111_pee-0123456789abcdef", "PEE — ACME", true),
            ("cmes_222_pee-0123456789abcdef", "PEE — ACME", false),
        ],
    )
    .await;
    assert_eq!(rows, vec![("cmes_pee-0123456789abcdef".into(), d("100"))]);
}

#[tokio::test]
async fn a_deletion_under_the_numbered_id_wins_over_a_live_legacy_account() {
    let rows = sync_one_plan(
        &["cmes_PEE"],
        &[
            ("cmes_12345_pee-0123456789abcdef", "PEE — ACME", true),
            ("cmes_12345_PEE", "PEE — ACME", false),
        ],
    )
    .await;
    // Nothing is imported again, and the legacy account closes at zero.
    assert_eq!(rows, vec![("cmes_12345_PEE".into(), d("0"))]);
}

#[tokio::test]
async fn a_saver_number_holding_an_underscore_is_taken_out_whole() {
    let rows = sync_one_plan(
        &["cmes_PEE"],
        &[("cmes_12345_01_pee-0123456789abcdef", "PEE — ACME", false)],
    )
    .await;
    assert_eq!(rows, vec![("cmes_pee-0123456789abcdef".into(), d("100"))]);
}

async fn insert_account_with_state(
    app: &picsou::testing::TestApp,
    member_id: i64,
    id: &str,
    name: &str,
    balance: &str,
    last_synced_at: Option<DateTime<Utc>>,
    deleted: bool,
) -> i64 {
    let account_id: i64 = picsou::db::query_scalar(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, external_account_id, \
         last_synced_at, is_manual, color) VALUES ($1, $2, 'EMPLOYEE_SAVINGS', 'Crédit Mutuel Épargne Salariale', \
         'EUR', $3, $4, $5, false, '#000') RETURNING id",
    )
    .bind(member_id)
    .bind(name)
    .bind(d(balance))
    .bind(id)
    .bind(last_synced_at)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    if deleted {
        picsou::db::query("UPDATE account SET deleted_at = CURRENT_TIMESTAMP WHERE id = $1")
            .bind(account_id)
            .execute(&app.state.db)
            .await
            .unwrap();
    }
    account_id
}

#[tokio::test]
async fn a_numbered_active_account_beats_an_older_zeroed_legacy_account_with_the_same_sync_time() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/positions",
        json!([plan("cmes_pee-0123456789abcdef", &["cmes_PEE"], "100")]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let synced_at = DateTime::parse_from_rfc3339("2026-01-14T08:00:00Z")
        .unwrap()
        .to_utc();
    let legacy_id = insert_account_with_state(
        &app,
        member_id,
        "cmes_12345_PEE",
        "PEE — ACME",
        "0",
        Some(synced_at),
        false,
    )
    .await;
    let active_id = insert_account_with_state(
        &app,
        member_id,
        "cmes_12345_pee-0123456789abcdef",
        "PEE — ACME",
        "50",
        Some(synced_at),
        false,
    )
    .await;
    assert!(legacy_id < active_id);
    let history_date = NaiveDate::from_ymd_opt(2026, 1, 14).unwrap();
    picsou::db::query(
        "INSERT INTO balance_snapshot (account_id, date, balance, invested_amount, created_at, source) \
         VALUES ($1, $2, $3, $3, $4, 'DERIVED')",
    )
    .bind(active_id)
    .bind(history_date)
    .bind(d("50"))
    .bind(synced_at)
    .execute(&app.state.db)
    .await
    .unwrap();

    login(&sidecar, &app, &client).await;

    let rows: Vec<(i64, String, Decimal)> = picsou::db::query_as(
        "SELECT id, external_account_id, current_balance FROM account WHERE deleted_at IS NULL \
         ORDER BY id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (legacy_id, "cmes_12345_PEE".into(), d("0")),
            (active_id, "cmes_pee-0123456789abcdef".into(), d("100")),
        ]
    );
    let snapshots: Vec<(NaiveDate, Decimal)> = picsou::db::query_as(
        "SELECT date, balance FROM balance_snapshot WHERE account_id = $1 ORDER BY date",
    )
    .bind(active_id)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        snapshots,
        vec![(history_date, d("50")), (app.state.clock.today(), d("100")),]
    );
}

#[tokio::test]
async fn a_numbered_account_beats_a_more_recently_synced_legacy_account() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/positions",
        json!([plan("cmes_pee-0123456789abcdef", &["cmes_PEE"], "100")]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let earlier = DateTime::parse_from_rfc3339("2026-01-13T08:00:00Z")
        .unwrap()
        .to_utc();
    let later = DateTime::parse_from_rfc3339("2026-01-14T08:00:00Z")
        .unwrap()
        .to_utc();
    let legacy_id = insert_account_with_state(
        &app,
        member_id,
        "cmes_12345_PEE",
        "PEE — ACME",
        "50",
        Some(later),
        false,
    )
    .await;
    let numbered_id = insert_account_with_state(
        &app,
        member_id,
        "cmes_12345_pee-0123456789abcdef",
        "PEE — ACME",
        "50",
        Some(earlier),
        false,
    )
    .await;

    login(&sidecar, &app, &client).await;

    let rows: Vec<(i64, String, Decimal)> = picsou::db::query_as(
        "SELECT id, external_account_id, current_balance FROM account WHERE deleted_at IS NULL \
         ORDER BY id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (legacy_id, "cmes_12345_PEE".into(), d("0")),
            (numbered_id, "cmes_pee-0123456789abcdef".into(), d("100")),
        ]
    );
}

#[tokio::test]
async fn between_two_legacy_accounts_with_the_same_sync_time_the_open_one_wins() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/positions",
        json!([plan("cmes_pee-0123456789abcdef", &["cmes_PEE"], "100")]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let synced_at = DateTime::parse_from_rfc3339("2026-01-14T08:00:00Z")
        .unwrap()
        .to_utc();
    let closed_id = insert_account_with_state(
        &app,
        member_id,
        "cmes_111_PEE",
        "PEE — ACME",
        "0",
        Some(synced_at),
        false,
    )
    .await;
    let open_id = insert_account_with_state(
        &app,
        member_id,
        "cmes_222_PEE",
        "PEE — ACME",
        "50",
        Some(synced_at),
        false,
    )
    .await;

    login(&sidecar, &app, &client).await;

    let rows: Vec<(i64, String, Decimal)> = picsou::db::query_as(
        "SELECT id, external_account_id, current_balance FROM account WHERE deleted_at IS NULL \
         ORDER BY id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (closed_id, "cmes_111_PEE".into(), d("0")),
            (open_id, "cmes_pee-0123456789abcdef".into(), d("100")),
        ]
    );
}

#[tokio::test]
async fn an_earlier_account_a_plan_does_not_use_closes_at_zero() {
    // The plan already has its account under the new id: the numbered one is stale.
    let rows = sync_one_plan(
        &["cmes_PEE"],
        &[
            ("cmes_pee-0123456789abcdef", "PEE — ACME", false),
            ("cmes_12345_pee-0123456789abcdef", "PEE — ACME", false),
        ],
    )
    .await;
    assert_eq!(
        rows,
        vec![
            ("cmes_pee-0123456789abcdef".into(), d("100")),
            ("cmes_12345_pee-0123456789abcdef".into(), d("0"))
        ]
    );
}

#[tokio::test]
async fn a_label_holding_the_separator_tells_its_plan_apart() {
    // Two plans of the family whose labels hold " — ": only "PEE — X" carries the old name.
    let rows = sync_plans(
        json!([
            named_plan(
                "cmes_pee-x-0000000000000001",
                &["cmes_PEE"],
                "PEE — X",
                "100"
            ),
            named_plan(
                "cmes_pee-y-0000000000000002",
                &["cmes_PEE"],
                "PEE — Y",
                "200"
            ),
        ]),
        &[("cmes_12345_PEE", "PEE — X — ACME", false)],
    )
    .await;
    assert_eq!(
        rows,
        vec![
            ("cmes_pee-x-0000000000000001".into(), d("100")),
            ("cmes_pee-y-0000000000000002".into(), d("200"))
        ]
    );
}

#[tokio::test]
async fn an_earlier_account_goes_to_the_plan_named_like_it_over_a_shorter_label() {
    // "PEE — X — ACME" carries both "PEE" and "PEE — X": it is the latter's.
    let rows = sync_plans(
        json!([
            named_plan("cmes_pee-0000000000000001", &["cmes_PEE"], "PEE", "100"),
            named_plan(
                "cmes_pee-x-0000000000000002",
                &["cmes_PEE"],
                "PEE — X",
                "200"
            ),
        ]),
        &[("cmes_12345_PEE", "PEE — X — ACME", false)],
    )
    .await;
    assert_eq!(
        rows,
        vec![
            ("cmes_pee-x-0000000000000002".into(), d("200")),
            ("cmes_pee-0000000000000001".into(), d("100"))
        ]
    );
}

#[tokio::test]
async fn a_deletion_belongs_to_the_plan_named_like_it_over_a_shorter_label() {
    let rows = sync_plans(
        json!([
            named_plan("cmes_pee-0000000000000001", &["cmes_PEE"], "PEE", "100"),
            named_plan(
                "cmes_pee-x-0000000000000002",
                &["cmes_PEE"],
                "PEE — X",
                "200"
            ),
        ]),
        &[("cmes_12345_PEE", "PEE — X — ACME", true)],
    )
    .await;
    assert_eq!(rows, vec![("cmes_pee-0000000000000001".into(), d("100"))]);
}

#[tokio::test]
async fn a_name_cut_at_100_characters_still_carries_its_label() {
    let label = "P".repeat(98);
    // "<label> — ACME" cut at 100 UTF-16 units keeps "<label> —".
    let stored_name = format!("{label} —");
    let rows = sync_plans(
        json!([named_plan(
            "cmes_p-0123456789abcdef",
            &["cmes_PEE"],
            &label,
            "100"
        )]),
        &[("cmes_12345_PEE", &stored_name, false)],
    )
    .await;
    assert_eq!(rows, vec![("cmes_p-0123456789abcdef".into(), d("100"))]);
}

#[tokio::test]
async fn a_deletion_counts_whatever_the_provider_of_the_deleted_account() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/positions",
        json!([plan("cmes_pee-0123456789abcdef", &["cmes_PEE"], "100")]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    insert_account(
        &app,
        member_id,
        "cmes_12345_pee-0123456789abcdef",
        "PEE — ACME",
        true,
    )
    .await;
    picsou::db::query("UPDATE account SET provider = 'Crédit Mutuel' WHERE member_id = $1")
        .bind(member_id)
        .execute(&app.state.db)
        .await
        .unwrap();

    login(&sidecar, &app, &client).await;

    assert!(balances(&app).await.is_empty());
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

#[tokio::test]
async fn a_fund_line_with_an_isin_stores_it_upper_cased() {
    let sidecar = MockServer::start().await;
    let mut body = plan("pee-1", &[], "100");
    body["positions"][0]["isin"] = json!("fr0010135103");
    answer(&sidecar, "/positions", json!([body])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    login(&sidecar, &app, &client).await;

    let held: (String, Option<String>) =
        picsou::db::query_as("SELECT ticker, isin FROM account_holding")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(held, ("FR0010135103".into(), Some("FR0010135103".into())));
}

#[tokio::test]
async fn a_fund_line_without_an_isin_stores_none() {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/positions", json!([plan("pee-1", &[], "100")])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    login(&sidecar, &app, &client).await;

    let held: (String, Option<String>) =
        picsou::db::query_as("SELECT ticker, isin FROM account_holding")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(held, ("CMES-F1".into(), None));
}
