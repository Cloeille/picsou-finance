//! The tracked fund of an account: the ISIN of the single fund it holds, validated and stored
//! uppercase. Synthetic data only.

use picsou::testing::{Client, TestApp};
use reqwest::StatusCode;
use serde_json::{Value, json};

use std::collections::{BTreeMap, HashMap};
use std::sync::Mutex;

use chrono::{DateTime, Utc};
use picsou::accounts::seams::{IsinAsTicker, IsinResolver};
use picsou::accounts::tracked_fund;
use picsou::connectors::seams::SecurityIdentities;
use picsou::db::AsDbHandle;
use picsou::ledger::instrument::TickerResult;

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

/// Changes the account's type through the API, with a body that omits the tracked ISIN.
async fn retype(client: &Client, id: i64, kind: &str) {
    let mut request = body("");
    let fields = request.as_object_mut().unwrap();
    fields.remove("trackedIsin");
    fields.insert("type".into(), json!(kind));
    let response = client
        .put(&format!("/api/accounts/{id}"))
        .json(&request)
        .send()
        .await;
    assert_eq!(
        response.status,
        StatusCode::OK,
        "{kind}: {}",
        response.text()
    );
}

/// A SAVINGS account tracking the fund, keyed before anything else happens to it.
async fn keyed_savings(app: &TestApp, member: i64) -> i64 {
    let id = simple(app, member, "SAVINGS", "1000").await;
    track(app, id).await;
    let keys = keys_of(app, &[id]).await;
    assert_eq!(keys.get(&id).map(String::as_str), Some(ISIN), "{keys:?}");
    id
}

#[tokio::test]
async fn a_tracked_account_retyped_to_a_debt_or_peer_loan_is_ignored_but_keeps_its_isin() {
    let app = TestApp::with_admin().await;
    let (member, client) = signed_in(&app, "alice").await;
    for kind in ["LOAN", "CREDIT_CARD", "PEER_LOAN"] {
        let id = keyed_savings(&app, member).await;
        retype(&client, id, kind).await;
        let keys = keys_of(&app, &[id]).await;
        assert!(!keys.contains_key(&id), "{kind} must be ignored: {keys:?}");
        assert!(!tracked_fund::eligible(&loaded(&app, id).await), "{kind}");
        assert_eq!(tracked(&client, id).await, ISIN, "{kind}");
    }
}

#[tokio::test]
async fn a_tracked_account_given_holdings_is_ignored_but_keeps_its_isin() {
    let app = TestApp::with_admin().await;
    let (member, client) = signed_in(&app, "alice").await;
    let id = keyed_savings(&app, member).await;
    holding(&app, id, "IWDA.AS", "10", Some("80")).await;
    let keys = keys_of(&app, &[id]).await;
    assert!(!keys.contains_key(&id), "real lines win: {keys:?}");
    assert!(tracked_fund::eligible(&loaded(&app, id).await));
    assert_eq!(tracked(&client, id).await, ISIN);
}

#[tokio::test]
async fn a_tracked_pocket_is_ignored_but_keeps_its_isin() {
    let app = TestApp::with_admin().await;
    let (member, client) = signed_in(&app, "alice").await;
    let wallet = keyed_savings(&app, member).await;
    let pocket = account(
        &app,
        Acct {
            parent: Some(wallet),
            ..Acct::new(member, "SAVINGS", "1000")
        },
    )
    .await;
    track(&app, pocket).await;
    let keys = keys_of(&app, &[wallet, pocket]).await;
    assert_eq!(keys.get(&wallet).map(String::as_str), Some(ISIN));
    assert!(!keys.contains_key(&pocket), "{keys:?}");
    assert!(!tracked_fund::eligible(&loaded(&app, pocket).await));
    assert_eq!(tracked(&client, pocket).await, ISIN);
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

/// Answers the same ticker for any ISIN.
struct FixedResolver(&'static str);

#[async_trait::async_trait]
impl IsinResolver for FixedResolver {
    async fn resolve(&self, _isin: &str) -> TickerResult {
        TickerResult {
            ticker: self.0.to_owned(),
            name: None,
        }
    }
}

/// Keeps every identity map it is asked to record.
#[derive(Default)]
struct Spy(Mutex<Vec<BTreeMap<String, String>>>);

#[async_trait::async_trait]
impl SecurityIdentities for Spy {
    async fn record(&self, isin_by_ticker: &BTreeMap<String, String>, _now: DateTime<Utc>) {
        self.0.lock().unwrap().push(isin_by_ticker.clone());
    }
}

async fn link(app: &TestApp, resolver: &dyn IsinResolver, spy: &Spy) -> Option<String> {
    let mut conn = app.state.db.acquire().await.unwrap();
    tracked_fund::resolve_and_link(
        &mut conn.as_handle(),
        resolver,
        spy,
        ISIN,
        picsou::testing::epoch(),
    )
    .await
    .unwrap()
}

#[tokio::test]
async fn a_resolvable_isin_is_linked_to_its_ticker() {
    let app = TestApp::with_admin().await;
    let spy = Spy::default();
    let linked = link(&app, &FixedResolver("IWDA.AS"), &spy).await;
    assert_eq!(linked.as_deref(), Some("IWDA.AS"));
    let recorded = spy.0.lock().unwrap().clone();
    assert_eq!(
        recorded,
        vec![BTreeMap::from([("IWDA.AS".to_owned(), ISIN.to_owned())])]
    );
}

#[tokio::test]
async fn an_unresolvable_isin_records_nothing() {
    let app = TestApp::with_admin().await;
    let spy = Spy::default();
    assert_eq!(link(&app, &IsinAsTicker, &spy).await, None);
    assert!(spy.0.lock().unwrap().is_empty());
}

/// Another synthetic-test ISIN, never linked to a ticker here.
const OTHER_ISIN: &str = "US0378331005";
const DELETED_ISIN: &str = "FR0010315770";

async fn track_with(app: &TestApp, id: i64, isin: &str) {
    picsou::db::query("UPDATE account SET tracked_isin = $1 WHERE id = $2")
        .bind(isin)
        .bind(id)
        .execute(&app.state.db)
        .await
        .expect("track");
}

#[tokio::test]
async fn profile_tickers_cover_live_tracked_accounts_only() {
    let app = TestApp::with_admin().await;
    let (member, _) = signed_in(&app, "alice").await;
    let resolved = simple(&app, member, "SAVINGS", "1000").await;
    track(&app, resolved).await;
    profile(&app, "IWDA.AS").await;
    let unresolved = simple(&app, member, "SAVINGS", "1000").await;
    track_with(&app, unresolved, OTHER_ISIN).await;
    let deleted = simple(&app, member, "SAVINGS", "1000").await;
    track_with(&app, deleted, DELETED_ISIN).await;
    picsou::db::query("UPDATE account SET deleted_at = $1 WHERE id = $2")
        .bind(picsou::testing::epoch())
        .bind(deleted)
        .execute(&app.state.db)
        .await
        .expect("soft delete");

    let mut keys = tracked_fund::profile_tickers(&app.state.db).await.unwrap();
    keys.sort();
    assert_eq!(keys, vec!["IWDA.AS".to_owned(), OTHER_ISIN.to_owned()]);

    let held = picsou::ledger::holdings::distinct_tickers(&app.state.db, None)
        .await
        .unwrap();
    for key in ["IWDA.AS", OTHER_ISIN, DELETED_ISIN, ISIN] {
        assert!(!held.iter().any(|t| t == key), "{key} in {held:?}");
    }
}

#[tokio::test]
async fn saving_an_account_with_an_isin_still_answers_when_resolution_fails() {
    let app = TestApp::with_admin().await;
    let (member, client) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "PEA", "1000").await;
    let (status, updated) = put(&client, id, ISIN).await;
    assert_eq!(status, StatusCode::OK, "{updated}");
    assert_eq!(updated["trackedIsin"], ISIN);
}
