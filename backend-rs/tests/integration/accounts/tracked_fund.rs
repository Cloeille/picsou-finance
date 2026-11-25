//! The tracked fund of an account: the ISIN of the single fund it holds, validated and stored
//! uppercase. Synthetic data only.

use picsou::testing::{Client, TestApp};
use reqwest::StatusCode;
use serde_json::{Value, json};

use super::analysis_support::{get_json, signed_in, simple};

/// Every field the update requires, with the tracked ISIN the test sends.
fn body(tracked_isin: &str) -> Value {
    json!({"name": "PEA account", "type": "PEA", "currency": "EUR", "isManual": true, "trackedIsin": tracked_isin})
}

async fn put(client: &Client, id: i64, tracked_isin: &str) -> (StatusCode, Value) {
    let response = client
        .put(&format!("/api/accounts/{id}"))
        .json(&body(tracked_isin))
        .send()
        .await;
    (response.status, response.json())
}

async fn tracked(client: &Client, id: i64) -> Value {
    get_json(client, &format!("/api/accounts/{id}")).await["trackedIsin"].clone()
}

#[tokio::test]
async fn a_valid_isin_is_stored_uppercase_and_returned() {
    let app = TestApp::with_admin().await;
    let (member, client) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "PEA", "1000").await;
    let (status, updated) = put(&client, id, " ie00b4l5y983 ").await;
    assert_eq!(status, StatusCode::OK, "{updated}");
    assert_eq!(updated["trackedIsin"], "IE00B4L5Y983");
    assert_eq!(tracked(&client, id).await, "IE00B4L5Y983");
    let stored: Option<String> =
        picsou::db::query_scalar("SELECT tracked_isin FROM account WHERE id = $1")
            .bind(id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(stored.as_deref(), Some("IE00B4L5Y983"));
}

#[tokio::test]
async fn an_invalid_isin_is_refused_with_a_field_error() {
    let app = TestApp::with_admin().await;
    let (member, client) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "PEA", "1000").await;
    let (status, _) = put(&client, id, "IE00B4L5Y983").await;
    assert_eq!(status, StatusCode::OK);
    let (status, refused) = put(&client, id, "IE00B4L5Y984").await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{refused}");
    assert!(refused["errors"]["trackedIsin"].is_string(), "{refused}");
    assert_eq!(tracked(&client, id).await, "IE00B4L5Y983");
}

#[tokio::test]
async fn a_blank_isin_clears_it() {
    let app = TestApp::with_admin().await;
    let (member, client) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "PEA", "1000").await;
    put(&client, id, "IE00B4L5Y983").await;
    assert_eq!(tracked(&client, id).await, "IE00B4L5Y983");
    let (status, cleared) = put(&client, id, "").await;
    assert_eq!(status, StatusCode::OK, "{cleared}");
    assert!(cleared["trackedIsin"].is_null(), "{cleared}");
    assert!(tracked(&client, id).await.is_null());
}

#[tokio::test]
async fn a_put_without_the_field_keeps_the_isin() {
    let app = TestApp::with_admin().await;
    let (member, client) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "PEA", "1000").await;
    put(&client, id, "IE00B4L5Y983").await;
    let mut without = body("");
    without.as_object_mut().unwrap().remove("trackedIsin");
    let response = client
        .put(&format!("/api/accounts/{id}"))
        .json(&without)
        .send()
        .await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    assert_eq!(response.json()["trackedIsin"], "IE00B4L5Y983");
    assert_eq!(tracked(&client, id).await, "IE00B4L5Y983");
}

#[tokio::test]
async fn a_whitespace_isin_clears_it() {
    let app = TestApp::with_admin().await;
    let (member, client) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "PEA", "1000").await;
    put(&client, id, "IE00B4L5Y983").await;
    let (status, cleared) = put(&client, id, "   ").await;
    assert_eq!(status, StatusCode::OK, "{cleared}");
    assert!(tracked(&client, id).await.is_null());
}

#[tokio::test]
async fn an_account_created_with_an_isin_stores_it() {
    let app = TestApp::with_admin().await;
    let (_, client) = signed_in(&app, "alice").await;
    let response = client
        .post("/api/accounts")
        .json(&body("ie00b4l5y983"))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::CREATED, "{}", response.text());
    let created = response.json();
    assert_eq!(created["trackedIsin"], "IE00B4L5Y983");
    let id = created["id"].as_i64().unwrap();
    assert_eq!(tracked(&client, id).await, "IE00B4L5Y983");
}
