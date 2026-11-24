//! Embedded MCP server (SSE and Streamable HTTP transports, protocol 2025-11-25) and the curated
//! member-scoped tool catalog (track T8).

pub mod protocol;
pub mod sync_status;
pub mod tools;
pub mod transport;

use std::collections::HashMap;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use chrono::{DateTime, Utc};
use tokio::sync::mpsc;

use crate::routes::Routes;

/// Idle TTL for Streamable HTTP sessions when clients omit `DELETE /mcp`.
const STREAMABLE_IDLE_TTL: chrono::Duration = chrono::Duration::minutes(30);
/// Per-user cap on concurrent Streamable HTTP sessions; oldest idle session is evicted first.
const MAX_STREAMABLE_PER_USER: usize = 64;
/// Per-user cap on concurrent legacy SSE sessions; oldest session is evicted first.
pub const MAX_SSE_PER_USER: usize = 16;
/// Bounded per-session SSE outbound message buffer.
pub const SSE_CHANNEL_CAPACITY: usize = 64;
/// Default wall-clock limit for a single MCP `tools/call` invocation (matches Java's 30 s).
pub const DEFAULT_TOOL_CALL_TIMEOUT: Duration = Duration::from_secs(30);

pub fn register(routes: &mut Routes) {
    routes
        .get("/mcp", transport::get_mcp)
        .post("/mcp", transport::post_mcp)
        .delete("/mcp", transport::delete_mcp)
        .post("/mcp/message", transport::post_message);
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProtocolMode {
    Sse,
    Streamable,
}

impl ProtocolMode {
    pub fn from_config(value: &str) -> Self {
        if value.trim().eq_ignore_ascii_case("STREAMABLE") {
            Self::Streamable
        } else {
            Self::Sse
        }
    }
}

/// Active MCP transport sessions held on AppState.
pub struct Runtime {
    sse: Mutex<HashMap<String, SseSession>>,
    streamable: Mutex<HashMap<String, StreamableSession>>,
    next_sse_id: AtomicU64,
    tool_call_timeout_ms: AtomicU64,
}

impl Default for Runtime {
    fn default() -> Self {
        Self {
            sse: Mutex::new(HashMap::new()),
            streamable: Mutex::new(HashMap::new()),
            next_sse_id: AtomicU64::new(0),
            tool_call_timeout_ms: AtomicU64::new(
                u64::try_from(DEFAULT_TOOL_CALL_TIMEOUT.as_millis()).unwrap_or(u64::MAX),
            ),
        }
    }
}

struct SseSession {
    user_id: i64,
    seq: u64,
    tx: mpsc::Sender<String>,
}

struct StreamableSession {
    user_id: i64,
    initialized: bool,
    last_seen: DateTime<Utc>,
    sse_tx: Option<(u64, mpsc::Sender<String>)>,
}

impl StreamableSession {
    fn is_idle_expired(&self, now: DateTime<Utc>) -> bool {
        let active_sse = self.sse_tx.as_ref().is_some_and(|(_, tx)| !tx.is_closed());
        !active_sse && (now - self.last_seen) > STREAMABLE_IDLE_TTL
    }
}

impl Runtime {
    pub fn tool_call_timeout(&self) -> Duration {
        Duration::from_millis(self.tool_call_timeout_ms.load(Ordering::Relaxed))
    }

    pub fn set_tool_call_timeout(&self, timeout: Duration) {
        self.tool_call_timeout_ms.store(
            u64::try_from(timeout.as_millis()).unwrap_or(u64::MAX),
            Ordering::Relaxed,
        );
    }

    pub(crate) fn insert_sse(&self, session_id: String, user_id: i64, tx: mpsc::Sender<String>) {
        let mut guard = self.sse.lock().unwrap_or_else(|e| e.into_inner());
        guard.retain(|_, s| !s.tx.is_closed());
        let user_count = guard.values().filter(|s| s.user_id == user_id).count();
        if user_count >= MAX_SSE_PER_USER {
            let overflow = user_count + 1 - MAX_SSE_PER_USER;
            let mut candidates: Vec<(String, u64)> = guard
                .iter()
                .filter(|(_, s)| s.user_id == user_id)
                .map(|(k, s)| (k.clone(), s.seq))
                .collect();
            candidates.sort_by_key(|(_, seq)| *seq);
            for (evict_id, _) in candidates.into_iter().take(overflow) {
                guard.remove(&evict_id);
            }
        }
        let seq = self.next_sse_id.fetch_add(1, Ordering::Relaxed);
        guard.insert(session_id, SseSession { user_id, seq, tx });
    }

    pub(crate) fn remove_sse(&self, session_id: &str) {
        self.sse
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(session_id);
    }

    pub(crate) fn sse_sender(
        &self,
        session_id: &str,
        user_id: i64,
    ) -> Option<mpsc::Sender<String>> {
        let guard = self.sse.lock().unwrap_or_else(|e| e.into_inner());
        guard
            .get(session_id)
            .filter(|s| s.user_id == user_id)
            .map(|s| s.tx.clone())
    }

    pub(crate) fn insert_streamable(&self, session_id: String, user_id: i64, now: DateTime<Utc>) {
        let mut guard = self.streamable.lock().unwrap_or_else(|e| e.into_inner());
        guard.retain(|_, s| !s.is_idle_expired(now));
        let user_count = guard.values().filter(|s| s.user_id == user_id).count();
        if user_count >= MAX_STREAMABLE_PER_USER {
            let overflow = user_count + 1 - MAX_STREAMABLE_PER_USER;
            let mut candidates: Vec<(String, DateTime<Utc>)> = guard
                .iter()
                .filter(|(_, s)| s.user_id == user_id)
                .map(|(k, s)| (k.clone(), s.last_seen))
                .collect();
            candidates.sort_by_key(|(_, seen)| *seen);
            for (evict_id, _) in candidates.into_iter().take(overflow) {
                guard.remove(&evict_id);
            }
        }
        guard.insert(
            session_id,
            StreamableSession {
                user_id,
                initialized: false,
                last_seen: now,
                sse_tx: None,
            },
        );
    }

    pub(crate) fn has_streamable(
        &self,
        session_id: &str,
        user_id: i64,
        now: DateTime<Utc>,
    ) -> bool {
        let mut guard = self.streamable.lock().unwrap_or_else(|e| e.into_inner());
        let Some(session) = guard.get_mut(session_id) else {
            return false;
        };
        if session.user_id != user_id {
            return false;
        }
        if session.is_idle_expired(now) {
            guard.remove(session_id);
            return false;
        }
        session.last_seen = now;
        true
    }

    pub(crate) fn mark_streamable_initialized(
        &self,
        session_id: &str,
        user_id: i64,
        now: DateTime<Utc>,
    ) -> bool {
        let mut guard = self.streamable.lock().unwrap_or_else(|e| e.into_inner());
        let Some(session) = guard.get_mut(session_id) else {
            return false;
        };
        if session.user_id != user_id {
            return false;
        }
        if session.is_idle_expired(now) {
            guard.remove(session_id);
            return false;
        }
        session.initialized = true;
        session.last_seen = now;
        true
    }

    pub(crate) fn attach_streamable_sse(
        &self,
        session_id: &str,
        user_id: i64,
        tx: mpsc::Sender<String>,
        now: DateTime<Utc>,
    ) -> Option<u64> {
        let mut guard = self.streamable.lock().unwrap_or_else(|e| e.into_inner());
        let session = guard.get_mut(session_id)?;
        if session.user_id != user_id {
            return None;
        }
        if session.is_idle_expired(now) {
            guard.remove(session_id);
            return None;
        }
        let sse_id = self.next_sse_id.fetch_add(1, Ordering::Relaxed);
        session.last_seen = now;
        session.sse_tx = Some((sse_id, tx));
        Some(sse_id)
    }

    pub(crate) fn detach_streamable_sse(&self, session_id: &str, sse_id: u64) {
        if let Some(session) = self
            .streamable
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get_mut(session_id)
            && session.sse_tx.as_ref().is_some_and(|(id, _)| *id == sse_id)
        {
            session.sse_tx = None;
        }
    }

    pub(crate) fn remove_streamable(&self, session_id: &str, user_id: i64) -> bool {
        let mut guard = self.streamable.lock().unwrap_or_else(|e| e.into_inner());
        if guard.get(session_id).is_none_or(|s| s.user_id != user_id) {
            return false;
        }
        guard.remove(session_id);
        true
    }

    /// Evicts idle Streamable HTTP sessions whose `last_seen` is past `STREAMABLE_IDLE_TTL`.
    pub fn evict_idle_streamable(&self, now: DateTime<Utc>) -> usize {
        let mut guard = self.streamable.lock().unwrap_or_else(|e| e.into_inner());
        let before = guard.len();
        guard.retain(|_, s| !s.is_idle_expired(now));
        before - guard.len()
    }
}

/// Parses Spring Boot duration strings like `60s`, `1s`, `500ms`, `2m`, `1h`, or `PT60S`.
/// Blank or zero disables keep-alive pings.
pub fn parse_keep_alive(raw: &str) -> Option<Duration> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    let duration = if let Some(rest) = trimmed
        .strip_prefix("PT")
        .or_else(|| trimmed.strip_prefix("pt"))
    {
        parse_simple_duration(rest)?
    } else {
        parse_simple_duration(trimmed)?
    };
    (!duration.is_zero()).then_some(duration)
}

fn parse_simple_duration(value: &str) -> Option<Duration> {
    let lower = value.trim().to_ascii_lowercase();
    if let Some(ms) = lower.strip_suffix("ms") {
        ms.parse::<u64>().ok().map(Duration::from_millis)
    } else if let Some(s) = lower.strip_suffix('s') {
        s.parse::<u64>().ok().map(Duration::from_secs)
    } else if let Some(m) = lower.strip_suffix('m') {
        m.parse::<u64>()
            .ok()
            .and_then(|v| v.checked_mul(60))
            .map(Duration::from_secs)
    } else if let Some(h) = lower.strip_suffix('h') {
        h.parse::<u64>()
            .ok()
            .and_then(|v| v.checked_mul(3600))
            .map(Duration::from_secs)
    } else {
        lower.parse::<u64>().ok().map(Duration::from_secs)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_spring_durations() {
        assert_eq!(parse_keep_alive("60s"), Some(Duration::from_secs(60)));
        assert_eq!(parse_keep_alive("1s"), Some(Duration::from_secs(1)));
        assert_eq!(parse_keep_alive("250ms"), Some(Duration::from_millis(250)));
        assert_eq!(parse_keep_alive("2m"), Some(Duration::from_secs(120)));
        assert_eq!(parse_keep_alive("PT30S"), Some(Duration::from_secs(30)));
        assert_eq!(parse_keep_alive("0s"), None);
        assert_eq!(parse_keep_alive(""), None);
        assert_eq!(parse_keep_alive("18446744073709551615h"), None);
        assert_eq!(parse_keep_alive("18446744073709551615m"), None);
    }

    #[test]
    fn streamable_sessions_evict_when_idle_and_detach_only_own_sse() {
        let rt = Runtime::default();
        let t0 = DateTime::parse_from_rfc3339("2026-11-21T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        rt.insert_streamable("s1".into(), 1, t0);
        let (tx1, _rx1) = mpsc::channel(SSE_CHANNEL_CAPACITY);
        let (tx2, _rx2) = mpsc::channel(SSE_CHANNEL_CAPACITY);
        let id1 = rt.attach_streamable_sse("s1", 1, tx1, t0).unwrap();
        let id2 = rt.attach_streamable_sse("s1", 1, tx2, t0).unwrap();
        // Detaching the superseded stream must not clear the newer sender.
        rt.detach_streamable_sse("s1", id1);
        assert_eq!(
            rt.evict_idle_streamable(t0 + chrono::Duration::minutes(31)),
            0
        );
        rt.detach_streamable_sse("s1", id2);
        assert_eq!(
            rt.evict_idle_streamable(t0 + chrono::Duration::minutes(31)),
            1
        );
        assert!(!rt.has_streamable("s1", 1, t0 + chrono::Duration::minutes(31)));
    }

    #[test]
    fn sse_sessions_cap_per_user_and_use_bounded_channels() {
        let rt = Runtime::default();
        let mut rxs = Vec::new();
        for i in 0..=MAX_SSE_PER_USER {
            let (tx, rx) = mpsc::channel(SSE_CHANNEL_CAPACITY);
            rt.insert_sse(format!("s{i}"), 1, tx);
            rxs.push(rx);
        }
        assert!(rt.sse_sender("s0", 1).is_none(), "oldest session evicted");
        let latest = rt
            .sse_sender(&format!("s{MAX_SSE_PER_USER}"), 1)
            .expect("newest session kept");
        for i in 0..SSE_CHANNEL_CAPACITY {
            assert!(latest.try_send(format!("msg-{i}")).is_ok());
        }
        assert!(matches!(
            latest.try_send("overflow".to_owned()),
            Err(mpsc::error::TrySendError::Full(_))
        ));
    }
}
