package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * Port for fetching live asset prices.
 * Implement this interface to add a new price source (e.g. Alpha Vantage).
 */
public interface PriceProviderPort {

    /**
     * Returns prices in EUR for the given tickers. A ticker this provider cannot
     * price is omitted from the returned map. {@link com.picsou.adapter.CompositePriceProvider}
     * routes each ticker to the right concrete provider, so callers depend only
     * on this port rather than picking a provider themselves.
     */
    Map<String, BigDecimal> getPricesEur(Set<String> tickers);

    /** Whether this provider supports the given ticker. */
    boolean supports(String ticker);

    /**
     * Daily historical prices in EUR for a ticker over {@code [from, to]}.
     * Returns an empty map for an expected upstream failure (the caller treats
     * anything thrown as a genuine bug).
     */
    Map<LocalDate, BigDecimal> getHistoricalPricesEur(String ticker, LocalDate from, LocalDate to);

    /** Intraday (hourly) prices in EUR for a ticker over {@code [from, to]}. */
    Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to);

    /**
     * Returns the FX rate to convert one unit of {@code currency} into EUR.
     * Returns {@code null} if the rate cannot be determined. Pure-cash balances
     * must go through this method — not through {@link #getPricesEur} — because
     * a bare currency code can resolve as an unrelated chart symbol (e.g. USD
     * would hit the ProShares Ultra Semiconductors ETF on Yahoo Finance).
     */
    BigDecimal getFxRateToEur(String currency);
}
