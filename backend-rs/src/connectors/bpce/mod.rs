//! BpceSyncEngine (track T6): what the BPCE banks (Caisse d'Epargne, Banque Populaire) share.
//! Their sidecars speak one contract, so the keypad login, the job state machine and the import
//! of current accounts, savings books, cards, loans, PER and assurance vie live here once; each
//! bank module adds its own /initiate body and the branding of its accounts.
//!
//! The password never reaches Picsou: /initiate answers with the bank's keypad, the user clicks
//! it in the browser, /keypad forwards only the positions and /complete waits for the phone
//! approval. The sync is on demand only, and the identifier of the last login is kept encrypted.

mod adapter;
mod commit;
mod per;
mod prepare;
pub mod web;

use std::collections::HashMap;
use std::fmt;
use std::sync::{Arc, LazyLock, Mutex, PoisonError};

use crate::db::DbHandle;
use async_trait::async_trait;
use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex as AsyncMutex;

pub use adapter::{InitiateResult, initiate as sidecar_initiate};

use super::sidecar::template::error;
use super::sidecar::{
    JobResult, NewSession, SidecarSession, SidecarSyncStatus, SyncError, SyncJob, SyncStrategy,
    SyncTemplate, session,
};
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::http::rate_limit::Policy;
use crate::ledger::members;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode};
use crate::ledger::sync_values;

/// The keypad is 10 images in 5 columns; anything else is a changed page.
const KEYPAD_KEY_COUNT: usize = 10;
const KEYPAD_COLUMNS: i32 = 5;
/// A key image is a small PNG (about 920 bytes live); bigger is not a key image.
const KEYPAD_MAX_IMAGE_LENGTH: usize = 16 * 1024;
const KEYPAD_IMAGE_PREFIX: &str = "data:image/png;base64,";
/// How long the user has to approve on the phone once the keypad was submitted. The sidecar
/// waits 150 s, so this ceiling is what bounds a stale process id afterwards.
const KEYPAD_APPROVAL_WINDOW_SECONDS: i64 = 180;
const MAX_UNSUPPORTED_ID: usize = 128;

/// What the stamped accounts carry: account.provider, and the institution whose logo they get.
pub struct Branding {
    pub provider: &'static str,
    /// (country, institution name) to look the logo up under, or None for no logo.
    pub logo: Option<(&'static str, &'static str)>,
}

/// BpceBank: everything that tells one BPCE bank from the other.
pub struct Bank {
    pub codes: SidecarBank,
    /// Prefixed to every account and transaction id, so two banks never collide.
    pub prefix: &'static str,
    pub auth_policy: &'static Policy,
    pub keypad_policy: &'static Policy,
    /// The branding of a session's sync, from its stored region.
    pub branding: fn(Option<&str>) -> Branding,
}

impl Bank {
    pub fn provider(&self) -> &'static str {
        self.codes.label()
    }

    fn template(&'static self) -> SyncTemplate<Strategy> {
        SyncTemplate::new(self.codes, Strategy { bank: self }, false)
    }
}

/// What the row a login opens carries besides the session: the identifier that logged in
/// (encrypted) and Banque Populaire's regional bank, both captured when the login starts.
#[derive(Clone)]
pub struct SessionSeed {
    pub encrypted_credentials: Option<String>,
    pub region: Option<String>,
}

/// A login in flight. Only one deadline is set: the keypad's while the user clicks, the
/// approval window's once the digits were sent.
#[derive(Clone)]
struct PendingLogin {
    member_id: i64,
    keypad_expires_at: Option<DateTime<Utc>>,
    approval_expires_at: Option<DateTime<Utc>>,
    seed: SessionSeed,
    generation: u64,
}

impl fmt::Debug for PendingLogin {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("PendingLogin")
            .field("member_id", &self.member_id)
            .finish_non_exhaustive()
    }
}

impl PendingLogin {
    fn deadline(&self) -> Option<DateTime<Utc>> {
        self.approval_expires_at.or(self.keypad_expires_at)
    }
}

#[derive(Default)]
struct LoginState {
    pending: HashMap<String, PendingLogin>,
    generations: HashMap<i64, u64>,
}

/// The logins of one bank in one running application: process-local on purpose, a restart drops
/// the attempt and the user starts again.
#[derive(Default)]
struct Logins {
    state: Mutex<LoginState>,
    /// sessionWriteLock: a session write and a disconnect of the same bank never interleave.
    write: AsyncMutex<()>,
}

impl Logins {
    fn lock(&self) -> std::sync::MutexGuard<'_, LoginState> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn generation(&self, member_id: i64) -> u64 {
        self.lock()
            .generations
            .get(&member_id)
            .copied()
            .unwrap_or(0)
    }
}

/// The application a login belongs to: the state's address and its database, so two test
/// applications never see each other's logins even when one reuses a freed address.
type AppKey = (usize, String, SidecarBank);

static LOGINS: LazyLock<Mutex<HashMap<AppKey, Arc<Logins>>>> = LazyLock::new(Mutex::default);

fn logins(state: &AppState, bank: &Bank) -> Arc<Logins> {
    let inner: &crate::Inner = state;
    let key = (
        std::ptr::from_ref(inner) as usize,
        state.db.identifier(),
        bank.codes,
    );
    LOGINS
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .entry(key)
        .or_default()
        .clone()
}

fn attempt_expired(bank: &Bank) -> SyncError {
    error(
        SidecarErrorCode::AuthAttemptExpired,
        format!(
            "This {} sign-in attempt expired. Please start again.",
            bank.provider()
        ),
    )
}

fn connection_removed(bank: &Bank) -> SyncError {
    error(
        SidecarErrorCode::AuthAttemptExpired,
        format!(
            "The {} connection was removed during the login",
            bank.provider()
        ),
    )
}

/// The pending login to show: the bank's keypad, to be clicked by the user, and its lifetime.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InitiateResponse {
    pub process_id: String,
    pub keypad: KeypadView,
    pub expires_in_seconds: i32,
}

#[derive(Debug, Clone, Serialize)]
pub struct KeypadView {
    pub images: Vec<String>,
    pub columns: i32,
}

/// Starts a login. \`call\` makes the one sidecar call (no retry); \`seed\` is what the row this
/// login opens will carry. A failure leaves any stored session as it was.
pub async fn initiate(
    state: &AppState,
    bank: &Bank,
    member_id: i64,
    seed: SessionSeed,
    call: impl Future<Output = Result<InitiateResult, SyncError>>,
) -> AppResult<InitiateResponse> {
    let logins = logins(state, bank);
    let generation = logins.generation(member_id);
    let result = call.await?;
    let (Some(process_id), Some(keypad)) = (
        result.process_id.filter(|p| !p.trim().is_empty()),
        result.keypad,
    ) else {
        return Err(unusable_challenge(bank).into());
    };
    let expires_in_seconds = result.expires_in_seconds.unwrap_or(0);
    if expires_in_seconds <= 0 {
        return Err(unusable_challenge(bank).into());
    }
    let keypad = safe_keypad(bank, keypad)?;
    let now = state.clock.now();
    let pending = PendingLogin {
        member_id,
        keypad_expires_at: Some(now + Duration::seconds(i64::from(expires_in_seconds))),
        approval_expires_at: None,
        seed,
        generation,
    };
    {
        let _write = logins.write.lock().await;
        let mut guard = logins.lock();
        // Swept on every start: a stale process id must never be reachable again.
        guard
            .pending
            .retain(|_, p| p.deadline().is_some_and(|d| d > now));
        register(bank, &mut guard, process_id.clone(), pending)?;
    }
    Ok(InitiateResponse {
        process_id,
        keypad,
        expires_in_seconds,
    })
}

fn unusable_challenge(bank: &Bank) -> SyncError {
    error(
        SidecarErrorCode::UpstreamFormatChanged,
        format!(
            "{} did not return a usable sign-in challenge",
            bank.provider()
        ),
    )
}

/// Puts a login in the map unless a disconnect ran since it started. Called under the write lock.
fn register(
    bank: &Bank,
    state: &mut LoginState,
    process_id: String,
    pending: PendingLogin,
) -> Result<(), SyncError> {
    let current = state
        .generations
        .get(&pending.member_id)
        .copied()
        .unwrap_or(0);
    if current != pending.generation {
        return Err(connection_removed(bank));
    }
    state.pending.insert(process_id, pending);
    Ok(())
}

/// A keypad that cannot be shown to a user as it is becomes KEYPAD_CHANGED, never registered.
fn safe_keypad(bank: &Bank, keypad: adapter::Keypad) -> Result<KeypadView, SyncError> {
    let images: Option<Vec<String>> = keypad
        .images
        .filter(|images| images.len() == KEYPAD_KEY_COUNT)
        .and_then(|images| images.into_iter().collect());
    match images {
        Some(images)
            if keypad.columns == Some(KEYPAD_COLUMNS)
                && images.iter().all(|i| displayable_key_image(i)) =>
        {
            Ok(KeypadView {
                images,
                columns: KEYPAD_COLUMNS,
            })
        }
        _ => Err(error(
            SidecarErrorCode::KeypadChanged,
            format!(
                "{} changed its login page. Nothing was sent.",
                bank.provider()
            ),
        )),
    }
}

/// A PNG data URI under the size cap whose payload is plain base64: the string goes to the UI as
/// it is, so nothing else may ride along in it.
fn displayable_key_image(image: &str) -> bool {
    let Some(payload) = image.strip_prefix(KEYPAD_IMAGE_PREFIX) else {
        return false;
    };
    if image.encode_utf16().count() > KEYPAD_MAX_IMAGE_LENGTH || payload.is_empty() {
        return false;
    }
    let body = payload.trim_end_matches('=');
    payload.len() - body.len() <= 2
        && !body.is_empty()
        && body
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'+' || b == b'/')
}

/// Removes a pending login for its owner, or refuses without burning the owner's attempt.
fn take(
    bank: &Bank,
    logins: &Logins,
    process_id: &str,
    member_id: i64,
) -> Result<PendingLogin, SyncError> {
    let mut guard = logins.lock();
    match guard.pending.get(process_id) {
        Some(pending) if pending.member_id == member_id => {}
        _ => return Err(attempt_expired(bank)),
    }
    guard
        .pending
        .remove(process_id)
        .ok_or_else(|| attempt_expired(bank))
}

/// Clicks the digits the user chose. Single use, no retry: the process is taken out before the
/// sidecar is called. The positions are the password in disguise and are never logged.
pub async fn submit_keypad(
    state: &AppState,
    bank: &Bank,
    process_id: &str,
    positions: &[i32],
    member_id: i64,
) -> AppResult<()> {
    let logins = logins(state, bank);
    let pending = take(bank, &logins, process_id, member_id)?;
    if pending.approval_expires_at.is_some() {
        return Err(attempt_expired(bank).into());
    }
    if pending
        .keypad_expires_at
        .is_none_or(|expires| expires <= state.clock.now())
    {
        return Err(error(
            SidecarErrorCode::KeypadExpired,
            format!("The {} keypad expired. Start again.", bank.provider()),
        )
        .into());
    }
    adapter::submit_keypad(state, bank, process_id, positions).await?;
    let approving = PendingLogin {
        keypad_expires_at: None,
        approval_expires_at: Some(
            state.clock.now() + Duration::seconds(KEYPAD_APPROVAL_WINDOW_SECONDS),
        ),
        ..pending
    };
    let _write = logins.write.lock().await;
    register(bank, &mut logins.lock(), process_id.to_owned(), approving)?;
    Ok(())
}

/// Finishes a login the user approved on the phone and stores its encrypted session; it does not
/// queue a sync, the user triggers it.
pub async fn complete(
    state: &AppState,
    bank: &Bank,
    process_id: &str,
    member_id: i64,
) -> AppResult<()> {
    let logins = logins(state, bank);
    let pending = take(bank, &logins, process_id, member_id)?;
    if pending
        .approval_expires_at
        .is_none_or(|expires| expires <= state.clock.now())
    {
        return Err(attempt_expired(bank).into());
    }
    let plain_state = adapter::complete(state, bank, process_id)
        .await?
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| {
            error(
                SidecarErrorCode::UpstreamFormatChanged,
                format!("{} did not return a session", bank.provider()),
            )
        })?;
    let _write = logins.write.lock().await;
    if logins.generation(member_id) != pending.generation {
        return Err(connection_removed(bank).into());
    }
    let mut tx = state.db.begin().await?;
    if members::find_by_id(&mut tx, member_id).await?.is_none() {
        return Err(AppError::NotFound("Family member not found".to_owned()));
    }
    if let Some(previous) = session::find_for_update(&mut tx, bank.codes, member_id).await? {
        session::delete(&mut tx, previous.id).await?;
    }
    let now = state.clock.now();
    let draft = NewSession {
        bank: bank.codes,
        member_id,
        session_state: state.crypto.encrypt(&plain_state)?,
        encrypted_credentials: pending.seed.encrypted_credentials,
        unsupported_contracts: None,
        region: pending.seed.region,
        validated_at: now,
    };
    session::insert(&mut tx, &draft, now).await?;
    tx.commit().await?;
    Ok(())
}

/// Queues an import of the stored session; one already in flight is left alone.
pub async fn queue_sync(
    state: &AppState,
    bank: &'static Bank,
    member_id: i64,
) -> AppResult<StatusResponse> {
    let session = bank.template().queue(state, member_id, false).await?;
    Ok(status_of(state, bank, &session))
}

pub async fn get_status(
    state: &AppState,
    bank: &Bank,
    member_id: i64,
) -> AppResult<StatusResponse> {
    Ok(session::find(&state.db, bank.codes, member_id)
        .await?
        .map_or_else(StatusResponse::inactive, |s| status_of(state, bank, &s)))
}

/// Deletes the stored session (the bank session itself is not revoked) and cancels any login in
/// flight, so an approval on the phone after this stores nothing. Whether a session was deleted.
pub async fn clear_session(
    state: &AppState,
    bank: &'static Bank,
    member_id: i64,
) -> AppResult<bool> {
    let logins = logins(state, bank);
    let _write = logins.write.lock().await;
    {
        let mut guard = logins.lock();
        *guard.generations.entry(member_id).or_insert(0) += 1;
        guard.pending.retain(|_, p| p.member_id != member_id);
    }
    bank.template().clear(state, member_id).await
}

/// Persisted QUEUED or RUNNING syncs cannot survive a restart: they become a retryable failure.
pub async fn recover_interrupted(state: &AppState, bank: &'static Bank) -> sqlx::Result<u64> {
    bank.template().recover_interrupted(state).await
}

/// A contract the sidecar saw but Picsou does not import, as stored on the session row.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredUnsupported {
    #[serde(default)]
    external_id: Option<String>,
    #[serde(default)]
    family_code: Option<String>,
    #[serde(default)]
    sub_family_code: Option<String>,
}

/// UnsupportedContract as the status shows it.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UnsupportedContract {
    pub external_id: String,
    pub family_code: Option<String>,
    pub sub_family_code: Option<String>,
}

impl UnsupportedContract {
    /// Bounded whatever was sent or stored: an out-of-shape code becomes "?".
    fn bounded(
        external_id: Option<&str>,
        family_code: Option<&str>,
        sub_family_code: Option<&str>,
    ) -> Self {
        Self {
            external_id: sync_values::limit(external_id, MAX_UNSUPPORTED_ID, "?"),
            family_code: unsupported_code(family_code),
            sub_family_code: unsupported_code(sub_family_code),
        }
    }
}

/// Null when no code was sent, the code when it is short and plain, else "?".
fn unsupported_code(raw: Option<&str>) -> Option<String> {
    let cleaned = sync_values::clean(raw)?;
    let plain = (1..=8).contains(&cleaned.len())
        && cleaned
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-');
    Some(if plain { cleaned } else { "?".to_owned() })
}

fn serialize_unsupported(unsupported: &[UnsupportedContract]) -> String {
    let stored: Vec<StoredUnsupported> = unsupported
        .iter()
        .map(|u| StoredUnsupported {
            external_id: Some(u.external_id.clone()),
            family_code: u.family_code.clone(),
            sub_family_code: u.sub_family_code.clone(),
        })
        .collect();
    serde_json::to_string(&stored).expect("unsupported contracts serialize")
}

/// A list stored by an earlier version was not bounded: the write-side bounds apply on read, and
/// an unreadable list is an empty one.
fn parse_unsupported(bank: &Bank, json: Option<&str>) -> Vec<UnsupportedContract> {
    let Some(json) = json.filter(|j| !j.trim().is_empty()) else {
        return Vec::new();
    };
    match serde_json::from_str::<Option<Vec<Option<StoredUnsupported>>>>(json) {
        Ok(stored) => stored
            .unwrap_or_default()
            .into_iter()
            .flatten()
            .filter(|u| u.external_id.is_some() && u.family_code.is_some())
            .map(|u| {
                UnsupportedContract::bounded(
                    u.external_id.as_deref(),
                    u.family_code.as_deref(),
                    u.sub_family_code.as_deref(),
                )
            })
            .collect(),
        Err(_) => {
            tracing::warn!(
                "Stored {} unsupported-contract list is unreadable",
                bank.provider()
            );
            Vec::new()
        }
    }
}

/// SessionStatusResponse of the BPCE banks.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusResponse {
    pub is_active: bool,
    pub sync_status: SidecarSyncStatus,
    pub last_sync_started_at: Option<DateTime<Utc>>,
    pub last_sync_completed_at: Option<DateTime<Utc>>,
    pub last_sync_error: Option<SidecarErrorCode>,
    pub unsupported: Vec<UnsupportedContract>,
    /// An identifier is remembered: a reconnect can send a blank one. Never the value itself.
    pub remembered: bool,
    pub unsupported_count: usize,
}

impl StatusResponse {
    fn inactive() -> Self {
        Self {
            is_active: false,
            sync_status: SidecarSyncStatus::Idle,
            last_sync_started_at: None,
            last_sync_completed_at: None,
            last_sync_error: None,
            unsupported: Vec::new(),
            remembered: false,
            unsupported_count: 0,
        }
    }
}

fn status_of(state: &AppState, bank: &Bank, session: &SidecarSession) -> StatusResponse {
    let unsupported = parse_unsupported(bank, session.unsupported_contracts.as_deref());
    StatusResponse {
        is_active: session.active,
        sync_status: session.sync_status,
        last_sync_started_at: session.last_sync_started_at,
        last_sync_completed_at: session.last_sync_completed_at,
        last_sync_error: session.last_sync_error,
        unsupported_count: unsupported.len(),
        unsupported,
        remembered: session
            .encrypted_credentials
            .as_deref()
            .is_some_and(|c| read_customer_id(state, bank, c).is_some()),
    }
}

/// The identifier of the last successful login, the region it was used at, and the ciphertext
/// it was read from.
pub struct StoredLogin {
    pub customer_id: String,
    pub region: Option<String>,
    pub ciphertext: String,
}

impl fmt::Debug for StoredLogin {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("StoredLogin[redacted]")
    }
}

/// The remembered login, or INVALID_CREDENTIALS when there is none or it cannot be read (an
/// unreadable one is forgotten).
pub async fn stored_login(state: &AppState, bank: &Bank, member_id: i64) -> AppResult<StoredLogin> {
    if let Some(session) = session::find(&state.db, bank.codes, member_id).await?
        && let Some(encrypted) = session.encrypted_credentials
    {
        if let Some(customer_id) = read_customer_id(state, bank, &encrypted) {
            return Ok(StoredLogin {
                customer_id,
                region: session.region,
                ciphertext: encrypted,
            });
        }
        forget_customer_id(state, bank, member_id, &encrypted).await?;
    }
    Err(error(
        SidecarErrorCode::InvalidCredentials,
        format!(
            "No {} identifier is remembered. Please enter it.",
            bank.provider()
        ),
    )
    .into())
}

/// The identifier inside a stored ciphertext, or None when it is unreadable or blank.
fn read_customer_id(state: &AppState, bank: &Bank, encrypted: &str) -> Option<String> {
    let parsed = state
        .crypto
        .decrypt(encrypted)
        .map_err(|_| "CryptoError")
        .and_then(|plain| {
            serde_json::from_str::<serde_json::Value>(&plain).map_err(|_| "JsonProcessingException")
        });
    match parsed {
        Ok(value) => {
            let customer_id = match value.get("customerId") {
                Some(serde_json::Value::String(s)) => s.clone(),
                Some(v @ (serde_json::Value::Number(_) | serde_json::Value::Bool(_))) => {
                    v.to_string()
                }
                _ => String::new(),
            };
            sync_values::clean(Some(&customer_id)).map(|_| customer_id)
        }
        Err(kind) => {
            tracing::warn!(
                "Stored {} identifier is unreadable (type={kind})",
                bank.provider()
            );
            None
        }
    }
}

/// Forgets the remembered identifier, but only if it is still the one that was read: a login
/// that succeeded meanwhile stored a new ciphertext, which must survive.
pub async fn forget_customer_id(
    state: &AppState,
    bank: &Bank,
    member_id: i64,
    read_ciphertext: &str,
) -> AppResult<()> {
    let mut tx = state.db.begin().await?;
    if let Some(mut current) = session::find_for_update(&mut tx, bank.codes, member_id).await?
        && current.encrypted_credentials.as_deref() == Some(read_ciphertext)
    {
        current.encrypted_credentials = None;
        session::save(&mut tx, &current, state.clock.now()).await?;
    }
    tx.commit().await?;
    Ok(())
}

/// The identifier that logs in, encrypted as the session row stores it.
pub fn encrypt_customer_id(state: &AppState, customer_id: &str) -> AppResult<String> {
    let json = serde_json::json!({ "customerId": customer_id }).to_string();
    Ok(state.crypto.encrypt(&json)?)
}

/// Whether a failed /initiate means the bank rejected the identifier it was given.
pub fn rejects_identifier(bank: &Bank, error: &SyncError) -> bool {
    sync_values::error_code(
        error.code(),
        bank.codes,
        SidecarErrorCode::UpstreamUnavailable,
    ) == SidecarErrorCode::InvalidCredentials
}

pub struct Strategy {
    bank: &'static Bank,
}

/// A validated snapshot, the label its accounts get and the logo looked up for them.
pub struct Fetched {
    snapshot: prepare::PreparedSnapshot,
    provider: &'static str,
    logo_url: Option<String>,
}

#[async_trait]
impl SyncStrategy for Strategy {
    type Prepared = Fetched;

    async fn fetch(&self, state: &AppState, job: &SyncJob) -> JobResult<Fetched> {
        let fetched = adapter::fetch_accounts(state, self.bank, &job.plain_state).await?;
        let snapshot = prepare::snapshot(state, self.bank, fetched).await?;
        // Read once per sync, after the snapshot was accepted: a refused snapshot costs no lookup.
        let region = session::find(&state.db, self.bank.codes, job.member_id)
            .await?
            .filter(|s| s.id == job.session_id)
            .and_then(|s| s.region);
        let branding = (self.bank.branding)(region.as_deref());
        let logo_url = match branding.logo {
            Some((country, name)) => {
                state
                    .connectors
                    .seams
                    .bank_logos
                    .logo_url(country, name)
                    .await
            }
            None => None,
        };
        Ok(Fetched {
            snapshot,
            provider: branding.provider,
            logo_url,
        })
    }

    async fn commit(
        &self,
        state: &AppState,
        conn: &mut DbHandle<'_>,
        session: &mut SidecarSession,
        job: &SyncJob,
        fetched: &Fetched,
        synced_at: DateTime<Utc>,
    ) -> JobResult<()> {
        commit::write(
            state,
            conn,
            self.bank,
            job.member_id,
            &fetched.snapshot,
            fetched.provider,
            fetched.logo_url.as_deref(),
            synced_at,
        )
        .await?;
        session.unsupported_contracts = Some(serialize_unsupported(&fetched.snapshot.unsupported));
        Ok(())
    }

    async fn after_commit(&self, state: &AppState, _job: &SyncJob, fetched: &Fetched) {
        for account in &fetched.snapshot.accounts {
            if let Some(per) = &account.per {
                per::backfill(state, per).await;
            }
        }
    }

    fn describe(&self, fetched: &Fetched) -> String {
        format!("accounts={}", fetched.snapshot.accounts.len())
    }

    fn redacts_unexpected_failures(&self) -> bool {
        true
    }
}
