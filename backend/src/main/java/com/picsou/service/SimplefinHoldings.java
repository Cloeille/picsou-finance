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
 * <p>Two things the bridge gets wrong or leaves out, both seen on real Fidelity data:
 * <ul>
 *   <li>{@code cost_basis} is 0.00 on every line, stocks included. A zero is "unknown", so the
 *       buy-in falls back to {@code purchase_price}, then to nothing. Treating 0 as the cost
 *       would show every position as an infinite gain.</li>
 *   <li>Cash sits in a money-market sweep fund (SPAXX, FDRXX) that the bridge lists as a
 *       position. It is cash, not a holding: it goes to the account's cash balance, as a PEA's
 *       cash does, so it neither appears as a stock nor skews the performance figures.</li>
 * </ul>
 */
final class SimplefinHoldings {

    /** A sweep fund is priced at a dollar a share; a few cents of tolerance cover rounding. */
    private static final BigDecimal NAV_LOW = new BigDecimal("0.99");
    private static final BigDecimal NAV_HIGH = new BigDecimal("1.01");

    private SimplefinHoldings() {}

    /** One position in its own currency. {@code averageBuyIn} and {@code currentPrice} may be null. */
    record Position(String ticker, String name, String currency,
                    BigDecimal quantity, BigDecimal averageBuyIn, BigDecimal currentPrice) {}

    /** Positions plus the cash found among them, as {@code (currency, amount)} sums. */
    record Mapped(List<Position> positions, Map<String, BigDecimal> cashByCurrency) {}

    static Mapped map(List<SimplefinHolding> raw, String accountCurrency) {
        Map<String, Position> byTicker = new LinkedHashMap<>();
        Map<String, BigDecimal> cash = new LinkedHashMap<>();
        for (SimplefinHolding h : raw) {
            if (h.shares() == null || h.shares().signum() <= 0) continue; // nothing held
            String currency = currencyOf(h.currency(), accountCurrency);
            if (currency == null) continue;
            if (!fits(h.shares()) || !fits(h.marketValue()) || !fits(h.costBasis()) || !fits(h.purchasePrice())) {
                continue; // would overflow numeric(20,8) and abort the whole sync
            }
            BigDecimal marketValue = h.marketValue() != null && h.marketValue().signum() > 0 ? h.marketValue() : null;

            if (isCashSweep(h, marketValue)) {
                cash.merge(currency, marketValue, BigDecimal::add);
                continue;
            }
            if (h.symbol() == null || h.symbol().isBlank()) continue; // cannot be keyed or priced
            String ticker = h.symbol().trim().toUpperCase(Locale.ROOT);
            Position next = new Position(ticker, h.description(), currency, h.shares(), buyIn(h), price(h, marketValue));
            byTicker.merge(ticker, next, SimplefinHoldings::merge);
        }
        return new Mapped(List.copyOf(new ArrayList<>(byTicker.values())), Map.copyOf(cash));
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

    /** numeric(20,8) holds 12 integer digits; a bridge figure past that would fail the insert. */
    private static final BigDecimal LIMIT = new BigDecimal("1000000000000");

    private static boolean fits(BigDecimal value) {
        return value == null || value.abs().compareTo(LIMIT) < 0;
    }

    /**
     * A money-market sweep: a symbol ending in XX (the US naming), or no symbol at all (a bank's
     * own cash line), trading at about one dollar a share. A symbol-less line at any other price
     * is not cash and, having no ticker to price it by, is skipped.
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

    private static BigDecimal price(SimplefinHolding h, BigDecimal marketValue) {
        return marketValue == null ? null : marketValue.divide(h.shares(), 8, RoundingMode.HALF_UP);
    }

    /** The same ticker in two lots: quantities add, buy-ins weigh by quantity when both are known. */
    private static Position merge(Position a, Position b) {
        BigDecimal quantity = a.quantity().add(b.quantity());
        BigDecimal buyIn = null;
        if (a.averageBuyIn() != null && b.averageBuyIn() != null) {
            buyIn = a.averageBuyIn().multiply(a.quantity())
                .add(b.averageBuyIn().multiply(b.quantity()))
                .divide(quantity, 8, RoundingMode.HALF_UP);
        }
        return new Position(a.ticker(), a.name() != null ? a.name() : b.name(), a.currency(), quantity, buyIn,
            a.currentPrice() != null ? a.currentPrice() : b.currentPrice());
    }
}
