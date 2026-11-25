//! The cross-track seams of the Enable Banking sync, held by AppState as state.sync.enable_banking.
//! Each default is what Java does when the other side has nothing to add.

use std::sync::{Arc, Mutex};

use crate::db::DbHandle;
use chrono::{DateTime, NaiveDate, Utc};
use rsa::RsaPrivateKey;
use rust_decimal::Decimal;
use serde::Serialize;

use crate::AppState;
use crate::error::AppResult;
use crate::ledger::model::{Account, AccountType, CardNature, RecurringCadence};
use crate::ledger::valuation;
use crate::setup_auth::enable_banking::EnableBankingSettings;

/// EnableBankingConfigProvider: the connection settings (track T1 owns the admin settings).
#[async_trait::async_trait]
pub trait ConfigSource: Send + Sync {
    async fn application_id(&self, state: &AppState) -> AppResult<Option<String>>;
    /// The JWT kid; the application id when no key id is configured.
    async fn key_id(&self, state: &AppState) -> AppResult<Option<String>>;
    async fn redirect_uri(&self, state: &AppState) -> AppResult<Option<String>>;
    fn private_key(&self, state: &AppState) -> AppResult<Option<RsaPrivateKey>>;
}

/// app_setting first, the ENABLEBANKING_* environment second, the key file before the inline PEM.
pub struct AppSettingConfig;

#[async_trait::async_trait]
impl ConfigSource for AppSettingConfig {
    async fn application_id(&self, state: &AppState) -> AppResult<Option<String>> {
        EnableBankingSettings::new(state).application_id().await
    }

    async fn key_id(&self, state: &AppState) -> AppResult<Option<String>> {
        EnableBankingSettings::new(state).key_id().await
    }

    async fn redirect_uri(&self, state: &AppState) -> AppResult<Option<String>> {
        EnableBankingSettings::new(state).redirect_uri().await
    }

    fn private_key(&self, state: &AppState) -> AppResult<Option<RsaPrivateKey>> {
        EnableBankingSettings::new(state).private_key()
    }
}

/// AccountService.toResponse(account) for a synced account (track T2 owns the account read model).
#[async_trait::async_trait]
pub trait AccountResponses: Send + Sync {
    async fn to_response(
        &self,
        state: &AppState,
        conn: &mut DbHandle<'_>,
        account: &Account,
    ) -> AppResult<serde_json::Value>;
}

/// AccountResponse.from(account, liveBalanceEur) with no viewer: what toResponse returns for an
/// account without real-estate, SCPI, debt or savings rows attached.
pub struct BaseAccountResponse;

#[serde_with::skip_serializing_none]
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AccountView<'a> {
    id: i64,
    name: &'a str,
    #[serde(rename = "type")]
    account_type: AccountType,
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
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    payment_due_amount: Option<Decimal>,
    payment_due_date: Option<NaiveDate>,
    reward_points: Option<i64>,
    parent_account_id: Option<i64>,
    hidden: bool,
    list_collapsed: bool,
    card_nature: Option<CardNature>,
    contract: Option<ContractView>,
    institution: Option<InstitutionView<'a>>,
}

#[serde_with::skip_serializing_none]
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ContractView {
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
struct InstitutionView<'a> {
    name: &'a str,
    has_logo: bool,
}

#[async_trait::async_trait]
impl AccountResponses for BaseAccountResponse {
    async fn to_response(
        &self,
        state: &AppState,
        conn: &mut DbHandle<'_>,
        a: &Account,
    ) -> AppResult<serde_json::Value> {
        let balance_eur =
            valuation::live_balance_eur(conn, &*state.ledger.prices, a, state.clock.today())
                .await?;
        let contract = (a.provider_paid_in_eur.is_some()
            || a.provider_invested_eur.is_some()
            || a.scheduled_payment_eur.is_some()
            || a.scheduled_payment_cadence.is_some())
        .then_some(ContractView {
            paid_in_eur: a.provider_paid_in_eur,
            net_invested_eur: a.provider_invested_eur,
            scheduled_payment_eur: a.scheduled_payment_eur,
            scheduled_payment_cadence: a.scheduled_payment_cadence,
        });
        let institution = a
            .institution_name
            .as_deref()
            .filter(|n| !n.trim().is_empty())
            .map(|name| InstitutionView {
                name,
                has_logo: a
                    .institution_domain
                    .as_deref()
                    .is_some_and(|d| !d.trim().is_empty()),
            });
        let view = AccountView {
            id: a.id,
            name: &a.name,
            account_type: a.account_type,
            provider: a.provider.as_deref(),
            currency: &a.currency,
            current_balance: a.current_balance,
            current_balance_eur: balance_eur,
            cash_balance: a.cash_balance,
            last_synced_at: a.last_synced_at,
            is_manual: a.is_manual,
            color: &a.color,
            ticker: a.ticker.as_deref(),
            logo_url: a.logo_url.as_deref(),
            logo_key: a.logo_key.as_deref(),
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
        };
        serde_json::to_value(view).map_err(crate::error::AppError::internal)
    }
}

/// RecurringDetectionService.detect, run after a sync (track T3 owns the budget).
#[async_trait::async_trait]
pub trait RecurringDetection: Send + Sync {
    async fn detect(&self, state: &AppState, member_id: i64, today: NaiveDate) -> AppResult<()>;
}

/// No recurring detection: nothing to refresh.
pub struct NoRecurringDetection;

#[async_trait::async_trait]
impl RecurringDetection for NoRecurringDetection {
    async fn detect(&self, _state: &AppState, _member_id: i64, _today: NaiveDate) -> AppResult<()> {
        Ok(())
    }
}

pub struct Hooks {
    pub config: Arc<dyn ConfigSource>,
    pub accounts: Arc<dyn AccountResponses>,
    pub recurring: Arc<dyn RecurringDetection>,
    /// The connector's countries cache: the last non-empty list and when it was fetched.
    pub(super) countries: Mutex<Option<(Vec<String>, DateTime<Utc>)>>,
}

impl Hooks {
    pub fn new() -> Self {
        Self {
            config: Arc::new(AppSettingConfig),
            accounts: Arc::new(BaseAccountResponse),
            recurring: Arc::new(NoRecurringDetection),
            countries: Mutex::new(None),
        }
    }
}

impl Default for Hooks {
    fn default() -> Self {
        Self::new()
    }
}
