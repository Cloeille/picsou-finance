package com.picsou.dto;

import java.math.BigDecimal;

/**
 * What the member's transactions suggest they spend each month, offered as a starting point for
 * the safety-net target.
 *
 * <p>Never written on the member's behalf. The sample size travels with the figure so the screen
 * can say "estimated over 3 months" instead of presenting a thin average as a fact.
 *
 * @param estimate              mean monthly debits on current accounts, transfers removed; null
 *                              when there is not enough history to say anything
 * @param monthsObserved        complete months that actually carried transactions — the divisor,
 *                              which is not the same as the months looked at
 * @param excludedTransferCount debits dropped as internal movements, so the user can judge
 *                              whether the heuristic behaved
 */
public record EssentialExpenseEstimateResponse(
    BigDecimal estimate,
    int monthsObserved,
    int excludedTransferCount
) {}
