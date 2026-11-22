//! RecordedPrices, the database-only PriceRead: what PriceService answers with every provider down.

use std::collections::BTreeSet;

use picsou::ledger::prices::{PriceRead, RecordedPrices};
use picsou::testing::TestApp;

use super::support::{d, day, today};

async fn record(app: &TestApp, ticker: &str, date: &str, price: &str) {
    picsou::db::query("INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ($1, $2, $3, now())")
        .bind(ticker)
        .bind(day(date))
        .bind(d(price))
        .execute(&app.state.db)
        .await
        .expect("price");
}

fn set(tickers: &[&str]) -> BTreeSet<String> {
    tickers.iter().map(|t| (*t).to_owned()).collect()
}

#[tokio::test]
async fn a_recorded_price_within_seven_days_stands_in_as_stale_and_an_older_one_does_not() {
    let app = TestApp::spawn().await;
    record(&app, "CW8.PA", "2026-03-10", "500.5").await;
    record(&app, "CW8.PA", "2026-03-12", "510.25").await;
    record(&app, "OLD.PA", "2026-03-07", "10").await;
    let prices = RecordedPrices::new(app.state.db.clone());

    let quotes = prices
        .quotes(&set(&["cw8.pa", "OLD.PA", "EUR", " "]), false, today())
        .await;
    let cw8 = quotes["CW8.PA"];
    assert_eq!(cw8.price, d("510.25"));
    assert_eq!(cw8.as_of, day("2026-03-12"));
    assert!(!cw8.live);
    assert!(!quotes.contains_key("OLD.PA"));
    assert_eq!(quotes["EUR"].price, d("1"));
    assert!(quotes["EUR"].live);
    assert_eq!(quotes.len(), 2);
}

#[tokio::test]
async fn a_crypto_only_read_prices_registered_coins_only() {
    let app = TestApp::spawn().await;
    picsou::db::query("DELETE FROM crypto_asset WHERE upper(ticker) IN ('BTC', 'REG')")
        .execute(&app.state.db)
        .await
        .unwrap();
    picsou::db::query(
        "INSERT INTO crypto_asset (ticker, coingecko_id, name, source, resolved_at) \
         VALUES ('BTC', 'bitcoin', 'Bitcoin', 'CURATED', now())",
    )
    .execute(&app.state.db)
    .await
    .unwrap();
    record(&app, "BTC", "2026-03-14", "60000").await;
    record(&app, "REG", "2026-03-14", "45").await;
    let prices = RecordedPrices::new(app.state.db.clone());

    let crypto = prices.quotes(&set(&["btc", "REG"]), true, today()).await;
    assert_eq!(crypto["BTC"].price, d("60000"));
    assert!(
        !crypto.contains_key("REG"),
        "an unmapped symbol never reads a stock's row"
    );

    let mixed = prices.quotes(&set(&["REG"]), false, today()).await;
    assert_eq!(mixed["REG"].price, d("45"));
}

#[tokio::test]
async fn to_eur_converts_by_ticker_and_returns_cash_unconverted_without_a_rate() {
    let app = TestApp::spawn().await;
    record(&app, "BTC-EUR", "2026-03-14", "60000").await;
    let prices = RecordedPrices::new(app.state.db.clone());

    assert_eq!(
        prices
            .to_eur(Some(d("0.5")), "EUR", Some("BTC-EUR"), today())
            .await,
        d("30000")
    );
    assert_eq!(
        prices.to_eur(Some(d("100")), "USD", None, today()).await,
        d("100")
    );
    assert_eq!(
        prices.to_eur(Some(d("100")), "eur", None, today()).await,
        d("100")
    );
    assert_eq!(prices.to_eur(None, "EUR", None, today()).await, d("0"));
    assert_eq!(
        prices
            .to_eur(Some(d("3")), "EUR", Some("NOPE"), today())
            .await,
        d("3")
    );
}
