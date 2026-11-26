//! PortfolioDiversificationService: the equity sleeve spread across sectors and regions, from
//! persisted profiles only. Whatever has no profile is listed as unclassified, not renormalised.

use std::collections::{HashMap, HashSet};

use crate::db::DbHandle;
use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Serialize;

use super::java_hash::hash_order;
use super::math::{jmin, percent_of, rounded_int, scale2};
use super::profiles::{self, ProfileSlice};
use crate::accounts::classification::{HoldingClassification, overrides_of};
use crate::accounts::holdings::holdings_of;
use crate::accounts::tracked_fund;
use crate::db::AsDbHandle;
use crate::error::AppResult;
use crate::ledger::access::{self, weigh};
use crate::ledger::decimal::{HALF_UP, divide, set_scale};
use crate::ledger::model::WealthTier;
use crate::ledger::prices::PriceRead;
use crate::ledger::{holdings, valuation};

const SCALE: u32 = 8;
const MAX_CONTRIBUTORS_PER_SLICE: usize = 12;
const SECTOR_TARGET: i64 = 6;
const COUNTRY_TARGET: i64 = 3;

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiversificationResponse {
    #[serde(with = "crate::accounts::jdecimal")]
    total_value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    classified_value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    unclassified_value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    coverage_percent: Decimal,
    unclassified: Vec<UnclassifiedLine>,
    sectors: Breakdown,
    countries: Breakdown,
    securities: Vec<SecurityInfo>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct UnclassifiedLine {
    ticker: String,
    isin: Option<String>,
    name: Option<String>,
    account_id: Option<i64>,
    account_name: Option<String>,
    #[serde(with = "crate::accounts::jdecimal")]
    value_eur: Decimal,
    sector_missing: bool,
    country_missing: bool,
    profile_looked: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum UnclassifiedReason {
    /// No persisted security profile, or the row has never been looked up yet.
    NoProfile,
    /// The last provider lookup failed.
    LookupFailed,
    /// The profile was looked up but carries no data for this axis.
    NoData,
    /// The profile's look-through slices cover less than 100 % of the holding.
    IncompleteComposition,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AxisUnclassifiedItem {
    ticker: String,
    isin: Option<String>,
    name: Option<String>,
    account_id: Option<i64>,
    account_name: Option<String>,
    #[serde(with = "crate::accounts::jdecimal")]
    total_value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    unclassified_value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    unclassified_percent: Decimal,
    reason: UnclassifiedReason,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Breakdown {
    score: i64,
    #[serde(with = "crate::accounts::jdecimal")]
    effective_count: Decimal,
    target_count: i64,
    basis: &'static str,
    #[serde(with = "crate::accounts::jdecimal")]
    classified_value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    unclassified_value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    coverage_percent: Decimal,
    slices: Vec<Slice>,
    unclassified: Vec<AxisUnclassifiedItem>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Slice {
    label: String,
    #[serde(with = "crate::accounts::jdecimal")]
    percent: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    value_eur: Decimal,
    contributors: Vec<Contributor>,
    contributor_count: usize,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Contributor {
    ticker: Option<String>,
    #[serde(with = "crate::accounts::jdecimal")]
    value_eur: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    share_percent: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SecurityInfo {
    ticker: String,
    name: Option<String>,
    account_id: Option<i64>,
    #[serde(with = "crate::accounts::jdecimal")]
    value_eur: Decimal,
}

/// A label's running total and the holdings behind it.
type LabelTotal = (String, Decimal, Vec<(String, Decimal)>);

/// Running totals of one axis, keeping the holdings behind each label, in insertion order
/// (Java's HashMap order is applied when they are read out).
#[derive(Default)]
struct AxisTotals {
    labels: Vec<LabelTotal>,
}

impl AxisTotals {
    fn add(&mut self, label: &str, ticker: &str, amount: Decimal) {
        let Some(entry) = self.labels.iter_mut().find(|(l, _, _)| l == label) else {
            self.labels
                .push((label.to_owned(), amount, vec![(ticker.to_owned(), amount)]));
            return;
        };
        entry.1 += amount;
        match entry.2.iter_mut().find(|(t, _)| t == ticker) {
            Some((_, v)) => *v += amount,
            None => entry.2.push((ticker.to_owned(), amount)),
        }
    }
}

/// Where a ticker was seen, so an unclassified line can be named, attributed, and reached.
struct Origin {
    account_id: i64,
    account_names: Vec<String>,
    name: Option<String>,
    isin: Option<String>,
}

impl Origin {
    fn account_name(&self) -> Option<String> {
        if self.account_names.is_empty() {
            None
        } else {
            Some(self.account_names.join(", "))
        }
    }
}

fn record_origin(
    origins: &mut HashMap<String, Origin>,
    ticker: String,
    account_id: i64,
    account_name: &str,
    name: Option<String>,
    isin: Option<String>,
) {
    match origins.get_mut(&ticker) {
        Some(existing) => {
            if !account_name.is_empty() && !existing.account_names.iter().any(|n| n == account_name)
            {
                existing.account_names.push(account_name.to_owned());
            }
            if existing.name.is_none() {
                existing.name = name;
            }
            if existing.isin.is_none() {
                existing.isin = isin;
            }
        }
        None => {
            let account_names = if account_name.is_empty() {
                Vec::new()
            } else {
                vec![account_name.to_owned()]
            };
            origins.insert(
                ticker,
                Origin {
                    account_id,
                    account_names,
                    name,
                    isin,
                },
            );
        }
    }
}

fn push_axis_unclassified(
    into: &mut Vec<AxisUnclassifiedItem>,
    ticker: &str,
    origin: Option<&Origin>,
    profile_isin: Option<&str>,
    total_value: Decimal,
    placed: Decimal,
    reason: UnclassifiedReason,
) {
    let remainder = (total_value - placed).max(Decimal::ZERO);
    let scaled_remainder = scale2(remainder);
    if scaled_remainder <= Decimal::ZERO {
        return;
    }
    let unclassified_percent = if placed.is_zero() {
        scale2(Decimal::ONE_HUNDRED)
    } else {
        scale2(percent_of(remainder, total_value))
    };
    into.push(AxisUnclassifiedItem {
        ticker: ticker.to_owned(),
        isin: origin
            .and_then(|o| o.isin.clone())
            .or_else(|| profile_isin.map(str::to_owned)),
        name: origin.and_then(|o| o.name.clone()),
        account_id: origin.map(|o| o.account_id),
        account_name: origin.and_then(Origin::account_name),
        total_value_eur: scale2(total_value),
        unclassified_value_eur: scaled_remainder,
        unclassified_percent,
        reason,
    });
}

/// The member's override first, then the provider's tier; untiered lines count as equity.
fn is_equity_line(over: Option<&HoldingClassification>, provider: Option<WealthTier>) -> bool {
    let tier = over.and_then(|o| o.wealth_tier).or(provider);
    tier.is_none_or(|t| t == WealthTier::Equity)
}

pub async fn diversification(
    conn: &mut DbHandle<'_>,
    prices: &dyn PriceRead,
    logos: &dyn crate::accounts::positions::CryptoLogoRead,
    member_id: i64,
    today: NaiveDate,
) -> AppResult<DiversificationResponse> {
    let accounts = access::readable_accounts(&mut *conn, member_id).await?;
    let shares = access::shares_for(&mut *conn, &accounts, member_id).await?;
    let overrides = overrides_of(&mut *conn, member_id).await?;

    let mut value_by_ticker: Vec<(String, Decimal)> = Vec::new();
    let mut origins: HashMap<String, Origin> = HashMap::new();
    let mut total = Decimal::ZERO;
    for account in &accounts {
        if WealthTier::of(account.account_type) != WealthTier::Equity
            || !holdings::exists_for_readable_account(&mut *conn, account.id, member_id).await?
        {
            continue;
        }
        let share = shares.get(&account.id).copied().unwrap_or_default();
        for line in holdings_of(&mut *conn, prices, logos, account, today).await? {
            let Some(current) = line.current_value_eur else {
                continue;
            };
            let value = weigh(current, share);
            if value.is_zero() {
                continue;
            }
            let ticker = line.ticker.to_uppercase();
            if !is_equity_line(overrides.get(&ticker), line.provider_wealth_tier) {
                continue;
            }
            match value_by_ticker.iter_mut().find(|(t, _)| *t == ticker) {
                Some((_, v)) => *v += value,
                None => value_by_ticker.push((ticker.clone(), value)),
            }
            record_origin(
                &mut origins,
                ticker,
                account.id,
                &account.name,
                line.name.clone(),
                line.isin.clone(),
            );
            total += value;
        }
    }
    // An account holding one declared fund, without lines, is one line of that fund, after the
    // held lines so that a held line of the same ticker names it.
    let tracked = tracked_fund::keys(&mut *conn, &accounts.iter().collect::<Vec<_>>()).await?;
    for account in accounts.iter().filter(|a| tracked.contains_key(&a.id)) {
        let ticker = tracked[&account.id].clone();
        if !is_equity_line(overrides.get(&ticker), None) {
            continue;
        }
        let share = shares.get(&account.id).copied().unwrap_or_default();
        let live = valuation::valuation(&mut *conn, prices, account, today)
            .await?
            .live_eur;
        let value = weigh(live, share);
        // An overdrawn account holds no fund: it adds no negative line.
        if value <= Decimal::ZERO {
            continue;
        }
        match value_by_ticker.iter_mut().find(|(t, _)| *t == ticker) {
            Some((_, v)) => *v += value,
            None => value_by_ticker.push((ticker.clone(), value)),
        }
        record_origin(
            &mut origins,
            ticker,
            account.id,
            &account.name,
            None,
            account.tracked_isin.clone(),
        );
        total += value;
    }

    let loaded = profiles::load(
        &mut conn.as_handle(),
        value_by_ticker.iter().map(|(t, _)| t.as_str()),
    )
    .await?;
    let mut sector_totals = AxisTotals::default();
    let mut country_totals = AxisTotals::default();
    let mut unclassified: Vec<UnclassifiedLine> = Vec::new();
    let mut sector_unclassified: Vec<AxisUnclassifiedItem> = Vec::new();
    let mut country_unclassified: Vec<AxisUnclassifiedItem> = Vec::new();
    let mut sector_classified = Decimal::ZERO;
    let mut country_classified = Decimal::ZERO;
    let mut sector_mixed = false;
    let mut country_mixed = false;
    let record = |into: &mut Vec<UnclassifiedLine>,
                  ticker: &str,
                  profile_isin: Option<&str>,
                  value,
                  sector,
                  country,
                  looked| {
        let origin = origins.get(ticker);
        into.push(UnclassifiedLine {
            ticker: ticker.to_owned(),
            isin: origin
                .and_then(|o| o.isin.clone())
                .or_else(|| profile_isin.map(str::to_owned)),
            name: origin.and_then(|o| o.name.clone()),
            account_id: origin.map(|o| o.account_id),
            account_name: origin.and_then(Origin::account_name),
            value_eur: scale2(value),
            sector_missing: sector,
            country_missing: country,
            profile_looked: looked,
        });
    };

    for (ticker, value) in &value_by_ticker {
        let value = *value;
        let over = overrides.get(ticker);
        let raw_profile = loaded.get(ticker);
        let profile_isin = raw_profile.and_then(|p| p.isin.as_deref());
        let origin = origins.get(ticker);
        let mut placed_sector = false;
        let mut placed_country = false;
        // The member's verdict beats anything inferred, field by field.
        if let Some(sector) = over.and_then(|o| o.sector_key.as_deref()) {
            sector_totals.add(sector, ticker, value);
            sector_classified += value;
            sector_mixed = true;
            placed_sector = true;
        }
        if let Some(country) = over.and_then(|o| o.country_key.as_deref()) {
            country_totals.add(country, ticker, value);
            country_classified += value;
            country_mixed = true;
            placed_country = true;
        }
        let Some(profile) = raw_profile.filter(|p| p.was_looked_up()) else {
            if !placed_sector {
                push_axis_unclassified(
                    &mut sector_unclassified,
                    ticker,
                    origin,
                    profile_isin,
                    value,
                    Decimal::ZERO,
                    UnclassifiedReason::NoProfile,
                );
            }
            if !placed_country {
                push_axis_unclassified(
                    &mut country_unclassified,
                    ticker,
                    origin,
                    profile_isin,
                    value,
                    Decimal::ZERO,
                    UnclassifiedReason::NoProfile,
                );
            }
            if !placed_sector || !placed_country {
                record(
                    &mut unclassified,
                    ticker,
                    profile_isin,
                    value,
                    !placed_sector,
                    !placed_country,
                    false,
                );
            }
            continue;
        };
        let missing_reason = if profile.status == "FAILED" {
            UnclassifiedReason::LookupFailed
        } else {
            UnclassifiedReason::NoData
        };
        if !placed_sector {
            let placed = spread(&profile.slices, "SECTOR", ticker, value, &mut sector_totals);
            if placed > Decimal::ZERO {
                sector_classified += placed;
                placed_sector = true;
                push_axis_unclassified(
                    &mut sector_unclassified,
                    ticker,
                    origin,
                    profile_isin,
                    value,
                    placed,
                    UnclassifiedReason::IncompleteComposition,
                );
            } else if let Some(sector) = &profile.sector_key {
                sector_totals.add(sector, ticker, value);
                sector_classified += value;
                sector_mixed = true;
                placed_sector = true;
            } else {
                push_axis_unclassified(
                    &mut sector_unclassified,
                    ticker,
                    origin,
                    profile_isin,
                    value,
                    Decimal::ZERO,
                    missing_reason,
                );
            }
        }
        if !placed_country {
            let placed = spread(
                &profile.slices,
                "COUNTRY",
                ticker,
                value,
                &mut country_totals,
            );
            if placed > Decimal::ZERO {
                country_classified += placed;
                placed_country = true;
                push_axis_unclassified(
                    &mut country_unclassified,
                    ticker,
                    origin,
                    profile_isin,
                    value,
                    placed,
                    UnclassifiedReason::IncompleteComposition,
                );
            } else if let Some(country) = &profile.country_key {
                country_totals.add(country, ticker, value);
                country_classified += value;
                country_mixed = true;
                placed_country = true;
            } else {
                push_axis_unclassified(
                    &mut country_unclassified,
                    ticker,
                    origin,
                    profile_isin,
                    value,
                    Decimal::ZERO,
                    missing_reason,
                );
            }
        }
        if !placed_sector || !placed_country {
            record(
                &mut unclassified,
                ticker,
                profile_isin,
                value,
                !placed_sector,
                !placed_country,
                true,
            );
        }
    }

    let classified = if sector_classified >= country_classified {
        sector_classified
    } else {
        country_classified
    };
    let remainder = total - classified;
    let unclassified_value = if remainder >= Decimal::ZERO {
        remainder
    } else {
        Decimal::ZERO
    };
    sector_unclassified.sort_by(|a, b| {
        b.unclassified_value_eur
            .cmp(&a.unclassified_value_eur)
            .then_with(|| a.ticker.cmp(&b.ticker))
    });
    country_unclassified.sort_by(|a, b| {
        b.unclassified_value_eur
            .cmp(&a.unclassified_value_eur)
            .then_with(|| a.ticker.cmp(&b.ticker))
    });
    let sectors = breakdown(
        sector_totals,
        sector_classified,
        total,
        SECTOR_TARGET,
        sector_mixed,
        sector_unclassified,
    );
    let countries = breakdown(
        country_totals,
        country_classified,
        total,
        COUNTRY_TARGET,
        country_mixed,
        country_unclassified,
    );
    // Biggest first: the line worth correcting by hand is the one moving the most money.
    unclassified.sort_by_key(|line| std::cmp::Reverse(line.value_eur));
    let securities = securities_in(&sectors, &countries, &origins, &value_by_ticker);
    Ok(DiversificationResponse {
        total_value_eur: scale2(total),
        classified_value_eur: scale2(classified),
        unclassified_value_eur: scale2(unclassified_value),
        coverage_percent: scale2(percent_of(classified, total)),
        unclassified,
        sectors,
        countries,
        securities,
    })
}

/// Places a holding across its published slices literally; the undisclosed rest stays out.
fn spread(
    slices: &[ProfileSlice],
    kind: &str,
    ticker: &str,
    value: Decimal,
    target: &mut AxisTotals,
) -> Decimal {
    let mut placed = Decimal::ZERO;
    for slice in slices.iter().filter(|s| s.kind == kind) {
        let part = divide(value * slice.percent, Decimal::ONE_HUNDRED, SCALE, HALF_UP);
        target.add(&slice.label, ticker, part);
        placed += part;
    }
    placed
}

fn breakdown(
    totals: AxisTotals,
    classified: Decimal,
    total: Decimal,
    target: i64,
    mixed: bool,
    unclassified: Vec<AxisUnclassifiedItem>,
) -> Breakdown {
    let basis = if mixed { "MIXED" } else { "EXPOSURE" };
    let unclassified_value = (total - classified).max(Decimal::ZERO);
    if classified <= Decimal::ZERO {
        return Breakdown {
            score: 0,
            effective_count: Decimal::ZERO,
            target_count: target,
            basis,
            classified_value_eur: Decimal::ZERO,
            unclassified_value_eur: scale2(total.max(Decimal::ZERO)),
            coverage_percent: Decimal::ZERO,
            slices: Vec::new(),
            unclassified,
        };
    }
    // Inverse Herfindahl: the effective number of buckets.
    let mut herfindahl = Decimal::ZERO;
    for (_, value, _) in &totals.labels {
        let weight = divide(*value, classified, SCALE, HALF_UP);
        herfindahl += weight * weight;
    }
    let effective = if herfindahl.is_zero() {
        Decimal::ZERO
    } else {
        divide(Decimal::ONE, herfindahl, SCALE, HALF_UP)
    };
    let score = rounded_int(jmin(
        divide(effective, Decimal::from(target), SCALE, HALF_UP) * Decimal::ONE_HUNDRED,
        Decimal::ONE_HUNDRED,
    ));
    let entries = hash_order(
        totals
            .labels
            .into_iter()
            .map(|(label, value, tickers)| (label, (value, tickers)))
            .collect(),
    );
    let mut slices: Vec<Slice> = entries
        .into_iter()
        .map(|(label, (value, tickers))| Slice {
            label,
            percent: scale2(percent_of(value, classified)),
            value_eur: scale2(value),
            contributor_count: tickers.len(),
            contributors: contributors_of(tickers, value),
        })
        .collect();
    slices.sort_by_key(|slice| std::cmp::Reverse(slice.percent));
    Breakdown {
        score,
        effective_count: set_scale(effective, 2, HALF_UP),
        target_count: target,
        basis,
        classified_value_eur: scale2(classified),
        unclassified_value_eur: scale2(unclassified_value),
        coverage_percent: scale2(percent_of(classified, total)),
        slices,
        unclassified,
    }
}

/// The holdings behind one slice, largest first, the small tail folded into one line.
fn contributors_of(by_ticker: Vec<(String, Decimal)>, slice_total: Decimal) -> Vec<Contributor> {
    if by_ticker.is_empty() || slice_total <= Decimal::ZERO {
        return Vec::new();
    }
    let mut ranked = hash_order(by_ticker);
    ranked.sort_by_key(|entry| std::cmp::Reverse(entry.1));
    let min_share = Decimal::new(5, 1);
    let mut out = Vec::new();
    let mut folded = Decimal::ZERO;
    for (ticker, value) in ranked {
        let share = percent_of(value, slice_total);
        if out.len() >= MAX_CONTRIBUTORS_PER_SLICE || share < min_share {
            folded += value;
            continue;
        }
        out.push(Contributor {
            ticker: Some(ticker),
            value_eur: scale2(value),
            share_percent: scale2(share),
        });
    }
    if folded > Decimal::ZERO {
        out.push(Contributor {
            ticker: None,
            value_eur: scale2(folded),
            share_percent: scale2(percent_of(folded, slice_total)),
        });
    }
    out
}

/// Every security appearing as a contributor, once, in slice order.
fn securities_in(
    sectors: &Breakdown,
    countries: &Breakdown,
    origins: &HashMap<String, Origin>,
    value_by_ticker: &[(String, Decimal)],
) -> Vec<SecurityInfo> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for breakdown in [sectors, countries] {
        for slice in &breakdown.slices {
            for ticker in slice.contributors.iter().filter_map(|c| c.ticker.as_ref()) {
                if !seen.insert(ticker.clone()) {
                    continue;
                }
                let origin = origins.get(ticker);
                out.push(SecurityInfo {
                    ticker: ticker.clone(),
                    name: origin.and_then(|o| o.name.clone()),
                    account_id: origin.map(|o| o.account_id),
                    value_eur: scale2(
                        value_by_ticker
                            .iter()
                            .find(|(t, _)| t == ticker)
                            .map_or(Decimal::ZERO, |(_, v)| *v),
                    ),
                });
            }
        }
    }
    out
}
