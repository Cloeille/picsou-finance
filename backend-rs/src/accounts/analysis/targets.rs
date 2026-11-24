//! AllocationTargetService: the member's target allocation, or the shipped defaults, which are
//! never written on their behalf (no row means "never configured").

use axum::extract::State;
use rust_decimal::Decimal;
use rust_decimal::prelude::ToPrimitive;
use serde::{Deserialize, Deserializer, Serialize};

use crate::AppState;
use crate::auth::Auth;
use crate::db::{self, DbExec, DbFromRow};
use crate::error::AppResult;
use crate::http::json::{Errors, Json, Lang, Validate};
use crate::ledger::model::WealthTier;

/// A member_allocation_profile row, or the defaults.
#[derive(Debug, Clone, PartialEq, DbFromRow)]
pub struct AllocationProfile {
    pub monthly_essential_expenses: Option<Decimal>,
    pub safety_net_months: i16,
    pub real_estate_pct: Decimal,
    pub equity_pct: Decimal,
    pub crypto_pct: Decimal,
    pub alternative_pct: Decimal,
}

impl Default for AllocationProfile {
    fn default() -> Self {
        Self {
            monthly_essential_expenses: None,
            safety_net_months: 6,
            real_estate_pct: Decimal::new(3000, 2),
            equity_pct: Decimal::new(5000, 2),
            crypto_pct: Decimal::new(1000, 2),
            alternative_pct: Decimal::new(1000, 2),
        }
    }
}

impl AllocationProfile {
    /// The target of a tier; none for the safety net, whose target is an amount.
    pub fn target_for(&self, tier: WealthTier) -> Option<Decimal> {
        match tier {
            WealthTier::SafetyNet => None,
            WealthTier::RealEstate => Some(self.real_estate_pct),
            WealthTier::Equity => Some(self.equity_pct),
            WealthTier::Crypto => Some(self.crypto_pct),
            WealthTier::Alternative => Some(self.alternative_pct),
        }
    }
}

/// profileFor: the stored profile, or an unsaved one carrying the defaults.
pub async fn profile_for<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<AllocationProfile> {
    let row: Option<AllocationProfile> = db::query_as(
        "SELECT monthly_essential_expenses, safety_net_months, real_estate_pct, equity_pct, crypto_pct, \
         alternative_pct FROM member_allocation_profile WHERE member_id = $1",
    )
    .bind(member_id)
    .fetch_optional(db)
    .await?;
    Ok(row
        .map(|mut p| {
            use crate::accounts::scale::column as column_scale;
            p.monthly_essential_expenses = p.monthly_essential_expenses.map(|v| column_scale(v, 2));
            for pct in [
                &mut p.real_estate_pct,
                &mut p.equity_pct,
                &mut p.crypto_pct,
                &mut p.alternative_pct,
            ] {
                *pct = column_scale(*pct, 2);
            }
            p
        })
        .unwrap_or_default())
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AllocationTargetsResponse {
    #[serde(with = "crate::accounts::jdecimal::option")]
    monthly_essential_expenses: Option<Decimal>,
    safety_net_months: i16,
    #[serde(with = "crate::accounts::jdecimal")]
    real_estate_pct: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    equity_pct: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    crypto_pct: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    alternative_pct: Decimal,
}

impl From<AllocationProfile> for AllocationTargetsResponse {
    fn from(p: AllocationProfile) -> Self {
        Self {
            monthly_essential_expenses: p.monthly_essential_expenses,
            safety_net_months: p.safety_net_months,
            real_estate_pct: p.real_estate_pct,
            equity_pct: p.equity_pct,
            crypto_pct: p.crypto_pct,
            alternative_pct: p.alternative_pct,
        }
    }
}

/// Jackson's Short: a fractional number is truncated, one out of range is unreadable.
fn short<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<i16>, D::Error> {
    let value = Option::<Decimal>::deserialize(deserializer)?;
    value
        .map(|v| {
            v.trunc()
                .to_i16()
                .ok_or_else(|| serde::de::Error::custom("Numeric value out of range of short"))
        })
        .transpose()
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AllocationTargetsRequest {
    monthly_essential_expenses: Option<Decimal>,
    #[serde(default, deserialize_with = "short")]
    safety_net_months: Option<i16>,
    real_estate_pct: Option<Decimal>,
    equity_pct: Option<Decimal>,
    crypto_pct: Option<Decimal>,
    alternative_pct: Option<Decimal>,
}

fn at_least(lang: Lang, bound: i64) -> String {
    match lang {
        Lang::English => format!("must be greater than or equal to {bound}"),
        Lang::French => format!("doit être supérieur ou égal à {bound}"),
    }
}

fn at_most(lang: Lang, bound: i64) -> String {
    match lang {
        Lang::English => format!("must be less than or equal to {bound}"),
        Lang::French => format!("doit être inférieur ou égal à {bound}"),
    }
}

impl Validate for AllocationTargetsRequest {
    fn validate(&self, errors: &mut Errors) {
        let lang = errors.lang();
        if self
            .monthly_essential_expenses
            .is_some_and(|v| v < Decimal::ZERO)
        {
            errors.add("monthlyEssentialExpenses", at_least(lang, 0));
        }
        errors.not_null("safetyNetMonths", self.safety_net_months.as_ref());
        match self.safety_net_months {
            Some(m) if m < 1 => errors.add("safetyNetMonths", at_least(lang, 1)),
            Some(m) if m > 24 => errors.add("safetyNetMonths", at_most(lang, 24)),
            _ => {}
        }
        for (field, value) in [
            ("realEstatePct", self.real_estate_pct),
            ("equityPct", self.equity_pct),
            ("cryptoPct", self.crypto_pct),
            ("alternativePct", self.alternative_pct),
        ] {
            errors.not_null(field, value.as_ref());
            if value.is_some_and(|v| v < Decimal::ZERO) {
                errors.add(field, at_least(lang, 0));
            }
        }
        // Null components pass, so a half-filled form shows its @NotNull messages instead.
        if let (Some(a), Some(b), Some(c), Some(d)) = (
            self.real_estate_pct,
            self.equity_pct,
            self.crypto_pct,
            self.alternative_pct,
        ) && [a, b, c, d]
            .into_iter()
            .try_fold(Decimal::ZERO, Decimal::checked_add)
            != Some(Decimal::ONE_HUNDRED)
        {
            errors.add("summingToOneHundred", "allocation targets must sum to 100");
        }
    }
}

pub async fn get(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<AllocationTargetsResponse>> {
    Ok(axum::Json(
        profile_for(&state.db, auth.member_id).await?.into(),
    ))
}

/// Replaces the whole profile, creating the row on a member's first edit.
pub async fn replace(
    State(state): State<AppState>,
    auth: Auth,
    Json(request): Json<AllocationTargetsRequest>,
) -> AppResult<axum::Json<AllocationTargetsResponse>> {
    let saved = AllocationProfile {
        monthly_essential_expenses: request.monthly_essential_expenses,
        safety_net_months: request.safety_net_months.unwrap_or_default(),
        real_estate_pct: request.real_estate_pct.unwrap_or_default(),
        equity_pct: request.equity_pct.unwrap_or_default(),
        crypto_pct: request.crypto_pct.unwrap_or_default(),
        alternative_pct: request.alternative_pct.unwrap_or_default(),
    };
    let now = state.clock.now();
    db::query(
        "INSERT INTO member_allocation_profile (member_id, monthly_essential_expenses, safety_net_months, \
         real_estate_pct, equity_pct, crypto_pct, alternative_pct, created_at, updated_at) \
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $8) ON CONFLICT (member_id) DO UPDATE SET \
         monthly_essential_expenses = EXCLUDED.monthly_essential_expenses, \
         safety_net_months = EXCLUDED.safety_net_months, real_estate_pct = EXCLUDED.real_estate_pct, \
         equity_pct = EXCLUDED.equity_pct, crypto_pct = EXCLUDED.crypto_pct, \
         alternative_pct = EXCLUDED.alternative_pct, updated_at = EXCLUDED.updated_at",
    )
    .bind(auth.member_id)
    .bind(saved.monthly_essential_expenses)
    .bind(saved.safety_net_months)
    .bind(saved.real_estate_pct)
    .bind(saved.equity_pct)
    .bind(saved.crypto_pct)
    .bind(saved.alternative_pct)
    .bind(now)
    .execute(&state.db)
    .await?;
    // Java answers with the entity it set: the request's values and scale.
    Ok(axum::Json(saved.into()))
}
