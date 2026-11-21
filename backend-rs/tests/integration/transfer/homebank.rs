//! HomeBank import over HTTP (HomeBankImportServiceTest, HomeBankImportPersistenceTest,
//! HomeBankQifPersistenceTest, HomeBankImportControllerTest).

use std::io::Write;

use flate2::Compression;
use flate2::write::DeflateEncoder;
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::model::AccountType;
use picsou::testing::{Client, Response, TestApp};
use rust_decimal::Decimal;
use serde_json::{Value, json};

use super::support::{Part, admin_member, multipart, now};

const CHECKING: &str = "a12b3c4d-1111-4111-8111-111111111111";
const SAVINGS: &str = "a12b3c4d-2222-4222-8222-222222222222";
const FRESH: &str = "b12b3c4d-2222-4222-8222-222222222222";
const GROCERIES: &str = "b12b3c4d-1111-4111-8111-111111111111";
const SALARY: &str = "b12b3c4d-3333-4333-8333-333333333333";

const PAYLOAD: &str = r#"{"data":{
  "accounts":[
    {"id":"a12b3c4d-1111-4111-8111-111111111111","name":"Synthetic Checking","institution":"Synthetic Bank","type":"bank","currency":{"code":"EUR"},"initialAmount":{"amount":1250.125,"currencyCode":"EUR"},"isClosed":false},
    {"id":"a12b3c4d-2222-4222-8222-222222222222","name":"Synthetic Savings","institution":"Synthetic Bank","type":"savings","currency":{"code":"EUR"},"initialAmount":{"amount":50.00,"currencyCode":"EUR"},"isClosed":false}],
  "categories":[
    {"id":"b12b3c4d-2222-4222-8222-222222222222","name":"Fresh produce","parentID":"b12b3c4d-1111-4111-8111-111111111111","isIncomeType":false},
    {"id":"b12b3c4d-1111-4111-8111-111111111111","name":"Groceries","isIncomeType":false},
    {"id":"b12b3c4d-3333-4333-8333-333333333333","name":"Salary","isIncomeType":true}],
  "payees":[{"id":"c12b3c4d-1111-4111-8111-111111111111","name":"Synthetic Market"}],
  "transactions":[
    {"id":"d12b3c4d-1111-4111-8111-111111111111","accountID":"a12b3c4d-1111-4111-8111-111111111111","amount":{"amount":-12.34000001,"currencyCode":"EUR"},"date":{"year":2024,"month":2,"day":29},"category":{"id":"b12b3c4d-2222-4222-8222-222222222222"},"payee":{"id":"c12b3c4d-1111-4111-8111-111111111111"},"memo":"Fresh food note","isForecast":false},
    {"id":"d12b3c4d-2222-4222-8222-222222222222","accountID":"a12b3c4d-1111-4111-8111-111111111111","amount":{"amount":2400.55,"currencyCode":"EUR"},"date":{"year":2024,"month":3,"day":1},"category":{"id":"b12b3c4d-3333-4333-8333-333333333333"},"payee":{"id":"c12b3c4d-1111-4111-8111-111111111111"},"memo":"Monthly salary note","isForecast":false},
    {"id":"d12b3c4d-3333-4333-8333-333333333333","accountID":"a12b3c4d-1111-4111-8111-111111111111","amount":{"amount":-25.25,"currencyCode":"EUR"},"date":{"year":2024,"month":3,"day":2},"transferAccountID":"a12b3c4d-2222-4222-8222-222222222222","isForecast":false},
    {"id":"d12b3c4d-4444-4444-8444-444444444444","accountID":"a12b3c4d-2222-4222-8222-222222222222","amount":{"amount":25.25,"currencyCode":"EUR"},"date":{"year":2024,"month":3,"day":2},"transferAccountID":"a12b3c4d-1111-4111-8111-111111111111","isForecast":false},
    {"id":"d12b3c4d-5555-4555-8555-555555555555","accountID":"a12b3c4d-2222-4222-8222-222222222222","amount":{"amount":99,"currencyCode":"EUR"},"date":{"year":2024,"month":4,"day":2},"isForecast":true}]},
  "manifest":{"schemaVersion":3,"platform":"iOS","appVersion":"synthetic","appBuild":"test","exportDate":"2024-03-03"}}"#;

const QIF: &str = "!Account\nNSynthetic Checking\nTBank\n^\n!Type:Bank\nD2024/02/29\nT-12.34000001\nPSynthetic Market\nMfirst note\nLFood:Fresh\n^\nD2024/03/01\nT-30.00\nPSynthetic Split\nMsplit note\nL\nSFood:Fresh\nEfresh note\n$-10.00\nSFood:Other\nEother note\n$-20.00\n^\nD2024/03/02\nT-25.25\nL[Synthetic Savings]\n^\n!Account\nNSynthetic Savings\nTBank\n^\n!Type:Bank\nD2024/03/02\nT25.25\nL[Synthetic Checking]\n^\n";

fn deflate(bytes: &[u8]) -> Vec<u8> {
    let mut encoder = DeflateEncoder::new(Vec::new(), Compression::default());
    encoder.write_all(bytes).unwrap();
    encoder.finish().unwrap()
}

async fn upload(
    client: &Client,
    filename: &str,
    bytes: &[u8],
    params: &[(&str, &str)],
) -> Response {
    let mut parts = vec![Part::File {
        name: "file",
        filename,
        content_type: "application/octet-stream",
        bytes,
    }];
    parts.extend(
        params
            .iter()
            .map(|(name, value)| Part::Text { name, value }),
    );
    let (content_type, body) = multipart(&parts);
    client
        .post("/api/homebank/import/preview")
        .body(&content_type, body)
        .send()
        .await
}

async fn preview_hbk(client: &Client, json: &str) -> Value {
    let response = upload(client, "synthetic.hbk", &deflate(json.as_bytes()), &[]).await;
    assert_eq!(response.status, 200, "{}", response.text());
    response.json()
}

async fn execute(client: &Client, body: &Value) -> Response {
    client.post("/api/homebank/import").json(body).send().await
}

fn create(source: &str, name: &str, account_type: &str) -> Value {
    json!({"sourceId": source, "action": "CREATE_NEW", "newAccount": {"name": name, "type": account_type, "currency": "EUR", "color": "#6366f1"}})
}

fn full_request(token: &Value) -> Value {
    json!({
        "fileToken": token,
        "accountMappings": [create(CHECKING, "Synthetic Checking", "CHECKING"), create(SAVINGS, "Synthetic Savings", "SAVINGS")],
        "categoryMappings": [
            {"sourceId": FRESH, "action": "CREATE_NEW", "name": "Fresh produce"},
            {"sourceId": GROCERIES, "action": "CREATE_NEW", "name": "Groceries"},
            {"sourceId": SALARY, "action": "CREATE_NEW", "name": "Salary"},
        ],
    })
}

async fn count(app: &TestApp, sql: &str) -> i64 {
    sqlx::query_scalar(sql)
        .fetch_one(&app.state.db)
        .await
        .unwrap()
}

async fn balance_of(app: &TestApp, external_id: &str) -> Decimal {
    sqlx::query_scalar("SELECT current_balance FROM account WHERE external_account_id = $1")
        .bind(external_id)
        .fetch_one(&app.state.db)
        .await
        .unwrap()
}

async fn manual_account(
    app: &TestApp,
    member: i64,
    account_type: AccountType,
    manual: bool,
    balance: &str,
) -> i64 {
    let mut draft = NewAccount::new(member, "Existing", account_type);
    draft.is_manual = manual;
    draft.current_balance = balance.parse().unwrap();
    accounts::insert(&app.state.db, &draft, now())
        .await
        .unwrap()
        .id
}

#[tokio::test]
async fn ios_preview_writes_nothing_then_import_persists_an_exact_ledger_and_replay_deduplicates() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let preview = preview_hbk(&client, PAYLOAD).await;
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 0);
    assert_eq!(preview["totalTransactions"], 4);
    assert_eq!(preview["forecastTransactions"], 1);
    assert_eq!(
        preview["accounts"][0],
        json!({"sourceId": CHECKING, "name": "Synthetic Checking", "institution": "Synthetic Bank", "sourceType": "bank",
               "suggestedType": "CHECKING", "currency": "EUR", "initialBalance": 1250.125, "balance": 3613.08499999,
               "transactionCount": 3, "closed": false})
    );
    assert_eq!(preview["accounts"][1]["suggestedType"], "SAVINGS");
    assert_eq!(preview["accounts"][1]["balance"], json!(75.25));
    assert_eq!(preview["categories"][0]["parentSourceId"], GROCERIES);
    assert!(preview["categories"][1].get("parentSourceId").is_none());
    assert_eq!(preview["sampleTransactions"].as_array().unwrap().len(), 4);
    assert_eq!(
        preview["sampleTransactions"][0]["payee"],
        "Synthetic Market"
    );
    assert_eq!(preview["sampleTransactions"][2]["transfer"], true);

    let result = execute(&client, &full_request(&preview["fileToken"])).await;
    assert_eq!(result.status, 201, "{}", result.text());
    assert_eq!(
        result.json(),
        json!({"accountsCreated": 2, "accountsMapped": 0, "accountsSkipped": 0, "categoriesCreated": 3,
               "transactionsImported": 4, "transactionsSkipped": 1})
    );
    assert_eq!(
        balance_of(&app, &format!("homebank_{CHECKING}")).await,
        "3613.08499999".parse().unwrap()
    );
    assert_eq!(
        balance_of(&app, &format!("homebank_{SAVINGS}")).await,
        "75.25".parse().unwrap()
    );
    assert_eq!(count(&app, "SELECT count(*) FROM \"transaction\"").await, 6);
    let (description, counterparty, label, kind, manual): (String, String, String, String, bool) = sqlx::query_as(
        "SELECT t.description, t.counterparty, t.category, c.kind::text, t.category_manual FROM \"transaction\" t \
         JOIN category c ON c.id = t.category_id WHERE t.external_id = 'homebank_d12b3c4d-1111-4111-8111-111111111111'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        (
            description.as_str(),
            counterparty.as_str(),
            label.as_str(),
            kind.as_str(),
            manual
        ),
        (
            "Fresh food note",
            "Synthetic Market",
            "Fresh produce",
            "EXPENSE",
            true
        )
    );
    let parent: String = sqlx::query_scalar(&format!(
        "SELECT p.name FROM category c JOIN category p ON p.id = c.parent_id WHERE c.slug = 'homebank_{FRESH}'"
    ))
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(parent, "Groceries");
    assert_eq!(
        count(
            &app,
            "SELECT count(*) FROM \"transaction\" t JOIN category c ON c.id = t.category_id \
                     WHERE c.kind = 'TRANSFER' AND t.category_manual"
        )
        .await,
        4,
        "two transfer legs and two openings"
    );
    let opening: (Decimal, String) = sqlx::query_as(&format!(
        "SELECT amount, date::text FROM \"transaction\" WHERE external_id = 'homebank_opening_{CHECKING}'"
    ))
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        opening,
        ("1250.125".parse().unwrap(), "2024-02-28".to_owned())
    );
    assert!(count(&app, "SELECT count(*) FROM balance_snapshot").await > 0);

    let replay = preview_hbk(&client, PAYLOAD).await;
    assert_eq!(replay["existingAccounts"].as_array().unwrap().len(), 2);
    assert_eq!(
        replay["existingAccounts"][0]["currentBalanceEur"],
        replay["existingAccounts"][0]["currentBalance"]
    );
    let again = execute(&client, &full_request(&replay["fileToken"])).await;
    assert_eq!(again.status, 201);
    assert_eq!(again.json()["transactionsImported"], 0);
    assert_eq!(again.json()["transactionsSkipped"], 5);
    assert_eq!(again.json()["categoriesCreated"], 0);
    assert_eq!(count(&app, "SELECT count(*) FROM \"transaction\"").await, 6);
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 2);
    assert_eq!(
        count(
            &app,
            "SELECT count(*) FROM category WHERE slug LIKE 'homebank%'"
        )
        .await,
        4
    );

    let used = execute(&client, &full_request(&replay["fileToken"])).await;
    assert_eq!(used.status, 400);
    assert_eq!(
        used.json()["detail"],
        "Preview expired or invalid; please upload the file again"
    );
}

#[tokio::test]
async fn an_invalid_mapping_writes_nothing_and_keeps_the_token_for_a_retry() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let preview = preview_hbk(&client, PAYLOAD).await;
    let mut wrong = full_request(&preview["fileToken"]);
    wrong["accountMappings"][1]["newAccount"]["currency"] = json!("USD");
    let response = execute(&client, &wrong).await;
    assert_eq!(response.status, 400);
    assert_eq!(response.json()["detail"], "Invalid new account details");
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 0);
    assert_eq!(count(&app, "SELECT count(*) FROM category").await, 0);

    let mut missing = full_request(&preview["fileToken"]);
    missing["categoryMappings"].as_array_mut().unwrap().pop();
    assert_eq!(
        execute(&client, &missing).await.json()["detail"],
        "Every source item must have exactly one mapping"
    );

    let mut uncategorized_parent = full_request(&preview["fileToken"]);
    uncategorized_parent["categoryMappings"][1] =
        json!({"sourceId": GROCERIES, "action": "UNCATEGORIZED"});
    assert_eq!(
        execute(&client, &uncategorized_parent).await.json()["detail"],
        "Category parent must also be mapped"
    );

    let ok = execute(&client, &full_request(&preview["fileToken"])).await;
    assert_eq!(ok.status, 201);
}

#[tokio::test]
async fn qif_split_and_transfer_ledger_imports_once() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let missing_currency = upload(&client, "desktop.qif", QIF.as_bytes(), &[]).await;
    assert_eq!(missing_currency.status, 400);
    assert_eq!(
        missing_currency.json()["detail"],
        "Invalid or unsupported QIF file"
    );

    let response = upload(
        &client,
        "desktop.qif",
        QIF.as_bytes(),
        &[("currency", "EUR")],
    )
    .await;
    assert_eq!(response.status, 200, "{}", response.text());
    let preview = response.json();
    assert_eq!(preview["totalTransactions"], 5);
    let request = |preview: &Value| {
        json!({
            "fileToken": preview["fileToken"],
            "accountMappings": preview["accounts"].as_array().unwrap().iter().map(|a| json!({
                "sourceId": a["sourceId"], "action": "CREATE_NEW",
                "newAccount": {"name": a["name"], "type": "CHECKING", "currency": a["currency"]}})).collect::<Vec<_>>(),
            "categoryMappings": preview["categories"].as_array().unwrap().iter().map(|c| json!({
                "sourceId": c["sourceId"], "action": "CREATE_NEW", "name": c["name"]})).collect::<Vec<_>>(),
        })
    };
    let result = execute(&client, &request(&preview)).await;
    assert_eq!(result.status, 201, "{}", result.text());
    assert_eq!(result.json()["transactionsImported"], 5);
    assert_eq!(result.json()["categoriesCreated"], 3);
    let balances: Vec<(String, Decimal, Decimal)> = sqlx::query_as(
        "SELECT a.name, a.current_balance, COALESCE(SUM(t.amount), 0) FROM account a \
         LEFT JOIN \"transaction\" t ON t.account_id = a.id GROUP BY a.id ORDER BY a.id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(balances[0].1, "-67.59000001".parse().unwrap());
    assert!(balances.iter().all(|(_, balance, sum)| balance == sum));
    assert_eq!(
        count(
            &app,
            "SELECT count(*) FROM \"transaction\" WHERE external_id LIKE 'homebank_opening_%'"
        )
        .await,
        0
    );

    let replay = upload(
        &client,
        "desktop.qif",
        QIF.as_bytes(),
        &[("currency", "EUR")],
    )
    .await
    .json();
    assert_eq!(
        execute(&client, &request(&replay)).await.json()["transactionsImported"],
        0
    );
}

#[tokio::test]
async fn mapped_manual_account_gets_an_additive_opening_and_synced_targets_keep_their_balance() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let target = manual_account(&app, member, AccountType::Checking, true, "999").await;
    let synced = manual_account(&app, member, AccountType::Checking, false, "900").await;
    let source = "e12b3c4d-1111-4111-8111-111111111111";
    let other = "e12b3c4d-2222-4222-8222-222222222222";
    let payload = format!(
        r#"{{"data":{{"accounts":[{{"id":"{source}","name":"Mapped","type":"bank","currency":{{"code":"EUR"}},"initialAmount":{{"amount":1000,"currencyCode":"EUR"}},"isClosed":false}},
        {{"id":"{other}","name":"Synced","type":"bank","currency":{{"code":"EUR"}},"initialAmount":{{"amount":50,"currencyCode":"EUR"}},"isClosed":false}}],
        "categories":[],"payees":[],"transactions":[{{"id":"f12b3c4d-1111-4111-8111-111111111111","accountID":"{source}","amount":{{"amount":250,"currencyCode":"EUR"}},"date":{{"year":2024,"month":1,"day":5}},"isForecast":false}},
        {{"id":"f12b3c4d-2222-4222-8222-222222222222","accountID":"{other}","amount":{{"amount":10,"currencyCode":"EUR"}},"date":{{"year":2024,"month":1,"day":5}},"isForecast":false}}]}},
        "manifest":{{"schemaVersion":3,"platform":"iOS"}}}}"#
    );
    let mapping = |token: &Value| {
        json!({"fileToken": token, "categoryMappings": [], "accountMappings": [
        {"sourceId": source, "action": "MAP_EXISTING", "targetAccountId": target},
        {"sourceId": other, "action": "MAP_EXISTING", "targetAccountId": synced}]})
    };
    let preview = preview_hbk(&client, &payload).await;
    let result = execute(&client, &mapping(&preview["fileToken"])).await;
    assert_eq!(result.status, 201, "{}", result.text());
    assert_eq!(result.json()["accountsMapped"], 2);
    let balance = |id: i64| {
        let app = &app;
        async move {
            sqlx::query_scalar::<_, Decimal>("SELECT current_balance FROM account WHERE id = $1")
                .bind(id)
                .fetch_one(&app.state.db)
                .await
                .unwrap()
        }
    };
    assert_eq!(balance(target).await, Decimal::from(1250));
    assert_eq!(balance(synced).await, Decimal::from(900));
    assert_eq!(
        count(
            &app,
            &format!("SELECT count(*) FROM \"transaction\" WHERE account_id = {synced}")
        )
        .await,
        1
    );

    let replay = preview_hbk(&client, &payload).await;
    execute(&client, &mapping(&replay["fileToken"])).await;
    assert_eq!(
        count(
            &app,
            &format!("SELECT count(*) FROM \"transaction\" WHERE account_id = {target}")
        )
        .await,
        2
    );
    assert_eq!(balance(target).await, Decimal::from(1250));

    let elsewhere = manual_account(&app, member, AccountType::Checking, true, "0").await;
    let moved = preview_hbk(&client, &payload).await;
    let mut remap = mapping(&moved["fileToken"]);
    remap["accountMappings"][0]["targetAccountId"] = json!(elsewhere);
    let response = execute(&client, &remap).await;
    assert_eq!(response.status, 400);
    assert_eq!(
        response.json()["detail"],
        "HomeBank transaction was previously imported into a different account"
    );
}

#[tokio::test]
async fn unsafe_targets_and_other_members_are_rejected() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let pea = manual_account(&app, member, AccountType::Pea, true, "0").await;
    let (other_member, _) = app.create_member("bob", "Sup3r-secret-pass!").await;
    let foreign = manual_account(&app, other_member, AccountType::Checking, true, "0").await;
    let preview = preview_hbk(&client, PAYLOAD).await;
    let with_target = |target: i64| {
        let mut request = full_request(&preview["fileToken"]);
        request["accountMappings"][0] =
            json!({"sourceId": CHECKING, "action": "MAP_EXISTING", "targetAccountId": target});
        request
    };
    assert_eq!(
        execute(&client, &with_target(pea)).await.json()["detail"],
        "Investment and valued accounts cannot receive HomeBank transactions"
    );
    assert_eq!(
        execute(&client, &with_target(foreign)).await.json()["detail"],
        "Target account not found"
    );
    let mut both = full_request(&preview["fileToken"]);
    let checking = manual_account(&app, member, AccountType::Checking, true, "0").await;
    both["accountMappings"] = json!([
        {"sourceId": CHECKING, "action": "MAP_EXISTING", "targetAccountId": checking},
        {"sourceId": SAVINGS, "action": "MAP_EXISTING", "targetAccountId": checking}]);
    assert_eq!(
        execute(&client, &both).await.json()["detail"],
        "Multiple source accounts cannot map to one target"
    );

    let bob = app.login("bob", "Sup3r-secret-pass!").await;
    let stolen = execute(&bob, &full_request(&preview["fileToken"])).await;
    assert_eq!(stolen.status, 400);
    assert_eq!(
        stolen.json()["detail"],
        "Preview expired or invalid; please upload the file again"
    );
    assert_eq!(count(&app, "SELECT count(*) FROM \"transaction\"").await, 0);
}

#[tokio::test]
async fn uncategorized_rows_stay_automatic_and_archived_slugs_are_refused() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let preview = preview_hbk(&client, PAYLOAD).await;
    let mut request = full_request(&preview["fileToken"]);
    request["categoryMappings"] = json!([
        {"sourceId": FRESH, "action": "UNCATEGORIZED"},
        {"sourceId": GROCERIES, "action": "UNCATEGORIZED"},
        {"sourceId": SALARY, "action": "UNCATEGORIZED", "name": "x"}]);
    assert_eq!(
        execute(&client, &request).await.json()["detail"],
        "Uncategorized mapping cannot have a target or name"
    );
    request["categoryMappings"][2] = json!({"sourceId": SALARY, "action": "UNCATEGORIZED"});
    assert_eq!(execute(&client, &request).await.status, 201);
    let (category, manual): (Option<i64>, bool) = sqlx::query_as(
        "SELECT category_id, category_manual FROM \"transaction\" WHERE external_id = 'homebank_d12b3c4d-2222-4222-8222-222222222222'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!((category, manual), (None, false));

    sqlx::query("UPDATE category SET archived = true WHERE slug = 'homebank-transfer'")
        .execute(&app.state.db)
        .await
        .unwrap();
    let again = preview_hbk(&client, PAYLOAD).await;
    let response = execute(&client, &full_request(&again["fileToken"])).await;
    assert_eq!(
        response.json()["detail"],
        "HomeBank transfer category slug is archived or has an incompatible kind"
    );
}

#[tokio::test]
async fn upload_and_request_errors() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let zip = upload(&client, "sample.zip", b"x", &[]).await;
    assert_eq!(zip.status, 400);
    assert_eq!(zip.json()["detail"], "Unsupported HomeBank file extension");
    let empty = upload(&client, "sample.hbk", b"", &[]).await;
    assert_eq!(empty.json()["detail"], "HomeBank file size is invalid");
    let corrupt = upload(&client, "sample.hbk", b"not deflate", &[]).await;
    assert_eq!(
        corrupt.json()["detail"],
        "Invalid or corrupted HomeBank file"
    );
    let locked = upload(&client, "sample.hbexport", b"x", &[]).await;
    assert_eq!(
        locked.json()["detail"],
        "Password required for HomeBank export"
    );
    let long = "x".repeat(1025);
    let too_long = upload(&client, "sample.hbexport", b"x", &[("password", &long)]).await;
    assert_eq!(too_long.json()["detail"], "HomeBank password is too long");
    let (content_type, body) = multipart(&[Part::Text {
        name: "password",
        value: "x",
    }]);
    let no_file = client
        .post("/api/homebank/import/preview")
        .body(&content_type, body)
        .send()
        .await;
    assert_eq!(no_file.status, 400);

    let invalid = execute(
        &client,
        &json!({"fileToken": "token", "accountMappings": [null], "categoryMappings": []}),
    )
    .await;
    assert_eq!(invalid.status, 422);
    assert_eq!(
        invalid.json()["errors"]["accountMappings[0]"],
        "must not be null"
    );
    let blank = execute(
        &client,
        &json!({"fileToken": " ", "accountMappings": [{"action": "SKIP"}]}),
    )
    .await;
    assert_eq!(blank.status, 422);
    let errors = &blank.json()["errors"];
    assert_eq!(errors["fileToken"], "must not be blank");
    assert_eq!(errors["accountMappings[0].sourceId"], "must not be blank");
    assert_eq!(errors["categoryMappings"], "must not be null");
    let unknown = execute(
        &client,
        &json!({"fileToken": "t", "accountMappings": [], "categoryMappings": [], "extra": 1}),
    )
    .await;
    assert_eq!(
        unknown.json()["detail"],
        "Preview expired or invalid; please upload the file again"
    );
}
