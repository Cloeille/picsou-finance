//! IGN Géoplateforme geocoder tests (GeoplateformeGeocoderTest).

use picsou::market::geocoding::{GeocodeResult, autocomplete, geocode};
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const PARIS_JSON: &str = r#"{
  "type": "FeatureCollection",
  "features": [{
    "type": "Feature",
    "geometry": {"type": "Point", "coordinates": [2.33031, 48.868546]},
    "properties": {
      "label": "1 Rue de la Paix 75002 Paris",
      "score": 0.96406,
      "postcode": "75002",
      "citycode": "75102",
      "city": "Paris",
      "banId": "50d9aa1d-5049-4ec2-a439-0649f829a186"
    }
  }]
}"#;

#[tokio::test]
async fn geocode_maps_insee_code_geojson_lon_lat_order_and_department_codes() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/search"))
        .respond_with(ResponseTemplate::new(200).set_body_string(PARIS_JSON))
        .mount(&server)
        .await;

    let http = reqwest::Client::new();
    let r = geocode(&http, &server.uri(), "1 rue de la Paix Paris")
        .await
        .expect("result");
    assert_eq!(r.insee_code.as_deref(), Some("75102"));
    assert_eq!(r.postcode.as_deref(), Some("75002"));
    assert_eq!(r.city.as_deref(), Some("Paris"));
    assert_eq!(
        r.ban_id.as_deref(),
        Some("50d9aa1d-5049-4ec2-a439-0649f829a186")
    );
    assert_eq!(r.score.unwrap().to_string(), "0.96406");
    // GeoJSON order is [lon, lat].
    assert_eq!(r.latitude.unwrap().to_string(), "48.868546");
    assert_eq!(r.longitude.unwrap().to_string(), "2.33031");
    assert_eq!(r.department_code().as_deref(), Some("75"));

    // Overseas 3-digit department code (Mayotte = 976).
    let mayotte = GeocodeResult {
        label: Some("Mamoudzou".to_owned()),
        score: None,
        latitude: None,
        longitude: None,
        insee_code: Some("97611".to_owned()),
        postcode: Some("97600".to_owned()),
        city: Some("Mamoudzou".to_owned()),
        ban_id: None,
    };
    assert_eq!(mayotte.department_code().as_deref(), Some("976"));
}

#[tokio::test]
async fn geocode_handles_blank_empty_and_outages_gracefully() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/search"))
        .respond_with(ResponseTemplate::new(503))
        .expect(1)
        .mount(&server)
        .await;

    let http = reqwest::Client::new();
    // Blank query never hits the server.
    assert!(
        autocomplete(&http, &server.uri(), "   ", 5)
            .await
            .is_empty()
    );
    // 503 answers empty without panicking.
    assert!(
        geocode(&http, &server.uri(), "1 rue de la Paix")
            .await
            .is_none()
    );
}
