package com.picsou.dto;

import com.picsou.model.SavingsProduct;

import java.math.BigDecimal;

/**
 * Suggestion produced by {@link com.picsou.service.SavingsBookDetector} when an account name
 * looks like a savings book.
 *
 * <p>This record is part of the shared contract for the REST API (Stream B) and the
 * frontend (Stream C).  The caller must confirm the suggestion before persisting a
 * {@code SavingsInterestConfig} — the detector never writes anything.</p>
 *
 * @param suggestedProduct  The inferred savings-book product type.
 * @param defaultAnnualRate Default annual rate (percentage) from {@link com.picsou.service.RegulatedRates},
 *                          or {@code null} for {@code COMMERCIAL} (rate is user-defined).
 * @param uncertain         {@code true} when the match is ambiguous (e.g. generic "Livret"
 *                          without a qualifying suffix), signalling that the user should
 *                          review and potentially override the suggestion.
 */
public record SavingsBookSuggestion(
    SavingsProduct suggestedProduct,
    BigDecimal defaultAnnualRate,
    boolean uncertain
) {}
