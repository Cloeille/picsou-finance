//! CSV import of trades and of bank and card statement lines (TransactionImportServiceTest,
//! TransactionRowMapperTest, LedgerRowMapperTest).

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
    assert_eq!(body["mode"], "TRADES");
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
        json!({"imported": 3, "skipped": 0, "errors": [], "duplicates": 0})
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
        json!({"imported": 2, "skipped": 6, "duplicates": 0, "errors": [
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
async fn figures_too_large_for_a_decimal_are_reported_on_their_row() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let pea = account(&app, admin_member(&app).await, AccountType::Pea, true).await;
    let csv = "date,side,ticker,qty,price,fees,amount\n\
2024-01-02,buy,AIR.PA,1000000000000000,1000000000000000,0,\n\
2024-01-03,buy,AIR.PA,0.0000000001,,0,-100000000000000000000\n\
2024-01-04,buy,AIR.PA,1,10,0,\n";
    let body = preview(&client, pea.id, csv).await.json();
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
        json!({"imported": 1, "skipped": 2, "duplicates": 0, "errors": [
            {"rowNumber": 2, "message": "Amount out of range"},
            {"rowNumber": 3, "message": "Amount out of range"},
        ]})
    );
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
async fn trades_need_an_owned_manual_investment_account_and_loans_are_refused() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let admin = admin_member(&app).await;
    let synced = account(&app, admin, AccountType::Pea, false).await;
    let loan = account(&app, admin, AccountType::Loan, true).await;
    let peer_loan = account(&app, admin, AccountType::PeerLoan, true).await;
    let (_, other) = app.create_member("bob", "password-1234").await;
    let foreign = account(&app, other, AccountType::Pea, true).await;

    let r = preview(&client, synced.id, CSV).await;
    assert_eq!(r.status, 400);
    assert_eq!(
        r.json()["detail"],
        "CSV trade import is only available for manual investment accounts"
    );
    for refused in [&loan, &peer_loan] {
        let r = preview(&client, refused.id, CSV).await;
        assert_eq!(r.status, 400);
        assert_eq!(
            r.json()["detail"],
            "CSV transaction import is not available for loans"
        );
    }
    let r = preview(&client, foreign.id, CSV).await;
    assert_eq!(r.status, 404);
    assert_eq!(r.json()["detail"], "Account not found");
}

const STATEMENT: &str = "Date;Libellé;Débit;Crédit\n\
02/11/2024;CB CARREFOUR;45,30;\n\
03/11/2024;VIR SALAIRE;;2 100,00\n\
05/11/2024;CB BOULANGERIE;3,20;\n";

async fn statement_import(
    client: &Client,
    account_id: i64,
    content: &str,
    invert_sign: bool,
) -> picsou::testing::Response {
    let body = preview(client, account_id, content).await.json();
    let mut request = commit(&body, body["suggestedMapping"].clone());
    request["sideValueMap"] = Value::Null;
    request["invertSign"] = json!(invert_sign);
    client
        .post(&format!("/api/accounts/{account_id}/transactions/import"))
        .json(&request)
        .send()
        .await
}

async fn lines(
    app: &TestApp,
    account_id: i64,
) -> Vec<(String, Decimal, String, Option<String>, bool)> {
    picsou::db::query_as(
        "SELECT description, amount, native_currency, tx_type, is_manual FROM \"transaction\" \
         WHERE account_id = $1 ORDER BY date, id",
    )
    .bind(account_id)
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

async fn balance(app: &TestApp, account_id: i64) -> Decimal {
    picsou::db::query_scalar("SELECT current_balance FROM account WHERE id = $1")
        .bind(account_id)
        .fetch_one(&app.state.db)
        .await
        .unwrap()
}

#[tokio::test]
async fn a_statement_preview_on_a_checking_account_guesses_label_debit_and_credit() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let checking = account(&app, admin_member(&app).await, AccountType::Checking, true).await;

    let body = preview(&client, checking.id, STATEMENT).await.json();

    assert_eq!(body["mode"], "CASH");
    assert_eq!(
        body["suggestedMapping"],
        json!({"date": 0, "description": 1, "debit": 2, "credit": 3})
    );
    assert_eq!(body["dialect"]["decimal"], "COMMA");
}

#[tokio::test]
async fn previews_pick_the_mode_of_the_account_type() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let admin = admin_member(&app).await;
    for (account_type, mode) in [
        (AccountType::Savings, "CASH"),
        (AccountType::CreditCard, "CASH"),
        (AccountType::Scpi, "CASH"),
        (AccountType::Per, "TRADES"),
    ] {
        let target = account(&app, admin, account_type, true).await;
        let r = preview(&client, target.id, STATEMENT).await;
        assert_eq!(r.status, 200, "{account_type}");
        assert_eq!(r.json()["mode"], mode, "{account_type}");
    }
}

#[tokio::test]
async fn a_manual_checking_gets_signed_lines_and_its_balance_from_them() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let checking = account(&app, admin_member(&app).await, AccountType::Checking, true).await;

    let r = statement_import(&client, checking.id, STATEMENT, false).await;

    assert_eq!(r.status, 201, "{}", r.text());
    assert_eq!(
        r.json(),
        json!({"imported": 3, "skipped": 0, "errors": [], "duplicates": 0})
    );
    let dec = |s: &str| s.parse::<Decimal>().unwrap();
    assert_eq!(
        lines(&app, checking.id).await,
        vec![
            (
                "CB CARREFOUR".into(),
                dec("-45.30"),
                "EUR".into(),
                Some("WITHDRAWAL".into()),
                true
            ),
            (
                "VIR SALAIRE".into(),
                dec("2100"),
                "EUR".into(),
                Some("DEPOSIT".into()),
                true
            ),
            (
                "CB BOULANGERIE".into(),
                dec("-3.20"),
                "EUR".into(),
                Some("WITHDRAWAL".into()),
                true
            ),
        ]
    );
    assert_eq!(balance(&app, checking.id).await, dec("2051.50"));
}

#[tokio::test]
async fn a_synced_checking_and_an_scpi_keep_their_balance() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let admin = admin_member(&app).await;
    for (account_type, manual) in [(AccountType::Checking, false), (AccountType::Scpi, true)] {
        let target = account(&app, admin, account_type, manual).await;
        picsou::db::query("UPDATE account SET current_balance = 812 WHERE id = $1")
            .bind(target.id)
            .execute(&app.state.db)
            .await
            .unwrap();

        let r = statement_import(&client, target.id, STATEMENT, false).await;

        assert_eq!(r.status, 201, "{account_type}");
        assert_eq!(r.json()["imported"], 3, "{account_type}");
        assert_eq!(lines(&app, target.id).await.len(), 3, "{account_type}");
        assert_eq!(
            balance(&app, target.id).await,
            Decimal::from(812),
            "{account_type}"
        );
    }
}

#[tokio::test]
async fn an_overlapping_statement_leaves_out_the_lines_the_account_holds() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let checking = account(&app, admin_member(&app).await, AccountType::Checking, true).await;
    statement_import(&client, checking.id, STATEMENT, false).await;
    let overlap = "Date;Libellé;Débit;Crédit\n\
02/11/2024;cb  carrefour;45,30;\n\
06/11/2024;CB PHARMACIE;12,00;\n";

    let r = statement_import(&client, checking.id, overlap, false).await;

    assert_eq!(
        r.json(),
        json!({"imported": 1, "skipped": 0, "errors": [], "duplicates": 1})
    );
    assert_eq!(lines(&app, checking.id).await.len(), 4);
}

#[tokio::test]
async fn a_repeat_inside_one_statement_is_kept_and_a_second_import_of_it_is_not() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let checking = account(&app, admin_member(&app).await, AccountType::Checking, true).await;
    let coffees = "Date;Libellé;Montant\n04/11/2024;CB CAFE;-2,50\n04/11/2024;CB CAFE;-2,50\n";

    let first = statement_import(&client, checking.id, coffees, false).await;
    let again = statement_import(&client, checking.id, coffees, false).await;

    assert_eq!(first.json()["imported"], 2);
    assert_eq!(first.json()["duplicates"], 0);
    assert_eq!(again.json()["imported"], 0);
    assert_eq!(again.json()["duplicates"], 2);
    assert_eq!(lines(&app, checking.id).await.len(), 2);
}

#[tokio::test]
async fn invert_sign_turns_positive_card_purchases_into_outflows() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let card = account(
        &app,
        admin_member(&app).await,
        AccountType::CreditCard,
        true,
    )
    .await;
    let csv =
        "Date;Description;Amount;Currency\n04/11/2024;AMAZON;19,99;eur\n05/11/2024;CAFE;1,00;€\n";

    let r = statement_import(&client, card.id, csv, true).await;

    assert_eq!(r.status, 201, "{}", r.text());
    assert_eq!(r.json()["imported"], 2);
    let stored = lines(&app, card.id).await;
    assert_eq!(stored[0].1, "-19.99".parse::<Decimal>().unwrap());
    assert_eq!(stored[0].2, "EUR");
    assert_eq!(stored[1].2, "EUR");
}

#[tokio::test]
async fn invalid_statement_lines_are_reported_by_row_and_valid_ones_still_imported() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let checking = account(&app, admin_member(&app).await, AccountType::Checking, true).await;
    let csv = "Date;Libellé;Montant\n\
02/11/2024;OK;-1,00\n\
;SANS DATE;-1,00\n\
03/11/2024;ZERO;0,00\n\
03/11/2024;VIDE;\n\
03/11/2024;TEXTE;abc\n\
03/11/2024;ENORME;-1000000000000,00\n\
32/11/2024;MAUVAISE DATE;-1,00\n";

    let r = statement_import(&client, checking.id, csv, false).await;

    assert_eq!(r.status, 201);
    assert_eq!(
        r.json(),
        json!({"imported": 1, "skipped": 6, "duplicates": 0, "errors": [
            {"rowNumber": 3, "message": "Missing date"},
            {"rowNumber": 4, "message": "Amount is zero"},
            {"rowNumber": 5, "message": "Missing amount (or debit/credit)"},
            {"rowNumber": 6, "message": "Invalid amount 'abc'"},
            {"rowNumber": 7, "message": "Amount is too large"},
            {"rowNumber": 8, "message": "Invalid date '32/11/2024'"},
        ]})
    );
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
async fn date_patterns_with_time_tokens_import_their_calendar_dates() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let cto = account(
        &app,
        admin_member(&app).await,
        AccountType::CompteTitres,
        true,
    )
    .await;
    let csv = "Date,Side,Ticker,Qty,Price,Fees\n\
2024-01-15 14:30:00,BUY,AIR.PA,2,100,1\n\
2024-02-20 09:05:12,BUY,AIR.PA,1,110,1\n\
2024-03-10 25:00:00,BUY,AIR.PA,1,120,1\n";
    let body = preview(&client, cto.id, csv).await.json();
    let request = json!({
        "fileToken": body["fileToken"],
        "mapping": {"date": 0, "side": 1, "tickerOrIsin": 2, "quantity": 3, "unitPrice": 4, "fees": 5},
        "dialect": {"delimiter": ",", "decimal": "DOT", "dateFormat": "yyyy-MM-dd HH:mm:ss"},
        "hasHeaderRow": true,
        "feesIncludedInAmount": false,
    });
    let r = client
        .post(&format!("/api/accounts/{}/transactions/import", cto.id))
        .json(&request)
        .send()
        .await;
    assert_eq!(r.status, 201, "{}", r.text());
    assert_eq!(
        r.json(),
        json!({
            "imported": 2,
            "skipped": 1,
            "duplicates": 0,
            "errors": [{"rowNumber": 4, "message": "Invalid date '2024-03-10 25:00:00'"}],
        })
    );
    let dates: Vec<chrono::NaiveDate> = picsou::db::query_scalar(
        "SELECT date FROM \"transaction\" WHERE account_id = $1 ORDER BY date",
    )
    .bind(cto.id)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        dates,
        vec![
            chrono::NaiveDate::from_ymd_opt(2024, 1, 15).unwrap(),
            chrono::NaiveDate::from_ymd_opt(2024, 2, 20).unwrap(),
        ]
    );
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
        json!({"imported": 1, "skipped": 2, "duplicates": 0, "errors": [
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

/// A broken export is refused or read as it is, never a panic or a parse that does not return.
#[test]
fn csv_parser_never_panics_on_a_mangled_file() {
    use picsou::transfer::csv_import::csv;

    let files = [
        CSV.to_owned(),
        "Date,Montant\n\"01/02/2020\",\"1 234,56 €\"\n\"a\"\"b\",\u{a0}-1\u{202f}000.5\n"
            .to_owned(),
    ];
    crate::native_sidecars_a::assert_never_panics("csv import", &files, |file| {
        let delimiter = csv::detect_delimiter(file);
        let rows = csv::parse(file, delimiter);
        let _ = csv::parse(file, ';');
        let _ = csv::detect_decimal(&rows);
        let _ = csv::detect_date_format(&rows);
        let _ = csv::java_big_decimal(file);
        for cell in rows.iter().flatten() {
            let _ = csv::parse_decimal(Some(cell), csv::DecimalStyle::Comma);
            let _ = csv::parse_decimal(Some(cell), csv::DecimalStyle::Dot);
            let _ = csv::java_big_decimal(cell);
        }
    });
}
