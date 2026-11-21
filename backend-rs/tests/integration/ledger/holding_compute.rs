use std::str::FromStr;

use chrono::{DateTime, NaiveDate, TimeZone, Utc};
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::holding_compute::{net_positions, recompute_holdings};
use picsou::ledger::holdings;
use picsou::ledger::model::{
    AccountType, HoldingDraft, NewTransaction, Transaction, TransactionType,
};
use picsou::ledger::transactions;
use picsou::testing::TestApp;
use rust_decimal::Decimal;

fn d(s: &str) -> Decimal {
    Decimal::from_str(s).unwrap()
}

fn day(y: i32, m: u32, dd: u32) -> NaiveDate {
    NaiveDate::from_ymd_opt(y, m, dd).unwrap()
}

fn now() -> DateTime<Utc> {
    Utc.with_ymd_and_hms(2026, 3, 15, 12, 0, 0).unwrap()
}

fn trade(tx_type: TransactionType, ticker: Option<&str>, qty: Option<&str>) -> Transaction {
    Transaction {
        id: 0,
        account_id: 1,
        date: day(2024, 1, 1),
        description: format!("{tx_type} {}", ticker.unwrap_or("?")),
        amount: Decimal::ZERO,
        kind: None,
        category: None,
        category_id: None,
        category_manual: false,
        counterparty: None,
        merchant_label: None,
        merchant_brand_id: None,
        ai_suggested_category_id: None,
        ai_confidence: None,
        external_id: None,
        recurring_series_id: None,
        native_currency: "EUR".to_owned(),
        created_at: now(),
        is_manual: true,
        budget_neutral: false,
        external_transaction_id: None,
        tx_type: Some(tx_type),
        ticker: ticker.map(str::to_owned),
        name: None,
        quantity: qty.map(d),
        price_per_unit: None,
        fees: None,
    }
}

fn buy(ticker: &str, qty: &str, price: Option<&str>) -> Transaction {
    Transaction {
        price_per_unit: price.map(d),
        ..trade(TransactionType::Buy, Some(ticker), Some(qty))
    }
}

fn buy_with_fees(ticker: &str, qty: &str, price: &str, fees: Option<&str>) -> Transaction {
    Transaction {
        fees: fees.map(d),
        ..buy(ticker, qty, Some(price))
    }
}

fn sell(ticker: &str, qty: &str) -> Transaction {
    trade(TransactionType::Sell, Some(ticker), Some(qty))
}

#[test]
fn buy_only_sets_quantity_and_average_buy_in() {
    let positions = net_positions(&[buy("AAPL", "10", Some("150.00"))]);
    assert_eq!(positions.len(), 1);
    let p = &positions["AAPL"];
    assert_eq!(p.quantity, d("10"));
    assert_eq!(p.average_buy_in.unwrap().to_string(), "150.00000000");
}

#[test]
fn multiple_buys_computes_vwap() {
    let positions = net_positions(&[
        buy("ETH", "10", Some("100.00")),
        buy("ETH", "20", Some("200.00")),
    ]);
    let p = &positions["ETH"];
    assert_eq!(p.quantity, d("30"));
    assert_eq!(p.average_buy_in.unwrap().to_string(), "166.66666667");
}

#[test]
fn buy_with_fees_folds_fees_into_average_buy_in() {
    let positions = net_positions(&[buy_with_fees("AAPL", "10", "100.00", Some("5.00"))]);
    assert_eq!(
        positions["AAPL"].average_buy_in.unwrap().to_string(),
        "100.50000000"
    );
}

#[test]
fn multiple_buys_with_fees_folds_all_fees_into_vwap() {
    let positions = net_positions(&[
        buy_with_fees("ETH", "10", "100.00", Some("5.00")),
        buy_with_fees("ETH", "10", "200.00", Some("5.00")),
    ]);
    let p = &positions["ETH"];
    assert_eq!(p.quantity, d("20"));
    assert_eq!(p.average_buy_in.unwrap().to_string(), "150.50000000");
}

#[test]
fn null_fees_treated_as_zero_in_vwap() {
    let positions = net_positions(&[buy_with_fees("BTC", "10", "100.00", None)]);
    assert_eq!(
        positions["BTC"].average_buy_in.unwrap().to_string(),
        "100.00000000"
    );
}

#[test]
fn buy_then_sell_reduces_quantity() {
    let positions = net_positions(&[buy("BTC", "5", Some("30000.00")), sell("BTC", "2")]);
    let p = &positions["BTC"];
    assert_eq!(p.quantity, d("3"));
    // The buy-in comes from the BUY rows only.
    assert_eq!(p.average_buy_in.unwrap().to_string(), "30000.00000000");
}

#[test]
fn null_ticker_skipped() {
    let mut blank = buy("X", "5", Some("100"));
    blank.ticker = Some("  ".to_owned());
    let positions = net_positions(&[trade(TransactionType::Buy, None, Some("5")), blank]);
    assert!(positions.is_empty());
}

#[test]
fn null_quantity_skipped() {
    let positions = net_positions(&[trade(TransactionType::Buy, Some("BTC"), None)]);
    assert!(positions.is_empty());
}

#[test]
fn multiple_tickers_handled_independently() {
    let positions = net_positions(&[
        buy("AAPL", "10", Some("150.00")),
        buy("MSFT", "5", Some("300.00")),
    ]);
    assert_eq!(positions.len(), 2);
    assert_eq!(positions["AAPL"].quantity, d("10"));
    assert_eq!(
        positions["AAPL"].average_buy_in.unwrap().to_string(),
        "150.00000000"
    );
    assert_eq!(positions["MSFT"].quantity, d("5"));
    assert_eq!(
        positions["MSFT"].average_buy_in.unwrap().to_string(),
        "300.00000000"
    );
}

#[test]
fn null_price_per_unit_treated_as_zero_for_vwap() {
    let positions = net_positions(&[buy("XRP", "10", None)]);
    assert_eq!(positions["XRP"].quantity, d("10"));
    assert_eq!(
        positions["XRP"].average_buy_in.unwrap().to_string(),
        "0.00000000"
    );
}

#[test]
fn position_name_uses_name_from_newest_transaction() {
    let older = Transaction {
        name: Some("iShares MSCI World (old label)".to_owned()),
        ..buy("IWDA.AS", "10", Some("80.00"))
    };
    let newer = Transaction {
        name: Some("iShares Core MSCI World UCITS ETF".to_owned()),
        date: day(2024, 3, 1),
        ..buy("IWDA.AS", "5", Some("90.00"))
    };
    let positions = net_positions(&[older, newer]);
    assert_eq!(
        positions["IWDA.AS"].name.as_deref(),
        Some("iShares Core MSCI World UCITS ETF")
    );
}

async fn pea(app: &TestApp) -> i64 {
    let (_, member) = app.create_member("alice", "pw-alice-123").await;
    accounts::insert(
        &app.state.db,
        &NewAccount::new(member, "PEA", AccountType::Pea),
        now(),
    )
    .await
    .unwrap()
    .id
}

async fn record(app: &TestApp, account_id: i64, tx: Transaction) {
    let mut row = NewTransaction::new(account_id, tx.date, tx.description, tx.amount);
    row.is_manual = true;
    row.tx_type = tx.tx_type;
    row.ticker = tx.ticker;
    row.name = tx.name;
    row.quantity = tx.quantity;
    row.price_per_unit = tx.price_per_unit;
    row.fees = tx.fees;
    transactions::insert(&app.state.db, &row, now())
        .await
        .unwrap();
}

async fn hold(app: &TestApp, account_id: i64, ticker: &str, name: Option<&str>, qty: &str) -> i64 {
    let draft = HoldingDraft {
        ticker: ticker.to_owned(),
        name: name.map(str::to_owned),
        quantity: d(qty),
        average_buy_in: Some(d("300.00")),
        current_price: Some(d("410")),
        ..HoldingDraft::default()
    };
    holdings::insert(&app.state.db, account_id, &draft, now())
        .await
        .unwrap()
        .id
}

#[tokio::test]
async fn existing_holding_updated_in_place() {
    let app = TestApp::with_admin().await;
    let account = pea(&app).await;
    let id = hold(&app, account, "NVDA", None, "1").await;
    record(&app, account, buy("NVDA", "3", Some("400.00"))).await;

    let mut conn = app.state.db.acquire().await.unwrap();
    recompute_holdings(&mut conn, account, now()).await.unwrap();

    let saved = holdings::find_by_account(&app.state.db, account)
        .await
        .unwrap();
    assert_eq!(saved.len(), 1);
    assert_eq!(saved[0].id, id);
    assert_eq!(saved[0].quantity, d("3"));
    assert_eq!(saved[0].average_buy_in.unwrap().to_string(), "400.00000000");
    // Columns the trades do not drive survive the update.
    assert_eq!(saved[0].current_price, Some(d("410")));
}

#[tokio::test]
async fn fully_sold_deletes_holding() {
    let app = TestApp::with_admin().await;
    let account = pea(&app).await;
    hold(&app, account, "SOL", None, "10").await;
    // A holding no trade mentions is left alone.
    hold(&app, account, "SYNCED", None, "2").await;
    record(&app, account, buy("SOL", "10", Some("50.00"))).await;
    record(
        &app,
        account,
        Transaction {
            date: day(2024, 6, 1),
            ..sell("SOL", "10")
        },
    )
    .await;

    let mut conn = app.state.db.acquire().await.unwrap();
    recompute_holdings(&mut conn, account, now()).await.unwrap();

    let saved = holdings::find_by_account(&app.state.db, account)
        .await
        .unwrap();
    let tickers: Vec<&str> = saved.iter().map(|h| h.ticker.as_str()).collect();
    assert_eq!(tickers, ["SYNCED"]);
}

#[tokio::test]
async fn new_position_is_created_from_the_trades() {
    let app = TestApp::with_admin().await;
    let account = pea(&app).await;
    record(&app, account, buy("AAPL", "10", Some("150.00"))).await;
    record(&app, account, trade(TransactionType::Buy, None, Some("5"))).await;

    let mut conn = app.state.db.acquire().await.unwrap();
    recompute_holdings(&mut conn, account, now()).await.unwrap();

    let saved = holdings::find_by_account(&app.state.db, account)
        .await
        .unwrap();
    assert_eq!(saved.len(), 1);
    assert_eq!(saved[0].ticker, "AAPL");
    assert_eq!(saved[0].quantity, d("10"));
    assert_eq!(saved[0].average_buy_in, Some(d("150")));
    assert_eq!(saved[0].current_price, None);
}

#[tokio::test]
async fn position_name_preserves_existing_name_when_transactions_have_none() {
    let app = TestApp::with_admin().await;
    let account = pea(&app).await;
    hold(
        &app,
        account,
        "VWCE.DE",
        Some("Vanguard FTSE All-World UCITS ETF"),
        "1",
    )
    .await;
    record(&app, account, buy("VWCE.DE", "4", Some("100.00"))).await;

    let mut conn = app.state.db.acquire().await.unwrap();
    recompute_holdings(&mut conn, account, now()).await.unwrap();

    let saved = holdings::find_by_account(&app.state.db, account)
        .await
        .unwrap();
    assert_eq!(
        saved[0].name.as_deref(),
        Some("Vanguard FTSE All-World UCITS ETF")
    );
    assert_eq!(saved[0].quantity, d("4"));
}
