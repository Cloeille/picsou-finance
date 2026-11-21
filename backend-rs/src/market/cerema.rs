//! Cerema DV3F open data: comparable property sales (CeremaDv3fValuationProvider).

use std::time::Duration;

use rust_decimal::Decimal;
use serde_json::Value;

use super::num::{json_decimal, json_i64};
use super::yahoo::{QUERY_VALUE, transport_message};
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::model::ValuationConfidence;

pub const PROVIDER_NAME: &str = "CEREMA_DV3F";

const TIMEOUT: Duration = Duration::from_secs(15);
const UNCOVERED_DEPARTMENTS: [&str; 4] = ["57", "67", "68", "976"];
const LOW_CONFIDENCE_SAMPLE: i32 = 15;
const MEDIUM_CONFIDENCE_SAMPLE: i32 = 50;
const MAX_ROOM_SERIES: i16 = 5;
const MIN_ROOM_SERIES_SAMPLE: i32 = 20;

/// Physical nature of a property (PropertyKind).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PropertyKind {
    House,
    Apartment,
    Building,
    Land,
    Parking,
    Commercial,
}

impl PropertyKind {
    pub fn is_estimable(self) -> bool {
        matches!(self, Self::House | Self::Apartment)
    }

    /// Lenient parse of real_estate_metadata.property_type (including historical French labels).
    pub fn parse(raw: &str) -> Option<Self> {
        match raw.trim().to_uppercase().as_str() {
            "HOUSE" | "MAISON" | "VILLA" => Some(Self::House),
            "APARTMENT" | "APPARTEMENT" | "APPART" | "FLAT" | "STUDIO" => Some(Self::Apartment),
            "BUILDING" | "IMMEUBLE" => Some(Self::Building),
            "LAND" | "TERRAIN" => Some(Self::Land),
            "PARKING" | "GARAGE" | "BOX" => Some(Self::Parking),
            "COMMERCIAL" | "COMMERCE" | "LOCAL_COMMERCIAL" | "BUREAU" | "OFFICE" => {
                Some(Self::Commercial)
            }
            _ => None,
        }
    }
}

/// CeremaDv3fValuationProvider.ValuationInput.
#[derive(Debug, Clone, PartialEq)]
pub struct ValuationInput {
    pub insee_code: Option<String>,
    pub department_code: Option<String>,
    pub country_code: Option<String>,
    pub kind: Option<PropertyKind>,
    pub living_area: Option<Decimal>,
    pub land_area: Option<Decimal>,
    pub rooms: Option<i16>,
    pub construction_year: Option<i16>,
    pub latitude: Option<Decimal>,
    pub longitude: Option<Decimal>,
}

/// CeremaDv3fValuationProvider.ValuationResult.
#[derive(Debug, Clone, PartialEq)]
pub struct ValuationResult {
    pub estimated_value: Decimal,
    pub low_value: Option<Decimal>,
    pub high_value: Option<Decimal>,
    pub price_per_sqm: Decimal,
    pub sample_size: Option<i32>,
    pub confidence: ValuationConfidence,
    pub source_year: Option<i16>,
    pub scale: String,
}

/// Raised when Cerema cannot be reached or answers an error status, distinct from "no data".
#[derive(Debug, Clone, thiserror::Error)]
#[error("{0}")]
pub struct ProviderError(pub String);

pub struct CeremaDv3f {
    http: reqwest::Client,
    base_url: String,
}

impl CeremaDv3f {
    pub fn new(http: reqwest::Client, base_url: &str) -> Self {
        Self {
            http,
            base_url: base_url.trim_end_matches('/').to_owned(),
        }
    }

    pub fn provider_name(&self) -> &'static str {
        PROVIDER_NAME
    }

    /// True when DVF covers the area and the property has an estimable kind and positive area.
    pub fn supports(&self, input: &ValuationInput) -> bool {
        if input.kind.is_none_or(|k| !k.is_estimable()) {
            return false;
        }
        if let Some(country) = input.country_code.as_deref()
            && !country.eq_ignore_ascii_case("FR")
        {
            return false;
        }
        if input
            .insee_code
            .as_deref()
            .is_none_or(|c| c.trim().is_empty())
        {
            return false;
        }
        if input.living_area.is_none_or(|a| a <= Decimal::ZERO) {
            return false;
        }
        if let Some(dept) = input.department_code.as_deref()
            && UNCOVERED_DEPARTMENTS.contains(&dept)
        {
            return false;
        }
        true
    }

    /// Commune median first, falling back to department (marked LOW confidence) when empty.
    pub async fn estimate(
        &self,
        input: &ValuationInput,
    ) -> Result<Option<ValuationResult>, ProviderError> {
        if !self.supports(input) {
            return Ok(None);
        }
        let insee = input.insee_code.as_deref().unwrap_or_default();
        if let Some(commune) = self
            .fetch_and_build("communes", insee, input, false)
            .await?
        {
            return Ok(Some(commune));
        }
        let Some(department) = input.department_code.as_deref() else {
            return Ok(None);
        };
        tracing::debug!(
            "No usable commune median for INSEE {insee} — falling back to department {department}"
        );
        self.fetch_and_build("departements", department, input, true)
            .await
    }

    async fn fetch_and_build(
        &self,
        scale: &str,
        code: &str,
        input: &ValuationInput,
        degraded: bool,
    ) -> Result<Option<ValuationResult>, ProviderError> {
        let payload = self.fetch(scale, code).await?;
        let Some(results) = payload
            .get("results")
            .and_then(Value::as_array)
            .filter(|a| !a.is_empty())
        else {
            return Ok(None);
        };
        for row in results.iter().rev() {
            if let Some(built) = build(row, input, scale, degraded) {
                return Ok(Some(built));
            }
        }
        Ok(None)
    }

    async fn fetch(&self, scale: &str, code: &str) -> Result<Value, ProviderError> {
        let url = format!(
            "{}/indicateurs/dv3f/prix/annuel/?echelle={}&code={}",
            self.base_url,
            percent_encoding::utf8_percent_encode(scale, QUERY_VALUE),
            percent_encoding::utf8_percent_encode(code, QUERY_VALUE),
        );
        let response = self
            .http
            .get(url)
            .header("Accept", "application/json")
            .timeout(TIMEOUT)
            .send()
            .await
            .map_err(|e| {
                let msg = transport_message(&e);
                tracing::warn!("Cerema DV3F unreachable for {scale}={code} ({msg})");
                ProviderError(format!("Cerema DV3F {scale}={code} failed: {msg}"))
            })?;
        if !response.status().is_success() {
            let status = response.status().as_u16();
            tracing::warn!("Cerema DV3F {scale}={code} failed: HTTP {status}");
            return Err(ProviderError(format!(
                "Cerema DV3F {scale}={code} failed: HTTP {status}"
            )));
        }
        response.json::<Value>().await.map_err(|e| {
            tracing::warn!("Cerema DV3F {scale}={code} returned unreadable JSON");
            ProviderError(format!("Cerema DV3F {scale}={code} unreadable JSON: {e}"))
        })
    }
}

fn build(
    row: &Value,
    input: &ValuationInput,
    scale: &str,
    degraded: bool,
) -> Option<ValuationResult> {
    let series = series_for(input, row)?;
    let median = decimal(row, &format!("pxm2_median_{series}")).filter(|v| *v > Decimal::ZERO)?;
    let sample = integer(row, &format!("nbtrans_{series}"));
    let area = input.living_area?;
    let q25 = decimal(row, &format!("pxm2_q25_{series}"));
    let q75 = decimal(row, &format!("pxm2_q75_{series}"));
    let confidence = confidence_for(sample, degraded);

    Some(ValuationResult {
        estimated_value: scale2(median * area),
        low_value: q25.map(|q| scale2(q * area)),
        high_value: q75.map(|q| scale2(q * area)),
        price_per_sqm: median,
        sample_size: sample,
        confidence,
        source_year: short_value(row, "annee"),
        scale: scale.to_owned(),
    })
}

fn series_for(input: &ValuationInput, row: &Value) -> Option<String> {
    match input.kind? {
        PropertyKind::House => Some("cod111".to_owned()),
        PropertyKind::Apartment => {
            if let Some(rooms) = input.rooms.filter(|r| *r > 0) {
                let bucket = rooms.min(MAX_ROOM_SERIES);
                let room_series = format!("cod121x{bucket}");
                let room_sample = integer(row, &format!("nbtrans_{room_series}"));
                let room_median = decimal(row, &format!("pxm2_median_{room_series}"));
                if room_median.is_some_and(|m| m > Decimal::ZERO)
                    && room_sample.is_some_and(|s| s >= MIN_ROOM_SERIES_SAMPLE)
                {
                    return Some(room_series);
                }
            }
            Some("cod121".to_owned())
        }
        _ => None,
    }
}

fn confidence_for(sample: Option<i32>, degraded: bool) -> ValuationConfidence {
    if degraded {
        return ValuationConfidence::Low;
    }
    match sample {
        None => ValuationConfidence::Low,
        Some(n) if n < LOW_CONFIDENCE_SAMPLE => ValuationConfidence::Low,
        Some(n) if n < MEDIUM_CONFIDENCE_SAMPLE => ValuationConfidence::Medium,
        Some(_) => ValuationConfidence::High,
    }
}

fn scale2(value: Decimal) -> Decimal {
    set_scale(value, 2, HALF_UP)
}

fn decimal(row: &Value, field: &str) -> Option<Decimal> {
    row.get(field).and_then(json_decimal)
}

fn integer(row: &Value, field: &str) -> Option<i32> {
    row.get(field)
        .and_then(json_i64)
        .and_then(|v| i32::try_from(v).ok())
}

fn short_value(row: &Value, field: &str) -> Option<i16> {
    let node = row.get(field)?;
    if let Some(s) = node.as_str() {
        return s.trim().parse().ok();
    }
    json_i64(node).and_then(|v| i16::try_from(v).ok())
}
