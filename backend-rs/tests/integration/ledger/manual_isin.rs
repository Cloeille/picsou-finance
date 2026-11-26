//! Manual entry and CSV imports keep the ISIN (#703): a trade entered by ISIN records it against
//! the ticker it resolved to, its line carries it and is priced by it, and manual lines stored
//! under a ticker with a known ISIN are linked to it.

use picsou::market::runners::link_manual_holding_isins;
use picsou::testing::TestApp;
use serde_json::json;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const WORLD: &str = "IE000BI8OT95";

async fn openfigi_resolves_world_to_mwrd() -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v3/mapping"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([
            {"data": [{"ticker": "MWRD", "exchCode": "FP", "name": "AMUNDI CORE MSCI WORLD"}]}
        ])))
        .mount(&server)
        .await;
    server
}

/// justETF quotes the ISIN at 125 EUR; Yahoo is never stubbed, so a ticker price is never found.
async fn justetf_quotes_world_at_125() -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path(format!("/api/etfs/{WORLD}/quote")))
        .respond_with(ResponseTemplate::new(200).set_body_raw(
            r#"{"latestQuote":{"raw":125.0,"localized":"125.00"},"latestQuoteDate":"2026-01-14"}"#,
            "application/json",
        ))
        .mount(&server)
        .await;
    server
}

async fn manual_account(app: &TestApp, kind: &str) -> i64 {
    picsou::db::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color, \
         created_at, updated_at) SELECT member_id, 'Manual', $1::account_type, 'EUR', 0, true, \
         '#000000', now(), now() FROM app_user WHERE username = 'admin' RETURNING id",
    )
    .bind(kind)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

async fn lines(app: &TestApp, account: i64) -> Vec<(String, Option<String>)> {
    picsou::db::query_as("SELECT ticker, isin FROM account_holding WHERE account_id = $1")
        .bind(account)
        .fetch_all(&app.state.db)
        .await
        .unwrap()
}

#[tokio::test]
async fn a_manual_buy_entered_by_isin_keeps_it_and_is_priced_by_it() {
    let (openfigi, justetf) = (
        openfigi_resolves_world_to_mwrd().await,
        justetf_quotes_world_at_125().await,
    );
    let app = TestApp::builder()
        .with_admin()
        .env("OPENFIGI_BASE_URL", openfigi.uri())
        .env("JUSTETF_BASE_URL", justetf.uri())
        .spawn()
        .await;
    let client = app.login_admin().await;
    let pea = manual_account(&app, "PEA").await;

    let response = client
        .post(&format!("/api/accounts/{pea}/transactions"))
        .json(&json!({
            "date": "2026-01-10", "description": "Achat", "amount": -1000, "txType": "BUY",
            "ticker": "ie000bi8ot95", "quantity": 8, "pricePerUnit": 125
        }))
        .send()
        .await;
    assert_eq!(response.status, 201, "{}", response.text());

    assert_eq!(
        lines(&app, pea).await,
        vec![("MWRD.PA".to_owned(), Some(WORLD.to_owned()))]
    );
    let holdings = client
        .get(&format!("/api/accounts/{pea}/holdings"))
        .send()
        .await
        .json();
    // 8 x 125 from justETF by ISIN: no Yahoo price exists for MWRD.PA.
    let value: rust_decimal::Decimal = holdings[0]["currentValueEur"].to_string().parse().unwrap();
    assert_eq!(value, rust_decimal::Decimal::from(1000));
    assert_eq!(holdings[0]["priceStale"], false);
}

#[tokio::test]
async fn a_csv_row_entered_by_isin_keeps_it_on_its_line() {
    let openfigi = openfigi_resolves_world_to_mwrd().await;
    let app = TestApp::builder()
        .with_admin()
        .env("OPENFIGI_BASE_URL", openfigi.uri())
        .spawn()
        .await;
    let client = app.login_admin().await;
    let cto = manual_account(&app, "COMPTE_TITRES").await;
    let csv = format!("Date;Sens;Ticker;Quantité;Prix\n10/01/2026;ACHAT;{WORLD};8;125\n");
    let (content_type, body) =
        crate::transfer::support::file("ordres.csv", "text/csv", csv.as_bytes());
    let preview = client
        .post(&format!("/api/accounts/{cto}/transactions/import/preview"))
        .body(&content_type, body)
        .allow_decimal_strings()
        .send()
        .await
        .json();

    let response = client
        .post(&format!("/api/accounts/{cto}/transactions/import"))
        .json(&json!({
            "fileToken": preview["fileToken"],
            "mapping": {"date": 0, "side": 1, "tickerOrIsin": 2, "quantity": 3, "unitPrice": 4},
            "dialect": preview["dialect"],
            "hasHeaderRow": true,
            "feesIncludedInAmount": false,
            "sideValueMap": {"ACHAT": "BUY"},
        }))
        .send()
        .await;
    assert_eq!(response.status, 201, "{}", response.text());

    assert_eq!(
        lines(&app, cto).await,
        vec![("MWRD.PA".to_owned(), Some(WORLD.to_owned()))]
    );
}

#[tokio::test]
async fn existing_manual_trades_under_a_ticker_with_a_known_isin_are_linked_to_it() {
    let app = TestApp::with_admin().await;
    let pea = manual_account(&app, "PEA").await;
    let crypto = manual_account(&app, "CRYPTO").await;
    picsou::db::query(
        "INSERT INTO security_profile (ticker, asset_type, isin, status, created_at, updated_at) \
         VALUES ('MWRD.PA', 'ETF', $1, 'OK', now(), now()), \
         ('BTC', 'UNKNOWN', 'US0000000001', 'OK', now(), now())",
    )
    .bind(WORLD)
    .execute(&app.state.db)
    .await
    .unwrap();
    for (account, ticker) in [(pea, "MWRD.PA"), (pea, "AAPL"), (crypto, "BTC")] {
        picsou::db::query(
            "INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) \
             VALUES ($1, $2, 1, now(), now())",
        )
        .bind(account)
        .bind(ticker)
        .execute(&app.state.db)
        .await
        .unwrap();
    }

    assert_eq!(link_manual_holding_isins(&app.state).await.unwrap(), 1);
    assert_eq!(link_manual_holding_isins(&app.state).await.unwrap(), 0);

    let mut pea_lines = lines(&app, pea).await;
    pea_lines.sort();
    assert_eq!(
        pea_lines,
        vec![
            ("AAPL".to_owned(), None),
            ("MWRD.PA".to_owned(), Some(WORLD.to_owned())),
        ]
    );
    assert_eq!(lines(&app, crypto).await, vec![("BTC".to_owned(), None)]);
}

#[tokio::test]
async fn an_isin_openfigi_cannot_resolve_stays_the_ticker_and_the_line_keeps_it() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let pea = manual_account(&app, "PEA").await;

    let response = client
        .post(&format!("/api/accounts/{pea}/transactions"))
        .json(&json!({
            "date": "2026-01-10", "description": "Achat", "amount": -1000, "txType": "BUY",
            "ticker": WORLD, "quantity": 8, "pricePerUnit": 125
        }))
        .send()
        .await;
    assert_eq!(response.status, 201, "{}", response.text());

    assert_eq!(
        lines(&app, pea).await,
        vec![(WORLD.to_owned(), Some(WORLD.to_owned()))]
    );
}

#[tokio::test]
async fn a_crypto_account_never_takes_an_isin() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let wallet = manual_account(&app, "CRYPTO").await;

    let response = client
        .post(&format!("/api/accounts/{wallet}/transactions"))
        .json(&json!({
            "date": "2026-01-10", "description": "Achat", "amount": -1000, "txType": "BUY",
            "ticker": WORLD, "quantity": 8, "pricePerUnit": 125
        }))
        .send()
        .await;
    assert_eq!(response.status, 201, "{}", response.text());

    assert_eq!(lines(&app, wallet).await, vec![(WORLD.to_owned(), None)]);
}
