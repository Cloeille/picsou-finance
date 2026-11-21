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

#[tokio::test]
async fn property_valuation_adjusts_reindexes_geocodes_and_respects_manual_lock() {
    use picsou::ledger::model::{ValuationMode, ValuationStatus};
    use picsou::wealth::real_estate::valuation::{
        FullMetadata, compute_adjustments, estimate, refresh_all_for_member,
    };

    // Pure adjustments: capped additive extras reconcile with added_amount, energy supersedes era,
    // bounds bracket the adjusted estimate.
    let hoarder = FullMetadata {
        id: 1,
        member_id: 1,
        account_id: 1,
        purchase_price: d("300000"),
        agency_fees: None,
        notary_fees: None,
        works_cost: None,
        property_type: Some("HOUSE".to_owned()),
        address: None,
        postal_code: None,
        city: None,
        country: Some("FR".to_owned()),
        insee_code: Some("33063".to_owned()),
        latitude: None,
        longitude: None,
        surface_area: Some(d("100")),
        land_area: Some(d("100000")),
        construction_year: Some(1960),
        rooms: None,
        bathrooms: Some(2),
        floor_number: Some(0),
        floors_total: None,
        has_elevator: None,
        garage_count: 20,
        parking_count: 20,
        has_garden: false,
        has_terrace: false,
        has_balcony: false,
        energy_class: Some("A".to_owned()),
        valuation_mode: ValuationMode::Estimated,
    };
    let adj = compute_adjustments(&hoarder, d("400000"), Some(d("4000")));
    assert!(!adj.applied.iter().any(|a| a.code.starts_with("ERA_")));
    assert_eq!(adj.added_amount.to_string(), "240000");
    let disclosed_additive: rust_decimal::Decimal = adj
        .applied
        .iter()
        .filter(|a| a.sqm.is_some())
        .filter_map(|a| a.amount)
        .sum();
    assert_eq!(disclosed_additive.to_string(), "240000.00");
    let low = adj.apply_to(Some(d("340000"))).unwrap();
    let high = adj.apply_to(Some(d("470000"))).unwrap();
    assert!(adj.value >= low && adj.value <= high);

    // End-to-end estimate over wiremocked IGN + Cerema + INSEE.
    let geocode_srv = MockServer::start().await;
    let cerema_srv = MockServer::start().await;
    let insee_srv = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/search"))
        .respond_with(ResponseTemplate::new(200).set_body_string(
            r#"{"features":[{"geometry":{"coordinates":[-0.58,44.84]},
               "properties":{"label":"10 Rue Test 33000 Bordeaux","score":0.95,"citycode":"33063","postcode":"33000","city":"Bordeaux"}}]}"#,
        ))
        .mount(&geocode_srv)
        .await;
    Mock::given(method("GET"))
        .and(path("/indicateurs/dv3f/prix/annuel/"))
        .and(query_param("code", "33063"))
        .respond_with(ResponseTemplate::new(200).set_body_string(
            r#"{"count":1,"results":[{"annee":"2024","echelle":"communes","code":"33063",
               "nbtrans_cod111":200,"pxm2_median_cod111":4000.00,"pxm2_q25_cod111":3500.00,"pxm2_q75_cod111":4700.00}]}"#,
        ))
        .mount(&cerema_srv)
        .await;
    Mock::given(method("GET"))
        .and(path("/data/SERIES_BDM/010567060"))
        .respond_with(ResponseTemplate::new(200).set_body_string(
            r#"<DataSet><Obs TIME_PERIOD="2024-Q3" OBS_VALUE="100.0"/><Obs TIME_PERIOD="2025-Q4" OBS_VALUE="105.0"/></DataSet>"#,
        ))
        .mount(&insee_srv)
        .await;

    let app = TestApp::builder()
        .with_admin()
        .env("GEOCODING_API_URL", geocode_srv.uri())
        .env("CEREMA_API_URL", cerema_srv.uri())
        .env("INSEE_API_URL", insee_srv.uri())
        .spawn()
        .await;
    let member = admin_member(&app).await;

    // ESTIMATED house without insee_code -> geocodes, values, re-indexes (+5%), writes balance.
    let mut est_acc = NewAccount::new(member, "Maison", AccountType::RealEstate);
    est_acc.is_manual = true;
    est_acc.current_balance = d("300000");
    let est_id = account(&app, est_acc).await.id;
    sqlx::query(
        "INSERT INTO real_estate_metadata (account_id, member_id, purchase_price, property_type, address, city, surface_area, valuation_mode) \
         VALUES ($1, $2, 300000, 'HOUSE', '10 Rue Test', 'Bordeaux', 100, 'ESTIMATED')",
    )
    .bind(est_id)
    .bind(member)
    .execute(&app.state.db)
    .await
    .unwrap();

    // MANUAL house -> computes & stores valuation, leaves current_balance untouched.
    let mut man_acc = NewAccount::new(member, "Manuel", AccountType::RealEstate);
    man_acc.is_manual = true;
    man_acc.current_balance = d("250000");
    let man_id = account(&app, man_acc).await.id;
    sqlx::query(
        "INSERT INTO real_estate_metadata (account_id, member_id, purchase_price, property_type, insee_code, surface_area, valuation_mode) \
         VALUES ($1, $2, 250000, 'HOUSE', '33063', 100, 'MANUAL')",
    )
    .bind(man_id)
    .bind(member)
    .execute(&app.state.db)
    .await
    .unwrap();

    // Uncovered area (Strasbourg 67482) with 0 balance -> returns UNSUPPORTED_AREA and seeds cost_basis floor.
    let mut alsace = NewAccount::new(member, "Strasbourg", AccountType::RealEstate);
    alsace.is_manual = true;
    alsace.current_balance = d("0");
    let alsace_id = account(&app, alsace).await.id;
    sqlx::query(
        "INSERT INTO real_estate_metadata (account_id, member_id, purchase_price, notary_fees, property_type, insee_code, surface_area, valuation_mode) \
         VALUES ($1, $2, 200000, 15000, 'HOUSE', '67482', 80, 'ESTIMATED')",
    )
    .bind(alsace_id)
    .bind(member)
    .execute(&app.state.db)
    .await
    .unwrap();

    let est_resp = estimate(&app.state, est_id, member).await.unwrap();
    assert_eq!(est_resp.status, ValuationStatus::Ok);
    assert!(est_resp.applied_to_balance);
    assert_eq!(est_resp.estimated_value.unwrap().to_string(), "420000.00");
    assert_eq!(est_resp.reindex_ratio.unwrap().to_string(), "1.050000");

    let new_bal: rust_decimal::Decimal =
        sqlx::query_scalar("SELECT current_balance FROM account WHERE id = $1")
            .bind(est_id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(new_bal.to_string(), "420000.00000000");

    let man_resp = estimate(&app.state, man_id, member).await.unwrap();
    assert_eq!(man_resp.status, ValuationStatus::Ok);
    assert!(!man_resp.applied_to_balance);
    let man_bal: rust_decimal::Decimal =
        sqlx::query_scalar("SELECT current_balance FROM account WHERE id = $1")
            .bind(man_id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(man_bal.to_string(), "250000.00000000");

    let alsace_resp = estimate(&app.state, alsace_id, member).await.unwrap();
    assert_eq!(alsace_resp.status, ValuationStatus::UnsupportedArea);
    let floor_bal: rust_decimal::Decimal =
        sqlx::query_scalar("SELECT current_balance FROM account WHERE id = $1")
            .bind(alsace_id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(floor_bal.to_string(), "215000.00000000");

    // Scheduled refresh handles all 3 independently and returns 2 OK (Maison + Manuel).
    assert_eq!(refresh_all_for_member(&app.state, member).await, 2);
}
