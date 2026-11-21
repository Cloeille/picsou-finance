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
            sqlx::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
                .fetch_one(&app.state.db)
                .await
                .unwrap();
        Self { app, admin, member }
    }

    async fn sql(&self, statement: &str) {
        sqlx::query(statement)
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
        sqlx::query(
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
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO dividend_source (ticker, currency, status) VALUES ($1, 'EUR', 'OK') RETURNING id",
        )
        .bind(ticker)
        .fetch_one(&self.app.state.db)
        .await
        .unwrap();
        for (ex, amount, payment, declared) in events {
            sqlx::query(
                "INSERT INTO dividend_event (source_id, ex_date, amount, amount_eur, payment_date, declared) \
                 VALUES ($1, $2::date, $3::numeric, $3::numeric, $4::date, $5)",
            )
            .bind(id)
            .bind(ex)
            .bind(amount)
            .bind(payment)
            .bind(declared)
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
    sqlx::query(
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
    let credit: i64 = sqlx::query_scalar(
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
    let (tx_type, ticker, slug): (String, String, String) = sqlx::query_as(
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
