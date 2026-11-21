//! GET /api/accounts/{id}/positions: the per-product breakdown of an exchange account.

use picsou::testing::TestApp;
use reqwest::StatusCode;
use serde_json::{Value, json};

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

async fn position(
    app: &TestApp,
    account_id: i64,
    product: &str,
    ticker: &str,
    quantity: &str,
    principal: Option<&str>,
    interest: Option<&str>,
) {
    sqlx::query(
        "INSERT INTO crypto_exchange_position (account_id, product, ticker, quantity, principal, interest) \
         VALUES ($1, $2, $3, $4::numeric, $5::numeric, $6::numeric)",
    )
    .bind(account_id)
    .bind(product)
    .bind(ticker)
    .bind(quantity)
    .bind(principal)
    .bind(interest)
    .execute(&app.state.db)
    .await
    .expect("position");
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

#[tokio::test]
async fn an_account_without_positions_or_with_spot_only_has_no_breakdown() {
    let (app, member, client) = setup().await;
    let checking = account(&app, member, "CHECKING", "EUR").await;
    let binance = account(&app, member, "CRYPTO", "EUR").await;
    position(&app, binance, "SPOT", "BTC", "1", None, None).await;
    position(&app, binance, "SPOT", "ETH", "2", None, None).await;

    for id in [checking, binance] {
        let response = client
            .get(&format!("/api/accounts/{id}/positions"))
            .send()
            .await;
        assert_eq!(response.status, StatusCode::OK);
        assert_eq!(response.json(), json!([]));
    }
}

#[tokio::test]
async fn lines_share_the_assets_unit_cost_and_are_valued_at_the_recorded_crypto_price() {
    let (app, member, client) = setup().await;
    let meria = account(&app, member, "CRYPTO", "EUR").await;
    position(
        &app,
        meria,
        "STAKING",
        "BTC",
        "0.30000000",
        Some("0.25000000"),
        Some("0.05000000"),
    )
    .await;
    position(&app, meria, "SPOT", "BTC", "0.20000000", None, None).await;
    position(&app, meria, "LENDING", "NOPE", "5", None, None).await;
    sqlx::query(
        "INSERT INTO account_holding (account_id, ticker, quantity, average_buy_in, created_at, updated_at) \
         VALUES ($1, 'BTC', 0.5, 20000, now(), now())",
    )
    .bind(meria)
    .execute(&app.state.db)
    .await
    .unwrap();
    // BTC is a registered coin; the snapshot stands in for the live quote, marked stale.
    sqlx::query("INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ('BTC', '2026-01-14', 30000, now())")
        .execute(&app.state.db)
        .await
        .unwrap();

    let body = client
        .get(&format!("/api/accounts/{meria}/positions"))
        .send()
        .await
        .json();
    let lines = body.as_array().unwrap();
    let order: Vec<(&str, &str)> = lines
        .iter()
        .map(|l| {
            (
                l["product"].as_str().unwrap(),
                l["ticker"].as_str().unwrap(),
            )
        })
        .collect();
    assert_eq!(
        order,
        [("LENDING", "NOPE"), ("SPOT", "BTC"), ("STAKING", "BTC")]
    );

    let unpriced = &lines[0];
    assert!(unpriced.get("currentPriceEur").is_none());
    assert!(unpriced.get("averageBuyIn").is_none());
    assert!(unpriced.get("pnlPercent").is_none());
    assert_eq!(unpriced["priceStale"], false);

    let staking = &lines[2];
    assert_eq!(num(&staking["quantity"]), "0.30000000");
    assert_eq!(num(&staking["principal"]), "0.25000000");
    assert_eq!(num(&staking["interest"]), "0.05000000");
    assert_eq!(num(&staking["averageBuyIn"]), "20000.00000000");
    assert_eq!(num(&staking["currentPriceEur"]), "30000.00000000");
    assert_eq!(num(&staking["currentValueEur"]), "9000.0000000000000000");
    assert_eq!(num(&staking["costBasisEur"]), "6000.0000000000000000");
    assert_eq!(num(&staking["pnlEur"]), "3000.0000000000000000");
    assert_eq!(num(&staking["pnlPercent"]), "50.0000");
    assert_eq!(staking["priceAsOf"], "2026-01-14");
    assert_eq!(staking["priceStale"], true);
    assert!(staking.get("logoUrl").is_none(), "no logo provider answers");
}

#[tokio::test]
async fn another_members_account_is_not_found() {
    let (app, _, client) = setup().await;
    let (_, carol) = app.create_member("carol", PASSWORD).await;
    let hers = account(&app, carol, "CRYPTO", "EUR").await;

    let response = client
        .get(&format!("/api/accounts/{hers}/positions"))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::NOT_FOUND);
}
