//! What track T2 calls in other tracks, behind narrow traits. Each default is what Java does
//! when that side has nothing; the owning track swaps in its implementation through
//! state.accounts after the wave merges.

use std::collections::BTreeMap;
use std::sync::Arc;

use async_trait::async_trait;
use chrono::NaiveDateTime;
use rust_decimal::Decimal;
use sqlx::PgConnection;

use crate::AppState;
use crate::error::AppResult;
use crate::ledger::instrument::TickerResult;

/// GoalService.toProgressResponse over the member's goals (track T4), as the dashboard lists
/// them: every goal but RECURRING_INVESTMENT, oldest first, in GoalProgressResponse's JSON.
#[async_trait]
pub trait DashboardGoals: Send + Sync {
    async fn summaries(
        &self,
        state: &AppState,
        member_id: i64,
    ) -> AppResult<Vec<serde_json::Value>>;
}

/// Without the goals track no goal can exist, so there is nothing to summarize.
pub struct NoGoals;

#[async_trait]
impl DashboardGoals for NoGoals {
    async fn summaries(
        &self,
        _state: &AppState,
        _member_id: i64,
    ) -> AppResult<Vec<serde_json::Value>> {
        Ok(Vec::new())
    }
}

/// PriceService.getIntradayPricesEur / getCryptoIntradayPricesEur (track T4): the EUR prices of
/// one series between two local instants, keyed by local instant.
#[async_trait]
pub trait IntradayPrices: Send + Sync {
    async fn prices_eur(
        &self,
        ticker: &str,
        crypto: bool,
        from: NaiveDateTime,
        to: NaiveDateTime,
    ) -> BTreeMap<NaiveDateTime, Decimal>;
}

/// No provider: Java logs the failed fetch and leaves the series out of the chart.
pub struct NoIntradayPrices;

#[async_trait]
impl IntradayPrices for NoIntradayPrices {
    async fn prices_eur(
        &self,
        _ticker: &str,
        _crypto: bool,
        _from: NaiveDateTime,
        _to: NaiveDateTime,
    ) -> BTreeMap<NaiveDateTime, Decimal> {
        BTreeMap::new()
    }
}

/// OpenFigiIsinConverter.resolve (track T4).
#[async_trait]
pub trait IsinResolver: Send + Sync {
    async fn resolve(&self, isin: &str) -> TickerResult;
}

/// OpenFIGI unreachable: Java keeps the normalized ISIN as the ticker, with no name.
pub struct IsinAsTicker;

#[async_trait]
impl IsinResolver for IsinAsTicker {
    async fn resolve(&self, isin: &str) -> TickerResult {
        TickerResult {
            ticker: isin.trim().to_uppercase(),
            name: None,
        }
    }
}

/// BankLogoResolver.logoUrlOrNull: the institution catalog of the bank connector (track T6).
#[async_trait]
pub trait BankLogos: Send + Sync {
    async fn logo_url(
        &self,
        country: &str,
        institution_id: Option<&str>,
        provider: &str,
    ) -> Option<String>;
}

/// No bank connector configured to ask: no logo.
pub struct NoBankLogos;

#[async_trait]
impl BankLogos for NoBankLogos {
    async fn logo_url(
        &self,
        _country: &str,
        _institution_id: Option<&str>,
        _provider: &str,
    ) -> Option<String> {
        None
    }
}

/// The sync connection an account came from, as AccountConnectionService resolves it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConnectionRef {
    pub kind: ConnectionKind,
    pub discriminator: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionKind {
    Wallet,
    Exchange,
    Amundi,
    Cmes,
    Cic,
    TradeRepublic,
    BourseDirect,
    Bourso,
    Fortuneo,
    Ibkr,
    Simplefin,
    Wise,
    Degiro,
    Corum,
    Louve,
    Natixis,
    Lbp,
    Bnp,
    EnableBanking,
}

/// AccountConnectionService.removeConnection for the connections the sync tracks own (T5/T6):
/// clearSession, deleteConnection, removeWallet, removeExchange. True when something was removed.
/// Enable Banking requisitions are removed by T2 itself (a repository delete).
#[async_trait]
pub trait ConnectionRemoval: Send + Sync {
    async fn remove(
        &self,
        conn: &mut PgConnection,
        connection: &ConnectionRef,
        member_id: i64,
    ) -> AppResult<bool>;
}

/// No sync track: no session or connection row of theirs can exist, so nothing is removed.
pub struct NoSyncConnections;

#[async_trait]
impl ConnectionRemoval for NoSyncConnections {
    async fn remove(
        &self,
        _conn: &mut PgConnection,
        _connection: &ConnectionRef,
        _member_id: i64,
    ) -> AppResult<bool> {
        Ok(false)
    }
}

/// PropertyValuationService.estimate once the account and its metadata are checked (track T4):
/// the PropertyValuationResponse JSON.
#[async_trait]
pub trait PropertyValuer: Send + Sync {
    async fn estimate(
        &self,
        state: &AppState,
        account_id: i64,
        member_id: i64,
    ) -> AppResult<serde_json::Value>;
}

/// Every T2 seam, held by AppState as state.accounts.
pub struct Seams {
    pub goals: Arc<dyn DashboardGoals>,
    pub intraday: Arc<dyn IntradayPrices>,
    pub isin: Arc<dyn IsinResolver>,
    pub bank_logos: Arc<dyn BankLogos>,
    pub connections: Arc<dyn ConnectionRemoval>,
    pub property_valuation: Arc<dyn PropertyValuer>,
}

impl Default for Seams {
    fn default() -> Self {
        Self {
            goals: Arc::new(NoGoals),
            intraday: Arc::new(NoIntradayPrices),
            isin: Arc::new(IsinAsTicker),
            bank_logos: Arc::new(NoBankLogos),
            connections: Arc::new(NoSyncConnections),
            property_valuation: Arc::new(super::meta::ValuationDisabled),
        }
    }
}
