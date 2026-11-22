//! Finary XLSX import (FinaryImportServiceTest, FinaryPersistenceHelperTest, FinaryAccountMatcherTest
//! as the file import reaches them).

use chrono::{Duration, NaiveDate};
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::model::AccountType;
use picsou::testing::{Client, Response, TestApp};
use rust_decimal::Decimal;
use rust_xlsxwriter::Workbook;
use serde_json::{Value, json};

use super::support::{admin_member, file, now};

enum C {
    S(&'static str),
    N(f64),
    Empty,
}

/// An XLSX with the given sheets; strings go through the shared-strings table.
fn xlsx(sheets: &[(&str, Vec<Vec<C>>)]) -> Vec<u8> {
    let mut workbook = Workbook::new();
    for (name, rows) in sheets {
        let sheet = workbook.add_worksheet();
        sheet.set_name(*name).unwrap();
        for (r, row) in rows.iter().enumerate() {
            for (c, cell) in row.iter().enumerate() {
                match cell {
                    C::S(s) => {
                        sheet.write_string(r as u32, c as u16, *s).unwrap();
                    }
                    C::N(n) => {
                        sheet.write_number(r as u32, c as u16, *n).unwrap();
                    }
                    C::Empty => {}
                }
            }
        }
    }
    workbook.save_to_buffer().unwrap()
}

fn header() -> Vec<C> {
    vec![
        C::S("Name"),
        C::S("Institution"),
        C::S("Balance"),
        C::S("Currency"),
    ]
}

fn account_row(name: &'static str, balance: f64, currency: &'static str) -> Vec<C> {
    vec![C::S(name), C::S("Bank"), C::N(balance), C::S(currency)]
}

fn tx_row(date: String, label: &'static str, amount: f64, account: &'static str) -> Vec<C> {
    let date: &'static str = Box::leak(date.into_boxed_str());
    vec![
        C::S("cat"),
        C::S(date),
        C::S(label),
        C::N(amount),
        C::S("debit"),
        C::S(account),
        C::S("Bank"),
        C::S("EUR"),
    ]
}

fn day(app: &TestApp, offset: i64) -> NaiveDate {
    app.state.clock.today() + Duration::days(offset)
}

async fn preview(client: &Client, bytes: &[u8]) -> Response {
    let (content_type, body) = file(
        "finary.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        bytes,
    );
    client
        .post("/api/finary/preview")
        .body(&content_type, body)
        .send()
        .await
}

async fn import(client: &Client, body: Value) -> Response {
    client.post("/api/finary/import").json(&body).send().await
}

async fn manual_account(app: &TestApp, member: i64, name: &str, kind: AccountType) -> i64 {
    let mut draft = NewAccount::new(member, name, kind);
    draft.current_balance = Decimal::from(100);
    accounts::insert(&app.state.db, &draft, now())
        .await
        .unwrap()
        .id
}

async fn balance_of(app: &TestApp, id: i64) -> (Decimal, String) {
    picsou::db::query_as("SELECT current_balance, currency FROM account WHERE id = $1")
        .bind(id)
        .fetch_one(&app.state.db)
        .await
        .unwrap()
}

async fn snapshots(app: &TestApp, id: i64) -> Vec<(NaiveDate, Decimal, String)> {
    picsou::db::query_as(
        "SELECT date, balance, source FROM balance_snapshot WHERE account_id = $1 ORDER BY date DESC",
    )
    .bind(id)
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

fn mapping(row: &Value, action: &str) -> Value {
    json!({
        "finaryId": row["finaryId"],
        "finaryName": row["finaryName"],
        "finaryCategory": row["finaryCategory"],
        "action": action,
    })
}

#[tokio::test]
async fn preview_reports_rows_and_import_maps_the_balance_and_rebuilds_end_of_day_history() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let existing = manual_account(&app, member, "Compte courant", AccountType::Checking).await;
    let (d1, d2) = (day(&app, -1), day(&app, -2));
    let bytes = xlsx(&[
        (
            "Checkings",
            vec![header(), account_row("Compte courant", 1000.0, "EUR")],
        ),
        (
            "SAVINGS",
            vec![header(), account_row("Livret", 1234.5600000000001, "EUR")],
        ),
        (
            "Transactions",
            vec![
                vec![C::S("Category")],
                tx_row(d2.to_string(), "Deposit", 200.0, "compte COURANT"),
                tx_row(d1.to_string(), "Withdrawal", -50.0, "Compte courant"),
                tx_row("2024/01/01".into(), "Bad date", 1.0, "Compte courant"),
                vec![
                    C::S("x"),
                    C::S("2024-01-02"),
                    C::S("No amount"),
                    C::Empty,
                    C::Empty,
                    C::S("Compte courant"),
                ],
            ],
        ),
    ]);

    let response = preview(&client, &bytes).await;
    assert_eq!(response.status, 200, "{}", response.text());
    let body = response.json();
    assert_eq!(body["totalTransactionCount"], 2);
    assert_eq!(body["accounts"][0]["finaryId"], "checkings_compte_courant");
    assert_eq!(body["accounts"][0]["transactionCount"], 2);
    assert_eq!(body["accounts"][0]["currentBalance"].to_string(), "1000.0");
    // The sheet is found ignoring case, the balance printed as Java prints the double.
    assert_eq!(body["accounts"][1]["finaryCategory"], "Savings");
    assert_eq!(body["accounts"][1]["suggestedType"], "SAVINGS");
    assert_eq!(
        body["accounts"][1]["currentBalance"].to_string(),
        "1234.5600000000002"
    );
    assert_eq!(body["autoMapped"], false);
    assert_eq!(body["existingPicsouAccounts"][0]["id"], existing);
    assert_eq!(
        body["existingPicsouAccounts"][0]["currentBalanceEur"].to_string(),
        "100.00000000"
    );
    assert_eq!(body["suggestedMappings"][0]["action"], "MAP_EXISTING");
    assert_eq!(body["suggestedMappings"][0]["targetAccountId"], existing);
    assert_eq!(body["suggestedMappings"][1]["action"], "CREATE_NEW");
    assert!(
        body["suggestedMappings"][1]
            .get("targetAccountId")
            .is_none()
    );

    let mut create = mapping(&body["accounts"][1], "CREATE_NEW");
    create["newAccount"] = json!({"name": "Livret", "type": "SAVINGS", "currency": "EUR"});
    let response = import(
        &client,
        json!({"fileToken": body["fileToken"], "mappings": [body["suggestedMappings"][0], create]}),
    )
    .await;
    assert_eq!(response.status, 200, "{}", response.text());
    let result = response.json();
    assert_eq!(result["accountsMapped"], 1);
    assert_eq!(result["accountsCreated"], 1);
    assert_eq!(result["transactionsImported"], 2);
    assert_eq!(result["snapshotsCreated"], 4);
    assert_eq!(result["importedAccounts"][1]["color"], "#8b5cf6");
    assert_eq!(
        result["importedAccounts"][1]["currentBalance"].to_string(),
        "1234.5600000000002"
    );

    assert_eq!(balance_of(&app, existing).await.0, Decimal::from(1000));
    let today = app.state.clock.today();
    let history: Vec<(NaiveDate, Decimal)> = snapshots(&app, existing)
        .await
        .into_iter()
        .map(|(d, b, _)| (d, b))
        .collect();
    // End of yesterday is today's balance; end of two days ago is before the -50.
    assert_eq!(
        history,
        vec![
            (today, Decimal::from(1000)),
            (d1, Decimal::from(1000)),
            (d2, Decimal::from(1050))
        ]
    );
    let created: (i64, Option<String>, bool) = picsou::db::query_as(
        "SELECT id, external_account_id, is_manual FROM account WHERE name = 'Livret'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(created.1.as_deref(), Some("finary_Savings_livret"));
    assert!(created.2);

    // The token is consumed; a new preview finds the created account by its external id.
    let again = import(
        &client,
        json!({"fileToken": body["fileToken"], "mappings": []}),
    )
    .await;
    assert_eq!(again.status, 400);
    assert_eq!(
        again.json()["detail"],
        "Preview expired or invalid -- please re-upload the file"
    );
    let body = preview(&client, &bytes).await.json();
    assert_eq!(body["suggestedMappings"][1]["targetAccountId"], created.0);
    assert_eq!(body["autoMapped"], true);
}

#[tokio::test]
async fn two_rows_onto_one_account_are_rejected_before_anything_changes() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let target = manual_account(&app, member, "Other", AccountType::Checking).await;
    let bytes = xlsx(&[(
        "Checkings",
        vec![
            header(),
            account_row("Joint", 10.0, "EUR"),
            account_row("Perso", 20.0, "EUR"),
        ],
    )]);
    let body = preview(&client, &bytes).await.json();
    let clash: Vec<Value> = body["accounts"]
        .as_array()
        .unwrap()
        .iter()
        .map(|row| {
            let mut m = mapping(row, "MAP_EXISTING");
            m["targetAccountId"] = json!(target);
            m
        })
        .collect();
    let response = import(
        &client,
        json!({"fileToken": body["fileToken"], "mappings": clash}),
    )
    .await;
    assert_eq!(response.status, 400);
    assert_eq!(
        response.json()["detail"],
        format!(
            "Two Finary accounts are mapped onto the same Picsou account {target} -- pick a different account for one of them"
        )
    );
    assert_eq!(balance_of(&app, target).await.0, Decimal::from(100));
}

#[tokio::test]
async fn preview_matches_by_own_name_and_type_never_a_connector_account() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let mut savings_emergency = NewAccount::new(member, "Savings Emergency", AccountType::Savings);
    savings_emergency.external_account_id = Some("finary_Savings_savings_emergency".into());
    accounts::insert(&app.state.db, &savings_emergency, now())
        .await
        .unwrap();
    let mut rainy = NewAccount::new(member, "Rainy day", AccountType::Savings);
    rainy.external_account_id = Some("finary_Savings_emergency".into());
    let rainy = accounts::insert(&app.state.db, &rainy, now())
        .await
        .unwrap()
        .id;
    let mut broker = NewAccount::new(member, "Trade Republic", AccountType::CompteTitres);
    broker.is_manual = false;
    broker.external_account_id = Some("tr_portfolio_42".into());
    accounts::insert(&app.state.db, &broker, now())
        .await
        .unwrap();
    manual_account(&app, member, "Livret A", AccountType::Savings).await;
    manual_account(&app, member, "Livret A", AccountType::LivretA).await;
    let pea = manual_account(&app, member, " PEA banque ", AccountType::Pea).await;

    let bytes = xlsx(&[
        (
            "Savings",
            vec![
                header(),
                account_row("Emergency", 1.0, "EUR"),
                account_row("Livret A", 1.0, "EUR"),
            ],
        ),
        (
            "Investments",
            vec![
                header(),
                account_row("Trade Republic", 1.0, "EUR"),
                account_row("PEA BANQUE", 1.0, "EUR"),
            ],
        ),
    ]);
    let body = preview(&client, &bytes).await.json();
    let targets: Vec<Value> = body["suggestedMappings"]
        .as_array()
        .unwrap()
        .iter()
        .map(|m| m.get("targetAccountId").cloned().unwrap_or(Value::Null))
        .collect();
    // Emergency: its own id, not the account whose slug ends the same; Livret A: ambiguous;
    // Trade Republic: owned by its connector; the PEA: trimmed name, compatible type.
    assert_eq!(
        targets,
        vec![json!(rainy), Value::Null, Value::Null, json!(pea)]
    );
}

#[tokio::test]
async fn previews_and_accounts_are_scoped_to_the_member() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let (_, other_member) = app.create_member("bob", "bob-password-123").await;
    let bob = app.login("bob", "bob-password-123").await;
    let foreign = manual_account(&app, other_member, "Compte courant", AccountType::Checking).await;
    let bytes = xlsx(&[(
        "Checkings",
        vec![header(), account_row("Compte courant", 5.0, "EUR")],
    )]);

    let body = preview(&admin, &bytes).await.json();
    assert_eq!(body["existingPicsouAccounts"], json!([]));
    assert_eq!(body["suggestedMappings"][0]["action"], "CREATE_NEW");
    let mut onto_foreign = mapping(&body["accounts"][0], "MAP_EXISTING");
    onto_foreign["targetAccountId"] = json!(foreign);
    let request = json!({"fileToken": body["fileToken"], "mappings": [onto_foreign]});

    let stolen = import(&bob, request.clone()).await;
    assert_eq!(stolen.status, 400);
    assert_eq!(
        stolen.json()["detail"],
        "Preview expired or invalid -- please re-upload the file"
    );
    let response = import(&admin, request).await;
    assert_eq!(response.status, 400);
    assert_eq!(
        response.json()["detail"],
        format!("Account {foreign} not found")
    );
    assert_eq!(balance_of(&app, foreign).await.0, Decimal::from(100));
}

#[tokio::test]
async fn a_missing_rate_aborts_the_import_and_keeps_the_preview() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let bytes = xlsx(&[(
        "Checkings",
        vec![header(), account_row("Dollars", 10.0, "USD")],
    )]);
    let body = preview(&client, &bytes).await.json();
    let mut create = mapping(&body["accounts"][0], "CREATE_NEW");
    create["newAccount"] = json!({"name": "Dollars", "type": "CHECKING", "currency": "USD"});
    let request = json!({"fileToken": body["fileToken"], "mappings": [create.clone()]});

    let response = import(&client, request).await;
    assert_eq!(response.status, 422);
    assert_eq!(
        response.json()["detail"],
        "No EUR rate for USD -- cannot rebuild the balance history, try again once the rate is available"
    );
    let count: i64 = picsou::db::query_scalar("SELECT count(*) FROM account")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(count, 0);
    let skip = import(
        &client,
        json!({"fileToken": body["fileToken"], "mappings": [mapping(&body["accounts"][0], "SKIP")]}),
    )
    .await;
    assert_eq!(skip.status, 200);
    assert_eq!(skip.json()["accountsSkipped"], 1);
}

#[tokio::test]
async fn hand_entered_statements_and_manual_rows_survive_and_dividends_are_restored() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let cto = manual_account(&app, member, "CTO", AccountType::CompteTitres).await;
    let today = app.state.clock.today();
    let (paid, statement, old) = (day(&app, -3), day(&app, -10), day(&app, -20));
    picsou::db::query(
        "INSERT INTO balance_snapshot (account_id, date, balance, invested_amount, source, native_balance) \
         VALUES ($1, $2, 1200, 1200, 'MANUAL', 1200), ($1, $3, 5, 5, 'DERIVED', NULL)",
    )
    .bind(cto)
    .bind(statement)
    .bind(day(&app, -1))
    .execute(&app.state.db)
    .await
    .unwrap();
    picsou::db::query(
        "INSERT INTO \"transaction\" (account_id, date, description, amount, native_currency, is_manual, tx_type, ticker, name) VALUES \
         ($1, $2, 'DIVIDENDE', 59.50, 'EUR', false, 'DIVIDEND', 'TTE.PA', 'TotalEnergies'), \
         ($1, $2, 'Kept by hand', 1, 'EUR', true, NULL, NULL, NULL), \
         ($1, $2, 'Old sync', 3, 'EUR', false, NULL, NULL, NULL)",
    )
    .bind(cto)
    .bind(paid)
    .execute(&app.state.db)
    .await
    .unwrap();

    let bytes = xlsx(&[
        (
            "Investments",
            vec![header(), account_row("CTO", 1250.0, "EUR")],
        ),
        (
            "Transactions",
            vec![
                vec![C::S("Category")],
                tx_row(paid.to_string(), "DIVIDENDE", 59.5, "CTO"),
                tx_row(paid.to_string(), "DIVIDENDE", 59.5, "CTO"),
                tx_row(old.to_string(), "Before the statement", 10.0, "CTO"),
            ],
        ),
    ]);
    let body = preview(&client, &bytes).await.json();
    let response = import(
        &client,
        json!({"fileToken": body["fileToken"], "mappings": [body["suggestedMappings"][0]]}),
    )
    .await;
    assert_eq!(response.status, 200, "{}", response.text());
    assert_eq!(response.json()["snapshotsCreated"], 2);

    let history = snapshots(&app, cto).await;
    let dates: Vec<NaiveDate> = history.iter().map(|(d, _, _)| *d).collect();
    assert_eq!(dates, vec![today, paid, statement]);
    assert_eq!(
        history[2],
        (statement, Decimal::from(1200), "MANUAL".to_owned())
    );

    let rows: Vec<(String, bool, Option<String>, Option<String>)> = picsou::db::query_as(
        "SELECT description, is_manual, tx_type, ticker FROM \"transaction\" WHERE account_id = $1 ORDER BY id",
    )
    .bind(cto)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    let described: Vec<(&str, bool, Option<&str>)> = rows
        .iter()
        .map(|(d, m, t, _)| (d.as_str(), *m, t.as_deref()))
        .collect();
    // Each recognition restores once: the second identical credit stays plain.
    assert_eq!(
        described,
        vec![
            ("Kept by hand", true, None),
            ("DIVIDENDE", false, Some("DIVIDEND")),
            ("DIVIDENDE", false, None),
            ("Before the statement", false, None),
        ]
    );
    assert_eq!(rows[1].3.as_deref(), Some("TTE.PA"));
}

#[tokio::test]
async fn rejects_unreadable_uploads_and_expired_previews() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let garbage = preview(&client, b"not a spreadsheet").await;
    assert_eq!(garbage.status, 400);
    assert_eq!(
        garbage.json()["detail"],
        "That file isn't a valid Excel spreadsheet (.xlsx)."
    );
    let empty = preview(&client, b"").await;
    assert_eq!(empty.status, 400);
    assert_eq!(
        empty.json()["detail"],
        "The supplied file was empty (zero bytes long)"
    );

    let bytes = xlsx(&[(
        "Checkings",
        vec![header(), account_row("Joint", 1.0, "EUR")],
    )]);
    let body = preview(&client, &bytes).await.json();
    app.clock.advance(std::time::Duration::from_secs(30 * 60));
    let client = app.login_admin().await;
    let response = import(
        &client,
        json!({"fileToken": body["fileToken"], "mappings": []}),
    )
    .await;
    assert_eq!(response.status, 400);
    assert_eq!(
        response.json()["detail"],
        "Preview expired or invalid -- please re-upload the file"
    );
}
