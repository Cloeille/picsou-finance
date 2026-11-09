package com.picsou.service;

import com.picsou.model.PeerLoanRepayment;
import com.picsou.model.PeerLoanRepaymentKind;
import com.picsou.model.PeerLoanTerms;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The peer-loan arithmetic: remaining capital, simple interest, and the optional
 * forecast. Pure functions, no persistence — the same principle as
 * {@link LoanAmortizationService}, where only the inputs live in the database.
 *
 * <p>Interest is simple and accrues on the outstanding capital at {@code actual/365}.
 * Kinds are applied to their own bucket: a {@code PRINCIPAL} row amortises capital, an
 * {@code INTEREST} row pays accrued interest, and a {@code WRITE_OFF} reduces capital
 * because the member gave up on it. Nothing is split implicitly, because one cash
 * movement carries one budget category.
 */
public final class PeerLoanCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MONEY_SCALE = 2;
    private static final int INTERNAL_SCALE = 8;
    /** A century of monthly instalments: the same ceiling the request validation applies. */
    private static final int MAX_MONTHS = 1200;

    private PeerLoanCalculator() {
    }

    public record Computed(
        BigDecimal principalRemaining,
        BigDecimal interestAccrued,
        BigDecimal interestReceived,
        BigDecimal principalPaid,
        BigDecimal writtenOff,
        List<PaymentSplit> splits
    ) {}

    /** How one ledger row was applied: interest first, then capital (or a write-off). */
    public record PaymentSplit(
        Long repaymentId,
        BigDecimal interest,
        BigDecimal capital,
        BigDecimal writtenOff
    ) {}

    public record Plan(BigDecimal scheduledRemaining, Integer installmentsBehind,
                       BigDecimal plannedInterest) {}

    public record Simulation(BigDecimal monthlyPayment, int months,
                             BigDecimal totalInterest, BigDecimal totalCost) {}

    public static Computed compute(PeerLoanTerms terms, List<PeerLoanRepayment> repayments, LocalDate asOf) {
        BigDecimal principal = nonNull(terms.getPrincipal());
        BigDecimal outstanding = principal;
        BigDecimal accrued = BigDecimal.ZERO;
        BigDecimal interestReceived = BigDecimal.ZERO;
        BigDecimal principalPaid = BigDecimal.ZERO;
        BigDecimal writtenOff = BigDecimal.ZERO;

        LocalDate cursor = terms.getLentAt();
        BigDecimal rate = rate(terms.getAnnualRate());

        List<PeerLoanRepayment> ordered = repayments == null ? List.of() : repayments.stream()
            .sorted(Comparator.comparing(PeerLoanRepayment::getPaidOn)
                .thenComparing(r -> r.getId() == null ? 0L : r.getId()))
            .toList();

        List<PaymentSplit> splits = new ArrayList<>(ordered.size());

        for (PeerLoanRepayment repayment : ordered) {
            LocalDate eventDate = repayment.getPaidOn() == null ? cursor : repayment.getPaidOn();
            if (cursor != null && eventDate.isBefore(cursor)) {
                eventDate = cursor;
            }
            accrued = accrued.add(accrue(outstanding, rate, cursor, eventDate));

            BigDecimal amount = nonNull(repayment.getAmount());
            PeerLoanRepaymentKind kind = repayment.getKind() == null
                ? PeerLoanRepaymentKind.PRINCIPAL
                : repayment.getKind();

            switch (kind) {
                case PRINCIPAL, INTEREST -> {
                    // One payment, whatever its label: the accrued interest is paid first,
                    // then the remainder reduces the capital. This is the banking convention
                    // and the single-amount entry the UI now offers.
                    BigDecimal toInterest = amount.min(accrued);
                    accrued = accrued.subtract(toInterest);
                    interestReceived = interestReceived.add(toInterest);
                    BigDecimal toPrincipal = amount.subtract(toInterest).min(outstanding);
                    outstanding = outstanding.subtract(toPrincipal);
                    principalPaid = principalPaid.add(toPrincipal);
                    splits.add(new PaymentSplit(
                        repayment.getId(),
                        scale(toInterest),
                        scale(toPrincipal),
                        BigDecimal.ZERO.setScale(MONEY_SCALE)));
                }
                case WRITE_OFF -> {
                    BigDecimal toWriteOff = amount.min(outstanding);
                    outstanding = outstanding.subtract(toWriteOff);
                    writtenOff = writtenOff.add(toWriteOff);
                    splits.add(new PaymentSplit(
                        repayment.getId(),
                        BigDecimal.ZERO.setScale(MONEY_SCALE),
                        BigDecimal.ZERO.setScale(MONEY_SCALE),
                        scale(toWriteOff)));
                }
            }

            if (cursor == null || eventDate.isAfter(cursor)) {
                cursor = eventDate;
            }
        }

        accrued = accrued.add(accrue(outstanding, rate, cursor, asOf));

        return new Computed(
            scale(outstanding.max(BigDecimal.ZERO)),
            scale(accrued.max(BigDecimal.ZERO)),
            scale(interestReceived),
            scale(principalPaid),
            scale(writtenOff),
            List.copyOf(splits)
        );
    }

    /**
     * The nominal still owed, the one figure net worth uses. Interest is deliberately not
     * included: it was never received, so counting it would repeat the optimism the
     * no-provision decision rejects.
     */
    public static BigDecimal principalRemaining(PeerLoanTerms terms, List<PeerLoanRepayment> repayments) {
        return compute(terms, repayments, terms.getLentAt() == null ? LocalDate.now() : terms.getLentAt())
            .principalRemaining();
    }

    /**
     * The forecast at {@code asOf}, or {@code null} when no plan is set. This never feeds
     * the balance; it only draws the dashed curve and the drift.
     */
    public static Plan plan(PeerLoanTerms terms, BigDecimal actualRemaining, LocalDate asOf) {
        if (terms.getPlannedMonthlyAmount() == null || terms.getPlannedMonths() == null) {
            return null;
        }
        BigDecimal principal = nonNull(terms.getPrincipal());
        BigDecimal monthly = terms.getPlannedMonthlyAmount();
        // Clamp on read: a value stored before the request bound existed must not make a GET
        // walk a million-month annuity.
        int totalMonths = Math.min(terms.getPlannedMonths(), MAX_MONTHS);
        BigDecimal monthlyRate = rate(terms.getAnnualRate()).divide(new BigDecimal("12"), MC);

        LocalDate start = terms.getLentAt();
        long elapsed = start == null ? 0 : ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(asOf));
        int paidMonths = (int) Math.max(0, Math.min(elapsed, totalMonths));

        BigDecimal scheduled;
        if (monthlyRate.signum() == 0) {
            scheduled = principal.subtract(monthly.multiply(BigDecimal.valueOf(paidMonths)));
        } else {
            BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow(paidMonths, MC);
            BigDecimal annuity = monthly
                .multiply(growth.subtract(BigDecimal.ONE))
                .divide(monthlyRate, MC);
            scheduled = principal.multiply(growth, MC).subtract(annuity);
        }
        scheduled = scale(scheduled.max(BigDecimal.ZERO));

        // The total interest the whole plan implies, not the part accrued so far: a
        // one-month plan at 12 %/month on 10 000 € answers 1 200 € immediately.
        BigDecimal plannedInterest = monthly.multiply(BigDecimal.valueOf(totalMonths))
            .subtract(principal)
            .max(BigDecimal.ZERO);

        Integer behind = null;
        if (monthly.signum() > 0) {
            behind = nonNull(actualRemaining)
                .subtract(scheduled)
                .divide(monthly, 0, RoundingMode.CEILING)
                .intValue();
        }
        return new Plan(scheduled, behind, scale(plannedInterest));
    }

    /** Duration → monthly payment. The standard annuity, with a zero-rate shortcut. */
    public static Simulation simulateByMonths(BigDecimal principal, BigDecimal annualRate, int months) {
        if (months <= 0) {
            throw new IllegalArgumentException("months must be greater than zero");
        }
        if (months > MAX_MONTHS) {
            throw new IllegalArgumentException("A simulation cannot exceed " + MAX_MONTHS + " months");
        }
        BigDecimal p = nonNull(principal);
        BigDecimal monthlyRate = rate(annualRate).divide(new BigDecimal("12"), MC);
        BigDecimal monthly;
        if (monthlyRate.signum() == 0) {
            monthly = p.divide(BigDecimal.valueOf(months), INTERNAL_SCALE, RoundingMode.HALF_UP);
        } else {
            BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow(months, MC);
            monthly = p.multiply(monthlyRate, MC).multiply(growth, MC)
                .divide(growth.subtract(BigDecimal.ONE), INTERNAL_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal total = monthly.multiply(BigDecimal.valueOf(months));
        return new Simulation(
            scale(monthly),
            months,
            scale(total.subtract(p).max(BigDecimal.ZERO)),
            scale(total));
    }

    /**
     * Monthly payment → duration. Refused when the payment does not cover the monthly
     * interest: such a plan never amortises, and returning a huge number would look like
     * an answer.
     */
    public static Simulation simulateByPayment(BigDecimal principal, BigDecimal annualRate, BigDecimal monthlyPayment) {
        if (monthlyPayment == null || monthlyPayment.signum() <= 0) {
            throw new IllegalArgumentException("monthlyPayment must be greater than zero");
        }
        BigDecimal p = nonNull(principal);
        BigDecimal monthlyRate = rate(annualRate).divide(new BigDecimal("12"), MC);
        if (monthlyRate.signum() == 0) {
            double rawMonths =
                p.divide(monthlyPayment, INTERNAL_SCALE, RoundingMode.HALF_UP).doubleValue();
            requireReachableTerm(rawMonths);
            int months = Math.max(1, (int) Math.ceil(rawMonths));
            // No interest: the last payment is whatever is left, so the cost is exactly the principal.
            return new Simulation(
                scale(monthlyPayment),
                months,
                BigDecimal.ZERO.setScale(MONEY_SCALE),
                scale(p));
        }

        BigDecimal interestOnly = p.multiply(monthlyRate, MC);
        if (monthlyPayment.compareTo(interestOnly) <= 0) {
            throw new IllegalArgumentException(
                "The monthly payment does not cover the monthly interest: the loan would never be repaid");
        }
        double ratio = BigDecimal.ONE
            .subtract(p.multiply(monthlyRate, MC).divide(monthlyPayment, MC))
            .doubleValue();
        double logBase = Math.log(1 + monthlyRate.doubleValue());
        if (ratio <= 0 || logBase <= 0) {
            throw new IllegalArgumentException("The monthly payment does not amortise the loan");
        }
        double rawMonths = -Math.log(ratio) / logBase;
        requireReachableTerm(rawMonths);
        int months = Math.max(1, (int) Math.ceil(rawMonths));

        // The final installment is not a full monthly payment: it clears the balance left
        // after the preceding ones, plus the interest that balance accrued. Multiplying the
        // monthly payment by the month count would overstate both the cost and the interest.
        BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow(months - 1, MC);
        BigDecimal beforeFinal = p.multiply(growth, MC)
            .subtract(monthlyPayment.multiply(growth.subtract(BigDecimal.ONE)).divide(monthlyRate, MC))
            .max(BigDecimal.ZERO);
        BigDecimal finalPayment = beforeFinal.multiply(BigDecimal.ONE.add(monthlyRate), MC);
        BigDecimal total = monthlyPayment.multiply(BigDecimal.valueOf(months - 1L)).add(finalPayment);

        return new Simulation(
            scale(monthlyPayment),
            months,
            scale(total.subtract(p).max(BigDecimal.ZERO)),
            scale(total));
    }

    /**
     * A payment that would need centuries (or a non-finite term) is refused as bad input,
     * not answered with {@link Integer#MAX_VALUE}: the annuity that follows would overflow.
     */
    private static void requireReachableTerm(double months) {
        if (!Double.isFinite(months) || months > MAX_MONTHS) {
            throw new IllegalArgumentException(
                "The monthly payment is too small: repayment would take more than "
                    + MAX_MONTHS + " months");
        }
    }

    /** Percent of the original principal actually repaid (write-offs excluded). */
    public static BigDecimal repaidPct(BigDecimal principal, BigDecimal principalPaid) {
        BigDecimal p = nonNull(principal);
        if (p.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return nonNull(principalPaid).multiply(HUNDRED).divide(p, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal accrue(BigDecimal outstanding, BigDecimal annualRate, LocalDate from, LocalDate to) {
        if (outstanding.signum() <= 0 || annualRate.signum() <= 0 || from == null || to == null) {
            return BigDecimal.ZERO;
        }
        long days = ChronoUnit.DAYS.between(from, to);
        if (days <= 0) {
            return BigDecimal.ZERO;
        }
        return outstanding.multiply(annualRate, MC)
            .multiply(BigDecimal.valueOf(days))
            .divide(DAYS_PER_YEAR, INTERNAL_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Null-safe rate, rounded to the six decimals the column stores. The dialog sends a
     * floating-point double, so a 1.1 % rate arrives as {@code 0.011000000000000001}; the
     * backend owns the rounding rather than rejecting an ordinary rate.
     */
    public static BigDecimal rate(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
