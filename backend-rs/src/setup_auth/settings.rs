//! The app_setting key/value rows the setup wizard writes and the security chain reads.

use sqlx::PgExecutor;

use crate::AppState;

pub const SETUP_STATE: &str = "setup.state";
pub const CORS_ALLOWED_ORIGINS: &str = "cors.allowed-origins";
pub const SECURE_COOKIES: &str = "app.secure-cookies";
pub const ENABLEBANKING_APP_ID: &str = "enablebanking.application-id";
pub const ENABLEBANKING_KEY_ID: &str = "enablebanking.key-id";
pub const ENABLEBANKING_REDIRECT_URI: &str = "enablebanking.redirect-uri";
pub const BOURSO_AUTH_URL: &str = "bourso-auth.url";

pub async fn read(state: &AppState, key: &str) -> Result<Option<String>, sqlx::Error> {
    read_with(&state.db, key).await
}

pub async fn read_with<'e>(
    db: impl PgExecutor<'e>,
    key: &str,
) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar("SELECT value FROM app_setting WHERE setting_key = $1")
        .bind(key)
        .fetch_optional(db)
        .await
}

pub async fn upsert<'e>(
    db: impl PgExecutor<'e>,
    key: &str,
    value: &str,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO app_setting (setting_key, value) VALUES ($1, $2) \
         ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()",
    )
    .bind(key)
    .bind(value)
    .execute(db)
    .await?;
    Ok(())
}

/// Boolean.parseBoolean: true only for "true", ignoring case.
pub fn parse_bool(value: &str) -> bool {
    value.eq_ignore_ascii_case("true")
}

/// SecureCookieProvider: the wizard's choice, else SECURE_COOKIES.
pub async fn secure_cookies(state: &AppState) -> bool {
    match read(state, SECURE_COOKIES).await {
        Ok(Some(value)) => parse_bool(&value),
        _ => state.config.secure_cookies,
    }
}
