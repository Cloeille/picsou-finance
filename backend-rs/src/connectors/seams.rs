//! The narrow calls the connectors make into services other tracks own. Each default is what
//! Java does when that side has nothing; the coordinator swaps in the real implementation.

use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::fmt;
use std::sync::Arc;

use async_trait::async_trait;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::Serialize;
use serde_json::Value;
use sqlx::{PgConnection, PgPool};

use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::ledger::model::{Account, AccountType, CardNature};
use crate::ledger::valuation;

use crate::ledger::instrument::{TickerResult, is_isin};
use crate::ledger::sync_values::clean;

/// OpenFigiIsinConverter.resolve (track T4): an ISIN to a quotable ticker and display name.
#[async_trait]
pub trait IsinResolver: Send + Sync {
    async fn resolve(&self, isin: &str) -> TickerResult;

    /// resolveIsinOrSymbol: the ISIN wins, the broker's symbol is the fallback, and the broker's
    /// label is kept unless the lookup knows a name.
    async fn resolve_isin_or_symbol(
        &self,
        isin: Option<&str>,
        symbol: Option<&str>,
        provider_name: Option<&str>,
    ) -> (Option<String>, Option<String>) {
        match isin.and_then(|i| clean(Some(i))) {
            None => (symbol.map(str::to_owned), provider_name.map(str::to_owned)),
            Some(isin) => {
                let resolved = self.resolve(&isin).await;
                (
                    Some(resolved.ticker),
                    resolved.name.or_else(|| provider_name.map(str::to_owned)),
                )
            }
        }
    }
}

/// What OpenFIGI answers when it cannot be reached: the ISIN itself, normalized, and no name.
#[derive(Debug, Default, Clone, Copy)]
pub struct UnresolvedIsins;

#[async_trait]
impl IsinResolver for UnresolvedIsins {
    async fn resolve(&self, isin: &str) -> TickerResult {
        TickerResult {
            ticker: isin.trim().to_uppercase(),
            name: None,
        }
    }
}

/// SecurityIdentityService.record (track T2): remembers which ISIN a ticker came from.
#[async_trait]
pub trait SecurityIdentities: Send + Sync {
    /// Never fails the caller: reference data the next sync supplies again.
    async fn record(&self, isin_by_ticker: &BTreeMap<String, String>, now: DateTime<Utc>);
}

/// SecurityIdentityService's own write on security_profile: a new row seeded with its identity
/// only, or the ISIN filled in on a row that has none.
pub struct RecordedSecurityIdentities {
    db: PgPool,
}

impl RecordedSecurityIdentities {
    pub fn new(db: PgPool) -> Self {
        Self { db }
    }
}

#[async_trait]
impl SecurityIdentities for RecordedSecurityIdentities {
    async fn record(&self, isin_by_ticker: &BTreeMap<String, String>, now: DateTime<Utc>) {
        for (ticker, isin) in isin_by_ticker {
            if ticker.trim().is_empty() || !is_isin(isin) {
                continue;
            }
            let outcome = sqlx::query(
                "INSERT INTO security_profile (ticker, asset_type, isin, status, created_at, updated_at) \
                 VALUES ($1, 'UNKNOWN', $2, 'NEVER_FETCHED', $3, $3) \
                 ON CONFLICT (ticker) DO UPDATE SET isin = EXCLUDED.isin, updated_at = EXCLUDED.updated_at \
                 WHERE security_profile.isin IS NULL",
            )
            .bind(ticker.to_uppercase())
            .bind(isin)
            .bind(now)
            .execute(&self.db)
            .await;
            if let Err(e) = outcome {
                tracing::debug!("Could not record ISIN {isin} for {ticker}: {e}");
            }
        }
    }
}

/// AccountService.toResponse (track T2): the AccountResponse a sync endpoint answers with.
#[async_trait]
pub trait AccountResponses: Send + Sync {
    async fn to_response(&self, state: &AppState, account: &Account) -> AppResult<Value>;
}

/// The AccountResponse fields the account row alone decides (no real-estate, debt, savings, SCPI,
/// contract, institution or display-preference overlays, which track T2 owns).
#[derive(Debug, Default, Clone, Copy)]
pub struct AccountRowResponses;

#[serde_with::skip_serializing_none]
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AccountRow<'a> {
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
}

#[async_trait]
impl AccountResponses for AccountRowResponses {
    async fn to_response(&self, state: &AppState, account: &Account) -> AppResult<Value> {
        let mut conn = state.db.acquire().await?;
        let eur = valuation::signed_live_balance_eur(
            &mut conn,
            &*state.ledger.prices,
            account,
            state.clock.today(),
        )
        .await?;
        let row = AccountRow {
            id: account.id,
            name: &account.name,
            account_type: account.account_type,
            provider: account.provider.as_deref(),
            currency: &account.currency,
            current_balance: account.current_balance,
            current_balance_eur: eur,
            cash_balance: account.cash_balance,
            last_synced_at: account.last_synced_at,
            is_manual: account.is_manual,
            color: &account.color,
            ticker: account.ticker.as_deref(),
            logo_url: account.logo_url.as_deref(),
            logo_key: account.logo_key.as_deref(),
            created_at: account.created_at,
            opened_at: account.opened_at,
            payment_due_amount: account.payment_due_amount,
            payment_due_date: account.payment_due_date,
            reward_points: account.reward_points,
            parent_account_id: account.parent_account_id,
            hidden: account.hidden,
            list_collapsed: false,
            card_nature: account.card_nature,
        };
        serde_json::to_value(row).map_err(AppError::internal)
    }
}

/// PriceService.backfillHistoricalPrices (track T4): stores the daily prices of `tickers` from
/// `from` on, and says how many rows it saved.
#[async_trait]
pub trait PriceBackfill: Send + Sync {
    async fn backfill(&self, tickers: &BTreeSet<String>, from: NaiveDate) -> anyhow::Result<usize>;
}

/// What PriceService saves with no price provider: nothing.
#[derive(Debug, Default, Clone, Copy)]
pub struct NoPriceBackfill;

#[async_trait]
impl PriceBackfill for NoPriceBackfill {
    async fn backfill(
        &self,
        _tickers: &BTreeSet<String>,
        _from: NaiveDate,
    ) -> anyhow::Result<usize> {
        Ok(0)
    }
}

/// SymbolCatalogPort.hasQuote (track T4): whether the price source quotes a ticker at all.
#[async_trait]
pub trait SymbolCatalog: Send + Sync {
    /// False on any failure: a negative means "no information", never "this symbol is dead".
    async fn has_quote(&self, ticker: &str) -> bool;
}

/// No symbol catalog configured: no ticker is known to be quoted.
#[derive(Debug, Default, Clone, Copy)]
pub struct NoSymbolCatalog;

#[async_trait]
impl SymbolCatalog for NoSymbolCatalog {
    async fn has_quote(&self, _ticker: &str) -> bool {
        false
    }
}

/// BankLogoResolver.logoUrlOrNull (Enable Banking catalog, track T5): an institution's logo by its name.
#[async_trait]
pub trait BankLogos: Send + Sync {
    /// None when the catalog is not configured, unreachable or has no match.
    async fn logo_url(&self, country: &str, institution_name: &str) -> Option<String>;
}

/// Enable Banking not configured: no logo.
#[derive(Debug, Default, Clone, Copy)]
pub struct NoBankLogos;

#[async_trait]
impl BankLogos for NoBankLogos {
    async fn logo_url(&self, _country: &str, _institution_name: &str) -> Option<String> {
        None
    }
}

/// ScpiProvider (track T4): a client space whose funds a sync maps onto SCPI accounts, stored by
/// name in scpi_provider_fund. Louve Invest is left out: no connector of this track syncs it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScpiProvider {
    Corum,
    Sofidy,
}

impl ScpiProvider {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Corum => "CORUM",
            Self::Sofidy => "SOFIDY",
        }
    }

    /// What a synced account shows as its provider and management company.
    pub fn display_name(self) -> &'static str {
        match self {
            Self::Corum => "CORUM",
            Self::Sofidy => "Sofidy",
        }
    }
}

/// ScpiFundOutcome (track T4): what the latest sync did with one provider fund.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScpiFundOutcome {
    Created,
    Linked,
    Updated,
    PriceIncomplete,
    Ambiguous,
    Ignored,
    MissingAtProvider,
}

impl ScpiFundOutcome {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Created => "CREATED",
            Self::Linked => "LINKED",
            Self::Updated => "UPDATED",
            Self::PriceIncomplete => "PRICE_INCOMPLETE",
            Self::Ambiguous => "AMBIGUOUS",
            Self::Ignored => "IGNORED",
            Self::MissingAtProvider => "MISSING_AT_PROVIDER",
        }
    }
}

impl fmt::Display for ScpiFundOutcome {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// ScpiFundReconciler.SyncedFund without the fields only Louve Invest publishes. A None
/// subscription price leaves the stored one alone; a None withdrawal price keeps the balance.
#[derive(Debug, Clone, PartialEq)]
pub struct SyncedFund {
    pub fund_code: String,
    pub label: Option<String>,
    pub quantity: Decimal,
    pub subscription_price: Option<Decimal>,
    pub withdrawal_price: Option<Decimal>,
}

/// ScpiFundReconciler and ScpiPositionService.applySyncedPosition (track T4), as the CORUM and
/// Sofidy syncs call them: in the sync's own transaction, after the member row lock.
#[async_trait]
pub trait ScpiFunds: Send + Sync {
    /// reconcile: the outcome per fund code, in snapshot order, then the funds no longer listed.
    async fn reconcile(
        &self,
        conn: &mut PgConnection,
        member_id: i64,
        provider: ScpiProvider,
        funds: &[SyncedFund],
        synced_at: DateTime<Utc>,
    ) -> AppResult<Vec<(String, ScpiFundOutcome)>>;

    /// SofidySyncService.reconcileSoldFunds: every position linked to a fund of `provider` that
    /// `listed` lacks is closed at zero shares, its account kept; how many were closed.
    async fn close_unlisted(
        &self,
        conn: &mut PgConnection,
        member_id: i64,
        provider: ScpiProvider,
        listed: &HashSet<String>,
        now: DateTime<Utc>,
    ) -> AppResult<usize>;
}

/// The connectors' plug-in points, held by state.connectors.seams.
pub struct Seams {
    pub isin: Arc<dyn IsinResolver>,
    pub security_identities: Arc<dyn SecurityIdentities>,
    pub accounts: Arc<dyn AccountResponses>,
    pub price_backfill: Arc<dyn PriceBackfill>,
    pub symbol_catalog: Arc<dyn SymbolCatalog>,
    pub bank_logos: Arc<dyn BankLogos>,
    pub scpi_funds: Arc<dyn ScpiFunds>,
}

impl Seams {
    pub fn new(db: PgPool) -> Self {
        Self {
            isin: Arc::new(UnresolvedIsins),
            security_identities: Arc::new(RecordedSecurityIdentities::new(db)),
            accounts: Arc::new(AccountRowResponses),
            price_backfill: Arc::new(NoPriceBackfill),
            symbol_catalog: Arc::new(NoSymbolCatalog),
            bank_logos: Arc::new(NoBankLogos),
            scpi_funds: Arc::new(super::corum::scpi_funds::RecordedScpiFunds),
        }
    }
}
