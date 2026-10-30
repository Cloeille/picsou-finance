package com.picsou.adapter;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps justETF's English labels onto the keys the rest of the app already speaks.
 *
 * <p>Sectors normalise to the eleven Morningstar keys the ETF slices and Yahoo's equity sectors
 * both use. An unmapped label is <strong>lowercased and underscored</strong> rather than passed
 * through verbatim — {@code BoursoramaLabels} does pass through, and copying that here would be a
 * real bug: a raw {@code "Utilities"} would become a bucket distinct from {@code utilities}, so a
 * portfolio holding one fund from each source would show the sector twice and score as more
 * diversified than it is.
 *
 * <p>Countries come from the JDK rather than a hand-kept table of 249 rows: the reverse index is
 * built from {@link Locale#getISOCountries()}, with a handful of overrides where justETF's
 * spelling differs from the JDK's display name.
 */
final class JustEtfLabels {

    private JustEtfLabels() {}

    /** The label justETF uses for the remainder it does not break out. */
    static final String OTHER_LABEL = "Other";

    private static final Map<String, String> SECTORS = Map.ofEntries(
        Map.entry("technology", "technology"),
        Map.entry("finance", "financial_services"),
        Map.entry("financials", "financial_services"),
        Map.entry("financial services", "financial_services"),
        Map.entry("healthcare", "healthcare"),
        Map.entry("health care", "healthcare"),
        Map.entry("industrials", "industrials"),
        Map.entry("consumer discretionary", "consumer_cyclical"),
        Map.entry("consumer cyclical", "consumer_cyclical"),
        Map.entry("consumer staples", "consumer_defensive"),
        Map.entry("consumer defensive", "consumer_defensive"),
        Map.entry("telecommunication", "communication_services"),
        Map.entry("telecommunications", "communication_services"),
        Map.entry("communication services", "communication_services"),
        Map.entry("utilities", "utilities"),
        Map.entry("energy", "energy"),
        Map.entry("basic materials", "basic_materials"),
        Map.entry("materials", "basic_materials"),
        Map.entry("real estate", "real_estate")
    );

    /** justETF spellings the JDK's English display names do not match. */
    private static final Map<String, String> COUNTRY_OVERRIDES = Map.of(
        "south korea", "KR",
        "russia", "RU",
        "taiwan", "TW",
        "usa", "US",
        "united states of america", "US",
        "great britain", "GB",
        "czech republic", "CZ",
        "hong kong sar", "HK"
    );

    private static final Map<String, String> COUNTRIES = buildCountryIndex();

    private static Map<String, String> buildCountryIndex() {
        Map<String, String> index = new HashMap<>();
        for (String code : Locale.getISOCountries()) {
            String english = Locale.of("", code).getDisplayCountry(Locale.ENGLISH);
            if (!english.isBlank()) index.putIfAbsent(normalize(english), code);
        }
        index.putAll(COUNTRY_OVERRIDES);
        return Map.copyOf(index);
    }

    /**
     * A Morningstar sector key, or a normalised form of whatever justETF said.
     *
     * @return null for the residual bucket, which is never stored — see the diversification
     *         service: an undisclosed remainder counts as unclassified rather than as a sector
     */
    static String sectorKey(String englishLabel) {
        if (englishLabel == null) return null;
        String normalized = normalize(englishLabel);
        if (normalized.isEmpty()) return null;
        String mapped = SECTORS.get(normalized);
        return mapped != null ? mapped : normalized.replace(' ', '_');
    }

    /** ISO 3166-1 alpha-2, or null when the name is not one the JDK or the overrides know. */
    static String countryKey(String englishLabel) {
        if (englishLabel == null) return null;
        return COUNTRIES.get(normalize(englishLabel));
    }

    static boolean isOther(String label) {
        return label != null && OTHER_LABEL.equalsIgnoreCase(label.trim());
    }

    /** Lowercase, strip accents, collapse spaces — same shape as {@code BoursoramaLabels}'. */
    private static String normalize(String s) {
        String noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
