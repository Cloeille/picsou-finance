//! Dividend endpoints: the forecast from stored history and SCPI declarations, received
//! dividends, and the classification of recent credits.

use picsou::ledger::accounts::NewAccount;
use picsou::ledger::model::AccountType;
use picsou::testing::{Client, TestApp};
use serde_json::Value;

use super::support::{account, d};

struct Fixture {
    app: TestApp,
    admin: Client,
    member: i64,
}

impl Fixture {
    async fn new() -> Self {
        let app = TestApp::with_admin().await;
        let admin = app.login_admin().await;
        let member: i64 =
            picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
                .fetch_one(&app.state.db)
                .await
                .unwrap();
        Self { app, admin, member }
    }

    async fn sql(&self, statement: &str) {
        picsou::db::query(statement)
            .execute(&self.app.state.db)
            .await
            .unwrap();
    }

    async fn cto(&self, name: &str, provider: Option<&str>) -> i64 {
        let mut draft = NewAccount::new(self.member, name, AccountType::CompteTitres);
        draft.provider = provider.map(str::to_owned);
        account(&self.app, draft).await.id
    }

    async fn holding(&self, account_id: i64, ticker: &str, name: &str, quantity: &str) {
        picsou::db::query(
            "INSERT INTO account_holding (account_id, ticker, name, quantity, created_at, updated_at) \
             VALUES ($1, $2, $3, $4::numeric, now(), now())",
        )
        .bind(account_id)
        .bind(ticker)
        .bind(name)
        .bind(quantity)
        .execute(&self.app.state.db)
        .await
        .unwrap();
    }

    /// A source with (ex_date, amount_eur, payment_date, declared) events.
    async fn source(&self, ticker: &str, events: &[(&str, &str, Option<&str>, bool)]) {
        let id: i64 = picsou::db::query_scalar(
            "INSERT INTO dividend_source (ticker, currency, status) VALUES ($1, 'EUR', 'OK') RETURNING id",
        )
        .bind(ticker)
        .fetch_one(&self.app.state.db)
        .await
        .unwrap();
        for (ex, amount, payment, declared) in events {
            picsou::db::query(
                "INSERT INTO dividend_event (source_id, ex_date, amount, amount_eur, payment_date, declared) \
                 VALUES ($1, $2::date, $3::numeric, $3::numeric, $4::date, $5)",
            )
            .bind(id)
            .bind(*ex)
            .bind(*amount)
            .bind(*payment)
            .bind(*declared)
            .execute(&self.app.state.db)
            .await
            .unwrap();
        }
    }

    async fn upcoming(&self, query: &str) -> Vec<Value> {
        let response = self
            .admin
            .get(&format!("/api/dividends/upcoming{query}"))
            .send()
            .await;
        assert_eq!(response.status, 200, "{}", response.text());
        response.json().as_array().unwrap().clone()
    }
}

#[tokio::test]
async fn a_quarterly_payer_is_projected_and_an_announced_payment_replaces_its_projection() {
    let f = Fixture::new().await;
    let cto = f.cto("CTO", None).await;
    f.holding(cto, "TTE.PA", "TotalEnergies", "10").await;
    f.source(
        "TTE.PA",
        &[
            ("2025-04-01", "0.79", Some("2025-04-03"), false),
            ("2025-07-01", "0.79", Some("2025-07-03"), false),
            ("2025-10-01", "0.85", Some("2025-10-03"), false),
            ("2026-01-02", "0.85", Some("2026-01-05"), false),
        ],
    )
    .await;
    let lines = f.upcoming("?horizonDays=200").await;
    let dates: Vec<&str> = lines
        .iter()
        .map(|l| l["expectedDate"].as_str().unwrap())
        .collect();
    assert_eq!(dates, ["2026-04-02", "2026-07-02"]);
    let first = &lines[0];
    assert_eq!(first["basis"], "HISTORY_CADENCE");
    assert_eq!(first["cadence"], "QUARTERLY");
    assert_eq!(first["id"], format!("HOLDING:{cto}:TTE.PA:2026-04-02"));
    assert_eq!(first["paymentDate"], "2026-04-04");
    assert_eq!(first["paymentDateEstimated"], true);
    assert_eq!(d(&first["amountEur"].to_string()), d("8.5"));
    assert_eq!(first["countsInBudget"], true);

    // Clamped to 366 days, and an announced ex-date within 20 days replaces the projection.
    f.sql(
        "INSERT INTO dividend_event (source_id, ex_date, amount, amount_eur, declared) \
           SELECT id, '2026-04-10', 0.9, 0.9, true FROM dividend_source WHERE ticker = 'TTE.PA'",
    )
    .await;
    let lines = f.upcoming("?horizonDays=100").await;
    assert_eq!(lines.len(), 1);
    assert_eq!(lines[0]["basis"], "ANNOUNCED");
    assert_eq!(lines[0]["expectedDate"], "2026-04-10");
    assert_eq!(lines[0]["paymentDate"], "2026-04-12");
}

#[tokio::test]
async fn a_stale_or_irregular_history_is_not_projected() {
    let f = Fixture::new().await;
    let cto = f.cto("CTO", None).await;
    f.holding(cto, "OLD", "Old Corp", "5").await;
    f.source(
        "OLD",
        &[
            ("2024-03-01", "1", None, false),
            ("2025-03-01", "1", None, false),
        ],
    )
    .await;
    f.holding(cto, "ODD", "Odd Corp", "5").await;
    f.source(
        "ODD",
        &[
            ("2025-06-01", "1", None, false),
            ("2025-08-01", "1", None, false),
            ("2025-12-20", "1", None, false),
        ],
    )
    .await;
    assert!(
        f.upcoming("?horizonDays=366")
            .await
            .iter()
            .all(|l| l["ticker"] != "ODD")
    );
    let old: Vec<Value> = f
        .upcoming("?horizonDays=366")
        .await
        .into_iter()
        .filter(|l| l["ticker"] == "OLD")
        .collect();
    assert_eq!(old.len(), 1, "annual payer projected once: {old:?}");
    assert_eq!(old[0]["expectedDate"], "2026-03-01");
}

#[tokio::test]
async fn an_scpi_pays_its_declared_distribution_on_the_25th() {
    let f = Fixture::new().await;
    let mut draft = NewAccount::new(f.member, "Corum Origin", AccountType::Scpi);
    draft.is_manual = true;
    let scpi = account(&f.app, draft).await.id;
    picsou::db::query(
        "INSERT INTO scpi_position (account_id, member_id, isin, share_count, dividend_policy, \
         annual_distribution_per_share_eur, distribution_frequency) VALUES ($1, $2, 'FR0000000001', 10, 'CASH', 12, 'MONTHLY')",
    )
    .bind(scpi)
    .bind(f.member)
    .execute(&f.app.state.db)
    .await
    .unwrap();
    let lines = f.upcoming("?horizonDays=45").await;
    let dates: Vec<&str> = lines
        .iter()
        .map(|l| l["expectedDate"].as_str().unwrap())
        .collect();
    assert_eq!(dates, ["2026-01-25", "2026-02-25"]);
    assert_eq!(lines[0]["source"], "SCPI");
    assert_eq!(lines[0]["basis"], "SCPI_DECLARED");
    assert_eq!(lines[0]["amountPerShare"].to_string(), "1.00000000");
    assert_eq!(d(&lines[0]["amountEur"].to_string()), d("10"));
    assert_eq!(
        lines[0]["id"],
        format!("SCPI:{scpi}:FR0000000001:2026-01-25")
    );
}

#[tokio::test]
async fn an_unreadable_account_is_a_404() {
    let f = Fixture::new().await;
    let (_, other) = f.app.create_member("other", "password-1234").await;
    let foreign = account(
        &f.app,
        NewAccount::new(other, "CTO", AccountType::CompteTitres),
    )
    .await
    .id;
    let response = f
        .admin
        .get(&format!("/api/dividends/upcoming?accountId={foreign}"))
        .send()
        .await;
    assert_eq!(response.status, 404);
}

#[tokio::test]
async fn a_recent_credit_is_classified_once_then_listed_as_received() {
    let f = Fixture::new().await;
    let cto = f.cto("CTO", Some("Fortuneo")).await;
    f.holding(cto, "TTE.PA", "TotalEnergies", "10").await;
    f.source(
        "TTE.PA",
        &[("2026-01-02", "0.79", Some("2026-01-06"), false)],
    )
    .await;
    let credit: i64 = picsou::db::query_scalar(
        "INSERT INTO \"transaction\" (account_id, date, description, amount, native_currency, created_at, is_manual) \
         VALUES ($1, '2026-01-06', 'DIVIDENDE TOTALENERGIES', 7.90, 'EUR', now(), false) RETURNING id",
    )
    .bind(cto)
    .fetch_one(&f.app.state.db)
    .await
    .unwrap();
    let today = f.app.state.clock.today();
    let now = f.app.state.clock.now();
    let classifier = f.app.state.ledger.dividends.clone();
    assert_eq!(
        classifier
            .classify_recent(&f.app.state.db, f.member, today, now)
            .await
            .unwrap(),
        1
    );
    assert_eq!(
        classifier
            .classify_recent(&f.app.state.db, f.member, today, now)
            .await
            .unwrap(),
        0
    );
    let (tx_type, ticker, slug): (String, String, String) = picsou::db::query_as(
        "SELECT t.tx_type, t.ticker, c.slug FROM \"transaction\" t JOIN category c ON c.id = t.category_id WHERE t.id = $1",
    )
    .bind(credit)
    .fetch_one(&f.app.state.db)
    .await
    .unwrap();
    assert_eq!(
        (tx_type.as_str(), ticker.as_str(), slug.as_str()),
        ("DIVIDEND", "TTE.PA", "revenus-capital")
    );

    let body = f
        .admin
        .get("/api/dividends/received?days=30")
        .send()
        .await
        .json();
    let row = &body[0];
    assert_eq!(row["id"], format!("HOLDING:{cto}:TTE.PA:2026-01-02"));
    assert_eq!(row["transactionId"], credit);
    assert_eq!(row["exDate"], "2026-01-02");
    assert_eq!(row["amountPerShare"].to_string(), "0.79000000");
    assert_eq!(row["countsInBudget"], true);
}

#[tokio::test]
async fn the_history_refresh_stores_yahoo_events_in_eur_with_nasdaqs_declared_dividend() {
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    let yahoo = MockServer::start().await;
    let nasdaq = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/KO"))
        .respond_with(ResponseTemplate::new(200).set_body_raw(
            r#"{"chart":{"result":[{"meta":{"currency":"USD","regularMarketPrice":60.0},
               "events":{"dividends":{"1":{"amount":0.485,"date":1757289600},"2":{"amount":0.51,"date":1764892800}}}}]}}"#,
            "application/json",
        ))
        .mount(&yahoo)
        .await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/USDEUR=X"))
        .respond_with(ResponseTemplate::new(200).set_body_raw(
            r#"{"chart":{"result":[{"meta":{"currency":"EUR","regularMarketPrice":0.9}}]}}"#,
            "application/json",
        ))
        .mount(&yahoo)
        .await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/FAIL.PA"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&yahoo)
        .await;
    Mock::given(method("GET"))
        .and(path("/api/quote/KO/dividends"))
        .respond_with(ResponseTemplate::new(200).set_body_raw(
            r#"{"data":{"dividends":{"rows":[
                {"exOrEffDate":"12/05/2025","paymentDate":"12/15/2025","amount":"$0.51","currency":"USD"},
                {"exOrEffDate":"03/02/2026","paymentDate":"04/01/2026","amount":"$0.53","currency":"USD"}]}},"status":{"rCode":200}}"#,
            "application/json",
        ))
        .mount(&nasdaq)
        .await;
    let app = TestApp::builder()
        .with_admin()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .env("NASDAQ_BASE_URL", nasdaq.uri())
        .spawn()
        .await;
    let tickers = vec![
        "ko".to_owned(),
        "FAIL.PA".to_owned(),
        "GOLD:GRAM".to_owned(),
    ];
    let refreshed = picsou::wealth::dividends::history::refresh_stale(&app.state, &tickers, 10)
        .await
        .unwrap();
    assert_eq!(refreshed, 1);
    let rows: Vec<(String, String, Option<String>, bool)> = picsou::db::query_as(
        "SELECT e.ex_date::text, e.amount_eur::text, e.payment_date::text, e.declared FROM dividend_event e \
         JOIN dividend_source s ON s.id = e.source_id WHERE s.ticker = 'KO' ORDER BY e.ex_date",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (
                "2025-09-08".to_owned(),
                "0.43650000".to_owned(),
                None,
                false
            ),
            (
                "2025-12-05".to_owned(),
                "0.45900000".to_owned(),
                Some("2025-12-15".to_owned()),
                false
            ),
            (
                "2026-03-02".to_owned(),
                "0.47700000".to_owned(),
                Some("2026-04-01".to_owned()),
                true
            ),
        ]
    );
    let statuses: Vec<(String, String)> =
        picsou::db::query_as("SELECT ticker, status FROM dividend_source ORDER BY ticker")
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(
        statuses,
        vec![
            ("FAIL.PA".to_owned(), "FAILED".to_owned()),
            ("KO".to_owned(), "OK".to_owned())
        ]
    );
    // Fresh sources are not asked again.
    assert_eq!(
        picsou::wealth::dividends::history::refresh_stale(&app.state, &tickers, 10)
            .await
            .unwrap(),
        0
    );
}

#[tokio::test]
async fn a_credit_that_does_not_fit_the_amount_is_left_alone() {
    let f = Fixture::new().await;
    let cto = f.cto("CTO", None).await;
    f.holding(cto, "TTE.PA", "TotalEnergies", "10").await;
    f.source("TTE.PA", &[("2026-01-02", "0.79", None, false)])
        .await;
    f.sql(&format!(
        "INSERT INTO \"transaction\" (account_id, date, description, amount, native_currency, created_at, is_manual) \
         VALUES ({cto}, '2026-01-06', 'DIVIDENDE TOTALENERGIES', 20, 'EUR', now(), false)"
    ))
    .await;
    let classifier = f.app.state.ledger.dividends.clone();
    let n = classifier
        .classify_recent(
            &f.app.state.db,
            f.member,
            f.app.state.clock.today(),
            f.app.state.clock.now(),
        )
        .await
        .unwrap();
    assert_eq!(n, 0);
}

#[tokio::test]
async fn classify_recent_rolls_back_all_updates_when_one_update_fails() {
    let f = Fixture::new().await;
    let cto = f.cto("CTO", Some("Fortuneo")).await;
    f.holding(cto, "TTE.PA", "TotalEnergies", "10").await;
    f.source(
        "TTE.PA",
        &[("2026-01-02", "0.79", Some("2026-01-06"), false)],
    )
    .await;
    let mut ids = Vec::new();
    for label in ["DIVIDENDE TOTALENERGIES 1", "DIVIDENDE TOTALENERGIES 2"] {
        let id: i64 = picsou::db::query_scalar(
            "INSERT INTO \"transaction\" (account_id, date, description, amount, native_currency, created_at, is_manual) \
             VALUES ($1, '2026-01-06', $2, 7.90, 'EUR', now(), false) RETURNING id",
        )
        .bind(cto)
        .bind(label)
        .fetch_one(&f.app.state.db)
        .await
        .unwrap();
        ids.push(id);
    }
    let (first, second) = (ids[0], ids[1]);
    if f.app.state.db.is_sqlite() {
        f.sql(&format!(
            "CREATE TRIGGER trg_fail_second_tx BEFORE UPDATE ON \"transaction\" \
             WHEN NEW.id = {second} BEGIN SELECT RAISE(ABORT, 'simulated failure'); END"
        ))
        .await;
    } else {
        f.sql(&format!(
            "CREATE OR REPLACE FUNCTION fail_second_tx() RETURNS trigger AS $$ \
             BEGIN IF NEW.id = {second} THEN RAISE EXCEPTION 'simulated failure'; END IF; \
             RETURN NEW; END; $$ LANGUAGE plpgsql"
        ))
        .await;
        f.sql(
            "CREATE TRIGGER trg_fail_second_tx BEFORE UPDATE ON \"transaction\" \
             FOR EACH ROW EXECUTE FUNCTION fail_second_tx()",
        )
        .await;
    }

    let classifier = f.app.state.ledger.dividends.clone();
    let err = classifier
        .classify_recent(
            &f.app.state.db,
            f.member,
            f.app.state.clock.today(),
            f.app.state.clock.now(),
        )
        .await;
    assert!(err.is_err(), "expected update failure on second credit");

    let (tx_type, ticker, category_id): (Option<String>, Option<String>, Option<i64>) =
        picsou::db::query_as(
            "SELECT tx_type, ticker, category_id FROM \"transaction\" WHERE id = $1",
        )
        .bind(first)
        .fetch_one(&f.app.state.db)
        .await
        .unwrap();
    assert_eq!(
        (tx_type, ticker, category_id),
        (None, None, None),
        "first credit must be rolled back when the second update fails"
    );
}
