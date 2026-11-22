//! The JSON bodies of the IBKR endpoints: IbkrConnectionStatusResponse, and the
//! AccountResponse of a synced account as AccountResponse.from builds it.

use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::Serialize;
use serde_json::Value;
use serde_with::skip_serializing_none;
use sqlx::PgConnection;

use crate::db::{self, DbFromRow};
use crate::error::{AppError, AppResult};
use crate::ledger::model::{Account, AccountType, CardNature, RecurringCadence};
use crate::ledger::prices::PriceRead;
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::valuation;

#[skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct IbkrConnectionStatusResponse {
    pub connected: bool,
    pub connection_id: Option<i64>,
    pub status: Option<String>,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub masked_token: Option<String>,
}

#[skip_serializing_none]
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

#[skip_serializing_none]
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

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Institution {
    name: String,
    has_logo: bool,
}

#[skip_serializing_none]
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
    list_position: Option<i32>,
    list_collapsed: bool,
    card_nature: Option<CardNature>,
    contract: Option<AccountContract>,
    institution: Option<Institution>,
}

/// AccountService.toResponse(account) without a viewer, for an account of the member's own.
pub async fn account_response(
    conn: &mut PgConnection,
    prices: &dyn PriceRead,
    account: &Account,
    today: NaiveDate,
) -> AppResult<Value> {
    let balance_eur = valuation::live_balance_eur(&mut *conn, prices, account, today).await?;
    let savings_config: Option<SavingsConfig> = db::query_as(
        "SELECT product::text AS product, annual_rate, rate_basis::text AS rate_basis, tax_rate_pct, \
         ceiling FROM savings_interest_config WHERE account_id = $1",
    )
    .bind(account.id)
    .fetch_optional(&mut *conn)
    .await?;
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
        .as_deref()
        .filter(|name| !java_is_blank(name))
        .map(|name| Institution {
            name: name.to_owned(),
            has_logo: account
                .institution_domain
                .as_deref()
                .is_some_and(|domain| !java_is_blank(domain)),
        });
    let response = AccountResponse {
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
    };
    serde_json::to_value(response).map_err(AppError::internal)
}
