package com.picsou.port;

import com.picsou.model.PropertyKind;
import com.picsou.model.ValuationConfidence;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Port for estimating what a property is worth today.
 *
 * <p>Implement this to add a valuation source (a local DVF comparables engine, another
 * country's land registry, a commercial API). {@code PropertyValuationService} picks the
 * first implementation whose {@link #supports} accepts the input, so ordering is by
 * {@code @Order} / bean registration, and an implementation must be honest about what it
 * cannot value rather than returning a weak guess.
 */
public interface PropertyValuationPort {

    /** Stable identifier recorded on every estimate, e.g. {@code CEREMA_DV3F}. */
    String providerName();

    /**
     * Whether this provider can value the property at all.
     *
     * <p>Returning {@code false} is the right answer for an uncovered area — French DVF has
     * no data for Alsace-Moselle or Mayotte, and a plausible-looking number there would be
     * worse than none.
     */
    boolean supports(ValuationInput input);

    /** The estimate, or empty when the source had no usable sample. */
    Optional<ValuationResult> estimate(ValuationInput input);

    /**
     * Everything a provider may need. Fields beyond kind/area/INSEE are optional and only
     * some providers use them.
     *
     * @param inseeCode    INSEE commune code, the join key for French price data
     * @param departmentCode derived department, for coverage checks
     * @param countryCode  ISO-3166 alpha-2
     * @param kind         house, apartment, ...
     * @param livingArea   m² of living space — without it there is nothing to scale
     * @param rooms        principal rooms, used to pick a room-count-specific price series
     */
    record ValuationInput(
        String inseeCode,
        String departmentCode,
        String countryCode,
        PropertyKind kind,
        BigDecimal livingArea,
        BigDecimal landArea,
        Short rooms,
        Short constructionYear,
        BigDecimal latitude,
        BigDecimal longitude
    ) {}

    /**
     * @param estimatedValue the headline figure, before any caller-side adjustments
     * @param lowValue       25th-percentile bound
     * @param highValue      75th-percentile bound
     * @param pricePerSqm    median €/m² the value was derived from
     * @param sampleSize     comparable transactions behind the median
     * @param sourceYear     vintage of the underlying data — it is not "today"
     */
    record ValuationResult(
        BigDecimal estimatedValue,
        BigDecimal lowValue,
        BigDecimal highValue,
        BigDecimal pricePerSqm,
        Integer sampleSize,
        ValuationConfidence confidence,
        Short sourceYear,
        String scale
    ) {}
}
