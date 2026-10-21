package com.picsou.port;

import java.util.List;

/**
 * Nasdaq's public dividend table, a complement to Yahoo for Nasdaq-listed symbols.
 *
 * <p>Yahoo publishes ex-dates and amounts, not payment dates, and it does not publish a
 * dividend until the ex-date has passed. Nasdaq does both, but only for symbols it lists:
 * a NYSE name or an ETF comes back with empty rows, which is not a failure. A transport
 * failure throws, so the caller can keep the Yahoo result it already has.
 */
public interface NasdaqDividendPort {

    List<NasdaqDividend> fetchDividends(String symbol);
}
