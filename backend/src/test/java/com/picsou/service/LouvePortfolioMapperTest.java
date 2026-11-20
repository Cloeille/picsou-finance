package com.picsou.service;

import com.picsou.adapter.LouveAdapter;
import com.picsou.exception.SyncException;
import com.picsou.model.DistributionFrequency;
import com.picsou.port.SidecarErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Synthetic Louve Invest snapshots only. */
class LouvePortfolioMapperTest {

    private static final List<String> FULL = List.of("Pleine propriété");

    private static LouveAdapter.Position line(String code, String shares, String sellingTotal, List<String> ownership) {
        return new LouveAdapter.Position(code, code.toUpperCase(), new BigDecimal(shares),
            sellingTotal == null ? null : new BigDecimal(sellingTotal), ownership);
    }

    private static LouveAdapter.Fund sheet(String code, String subscription, String withdrawal) {
        return new LouveAdapter.Fund(code, "Sheet " + code, "FR0000SYNTH6", "Exemple REIM",
            subscription == null ? null : new BigDecimal(subscription),
            withdrawal == null ? null : new BigDecimal(withdrawal), new BigDecimal("0.08"), "monthly");
    }

    private static LouveAdapter.Snapshot snapshot(String total, List<LouveAdapter.Position> lines,
                                                  Map<String, LouveAdapter.Fund> funds) {
        return new LouveAdapter.Snapshot(total == null ? null : new BigDecimal(total), lines, funds);
    }

    @Test
    void aFullOwnershipLineIsValuedAtTheWithdrawalPriceOfItsSheet() {
        LouvePortfolioMapper.Mapped mapped = LouvePortfolioMapper.map(snapshot("960",
            List.of(line("aurore", "12", "960", FULL)), Map.of("aurore", sheet("aurore", "80", "80"))));

        assertThat(mapped.unsupported()).isEmpty();
        assertThat(mapped.funds()).singleElement().satisfies(fund -> {
            assertThat(fund.fundCode()).isEqualTo("aurore");
            assertThat(fund.quantity()).isEqualByComparingTo("12");
            assertThat(fund.withdrawalPrice()).isEqualByComparingTo("80");
            assertThat(fund.subscriptionPrice()).isEqualByComparingTo("80");
            assertThat(fund.managementCompany()).isEqualTo("Exemple REIM");
            assertThat(fund.isin()).isEqualTo("FR0000SYNTH6");
            // 0.08 x 80 subscription price, the "taux de distribution" basis.
            assertThat(fund.annualDistributionPerShare()).isEqualByComparingTo("6.40");
            assertThat(fund.distributionFrequency()).isEqualTo(DistributionFrequency.MONTHLY);
        });
    }

    @Test
    void whenTheSheetAndTheLineDisagreeTheLowerWithdrawalPriceWins() {
        LouvePortfolioMapper.Mapped higherSheet = LouvePortfolioMapper.map(snapshot("900",
            List.of(line("aurore", "12", "900", FULL)), Map.of("aurore", sheet("aurore", "80", "80"))));
        LouvePortfolioMapper.Mapped lowerSheet = LouvePortfolioMapper.map(snapshot("1100",
            List.of(line("aurore", "12", "1100", FULL)), Map.of("aurore", sheet("aurore", "80", "80"))));

        assertThat(higherSheet.funds().get(0).withdrawalPrice()).isEqualByComparingTo("75");
        assertThat(lowerSheet.funds().get(0).withdrawalPrice()).isEqualByComparingTo("80");
    }

    @Test
    void aRoundingDifferenceKeepsThePublishedPrice() {
        LouvePortfolioMapper.Mapped mapped = LouvePortfolioMapper.map(snapshot("1000.01",
            List.of(line("aurore", "3", "1000.01", FULL)), Map.of("aurore", sheet("aurore", null, "333.34"))));

        assertThat(mapped.funds().get(0).withdrawalPrice()).isEqualByComparingTo("333.34");
        assertThat(mapped.funds().get(0).subscriptionPrice()).isNull();
        assertThat(mapped.funds().get(0).annualDistributionPerShare()).isNull();
    }

    @Test
    void withoutASheetTheLineItselfGivesThePrice() {
        LouvePortfolioMapper.Mapped mapped = LouvePortfolioMapper.map(snapshot("960",
            List.of(line("aurore", "12", "960", FULL)), Map.of()));

        assertThat(mapped.funds().get(0).withdrawalPrice()).isEqualByComparingTo("80");
        assertThat(mapped.funds().get(0).managementCompany()).isNull();
        assertThat(mapped.funds().get(0).subscriptionPrice()).isNull();
    }

    @Test
    void noWithdrawalPriceAnywhereLeavesItNullForPriceIncomplete() {
        LouvePortfolioMapper.Mapped mapped = LouvePortfolioMapper.map(snapshot("0",
            List.of(line("aurore", "12", "0", FULL)), Map.of("aurore", sheet("aurore", "80", null))));

        assertThat(mapped.funds().get(0).withdrawalPrice()).isNull();
        // The subscription price is kept beside the balance, never used as it.
        assertThat(mapped.funds().get(0).subscriptionPrice()).isEqualByComparingTo("80");
    }

    @Test
    void aDismemberedLineIsNotValued() {
        LouvePortfolioMapper.Mapped mapped = LouvePortfolioMapper.map(snapshot("1560", List.of(
            line("aurore", "12", "960", FULL),
            line("horizon", "10", "600", List.of("Nue-propriété")),
            line("other", "1", "0", List.of())
        ), Map.of()));

        assertThat(mapped.funds()).extracting(ScpiFundReconciler.SyncedFund::fundCode).containsExactly("aurore");
        assertThat(mapped.unsupported()).extracting(ScpiFundReconciler.UnsupportedFund::fundCode)
            .containsExactly("horizon", "other");
    }

    @Test
    void aFundHeldBothWaysSyncsOnlyItsFullOwnershipLine() {
        LouvePortfolioMapper.Mapped mapped = LouvePortfolioMapper.map(snapshot("1460", List.of(
            line("aurore", "12", "960", FULL),
            line("aurore", "10", "500", List.of("Usufruit"))
        ), Map.of()));

        assertThat(mapped.funds()).singleElement().satisfies(f -> assertThat(f.quantity()).isEqualByComparingTo("12"));
        assertThat(mapped.unsupported()).isEmpty();
    }

    @Test
    void linesThatDoNotAddUpToTheDeclaredTotalWriteNothing() {
        assertCode(() -> LouvePortfolioMapper.map(snapshot("1920",
            List.of(line("aurore", "12", "960", FULL)), Map.of())), SidecarErrorCode.PORTFOLIO_INCOMPLETE);
        assertCode(() -> LouvePortfolioMapper.map(snapshot(null,
            List.of(line("aurore", "12", "960", FULL)), Map.of())), SidecarErrorCode.PORTFOLIO_INCOMPLETE);
        assertCode(() -> LouvePortfolioMapper.map(snapshot("960",
            List.of(line("aurore", "12", null, FULL)), Map.of())), SidecarErrorCode.PORTFOLIO_INCOMPLETE);
        assertCode(() -> LouvePortfolioMapper.map(snapshot("500", List.of(), Map.of())),
            SidecarErrorCode.PORTFOLIO_INCOMPLETE);
    }

    @Test
    void anEmptyPortfolioIsAValidAnswer() {
        LouvePortfolioMapper.Mapped mapped = LouvePortfolioMapper.map(snapshot("0", List.of(), Map.of()));

        assertThat(mapped.funds()).isEmpty();
        assertThat(mapped.unsupported()).isEmpty();
    }

    @Test
    void duplicateFullLinesOrBadCodesAreRefused() {
        assertCode(() -> LouvePortfolioMapper.map(snapshot("1920", List.of(
            line("aurore", "12", "960", FULL), line("aurore", "12", "960", FULL)), Map.of())),
            SidecarErrorCode.INVALID_DATA);
        assertCode(() -> LouvePortfolioMapper.map(snapshot("960",
            List.of(line("Bad Code!", "12", "960", FULL)), Map.of())), SidecarErrorCode.INVALID_DATA);
        assertCode(() -> LouvePortfolioMapper.map(snapshot("960",
            List.of(line("aurore", "-1", "960", FULL)), Map.of())), SidecarErrorCode.INVALID_DATA);
    }

    @Test
    void anInternalCodeIsNotStoredAsAnIsin() {
        LouveAdapter.Fund internal = new LouveAdapter.Fund("aurore", "Aurore", "SCPI99990001", null,
            new BigDecimal("80"), new BigDecimal("80"), null, null);

        LouvePortfolioMapper.Mapped mapped = LouvePortfolioMapper.map(snapshot("960",
            List.of(line("aurore", "12", "960", FULL)), Map.of("aurore", internal)));

        assertThat(mapped.funds().get(0).isin()).isNull();
    }

    @Test
    void anUnknownCadenceOrAnImplausibleRateLeavesTheDistributionAlone() {
        LouveAdapter.Fund yearly = new LouveAdapter.Fund("aurore", "Aurore", null, null,
            new BigDecimal("80"), new BigDecimal("80"), new BigDecimal("0.08"), "yearly");
        LouveAdapter.Fund percent = new LouveAdapter.Fund("aurore", "Aurore", null, null,
            new BigDecimal("80"), new BigDecimal("80"), new BigDecimal("8"), "monthly");

        for (LouveAdapter.Fund fund : List.of(yearly, percent)) {
            ScpiFundReconciler.SyncedFund mapped = LouvePortfolioMapper.map(snapshot("960",
                List.of(line("aurore", "12", "960", FULL)), Map.of("aurore", fund))).funds().get(0);
            assertThat(mapped.annualDistributionPerShare()).isNull();
            assertThat(mapped.distributionFrequency()).isNull();
        }
        assertThat(LouvePortfolioMapper.frequency("Quarterly")).isEqualTo(DistributionFrequency.QUARTERLY);
    }

    @Test
    void onlyASingleFullOwnershipCountsAsFull() {
        assertThat(LouvePortfolioMapper.isFullOwnership(List.of("pleine propriete"))).isTrue();
        assertThat(LouvePortfolioMapper.isFullOwnership(List.of("Pleine propriété", "Usufruit"))).isFalse();
        assertThat(LouvePortfolioMapper.isFullOwnership(List.of())).isFalse();
        assertThat(LouvePortfolioMapper.isFullOwnership(null)).isFalse();
    }

    private static void assertCode(Runnable call, SidecarErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SyncException.class,
            ex -> assertThat(ex.getCode()).isEqualTo(code.name()));
    }
}
