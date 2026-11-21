//! SyncServiceTest: initiation, the OAuth callback and its failure paths.

use picsou::sync::enable_banking::requisitions::RequisitionStatus;
use reqwest::StatusCode;
use serde_json::{Value, json};
use wiremock::matchers::{body_partial_json, method, path, query_param};
use wiremock::{Mock, ResponseTemplate};

use super::support::{
    bank, detail, empty_catalog, no_transactions, requisition, session, status_of,
};

async fn body_of(b: &super::support::Bank, route: &str) -> Value {
    let requests = b.server.received_requests().await.unwrap();
    let request = requests
        .iter()
        .find(|r| r.url.path() == route)
        .expect("request sent");
    serde_json::from_slice(&request.body).unwrap()
}

#[tokio::test]
async fn initiate_stores_a_created_requisition_with_its_nonce_and_the_catalog_logo() {
    let b = bank().await;
    Mock::given(method("POST"))
        .and(path("/auth"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "url": "https://bank.test/authorize?x=1", "authorization_id": "auth-123"
        })))
        .mount(&b.server)
        .await;
    Mock::given(method("GET"))
        .and(path("/aspsps"))
        .and(query_param("country", "FR"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"aspsps": [
            {"name": "Swan", "country": "FR", "psu_types": ["personal"], "logo": "https://logo.test/wrong.png"},
            {"name": "Swan", "country": "FR", "psu_types": ["business"], "logo": "https://logo.test/swan.png"}
        ]})))
        .mount(&b.server)
        .await;
    let response = b
        .admin
        .post("/api/sync/initiate")
        .json(&json!({"institutionId": "Swan::FR::business", "institutionName": "Swan"}))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::OK);
    assert_eq!(
        response.json(),
        json!({"requisitionId": "auth-123", "authLink": "https://bank.test/authorize?x=1"})
    );
    let auth = body_of(&b, "/auth").await;
    assert_eq!(auth["aspsp"], json!({"name": "Swan", "country": "FR"}));
    assert_eq!(auth["psu_type"], "business");
    assert_eq!(auth["redirect_url"], "https://picsou.test/sync/callback");
    assert_eq!(auth["access"]["valid_until"], "2026-04-15T10:00:00Z");
    let nonce = auth["state"].as_str().unwrap().to_owned();
    assert_eq!(uuid::Uuid::parse_str(&nonce).unwrap().get_version_num(), 4);

    let (id, member, logo, status, stored): (i64, i64, Option<String>, String, Option<String>) =
        sqlx::query_as(
            "SELECT id, member_id, logo_url, status::text, oauth_state FROM requisition WHERE requisition_id = 'auth-123'",
        )
        .fetch_one(&b.app.state.db)
        .await
        .unwrap();
    assert!(id > 0);
    assert_eq!(member, b.member_id);
    assert_eq!(
        logo.as_deref(),
        Some("https://logo.test/swan.png"),
        "exact id match wins"
    );
    assert_eq!(status, "CREATED");
    assert_eq!(stored.as_deref(), Some(nonce.as_str()));
}

#[tokio::test]
async fn a_client_supplied_psu_type_outside_the_enum_is_sent_as_personal() {
    let b = bank().await;
    empty_catalog(&b.server).await;
    Mock::given(method("POST"))
        .and(path("/auth"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"url": "https://bank.test/a", "authorization_id": "auth-1"})),
        )
        .mount(&b.server)
        .await;
    let ok = b
        .admin
        .post("/api/sync/initiate")
        .json(&json!({"institutionId": "BoursoBank", "institutionName": "BoursoBank::evil"}))
        .send()
        .await;
    assert_eq!(ok.status, StatusCode::OK);
    let auth = body_of(&b, "/auth").await;
    assert_eq!(
        auth["aspsp"],
        json!({"name": "BoursoBank", "country": "FR"})
    );
    assert_eq!(auth["psu_type"], "personal");
}

#[tokio::test]
async fn initiate_upstream_failure_is_422_and_stores_nothing() {
    let b = bank().await;
    Mock::given(method("POST"))
        .and(path("/auth"))
        .respond_with(ResponseTemplate::new(400).set_body_string("ASPSP_NOT_FOUND"))
        .mount(&b.server)
        .await;
    let response = b
        .admin
        .post("/api/sync/initiate")
        .json(&json!({"institutionId": "Nope::FR::personal", "institutionName": "Nope"}))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        detail(&response.json()),
        "Enable Banking auth failed: ASPSP_NOT_FOUND"
    );
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM requisition")
        .fetch_one(&b.app.state.db)
        .await
        .unwrap();
    assert_eq!(count, 0);
}

fn exchange(session_id: &str) -> Mock {
    Mock::given(method("POST"))
        .and(path("/sessions"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"session_id": session_id})))
}

#[tokio::test]
async fn complete_links_the_accounts_imports_transactions_and_clears_the_nonce() {
    let b = bank().await;
    let id = requisition(
        &b.app,
        b.member_id,
        "auth-1",
        "Banque::FR::personal",
        RequisitionStatus::Created,
        Some("nonce-1"),
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/sessions"))
        .and(body_partial_json(json!({"code": "code-1"})))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"session_id": "sess-1"})))
        .mount(&b.server)
        .await;
    session(
        &b.server,
        "sess-1",
        "uid-1",
        "FR7630006000011234567890189",
        "1234.50",
    )
    .await;
    Mock::given(method("GET"))
        .and(path("/accounts/uid-1/transactions"))
        .and(query_param("date_from", "2025-10-17"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"transactions": [
            {"entry_reference": "E1", "transaction_amount": {"amount": "-12.34", "currency": "EUR"},
             "credit_debit_indicator": "DBIT", "status": "BOOK", "booking_date": "2026-01-10",
             "remittance_information": ["CARTE", " BOULANGERIE "]},
            {"transaction_amount": {"amount": "5.00", "currency": "EUR"}, "credit_debit_indicator": "DBIT",
             "status": "PDNG", "booking_date": "2026-01-14"},
            {"entry_reference": "E2", "transaction_amount": {"amount": 100, "currency": "EUR"},
             "credit_debit_indicator": "CRDT", "status": "BOOK", "value_date": "2026-01-11",
             "debtor": {"name": "Employeur SA"}}
        ]})))
        .mount(&b.server)
        .await;
    empty_catalog(&b.server).await;

    let response = b
        .admin
        .post("/api/sync/complete")
        .json(&json!({"code": "code-1", "state": "nonce-1"}))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    let accounts = response.json();
    assert_eq!(accounts.as_array().unwrap().len(), 1);
    let account = &accounts[0];
    assert_eq!(account["name"], "Compte courant");
    assert_eq!(account["type"], "CHECKING");
    assert_eq!(account["provider"], "Banque");
    assert_eq!(
        account["currentBalance"].to_string(),
        "1234.50",
        "the CLBD balance, scale kept"
    );
    assert_eq!(account["isManual"], false);
    assert_eq!(account["color"], "#6366f1");
    assert_eq!(account["listCollapsed"], false);
    assert!(
        account.get("iban").is_none(),
        "the IBAN never leaves the server"
    );

    let (status, nonce, session_id) = status_of(&b.app, id).await;
    assert_eq!(
        (status.as_str(), nonce, session_id.as_str()),
        ("LINKED", None, "sess-1")
    );
    let rows: Vec<(Option<String>, String, rust_decimal::Decimal)> =
        sqlx::query_as("SELECT external_id, description, amount FROM transaction ORDER BY date")
            .fetch_all(&b.app.state.db)
            .await
            .unwrap();
    assert_eq!(rows.len(), 2, "the pending entry is skipped");
    assert_eq!(rows[0].1, "CARTE BOULANGERIE");
    assert_eq!(rows[0].2, "-12.34".parse().unwrap());
    assert_eq!(rows[1].1, "Employeur SA");
    let (iban, req_link): (Option<String>, Option<i64>) =
        sqlx::query_as("SELECT iban, requisition_id FROM account")
            .fetch_one(&b.app.state.db)
            .await
            .unwrap();
    assert_eq!(iban.as_deref(), Some("FR7630006000011234567890189"));
    assert_eq!(req_link, Some(id));
    let snapshot: rust_decimal::Decimal =
        sqlx::query_scalar("SELECT balance FROM balance_snapshot WHERE date = '2026-01-15'")
            .fetch_one(&b.app.state.db)
            .await
            .unwrap();
    assert_eq!(snapshot, "1234.50".parse().unwrap());

    // The nonce is single-use: replaying it falls back to legacy rows, of which there are none.
    let replay = b
        .admin
        .post("/api/sync/complete")
        .json(&json!({"code": "code-1", "state": "nonce-1"}))
        .send()
        .await;
    assert_eq!(replay.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        detail(&replay.json()),
        "Unknown or expired bank connection. Please initiate a new connection."
    );
}

#[tokio::test]
async fn an_unknown_state_falls_back_only_to_a_legacy_row_without_nonce() {
    let b = bank().await;
    let modern = requisition(
        &b.app,
        b.member_id,
        "auth-new",
        "Banque::FR::personal",
        RequisitionStatus::Created,
        Some("nonce-x"),
    )
    .await;
    let legacy = requisition(
        &b.app,
        b.member_id,
        "auth-old",
        "Legacy::FR",
        RequisitionStatus::Created,
        None,
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/sessions"))
        .respond_with(ResponseTemplate::new(400).set_body_string("{\"error\":\"INVALID_CODE\"}"))
        .mount(&b.server)
        .await;
    let response = b
        .admin
        .post("/api/sync/complete")
        .json(&json!({"code": "c", "state": "forged"}))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        detail(&response.json()),
        "Enable Banking code exchange failed: {\"error\":\"INVALID_CODE\"}"
    );
    assert_eq!(
        status_of(&b.app, legacy).await.0,
        "FAILED",
        "the legacy row took the callback"
    );
    let (status, nonce, _) = status_of(&b.app, modern).await;
    assert_eq!(
        (status.as_str(), nonce.as_deref()),
        ("CREATED", Some("nonce-x"))
    );
}

#[tokio::test]
async fn an_exchange_failure_keeps_the_nonce_so_the_callback_can_be_replayed() {
    let b = bank().await;
    let id = requisition(
        &b.app,
        b.member_id,
        "auth-1",
        "Banque::FR::personal",
        RequisitionStatus::Created,
        Some("nonce-1"),
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/sessions"))
        .respond_with(ResponseTemplate::new(500).set_body_string("boom"))
        .mount(&b.server)
        .await;
    let response = b
        .admin
        .post("/api/sync/complete")
        .json(&json!({"code": "c", "state": "nonce-1"}))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    let (status, nonce, session_id) = status_of(&b.app, id).await;
    assert_eq!(status, "FAILED");
    assert_eq!(nonce.as_deref(), Some("nonce-1"));
    assert_eq!(session_id, "auth-1");
}

#[tokio::test]
async fn a_fetch_failure_after_the_exchange_keeps_the_session_checkpoint() {
    let b = bank().await;
    let id = requisition(
        &b.app,
        b.member_id,
        "auth-1",
        "Banque::FR::personal",
        RequisitionStatus::Created,
        Some("nonce-1"),
    )
    .await;
    exchange("sess-1").mount(&b.server).await;
    Mock::given(method("GET"))
        .and(path("/sessions/sess-1"))
        .respond_with(ResponseTemplate::new(429).set_body_string("ASPSP_RATE_LIMIT_EXCEEDED"))
        .mount(&b.server)
        .await;
    let response = b
        .admin
        .post("/api/sync/complete")
        .json(&json!({"code": "c", "state": "nonce-1"}))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        detail(&response.json()),
        "Failed to fetch session: ASPSP_RATE_LIMIT_EXCEEDED"
    );
    let (status, nonce, session_id) = status_of(&b.app, id).await;
    assert_eq!(
        (status.as_str(), nonce, session_id.as_str()),
        ("FAILED", None, "sess-1")
    );
}

#[tokio::test]
async fn an_empty_session_is_kept_retryable() {
    let b = bank().await;
    let id = requisition(
        &b.app,
        b.member_id,
        "auth-1",
        "Banque::FR::personal",
        RequisitionStatus::Created,
        Some("nonce-1"),
    )
    .await;
    exchange("sess-1").mount(&b.server).await;
    Mock::given(method("GET"))
        .and(path("/sessions/sess-1"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"status": "AUTHORIZED", "accounts": []})),
        )
        .expect(3)
        .mount(&b.server)
        .await;
    let response = b
        .admin
        .post("/api/sync/complete")
        .json(&json!({"code": "c", "state": "nonce-1"}))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::OK);
    assert_eq!(response.json(), json!([]));
    let (status, _, session_id) = status_of(&b.app, id).await;
    assert_eq!((status.as_str(), session_id.as_str()), ("FAILED", "sess-1"));
}

#[tokio::test]
async fn an_already_authorized_replay_refreshes_the_same_institution_only() {
    let b = bank().await;
    let other = requisition(
        &b.app,
        b.member_id,
        "sess-bnp",
        "BNP::FR::personal",
        RequisitionStatus::Linked,
        None,
    )
    .await;
    let same = requisition(
        &b.app,
        b.member_id,
        "sess-rev",
        "Revolut::FR::personal",
        RequisitionStatus::Linked,
        None,
    )
    .await;
    requisition(
        &b.app,
        b.member_id,
        "auth-rev",
        "Revolut::FR::personal",
        RequisitionStatus::Created,
        Some("nonce-rev"),
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/sessions"))
        .respond_with(
            ResponseTemplate::new(422).set_body_string("{\"error\":\"ALREADY_AUTHORIZED\"}"),
        )
        .mount(&b.server)
        .await;
    session(
        &b.server,
        "sess-rev",
        "uid-rev",
        "LT000000000000000001",
        "50.00",
    )
    .await;
    no_transactions(&b.server).await;
    empty_catalog(&b.server).await;
    let response = b
        .admin
        .post("/api/sync/complete")
        .json(&json!({"code": "c", "state": "nonce-rev"}))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    assert_eq!(response.json()[0]["provider"], "Revolut");
    let requests = b.server.received_requests().await.unwrap();
    assert!(
        requests
            .iter()
            .all(|r| r.url.path() != "/sessions/sess-bnp")
    );
    let synced: Option<chrono::DateTime<chrono::Utc>> =
        sqlx::query_scalar("SELECT last_synced_at FROM requisition WHERE id = $1")
            .bind(same)
            .fetch_one(&b.app.state.db)
            .await
            .unwrap();
    assert!(synced.is_some());
    assert_eq!(status_of(&b.app, other).await.0, "LINKED");
}

#[tokio::test]
async fn the_callback_completes_for_the_member_the_nonce_was_issued_to() {
    let b = bank().await;
    let managed = b.app.create_managed_member("Enfant").await;
    let id = requisition(
        &b.app,
        managed,
        "auth-1",
        "Banque::FR::personal",
        RequisitionStatus::Created,
        Some("nonce-kid"),
    )
    .await;
    exchange("sess-1").mount(&b.server).await;
    session(
        &b.server,
        "sess-1",
        "uid-1",
        "FR7600000000000000000000001",
        "10.00",
    )
    .await;
    no_transactions(&b.server).await;
    empty_catalog(&b.server).await;
    let response = b
        .admin
        .post("/api/sync/complete")
        .json(&json!({"code": "c", "state": "nonce-kid"}))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    let owner: i64 = sqlx::query_scalar("SELECT member_id FROM account")
        .fetch_one(&b.app.state.db)
        .await
        .unwrap();
    assert_eq!(owner, managed);
    assert_eq!(status_of(&b.app, id).await.0, "LINKED");
}
