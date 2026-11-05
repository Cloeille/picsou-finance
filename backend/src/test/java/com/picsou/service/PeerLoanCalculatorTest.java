package com.picsou.service;

import com.picsou.model.PeerLoanRepayment;
import com.picsou.model.PeerLoanRepaymentKind;
import com.picsou.model.PeerLoanTerms;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeerLoanCalculatorTest {

    private static PeerLoanTerms terms(String principal, String rate, String lentAt) {
        return PeerLoanTerms.builder()
            .principal(new BigDecimal(principal))
            .annualRate(rate == null ? null : new BigDecimal(rate))
            .lentAt(LocalDate.parse(lentAt))
            .build();
    }

    private static PeerLoanRepayment repayment(String date, String amount, PeerLoanRepaymentKind kind) {
        return PeerLoanRepayment.builder()
            .id((long) (Math.random() * 100000))
            .paidOn(LocalDate.parse(date))
            .amount(new BigDecimal(amount))
            .kind(kind)
            .build();
    }

    @Test
    void zeroRate_principalReducedByEachRepayment() {
        PeerLoanCalculator.Computed out = PeerLoanCalculator.compute(
            terms("500", "0", "2026-01-01"),
            List.of(
                repayment("2026-02-01", "100", PeerLoanRepaymentKind.PRINCIPAL),
                repayment("2026-03-01", "50", PeerLoanRepaymentKind.PRINCIPAL)),
            LocalDate.parse("2026-03-01"));

        assertThat(out.principalRemaining()).isEqualByComparingTo("350.00");
        assertThat(out.principalPaid()).isEqualByComparingTo("150.00");
        assertThat(out.interestAccrued()).isEqualByComparingTo("0.00");
        assertThat(out.writtenOff()).isEqualByComparingTo("0.00");
    }

    @Test
    void interestAccruesAtActualOver365() {
        PeerLoanCalculator.Computed out = PeerLoanCalculator.compute(
            terms("1000", "0.10", "2026-01-01"),
            List.of(),
            LocalDate.parse("2026-01-11"));

        // 1000 × 10 % × 10 / 365 = 2.7397...
        assertThat(out.interestAccrued()).isEqualByComparingTo("2.74");
        assertThat(out.principalRemaining()).isEqualByComparingTo("1000.00");
    }

    @Test
    void interestPaymentReducesAccruedAndTheExcessGoesToCapital() {
        PeerLoanCalculator.Computed out = PeerLoanCalculator.compute(
            terms("1000", "0.10", "2026-01-01"),
            List.of(repayment("2026-01-11", "10", PeerLoanRepaymentKind.INTEREST)),
            LocalDate.parse("2026-01-11"));

        assertThat(out.interestReceived()).isEqualByComparingTo("2.74");
        assertThat(out.principalPaid()).isEqualByComparingTo("7.26");
        assertThat(out.principalRemaining()).isEqualByComparingTo("992.74");
    }

    @Test
    void principalPaymentIsAllocatedInterestFirst() {
        PeerLoanCalculator.Computed out = PeerLoanCalculator.compute(
            terms("1000", "0.10", "2026-01-01"),
            List.of(repayment("2026-01-11", "10", PeerLoanRepaymentKind.PRINCIPAL)),
            LocalDate.parse("2026-01-11"));

        assertThat(out.interestReceived()).isEqualByComparingTo("2.74");
        assertThat(out.principalPaid()).isEqualByComparingTo("7.26");
        assertThat(out.principalRemaining()).isEqualByComparingTo("992.74");
        assertThat(out.splits()).hasSize(1);
        assertThat(out.splits().getFirst().interest()).isEqualByComparingTo("2.74");
        assertThat(out.splits().getFirst().capital()).isEqualByComparingTo("7.26");
    }

    @Test
    void paymentSmallerThanAccruedInterestDoesNotAmortise() {
        PeerLoanCalculator.Computed out = PeerLoanCalculator.compute(
            terms("1000", "0.10", "2026-01-01"),
            List.of(repayment("2026-01-11", "1", PeerLoanRepaymentKind.INTEREST)),
            LocalDate.parse("2026-01-11"));

        assertThat(out.interestReceived()).isEqualByComparingTo("1.00");
        assertThat(out.principalPaid()).isEqualByComparingTo("0.00");
        assertThat(out.principalRemaining()).isEqualByComparingTo("1000.00");
    }

    @Test
    void writeOffReducesTheNominalAndIsReportedSeparately() {
        PeerLoanCalculator.Computed out = PeerLoanCalculator.compute(
            terms("500", "0", "2026-01-01"),
            List.of(repayment("2026-02-01", "200", PeerLoanRepaymentKind.WRITE_OFF)),
            LocalDate.parse("2026-02-01"));

        assertThat(out.writtenOff()).isEqualByComparingTo("200.00");
        assertThat(out.principalRemaining()).isEqualByComparingTo("300.00");
        assertThat(out.principalPaid()).isEqualByComparingTo("0.00");
    }

    @Test
    void overpaymentClampsTheBalanceAtZero() {
        PeerLoanCalculator.Computed out = PeerLoanCalculator.compute(
            terms("100", "0", "2026-01-01"),
            List.of(repayment("2026-02-01", "150", PeerLoanRepaymentKind.PRINCIPAL)),
            LocalDate.parse("2026-02-01"));

        assertThat(out.principalRemaining()).isEqualByComparingTo("0.00");
        assertThat(out.principalPaid()).isEqualByComparingTo("100.00");
    }

    @Test
    void planDrawsTheForecastAndTheDriftInInstallments() {
        PeerLoanTerms terms = PeerLoanTerms.builder()
            .principal(new BigDecimal("1200"))
            .annualRate(BigDecimal.ZERO)
            .lentAt(LocalDate.parse("2026-01-01"))
            .plannedMonthlyAmount(new BigDecimal("100"))
            .plannedMonths(12)
            .build();

        PeerLoanCalculator.Plan plan = PeerLoanCalculator.plan(
            terms, new BigDecimal("500"), LocalDate.parse("2026-07-15"));

        assertThat(plan.scheduledRemaining()).isEqualByComparingTo("600.00");
        assertThat(plan.installmentsBehind()).isEqualTo(-1);
    }

    @Test
    void simulateByMonths_matchesTheAnnuity() {
        PeerLoanCalculator.Simulation out = PeerLoanCalculator.simulateByMonths(
            new BigDecimal("1200"), BigDecimal.ZERO, 12);
        assertThat(out.monthlyPayment()).isEqualByComparingTo("100.00");
        assertThat(out.months()).isEqualTo(12);
        assertThat(out.totalInterest()).isEqualByComparingTo("0.00");

        PeerLoanCalculator.Simulation withInterest = PeerLoanCalculator.simulateByMonths(
            new BigDecimal("10000"), new BigDecimal("0.05"), 24);
        assertThat(withInterest.monthlyPayment().doubleValue()).isCloseTo(438.71, org.assertj.core.api.Assertions.within(1.0));
        assertThat(withInterest.totalCost().doubleValue()).isCloseTo(10529.0, org.assertj.core.api.Assertions.within(5.0));
    }

    @Test
    void highShortTermRateIsAccepted() {
        // 120 %/year = 10 %/month: a short-term loan may carry it.
        PeerLoanCalculator.Simulation out = PeerLoanCalculator.simulateByMonths(
            new BigDecimal("500"), new BigDecimal("1.20"), 12);

        assertThat(out.monthlyPayment().doubleValue())
            .isCloseTo(73.38, org.assertj.core.api.Assertions.within(1.0));
        assertThat(out.totalInterest()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void simulateByPayment_refusesAPaymentThatNeverAmortises() {        assertThatThrownBy(() -> PeerLoanCalculator.simulateByPayment(
            new BigDecimal("1000"), new BigDecimal("0.10"), new BigDecimal("5")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("never be repaid");
    }

    @Test
    void simulateByPayment_computesTheDuration() {
        PeerLoanCalculator.Simulation out = PeerLoanCalculator.simulateByPayment(
            new BigDecimal("1200"), BigDecimal.ZERO, new BigDecimal("100"));
        assertThat(out.months()).isEqualTo(12);
        assertThat(out.monthlyPayment()).isEqualByComparingTo("100.00");
    }

    @Test
    void repaidPercentExcludesWriteOffs() {
        assertThat(PeerLoanCalculator.repaidPct(new BigDecimal("500"), new BigDecimal("300")))
            .isEqualByComparingTo("60.00");
    }
}
