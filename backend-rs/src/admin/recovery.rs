//! AdminRecoveryRunner: with ADMIN_RECOVERY_ENABLED, every admin gets a one-hour activation link
//! in the log at startup, is deactivated and loses its sessions. Startup only: no API reaches it.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use rand::RngExt;

use crate::AppState;
use crate::identity::common::log_safe;

const TOKEN_TTL_HOURS: i64 = 1;

fn generate_token() -> String {
    let mut bytes = [0u8; 32];
    rand::rng().fill(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

pub async fn run(state: &AppState) -> anyhow::Result<()> {
    if !state.config.admin_recovery_enabled {
        return Ok(());
    }
    let admins: Vec<(i64, String)> =
        sqlx::query_as("SELECT id, username FROM app_user WHERE role = 'ADMIN' ORDER BY id")
            .fetch_all(&state.db)
            .await?;
    if admins.is_empty() {
        tracing::warn!("ADMIN_RECOVERY_ENABLED=true but no ADMIN user exists yet — skipping.");
        return Ok(());
    }
    let base = state.config.public_base_url.trim_end_matches('/').to_owned();
    for (id, username) in admins {
        let token = generate_token();
        sqlx::query(
            "UPDATE app_user SET activation_token = $2, activation_token_expires = $3, is_activated = false, \
             token_version = token_version + 1, updated_at = now() WHERE id = $1",
        )
        .bind(id)
        .bind(&token)
        .bind(state.clock.now() + chrono::Duration::hours(TOKEN_TTL_HOURS))
        .execute(&state.db)
        .await?;
        let separator = "============================================================";
        tracing::warn!("");
        tracing::warn!("{separator}");
        tracing::warn!("ADMIN RECOVERY ACTIVATED");
        tracing::warn!("Username : {}", log_safe(&username));
        tracing::warn!("Valid    : {TOKEN_TTL_HOURS} hour");
        tracing::warn!("URL      : {base}/activate/{token}");
        tracing::warn!("");
        tracing::warn!("After resetting the password, set ADMIN_RECOVERY_ENABLED=false");
        tracing::warn!("and restart Picsou. All previous sessions have been invalidated.");
        tracing::warn!("{separator}");
        tracing::warn!("");
        // Not a request: the audit row has no address and no user agent.
        if let Err(e) = sqlx::query(
            "INSERT INTO setup_audit (event, actor_username, ip, user_agent, details, at) VALUES ($1, $2, NULL, NULL, $3, $4)",
        )
        .bind("admin.recovery.token-generated")
        .bind(&username)
        .bind(format!("ttl={TOKEN_TTL_HOURS}h"))
        .bind(state.clock.now())
        .execute(&state.db)
        .await
        {
            tracing::warn!("setup_audit.write_failed event=admin.recovery.token-generated err={e}");
        }
    }
    Ok(())
}
