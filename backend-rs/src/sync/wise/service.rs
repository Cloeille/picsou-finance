//! WiseSyncService: connect, sync, status, disconnect and the scheduler entry point.

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use chrono::{DateTime, NaiveDate, Utc};
use serde::Serialize;
use serde_json::Value;

use super::error::{SyncFailure, WiseError};
use super::store::{self, WiseConnection};
use super::{
    COLOR, EXTERNAL_ID_PREFIX, MAX_NAME_LEN, PROVIDER, SOURCE, STATUS_ERROR,
    STATUS_REAUTH_REQUIRED, WiseJar, client, currency,
};
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::bank_import::{clip, fits_ledger_amount};
use crate::ledger::members;
use crate::ledger::model::AccountType;
use crate::ledger::sidecar::{SourceSyncResult, SyncStatus};
use crate::ledger::sync_values::{java_is_blank, java_trim};
use crate::ledger::valuation::{self, SnapshotContext};

const TOKEN_REFUSED_CODE: &str = "SESSION_EXPIRED";
const MASK: &str = "\u{2022}\u{2022}\u{2022}\u{2022}";

/// WiseConnectionStatusResponse. The token itself is never returned.
#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ConnectionStatus {
    connected: bool,
    connection_id: Option<i64>,
    status: Option<String>,
    last_synced_at: Option<DateTime<Utc>>,
    masked_token: Option<String>,
}

/// Reads the jars once to prove the token works, then stores it and imports them. A refused
/// token stores nothing.
pub(super) async fn connect(
    state: &AppState,
    api_token: &str,
    member_id: i64,
) -> AppResult<Vec<Value>> {
    let member = members::find_by_id(&state.db, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Family member not found".into()))?;
    let token = java_trim(api_token);
    let jars = fetch_jars(state, token).await?;
    let encrypted = state.crypto.encrypt(token)?;
    let now = state.clock.now();
    let mut tx = state.db.begin().await?;
    store::save_token(&mut tx, member.id, &encrypted, now).await?;
    tracing::info!("Wise token stored for member {member_id}");
    let responses = import_jars(state, &mut tx.as_handle(), &jars, member.id, now).await?;
    tx.commit().await?;
    Ok(responses)
}

pub(super) async fn connection_status(
    state: &AppState,
    member_id: i64,
) -> AppResult<ConnectionStatus> {
    let Some(stored) = store::find_by_member(&state.db, member_id).await? else {
        return Ok(ConnectionStatus {
            connected: false,
            connection_id: None,
            status: None,
            last_synced_at: None,
            masked_token: None,
        });
    };
    let (status, masked_token) = match state.crypto.decrypt(&stored.api_token) {
        Ok(token) => (stored.status, mask(&token)),
        Err(error) => {
            tracing::error!(
                "Wise: cannot decrypt stored token for connection {}: {error}",
                stored.id
            );
            (STATUS_ERROR.to_owned(), MASK.to_owned())
        }
    };
    Ok(ConnectionStatus {
        connected: true,
        connection_id: Some(stored.id),
        status: Some(status),
        last_synced_at: stored.last_synced_at,
        masked_token: Some(masked_token),
    })
}

pub(super) async fn delete_connection<'e>(
    db: impl crate::db::DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<bool> {
    let deleted = store::delete_by_member(db, member_id).await?;
    tracing::info!("Wise connection cleared for member {member_id}");
    Ok(deleted)
}

pub(super) async fn sync(state: &AppState, member_id: i64) -> AppResult<Vec<Value>> {
    let connection = store::find_by_member(&state.db, member_id)
        .await?
        .ok_or_else(|| {
            AppError::from(SyncFailure {
                message: "No Wise connection. Connect with an API token first.".into(),
                code: None,
            })
        })?;
    Ok(sync_with_connection(state, &connection, member_id).await?)
}

/// Scheduler and Sync All entry point: reports failures instead of returning them.
pub(super) async fn resync_reporting(state: &AppState, member_id: i64) -> SourceSyncResult {
    let connection = match store::find_by_member(&state.db, member_id).await {
        Ok(Some(connection)) => connection,
        Ok(None) => return result(SyncStatus::SkippedNotConnected, "No connection"),
        Err(error) => {
            tracing::error!(
                "Wise auto-sync hit an unexpected error for member {member_id}: {error}"
            );
            return result(SyncStatus::Failed, "Unexpected sync error");
        }
    };
    match sync_with_connection(state, &connection, member_id).await {
        Ok(_) => result(SyncStatus::Synced, ""),
        Err(failure) => {
            tracing::warn!(
                "Wise scheduled sync failed for member {member_id} (code={})",
                failure.code.unwrap_or("null")
            );
            SourceSyncResult::from_sync_exception(SOURCE, failure.code, Some(&failure.message))
        }
    }
}

fn result(status: SyncStatus, message: &str) -> SourceSyncResult {
    SourceSyncResult {
        source: SOURCE.to_owned(),
        status,
        message: message.to_owned(),
    }
}

/// A failure stores REAUTH_REQUIRED when Wise refused the token, ERROR otherwise, outside the
/// rolled-back import; anything but a SyncException becomes the generic retry message.
async fn sync_with_connection(
    state: &AppState,
    connection: &WiseConnection,
    member_id: i64,
) -> Result<Vec<Value>, SyncFailure> {
    match try_sync(state, connection, member_id).await {
        Ok(responses) => Ok(responses),
        Err(error) => {
            let token_refused = matches!(
                &error,
                WiseError::Sync(SyncFailure { code: Some(code), .. }) if *code == TOKEN_REFUSED_CODE
            );
            tracing::error!(
                "Wise sync failed for connection {} (token refused: {token_refused}): {error}",
                connection.id
            );
            let status = if token_refused {
                STATUS_REAUTH_REQUIRED
            } else {
                STATUS_ERROR
            };
            store::mark(&state.db, connection.id, status, state.clock.now()).await;
            Err(match error {
                WiseError::Sync(failure) => failure,
                WiseError::App(_) => SyncFailure {
                    message: "Wise sync failed. Try again in a moment.".into(),
                    code: None,
                },
            })
        }
    }
}

async fn try_sync(
    state: &AppState,
    connection: &WiseConnection,
    member_id: i64,
) -> Result<Vec<Value>, WiseError> {
    let token = state.crypto.decrypt(&connection.api_token).map_err(|_| {
        WiseError::sync(
            "Could not decrypt the stored Wise token. The encryption key may have changed. Connect again with a new token.",
        )
    })?;
    let jars = fetch_jars(state, &token).await?;
    let mut tx = state.db.begin().await?;
    let member = members::find_by_id(&mut tx, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Family member not found".into()))?;
    let now = state.clock.now();
    let responses = import_jars(state, &mut tx.as_handle(), &jars, member.id, now).await?;
    store::mark_synced(&mut tx, connection.id, now).await?;
    tx.commit().await?;
    Ok(responses)
}

async fn fetch_jars(state: &AppState, token: &str) -> Result<Vec<WiseJar>, WiseError> {
    client::fetch_jars(&state.sync.wise.http, state.config.sync_url("wise"), token).await
}

async fn import_jars(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    jars: &[WiseJar],
    member_id: i64,
    now: DateTime<Utc>,
) -> AppResult<Vec<Value>> {
    let today = state.clock.today();
    let mut responses = Vec::new();
    for jar in jars {
        if !currency::is_iso_currency(&jar.currency) {
            tracing::info!(
                "Wise: skipping jar {} \u{2014} currency is not ISO 4217",
                jar.balance_id
            );
            continue;
        }
        if !fits_ledger_amount(jar.total_worth) {
            tracing::info!(
                "Wise: skipping jar {} \u{2014} balance does not fit the ledger",
                jar.balance_id
            );
            continue;
        }
        if let Some(response) = upsert_jar(state, &mut *conn, jar, member_id, now, today).await? {
            responses.push(response);
        }
    }
    tracing::info!(
        "Wise sync complete for member {member_id}: {} jar(s) updated",
        responses.len()
    );
    Ok(responses)
}

async fn upsert_jar(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    jar: &WiseJar,
    member_id: i64,
    now: DateTime<Utc>,
    today: NaiveDate,
) -> AppResult<Option<Value>> {
    let external_id = format!("{EXTERNAL_ID_PREFIX}{}", jar.balance_id);
    let existing =
        accounts::find_by_external_id_and_member(&mut *conn, &external_id, member_id).await?;
    if existing.is_none()
        && accounts::exists_soft_deleted_by_external_id(&mut *conn, &external_id, member_id).await?
    {
        tracing::info!(
            "Wise: skipping resurrection of soft-deleted jar {external_id} for member {member_id}"
        );
        return Ok(None);
    }
    let mut account = match existing {
        Some(mut account) => {
            account.current_balance = jar.total_worth;
            account.currency = jar.currency.clone();
            account.last_synced_at = Some(now);
            accounts::update(&mut *conn, &account, now).await?
        }
        None => {
            let mut draft = NewAccount::new(member_id, account_name(jar), AccountType::Savings);
            draft.provider = Some(PROVIDER.to_owned());
            draft.currency = jar.currency.clone();
            draft.current_balance = jar.total_worth;
            draft.last_synced_at = Some(now);
            draft.external_account_id = Some(external_id);
            draft.is_manual = false;
            draft.color = COLOR.to_owned();
            accounts::insert(&mut *conn, &draft, now).await?
        }
    };
    // The response shows the figure Wise sent, as the saved entity does in Java, not the
    // numeric(20, 8) round trip.
    account.current_balance = jar.total_worth;
    let ctx = SnapshotContext {
        prices: &*state.ledger.prices,
        today,
        now,
    };
    valuation::upsert_snapshot_from_native(&mut *conn, &ctx, &account, jar.total_worth, today)
        .await?;
    let response = state
        .sync
        .wise
        .accounts
        .to_response(state, &mut *conn, &account)
        .await?;
    Ok(Some(response))
}

/// The jar's own name: account cards already show the provider beneath it.
pub(super) fn account_name(jar: &WiseJar) -> String {
    let name = match jar.name.as_deref() {
        Some(name) if !java_is_blank(name) => java_trim(name).to_owned(),
        _ => format!("Jar {}", jar.currency),
    };
    let name = if jar.business {
        format!("{name} (Business)")
    } else {
        name
    };
    clip(&name, MAX_NAME_LEN)
}

pub(super) fn mask(token: &str) -> String {
    let units: Vec<u16> = token.encode_utf16().collect();
    if units.len() <= 8 {
        return MASK.to_owned();
    }
    format!(
        "{MASK}{}",
        String::from_utf16_lossy(&units[units.len() - 4..])
    )
}
