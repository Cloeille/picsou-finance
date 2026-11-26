//! Boursorama public ETF composition provider (port of `BoursoramaClient`,
//! `BoursoramaCompositionProvider`, and `BoursoramaLabels`).
//!
//! Two-step flow (no auth):
//! 1. `GET /recherche/?query=<isin-or-bare-ticker>` without following redirects; read the
//!    Boursorama symbol from the `Location` header (`/cours/{SYMBOL}/`).
//! 2. `GET /bourse/trackers/cours/composition/{SYMBOL}/` (fallback `/bourse/opcvm/...`) and
//!    parse the inline `amChartData` blocks (`regional`, `sector`), the `c-table-gauge` top
//!    holdings table (skipping synthetic swap lines), and `Date du portefeuille : DD/MM/YYYY`.

use std::collections::HashMap;
use std::str::FromStr;
use std::sync::LazyLock;
use std::time::Duration;

use chrono::NaiveDate;
use regex::Regex;
use reqwest::header;
use rust_decimal::Decimal;
use serde_json::Value;
use unicode_normalization::UnicodeNormalization;

use super::justetf::{EtfComposition, WeightedSlice};
use super::yahoo::{QUERY_VALUE, safe, transport_message};
use crate::ledger::decimal::{HALF_UP, set_scale};

const HOST: &str = "https://www.boursorama.com";
const SOURCE: &str = "Boursorama";
const TIMEOUT: Duration = Duration::from_secs(15);
const TOP_COMPANIES: usize = 10;
const COMPOSITION_PATHS: &[&str] = &[
    "/bourse/trackers/cours/composition/",
    "/bourse/opcvm/cours/composition/",
];

static SYMBOL_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"/cours/([^/?]+)/").expect("regex"));
static AMCHART_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)"id":"(regional|sector)".*?"amChartData":(\[[^\]]*\])"#).expect("regex")
});
static HOLDING_ROW_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r#"(?s)c-table-gauge__cell--header">\s*(.*?)\s*</td>.*?data-gauge-current-step="([0-9.,]+)""#,
    )
    .expect("regex")
});
static AS_OF_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"Date du portefeuille\s*:\s*(\d{2})/(\d{2})/(\d{4})").expect("regex")
});
static EQUITY_ISIN_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#""fv_code_isin"\s*:\s*"([A-Z]{2}[A-Z0-9]{9}\d)"#).expect("regex")
});
static HTML_NUM_ENTITY: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"&#(?:x([0-9a-fA-F]+)|([0-9]+));").expect("regex"));

pub struct Boursorama {
    http: reqwest::Client,
    no_redirect: reqwest::Client,
    base_url: String,
}

impl Boursorama {
    pub fn new(http: reqwest::Client) -> Self {
        Self::with_base_url(http, HOST)
    }

    pub fn with_base_url(http: reqwest::Client, base_url: &str) -> Self {
        let no_redirect = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(TIMEOUT)
            .build()
            .unwrap_or_else(|_| http.clone());
        Self {
            http,
            no_redirect,
            base_url: base_url.trim_end_matches('/').to_owned(),
        }
    }

    /// Resolves an ISIN or ticker (exchange suffix stripped) to Boursorama's internal symbol
    /// (`LU1681043599` / `CW8.PA` → `1rTCW8`).
    pub async fn resolve_symbol(&self, identifier: &str) -> Option<String> {
        let query = bare_ticker(identifier.trim());
        if query.is_empty() {
            return None;
        }
        let url = format!(
            "{}/recherche/?query={}",
            self.base_url,
            percent_encoding::utf8_percent_encode(query, QUERY_VALUE)
        );
        let resp = match self
            .no_redirect
            .get(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept-Language", "fr-FR")
            .timeout(TIMEOUT)
            .send()
            .await
        {
            Ok(r) => r,
            Err(e) => {
                tracing::debug!(
                    "Boursorama resolve failed for {}: {}",
                    safe(identifier),
                    transport_message(&e)
                );
                return None;
            }
        };
        let location = resp
            .headers()
            .get(header::LOCATION)
            .and_then(|v| v.to_str().ok())?;
        symbol_from_location(location)
    }

    /// Fetches and parses ETF composition by ISIN or ticker; returns `None` if unavailable or empty.
    pub async fn fetch_composition(&self, identifier: &str) -> Option<EtfComposition> {
        let symbol = self.resolve_symbol(identifier).await?;
        let html = self.fetch_composition_html(&symbol).await?;
        let parsed = parse(&html);
        if parsed.countries.is_empty() && parsed.sectors.is_empty() && parsed.companies.is_empty() {
            return None;
        }
        Some(parsed)
    }

    /// Fetches the ISIN of an equity from its Boursorama quote page (`/cours/{symbol}/`),
    /// verifying that the page actually matches the requested symbol before trusting `fv_code_isin`.
    pub async fn fetch_equity_isin(&self, identifier: &str) -> Option<String> {
        let symbol = self.resolve_symbol(identifier).await?;
        let encoded = percent_encoding::utf8_percent_encode(&symbol, QUERY_VALUE);
        let url = format!("{}/cours/{encoded}/", self.base_url);
        let resp = match self
            .http
            .get(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept-Language", "fr-FR")
            .timeout(TIMEOUT)
            .send()
            .await
        {
            Ok(r) if r.status().is_success() => r,
            _ => return None,
        };
        let html = resp.text().await.ok()?;
        isin_from_quote_html(&html, &symbol)
    }

    async fn fetch_composition_html(&self, symbol: &str) -> Option<String> {
        let encoded = percent_encoding::utf8_percent_encode(symbol, QUERY_VALUE);
        for prefix in COMPOSITION_PATHS {
            let url = format!("{}{prefix}{encoded}/", self.base_url);
            let resp = match self
                .http
                .get(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept-Language", "fr-FR")
                .timeout(TIMEOUT)
                .send()
                .await
            {
                Ok(r) if r.status().is_success() => r,
                _ => continue,
            };
            if let Ok(body) = resp.text().await
                && body.contains("amChartData")
            {
                return Some(body);
            }
        }
        None
    }
}

pub fn symbol_from_location(location: &str) -> Option<String> {
    SYMBOL_RE
        .captures(location)
        .and_then(|c| c.get(1))
        .map(|m| m.as_str().to_owned())
}

/// Strips the exchange suffix from a ticker (`PUST.PA` → `PUST`). ISINs have no dot and stay intact.
pub fn bare_ticker(identifier: &str) -> &str {
    match identifier.find('.') {
        Some(dot) if dot > 0 => &identifier[..dot],
        _ => identifier,
    }
}

/// Pure parser over a Boursorama tracker/OPCVM composition HTML page.
pub fn parse(html: &str) -> EtfComposition {
    let mut countries = Vec::new();
    let mut sectors = Vec::new();
    for cap in AMCHART_RE.captures_iter(html) {
        match &cap[1] {
            "regional" => countries = parse_slices(&cap[2], country_key),
            _ => sectors = parse_slices(&cap[2], sector_key),
        }
    }
    let companies = parse_holdings(html);
    EtfComposition {
        companies,
        countries,
        sectors,
        source: SOURCE.to_owned(),
        as_of: parse_as_of(html),
        facts: None,
        index_name: None,
        investment_focus: None,
    }
}

/// Extracts `fv_code_isin` from a Boursorama quote page, refusing pages whose `fv_symb_societe`
/// does not match `symbol` (a wrong symbol returns HTTP 200 with an unrelated page).
pub fn isin_from_quote_html(html: &str, symbol: &str) -> Option<String> {
    if html.is_empty() || symbol.is_empty() {
        return None;
    }
    let guard = format!("\"fv_symb_societe\":\"{symbol}\"");
    if !html.contains(&guard) {
        return None;
    }
    EQUITY_ISIN_RE
        .captures(html)
        .and_then(|c| c.get(1))
        .map(|m| m.as_str().to_ascii_uppercase())
}

/// Extracts an ISO 3166-1 alpha-2 country code from the prefix of a 12-character ISIN.
/// Rejects supranational/synthetic prefixes (`XS`, `EU`, `EZ`, `XF`, `XU`, `ZZ`) that do not
/// name a country.
pub fn country_from_isin(isin: &str) -> Option<String> {
    let trimmed = isin.trim();
    if trimmed.len() != 12 || !trimmed.is_ascii() {
        return None;
    }
    let prefix = trimmed[..2].to_ascii_uppercase();
    if !prefix.bytes().all(|b| b.is_ascii_uppercase()) {
        return None;
    }
    if matches!(prefix.as_str(), "XS" | "EU" | "EZ" | "XF" | "XU" | "ZZ") {
        return None;
    }
    Some(prefix)
}

fn parse_slices(json_array: &str, key_fn: fn(&str) -> Option<String>) -> Vec<WeightedSlice> {
    let Ok(Value::Array(items)) = serde_json::from_str::<Value>(json_array) else {
        return Vec::new();
    };
    let mut out: Vec<WeightedSlice> = Vec::new();
    for node in items {
        let Some(name) = node.get("name").and_then(Value::as_str) else {
            continue;
        };
        let trimmed = name.trim();
        if trimmed.is_empty() || is_other(trimmed) {
            continue;
        }
        let Some(val) = node.get("value").and_then(json_number_to_decimal) else {
            continue;
        };
        let pct = set_scale(val, 2, HALF_UP);
        if pct <= Decimal::ZERO {
            continue;
        }
        if let Some(label) = key_fn(trimmed) {
            // Boursorama can emit two entries that share the same French label or map to the same
            // canonical sector (e.g. FactSet sub-sectors or repeated "Biens de consommation
            // cycliques" / "Finance"); sum them rather than dropping the second slice later.
            if let Some(existing) = out.iter_mut().find(|s| s.label == label) {
                existing.percent += pct;
            } else {
                out.push(WeightedSlice {
                    label,
                    percent: pct,
                });
            }
        }
    }
    out
}

fn json_number_to_decimal(v: &Value) -> Option<Decimal> {
    let Value::Number(n) = v else {
        return None;
    };
    Decimal::from_str(&n.to_string()).ok()
}

fn parse_holdings(html: &str) -> Vec<WeightedSlice> {
    let mut out = Vec::new();
    for cap in HOLDING_ROW_RE.captures_iter(html) {
        if out.len() >= TOP_COMPANIES {
            break;
        }
        let name = html_unescape(&cap[1]).trim().to_owned();
        if name.is_empty() || is_swap_line(&name) {
            continue;
        }
        if let Ok(weight) = Decimal::from_str(&cap[2].replace(',', ".")) {
            out.push(WeightedSlice {
                label: name,
                percent: set_scale(weight, 2, HALF_UP),
            });
        }
    }
    out
}

/// Synthetic ETFs list only their total-return swap as a holding — not a real constituent.
fn is_swap_line(name: &str) -> bool {
    let upper = name.to_ascii_uppercase();
    upper.starts_with("TRS ") || upper.contains("SWAP")
}

fn is_other(label: &str) -> bool {
    let norm = normalize(label);
    matches!(norm.as_str(), "autre" | "autres" | "other" | "others")
}

fn parse_as_of(html: &str) -> Option<NaiveDate> {
    let cap = AS_OF_RE.captures(html)?;
    let day: u32 = cap[1].parse().ok()?;
    let month: u32 = cap[2].parse().ok()?;
    let year: i32 = cap[3].parse().ok()?;
    NaiveDate::from_ymd_opt(year, month, day)
}

fn html_unescape(raw: &str) -> String {
    let decoded = HTML_NUM_ENTITY.replace_all(raw, |caps: &regex::Captures<'_>| {
        let code = if let Some(hex) = caps.get(1) {
            u32::from_str_radix(hex.as_str(), 16).ok()
        } else if let Some(dec) = caps.get(2) {
            dec.as_str().parse::<u32>().ok()
        } else {
            None
        };
        code.and_then(char::from_u32)
            .map(|c| c.to_string())
            .unwrap_or_else(|| caps[0].to_owned())
    });
    decoded
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&nbsp;", "\u{00A0}")
}

/// Maps Boursorama French sector labels (both Morningstar and FactSet/current Boursorama wording)
/// to canonical i18n sector keys.
pub fn sector_key(french_label: &str) -> Option<String> {
    let trimmed = french_label.trim();
    if trimmed.is_empty() {
        return None;
    }
    let norm = normalize(trimmed);
    let mapped = match norm.as_str() {
        "technologie"
        | "technologies"
        | "services technologiques"
        | "technologie electronique"
        | "technologie de l'information"
        | "technologies de l'information" => "technology",
        "services de communication"
        | "telecommunication"
        | "telecommunications"
        | "communication"
        | "communications" => "communication_services",
        "biens de consommation cyclique"
        | "biens de consommation cycliques"
        | "consommation cyclique"
        | "consommation discretionnaire"
        | "services aux consommateurs"
        | "biens de consommation durables"
        | "commerce de detail" => "consumer_cyclical",
        "biens de consommation defensif"
        | "biens de consommation defensifs"
        | "biens de consommation non cycliques"
        | "biens de consommation non cyclique"
        | "biens de consommation non durables"
        | "biens de consommation de base"
        | "consommation defensive"
        | "consommation non cyclique"
        | "consommation de base" => "consumer_defensive",
        "sante"
        | "soins de sante"
        | "technologies de la sante"
        | "services de sante"
        | "pharmacie" => "healthcare",
        "industriels"
        | "industrie"
        | "industrie manufacturiere"
        | "produits industriels"
        | "services industriels"
        | "services de distribution"
        | "industries de transformation"
        | "services commerciaux"
        | "transports" => "industrials",
        "services publics" | "services aux collectivites" | "collectivites" => "utilities",
        "matieres premieres de base"
        | "matieres premieres"
        | "materiaux de base"
        | "materiaux"
        | "materiaux de construction"
        | "produits chimiques"
        | "matieres non energetiques"
        | "mineraux non energetiques" => "basic_materials",
        "energie" | "mineraux energetiques" | "petrole et gaz" => "energy",
        "services financiers" | "finance" | "finances" | "banques" | "assurances" => {
            "financial_services"
        }
        "immobilier" | "valeurs immobilieres" => "real_estate",
        _ => return Some(trimmed.to_owned()),
    };
    Some(mapped.to_owned())
}

/// Maps Boursorama French country names to ISO 3166-1 alpha-2 codes.
pub fn country_key(french_label: &str) -> Option<String> {
    let trimmed = french_label.trim();
    if trimmed.is_empty() {
        return None;
    }
    let norm = normalize(trimmed);
    COUNTRIES
        .get(norm.as_str())
        .map(|code| (*code).to_owned())
        .or_else(|| Some(trimmed.to_owned()))
}

fn normalize(s: &str) -> String {
    let stripped: String = s
        .nfd()
        .filter(|c| !unicode_normalization::char::is_combining_mark(*c))
        .collect();
    stripped
        .to_lowercase()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

static COUNTRIES: LazyLock<HashMap<&'static str, &'static str>> = LazyLock::new(|| {
    HashMap::from([
        ("etats-unis", "US"),
        ("etats unis", "US"),
        ("etats-unis d'amerique", "US"),
        ("etats unis d'amerique", "US"),
        ("pays-bas", "NL"),
        ("canada", "CA"),
        ("france", "FR"),
        ("royaume-uni", "GB"),
        ("grande-bretagne", "GB"),
        ("allemagne", "DE"),
        ("suisse", "CH"),
        ("irlande", "IE"),
        ("japon", "JP"),
        ("chine", "CN"),
        ("luxembourg", "LU"),
        ("australie", "AU"),
        ("espagne", "ES"),
        ("italie", "IT"),
        ("suede", "SE"),
        ("danemark", "DK"),
        ("hong kong", "HK"),
        ("hong-kong", "HK"),
        ("coree du sud", "KR"),
        ("coree", "KR"),
        ("republique de coree", "KR"),
        ("taiwan", "TW"),
        ("inde", "IN"),
        ("bresil", "BR"),
        ("belgique", "BE"),
        ("finlande", "FI"),
        ("norvege", "NO"),
        ("singapour", "SG"),
        ("autriche", "AT"),
        ("portugal", "PT"),
        ("nouvelle-zelande", "NZ"),
        ("mexique", "MX"),
        ("afrique du sud", "ZA"),
        ("arabie saoudite", "SA"),
        ("emirats arabes unis", "AE"),
        ("indonesie", "ID"),
        ("thailande", "TH"),
        ("malaisie", "MY"),
        ("pologne", "PL"),
        ("turquie", "TR"),
        ("grece", "GR"),
        ("israel", "IL"),
        ("chili", "CL"),
        ("colombie", "CO"),
        ("perou", "PE"),
        ("philippines", "PH"),
        ("qatar", "QA"),
        ("koweit", "KW"),
        ("hongrie", "HU"),
        ("republique tcheque", "CZ"),
        ("tchequie", "CZ"),
        ("iles caimans", "KY"),
        ("iles caimanes", "KY"),
        ("bermudes", "BM"),
        ("jersey", "JE"),
        ("guernesey", "GG"),
        ("egypte", "EG"),
        ("roumanie", "RO"),
        ("bulgarie", "BG"),
        ("croatie", "HR"),
        ("slovenie", "SI"),
        ("slovaquie", "SK"),
        ("estonie", "EE"),
        ("lettonie", "LV"),
        ("lituanie", "LT"),
        ("chypre", "CY"),
        ("malte", "MT"),
        ("islande", "IS"),
        ("argentine", "AR"),
        ("maroc", "MA"),
        ("nigeria", "NG"),
        ("kenya", "KE"),
        ("vietnam", "VN"),
        ("viet nam", "VN"),
        ("pakistan", "PK"),
        ("kazakhstan", "KZ"),
        ("ukraine", "UA"),
        ("russie", "RU"),
        ("federation de russie", "RU"),
        ("panama", "PA"),
        ("uruguay", "UY"),
        ("bahrein", "BH"),
        ("oman", "OM"),
        ("jordanie", "JO"),
        ("tunisie", "TN"),
        ("macao", "MO"),
        ("ile de man", "IM"),
        ("iles vierges britanniques", "VG"),
        ("iles feroe", "FO"),
        ("liechtenstein", "LI"),
        ("monaco", "MC"),
    ])
});

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn symbol_and_bare_ticker_extraction() {
        assert_eq!(
            symbol_from_location("/bourse/trackers/cours/1rTCW8/"),
            Some("1rTCW8".into())
        );
        assert_eq!(
            symbol_from_location("https://www.boursorama.com/bourse/trackers/cours/1zIS3N/?foo=1"),
            Some("1zIS3N".into())
        );
        assert_eq!(symbol_from_location("/recherche/"), None);
        assert_eq!(bare_ticker("PUST.PA"), "PUST");
        assert_eq!(bare_ticker("LU1681043599"), "LU1681043599");
    }

    #[test]
    fn parses_amchart_regional_sector_holdings_and_as_of() {
        let html = r#"
            <div>Date du portefeuille : 30/09/2026</div>
            <script>
            {"id":"regional","amChartData":[{"name":"États-Unis","value":72.59},{"name":"Autriche","value":6.43},{"name":"Autres","value":1.2}]}
            {"id":"sector","amChartData":[{"name":"Technologie","value":30.61},{"name":"Finance","value":15.97},{"name":"Produits industriels","value":10.38},{"name":"Biens de consommation cycliques","value":8.59},{"name":"Télécommunications","value":7.26},{"name":"Matières non énergétiques","value":5.95},{"name":"Biens de consommation non cycliques","value":4.80}]}
            </script>
            <table>
              <tr><td class="c-table-gauge__cell--header">TRS MSCI WORLD SWAP</td><td data-gauge-current-step="100.0"></td></tr>
              <tr><td class="c-table-gauge__cell--header">NVIDIA Corp</td><td data-gauge-current-step="5,42"></td></tr>
            </table>
        "#;
        let comp = parse(html);
        assert_eq!(comp.source, "Boursorama");
        assert_eq!(comp.as_of, NaiveDate::from_ymd_opt(2026, 9, 30));
        assert_eq!(
            comp.countries,
            vec![
                WeightedSlice {
                    label: "US".into(),
                    percent: Decimal::from_str("72.59").unwrap(),
                },
                WeightedSlice {
                    label: "AT".into(),
                    percent: Decimal::from_str("6.43").unwrap(),
                },
            ]
        );
        let sector_labels: Vec<&str> = comp.sectors.iter().map(|s| s.label.as_str()).collect();
        assert_eq!(
            sector_labels,
            vec![
                "technology",
                "financial_services",
                "industrials",
                "consumer_cyclical",
                "communication_services",
                "basic_materials",
                "consumer_defensive",
            ]
        );
        assert_eq!(comp.companies.len(), 1);
        assert_eq!(comp.companies[0].label, "NVIDIA Corp");
        assert_eq!(
            comp.companies[0].percent,
            Decimal::from_str("5.42").unwrap()
        );
    }

    #[test]
    fn sums_duplicate_canonical_sectors_and_maps_factset_labels() {
        let html = r#"
            <script>
            {"id":"regional","amChartData":[{"name":"États-Unis","value":95.00},{"name":"Égypte","value":2.50},{"name":"Roumanie","value":1.50}]}
            {"id":"sector","amChartData":[{"name":"Technologie","value":38.24},{"name":"Finance","value":12.03},{"name":"Biens de consommation cycliques","value":9.19},{"name":"Biens de consommation cycliques","value":4.88},{"name":"Finance","value":1.8},{"name":"Technologie électronique","value":1.20},{"name":"Biens de consommation non durables","value":2.00}]}
            </script>
        "#;
        let comp = parse(html);
        assert_eq!(
            comp.countries,
            vec![
                WeightedSlice {
                    label: "US".into(),
                    percent: Decimal::from_str("95.00").unwrap(),
                },
                WeightedSlice {
                    label: "EG".into(),
                    percent: Decimal::from_str("2.50").unwrap(),
                },
                WeightedSlice {
                    label: "RO".into(),
                    percent: Decimal::from_str("1.50").unwrap(),
                },
            ]
        );
        assert_eq!(
            comp.sectors,
            vec![
                WeightedSlice {
                    label: "technology".into(),
                    percent: Decimal::from_str("39.44").unwrap(),
                },
                WeightedSlice {
                    label: "financial_services".into(),
                    percent: Decimal::from_str("13.83").unwrap(),
                },
                WeightedSlice {
                    label: "consumer_cyclical".into(),
                    percent: Decimal::from_str("14.07").unwrap(),
                },
                WeightedSlice {
                    label: "consumer_defensive".into(),
                    percent: Decimal::from_str("2.00").unwrap(),
                },
            ]
        );
    }

    #[test]
    fn extracts_equity_isin_and_country_only_when_symbol_guard_matches() {
        let html =
            r#"<script>{"fv_symb_societe":"1rPMC","fv_code_isin":"FR0000121014_MC"}</script>"#;
        assert_eq!(
            isin_from_quote_html(html, "1rPMC").as_deref(),
            Some("FR0000121014")
        );
        // Wrong symbol on a 200 page must be refused.
        assert_eq!(isin_from_quote_html(html, "AAPL"), None);
        assert_eq!(country_from_isin("FR0000121014").as_deref(), Some("FR"));
        assert_eq!(country_from_isin("US0378331005").as_deref(), Some("US"));
        // Supranational / synthetic prefixes are not country codes.
        assert_eq!(country_from_isin("XS1234567890"), None);
        assert_eq!(country_from_isin("XF000BTC0017"), None);
    }
}
