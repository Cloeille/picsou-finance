//! LouvePortfolioMapper: a Louve Invest snapshot into the fund lines the SCPI reconciler writes,
//! under the SCPI invariants: the lines add up to the declared total or nothing is written; the
//! balance comes from the withdrawal price (the sheet's, cross-checked with the line's, the lower
//! one on disagreement); a dismembered line is not valued; the distribution per share is the
//! rate times the subscription price. Logs carry fund codes only, never a figure.

use std::collections::HashSet;
use std::sync::LazyLock;

use regex::Regex;
use rust_decimal::Decimal;
use unicode_normalization::UnicodeNormalization;

use super::adapter::{Fund, Position, Snapshot};
use super::codes;
use super::error::{Failure, LouveResult};
use super::scpi::{DistributionFrequency, SyncedFund, UnsupportedFund};
use crate::ledger::decimal::{HALF_UP, divide, set_scale};
use crate::ledger::sidecar::SidecarErrorCode;

/// Several lines each rounded to the cent; one euro absorbs that and nothing a missing line hides.
const ENVELOPE_TOLERANCE: Decimal = Decimal::ONE;
/// A per-share price derived from a total rounded to the cent is off by less than this.
const PRICE_TOLERANCE: Decimal = Decimal::from_parts(1, 0, 0, false, 2);
const PRICE_SCALE: u32 = 8;
const FULL_OWNERSHIP: &str = "pleine propriete";

static MARKS: LazyLock<Regex> = LazyLock::new(|| Regex::new("\\p{M}+").expect("valid regex"));
static NON_LETTERS: LazyLock<Regex> = LazyLock::new(|| Regex::new("[^a-z]+").expect("valid regex"));

#[derive(Debug)]
pub struct Mapped {
    pub funds: Vec<SyncedFund>,
    pub unsupported: Vec<UnsupportedFund>,
}

fn error(code: SidecarErrorCode, message: &str) -> Failure {
    Failure::sync(code, message)
}

pub fn map(snapshot: &Snapshot) -> LouveResult<Mapped> {
    for position in &snapshot.positions {
        if !position
            .fund_code
            .as_deref()
            .is_some_and(codes::is_fund_code)
        {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "Louve Invest returned a position without a usable fund code",
            ));
        }
        if position
            .share_count
            .is_none_or(|c| c.is_sign_negative() && !c.is_zero())
        {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "Louve Invest returned a position without a valid share count",
            ));
        }
        if position
            .selling_total
            .is_some_and(|t| t.is_sign_negative() && !t.is_zero())
        {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "Louve Invest returned a negative position value",
            ));
        }
    }
    check_envelope(snapshot)?;

    let mut full: Vec<&Position> = Vec::new();
    let mut dismembered: Vec<(&str, Option<&str>)> = Vec::new();
    for position in &snapshot.positions {
        let code = position.fund_code.as_deref().unwrap_or_default();
        if is_full_ownership(&position.ownership) {
            if full.iter().any(|p| p.fund_code.as_deref() == Some(code)) {
                // Two full-ownership lines for one fund would write the share count twice.
                return Err(error(
                    SidecarErrorCode::InvalidData,
                    "Louve Invest returned a duplicate fund",
                ));
            }
            full.push(position);
        } else if !dismembered.iter().any(|(c, _)| *c == code) {
            dismembered.push((code, position.label.as_deref()));
        }
    }

    let funds = full
        .iter()
        .map(|p| {
            synced_fund(
                p,
                p.fund_code.as_deref().and_then(|c| snapshot.funds.get(c)),
            )
        })
        .collect();
    let mut seen: HashSet<&str> = full.iter().filter_map(|p| p.fund_code.as_deref()).collect();
    let mut unsupported = Vec::new();
    for (code, label) in dismembered {
        if !seen.insert(code) {
            tracing::info!(
                "Louve Invest lists a fund in full and dismembered ownership, only the full line is synced (fund={code})"
            );
            continue;
        }
        unsupported.push(UnsupportedFund {
            fund_code: code.to_owned(),
            label: label.map(str::to_owned),
        });
    }
    Ok(Mapped { funds, unsupported })
}

fn check_envelope(snapshot: &Snapshot) -> LouveResult<()> {
    if snapshot.positions.is_empty() {
        if snapshot
            .declared_selling_total
            .is_some_and(|d| d.abs() > ENVELOPE_TOLERANCE)
        {
            return Err(error(
                SidecarErrorCode::PortfolioIncomplete,
                "Louve Invest declared SCPI value but listed no position",
            ));
        }
        return Ok(());
    }
    let declared = match snapshot.declared_selling_total {
        Some(d) if !d.is_sign_negative() || d.is_zero() => d,
        _ => {
            return Err(error(
                SidecarErrorCode::PortfolioIncomplete,
                "Louve Invest returned no valid SCPI total",
            ));
        }
    };
    let mut computed = Decimal::ZERO;
    for position in &snapshot.positions {
        let Some(total) = position.selling_total else {
            return Err(error(
                SidecarErrorCode::PortfolioIncomplete,
                "Louve Invest returned a position without its value",
            ));
        };
        computed += total;
    }
    if (computed - declared).abs() > ENVELOPE_TOLERANCE {
        // No figure in the message: the handler and the sync logs print it.
        return Err(error(
            SidecarErrorCode::PortfolioIncomplete,
            "The Louve Invest positions do not add up to the declared SCPI total",
        ));
    }
    Ok(())
}

fn synced_fund(position: &Position, fund: Option<&Fund>) -> SyncedFund {
    let code = position.fund_code.clone().unwrap_or_default();
    let subscription = fund.and_then(|f| positive(f.subscription_price));
    let withdrawal = withdrawal_price(position, fund);
    let label = position
        .label
        .clone()
        .or_else(|| fund.and_then(|f| f.name.clone()))
        .unwrap_or_else(|| code.clone());
    let frequency = fund.and_then(|f| frequency(f.rent_frequency.as_deref()));
    let annual = annual_distribution(&code, fund, subscription, frequency);
    SyncedFund {
        fund_code: code,
        label: Some(label),
        quantity: position.share_count.unwrap_or_default(),
        subscription_price: subscription,
        withdrawal_price: withdrawal,
        management_company: fund.and_then(|f| f.management_company.clone()),
        isin: fund.and_then(|f| codes::isin_or_none(f.isin_code.as_deref())),
        annual_distribution_per_share: annual,
        distribution_frequency: annual.and(frequency),
    }
}

/// The withdrawal price per share, or None (PRICE_INCOMPLETE). The sheet's published price wins
/// when the line agrees with it to the cent; otherwise the lower of the two.
fn withdrawal_price(position: &Position, fund: Option<&Fund>) -> Option<Decimal> {
    let published = fund.and_then(|f| positive(f.withdrawal_price));
    let shares = position.share_count.unwrap_or_default();
    let derived = match position.selling_total {
        Some(total) if shares > Decimal::ZERO => Some(divide(total, shares, PRICE_SCALE, HALF_UP)),
        _ => None,
    };
    let Some(published) = published else {
        return derived.filter(|d| !d.is_zero());
    };
    match derived {
        Some(derived) if (derived - published).abs() > PRICE_TOLERANCE => {
            tracing::warn!(
                "Louve Invest fund sheet and position disagree on the withdrawal price, the lower one is used (fund={})",
                position.fund_code.as_deref().unwrap_or_default()
            );
            Some(if derived <= published {
                derived
            } else {
                published
            })
        }
        _ => Some(published),
    }
}

fn annual_distribution(
    code: &str,
    fund: Option<&Fund>,
    subscription: Option<Decimal>,
    frequency: Option<DistributionFrequency>,
) -> Option<Decimal> {
    let rate = fund?.distribution_rate?;
    let subscription = subscription?;
    frequency?;
    if (rate.is_sign_negative() && !rate.is_zero()) || rate >= Decimal::ONE {
        // A fraction is expected (0.065). Anything else is not guessed into a percentage.
        tracing::warn!(
            "Louve Invest returned a distribution rate outside [0, 1), left untouched (fund={code})"
        );
        return None;
    }
    Some(set_scale(rate * subscription, 2, HALF_UP))
}

fn frequency(rent_frequency: Option<&str>) -> Option<DistributionFrequency> {
    match rent_frequency?.trim().to_lowercase().as_str() {
        "monthly" => Some(DistributionFrequency::Monthly),
        "quarterly" => Some(DistributionFrequency::Quarterly),
        _ => None,
    }
}

/// Exactly one ownership, and it is full ownership. Anything else, missing included, is not.
fn is_full_ownership(ownership: &[String]) -> bool {
    match ownership {
        [only] => normalize(only) == FULL_OWNERSHIP,
        _ => false,
    }
}

fn normalize(value: &str) -> String {
    let decomposed: String = value.nfd().collect();
    let stripped = MARKS.replace_all(&decomposed, "").to_lowercase();
    NON_LETTERS.replace_all(&stripped, " ").trim().to_owned()
}

fn positive(value: Option<Decimal>) -> Option<Decimal> {
    value.filter(|v| *v > Decimal::ZERO)
}
