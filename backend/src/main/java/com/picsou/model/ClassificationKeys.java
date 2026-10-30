package com.picsou.model;

/**
 * The classification values that describe an asset no market data source will ever cover.
 *
 * <p>An ELTIF, a closed fund, an unquoted holding: Yahoo does not know them, justETF does not
 * know them, and Boursorama has no page for them. Leaving them permanently "unclassified" reads
 * as a gap in the data when it is nothing of the sort — unlisted is a class of asset, not a
 * missing lookup. Given somewhere to go, they count as classified and stop dragging coverage
 * down.
 */
public final class ClassificationKeys {

    private ClassificationKeys() {}

    /** Sits alongside the eleven Morningstar sector keys. */
    public static final String SECTOR_PRIVATE_EQUITY = "private_equity";

    /**
     * A pseudo-country for "not listed anywhere".
     *
     * <p>{@code XU} is in ISO 3166-1's user-assigned range (XA–XZ) and unused. Deliberately not
     * {@code ZZ}, which CLDR defines as "Unknown Region" — that would conflate a member's
     * deliberate verdict with an absent answer, which is the exact distinction this exists to
     * draw. {@code XS} is avoided too: real Euroclear bond ISINs produce it.
     */
    public static final String COUNTRY_UNLISTED = "XU";
}
