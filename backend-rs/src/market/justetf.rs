//! JustEtfClient + JustEtfLabels + JustEtfProvider: reads an ETF's TER, distribution policy,
//! replication, domicile, top holdings, country and sector breakdown from justETF by ISIN.

use std::collections::HashMap;
use std::sync::LazyLock;
use std::time::Duration;

use chrono::NaiveDate;
use regex::Regex;
use rust_decimal::Decimal;
use unicode_normalization::UnicodeNormalization;

use super::openfigi::is_isin;
use super::yahoo::{QUERY_VALUE, safe, transport_message};
use crate::ledger::decimal::{HALF_UP, set_scale};

const SOURCE: &str = "justETF";
const TIMEOUT: Duration = Duration::from_secs(15);
const TOP_COMPANIES: usize = 10;
const ISIN_TESTID: &str = "etf-profile-header_isin-value";

static AS_OF: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"data-testid="tl_etf-holdings_reference-date"[^>]*>\s*As of\s*([0-9/]+)"#)
        .expect("regex")
});
static COUNTRY_ROW: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r#"(?s)data-testid="tl_etf-holdings_countries_value_name"[^>]*>([^<]*)<.*?data-testid="tl_etf-holdings_countries_value_percentage"[^>]*>([^<]*)<"#,
    )
    .expect("regex")
});
static SECTOR_ROW: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r#"(?s)data-testid="tl_etf-holdings_sectors_value_name"[^>]*>([^<]*)<.*?data-testid="tl_etf-holdings_sectors_value_percentage"[^>]*>([^<]*)<"#,
    )
    .expect("regex")
});
static COMPANY_ROW: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)data-testid="etf-holdings_top-holdings_row">(.*?)</tr>"#).expect("regex")
});
static COMPANY_PCT: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"data-testid="tl_etf-holdings_top-holdings_value_percentage"[^>]*>([^<]*)<"#)
        .expect("regex")
});
static COMPANY_NAME: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#">([^<>]{2,80})</a>|<td[^>]*>\s*([^<>]{2,80}?)\s*</td>"#).expect("regex")
});
static NUMBER: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(-?[0-9]+(?:[.,][0-9]+)?)").expect("regex"));
static HTML_NUM_ENTITY: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"&#(?:x([0-9a-fA-F]+)|([0-9]+));").expect("regex"));

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DistributionPolicy {
    Accumulating,
    Distributing,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Replication {
    Physical,
    Synthetic,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WeightedSlice {
    pub label: String,
    pub percent: Decimal,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FundFacts {
    pub ter_percent: Option<Decimal>,
    pub distribution_policy: Option<DistributionPolicy>,
    pub replication: Option<Replication>,
    pub domicile_country_key: Option<String>,
    pub source: String,
}

impl FundFacts {
    pub fn is_empty(&self) -> bool {
        self.ter_percent.is_none()
            && self.distribution_policy.is_none()
            && self.replication.is_none()
            && self.domicile_country_key.is_none()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EtfComposition {
    pub companies: Vec<WeightedSlice>,
    pub countries: Vec<WeightedSlice>,
    pub sectors: Vec<WeightedSlice>,
    pub source: String,
    pub as_of: Option<NaiveDate>,
    pub facts: Option<FundFacts>,
    pub index_name: Option<String>,
    pub investment_focus: Option<String>,
}

pub struct JustEtf {
    http: reqwest::Client,
    base_url: String,
}

impl JustEtf {
    pub fn new(http: reqwest::Client, base_url: &str) -> Self {
        Self {
            http,
            base_url: base_url.trim_end_matches('/').to_owned(),
        }
    }

    /// True only when `isin` has ISIN shape.
    pub fn supports(isin: Option<&str>) -> bool {
        isin.is_some_and(is_isin)
    }

    /// Fetches and parses the justETF profile page for `isin`; returns None on outage or mismatch.
    pub async fn fetch(&self, isin: &str) -> Option<EtfComposition> {
        if !is_isin(isin) {
            return None;
        }
        let url = format!(
            "{}/en/etf-profile.html?isin={}",
            self.base_url,
            percent_encoding::utf8_percent_encode(isin, QUERY_VALUE)
        );
        let response = match self
            .http
            .get(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept-Language", "en")
            .timeout(TIMEOUT)
            .send()
            .await
        {
            Ok(r) if r.status().is_success() => r,
            Ok(r) => {
                tracing::debug!(
                    "justETF fetch for {} returned HTTP {}",
                    safe(isin),
                    r.status().as_u16()
                );
                return None;
            }
            Err(e) => {
                tracing::warn!(
                    "justETF fetch failed for {}: {}",
                    safe(isin),
                    transport_message(&e)
                );
                return None;
            }
        };
        let html = response.text().await.ok()?;
        parse(&html, isin)
    }
}

/// Pure parser over the justETF profile HTML; verifies the page is actually about `isin`.
pub fn parse(html: &str, isin: &str) -> Option<EtfComposition> {
    if html.is_empty() || isin.is_empty() {
        return None;
    }
    let guard = format!("data-testid=\"{ISIN_TESTID}\">{isin}<");
    if !html.contains(&guard) {
        tracing::debug!("justETF: page is not about {} — refusing it", safe(isin));
        return None;
    }

    let countries = parse_axis(html, &COUNTRY_ROW, country_key);
    let sectors = parse_axis(html, &SECTOR_ROW, sector_key);
    let companies = parse_companies(html);
    let facts = parse_facts(html);
    let index_name = value(html, "tl_etf-basics_value_index-name");
    let investment_focus = value(html, "tl_etf-basics_value_investment-focus");

    if countries.is_empty() && sectors.is_empty() && companies.is_empty() && facts.is_empty() {
        return None;
    }
    Some(EtfComposition {
        companies,
        countries,
        sectors,
        source: SOURCE.to_owned(),
        as_of: parse_as_of(html),
        facts: (!facts.is_empty()).then_some(facts),
        index_name,
        investment_focus,
    })
}

fn parse_facts(html: &str) -> FundFacts {
    FundFacts {
        ter_percent: value(html, "tl_etf-basics_value_ter")
            .as_deref()
            .and_then(parse_percent),
        distribution_policy: value(html, "tl_etf-basics_value_distribution-policy").map(|v| {
            if v.to_ascii_lowercase().starts_with("accum") {
                DistributionPolicy::Accumulating
            } else {
                DistributionPolicy::Distributing
            }
        }),
        replication: value(html, "tl_etf-basics_value_replication").map(|v| {
            if v.to_ascii_lowercase().starts_with("synth") {
                Replication::Synthetic
            } else {
                Replication::Physical
            }
        }),
        domicile_country_key: value(html, "tl_etf-basics_value_domicile-country")
            .as_deref()
            .and_then(country_key),
        source: SOURCE.to_owned(),
    }
}

fn parse_axis(
    html: &str,
    row: &Regex,
    to_key: impl Fn(&str) -> Option<String>,
) -> Vec<WeightedSlice> {
    let mut slices: Vec<WeightedSlice> = Vec::new();
    for cap in row.captures_iter(html) {
        let label = html_unescape(&cap[1]);
        let label = label.trim();
        if is_other(label) {
            continue;
        }
        if let (Some(key), Some(pct)) = (to_key(label), parse_percent(&cap[2]))
            && pct > Decimal::ZERO
        {
            if let Some(existing) = slices.iter_mut().find(|s| s.label == key) {
                existing.percent += pct;
            } else {
                slices.push(WeightedSlice {
                    label: key,
                    percent: pct,
                });
            }
        }
    }
    slices
}

fn parse_companies(html: &str) -> Vec<WeightedSlice> {
    let mut out = Vec::new();
    for row in COMPANY_ROW.captures_iter(html) {
        if out.len() >= TOP_COMPANIES {
            break;
        }
        let cell = &row[1];
        let (Some(p), Some(n)) = (COMPANY_PCT.captures(cell), COMPANY_NAME.captures(cell)) else {
            continue;
        };
        let raw_name = n
            .get(1)
            .or_else(|| n.get(2))
            .map(|m| m.as_str())
            .unwrap_or("");
        let label = html_unescape(raw_name).trim().to_owned();
        if let Some(percent) = parse_percent(&p[1])
            && !label.is_empty()
        {
            out.push(WeightedSlice { label, percent });
        }
    }
    out
}

fn value(html: &str, test_id: &str) -> Option<String> {
    let needle = format!("data-testid=\"{test_id}\"");
    let start = html.find(&needle)? + needle.len();
    let rest = &html[start..];
    let gt = rest.find('>')? + 1;
    let content = &rest[gt..];
    let lt = content.find('<')?;
    let raw = html_unescape(&content[..lt]).trim().to_owned();
    (!raw.is_empty() && raw != "-").then_some(raw)
}

fn parse_percent(raw: &str) -> Option<Decimal> {
    let cap = NUMBER.captures(raw)?;
    let num: Decimal = cap[1].replace(',', ".").parse().ok()?;
    Some(set_scale(num, 2, HALF_UP))
}

fn parse_as_of(html: &str) -> Option<NaiveDate> {
    let cap = AS_OF.captures(html)?;
    NaiveDate::parse_from_str(&cap[1], "%d/%m/%Y").ok()
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

/// JustEtfLabels.isOther: true for "Other" (and French/German equivalents) regardless of casing or
/// surrounding whitespace.
pub fn is_other(label: &str) -> bool {
    let norm = normalize(label);
    matches!(norm.as_str(), "other" | "autre" | "autres" | "sonstige")
}

/// JustEtfLabels.sectorKey: Morningstar key or normalized snake_case; None for blank.
pub fn sector_key(label: &str) -> Option<String> {
    let normalized = normalize(label);
    if normalized.is_empty() {
        return None;
    }
    let mapped = match normalized.as_str() {
        "technology"
        | "information technology"
        | "technologie"
        | "technologie de l'information" => "technology",
        "finance"
        | "financial"
        | "financials"
        | "financial services"
        | "finanzen"
        | "finanzdienstleistungen"
        | "services financiers" => "financial_services",
        "healthcare" | "health care" | "health" | "gesundheitswesen" | "gesundheit" | "sante"
        | "soins de sante" => "healthcare",
        "industrials" | "industrial" | "industrie" | "produits industriels" => "industrials",
        "consumer discretionary"
        | "consumer cyclical"
        | "consumer cyclicals"
        | "consumer goods"
        | "consumer services"
        | "zyklische konsumguter"
        | "nicht-basiskonsumguter"
        | "zyklischer konsum"
        | "consommation cyclique"
        | "consommation discretionnaire" => "consumer_cyclical",
        "consumer staples"
        | "consumer defensive"
        | "consumer non-cyclicals"
        | "consumer non-cyclical"
        | "basiskonsumguter"
        | "nicht-zyklische konsumguter"
        | "consommation defensive"
        | "consommation de base" => "consumer_defensive",
        "telecommunication"
        | "telecommunications"
        | "telecom"
        | "telecoms"
        | "communication"
        | "communications"
        | "communication services"
        | "kommunikation"
        | "kommunikationsdienste"
        | "telekommunikation"
        | "services de communication" => "communication_services",
        "utilities" | "versorger" | "services publics" | "services aux collectivites" => {
            "utilities"
        }
        "energy" | "energie" => "energy",
        "basic materials" | "basic material" | "materials" | "material" | "grundstoffe"
        | "rohstoffe" | "materiaux" | "materiaux de base" => "basic_materials",
        "real estate" | "immobilien" | "immobilier" => "real_estate",
        _ => return Some(normalized.replace(' ', "_")),
    };
    Some(mapped.to_owned())
}

/// Maps a tracked equity index name (or investment focus) to a widely-published physical
/// reference ETF ISIN when a synthetic swap ETF (e.g. PEA unfunded swap) publishes no holdings.
/// Returns `None` for non-equity funds or narrow/unrecognized indices.
pub fn proxy_isin_for_index(
    index_name: Option<&str>,
    investment_focus: Option<&str>,
) -> Option<&'static str> {
    if let Some(focus) = investment_focus {
        let f = normalize(focus);
        if !f.is_empty() && !f.contains("equity") {
            return None;
        }
    }
    let idx = index_name.map(normalize).unwrap_or_default();
    let focus = investment_focus.map(normalize).unwrap_or_default();
    if idx.contains("msci em")
        || idx.contains("emerging market")
        || focus.contains("emerging market")
    {
        return Some("IE00BKM4GZ66"); // iShares Core MSCI EM IMI UCITS ETF
    }
    if idx.contains("msci acwi") {
        return Some("IE00B6R52259"); // iShares MSCI ACWI UCITS ETF
    }
    if idx.contains("ftse all-world") || idx.contains("ftse all world") {
        return Some("IE00BK5BQT80"); // Vanguard FTSE All-World UCITS ETF
    }
    if idx.contains("msci world") {
        return Some("IE00B4L5Y983"); // iShares Core MSCI World UCITS ETF
    }
    if idx.contains("s&p 500") || idx.contains("s&p500") {
        return Some("IE00B5BMR087"); // iShares Core S&P 500 UCITS ETF
    }
    if idx.contains("nasdaq-100") || idx.contains("nasdaq 100") {
        return Some("IE00B53SZB19"); // iShares Nasdaq 100 UCITS ETF
    }
    if idx.contains("euro stoxx 50") {
        return Some("IE00B53L3W79"); // iShares Core EURO STOXX 50 UCITS ETF
    }
    if idx.contains("stoxx europe 600") {
        return Some("LU0908500753"); // Amundi Core Stoxx Europe 600 UCITS ETF
    }
    if idx.contains("msci europe") {
        return Some("IE00B4K48X80"); // iShares Core MSCI Europe UCITS ETF
    }
    if idx.contains("msci japan") || idx.contains("topix") {
        return Some("IE00B4L5YX21"); // iShares Core MSCI Japan IMI UCITS ETF
    }
    None
}

/// JustEtfLabels.countryKey: ISO 3166-1 alpha-2 code for an English country name, or None.
pub fn country_key(english_label: &str) -> Option<String> {
    let normalized = normalize(english_label);
    COUNTRIES.get(normalized.as_str()).map(|s| (*s).to_owned())
}

fn normalize(s: &str) -> String {
    let stripped: String = s
        .nfd()
        .filter(|c| !unicode_normalization::char::is_combining_mark(*c) && !matches!(*c, '®' | '™'))
        .collect();
    stripped
        .to_lowercase()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

static COUNTRIES: LazyLock<HashMap<&'static str, &'static str>> = LazyLock::new(|| {
    HashMap::from([
        ("afghanistan", "AF"),
        ("aland islands", "AX"),
        ("albania", "AL"),
        ("algeria", "DZ"),
        ("american samoa", "AS"),
        ("andorra", "AD"),
        ("angola", "AO"),
        ("anguilla", "AI"),
        ("antarctica", "AQ"),
        ("antigua & barbuda", "AG"),
        ("antigua and barbuda", "AG"),
        ("argentina", "AR"),
        ("armenia", "AM"),
        ("aruba", "AW"),
        ("australia", "AU"),
        ("austria", "AT"),
        ("azerbaijan", "AZ"),
        ("bahamas", "BS"),
        ("bahrain", "BH"),
        ("bangladesh", "BD"),
        ("barbados", "BB"),
        ("belarus", "BY"),
        ("belgium", "BE"),
        ("belize", "BZ"),
        ("benin", "BJ"),
        ("bermuda", "BM"),
        ("bhutan", "BT"),
        ("bolivia", "BO"),
        ("bosnia & herzegovina", "BA"),
        ("bosnia and herzegovina", "BA"),
        ("botswana", "BW"),
        ("bouvet island", "BV"),
        ("brazil", "BR"),
        ("british indian ocean territory", "IO"),
        ("british virgin islands", "VG"),
        ("brunei", "BN"),
        ("bulgaria", "BG"),
        ("burkina faso", "BF"),
        ("burundi", "BI"),
        ("cambodia", "KH"),
        ("cameroon", "CM"),
        ("canada", "CA"),
        ("cape verde", "CV"),
        ("caribbean netherlands", "BQ"),
        ("cayman islands", "KY"),
        ("central african republic", "CF"),
        ("chad", "TD"),
        ("chile", "CL"),
        ("china", "CN"),
        ("china (mainland)", "CN"),
        ("christmas island", "CX"),
        ("cocos (keeling) islands", "CC"),
        ("colombia", "CO"),
        ("comoros", "KM"),
        ("congo - brazzaville", "CG"),
        ("congo - kinshasa", "CD"),
        ("cook islands", "CK"),
        ("costa rica", "CR"),
        ("cote d'ivoire", "CI"),
        ("ivory coast", "CI"),
        ("croatia", "HR"),
        ("cuba", "CU"),
        ("curacao", "CW"),
        ("cyprus", "CY"),
        ("czechia", "CZ"),
        ("czech republic", "CZ"),
        ("denmark", "DK"),
        ("djibouti", "DJ"),
        ("dominica", "DM"),
        ("dominican republic", "DO"),
        ("ecuador", "EC"),
        ("egypt", "EG"),
        ("el salvador", "SV"),
        ("equatorial guinea", "GQ"),
        ("eritrea", "ER"),
        ("estonia", "EE"),
        ("eswatini", "SZ"),
        ("ethiopia", "ET"),
        ("falkland islands", "FK"),
        ("faroe islands", "FO"),
        ("fiji", "FJ"),
        ("finland", "FI"),
        ("france", "FR"),
        ("french guiana", "GF"),
        ("french polynesia", "PF"),
        ("french southern territories", "TF"),
        ("gabon", "GA"),
        ("gambia", "GM"),
        ("georgia", "GE"),
        ("germany", "DE"),
        ("ghana", "GH"),
        ("gibraltar", "GI"),
        ("great britain", "GB"),
        ("greece", "GR"),
        ("greenland", "GL"),
        ("grenada", "GD"),
        ("guadeloupe", "GP"),
        ("guam", "GU"),
        ("guatemala", "GT"),
        ("guernsey", "GG"),
        ("guinea", "GN"),
        ("guinea-bissau", "GW"),
        ("guyana", "GY"),
        ("haiti", "HT"),
        ("heard & mcdonald islands", "HM"),
        ("honduras", "HN"),
        ("hong kong", "HK"),
        ("hong kong (sar)", "HK"),
        ("hong kong sar", "HK"),
        ("hong kong sar china", "HK"),
        ("hungary", "HU"),
        ("iceland", "IS"),
        ("india", "IN"),
        ("indonesia", "ID"),
        ("iran", "IR"),
        ("iraq", "IQ"),
        ("ireland", "IE"),
        ("isle of man", "IM"),
        ("israel", "IL"),
        ("italy", "IT"),
        ("jamaica", "JM"),
        ("japan", "JP"),
        ("jersey", "JE"),
        ("jordan", "JO"),
        ("kazakhstan", "KZ"),
        ("kenya", "KE"),
        ("kiribati", "KI"),
        ("kuwait", "KW"),
        ("kyrgyzstan", "KG"),
        ("laos", "LA"),
        ("latvia", "LV"),
        ("lebanon", "LB"),
        ("lesotho", "LS"),
        ("liberia", "LR"),
        ("libya", "LY"),
        ("liechtenstein", "LI"),
        ("lithuania", "LT"),
        ("luxembourg", "LU"),
        ("macao", "MO"),
        ("macau", "MO"),
        ("macao sar china", "MO"),
        ("madagascar", "MG"),
        ("malawi", "MW"),
        ("malaysia", "MY"),
        ("maldives", "MV"),
        ("mali", "ML"),
        ("malta", "MT"),
        ("marshall islands", "MH"),
        ("martinique", "MQ"),
        ("mauritania", "MR"),
        ("mauritius", "MU"),
        ("mayotte", "YT"),
        ("mexico", "MX"),
        ("micronesia", "FM"),
        ("moldova", "MD"),
        ("monaco", "MC"),
        ("mongolia", "MN"),
        ("montenegro", "ME"),
        ("montserrat", "MS"),
        ("morocco", "MA"),
        ("mozambique", "MZ"),
        ("myanmar (burma)", "MM"),
        ("namibia", "NA"),
        ("nauru", "NR"),
        ("nepal", "NP"),
        ("netherlands", "NL"),
        ("new caledonia", "NC"),
        ("new zealand", "NZ"),
        ("nicaragua", "NI"),
        ("niger", "NE"),
        ("nigeria", "NG"),
        ("niue", "NU"),
        ("norfolk island", "NF"),
        ("north korea", "KP"),
        ("north macedonia", "MK"),
        ("northern mariana islands", "MP"),
        ("norway", "NO"),
        ("oman", "OM"),
        ("pakistan", "PK"),
        ("palau", "PW"),
        ("palestinian territories", "PS"),
        ("panama", "PA"),
        ("papua new guinea", "PG"),
        ("paraguay", "PY"),
        ("peru", "PE"),
        ("philippines", "PH"),
        ("pitcairn islands", "PN"),
        ("poland", "PL"),
        ("portugal", "PT"),
        ("puerto rico", "PR"),
        ("qatar", "QA"),
        ("reunion", "RE"),
        ("romania", "RO"),
        ("russia", "RU"),
        ("russian federation", "RU"),
        ("rwanda", "RW"),
        ("samoa", "WS"),
        ("san marino", "SM"),
        ("sao tome & principe", "ST"),
        ("saudi arabia", "SA"),
        ("senegal", "SN"),
        ("serbia", "RS"),
        ("seychelles", "SC"),
        ("sierra leone", "SL"),
        ("singapore", "SG"),
        ("sint maarten", "SX"),
        ("slovakia", "SK"),
        ("slovenia", "SI"),
        ("solomon islands", "SB"),
        ("somalia", "SO"),
        ("south africa", "ZA"),
        ("south georgia & south sandwich islands", "GS"),
        ("south korea", "KR"),
        ("korea", "KR"),
        ("korea, republic of", "KR"),
        ("republic of korea", "KR"),
        ("south sudan", "SS"),
        ("spain", "ES"),
        ("sri lanka", "LK"),
        ("st. barthelemy", "BL"),
        ("st. helena", "SH"),
        ("st. kitts & nevis", "KN"),
        ("st. lucia", "LC"),
        ("st. martin", "MF"),
        ("st. pierre & miquelon", "PM"),
        ("st. vincent & grenadines", "VC"),
        ("sudan", "SD"),
        ("suriname", "SR"),
        ("svalbard & jan mayen", "SJ"),
        ("sweden", "SE"),
        ("switzerland", "CH"),
        ("syria", "SY"),
        ("taiwan", "TW"),
        ("taiwan, china", "TW"),
        ("taiwan, province of china", "TW"),
        ("tajikistan", "TJ"),
        ("tanzania", "TZ"),
        ("thailand", "TH"),
        ("timor-leste", "TL"),
        ("togo", "TG"),
        ("tokelau", "TK"),
        ("tonga", "TO"),
        ("trinidad & tobago", "TT"),
        ("trinidad and tobago", "TT"),
        ("tunisia", "TN"),
        ("turkiye", "TR"),
        ("turkey", "TR"),
        ("turkmenistan", "TM"),
        ("turks & caicos islands", "TC"),
        ("tuvalu", "TV"),
        ("u.s. outlying islands", "UM"),
        ("u.s. virgin islands", "VI"),
        ("uganda", "UG"),
        ("ukraine", "UA"),
        ("united arab emirates", "AE"),
        ("uae", "AE"),
        ("united kingdom", "GB"),
        ("uk", "GB"),
        ("u.k.", "GB"),
        ("united states", "US"),
        ("united states of america", "US"),
        ("u.s.", "US"),
        ("u.s.a.", "US"),
        ("usa", "US"),
        ("uruguay", "UY"),
        ("uzbekistan", "UZ"),
        ("vanuatu", "VU"),
        ("vatican city", "VA"),
        ("venezuela", "VE"),
        ("vietnam", "VN"),
        ("viet nam", "VN"),
        ("virgin islands (british)", "VG"),
        ("wallis & futuna", "WF"),
        ("western sahara", "EH"),
        ("yemen", "YE"),
        ("zambia", "ZM"),
        ("zimbabwe", "ZW"),
    ])
});

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_consumer_non_cyclicals_and_resolves_index_proxies() {
        assert_eq!(
            sector_key("Consumer Non-Cyclicals").as_deref(),
            Some("consumer_defensive")
        );
        assert_eq!(
            sector_key("Consumer Cyclicals").as_deref(),
            Some("consumer_cyclical")
        );
        assert_eq!(
            proxy_isin_for_index(
                Some("MSCI EM ex-Egypt ESG Broad CTB Select"),
                Some("Equity, Emerging Markets, Social/Environmental")
            ),
            Some("IE00BKM4GZ66")
        );
        assert_eq!(
            proxy_isin_for_index(Some("MSCI World"), Some("Equity, World")),
            Some("IE00B4L5Y983")
        );
        assert_eq!(
            proxy_isin_for_index(Some("MSCI EM"), Some("Bonds, Emerging Markets")),
            None
        );
        // Trademark symbols in real justETF index names must not defeat proxy matching.
        assert_eq!(
            proxy_isin_for_index(Some("STOXX® Europe 600"), Some("Equity, Europe")),
            Some("LU0908500753")
        );
        assert_eq!(
            proxy_isin_for_index(Some("EURO STOXX® 50"), Some("Equity, Europe")),
            Some("IE00B53L3W79")
        );
    }

    #[test]
    fn maps_gics_and_multilingual_labels_and_sums_duplicates() {
        assert_eq!(
            sector_key("Information Technology").as_deref(),
            Some("technology")
        );
        assert_eq!(
            sector_key("Finanzen").as_deref(),
            Some("financial_services")
        );
        assert_eq!(
            sector_key("Basiskonsumgüter").as_deref(),
            Some("consumer_defensive")
        );
        assert_eq!(country_key("Republic of Korea").as_deref(), Some("KR"));
        assert_eq!(country_key("UK").as_deref(), Some("GB"));
        assert!(is_other("Sonstige"));
        assert!(is_other("Autres"));

        let html = r#"
            <span data-testid="etf-profile-header_isin-value">IE0000000001</span>
            <div data-testid="tl_etf-holdings_sectors_value_name">Technology</div>
            <div data-testid="tl_etf-holdings_sectors_value_percentage">20.00%</div>
            <div data-testid="tl_etf-holdings_sectors_value_name">Information Technology</div>
            <div data-testid="tl_etf-holdings_sectors_value_percentage">5.50%</div>
            <div data-testid="tl_etf-holdings_sectors_value_name">Other</div>
            <div data-testid="tl_etf-holdings_sectors_value_percentage">74.50%</div>
        "#;
        let comp = parse(html, "IE0000000001").expect("parsed");
        assert_eq!(comp.sectors.len(), 1);
        assert_eq!(comp.sectors[0].label, "technology");
        assert_eq!(comp.sectors[0].percent.to_string(), "25.50");
    }
}
