//! RecurringDetectionService: groups the member's recent rows by merchant identity and upserts a
//! series for every regular one, with a confidence score, silent auto-confirmation of a
//! high-confidence fixed amount, price-change tracking and transaction back-links. An IGNORED
//! series is never resurrected and auto-confirmation only promotes a SUGGESTED one.
//!
//! Java computes the ratios and the score in double; they are computed here in Decimal, which
//! agrees except where a value lands within rounding noise of a threshold.

use chrono::{Days, NaiveDate};
use rust_decimal::{Decimal, RoundingStrategy};

use super::recurring::{self, RecurringStatus, Series};
use super::repo;
use crate::AppState;
use crate::error::AppResult;
use crate::ledger::model::{RecurringCadence, Transaction};
use crate::ledger::sync_values::{java_is_blank, java_trim};

const MIN_OCCURRENCES: usize = 3;
const LOOKBACK_DAYS: u64 = 400;
const RECENT_WINDOW: usize = 4;
const AUTO_CONFIRM_MIN_OCCURRENCES: usize = 3;

fn dec(text: &str) -> Decimal {
    text.parse().expect("a decimal literal")
}

/// Each gap must sit within this fraction of the median gap.
fn interval_tolerance() -> Decimal {
    dec("0.30")
}

fn fixed_tolerance() -> Decimal {
    dec("0.15")
}

fn variable_tolerance() -> Decimal {
    dec("0.40")
}

fn price_change_pct() -> Decimal {
    dec("0.05")
}

fn auto_confirm_confidence() -> Decimal {
    dec("0.80")
}

/// Lower case, trimmed, inner whitespace collapsed.
pub fn normalise(value: Option<&str>) -> String {
    let Some(value) = value else {
        return String::new();
    };
    let lowered = java_trim(value).to_lowercase();
    let mut out = String::with_capacity(lowered.len());
    let mut in_space = false;
    for c in lowered.chars() {
        if matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r') {
            if !in_space {
                out.push(' ');
            }
            in_space = true;
        } else {
            out.push(c);
            in_space = false;
        }
    }
    out
}

/// The clean merchant label when present, else the raw counterparty.
pub fn identity_key(tx: &Transaction) -> String {
    match tx.merchant_label.as_deref() {
        Some(label) if !java_is_blank(label) => normalise(Some(label)),
        _ => normalise(tx.counterparty.as_deref()),
    }
}

/// Groups by identity in first-seen order, each group by date then id.
pub fn group_by_identity(transactions: Vec<Transaction>) -> Vec<Vec<Transaction>> {
    let mut keys: Vec<String> = Vec::new();
    let mut groups: Vec<Vec<Transaction>> = Vec::new();
    for tx in transactions {
        let key = identity_key(&tx);
        if key.is_empty() {
            continue;
        }
        match keys.iter().position(|k| *k == key) {
            Some(i) => groups[i].push(tx),
            None => {
                keys.push(key);
                groups.push(vec![tx]);
            }
        }
    }
    for group in &mut groups {
        group.sort_by_key(|t| (t.date, t.id));
    }
    groups
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AmountClass {
    Fixed,
    Variable,
    Unstable,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Candidate {
    pub cadence: RecurringCadence,
    pub expected_amount: Decimal,
    pub amount_min: Decimal,
    pub amount_max: Decimal,
    pub is_variable: bool,
    pub confidence: Decimal,
    pub occurrences: usize,
    pub auto_confirm: bool,
    pub last_seen: NaiveDate,
    pub next_due: NaiveDate,
    pub label: String,
}

fn clamp01(v: Decimal) -> Decimal {
    v.clamp(Decimal::ZERO, Decimal::ONE)
}

/// RecurringCadence.fromMedianDays: the closest period, if within 25% of it.
pub fn cadence_from_median_days(median: Decimal) -> Option<RecurringCadence> {
    let periods = [
        (RecurringCadence::Weekly, 7),
        (RecurringCadence::Biweekly, 14),
        (RecurringCadence::Monthly, 30),
        (RecurringCadence::Quarterly, 91),
        (RecurringCadence::Yearly, 365),
    ];
    let mut best: Option<(RecurringCadence, Decimal)> = None;
    for (cadence, days) in periods {
        let days = Decimal::from(days);
        let ratio = (median - days).abs() / days;
        if best.is_none_or(|(_, r)| ratio < r) {
            best = Some((cadence, ratio));
        }
    }
    best.filter(|(_, r)| *r <= dec("0.25")).map(|(c, _)| c)
}

fn median_days(gaps: &[i64]) -> Decimal {
    let mut sorted = gaps.to_vec();
    sorted.sort_unstable();
    if sorted.is_empty() {
        return Decimal::ZERO;
    }
    let mid = sorted.len() / 2;
    if sorted.len() % 2 == 1 {
        Decimal::from(sorted[mid])
    } else {
        Decimal::from(sorted[mid - 1] + sorted[mid]) / Decimal::TWO
    }
}

/// medianAmount: BigDecimal's exact divide by two keeps the sum's scale unless it needs one more.
pub fn median_amount(amounts: &[Decimal]) -> Decimal {
    let mut sorted = amounts.to_vec();
    sorted.sort();
    let mid = sorted.len() / 2;
    if sorted.len() % 2 == 1 {
        return sorted[mid];
    }
    let sum = sorted[mid - 1] + sorted[mid];
    let mantissa = sum.mantissa();
    if mantissa % 2 == 0 {
        Decimal::from_i128_with_scale(mantissa / 2, sum.scale())
    } else {
        Decimal::from_i128_with_scale(mantissa * 5, sum.scale() + 1)
    }
}

fn gaps_are_regular(gaps: &[i64], median: Decimal) -> bool {
    let allowed = median * interval_tolerance();
    gaps.iter()
        .all(|g| (Decimal::from(*g) - median).abs() <= allowed)
}

pub fn classify_amounts(amounts: &[Decimal], median: Decimal) -> AmountClass {
    let med = median.abs();
    if med.is_zero() {
        return AmountClass::Unstable;
    }
    let max_rel = amounts
        .iter()
        .map(|a| (a.abs() - med).abs() / med)
        .fold(Decimal::ZERO, Decimal::max);
    if max_rel <= fixed_tolerance() {
        AmountClass::Fixed
    } else if max_rel <= variable_tolerance() {
        AmountClass::Variable
    } else {
        AmountClass::Unstable
    }
}

/// 0.45 regularity + 0.35 amount stability + 0.20 volume, rounded half-up to 3 decimals.
pub fn confidence(
    gaps: &[i64],
    median_gap: Decimal,
    recent: &[Decimal],
    median_amount: Decimal,
    occurrences: usize,
) -> Decimal {
    let mean_rel_gap = if gaps.is_empty() {
        Decimal::ZERO
    } else {
        gaps.iter()
            .map(|g| (Decimal::from(*g) - median_gap).abs() / median_gap)
            .sum::<Decimal>()
            / Decimal::from(gaps.len())
    };
    let regularity = clamp01(Decimal::ONE - mean_rel_gap / interval_tolerance());
    let med = median_amount.abs();
    let max_rel_amount = if med.is_zero() {
        Decimal::ONE
    } else {
        recent
            .iter()
            .map(|a| (a.abs() - med).abs() / med)
            .fold(Decimal::ZERO, Decimal::max)
    };
    let stability = clamp01(Decimal::ONE - max_rel_amount / variable_tolerance());
    let extra = Decimal::from(i64::try_from(occurrences).unwrap_or(i64::MAX) - 3);
    let volume = dec("0.4") + dec("0.6") * clamp01(extra / Decimal::from(3));
    let score = dec("0.45") * regularity + dec("0.35") * stability + dec("0.20") * volume;
    let mut rounded = score.round_dp_with_strategy(3, RoundingStrategy::MidpointAwayFromZero);
    rounded.rescale(3);
    rounded
}

fn clean_label(tx: &Transaction) -> String {
    match tx.merchant_label.as_deref() {
        Some(label) if !java_is_blank(label) => java_trim(label).to_owned(),
        _ => tx
            .counterparty
            .as_deref()
            .map(java_trim)
            .unwrap_or_default()
            .to_owned(),
    }
}

/// A step above 5% (and at least a cent) is a price change.
pub fn is_price_change(previous: Option<Decimal>, current: Decimal) -> bool {
    let Some(previous) = previous.filter(|p| !p.is_zero()) else {
        return false;
    };
    let diff = (current - previous).abs();
    if diff < dec("0.01") {
        return false;
    }
    diff / previous.abs() > price_change_pct()
}

/// Whether the group forms a regular series (fixed or variable amount).
pub fn analyse(txs: &[Transaction]) -> Option<Candidate> {
    if txs.len() < MIN_OCCURRENCES {
        return None;
    }
    let gaps: Vec<i64> = txs
        .windows(2)
        .map(|w| (w[1].date - w[0].date).num_days())
        .collect();
    let median_gap = median_days(&gaps);
    if median_gap <= Decimal::ZERO || !gaps_are_regular(&gaps, median_gap) {
        return None;
    }
    let cadence = cadence_from_median_days(median_gap)?;
    let amounts: Vec<Decimal> = txs.iter().map(|t| t.amount).collect();
    let recent = &amounts[amounts.len().saturating_sub(RECENT_WINDOW)..];
    let expected = median_amount(recent);
    let class = classify_amounts(recent, expected);
    if class == AmountClass::Unstable {
        return None;
    }
    let variable = class == AmountClass::Variable;
    // Stream.min/max keep the first of equal values, as the scale of a tie matters on output.
    let min = amounts
        .iter()
        .copied()
        .reduce(|a, b| if b < a { b } else { a })
        .unwrap_or(expected);
    let max = amounts
        .iter()
        .copied()
        .reduce(|a, b| if b > a { b } else { a })
        .unwrap_or(expected);
    let confidence = confidence(&gaps, median_gap, recent, expected, txs.len());
    let auto_confirm = !variable
        && txs.len() >= AUTO_CONFIRM_MIN_OCCURRENCES
        && confidence >= auto_confirm_confidence();
    let last = txs.last()?;
    Some(Candidate {
        cadence,
        expected_amount: expected,
        amount_min: min,
        amount_max: max,
        is_variable: variable,
        confidence,
        occurrences: txs.len(),
        auto_confirm,
        last_seen: last.date,
        next_due: recurring::next(cadence, last.date),
        label: clean_label(last),
    })
}

/// Scans the last 400 days and upserts the detected series; returns how many were written.
pub async fn detect(state: &AppState, member_id: i64, today: NaiveDate) -> AppResult<i32> {
    let from = today
        .checked_sub_days(Days::new(LOOKBACK_DAYS))
        .unwrap_or(NaiveDate::MIN);
    let transactions = repo::member_between(&state.db, member_id, from, today).await?;
    let mut upserted = 0;
    let mut conn = state.db.begin().await?;
    for group in group_by_identity(transactions) {
        let Some(candidate) = analyse(&group) else {
            continue;
        };
        if java_is_blank(&candidate.label) {
            continue;
        }
        if upsert(state, &mut conn, member_id, &candidate, &group).await? {
            upserted += 1;
        }
    }
    conn.commit().await?;
    Ok(upserted)
}

async fn upsert(
    state: &AppState,
    conn: &mut sqlx::PgConnection,
    member_id: i64,
    c: &Candidate,
    group: &[Transaction],
) -> AppResult<bool> {
    let existing = recurring::find_by_label(&mut *conn, member_id, &c.label).await?;
    if existing
        .as_ref()
        .is_some_and(|s| s.status == RecurringStatus::Ignored)
    {
        return Ok(false);
    }
    let mut series = match existing {
        Some(mut s) => {
            if is_price_change(Some(s.expected_amount), c.expected_amount) {
                s.previous_amount = Some(s.expected_amount);
                s.price_changed_at = Some(c.last_seen);
            }
            s
        }
        None => Series {
            id: 0,
            label: c.label.clone(),
            counterparty: None,
            expected_amount: c.expected_amount,
            cadence: c.cadence,
            status: RecurringStatus::Suggested,
            next_due_date: None,
            last_seen_date: None,
            category_id: None,
            category_name: None,
            category_color: None,
            category_icon: None,
            confidence: None,
            amount_min: None,
            amount_max: None,
            is_variable: false,
            previous_amount: None,
            price_changed_at: None,
            auto_confirmed: false,
        },
    };
    let latest = group.last().expect("a detected group is never empty");
    series.counterparty.clone_from(&latest.counterparty);
    series.expected_amount = c.expected_amount;
    series.amount_min = Some(c.amount_min);
    series.amount_max = Some(c.amount_max);
    series.is_variable = c.is_variable;
    series.confidence = Some(c.confidence);
    series.cadence = c.cadence;
    series.last_seen_date = Some(c.last_seen);
    series.next_due_date = Some(c.next_due);
    if series.status == RecurringStatus::Suggested && c.auto_confirm {
        series.status = RecurringStatus::Confirmed;
        series.auto_confirmed = true;
    }
    let id = recurring::save(&mut *conn, member_id, &series, state.clock.now()).await?;
    let to_link: Vec<i64> = group
        .iter()
        .filter(|t| t.recurring_series_id != Some(id))
        .map(|t| t.id)
        .collect();
    if !to_link.is_empty() {
        sqlx::query("UPDATE \"transaction\" SET recurring_series_id = $1 WHERE id = ANY($2)")
            .bind(id)
            .bind(&to_link)
            .execute(&mut *conn)
            .await?;
    }
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn median_amount_divides_like_big_decimal() {
        assert_eq!(
            median_amount(&[dec("-9.99"), dec("-10.00")]).to_string(),
            "-9.995"
        );
        assert_eq!(
            median_amount(&[dec("-10.00"), dec("-12.00")]).to_string(),
            "-11.00"
        );
        assert_eq!(
            median_amount(&[dec("1"), dec("2"), dec("9")]).to_string(),
            "2"
        );
    }

    #[test]
    fn normalise_collapses_case_and_whitespace() {
        assert_eq!(normalise(Some("  NETFLIX   EU \t")), "netflix eu");
        assert_eq!(normalise(None), "");
    }

    #[test]
    fn price_change_needs_more_than_five_percent_and_a_cent() {
        assert!(is_price_change(Some(dec("-10.00")), dec("-11.00")));
        assert!(!is_price_change(Some(dec("-10.00")), dec("-10.40")));
        assert!(!is_price_change(Some(dec("0.10")), dec("0.105")));
        assert!(!is_price_change(None, dec("-11.00")));
        assert!(!is_price_change(Some(Decimal::ZERO), dec("-11.00")));
    }

    #[test]
    fn cadence_matches_the_closest_period_within_a_quarter() {
        assert_eq!(
            cadence_from_median_days(dec("31")),
            Some(RecurringCadence::Monthly)
        );
        assert_eq!(
            cadence_from_median_days(dec("7")),
            Some(RecurringCadence::Weekly)
        );
        assert_eq!(cadence_from_median_days(dec("200")), None);
    }
}
