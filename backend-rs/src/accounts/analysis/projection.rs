//! ProjectionService: the investable portfolio projected forward under four return assumptions,
//! fed by the member's recurring investment plans, and where the mix is heading.

use std::str::FromStr;

use crate::db::DbHandle;
use chrono::{Datelike, Months, NaiveDate};
use rust_decimal::Decimal;
use serde::Serialize;

use super::math::{jmax, scale2};
use super::pyramid::WealthPyramidResponse;
use crate::db;
use crate::ledger::access::{self, weigh};
use crate::ledger::decimal::{HALF_UP, divide, round_significant};
use crate::ledger::model::{AccountType, WealthTier};

const MIN_YEARS: i64 = 1;
const MAX_YEARS: i64 = 40;
/// MathContext(20, HALF_UP), the precision every monthly compounding step is rounded to.
const DIGITS: u32 = 20;

/// The headline curve compounds the cushion, equity and crypto; property does not grow at an
/// equity rate and would inflate every scenario.
const INVESTABLE: [WealthTier; 3] = [
    WealthTier::SafetyNet,
    WealthTier::Equity,
    WealthTier::Crypto,
];

/// The four curves, as spreads on risky assets: (key, points added to equity and crypto).
fn assumptions() -> [(&'static str, Decimal); 4] {
    [
        ("PESSIMISTIC", Decimal::new(-25, 1)),
        ("CAUTIOUS", Decimal::new(-10, 1)),
        ("REFERENCE", Decimal::ZERO),
        ("OPTIMISTIC", Decimal::new(25, 1)),
    ]
}

/// What each tier is assumed to earn; property and alternatives are not forecast.
fn tier_rate(tier: WealthTier) -> Decimal {
    match tier {
        WealthTier::SafetyNet => Decimal::new(20, 1),
        WealthTier::Equity | WealthTier::Crypto => Decimal::new(75, 1),
        WealthTier::RealEstate | WealthTier::Alternative => Decimal::ZERO,
    }
}

fn is_risky(tier: WealthTier) -> bool {
    matches!(tier, WealthTier::Equity | WealthTier::Crypto)
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectionResponse {
    #[serde(with = "crate::accounts::jdecimal")]
    base_value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    monthly_inflow_eur: Decimal,
    years: i64,
    scenarios: Vec<Scenario>,
    allocation: Vec<AllocationPoint>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Scenario {
    key: &'static str,
    #[serde(with = "crate::accounts::jdecimal")]
    annual_percent: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    risky_delta: Decimal,
    points: Vec<Point>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Point {
    date: NaiveDate,
    #[serde(with = "crate::accounts::jdecimal")]
    value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    contributed_eur: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AllocationPoint {
    date: NaiveDate,
    tiers: Vec<TierShare>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct TierShare {
    tier: WealthTier,
    #[serde(with = "crate::accounts::jdecimal")]
    value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    percent: Decimal,
    #[serde(with = "crate::accounts::jdecimal::option")]
    target_percent: Option<Decimal>,
}

/// A month as a count of months since year 0, so ranges and offsets are plain integers.
type Month = i32;

fn month_of(date: NaiveDate) -> Month {
    date.year() * 12 + date.month0() as i32
}

fn end_of_month(month: Month) -> NaiveDate {
    let first = NaiveDate::from_ymd_opt(month.div_euclid(12), month.rem_euclid(12) as u32 + 1, 1)
        .expect("valid month");
    first + Months::new(1) - chrono::Days::new(1)
}

/// A recurring plan reduced to how much, where to, at what rate, and when.
#[derive(Debug, Clone, PartialEq)]
struct Plan {
    monthly: Decimal,
    tier: WealthTier,
    annual_percent: Decimal,
    start: Option<Month>,
    end: Option<Month>,
}

impl Plan {
    fn active_in(&self, month: Month) -> bool {
        self.start.is_none_or(|s| month >= s) && self.end.is_none_or(|e| month <= e)
    }
}

/// Money grouped by the rate it earns, one pot per source.
struct Pot {
    tier: WealthTier,
    annual_percent: Decimal,
    amount: Decimal,
}

pub async fn project(
    conn: &mut DbHandle<'_>,
    pyramid: &WealthPyramidResponse,
    member_id: i64,
    requested_years: i64,
    today: NaiveDate,
) -> sqlx::Result<ProjectionResponse> {
    let years = requested_years.clamp(MIN_YEARS, MAX_YEARS);
    let mut mix = [Decimal::ZERO; 5];
    mix[index(WealthTier::SafetyNet)] = pyramid.safety_net.value_eur;
    let mut targets: [Option<Decimal>; 5] = [None; 5];
    for line in &pyramid.tiers {
        mix[index(line.tier)] = line.value_eur;
        targets[index(line.tier)] = Some(line.target_percent);
    }
    let plans = plans_for(conn, member_id).await?;
    let start = month_of(today);
    let base = INVESTABLE
        .iter()
        .fold(Decimal::ZERO, |acc, t| acc + mix[index(*t)]);
    let inflow = plans
        .iter()
        .filter(|p| p.active_in(start))
        .fold(Decimal::ZERO, |acc, p| acc + p.monthly);
    let scenarios = assumptions()
        .into_iter()
        .map(|(key, delta)| scenario(key, delta, &mix, &plans, start, years))
        .collect();
    Ok(ProjectionResponse {
        base_value_eur: scale2(base),
        monthly_inflow_eur: scale2(inflow),
        years,
        scenarios,
        allocation: allocation(&mix, &targets, &plans, start, years),
    })
}

fn index(tier: WealthTier) -> usize {
    WealthTier::ALL
        .iter()
        .position(|t| *t == tier)
        .unwrap_or_default()
}

/// The member's recurring plans, each resolved to the tier its account feeds and weighted by
/// the member's share of that account.
async fn plans_for(conn: &mut DbHandle<'_>, member_id: i64) -> sqlx::Result<Vec<Plan>> {
    let readable = access::readable_accounts(&mut *conn, member_id).await?;
    let shares = access::shares_for(&mut *conn, &readable, member_id).await?;
    type GoalRow = (
        i64,
        String,
        Option<Decimal>,
        Option<Decimal>,
        Option<NaiveDate>,
        Option<NaiveDate>,
    );
    let goals: Vec<GoalRow> = db::query_as(
        "SELECT id, type, monthly_amount, expected_return, start_date, end_date FROM goal \
         WHERE member_id = $1 ORDER BY created_at, id",
    )
    .bind(member_id)
    .fetch_all(&mut *conn)
    .await?;
    let mut plans = Vec::new();
    for (goal_id, kind, monthly_amount, expected_return, start_date, end_date) in goals {
        if kind != "RECURRING_INVESTMENT" {
            continue;
        }
        let Some(monthly_amount) = monthly_amount else {
            continue;
        };
        let first: Option<(i64, String)> = db::query_as(
            "SELECT a.id, a.type::text FROM goal_account ga JOIN account a ON a.id = ga.account_id \
             WHERE ga.goal_id = $1 AND a.deleted_at IS NULL LIMIT 1",
        )
        .bind(goal_id)
        .fetch_optional(&mut *conn)
        .await?;
        let Some((account_id, account_type)) = first else {
            continue;
        };
        let Ok(account_type) = AccountType::from_str(&account_type) else {
            continue;
        };
        let tier = WealthTier::of(account_type);
        let monthly = weigh(
            monthly_amount,
            shares.get(&account_id).copied().unwrap_or_default(),
        );
        if monthly <= Decimal::ZERO {
            continue;
        }
        plans.push(Plan {
            monthly,
            tier,
            annual_percent: expected_return.unwrap_or_else(|| tier_rate(tier)),
            start: start_date.map(month_of),
            end: end_date.map(month_of),
        });
    }
    Ok(plans)
}

fn pots_of(mix: &[Decimal; 5], tiers: &[WealthTier]) -> Vec<Pot> {
    tiers
        .iter()
        .map(|t| Pot {
            tier: *t,
            annual_percent: tier_rate(*t),
            amount: mix[index(*t)],
        })
        .collect()
}

fn total(pots: &[Pot]) -> Decimal {
    pots.iter().fold(Decimal::ZERO, |acc, p| acc + p.amount)
}

fn point(month: Month, value: Decimal, contributed: Decimal) -> Point {
    Point {
        date: end_of_month(month),
        value_eur: scale2(value),
        contributed_eur: scale2(contributed),
    }
}

fn scenario(
    key: &'static str,
    delta: Decimal,
    mix: &[Decimal; 5],
    plans: &[Plan],
    start: Month,
    years: i64,
) -> Scenario {
    let funding: Vec<&Plan> = plans
        .iter()
        .filter(|p| INVESTABLE.contains(&p.tier))
        .collect();
    let mut pots = pots_of(mix, &INVESTABLE);
    let first_plan_pot = pots.len();
    pots.extend(funding.iter().map(|p| Pot {
        tier: p.tier,
        annual_percent: p.annual_percent,
        amount: Decimal::ZERO,
    }));
    let mut contributed = total(&pots);
    // What each plan pays in over the horizon, in first-payment order, weighting its rate.
    let mut paid_in: Vec<(usize, Decimal)> = Vec::new();
    let mut points = vec![point(start, total(&pots), contributed)];
    for i in 1..=years * 12 {
        let month = start + i as Month;
        grow(&mut pots, delta);
        for (n, plan) in funding.iter().enumerate() {
            if !plan.active_in(month) {
                continue;
            }
            pots[first_plan_pot + n].amount += plan.monthly;
            contributed += plan.monthly;
            match paid_in.iter_mut().find(|(k, _)| *k == n) {
                Some((_, sum)) => *sum += plan.monthly,
                None => paid_in.push((n, plan.monthly)),
            }
        }
        if i % 12 == 0 {
            points.push(point(month, total(&pots), contributed));
        }
    }
    Scenario {
        key,
        annual_percent: effective_rate(mix, &funding, &paid_in, delta),
        risky_delta: delta,
        points,
    }
}

/// The blended rate the scenario works out to, weighted by capital in.
fn effective_rate(
    mix: &[Decimal; 5],
    funding: &[&Plan],
    paid_in: &[(usize, Decimal)],
    delta: Decimal,
) -> Decimal {
    let mut weighted = Decimal::ZERO;
    let mut total = Decimal::ZERO;
    for tier in INVESTABLE {
        let amount = mix[index(tier)];
        if amount <= Decimal::ZERO {
            continue;
        }
        weighted += amount * adjust(tier_rate(tier), tier, delta);
        total += amount;
    }
    for (n, amount) in paid_in {
        let plan = funding[*n];
        weighted += *amount * adjust(plan.annual_percent, plan.tier, delta);
        total += *amount;
    }
    if total.is_zero() {
        Decimal::ZERO
    } else {
        divide(weighted, total, 2, HALF_UP)
    }
}

fn allocation(
    mix: &[Decimal; 5],
    targets: &[Option<Decimal>; 5],
    plans: &[Plan],
    start: Month,
    years: i64,
) -> Vec<AllocationPoint> {
    let mut pots = pots_of(mix, WealthTier::ALL);
    let first_plan_pot = pots.len();
    pots.extend(plans.iter().map(|p| Pot {
        tier: p.tier,
        annual_percent: p.annual_percent,
        amount: Decimal::ZERO,
    }));
    let mut points = vec![allocation_point(start, &pots, targets)];
    for i in 1..=years * 12 {
        let month = start + i as Month;
        grow(&mut pots, Decimal::ZERO);
        for (n, plan) in plans.iter().enumerate() {
            if plan.active_in(month) {
                pots[first_plan_pot + n].amount += plan.monthly;
            }
        }
        if i % 12 == 0 {
            points.push(allocation_point(month, &pots, targets));
        }
    }
    points
}

fn allocation_point(month: Month, pots: &[Pot], targets: &[Option<Decimal>; 5]) -> AllocationPoint {
    let mut by_tier = [Decimal::ZERO; 5];
    for pot in pots {
        by_tier[index(pot.tier)] += pot.amount;
    }
    let total = by_tier.iter().fold(Decimal::ZERO, |acc, v| acc + *v);
    let tiers = WealthTier::ALL
        .iter()
        .map(|tier| {
            let amount = by_tier[index(*tier)];
            TierShare {
                tier: *tier,
                value_eur: scale2(amount),
                percent: if total.is_zero() {
                    Decimal::ZERO
                } else {
                    divide(amount * Decimal::ONE_HUNDRED, total, 2, HALF_UP)
                },
                target_percent: targets[index(*tier)],
            }
        })
        .collect();
    AllocationPoint {
        date: end_of_month(month),
        tiers,
    }
}

/// One month of compounding, every pot at its own rate; the spread lands on risky tiers only.
fn grow(pots: &mut [Pot], delta: Decimal) {
    for pot in pots {
        if pot.amount.is_zero() {
            continue;
        }
        let rate = adjust(pot.annual_percent, pot.tier, delta);
        if rate.is_zero() {
            continue;
        }
        pot.amount = round_significant(
            pot.amount * (Decimal::ONE + monthly_rate(rate)),
            DIGITS,
            HALF_UP,
        );
    }
}

fn adjust(rate: Decimal, tier: WealthTier, delta: Decimal) -> Decimal {
    if is_risky(tier) {
        jmax(rate + delta, Decimal::ZERO)
    } else {
        rate
    }
}

/// The geometric monthly rate (1 + r)^(1/12) - 1. Java computes it in double and reads it back
/// through Double.toString; the same shortest decimal is reproduced here, as the rate is a
/// pure ratio and every amount it scales stays a Decimal.
fn monthly_rate(annual_percent: Decimal) -> Decimal {
    let annual: f64 = annual_percent.to_string().parse().unwrap_or_default();
    let rate = (1.0 + annual / 100.0).powf(1.0 / 12.0) - 1.0;
    Decimal::from_str(&format!("{rate}"))
        .or_else(|_| Decimal::from_scientific(&format!("{rate:e}")))
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn monthly_rate_is_javas_double_rendering() {
        // BigDecimal.valueOf(Math.pow(1 + r, 1.0 / 12.0) - 1) in Java 21.
        for (annual, java) in [
            ("7.5", "0.006044919024291717"),
            ("2.0", "0.0016515813019202241"),
            ("5", "0.0040741237836483535"),
            ("0.1", "0.00008329516327321151"),
        ] {
            assert_eq!(
                monthly_rate(annual.parse().unwrap()).to_string(),
                java,
                "{annual}"
            );
        }
    }
}
