package com.picsou.adapter;

import com.picsou.adapter.YahooQuotePageParser.Result;
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
        var urls = marked(fixture("quote-page-aapl.html"), "AAPL");

        assertThat(urls.light()).isEqualTo(URI.create(
            "https://s.yimg.com/lo/mysterio/api/5dee/finance/resizefill_w50_h50/https://s.yimg.com/lg/logos/US0378331005/light/2e23b039.png"));
        assertThat(urls.dark()).isEqualTo(URI.create(
            "https://s.yimg.com/lo/mysterio/api/402d/finance/resizefill_w50_h50/https://s.yimg.com/lg/logos/US0378331005/dark/fabb0b30.png"));
    }

    @Test
    void readsAnEtfWithAnExchangeSuffix() {
        var urls = marked(fixture("quote-page-iwda.html"), "IWDA.AS");

        assertThat(urls.light().toString()).endsWith("/IE00B4L5Y983/light/22532c01.png");
        assertThat(urls.dark().toString()).endsWith("/IE00B4L5Y983/dark/22532c01.png");
    }

    @Test
    void matchesTheSymbolCaseInsensitively() {
        assertThat(YahooQuotePageParser.read(fixture("quote-page-iwda.html"), "iwda.as")).isInstanceOf(Result.Marked.class);
    }

    @Test
    void aSymbolThePageDoesNotQuote_isNotQuoted_ratherThanUnmarked() {
        // GOOGL is nowhere in the page; the other companies' logos must not stand in for it.
        assertThat(YahooQuotePageParser.read(fixture("quote-page-aapl.html"), "GOOGL")).isInstanceOf(Result.NotQuoted.class);
    }

    @Test
    void aLayoutWithoutJsonBlocks_quotesNothing_ratherThanReportingNoMark() {
        String html = "<html><body><img src=\"https://s.yimg.com/lg/logos/US0378331005/light/2e23b039.png\">"
            + "<div data-symbol=\"AAPL\"></div></body></html>";

        assertThat(YahooQuotePageParser.read(html, "AAPL")).isInstanceOf(Result.NotQuoted.class);
    }

    @Test
    void refusesALogoUrlOffTheImageHost_withoutCallingTheSymbolUnmarked() {
        // A CDN move would put every mark off the host at once; that is not "Yahoo has none".
        String html = page("{\"symbol\":\"MSFT\",\"logoUrl\":\"https://s.yimg.com/lg/m.png\"},"
            + "{\"symbol\":\"AAPL\",\"quoteType\":\"EQUITY\",\"logoUrl\":\"https://evil.example/logo.png\"}");

        assertThat(YahooQuotePageParser.read(html, "AAPL")).isInstanceOf(Result.RefusedMark.class);
    }

    @Test
    void anObjectKeyedByTheSymbolThatIsNotAQuote_isNotQuoted() {
        // The real page has one: recommendationsbysymbol, which never carries a logo.
        String html = page("{\"symbol\":\"MSFT\",\"logoUrl\":\"https://s.yimg.com/lg/m.png\"},"
            + "{\"symbol\":\"AAPL\",\"recommendedSymbols\":[{\"symbol\":\"MSFT\",\"score\":0.25}]}");

        assertThat(YahooQuotePageParser.read(html, "AAPL")).isInstanceOf(Result.NotQuoted.class);
    }

    @Test
    void aQuoteWithoutALogo_onAPageWithNoUsableMarkAtAll_isNotQuoted() {
        String html = page("{\"symbol\":\"MSFT\",\"quoteType\":\"EQUITY\"},{\"symbol\":\"AAPL\",\"quoteType\":\"EQUITY\"}");

        assertThat(YahooQuotePageParser.read(html, "AAPL")).isInstanceOf(Result.NotQuoted.class);
    }

    @Test
    void theRealPageWithoutTheSymbolsOwnMark_isUnmarked() {
        String html = fixture("quote-page-aapl.html").replace(
            "https://s.yimg.com/lo/mysterio/api/5dee/finance/resizefill_w50_h50/https://s.yimg.com/lg/logos/US0378331005/light/2e23b039.png", "");

        assertThat(YahooQuotePageParser.read(html, "AAPL")).isInstanceOf(Result.Unmarked.class);
    }

    @Test
    void theRealPageWithItsLogoFieldRenamed_isNotQuoted_ratherThanUnmarked() {
        String html = fixture("quote-page-aapl.html").replace("logoUrl", "brandLogo");

        assertThat(YahooQuotePageParser.read(html, "AAPL")).isInstanceOf(Result.NotQuoted.class);
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

        var urls = marked(html, "AAPL");
        assertThat(urls.light()).isEqualTo(URI.create("https://s.yimg.com/lg/a.png"));
        assertThat(urls.dark()).isNull();
    }

    @Test
    void toleratesNullAndBlankInput() {
        assertThat(YahooQuotePageParser.read(null, "AAPL")).isInstanceOf(Result.NotQuoted.class);
        assertThat(YahooQuotePageParser.read("<html></html>", " ")).isInstanceOf(Result.NotQuoted.class);
    }

    @Test
    void aQuoteObjectForTheSymbolWithoutALogo_isUnmarked() {
        String html = page("{\"symbol\":\"MSFT\",\"logoUrl\":\"https://s.yimg.com/lg/m.png\"},"
            + "{\"symbol\":\"AAPL\",\"quoteType\":\"EQUITY\"}");

        assertThat(YahooQuotePageParser.read(html, "AAPL")).isInstanceOf(Result.Unmarked.class);
    }

    @Test
    void jsonThatOnlyQuotesOtherSymbols_isNotQuoted() {
        String html = page("{\"symbol\":\"MSFT\",\"logoUrl\":\"https://s.yimg.com/lg/m.png\"}");

        assertThat(YahooQuotePageParser.read(html, "AAPL")).isInstanceOf(Result.NotQuoted.class);
    }

    @Test
    void aMarkInALaterBlock_winsOverAnUnmarkedQuoteInAnEarlierOne() {
        String html = page("{\"symbol\":\"AAPL\",\"quoteType\":\"EQUITY\"}")
            + page("{\"symbol\":\"AAPL\",\"logoUrl\":\"https://s.yimg.com/lg/a.png\"}");

        assertThat(marked(html, "AAPL").light()).isEqualTo(URI.create("https://s.yimg.com/lg/a.png"));
    }

    private static YahooQuotePageParser.LogoUrls marked(String html, String symbol) {
        Result result = YahooQuotePageParser.read(html, symbol);
        assertThat(result).isInstanceOf(Result.Marked.class);
        return ((Result.Marked) result).urls();
    }

    private static String page(String quoteJson) {
        return "<html><script type=\"application/json\">{\"quoteResponse\":{\"result\":[" + quoteJson + "]}}</script></html>";
    }
}
