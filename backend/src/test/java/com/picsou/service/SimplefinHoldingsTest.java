package com.picsou.service;

import com.picsou.port.SimplefinPort.SimplefinHolding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The mapping rules, with synthetic figures shaped like what a US broker reports. */
class SimplefinHoldingsTest {

    /** Maps in USD. */
    private static SimplefinHoldings.Mapped map(SimplefinHolding... lines) {
        return SimplefinHoldings.map(List.of(lines), "USD");
    }

    private static SimplefinHolding h(String symbol, String shares, String marketValue,
                                      String costBasis, String purchasePrice) {
        return new SimplefinHolding(symbol, symbol + " name", "USD", new BigDecimal(shares),
            marketValue == null ? null : new BigDecimal(marketValue),
            costBasis == null ? null : new BigDecimal(costBasis),
            purchasePrice == null ? null : new BigDecimal(purchasePrice));
    }

    @Test
    void positiveCostBasis_isPreferredAndDividedByShares() {
        var mapped = map(h("AAA", "4", "500", "400", "90"));

        assertThat(mapped.positions()).singleElement()
            .satisfies(p -> assertThat(p.averageBuyIn()).isEqualByComparingTo("100"));
    }

    @Test
    void zeroCostBasis_usesThePurchasePrice() {
        var mapped = map(h("AAA", "4", "500", "0.00", "90"));

        assertThat(mapped.positions()).singleElement()
            .satisfies(p -> assertThat(p.averageBuyIn()).isEqualByComparingTo("90"));
    }

    @Test
    void noCostInformation_leavesTheBuyInUnknown() {
        var mapped = map(h("AAA", "4", "500", "0.00", "0.00"));

        assertThat(mapped.positions()).singleElement().satisfies(p -> assertThat(p.averageBuyIn()).isNull());
    }

    @Test
    void currentPrice_isMarketValuePerShare() {
        var mapped = map(h("AAA", "4", "500", null, null));

        assertThat(mapped.positions()).singleElement()
            .satisfies(p -> assertThat(p.currentPrice()).isEqualByComparingTo("125"));
    }

    @Test
    void moneyMarketSweep_isCash() {
        var mapped = map(h("SPAXX", "100", "100", "0", "0"));

        assertThat(mapped.positions()).isEmpty();
        assertThat(mapped.sweepByCurrency().get("USD")).isEqualByComparingTo("100");
        assertThat(mapped.complete()).isTrue();
    }

    @Test
    void symbolEndingInXxAtAnotherPrice_isNotCash() {
        var mapped = map(h("ABXX", "10", "500", "0", "40"));

        assertThat(mapped.positions()).hasSize(1);
        assertThat(mapped.sweepByCurrency().getOrDefault("USD", BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void oneDollarSharePriceWithoutTheXxSuffix_isNotCash() {
        var mapped = map(h("PENNY", "10", "10", "0", "0"));

        assertThat(mapped.positions()).hasSize(1);
        assertThat(mapped.sweepByCurrency().getOrDefault("USD", BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void zeroShareLine_isNothingHeldAndStillComplete() {
        var mapped = map(h("AAA", "0.00", "0.00", "0", "0"));

        assertThat(mapped.positions()).isEmpty();
        assertThat(mapped.sweepByCurrency()).isEmpty();
        assertThat(mapped.complete()).isTrue();
    }

    @Test
    void zeroShareLineInAnUnknownCurrency_isStillNothingHeld() {
        var mapped = map(h("AAA", "4", "500", "0", "0"),
            new SimplefinHolding("OLD", null, "US Dollar", BigDecimal.ZERO, BigDecimal.ZERO, null, null));

        assertThat(mapped.complete()).isTrue();
    }

    // ---- merging lots -------------------------------------------------------------------

    @Test
    void sameTickerTwice_isOnePositionWithAWeightedBuyIn() {
        var mapped = map(h("AAA", "1", "10", "0", "10"), h("AAA", "3", "60", "0", "30"));

        assertThat(mapped.positions()).singleElement().satisfies(p -> {
            assertThat(p.quantity()).isEqualByComparingTo("4");
            assertThat(p.averageBuyIn()).isEqualByComparingTo("25");
        });
        assertThat(mapped.complete()).isTrue();
    }

    @Test
    void mergedLots_areValuedAtTheirCombinedMarketValueNotTheFirstPrice() {
        var mapped = map(h("AAA", "1", "10", null, null), h("AAA", "3", "60", null, null));

        assertThat(mapped.positions()).singleElement().satisfies(p -> {
            assertThat(p.marketValue()).isEqualByComparingTo("70");
            assertThat(p.currentPrice()).isEqualByComparingTo("17.5");
        });
    }

    @Test
    void mergedLots_withOneUnknownValue_haveNoValueAtAll() {
        var mapped = map(h("AAA", "1", "10", null, null),
            new SimplefinHolding("AAA", null, "USD", new BigDecimal("3"), null, null, null));

        assertThat(mapped.positions()).singleElement().satisfies(p -> {
            assertThat(p.marketValue()).isNull();
            assertThat(p.currentPrice()).isNull();
        });
    }

    @Test
    void mergedLots_withOneUnknownBuyIn_haveNoBuyIn() {
        var mapped = map(h("AAA", "1", "10", null, "10"), h("AAA", "3", "60", null, null));

        assertThat(mapped.positions()).singleElement().satisfies(p -> assertThat(p.averageBuyIn()).isNull());
    }

    @Test
    void sameTickerInTwoCurrencies_cannotBeMergedAndIsIncomplete() {
        var mapped = map(h("AAA", "1", "10", null, null),
            new SimplefinHolding("AAA", null, "CAD", BigDecimal.ONE, BigDecimal.TEN, null, null));

        assertThat(mapped.complete()).isFalse();
    }

    @Test
    void mergedQuantityPastTheColumn_isIncomplete() {
        var mapped = map(h("AAA", "999999999999", "0", null, null), h("AAA", "999999999999", "0", null, null));

        assertThat(mapped.complete()).isFalse();
    }

    // ---- lines the sync cannot carry over -----------------------------------------------

    @Test
    void symbolLessLineAtOneDollar_isACashLineNotALostBalance() {
        var mapped = SimplefinHoldings.map(
            List.of(new SimplefinHolding(null, "Cash", "USD", new BigDecimal("250"), new BigDecimal("250"), null, null)),
            "USD");

        assertThat(mapped.positions()).isEmpty();
        assertThat(mapped.sweepByCurrency().get("USD")).isEqualByComparingTo("250");
        assertThat(mapped.complete()).isTrue();
    }

    @Test
    void lineWithoutSymbol_cannotBePricedAndIsIncomplete() {
        var mapped = SimplefinHoldings.map(
            List.of(new SimplefinHolding(null, "Mystery", "USD", BigDecimal.ONE, BigDecimal.TEN, null, null)),
            "USD");

        assertThat(mapped.positions()).isEmpty();
        assertThat(mapped.complete()).isFalse();
    }

    @Test
    void currencyThatIsNotAnIsoCode_dropsTheLineAndIsIncomplete() {
        var mapped = SimplefinHoldings.map(
            List.of(new SimplefinHolding("AAA", null, "US Dollar", BigDecimal.ONE, BigDecimal.TEN, null, null)),
            "USD");

        assertThat(mapped.positions()).isEmpty();
        assertThat(mapped.complete()).isFalse();
    }

    @Test
    void currencyWithStrayCaseAndSpaces_isNormalised() {
        var mapped = SimplefinHoldings.map(
            List.of(new SimplefinHolding("AAA", null, " usd ", BigDecimal.ONE, BigDecimal.TEN, null, null)),
            "CAD");

        assertThat(mapped.positions()).singleElement().satisfies(p -> assertThat(p.currency()).isEqualTo("USD"));
    }

    @Test
    void figureTooBigForTheColumn_dropsTheLineAndIsIncomplete() {
        var mapped = map(h("AAA", "1", "10000000000000", "0", "0"), h("BBB", "1", "10", "0", "0"));

        assertThat(mapped.positions()).extracting(p -> p.ticker()).containsExactly("BBB");
        assertThat(mapped.complete()).isFalse();
    }

    @Test
    void figureThatRoundsPastTheColumn_isIncomplete() {
        var mapped = map(h("AAA", "1", "999999999999.999999999", "0", "0"));

        assertThat(mapped.complete()).isFalse();
    }

    @Test
    void figureThatRoundsJustUnderTheColumnCeiling_stillFits() {
        var mapped = map(h("AAA", "1", "999999999999.999999994", "0", "0"));

        assertThat(mapped.complete()).isTrue();
    }

    @Test
    void derivedPriceThatOverflowsTheColumn_isIncomplete() {
        var mapped = map(h("AAA", "0.00000001", "20000", "0", "0"));

        assertThat(mapped.positions()).isEmpty();
        assertThat(mapped.complete()).isFalse();
    }

    @Test
    void derivedBuyInThatOverflowsTheColumn_isIncomplete() {
        var mapped = map(h("AAA", "0.00000001", "1", "20000", "0"));

        assertThat(mapped.complete()).isFalse();
    }

    @Test
    void shortPosition_isIncompleteNotSilentlyDropped() {
        var mapped = map(h("AAA", "-5", "-500", "0", "0"), h("BBB", "1", "500", "0", "0"));

        assertThat(mapped.positions()).extracting(p -> p.ticker()).containsExactly("BBB");
        assertThat(mapped.complete()).isFalse();
    }

    @Test
    void lineWithoutCurrency_inheritsTheAccountCurrency() {
        var mapped = SimplefinHoldings.map(
            List.of(new SimplefinHolding("AAA", null, null, BigDecimal.ONE, BigDecimal.TEN, null, null)),
            "CAD");

        assertThat(mapped.positions()).singleElement().satisfies(p -> assertThat(p.currency()).isEqualTo("CAD"));
    }

    // ---- sweep --------------------------------------------------------------------------

    @Test
    void sweepLines_areSummedPerCurrency() {
        var mapped = SimplefinHoldings.map(List.of(
            h("SPAXX", "100", "100", "0", "0"),
            h("FDRXX", "50", "50", "0", "0"),
            new SimplefinHolding("TDXX", null, "CAD", new BigDecimal("30"), new BigDecimal("30"), null, null)),
            "USD");

        assertThat(mapped.positions()).isEmpty();
        assertThat(mapped.sweepByCurrency()).containsOnlyKeys("USD", "CAD");
        assertThat(mapped.sweepByCurrency().get("USD")).isEqualByComparingTo("150");
        assertThat(mapped.sweepByCurrency().get("CAD")).isEqualByComparingTo("30");
        assertThat(mapped.complete()).isTrue();
    }

    @Test
    void sweepBesideAPosition_isNotMixedWithIt() {
        var mapped = map(h("AAA", "10", "1000", null, null), h("SPAXX", "200", "200", "0", "0"));

        assertThat(mapped.positions()).extracting(p -> p.ticker()).containsExactly("AAA");
        assertThat(mapped.sweepByCurrency()).containsOnlyKeys("USD");
        assertThat(mapped.sweepByCurrency().get("USD")).isEqualByComparingTo("200");
    }

    @Test
    void sweepPastTheColumn_isIncomplete() {
        var mapped = map(h("SPAXX", "999999999999", "999999999999", "0", "0"),
            h("FDRXX", "999999999999", "999999999999", "0", "0"));

        assertThat(mapped.sweepByCurrency()).isEmpty();
        assertThat(mapped.complete()).isFalse();
    }
}
