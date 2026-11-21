//! EnableBankingBankConnectorTest: signing, the institution catalog and the balance selection.

use jsonwebtoken::{Algorithm, DecodingKey, Validation};
use reqwest::StatusCode;
use serde_json::{Value, json};
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, ResponseTemplate};

use super::support::{APP_ID, bank, keys};

fn verify(authorization: &str) -> (jsonwebtoken::Header, Value) {
    let token = authorization.strip_prefix("Bearer ").expect("bearer token");
    let mut validation = Validation::new(Algorithm::RS256);
    validation.set_audience(&["api.enablebanking.com"]);
    validation.validate_exp = false;
    let decoded = jsonwebtoken::decode::<Value>(
        token,
        &DecodingKey::from_rsa_pem(keys().public_pem.as_bytes()).unwrap(),
        &validation,
    )
    .expect("a JWT signed with the configured key");
    (decoded.header, decoded.claims)
}

#[tokio::test]
async fn institutions_are_filtered_deduplicated_and_signed_with_the_configured_key() {
    let b = bank().await;
    Mock::given(method("GET"))
        .and(path("/aspsps"))
        .and(query_param("country", "FR"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"aspsps": [
            {"name": "Swan", "country": "FR", "psu_types": ["business"], "bic": "SWNBFR22"},
            {"name": "Swan", "country": "FR", "psu_types": ["business"], "logo": "https://logo.test/swan.png"},
            {"name": "Crédit Mutuel", "country": "FR", "psu_types": ["business", "personal"]},
            {"name": "Odd Bank", "psu_types": ["corporate"]},
            {"name": "BoursoBank", "country": "FR", "psu_types": ["personal"]}
        ]})))
        .mount(&b.server)
        .await;
    let response = b.admin.get("/api/sync/institutions?query=").send().await;
    assert_eq!(response.status, StatusCode::OK);
    assert_eq!(
        response.json(),
        json!([
            {"id": "Swan::FR::business", "name": "Swan", "logoUrl": "https://logo.test/swan.png", "country": "FR", "psuType": "business"},
            {"id": "Crédit Mutuel::FR::personal", "name": "Crédit Mutuel", "country": "FR", "psuType": "personal"},
            {"id": "Odd Bank::FR::corporate", "name": "Odd Bank", "country": "FR", "psuType": "corporate"},
            {"id": "BoursoBank::FR::personal", "name": "BoursoBank", "country": "FR", "psuType": "personal"}
        ])
    );
    let filtered = b
        .admin
        .get("/api/sync/institutions?query=bourso&country=FR")
        .send()
        .await
        .json();
    assert_eq!(filtered.as_array().unwrap().len(), 1);

    let request = &b.server.received_requests().await.unwrap()[0];
    let authorization = request
        .headers
        .get("authorization")
        .unwrap()
        .to_str()
        .unwrap();
    let (header, claims) = verify(authorization);
    assert_eq!(
        header.kid.as_deref(),
        Some(APP_ID),
        "the kid falls back to the application id"
    );
    assert_eq!(header.alg, Algorithm::RS256);
    let now = b.app.state.clock.now().timestamp();
    assert_eq!(claims["iss"], APP_ID);
    assert_eq!(claims["iat"], now);
    assert_eq!(claims["exp"], now + 3600);
}

#[tokio::test]
async fn a_stored_key_id_wins_over_the_application_id() {
    let b = bank().await;
    sqlx::query(
        "INSERT INTO app_setting (setting_key, value) VALUES ('enablebanking.key-id', 'kid-9')",
    )
    .execute(&b.app.state.db)
    .await
    .unwrap();
    Mock::given(method("GET"))
        .and(path("/aspsps"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"aspsps": []})))
        .mount(&b.server)
        .await;
    assert_eq!(
        b.admin.get("/api/sync/institutions").send().await.json(),
        json!([])
    );
    let request = &b.server.received_requests().await.unwrap()[0];
    let (header, _) = verify(
        request
            .headers
            .get("authorization")
            .unwrap()
            .to_str()
            .unwrap(),
    );
    assert_eq!(header.kid.as_deref(), Some("kid-9"));
}

#[tokio::test]
async fn institution_search_caps_the_result_at_thirty() {
    let b = bank().await;
    let aspsps: Vec<Value> = (0..40)
        .map(|i| json!({"name": format!("Bank {i}"), "country": "DE", "psu_types": ["personal"]}))
        .collect();
    Mock::given(method("GET"))
        .and(path("/aspsps"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "aspsps": aspsps })))
        .mount(&b.server)
        .await;
    let found = b
        .admin
        .get("/api/sync/institutions?country=DE")
        .send()
        .await
        .json();
    assert_eq!(found.as_array().unwrap().len(), 30);
}
