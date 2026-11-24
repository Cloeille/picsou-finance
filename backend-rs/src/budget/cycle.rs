//! BudgetCycle: the payday budget cycle, from cycleStartDay of one month to the day before it
//! in the next (the calendar month when the day is 1), and the CYCLE / YTD period ranges.

use std::str::FromStr;

use chrono::{Datelike, Months, NaiveDate};
use serde::Serialize;

/// Inclusive [start, end] of one cycle.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CycleRange {
    pub start: NaiveDate,
    pub end: NaiveDate,
}

impl CycleRange {
    pub fn contains(&self, date: NaiveDate) -> bool {
        date >= self.start && date <= self.end
    }
}

fn with_day(date: NaiveDate, day: u32) -> NaiveDate {
    date.with_day(day).unwrap_or(date)
}

/// The cycle containing date. cycle_start_day is 1 to 28, which the settings validation and the
/// column's check constraint guarantee.
pub fn cycle_for(date: NaiveDate, cycle_start_day: u32) -> CycleRange {
    assert!(
        (1..=28).contains(&cycle_start_day),
        "cycleStartDay must be between 1 and 28, was {cycle_start_day}"
    );
    let start = if date.day() >= cycle_start_day {
        with_day(date, cycle_start_day)
    } else {
        with_day(
            date.checked_sub_months(Months::new(1)).unwrap_or(date),
            cycle_start_day,
        )
    };
    let end = start
        .checked_add_months(Months::new(1))
        .and_then(|d| d.pred_opt())
        .unwrap_or(start);
    CycleRange { start, end }
}

/// Every cycle overlapping [from, to], oldest first.
pub fn cycles_between(from: NaiveDate, to: NaiveDate, cycle_start_day: u32) -> Vec<CycleRange> {
    let mut cycles = Vec::new();
    let mut cursor = cycle_for(from, cycle_start_day);
    while cursor.start <= to {
        cycles.push(cursor);
        match cursor.end.succ_opt() {
            Some(next) => cursor = cycle_for(next, cycle_start_day),
            None => break,
        }
    }
    cycles
}

/// CashflowPeriod: the current cycle, or the calendar year to date.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CashflowPeriod {
    Cycle,
    Ytd,
}

impl FromStr for CashflowPeriod {
    type Err = ();

    fn from_str(value: &str) -> Result<Self, ()> {
        match value {
            "CYCLE" => Ok(Self::Cycle),
            "YTD" => Ok(Self::Ytd),
            _ => Err(()),
        }
    }
}

/// The [from, to] span of a period anchored on today.
pub fn range(
    period: CashflowPeriod,
    today: NaiveDate,
    cycle_start_day: u32,
) -> (NaiveDate, NaiveDate) {
    match period {
        CashflowPeriod::Ytd => (today.with_ordinal(1).unwrap_or(today), today),
        CashflowPeriod::Cycle => {
            let cycle = cycle_for(today, cycle_start_day);
            (cycle.start, cycle.end)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(s: &str) -> NaiveDate {
        s.parse().unwrap()
    }

    #[test]
    fn calendar_month_when_start_day_is_one() {
        let c = cycle_for(d("2026-02-14"), 1);
        assert_eq!((c.start, c.end), (d("2026-02-01"), d("2026-02-28")));
    }

    #[test]
    fn payday_cycle_mid_month_and_before_start_rolls_back() {
        let c = cycle_for(d("2026-03-27"), 25);
        assert_eq!((c.start, c.end), (d("2026-03-25"), d("2026-04-24")));
        let c = cycle_for(d("2026-03-10"), 25);
        assert_eq!((c.start, c.end), (d("2026-02-25"), d("2026-03-24")));
        let c = cycle_for(d("2026-03-25"), 25);
        assert_eq!(c.start, d("2026-03-25"));
    }

    #[test]
    fn rolls_back_over_the_year_boundary() {
        let c = cycle_for(d("2026-01-05"), 15);
        assert_eq!((c.start, c.end), (d("2025-12-15"), d("2026-01-14")));
        assert!(c.contains(d("2025-12-15")) && c.contains(d("2026-01-14")));
        assert!(!c.contains(d("2026-01-15")));
    }

    #[test]
    #[should_panic(expected = "cycleStartDay must be between 1 and 28")]
    fn rejects_out_of_range_start_day() {
        cycle_for(d("2026-01-05"), 29);
    }

    #[test]
    fn cycles_between_enumerates_inclusive() {
        let cycles = cycles_between(d("2026-01-10"), d("2026-03-02"), 1);
        let starts: Vec<_> = cycles.iter().map(|c| c.start).collect();
        assert_eq!(
            starts,
            vec![d("2026-01-01"), d("2026-02-01"), d("2026-03-01")]
        );
    }
}
