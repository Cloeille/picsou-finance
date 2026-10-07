package com.picsou.dto;

import com.picsou.model.Budget;
import com.picsou.model.CategoryKind;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A budget envelope with its current-cycle progress. {@code spent}, {@code remaining} and
 * {@code percent} are computed on read against the active payday cycle, never persisted.
 * {@code rollup} is true when the envelope's category is a parent — {@code spent} then covers the
 * whole subtree (the parent's own transactions plus every child's), so the UI can flag it.
 */
public record BudgetResponse(
    Long id,
    Long categoryId,
    String categoryName,
    CategoryKind categoryKind,
    String categoryColor,
    String categoryIcon,
    BigDecimal monthlyLimit,
    BigDecimal spent,
    BigDecimal remaining,
    BigDecimal percent,
    boolean overBudget,
    boolean rollup,
    LocalDate cycleStart,
    LocalDate cycleEnd
) {
    public static BudgetResponse from(
        Budget b, BigDecimal spent, BigDecimal percent, boolean rollup, LocalDate cycleStart, LocalDate cycleEnd
    ) {
        BigDecimal limit = b.getMonthlyLimit();
        BigDecimal remaining = limit.subtract(spent);
        return new BudgetResponse(
            b.getId(),
            b.getCategory().getId(),
            b.getCategory().getName(),
            b.getCategory().getKind(),
            b.getCategory().getColor(),
            b.getCategory().getIcon(),
            limit,
            spent,
            remaining,
            percent,
            spent.compareTo(limit) > 0,
            rollup,
            cycleStart,
            cycleEnd
        );
    }
}
