//! CorumLifeWriter: the CORUM Life (assurance-vie) contracts of a portfolio, one account each.
//!
//! For each contract: the live account carrying its external id is updated; a soft-deleted one
//! stops the sync from bringing it back; otherwise, when the portfolio holds exactly one contract,
//! the one manual assurance-vie account named "CORUM Life" or filed under CORUM, unlinked and
//! without holdings, is adopted; when such accounts exist but none can be adopted nothing is
//! written (AMBIGUOUS), since a new account beside it would count the contract twice; otherwise
//! an account is created. The name is set on creation only.
//!
//! The units are holdings valued by CORUM itself; the contract's net invested amount is the cost
//! basis, and a line's own cost is kept only when the lines add up to it.

use std::collections::HashSet;

use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::adapter::{LifeContract, LifeHolding, LifeOperation, ScheduledPayment};
use super::operations::{self, TICKER_PREFIX};
use super::scpi_funds::normalize;
use crate::AppState;
use crate::connectors::sidecar::{JobResult, SyncError};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::decimal::{HALF_UP, divide, set_scale};
use crate::ledger::model::{
    Account, AccountType, CORUM_LIFE_EXTERNAL_ID_PREFIX, HoldingDraft, RecurringCadence, WealthTier,
};
use crate::ledger::sidecar::SidecarErrorCode;
use crate::ledger::sync_values::{clean, lines_close};
use crate::ledger::valuation::{self, SnapshotContext};
use crate::ledger::{holdings, snapshots};

pub const PROVIDER: &str = "CORUM";
const ACCOUNT_NAME: &str = "CORUM Life";
const ACCOUNT_COLOR: &str = "#8b5cf6";
const ACCOUNT_NAME_MAX: usize = 100;
const HOLDING_NAME_MAX: usize = 255;
const OPERATION_ID_MAX: usize = 40;
const OPERATION_TYPE_MAX: usize = 60;
const UNIT_PRICE_SCALE: u32 = 8;

/// What a sync did with one contract; AMBIGUOUS and IGNORED wrote nothing.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Outcome {
    Updated,
    Linked,
    Created,
    Ambiguous,
    Ignored,
}

impl Outcome {
    pub const ALL: [Outcome; 5] = [
        Self::Updated,
        Self::Linked,
        Self::Created,
        Self::Ambiguous,
        Self::Ignored,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Updated => "UPDATED",
            Self::Linked => "LINKED",
            Self::Created => "CREATED",
            Self::Ambiguous => "AMBIGUOUS",
            Self::Ignored => "IGNORED",
        }
    }
}

fn error(code: SidecarErrorCode, message: impl Into<String>) -> SyncError {
    SyncError::new(code, message)
}

fn truncate(value: &str, max: usize) -> String {
    value.chars().take(max).collect()
}

fn code_of(contract: &LifeContract) -> String {
    contract
        .contract_code
        .as_deref()
        .unwrap_or("")
        .trim()
        .to_owned()
}

fn ticker(holding: &LifeHolding) -> String {
    format!(
        "{TICKER_PREFIX}{}",
        holding
            .product_code
            .as_deref()
            .unwrap_or("")
            .trim()
            .to_uppercase()
    )
}

/// The contract's lines, all present once validated.
pub fn lines(contract: &LifeContract) -> Vec<&LifeHolding> {
    contract.holdings.iter().flatten().flatten().collect()
}

fn operations_of(contract: &LifeContract) -> Option<Vec<&LifeOperation>> {
    contract
        .operations
        .as_ref()
        .map(|ops| ops.iter().flatten().collect())
}

/// Refuses contracts that cannot be written as they are. The sidecar checks all of this too;
/// repeating it is deliberate, a stale sidecar image being exactly what would skip its check.
pub fn validate(contracts: Option<&[Option<LifeContract>]>) -> Result<(), SyncError> {
    let Some(contracts) = contracts else {
        return Err(error(
            SidecarErrorCode::InvalidData,
            "CORUM returned no life contract list",
        ));
    };
    let mut codes = HashSet::new();
    for contract in contracts {
        let Some(contract) = contract
            .as_ref()
            .filter(|c| clean(c.contract_code.as_deref()).is_some())
        else {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CORUM returned a life contract without a code",
            ));
        };
        if !codes.insert(code_of(contract)) {
            // Two reads of one contract would write one account twice.
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CORUM returned a duplicate life contract",
            ));
        }
        if !contract.snapshot_complete {
            return Err(error(
                SidecarErrorCode::PortfolioIncomplete,
                "CORUM returned an incomplete life contract",
            ));
        }
        if contract.currency.as_deref() != Some("EUR") {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CORUM returned a life contract not in euros",
            ));
        }
        validate_figures(contract)?;
        validate_operations(contract.operations.as_deref())?;
    }
    Ok(())
}

/// An operation without an id, a type, a date or an amount cannot be reconciled; two that still
/// share a key are not refused but dropped by [\`reported_operations\`], keeping the stored rows.
fn validate_operations(operations: Option<&[Option<LifeOperation>]>) -> Result<(), SyncError> {
    let Some(operations) = operations else {
        return Ok(());
    };
    let mut valid = Vec::with_capacity(operations.len());
    for operation in operations {
        let Some(operation) = operation.as_ref().filter(|o| {
            clean(o.operation_id.as_deref()).is_some()
                && clean(o.kind.as_deref()).is_some()
                && o.date.is_some()
                && o.amount_eur.is_some()
        }) else {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CORUM returned an incomplete life operation",
            ));
        };
        let long = |value: &Option<String>, max: usize| {
            value.as_deref().unwrap_or("").trim().chars().count() > max
        };
        if long(&operation.operation_id, OPERATION_ID_MAX)
            || long(&operation.kind, OPERATION_TYPE_MAX)
        {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CORUM returned an unreadable life operation",
            ));
        }
        valid.push(operation);
    }
    if operations::external_ids(&valid).is_none() {
        // Counts only: the rows carry the member's money.
        tracing::warn!(
            "CORUM Life operations share a key, list not written (operations={})",
            valid.len()
        );
    }
    Ok(())
}

/// The operations as the reconciliation takes them: None ("not read") when two still share a key.
fn reported_operations(contract: &LifeContract) -> Option<Vec<&LifeOperation>> {
    operations_of(contract).filter(|ops| operations::external_ids(ops).is_some())
}

fn validate_figures(contract: &LifeContract) -> Result<(), SyncError> {
    let declared = contract
        .total_valuation_eur
        .filter(|d| *d >= Decimal::ZERO)
        .ok_or_else(|| {
            error(
                SidecarErrorCode::PortfolioIncomplete,
                "CORUM returned no valid life contract total",
            )
        })?;
    let negative = |value: Option<Decimal>| value.is_some_and(|v| v < Decimal::ZERO);
    if negative(contract.net_invested_eur) || negative(contract.paid_in_eur) {
        return Err(error(
            SidecarErrorCode::InvalidData,
            "CORUM returned a negative invested amount",
        ));
    }
    let Some(holdings) = &contract.holdings else {
        return Err(error(
            SidecarErrorCode::InvalidData,
            "CORUM returned a life contract without holdings",
        ));
    };
    let mut tickers = HashSet::new();
    let mut computed = Decimal::ZERO;
    for holding in holdings {
        let Some(holding) = holding.as_ref().filter(|h| {
            clean(h.product_code.as_deref()).is_some()
                && h.quantity.is_some()
                && h.value_eur.is_some()
        }) else {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CORUM returned an incomplete life holding",
            ));
        };
        let (quantity, value) = (
            holding.quantity.unwrap_or_default(),
            holding.value_eur.unwrap_or_default(),
        );
        if !tickers.insert(ticker(holding)) {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CORUM returned a duplicate life holding",
            ));
        }
        if quantity < Decimal::ZERO {
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CORUM returned a negative unit count",
            ));
        }
        if holding.euro_fund && (quantity != value || holding.unit_price != Some(Decimal::ONE)) {
            // Valued as quantity x price or as its value, the euro fund must come to one figure.
            return Err(error(
                SidecarErrorCode::InvalidData,
                "CORUM returned a euro fund not in 1 EUR units",
            ));
        }
        computed += value;
    }
    // Fewer raw rows than holdings cannot be true: each holding is at least one row.
    let line_count = contract
        .valuation_line_count
        .filter(|lines| usize::try_from(*lines).is_ok_and(|l| l >= holdings.len()))
        .ok_or_else(|| {
            error(
                SidecarErrorCode::InvalidData,
                "CORUM returned no valid line count for a life contract",
            )
        })?;
    if !lines_close(computed, declared, line_count) {
        return Err(error(
            SidecarErrorCode::PortfolioIncomplete,
            format!(
                "The CORUM life lines add up to {} but the contract declares {}",
                set_scale(computed, 2, HALF_UP),
                set_scale(declared, 2, HALF_UP)
            ),
        ));
    }
    Ok(())
}

/// Writes validated contracts; the outcome per contract code, in portfolio order.
pub async fn write(
    state: &AppState,
    conn: &mut PgConnection,
    member_id: i64,
    contracts: &[Option<LifeContract>],
    synced_at: DateTime<Utc>,
) -> JobResult<Vec<(String, Outcome)>> {
    let mut outcomes = Vec::with_capacity(contracts.len());
    for contract in contracts.iter().flatten() {
        let code = code_of(contract);
        let outcome = write_contract(
            state,
            conn,
            member_id,
            &code,
            contract,
            contracts.len(),
            synced_at,
        )
        .await?;
        outcomes.push((code, outcome));
    }
    Ok(outcomes)
}

async fn write_contract(
    state: &AppState,
    conn: &mut PgConnection,
    member_id: i64,
    code: &str,
    contract: &LifeContract,
    contract_count: usize,
    synced_at: DateTime<Utc>,
) -> JobResult<Outcome> {
    let now = state.clock.now();
    let external_id = format!("{CORUM_LIFE_EXTERNAL_ID_PREFIX}{code}");
    let (mut account, outcome) = match accounts::find_by_external_id_and_member(
        &mut *conn,
        &external_id,
        member_id,
    )
    .await?
    {
        Some(linked) => (linked, Outcome::Updated),
        None => {
            if accounts::exists_soft_deleted_by_external_id(&mut *conn, &external_id, member_id)
                .await?
            {
                tracing::info!("CORUM Life skipped a soft-deleted account (member={member_id})");
                return Ok(Outcome::Ignored);
            }
            let candidates = adoption_candidates(conn, member_id).await?;
            let adoptable = match candidates.as_slice() {
                [only] if contract_count == 1 => holdings::find_by_account(&mut *conn, only.id)
                    .await?
                    .is_empty(),
                _ => false,
            };
            if adoptable {
                (candidates[0].clone(), Outcome::Linked)
            } else if !candidates.is_empty() {
                // Several candidates, holdings typed by hand, or several contracts for one
                // account: the member decides, by renaming or deleting the manual account.
                tracing::info!(
                    "A manual CORUM Life account cannot be adopted, contract not written \
                         (member={member_id}; matches={}; contracts={contract_count})",
                    candidates.len()
                );
                return Ok(Outcome::Ambiguous);
            } else {
                let name = if contract_count == 1 {
                    ACCOUNT_NAME.to_owned()
                } else {
                    format!("{ACCOUNT_NAME} {code}")
                };
                let mut draft = NewAccount::new(
                    member_id,
                    truncate(&name, ACCOUNT_NAME_MAX),
                    AccountType::AssuranceVie,
                );
                draft.color = ACCOUNT_COLOR.to_owned();
                (
                    accounts::insert(&mut *conn, &draft, now).await?,
                    Outcome::Created,
                )
            }
        }
    };

    let total = contract.total_valuation_eur.unwrap_or_default();
    account.external_account_id = Some(external_id);
    account.provider = Some(PROVIDER.to_owned());
    account.currency = "EUR".to_owned();
    account.is_manual = false;
    account.current_balance = total;
    account.cash_balance = Some(Decimal::ZERO);
    account.last_synced_at = Some(synced_at);
    // None from a sidecar that predates it, which restores the line-cost valuation.
    account.provider_invested_eur = contract.net_invested_eur;
    account.provider_paid_in_eur = contract.paid_in_eur;
    let cadence = cadence_of(contract.scheduled_payment.as_ref());
    account.scheduled_payment_eur = cadence
        .and(contract.scheduled_payment.as_ref())
        .and_then(|p| p.amount_eur);
    account.scheduled_payment_cadence = cadence;
    // The member's own date wins: it may be the one their paperwork carries.
    if account.opened_at.is_none() {
        account.opened_at = contract.opened_at;
    }
    let saved = accounts::update(&mut *conn, &account, now).await?;

    let lines = lines(contract);
    let lines_invested = lines
        .iter()
        .try_fold(Decimal::ZERO, |sum, h| h.net_invested_eur.map(|v| sum + v));
    // A switch between funds leaves the lines' costs short of the contract's, and the line
    // switched into shows a gain it never made: line costs are then dropped.
    let line_costs = lines_invested.is_some_and(|invested| {
        contract.net_invested_eur.is_none_or(|net| {
            lines_close(
                invested,
                net,
                contract.valuation_line_count.unwrap_or_default(),
            )
        })
    });
    if !line_costs && lines_invested.is_some() {
        tracing::info!(
            "CORUM Life line costs do not add up to the contract's, written without them (member={member_id})"
        );
    }
    let drafts: Vec<HoldingDraft> = lines
        .iter()
        .map(|h| to_holding(h, line_costs, synced_at))
        .collect();
    holdings::replace_all(&mut *conn, saved.id, &drafts, now).await?;

    let reported = reported_operations(contract);
    let result =
        operations::reconcile(state, conn, member_id, &saved, reported.as_deref(), &lines).await?;
    tracing::info!(
        "CORUM Life operations reconciled (member={member_id}; reported={}; inserted={}; updated={}; \
         deleted={}; unknownTypes={})",
        reported
            .as_ref()
            .map_or_else(|| "none".to_owned(), |r| r.len().to_string()),
        result.inserted,
        result.updated,
        result.deleted,
        result.unknown_types
    );

    let today = state.clock.today();
    match contract.net_invested_eur.or(lines_invested) {
        Some(invested) => {
            snapshots::upsert(&mut *conn, saved.id, total, invested, today, now).await?;
        }
        None => {
            // A line without its paid-in amount leaves the cost basis to the ledger's derivation.
            let ctx = SnapshotContext {
                prices: &*state.ledger.prices,
                today,
                now,
            };
            valuation::upsert_snapshot(&mut *conn, &ctx, &saved, total, today).await?;
        }
    }
    Ok(outcome)
}

fn to_holding(holding: &LifeHolding, line_costs: bool, synced_at: DateTime<Utc>) -> HoldingDraft {
    let quantity = holding.quantity.unwrap_or_default();
    let value = holding.value_eur.unwrap_or_default();
    let net_invested = holding.net_invested_eur.filter(|_| line_costs);
    let average_buy_in = net_invested
        .filter(|_| quantity > Decimal::ZERO)
        .map(|net| divide(net, quantity, UNIT_PRICE_SCALE, HALF_UP));
    let name = clean(holding.label.as_deref()).unwrap_or_else(|| {
        format!(
            "CORUM {}",
            holding.product_code.as_deref().unwrap_or("").trim()
        )
    });
    HoldingDraft {
        ticker: ticker(holding),
        name: Some(truncate(&name, HOLDING_NAME_MAX)),
        quantity,
        average_buy_in,
        current_price: holding.unit_price,
        quote_currency: Some("EUR".to_owned()),
        provider_value_eur: Some(value),
        provider_price_date: holding.valuation_date,
        provider_pnl_eur: net_invested.map(|net| value - net),
        last_synced_at: Some(synced_at),
        provider_wealth_tier: provider_tier(holding.asset_class.as_deref()),
    }
}

/// Only CORUM's \`realEstate\` section is conclusive; every other line keeps the account's tier.
fn provider_tier(asset_class: Option<&str>) -> Option<WealthTier> {
    (asset_class == Some("realEstate")).then_some(WealthTier::RealEstate)
}

/// CORUM's frequency word as a cadence; a plan of zero or an unknown word is left out, never a
/// reason to refuse the contract.
fn cadence_of(payment: Option<&ScheduledPayment>) -> Option<RecurringCadence> {
    let payment = payment?;
    let (amount, frequency) = (payment.amount_eur?, payment.frequency.as_deref()?);
    if amount <= Decimal::ZERO {
        tracing::info!("CORUM Life savings plan of zero, plan left out");
        return None;
    }
    let cadence = match frequency.trim().to_lowercase().as_str() {
        "monthly" => Some(RecurringCadence::Monthly),
        "quarterly" => Some(RecurringCadence::Quarterly),
        "annual" | "yearly" => Some(RecurringCadence::Yearly),
        _ => None,
    };
    if cadence.is_none() {
        tracing::info!("CORUM Life savings plan frequency not recognised, plan left out");
    }
    cadence
}

/// Manual, unlinked assurance-vie accounts named "CORUM Life" up to case and punctuation, or
/// filed under the CORUM provider whatever their name.
async fn adoption_candidates(
    conn: &mut PgConnection,
    member_id: i64,
) -> sqlx::Result<Vec<Account>> {
    let wanted = normalize(ACCOUNT_NAME);
    Ok(accounts::find_all_by_member(&mut *conn, member_id)
        .await?
        .into_iter()
        .filter(|a| {
            a.account_type == AccountType::AssuranceVie
                && a.is_manual
                && a.external_account_id.is_none()
                && (normalize(&a.name) == wanted
                    || a.provider
                        .as_deref()
                        .is_some_and(|p| p.trim().eq_ignore_ascii_case(PROVIDER)))
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn payment(amount: &str, frequency: &str) -> ScheduledPayment {
        ScheduledPayment {
            amount_eur: Some(amount.parse().unwrap()),
            frequency: Some(frequency.to_owned()),
        }
    }

    #[test]
    fn cadence_of_maps_corum_frequencies() {
        assert_eq!(
            cadence_of(Some(&payment("100", " Monthly "))),
            Some(RecurringCadence::Monthly)
        );
        assert_eq!(
            cadence_of(Some(&payment("100", "quarterly"))),
            Some(RecurringCadence::Quarterly)
        );
        assert_eq!(
            cadence_of(Some(&payment("100", "annual"))),
            Some(RecurringCadence::Yearly)
        );
        assert_eq!(
            cadence_of(Some(&payment("100", "yearly"))),
            Some(RecurringCadence::Yearly)
        );
        assert_eq!(cadence_of(Some(&payment("100", "weekly"))), None);
        assert_eq!(cadence_of(Some(&payment("0", "monthly"))), None);
        assert_eq!(cadence_of(None), None);
    }

    #[test]
    fn provider_tier_only_real_estate_is_conclusive() {
        assert_eq!(
            provider_tier(Some("realEstate")),
            Some(WealthTier::RealEstate)
        );
        assert_eq!(provider_tier(Some("bondFund")), None);
        assert_eq!(provider_tier(None), None);
    }
}
