//! INSEE BDM housing price index provider tests (InseeBdmIndexProvider).

use std::time::Duration;

use picsou::market::cerema::PropertyKind;
use picsou::market::insee::{YearMonth, parse_sdmx};
use picsou::testing::TestApp;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const SDMX_XML: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<message:StructureSpecificData xmlns:message="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message">
  <message:DataSet>
    <Series IDBANK="010567060">
      <Obs TIME_PERIOD="2024-Q3" OBS_VALUE="120.0"/>
      <Obs TIME_PERIOD="2025-Q2" OBS_VALUE="126.0"/>
      <Obs TIME_PERIOD="2025" OBS_VALUE="999.0"/>
      <Obs TIME_PERIOD="2025-Q3" OBS_VALUE="bad"/>
    </Series>
  </message:DataSet>
</message:StructureSpecificData>"#;

#[test]
fn sdmx_parser_reads_quarterly_observations_and_ignores_annual_or_malformed() {
    let map = parse_sdmx(SDMX_XML).expect("parsed");
    assert_eq!(map.len(), 2);
    assert_eq!(map[&20243].to_string(), "120.0");
    assert_eq!(map[&20252].to_string(), "126.0");
}

#[tokio::test]
async fn reindex_ratio_floors_to_latest_quarter_caches_24h_and_serves_stale_on_outage() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/data/SERIES_BDM/010567060"))
        .respond_with(ResponseTemplate::new(200).set_body_string(SDMX_XML))
        .expect(1)
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/data/SERIES_BDM/010567060"))
        .respond_with(ResponseTemplate::new(503))
        .mount(&server)
        .await;

    let app = TestApp::builder()
        .env("INSEE_API_URL", server.uri())
        .spawn()
        .await;
    let insee = app.state.market.insee();

    // Same period short-circuits to 1 without HTTP.
    assert_eq!(
        insee
            .reindex_ratio(
                YearMonth::new(2025, 1),
                YearMonth::new(2025, 1),
                Some(PropertyKind::Apartment)
            )
            .await
            .map(|d| d.to_string())
            .as_deref(),
        Some("1")
    );

    // 2024-07 (2024-Q3 = 120.0) -> 2026-01 (floors to 2025-Q2 = 126.0) => 126 / 120 = 1.050000.
    let ratio = insee
        .reindex_ratio(
            YearMonth::new(2024, 7),
            YearMonth::new(2026, 1),
            Some(PropertyKind::House),
        )
        .await
        .expect("ratio");
    assert_eq!(ratio.to_string(), "1.050000");

    // Second call within 24h hits cache without another HTTP request.
    let cached = insee
        .reindex_ratio(
            YearMonth::new(2024, 7),
            YearMonth::new(2026, 1),
            Some(PropertyKind::House),
        )
        .await
        .expect("cached");
    assert_eq!(cached.to_string(), "1.050000");

    // After 25h the cache expires, the retry sees 503, and the stale series is served instead of None.
    app.clock.advance(Duration::from_secs(25 * 3600));
    let stale = insee
        .reindex_ratio(
            YearMonth::new(2024, 7),
            YearMonth::new(2026, 1),
            Some(PropertyKind::House),
        )
        .await
        .expect("stale fallback");
    assert_eq!(stale.to_string(), "1.050000");
}
