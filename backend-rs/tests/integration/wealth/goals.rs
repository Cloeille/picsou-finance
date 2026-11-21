//! Goal endpoints: progress, member scoping, monthly entries, history start, overrides.

use picsou::ledger::accounts::NewAccount;
use picsou::ledger::model::AccountType;
use picsou::testing::{Client, TestApp};
use serde_json::{Value, json};

use super::support::{account, d};

pub struct Fixture {
    pub app: TestApp,
    pub admin: Client,
    pub member: i64,
}

impl Fixture {
    pub async fn new() -> Self {
        let app = TestApp::with_admin().await;
        let admin = app.login_admin().await;
        let member: i64 =
            sqlx::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
                .fetch_one(&app.state.db)
                .await
                .unwrap();
        Self { app, admin, member }
    }

    pub async fn account(&self, member: i64, kind: AccountType, balance: &str) -> i64 {
        let mut draft = NewAccount::new(member, "Livret", kind);
        draft.is_manual = true;
        draft.current_balance = d(balance);
        account(&self.app, draft).await.id
    }

    async fn goal(&self, body: Value) -> picsou::testing::Response {
        self.admin.post("/api/goals").json(&body).send().await
    }
}

fn target(accounts: &[i64], amount: u32, deadline: &str) -> Value {
    json!({"name": "Apport", "type": "SAVINGS_TARGET", "targetAmount": amount, "deadline": deadline, "accountIds": accounts})
}

#[tokio::test]
async fn a_savings_target_reports_its_progress() {
    let f = Fixture::new().await;
    let livret = f.account(f.member, AccountType::Lep, "5000").await;
    let response = f.goal(target(&[livret], 20000, "2026-07-15")).await;
    assert_eq!(response.status, 201, "{}", response.text());
    let body = response.json();
    assert_eq!(d(&body["currentTotal"].to_string()), d("5000"));
    assert_eq!(body["percentComplete"].to_string(), "25.0000");
    assert_eq!(body["monthsLeft"], 6);
    assert_eq!(body["monthlyNeeded"].to_string(), "2500.00");
    assert_eq!(body["accounts"][0]["id"], livret);
    assert_eq!(body["isOnTrack"], true);
}

#[tokio::test]
async fn goals_are_member_scoped_and_refuse_foreign_accounts() {
    let f = Fixture::new().await;
    let (_, other) = f.app.create_member("other", "password-1234").await;
    let foreign = f.account(other, AccountType::Savings, "100").await;
    let response = f.goal(target(&[foreign], 1000, "2027-01-31")).await;
    assert_eq!(response.status, 400);
    assert_eq!(
        response.json()["detail"],
        "One or more account IDs not found"
    );

    let mine = f.account(f.member, AccountType::Savings, "100").await;
    let id = f.goal(target(&[mine], 1000, "2027-01-31")).await.json()["id"]
        .as_i64()
        .unwrap();
    let other_client = f.app.login("other", "password-1234").await;
    let response = other_client.get(&format!("/api/goals/{id}")).send().await;
    assert_eq!(response.status, 404);
    assert_eq!(response.json()["detail"], "Goal not found");
    let response = other_client
        .delete(&format!("/api/goals/{id}/months/2026-02"))
        .send()
        .await;
    assert_eq!(response.status, 404);
}

#[tokio::test]
async fn the_request_is_validated() {
    let f = Fixture::new().await;
    let response = f
        .goal(json!({"type": "SAVINGS_TARGET", "accountIds": []}))
        .await;
    assert_eq!(response.status, 422, "{}", response.text());
    assert!(response.json()["errors"]["name"].is_string());
}

#[tokio::test]
async fn extending_the_history_moves_its_start_back_a_year_then_a_month() {
    let f = Fixture::new().await;
    let livret = f.account(f.member, AccountType::Savings, "100").await;
    let id = f.goal(target(&[livret], 1000, "2027-01-31")).await.json()["id"]
        .as_i64()
        .unwrap();
    let body = f
        .admin
        .post(&format!("/api/goals/{id}/history/extend"))
        .send()
        .await
        .json();
    assert_eq!(body["historyStartMonth"], "2025-01");
    let body = f
        .admin
        .post(&format!("/api/goals/{id}/history/extend/month"))
        .send()
        .await
        .json();
    assert_eq!(body["historyStartMonth"], "2024-12");
    let months = f
        .admin
        .get(&format!("/api/goals/{id}/months"))
        .send()
        .await
        .json();
    assert_eq!(months[0]["yearMonth"], "2024-12");
}

#[tokio::test]
async fn an_objective_override_changes_the_objective_not_the_amount_saved() {
    let f = Fixture::new().await;
    let id = f
        .goal(target(
            &[f.account(f.member, AccountType::Savings, "0").await],
            1000,
            "2027-01-31",
        ))
        .await
        .json()["id"]
        .as_i64()
        .unwrap();
    let response = f
        .admin
        .put(&format!("/api/goals/{id}/months/2026-01"))
        .json(&json!({"amount": 200}))
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    // The write answers Java's shape: the objective before overrides, the amount as effective.
    let entry = response.json();
    assert_eq!(d(&entry["override"].to_string()), d("200"));
    assert_eq!(entry["objective"].to_string(), "83.33");
    assert_eq!(d(&entry["effective"].to_string()), d("200"));

    let months = f
        .admin
        .get(&format!("/api/goals/{id}/months"))
        .send()
        .await
        .json();
    let january = months
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["yearMonth"] == "2026-01")
        .unwrap()
        .clone();
    assert_eq!(d(&january["objective"].to_string()), d("200"));
    assert!(january.get("effective").is_none());

    let response = f
        .admin
        .delete(&format!("/api/goals/{id}/months/2026-01"))
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    assert!(response.json().get("override").is_none());
}

#[tokio::test]
async fn goal_creation_embeds_wired_account_response() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let member: i64 = sqlx::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    let mut draft = NewAccount::new(member, "Livret", AccountType::Savings);
    draft.is_manual = true;
    draft.current_balance = d("250");
    let livret = account(&app, draft).await.id;
    let response = admin
        .post("/api/goals")
        .json(&target(&[livret], 1000, "2027-01-31"))
        .send()
        .await;
    assert_eq!(response.status, 201, "{}", response.text());
    let body = response.json();
    assert_eq!(body["accounts"][0]["id"], livret);
    assert_eq!(body["accounts"][0]["type"], "SAVINGS");
    assert_eq!(
        d(&body["accounts"][0]["currentBalanceEur"].to_string()),
        d("250")
    );
}
