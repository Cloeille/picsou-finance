//! Dividend forecast, received dividends and their classification (DividendController,
//! DividendForecastService, DividendReceiptService).

pub mod forecast;
pub mod model;
pub mod receipt;

use std::sync::Arc;

use axum::extract::State as AxumState;
use sqlx::PgPool;

use crate::AppState;
use crate::auth::Auth;
use crate::clock::AppClock;
use crate::config::Config;
use crate::error::AppResult;
use crate::ledger::transfers::DividendClassifier;
use crate::routes::Routes;
use crate::wealth::params::{self, Query};

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/dividends/upcoming", upcoming)
        .get("/api/dividends/received", received);
}

pub struct State {}

impl State {
    pub fn new(_config: &Config, _db: &PgPool, _http: &reqwest::Client, _clock: &AppClock) -> Self {
        Self {}
    }

    /// DividendReceiptService.classifyRecent, as the ledger's import pipeline calls it.
    pub fn classifier(&self) -> Arc<dyn DividendClassifier> {
        Arc::new(receipt::ReceiptClassifier)
    }
}

/// Payments from today through the horizon (default 60 days, clamped to 1..366).
async fn upcoming(
    AxumState(state): AxumState<AppState>,
    auth: Auth,
    query: Query,
) -> AppResult<axum::Json<Vec<forecast::UpcomingDividendResponse>>> {
    let horizon = params::or_default(&query, "horizonDays", 60)?;
    let account_id = params::optional(&query, "accountId")?;
    let today = state.clock.today();
    forecast::upcoming(&state, auth.member_id, today, horizon, account_id)
        .await
        .map(axum::Json)
}

/// Dividends received over the last days (default 30, clamped to 1..366).
async fn received(
    AxumState(state): AxumState<AppState>,
    auth: Auth,
    query: Query,
) -> AppResult<axum::Json<Vec<receipt::ReceivedDividendResponse>>> {
    let days = params::or_default(&query, "days", 30)?;
    receipt::received(&state, auth.member_id, state.clock.today(), days)
        .await
        .map(axum::Json)
}
