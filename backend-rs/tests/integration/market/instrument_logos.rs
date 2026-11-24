//! The logo resolver of shares and funds, with Yahoo's quote page and its image host stubbed.

use std::time::Duration;

use chrono::{DateTime, Utc};
use picsou::jobs::scheduler;
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::model::AccountType;
use picsou::market::instrument_logos::{MAX_IMAGE_BYTES, MAX_PAGE_BYTES, Miss};
use picsou::testing::TestApp;
use rust_decimal::Decimal;
use serde_json::json;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const AAPL_PAGE: &str = include_str!("fixtures/yahoo/quote-page-aapl.html");
const PAGE: &str = "/quote/AAPL/";
const LIGHT: &str = "/lo/mysterio/api/5dee/finance/resizefill_w50_h50/https://s.yimg.com/lg/logos/US0378331005/light/2e23b039.png";
const DARK: &str = "/lo/mysterio/api/402d/finance/resizefill_w50_h50/https://s.yimg.com/lg/logos/US0378331005/dark/fabb0b30.png";
const PNG: [u8; 16] = [
    0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, b'I', b'H', b'D', b'R',
];
const PNG_DARK: [u8; 20] = [
    0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, b'I', b'H', b'D', b'R', 0, 0, 0, 0,
];

/// An app whose quote pages and logo images both come from `yahoo`.
async fn app(yahoo: &MockServer) -> TestApp {
    TestApp::builder()
        .with_admin()
        .env("YAHOO_QUOTE_PAGE_BASE_URL", yahoo.uri())
        .env("YAHOO_LOGO_IMAGE_BASE_URL", yahoo.uri())
        .spawn()
        .await
}

async fn serve(yahoo: &MockServer, at: &str, response: ResponseTemplate) {
    Mock::given(method("GET"))
        .and(path(at))
        .respond_with(response)
        .mount(yahoo)
        .await;
}

fn html(body: impl Into<String>) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_raw(body.into(), "text/html")
}

fn image(bytes: &[u8], content_type: &str) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_raw(bytes.to_vec(), content_type)
}

/// A quote page whose only JSON block is a quote response holding `quotes`.
fn quotes(quotes: &str) -> ResponseTemplate {
    html(format!(
        r#"<html><script type="application/json">{{"quoteResponse":{{"result":[{quotes}]}}}}</script></html>"#
    ))
}

/// The paths Yahoo was asked for, in order.
async fn requested(yahoo: &MockServer) -> Vec<String> {
    yahoo
        .received_requests()
        .await
        .expect("request recording")
        .iter()
        .map(|request| request.url.path().to_owned())
        .collect()
}

async fn lookup(app: &TestApp) -> Result<picsou::market::instrument_logos::Mark, Miss> {
    app.state.market.instrument_logos().lookup("AAPL").await
}

fn is_unavailable<T: std::fmt::Debug>(result: &Result<T, Miss>) -> bool {
    matches!(result, Err(Miss::Unavailable(_)))
}

fn is_absent<T: std::fmt::Debug>(result: &Result<T, Miss>) -> bool {
    matches!(result, Err(Miss::Absent(_)))
}

// ---------------------------------------------------------------------------
// One lookup
// ---------------------------------------------------------------------------

#[tokio::test]
async fn a_lookup_reads_both_variants_from_the_page_then_the_image_host() {
    let yahoo = MockServer::start().await;
    serve(&yahoo, PAGE, html(AAPL_PAGE)).await;
    serve(&yahoo, LIGHT, image(&PNG, "image/png")).await;
    serve(&yahoo, DARK, image(&PNG_DARK, "image/png")).await;
    let app = app(&yahoo).await;

    let mark = lookup(&app).await.expect("a mark");

    assert_eq!(mark.light.bytes, PNG);
    assert_eq!(mark.light.content_type, "image/png");
    assert_eq!(mark.dark.expect("a dark variant").bytes, PNG_DARK);
    assert_eq!(requested(&yahoo).await, [PAGE, LIGHT, DARK]);
}

#[tokio::test]
async fn a_429_on_the_page_pauses_the_lookups_for_what_retry_after_says() {
    let yahoo = MockServer::start().await;
    serve(
        &yahoo,
        PAGE,
        ResponseTemplate::new(429).insert_header("Retry-After", "120"),
    )
    .await;
    let app = app(&yahoo).await;

    assert_eq!(lookup(&app).await, Err(Miss::RateLimited));
    assert_eq!(
        app.state.market.cooldown().until(),
        app.state.clock.now() + chrono::Duration::seconds(120)
    );

    // While it lasts, no request leaves for Yahoo.
    assert_eq!(lookup(&app).await, Err(Miss::RateLimited));
    assert_eq!(requested(&yahoo).await, [PAGE]);
}

#[tokio::test]
async fn a_5xx_is_asked_again_later_and_pauses_the_next_lookups() {
    let yahoo = MockServer::start().await;
    serve(&yahoo, PAGE, ResponseTemplate::new(503)).await;
    let app = app(&yahoo).await;
    let logos = app.state.market.instrument_logos();

    assert!(is_unavailable(&logos.lookup("AAPL").await));
    assert_eq!(logos.lookup("MSFT").await, Err(Miss::RateLimited));
    assert_eq!(requested(&yahoo).await, [PAGE]);
}

#[tokio::test]
async fn a_quote_page_that_does_not_exist_is_a_permanent_miss() {
    let yahoo = MockServer::start().await;
    let app = app(&yahoo).await;

    assert_eq!(lookup(&app).await, Err(Miss::Absent("HTTP 404")));
    assert!(!app.state.market.cooldown().active());
}

#[tokio::test]
async fn a_page_that_does_not_show_the_symbols_quote_is_asked_again_later() {
    let msft = r#"{"symbol":"MSFT","logoUrl":"https://s.yimg.com/lg/m.png"}"#;
    let pages = [
        (
            "a consent page without JSON",
            html(
                r#"<html><body><form action="https://consent.yahoo.com/v2/collectConsent">Accept all</form></body></html>"#,
            ),
        ),
        ("JSON that only quotes other symbols", quotes(msft)),
        (
            "a quote without a logo on a page with no mark at all",
            quotes(r#"{"symbol":"AAPL","quoteType":"EQUITY"}"#),
        ),
        (
            "only a recommendations object for the symbol",
            quotes(&format!(
                r#"{msft},{{"symbol":"AAPL","recommendedSymbols":[{{"symbol":"MSFT","score":0.25}}]}}"#
            )),
        ),
        (
            "a logo URL off the image host",
            quotes(&format!(
                r#"{msft},{{"symbol":"AAPL","quoteType":"EQUITY","logoUrl":"https://cdn.example/aapl.png"}}"#
            )),
        ),
        (
            // A body worth parsing, so only the status can make this a miss.
            "a redirected page",
            ResponseTemplate::new(301)
                .insert_header("Location", "https://consent.yahoo.com/")
                .set_body_raw(AAPL_PAGE, "text/html"),
        ),
        (
            // The page's size is Yahoo's layout, the same for every ticker.
            "a page over the memory cap",
            html("x".repeat(MAX_PAGE_BYTES + 1)),
        ),
    ];
    let yahoo = MockServer::start().await;
    let app = app(&yahoo).await;
    for (case, page) in pages {
        yahoo.reset().await;
        serve(&yahoo, PAGE, page).await;

        assert!(is_unavailable(&lookup(&app).await), "{case}");
        // Nothing is downloaded, and a redirect is not followed.
        assert_eq!(requested(&yahoo).await, [PAGE], "{case}");
        assert!(!app.state.market.cooldown().active(), "{case}");
    }
}

#[tokio::test]
async fn a_quote_for_the_symbol_without_a_logo_is_a_permanent_miss_and_downloads_nothing() {
    let yahoo = MockServer::start().await;
    serve(
        &yahoo,
        PAGE,
        quotes(
            r#"{"symbol":"MSFT","logoUrl":"https://s.yimg.com/lg/m.png"},{"symbol":"AAPL","quoteType":"EQUITY"}"#,
        ),
    )
    .await;
    let app = app(&yahoo).await;

    assert_eq!(
        lookup(&app).await,
        Err(Miss::Absent("no logo on the quote page"))
    );
    assert_eq!(requested(&yahoo).await, [PAGE]);
}

#[tokio::test]
async fn the_bytes_of_the_light_mark_decide_what_is_kept() {
    let oversized = {
        let mut bytes = PNG.to_vec();
        bytes.resize(MAX_IMAGE_BYTES + 1, 0);
        bytes
    };
    let svg = br#"<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"/>"#;
    let yahoo = MockServer::start().await;
    let app = app(&yahoo).await;

    // A refusal about the mark itself: asking again gets the same file.
    for (case, light) in [
        ("an SVG mark", image(svg, "image/svg+xml")),
        ("an oversized mark", image(&oversized, "image/png")),
    ] {
        yahoo.reset().await;
        serve(&yahoo, PAGE, html(AAPL_PAGE)).await;
        serve(&yahoo, LIGHT, light).await;
        assert!(is_absent(&lookup(&app).await), "{case}");
    }

    // Not the mark at all: an error page, or a redirect that is not followed.
    for (case, light) in [
        (
            "an HTML page served as the image",
            image(b"<html>Too many requests</html>", "text/html"),
        ),
        (
            "an HTML page over the image cap",
            image("<html>".repeat(50_000).as_bytes(), "text/html"),
        ),
        (
            "a redirected image",
            ResponseTemplate::new(302)
                .insert_header("Location", "https://s.yimg.com/elsewhere.png"),
        ),
    ] {
        yahoo.reset().await;
        serve(&yahoo, PAGE, html(AAPL_PAGE)).await;
        serve(&yahoo, LIGHT, light).await;
        assert!(is_unavailable(&lookup(&app).await), "{case}");
        assert_eq!(requested(&yahoo).await, [PAGE, LIGHT], "{case}");
    }

    // A label the CDN left out or got wrong changes nothing: the stored type is the signature's.
    for (case, light) in [
        (
            "no content type",
            ResponseTemplate::new(200).set_body_bytes(PNG.to_vec()),
        ),
        ("octet-stream", image(&PNG, "application/octet-stream")),
    ] {
        yahoo.reset().await;
        serve(&yahoo, PAGE, html(AAPL_PAGE)).await;
        serve(&yahoo, LIGHT, light).await;
        let mark = lookup(&app)
            .await
            .unwrap_or_else(|miss| panic!("{case}: {miss:?}"));
        assert_eq!(mark.light.content_type, "image/png", "{case}");
    }
}

#[tokio::test]
async fn a_dark_variant_yahoo_does_not_have_keeps_the_light_mark() {
    let yahoo = MockServer::start().await;
    serve(&yahoo, PAGE, html(AAPL_PAGE)).await;
    serve(&yahoo, LIGHT, image(&PNG, "image/png")).await;
    let app = app(&yahoo).await;

    let mark = lookup(&app).await.expect("the light mark");

    assert_eq!(mark.light.bytes, PNG);
    assert_eq!(mark.dark, None);
    assert_eq!(requested(&yahoo).await, [PAGE, LIGHT, DARK]);
}

#[tokio::test]
async fn a_dark_variant_yahoo_fails_to_serve_leaves_the_whole_ticker_for_later() {
    // Storing the light mark alone would settle the ticker without its dark variant.
    let yahoo = MockServer::start().await;
    let app = app(&yahoo).await;
    for (case, dark) in [
        ("an HTML page", image(b"<html></html>", "text/html")),
        ("a 503", ResponseTemplate::new(503)),
    ] {
        yahoo.reset().await;
        serve(&yahoo, PAGE, html(AAPL_PAGE)).await;
        serve(&yahoo, LIGHT, image(&PNG, "image/png")).await;
        serve(&yahoo, DARK, dark).await;
        assert!(is_unavailable(&lookup(&app).await), "{case}");
    }

    // The 503 armed the pause; once it is over, a 429 on the dark variant arms its own.
    app.clock.advance(Duration::from_secs(3600));
    yahoo.reset().await;
    serve(&yahoo, PAGE, html(AAPL_PAGE)).await;
    serve(&yahoo, LIGHT, image(&PNG, "image/png")).await;
    serve(
        &yahoo,
        DARK,
        ResponseTemplate::new(429).insert_header("Retry-After", "90"),
    )
    .await;
    assert_eq!(lookup(&app).await, Err(Miss::RateLimited));
    assert_eq!(
        app.state.market.cooldown().until(),
        app.state.clock.now() + chrono::Duration::seconds(90)
    );
}

// ---------------------------------------------------------------------------
// The pass
// ---------------------------------------------------------------------------

async fn admin_member_id(app: &TestApp) -> i64 {
    picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .expect("admin member")
}

/// An account of `kind` holding one unit of each ticker.
async fn holding(app: &TestApp, kind: AccountType, tickers: &[&str]) {
    let member = admin_member_id(app).await;
    let now = app.state.clock.now();
    let account = accounts::insert(&app.state.db, &NewAccount::new(member, "Held", kind), now)
        .await
        .expect("account");
    for ticker in tickers {
        picsou::db::query(
            "INSERT INTO account_holding (account_id, ticker, name, quantity, created_at, updated_at) \
             VALUES ($1, $2, $2, 1, $3, $3)",
        )
        .bind(account.id)
        .bind(*ticker)
        .bind(now)
        .execute(&app.state.db)
        .await
        .expect("holding");
    }
}

/// A recorded price: the proof that a provider quotes the ticker.
async fn priced(app: &TestApp, tickers: &[&str]) {
    for ticker in tickers {
        picsou::db::query(
            "INSERT INTO price_snapshot (ticker, date, price_eur) VALUES ($1, '2026-01-14', 10)",
        )
        .bind(*ticker)
        .execute(&app.state.db)
        .await
        .expect("price");
    }
}

async fn answered(app: &TestApp, ticker: &str, status: &str, attempted_at: DateTime<Utc>) {
    picsou::db::query(
        "INSERT INTO instrument_logo (ticker, status, attempted_at) VALUES ($1, $2, $3)",
    )
    .bind(ticker)
    .bind(status)
    .bind(attempted_at)
    .execute(&app.state.db)
    .await
    .expect("instrument_logo");
}

type LogoRow = (
    String,
    String,
    Option<Vec<u8>>,
    Option<Vec<u8>>,
    DateTime<Utc>,
    Option<DateTime<Utc>>,
);

/// ticker, status, image, image_dark, attempted_at, fetched_at of every row, by ticker.
async fn rows(app: &TestApp) -> Vec<LogoRow> {
    picsou::db::query_as(
        "SELECT ticker, status, image, image_dark, attempted_at, fetched_at FROM instrument_logo \
         ORDER BY ticker",
    )
    .fetch_all(&app.state.db)
    .await
    .expect("instrument_logo rows")
}

#[tokio::test]
async fn due_tickers_are_held_priced_shares_outside_crypto_accounts() {
    let app = TestApp::with_admin().await;
    // PEPE is a coin of the seeded registry; 1RAKKPA is a fund code no provider ever priced;
    // US0378331005 is an ISIN, which is not a symbol; SOL.X sits in a crypto account.
    holding(
        &app,
        AccountType::Pea,
        &["aapl", "PEPE", "1RAKKPA", "US0378331005", "MC.PA"],
    )
    .await;
    holding(&app, AccountType::Crypto, &["SOL.X"]).await;
    priced(&app, &["AAPL", "PEPE", "US0378331005", "MC.PA", "SOL.X"]).await;

    let due = app.state.market.instrument_logos().due_tickers().await;

    assert_eq!(due.expect("due tickers"), ["AAPL", "MC.PA"]);
}

#[tokio::test]
async fn due_tickers_leave_out_what_was_answered_and_retry_a_failure_after_seven_days() {
    let app = TestApp::with_admin().await;
    let now = app.state.clock.now();
    let tickers = ["STORED", "NOLOGO", "RECENT", "OLD", "NEW"];
    holding(&app, AccountType::Pea, &tickers).await;
    priced(&app, &tickers).await;
    picsou::db::query(
        "INSERT INTO instrument_logo (ticker, status, image, content_type, attempted_at, fetched_at) \
         VALUES ('STORED', 'STORED', $1, 'image/png', $2, $2)",
    )
    .bind(PNG.as_slice())
    .bind(now)
    .execute(&app.state.db)
    .await
    .expect("stored logo");
    answered(&app, "NOLOGO", "ABSENT", now - chrono::Duration::days(400)).await;
    answered(&app, "RECENT", "FAILED", now - chrono::Duration::days(7)).await;
    answered(
        &app,
        "OLD",
        "FAILED",
        now - chrono::Duration::days(7) - chrono::Duration::seconds(1),
    )
    .await;

    let due = app.state.market.instrument_logos().due_tickers().await;

    assert_eq!(due.expect("due tickers"), ["NEW", "OLD"]);
}

#[tokio::test]
async fn due_tickers_are_capped_per_pass() {
    let app = TestApp::with_admin().await;
    let tickers: Vec<String> = ('A'..='Y').map(|letter| format!("T{letter}")).collect();
    let tickers: Vec<&str> = tickers.iter().map(String::as_str).collect();
    holding(&app, AccountType::Pea, &tickers).await;
    priced(&app, &tickers).await;

    let due = app
        .state
        .market
        .instrument_logos()
        .due_tickers()
        .await
        .expect("due tickers");

    assert_eq!(due, &tickers[..10]);
}

#[tokio::test]
async fn a_pass_stores_the_mark_and_picsou_serves_it() {
    let yahoo = MockServer::start().await;
    serve(&yahoo, PAGE, html(AAPL_PAGE)).await;
    serve(&yahoo, LIGHT, image(&PNG, "image/png")).await;
    serve(&yahoo, DARK, image(&PNG_DARK, "image/png")).await;
    let app = app(&yahoo).await;
    holding(&app, AccountType::Pea, &["AAPL"]).await;
    priced(&app, &["AAPL"]).await;
    let now = app.state.clock.now();
    let logos = app.state.market.instrument_logos();

    assert_eq!(logos.resolve_pending().await.expect("pass"), 1);

    assert_eq!(
        rows(&app).await,
        [(
            "AAPL".to_owned(),
            "STORED".to_owned(),
            Some(PNG.to_vec()),
            Some(PNG_DARK.to_vec()),
            now,
            Some(now)
        )]
    );
    let admin = app.login_admin().await;
    let light = admin.get("/api/instrument-logos/AAPL").send().await;
    assert_eq!(light.status, 200);
    assert_eq!(light.header("content-type"), Some("image/png"));
    assert_eq!(light.body, PNG);
    let dark = admin
        .get("/api/instrument-logos/AAPL?variant=dark")
        .send()
        .await;
    assert_eq!(dark.body, PNG_DARK);

    // Answered once: the next pass asks Yahoo nothing.
    assert_eq!(logos.resolve_pending().await.expect("second pass"), 0);
    assert_eq!(requested(&yahoo).await, [PAGE, LIGHT, DARK]);
}

#[tokio::test]
async fn a_permanent_miss_is_recorded_as_absent_and_the_pass_goes_on() {
    let yahoo = MockServer::start().await;
    serve(
        &yahoo,
        PAGE,
        quotes(
            r#"{"symbol":"MSFT","logoUrl":"https://s.yimg.com/lg/m.png"},{"symbol":"AAPL","quoteType":"EQUITY"}"#,
        ),
    )
    .await;
    serve(
        &yahoo,
        "/quote/MC.PA/",
        quotes(r#"{"symbol":"MC.PA","logoUrl":"https://s.yimg.com/lg/mc.png"}"#),
    )
    .await;
    serve(&yahoo, "/lg/mc.png", image(&PNG, "image/png")).await;
    let app = app(&yahoo).await;
    holding(&app, AccountType::Pea, &["AAPL", "MC.PA"]).await;
    priced(&app, &["AAPL", "MC.PA"]).await;
    let now = app.state.clock.now();

    let stored = app.state.market.instrument_logos().resolve_pending().await;

    assert_eq!(stored.expect("pass"), 1);
    assert_eq!(
        rows(&app).await,
        [
            (
                "AAPL".to_owned(),
                "ABSENT".to_owned(),
                None,
                None,
                now,
                None
            ),
            (
                "MC.PA".to_owned(),
                "STORED".to_owned(),
                Some(PNG.to_vec()),
                None,
                now,
                Some(now)
            ),
        ]
    );
}

#[tokio::test]
async fn an_unanswered_lookup_is_recorded_as_failed_stops_the_pass_and_is_retried_in_place() {
    let yahoo = MockServer::start().await;
    serve(&yahoo, PAGE, ResponseTemplate::new(503)).await;
    let app = app(&yahoo).await;
    holding(&app, AccountType::Pea, &["AAPL", "MC.PA", "MSFT"]).await;
    priced(&app, &["AAPL", "MC.PA", "MSFT"]).await;
    let failed_at = app.state.clock.now();
    let logos = app.state.market.instrument_logos();

    assert_eq!(logos.resolve_pending().await.expect("pass"), 0);

    // The rest of the batch waits for the next pass.
    assert_eq!(requested(&yahoo).await, [PAGE]);
    assert_eq!(
        rows(&app).await,
        [(
            "AAPL".to_owned(),
            "FAILED".to_owned(),
            None,
            None,
            failed_at,
            None
        )]
    );
    let id_of_the_failure: i64 =
        picsou::db::query_scalar("SELECT id FROM instrument_logo WHERE ticker = 'AAPL'")
            .fetch_one(&app.state.db)
            .await
            .expect("id");

    // Eight days later Yahoo answers: the same row becomes the stored mark.
    app.clock.advance(Duration::from_secs(8 * 24 * 3600));
    yahoo.reset().await;
    serve(&yahoo, PAGE, html(AAPL_PAGE)).await;
    serve(&yahoo, LIGHT, image(&PNG, "image/png")).await;
    answered(&app, "MC.PA", "ABSENT", failed_at).await;
    answered(&app, "MSFT", "ABSENT", failed_at).await;

    assert_eq!(logos.resolve_pending().await.expect("retry"), 1);

    let retried: (i64, String, DateTime<Utc>) = picsou::db::query_as(
        "SELECT id, status, attempted_at FROM instrument_logo WHERE ticker = 'AAPL'",
    )
    .fetch_one(&app.state.db)
    .await
    .expect("retried row");
    assert_eq!(
        retried,
        (
            id_of_the_failure,
            "STORED".to_owned(),
            app.state.clock.now()
        )
    );
}

#[tokio::test]
async fn a_rate_limit_records_nothing_and_stops_the_pass() {
    let yahoo = MockServer::start().await;
    serve(&yahoo, PAGE, ResponseTemplate::new(429)).await;
    let app = app(&yahoo).await;
    holding(&app, AccountType::Pea, &["AAPL", "MC.PA"]).await;
    priced(&app, &["AAPL", "MC.PA"]).await;

    let stored = app.state.market.instrument_logos().resolve_pending().await;

    assert_eq!(stored.expect("pass"), 0);
    assert_eq!(rows(&app).await, []);
    assert_eq!(requested(&yahoo).await, [PAGE]);
}

// ---------------------------------------------------------------------------
// The hourly job
// ---------------------------------------------------------------------------

/// An app holding AAPL, never priced, with Yahoo's chart, quote page and image host stubbed.
async fn app_about_to_price_aapl(yahoo: &MockServer, logos_enabled: &str) -> TestApp {
    serve(
        yahoo,
        "/v8/finance/chart/AAPL",
        ResponseTemplate::new(200).set_body_json(json!({"chart": {"result": [{
            "meta": {"currency": "EUR", "regularMarketPrice": 100.0},
            "timestamp": [1768471200],
            "indicators": {"quote": [{"close": [100.0]}]}
        }], "error": null}})),
    )
    .await;
    serve(yahoo, PAGE, html(AAPL_PAGE)).await;
    serve(yahoo, LIGHT, image(&PNG, "image/png")).await;
    let app = TestApp::builder()
        .with_admin()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .env("YAHOO_QUOTE_PAGE_BASE_URL", yahoo.uri())
        .env("YAHOO_LOGO_IMAGE_BASE_URL", yahoo.uri())
        .env("INSTRUMENT_LOGOS_ENABLED", logos_enabled)
        .spawn()
        .await;
    holding(&app, AccountType::Pea, &["AAPL"]).await;
    app
}

#[tokio::test]
async fn the_hourly_price_job_fetches_the_logo_of_a_ticker_it_has_just_priced() {
    let yahoo = MockServer::start().await;
    let app = app_about_to_price_aapl(&yahoo, "true").await;

    scheduler::refresh_prices(&app.state)
        .await
        .expect("price job");

    let price: Decimal =
        picsou::db::query_scalar("SELECT price_eur FROM price_snapshot WHERE ticker = 'AAPL'")
            .fetch_one(&app.state.db)
            .await
            .expect("recorded price");
    assert_eq!(price, Decimal::from(100));
    let logos = rows(&app).await;
    assert_eq!(logos.len(), 1, "{logos:?}");
    assert_eq!(
        (logos[0].0.as_str(), logos[0].1.as_str()),
        ("AAPL", "STORED")
    );
}

#[tokio::test]
async fn the_hourly_price_job_asks_for_no_logo_when_they_are_switched_off() {
    let yahoo = MockServer::start().await;
    let app = app_about_to_price_aapl(&yahoo, "false").await;

    scheduler::refresh_prices(&app.state)
        .await
        .expect("price job");

    assert_eq!(rows(&app).await, []);
    assert!(!requested(&yahoo).await.contains(&PAGE.to_owned()));
}
