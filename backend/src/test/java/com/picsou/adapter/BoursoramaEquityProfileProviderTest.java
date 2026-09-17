package com.picsou.adapter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures are real Boursorama quote pages, trimmed to the analytics datalayer the parser reads.
 * The full pages are ~1.2 MB each and the parser looks at exactly two fields, so committing them
 * whole would be 2.4 MB of noise around 100 bytes of contract.
 */
class BoursoramaEquityProfileProviderTest {

    private static String fixture(String name) {
        try (InputStream in = BoursoramaEquityProfileProviderTest.class
                .getResourceAsStream("/boursorama/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void readsTheCountryFromTheIsinOfAFrenchListing() {
        Optional<String> country = BoursoramaEquityProfileProvider
            .countryOf(fixture("quote-air-liquide.html"), "1rPAI");

        // FR0000120073 → FR. The ISIN also recovers an identifier ingestion throws away: holdings
        // store a Yahoo ticker, never the ISIN they were converted from.
        assertThat(country).contains("FR");
    }

    @Test
    void readsTheCountryOfANonEuronextListingToo() {
        Optional<String> country = BoursoramaEquityProfileProvider
            .countryOf(fixture("quote-apple.html"), "AAPL");

        // This page's fv_secteur_activite reads "n-d" — which is exactly why the sector comes
        // from Yahoo and only the country is taken from here.
        assertThat(country).contains("US");
        assertThat(fixture("quote-apple.html")).contains("\"fv_secteur_activite\":\"n-d\"");
    }

    @Test
    void refusesAPageThatIsNotAboutTheRequestedSymbol() {
        // A wrong symbol does not 404 here — Boursorama answers 200 with something else
        // entirely. Parsing whatever ISIN happened to be on it would file a position under an
        // unrelated company's country, so "the page loaded" is not "the page is about this".
        Optional<String> country = BoursoramaEquityProfileProvider
            .countryOf(fixture("quote-wrong-symbol.html"), "1rTTE");

        assertThat(country).isEmpty();
    }

    @Test
    void refusesAPageWhoseSymbolBelongsToAnotherSecurity() {
        assertThat(BoursoramaEquityProfileProvider
            .countryOf(fixture("quote-apple.html"), "1rPAI")).isEmpty();
    }

    @Test
    void survivesEmptyAndMalformedInput() {
        assertThat(BoursoramaEquityProfileProvider.countryOf("", "AAPL")).isEmpty();
        assertThat(BoursoramaEquityProfileProvider.countryOf(null, "AAPL")).isEmpty();
        assertThat(BoursoramaEquityProfileProvider.countryOf("<html></html>", null)).isEmpty();
    }

    @Test
    void ignoresAMalformedIsin() {
        String html = "<html>\"fv_symb_societe\":\"XX\" \"fv_code_isin\":\"NOTANISIN\"</html>";
        assertThat(BoursoramaEquityProfileProvider.countryOf(html, "XX")).isEmpty();
    }
}
