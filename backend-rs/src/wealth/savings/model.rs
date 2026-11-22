//! SavingsProduct, RateBasis, RegulatedRates and SavingsInterestConfigRepository.

use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use crate::db::{DbExec, DbFromRow};

#[derive(
    Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, sqlx::Type, crate::db::DbEnum,
)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[sqlx(type_name = "savings_product", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SavingsProduct {
    LivretA,
    Ldds,
    Lep,
    Commercial,
}

impl SavingsProduct {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::LivretA => "LIVRET_A",
            Self::Ldds => "LDDS",
            Self::Lep => "LEP",
            Self::Commercial => "COMMERCIAL",
        }
    }

    pub fn is_regulated(self) -> bool {
        self != Self::Commercial
    }
}

#[derive(
    Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, sqlx::Type, crate::db::DbEnum,
)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[sqlx(type_name = "rate_basis", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RateBasis {
    Gross,
    Net,
}

/// The official rates and ceilings of the regulated books (service-public.fr, 2025-02 and 2026).
pub struct RegulatedRates;

impl RegulatedRates {
    pub const LIVRET_A: &str = "2.40";
    pub const LDDS: &str = "2.40";
    pub const LEP: &str = "3.50";
    pub const LIVRET_A_CEILING: &str = "22950.00";
    pub const LDDS_CEILING: &str = "12000.00";
    pub const LEP_CEILING: &str = "10000.00";
}

/// One savings_interest_config row, without its audit columns.
#[derive(Debug, Clone, PartialEq, DbFromRow)]
pub struct SavingsConfig {
    pub account_id: i64,
    pub product: SavingsProduct,
    pub annual_rate: Decimal,
    pub rate_basis: RateBasis,
    pub tax_rate_pct: Option<Decimal>,
    pub ceiling: Option<Decimal>,
}

pub async fn find_by_account(
    db: impl DbExec<'_>,
    account_id: i64,
) -> sqlx::Result<Option<SavingsConfig>> {
    crate::db::query_as::<SavingsConfig>(
        "SELECT account_id, product, annual_rate, rate_basis, tax_rate_pct, ceiling \
         FROM savings_interest_config WHERE account_id = $1",
    )
    .bind(account_id)
    .fetch_optional(db)
    .await
}

/// The account ids among ids that have a config.
pub async fn configured_accounts(db: impl DbExec<'_>, ids: &[i64]) -> sqlx::Result<Vec<i64>> {
    crate::db::query_scalar::<i64>(
        "SELECT account_id FROM savings_interest_config WHERE account_id = ANY($1)",
    )
    .bind(ids)
    .fetch_all(db)
    .await
}

pub async fn upsert(db: impl DbExec<'_>, config: &SavingsConfig) -> sqlx::Result<()> {
    crate::db::query(
        "INSERT INTO savings_interest_config (account_id, product, annual_rate, rate_basis, tax_rate_pct, ceiling) \
         VALUES ($1, $2, $3, $4, $5, $6) \
         ON CONFLICT (account_id) DO UPDATE SET product = EXCLUDED.product, annual_rate = EXCLUDED.annual_rate, \
         rate_basis = EXCLUDED.rate_basis, tax_rate_pct = EXCLUDED.tax_rate_pct, ceiling = EXCLUDED.ceiling, \
         updated_at = NOW()",
    )
    .bind(config.account_id)
    .bind(config.product)
    .bind(config.annual_rate)
    .bind(config.rate_basis)
    .bind(config.tax_rate_pct)
    .bind(config.ceiling)
    .execute(db)
    .await
    .map(drop)
}

/// insertIfAbsent: a concurrent or earlier configuration wins without raising. True when a
/// row was created.
pub async fn insert_if_absent(db: impl DbExec<'_>, config: &SavingsConfig) -> sqlx::Result<bool> {
    let result = crate::db::query(
        "INSERT INTO savings_interest_config (account_id, product, annual_rate, rate_basis, ceiling) \
         VALUES ($1, $2, $3, $4, $5) ON CONFLICT (account_id) DO NOTHING",
    )
    .bind(config.account_id)
    .bind(config.product)
    .bind(config.annual_rate)
    .bind(config.rate_basis)
    .bind(config.ceiling)
    .execute(db)
    .await?;
    Ok(result.rows_affected() > 0)
}

pub async fn delete_by_account(db: impl DbExec<'_>, account_id: i64) -> sqlx::Result<()> {
    crate::db::query("DELETE FROM savings_interest_config WHERE account_id = $1")
        .bind(account_id)
        .execute(db)
        .await
        .map(drop)
}
