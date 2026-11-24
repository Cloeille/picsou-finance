//! BanquePopulaireController and BanquePopulaireSyncService (track T6): Caisse d'Epargne's BPCE
//! keypad login plus the customer's regional bank, which is stored on the session and names the
//! imported accounts after that bank, with its logo from the Enable Banking catalog.

use std::sync::LazyLock;

use axum::extract::{Request, State};
use axum::response::Response;
use regex::Regex;
use serde_json::json;

use super::bpce::{self, Bank, Branding, SessionSeed, web};
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::client_ip::ClientIp;
use crate::http::rate_limit::policies;
use crate::ledger::sidecar::SidecarBank;
use crate::routes::Routes;

/// Every regional bank is French: narrows the catalog search to one country.
const LOGO_COUNTRY: &str = "FR";

/// BanquePopulaireRegion: the constant name the sidecar expects and sidecar_session.region
/// stores, and the regional bank's official name, stamped on account.provider.
const REGIONS: &[(&str, &str)] = &[
    (
        "ALSACE_LORRAINE_CHAMPAGNE",
        "Banque Populaire Alsace Lorraine Champagne",
    ),
    (
        "AQUITAINE_CENTRE_ATLANTIQUE",
        "Banque Populaire Aquitaine Centre Atlantique",
    ),
    (
        "AUVERGNE_RHONE_ALPES",
        "Banque Populaire Auvergne Rhône Alpes",
    ),
    (
        "BOURGOGNE_FRANCHE_COMTE",
        "Banque Populaire Bourgogne Franche-Comté",
    ),
    ("BRED", "BRED Banque Populaire"),
    ("GRAND_OUEST", "Banque Populaire Grand Ouest"),
    ("MEDITERRANEE", "Banque Populaire Méditerranée"),
    ("NORD", "Banque Populaire du Nord"),
    ("OCCITANE", "Banque Populaire Occitane"),
    ("RIVES_DE_PARIS", "Banque Populaire Rives de Paris"),
    ("SUD", "Banque Populaire du Sud"),
    ("VAL_DE_FRANCE", "Banque Populaire Val de France"),
];

fn branding(region: Option<&str>) -> Branding {
    match REGIONS.iter().find(|(name, _)| Some(*name) == region) {
        Some((_, institution)) => Branding {
            provider: institution,
            logo: Some((LOGO_COUNTRY, institution)),
        },
        None => Branding {
            provider: crate::ledger::providers::BANQUE_POPULAIRE,
            logo: None,
        },
    }
}

static BANK: Bank = Bank {
    codes: SidecarBank::BanquePopulaire,
    prefix: crate::ledger::providers::BANQUE_POPULAIRE_ID_PREFIX,
    auth_policy: &policies::BANQUE_POPULAIRE_AUTH,
    keypad_policy: &policies::BANQUE_POPULAIRE_KEYPAD,
    branding,
};

/// Letters and digits (a letter then seven digits, observed live).
static CUSTOMER_ID: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Za-z0-9]{1,20}$").expect("regex"));

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/banque-populaire/auth/initiate", initiate)
        .post("/api/banque-populaire/auth/keypad", keypad)
        .post("/api/banque-populaire/auth/complete", complete)
        .post("/api/banque-populaire/sync", sync_now)
        .get("/api/banque-populaire/status", status)
        .delete("/api/banque-populaire/session", clear);
}

/// Starts the login at the regional bank. An empty identifier reuses the remembered one with its
/// own region; the request's region is then ignored, so the remembered identifier never goes to
/// another regional bank.
async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    request: Request,
) -> AppResult<Response> {
    let fields = web::read_only_fields(&BANK, request, &["customerId", "region"]).await?;
    let customer_id = web::text(&BANK, &fields, "customerId")?;
    let raw_region = web::text(&BANK, &fields, "region")?;
    let login = if customer_id.is_empty() {
        None
    } else {
        if !CUSTOMER_ID.is_match(customer_id) {
            return Err(web::invalid_body(&BANK));
        }
        // By its exact constant name: no case folding, no guessing.
        let region = REGIONS
            .iter()
            .map(|(name, _)| *name)
            .find(|name| *name == raw_region)
            .ok_or_else(|| web::invalid_body(&BANK))?;
        Some((customer_id, region))
    };
    if let Some(response) = web::limited(&state, BANK.auth_policy, &ip, web::sign_in_limited(&BANK))
    {
        return Ok(response);
    }
    let response = initiate_auth(&state, login, auth.member_id).await?;
    Ok(web::no_store(response))
}

async fn initiate_auth(
    state: &AppState,
    login: Option<(&str, &str)>,
    member_id: i64,
) -> AppResult<bpce::InitiateResponse> {
    let stored = match login {
        Some(_) => None,
        None => Some(bpce::stored_login(state, &BANK, member_id).await?),
    };
    let (identifier, region) = match (&stored, login) {
        (Some(stored), _) => (
            stored.customer_id.as_str(),
            stored.region.clone().unwrap_or_default(),
        ),
        (None, Some((customer_id, region))) => (customer_id, region.to_owned()),
        (None, None) => unreachable!("a login without an identifier reads the stored one"),
    };
    let seed = SessionSeed {
        encrypted_credentials: Some(bpce::encrypt_customer_id(state, identifier)?),
        region: Some(region.clone()),
    };
    let call = async {
        let body = json!({ "customerId": identifier, "region": region });
        let result = bpce::sidecar_initiate(state, &BANK, &body).await;
        if let (Err(e), Some(stored)) = (&result, &stored)
            && bpce::rejects_identifier(&BANK, e)
        {
            bpce::forget_customer_id(state, &BANK, member_id, &stored.ciphertext)
                .await
                .map_err(|_| e.clone())?;
        }
        result
    };
    bpce::initiate(state, &BANK, member_id, seed, call).await
}

async fn keypad(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    request: Request,
) -> AppResult<Response> {
    web::keypad(&state, &BANK, auth.member_id, &ip, request).await
}

async fn complete(
    State(state): State<AppState>,
    auth: Auth,
    request: Request,
) -> AppResult<Response> {
    web::complete(&state, &BANK, auth.member_id, request).await
}

async fn sync_now(State(state): State<AppState>, auth: Auth, ip: ClientIp) -> AppResult<Response> {
    web::sync(&state, &BANK, auth.member_id, &ip).await
}

async fn status(State(state): State<AppState>, auth: Auth) -> AppResult<Response> {
    web::status(&state, &BANK, auth.member_id).await
}

async fn clear(State(state): State<AppState>, auth: Auth) -> AppResult<Response> {
    web::clear(&state, &BANK, auth.member_id).await
}

/// Turns this bank's persisted in-flight syncs into retryable failures at startup.
pub async fn recover_interrupted_syncs(state: &AppState) -> sqlx::Result<u64> {
    bpce::recover_interrupted(state, &BANK).await
}
