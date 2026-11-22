//! Real estate summary and valuation history (RealEstateController), address search
//! (GeocodingController), and property valuation / adjustments (PropertyValuationService).

pub mod valuation;

use std::collections::HashMap;

use crate::db::DbHandle;
use axum::extract::State as AxumState;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Serialize;

use crate::AppState;
use crate::auth::Auth;
use crate::clock::AppClock;
use crate::config::Config;
use crate::db::DbFromRow;
use crate::error::AppResult;
use crate::http::json::Path;
use crate::http::rate_limit::policies;
use crate::ledger::decimal::{HALF_UP, divide, set_scale};
use crate::ledger::model::{Account, AccountType};
use crate::ledger::{access, accounts, liabilities, loans, valuation as ledger_valuation};
use crate::market::geocoding;
use crate::routes::Routes;
use crate::wealth::params::{self, Query};

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/real-estate/summary", summary)
        .get("/api/real-estate/{accountId}/valuations", valuations)
        .get("/api/geocode", geocode);
}

pub struct State {}

impl State {
    pub fn new(_config: &Config, _http: &reqwest::Client, _clock: &AppClock) -> Self {
        Self {}
    }
}

fn scale(value: Decimal) -> Decimal {
    set_scale(value, 2, HALF_UP)
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkedLoan {
    pub account_id: i64,
    pub name: String,
    pub lender_name: Option<String>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub outstanding_balance: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub share_percent: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub monthly_payment: Option<Decimal>,
    pub end_date: Option<NaiveDate>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PropertyLine {
    pub account_id: i64,
    pub name: String,
    pub color: String,
    pub property_type: Option<String>,
    pub category: Option<String>,
    pub city: Option<String>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub share_percent: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub gross_value: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub outstanding_debt: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub net_value: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub cost_basis: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub unrealized_gain: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub surface_area: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub rental_income: Decimal,
    pub valuation_mode: String,
    pub last_valued_at: Option<NaiveDate>,
    pub last_confidence: Option<String>,
    pub loans: Vec<LinkedLoan>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PaperLine {
    pub account_id: i64,
    pub name: String,
    pub color: String,
    pub management_company: Option<String>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub share_count: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub share_percent: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub withdrawal_price_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub subscription_price_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub gross_value: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub outstanding_debt: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub net_value: Decimal,
    pub valuation_status: Option<String>,
    pub loans: Vec<LinkedLoan>,
}

/// RealEstateSummaryResponse.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RealEstateSummaryResponse {
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub gross_value: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub outstanding_debt: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub net_value: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub cost_basis: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub unrealized_gain: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub unrealized_gain_percent: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub loan_to_value: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub monthly_rental_income: Decimal,
    pub properties: Vec<PropertyLine>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub paper_gross: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub paper_debt: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub paper_net: Decimal,
    pub paper: Vec<PaperLine>,
}

#[derive(DbFromRow)]
struct Metadata {
    purchase_price: Option<Decimal>,
    agency_fees: Option<Decimal>,
    notary_fees: Option<Decimal>,
    works_cost: Option<Decimal>,
    property_type: Option<String>,
    category: Option<String>,
    city: Option<String>,
    surface_area: Option<Decimal>,
    rental_income: Option<Decimal>,
    valuation_mode: String,
}

impl Metadata {
    /// RealEstateMetadata.costBasis: price plus agency and notary fees and works.
    fn cost_basis(&self) -> Decimal {
        [
            self.purchase_price,
            self.agency_fees,
            self.notary_fees,
            self.works_cost,
        ]
        .into_iter()
        .flatten()
        .sum()
    }
}

#[derive(DbFromRow)]
struct ScpiPosition {
    management_company: Option<String>,
    share_count: Decimal,
    withdrawal_price_eur: Option<Decimal>,
    subscription_price_eur: Option<Decimal>,
    valuation_status: String,
}

/// RealEstateSummaryService.summarize: hidden accounts included, every figure weighted by the
/// member's share.
pub async fn summarize(
    state: &AppState,
    member_id: i64,
    include_hidden: bool,
) -> AppResult<RealEstateSummaryResponse> {
    let mut conn = state.db.pool_handle();
    let mut readable = access::readable_accounts(&mut conn, member_id).await?;
    if !include_hidden {
        readable.retain(|a| !a.hidden);
    }
    let shares = access::shares_for(&mut conn, &readable, member_id).await?;
    let today = state.clock.today();
    let prices = &*state.ledger.prices;

    let (mut gross_total, mut debt_total, mut cost_total, mut rent_total) =
        (Decimal::ZERO, Decimal::ZERO, Decimal::ZERO, Decimal::ZERO);
    let (mut paper_gross, mut paper_debt) = (Decimal::ZERO, Decimal::ZERO);
    let mut properties = Vec::new();
    let mut paper = Vec::new();

    for account in &readable {
        let Some(share) = shares
            .get(&account.id)
            .copied()
            .filter(|s| *s > Decimal::ZERO)
        else {
            continue;
        };
        if account.account_type == AccountType::Scpi {
            let gross = access::weigh(
                ledger_valuation::live_balance_eur(&mut conn, prices, account, today).await?,
                share,
            );
            let position: Option<ScpiPosition> = crate::db::query_as::<ScpiPosition>(
                "SELECT management_company, share_count, withdrawal_price_eur, subscription_price_eur, valuation_status \
                 FROM scpi_position WHERE account_id = $1 AND member_id = $2",
            )
            .bind(account.id)
            .bind(account.member_id)
            .fetch_optional(&mut conn)
            .await?;
            let (debt, loans) =
                loans_for(&mut conn, account, member_id, today, include_hidden).await?;
            paper.push(PaperLine {
                account_id: account.id,
                name: account.name.clone(),
                color: account.color.clone(),
                management_company: position.as_ref().and_then(|p| p.management_company.clone()),
                share_count: position.as_ref().map(|p| p.share_count),
                share_percent: share,
                withdrawal_price_eur: position.as_ref().and_then(|p| p.withdrawal_price_eur),
                subscription_price_eur: position.as_ref().and_then(|p| p.subscription_price_eur),
                gross_value: scale(gross),
                outstanding_debt: scale(debt),
                net_value: scale(gross - debt),
                valuation_status: position.map(|p| p.valuation_status),
                loans,
            });
            paper_gross += gross;
            paper_debt += debt;
            continue;
        }
        if account.account_type != AccountType::RealEstate {
            continue;
        }
        let gross = access::weigh(
            ledger_valuation::live_balance_eur(&mut conn, prices, account, today).await?,
            share,
        );
        let metadata: Option<Metadata> = crate::db::query_as::<Metadata>(
            "SELECT purchase_price, agency_fees, notary_fees, works_cost, property_type, category, city, \
             surface_area, rental_income, valuation_mode FROM real_estate_metadata WHERE account_id = $1",
        )
        .bind(account.id)
        .fetch_optional(&mut conn)
        .await?;
        let cost_basis = metadata
            .as_ref()
            .map_or(Decimal::ZERO, |m| access::weigh(m.cost_basis(), share));
        let rent = metadata.as_ref().map_or(Decimal::ZERO, |m| {
            access::weigh(m.rental_income.unwrap_or_default(), share)
        });
        let (debt, loans) = loans_for(&mut conn, account, member_id, today, include_hidden).await?;
        let latest: Option<(NaiveDate, Option<String>)> =
            crate::db::query_as::<(NaiveDate, Option<String>)>(
                "SELECT valued_at, confidence FROM property_valuation WHERE account_id = $1 ORDER BY valued_at DESC LIMIT 1",
            )
            .bind(account.id)
            .fetch_optional(&mut conn)
            .await?;
        properties.push(PropertyLine {
            account_id: account.id,
            name: account.name.clone(),
            color: account.color.clone(),
            property_type: metadata.as_ref().and_then(|m| m.property_type.clone()),
            category: metadata.as_ref().and_then(|m| m.category.clone()),
            city: metadata.as_ref().and_then(|m| m.city.clone()),
            share_percent: share,
            gross_value: scale(gross),
            outstanding_debt: scale(debt),
            net_value: scale(gross - debt),
            cost_basis: scale(cost_basis),
            unrealized_gain: scale(gross - cost_basis),
            surface_area: metadata.as_ref().and_then(|m| m.surface_area),
            rental_income: scale(rent),
            valuation_mode: metadata
                .as_ref()
                .map_or("ESTIMATED".to_owned(), |m| m.valuation_mode.clone()),
            last_valued_at: latest.as_ref().map(|l| l.0),
            last_confidence: latest.and_then(|l| l.1),
            loans,
        });
        gross_total += gross;
        debt_total += debt;
        cost_total += cost_basis;
        rent_total += rent;
    }

    let gain = gross_total - cost_total;
    let hundred = Decimal::ONE_HUNDRED;
    Ok(RealEstateSummaryResponse {
        gross_value: scale(gross_total),
        outstanding_debt: scale(debt_total),
        net_value: scale(gross_total - debt_total),
        cost_basis: scale(cost_total),
        unrealized_gain: scale(gain),
        unrealized_gain_percent: (cost_total > Decimal::ZERO)
            .then(|| divide(gain * hundred, cost_total, 2, HALF_UP)),
        loan_to_value: (gross_total > Decimal::ZERO)
            .then(|| divide(debt_total * hundred, gross_total, 2, HALF_UP)),
        monthly_rental_income: scale(rent_total),
        properties,
        paper_gross: scale(paper_gross),
        paper_debt: scale(paper_debt),
        paper_net: scale(paper_gross - paper_debt),
        paper,
    })
}

/// The loans financing the property, each at the member's share of the loan account.
async fn loans_for(
    conn: &mut DbHandle<'_>,
    property: &Account,
    member_id: i64,
    today: NaiveDate,
    include_hidden: bool,
) -> AppResult<(Decimal, Vec<LinkedLoan>)> {
    let debts = liabilities::debts_linked_to(&mut *conn, property.id).await?;
    if debts.is_empty() {
        return Ok((Decimal::ZERO, Vec::new()));
    }
    let ids: Vec<i64> = debts.iter().map(|d| d.account_id).collect();
    let loan_accounts: HashMap<i64, Account> = accounts::find_by_ids(&mut *conn, &ids)
        .await?
        .into_iter()
        .map(|a| (a.id, a))
        .collect();
    let listed: Vec<Account> = loan_accounts.values().cloned().collect();
    let loan_shares = access::shares_for(&mut *conn, &listed, member_id).await?;
    let mut total = Decimal::ZERO;
    let mut lines = Vec::new();
    for debt in &debts {
        let Some(loan) = loan_accounts.get(&debt.account_id) else {
            continue;
        };
        if !include_hidden && loan.hidden {
            continue;
        }
        let share = loan_shares.get(&loan.id).copied().unwrap_or_default();
        if share <= Decimal::ZERO {
            continue;
        }
        let remaining = if loans::has_schedule(debt) {
            loans::compute_remaining_balance(debt, today)
        } else {
            loan.current_balance
        };
        let outstanding = access::weigh(remaining, share);
        total += outstanding;
        lines.push(LinkedLoan {
            account_id: loan.id,
            name: loan.name.clone(),
            lender_name: debt.lender_name.clone(),
            outstanding_balance: scale(outstanding),
            share_percent: share,
            monthly_payment: debt.monthly_payment.map(|m| scale(access::weigh(m, share))),
            end_date: debt.end_date,
        });
    }
    Ok((total, lines))
}

async fn summary(
    AxumState(state): AxumState<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<RealEstateSummaryResponse>> {
    summarize(&state, auth.member_id, true)
        .await
        .map(axum::Json)
}

/// RealEstateController.ValuationHistoryEntry.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize, DbFromRow)]
#[serde(rename_all = "camelCase")]
pub struct ValuationHistoryEntry {
    pub valued_at: NaiveDate,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub estimated_value: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub low_value: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub high_value: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub price_per_sqm: Option<Decimal>,
    pub provider: String,
    pub confidence: Option<String>,
    pub sample_size: Option<i32>,
    pub source_year: Option<i16>,
}

/// PropertyValuationService.history: the readable property's valuations, newest first.
pub async fn valuations(
    AxumState(state): AxumState<AppState>,
    auth: Auth,
    Path(account_id): Path<i64>,
) -> AppResult<axum::Json<Vec<ValuationHistoryEntry>>> {
    let mut conn = state.db.pool_handle();
    access::require_readable(&mut conn, account_id, auth.member_id).await?;
    let rows = crate::db::query_as::<ValuationHistoryEntry>(
        "SELECT valued_at, estimated_value, low_value, high_value, price_per_sqm, provider, confidence, \
         sample_size, source_year FROM property_valuation WHERE account_id = $1 ORDER BY valued_at DESC",
    )
    .bind(account_id)
    .fetch_all(&mut conn)
    .await?;
    Ok(axum::Json(rows))
}

/// GeocodingController.GeocodeSuggestion.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GeocodeSuggestion {
    pub label: Option<String>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub score: Option<Decimal>,
    pub postcode: Option<String>,
    pub city: Option<String>,
    pub insee_code: Option<String>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub latitude: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub longitude: Option<Decimal>,
}

/// Address suggestions; a query under three characters is answered empty without a call, and
/// each member may ask 60 times a minute (then a bare 429).
async fn geocode(
    AxumState(state): AxumState<AppState>,
    auth: Auth,
    query: Query,
) -> AppResult<Response> {
    let q = params::required(&query, "q")?;
    let limit: Option<i64> = params::optional(&query, "limit")?;
    if q.trim().chars().count() < 3 {
        return Ok(axum::Json(Vec::<GeocodeSuggestion>::new()).into_response());
    }
    if !state
        .limits
        .try_consume(&policies::GEOCODE, &auth.member_id.to_string())
    {
        tracing::warn!("geocode.rate_limited memberId={}", auth.member_id);
        return Ok(StatusCode::TOO_MANY_REQUESTS.into_response());
    }
    let results = geocoding::autocomplete(
        &state.http,
        &state.config.geocoding_api_url,
        q.trim(),
        limit.unwrap_or(5),
    )
    .await;
    let suggestions: Vec<GeocodeSuggestion> = results
        .into_iter()
        .map(|r| GeocodeSuggestion {
            label: r.label,
            score: r.score,
            postcode: r.postcode,
            city: r.city,
            insee_code: r.insee_code,
            latitude: r.latitude,
            longitude: r.longitude,
        })
        .collect();
    Ok(axum::Json(suggestions).into_response())
}
