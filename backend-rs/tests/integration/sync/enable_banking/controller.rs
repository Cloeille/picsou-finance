//! SyncControllerTest: routing, validation, rate limits, not-configured and member scoping.

use picsou::sync::enable_banking::requisitions::RequisitionStatus;
use picsou::testing::TestApp;
use reqwest::StatusCode;
use serde_json::json;
use wiremock::matchers::{method, path};
use wiremock::{Mock, ResponseTemplate};

use super::support::{bank, detail, requisition};

#[tokio::test]
async fn not_configured_names_the_missing_setting_with_a_stable_code() {
    let app = TestApp::builder()
        .with_admin()
        .env(
            "ENABLEBANKING_PRIVATE_KEY_PATH",
            "/nonexistent/picsou/eb.pem",
        )
        .spawn()
        .await;
    let admin = app.login_admin().await;
    let response = admin
        .get("/api/sync/institutions?query=ci&country=FR")
        .send()
        .await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    let body = response.json();
    assert_eq!(body["code"], "ENABLE_BANKING_NOT_CONFIGURED");
    assert!(detail(&body).contains("Key ID is missing"), "{body}");

    let countries = admin.get("/api/sync/countries").send().await;
    assert_eq!(countries.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(countries.json()["code"], "ENABLE_BANKING_NOT_CONFIGURED");

    let initiate = admin
        .post("/api/sync/initiate")
        .json(&json!({"institutionId": "Banque::FR::personal", "institutionName": "Banque"}))
        .send()
        .await;
    assert_eq!(initiate.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(initiate.json()["code"], "ENABLE_BANKING_NOT_CONFIGURED");
    let none: i64 = sqlx::query_scalar("SELECT count(*) FROM requisition")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(none, 0, "a failed initiation stores no requisition");
}

#[tokio::test]
async fn an_application_id_without_a_key_names_the_private_key() {
    let app = TestApp::builder()
        .with_admin()
        .env("ENABLEBANKING_APPLICATION_ID", "app-0001")
        .env(
            "ENABLEBANKING_PRIVATE_KEY_PATH",
            "/nonexistent/picsou/eb.pem",
        )
        .spawn()
        .await;
    let admin = app.login_admin().await;
    let body = admin.get("/api/sync/institutions").send().await.json();
    assert_eq!(body["code"], "ENABLE_BANKING_NOT_CONFIGURED");
    assert!(detail(&body).contains("private key is missing"), "{body}");
}

#[tokio::test]
async fn validation_errors_name_the_blank_fields() {
    let b = bank().await;
    let initiate = b
        .admin
        .post("/api/sync/initiate")
        .json(&json!({"institutionId": " "}))
        .send()
        .await;
    assert_eq!(initiate.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        initiate.json()["errors"],
        json!({"institutionId": "must not be blank", "institutionName": "must not be blank"})
    );
    let complete = b
        .admin
        .post("/api/sync/complete")
        .json(&json!({"state": "nonce-1"}))
        .send()
        .await;
    assert_eq!(complete.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        complete.json()["errors"],
        json!({"code": "must not be blank"})
    );
    let get = b.admin.get("/api/sync/complete?code=x").send().await;
    assert_eq!(get.status, StatusCode::METHOD_NOT_ALLOWED);
    let bad_id = b.admin.post("/api/sync/abc/retry").send().await;
    assert_eq!(bad_id.status, StatusCode::BAD_REQUEST);
    assert!(b.server.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn anonymous_calls_are_rejected() {
    let b = bank().await;
    let anon = b.app.client();
    assert_eq!(
        anon.get("/api/sync/status").send().await.status,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        anon.get("/api/sync/institutions").send().await.status,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn countries_are_sorted_cached_and_rate_limited_per_endpoint() {
    let b = bank().await;
    Mock::given(method("GET"))
        .and(path("/application"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({"countries": ["FR", "DE", "EE"]})),
        )
        .expect(1)
        .mount(&b.server)
        .await;
    for _ in 0..10 {
        let response = b.admin.get("/api/sync/countries").send().await;
        assert_eq!(response.status, StatusCode::OK);
        assert_eq!(response.json(), json!(["DE", "EE", "FR"]));
    }
    let limited = b.admin.get("/api/sync/countries").send().await;
    assert_eq!(limited.status, StatusCode::TOO_MANY_REQUESTS);
    let body = limited.json();
    assert_eq!(
        body["detail"],
        "Too many sync requests. Please wait a moment."
    );
    assert_eq!(body["title"], "Too Many Requests");

    // The initiate bucket is its own: still open.
    let initiate = b
        .admin
        .post("/api/sync/initiate")
        .json(&json!({"institutionId": "Banque::FR::personal", "institutionName": "Banque"}))
        .send()
        .await;
    assert_ne!(initiate.status, StatusCode::TOO_MANY_REQUESTS);

    b.app.clock.advance(std::time::Duration::from_secs(60));
    assert_eq!(
        b.admin.get("/api/sync/countries").send().await.status,
        StatusCode::OK
    );
}

#[tokio::test]
async fn countries_are_refetched_after_six_hours_and_an_empty_answer_keeps_the_last_list() {
    let b = bank().await;
    Mock::given(method("GET"))
        .and(path("/application"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"countries": ["FR"]})))
        .up_to_n_times(1)
        .mount(&b.server)
        .await;
    Mock::given(method("GET"))
        .and(path("/application"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
        .mount(&b.server)
        .await;
    assert_eq!(
        b.admin.get("/api/sync/countries").send().await.json(),
        json!(["FR"])
    );
    b.app
        .clock
        .advance(std::time::Duration::from_secs(6 * 3600 + 1));
    let admin = b.app.login_admin().await;
    assert_eq!(
        admin.get("/api/sync/countries").send().await.json(),
        json!(["FR"])
    );
    assert_eq!(b.server.received_requests().await.unwrap().len(), 2);
}

#[tokio::test]
async fn upstream_failures_become_422_with_the_provider_answer() {
    let b = bank().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(503).set_body_string("{\"error\":\"down\"}"))
        .mount(&b.server)
        .await;
    let institutions = b.admin.get("/api/sync/institutions?query=x").send().await;
    assert_eq!(institutions.status, StatusCode::UNPROCESSABLE_ENTITY);
    let body = institutions.json();
    assert_eq!(
        body["detail"],
        "Failed to fetch institutions: [503 SERVICE_UNAVAILABLE] {\"error\":\"down\"}"
    );
    assert!(body.get("code").is_none());
    let countries = b.admin.get("/api/sync/countries").send().await.json();
    assert_eq!(
        countries["detail"],
        "Failed to fetch application countries: [503 SERVICE_UNAVAILABLE] {\"error\":\"down\"}"
    );
}

#[tokio::test]
async fn status_lists_only_the_members_own_requisitions_without_the_nonce() {
    let b = bank().await;
    let id = requisition(
        &b.app,
        b.member_id,
        "sess-1",
        "Banque::FR::personal",
        RequisitionStatus::Created,
        Some("nonce-secret"),
    )
    .await;
    let list = b.admin.get("/api/sync/status").send().await.json();
    assert_eq!(list.as_array().unwrap().len(), 1);
    let row = &list[0];
    assert_eq!(row["id"], id);
    assert_eq!(row["requisitionId"], "sess-1");
    assert_eq!(row["institutionId"], "Banque::FR::personal");
    assert_eq!(row["status"], "CREATED");
    assert!(row.get("oauthState").is_none() && row.get("member").is_none());
    assert!(row.get("lastSyncedAt").is_none(), "null fields are omitted");
    assert_eq!(row["createdAt"], "2026-01-15T10:00:00Z");

    b.app.create_member("bob", "password-1234").await;
    let bob = b.app.login("bob", "password-1234").await;
    assert_eq!(bob.get("/api/sync/status").send().await.json(), json!([]));
    for response in [
        bob.post(&format!("/api/sync/{id}/retry")).send().await,
        bob.post(&format!("/api/sync/{id}/reconnect")).send().await,
        bob.delete(&format!("/api/sync/{id}")).send().await,
    ] {
        assert_eq!(response.status, StatusCode::NOT_FOUND);
        assert_eq!(response.json()["detail"], "Requisition not found");
    }
    assert!(b.server.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn delete_removes_the_requisition_and_keeps_its_accounts() {
    let b = bank().await;
    let id = requisition(
        &b.app,
        b.member_id,
        "sess-1",
        "Banque::FR::personal",
        RequisitionStatus::Linked,
        None,
    )
    .await;
    let account: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color, requisition_id, created_at, updated_at) \
         VALUES ($1, 'Courant', 'CHECKING', 'EUR', 10, false, '#6366f1', $2, now(), now()) RETURNING id",
    )
    .bind(b.member_id)
    .bind(id)
    .fetch_one(&b.app.state.db)
    .await
    .unwrap();
    let deleted = b.admin.delete(&format!("/api/sync/{id}")).send().await;
    assert_eq!(deleted.status, StatusCode::NO_CONTENT);
    assert!(deleted.body.is_empty());
    let again = b.admin.delete(&format!("/api/sync/{id}")).send().await;
    assert_eq!(again.status, StatusCode::NOT_FOUND);
    let link: Option<i64> = sqlx::query_scalar("SELECT requisition_id FROM account WHERE id = $1")
        .bind(account)
        .fetch_one(&b.app.state.db)
        .await
        .unwrap();
    assert_eq!(link, None);
}
