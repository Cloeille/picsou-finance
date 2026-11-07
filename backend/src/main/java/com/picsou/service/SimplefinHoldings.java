package com.picsou.service;

import com.picsou.port.SimplefinPort.SimplefinHolding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the positions a SimpleFIN bridge reports into Picsou holdings, in the position's own
 * currency. Pure and stateless: currency conversion and persistence stay in
 * {@link SimplefinSyncService}.
 *
 * <p>Things the bridge gets wrong or leaves out, seen on real Fidelity data:
 * <ul>
 *   <li>{@code cost_basis} is 0.00 on every line, stocks included. A zero is "unknown", so the
 *       buy-in falls back to {@code purchase_price}, then to nothing. Treating 0 as the cost
 *       would show every position as an infinite gain.</li>
 *   <li>Cash sits in a money-market sweep fund (SPAXX, FDRXX) that the bridge lists as a
 *       position. It is cash, not a holding: it goes to the account's cash balance, as a PEA's
 *       cash does, so it neither appears as a stock nor skews the performance figures.</li>
 *   <li>Some cash is not listed at all: the account balance can exceed the positions' value by
 *       what the bridge left out. The sweep lines are the cash known for sure; the rest is the
 *       balance minus the positions, which needs one currency to compare them in, so the sync
 *       works it out in EUR.</li>
 * </ul>
 *
 * <p>{@link Mapped#complete} is false when a line that holds something could not be carried over.
 * The sync then keeps what it stored: replacing a portfolio with part of it would delete the
 * rest.
 */
final class SimplefinHoldings {

    /** A sweep fund is priced at a dollar a share; a few cents of tolerance cover rounding. */
    private static final BigDecimal NAV_LOW = new BigDecimal("0.99");
    private static final BigDecimal NAV_HIGH = new BigDecimal("1.01");

    private SimplefinHoldings() {}

    /**
     * One position in its own currency. {@code averageBuyIn} and {@code marketValue} may be null.
     * The price is derived from the market value, so lots merged under one ticker price at their
     * combined worth rather than at the first lot's.
     */
    record Position(String ticker, String name, String currency,
                    BigDecimal quantity, BigDecimal averageBuyIn, BigDecimal marketValue) {

        BigDecimal currentPrice() {
            return marketValue == null ? null : marketValue.divide(quantity, 8, RoundingMode.HALF_UP);
        }
    }

    /**
     * Positions plus the sweep lines as {@code (currency, amount)} sums. An empty result (no
     * position and no sweep) means nothing is held. {@code complete} is false when a line holding
     * something was left out.
     */
    record Mapped(List<Position> positions, Map<String, BigDecimal> sweepByCurrency, boolean complete) {}

    static Mapped map(List<SimplefinHolding> raw, String accountCurrency) {
        Map<String, Position> byTicker = new LinkedHashMap<>();
        Map<String, BigDecimal> sweep = new LinkedHashMap<>();
        boolean complete = true;
        for (SimplefinHolding h : raw) {
            if (h.shares() != null && h.shares().signum() == 0) continue; // nothing held
            String currency = readable(h) ? currencyOf(h.currency(), accountCurrency) : null;
            if (currency == null) {
                complete = false; // short, malformed, past the column or in a currency we cannot value
                continue;
            }
            BigDecimal marketValue = h.marketValue() != null && h.marketValue().signum() > 0 ? h.marketValue() : null;

            if (isCashSweep(h, marketValue)) {
                sweep.merge(currency, marketValue, BigDecimal::add);
                continue;
            }
            BigDecimal buyIn = buyIn(h);
            if (h.symbol() == null || h.symbol().isBlank() || !fits(buyIn)) {
                complete = false; // cannot be keyed or priced, or the buy-in overflows the column
                continue;
            }
            String ticker = h.symbol().trim().toUpperCase(Locale.ROOT);
            Position next = new Position(ticker, h.description(), currency, h.shares(), buyIn, marketValue);
            Position previous = byTicker.get(ticker);
            if (previous == null) {
                byTicker.put(ticker, next);
            } else if (previous.currency().equals(currency)) {
                byTicker.put(ticker, merge(previous, next));
            } else {
                complete = false; // one (account, ticker) row cannot hold two currencies
            }
        }

        List<Position> positions = new ArrayList<>();
        for (Position p : byTicker.values()) {
            if (fits(p.quantity()) && fits(p.marketValue()) && fits(p.currentPrice())) {
                positions.add(p);
            } else {
                complete = false; // a merged or derived figure overflows the column
            }
        }
        Map<String, BigDecimal> sweeps = new LinkedHashMap<>();
        for (var entry : sweep.entrySet()) {
            if (fits(entry.getValue())) {
                sweeps.put(entry.getKey(), entry.getValue());
            } else {
                complete = false;
            }
        }
        return new Mapped(List.copyOf(positions), Map.copyOf(sweeps), complete);
    }

    /** A positive share count and figures that fit the columns. */
    private static boolean readable(SimplefinHolding h) {
        return h.shares() != null && h.shares().signum() > 0
            && fits(h.shares()) && fits(h.marketValue()) && fits(h.costBasis()) && fits(h.purchasePrice());
    }

    /** A real ISO 4217 code, else the account's own, else none: the column is CHAR(3) with a check. */
    private static String currencyOf(String raw, String accountCurrency) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) return accountCurrency;
        try {
            Currency.getInstance(code);
            return code;
        } catch (IllegalArgumentException ex) {
            return null; // a custom or garbled currency: the line cannot be valued
        }
    }

    /**
     * The smallest magnitude that rounds (HALF_UP, to the column's 8 decimals) to 10^12, the first
     * value numeric(20,8) cannot hold. A plain comparison: rounding the figure first would throw on
     * an absurd exponent, and the parser already refuses those.
     */
    private static final BigDecimal COLUMN_CEILING = new BigDecimal("999999999999.999999995");

    /** numeric(20,8) holds 12 integer digits; a figure past that would fail the insert. */
    static boolean fits(BigDecimal value) {
        return value == null || value.abs().compareTo(COLUMN_CEILING) < 0;
    }

    /**
     * A money-market sweep: a symbol ending in XX (the US naming), or no symbol at all (a bank's
     * own cash line), trading at about one dollar a share. A symbol-less line at any other price
     * is not cash and, having no ticker to price it by, cannot be kept.
     */
    private static boolean isCashSweep(SimplefinHolding h, BigDecimal marketValue) {
        if (marketValue == null) return false;
        boolean blank = h.symbol() == null || h.symbol().isBlank();
        if (!blank && !h.symbol().trim().toUpperCase(Locale.ROOT).endsWith("XX")) return false;
        BigDecimal nav = marketValue.divide(h.shares(), 6, RoundingMode.HALF_UP);
        return nav.compareTo(NAV_LOW) >= 0 && nav.compareTo(NAV_HIGH) <= 0;
    }

    private static BigDecimal buyIn(SimplefinHolding h) {
        if (h.costBasis() != null && h.costBasis().signum() > 0) {
            return h.costBasis().divide(h.shares(), 8, RoundingMode.HALF_UP);
        }
        if (h.purchasePrice() != null && h.purchasePrice().signum() > 0) return h.purchasePrice();
        return null;
    }

    /**
     * The same ticker in two lots of one currency: quantities add, and the buy-in (weighed by
     * quantity) and the market value are kept only when both lots know them.
     */
    private static Position merge(Position a, Position b) {
        BigDecimal quantity = a.quantity().add(b.quantity());
        BigDecimal buyIn = null;
        if (a.averageBuyIn() != null && b.averageBuyIn() != null) {
            buyIn = a.averageBuyIn().multiply(a.quantity())
                .add(b.averageBuyIn().multiply(b.quantity()))
                .divide(quantity, 8, RoundingMode.HALF_UP);
        }
        BigDecimal marketValue = a.marketValue() != null && b.marketValue() != null
            ? a.marketValue().add(b.marketValue()) : null;
        return new Position(a.ticker(), a.name() != null ? a.name() : b.name(), a.currency(),
            quantity, buyIn, marketValue);
    }
}
