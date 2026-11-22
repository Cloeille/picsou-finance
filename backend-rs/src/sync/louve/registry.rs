//! LouveClientRegistry: one Louve Invest client registration per redirect URI, made on first use
//! and kept in app_setting under louve.client-id:<SHA-256 hex of the redirect URI> (80
//! characters: setting_key is VARCHAR(100) and a redirect URI can be longer). The value is the
//! client id; a public client has no secret.

use async_trait::async_trait;
use sha2::{Digest, Sha256};
use sqlx::PgPool;

use super::error::LouveResult;
use super::oauth;
use crate::AppState;
use crate::db;
use crate::error::AppResult;

const KEY_PREFIX: &str = "louve.client-id:";

/// The app_setting rows the registry reads and writes (AppSettingRepository). Track T1 owns
/// app_setting; AppSettingTable is Java's behavior on that table.
#[async_trait]
pub trait ClientSettings: Send + Sync {
    /// findByKey(key).map(getValue)
    async fn find(&self, key: &str) -> AppResult<Option<String>>;
    /// insertIfAbsent: INSERT ... ON CONFLICT (setting_key) DO NOTHING
    async fn insert_if_absent(&self, key: &str, value: &str) -> AppResult<()>;
    /// deleteIfValue: one compare-and-delete on key and value; the rows removed.
    async fn delete_if_value(&self, key: &str, value: &str) -> AppResult<u64>;
}

pub struct AppSettingTable {
    db: PgPool,
}

impl AppSettingTable {
    pub fn new(db: PgPool) -> Self {
        Self { db }
    }
}

#[async_trait]
impl ClientSettings for AppSettingTable {
    async fn find(&self, key: &str) -> AppResult<Option<String>> {
        Ok(
            db::query_scalar("SELECT value FROM app_setting WHERE setting_key = $1")
                .bind(key)
                .fetch_optional(&self.db)
                .await?,
        )
    }

    async fn insert_if_absent(&self, key: &str, value: &str) -> AppResult<()> {
        db::query(
            "INSERT INTO app_setting (setting_key, value) VALUES ($1, $2) ON CONFLICT (setting_key) DO NOTHING",
        )
        .bind(key)
        .bind(value)
        .execute(&self.db)
        .await?;
        Ok(())
    }

    async fn delete_if_value(&self, key: &str, value: &str) -> AppResult<u64> {
        let result = db::query("DELETE FROM app_setting WHERE setting_key = $1 AND value = $2")
            .bind(key)
            .bind(value)
            .execute(&self.db)
            .await?;
        Ok(result.rows_affected())
    }
}

pub fn key(redirect_uri: &str) -> String {
    format!(
        "{KEY_PREFIX}{}",
        hex::encode(Sha256::digest(redirect_uri.as_bytes()))
    )
}

/// The stored client id for redirect_uri, registering one when there is none. Of two
/// registrations racing for the same URI (another instance), the first one stored wins.
pub async fn client_id_for(state: &AppState, redirect_uri: &str) -> LouveResult<String> {
    let hooks = &state.sync.louve;
    let _serialized = hooks.registry_lock.lock().await;
    let key = key(redirect_uri);
    if let Some(stored) = hooks.settings.find(&key).await? {
        return Ok(stored);
    }
    let client_id = oauth::register(state, redirect_uri).await?;
    hooks.settings.insert_if_absent(&key, &client_id).await?;
    let stored = hooks
        .settings
        .find(&key)
        .await?
        .unwrap_or_else(|| client_id.clone());
    if stored == client_id {
        tracing::info!("Registered Picsou as a Louve Invest client");
    }
    Ok(stored)
}

/// Drops the registration of redirect_uri if it still holds the refused client id: a newer
/// registration is kept, and a second forget finds 0 rows instead of failing.
pub async fn forget(
    state: &AppState,
    redirect_uri: Option<&str>,
    client_id: Option<&str>,
) -> AppResult<()> {
    let (Some(redirect_uri), Some(client_id)) = (redirect_uri, client_id) else {
        return Ok(());
    };
    if client_id.trim().is_empty() {
        return Ok(());
    }
    let hooks = &state.sync.louve;
    let _serialized = hooks.registry_lock.lock().await;
    if hooks
        .settings
        .delete_if_value(&key(redirect_uri), client_id)
        .await?
        > 0
    {
        tracing::info!("Dropped the Louve Invest client registration Louve no longer recognises");
    }
    Ok(())
}
