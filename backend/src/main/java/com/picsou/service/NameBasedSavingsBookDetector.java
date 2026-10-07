package com.picsou.service;

import com.picsou.dto.SavingsBookSuggestion;
import com.picsou.model.SavingsProduct;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Optional;

/**
 * Name-based implementation of {@link SavingsBookDetector}.
 *
 * <p>Matches case-insensitively and accent-insensitively.  Rules are evaluated in
 * precedence order (most specific first to avoid "Livret A" matching the generic
 * "Livret" rule).</p>
 *
 * <p>This implementation never writes to the database.</p>
 */
@Service
public class NameBasedSavingsBookDetector implements SavingsBookDetector {

    /**
     * Normalises a string for matching: strips diacritics (NFD decomposition + strip
     * combining marks) then converts to upper-case.
     */
    static String normalise(String s) {
        if (s == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        // Remove all combining diacritical marks (Unicode block 0300–036F)
        String stripped = decomposed.replaceAll("\\p{InCombiningDiacriticalMarks}", "");
        return stripped.toUpperCase();
    }

    @Override
    public Optional<SavingsBookSuggestion> suggest(String accountName) {
        if (accountName == null || accountName.isBlank()) {
            return Optional.empty();
        }

        String normalised = normalise(accountName);

        // ── Livret A (check before generic "LIVRET" to avoid false match) ───────
        if (containsToken(normalised, "LIVRET A") || containsToken(normalised, "LIVRET_A")) {
            return certain(SavingsProduct.LIVRET_A, RegulatedRates.LIVRET_A);
        }

        // ── LDDS / LDD / Développement durable ──────────────────────────────────
        if (containsToken(normalised, "LDDS")
                || containsToken(normalised, "LDD")
                || normalised.contains("DEVELOPPEMENT DURABLE")) {
            return certain(SavingsProduct.LDDS, RegulatedRates.LDDS);
        }

        // ── LEP / Épargne populaire ──────────────────────────────────────────────
        if (containsToken(normalised, "LEP")
                || normalised.contains("EPARGNE POPULAIRE")) {
            return certain(SavingsProduct.LEP, RegulatedRates.LEP);
        }

        // ── Generic livret / CSL / Compte sur livret → COMMERCIAL (uncertain) ──
        if (normalised.contains("LIVRET")
                || containsToken(normalised, "CSL")
                || normalised.contains("COMPTE SUR LIVRET")) {
            return uncertain(SavingsProduct.COMMERCIAL, null);
        }

        return Optional.empty();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Returns true if {@code normalised} contains {@code token} as a word or phrase.
     * Uses simple {@code contains} for multi-word tokens; for single tokens, a word-boundary
     * check prevents "LEP" matching "TELEPORT" etc.
     */
    private boolean containsToken(String normalised, String token) {
        if (!normalised.contains(token)) {
            return false;
        }
        if (token.contains(" ") || token.contains("_")) {
            // Multi-word token: plain contains is sufficient
            return true;
        }
        // Single-word token: verify it stands alone (not embedded in a longer word)
        int idx = normalised.indexOf(token);
        while (idx >= 0) {
            boolean beforeOk = idx == 0 || !Character.isLetterOrDigit(normalised.charAt(idx - 1));
            boolean afterOk = (idx + token.length()) >= normalised.length()
                || !Character.isLetterOrDigit(normalised.charAt(idx + token.length()));
            if (beforeOk && afterOk) {
                return true;
            }
            idx = normalised.indexOf(token, idx + 1);
        }
        return false;
    }

    private Optional<SavingsBookSuggestion> certain(SavingsProduct product, BigDecimal defaultRate) {
        return Optional.of(new SavingsBookSuggestion(product, defaultRate, false));
    }

    private Optional<SavingsBookSuggestion> uncertain(SavingsProduct product, BigDecimal defaultRate) {
        return Optional.of(new SavingsBookSuggestion(product, defaultRate, true));
    }
}
