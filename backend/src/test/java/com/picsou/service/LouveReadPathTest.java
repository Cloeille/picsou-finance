package com.picsou.service;

import com.picsou.adapter.LouveAdapter;
import com.picsou.adapter.LouveFakeServer;
import com.picsou.exception.SyncException;
import com.picsou.port.SidecarErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole read path with synthetic tool texts: real MCP client, real adapter, real mapper. An
 * answer of the wrong shape must stop the run before anything is written.
 */
class LouveReadPathTest {

    private static final String LINE = "{\"scpiUrlId\":\"aurore\",\"scpiName\":\"Synthetic Aurore\",\"nbShares\":12,"
        + "\"sellingPrice\":960,\"ownership\":%s}";

    private static String portfolio(String ownership) {
        return "{\"scpi\":{\"sellingPrice\":960,\"positions\":[" + LINE.formatted(ownership) + "]}}";
    }

    @Test
    void aFullOwnershipLineIsValued() {
        LouvePortfolioMapper.Mapped mapped = LouvePortfolioMapper.map(LouveFakeServer.snapshot(portfolio("[\"Pleine propriété\"]")));

        assertThat(mapped.funds()).singleElement().satisfies(f -> assertThat(f.withdrawalPrice()).isEqualByComparingTo("80"));
    }

    @Test
    void anOwnershipElementThatIsNotTextIsAFormatChangeNotFullOwnership() {
        assertFormatChanged(() -> LouveFakeServer.snapshot(portfolio("[\"Pleine propriété\",{\"type\":\"Usufruit\"}]")));
        assertFormatChanged(() -> LouveFakeServer.snapshot(portfolio("\"Pleine propriété\"")));
    }

    @Test
    void aNullOrMissingOwnershipIsAFormatChangeNotADismemberedLine() {
        assertFormatChanged(() -> LouveFakeServer.snapshot(portfolio("null")));
        assertFormatChanged(() -> LouveFakeServer.snapshot("{\"scpi\":{\"sellingPrice\":960,\"positions\":["
            + "{\"scpiUrlId\":\"aurore\",\"nbShares\":12,\"sellingPrice\":960}]}}"));
    }

    @Test
    void aPortfolioThatIsNotAnObjectIsAFormatChangeNotAnEmptyPortfolio() {
        assertFormatChanged(() -> LouveFakeServer.snapshot("[]"));
        assertFormatChanged(() -> LouveFakeServer.snapshot("42"));
        assertFormatChanged(() -> LouveFakeServer.snapshot("{\"date\":\"2026-11-17\"}"));
        assertFormatChanged(() -> LouveFakeServer.snapshot("{\"scpi\":{\"sellingPrice\":0}}"));
        assertFormatChanged(() -> LouveFakeServer.snapshot("{\"scpi\":[]}"));
    }

    @Test
    void anExplicitlyEmptyScpiSideIsStillAValidEmptyPortfolio() {
        LouveAdapter.Snapshot none = LouveFakeServer.snapshot("{\"scpi\":null}");
        LouveAdapter.Snapshot empty = LouveFakeServer.snapshot("{\"scpi\":{\"sellingPrice\":0,\"positions\":[]}}");

        assertThat(LouvePortfolioMapper.map(none).funds()).isEmpty();
        assertThat(LouvePortfolioMapper.map(empty).funds()).isEmpty();
    }

    @Test
    void aFundSheetThatIsNotAnObjectOrHasNoIdentifierIsAFormatChange() {
        String portfolio = portfolio("[\"Pleine propriété\"]");
        assertFormatChanged(() -> LouveFakeServer.snapshot(params ->
            params.path("name").asText().equals("get_portfolio") ? portfolio : "[]"));
        assertFormatChanged(() -> LouveFakeServer.snapshot(params ->
            params.path("name").asText().equals("get_portfolio") ? portfolio : "{\"sellingPriceByShare\":80}"));
    }

    private static void assertFormatChanged(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SyncException.class,
            ex -> assertThat(ex.getCode()).isEqualTo(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED.name()));
    }
}
