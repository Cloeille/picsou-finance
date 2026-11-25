//! The per-type metadata of an account: real estate, SCPI position, loan terms and schedule,
//! and the property valuation refresh.

use std::sync::LazyLock;

use async_trait::async_trait;
use axum::extract::State;
use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use super::dto::{
    DebtResponse, REAL_ESTATE_COLUMNS, RealEstateMetadata, RealEstateMetadataResponse,
    ScpiPosition, ScpiPositionResponse, last_valued_at, real_estate_of, scpi_position_of,
    withdrawal_value,
};
use super::seams::PropertyValuer;
use super::validation::{Check, ValidJson, ValidateAt};
use crate::AppState;
use crate::auth::Auth;
use crate::db::{self, AsDbHandle, DbExec, DbHandle};
use crate::error::{AppError, AppResult};
use crate::http::json::Path;
use crate::ledger::model::{AccountType, Debt, ValuationMode};
use crate::ledger::{access, accounts, liabilities, loans};

static ENERGY_CLASS: LazyLock<regex::Regex> =
    LazyLock::new(|| regex::Regex::new("^[A-G]$").expect("regex"));

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RealEstateMetadataRequest {
    purchase_price: Option<Decimal>,
    purchase_date: Option<NaiveDate>,
    agency_fees: Option<Decimal>,
    notary_fees: Option<Decimal>,
    works_cost: Option<Decimal>,
    property_type: Option<String>,
    category: Option<PropertyCategory>,
    description: Option<String>,
    address: Option<String>,
    postal_code: Option<String>,
    city: Option<String>,
    country: Option<String>,
    surface_area: Option<Decimal>,
    land_area: Option<Decimal>,
    construction_year: Option<i16>,
    rooms: Option<i16>,
    bedrooms: Option<i16>,
    bathrooms: Option<i16>,
    floor_number: Option<i16>,
    floors_total: Option<i16>,
    has_elevator: Option<bool>,
    garage_count: Option<i16>,
    parking_count: Option<i16>,
    has_garden: Option<bool>,
    has_terrace: Option<bool>,
    has_balcony: Option<bool>,
    energy_class: Option<String>,
    valuation_mode: Option<ValuationMode>,
    rental_income: Option<Decimal>,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum PropertyCategory {
    PrimaryResidence,
    SecondaryResidence,
    Rental,
    Land,
    Other,
}

impl PropertyCategory {
    fn as_str(self) -> &'static str {
        match self {
            Self::PrimaryResidence => "PRIMARY_RESIDENCE",
            Self::SecondaryResidence => "SECONDARY_RESIDENCE",
            Self::Rental => "RENTAL",
            Self::Land => "LAND",
            Self::Other => "OTHER",
        }
    }
}

impl ValidateAt for RealEstateMetadataRequest {
    fn validate_at(&self, check: &mut Check<'_>) {
        check
            .errors
            .not_null("purchasePrice", self.purchase_price.as_ref())
            .size("propertyType", self.property_type.as_deref(), 0, 50)
            .size("description", self.description.as_deref(), 0, 5000)
            .size("address", self.address.as_deref(), 0, 500)
            .size("postalCode", self.postal_code.as_deref(), 0, 10)
            .size("city", self.city.as_deref(), 0, 120)
            .size("country", self.country.as_deref(), 2, 2)
            .pattern(
                "energyClass",
                self.energy_class.as_deref(),
                &ENERGY_CLASS,
                "must be a DPE class between A and G",
            );
        for (field, value) in [
            ("purchasePrice", self.purchase_price),
            ("agencyFees", self.agency_fees),
            ("notaryFees", self.notary_fees),
            ("worksCost", self.works_cost),
            ("surfaceArea", self.surface_area),
            ("landArea", self.land_area),
            ("rentalIncome", self.rental_income),
        ] {
            check.decimal_min(field, value, "0", true);
        }
        if let Some(purchase_price) = self.purchase_price
            && [self.agency_fees, self.notary_fees, self.works_cost]
                .into_iter()
                .flatten()
                .try_fold(purchase_price, Decimal::checked_add)
                .is_none()
        {
            let message = check.errors.default_message(
                "numeric value out of bounds",
                "valeur numérique hors limites",
            );
            check.errors.add("purchasePrice", message);
        }
        let int = |v: Option<i16>| v.map(i64::from);
        check
            .range("constructionYear", int(self.construction_year), 1000, 2200)
            .range("rooms", int(self.rooms), 0, 1000)
            .range("bedrooms", int(self.bedrooms), 0, 1000)
            .range("bathrooms", int(self.bathrooms), 0, 100)
            .range("floorNumber", int(self.floor_number), -10, 200)
            .range("floorsTotal", int(self.floors_total), 0, 200)
            .range("garageCount", int(self.garage_count), 0, 100)
            .range("parkingCount", int(self.parking_count), 0, 100);
    }
}

pub async fn update_real_estate(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    ValidJson(req): ValidJson<RealEstateMetadataRequest>,
) -> AppResult<axum::Json<RealEstateMetadataResponse>> {
    let mut tx = state.db.begin().await?;
    let mut account =
        super::scale::fixed(accounts::get_or_throw(&mut tx, id, auth.member_id).await?);
    let existing = real_estate_of(&mut tx, id).await?;
    let address_changed = existing.as_ref().is_none_or(|m| {
        m.address != req.address || m.postal_code != req.postal_code || m.city != req.city
    });
    let mut m = existing.unwrap_or_else(|| RealEstateMetadata {
        id: 0,
        account_id: id,
        member_id: account.member_id,
        purchase_price: Decimal::ZERO,
        purchase_date: None,
        agency_fees: None,
        notary_fees: None,
        works_cost: None,
        property_type: None,
        category: None,
        description: None,
        address: None,
        postal_code: None,
        city: None,
        country: "FR".to_owned(),
        insee_code: None,
        latitude: None,
        longitude: None,
        ban_id: None,
        geocode_score: None,
        geocoded_at: None,
        surface_area: None,
        land_area: None,
        construction_year: None,
        rooms: None,
        bedrooms: None,
        bathrooms: None,
        floor_number: None,
        floors_total: None,
        has_elevator: None,
        garage_count: 0,
        parking_count: 0,
        has_garden: false,
        has_terrace: false,
        has_balcony: false,
        energy_class: None,
        valuation_mode: ValuationMode::Estimated,
        rental_income: Some(Decimal::ZERO),
    });
    m.purchase_price = req.purchase_price.unwrap_or_default();
    m.purchase_date = req.purchase_date;
    m.agency_fees = req.agency_fees;
    m.notary_fees = req.notary_fees;
    m.works_cost = req.works_cost;
    m.property_type = req.property_type.clone();
    m.category = req.category.map(|c| c.as_str().to_owned());
    m.description = req.description.clone();
    m.address = req.address.clone();
    m.postal_code = req.postal_code.clone();
    m.city = req.city.clone();
    m.country = req
        .country
        .as_deref()
        .map_or_else(|| "FR".to_owned(), str::to_uppercase);
    if address_changed {
        // A cleared INSEE code is what makes the next valuation geocode the new address.
        m.insee_code = None;
        m.latitude = None;
        m.longitude = None;
        m.ban_id = None;
        m.geocode_score = None;
        m.geocoded_at = None;
    }
    m.surface_area = req.surface_area;
    m.land_area = req.land_area;
    m.construction_year = req.construction_year;
    m.rooms = req.rooms;
    m.bedrooms = req.bedrooms;
    m.bathrooms = req.bathrooms;
    m.floor_number = req.floor_number;
    m.floors_total = req.floors_total;
    m.has_elevator = req.has_elevator;
    m.garage_count = req.garage_count.unwrap_or(0);
    m.parking_count = req.parking_count.unwrap_or(0);
    m.has_garden = req.has_garden == Some(true);
    m.has_terrace = req.has_terrace == Some(true);
    m.has_balcony = req.has_balcony == Some(true);
    m.energy_class = req.energy_class.clone();
    m.valuation_mode = req.valuation_mode.unwrap_or(ValuationMode::Estimated);
    m.rental_income = Some(req.rental_income.unwrap_or(Decimal::ZERO));

    // A property described but never valued starts at what was paid for it.
    let cost_basis = m.cost_basis();
    let now = state.clock.now();
    if account.current_balance.is_zero() && cost_basis > Decimal::ZERO {
        account.current_balance = cost_basis;
        accounts::update(&mut tx, &account, now).await?;
    }
    save_real_estate(&mut tx, &m).await?;
    let valued = last_valued_at(&mut tx, id).await?;
    tx.commit().await?;
    Ok(axum::Json(RealEstateMetadataResponse::from_row(&m, valued)))
}

async fn save_real_estate(db: impl DbExec<'_>, m: &RealEstateMetadata) -> sqlx::Result<()> {
    db::query(
        "INSERT INTO real_estate_metadata (account_id, member_id, purchase_price, purchase_date, agency_fees, \
         notary_fees, works_cost, property_type, category, description, address, postal_code, city, country, \
         insee_code, latitude, longitude, ban_id, geocode_score, geocoded_at, surface_area, land_area, \
         construction_year, rooms, bedrooms, bathrooms, floor_number, floors_total, has_elevator, garage_count, \
         parking_count, has_garden, has_terrace, has_balcony, energy_class, valuation_mode, rental_income, \
         created_at, updated_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, \
         $17, $18, $19, $20, $21, $22, $23, $24, $25, $26, $27, $28, $29, $30, $31, $32, $33, $34, $35, $36, $37, \
         NOW(), NOW()) ON CONFLICT (account_id) DO UPDATE SET purchase_price = EXCLUDED.purchase_price, \
         purchase_date = EXCLUDED.purchase_date, agency_fees = EXCLUDED.agency_fees, \
         notary_fees = EXCLUDED.notary_fees, works_cost = EXCLUDED.works_cost, \
         property_type = EXCLUDED.property_type, category = EXCLUDED.category, \
         description = EXCLUDED.description, address = EXCLUDED.address, postal_code = EXCLUDED.postal_code, \
         city = EXCLUDED.city, country = EXCLUDED.country, insee_code = EXCLUDED.insee_code, \
         latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, ban_id = EXCLUDED.ban_id, \
         geocode_score = EXCLUDED.geocode_score, geocoded_at = EXCLUDED.geocoded_at, \
         surface_area = EXCLUDED.surface_area, land_area = EXCLUDED.land_area, \
         construction_year = EXCLUDED.construction_year, rooms = EXCLUDED.rooms, bedrooms = EXCLUDED.bedrooms, \
         bathrooms = EXCLUDED.bathrooms, floor_number = EXCLUDED.floor_number, \
         floors_total = EXCLUDED.floors_total, has_elevator = EXCLUDED.has_elevator, \
         garage_count = EXCLUDED.garage_count, parking_count = EXCLUDED.parking_count, \
         has_garden = EXCLUDED.has_garden, has_terrace = EXCLUDED.has_terrace, \
         has_balcony = EXCLUDED.has_balcony, energy_class = EXCLUDED.energy_class, \
         valuation_mode = EXCLUDED.valuation_mode, rental_income = EXCLUDED.rental_income, updated_at = NOW()",
    )
    .bind(m.account_id)
    .bind(m.member_id)
    .bind(m.purchase_price)
    .bind(m.purchase_date)
    .bind(m.agency_fees)
    .bind(m.notary_fees)
    .bind(m.works_cost)
    .bind(&m.property_type)
    .bind(&m.category)
    .bind(&m.description)
    .bind(&m.address)
    .bind(&m.postal_code)
    .bind(&m.city)
    .bind(&m.country)
    .bind(&m.insee_code)
    .bind(m.latitude)
    .bind(m.longitude)
    .bind(&m.ban_id)
    .bind(m.geocode_score)
    .bind(m.geocoded_at)
    .bind(m.surface_area)
    .bind(m.land_area)
    .bind(m.construction_year)
    .bind(m.rooms)
    .bind(m.bedrooms)
    .bind(m.bathrooms)
    .bind(m.floor_number)
    .bind(m.floors_total)
    .bind(m.has_elevator)
    .bind(m.garage_count)
    .bind(m.parking_count)
    .bind(m.has_garden)
    .bind(m.has_terrace)
    .bind(m.has_balcony)
    .bind(&m.energy_class)
    .bind(m.valuation_mode)
    .bind(m.rental_income)
    .execute(db)
    .await?;
    Ok(())
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DebtRequest {
    linked_account_id: Option<i64>,
    borrowed_amount: Option<Decimal>,
    interest_rate: Option<Decimal>,
    monthly_payment: Option<Decimal>,
    lender_name: Option<String>,
    start_date: Option<NaiveDate>,
    end_date: Option<NaiveDate>,
    insurance_monthly: Option<Decimal>,
    file_fees: Option<Decimal>,
}

impl ValidateAt for DebtRequest {
    fn validate_at(&self, check: &mut Check<'_>) {
        check
            .errors
            .not_null("borrowedAmount", self.borrowed_amount.as_ref())
            .size("lenderName", self.lender_name.as_deref(), 0, 100);
        check
            .decimal_min("borrowedAmount", self.borrowed_amount, "0", true)
            .decimal_min("interestRate", self.interest_rate, "0", true)
            .decimal_max("interestRate", self.interest_rate, "1")
            .decimal_min("monthlyPayment", self.monthly_payment, "0", true)
            .decimal_min("insuranceMonthly", self.insurance_monthly, "0", true)
            .decimal_min("fileFees", self.file_fees, "0", true);
    }
}

pub async fn update_debt(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    ValidJson(req): ValidJson<DebtRequest>,
) -> AppResult<axum::Json<DebtResponse>> {
    let mut tx = state.db.begin().await?;
    let account = super::scale::fixed(accounts::get_or_throw(&mut tx, id, auth.member_id).await?);
    let existing = liabilities::debt_of_account(&mut tx, id).await?;
    // Member-scoped like every lookup here: a request-supplied id never escapes the member.
    let linked = match req.linked_account_id {
        Some(linked_id) => Some(super::scale::fixed(
            accounts::get_or_throw(&mut tx, linked_id, auth.member_id).await?,
        )),
        None => None,
    };
    let debt = Debt {
        id: existing.as_ref().map_or(0, |d| d.id),
        member_id: existing.as_ref().map_or(account.member_id, |d| d.member_id),
        account_id: id,
        linked_account_id: linked.as_ref().map(|a| a.id),
        borrowed_amount: req.borrowed_amount.unwrap_or_default(),
        interest_rate: req.interest_rate,
        monthly_payment: req.monthly_payment,
        lender_name: req.lender_name.clone(),
        start_date: req.start_date,
        end_date: req.end_date,
        insurance_monthly: req.insurance_monthly,
        file_fees: req.file_fees,
    };
    loans::try_compute(&debt, state.clock.today())
        .map_err(|e| AppError::BadRequest(e.to_string()))?;
    db::query(
        "INSERT INTO debt (account_id, member_id, linked_account_id, borrowed_amount, interest_rate, \
         monthly_payment, lender_name, start_date, end_date, insurance_monthly, file_fees, created_at, updated_at) \
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, NOW(), NOW()) ON CONFLICT (account_id) DO UPDATE SET \
         linked_account_id = EXCLUDED.linked_account_id, borrowed_amount = EXCLUDED.borrowed_amount, \
         interest_rate = EXCLUDED.interest_rate, monthly_payment = EXCLUDED.monthly_payment, \
         lender_name = EXCLUDED.lender_name, start_date = EXCLUDED.start_date, end_date = EXCLUDED.end_date, \
         insurance_monthly = EXCLUDED.insurance_monthly, file_fees = EXCLUDED.file_fees, updated_at = NOW()",
    )
    .bind(debt.account_id)
    .bind(debt.member_id)
    .bind(debt.linked_account_id)
    .bind(debt.borrowed_amount)
    .bind(debt.interest_rate)
    .bind(debt.monthly_payment)
    .bind(&debt.lender_name)
    .bind(debt.start_date)
    .bind(debt.end_date)
    .bind(debt.insurance_monthly)
    .bind(debt.file_fees)
    .execute(&mut tx)
    .await?;
    tx.commit().await?;
    Ok(axum::Json(DebtResponse::with_linked(
        &debt,
        linked.as_ref(),
    )))
}

pub async fn loan_summary(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<loans::LoanScheduleResponse>> {
    let mut conn = state.db.pool_handle();
    let account =
        super::scale::fixed(access::require_readable(&mut conn, id, auth.member_id).await?);
    if account.account_type != AccountType::Loan {
        return Err(AppError::BadRequest("Account is not a loan".to_owned()));
    }
    let debt = liabilities::debt_of_account(&mut conn, id)
        .await?
        .ok_or_else(|| AppError::NotFound("Debt details not set for account".to_owned()))?;
    let share = access::share_for(&mut conn, &account, auth.member_id).await?;
    let schedule = loans::try_compute(&debt, state.clock.today())
        .map_err(|e| AppError::BadRequest(e.to_string()))?
        .weigh(share);
    Ok(axum::Json(schedule))
}

/// ScpiPositionRequest, read as its custom Jackson deserializer reads it: blank strings are null
/// and whether the two distribution fields were sent at all is kept.
#[derive(Debug, Clone, Default)]
pub struct ScpiPositionRequest {
    isin: Option<String>,
    management_company: Option<String>,
    corum_fund_code: Option<String>,
    sofidy_fund_code: Option<String>,
    louve_fund_code: Option<String>,
    share_count: Option<Decimal>,
    subscription_price_eur: Option<Decimal>,
    withdrawal_price_eur: Option<Decimal>,
    dividend_policy: Option<String>,
    jouissance_date: Option<NaiveDate>,
    annual_distribution_per_share_eur: Option<Decimal>,
    distribution_frequency: Option<String>,
    annual_distribution_specified: bool,
    distribution_frequency_specified: bool,
}

impl<'de> Deserialize<'de> for ScpiPositionRequest {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        use serde::de::Error;
        let node = serde_json::Value::deserialize(deserializer)?;
        let obj = node
            .as_object()
            .ok_or_else(|| D::Error::custom("an object"))?;
        let text = |field: &str| -> Option<String> {
            match obj.get(field) {
                None | Some(serde_json::Value::Null) => None,
                Some(serde_json::Value::String(s)) => Some(s.clone()),
                Some(other) => Some(other.to_string()),
            }
        };
        let filled = |field: &str| text(field).filter(|s| !s.trim().is_empty());
        let decimal = |field: &str| -> Result<Option<Decimal>, D::Error> {
            filled(field)
                .map(|s| {
                    s.parse::<Decimal>()
                        .or_else(|_| Decimal::from_scientific(&s))
                        .map_err(D::Error::custom)
                })
                .transpose()
        };
        let one_of = |field: &str, allowed: &[&str]| -> Result<Option<String>, D::Error> {
            match filled(field) {
                Some(v) if allowed.contains(&v.as_str()) => Ok(Some(v)),
                Some(v) => Err(D::Error::custom(format!("unknown {field} {v}"))),
                None => Ok(None),
            }
        };
        let jouissance_date = filled("jouissanceDate")
            .map(|s| NaiveDate::parse_from_str(&s, "%Y-%m-%d").map_err(D::Error::custom))
            .transpose()?;
        Ok(Self {
            isin: text("isin"),
            management_company: text("managementCompany"),
            corum_fund_code: text("corumFundCode"),
            sofidy_fund_code: text("sofidyFundCode"),
            louve_fund_code: text("louveFundCode"),
            share_count: decimal("shareCount")?,
            subscription_price_eur: decimal("subscriptionPriceEur")?,
            withdrawal_price_eur: decimal("withdrawalPriceEur")?,
            dividend_policy: one_of("dividendPolicy", &["CASH", "REINVEST"])?,
            jouissance_date,
            annual_distribution_per_share_eur: decimal("annualDistributionPerShareEur")?,
            distribution_frequency: one_of("distributionFrequency", &["MONTHLY", "QUARTERLY"])?,
            annual_distribution_specified: obj.contains_key("annualDistributionPerShareEur"),
            distribution_frequency_specified: obj.contains_key("distributionFrequency"),
        })
    }
}

impl ValidateAt for ScpiPositionRequest {
    fn validate_at(&self, check: &mut Check<'_>) {
        check
            .errors
            .size("isin", self.isin.as_deref(), 0, 12)
            .size(
                "managementCompany",
                self.management_company.as_deref(),
                0,
                100,
            )
            .size("corumFundCode", self.corum_fund_code.as_deref(), 0, 40)
            .size("sofidyFundCode", self.sofidy_fund_code.as_deref(), 0, 40)
            .size("louveFundCode", self.louve_fund_code.as_deref(), 0, 40)
            .not_null("shareCount", self.share_count.as_ref());
        check
            .decimal_min("shareCount", self.share_count, "0", true)
            .decimal_min(
                "subscriptionPriceEur",
                self.subscription_price_eur,
                "0",
                true,
            )
            .decimal_min("withdrawalPriceEur", self.withdrawal_price_eur, "0", true)
            .decimal_min(
                "annualDistributionPerShareEur",
                self.annual_distribution_per_share_eur,
                "0",
                true,
            )
            .digits(
                "annualDistributionPerShareEur",
                self.annual_distribution_per_share_eur,
                12,
                8,
            );
        if let (Some(shares), Some(price)) = (self.share_count, self.withdrawal_price_eur)
            && shares.checked_mul(price).is_none()
        {
            let message = check.errors.default_message(
                "numeric value out of bounds",
                "valeur numérique hors limites",
            );
            check.errors.add("withdrawalPriceEur", message);
        }
    }
}

fn blank_to_null(value: Option<&str>) -> Option<String> {
    value
        .filter(|v| !v.trim().is_empty())
        .map(|v| v.trim().to_owned())
}

/// ScpiFundService.tombstone / clearTombstone: a fund the member detached stays ignored, so a
/// later sync does not recreate its account.
async fn tombstone(
    db: impl DbExec<'_>,
    member_id: i64,
    provider: &str,
    code: Option<&str>,
) -> sqlx::Result<()> {
    let Some(code) = code.filter(|c| !c.trim().is_empty()) else {
        return Ok(());
    };
    db::query(
        "INSERT INTO scpi_provider_fund (member_id, provider, fund_code, ignored, created_at, updated_at) \
         VALUES ($1, $2, $3, TRUE, NOW(), NOW()) ON CONFLICT (member_id, provider, fund_code) \
         DO UPDATE SET ignored = TRUE, updated_at = NOW()",
    )
    .bind(member_id)
    .bind(provider)
    .bind(code)
    .execute(db)
    .await?;
    Ok(())
}

async fn clear_tombstone(
    db: impl DbExec<'_>,
    member_id: i64,
    provider: &str,
    code: &str,
) -> sqlx::Result<()> {
    if code.trim().is_empty() {
        return Ok(());
    }
    db::query(
        "UPDATE scpi_provider_fund SET ignored = FALSE, updated_at = NOW() \
         WHERE member_id = $1 AND provider = $2 AND fund_code = $3 AND ignored",
    )
    .bind(member_id)
    .bind(provider)
    .bind(code)
    .execute(db)
    .await?;
    Ok(())
}

/// Tombstones the funds of the position an account deletion orphans and clears its links.
pub async fn detach_scpi_funds(
    conn: &mut DbHandle<'_>,
    account_id: i64,
    member_id: i64,
) -> sqlx::Result<()> {
    let Some(p) = scpi_position_of(&mut *conn, account_id, member_id).await? else {
        return Ok(());
    };
    tombstone(&mut *conn, member_id, "CORUM", p.corum_fund_code.as_deref()).await?;
    tombstone(
        &mut *conn,
        member_id,
        "SOFIDY",
        p.sofidy_fund_code.as_deref(),
    )
    .await?;
    tombstone(&mut *conn, member_id, "LOUVE", p.louve_fund_code.as_deref()).await?;
    db::query(
        "UPDATE scpi_position SET corum_fund_code = NULL, sofidy_fund_code = NULL, louve_fund_code = NULL, \
         updated_at = NOW() WHERE id = $1",
    )
    .bind(p.id)
    .execute(conn)
    .await?;
    Ok(())
}

async fn relink(
    conn: &mut DbHandle<'_>,
    member_id: i64,
    provider: &str,
    previous: Option<&str>,
    next: Option<&str>,
) -> sqlx::Result<()> {
    if let Some(prev) = previous
        && Some(prev) != next
    {
        tombstone(&mut *conn, member_id, provider, Some(prev)).await?;
    }
    if let Some(next) = next {
        clear_tombstone(conn, member_id, provider, next).await?;
    }
    Ok(())
}

pub async fn update_scpi(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    ValidJson(req): ValidJson<ScpiPositionRequest>,
) -> AppResult<axum::Json<ScpiPositionResponse>> {
    let member_id = auth.member_id;
    let mut tx = state.db.begin().await?;
    let mut account = super::scale::fixed(accounts::get_or_throw(&mut tx, id, member_id).await?);
    if account.account_type != AccountType::Scpi {
        return Err(AppError::BadRequest(
            "Account is not a SCPI account".to_owned(),
        ));
    }
    account.currency = "EUR".to_owned();
    let mut p = scpi_position_of(&mut tx, id, member_id)
        .await?
        .unwrap_or(ScpiPosition {
            id: 0,
            account_id: id,
            member_id: account.member_id,
            isin: None,
            management_company: None,
            share_count: Decimal::ZERO,
            subscription_price_eur: None,
            withdrawal_price_eur: None,
            dividend_policy: "CASH".to_owned(),
            jouissance_date: None,
            valuation_status: "PRICE_INCOMPLETE".to_owned(),
            corum_fund_code: None,
            sofidy_fund_code: None,
            louve_fund_code: None,
            annual_distribution_per_share_eur: None,
            distribution_frequency: None,
        });
    p.isin = blank_to_null(req.isin.as_deref());
    p.management_company = blank_to_null(req.management_company.as_deref());
    for (provider, requested, slot) in [
        ("CORUM", &req.corum_fund_code, &mut p.corum_fund_code),
        ("SOFIDY", &req.sofidy_fund_code, &mut p.sofidy_fund_code),
        ("LOUVE", &req.louve_fund_code, &mut p.louve_fund_code),
    ] {
        if let Some(requested) = requested {
            let next = blank_to_null(Some(requested));
            relink(
                &mut tx.as_handle(),
                member_id,
                provider,
                slot.as_deref(),
                next.as_deref(),
            )
            .await?;
            *slot = next;
        }
    }
    p.share_count = req.share_count.unwrap_or_default();
    p.subscription_price_eur = req.subscription_price_eur;
    p.withdrawal_price_eur = req.withdrawal_price_eur;
    p.dividend_policy = req
        .dividend_policy
        .clone()
        .unwrap_or_else(|| "CASH".to_owned());
    p.jouissance_date = req.jouissance_date;
    if req.annual_distribution_specified || req.annual_distribution_per_share_eur.is_some() {
        p.annual_distribution_per_share_eur = req.annual_distribution_per_share_eur;
    }
    if req.distribution_frequency_specified || req.distribution_frequency.is_some() {
        p.distribution_frequency = req.distribution_frequency.clone();
    }
    let value = withdrawal_value(req.share_count, req.withdrawal_price_eur);
    match value {
        // A missing withdrawal price leaves the balance alone; the subscription price is no substitute.
        None => p.valuation_status = "PRICE_INCOMPLETE".to_owned(),
        Some(v) => {
            p.valuation_status = "OK".to_owned();
            account.current_balance = v;
            account.cash_balance = Some(Decimal::ZERO);
        }
    }
    save_scpi(&mut tx, &p).await?;
    accounts::update(&mut tx, &account, state.clock.now()).await?;
    tx.commit().await?;
    Ok(axum::Json(ScpiPositionResponse::from_position(&p, value)))
}

async fn save_scpi(db: impl DbExec<'_>, p: &ScpiPosition) -> sqlx::Result<()> {
    db::query(
        "INSERT INTO scpi_position (account_id, member_id, isin, management_company, share_count, \
         subscription_price_eur, withdrawal_price_eur, dividend_policy, jouissance_date, valuation_status, \
         corum_fund_code, sofidy_fund_code, louve_fund_code, annual_distribution_per_share_eur, \
         distribution_frequency, created_at, updated_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, \
         $13, $14, $15, NOW(), NOW()) ON CONFLICT (account_id) DO UPDATE SET isin = EXCLUDED.isin, \
         management_company = EXCLUDED.management_company, share_count = EXCLUDED.share_count, \
         subscription_price_eur = EXCLUDED.subscription_price_eur, \
         withdrawal_price_eur = EXCLUDED.withdrawal_price_eur, dividend_policy = EXCLUDED.dividend_policy, \
         jouissance_date = EXCLUDED.jouissance_date, valuation_status = EXCLUDED.valuation_status, \
         corum_fund_code = EXCLUDED.corum_fund_code, sofidy_fund_code = EXCLUDED.sofidy_fund_code, \
         louve_fund_code = EXCLUDED.louve_fund_code, \
         annual_distribution_per_share_eur = EXCLUDED.annual_distribution_per_share_eur, \
         distribution_frequency = EXCLUDED.distribution_frequency, updated_at = NOW()",
    )
    .bind(p.account_id)
    .bind(p.member_id)
    .bind(&p.isin)
    .bind(&p.management_company)
    .bind(p.share_count)
    .bind(p.subscription_price_eur)
    .bind(p.withdrawal_price_eur)
    .bind(&p.dividend_policy)
    .bind(p.jouissance_date)
    .bind(&p.valuation_status)
    .bind(&p.corum_fund_code)
    .bind(&p.sofidy_fund_code)
    .bind(&p.louve_fund_code)
    .bind(p.annual_distribution_per_share_eur)
    .bind(&p.distribution_frequency)
    .execute(db)
    .await?;
    Ok(())
}

pub async fn refresh_valuation(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<serde_json::Value>> {
    {
        let mut conn = state.db.pool_handle();
        let account =
            super::scale::fixed(access::require_owner(&mut conn, id, auth.member_id).await?);
        if account.account_type != AccountType::RealEstate {
            return Err(AppError::BadRequest(
                "Account is not a real estate account".to_owned(),
            ));
        }
        if real_estate_of(&mut conn, id).await?.is_none() {
            return Err(AppError::internal("Property has no metadata yet"));
        }
    }
    let valuer = state.accounts.property_valuation.clone();
    Ok(axum::Json(
        valuer.estimate(&state, id, auth.member_id).await?,
    ))
}

/// PropertyValuationResponse.failed.
#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct FailedValuation {
    status: &'static str,
    mode: ValuationMode,
    applied_to_balance: bool,
    adjustments: Vec<serde_json::Value>,
}

/// app.valuation.enabled=false: PROVIDER_UNAVAILABLE, after seeding an unvalued property from
/// its cost basis (withCostBasisFloor). The default until track T4 wires the estimator.
pub struct ValuationDisabled;

#[async_trait]
impl PropertyValuer for ValuationDisabled {
    async fn estimate(
        &self,
        state: &AppState,
        account_id: i64,
        member_id: i64,
    ) -> AppResult<serde_json::Value> {
        let mut tx = state.db.begin().await?;
        let mut account = super::scale::fixed(
            access::require_owner(&mut tx.as_handle(), account_id, member_id).await?,
        );
        let meta = db::query_as::<RealEstateMetadata>(&format!(
            "SELECT {REAL_ESTATE_COLUMNS} FROM real_estate_metadata WHERE account_id = $1"
        ))
        .bind(account_id)
        .fetch_optional(&mut tx)
        .await?
        .ok_or_else(|| AppError::internal("Property has no metadata yet"))?;
        let cost_basis = meta.cost_basis();
        if account.current_balance.is_zero() && cost_basis > Decimal::ZERO {
            account.current_balance = cost_basis;
            accounts::update(&mut tx, &account, state.clock.now()).await?;
        }
        tx.commit().await?;
        serde_json::to_value(FailedValuation {
            status: "PROVIDER_UNAVAILABLE",
            mode: meta.valuation_mode,
            applied_to_balance: false,
            adjustments: Vec::new(),
        })
        .map_err(AppError::internal)
    }
}
