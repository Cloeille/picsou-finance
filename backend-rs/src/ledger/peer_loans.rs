//! Peer-loan arithmetic, as com.picsou.service.PeerLoanCalculator computes it: remaining
//! capital, simple interest and the optional forecast. Pure functions; only the inputs live in
//! the database.
//!
//! Interest is simple and accrues on the outstanding capital at actual/365. A PRINCIPAL or
//! INTEREST row pays the accrued interest first, then capital; a WRITE_OFF reduces capital
//! because the member gave up on it.

use chrono::NaiveDate;
use rust_decimal::prelude::ToPrimitive;
use rust_decimal::{Decimal, MathematicalOps};
use serde::Serialize;

use crate::ledger::decimal::{
    CEILING, HALF_UP, Scaled, decimal64, divide, divide64, multiply64, pow64, set_scale,
};
use crate::ledger::loans::{annuity, months_between};
use crate::ledger::model::{PeerLoanRepayment, PeerLoanRepaymentKind, PeerLoanTerms};

const DAYS_PER_YEAR: Decimal = Decimal::from_parts(365, 0, 0, false, 0);
const TWELVE: Decimal = Decimal::from_parts(12, 0, 0, false, 0);
const MONEY_SCALE: u32 = 2;
const INTERNAL_SCALE: u32 = 8;
/// A century of monthly instalments: the same ceiling the request validation applies.
pub const MAX_MONTHS: i32 = 1200;

/// A simulation the inputs cannot answer; the messages are the Java IllegalArgumentException ones.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum PeerLoanError {
    #[error("months must be greater than zero")]
    MonthsNotPositive,
    #[error("A simulation cannot exceed {MAX_MONTHS} months")]
    TooManyMonths,
    #[error("monthlyPayment must be greater than zero")]
    PaymentNotPositive,
    #[error(
        "The monthly payment does not cover the monthly interest: the loan would never be repaid"
    )]
    InterestNotCovered,
    #[error("The monthly payment does not amortise the loan")]
    NotAmortising,
    #[error("The monthly payment is too small: repayment would take more than {MAX_MONTHS} months")]
    TermUnreachable,
    /// Rust only: a result BigDecimal holds but Decimal cannot (above 7.9 × 10^28).
    #[error("The amounts are too large to compute")]
    Overflow,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Computed {
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub principal_remaining: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub interest_accrued: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub interest_received: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub principal_paid: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub written_off: Decimal,
    pub splits: Vec<PaymentSplit>,
}

/// How one ledger row was applied: interest first, then capital (or a write-off).
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PaymentSplit {
    pub repayment_id: i64,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub interest: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub capital: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub written_off: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Plan {
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub scheduled_remaining: Decimal,
    pub installments_behind: Option<i32>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub planned_interest: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Simulation {
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub monthly_payment: Decimal,
    pub months: i32,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub total_interest: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub total_cost: Decimal,
}

/// Replays the repayments in date order (then id) and accrues interest up to as_of.
pub fn compute(
    terms: &PeerLoanTerms,
    repayments: &[PeerLoanRepayment],
    as_of: NaiveDate,
) -> Computed {
    let mut outstanding = terms.principal;
    let mut accrued = Decimal::ZERO;
    let mut interest_received = Decimal::ZERO;
    let mut principal_paid = Decimal::ZERO;
    let mut written_off = Decimal::ZERO;
    let mut cursor = terms.lent_at;
    let rate = rate(terms.annual_rate);

    let mut ordered: Vec<&PeerLoanRepayment> = repayments.iter().collect();
    ordered.sort_by_key(|r| (r.paid_on, r.id));

    let mut splits = Vec::with_capacity(ordered.len());
    for repayment in ordered {
        let event_date = repayment.paid_on.max(cursor);
        accrued += accrue(outstanding, rate, cursor, event_date);
        let amount = repayment.amount;
        match repayment.kind {
            // One payment, whatever its label: the accrued interest is paid first, then the
            // remainder reduces the capital, the banking convention.
            PeerLoanRepaymentKind::Principal | PeerLoanRepaymentKind::Interest => {
                let to_interest = amount.min(accrued);
                accrued -= to_interest;
                interest_received += to_interest;
                let to_principal = (amount - to_interest).min(outstanding);
                outstanding -= to_principal;
                principal_paid += to_principal;
                splits.push(PaymentSplit {
                    repayment_id: repayment.id,
                    interest: scale(to_interest),
                    capital: scale(to_principal),
                    written_off: scale(Decimal::ZERO),
                });
            }
            PeerLoanRepaymentKind::WriteOff => {
                let to_write_off = amount.min(outstanding);
                outstanding -= to_write_off;
                written_off += to_write_off;
                splits.push(PaymentSplit {
                    repayment_id: repayment.id,
                    interest: scale(Decimal::ZERO),
                    capital: scale(Decimal::ZERO),
                    written_off: scale(to_write_off),
                });
            }
        }
        cursor = event_date;
    }
    accrued += accrue(outstanding, rate, cursor, as_of);

    Computed {
        principal_remaining: scale(outstanding.max(Decimal::ZERO)),
        interest_accrued: scale(accrued.max(Decimal::ZERO)),
        interest_received: scale(interest_received),
        principal_paid: scale(principal_paid),
        written_off: scale(written_off),
        splits,
    }
}

/// The nominal still owed, the one figure net worth uses. Interest is left out: it was never
/// received, and counting it would repeat the optimism the no-provision decision rejects.
pub fn principal_remaining(terms: &PeerLoanTerms, repayments: &[PeerLoanRepayment]) -> Decimal {
    compute(terms, repayments, terms.lent_at).principal_remaining
}

/// The forecast at as_of, or None when no plan is set. It never feeds the balance; it only
/// draws the dashed curve and the drift.
pub fn plan(
    terms: &PeerLoanTerms,
    actual_remaining: Option<Decimal>,
    as_of: NaiveDate,
) -> Result<Option<Plan>, PeerLoanError> {
    let (Some(monthly), Some(planned_months)) =
        (terms.planned_monthly_amount, terms.planned_months)
    else {
        return Ok(None);
    };
    let principal = terms.principal;
    // Clamped on read: a value stored before the request bound existed must not make a read
    // walk a million-month annuity.
    let total_months = planned_months.min(MAX_MONTHS);
    let monthly_rate = divide64(rate(terms.annual_rate), TWELVE);
    let elapsed = months_between(terms.lent_at, as_of);
    let paid_months = elapsed.min(i64::from(total_months)).max(0) as u32;

    let scheduled = if monthly_rate.is_zero() {
        principal - monthly * Decimal::from(paid_months)
    } else {
        balance_after(
            principal,
            monthly,
            monthly_rate,
            pow64(Decimal::ONE + monthly_rate, paid_months),
        )?
    };
    let scheduled = scale(scheduled.max(Decimal::ZERO));

    // The total interest the whole plan implies, not the part accrued so far: a one-month plan
    // at 12 %/month on 10 000 € answers 1 200 € at once.
    let planned_interest = (monthly * Decimal::from(total_months) - principal).max(Decimal::ZERO);

    let installments_behind = (monthly > Decimal::ZERO).then(|| {
        let behind = divide(
            actual_remaining.unwrap_or_default() - scheduled,
            monthly,
            0,
            CEILING,
        );
        // BigDecimal.intValue keeps the low 32 bits.
        behind.mantissa() as i32
    });
    Ok(Some(Plan {
        scheduled_remaining: scheduled,
        installments_behind,
        planned_interest: scale(planned_interest),
    }))
}

/// Duration → monthly payment: the standard annuity, with a zero-rate shortcut.
pub fn simulate_by_months(
    principal: Option<Decimal>,
    annual_rate: Option<Decimal>,
    months: i32,
) -> Result<Simulation, PeerLoanError> {
    if months <= 0 {
        return Err(PeerLoanError::MonthsNotPositive);
    }
    if months > MAX_MONTHS {
        return Err(PeerLoanError::TooManyMonths);
    }
    let p = principal.unwrap_or_default();
    let monthly_rate = divide64(rate(annual_rate), TWELVE);
    let monthly = if monthly_rate.is_zero() {
        divide(p, Decimal::from(months), INTERNAL_SCALE, HALF_UP)
    } else {
        annuity(p, monthly_rate, months.unsigned_abs())
    };
    let total = monthly * Decimal::from(months);
    Ok(Simulation {
        monthly_payment: scale(monthly),
        months,
        total_interest: scale((total - p).max(Decimal::ZERO)),
        total_cost: scale(total),
    })
}

/// Monthly payment → duration. Refused when the payment does not cover the monthly interest:
/// such a plan never amortises, and a huge month count would look like an answer.
pub fn simulate_by_payment(
    principal: Option<Decimal>,
    annual_rate: Option<Decimal>,
    monthly_payment: Option<Decimal>,
) -> Result<Simulation, PeerLoanError> {
    let monthly_payment = monthly_payment
        .filter(|m| *m > Decimal::ZERO)
        .ok_or(PeerLoanError::PaymentNotPositive)?;
    let p = principal.unwrap_or_default();
    let monthly_rate = divide64(rate(annual_rate), TWELVE);
    if monthly_rate.is_zero() {
        let months = months_for(divide(p, monthly_payment, INTERNAL_SCALE, HALF_UP))?;
        // No interest: the last payment is whatever is left, so the cost is exactly the principal.
        return Ok(Simulation {
            monthly_payment: scale(monthly_payment),
            months,
            total_interest: scale(Decimal::ZERO),
            total_cost: scale(p),
        });
    }

    let interest_only = multiply64(p, monthly_rate);
    if monthly_payment <= interest_only {
        return Err(PeerLoanError::InterestNotCovered);
    }
    let ratio = Decimal::ONE - divide64(interest_only, monthly_payment);
    let base = Decimal::ONE + monthly_rate;
    if ratio <= Decimal::ZERO {
        return Err(PeerLoanError::NotAmortising);
    }
    // Java reads Math.log(1 + r) as NaN below zero, which only the term check then refuses.
    if base < Decimal::ZERO {
        return Err(PeerLoanError::TermUnreachable);
    }
    if base <= Decimal::ONE {
        return Err(PeerLoanError::NotAmortising);
    }
    let months = months_for(-ratio.ln() / base.ln())?;

    // The final installment clears the balance left after the preceding ones plus its interest,
    // not a full monthly payment: monthly × months would overstate the cost and the interest.
    let before_final = balance_after(
        p,
        monthly_payment,
        monthly_rate,
        pow64(base, months.unsigned_abs() - 1),
    )?;
    let final_payment = multiply64(before_final, base);
    let total = monthly_payment * Decimal::from(months - 1) + final_payment;
    Ok(Simulation {
        monthly_payment: scale(monthly_payment),
        months,
        total_interest: scale((total - p).max(Decimal::ZERO)),
        total_cost: scale(total),
    })
}

/// Percent of the original principal actually repaid (write-offs excluded).
pub fn repaid_pct(principal: Option<Decimal>, principal_paid: Option<Decimal>) -> Decimal {
    let p = principal.unwrap_or_default();
    if p <= Decimal::ZERO {
        return Decimal::ZERO;
    }
    divide(
        principal_paid.unwrap_or_default() * Decimal::ONE_HUNDRED,
        p,
        MONEY_SCALE,
        HALF_UP,
    )
}

/// The rate rounded to the six decimals the column stores: the dialog sends a binary float, so
/// 1.1 % arrives as 0.011000000000000001, and the backend owns the rounding.
pub fn rate(value: Option<Decimal>) -> Decimal {
    value.map_or(Decimal::ZERO, |v| set_scale(v, 6, HALF_UP))
}

/// max(1, ceil(raw)), once the term is known to fit the ceiling. Java derives raw through
/// double and Math.log; Decimal's ln carries more digits, so only a raw term within about
/// 10^-15 of an integer could round up on one side and not the other.
fn months_for(raw: Decimal) -> Result<i32, PeerLoanError> {
    if raw > Decimal::from(MAX_MONTHS) {
        return Err(PeerLoanError::TermUnreachable);
    }
    // Below i32::MIN Java's (int) cast saturates, and max(1, …) answers 1 all the same.
    let months = raw.ceil().to_i32().unwrap_or(1);
    Ok(months.max(1))
}

/// principal·g − monthly·(g − 1)/r floored at zero, g = (1 + r)^n: the capital an annuity of
/// monthly leaves after n payments.
fn balance_after(
    principal: Decimal,
    monthly: Decimal,
    monthly_rate: Decimal,
    growth: Scaled,
) -> Result<Decimal, PeerLoanError> {
    if let Some(g) = growth.to_decimal()
        && let Some(grown) = principal.checked_mul(g)
        && let Some(paid) = monthly
            .checked_mul(g - Decimal::ONE)
            .and_then(|v| v.checked_div(monthly_rate))
        && let Some(balance) = decimal64(grown).checked_sub(decimal64(paid))
    {
        return Ok(balance.max(Decimal::ZERO));
    }
    // g is past Decimal's range: the −1 and the 10^exponent both vanish at sixteen digits, so the
    // sign of the mantissas' difference decides. Negative floors at zero as Java's does.
    let m = growth.mantissa;
    let lead = decimal64(principal * m) - decimal64(monthly * m / monthly_rate);
    if lead > Decimal::ZERO {
        Err(PeerLoanError::Overflow)
    } else {
        Ok(Decimal::ZERO)
    }
}

fn accrue(outstanding: Decimal, annual_rate: Decimal, from: NaiveDate, to: NaiveDate) -> Decimal {
    let days = (to - from).num_days();
    if outstanding <= Decimal::ZERO || annual_rate <= Decimal::ZERO || days <= 0 {
        return Decimal::ZERO;
    }
    divide(
        multiply64(outstanding, annual_rate) * Decimal::from(days),
        DAYS_PER_YEAR,
        INTERNAL_SCALE,
        HALF_UP,
    )
}

fn scale(value: Decimal) -> Decimal {
    set_scale(value, MONEY_SCALE, HALF_UP)
}
