//! Validation of personal access keys (psk_ + 32 base62 characters), as AccessKeyService.validate.

use std::collections::HashMap;
use std::sync::Mutex;

use chrono::{DateTime, Utc};
use subtle::ConstantTimeEq;

use crate::AppState;
use crate::auth::persistent::sha256_hex;
use crate::auth::users::{self, AppUser};

pub const PREFIX: &str = "psk_";
const FULL_LENGTH: usize = 36;
const PREFIX_LENGTH: usize = 12;

#[derive(Debug, Clone)]
pub struct ResolvedKey {
    pub owner: AppUser,
    pub scopes: Vec<String>,
    pub key_id: i64,
}

/// Last persisted last_used_at per key, to write it at most once per throttle window.
#[derive(Debug, Default)]
pub struct UsageThrottle(Mutex<HashMap<i64, DateTime<Utc>>>);

#[derive(sqlx::FromRow)]
struct KeyRow {
    id: i64,
    created_by: i64,
    key_hash: String,
    scopes: String,
    expires_at: Option<DateTime<Utc>>,
    revoked_at: Option<DateTime<Utc>>,
}

pub async fn validate(state: &AppState, raw: &str) -> Result<Option<ResolvedKey>, sqlx::Error> {
    if raw.len() != FULL_LENGTH || !raw.starts_with(PREFIX) || !raw.is_char_boundary(PREFIX_LENGTH)
    {
        return Ok(None);
    }
    let row: Option<KeyRow> = sqlx::query_as(
        "SELECT id, created_by, key_hash, scopes, expires_at, revoked_at FROM access_key WHERE key_prefix = $1",
    )
    .bind(&raw[..PREFIX_LENGTH])
    .fetch_optional(&state.db)
    .await?;
    let Some(key) = row else { return Ok(None) };
    let presented = sha256_hex(raw);
    if presented.len() != key.key_hash.len()
        || !bool::from(presented.as_bytes().ct_eq(key.key_hash.as_bytes()))
    {
        return Ok(None);
    }
    let now = state.clock.now();
    if key.revoked_at.is_some() || key.expires_at.is_some_and(|e| e <= now) {
        return Ok(None);
    }
    let Some(owner) = users::find_by_id(&state.db, key.created_by).await? else {
        return Ok(None);
    };
    if !owner.activated {
        return Ok(None);
    }
    record_usage(state, key.id, now).await;
    let scopes = key.scopes.split_whitespace().map(str::to_owned).collect();
    Ok(Some(ResolvedKey {
        owner,
        scopes,
        key_id: key.id,
    }))
}

async fn record_usage(state: &AppState, key_id: i64, now: DateTime<Utc>) {
    {
        let mut touched = state
            .access_key_usage
            .0
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let throttle =
            chrono::Duration::from_std(state.config.access_key_touch_throttle).unwrap_or_default();
        if touched
            .get(&key_id)
            .is_some_and(|last| now - *last < throttle)
        {
            return;
        }
        touched.insert(key_id, now);
    }
    if let Err(e) = sqlx::query("UPDATE access_key SET last_used_at = $1 WHERE id = $2")
        .bind(now)
        .bind(key_id)
        .execute(&state.db)
        .await
    {
        tracing::warn!("Failed to record access-key usage for key {key_id}: {e}");
    }
}
