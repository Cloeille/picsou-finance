//! PropertyAdjustments and PropertyValuationService: heuristic property adjustments over Cerema
//! DV3F medians + INSEE BDM re-indexing + IGN geocoding, persisting property_valuation rows and
//! updating ESTIMATED account balances. Exposed for T2's POST /api/accounts/{id}/valuation/refresh
//! and T8's monthly valuation scheduler.

use chrono::{Datelike, NaiveDate};
use rust_decimal::Decimal;
use serde::Serialize;
use serde_json::{Value, json};

use crate::AppState;
use crate::db::{AsDbHandle, DbExec, DbFromRow, DbHandle};
use crate::error::{AppError, AppResult};
use crate::ledger::decimal::{HALF_UP, divide, set_scale};
use crate::ledger::model::{
    Account, AccountType, ValuationConfidence, ValuationMode, ValuationStatus,
};
use crate::ledger::{access, accounts};
use crate::market::cerema::{PropertyKind, ValuationInput, ValuationResult};
use crate::market::geocoding;
use crate::market::insee::YearMonth;

const NO_ELEVATOR_BASE: Decimal = Decimal::from_parts(5, 0, 0, true, 2); // -0.05
const NO_ELEVATOR_PER_FLOOR: Decimal = Decimal::from_parts(1, 0, 0, true, 2); // -0.01
const NO_ELEVATOR_FLOOR_CAP: Decimal = Decimal::from_parts(10, 0, 0, true, 2); // -0.10
const NO_ELEVATOR_FROM_FLOOR: i16 = 3;

const TOP_FLOOR_WITH_ELEVATOR: Decimal = Decimal::from_parts(3, 0, 0, false, 2); // 0.03
const GROUND_FLOOR: Decimal = Decimal::from_parts(3, 0, 0, true, 2); // -0.03
const EXTRA_BATHROOM: Decimal = Decimal::from_parts(2, 0, 0, false, 2); // 0.02
const EXTRA_BATHROOM_CAP: Decimal = Decimal::from_parts(4, 0, 0, false, 2); // 0.04

const GARDEN_APARTMENT: Decimal = Decimal::from_parts(5, 0, 0, false, 2); // 0.05
const GARDEN_HOUSE: Decimal = Decimal::from_parts(2, 0, 0, false, 2); // 0.02
const TERRACE: Decimal = Decimal::from_parts(3, 0, 0, false, 2); // 0.03
const BALCONY: Decimal = Decimal::from_parts(15, 0, 0, false, 3); // 0.015

const ENERGY_A_B: Decimal = Decimal::from_parts(4, 0, 0, false, 2); // 0.04
const ENERGY_C: Decimal = Decimal::from_parts(2, 0, 0, false, 2); // 0.02
const ENERGY_E: Decimal = Decimal::from_parts(3, 0, 0, true, 2); // -0.03
const ENERGY_F: Decimal = Decimal::from_parts(6, 0, 0, true, 2); // -0.06
const ENERGY_G: Decimal = Decimal::from_parts(10, 0, 0, true, 2); // -0.10

const ERA_PRE_1949: Decimal = Decimal::from_parts(2, 0, 0, true, 2); // -0.02
const ERA_1949_1974: Decimal = Decimal::from_parts(4, 0, 0, true, 2); // -0.04
const ERA_2001_2012: Decimal = Decimal::from_parts(2, 0, 0, false, 2); // 0.02
const ERA_POST_2012: Decimal = Decimal::from_parts(5, 0, 0, false, 2); // 0.05

const MIN_MULTIPLIER: Decimal = Decimal::from_parts(75, 0, 0, false, 2); // 0.75
const MAX_MULTIPLIER: Decimal = Decimal::from_parts(125, 0, 0, false, 2); // 1.25

const GARAGE_SQM_EQUIVALENT: Decimal = Decimal::from_parts(12, 0, 0, false, 0);
const PARKING_SQM_EQUIVALENT: Decimal = Decimal::from_parts(7, 0, 0, false, 0);
const LAND_REFERENCE_SQM: Decimal = Decimal::from_parts(500, 0, 0, false, 0);
const LAND_SQM_PER_EXTRA_SQM: Decimal = Decimal::from_parts(2, 0, 0, false, 2); // 0.02
const LAND_CAP_SQM_EQUIVALENT: Decimal = Decimal::from_parts(30, 0, 0, false, 0);
const ADDITIVE_CAP_SQM_EQUIVALENT: Decimal = Decimal::from_parts(60, 0, 0, false, 0);

const MIN_GEOCODE_SCORE: Decimal = Decimal::from_parts(4, 0, 0, false, 1); // 0.4

/// Full real_estate_metadata row needed for valuation and adjustments.
#[derive(Debug, Clone, DbFromRow)]
pub struct FullMetadata {
    pub id: i64,
    pub member_id: i64,
    pub account_id: i64,
    pub purchase_price: Decimal,
    pub agency_fees: Option<Decimal>,
    pub notary_fees: Option<Decimal>,
    pub works_cost: Option<Decimal>,
    pub property_type: Option<String>,
    pub address: Option<String>,
    pub postal_code: Option<String>,
    pub city: Option<String>,
    pub country: Option<String>,
    pub insee_code: Option<String>,
    pub latitude: Option<Decimal>,
    pub longitude: Option<Decimal>,
    pub surface_area: Option<Decimal>,
    pub land_area: Option<Decimal>,
    pub construction_year: Option<i16>,
    pub rooms: Option<i16>,
    pub bathrooms: Option<i16>,
    pub floor_number: Option<i16>,
    pub floors_total: Option<i16>,
    pub has_elevator: Option<bool>,
    pub garage_count: i16,
    pub parking_count: i16,
    pub has_garden: bool,
    pub has_terrace: bool,
    pub has_balcony: bool,
    pub energy_class: Option<String>,
    pub valuation_mode: ValuationMode,
}

impl FullMetadata {
    pub fn kind(&self) -> Option<PropertyKind> {
        self.property_type.as_deref().and_then(PropertyKind::parse)
    }

    pub fn cost_basis(&self) -> Decimal {
        self.purchase_price
            + self.agency_fees.unwrap_or_default()
            + self.notary_fees.unwrap_or_default()
            + self.works_cost.unwrap_or_default()
    }
}

/// PropertyValuationResponse.AdjustmentDto.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AdjustmentDto {
    pub code: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub factor: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub sqm: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub amount: Option<Decimal>,
}

/// PropertyAdjustments.Result.
#[derive(Debug, Clone, PartialEq)]
pub struct AdjustmentResult {
    pub value: Decimal,
    pub applied: Vec<AdjustmentDto>,
    pub multiplier: Decimal,
    pub added_amount: Decimal,
}

impl AdjustmentResult {
    pub fn apply_to(&self, bound: Option<Decimal>) -> Option<Decimal> {
        bound.map(|b| scale2(b * self.multiplier + self.added_amount))
    }
}

/// PropertyValuationResponse.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PropertyValuationResponse {
    pub status: ValuationStatus,
    pub mode: ValuationMode,
    pub applied_to_balance: bool,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub estimated_value: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub low_value: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub high_value: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub price_per_sqm: Option<Decimal>,
    pub sample_size: Option<i32>,
    pub confidence: Option<ValuationConfidence>,
    pub source_year: Option<i16>,
    pub provider: Option<String>,
    pub scale: Option<String>,
    pub valued_at: Option<NaiveDate>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub reindex_ratio: Option<Decimal>,
    pub adjustments: Vec<AdjustmentDto>,
}

impl PropertyValuationResponse {
    pub fn failed(status: ValuationStatus, mode: ValuationMode) -> Self {
        Self {
            status,
            mode,
            applied_to_balance: false,
            estimated_value: None,
            low_value: None,
            high_value: None,
            price_per_sqm: None,
            sample_size: None,
            confidence: None,
            source_year: None,
            provider: None,
            scale: None,
            valued_at: None,
            reindex_ratio: None,
            adjustments: Vec::new(),
        }
    }
}

fn scale2(v: Decimal) -> Decimal {
    set_scale(v, 2, HALF_UP)
}

/// PropertyAdjustments.compute: multiplicative + additive area-equivalent corrections.
pub fn compute_adjustments(
    m: &FullMetadata,
    base_value: Decimal,
    price_per_sqm: Option<Decimal>,
) -> AdjustmentResult {
    let mut applied = Vec::new();
    let kind = m.kind();

    let mut factor_sum = Decimal::ZERO;
    factor_sum += floor_factors(m, kind, &mut applied, base_value);
    factor_sum += outdoor_factors(m, kind, &mut applied, base_value);
    factor_sum += bathroom_factor(m, &mut applied, base_value);
    factor_sum += energy_or_era_factor(m, &mut applied, base_value);

    let multiplier = (Decimal::ONE + factor_sum)
        .max(MIN_MULTIPLIER)
        .min(MAX_MULTIPLIER);
    let extra_sqm = additive_sqm(m, kind, &mut applied, price_per_sqm);
    let added_amount = match price_per_sqm {
        Some(pps) if extra_sqm > Decimal::ZERO => extra_sqm * pps,
        _ => Decimal::ZERO,
    };
    let adjusted = scale2(base_value * multiplier + added_amount);

    AdjustmentResult {
        value: adjusted,
        applied,
        multiplier,
        added_amount,
    }
}

fn record_factor(
    applied: &mut Vec<AdjustmentDto>,
    code: impl Into<String>,
    factor: Decimal,
    base: Decimal,
) -> Decimal {
    applied.push(AdjustmentDto {
        code: code.into(),
        factor: Some(factor),
        sqm: None,
        amount: Some(scale2(base * factor)),
    });
    factor
}

fn additive_dto(code: &str, sqm: Decimal, price_per_sqm: Option<Decimal>) -> AdjustmentDto {
    AdjustmentDto {
        code: code.to_owned(),
        factor: None,
        sqm: Some(sqm),
        amount: price_per_sqm.map(|pps| scale2(sqm * pps)),
    }
}

fn floor_factors(
    m: &FullMetadata,
    kind: Option<PropertyKind>,
    applied: &mut Vec<AdjustmentDto>,
    base: Decimal,
) -> Decimal {
    if kind != Some(PropertyKind::Apartment) {
        return Decimal::ZERO;
    }
    let Some(floor) = m.floor_number else {
        return Decimal::ZERO;
    };
    let has_elevator = m.has_elevator == Some(true);
    let mut total = Decimal::ZERO;
    if floor == 0 {
        total += record_factor(applied, "GROUND_FLOOR", GROUND_FLOOR, base);
    } else if floor >= NO_ELEVATOR_FROM_FLOOR && !has_elevator {
        let extra = NO_ELEVATOR_PER_FLOOR * Decimal::from(floor - NO_ELEVATOR_FROM_FLOOR);
        let penalty = (NO_ELEVATOR_BASE + extra).max(NO_ELEVATOR_FLOOR_CAP);
        total += record_factor(applied, "NO_ELEVATOR", penalty, base);
    }
    if has_elevator
        && let Some(top) = m.floors_total
        && top >= 3
        && floor == top
    {
        total += record_factor(applied, "TOP_FLOOR_ELEVATOR", TOP_FLOOR_WITH_ELEVATOR, base);
    }
    total
}

fn bathroom_factor(m: &FullMetadata, applied: &mut Vec<AdjustmentDto>, base: Decimal) -> Decimal {
    let Some(bathrooms) = m.bathrooms.filter(|b| *b > 1) else {
        return Decimal::ZERO;
    };
    let factor = (EXTRA_BATHROOM * Decimal::from(bathrooms - 1)).min(EXTRA_BATHROOM_CAP);
    record_factor(applied, "EXTRA_BATHROOMS", factor, base)
}

fn outdoor_factors(
    m: &FullMetadata,
    kind: Option<PropertyKind>,
    applied: &mut Vec<AdjustmentDto>,
    base: Decimal,
) -> Decimal {
    let mut total = Decimal::ZERO;
    if m.has_garden {
        let factor = if kind == Some(PropertyKind::Apartment) {
            GARDEN_APARTMENT
        } else {
            GARDEN_HOUSE
        };
        total += record_factor(applied, "GARDEN", factor, base);
    }
    if m.has_terrace {
        total += record_factor(applied, "TERRACE", TERRACE, base);
    }
    if m.has_balcony {
        total += record_factor(applied, "BALCONY", BALCONY, base);
    }
    total
}

fn energy_or_era_factor(
    m: &FullMetadata,
    applied: &mut Vec<AdjustmentDto>,
    base: Decimal,
) -> Decimal {
    if let Some(energy) = m
        .energy_class
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        let upper = energy.to_uppercase();
        let factor = match upper.as_str() {
            "A" | "B" => ENERGY_A_B,
            "C" => ENERGY_C,
            "E" => ENERGY_E,
            "F" => ENERGY_F,
            "G" => ENERGY_G,
            _ => Decimal::ZERO,
        };
        if !factor.is_zero() {
            return record_factor(applied, format!("ENERGY_{upper}"), factor, base);
        }
        return Decimal::ZERO;
    }
    let Some(year) = m.construction_year else {
        return Decimal::ZERO;
    };
    let (code, factor) = if year < 1949 {
        ("ERA_PRE_1949", ERA_PRE_1949)
    } else if year <= 1974 {
        ("ERA_1949_1974", ERA_1949_1974)
    } else if year <= 2000 {
        return Decimal::ZERO;
    } else if year <= 2012 {
        ("ERA_2001_2012", ERA_2001_2012)
    } else {
        ("ERA_POST_2012", ERA_POST_2012)
    };
    record_factor(applied, code, factor, base)
}

fn additive_sqm(
    m: &FullMetadata,
    kind: Option<PropertyKind>,
    applied: &mut Vec<AdjustmentDto>,
    price_per_sqm: Option<Decimal>,
) -> Decimal {
    let mut total = Decimal::ZERO;
    let from = applied.len();

    if m.garage_count > 0 {
        let sqm = GARAGE_SQM_EQUIVALENT * Decimal::from(m.garage_count);
        total += sqm;
        applied.push(additive_dto("GARAGE", sqm, price_per_sqm));
    }
    if m.parking_count > 0 {
        let sqm = PARKING_SQM_EQUIVALENT * Decimal::from(m.parking_count);
        total += sqm;
        applied.push(additive_dto("PARKING", sqm, price_per_sqm));
    }
    if kind == Some(PropertyKind::House)
        && let Some(land) = m.land_area
    {
        let extra = land - LAND_REFERENCE_SQM;
        if extra > Decimal::ZERO {
            let sqm = (extra * LAND_SQM_PER_EXTRA_SQM).min(LAND_CAP_SQM_EQUIVALENT);
            total += sqm;
            applied.push(additive_dto("LAND_SURPLUS", sqm, price_per_sqm));
        }
    }

    if total > ADDITIVE_CAP_SQM_EQUIVALENT {
        let ratio = divide(ADDITIVE_CAP_SQM_EQUIVALENT, total, 10, HALF_UP);
        for dto in &mut applied[from..] {
            if let Some(sqm) = dto.sqm {
                let scaled = scale2(sqm * ratio);
                dto.sqm = Some(scaled);
                dto.amount = price_per_sqm.map(|pps| scale2(scaled * pps));
            }
        }
        return ADDITIVE_CAP_SQM_EQUIVALENT;
    }
    total
}

async fn load_metadata<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
) -> sqlx::Result<Option<FullMetadata>> {
    crate::db::query_as::<FullMetadata>(
        "SELECT id, member_id, account_id, purchase_price, agency_fees, notary_fees, works_cost, \
         property_type, address, postal_code, city, country, insee_code, latitude, longitude, \
         surface_area, land_area, construction_year, rooms, bathrooms, floor_number, floors_total, \
         has_elevator, garage_count, parking_count, has_garden, has_terrace, has_balcony, \
         energy_class, valuation_mode \
         FROM real_estate_metadata WHERE account_id = $1",
    )
    .bind(account_id)
    .fetch_optional(db)
    .await
}

/// PropertyValuationService.estimate: owner-only valuation of a single REAL_ESTATE account.
pub async fn estimate(
    state: &AppState,
    account_id: i64,
    member_id: i64,
) -> AppResult<PropertyValuationResponse> {
    let mut tx = state.db.begin().await?;
    let account = access::require_owner(&mut tx.as_handle(), account_id, member_id).await?;
    if account.account_type != AccountType::RealEstate {
        return Err(AppError::BadRequest(
            "Account is not a real estate account".to_owned(),
        ));
    }
    let metadata = load_metadata(&mut tx, account_id).await?.ok_or_else(|| {
        AppError::status(
            axum::http::StatusCode::CONFLICT,
            "Property has no metadata yet",
        )
    })?;
    let response = estimate_for(state, &mut tx.as_handle(), &account, metadata).await?;
    tx.commit().await?;
    Ok(response)
}

/// PropertyValuationService.refreshAllForMember: values each REAL_ESTATE property of the member
/// in its own isolated transaction; returns how many succeeded with status OK.
pub async fn refresh_all_for_member(state: &AppState, member_id: i64) -> usize {
    if !state.config.valuation_enabled {
        return 0;
    }
    let member_accounts = match accounts::find_all_by_member(&state.db, member_id).await {
        Ok(list) => list,
        Err(e) => {
            tracing::error!(
                "Could not list accounts for valuation refresh (member {member_id}): {e}"
            );
            return 0;
        }
    };
    let mut refreshed = 0;
    for account in member_accounts {
        if account.account_type != AccountType::RealEstate {
            continue;
        }
        let outcome: AppResult<bool> = async {
            let mut tx = state.db.begin().await?;
            let Some(metadata) = load_metadata(&mut tx, account.id).await? else {
                return Ok(false);
            };
            let resp = estimate_for(state, &mut tx.as_handle(), &account, metadata).await?;
            tx.commit().await?;
            Ok(resp.status == ValuationStatus::Ok)
        }
        .await;
        match outcome {
            Ok(true) => refreshed += 1,
            Ok(false) => {}
            Err(e) => {
                tracing::error!(
                    "Valuation failed for property {} (member {member_id}) — skipping it: {e}",
                    account.id
                );
            }
        }
    }
    refreshed
}

async fn estimate_for(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    account: &Account,
    mut metadata: FullMetadata,
) -> AppResult<PropertyValuationResponse> {
    let mode = metadata.valuation_mode;
    if !state.config.valuation_enabled {
        return with_cost_basis_floor(
            conn,
            account,
            &metadata,
            PropertyValuationResponse::failed(ValuationStatus::ProviderUnavailable, mode),
        )
        .await;
    }
    let Some(kind) = metadata.kind().filter(|k| k.is_estimable()) else {
        return with_cost_basis_floor(
            conn,
            account,
            &metadata,
            PropertyValuationResponse::failed(ValuationStatus::NotEstimable, mode),
        )
        .await;
    };
    if metadata.surface_area.is_none_or(|a| a <= Decimal::ZERO) {
        return with_cost_basis_floor(
            conn,
            account,
            &metadata,
            PropertyValuationResponse::failed(ValuationStatus::IncompleteData, mode),
        )
        .await;
    }
    let geocode_status = geocode_if_needed(state, conn, &mut metadata).await?;
    if geocode_status != ValuationStatus::Ok {
        return with_cost_basis_floor(
            conn,
            account,
            &metadata,
            PropertyValuationResponse::failed(geocode_status, mode),
        )
        .await;
    }

    let input = to_input(&metadata, kind);
    let cerema = state.market.cerema();
    if !cerema.supports(&input) {
        return with_cost_basis_floor(
            conn,
            account,
            &metadata,
            PropertyValuationResponse::failed(ValuationStatus::UnsupportedArea, mode),
        )
        .await;
    }

    let raw = match cerema.estimate(&input).await {
        Ok(Some(r)) => r,
        Ok(None) => {
            return with_cost_basis_floor(
                conn,
                account,
                &metadata,
                PropertyValuationResponse::failed(ValuationStatus::NoComparableData, mode),
            )
            .await;
        }
        Err(e) => {
            tracing::warn!(
                "Valuation provider unavailable for property {}: {e}",
                account.id
            );
            return with_cost_basis_floor(
                conn,
                account,
                &metadata,
                PropertyValuationResponse::failed(ValuationStatus::ProviderUnavailable, mode),
            )
            .await;
        }
    };

    let adjusted = compute_adjustments(&metadata, raw.estimated_value, Some(raw.price_per_sqm));
    let mut low = adjusted.apply_to(raw.low_value);
    let mut high = adjusted.apply_to(raw.high_value);

    let today = state.clock.today();
    let reindex_ratio = match raw.source_year {
        Some(year) => {
            let from = YearMonth::new(i32::from(year), 7);
            let to = YearMonth::new(today.year(), today.month());
            if from < to {
                state
                    .market
                    .insee()
                    .reindex_ratio(from, to, Some(kind))
                    .await
            } else {
                None
            }
        }
        None => None,
    };

    let mut final_value = adjusted.value;
    if let Some(ratio) = reindex_ratio {
        final_value *= ratio;
        low = low.map(|v| v * ratio);
        high = high.map(|v| v * ratio);
    }
    final_value = scale2(final_value);
    let low_scaled = low.map(scale2);
    let high_scaled = high.map(scale2);

    let detail = method_detail_json(&raw, &adjusted, reindex_ratio);
    let provider_name = cerema.provider_name();
    let now = state.clock.now();
    crate::db::query(
        "INSERT INTO property_valuation \
         (account_id, member_id, valued_at, estimated_value, low_value, high_value, price_per_sqm, \
          provider, confidence, sample_size, source_year, method_detail, created_at, updated_at) \
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $13) \
         ON CONFLICT (account_id, valued_at) DO UPDATE SET \
           estimated_value = EXCLUDED.estimated_value, \
           low_value = EXCLUDED.low_value, \
           high_value = EXCLUDED.high_value, \
           price_per_sqm = EXCLUDED.price_per_sqm, \
           provider = EXCLUDED.provider, \
           confidence = EXCLUDED.confidence, \
           sample_size = EXCLUDED.sample_size, \
           source_year = EXCLUDED.source_year, \
           method_detail = EXCLUDED.method_detail, \
           updated_at = EXCLUDED.updated_at",
    )
    .bind(account.id)
    .bind(metadata.member_id)
    .bind(today)
    .bind(final_value)
    .bind(low_scaled)
    .bind(high_scaled)
    .bind(raw.price_per_sqm)
    .bind(provider_name)
    .bind(raw.confidence)
    .bind(raw.sample_size)
    .bind(raw.source_year)
    .bind(detail)
    .bind(now)
    .execute(&mut *conn)
    .await?;

    let applied_to_balance = mode == ValuationMode::Estimated;
    if applied_to_balance {
        crate::db::query("UPDATE account SET current_balance = $2, updated_at = $3 WHERE id = $1")
            .bind(account.id)
            .bind(final_value)
            .bind(now)
            .execute(&mut *conn)
            .await?;
    }

    Ok(PropertyValuationResponse {
        status: ValuationStatus::Ok,
        mode,
        applied_to_balance,
        estimated_value: Some(final_value),
        low_value: low_scaled,
        high_value: high_scaled,
        price_per_sqm: Some(raw.price_per_sqm),
        sample_size: raw.sample_size,
        confidence: Some(raw.confidence),
        source_year: raw.source_year,
        provider: Some(provider_name.to_owned()),
        scale: Some(raw.scale),
        valued_at: Some(today),
        reindex_ratio,
        adjustments: adjusted.applied,
    })
}

async fn with_cost_basis_floor(
    conn: &mut DbHandle<'_>,
    account: &Account,
    metadata: &FullMetadata,
    response: PropertyValuationResponse,
) -> AppResult<PropertyValuationResponse> {
    if !account.current_balance.is_zero() {
        return Ok(response);
    }
    let cost_basis = metadata.cost_basis();
    if cost_basis <= Decimal::ZERO {
        return Ok(response);
    }
    tracing::info!(
        "Property {} has no valuation yet; seeding its value from the cost basis {cost_basis}",
        account.id
    );
    crate::db::query("UPDATE account SET current_balance = $2 WHERE id = $1")
        .bind(account.id)
        .bind(cost_basis)
        .execute(conn)
        .await?;
    Ok(response)
}

async fn geocode_if_needed(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    m: &mut FullMetadata,
) -> AppResult<ValuationStatus> {
    if m.insee_code
        .as_deref()
        .is_some_and(|c| !c.trim().is_empty())
    {
        return Ok(ValuationStatus::Ok);
    }
    let query = build_address_query(m);
    if query.is_empty() {
        return Ok(ValuationStatus::IncompleteData);
    }
    let Some(hit) = geocoding::geocode(&state.http, &state.config.geocoding_api_url, &query).await
    else {
        return Ok(ValuationStatus::GeocodingFailed);
    };
    let Some(insee) = hit.insee_code.filter(|c| !c.trim().is_empty()) else {
        return Ok(ValuationStatus::GeocodingFailed);
    };
    if hit.score.is_some_and(|s| s < MIN_GEOCODE_SCORE) {
        tracing::info!(
            "Geocoding score {:?} too low for '{query}' — refusing to value on it",
            hit.score
        );
        return Ok(ValuationStatus::GeocodingFailed);
    }
    m.insee_code = Some(insee.clone());
    m.latitude = hit.latitude;
    m.longitude = hit.longitude;
    if m.postal_code.as_deref().is_none_or(|s| s.trim().is_empty()) {
        m.postal_code = hit.postcode.clone();
    }
    if m.city.as_deref().is_none_or(|s| s.trim().is_empty()) {
        m.city = hit.city.clone();
    }
    let now = state.clock.now();
    crate::db::query(
        "UPDATE real_estate_metadata SET \
           insee_code = $2, latitude = $3, longitude = $4, ban_id = $5, \
           geocode_score = $6, geocoded_at = $7, postal_code = $8, city = $9, updated_at = $7 \
         WHERE id = $1",
    )
    .bind(m.id)
    .bind(&m.insee_code)
    .bind(m.latitude)
    .bind(m.longitude)
    .bind(&hit.ban_id)
    .bind(hit.score)
    .bind(now)
    .bind(&m.postal_code)
    .bind(&m.city)
    .execute(conn)
    .await?;
    Ok(ValuationStatus::Ok)
}

fn build_address_query(m: &FullMetadata) -> String {
    let mut parts = Vec::new();
    for part in [&m.address, &m.postal_code, &m.city] {
        if let Some(s) = part.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
            parts.push(s);
        }
    }
    parts.join(" ")
}

fn to_input(m: &FullMetadata, kind: PropertyKind) -> ValuationInput {
    let department = m.insee_code.as_deref().and_then(|insee| {
        if insee.starts_with("97") && insee.len() >= 3 {
            Some(insee[..3].to_owned())
        } else if insee.len() >= 2 {
            Some(insee[..2].to_owned())
        } else {
            None
        }
    });
    ValuationInput {
        insee_code: m.insee_code.clone(),
        department_code: department,
        country_code: m.country.clone(),
        kind: Some(kind),
        living_area: m.surface_area,
        land_area: m.land_area,
        rooms: m.rooms,
        construction_year: m.construction_year,
        latitude: m.latitude,
        longitude: m.longitude,
    }
}

fn dec_num(d: Decimal) -> Value {
    serde_json::from_str(&d.to_string()).unwrap_or(Value::Null)
}

fn method_detail_json(
    raw: &ValuationResult,
    adjusted: &AdjustmentResult,
    reindex_ratio: Option<Decimal>,
) -> Option<String> {
    let detail = json!({
        "basePricePerSqm": dec_num(raw.price_per_sqm),
        "baseValue": dec_num(raw.estimated_value),
        "scale": raw.scale,
        "sourceYear": raw.source_year,
        "sampleSize": raw.sample_size,
        "multiplier": dec_num(adjusted.multiplier),
        "reindexRatio": reindex_ratio.map(dec_num),
        "adjustments": adjusted.applied,
    });
    serde_json::to_string(&detail).ok()
}
