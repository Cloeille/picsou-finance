//! Peer-loan endpoints: terms, repayments and their cash legs, closure, simulation, overview.

use picsou::ledger::accounts::NewAccount;
use picsou::ledger::model::AccountType;
use picsou::testing::{Client, TestApp};
use serde_json::{Value, json};

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

    async fn account(&self, name: &str, kind: AccountType, manual: bool, currency: &str) -> i64 {
        let mut draft = NewAccount::new(self.member, name, kind);
        draft.is_manual = manual;
        draft.currency = currency.to_owned();
        account(&self.app, draft).await.id
    }

    /// A peer loan of 1000 lent on 2026-01-05 from a manual checking account.
    async fn loan(&self, rate: f64) -> (i64, i64) {
        let loan = self
            .account("Alice", AccountType::PeerLoan, true, "EUR")
            .await;
        let cash = self
            .account("Courant", AccountType::Checking, true, "EUR")
            .await;
        let response = self
            .admin
            .put(&format!("/api/accounts/{loan}/peer-loan"))
            .json(&json!({"principal": 1000, "lentAt": "2026-01-05", "annualRate": rate, "linkedAccountId": cash}))
            .send()
            .await;
        assert_eq!(response.status, 200, "{}", response.text());
        (loan, cash)
    }

    async fn repay(&self, loan: i64, body: Value) -> picsou::testing::Response {
        self.admin
            .post(&format!("/api/accounts/{loan}/peer-loan/repayments"))
            .json(&body)
            .send()
            .await
    }

    async fn detail(&self, loan: i64) -> Value {
        let response = self
            .admin
            .get(&format!("/api/accounts/{loan}/peer-loan"))
            .send()
            .await;
        assert_eq!(response.status, 200, "{}", response.text());
        response.json()
    }

    async fn cash_rows(&self, account_id: i64) -> Vec<(String, String, Option<String>)> {
        sqlx::query_as(
            "SELECT t.description, t.amount::text, c.slug FROM \"transaction\" t LEFT JOIN category c ON c.id = t.category_id \
             WHERE t.account_id = $1 ORDER BY t.id",
        )
        .bind(account_id)
        .fetch_all(&self.app.state.db)
        .await
        .unwrap()
    }
}

fn num(value: &Value) -> String {
    value.to_string()
}

fn detail_of(response: &picsou::testing::Response) -> String {
    response.json()["detail"]
        .as_str()
        .unwrap_or_default()
        .to_owned()
}

#[tokio::test]
async fn get_answers_an_unconfigured_loan_refuses_another_type_and_hides_foreign_accounts() {
    let f = Fixture::new().await;
    let mut draft = NewAccount::new(f.member, "Bob", AccountType::PeerLoan);
    draft.current_balance = d("250.50");
    let loan = account(&f.app, draft).await.id;
    let body = f.detail(loan).await;
    assert_eq!(body["configured"], false);
    assert_eq!(num(&body["principal"]), "250.50000000");
    assert_eq!(body["ratePeriod"], "ANNUAL");
    assert_eq!(num(&body["principalRemaining"]), "0");
    assert!(body.get("scheduledRemaining").is_none());
    assert_eq!(body["repayments"], json!([]));

    let checking = f
        .account("Courant", AccountType::Checking, true, "EUR")
        .await;
    let response = f
        .admin
        .get(&format!("/api/accounts/{checking}/peer-loan"))
        .send()
        .await;
    assert_eq!(response.status, 400);
    assert_eq!(detail_of(&response), "Account is not a peer loan");

    let (_, other) = f.app.create_member("other", "password-1234").await;
    let mut foreign = NewAccount::new(other, "Foreign", AccountType::PeerLoan);
    foreign.is_manual = true;
    let foreign = account(&f.app, foreign).await.id;
    let response = f
        .admin
        .get(&format!("/api/accounts/{foreign}/peer-loan"))
        .send()
        .await;
    assert_eq!(response.status, 404);
    assert_eq!(detail_of(&response), "Account not found");
}

#[tokio::test]
async fn save_terms_writes_the_outflow_on_a_manual_linked_account_once() {
    let f = Fixture::new().await;
    let (loan, cash) = f.loan(0.0).await;
    let rows = f.cash_rows(cash).await;
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].0, "Prêt — Alice");
    assert_eq!(d(&rows[0].1), d("-1000"));
    assert_eq!(rows[0].2.as_deref(), Some("virement-interne"));

    let body = f.detail(loan).await;
    assert_eq!(body["configured"], true);
    assert_eq!(num(&body["principalRemaining"]), "1000.00");
    assert_eq!(body["linkedAccountName"], "Courant");
    assert_eq!(num(&body["annualRate"]), "0.000000");

    // Editing the terms never writes the outflow again.
    let response = f
        .admin
        .put(&format!("/api/accounts/{loan}/peer-loan"))
        .json(&json!({"principal": 1000, "lentAt": "2026-01-05", "note": "edited", "linkedAccountId": cash}))
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    assert_eq!(response.json()["note"], "edited");
    assert_eq!(f.cash_rows(cash).await.len(), 1);

    // Once the cash movement exists its amount is a fact.
    let response = f
        .admin
        .put(&format!("/api/accounts/{loan}/peer-loan"))
        .json(&json!({"principal": 900, "lentAt": "2026-01-05", "linkedAccountId": cash}))
        .send()
        .await;
    assert_eq!(response.status, 400);
    assert!(detail_of(&response).starts_with("The amount cannot change"));
}

#[tokio::test]
async fn save_terms_without_cash_leg_or_on_a_synced_account_writes_no_transaction() {
    let f = Fixture::new().await;
    let loan = f.account("Alice", AccountType::PeerLoan, true, "EUR").await;
    let cash = f
        .account("Courant", AccountType::Checking, true, "EUR")
        .await;
    let response = f
        .admin
        .put(&format!("/api/accounts/{loan}/peer-loan"))
        .json(&json!({"principal": 500, "lentAt": "2026-01-05", "linkedAccountId": cash, "withCashLeg": false}))
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    assert!(f.cash_rows(cash).await.is_empty());

    let loan2 = f.account("Bob", AccountType::PeerLoan, true, "EUR").await;
    let synced = f
        .account("Banque", AccountType::Checking, false, "EUR")
        .await;
    let response = f
        .admin
        .put(&format!("/api/accounts/{loan2}/peer-loan"))
        .json(&json!({"principal": 500, "lentAt": "2026-01-05", "linkedAccountId": synced}))
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    assert!(f.cash_rows(synced).await.is_empty());
}

#[tokio::test]
async fn save_terms_validates_the_body_the_plan_the_dates_and_the_cash_account() {
    let f = Fixture::new().await;
    let loan = f.account("Alice", AccountType::PeerLoan, true, "EUR").await;
    let cash = f
        .account("Courant", AccountType::Checking, true, "EUR")
        .await;
    let usd = f
        .account("Dollars", AccountType::Checking, true, "USD")
        .await;
    let put = |body: Value| {
        let client = &f.admin;
        async move {
            client
                .put(&format!("/api/accounts/{loan}/peer-loan"))
                .json(&body)
                .send()
                .await
        }
    };

    let response = put(json!({"principal": 0, "annualRate": 2000, "plannedMonths": 5000})).await;
    assert_eq!(response.status, 422);
    let errors = &response.json()["errors"];
    assert_eq!(errors["principal"], "Principal must be greater than zero");
    assert_eq!(errors["lentAt"], "must not be null");
    assert_eq!(errors["linkedAccountId"], "must not be null");
    assert_eq!(errors["annualRate"], "The rate is too high");
    assert_eq!(
        errors["plannedMonths"],
        "must be less than or equal to 1200"
    );

    let response =
        put(json!({"principal": 10.123, "lentAt": "2026-01-05", "linkedAccountId": cash})).await;
    assert_eq!(response.status, 422);
    assert_eq!(
        response.json()["errors"]["principal"],
        "Principal is too large"
    );

    let cases = [
        (
            json!({"principal": 10, "lentAt": "2026-01-05", "linkedAccountId": cash, "plannedMonths": 3}),
            "A plan needs both a monthly amount and a month count",
        ),
        (
            json!({"principal": 10, "lentAt": "2026-02-01", "linkedAccountId": cash}),
            "The lending date cannot be in the future",
        ),
        (
            json!({"principal": 10, "lentAt": "1899-12-31", "linkedAccountId": cash}),
            "The lending date is too far in the past",
        ),
        (
            json!({"principal": 10, "lentAt": "2026-01-05", "expectedBack": "2026-01-01", "linkedAccountId": cash}),
            "The expected return date cannot precede the lending date",
        ),
        (
            json!({"principal": 10, "lentAt": "2026-01-05", "linkedAccountId": loan}),
            "A loan cannot be linked to itself",
        ),
        (
            json!({"principal": 10, "lentAt": "2026-01-05", "linkedAccountId": usd}),
            "Only a EUR cash account can be linked to a peer loan (this one is in USD)",
        ),
    ];
    for (body, message) in cases {
        let response = put(body).await;
        assert_eq!(response.status, 400, "{message}");
        assert_eq!(detail_of(&response), message);
    }
    let response =
        put(json!({"principal": 10, "lentAt": "2026-01-05", "linkedAccountId": 999999})).await;
    assert_eq!(response.status, 404);
}

#[tokio::test]
async fn repayments_write_their_cash_leg_and_respect_the_cap() {
    let f = Fixture::new().await;
    let (loan, cash) = f.loan(0.0).await;

    let response = f
        .repay(loan, json!({"paidOn": "2026-01-10", "amount": 1000.01}))
        .await;
    assert_eq!(response.status, 400);
    assert_eq!(
        detail_of(&response),
        "A repayment cannot exceed 1000.00 EUR (accrued interest plus remaining capital)"
    );

    let response = f
        .repay(
            loan,
            json!({"paidOn": "2026-01-10", "amount": 400.5, "note": "first"}),
        )
        .await;
    assert_eq!(response.status, 201, "{}", response.text());
    let created = response.json();
    assert_eq!(num(&created["amount"]), "400.5");
    assert_eq!(created["kind"], "PRINCIPAL");
    assert_eq!(created["hasCashLeg"], true);
    assert_eq!(created["cashLegOwned"], true);
    assert!(created.get("interestPart").is_none());
    let rows = f.cash_rows(cash).await;
    assert_eq!(rows[1].0, "Remboursement — Alice");
    assert_eq!(rows[1].2.as_deref(), Some("virement-interne"));

    let body = f.detail(loan).await;
    assert_eq!(num(&body["principalRemaining"]), "599.50");
    assert_eq!(num(&body["repaidPct"]), "40.05");
    assert_eq!(num(&body["repayments"][0]["amount"]), "400.50000000");
    assert_eq!(num(&body["repayments"][0]["capitalPart"]), "400.50");

    for (body, message) in [
        (
            json!({"paidOn": "2026-01-10", "amount": 10, "kind": "WRITE_OFF"}),
            "A write-off is recorded by closing the loan, not by adding a repayment",
        ),
        (
            json!({"paidOn": "2026-01-16", "amount": 10}),
            "A repayment date cannot be in the future",
        ),
        (
            json!({"paidOn": "1899-12-31", "amount": 10}),
            "A repayment date is too far in the past",
        ),
        (
            json!({"paidOn": "2026-01-10", "amount": 10, "kind": "INTEREST"}),
            "An interest repayment cannot exceed the accrued interest of 0.00 EUR",
        ),
    ] {
        let response = f.repay(loan, body).await;
        assert_eq!(response.status, 400, "{message}");
        assert_eq!(detail_of(&response), message);
    }

    let response = f
        .repay(
            loan,
            json!({"paidOn": "2026-01-11", "amount": 50, "withCashLeg": false}),
        )
        .await;
    assert_eq!(response.status, 201);
    assert_eq!(response.json()["hasCashLeg"], false);
    assert_eq!(f.cash_rows(cash).await.len(), 2);
}

#[tokio::test]
async fn interest_accrues_at_actual_365_and_caps_an_interest_repayment() {
    let f = Fixture::new().await;
    let loan = f.account("Alice", AccountType::PeerLoan, true, "EUR").await;
    let cash = f
        .account("Courant", AccountType::Checking, false, "EUR")
        .await;
    let response = f
        .admin
        .put(&format!("/api/accounts/{loan}/peer-loan"))
        .json(&json!({"principal": 1000, "lentAt": "2025-01-15", "annualRate": 0.1, "linkedAccountId": cash}))
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    assert_eq!(num(&response.json()["interestAccrued"]), "100.00");

    let response = f
        .repay(
            loan,
            json!({"paidOn": "2026-01-15", "amount": 100.01, "kind": "INTEREST"}),
        )
        .await;
    assert_eq!(response.status, 400);
    assert_eq!(
        detail_of(&response),
        "An interest repayment cannot exceed the accrued interest of 100.00 EUR"
    );
    let response = f
        .repay(loan, json!({"paidOn": "2026-01-15", "amount": 150}))
        .await;
    assert_eq!(response.status, 201);
    let body = f.detail(loan).await;
    assert_eq!(num(&body["interestReceived"]), "100.00");
    assert_eq!(num(&body["principalRemaining"]), "950.00");
    assert_eq!(num(&body["repayments"][0]["interestPart"]), "100.00");
    assert_eq!(num(&body["repayments"][0]["capitalPart"]), "50.00");
    assert_eq!(num(&body["totalRepaid"]), "150.00");
}

#[tokio::test]
async fn the_repayment_that_clears_the_capital_closes_the_loan_and_deleting_it_reopens() {
    let f = Fixture::new().await;
    let (loan, _) = f.loan(0.0).await;
    let response = f
        .repay(loan, json!({"paidOn": "2026-01-12", "amount": 1000}))
        .await;
    assert_eq!(response.status, 201);
    let repayment = response.json()["id"].as_i64().unwrap();
    let body = f.detail(loan).await;
    assert_eq!(body["closedAt"], "2026-01-12");

    let response = f
        .repay(loan, json!({"paidOn": "2026-01-13", "amount": 1}))
        .await;
    assert_eq!(response.status, 400);
    assert_eq!(
        detail_of(&response),
        "This loan is closed. Delete its write-off to reopen it before recording a repayment."
    );

    let response = f
        .admin
        .delete(&format!(
            "/api/accounts/{loan}/peer-loan/repayments/{repayment}"
        ))
        .send()
        .await;
    assert_eq!(response.status, 204);
    let body = f.detail(loan).await;
    assert!(body.get("closedAt").is_none());
    assert_eq!(num(&body["principalRemaining"]), "1000.00");
}

#[tokio::test]
async fn close_writes_the_residual_off_ignoring_later_repayments() {
    let f = Fixture::new().await;
    let (loan, _) = f.loan(0.0).await;
    f.repay(loan, json!({"paidOn": "2026-01-12", "amount": 100}))
        .await;

    let response = f
        .admin
        .post(&format!(
            "/api/accounts/{loan}/peer-loan/close?date=2026-01-01"
        ))
        .send()
        .await;
    assert_eq!(response.status, 400);
    assert_eq!(
        detail_of(&response),
        "The closure date cannot precede the lending date"
    );

    let response = f
        .admin
        .post(&format!(
            "/api/accounts/{loan}/peer-loan/close?date=2026-01-10"
        ))
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    let body = response.json();
    assert_eq!(body["closedAt"], "2026-01-10");
    let write_off = &body["repayments"][0];
    assert_eq!(write_off["kind"], "WRITE_OFF");
    assert_eq!(num(&write_off["amount"]), "1000.00");
    assert_eq!(write_off["note"], "Closure write-off");
    assert_eq!(num(&body["writtenOff"]), "1000.00");

    let response = f
        .admin
        .post(&format!("/api/accounts/{loan}/peer-loan/close"))
        .send()
        .await;
    assert_eq!(response.status, 200);
    assert_eq!(response.json()["closedAt"], "2026-01-10");

    let response = f
        .admin
        .post(&format!("/api/accounts/{loan}/peer-loan/close?date=abc"))
        .send()
        .await;
    assert_eq!(response.status, 400);
    assert_eq!(
        detail_of(&response),
        "Failed to convert 'date' with value: 'abc'"
    );
}

#[tokio::test]
async fn a_backdated_repayment_recomputes_every_later_snapshot() {
    let mut f = Fixture::new().await;
    let (loan, _) = f.loan(0.0).await;
    f.app
        .clock
        .advance(std::time::Duration::from_secs(3 * 86_400));
    f.admin = f.app.login_admin().await;
    let r = f
        .repay(loan, json!({"paidOn": "2026-01-17", "amount": 100}))
        .await;
    assert_eq!(r.status, 201, "{}", r.text());
    let r = f
        .repay(loan, json!({"paidOn": "2026-01-07", "amount": 200}))
        .await;
    assert_eq!(r.status, 201, "{}", r.text());

    let rows: Vec<(chrono::NaiveDate, String)> = sqlx::query_as(
        "SELECT date, balance::text FROM balance_snapshot WHERE account_id = $1 ORDER BY date",
    )
    .bind(loan)
    .fetch_all(&f.app.state.db)
    .await
    .unwrap();
    let rows: Vec<(String, rust_decimal::Decimal)> = rows
        .into_iter()
        .map(|(date, b)| (date.to_string(), d(&b)))
        .collect();
    assert_eq!(
        rows,
        vec![
            ("2026-01-05".to_owned(), d("1000")),
            ("2026-01-07".to_owned(), d("800")),
            ("2026-01-17".to_owned(), d("700")),
        ]
    );
}

#[tokio::test]
async fn updating_a_repayment_moves_its_cash_leg_and_refuses_linked_rows() {
    let f = Fixture::new().await;
    let (loan, cash) = f.loan(0.0).await;
    let created = f
        .repay(loan, json!({"paidOn": "2026-01-10", "amount": 100}))
        .await
        .json();
    let id = created["id"].as_i64().unwrap();
    let response = f
        .admin
        .put(&format!("/api/accounts/{loan}/peer-loan/repayments/{id}"))
        .json(&json!({"paidOn": "2026-01-11", "amount": 150, "withCashLeg": false}))
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    assert_eq!(response.json()["hasCashLeg"], false);
    assert_eq!(f.cash_rows(cash).await.len(), 1);

    let response = f
        .admin
        .put(&format!("/api/accounts/{loan}/peer-loan/repayments/{id}"))
        .json(&json!({"paidOn": "2026-01-11", "amount": 150, "kind": "WRITE_OFF"}))
        .send()
        .await;
    assert_eq!(response.status, 400);

    let response = f
        .admin
        .put(&format!("/api/accounts/{loan}/peer-loan/repayments/999999"))
        .json(&json!({"paidOn": "2026-01-11", "amount": 150}))
        .send()
        .await;
    assert_eq!(response.status, 404);
    assert_eq!(detail_of(&response), "Repayment not found");
}

async fn bank_row(f: &Fixture, account_id: i64, amount: &str, description: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO \"transaction\" (account_id, date, description, amount, native_currency, created_at, is_manual) \
         VALUES ($1, '2026-01-08', $2, $3::numeric, 'EUR', now(), false) RETURNING id",
    )
    .bind(account_id)
    .bind(description)
    .bind(amount)
    .fetch_one(&f.app.state.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn a_loan_and_a_repayment_link_existing_transactions_without_copying_them() {
    let f = Fixture::new().await;
    let bank = f
        .account("Banque", AccountType::Checking, false, "EUR")
        .await;
    let out = bank_row(&f, bank, "-300", "VIR Carol").await;
    let incoming = bank_row(&f, bank, "120", "VIR Carol retour").await;

    let response = f
        .admin
        .post("/api/peer-loans/from-transaction")
        .json(&json!({"transactionId": incoming}))
        .send()
        .await;
    assert_eq!(response.status, 400);
    assert_eq!(
        detail_of(&response),
        "Only an outgoing transaction can fund a peer loan"
    );

    let response = f
        .admin
        .post("/api/peer-loans/from-transaction")
        .json(&json!({"transactionId": out, "beneficiary": "  Carol  "}))
        .send()
        .await;
    assert_eq!(response.status, 201, "{}", response.text());
    let created = response.json();
    assert_eq!(created["accountName"], "Carol");
    assert_eq!(created["lentAt"], "2026-01-08");
    let loan = created["accountId"].as_i64().unwrap();

    let response = f
        .admin
        .post("/api/peer-loans/from-transaction")
        .json(&json!({"transactionId": out}))
        .send()
        .await;
    assert_eq!(
        detail_of(&response),
        "This transaction is already linked to a peer loan"
    );

    let response = f
        .admin
        .post(&format!(
            "/api/accounts/{loan}/peer-loan/repayments/from-transaction"
        ))
        .json(&json!({"transactionId": incoming}))
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    let body = response.json();
    assert_eq!(num(&body["principalRemaining"]), "180.00");
    assert_eq!(body["repayments"][0]["cashLegOwned"], false);
    assert_eq!(f.cash_rows(bank).await.len(), 2);
    assert!(
        f.cash_rows(bank)
            .await
            .iter()
            .all(|r| r.2.as_deref() == Some("virement-interne"))
    );

    // Deleting a linked repayment only unlinks the transaction.
    let id = body["repayments"][0]["id"].as_i64().unwrap();
    let response = f
        .admin
        .delete(&format!("/api/accounts/{loan}/peer-loan/repayments/{id}"))
        .send()
        .await;
    assert_eq!(response.status, 204);
    assert_eq!(f.cash_rows(bank).await.len(), 2);

    let (_, other) = f.app.create_member("other", "password-1234").await;
    let mut foreign = NewAccount::new(other, "Foreign", AccountType::Checking);
    foreign.is_manual = true;
    let foreign = account(&f.app, foreign).await.id;
    let foreign_row = bank_row(&f, foreign, "-50", "x").await;
    let response = f
        .admin
        .post("/api/peer-loans/from-transaction")
        .json(&json!({"transactionId": foreign_row}))
        .send()
        .await;
    assert_eq!(response.status, 404);
    assert_eq!(detail_of(&response), "Transaction not found");
}

#[tokio::test]
async fn simulate_needs_exactly_one_direction() {
    let f = Fixture::new().await;
    let simulate = |body: Value| {
        let client = &f.admin;
        async move {
            client
                .post("/api/peer-loans/simulate")
                .json(&body)
                .send()
                .await
        }
    };
    let response = simulate(json!({"principal": 1200})).await;
    assert_eq!(response.status, 400);
    assert_eq!(
        detail_of(&response),
        "Provide exactly one of months or monthlyPayment"
    );
    let response = simulate(json!({"principal": 1200, "months": 12, "monthlyPayment": 100})).await;
    assert_eq!(response.status, 400);

    let response = simulate(json!({"principal": 1200, "months": 12})).await;
    assert_eq!(response.status, 200, "{}", response.text());
    let body = response.json();
    assert_eq!(num(&body["monthlyPayment"]), "100.00");
    assert_eq!(body["months"], 12);
    assert_eq!(num(&body["totalInterest"]), "0.00");

    let response = simulate(json!({"principal": 1200, "monthlyPayment": 500})).await;
    assert_eq!(response.json()["months"], 3);

    let response = simulate(json!({"principal": 1200, "months": 1201})).await;
    assert_eq!(response.status, 422);
    assert_eq!(
        response.json()["errors"]["months"],
        "must be less than or equal to 1200"
    );
}

#[tokio::test]
async fn open_loans_and_the_overview_list_configured_loans_open_first() {
    let f = Fixture::new().await;
    let response = f.admin.get("/api/peer-loans/overview").send().await;
    assert_eq!(response.status, 200);
    let body = response.json();
    assert_eq!(body["history"], json!([]));
    assert_eq!(num(&body["totalOutstanding"]), "0");

    let (zed, cash) = f.loan(0.0).await;
    let bob = f.account("bob", AccountType::PeerLoan, true, "EUR").await;
    let response = f
        .admin
        .put(&format!("/api/accounts/{bob}/peer-loan"))
        .json(&json!({"principal": 200, "lentAt": "2026-01-05", "linkedAccountId": cash}))
        .send()
        .await;
    assert_eq!(response.status, 200);
    sqlx::query("UPDATE account SET name = 'zed' WHERE id = $1")
        .bind(zed)
        .execute(&f.app.state.db)
        .await
        .unwrap();

    let open = f.admin.get("/api/peer-loans/open").send().await.json();
    let names: Vec<&str> = open
        .as_array()
        .unwrap()
        .iter()
        .map(|l| l["name"].as_str().unwrap())
        .collect();
    assert_eq!(names, ["bob", "zed"]);
    assert_eq!(num(&open[0]["remaining"]), "200.00");

    // The outstanding curve reads track T2's wired history service.
    let response = f
        .admin
        .get("/api/peer-loans/overview?months=6")
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    let overview = response.json();
    assert!(overview["history"].is_array());
}
