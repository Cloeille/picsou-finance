//! MeProfileController and MemberProfileService: the member's personal and fiscal profile.

use axum::extract::State;
use chrono::{Datelike, NaiveDate};
use rust_decimal::{Decimal, RoundingStrategy};
use serde::{Deserialize, Serialize};
use serde_with::skip_serializing_none;

use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::Errors;
use crate::identity::common::JsonBody;

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum HouseholdStatus {
    Single,
    Couple,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RiskProfile {
    Prudent,
    Balanced,
    Dynamic,
    Aggressive,
}

impl HouseholdStatus {
    fn as_str(self) -> &'static str {
        match self {
            Self::Single => "SINGLE",
            Self::Couple => "COUPLE",
        }
    }

    fn parse(raw: &str) -> Option<Self> {
        match raw {
            "SINGLE" => Some(Self::Single),
            "COUPLE" => Some(Self::Couple),
            _ => None,
        }
    }
}

impl RiskProfile {
    fn as_str(self) -> &'static str {
        match self {
            Self::Prudent => "PRUDENT",
            Self::Balanced => "BALANCED",
            Self::Dynamic => "DYNAMIC",
            Self::Aggressive => "AGGRESSIVE",
        }
    }

    fn parse(raw: &str) -> Option<Self> {
        match raw {
            "PRUDENT" => Some(Self::Prudent),
            "BALANCED" => Some(Self::Balanced),
            "DYNAMIC" => Some(Self::Dynamic),
            "AGGRESSIVE" => Some(Self::Aggressive),
            _ => None,
        }
    }
}

/// One profile, stored or requested: every field is optional and null clears it.
#[derive(Debug, Default, Deserialize, crate::db::DbFromRow)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    pub birth_date: Option<NaiveDate>,
    pub marginal_tax_rate: Option<Decimal>,
    #[sqlx(skip)]
    pub household_status: Option<HouseholdStatus>,
    pub tax_household_parts: Option<Decimal>,
    pub dependents: Option<i16>,
    pub annual_gross_income: Option<Decimal>,
    pub monthly_net_before_tax: Option<Decimal>,
    pub withholding_tax_rate: Option<Decimal>,
    pub monthly_savings_capacity: Option<Decimal>,
    pub target_retirement_age: Option<i16>,
    #[sqlx(skip)]
    pub risk_profile: Option<RiskProfile>,
}

#[skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileResponse {
    birth_date: Option<NaiveDate>,
    age: Option<i32>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    marginal_tax_rate: Option<Decimal>,
    household_status: Option<HouseholdStatus>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    tax_household_parts: Option<Decimal>,
    dependents: Option<i16>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    annual_gross_income: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    monthly_net_before_tax: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    withholding_tax_rate: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    monthly_net_income: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    monthly_savings_capacity: Option<Decimal>,
    target_retirement_age: Option<i16>,
    risk_profile: Option<RiskProfile>,
}

/// Period.between(birthDate, today).getYears().
fn age_on(birth: NaiveDate, today: NaiveDate) -> i32 {
    let mut years = today.year() - birth.year();
    if (today.month(), today.day()) < (birth.month(), birth.day()) {
        years -= 1;
    }
    years
}

/// netBeforeTax x (100 - withholding) / 100, scale 2 HALF_UP; null unless both are stated.
fn monthly_net_income(net: Option<Decimal>, withholding: Option<Decimal>) -> Option<Decimal> {
    let (net, rate) = (net?, withholding?);
    let hundred = Decimal::ONE_HUNDRED;
    let mut income = (net * (hundred - rate) / hundred)
        .round_dp_with_strategy(2, RoundingStrategy::MidpointAwayFromZero);
    income.rescale(2);
    Some(income)
}

fn respond(p: Profile, today: NaiveDate) -> ProfileResponse {
    ProfileResponse {
        birth_date: p.birth_date,
        age: p.birth_date.map(|b| age_on(b, today)),
        marginal_tax_rate: p.marginal_tax_rate,
        household_status: p.household_status,
        tax_household_parts: p.tax_household_parts,
        dependents: p.dependents,
        annual_gross_income: p.annual_gross_income,
        monthly_net_before_tax: p.monthly_net_before_tax,
        withholding_tax_rate: p.withholding_tax_rate,
        monthly_net_income: monthly_net_income(p.monthly_net_before_tax, p.withholding_tax_rate),
        monthly_savings_capacity: p.monthly_savings_capacity,
        target_retirement_age: p.target_retirement_age,
        risk_profile: p.risk_profile,
    }
}

/// The member's stored profile, or an empty one; reading never creates a row.
pub async fn profile_for(state: &AppState, member_id: i64) -> Result<Profile, sqlx::Error> {
    let row: Option<ProfileRow> = crate::db::query_as(
        "SELECT birth_date, marginal_tax_rate, household_status, tax_household_parts, dependents, \
         annual_gross_income, monthly_net_before_tax, withholding_tax_rate, monthly_savings_capacity, \
         target_retirement_age, risk_profile FROM member_profile WHERE member_id = $1",
    )
    .bind(member_id)
    .fetch_optional(&state.db)
    .await?;
    Ok(match row {
        Some(row) => {
            let mut profile = row.profile;
            profile.household_status = row
                .household_status
                .as_deref()
                .and_then(HouseholdStatus::parse);
            profile.risk_profile = row.risk_profile.as_deref().and_then(RiskProfile::parse);
            profile
        }
        None => Profile::default(),
    })
}

#[derive(crate::db::DbFromRow)]
struct ProfileRow {
    #[sqlx(flatten)]
    profile: Profile,
    household_status: Option<String>,
    risk_profile: Option<String>,
}

pub async fn get(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<ProfileResponse>> {
    let profile = profile_for(&state, auth.member_id).await?;
    Ok(axum::Json(respond(profile, state.clock.today())))
}

fn decimal_bounds(e: &mut Errors, field: &str, value: Option<Decimal>, min: i64, max: Option<i64>) {
    let Some(v) = value else { return };
    if v < Decimal::from(min) {
        let message = e.default_message(
            &format!("must be greater than or equal to {min}"),
            &format!("doit être supérieur ou égal à {min}"),
        );
        e.add(field, message);
    }
    if let Some(max) = max
        && v > Decimal::from(max)
    {
        let message = e.default_message(
            &format!("must be less than or equal to {max}"),
            &format!("doit être inférieur ou égal à {max}"),
        );
        e.add(field, message);
    }
}

fn integer_bounds(e: &mut Errors, field: &str, value: Option<i16>, min: i64, max: i64) {
    decimal_bounds(e, field, value.map(Decimal::from), min, Some(max));
}

impl Profile {
    fn validate(&self, today: NaiveDate, e: &mut Errors) {
        if self.birth_date.is_some_and(|d| d >= today) {
            let message = e.default_message("must be a past date", "doit être dans le passé");
            e.add("birthDate", message);
        }
        decimal_bounds(e, "marginalTaxRate", self.marginal_tax_rate, 0, Some(100));
        decimal_bounds(
            e,
            "taxHouseholdParts",
            self.tax_household_parts,
            1,
            Some(20),
        );
        integer_bounds(e, "dependents", self.dependents, 0, 20);
        decimal_bounds(e, "annualGrossIncome", self.annual_gross_income, 0, None);
        decimal_bounds(
            e,
            "monthlyNetBeforeTax",
            self.monthly_net_before_tax,
            0,
            None,
        );
        decimal_bounds(
            e,
            "withholdingTaxRate",
            self.withholding_tax_rate,
            0,
            Some(100),
        );
        decimal_bounds(
            e,
            "monthlySavingsCapacity",
            self.monthly_savings_capacity,
            0,
            None,
        );
        integer_bounds(e, "targetRetirementAge", self.target_retirement_age, 40, 90);
    }
}

pub async fn replace(
    State(state): State<AppState>,
    auth: Auth,
    body: JsonBody<Profile>,
) -> AppResult<axum::Json<ProfileResponse>> {
    let today = state.clock.today();
    let mut errors = body.errors();
    body.value.validate(today, &mut errors);
    errors.into_result()?;
    let p = body.value;
    let member_exists: bool =
        crate::db::query_scalar("SELECT EXISTS (SELECT 1 FROM family_member WHERE id = $1)")
            .bind(auth.member_id)
            .fetch_one(&state.db)
            .await?;
    if !member_exists {
        return Err(AppError::BadRequest("Unknown member".into()));
    }
    crate::db::query(
        "INSERT INTO member_profile (member_id, birth_date, marginal_tax_rate, household_status, tax_household_parts, \
         dependents, annual_gross_income, monthly_net_before_tax, withholding_tax_rate, monthly_savings_capacity, \
         target_retirement_age, risk_profile, created_at, updated_at) \
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $13) \
         ON CONFLICT (member_id) DO UPDATE SET birth_date = EXCLUDED.birth_date, \
         marginal_tax_rate = EXCLUDED.marginal_tax_rate, household_status = EXCLUDED.household_status, \
         tax_household_parts = EXCLUDED.tax_household_parts, dependents = EXCLUDED.dependents, \
         annual_gross_income = EXCLUDED.annual_gross_income, monthly_net_before_tax = EXCLUDED.monthly_net_before_tax, \
         withholding_tax_rate = EXCLUDED.withholding_tax_rate, monthly_savings_capacity = EXCLUDED.monthly_savings_capacity, \
         target_retirement_age = EXCLUDED.target_retirement_age, risk_profile = EXCLUDED.risk_profile, \
         updated_at = EXCLUDED.updated_at",
    )
    .bind(auth.member_id)
    .bind(p.birth_date)
    .bind(p.marginal_tax_rate)
    .bind(p.household_status.map(HouseholdStatus::as_str))
    .bind(p.tax_household_parts)
    .bind(p.dependents)
    .bind(p.annual_gross_income)
    .bind(p.monthly_net_before_tax)
    .bind(p.withholding_tax_rate)
    .bind(p.monthly_savings_capacity)
    .bind(p.target_retirement_age)
    .bind(p.risk_profile.map(RiskProfile::as_str))
    .bind(state.clock.now())
    .execute(&state.db)
    .await?;
    // The saved entity is returned as the request built it, so numbers keep the request's scale.
    Ok(axum::Json(respond(p, today)))
}
