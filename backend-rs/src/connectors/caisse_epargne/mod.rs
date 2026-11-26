//! CaisseEpargneController and CaisseEpargneSyncService (track T6): the BPCE keypad login with
//! the customer's identifier, and the on-demand import of the shared BPCE engine.

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

static BANK: Bank = Bank {
    codes: SidecarBank::CaisseEpargne,
    prefix: crate::ledger::providers::CAISSE_EPARGNE_ID_PREFIX,
    auth_policy: &policies::CAISSE_EPARGNE_AUTH,
    keypad_policy: &policies::CAISSE_EPARGNE_KEYPAD,
    branding: |_| Branding {
        provider: crate::ledger::providers::CAISSE_EPARGNE,
        logo: None,
    },
};

static CUSTOMER_ID: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[0-9]{1,20}$").expect("regex"));

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/caisse-epargne/auth/initiate", initiate)
        .post("/api/caisse-epargne/auth/keypad", keypad)
        .post("/api/caisse-epargne/auth/complete", complete)
        .post("/api/caisse-epargne/sync", sync_now)
        .get("/api/caisse-epargne/status", status)
        .delete("/api/caisse-epargne/session", clear);
}

/// Answers the bank's keypad for the user to click. The body is validated first, so a malformed
/// request spends no attempt; an empty identifier reuses the remembered one.
async fn initiate(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    request: Request,
) -> AppResult<Response> {
    let fields = web::read_only_fields(&BANK, request, &["customerId"]).await?;
    let customer_id = web::text(&BANK, &fields, "customerId")?;
    if !customer_id.is_empty() && !CUSTOMER_ID.is_match(customer_id) {
        return Err(web::invalid_body(&BANK));
    }
    if let Some(response) = web::limited(&state, BANK.auth_policy, &ip, web::sign_in_limited(&BANK))
    {
        return Ok(response);
    }
    let customer_id = Some(customer_id).filter(|c| !c.is_empty());
    let response = initiate_auth(&state, customer_id, auth.member_id).await?;
    Ok(web::no_store(response))
}

/// One call to the sidecar, no retry. A remembered identifier the bank rejects is forgotten, so
/// it is never submitted again.
async fn initiate_auth(
    state: &AppState,
    customer_id: Option<&str>,
    member_id: i64,
) -> AppResult<bpce::InitiateResponse> {
    let stored = match customer_id {
        Some(_) => None,
        None => Some(bpce::stored_login(state, &BANK, member_id).await?),
    };
    let identifier = stored.as_ref().map_or_else(
        || customer_id.unwrap_or_default(),
        |s| s.customer_id.as_str(),
    );
    let seed = SessionSeed {
        encrypted_credentials: Some(bpce::encrypt_customer_id(state, identifier)?),
        region: None,
    };
    let call = async {
        let result =
            bpce::sidecar_initiate(state, &BANK, &json!({ "customerId": identifier })).await;
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
