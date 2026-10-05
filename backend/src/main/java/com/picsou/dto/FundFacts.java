package com.picsou.dto;

import com.picsou.model.DistributionPolicy;
import com.picsou.model.Replication;

import java.math.BigDecimal;

/**
 * What a fund costs and how it behaves, as distinct from what it holds.
 *
 * <p>Travels with {@link EtfComposition} rather than through a port of its own because it comes
 * off the same page in the same request. A separate provider would mean fetching that page twice
 * per refresh, or hiding the second fetch behind a cache.
 *
 * @param terPercent      total expense ratio, in percent per year (0.38 means 0.38 %/yr)
 * @param domicileCountryKey ISO 3166-1 alpha-2 of the fund's domicile — a legal and tax fact
 *                        about the wrapper, and deliberately not mixed into the geographic
 *                        breakdown, which is about the underlying holdings
 */
public record FundFacts(
    BigDecimal terPercent,
    DistributionPolicy distributionPolicy,
    Replication replication,
    String domicileCountryKey,
    String source
) {
    public boolean isEmpty() {
        return terPercent == null && distributionPolicy == null
            && replication == null && domicileCountryKey == null;
    }
}
