//! Imports and exports (track T7): CSV investment transactions, Actual Budget, HomeBank and
//! Finary files, the per-account XLSX workbook and the full member export.

pub mod account_export;
pub mod actual;
pub mod csv_import;
pub mod finary;
pub mod homebank;
pub mod me_export;
pub mod preview_store;
pub mod upload;

use http::StatusCode;

use crate::AppState;
use crate::clock::AppClock;
use crate::error::{AppError, AppResult, Problem};
use crate::http::client_ip::ClientIp;
use crate::http::rate_limit::policies;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    csv_import::register(routes);
    actual::register(routes);
    homebank::register(routes);
    finary::register(routes);
    account_export::register(routes);
    me_export::register(routes);
}

/// The preview stores of the two-phase imports, held by AppState as state.transfer.
pub struct Transfer {
    pub csv: csv_import::Stores,
    pub actual: actual::Stores,
    pub homebank: homebank::Stores,
    pub finary: finary::Stores,
}

impl Transfer {
    pub fn new(clock: &AppClock) -> Self {
        Self {
            csv: csv_import::Stores::new(clock),
            actual: actual::Stores::new(clock),
            homebank: homebank::Stores::new(clock),
            finary: finary::Stores::new(clock),
        }
    }
}

/// The shared per-IP sync bucket every file import draws from.
pub fn import_rate_limit(state: &AppState, ip: &ClientIp) -> AppResult<()> {
    if state
        .limits
        .try_consume(&policies::SYNC, &ip.as_java_string())
    {
        return Ok(());
    }
    Err(AppError::Problem(Problem::with_detail(
        StatusCode::TOO_MANY_REQUESTS,
        "Too many import requests. Please wait a moment.",
    )))
}
