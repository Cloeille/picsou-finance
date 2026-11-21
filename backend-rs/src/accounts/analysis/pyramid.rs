//! WealthPyramidService: the member's assets placed on the five tiers of the investment pyramid,
//! scored against their targets. Aggregates like the dashboard: readable visible accounts, one
//! valuation each, the member's share applied exactly once.

use std::collections::{BTreeSet, HashMap};

use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Serialize;
use sqlx::PgConnection;

use super::math::{jmax, jmin, percent_of, rounded_int, scale2};
use super::property::visible_property_debt;
use super::targets::{AllocationProfile, profile_for};
use crate::accounts::classification::overrides_of;
use crate::accounts::holdings::owned_holdings;
use crate::error::AppResult;
use crate::ledger::access::{self, weigh};
use crate::ledger::decimal::{HALF_UP, divide, set_scale};
use crate::ledger::model::{AccountType, WealthTier};
use crate::ledger::prices::{PriceRead, registered_coins};
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::{holdings, valuation};

const SCALE: u32 = 8;

/// The tiers the four target percentages divide; the safety net is measured in euros instead.
pub const INVESTMENT_TIERS: [WealthTier; 4] = [
    WealthTier::RealEstate,
    WealthTier::Equity,
    WealthTier::Crypto,
    WealthTier::Alternative,
];

/// Market-cap leaders, stablecoins excluded, reviewed by hand (as Java's static list).
const TOP_TIER_COINS: [&str; 10] = [
    "BTC", "ETH", "BNB", "SOL", "XRP", "ADA", "DOGE", "TRX", "AVAX", "LINK",
];

fn d(units: i64, scale: u32) -> Decimal {
    Decimal::new(units, scale)
}

fn index(tier: WealthTier) -> usize {
    WealthTier::ALL
        .iter()
        .position(|t| *t == tier)
        .unwrap_or_default()
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WealthPyramidResponse {
    #[serde(with = "crate::accounts::jdecimal")]
    pub total_assets_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub allocatable_eur: Decimal,
    pub safety_net: SafetyNet,
    pub tiers: Vec<TierLine>,
    pub score: Score,
    pub alerts: Vec<Alert>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SafetyNet {
    #[serde(with = "crate::accounts::jdecimal")]
    pub value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub daily_cash_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub target_eur: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub coverage: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal")]
    pub excess_eur: Decimal,
    pub known: bool,
    pub score: Option<i64>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TierLine {
    pub tier: WealthTier,
    #[serde(with = "crate::accounts::jdecimal")]
    pub value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub actual_percent: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub target_percent: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub target_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub gap_percent: Decimal,
    pub accounts: Vec<TierAccount>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TierAccount {
    pub account_id: i64,
    pub name: String,
    pub color: String,
    #[serde(with = "crate::accounts::jdecimal")]
    pub value_eur: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Score {
    pub global: Option<i64>,
    pub allocation: Option<i64>,
    #[serde(with = "crate::accounts::jdecimal")]
    pub misplaced_percent: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub crypto_penalty: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub leverage_bonus: Decimal,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub crypto_top_ten_share: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub loan_to_value: Option<Decimal>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Alert {
    pub code: &'static str,
    pub label: Option<String>,
    #[serde(with = "crate::accounts::jdecimal")]
    pub value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    pub percent: Decimal,
}

/// The sums the account loop produces, by tier ordinal.
#[derive(Default)]
struct Tally {
    by_tier: [Decimal; 5],
    accounts_by_tier: [Vec<TierAccount>; 5],
    total_assets: Decimal,
    daily_cash: Decimal,
    crypto_top_ten: Decimal,
    crypto_detailed: Decimal,
}

pub async fn pyramid(
    conn: &mut PgConnection,
    prices: &dyn PriceRead,
    logos: &dyn crate::accounts::positions::CryptoLogoRead,
    member_id: i64,
    today: NaiveDate,
) -> AppResult<WealthPyramidResponse> {
    // Hidden accounts are out, as on the dashboard.
    let accounts: Vec<_> = access::readable_accounts(&mut *conn, member_id)
        .await?
        .into_iter()
        .filter(|a| !a.hidden)
        .collect();
    let shares = access::shares_for(&mut *conn, &accounts, member_id).await?;
    let profile = profile_for(&mut *conn, member_id).await?;
    let overrides: HashMap<String, WealthTier> = overrides_of(&mut *conn, member_id)
        .await?
        .into_iter()
        .filter_map(|(ticker, c)| c.wealth_tier.map(|t| (ticker, t)))
        .collect();

    let mut tally = Tally::default();
    for account in &accounts {
        // Liabilities never count as assets (property enters net of its debt below), a peer
        // loan earns nothing, and a pocket is already inside its parent wallet.
        if account.account_type.is_liability()
            || account.account_type == AccountType::PeerLoan
            || account.parent_account_id.is_some()
        {
            continue;
        }
        let share = shares.get(&account.id).copied().unwrap_or_default();
        let account_tier = WealthTier::of(account.account_type);
        let current_account = account.account_type == AccountType::Checking;
        let live = valuation::valuation(&mut *conn, prices, account, today)
            .await?
            .live_eur;
        let account_total = weigh(live, share);

        if !holdings::exists_for_readable_account(&mut *conn, account.id, member_id).await? {
            tally.total_assets += account_total;
            if current_account && account_tier == WealthTier::SafetyNet {
                tally.daily_cash += account_total;
            } else {
                tally.by_tier[index(account_tier)] += account_total;
                tally.accounts_by_tier[index(account_tier)].push(TierAccount {
                    account_id: account.id,
                    name: account.name.clone(),
                    color: account.color.clone(),
                    value_eur: account_total,
                });
            }
            continue;
        }

        // The wrapper does not determine the asset: split line by line, then reconcile.
        let lines = owned_holdings(&mut *conn, prices, logos, account.id, member_id, today).await?;
        let line_tickers: BTreeSet<String> = lines
            .iter()
            .filter(|l| !java_is_blank(&l.ticker))
            .map(|l| l.ticker.to_uppercase())
            .collect();
        let coins = if line_tickers.is_empty() {
            BTreeSet::new()
        } else {
            registered_coins(&mut *conn, &line_tickers).await?
        };
        let mut per_tier: [Option<Decimal>; 5] = [None; 5];
        let mut lines_total = Decimal::ZERO;
        for line in &lines {
            let Some(current) = line.current_value_eur else {
                continue;
            };
            let value = weigh(current, share);
            let tier = tier_for(
                &line.ticker,
                line.provider_wealth_tier,
                account_tier,
                &overrides,
                &coins,
            );
            let slot = &mut per_tier[index(tier)];
            *slot = Some(slot.map_or(value, |v| v + value));
            lines_total += value;
            if tier == WealthTier::Crypto {
                tally.crypto_detailed += value;
                if TOP_TIER_COINS.contains(&line.ticker.to_uppercase().as_str()) {
                    tally.crypto_top_ten += value;
                }
            }
        }
        // Cash inside the envelope and unpriced lines stay with the account's own tier.
        let residual = account_total - lines_total;
        if !residual.is_zero() {
            let slot = &mut per_tier[index(account_tier)];
            *slot = Some(slot.map_or(residual, |v| v + residual));
        }
        tally.total_assets += account_total;
        for (i, value) in per_tier.into_iter().enumerate() {
            let Some(value) = value else { continue };
            // An override wins even on a current account; only cushion money is daily cash.
            if current_account && WealthTier::ALL[i] == WealthTier::SafetyNet {
                tally.daily_cash += value;
                continue;
            }
            tally.by_tier[i] += value;
            tally.accounts_by_tier[i].push(TierAccount {
                account_id: account.id,
                name: account.name.clone(),
                color: account.color.clone(),
                value_eur: value,
            });
        }
    }

    // Property net of its mortgage, physical and paper alike, visible accounts only.
    let property = visible_property_debt(&mut *conn, prices, member_id, today).await?;
    let property_debt = property.outstanding + property.paper;
    if property_debt > Decimal::ZERO {
        tally.total_assets -= property_debt;
        tally.by_tier[index(WealthTier::RealEstate)] -= property_debt;
    }
    Ok(assemble(tally, &profile, property.loan_to_value))
}

/// The member's override, then the provider's tier, then the coin registry, then the account.
fn tier_for(
    ticker: &str,
    provider_tier: Option<WealthTier>,
    account_tier: WealthTier,
    overrides: &HashMap<String, WealthTier>,
    coins: &BTreeSet<String>,
) -> WealthTier {
    if java_is_blank(ticker) {
        return account_tier;
    }
    let upper = ticker.to_uppercase();
    if let Some(tier) = overrides.get(&upper) {
        return *tier;
    }
    if let Some(tier) = provider_tier {
        return tier;
    }
    if coins.contains(&upper) {
        return WealthTier::Crypto;
    }
    account_tier
}

fn assemble(
    tally: Tally,
    profile: &AllocationProfile,
    loan_to_value: Option<Decimal>,
) -> WealthPyramidResponse {
    let hundred = Decimal::ONE_HUNDRED;
    let safety_value = tally.by_tier[index(WealthTier::SafetyNet)];
    let expenses = profile
        .monthly_essential_expenses
        .filter(|e| *e > Decimal::ZERO);
    let known = expenses.is_some();
    let safety_target = expenses.map(|e| e * Decimal::from(profile.safety_net_months));
    let coverage = safety_target
        .filter(|t| *t > Decimal::ZERO)
        .map(|t| divide(safety_value, t, SCALE, HALF_UP));
    // Zero when unknown: a target we cannot compute must not be assumed exceeded.
    let excess = safety_target.map_or(Decimal::ZERO, |t| jmax(safety_value - t, Decimal::ZERO));
    let allocatable = jmax(
        tally.total_assets - safety_value - tally.daily_cash,
        Decimal::ZERO,
    );

    let mut tiers = Vec::new();
    let mut divergence = Decimal::ZERO;
    let mut accounts_by_tier = tally.accounts_by_tier;
    for tier in INVESTMENT_TIERS {
        let value = tally.by_tier[index(tier)];
        let actual = percent_of(value, allocatable);
        let target = profile.target_for(tier).unwrap_or_default();
        divergence += (actual - target).abs();
        tiers.push(TierLine {
            tier,
            value_eur: value,
            actual_percent: scale2(actual),
            target_percent: scale2(target),
            target_eur: scale2(divide(allocatable * target, hundred, SCALE, HALF_UP)),
            gap_percent: scale2(actual - target),
            accounts: std::mem::take(&mut accounts_by_tier[index(tier)]),
        });
    }
    let misplaced = divide(divergence, Decimal::TWO, SCALE, HALF_UP);
    // None, not 100, with nothing to allocate: no allocation is not a perfect one.
    let allocation_score =
        (!allocatable.is_zero()).then(|| jmax(hundred - misplaced, Decimal::ZERO));
    let safety_score = if known {
        safety_score(coverage, excess, tally.total_assets)
    } else {
        None
    };
    let crypto_share = (tally.crypto_detailed > Decimal::ZERO)
        .then(|| divide(tally.crypto_top_ten, tally.crypto_detailed, SCALE, HALF_UP));
    let crypto_penalty = crypto_penalty(
        crypto_share,
        percent_of(tally.by_tier[index(WealthTier::Crypto)], allocatable),
    );
    let leverage_bonus = leverage_bonus(loan_to_value);
    let global = combine(
        safety_score,
        allocation_score,
        crypto_penalty,
        leverage_bonus,
    );

    let alerts = alerts(
        &accounts_by_tier[index(WealthTier::SafetyNet)],
        &tiers,
        &tally.by_tier,
        tally.total_assets,
        excess,
        coverage,
        known,
    );
    WealthPyramidResponse {
        total_assets_eur: scale2(tally.total_assets),
        allocatable_eur: scale2(allocatable),
        safety_net: SafetyNet {
            value_eur: scale2(safety_value),
            daily_cash_eur: scale2(tally.daily_cash),
            target_eur: safety_target.map(scale2),
            coverage: coverage.map(|c| set_scale(c, 4, HALF_UP)),
            excess_eur: scale2(excess),
            known,
            score: safety_score,
        },
        tiers,
        score: Score {
            global,
            allocation: allocation_score.map(rounded_int),
            misplaced_percent: scale2(misplaced),
            crypto_penalty: scale2(crypto_penalty),
            leverage_bonus: scale2(leverage_bonus),
            crypto_top_ten_share: crypto_share.map(|s| scale2(s * hundred)),
            loan_to_value: loan_to_value.map(scale2),
        },
        alerts,
    }
}

/// Facts about the portfolio's shape that no choice of targets can silence.
fn alerts(
    cushion_accounts: &[TierAccount],
    tiers: &[TierLine],
    by_tier: &[Decimal; 5],
    total_assets: Decimal,
    excess: Decimal,
    coverage: Option<Decimal>,
    known: bool,
) -> Vec<Alert> {
    let mut alerts = Vec::new();
    if total_assets <= Decimal::ZERO {
        return alerts;
    }
    // Tier order, the cushion first; the first of equal values wins, as Stream.max keeps it.
    let mut biggest: Option<&TierAccount> = None;
    for account in cushion_accounts
        .iter()
        .chain(tiers.iter().flat_map(|t| &t.accounts))
    {
        if biggest.is_none_or(|b| account.value_eur > b.value_eur) {
            biggest = Some(account);
        }
    }
    if let Some(b) = biggest {
        let share = percent_of(b.value_eur, total_assets);
        if share > d(40, 0) {
            alerts.push(Alert {
                code: "SINGLE_ASSET_CONCENTRATION",
                label: Some(b.name.clone()),
                value_eur: scale2(b.value_eur),
                percent: scale2(share),
            });
        }
    }
    for tier in INVESTMENT_TIERS {
        if by_tier[index(tier)].is_zero() {
            alerts.push(Alert {
                code: "EMPTY_TIER",
                label: Some(tier.as_str().to_owned()),
                value_eur: Decimal::ZERO,
                percent: Decimal::ZERO,
            });
        }
    }
    if known
        && coverage.is_some_and(|c| c > d(120, 2))
        && percent_of(excess, total_assets) > Decimal::ONE
    {
        alerts.push(Alert {
            code: "CUSHION_OVERFUNDED",
            label: None,
            value_eur: scale2(excess),
            percent: scale2(percent_of(excess, total_assets)),
        });
    }
    alerts
}

/// 40/60 when both terms exist, the survivor alone otherwise, nothing when neither does.
fn combine(
    safety: Option<i64>,
    allocation: Option<Decimal>,
    crypto_penalty: Decimal,
    leverage_bonus: Decimal,
) -> Option<i64> {
    let base = match (safety, allocation) {
        (Some(s), Some(a)) => d(40, 2) * Decimal::from(s) + d(60, 2) * a,
        (Some(s), None) => Decimal::from(s),
        (None, Some(a)) => a,
        (None, None) => return None,
    };
    Some(rounded_int(jmin(
        jmax(base - crypto_penalty + leverage_bonus, Decimal::ZERO),
        Decimal::ONE_HUNDRED,
    )))
}

/// Short of target scores in proportion; over target floors at 60, by the idle share of assets.
fn safety_score(coverage: Option<Decimal>, excess: Decimal, total_assets: Decimal) -> Option<i64> {
    let coverage = coverage?;
    if coverage <= Decimal::ONE {
        return Some(rounded_int(Decimal::ONE_HUNDRED * coverage));
    }
    if total_assets <= Decimal::ZERO {
        return Some(100);
    }
    let idle = divide(excess, total_assets, SCALE, HALF_UP);
    let over = jmin(divide(idle, d(50, 2), SCALE, HALF_UP), Decimal::ONE);
    Some(rounded_int(Decimal::ONE_HUNDRED - d(40, 0) * over))
}

fn crypto_penalty(top_ten_share: Option<Decimal>, crypto_percent: Decimal) -> Decimal {
    let floor = d(80, 2);
    let Some(share) = top_ten_share.filter(|s| *s < floor) else {
        return Decimal::ZERO;
    };
    let shortfall = divide(floor - share, floor, SCALE, HALF_UP);
    let weight = divide(crypto_percent, Decimal::ONE_HUNDRED, SCALE, HALF_UP);
    Decimal::TEN * weight * shortfall
}

/// Rises to 5 at 60 % LTV, holds to 85 %, decays to zero at 100 %.
fn leverage_bonus(loan_to_value: Option<Decimal>) -> Decimal {
    let Some(ltv) = loan_to_value.filter(|l| *l > Decimal::ZERO) else {
        return Decimal::ZERO;
    };
    let ltv = divide(ltv, Decimal::ONE_HUNDRED, SCALE, HALF_UP);
    let ramp = jmin(divide(ltv, d(60, 2), SCALE, HALF_UP), Decimal::ONE);
    let decay_start = d(85, 2);
    let decay = jmax(
        Decimal::ONE
            - divide(
                jmax(ltv - decay_start, Decimal::ZERO),
                Decimal::ONE - decay_start,
                SCALE,
                HALF_UP,
            ),
        Decimal::ZERO,
    );
    d(5, 0) * ramp * decay
}
