//! Cross-track seam wiring: connects every domain's narrow seam traits to the real owning
//! implementations at AppState construction time so zero 501 NOT_IMPLEMENTED seams remain.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::sync::{Arc, OnceLock, Weak};
use std::time::Duration;

use async_trait::async_trait;
use chrono::{DateTime, NaiveDate, NaiveDateTime, Utc};
use rust_decimal::Decimal;
use serde_json::Value;
use sqlx::{PgConnection, PgPool};

use crate::accounts::analysis::profiles::RefreshRunner;
use crate::accounts::analysis::seams::{
    EtfCompositionProvider, InstrumentTypeSource, SecurityProfileRefresher,
};
use crate::accounts::positions::CryptoLogoRead;
use crate::accounts::seams::{
    BankLogos as AccountBankLogos, ConnectionKind, ConnectionRef, ConnectionRemoval,
    DashboardGoals, IntradayPrices, IsinResolver as AccountIsinResolver, PropertyValuer,
    RemovedConnection,
};
use crate::accounts::security::{
    EtfComposition as AccountEtfComposition, FundFacts as AccountFundFacts, SecurityRef,
    WeightedSlice as AccountWeightedSlice,
};
use crate::clock::AppClock;
use crate::connectors::corum::scpi_funds::RecordedScpiFunds;
use crate::connectors::seams::{
    AccountResponses as ConnectorAccountResponses, BankLogos as ConnectorBankLogos,
    IsinResolver as ConnectorIsinResolver, PriceBackfill, RecordedSecurityIdentities,
    SavingsAutoConfig, ScpiFundOutcome as ConnectorScpiOutcome, ScpiFunds as ConnectorScpiFunds,
    ScpiProvider as ConnectorScpiProvider, SecurityIdentities as ConnectorSecurityIdentities,
    SymbolCatalog, SyncedFund as ConnectorSyncedFund,
};
use crate::error::{AppError, AppResult};
use crate::ledger::instrument::{TickerResult, is_isin};
use crate::ledger::model::{Account, AccountType};
use crate::ledger::prices::PriceRead;
use crate::ledger::sidecar::{SidecarBank, SourceSyncResult};
use crate::ledger::sync_values::java_is_blank;
use crate::market::coingecko::CoinGecko;
use crate::market::justetf::{DistributionPolicy, JustEtf, Replication};
use crate::market::openfigi::OpenFigi;
use crate::market::prices::PriceService;
use crate::market::yahoo::YahooFinance;
use crate::sync::enable_banking::hooks::{
    AccountResponses as EbAccountResponses, RecurringDetection,
};
use crate::sync::exchanges::seams::{AccountResponder as ExchangeAccountResponder, CryptoLogos};
use crate::sync::finary::IsinLookup as FinaryIsinLookup;
use crate::sync::ibkr::seams::{
    AccountResponses as IbkrAccountResponses, IsinResolver as IbkrIsinResolver,
    SecurityIdentities as IbkrSecurityIdentities,
};
use crate::sync::louve::scpi::{
    DistributionFrequency as LouveDistFreq, Outcome as LouveScpiOutcome, ScpiFunds as LouveScpi,
    SyncedFund as LouveSyncedFund, UnsupportedFund as LouveUnsupportedFund,
};
use crate::sync::member_sync::{NoSidecarSessions, SidecarSources};
use crate::sync::simplefin::AccountResponses as SimplefinAccountResponses;
use crate::sync::wallets::WalletPrices;
use crate::sync::wise::AccountView as WiseAccountView;
use crate::transfer::IsinResolver as TransferIsinResolver;
use crate::wealth::history::{
    AccountPoint as WealthAccountPoint, HistorySource, NetWorthPoint as WealthNetWorthPoint,
};
use crate::wealth::savings::AccountResponses as WealthAccountResponses;
use crate::wealth::savings::model::{
    RateBasis, RegulatedRates, SavingsConfig, SavingsProduct, insert_if_absent,
};
use crate::wealth::scpi::Provider as WealthScpiProvider;
use crate::wealth::scpi::reconciler::{
    Outcome as WealthScpiOutcome, SyncedFund as WealthSyncedFund,
    UnsupportedFund as WealthUnsupportedFund,
};
use crate::{AppState, Inner};

/// Weak back-reference to `AppState` for seam traits whose signatures do not pass `&AppState`.
/// Populated once right after `Arc::new(inner)` so no reference cycle is ever created.
#[derive(Clone, Default)]
pub(crate) struct StateRef(Arc<OnceLock<Weak<Inner>>>);

impl StateRef {
    pub(crate) fn set(&self, state: &AppState) {
        let _ = self.0.set(state.downgrade());
    }

    fn get(&self) -> Option<AppState> {
        self.0.get().and_then(AppState::upgrade)
    }
}

/// Wires every cross-track seam on `inner`. Leaves `inner.budget.ai` untouched (owned by `ai`).
pub(crate) fn wire_all(inner: &mut Inner) -> StateRef {
    let state_ref = StateRef::default();

    let prices = inner.market.prices().clone();
    let openfigi = inner.market.openfigi().clone();
    let coingecko = inner.market.coingecko().clone();
    let yahoo = inner.market.yahoo().clone();
    let justetf = inner.market.justetf().clone();

    let account_responses = Arc::new(AccountsToResponse);
    let isin_resolver = Arc::new(OpenFigiIsin(openfigi));
    let crypto_logos = Arc::new(CoinGeckoLogos(coingecko.clone()));
    let bank_logos = Arc::new(EnableBankingLogos(state_ref.clone()));

    // 1. Track T2 (accounts + analysis)
    inner.accounts.goals = Arc::new(WealthDashboardGoals);
    inner.accounts.intraday = Arc::new(MarketIntradayPrices {
        prices: prices.clone(),
        coingecko: coingecko.clone(),
    });
    inner.accounts.isin = isin_resolver.clone();
    inner.accounts.bank_logos = bank_logos.clone();
    inner.accounts.connections = Arc::new(SyncConnectionRemoval(state_ref.clone()));
    inner.accounts.property_valuation = Arc::new(WealthPropertyValuer);
    inner.accounts.crypto_logos = crypto_logos.clone();
    inner.accounts.analysis.instrument_types = Arc::new(YahooInstrumentTypes(yahoo.clone()));
    inner.accounts.analysis.etf_compositions = vec![Arc::new(JustEtfComposition(justetf))];
    inner.accounts.analysis.profile_runner =
        Arc::new(RefreshRunner::new(Arc::new(MarketSecurityProfiles {
            state_ref: state_ref.clone(),
            yahoo: yahoo.clone(),
            clock: inner.clock.clone(),
            pacing: Duration::from_millis(500),
        })));

    // 2. Track T4 (wealth)
    inner
        .wealth
        .savings
        .wire_account_responses(account_responses.clone());
    inner.wealth.history = Arc::new(AccountsHistorySource);

    // 3. Track T5 (sync)
    inner.sync.set_sidecars(Arc::new(ConnectorSidecars));
    inner.sync.enable_banking.recurring = Arc::new(BudgetRecurringDetection);
    inner.sync.enable_banking.accounts = account_responses.clone();
    inner.sync.exchanges.accounts = account_responses.clone();
    inner.sync.ibkr.account_responses = account_responses.clone();
    inner.sync.simplefin.accounts = account_responses.clone();
    inner.sync.wise.accounts = account_responses.clone();
    inner
        .sync
        .wallets
        .set_prices(Arc::new(MarketWalletPrices(prices.clone())));
    inner.sync.exchanges.logos = crypto_logos;
    inner.sync.ibkr.isin_resolver = isin_resolver.clone();
    inner.sync.finary.isin = isin_resolver.clone();
    inner.sync.ibkr.security_identities = Arc::new(RecordedIbkrIdentities {
        db: inner.db.clone(),
        clock: inner.clock.clone(),
    });
    inner.sync.louve.scpi = Arc::new(WealthLouveScpi);

    // 4. Track T6 (connectors)
    inner.connectors.seams.isin = isin_resolver.clone();
    inner.connectors.seams.price_backfill = Arc::new(MarketPriceBackfill(prices));
    inner.connectors.seams.symbol_catalog = Arc::new(YahooSymbolCatalog(yahoo));
    inner.connectors.seams.scpi_funds = Arc::new(WealthConnectorScpi);
    inner.connectors.seams.savings = Arc::new(WealthSavingsAutoConfig);
    inner.connectors.seams.accounts = account_responses;
    inner.connectors.seams.security_identities =
        Arc::new(RecordedSecurityIdentities::new(inner.db.clone()));
    inner.connectors.seams.bank_logos = bank_logos;

    // 5. Track T7 (transfer)
    inner.transfer.isin = isin_resolver;

    state_ref
}

// ---------------------------------------------------------------------------
// Shared AccountService.toResponse adapter (T2 -> T4, T5, T6)
// ---------------------------------------------------------------------------

struct AccountsToResponse;

async fn account_to_json(
    conn: &mut PgConnection,
    prices: &dyn PriceRead,
    account: &Account,
    today: NaiveDate,
) -> AppResult<Value> {
    let fixed = crate::accounts::scale::fixed(account.clone());
    let response =
        crate::accounts::dto::to_response(conn, prices, &fixed, None, None, today).await?;
    serde_json::to_value(response).map_err(AppError::internal)
}

#[async_trait]
impl WealthAccountResponses for AccountsToResponse {
    async fn to_response(&self, state: &AppState, account: &Account) -> AppResult<Value> {
        let mut conn = state.db.acquire().await?;
        account_to_json(
            &mut conn,
            &*state.ledger.prices,
            account,
            state.clock.today(),
        )
        .await
    }
}

#[async_trait]
impl EbAccountResponses for AccountsToResponse {
    async fn to_response(
        &self,
        state: &AppState,
        conn: &mut PgConnection,
        account: &Account,
    ) -> AppResult<Value> {
        account_to_json(conn, &*state.ledger.prices, account, state.clock.today()).await
    }
}

#[async_trait]
impl ExchangeAccountResponder for AccountsToResponse {
    async fn account_response(&self, state: &AppState, account: &Account) -> AppResult<Value> {
        let mut conn = state.db.acquire().await?;
        // Exchange sync returns the freshly written account with its computed balance scale
        // (Hibernate keeps the managed instance rather than re-reading numeric(20,8)).
        let response = crate::accounts::dto::to_response(
            &mut conn,
            &*state.ledger.prices,
            account,
            None,
            None,
            state.clock.today(),
        )
        .await?;
        serde_json::to_value(response).map_err(AppError::internal)
    }
}

#[async_trait]
impl IbkrAccountResponses for AccountsToResponse {
    async fn to_response(
        &self,
        conn: &mut PgConnection,
        prices: &dyn PriceRead,
        account: &Account,
        today: NaiveDate,
    ) -> AppResult<Value> {
        account_to_json(conn, prices, account, today).await
    }
}

#[async_trait]
impl SimplefinAccountResponses for AccountsToResponse {
    async fn to_response(
        &self,
        conn: &mut PgConnection,
        prices: &dyn PriceRead,
        account: &Account,
        today: NaiveDate,
    ) -> sqlx::Result<Value> {
        match account_to_json(conn, prices, account, today).await {
            Ok(v) => Ok(v),
            Err(other) => Err(sqlx::Error::Protocol(other.to_string())),
        }
    }
}

#[async_trait]
impl WiseAccountView for AccountsToResponse {
    async fn to_response(
        &self,
        state: &AppState,
        conn: &mut PgConnection,
        account: &Account,
    ) -> AppResult<Value> {
        account_to_json(conn, &*state.ledger.prices, account, state.clock.today()).await
    }
}

#[async_trait]
impl ConnectorAccountResponses for AccountsToResponse {
    async fn to_response(&self, state: &AppState, account: &Account) -> AppResult<Value> {
        let mut conn = state.db.acquire().await?;
        account_to_json(
            &mut conn,
            &*state.ledger.prices,
            account,
            state.clock.today(),
        )
        .await
    }
}

// ---------------------------------------------------------------------------
// Shared OpenFIGI ISIN resolver (T4 -> T2, T5, T6, T7)
// ---------------------------------------------------------------------------

struct OpenFigiIsin(Arc<OpenFigi>);

#[async_trait]
impl AccountIsinResolver for OpenFigiIsin {
    async fn resolve(&self, isin: &str) -> TickerResult {
        self.0.resolve(isin).await
    }
}

#[async_trait]
impl IbkrIsinResolver for OpenFigiIsin {
    async fn resolve(&self, isin: &str) -> TickerResult {
        self.0.resolve(isin).await
    }
}

#[async_trait]
impl FinaryIsinLookup for OpenFigiIsin {
    async fn resolve(&self, isin: &str) -> TickerResult {
        self.0.resolve(isin).await
    }
}

#[async_trait]
impl ConnectorIsinResolver for OpenFigiIsin {
    async fn resolve(&self, isin: &str) -> TickerResult {
        self.0.resolve(isin).await
    }
}

#[async_trait]
impl TransferIsinResolver for OpenFigiIsin {
    async fn resolve(&self, isin: &str) -> TickerResult {
        self.0.resolve(isin).await
    }
}

// ---------------------------------------------------------------------------
// Shared CoinGecko logos (T4 -> T2, T5)
// ---------------------------------------------------------------------------

struct CoinGeckoLogos(Arc<CoinGecko>);

#[async_trait]
impl CryptoLogoRead for CoinGeckoLogos {
    async fn logo_urls(&self, tickers: &BTreeSet<String>) -> HashMap<String, String> {
        self.0.logo_urls(tickers).await
    }
}

#[async_trait]
impl CryptoLogos for CoinGeckoLogos {
    async fn logo_urls(&self, tickers: &BTreeSet<String>) -> HashMap<String, String> {
        self.0.logo_urls(tickers).await
    }
}

// ---------------------------------------------------------------------------
// Shared Enable Banking bank logo resolver (T5 -> T2, T6)
// ---------------------------------------------------------------------------

struct EnableBankingLogos(StateRef);

#[async_trait]
impl AccountBankLogos for EnableBankingLogos {
    async fn logo_url(
        &self,
        country: &str,
        institution_id: Option<&str>,
        provider: &str,
    ) -> Option<String> {
        let state = self.0.get()?;
        crate::sync::enable_banking::service::logo_url_or_null(
            &state,
            Some(country),
            institution_id,
            Some(provider),
        )
        .await
    }
}

#[async_trait]
impl ConnectorBankLogos for EnableBankingLogos {
    async fn logo_url(&self, country: &str, institution_name: &str) -> Option<String> {
        let state = self.0.get()?;
        crate::sync::enable_banking::service::logo_url_or_null(
            &state,
            Some(country),
            None,
            Some(institution_name),
        )
        .await
    }
}

// ---------------------------------------------------------------------------
// Track T2 seams (goals, intraday, property valuation, connection removal, analysis)
// ---------------------------------------------------------------------------

struct WealthDashboardGoals;

#[async_trait]
impl DashboardGoals for WealthDashboardGoals {
    async fn summaries(&self, state: &AppState, member_id: i64) -> AppResult<Vec<Value>> {
        let goals = crate::wealth::goals::dashboard_summaries(state, member_id).await?;
        goals
            .into_iter()
            .map(|g| serde_json::to_value(g).map_err(AppError::internal))
            .collect()
    }
}

struct MarketIntradayPrices {
    prices: Arc<PriceService>,
    coingecko: Arc<CoinGecko>,
}

#[async_trait]
impl IntradayPrices for MarketIntradayPrices {
    async fn prices_eur(
        &self,
        ticker: &str,
        crypto: bool,
        from: NaiveDateTime,
        to: NaiveDateTime,
    ) -> BTreeMap<NaiveDateTime, Decimal> {
        if java_is_blank(ticker) || ticker.eq_ignore_ascii_case("EUR") {
            return BTreeMap::new();
        }
        let series = if crypto {
            self.coingecko
                .intraday_prices_eur(&ticker.to_uppercase(), from, to)
                .await
        } else {
            self.prices.intraday_prices_eur(ticker, from, to).await
        };
        series.into_iter().collect()
    }
}

struct WealthPropertyValuer;

#[async_trait]
impl PropertyValuer for WealthPropertyValuer {
    async fn estimate(
        &self,
        state: &AppState,
        account_id: i64,
        member_id: i64,
    ) -> AppResult<Value> {
        let resp =
            crate::wealth::real_estate::valuation::estimate(state, account_id, member_id).await?;
        serde_json::to_value(resp).map_err(AppError::internal)
    }
}

/// Removes the T5/T6 connection feeding a deleted account inside the account deletion's own
/// transaction so row locks (CORUM/Louve) never deadlock and rollback stays atomic.
struct SyncConnectionRemoval(StateRef);

#[async_trait]
impl ConnectionRemoval for SyncConnectionRemoval {
    async fn remove(
        &self,
        conn: &mut PgConnection,
        connection: &ConnectionRef,
        member_id: i64,
    ) -> AppResult<RemovedConnection> {
        use ConnectionKind as K;
        let state = self
            .0
            .get()
            .ok_or_else(|| AppError::internal("AppState unavailable during connection removal"))?;
        let removed = match connection.kind {
            K::Wallet => {
                let Ok(wallet_id) = connection.discriminator.parse::<i64>() else {
                    return Ok(RemovedConnection::simple(false));
                };
                let exists: bool = sqlx::query_scalar(
                    "SELECT EXISTS(SELECT 1 FROM wallet_address WHERE id = $1 AND member_id = $2)",
                )
                .bind(wallet_id)
                .bind(member_id)
                .fetch_one(&mut *conn)
                .await?;
                if !exists {
                    return Ok(RemovedConnection::simple(false));
                }
                crate::sync::wallets::remove_wallet(conn, state.clock.now(), wallet_id, member_id)
                    .await?;
                true
            }
            K::Exchange => {
                let id: Option<i64> = sqlx::query_scalar(
                    "SELECT id FROM crypto_exchange_session WHERE exchange_type = $1 AND member_id = $2",
                )
                .bind(&connection.discriminator)
                .bind(member_id)
                .fetch_optional(&mut *conn)
                .await?;
                let Some(id) = id else {
                    return Ok(RemovedConnection::simple(false));
                };
                sqlx::query("DELETE FROM crypto_exchange_session WHERE id = $1")
                    .bind(id)
                    .execute(&mut *conn)
                    .await?;
                true
            }
            K::Amundi => delete_sidecar(conn, SidecarBank::Amundi, member_id).await?,
            K::Cmes => {
                let _guard = state.connectors.cmes_logins.write_lock().await;
                state.connectors.cmes_logins.forget_member(member_id);
                delete_sidecar(conn, SidecarBank::Cmes, member_id).await?
            }
            K::Cic => {
                let _guard = state.connectors.cic_logins.write_lock().await;
                state.connectors.cic_logins.forget_member(member_id);
                delete_sidecar(conn, SidecarBank::Cic, member_id).await?
            }
            K::Natixis => {
                let _guard = state.connectors.natixis_logins.write_lock().await;
                state.connectors.natixis_logins.forget_member(member_id);
                delete_sidecar(conn, SidecarBank::Natixis, member_id).await?
            }
            K::TradeRepublic => {
                let _guard = state.connectors.trade_republic.session_write.lock().await;
                state.connectors.trade_republic.bump_generation(member_id);
                delete_sidecar(conn, SidecarBank::TradeRepublic, member_id).await?
            }
            K::BourseDirect => delete_sidecar(conn, SidecarBank::BourseDirect, member_id).await?,
            K::Bourso => delete_sidecar(conn, SidecarBank::Bourso, member_id).await?,
            K::Fortuneo => delete_sidecar(conn, SidecarBank::Fortuneo, member_id).await?,
            K::Corum => delete_sidecar(conn, SidecarBank::Corum, member_id).await?,
            K::Bnp => delete_sidecar(conn, SidecarBank::Bnp, member_id).await?,
            K::Degiro => {
                let rows = sqlx::query("DELETE FROM degiro_session WHERE member_id = $1")
                    .bind(member_id)
                    .execute(&mut *conn)
                    .await?
                    .rows_affected();
                rows > 0
            }
            K::Lbp => {
                let rows = sqlx::query("DELETE FROM lbp_session WHERE member_id = $1")
                    .bind(member_id)
                    .execute(&mut *conn)
                    .await?
                    .rows_affected();
                rows > 0
            }
            K::Ibkr => {
                let rows = sqlx::query("DELETE FROM ibkr_connection WHERE member_id = $1")
                    .bind(member_id)
                    .execute(&mut *conn)
                    .await?
                    .rows_affected();
                rows > 0
            }
            K::Simplefin => {
                let rows = sqlx::query("DELETE FROM simplefin_connection WHERE member_id = $1")
                    .bind(member_id)
                    .execute(&mut *conn)
                    .await?
                    .rows_affected();
                rows > 0
            }
            K::Wise => crate::sync::wise::delete_connection(&mut *conn, member_id).await?,
            K::Louve => {
                let removal =
                    crate::sync::louve::service::delete_connection_in(&state, conn, member_id)
                        .await?;
                let removed = removal.removed;
                let after_commit = removed.then(|| {
                    let revoke_state = state.clone();
                    Box::new(move || {
                        tokio::spawn(async move {
                            removal.revoke_after_commit(&revoke_state).await;
                        });
                    }) as Box<dyn FnOnce() + Send>
                });
                return Ok(RemovedConnection {
                    removed,
                    after_commit,
                });
            }
            K::EnableBanking => false,
        };
        Ok(RemovedConnection::simple(removed))
    }
}

async fn delete_sidecar(
    conn: &mut PgConnection,
    bank: SidecarBank,
    member_id: i64,
) -> AppResult<bool> {
    let rows = sqlx::query("DELETE FROM sidecar_session WHERE bank = $1 AND member_id = $2")
        .bind(bank.as_str())
        .bind(member_id)
        .execute(&mut *conn)
        .await?
        .rows_affected();
    Ok(rows > 0)
}

// ---------------------------------------------------------------------------
// Track T2 analysis seams (instrument types, ETF compositions, security profiles)
// ---------------------------------------------------------------------------

struct YahooInstrumentTypes(Arc<YahooFinance>);

#[async_trait]
impl InstrumentTypeSource for YahooInstrumentTypes {
    async fn instrument_type(&self, upper_ticker: &str) -> Option<String> {
        self.0.instrument_type(upper_ticker).await
    }
}

struct JustEtfComposition(Arc<JustEtf>);

#[async_trait]
impl EtfCompositionProvider for JustEtfComposition {
    fn supports(&self, security: &SecurityRef) -> bool {
        JustEtf::supports(security.isin.as_deref())
    }

    async fn fetch(&self, security: &SecurityRef) -> Option<AccountEtfComposition> {
        let isin = security.isin.as_deref()?;
        let comp = self.0.fetch(isin).await?;
        let map_slices = |items: Vec<crate::market::justetf::WeightedSlice>| {
            items
                .into_iter()
                .map(|s| AccountWeightedSlice {
                    label: Some(s.label),
                    percent: Some(s.percent),
                })
                .collect()
        };
        let facts = comp.facts.map(|f| AccountFundFacts {
            ter_percent: f.ter_percent,
            distribution_policy: f.distribution_policy.map(|p| {
                match p {
                    DistributionPolicy::Accumulating => "ACCUMULATING",
                    DistributionPolicy::Distributing => "DISTRIBUTING",
                }
                .to_owned()
            }),
            replication: f.replication.map(|r| {
                match r {
                    Replication::Physical => "PHYSICAL",
                    Replication::Synthetic => "SYNTHETIC",
                }
                .to_owned()
            }),
            domicile_country_key: f.domicile_country_key,
            source: Some(f.source),
        });
        Some(AccountEtfComposition {
            companies: map_slices(comp.companies),
            countries: map_slices(comp.countries),
            sectors: map_slices(comp.sectors),
            source: Some(comp.source),
            as_of: comp.as_of,
            facts,
        })
    }
}

/// SecurityProfileService.refreshStale: refreshes stale security_profile rows from Yahoo + justETF,
/// committing each ticker in its own short transaction.
struct MarketSecurityProfiles {
    state_ref: StateRef,
    yahoo: Arc<YahooFinance>,
    clock: AppClock,
    pacing: Duration,
}

const PROFILE_REFRESH_AFTER_DAYS: i64 = 30;
const PROFILE_RETRY_FAILURE_DAYS: i64 = 3;

#[derive(sqlx::FromRow)]
struct ProfileFreshness {
    ticker: String,
    status: String,
    refreshed_at: Option<DateTime<Utc>>,
    last_attempt_at: Option<DateTime<Utc>>,
    isin: Option<String>,
}

fn profile_is_due(
    row: Option<&ProfileFreshness>,
    success_cutoff: DateTime<Utc>,
    failure_cutoff: DateTime<Utc>,
) -> bool {
    let Some(p) = row else {
        return true;
    };
    let Some(refreshed_at) = p.refreshed_at else {
        return true;
    };
    if p.status == "FAILED" {
        return p.last_attempt_at.is_none_or(|at| at < failure_cutoff);
    }
    refreshed_at < success_cutoff
}

#[async_trait]
impl SecurityProfileRefresher for MarketSecurityProfiles {
    async fn refresh_stale(&self, db: &PgPool, tickers: &BTreeSet<String>, limit: usize) -> usize {
        if tickers.is_empty() || limit == 0 {
            return 0;
        }
        let Some(state) = self.state_ref.get() else {
            return 0;
        };
        let upper: BTreeSet<String> = tickers
            .iter()
            .filter(|t| !java_is_blank(t))
            .map(|t| t.to_uppercase())
            .collect();
        if upper.is_empty() {
            return 0;
        }
        let keys: Vec<&str> = upper.iter().map(String::as_str).collect();
        let known: HashMap<String, ProfileFreshness> = match sqlx::query_as::<_, ProfileFreshness>(
            "SELECT ticker, status, refreshed_at, last_attempt_at, isin \
             FROM security_profile WHERE ticker = ANY($1)",
        )
        .bind(&keys)
        .fetch_all(db)
        .await
        {
            Ok(rows) => rows
                .into_iter()
                .map(|r| (r.ticker.to_uppercase(), r))
                .collect(),
            Err(e) => {
                tracing::warn!("Could not load security profiles: {e}");
                return 0;
            }
        };

        let now = self.clock.now();
        let success_cutoff = now - chrono::Duration::days(PROFILE_REFRESH_AFTER_DAYS);
        let failure_cutoff = now - chrono::Duration::days(PROFILE_RETRY_FAILURE_DAYS);

        let due: Vec<String> = upper
            .into_iter()
            .filter(|t| profile_is_due(known.get(t), success_cutoff, failure_cutoff))
            .take(limit)
            .collect();

        let mut refreshed = 0usize;
        for (attempt, ticker) in due.iter().enumerate() {
            if attempt > 0 && !self.pacing.is_zero() {
                tokio::time::sleep(self.pacing).await;
            }
            let known_isin = known.get(ticker).and_then(|p| p.isin.clone());
            match self
                .refresh_one(&state, db, ticker, known_isin.as_deref())
                .await
            {
                Ok(true) => refreshed += 1,
                Ok(false) => {}
                Err(e) => {
                    tracing::warn!("Security profile refresh failed for {ticker}: {e}");
                }
            }
        }
        refreshed
    }
}

impl MarketSecurityProfiles {
    async fn refresh_one(
        &self,
        state: &AppState,
        db: &PgPool,
        upper: &str,
        stored_isin: Option<&str>,
    ) -> AppResult<bool> {
        let now = self.clock.now();
        let isin = stored_isin
            .filter(|i| is_isin(i))
            .or_else(|| is_isin(upper).then_some(upper))
            .map(str::to_owned);

        let insight = crate::accounts::security::get_insight(
            db,
            &state.accounts.analysis,
            upper,
            None,
            isin.as_deref(),
            now,
        )
        .await?;

        let mut sector_key: Option<String> = None;
        let mut source: Option<String> = None;
        let mut as_of: Option<NaiveDate> = None;
        let mut slices: Vec<(&'static str, String, Decimal)> = Vec::new();
        let mut replace_slices = false;
        let mut ter_percent: Option<Decimal> = None;
        let mut distribution_policy: Option<String> = None;
        let mut replication: Option<String> = None;
        let mut domicile_country: Option<String> = None;

        let resolved = if let Some(comp) = insight.composition {
            source = comp.source;
            as_of = comp.as_of;
            collect_slices(&mut slices, "COMPANY", &comp.companies);
            collect_slices(&mut slices, "COUNTRY", &comp.countries);
            collect_slices(&mut slices, "SECTOR", &comp.sectors);
            if !slices.is_empty() {
                replace_slices = true;
            }
            let has_facts = comp.facts.is_some();
            if let Some(facts) = comp.facts {
                ter_percent = facts.ter_percent;
                distribution_policy = facts.distribution_policy;
                replication = facts.replication;
                domicile_country = facts.domicile_country_key;
            }
            !slices.is_empty() || has_facts
        } else if insight.asset_type == "STOCK" {
            if let Some(sector) = self.yahoo.equity_sector(upper).await {
                sector_key = Some(sector);
                source = Some("Yahoo Finance".to_owned());
                replace_slices = true;
                true
            } else {
                false
            }
        } else {
            false
        };

        let status = if resolved { "OK" } else { "NO_DATA" };
        let mut tx = db.begin().await?;
        let profile_id: i64 = sqlx::query_scalar(
            "INSERT INTO security_profile \
             (ticker, asset_type, isin, sector_key, country_key, source, as_of, status, last_error, \
              last_attempt_at, refreshed_at, ter_percent, distribution_policy, replication, domicile_country, \
              created_at, updated_at) \
             VALUES ($1, $2, $3, $4, NULL, $5, $6, $7, NULL, $8, $8, $9, $10, $11, $12, $8, $8) \
             ON CONFLICT (ticker) DO UPDATE SET \
              asset_type = EXCLUDED.asset_type, \
              isin = COALESCE(security_profile.isin, EXCLUDED.isin), \
              sector_key = CASE WHEN $13 THEN EXCLUDED.sector_key ELSE security_profile.sector_key END, \
              country_key = CASE WHEN $13 THEN NULL ELSE security_profile.country_key END, \
              source = COALESCE(EXCLUDED.source, security_profile.source), \
              as_of = COALESCE(EXCLUDED.as_of, security_profile.as_of), \
              status = EXCLUDED.status, \
              last_error = NULL, \
              last_attempt_at = EXCLUDED.last_attempt_at, \
              refreshed_at = EXCLUDED.refreshed_at, \
              ter_percent = COALESCE(EXCLUDED.ter_percent, security_profile.ter_percent), \
              distribution_policy = COALESCE(EXCLUDED.distribution_policy, security_profile.distribution_policy), \
              replication = COALESCE(EXCLUDED.replication, security_profile.replication), \
              domicile_country = COALESCE(EXCLUDED.domicile_country, security_profile.domicile_country), \
              updated_at = EXCLUDED.updated_at \
             RETURNING id",
        )
        .bind(upper)
        .bind(&insight.asset_type)
        .bind(&isin)
        .bind(&sector_key)
        .bind(&source)
        .bind(as_of)
        .bind(status)
        .bind(now)
        .bind(ter_percent)
        .bind(&distribution_policy)
        .bind(&replication)
        .bind(&domicile_country)
        .bind(replace_slices)
        .fetch_one(&mut *tx)
        .await?;

        if replace_slices {
            sqlx::query("DELETE FROM security_composition_slice WHERE profile_id = $1")
                .bind(profile_id)
                .execute(&mut *tx)
                .await?;
            for (kind, label, percent) in &slices {
                sqlx::query(
                    "INSERT INTO security_composition_slice (profile_id, kind, label, percent) \
                     VALUES ($1, $2, $3, $4)",
                )
                .bind(profile_id)
                .bind(kind)
                .bind(label)
                .bind(percent)
                .execute(&mut *tx)
                .await?;
            }
        }
        tx.commit().await?;
        Ok(true)
    }
}

fn collect_slices(
    target: &mut Vec<(&'static str, String, Decimal)>,
    kind: &'static str,
    source: &[AccountWeightedSlice],
) {
    let mut seen: HashSet<String> = HashSet::new();
    for slice in source {
        let Some(label) = slice.label.as_deref().filter(|l| !java_is_blank(l)) else {
            continue;
        };
        let Some(percent) = slice.percent else {
            continue;
        };
        if seen.insert(label.to_owned()) {
            target.push((kind, label.to_owned(), percent));
        }
    }
}

// ---------------------------------------------------------------------------
// Track T4 wealth history seam (T2 -> T4)
// ---------------------------------------------------------------------------

struct AccountsHistorySource;

#[async_trait]
impl HistorySource for AccountsHistorySource {
    async fn build_history(
        &self,
        state: &AppState,
        account_ids: &[i64],
        months: i32,
        split: bool,
        member_id: i64,
    ) -> AppResult<Vec<WealthNetWorthPoint>> {
        let mut conn = state.db.acquire().await?;
        let points = crate::accounts::history::build_history(
            state,
            &mut conn,
            account_ids,
            months,
            split,
            member_id,
        )
        .await?;
        Ok(points
            .into_iter()
            .map(|p| WealthNetWorthPoint {
                date: p.date,
                total: Some(p.total),
                invested: p.invested,
                pnl: p.pnl,
                accounts: p.accounts.map(|map| {
                    map.into_iter()
                        .map(|(id, ap)| {
                            (
                                id,
                                WealthAccountPoint {
                                    total: Some(ap.total),
                                    invested: ap.invested,
                                    pnl: ap.pnl,
                                },
                            )
                        })
                        .collect()
                }),
            })
            .collect())
    }
}

// ---------------------------------------------------------------------------
// Track T5 sync seams (T6 sidecars, T3 recurring, T4 prices/SCPI, T2 identities)
// ---------------------------------------------------------------------------

struct ConnectorSidecars;

#[async_trait]
impl SidecarSources for ConnectorSidecars {
    async fn resync_reporting(
        &self,
        state: &AppState,
        source: &'static str,
        member_id: i64,
    ) -> SourceSyncResult {
        use crate::connectors;
        match source {
            "revolut" => connectors::revolut::resync_reporting(state, member_id).await,
            "trade-republic" => {
                connectors::trade_republic::resync_reporting(state, member_id).await
            }
            "bourso" => connectors::bourso::resync_reporting(state, member_id).await,
            "bourse-direct" => connectors::bourse_direct::resync_reporting(state, member_id).await,
            "amundi" => connectors::amundi::resync_reporting(state, member_id).await,
            "fortuneo" => connectors::fortuneo::resync_reporting(state, member_id).await,
            "amex" => connectors::amex::resync_reporting(state, member_id).await,
            "degiro" => connectors::degiro::user_sync_reporting(state, member_id).await,
            "cmes" => connectors::cmes::resync_reporting(state, member_id).await,
            "cic" => connectors::cic::resync_reporting(state, member_id).await,
            "corum" => connectors::corum::resync_reporting(state, member_id).await,
            "natixis" => connectors::natixis::resync_reporting(state, member_id).await,
            "lbp" => connectors::lbp::resync_reporting(state, member_id).await,
            "bnp" => connectors::bnp::resync_reporting(state, member_id).await,
            _ => {
                NoSidecarSessions
                    .resync_reporting(state, source, member_id)
                    .await
            }
        }
    }
}

struct BudgetRecurringDetection;

#[async_trait]
impl RecurringDetection for BudgetRecurringDetection {
    async fn detect(&self, state: &AppState, member_id: i64, today: NaiveDate) -> AppResult<()> {
        crate::budget::detection::detect(state, member_id, today)
            .await
            .map(|_| ())
    }
}

struct MarketWalletPrices(Arc<PriceService>);

#[async_trait]
impl WalletPrices for MarketWalletPrices {
    async fn refresh_prices(
        &self,
        tickers: &BTreeSet<String>,
        _today: NaiveDate,
    ) -> HashMap<String, Decimal> {
        self.0.refresh_prices(tickers).await
    }
}

struct RecordedIbkrIdentities {
    db: PgPool,
    clock: AppClock,
}

#[async_trait]
impl IbkrSecurityIdentities for RecordedIbkrIdentities {
    async fn record(&self, isin_by_ticker: &[(String, String)]) {
        let map: BTreeMap<String, String> = isin_by_ticker.iter().cloned().collect();
        RecordedSecurityIdentities::new(self.db.clone())
            .record(&map, self.clock.now())
            .await;
    }
}

struct WealthLouveScpi;

#[async_trait]
impl LouveScpi for WealthLouveScpi {
    async fn reconcile(
        &self,
        conn: &mut PgConnection,
        member_id: i64,
        funds: &[LouveSyncedFund],
        unsupported: &[LouveUnsupportedFund],
        synced_at: DateTime<Utc>,
    ) -> AppResult<Vec<(String, LouveScpiOutcome)>> {
        let mapped_funds: Vec<WealthSyncedFund> = funds
            .iter()
            .map(|f| WealthSyncedFund {
                fund_code: f.fund_code.clone(),
                label: f.label.clone(),
                quantity: f.quantity,
                subscription_price: f.subscription_price,
                withdrawal_price: f.withdrawal_price,
                management_company: f.management_company.clone(),
                isin: f.isin.clone(),
                annual_distribution_per_share: f.annual_distribution_per_share,
                distribution_frequency: f.distribution_frequency.map(|d| {
                    match d {
                        LouveDistFreq::Monthly => "MONTHLY",
                        LouveDistFreq::Quarterly => "QUARTERLY",
                    }
                    .to_owned()
                }),
            })
            .collect();
        let mapped_unsupported: Vec<WealthUnsupportedFund> = unsupported
            .iter()
            .map(|u| WealthUnsupportedFund {
                fund_code: u.fund_code.clone(),
                label: u.label.clone(),
            })
            .collect();
        let outcomes = crate::wealth::scpi::reconciler::reconcile(
            conn,
            member_id,
            WealthScpiProvider::Louve,
            &mapped_funds,
            &mapped_unsupported,
            synced_at,
        )
        .await?;
        Ok(outcomes
            .into_iter()
            .map(|(code, o)| (code, map_louve_outcome(o)))
            .collect())
    }
}

fn map_louve_outcome(o: WealthScpiOutcome) -> LouveScpiOutcome {
    match o {
        WealthScpiOutcome::Created => LouveScpiOutcome::Created,
        WealthScpiOutcome::Linked => LouveScpiOutcome::Linked,
        WealthScpiOutcome::Updated => LouveScpiOutcome::Updated,
        WealthScpiOutcome::PriceIncomplete => LouveScpiOutcome::PriceIncomplete,
        WealthScpiOutcome::Ambiguous => LouveScpiOutcome::Ambiguous,
        WealthScpiOutcome::Ignored => LouveScpiOutcome::Ignored,
        WealthScpiOutcome::MissingAtProvider => LouveScpiOutcome::MissingAtProvider,
        WealthScpiOutcome::UnsupportedOwnership => LouveScpiOutcome::UnsupportedOwnership,
    }
}

// ---------------------------------------------------------------------------
// Track T6 connector seams (T4 prices, symbol catalog, SCPI, savings)
// ---------------------------------------------------------------------------

struct MarketPriceBackfill(Arc<PriceService>);

#[async_trait]
impl PriceBackfill for MarketPriceBackfill {
    async fn backfill(&self, tickers: &BTreeSet<String>, from: NaiveDate) -> anyhow::Result<usize> {
        Ok(self
            .0
            .backfill_historical_prices(tickers, from, false)
            .await)
    }
}

struct YahooSymbolCatalog(Arc<YahooFinance>);

#[async_trait]
impl SymbolCatalog for YahooSymbolCatalog {
    async fn has_quote(&self, ticker: &str) -> bool {
        self.0.has_quote(ticker).await
    }
}

struct WealthConnectorScpi;

#[async_trait]
impl ConnectorScpiFunds for WealthConnectorScpi {
    async fn reconcile(
        &self,
        conn: &mut PgConnection,
        member_id: i64,
        provider: ConnectorScpiProvider,
        funds: &[ConnectorSyncedFund],
        synced_at: DateTime<Utc>,
    ) -> AppResult<Vec<(String, ConnectorScpiOutcome)>> {
        let wealth_provider = match provider {
            ConnectorScpiProvider::Corum => WealthScpiProvider::Corum,
            ConnectorScpiProvider::Sofidy => WealthScpiProvider::Sofidy,
        };
        let mapped: Vec<WealthSyncedFund> = funds
            .iter()
            .map(|f| WealthSyncedFund {
                fund_code: f.fund_code.clone(),
                label: f.label.clone(),
                quantity: f.quantity,
                subscription_price: f.subscription_price,
                withdrawal_price: f.withdrawal_price,
                management_company: None,
                isin: None,
                annual_distribution_per_share: None,
                distribution_frequency: None,
            })
            .collect();
        let outcomes = crate::wealth::scpi::reconciler::reconcile(
            conn,
            member_id,
            wealth_provider,
            &mapped,
            &[],
            synced_at,
        )
        .await?;
        Ok(outcomes
            .into_iter()
            .map(|(code, o)| (code, map_connector_scpi_outcome(o)))
            .collect())
    }

    async fn close_unlisted(
        &self,
        conn: &mut PgConnection,
        member_id: i64,
        provider: ConnectorScpiProvider,
        listed: &HashSet<String>,
        now: DateTime<Utc>,
    ) -> AppResult<usize> {
        RecordedScpiFunds
            .close_unlisted(conn, member_id, provider, listed, now)
            .await
    }
}

fn map_connector_scpi_outcome(o: WealthScpiOutcome) -> ConnectorScpiOutcome {
    match o {
        WealthScpiOutcome::Created => ConnectorScpiOutcome::Created,
        WealthScpiOutcome::Linked => ConnectorScpiOutcome::Linked,
        WealthScpiOutcome::Updated => ConnectorScpiOutcome::Updated,
        WealthScpiOutcome::PriceIncomplete => ConnectorScpiOutcome::PriceIncomplete,
        WealthScpiOutcome::Ambiguous => ConnectorScpiOutcome::Ambiguous,
        WealthScpiOutcome::Ignored => ConnectorScpiOutcome::Ignored,
        WealthScpiOutcome::MissingAtProvider | WealthScpiOutcome::UnsupportedOwnership => {
            ConnectorScpiOutcome::MissingAtProvider
        }
    }
}

struct WealthSavingsAutoConfig;

#[async_trait]
impl SavingsAutoConfig for WealthSavingsAutoConfig {
    async fn configure_if_missing(
        &self,
        conn: &mut PgConnection,
        account_id: i64,
        account_type: AccountType,
    ) -> sqlx::Result<bool> {
        let (product, rate, ceiling) = match account_type {
            AccountType::LivretA => (
                SavingsProduct::LivretA,
                RegulatedRates::LIVRET_A,
                RegulatedRates::LIVRET_A_CEILING,
            ),
            AccountType::Ldds => (
                SavingsProduct::Ldds,
                RegulatedRates::LDDS,
                RegulatedRates::LDDS_CEILING,
            ),
            AccountType::Lep => (
                SavingsProduct::Lep,
                RegulatedRates::LEP,
                RegulatedRates::LEP_CEILING,
            ),
            _ => return Ok(false),
        };
        insert_if_absent(
            conn,
            &SavingsConfig {
                account_id,
                product,
                annual_rate: rate.parse().expect("regulated rate literal"),
                rate_basis: RateBasis::Net,
                tax_rate_pct: None,
                ceiling: Some(ceiling.parse().expect("regulated ceiling literal")),
            },
        )
        .await
    }
}
