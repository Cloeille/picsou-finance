//! AccountServiceTest and AccountOwnershipServiceTest over HTTP: listing, create/update rules,
//! visibility, display preferences, snapshots, ownership and member isolation.

use picsou::testing::TestApp;
use reqwest::StatusCode;
use serde_json::json;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use crate::writer_free::{Slow, stays_writable_while};

use super::support::{PASSWORD, account, admin_member, exec, ids, num};

#[tokio::test]
async fn create_stores_a_manual_cards_amount_owed_as_a_negative_debt_with_its_first_snapshot() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let r = admin
        .post("/api/accounts")
        .json(&json!({"name": "Visa", "type": "CREDIT_CARD", "currency": "EUR", "isManual": true, "currentBalance": 800}))
        .send()
        .await;
    assert_eq!(r.status, StatusCode::CREATED);
    let body = r.json();
    assert_eq!(num(&body["currentBalance"]), "-800");
    assert!(
        body.get("isOwner").is_none(),
        "create answers without a viewer"
    );
    let id = body["id"].as_i64().unwrap();
    let history = admin
        .get(&format!("/api/accounts/{id}/history"))
        .send()
        .await
        .json();
    assert_eq!(history.as_array().unwrap().len(), 1);
    assert_eq!(num(&history[0]["balance"]), "-800.00000000");
}

#[tokio::test]
async fn create_scpi_ignores_the_typed_balance_and_forces_manual_eur() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let body = admin
        .post("/api/accounts")
        .json(&json!({"name": "SCPI", "type": "SCPI", "currency": "USD", "currentBalance": 900}))
        .send()
        .await
        .json();
    assert_eq!(num(&body["currentBalance"]), "0");
    assert_eq!(body["currency"], "EUR");
    assert_eq!(body["isManual"], true);
}

#[tokio::test]
async fn create_validation_reports_every_field_and_a_future_opening_date() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let r = admin
        .post("/api/accounts")
        .json(&json!({"name": " ", "currency": "EURO", "currentBalance": -1, "color": "red", "openedAt": "2026-02-01"}))
        .send()
        .await;
    assert_eq!(r.status, StatusCode::UNPROCESSABLE_ENTITY);
    let errors = &r.json()["errors"];
    assert_eq!(errors["name"], "must not be blank");
    assert_eq!(errors["type"], "must not be null");
    assert_eq!(errors["color"], "Color must be a valid hex color");
    assert_eq!(
        errors["currentBalance"],
        "must be greater than or equal to 0"
    );
    assert_eq!(
        errors["openedAt"],
        "must be a date in the past or in the present"
    );
    assert!(errors["currency"].is_string());
}

#[tokio::test]
async fn update_converting_to_scpi_is_rejected_while_holdings_remain() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let id = account(&app, me, "PEA", "PEA", "100").await;
    exec(&app, &format!("INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) VALUES ({id}, 'CW8.PA', 1, now(), now())")).await;
    let admin = app.login_admin().await;
    let r = admin
        .put(&format!("/api/accounts/{id}"))
        .json(&json!({"name": "PEA", "type": "SCPI", "currency": "EUR"}))
        .send()
        .await;
    assert_eq!(r.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        r.json()["detail"],
        "Cannot convert an account that still has holdings to SCPI"
    );
}

#[tokio::test]
async fn update_same_balance_on_a_manual_ledger_adds_no_statement_and_a_new_one_is_manual() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let id = admin
        .post("/api/accounts")
        .json(&json!({"name": "Courant", "type": "CHECKING", "currency": "EUR", "isManual": true, "currentBalance": 100}))
        .send()
        .await
        .json()["id"]
        .as_i64()
        .unwrap();
    let body = json!({"name": "Courant 2", "type": "CHECKING", "currency": "EUR", "isManual": true, "currentBalance": 100});
    admin
        .put(&format!("/api/accounts/{id}"))
        .json(&body)
        .send()
        .await;
    let source: String =
        picsou::db::query_scalar("SELECT source FROM balance_snapshot WHERE account_id = $1")
            .bind(id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(source, "DERIVED");
    let body = json!({"name": "Courant 2", "type": "CHECKING", "currency": "EUR", "isManual": true, "currentBalance": 150});
    let updated = admin
        .put(&format!("/api/accounts/{id}"))
        .json(&body)
        .send()
        .await
        .json();
    assert_eq!(num(&updated["currentBalance"]), "150");
    let (source, native): (String, Option<rust_decimal::Decimal>) = picsou::db::query_as(
        "SELECT source, native_balance FROM balance_snapshot WHERE account_id = $1",
    )
    .bind(id)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(source, "MANUAL");
    assert_eq!(native, Some(super::support::d("150")));
}

#[tokio::test]
async fn find_all_excludes_hidden_accounts_unless_requested() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let shown = account(&app, me, "Shown", "CHECKING", "1").await;
    let hidden = account(&app, me, "Hidden", "CHECKING", "1").await;
    let admin = app.login_admin().await;
    let r = admin
        .put(&format!("/api/accounts/{hidden}/visibility"))
        .json(&json!({"hidden": true}))
        .send()
        .await;
    assert_eq!(r.json()["hidden"], true);
    assert_eq!(
        ids(&admin.get("/api/accounts").send().await.json()),
        vec![shown]
    );
    assert_eq!(
        ids(&admin
            .get("/api/accounts?includeHidden=true")
            .send()
            .await
            .json()),
        vec![shown, hidden]
    );
}

#[tokio::test]
async fn another_members_account_is_not_found_on_every_route() {
    let app = TestApp::with_admin().await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    let theirs = account(&app, bob, "Bob", "CHECKING", "10").await;
    let admin = app.login_admin().await;
    for (method, path) in [
        ("GET", format!("/api/accounts/{theirs}")),
        ("GET", format!("/api/accounts/{theirs}/history")),
        ("GET", format!("/api/accounts/{theirs}/transactions")),
        ("GET", format!("/api/accounts/{theirs}/ownership")),
        ("GET", format!("/api/accounts/{theirs}/deletion-impact")),
        ("DELETE", format!("/api/accounts/{theirs}")),
        ("GET", format!("/api/history?accountIds={theirs}")),
        ("GET", format!("/api/history/pnl?accountIds={theirs}")),
    ] {
        let request = if method == "GET" {
            admin.get(&path)
        } else {
            admin.delete(&path)
        };
        assert_eq!(
            request.send().await.status,
            StatusCode::NOT_FOUND,
            "{method} {path}"
        );
    }
    let r = admin
        .put(&format!("/api/accounts/{theirs}/visibility"))
        .json(&json!({"hidden": true}))
        .send()
        .await;
    assert_eq!(r.status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn display_order_writes_positions_keeps_collapse_and_rejects_bad_lists() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let a = account(&app, me, "A", "CHECKING", "1").await;
    let b = account(&app, me, "B", "CHECKING", "1").await;
    let admin = app.login_admin().await;
    let collapsed = admin
        .put(&format!("/api/accounts/{a}/display"))
        .json(&json!({"collapsed": true}))
        .send()
        .await
        .json();
    assert_eq!(collapsed["listCollapsed"], true);
    assert!(collapsed.get("listPosition").is_none());
    let ordered = admin
        .put("/api/accounts/display-order")
        .json(&json!({"accountIds": [b, a]}))
        .send()
        .await
        .json();
    assert_eq!(ids(&ordered), vec![b, a]);
    assert_eq!(ordered[1]["listPosition"], 1);
    assert_eq!(ordered[1]["listCollapsed"], true);
    let dup = admin
        .put("/api/accounts/display-order")
        .json(&json!({"accountIds": [a, a]}))
        .send()
        .await;
    assert_eq!(dup.status, StatusCode::BAD_REQUEST);
    let null = admin
        .put("/api/accounts/display-order")
        .json(&json!({"accountIds": null}))
        .send()
        .await;
    assert_eq!(null.status, StatusCode::UNPROCESSABLE_ENTITY);
    let unknown = admin
        .put("/api/accounts/display-order")
        .json(&json!({"accountIds": [a, 999_999]}))
        .send()
        .await;
    assert_eq!(unknown.status, StatusCode::NOT_FOUND);
    let after = admin.get(&format!("/api/accounts/{a}")).send().await.json();
    assert_eq!(after["listPosition"], 1, "a failed update saves nothing");
    admin
        .put("/api/accounts/display-order")
        .json(&json!({"accountIds": []}))
        .send()
        .await;
    assert!(
        admin
            .get(&format!("/api/accounts/{a}"))
            .send()
            .await
            .json()
            .get("listPosition")
            .is_none()
    );
}

#[tokio::test]
async fn snapshot_on_a_peer_loan_is_refused_and_a_card_statement_is_stored_negative() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let loan = account(&app, me, "Prêt à Léa", "PEER_LOAN", "0").await;
    let card = account(&app, me, "Visa", "CREDIT_CARD", "0").await;
    let admin = app.login_admin().await;
    let r = admin
        .post(&format!("/api/accounts/{loan}/history"))
        .json(&json!({"balance": 10, "date": "2026-01-10"}))
        .send()
        .await;
    assert_eq!(r.status, StatusCode::BAD_REQUEST);
    let r = admin
        .post(&format!("/api/accounts/{card}/history"))
        .json(&json!({"balance": 300, "date": "2026-01-10"}))
        .send()
        .await;
    assert_eq!(r.status, StatusCode::CREATED);
    let body = r.json();
    assert_eq!(num(&body["balance"]), "-300");
    assert_eq!(body["source"], "MANUAL");
}

#[tokio::test]
async fn ownership_split_rules_and_the_co_owner_view() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    let flat = account(&app, me, "Flat", "REAL_ESTATE", "200000").await;
    let checking = account(&app, me, "Courant", "CHECKING", "10").await;
    let admin = app.login_admin().await;
    let put = |id: i64, shares: serde_json::Value| {
        admin
            .put(&format!("/api/accounts/{id}/ownership"))
            .json(&json!({"shares": shares}))
    };
    assert_eq!(
        put(checking, json!([])).send().await.status,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    let over = put(
        flat,
        json!([{"memberId": me, "sharePercent": 70}, {"memberId": bob, "sharePercent": 40}]),
    )
    .send()
    .await;
    assert_eq!(
        over.json()["detail"],
        "Ownership shares add up to more than 100%"
    );
    let no_owner = put(flat, json!([{"memberId": bob, "sharePercent": 40}]))
        .send()
        .await;
    assert_eq!(
        no_owner.json()["detail"],
        "The account owner must keep a share"
    );
    let twice = put(
        flat,
        json!([{"memberId": me, "sharePercent": 10}, {"memberId": me, "sharePercent": 10}]),
    )
    .send()
    .await;
    assert_eq!(
        twice.json()["detail"],
        "A member appears twice in the ownership split"
    );
    let under = put(
        flat,
        json!([{"memberId": me, "sharePercent": 50}, {"memberId": bob, "sharePercent": 30}]),
    )
    .send()
    .await
    .json();
    assert_eq!(num(&under["totalAssigned"]), "80");
    assert_eq!(num(&under["unassigned"]), "20");

    let bob_client = app.login("bob", PASSWORD).await;
    let seen = bob_client
        .get(&format!("/api/accounts/{flat}"))
        .send()
        .await
        .json();
    assert_eq!(num(&seen["sharePercent"]), "30.000");
    assert_eq!(seen["isOwner"], false);
    assert_eq!(
        num(&seen["currentBalance"]),
        "200000.00000000",
        "the listing keeps the full value"
    );
    let edit = bob_client
        .put(&format!("/api/accounts/{flat}/ownership"))
        .json(&json!({"shares": []}))
        .send()
        .await;
    assert_eq!(edit.status, StatusCode::FORBIDDEN);

    let cleared = put(flat, json!([])).send().await.json();
    assert_eq!(cleared["shares"][0]["isOwner"], true);
    assert_eq!(num(&cleared["shares"][0]["sharePercent"]), "100");
}

#[tokio::test]
async fn the_database_stays_writable_while_a_typed_balance_is_converted() {
    let yahoo = MockServer::start().await;
    let (slow, asked) = Slow::answering(ResponseTemplate::new(200).set_body_json(json!({
        "chart": {"result": [{
            "meta": {"currency": "EUR", "regularMarketPrice": 0.9},
            "timestamp": [1768471200],
            "indicators": {"quote": [{"close": [0.9]}]}
        }], "error": null}
    })));
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/USDEUR=X"))
        .respond_with(slow)
        .mount(&yahoo)
        .await;
    let app = TestApp::builder()
        .with_admin()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .spawn()
        .await;
    let admin = app.login_admin().await;

    let created = stays_writable_while(
        &app.state.db,
        &asked,
        admin
            .post("/api/accounts")
            .json(&json!({"name": "Dollars", "type": "SAVINGS", "currency": "USD", "isManual": true, "currentBalance": 100}))
            .send(),
    )
    .await;

    assert_eq!(created.status, StatusCode::CREATED, "{}", created.text());
    // The rate asked before the transaction opened is the one the first snapshot was written at.
    let id = created.json()["id"].as_i64().unwrap();
    let history = admin
        .get(&format!("/api/accounts/{id}/history"))
        .send()
        .await
        .json();
    assert_eq!(num(&history[0]["balance"]), "90.00000000");
}
