//! AccountService.toResponse(account) for the account a wallet sync writes: no viewer, so no
//! share or ownership badge.

use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::Serialize;
use sqlx::PgConnection;

use crate::db::{self, DbFromRow};
use crate::error::AppResult;
use crate::ledger::model::{Account, AccountType, CardNature, RecurringCadence};
use crate::ledger::prices::PriceRead;
use crate::ledger::valuation;

#[serde_with::skip_serializing_none]
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
    pub savings_config: Option<SavingsConfig>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub payment_due_amount: Option<Decimal>,
    pub payment_due_date: Option<NaiveDate>,
    pub reward_points: Option<i64>,
    pub parent_account_id: Option<i64>,
    pub hidden: bool,
    pub list_position: Option<i32>,
    pub list_collapsed: bool,
    pub card_nature: Option<CardNature>,
    pub contract: Option<ContractResponse>,
    pub institution: Option<InstitutionResponse>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize, DbFromRow)]
#[serde(rename_all = "camelCase")]
pub struct SavingsConfig {
    pub product: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub annual_rate: Decimal,
    pub rate_basis: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub tax_rate_pct: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub ceiling: Option<Decimal>,
}

/// AccountContractResponse: what a provider reports about the contract.
#[serde_with::skip_serializing_none]
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

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstitutionResponse {
    pub name: String,
    pub has_logo: bool,
}

pub async fn build(
    conn: &mut PgConnection,
    prices: &dyn PriceRead,
    account: &Account,
    today: NaiveDate,
) -> AppResult<AccountResponse> {
    let balance_eur = valuation::live_balance_eur(&mut *conn, prices, account, today).await?;
    let savings_config: Option<SavingsConfig> = db::query_as(
        "SELECT product::text AS product, annual_rate, rate_basis::text AS rate_basis, tax_rate_pct, ceiling \
         FROM savings_interest_config WHERE account_id = $1",
    )
    .bind(account.id)
    .fetch_optional(&mut *conn)
    .await?;
    let contract = (account.provider_paid_in_eur.is_some()
        || account.provider_invested_eur.is_some()
        || account.scheduled_payment_eur.is_some()
        || account.scheduled_payment_cadence.is_some())
    .then_some(ContractResponse {
        paid_in_eur: account.provider_paid_in_eur,
        net_invested_eur: account.provider_invested_eur,
        scheduled_payment_eur: account.scheduled_payment_eur,
        scheduled_payment_cadence: account.scheduled_payment_cadence,
    });
    let institution = account
        .institution_name
        .as_deref()
        .filter(|name| !crate::ledger::sync_values::java_is_blank(name))
        .map(|name| InstitutionResponse {
            name: name.to_owned(),
            has_logo: account
                .institution_domain
                .as_deref()
                .is_some_and(|d| !crate::ledger::sync_values::java_is_blank(d)),
        });
    Ok(AccountResponse {
        id: account.id,
        name: account.name.clone(),
        account_type: account.account_type,
        provider: account.provider.clone(),
        currency: account.currency.clone(),
        current_balance: account.current_balance,
        current_balance_eur: balance_eur,
        cash_balance: account.cash_balance,
        last_synced_at: account.last_synced_at,
        is_manual: account.is_manual,
        color: account.color.clone(),
        ticker: account.ticker.clone(),
        logo_url: account.logo_url.clone(),
        logo_key: account.logo_key.clone(),
        created_at: account.created_at,
        opened_at: account.opened_at,
        savings_config,
        payment_due_amount: account.payment_due_amount,
        payment_due_date: account.payment_due_date,
        reward_points: account.reward_points,
        parent_account_id: account.parent_account_id,
        hidden: account.hidden,
        list_position: None,
        list_collapsed: false,
        card_nature: account.card_nature,
        contract,
        institution,
    })
}
