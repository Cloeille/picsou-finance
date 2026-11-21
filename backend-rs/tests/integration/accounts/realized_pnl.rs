//! RealizedPnlServiceTest over HTTP: moving-average realized P&L from the BUY/SELL stream.

use picsou::testing::TestApp;
use reqwest::StatusCode;
use serde_json::Value;

const PASSWORD: &str = "member-pass-1234";

async fn account(app: &TestApp, member_id: i64, account_type: &str, currency: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color, created_at, updated_at) \
         VALUES ($1, 'Account', $2::account_type, $3, 0, true, '#000000', now(), now()) RETURNING id",
    )
    .bind(member_id)
    .bind(account_type)
    .bind(currency)
    .fetch_one(&app.state.db)
    .await
    .expect("account")
}

/// One BUY or SELL row: (ticker, quantity, price per unit).
async fn trade(
    app: &TestApp,
    account_id: i64,
    date: &str,
    tx_type: &str,
    (ticker, qty, price): (&str, &str, &str),
    fees: Option<&str>,
) {
    sqlx::query(
        "INSERT INTO \"transaction\" (account_id, date, description, amount, tx_type, ticker, name, quantity, price_per_unit, fees, created_at) \
         VALUES ($1, $2::date, 'trade', 0, $3, $4, $4, $5::numeric, $6::numeric, $7::numeric, now())",
    )
    .bind(account_id)
    .bind(date)
    .bind(tx_type)
    .bind(ticker)
    .bind(qty)
    .bind(price)
    .bind(fees)
    .execute(&app.state.db)
    .await
    .expect("trade");
}

fn num(value: &Value) -> String {
    assert!(value.is_number(), "expected a number, got {value}");
    value.to_string()
}

async fn setup() -> (TestApp, i64, picsou::testing::Client) {
    let app = TestApp::with_admin().await;
    let (_, member) = app.create_member("bob", PASSWORD).await;
    let client = app.login("bob", PASSWORD).await;
    (app, member, client)
}

async fn pnl(client: &picsou::testing::Client, account_id: i64) -> Value {
    let response = client
        .get(&format!("/api/accounts/{account_id}/realized-pnl"))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    response.json()
}

#[tokio::test]
async fn a_partial_sell_realizes_against_the_average_cost() {
    let (app, member, client) = setup().await;
    let pea = account(&app, member, "PEA", "EUR").await;
    trade(&app, pea, "2024-01-01", "BUY", ("AAPL", "10", "100"), None).await;
    trade(&app, pea, "2024-06-01", "SELL", ("AAPL", "4", "150"), None).await;

    let body = pnl(&client, pea).await;
    assert_eq!(body["currency"], "EUR");
    assert_eq!(num(&body["realizedTotal"]), "200.00000000");
    let ticker = &body["byTicker"][0];
    assert_eq!(ticker["ticker"], "AAPL");
    assert_eq!(ticker["name"], "AAPL");
    assert_eq!(num(&ticker["quantitySold"]), "4.00000000");
    assert_eq!(num(&ticker["proceeds"]), "600.00000000");
    assert_eq!(num(&ticker["costBasis"]), "400.00000000");
    assert_eq!(ticker["warning"], false);
    let lot = &body["lots"][0];
    assert_eq!(lot["date"], "2024-06-01");
    assert_eq!(num(&lot["avgCost"]), "100.00000000");
    assert_eq!(num(&lot["realized"]), "200.00000000");
}

#[tokio::test]
async fn buys_average_and_fees_raise_cost_and_reduce_proceeds() {
    let (app, member, client) = setup().await;
    let etf = account(&app, member, "PEA", "EUR").await;
    trade(&app, etf, "2024-01-01", "BUY", ("ETF", "10", "100"), None).await;
    trade(&app, etf, "2024-02-01", "BUY", ("ETF", "10", "200"), None).await;
    trade(&app, etf, "2024-03-01", "SELL", ("ETF", "20", "180"), None).await;
    let fees = account(&app, member, "PEA", "EUR").await;
    trade(
        &app,
        fees,
        "2024-01-01",
        "BUY",
        ("VWCE", "10", "100"),
        Some("5"),
    )
    .await;
    trade(
        &app,
        fees,
        "2024-06-01",
        "SELL",
        ("VWCE", "10", "120"),
        Some("5"),
    )
    .await;

    assert_eq!(
        num(&pnl(&client, etf).await["realizedTotal"]),
        "600.00000000"
    );
    let body = pnl(&client, fees).await;
    assert_eq!(num(&body["realizedTotal"]), "190.00000000");
    assert_eq!(num(&body["lots"][0]["avgCost"]), "100.50000000");
    assert_eq!(num(&body["lots"][0]["proceeds"]), "1195.00000000");
}

#[tokio::test]
async fn an_over_sell_clamps_the_cost_and_flags_the_ticker() {
    let (app, member, client) = setup().await;
    let over = account(&app, member, "CRYPTO", "EUR").await;
    trade(&app, over, "2024-01-01", "BUY", ("BTC", "5", "100"), None).await;
    trade(&app, over, "2024-06-01", "SELL", ("BTC", "8", "120"), None).await;
    let naked = account(&app, member, "CRYPTO", "EUR").await;
    trade(&app, naked, "2024-06-01", "SELL", ("SOL", "3", "50"), None).await;

    let body = pnl(&client, over).await;
    assert_eq!(num(&body["realizedTotal"]), "460.00000000");
    assert_eq!(body["byTicker"][0]["warning"], true);
    let body = pnl(&client, naked).await;
    assert_eq!(num(&body["realizedTotal"]), "150.00000000");
    assert_eq!(num(&body["lots"][0]["avgCost"]), "0");
    assert_eq!(body["byTicker"][0]["warning"], true);
}

#[tokio::test]
async fn only_buys_realize_nothing_written_as_jackson_writes_a_scaled_zero() {
    let (app, member, client) = setup().await;
    let pea = account(&app, member, "PEA", "USD").await;
    trade(&app, pea, "2024-01-01", "BUY", ("AAPL", "10", "100"), None).await;

    let response = client
        .get(&format!("/api/accounts/{pea}/realized-pnl"))
        .send()
        .await;
    assert!(
        response.text().contains("\"realizedTotal\":0E-8"),
        "{}",
        response.text()
    );
    let body = response.json();
    assert_eq!(body["currency"], "USD");
    assert_eq!(body["byTicker"].as_array().unwrap().len(), 0);
    assert_eq!(body["lots"].as_array().unwrap().len(), 0);
}

#[tokio::test]
async fn same_day_rows_replay_in_insertion_order() {
    let (app, member, client) = setup().await;
    let in_order = account(&app, member, "PEA", "EUR").await;
    trade(
        &app,
        in_order,
        "2026-07-01",
        "BUY",
        ("AAPL", "10", "100"),
        None,
    )
    .await;
    trade(
        &app,
        in_order,
        "2026-07-01",
        "SELL",
        ("AAPL", "10", "110"),
        Some("0"),
    )
    .await;
    let reversed = account(&app, member, "PEA", "EUR").await;
    trade(
        &app,
        reversed,
        "2026-07-01",
        "SELL",
        ("AAPL", "10", "110"),
        Some("0"),
    )
    .await;
    trade(
        &app,
        reversed,
        "2026-07-01",
        "BUY",
        ("AAPL", "10", "100"),
        None,
    )
    .await;

    let body = pnl(&client, in_order).await;
    assert_eq!(num(&body["realizedTotal"]), "100.00000000");
    assert_eq!(body["byTicker"][0]["warning"], false);
    let body = pnl(&client, reversed).await;
    assert_eq!(num(&body["realizedTotal"]), "1100.00000000");
    assert_eq!(body["byTicker"][0]["warning"], true);
}

#[tokio::test]
async fn another_members_account_is_not_found() {
    let (app, _, client) = setup().await;
    let (_, carol) = app.create_member("carol", PASSWORD).await;
    let hers = account(&app, carol, "PEA", "EUR").await;

    let response = client
        .get(&format!("/api/accounts/{hers}/realized-pnl"))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::NOT_FOUND);
    assert_eq!(response.json()["detail"], "Account not found");
}
