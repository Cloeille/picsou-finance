//! LoanAmortizationServiceTest, plus figures the Java service printed for the same inputs.

use chrono::NaiveDate;
use picsou::ledger::loans::{self, LoanInstallment};
use picsou::ledger::model::Debt;
use rust_decimal::Decimal;

fn dec(s: &str) -> Decimal {
    s.parse().unwrap()
}

fn day(s: &str) -> NaiveDate {
    s.parse().unwrap()
}

fn debt(borrowed: &str, rate: Option<&str>, monthly: Option<&str>, start: &str, end: &str) -> Debt {
    Debt {
        id: 1,
        member_id: 1,
        account_id: 1,
        linked_account_id: None,
        borrowed_amount: dec(borrowed),
        interest_rate: rate.map(dec),
        monthly_payment: monthly.map(dec),
        lender_name: None,
        start_date: Some(day(start)),
        end_date: Some(day(end)),
        insurance_monthly: None,
        file_fees: None,
    }
}

fn dates(schedule: &[LoanInstallment]) -> Vec<NaiveDate> {
    schedule.iter().map(|row| row.date).collect()
}

#[test]
fn zero_rate_split_evenly_across_installments() {
    let out = loans::compute(
        &debt("12000", Some("0"), Some("100"), "2025-01-01", "2026-01-01"),
        day("2025-01-01"),
    );

    assert_eq!(out.summary.total_installments, 12);
    assert_eq!(out.summary.monthly_payment.to_string(), "100.00");
    assert_eq!(out.summary.total_interest_cost.to_string(), "0.00");
    assert_eq!(out.schedule.len(), 12);
    assert_eq!(out.schedule[0].capital.to_string(), "100.00");
    assert_eq!(out.schedule[11].remaining_balance.to_string(), "0.00");
}

#[test]
fn monthly_payment_is_computed_when_not_provided() {
    let out = loans::compute(
        &debt("100000", Some("0.03"), None, "2025-01-01", "2045-01-01"),
        day("2025-01-01"),
    );

    assert_eq!(out.summary.total_installments, 240);
    assert_eq!(out.summary.monthly_payment.to_string(), "554.60");
    assert_eq!(out.summary.total_interest_cost.to_string(), "33103.34");
    assert_eq!(out.summary.total_cost.to_string(), "133103.34");
    let first = &out.schedule[0];
    assert_eq!(
        (
            first.date,
            first.capital.to_string(),
            first.interest.to_string(),
            first.remaining_balance.to_string()
        ),
        (
            day("2025-02-01"),
            "304.60".into(),
            "250.00".into(),
            "99695.40".into()
        )
    );
    let last = &out.schedule[239];
    assert_eq!(
        (
            last.date,
            last.capital.to_string(),
            last.interest.to_string(),
            last.total_payment.to_string()
        ),
        (
            day("2045-01-01"),
            "553.13".into(),
            "1.38".into(),
            "554.51".into()
        )
    );
}

#[test]
fn paid_installments_computed_from_as_of_date() {
    let s = loans::compute(
        &debt("23649", Some("0.015"), None, "2024-01-01", "2029-01-01"),
        day("2026-05-01"),
    )
    .summary;

    assert_eq!(s.total_installments, 60);
    assert_eq!(s.paid_installments, 28);
    assert_eq!(s.remaining_installments, 32);
    assert_eq!(s.end_date, Some(day("2029-01-01")));
    // The Java service's figures for the same loan.
    assert_eq!(s.monthly_payment.to_string(), "409.36");
    assert_eq!(s.monthly_capital.to_string(), "392.83");
    assert_eq!(s.monthly_interest.to_string(), "16.53");
    assert_eq!(s.total_cost.to_string(), "24561.68");
    assert_eq!(s.total_repaid.to_string(), "11462.12");
    assert_eq!(s.capital_repaid.to_string(), "10815.82");
    assert_eq!(s.interest_repaid.to_string(), "646.30");
    assert_eq!(s.remaining_balance.to_string(), "12833.18");
    assert_eq!(s.capital_repaid_pct.to_string(), "45.73");
}

#[test]
fn capital_repaid_pct_is_proportional_to_capital_paid() {
    let s = loans::compute(
        &debt("10000", Some("0"), Some("1000"), "2025-01-01", "2025-11-01"),
        day("2025-06-01"),
    )
    .summary;

    assert_eq!(s.paid_installments, 5);
    assert_eq!(s.capital_repaid.to_string(), "5000.00");
    assert_eq!(s.capital_repaid_pct.to_string(), "50.00");
    assert_eq!(s.remaining_balance.to_string(), "5000.00");
}

#[test]
fn insurance_is_split_out_of_capital() {
    let mut d = debt("12000", Some("0"), Some("110"), "2025-01-01", "2026-01-01");
    d.insurance_monthly = Some(dec("10"));

    let out = loans::compute(&d, day("2025-01-01"));

    assert_eq!(out.summary.monthly_payment.to_string(), "110.00");
    assert_eq!(out.summary.monthly_insurance.to_string(), "10.00");
    assert_eq!(out.summary.total_insurance_cost.to_string(), "120.00");
    assert_eq!(out.schedule[0].capital.to_string(), "100.00");
}

#[test]
fn total_cost_includes_file_fees() {
    let mut d = debt("12000", Some("0"), Some("100"), "2025-01-01", "2026-01-01");
    d.file_fees = Some(dec("500"));

    let s = loans::compute(&d, day("2025-01-01")).summary;

    assert_eq!(s.total_cost.to_string(), "12500.00");
    assert_eq!(s.file_fees.to_string(), "500.00");
}

#[test]
fn loan_finished_remaining_balance_is_zero() {
    let s = loans::compute(
        &debt("1200", Some("0"), Some("100"), "2024-01-01", "2025-01-01"),
        day("2030-01-01"),
    )
    .summary;

    assert_eq!(s.paid_installments, 12);
    assert_eq!(s.remaining_installments, 0);
    assert_eq!(s.remaining_balance, Decimal::ZERO);
    assert_eq!(s.capital_repaid_pct.to_string(), "100.00");
}

#[test]
fn loan_not_started_yet_remaining_balance_is_principal() {
    let s = loans::compute(
        &debt("12000", Some("0"), Some("100"), "2030-01-01", "2031-01-01"),
        day("2026-01-01"),
    )
    .summary;

    assert_eq!(s.paid_installments, 0);
    // The borrowed amount as stored, unscaled, as Java returns it.
    assert_eq!(s.remaining_balance.to_string(), "12000");
}

#[test]
fn compute_remaining_balance_returns_positive_capital_remaining() {
    let d = debt("12000", Some("0"), Some("100"), "2025-01-01", "2026-01-01");

    assert_eq!(
        loans::compute_remaining_balance(&d, day("2025-04-01")).to_string(),
        "11700.00"
    );
}

#[test]
fn computed_monthly_payment_includes_insurance_so_capital_is_fully_amortised() {
    let mut d = debt("12000", Some("0"), None, "2025-01-01", "2026-01-01");
    d.insurance_monthly = Some(dec("10"));

    let out = loans::compute(&d, day("2025-01-01"));

    assert_eq!(out.summary.monthly_payment.to_string(), "1010.00");
    for i in [0, 10, 11] {
        assert_eq!(out.schedule[i].capital.to_string(), "1000.00");
    }
}

#[test]
fn resolve_monthly_payment_matches_the_computed_installment_insurance_included() {
    let mut d = debt("200000", Some("0.036"), None, "2025-01-01", "2045-01-01");
    d.insurance_monthly = Some(dec("30"));

    let resolved = loans::resolve_monthly_payment(&d).unwrap();

    assert_eq!(resolved.to_string(), "1200.22");
    assert_eq!(
        resolved,
        loans::compute(&d, day("2025-01-01"))
            .summary
            .monthly_payment
    );
}

#[test]
fn resolve_monthly_payment_returns_an_entered_payment_as_is() {
    let mut d = debt(
        "200000",
        Some("0.036"),
        Some("1500"),
        "2025-01-01",
        "2045-01-01",
    );
    d.insurance_monthly = Some(dec("30"));

    assert_eq!(
        loans::resolve_monthly_payment(&d).unwrap().to_string(),
        "1500.00"
    );
}

#[test]
fn resolve_monthly_payment_needs_dates_in_order() {
    let mut d = debt("200000", Some("0.036"), None, "2045-01-01", "2025-01-01");
    assert_eq!(loans::resolve_monthly_payment(&d), None);
    d.start_date = None;
    assert_eq!(loans::resolve_monthly_payment(&d), None);
}

#[test]
fn has_schedule_needs_a_start_and_an_end_date_in_order() {
    assert!(loans::has_schedule(&debt(
        "12000",
        Some("0"),
        Some("100"),
        "2025-01-01",
        "2026-01-01"
    )));
    let mut undated = debt("12000", None, None, "2025-01-01", "2026-01-01");
    undated.start_date = None;
    undated.end_date = None;
    assert!(!loans::has_schedule(&undated));
    assert!(!loans::has_schedule(&debt(
        "12000",
        Some("0"),
        Some("100"),
        "2026-01-01",
        "2025-01-01"
    )));
}

#[test]
fn undated_debt_has_an_empty_schedule_and_owes_the_borrowed_amount() {
    let mut d = debt("12000", None, None, "2025-01-01", "2026-01-01");
    d.start_date = None;

    let out = loans::compute(&d, day("2025-06-01"));

    assert!(out.schedule.is_empty());
    assert_eq!(out.summary.total_installments, 0);
    assert_eq!(out.summary.monthly_capital, Decimal::ZERO);
    assert_eq!(out.summary.remaining_balance.to_string(), "12000");
}

#[test]
fn installment_dates_take_the_due_day_from_the_end_date_when_the_start_is_a_clamped_month_end() {
    let d = debt("3000", Some("0"), Some("1000"), "2042-02-28", "2042-05-31");

    assert_eq!(
        dates(&loans::compute(&d, day("2042-01-01")).schedule),
        vec![day("2042-03-31"), day("2042-04-30"), day("2042-05-31")]
    );
}

#[test]
fn installment_dates_keep_the_start_day_when_the_start_is_not_a_month_end() {
    let d = debt("3000", Some("0"), Some("1000"), "2042-01-28", "2042-04-30");

    assert_eq!(
        dates(&loans::compute(&d, day("2042-01-01")).schedule),
        vec![day("2042-02-28"), day("2042-03-28"), day("2042-04-28")]
    );
}

#[test]
fn paid_installments_count_an_installment_from_its_due_date_when_the_start_is_a_clamped_month_end()
{
    let d = debt("3000", Some("0"), Some("1000"), "2042-02-28", "2042-05-31");

    assert_eq!(
        loans::compute(&d, day("2042-03-30"))
            .summary
            .paid_installments,
        0
    );
    assert_eq!(
        loans::compute(&d, day("2042-03-31"))
            .summary
            .paid_installments,
        1
    );
    assert_eq!(
        loans::compute_remaining_balance(&d, day("2042-03-30")),
        dec("3000.00")
    );
    assert_eq!(
        loans::compute_remaining_balance(&d, day("2042-03-31")),
        dec("2000.00")
    );
}

#[test]
fn paid_installments_count_an_installment_from_its_due_date_not_from_the_first_of_its_month() {
    let d = debt("3000", Some("0"), Some("1000"), "2042-01-15", "2042-04-15");

    assert_eq!(
        loans::compute(&d, day("2042-02-14"))
            .summary
            .paid_installments,
        0
    );
    assert_eq!(
        loans::compute(&d, day("2042-02-15"))
            .summary
            .paid_installments,
        1
    );
    assert_eq!(
        loans::compute_remaining_balance(&d, day("2042-02-14")),
        dec("3000.00")
    );
    assert_eq!(
        loans::compute_remaining_balance(&d, day("2042-02-15")),
        dec("2000.00")
    );
}

#[test]
fn paid_installments_reach_the_total_on_the_end_date_even_before_the_last_due_day() {
    let d = debt("3000", Some("0"), Some("1000"), "2042-01-15", "2042-04-01");

    assert_eq!(
        loans::compute(&d, day("2042-03-31"))
            .summary
            .paid_installments,
        2
    );
    let on_end_date = loans::compute(&d, day("2042-04-01")).summary;
    assert_eq!(on_end_date.paid_installments, 3);
    assert_eq!(on_end_date.remaining_balance, Decimal::ZERO);
}

#[test]
fn insurance_and_file_fees_mid_loan_match_java() {
    let mut d = debt("200000", Some("0.036"), None, "2025-01-01", "2045-01-01");
    d.insurance_monthly = Some(dec("30"));
    d.file_fees = Some(dec("1200"));

    let s = loans::compute(&d, day("2030-06-15")).summary;

    assert_eq!(s.paid_installments, 65);
    assert_eq!(s.monthly_capital.to_string(), "690.72");
    assert_eq!(s.monthly_interest.to_string(), "479.50");
    assert_eq!(s.total_cost.to_string(), "289253.45");
    assert_eq!(s.total_interest_cost.to_string(), "80853.45");
    assert_eq!(s.total_insurance_cost.to_string(), "7200.00");
    assert_eq!(s.total_repaid.to_string(), "78014.49");
    assert_eq!(s.capital_repaid.to_string(), "40857.67");
    assert_eq!(s.interest_repaid.to_string(), "35206.82");
    assert_eq!(s.insurance_repaid.to_string(), "1950.00");
    assert_eq!(s.remaining_balance.to_string(), "159142.33");
    assert_eq!(s.capital_repaid_pct.to_string(), "20.43");
}

/// (1 + r)^600 at the column's highest rate leaves Decimal's range; Java answers the
/// interest-only payment.
#[test]
fn rate_at_the_column_ceiling_over_fifty_years_matches_java() {
    let d = debt("10000", Some("9.9999"), None, "2025-01-01", "2075-01-01");

    let out = loans::compute(&d, day("2026-01-01"));

    assert_eq!(out.summary.monthly_payment.to_string(), "8333.25");
    assert_eq!(out.summary.remaining_balance.to_string(), "10000.00");
    assert_eq!(out.schedule[0].capital.to_string(), "0.00");
    assert_eq!(out.schedule[0].interest.to_string(), "8333.25");
    assert_eq!(
        loans::resolve_monthly_payment(&d).unwrap().to_string(),
        "8333.25"
    );
}

#[test]
fn schedule_serializes_with_the_java_component_names() {
    let out = loans::compute(
        &debt("1200", Some("0"), Some("100"), "2024-01-01", "2025-01-01"),
        day("2024-02-01"),
    );
    let json = serde_json::to_value(&out).unwrap();

    assert_eq!(json["summary"]["capitalRepaidPct"].to_string(), "8.33");
    assert_eq!(json["summary"]["endDate"], "2025-01-01");
    assert_eq!(json["schedule"][0]["totalPayment"].to_string(), "100.00");
    assert_eq!(json["schedule"][0]["date"], "2024-02-01");
}

#[test]
fn negative_amortisation_overflow_does_not_panic() {
    let d = debt("100000", Some("1.0"), Some("0"), "2025-01-01", "2105-01-01");

    let out = loans::compute(&d, day("2026-01-01"));

    assert!(out.schedule.is_empty());
    assert_eq!(out.summary.total_installments, 0);
    assert_eq!(out.summary.remaining_balance, dec("100000"));
}

#[test]
fn term_above_cap_does_not_build_an_unbounded_schedule() {
    let d = debt("10000", Some("0"), Some("1"), "1000-01-01", "5000-01-01");

    let out = loans::compute(&d, day("2026-01-01"));

    assert!(out.schedule.is_empty());
    assert_eq!(out.summary.total_installments, 0);
}

#[test]
fn weigh_scales_money_fields_and_keeps_counts_dates_and_percentages() {
    let mut d = debt("200000", Some("0.036"), None, "2025-01-01", "2045-01-01");
    d.insurance_monthly = Some(dec("30"));
    d.file_fees = Some(dec("1200"));
    let full = loans::compute(&d, day("2030-06-15"));

    // 100% share returns the schedule unchanged (including exact scales).
    assert_eq!(full.clone().weigh(dec("100")), full);
    assert_eq!(full.clone().weigh(dec("100.000")), full);

    // 50% share halves money fields (HALF_UP to 2 dp) while preserving counts, dates and %.
    let half = full.clone().weigh(dec("50"));
    assert_eq!(
        half.summary.total_installments,
        full.summary.total_installments
    );
    assert_eq!(
        half.summary.paid_installments,
        full.summary.paid_installments
    );
    assert_eq!(
        half.summary.remaining_installments,
        full.summary.remaining_installments
    );
    assert_eq!(half.summary.end_date, full.summary.end_date);
    assert_eq!(
        half.summary.capital_repaid_pct,
        full.summary.capital_repaid_pct
    );
    assert_eq!(half.summary.monthly_payment.to_string(), "600.11");
    assert_eq!(half.summary.monthly_capital.to_string(), "345.36");
    assert_eq!(half.summary.monthly_interest.to_string(), "239.75");
    assert_eq!(half.summary.monthly_insurance.to_string(), "15.00");
    assert_eq!(half.summary.total_cost.to_string(), "144626.73");
    assert_eq!(half.summary.total_capital_cost.to_string(), "100000.00");
    assert_eq!(half.summary.total_interest_cost.to_string(), "40426.73");
    assert_eq!(half.summary.total_insurance_cost.to_string(), "3600.00");
    assert_eq!(half.summary.file_fees.to_string(), "600.00");
    assert_eq!(half.summary.total_repaid.to_string(), "39007.25");
    assert_eq!(half.summary.capital_repaid.to_string(), "20428.84");
    assert_eq!(half.summary.interest_repaid.to_string(), "17603.41");
    assert_eq!(half.summary.insurance_repaid.to_string(), "975.00");
    assert_eq!(half.summary.remaining_balance.to_string(), "79571.17");
    assert_eq!(half.schedule[0].number, 1);
    assert_eq!(half.schedule[0].date, full.schedule[0].date);
    assert_eq!(half.schedule[0].insurance.to_string(), "15.00");
    assert_eq!(half.schedule[239].remaining_balance.to_string(), "0.00");

    // Fractional share (33.333%) rounds HALF_UP without double rounding.
    let third = full.clone().weigh(dec("33.333"));
    assert_eq!(third.summary.total_capital_cost.to_string(), "66666.00");
    assert_eq!(third.summary.monthly_insurance.to_string(), "10.00");
    assert_eq!(third.summary.file_fees.to_string(), "400.00");
    assert_eq!(
        third.summary.capital_repaid_pct,
        full.summary.capital_repaid_pct
    );

    // 0% share zeroes all money fields while keeping counts, dates and percentage.
    let zero = full.clone().weigh(dec("0"));
    assert_eq!(zero.summary.paid_installments, 65);
    assert_eq!(
        zero.summary.capital_repaid_pct,
        full.summary.capital_repaid_pct
    );
    assert_eq!(zero.summary.monthly_payment.to_string(), "0.00");
    assert_eq!(zero.summary.remaining_balance.to_string(), "0.00");
    assert_eq!(zero.schedule[0].date, full.schedule[0].date);
    assert_eq!(zero.schedule[0].total_payment.to_string(), "0.00");
}
