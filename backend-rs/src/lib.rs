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

use std::ops::Deref;
use std::sync::Arc;
use std::sync::OnceLock;
use std::sync::atomic::AtomicBool;
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
    /// Whether the desktop shell set PICSOU_DESKTOP, which adds its keys to /actuator/info.
    pub desktop_info: bool,
    /// The ledger's plug-in points (prices, connection locks, merchant knowledge base).
    pub ledger: ledger::Ledger,
    /// The import preview stores (track T7).
    pub transfer: transfer::Transfer,
}

impl Deref for AppState {
    type Target = Inner;

    fn deref(&self) -> &Inner {
        &self.0
    }
}

impl AppState {
    pub fn new(
        config: Config,
        db: PgPool,
        clock: Arc<dyn Clock>,
        desktop_info: bool,
    ) -> anyhow::Result<Self> {
        let clock = AppClock::new(clock, config.timezone);
        let crypto = CryptoEncryption::new(&config.crypto_key)?;
        let http = reqwest::Client::builder()
            .user_agent(concat!("picsou/", env!("CARGO_PKG_VERSION")))
            .connect_timeout(std::time::Duration::from_secs(10))
            .build()?;
        Ok(Self(Arc::new(Inner {
            jwt: Jwt::new(&config.jwt, clock.clone())?,
            limits: RateLimiter::new(clock.clone()),
            transfer: transfer::Transfer::new(&clock),
            crypto,
            http,
            clock,
            config,
            ledger: ledger::Ledger::new(db.clone()),
            db,
            setup_complete: AtomicBool::new(false),
            login_timing: TimingEqualizer::default(),
            access_key_usage: UsageThrottle::default(),
            bearer_authenticators: identity::bearer_authenticators(),
            desktop_info,
        })))
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
    let started = PROCESS_START.get().copied().unwrap_or_else(Instant::now);
    let desktop_info = std::env::var_os("PICSOU_DESKTOP").is_some_and(|v| !v.is_empty());
    let state = start(config, clock, desktop_info).await?;
    let addr = listener.local_addr()?;
    let app = app(state);
    tracing::info!(
        "Started Picsou on {addr}: ready {} ms after process start",
        started.elapsed().as_millis()
    );
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown)
    .await?;
    tracing::info!("Picsou stopped");
    Ok(())
}

/// Connects to the database, applies the migrations and builds the state.
pub async fn start(
    config: Config,
    clock: Arc<dyn Clock>,
    desktop_info: bool,
) -> anyhow::Result<AppState> {
    let db = db::connect(&config.datasource).await?;
    {
        let mut conn = db.acquire().await?;
        let applied = db::flyway::migrate(&mut conn).await?;
        if applied > 0 {
            tracing::info!("Successfully applied {applied} migrations to schema \"public\"");
        }
    }
    let state = AppState::new(config, db, clock, desktop_info)?;
    setup_auth::setup::seed_from_environment(&state)
        .await
        .map_err(|e| anyhow::anyhow!("{e}"))?;
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
