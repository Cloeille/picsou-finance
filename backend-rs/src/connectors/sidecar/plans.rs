//! Employee savings plans as Amundi and CMES sidecars report them: each plan's fund lines are
//! validated, keyed, merged and reconciled with the plan total before anything is written.

use std::collections::HashSet;
use std::sync::LazyLock;

use chrono::{DateTime, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use serde::Deserialize;
use sha2::{Digest, Sha256};

use super::error::SyncError;
use super::template::error;
use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::model::HoldingDraft;
use crate::ledger::sidecar::SidecarErrorCode;
use crate::ledger::sync_values::{clean, limit, money_close, sum_complete, weighted_average};

const MAX_TICKER_LENGTH: usize = 30;
const LABEL_HASH_LENGTH: usize = 8;

static ISIN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Z]{2}[A-Z0-9]{9}[0-9]$").expect("regex"));
static NON_ALNUM: LazyLock<Regex> = LazyLock::new(|| Regex::new("[^A-Z0-9]+").expect("regex"));

/// What differs between the two providers' readings of a plan.
pub struct PlanRules {
    /// The provider's name in error messages ("Amundi", "CMES").
    pub label: &'static str,
    /// "savings plan" or "plan", in the no-plan and incomplete-plan messages.
    pub plan_word: &'static str,
    pub external_id_prefix: &'static str,
    /// The account name when the plan has neither a name nor a kind.
    pub default_name: &'static str,
    /// Prefix a fund code gets before it keys a holding (CMES), if any.
    pub fund_code_prefix: Option<&'static str>,
    /// Whether a merged line left with neither units nor value is dropped (Amundi).
    pub drops_empty_merged_lines: bool,
    /// Whether an empty plan list is a valid answer (Natixis) rather than a short scrape.
    pub allows_no_plan: bool,
    /// Whether a label-keyed fund gets a hash of its label, so two truncated labels stay apart.
    pub hashes_label_fallback: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Position {
    isin: Option<String>,
    fund_code: Option<String>,
    label: Option<String>,
    quantity: Option<Decimal>,
    unit_value: Option<Decimal>,
    value_eur: Option<Decimal>,
    pnl_eur: Option<Decimal>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanData {
    external_id: Option<String>,
    legacy_external_ids: Option<Vec<String>>,
    name: Option<String>,
    plan_kind: Option<String>,
    employer: Option<String>,
    balance_eur: Option<Decimal>,
    positions: Option<Vec<Option<Position>>>,
    #[serde(default)]
    snapshot_complete: bool,
}

pub struct PreparedPlan {
    pub external_id: String,
    pub legacy_external_ids: Vec<String>,
    pub name: String,
    pub balance_eur: Decimal,
    pub invested_amount_eur: Decimal,
    positions: Vec<PreparedPosition>,
}

#[derive(Debug, Clone)]
struct PreparedPosition {
    ticker: String,
    name: String,
    quantity: Decimal,
    average_buy_in_eur: Option<Decimal>,
    unit_value: Option<Decimal>,
    value_eur: Decimal,
    pnl_eur: Option<Decimal>,
}

impl PreparedPlan {
    /// The plan's lines as holdings, priced in EUR when the provider gave a unit value.
    pub fn holdings(&self, synced_at: DateTime<Utc>) -> Vec<HoldingDraft> {
        self.positions
            .iter()
            .map(|p| HoldingDraft {
                ticker: p.ticker.clone(),
                name: Some(p.name.clone()),
                quantity: p.quantity,
                average_buy_in: p.average_buy_in_eur,
                current_price: p.unit_value,
                quote_currency: p.unit_value.map(|_| "EUR".to_owned()),
                provider_value_eur: Some(p.value_eur),
                provider_pnl_eur: p.pnl_eur,
                last_synced_at: Some(synced_at),
                ..HoldingDraft::default()
            })
            .collect()
    }
}

impl PlanRules {
    fn invalid(&self, what: &str) -> SyncError {
        error(
            SidecarErrorCode::InvalidData,
            format!("{} returned {what}", self.label),
        )
    }

    fn incomplete(&self) -> SyncError {
        error(
            SidecarErrorCode::PortfolioIncomplete,
            format!("{} returned an incomplete {}", self.label, self.plan_word),
        )
    }

    pub fn no_plan_message(&self) -> String {
        format!("{} returned no complete {}", self.label, self.plan_word)
    }

    pub fn prepare(&self, fetched: Vec<Option<PlanData>>) -> Result<Vec<PreparedPlan>, SyncError> {
        if fetched.is_empty() {
            return Err(error(
                SidecarErrorCode::PortfolioIncomplete,
                self.no_plan_message(),
            ));
        }
        let mut external_ids = HashSet::new();
        let mut prepared = Vec::with_capacity(fetched.len());
        for plan in fetched {
            let Some(plan) = plan.filter(|p| p.snapshot_complete) else {
                return Err(self.incomplete());
            };
            let external_id = self.stable_external_id(plan.external_id.as_deref())?;
            if !external_ids.insert(external_id.clone()) {
                return Err(self.invalid("duplicate plans"));
            }
            let (Some(balance_eur), Some(raw)) = (plan.balance_eur, plan.positions.clone()) else {
                return Err(self.invalid("incomplete plan values"));
            };
            let positions = self.prepare_positions(raw)?;
            let value: Decimal = positions.iter().map(|p| p.value_eur).sum();
            if !money_close(value, balance_eur) {
                return Err(self.incomplete());
            }
            let invested_amount_eur = positions
                .iter()
                .try_fold(Decimal::ZERO, |sum, p| {
                    p.pnl_eur.map(|pnl| sum + p.value_eur - pnl)
                })
                .unwrap_or(balance_eur);
            let mut legacy_external_ids = Vec::new();
            for legacy in plan.legacy_external_ids.iter().flatten() {
                let id = self.stable_external_id(Some(legacy))?;
                if !legacy_external_ids.contains(&id) {
                    legacy_external_ids.push(id);
                }
            }
            prepared.push(PreparedPlan {
                external_id,
                legacy_external_ids,
                name: self.account_name(&plan),
                balance_eur,
                invested_amount_eur,
                positions,
            });
        }
        Ok(prepared)
    }

    fn prepare_positions(
        &self,
        raw: Vec<Option<Position>>,
    ) -> Result<Vec<PreparedPosition>, SyncError> {
        let mut positions: Vec<PreparedPosition> = Vec::new();
        for position in raw {
            let Some((position, quantity, value_eur)) =
                position.and_then(|p| Some((p.clone(), p.quantity?, p.value_eur?)))
            else {
                return Err(self.invalid("an incomplete position"));
            };
            if quantity.is_zero() && value_eur.is_zero() {
                continue;
            }
            let ticker = self.resolve_ticker(&position)?;
            let average_buy_in_eur = match position.pnl_eur {
                Some(pnl) if !quantity.is_zero() => {
                    Some(divide(value_eur - pnl, quantity, 8, HALF_UP))
                }
                _ => None,
            };
            let resolved = PreparedPosition {
                name: limit(position.label.as_deref(), 100, &ticker),
                ticker: ticker.clone(),
                quantity,
                average_buy_in_eur,
                unit_value: position.unit_value,
                value_eur,
                pnl_eur: position.pnl_eur,
            };
            match positions.iter_mut().find(|p| p.ticker == ticker) {
                Some(left) => {
                    if left.name != resolved.name {
                        return Err(self.invalid("colliding fund identifiers"));
                    }
                    let total = left.quantity + resolved.quantity;
                    *left = PreparedPosition {
                        ticker: left.ticker.clone(),
                        name: left.name.clone(),
                        quantity: total,
                        average_buy_in_eur: weighted_average(
                            left.average_buy_in_eur,
                            left.quantity,
                            resolved.average_buy_in_eur,
                            resolved.quantity,
                            total,
                        ),
                        unit_value: left.unit_value.or(resolved.unit_value),
                        value_eur: left.value_eur + resolved.value_eur,
                        pnl_eur: sum_complete(left.pnl_eur, resolved.pnl_eur),
                    };
                }
                None => positions.push(resolved),
            }
        }
        if self.drops_empty_merged_lines {
            positions.retain(|p| !p.quantity.is_zero() || !p.value_eur.is_zero());
        }
        Ok(positions)
    }

    fn account_name(&self, plan: &PlanData) -> String {
        let base = clean(plan.name.as_deref())
            .or_else(|| clean(plan.plan_kind.as_deref()))
            .unwrap_or_else(|| self.default_name.to_owned());
        let composed = match clean(plan.employer.as_deref()) {
            Some(employer) if !base.to_lowercase().contains(&employer.to_lowercase()) => {
                format!("{base} — {employer}")
            }
            _ => base,
        };
        limit(Some(&composed), 100, self.default_name)
    }

    fn stable_external_id(&self, raw: Option<&str>) -> Result<String, SyncError> {
        let cleaned = clean(raw).ok_or_else(|| self.invalid("an invalid plan identifier"))?;
        let external_id = if cleaned.starts_with(self.external_id_prefix) {
            cleaned
        } else {
            format!("{}{cleaned}", self.external_id_prefix)
        };
        if external_id.encode_utf16().count() > 100 {
            return Err(self.invalid("an invalid plan identifier"));
        }
        Ok(external_id)
    }

    fn resolve_ticker(&self, position: &Position) -> Result<String, SyncError> {
        if let Some(isin) = clean(position.isin.as_deref()).map(|i| i.to_uppercase())
            && ISIN.is_match(&isin)
        {
            return Ok(isin);
        }
        if let Some(code) = slug(position.fund_code.as_deref()) {
            return Ok(match self.fund_code_prefix {
                Some(prefix) => slug(Some(&format!("{prefix}{code}"))).unwrap_or(code),
                None => code,
            });
        }
        let label = slug(position.label.as_deref())
            .ok_or_else(|| self.invalid("a fund without any identifier"))?;
        if !self.hashes_label_fallback {
            return Ok(label);
        }
        let digest = Sha256::digest(
            clean(position.label.as_deref())
                .unwrap_or_default()
                .as_bytes(),
        );
        let hash = hex::encode_upper(digest)[..LABEL_HASH_LENGTH].to_owned();
        let room = MAX_TICKER_LENGTH - LABEL_HASH_LENGTH - 1;
        let prefix = if label.len() <= room {
            label
        } else {
            label[..room].trim_end_matches('-').to_owned()
        };
        Ok(format!("{prefix}-{hash}"))
    }

    /// The ticker an operation's fund resolves to, as its position would (no ISIN on operations).
    pub fn operation_ticker(
        &self,
        fund_code: Option<&str>,
        fund_label: Option<&str>,
    ) -> Result<String, SyncError> {
        self.resolve_ticker(&Position {
            isin: None,
            fund_code: fund_code.map(str::to_owned),
            label: fund_label.map(str::to_owned),
            quantity: None,
            unit_value: None,
            value_eur: None,
            pnl_eur: None,
        })
    }
}

fn slug(value: Option<&str>) -> Option<String> {
    let upper = clean(value)?.to_uppercase();
    let replaced = NON_ALNUM.replace_all(&upper, "-");
    let slug = replaced.trim_matches('-');
    if slug.is_empty() {
        return None;
    }
    Some(slug.chars().take(MAX_TICKER_LENGTH).collect())
}
