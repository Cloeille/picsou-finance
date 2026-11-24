//! PeerLoanCalculatorTest, plus figures the Java calculator printed for the same inputs.

use chrono::NaiveDate;
use picsou::ledger::model::{
    PeerLoanRatePeriod, PeerLoanRepayment, PeerLoanRepaymentKind, PeerLoanTerms,
};
use picsou::ledger::peer_loans::{self, PeerLoanError};
use rust_decimal::Decimal;

use PeerLoanRepaymentKind::{Interest, Principal, WriteOff};

fn dec(s: &str) -> Decimal {
    s.parse().unwrap()
}

fn day(s: &str) -> NaiveDate {
    s.parse().unwrap()
}

fn terms(principal: &str, rate: Option<&str>, lent_at: &str) -> PeerLoanTerms {
    PeerLoanTerms {
        id: 1,
        account_id: 1,
        counterparty_id: 1,
        principal: dec(principal),
        lent_at: day(lent_at),
        expected_back: None,
        annual_rate: rate.map(dec),
        rate_period: PeerLoanRatePeriod::Annual,
        planned_monthly_amount: None,
        planned_months: None,
        linked_account_id: None,
        lending_transaction_id: None,
        note: None,
        closed_at: None,
    }
}

fn planned(
    principal: &str,
    rate: &str,
    lent_at: &str,
    monthly: &str,
    months: i32,
) -> PeerLoanTerms {
    PeerLoanTerms {
        planned_monthly_amount: Some(dec(monthly)),
        planned_months: Some(months),
        ..terms(principal, Some(rate), lent_at)
    }
}

fn repayment(id: i64, date: &str, amount: &str, kind: PeerLoanRepaymentKind) -> PeerLoanRepayment {
    PeerLoanRepayment {
        id,
        account_id: 1,
        paid_on: day(date),
        amount: dec(amount),
        kind,
        note: None,
        cash_transaction_id: None,
        cash_leg_owned: false,
    }
}

#[test]
fn zero_rate_principal_reduced_by_each_repayment() {
    let out = peer_loans::compute(
        &terms("500", Some("0"), "2026-01-01"),
        &[
            repayment(1, "2026-02-01", "100", Principal),
            repayment(2, "2026-03-01", "50", Principal),
        ],
        day("2026-03-01"),
    );

    assert_eq!(out.principal_remaining.to_string(), "350.00");
    assert_eq!(out.principal_paid.to_string(), "150.00");
    assert_eq!(out.interest_accrued.to_string(), "0.00");
    assert_eq!(out.written_off.to_string(), "0.00");
}

#[test]
fn interest_accrues_at_actual_over365() {
    let out = peer_loans::compute(
        &terms("1000", Some("0.10"), "2026-01-01"),
        &[],
        day("2026-01-11"),
    );

    // 1000 × 10 % × 10 / 365 = 2.7397...
    assert_eq!(out.interest_accrued.to_string(), "2.74");
    assert_eq!(out.principal_remaining.to_string(), "1000.00");
}

#[test]
fn interest_payment_reduces_accrued_and_the_excess_goes_to_capital() {
    let out = peer_loans::compute(
        &terms("1000", Some("0.10"), "2026-01-01"),
        &[repayment(1, "2026-01-11", "10", Interest)],
        day("2026-01-11"),
    );

    assert_eq!(out.interest_received.to_string(), "2.74");
    assert_eq!(out.principal_paid.to_string(), "7.26");
    assert_eq!(out.principal_remaining.to_string(), "992.74");
}

#[test]
fn principal_payment_is_allocated_interest_first() {
    let out = peer_loans::compute(
        &terms("1000", Some("0.10"), "2026-01-01"),
        &[repayment(7, "2026-01-11", "10", Principal)],
        day("2026-01-11"),
    );

    assert_eq!(out.interest_received.to_string(), "2.74");
    assert_eq!(out.principal_paid.to_string(), "7.26");
    assert_eq!(out.principal_remaining.to_string(), "992.74");
    assert_eq!(out.splits.len(), 1);
    assert_eq!(out.splits[0].repayment_id, 7);
    assert_eq!(out.splits[0].interest.to_string(), "2.74");
    assert_eq!(out.splits[0].capital.to_string(), "7.26");
    assert_eq!(out.splits[0].written_off.to_string(), "0.00");
}

#[test]
fn payment_smaller_than_accrued_interest_does_not_amortise() {
    let out = peer_loans::compute(
        &terms("1000", Some("0.10"), "2026-01-01"),
        &[repayment(1, "2026-01-11", "1", Interest)],
        day("2026-01-11"),
    );

    assert_eq!(out.interest_received.to_string(), "1.00");
    assert_eq!(out.principal_paid.to_string(), "0.00");
    assert_eq!(out.principal_remaining.to_string(), "1000.00");
}

#[test]
fn write_off_reduces_the_nominal_and_is_reported_separately() {
    let out = peer_loans::compute(
        &terms("500", Some("0"), "2026-01-01"),
        &[repayment(1, "2026-02-01", "200", WriteOff)],
        day("2026-02-01"),
    );

    assert_eq!(out.written_off.to_string(), "200.00");
    assert_eq!(out.principal_remaining.to_string(), "300.00");
    assert_eq!(out.principal_paid.to_string(), "0.00");
}

#[test]
fn overpayment_clamps_the_balance_at_zero() {
    let out = peer_loans::compute(
        &terms("100", Some("0"), "2026-01-01"),
        &[repayment(1, "2026-02-01", "150", Principal)],
        day("2026-02-01"),
    );

    assert_eq!(out.principal_remaining.to_string(), "0.00");
    assert_eq!(out.principal_paid.to_string(), "100.00");
}

/// Unordered rows, two on one day ordered by id, one dated before the loan: Java's figures.
#[test]
fn repayments_replay_in_date_then_id_order() {
    let t = terms("3000", Some("0.08"), "2026-01-10");
    let rows = [
        repayment(3, "2026-04-01", "300", WriteOff),
        repayment(2, "2026-03-01", "500", Principal),
        repayment(1, "2026-03-01", "20", Interest),
        repayment(4, "2025-12-01", "100", Principal),
    ];

    let out = peer_loans::compute(&t, &rows, day("2026-09-30"));

    assert_eq!(out.principal_remaining.to_string(), "2111.78");
    assert_eq!(out.interest_accrued.to_string(), "100.63");
    assert_eq!(out.interest_received.to_string(), "31.78");
    assert_eq!(out.principal_paid.to_string(), "588.22");
    assert_eq!(out.written_off.to_string(), "300.00");
    let splits: Vec<_> = out
        .splits
        .iter()
        .map(|s| {
            (
                s.repayment_id,
                s.interest.to_string(),
                s.capital.to_string(),
                s.written_off.to_string(),
            )
        })
        .collect();
    let expected = [
        (4, "0.00", "100.00", "0.00"),
        (1, "20.00", "0.00", "0.00"),
        (2, "11.78", "488.22", "0.00"),
        (3, "0.00", "0.00", "300.00"),
    ];
    assert_eq!(
        splits,
        expected.map(|(id, i, c, w)| (id, i.to_owned(), c.to_owned(), w.to_owned()))
    );
    assert_eq!(
        peer_loans::principal_remaining(&t, &rows).to_string(),
        "2111.78"
    );
}

#[test]
fn plan_draws_the_forecast_and_the_drift_in_installments() {
    let t = planned("1200", "0", "2026-01-01", "100", 12);

    let plan = peer_loans::plan(&t, Some(dec("500")), day("2026-07-15"))
        .unwrap()
        .unwrap();

    assert_eq!(plan.scheduled_remaining.to_string(), "600.00");
    assert_eq!(plan.installments_behind, Some(-1));
    assert_eq!(plan.planned_interest.to_string(), "0.00");
}

#[test]
fn plan_with_interest_matches_java() {
    let t = planned("5000", "0.06", "2026-01-01", "200", 30);

    let plan = peer_loans::plan(&t, Some(dec("4000")), day("2027-03-10"))
        .unwrap()
        .unwrap();

    assert_eq!(plan.scheduled_remaining.to_string(), "2468.76");
    assert_eq!(plan.installments_behind, Some(8));
    assert_eq!(plan.planned_interest.to_string(), "1000.00");
}

#[test]
fn plan_is_absent_without_a_planned_amount() {
    assert_eq!(
        peer_loans::plan(&terms("1000", None, "2026-01-01"), None, day("2026-05-01")),
        Ok(None)
    );
}

#[test]
fn planned_interest_is_the_total_interest_of_the_whole_plan() {
    // 10 000 € at 12 %/month over one month → 11 200 € paid, so 1 200 € of interest.
    let t = planned("10000", "1.44", "2026-11-05", "11200", 1);

    let plan = peer_loans::plan(&t, Some(dec("10000")), day("2026-11-05"))
        .unwrap()
        .unwrap();

    assert_eq!(plan.planned_interest.to_string(), "1200.00");
}

#[test]
fn simulate_by_months_matches_the_annuity() {
    let out = peer_loans::simulate_by_months(Some(dec("1200")), Some(Decimal::ZERO), 12).unwrap();
    assert_eq!(out.monthly_payment.to_string(), "100.00");
    assert_eq!(out.months, 12);
    assert_eq!(out.total_interest.to_string(), "0.00");

    let with_interest =
        peer_loans::simulate_by_months(Some(dec("10000")), Some(dec("0.05")), 24).unwrap();
    assert_eq!(with_interest.monthly_payment.to_string(), "438.71");
    assert_eq!(with_interest.total_interest.to_string(), "529.13");
    assert_eq!(with_interest.total_cost.to_string(), "10529.13");
}

#[test]
fn high_short_term_rate_is_accepted() {
    // 120 %/year = 10 %/month: a short-term loan may carry it.
    let out = peer_loans::simulate_by_months(Some(dec("500")), Some(dec("1.20")), 12).unwrap();

    assert_eq!(out.monthly_payment.to_string(), "73.38");
    assert_eq!(out.total_interest.to_string(), "380.58");
    assert_eq!(out.total_cost.to_string(), "880.58");
}

#[test]
fn simulate_by_months_refuses_a_non_positive_duration() {
    assert_eq!(
        peer_loans::simulate_by_months(Some(dec("1000")), None, 0),
        Err(PeerLoanError::MonthsNotPositive)
    );
}

#[test]
fn simulate_by_payment_refuses_a_payment_that_never_amortises() {
    let err = peer_loans::simulate_by_payment(Some(dec("1000")), Some(dec("0.10")), Some(dec("5")))
        .unwrap_err();

    assert_eq!(err, PeerLoanError::InterestNotCovered);
    assert!(err.to_string().contains("never be repaid"));
}

#[test]
fn simulate_by_payment_refuses_a_missing_or_zero_payment() {
    for payment in [None, Some(Decimal::ZERO)] {
        assert_eq!(
            peer_loans::simulate_by_payment(Some(dec("1000")), None, payment),
            Err(PeerLoanError::PaymentNotPositive)
        );
    }
}

#[test]
fn simulate_by_payment_computes_the_duration() {
    let out =
        peer_loans::simulate_by_payment(Some(dec("1200")), Some(Decimal::ZERO), Some(dec("100")))
            .unwrap();

    assert_eq!(out.months, 12);
    assert_eq!(out.monthly_payment.to_string(), "100.00");
    // No interest and a full final instalment: the cost is exactly the principal.
    assert_eq!(out.total_cost.to_string(), "1200.00");
    assert_eq!(out.total_interest.to_string(), "0.00");
}

#[test]
fn simulate_by_payment_counts_the_final_instalment_instead_of_multiplying_the_monthly() {
    let out =
        peer_loans::simulate_by_payment(Some(dec("1200")), Some(dec("0.12")), Some(dec("110")))
            .unwrap();

    assert_eq!(out.months, 12);
    // 11 payments of 110 leave ~66.45, whose final payment is ~67.11, not another full 110.
    assert_eq!(out.total_cost.to_string(), "1277.11");
    assert_eq!(out.total_interest.to_string(), "77.11");

    let longer =
        peer_loans::simulate_by_payment(Some(dec("5000")), Some(dec("0.07")), Some(dec("150")))
            .unwrap();
    assert_eq!(longer.months, 38);
    assert_eq!(longer.total_interest.to_string(), "576.29");
    assert_eq!(longer.total_cost.to_string(), "5576.29");
}

#[test]
fn simulate_by_months_refuses_more_than_a_century() {
    let err =
        peer_loans::simulate_by_months(Some(dec("1000")), Some(Decimal::ZERO), 1201).unwrap_err();

    assert_eq!(err, PeerLoanError::TooManyMonths);
    assert!(err.to_string().contains("1200"));
}

#[test]
fn simulate_by_payment_refuses_a_term_above_the_ceiling() {
    // Just above the monthly interest, so it amortises, but over centuries.
    let err =
        peer_loans::simulate_by_payment(Some(dec("100")), Some(dec("0.000001")), Some(dec("0.01")))
            .unwrap_err();
    assert_eq!(err, PeerLoanError::TermUnreachable);
    assert!(err.to_string().contains("1200"));

    // A zero-rate loan with a tiny payment has the same unbounded term.
    let err = peer_loans::simulate_by_payment(
        Some(dec("1000000")),
        Some(Decimal::ZERO),
        Some(dec("0.01")),
    )
    .unwrap_err();
    assert_eq!(err, PeerLoanError::TermUnreachable);
}

#[test]
fn rate_rounds_to_the_stored_precision() {
    // The dialog sends a floating-point double, e.g. 1.1 % -> 0.011000000000000001.
    assert_eq!(
        peer_loans::rate(Some(dec("0.011000000000000001"))).to_string(),
        "0.011000"
    );
    assert_eq!(
        peer_loans::rate(Some(dec("0.028999999999999998"))).to_string(),
        "0.029000"
    );
    assert_eq!(peer_loans::rate(None), Decimal::ZERO);
}

/// (1 + r)^1200 at 1 000 %/year is far past Decimal's range; Java's answers come back anyway.
#[test]
fn extreme_but_valid_inputs_complete_with_finite_results() {
    let out = peer_loans::simulate_by_months(Some(dec("1000")), Some(dec("1000")), 1200).unwrap();
    assert_eq!(out.months, 1200);
    assert_eq!(out.monthly_payment.to_string(), "83333.33");
    assert_eq!(out.total_interest.to_string(), "99999000.00");
    assert_eq!(out.total_cost.to_string(), "100000000.00");

    let t = planned("1000", "1000", "2026-01-01", "100000", 1200);
    let plan = peer_loans::plan(&t, Some(dec("1000")), day("2126-01-01"))
        .unwrap()
        .unwrap();
    assert_eq!(plan.scheduled_remaining.to_string(), "0.00");
    assert_eq!(plan.installments_behind, Some(1));
    assert_eq!(plan.planned_interest.to_string(), "119999000.00");
}

#[test]
fn repaid_percent_excludes_write_offs() {
    assert_eq!(
        peer_loans::repaid_pct(Some(dec("500")), Some(dec("300"))).to_string(),
        "60.00"
    );
    assert_eq!(
        peer_loans::repaid_pct(Some(Decimal::ZERO), Some(dec("300"))),
        Decimal::ZERO
    );
}

#[test]
fn plan_serializes_with_the_java_component_names() {
    let t = planned("1200", "0", "2026-01-01", "100", 12);
    let plan = peer_loans::plan(&t, Some(dec("500")), day("2026-07-15"))
        .unwrap()
        .unwrap();

    let json = serde_json::to_value(&plan).unwrap();

    assert_eq!(json["scheduledRemaining"].to_string(), "600.00");
    assert_eq!(json["installmentsBehind"], -1);
    assert_eq!(json["plannedInterest"].to_string(), "0.00");
}
