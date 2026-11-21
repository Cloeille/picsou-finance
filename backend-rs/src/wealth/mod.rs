//! Goals, savings, peer loans, real estate, SCPI and dividends (track T4).

pub mod dividends;
pub mod goals;
pub mod history;
pub mod peer_loans;
pub mod real_estate;
pub mod savings;
pub mod scpi;

use std::sync::Arc;

use sqlx::PgPool;

use crate::clock::AppClock;
use crate::config::Config;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    goals::register(routes);
    savings::register(routes);
    peer_loans::register(routes);
    real_estate::register(routes);
    scpi::register(routes);
    dividends::register(routes);
}

/// The wealth domain's process state and its cross-track seams, held by AppState as state.wealth.
pub struct Wealth {
    /// HistoryService.buildHistory, owned by track T2.
    pub history: Arc<dyn history::HistorySource>,
    pub savings: savings::State,
    pub real_estate: real_estate::State,
    pub dividends: dividends::State,
}

impl Wealth {
    pub fn new(config: &Config, db: &PgPool, http: &reqwest::Client, clock: &AppClock) -> Self {
        Self {
            history: Arc::new(history::Unwired),
            savings: savings::State::default(),
            real_estate: real_estate::State::new(config, http, clock),
            dividends: dividends::State::new(config, db, http, clock),
        }
    }
}
