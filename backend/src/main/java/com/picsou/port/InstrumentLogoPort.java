package com.picsou.port;

/**
 * Port for downloading the mark (logo) of a listed instrument, a share or a fund.
 *
 * <p>Separate from {@link LogoProviderPort}, which answers crypto with a URL the browser fetches.
 * This one answers with <em>bytes</em> that Picsou stores and serves itself, because the only
 * source that covers European listings and ETFs is a scraped quote page (issue #162): it is
 * fetched once per ticker, server-side, and never handed to the browser.
 *
 * <p>Implementations never throw for an upstream outage. The answer says what the caller should
 * remember, which is the whole point of the {@link Lookup} split.
 */
public interface InstrumentLogoPort {

    /**
     * Whether {@code ticker} is a symbol this source could carry a mark for, decided without a
     * request. False for anything that cannot be a listed instrument here (a crypto coin, a raw
     * ISIN, a contract address), so the caller never spends a request on it.
     */
    boolean supports(String ticker);

    /** One request budget's worth of work for one ticker. */
    Lookup lookup(String ticker);

    /** A validated image, ready to store and serve. */
    record Image(byte[] bytes, String contentType) {}

    /**
     * The outcome of one lookup, by what the caller must do with it.
     */
    sealed interface Lookup {

        /** The mark, and its dark-background variant when the source has a distinct one. */
        record Found(Image light, Image dark) implements Lookup {}

        /** The source answered and has no usable mark. Permanent: remember it, never ask again. */
        record Absent(String reason) implements Lookup {}

        /** The source did not answer (5xx, timeout, unreachable). Stop the batch, retry later. */
        record Unavailable(String reason) implements Lookup {}

        /**
         * The source is rate-limiting us, or a cooldown is still in force. Stop the batch and
         * record nothing: the ticker was not really asked, so it stays first in line.
         */
        record RateLimited() implements Lookup {}
    }
}
