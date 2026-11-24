//! CorumAdapter: read-only access to a CORUM client space through its sidecar. CORUM asks for
//! no second factor, so a login is one round-trip, and one read returns the whole portfolio:
//! the real-estate contract, if any, and every CORUM Life contract.

use std::time::Duration;

use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Deserialize;
use serde_json::json;

use super::super::sidecar::{SidecarClient, SidecarSessionState, SyncError};
use crate::AppState;
use crate::http::json::null_as_false;
use crate::ledger::sidecar::{SidecarBank, SidecarErrorCode};

const AUTH_TIMEOUT: Duration = Duration::from_secs(60);
/// Every contract is read, plus one call per real-estate fund: it scales with the portfolio.
const PORTFOLIO_TIMEOUT: Duration = Duration::from_secs(120);

/// Everything one CORUM login holds. \`real_estate\` is None without a real-estate contract and
/// \`life\` empty without a life contract; both absent is refused.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Portfolio {
    pub real_estate: Option<Snapshot>,
    pub life: Option<Vec<Option<LifeContract>>>,
}

/// One read of the real-estate contract; one holding per fund.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    pub currency: Option<String>,
    pub total_valuation_eur: Option<Decimal>,
    #[serde(default, deserialize_with = "null_as_false")]
    pub snapshot_complete: bool,
    pub holdings: Option<Vec<Option<Holding>>>,
}

/// One fund of the real-estate contract. \`withdrawal_price\` is the only price that may become
/// a balance; \`displayed_value_eur\` is quantity x subscription price, for the total check only.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Holding {
    pub fund_code: Option<String>,
    pub label: Option<String>,
    pub quantity: Option<Decimal>,
    pub withdrawal_price: Option<Decimal>,
    pub subscription_price: Option<Decimal>,
    pub displayed_value_eur: Option<Decimal>,
}

/// One CORUM Life contract; every line is a holding, the euro fund included.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LifeContract {
    pub contract_code: Option<String>,
    pub currency: Option<String>,
    pub total_valuation_eur: Option<Decimal>,
    /// The raw product rows the sidecar summed, each rounded on its own.
    pub valuation_line_count: Option<i32>,
    pub net_invested_eur: Option<Decimal>,
    pub paid_in_eur: Option<Decimal>,
    pub opened_at: Option<NaiveDate>,
    pub scheduled_payment: Option<ScheduledPayment>,
    #[serde(default, deserialize_with = "null_as_false")]
    pub snapshot_complete: bool,
    pub holdings: Option<Vec<Option<LifeHolding>>>,
    /// None when the list was not read: the stored rows are then kept.
    pub operations: Option<Vec<Option<LifeOperation>>>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScheduledPayment {
    pub amount_eur: Option<Decimal>,
    pub frequency: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LifeHolding {
    pub product_code: Option<String>,
    pub label: Option<String>,
    pub quantity: Option<Decimal>,
    pub unit_price: Option<Decimal>,
    pub value_eur: Option<Decimal>,
    pub net_invested_eur: Option<Decimal>,
    pub valuation_date: Option<NaiveDate>,
    #[serde(default, deserialize_with = "null_as_false")]
    pub euro_fund: bool,
    pub asset_class: Option<String>,
}

/// One operation of a life contract, in CORUM's own words, the amount signed as CORUM shows it.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LifeOperation {
    pub operation_id: Option<String>,
    #[serde(rename = "type")]
    pub kind: Option<String>,
    pub date: Option<NaiveDate>,
    pub amount_eur: Option<Decimal>,
    pub fund_codes: Option<Vec<String>>,
}

fn client(state: &AppState) -> SidecarClient {
    SidecarClient::new(state, SidecarBank::Corum, SidecarErrorCode::SessionExpired)
}

/// The session state of a fresh login.
pub async fn authenticate(
    state: &AppState,
    login: &str,
    password: &str,
) -> Result<Option<String>, SyncError> {
    let response: SidecarSessionState = client(state)
        .post(
            "/initiate",
            &json!({ "login": login, "password": password }),
            AUTH_TIMEOUT,
            "Could not authenticate with CORUM",
            Some(SidecarErrorCode::InvalidCredentials),
        )
        .await?;
    Ok(response.session_state)
}

/// Reads the whole portfolio. A bare 401 can only mean the session died, the credentials having
/// been checked at login; a contract flagged incomplete fails the whole read.
pub async fn fetch_portfolio(
    state: &AppState,
    session_state: &str,
) -> Result<Portfolio, SyncError> {
    let portfolio: Portfolio = client(state)
        .post(
            "/portfolio",
            &json!({ "sessionState": session_state }),
            PORTFOLIO_TIMEOUT,
            "Could not fetch the CORUM portfolio",
            Some(SidecarErrorCode::SessionExpired),
        )
        .await?;
    let incomplete_estate = portfolio
        .real_estate
        .as_ref()
        .is_some_and(|s| !s.snapshot_complete);
    let incomplete_life = portfolio
        .life
        .iter()
        .flatten()
        .flatten()
        .any(|c| !c.snapshot_complete);
    if incomplete_estate || incomplete_life {
        return Err(SyncError::new(
            SidecarErrorCode::PortfolioIncomplete,
            "CORUM returned an incomplete portfolio",
        ));
    }
    Ok(portfolio)
}
