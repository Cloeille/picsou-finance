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
