package com.picsou.model;

/**
 * Why an estimate did or did not happen.
 *
 * <p>Returned rather than thrown: every one of these is a normal, explainable situation the
 * UI should describe precisely. "No estimate available in Alsace-Moselle because the land
 * registry differs there" is useful; a generic error, or worse a plausible-looking wrong
 * number, is not.
 */
public enum ValuationStatus {
    /** An estimate was produced. */
    OK,
    /** Outside any provider's coverage — notably Alsace-Moselle (57/67/68) and Mayotte. */
    UNSUPPORTED_AREA,
    /** The property kind has no reliable price-per-m² (building, land, parking, commercial). */
    NOT_ESTIMABLE,
    /** Living area or address missing, so there is nothing to compute from. */
    INCOMPLETE_DATA,
    /** The address could not be resolved to an INSEE commune code. */
    GEOCODING_FAILED,
    /** The source answered, but with no usable sample for this property. */
    NO_COMPARABLE_DATA,
    /** The source was unreachable. The previous valuation is kept. */
    PROVIDER_UNAVAILABLE
}
