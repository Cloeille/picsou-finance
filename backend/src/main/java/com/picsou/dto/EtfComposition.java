package com.picsou.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Composition breakdowns of an ETF, each as a list of {@link WeightedSlice}
 * sorted by descending weight. Any breakdown may be empty when the issuer's
 * holdings file does not expose it.
 *
 * @param source the issuer the data came from (e.g. "Boursorama")
 * @param asOf   the holdings file date when known, else null
 * @param facts  the fund's fee and behaviour, when the source publishes them; null otherwise.
 *               Carried here rather than behind a port of its own because it comes off the same
 *               page in the same request
 */
public record EtfComposition(
    List<WeightedSlice> companies,
    List<WeightedSlice> countries,
    List<WeightedSlice> sectors,
    String source,
    LocalDate asOf,
    FundFacts facts
) {
    /** For sources that publish holdings but no fund metadata. */
    public EtfComposition(List<WeightedSlice> companies,
                          List<WeightedSlice> countries,
                          List<WeightedSlice> sectors,
                          String source,
                          LocalDate asOf) {
        this(companies, countries, sectors, source, asOf, null);
    }
}
