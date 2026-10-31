package com.picsou.service;

import com.picsou.dto.SavingsBookSuggestion;

import java.util.Optional;

/**
 * Analyses an account name and returns a savings-book product suggestion.
 *
 * <p>Implementations must be:</p>
 * <ul>
 *   <li><strong>Pure (read-only)</strong> — never persist anything.</li>
 *   <li><strong>Classification = SUGGEST</strong> — the caller is responsible for
 *       confirming and saving the result.</li>
 * </ul>
 */
public interface SavingsBookDetector {

    /**
     * Inspects {@code accountName} and, if it matches a known savings-book pattern,
     * returns a suggestion with the inferred product and its default regulated rate.
     *
     * @param accountName the account display name (may be null or blank)
     * @return an {@link Optional} containing a suggestion, or empty if the name does not
     *         match any savings-book pattern
     */
    Optional<SavingsBookSuggestion> suggest(String accountName);
}
