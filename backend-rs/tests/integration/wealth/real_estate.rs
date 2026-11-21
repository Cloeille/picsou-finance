//! Real estate summary (shares, linked loans, SCPI paper line), valuation history, geocoding.

use picsou::ledger::accounts::NewAccount;
use picsou::ledger::model::AccountType;
use picsou::testing::TestApp;
use serde_json::json;
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{account, d};

async fn admin_member(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .unwrap()
}

#[tokio::test]
async fn the_summary_weighs_properties_loans_and_scpi_by_the_members_share() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let member = admin_member(&app).await;
    let mut flat = NewAccount::new(member, "Appartement", AccountType::RealEstate);
    flat.is_manual = true;
    flat.current_balance = d("300000");
    let flat = account(&app, flat).await.id;
    sqlx::query(
        "INSERT INTO real_estate_metadata (account_id, member_id, purchase_price, notary_fees, city, rental_income, valuation_mode) \
         VALUES ($1, $2, 250000, 20000, 'Lyon', 900, 'MANUAL')",
    )
    .bind(flat)
    .bind(member)
    .execute(&app.state.db)
    .await
    .unwrap();
    let mut loan = NewAccount::new(member, "Prêt immo", AccountType::Loan);
    loan.is_manual = true;
    loan.current_balance = d("120000");
    let loan = account(&app, loan).await.id;
    sqlx::query(
        "INSERT INTO debt (member_id, account_id, linked_account_id, borrowed_amount, lender_name, monthly_payment) \
         VALUES ($1, $2, $3, 150000, 'Banque', 800)",
    )
    .bind(member)
    .bind(loan)
    .bind(flat)
    .execute(&app.state.db)
    .await
    .unwrap();
    let mut scpi = NewAccount::new(member, "CORUM XL", AccountType::Scpi);
    scpi.is_manual = true;
    scpi.current_balance = d("5000");
    let scpi = account(&app, scpi).await.id;
    sqlx::query("INSERT INTO scpi_position (account_id, member_id, share_count, management_company) VALUES ($1, $2, 25, 'CORUM')")
        .bind(scpi)
        .bind(member)
        .execute(&app.state.db)
        .await
        .unwrap();

    let response = admin.get("/api/real-estate/summary").send().await;
    assert_eq!(response.status, 200, "{}", response.text());
    let body = response.json();
    assert_eq!(body["grossValue"].to_string(), "300000.00");
    assert_eq!(body["outstandingDebt"].to_string(), "120000.00");
    assert_eq!(body["netValue"].to_string(), "180000.00");
    assert_eq!(body["costBasis"].to_string(), "270000.00");
    assert_eq!(body["unrealizedGainPercent"].to_string(), "11.11");
    assert_eq!(body["loanToValue"].to_string(), "40.00");
    assert_eq!(body["monthlyRentalIncome"].to_string(), "900.00");
    let property = &body["properties"][0];
    assert_eq!(property["city"], "Lyon");
    assert_eq!(property["valuationMode"], "MANUAL");
    assert_eq!(property["loans"][0]["lenderName"], "Banque");
    assert_eq!(property["loans"][0]["monthlyPayment"].to_string(), "800.00");
    assert_eq!(body["paperGross"].to_string(), "5000.00");
    assert_eq!(body["paper"][0]["managementCompany"], "CORUM");
    assert_eq!(body["paper"][0]["valuationStatus"], "PRICE_INCOMPLETE");

    // A member who cannot read the property sees an empty summary and a 404 on its history.
    app.create_member("other", "password-1234").await;
    let other = app.login("other", "password-1234").await;
    let body = other.get("/api/real-estate/summary").send().await.json();
    assert_eq!(body["properties"], json!([]));
    assert!(body.get("loanToValue").is_none());
    assert_eq!(
        other
            .get(&format!("/api/real-estate/{flat}/valuations"))
            .send()
            .await
            .status,
        404
    );
}

#[tokio::test]
async fn the_valuation_history_is_newest_first() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let member = admin_member(&app).await;
    let flat = account(
        &app,
        NewAccount::new(member, "Maison", AccountType::RealEstate),
    )
    .await
    .id;
    for (date, value) in [("2024-06-01", "200000"), ("2025-06-01", "210000")] {
        sqlx::query(
            "INSERT INTO property_valuation (account_id, member_id, valued_at, estimated_value, provider, confidence, sample_size) \
             VALUES ($1, $2, $3::date, $4::numeric, 'CEREMA_DVF', 'MEDIUM', 12)",
        )
        .bind(flat)
        .bind(member)
        .bind(date)
        .bind(value)
        .execute(&app.state.db)
        .await
        .unwrap();
    }
    let body = admin
        .get(&format!("/api/real-estate/{flat}/valuations"))
        .send()
        .await
        .json();
    assert_eq!(body[0]["valuedAt"], "2025-06-01");
    assert_eq!(body[0]["estimatedValue"].to_string(), "210000.00000000");
    assert_eq!(body[0]["confidence"], "MEDIUM");
    assert_eq!(body[1]["valuedAt"], "2024-06-01");
}

#[tokio::test]
async fn geocoding_maps_features_skips_short_queries_and_is_rate_limited() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/search"))
        .and(query_param("q", "8 bd du port"))
        .and(query_param("limit", "10"))
        .and(query_param("index", "address"))
        .respond_with(ResponseTemplate::new(200).set_body_raw(
            r#"{"features":[
                {"geometry":{"coordinates":[2.290084,49.897443]},"properties":{"label":"8 Boulevard du Port 80000 Amiens","score":0.49159121588068583,"citycode":"80021","postcode":"80000","city":"Amiens"}},
                {"properties":{"label":"no place"}}
            ]}"#,
            "application/json",
        ))
        .mount(&server)
        .await;
    let app = TestApp::builder()
        .with_admin()
        .env("GEOCODING_API_URL", server.uri())
        .spawn()
        .await;
    let admin = app.login_admin().await;

    assert_eq!(
        admin.get("/api/geocode?q=ab").send().await.json(),
        json!([])
    );
    let body = admin
        .get("/api/geocode?q=%208%20bd%20du%20port%20&limit=50")
        .send()
        .await
        .json();
    assert_eq!(body.as_array().unwrap().len(), 1);
    assert_eq!(body[0]["inseeCode"], "80021");
    assert_eq!(body[0]["latitude"].to_string(), "49.897443");
    assert_eq!(body[0]["score"].to_string(), "0.49159121588068583");
    assert_eq!(admin.get("/api/geocode").send().await.status, 400);

    for _ in 0..59 {
        admin.get("/api/geocode?q=rue").send().await;
    }
    let limited = admin.get("/api/geocode?q=rue").send().await;
    assert_eq!(limited.status, 429);
    assert!(limited.body.is_empty());
}
