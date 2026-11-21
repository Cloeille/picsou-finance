//! SyncTools (6 tools): refresh existing connections (with shared per-member cooldown) and read
//! connection sync status.

use std::collections::HashSet;

use serde_json::{Map, Value};

use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::rate_limit::{Probe, policies};
use crate::ledger::sidecar::{SourceSyncResult, SyncStatus};
use crate::mcp::sync_status;
use crate::mcp::tools::{ToolSpec, schema_empty};
use crate::sync::member_sync;

const SHARED_COOLDOWN_DESCRIPTION: &str = " Cooldown is shared by all MCP trigger tools for this member: one sync every 15 minutes, and four per day.";

const BANK_SOURCES: &[&str] = &[
    "enable-banking",
    "enable-banking-retry",
    "simplefin",
    "wise",
];
const BROKER_SOURCES: &[&str] = &[
    "trade-republic",
    "bourso",
    "bourse-direct",
    "amundi",
    "cmes",
    "corum",
    "natixis",
    "lbp",
    "bnp",
    "fortuneo",
    "amex",
    "ibkr",
    "degiro",
];
const EXCHANGE_SOURCES: &[&str] = &["crypto-exchanges"];
const WALLET_SOURCES: &[&str] = &["wallets"];

pub fn specs() -> Vec<ToolSpec> {
    vec![
        ToolSpec {
            name: "trigger_full_sync",
            description: concat!(
                "Refresh every existing connector for the authenticated member, in the same order as the 08:00 scheduler. DEGIRO is included because this call is user-initiated. Does not connect a new source. BoursoBank, Crédit Mutuel / CIC Épargne Salariale and CORUM (when the member chose to remember the credentials) log in again with their stored credentials when the session has expired; no source completes a new second factor. Returns one status line per source; a failure or a source that needs reauthentication is named, not hidden. ",
                " Cooldown is shared by all MCP trigger tools for this member: one sync every 15 minutes, and four per day."
            ),
            scope: "sync:trigger",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "trigger_bank_sync",
            description: concat!(
                "Refresh the authenticated member's existing Enable Banking connections, including the retry of connections that failed last time, and the SimpleFIN and Wise connections. Does not include Revolut — use trigger_full_sync for every source. Does not connect a new bank. Returns one status line per source; a failure is named, not hidden.",
                " Cooldown is shared by all MCP trigger tools for this member: one sync every 15 minutes, and four per day."
            ),
            scope: "sync:trigger",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "trigger_broker_sync",
            description: concat!(
                "Refresh the authenticated member's existing broker connections: Trade Republic, BoursoBank, Bourse Direct, Amundi, Crédit Mutuel / CIC Épargne Salariale, CORUM, Natixis Interépargne, La Banque Postale, BNP Paribas / Hello bank!, Fortuneo, American Express, IBKR, and DEGIRO when the session is still active. Does not connect a new source. BoursoBank, Crédit Mutuel / CIC Épargne Salariale and CORUM (when its credentials are remembered) log in again with their stored credentials when the session has expired; no source completes a new second factor. Returns one status line per source.",
                " Cooldown is shared by all MCP trigger tools for this member: one sync every 15 minutes, and four per day."
            ),
            scope: "sync:trigger",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "trigger_crypto_exchange_sync",
            description: concat!(
                "Refresh the authenticated member's existing crypto-exchange connections. Does not add an exchange. Returns one status line per source.",
                " Cooldown is shared by all MCP trigger tools for this member: one sync every 15 minutes, and four per day."
            ),
            scope: "sync:trigger",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "trigger_crypto_wallet_sync",
            description: concat!(
                "Refresh the authenticated member's existing on-chain wallets. Does not add a wallet. Returns one status line per source.",
                " Cooldown is shared by all MCP trigger tools for this member: one sync every 15 minutes, and four per day."
            ),
            scope: "sync:trigger",
            input_schema: schema_empty(),
        },
        ToolSpec {
            name: "get_sync_status",
            description: "Read the last sync time, status and reauthentication flag for each existing connection the daily scheduler would touch. Does not start a sync.",
            scope: "sync:read",
            input_schema: schema_empty(),
        },
    ]
}

pub async fn call(
    state: &AppState,
    auth: &Auth,
    name: &str,
    _args: &Map<String, Value>,
) -> AppResult<String> {
    let _ = SHARED_COOLDOWN_DESCRIPTION;
    match name {
        "trigger_full_sync" => Ok(trigger(state, auth.member_id, None).await),
        "trigger_bank_sync" => Ok(trigger(state, auth.member_id, Some(BANK_SOURCES)).await),
        "trigger_broker_sync" => Ok(trigger(state, auth.member_id, Some(BROKER_SOURCES)).await),
        "trigger_crypto_exchange_sync" => {
            Ok(trigger(state, auth.member_id, Some(EXCHANGE_SOURCES)).await)
        }
        "trigger_crypto_wallet_sync" => {
            Ok(trigger(state, auth.member_id, Some(WALLET_SOURCES)).await)
        }
        "get_sync_status" => Ok(sync_status::describe(state, auth.member_id).await),
        other => Err(AppError::BadRequest(format!("Unknown sync tool: {other}"))),
    }
}

async fn trigger(state: &AppState, member_id: i64, sources: Option<&[&str]>) -> String {
    if let Probe::Refused { retry_after } = state
        .limits
        .probe(&policies::MCP_MEMBER_SYNC, &member_id.to_string())
    {
        let nanos = retry_after.as_nanos();
        let minutes = nanos.div_ceil(60_000_000_000).max(1);
        return format!(
            "MCP sync cooldown is shared by all trigger tools for this member. Try again in {minutes} min."
        );
    }
    let filter: Option<HashSet<String>> =
        sources.map(|list| list.iter().map(|s| (*s).to_owned()).collect());
    let results = member_sync::resync_for_user(state, member_id, filter.as_ref()).await;
    format_summary(&results)
}

fn status_str(status: SyncStatus) -> &'static str {
    match status {
        SyncStatus::Synced => "SYNCED",
        SyncStatus::Queued => "QUEUED",
        SyncStatus::SkippedNotConnected => "SKIPPED_NOT_CONNECTED",
        SyncStatus::NeedsReauth => "NEEDS_REAUTH",
        SyncStatus::Failed => "FAILED",
        SyncStatus::Skipped => "SKIPPED",
    }
}

pub(crate) fn format_summary(results: &[SourceSyncResult]) -> String {
    if results.is_empty() {
        return "No sync sources ran.".to_owned();
    }
    let mut lines = Vec::with_capacity(results.len());
    for result in results {
        let mut line = format!("{}: {}", result.source, status_str(result.status));
        if !result.message.trim().is_empty() {
            line.push_str(" — ");
            line.push_str(&result.message);
        }
        lines.push(line);
    }
    lines.join("\n")
}
