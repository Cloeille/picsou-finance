package com.picsou.dto;

/**
 * How a security is identified when asking a provider about it.
 *
 * <p>Carries the ISIN alongside the ticker because the two providers key on different things:
 * Boursorama's search resolves either, but only the ISIN survives a ticker that OpenFIGI picked
 * from a US OTC listing, and a fund-facts lookup has no key other than the ISIN.
 *
 * <p>A record rather than a third positional {@code String} parameter — the port already carried
 * two identity strings, and a third would be a bug waiting to be written.
 */
public record SecurityRef(String ticker, String name, String isin) {

    public static SecurityRef of(String ticker, String name) {
        return new SecurityRef(ticker, name, null);
    }

    /** The identifier a source should be asked for: the ISIN when known, else the ticker. */
    public String preferredIdentifier() {
        return isin != null && !isin.isBlank() ? isin : ticker;
    }
}
