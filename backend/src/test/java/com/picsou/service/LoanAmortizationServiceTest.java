package com.picsou.service;

import com.picsou.model.Debt;
import com.picsou.service.LoanAmortizationService.LoanScheduleResponse;
import com.picsou.service.LoanAmortizationService.LoanSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LoanAmortizationServiceTest {

    private final LoanAmortizationService service = new LoanAmortizationService();

    @Test
    void zeroRate_splitEvenlyAcrossInstallments() {
        Debt debt = debt("12000", "0", "100", "2025-01-01", "2026-01-01");

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2025-01-01"));

        assertThat(out.summary().totalInstallments()).isEqualTo(12);
        assertThat(out.summary().monthlyPayment()).isEqualByComparingTo("100.00");
        assertThat(out.summary().totalInterestCost()).isEqualByComparingTo("0.00");
        assertThat(out.schedule()).hasSize(12);
        assertThat(out.schedule().get(0).capital()).isEqualByComparingTo("100.00");
        assertThat(out.schedule().get(11).remainingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void monthlyPayment_isComputedWhenNotProvided() {
        // 100k borrowed at 3% over 20y → standard French mortgage
        Debt debt = Debt.builder()
            .borrowedAmount(new BigDecimal("100000"))
            .interestRate(new BigDecimal("0.03"))
            .startDate(LocalDate.parse("2025-01-01"))
            .endDate(LocalDate.parse("2045-01-01"))
            .build();

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2025-01-01"));

        assertThat(out.summary().totalInstallments()).isEqualTo(240);
        assertThat(out.summary().monthlyPayment().doubleValue())
            .isCloseTo(554.60, within(1.0));
    }

    @Test
    void paidInstallments_computedFromAsOfDate() {
        Debt debt = debt("23649", "0.015", null, "2024-01-01", "2029-01-01");

        // 28 months in (2026-05-01) — matches the user mockup
        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2026-05-01"));

        LoanSummary s = out.summary();
        assertThat(s.totalInstallments()).isEqualTo(60);
        assertThat(s.paidInstallments()).isEqualTo(28);
        assertThat(s.remainingInstallments()).isEqualTo(32);
        assertThat(s.endDate()).isEqualTo(LocalDate.parse("2029-01-01"));
    }

    @Test
    void capitalRepaidPct_isProportionalToCapitalPaid() {
        Debt debt = debt("10000", "0", "1000", "2025-01-01", "2025-11-01");

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2025-06-01"));

        // 5 months paid out of 10 → 50% of capital
        assertThat(out.summary().paidInstallments()).isEqualTo(5);
        assertThat(out.summary().capitalRepaid()).isEqualByComparingTo("5000.00");
        assertThat(out.summary().capitalRepaidPct()).isEqualByComparingTo("50.00");
        assertThat(out.summary().remainingBalance()).isEqualByComparingTo("5000.00");
    }

    @Test
    void insurance_isSplitOutOfCapital() {
        Debt debt = Debt.builder()
            .borrowedAmount(new BigDecimal("12000"))
            .interestRate(new BigDecimal("0"))
            .monthlyPayment(new BigDecimal("110"))
            .insuranceMonthly(new BigDecimal("10"))
            .startDate(LocalDate.parse("2025-01-01"))
            .endDate(LocalDate.parse("2026-01-01"))
            .build();

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2025-01-01"));

        assertThat(out.summary().monthlyPayment()).isEqualByComparingTo("110.00");
        assertThat(out.summary().monthlyInsurance()).isEqualByComparingTo("10.00");
        assertThat(out.summary().totalInsuranceCost()).isEqualByComparingTo("120.00");
        // Capital portion of each installment = 110 - 10 (insurance) - 0 (interest) = 100
        assertThat(out.schedule().get(0).capital()).isEqualByComparingTo("100.00");
    }

    @Test
    void totalCost_includesFileFees() {
        Debt debt = Debt.builder()
            .borrowedAmount(new BigDecimal("12000"))
            .interestRate(new BigDecimal("0"))
            .monthlyPayment(new BigDecimal("100"))
            .fileFees(new BigDecimal("500"))
            .startDate(LocalDate.parse("2025-01-01"))
            .endDate(LocalDate.parse("2026-01-01"))
            .build();

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2025-01-01"));

        // 12000 + 0 (interest) + 0 (insurance) + 500 (fees) = 12500
        assertThat(out.summary().totalCost()).isEqualByComparingTo("12500.00");
        assertThat(out.summary().fileFees()).isEqualByComparingTo("500.00");
    }

    @Test
    void loanFinished_remainingBalanceIsZero() {
        Debt debt = debt("1200", "0", "100", "2024-01-01", "2025-01-01");

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2030-01-01"));

        assertThat(out.summary().paidInstallments()).isEqualTo(12);
        assertThat(out.summary().remainingInstallments()).isZero();
        assertThat(out.summary().remainingBalance()).isEqualByComparingTo("0.00");
        assertThat(out.summary().capitalRepaidPct()).isEqualByComparingTo("100.00");
    }

    @Test
    void loanNotStartedYet_remainingBalanceIsPrincipal() {
        Debt debt = debt("12000", "0", "100", "2030-01-01", "2031-01-01");

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2026-01-01"));

        assertThat(out.summary().paidInstallments()).isZero();
        assertThat(out.summary().remainingBalance()).isEqualByComparingTo("12000");
    }

    @Test
    void computeRemainingBalance_returnsPositiveCapitalRemaining() {
        Debt debt = debt("12000", "0", "100", "2025-01-01", "2026-01-01");

        BigDecimal remaining = service.computeRemainingBalance(debt, LocalDate.parse("2025-04-01"));

        // 3 months × 100€ paid → 300 paid, 11700 left
        assertThat(remaining).isEqualByComparingTo("11700.00");
    }

    /**
     * The computed annuity covers capital and interest; the schedule then takes the insurance
     * out of every installment. Without adding it back, each installment under-amortised the
     * capital by the insurance and the last one absorbed a shortfall of twelve times it.
     */
    @Test
    void computedMonthlyPayment_includesInsurance_soCapitalIsFullyAmortised() {
        Debt debt = Debt.builder()
            .borrowedAmount(new BigDecimal("12000"))
            .interestRate(new BigDecimal("0"))
            .insuranceMonthly(new BigDecimal("10"))
            .startDate(LocalDate.parse("2025-01-01"))
            .endDate(LocalDate.parse("2026-01-01"))
            .build();

        LoanScheduleResponse out = service.compute(debt, LocalDate.parse("2025-01-01"));

        assertThat(out.summary().monthlyPayment()).isEqualByComparingTo("1010.00");
        assertThat(out.schedule().get(0).capital()).isEqualByComparingTo("1000.00");
        assertThat(out.schedule().get(10).capital()).isEqualByComparingTo("1000.00");
        assertThat(out.schedule().get(11).capital()).isEqualByComparingTo("1000.00");
    }

    @Test
    void hasSchedule_needsAStartAndAnEndDateInOrder() {
        assertThat(LoanAmortizationService.hasSchedule(debt("12000", "0", "100", "2025-01-01", "2026-01-01"))).isTrue();
        assertThat(LoanAmortizationService.hasSchedule(Debt.builder().borrowedAmount(new BigDecimal("12000")).build())).isFalse();
        assertThat(LoanAmortizationService.hasSchedule(debt("12000", "0", "100", "2026-01-01", "2025-01-01"))).isFalse();
    }

    private static Debt debt(String borrowed, String rate, String monthly, String start, String end) {
        return Debt.builder()
            .borrowedAmount(new BigDecimal(borrowed))
            .interestRate(rate == null ? null : new BigDecimal(rate))
            .monthlyPayment(monthly == null ? null : new BigDecimal(monthly))
            .startDate(LocalDate.parse(start))
            .endDate(LocalDate.parse(end))
            .build();
    }
}
