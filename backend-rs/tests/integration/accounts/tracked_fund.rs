//! The tracked fund of an account: the ISIN of the single fund it holds, validated and stored
//! uppercase. Synthetic data only.

use picsou::testing::{Client, TestApp};
use reqwest::StatusCode;
use serde_json::{Value, json};

use std::collections::HashMap;

use picsou::accounts::tracked_fund;
use picsou::db::AsDbHandle;

use super::analysis_support::{Acct, account, get_json, holding, signed_in, simple};

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

const ISIN: &str = "IE00B4L5Y983";

async fn track(app: &TestApp, id: i64) {
    picsou::db::query("UPDATE account SET tracked_isin = $1 WHERE id = $2")
        .bind(ISIN)
        .bind(id)
        .execute(&app.state.db)
        .await
        .expect("track");
}

async fn profile(app: &TestApp, ticker: &str) {
    picsou::db::query(
        "INSERT INTO security_profile (ticker, asset_type, isin, status, created_at, updated_at) \
         VALUES ($1, 'ETF', $2, 'OK', now(), now())",
    )
    .bind(ticker)
    .bind(ISIN)
    .execute(&app.state.db)
    .await
    .expect("profile");
}

async fn loaded(app: &TestApp, id: i64) -> picsou::ledger::model::Account {
    picsou::ledger::accounts::find_by_id(&app.state.db, id)
        .await
        .unwrap()
        .expect("account")
}

async fn keys_of(app: &TestApp, ids: &[i64]) -> HashMap<i64, String> {
    let mut accounts = Vec::new();
    for id in ids {
        accounts.push(loaded(app, *id).await);
    }
    let refs: Vec<&_> = accounts.iter().collect();
    let mut conn = app.state.db.acquire().await.unwrap();
    tracked_fund::keys(&mut conn.as_handle(), &refs)
        .await
        .unwrap()
}

#[tokio::test]
async fn the_key_is_the_linked_ticker_else_the_isin() {
    let app = TestApp::with_admin().await;
    let (member, _) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "SAVINGS", "1000").await;
    track(&app, id).await;
    assert_eq!(
        keys_of(&app, &[id]).await.get(&id).map(String::as_str),
        Some(ISIN)
    );
    profile(&app, "IWDA.AS").await;
    profile(&app, ISIN).await;
    assert_eq!(
        keys_of(&app, &[id]).await.get(&id).map(String::as_str),
        Some("IWDA.AS")
    );
    profile(&app, "SWDA.L").await;
    assert_eq!(
        keys_of(&app, &[id]).await.get(&id).map(String::as_str),
        Some("IWDA.AS")
    );
}

#[tokio::test]
async fn the_fund_is_ignored_on_holdings_liabilities_peer_loans_and_pockets() {
    let app = TestApp::with_admin().await;
    let (member, _) = signed_in(&app, "alice").await;
    let plain = simple(&app, member, "SAVINGS", "1000").await;
    let held = simple(&app, member, "SAVINGS", "1000").await;
    holding(&app, held, "IWDA.AS", "10", Some("80")).await;
    let loan = simple(&app, member, "LOAN", "1000").await;
    let peer = simple(&app, member, "PEER_LOAN", "1000").await;
    let pocket = account(
        &app,
        Acct {
            parent: Some(plain),
            ..Acct::new(member, "SAVINGS", "1000")
        },
    )
    .await;
    let ignored = [held, loan, peer, pocket];
    for id in [plain].iter().chain(&ignored) {
        track(&app, *id).await;
    }
    let keys = keys_of(&app, &[plain, held, loan, peer, pocket]).await;
    assert_eq!(keys.get(&plain).map(String::as_str), Some(ISIN));
    for id in ignored {
        assert!(!keys.contains_key(&id), "{id} must be ignored: {keys:?}");
        assert!(!tracked_fund::eligible(&loaded(&app, id).await) || id == held);
        assert_eq!(loaded(&app, id).await.tracked_isin.as_deref(), Some(ISIN));
    }
}

#[tokio::test]
async fn the_account_response_carries_the_resolved_ticker_only() {
    let app = TestApp::with_admin().await;
    let (member, client) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "SAVINGS", "1000").await;
    track(&app, id).await;
    let path = format!("/api/accounts/{id}");
    assert!(get_json(&client, &path).await["trackedTicker"].is_null());
    profile(&app, "IWDA.AS").await;
    assert_eq!(get_json(&client, &path).await["trackedTicker"], "IWDA.AS");
    let listed = get_json(&client, "/api/accounts").await;
    let row = listed
        .as_array()
        .unwrap()
        .iter()
        .find(|a| a["id"] == id)
        .expect("listed");
    assert_eq!(row["trackedTicker"], "IWDA.AS");
}
