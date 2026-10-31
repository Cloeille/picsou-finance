package com.picsou.port;

import java.util.Map;
import java.util.Set;

/**
 * Port for asking a data source what mark (logo) it shows for an instrument.
 *
 * <p>Separate from {@link PriceProviderPort}, which answers "what is this worth". A mark is not a
 * price: it is decoration, it is fetched by the browser rather than by the server, and its sources
 * are a different set of keys — CoinGecko answers by coin id, an equity source by listing. Folding
 * it into {@code PriceProviderPort} would make every implementer stub a method that means nothing
 * for it, which is the argument {@link SymbolCatalogPort}'s own javadoc makes for staying apart.
 *
 * <p>Crypto only. Shares and funds use {@link InstrumentLogoPort} instead: their source answers
 * with bytes Picsou stores and serves, not with a URL the browser may fetch, so the two do not
 * share a contract.
 *
 * <p>Like every provider call here, a failure must be the caller's to survive: return what is
 * known, never throw for an upstream outage. A genuine bug still propagates, so it cannot hide
 * behind a missing mark.
 */
public interface LogoProviderPort {

    /**
     * The mark each known ticker has, keyed by upper-case ticker. A ticker this source carries no
     * mark for — an unmapped symbol, a source that does not cover equities — is simply absent
     * from the map, never mapped to {@code null}: callers read an absent key as "show the ticker",
     * so the difference between "no mark" and "no answer" is theirs to make, not this method's.
     *
     * <p>Must be answered for a whole set at once, so one page costs one request however many
     * instruments it lists.
     */
    Map<String, String> getLogoUrls(Set<String> tickers);
}
