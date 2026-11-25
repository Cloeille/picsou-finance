//! Pricing by ISIN (#700): the justETF and Yahoo sources and the service ordering them
//! (JustEtfIsinPriceSourceTest, YahooIsinPriceSourceTest, IsinPriceServiceTest).

use std::collections::BTreeMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use async_trait::async_trait;
use chrono::{NaiveDate, TimeZone, Utc};
use picsou::clock::{AppClock, FixedClock};
use picsou::market::cooldown::YahooCooldown;
use picsou::market::isin_prices::{
    BoursoramaIsinPriceSource, IsinPriceService, IsinPriceSource, IsinQuote,
    JustEtfIsinPriceSource, MAX_PROBED, QUOTE_TTL, YahooIsinPriceSource, boursorama_window,
    end_of_day_closes, quoted_in_euros, reference_currency, trades_in,
};
use picsou::market::yahoo::YahooFinance;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{method, path, path_regex, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

/// Bodies are justETF's real answers, captured on 2026-11-22.
const QUOTE_LU1829219390: &str = r#"{"latestQuote":{"raw":4.42,"localized":"4.42"},"latestQuoteDate":"2026-11-20","previousQuote":{"raw":4.55,"localized":"4.55"},"previousQuoteDate":"2026-11-19","dtdPrc":{"raw":-2.86,"localized":"-2.86"},"dtdAmt":{"raw":-0.13,"localized":"-0.13"},"quoteTradingVenue":"XETRA","quoteLowHigh":{"low":{"raw":3.59,"localized":"3.59"},"high":{"raw":5.07,"localized":"5.07"}}}"#;
const QUOTE_UNLISTED_FUND: &str = r#"{"latestQuote":null,"latestQuoteDate":null,"previousQuote":null,"previousQuoteDate":null,"dtdPrc":null,"dtdAmt":null,"quoteTradingVenue":null,"quoteLowHigh":null}"#;
const CHART_LU1829219390: &str = r#"{"latestQuote":{"raw":4.42,"localized":"4.42"},"latestQuoteDate":"2026-11-20","price":{"raw":4.42,"localized":"4.42"},"performance":{"raw":-6.75,"localized":"-6.75"},"prevDaySeries":[],"series":[{"date":"2026-11-16","value":{"raw":4.74,"localized":"4.74"}},{"date":"2026-11-17","value":{"raw":4.65,"localized":"4.65"}},{"date":"2026-11-18","value":{"raw":4.55,"localized":"4.55"}},{"date":"2026-11-19","value":{"raw":4.55,"localized":"4.55"}},{"date":"2026-11-20","value":{"raw":4.42,"localized":"4.42"}}],"latestDate":"2026-11-20","endOfDay":"2026-11-20T21:00:00Z","features":{}}"#;

const LU: &str = "LU1829219390";
const US: &str = "US0378331005";

fn day(y: i32, m: u32, d: u32) -> NaiveDate {
    NaiveDate::from_ymd_opt(y, m, d).unwrap()
}

fn dec(text: &str) -> Decimal {
    text.parse().unwrap()
}

fn clock() -> (Arc<FixedClock>, AppClock) {
    let fixed = Arc::new(FixedClock::new(
        Utc.with_ymd_and_hms(2026, 11, 22, 10, 0, 0).unwrap(),
    ));
    let app = AppClock::new(fixed.clone(), chrono_tz::UTC);
    (fixed, app)
}

fn justetf(server: &MockServer) -> JustEtfIsinPriceSource {
    JustEtfIsinPriceSource::new(reqwest::Client::new(), &server.uri(), clock().1)
}

fn json_body(body: &str) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_raw(body, "application/json")
}

async fn mount_justetf(server: &MockServer, endpoint: &str, body: &str) {
    Mock::given(method("GET"))
        .and(path(format!("/api/etfs/{LU}/{endpoint}")))
        .respond_with(json_body(body))
        .mount(server)
        .await;
}

#[tokio::test]
async fn justetf_quote_reads_the_euro_price_and_its_date() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path(format!("/api/etfs/{LU}/quote")))
        .and(query_param("currency", "EUR"))
        .and(query_param("isin", LU))
        .respond_with(json_body(QUOTE_LU1829219390))
        .mount(&server)
        .await;

    let quote = justetf(&server).quote(LU).await.expect("a quote");

    assert_eq!(quote.price_eur, dec("4.42"));
    assert_eq!(quote.as_of, Some(day(2026, 11, 20)));
    assert_eq!(quote.source, "justETF");
}

#[tokio::test]
async fn justetf_quote_is_none_for_an_unlisted_fund() {
    let server = MockServer::start().await;
    mount_justetf(&server, "quote", QUOTE_UNLISTED_FUND).await;

    assert!(justetf(&server).quote(LU).await.is_none());
}

#[tokio::test]
async fn justetf_daily_history_reads_the_series_in_range() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path(format!("/api/etfs/{LU}/performance-chart")))
        .and(query_param("currency", "EUR"))
        .and(query_param("dateFrom", "2026-11-17"))
        .and(query_param("dateTo", "2026-11-19"))
        .respond_with(json_body(CHART_LU1829219390))
        .mount(&server)
        .await;

    let history = justetf(&server)
        .daily_history(LU, day(2026, 11, 17), day(2026, 11, 19))
        .await;

    assert_eq!(history.len(), 3);
    assert_eq!(history[&day(2026, 11, 17)], dec("4.65"));
    assert_eq!(history[&day(2026, 11, 19)], dec("4.55"));
}

#[tokio::test]
async fn a_justetf_server_error_is_none_never_a_failure() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;
    let source = justetf(&server);

    assert!(source.quote(LU).await.is_none());
    assert!(
        source
            .daily_history(LU, day(2026, 11, 1), day(2026, 11, 20))
            .await
            .is_empty()
    );
}

#[tokio::test]
async fn a_justetf_429_pauses_the_source_until_the_pause_ends() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(429))
        .expect(2)
        .mount(&server)
        .await;
    let (fixed, app_clock) = clock();
    let source = JustEtfIsinPriceSource::new(reqwest::Client::new(), &server.uri(), app_clock);

    assert!(source.quote(LU).await.is_none());
    assert!(source.quote(US).await.is_none());
    // Only the first call reached the server; the pause then ends and the source asks again.
    fixed.advance(std::time::Duration::from_secs(5 * 60 + 1));
    assert!(source.quote(US).await.is_none());
}

#[tokio::test]
async fn justetf_rejects_invalid_isins_without_making_an_http_request() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200))
        .expect(0)
        .mount(&server)
        .await;
    let source = justetf(&server);

    for invalid in [
        "..",
        "../other",
        "MULYF",
        "",
        "not an isin",
        "LU1829219390/x",
    ] {
        assert!(source.quote(invalid).await.is_none());
        assert!(
            source
                .daily_history(invalid, day(2026, 11, 17), day(2026, 11, 19))
                .await
                .is_empty()
        );
    }
}

#[tokio::test]
async fn justetf_normalizes_lowercase_and_padded_isin() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path(format!("/api/etfs/{LU}/quote")))
        .and(query_param("currency", "EUR"))
        .and(query_param("isin", LU))
        .respond_with(json_body(QUOTE_LU1829219390))
        .expect(1)
        .mount(&server)
        .await;

    let quote = justetf(&server)
        .quote(" lu1829219390 ")
        .await
        .expect("normalized quote");
    assert_eq!(quote.price_eur, dec("4.42"));
}

fn yahoo_chart(price: f64, currency: &str) -> Value {
    json!({"chart": {"result": [{
        "meta": {"regularMarketPrice": price, "currency": currency},
        "timestamp": [1_795_392_000_i64]
    }]}})
}

fn yahoo_search(symbols: &[&str]) -> Value {
    let quotes: Vec<Value> = symbols
        .iter()
        .map(|s| json!({"symbol": s, "longname": "x", "isYahooFinance": true}))
        .collect();
    json!({"quotes": quotes})
}

async fn mount_search(server: &MockServer, symbols: &[&str]) {
    Mock::given(method("GET"))
        .and(path("/v1/finance/search"))
        .respond_with(ResponseTemplate::new(200).set_body_json(yahoo_search(symbols)))
        .mount(server)
        .await;
}

async fn mount_chart(server: &MockServer, symbol: &str, price: f64, currency: &str) {
    Mock::given(method("GET"))
        .and(path(format!("/v8/finance/chart/{symbol}")))
        .respond_with(ResponseTemplate::new(200).set_body_json(yahoo_chart(price, currency)))
        .mount(server)
        .await;
}

fn yahoo_source(server: &MockServer) -> YahooIsinPriceSource {
    let app_clock = clock().1;
    let yahoo = YahooFinance::new(
        reqwest::Client::new(),
        &server.uri(),
        Arc::new(YahooCooldown::new(app_clock.clone())),
        app_clock,
    );
    YahooIsinPriceSource::new(Arc::new(yahoo))
}

#[test]
fn reference_currency_follows_the_isins_market() {
    assert_eq!(reference_currency(US), Some("USD"));
    assert_eq!(reference_currency(LU), Some("EUR"));
    assert_eq!(reference_currency("IE00B4L5Y983"), Some("EUR"));
    assert_eq!(reference_currency("FR0000120271"), Some("EUR"));
    assert_eq!(reference_currency("GB0002875804"), Some("GBP"));
    assert_eq!(reference_currency("KYG9830T1067"), None);
}

#[test]
fn london_pence_count_as_the_gbp_market() {
    assert!(trades_in(Some("GBp"), "GBP"));
    assert!(trades_in(Some("GBX"), "GBP"));
    assert!(!trades_in(Some("USD"), "GBP"));
    assert!(!trades_in(None, "GBP"));
}

/// #683: the US grey-market USD line comes first and is skipped, the EUR line is priced.
#[tokio::test]
async fn a_luxembourg_etf_is_priced_on_its_euro_listing_not_the_usd_line() {
    let server = MockServer::start().await;
    mount_search(&server, &["MULYF", "BNKE.PA"]).await;
    mount_chart(&server, "MULYF", 437.0, "USD").await;
    mount_chart(&server, "BNKE.PA", 4.41, "EUR").await;

    let quote = yahoo_source(&server).quote(LU).await.expect("a quote");

    assert_eq!(quote.price_eur, dec("4.41"));
    assert_eq!(quote.as_of, Some(day(2026, 11, 23)));
    assert_eq!(quote.source, "Yahoo");
}

#[tokio::test]
async fn a_us_share_is_priced_on_its_usd_listing_converted_to_eur() {
    let server = MockServer::start().await;
    mount_search(&server, &["APC.DE", "AAPL"]).await;
    mount_chart(&server, "APC.DE", 300.0, "EUR").await;
    mount_chart(&server, "AAPL", 340.0, "USD").await;
    mount_chart(&server, "USDEUR=X", 0.92, "EUR").await;

    let quote = yahoo_source(&server).quote(US).await.expect("a quote");

    assert_eq!(quote.price_eur, dec("312.80"));
}

#[tokio::test]
async fn no_listing_in_the_reference_currency_means_no_yahoo_price() {
    let server = MockServer::start().await;
    mount_search(&server, &["MULYF"]).await;
    mount_chart(&server, "MULYF", 437.0, "USD").await;

    assert!(yahoo_source(&server).quote(LU).await.is_none());
}

#[tokio::test]
async fn the_found_symbol_is_remembered_so_the_next_quote_skips_the_search() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v1/finance/search"))
        .respond_with(ResponseTemplate::new(200).set_body_json(yahoo_search(&["BNKE.PA"])))
        .expect(1)
        .mount(&server)
        .await;
    mount_chart(&server, "BNKE.PA", 4.41, "EUR").await;
    let source = yahoo_source(&server);

    assert!(source.quote(LU).await.is_some());
    assert!(source.quote(LU).await.is_some());
}

#[tokio::test]
async fn at_most_three_matches_are_probed() {
    let server = MockServer::start().await;
    mount_search(&server, &["A1", "A2", "A3", "A4"]).await;
    Mock::given(method("GET"))
        .and(path_regex("^/v8/finance/chart/"))
        .respond_with(ResponseTemplate::new(200).set_body_json(yahoo_chart(1.0, "USD")))
        .expect(MAX_PROBED as u64)
        .mount(&server)
        .await;

    assert!(yahoo_source(&server).quote(LU).await.is_none());
}

#[tokio::test]
async fn yahoo_history_comes_from_the_reference_listing() {
    let server = MockServer::start().await;
    mount_search(&server, &["MULYF", "BNKE.PA"]).await;
    mount_chart(&server, "MULYF", 437.0, "USD").await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/BNKE.PA"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({"chart": {"result": [{
                "meta": {"regularMarketPrice": 4.41, "currency": "EUR"},
                "timestamp": [1_795_132_800_i64, 1_795_219_200_i64, 1_795_305_600_i64],
                "indicators": {"quote": [{"close": [4.4, 4.45, 4.41]}]}
            }]}})),
        )
        .mount(&server)
        .await;

    let history = yahoo_source(&server)
        .daily_history(LU, day(2026, 11, 20), day(2026, 11, 21))
        .await;

    assert_eq!(
        history,
        BTreeMap::from([
            (day(2026, 11, 20), dec("4.4")),
            (day(2026, 11, 21), dec("4.45"))
        ])
    );
}

#[tokio::test]
async fn yahoo_rejects_invalid_isins_without_making_an_http_request() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200))
        .expect(0)
        .mount(&server)
        .await;
    let source = yahoo_source(&server);

    for invalid in ["..", "../other", "US", "US_NOT_AN_ISIN", "MULYF", ""] {
        assert!(source.quote(invalid).await.is_none());
        assert!(
            source
                .daily_history(invalid, day(2026, 11, 20), day(2026, 11, 21))
                .await
                .is_empty()
        );
    }
}

#[tokio::test]
async fn yahoo_quote_returns_none_when_fx_conversion_overflows() {
    let server = MockServer::start().await;
    mount_search(&server, &["AAPL"]).await;
    let huge_chart = |currency: &str| {
        json!({"chart": {"result": [{
            "meta": {"regularMarketPrice": 10_000_000_000_000_000_000_u64, "currency": currency},
            "timestamp": [1_795_392_000_i64]
        }]}})
    };
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/AAPL"))
        .respond_with(ResponseTemplate::new(200).set_body_json(huge_chart("USD")))
        .expect(1)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/USDEUR=X"))
        .respond_with(ResponseTemplate::new(200).set_body_json(huge_chart("EUR")))
        .expect(1)
        .mount(&server)
        .await;

    assert!(yahoo_source(&server).quote(US).await.is_none());
}

/// A source answering a fixed quote and history, counting its quote calls.
struct Fake {
    name: &'static str,
    quote: Option<IsinQuote>,
    history: BTreeMap<NaiveDate, Decimal>,
    calls: AtomicUsize,
}

impl Fake {
    fn new(name: &'static str, price: Option<&str>, history: &[(NaiveDate, &str)]) -> Arc<Self> {
        Arc::new(Self {
            name,
            quote: price.map(|p| IsinQuote {
                price_eur: dec(p),
                as_of: Some(day(2026, 11, 20)),
                source: name,
            }),
            history: history.iter().map(|(d, p)| (*d, dec(p))).collect(),
            calls: AtomicUsize::new(0),
        })
    }

    fn calls(&self) -> usize {
        self.calls.load(Ordering::SeqCst)
    }
}

#[async_trait]
impl IsinPriceSource for Fake {
    fn name(&self) -> &'static str {
        self.name
    }

    async fn quote(&self, _isin: &str) -> Option<IsinQuote> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        self.quote.clone()
    }

    async fn daily_history(
        &self,
        _isin: &str,
        _from: NaiveDate,
        _to: NaiveDate,
    ) -> BTreeMap<NaiveDate, Decimal> {
        self.history.clone()
    }
}

fn service(sources: &[&Arc<Fake>]) -> (Arc<FixedClock>, IsinPriceService) {
    let (fixed, app_clock) = clock();
    let sources = sources
        .iter()
        .map(|s| (*s).clone() as Arc<dyn IsinPriceSource>)
        .collect();
    (fixed, IsinPriceService::new(sources, app_clock))
}

#[tokio::test]
async fn the_first_source_wins() {
    let justetf = Fake::new("justETF", Some("4.42"), &[]);
    let yahoo = Fake::new("Yahoo", Some("4.41"), &[]);

    let quote = service(&[&justetf, &yahoo]).1.quote(LU).await.unwrap();

    assert_eq!(quote.price_eur, dec("4.42"));
    assert_eq!(yahoo.calls(), 0);
}

#[tokio::test]
async fn the_second_source_answers_when_the_first_has_no_price() {
    let justetf = Fake::new("justETF", None, &[]);
    let yahoo = Fake::new("Yahoo", Some("4.41"), &[]);

    let quote = service(&[&justetf, &yahoo]).1.quote(LU).await.unwrap();

    assert_eq!(quote.source, "Yahoo");
}

#[tokio::test]
async fn an_unlisted_fund_is_none_never_a_failure() {
    let justetf = Fake::new("justETF", None, &[]);
    let yahoo = Fake::new("Yahoo", None, &[]);

    assert!(
        service(&[&justetf, &yahoo])
            .1
            .quote("FR0010135103")
            .await
            .is_none()
    );
}

#[tokio::test]
async fn anything_that_is_not_an_isin_is_none_without_asking_any_source() {
    let justetf = Fake::new("justETF", Some("1"), &[]);
    let service = service(&[&justetf]).1;

    assert!(service.quote("MULYF").await.is_none());
    assert!(service.quote("").await.is_none());
    assert_eq!(justetf.calls(), 0);
}

#[tokio::test]
async fn quotes_are_cached_and_refetched_once_stale() {
    let justetf = Fake::new("justETF", Some("4.42"), &[]);
    let (fixed, service) = service(&[&justetf]);

    service.quote(LU).await;
    service.quote("lu1829219390 ").await;
    assert_eq!(justetf.calls(), 1);

    fixed.advance(QUOTE_TTL.to_std().unwrap() + std::time::Duration::from_secs(1));
    service.quote(LU).await;
    assert_eq!(justetf.calls(), 2);
}

#[tokio::test]
async fn history_comes_from_the_first_source_that_has_any() {
    let justetf = Fake::new("justETF", None, &[]);
    let yahoo = Fake::new("Yahoo", None, &[(day(2026, 11, 20), "4.41")]);
    let service = service(&[&justetf, &yahoo]).1;

    let history = service
        .daily_history(LU, day(2026, 11, 15), day(2026, 11, 20))
        .await;

    assert_eq!(history, BTreeMap::from([(day(2026, 11, 20), dec("4.41"))]));
    assert!(
        service
            .daily_history(LU, day(2026, 11, 20), day(2026, 11, 15))
            .await
            .is_empty()
    );
}

// ─── Boursorama, for unlisted funds (#704) ──────────────────────────────────

const CARMIGNAC: &str = "FR0010135103";

/// The search row Boursorama answered for `FR0010135103`, captured on 2026-11-25.
const SEARCH_CARMIGNAC: &str = r#"<ul><li> <a class="search__list-link search__list-link--no-description" href="/bourse/opcvm/cours/MP-829413/" data-tag-commander-click='{"label" : "search-portal::trading", "s2": 2, "type" : "navigation"}'> <div> <div class="o-flex u-3/5"> <span class="search__item-title u-height-auto">Carmignac Patrimoine A EUR Acc</span> </div> <div class="o-flex"> <span class="u-color-neutral">OPCVM</span> </div> <div class="search__item-instrument o-vertical-interval-top@sm-max"> <span class="last">827,91 €</span> | <span class="u-color-positive">1,05 %</span> </div> </div> </a> </li></ul>"#;
/// The last three points of `GetTicksEOD?symbol=MP-829413&length=30`, captured on 2026-11-25.
const EOD_CARMIGNAC: &str = r#"{"d":{"Name":"Carmignac Patrimoine A EUR Acc","SymbolId":"MP-829413","Xperiod":0,"QuoteTab":[{"d":20776,"o":827.89,"h":827.89,"l":827.89,"c":827.89,"v":0},{"d":20777,"o":819.31,"h":819.31,"l":819.31,"c":819.31,"v":0},{"d":20780,"o":827.91,"h":827.91,"l":827.91,"c":827.91,"v":0}]}}"#;

fn boursorama(server: &MockServer) -> BoursoramaIsinPriceSource {
    BoursoramaIsinPriceSource::new(&server.uri(), clock().1)
}

async fn mount_carmignac(server: &MockServer, eod: ResponseTemplate) {
    Mock::given(method("GET"))
        .and(path("/recherche/"))
        .and(query_param("query", CARMIGNAC))
        .respond_with(
            ResponseTemplate::new(302)
                .insert_header("Location", "https://www.boursorama.com/cours/MP-829413/"),
        )
        .mount(server)
        .await;
    Mock::given(method("GET"))
        .and(path("/recherche/ajax"))
        .and(query_param("query", CARMIGNAC))
        .respond_with(ResponseTemplate::new(200).set_body_string(SEARCH_CARMIGNAC))
        .mount(server)
        .await;
    Mock::given(method("GET"))
        .and(path("/bourse/action/graph/ws/GetTicksEOD"))
        .and(query_param("symbol", "MP-829413"))
        // The shortest window Boursorama accepts: the quote, and a history reaching back two
        // days from the fixed clock (2026-11-22), both ask for 30 days.
        .and(query_param("length", "30"))
        .and(wiremock::matchers::header(
            "X-Requested-With",
            "XMLHttpRequest",
        ))
        .respond_with(eod)
        .mount(server)
        .await;
}

#[test]
fn boursorama_days_count_from_1970_and_a_close_is_the_c_field() {
    let closes = end_of_day_closes(&serde_json::from_str(EOD_CARMIGNAC).unwrap());

    assert_eq!(
        closes.into_iter().collect::<Vec<_>>(),
        [
            (day(2026, 11, 19), dec("827.89")),
            (day(2026, 11, 20), dec("819.31")),
            (day(2026, 11, 23), dec("827.91")),
        ]
    );
    assert!(end_of_day_closes(&json!([])).is_empty());
}

#[test]
fn boursorama_windows_are_the_lengths_it_accepts() {
    assert_eq!(boursorama_window(1), 30);
    assert_eq!(boursorama_window(31), 90);
    assert_eq!(boursorama_window(400), 730);
    assert_eq!(boursorama_window(50_000), 7300);
}

#[test]
fn a_search_row_counts_only_for_its_own_symbol_and_in_euros() {
    assert!(quoted_in_euros(SEARCH_CARMIGNAC, "MP-829413"));
    assert!(!quoted_in_euros(SEARCH_CARMIGNAC, "MP-000001"));
    let in_pounds = SEARCH_CARMIGNAC.replace("827,91 €", "3,6343 £");
    assert!(!quoted_in_euros(&in_pounds, "MP-829413"));
}

#[tokio::test]
async fn boursorama_prices_an_unlisted_fund_from_its_last_daily_nav() {
    let server = MockServer::start().await;
    mount_carmignac(&server, json_body(EOD_CARMIGNAC)).await;

    let quote = boursorama(&server).quote(CARMIGNAC).await.expect("a NAV");

    assert_eq!(quote.price_eur, dec("827.91"));
    assert_eq!(quote.as_of, Some(day(2026, 11, 23)));
    assert_eq!(quote.source, "Boursorama");
}

#[tokio::test]
async fn boursorama_history_keeps_the_days_asked_for() {
    let server = MockServer::start().await;
    mount_carmignac(&server, json_body(EOD_CARMIGNAC)).await;

    let history = boursorama(&server)
        .daily_history(CARMIGNAC, day(2026, 11, 20), day(2026, 11, 22))
        .await;

    assert_eq!(
        history.into_iter().collect::<Vec<_>>(),
        [(day(2026, 11, 20), dec("819.31"))]
    );
}

#[tokio::test]
async fn an_isin_boursorama_does_not_redirect_gets_no_price() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/recherche/"))
        .respond_with(ResponseTemplate::new(200).set_body_string("<html>Aucun résultat</html>"))
        .mount(&server)
        .await;

    assert_eq!(boursorama(&server).quote("FR0010135104").await, None);
    assert_eq!(server.received_requests().await.unwrap().len(), 1);
}

#[tokio::test]
async fn a_fund_boursorama_quotes_in_another_currency_gets_no_price() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/recherche/"))
        .respond_with(
            ResponseTemplate::new(302)
                .insert_header("Location", "https://www.boursorama.com/cours/MP-829413/"),
        )
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/recherche/ajax"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_string(SEARCH_CARMIGNAC.replace("827,91 €", "900,00 $")),
        )
        .mount(&server)
        .await;

    assert_eq!(boursorama(&server).quote(CARMIGNAC).await, None);
}

#[tokio::test]
async fn a_boursorama_403_pauses_the_source() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/recherche/"))
        .respond_with(ResponseTemplate::new(403))
        .mount(&server)
        .await;
    let source = boursorama(&server);

    assert_eq!(source.quote(CARMIGNAC).await, None);
    assert_eq!(source.quote(CARMIGNAC).await, None);
    assert_eq!(server.received_requests().await.unwrap().len(), 1);
}
