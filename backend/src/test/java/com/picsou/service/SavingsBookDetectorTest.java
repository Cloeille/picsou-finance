package com.picsou.service;

import com.picsou.dto.SavingsBookSuggestion;
import com.picsou.model.SavingsProduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the name-based savings-book detector.
 * Covers: case-insensitivity, accent-insensitivity, word-boundary safety,
 * uncertain flag logic, and non-savings names.
 */
class SavingsBookDetectorTest {

    private final SavingsBookDetector detector = new NameBasedSavingsBookDetector();

    // ─── LIVRET A ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "Livret A",
        "LIVRET A",
        "livret a",
        "Mon Livret A BNP",
        "LIVRET_A"
    })
    void livretA_detected(String name) {
        Optional<SavingsBookSuggestion> result = detector.suggest(name);

        assertThat(result).isPresent();
        assertThat(result.get().suggestedProduct()).isEqualTo(SavingsProduct.LIVRET_A);
        assertThat(result.get().defaultAnnualRate()).isEqualByComparingTo(RegulatedRates.LIVRET_A);
        assertThat(result.get().uncertain()).isFalse();
    }

    // ─── LDDS ────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "LDDS",
        "LDD",
        "Livret Développement Durable et Solidaire",
        "Livret developpement durable",
        "LDD Crédit Agricole",
        "Mon LDDS"
    })
    void ldds_detected(String name) {
        Optional<SavingsBookSuggestion> result = detector.suggest(name);

        assertThat(result).isPresent();
        assertThat(result.get().suggestedProduct()).isEqualTo(SavingsProduct.LDDS);
        assertThat(result.get().defaultAnnualRate()).isEqualByComparingTo(RegulatedRates.LDDS);
        assertThat(result.get().uncertain()).isFalse();
    }

    // ─── LEP ─────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "LEP",
        "Livret Épargne Populaire",
        "Livret epargne populaire",
        "Mon LEP La Poste"
    })
    void lep_detected(String name) {
        Optional<SavingsBookSuggestion> result = detector.suggest(name);

        assertThat(result).isPresent();
        assertThat(result.get().suggestedProduct()).isEqualTo(SavingsProduct.LEP);
        assertThat(result.get().defaultAnnualRate()).isEqualByComparingTo(RegulatedRates.LEP);
        assertThat(result.get().uncertain()).isFalse();
    }

    // ─── Generic livret → COMMERCIAL (uncertain) ─────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "Livret",
        "Mon livret",
        "CSL",
        "Compte sur livret",
        "COMPTE SUR LIVRET",
        "Livret Boursorama"
    })
    void genericLivret_commercialUncertain(String name) {
        Optional<SavingsBookSuggestion> result = detector.suggest(name);

        assertThat(result).isPresent();
        assertThat(result.get().suggestedProduct()).isEqualTo(SavingsProduct.COMMERCIAL);
        assertThat(result.get().uncertain()).isTrue();
        assertThat(result.get().defaultAnnualRate()).isNull();
    }

    // ─── Accent-insensitivity ─────────────────────────────────────────────────

    @Test
    void accentInsensitive_ldds() {
        assertThat(detector.suggest("Livret Développement Durable"))
            .isPresent()
            .hasValueSatisfying(s -> assertThat(s.suggestedProduct()).isEqualTo(SavingsProduct.LDDS));
    }

    @Test
    void accentInsensitive_lep() {
        assertThat(detector.suggest("Épargne Populaire"))
            .isPresent()
            .hasValueSatisfying(s -> assertThat(s.suggestedProduct()).isEqualTo(SavingsProduct.LEP));
    }

    // ─── Non-savings names → no suggestion ───────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "Compte courant",
        "Compte chèques",
        "PEA",
        "Assurance-vie",
        "Crypto BTC",
        "",
        " "
    })
    void nonSavingsName_noSuggestion(String name) {
        assertThat(detector.suggest(name)).isEmpty();
    }

    @Test
    void nullName_noSuggestion() {
        assertThat(detector.suggest(null)).isEmpty();
    }

    // ─── Word-boundary safety: token not embedded in larger word ─────────────

    @Test
    void wordBoundary_lepNotMatchedInTeleport() {
        // "TELEPORT" contains "LEP" as a substring but should NOT trigger LEP detection.
        assertThat(detector.suggest("Teleport savings")).isEmpty();
    }

    @Test
    void wordBoundary_cslNotMatchedInPartialString() {
        // "CSL" should only match when it's a standalone token.
        // "OCSL" should not trigger COMMERCIAL.
        assertThat(detector.suggest("OCSL account")).isEmpty();
    }
}
