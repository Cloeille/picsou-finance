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
use crate::ledger::prices::{PriceRead, registered_coins};
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::{holdings, valuation};

const SCALE: u32 = 8;
const MAX_CONTRIBUTORS_PER_SLICE: usize = 12;
const SECTOR_TARGET: i64 = 6;
const COUNTRY_TARGET: i64 = 3;
/// Bucket for the disclosed tail of a known top-N look-through (e.g. countries #11+, or justETF's
/// "Other" remainder) so known funds are not misreported as unclassified.
const OTHER_SLICE_LABEL: &str = "other";
/// Minimum published slice sum (%) required before assigning the undisclosed tail to `"other"`.
/// Below 50 %, the remainder stays unclassified so an upstream HTML layout breakage cannot be
/// hidden inside `"other"`.
const FLOOR_CLASSIFY_THRESHOLD: i64 = 50;
/// Sums at or above 99 % (e.g. 99.52 % from cash/rounding, or > 100 % overshoot) are normalized
/// across the disclosed slices to 100 % only when the source did not emit an explicit `"other"`
/// slice.
const ROUNDING_NORMALIZE_THRESHOLD: i64 = 99;

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
    name: Option<String>,
    account_id: Option<i64>,
    #[serde(with = "crate::accounts::jdecimal")]
    value_eur: Decimal,
    sector_missing: bool,
    country_missing: bool,
    profile_looked: bool,
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
    coverage_percent: Decimal,
    slices: Vec<Slice>,
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

/// Where a ticker was first seen, so an unclassified line can be named and reached.
struct Origin {
    account_id: i64,
    name: Option<String>,
}

/// The member's override first, then the provider's tier, then the coin registry; untiered lines
/// count as equity. Matches `pyramid::tier_for` so crypto held in a brokerage account is never
/// reported as unclassified equity.
fn is_equity_line(
    over: Option<&HoldingClassification>,
    provider: Option<WealthTier>,
    is_coin: bool,
) -> bool {
    let tier = over
        .and_then(|o| o.wealth_tier)
        .or(provider)
        .or(is_coin.then_some(WealthTier::Crypto));
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
        let lines = holdings_of(&mut *conn, prices, logos, account, today).await?;
        let line_tickers: std::collections::BTreeSet<String> = lines
            .iter()
            .filter(|l| !java_is_blank(&l.ticker))
            .map(|l| l.ticker.to_uppercase())
            .collect();
        let coins = if line_tickers.is_empty() {
            std::collections::BTreeSet::new()
        } else {
            registered_coins(&mut *conn, &line_tickers).await?
        };
        for line in lines {
            let Some(current) = line.current_value_eur else {
                continue;
            };
            let value = weigh(current, share);
            if value.is_zero() {
                continue;
            }
            let ticker = line.ticker.to_uppercase();
            if !is_equity_line(
                overrides.get(&ticker),
                line.provider_wealth_tier,
                coins.contains(&ticker),
            ) {
                continue;
            }
            match value_by_ticker.iter_mut().find(|(t, _)| *t == ticker) {
                Some((_, v)) => *v += value,
                None => value_by_ticker.push((ticker.clone(), value)),
            }
            origins.entry(ticker).or_insert(Origin {
                account_id: account.id,
                name: line.name.clone(),
            });
            total += value;
        }
    }
    // An account holding one declared fund, without lines, is one line of that fund, after the
    // held lines so that a held line of the same ticker names it.
    let tracked = tracked_fund::keys(&mut *conn, &accounts.iter().collect::<Vec<_>>()).await?;
    for account in accounts.iter().filter(|a| tracked.contains_key(&a.id)) {
        let ticker = tracked[&account.id].clone();
        if !is_equity_line(overrides.get(&ticker), None, false) {
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
        origins.entry(ticker).or_insert(Origin {
            account_id: account.id,
            name: None,
        });
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
    let mut sector_classified = Decimal::ZERO;
    let mut country_classified = Decimal::ZERO;
    let mut sector_mixed = false;
    let mut country_mixed = false;
    let record =
        |into: &mut Vec<UnclassifiedLine>, ticker: &str, value, sector, country, looked| {
            let origin = origins.get(ticker);
            into.push(UnclassifiedLine {
                ticker: ticker.to_owned(),
                name: origin.and_then(|o| o.name.clone()),
                account_id: origin.map(|o| o.account_id),
                value_eur: scale2(value),
                sector_missing: sector,
                country_missing: country,
                profile_looked: looked,
            });
        };

    for (ticker, value) in &value_by_ticker {
        let value = *value;
        let over = overrides.get(ticker);
        let profile = loaded.get(ticker);
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
        let Some(profile) = profile.filter(|p| p.was_looked_up()) else {
            if !placed_sector || !placed_country {
                record(
                    &mut unclassified,
                    ticker,
                    value,
                    !placed_sector,
                    !placed_country,
                    false,
                );
            }
            continue;
        };
        if !placed_sector {
            let placed = spread(&profile.slices, "SECTOR", ticker, value, &mut sector_totals);
            if placed > Decimal::ZERO {
                sector_classified += placed;
                placed_sector = placed >= value;
            } else if let Some(sector) = &profile.sector_key {
                sector_totals.add(sector, ticker, value);
                sector_classified += value;
                sector_mixed = true;
                placed_sector = true;
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
                placed_country = placed >= value;
            } else if let Some(country) = &profile.country_key {
                country_totals.add(country, ticker, value);
                country_classified += value;
                country_mixed = true;
                placed_country = true;
            }
        }
        if !placed_sector || !placed_country {
            record(
                &mut unclassified,
                ticker,
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
    let sectors = breakdown(
        sector_totals,
        sector_classified,
        total,
        SECTOR_TARGET,
        sector_mixed,
    );
    let countries = breakdown(
        country_totals,
        country_classified,
        total,
        COUNTRY_TARGET,
        country_mixed,
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

/// Places a holding across its published slices:
/// - When no positive slices exist for `kind`, returns 0 (genuinely unclassified).
/// - When the published sum is `< 50 %`, applies each published slice literally and leaves the
///   remainder unclassified (NOT `"other"`) so a broken HTML scrape cannot hide a gap.
/// - When the published sum exceeds `100 %` (rounding overshoot), normalizes all positive slices
///   proportionally to `100 %` of `value`.
/// - When the published sum is `>= 99 %` AND no explicit `"other"` slice was emitted by the
///   source, normalizes the named slices proportionally to `100 %` of `value` so cash/rounding
///   dust never spawns an unclassified or sub-percent `"other"` slice.
/// - Otherwise (`50 % <= sum <= 100 %`, either `< 99 %` or with an explicit `"other"` slice),
///   applies every named slice literally and assigns the remainder to the classified `"other"`
///   bucket.
fn spread(
    slices: &[ProfileSlice],
    kind: &str,
    ticker: &str,
    value: Decimal,
    target: &mut AxisTotals,
) -> Decimal {
    let positive: Vec<&ProfileSlice> = slices
        .iter()
        .filter(|s| s.kind == kind && s.percent > Decimal::ZERO)
        .collect();
    if positive.is_empty() {
        return Decimal::ZERO;
    }
    let mut explicit_other = Decimal::ZERO;
    let mut named: Vec<&ProfileSlice> = Vec::with_capacity(positive.len());
    for slice in &positive {
        if slice.label == OTHER_SLICE_LABEL {
            explicit_other += slice.percent;
        } else {
            named.push(slice);
        }
    }
    let named_sum: Decimal = named.iter().map(|s| s.percent).sum();
    let total_sum = named_sum + explicit_other;
    if total_sum <= Decimal::ZERO {
        return Decimal::ZERO;
    }
    let has_explicit_other = explicit_other > Decimal::ZERO;

    // Floor: below 50 % published coverage, leave the remainder unclassified rather than masking a
    // parser/layout regression inside "other".
    if total_sum < Decimal::from(FLOOR_CLASSIFY_THRESHOLD) {
        let mut placed = Decimal::ZERO;
        for slice in positive {
            let part = divide(value * slice.percent, Decimal::ONE_HUNDRED, SCALE, HALF_UP);
            target.add(&slice.label, ticker, part);
            placed += part;
        }
        return placed;
    }

    // Overshoot (> 100 %): normalize every positive slice (preserving explicit "other" if present).
    if total_sum > Decimal::ONE_HUNDRED {
        return spread_normalized(&positive, total_sum, ticker, value, target);
    }

    // Near-complete (>= 99 %) with no explicit "other" slice from the source: normalize to 100 %.
    if total_sum >= Decimal::from(ROUNDING_NORMALIZE_THRESHOLD) && !has_explicit_other {
        return spread_normalized(&named, named_sum, ticker, value, target);
    }

    // Known top-N look-through (50–99 %) or a distribution carrying an explicit "other" row: apply
    // named slices literally and assign the rest to the classified "other" bucket.
    let mut placed_named = Decimal::ZERO;
    for slice in named {
        let part = divide(value * slice.percent, Decimal::ONE_HUNDRED, SCALE, HALF_UP);
        target.add(&slice.label, ticker, part);
        placed_named += part;
    }
    let remainder = value - placed_named;
    if remainder > Decimal::ZERO {
        target.add(OTHER_SLICE_LABEL, ticker, remainder);
    }
    value
}

fn spread_normalized(
    slices: &[&ProfileSlice],
    sum: Decimal,
    ticker: &str,
    value: Decimal,
    target: &mut AxisTotals,
) -> Decimal {
    let mut placed = Decimal::ZERO;
    let mut parts: Vec<(&str, Decimal)> = Vec::with_capacity(slices.len());
    let mut largest_idx = 0usize;
    for (i, slice) in slices.iter().enumerate() {
        let part = divide(value * slice.percent, sum, SCALE, HALF_UP);
        if i == 0 || slice.percent > slices[largest_idx].percent {
            largest_idx = i;
        }
        placed += part;
        parts.push((&slice.label, part));
    }
    // Absorb any 1e-8 division residual into the largest slice so classified == value exactly.
    let residual = value - placed;
    if !residual.is_zero() {
        parts[largest_idx].1 += residual;
    }
    for (label, part) in parts {
        target.add(label, ticker, part);
    }
    value
}

fn breakdown(
    totals: AxisTotals,
    classified: Decimal,
    total: Decimal,
    target: i64,
    mixed: bool,
) -> Breakdown {
    let basis = if mixed { "MIXED" } else { "EXPOSURE" };
    if classified <= Decimal::ZERO {
        return Breakdown {
            score: 0,
            effective_count: Decimal::ZERO,
            target_count: target,
            basis,
            classified_value_eur: Decimal::ZERO,
            coverage_percent: Decimal::ZERO,
            slices: Vec::new(),
        };
    }
    // Inverse Herfindahl over named slices only: exclude "other" and renormalize weights across
    // the named buckets, because "other" aggregates unknown holdings/sectors/countries and
    // counting it as a single bucket would artificially inflate N_eff and the score.
    let named_total: Decimal = totals
        .labels
        .iter()
        .filter(|(label, _, _)| label != OTHER_SLICE_LABEL)
        .map(|(_, value, _)| *value)
        .sum();
    let mut herfindahl = Decimal::ZERO;
    if named_total > Decimal::ZERO {
        for (label, value, _) in &totals.labels {
            if label == OTHER_SLICE_LABEL {
                continue;
            }
            let weight = divide(*value, named_total, SCALE, HALF_UP);
            herfindahl += weight * weight;
        }
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
    slices.sort_by(|a, b| {
        let a_other = a.label == OTHER_SLICE_LABEL;
        let b_other = b.label == OTHER_SLICE_LABEL;
        a_other
            .cmp(&b_other)
            .then_with(|| b.percent.cmp(&a.percent))
    });
    Breakdown {
        score,
        effective_count: set_scale(effective, 2, HALF_UP),
        target_count: target,
        basis,
        classified_value_eur: scale2(classified),
        coverage_percent: scale2(percent_of(classified, total)),
        slices,
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
