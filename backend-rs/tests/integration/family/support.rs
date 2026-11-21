//! Fixtures of the family tests: members, accounts, goals and shares written as SQL rows.

use picsou::testing::TestApp;
use rust_decimal::Decimal;
use serde_json::Value;

pub const PASSWORD: &str = "member-pass-1234";

pub fn d(value: &str) -> Decimal {
    value.parse().expect("decimal literal")
}

/// A JSON number's source text, so a test pins the scale Jackson would write.
pub fn num(value: &Value) -> String {
    assert!(value.is_number(), "expected a number, got {value}");
    value.to_string()
}

pub async fn admin_member(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .expect("admin member")
}

pub async fn account(
    app: &TestApp,
    member_id: i64,
    name: &str,
    account_type: &str,
    balance: &str,
) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color, created_at, updated_at) \
         VALUES ($1, $2, $3::account_type, 'EUR', $4, true, '#000000', now(), now()) RETURNING id",
    )
    .bind(member_id)
    .bind(name)
    .bind(account_type)
    .bind(d(balance))
    .fetch_one(&app.state.db)
    .await
    .expect("account")
}

pub async fn exec(app: &TestApp, sql: &str) {
    sqlx::raw_sql(sql).execute(&app.state.db).await.expect(sql);
}

pub async fn share_level(app: &TestApp, member_id: i64, resource_type: &str, level: &str) {
    sqlx::query(
        "INSERT INTO sharing_settings (member_id, resource_type, sharing_level) VALUES ($1, $2, $3::sharing_level)",
    )
    .bind(member_id)
    .bind(resource_type)
    .bind(level)
    .execute(&app.state.db)
    .await
    .expect("sharing settings");
}

pub async fn share_resource(app: &TestApp, member_id: i64, resource_type: &str, id: i64) {
    sqlx::query("INSERT INTO shared_resource (owner_member_id, resource_type, resource_id) VALUES ($1, $2, $3)")
        .bind(member_id)
        .bind(resource_type)
        .bind(id)
        .execute(&app.state.db)
        .await
        .expect("shared resource");
}

pub async fn goal(
    app: &TestApp,
    member_id: i64,
    name: &str,
    target: &str,
    accounts: &[i64],
) -> i64 {
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO goal (member_id, name, target_amount, deadline) VALUES ($1, $2, $3, '2099-01-01') RETURNING id",
    )
    .bind(member_id)
    .bind(name)
    .bind(d(target))
    .fetch_one(&app.state.db)
    .await
    .expect("goal");
    for account in accounts {
        sqlx::query("INSERT INTO goal_account (goal_id, account_id) VALUES ($1, $2)")
            .bind(id)
            .bind(account)
            .execute(&app.state.db)
            .await
            .expect("goal account");
    }
    id
}

pub async fn contribution(app: &TestApp, goal_id: i64, member_id: i64, month: &str, amount: &str) {
    sqlx::query("INSERT INTO goal_manual_contribution (goal_id, member_id, year_month, amount) VALUES ($1, $2, $3, $4)")
        .bind(goal_id)
        .bind(member_id)
        .bind(month)
        .bind(d(amount))
        .execute(&app.state.db)
        .await
        .expect("contribution");
}
