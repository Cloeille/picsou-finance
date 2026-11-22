//! What the exchange sync needs from tracks still in flight, each with the behavior Java has
//! when the other side contributes nothing: AccountService.toResponse (T2) and
//! CryptoLogoService.getLogoUrls (T4).

use std::collections::{BTreeSet, HashMap};

use async_trait::async_trait;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::Serialize;
use serde_json::Value;
use serde_with::skip_serializing_none;

use crate::AppState;
use crate::db::{self, DbFromRow};
use crate::error::{AppError, AppResult};
use crate::ledger::model::{Account, CardNature, RecurringCadence};
use crate::ledger::valuation;

/// AccountService.toResponse(account), without a viewer: the account as AccountResponse JSON.
#[async_trait]
pub trait AccountResponder: Send + Sync {
    async fn account_response(&self, state: &AppState, account: &Account) -> AppResult<Value>;
}

/// CryptoLogoService.getLogoUrls: image URLs keyed by upper-case ticker; a ticker without one
/// is absent.
#[async_trait]
pub trait CryptoLogos: Send + Sync {
    async fn logo_urls(&self, tickers: &BTreeSet<String>) -> HashMap<String, String>;
}

/// No logo provider: every ticker is absent, which the client shows as the ticker itself.
pub struct NoCryptoLogos;

#[async_trait]
impl CryptoLogos for NoCryptoLogos {
    async fn logo_urls(&self, _tickers: &BTreeSet<String>) -> HashMap<String, String> {
        HashMap::new()
    }
}

/// AccountResponse.from(account, liveBalanceEur) plus the savings config, the only extras
/// toResponse adds to a CRYPTO account (real estate, SCPI and debt belong to other types).
pub struct LedgerAccountResponder;

#[skip_serializing_none]
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AccountResponse<'a> {
    id: i64,
    name: &'a str,
    #[serde(rename = "type")]
    account_type: &'static str,
    provider: Option<&'a str>,
    currency: &'a str,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    current_balance: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    current_balance_eur: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    cash_balance: Option<Decimal>,
    last_synced_at: Option<DateTime<Utc>>,
    is_manual: bool,
    color: &'a str,
    ticker: Option<&'a str>,
    logo_url: Option<&'a str>,
    logo_key: Option<&'a str>,
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
    contract: Option<Contract>,
    institution: Option<Institution<'a>>,
}

#[skip_serializing_none]
#[derive(Serialize, DbFromRow)]
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
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Contract {
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    paid_in_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    net_invested_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    scheduled_payment_eur: Option<Decimal>,
    scheduled_payment_cadence: Option<RecurringCadence>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Institution<'a> {
    name: &'a str,
    has_logo: bool,
}

fn non_blank(value: Option<&str>) -> Option<&str> {
    value.filter(|v| !crate::ledger::sync_values::java_is_blank(v))
}

#[async_trait]
impl AccountResponder for LedgerAccountResponder {
    async fn account_response(&self, state: &AppState, account: &Account) -> AppResult<Value> {
        let mut conn = state.db.acquire().await?;
        let balance_eur = valuation::live_balance_eur(
            &mut conn,
            &*state.ledger.prices,
            account,
            state.clock.today(),
        )
        .await?;
        let savings_config: Option<SavingsConfig> = db::query_as(
            "SELECT product::text AS product, annual_rate, rate_basis::text AS rate_basis, tax_rate_pct, ceiling \
             FROM savings_interest_config WHERE account_id = $1",
        )
        .bind(account.id)
        .fetch_optional(&mut conn)
        .await?;
        let contract = (account.provider_paid_in_eur.is_some()
            || account.provider_invested_eur.is_some()
            || account.scheduled_payment_eur.is_some()
            || account.scheduled_payment_cadence.is_some())
        .then_some(Contract {
            paid_in_eur: account.provider_paid_in_eur,
            net_invested_eur: account.provider_invested_eur,
            scheduled_payment_eur: account.scheduled_payment_eur,
            scheduled_payment_cadence: account.scheduled_payment_cadence,
        });
        let institution = non_blank(account.institution_name.as_deref()).map(|name| Institution {
            name,
            has_logo: non_blank(account.institution_domain.as_deref()).is_some(),
        });
        let response = AccountResponse {
            id: account.id,
            name: &account.name,
            account_type: account.account_type.as_str(),
            provider: account.provider.as_deref(),
            currency: &account.currency,
            current_balance: account.current_balance,
            current_balance_eur: balance_eur,
            cash_balance: account.cash_balance,
            last_synced_at: account.last_synced_at,
            is_manual: account.is_manual,
            color: &account.color,
            ticker: account.ticker.as_deref(),
            logo_url: account.logo_url.as_deref(),
            logo_key: account.logo_key.as_deref(),
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
        };
        serde_json::to_value(&response).map_err(AppError::internal)
    }
}
