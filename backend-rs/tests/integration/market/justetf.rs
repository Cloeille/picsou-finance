//! justETF provider & label tests (JustEtfProviderTest, JustEtfLabelsTest).

use chrono::NaiveDate;
use picsou::market::justetf::{
    DistributionPolicy, JustEtf, Replication, country_key, is_other, parse, sector_key,
};
use picsou::testing::TestApp;
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

const CW8_HTML: &str = r#"
<html><body>
  <span data-testid="etf-profile-header_isin-value">LU1681043599</span>
  <div data-testid="tl_etf-basics_value_ter">0.38% p.a.</div>
  <div data-testid="tl_etf-basics_value_distribution-policy">Accumulating</div>
  <div data-testid="tl_etf-basics_value_replication">Synthetic (Unfunded swap)</div>
  <div data-testid="tl_etf-basics_value_domicile-country">Luxembourg</div>
  <span data-testid="tl_etf-holdings_reference-date">As of 30/06/2026</span>
  <table>
    <tr data-testid="etf-holdings_top-holdings_row">
      <td><a href="/en/stock">Apple &amp; Co</a></td>
      <td data-testid="tl_etf-holdings_top-holdings_value_percentage">4.50%</td>
    </tr>
    <tr data-testid="etf-holdings_top-holdings_row">
      <td>Microsoft</td>
      <td data-testid="tl_etf-holdings_top-holdings_value_percentage">4.10%</td>
    </tr>
  </table>
  <div data-testid="tl_etf-holdings_countries_value_name">United States</div>
  <div data-testid="tl_etf-holdings_countries_value_percentage">69.70%</div>
  <div data-testid="tl_etf-holdings_countries_value_name">Japan</div>
  <div data-testid="tl_etf-holdings_countries_value_percentage">5.66%</div>
  <div data-testid="tl_etf-holdings_countries_value_name">United Kingdom</div>
  <div data-testid="tl_etf-holdings_countries_value_percentage">3.70%</div>
  <div data-testid="tl_etf-holdings_countries_value_name">Canada</div>
  <div data-testid="tl_etf-holdings_countries_value_percentage">3.10%</div>
  <div data-testid="tl_etf-holdings_countries_value_name">Other</div>
  <div data-testid="tl_etf-holdings_countries_value_percentage">17.84%</div>
  <div data-testid="tl_etf-holdings_sectors_value_name">Technology</div>
  <div data-testid="tl_etf-holdings_sectors_value_percentage">25.10%</div>
  <div data-testid="tl_etf-holdings_sectors_value_name">Finance</div>
  <div data-testid="tl_etf-holdings_sectors_value_percentage">16.20%</div>
  <div data-testid="tl_etf-holdings_sectors_value_name">Industrials</div>
  <div data-testid="tl_etf-holdings_sectors_value_percentage">11.02%</div>
  <div data-testid="tl_etf-holdings_sectors_value_name">Healthcare</div>
  <div data-testid="tl_etf-holdings_sectors_value_percentage">10.50%</div>
  <div data-testid="tl_etf-holdings_sectors_value_name">Other</div>
  <div data-testid="tl_etf-holdings_sectors_value_percentage">37.18%</div>
</body></html>
"#;

#[test]
fn labels_normalise_sectors_countries_and_other() {
    assert_eq!(sector_key("Finance").as_deref(), Some("financial_services"));
    assert_eq!(sector_key("Technology").as_deref(), Some("technology"));
    assert_eq!(sector_key("Healthcare").as_deref(), Some("healthcare"));
    assert_eq!(
        sector_key("Consumer Discretionary").as_deref(),
        Some("consumer_cyclical")
    );
    assert_eq!(
        sector_key("Consumer Staples").as_deref(),
        Some("consumer_defensive")
    );
    assert_eq!(
        sector_key("Telecommunication").as_deref(),
        Some("communication_services")
    );
    assert_eq!(sector_key("Utilities").as_deref(), Some("utilities"));
    assert_eq!(
        sector_key("Something New").as_deref(),
        Some("something_new")
    );
    assert!(sector_key("   ").is_none());

    assert_eq!(country_key("United States").as_deref(), Some("US"));
    assert_eq!(country_key("United Kingdom").as_deref(), Some("GB"));
    assert_eq!(country_key("Japan").as_deref(), Some("JP"));
    assert_eq!(country_key("Luxembourg").as_deref(), Some("LU"));
    assert_eq!(country_key("Ireland").as_deref(), Some("IE"));
    assert_eq!(country_key("South Korea").as_deref(), Some("KR"));
    assert_eq!(country_key("Taiwan").as_deref(), Some("TW"));
    assert!(country_key("Atlantis").is_none());

    assert!(is_other("Other"));
    assert!(is_other(" other "));
    assert!(!is_other("Others"));
}

#[test]
fn parser_reads_facts_breakdown_and_refuses_wrong_fund_or_residual() {
    let comp = parse(CW8_HTML, "LU1681043599").expect("parsed");
    assert_eq!(comp.source, "justETF");
    assert_eq!(comp.as_of, NaiveDate::from_ymd_opt(2026, 6, 30));

    let facts = comp.facts.as_ref().expect("facts");
    assert_eq!(
        facts.ter_percent.map(|d| d.to_string()).as_deref(),
        Some("0.38")
    );
    assert_eq!(
        facts.distribution_policy,
        Some(DistributionPolicy::Accumulating)
    );
    assert_eq!(facts.replication, Some(Replication::Synthetic));
    assert_eq!(facts.domicile_country_key.as_deref(), Some("LU"));

    let country_labels: Vec<&str> = comp.countries.iter().map(|s| s.label.as_str()).collect();
    assert_eq!(country_labels, vec!["US", "JP", "GB", "CA"]);
    assert_eq!(comp.countries[0].percent.to_string(), "69.70");

    let sector_labels: Vec<&str> = comp.sectors.iter().map(|s| s.label.as_str()).collect();
    assert_eq!(
        sector_labels,
        vec![
            "technology",
            "financial_services",
            "industrials",
            "healthcare"
        ]
    );

    assert_eq!(comp.companies.len(), 2);
    assert_eq!(comp.companies[0].label, "Apple & Co");
    assert_eq!(comp.companies[1].label, "Microsoft");

    // Wrong ISIN or screener page is refused.
    assert!(parse(CW8_HTML, "IE00B4L5Y983").is_none());
    assert!(parse("<html><body>screener</body></html>", "LU1681043599").is_none());
    assert!(parse("", "LU1681043599").is_none());
    assert!(JustEtf::supports(Some("LU1681043599")));
    assert!(!JustEtf::supports(Some("CW8.PA")));
}

#[tokio::test]
async fn client_fetches_profile_by_isin_and_handles_outages() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/en/etf-profile.html"))
        .and(query_param("isin", "LU1681043599"))
        .respond_with(ResponseTemplate::new(200).set_body_string(CW8_HTML))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/en/etf-profile.html"))
        .and(query_param("isin", "IE00B4L5Y983"))
        .respond_with(ResponseTemplate::new(503))
        .mount(&server)
        .await;

    let app = TestApp::builder()
        .env("JUSTETF_BASE_URL", server.uri())
        .spawn()
        .await;

    let ok = app
        .state
        .market
        .justetf()
        .fetch("LU1681043599")
        .await
        .expect("fetched");
    assert_eq!(
        ok.facts.unwrap().domicile_country_key.as_deref(),
        Some("LU")
    );
    assert!(
        app.state
            .market
            .justetf()
            .fetch("IE00B4L5Y983")
            .await
            .is_none()
    );
    assert!(app.state.market.justetf().fetch("CW8.PA").await.is_none());
}
