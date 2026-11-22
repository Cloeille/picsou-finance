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

use std::sync::{Arc, RwLock};

use crate::db::Db;

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
    /// Track T6 replaces it with the bank sidecars' resyncReporting.
    sidecars: RwLock<Arc<dyn member_sync::SidecarSources>>,
    pub finary: finary::Hooks,
    pub exchanges: exchanges::Hooks,
    pub ibkr: ibkr::Hooks,
    pub wallets: wallets::Hooks,
    pub simplefin: simplefin::Hooks,
    pub enable_banking: enable_banking::Hooks,
    pub louve: louve::Hooks,
    pub wise: wise::Hooks,
}

impl SyncHooks {
    pub fn new(db: Db) -> Self {
        Self {
            sidecars: RwLock::new(Arc::new(member_sync::NoSidecarSessions)),
            finary: finary::Hooks::new(),
            exchanges: exchanges::Hooks::new(),
            ibkr: ibkr::Hooks::default(),
            wallets: wallets::Hooks::new(),
            simplefin: simplefin::Hooks::new(),
            enable_banking: enable_banking::Hooks::new(),
            louve: louve::Hooks::new(db),
            wise: wise::Hooks::new(),
        }
    }

    pub fn sidecars(&self) -> Arc<dyn member_sync::SidecarSources> {
        self.sidecars
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }

    /// Track T8 plugs the bank sidecars' resyncReporting in here.
    pub fn set_sidecars(&self, sidecars: Arc<dyn member_sync::SidecarSources>) {
        *self.sidecars.write().unwrap_or_else(|e| e.into_inner()) = sidecars;
    }
}
