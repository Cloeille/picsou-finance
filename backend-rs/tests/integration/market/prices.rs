//! The price service and /api/prices: Yahoo and CoinGecko stubbed, cache, fallback, snapshots.

use std::collections::BTreeSet;

use picsou::testing::TestApp;
use rust_decimal::Decimal;
use serde_json::json;
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

fn d(value: &str) -> Decimal {
    value.parse().unwrap()
}

fn chart(currency: &str, price: f64) -> serde_json::Value {
    json!({"chart": {"result": [{
        "meta": {"currency": currency, "regularMarketPrice": price},
        "timestamp": [1768471200],
        "indicators": {"quote": [{"close": [price]}]}
    }], "error": null}})
}

async fn app(yahoo: &MockServer, coingecko: &MockServer) -> TestApp {
    TestApp::builder()
        .with_admin()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .env("COINGECKO_BASE_URL", coingecko.uri())
        .spawn()
        .await
}

async fn stub_chart(server: &MockServer, symbol: &str, currency: &str, price: f64, times: u64) {
    Mock::given(method("GET"))
        .and(path(format!("/v8/finance/chart/{symbol}")))
        .respond_with(ResponseTemplate::new(200).set_body_json(chart(currency, price)))
        .expect(times)
        .mount(server)
        .await;
}

#[tokio::test]
async fn get_prices_never_asks_yahoo_for_a_cmes_or_natixis_fund_code() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    stub_chart(&yahoo, "CMES-1613", "EUR", 10.0, 0).await;
    stub_chart(&yahoo, "NATIXIS-42", "EUR", 10.0, 0).await;
    let app = app(&yahoo, &coingecko).await;
    let admin = app.login_admin().await;

    // The holdings page asks live prices for every line, these fund codes included.
    let response = admin
        .get("/api/prices?tickers=CMES-1613,natixis-42")
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    assert_eq!(response.json(), json!({}));
}

#[tokio::test]
async fn get_prices_converts_to_eur_caches_and_records_todays_price() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    stub_chart(&yahoo, "AAPL", "USD", 100.0, 1).await;
    stub_chart(&yahoo, "USDEUR=X", "EUR", 0.9, 1).await;
    let app = app(&yahoo, &coingecko).await;
    let admin = app.login_admin().await;

    let response = admin.get("/api/prices?tickers=aapl,%20EUR,,").send().await;
    assert_eq!(response.status, 200, "{}", response.text());
    let body = response.json();
    assert_eq!(d(&body["AAPL"].to_string()), d("90"));
    assert_eq!(body["EUR"].to_string(), "1");

    // Served from the cache: the stubs expect one call each.
    let again = admin.get("/api/prices?tickers=AAPL").send().await.json();
    assert_eq!(d(&again["AAPL"].to_string()), d("90"));

    let recorded: Vec<(String, String)> =
        picsou::db::query_as("SELECT ticker, price_eur::text FROM price_snapshot ORDER BY ticker")
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(recorded.len(), 1);
    assert_eq!(recorded[0].0, "AAPL");
    assert_eq!(d(&recorded[0].1), d("90"));

    let history = admin.get("/api/prices/aapl/history").send().await.json();
    assert_eq!(history[0]["date"], "2026-01-15");
    assert_eq!(d(&history[0]["priceEur"].to_string()), d("90"));
}

#[tokio::test]
async fn get_prices_requires_its_tickers() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    let app = app(&yahoo, &coingecko).await;
    let admin = app.login_admin().await;
    let response = admin.get("/api/prices").send().await;
    assert_eq!(response.status, 400);
    assert_eq!(
        response.json()["detail"],
        "Required parameter 'tickers' is not present."
    );
    let response = admin.get("/api/prices/AAPL/history?months=x").send().await;
    assert_eq!(response.status, 400);
}

#[tokio::test]
async fn a_provider_failure_falls_back_to_the_last_price_of_the_week() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&yahoo)
        .await;
    let app = app(&yahoo, &coingecko).await;
    for (ticker, date, price) in [("CW8", "2026-01-12", "500.5"), ("OLD", "2026-01-01", "10")] {
        picsou::db::query("INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ($1, $2::date, $3::numeric, now())")
            .bind(ticker)
            .bind(date)
            .bind(price)
            .execute(&app.state.db)
            .await
            .unwrap();
    }
    let today = app.state.clock.today();
    let tickers = BTreeSet::from(["CW8".to_owned(), "OLD".to_owned(), "EUR".to_owned()]);
    let quotes = app.state.ledger.prices.quotes(&tickers, false, today).await;
    let cw8 = quotes["CW8"];
    assert!(!cw8.live);
    assert_eq!(cw8.price, d("500.5"));
    assert_eq!(cw8.as_of.to_string(), "2026-01-12");
    assert!(!quotes.contains_key("OLD"), "older than seven days");
    assert!(quotes["EUR"].live);
    assert_eq!(app.state.ledger.prices.fx_rate_eur("USD").await, None);
}

#[tokio::test]
async fn crypto_quotes_come_from_coingecko_and_never_from_an_equity_source() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/simple/price"))
        .and(query_param("vs_currencies", "eur"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_raw(r#"{"bitcoin":{"eur":50000.12}}"#, "application/json"),
        )
        .mount(&coingecko)
        .await;
    let app = app(&yahoo, &coingecko).await;
    picsou::db::query(
        "INSERT INTO crypto_asset (ticker, coingecko_id, name, source) VALUES ('BTC', 'bitcoin', 'Bitcoin', 'CURATED') \
         ON CONFLICT (ticker) DO UPDATE SET coingecko_id = 'bitcoin'",
    )
    .execute(&app.state.db)
    .await
    .unwrap();
    let today = app.state.clock.today();
    let tickers = BTreeSet::from(["BTC".to_owned(), "AAPL".to_owned()]);
    let quotes = app.state.ledger.prices.quotes(&tickers, true, today).await;
    assert_eq!(quotes.len(), 1);
    assert_eq!(quotes["BTC"].price.to_string(), "50000.12");
    assert!(yahoo.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn a_coingecko_429_pauses_the_next_calls() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(429).insert_header("Retry-After", "120"))
        .expect(1)
        .mount(&coingecko)
        .await;
    let app = app(&yahoo, &coingecko).await;
    picsou::db::query(
        "INSERT INTO crypto_asset (ticker, coingecko_id, name, source) VALUES ('ZZZ', 'zzz-coin', 'Zzz', 'AUTO') ON CONFLICT DO NOTHING",
    )
    .execute(&app.state.db)
    .await
    .unwrap();
    let admin = app.login_admin().await;
    let first = admin.get("/api/prices?tickers=ZZZ").send().await.json();
    assert_eq!(first, json!({}));
    app.clock.advance(std::time::Duration::from_secs(90));
    let second = admin.get("/api/prices?tickers=ZZZ").send().await.json();
    assert_eq!(second, json!({}));
}

#[tokio::test]
async fn gold_is_priced_per_gram_from_the_future_and_coins_from_the_quote_page() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    let loomis = MockServer::start().await;
    stub_chart(&yahoo, "GC=F", "EUR", 3110.34768, 1).await;
    Mock::given(method("GET"))
        .and(path("/fr/or/cotation-produits/"))
        .respond_with(ResponseTemplate::new(200).set_body_string(
            r#"<div class="raterow"><a href="/produit/20-francs-napoleon/">Napoléon</a>
               <span>Cotation du <span class="d">14/01/2026</span></span><div class="col col_price">1&nbsp;234,56 €</div></div>
               <div class="raterow"><a href="/produit/krugerrand-sud-afrique/">Kr</a>
               <span>Cotation du <span>01/01/2025</span></span><div class="col col_price">2 900,00 €</div></div>"#,
        ))
        .mount(&loomis)
        .await;
    let app = TestApp::builder()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .env("COINGECKO_BASE_URL", coingecko.uri())
        .env("PRECIOUS_METALS_BASE_URL", loomis.uri())
        .spawn()
        .await;
    let today = app.state.clock.today();
    let tickers = BTreeSet::from([
        "GOLD:GRAM".to_owned(),
        "gold:napoleon-20f".to_owned(),
        "GOLD:KRUGERRAND".to_owned(),
    ]);
    let quotes = app.state.ledger.prices.quotes(&tickers, false, today).await;
    assert_eq!(quotes["GOLD:GRAM"].price.to_string(), "100.00000000");
    assert_eq!(quotes["GOLD:NAPOLEON-20F"].price.to_string(), "1234.56");
    assert!(
        !quotes.contains_key("GOLD:KRUGERRAND"),
        "a quote older than a week is ignored"
    );
}

#[tokio::test]
async fn the_precious_metals_catalogue_is_listed_in_registry_order() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    let app = app(&yahoo, &coingecko).await;
    let body = app
        .login_admin()
        .await
        .get("/api/prices/precious-metals")
        .send()
        .await
        .json();
    assert_eq!(body.as_array().unwrap().len(), 16);
    assert_eq!(
        body[0],
        json!({"ticker": "GOLD:GRAM", "name": "Gold (fine gram)", "unit": "GRAM"})
    );
}

#[tokio::test]
async fn precious_metals_intraday_holds_coin_quote_flat_and_parses_french_amounts() {
    use chrono::NaiveDate;
    use picsou::market::metals::{parse_french_amount, parse_quotes};

    assert_eq!(
        parse_french_amount(" 3&nbsp;875,00&nbsp;€ ")
            .unwrap()
            .to_string(),
        "3875.00"
    );
    assert_eq!(
        parse_french_amount("3\u{202F}875,5 €").unwrap().to_string(),
        "3875.5"
    );
    assert!(parse_french_amount("Cotation suspendue").is_none());
    assert!(parse_french_amount("0,00 €").is_none());

    let html = r#"<div class="raterow"><a href="/produit/20-francs-napoleon/">N</a>
        <span>Cotation du <span>05/11/2026</span></span><div class="col col_price">709,90&nbsp;€</div></div>
        <div class="raterow"><a href="/produit/souverain/">S</a>
        <span>Cotation du <span>n/a</span></span><div class="col col_price">550,00 €</div></div>"#;
    assert!(
        parse_quotes(html, NaiveDate::from_ymd_opt(2026, 11, 12).unwrap())
            .contains_key("20-francs-napoleon")
    );
    assert!(
        !parse_quotes(html, NaiveDate::from_ymd_opt(2026, 11, 13).unwrap())
            .contains_key("20-francs-napoleon")
    );
    assert!(
        parse_quotes(html, NaiveDate::from_ymd_opt(2026, 11, 13).unwrap())
            .contains_key("souverain"),
        "a row without a readable date keeps its quote"
    );

    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    let loomis = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/fr/or/cotation-produits/"))
        .respond_with(ResponseTemplate::new(200).set_body_string(
            r#"<div class="raterow"><a href="/produit/20-francs-napoleon/">N</a>
               <span>Cotation du <span>14/01/2026</span></span><div class="col col_price">709,90&nbsp;€</div></div>"#,
        ))
        .mount(&loomis)
        .await;
    let app = TestApp::builder()
        .with_admin()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .env("COINGECKO_BASE_URL", coingecko.uri())
        .env("PRECIOUS_METALS_BASE_URL", loomis.uri())
        .spawn()
        .await;
    let admin = app.login_admin().await;
    let points = admin
        .get("/api/prices/GOLD:NAPOLEON-20F/intraday")
        .send()
        .await
        .json();
    let arr = points.as_array().unwrap();
    assert_eq!(arr.len(), 25);
    assert_eq!(arr[0]["priceEur"].to_string(), "709.90");
    assert_eq!(arr[24]["priceEur"].to_string(), "709.90");
}

#[tokio::test]
async fn coingecko_serves_history_intraday_and_logo_urls() {
    use chrono::NaiveDate;

    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    let app = app(&yahoo, &coingecko).await;
    let t1 = chrono::TimeZone::with_ymd_and_hms(&chrono::Utc, 2026, 1, 14, 12, 0, 0)
        .unwrap()
        .timestamp_millis();
    let t2 = chrono::TimeZone::with_ymd_and_hms(&chrono::Utc, 2026, 1, 15, 8, 0, 0)
        .unwrap()
        .timestamp_millis();
    let t_oob = chrono::TimeZone::with_ymd_and_hms(&chrono::Utc, 2026, 1, 1, 0, 0, 0)
        .unwrap()
        .timestamp_millis();
    Mock::given(method("GET"))
        .and(path("/coins/bitcoin/market_chart/range"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "prices": [
                [t_oob, 80000.0],
                [t1, 91234.567891234],
                [t2, 92000.5],
                [t2, -5.0],
                ["bad", 100.0]
            ]
        })))
        .mount(&coingecko)
        .await;
    Mock::given(method("GET"))
        .and(path("/coins/markets"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([
            {"id": "bitcoin", "image": "https://coin-images.coingecko.com/btc.png"}
        ])))
        .mount(&coingecko)
        .await;

    let saved = app
        .state
        .market
        .prices()
        .backfill_historical_prices(
            &BTreeSet::from(["BTC".to_owned()]),
            NaiveDate::from_ymd_opt(2026, 1, 10).unwrap(),
            true,
        )
        .await;
    assert_eq!(saved, 2);

    let admin = app.login_admin().await;
    let intraday = admin.get("/api/prices/BTC/intraday").send().await.json();
    let pts = intraday.as_array().unwrap();
    assert_eq!(pts.len(), 2);
    assert_eq!(pts[0]["priceEur"].to_string(), "91234.56789123");
    assert_eq!(pts[1]["priceEur"].to_string(), "92000.50000000");

    let logos = app
        .state
        .market
        .coingecko()
        .logo_urls(&BTreeSet::from(["btc".to_owned(), "AAPL".to_owned()]))
        .await;
    assert_eq!(
        logos.get("BTC").map(String::as_str),
        Some("https://coin-images.coingecko.com/btc.png")
    );
    assert!(!logos.contains_key("AAPL"));
}

#[tokio::test]
async fn crypto_learning_guards_with_yahoo_dominance_and_drops_old_stock_snapshots() {
    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    let app = app(&yahoo, &coingecko).await;

    // STX is a listed equity on Yahoo: must never be learned as crypto.
    stub_chart(&yahoo, "STX", "USD", 100.0, 1).await;
    // NEWC and AMBG are unknown to Yahoo (404).
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/NEWC"))
        .respond_with(ResponseTemplate::new(404))
        .mount(&yahoo)
        .await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/AMBG"))
        .respond_with(ResponseTemplate::new(404))
        .mount(&yahoo)
        .await;
    // CoinGecko returns a dominant coin for NEWC (20x runner-up) and ambiguous coins for AMBG (2x).
    Mock::given(method("GET"))
        .and(path("/coins/markets"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([
            {"id": "newcoin", "symbol": "newc", "name": "NewCoin", "market_cap_rank": 40, "market_cap": 200000000},
            {"id": "newcoin-wrapped", "symbol": "newc", "name": "Wrapped", "market_cap_rank": 900, "market_cap": 10000000},
            {"id": "ambg-a", "symbol": "ambg", "name": "Ambg A", "market_cap_rank": 200, "market_cap": 20000000},
            {"id": "ambg-b", "symbol": "ambg", "name": "Ambg B", "market_cap_rank": 250, "market_cap": 10000000}
        ])))
        .mount(&coingecko)
        .await;
    Mock::given(method("GET"))
        .and(path("/simple/price"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"newcoin": {"eur": 4.25}})))
        .mount(&coingecko)
        .await;

    // Seed a stale Yahoo-era snapshot for NEWC; learning must purge it atomically.
    picsou::db::query(
        "INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ('NEWC', '2026-01-10', 99.00, NOW())",
    )
    .execute(&app.state.db)
    .await
    .unwrap();

    let prices = app
        .state
        .market
        .prices()
        .refresh_crypto_prices(&BTreeSet::from([
            "STX".to_owned(),
            "NEWC".to_owned(),
            "AMBG".to_owned(),
        ]))
        .await;
    assert_eq!(
        prices.get("NEWC").map(ToString::to_string).as_deref(),
        Some("4.25")
    );
    assert!(!prices.contains_key("STX"));
    assert!(!prices.contains_key("AMBG"));

    // Only the freshly written 2026-01-15 snapshot remains for NEWC; the 2026-01-10 stock row is gone.
    let old_count: i64 = picsou::db::query_scalar(
        "SELECT count(*) FROM price_snapshot WHERE ticker = 'NEWC' AND date = '2026-01-10'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(old_count, 0);
}

#[tokio::test]
async fn coingecko_historical_backfill_clamps_lookback_within_365_day_public_limit() {
    use chrono::Months;

    let yahoo = MockServer::start().await;
    let coingecko = MockServer::start().await;
    let app = app(&yahoo, &coingecko).await;
    let now_ts = app.state.clock.now().timestamp();
    let expected_from = (now_ts - 364 * 86_400).to_string();
    let sample_ts = chrono::TimeZone::with_ymd_and_hms(&chrono::Utc, 2025, 2, 1, 12, 0, 0)
        .unwrap()
        .timestamp_millis();

    Mock::given(method("GET"))
        .and(path("/coins/bitcoin/market_chart/range"))
        .and(query_param("vs_currency", "eur"))
        .and(query_param("from", expected_from.as_str()))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({"prices": [[sample_ts, 85000.0]]})),
        )
        .expect(1)
        .mount(&coingecko)
        .await;

    // Same 12-month window PriceBackfillRunner requests on startup (365 days + hours > 365 days).
    let today = app.state.clock.today();
    let from = today.checked_sub_months(Months::new(12)).unwrap();
    let saved = app
        .state
        .market
        .prices()
        .backfill_historical_prices(&BTreeSet::from(["BTC".to_owned()]), from, true)
        .await;
    assert_eq!(saved, 1);
}
