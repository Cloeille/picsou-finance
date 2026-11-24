//! GoalRequest, GoalAllocationRequest, the month amount requests and the goal responses.

use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use crate::http::json::{Errors, Validate};
use crate::wealth::validation::{decimal_max_msg, decimal_min};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum GoalType {
    #[default]
    SavingsTarget,
    RecurringInvestment,
}

impl GoalType {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::SavingsTarget => "SAVINGS_TARGET",
            Self::RecurringInvestment => "RECURRING_INVESTMENT",
        }
    }

    pub fn from_db(value: &str) -> Self {
        if value == "RECURRING_INVESTMENT" {
            Self::RecurringInvestment
        } else {
            Self::SavingsTarget
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GoalAllocationRequest {
    pub ticker: Option<String>,
    pub monthly_amount: Option<Decimal>,
}

/// One request shape for both kinds of goal; a missing type means SAVINGS_TARGET.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GoalRequest {
    pub name: Option<String>,
    #[serde(rename = "type")]
    pub goal_type: Option<GoalType>,
    pub target_amount: Option<Decimal>,
    pub deadline: Option<NaiveDate>,
    pub monthly_amount: Option<Decimal>,
    pub expected_return: Option<Decimal>,
    pub start_date: Option<NaiveDate>,
    pub end_date: Option<NaiveDate>,
    pub allocations: Option<Vec<GoalAllocationRequest>>,
    pub account_ids: Option<Vec<i64>>,
}

impl GoalRequest {
    pub fn kind(&self) -> GoalType {
        self.goal_type.unwrap_or_default()
    }

    pub fn lines(&self) -> &[GoalAllocationRequest] {
        self.allocations.as_deref().unwrap_or_default()
    }

    pub fn ids(&self) -> &[i64] {
        self.account_ids.as_deref().unwrap_or_default()
    }

    /// Bean validation, @Future included: it needs today, which the Validate trait has not.
    pub fn validate(&self, errors: &mut Errors, today: NaiveDate) {
        errors.not_blank("name", self.name.as_deref());
        errors.size("name", self.name.as_deref(), 0, 200);
        decimal_min(errors, "targetAmount", self.target_amount, "0.01");
        if self.deadline.is_some_and(|d| d <= today) {
            let message =
                errors.default_message("must be a future date", "doit être dans le futur");
            errors.add("deadline", message);
        }
        decimal_min(errors, "monthlyAmount", self.monthly_amount, "0.01");
        decimal_min(errors, "expectedReturn", self.expected_return, "-100.0");
        decimal_max_msg(
            errors,
            "expectedReturn",
            self.expected_return,
            "100.0",
            None,
        );
        for (i, line) in self.lines().iter().enumerate() {
            let ticker = format!("allocations[{i}].ticker");
            errors.not_blank(&ticker, line.ticker.as_deref());
            errors.size(&ticker, line.ticker.as_deref(), 0, 30);
            let amount = format!("allocations[{i}].monthlyAmount");
            errors.not_null(&amount, line.monthly_amount.as_ref());
            decimal_min(errors, &amount, line.monthly_amount, "0.01");
        }
        errors.not_empty("accountIds", self.account_ids.as_deref());

        let kind = self.kind();
        let lines = self.lines();
        let rules = [
            (
                "savingsTargetComplete",
                kind != GoalType::SavingsTarget
                    || (self.target_amount.is_some() && self.deadline.is_some()),
                "a savings target needs both a target amount and a deadline",
            ),
            (
                "recurringComplete",
                kind != GoalType::RecurringInvestment || self.monthly_amount.is_some(),
                "a recurring investment needs a monthly amount",
            ),
            (
                "recurringSingleAccount",
                kind != GoalType::RecurringInvestment
                    || self.account_ids.as_ref().is_some_and(|ids| ids.len() == 1),
                "a recurring investment tracks exactly one account",
            ),
            (
                "dateRangeOrdered",
                match (self.start_date, self.end_date) {
                    (Some(start), Some(end)) => end > start,
                    _ => true,
                },
                "the end date must come after the start date",
            ),
            (
                "allocationOnlyOnRecurring",
                kind == GoalType::RecurringInvestment || lines.is_empty(),
                "only a recurring investment can split its monthly amount",
            ),
            (
                "allocationWithinMonthlyAmount",
                match self.monthly_amount {
                    Some(monthly) if !lines.is_empty() => lines
                        .iter()
                        .filter_map(|l| l.monthly_amount)
                        .try_fold(Decimal::ZERO, Decimal::checked_add)
                        .is_some_and(|total| total <= monthly),
                    _ => true,
                },
                "the split cannot exceed the monthly amount",
            ),
            (
                "allocationTickersUnique",
                {
                    let tickers: Vec<&str> =
                        lines.iter().filter_map(|l| l.ticker.as_deref()).collect();
                    let distinct: std::collections::HashSet<&str> =
                        tickers.iter().copied().collect();
                    distinct.len() == tickers.len()
                },
                "a position can only appear once in the split",
            ),
        ];
        for (field, holds, message) in rules {
            if !holds {
                errors.add(field, message);
            }
        }
    }
}

/// GoalMonthOverrideRequest and GoalManualContributionRequest: the same constraints.
#[derive(Debug, Clone, Deserialize)]
pub struct MonthAmountRequest {
    pub amount: Option<Decimal>,
}

impl Validate for MonthAmountRequest {
    fn validate(&self, errors: &mut Errors) {
        errors.not_null("amount", self.amount.as_ref());
        decimal_min(errors, "amount", self.amount, "0");
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GoalAllocationResponse {
    pub ticker: String,
    pub name: Option<String>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub monthly_amount: Decimal,
}

/// One goal's state; the target figures are absent for a RECURRING_INVESTMENT, which reports
/// monthsLeft 0 and isOnTrack true. allocations is always a list.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GoalProgressResponse {
    pub id: i64,
    pub name: String,
    #[serde(rename = "type")]
    pub goal_type: GoalType,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub target_amount: Option<Decimal>,
    pub deadline: Option<NaiveDate>,
    pub created_at: DateTime<Utc>,
    pub history_start_month: Option<String>,
    /// AccountResponse JSON, from the AccountResponses seam.
    pub accounts: Vec<serde_json::Value>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub current_total: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub percent_complete: Option<Decimal>,
    pub months_left: i64,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub monthly_needed: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub avg_monthly_contribution: Option<Decimal>,
    pub is_on_track: bool,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub surplus: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub monthly_amount: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub expected_return: Option<Decimal>,
    pub start_date: Option<NaiveDate>,
    pub end_date: Option<NaiveDate>,
    pub allocations: Vec<GoalAllocationResponse>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GoalMonthEntryResponse {
    pub year_month: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub objective: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub actual: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub manual_actual: Option<Decimal>,
    #[serde(
        rename = "override",
        with = "rust_decimal::serde::arbitrary_precision_option"
    )]
    pub override_amount: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub effective: Option<Decimal>,
}
