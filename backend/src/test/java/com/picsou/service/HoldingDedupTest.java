package com.picsou.service;

import com.picsou.service.HoldingDedup.HoldingAgg;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingDedupTest {

    @Test
    void vwapMerge_computesWeightedAverage() {
        // (qty=2, avg=10) + (qty=3, avg=20) → VWAP = (2*10 + 3*20) / 5 = 80/5 = 16
        HoldingAgg a = new HoldingAgg(bd("2"), bd("10"), bd("100"), "Foo");
        HoldingAgg b = new HoldingAgg(bd("3"), bd("20"), bd("110"), "Foo");

        HoldingAgg merged = HoldingDedup.vwapMerge(a, b);

        assertThat(merged.quantity()).isEqualByComparingTo("5");
        assertThat(merged.averageBuyIn()).isEqualByComparingTo("16.00000000");
    }

    @Test
    void vwapMerge_handlesNonIntegerVwap() {
        // (qty=10, avg=100) + (qty=20, avg=200) → VWAP = 5000/30 = 166.66666667
        HoldingAgg a = new HoldingAgg(bd("10"), bd("100"), null, null);
        HoldingAgg b = new HoldingAgg(bd("20"), bd("200"), null, null);

        HoldingAgg merged = HoldingDedup.vwapMerge(a, b);

        assertThat(merged.quantity()).isEqualByComparingTo("30");
        assertThat(merged.averageBuyIn()).isEqualByComparingTo("166.66666667");
    }

    @Test
    void vwapMerge_isOrderIndependent() {
        // VWAP must be symmetric: merge(a,b) == merge(b,a) for the average
        HoldingAgg a = new HoldingAgg(bd("2"), bd("10"), bd("100"), "A");
        HoldingAgg b = new HoldingAgg(bd("3"), bd("20"), bd("110"), "B");

        BigDecimal avgAB = HoldingDedup.vwapMerge(a, b).averageBuyIn();
        BigDecimal avgBA = HoldingDedup.vwapMerge(b, a).averageBuyIn();

        assertThat(avgAB).isEqualByComparingTo(avgBA);
    }

    @Test
    void vwapMerge_treatsNullAverageAsZero() {
        // (qty=2, avg=null) + (qty=3, avg=20) → VWAP = (0 + 60) / 5 = 12
        HoldingAgg a = new HoldingAgg(bd("2"), null, null, null);
        HoldingAgg b = new HoldingAgg(bd("3"), bd("20"), null, null);

        HoldingAgg merged = HoldingDedup.vwapMerge(a, b);

        assertThat(merged.averageBuyIn()).isEqualByComparingTo("12.00000000");
    }

    /**
     * Superseded by plan 003 (issue D): the merge used to return {@code prev} unchanged
     * (a stale non-zero position) whenever the total quantity was zero, purely to dodge
     * the divide-by-zero. Now that IBKR positions can be signed, a zero net quantity is
     * a real, common outcome (a covered short) and must produce a flat sentinel instead
     * of resurrecting whichever side happened to be "prev".
     */
    @Test
    void vwapMerge_totalQuantityZero_returnsZeroQuantitySentinel() {
        // Two already-flat aggregates merging: still zero, not a divide-by-zero crash.
        HoldingAgg a = new HoldingAgg(BigDecimal.ZERO, bd("10"), bd("100"), "A");
        HoldingAgg b = new HoldingAgg(BigDecimal.ZERO, bd("20"), bd("110"), "B");

        HoldingAgg merged = HoldingDedup.vwapMerge(a, b);

        assertThat(merged.quantity()).isEqualByComparingTo("0");
        assertThat(merged.averageBuyIn()).isNull();
    }

    @Test
    void vwapMerge_keepsFirstNonNullName() {
        HoldingAgg withName = new HoldingAgg(bd("1"), bd("1"), null, "FromIsinA");
        HoldingAgg withoutName = new HoldingAgg(bd("1"), bd("1"), null, null);

        assertThat(HoldingDedup.vwapMerge(withName, withoutName).name()).isEqualTo("FromIsinA");
        assertThat(HoldingDedup.vwapMerge(withoutName, withName).name()).isEqualTo("FromIsinA");
    }

    @Test
    void vwapMerge_fallsBackToNextCurrentPriceWhenPrevIsNull() {
        HoldingAgg a = new HoldingAgg(bd("1"), bd("1"), null, "A");
        HoldingAgg b = new HoldingAgg(bd("1"), bd("1"), bd("42"), "B");

        assertThat(HoldingDedup.vwapMerge(a, b).currentPrice()).isEqualByComparingTo("42");
    }

    // -- Plan 003 / issue D: sign-aware merge (IBKR can carry short quantities) --------

    @Test
    void vwapMerge_oppositeSignsNettingToZero_returnsZeroQuantitySentinel() {
        HoldingAgg longLeg = new HoldingAgg(bd("40"), bd("10"), bd("12"), "Acme");
        HoldingAgg shortLeg = new HoldingAgg(bd("-40"), bd("15"), bd("12"), "Acme");

        HoldingAgg merged = HoldingDedup.vwapMerge(longLeg, shortLeg);

        assertThat(merged.quantity()).isEqualByComparingTo("0");
        assertThat(merged.averageBuyIn()).isNull();
    }

    @Test
    void vwapMerge_oppositeSignsNonZeroNet_netsQuantityAndKeepsLargerSideAverage() {
        // +100 @ 10 vs -40 @ 20 -> net 60, average from the larger-|q| side (the +100 @ 10 leg).
        HoldingAgg big = new HoldingAgg(bd("100"), bd("10"), bd("12"), "Acme");
        HoldingAgg small = new HoldingAgg(bd("-40"), bd("20"), bd("12"), "Acme");

        HoldingAgg merged = HoldingDedup.vwapMerge(big, small);

        assertThat(merged.quantity()).isEqualByComparingTo("60");
        assertThat(merged.averageBuyIn()).isEqualByComparingTo("10");
    }

    @Test
    void vwapMerge_oppositeSignsNonZeroNet_isOrderIndependent() {
        // Same scenario as above with the arguments swapped: the larger-|q| side's
        // average must win regardless of which argument is "prev" and which is "next".
        HoldingAgg big = new HoldingAgg(bd("100"), bd("10"), bd("12"), "Acme");
        HoldingAgg small = new HoldingAgg(bd("-40"), bd("20"), bd("12"), "Acme");

        HoldingAgg merged = HoldingDedup.vwapMerge(small, big);

        assertThat(merged.quantity()).isEqualByComparingTo("60");
        assertThat(merged.averageBuyIn()).isEqualByComparingTo("10");
    }

    @Test
    void vwapMerge_sameSigns_unchangedVwapRegression() {
        // +10 @ 5 and +30 @ 9 -> (10*5 + 30*9) / 40 = 320/40 = 8.
        HoldingAgg a = new HoldingAgg(bd("10"), bd("5"), bd("100"), "Acme");
        HoldingAgg b = new HoldingAgg(bd("30"), bd("9"), bd("100"), "Acme");

        HoldingAgg merged = HoldingDedup.vwapMerge(a, b);

        assertThat(merged.quantity()).isEqualByComparingTo("40");
        assertThat(merged.averageBuyIn()).isEqualByComparingTo("8");
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
