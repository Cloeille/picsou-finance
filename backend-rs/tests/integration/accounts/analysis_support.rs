//! Fixtures of the holdings and analysis tests: rows a sync or a background pass would write.

use picsou::testing::{Client, TestApp};
use rust_decimal::Decimal;
use serde_json::Value;

pub const PASSWORD: &str = "password-1234";

pub fn d(value: &str) -> Decimal {
    value.parse().expect("decimal literal")
}

/// A member with a login, signed in.
pub async fn signed_in(app: &TestApp, name: &str) -> (i64, Client) {
    let (_, member) = app.create_member(name, PASSWORD).await;
    (member, app.login(name, PASSWORD).await)
}

pub struct Acct<'a> {
    pub member: i64,
    pub kind: &'a str,
    pub balance: &'a str,
    pub cash: Option<&'a str>,
    pub hidden: bool,
    pub parent: Option<i64>,
    pub manual: bool,
}

impl<'a> Acct<'a> {
    pub fn new(member: i64, kind: &'a str, balance: &'a str) -> Self {
        Self {
            member,
            kind,
            balance,
            cash: None,
            hidden: false,
            parent: None,
            manual: true,
        }
    }
}

pub async fn account(app: &TestApp, a: Acct<'_>) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, current_balance, cash_balance, is_manual, hidden, \
         parent_account_id) VALUES ($1, $2 || ' account', $2::account_type, $3, $4, $5, $6, $7) RETURNING id",
    )
    .bind(a.member)
    .bind(a.kind)
    .bind(d(a.balance))
    .bind(a.cash.map(d))
    .bind(a.manual)
    .bind(a.hidden)
    .bind(a.parent)
    .fetch_one(&app.state.db)
    .await
    .expect("account")
}

pub async fn simple(app: &TestApp, member: i64, kind: &str, balance: &str) -> i64 {
    account(app, Acct::new(member, kind, balance)).await
}

pub async fn holding(
    app: &TestApp,
    account: i64,
    ticker: &str,
    quantity: &str,
    buy_in: Option<&str>,
) {
    sqlx::query(
        "INSERT INTO account_holding (account_id, ticker, name, quantity, average_buy_in, created_at, updated_at) \
         VALUES ($1, $2, $2 || ' name', $3, $4, now(), now())",
    )
    .bind(account)
    .bind(ticker)
    .bind(d(quantity))
    .bind(buy_in.map(d))
    .execute(&app.state.db)
    .await
    .expect("holding");
}

/// A recorded price the day before the test clock's today (2026-01-15).
pub async fn price(app: &TestApp, ticker: &str, eur: &str) {
    sqlx::query(
        "INSERT INTO price_snapshot (ticker, date, price_eur) VALUES ($1, '2026-01-14', $2)",
    )
    .bind(ticker)
    .bind(d(eur))
    .execute(&app.state.db)
    .await
    .expect("price");
}

pub async fn share(app: &TestApp, account: i64, member: i64, percent: &str) {
    sqlx::query(
        "INSERT INTO account_ownership (account_id, member_id, share_percent, created_at, updated_at) \
         VALUES ($1, $2, $3, now(), now())",
    )
    .bind(account)
    .bind(member)
    .bind(d(percent))
    .execute(&app.state.db)
    .await
    .expect("share");
}

pub async fn sql(app: &TestApp, statement: &str) {
    sqlx::raw_sql(statement)
        .execute(&app.state.db)
        .await
        .expect("fixture sql");
}

pub async fn get_json(client: &Client, path: &str) -> Value {
    let response = client.get(path).send().await;
    assert_eq!(response.status, 200, "{path}: {}", response.text());
    response.json()
}

/// The JSON number as its source text, scale included.
pub fn num(value: &Value) -> String {
    match value {
        Value::Number(n) => n.to_string(),
        other => panic!("expected a number, got {other}"),
    }
}

/// The JSON number as a Decimal, for figures whose scale is not the point.
pub fn dec(value: &Value) -> Decimal {
    d(&num(value))
}
