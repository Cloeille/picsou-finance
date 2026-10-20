package com.picsou.port;

/**
 * Historical dividends for one ticker.
 *
 * <p>The forecast never calls this. {@code SchedulerService} does, weekly, and stores the
 * answer. A page render that needed Yahoo would be one 429 away from a blank budget card.
 */
public interface DividendHistoryPort {

    /**
     * Dividends published for {@code ticker} over the recent history.
     *
     * @return currency plus the ex-dates; an empty list means the symbol pays nothing
     * @throws RuntimeException on a transport failure, including HTTP 429
     */
    DividendHistory fetchDividendHistory(String ticker);
}
