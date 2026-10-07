package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A money-flow graph for the Sankey diagram: income sources flow into a single budget
 * {@code HUB}, which flows out to expense categories. When income exceeds spending the
 * surplus flows to a {@code SAVINGS} sink; when spending exceeds income the shortfall
 * enters as a {@code drawdown} source, so every node is balanced and the diagram is
 * proportionally correct (total in = total out = max(income, expense)).
 *
 * <p>{@code links} reference nodes by their index in {@code nodes}, matching Recharts'
 * {@code Sankey} data shape one-to-one. Transfers between the member's own accounts are
 * excluded, exactly as in {@link CashflowResponse}; income/expense split by amount sign,
 * so the totals here equal the cashflow totals by construction.
 */
public record CashflowFlowResponse(
    CashflowPeriod period,
    LocalDate from,
    LocalDate to,
    BigDecimal income,
    BigDecimal expense,
    BigDecimal net,
    List<FlowNode> nodes,
    List<FlowLink> links
) {
    public enum NodeType { INCOME, HUB, EXPENSE, SAVINGS }

    /**
     * One Sankey node. {@code key} is stable: {@code "cat:<id>"} for a real category or a
     * {@code "__…__"} sentinel for a synthetic node (hub, savings, uncategorized, …) the
     * frontend labels via i18n. {@code label}/{@code color} are set for category nodes and
     * left null for synthetic ones.
     */
    public record FlowNode(String key, String label, String color, NodeType type) {}

    /** A weighted edge between two node indices. */
    public record FlowLink(int source, int target, BigDecimal value) {}
}
