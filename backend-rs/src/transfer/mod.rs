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

use std::sync::Arc;

use http::StatusCode;

use crate::AppState;
use crate::clock::AppClock;
use crate::error::{AppError, AppResult, Problem};
use crate::http::client_ip::ClientIp;
use crate::http::rate_limit::policies;
use crate::ledger::instrument::TickerResult;
use crate::ledger::sync_values::java_trim;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    csv_import::register(routes);
    actual::register(routes);
    homebank::register(routes);
    finary::register(routes);
    account_export::register(routes);
    me_export::register(routes);
}

/// OpenFigiIsinConverter.resolve, owned by track T4. Without it Java falls back to the ISIN.
#[async_trait::async_trait]
pub trait IsinResolver: Send + Sync {
    async fn resolve(&self, isin: &str) -> TickerResult;
}

/// The converter's fallback when no lookup succeeds: the trimmed, upper-cased ISIN, no name.
pub struct IsinAsTicker;

#[async_trait::async_trait]
impl IsinResolver for IsinAsTicker {
    async fn resolve(&self, isin: &str) -> TickerResult {
        TickerResult {
            ticker: java_trim(isin).to_uppercase(),
            name: None,
        }
    }
}

/// The preview stores of the two-phase imports and the cross-track seams, held by AppState as
/// state.transfer.
pub struct Transfer {
    /// Track T4 replaces it with the OpenFIGI converter.
    pub isin: Arc<dyn IsinResolver>,
    pub csv: csv_import::Stores,
    pub actual: actual::Stores,
    pub homebank: homebank::Stores,
    pub finary: finary::Stores,
}

impl Transfer {
    pub fn new(clock: &AppClock) -> Self {
        Self {
            isin: Arc::new(IsinAsTicker),
            csv: csv_import::Stores::new(clock),
            actual: actual::Stores::new(clock),
            homebank: homebank::Stores::new(clock),
            finary: finary::Stores::new(clock),
        }
    }

    /// Evicts expired import previews across all four two-phase importers (wired into the 60 s
    /// cleanup scheduler by track T8).
    pub fn purge_expired(&self) {
        self.csv.purge_expired();
        self.actual.purge_expired();
        self.homebank.purge_expired();
        self.finary.purge_expired();
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
