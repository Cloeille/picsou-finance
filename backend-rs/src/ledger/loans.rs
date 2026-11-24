//! Loan amortisation, as com.picsou.service.LoanAmortizationService computes it: pure functions
//! over a Debt row, the date of the day passed in.

use std::fmt;

use chrono::{Datelike, NaiveDate};
use rust_decimal::{Decimal, RoundingStrategy};
use serde::Serialize;

use crate::ledger::decimal::{HALF_UP, decimal64, divide, divide64, multiply64, pow64, set_scale};
use crate::ledger::model::Debt;

const TWELVE: Decimal = Decimal::from_parts(12, 0, 0, false, 0);

/// Hard upper bound on generated monthly installments (100 years), preventing unbounded allocations.
pub const MAX_INSTALLMENTS: u32 = 1200;

/// Largest intermediate amount kept before scaling to 2 decimal places (`1_000_000 * 2^64 ≈ 1.84e25`,
/// comfortably below `Decimal::MAX / 1000`).
const MAX_INTERMEDIATE: Decimal = Decimal::from_parts(0, 0, 1_000_000, false, 0);

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LoanScheduleError {
    TermTooLong { max_installments: u32 },
    Overflow,
    DateOutOfRange,
}

impl fmt::Display for LoanScheduleError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::TermTooLong { max_installments } => {
                write!(f, "Loan term exceeds maximum of {max_installments} months")
            }
            Self::Overflow => write!(
                f,
                "Loan schedule overflows: monthly payment does not cover interest over this term"
            ),
            Self::DateOutOfRange => write!(f, "Loan installment date is out of supported range"),
        }
    }
}

impl std::error::Error for LoanScheduleError {}

/// One row of the amortisation table.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LoanInstallment {
    pub number: u32,
    pub date: NaiveDate,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub capital: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub interest: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub insurance: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub total_payment: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub remaining_balance: Decimal,
}

/// The loan page's figures at a given date.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LoanSummary {
    pub total_installments: u32,
    pub paid_installments: u32,
    pub remaining_installments: u32,
    pub end_date: Option<NaiveDate>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub monthly_payment: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub monthly_capital: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub monthly_interest: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub monthly_insurance: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub total_cost: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub total_capital_cost: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub total_interest_cost: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub total_insurance_cost: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub file_fees: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub total_repaid: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub capital_repaid: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub interest_repaid: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub insurance_repaid: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub remaining_balance: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub capital_repaid_pct: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LoanScheduleResponse {
    pub summary: LoanSummary,
    pub schedule: Vec<LoanInstallment>,
}

/// The full schedule from the start date, and the summary as of today, rejecting terms above
/// [`MAX_INSTALLMENTS`] or schedules that overflow `Decimal` arithmetic.
pub fn try_compute(debt: &Debt, today: NaiveDate) -> Result<LoanScheduleResponse, LoanScheduleError> {
    let principal = debt.borrowed_amount;
    let insurance = debt.insurance_monthly.unwrap_or_default();
    let file_fees = debt.file_fees.unwrap_or_default();
    let monthly_rate = divide64(debt.interest_rate.unwrap_or_default(), TWELVE);
    let term = checked_term(debt.start_date, debt.end_date)?;
    let total_installments = term.map_or(0, |(_, _, total)| total);

    // The whole installment, insurance included: the schedule takes the insurance back out of it.
    let monthly_payment = checked_whole_installment(debt, monthly_rate, total_installments)?;

    let mut schedule = Vec::with_capacity(total_installments as usize);
    let mut paid_installments = 0;
    let mut total_interest = Decimal::ZERO;
    let mut total_insurance = Decimal::ZERO;
    let mut capital_repaid = Decimal::ZERO;
    let mut interest_repaid = Decimal::ZERO;
    let mut insurance_repaid = Decimal::ZERO;

    if let Some((start, end, total)) = term {
        paid_installments = count_paid_installments(start, end, today, total)?;
        let mut remaining = principal;
        let scaled_insurance = checked_set_scale(insurance, 2, HALF_UP)?;
        for i in 1..=total {
            let interest_part =
                checked_set_scale(checked_multiply64(remaining, monthly_rate)?, 2, HALF_UP)?;
            let mut capital_part = monthly_payment
                .checked_sub(insurance)
                .and_then(|v| v.checked_sub(interest_part))
                .ok_or(LoanScheduleError::Overflow)?;
            // The last installment absorbs the rounding so the balance ends exactly at zero.
            if i == total || capital_part > remaining {
                capital_part = remaining;
            }
            let total_payment = capital_part
                .checked_add(interest_part)
                .and_then(|v| v.checked_add(insurance))
                .ok_or(LoanScheduleError::Overflow)?;
            let new_remaining = remaining
                .checked_sub(capital_part)
                .ok_or(LoanScheduleError::Overflow)?
                .max(Decimal::ZERO);
            schedule.push(LoanInstallment {
                number: i,
                date: checked_installment_date(start, end, i)?,
                capital: checked_set_scale(capital_part, 2, HALF_UP)?,
                interest: interest_part,
                insurance: scaled_insurance,
                total_payment: checked_set_scale(total_payment, 2, HALF_UP)?,
                remaining_balance: checked_set_scale(new_remaining, 2, HALF_UP)?,
            });
            total_interest = total_interest
                .checked_add(interest_part)
                .ok_or(LoanScheduleError::Overflow)?;
            total_insurance = total_insurance
                .checked_add(insurance)
                .ok_or(LoanScheduleError::Overflow)?;
            if i <= paid_installments {
                capital_repaid = capital_repaid
                    .checked_add(capital_part)
                    .ok_or(LoanScheduleError::Overflow)?;
                interest_repaid = interest_repaid
                    .checked_add(interest_part)
                    .ok_or(LoanScheduleError::Overflow)?;
                insurance_repaid = insurance_repaid
                    .checked_add(insurance)
                    .ok_or(LoanScheduleError::Overflow)?;
            }
            remaining = new_remaining;
        }
    }

    let paid = paid_installments as usize;
    let remaining_balance = if paid == 0 {
        principal
    } else if paid >= schedule.len() {
        Decimal::ZERO
    } else {
        schedule[paid - 1].remaining_balance
    };
    let capital_repaid = checked_set_scale(capital_repaid, 2, HALF_UP)?;
    let interest_repaid = checked_set_scale(interest_repaid, 2, HALF_UP)?;
    let insurance_repaid = checked_set_scale(insurance_repaid, 2, HALF_UP)?;
    let capital_repaid_pct = if principal.is_zero() {
        Decimal::ZERO
    } else {
        let scaled_repaid = capital_repaid
            .checked_mul(Decimal::ONE_HUNDRED)
            .ok_or(LoanScheduleError::Overflow)?;
        checked_divide(scaled_repaid, principal, 2, HALF_UP)?
    };

    // The UI shows the split of the most recently paid installment, or of the first one.
    let reference = schedule.get(paid.min(schedule.len()).saturating_sub(1));

    let total_cost_raw = principal
        .checked_add(total_interest)
        .and_then(|v| v.checked_add(total_insurance))
        .and_then(|v| v.checked_add(file_fees))
        .ok_or(LoanScheduleError::Overflow)?;
    let total_repaid = capital_repaid
        .checked_add(interest_repaid)
        .and_then(|v| v.checked_add(insurance_repaid))
        .ok_or(LoanScheduleError::Overflow)?;

    let summary = LoanSummary {
        total_installments,
        paid_installments,
        remaining_installments: total_installments - paid_installments,
        end_date: debt.end_date,
        monthly_payment: checked_set_scale(monthly_payment, 2, HALF_UP)?,
        monthly_capital: reference.map_or(Decimal::ZERO, |row| row.capital),
        monthly_interest: reference.map_or(Decimal::ZERO, |row| row.interest),
        monthly_insurance: checked_set_scale(insurance, 2, HALF_UP)?,
        total_cost: checked_set_scale(total_cost_raw, 2, HALF_UP)?,
        total_capital_cost: checked_set_scale(principal, 2, HALF_UP)?,
        total_interest_cost: checked_set_scale(total_interest, 2, HALF_UP)?,
        total_insurance_cost: checked_set_scale(total_insurance, 2, HALF_UP)?,
        file_fees: checked_set_scale(file_fees, 2, HALF_UP)?,
        total_repaid,
        capital_repaid,
        interest_repaid,
        insurance_repaid,
        remaining_balance,
        capital_repaid_pct,
    };
    Ok(LoanScheduleResponse { summary, schedule })
}

/// The full schedule from the start date, and the summary as of today. Returns an empty schedule
/// anchored at the borrowed amount when the terms cannot be scheduled safely.
pub fn compute(debt: &Debt, today: NaiveDate) -> LoanScheduleResponse {
    try_compute(debt, today).unwrap_or_else(|_| empty_schedule(debt))
}

fn empty_schedule(debt: &Debt) -> LoanScheduleResponse {
    let principal = debt.borrowed_amount;
    let insurance = debt.insurance_monthly.unwrap_or_default();
    let file_fees = debt.file_fees.unwrap_or_default();
    let monthly_payment = debt.monthly_payment.unwrap_or_default();
    let zero = set_scale(Decimal::ZERO, 2, HALF_UP);
    let total_cost = principal
        .checked_add(file_fees)
        .and_then(|v| checked_set_scale(v, 2, HALF_UP).ok())
        .unwrap_or(principal);
    LoanScheduleResponse {
        summary: LoanSummary {
            total_installments: 0,
            paid_installments: 0,
            remaining_installments: 0,
            end_date: debt.end_date,
            monthly_payment: checked_set_scale(monthly_payment, 2, HALF_UP).unwrap_or(zero),
            monthly_capital: Decimal::ZERO,
            monthly_interest: Decimal::ZERO,
            monthly_insurance: checked_set_scale(insurance, 2, HALF_UP).unwrap_or(zero),
            total_cost,
            total_capital_cost: checked_set_scale(principal, 2, HALF_UP).unwrap_or(principal),
            total_interest_cost: zero,
            total_insurance_cost: zero,
            file_fees: checked_set_scale(file_fees, 2, HALF_UP).unwrap_or(zero),
            total_repaid: zero,
            capital_repaid: zero,
            interest_repaid: zero,
            insurance_repaid: zero,
            remaining_balance: principal,
            capital_repaid_pct: Decimal::ZERO,
        },
        schedule: Vec::new(),
    }
}

/// The stored monthly payment, or the computed annuity plus insurance as compute() uses it;
/// None when the dates cannot carry a schedule. Does not build the schedule.
pub fn resolve_monthly_payment(debt: &Debt) -> Option<Decimal> {
    if let Some(entered) = debt.monthly_payment {
        return checked_set_scale(entered, 2, HALF_UP).ok();
    }
    let (_, _, n) = checked_term(debt.start_date, debt.end_date).ok()??;
    let monthly_rate = divide64(debt.interest_rate.unwrap_or_default(), TWELVE);
    let installment = checked_whole_installment(debt, monthly_rate, n).ok()?;
    checked_set_scale(installment, 2, HALF_UP).ok()
}

/// Capital still owed on that date, positive; the caller negates it for net worth.
pub fn compute_remaining_balance(debt: &Debt, today: NaiveDate) -> Decimal {
    try_compute(debt, today).map_or(debt.borrowed_amount, |out| out.summary.remaining_balance)
}

/// Whether the debt has a start and a later end date within the schedule cap. Without them the
/// schedule is empty and compute_remaining_balance can only answer the borrowed amount, so callers
/// use the balance stored on the account instead.
pub fn has_schedule(debt: &Debt) -> bool {
    matches!(checked_term(debt.start_date, debt.end_date), Ok(Some(_)))
}

/// P·r·g / (g − 1) to 8 decimals, g = (1 + r)^n, r non-zero: the annuity both
/// LoanAmortizationService and PeerLoanCalculator write the same way.
pub(crate) fn annuity(principal: Decimal, monthly_rate: Decimal, n: u32) -> Decimal {
    let growth = pow64(Decimal::ONE + monthly_rate, n);
    let interest = multiply64(principal, monthly_rate);
    if let Some(g) = growth.to_decimal()
        && let Some(numerator) = interest.checked_mul(g)
    {
        return divide(decimal64(numerator), g - Decimal::ONE, 8, HALF_UP);
    }
    // g is past Decimal's range, so g − 1 equals g to far more digits than the quotient keeps:
    // the power of ten cancels and only the mantissas divide.
    divide(
        decimal64(interest * growth.mantissa),
        growth.mantissa,
        8,
        HALF_UP,
    )
}

fn checked_annuity(
    principal: Decimal,
    monthly_rate: Decimal,
    n: u32,
) -> Result<Decimal, LoanScheduleError> {
    let growth = pow64(Decimal::ONE + monthly_rate, n);
    let interest = checked_multiply64(principal, monthly_rate)?;
    if let Some(g) = growth.to_decimal()
        && let Some(numerator) = interest.checked_mul(g)
        && numerator.abs() <= MAX_INTERMEDIATE
    {
        let denom = g - Decimal::ONE;
        return checked_divide(decimal64(numerator), denom, 8, HALF_UP);
    }
    if let Some(scaled) = interest.checked_mul(growth.mantissa)
        && scaled.abs() <= MAX_INTERMEDIATE
    {
        return checked_divide(decimal64(scaled), growth.mantissa, 8, HALF_UP);
    }
    checked_set_scale(interest, 8, HALF_UP)
}

/// Whole months between the two dates' months, as ChronoUnit.MONTHS.between on YearMonth.
pub(crate) fn months_between(from: NaiveDate, to: NaiveDate) -> i64 {
    (i64::from(to.year()) * 12 + i64::from(to.month0()))
        - (i64::from(from.year()) * 12 + i64::from(from.month0()))
}

/// The start date, the end date and the installment count, when there is at least one and it fits
/// inside [`MAX_INSTALLMENTS`].
fn checked_term(
    start: Option<NaiveDate>,
    end: Option<NaiveDate>,
) -> Result<Option<(NaiveDate, NaiveDate, u32)>, LoanScheduleError> {
    let Some((start, end)) = start.zip(end) else {
        return Ok(None);
    };
    if end <= start {
        return Ok(None);
    }
    let months = months_between(start, end);
    if months <= 0 {
        return Ok(None);
    }
    if months > i64::from(MAX_INSTALLMENTS) {
        return Err(LoanScheduleError::TermTooLong {
            max_installments: MAX_INSTALLMENTS,
        });
    }
    Ok(Some((start, end, months as u32)))
}

/// A user-entered payment already includes the insurance; a computed annuity does not, so it is
/// added, or every installment would under-amortise the capital by the insurance.
fn checked_whole_installment(
    debt: &Debt,
    monthly_rate: Decimal,
    n: u32,
) -> Result<Decimal, LoanScheduleError> {
    match debt.monthly_payment {
        Some(entered) => Ok(entered),
        None => checked_monthly_payment(debt.borrowed_amount, monthly_rate, n)?
            .checked_add(debt.insurance_monthly.unwrap_or_default())
            .ok_or(LoanScheduleError::Overflow),
    }
}

/// M = P·r / (1 − (1 + r)^−n), or P / n for an interest-free loan.
fn checked_monthly_payment(
    principal: Decimal,
    monthly_rate: Decimal,
    n: u32,
) -> Result<Decimal, LoanScheduleError> {
    if n == 0 {
        Ok(Decimal::ZERO)
    } else if monthly_rate.is_zero() {
        checked_divide(principal, Decimal::from(n), 8, HALF_UP)
    } else {
        checked_annuity(principal, monthly_rate, n)
    }
}

fn checked_multiply64(a: Decimal, b: Decimal) -> Result<Decimal, LoanScheduleError> {
    let prod = a.checked_mul(b).ok_or(LoanScheduleError::Overflow)?;
    if prod.abs() > MAX_INTERMEDIATE {
        return Err(LoanScheduleError::Overflow);
    }
    Ok(decimal64(prod))
}

fn checked_divide(
    a: Decimal,
    b: Decimal,
    scale: u32,
    mode: RoundingStrategy,
) -> Result<Decimal, LoanScheduleError> {
    let q = a.checked_div(b).ok_or(LoanScheduleError::Overflow)?;
    checked_set_scale(q, scale, mode)
}

fn checked_set_scale(
    value: Decimal,
    scale: u32,
    mode: RoundingStrategy,
) -> Result<Decimal, LoanScheduleError> {
    let mut rounded = value.round_dp_with_strategy(scale, mode);
    let factor = Decimal::from(10_i64.pow(scale));
    rounded
        .trunc()
        .checked_mul(factor)
        .ok_or(LoanScheduleError::Overflow)?;
    rounded.rescale(scale);
    if rounded.scale() != scale {
        return Err(LoanScheduleError::Overflow);
    }
    Ok(rounded)
}

/// Installment i falls i months after the start, on the due day clamped to its own month. A
/// start on the last day of its month may itself be a clamp (a loan due on the 31st anchored on
/// 28 February): when the end date falls on a later day, that day is the due day.
fn checked_installment_date(
    start: NaiveDate,
    end: NaiveDate,
    i: u32,
) -> Result<NaiveDate, LoanScheduleError> {
    let due_day =
        if start.day() == days_in_month(start.year(), start.month()) && end.day() > start.day() {
            end.day()
        } else {
            start.day()
        };
    let months = i64::from(start.year()) * 12 + i64::from(start.month0()) + i64::from(i);
    let year = i32::try_from(months.div_euclid(12)).map_err(|_| LoanScheduleError::DateOutOfRange)?;
    let month =
        u32::try_from(months.rem_euclid(12)).map_err(|_| LoanScheduleError::DateOutOfRange)? + 1;
    let day = due_day.min(days_in_month(year, month));
    NaiveDate::from_ymd_opt(year, month, day).ok_or(LoanScheduleError::DateOutOfRange)
}

/// An installment counts as paid from its due date, not from the 1st of its month; from the end
/// date on, the loan is repaid even when it falls before the last due day.
fn count_paid_installments(
    start: NaiveDate,
    end: NaiveDate,
    today: NaiveDate,
    total: u32,
) -> Result<u32, LoanScheduleError> {
    if today >= end {
        return Ok(total);
    }
    let mut paid = 0;
    while paid < total && checked_installment_date(start, end, paid + 1)? <= today {
        paid += 1;
    }
    Ok(paid)
}

fn days_in_month(year: i32, month: u32) -> u32 {
    let (next_year, next_month) = if month == 12 {
        (year + 1, 1)
    } else {
        (year, month + 1)
    };
    NaiveDate::from_ymd_opt(next_year, next_month, 1)
        .and_then(|first| first.pred_opt())
        .map_or(31, |last| last.day())
}
