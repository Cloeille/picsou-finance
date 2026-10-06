package com.picsou.dto;

import com.picsou.model.AssetClass;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A money-flow graph for the Sankey diagram: income sources flow into a single budget
 * {@code HUB}, which flows out to expense categories and to the savings/investment accounts
 * that received a transfer (one {@code SAVINGS} sink each). Income left over after spending
 * and saving flows to an {@code UNSPENT} sink; when spending plus saving exceeds income the
 * gap enters as a {@code SHORTFALL} source (taken from the balance). Every node is balanced,
 * so total in = total out = max(income, expense + saved).
 *
 * <p>{@code SAVINGS} means only money that actually reached a savings/investment account —
 * surplus that stayed on the current account is {@code UNSPENT}, not saved.
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
    BigDecimal saved,
    List<FlowNode> nodes,
    List<FlowLink> links
) {
    public enum NodeType { INCOME, HUB, EXPENSE, SAVINGS, UNSPENT, SHORTFALL }

    /**
     * One Sankey node. {@code key} is stable: {@code "cat:<id>"} for a real category,
     * {@code "acct:<id>"} for a savings account sink, or a {@code "__…__"} sentinel for a
     * synthetic node (hub, unspent, shortfall, uncategorized, …) the frontend labels via i18n.
     * {@code label}/{@code color} are set for category and account nodes (color may be null)
     * and left null for synthetic ones. {@code assetClass} is set only on account sinks
     * ({@link AssetClass#SAVINGS} or {@link AssetClass#INVESTMENT}, from the account type) so
     * the frontend can prefix the raw account name; it is null on every other node.
     */
    public record FlowNode(String key, String label, String color, NodeType type, AssetClass assetClass) {}

    /** A weighted edge between two node indices. */
    public record FlowLink(int source, int target, BigDecimal value) {}
}
