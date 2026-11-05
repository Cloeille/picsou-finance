package com.picsou.dto;

import com.picsou.model.PeerLoanRatePeriod;
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
    BigDecimal principal,
    LocalDate lentAt,
    LocalDate expectedBack,
    BigDecimal annualRate,
    PeerLoanRatePeriod ratePeriod,
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
    /** Total interest the plan implies over its whole duration; null without a plan. */
    BigDecimal plannedInterest,
    List<PeerLoanRepaymentResponse> repayments,
    /** Actual capital over time, and the forecast when a plan exists. */
    List<CurvePoint> curve
) {
    public record CurvePoint(LocalDate date, BigDecimal actual, BigDecimal scheduled) {}
}
