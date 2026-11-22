//! CSV import of investment transactions (TransactionImportServiceTest, TransactionRowMapperTest).

use picsou::ledger::model::AccountType;
use picsou::testing::{Client, TestApp};
use rust_decimal::Decimal;
use serde_json::{Value, json};

use super::support::{account, admin_member, file};

const CSV: &str = "Date;Sens;Ticker;Nom;Quantité;Prix;Frais;Devise\n\
15/01/2020;ACHAT;CW8.PA;Amundi MSCI World;3;412,50;1,99;EUR\n\
20/02/2020;ACHAT;CW8.PA;Amundi MSCI World;2;398,10;1,99;EUR\n\
10/03/2020;VENTE;CW8.PA;Amundi MSCI World;1;350;0;EUR\n";

async fn preview(client: &Client, account_id: i64, content: &str) -> picsou::testing::Response {
    let (content_type, body) = file("ordres.csv", "text/csv", content.as_bytes());
    client
        .post(&format!(
            "/api/accounts/{account_id}/transactions/import/preview"
        ))
        .body(&content_type, body)
        .allow_decimal_strings()
        .send()
        .await
}

fn commit(preview: &Value, mapping: Value) -> Value {
    json!({
        "fileToken": preview["fileToken"],
        "mapping": mapping,
        "dialect": preview["dialect"],
        "hasHeaderRow": preview["hasHeaderRow"],
        "feesIncludedInAmount": false,
        "sideValueMap": {"ACHAT": "BUY", "VENTE": "SELL"},
    })
}

fn full_mapping() -> Value {
    json!({"date": 0, "side": 1, "tickerOrIsin": 2, "name": 3, "quantity": 4, "unitPrice": 5, "fees": 6, "currency": 7})
}

async fn rows(
    app: &TestApp,
    account_id: i64,
) -> Vec<(String, Decimal, Option<Decimal>, Option<String>)> {
    picsou::db::query_as(
        "SELECT tx_type, amount, price_per_unit, ticker FROM \"transaction\" WHERE account_id = $1 ORDER BY date",
    )
    .bind(account_id)
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn preview_detects_the_dialect_and_mapping_then_import_writes_trades_and_holdings() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let cto = account(
        &app,
        admin_member(&app).await,
        AccountType::CompteTitres,
        true,
    )
    .await;

    let response = preview(&client, cto.id, CSV).await;
    assert_eq!(response.status, 200);
    let body = response.json();
    assert_eq!(
        body["dialect"],
        json!({"delimiter": ";", "decimal": "COMMA", "dateFormat": "dd/MM/yyyy"})
    );
    assert_eq!(body["hasHeaderRow"], true);
    assert_eq!(body["totalRows"], 3);
    assert_eq!(body["suggestedMapping"], full_mapping());
    assert_eq!(body["sampleRows"][0][5], "412,50");

    let response = client
        .post(&format!("/api/accounts/{}/transactions/import", cto.id))
        .json(&commit(&body, full_mapping()))
        .send()
        .await;
    assert_eq!(response.status, 201);
    assert_eq!(
        response.json(),
        json!({"imported": 3, "skipped": 0, "errors": []})
    );

    let stored = rows(&app, cto.id).await;
    assert_eq!(stored.len(), 3);
    assert_eq!(stored[0].0, "BUY");
    assert_eq!(stored[0].1, "-1239.49".parse::<Decimal>().unwrap());
    assert_eq!(stored[2].0, "SELL");
    assert_eq!(stored[2].1, "350".parse::<Decimal>().unwrap());
    let quantity: Decimal = picsou::db::query_scalar(
        "SELECT quantity FROM account_holding WHERE account_id = $1 AND ticker = 'CW8.PA'",
    )
    .bind(cto.id)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(quantity, Decimal::from(4));

    let replay = client
        .post(&format!("/api/accounts/{}/transactions/import", cto.id))
        .json(&commit(&body, full_mapping()))
        .send()
        .await;
    assert_eq!(replay.status, 400);
    assert_eq!(
        replay.json()["detail"],
        "Preview expired or invalid -- please re-upload the file"
    );
}

#[tokio::test]
async fn invalid_rows_are_reported_and_valid_ones_still_imported() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let pea = account(&app, admin_member(&app).await, AccountType::Pea, true).await;
    let csv = "date,side,ticker,qty,price,fees,amount\n\
2024-01-02,buy,AIR.PA,2,100.5,1,\n\
,buy,AIR.PA,2,100,0,\n\
2024-01-03,buy,AIR.PA,0,100,0,\n\
2024-01-04,buy,,1,100,0,\n\
2024-01-05,buy,AIR.PA,1,100,-1,\n\
2024-01-06,souscription,AIR.PA,1,,0,\n\
2024-01-07,,AIR.PA,4,,2,-402\n\
2024-13-07,buy,AIR.PA,1,1,0,\n";
    let body = preview(&client, pea.id, csv).await.json();
    assert_eq!(body["dialect"]["delimiter"], ",");
    let mapping = json!({"date": 0, "side": 1, "tickerOrIsin": 2, "quantity": 3, "unitPrice": 4, "fees": 5, "amount": 6});
    let request = json!({
        "fileToken": body["fileToken"], "mapping": mapping,
        "dialect": {"delimiter": ",", "decimal": "DOT", "dateFormat": "yyyy-MM-dd"},
        "hasHeaderRow": true, "feesIncludedInAmount": true,
    });
    let response = client
        .post(&format!("/api/accounts/{}/transactions/import", pea.id))
        .json(&request)
        .send()
        .await;
    assert_eq!(response.status, 201);
    assert_eq!(
        response.json(),
        json!({"imported": 2, "skipped": 6, "errors": [
            {"rowNumber": 3, "message": "Missing date"},
            {"rowNumber": 4, "message": "Missing or non-positive quantity"},
            {"rowNumber": 5, "message": "Missing ticker/ISIN"},
            {"rowNumber": 6, "message": "Fees cannot be negative"},
            {"rowNumber": 7, "message": "Cannot determine BUY/SELL"},
            {"rowNumber": 9, "message": "Invalid date '2024-13-07'"},
        ]})
    );
    let stored = rows(&app, pea.id).await;
    assert_eq!(stored[0].1, "-202.0".parse::<Decimal>().unwrap());
    assert_eq!(stored[1].0, "BUY");
    assert_eq!(stored[1].2, Some("100.00000000".parse().unwrap()));
}

#[tokio::test]
async fn header_only_file_has_no_data_rows_and_an_empty_file_is_refused() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let cto = account(
        &app,
        admin_member(&app).await,
        AccountType::CompteTitres,
        true,
    )
    .await;
    let body = preview(&client, cto.id, "Date;Ticker;Quantité\n")
        .await
        .json();
    assert_eq!(body["totalRows"], 0);
    assert_eq!(body["sampleRows"], json!([]));

    let empty = preview(&client, cto.id, "  \n").await;
    assert_eq!(empty.status, 400);
    assert_eq!(empty.json()["detail"], "The file is empty");

    let no_header = preview(&client, cto.id, "2024-01-02,AIR.PA,2\n")
        .await
        .json();
    assert_eq!(no_header["hasHeaderRow"], false);
    assert_eq!(
        no_header["detectedColumns"],
        json!(["Column 1", "Column 2", "Column 3"])
    );
    assert_eq!(no_header["suggestedMapping"], json!({}));
}

#[tokio::test]
async fn accounts_must_be_owned_manual_investment_accounts() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let admin = admin_member(&app).await;
    let checking = account(&app, admin, AccountType::Checking, true).await;
    let synced = account(&app, admin, AccountType::Pea, false).await;
    let (_, other) = app.create_member("bob", "password-1234").await;
    let foreign = account(&app, other, AccountType::Pea, true).await;

    let r = preview(&client, checking.id, CSV).await;
    assert_eq!(r.status, 400);
    assert_eq!(
        r.json()["detail"],
        "CSV transaction import is only available for investment accounts (PEA, CTO, crypto)"
    );
    let r = preview(&client, synced.id, CSV).await;
    assert_eq!(
        r.json()["detail"],
        "CSV transaction import is only available for manual investment accounts"
    );
    let r = preview(&client, foreign.id, CSV).await;
    assert_eq!(r.status, 404);
    assert_eq!(r.json()["detail"], "Account not found");
}

#[tokio::test]
async fn a_token_is_bound_to_its_account_and_its_member() {
    let app = TestApp::with_admin().await;
    let admin_client = app.login_admin().await;
    let admin = admin_member(&app).await;
    let first = account(&app, admin, AccountType::Pea, true).await;
    let second = account(&app, admin, AccountType::Pea, true).await;
    let body = preview(&admin_client, first.id, CSV).await.json();

    let r = admin_client
        .post(&format!("/api/accounts/{}/transactions/import", second.id))
        .json(&commit(&body, full_mapping()))
        .send()
        .await;
    assert_eq!(r.status, 400);
    assert_eq!(
        r.json()["detail"],
        "Preview does not belong to this account"
    );

    let (_, bob) = app.create_member("bob", "password-1234").await;
    let bob_account = account(&app, bob, AccountType::Pea, true).await;
    let bob_client = app.login("bob", "password-1234").await;
    let r = bob_client
        .post(&format!(
            "/api/accounts/{}/transactions/import",
            bob_account.id
        ))
        .json(&commit(&body, full_mapping()))
        .send()
        .await;
    assert_eq!(
        r.json()["detail"],
        "Preview expired or invalid -- please re-upload the file"
    );

    // The token survived both refusals.
    let r = admin_client
        .post(&format!("/api/accounts/{}/transactions/import", first.id))
        .json(&commit(&body, full_mapping()))
        .send()
        .await;
    assert_eq!(r.status, 201);
}

#[tokio::test]
async fn an_invalid_date_pattern_fails_the_import_and_hands_the_preview_back() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let cto = account(
        &app,
        admin_member(&app).await,
        AccountType::CompteTitres,
        true,
    )
    .await;
    let body = preview(&client, cto.id, CSV).await.json();
    let mut request = commit(&body, full_mapping());
    request["dialect"]["dateFormat"] = json!("dd/MM/yyyy bb");
    let r = client
        .post(&format!("/api/accounts/{}/transactions/import", cto.id))
        .json(&request)
        .send()
        .await;
    assert_eq!(r.status, 400);
    assert_eq!(
        r.json()["detail"],
        "Invalid date format pattern 'dd/MM/yyyy bb'"
    );
    let r = client
        .post(&format!("/api/accounts/{}/transactions/import", cto.id))
        .json(&commit(&body, full_mapping()))
        .send()
        .await;
    assert_eq!(r.status, 201);
}

#[tokio::test]
async fn side_values_map_case_insensitively_and_metals_take_catalogue_tickers() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let metals = account(
        &app,
        admin_member(&app).await,
        AccountType::PreciousMetals,
        true,
    )
    .await;
    let csv = "date;side;ticker;qty;price\n2024-01-02;Achat;gold:napoleon-20f;1;400\n2024-01-03;Achat;NOPE;1;400\n2024-01-04;Weird;GOLD:NAPOLEON-20F;1;400\n";
    let body = preview(&client, metals.id, csv).await.json();
    let request = json!({
        "fileToken": body["fileToken"],
        "mapping": {"date": 0, "side": 1, "tickerOrIsin": 2, "quantity": 3, "unitPrice": 4},
        "dialect": body["dialect"], "hasHeaderRow": true, "feesIncludedInAmount": false,
        "sideValueMap": {"achat": "buy", "weird": "nope"},
    });
    let r = client
        .post(&format!("/api/accounts/{}/transactions/import", metals.id))
        .json(&request)
        .send()
        .await;
    assert_eq!(
        r.json(),
        json!({"imported": 1, "skipped": 2, "errors": [
            {"rowNumber": 3, "message": "'NOPE' is not a precious metals product"},
            {"rowNumber": 4, "message": "No enum constant com.picsou.model.TransactionType.NOPE"},
        ]})
    );
    assert_eq!(
        rows(&app, metals.id).await[0].3.as_deref(),
        Some("GOLD:NAPOLEON-20F")
    );
}

#[tokio::test]
async fn validation_and_missing_file_errors() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let cto = account(
        &app,
        admin_member(&app).await,
        AccountType::CompteTitres,
        true,
    )
    .await;
    let r = client
        .post(&format!("/api/accounts/{}/transactions/import", cto.id))
        .json(&json!({"fileToken": " "}))
        .send()
        .await;
    assert_eq!(r.status, 422);
    assert_eq!(
        r.json()["errors"],
        json!({"fileToken": "must not be blank", "mapping": "must not be null", "dialect": "must not be null"})
    );
    let (content_type, body) = super::support::multipart(&[super::support::Part::Text {
        name: "other",
        value: "x",
    }]);
    let r = client
        .post(&format!(
            "/api/accounts/{}/transactions/import/preview",
            cto.id
        ))
        .body(&content_type, body)
        .send()
        .await;
    assert_eq!(r.status, 400);
    assert_eq!(r.json()["detail"], "Required part 'file' is not present.");
    let r = client
        .post(&format!(
            "/api/accounts/{}/transactions/import/preview",
            cto.id
        ))
        .json(&json!({}))
        .send()
        .await;
    assert_eq!(r.status, 415);
}
