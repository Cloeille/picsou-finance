//! Per-account XLSX export (AccountExportControllerTest, AccountsWorkbookServiceTest, SheetLabelsTest).
//!
//! fixtures/account_export holds a workbook the Java backend wrote and the rows it had in its
//! database at that moment; the Rust export of the same rows must carry the same cells.

use std::io::Cursor;

use calamine::{Data, Reader, Xlsx, open_workbook_from_rs};
use picsou::ledger::model::AccountType;
use picsou::testing::{Client, TestApp};
use serde_json::{Value, json};

use super::support::{account, admin_member};

const JAVA_XLSX: &[u8] = include_bytes!("fixtures/account_export/java.xlsx");
const JAVA_ROWS: &str = include_str!("fixtures/account_export/java-rows.json");
const XLSX: &str = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

/// Every sheet as (name, rows of cells), each cell in its typed debug form.
fn cells(bytes: &[u8]) -> Vec<(String, Vec<Vec<String>>)> {
    let mut book: Xlsx<_> = open_workbook_from_rs(Cursor::new(bytes.to_vec())).expect("an xlsx");
    let names = book.sheet_names().to_vec();
    names
        .into_iter()
        .map(|name| {
            let range = book.worksheet_range(&name).expect("a sheet");
            let rows = range
                .rows()
                .map(|row| {
                    row.iter()
                        .map(|c| match c {
                            Data::DateTime(d) => format!("date:{}", d.as_f64()),
                            other => format!("{other:?}"),
                        })
                        .collect::<Vec<_>>()
                })
                .map(|mut row| {
                    while row.last().is_some_and(|c| c == "Empty") {
                        row.pop();
                    }
                    row
                })
                .collect();
            (name, rows)
        })
        .collect()
}

/// Replaces the cells whose value is the moment the export or the rows were made.
fn mask(mut book: Vec<(String, Vec<Vec<String>>)>) -> Vec<(String, Vec<Vec<String>>)> {
    for (_, rows) in &mut book {
        for row in rows.iter_mut() {
            let label = row.first().cloned().unwrap_or_default();
            if label.contains("Exported at") || label.contains("Created") {
                row.truncate(1);
                row.push("<moment>".into());
            }
        }
    }
    book
}

async fn restore(app: &TestApp, member_id: i64) -> Value {
    let mut dump: Value = serde_json::from_str(JAVA_ROWS).unwrap();
    for table in [
        "account",
        "transaction",
        "account_holding",
        "balance_snapshot",
        "debt",
        "member_profile",
        "goal",
        "goal_account",
        "goal_allocation",
    ] {
        let mut rows = dump[table].take();
        for row in rows.as_array_mut().unwrap() {
            if row.get("member_id").is_some() {
                row["member_id"] = json!(member_id);
            }
        }
        if app.state.db.is_sqlite() {
            for row in rows.as_array().unwrap() {
                let obj = row.as_object().unwrap();
                let cols: Vec<String> = obj.keys().map(|k| format!("\"{k}\"")).collect();
                let placeholders: Vec<String> = (1..=cols.len()).map(|i| format!("${i}")).collect();
                let sql = format!(
                    "INSERT INTO \"{table}\" ({}) VALUES ({})",
                    cols.join(", "),
                    placeholders.join(", ")
                );
                let mut q = picsou::db::query(&sql);
                for val in obj.values() {
                    q = match val {
                        Value::Null => q.bind(None::<String>),
                        Value::Bool(b) => q.bind(*b),
                        Value::Number(n) => {
                            if let Some(i) = n.as_i64() {
                                q.bind(i)
                            } else {
                                q.bind(n.to_string())
                            }
                        }
                        Value::String(s) => q.bind(s.as_str()),
                        other => q.bind(other.to_string()),
                    };
                }
                q.execute(&app.state.db)
                    .await
                    .unwrap_or_else(|e| panic!("restore {table}: {e}"));
            }
        } else {
            picsou::db::query(&format!(
                "INSERT INTO \"{table}\" SELECT * FROM jsonb_populate_recordset(NULL::\"{table}\", $1)"
            ))
            .bind(&rows)
            .execute(&app.state.db)
            .await
            .unwrap_or_else(|e| panic!("restore {table}: {e}"));
        }
        dump[table] = rows;
    }
    dump
}

async fn export(client: &Client, body: Value) -> picsou::testing::Response {
    client.post("/api/accounts/export").json(&body).send().await
}

#[tokio::test]
async fn the_workbook_matches_the_one_java_writes_for_the_same_rows() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let dump = restore(&app, admin_member(&app).await).await;
    let ids: Vec<i64> = dump["account"]
        .as_array()
        .unwrap()
        .iter()
        .map(|a| a["id"].as_i64().unwrap())
        .collect();
    let response = export(
        &client,
        json!({"accountIds": [ids[0], ids[1], ids[2], ids[0]], "labels": {"accountName": "Nom"}}),
    )
    .await;
    assert_eq!(response.status, 200);
    assert_eq!(response.header("content-type"), Some(XLSX));
    assert_eq!(
        response.header("content-disposition"),
        Some("attachment; filename=\"picsou-comptes-20260115-100000.xlsx\"")
    );
    let rust = mask(cells(&response.body));
    let java = mask(cells(JAVA_XLSX));
    assert_eq!(
        rust.iter().map(|(n, _)| n.clone()).collect::<Vec<_>>(),
        vec!["Summary", "Debts", "Courant", "PEA Bourse", "Crédit auto"]
    );
    for ((name, rust_rows), (_, java_rows)) in rust.iter().zip(&java) {
        assert_eq!(rust_rows, java_rows, "sheet {name}");
    }
}

#[tokio::test]
async fn validation_rate_limit_and_member_scope() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let r = export(&client, json!({"accountIds": []})).await;
    assert_eq!(r.status, 422);
    assert_eq!(
        r.json()["errors"],
        json!({"accountIds": "must not be empty"})
    );
    let many: Vec<i64> = (1..=201).collect();
    let r = export(&client, json!({"accountIds": many})).await;
    assert_eq!(
        r.json()["errors"],
        json!({"accountIds": "cannot export more than 200 accounts at once"})
    );

    let (_, bob) = app.create_member("bob", "password-1234").await;
    let foreign = account(&app, bob, AccountType::Checking, true).await;
    let r = export(&client, json!({"accountIds": [foreign.id]})).await;
    assert_eq!(r.status, 500);
    assert!(r.body.is_empty());
    assert_eq!(r.header("content-type"), Some(XLSX));

    let own = account(&app, admin_member(&app).await, AccountType::Checking, true).await;
    for _ in 0..19 {
        assert_eq!(
            export(&client, json!({"accountIds": [own.id]}))
                .await
                .status,
            200
        );
    }
    let r = export(&client, json!({"accountIds": [own.id]})).await;
    assert_eq!(r.status, 429);
    assert!(r.body.is_empty());
}

#[tokio::test]
async fn sheet_names_are_deduplicated_and_an_empty_member_still_gets_both_fixed_sheets() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let first = account(&app, member, AccountType::Checking, true).await;
    let second = account(&app, member, AccountType::Checking, true).await;
    let r = export(
        &client,
        json!({"accountIds": [first.id, second.id], "labels": {"summarySheet": "Account"}}),
    )
    .await;
    let book = cells(&r.body);
    assert_eq!(
        book.iter().map(|(n, _)| n.as_str()).collect::<Vec<_>>(),
        vec!["Account", "Debts", "Account (2)", "Account (3)"]
    );
    assert!(
        book[1]
            .1
            .iter()
            .any(|row| row.first().is_some_and(|c| c.contains("No debt recorded")))
    );
}

#[tokio::test]
async fn sheet_names_with_apostrophes_and_duplicate_labels_export_without_error() {
    use picsou::ledger::accounts::{self, NewAccount};

    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let now = super::support::now();

    let mut ids = Vec::new();
    for name in [
        "Caisse d'Epargne''",
        ":'Livret':",
        "'''",
        "Caisse d'Epargne''",
    ] {
        let mut draft = NewAccount::new(member, name, AccountType::Checking);
        draft.is_manual = true;
        ids.push(
            accounts::insert(&app.state.db, &draft, now)
                .await
                .unwrap()
                .id,
        );
    }

    let r = export(
        &client,
        json!({
            "accountIds": ids,
            "labels": {"summarySheet": "Bilan", "debtSheet": "Bilan"},
        }),
    )
    .await;
    assert_eq!(
        r.status, 200,
        "export must not 500 on apostrophes or duplicate fixed labels"
    );
    let book = cells(&r.body);
    let names: Vec<&str> = book.iter().map(|(n, _)| n.as_str()).collect();
    assert_eq!(
        names,
        vec![
            "Bilan",
            "Bilan (2)",
            "Caisse d'Epargne",
            "Livret",
            &format!("Account {}", ids[2]),
            "Caisse d'Epargne (2)",
        ]
    );
}

#[tokio::test]
async fn a_date_before_1900_exports_without_error() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let house = account(&app, member, AccountType::RealEstate, true).await;
    let pre_1900 = chrono::NaiveDate::from_ymd_opt(1895, 6, 15).unwrap();
    picsou::db::query(
        "INSERT INTO real_estate_metadata (account_id, member_id, purchase_price, purchase_date) \
         VALUES ($1, $2, $3, $4)",
    )
    .bind(house.id)
    .bind(member)
    .bind(rust_decimal::Decimal::from(100_000))
    .bind(pre_1900)
    .execute(&app.state.db)
    .await
    .unwrap();

    let r = export(&client, json!({"accountIds": [house.id]})).await;
    assert_eq!(r.status, 200, "export must not 500 on a date before 1900");
    let book = cells(&r.body);
    let purchase_row = book[2]
        .1
        .iter()
        .find(|row| row.first().is_some_and(|c| c.contains("Purchase date")))
        .expect("purchase_date row");
    assert!(
        purchase_row
            .get(1)
            .is_some_and(|c| c.contains("1895-06-15")),
        "expected formatted pre-1900 date, got {purchase_row:?}"
    );
}

#[tokio::test]
async fn co_owner_can_export_a_shared_account_with_its_holdings_and_share() {
    use picsou::ledger::accounts::{self, NewAccount};
    use picsou::ledger::holdings;
    use rust_decimal::Decimal;

    let app = TestApp::with_admin().await;
    let owner = admin_member(&app).await;
    let (_, coowner_id) = app.create_member("bob", "password-1234").await;
    let coowner = app.login("bob", "password-1234").await;
    let (_, _) = app.create_member("carol", "password-1234").await;
    let outsider = app.login("carol", "password-1234").await;
    let now = super::support::now();

    let mut draft = NewAccount::new(owner, "Shared PEA", AccountType::CompteTitres);
    draft.current_balance = Decimal::from(1000);
    draft.is_manual = true;
    let shared = accounts::insert(&app.state.db, &draft, now).await.unwrap();

    holdings::upsert_holding(
        &mut app.state.db.pool_handle(),
        shared.id,
        owner,
        "CW8.PA",
        Some("Amundi MSCI World"),
        Decimal::from(10),
        Some(Decimal::from(100)),
        now,
    )
    .await
    .unwrap();

    for (member_id, share) in [(owner, Decimal::from(60)), (coowner_id, Decimal::from(40))] {
        picsou::db::query(
            "INSERT INTO account_ownership (account_id, member_id, share_percent, created_at, updated_at) \
             VALUES ($1, $2, $3, $4, $4)",
        )
        .bind(shared.id)
        .bind(member_id)
        .bind(share)
        .bind(now)
        .execute(&app.state.db)
        .await
        .unwrap();
    }

    let r = export(&coowner, json!({"accountIds": [shared.id]})).await;
    assert_eq!(
        r.status, 200,
        "co-owner must be able to export a shared account"
    );
    assert_eq!(r.header("content-type"), Some(XLSX));

    let book = cells(&r.body);
    let names: Vec<&str> = book.iter().map(|(n, _)| n.as_str()).collect();
    assert_eq!(names, vec!["Summary", "Debts", "Shared PEA"]);

    let summary_row = book[0]
        .1
        .iter()
        .find(|row| row.first().is_some_and(|c| c.contains("Shared PEA")))
        .expect("summary row for shared account");
    assert_eq!(
        summary_row.get(6).map(String::as_str),
        Some("Float(40.0)"),
        "expected 40% share on summary sheet, got {summary_row:?}"
    );

    let account_sheet = &book[2].1;
    let share_row = account_sheet
        .iter()
        .find(|row| row.first().is_some_and(|c| c.contains("Ownership share")))
        .expect("ownership share row");
    assert_eq!(
        share_row.get(1).map(String::as_str),
        Some("Float(40.0)"),
        "expected 40% share on account sheet, got {share_row:?}"
    );
    assert!(
        account_sheet
            .iter()
            .any(|row| row.first().is_some_and(|c| c.contains("CW8.PA"))),
        "expected shared account holdings on account sheet, got {account_sheet:?}"
    );

    let denied = export(&outsider, json!({"accountIds": [shared.id]})).await;
    assert_eq!(denied.status, 500);
    assert!(denied.body.is_empty());
    assert_eq!(denied.header("content-type"), Some(XLSX));
}

#[tokio::test]
async fn a_co_owner_export_names_no_account_that_was_not_shared_with_them() {
    use picsou::ledger::accounts::{self, NewAccount};
    use rust_decimal::Decimal;

    let app = TestApp::with_admin().await;
    let owner = admin_member(&app).await;
    let admin = app.login_admin().await;
    let (_, coowner_id) = app.create_member("bob", "password-1234").await;
    let coowner = app.login("bob", "password-1234").await;
    let now = super::support::now();

    let mut ids = Vec::new();
    for (name, account_type) in [
        ("Shared House", AccountType::RealEstate),
        ("Private Mortgage", AccountType::Loan),
        ("Shared Loan", AccountType::Loan),
        ("Private Flat", AccountType::RealEstate),
        ("Shared Receivable", AccountType::PeerLoan),
        ("Private Checking", AccountType::Checking),
    ] {
        let mut draft = NewAccount::new(owner, name, account_type);
        draft.is_manual = true;
        ids.push(
            accounts::insert(&app.state.db, &draft, now)
                .await
                .unwrap()
                .id,
        );
    }
    let [
        house,
        private_mortgage,
        shared_loan,
        private_flat,
        receivable,
        private_checking,
    ] = ids[..]
    else {
        unreachable!()
    };
    for shared in [house, shared_loan, receivable] {
        for (member_id, share) in [(owner, Decimal::from(60)), (coowner_id, Decimal::from(40))] {
            picsou::db::query(
                "INSERT INTO account_ownership (account_id, member_id, share_percent, created_at, updated_at) \
                 VALUES ($1, $2, $3, $4, $4)",
            )
            .bind(shared)
            .bind(member_id)
            .bind(share)
            .bind(now)
            .execute(&app.state.db)
            .await
            .unwrap();
        }
    }
    for (loan, lender, linked) in [
        (private_mortgage, "Private Lender", house),
        (shared_loan, "Shared Lender", private_flat),
    ] {
        let saved = admin
            .put(&format!("/api/accounts/{loan}/debt"))
            .json(&json!({"borrowedAmount": 200000, "monthlyPayment": 900, "lenderName": lender, "linkedAccountId": linked}))
            .send()
            .await;
        assert_eq!(saved.status, 200, "{}", saved.text());
    }
    let described = admin
        .put(&format!("/api/accounts/{house}/real-estate"))
        .json(&json!({"purchasePrice": 300000}))
        .send()
        .await;
    assert_eq!(described.status, 200, "{}", described.text());
    let lent = admin
        .put(&format!("/api/accounts/{receivable}/peer-loan"))
        .json(&json!({"principal": 1000, "lentAt": "2026-01-05", "linkedAccountId": private_checking, "withCashLeg": false}))
        .send()
        .await;
    assert_eq!(lent.status, 200, "{}", lent.text());

    let selection = json!({"accountIds": [house, shared_loan, receivable]});
    let text_of = |body: &[u8]| format!("{:?}", cells(body));

    let theirs = export(&coowner, selection.clone()).await;
    assert_eq!(theirs.status, 200);
    let theirs = text_of(&theirs.body);
    assert!(theirs.contains("Shared Lender"), "{theirs}");
    for private in [
        "Private Mortgage",
        "Private Lender",
        "Private Flat",
        "Private Checking",
    ] {
        assert!(!theirs.contains(private), "{private} leaked: {theirs}");
    }

    let own = export(&admin, selection).await;
    assert_eq!(own.status, 200);
    let own = text_of(&own.body);
    for visible in [
        "Private Mortgage",
        "Private Lender",
        "Private Flat",
        "Private Checking",
    ] {
        assert!(own.contains(visible), "the owner lost {visible}: {own}");
    }
}

#[tokio::test]
async fn a_quote_the_bank_contradicts_keeps_the_bank_value_on_the_positions_sheet() {
    use chrono::NaiveDate;
    use picsou::ledger::accounts::{self, NewAccount};
    use picsou::ledger::model::HoldingDraft;
    use picsou::ledger::{holdings, providers};
    use rust_decimal::Decimal;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    let yahoo = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/MULYF"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({"chart": {"result": [{
            "meta": {"currency": "EUR", "regularMarketPrice": 376.28},
            "timestamp": [1_768_471_200],
            "indicators": {"quote": [{"close": [376.28]}]}
        }], "error": null}})),
        )
        .mount(&yahoo)
        .await;

    let app = TestApp::builder()
        .with_admin()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .spawn()
        .await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let now = super::support::now();

    let mut draft = NewAccount::new(member, "PEA Bourso", AccountType::Pea);
    draft.provider = Some(providers::BOURSO.to_owned());
    draft.is_manual = false;
    draft.current_balance = "453.08".parse::<Decimal>().unwrap();
    draft.cash_balance = Some(Decimal::from(100));
    let pea = accounts::insert(&app.state.db, &draft, now).await.unwrap();

    holdings::insert(
        &app.state.db,
        pea.id,
        &HoldingDraft {
            ticker: "MULYF".to_owned(),
            name: Some("Amundi Euro Stoxx Banks".to_owned()),
            quantity: Decimal::from(80),
            average_buy_in: Some("3.81".parse().unwrap()),
            provider_value_eur: Some("353.08".parse().unwrap()),
            provider_pnl_eur: Some("48.43".parse().unwrap()),
            provider_price_date: Some(NaiveDate::from_ymd_opt(2026, 1, 10).unwrap()),
            ..HoldingDraft::default()
        },
        now,
    )
    .await
    .unwrap();

    let r = export(&client, json!({"accountIds": [pea.id]})).await;
    assert_eq!(r.status, 200);
    let book = cells(&r.body);
    let account_sheet = &book[2].1;

    let balance_row = account_sheet
        .iter()
        .find(|row| {
            row.first()
                .is_some_and(|c| c == "String(\"Balance (EUR)\")")
        })
        .expect("Balance (EUR) row");
    assert_eq!(
        balance_row.get(1).map(String::as_str),
        Some("Float(453.08)"),
        "account balance row: {balance_row:?}"
    );

    let position_row = account_sheet
        .iter()
        .find(|row| row.first().is_some_and(|c| c == "String(\"MULYF\")"))
        .expect("MULYF position row");
    // Columns: Ticker, Name, Quantity, Average buy-in, Current price, Quote currency,
    // Current value (EUR), Cost basis (EUR), P&L (EUR), P&L (%), Price as of, Stale.
    assert_eq!(
        position_row.get(6).map(String::as_str),
        Some("Float(353.08)"),
        "position row: {position_row:?}"
    );
    assert_eq!(
        position_row.get(7).map(String::as_str),
        Some("Float(304.65)"),
        "position row: {position_row:?}"
    );
    assert_eq!(
        position_row.get(8).map(String::as_str),
        Some("Float(48.43)"),
        "position row: {position_row:?}"
    );
    // 2026-01-10 in Excel serial date (46032) rather than 2026-01-15 (46037).
    assert_eq!(
        position_row.get(10).map(String::as_str),
        Some("date:46032"),
        "position row: {position_row:?}"
    );
    assert_eq!(
        position_row.get(11).map(String::as_str),
        Some("String(\"Yes\")"),
        "position row: {position_row:?}"
    );
}
