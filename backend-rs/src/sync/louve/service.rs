//! LouveSyncService: the OAuth attempt and callback, the sync, the daily entry and the
//! disconnect. No transaction spans a network call except the refresh, which holds the connection
//! row lock on purpose: Louve rotates refresh tokens, so the new set is committed before it is
//! used and two runs never refresh the same token. Runs are fenced on the row id and
//! grant_generation: one overtaken by a reconnect or a disconnect writes nothing.

use chrono::{DateTime, Utc};
use serde::Serialize;
use sqlx::PgConnection;
use subtle::ConstantTimeEq;

use super::error::{Failure, Kind, LouveResult};
use super::repo::{self, Connection};
use super::{adapter, mapper, oauth, registry};
use crate::AppState;
use crate::db::{AsDbHandle, DbHandle};
use crate::error::AppResult;
use crate::ledger::members;
use crate::ledger::sidecar::{SidecarErrorCode, SourceSyncResult, SyncStatus};

/// The source name in the member sync.
pub const SOURCE: &str = "louve";
/// The SPA's callback; the redirect URI is the instance origin plus this.
pub const CALLBACK_PATH: &str = "/sync/louve/callback";
const ATTEMPT_TTL_MINUTES: i64 = 10;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StartResponse {
    pub authorize_url: String,
}

/// The panel's view: never a token. connected is true once a token set is stored.
#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionStatusResponse {
    pub connected: bool,
    pub status: Option<String>,
    pub last_synced_at: Option<DateTime<Utc>>,
}

/// One sync run: the row and the grant it started under. A rotation keeps the grant.
#[derive(Debug, Clone, Copy)]
struct Run {
    member_id: i64,
    connection_id: i64,
    generation: i64,
}

impl Run {
    fn matches(&self, row: &Connection) -> bool {
        row.id == self.connection_id && row.grant_generation == self.generation
    }
}

struct Attempt {
    connection_id: i64,
    generation: i64,
    client_id: String,
    redirect_uri: String,
    verifier: String,
}

fn expired_attempt() -> Failure {
    Failure::sync(
        SidecarErrorCode::AuthAttemptExpired,
        "This Louve Invest connection attempt is no longer valid. Start again.",
    )
}

fn decrypt_or_none(state: &AppState, encrypted: Option<&str>) -> Option<String> {
    let encrypted = encrypted?;
    match state.crypto.decrypt(encrypted) {
        Ok(plain) => Some(plain),
        Err(_) => {
            tracing::warn!("Stored Louve Invest secret is unreadable (type=CryptoError)");
            None
        }
    }
}

fn constant_time_equals(expected: Option<&str>, actual: &str) -> bool {
    match expected {
        Some(expected) if !expected.is_empty() => {
            expected.as_bytes().ct_eq(actual.as_bytes()).into()
        }
        _ => false,
    }
}

async fn lock_member(conn: &mut PgConnection, member_id: i64) -> LouveResult<()> {
    match members::lock(&mut *conn, member_id).await? {
        Some(_) => Ok(()),
        None => Err(Failure::App(crate::error::AppError::NotFound(
            "Family member not found".to_owned(),
        ))),
    }
}

/// Records a pending attempt for redirect_uri (validated by the controller) and returns Louve's
/// consent URL. A previous attempt is replaced; stored tokens are kept until a new consent.
pub async fn start_connection(
    state: &AppState,
    member_id: i64,
    redirect_uri: &str,
) -> LouveResult<StartResponse> {
    let client_id = registry::client_id_for(state, redirect_uri).await?;
    let verifier = oauth::random_token();
    let oauth_state = oauth::random_token();
    let encrypted_verifier = state.crypto.encrypt(&verifier)?;
    let mut tx = state.db.begin().await?;
    lock_member(&mut tx, member_id).await?;
    let existing = repo::lock_by_member(&mut tx.as_handle(), member_id)
        .await?
        .map(|row| row.id);
    let attempt = repo::Attempt {
        state: &oauth_state,
        verifier: &encrypted_verifier,
        redirect_uri,
        client_id: &client_id,
    };
    repo::save_attempt(&mut tx, member_id, existing, &attempt, state.clock.now()).await?;
    tx.commit().await?;
    Ok(StartResponse {
        authorize_url: oauth::authorize_uri(
            state,
            &client_id,
            redirect_uri,
            &oauth_state,
            &oauth::challenge(&verifier),
        ),
    })
}

/// Finishes the connection from Louve's callback, then runs the first sync. The issuer must be
/// Louve (RFC 9207), the state the member's live attempt (constant time, ten minutes), and the
/// attempt is spent before the exchange, so a replayed callback finds nothing.
pub async fn complete_connection(
    state: &AppState,
    member_id: i64,
    code: &str,
    oauth_state: &str,
    issuer: &str,
) -> LouveResult<ConnectionStatusResponse> {
    if issuer != oauth::ISSUER {
        return Err(Failure::sync(
            SidecarErrorCode::AuthAttemptExpired,
            "This answer did not come from Louve Invest. Start the connection again.",
        ));
    }
    let attempt = spend_attempt(state, member_id, oauth_state).await?;
    let plain = match adapter::exchange_code(
        state,
        &attempt.client_id,
        &attempt.redirect_uri,
        code,
        &attempt.verifier,
    )
    .await
    {
        Ok(plain) => plain,
        Err(failure) => {
            forget_quietly(state, &failure).await;
            return Err(failure);
        }
    };
    let replaced = match store_grant(state, member_id, &attempt, &plain).await {
        Ok(replaced) => replaced,
        Err(failure) => {
            // Only the grant this callback just obtained: the connection that won keeps its own.
            adapter::revoke(state, &plain).await;
            return Err(failure);
        }
    };
    if let Some(previous) = replaced {
        adapter::revoke(state, &previous).await;
    }
    match sync(state, member_id).await {
        Ok(_) => {}
        // Connected all the same: the status says the first sync failed, and the panel shows it.
        Err(Failure::Sync { code, .. }) => {
            tracing::warn!("First Louve Invest sync failed (member={member_id}; code={code})");
        }
        Err(other) => return Err(other),
    }
    Ok(get_status(state, member_id).await?)
}

async fn spend_attempt(
    state: &AppState,
    member_id: i64,
    oauth_state: &str,
) -> LouveResult<Attempt> {
    let mut tx = state.db.begin().await?;
    let row = repo::lock_by_member(&mut tx.as_handle(), member_id)
        .await?
        .ok_or_else(expired_attempt)?;
    let ttl = chrono::Duration::minutes(ATTEMPT_TTL_MINUTES);
    let live = row.oauth_state.is_some()
        && row
            .oauth_started_at
            .is_some_and(|started| started + ttl > state.clock.now());
    if !live || !constant_time_equals(row.oauth_state.as_deref(), oauth_state) {
        return Err(expired_attempt());
    }
    // An unreadable verifier rolls the spend back with the refusal, as Java's transaction does.
    let verifier =
        decrypt_or_none(state, row.oauth_verifier.as_deref()).ok_or_else(expired_attempt)?;
    repo::clear_attempt(&mut tx, row.id, state.clock.now()).await?;
    tx.commit().await?;
    Ok(Attempt {
        connection_id: row.id,
        generation: row.grant_generation,
        client_id: row.oauth_client_id.unwrap_or_default(),
        redirect_uri: row.oauth_redirect_uri.unwrap_or_default(),
        verifier,
    })
}

/// Stores the token set if the row is still the one, at the generation, the attempt was spent
/// on: a disconnect, or a newer consent stored meanwhile, wins. Returns the grant it replaces.
async fn store_grant(
    state: &AppState,
    member_id: i64,
    attempt: &Attempt,
    plain: &str,
) -> LouveResult<Option<String>> {
    let mut tx = state.db.begin().await?;
    let row = repo::lock_by_member(&mut tx.as_handle(), member_id)
        .await?
        .filter(|row| row.id == attempt.connection_id && row.grant_generation == attempt.generation)
        .ok_or_else(expired_attempt)?;
    let previous = decrypt_or_none(state, row.token_state.as_deref());
    let encrypted = state.crypto.encrypt(plain)?;
    repo::store_grant(&mut tx, row.id, &encrypted, state.clock.now()).await?;
    tx.commit().await?;
    Ok(previous)
}

/// Reads the portfolio and writes it onto the member's SCPI accounts. A refused grant turns the
/// connection REAUTH_REQUIRED; any other failure ERROR. The tokens stay stored either way, so a
/// disconnect can still revoke them.
pub async fn sync(state: &AppState, member_id: i64) -> LouveResult<ConnectionStatusResponse> {
    let row = repo::find_by_member(&state.db, member_id)
        .await?
        .filter(|row| row.token_state.is_some())
        .ok_or_else(|| {
            Failure::sync(
                SidecarErrorCode::SessionExpired,
                "No Louve Invest connection. Connect first.",
            )
        })?;
    let run = Run {
        member_id,
        connection_id: row.id,
        generation: row.grant_generation,
    };
    match run_sync(state, run).await {
        Ok(()) => {}
        Err(
            failure @ Failure::Sync {
                kind: Kind::Transient(reason),
                ..
            },
        ) => {
            // Superseded or busy: nothing was written, and the status is not this run's.
            tracing::info!("Louve Invest sync not applied (member={member_id}; reason={reason})");
            return Err(failure);
        }
        Err(failure @ Failure::Sync { code, .. }) => {
            let status = if matches!(
                code,
                SidecarErrorCode::SessionExpired | SidecarErrorCode::InvalidSessionState
            ) {
                repo::STATUS_REAUTH_REQUIRED
            } else {
                repo::STATUS_ERROR
            };
            mark_status(state, run, status).await;
            tracing::warn!("Louve Invest sync failed (member={member_id}; code={code})");
            return Err(failure);
        }
        Err(Failure::App(_)) => {
            mark_status(state, run, repo::STATUS_ERROR).await;
            // By type only: an unexpected error may carry a payload fragment.
            tracing::error!(
                "Louve Invest sync failed unexpectedly (member={member_id}; type=AppError)"
            );
            return Err(Failure::sync(
                SidecarErrorCode::InternalError,
                "Louve Invest sync failed. Try again in a moment.",
            ));
        }
    }
    Ok(get_status(state, member_id).await?)
}

async fn run_sync(state: &AppState, run: Run) -> LouveResult<()> {
    let used = fresh_state(state, run, None).await?;
    let snapshot = match adapter::read(state, &used).await {
        Err(failure) if failure.kind() == Some(&Kind::Unauthorized) => {
            tracing::info!(
                "Louve Invest refused the access token before its expiry, refreshing once"
            );
            let fresh = fresh_state(state, run, Some(&used)).await?;
            adapter::read(state, &fresh).await?
        }
        other => other?,
    };
    commit(state, run, &snapshot).await
}

/// The run's connection, locked: the same row, the same grant, still holding tokens.
async fn current(conn: &mut DbHandle<'_>, run: Run) -> LouveResult<Connection> {
    repo::lock_by_member(conn, run.member_id)
        .await?
        .filter(|row| run.matches(row) && row.token_state.is_some())
        .ok_or_else(|| {
            Failure::transient(
                "superseded",
                "The Louve Invest connection changed during the sync. Nothing was written.",
            )
        })
}

/// The state to read with, refreshed and committed first when needed, under the row lock.
/// refused is the state Louve just refused, or None for the run's first read; when another run
/// rotated the tokens in between (same grant), its state is returned as it is.
async fn fresh_state(state: &AppState, run: Run, refused: Option<&str>) -> LouveResult<String> {
    let result = async {
        let mut tx = state.db.begin().await?;
        let row = current(&mut tx.as_handle(), run).await?;
        let plain = decrypt_or_none(state, row.token_state.as_deref()).ok_or_else(|| {
            Failure::sync(
                SidecarErrorCode::InvalidSessionState,
                "The stored Louve Invest access is unusable. Please reconnect.",
            )
        })?;
        let rotated_meanwhile = refused.is_some_and(|r| r != plain);
        if rotated_meanwhile || (refused.is_none() && !adapter::needs_refresh(state, &plain)?) {
            return Ok(plain);
        }
        let next = adapter::refresh(state, &plain).await?;
        let encrypted = state.crypto.encrypt(&next)?;
        repo::store_tokens(&mut tx, row.id, &encrypted, state.clock.now()).await?;
        tx.commit().await?;
        Ok(next)
    }
    .await;
    if let Err(failure) = &result {
        // After the rolled-back transaction, so the registration really goes.
        forget_quietly(state, failure).await;
    }
    result
}

/// Writes the snapshot in one transaction, member row locked like the CORUM and Sofidy commits,
/// so two writers cannot create the same account.
async fn commit(state: &AppState, run: Run, snapshot: &adapter::Snapshot) -> LouveResult<()> {
    let mapped = mapper::map(snapshot)?;
    let mut tx = state.db.begin().await?;
    lock_member(&mut tx, run.member_id).await?;
    let row = current(&mut tx.as_handle(), run).await?;
    // One instant for the funds and the connection: the panel matches the run by it.
    let now = state.clock.now();
    let synced_at = DateTime::from_timestamp_micros(now.timestamp_micros()).unwrap_or(now);
    let outcomes = state
        .sync
        .louve
        .scpi
        .reconcile(
            &mut tx,
            run.member_id,
            &mapped.funds,
            &mapped.unsupported,
            synced_at,
        )
        .await?;
    repo::set_synced(&mut tx, row.id, synced_at, now).await?;
    tx.commit().await?;
    let outcomes: Vec<String> = outcomes
        .iter()
        .map(|(code, outcome)| format!("{code}={}", outcome.as_str()))
        .collect();
    tracing::info!(
        "Louve Invest fund outcomes (member={}; funds={{{}}})",
        run.member_id,
        outcomes.join(", ")
    );
    Ok(())
}

/// Records a failure on the run's own connection only, never on one that replaced it.
async fn mark_status(state: &AppState, run: Run, status: &str) {
    let result: LouveResult<()> = async {
        let mut tx = state.db.begin().await?;
        if let Some(row) = repo::lock_by_member(&mut tx.as_handle(), run.member_id).await?
            && run.matches(&row)
        {
            repo::set_status(&mut tx, row.id, status, state.clock.now()).await?;
        }
        tx.commit().await?;
        Ok(())
    }
    .await;
    if result.is_err() {
        tracing::error!("Could not record the Louve Invest sync status (type=SyncError)");
    }
}

/// Drops a refused registration without ever replacing the refusal.
async fn forget_quietly(state: &AppState, failure: &Failure) {
    if let Some(Kind::InvalidClient {
        client_id,
        redirect_uri,
    }) = failure.kind()
        && registry::forget(state, redirect_uri.as_deref(), client_id.as_deref())
            .await
            .is_err()
    {
        tracing::warn!(
            "Could not drop the refused Louve Invest client registration (type=AppError)"
        );
    }
}

/// The daily entry: no tokens is SKIPPED_NOT_CONNECTED; a refused grant is NEEDS_REAUTH without
/// calling Louve; otherwise the sync runs and its failure is classified.
pub async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    let result = |status: SyncStatus, message: &str| SourceSyncResult {
        source: SOURCE.to_owned(),
        status,
        message: message.to_owned(),
    };
    let row = match repo::find_by_member(&state.db, member_id).await {
        Ok(row) => row.filter(|row| row.token_state.is_some()),
        Err(_) => return unexpected(member_id),
    };
    let Some(row) = row else {
        return result(SyncStatus::SkippedNotConnected, "No connection");
    };
    if row.status == repo::STATUS_REAUTH_REQUIRED {
        return result(
            SyncStatus::NeedsReauth,
            SidecarErrorCode::SessionExpired.as_str(),
        );
    }
    match sync(state, member_id).await {
        Ok(_) => result(SyncStatus::Synced, ""),
        Err(Failure::Sync { code, message, .. }) => {
            SourceSyncResult::from_sync_exception(SOURCE, Some(code.as_str()), Some(&message))
        }
        Err(Failure::App(_)) => unexpected(member_id),
    }
}

fn unexpected(member_id: i64) -> SourceSyncResult {
    tracing::error!(
        "Louve Invest scheduled sync failed unexpectedly (member={member_id}; type=AppError)"
    );
    SourceSyncResult {
        source: SOURCE.to_owned(),
        status: SyncStatus::Failed,
        message: "Unexpected sync error".to_owned(),
    }
}

pub async fn get_status(state: &AppState, member_id: i64) -> AppResult<ConnectionStatusResponse> {
    Ok(match repo::find_by_member(&state.db, member_id).await? {
        Some(row) => ConnectionStatusResponse {
            connected: row.token_state.is_some(),
            status: Some(row.status),
            last_synced_at: row.last_synced_at,
        },
        None => ConnectionStatusResponse {
            connected: false,
            status: None,
            last_synced_at: None,
        },
    })
}

/// What deleting the connection inside a caller's transaction left to do once it commits.
#[must_use = "revoke the grant once the transaction has committed"]
pub struct Removal {
    pub removed: bool,
    token_state: Option<String>,
}

impl Removal {
    /// Revokes the removed grant on Louve, best effort. Call it after the commit: a rolled-back
    /// deletion keeps both the row and a live grant.
    pub async fn revoke_after_commit(self, state: &AppState) {
        if let Some(plain) = self.token_state {
            adapter::revoke(state, &plain).await;
        }
    }
}

/// deleteConnection joined to the caller's transaction (an account deletion): takes the member
/// lock, deletes the row and keeps its decrypted token set for the revocation after commit.
pub async fn delete_connection_in(
    state: &AppState,
    conn: &mut PgConnection,
    member_id: i64,
) -> AppResult<Removal> {
    let removal: LouveResult<Removal> = async {
        lock_member(&mut *conn, member_id).await?;
        let Some(row) = repo::lock_by_member(&mut conn.as_handle(), member_id).await? else {
            return Ok(Removal {
                removed: false,
                token_state: None,
            });
        };
        let token_state = decrypt_or_none(state, row.token_state.as_deref());
        repo::delete(&mut *conn, row.id).await?;
        Ok(Removal {
            removed: true,
            token_state,
        })
    }
    .await;
    let removal = removal?;
    tracing::info!("Louve Invest connection cleared for member {member_id}");
    Ok(removal)
}

/// Deletes the connection, then revokes its grant on Louve (best effort, after the commit),
/// whatever the status. A row with only a pending attempt holds no token. True when a row went.
pub async fn delete_connection(state: &AppState, member_id: i64) -> AppResult<bool> {
    let mut tx = state.db.begin().await?;
    let removal = delete_connection_in(state, &mut tx, member_id).await?;
    tx.commit().await?;
    let removed = removal.removed;
    removal.revoke_after_commit(state).await;
    Ok(removed)
}
