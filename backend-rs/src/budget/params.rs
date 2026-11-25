//! @RequestParam binding as Spring MVC does it: the first value of a name, an empty value
//! treated as absent (defaultValue applies, an optional parameter is null), and 400 problems
//! for a missing required parameter or a value that does not convert.

use std::str::FromStr;

use axum::extract::FromRequestParts;
use axum::http::request::Parts;
use chrono::NaiveDate;

use super::cycle::CashflowPeriod;
use crate::error::{AppError, AppResult};

/// The query string of a request, decoded.
#[derive(Debug, Clone, Default)]
pub struct Params(Vec<(String, String)>);

impl<S: Send + Sync> FromRequestParts<S> for Params {
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(parts: &mut Parts, _: &S) -> Result<Self, Self::Rejection> {
        Ok(Self::parse(parts.uri.query().unwrap_or_default()))
    }
}

fn mismatch(name: &str, value: &str) -> AppError {
    AppError::BadRequest(format!("Failed to convert '{name}' with value: '{value}'"))
}

impl Params {
    pub fn parse(query: &str) -> Self {
        Self(
            url::form_urlencoded::parse(query.as_bytes())
                .map(|(k, v)| (k.into_owned(), v.into_owned()))
                .collect(),
        )
    }

    fn raw(&self, name: &str) -> Option<&str> {
        self.0
            .iter()
            .find(|(k, _)| k == name)
            .map(|(_, v)| v.as_str())
    }

    /// The value, or None when absent or empty.
    fn value(&self, name: &str) -> Option<&str> {
        self.raw(name).filter(|v| !v.is_empty())
    }

    /// An optional parameter converted with FromStr (an enum, a Long, an int).
    pub fn optional<T: FromStr>(&self, name: &str) -> AppResult<Option<T>> {
        match self.value(name) {
            None => Ok(None),
            Some(v) => v.trim().parse().map(Some).map_err(|_| mismatch(name, v)),
        }
    }

    /// A parameter with a defaultValue.
    pub fn or_default<T: FromStr>(&self, name: &str, default: T) -> AppResult<T> {
        Ok(self.optional(name)?.unwrap_or(default))
    }

    /// An ISO date (@DateTimeFormat(iso = DATE)).
    pub fn date(&self, name: &str) -> AppResult<Option<NaiveDate>> {
        match self.value(name) {
            None => Ok(None),
            Some(v) => NaiveDate::parse_from_str(v.trim(), "%Y-%m-%d")
                .map(Some)
                .map_err(|_| mismatch(name, v)),
        }
    }

    /// The period (default CYCLE) and the day it is anchored on (default today).
    pub fn period_and_anchor(&self, today: NaiveDate) -> AppResult<(CashflowPeriod, NaiveDate)> {
        let period = self.or_default("period", CashflowPeriod::Cycle)?;
        let anchor = self.date("anchor")?.unwrap_or(today);
        Ok((period, anchor))
    }

    /// A required ISO date: 400 when absent.
    pub fn required_date(&self, name: &str) -> AppResult<NaiveDate> {
        if self.raw(name).is_none() {
            return Err(AppError::BadRequest(format!(
                "Required parameter '{name}' is not present."
            )));
        }
        self.date(name)?.ok_or_else(|| {
            AppError::BadRequest(format!("Required parameter '{name}' is not present."))
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_values_count_as_absent_and_bad_values_are_named() {
        let p = Params::parse("period=&horizonDays=x&anchor=2026-02-30&status=CONFIRMED");
        assert_eq!(p.or_default("period", 7).unwrap(), 7);
        assert!(p.optional::<i32>("horizonDays").is_err());
        assert!(p.date("anchor").is_err());
        assert_eq!(
            p.optional::<String>("status").unwrap().as_deref(),
            Some("CONFIRMED")
        );
        assert!(p.required_date("from").is_err());
    }
}
