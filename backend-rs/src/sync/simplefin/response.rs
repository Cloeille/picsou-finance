//! The account read model the sync answers with. AccountService.toResponse belongs to the
//! accounts track; AccountResponses is the seam, and LedgerAccountResponses answers with
//! AccountResponse.from(account, liveBalanceEur), the part built from the account row alone.

use crate::db::DbHandle;
use async_trait::async_trait;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::Serialize;
use serde_json::Value;
use serde_with::skip_serializing_none;

use crate::ledger::model::{Account, AccountType, CardNature, RecurringCadence};
use crate::ledger::prices::PriceRead;
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::valuation;

/// AccountService.toResponse(account) with no viewer, for an account the sync just wrote.
#[async_trait]
pub trait AccountResponses: Send + Sync {
    async fn to_response(
        &self,
        conn: &mut DbHandle<'_>,
        prices: &dyn PriceRead,
        account: &Account,
        today: NaiveDate,
    ) -> sqlx::Result<Value>;
}

/// AccountResponse.from with the live EUR balance. The type-specific sections (real estate,
/// SCPI, debt, savings configuration) are the accounts track's to add.
pub struct LedgerAccountResponses;

#[async_trait]
impl AccountResponses for LedgerAccountResponses {
    async fn to_response(
        &self,
        conn: &mut DbHandle<'_>,
        prices: &dyn PriceRead,
        account: &Account,
        today: NaiveDate,
    ) -> sqlx::Result<Value> {
        let balance_eur = valuation::live_balance_eur(conn, prices, account, today).await?;
        Ok(
            serde_json::to_value(AccountResponse::from(account, balance_eur))
                .unwrap_or(Value::Null),
        )
    }
}

#[skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountResponse {
    pub id: i64,
    pub name: String,
    #[serde(rename = "type")]
    pub account_type: AccountType,
    pub provider: Option<String>,
    pub currency: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub current_balance: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub current_balance_eur: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub cash_balance: Option<Decimal>,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub is_manual: bool,
    pub color: String,
    pub ticker: Option<String>,
    pub logo_url: Option<String>,
    pub logo_key: Option<String>,
    pub created_at: DateTime<Utc>,
    pub opened_at: Option<NaiveDate>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub payment_due_amount: Option<Decimal>,
    pub payment_due_date: Option<NaiveDate>,
    pub reward_points: Option<i64>,
    pub parent_account_id: Option<i64>,
    pub hidden: bool,
    pub list_collapsed: bool,
    pub card_nature: Option<CardNature>,
    pub contract: Option<ContractResponse>,
    pub institution: Option<InstitutionResponse>,
}

#[skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ContractResponse {
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub paid_in_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub net_invested_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub scheduled_payment_eur: Option<Decimal>,
    pub scheduled_payment_cadence: Option<RecurringCadence>,
}

/// The bank behind an aggregator account; the domain itself is never exposed.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstitutionResponse {
    pub name: String,
    pub has_logo: bool,
}

impl AccountResponse {
    pub fn from(a: &Account, balance_eur: Decimal) -> Self {
        let contract = (a.provider_paid_in_eur.is_some()
            || a.provider_invested_eur.is_some()
            || a.scheduled_payment_eur.is_some()
            || a.scheduled_payment_cadence.is_some())
        .then_some(ContractResponse {
            paid_in_eur: a.provider_paid_in_eur,
            net_invested_eur: a.provider_invested_eur,
            scheduled_payment_eur: a.scheduled_payment_eur,
            scheduled_payment_cadence: a.scheduled_payment_cadence,
        });
        let institution = a
            .institution_name
            .as_ref()
            .filter(|name| !java_is_blank(name))
            .map(|name| InstitutionResponse {
                name: name.clone(),
                has_logo: a
                    .institution_domain
                    .as_deref()
                    .is_some_and(|d| !java_is_blank(d)),
            });
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
            payment_due_amount: a.payment_due_amount,
            payment_due_date: a.payment_due_date,
            reward_points: a.reward_points,
            parent_account_id: a.parent_account_id,
            hidden: a.hidden,
            list_collapsed: false,
            card_nature: a.card_nature,
            contract,
            institution,
        }
    }
}
