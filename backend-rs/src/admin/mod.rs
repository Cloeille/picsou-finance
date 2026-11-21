//! Admin settings, member MFA and identity resets, the Enable Banking call log, telemetry and the
//! instance status (track T1). The typed app_setting accessors other modules read live in
//! settings (AI provider and concurrency, integration flags) and telemetry (consent).

pub mod ai;
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

/// In-memory admin state shared by every request.
#[derive(Default)]
pub struct Runtime {
    pub eb_calls: EnableBankingCallLog,
    pub status: status::Cache,
    pub telemetry: telemetry::Sends,
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

/// EnableBankingCallLogger: the last 200 raw Enable Banking calls, which the connector records.
#[derive(Default)]
pub struct EnableBankingCallLog(Mutex<VecDeque<CallEntry>>);

impl EnableBankingCallLog {
    pub fn log(&self, entry: CallEntry) {
        let mut buffer = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if buffer.len() >= MAX_CALL_LOG_ENTRIES {
            buffer.pop_front();
        }
        buffer.push_back(entry);
    }

    /// Newest first.
    pub fn entries(&self) -> Vec<CallEntry> {
        self.0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .iter()
            .rev()
            .cloned()
            .collect()
    }

    pub fn clear(&self) {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).clear();
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
