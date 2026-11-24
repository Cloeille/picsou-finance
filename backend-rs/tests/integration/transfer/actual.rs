//! Actual Budget import (ActualBudgetFileParserTest, ActualBudgetImportServiceTest,
//! ActualBudgetImportControllerTest), over HTTP against synthetic Actual databases built here
//! with the tables and columns of Actual's loot-core schema.

use std::io::Write;
use std::time::Duration;

use picsou::testing::{Client, Response, TestApp};
use rust_decimal::Decimal;
use serde_json::{Value, json};

use super::support::{admin_member, file};

const SCHEMA: &[&str] = &[
    "CREATE TABLE accounts (id TEXT PRIMARY KEY, account_id TEXT, name TEXT, balance_current INTEGER, type TEXT, \
     offbudget INTEGER DEFAULT 0, closed INTEGER DEFAULT 0, tombstone INTEGER DEFAULT 0, sort_order REAL)",
    "CREATE TABLE category_groups (id TEXT PRIMARY KEY, name TEXT UNIQUE, is_income INTEGER DEFAULT 0, \
     sort_order REAL, tombstone INTEGER DEFAULT 0, hidden BOOLEAN DEFAULT 0)",
    "CREATE TABLE categories (id TEXT PRIMARY KEY, name TEXT, is_income INTEGER DEFAULT 0, cat_group TEXT, \
     sort_order REAL, tombstone INTEGER DEFAULT 0, hidden BOOLEAN DEFAULT 0)",
    "CREATE TABLE category_mapping (id TEXT PRIMARY KEY, transferId TEXT)",
    "CREATE TABLE payees (id TEXT PRIMARY KEY, name TEXT, category TEXT, tombstone INTEGER DEFAULT 0, \
     transfer_acct TEXT)",
    "CREATE TABLE payee_mapping (id TEXT PRIMARY KEY, targetId TEXT)",
    "CREATE TABLE transactions (id TEXT PRIMARY KEY, isParent INTEGER DEFAULT 0, isChild INTEGER DEFAULT 0, \
     acct TEXT, category TEXT, amount INTEGER, description TEXT, notes TEXT, date INTEGER, financial_id TEXT, \
     type TEXT, location TEXT, error TEXT, imported_description TEXT, starting_balance_flag INTEGER DEFAULT 0, \
     transferred_id TEXT, sort_order REAL, tombstone INTEGER DEFAULT 0, cleared INTEGER DEFAULT 1, \
     pending INTEGER DEFAULT 0, parent_id TEXT)",
    "CREATE TABLE preferences (id TEXT PRIMARY KEY, value TEXT)",
    "CREATE TABLE messages_crdt (id INTEGER PRIMARY KEY, timestamp TEXT, dataset TEXT, row TEXT, column TEXT, \
     value BLOB)",
];

/// ActualBudgetFixture: a synthetic Actual Budget database.
#[derive(Clone)]
struct Fixture {
    statements: Vec<String>,
    wal: bool,
}

fn quote(value: Option<&str>) -> String {
    value.map_or("NULL".to_owned(), |v| {
        format!("'{}'", v.replace('\'', "''"))
    })
}

impl Fixture {
    fn empty() -> Self {
        Self {
            statements: SCHEMA.iter().map(|s| (*s).to_owned()).collect(),
            wal: false,
        }
    }

    /// Two live accounts plus a deleted one, a transfer between the live ones, a split with a
    /// deleted child, a split whose parent was deleted, merged payees and categories, a
    /// starting balance and an EUR currency preference.
    fn household() -> Self {
        Self::empty()
            .sql("INSERT INTO preferences VALUES ('defaultCurrencyCode', 'EUR')")
            .sql("INSERT INTO accounts (id, name, offbudget, closed, tombstone) VALUES \
                  ('acc-checking', 'Everyday', 0, 0, 0), ('acc-savings', 'Rainy day', 1, 0, 0), \
                  ('acc-old', 'Old card', 0, 1, 1)")
            .sql("INSERT INTO category_groups (id, name, is_income, tombstone) VALUES \
                  ('grp-food', 'Food', 0, 0), ('grp-income', 'Income', 1, 0), ('grp-dead', 'Gone', 0, 1)")
            .sql("INSERT INTO categories (id, name, is_income, cat_group, tombstone) VALUES \
                  ('cat-groceries', 'Groceries', 0, 'grp-food', 0), \
                  ('cat-restaurants', 'Restaurants', 0, 'grp-food', 0), \
                  ('cat-salary', 'Salary', 1, 'grp-income', 0), \
                  ('cat-starting', 'Starting Balances', 1, 'grp-income', 0), \
                  ('cat-merged', 'Supermarket', 0, 'grp-food', 1), \
                  ('cat-orphan', 'Orphan', 0, 'grp-dead', 0)")
            .sql("INSERT INTO category_mapping VALUES ('cat-groceries', 'cat-groceries'), \
                  ('cat-restaurants', 'cat-restaurants'), ('cat-salary', 'cat-salary'), \
                  ('cat-starting', 'cat-starting'), ('cat-merged', 'cat-groceries')")
            .sql("INSERT INTO payees (id, name, tombstone, transfer_acct) VALUES \
                  ('p-market', 'Market', 0, NULL), ('p-employer', 'Employer', 0, NULL), \
                  ('p-old-market', 'Old market', 1, NULL), ('p-starting', 'Starting Balance', 0, NULL), \
                  ('p-to-savings', '', 0, 'acc-savings'), ('p-to-checking', '', 0, 'acc-checking')")
            .sql("INSERT INTO payee_mapping VALUES ('p-market', 'p-market'), ('p-old-market', 'p-market')")
            .tx("t-start", "acc-checking", 100000, 20240101, Some("cat-starting"), Some("p-starting"), None, Some("starting_balance_flag = 1"))
            .tx("t-groceries", "acc-checking", -1234, 20240102, Some("cat-merged"), Some("p-old-market"), None, None)
            .tx("t-transfer-out", "acc-checking", -50000, 20240115, None, Some("p-to-savings"), None, Some("transferred_id = 't-transfer-in'"))
            .tx("t-transfer-in", "acc-savings", 50000, 20240115, None, Some("p-to-checking"), None, Some("transferred_id = 't-transfer-out'"))
            .tx("t-split", "acc-checking", -3000, 20240120, None, Some("p-market"), Some("Weekly shop"), Some("isParent = 1"))
            .tx("t-split/1", "acc-checking", -2000, 20240120, Some("cat-groceries"), None, None, Some("isChild = 1, parent_id = 't-split'"))
            .tx("t-split/2", "acc-checking", -1000, 20240120, Some("cat-restaurants"), None, Some("Lunch"), Some("isChild = 1, parent_id = 't-split'"))
            .tx("t-split/3", "acc-checking", -999, 20240120, Some("cat-groceries"), None, None, Some("isChild = 1, parent_id = 't-split', tombstone = 1"))
            .tx("t-deleted", "acc-checking", -777, 20240121, Some("cat-groceries"), Some("p-market"), None, Some("tombstone = 1"))
            .tx("t-old-account", "acc-old", -5000, 20240122, Some("cat-groceries"), Some("p-market"), None, None)
            .tx("t-dead-parent", "acc-checking", -600, 20240123, None, Some("p-market"), None, Some("isParent = 1, tombstone = 1"))
            .tx("t-dead-parent/1", "acc-checking", -600, 20240123, Some("cat-groceries"), None, None, Some("isChild = 1, parent_id = 't-dead-parent'"))
            .tx("t-lonely-parent", "acc-checking", -4200, 20240125, Some("cat-restaurants"), Some("p-market"), None, Some("isParent = 1"))
            .tx("t-lonely-parent/1", "acc-checking", -4200, 20240125, Some("cat-restaurants"), None, None, Some("isChild = 1, parent_id = 't-lonely-parent', tombstone = 1"))
            .tx("t-salary", "acc-checking", 250000, 20240131, Some("cat-salary"), Some("p-employer"), Some("January pay"), None)
    }

    fn sql(mut self, statement: &str) -> Self {
        self.statements.push(statement.to_owned());
        self
    }

    #[allow(clippy::too_many_arguments)]
    fn tx(
        mut self,
        id: &str,
        account: &str,
        amount: i64,
        date: i64,
        category: Option<&str>,
        payee: Option<&str>,
        notes: Option<&str>,
        extra: Option<&str>,
    ) -> Self {
        self.statements.push(format!(
            "INSERT INTO transactions (id, acct, amount, date, category, description, notes) VALUES ({}, {}, {amount}, \
             {date}, {}, {}, {})",
            quote(Some(id)),
            quote(Some(account)),
            quote(category),
            quote(payee),
            quote(notes)
        ));
        if let Some(extra) = extra {
            self.statements.push(format!(
                "UPDATE transactions SET {extra} WHERE id = {}",
                quote(Some(id))
            ));
        }
        self
    }

    fn wal(mut self) -> Self {
        self.wal = true;
        self
    }

    fn sqlite(&self) -> Vec<u8> {
        let path =
            std::env::temp_dir().join(format!("actual-fixture-{}.sqlite", uuid::Uuid::new_v4()));
        {
            picsou::db::sqlite::initialize_library();
            let connection = rusqlite::Connection::open(&path).unwrap();
            if self.wal {
                connection
                    .pragma_update(None, "journal_mode", "WAL")
                    .unwrap();
            }
            for statement in &self.statements {
                connection.execute_batch(statement).unwrap();
            }
        }
        let bytes = std::fs::read(&path).unwrap();
        for suffix in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{}{suffix}", path.display()));
        }
        bytes
    }

    /// The budget export layout: db.sqlite next to metadata.json.
    fn zip(&self) -> Vec<u8> {
        zip_of(&[
            ("db.sqlite", self.sqlite()),
            ("metadata.json", br#"{"budgetName":"Synthetic"}"#.to_vec()),
        ])
    }
}

fn zip_of(entries: &[(&str, Vec<u8>)]) -> Vec<u8> {
    let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);
    for (name, bytes) in entries {
        writer.start_file(*name, options).unwrap();
        writer.write_all(bytes).unwrap();
    }
    writer.finish().unwrap().into_inner()
}

fn with_bulk(mut fixture: Fixture, range: std::ops::Range<usize>) -> Fixture {
    for i in range {
        fixture = fixture.tx(
            &format!("t-bulk-{i}"),
            "acc-checking",
            -100,
            20240301,
            None,
            Some("p-market"),
            None,
            None,
        );
    }
    fixture
}

/// Splits the -42.00 lonely parent into -25.00 and -17.00 children.
fn split_lonely_parent(fixture: Fixture) -> Fixture {
    fixture
        .tx(
            "t-lonely-parent/2",
            "acc-checking",
            -2500,
            20240125,
            Some("cat-restaurants"),
            None,
            None,
            Some("isChild = 1, parent_id = 't-lonely-parent'"),
        )
        .tx(
            "t-lonely-parent/3",
            "acc-checking",
            -1700,
            20240125,
            Some("cat-groceries"),
            None,
            None,
            Some("isChild = 1, parent_id = 't-lonely-parent'"),
        )
}

/// A second budget whose only account has the same name as the household's checking account.
fn other_budget() -> Fixture {
    Fixture::empty()
        .sql("INSERT INTO preferences VALUES ('defaultCurrencyCode', 'EUR')")
        .sql("INSERT INTO accounts (id, name) VALUES ('b2-checking', 'Everyday')")
        .tx(
            "b2-coffee",
            "b2-checking",
            -500,
            20240301,
            None,
            None,
            Some("Coffee"),
            None,
        )
}

struct Ctx {
    app: TestApp,
    client: Client,
    member: i64,
}

async fn setup() -> Ctx {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    picsou::db::query(
        "INSERT INTO category (member_id, slug, name, kind, color, is_default, sort_order) \
         SELECT $1, 'virement-interne', 'Virement interne', 'TRANSFER', '#64748b', true, 0 \
         WHERE NOT EXISTS (SELECT 1 FROM category WHERE member_id = $1 AND slug = 'virement-interne')",
    )
    .bind(member)
    .execute(&app.state.db)
    .await
    .unwrap();
    Ctx {
        app,
        client,
        member,
    }
}

/// Every import endpoint draws from the 10-per-minute sync bucket; moving the clock keeps a
/// long test under it.
fn pace(ctx: &Ctx) {
    ctx.app.clock.advance(Duration::from_secs(7));
}

async fn upload(ctx: &Ctx, bytes: &[u8]) -> Response {
    pace(ctx);
    let (content_type, body) = file("budget.zip", "application/zip", bytes);
    ctx.client
        .post("/api/actual/import/preview")
        .body(&content_type, body)
        .send()
        .await
}

async fn preview(ctx: &Ctx, fixture: &Fixture) -> Value {
    let response = upload(ctx, &fixture.zip()).await;
    assert_eq!(response.status, 200, "{}", response.text());
    response.json()
}

async fn rejected(ctx: &Ctx, bytes: &[u8]) -> String {
    let response = upload(ctx, bytes).await;
    assert_eq!(response.status, 400, "{}", response.text());
    response.json()["detail"].as_str().unwrap().to_owned()
}

fn create_everything(preview: &Value) -> Value {
    let accounts: Vec<Value> = preview["accounts"]
        .as_array()
        .unwrap()
        .iter()
        .map(|a| {
            json!({"sourceId": a["sourceId"], "action": "CREATE_NEW",
            "newAccount": {"name": a["name"], "type": a["suggestedType"], "currency": "EUR"}})
        })
        .collect();
    let categories: Vec<Value> = preview["categories"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| json!({"sourceId": c["sourceId"], "action": "CREATE_NEW", "name": c["name"]}))
        .collect();
    json!({"fileToken": preview["fileToken"], "currency": "EUR", "accountMappings": accounts,
        "categoryMappings": categories, "acknowledgeLargeDeletion": false})
}

fn with_account(mut request: Value, source: &str, mapping: Value) -> Value {
    for m in request["accountMappings"].as_array_mut().unwrap() {
        if m["sourceId"] == source {
            *m = mapping.clone();
        }
    }
    request
}

fn with_category(mut request: Value, source: &str, mapping: Value) -> Value {
    for m in request["categoryMappings"].as_array_mut().unwrap() {
        if m["sourceId"] == source {
            *m = mapping.clone();
        }
    }
    request
}

fn map_checking_onto(account_id: i64, preview: &Value) -> Value {
    with_account(
        create_everything(preview),
        "acc-checking",
        json!({"sourceId": "acc-checking", "action": "MAP_EXISTING", "targetAccountId": account_id}),
    )
}

async fn execute(ctx: &Ctx, request: &Value) -> Response {
    pace(ctx);
    ctx.client
        .post("/api/actual/import")
        .json(request)
        .send()
        .await
}

async fn imported(ctx: &Ctx, request: &Value) -> Value {
    let response = execute(ctx, request).await;
    assert_eq!(response.status, 201, "{}", response.text());
    response.json()
}

async fn refused(ctx: &Ctx, request: &Value) -> String {
    let response = execute(ctx, request).await;
    assert_eq!(response.status, 400, "{}", response.text());
    response.json()["detail"].as_str().unwrap().to_owned()
}

async fn plan(ctx: &Ctx, request: &Value) -> Response {
    pace(ctx);
    ctx.client
        .post("/api/actual/import/plan")
        .json(request)
        .send()
        .await
}

fn result(counts: [u64; 8], warnings: Value) -> Value {
    json!({"accountsCreated": counts[0], "accountsMapped": counts[1], "accountsSkipped": counts[2],
        "categoriesCreated": counts[3], "transactionsImported": counts[4], "transactionsSkipped": counts[5],
        "transactionsDeleted": counts[6], "transactionsMoved": counts[7], "warnings": warnings})
}

fn plan_of(add: u64, delete: u64, moved: u64, warnings: Value, large: bool) -> Value {
    json!({"transactionsToAdd": add, "transactionsToDelete": delete, "transactionsToMove": moved,
        "warnings": warnings, "largeDeletion": large})
}

type TxRow = (
    i64,
    chrono::NaiveDate,
    Decimal,
    String,
    Option<String>,
    String,
    bool,
    Option<String>,
    Option<String>,
    Option<String>,
    bool,
);

async fn tx_row(ctx: &Ctx, external: &str) -> TxRow {
    picsou::db::query_as(
        "SELECT t.account_id, t.date, t.amount, t.description, t.counterparty, t.native_currency, t.category_manual, \
         t.category, c.name, p.name, t.is_manual FROM \"transaction\" t LEFT JOIN category c ON c.id = t.category_id \
         LEFT JOIN category p ON p.id = c.parent_id WHERE t.external_id = $1",
    )
    .bind(external)
    .fetch_one(&ctx.app.state.db)
    .await
    .unwrap()
}

async fn first_import(ctx: &Ctx) {
    let p = preview(ctx, &Fixture::household()).await;
    imported(ctx, &create_everything(&p)).await;
}

async fn account_by_external(ctx: &Ctx, external: &str) -> (i64, Decimal) {
    picsou::db::query_as(
        "SELECT id, current_balance FROM account WHERE external_account_id = $1 AND member_id = $2",
    )
    .bind(external)
    .bind(ctx.member)
    .fetch_one(&ctx.app.state.db)
    .await
    .unwrap()
}

async fn balance(ctx: &Ctx, id: i64) -> Decimal {
    picsou::db::query_scalar("SELECT current_balance FROM account WHERE id = $1")
        .bind(id)
        .fetch_one(&ctx.app.state.db)
        .await
        .unwrap()
}

async fn row_account(ctx: &Ctx, external_id: &str) -> Option<i64> {
    picsou::db::query_scalar("SELECT account_id FROM \"transaction\" WHERE external_id = $1")
        .bind(external_id)
        .fetch_optional(&ctx.app.state.db)
        .await
        .unwrap()
}

async fn row_count(ctx: &Ctx) -> i64 {
    picsou::db::query_scalar(
        "SELECT count(*) FROM \"transaction\" t JOIN account a ON a.id = t.account_id WHERE a.member_id = $1",
    )
    .bind(ctx.member)
    .fetch_one(&ctx.app.state.db)
    .await
    .unwrap()
}

async fn user_account(ctx: &Ctx, name: &str, account_type: &str, balance: &str) -> i64 {
    picsou::db::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color, created_at, updated_at) \
         VALUES ($1, $2, $3::account_type, 'EUR', $4::numeric, true, '#6366f1', now(), now()) RETURNING id",
    )
    .bind(ctx.member)
    .bind(name)
    .bind(account_type)
    .bind(balance)
    .fetch_one(&ctx.app.state.db)
    .await
    .unwrap()
}

async fn user_category(ctx: &Ctx, name: &str, kind: &str) -> i64 {
    picsou::db::query_scalar(
        "INSERT INTO category (member_id, name, kind, color) VALUES ($1, $2, $3::category_kind, '#6366f1') RETURNING id",
    )
    .bind(ctx.member)
    .bind(name)
    .bind(kind)
    .fetch_one(&ctx.app.state.db)
    .await
    .unwrap()
}

fn dec(value: &str) -> Decimal {
    value.parse().unwrap()
}

fn sample<'a>(preview: &'a Value, id: &str) -> &'a Value {
    preview["sampleTransactions"]
        .as_array()
        .unwrap()
        .iter()
        .find(|t| t["sourceId"] == id)
        .unwrap_or_else(|| panic!("no sample {id}"))
}

// --- Parser ---------------------------------------------------------------------------------

#[tokio::test]
async fn preview_resolves_splits_transfers_merges_and_tombstones() {
    let ctx = setup().await;
    let p = preview(&ctx, &Fixture::household()).await;

    assert_eq!(p["currency"], "EUR");
    assert_eq!(p["totalTransactions"], 8);
    assert_eq!(p["transferTransactions"], 3);
    assert_eq!(
        p["accounts"],
        json!([
            {"sourceId": "acc-checking", "name": "Everyday", "offBudget": false, "closed": false,
             "suggestedType": "CHECKING", "balance": 2915.66, "transactionCount": 7},
            {"sourceId": "acc-savings", "name": "Rainy day", "offBudget": true, "closed": false,
             "suggestedType": "OTHER", "balance": p["accounts"][1]["balance"], "transactionCount": 1},
        ])
    );
    assert_eq!(p["accounts"][1]["balance"].to_string(), "500.00");
    assert_eq!(
        p["categories"],
        json!([
            {"sourceId": "cat-groceries", "name": "Groceries", "groupName": "Food", "income": false, "transactionCount": 2},
            {"sourceId": "cat-restaurants", "name": "Restaurants", "groupName": "Food", "income": false, "transactionCount": 2},
            {"sourceId": "cat-salary", "name": "Salary", "groupName": "Income", "income": true, "transactionCount": 1},
            {"sourceId": "cat-starting", "name": "Starting Balances", "groupName": "Income", "income": true, "transactionCount": 0},
        ])
    );
    let ids: Vec<&str> = p["sampleTransactions"]
        .as_array()
        .unwrap()
        .iter()
        .map(|t| t["sourceId"].as_str().unwrap())
        .collect();
    assert_eq!(
        ids,
        [
            "t-salary",
            "t-lonely-parent",
            "t-split/2",
            "t-split/1",
            "t-transfer-in",
            "t-transfer-out",
            "t-groceries",
            "t-start"
        ]
    );
    assert_eq!(
        *sample(&p, "t-split/1"),
        json!({"sourceId": "t-split/1", "accountSourceId": "acc-checking", "date": "2024-01-20", "amount": sample(&p, "t-split/1")["amount"],
               "payee": "Market", "notes": "Weekly shop", "categorySourceId": "cat-groceries", "kind": "REGULAR"})
    );
    assert_eq!(sample(&p, "t-split/1")["amount"].to_string(), "-20.00");
    assert_eq!(sample(&p, "t-transfer-in")["amount"].to_string(), "500.00");
    assert_eq!(sample(&p, "t-split/2")["notes"], "Lunch");
    assert_eq!(
        sample(&p, "t-lonely-parent")["amount"].to_string(),
        "-42.00"
    );
    assert_eq!(
        *sample(&p, "t-transfer-in"),
        json!({"sourceId": "t-transfer-in", "accountSourceId": "acc-savings", "date": "2024-01-15", "amount": sample(&p, "t-transfer-in")["amount"],
               "payee": "Everyday", "kind": "TRANSFER"})
    );
    assert_eq!(sample(&p, "t-transfer-out")["payee"], "Rainy day");
    assert_eq!(sample(&p, "t-start")["kind"], "STARTING_BALANCE");
    assert!(sample(&p, "t-start").get("categorySourceId").is_none());
    assert_eq!(sample(&p, "t-groceries")["payee"], "Market");
    assert_eq!(
        sample(&p, "t-groceries")["categorySourceId"],
        "cat-groceries"
    );
    assert_eq!(sample(&p, "t-groceries")["amount"].to_string(), "-12.34");
    assert_eq!(sample(&p, "t-salary")["date"], "2024-01-31");
    assert_eq!(p["actualAccountIds"], json!([]));
    assert_eq!(row_count(&ctx).await, 0);
}

#[tokio::test]
async fn a_bare_database_in_wal_mode_without_currency_or_parent_ids_is_read() {
    let ctx = setup().await;
    let wal = Fixture::household().wal().sqlite();
    assert_eq!(wal[18], 2, "header marks the file as WAL");
    let response = upload(&ctx, &wal).await;
    assert_eq!(response.status, 200, "{}", response.text());
    assert_eq!(response.json()["totalTransactions"], 8);

    let legacy = Fixture::empty()
        .sql("INSERT INTO accounts (id, name) VALUES ('a', 'Cash')")
        .sql("INSERT INTO payees (id, name) VALUES ('p', 'Shop')")
        .tx(
            "s",
            "a",
            -300,
            20230101,
            None,
            Some("p"),
            None,
            Some("isParent = 1"),
        )
        .tx(
            "s/1",
            "a",
            -300,
            20230101,
            None,
            None,
            None,
            Some("isChild = 1"),
        )
        .tx(
            "t",
            "a",
            100,
            20230615,
            None,
            None,
            Some(&"n".repeat(10_000)),
            None,
        );
    let p = preview(&ctx, &legacy).await;
    assert!(p.get("currency").is_none());
    let samples = p["sampleTransactions"].as_array().unwrap();
    assert_eq!(samples.len(), 2);
    assert_eq!(samples[0]["date"], "2023-06-15");
    assert_eq!(samples[0]["notes"].as_str().unwrap(), "n".repeat(255));
    assert_eq!(samples[1]["sourceId"], "s/1");
    assert_eq!(samples[1]["payee"], "Shop");
}

#[tokio::test]
async fn files_that_are_not_actual_budgets_are_refused() {
    let ctx = setup().await;
    let cash = || Fixture::empty().sql("INSERT INTO accounts (id, name) VALUES ('a', 'Cash')");
    let cases: Vec<(Vec<u8>, &str)> = vec![
        (b"date,amount\n2024-01-01,1".to_vec(), "Unsupported file: expected an Actual Budget export (.zip) or its db.sqlite"),
        (Fixture::empty().sql("DROP TABLE payees").sqlite(), "Not an Actual Budget database: table 'payees' is missing"),
        (Fixture::empty().sqlite(), "The Actual Budget file contains no accounts"),
        (cash().tx("t", "a", 100, 20241332, None, None, None, None).sqlite(), "Invalid transaction date in the Actual Budget file"),
        (cash().sql("INSERT INTO transactions (id, acct, amount, date) VALUES ('t', 'a', 12.5, 20240101)").sqlite(),
            "Invalid transaction amount in the Actual Budget file"),
        (cash().sql("INSERT INTO transactions (id, acct, amount, date) VALUES ('t', 'a', 'abc', 20240101)").sqlite(),
            "Invalid transaction amount in the Actual Budget file"),
        (Fixture::household().sql("INSERT INTO payees (id, name) VALUES ('p-huge', printf('%.*c', 2000000, 'x'))").sqlite(),
            "The Actual Budget file holds a value larger than 1 MiB"),
        (Fixture::household().sql("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i <= 50000) \
            INSERT INTO payee_mapping SELECT 'pm-' || i, 'p-market' FROM n").sqlite(),
            "The Actual Budget file has more than 50000 rows in 'payee_mapping'"),
        (Fixture::empty().sql("DROP TABLE payees").sql("CREATE TABLE payees (id TEXT PRIMARY KEY, tombstone INTEGER DEFAULT 0, \
            transfer_acct TEXT, name TEXT GENERATED ALWAYS AS (printf('%.*c', 1000, id)) VIRTUAL)")
            .sql("INSERT INTO accounts (id, name) VALUES ('a', 'Cash')").sqlite(),
            "Unsupported Actual Budget database: column 'payees.name' is generated"),
        (Fixture::empty().sql("ALTER TABLE payees RENAME TO payees_data").sql("CREATE VIEW payees AS SELECT * FROM payees_data")
            .sql("INSERT INTO accounts (id, name) VALUES ('a', 'Cash')").sqlite(),
            "Unsupported Actual Budget database: 'payees' is not a plain table"),
        (Fixture::household().sql(&format!("INSERT INTO payees (id, name) VALUES ('{}', 'Long')", "p".repeat(81))).sqlite(),
            "Unsupported identifier in the Actual Budget file"),
        (zip_of(&[("metadata.json", b"{}".to_vec())]), "The archive does not contain an Actual Budget db.sqlite"),
        (zip_of(&[("db.sqlite", Fixture::household().sqlite()), ("../../etc/cron.d/evil", b"* * * * * root id".to_vec())]),
            "The Actual Budget archive contains an unsafe path"),
        (zip_of(&[("../db.sqlite", Fixture::household().sqlite())]), "The Actual Budget archive contains an unsafe path"),
        (zip_of(&[("db.sqlite", b"not sqlite".to_vec())]), "The archive's db.sqlite is not a SQLite database"),
    ];
    for (bytes, message) in cases {
        assert_eq!(rejected(&ctx, &bytes).await, message);
    }
    let mut crowded: Vec<(String, Vec<u8>)> = (0..40)
        .map(|i| (format!("filler-{i}.txt"), vec![1]))
        .collect();
    crowded.push(("db.sqlite".into(), Fixture::household().sqlite()));
    let crowded: Vec<(&str, Vec<u8>)> = crowded
        .iter()
        .map(|(n, b)| (n.as_str(), b.clone()))
        .collect();
    assert_eq!(
        rejected(&ctx, &zip_of(&crowded)).await,
        "The Actual Budget archive has too many entries"
    );

    let zip = Fixture::household().zip();
    rejected(&ctx, &zip[..zip.len() / 2]).await;

    let empty = upload(&ctx, b"").await;
    assert_eq!(empty.status, 400);
    assert_eq!(empty.json()["detail"], "The file is empty");
}

#[tokio::test]
async fn an_archive_inflating_past_256_mib_is_refused_while_inflating() {
    let ctx = setup().await;
    let bomb = zip_of(&[
        ("metadata.json", vec![0u8; 257 * 1024 * 1024]),
        ("db.sqlite", Fixture::household().sqlite()),
    ]);
    assert!(bomb.len() < 10 * 1024 * 1024);
    assert_eq!(
        rejected(&ctx, &bomb).await,
        "The Actual Budget archive is too large once uncompressed"
    );
}

#[tokio::test]
async fn a_missing_file_part_and_an_unsupported_file_are_problem_details() {
    let ctx = setup().await;
    let (content_type, body) = super::support::multipart(&[super::support::Part::Text {
        name: "x",
        value: "y",
    }]);
    let response = ctx
        .client
        .post("/api/actual/import/preview")
        .body(&content_type, body)
        .send()
        .await;
    assert_eq!(response.status, 400);
    assert_eq!(
        response.json()["detail"],
        "Required part 'file' is not present."
    );
}

// --- Import ---------------------------------------------------------------------------------

#[tokio::test]
async fn imports_accounts_categories_and_transactions_exactly() {
    let ctx = setup().await;
    let p = preview(&ctx, &Fixture::household()).await;
    let request = create_everything(&p);

    let dry = plan(&ctx, &request).await;
    assert_eq!(dry.status, 200);
    assert_eq!(dry.json(), plan_of(8, 0, 0, json!([]), false));
    assert_eq!(row_count(&ctx).await, 0);

    assert_eq!(
        imported(&ctx, &request).await,
        result([2, 0, 0, 6, 8, 0, 0, 0], json!([]))
    );
    let (checking, checking_balance) = account_by_external(&ctx, "actual_acc-checking").await;
    assert_eq!(checking_balance, dec("2915.66"));
    assert_eq!(
        account_by_external(&ctx, "actual_acc-savings").await.1,
        dec("500.00")
    );
    let (currency, manual): (String, bool) =
        picsou::db::query_as("SELECT currency, is_manual FROM account WHERE id = $1")
            .bind(checking)
            .fetch_one(&ctx.app.state.db)
            .await
            .unwrap();
    assert_eq!((currency.as_str(), manual), ("EUR", true));

    let groceries = tx_row(&ctx, "actual_t-groceries").await;
    assert_eq!(
        groceries,
        (
            checking,
            chrono::NaiveDate::from_ymd_opt(2024, 1, 2).unwrap(),
            dec("-12.34"),
            "Market".into(),
            Some("Market".into()),
            "EUR".into(),
            true,
            Some("Groceries".into()),
            Some("Groceries".into()),
            Some("Food".into()),
            true
        )
    );
    let salary = tx_row(&ctx, "actual_t-salary").await;
    assert_eq!(
        (salary.3.as_str(), salary.4.as_deref()),
        ("January pay", Some("Employer"))
    );
    let kind: String = picsou::db::query_scalar(
        "SELECT c.kind::text FROM \"transaction\" t JOIN category c ON c.id = t.category_id \
        WHERE t.external_id = 'actual_t-salary'",
    )
    .fetch_one(&ctx.app.state.db)
    .await
    .unwrap();
    assert_eq!(kind, "INCOME");
    let start = tx_row(&ctx, "actual_t-start").await;
    assert_eq!(
        (start.3.as_str(), start.8.as_deref()),
        ("Starting Balance", Some("Virement interne"))
    );
    let transfer: Vec<String> = picsou::db::query_scalar(
        "SELECT t.external_id FROM \"transaction\" t JOIN category c ON c.id = t.category_id \
        WHERE c.slug = 'virement-interne' ORDER BY t.external_id",
    )
    .fetch_all(&ctx.app.state.db)
    .await
    .unwrap();
    assert_eq!(
        transfer,
        [
            "actual_t-start",
            "actual_t-transfer-in",
            "actual_t-transfer-out"
        ]
    );
    let snapshots: i64 =
        picsou::db::query_scalar("SELECT count(*) FROM balance_snapshot WHERE account_id = $1")
            .bind(checking)
            .fetch_one(&ctx.app.state.db)
            .await
            .unwrap();
    assert!(snapshots > 0);

    let replay = execute(&ctx, &request).await;
    assert_eq!(replay.status, 400);
    assert_eq!(
        replay.json()["detail"],
        "Preview expired or invalid -- please upload the file again"
    );
}

#[tokio::test]
async fn creates_its_own_transfer_category_when_the_default_one_is_archived() {
    let ctx = setup().await;
    picsou::db::query(
        "UPDATE category SET archived = true WHERE slug = 'virement-interne' AND member_id = $1",
    )
    .bind(ctx.member)
    .execute(&ctx.app.state.db)
    .await
    .unwrap();
    first_import(&ctx).await;
    let (slug, kind): (String, String) = picsou::db::query_as(
        "SELECT c.slug, c.kind::text FROM \"transaction\" t \
        JOIN category c ON c.id = t.category_id WHERE t.external_id = 'actual_t-transfer-in'",
    )
    .fetch_one(&ctx.app.state.db)
    .await
    .unwrap();
    assert_eq!(
        (slug.as_str(), kind.as_str()),
        ("actual-transfer", "TRANSFER")
    );
}

#[tokio::test]
async fn reimporting_converges_and_points_at_the_created_accounts() {
    let ctx = setup().await;
    first_import(&ctx).await;
    let categories: i64 = picsou::db::query_scalar("SELECT count(*) FROM category")
        .fetch_one(&ctx.app.state.db)
        .await
        .unwrap();
    user_account(&ctx, "Everyday", "CHECKING", "0").await;
    picsou::db::query(
        "UPDATE \"transaction\" SET date = '2030-06-01' WHERE external_id = 'actual_t-groceries'",
    )
    .execute(&ctx.app.state.db)
    .await
    .unwrap();

    let p = preview(&ctx, &Fixture::household()).await;
    let checking = account_by_external(&ctx, "actual_acc-checking").await.0;
    let savings = account_by_external(&ctx, "actual_acc-savings").await.0;
    assert_eq!(p["accounts"][0]["importedAccountId"], checking);
    assert_eq!(p["accounts"][1]["importedAccountId"], savings);
    assert_eq!(p["actualAccountIds"], json!([checking, savings]));
    assert_eq!(p["existingAccounts"].as_array().unwrap().len(), 3);

    assert_eq!(
        imported(&ctx, &create_everything(&p)).await,
        result([0, 2, 0, 0, 0, 8, 0, 0], json!([]))
    );
    let after: i64 = picsou::db::query_scalar("SELECT count(*) FROM category")
        .fetch_one(&ctx.app.state.db)
        .await
        .unwrap();
    assert_eq!(after, categories);
    assert_eq!(row_count(&ctx).await, 8);
}

#[tokio::test]
async fn reimporting_onto_the_created_account_recomputes_its_balance() {
    let ctx = setup().await;
    first_import(&ctx).await;
    let checking = account_by_external(&ctx, "actual_acc-checking").await.0;
    let newer = Fixture::household().tx(
        "t-refund",
        "acc-checking",
        20000,
        20240205,
        Some("cat-groceries"),
        Some("p-market"),
        None,
        None,
    );
    let p = preview(&ctx, &newer).await;
    assert_eq!(
        imported(&ctx, &map_checking_onto(checking, &p)).await,
        result([0, 2, 0, 0, 1, 8, 0, 0], json!([]))
    );
    assert_eq!(balance(&ctx, checking).await, dec("3115.66"));
}

#[tokio::test]
async fn stored_rows_are_looked_up_in_batches() {
    let ctx = setup().await;
    let p = preview(&ctx, &with_bulk(Fixture::household(), 0..1_500)).await;
    imported(&ctx, &create_everything(&p)).await;
    assert_eq!(row_count(&ctx).await, 1_508);
    let p = preview(&ctx, &with_bulk(Fixture::household(), 0..1_500)).await;
    assert_eq!(
        imported(&ctx, &create_everything(&p)).await["transactionsSkipped"],
        1_508
    );
}

#[tokio::test]
async fn ownership_refusals_write_nothing() {
    let ctx = setup().await;
    first_import(&ctx).await;
    let checking = account_by_external(&ctx, "actual_acc-checking").await.0;
    let savings = account_by_external(&ctx, "actual_acc-savings").await.0;
    let other = user_account(&ctx, "Other", "CHECKING", "0").await;

    let p = preview(&ctx, &Fixture::household()).await;
    assert_eq!(
        refused(&ctx, &map_checking_onto(other, &p)).await,
        "The Actual account 'Everyday' was imported into 'Everyday' before; map it to that account to update it"
    );

    let foreign = "This account was created by another Actual import; map it from that file or create a new account";
    let p = preview(&ctx, &other_budget()).await;
    let request = with_account(
        create_everything(&p),
        "b2-checking",
        json!({"sourceId": "b2-checking", "action": "MAP_EXISTING", "targetAccountId": checking}),
    );
    let dry = plan(&ctx, &request).await;
    assert_eq!(dry.status, 400);
    assert_eq!(dry.json()["detail"], foreign);
    assert_eq!(refused(&ctx, &request).await, foreign);

    let p = preview(&ctx, &Fixture::household()).await;
    assert_eq!(
        refused(&ctx, &map_checking_onto(savings, &p)).await,
        foreign
    );
    assert_eq!(row_count(&ctx).await, 8);
    assert_eq!(balance(&ctx, checking).await, dec("2915.66"));
}

#[tokio::test]
async fn a_row_deleted_in_actual_is_deleted_from_the_created_account() {
    let ctx = setup().await;
    first_import(&ctx).await;
    let checking = account_by_external(&ctx, "actual_acc-checking").await.0;
    let p = preview(
        &ctx,
        &Fixture::household().sql("UPDATE transactions SET tombstone = 1 WHERE id = 't-groceries'"),
    )
    .await;
    assert_eq!(
        imported(&ctx, &map_checking_onto(checking, &p)).await,
        result([0, 2, 0, 0, 0, 7, 1, 0], json!([]))
    );
    assert_eq!(row_account(&ctx, "actual_t-groceries").await, None);
    assert_eq!(balance(&ctx, checking).await, dec("2928.00"));
}

#[tokio::test]
async fn large_deletions_need_an_acknowledgement() {
    let ctx = setup().await;
    first_import(&ctx).await;
    let p = preview(&ctx, &Fixture::household()
        .sql("UPDATE transactions SET tombstone = 1 WHERE id IN ('t-start', 't-groceries', 't-salary')")).await;
    let request = create_everything(&p);
    assert_eq!(
        plan(&ctx, &request).await.json(),
        plan_of(0, 3, 0, json!([]), true)
    );
    assert_eq!(
        refused(&ctx, &request).await,
        "This import would delete 3 transactions; review the plan and confirm the deletion"
    );
    assert_eq!(row_count(&ctx).await, 8);
    let mut acknowledged = request.clone();
    acknowledged["acknowledgeLargeDeletion"] = json!(true);
    assert_eq!(
        imported(&ctx, &acknowledged).await["transactionsDeleted"],
        3
    );
    assert_eq!(row_count(&ctx).await, 5);
}

#[tokio::test]
async fn deleting_more_than_two_hundred_rows_needs_an_acknowledgement_whatever_the_share() {
    let ctx = setup().await;
    let p = preview(&ctx, &with_bulk(Fixture::household(), 0..1_500)).await;
    imported(&ctx, &create_everything(&p)).await;
    let p = preview(&ctx, &with_bulk(Fixture::household(), 250..1_500)).await;
    let mut request = create_everything(&p);
    assert_eq!(
        plan(&ctx, &request).await.json(),
        plan_of(0, 250, 0, json!([]), true)
    );
    assert_eq!(
        refused(&ctx, &request).await,
        "This import would delete 250 transactions; review the plan and confirm the deletion"
    );
    request["acknowledgeLargeDeletion"] = json!(true);
    assert_eq!(imported(&ctx, &request).await["transactionsDeleted"], 250);
}

#[tokio::test]
async fn a_split_after_import_replaces_the_parent_with_its_children() {
    let ctx = setup().await;
    first_import(&ctx).await;
    let p = preview(&ctx, &split_lonely_parent(Fixture::household())).await;
    let request = create_everything(&p);
    assert_eq!(
        plan(&ctx, &request).await.json(),
        plan_of(2, 1, 0, json!([]), false)
    );
    assert_eq!(
        imported(&ctx, &request).await,
        result([0, 2, 0, 0, 2, 7, 1, 0], json!([]))
    );
    assert_eq!(row_account(&ctx, "actual_t-lonely-parent").await, None);
    assert!(
        row_account(&ctx, "actual_t-lonely-parent/3")
            .await
            .is_some()
    );
    assert_eq!(
        account_by_external(&ctx, "actual_acc-checking").await.1,
        dec("2915.66")
    );
}

#[tokio::test]
async fn rows_missing_from_actual_stay_on_a_user_account_and_are_reported() {
    let ctx = setup().await;
    let everyday = user_account(&ctx, "Everyday", "CHECKING", "12.00").await;
    let p = preview(&ctx, &Fixture::household()).await;
    imported(&ctx, &map_checking_onto(everyday, &p)).await;
    let p = preview(
        &ctx,
        &split_lonely_parent(
            Fixture::household()
                .sql("UPDATE transactions SET tombstone = 1 WHERE id = 't-groceries'"),
        ),
    )
    .await;
    let request = map_checking_onto(everyday, &p);
    let warnings = json!([{"reason": "KEPT_MISSING", "count": 2}]);
    assert_eq!(
        plan(&ctx, &request).await.json(),
        plan_of(2, 0, 0, warnings.clone(), false)
    );
    assert_eq!(
        imported(&ctx, &request).await,
        result([0, 2, 0, 0, 2, 6, 0, 0], warnings)
    );
    assert_eq!(
        row_account(&ctx, "actual_t-groceries").await,
        Some(everyday)
    );
    assert_eq!(
        row_account(&ctx, "actual_t-lonely-parent").await,
        Some(everyday)
    );
    assert_eq!(balance(&ctx, everyday).await, dec("12.00"));
}

#[tokio::test]
async fn rows_moved_between_created_accounts_follow_them() {
    let ctx = setup().await;
    first_import(&ctx).await;
    let (checking, _) = account_by_external(&ctx, "actual_acc-checking").await;
    let (savings, _) = account_by_external(&ctx, "actual_acc-savings").await;
    let p = preview(
        &ctx,
        &Fixture::household()
            .sql("UPDATE transactions SET acct = 'acc-savings' WHERE id = 't-groceries'"),
    )
    .await;
    let request = create_everything(&p);
    assert_eq!(
        plan(&ctx, &request).await.json(),
        plan_of(0, 0, 1, json!([]), false)
    );
    assert_eq!(
        imported(&ctx, &request).await,
        result([0, 2, 0, 0, 0, 7, 0, 1], json!([]))
    );
    assert_eq!(row_account(&ctx, "actual_t-groceries").await, Some(savings));
    assert_eq!(balance(&ctx, checking).await, dec("2928.00"));
    assert_eq!(balance(&ctx, savings).await, dec("487.66"));

    let p = preview(
        &ctx,
        &Fixture::household()
            .sql("INSERT INTO accounts (id, name) VALUES ('acc-new', 'New card')")
            .sql("UPDATE transactions SET acct = 'acc-new' WHERE id = 't-groceries'"),
    )
    .await;
    assert_eq!(
        imported(&ctx, &create_everything(&p)).await,
        result([1, 2, 0, 0, 0, 7, 0, 1], json!([]))
    );
    let (created, created_balance) = account_by_external(&ctx, "actual_acc-new").await;
    assert_eq!(row_account(&ctx, "actual_t-groceries").await, Some(created));
    assert_eq!(created_balance, dec("-12.34"));
    assert_eq!(balance(&ctx, savings).await, dec("500.00"));
}

#[tokio::test]
async fn rows_moved_out_of_or_into_a_user_account_stay_and_are_reported() {
    let ctx = setup().await;
    let everyday = user_account(&ctx, "Everyday", "CHECKING", "12.00").await;
    let p = preview(&ctx, &Fixture::household()).await;
    imported(&ctx, &map_checking_onto(everyday, &p)).await;
    let warnings = json!([{"reason": "KEPT_MOVED", "count": 1}]);

    let p = preview(
        &ctx,
        &Fixture::household()
            .sql("UPDATE transactions SET acct = 'acc-savings' WHERE id = 't-groceries'"),
    )
    .await;
    let request = map_checking_onto(everyday, &p);
    assert_eq!(
        plan(&ctx, &request).await.json(),
        plan_of(0, 0, 0, warnings.clone(), false)
    );
    assert_eq!(
        imported(&ctx, &request).await,
        result([0, 2, 0, 0, 0, 8, 0, 0], warnings.clone())
    );
    assert_eq!(
        row_account(&ctx, "actual_t-groceries").await,
        Some(everyday)
    );
    assert_eq!(
        account_by_external(&ctx, "actual_acc-savings").await.1,
        dec("500.00")
    );

    let p = preview(
        &ctx,
        &Fixture::household()
            .sql("UPDATE transactions SET acct = 'acc-checking' WHERE id = 't-transfer-in'"),
    )
    .await;
    assert_eq!(
        imported(&ctx, &map_checking_onto(everyday, &p)).await["warnings"],
        warnings
    );
    let savings = account_by_external(&ctx, "actual_acc-savings").await.0;
    assert_eq!(
        row_account(&ctx, "actual_t-transfer-in").await,
        Some(savings)
    );

    let p = preview(
        &ctx,
        &Fixture::household()
            .sql("INSERT INTO accounts (id, name) VALUES ('acc-new', 'New card')")
            .sql("UPDATE transactions SET acct = 'acc-new' WHERE id = 't-groceries'"),
    )
    .await;
    let request = with_account(
        create_everything(&p),
        "acc-checking",
        json!({"sourceId": "acc-checking", "action": "SKIP"}),
    );
    assert_eq!(
        imported(&ctx, &request).await,
        result([1, 1, 1, 0, 0, 8, 0, 0], warnings)
    );
    assert_eq!(
        row_account(&ctx, "actual_t-groceries").await,
        Some(everyday)
    );
    assert_eq!(
        account_by_external(&ctx, "actual_acc-new").await.1,
        dec("0")
    );
}

#[tokio::test]
async fn rows_of_a_soft_deleted_created_account_do_not_block_a_reimport() {
    let ctx = setup().await;
    first_import(&ctx).await;
    picsou::db::query(
        "UPDATE account SET deleted_at = now() WHERE external_account_id = 'actual_acc-savings'",
    )
    .execute(&ctx.app.state.db)
    .await
    .unwrap();
    let p = preview(
        &ctx,
        &Fixture::household()
            .sql("INSERT INTO accounts (id, name) VALUES ('acc-new', 'New card')")
            .sql("UPDATE transactions SET acct = 'acc-new' WHERE id = 't-transfer-in'"),
    )
    .await;
    let request = with_account(
        create_everything(&p),
        "acc-savings",
        json!({"sourceId": "acc-savings", "action": "SKIP"}),
    );
    assert_eq!(
        imported(&ctx, &request).await,
        result([1, 1, 1, 0, 1, 7, 0, 0], json!([]))
    );
    assert_eq!(
        account_by_external(&ctx, "actual_acc-new").await.1,
        dec("500.00")
    );

    let p = preview(&ctx, &Fixture::household()).await;
    assert_eq!(
        refused(&ctx, &create_everything(&p)).await,
        "The account 'Rainy day' was imported before and then deleted"
    );
}

#[tokio::test]
async fn skipped_accounts_keep_their_rows_out_and_the_other_leg_stays_neutral() {
    let ctx = setup().await;
    let p = preview(&ctx, &Fixture::household()).await;
    let request = with_account(
        create_everything(&p),
        "acc-savings",
        json!({"sourceId": "acc-savings", "action": "SKIP"}),
    );
    assert_eq!(
        imported(&ctx, &request).await,
        result([1, 0, 1, 6, 7, 1, 0, 0], json!([]))
    );
    assert_eq!(row_account(&ctx, "actual_t-transfer-in").await, None);
    let slug: String = picsou::db::query_scalar(
        "SELECT c.slug FROM \"transaction\" t JOIN category c ON c.id = t.category_id \
        WHERE t.external_id = 'actual_t-transfer-out'",
    )
    .fetch_one(&ctx.app.state.db)
    .await
    .unwrap();
    assert_eq!(slug, "virement-interne");
}

#[tokio::test]
async fn maps_onto_existing_accounts_and_categories_without_touching_their_balance() {
    let ctx = setup().await;
    let everyday = user_account(&ctx, "Everyday", "CHECKING", "12.00").await;
    let food = user_category(&ctx, "Food", "EXPENSE").await;
    let p = preview(&ctx, &Fixture::household()).await;
    let request = with_category(
        with_category(
            map_checking_onto(everyday, &p),
            "cat-groceries",
            json!({"sourceId": "cat-groceries", "action": "MAP_EXISTING", "targetCategoryId": food}),
        ),
        "cat-restaurants",
        json!({"sourceId": "cat-restaurants", "action": "UNCATEGORIZED"}),
    );
    assert_eq!(imported(&ctx, &request).await["accountsMapped"], 1);
    assert_eq!(balance(&ctx, everyday).await, dec("12.00"));
    let (account, category_id): (i64, Option<i64>) = picsou::db::query_as(
        "SELECT account_id, category_id FROM \"transaction\" WHERE external_id = 'actual_t-split/1'")
        .fetch_one(&ctx.app.state.db).await.unwrap();
    assert_eq!((account, category_id), (everyday, Some(food)));
    let lunch: (Option<i64>, bool, Option<String>) = picsou::db::query_as(
        "SELECT category_id, category_manual, category FROM \"transaction\" WHERE external_id = 'actual_t-split/2'")
        .fetch_one(&ctx.app.state.db).await.unwrap();
    assert_eq!(lunch, (None, false, Some("Restaurants".into())));
}

#[tokio::test]
async fn invalid_mappings_are_refused_before_anything_is_written() {
    let ctx = setup().await;
    let pea = user_account(&ctx, "PEA", "PEA", "0").await;
    let loan = user_account(&ctx, "Mortgage", "LOAN", "150000").await;
    let shopping = user_category(&ctx, "Shopping", "EXPENSE").await;
    let p = preview(&ctx, &Fixture::household()).await;
    let base = create_everything(&p);

    let mut usd = base.clone();
    usd["currency"] = json!("USD");
    assert_eq!(
        refused(&ctx, &usd).await,
        "The budget currency is EUR, not USD"
    );
    let mut incomplete = base.clone();
    incomplete["accountMappings"]
        .as_array_mut()
        .unwrap()
        .truncate(1);
    assert_eq!(
        refused(&ctx, &incomplete).await,
        "Each Actual account and category must be mapped exactly once"
    );
    let investment = with_account(
        base.clone(),
        "acc-savings",
        json!({"sourceId": "acc-savings", "action": "MAP_EXISTING", "targetAccountId": pea}),
    );
    assert_eq!(
        refused(&ctx, &investment).await,
        "Investment and property accounts cannot receive Actual Budget transactions"
    );
    let loan_target = map_checking_onto(loan, &p);
    assert_eq!(
        refused(&ctx, &loan_target).await,
        "Loan accounts cannot receive Actual Budget transactions; use another account type"
    );
    let loan_new = with_account(
        base.clone(),
        "acc-checking",
        json!({"sourceId": "acc-checking", "action": "CREATE_NEW",
        "newAccount": {"name": "Mortgage", "type": "LOAN", "currency": "EUR"}}),
    );
    assert_eq!(
        refused(&ctx, &loan_new).await,
        "Loan accounts cannot receive Actual Budget transactions; use another account type"
    );
    let bad_color = with_account(
        base.clone(),
        "acc-checking",
        json!({"sourceId": "acc-checking", "action": "CREATE_NEW",
        "newAccount": {"name": "X", "type": "CHECKING", "color": "red"}}),
    );
    assert_eq!(
        refused(&ctx, &bad_color).await,
        "Invalid new account details"
    );
    let no_target = with_account(
        base.clone(),
        "acc-checking",
        json!({"sourceId": "acc-checking", "action": "MAP_EXISTING"}),
    );
    assert_eq!(
        refused(&ctx, &no_target).await,
        "Choose the account 'Everyday' maps to"
    );
    let wrong_kind = with_category(
        base.clone(),
        "cat-salary",
        json!({"sourceId": "cat-salary", "action": "MAP_EXISTING", "targetCategoryId": shopping}),
    );
    assert_eq!(
        refused(&ctx, &wrong_kind).await,
        "Category 'Salary' must map to a INCOME category"
    );
    let unnamed = with_category(
        base.clone(),
        "cat-salary",
        json!({"sourceId": "cat-salary", "action": "CREATE_NEW", "name": " "}),
    );
    assert_eq!(
        refused(&ctx, &unnamed).await,
        "Invalid name for category 'Salary'"
    );

    assert_eq!(row_count(&ctx).await, 0);
    let accounts: i64 = picsou::db::query_scalar("SELECT count(*) FROM account")
        .fetch_one(&ctx.app.state.db)
        .await
        .unwrap();
    assert_eq!(accounts, 2);
    assert_eq!(imported(&ctx, &base).await["transactionsImported"], 8);
}

#[tokio::test]
async fn a_preview_is_single_use_bound_to_its_member_and_expires() {
    let ctx = setup().await;
    let p = preview(&ctx, &Fixture::household()).await;
    let request = create_everything(&p);
    ctx.app.create_member("bob", "bob-password-123").await;
    let bob = ctx.app.login("bob", "bob-password-123").await;
    for path in ["/api/actual/import/plan", "/api/actual/import"] {
        pace(&ctx);
        let response = bob.post(path).json(&request).send().await;
        assert_eq!(response.status, 400, "{}", response.text());
        assert_eq!(
            response.json()["detail"],
            "Preview expired or invalid -- please upload the file again"
        );
    }
    let p2 = preview(&ctx, &Fixture::household()).await;
    assert_eq!(
        refused(&ctx, &request).await,
        "Preview expired or invalid -- please upload the file again"
    );
    ctx.app.clock.advance(Duration::from_secs(31 * 60));
    // The session expired with the preview: sign in again.
    let admin = ctx.app.login_admin().await;
    let response = admin
        .post("/api/actual/import")
        .json(&create_everything(&p2))
        .send()
        .await;
    assert_eq!(response.status, 400);
    assert_eq!(
        response.json()["detail"],
        "Preview expired or invalid -- please upload the file again"
    );
}

#[tokio::test]
async fn request_validation_answers_422_before_the_service() {
    let ctx = setup().await;
    let response = execute(
        &ctx,
        &json!({"fileToken": "t", "currency": "euro", "accountMappings": [{"action": "SKIP"}]}),
    )
    .await;
    assert_eq!(response.status, 422);
    let errors = &response.json()["errors"];
    assert_eq!(errors["currency"], "must match \"[A-Z]{3}\"");
    assert_eq!(errors["accountMappings[0].sourceId"], "must not be blank");
    assert_eq!(errors["categoryMappings"], "must not be null");
    let unknown = execute(&ctx, &json!({"fileToken": "t", "currency": "EUR", "accountMappings": [{"sourceId": "a", "action": "NOPE"}], "categoryMappings": []})).await;
    assert_eq!(unknown.status, 400);
}

#[tokio::test]
async fn a_failed_concurrent_import_does_not_restore_another_requests_consumed_token() {
    let ctx = setup().await;
    let p = preview(&ctx, &with_bulk(Fixture::household(), 0..200)).await;
    let valid = create_everything(&p);
    let mut wrong_currency = valid.clone();
    wrong_currency["currency"] = json!("USD");

    let send = |body: Value| {
        let client = ctx.client.clone();
        async move { client.post("/api/actual/import").json(&body).send().await }
    };
    let (r1, r2, r3, r4) = tokio::join!(
        send(valid.clone()),
        send(wrong_currency.clone()),
        send(wrong_currency.clone()),
        send(valid.clone()),
    );
    let responses = [r1, r2, r3, r4];
    let statuses: Vec<u16> = responses.iter().map(|r| r.status.as_u16()).collect();
    assert_eq!(
        statuses.iter().filter(|&&s| s == 201).count(),
        1,
        "exactly one concurrent import must succeed: {statuses:?}"
    );
    assert!(
        statuses.iter().all(|&s| s == 201 || s == 400),
        "losing concurrent imports must answer 400, not collide on a restored token: {statuses:?}"
    );

    // Once the winning import completed, the preview token must stay spent.
    let after = plan(&ctx, &valid).await;
    assert_eq!(
        after.status,
        400,
        "consumed token was restored by a failed concurrent request: {}",
        after.text()
    );
    assert_eq!(
        after.json()["detail"],
        "Preview expired or invalid -- please upload the file again"
    );
}

#[tokio::test]
async fn a_preferences_view_generated_column_or_oversized_table_is_refused() {
    let ctx = setup().await;
    let view = Fixture::empty()
        .sql("DROP TABLE preferences")
        .sql("CREATE VIEW preferences AS SELECT 'defaultCurrencyCode' AS id, 'EUR' AS value")
        .sql("INSERT INTO accounts (id, name) VALUES ('a', 'Cash')")
        .sqlite();
    assert_eq!(
        rejected(&ctx, &view).await,
        "Unsupported Actual Budget database: 'preferences' is not a plain table"
    );

    let generated = Fixture::empty()
        .sql("DROP TABLE preferences")
        .sql("CREATE TABLE preferences (id TEXT PRIMARY KEY, value TEXT GENERATED ALWAYS AS ('EUR') VIRTUAL)")
        .sql("INSERT INTO accounts (id, name) VALUES ('a', 'Cash')")
        .sqlite();
    assert_eq!(
        rejected(&ctx, &generated).await,
        "Unsupported Actual Budget database: column 'preferences.value' is generated"
    );

    let oversized = Fixture::household()
        .sql(
            "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i <= 10000) \
             INSERT INTO preferences SELECT 'pref-' || i, 'x' FROM n",
        )
        .sqlite();
    assert_eq!(
        rejected(&ctx, &oversized).await,
        "The Actual Budget file has more than 10000 rows in 'preferences'"
    );
}
