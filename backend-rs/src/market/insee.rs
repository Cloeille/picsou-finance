//! INSEE BDM: the housing price index series (InseeBdmIndexProvider), re-indexing older DVF
//! vintages forward to today using the quarterly "indice des prix des logements anciens".

use std::collections::{BTreeMap, HashMap};
use std::str::FromStr;
use std::sync::Mutex;
use std::time::Duration;

use chrono::{DateTime, Utc};
use quick_xml::Reader;
use quick_xml::events::Event;
use rust_decimal::Decimal;

use super::cerema::PropertyKind;
use super::yahoo::transport_message;
use crate::clock::AppClock;
use crate::ledger::decimal::{HALF_UP, divide};

const TIMEOUT: Duration = Duration::from_secs(15);
const CACHE_TTL: chrono::Duration = chrono::Duration::hours(24);

const IDBANK_APARTMENTS: &str = "010567056";
const IDBANK_ALL: &str = "010567058";
const IDBANK_HOUSES: &str = "010567060";

/// (year, month 1..=12), matching java.time.YearMonth.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct YearMonth {
    pub year: i32,
    pub month: u32,
}

impl YearMonth {
    pub const fn new(year: i32, month: u32) -> Self {
        Self { year, month }
    }

    /// Sortable YYYYQ key, e.g. 2026-01 -> 20261.
    const fn quarter_key(self) -> i32 {
        self.year * 10 + ((self.month as i32 - 1) / 3 + 1)
    }
}

struct CachedSeries {
    values: BTreeMap<i32, Decimal>,
    fetched_at: DateTime<Utc>,
}

pub struct InseeBdm {
    http: reqwest::Client,
    base_url: String,
    clock: AppClock,
    cache: Mutex<HashMap<&'static str, CachedSeries>>,
}

impl InseeBdm {
    pub fn new(http: reqwest::Client, base_url: &str, clock: AppClock) -> Self {
        Self {
            http,
            base_url: base_url.trim_end_matches('/').to_owned(),
            clock,
            cache: Mutex::new(HashMap::new()),
        }
    }

    /// Ratio to multiply a `from`-era price by to express it in `to` money (scale 6, HALF_UP).
    /// Returns None if either period or the index is unavailable.
    pub async fn reindex_ratio(
        &self,
        from: YearMonth,
        to: YearMonth,
        kind: Option<PropertyKind>,
    ) -> Option<Decimal> {
        if from == to {
            return Some(Decimal::ONE);
        }
        let series = self.series(id_bank_for(kind)).await?;
        if series.is_empty() {
            return None;
        }
        let from_val = value_at_or_before(&series, from)?;
        let to_val = value_at_or_before(&series, to)?;
        if from_val <= Decimal::ZERO {
            return None;
        }
        Some(divide(to_val, from_val, 6, HALF_UP))
    }

    async fn series(&self, id_bank: &'static str) -> Option<BTreeMap<i32, Decimal>> {
        let now = self.clock.now();
        let stale = {
            let cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(entry) = cache.get(id_bank) {
                if now <= entry.fetched_at + CACHE_TTL {
                    return Some(entry.values.clone());
                }
                Some(entry.values.clone())
            } else {
                None
            }
        };
        if let Some(fetched) = self.fetch(id_bank).await {
            self.cache.lock().unwrap_or_else(|e| e.into_inner()).insert(
                id_bank,
                CachedSeries {
                    values: fetched.clone(),
                    fetched_at: now,
                },
            );
            return Some(fetched);
        }
        // Serve a stale series rather than nothing on an outage.
        stale
    }

    async fn fetch(&self, id_bank: &str) -> Option<BTreeMap<i32, Decimal>> {
        let url = format!("{}/data/SERIES_BDM/{id_bank}", self.base_url);
        let response = match self
            .http
            .get(url)
            .header("Accept", "application/xml")
            .timeout(TIMEOUT)
            .send()
            .await
        {
            Ok(r) if r.status().is_success() => r,
            Ok(r) => {
                tracing::warn!(
                    "INSEE index {id_bank} unavailable (HTTP {}) — estimates will skip re-indexing",
                    r.status().as_u16()
                );
                return None;
            }
            Err(e) => {
                tracing::warn!(
                    "INSEE index {id_bank} unavailable ({}) — estimates will skip re-indexing",
                    transport_message(&e)
                );
                return None;
            }
        };
        let xml = match response.text().await {
            Ok(text) if !text.trim().is_empty() => text,
            _ => return None,
        };
        match parse_sdmx(&xml) {
            Ok(values) => Some(values),
            Err(e) => {
                tracing::warn!(
                    "INSEE index {id_bank} could not be parsed ({e}) — estimates will skip re-indexing"
                );
                None
            }
        }
    }
}

fn id_bank_for(kind: Option<PropertyKind>) -> &'static str {
    match kind {
        Some(PropertyKind::House) => IDBANK_HOUSES,
        Some(PropertyKind::Apartment) => IDBANK_APARTMENTS,
        _ => IDBANK_ALL,
    }
}

fn value_at_or_before(series: &BTreeMap<i32, Decimal>, period: YearMonth) -> Option<Decimal> {
    series
        .range(..=period.quarter_key())
        .next_back()
        .map(|(_, v)| *v)
}

/// Pulls `<Obs TIME_PERIOD="YYYY-Qn" OBS_VALUE="..."/>` out of the SDMX-ML payload.
pub fn parse_sdmx(xml: &str) -> Result<BTreeMap<i32, Decimal>, quick_xml::Error> {
    let mut reader = Reader::from_str(xml);
    let mut values = BTreeMap::new();
    loop {
        match reader.read_event()? {
            Event::Start(e) | Event::Empty(e) => {
                if e.local_name().as_ref() != "Obs" {
                    continue;
                }
                let mut period: Option<String> = None;
                let mut obs_val: Option<String> = None;
                for attr in e.attributes().flatten() {
                    match attr.key.local_name().as_ref() {
                        "TIME_PERIOD" => period = Some(attr.value.as_ref().to_owned()),
                        "OBS_VALUE" => obs_val = Some(attr.value.as_ref().to_owned()),
                        _ => {}
                    }
                }
                if let (Some(key), Some(raw)) = (
                    period.as_deref().and_then(parse_period),
                    obs_val.as_deref().filter(|s| !s.trim().is_empty()),
                ) && let Ok(num) = Decimal::from_str(raw.trim())
                {
                    values.insert(key, num);
                }
            }
            Event::Eof => break,
            _ => {}
        }
    }
    Ok(values)
}

/// `2026-Q1` to `20261`. None for annual or monthly periods.
fn parse_period(period: &str) -> Option<i32> {
    let bytes = period.as_bytes();
    if bytes.len() != 7 || bytes[4] != b'-' || bytes[5] != b'Q' {
        return None;
    }
    let year: i32 = period[..4].parse().ok()?;
    let quarter: i32 = period[6..].parse().ok()?;
    (1..=4).contains(&quarter).then_some(year * 10 + quarter)
}
