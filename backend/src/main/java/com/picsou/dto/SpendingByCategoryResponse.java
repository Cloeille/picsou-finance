package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Expense totals grouped by category over a span — the ranked breakdown behind the
 * spending drill-down. Amounts are positive magnitudes; {@code share} is the fraction
 * of {@code totalExpense} (0–1). The {@code categoryId == null} row, when present,
 * collects spending that has no managed category yet. Transfers are excluded.
 */
public record SpendingByCategoryResponse(
    CashflowPeriod period,
    LocalDate from,
    LocalDate to,
    BigDecimal totalExpense,
    List<CategorySpend> categories
) {
    /**
     * One leaf category's expense total. Rows stay leaf-scoped — never rolled up server-side, so
     * a parent and its children are never double-counted. {@code parentId}/{@code parentName}/
     * {@code parentColor} let the client group children under their parent and sum the subtree.
     * All three are {@code null} for a top-level category and for the uncategorized bucket.
     */
    public record CategorySpend(
        Long categoryId,
        String slug,
        String name,
        String color,
        String icon,
        BigDecimal amount,
        int count,
        BigDecimal share,
        Long parentId,
        String parentName,
        String parentColor
    ) {}
}
