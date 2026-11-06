package com.picsou.adapter;

import java.time.Clock;
import java.util.Optional;

/** A real {@link PreciousMetalPriceProvider} reading a canned Loomis page, for tests outside this package. */
public final class PreciousMetalStubs {

    private PreciousMetalStubs() {}

    /** {@code clock} is what the quote dates in {@code quotesHtml} are checked against. */
    public static PreciousMetalPriceProvider provider(YahooFinancePriceProvider yahoo, String quotesHtml, Clock clock) {
        return new PreciousMetalPriceProvider(yahoo, () -> Optional.of(quotesHtml), clock);
    }
}
