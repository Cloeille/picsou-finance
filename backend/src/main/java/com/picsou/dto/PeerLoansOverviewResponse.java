package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The member's peer loans as one view: who still owes what, and how the total outstanding
 * moved. Deliberately facts only -- no score, no predicted risk.
 *
 * @param totalOutstanding capital still owed on the open loans
 * @param openCount        loans without a closure date
 * @param closedCount      loans closed (their residual was written off)
 * @param interestAccrued  interest accrued but not paid, across every loan
 * @param loans            one entry per configured loan, open ones first then by name
 * @param history          the total outstanding on each history point
 */
public record PeerLoansOverviewResponse(
    BigDecimal totalOutstanding,
    int openCount,
    int closedCount,
    BigDecimal interestAccrued,
    List<LoanEntry> loans,
    List<Point> history
) {

    public record LoanEntry(
        Long accountId,
        String name,
        BigDecimal principal,
        BigDecimal principalRemaining,
        BigDecimal repaidPct,
        BigDecimal interestAccrued,
        BigDecimal interestReceived,
        LocalDate lentAt,
        LocalDate expectedBack,
        /** Null when the loan has no plan; 0 or negative means on or ahead of schedule. */
        Integer installmentsBehind,
        LocalDate lastPaymentDate,
        BigDecimal lastPaymentAmount,
        LocalDate closedAt
    ) {}

    public record Point(LocalDate date, BigDecimal outstanding) {}
}
