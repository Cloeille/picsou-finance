//! Cross-track seam: AccountService.toResponse (track T2 owns the account read model).

use crate::db::DbHandle;
use async_trait::async_trait;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::Serialize;
use serde_json::Value;

use crate::AppState;
use crate::db::{self, DbFromRow};
use crate::error::{AppError, AppResult};
use crate::ledger::model::{Account, AccountType, CardNature, RecurringCadence};
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::valuation;

/// The account as the account list shows it, for a caller with no viewer (toResponse(account)):
/// no share percent, no isOwner, no display preferences.
#[async_trait]
pub trait AccountView: Send + Sync {
    async fn to_response(
        &self,
        state: &AppState,
        conn: &mut DbHandle<'_>,
        account: &Account,
    ) -> AppResult<Value>;
}

/// AccountResponse.from(account, liveBalanceEur) with the savings configuration, which is what
/// toResponse gives a savings account. The real-estate, debt and SCPI decorations belong to the
/// account read model of track T2 and are not added here.
pub struct BaseAccountView;

#[async_trait]
impl AccountView for BaseAccountView {
    async fn to_response(
        &self,
        state: &AppState,
        conn: &mut DbHandle<'_>,
        account: &Account,
    ) -> AppResult<Value> {
        let current_balance_eur = valuation::live_balance_eur(
            &mut *conn,
            &*state.ledger.prices,
            account,
            state.clock.today(),
        )
        .await?;
        let savings_config = db::query_as::<SavingsConfig>(
            "SELECT product::text AS product, annual_rate, rate_basis::text AS rate_basis, tax_rate_pct, ceiling \
             FROM savings_interest_config WHERE account_id = $1",
        )
        .bind(account.id)
        .fetch_optional(&mut *conn)
        .await?;
        let response = AccountResponse::from(account, current_balance_eur, savings_config);
        serde_json::to_value(response).map_err(AppError::internal)
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AccountResponse {
    id: i64,
    name: String,
    #[serde(rename = "type")]
    account_type: AccountType,
    provider: Option<String>,
    currency: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    current_balance: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    current_balance_eur: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    cash_balance: Option<Decimal>,
    last_synced_at: Option<DateTime<Utc>>,
    is_manual: bool,
    color: String,
    ticker: Option<String>,
    logo_url: Option<String>,
    logo_key: Option<String>,
    created_at: DateTime<Utc>,
    opened_at: Option<NaiveDate>,
    savings_config: Option<SavingsConfig>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    payment_due_amount: Option<Decimal>,
    payment_due_date: Option<NaiveDate>,
    reward_points: Option<i64>,
    parent_account_id: Option<i64>,
    hidden: bool,
    list_collapsed: bool,
    card_nature: Option<CardNature>,
    contract: Option<AccountContract>,
    institution: Option<Institution>,
}

impl AccountResponse {
    fn from(
        account: &Account,
        current_balance_eur: Decimal,
        savings_config: Option<SavingsConfig>,
    ) -> Self {
        let contract = (account.provider_paid_in_eur.is_some()
            || account.provider_invested_eur.is_some()
            || account.scheduled_payment_eur.is_some()
            || account.scheduled_payment_cadence.is_some())
        .then_some(AccountContract {
            paid_in_eur: account.provider_paid_in_eur,
            net_invested_eur: account.provider_invested_eur,
            scheduled_payment_eur: account.scheduled_payment_eur,
            scheduled_payment_cadence: account.scheduled_payment_cadence,
        });
        let institution = account
            .institution_name
            .as_ref()
            .filter(|name| !java_is_blank(name))
            .map(|name| Institution {
                name: name.clone(),
                has_logo: account
                    .institution_domain
                    .as_deref()
                    .is_some_and(|domain| !java_is_blank(domain)),
            });
        Self {
            id: account.id,
            name: account.name.clone(),
            account_type: account.account_type,
            provider: account.provider.clone(),
            currency: account.currency.clone(),
            current_balance: account.current_balance,
            current_balance_eur,
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
            list_collapsed: false,
            card_nature: account.card_nature,
            contract,
            institution,
        }
    }
}

/// SavingsConfigDto.
#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize, DbFromRow)]
#[serde(rename_all = "camelCase")]
struct SavingsConfig {
    product: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    annual_rate: Decimal,
    rate_basis: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    tax_rate_pct: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    ceiling: Option<Decimal>,
}

/// AccountContractResponse.
#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AccountContract {
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    paid_in_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    net_invested_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    scheduled_payment_eur: Option<Decimal>,
    scheduled_payment_cadence: Option<RecurringCadence>,
}

/// InstitutionResponse.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Institution {
    name: String,
    has_logo: bool,
}
