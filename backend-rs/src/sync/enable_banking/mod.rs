//! Enable Banking: SyncController, SyncService, requisitions and the bank connector.

mod balances;
pub mod connector;
mod entries;
pub mod error;
mod handlers;
pub mod hooks;
pub mod requisitions;
pub mod service;
mod wire;

pub use balances::AccountData;
pub use hooks::Hooks;
pub use service::{resync_all_reporting, retry_failed_reporting};

use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/sync/institutions", handlers::institutions)
        .get("/api/sync/countries", handlers::countries)
        .post("/api/sync/initiate", handlers::initiate)
        .post("/api/sync/complete", handlers::complete)
        .get("/api/sync/status", handlers::status)
        .post("/api/sync/{id}/retry", handlers::retry)
        .post("/api/sync/{id}/reconnect", handlers::reconnect)
        .delete("/api/sync/{id}", handlers::delete);
}
