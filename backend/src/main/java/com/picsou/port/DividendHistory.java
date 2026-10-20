package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a provider knows about a ticker's dividends.
 *
 * <p>An empty {@code events} list is a real answer — the symbol pays nothing — not a failure.
 * A transport failure throws, so the caller can keep whatever it stored last time. Amounts are
 * in {@code currency}, not euros; converting them is the caller's job, once, at refresh time.
 */
public record DividendHistory(String currency, List<Point> events) {

    public DividendHistory {
        events = events == null ? List.of() : List.copyOf(events);
    }

    /** One ex-date and the cash amount in {@code currency}, not yet converted. */
    public record Point(LocalDate exDate, BigDecimal amount) {}
}
