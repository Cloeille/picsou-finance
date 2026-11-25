//! IGN Géoplateforme geocoding (GeocodingPort, GeoplateformeGeocoder): address search over the
//! BAN index. An outage is no results, never an error.

use std::time::Duration;

use rust_decimal::Decimal;
use serde_json::Value;

use super::num::json_decimal;
use super::yahoo::{QUERY_VALUE, safe, transport_message};

const TIMEOUT: Duration = Duration::from_secs(10);
const MAX_LIMIT: i64 = 10;

/// GeocodingPort.GeocodeResult.
#[derive(Debug, Clone, PartialEq)]
pub struct GeocodeResult {
    pub label: Option<String>,
    pub score: Option<Decimal>,
    pub latitude: Option<Decimal>,
    pub longitude: Option<Decimal>,
    pub insee_code: Option<String>,
    pub postcode: Option<String>,
    pub city: Option<String>,
    pub ban_id: Option<String>,
}

/// Two-character metropolitan department code, or three characters overseas (97x).
pub fn department_code_from_insee(code: &str) -> Option<String> {
    let mut chars = code.chars();
    let c1 = chars.next()?;
    let c2 = chars.next()?;
    if c1 == '9'
        && c2 == '7'
        && let Some(c3) = chars.next()
    {
        return Some([c1, c2, c3].into_iter().collect());
    }
    Some([c1, c2].into_iter().collect())
}

impl GeocodeResult {
    /// Two-digit metropolitan department code, or three digits overseas (97x).
    pub fn department_code(&self) -> Option<String> {
        department_code_from_insee(self.insee_code.as_deref()?)
    }
}

/// geocode(query): best single address match.
pub async fn geocode(http: &reqwest::Client, base_url: &str, query: &str) -> Option<GeocodeResult> {
    search(http, base_url, query, 1).await.into_iter().next()
}

/// autocomplete(query, limit): up to limit (clamped to 1..10) addresses.
pub async fn autocomplete(
    http: &reqwest::Client,
    base_url: &str,
    query: &str,
    limit: i64,
) -> Vec<GeocodeResult> {
    search(http, base_url, query, limit.clamp(1, MAX_LIMIT)).await
}

async fn search(
    http: &reqwest::Client,
    base_url: &str,
    query: &str,
    limit: i64,
) -> Vec<GeocodeResult> {
    if query.trim().is_empty() {
        return Vec::new();
    }
    let url = format!(
        "{}/search?q={}&limit={limit}&index=address&returntruegeometry=false",
        base_url.trim_end_matches('/'),
        percent_encoding::utf8_percent_encode(query, QUERY_VALUE)
    );
    let response = match http
        .get(url)
        .header("Accept", "application/json")
        .timeout(TIMEOUT)
        .send()
        .await
    {
        Ok(r) => r,
        Err(e) => {
            tracing::warn!(
                "Geocoding unreachable for '{}' ({})",
                safe(query),
                transport_message(&e)
            );
            return Vec::new();
        }
    };
    if !response.status().is_success() {
        tracing::warn!(
            "Geocoding failed for '{}': HTTP {}",
            safe(query),
            response.status().as_u16()
        );
        return Vec::new();
    }
    let Ok(body) = response.json::<Value>().await else {
        tracing::warn!("Geocoding failed for '{}': unreadable body", safe(query));
        return Vec::new();
    };
    body.get("features")
        .and_then(Value::as_array)
        .map(|features| features.iter().filter_map(to_result).collect())
        .unwrap_or_default()
}

fn text(properties: &Value, key: &str) -> Option<String> {
    properties
        .get(key)
        .and_then(Value::as_str)
        .map(str::to_owned)
}

fn to_result(feature: &Value) -> Option<GeocodeResult> {
    let properties = feature.get("properties").filter(|p| p.is_object())?;
    let coordinates = feature
        .get("geometry")
        .and_then(|g| g.get("coordinates"))
        .and_then(Value::as_array)
        .filter(|c| c.len() >= 2);
    let (longitude, latitude) = match coordinates {
        Some(c) => (json_decimal(&c[0]), json_decimal(&c[1])),
        None => (None, None),
    };
    let insee_code = text(properties, "citycode");
    if insee_code.is_none() && latitude.is_none() {
        return None;
    }
    Some(GeocodeResult {
        label: text(properties, "label"),
        score: properties.get("score").and_then(json_decimal),
        latitude,
        longitude,
        insee_code,
        postcode: text(properties, "postcode"),
        city: text(properties, "city"),
        ban_id: text(properties, "banId"),
    })
}
