//! SyncService: connecting a bank through Enable Banking, refreshing it, and the requisitions.

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use serde::Serialize;

use super::balances::AccountData;
use super::connector::{self, InstitutionData};
use super::error::EbError;
use super::requisitions::{self, NewRequisition, Requisition, RequisitionStatus};
use super::wire::fingerprint;
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::bank_import::{self, DEFAULT_INITIAL_HISTORY_DAYS, TransactionData};
use crate::ledger::members;
use crate::ledger::model::{Account, AccountType};
use crate::ledger::prices::PriceNeeds;
use crate::ledger::sidecar::{SourceSyncResult, SyncStatus};
use crate::ledger::valuation::{self, SnapshotContext};
use chrono::NaiveDate;
use rust_decimal::Decimal;

pub const SOURCE: &str = "enable-banking";
pub const RETRY_SOURCE: &str = "enable-banking-retry";
const ACCOUNT_COLOR: &str = "#6366f1";

/// SyncService.InitiateResponse.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InitiateResponse {
    pub requisition_id: Option<String>,
    pub auth_link: String,
}

fn new_state() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// Step 1: the bank's authorization link, and a CREATED requisition holding the state nonce.
pub async fn initiate_connection(
    state: &AppState,
    institution_id: &str,
    institution_name: &str,
    member_id: i64,
) -> Result<InitiateResponse, EbError> {
    members::find_by_id(&state.db, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Family member not found".to_owned()))?;
    let nonce = new_state();
    let result = connector::initiate(state, institution_id, &nonce).await?;
    let logo_url = logo_url_or_null(
        state,
        connector::country_of(institution_id).as_deref(),
        Some(institution_id),
        Some(institution_name),
    )
    .await;
    let requisition_id = result.requisition_id.clone().ok_or_else(|| {
        EbError::Other(AppError::internal(
            "Enable Banking /auth returned no authorization id",
        ))
    })?;
    requisitions::insert(
        &state.db,
        &NewRequisition {
            member_id,
            requisition_id: &requisition_id,
            institution_id,
            institution_name,
            logo_url,
            auth_link: &result.auth_link,
            oauth_state: &nonce,
        },
        state.clock.now(),
    )
    .await?;
    Ok(InitiateResponse {
        requisition_id: result.requisition_id,
        auth_link: result.auth_link,
    })
}

/// Step 2: exchange the OAuth code, fetch the balances and upsert the accounts. The member is
/// the one the state nonce was issued for, not the caller's (admin impersonation).
pub async fn complete_connection(
    state: &AppState,
    code: &str,
    oauth_state: Option<&str>,
    member_id: i64,
) -> Result<Vec<serde_json::Value>, EbError> {
    let requisition = resolve_callback(state, oauth_state, member_id).await?;
    let target = requisition.member_id;
    let session_id = match connector::exchange_code(state, code).await {
        Ok(session_id) => session_id,
        Err(EbError::Sync(e)) if e.message.contains("ALREADY_AUTHORIZED") => {
            tracing::info!(
                "Code already used, refreshing latest linked session for {}",
                requisition.institution_name.as_deref().unwrap_or("null")
            );
            return resync_latest(state, target, &requisition.institution_id).await;
        }
        Err(EbError::Sync(e)) => {
            // The nonce is kept so a transient exchange failure can replay the same callback.
            requisitions::mark_failed(&state.db, requisition.id, target, state.clock.now()).await?;
            return Err(EbError::Sync(e));
        }
        Err(other) => return Err(other),
    };
    // The code is spent once exchanged: its session survives whatever fails next.
    requisitions::checkpoint_session(
        &state.db,
        requisition.id,
        target,
        &session_id,
        state.clock.now(),
    )
    .await?;
    match link_session(state, requisition.clone(), &session_id).await {
        Ok(responses) => Ok(responses),
        Err(e) => {
            requisitions::mark_failed(&state.db, requisition.id, target, state.clock.now()).await?;
            match e {
                EbError::Sync(sync) => Err(EbError::Sync(sync)),
                EbError::Other(cause) => {
                    tracing::warn!("Enable Banking account writes failed: {cause}");
                    Err(EbError::sync(
                        "Synchronized bank accounts could not be saved. Please retry the connection.",
                    ))
                }
            }
        }
    }
}

async fn link_session(
    state: &AppState,
    mut requisition: Requisition,
    session_id: &str,
) -> Result<Vec<serde_json::Value>, EbError> {
    let data = connector::fetch_balances(state, session_id).await?;
    if data.is_empty() {
        requisitions::mark_failed(
            &state.db,
            requisition.id,
            requisition.member_id,
            state.clock.now(),
        )
        .await?;
        tracing::info!(
            "Enable Banking requisition {} ({}) returned no accounts during completion — marking retryable",
            requisition.id,
            requisition.institution_name.as_deref().unwrap_or("null")
        );
        return Ok(Vec::new());
    }
    let asked = ask_first(state, &data, requisition.member_id, session_id).await?;
    let mut tx = state.db.begin().await?;
    let responses = upsert_all(state, &mut tx.as_handle(), &data, &requisition, &asked).await?;
    let now = state.clock.now();
    requisition.requisition_id = session_id.to_owned();
    requisition.oauth_state = None;
    requisition.status = RequisitionStatus::Linked;
    requisition.last_synced_at = Some(now);
    requisitions::save(&mut tx, &requisition, now).await?;
    tx.commit().await?;
    detect_recurring(state, requisition.member_id).await;
    tracing::info!(
        "Completed Enable Banking sync for {}: {} accounts linked",
        requisition.institution_name.as_deref().unwrap_or("null"),
        responses.len()
    );
    Ok(responses)
}

pub async fn search_institutions(
    state: &AppState,
    query: &str,
    country: &str,
) -> Result<Vec<InstitutionData>, EbError> {
    connector::search_institutions(state, Some(query), Some(country)).await
}

pub async fn list_countries(state: &AppState) -> Result<Vec<String>, EbError> {
    connector::list_countries(state).await
}

pub async fn all_requisitions(state: &AppState, member_id: i64) -> AppResult<Vec<Requisition>> {
    Ok(requisitions::find_all_by_member(&state.db, member_id).await?)
}

/// Refetches the accounts of a requisition with its stored session id. A SyncException marks it
/// FAILED, and that status is kept.
pub async fn retry_sync(
    state: &AppState,
    id: i64,
    member_id: i64,
) -> Result<Vec<serde_json::Value>, EbError> {
    let mut req = requisitions::find_by_id_and_member(&state.db, id, member_id)
        .await?
        .ok_or_else(requisitions::not_found)?;
    tracing::info!(
        "Retrying sync for {} (requisition #{})",
        req.institution_name.as_deref().unwrap_or("null"),
        req.id
    );
    ensure_logo_url(state, &mut req).await;
    let data = match connector::fetch_balances(state, &req.requisition_id).await {
        Ok(data) => data,
        Err(EbError::Sync(e)) => {
            req.status = RequisitionStatus::Failed;
            requisitions::save(&state.db, &req, state.clock.now()).await?;
            return Err(EbError::Sync(e));
        }
        Err(other) => return Err(other),
    };
    let asked = ask_first(state, &data, member_id, &req.requisition_id).await?;
    let mut tx = state.db.begin().await?;
    let responses = upsert_all(state, &mut tx.as_handle(), &data, &req, &asked).await?;
    if mark_retryable_if_empty(&mut req, &data, "retry") {
        requisitions::save(&mut tx, &req, state.clock.now()).await?;
        tx.commit().await?;
        return Ok(responses);
    }
    let now = state.clock.now();
    req.status = RequisitionStatus::Linked;
    req.last_synced_at = Some(now);
    requisitions::save(&mut tx, &req, now).await?;
    tx.commit().await?;
    detect_recurring(state, member_id).await;
    tracing::info!(
        "Retry sync OK for {}: {} accounts linked",
        req.institution_name.as_deref().unwrap_or("null"),
        responses.len()
    );
    Ok(responses)
}

/// Re-initiates the OAuth flow of a dead requisition, reusing the row: accounts stay attached
/// because the upsert matches on the external account id.
pub async fn reconnect(
    state: &AppState,
    id: i64,
    member_id: i64,
) -> Result<InitiateResponse, EbError> {
    let mut req = requisitions::find_by_id_and_member(&state.db, id, member_id)
        .await?
        .ok_or_else(requisitions::not_found)?;
    // A LINKED requisition holds a working session id that scheduled syncs still need.
    if req.status == RequisitionStatus::Linked {
        return Err(EbError::sync(
            "This bank connection is still active. Use sync/retry instead, or delete it to start over.",
        ));
    }
    let nonce = new_state();
    let result = connector::initiate(state, &req.institution_id, &nonce).await?;
    req.requisition_id = result.requisition_id.clone().ok_or_else(|| {
        EbError::Other(AppError::internal(
            "Enable Banking /auth returned no authorization id",
        ))
    })?;
    req.auth_link = Some(result.auth_link.clone());
    req.status = RequisitionStatus::Created;
    req.oauth_state = Some(nonce);
    requisitions::save(&state.db, &req, state.clock.now()).await?;
    tracing::info!(
        "Re-initiated Enable Banking auth for {} (requisition {id})",
        req.institution_name.as_deref().unwrap_or("null")
    );
    Ok(InitiateResponse {
        requisition_id: result.requisition_id,
        auth_link: result.auth_link,
    })
}

pub async fn delete_requisition(state: &AppState, id: i64, member_id: i64) -> AppResult<()> {
    requisitions::find_by_id_and_member(&state.db, id, member_id)
        .await?
        .ok_or_else(requisitions::not_found)?;
    requisitions::delete(&state.db, id).await?;
    tracing::info!("Deleted requisition {id}");
    Ok(())
}

/// SyncService.retryFailedReporting: retries every FAILED requisition of the member.
pub async fn retry_failed_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    let failed = match requisitions::find_by_status_and_member(
        &state.db,
        RequisitionStatus::Failed,
        member_id,
    )
    .await
    {
        Ok(failed) => failed,
        Err(e) => return unexpected(RETRY_SOURCE, member_id, &e),
    };
    if failed.is_empty() {
        return result(
            RETRY_SOURCE,
            SyncStatus::SkippedNotConnected,
            "No FAILED requisition",
        );
    }
    let mut had_failure = false;
    for req in failed {
        if let Err(e) = retry_sync(state, req.id, member_id).await {
            tracing::warn!(
                "Scheduled retry failed for {} (requisition #{}): {}",
                req.institution_name.as_deref().unwrap_or("null"),
                req.id,
                e.message()
            );
            had_failure = true;
        }
    }
    if had_failure {
        return result(
            RETRY_SOURCE,
            SyncStatus::Failed,
            "One or more retries failed",
        );
    }
    result(RETRY_SOURCE, SyncStatus::Synced, "")
}

/// SyncService.resyncAllReporting: refreshes every LINKED requisition of the member; one that
/// fails goes FAILED without stopping the others.
pub async fn resync_all_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    let linked = match requisitions::find_by_status_and_member(
        &state.db,
        RequisitionStatus::Linked,
        member_id,
    )
    .await
    {
        Ok(linked) => linked,
        Err(e) => return unexpected(SOURCE, member_id, &e),
    };
    if linked.is_empty() {
        return result(
            SOURCE,
            SyncStatus::SkippedNotConnected,
            "No linked requisition",
        );
    }
    let mut had_failure = false;
    for mut req in linked {
        if let Err(e) = resync_one(state, &mut req).await {
            req.status = RequisitionStatus::Failed;
            if let Err(save) = requisitions::save(&state.db, &req, state.clock.now()).await {
                tracing::warn!("Could not mark requisition #{} failed: {save}", req.id);
            }
            tracing::warn!(
                "Auto-resync failed for {}: {}",
                req.institution_name.as_deref().unwrap_or("null"),
                e.message()
            );
            had_failure = true;
        }
    }
    if had_failure {
        return result(SOURCE, SyncStatus::Failed, "One or more syncs failed");
    }
    result(SOURCE, SyncStatus::Synced, "")
}

async fn resync_one(state: &AppState, req: &mut Requisition) -> Result<(), EbError> {
    ensure_logo_url(state, req).await;
    let data = connector::fetch_balances(state, &req.requisition_id).await?;
    let asked = ask_first(state, &data, req.member_id, &req.requisition_id).await?;
    let mut tx = state.db.begin().await?;
    if mark_retryable_if_empty(req, &data, "resync") {
        requisitions::save(&mut tx, req, state.clock.now()).await?;
        tx.commit().await?;
        return Ok(());
    }
    upsert_all(state, &mut tx.as_handle(), &data, req, &asked).await?;
    let now = state.clock.now();
    req.last_synced_at = Some(now);
    requisitions::save(&mut tx, req, now).await?;
    tx.commit().await?;
    detect_recurring(state, req.member_id).await;
    tracing::info!(
        "Auto-resync OK for {}: {} accounts",
        req.institution_name.as_deref().unwrap_or("null"),
        data.len()
    );
    Ok(())
}

fn result(source: &str, status: SyncStatus, message: &str) -> SourceSyncResult {
    SourceSyncResult {
        source: source.to_owned(),
        status,
        message: message.to_owned(),
    }
}

/// MemberSyncService.executeSource's answer to an exception escaping the source.
fn unexpected(source: &str, member_id: i64, error: &sqlx::Error) -> SourceSyncResult {
    tracing::error!("{source} sync failed unexpectedly for member {member_id}: {error}");
    result(source, SyncStatus::Failed, "Unexpected sync error")
}

/// The requisition an OAuth callback belongs to: by state nonce, else (a state never stored by a
/// pre-nonce install, or none) the latest CREATED requisition of the member without a nonce.
async fn resolve_callback(
    state: &AppState,
    oauth_state: Option<&str>,
    member_id: i64,
) -> Result<Requisition, EbError> {
    match oauth_state.filter(|s| !crate::ledger::sync_values::java_is_blank(s)) {
        Some(nonce) => {
            if let Some(req) = requisitions::find_by_oauth_state(&state.db, nonce).await? {
                return Ok(req);
            }
            tracing::warn!(
                "OAuth callback state not found — trying legacy (pre-nonce) requisitions for member {member_id}"
            );
        }
        None => tracing::warn!(
            "OAuth callback without state — trying legacy (pre-nonce) requisitions for member {member_id}"
        ),
    }
    requisitions::find_by_status_and_member(&state.db, RequisitionStatus::Created, member_id)
        .await?
        .into_iter()
        .find(|r| r.oauth_state.is_none())
        .ok_or_else(|| {
            EbError::sync("Unknown or expired bank connection. Please initiate a new connection.")
        })
}

/// A replayed callback refreshes the latest LINKED session of the same institution only.
async fn resync_latest(
    state: &AppState,
    member_id: i64,
    institution_id: &str,
) -> Result<Vec<serde_json::Value>, EbError> {
    let mut req = requisitions::find_by_status_member_and_institution(
        &state.db,
        RequisitionStatus::Linked,
        member_id,
        institution_id,
    )
    .await?
    .into_iter()
    .next()
    .ok_or_else(|| EbError::sync("No linked session found to refresh."))?;
    ensure_logo_url(state, &mut req).await;
    let data = connector::fetch_balances(state, &req.requisition_id).await?;
    let asked = ask_first(state, &data, member_id, &req.requisition_id).await?;
    let mut tx = state.db.begin().await?;
    if mark_retryable_if_empty(&mut req, &data, "refresh") {
        requisitions::save(&mut tx, &req, state.clock.now()).await?;
        tx.commit().await?;
        return Ok(Vec::new());
    }
    let responses = upsert_all(state, &mut tx.as_handle(), &data, &req, &asked).await?;
    let now = state.clock.now();
    req.last_synced_at = Some(now);
    requisitions::save(&mut tx, &req, now).await?;
    tx.commit().await?;
    detect_recurring(state, member_id).await;
    tracing::info!(
        "Refreshed {} accounts for {}",
        responses.len(),
        req.institution_name.as_deref().unwrap_or("null")
    );
    Ok(responses)
}

/// An empty account list can mean the provider is still linking. A LINKED session keeps its
/// status (a transient gap must not flap it to FAILED); any other goes FAILED, retryable.
fn mark_retryable_if_empty(req: &mut Requisition, data: &[AccountData], operation: &str) -> bool {
    if !data.is_empty() {
        return false;
    }
    if req.status == RequisitionStatus::Linked {
        tracing::warn!(
            "Enable Banking requisition #{} returned no accounts during {operation} — keeping LINKED, skipping update",
            req.id
        );
        return true;
    }
    req.status = RequisitionStatus::Failed;
    tracing::info!(
        "Enable Banking requisition #{} returned no accounts during {operation} — marking retryable",
        req.id
    );
    true
}

async fn detect_recurring(state: &AppState, member_id: i64) {
    let detection = &state.sync.enable_banking.recurring;
    if let Err(e) = detection
        .detect(state, member_id, state.clock.today())
        .await
    {
        tracing::warn!("Recurring detection skipped for member {member_id}: {e}");
    }
}

/// One attempt per requisition at backfilling its logo. The marker is set only once the search
/// completed, so a provider outage does not burn the attempt.
async fn ensure_logo_url(state: &AppState, req: &mut Requisition) {
    if req.logo_url.is_some() || req.logo_backfill_attempted_at.is_some() {
        return;
    }
    match logo_url(
        state,
        connector::country_of(&req.institution_id).as_deref(),
        Some(&req.institution_id),
        req.institution_name.as_deref(),
    )
    .await
    {
        Ok(logo) => {
            req.logo_backfill_attempted_at = Some(state.clock.now());
            if logo.is_some() {
                req.logo_url = logo;
            }
        }
        Err(e) => tracing::warn!(
            "Could not backfill logo for requisition {} ({}): {}",
            req.id,
            req.institution_name.as_deref().unwrap_or("null"),
            e.message()
        ),
    }
}

/// BankLogoResolver.logoUrl: the institution's logo from the connector's catalog, matched by
/// exact id, then name and country, then name. Propagates the search failure.
pub async fn logo_url(
    state: &AppState,
    country: Option<&str>,
    institution_id: Option<&str>,
    institution_name: Option<&str>,
) -> Result<Option<String>, EbError> {
    let matches = connector::search_institutions(state, institution_name, country).await?;
    Ok(find_institution(&matches, institution_id, institution_name)
        .and_then(|i| i.logo_url.clone()))
}

/// BankLogoResolver.logoUrlOrNull: a catalog outage or an unconfigured install only means no logo.
pub async fn logo_url_or_null(
    state: &AppState,
    country: Option<&str>,
    institution_id: Option<&str>,
    institution_name: Option<&str>,
) -> Option<String> {
    match logo_url(state, country, institution_id, institution_name).await {
        Ok(logo) => logo,
        Err(e) => {
            tracing::warn!(
                "Could not resolve logo for institution {} ({}): {}",
                institution_id.unwrap_or("null"),
                institution_name.unwrap_or("null"),
                e.message()
            );
            None
        }
    }
}

fn institution_key(id: &str) -> String {
    let parts = super::wire::java_split(id);
    if parts.len() > 1 {
        format!(
            "{}::{}",
            parts[0].trim().to_lowercase(),
            parts[1].trim().to_uppercase()
        )
    } else {
        id.trim().to_lowercase()
    }
}

fn find_institution<'a>(
    candidates: &'a [InstitutionData],
    institution_id: Option<&str>,
    institution_name: Option<&str>,
) -> Option<&'a InstitutionData> {
    candidates
        .iter()
        .find(|i| Some(i.id.as_str()) == institution_id)
        .or_else(|| {
            let key = institution_key(institution_id?);
            candidates.iter().find(|i| institution_key(&i.id) == key)
        })
        .or_else(|| {
            let name = institution_name?.to_lowercase();
            candidates
                .iter()
                .find(|i| i.name.as_deref().is_some_and(|n| n.to_lowercase() == name))
        })
}

/// The transactions of one bank entry, asked of the bank before the write transaction opens.
struct AskedTransactions {
    /// The day they were asked from.
    since: NaiveDate,
    rows: Vec<TransactionData>,
}

/// Everything the write needs from outside, asked before its transaction opens: each entry's
/// transactions (None where there is nothing to import: no session, an account deleted here,
/// a bank that refused) and the rates its balances convert at. Inside the transaction these
/// calls would keep SQLite's only writer for as long as the bank takes to answer.
async fn ask_first(
    state: &AppState,
    data: &[AccountData],
    member_id: i64,
    session_id: &str,
) -> Result<Vec<Option<AskedTransactions>>, EbError> {
    let today = state.clock.today();
    let mut conn = state.db.pool_handle();
    let mut needs = PriceNeeds::default();
    let mut asked = Vec::with_capacity(data.len());
    for d in data {
        let stored = match_account(&mut conn, d, member_id).await?;
        needs.account(
            d.account_type,
            d.currency.as_deref().unwrap_or("EUR"),
            None,
            [""; 0],
        );
        let since = match &stored {
            Stored::Deleted => {
                asked.push(None);
                continue;
            }
            Stored::Account(account) => {
                // A balance the bank left out keeps the currency the account was stored in.
                needs.account(
                    account.account_type,
                    &account.currency,
                    account.ticker.as_deref(),
                    [""; 0],
                );
                bank_import::window_start(
                    &mut conn,
                    account.id,
                    today,
                    DEFAULT_INITIAL_HISTORY_DAYS,
                )
                .await?
            }
            Stored::None => bank_import::shared_history_start(today, DEFAULT_INITIAL_HISTORY_DAYS),
        };
        asked.push(ask_transactions(state, d, session_id, since).await);
    }
    state.ledger.prices.prefetch(&needs, today).await;
    Ok(asked)
}

/// BankTransactionImportService.importFor's fetch: a provider failure logs and imports nothing.
async fn ask_transactions(
    state: &AppState,
    data: &AccountData,
    session_id: &str,
    since: NaiveDate,
) -> Option<AskedTransactions> {
    if session_id.is_empty() {
        return None;
    }
    match connector::fetch_transactions(state, &data.external_id, Some(since)).await {
        Ok(rows) => Some(AskedTransactions { since, rows }),
        Err(e) => {
            tracing::warn!(
                "Could not fetch transactions for account externalId={} since {since}: {}",
                fingerprint(Some(&data.external_id)),
                e.message()
            );
            None
        }
    }
}

async fn upsert_all(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    data: &[AccountData],
    req: &Requisition,
    asked: &[Option<AskedTransactions>],
) -> Result<Vec<serde_json::Value>, EbError> {
    let mut responses = Vec::new();
    for (d, transactions) in data.iter().zip(asked) {
        if let Some(response) = upsert_account(state, conn, d, req, transactions.as_ref()).await? {
            responses.push(response);
        }
    }
    Ok(responses)
}

/// The stored account a bank entry writes into.
enum Stored {
    Account(Box<Account>),
    None,
    /// Soft-deleted here: a bank re-sending an id is no consent to resurrect it.
    Deleted,
}

/// Matches the external uid first, then (uid rotation) the IBAN and currency.
async fn match_account(
    conn: &mut DbHandle<'_>,
    data: &AccountData,
    member_id: i64,
) -> sqlx::Result<Stored> {
    let currency = data.currency.as_deref().unwrap_or("EUR");
    // A card may carry the IBAN of the account it is debited from: never match it on the card.
    let iban = data
        .iban
        .as_deref()
        .filter(|_| data.account_type != AccountType::CreditCard);
    let mut existing =
        accounts::find_by_external_id_and_member(&mut *conn, &data.external_id, member_id).await?;
    if existing.is_none() {
        // A deleted uid is not a rotation, even when another connector owns the IBAN.
        let deleted_uid =
            accounts::exists_soft_deleted_by_external_id(&mut *conn, &data.external_id, member_id)
                .await?;
        if !deleted_uid && let Some(iban) = iban {
            existing =
                accounts::find_by_iban_currency_and_member(&mut *conn, iban, currency, member_id)
                    .await?;
        }
        let deleted_iban = match (&existing, iban) {
            (None, Some(iban)) if !deleted_uid => {
                accounts::exists_soft_deleted_by_iban(
                    &mut *conn,
                    iban,
                    member_id,
                    Some(currency),
                    None,
                )
                .await?
            }
            _ => false,
        };
        if deleted_uid || deleted_iban {
            return Ok(Stored::Deleted);
        }
    }
    Ok(match existing {
        Some(account) => Stored::Account(Box::new(account)),
        None => Stored::None,
    })
}

/// None when the matching account was soft-deleted.
async fn upsert_account(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    data: &AccountData,
    req: &Requisition,
    transactions: Option<&AskedTransactions>,
) -> Result<Option<serde_json::Value>, EbError> {
    let member_id = req.member_id;
    let currency = data.currency.clone().unwrap_or_else(|| "EUR".to_owned());
    let is_card = data.account_type == AccountType::CreditCard;
    // A card may carry the IBAN of the account it is debited from: never store it on the card.
    let iban = data.iban.as_deref().filter(|_| !is_card);
    let existing = match match_account(conn, data, member_id).await? {
        Stored::Deleted => {
            tracing::info!(
                "Skipping resurrection of soft-deleted account externalId={} iban={} member={member_id}",
                fingerprint(Some(&data.external_id)),
                fingerprint(iban)
            );
            return Ok(None);
        }
        Stored::Account(account) => Some(*account),
        Stored::None => None,
    };
    // A card never takes a positive balance: whatever the path, that figure is credit left, not
    // owed.
    let stored_card = existing
        .as_ref()
        .is_some_and(|account| account.account_type == AccountType::CreditCard);
    let balance = data
        .balance
        .filter(|balance| !(stored_card && *balance > Decimal::ZERO));
    let now = state.clock.now();
    let created = existing.is_none();
    let account = match existing {
        Some(mut account) => {
            // A balance left as is keeps the currency it was stored in.
            if let Some(balance) = balance {
                account.currency = currency.clone();
                account.current_balance = balance;
            }
            account.last_synced_at = Some(now);
            // The provider may have rotated the uid (Enable Banking v0.16.4, Boursorama).
            account.external_account_id = Some(data.external_id.clone());
            if let Some(iban) = iban {
                // A deleted sidecar account can be restored on reconnect, so it still reserves its IBAN.
                let taken = accounts::exists_by_iban_currency_and_member_other_than(
                    &mut *conn, iban, &currency, member_id, account.id,
                )
                .await?
                    || accounts::exists_soft_deleted_by_iban(
                        &mut *conn,
                        iban,
                        member_id,
                        Some(&currency),
                        None,
                    )
                    .await?;
                if !taken {
                    account.iban = Some(iban.to_owned());
                }
            }
            if account.logo_url.is_none() && req.logo_url.is_some() {
                account.logo_url = req.logo_url.clone();
            }
            account.requisition_id = Some(req.id);
            refresh_card_identity(&mut account, data);
            accounts::update(&mut *conn, &account, now).await?
        }
        None => {
            let mut draft = NewAccount::new(
                member_id,
                data.name.clone().unwrap_or_else(|| "Account".to_owned()),
                data.account_type,
            );
            draft.card_nature = data.card_nature.filter(|_| is_card);
            draft.provider = req.institution_name.clone();
            draft.currency = currency.clone();
            draft.current_balance = balance.unwrap_or(Decimal::ZERO);
            draft.last_synced_at = Some(now);
            draft.external_account_id = Some(data.external_id.clone());
            draft.iban = iban.map(str::to_owned);
            draft.is_manual = false;
            draft.color = ACCOUNT_COLOR.to_owned();
            draft.logo_url = req.logo_url.clone();
            draft.requisition_id = Some(req.id);
            accounts::insert(&mut *conn, &draft, now).await?
        }
    };
    // The response carries the balance as the provider sent it, not as the column rescaled it; a
    // balance left as is answers with the stored one, and a new account without one with zero.
    let account = Account {
        current_balance: match balance {
            Some(balance) => balance,
            None if created => Decimal::ZERO,
            None => account.current_balance,
        },
        ..account
    };
    if let Some(balance) = balance {
        let ctx = SnapshotContext {
            prices: &*state.ledger.prices,
            today: state.clock.today(),
            now,
        };
        valuation::upsert_snapshot_from_native(&mut *conn, &ctx, &account, balance, ctx.today)
            .await?;
    }
    // Balances first, transactions after: the import is best-effort, so an ASPSP serving balances
    // but refusing /transactions still produces the sync the user asked for.
    if let Some(asked) = transactions {
        bank_import::import_fetched(conn, &account, asked.since, &asked.rows, now).await?;
    }
    let response = state
        .sync
        .enable_banking
        .accounts
        .to_response(state, conn, &account)
        .await?;
    Ok(Some(response))
}

/// Brings an account synced before the provider's card type was read up to date (#664): a
/// still-Checking account becomes a card, a card nature is only filled in when unknown, and only a
/// name still equal to the one an earlier release generated is replaced. A type other than
/// Checking and a name the user typed are kept; Checking itself cannot be told apart from the old
/// default, so a card set back to it by hand is upgraded again.
fn refresh_card_identity(account: &mut Account, data: &AccountData) {
    if data.account_type != AccountType::CreditCard {
        return;
    }
    // An IBAN stored in the current-account days is its funding account's: it would block that
    // account from storing it and could match the card to it after a uid rotation.
    account.iban = None;
    if account.account_type == AccountType::Checking {
        // Kept as is (no readable balance), a positive figure from the current-account days is
        // credit left, not owed: as a card it would read as a negative debt, so wait for one.
        if data.balance.is_none() && account.current_balance > Decimal::ZERO {
            return;
        }
        account.account_type = AccountType::CreditCard;
    }
    if account.account_type == AccountType::CreditCard && account.card_nature.is_none() {
        account.card_nature = data.card_nature;
    }
    if let (Some(legacy), Some(name)) = (&data.legacy_name, &data.name)
        && *legacy == account.name
    {
        account.name = name.clone();
    }
}
