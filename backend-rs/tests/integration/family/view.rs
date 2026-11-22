//! FamilyViewServiceTest over HTTP: the family dashboard aggregates only what the other
//! members share, and goal contributions follow the owner's sharing.

use picsou::testing::TestApp;
use reqwest::StatusCode;
use serde_json::{Value, json};

use super::support::{
    PASSWORD, account, admin_member, contribution, exec, goal, num, share_level, share_resource,
};

fn ids(list: &Value) -> Vec<i64> {
    list.as_array()
        .unwrap()
        .iter()
        .map(|a| a["id"].as_i64().unwrap())
        .collect()
}

#[tokio::test]
async fn nothing_shared_is_an_empty_dashboard_and_the_viewers_own_accounts_never_show() {
    let app = TestApp::with_admin().await;
    let admin_id = admin_member(&app).await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    account(&app, bob, "Private", "CHECKING", "100").await;
    account(&app, admin_id, "Mine", "CHECKING", "50").await;
    share_level(&app, admin_id, "ACCOUNT", "ALL").await;
    let admin = app.login_admin().await;

    let body = admin.get("/api/family/dashboard").send().await.json();
    assert_eq!(
        body,
        json!({"sharedAccounts": [], "sharedGoals": [], "totalSharedNetWorth": 0})
    );
}

#[tokio::test]
async fn shared_loans_count_negatively_and_hidden_or_deleted_accounts_stay_out() {
    let app = TestApp::with_admin().await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    let checking = account(&app, bob, "Checking", "CHECKING", "2000").await;
    let loan = account(&app, bob, "Mortgage", "LOAN", "10000").await;
    let hidden = account(&app, bob, "Hidden", "SAVINGS", "7").await;
    let deleted = account(&app, bob, "Gone", "SAVINGS", "9").await;
    exec(
        &app,
        &format!("UPDATE account SET hidden = true WHERE id = {hidden}"),
    )
    .await;
    exec(
        &app,
        &format!("UPDATE account SET deleted_at = now() WHERE id = {deleted}"),
    )
    .await;
    share_level(&app, bob, "ACCOUNT", "ALL").await;
    let admin = app.login_admin().await;

    let body = admin.get("/api/family/dashboard").send().await.json();
    assert_eq!(ids(&body["sharedAccounts"]), [checking, loan]);
    let shared_loan = &body["sharedAccounts"][1];
    assert_eq!(shared_loan["ownerName"], "bob");
    assert_eq!(shared_loan["type"], "LOAN");
    assert_eq!(shared_loan["currency"], "EUR");
    assert_eq!(num(&shared_loan["balance"]), "10000.00000000");
    assert_eq!(num(&shared_loan["balanceEur"]), "-10000.00000000");
    assert_eq!(num(&body["totalSharedNetWorth"]), "-8000.00000000");
}

#[tokio::test]
async fn a_pocket_counts_once_under_its_shared_wallet_and_alone_without_it() {
    let app = TestApp::with_admin().await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    let (_, carol) = app.create_member("carol", PASSWORD).await;
    let wallet = account(&app, bob, "Revolut EUR", "CHECKING", "1000").await;
    let pocket = account(&app, bob, "Holidays", "CHECKING", "400").await;
    exec(
        &app,
        &format!("UPDATE account SET parent_account_id = {wallet} WHERE id = {pocket}"),
    )
    .await;
    share_level(&app, bob, "ACCOUNT", "ALL").await;
    let carol_wallet = account(&app, carol, "Wallet", "CHECKING", "500").await;
    let carol_pocket = account(&app, carol, "Pocket", "CHECKING", "40").await;
    exec(
        &app,
        &format!("UPDATE account SET parent_account_id = {carol_wallet} WHERE id = {carol_pocket}"),
    )
    .await;
    share_level(&app, carol, "ACCOUNT", "MANUAL").await;
    share_resource(&app, carol, "ACCOUNT", carol_pocket).await;
    let admin = app.login_admin().await;

    let body = admin.get("/api/family/dashboard").send().await.json();
    assert_eq!(ids(&body["sharedAccounts"]), [wallet, pocket, carol_pocket]);
    assert_eq!(num(&body["totalSharedNetWorth"]), "1040.00000000");
}

#[tokio::test]
async fn manual_shares_skip_ids_the_member_does_not_own_and_co_owned_accounts_count_the_owners_share()
 {
    let app = TestApp::with_admin().await;
    let admin_id = admin_member(&app).await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    let house = account(&app, bob, "House", "REAL_ESTATE", "300000").await;
    let admins = account(&app, admin_id, "Admin's", "CHECKING", "999").await;
    picsou::db::query(
        "INSERT INTO account_ownership (account_id, member_id, share_percent, created_at, updated_at) \
         VALUES ($1, $2, 50, now(), now()), ($1, $3, 50, now(), now())",
    )
    .bind(house)
    .bind(bob)
    .bind(admin_id)
    .execute(&app.state.db)
    .await
    .unwrap();
    share_level(&app, bob, "ACCOUNT", "MANUAL").await;
    share_resource(&app, bob, "ACCOUNT", house).await;
    // A stale row pointing at someone else's account must not leak it.
    share_resource(&app, bob, "ACCOUNT", admins).await;
    let admin = app.login_admin().await;

    let body = admin.get("/api/family/dashboard").send().await.json();
    assert_eq!(ids(&body["sharedAccounts"]), [house]);
    assert_eq!(
        num(&body["sharedAccounts"][0]["balanceEur"]),
        "150000.00000000"
    );
    assert_eq!(num(&body["totalSharedNetWorth"]), "150000.00000000");
}

#[tokio::test]
async fn shared_goals_net_linked_loans_and_list_positive_contributions_by_member() {
    let app = TestApp::with_admin().await;
    let admin_id = admin_member(&app).await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    let checking = account(&app, bob, "Checking", "CHECKING", "2000").await;
    let loan = account(&app, bob, "Mortgage", "LOAN", "10000").await;
    let house = goal(&app, bob, "House", "50000", &[checking, loan]).await;
    let empty = goal(&app, bob, "Empty", "10", &[]).await;
    contribution(&app, house, bob, "2026-01", "100.50").await;
    contribution(&app, house, admin_id, "2026-02", "20").await;
    contribution(&app, house, admin_id, "2026-03", "-20").await;
    share_level(&app, bob, "GOAL", "ALL").await;
    let admin = app.login_admin().await;

    let body = admin.get("/api/family/dashboard").send().await.json();
    assert_eq!(
        body["sharedAccounts"],
        json!([]),
        "sharing goals shares no account"
    );
    let goals = body["sharedGoals"].as_array().unwrap();
    assert_eq!(ids(&body["sharedGoals"]), [house, empty]);
    assert_eq!(goals[0]["ownerName"], "bob");
    assert_eq!(num(&goals[0]["targetAmount"]), "50000.00");
    assert_eq!(num(&goals[0]["currentTotal"]), "-8000.00000000");
    // The admin's contributions net to zero, so only Bob's appear.
    assert_eq!(goals[0]["contributions"].as_array().unwrap().len(), 1);
    assert_eq!(goals[0]["contributions"][0]["memberName"], "bob");
    assert_eq!(num(&goals[0]["contributions"][0]["amount"]), "100.50");
    assert_eq!(num(&goals[1]["currentTotal"]), "0");
}

#[tokio::test]
async fn contributions_follow_the_owners_sharing() {
    let app = TestApp::with_admin().await;
    let admin_id = admin_member(&app).await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    let trip = goal(&app, bob, "Trip", "1200", &[]).await;
    let other = goal(&app, bob, "Other", "1", &[]).await;
    contribution(&app, trip, admin_id, "2026-01", "30").await;
    contribution(&app, trip, bob, "2026-02", "70").await;
    let admin = app.login_admin().await;
    let bob_client = app.login("bob", PASSWORD).await;

    let private = admin
        .get(&format!("/api/family/goals/{trip}/contributions"))
        .send()
        .await;
    assert_eq!(private.status, StatusCode::FORBIDDEN);
    assert_eq!(private.json()["detail"], "Goal not accessible");
    let own = bob_client
        .get(&format!("/api/family/goals/{trip}/contributions"))
        .send()
        .await;
    let own = own.json();
    let breakdown: Vec<(String, String)> = own
        .as_array()
        .unwrap()
        .iter()
        .map(|c| {
            (
                c["memberName"].as_str().unwrap().to_owned(),
                num(&c["amount"]),
            )
        })
        .collect();
    assert_eq!(
        breakdown,
        [
            ("admin".to_owned(), "30.00".to_owned()),
            ("bob".to_owned(), "70.00".to_owned())
        ]
    );
    let missing = admin
        .get("/api/family/goals/999999/contributions")
        .send()
        .await;
    assert_eq!(missing.status, StatusCode::NOT_FOUND);
    assert_eq!(missing.json()["detail"], "Goal not found");

    share_level(&app, bob, "GOAL", "MANUAL").await;
    share_resource(&app, bob, "GOAL", trip).await;
    let shared = admin
        .get(&format!("/api/family/goals/{trip}/contributions"))
        .send()
        .await;
    assert_eq!(shared.status, StatusCode::OK);
    assert_eq!(num(&shared.json()[0]["amount"]), "30.00");
    let unshared = admin
        .get(&format!("/api/family/goals/{other}/contributions"))
        .send()
        .await;
    assert_eq!(unshared.status, StatusCode::FORBIDDEN);
}
