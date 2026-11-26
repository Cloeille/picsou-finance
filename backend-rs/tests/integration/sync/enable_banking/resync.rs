//! SyncServiceTest and SyncServiceIbanMatchTest: retry, reconnect and the scheduled resync.

use picsou::ledger::sidecar::SyncStatus;
use picsou::sync::enable_banking::requisitions::RequisitionStatus;
use picsou::sync::enable_banking::{resync_all_reporting, retry_failed_reporting};
use reqwest::StatusCode;
use serde_json::json;
use wiremock::matchers::{method, path};
use wiremock::{Mock, ResponseTemplate};

use super::support::{
    account, bank, detail, empty_catalog, no_transactions, requisition, session, status_of,
};
use crate::writer_free::{Slow, stays_writable_while};

type AccountRow = (
    i64,
    Option<String>,
    rust_decimal::Decimal,
    String,
    Option<i64>,
);

async fn insert_account(
    b: &super::support::Bank,
    external: &str,
    iban: Option<&str>,
    deleted: bool,
) -> i64 {
    picsou::db::query_scalar(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, is_manual, color, \
         external_account_id, iban, deleted_at, created_at, updated_at) \
         VALUES ($1, 'Old name', 'SAVINGS', 'Banque', 'EUR', 1, false, '#123456', $2, $3, \
         CASE WHEN $4 THEN now() END, now(), now()) RETURNING id",
    )
    .bind(b.member_id)
    .bind(external)
    .bind(iban)
    .bind(deleted)
    .fetch_one(&b.app.state.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn resync_reports_skipped_when_nothing_is_linked() {
    let b = bank().await;
    let result = resync_all_reporting(&b.app.state, b.member_id).await;
    assert_eq!(result.source, "enable-banking");
    assert_eq!(result.status, SyncStatus::SkippedNotConnected);
    assert_eq!(result.message, "No linked requisition");
    let retry = retry_failed_reporting(&b.app.state, b.member_id).await;
    assert_eq!(retry.source, "enable-banking-retry");
    assert_eq!(retry.status, SyncStatus::SkippedNotConnected);
    assert_eq!(retry.message, "No FAILED requisition");
}

#[tokio::test]
async fn resync_matches_a_rotated_uid_by_iban_and_currency() {
    let b = bank().await;
    let existing = insert_account(&b, "uid-old", Some("FR7611111111111111111111111"), false).await;
    let id = requisition(
        &b.app,
        b.member_id,
        "sess-1",
        "Banque::FR::personal",
        RequisitionStatus::Linked,
        None,
    )
    .await;
    session(
        &b.server,
        "sess-1",
        "uid-new",
        "FR7611111111111111111111111",
        "77.70",
    )
    .await;
    no_transactions(&b.server).await;
    empty_catalog(&b.server).await;
    let result = resync_all_reporting(&b.app.state, b.member_id).await;
    assert_eq!(result.status, SyncStatus::Synced, "{result:?}");
    let rows: Vec<AccountRow> = picsou::db::query_as(
        "SELECT id, external_account_id, current_balance, type::text, requisition_id FROM account",
    )
    .fetch_all(&b.app.state.db)
    .await
    .unwrap();
    assert_eq!(rows.len(), 1, "no duplicate account");
    assert_eq!(rows[0].0, existing);
    assert_eq!(rows[0].1.as_deref(), Some("uid-new"));
    assert_eq!(rows[0].2, "77.7".parse().unwrap());
    assert_eq!(rows[0].3, "SAVINGS", "the member's type is kept");
    assert_eq!(rows[0].4, Some(id));
    // The single backfill attempt is recorded once the search completed.
    let attempted: Option<chrono::DateTime<chrono::Utc>> = picsou::db::query_scalar(
        "SELECT logo_backfill_attempted_at FROM requisition WHERE id = $1",
    )
    .bind(id)
    .fetch_one(&b.app.state.db)
    .await
    .unwrap();
    assert_eq!(attempted, Some(b.app.state.clock.now()));
}

#[tokio::test]
async fn resync_never_resurrects_a_deleted_account() {
    let b = bank().await;
    insert_account(&b, "uid-gone", None, true).await;
    insert_account(&b, "uid-other", Some("FR7622222222222222222222222"), true).await;
    requisition(
        &b.app,
        b.member_id,
        "sess-1",
        "Banque::FR::personal",
        RequisitionStatus::Linked,
        None,
    )
    .await;
    Mock::given(method("GET"))
        .and(path("/sessions/sess-1"))
        .respond_with(ResponseTemplate::new(200).set_body_json(
            json!({"accounts": ["uid-gone", "uid-rotated"], "aspsp": {"name": "Banque"}}),
        ))
        .mount(&b.server)
        .await;
    account(
        &b.server,
        "uid-gone",
        "Livret",
        "FR7633333333333333333333333",
        "5.00",
    )
    .await;
    account(
        &b.server,
        "uid-rotated",
        "Courant",
        "FR7622222222222222222222222",
        "6.00",
    )
    .await;
    no_transactions(&b.server).await;
    empty_catalog(&b.server).await;
    let result = resync_all_reporting(&b.app.state, b.member_id).await;
    assert_eq!(result.status, SyncStatus::Synced);
    let live: i64 =
        picsou::db::query_scalar("SELECT count(*) FROM account WHERE deleted_at IS NULL")
            .fetch_one(&b.app.state.db)
            .await
            .unwrap();
    assert_eq!(live, 0);
}

#[tokio::test]
async fn a_failing_resync_marks_only_that_requisition_failed() {
    let b = bank().await;
    let broken = requisition(
        &b.app,
        b.member_id,
        "sess-dead",
        "Dead::FR::personal",
        RequisitionStatus::Linked,
        None,
    )
    .await;
    let fine = requisition(
        &b.app,
        b.member_id,
        "sess-1",
        "Banque::FR::personal",
        RequisitionStatus::Linked,
        None,
    )
    .await;
    Mock::given(method("GET"))
        .and(path("/sessions/sess-dead"))
        .respond_with(ResponseTemplate::new(401).set_body_string("EXPIRED_SESSION"))
        .mount(&b.server)
        .await;
    session(
        &b.server,
        "sess-1",
        "uid-1",
        "FR7600000000000000000000002",
        "1.00",
    )
    .await;
    no_transactions(&b.server).await;
    Mock::given(method("GET"))
        .and(path("/aspsps"))
        .respond_with(ResponseTemplate::new(503))
        .mount(&b.server)
        .await;
    let result = resync_all_reporting(&b.app.state, b.member_id).await;
    assert_eq!(result.status, SyncStatus::Failed);
    assert_eq!(result.message, "One or more syncs failed");
    assert_eq!(status_of(&b.app, broken).await.0, "FAILED");
    assert_eq!(status_of(&b.app, fine).await.0, "LINKED");
    let attempted: Option<chrono::DateTime<chrono::Utc>> = picsou::db::query_scalar(
        "SELECT logo_backfill_attempted_at FROM requisition WHERE id = $1",
    )
    .bind(fine)
    .fetch_one(&b.app.state.db)
    .await
    .unwrap();
    assert_eq!(
        attempted, None,
        "a catalog outage does not burn the backfill attempt"
    );
}

#[tokio::test]
async fn retry_failure_persists_failed_and_a_good_retry_links() {
    let b = bank().await;
    let id = requisition(
        &b.app,
        b.member_id,
        "sess-1",
        "Banque::FR::personal",
        RequisitionStatus::Created,
        None,
    )
    .await;
    empty_catalog(&b.server).await;
    Mock::given(method("GET"))
        .and(path("/sessions/sess-1"))
        .respond_with(ResponseTemplate::new(500).set_body_string("oops"))
        .up_to_n_times(1)
        .mount(&b.server)
        .await;
    let failed = b.admin.post(&format!("/api/sync/{id}/retry")).send().await;
    assert_eq!(failed.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(detail(&failed.json()), "Failed to fetch session: oops");
    assert_eq!(status_of(&b.app, id).await.0, "FAILED");

    session(
        &b.server,
        "sess-1",
        "uid-1",
        "FR7600000000000000000000003",
        "3.00",
    )
    .await;
    no_transactions(&b.server).await;
    let result = retry_failed_reporting(&b.app.state, b.member_id).await;
    assert_eq!(result.status, SyncStatus::Synced, "{result:?}");
    assert_eq!(status_of(&b.app, id).await.0, "LINKED");
    let retried = b.admin.post(&format!("/api/sync/{id}/retry")).send().await;
    assert_eq!(retried.status, StatusCode::OK);
    assert_eq!(retried.json().as_array().unwrap().len(), 1);
}

#[tokio::test]
async fn reconnect_refuses_a_live_connection_and_reinitiates_a_dead_one() {
    let b = bank().await;
    let live = requisition(
        &b.app,
        b.member_id,
        "sess-live",
        "Banque::FR::personal",
        RequisitionStatus::Linked,
        None,
    )
    .await;
    let dead = requisition(
        &b.app,
        b.member_id,
        "sess-dead",
        "Banque::FR::personal",
        RequisitionStatus::Failed,
        None,
    )
    .await;
    let refused = b
        .admin
        .post(&format!("/api/sync/{live}/reconnect"))
        .send()
        .await;
    assert_eq!(refused.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        detail(&refused.json()),
        "This bank connection is still active. Use sync/retry instead, or delete it to start over."
    );
    Mock::given(method("POST"))
        .and(path("/auth"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(
                json!({"url": "https://bank.test/again", "authorization_id": "auth-2"}),
            ),
        )
        .mount(&b.server)
        .await;
    let response = b
        .admin
        .post(&format!("/api/sync/{dead}/reconnect"))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::OK);
    assert_eq!(
        response.json(),
        json!({"requisitionId": "auth-2", "authLink": "https://bank.test/again"})
    );
    let (status, nonce, session_id) = status_of(&b.app, dead).await;
    assert_eq!(
        (status.as_str(), session_id.as_str()),
        ("CREATED", "auth-2")
    );
    assert!(nonce.is_some());
}

#[tokio::test]
async fn the_database_stays_writable_while_the_bank_lists_the_transactions() {
    let b = bank().await;
    requisition(
        &b.app,
        b.member_id,
        "sess-1",
        "Banque::FR::personal",
        RequisitionStatus::Linked,
        None,
    )
    .await;
    session(
        &b.server,
        "sess-1",
        "uid-1",
        "FR7611111111111111111111111",
        "77.70",
    )
    .await;
    let (slow, asked) = Slow::answering(ResponseTemplate::new(200).set_body_json(json!({
        "transactions": [{
            "entry_reference": "E1", "transaction_amount": {"amount": "-12.34", "currency": "EUR"},
            "credit_debit_indicator": "DBIT", "status": "BOOK", "booking_date": "2026-01-10",
            "remittance_information": ["CARTE BOULANGERIE"]
        }]
    })));
    Mock::given(method("GET"))
        .and(path("/accounts/uid-1/transactions"))
        .respond_with(slow)
        .mount(&b.server)
        .await;
    empty_catalog(&b.server).await;

    let result = stays_writable_while(
        &b.app.state.db,
        &asked,
        resync_all_reporting(&b.app.state, b.member_id),
    )
    .await;

    assert_eq!(result.status, SyncStatus::Synced, "{result:?}");
    // What the bank listed before the transaction opened is what the transaction wrote.
    let imported: i64 = picsou::db::query_scalar("SELECT count(*) FROM \"transaction\"")
        .fetch_one(&b.app.state.db)
        .await
        .unwrap();
    assert_eq!(imported, 1);
}

#[tokio::test]
async fn retry_on_an_authorized_empty_session_marks_no_accounts_and_stops_further_retries() {
    let b = bank().await;
    let id = requisition(
        &b.app,
        b.member_id,
        "sess-empty",
        "Banque::FR::personal",
        RequisitionStatus::Failed,
        None,
    )
    .await;
    empty_catalog(&b.server).await;
    Mock::given(method("GET"))
        .and(path("/sessions/sess-empty"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"status": "AUTHORIZED", "accounts": []})),
        )
        .expect(3)
        .mount(&b.server)
        .await;

    let first = retry_failed_reporting(&b.app.state, b.member_id).await;
    assert_eq!(first.status, SyncStatus::Failed);
    let error_code: Option<String> =
        picsou::db::query_scalar("SELECT error_code FROM requisition WHERE id = $1")
            .bind(id)
            .fetch_one(&b.app.state.db)
            .await
            .unwrap();
    assert_eq!(error_code.as_deref(), Some("ENABLE_BANKING_NO_ACCOUNTS"));

    // Subsequent scheduled retries must not hit Enable Banking again.
    let second = retry_failed_reporting(&b.app.state, b.member_id).await;
    assert_eq!(second.status, SyncStatus::SkippedNotConnected);

    let session_calls = b
        .server
        .received_requests()
        .await
        .unwrap()
        .into_iter()
        .filter(|r| r.url.path() == "/sessions/sess-empty")
        .count();
    assert_eq!(session_calls, 3);
}

#[tokio::test]
async fn linked_requisition_stays_linked_on_empty_authorized_session() {
    let b = bank().await;
    let id = requisition(
        &b.app,
        b.member_id,
        "sess-linked-empty",
        "Banque::FR::personal",
        RequisitionStatus::Linked,
        None,
    )
    .await;
    empty_catalog(&b.server).await;
    Mock::given(method("GET"))
        .and(path("/sessions/sess-linked-empty"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"status": "AUTHORIZED", "accounts": []})),
        )
        .mount(&b.server)
        .await;

    let resync = resync_all_reporting(&b.app.state, b.member_id).await;
    assert_eq!(resync.status, SyncStatus::Synced, "{resync:?}");
    assert_eq!(status_of(&b.app, id).await.0, "LINKED");
    let error_code: Option<String> =
        picsou::db::query_scalar("SELECT error_code FROM requisition WHERE id = $1")
            .bind(id)
            .fetch_one(&b.app.state.db)
            .await
            .unwrap();
    assert_eq!(error_code, None);

    let retried = b.admin.post(&format!("/api/sync/{id}/retry")).send().await;
    assert_eq!(retried.status, StatusCode::OK);
    assert_eq!(retried.json(), json!([]));
    assert_eq!(status_of(&b.app, id).await.0, "LINKED");
    let error_code: Option<String> =
        picsou::db::query_scalar("SELECT error_code FROM requisition WHERE id = $1")
            .bind(id)
            .fetch_one(&b.app.state.db)
            .await
            .unwrap();
    assert_eq!(error_code, None);
}
