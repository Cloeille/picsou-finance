//! Budgets, cashflow, categories, rules, recurring series, merchants (track T3).
//!
//! Plug-in points live in `state.budget` ([BudgetState]): the AI categorizer (track T8) and the
//! merchant logo source. Each starts with what Java does when the provider is absent.

pub mod ai_job;
pub mod allocation;
pub mod cashflow;
pub mod categories;
pub mod categorization;
pub mod cycle;
pub mod detection;
pub mod envelopes;
pub mod flow;
pub mod jdec;
pub mod logos;
pub mod params;
pub mod recurring;
pub mod repo;
pub mod rules;
pub mod settings;

use std::sync::{Arc, RwLock};

use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    envelopes::register(routes);
    settings::register(routes);
    cashflow::register(routes);
    allocation::register(routes);
    flow::register(routes);
    categories::register(routes);
    rules::register(routes);
    categorization::register(routes);
    recurring::register(routes);
    logos::register(routes);
}

/// A replaceable implementation: the default answers as Java does without the provider, and
/// the owning track (or a test) swaps in its own with [Seam::set].
pub struct Seam<T: ?Sized>(RwLock<Arc<T>>);

impl<T: ?Sized> Seam<T> {
    pub fn new(value: Arc<T>) -> Self {
        Self(RwLock::new(value))
    }

    pub fn get(&self) -> Arc<T> {
        self.0.read().unwrap_or_else(|e| e.into_inner()).clone()
    }

    pub fn set(&self, value: Arc<T>) {
        *self.0.write().unwrap_or_else(|e| e.into_inner()) = value;
    }
}

/// The budget module's shared state, held by AppState as `state.budget`.
pub struct BudgetState {
    /// TransactionCategorizerPort and AiConfigProvider; track T8 replaces the default.
    pub ai: Seam<dyn ai_job::AiCategorizer>,
    /// MerchantLogoPort (DuckDuckGo's icon service by default).
    pub logos: Seam<dyn logos::LogoSource>,
    pub(crate) ai_jobs: ai_job::Jobs,
    pub(crate) logo_cache: logos::LogoCache,
}

impl Default for BudgetState {
    fn default() -> Self {
        Self {
            ai: Seam::new(Arc::new(ai_job::NoAiProvider)),
            logos: Seam::new(Arc::new(logos::DuckDuckGo::default())),
            ai_jobs: ai_job::Jobs::default(),
            logo_cache: logos::LogoCache::default(),
        }
    }
}
