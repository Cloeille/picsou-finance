//! AccountResponse and the DTOs it embeds, built as AccountService.toResponse builds them.

use std::collections::HashMap;

use crate::db::DbHandle;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::Serialize;

use super::jdecimal;
use super::scale::{column, column_opt};
use crate::db::{self, DbExec, DbFromRow};
use crate::error::AppResult;
use crate::ledger::model::{
    Account, AccountDisplayPref, AccountType, CardNature, Debt, RecurringCadence, ValuationMode,
};
use crate::ledger::prices::PriceRead;
use crate::ledger::{accounts, liabilities, valuation};

const FULL_SHARE: Decimal = Decimal::from_parts(100, 0, 0, false, 0);

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountResponse {
    pub id: i64,
    pub name: String,
    #[serde(rename = "type")]
    pub account_type: AccountType,
    pub provider: Option<String>,
    pub currency: String,
    #[serde(with = "jdecimal")]
    pub current_balance: Decimal,
    #[serde(with = "jdecimal")]
    pub current_balance_eur: Decimal,
    #[serde(with = "jdecimal::option")]
    pub cash_balance: Option<Decimal>,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub is_manual: bool,
    pub color: String,
    pub ticker: Option<String>,
    pub logo_url: Option<String>,
    pub logo_key: Option<String>,
    pub created_at: DateTime<Utc>,
    pub opened_at: Option<NaiveDate>,
    pub real_estate: Option<RealEstateMetadataResponse>,
    pub debt: Option<DebtResponse>,
    pub savings_config: Option<SavingsConfigDto>,
    #[serde(with = "jdecimal::option")]
    pub payment_due_amount: Option<Decimal>,
    pub payment_due_date: Option<NaiveDate>,
    pub reward_points: Option<i64>,
    pub parent_account_id: Option<i64>,
    pub hidden: bool,
    pub list_position: Option<i32>,
    pub list_collapsed: bool,
    #[serde(with = "jdecimal::option")]
    pub share_percent: Option<Decimal>,
    pub is_owner: Option<bool>,
    pub scpi: Option<ScpiPositionResponse>,
    pub card_nature: Option<CardNature>,
    pub contract: Option<AccountContractResponse>,
    pub institution: Option<InstitutionResponse>,
}

impl AccountResponse {
    /// AccountResponse.from: the row and its live EUR value, nothing embedded yet.
    pub fn from_account(a: &Account, balance_eur: Decimal) -> Self {
        Self {
            id: a.id,
            name: a.name.clone(),
            account_type: a.account_type,
            provider: a.provider.clone(),
            currency: a.currency.clone(),
            current_balance: a.current_balance,
            current_balance_eur: balance_eur,
            cash_balance: a.cash_balance,
            last_synced_at: a.last_synced_at,
            is_manual: a.is_manual,
            color: a.color.clone(),
            ticker: a.ticker.clone(),
            logo_url: a.logo_url.clone(),
            logo_key: a.logo_key.clone(),
            created_at: a.created_at,
            opened_at: a.opened_at,
            real_estate: None,
            debt: None,
            savings_config: None,
            payment_due_amount: a.payment_due_amount,
            payment_due_date: a.payment_due_date,
            reward_points: a.reward_points,
            parent_account_id: a.parent_account_id,
            hidden: a.hidden,
            list_position: None,
            list_collapsed: false,
            share_percent: None,
            is_owner: None,
            scpi: None,
            card_nature: a.card_nature,
            contract: AccountContractResponse::from_account(a),
            institution: InstitutionResponse::from_account(a),
        }
    }

    pub fn with_display_prefs(mut self, pref: Option<&AccountDisplayPref>) -> Self {
        self.list_position = pref.and_then(|p| p.list_position);
        self.list_collapsed = pref.is_some_and(|p| p.list_collapsed);
        self
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountContractResponse {
    #[serde(with = "jdecimal::option")]
    pub paid_in_eur: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub net_invested_eur: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub scheduled_payment_eur: Option<Decimal>,
    pub scheduled_payment_cadence: Option<RecurringCadence>,
}

impl AccountContractResponse {
    fn from_account(a: &Account) -> Option<Self> {
        if a.provider_paid_in_eur.is_none()
            && a.provider_invested_eur.is_none()
            && a.scheduled_payment_eur.is_none()
            && a.scheduled_payment_cadence.is_none()
        {
            return None;
        }
        Some(Self {
            paid_in_eur: a.provider_paid_in_eur,
            net_invested_eur: a.provider_invested_eur,
            scheduled_payment_eur: a.scheduled_payment_eur,
            scheduled_payment_cadence: a.scheduled_payment_cadence,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstitutionResponse {
    pub name: String,
    pub has_logo: bool,
}

impl InstitutionResponse {
    fn from_account(a: &Account) -> Option<Self> {
        let name = a
            .institution_name
            .as_deref()
            .filter(|n| !n.trim().is_empty())?;
        Some(Self {
            name: name.to_owned(),
            has_logo: a
                .institution_domain
                .as_deref()
                .is_some_and(|d| !d.trim().is_empty()),
        })
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DebtResponse {
    pub linked_account_id: Option<i64>,
    pub linked_account_name: Option<String>,
    #[serde(with = "jdecimal")]
    pub borrowed_amount: Decimal,
    #[serde(with = "jdecimal::option")]
    pub interest_rate: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub monthly_payment: Option<Decimal>,
    pub lender_name: Option<String>,
    pub start_date: Option<NaiveDate>,
    pub end_date: Option<NaiveDate>,
    #[serde(with = "jdecimal::option")]
    pub insurance_monthly: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub file_fees: Option<Decimal>,
}

impl DebtResponse {
    /// DebtResponse.from; the linked account is read as Hibernate would load it (live rows only).
    pub async fn from_debt(conn: &mut DbHandle<'_>, d: &Debt) -> AppResult<Self> {
        let linked = match d.linked_account_id {
            Some(id) => accounts::find_by_id(&mut *conn, id).await?,
            None => None,
        };
        Ok(Self::from_row(d, linked.as_ref()))
    }

    /// A debt row read back, its zeros at their column scale.
    pub fn from_row(d: &Debt, linked: Option<&Account>) -> Self {
        let mut r = Self::with_linked(d, linked);
        r.borrowed_amount = column(r.borrowed_amount, 8);
        r.interest_rate = column_opt(r.interest_rate, 4);
        r.monthly_payment = column_opt(r.monthly_payment, 8);
        r.insurance_monthly = column_opt(r.insurance_monthly, 8);
        r.file_fees = column_opt(r.file_fees, 8);
        r
    }

    pub fn with_linked(d: &Debt, linked: Option<&Account>) -> Self {
        Self {
            linked_account_id: linked.map(|a| a.id),
            linked_account_name: linked.map(|a| a.name.clone()),
            borrowed_amount: d.borrowed_amount,
            interest_rate: d.interest_rate,
            monthly_payment: d.monthly_payment,
            lender_name: d.lender_name.clone(),
            start_date: d.start_date,
            end_date: d.end_date,
            insurance_monthly: d.insurance_monthly,
            file_fees: d.file_fees,
        }
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize, DbFromRow)]
#[serde(rename_all = "camelCase")]
pub struct SavingsConfigDto {
    pub product: String,
    #[serde(with = "jdecimal")]
    pub annual_rate: Decimal,
    pub rate_basis: String,
    #[serde(with = "jdecimal::option")]
    pub tax_rate_pct: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub ceiling: Option<Decimal>,
}

/// A real_estate_metadata row.
#[derive(Debug, Clone, PartialEq, DbFromRow)]
pub struct RealEstateMetadata {
    pub id: i64,
    pub account_id: i64,
    pub member_id: i64,
    pub purchase_price: Decimal,
    pub purchase_date: Option<NaiveDate>,
    pub agency_fees: Option<Decimal>,
    pub notary_fees: Option<Decimal>,
    pub works_cost: Option<Decimal>,
    pub property_type: Option<String>,
    pub category: Option<String>,
    pub description: Option<String>,
    pub address: Option<String>,
    pub postal_code: Option<String>,
    pub city: Option<String>,
    pub country: String,
    pub insee_code: Option<String>,
    pub latitude: Option<Decimal>,
    pub longitude: Option<Decimal>,
    pub ban_id: Option<String>,
    pub geocode_score: Option<Decimal>,
    pub geocoded_at: Option<DateTime<Utc>>,
    pub surface_area: Option<Decimal>,
    pub land_area: Option<Decimal>,
    pub construction_year: Option<i16>,
    pub rooms: Option<i16>,
    pub bedrooms: Option<i16>,
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
    pub rental_income: Option<Decimal>,
}

pub const REAL_ESTATE_COLUMNS: &str = "id, account_id, member_id, purchase_price, purchase_date, agency_fees, \
    notary_fees, works_cost, property_type, category, description, address, postal_code, city, country, insee_code, \
    latitude, longitude, ban_id, geocode_score, geocoded_at, surface_area, land_area, construction_year, rooms, \
    bedrooms, bathrooms, floor_number, floors_total, has_elevator, garage_count, parking_count, has_garden, \
    has_terrace, has_balcony, energy_class, valuation_mode, rental_income";

impl RealEstateMetadata {
    /// RealEstateMetadata.costBasis: the purchase price plus whichever fees are set.
    pub fn cost_basis(&self) -> Decimal {
        [self.agency_fees, self.notary_fees, self.works_cost]
            .into_iter()
            .flatten()
            .fold(self.purchase_price, |total, fee| total + fee)
    }
}

/// PropertyKind.parse.
pub fn property_kind(raw: Option<&str>) -> Option<&'static str> {
    let v = raw?.trim().to_uppercase();
    Some(match v.as_str() {
        "HOUSE" | "MAISON" | "VILLA" => "HOUSE",
        "APARTMENT" | "APPARTEMENT" | "APPART" | "FLAT" | "STUDIO" => "APARTMENT",
        "BUILDING" | "IMMEUBLE" => "BUILDING",
        "LAND" | "TERRAIN" => "LAND",
        "PARKING" | "GARAGE" | "BOX" => "PARKING",
        "COMMERCIAL" | "COMMERCE" | "LOCAL_COMMERCIAL" | "BUREAU" | "OFFICE" => "COMMERCIAL",
        _ => return None,
    })
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RealEstateMetadataResponse {
    #[serde(with = "jdecimal")]
    pub purchase_price: Decimal,
    pub purchase_date: Option<NaiveDate>,
    #[serde(with = "jdecimal::option")]
    pub agency_fees: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub notary_fees: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub works_cost: Option<Decimal>,
    #[serde(with = "jdecimal")]
    pub cost_basis: Decimal,
    pub property_type: Option<String>,
    pub property_kind: Option<&'static str>,
    pub category: Option<String>,
    pub description: Option<String>,
    pub address: Option<String>,
    pub postal_code: Option<String>,
    pub city: Option<String>,
    pub country: String,
    pub insee_code: Option<String>,
    #[serde(with = "jdecimal::option")]
    pub latitude: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub longitude: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub geocode_score: Option<Decimal>,
    pub geocoded_at: Option<DateTime<Utc>>,
    #[serde(with = "jdecimal::option")]
    pub surface_area: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub land_area: Option<Decimal>,
    pub construction_year: Option<i16>,
    pub rooms: Option<i16>,
    pub bedrooms: Option<i16>,
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
    pub last_valued_at: Option<NaiveDate>,
    #[serde(with = "jdecimal::option")]
    pub rental_income: Option<Decimal>,
}

impl RealEstateMetadataResponse {
    pub fn from_row(m: &RealEstateMetadata, last_valued_at: Option<NaiveDate>) -> Self {
        Self {
            purchase_price: m.purchase_price,
            purchase_date: m.purchase_date,
            agency_fees: m.agency_fees,
            notary_fees: m.notary_fees,
            works_cost: m.works_cost,
            cost_basis: m.cost_basis(),
            property_type: m.property_type.clone(),
            property_kind: property_kind(m.property_type.as_deref()),
            category: m.category.clone(),
            description: m.description.clone(),
            address: m.address.clone(),
            postal_code: m.postal_code.clone(),
            city: m.city.clone(),
            country: m.country.clone(),
            insee_code: m.insee_code.clone(),
            latitude: m.latitude,
            longitude: m.longitude,
            geocode_score: m.geocode_score,
            geocoded_at: m.geocoded_at,
            surface_area: m.surface_area,
            land_area: m.land_area,
            construction_year: m.construction_year,
            rooms: m.rooms,
            bedrooms: m.bedrooms,
            bathrooms: m.bathrooms,
            floor_number: m.floor_number,
            floors_total: m.floors_total,
            has_elevator: m.has_elevator,
            garage_count: m.garage_count,
            parking_count: m.parking_count,
            has_garden: m.has_garden,
            has_terrace: m.has_terrace,
            has_balcony: m.has_balcony,
            energy_class: m.energy_class.clone(),
            valuation_mode: m.valuation_mode,
            last_valued_at,
            rental_income: m.rental_income,
        }
    }
}

/// A scpi_position row.
#[derive(Debug, Clone, PartialEq, DbFromRow)]
pub struct ScpiPosition {
    pub id: i64,
    pub account_id: i64,
    pub member_id: i64,
    pub isin: Option<String>,
    pub management_company: Option<String>,
    pub share_count: Decimal,
    pub subscription_price_eur: Option<Decimal>,
    pub withdrawal_price_eur: Option<Decimal>,
    pub dividend_policy: String,
    pub jouissance_date: Option<NaiveDate>,
    pub valuation_status: String,
    pub corum_fund_code: Option<String>,
    pub sofidy_fund_code: Option<String>,
    pub louve_fund_code: Option<String>,
    pub annual_distribution_per_share_eur: Option<Decimal>,
    pub distribution_frequency: Option<String>,
}

pub const SCPI_COLUMNS: &str = "id, account_id, member_id, isin, management_company, share_count, \
    subscription_price_eur, withdrawal_price_eur, dividend_policy, jouissance_date, valuation_status, \
    corum_fund_code, sofidy_fund_code, louve_fund_code, annual_distribution_per_share_eur, distribution_frequency";

pub async fn scpi_position_of(
    db: impl DbExec<'_>,
    account_id: i64,
    member_id: i64,
) -> sqlx::Result<Option<ScpiPosition>> {
    db::query_as(&format!(
        "SELECT {SCPI_COLUMNS} FROM scpi_position WHERE account_id = $1 AND member_id = $2"
    ))
    .bind(account_id)
    .bind(member_id)
    .fetch_optional(db)
    .await
    .map(|row| {
        row.map(|mut p: ScpiPosition| {
            p.share_count = column(p.share_count, 8);
            p.subscription_price_eur = column_opt(p.subscription_price_eur, 8);
            p.withdrawal_price_eur = column_opt(p.withdrawal_price_eur, 8);
            p.annual_distribution_per_share_eur =
                column_opt(p.annual_distribution_per_share_eur, 8);
            p
        })
    })
}

/// ScpiPositionService.withdrawalValue: shares × withdrawal price at 8 places, half up; no
/// shares is a known zero, a missing price an unknown value.
pub fn withdrawal_value(
    share_count: Option<Decimal>,
    withdrawal_price: Option<Decimal>,
) -> Option<Decimal> {
    let shares = share_count?;
    if shares.is_zero() {
        return Some(Decimal::new(0, 8));
    }
    let price = withdrawal_price?;
    Some(crate::ledger::decimal::set_scale(
        shares * price,
        8,
        rust_decimal::RoundingStrategy::MidpointAwayFromZero,
    ))
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScpiPositionResponse {
    pub isin: Option<String>,
    pub management_company: Option<String>,
    pub corum_fund_code: Option<String>,
    pub sofidy_fund_code: Option<String>,
    pub louve_fund_code: Option<String>,
    #[serde(with = "jdecimal")]
    pub share_count: Decimal,
    #[serde(with = "jdecimal::option")]
    pub subscription_price_eur: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub withdrawal_price_eur: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub withdrawal_value_eur: Option<Decimal>,
    pub dividend_policy: String,
    pub jouissance_date: Option<NaiveDate>,
    #[serde(with = "jdecimal::option")]
    pub annual_distribution_per_share_eur: Option<Decimal>,
    pub distribution_frequency: Option<String>,
    pub valuation_status: String,
}

impl ScpiPositionResponse {
    pub fn from_position(p: &ScpiPosition, withdrawal_value_eur: Option<Decimal>) -> Self {
        Self {
            isin: p.isin.clone(),
            management_company: p.management_company.clone(),
            corum_fund_code: p.corum_fund_code.clone(),
            sofidy_fund_code: p.sofidy_fund_code.clone(),
            louve_fund_code: p.louve_fund_code.clone(),
            share_count: p.share_count,
            subscription_price_eur: p.subscription_price_eur,
            withdrawal_price_eur: p.withdrawal_price_eur,
            withdrawal_value_eur,
            dividend_policy: p.dividend_policy.clone(),
            jouissance_date: p.jouissance_date,
            annual_distribution_per_share_eur: p.annual_distribution_per_share_eur,
            distribution_frequency: p.distribution_frequency.clone(),
            valuation_status: p.valuation_status.clone(),
        }
    }
}

pub async fn real_estate_of(
    db: impl DbExec<'_>,
    account_id: i64,
) -> sqlx::Result<Option<RealEstateMetadata>> {
    db::query_as(&format!(
        "SELECT {REAL_ESTATE_COLUMNS} FROM real_estate_metadata WHERE account_id = $1"
    ))
    .bind(account_id)
    .fetch_optional(db)
    .await
    .map(|row| {
        row.map(|mut m: RealEstateMetadata| {
            m.purchase_price = column(m.purchase_price, 8);
            m.agency_fees = column_opt(m.agency_fees, 8);
            m.notary_fees = column_opt(m.notary_fees, 8);
            m.works_cost = column_opt(m.works_cost, 8);
            m.rental_income = column_opt(m.rental_income, 8);
            m.surface_area = column_opt(m.surface_area, 2);
            m.land_area = column_opt(m.land_area, 2);
            m.latitude = column_opt(m.latitude, 6);
            m.longitude = column_opt(m.longitude, 6);
            m.geocode_score = column_opt(m.geocode_score, 3);
            m
        })
    })
}

/// When the property was last valued (property_valuation), or None.
pub async fn last_valued_at(
    db: impl DbExec<'_>,
    account_id: i64,
) -> sqlx::Result<Option<NaiveDate>> {
    db::query_scalar(
        "SELECT valued_at FROM property_valuation WHERE account_id = $1 ORDER BY valued_at DESC LIMIT 1",
    )
    .bind(account_id)
    .fetch_optional(db)
    .await
}

async fn savings_config_of(
    db: impl DbExec<'_>,
    account_id: i64,
) -> sqlx::Result<Option<SavingsConfigDto>> {
    db::query_as(
        "SELECT product::text AS product, annual_rate, rate_basis::text AS rate_basis, tax_rate_pct, ceiling \
         FROM savings_interest_config WHERE account_id = $1",
    )
    .bind(account_id)
    .fetch_optional(db)
    .await
    .map(|row| {
        row.map(|mut s: SavingsConfigDto| {
            s.annual_rate = column(s.annual_rate, 4);
            s.tax_rate_pct = column_opt(s.tax_rate_pct, 2);
            s.ceiling = column_opt(s.ceiling, 2);
            s
        })
    })
}

/// AccountService.toResponse(account, sharePercent, viewerId).
pub async fn to_response(
    conn: &mut DbHandle<'_>,
    prices: &dyn PriceRead,
    account: &Account,
    share_percent: Option<Decimal>,
    viewer_id: Option<i64>,
    today: NaiveDate,
) -> AppResult<AccountResponse> {
    let balance_eur = valuation::live_balance_eur(&mut *conn, prices, account, today).await?;
    let mut response = AccountResponse::from_account(account, balance_eur);
    response.share_percent = share_percent.filter(|s| *s != FULL_SHARE);
    response.is_owner = viewer_id.map(|viewer| viewer == account.member_id);
    if response.is_owner == Some(false)
        && let Some(institution) = response.institution.as_mut()
    {
        institution.has_logo = false;
    }
    match account.account_type {
        AccountType::RealEstate => {
            if let Some(meta) = real_estate_of(&mut *conn, account.id).await? {
                let valued = last_valued_at(&mut *conn, account.id).await?;
                response.real_estate = Some(RealEstateMetadataResponse::from_row(&meta, valued));
            }
        }
        AccountType::Scpi => {
            // The owner's row, not the viewer's: a co-owner still sees the position.
            if let Some(p) = scpi_position_of(&mut *conn, account.id, account.member_id).await? {
                let value = withdrawal_value(Some(p.share_count), p.withdrawal_price_eur);
                response.scpi = Some(ScpiPositionResponse::from_position(&p, value));
            }
        }
        AccountType::Loan => {
            if let Some(debt) = liabilities::debt_of_account(&mut *conn, account.id).await? {
                response.debt = Some(DebtResponse::from_debt(&mut *conn, &debt).await?);
            }
        }
        _ => {}
    }
    response.savings_config = savings_config_of(&mut *conn, account.id).await?;
    Ok(response)
}

/// The viewer's responses over readable accounts, with their shares and display prefs.
pub async fn responses_for_viewer(
    conn: &mut DbHandle<'_>,
    prices: &dyn PriceRead,
    accounts: &[Account],
    shares: &HashMap<i64, Decimal>,
    prefs: &HashMap<i64, AccountDisplayPref>,
    viewer_id: i64,
    today: NaiveDate,
) -> AppResult<Vec<AccountResponse>> {
    let mut out = Vec::with_capacity(accounts.len());
    for account in accounts {
        let response = to_response(
            &mut *conn,
            prices,
            account,
            shares.get(&account.id).copied(),
            Some(viewer_id),
            today,
        )
        .await?;
        out.push(response.with_display_prefs(prefs.get(&account.id)));
    }
    Ok(out)
}
