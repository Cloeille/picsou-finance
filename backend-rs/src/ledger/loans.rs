//! Loan amortisation, as com.picsou.service.LoanAmortizationService computes it: pure functions
//! over a Debt row, the date of the day passed in.

use chrono::{Datelike, NaiveDate};
use rust_decimal::Decimal;
use serde::Serialize;

use crate::ledger::decimal::{HALF_UP, decimal64, divide, divide64, multiply64, pow64, set_scale};
use crate::ledger::model::Debt;

const TWELVE: Decimal = Decimal::from_parts(12, 0, 0, false, 0);

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

/// The full schedule from the start date, and the summary as of today.
pub fn compute(debt: &Debt, today: NaiveDate) -> LoanScheduleResponse {
    let principal = debt.borrowed_amount;
    let insurance = debt.insurance_monthly.unwrap_or_default();
    let file_fees = debt.file_fees.unwrap_or_default();
    let monthly_rate = divide64(debt.interest_rate.unwrap_or_default(), TWELVE);
    let term = term(debt.start_date, debt.end_date);
    let total_installments = term.map_or(0, |(_, _, total)| total);

    // The whole installment, insurance included: the schedule takes the insurance back out of it.
    let monthly_payment = whole_installment(debt, monthly_rate, total_installments);

    let mut schedule = Vec::with_capacity(total_installments as usize);
    let mut paid_installments = 0;
    let mut total_interest = Decimal::ZERO;
    let mut total_insurance = Decimal::ZERO;
    let mut capital_repaid = Decimal::ZERO;
    let mut interest_repaid = Decimal::ZERO;
    let mut insurance_repaid = Decimal::ZERO;

    if let Some((start, end, total)) = term {
        paid_installments = count_paid_installments(start, end, today, total);
        let mut remaining = principal;
        for i in 1..=total {
            let interest_part = set_scale(multiply64(remaining, monthly_rate), 2, HALF_UP);
            let mut capital_part = monthly_payment - insurance - interest_part;
            // The last installment absorbs the rounding so the balance ends exactly at zero.
            if i == total || capital_part > remaining {
                capital_part = remaining;
            }
            let total_payment = capital_part + interest_part + insurance;
            let new_remaining = (remaining - capital_part).max(Decimal::ZERO);
            schedule.push(LoanInstallment {
                number: i,
                date: installment_date(start, end, i),
                capital: set_scale(capital_part, 2, HALF_UP),
                interest: interest_part,
                insurance: set_scale(insurance, 2, HALF_UP),
                total_payment: set_scale(total_payment, 2, HALF_UP),
                remaining_balance: set_scale(new_remaining, 2, HALF_UP),
            });
            total_interest += interest_part;
            total_insurance += insurance;
            if i <= paid_installments {
                capital_repaid += capital_part;
                interest_repaid += interest_part;
                insurance_repaid += insurance;
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
    let capital_repaid = set_scale(capital_repaid, 2, HALF_UP);
    let interest_repaid = set_scale(interest_repaid, 2, HALF_UP);
    let insurance_repaid = set_scale(insurance_repaid, 2, HALF_UP);
    let capital_repaid_pct = if principal.is_zero() {
        Decimal::ZERO
    } else {
        divide(capital_repaid * Decimal::ONE_HUNDRED, principal, 2, HALF_UP)
    };

    // The UI shows the split of the most recently paid installment, or of the first one.
    let reference = schedule.get(paid.min(schedule.len()).saturating_sub(1));

    let summary = LoanSummary {
        total_installments,
        paid_installments,
        remaining_installments: total_installments - paid_installments,
        end_date: debt.end_date,
        monthly_payment: set_scale(monthly_payment, 2, HALF_UP),
        monthly_capital: reference.map_or(Decimal::ZERO, |row| row.capital),
        monthly_interest: reference.map_or(Decimal::ZERO, |row| row.interest),
        monthly_insurance: set_scale(insurance, 2, HALF_UP),
        total_cost: set_scale(
            principal + total_interest + total_insurance + file_fees,
            2,
            HALF_UP,
        ),
        total_capital_cost: set_scale(principal, 2, HALF_UP),
        total_interest_cost: set_scale(total_interest, 2, HALF_UP),
        total_insurance_cost: set_scale(total_insurance, 2, HALF_UP),
        file_fees: set_scale(file_fees, 2, HALF_UP),
        total_repaid: capital_repaid + interest_repaid + insurance_repaid,
        capital_repaid,
        interest_repaid,
        insurance_repaid,
        remaining_balance,
        capital_repaid_pct,
    };
    LoanScheduleResponse { summary, schedule }
}

/// The stored monthly payment, or the computed annuity plus insurance as compute() uses it;
/// None when the dates cannot carry a schedule. Does not build the schedule.
pub fn resolve_monthly_payment(debt: &Debt) -> Option<Decimal> {
    if let Some(entered) = debt.monthly_payment {
        return Some(set_scale(entered, 2, HALF_UP));
    }
    let (start, end) = debt.start_date.zip(debt.end_date)?;
    if end <= start {
        return None;
    }
    let monthly_rate = divide64(debt.interest_rate.unwrap_or_default(), TWELVE);
    let n = months_between(start, end).max(0) as u32;
    Some(set_scale(
        whole_installment(debt, monthly_rate, n),
        2,
        HALF_UP,
    ))
}

/// Capital still owed on that date, positive; the caller negates it for net worth.
pub fn compute_remaining_balance(debt: &Debt, today: NaiveDate) -> Decimal {
    compute(debt, today).summary.remaining_balance
}

/// Whether the debt has a start and a later end date. Without them the schedule is empty and
/// compute_remaining_balance can only answer the borrowed amount, so callers use the balance
/// stored on the account instead.
pub fn has_schedule(debt: &Debt) -> bool {
    term(debt.start_date, debt.end_date).is_some()
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

/// Whole months between the two dates' months, as ChronoUnit.MONTHS.between on YearMonth.
pub(crate) fn months_between(from: NaiveDate, to: NaiveDate) -> i64 {
    (i64::from(to.year()) * 12 + i64::from(to.month0()))
        - (i64::from(from.year()) * 12 + i64::from(from.month0()))
}

/// The start date, the end date and the installment count, when there is at least one.
fn term(start: Option<NaiveDate>, end: Option<NaiveDate>) -> Option<(NaiveDate, NaiveDate, u32)> {
    let (start, end) = start.zip(end)?;
    if end <= start {
        return None;
    }
    let total = u32::try_from(months_between(start, end)).ok()?;
    (total > 0).then_some((start, end, total))
}

/// A user-entered payment already includes the insurance; a computed annuity does not, so it is
/// added, or every installment would under-amortise the capital by the insurance.
fn whole_installment(debt: &Debt, monthly_rate: Decimal, n: u32) -> Decimal {
    match debt.monthly_payment {
        Some(entered) => entered,
        None => {
            monthly_payment(debt.borrowed_amount, monthly_rate, n)
                + debt.insurance_monthly.unwrap_or_default()
        }
    }
}

/// M = P·r / (1 − (1 + r)^−n), or P / n for an interest-free loan.
fn monthly_payment(principal: Decimal, monthly_rate: Decimal, n: u32) -> Decimal {
    if n == 0 {
        Decimal::ZERO
    } else if monthly_rate.is_zero() {
        divide(principal, Decimal::from(n), 8, HALF_UP)
    } else {
        annuity(principal, monthly_rate, n)
    }
}

/// Installment i falls i months after the start, on the due day clamped to its own month. A
/// start on the last day of its month may itself be a clamp (a loan due on the 31st anchored on
/// 28 February): when the end date falls on a later day, that day is the due day.
fn installment_date(start: NaiveDate, end: NaiveDate, i: u32) -> NaiveDate {
    let due_day =
        if start.day() == days_in_month(start.year(), start.month()) && end.day() > start.day() {
            end.day()
        } else {
            start.day()
        };
    let months = i64::from(start.year()) * 12 + i64::from(start.month0()) + i64::from(i);
    let year =
        i32::try_from(months.div_euclid(12)).expect("installment year within chrono's range");
    let month = u32::try_from(months.rem_euclid(12)).expect("a month index") + 1;
    let day = due_day.min(days_in_month(year, month));
    NaiveDate::from_ymd_opt(year, month, day).expect("a clamped day exists")
}

/// An installment counts as paid from its due date, not from the 1st of its month; from the end
/// date on, the loan is repaid even when it falls before the last due day.
fn count_paid_installments(start: NaiveDate, end: NaiveDate, today: NaiveDate, total: u32) -> u32 {
    if today >= end {
        return total;
    }
    let mut paid = 0;
    while paid < total && installment_date(start, end, paid + 1) <= today {
        paid += 1;
    }
    paid
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
