package com.picsou.service;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared deduplication helper for sync services (IBKR, TR, Bourso) when multiple
 * external positions (e.g. several ISINs, or long/short legs) resolve to the same
 * ticker.
 *
 * <p>The merge uses a VWAP (Volume Weighted Average Price) formula so the
 * resulting {@code averageBuyIn} is the quantity-weighted mean of the inputs,
 * rather than the non-deterministic "first-seen" value that {@code HashMap}
 * iteration order would yield. TR and Bourso only ever feed positive quantities
 * today; IBKR can carry short positions (negative {@code quantity}), so the merge
 * is sign-aware -- see {@link #vwapMerge}.
 */
@Slf4j
public final class HoldingDedup {

    private HoldingDedup() {}

    public record HoldingAgg(
        BigDecimal quantity,
        BigDecimal averageBuyIn,
        BigDecimal currentPrice,
        String name
    ) {}

    /**
     * Merges two aggregates of the same ticker.
     *
     * <ul>
     *   <li><b>Zero net quantity</b> (e.g. a short fully covered by an equal-and-opposite
     *       long lot): returns a sentinel {@code HoldingAgg} with {@code quantity = 0}
     *       and {@code averageBuyIn = null} -- a flat position, not "nothing to merge".
     *       <b>Callers must skip a zero-quantity aggregate before persisting it</b> (a
     *       stale non-zero holding must not survive when the net position is flat).</li>
     *   <li><b>Opposite signs, non-zero net</b> (a long and a short leg that do not fully
     *       cancel): a quantity-weighted average across the two is not meaningful -- it
     *       can land anywhere, including negative -- so the quantity is netted
     *       arithmetically and {@code averageBuyIn} is taken from the
     *       <b>larger-{@code |quantity|}</b> side, i.e. the cost basis of the residual
     *       exposure that remains after netting. Logged at WARN (mixed-sign positions
     *       are unusual and worth surfacing).</li>
     *   <li><b>Same signs</b> (or one side is already flat, e.g. from a prior netting
     *       merge in the same reduction): the original formula, unchanged --
     *       <pre>weightedAvg = (q1 * a1 + q2 * a2) / (q1 + q2)</pre>
     *       Scale 8, {@link RoundingMode#HALF_UP} -- matches the project-wide convention
     *       used by {@code HoldingComputeService}. Null averages are treated as zero.</li>
     * </ul>
     */
    public static HoldingAgg vwapMerge(HoldingAgg prev, HoldingAgg next) {
        BigDecimal totalQty = prev.quantity().add(next.quantity());
        BigDecimal currentPrice = prev.currentPrice() != null ? prev.currentPrice() : next.currentPrice();
        String name = prev.name() != null ? prev.name() : next.name();

        if (totalQty.signum() == 0) {
            return new HoldingAgg(BigDecimal.ZERO, null, currentPrice, name);
        }

        boolean mixedSigns = prev.quantity().signum() != 0
            && next.quantity().signum() != 0
            && prev.quantity().signum() != next.quantity().signum();

        if (mixedSigns) {
            log.warn("IBKR: netting mixed-sign positions for '{}': {} @ {} vs {} @ {} -> net {}",
                name, prev.quantity(), prev.averageBuyIn(), next.quantity(), next.averageBuyIn(), totalQty);
            HoldingAgg larger = prev.quantity().abs().compareTo(next.quantity().abs()) >= 0 ? prev : next;
            return new HoldingAgg(totalQty, larger.averageBuyIn(), currentPrice, name);
        }

        BigDecimal prevAvg = prev.averageBuyIn() != null ? prev.averageBuyIn() : BigDecimal.ZERO;
        BigDecimal nextAvg = next.averageBuyIn() != null ? next.averageBuyIn() : BigDecimal.ZERO;
        BigDecimal weightedAvg = prev.quantity().multiply(prevAvg)
            .add(next.quantity().multiply(nextAvg))
            .divide(totalQty, 8, RoundingMode.HALF_UP);

        return new HoldingAgg(totalQty, weightedAvg, currentPrice, name);
    }
}
