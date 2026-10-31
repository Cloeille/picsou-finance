package com.picsou.dto;

import com.picsou.model.SavingsProduct;

import java.math.BigDecimal;

/**
 * One savings-book suggestion returned by {@code GET /api/savings/suggestions}.
 *
 * <p>Produced only for bank-synced accounts (isManual=false) that:</p>
 * <ol>
 *   <li>Have no {@code SavingsInterestConfig} saved yet.</li>
 *   <li>Match a known savings-book name pattern (via {@link com.picsou.service.SavingsBookDetector}).</li>
 * </ol>
 *
 * <p>This is a suggestion only — the user must confirm before a config is persisted.</p>
 */
public record SavingsSuggestionResponse(
    Long accountId,
    String accountName,
    SavingsProduct suggestedProduct,
    BigDecimal defaultAnnualRate,
    boolean uncertain
) {}
