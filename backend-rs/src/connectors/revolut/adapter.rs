//! RevolutAdapter: the revolut-auth sidecar's `POST /sync`, which reuses a live per-member browser
//! profile or logs in with a mobile push approval before harvesting, and the best-effort poll of
//! its `GET /progress/{member}` while that call blocks. This sidecar answers bare statuses, not the
//! `detail` code contract, so it is called through SidecarHttp directly.

use std::str::FromStr;
use std::sync::LazyLock;
use std::time::Duration;

use chrono::NaiveDate;
use http::{Method, StatusCode};
use regex::Regex;
use rust_decimal::Decimal;
use serde_json::{Value, json};

use super::progress::PhaseUpdate;
use crate::AppState;
use crate::connectors::sidecar::{CallError, JobError, SidecarHttp, SyncError};
use crate::error::AppError;
use crate::ledger::model::{AccountType, CategoryKind};
use crate::ledger::sidecar::SidecarTransaction;

const SERVICE: &str = "revolut-auth";
/// Comfortably above the sidecar's own five-minute approval wait.
const SYNC_TIMEOUT: Duration = Duration::from_secs(480);
const POLL_TIMEOUT: Duration = Duration::from_secs(5);
const POLL_DELAY: Duration = Duration::from_secs(1);
const POLL_PERIOD: Duration = Duration::from_secs(2);
pub const NO_RESPONSE: &str = "No response from the Revolut service. Please try again later.";
pub const GENERIC_FAILURE: &str = "Failed to sync Revolut accounts. Please try again later.";

static ISO_DATE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^\d{4}-\d{2}-\d{2}$").expect("regex"));

#[derive(Debug, Clone, PartialEq)]
pub struct RevolutTxn {
    pub external_id: Option<String>,
    pub date: NaiveDate,
    pub description: Option<String>,
    pub amount: Decimal,
    pub counterparty: Option<String>,
    pub kind: Option<CategoryKind>,
    /// The leg's own currency; None means the pocket's.
    pub currency: Option<String>,
}

impl RevolutTxn {
    pub fn to_sidecar_transaction(&self, kind: Option<CategoryKind>) -> SidecarTransaction {
        SidecarTransaction {
            external_id: self.external_id.clone(),
            date: Some(self.date),
            description: self.description.clone(),
            amount: Some(self.amount),
            counterparty: self.counterparty.clone(),
            kind: kind.map(|k| k.as_str().to_owned()),
            currency: self.currency.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct RevolutAccountData {
    pub external_id: Option<String>,
    pub name: Option<String>,
    pub account_type: AccountType,
    pub iban: Option<String>,
    pub balance: Decimal,
    pub currency: String,
    /// Set on pocket sub-accounts: the external id of their parent wallet.
    pub parent_external_id: Option<String>,
    pub txns: Vec<RevolutTxn>,
}

impl RevolutAccountData {
    /// The account's currency, EUR when blank.
    pub fn currency_code(&self) -> String {
        let trimmed = self.currency.trim();
        if trimmed.is_empty() {
            "EUR".to_owned()
        } else {
            trimmed.to_uppercase()
        }
    }
}

/// Stops the progress poll when the sync call ends, whichever way it ends.
struct Poller(tokio::task::JoinHandle<()>);

impl Drop for Poller {
    fn drop(&mut self) {
        self.0.abort();
    }
}

fn sync_error(message: &str) -> JobError {
    JobError::Sync(SyncError::uncoded(message))
}

fn coded(code: &str) -> JobError {
    JobError::Sync(SyncError::with_code(code, code))
}

fn unexpected(message: impl Into<String>) -> JobError {
    JobError::App(AppError::Internal(message.into()))
}

/// Harvests the member's wallets, pockets and vaults. With `allow_login` false the sidecar may
/// reuse a live profile but must not start a login waiting for mobile approval.
pub async fn sync(
    state: &AppState,
    phone_number: &str,
    passcode: &str,
    member_id: i64,
    allow_login: bool,
) -> Result<Vec<RevolutAccountData>, JobError> {
    tracing::info!("Requesting Revolut sync via revolut-auth sidecar for member {member_id}");
    let http = SidecarHttp::new(state, SERVICE, "Revolut");
    let _poller = Poller(tokio::spawn(poll_progress(
        state.clone(),
        http.clone(),
        member_id,
    )));
    let body = json!({
        "phoneNumber": phone_number,
        "passcode": passcode,
        "memberId": member_id.to_string(),
        "allowLogin": allow_login,
    });
    let response = match http
        .send(Method::POST, "/sync", Some(&body), SYNC_TIMEOUT)
        .await
    {
        Ok(response) => response,
        Err(CallError::Unauthorized(error)) => return Err(JobError::Sync(error)),
        Err(CallError::Timeout) => return Err(sync_error("REVOLUT_TIMEOUT")),
        Err(CallError::Unreachable) => return Err(sync_error(NO_RESPONSE)),
        Err(CallError::Transport(detail)) => {
            return Err(unexpected(format!("revolut-auth sidecar /sync: {detail}")));
        }
    };
    if !response.is_success() {
        return Err(map_status(response.status, member_id));
    }
    if response.body.iter().all(u8::is_ascii_whitespace) {
        return Err(sync_error(NO_RESPONSE));
    }
    let node: Value = response
        .json()
        .map_err(|_| unexpected("revolut-auth sidecar answered malformed JSON"))?;
    let accounts = parse_accounts(&node)?;
    tracing::info!(
        "Revolut sync complete: {} account(s) for member {member_id}",
        accounts.len()
    );
    Ok(accounts)
}

fn map_status(status: StatusCode, member_id: i64) -> JobError {
    match status.as_u16() {
        401 => {
            tracing::warn!(
                "revolut-auth sidecar reports session expired (401) for member {member_id}"
            );
            sync_error("SESSION_EXPIRED")
        }
        408 => {
            tracing::warn!(
                "revolut-auth sidecar reports approval timeout (408) for member {member_id}"
            );
            sync_error("APPROVAL_TIMEOUT")
        }
        409 => {
            tracing::warn!(
                "revolut-auth sidecar reports a sync already in progress (409) for member {member_id}"
            );
            sync_error("SYNC_IN_PROGRESS")
        }
        403 => {
            tracing::warn!(
                "revolut-auth sidecar reports Revolut asked for a human verification (403) for member {member_id}"
            );
            coded("HUMAN_VERIFICATION_REQUIRED")
        }
        424 => {
            tracing::warn!(
                "revolut-auth sidecar reports an unrecognised login page (424) for member {member_id}"
            );
            coded("UPSTREAM_FORMAT_CHANGED")
        }
        503 => {
            tracing::warn!(
                "revolut-auth sidecar could not launch the browser for member {member_id}"
            );
            sync_error("BROWSER_LAUNCH_FAILED")
        }
        other => {
            tracing::error!("revolut-auth sidecar /sync failed ({other})");
            sync_error(GENERIC_FAILURE)
        }
    }
}

/// Polls the sidecar's phase every two seconds and forwards it to the member's progress; a failed
/// poll is skipped, and a member with no job registered (the scheduler's resync) ignores it.
async fn poll_progress(state: AppState, http: SidecarHttp, member_id: i64) {
    let mut ticks = tokio::time::interval_at(tokio::time::Instant::now() + POLL_DELAY, POLL_PERIOD);
    ticks.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let path = format!("/progress/{member_id}");
    loop {
        ticks.tick().await;
        let Ok(response) = http
            .send::<Value>(Method::GET, &path, None, POLL_TIMEOUT)
            .await
        else {
            continue;
        };
        if !response.is_success() {
            continue;
        }
        let Ok(node) = response.json::<Value>() else {
            continue;
        };
        let Some(phase) = node.get("phase").filter(|p| !p.is_null()) else {
            continue;
        };
        state.connectors.seams.revolut_progress.phase(
            member_id,
            PhaseUpdate {
                phase: as_text(phase),
                remaining_seconds: int_field(&node, "remainingSeconds"),
                elapsed_seconds: int_field(&node, "elapsedSeconds"),
                accounts_found: int_field(&node, "accountsFound"),
            },
        );
    }
}

fn parse_accounts(response: &Value) -> Result<Vec<RevolutAccountData>, JobError> {
    let mut accounts = Vec::new();
    for node in elements(response.get("accounts")) {
        let account_type = if text_or_null(node, "type").as_deref() == Some("SAVINGS") {
            AccountType::Savings
        } else {
            AccountType::Checking
        };
        let currency = node
            .get("currency")
            .filter(|c| !c.is_null())
            .map_or_else(|| "EUR".to_owned(), as_text);
        let mut txns = Vec::new();
        for tx in elements(node.get("transactions")) {
            let date = tx.get("date").map(as_text).unwrap_or_default();
            let date = ISO_DATE
                .is_match(&date)
                .then(|| NaiveDate::parse_from_str(&date, "%Y-%m-%d").ok())
                .flatten()
                .ok_or_else(|| unexpected("Revolut returned a transaction without a valid date"))?;
            txns.push(RevolutTxn {
                external_id: text_or_null(tx, "externalId"),
                date,
                description: text_or_null(tx, "description"),
                amount: decimal_value(tx.get("amount")),
                counterparty: text_or_null(tx, "counterparty"),
                kind: text_or_null(tx, "kind").and_then(|k| category_kind(&k)),
                currency: text_or_null(tx, "currency"),
            });
        }
        accounts.push(RevolutAccountData {
            external_id: text_or_null(node, "externalId"),
            name: text_or_null(node, "name"),
            account_type,
            iban: text_or_null(node, "iban"),
            balance: decimal_value(node.get("balance")),
            currency,
            parent_external_id: text_or_null(node, "parentExternalId"),
            txns,
        });
    }
    Ok(accounts)
}

fn elements(node: Option<&Value>) -> &[Value] {
    node.and_then(Value::as_array).map_or(&[], Vec::as_slice)
}

/// JsonNode.asText: a string as is, a number or a boolean as its text, anything else empty.
fn as_text(node: &Value) -> String {
    match node {
        Value::String(s) => s.clone(),
        Value::Number(n) => n.to_string(),
        Value::Bool(b) => b.to_string(),
        _ => String::new(),
    }
}

fn text_or_null(node: &Value, field: &str) -> Option<String> {
    node.get(field).filter(|v| !v.is_null()).map(as_text)
}

/// JsonNode.asInt of a present, non-null field: a number truncated, a numeric string parsed,
/// anything else 0.
fn int_field(node: &Value, field: &str) -> Option<i32> {
    let value = node.get(field).filter(|v| !v.is_null())?;
    Some(match value {
        Value::Number(n) => n
            .as_i64()
            .map(|v| v as i32)
            .or_else(|| {
                Decimal::from_str(&n.to_string())
                    .ok()
                    .and_then(|d| i32::try_from(d.trunc().mantissa()).ok())
            })
            .unwrap_or(0),
        Value::String(s) => s.trim().parse().unwrap_or(0),
        Value::Bool(b) => i32::from(*b),
        _ => 0,
    })
}

/// JsonNode.decimalValue as Jackson reads an untyped tree: an integer exactly, a fraction through
/// a double (BigDecimal.valueOf drops trailing zeros but keeps one fraction digit), anything that
/// is not a number zero.
fn decimal_value(node: Option<&Value>) -> Decimal {
    let Some(Value::Number(number)) = node else {
        return Decimal::ZERO;
    };
    let text = number.to_string();
    let Ok(value) = Decimal::from_str(&text).or_else(|_| Decimal::from_scientific(&text)) else {
        return Decimal::ZERO;
    };
    if !text.contains(['.', 'e', 'E']) {
        return value;
    }
    let mut value = value.normalize();
    if value.scale() == 0 {
        value.rescale(1);
    }
    value
}

/// CategoryKind.valueOf of the trimmed, upper-cased name; None for anything else.
fn category_kind(value: &str) -> Option<CategoryKind> {
    let value = value.trim();
    if value.is_empty() {
        return None;
    }
    CategoryKind::from_str(&value.to_uppercase()).ok()
}
