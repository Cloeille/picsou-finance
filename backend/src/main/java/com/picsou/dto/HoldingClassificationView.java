package com.picsou.dto;

import com.picsou.model.WealthTier;

/**
 * What the classification editor needs to open on a ticker: the member's own override, and
 * separately what the providers inferred.
 *
 * <p>The two are kept apart rather than merged into one "effective" value on purpose. A form
 * pre-filled with an inferred sector cannot tell the member whether they are confirming a guess
 * or reading their own earlier decision, and saving it would turn every guess into a permanent
 * hand-made override — the resolved value would then stop tracking the provider forever.
 *
 * @param inferredSectorKey  the provider's sector, or null when it has none. Non-null with a null
 *                           {@code sectorKey} means "shown greyed as the current value, not yet
 *                           adopted"
 * @param profileLooked      whether a lookup has happened at all, so "no sector" can be told
 *                           apart from "never asked"
 */
public record HoldingClassificationView(
    String ticker,
    WealthTier wealthTier,
    String sectorKey,
    String countryKey,
    String inferredSectorKey,
    String inferredCountryKey,
    boolean profileLooked
) {}
