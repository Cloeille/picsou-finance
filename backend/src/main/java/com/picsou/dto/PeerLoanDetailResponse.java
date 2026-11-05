package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the peer-loan detail page renders, in one round trip.
 *
 * <p>{@code configured} is false while the account exists without terms yet, which is
 * how a freshly created {@code PEER_LOAN} account reaches the page: the UI then shows
 * the setup form instead of an empty summary.
 *
 * <p>{@code scheduledRemaining} / {@code installmentsBehind} are {@code null} without a
 * plan. They are a forecast, never a state of the loan.
 */
public record PeerLoanDetailResponse(
    Long accountId,
    String accountName,
    boolean configured,
    Long counterpartyId,
    String counterpartyName,
    BigDecimal principal,
    LocalDate lentAt,
    LocalDate expectedBack,
    BigDecimal annualRate,
    BigDecimal plannedMonthlyAmount,
    Integer plannedMonths,
    Long linkedAccountId,
    String linkedAccountName,
    String note,
    LocalDate closedAt,
    BigDecimal principalRemaining,
    BigDecimal interestAccrued,
    BigDecimal interestReceived,
    BigDecimal principalRepaid,
    BigDecimal totalRepaid,
    BigDecimal writtenOff,
    BigDecimal repaidPct,
    BigDecimal scheduledRemaining,
    Integer installmentsBehind,
    List<PeerLoanRepaymentResponse> repayments,
    PeerCounterpartyHistory counterpartyHistory
) {
    /** Facts only: no score, no predicted risk. */
    public record PeerCounterpartyHistory(
        Long id,
        String name,
        int loanCount,
        int openLoans,
        int closedLoans,
        BigDecimal totalLent,
        BigDecimal totalRecovered,
        BigDecimal totalWrittenOff,
        BigDecimal totalInterestReceived,
        Long averageClosureDelayDays,
        BigDecimal openExposure,
        List<LoanBrief> loans
    ) {}

    public record LoanBrief(
        Long accountId,
        String accountName,
        BigDecimal principal,
        BigDecimal remaining,
        LocalDate lentAt,
        LocalDate closedAt
    ) {}
}
