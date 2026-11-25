//! Interactive Brokers Flex sync: IbkrController, IbkrSyncService, IbkrFlexClient,
//! IbkrStatusWriter and the ibkr_connection repository.

pub mod connection;
mod flex;
mod handlers;
pub mod response;
pub mod seams;
mod service;
mod xml;

use axum::http::StatusCode;

pub use self::seams::Hooks;
pub use self::service::{connect, connection_status, delete_connection, resync_reporting, sync};
use crate::error::AppError;
use crate::routes::Routes;

/// The SourceSyncResult source name of this connector.
pub const SOURCE: &str = "ibkr";

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/ibkr/connect", handlers::connect)
        .get("/api/ibkr/status", handlers::status)
        .post("/api/ibkr/sync", handlers::sync)
        .delete("/api/ibkr/connection", handlers::clear_connection);
}

/// A SyncException: 422 with the message as detail, no code (IBKR never sets one).
fn sync_error(detail: impl Into<String>) -> AppError {
    AppError::status(StatusCode::UNPROCESSABLE_ENTITY, detail)
}

/// The message of a SyncException, None for any other failure.
fn sync_error_message(error: &AppError) -> Option<&str> {
    match error {
        AppError::Status { status, detail } if *status == StatusCode::UNPROCESSABLE_ENTITY => {
            Some(detail)
        }
        _ => None,
    }
}

/// An IBKR account id for the logs: its last three characters only.
fn masked_account(account_id: &str) -> String {
    let chars: Vec<char> = account_id.chars().collect();
    let visible: String = chars[chars.len().saturating_sub(3)..].iter().collect();
    format!("***{visible}")
}
