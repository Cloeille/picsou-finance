package com.picsou.dto;

import com.picsou.model.HoldingClassification;
import com.picsou.model.WealthTier;

/**
 * The override now in force for a ticker. All three fields null means there is none — the row is
 * deleted rather than kept blank, so "no override" and "deliberately blank" cannot diverge.
 */
public record HoldingClassificationResponse(
    String ticker,
    WealthTier wealthTier,
    String sectorKey,
    String countryKey
) {
    public static HoldingClassificationResponse from(String ticker, HoldingClassification saved) {
        return saved == null
            ? new HoldingClassificationResponse(ticker.toUpperCase(java.util.Locale.ROOT), null, null, null)
            : new HoldingClassificationResponse(saved.getTicker(), saved.getWealthTier(),
                saved.getSectorKey(), saved.getCountryKey());
    }
}
