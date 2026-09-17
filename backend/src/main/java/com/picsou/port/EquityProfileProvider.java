package com.picsou.port;

import com.picsou.dto.EquityProfile;

import java.util.Optional;

/**
 * Resolves the sector and country of a single listed share.
 *
 * <p>Separate from {@link EtfCompositionProvider} on purpose. That port returns weighted slice
 * lists and its resolver stops at the first provider with <em>any</em> data; adding an equity
 * provider to that list would change how ETFs resolve. Here the caller merges results
 * <em>field by field</em>, because no single source has both halves: Yahoo knows the sector for
 * any listing, Boursorama knows the ISIN that gives the country.
 *
 * <p>Implementations never throw. Anything that goes wrong is an empty answer, which surfaces as
 * an unclassified slice rather than a broken page.
 */
public interface EquityProfileProvider {

    boolean supports(String ticker);

    Optional<EquityProfile> fetch(String ticker);
}
