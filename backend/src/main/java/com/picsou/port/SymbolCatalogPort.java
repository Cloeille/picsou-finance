package com.picsou.port;

import java.util.List;

/**
 * Port for asking a price source which symbols it actually carries.
 *
 * <p>Separate from {@link PriceProviderPort}, which answers "what is this worth". This one answers
 * "is this symbol one you quote, and what do you call the instrument behind this identifier" — the
 * question that decides which of an instrument's many listings gets persisted on a holding.
 * {@code OpenFigiIsinConverter} maps an ISIN to every listing that exists; only the source that
 * will later be asked for a price knows which of them it can answer for.
 *
 * <p>Deliberately not folded into {@code PriceProviderPort}: {@link #searchSymbols} has no meaning
 * for a provider keyed by coin id rather than by listing (CoinGecko), and a port every implementer
 * must stub out is not an abstraction. Implement this one only if the source has a symbol catalog
 * to search.
 */
public interface SymbolCatalogPort {

    /** A symbol the source carries, with the name it displays for it. */
    record SymbolMatch(String symbol, String name) {}

    /**
     * Whether the source currently quotes {@code ticker} at all — a symbol check, not a price read.
     *
     * <p>Must answer {@code false} on any failure: a rate-limited or unreachable source has not
     * said "this symbol is dead", and callers are expected to treat a negative as "no information"
     * rather than as grounds for discarding a symbol.
     */
    boolean hasQuote(String ticker);

    /**
     * The symbols the source itself indexes for {@code query} — an ISIN, in practice — in its own
     * relevance order. Empty when it knows nothing about the query, or when the lookup fails.
     */
    List<SymbolMatch> searchSymbols(String query);
}
