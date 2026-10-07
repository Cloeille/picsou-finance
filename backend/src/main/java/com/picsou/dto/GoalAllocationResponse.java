package com.picsou.dto;

import com.picsou.model.GoalAllocation;

import java.math.BigDecimal;

/**
 * One line of a plan's monthly split, with the holding's name resolved for display.
 *
 * @param name the holding's name in the funded account, or null when the account no longer
 *             reports one — the ticker is what identifies the line, the name is decoration
 */
public record GoalAllocationResponse(
    String ticker,
    String name,
    BigDecimal monthlyAmount
) {
    public static GoalAllocationResponse of(GoalAllocation allocation, String name) {
        return new GoalAllocationResponse(allocation.getTicker(), name, allocation.getMonthlyAmount());
    }
}
