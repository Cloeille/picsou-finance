package com.picsou.dto;

import java.math.BigDecimal;

/**
 * The member's allocation profile — their stored one, or the shipped defaults when they have
 * never edited it. The client cannot tell the two apart, and does not need to.
 */
public record AllocationTargetsResponse(
    BigDecimal monthlyEssentialExpenses,
    Short safetyNetMonths,
    BigDecimal realEstatePct,
    BigDecimal equityPct,
    BigDecimal cryptoPct,
    BigDecimal alternativePct
) {}
