//! LouveAdapter: the protocol and the token state. The session state is the token set,
//! serialized here and stored encrypted by the caller. Louve rotates refresh tokens: refresh
//! returns the new state, which the caller persists under the connection row lock before read
//! uses it. Which figures become a balance is decided by the mapper.

use std::collections::HashMap;

use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};

use super::codes;
use super::error::{Failure, Kind, LouveResult};
use super::{mcp, oauth};
use crate::AppState;
use crate::ledger::sidecar::SidecarErrorCode;

/// An access token this close to its expiry is refreshed before the run, not halfway through.
const EXPIRY_MARGIN_SECONDS: i64 = 60;
/// A portfolio is a handful of funds; this only bounds the get_scpi calls of a bad answer.
const MAX_POSITIONS: usize = 200;
const STATE_VERSION: i64 = 1;

/// One line of get_portfolio.scpi.positions. selling_total is for the whole line.
#[derive(Debug, Clone, PartialEq)]
pub struct Position {
    pub fund_code: Option<String>,
    pub label: Option<String>,
    pub share_count: Option<Decimal>,
    pub selling_total: Option<Decimal>,
    pub ownership: Vec<String>,
}

/// What get_scpi says about one fund, per share. isin_code is Louve's raw code.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Fund {
    pub name: Option<String>,
    pub isin_code: Option<String>,
    pub management_company: Option<String>,
    pub subscription_price: Option<Decimal>,
    pub withdrawal_price: Option<Decimal>,
    pub distribution_rate: Option<Decimal>,
    pub rent_frequency: Option<String>,
}

/// declared_selling_total is get_portfolio.scpi.sellingPrice, the envelope the lines must add up
/// to. funds is keyed by fund code; a fund whose sheet Louve could not serve is absent.
#[derive(Debug, Clone, PartialEq)]
pub struct Snapshot {
    pub declared_selling_total: Option<Decimal>,
    pub positions: Vec<Position>,
    pub funds: HashMap<String, Fund>,
}

/// The stored token set. redirect_uri names the registration client_id belongs to, so a refused
/// client can be forgotten. Field order is Java's.
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TokenState {
    v: i64,
    client_id: String,
    redirect_uri: String,
    access_token: String,
    refresh_token: String,
    expires_at: i64,
}

fn serialize(state: &TokenState) -> String {
    serde_json::to_string(state).expect("a token state serializes")
}

fn unusable() -> Failure {
    Failure::sync(
        SidecarErrorCode::InvalidSessionState,
        "The stored Louve Invest access is unusable. Please reconnect.",
    )
}

/// The stored state, or INVALID_SESSION_STATE: every field is required, the redirect URI included.
fn parse(plain: &str) -> LouveResult<TokenState> {
    let node: Value = serde_json::from_str(plain).map_err(|_| unusable())?;
    let Value::Object(node) = node else {
        return Err(unusable());
    };
    let field = |name: &str| text(&node, name).ok_or_else(unusable);
    if node.get("v").and_then(Value::as_i64) != Some(STATE_VERSION) {
        return Err(unusable());
    }
    let expires_at = node
        .get("expiresAt")
        .and_then(Value::as_i64)
        .ok_or_else(unusable)?;
    Ok(TokenState {
        v: STATE_VERSION,
        client_id: field("clientId")?,
        redirect_uri: field("redirectUri")?,
        access_token: field("accessToken")?,
        refresh_token: field("refreshToken")?,
        expires_at,
    })
}

fn expiry(state: &AppState, tokens: &oauth::Tokens) -> i64 {
    state.clock.now().timestamp() + tokens.expires_in_seconds
}

/// Exchanges the code and returns the session state to store (encrypted) for the member.
pub async fn exchange_code(
    state: &AppState,
    client_id: &str,
    redirect_uri: &str,
    code: &str,
    verifier: &str,
) -> LouveResult<String> {
    let tokens = oauth::exchange_code(state, client_id, redirect_uri, code, verifier).await?;
    let Some(refresh_token) = tokens.refresh_token.clone() else {
        // Without one, the connection would silently die after an hour.
        return Err(Failure::format_changed(
            "Louve Invest granted no lasting access",
        ));
    };
    Ok(serialize(&TokenState {
        v: STATE_VERSION,
        client_id: client_id.to_owned(),
        redirect_uri: redirect_uri.to_owned(),
        access_token: tokens.access_token.clone(),
        refresh_token,
        expires_at: expiry(state, &tokens),
    }))
}

/// Revokes the grant behind a stored state. Best effort, never fails.
pub async fn revoke(state: &AppState, plain: &str) {
    let Ok(tokens) = parse(plain) else {
        return;
    };
    // Revoking the refresh token ends the grant; the access token is revoked too in case Louve
    // treats them separately.
    oauth::revoke(
        state,
        &tokens.client_id,
        &tokens.refresh_token,
        "refresh_token",
    )
    .await;
    oauth::revoke(
        state,
        &tokens.client_id,
        &tokens.access_token,
        "access_token",
    )
    .await;
}

/// Whether the access token expires within the margin, so the run refreshes first.
pub fn needs_refresh(state: &AppState, plain: &str) -> LouveResult<bool> {
    let expires_at =
        DateTime::<Utc>::from_timestamp(parse(plain)?.expires_at, 0).ok_or_else(unusable)?;
    Ok(expires_at <= state.clock.now() + chrono::Duration::seconds(EXPIRY_MARGIN_SECONDS))
}

/// Refreshes the access token and returns the new state, which the caller must persist before
/// using it: Louve has already invalidated the old refresh token.
pub async fn refresh(state: &AppState, plain: &str) -> LouveResult<String> {
    let current = parse(plain)?;
    let tokens = match oauth::refresh(state, &current.client_id, &current.refresh_token).await {
        // The refresh request carries no redirect URI; the stored state knows which registration
        // the refused client belongs to.
        Err(Failure::Sync {
            code,
            message,
            kind: Kind::InvalidClient { client_id, .. },
        }) => {
            return Err(Failure::Sync {
                code,
                message,
                kind: Kind::InvalidClient {
                    client_id,
                    redirect_uri: Some(current.redirect_uri),
                },
            });
        }
        other => other?,
    };
    // Louve rotates: keep the old refresh token only if none came back.
    let refresh_token = tokens
        .refresh_token
        .clone()
        .unwrap_or(current.refresh_token.clone());
    Ok(serialize(&TokenState {
        v: STATE_VERSION,
        client_id: current.client_id,
        redirect_uri: current.redirect_uri,
        access_token: tokens.access_token.clone(),
        refresh_token,
        expires_at: expiry(state, &tokens),
    }))
}

/// Reads the SCPI portfolio with the state's access token. A refused token is Unauthorized: the
/// caller refreshes once and reads again.
pub async fn read(state: &AppState, plain: &str) -> LouveResult<Snapshot> {
    let access_token = parse(plain)?.access_token;
    let mut session = mcp::open(state, &access_token).await?;
    // includeExternal=false: external lines are SCPIs the member declared on Louve as held
    // elsewhere. Louve does not hold them, and the member may already sync them from that place.
    let portfolio = session
        .call_tool("get_portfolio", json!({"includeExternal": false}))
        .await?;
    // No "scpi" key at all is another shape, not "no SCPI": reading it as empty would report
    // every known fund as missing.
    let Some(scpi) = portfolio.get("scpi") else {
        return Err(Failure::format_changed(
            "Louve Invest returned a portfolio without its SCPI side",
        ));
    };
    let scpi = match scpi {
        // An explicit null SCPI side: an account holding only life insurance, say.
        Value::Null => {
            return Ok(Snapshot {
                declared_selling_total: Some(Decimal::ZERO),
                positions: Vec::new(),
                funds: HashMap::new(),
            });
        }
        Value::Object(scpi) => scpi,
        _ => return Err(no_positions()),
    };
    let Some(Value::Array(lines)) = scpi.get("positions") else {
        return Err(no_positions());
    };
    if lines.len() > MAX_POSITIONS {
        return Err(Failure::sync(
            SidecarErrorCode::InvalidData,
            "Louve Invest returned too many positions",
        ));
    }
    let mut positions = Vec::with_capacity(lines.len());
    for line in lines {
        let Value::Object(line) = line else {
            return Err(Failure::format_changed(
                "Louve Invest returned an unreadable SCPI position",
            ));
        };
        // Required, and an array of strings: missing or null is another format, not
        // "dismembered". Every element is checked, none dropped: filtering an object out of
        // ["Pleine propriété", {...}] would value a dismembered line as full ownership.
        let Some(Value::Array(owned)) = line.get("ownership") else {
            return Err(unreadable_ownership());
        };
        let ownership = owned
            .iter()
            .map(|item| {
                item.as_str()
                    .map(str::to_owned)
                    .ok_or_else(unreadable_ownership)
            })
            .collect::<LouveResult<Vec<_>>>()?;
        positions.push(Position {
            fund_code: text(line, "scpiUrlId"),
            label: text(line, "scpiName"),
            share_count: decimal(line, "nbShares"),
            selling_total: decimal(line, "sellingPrice"),
            ownership,
        });
    }
    let mut funds = HashMap::new();
    for position in &positions {
        let Some(code) = position.fund_code.as_deref() else {
            continue;
        };
        if funds.contains_key(code) || !codes::is_fund_code(code) {
            continue;
        }
        match session.call_tool("get_scpi", json!({"scpi": code})).await {
            Ok(sheet) => {
                funds.insert(code.to_owned(), fund(code, &sheet)?);
            }
            Err(failure) if failure.kind() == Some(&Kind::ToolFailed) => {
                // The portfolio line still carries its own value; the mapper decides without it.
                tracing::warn!("Louve Invest could not serve a fund sheet (fund={code})");
            }
            Err(failure) => return Err(failure),
        }
    }
    Ok(Snapshot {
        declared_selling_total: decimal(scpi, "sellingPrice"),
        positions,
        funds,
    })
}

fn no_positions() -> Failure {
    Failure::format_changed("Louve Invest returned a portfolio without its SCPI positions")
}

fn unreadable_ownership() -> Failure {
    Failure::format_changed("Louve Invest returned an unreadable ownership")
}

fn fund(code: &str, sheet: &Map<String, Value>) -> LouveResult<Fund> {
    let Some(url_id) = text(sheet, "urlId") else {
        return Err(Failure::format_changed(
            "Louve Invest returned a fund sheet without its identifier",
        ));
    };
    if url_id != code {
        return Err(Failure::sync(
            SidecarErrorCode::InvalidData,
            "Louve Invest answered another fund than the one asked for",
        ));
    }
    Ok(Fund {
        name: text(sheet, "name"),
        isin_code: text(sheet, "isinCode"),
        management_company: text(sheet, "managementCompany"),
        subscription_price: decimal(sheet, "buyingPriceByShare"),
        withdrawal_price: decimal(sheet, "sellingPriceByShare"),
        distribution_rate: decimal(sheet, "distributionRate"),
        rent_frequency: text(sheet, "rentFrequency"),
    })
}

/// A non-blank string field, trimmed.
fn text(node: &Map<String, Value>, field: &str) -> Option<String> {
    node.get(field)
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .map(str::to_owned)
}

/// A JSON number (or a numeric string) as a decimal; None when absent or unreadable.
fn decimal(node: &Map<String, Value>, field: &str) -> Option<Decimal> {
    let text = match node.get(field)? {
        Value::Number(number) => number.to_string(),
        Value::String(text) => text.trim().to_owned(),
        _ => return None,
    };
    parse_decimal(&text)
}

/// new BigDecimal(text): plain or scientific notation.
fn parse_decimal(text: &str) -> Option<Decimal> {
    if text.contains(['e', 'E']) {
        Decimal::from_scientific(text).ok()
    } else {
        text.parse::<Decimal>().ok()
    }
}
