package com.picsou.adapter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class YahooQuotePageParserTest {

    static String fixture(String name) {
        try (InputStream in = YahooQuotePageParserTest.class.getResourceAsStream("/yahoo/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void readsTheMarkOfTheSymbolThePageIsAbout_notTheFirstLogoOnThePage() {
        // The page's first logo is VST's, in the trending block, and MSFT's sits in the same quote
        // response just before AAPL's. A scan for the first s.yimg.com/lg/logos URL would store
        // another company's mark under AAPL.
        var urls = YahooQuotePageParser.logoUrls(fixture("quote-page-aapl.html"), "AAPL").orElseThrow();

        assertThat(urls.light()).isEqualTo(URI.create(
            "https://s.yimg.com/lo/mysterio/api/5dee/finance/resizefill_w50_h50/https://s.yimg.com/lg/logos/US0378331005/light/2e23b039.png"));
        assertThat(urls.dark()).isEqualTo(URI.create(
            "https://s.yimg.com/lo/mysterio/api/402d/finance/resizefill_w50_h50/https://s.yimg.com/lg/logos/US0378331005/dark/fabb0b30.png"));
    }

    @Test
    void readsAnEtfWithAnExchangeSuffix() {
        var urls = YahooQuotePageParser.logoUrls(fixture("quote-page-iwda.html"), "IWDA.AS").orElseThrow();

        assertThat(urls.light().toString()).endsWith("/IE00B4L5Y983/light/22532c01.png");
        assertThat(urls.dark().toString()).endsWith("/IE00B4L5Y983/dark/22532c01.png");
    }

    @Test
    void matchesTheSymbolCaseInsensitively() {
        assertThat(YahooQuotePageParser.logoUrls(fixture("quote-page-iwda.html"), "iwda.as")).isPresent();
    }

    @Test
    void findsNothingForASymbolThePageDoesNotQuote() {
        // GOOGL is nowhere in the page; the other companies' logos must not stand in for it.
        assertThat(YahooQuotePageParser.logoUrls(fixture("quote-page-aapl.html"), "GOOGL")).isEmpty();
    }

    @Test
    void findsNothingWhenTheLayoutCarriesNoJsonBlocks() {
        String html = "<html><body><img src=\"https://s.yimg.com/lg/logos/US0378331005/light/2e23b039.png\">"
            + "<div data-symbol=\"AAPL\"></div></body></html>";

        assertThat(YahooQuotePageParser.logoUrls(html, "AAPL")).isEmpty();
    }

    @Test
    void refusesALogoUrlOffTheImageHost() {
        String html = page("{\"symbol\":\"AAPL\",\"logoUrl\":\"https://evil.example/logo.png\"}");

        assertThat(YahooQuotePageParser.logoUrls(html, "AAPL")).isEmpty();
    }

    @Test
    void refusesPlainHttpAndLookalikeHosts() {
        assertThat(YahooQuotePageParser.imageUri("http://s.yimg.com/lg/logos/X/light/a.png")).isNull();
        assertThat(YahooQuotePageParser.imageUri("https://s.yimg.com.evil.example/a.png")).isNull();
        assertThat(YahooQuotePageParser.imageUri("https://user@s.yimg.com/a.png")).isNull();
        assertThat(YahooQuotePageParser.imageUri("https://s.yimg.com:8443/a.png")).isNull();
        assertThat(YahooQuotePageParser.imageUri("javascript:alert(1)")).isNull();
        assertThat(YahooQuotePageParser.imageUri("https://s.yimg.com/lg/logos/X/light/a.png")).isNotNull();
    }

    @Test
    void keepsTheLightMarkWhenTheDarkOneIsUnusable() {
        String html = page("{\"symbol\":\"AAPL\",\"logoUrl\":\"https://s.yimg.com/lg/a.png\","
            + "\"logoUrlDarkMode\":\"https://evil.example/b.png\"}");

        var urls = YahooQuotePageParser.logoUrls(html, "AAPL").orElseThrow();
        assertThat(urls.light()).isEqualTo(URI.create("https://s.yimg.com/lg/a.png"));
        assertThat(urls.dark()).isNull();
    }

    @Test
    void toleratesNullAndBlankInput() {
        assertThat(YahooQuotePageParser.logoUrls(null, "AAPL")).isEmpty();
        assertThat(YahooQuotePageParser.logoUrls("<html></html>", " ")).isEmpty();
    }

    private static String page(String quoteJson) {
        return "<html><script type=\"application/json\">{\"quoteResponse\":{\"result\":[" + quoteJson + "]}}</script></html>";
    }
}
