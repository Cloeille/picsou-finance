package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the ticker, display name, and language-neutral description of a transaction
 * carrying a ticker or ISIN. This is the single source of truth shared by the manual-entry
 * service and the CSV importer, so an ISIN row and the equivalent ticker row always collapse
 * to the same position.
 *
 * <p>When the input is an ISIN it is resolved (via OpenFIGI) to a Yahoo ticker + display
 * name, so an ISIN entry and the equivalent ticker entry merge into one position and Yahoo
 * pricing works. A user-supplied {@code name} always wins over the resolved one. The
 * persisted description contains the effective name or canonical ticker, so a raw ISIN never
 * surfaces in the row. The frontend combines the transaction type with the ticker when it needs
 * a localized fallback label.
 */
@Component
@RequiredArgsConstructor
public class InstrumentFieldResolver {

    private final OpenFigiIsinConverter openFigiIsinConverter;

    /**
     * Canonical instrument fields ready for persistence.
     *
     * @param ticker      the canonical ticker
     * @param name        the resolved or user-supplied display name, when available
     * @param description the display name, or the canonical ticker when no name is available
     */
    public record ResolvedInstrument(String ticker, String name, String description) {}

    /**
     * Resolves the instrument fields for a transaction carrying a ticker or ISIN.
     *
     * @param tickerOrIsin the raw ticker or ISIN input
     * @param userName     an optional user-supplied display name (wins over the resolved one)
     * @return the resolved fields, or {@code null} when the input is blank (a cash transaction,
     *         for which the caller keeps its own description/ticker/name).
     */
    public ResolvedInstrument resolve(String tickerOrIsin, String userName) {
        if (tickerOrIsin == null || tickerOrIsin.isBlank()) {
            return null; // cash transaction — no instrument
        }

        String resolvedTicker;
        String resolvedName;
        if (OpenFigiIsinConverter.isIsin(tickerOrIsin)) {
            OpenFigiIsinConverter.TickerResult r = openFigiIsinConverter.resolve(tickerOrIsin);
            resolvedTicker = r.ticker();   // already falls back to the ISIN itself on failure
            resolvedName = r.name();
        } else {
            resolvedTicker = tickerOrIsin.trim().toUpperCase();
            resolvedName = null;
        }

        String effectiveName = (userName != null && !userName.isBlank())
            ? userName.trim()
            : resolvedName;

        String description = effectiveName != null
            ? effectiveName
            : resolvedTicker;

        return new ResolvedInstrument(resolvedTicker, effectiveName, description);
    }
}
