package com.picsou.adapter;

import com.picsou.dto.EtfComposition;
import com.picsou.dto.WeightedSlice;
import com.picsou.model.DistributionPolicy;
import com.picsou.model.Replication;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the static parser against real justETF markup, trimmed to the nodes it reads.
 *
 * <p>The fixtures are ~10 KB of a ~500 KB page. Keeping the whole thing would bury the fifteen
 * fields that matter under chrome, exactly as the Boursorama fixtures decided.
 */
class JustEtfProviderTest {

    private String fixture(String name) {
        try (var in = getClass().getResourceAsStream("/justetf/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void readsFeeDistributionPolicyAndBreakdown() {
        EtfComposition c = JustEtfProvider.parse(fixture("cw8-profile.html"), "LU1681043599").orElseThrow();

        assertThat(c.source()).isEqualTo("justETF");
        assertThat(c.asOf()).isEqualTo(LocalDate.of(2026, 6, 30));

        assertThat(c.facts().terPercent()).isEqualByComparingTo("0.38");
        assertThat(c.facts().distributionPolicy()).isEqualTo(DistributionPolicy.ACCUMULATING);
        assertThat(c.facts().replication()).isEqualTo(Replication.SYNTHETIC);
        assertThat(c.facts().domicileCountryKey()).isEqualTo("LU");

        assertThat(c.countries()).extracting(WeightedSlice::label)
            .containsExactly("US", "JP", "GB", "CA");
        assertThat(c.countries().getFirst().percent()).isEqualByComparingTo("69.70");

        // "Finance" is justETF's word for what the rest of the app calls financial_services.
        assertThat(c.sectors()).extracting(WeightedSlice::label)
            .containsExactly("technology", "financial_services", "industrials", "healthcare");
    }

    @Test
    void theUndisclosedRemainderIsNeverStoredAsASlice() {
        EtfComposition c = JustEtfProvider.parse(fixture("cw8-profile.html"), "LU1681043599").orElseThrow();

        // justETF names the residual (Other 17.84 %), so the share it covers is known to be
        // unallocated. Keeping it would claim a distribution we do not have; the diversification
        // service counts what is not placed as unclassified instead.
        assertThat(c.countries()).extracting(WeightedSlice::label).doesNotContain("other", "Other");
        assertThat(c.sectors()).extracting(WeightedSlice::label).doesNotContain("other", "Other");

        BigDecimalAssert.assertSumsToAbout(c.countries(), "82.16");
        BigDecimalAssert.assertSumsToAbout(c.sectors(), "71.82");
    }

    @Test
    void aSecondIssuerParsesTheSameWay() {
        // The basics table is read by testid, not by row order — this fund is Physical and Irish.
        EtfComposition c = JustEtfProvider.parse(fixture("iwda-profile.html"), "IE00B4L5Y983").orElseThrow();

        assertThat(c.facts().terPercent()).isEqualByComparingTo("0.20");
        assertThat(c.facts().replication()).isEqualTo(Replication.PHYSICAL);
        assertThat(c.facts().domicileCountryKey()).isEqualTo("IE");
        assertThat(c.countries()).extracting(WeightedSlice::label).containsExactly("US", "JP", "GB", "CA");
    }

    @Test
    void aPageAboutAnotherFundIsRefused() {
        // justETF answers an unknown ISIN with 200 and its screener, not a 404. Without the
        // identity guard, a plausible-looking page would be parsed as the requested fund.
        assertThat(JustEtfProvider.parse(fixture("screener-unknown-isin.html"), "LU3170240538")).isEmpty();

        // And the right shape of page, but the wrong fund, is refused too.
        assertThat(JustEtfProvider.parse(fixture("cw8-profile.html"), "IE00B4L5Y983")).isEmpty();
    }

    @Test
    void malformedOrEmptyInputYieldsNothingRatherThanThrowing() {
        assertThat(JustEtfProvider.parse("<html><body>truncated", "LU1681043599")).isEmpty();
        assertThat(JustEtfProvider.parse("", "LU1681043599")).isEmpty();
        assertThat(JustEtfProvider.parse(null, "LU1681043599")).isEmpty();
        assertThat(JustEtfProvider.parse(fixture("cw8-profile.html"), null)).isEmpty();
    }

    @Test
    void onlySecuritiesWithAnIsinAreAttempted() {
        JustEtfProvider provider = new JustEtfProvider(new JustEtfClient());

        assertThat(provider.supports(new com.picsou.dto.SecurityRef("CW8.PA", "Amundi", "LU1681043599"))).isTrue();
        // Boursorama can work from a bare ticker; justETF has no way to look one up.
        assertThat(provider.supports(com.picsou.dto.SecurityRef.of("CW8.PA", "Amundi"))).isFalse();
        assertThat(provider.fetch(com.picsou.dto.SecurityRef.of("CW8.PA", "Amundi"))).isEmpty();
    }

    /** Small helper so the sum assertions read as one line each. */
    private static final class BigDecimalAssert {
        static void assertSumsToAbout(java.util.List<WeightedSlice> slices, String expected) {
            java.math.BigDecimal sum = slices.stream()
                .map(WeightedSlice::percent)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            assertThat(sum).isEqualByComparingTo(expected);
        }
    }
}
