//! SyncStatusService: read-only status summary of all connections `MemberSyncService` would touch.
//! Each reader runs independently on the pool so a failure in one never hides the rest or leaks
//! secrets/DB errors.

use chrono::{DateTime, SecondsFormat, Utc};

use crate::AppState;
use crate::ledger::sidecar::SidecarBank;

const REAUTH_ON_INVALID_CREDENTIALS: &[&str] =
    &["bourso", "cmes", "cic", "corum", "natixis", "lbp", "bnp"];

pub async fn describe(state: &AppState, member_id: i64) -> String {
    let mut lines = Vec::new();
    push_reader(&mut lines, "revolut", revolut(state, member_id).await);
    push_reader(
        &mut lines,
        "enable-banking",
        banking(state, member_id).await,
    );
    for (source, bank) in [
        ("trade-republic", SidecarBank::TradeRepublic),
        ("bourso", SidecarBank::Bourso),
        ("bourse-direct", SidecarBank::BourseDirect),
        ("amundi", SidecarBank::Amundi),
        ("cmes", SidecarBank::Cmes),
        ("cic", SidecarBank::Cic),
        ("corum", SidecarBank::Corum),
        ("natixis", SidecarBank::Natixis),
    ] {
        push_reader(
            &mut lines,
            source,
            sidecar_broker(state, source, bank, member_id).await,
        );
    }
    push_reader(&mut lines, "lbp", lbp_broker(state, member_id).await);
    for (source, bank) in [
        ("bnp", SidecarBank::Bnp),
        ("fortuneo", SidecarBank::Fortuneo),
        ("amex", SidecarBank::Amex),
    ] {
        push_reader(
            &mut lines,
            source,
            sidecar_broker(state, source, bank, member_id).await,
        );
    }
    push_reader(&mut lines, "ibkr", ibkr(state, member_id).await);
    push_reader(&mut lines, "simplefin", simplefin(state, member_id).await);
    push_reader(&mut lines, "wise", wise(state, member_id).await);
    push_reader(
        &mut lines,
        "crypto-exchanges",
        exchanges(state, member_id).await,
    );
    push_reader(&mut lines, "wallets", wallets(state, member_id).await);
    push_reader(&mut lines, "finary", finary(state, member_id).await);
    push_reader(&mut lines, "degiro", degiro(state, member_id).await);
    lines.join("\n")
}

fn push_reader(lines: &mut Vec<String>, source: &str, result: Result<Vec<String>, sqlx::Error>) {
    match result {
        Ok(produced) if produced.is_empty() => {
            lines.push(line(source, "NOT_CONNECTED", None, false, None));
        }
        Ok(produced) => lines.extend(produced),
        Err(e) => {
            tracing::warn!("SyncStatusService failed reading {source}: {e}");
            lines.push(line(
                source,
                "FAILED",
                None,
                false,
                Some("status unreadable"),
            ));
        }
    }
}

async fn revolut(state: &AppState, member_id: i64) -> Result<Vec<String>, sqlx::Error> {
    let row: Option<(bool, Option<DateTime<Utc>>)> = crate::db::query_as(
        "SELECT remember_credentials, last_synced_at FROM revolut_session \
         WHERE member_id = $1 ORDER BY id LIMIT 1",
    )
    .bind(member_id)
    .fetch_optional(&state.db)
    .await?;
    let Some((remembered, last_synced_at)) = row else {
        return Ok(vec![line("revolut", "NOT_CONNECTED", None, false, None)]);
    };
    let detail = (!remembered).then_some("credentials not remembered");
    Ok(vec![line(
        "revolut",
        "CONNECTED",
        last_synced_at,
        false,
        detail,
    )])
}

type RequisitionRow = (
    Option<String>,
    Option<String>,
    String,
    Option<String>,
    Option<DateTime<Utc>>,
);
type BrokerSessionRow = (bool, String, Option<DateTime<Utc>>, Option<String>);

async fn banking(state: &AppState, member_id: i64) -> Result<Vec<String>, sqlx::Error> {
    let rows: Vec<RequisitionRow> = crate::db::query_as(
        "SELECT institution_name, institution_id, status::text, error_code, last_synced_at \
             FROM requisition WHERE member_id = $1 ORDER BY id",
    )
    .bind(member_id)
    .fetch_all(&state.db)
    .await?;
    if rows.is_empty() {
        return Ok(Vec::new());
    }
    Ok(rows
        .into_iter()
        .map(
            |(inst_name, inst_id, status_raw, error_code, last_synced_at)| {
                let lbl = format!(
                    "enable-banking/{}",
                    label(inst_name.as_deref(), inst_id.as_deref())
                );
                let no_accounts =
                    error_code.as_deref() == Some(crate::sync::enable_banking::error::NO_ACCOUNTS);
                let reauth = status_raw == "EXPIRED" || no_accounts;
                let status = if no_accounts {
                    "NEEDS_REAUTH"
                } else {
                    match status_raw.as_str() {
                        "EXPIRED" => "NEEDS_REAUTH",
                        "FAILED" => "FAILED",
                        "LINKED" => "CONNECTED",
                        _ => "NOT_CONNECTED",
                    }
                };
                line(&lbl, status, last_synced_at, reauth, error_code.as_deref())
            },
        )
        .collect())
}

async fn sidecar_broker(
    state: &AppState,
    source: &str,
    bank: SidecarBank,
    member_id: i64,
) -> Result<Vec<String>, sqlx::Error> {
    let row: Option<BrokerSessionRow> = crate::db::query_as(
        "SELECT is_active, sync_status, last_sync_completed_at, last_sync_error \
         FROM sidecar_session WHERE bank = $1 AND member_id = $2",
    )
    .bind(bank.as_str())
    .bind(member_id)
    .fetch_optional(&state.db)
    .await?;
    let Some((active, sync_status, completed, error)) = row else {
        return Ok(vec![broker(source, false, "IDLE", None, None)]);
    };
    Ok(vec![broker(
        source,
        active,
        &sync_status,
        completed,
        error.as_deref(),
    )])
}

async fn lbp_broker(state: &AppState, member_id: i64) -> Result<Vec<String>, sqlx::Error> {
    let row: Option<BrokerSessionRow> = crate::db::query_as(
        "SELECT is_active, sync_status, last_sync_completed_at, last_sync_error \
         FROM lbp_session WHERE member_id = $1",
    )
    .bind(member_id)
    .fetch_optional(&state.db)
    .await?;
    let Some((active, sync_status, completed, error)) = row else {
        return Ok(vec![broker("lbp", false, "IDLE", None, None)]);
    };
    Ok(vec![broker(
        "lbp",
        active,
        &sync_status,
        completed,
        error.as_deref(),
    )])
}

async fn ibkr(state: &AppState, member_id: i64) -> Result<Vec<String>, sqlx::Error> {
    let row: Option<(String, Option<DateTime<Utc>>)> = crate::db::query_as(
        "SELECT status, last_synced_at FROM ibkr_connection WHERE member_id = $1",
    )
    .bind(member_id)
    .fetch_optional(&state.db)
    .await?;
    Ok(row
        .into_iter()
        .map(|(status_raw, last_synced_at)| {
            let reauth = reauth_text(Some(&status_raw));
            let status = if reauth {
                "NEEDS_REAUTH"
            } else if status_raw == "ERROR" {
                "FAILED"
            } else {
                "CONNECTED"
            };
            line("ibkr", status, last_synced_at, reauth, None)
        })
        .collect())
}

async fn simplefin(state: &AppState, member_id: i64) -> Result<Vec<String>, sqlx::Error> {
    let row: Option<(String, Option<DateTime<Utc>>)> = crate::db::query_as(
        "SELECT status, last_synced_at FROM simplefin_connection WHERE member_id = $1",
    )
    .bind(member_id)
    .fetch_optional(&state.db)
    .await?;
    Ok(row
        .into_iter()
        .map(|(status_raw, last_synced_at)| {
            let status = if status_raw == "ERROR" {
                "FAILED"
            } else {
                "CONNECTED"
            };
            line("simplefin", status, last_synced_at, false, None)
        })
        .collect())
}

async fn wise(state: &AppState, member_id: i64) -> Result<Vec<String>, sqlx::Error> {
    let row: Option<(String, Option<DateTime<Utc>>)> = crate::db::query_as(
        "SELECT status, last_synced_at FROM wise_connection WHERE member_id = $1",
    )
    .bind(member_id)
    .fetch_optional(&state.db)
    .await?;
    Ok(row
        .into_iter()
        .map(|(status_raw, last_synced_at)| {
            let reauth = status_raw == "REAUTH_REQUIRED";
            let status = if reauth {
                "NEEDS_REAUTH"
            } else if status_raw == "ERROR" {
                "FAILED"
            } else {
                "CONNECTED"
            };
            line("wise", status, last_synced_at, reauth, None)
        })
        .collect())
}

async fn exchanges(state: &AppState, member_id: i64) -> Result<Vec<String>, sqlx::Error> {
    let rows: Vec<(String, String, Option<DateTime<Utc>>)> = crate::db::query_as(
        "SELECT exchange_type::text, status, last_synced_at FROM crypto_exchange_session \
         WHERE member_id = $1 ORDER BY id",
    )
    .bind(member_id)
    .fetch_all(&state.db)
    .await?;
    Ok(rows
        .into_iter()
        .map(|(exchange_type, status_raw, last_synced_at)| {
            let reauth = reauth_text(Some(&status_raw));
            let status = if reauth {
                "NEEDS_REAUTH"
            } else if status_raw == "CONNECTED" {
                "CONNECTED"
            } else {
                status_raw.as_str()
            };
            line(
                &format!("crypto-exchanges/{exchange_type}"),
                status,
                last_synced_at,
                reauth,
                None,
            )
        })
        .collect())
}

async fn wallets(state: &AppState, member_id: i64) -> Result<Vec<String>, sqlx::Error> {
    let rows: Vec<(String, Option<DateTime<Utc>>)> = crate::db::query_as(
        "SELECT chain::text, last_synced_at FROM wallet_address WHERE member_id = $1 ORDER BY id",
    )
    .bind(member_id)
    .fetch_all(&state.db)
    .await?;
    Ok(rows
        .into_iter()
        .map(|(chain, last_synced_at)| {
            line(
                &format!("wallets/{chain}"),
                "CONNECTED",
                last_synced_at,
                false,
                None,
            )
        })
        .collect())
}

async fn finary(state: &AppState, member_id: i64) -> Result<Vec<String>, sqlx::Error> {
    let row: Option<(String, Option<DateTime<Utc>>)> = crate::db::query_as(
        "SELECT status, last_synced_at FROM finary_session WHERE member_id = $1 ORDER BY id LIMIT 1",
    )
    .bind(member_id)
    .fetch_optional(&state.db)
    .await?;
    Ok(row
        .into_iter()
        .map(|(status_raw, last_synced_at)| {
            let reauth = reauth_text(Some(&status_raw));
            let status = if reauth {
                "NEEDS_REAUTH"
            } else if status_raw == "CONNECTED" {
                "CONNECTED"
            } else {
                status_raw.as_str()
            };
            line("finary", status, last_synced_at, reauth, None)
        })
        .collect())
}

async fn degiro(state: &AppState, member_id: i64) -> Result<Vec<String>, sqlx::Error> {
    let row: Option<(String, Option<DateTime<Utc>>, Option<String>)> = crate::db::query_as(
        "SELECT status, last_synced_at, last_error FROM degiro_session WHERE member_id = $1",
    )
    .bind(member_id)
    .fetch_optional(&state.db)
    .await?;
    Ok(row
        .into_iter()
        .map(|(status_raw, last_synced_at, last_error)| {
            let reauth = status_raw == "REAUTH_REQUIRED" || reauth_text(last_error.as_deref());
            let status = match status_raw.as_str() {
                "REAUTH_REQUIRED" => "NEEDS_REAUTH",
                "FAILED" => {
                    if reauth {
                        "NEEDS_REAUTH"
                    } else {
                        "FAILED"
                    }
                }
                _ => "CONNECTED",
            };
            let detail = last_error.as_deref().map(|_| {
                if reauth {
                    "Reauthentication required"
                } else {
                    "Sync failed"
                }
            });
            line("degiro", status, last_synced_at, reauth, detail)
        })
        .collect())
}

fn broker(
    source: &str,
    active: bool,
    sync_status: &str,
    completed: Option<DateTime<Utc>>,
    error_name: Option<&str>,
) -> String {
    let reauth = error_name == Some("SESSION_EXPIRED")
        || (REAUTH_ON_INVALID_CREDENTIALS.contains(&source)
            && error_name == Some("INVALID_CREDENTIALS"))
        || (source == "trade-republic" && error_name == Some("INVALID_SESSION_STATE"));
    let status = if reauth {
        "NEEDS_REAUTH"
    } else if !active && error_name.is_none() {
        "NOT_CONNECTED"
    } else if sync_status == "FAILED" {
        "FAILED"
    } else if sync_status == "QUEUED" || sync_status == "RUNNING" {
        sync_status
    } else if !active {
        "NOT_CONNECTED"
    } else {
        "CONNECTED"
    };
    line(source, status, completed, reauth, error_name)
}

fn reauth_text(value: Option<&str>) -> bool {
    let Some(value) = value.filter(|v| !v.trim().is_empty()) else {
        return false;
    };
    let upper = value.to_ascii_uppercase();
    [
        "AUTH",
        "SESSION",
        "CREDENTIAL",
        "MFA",
        "FRAUD",
        "REAUTH",
        "TOTP",
        "VALIDATION",
    ]
    .iter()
    .any(|needle| upper.contains(needle))
}

fn line(
    source: &str,
    status: &str,
    last_sync: Option<DateTime<Utc>>,
    reauth: bool,
    detail: Option<&str>,
) -> String {
    let last_sync_str = last_sync.map_or_else(
        || "none".to_owned(),
        |ts| ts.to_rfc3339_opts(SecondsFormat::AutoSi, true),
    );
    let mut text = format!("{source}: {status} lastSync={last_sync_str} reauth={reauth}");
    let cleaned = one_line(detail);
    if !cleaned.is_empty() {
        text.push_str(" — ");
        text.push_str(&cleaned);
    }
    text
}

fn label(preferred: Option<&str>, fallback: Option<&str>) -> String {
    let cleaned = one_line(preferred);
    if !cleaned.is_empty() {
        return cleaned;
    }
    let cleaned = one_line(fallback);
    if cleaned.is_empty() {
        "unknown".to_owned()
    } else {
        cleaned
    }
}

fn one_line(value: Option<&str>) -> String {
    let Some(value) = value else {
        return String::new();
    };
    let cleaned = value.replace(['\n', '\r'], " ");
    let trimmed = cleaned.trim();
    if trimmed.chars().count() > 80 {
        trimmed.chars().take(80).collect()
    } else {
        trimmed.to_owned()
    }
}
