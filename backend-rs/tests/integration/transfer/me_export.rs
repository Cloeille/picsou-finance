//! Full member export (MeExportControllerTest, ReAuthServiceTest, DataExportServiceTest,
//! CsvWriterTest, BalanceSnapshotsExporterTest): the ZIP is unpacked and read back.

use std::collections::BTreeMap;
use std::io::{Cursor, Read};

use picsou::auth::users;
use picsou::ledger::model::AccountType;
use picsou::testing::{ADMIN_PASSWORD, Client, Response, TestApp};
use serde_json::{Value, json};
use std::time::Duration;

use super::support::{account, admin_member, now};

const ENTRIES: [&str; 16] = [
    "account_ownership",
    "accounts",
    "balance_snapshots",
    "bank_connections",
    "debts",
    "goals",
    "holdings",
    "linked_identities",
    "peer_counterparties",
    "peer_loan_repayments",
    "peer_loan_terms",
    "profile",
    "property_valuations",
    "shared_resources",
    "transactions",
    "wallet_addresses",
];

/// The header rows the Java exporters write.
fn java_header(name: &str) -> &'static str {
    match name {
        "account_ownership" => "id,account_id,member_id,share_percent,created_at,updated_at",
        "accounts" => {
            "id,name,type,provider,currency,current_balance,cash_balance,ticker,is_manual,color,external_account_id,last_synced_at,created_at,updated_at"
        }
        "balance_snapshots" => "id,account_id,date,balance,invested_amount,created_at",
        "bank_connections" => {
            "id,requisition_id,institution_id,institution_name,status,last_synced_at,created_at,updated_at"
        }
        "debts" => {
            "id,account_id,linked_account_id,borrowed_amount,interest_rate,monthly_payment,lender_name,start_date,end_date,insurance_monthly,file_fees,created_at,updated_at"
        }
        "goals" => {
            "id,name,type,target_amount,deadline,monthly_amount,expected_return,start_date,end_date,account_ids,created_at,updated_at"
        }
        "holdings" => {
            "id,account_id,ticker,name,quantity,average_buy_in,current_price,quote_currency,provider_value_eur,provider_pnl_eur,last_synced_at,created_at,updated_at"
        }
        "linked_identities" => "id,issuer,subject,email,display_name,created_at,last_login_at",
        "peer_counterparties" => "id,member_id,name,note,created_at,updated_at",
        "peer_loan_repayments" => {
            "id,account_id,paid_on,amount,kind,note,cash_transaction_id,cash_leg_owned,created_at,updated_at"
        }
        "peer_loan_terms" => {
            "id,account_id,counterparty_id,principal,lent_at,expected_back,annual_rate,rate_period,planned_monthly_amount,planned_months,linked_account_id,lending_transaction_id,note,closed_at,created_at,updated_at"
        }
        "profile" => {
            "user_id,username,role,activated,acknowledged_warning,created_at,updated_at,member_id,display_name,avatar_color,managed"
        }
        "property_valuations" => {
            "id,account_id,valued_at,estimated_value,low_value,high_value,price_per_sqm,provider,confidence,sample_size,source_year,method_detail,created_at,updated_at"
        }
        "shared_resources" => "id,resource_type,resource_id,created_at",
        "transactions" => {
            "id,account_id,date,description,amount,type,category,native_currency,is_manual,tx_type,ticker,quantity,price_per_unit,fees,created_at"
        }
        "wallet_addresses" => "id,chain,address,label,last_synced_at,created_at,updated_at",
        other => panic!("no exporter {other}"),
    }
}

async fn app() -> TestApp {
    let app = TestApp::with_admin().await;
    app.clock.set(now());
    app
}

async fn export(client: &Client, body: Value) -> Response {
    client.post("/api/me/export").json(&body).send().await
}

fn with_password(password: &str, snapshots: bool) -> Value {
    json!({"reAuth": {"password": password}, "includeBalanceSnapshots": snapshots})
}

/// The entries in archive order, decompressed.
fn unzip(response: &Response) -> Vec<(String, Vec<u8>)> {
    let mut archive = zip::ZipArchive::new(Cursor::new(response.body.clone())).expect("a ZIP body");
    (0..archive.len())
        .map(|i| {
            let mut entry = archive.by_index(i).unwrap();
            let mut bytes = Vec::new();
            entry.read_to_end(&mut bytes).unwrap();
            (entry.name().unwrap().into_owned(), bytes)
        })
        .collect()
}

fn entries(response: &Response) -> BTreeMap<String, String> {
    unzip(response)
        .into_iter()
        .map(|(n, b)| (n, String::from_utf8(b).unwrap()))
        .collect()
}

fn data_json(response: &Response) -> Value {
    serde_json::from_str(&entries(response)["data.json"]).unwrap()
}

fn assert_reauth_failed(response: &Response, detail: &str) {
    assert_eq!(response.status, 401, "{}", response.text());
    let body = response.json();
    assert_eq!(body["title"], "REAUTH_FAILED");
    assert_eq!(body["code"], "REAUTH_FAILED");
    assert_eq!(body["detail"], detail);
}

async fn sql(app: &TestApp, statement: &str) {
    sqlx::raw_sql(statement)
        .execute(&app.state.db)
        .await
        .unwrap();
}

#[tokio::test]
async fn export_writes_every_entity_in_spring_order_with_java_headers_and_formats() {
    let app = app().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let checking = account(&app, member, AccountType::Checking, true).await;
    let gone = account(&app, member, AccountType::Checking, true).await;
    sql(
        &app,
        &format!(
            "UPDATE account SET name = 'Courant, \"main\"', current_balance = -750 WHERE id = {c};
             INSERT INTO \"transaction\" (account_id, date, description, amount, native_currency, created_at, is_manual, tx_type, category_manual, budget_neutral)
               VALUES ({c}, '2021-05-04', E'Loyer\\nmai', -750, 'EUR', '2026-03-15T10:00:00.120Z', true, 'WITHDRAWAL', false, false);
             INSERT INTO account_holding (account_id, ticker, name, quantity, average_buy_in, current_price, created_at, updated_at)
               VALUES ({c}, 'ZZQX.PA', 'Fund', 0, 0.00000010, 65000, '2020-01-01T00:00:00.123456Z', '2020-01-01T00:00:00Z');
             INSERT INTO balance_snapshot (account_id, date, balance, invested_amount, created_at, source)
               VALUES ({c}, '2020-01-02', 0, 12.5, '2020-01-02T00:00:00Z', 'MANUAL');
             INSERT INTO peer_counterparty (id, member_id, name, created_at, updated_at) VALUES (77, {member}, 'Marie', now(), now());
             INSERT INTO peer_loan_terms (account_id, counterparty_id, principal, lent_at, rate_period, created_at, updated_at)
               VALUES ({g}, 77, 100, '2020-01-01', 'ANNUAL', now(), now());
             INSERT INTO goal (id, name, member_id, type, target_amount, deadline, created_at, updated_at)
               VALUES (55, 'Vacances', {member}, 'SAVINGS_TARGET', 1000, '2030-01-01', now(), now());
             INSERT INTO goal_account (goal_id, account_id) VALUES (55, {c}), (55, {g});
             INSERT INTO goal_month_override (goal_id, year_month, amount) VALUES (55, '2026-01', 50);
             UPDATE account SET deleted_at = now() WHERE id = {g};",
            c = checking.id,
            g = gone.id,
        ),
    )
    .await;

    let response = export(&client, with_password(ADMIN_PASSWORD, true)).await;

    assert_eq!(response.status, 200);
    assert_eq!(response.header("content-type"), Some("application/zip"));
    assert_eq!(
        response.header("content-disposition"),
        Some("attachment; filename=\"picsou-export-admin-20260315-100000.zip\"")
    );
    let unzipped = unzip(&response);
    let mut expected = vec!["data.json".to_owned()];
    expected.extend(ENTRIES.iter().map(|e| format!("{e}.csv")));
    expected.push("README.txt".to_owned());
    assert_eq!(
        unzipped.iter().map(|(n, _)| n.clone()).collect::<Vec<_>>(),
        expected
    );

    for (name, bytes) in &unzipped {
        if let Some(stem) = name.strip_suffix(".csv") {
            let text = std::str::from_utf8(bytes).unwrap();
            let body = text
                .strip_prefix('\u{FEFF}')
                .expect("CSV starts with a BOM");
            assert!(
                body.starts_with(&format!("{}\r\n", java_header(stem))),
                "{name}: {body}"
            );
        }
    }

    let files = entries(&response);
    let raw = &files["data.json"];
    assert!(raw.starts_with(
        "{\n  \"schema_version\" : \"1\",\n  \"exported_at\" : \"2026-03-15T10:00:00Z\","
    ));
    assert!(raw.contains("\"quantity\" : 0E-8,"));
    assert!(raw.contains("\"average_buy_in\" : 1.0E-7,"));
    assert!(raw.contains("\"account_ownership\" : [ ],"));
    let data: Value = serde_json::from_str(raw).unwrap();
    let keys: Vec<&str> = data
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect();
    let mut expected_keys = vec![
        "schema_version",
        "exported_at",
        "user_id",
        "include_balance_snapshots",
    ];
    expected_keys.extend(ENTRIES);
    assert_eq!(keys, expected_keys);
    assert_eq!(data["profile"]["username"], "admin");
    assert_eq!(data["profile"]["member"]["id"], json!(member));
    assert_eq!(data["accounts"].as_array().unwrap().len(), 1);
    assert_eq!(data["accounts"][0]["cash_balance"], Value::Null);
    assert_eq!(data["transactions"][0]["description"], "Loyer\nmai");
    assert_eq!(
        data["transactions"][0]["created_at"],
        "2026-03-15T10:00:00.120Z"
    );
    assert_eq!(
        data["holdings"][0]["created_at"],
        "2020-01-01T00:00:00.123456Z"
    );
    // The soft-deleted account leaves the goal's accounts and takes its loan with it.
    assert_eq!(data["goals"][0]["account_ids"], json!([checking.id]));
    assert_eq!(
        data["goals"][0]["month_overrides"][0]["year_month"],
        "2026-01"
    );
    assert_eq!(data["peer_loan_terms"], json!([]));
    assert_eq!(data["peer_counterparties"][0]["name"], "Marie");

    assert!(files["accounts.csv"].contains(&format!(
        "{},\"Courant, \"\"main\"\"\",CHECKING,",
        checking.id
    )));
    assert!(
        files["transactions.csv"].contains("\"Loyer\nmai\",-750.00000000,,,EUR,true,WITHDRAWAL,")
    );
    assert!(files["holdings.csv"].contains(",0.00000000,0.00000010,65000.00000000,"));
    assert!(
        files["balance_snapshots.csv"]
            .contains(",2020-01-02,0.00000000,12.50000000,2020-01-02T00:00:00Z\r\n")
    );
    assert!(files["goals.csv"].contains(&format!(",2030-01-01,,,,,{},", checking.id)));
    assert!(files["README.txt"].contains("Exported at:   2026-03-15T10:00:00Z\nUser id:       "));
    assert!(files["README.txt"].contains("SimpleFIN access URLs"));
}

#[tokio::test]
async fn balance_snapshots_are_opt_in() {
    let app = app().await;
    let client = app.login_admin().await;

    let response = export(&client, json!({"reAuth": {"password": ADMIN_PASSWORD}})).await;

    assert_eq!(response.status, 200);
    let files = entries(&response);
    assert!(!files.contains_key("balance_snapshots.csv"));
    let data = data_json(&response);
    assert_eq!(data["include_balance_snapshots"], false);
    assert!(data.get("balance_snapshots").is_none());
    assert_eq!(data["accounts"], json!([]));
}

#[tokio::test]
async fn reauth_refuses_a_missing_blank_or_wrong_password() {
    let app = app().await;
    let client = app.login_admin().await;

    assert_reauth_failed(
        &export(&client, json!({"includeBalanceSnapshots": false})).await,
        "re-auth payload missing",
    );
    assert_reauth_failed(
        &export(&client, json!({"reAuth": null})).await,
        "re-auth payload missing",
    );
    assert_reauth_failed(
        &export(&client, with_password(" ", false)).await,
        "password required",
    );
    assert_reauth_failed(
        &export(&client, json!({"reAuth": {"totpCode": "123456"}})).await,
        "password required",
    );
    assert_reauth_failed(
        &export(&client, with_password("wrong", false)).await,
        "invalid password",
    );
}

#[tokio::test]
async fn a_missing_body_is_a_bad_request() {
    let app = app().await;
    let client = app.login_admin().await;

    let empty = client
        .post("/api/me/export")
        .body("application/json", "")
        .send()
        .await;
    let malformed = client
        .post("/api/me/export")
        .body("application/json", "{")
        .send()
        .await;

    assert_eq!(empty.status, 400);
    assert_eq!(malformed.status, 400);
}

#[tokio::test]
async fn with_2fa_on_the_totp_is_required_and_the_password_is_not_enough() {
    let app = app().await;
    let client = app.login_admin().await;
    let secret = "JBSWY3DPEHPK3PXP";
    let encrypted = app.state.crypto.encrypt(secret).unwrap();
    sqlx::query(
        "INSERT INTO user_mfa (user_id, enabled, totp_secret_enc, enrolled_at) \
         SELECT id, true, $1, now() FROM app_user WHERE username = 'admin'",
    )
    .bind(&encrypted)
    .execute(&app.state.db)
    .await
    .unwrap();

    assert_reauth_failed(
        &export(&client, with_password(ADMIN_PASSWORD, false)).await,
        "totp required",
    );
    assert_reauth_failed(
        &export(&client, json!({"reAuth": {"totpCode": "000000"}})).await,
        "invalid totp",
    );
    let code = picsou::setup_auth::mfa::current_code(&app.state, secret).unwrap();
    let response = export(&client, json!({"reAuth": {"totpCode": code}})).await;
    assert_eq!(response.status, 200);

    for (name, bytes) in unzip(&response) {
        let text = String::from_utf8_lossy(&bytes);
        assert!(!text.contains(secret), "{name}");
        assert!(!text.contains(&encrypted), "{name}");
    }
}

#[tokio::test]
async fn a_valid_reauth_proof_replaces_the_inline_payload() {
    let app = app().await;
    let client = app.login_admin().await;
    let admin_id: i64 = sqlx::query_scalar("SELECT id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    let admin = users::find_by_id(&app.state.db, admin_id)
        .await
        .unwrap()
        .unwrap();

    client.set_cookie("reauth_proof", &app.state.jwt.reauth_proof(&admin));
    assert_eq!(export(&client, json!({})).await.status, 200);

    let (other_id, _) = app.create_member("mallory", "mallory-password").await;
    let mut other = users::find_by_id(&app.state.db, other_id)
        .await
        .unwrap()
        .unwrap();
    other.token_version = admin.token_version;
    client.set_cookie("reauth_proof", &app.state.jwt.reauth_proof(&other));
    assert_reauth_failed(&export(&client, json!({})).await, "re-auth payload missing");

    client.set_cookie("reauth_proof", &app.state.jwt.access_token(&admin, None));
    assert_reauth_failed(&export(&client, json!({})).await, "re-auth payload missing");

    let proof = app.state.jwt.reauth_proof(&admin);
    app.clock.advance(Duration::from_secs(6 * 60));
    client.set_cookie("reauth_proof", &proof);
    assert_reauth_failed(&export(&client, json!({})).await, "re-auth payload missing");
}

#[tokio::test]
async fn a_proof_minted_before_the_token_version_changed_is_refused() {
    let app = app().await;
    let client = app.login_admin().await;
    let admin_id: i64 = sqlx::query_scalar("SELECT id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    let mut admin = users::find_by_id(&app.state.db, admin_id)
        .await
        .unwrap()
        .unwrap();
    admin.token_version += 1;

    client.set_cookie("reauth_proof", &app.state.jwt.reauth_proof(&admin));

    assert_reauth_failed(&export(&client, json!({})).await, "re-auth payload missing");
}

#[tokio::test]
async fn the_sixth_export_within_the_hour_is_refused_with_an_empty_429() {
    let app = app().await;
    let client = app.login_admin().await;

    for _ in 0..5 {
        assert_eq!(
            export(&client, with_password("wrong", false)).await.status,
            401
        );
    }
    let refused = export(&client, with_password(ADMIN_PASSWORD, false)).await;
    assert_eq!(refused.status, 429);
    assert!(refused.body.is_empty());

    app.clock.advance(Duration::from_secs(61 * 60));
    let client = app.login_admin().await;
    assert_eq!(
        export(&client, with_password(ADMIN_PASSWORD, false))
            .await
            .status,
        200
    );
}

#[tokio::test]
async fn the_export_is_the_users_own_data_even_when_managing_another_profile() {
    let app = app().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let mine = account(&app, member, AccountType::Checking, true).await;
    let managed = app.create_managed_member("Kid").await;
    let kids = account(&app, managed, AccountType::Savings, true).await;
    let (_, other_member) = app.create_member("bob", "bob-password-1").await;
    let bobs = account(&app, other_member, AccountType::Checking, true).await;
    sql(
        &app,
        &format!(
            "INSERT INTO account_ownership (account_id, member_id, share_percent, created_at, updated_at)
               VALUES ({mine}, {member}, 60, now(), now()), ({mine}, {other_member}, 40, now(), now());
             INSERT INTO wallet_address (chain, address, member_id, created_at, updated_at)
               VALUES ('BITCOIN', 'bc1-bob', {other_member}, now(), now());
             INSERT INTO shared_resource (owner_member_id, resource_type, resource_id, created_at)
               VALUES ({member}, 'WALLET', 1, now()), ({member}, 'ACCOUNT', {mine}, now()), ({other_member}, 'ACCOUNT', {bobs}, now());",
            mine = mine.id,
            bobs = bobs.id,
        ),
    )
    .await;

    let response = client
        .post(&format!("/api/me/export?memberId={managed}"))
        .json(&with_password(ADMIN_PASSWORD, false))
        .send()
        .await;

    assert_eq!(response.status, 200);
    let data = data_json(&response);
    let ids: Vec<i64> = data["accounts"]
        .as_array()
        .unwrap()
        .iter()
        .map(|a| a["id"].as_i64().unwrap())
        .collect();
    assert_eq!(ids, vec![mine.id]);
    assert!(!ids.contains(&kids.id));
    assert_eq!(data["account_ownership"].as_array().unwrap().len(), 1);
    assert_eq!(data["account_ownership"][0]["member_id"], json!(member));
    assert_eq!(
        data["account_ownership"][0]["share_percent"].to_string(),
        "60.000"
    );
    assert_eq!(data["wallet_addresses"], json!([]));
    let types: Vec<&str> = data["shared_resources"]
        .as_array()
        .unwrap()
        .iter()
        .map(|s| s["resource_type"].as_str().unwrap())
        .collect();
    assert_eq!(types, vec!["ACCOUNT", "WALLET"]);
}

#[tokio::test]
async fn no_secret_reaches_the_archive() {
    let app = app().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let link = "https://auth.example.test/start?token=SECRET_ONE_SHOT_AUTH_XyZ789";
    sql(
        &app,
        &format!(
            "UPDATE app_user SET activation_token = 'ACTIVATION_TOKEN_aBcDeF123' WHERE username = 'admin';
             INSERT INTO requisition (requisition_id, institution_id, institution_name, status, auth_link, created_at, updated_at, member_id)
               VALUES ('req_abc123', 'bnpparibas', 'BNP Paribas', 'LINKED', '{link}', now(), now(), {member});"
        ),
    )
    .await;
    let hash: String =
        sqlx::query_scalar("SELECT password_hash FROM app_user WHERE username = 'admin'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();

    let response = export(&client, with_password(ADMIN_PASSWORD, false)).await;

    assert_eq!(response.status, 200);
    let files = entries(&response);
    assert!(files["bank_connections.csv"].contains(",req_abc123,bnpparibas,BNP Paribas,LINKED,"));
    for (name, text) in &files {
        for secret in [
            hash.as_str(),
            "ACTIVATION_TOKEN_aBcDeF123",
            link,
            "password_hash",
            "activation_token",
            "auth_link",
        ] {
            assert!(!text.contains(secret), "{name} leaks {secret}");
        }
    }
}

#[tokio::test]
async fn a_failure_mid_stream_stamps_a_marker_into_a_readable_archive() {
    let app = app().await;
    let client = app.login_admin().await;
    let member = admin_member(&app).await;
    let broken = account(&app, member, AccountType::Checking, true).await;
    sql(
        &app,
        &format!(
            "UPDATE account SET cash_balance = 'NaN' WHERE id = {}",
            broken.id
        ),
    )
    .await;

    let response = export(&client, with_password(ADMIN_PASSWORD, false)).await;

    assert_eq!(response.status, 200);
    let files = entries(&response);
    assert!(files["__EXPORT_FAILED__.txt"].contains("incomplete"));
    assert!(!files.contains_key("README.txt"));
}
