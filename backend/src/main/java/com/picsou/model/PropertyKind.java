package com.picsou.model;

/**
 * Physical nature of a property, used to pick the right DVF price series.
 *
 * <p>Only {@link #HOUSE} and {@link #APARTMENT} have a reliable price-per-m² median in
 * the open data. The others exist so a user can describe what they own, but the
 * estimator refuses them and falls back to a manual valuation — see
 * {@code PropertyValuationService}.
 */
public enum PropertyKind {
    HOUSE,
    APARTMENT,
    BUILDING,
    LAND,
    PARKING,
    COMMERCIAL;

    /** Whether the open-data estimator can produce a figure for this kind. */
    public boolean isEstimable() {
        return this == HOUSE || this == APARTMENT;
    }

    /**
     * Lenient parse of {@code real_estate_metadata.property_type}.
     *
     * <p>That column predates this enum and is free text (V19), so existing rows may hold
     * anything a user or the Finary import wrote — including French labels. New writes go
     * through the enum names, but old rows must not break the estimator, hence the aliases
     * and the {@code null} return instead of an exception.
     */
    public static PropertyKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (v) {
            case "HOUSE", "MAISON", "VILLA" -> HOUSE;
            case "APARTMENT", "APPARTEMENT", "APPART", "FLAT", "STUDIO" -> APARTMENT;
            case "BUILDING", "IMMEUBLE" -> BUILDING;
            case "LAND", "TERRAIN" -> LAND;
            case "PARKING", "GARAGE", "BOX" -> PARKING;
            case "COMMERCIAL", "COMMERCE", "LOCAL_COMMERCIAL", "BUREAU", "OFFICE" -> COMMERCIAL;
            default -> null;
        };
    }
}
