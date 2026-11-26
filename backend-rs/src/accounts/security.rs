//! SecurityController and SecurityInsightService: a security's asset type and, for a fund, its
//! composition. Reference data, not member-scoped. The market sources are track T4's (seams).

use std::collections::HashMap;
use std::sync::Mutex;

use axum::extract::{Query, State};
use chrono::{DateTime, Duration, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use crate::AppState;
use crate::accounts::analysis::seams::AnalysisSeams;
use crate::auth::Auth;
use crate::db::{self, DbExec};
use crate::error::AppResult;
use crate::http::json::Path;
use crate::ledger::sync_values::java_is_blank;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes.get("/api/securities/{ticker}/insight", insight);
}

/// How a security is identified when asking a provider (SecurityRef).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SecurityRef {
    pub ticker: String,
    pub name: Option<String>,
    pub isin: Option<String>,
}

/// WeightedSlice: a company, country or sector and its weight in percent.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WeightedSlice {
    pub label: Option<String>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub percent: Option<Decimal>,
}

/// FundFacts: what a fund costs and how it behaves.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FundFacts {
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub ter_percent: Option<Decimal>,
    pub distribution_policy: Option<String>,
    pub replication: Option<String>,
    pub domicile_country_key: Option<String>,
    pub source: Option<String>,
}

impl FundFacts {
    fn is_empty(&self) -> bool {
        self.ter_percent.is_none()
            && self.distribution_policy.is_none()
            && self.replication.is_none()
            && self.domicile_country_key.is_none()
    }
}

/// Two Boursorama occurrences sharing a French label known to collide between two canonical sectors.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AmbiguousSectorCollision {
    pub raw_label: String,
    pub sector_a: String,
    pub sector_b: String,
    pub weights: [Decimal; 2],
}

/// EtfComposition.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EtfComposition {
    pub companies: Vec<WeightedSlice>,
    pub countries: Vec<WeightedSlice>,
    pub sectors: Vec<WeightedSlice>,
    pub source: Option<String>,
    pub as_of: Option<NaiveDate>,
    pub facts: Option<FundFacts>,
    /// Internal hint from a metadata provider (justETF): physical reference ETF ISIN for the
    /// tracked index, used if a synthetic ETF's primary breakdown came from its substitution
    /// basket rather than the index. Never serialized.
    #[serde(skip)]
    pub index_proxy_isin: Option<String>,
    /// Explicit "Other" / "Autres" weight removed while parsing countries, persisted as `"other"`
    /// in `security_composition_slice` so `spread()` never erases it by normalizing to 100 %.
    #[serde(skip)]
    pub countries_other: Option<Decimal>,
    /// Explicit "Other" / "Autres" weight removed while parsing sectors.
    #[serde(skip)]
    pub sectors_other: Option<Decimal>,
    /// Known duplicate-raw-label sector collisions awaiting disambiguation in `resolve_composition`.
    #[serde(skip)]
    pub ambiguous_sectors: Vec<AmbiguousSectorCollision>,
    /// True when a synthetic ETF's suspected substitution basket was dropped with no replacement,
    /// telling profile refresh to clear any stale collateral slices already in the database.
    #[serde(skip)]
    pub dropped_substitution_basket: bool,
}

impl EtfComposition {
    fn has_any_data(&self) -> bool {
        !self.companies.is_empty()
            || !self.countries.is_empty()
            || !self.sectors.is_empty()
            || !self.ambiguous_sectors.is_empty()
    }
}

/// SecurityInsightResponse.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SecurityInsightResponse {
    pub ticker: Option<String>,
    pub asset_type: String,
    pub composition: Option<EtfComposition>,
}

const CACHE_TTL_DAYS: i64 = 3;
const MAX_CACHE_ENTRIES: usize = 1024;

type Cached = (SecurityInsightResponse, DateTime<Utc>);

/// SecurityInsightService's in-memory cache, one per process (state.accounts.analysis).
#[derive(Default)]
pub struct InsightCache(Mutex<HashMap<String, Cached>>);

/// getInsight, cached for three days per ticker (and ISIN when one is given).
pub async fn get_insight<'e>(
    db: impl DbExec<'e>,
    seams: &AnalysisSeams,
    ticker: &str,
    name: Option<&str>,
    isin: Option<&str>,
    now: DateTime<Utc>,
) -> sqlx::Result<SecurityInsightResponse> {
    if java_is_blank(ticker) {
        return Ok(SecurityInsightResponse {
            ticker: Some(ticker.to_owned()),
            asset_type: "UNKNOWN".to_owned(),
            composition: None,
        });
    }
    let upper = ticker.to_uppercase();
    let key = match isin.filter(|i| !java_is_blank(i)) {
        Some(isin) => format!("{upper}|{isin}"),
        None => upper.clone(),
    };
    if let Some(cached) = cache_get(&seams.insights, &key, now) {
        return Ok(cached);
    }
    let mut asset_type = classify(db, seams, &upper).await?;
    let isin_ref = isin.filter(|i| !java_is_blank(i));
    let raw_isin = isin_ref
        .map(str::to_owned)
        .or_else(|| crate::ledger::instrument::is_isin(&upper).then(|| upper.clone()));
    let composition = if asset_type == "ETF" || (asset_type == "UNKNOWN" && raw_isin.is_some()) {
        let comp = resolve_composition(
            seams,
            &SecurityRef {
                ticker: ticker.to_owned(),
                name: name.map(str::to_owned),
                isin: raw_isin,
            },
        )
        .await;
        if comp.is_some() && asset_type == "UNKNOWN" {
            asset_type = "ETF";
        }
        comp
    } else {
        None
    };
    let response = SecurityInsightResponse {
        ticker: Some(upper),
        asset_type: asset_type.to_owned(),
        composition,
    };
    cache_put(&seams.insights, key, response.clone(), now);
    Ok(response)
}

fn cache_get(
    cache: &InsightCache,
    key: &str,
    now: DateTime<Utc>,
) -> Option<SecurityInsightResponse> {
    let mut cache = cache.0.lock().unwrap_or_else(|e| e.into_inner());
    match cache.get(key) {
        Some((response, at)) if now <= *at + Duration::days(CACHE_TTL_DAYS) => {
            Some(response.clone())
        }
        Some(_) => {
            cache.remove(key);
            None
        }
        None => None,
    }
}

fn cache_put(
    cache: &InsightCache,
    key: String,
    response: SecurityInsightResponse,
    now: DateTime<Utc>,
) {
    let mut cache = cache.0.lock().unwrap_or_else(|e| e.into_inner());
    if !cache.contains_key(&key) && cache.len() >= MAX_CACHE_ENTRIES {
        let cutoff = now - Duration::days(CACHE_TTL_DAYS);
        cache.retain(|_, (_, at)| *at >= cutoff);
        if cache.len() >= MAX_CACHE_ENTRIES
            && let Some(oldest) = cache
                .iter()
                .min_by_key(|(_, (_, at))| *at)
                .map(|(k, _)| k.clone())
        {
            cache.remove(&oldest);
        }
    }
    cache.insert(key, (response, now));
}

/// A coin the crypto registry knows, else the instrument type the quote source reports.
async fn classify<'e>(
    db: impl DbExec<'e>,
    seams: &AnalysisSeams,
    upper: &str,
) -> sqlx::Result<&'static str> {
    let coin: bool =
        db::query_scalar("SELECT EXISTS(SELECT 1 FROM crypto_asset WHERE upper(ticker) = $1)")
            .bind(upper)
            .fetch_one(db)
            .await?;
    if coin {
        return Ok("CRYPTO");
    }
    let Some(kind) = seams.instrument_types.instrument_type(upper).await else {
        return Ok("UNKNOWN");
    };
    Ok(match kind.to_uppercase().as_str() {
        "ETF" | "MUTUALFUND" => "ETF",
        "EQUITY" => "STOCK",
        "CRYPTOCURRENCY" => "CRYPTO",
        _ => "UNKNOWN",
    })
}

/// The breakdown whole from the first provider that has one; the facts from whichever has them.
/// When a synthetic ETF's primary breakdown lists physical equity holdings (indicating a swap
/// substitution/collateral basket rather than index exposure), replaces it with the physical
/// index proxy or the secondary provider's index breakdown.
async fn resolve_composition(
    seams: &AnalysisSeams,
    security: &SecurityRef,
) -> Option<EtfComposition> {
    let mut slices: Option<EtfComposition> = None;
    let mut slices_provider_idx: Option<usize> = None;
    let mut fallback_slices: Option<EtfComposition> = None;
    let mut facts: Option<FundFacts> = None;
    let mut facts_provider_idx: Option<usize> = None;
    let mut proxy_isin: Option<String> = None;
    let mut sources: Vec<String> = Vec::new();
    for (idx, provider) in seams.etf_compositions.iter().enumerate() {
        if !provider.supports(security) {
            continue;
        }
        if slices.is_some() && facts.is_some() {
            break;
        }
        let Some(answer) = provider.fetch(security).await else {
            continue;
        };
        let answer_facts = answer.facts.clone();
        if proxy_isin.is_none() && answer.index_proxy_isin.is_some() {
            proxy_isin = answer.index_proxy_isin.clone();
        }
        if slices.is_none() && answer.has_any_data() {
            if let Some(source) = &answer.source {
                sources.push(source.clone());
            }
            slices = Some(answer);
            slices_provider_idx = Some(idx);
        } else if fallback_slices.is_none()
            && (!answer.countries.is_empty() || !answer.sectors.is_empty())
        {
            fallback_slices = Some(answer);
        }
        if facts.is_none()
            && let Some(f) = answer_facts.filter(|f| !f.is_empty())
        {
            if let Some(source) = &f.source
                && !sources.contains(source)
            {
                sources.push(source.clone());
            }
            facts = Some(f);
            facts_provider_idx = Some(idx);
        }
    }
    let is_synthetic = facts
        .as_ref()
        .and_then(|f| f.replication.as_deref())
        .is_some_and(|r| r.eq_ignore_ascii_case("SYNTHETIC"));
    let suspected_substitution_basket = is_synthetic
        && slices_provider_idx != facts_provider_idx
        && slices.as_ref().is_some_and(|s| !s.companies.is_empty());
    let mut dropped_substitution_basket = false;
    if suspected_substitution_basket {
        let mut replacement: Option<EtfComposition> = None;
        if let Some(proxy) = proxy_isin.as_deref()
            && !security
                .isin
                .as_deref()
                .is_some_and(|own| own.eq_ignore_ascii_case(proxy))
        {
            for provider in &seams.etf_compositions {
                if let Some(comp) = provider.fetch_index_proxy(proxy).await
                    && (!comp.countries.is_empty()
                        || !comp.sectors.is_empty()
                        || !comp.ambiguous_sectors.is_empty())
                {
                    replacement = Some(comp);
                    break;
                }
            }
        }
        if replacement.is_none() {
            replacement = fallback_slices.take();
        }
        if let Some(rep) = replacement {
            sources.clear();
            if let Some(src) = &rep.source {
                sources.push(src.clone());
            }
            if let Some(f_src) = facts.as_ref().and_then(|f| f.source.as_ref())
                && !sources.contains(f_src)
            {
                sources.push(f_src.clone());
            }
            slices = Some(rep);
        } else {
            tracing::warn!(
                ticker = %security.ticker,
                isin = security.isin.as_deref().unwrap_or(""),
                "Synthetic ETF returned a suspected substitution basket with no index proxy or fallback breakdown; treating composition as unknown"
            );
            slices = None;
            dropped_substitution_basket = true;
            sources.clear();
            if let Some(f_src) = facts.as_ref().and_then(|f| f.source.as_ref()) {
                sources.push(f_src.clone());
            }
        }
    }
    if let Some(ref mut chosen) = slices {
        let reference_sectors = fallback_slices
            .as_ref()
            .map(|f| f.sectors.as_slice())
            .unwrap_or(&[]);
        disambiguate_sectors(chosen, reference_sectors);
    }
    if slices.is_none() && facts.is_none() {
        return None;
    }
    let source = Some(sources.join(" · "));
    Some(match slices {
        None => EtfComposition {
            companies: Vec::new(),
            countries: Vec::new(),
            sectors: Vec::new(),
            source,
            as_of: None,
            facts,
            index_proxy_isin: None,
            countries_other: None,
            sectors_other: None,
            ambiguous_sectors: Vec::new(),
            dropped_substitution_basket,
        },
        Some(s) => EtfComposition {
            companies: s.companies,
            countries: s.countries,
            sectors: s.sectors,
            source,
            as_of: s.as_of,
            facts,
            index_proxy_isin: None,
            countries_other: s.countries_other,
            sectors_other: s.sectors_other,
            ambiguous_sectors: Vec::new(),
            dropped_substitution_basket,
        },
    })
}

/// Resolves known duplicate-raw-label sector collisions (e.g. Boursorama emitting "Biens de
/// consommation cycliques" twice for cyclical + defensive, or "Finance" twice for financials +
/// real estate) against the secondary provider's (justETF top-4) sectors.
///
/// - If the reference breakdown names one (or both) of the twin sectors, assigns the occurrence
///   with the closest weight to that sector and the other occurrence to the twin sector.
/// - Otherwise, sends both ambiguous occurrences to `"other"` rather than guessing a named sector.
fn disambiguate_sectors(chosen: &mut EtfComposition, reference_sectors: &[WeightedSlice]) {
    if chosen.ambiguous_sectors.is_empty() {
        return;
    }
    let source_name = chosen.source.as_deref().unwrap_or("unknown").to_owned();
    let collisions = std::mem::take(&mut chosen.ambiguous_sectors);
    for collision in collisions {
        let [w0, w1] = collision.weights;
        let ref_a = reference_sectors
            .iter()
            .find(|s| s.label.as_deref() == Some(collision.sector_a.as_str()))
            .and_then(|s| s.percent);
        let ref_b = reference_sectors
            .iter()
            .find(|s| s.label.as_deref() == Some(collision.sector_b.as_str()))
            .and_then(|s| s.percent);
        let assignment = match (ref_a, ref_b) {
            (Some(ja), Some(jb)) => {
                let direct_err = (w0 - ja).abs() + (w1 - jb).abs();
                let swapped_err = (w1 - ja).abs() + (w0 - jb).abs();
                if direct_err <= swapped_err {
                    Some((w0, w1))
                } else {
                    Some((w1, w0))
                }
            }
            (Some(ja), None) => {
                if (w0 - ja).abs() <= (w1 - ja).abs() {
                    Some((w0, w1))
                } else {
                    Some((w1, w0))
                }
            }
            (None, Some(jb)) => {
                if (w0 - jb).abs() <= (w1 - jb).abs() {
                    Some((w1, w0))
                } else {
                    Some((w0, w1))
                }
            }
            (None, None) => None,
        };
        if let Some((for_a, for_b)) = assignment {
            add_or_merge_slice(&mut chosen.sectors, &collision.sector_a, for_a);
            add_or_merge_slice(&mut chosen.sectors, &collision.sector_b, for_b);
        } else {
            tracing::debug!(
                source = source_name.as_str(),
                raw_label = collision.raw_label.as_str(),
                sector_a = collision.sector_a.as_str(),
                sector_b = collision.sector_b.as_str(),
                "Ambiguous duplicate sector label not disambiguated by reference breakdown; routing to other"
            );
            add_or_merge_slice(&mut chosen.sectors, "other", w0 + w1);
        }
    }
}

fn add_or_merge_slice(slices: &mut Vec<WeightedSlice>, label: &str, percent: Decimal) {
    if percent <= Decimal::ZERO {
        return;
    }
    if let Some(existing) = slices
        .iter_mut()
        .find(|s| s.label.as_deref() == Some(label))
    {
        if let Some(p) = existing.percent.as_mut() {
            *p += percent;
        } else {
            existing.percent = Some(percent);
        }
    } else {
        slices.push(WeightedSlice {
            label: Some(label.to_owned()),
            percent: Some(percent),
        });
    }
}

#[derive(Debug, Deserialize)]
pub struct InsightQuery {
    name: Option<String>,
}

async fn insight(
    State(state): State<AppState>,
    _auth: Auth,
    Path(ticker): Path<String>,
    Query(query): Query<InsightQuery>,
) -> AppResult<axum::Json<SecurityInsightResponse>> {
    let stored_isin: Option<String> = if java_is_blank(&ticker) {
        None
    } else {
        db::query_scalar(
            "SELECT isin FROM security_profile WHERE upper(ticker) = $1 AND isin IS NOT NULL",
        )
        .bind(ticker.to_uppercase())
        .fetch_optional(&state.db)
        .await?
    };
    Ok(axum::Json(
        get_insight(
            &state.db,
            &state.accounts.analysis,
            &ticker,
            query.name.as_deref(),
            stored_isin.as_deref(),
            state.clock.now(),
        )
        .await?,
    ))
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::sync::{Arc, Mutex};

    use async_trait::async_trait;
    use chrono::TimeZone;

    use super::*;
    use crate::accounts::analysis::seams::InstrumentTypeSource;
    use crate::db::Db;
    use crate::db::sqlite::{SqliteDb, migrate};

    #[derive(Default)]
    struct CountingTypes(Mutex<HashMap<String, usize>>);

    impl CountingTypes {
        fn calls(&self, ticker: &str) -> usize {
            *self.0.lock().unwrap().get(ticker).unwrap_or(&0)
        }
    }

    #[async_trait]
    impl InstrumentTypeSource for CountingTypes {
        async fn instrument_type(&self, upper_ticker: &str) -> Option<String> {
            *self
                .0
                .lock()
                .unwrap()
                .entry(upper_ticker.to_owned())
                .or_default() += 1;
            Some("EQUITY".to_owned())
        }
    }

    fn in_memory_db() -> Db {
        let sq = SqliteDb::open_in_memory().unwrap();
        sq.with_conn(|c| Ok(migrate::migrate(c).unwrap())).unwrap();
        Db::from(sq)
    }

    #[tokio::test]
    async fn insight_cache_evicts_oldest_entries_once_capacity_is_reached() {
        let db = in_memory_db();
        let types = Arc::new(CountingTypes::default());
        let seams = AnalysisSeams {
            instrument_types: types.clone(),
            ..AnalysisSeams::default()
        };
        let t0 = Utc.with_ymd_and_hms(2026, 1, 15, 10, 0, 0).unwrap();

        // Populate 1025 distinct tickers within the 3-day TTL window.
        for i in 0..1025_i64 {
            let ticker = format!("T{i}");
            let resp = get_insight(&db, &seams, &ticker, None, None, t0 + Duration::seconds(i))
                .await
                .unwrap();
            assert_eq!(resp.asset_type, "STOCK");
        }
        assert_eq!(types.calls("T0"), 1);
        assert_eq!(types.calls("T1024"), 1);

        // The most recent entry is still cached; the oldest entry was evicted.
        get_insight(
            &db,
            &seams,
            "T1024",
            None,
            None,
            t0 + Duration::seconds(2000),
        )
        .await
        .unwrap();
        assert_eq!(types.calls("T1024"), 1);

        get_insight(&db, &seams, "T0", None, None, t0 + Duration::seconds(2001))
            .await
            .unwrap();
        assert_eq!(
            types.calls("T0"),
            2,
            "oldest entry must have been evicted once cache reached its cap"
        );
    }

    #[tokio::test]
    async fn insight_cache_sweeps_expired_entries_before_evicting_live_ones() {
        let db = in_memory_db();
        let types = Arc::new(CountingTypes::default());
        let seams = AnalysisSeams {
            instrument_types: types.clone(),
            ..AnalysisSeams::default()
        };
        let t0 = Utc.with_ymd_and_hms(2026, 1, 15, 10, 0, 0).unwrap();

        // One entry written four days earlier is expired once we advance to t1.
        get_insight(&db, &seams, "EXPIRED", None, None, t0)
            .await
            .unwrap();
        let t1 = t0 + Duration::days(CACHE_TTL_DAYS + 1);
        for i in 0..MAX_CACHE_ENTRIES as i64 {
            let ticker = format!("LIVE{i}");
            get_insight(&db, &seams, &ticker, None, None, t1 + Duration::seconds(i))
                .await
                .unwrap();
        }
        // LIVE0 is still live and must survive because EXPIRED was swept first.
        get_insight(
            &db,
            &seams,
            "LIVE0",
            None,
            None,
            t1 + Duration::seconds(2000),
        )
        .await
        .unwrap();
        assert_eq!(types.calls("LIVE0"), 1);
    }

    struct StubComposition {
        answer: Option<EtfComposition>,
        proxy: Option<EtfComposition>,
    }

    #[async_trait]
    impl crate::accounts::analysis::seams::EtfCompositionProvider for StubComposition {
        fn supports(&self, _security: &SecurityRef) -> bool {
            true
        }

        async fn fetch(&self, _security: &SecurityRef) -> Option<EtfComposition> {
            self.answer.clone()
        }

        async fn fetch_index_proxy(&self, _proxy_isin: &str) -> Option<EtfComposition> {
            self.proxy.clone()
        }
    }

    struct FixedTypes(Option<&'static str>);

    #[async_trait]
    impl InstrumentTypeSource for FixedTypes {
        async fn instrument_type(&self, _upper_ticker: &str) -> Option<String> {
            self.0.map(str::to_owned)
        }
    }

    fn slice(label: &str, pct: &str) -> WeightedSlice {
        WeightedSlice {
            label: Some(label.to_owned()),
            percent: Some(pct.parse().unwrap()),
        }
    }

    fn comp(
        companies: Vec<WeightedSlice>,
        countries: Vec<WeightedSlice>,
        sectors: Vec<WeightedSlice>,
        source: &str,
    ) -> EtfComposition {
        EtfComposition {
            companies,
            countries,
            sectors,
            source: Some(source.to_owned()),
            as_of: None,
            facts: None,
            index_proxy_isin: None,
            countries_other: None,
            sectors_other: None,
            ambiguous_sectors: Vec::new(),
            dropped_substitution_basket: false,
        }
    }

    #[tokio::test]
    async fn synthetic_etf_substitution_basket_is_replaced_by_index_proxy() {
        let db = in_memory_db();
        let t0 = Utc.with_ymd_and_hms(2026, 1, 15, 10, 0, 0).unwrap();

        let collateral_primary = Arc::new(StubComposition {
            answer: Some(comp(
                vec![slice("Allianz SE", "9.93")],
                vec![slice("FR", "26.59"), slice("NL", "26.30")],
                vec![slice("financial_services", "53.11")],
                "Boursorama",
            )),
            proxy: None,
        });
        let mut justetf_ans = comp(
            vec![],
            vec![slice("GB", "21.96"), slice("CH", "14.38")],
            vec![slice("financial_services", "27.26")],
            "justETF",
        );
        justetf_ans.facts = Some(FundFacts {
            ter_percent: Some("0.20".parse().unwrap()),
            distribution_policy: Some("ACCUMULATING".to_owned()),
            replication: Some("SYNTHETIC".to_owned()),
            domicile_country_key: Some("FR".to_owned()),
            source: Some("justETF".to_owned()),
        });
        justetf_ans.index_proxy_isin = Some("LU0908500753".to_owned());
        let justetf_with_proxy = Arc::new(StubComposition {
            answer: Some(justetf_ans),
            proxy: Some(comp(
                vec![slice("ASML", "3.73")],
                vec![slice("GB", "21.79"), slice("CH", "15.33")],
                vec![
                    slice("financial_services", "24.70"),
                    slice("industrials", "18.57"),
                ],
                "Boursorama (proxy)",
            )),
        });

        let seams = AnalysisSeams {
            instrument_types: Arc::new(FixedTypes(Some("ETF"))),
            etf_compositions: vec![collateral_primary, justetf_with_proxy],
            ..AnalysisSeams::default()
        };
        let resp = get_insight(&db, &seams, "ETZ.PA", None, Some("FR0011550193"), t0)
            .await
            .unwrap();
        let comp = resp.composition.expect("composition");
        assert_eq!(comp.source.as_deref(), Some("Boursorama (proxy) · justETF"));
        assert_eq!(comp.countries[0].label.as_deref(), Some("GB"));
        assert_eq!(comp.sectors[0].percent, Some("24.70".parse().unwrap()));
        assert!(!comp.dropped_substitution_basket);
    }

    #[tokio::test]
    async fn synthetic_etf_without_proxy_or_fallback_drops_substitution_basket() {
        let db = in_memory_db();
        let t0 = Utc.with_ymd_and_hms(2026, 1, 15, 10, 0, 0).unwrap();

        let collateral_primary = Arc::new(StubComposition {
            answer: Some(comp(
                vec![slice("Allianz SE", "9.93")],
                vec![slice("FR", "40.00")],
                vec![slice("financial_services", "53.11")],
                "Boursorama",
            )),
            proxy: None,
        });
        let mut justetf_facts_only = comp(vec![], vec![], vec![], "justETF");
        justetf_facts_only.facts = Some(FundFacts {
            ter_percent: Some("0.30".parse().unwrap()),
            distribution_policy: Some("ACCUMULATING".to_owned()),
            replication: Some("SYNTHETIC".to_owned()),
            domicile_country_key: Some("FR".to_owned()),
            source: Some("justETF".to_owned()),
        });
        let justetf = Arc::new(StubComposition {
            answer: Some(justetf_facts_only),
            proxy: None,
        });

        let seams = AnalysisSeams {
            instrument_types: Arc::new(FixedTypes(Some("ETF"))),
            etf_compositions: vec![collateral_primary, justetf],
            ..AnalysisSeams::default()
        };
        let resp = get_insight(&db, &seams, "BNK.PA", None, Some("FR0010345371"), t0)
            .await
            .unwrap();
        let resolved = resp.composition.expect("facts kept");
        assert!(resolved.dropped_substitution_basket);
        assert!(resolved.companies.is_empty());
        assert!(resolved.countries.is_empty());
        assert!(resolved.sectors.is_empty());
        assert_eq!(resolved.source.as_deref(), Some("justETF"));
        assert_eq!(
            resolved.facts.unwrap().replication.as_deref(),
            Some("SYNTHETIC")
        );
    }

    #[tokio::test]
    async fn boursorama_sector_collisions_disambiguate_with_justetf_or_route_to_other() {
        let db = in_memory_db();
        let t0 = Utc.with_ymd_and_hms(2026, 1, 15, 10, 0, 0).unwrap();

        let mut boursorama_ans = comp(
            vec![],
            vec![slice("US", "99.52")],
            vec![slice("technology", "38.24")],
            "Boursorama",
        );
        boursorama_ans.ambiguous_sectors = vec![
            AmbiguousSectorCollision {
                raw_label: "Finance".to_owned(),
                sector_a: "financial_services".to_owned(),
                sector_b: "real_estate".to_owned(),
                weights: ["12.03".parse().unwrap(), "1.80".parse().unwrap()],
            },
            AmbiguousSectorCollision {
                raw_label: "Biens de consommation cycliques".to_owned(),
                sector_a: "consumer_cyclical".to_owned(),
                sector_b: "consumer_defensive".to_owned(),
                weights: ["9.19".parse().unwrap(), "4.88".parse().unwrap()],
            },
        ];

        // Case 1: justETF names financial_services (~13 %) and consumer_defensive (~5 %)
        // -> Finance maps 12.03 -> financial_services, 1.80 -> real_estate;
        // -> Biens de consommation cycliques maps 4.88 -> consumer_defensive, 9.19 -> consumer_cyclical.
        let mut justetf_with_matches = comp(
            vec![],
            vec![slice("US", "97.00")],
            vec![
                slice("technology", "35.00"),
                slice("financial_services", "13.10"),
                slice("consumer_defensive", "5.10"),
            ],
            "justETF",
        );
        justetf_with_matches.facts = Some(FundFacts {
            ter_percent: Some("0.15".parse().unwrap()),
            distribution_policy: None,
            replication: Some("PHYSICAL".to_owned()),
            domicile_country_key: None,
            source: Some("justETF".to_owned()),
        });
        let seams_matched = AnalysisSeams {
            instrument_types: Arc::new(FixedTypes(Some("ETF"))),
            etf_compositions: vec![
                Arc::new(StubComposition {
                    answer: Some(boursorama_ans.clone()),
                    proxy: None,
                }),
                Arc::new(StubComposition {
                    answer: Some(justetf_with_matches),
                    proxy: None,
                }),
            ],
            ..AnalysisSeams::default()
        };
        let matched = get_insight(
            &db,
            &seams_matched,
            "MATCH.PA",
            None,
            Some("IE0000000001"),
            t0,
        )
        .await
        .unwrap()
        .composition
        .unwrap();
        let find_pct = |c: &EtfComposition, k: &str| {
            c.sectors
                .iter()
                .find(|s| s.label.as_deref() == Some(k))
                .and_then(|s| s.percent)
        };
        assert_eq!(
            find_pct(&matched, "financial_services"),
            Some("12.03".parse().unwrap())
        );
        assert_eq!(
            find_pct(&matched, "real_estate"),
            Some("1.80".parse().unwrap())
        );
        assert_eq!(
            find_pct(&matched, "consumer_cyclical"),
            Some("9.19".parse().unwrap())
        );
        assert_eq!(
            find_pct(&matched, "consumer_defensive"),
            Some("4.88".parse().unwrap())
        );
        assert_eq!(find_pct(&matched, "other"), None);

        // Case 2: justETF names NEITHER twin for consumer -> routes both occurrences to "other"
        // while still disambiguating Finance via financial_services.
        let mut justetf_partial = comp(
            vec![],
            vec![slice("US", "97.00")],
            vec![
                slice("technology", "35.00"),
                slice("financial_services", "13.10"),
            ],
            "justETF",
        );
        justetf_partial.facts = Some(FundFacts {
            ter_percent: Some("0.15".parse().unwrap()),
            distribution_policy: None,
            replication: Some("PHYSICAL".to_owned()),
            domicile_country_key: None,
            source: Some("justETF".to_owned()),
        });
        let seams_unmatched = AnalysisSeams {
            instrument_types: Arc::new(FixedTypes(Some("ETF"))),
            etf_compositions: vec![
                Arc::new(StubComposition {
                    answer: Some(boursorama_ans),
                    proxy: None,
                }),
                Arc::new(StubComposition {
                    answer: Some(justetf_partial),
                    proxy: None,
                }),
            ],
            ..AnalysisSeams::default()
        };
        let unmatched = get_insight(
            &db,
            &seams_unmatched,
            "UNMATCH.PA",
            None,
            Some("IE0000000002"),
            t0,
        )
        .await
        .unwrap()
        .composition
        .unwrap();
        assert_eq!(
            find_pct(&unmatched, "financial_services"),
            Some("12.03".parse().unwrap())
        );
        assert_eq!(
            find_pct(&unmatched, "real_estate"),
            Some("1.80".parse().unwrap())
        );
        assert_eq!(find_pct(&unmatched, "consumer_cyclical"), None);
        assert_eq!(find_pct(&unmatched, "consumer_defensive"), None);
        assert_eq!(
            find_pct(&unmatched, "other"),
            Some("14.07".parse().unwrap())
        );
    }

    #[tokio::test]
    async fn synthetic_etf_with_swap_filtered_out_keeps_primary_index_slices() {
        let db = in_memory_db();
        let t0 = Utc.with_ymd_and_hms(2026, 1, 15, 10, 0, 0).unwrap();

        let index_primary = Arc::new(StubComposition {
            answer: Some(comp(
                // Swap line already filtered out => empty companies means index look-through.
                vec![],
                vec![slice("US", "99.52")],
                vec![slice("technology", "38.24")],
                "Boursorama",
            )),
            proxy: None,
        });
        let mut justetf_ans = comp(
            vec![],
            vec![slice("US", "96.97")],
            vec![slice("technology", "47.37")],
            "justETF",
        );
        justetf_ans.facts = Some(FundFacts {
            ter_percent: Some("0.15".parse().unwrap()),
            distribution_policy: Some("ACCUMULATING".to_owned()),
            replication: Some("SYNTHETIC".to_owned()),
            domicile_country_key: Some("FR".to_owned()),
            source: Some("justETF".to_owned()),
        });
        justetf_ans.index_proxy_isin = Some("IE00B5BMR087".to_owned());
        let justetf_secondary = Arc::new(StubComposition {
            answer: Some(justetf_ans),
            proxy: None,
        });

        let seams = AnalysisSeams {
            instrument_types: Arc::new(FixedTypes(Some("ETF"))),
            etf_compositions: vec![index_primary, justetf_secondary],
            ..AnalysisSeams::default()
        };
        let resp = get_insight(&db, &seams, "ESE.PA", None, Some("FR0011550185"), t0)
            .await
            .unwrap();
        let comp = resp.composition.expect("composition");
        assert_eq!(comp.source.as_deref(), Some("Boursorama · justETF"));
        assert_eq!(comp.countries[0].percent, Some("99.52".parse().unwrap()));
    }

    #[tokio::test]
    async fn raw_isin_unknown_to_yahoo_still_resolves_etf_composition() {
        let db = in_memory_db();
        let t0 = Utc.with_ymd_and_hms(2026, 1, 15, 10, 0, 0).unwrap();
        let provider = Arc::new(StubComposition {
            answer: Some(comp(
                vec![],
                vec![slice("US", "100.00")],
                vec![slice("technology", "100.00")],
                "Boursorama",
            )),
            proxy: None,
        });
        let seams = AnalysisSeams {
            instrument_types: Arc::new(FixedTypes(None)),
            etf_compositions: vec![provider],
            ..AnalysisSeams::default()
        };
        let resp = get_insight(&db, &seams, "FR0011550185", None, None, t0)
            .await
            .unwrap();
        assert_eq!(resp.asset_type, "ETF");
        assert!(resp.composition.is_some());
    }
}
