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
}

impl EtfComposition {
    fn has_any_data(&self) -> bool {
        !self.companies.is_empty() || !self.countries.is_empty() || !self.sectors.is_empty()
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
    if let Some((cached, at)) = cache_get(&seams.insights, &key)
        && now <= at + Duration::days(CACHE_TTL_DAYS)
    {
        return Ok(cached);
    }
    let asset_type = classify(db, seams, &upper).await?;
    let composition = if asset_type == "ETF" {
        resolve_composition(
            seams,
            &SecurityRef {
                ticker: ticker.to_owned(),
                name: name.map(str::to_owned),
                isin: isin.map(str::to_owned),
            },
        )
        .await
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

fn cache_get(cache: &InsightCache, key: &str) -> Option<Cached> {
    let cache = cache.0.lock().unwrap_or_else(|e| e.into_inner());
    cache.get(key).cloned()
}

fn cache_put(
    cache: &InsightCache,
    key: String,
    response: SecurityInsightResponse,
    now: DateTime<Utc>,
) {
    let mut cache = cache.0.lock().unwrap_or_else(|e| e.into_inner());
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
async fn resolve_composition(
    seams: &AnalysisSeams,
    security: &SecurityRef,
) -> Option<EtfComposition> {
    let mut slices: Option<EtfComposition> = None;
    let mut facts: Option<FundFacts> = None;
    let mut sources: Vec<String> = Vec::new();
    for provider in &seams.etf_compositions {
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
        if slices.is_none() && answer.has_any_data() {
            if let Some(source) = &answer.source {
                sources.push(source.clone());
            }
            slices = Some(answer);
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
        }
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
        },
        Some(s) => EtfComposition {
            companies: s.companies,
            countries: s.countries,
            sectors: s.sectors,
            source,
            as_of: s.as_of,
            facts,
        },
    })
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
    Ok(axum::Json(
        get_insight(
            &state.db,
            &state.accounts.analysis,
            &ticker,
            query.name.as_deref(),
            None,
            state.clock.now(),
        )
        .await?,
    ))
}
