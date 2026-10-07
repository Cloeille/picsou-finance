package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One category's spending over a span, with the underlying transactions — the
 * {@code /budget/spending/:categoryId} drill page. When the category is a <em>parent</em>, the
 * span covers its whole subtree (the parent's own transactions plus every child's), {@code total}
 * and {@code count} are the subtree totals, and {@code children} carries the per-child rollup;
 * for a leaf category {@code children} is empty. {@code total} is the signed sum of the listed
 * transactions (negative for net expense).
 */
public record SpendingDetailResponse(
    Long categoryId,
    String slug,
    String name,
    String color,
    String icon,
    CashflowPeriod period,
    LocalDate from,
    LocalDate to,
    BigDecimal total,
    int count,
    List<TransactionResponse> transactions,
    List<ChildSpend> children
) {
    /** Per-child rollup shown above the transaction list when drilling a parent. {@code total} signed. */
    public record ChildSpend(
        Long categoryId,
        String name,
        String color,
        String icon,
        BigDecimal total,
        int count
    ) {}
}
