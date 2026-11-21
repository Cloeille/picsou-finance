//! The Picsou backend: one crate serving the HTTP contract of the Java backend on the same
//! PostgreSQL schema. picsou::serve is what both the server binary and the desktop app call.

pub mod accounts;
pub mod actuator;
pub mod admin;
pub mod ai;
pub mod auth;
pub mod budget;
pub mod clock;
pub mod config;
pub mod connectors;
pub mod crypto;
pub mod db;
pub mod error;
pub mod family;
pub mod http;
pub mod identity;
pub mod jobs;
pub mod ledger;
pub mod logging;
pub mod market;
pub mod mcp;
pub mod routes;
pub mod setup_auth;
pub mod sync;
#[cfg(feature = "testing")]
pub mod testing;
pub mod transfer;
pub mod wealth;
pub mod wiring;

use std::ops::Deref;
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, OnceLock, Weak};
use std::time::Instant;

use axum::Router;
use sqlx::PgPool;
use tokio::net::TcpListener;

use crate::auth::BearerAuthenticator;
use crate::auth::access_key::UsageThrottle;
use crate::auth::jwt::Jwt;
use crate::auth::password::TimingEqualizer;
use crate::clock::{AppClock, Clock, SystemClock};
use crate::config::Config;
use crate::crypto::CryptoEncryption;
use crate::http::rate_limit::RateLimiter;

/// Set by the binary first thing, so readiness is logged from process start.
pub static PROCESS_START: OnceLock<Instant> = OnceLock::new();

/// Everything a handler needs, shared by every request.
#[derive(Clone)]
pub struct AppState(Arc<Inner>);

pub struct Inner {
    pub config: Config,
    pub db: PgPool,
    pub clock: AppClock,
    pub jwt: Jwt,
    pub crypto: CryptoEncryption,
    pub limits: RateLimiter,
    /// Outbound HTTP (providers, sidecars); tests point base URLs at wiremock through Config.
    pub http: reqwest::Client,
    pub setup_complete: AtomicBool,
    pub login_timing: TimingEqualizer,
    pub access_key_usage: UsageThrottle,
    pub bearer_authenticators: Vec<Arc<dyn BearerAuthenticator>>,
    /// The ledger's plug-in points (prices, connection locks, merchant knowledge base).
    pub ledger: ledger::Ledger,
    /// The identity module's in-memory state (SSO discovery and handshakes).
    pub identity: identity::Runtime,
    /// The admin module's in-memory state (Enable Banking call log, release check cache).
    pub admin: admin::Runtime,
    /// The budget module's plug-in points (AI categorizer, logo source) and job state.
    pub budget: budget::BudgetState,
    /// Track T2's seams into the other tracks (goals, intraday prices, OpenFIGI, bank logos,
    /// sync connections, property valuation).
    pub accounts: accounts::Seams,
    /// The import preview stores (track T7).
    pub transfer: transfer::Transfer,
    /// The sync module's plug-in points (cross-track seams).
    pub sync: sync::SyncHooks,
    /// The sidecar connectors' base URLs, shared key, sync executors and cross-track seams.
    pub connectors: connectors::Connectors,
    /// Price, FX and instrument data providers with their caches (track T4).
    pub market: market::Market,
    /// The wealth domain's state and cross-track seams (track T4).
    pub wealth: wealth::Wealth,
    /// Process-local scheduler state (track T8).
    pub jobs: jobs::JobsState,
}

impl Deref for AppState {
    type Target = Inner;

    fn deref(&self) -> &Inner {
        &self.0
    }
}

impl AppState {
    pub(crate) fn downgrade(&self) -> Weak<Inner> {
        Arc::downgrade(&self.0)
    }

    pub(crate) fn upgrade(weak: &Weak<Inner>) -> Option<Self> {
        weak.upgrade().map(Self)
    }

    pub fn new(config: Config, db: PgPool, clock: Arc<dyn Clock>) -> anyhow::Result<Self> {
        let clock = AppClock::new(clock, config.timezone);
        let connectors = connectors::Connectors::new(&config, db.clone())?;
        let crypto = CryptoEncryption::new(&config.crypto_key)?;
        let http = reqwest::Client::builder()
            .user_agent(concat!("picsou/", env!("CARGO_PKG_VERSION")))
            .connect_timeout(std::time::Duration::from_secs(10))
            .build()?;
        let market = market::Market::new(&config, &db, &http, &clock);
        let wealth = wealth::Wealth::new(&config, &db, &http, &clock);
        let jobs = jobs::JobsState::new(db.clone(), clock.clone());
        let mut ledger = ledger::Ledger::new(db.clone());
        ledger.prices = market.price_read();
        ledger.dividends = wealth.dividends.classifier();
        let mut inner = Inner {
            jwt: Jwt::new(&config.jwt, clock.clone())?,
            limits: RateLimiter::new(clock.clone()),
            transfer: transfer::Transfer::new(&clock),
            crypto,
            http,
            clock,
            config,
            ledger,
            market,
            wealth,
            jobs,
            budget: budget::BudgetState::default(),
            accounts: accounts::Seams::default(),
            sync: sync::SyncHooks::new(db.clone()),
            connectors,
            db,
            setup_complete: AtomicBool::new(false),
            login_timing: TimingEqualizer::default(),
            access_key_usage: UsageThrottle::default(),
            bearer_authenticators: identity::bearer_authenticators(),
            identity: identity::Runtime::default(),
            admin: admin::Runtime::default(),
        };
        let state_ref = wiring::wire_all(&mut inner);
        let state = Self(Arc::new(inner));
        state_ref.set(&state);
        Ok(state)
    }
}

/// The application router with every module's routes and the security layers.
pub fn app(state: AppState) -> Router {
    http::build(routes::all(), state)
}

/// Connects, migrates, seeds the environment admin and serves until SIGINT or SIGTERM.
pub async fn serve(config: Config, listener: TcpListener) -> anyhow::Result<()> {
    serve_with_shutdown(config, listener, Arc::new(SystemClock), shutdown_signal()).await
}

pub async fn serve_with_shutdown(
    config: Config,
    listener: TcpListener,
    clock: Arc<dyn Clock>,
    shutdown: impl Future<Output = ()> + Send + 'static,
) -> anyhow::Result<()> {
    let state = start(config, clock).await?;
    run(state, listener, shutdown).await
}

/// Serves a started state until `shutdown` resolves, then closes the database pool. The desktop
/// app calls it after `start`, so it knows the backend is ready without polling it.
pub async fn run(
    state: AppState,
    listener: TcpListener,
    shutdown: impl Future<Output = ()> + Send + 'static,
) -> anyhow::Result<()> {
    let started = PROCESS_START.get().copied().unwrap_or_else(Instant::now);
    let addr = listener.local_addr()?;
    let db = state.db.clone();
    let (stop_tx, stop_rx) = tokio::sync::watch::channel(false);
    let schedulers = jobs::spawn_schedulers(state.clone(), stop_rx);
    let app = app(state);
    tracing::info!(
        "Started Picsou on {addr}: ready {} ms after process start",
        started.elapsed().as_millis()
    );
    let stop_on_shutdown = stop_tx.clone();
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .with_graceful_shutdown(async move {
        shutdown.await;
        let _ = stop_on_shutdown.send(true);
    })
    .await?;
    let _ = stop_tx.send(true);
    schedulers.shutdown(std::time::Duration::from_secs(2)).await;
    // A background task can hold a connection past the last request: do not wait on it forever.
    let _ = tokio::time::timeout(std::time::Duration::from_secs(5), db.close()).await;
    tracing::info!("Picsou stopped");
    Ok(())
}

/// Connects to the database, applies the migrations and builds the state.
pub async fn start(config: Config, clock: Arc<dyn Clock>) -> anyhow::Result<AppState> {
    let db = db::connect(&config.datasource).await?;
    {
        let mut conn = db.acquire().await?;
        let applied = db::flyway::migrate(&mut conn).await?;
        if applied > 0 {
            tracing::info!("Successfully applied {applied} migrations to schema \"public\"");
        }
    }
    let state = AppState::new(config, db, clock)?;
    setup_auth::setup::seed_from_environment(&state)
        .await
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    identity::on_startup(&state).await?;
    admin::on_startup(&state).await?;
    jobs::on_startup(&state).await?;
    let timing = state.clone();
    tokio::spawn(async move {
        timing.login_timing.hash().await;
    });
    Ok(state)
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        if let Ok(mut signal) =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            signal.recv().await;
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        () = ctrl_c => {},
        () = terminate => {},
    }
}
