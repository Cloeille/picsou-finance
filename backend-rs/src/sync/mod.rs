//! Enable Banking, exchanges, wallets, IBKR, SimpleFIN, Wise, Louve, Finary sync (track T5).

pub mod enable_banking;
pub mod exchanges;
pub mod finary;
pub mod ibkr;
pub mod louve;
pub mod member_sync;
pub mod simplefin;
pub mod wallets;
pub mod wise;

use sqlx::PgPool;

use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    enable_banking::register(routes);
    exchanges::register(routes);
    wallets::register(routes);
    ibkr::register(routes);
    simplefin::register(routes);
    wise::register(routes);
    louve::register(routes);
    finary::register(routes);
}

/// The sync module's cross-track seams, held by AppState as state.sync. Each starts with the
/// behavior Java has when the other side has nothing; the coordinator wires the real ones.
pub struct SyncHooks {
    pub simplefin: simplefin::Hooks,
    pub enable_banking: enable_banking::Hooks,
    pub louve: louve::Hooks,
    pub wise: wise::Hooks,
}

impl SyncHooks {
    pub fn new(db: PgPool) -> Self {
        Self {
            simplefin: simplefin::Hooks::new(),
            enable_banking: enable_banking::Hooks::new(),
            louve: louve::Hooks::new(db),
            wise: wise::Hooks::new(),
        }
    }
}
