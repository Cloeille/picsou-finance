package com.picsou.adapter;

import com.picsou.adapter.YahooFinancePriceProvider.SearchQuote;
import com.picsou.adapter.YahooFinancePriceProvider.SearchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The sector half of the equity profile, which Boursorama cannot answer outside Euronext. */
class YahooEquityProfileTest {

    private static SearchQuote quote(String symbol, String sector) {
        return new SearchQuote(symbol, symbol, symbol, true, sector, null);
    }

    @Test
    void normalisesYahooSectorsOntoTheKeysEtfSlicesAlreadyUse() {
        // Both sides speak the Morningstar taxonomy, so the mapping is a lowercase and a
        // space-to-underscore — and every resulting key already has a translation.
        assertThat(YahooFinancePriceProvider.sectorKey("Technology")).isEqualTo("technology");
        assertThat(YahooFinancePriceProvider.sectorKey("Basic Materials")).isEqualTo("basic_materials");
        assertThat(YahooFinancePriceProvider.sectorKey("Financial Services")).isEqualTo("financial_services");
        assertThat(YahooFinancePriceProvider.sectorKey(" Healthcare ")).isEqualTo("healthcare");
    }

    @Test
    void picksTheQuoteWhoseSymbolWasAskedFor() {
        // Never quotes[0]: the search is a relevance ranking, so a thin European listing can be
        // outranked by a better-known foreign namesake and the position filed under its sector.
        SearchResponse response = new SearchResponse(List.of(
            quote("AAPL", "Technology"),
            quote("AI.PA", "Basic Materials")));

        assertThat(YahooFinancePriceProvider.sectorFrom(response, "AI.PA")).contains("basic_materials");
    }

    @Test
    void matchesTheSymbolCaseInsensitively() {
        SearchResponse response = new SearchResponse(List.of(quote("AI.PA", "Basic Materials")));
        assertThat(YahooFinancePriceProvider.sectorFrom(response, "ai.pa")).contains("basic_materials");
    }

    @Test
    void anEtfHasNoSectorOfItsOwn() {
        // Correct, not a failure: a fund has a distribution, and the composition pipeline
        // already resolves it.
        SearchResponse response = new SearchResponse(List.of(quote("CW8.PA", null)));
        assertThat(YahooFinancePriceProvider.sectorFrom(response, "CW8.PA")).isEmpty();
    }

    @Test
    void aSymbolTheSearchDidNotReturnYieldsNothing() {
        SearchResponse response = new SearchResponse(List.of(quote("AAPL", "Technology")));
        assertThat(YahooFinancePriceProvider.sectorFrom(response, "MSFT")).isEmpty();
    }

    @Test
    void survivesAnEmptyOrNullResponse() {
        assertThat(YahooFinancePriceProvider.sectorFrom(null, "AAPL")).isEmpty();
        assertThat(YahooFinancePriceProvider.sectorFrom(new SearchResponse(null), "AAPL")).isEmpty();
        assertThat(YahooFinancePriceProvider.sectorFrom(new SearchResponse(List.of()), "AAPL")).isEmpty();
    }
}
