//! Louve Invest over its official MCP server and OAuth 2.1 (no sidecar): LouveController,
//! LouveSyncService, the OAuth and MCP clients, the portfolio mapper and the client registry.
//!
//! Entry points for the scheduler and the member sync: service::resync_reporting (source
//! "louve") and service::sync; for an account deletion, service::delete_connection_in. The
//! account-deletion lock is the ledger's MemberRowLock: Java's lockMemberForAccountDeletion only
//! locks the family_member row.

mod adapter;
mod codes;
mod controller;
pub mod error;
mod mapper;
mod mcp;
mod oauth;
mod registry;
mod repo;
pub mod scpi;
pub mod service;
mod transport;

use std::sync::Arc;

use sqlx::PgPool;

pub use registry::{AppSettingTable, ClientSettings};
pub use service::resync_reporting;

use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/louve/auth/start", controller::start)
        .post("/api/louve/auth/complete", controller::complete)
        .post("/api/louve/sync", controller::sync)
        .get("/api/louve/status", controller::status)
        .delete("/api/louve/connection", controller::disconnect);
}

/// Louve's plug-in state in AppState (state.sync.louve).
pub struct Hooks {
    /// The app_setting rows of the client registrations (track T1 owns app_setting).
    pub settings: Arc<dyn ClientSettings>,
    /// ScpiFundReconciler for the LOUVE provider (track T4 owns SCPI).
    pub scpi: Arc<dyn scpi::ScpiFunds>,
    /// Redirects refused, 2 MB bodies: never state.http, which follows redirects.
    http: reqwest::Client,
    /// LouveClientRegistry's synchronized methods.
    registry_lock: tokio::sync::Mutex<()>,
}

impl Hooks {
    pub fn new(db: PgPool) -> Self {
        Self {
            settings: Arc::new(AppSettingTable::new(db)),
            scpi: Arc::new(scpi::SqlScpiFunds),
            http: transport::client(),
            registry_lock: tokio::sync::Mutex::new(()),
        }
    }
}
