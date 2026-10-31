package com.picsou.port;

import com.picsou.dto.EtfComposition;
import com.picsou.dto.SecurityRef;

import java.util.Optional;

/**
 * Port for fetching the aggregated composition of an ETF (companies, countries,
 * sectors) from an external source.
 *
 * <p>Implementations resolve a {@link SecurityRef} rather than a bare ticker: which identifier
 * works depends on the source, and the ISIN is the one that resolves when OpenFIGI has picked a
 * US OTC ticker for a European fund.
 */
public interface EtfCompositionProvider {

    /** Whether this provider can attempt the given security. */
    boolean supports(SecurityRef ref);

    /** Aggregated composition for the ETF, or empty when it cannot be resolved. */
    Optional<EtfComposition> fetch(SecurityRef ref);
}
