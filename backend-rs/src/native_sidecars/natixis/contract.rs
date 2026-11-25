//! Wire contract types and strict input/output validation for the Natixis native sidecar.
//!
//! Mirrors the Pydantic models (`extra="forbid"`) and FastAPI `otp_field_detail` validation
//! behavior of `services/natixis-auth/main.py`.

use std::sync::LazyLock;

use http::StatusCode;
use regex::Regex;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::native_sidecars::NativeError;

static OTP_CODE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[0-9]{4,12}$").expect("valid OTP regex"));
static ISO_DATE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^\d{4}-\d{2}-\d{2}$").expect("valid date regex"));

/// Domain error carrying an HTTP status and a contract detail code.
/// The internal log reason never carries secrets or raw bank payloads.
#[derive(Debug, Clone)]
pub(crate) struct NatixisError {
    pub status: StatusCode,
    pub code: &'static str,
    pub reason: String,
}

impl NatixisError {
    pub fn new(status: StatusCode, code: &'static str, reason: impl Into<String>) -> Self {
        Self {
            status,
            code,
            reason: reason.into(),
        }
    }

    pub fn format_changed(reason: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED", reason)
    }

    pub fn unavailable(reason: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE", reason)
    }

    pub fn into_native(self) -> NativeError {
        NativeError::new(self.status, self.code)
    }
}

#[derive(Debug, Clone)]
pub(crate) struct InitiateRequest {
    pub email: String,
    pub password: String,
}

#[derive(Debug, Clone)]
pub(crate) struct CompleteRequest {
    pub process_id: String,
    pub code: String,
}

#[derive(Debug, Clone)]
pub(crate) struct CancelRequest {
    pub process_id: String,
}

#[derive(Debug, Clone)]
pub(crate) struct PositionsRequest {
    pub session_state: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct InitiateResponse {
    pub process_id: String,
    pub mfa_required: bool,
    pub mfa_type: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SessionResponse {
    pub session_state: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PositionPayload {
    pub isin: Option<String>,
    pub fund_code: Option<String>,
    pub label: String,
    pub quantity: Decimal,
    pub unit_value: Option<Decimal>,
    pub value_eur: Decimal,
    pub pnl_eur: Option<Decimal>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanPayload {
    pub external_id: String,
    pub name: String,
    pub plan_kind: Option<String>,
    pub employer: Option<String>,
    pub balance_eur: Decimal,
    pub positions: Vec<PositionPayload>,
    pub snapshot_complete: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OperationPayload {
    pub external_id: String,
    pub plan_external_id: String,
    pub date: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub label: String,
    pub fund_label: String,
    pub fund_code: Option<String>,
    pub quantity: Decimal,
    pub unit_value: Option<Decimal>,
    pub amount_eur: Decimal,
    pub fees_eur: Option<Decimal>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PositionsResponse {
    pub session_state: String,
    pub plans: Vec<PlanPayload>,
    pub operations_complete: bool,
    pub operations_from: Option<String>,
    pub operations: Vec<OperationPayload>,
}

fn require_object(body: Option<Value>) -> Result<Map<String, Value>, NativeError> {
    match body {
        Some(Value::Object(map)) => Ok(map),
        _ => Err(NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA")),
    }
}

fn check_no_extra_fields(map: &Map<String, Value>, allowed: &[&str]) -> bool {
    map.keys().all(|k| allowed.contains(&k.as_str()))
}

fn extract_bounded_str(
    map: &Map<String, Value>,
    key: &str,
    min: usize,
    max: usize,
) -> Option<String> {
    let val = map.get(key)?.as_str()?;
    let len = val.chars().count();
    if len < min || len > max {
        return None;
    }
    Some(val.to_owned())
}

impl InitiateRequest {
    pub fn parse(body: Option<Value>) -> Result<Self, NativeError> {
        let map = require_object(body)?;
        if !check_no_extra_fields(&map, &["email", "password"]) {
            return Err(NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA"));
        }
        let Some(email) = extract_bounded_str(&map, "email", 1, 254) else {
            return Err(NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA"));
        };
        let Some(password) = extract_bounded_str(&map, "password", 1, 100) else {
            return Err(NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA"));
        };
        Ok(Self { email, password })
    }
}

impl CompleteRequest {
    /// Mirrors FastAPI + `otp_field_detail("INVALID_OTP", "INVALID_DATA")`:
    /// whenever `code` fails validation (missing, wrong type, or not `^[0-9]{4,12}$`),
    /// responds `400 INVALID_OTP`; any other schema error responds `400 INVALID_DATA`.
    pub fn parse(body: Option<Value>) -> Result<Self, NativeError> {
        let map = require_object(body)?;
        let code_valid = map
            .get("code")
            .and_then(Value::as_str)
            .filter(|s| OTP_CODE_RE.is_match(s))
            .map(str::to_owned);
        if code_valid.is_none() {
            return Err(NativeError::new(StatusCode::BAD_REQUEST, "INVALID_OTP"));
        }
        if !check_no_extra_fields(&map, &["processId", "code"]) {
            return Err(NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA"));
        }
        let Some(process_id) = extract_bounded_str(&map, "processId", 1, 100) else {
            return Err(NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA"));
        };
        Ok(Self {
            process_id,
            code: code_valid.expect("checked above"),
        })
    }
}

impl CancelRequest {
    pub fn parse(body: Option<Value>) -> Result<Self, NativeError> {
        let map = require_object(body)?;
        if !check_no_extra_fields(&map, &["processId"]) {
            return Err(NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA"));
        }
        let Some(process_id) = extract_bounded_str(&map, "processId", 1, 100) else {
            return Err(NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA"));
        };
        Ok(Self { process_id })
    }
}

impl PositionsRequest {
    pub fn parse(body: Option<Value>) -> Result<Self, NativeError> {
        let map = require_object(body)?;
        if !check_no_extra_fields(&map, &["sessionState"]) {
            return Err(NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA"));
        }
        let Some(session_state) = extract_bounded_str(&map, "sessionState", 2, 10_000) else {
            return Err(NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA"));
        };
        Ok(Self { session_state })
    }
}

fn in_bounds(s: &str, min: usize, max: usize) -> bool {
    let len = s.chars().count();
    len >= min && len <= max
}

impl PositionPayload {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.isin.is_some() {
            return Err("isin must be null");
        }
        if let Some(code) = &self.fund_code
            && code.chars().count() > 20
        {
            return Err("fundCode too long");
        }
        if !in_bounds(&self.label, 1, 200) {
            return Err("position label length out of bounds");
        }
        Ok(())
    }
}

impl PlanPayload {
    pub fn validate(&self) -> Result<(), &'static str> {
        if !in_bounds(&self.external_id, 1, 100) {
            return Err("plan externalId length out of bounds");
        }
        if !in_bounds(&self.name, 1, 200) {
            return Err("plan name length out of bounds");
        }
        if let Some(kind) = &self.plan_kind
            && kind.chars().count() > 30
        {
            return Err("planKind too long");
        }
        if let Some(employer) = &self.employer
            && employer.chars().count() > 200
        {
            return Err("employer too long");
        }
        if !self.snapshot_complete {
            return Err("snapshotComplete must be true");
        }
        for pos in &self.positions {
            pos.validate()?;
        }
        Ok(())
    }
}

impl OperationPayload {
    pub fn validate(&self) -> Result<(), &'static str> {
        if !in_bounds(&self.external_id, 1, 100) {
            return Err("operation externalId length out of bounds");
        }
        if !in_bounds(&self.plan_external_id, 1, 100) {
            return Err("operation planExternalId length out of bounds");
        }
        if !ISO_DATE_RE.is_match(&self.date) {
            return Err("operation date is not YYYY-MM-DD");
        }
        if self.kind != "BUY" && self.kind != "SELL" {
            return Err("operation type must be BUY or SELL");
        }
        if !in_bounds(&self.label, 1, 200) {
            return Err("operation label length out of bounds");
        }
        if !in_bounds(&self.fund_label, 1, 200) {
            return Err("operation fundLabel length out of bounds");
        }
        if let Some(code) = &self.fund_code
            && code.chars().count() > 20
        {
            return Err("operation fundCode too long");
        }
        Ok(())
    }
}
