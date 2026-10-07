package com.picsou.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping matters more than it looks: an unmapped label that reaches the breakdown verbatim
 * becomes a bucket of its own, so one portfolio would show a sector twice and score as more
 * diversified than it is.
 */
class JustEtfLabelsTest {

    @Test
    void justEtfsOwnWordsBecomeTheKeysTheRestOfTheAppUses() {
        assertThat(JustEtfLabels.sectorKey("Finance")).isEqualTo("financial_services");
        assertThat(JustEtfLabels.sectorKey("Technology")).isEqualTo("technology");
        assertThat(JustEtfLabels.sectorKey("Healthcare")).isEqualTo("healthcare");
        assertThat(JustEtfLabels.sectorKey("Consumer Discretionary")).isEqualTo("consumer_cyclical");
        assertThat(JustEtfLabels.sectorKey("Consumer Staples")).isEqualTo("consumer_defensive");
        assertThat(JustEtfLabels.sectorKey("Telecommunication")).isEqualTo("communication_services");
    }

    @Test
    void anUnmappedSectorIsNormalisedRatherThanPassedThrough() {
        // Boursorama's labels class passes unknown values through verbatim. Copying that here
        // would let a raw "Utilities" sit next to Boursorama's "utilities" as a separate slice.
        assertThat(JustEtfLabels.sectorKey("Utilities")).isEqualTo("utilities");
        assertThat(JustEtfLabels.sectorKey("Something New")).isEqualTo("something_new");
        assertThat(JustEtfLabels.sectorKey("  ")).isNull();
        assertThat(JustEtfLabels.sectorKey(null)).isNull();
    }

    @Test
    void countryNamesResolveToIsoCodes() {
        assertThat(JustEtfLabels.countryKey("United States")).isEqualTo("US");
        assertThat(JustEtfLabels.countryKey("United Kingdom")).isEqualTo("GB");
        assertThat(JustEtfLabels.countryKey("Japan")).isEqualTo("JP");
        assertThat(JustEtfLabels.countryKey("Luxembourg")).isEqualTo("LU");
        assertThat(JustEtfLabels.countryKey("Ireland")).isEqualTo("IE");
        // Spellings the JDK's display names do not produce.
        assertThat(JustEtfLabels.countryKey("South Korea")).isEqualTo("KR");
        assertThat(JustEtfLabels.countryKey("Taiwan")).isEqualTo("TW");
    }

    @Test
    void anUnknownCountryIsDroppedRatherThanInvented() {
        // Null keeps it out of the breakdown entirely, where it shows up honestly as uncovered —
        // better than a bucket named after a string nobody can translate.
        assertThat(JustEtfLabels.countryKey("Atlantis")).isNull();
        assertThat(JustEtfLabels.countryKey(null)).isNull();
    }

    @Test
    void theResidualBucketIsRecognisedWhateverTheCasing() {
        assertThat(JustEtfLabels.isOther("Other")).isTrue();
        assertThat(JustEtfLabels.isOther(" other ")).isTrue();
        assertThat(JustEtfLabels.isOther("Others")).isFalse();
        assertThat(JustEtfLabels.isOther(null)).isFalse();
    }
}
