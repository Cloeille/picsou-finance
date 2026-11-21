//! Embedded MCP server (SSE and Streamable HTTP transports, protocol 2025-11-25) and the curated
//! member-scoped tool catalog (track T8).

pub mod protocol;
pub mod sync_status;
pub mod tools;
pub mod transport;

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;

use tokio::sync::mpsc;

use crate::routes::Routes;

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
#[derive(Default)]
pub struct Runtime {
    sse: Mutex<HashMap<String, SseSession>>,
    streamable: Mutex<HashMap<String, StreamableSession>>,
}

struct SseSession {
    user_id: i64,
    tx: mpsc::UnboundedSender<String>,
}

struct StreamableSession {
    user_id: i64,
    initialized: bool,
    sse_tx: Option<mpsc::UnboundedSender<String>>,
}

impl Runtime {
    pub(crate) fn insert_sse(
        &self,
        session_id: String,
        user_id: i64,
        tx: mpsc::UnboundedSender<String>,
    ) {
        self.sse
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(session_id, SseSession { user_id, tx });
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
    ) -> Option<mpsc::UnboundedSender<String>> {
        let guard = self.sse.lock().unwrap_or_else(|e| e.into_inner());
        guard
            .get(session_id)
            .filter(|s| s.user_id == user_id)
            .map(|s| s.tx.clone())
    }

    pub(crate) fn insert_streamable(&self, session_id: String, user_id: i64) {
        self.streamable
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(
                session_id,
                StreamableSession {
                    user_id,
                    initialized: false,
                    sse_tx: None,
                },
            );
    }

    pub(crate) fn has_streamable(&self, session_id: &str, user_id: i64) -> bool {
        self.streamable
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(session_id)
            .is_some_and(|s| s.user_id == user_id)
    }

    pub(crate) fn mark_streamable_initialized(&self, session_id: &str, user_id: i64) -> bool {
        let mut guard = self.streamable.lock().unwrap_or_else(|e| e.into_inner());
        let Some(session) = guard.get_mut(session_id) else {
            return false;
        };
        if session.user_id != user_id {
            return false;
        }
        session.initialized = true;
        true
    }

    pub(crate) fn attach_streamable_sse(
        &self,
        session_id: &str,
        user_id: i64,
        tx: mpsc::UnboundedSender<String>,
    ) -> bool {
        let mut guard = self.streamable.lock().unwrap_or_else(|e| e.into_inner());
        let Some(session) = guard.get_mut(session_id) else {
            return false;
        };
        if session.user_id != user_id {
            return false;
        }
        session.sse_tx = Some(tx);
        true
    }

    pub(crate) fn detach_streamable_sse(&self, session_id: &str) {
        if let Some(session) = self
            .streamable
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get_mut(session_id)
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
        m.parse::<u64>().ok().map(|v| Duration::from_secs(v * 60))
    } else if let Some(h) = lower.strip_suffix('h') {
        h.parse::<u64>().ok().map(|v| Duration::from_secs(v * 3600))
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
    }
}
