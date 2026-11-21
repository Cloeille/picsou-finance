//! The bank sidecar controllers and the generic sidecar client (track T6). Each bank module
//! registers its controller's routes; `sidecar` holds what they share.

pub mod amex;
pub mod amundi;
pub mod banque_populaire;
pub mod bnp;
pub mod bourse_direct;
pub mod bourso;
pub mod caisse_epargne;
pub mod cic;
pub mod cmes;
pub mod corum;
pub mod degiro;
pub mod fortuneo;
pub mod lbp;
pub mod natixis;
pub mod revolut;
pub mod seams;
pub mod sidecar;
pub mod sofidy;
pub mod trade_republic;

use std::collections::HashMap;

use sqlx::PgPool;

use self::seams::Seams;
use self::sidecar::jobs::Jobs;
use crate::AppState;
use crate::config::Config;
use crate::ledger::sidecar::SidecarBank;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    amex::register(routes);
    amundi::register(routes);
    banque_populaire::register(routes);
    bnp::register(routes);
    bourse_direct::register(routes);
    bourso::register(routes);
    caisse_epargne::register(routes);
    cic::register(routes);
    cmes::register(routes);
    corum::register(routes);
    degiro::register(routes);
    fortuneo::register(routes);
    lbp::register(routes);
    natixis::register(routes);
    revolut::register(routes);
    sofidy::register(routes);
    trade_republic::register(routes);
}

/// The connectors' shared state, held by AppState as state.connectors.
pub struct Connectors {
    bases: HashMap<&'static str, String>,
    api_key: Option<String>,
    pub jobs: Jobs,
    pub seams: Seams,
}

impl Connectors {
    /// Validates every sidecar URL, so a deployment that would send credentials in cleartext over
    /// a network never starts.
    pub fn new(config: &Config, db: PgPool) -> anyhow::Result<Self> {
        let mut bases = HashMap::new();
        for (service, url) in &config.sidecars {
            let name = SidecarBank::ALL
                .iter()
                .find(|bank| bank.service_name() == *service)
                .map_or_else(|| sidecar_name(service), |bank| bank.label());
            let base = sidecar::base_url::validate(name, url).map_err(anyhow::Error::msg)?;
            bases.insert(*service, base);
        }
        Ok(Self {
            bases,
            api_key: config.sidecar_api_key.clone(),
            jobs: Jobs::default(),
            seams: Seams::new(db),
        })
    }

    /// The validated base URL of a sidecar by its compose service name.
    pub fn base_url(&self, service: &str) -> &str {
        self.bases.get(service).map_or("", String::as_str)
    }

    /// APP_SIDECAR_API_KEY, trimmed; None when blank, so no empty header is ever sent.
    pub fn api_key(&self) -> Option<&str> {
        self.api_key.as_deref()
    }
}

fn sidecar_name(service: &str) -> &'static str {
    match service {
        "degiro-auth" => "DEGIRO",
        "revolut-auth" => "Revolut",
        _ => "connector",
    }
}

/// SidecarSyncRecovery, LbpSyncRecovery and NatixisSyncRecovery, for track T8 to run at startup:
/// every persisted QUEUED or RUNNING session becomes a retryable failure. A recovery that fails
/// aborts startup, since that session would block every later sync of its bank.
pub async fn recover_interrupted_syncs(state: &AppState) -> anyhow::Result<()> {
    amex::recover_interrupted_syncs(state)
        .await
        .map_err(|e| recovery_failed("amex", e))?;
    amundi::recover_interrupted_syncs(state)
        .await
        .map_err(|e| recovery_failed("amundi", e))?;
    banque_populaire::recover_interrupted_syncs(state)
        .await
        .map_err(|e| recovery_failed("banque_populaire", e))?;
    bnp::recover_interrupted_syncs(state)
        .await
        .map_err(|e| recovery_failed("bnp", e))?;
    bourse_direct::recover_interrupted_syncs(state)
        .await
        .map_err(|e| recovery_failed("bourse_direct", e))?;
    bourso::recover_interrupted_syncs(state)
        .await
        .map_err(|e| recovery_failed("bourso", e))?;
    caisse_epargne::recover_interrupted_syncs(state)
        .await
        .map_err(|e| recovery_failed("caisse_epargne", e))?;
    cic::recover_interrupted_syncs(state)
        .await
        .map_err(|e| recovery_failed("cic", e))?;
    cmes::recover_interrupted_syncs(state)
        .await
        .map_err(|e| recovery_failed("cmes", e))?;
    fortuneo::recover_interrupted_syncs(state)
        .await
        .map_err(|e| recovery_failed("fortuneo", e))?;
    trade_republic::recover_interrupted_syncs(state)
        .await
        .map_err(|e| recovery_failed("trade_republic", e))?;
    lbp::recover_interrupted_syncs(state)
        .await
        .map_err(|e| recovery_failed("lbp", e))?;
    natixis::recover_interrupted_syncs(state)
        .await
        .map_err(|e| recovery_failed("natixis", e))?;
    Ok(())
}

fn recovery_failed(bank: &str, error: sqlx::Error) -> anyhow::Error {
    tracing::error!("{bank} sync recovery failed; aborting application startup: {error}");
    anyhow::anyhow!("{bank} sync recovery failed: {error}")
}
