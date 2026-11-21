//! Dividend forecast, received dividends and their classification (DividendController).

use std::sync::Arc;

use sqlx::PgPool;

use crate::clock::AppClock;
use crate::config::Config;
use crate::ledger::transfers::{DividendClassifier, NoDividendClassifier};
use crate::routes::Routes;

pub fn register(_routes: &mut Routes) {}

pub struct State {}

impl State {
    pub fn new(_config: &Config, _db: &PgPool, _http: &reqwest::Client, _clock: &AppClock) -> Self {
        Self {}
    }

    /// DividendReceiptService.classifyRecent, as the ledger's import pipeline calls it.
    pub fn classifier(&self) -> Arc<dyn DividendClassifier> {
        Arc::new(NoDividendClassifier)
    }
}
