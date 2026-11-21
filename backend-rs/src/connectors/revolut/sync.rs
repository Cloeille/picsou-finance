//! RevolutSyncService: the on-demand discovery (harvest only, run in the background) and the
//! confirm that persists the accounts the member picked, the remembered credentials of the
//! revolut_session row, and the scheduler's unattended resync.

use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

use chrono::{DateTime, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::adapter::{self, GENERIC_FAILURE, RevolutAccountData, RevolutTxn};
use super::progress::{Credentials, DiscoveredRevolutAccount};
use crate::AppState;
use crate::connectors::sidecar::{JobError, SyncError};
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::categorize::{self, CategorizationContext};
use crate::ledger::members;
use crate::ledger::model::{Account, AccountType, CategoryKind};
use crate::ledger::sidecar::{SourceSyncResult, SyncStatus};
use crate::ledger::sidecar_import;
use crate::ledger::valuation::{self, SnapshotContext};

const PROVIDER: &str = "Revolut";
const SOURCE: &str = "revolut";
const NO_SAVED_CREDENTIALS: &str =
    "No saved Revolut credentials. Please enter your phone number and passcode.";

static EXCHANGED_TO_EUR: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)^exchanged[ \t\n\x0B\f\r]+to[ \t\n\x0B\f\r]+eur$").expect("regex")
});
static EXCHANGED_FROM_EUR: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)^exchanged[ \t\n\x0B\f\r]+from[ \t\n\x0B\f\r]+eur$").expect("regex")
});

/// StatusResponse: whether the connector has synced, remembers credentials, and when it last ran.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusResponse {
    pub connected: bool,
    pub remembered: bool,
    pub last_synced_at: Option<DateTime<Utc>>,
}

#[derive(Debug, sqlx::FromRow)]
struct SessionRow {
    id: i64,
    credentials_enc: Option<String>,
    remember_credentials: bool,
    last_synced_at: Option<DateTime<Utc>>,
}

async fn find_session<'e>(
    db: impl sqlx::PgExecutor<'e>,
    member_id: i64,
) -> sqlx::Result<Option<SessionRow>> {
    sqlx::query_as(
        "SELECT id, credentials_enc, remember_credentials, last_synced_at FROM revolut_session \
         WHERE member_id = $1 ORDER BY id LIMIT 1",
    )
    .bind(member_id)
    .fetch_optional(db)
    .await
}

/// The SyncException messages the UI shows, by the sidecar's code; anything else is already a
/// message.
fn friendly(code: &str) -> &str {
    match code {
        "SESSION_EXPIRED" => {
            "Your Revolut session has expired. Please reconnect with your phone number and passcode."
        }
        "APPROVAL_TIMEOUT" => {
            "The mobile approval was not confirmed in time. Please try again and approve the push notification on your phone."
        }
        "HUMAN_VERIFICATION_REQUIRED" => {
            "Revolut is asking for a human verification (captcha) that Picsou cannot complete, so sign-in cannot continue. Please try again later."
        }
        "UPSTREAM_FORMAT_CHANGED" => {
            "The Revolut sign-in didn't go through. Check your phone number and try again."
        }
        "SYNC_IN_PROGRESS" => {
            "A Revolut sync is already running for this account. Please wait for it to finish before starting another."
        }
        "BROWSER_LAUNCH_FAILED" => {
            "The Revolut browser service could not start. Please try again after restarting the connector."
        }
        "REVOLUT_TIMEOUT" => "The Revolut sync took too long. Please try again later.",
        other => other,
    }
}

fn blank(value: Option<&str>) -> bool {
    value.is_none_or(|v| v.trim().is_empty())
}

async fn resolve_credentials(
    state: &AppState,
    member_id: i64,
    phone_number: Option<&str>,
    passcode: Option<&str>,
) -> Result<Credentials, JobError> {
    if let (Some(phone), Some(passcode)) = (phone_number, passcode)
        && !blank(Some(phone))
        && !blank(Some(passcode))
    {
        return Ok(Credentials {
            phone: phone.to_owned(),
            passcode: passcode.to_owned(),
        });
    }
    load_stored_credentials(state, member_id)
        .await?
        .ok_or_else(|| JobError::Sync(SyncError::uncoded(NO_SAVED_CREDENTIALS)))
}

async fn load_stored_credentials(
    state: &AppState,
    member_id: i64,
) -> sqlx::Result<Option<Credentials>> {
    let Some(session) = find_session(&state.db, member_id).await? else {
        return Ok(None);
    };
    if !session.remember_credentials {
        return Ok(None);
    }
    let Some(encrypted) = session.credentials_enc else {
        return Ok(None);
    };
    let decoded = state
        .crypto
        .decrypt(&encrypted)
        .map_err(|e| e.to_string())
        .and_then(|json| serde_json::from_str::<Credentials>(&json).map_err(|e| e.to_string()));
    match decoded {
        Ok(credentials) => Ok(Some(credentials)),
        Err(_) => {
            tracing::warn!("Failed to decrypt/parse stored Revolut credentials");
            Ok(None)
        }
    }
}

/// The background discovery of a manual sync: harvests with no write, then hands the preview and
/// the raw accounts to the progress registry. Never fails: the outcome is the job's terminal state.
pub async fn discover(
    state: &AppState,
    member_id: i64,
    phone_number: Option<String>,
    passcode: Option<String>,
) {
    let progress = &state.connectors.seams.revolut_progress;
    let outcome = async {
        let credentials = resolve_credentials(
            state,
            member_id,
            phone_number.as_deref(),
            passcode.as_deref(),
        )
        .await?;
        let harvested = adapter::sync(
            state,
            &credentials.phone,
            &credentials.passcode,
            member_id,
            true,
        )
        .await?;
        let preview = build_preview(state, &harvested, member_id).await?;
        Ok::<_, JobError>((harvested, preview, credentials))
    }
    .await;
    match outcome {
        Ok((harvested, preview, credentials)) => {
            progress.discovered(member_id, preview, harvested, credentials);
        }
        Err(JobError::Sync(error)) => {
            progress.error(member_id, friendly(&error.message), error.code());
        }
        Err(other) => {
            tracing::warn!("Revolut discovery failed for member {member_id}: {other}");
            progress.error(member_id, GENERIC_FAILURE, None);
        }
    }
}

async fn build_preview(
    state: &AppState,
    harvested: &[RevolutAccountData],
    member_id: i64,
) -> sqlx::Result<Vec<DiscoveredRevolutAccount>> {
    let mut preview = Vec::with_capacity(harvested.len());
    let mut conn = state.db.acquire().await?;
    for data in harvested {
        let imported = find_active_account(&mut conn, data, member_id)
            .await?
            .is_some();
        preview.push(DiscoveredRevolutAccount {
            external_id: data.external_id.clone(),
            name: data.name.clone(),
            account_type: data.account_type.as_str().to_owned(),
            currency: data.currency.clone(),
            balance: data.balance,
            parent_external_id: data.parent_external_id.clone(),
            already_imported: imported,
            transaction_count: data.txns.len(),
        });
    }
    Ok(preview)
}

/// A wallet matches the same IBAN and currency first (the Enable Banking sub-account it
/// duplicates); pockets and vaults have no IBAN and match on their external id alone.
async fn find_active_account(
    conn: &mut PgConnection,
    data: &RevolutAccountData,
    member_id: i64,
) -> sqlx::Result<Option<Account>> {
    if let Some(iban) = &data.iban
        && let Some(account) = accounts::find_by_iban_currency_and_member(
            &mut *conn,
            iban,
            &data.currency_code(),
            member_id,
        )
        .await?
    {
        return Ok(Some(account));
    }
    match &data.external_id {
        Some(id) => accounts::find_by_external_id_and_member(&mut *conn, id, member_id).await,
        None => Ok(None),
    }
}

/// Persists the selected subset of the last discovery. Additive: an account left unselected is
/// never deleted. `voluntary` (an explicit Add-account) lifts the soft-delete tombstone of each
/// selected account; an auto-sync confirm leaves a deleted account deleted.
pub async fn confirm_sync(
    state: &AppState,
    member_id: i64,
    selected_external_ids: &[Option<String>],
    remember: bool,
    voluntary: bool,
) -> AppResult<()> {
    let (discovered, credentials) = state
        .connectors
        .seams
        .revolut_progress
        .take_pending(member_id);
    if discovered.is_empty() {
        return Err(SyncError::uncoded(
            "No Revolut accounts are pending import. Please run a sync again before importing.",
        )
        .into());
    }
    let selected: HashSet<Option<&str>> =
        selected_external_ids.iter().map(Option::as_deref).collect();
    let is_selected = |data: &RevolutAccountData| selected.contains(&data.external_id.as_deref());
    let categorization = load_categorization(state, member_id).await?;
    let mut tx = state.db.begin().await?;
    if voluntary {
        for data in discovered.iter().filter(|d| is_selected(d)) {
            accounts::restore_soft_deleted_revolut_account(
                &mut *tx,
                member_id,
                data.external_id.as_deref().unwrap_or_default(),
                data.iban.as_deref(),
                &data.currency_code(),
            )
            .await?;
        }
    }
    let chosen: Vec<&RevolutAccountData> = discovered.iter().filter(|d| is_selected(d)).collect();
    persist_selected(
        state,
        &mut tx,
        &discovered,
        chosen,
        member_id,
        &categorization,
    )
    .await?;
    let remembered = credentials.as_ref().filter(|_| remember);
    apply_post_sync_session_state(state, &mut tx, member_id, remembered).await?;
    tx.commit().await?;
    Ok(())
}

async fn load_categorization(
    state: &AppState,
    member_id: i64,
) -> sqlx::Result<CategorizationContext> {
    let knowledge_base = state.ledger.knowledge_base(&state.db).await?;
    categorize::load_context(&state.db, knowledge_base, member_id, state.clock.now()).await
}

/// Upserts the chosen accounts, wallets first so a pocket's parent resolves to its stored wallet.
async fn persist_selected(
    state: &AppState,
    conn: &mut PgConnection,
    discovered: &[RevolutAccountData],
    mut chosen: Vec<&RevolutAccountData>,
    member_id: i64,
    categorization: &CategorizationContext,
) -> AppResult<usize> {
    let transfer_legs = verified_transfer_legs(discovered);
    chosen.sort_by_key(|d| d.parent_external_id.is_some());
    let mut account_ids: HashMap<Option<&str>, i64> = HashMap::new();
    let mut updated = 0;
    for data in chosen {
        let parent_id = data
            .parent_external_id
            .as_deref()
            .and_then(|parent| account_ids.get(&Some(parent)).copied());
        if let Some(id) = upsert_account(
            state,
            conn,
            data,
            member_id,
            parent_id,
            categorization,
            &transfer_legs,
        )
        .await?
        {
            account_ids.insert(data.external_id.as_deref(), id);
            updated += 1;
        }
    }
    tracing::info!("Revolut sync complete: {updated} accounts updated");
    Ok(updated)
}

/// The row is upserted with a fresh lastSyncedAt after every successful sync, remember or not:
/// it marks that the on-demand connector has produced this member's pockets. Remembered
/// credentials are stored encrypted; otherwise any earlier ones are cleared.
async fn apply_post_sync_session_state(
    state: &AppState,
    conn: &mut PgConnection,
    member_id: i64,
    remembered: Option<&Credentials>,
) -> AppResult<()> {
    let credentials_enc = match remembered {
        Some(credentials) => {
            let json = serde_json::to_string(credentials).map_err(|_| {
                AppError::from(SyncError::uncoded("Failed to store Revolut credentials."))
            })?;
            Some(state.crypto.encrypt(&json)?)
        }
        None => None,
    };
    let now = state.clock.now();
    match find_session(&mut *conn, member_id).await? {
        Some(session) => {
            sqlx::query(
                "UPDATE revolut_session SET credentials_enc = $2, remember_credentials = $3, \
                 last_synced_at = $4, updated_at = $4 WHERE id = $1",
            )
            .bind(session.id)
            .bind(&credentials_enc)
            .bind(remembered.is_some())
            .bind(now)
            .execute(&mut *conn)
            .await?;
        }
        None => {
            if members::find_by_id(&mut *conn, member_id).await?.is_none() {
                return Err(AppError::NotFound("Family member not found".to_owned()));
            }
            sqlx::query(
                "INSERT INTO revolut_session (member_id, credentials_enc, remember_credentials, \
                 last_synced_at, created_at, updated_at) VALUES ($1, $2, $3, $4, $4, $4)",
            )
            .bind(member_id)
            .bind(&credentials_enc)
            .bind(remembered.is_some())
            .bind(now)
            .execute(&mut *conn)
            .await?;
        }
    }
    Ok(())
}

/// Dedups a wallet against the Enable Banking sub-account of the same IBAN and currency, and never
/// resurrects an account the member deleted. Returns the account id, None when skipped.
async fn upsert_account(
    state: &AppState,
    conn: &mut PgConnection,
    data: &RevolutAccountData,
    member_id: i64,
    parent_account_id: Option<i64>,
    categorization: &CategorizationContext,
    transfer_legs: &HashSet<TransferKey>,
) -> AppResult<Option<i64>> {
    let currency = data.currency_code();
    let now = state.clock.now();
    let existing = find_active_account(&mut *conn, data, member_id).await?;
    let account = match existing {
        Some(mut account) => {
            account.current_balance = data.balance;
            account.last_synced_at = Some(now);
            account.external_account_id = data.external_id.clone();
            if data.iban.is_some() {
                account.iban = data.iban.clone();
            }
            if parent_account_id.is_some() {
                account.parent_account_id = parent_account_id;
            }
            accounts::update(&mut *conn, &account, now).await?
        }
        None => {
            let deleted_by_iban = match &data.iban {
                Some(iban) => {
                    accounts::exists_soft_deleted_by_iban(
                        &mut *conn,
                        iban,
                        member_id,
                        Some(&currency),
                        None,
                    )
                    .await?
                }
                None => false,
            };
            let deleted_by_id = match &data.external_id {
                Some(id) if !deleted_by_iban => {
                    accounts::exists_soft_deleted_by_external_id(&mut *conn, id, member_id).await?
                }
                _ => false,
            };
            if deleted_by_iban || deleted_by_id {
                tracing::info!(
                    "Revolut: skipping resurrection of a soft-deleted account (member={member_id})"
                );
                return Ok(None);
            }
            if members::find_by_id(&mut *conn, member_id).await?.is_none() {
                return Err(AppError::NotFound("Family member not found".to_owned()));
            }
            let name = data
                .name
                .clone()
                .ok_or_else(|| AppError::internal("Revolut returned an account without a name"))?;
            let mut draft = NewAccount::new(member_id, name, data.account_type);
            draft.provider = Some(PROVIDER.to_owned());
            draft.currency = currency;
            draft.current_balance = data.balance;
            draft.last_synced_at = Some(now);
            draft.external_account_id = data.external_id.clone();
            draft.iban = data.iban.clone();
            draft.parent_account_id = parent_account_id;
            draft.is_manual = false;
            draft.color = color_for(data.account_type).to_owned();
            accounts::insert(&mut *conn, &draft, now).await?
        }
    };
    let today = state.clock.today();
    let ctx = SnapshotContext {
        prices: &*state.ledger.prices,
        today,
        now,
    };
    valuation::upsert_snapshot_from_native(&mut *conn, &ctx, &account, data.balance, today).await?;

    let rows: Vec<_> = data
        .txns
        .iter()
        .filter(|txn| !foreign_leg_without_currency(txn))
        .map(|txn| {
            let kind = if transfer_key(data, txn).is_some_and(|k| transfer_legs.contains(&k)) {
                Some(CategoryKind::Transfer)
            } else if txn.kind == Some(CategoryKind::Transfer) {
                None
            } else {
                txn.kind
            };
            txn.to_sidecar_transaction(kind)
        })
        .collect();
    let inserted =
        sidecar_import::import(&mut *conn, &account, &rows, categorization, SOURCE, now).await?;
    if inserted > 0 {
        tracing::info!(
            "Ingested {inserted} new Revolut transactions for account {}",
            account.id
        );
    }
    Ok(Some(account.id))
}

fn color_for(account_type: AccountType) -> &'static str {
    match account_type {
        AccountType::Savings => "#8b5cf6",
        _ => "#6366f1",
    }
}

/// A debit "Exchanged to EUR" or a credit "Exchanged from EUR" is the foreign leg of an exchange.
/// Without its own currency it would be booked at the pocket's EUR scale (#251), so it is dropped.
fn foreign_leg_without_currency(txn: &RevolutTxn) -> bool {
    if txn
        .currency
        .as_deref()
        .is_some_and(|c| !c.trim().is_empty())
    {
        return false;
    }
    let Some(description) = txn.description.as_deref() else {
        return false;
    };
    let description = description.trim();
    (txn.amount.is_sign_negative()
        && !txn.amount.is_zero()
        && EXCHANGED_TO_EUR.is_match(description))
        || (txn.amount.is_sign_positive()
            && !txn.amount.is_zero()
            && EXCHANGED_FROM_EUR.is_match(description))
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct TransferKey {
    account_id: Option<String>,
    transaction_id: String,
    date: chrono::NaiveDate,
    amount: String,
    currency: String,
}

fn transfer_key(account: &RevolutAccountData, txn: &RevolutTxn) -> Option<TransferKey> {
    Some(TransferKey {
        account_id: account.external_id.clone(),
        transaction_id: txn.external_id.clone()?,
        date: txn.date,
        amount: txn.amount.normalize().to_string(),
        currency: account.currency_code(),
    })
}

/// The transfer legs Revolut lists on two of the member's accounts under one id: both marked
/// TRANSFER, on two different accounts of the same currency, the same day, and summing to zero.
fn verified_transfer_legs(accounts: &[RevolutAccountData]) -> HashSet<TransferKey> {
    let mut by_id: HashMap<&str, Vec<(&RevolutAccountData, &RevolutTxn)>> = HashMap::new();
    for account in accounts {
        for txn in &account.txns {
            if let Some(id) = txn.external_id.as_deref() {
                by_id.entry(id).or_default().push((account, txn));
            }
        }
    }
    let mut verified = HashSet::new();
    for candidates in by_id.values() {
        let [(left_account, left), (right_account, right)] = candidates.as_slice() else {
            continue;
        };
        if left.kind != Some(CategoryKind::Transfer)
            || right.kind != Some(CategoryKind::Transfer)
            || left_account.external_id == right_account.external_id
            || left.date != right.date
            || left_account.currency_code() != right_account.currency_code()
            || left.amount.is_zero()
            || left.amount + right.amount != Decimal::ZERO
        {
            continue;
        }
        verified.extend(transfer_key(left_account, left));
        verified.extend(transfer_key(right_account, right));
    }
    verified
}

/// `connected` once the member has completed a sidecar sync (the revolut_session row) or already
/// has Revolut wallets from a past sync.
pub async fn get_status(state: &AppState, member_id: i64) -> AppResult<StatusResponse> {
    let session = find_session(&state.db, member_id).await?;
    let has_wallets = !accounts::find_revolut_wallets(&state.db, member_id)
        .await?
        .is_empty();
    Ok(StatusResponse {
        connected: session.is_some() || has_wallets,
        remembered: session.as_ref().is_some_and(|s| s.remember_credentials),
        last_synced_at: session.and_then(|s| s.last_synced_at),
    })
}

/// Forgets the remembered credentials; accounts already synced are left untouched.
pub async fn disconnect(state: &AppState, member_id: i64) -> AppResult<()> {
    if let Some(session) = find_session(&state.db, member_id).await? {
        sqlx::query("DELETE FROM revolut_session WHERE id = $1")
            .bind(session.id)
            .execute(&state.db)
            .await?;
    }
    tracing::info!("Revolut session cleared for member {member_id}");
    Ok(())
}

/// The daily scheduler's entry (track T8): a no-op unless credentials are remembered; then an
/// unattended sync that may reuse a live browser profile but never starts a login waiting for a
/// mobile approval, imports every harvested account and lifts no tombstone. Never retries.
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    let remembered = match find_session(&state.db, member_id).await {
        Ok(session) => session.is_some_and(|s| s.remember_credentials),
        Err(e) => {
            tracing::error!(
                "Revolut scheduled sync failed unexpectedly for member {member_id}: {e}"
            );
            return result(SyncStatus::Failed, "Unexpected sync error");
        }
    };
    if !remembered {
        return result(SyncStatus::SkippedNotConnected, "No remembered credentials");
    }
    match unattended_sync(state, member_id).await {
        Ok(()) => result(SyncStatus::Synced, ""),
        Err(JobError::Sync(error)) => {
            tracing::warn!(
                "Revolut scheduled sync failed for member {member_id} (code={})",
                error.code().unwrap_or("null")
            );
            SourceSyncResult::from_sync_exception(SOURCE, error.code(), Some(&error.message))
        }
        Err(other) => {
            tracing::error!(
                "Revolut scheduled sync failed unexpectedly for member {member_id}: {other}"
            );
            result(SyncStatus::Failed, "Unexpected sync error")
        }
    }
}

async fn unattended_sync(state: &AppState, member_id: i64) -> Result<(), JobError> {
    let credentials = resolve_credentials(state, member_id, None, None).await?;
    let harvested = match adapter::sync(
        state,
        &credentials.phone,
        &credentials.passcode,
        member_id,
        false,
    )
    .await
    {
        Ok(harvested) => harvested,
        Err(JobError::Sync(error)) => {
            let code = error.code.clone().or_else(|| {
                (error.message == "SESSION_EXPIRED").then(|| "SESSION_EXPIRED".to_owned())
            });
            return Err(JobError::Sync(SyncError {
                message: friendly(&error.message).to_owned(),
                code,
            }));
        }
        Err(other) => return Err(other),
    };
    let categorization = load_categorization(state, member_id).await?;
    let mut tx = state.db.begin().await?;
    let all: Vec<&RevolutAccountData> = harvested.iter().collect();
    persist_selected(state, &mut tx, &harvested, all, member_id, &categorization).await?;
    apply_post_sync_session_state(state, &mut tx, member_id, Some(&credentials)).await?;
    tx.commit().await?;
    Ok(())
}

fn result(status: SyncStatus, message: &str) -> SourceSyncResult {
    SourceSyncResult {
        source: SOURCE.to_owned(),
        status,
        message: message.to_owned(),
    }
}
