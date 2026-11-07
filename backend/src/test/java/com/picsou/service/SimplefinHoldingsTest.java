package com.picsou.service;

import com.picsou.port.SimplefinPort.SimplefinHolding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The mapping rules, with synthetic figures shaped like what a US broker reports. */
class SimplefinHoldingsTest {

    private static SimplefinHolding h(String symbol, String shares, String marketValue,
                                      String costBasis, String purchasePrice) {
        return new SimplefinHolding(symbol, symbol + " name", "USD", new BigDecimal(shares),
            marketValue == null ? null : new BigDecimal(marketValue),
            costBasis == null ? null : new BigDecimal(costBasis),
            purchasePrice == null ? null : new BigDecimal(purchasePrice));
    }

    @Test
    void positiveCostBasis_isPreferredAndDividedByShares() {
        var mapped = SimplefinHoldings.map(List.of(h("AAA", "4", "500", "400", "90")), "USD");

        assertThat(mapped.positions()).singleElement()
            .satisfies(p -> assertThat(p.averageBuyIn()).isEqualByComparingTo("100"));
    }

    @Test
    void zeroCostBasis_usesThePurchasePrice() {
        var mapped = SimplefinHoldings.map(List.of(h("AAA", "4", "500", "0.00", "90")), "USD");

        assertThat(mapped.positions()).singleElement()
            .satisfies(p -> assertThat(p.averageBuyIn()).isEqualByComparingTo("90"));
    }

    @Test
    void noCostInformation_leavesTheBuyInUnknown() {
        var mapped = SimplefinHoldings.map(List.of(h("AAA", "4", "500", "0.00", "0.00")), "USD");

        assertThat(mapped.positions()).singleElement().satisfies(p -> assertThat(p.averageBuyIn()).isNull());
    }

    @Test
    void currentPrice_isMarketValuePerShare() {
        var mapped = SimplefinHoldings.map(List.of(h("AAA", "4", "500", null, null)), "USD");

        assertThat(mapped.positions()).singleElement()
            .satisfies(p -> assertThat(p.currentPrice()).isEqualByComparingTo("125"));
    }

    @Test
    void moneyMarketSweep_isCash() {
        var mapped = SimplefinHoldings.map(List.of(h("SPAXX", "100", "100", "0", "0")), "USD");

        assertThat(mapped.positions()).isEmpty();
        assertThat(mapped.cashByCurrency().get("USD")).isEqualByComparingTo("100");
    }

    @Test
    void symbolEndingInXxAtAnotherPrice_isNotCash() {
        var mapped = SimplefinHoldings.map(List.of(h("ABXX", "10", "500", "0", "40")), "USD");

        assertThat(mapped.positions()).hasSize(1);
        assertThat(mapped.cashByCurrency()).isEmpty();
    }

    @Test
    void oneDollarSharePriceWithoutTheXxSuffix_isNotCash() {
        var mapped = SimplefinHoldings.map(List.of(h("PENNY", "10", "10", "0", "0")), "USD");

        assertThat(mapped.positions()).hasSize(1);
        assertThat(mapped.cashByCurrency()).isEmpty();
    }

    @Test
    void zeroShareLine_isNothingHeld() {
        var mapped = SimplefinHoldings.map(List.of(h("AAA", "0.00", "0.00", "0", "0")), "USD");

        assertThat(mapped.positions()).isEmpty();
        assertThat(mapped.cashByCurrency()).isEmpty();
    }

    @Test
    void sameTickerTwice_isOnePositionWithAWeightedBuyIn() {
        var mapped = SimplefinHoldings.map(
            List.of(h("AAA", "1", "10", "0", "10"), h("AAA", "3", "60", "0", "30")), "USD");

        assertThat(mapped.positions()).singleElement().satisfies(p -> {
            assertThat(p.quantity()).isEqualByComparingTo("4");
            assertThat(p.averageBuyIn()).isEqualByComparingTo("25");
        });
    }

    @Test
    void symbolLessLineAtOneDollar_isACashLineNotALostBalance() {
        var mapped = SimplefinHoldings.map(
            List.of(new SimplefinHolding(null, "Cash", "USD", new BigDecimal("250"), new BigDecimal("250"), null, null)),
            "USD");

        assertThat(mapped.positions()).isEmpty();
        assertThat(mapped.cashByCurrency().get("USD")).isEqualByComparingTo("250");
    }

    @Test
    void lineWithoutSymbol_isSkippedSinceItCannotBePriced() {
        var mapped = SimplefinHoldings.map(
            List.of(new SimplefinHolding(null, "Mystery", "USD", BigDecimal.ONE, BigDecimal.TEN, null, null)), "USD");

        assertThat(mapped.positions()).isEmpty();
    }

    @Test
    void currencyThatIsNotAnIsoCode_dropsTheLineInsteadOfFailingTheInsert() {
        var mapped = SimplefinHoldings.map(
            List.of(new SimplefinHolding("AAA", null, "US Dollar", BigDecimal.ONE, BigDecimal.TEN, null, null)), "USD");

        assertThat(mapped.positions()).isEmpty();
    }

    @Test
    void currencyWithStrayCaseAndSpaces_isNormalised() {
        var mapped = SimplefinHoldings.map(
            List.of(new SimplefinHolding("AAA", null, " usd ", BigDecimal.ONE, BigDecimal.TEN, null, null)), "CAD");

        assertThat(mapped.positions()).singleElement().satisfies(p -> assertThat(p.currency()).isEqualTo("USD"));
    }

    @Test
    void figureTooBigForTheColumn_dropsTheLineInsteadOfAbortingTheSync() {
        var mapped = SimplefinHoldings.map(
            List.of(h("AAA", "1", "10000000000000", "0", "0"), h("BBB", "1", "10", "0", "0")), "USD");

        assertThat(mapped.positions()).extracting(p -> p.ticker()).containsExactly("BBB");
    }

    @Test
    void lineWithoutCurrency_inheritsTheAccountCurrency() {
        var mapped = SimplefinHoldings.map(
            List.of(new SimplefinHolding("AAA", null, null, BigDecimal.ONE, BigDecimal.TEN, null, null)), "CAD");

        assertThat(mapped.positions()).singleElement().satisfies(p -> assertThat(p.currency()).isEqualTo("CAD"));
    }
}
