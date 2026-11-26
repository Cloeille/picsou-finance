//! Rewriting history valued through a wrong ticker (#705): the #683 PEA's past snapshots are
//! recomputed from LU1829219390's daily prices, a second run changes nothing, and snapshots
//! entered by hand are left alone.

use picsou::market::runners::{SqlAppSettings, rewrite_isin_history};
use picsou::testing::TestApp;
use rust_decimal::Decimal;

const LU: &str = "LU1829219390";

fn d(value: &str) -> Decimal {
    value.parse().unwrap()
}

async fn sql(app: &TestApp, statement: &str) {
    app.state.db.execute_batch(statement).await.unwrap();
}

/// The #683 PEA: 80 units of LU1829219390 still carrying the grey-market ticker MULYF, 100 cash.
/// Days 10 and 11 were valued through MULYF (80 x 376.28 + 100 = 30 202.40), day 12 by the bank
/// (453.08), and day 13 holds a statement typed by hand at the wrong figure.
async fn pea_683(app: &TestApp) -> i64 {
    let pea: i64 = picsou::db::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, cash_balance, \
         is_manual, provider, color, created_at, updated_at) \
         SELECT member_id, 'PEA', 'PEA', 'EUR', 453.08, 100, false, 'BoursoBank', '#000000', now(), \
         now() FROM app_user WHERE username = 'admin' RETURNING id",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    sql(
        app,
        &format!(
            "INSERT INTO account_holding (account_id, ticker, isin, quantity, provider_value_eur, \
             created_at, updated_at) VALUES ({pea}, 'MULYF', '{LU}', 80, 353.08, now(), now());\
             INSERT INTO price_snapshot (ticker, date, price_eur) VALUES \
             ('MULYF', '2026-01-10', 376.28), ('MULYF', '2026-01-11', 376.28), \
             ('MULYF', '2026-01-12', 376.28), ('MULYF', '2026-01-13', 376.28), \
             ('{LU}', '2026-01-10', 4.40), ('{LU}', '2026-01-11', 4.42), \
             ('{LU}', '2026-01-12', 4.41), ('{LU}', '2026-01-13', 4.41);\
             INSERT INTO balance_snapshot (account_id, date, balance, invested_amount, source) VALUES \
             ({pea}, '2026-01-10', 30202.40, 404.65, 'DERIVED'), \
             ({pea}, '2026-01-11', 30202.40, 404.65, 'DERIVED'), \
             ({pea}, '2026-01-12', 453.08, 404.65, 'DERIVED'), \
             ({pea}, '2026-01-13', 30202.40, 404.65, 'MANUAL');"
        ),
    )
    .await;
    pea
}

async fn balances(app: &TestApp, account: i64) -> Vec<(String, Decimal)> {
    picsou::db::query_as(
        "SELECT date::text, balance FROM balance_snapshot WHERE account_id = $1 ORDER BY date",
    )
    .bind(account)
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn the_683_pea_is_recomputed_from_its_isin_prices_once_and_manual_rows_are_kept() {
    let app = TestApp::with_admin().await;
    let pea = pea_683(&app).await;
    let settings = SqlAppSettings(app.state.db.clone());

    assert_eq!(
        rewrite_isin_history(&app.state, &settings).await.unwrap(),
        2
    );

    let expected = [
        // 80 x 4.40 + 100 and 80 x 4.42 + 100.
        ("2026-01-10".to_owned(), d("452.00")),
        ("2026-01-11".to_owned(), d("453.60")),
        // Valued by the bank already: smaller than the MULYF contribution, left alone.
        ("2026-01-12".to_owned(), d("453.08")),
        // Typed by hand: never rewritten.
        ("2026-01-13".to_owned(), d("30202.40")),
    ];
    let got: Vec<(String, Decimal)> = balances(&app, pea)
        .await
        .into_iter()
        .map(|(day, b)| (day, b.normalize()))
        .collect();
    let want: Vec<(String, Decimal)> = expected
        .iter()
        .map(|(day, b)| (day.clone(), b.normalize()))
        .collect();
    assert_eq!(got, want);

    assert_eq!(
        rewrite_isin_history(&app.state, &settings).await.unwrap(),
        0
    );
    // The rule itself leaves corrected days alone, not only the done flag.
    sql(
        &app,
        "DELETE FROM app_setting WHERE setting_key LIKE 'isin_history.rewrite_done.%'",
    )
    .await;
    assert_eq!(
        rewrite_isin_history(&app.state, &settings).await.unwrap(),
        0
    );
    let again: Vec<(String, Decimal)> = balances(&app, pea)
        .await
        .into_iter()
        .map(|(day, b)| (day, b.normalize()))
        .collect();
    assert_eq!(again, want);
}

#[tokio::test]
async fn an_account_with_no_recorded_isin_history_is_retried_at_the_next_run() {
    let app = TestApp::with_admin().await;
    let pea = pea_683(&app).await;
    sql(
        &app,
        &format!("DELETE FROM price_snapshot WHERE ticker = '{LU}'"),
    )
    .await;
    let settings = SqlAppSettings(app.state.db.clone());

    assert_eq!(
        rewrite_isin_history(&app.state, &settings).await.unwrap(),
        0
    );
    sql(
        &app,
        &format!("INSERT INTO price_snapshot (ticker, date, price_eur) VALUES ('{LU}', '2026-01-10', 4.40)"),
    )
    .await;

    assert_eq!(
        rewrite_isin_history(&app.state, &settings).await.unwrap(),
        1
    );
    assert_eq!(balances(&app, pea).await[0].1.normalize(), d("452"));
}

#[tokio::test]
async fn a_ticker_price_that_agrees_with_the_isin_is_left_alone() {
    let app = TestApp::with_admin().await;
    let pea = pea_683(&app).await;
    // CW8-style line: the ticker names the right listing, within the factor of two.
    sql(
        &app,
        &format!(
            "UPDATE price_snapshot SET price_eur = 4.50 WHERE ticker = 'MULYF';\
             UPDATE balance_snapshot SET balance = 460 WHERE account_id = {pea} AND source = 'DERIVED'"
        ),
    )
    .await;
    let settings = SqlAppSettings(app.state.db.clone());

    assert_eq!(
        rewrite_isin_history(&app.state, &settings).await.unwrap(),
        0
    );
}

#[tokio::test]
async fn a_day_already_valued_by_isin_is_left_alone_when_the_ticker_was_far_below() {
    let app = TestApp::with_admin().await;
    let pea = pea_683(&app).await;
    // A penny listing: the ticker priced 0.01, so the right days hold the line at its ISIN price.
    sql(
        &app,
        &format!(
            "UPDATE price_snapshot SET price_eur = 0.01 WHERE ticker = 'MULYF';\
             UPDATE balance_snapshot SET balance = 452 WHERE account_id = {pea} AND source = 'DERIVED'"
        ),
    )
    .await;
    let settings = SqlAppSettings(app.state.db.clone());

    assert_eq!(
        rewrite_isin_history(&app.state, &settings).await.unwrap(),
        0
    );
    assert!(
        balances(&app, pea).await[..3]
            .iter()
            .all(|(_, b)| b.normalize() == d("452"))
    );
}

#[tokio::test]
async fn a_never_market_priced_account_is_left_alone() {
    let app = TestApp::with_admin().await;
    let pea = pea_683(&app).await;
    sql(
        &app,
        &format!(
            "UPDATE account SET provider = '{}' WHERE id = {pea}",
            picsou::ledger::providers::NATIXIS
        ),
    )
    .await;
    let settings = SqlAppSettings(app.state.db.clone());

    assert_eq!(
        rewrite_isin_history(&app.state, &settings).await.unwrap(),
        0
    );
    assert_eq!(balances(&app, pea).await[0].1.normalize(), d("30202.4"));
}

#[tokio::test]
async fn a_later_change_of_the_account_does_not_sway_the_rewrite() {
    let app = TestApp::with_admin().await;
    let pea = pea_683(&app).await;
    // 60 000 EUR of cash paid in since: today's account no longer looks like those days.
    sql(
        &app,
        &format!(
            "UPDATE account SET cash_balance = 60000, current_balance = 60352.8 WHERE id = {pea}"
        ),
    )
    .await;
    let settings = SqlAppSettings(app.state.db.clone());

    assert_eq!(
        rewrite_isin_history(&app.state, &settings).await.unwrap(),
        2
    );
    let days: Vec<Decimal> = balances(&app, pea)
        .await
        .into_iter()
        .map(|(_, b)| b.normalize())
        .collect();
    assert_eq!(days, vec![d("452"), d("453.6"), d("453.08"), d("30202.4")]);
}

/// A right day with an ordinary gain or loss is never moved, whichever way the ticker was off:
/// 80 x 4.40 + 100 = 452 against costs that put it at +20% and at -20%.
#[tokio::test]
async fn a_right_day_with_an_ordinary_gain_or_loss_is_left_alone() {
    for (ticker_price, invested) in [("12", "376.67"), ("0.5", "565")] {
        let app = TestApp::with_admin().await;
        let pea = pea_683(&app).await;
        sql(
            &app,
            &format!(
                "UPDATE price_snapshot SET price_eur = {ticker_price} WHERE ticker = 'MULYF';\
                 UPDATE balance_snapshot SET balance = 452, invested_amount = {invested} \
                 WHERE account_id = {pea} AND source = 'DERIVED'"
            ),
        )
        .await;
        let settings = SqlAppSettings(app.state.db.clone());

        assert_eq!(
            rewrite_isin_history(&app.state, &settings).await.unwrap(),
            0,
            "{ticker_price}"
        );
    }
}
