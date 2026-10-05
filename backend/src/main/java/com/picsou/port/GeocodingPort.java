package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Port for turning a postal address into coordinates and administrative codes.
 *
 * <p>The INSEE code is the part that actually matters: every French open-data price series
 * is keyed on it, so geocoding is a prerequisite for any valuation, not a nicety.
 *
 * <p>Implement this to add another country's address service.
 */
public interface GeocodingPort {

    /** Best match for a free-form address, or empty when nothing scores. */
    Optional<GeocodeResult> geocode(String query);

    /** Ranked suggestions for an as-you-type box. Never throws on upstream failure — returns empty. */
    List<GeocodeResult> autocomplete(String query, int limit);

    /**
     * @param label     normalised address as the provider spells it
     * @param score     provider confidence in [0, 1]
     * @param latitude  WGS84
     * @param longitude WGS84
     * @param inseeCode INSEE commune code — the join key for DVF price data
     * @param postcode  postal code
     * @param city      commune name
     * @param banId     stable Base Adresse Nationale identifier, kept so a re-geocode can be
     *                  compared against what was stored
     */
    record GeocodeResult(
        String label,
        BigDecimal score,
        BigDecimal latitude,
        BigDecimal longitude,
        String inseeCode,
        String postcode,
        String city,
        String banId
    ) {
        /**
         * INSEE codes start with the department, except overseas ones which need three
         * digits. Used to reject the areas DVF does not cover.
         */
        public String departmentCode() {
            if (inseeCode == null || inseeCode.length() < 2) {
                return null;
            }
            return inseeCode.startsWith("97") ? inseeCode.substring(0, 3) : inseeCode.substring(0, 2);
        }
    }
}
