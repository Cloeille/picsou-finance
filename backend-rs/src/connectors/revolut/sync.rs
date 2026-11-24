//! RevolutSyncService: the on-demand discovery (harvest only, run in the background) and the
//! confirm that persists the accounts the member picked, the remembered credentials of the
//! revolut_session row, and the scheduler's unattended resync.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, LazyLock, Mutex, MutexGuard};
use std::time::Duration;

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use chrono::{DateTime, Utc};
use http::Method;
use regex::Regex;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use super::adapter::{self, GENERIC_FAILURE, RevolutAccountData, RevolutTxn};
use super::progress::{Credentials, DiscoveredRevolutAccount};
use crate::AppState;
use crate::connectors::sidecar::jobs::Pool;
use crate::connectors::sidecar::{CallError, JobError, SidecarHttp, SyncError};
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::categorize::{self, CategorizationContext};
use crate::ledger::members;
use crate::ledger::model::{Account, AccountType, CategoryKind};
use crate::ledger::prices::PriceNeeds;
use crate::ledger::sidecar::{SourceSyncResult, SyncStatus};
use crate::ledger::sidecar_import;
use crate::ledger::sync_values::clean;
use crate::ledger::valuation::{self, SnapshotContext};

const PROVIDER: &str = "Revolut";
const SOURCE: &str = "revolut";
const SERVICE: &str = "revolut-auth";
const WEBVIEW_TIMEOUT: Duration = Duration::from_secs(30);
const WEBVIEW_ATTEMPT_SECONDS: i64 = 360;
const NO_SAVED_CREDENTIALS: &str =
    "No saved Revolut credentials. Please enter your phone number and passcode.";

pub fn webview_supported(state: &AppState) -> bool {
    state.config.desktop.eq_ignore_ascii_case("true")
}

#[derive(Debug, Clone)]
struct PendingWebview {
    member_id: i64,
    deadline: DateTime<Utc>,
    generation: u64,
}

#[derive(Default)]
struct WebviewMaps {
    pending: HashMap<String, PendingWebview>,
    generations: HashMap<i64, u64>,
}

#[derive(Default)]
struct WebviewLogins {
    maps: Mutex<WebviewMaps>,
    write: tokio::sync::Mutex<()>,
}

impl WebviewLogins {
    fn maps(&self) -> MutexGuard<'_, WebviewMaps> {
        self.maps
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn generation(&self, member_id: i64) -> u64 {
        self.maps()
            .generations
            .get(&member_id)
            .copied()
            .unwrap_or(0)
    }

    fn evict_expired(&self, now: DateTime<Utc>) {
        self.maps().pending.retain(|_, p| p.deadline > now);
    }

    fn remove(&self, process_id: &str) {
        self.maps().pending.remove(process_id);
    }

    fn owned_attempt(
        &self,
        process_id: &str,
        member_id: i64,
        now: DateTime<Utc>,
    ) -> Result<PendingWebview, SyncError> {
        self.maps()
            .pending
            .get(process_id)
            .filter(|p| p.member_id == member_id && p.deadline > now)
            .cloned()
            .ok_or_else(|| {
                SyncError::with_code("AUTH_ATTEMPT_EXPIRED", "Revolut sign-in attempt expired")
            })
    }

    fn require_generation(&self, member_id: i64, generation: u64) -> Result<(), SyncError> {
        if self.generation(member_id) != generation {
            return Err(SyncError::with_code(
                "AUTH_ATTEMPT_EXPIRED",
                "The Revolut connection was removed during the login",
            ));
        }
        Ok(())
    }

    fn forget_member(&self, member_id: i64) {
        let mut maps = self.maps();
        *maps.generations.entry(member_id).or_insert(0) += 1;
        maps.pending.retain(|_, p| p.member_id != member_id);
    }
}

fn webview_logins(state: &AppState) -> Arc<WebviewLogins> {
    state
        .connectors
        .jobs
        .local::<WebviewLogins>("revolut-webview")
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WebviewStartResponse {
    pub process_id: String,
    pub status: String,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WebviewStatusResponse {
    pub status: String,
    pub error_code: Option<String>,
    pub session: Option<StatusResponse>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WebviewCancelResponse {
    pub cancelled: bool,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct BridgeWebviewStart {
    process_id: Option<String>,
    status: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct BridgeWebviewStatus {
    status: Option<String>,
    error_code: Option<String>,
}

fn require_webview(state: &AppState) -> AppResult<()> {
    if webview_supported(state) {
        Ok(())
    } else {
        Err(AppError::NotFound(
            "Revolut interactive WebView login is only available in Picsou Desktop".to_owned(),
        ))
    }
}

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
    /// Present and `true` only on Picsou Desktop where native OS WebView login is available.
    pub webview_login_supported: Option<bool>,
}

#[derive(Debug, crate::db::DbFromRow)]
struct SessionRow {
    id: i64,
    credentials_enc: Option<String>,
    remember_credentials: bool,
    last_synced_at: Option<DateTime<Utc>>,
}

async fn find_session<'e>(
    db: impl crate::db::DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Option<SessionRow>> {
    crate::db::query_as(
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
    if let Some(credentials) = load_stored_credentials(state, member_id).await? {
        return Ok(credentials);
    }
    if webview_supported(state) && find_session(&state.db, member_id).await?.is_some() {
        return Ok(Credentials {
            phone: String::new(),
            passcode: String::new(),
        });
    }
    Err(JobError::Sync(SyncError::uncoded(NO_SAVED_CREDENTIALS)))
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
    let outcome = JobError::caught(async {
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
    })
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
    let mut conn = state.db.pool_handle();
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
    conn: &mut DbHandle<'_>,
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
    // The balance of each account is converted from its own currency: the rates are asked
    // before the transaction opens, where a slow provider would keep SQLite's only writer.
    let mut needs = PriceNeeds::default();
    for data in discovered.iter().filter(|d| is_selected(d)) {
        needs.currency(&data.currency_code());
    }
    state
        .ledger
        .prices
        .prefetch(&needs, state.clock.today())
        .await;
    let mut tx = state.db.begin().await?;
    if voluntary {
        for data in discovered.iter().filter(|d| is_selected(d)) {
            accounts::restore_soft_deleted_revolut_account(
                &mut tx,
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
        &mut tx.as_handle(),
        &discovered,
        chosen,
        member_id,
        &categorization,
    )
    .await?;
    let remembered = credentials.as_ref().filter(|_| remember);
    apply_post_sync_session_state(state, &mut tx.as_handle(), member_id, remembered).await?;
    tx.commit().await?;
    crate::ledger::transfers::after_import(
        &state.db,
        &*state.ledger.dividends,
        member_id,
        state.clock.today(),
        state.clock.now(),
    )
    .await;
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
    conn: &mut DbHandle<'_>,
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
    conn: &mut DbHandle<'_>,
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
            crate::db::query(
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
            crate::db::query(
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

/// Updates an already-persisted Revolut account from a fresh harvest, repairing currency,
/// parent linkage (including detaching pockets whose currency no longer matches the wallet),
/// and legacy raw `"CURRENT"` placeholder names while keeping custom user names intact.
fn apply_existing_update(
    account: &mut Account,
    data: &RevolutAccountData,
    currency: &str,
    parent_account_id: Option<i64>,
    now: DateTime<Utc>,
) {
    account.current_balance = data.balance;
    account.currency = currency.to_owned();
    account.last_synced_at = Some(now);
    account.external_account_id = data.external_id.clone();
    if data.iban.is_some() {
        account.iban = data.iban.clone();
    }
    if parent_account_id.is_some() {
        account.parent_account_id = parent_account_id;
    } else if data.parent_external_id.is_none() {
        account.parent_account_id = None;
    }
    if account.name.eq_ignore_ascii_case("CURRENT")
        && let Some(new_name) = data.name.as_deref()
        && !new_name.trim().is_empty()
        && !new_name.eq_ignore_ascii_case("CURRENT")
    {
        account.name = new_name.to_owned();
    }
}

/// Dedups a wallet against the Enable Banking sub-account of the same IBAN and currency, and never
/// resurrects an account the member deleted. Returns the account id, None when skipped.
async fn upsert_account(
    state: &AppState,
    conn: &mut DbHandle<'_>,
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
            apply_existing_update(&mut account, data, &currency, parent_account_id, now);
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

/// Background discovery driven by a completed Desktop WebView login: pulls the freshly cached
/// snapshot from the WebView bridge without prompting for credentials.
pub async fn discover_webview(state: &AppState, member_id: i64) {
    let progress = &state.connectors.seams.revolut_progress;
    let outcome = JobError::caught(async {
        let harvested = adapter::sync(state, "", "", member_id, false).await?;
        let preview = build_preview(state, &harvested, member_id).await?;
        Ok::<_, JobError>((
            harvested,
            preview,
            Credentials {
                phone: String::new(),
                passcode: String::new(),
            },
        ))
    })
    .await;
    match outcome {
        Ok((harvested, preview, credentials)) => {
            progress.discovered(member_id, preview, harvested, credentials);
        }
        Err(JobError::Sync(error)) => {
            progress.error(member_id, friendly(&error.message), error.code());
        }
        Err(other) => {
            tracing::warn!("Revolut WebView discovery failed for member {member_id}: {other}");
            progress.error(member_id, GENERIC_FAILURE, None);
        }
    }
}

async fn ensure_session_row(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    member_id: i64,
) -> AppResult<()> {
    let now = state.clock.now();
    match find_session(&mut *conn, member_id).await? {
        Some(session) => {
            crate::db::query("UPDATE revolut_session SET updated_at = $2 WHERE id = $1")
                .bind(session.id)
                .bind(now)
                .execute(&mut *conn)
                .await?;
        }
        None => {
            if members::find_by_id(&mut *conn, member_id).await?.is_none() {
                return Err(AppError::NotFound("Family member not found".to_owned()));
            }
            crate::db::query(
                "INSERT INTO revolut_session (member_id, credentials_enc, remember_credentials, \
                 last_synced_at, created_at, updated_at) VALUES ($1, NULL, FALSE, NULL, $2, $2)",
            )
            .bind(member_id)
            .bind(now)
            .execute(&mut *conn)
            .await?;
        }
    }
    Ok(())
}

fn map_bridge_call_error(err: CallError) -> AppError {
    match err {
        CallError::Unauthorized(sync_err) => sync_err.into(),
        CallError::Timeout | CallError::Unreachable => {
            SyncError::with_code("UPSTREAM_UNAVAILABLE", adapter::NO_RESPONSE).into()
        }
        CallError::Transport(detail) => AppError::internal(format!("revolut webview: {detail}")),
    }
}

pub async fn start_webview(state: &AppState, member_id: i64) -> AppResult<WebviewStartResponse> {
    require_webview(state)?;
    let logins = webview_logins(state);
    let generation = logins.generation(member_id);
    let http = SidecarHttp::new(state, SERVICE, PROVIDER);
    let raw = http
        .send(
            Method::POST,
            "/webview/start",
            Some(&json!({ "memberId": member_id.to_string() })),
            WEBVIEW_TIMEOUT,
        )
        .await
        .map_err(map_bridge_call_error)?;
    if !raw.is_success() {
        return Err(SyncError::with_code("UPSTREAM_UNAVAILABLE", GENERIC_FAILURE).into());
    }
    let started: BridgeWebviewStart = raw.json().map_err(|_| {
        AppError::from(SyncError::with_code(
            "UPSTREAM_FORMAT_CHANGED",
            "Revolut WebView bridge returned malformed JSON",
        ))
    })?;
    let Some(process_id) = started.process_id.filter(|s| clean(Some(s)).is_some()) else {
        return Err(SyncError::with_code(
            "UPSTREAM_FORMAT_CHANGED",
            "Revolut WebView bridge did not return a process identifier",
        )
        .into());
    };
    let status = started.status.unwrap_or_else(|| "WAITING_LOGIN".to_owned());
    let now = state.clock.now();
    {
        let _write = logins.write.lock().await;
        logins.require_generation(member_id, generation)?;
        logins.evict_expired(now);
        logins.maps().pending.insert(
            process_id.clone(),
            PendingWebview {
                member_id,
                deadline: now + chrono::Duration::seconds(WEBVIEW_ATTEMPT_SECONDS),
                generation,
            },
        );
    }
    Ok(WebviewStartResponse { process_id, status })
}

pub async fn poll_webview_status(
    state: &AppState,
    process_id: &str,
    member_id: i64,
    pool: Pool,
) -> AppResult<WebviewStatusResponse> {
    require_webview(state)?;
    let logins = webview_logins(state);
    let current = logins.owned_attempt(process_id, member_id, state.clock.now())?;
    let http = SidecarHttp::new(state, SERVICE, PROVIDER);
    let path = format!("/webview/status/{process_id}");
    let raw = http
        .send::<()>(Method::GET, &path, None, WEBVIEW_TIMEOUT)
        .await
        .map_err(map_bridge_call_error)?;
    if !raw.is_success() {
        if raw.status == http::StatusCode::GONE || raw.status == http::StatusCode::NOT_FOUND {
            logins.remove(process_id);
            return Err(SyncError::with_code(
                "AUTH_ATTEMPT_EXPIRED",
                "Revolut sign-in attempt expired",
            )
            .into());
        }
        return Err(SyncError::with_code("UPSTREAM_UNAVAILABLE", GENERIC_FAILURE).into());
    }
    let bridge: BridgeWebviewStatus = raw.json().map_err(|_| {
        AppError::from(SyncError::with_code(
            "UPSTREAM_FORMAT_CHANGED",
            "Could not decode Revolut WebView status",
        ))
    })?;
    let status = bridge.status.unwrap_or_else(|| "FAILED".to_owned());
    match status.as_str() {
        "WAITING_LOGIN" | "FETCHING" => Ok(WebviewStatusResponse {
            status,
            error_code: None,
            session: None,
        }),
        "CANCELLED" => {
            logins.remove(process_id);
            Ok(WebviewStatusResponse {
                status,
                error_code: None,
                session: None,
            })
        }
        "FAILED" => {
            logins.remove(process_id);
            let error_code = Some(
                bridge
                    .error_code
                    .unwrap_or_else(|| "UPSTREAM_UNAVAILABLE".to_owned()),
            );
            Ok(WebviewStatusResponse {
                status,
                error_code,
                session: None,
            })
        }
        "DONE" => {
            let should_trigger = {
                let _write = logins.write.lock().await;
                let still_pending = logins.maps().pending.remove(process_id);
                if still_pending.is_none() {
                    false
                } else {
                    logins.require_generation(member_id, current.generation)?;
                    let mut tx = state.db.begin().await?;
                    ensure_session_row(state, &mut tx.as_handle(), member_id).await?;
                    tx.commit().await?;
                    true
                }
            };
            if should_trigger {
                let progress = &state.connectors.seams.revolut_progress;
                if progress.start_if_idle(member_id, state.clock.now()) {
                    let task_state = state.clone();
                    let submitted = state.connectors.jobs.submit("REVOLUT", pool, async move {
                        discover_webview(&task_state, member_id).await;
                    });
                    if submitted.is_err() {
                        tracing::warn!(
                            "Revolut WebView sync executor refused a job (member={member_id})"
                        );
                        progress.error(member_id, GENERIC_FAILURE, None);
                        return Err(AppError::internal("the Revolut sync executor is full"));
                    }
                }
            }
            let session = get_status(state, member_id).await?;
            Ok(WebviewStatusResponse {
                status: "SYNCING".to_owned(),
                error_code: None,
                session: Some(session),
            })
        }
        _ => {
            logins.remove(process_id);
            Err(SyncError::with_code(
                "UPSTREAM_FORMAT_CHANGED",
                "Revolut WebView bridge returned an unexpected status",
            )
            .into())
        }
    }
}

pub async fn cancel_webview(
    state: &AppState,
    process_id: &str,
    member_id: i64,
) -> AppResult<WebviewCancelResponse> {
    require_webview(state)?;
    let logins = webview_logins(state);
    let removed = {
        let mut maps = logins.maps();
        if maps
            .pending
            .get(process_id)
            .is_some_and(|p| p.member_id == member_id)
        {
            maps.pending.remove(process_id);
            true
        } else {
            false
        }
    };
    if !removed {
        return Ok(WebviewCancelResponse { cancelled: false });
    }
    let http = SidecarHttp::new(state, SERVICE, PROVIDER);
    let path = format!("/webview/cancel/{process_id}");
    let _ = http
        .send::<Value>(Method::POST, &path, Some(&json!({})), WEBVIEW_TIMEOUT)
        .await;
    Ok(WebviewCancelResponse { cancelled: true })
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
        webview_login_supported: webview_supported(state).then_some(true),
    })
}

/// Forgets the remembered credentials; accounts already synced are left untouched.
pub async fn disconnect(state: &AppState, member_id: i64) -> AppResult<()> {
    let logins = webview_logins(state);
    let _write = logins.write.lock().await;
    logins.forget_member(member_id);
    if let Some(session) = find_session(&state.db, member_id).await? {
        crate::db::query("DELETE FROM revolut_session WHERE id = $1")
            .bind(session.id)
            .execute(&state.db)
            .await?;
    }
    if webview_supported(state) && !state.connectors.base_url(SERVICE).is_empty() {
        let http = SidecarHttp::new(state, SERVICE, PROVIDER);
        let path = format!("/session/{member_id}");
        let _ = http
            .send::<Value>(Method::DELETE, &path, None, Duration::from_secs(5))
            .await;
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
    persist_selected(
        state,
        &mut tx.as_handle(),
        &harvested,
        all,
        member_id,
        &categorization,
    )
    .await?;
    apply_post_sync_session_state(state, &mut tx.as_handle(), member_id, Some(&credentials))
        .await?;
    tx.commit().await?;
    crate::ledger::transfers::after_import(
        &state.db,
        &*state.ledger.dividends,
        member_id,
        state.clock.today(),
        state.clock.now(),
    )
    .await;
    Ok(())
}

fn result(status: SyncStatus, message: &str) -> SourceSyncResult {
    SourceSyncResult {
        source: SOURCE.to_owned(),
        status,
        message: message.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stored_account(name: &str, currency: &str, parent_account_id: Option<i64>) -> Account {
        let now = DateTime::UNIX_EPOCH;
        Account {
            id: 1,
            member_id: 1,
            name: name.to_owned(),
            account_type: AccountType::Checking,
            provider: Some(PROVIDER.to_owned()),
            currency: currency.to_owned(),
            current_balance: Decimal::ZERO,
            cash_balance: None,
            last_synced_at: None,
            payment_due_amount: None,
            payment_due_date: None,
            card_nature: None,
            reward_points: None,
            external_account_id: Some("ext-1".to_owned()),
            is_manual: false,
            color: "#6366f1".to_owned(),
            iban: None,
            ticker: None,
            logo_url: None,
            logo_key: None,
            institution_name: None,
            institution_domain: None,
            requisition_id: None,
            opened_at: None,
            provider_invested_eur: None,
            provider_paid_in_eur: None,
            scheduled_payment_eur: None,
            scheduled_payment_cadence: None,
            deleted_at: None,
            parent_account_id,
            hidden: false,
            created_at: now,
            updated_at: now,
        }
    }

    fn data(
        name: &str,
        currency: &str,
        balance: Decimal,
        parent_external_id: Option<&str>,
    ) -> RevolutAccountData {
        RevolutAccountData {
            external_id: Some("ext-1".to_owned()),
            name: Some(name.to_owned()),
            account_type: AccountType::Checking,
            iban: None,
            balance,
            currency: currency.to_owned(),
            parent_external_id: parent_external_id.map(str::to_owned),
            txns: Vec::new(),
        }
    }

    #[test]
    fn existing_update_repairs_wallet_currency_detaches_mismatched_pocket_and_renames_current() {
        let now = DateTime::UNIX_EPOCH;

        // 1. Misclassified USD wallet repairs to EUR + real balance
        let mut wallet = stored_account("Revolut", "USD", None);
        apply_existing_update(
            &mut wallet,
            &data("Revolut", "EUR", Decimal::new(254, 2), None),
            "EUR",
            None,
            now,
        );
        assert_eq!(wallet.currency, "EUR");
        assert_eq!(wallet.current_balance, Decimal::new(254, 2));

        // 2. Previously attached USD pocket detaches cleanly and drops raw "CURRENT" label
        let mut usd_pocket = stored_account("CURRENT", "USD", Some(99));
        apply_existing_update(
            &mut usd_pocket,
            &data("Revolut USD", "USD", Decimal::ZERO, None),
            "USD",
            None,
            now,
        );
        assert_eq!(usd_pocket.parent_account_id, None);
        assert_eq!(usd_pocket.name, "Revolut USD");

        // 3. Previously detached EUR pocket attaches to parent wallet and drops raw "CURRENT"
        let mut eur_pocket = stored_account("CURRENT", "EUR", None);
        apply_existing_update(
            &mut eur_pocket,
            &data("Revolut EUR", "EUR", Decimal::new(254, 2), Some("wallet-1")),
            "EUR",
            Some(42),
            now,
        );
        assert_eq!(eur_pocket.parent_account_id, Some(42));
        assert_eq!(eur_pocket.name, "Revolut EUR");

        // 4. Custom user-chosen name is preserved
        let mut custom = stored_account("Mon compte principal", "EUR", None);
        apply_existing_update(
            &mut custom,
            &data("Revolut EUR", "EUR", Decimal::new(254, 2), Some("wallet-1")),
            "EUR",
            Some(42),
            now,
        );
        assert_eq!(custom.name, "Mon compte principal");
    }
}
