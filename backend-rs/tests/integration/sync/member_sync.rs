//! MemberSyncService (per-member sync orchestration), T5 route coverage, and Louve's
//! ConnectionLocks verification.

use std::collections::{BTreeSet, HashSet};
use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use chrono::Days;
use picsou::AppState;
use picsou::db::AsDbHandle;
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::locks::LockedConnection;
use picsou::ledger::model::{AccountType, CategoryKind};
use picsou::ledger::sidecar::{SourceSyncResult, SyncStatus};
use picsou::sync::member_sync::{self, CANONICAL_ORDER, SidecarSources};
use picsou::testing::TestApp;
use regex::Regex;
use rust_decimal::Decimal;
use serde_json::json;
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

const JAVA_ENDPOINTS: &str = include_str!("../../../scripts/java_endpoints.tsv");

const T5_CONTROLLERS: &[&str] = &[
    "SyncController",
    "CryptoExchangeController",
    "WalletController",
    "IbkrController",
    "SimplefinController",
    "WiseController",
    "LouveController",
    "FinaryApiSyncController",
];

fn normalize_path(path: &str) -> String {
    let var = Regex::new(r"\{[^}]*\}").unwrap();
    var.replace_all(path, "{}").into_owned()
}

#[test]
fn all_t5_controllers_are_fully_covered() {
    let routes = picsou::routes::all();
    let rust: BTreeSet<(String, String)> = routes
        .list()
        .iter()
        .map(|(m, p)| (m.to_string(), normalize_path(p)))
        .collect();

    let expected: Vec<(String, String, String)> = JAVA_ENDPOINTS
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(|l| {
            let cols: Vec<&str> = l.split('\t').collect();
            (
                cols[0].to_owned(),
                normalize_path(cols[1]),
                cols.get(2).copied().unwrap_or_default().to_owned(),
            )
        })
        .filter(|(_, _, controller)| T5_CONTROLLERS.contains(&controller.as_str()))
        .collect();

    assert_eq!(expected.len(), 40);
    for (method, path, controller) in expected {
        assert!(
            rust.contains(&(method.clone(), path.clone())),
            "{controller}: {method} {path} not served"
        );
    }
}

struct RecordingSidecars {
    calls: Mutex<Vec<&'static str>>,
    panic_on: Option<&'static str>,
    degiro_status: SyncStatus,
}

impl RecordingSidecars {
    fn new() -> Self {
        Self {
            calls: Mutex::new(Vec::new()),
            panic_on: None,
            degiro_status: SyncStatus::Synced,
        }
    }

    fn panicking_on(source: &'static str) -> Self {
        Self {
            calls: Mutex::new(Vec::new()),
            panic_on: Some(source),
            degiro_status: SyncStatus::Synced,
        }
    }

    fn calls(&self) -> Vec<&'static str> {
        self.calls.lock().unwrap().clone()
    }
}

#[async_trait]
impl SidecarSources for RecordingSidecars {
    async fn resync_reporting(
        &self,
        _state: &AppState,
        source: &'static str,
        _member_id: i64,
    ) -> SourceSyncResult {
        self.calls.lock().unwrap().push(source);
        if self.panic_on == Some(source) {
            panic!("connector-secret-token-123");
        }
        let status = if source == "degiro" {
            self.degiro_status
        } else {
            SyncStatus::Synced
        };
        SourceSyncResult {
            source: source.to_owned(),
            status,
            message: String::new(),
        }
    }
}

async fn member_id(app: &TestApp) -> i64 {
    picsou::db::query_scalar("SELECT id FROM family_member ORDER BY id LIMIT 1")
        .fetch_one(&app.state.db)
        .await
        .expect("seeded member")
}

#[tokio::test]
async fn resync_scheduled_runs_canonical_order_and_skips_degiro() {
    let app = TestApp::with_admin().await;
    let sidecars = Arc::new(RecordingSidecars::new());
    app.state.sync.set_sidecars(sidecars.clone());
    let mid = member_id(&app).await;

    let results = member_sync::resync_scheduled(&app.state, mid).await;

    let sources: Vec<&str> = results.iter().map(|r| r.source.as_str()).collect();
    assert_eq!(sources, CANONICAL_ORDER);
    assert_eq!(results.len(), 23);

    // Revolut precedes Enable Banking; Louve is last; DEGIRO is skipped without calling its hook.
    assert_eq!(results[0].source, "revolut");
    assert_eq!(results[1].source, "enable-banking");
    assert_eq!(results[2].source, "enable-banking-retry");
    assert_eq!(results[15].source, "degiro");
    assert_eq!(results[15].status, SyncStatus::Skipped);
    assert_eq!(results[15].message, "Unattended DEGIRO sync is disabled");
    assert_eq!(results[22].source, "louve");

    assert!(
        !sidecars.calls().contains(&"degiro"),
        "scheduled run must never invoke degiro"
    );
    assert_eq!(
        sidecars.calls(),
        vec![
            "revolut",
            "trade-republic",
            "bourso",
            "bourse-direct",
            "amundi",
            "fortuneo",
            "amex",
            "cmes",
            "cic",
            "corum",
            "natixis",
            "lbp",
            "bnp",
        ]
    );

    // Unconnected T5 connectors report SkippedNotConnected.
    for t5_source in [
        "enable-banking",
        "enable-banking-retry",
        "ibkr",
        "simplefin",
        "wise",
        "crypto-exchanges",
        "wallets",
        "finary",
        "louve",
    ] {
        let entry = results
            .iter()
            .find(|r| r.source == t5_source)
            .unwrap_or_else(|| panic!("missing {t5_source}"));
        assert_eq!(
            entry.status,
            SyncStatus::SkippedNotConnected,
            "unexpected status for unconnected {t5_source}: {entry:?}"
        );
    }
    assert_eq!(
        results
            .iter()
            .find(|r| r.source == "wallets")
            .unwrap()
            .message,
        "No wallets"
    );
    assert_eq!(
        results
            .iter()
            .find(|r| r.source == "finary")
            .unwrap()
            .message,
        "No connected Finary session"
    );
}

#[tokio::test]
async fn resync_for_user_includes_degiro_and_filters_in_canonical_order() {
    let app = TestApp::with_admin().await;
    let sidecars = Arc::new(RecordingSidecars::new());
    app.state.sync.set_sidecars(sidecars.clone());
    let mid = member_id(&app).await;

    let full = member_sync::resync_for_user(&app.state, mid, None).await;
    assert_eq!(full.len(), 23);
    let degiro = full.iter().find(|r| r.source == "degiro").unwrap();
    assert_eq!(degiro.status, SyncStatus::Synced);
    assert!(sidecars.calls().contains(&"degiro"));

    sidecars.calls.lock().unwrap().clear();
    let filter: HashSet<String> = ["louve", "amundi", "revolut", "unknown-source"]
        .into_iter()
        .map(str::to_owned)
        .collect();
    let subset = member_sync::resync_for_user(&app.state, mid, Some(&filter)).await;
    let subset_sources: Vec<&str> = subset.iter().map(|r| r.source.as_str()).collect();
    assert_eq!(subset_sources, vec!["revolut", "amundi", "louve"]);
    assert_eq!(sidecars.calls(), vec!["revolut", "amundi"]);
}

#[tokio::test]
async fn panicking_source_returns_generic_failure_and_does_not_stop_next_sources() {
    let app = TestApp::with_admin().await;
    let sidecars = Arc::new(RecordingSidecars::panicking_on("bourso"));
    app.state.sync.set_sidecars(sidecars.clone());
    let mid = member_id(&app).await;

    let filter: HashSet<String> = ["bourso", "bourse-direct", "amundi"]
        .into_iter()
        .map(str::to_owned)
        .collect();
    let results = member_sync::resync_for_user(&app.state, mid, Some(&filter)).await;

    assert_eq!(results.len(), 3);
    assert_eq!(results[0].source, "bourso");
    assert_eq!(results[0].status, SyncStatus::Failed);
    assert_eq!(results[0].message, "Unexpected sync error");
    assert!(!results[0].message.contains("connector-secret-token-123"));
    assert_eq!(results[1].source, "bourse-direct");
    assert_eq!(results[1].status, SyncStatus::Synced);
    assert_eq!(results[2].source, "amundi");
    assert_eq!(results[2].status, SyncStatus::Synced);
}

#[tokio::test]
async fn after_import_pairs_internal_transfers_even_when_a_source_fails() {
    let app = TestApp::with_admin().await;
    let sidecars = Arc::new(RecordingSidecars::panicking_on("bourso"));
    app.state.sync.set_sidecars(sidecars);
    let mid = member_id(&app).await;
    let now = app.state.clock.now();
    let today = app.state.clock.today();

    let mut new_a = NewAccount::new(mid, "Checking A", AccountType::Checking);
    new_a.is_manual = false;
    let checking_a = accounts::insert(&app.state.db, &new_a, now).await.unwrap();
    let mut new_b = NewAccount::new(mid, "Checking B", AccountType::Checking);
    new_b.is_manual = false;
    let checking_b = accounts::insert(&app.state.db, &new_b, now).await.unwrap();

    let tx_date = today - Days::new(2);
    for (account_id, amount, ext_id) in [
        (checking_a.id, Decimal::new(-25000, 2), "out-1"),
        (checking_b.id, Decimal::new(25000, 2), "in-1"),
    ] {
        picsou::db::query(
            "INSERT INTO transaction (account_id, date, description, amount, native_currency, \
             external_id, is_manual, budget_neutral, category_manual, created_at) \
             VALUES ($1, $2, 'Transfer', $3, 'EUR', $4, false, false, false, $5)",
        )
        .bind(account_id)
        .bind(tx_date)
        .bind(amount)
        .bind(ext_id)
        .bind(now)
        .execute(&app.state.db)
        .await
        .unwrap();
    }

    let filter: HashSet<String> = ["bourso", "wise"].into_iter().map(str::to_owned).collect();
    let results = member_sync::resync_for_user(&app.state, mid, Some(&filter)).await;
    assert_eq!(results[0].status, SyncStatus::Failed);
    assert_eq!(results[1].status, SyncStatus::SkippedNotConnected);

    let paired_kinds: Vec<String> = picsou::db::query_scalar(
        "SELECT c.kind::text FROM transaction t JOIN category c ON c.id = t.category_id \
         WHERE t.account_id IN ($1, $2) ORDER BY t.account_id",
    )
    .bind(checking_a.id)
    .bind(checking_b.id)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        paired_kinds,
        vec![
            CategoryKind::Transfer.as_str(),
            CategoryKind::Transfer.as_str()
        ]
    );
}

#[tokio::test]
async fn connected_wise_syncs_through_member_sync() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v2/profiles"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([
            {"id": 1, "type": "PERSONAL"}
        ])))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v4/profiles/1/balances"))
        .and(query_param("types", "SAVINGS"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([
            {
                "id": 42,
                "currency": "EUR",
                "type": "SAVINGS",
                "name": "Emergency",
                "investmentState": "NOT_INVESTED",
                "amount": {"value": 1500.00, "currency": "EUR"},
                "totalWorth": {"value": 1500.00, "currency": "EUR"},
                "visible": true,
                "primary": false
            }
        ])))
        .mount(&server)
        .await;

    let app = TestApp::builder()
        .env("WISE_API_URL", server.uri())
        .with_admin()
        .spawn()
        .await;
    let (_, mid) = app.create_member("alice", "password-1234").await;
    let client = app.login("alice", "password-1234").await;
    let connect = client
        .post("/api/wise/connect")
        .json(&json!({"token": "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"}))
        .send()
        .await;
    assert_eq!(connect.status, reqwest::StatusCode::OK);

    let filter: HashSet<String> = ["wise".to_owned()].into_iter().collect();
    let results = member_sync::resync_for_user(&app.state, mid, Some(&filter)).await;
    assert_eq!(results.len(), 1);
    assert_eq!(results[0].source, "wise");
    assert_eq!(results[0].status, SyncStatus::Synced);

    let balance: Decimal = picsou::db::query_scalar(
        "SELECT current_balance FROM account WHERE member_id = $1 AND external_account_id = 'wise_jar_42'",
    )
    .bind(mid)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(balance, Decimal::new(150000, 2));
}

#[tokio::test]
async fn louve_connection_lock_uses_member_row_lock() {
    let app = TestApp::with_admin().await;
    let mid = member_id(&app).await;

    let mut tx = app.state.db.begin().await.unwrap();
    app.state
        .ledger
        .connection_locks
        .lock_member_for_account_deletion(&mut tx.as_handle(), LockedConnection::Louve, mid)
        .await
        .expect("locks existing member row");
    tx.commit().await.unwrap();

    let mut tx = app.state.db.begin().await.unwrap();
    let err = app
        .state
        .ledger
        .connection_locks
        .lock_member_for_account_deletion(&mut tx.as_handle(), LockedConnection::Louve, 999_999)
        .await
        .expect_err("unknown member must fail");
    assert!(matches!(err, picsou::error::AppError::NotFound(_)));
}
