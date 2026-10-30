package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Cashflow over a span: total {@code income}, total {@code expense} (as a positive magnitude)
 * and {@code net} (income − expense), plus a per-pay-cycle {@code series} for charting.
 * Transfers between the member's own accounts are excluded. Totals equal the sum of the
 * series buckets by construction.
 */
public record CashflowResponse(
    CashflowPeriod period,
    LocalDate from,
    LocalDate to,
    BigDecimal income,
    BigDecimal expense,
    BigDecimal net,
    List<CashflowBucket> series
) {
    /** One pay-cycle bucket within the series. */
    public record CashflowBucket(
        LocalDate start,
        LocalDate end,
        String label,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal net
    ) {}
}
