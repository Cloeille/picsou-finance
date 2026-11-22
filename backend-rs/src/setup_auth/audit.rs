//! SetupAuditService: append-only setup_audit rows, never failing the operation they record.

use crate::AppState;

const MAX_USER_AGENT: usize = 500;

pub struct AuditContext<'a> {
    pub ip: &'a str,
    pub user_agent: Option<&'a str>,
}

pub async fn record(
    state: &AppState,
    event: &str,
    actor: Option<&str>,
    ctx: &AuditContext<'_>,
    details: Option<&str>,
) {
    let user_agent = ctx.user_agent.map(|ua| {
        let units: Vec<u16> = ua.encode_utf16().collect();
        if units.len() > MAX_USER_AGENT {
            String::from_utf16_lossy(&units[..MAX_USER_AGENT])
        } else {
            ua.to_owned()
        }
    });
    let result = crate::db::query(
        "INSERT INTO setup_audit (event, actor_username, ip, user_agent, details, at) VALUES ($1, $2, $3, $4, $5, $6)",
    )
    .bind(event)
    .bind(actor)
    .bind(ctx.ip)
    .bind(user_agent)
    .bind(details)
    .bind(state.clock.now())
    .execute(&state.db)
    .await;
    if let Err(e) = result {
        tracing::warn!("setup_audit.write_failed event={event} err={e}");
    }
}
