//! The tracked fund of an account: the ISIN of the single fund it holds, validated and stored
//! uppercase. Synthetic data only.

use picsou::testing::{Client, TestApp};
use reqwest::StatusCode;
use serde_json::{Value, json};
use wiremock::matchers::{body_string_contains, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use std::collections::{BTreeMap, HashMap};
use std::sync::Mutex;
use std::time::Duration;

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

    // An explicit null is no value either: the ISIN is kept.
    let mut null = body("");
    null["trackedIsin"] = Value::Null;
    let response = client
        .put(&format!("/api/accounts/{id}"))
        .json(&null)
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
async fn a_resolvable_isin_is_recorded_with_its_ticker() {
    let app = TestApp::with_admin().await;
    let spy = Spy::default();
    // The spy writes nothing, so the ISIN is not linked afterwards: nothing is reported.
    assert_eq!(link(&app, &FixedResolver("IWDA.AS"), &spy).await, None);
    let recorded = spy.0.lock().unwrap().clone();
    assert_eq!(
        recorded,
        vec![BTreeMap::from([("IWDA.AS".to_owned(), ISIN.to_owned())])]
    );
}

#[tokio::test]
async fn a_resolvable_isin_is_linked_to_its_ticker() {
    let app = TestApp::with_admin().await;
    let mut conn = app.state.db.acquire().await.unwrap();
    let linked = tracked_fund::resolve_and_link(
        &mut conn.as_handle(),
        &FixedResolver("IWDA.AS"),
        app.state.connectors.seams.security_identities.as_ref(),
        ISIN,
        picsou::testing::epoch(),
    )
    .await
    .unwrap();
    drop(conn);
    assert_eq!(linked.as_deref(), Some("IWDA.AS"));
    assert_eq!(linked_tickers(&app, ISIN).await, vec!["IWDA.AS".to_owned()]);
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

/// OpenFIGI and Yahoo stand-ins: the app reaches no other host. Yahoo answers 404 to every
/// call, so OpenFIGI's listing is kept as is and profiles come out without data.
struct Market {
    figi: MockServer,
    yahoo: MockServer,
}

impl Market {
    async fn start() -> Self {
        Self {
            figi: MockServer::start().await,
            yahoo: MockServer::start().await,
        }
    }

    /// OpenFIGI maps `isin` to `ticker` on Euronext Amsterdam (`.AS`), `times` times exactly.
    async fn maps(&self, isin: &str, ticker: &str, times: u64) {
        Mock::given(method("POST"))
            .and(path("/v3/mapping"))
            .and(body_string_contains(isin))
            .respond_with(ResponseTemplate::new(200).set_body_json(
                json!([{"data": [{"ticker": ticker, "exchCode": "NA", "name": "SYNTHETIC FUND"}]}]),
            ))
            .expect(times)
            .mount(&self.figi)
            .await;
    }

    async fn app(&self) -> TestApp {
        TestApp::builder()
            .with_admin()
            .env("OPENFIGI_BASE_URL", self.figi.uri())
            .env("YAHOO_FINANCE_BASE_URL", self.yahoo.uri())
            .spawn()
            .await
    }
}

/// The ISIN on the profile row of `ticker`, once a refresh pass has written that row.
async fn refreshed_profile(app: &TestApp, ticker: &str) -> Option<Option<String>> {
    picsou::db::query_scalar::<Option<String>>(
        "SELECT isin FROM security_profile WHERE ticker = $1 AND refreshed_at IS NOT NULL",
    )
    .bind(ticker)
    .fetch_optional(&app.state.db)
    .await
    .unwrap()
}

/// Waits, at most five seconds, for the background pass to refresh the profile of `ticker`.
async fn wait_for_refresh(app: &TestApp, ticker: &str) -> Option<Option<String>> {
    for _ in 0..200 {
        if let Some(isin) = refreshed_profile(app, ticker).await {
            return Some(isin);
        }
        tokio::time::sleep(Duration::from_millis(25)).await;
    }
    None
}

async fn linked_tickers(app: &TestApp, isin: &str) -> Vec<String> {
    picsou::db::query_scalar::<String>(
        "SELECT ticker FROM security_profile WHERE isin = $1 AND ticker <> $1 ORDER BY ticker",
    )
    .bind(isin)
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn saving_an_isin_links_it_to_its_ticker_and_refreshes_that_profile() {
    let market = Market::start().await;
    market.maps(ISIN, "IWDA", 1).await;
    let app = market.app().await;
    let (member, client) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "PEA", "1000").await;
    let (status, updated) = put(&client, id, ISIN).await;
    assert_eq!(status, StatusCode::OK, "{updated}");
    assert_eq!(
        wait_for_refresh(&app, "IWDA.AS").await,
        Some(Some(ISIN.to_owned()))
    );
    assert_eq!(linked_tickers(&app, ISIN).await, vec!["IWDA.AS".to_owned()]);
    market.figi.verify().await;
}

#[tokio::test]
async fn creating_an_account_with_an_isin_links_it_to_its_ticker() {
    let market = Market::start().await;
    market.maps(ISIN, "IWDA", 1).await;
    let app = market.app().await;
    let (_, client) = signed_in(&app, "alice").await;
    let response = client.post("/api/accounts").json(&body(ISIN)).send().await;
    assert_eq!(response.status, StatusCode::CREATED, "{}", response.text());
    assert_eq!(
        wait_for_refresh(&app, "IWDA.AS").await,
        Some(Some(ISIN.to_owned()))
    );
    market.figi.verify().await;
}

#[tokio::test]
async fn saving_an_account_with_an_isin_still_answers_when_resolution_fails() {
    let market = Market::start().await;
    Mock::given(method("POST"))
        .and(path("/v3/mapping"))
        .respond_with(ResponseTemplate::new(500))
        .expect(1)
        .mount(&market.figi)
        .await;
    let app = market.app().await;
    let (member, client) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "PEA", "1000").await;
    let (status, updated) = put(&client, id, ISIN).await;
    assert_eq!(status, StatusCode::OK, "{updated}");
    assert_eq!(updated["trackedIsin"], ISIN);
    // The pass ends with the profile of its key, the ISIN itself: nothing was linked.
    assert_eq!(
        wait_for_refresh(&app, ISIN).await,
        Some(Some(ISIN.to_owned()))
    );
    assert!(linked_tickers(&app, ISIN).await.is_empty());
    assert!(tracked(&client, id).await.is_string());
    market.figi.verify().await;
}

#[tokio::test]
async fn the_weekly_retry_links_only_live_unlinked_isins() {
    let market = Market::start().await;
    market.maps(ISIN, "IWDA", 1).await;
    market.maps(OTHER_ISIN, "AAPL", 0).await;
    market.maps(DELETED_ISIN, "CW8", 0).await;
    let app = market.app().await;
    let (member, _) = signed_in(&app, "alice").await;
    let pending = simple(&app, member, "SAVINGS", "1000").await;
    track(&app, pending).await;
    let linked = simple(&app, member, "SAVINGS", "1000").await;
    track_with(&app, linked, OTHER_ISIN).await;
    picsou::db::query(
        "INSERT INTO security_profile (ticker, asset_type, isin, status, created_at, updated_at) \
         VALUES ('AAPL', 'STOCK', $1, 'OK', now(), now())",
    )
    .bind(OTHER_ISIN)
    .execute(&app.state.db)
    .await
    .unwrap();
    let deleted = simple(&app, member, "SAVINGS", "1000").await;
    track_with(&app, deleted, DELETED_ISIN).await;
    picsou::db::query("UPDATE account SET deleted_at = $1 WHERE id = $2")
        .bind(picsou::testing::epoch())
        .bind(deleted)
        .execute(&app.state.db)
        .await
        .unwrap();

    assert_eq!(tracked_fund::resolve_pending(&app.state).await, 1);
    assert_eq!(linked_tickers(&app, ISIN).await, vec!["IWDA.AS".to_owned()]);
    assert!(linked_tickers(&app, DELETED_ISIN).await.is_empty());
    market.figi.verify().await;
}

#[tokio::test]
async fn the_weekly_retry_links_an_isin_whose_first_lookup_failed() {
    let market = Market::start().await;
    Mock::given(method("POST"))
        .and(path("/v3/mapping"))
        .respond_with(ResponseTemplate::new(500))
        .up_to_n_times(1)
        .expect(1)
        .mount(&market.figi)
        .await;
    market.maps(ISIN, "IWDA", 1).await;
    let app = market.app().await;
    let (member, _) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "SAVINGS", "1000").await;
    track(&app, id).await;

    assert_eq!(tracked_fund::resolve_pending(&app.state).await, 0);
    assert!(linked_tickers(&app, ISIN).await.is_empty());
    // Same process: the failed lookup is cached, but the retry gets past the cache through
    // resolve_fresh and asks OpenFIGI again.
    assert_eq!(tracked_fund::resolve_pending(&app.state).await, 1);
    assert_eq!(linked_tickers(&app, ISIN).await, vec!["IWDA.AS".to_owned()]);
    market.figi.verify().await;
}

/// Seeds the profile row of `ticker` with `isin`, another fund's ISIN.
async fn profile_of_other_fund(app: &TestApp, ticker: &str, isin: &str) {
    picsou::db::query(
        "INSERT INTO security_profile (ticker, asset_type, isin, status, created_at, updated_at) \
         VALUES ($1, 'ETF', $2, 'OK', now(), now())",
    )
    .bind(ticker)
    .bind(isin)
    .execute(&app.state.db)
    .await
    .unwrap();
}

#[tokio::test]
async fn a_ticker_that_already_has_another_isin_is_not_reported_as_linked() {
    let market = Market::start().await;
    market.maps(ISIN, "IWDA", 1).await;
    let app = market.app().await;
    profile_of_other_fund(&app, "IWDA.AS", OTHER_ISIN).await;
    let (member, _) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "SAVINGS", "1000").await;
    track(&app, id).await;

    let mut conn = app.state.db.acquire().await.unwrap();
    let linked = tracked_fund::resolve_and_link(
        &mut conn.as_handle(),
        &FixedResolver("IWDA.AS"),
        app.state.connectors.seams.security_identities.as_ref(),
        ISIN,
        picsou::testing::epoch(),
    )
    .await
    .unwrap();
    drop(conn);
    assert_eq!(linked, None);
    assert_eq!(tracked_fund::resolve_pending(&app.state).await, 0);
    assert!(linked_tickers(&app, ISIN).await.is_empty());
    market.figi.verify().await;
}

#[tokio::test]
async fn saving_an_isin_whose_ticker_has_another_isin_refreshes_the_isin_profile() {
    let market = Market::start().await;
    market.maps(ISIN, "IWDA", 1).await;
    let app = market.app().await;
    profile_of_other_fund(&app, "IWDA.AS", OTHER_ISIN).await;
    let (member, client) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "PEA", "1000").await;
    let (status, updated) = put(&client, id, ISIN).await;
    assert_eq!(status, StatusCode::OK, "{updated}");
    // Nothing was linked, so the fund's key stays the ISIN: that is the profile refreshed.
    assert_eq!(
        wait_for_refresh(&app, ISIN).await,
        Some(Some(ISIN.to_owned()))
    );
    assert!(linked_tickers(&app, ISIN).await.is_empty());
    market.figi.verify().await;
}

#[tokio::test]
async fn the_weekly_profile_pass_links_then_refreshes_a_tracked_fund() {
    let market = Market::start().await;
    market.maps(ISIN, "IWDA", 1).await;
    let app = market.app().await;
    let (member, _) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "SAVINGS", "1000").await;
    track(&app, id).await;
    let refreshed = picsou::jobs::scheduler::refresh_security_profiles(&app.state)
        .await
        .unwrap();
    assert_eq!(refreshed, 1);
    assert_eq!(
        refreshed_profile(&app, "IWDA.AS").await,
        Some(Some(ISIN.to_owned()))
    );
    market.figi.verify().await;
}

#[tokio::test]
async fn the_on_demand_profile_refresh_includes_tracked_funds() {
    let market = Market::start().await;
    let app = market.app().await;
    let (member, _) = signed_in(&app, "alice").await;
    let id = simple(&app, member, "SAVINGS", "1000").await;
    track(&app, id).await;
    let queued = app
        .state
        .accounts
        .analysis
        .profile_runner
        .trigger(&app.state.db)
        .await
        .unwrap();
    assert_eq!(
        queued,
        picsou::accounts::analysis::profiles::Trigger::Queued(1)
    );
    assert_eq!(
        wait_for_refresh(&app, ISIN).await,
        Some(Some(ISIN.to_owned()))
    );
}
