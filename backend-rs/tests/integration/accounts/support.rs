//! Fixtures of the accounts tests: rows the endpoints read, written as SQL.

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
    picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .expect("admin member")
}

pub async fn account(app: &TestApp, member_id: i64, name: &str, kind: &str, balance: &str) -> i64 {
    picsou::db::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color, created_at, updated_at) \
         VALUES ($1, $2, $3::account_type, 'EUR', $4, true, '#000000', now(), now()) RETURNING id",
    )
    .bind(member_id)
    .bind(name)
    .bind(kind)
    .bind(d(balance))
    .fetch_one(&app.state.db)
    .await
    .expect("account")
}

pub async fn exec(app: &TestApp, sql: &str) {
    app.state.db.execute_batch(sql).await.expect(sql);
}

pub async fn snapshot(app: &TestApp, account_id: i64, date: &str, balance: &str, invested: &str) {
    exec(
        app,
        &format!(
            "INSERT INTO balance_snapshot (account_id, date, balance, invested_amount, created_at, source) \
             VALUES ({account_id}, '{date}', {balance}, {invested}, now(), 'DERIVED')"
        ),
    )
    .await;
}

pub async fn share(app: &TestApp, account_id: i64, member_id: i64, percent: &str) {
    exec(
        app,
        &format!(
            "INSERT INTO account_ownership (account_id, member_id, share_percent, created_at, updated_at) \
             VALUES ({account_id}, {member_id}, {percent}, now(), now())"
        ),
    )
    .await;
}

pub fn ids(list: &Value) -> Vec<i64> {
    list.as_array()
        .expect("a list")
        .iter()
        .map(|a| a["id"].as_i64().expect("an id"))
        .collect()
}
