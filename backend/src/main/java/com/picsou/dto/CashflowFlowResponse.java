package com.picsou.dto;

import com.picsou.model.AssetClass;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A money-flow graph for the Sankey diagram: sources flow into a single budget {@code HUB},
 * which flows out to sinks.
 *
 * <ul>
 *   <li>Sources (left): {@code INCOME} categories; {@code WITHDRAWAL} (one per savings/investment
 *       account whose transfers netted out over the period); {@code TRANSFER_IN} (money received
 *       from accounts Picsou does not see); {@code SHORTFALL} (what the others can't cover,
 *       taken from the current balance).</li>
 *   <li>Sinks (right): {@code EXPENSE} categories; {@code SAVINGS} (one per savings/investment
 *       account whose transfers netted in); {@code TRANSFER_OUT} (money sent to accounts Picsou
 *       does not see — not assumed to be savings); {@code UNSPENT} (income left on the current
 *       account).</li>
 * </ul>
 *
 * Every node is balanced, so total in = total out = max(income + withdrawn + transferredIn,
 * expense + saved + transferredOut).
 *
 * <p>Savings are counted <i>net per account</i>: an out-and-back on a Livret A is neither saved
 * nor withdrawn. {@code saved}/{@code withdrawn} are the sums of the positive/negative account
 * nets; {@code transferredOut}/{@code transferredIn} are the net leaving/entering the linked
 * perimeter (non-negative, at most one non-zero). {@code net} stays income − expense, as in
 * {@link CashflowResponse}.
 *
 * <p>{@code links} reference nodes by their index in {@code nodes}, matching Recharts'
 * {@code Sankey} data shape one-to-one. Transfers between the member's own accounts are
 * excluded from income/expense, exactly as in {@link CashflowResponse}; income/expense split by
 * amount sign, so those totals equal the cashflow totals by construction.
 */
public record CashflowFlowResponse(
    CashflowPeriod period,
    LocalDate from,
    LocalDate to,
    BigDecimal income,
    BigDecimal expense,
    BigDecimal net,
    BigDecimal saved,
    BigDecimal withdrawn,
    BigDecimal transferredOut,
    BigDecimal transferredIn,
    List<FlowNode> nodes,
    List<FlowLink> links
) {
    public enum NodeType {
        INCOME, HUB, EXPENSE, SAVINGS, WITHDRAWAL, TRANSFER_IN, TRANSFER_OUT, UNSPENT, SHORTFALL
    }

    /**
     * One Sankey node. {@code key} is stable: {@code "cat:<id>"} for a real category,
     * {@code "acct:<id>"} for a savings account (sink or withdrawal source; an account is only
     * ever one of them in a given period), or a {@code "__…__"} sentinel for a synthetic node
     * (hub, unspent, shortfall, transfer in/out, uncategorized, …) the frontend labels via i18n.
     * {@code label}/{@code color} are set for category and account nodes (color may be null)
     * and left null for synthetic ones. {@code assetClass} is set only on account nodes
     * ({@link AssetClass#SAVINGS} or {@link AssetClass#INVESTMENT}, from the account type) so
     * the frontend can prefix the raw account name; it is null on every other node.
     */
    public record FlowNode(String key, String label, String color, NodeType type, AssetClass assetClass) {}

    /** A weighted edge between two node indices. */
    public record FlowLink(int source, int target, BigDecimal value) {}
}
