//! java.time's YearMonth and ChronoUnit.MONTHS.between, the calendar arithmetic of the goals.

use std::fmt;

use chrono::{Datelike, NaiveDate};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct YearMonth {
    year: i32,
    month: u32,
}

impl YearMonth {
    pub fn of(date: NaiveDate) -> Self {
        Self {
            year: date.year(),
            month: date.month(),
        }
    }

    /// YearMonth.parse: "uuuu-MM".
    pub fn parse(value: &str) -> Option<Self> {
        let (year, month) = value.split_once('-')?;
        if year.len() != 4
            || month.len() != 2
            || !year.bytes().all(|b| b.is_ascii_digit())
            || !month.bytes().all(|b| b.is_ascii_digit())
        {
            return None;
        }
        let month: u32 = month.parse().ok()?;
        (1..=12).contains(&month).then_some(Self {
            year: year.parse().ok()?,
            month,
        })
    }

    fn index(self) -> i64 {
        i64::from(self.year) * 12 + i64::from(self.month) - 1
    }

    pub fn plus_months(self, months: i64) -> Self {
        let index = self.index() + months;
        Self {
            year: index.div_euclid(12) as i32,
            month: index.rem_euclid(12) as u32 + 1,
        }
    }

    pub fn at_end_of_month(self) -> NaiveDate {
        let next = self.plus_months(1);
        NaiveDate::from_ymd_opt(next.year, next.month, 1)
            .and_then(|d| d.pred_opt())
            .expect("a valid month")
    }
}

impl fmt::Display for YearMonth {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:04}-{:02}", self.year, self.month)
    }
}

/// ChronoUnit.MONTHS.between(start, end): whole months, truncated towards zero.
pub fn months_between(start: NaiveDate, end: NaiveDate) -> i64 {
    let mut months = YearMonth::of(end).index() - YearMonth::of(start).index();
    let days = i64::from(end.day()) - i64::from(start.day());
    if months > 0 && days < 0 {
        months -= 1;
    } else if months < 0 && days > 0 {
        months += 1;
    }
    months
}

#[cfg(test)]
mod tests {
    use super::*;

    fn day(s: &str) -> NaiveDate {
        s.parse().unwrap()
    }

    #[test]
    fn months_between_truncates_like_java() {
        assert_eq!(months_between(day("2026-01-31"), day("2026-02-28")), 0);
        assert_eq!(months_between(day("2026-01-15"), day("2027-07-15")), 18);
        assert_eq!(months_between(day("2026-12-20"), day("2027-01-19")), 0);
        assert_eq!(months_between(day("2026-12-20"), day("2027-01-20")), 1);
        assert_eq!(months_between(day("2026-03-10"), day("2026-01-20")), -1);
    }

    #[test]
    fn year_month_crosses_year_ends() {
        let jan = YearMonth::parse("2026-01").unwrap();
        assert_eq!(jan.plus_months(-1).to_string(), "2025-12");
        assert_eq!(jan.plus_months(-13).to_string(), "2024-12");
        assert_eq!(jan.plus_months(12).to_string(), "2027-01");
        assert_eq!(YearMonth::parse("2024-02").unwrap().at_end_of_month(), day("2024-02-29"));
        assert!(YearMonth::parse("2026-13").is_none());
        assert!(YearMonth::parse("2026-1").is_none());
        assert!(YearMonth::parse("abc").is_none());
    }
}
