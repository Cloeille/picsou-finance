//! Admin settings, member MFA and identity resets, the Enable Banking call log, telemetry and the
//! instance status (track T1). The typed app_setting accessors other modules read live in
//! settings (AI provider and concurrency, integration flags) and telemetry (consent).

pub mod ai;
pub mod archive;
pub mod members;
pub mod recovery;
pub mod settings;
pub mod status;
pub mod telemetry;

use std::collections::VecDeque;
use std::sync::Mutex;

use axum::extract::State;
use axum::http::StatusCode;
use chrono::{DateTime, Utc};
use serde::Serialize;

use crate::AppState;
use crate::routes::Routes;

const MAX_CALL_LOG_ENTRIES: usize = 200;
const MAX_CALL_LOG_BODY_CHARS: usize = 256 * 1024;
/// A handful of capped catalog answers, or many account calls: a few megabytes of memory.
const MAX_CALL_LOG_TOTAL_BODY_CHARS: usize = 8 * 1024 * 1024;

/// In-memory admin state shared by every request.
pub struct Runtime {
    pub eb_calls: EnableBankingCallLog,
    pub status: status::Cache,
    pub telemetry: telemetry::Sends,
    pub ai_http: reqwest::Client,
}

impl Default for Runtime {
    fn default() -> Self {
        Self {
            eb_calls: EnableBankingCallLog::default(),
            status: status::Cache::default(),
            telemetry: telemetry::Sends::default(),
            ai_http: crate::ai::provider::build_http_client(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CallEntry {
    pub timestamp: DateTime<Utc>,
    pub method: String,
    pub url: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_body: Option<String>,
    pub response_status: u16,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_body: Option<String>,
}

/// EnableBankingCallLogger: the raw Enable Banking calls the connector records, in memory only.
/// Bounded three ways so it stays a few megabytes on a small instance: the 200 newest calls,
/// 256 K characters per stored body, 8 M characters across all bodies, the oldest going first.
#[derive(Default)]
pub struct EnableBankingCallLog(Mutex<CallBuffer>);

#[derive(Default)]
struct CallBuffer {
    entries: VecDeque<CallEntry>,
    body_chars: usize,
}

fn body_chars(entry: &CallEntry) -> usize {
    entry
        .response_body
        .as_deref()
        .map_or(0, |body| body.chars().count())
}

/// A body past the cap is cut on a character and says how long it was.
fn cap_body(body: &str) -> String {
    let total = body.chars().count();
    if total <= MAX_CALL_LOG_BODY_CHARS {
        return body.to_owned();
    }
    let kept: String = body.chars().take(MAX_CALL_LOG_BODY_CHARS).collect();
    format!("{kept}…[truncated, {total} characters in total]")
}

impl EnableBankingCallLog {
    /// One response the connector received, whatever its status. Request bodies are not kept:
    /// the responses are what diagnose a sync.
    pub fn record(
        &self,
        timestamp: DateTime<Utc>,
        method: &str,
        url: &str,
        response_status: u16,
        response_body: &str,
    ) {
        self.log(CallEntry {
            timestamp,
            method: method.to_owned(),
            url: url.to_owned(),
            request_body: Some(String::new()),
            response_status,
            response_body: Some(cap_body(response_body)),
        });
    }

    pub fn log(&self, entry: CallEntry) {
        let mut buffer = self.0.lock().unwrap_or_else(|e| e.into_inner());
        buffer.body_chars += body_chars(&entry);
        buffer.entries.push_back(entry);
        while buffer.entries.len() > MAX_CALL_LOG_ENTRIES
            || (buffer.body_chars > MAX_CALL_LOG_TOTAL_BODY_CHARS && buffer.entries.len() > 1)
        {
            if let Some(oldest) = buffer.entries.pop_front() {
                buffer.body_chars -= body_chars(&oldest);
            }
        }
    }

    /// Newest first.
    pub fn entries(&self) -> Vec<CallEntry> {
        self.0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .entries
            .iter()
            .rev()
            .cloned()
            .collect()
    }

    pub fn clear(&self) {
        *self.0.lock().unwrap_or_else(|e| e.into_inner()) = CallBuffer::default();
    }
}

async fn eb_call_log(State(state): State<AppState>) -> axum::Json<Vec<CallEntry>> {
    axum::Json(state.admin.eb_calls.entries())
}

async fn clear_eb_call_log(State(state): State<AppState>) -> StatusCode {
    state.admin.eb_calls.clear();
    StatusCode::NO_CONTENT
}

pub fn register(routes: &mut Routes) {
    archive::register(routes);
    routes
        .get("/api/admin/settings", settings::get_settings)
        .put("/api/admin/settings/security", settings::update_security)
        .put("/api/admin/settings/telemetry", settings::update_telemetry)
        .post(
            "/api/admin/settings/cors/reload-from-env",
            settings::reload_cors_from_env,
        )
        .put(
            "/api/admin/settings/enablebanking",
            settings::update_enable_banking,
        )
        .post(
            "/api/admin/settings/enablebanking/keypair",
            settings::generate_keypair,
        )
        .post(
            "/api/admin/settings/enablebanking/keypair/import",
            settings::import_private_key,
        )
        .patch(
            "/api/admin/settings/integrations/{key}",
            settings::toggle_integration,
        )
        .put("/api/admin/settings/ai", settings::update_ai)
        .post("/api/admin/settings/ai/test", ai::test)
        .post("/api/admin/settings/ai/models", ai::models)
        .get("/api/admin/ai-calls", ai::calls)
        .get("/api/admin/enablebanking/call-log", eb_call_log)
        .delete("/api/admin/enablebanking/call-log", clear_eb_call_log)
        .get("/api/admin/instance-status", status::instance_status)
        .delete(
            "/api/admin/members/{memberId}/mfa",
            members::force_disable_mfa,
        )
        .delete(
            "/api/admin/members/{memberId}/identities",
            members::unlink_identities,
        )
        .get("/api/telemetry/config", telemetry::config)
        .post("/api/telemetry/tunnel", telemetry::tunnel);
}

/// Startup work of the admin module: the break-glass admin recovery.
pub async fn on_startup(state: &AppState) -> anyhow::Result<()> {
    recovery::run(state).await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(second: i64) -> DateTime<Utc> {
        DateTime::from_timestamp(second, 0).expect("a timestamp")
    }

    #[test]
    fn a_long_body_is_cut_on_a_character_and_says_how_long_it_was() {
        let log = EnableBankingCallLog::default();
        let body = "é".repeat(MAX_CALL_LOG_BODY_CHARS + 3);
        log.record(at(0), "GET", "https://eb.test/aspsps", 200, &body);

        let stored = log.entries().remove(0).response_body.expect("a body");
        let suffix = format!(
            "…[truncated, {} characters in total]",
            MAX_CALL_LOG_BODY_CHARS + 3
        );
        assert!(
            stored.ends_with(&suffix),
            "{}",
            &stored[stored.len() - 60..]
        );
        assert_eq!(
            stored.chars().count(),
            MAX_CALL_LOG_BODY_CHARS + suffix.chars().count()
        );
    }

    #[test]
    fn a_short_body_and_an_empty_request_body_are_kept_as_they_are() {
        let log = EnableBankingCallLog::default();
        log.record(
            at(0),
            "POST",
            "https://eb.test/sessions",
            422,
            "{\"error\":\"x\"}",
        );

        let entry = log.entries().remove(0);
        assert_eq!(entry.response_body.as_deref(), Some("{\"error\":\"x\"}"));
        assert_eq!(entry.request_body.as_deref(), Some(""));
        assert_eq!(
            (entry.method.as_str(), entry.response_status),
            ("POST", 422)
        );
    }

    #[test]
    fn only_the_newest_calls_are_kept() {
        let log = EnableBankingCallLog::default();
        for i in 0..(MAX_CALL_LOG_ENTRIES as i64 + 5) {
            log.record(at(i), "GET", "https://eb.test/a", 200, "{}");
        }

        let entries = log.entries();
        assert_eq!(entries.len(), MAX_CALL_LOG_ENTRIES);
        assert_eq!(entries[0].timestamp, at(MAX_CALL_LOG_ENTRIES as i64 + 4));
        assert_eq!(entries[MAX_CALL_LOG_ENTRIES - 1].timestamp, at(5));
    }

    #[test]
    fn the_oldest_calls_go_when_the_bodies_together_are_too_large() {
        let log = EnableBankingCallLog::default();
        let catalog = "x".repeat(MAX_CALL_LOG_BODY_CHARS);
        let fitting = MAX_CALL_LOG_TOTAL_BODY_CHARS / MAX_CALL_LOG_BODY_CHARS;
        for i in 0..(fitting as i64 + 3) {
            log.record(at(i), "GET", "https://eb.test/aspsps", 200, &catalog);
        }

        let entries = log.entries();
        assert_eq!(entries.len(), fitting);
        assert_eq!(entries[0].timestamp, at(fitting as i64 + 2), "newest kept");

        log.clear();
        log.record(at(0), "GET", "https://eb.test/a", 200, "{}");
        assert_eq!(log.entries().len(), 1, "the count restarts after a clear");
    }
}
