//! PersistentSessionService: rotating "Remember Me" tokens with theft detection and a grace
//! window for concurrent tabs. The cookie is "<series uuid>:<token>"; only the token's SHA-256
//! is stored.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use chrono::{DateTime, Utc};
use rand::RngExt;
use sha2::{Digest, Sha256};
use sqlx::{Connection, FromRow, PgPool};
use uuid::Uuid;

use crate::AppState;
use crate::auth::users::AppUser;

const RAW_TOKEN_BYTES: usize = 64;

#[derive(Debug, Clone, FromRow)]
pub struct PersistentSession {
    pub id: i64,
    pub series_id: Uuid,
    pub token_hash: String,
    pub previous_token_hash: Option<String>,
    pub previous_token_at: Option<DateTime<Utc>>,
    pub user_id: i64,
    pub user_agent: Option<String>,
    pub ip_prefix: Option<String>,
    pub trusted_for_2fa: bool,
    pub created_at: DateTime<Utc>,
    pub last_used_at: DateTime<Utc>,
    pub expires_at: DateTime<Utc>,
    pub sso_authenticated_at: Option<DateTime<Utc>>,
    pub revoked_at: Option<DateTime<Utc>>,
}

impl PersistentSession {
    pub fn is_active(&self, now: DateTime<Utc>) -> bool {
        self.revoked_at.is_none() && self.expires_at > now
    }
}

const COLUMNS: &str = "id, series_id, token_hash, previous_token_hash, previous_token_at, user_id, user_agent, \
    ip_prefix, trusted_for_2fa, created_at, last_used_at, expires_at, sso_authenticated_at, revoked_at";

pub fn sha256_hex(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}

fn generate_token() -> String {
    let mut buf = [0u8; RAW_TOKEN_BYTES];
    rand::rng().fill(&mut buf[..]);
    URL_SAFE_NO_PAD.encode(buf)
}

fn constant_time_eq(a: &str, b: &str) -> bool {
    use subtle::ConstantTimeEq;
    a.len() == b.len() && bool::from(a.as_bytes().ct_eq(b.as_bytes()))
}

/// (series, token) from a cookie value; None when malformed.
pub fn parse_cookie(value: &str) -> Option<(Uuid, &str)> {
    let idx = value.find(':')?;
    if idx == 0 || idx == value.len() - 1 {
        return None;
    }
    Some((Uuid::parse_str(&value[..idx]).ok()?, &value[idx + 1..]))
}

pub fn series_from_cookie(value: &str) -> Option<Uuid> {
    parse_cookie(value).map(|(series, _)| series)
}

/// The first three IPv4 octets ("a.b.c."), or the first four non-empty IPv6 hextets.
pub fn ip_prefix(remote: &str) -> Option<String> {
    if remote.trim().is_empty() {
        return None;
    }
    if remote.contains('.') {
        let dots: Vec<usize> = remote.match_indices('.').map(|(i, _)| i).take(3).collect();
        return Some(match dots.get(2) {
            Some(third) => remote[..=*third].to_owned(),
            None => remote.to_owned(),
        });
    }
    let mut out = String::new();
    let mut kept = 0;
    for part in remote.split(':') {
        if !out.is_empty() {
            out.push(':');
        }
        out.push_str(part);
        if !part.is_empty() {
            kept += 1;
        }
        if kept >= 4 {
            break;
        }
    }
    Some(out)
}

fn truncate(value: Option<&str>, max: usize) -> Option<String> {
    value.map(|v| {
        let units: Vec<u16> = v.encode_utf16().collect();
        if units.len() <= max {
            v.to_owned()
        } else {
            String::from_utf16_lossy(&units[..max])
        }
    })
}

pub struct Issued {
    pub cookie_value: String,
    pub session: PersistentSession,
}

pub async fn issue(
    state: &AppState,
    user: &AppUser,
    trusted_for_2fa: bool,
    user_agent: Option<&str>,
    remote_addr: &str,
    sso_authenticated_at: Option<DateTime<Utc>>,
) -> Result<Issued, sqlx::Error> {
    let series = Uuid::new_v4();
    let token = generate_token();
    let now = state.clock.now();
    let expires = now
        + chrono::Duration::from_std(state.config.persistent_session_expiry).unwrap_or_default();
    let session: PersistentSession = sqlx::query_as(&format!(
        "INSERT INTO persistent_session (series_id, token_hash, user_id, user_agent, ip_prefix, trusted_for_2fa, \
         sso_authenticated_at, created_at, last_used_at, expires_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $8, $9) \
         RETURNING {COLUMNS}"
    ))
    .bind(series)
    .bind(sha256_hex(&token))
    .bind(user.id)
    .bind(truncate(user_agent, 255))
    .bind(ip_prefix(remote_addr))
    .bind(trusted_for_2fa)
    .bind(sso_authenticated_at)
    .bind(now)
    .bind(expires)
    .fetch_one(&state.db)
    .await?;
    Ok(Issued {
        cookie_value: format!("{series}:{token}"),
        session,
    })
}

/// Validates the cookie and rotates its token under a row lock. A token matching neither the
/// current hash nor, within the grace window, the previous one revokes the whole series.
pub async fn validate_and_rotate(
    state: &AppState,
    cookie: &str,
) -> Result<Option<Issued>, sqlx::Error> {
    let Some((series, token)) = parse_cookie(cookie) else {
        return Ok(None);
    };
    let mut conn = state.db.acquire().await?;
    let mut tx = conn.begin().await?;
    let found: Option<PersistentSession> = sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM persistent_session WHERE series_id = $1 FOR UPDATE"
    ))
    .bind(series)
    .fetch_optional(&mut *tx)
    .await?;
    let Some(mut session) = found else {
        return Ok(None);
    };
    let now = state.clock.now();
    if !session.is_active(now) {
        return Ok(None);
    }
    let presented = sha256_hex(token);
    let grace = chrono::Duration::from_std(state.config.persistent_session_rotation_grace)
        .unwrap_or_default();
    let matches_current = constant_time_eq(&presented, &session.token_hash);
    let matches_previous = match (&session.previous_token_hash, session.previous_token_at) {
        (Some(previous), Some(at)) => now <= at + grace && constant_time_eq(&presented, previous),
        _ => false,
    };
    if !matches_current && !matches_previous {
        tracing::warn!(
            "Persistent token theft suspected: series={} user={} — wiping series",
            session.series_id,
            session.user_id
        );
        sqlx::query("UPDATE persistent_session SET revoked_at = $1 WHERE id = $2")
            .bind(now)
            .bind(session.id)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        return Ok(None);
    }
    let new_token = generate_token();
    if matches_current {
        // A genuine rotation arms the grace window with the token rotated away from; a grace
        // acceptance leaves it anchored so every concurrent tab is accepted.
        session.previous_token_hash = Some(session.token_hash.clone());
        session.previous_token_at = Some(now);
    }
    session.token_hash = sha256_hex(&new_token);
    session.last_used_at = now;
    sqlx::query(
        "UPDATE persistent_session SET token_hash = $1, previous_token_hash = $2, previous_token_at = $3, \
         last_used_at = $4 WHERE id = $5",
    )
    .bind(&session.token_hash)
    .bind(&session.previous_token_hash)
    .bind(session.previous_token_at)
    .bind(now)
    .bind(session.id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(Some(Issued {
        cookie_value: format!("{}:{new_token}", session.series_id),
        session,
    }))
}

pub async fn find_by_series(
    db: &PgPool,
    series: Uuid,
) -> Result<Option<PersistentSession>, sqlx::Error> {
    sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM persistent_session WHERE series_id = $1"
    ))
    .bind(series)
    .fetch_optional(db)
    .await
}

pub async fn list_active_for_user(
    db: &PgPool,
    user_id: i64,
) -> Result<Vec<PersistentSession>, sqlx::Error> {
    sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM persistent_session WHERE user_id = $1 AND revoked_at IS NULL ORDER BY last_used_at DESC"
    ))
    .bind(user_id)
    .fetch_all(db)
    .await
}

pub async fn revoke_all_for_user(state: &AppState, user_id: i64) -> Result<u64, sqlx::Error> {
    Ok(sqlx::query(
        "UPDATE persistent_session SET revoked_at = $1 WHERE user_id = $2 AND revoked_at IS NULL",
    )
    .bind(state.clock.now())
    .bind(user_id)
    .execute(&state.db)
    .await?
    .rows_affected())
}

pub async fn revoke_all_for_user_except(
    state: &AppState,
    user_id: i64,
    except_id: i64,
) -> Result<u64, sqlx::Error> {
    Ok(sqlx::query(
        "UPDATE persistent_session SET revoked_at = $1 WHERE user_id = $2 AND id <> $3 AND revoked_at IS NULL",
    )
    .bind(state.clock.now())
    .bind(user_id)
    .bind(except_id)
    .execute(&state.db)
    .await?
    .rows_affected())
}

/// Revokes the owner's session; false when it does not exist or belongs to someone else.
pub async fn revoke(state: &AppState, session_id: i64, owner_id: i64) -> Result<bool, sqlx::Error> {
    let found: Option<(i64, Option<DateTime<Utc>>)> =
        sqlx::query_as("SELECT user_id, revoked_at FROM persistent_session WHERE id = $1")
            .bind(session_id)
            .fetch_optional(&state.db)
            .await?;
    match found {
        Some((user_id, _)) if user_id != owner_id => Ok(false),
        Some((_, Some(_))) => Ok(true),
        Some((_, None)) => {
            sqlx::query("UPDATE persistent_session SET revoked_at = $1 WHERE id = $2")
                .bind(state.clock.now())
                .bind(session_id)
                .execute(&state.db)
                .await?;
            Ok(true)
        }
        None => Ok(false),
    }
}

pub async fn revoke_by_series(state: &AppState, series: Uuid) -> Result<(), sqlx::Error> {
    sqlx::query(
        "UPDATE persistent_session SET revoked_at = $1 WHERE series_id = $2 AND revoked_at IS NULL",
    )
    .bind(state.clock.now())
    .bind(series)
    .execute(&state.db)
    .await?;
    Ok(())
}

pub async fn is_series_active(state: &AppState, series: Uuid) -> Result<bool, sqlx::Error> {
    let now = state.clock.now();
    Ok(find_by_series(&state.db, series)
        .await?
        .is_some_and(|s| s.is_active(now)))
}

/// The cookie's owner, without checking the token: only for "is this left-over cookie the
/// signing-in user's?" decisions, never to grant access.
pub async fn owner_user_id(state: &AppState, cookie: &str) -> Result<Option<i64>, sqlx::Error> {
    let Some(series) = series_from_cookie(cookie) else {
        return Ok(None);
    };
    Ok(find_by_series(&state.db, series).await?.map(|s| s.user_id))
}

pub async fn is_trusted_device_for(
    state: &AppState,
    user_id: i64,
    cookie: &str,
) -> Result<bool, sqlx::Error> {
    let Some(series) = series_from_cookie(cookie) else {
        return Ok(false);
    };
    let now = state.clock.now();
    Ok(find_by_series(&state.db, series)
        .await?
        .is_some_and(|s| s.user_id == user_id && s.trusted_for_2fa && s.is_active(now)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ip_prefix_like_java() {
        assert_eq!(ip_prefix("192.168.1.42").as_deref(), Some("192.168.1."));
        assert_eq!(ip_prefix("0:0:0:0:0:0:0:1").as_deref(), Some("0:0:0:0"));
        assert_eq!(ip_prefix("2001:db8::1").as_deref(), Some("2001:db8::1"));
        assert_eq!(ip_prefix(""), None);
    }

    #[test]
    fn cookie_parsing() {
        assert!(parse_cookie("not-a-uuid:abc").is_none());
        assert!(parse_cookie(":abc").is_none());
        let series = Uuid::new_v4();
        assert_eq!(
            parse_cookie(&format!("{series}:tok")),
            Some((series, "tok"))
        );
        assert!(parse_cookie(&format!("{series}:")).is_none());
    }
}
