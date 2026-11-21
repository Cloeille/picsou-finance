//! Cerema DV3F valuation provider tests (CeremaDv3fValuationProviderTest).

use picsou::ledger::model::ValuationConfidence;
use picsou::market::cerema::{CeremaDv3f, PropertyKind, ValuationInput};
use picsou::testing::TestApp;
use rust_decimal::Decimal;
use std::str::FromStr;
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

const BORDEAUX_JSON: &str = r#"{
  "count": 2,
  "results": [
    {"annee": "2024", "echelle": "communes", "code": "33063", "libelle": "Bordeaux",
     "nbtrans_cod111": 900, "pxm2_median_cod111": 4700.00,
     "pxm2_q25_cod111": 3900.00, "pxm2_q75_cod111": 5700.00,
     "nbtrans_cod121": 3000, "pxm2_median_cod121": 4000.00},
    {"annee": "2025", "echelle": "communes", "code": "33063", "libelle": "Bordeaux",
     "nbtrans_cod111": 1048, "pxm2_median_cod111": 4864.165,
     "pxm2_q25_cod111": 4042.6475, "pxm2_q75_cod111": 5906.425,
     "nbtrans_cod121": 3345, "pxm2_median_cod121": 4114.89,
     "pxm2_q25_cod121": 3455.88, "pxm2_q75_cod121": 4863.64,
     "nbtrans_cod121x3": 687, "pxm2_median_cod121x3": 3811.19,
     "pxm2_q25_cod121x3": 3277.37, "pxm2_q75_cod121x3": 4511.845,
     "nbtrans_cod121x1": 5, "pxm2_median_cod121x1": 9999.00,
     "nbtrans_cod121x5": 82, "pxm2_median_cod121x5": 3577.475}
  ]
}"#;

const EMPTY_JSON: &str = r#"{"count": 0, "next": null, "previous": null, "results": []}"#;

fn house(insee: &str, area: &str) -> ValuationInput {
    let dept = if insee.starts_with("97") && insee.len() >= 3 {
        insee[..3].to_owned()
    } else {
        insee[..2].to_owned()
    };
    ValuationInput {
        insee_code: Some(insee.to_owned()),
        department_code: Some(dept),
        country_code: Some("FR".to_owned()),
        kind: Some(PropertyKind::House),
        living_area: Some(Decimal::from_str(area).unwrap()),
        land_area: None,
        rooms: None,
        construction_year: None,
        latitude: None,
        longitude: None,
    }
}

fn apartment(insee: &str, area: &str, rooms: Option<i16>) -> ValuationInput {
    ValuationInput {
        kind: Some(PropertyKind::Apartment),
        rooms,
        ..house(insee, area)
    }
}

#[test]
fn supports_refuses_alsace_moselle_mayotte_non_fr_and_non_estimable_kinds() {
    let provider = CeremaDv3f::new(reqwest::Client::new(), "http://127.0.0.1:9");
    assert!(!provider.supports(&house("67482", "100")));
    assert!(!provider.supports(&house("68066", "100")));
    assert!(!provider.supports(&house("57463", "100")));
    assert!(!provider.supports(&house("97611", "100")));
    assert!(provider.supports(&house("33063", "100")));

    for kind in [
        PropertyKind::Building,
        PropertyKind::Land,
        PropertyKind::Parking,
        PropertyKind::Commercial,
    ] {
        let mut input = house("33063", "100");
        input.kind = Some(kind);
        assert!(!provider.supports(&input));
    }

    let mut no_area = house("33063", "100");
    no_area.living_area = None;
    assert!(!provider.supports(&no_area));

    let mut foreign = house("33063", "100");
    foreign.country_code = Some("BE".to_owned());
    assert!(!provider.supports(&foreign));
}

#[tokio::test]
async fn estimate_picks_freshest_vintage_room_bucket_and_department_fallback() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/indicateurs/dv3f/prix/annuel/"))
        .and(query_param("echelle", "communes"))
        .and(query_param("code", "33063"))
        .respond_with(ResponseTemplate::new(200).set_body_string(BORDEAUX_JSON))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/indicateurs/dv3f/prix/annuel/"))
        .and(query_param("echelle", "communes"))
        .and(query_param("code", "33999"))
        .respond_with(ResponseTemplate::new(200).set_body_string(EMPTY_JSON))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/indicateurs/dv3f/prix/annuel/"))
        .and(query_param("echelle", "departements"))
        .and(query_param("code", "33"))
        .respond_with(ResponseTemplate::new(200).set_body_string(
            r#"{"count":1,"results":[{"annee":"2025","echelle":"departements","code":"33",
                "nbtrans_cod111":10195,"pxm2_median_cod111":2800.00}]}"#,
        ))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/indicateurs/dv3f/prix/annuel/"))
        .and(query_param("code", "75101"))
        .respond_with(ResponseTemplate::new(503).set_body_string("{}"))
        .mount(&server)
        .await;

    let app = TestApp::builder()
        .env("CEREMA_API_URL", server.uri())
        .spawn()
        .await;
    let cerema = app.state.market.cerema();

    // Freshest vintage (2025 over 2024) for house.
    let r = cerema
        .estimate(&house("33063", "100"))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(r.source_year, Some(2025));
    assert_eq!(r.price_per_sqm.to_string(), "4864.165");
    assert_eq!(r.estimated_value.to_string(), "486416.50");
    assert_eq!(r.low_value.unwrap().to_string(), "404264.75");
    assert_eq!(r.high_value.unwrap().to_string(), "590642.50");
    assert_eq!(r.sample_size, Some(1048));
    assert_eq!(r.confidence, ValuationConfidence::High);
    assert_eq!(r.scale, "communes");

    // 3-room apartment prefers cod121x3.
    let flat3 = cerema
        .estimate(&apartment("33063", "66", Some(3)))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(flat3.price_per_sqm.to_string(), "3811.19");
    assert_eq!(flat3.sample_size, Some(687));

    // Thin room bucket (5 < 20) falls back to commune-wide cod121.
    let studio = cerema
        .estimate(&apartment("33063", "25", Some(1)))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(studio.price_per_sqm.to_string(), "4114.89");

    // 7-room apartment clamps to cod121x5.
    let big = cerema
        .estimate(&apartment("33063", "150", Some(7)))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(big.price_per_sqm.to_string(), "3577.475");

    // Empty commune falls back to department with LOW confidence.
    let fallback = cerema
        .estimate(&house("33999", "100"))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(fallback.scale, "departements");
    assert_eq!(fallback.confidence, ValuationConfidence::Low);
    assert_eq!(fallback.estimated_value.to_string(), "280000.00");

    // 503 outage returns ProviderError rather than Ok(None).
    assert!(cerema.estimate(&house("75101", "50")).await.is_err());
}
