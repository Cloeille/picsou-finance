package com.picsou.service;

import com.picsou.model.Debt;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class LoanAmortizationService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal TWELVE = new BigDecimal("12");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record LoanInstallment(
        int number,
        LocalDate date,
        BigDecimal capital,
        BigDecimal interest,
        BigDecimal insurance,
        BigDecimal totalPayment,
        BigDecimal remainingBalance
    ) {}

    public record LoanSummary(
        int totalInstallments,
        int paidInstallments,
        int remainingInstallments,
        LocalDate endDate,
        BigDecimal monthlyPayment,
        BigDecimal monthlyCapital,
        BigDecimal monthlyInterest,
        BigDecimal monthlyInsurance,
        BigDecimal totalCost,
        BigDecimal totalCapitalCost,
        BigDecimal totalInterestCost,
        BigDecimal totalInsuranceCost,
        BigDecimal fileFees,
        BigDecimal totalRepaid,
        BigDecimal capitalRepaid,
        BigDecimal interestRepaid,
        BigDecimal insuranceRepaid,
        BigDecimal remainingBalance,
        BigDecimal capitalRepaidPct
    ) {}

    public record LoanScheduleResponse(LoanSummary summary, List<LoanInstallment> schedule) {}

    public LoanScheduleResponse compute(Debt debt) {
        return compute(debt, LocalDate.now());
    }

    public LoanScheduleResponse compute(Debt debt, LocalDate asOf) {
        BigDecimal principal = nz(debt.getBorrowedAmount());
        BigDecimal annualRate = nz(debt.getInterestRate());
        BigDecimal insurance = nz(debt.getInsuranceMonthly());
        BigDecimal fileFees = nz(debt.getFileFees());
        LocalDate startDate = debt.getStartDate();
        LocalDate endDate = debt.getEndDate();

        int totalInstallments = computeTotalInstallments(startDate, endDate);
        BigDecimal monthlyRate = annualRate.divide(TWELVE, MC);

        // monthlyPayment is the whole installment, insurance included: the schedule below takes
        // the insurance back out of it. A user-entered payment already includes it; a computed
        // annuity does not, so it is added here, or every installment under-amortised the capital
        // by the insurance and the last one absorbed the shortfall.
        BigDecimal monthlyPayment = debt.getMonthlyPayment() != null
            ? debt.getMonthlyPayment()
            : computeMonthlyPayment(principal, monthlyRate, totalInstallments).add(insurance);

        // Build full schedule from start
        List<LoanInstallment> schedule = new ArrayList<>(Math.max(totalInstallments, 0));
        BigDecimal remaining = principal;
        BigDecimal totalInterest = BigDecimal.ZERO;
        BigDecimal totalInsurance = BigDecimal.ZERO;
        int paidInstallments = computePaidInstallments(startDate, asOf, totalInstallments);
        BigDecimal capitalRepaid = BigDecimal.ZERO;
        BigDecimal interestRepaid = BigDecimal.ZERO;
        BigDecimal insuranceRepaid = BigDecimal.ZERO;

        BigDecimal capitalForCurrentMonth = monthlyPayment.subtract(insurance);

        for (int i = 1; i <= totalInstallments; i++) {
            BigDecimal interestPart = remaining.multiply(monthlyRate, MC).setScale(2, RoundingMode.HALF_UP);
            BigDecimal capitalPart = monthlyPayment.subtract(insurance).subtract(interestPart);
            // Last installment absorbs rounding so remaining hits exactly zero
            if (i == totalInstallments) {
                capitalPart = remaining;
            }
            if (capitalPart.compareTo(remaining) > 0) {
                capitalPart = remaining;
            }
            BigDecimal totalPayment = capitalPart.add(interestPart).add(insurance);
            BigDecimal newRemaining = remaining.subtract(capitalPart).max(BigDecimal.ZERO);
            LocalDate installmentDate = startDate != null
                ? startDate.plusMonths(i)
                : asOf.plusMonths(i - paidInstallments);
            schedule.add(new LoanInstallment(
                i,
                installmentDate,
                capitalPart.setScale(2, RoundingMode.HALF_UP),
                interestPart,
                insurance.setScale(2, RoundingMode.HALF_UP),
                totalPayment.setScale(2, RoundingMode.HALF_UP),
                newRemaining.setScale(2, RoundingMode.HALF_UP)
            ));
            totalInterest = totalInterest.add(interestPart);
            totalInsurance = totalInsurance.add(insurance);
            if (i <= paidInstallments) {
                capitalRepaid = capitalRepaid.add(capitalPart);
                interestRepaid = interestRepaid.add(interestPart);
                insuranceRepaid = insuranceRepaid.add(insurance);
                if (i == paidInstallments) {
                    capitalForCurrentMonth = capitalPart;
                }
            }
            remaining = newRemaining;
        }

        BigDecimal remainingBalance = paidInstallments == 0
            ? principal
            : (paidInstallments >= schedule.size()
                ? BigDecimal.ZERO
                : schedule.get(paidInstallments - 1).remainingBalance());
        BigDecimal totalCapitalRepaid = capitalRepaid.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalInterestRepaid = interestRepaid.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalInsuranceRepaid = insuranceRepaid.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalRepaid = totalCapitalRepaid.add(totalInterestRepaid).add(totalInsuranceRepaid);

        BigDecimal totalCost = principal.add(totalInterest).add(totalInsurance).add(fileFees)
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal capitalRepaidPct = principal.signum() == 0
            ? BigDecimal.ZERO
            : totalCapitalRepaid.multiply(HUNDRED).divide(principal, 2, RoundingMode.HALF_UP);

        // Use the actual capital portion of the most-recently-paid (or upcoming) installment
        // for the "monthlyCapital" / "monthlyInterest" displayed in the UI.
        int referenceIdx = paidInstallments > 0
            ? Math.min(paidInstallments, schedule.size()) - 1
            : 0;
        BigDecimal monthlyCapital = schedule.isEmpty() ? BigDecimal.ZERO : schedule.get(referenceIdx).capital();
        BigDecimal monthlyInterest = schedule.isEmpty() ? BigDecimal.ZERO : schedule.get(referenceIdx).interest();

        LoanSummary summary = new LoanSummary(
            totalInstallments,
            paidInstallments,
            Math.max(totalInstallments - paidInstallments, 0),
            endDate,
            monthlyPayment.setScale(2, RoundingMode.HALF_UP),
            monthlyCapital,
            monthlyInterest,
            insurance.setScale(2, RoundingMode.HALF_UP),
            totalCost,
            principal.setScale(2, RoundingMode.HALF_UP),
            totalInterest.setScale(2, RoundingMode.HALF_UP),
            totalInsurance.setScale(2, RoundingMode.HALF_UP),
            fileFees.setScale(2, RoundingMode.HALF_UP),
            totalRepaid,
            totalCapitalRepaid,
            totalInterestRepaid,
            totalInsuranceRepaid,
            remainingBalance,
            capitalRepaidPct
        );

        return new LoanScheduleResponse(summary, schedule);
    }

    /**
     * Capital restant dû à la date donnée. Used by AccountService.liveBalanceEur for LOAN accounts.
     * Returns a positive value; the caller is expected to negate for net-worth purposes.
     */
    public BigDecimal computeRemainingBalance(Debt debt, LocalDate asOf) {
        return compute(debt, asOf).summary().remainingBalance();
    }

    /**
     * Whether the debt carries enough to amortise: a start and an end date, in that order.
     * Without them the schedule is empty and {@link #computeRemainingBalance} can only answer
     * the borrowed amount, which is not what the user owes; callers fall back to the balance
     * stored on the account instead.
     */
    public static boolean hasSchedule(Debt debt) {
        return computeTotalInstallments(debt.getStartDate(), debt.getEndDate()) > 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static int computeTotalInstallments(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || !endDate.isAfter(startDate)) return 0;
        long months = ChronoUnit.MONTHS.between(YearMonth.from(startDate), YearMonth.from(endDate));
        return (int) Math.max(months, 0);
    }

    private static int computePaidInstallments(LocalDate startDate, LocalDate asOf, int totalInstallments) {
        if (startDate == null || asOf == null || totalInstallments == 0) return 0;
        if (!asOf.isAfter(startDate)) return 0;
        long months = ChronoUnit.MONTHS.between(YearMonth.from(startDate), YearMonth.from(asOf));
        return (int) Math.min(Math.max(months, 0), totalInstallments);
    }

    /**
     * Standard amortization formula: M = P * r / (1 - (1+r)^-n).
     * Falls back to P/n when the monthly rate is zero (interest-free loan).
     */
    private static BigDecimal computeMonthlyPayment(BigDecimal principal, BigDecimal monthlyRate, int n) {
        if (n <= 0) return BigDecimal.ZERO;
        if (monthlyRate.signum() == 0) {
            return principal.divide(new BigDecimal(n), 8, RoundingMode.HALF_UP);
        }
        // (1 + r)^n
        BigDecimal pow = BigDecimal.ONE.add(monthlyRate).pow(n, MC);
        // r / (1 - (1+r)^-n)  =  r * pow / (pow - 1)
        BigDecimal numerator = principal.multiply(monthlyRate, MC).multiply(pow, MC);
        BigDecimal denominator = pow.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP);
    }
}
