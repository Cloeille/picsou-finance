package com.picsou.service.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure, no-Spring tests for the keystone of clean transaction names. Cases mirror the real
 * shapes Enable Banking / PSD2 feeds produce (processor wrappers, card refs, dates, French
 * transaction-type prefixes).
 */
class MerchantNormalizerTest {

    @Test
    void stripsPaymentProcessorWrapper_keepingTheRealMerchant() {
        assertThat(MerchantNormalizer.normalize("PAYPAL *SPOTIFY 4357", null)).isEqualTo("Spotify");
        // Everything before the last '*' (incl. a CB prefix) is the processor — dropped.
        assertThat(MerchantNormalizer.normalize("CB PAYPAL *NETFLIX", null)).isEqualTo("Netflix");
        assertThat(MerchantNormalizer.normalize("SUMUP *BOULANGERIE DUPONT", null))
            .isEqualTo("Boulangerie Dupont");
    }

    @Test
    void stripsLeadingTransactionNoise_cardRefsAndDates() {
        assertThat(MerchantNormalizer.normalize("CB CARREFOUR MARKET PARIS 12 01/02", null))
            .isEqualTo("Carrefour Market Paris 12");
        assertThat(MerchantNormalizer.normalize("CARTE 12/06 AUCHAN 4521", null)).isEqualTo("Auchan");
        assertThat(MerchantNormalizer.normalize("MCDONALD'S 1234 PARIS", null)).isEqualTo("Mcdonalds Paris");
    }

    @Test
    void stripsSepaTransferPrefix_fromDescription_whenNoCounterparty() {
        assertThat(MerchantNormalizer.normalize("", "VIR SEPA SALAIRE ACME")).isEqualTo("Salaire Acme");
        assertThat(MerchantNormalizer.normalize(null, "PRLV SEPA NETFLIX.COM")).isEqualTo("Netflix.com");
    }

    @Test
    void prefersCounterpartyOverDescription_andFallsBackWhenBlank() {
        assertThat(MerchantNormalizer.normalize("CARREFOUR", "CB CARREFOUR PARIS")).isEqualTo("Carrefour");
        assertThat(MerchantNormalizer.normalize(null, null)).isEmpty();
        assertThat(MerchantNormalizer.normalize("   ", "  ")).isEmpty();
    }

    @Test
    void neverReturnsEmptyWhenInputHasContent_evenIfAllNoise() {
        // Stripping must not erase a real label down to nothing: fall back to the trimmed original.
        assertThat(MerchantNormalizer.normalize("123456", null)).isNotEmpty();
    }

    @Test
    void matchKey_isLowerCaseAccentFreeAndApostropheStripped() {
        assertThat(MerchantNormalizer.matchKey("Café Crème")).isEqualTo("cafe creme");
        assertThat(MerchantNormalizer.matchKey("E.Leclerc")).isEqualTo("e.leclerc");
        assertThat(MerchantNormalizer.matchKey("O'Tacos")).isEqualTo("otacos");
        assertThat(MerchantNormalizer.matchKey("  Spotify  ")).isEqualTo("spotify");
        assertThat(MerchantNormalizer.matchKey(null)).isEmpty();
    }
}
