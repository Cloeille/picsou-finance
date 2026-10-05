package com.picsou.adapter;

import com.picsou.port.InstrumentLogoPort.Image;
import com.picsou.port.InstrumentLogoPort.Lookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YahooQuotePageLogoProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final String PAGE = "https://finance.yahoo.com/quote/AAPL/";
    private static final String LIGHT =
        "https://s.yimg.com/lo/mysterio/api/5dee/finance/resizefill_w50_h50/https://s.yimg.com/lg/logos/US0378331005/light/2e23b039.png";
    private static final String DARK =
        "https://s.yimg.com/lo/mysterio/api/402d/finance/resizefill_w50_h50/https://s.yimg.com/lg/logos/US0378331005/dark/fabb0b30.png";

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] PNG_DARK = Arrays.copyOf(PNG, 20);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
    private static final byte[] SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>"
        .getBytes(StandardCharsets.UTF_8);

    /** url -> canned response; anything unrouted is a 404. */
    private final Map<String, Mono<ClientResponse>> routes = new HashMap<>();
    private final List<String> requested = new ArrayList<>();
    private final YahooCooldown cooldown = new YahooCooldown(Clock.fixed(NOW, ZoneOffset.UTC));
    private final CoinGeckoPriceProvider coinGecko = mock(CoinGeckoPriceProvider.class);
    private YahooQuotePageLogoProvider provider;

    @BeforeEach
    void setUp() {
        when(coinGecko.supports("BTC")).thenReturn(true);
        ExchangeFunction exchange = request -> {
            String url = request.url().toString();
            requested.add(url);
            return routes.getOrDefault(url, Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build()));
        };
        WebClient client = YahooQuotePageLogoProvider.clientBuilder().exchangeFunction(exchange).build();
        provider = new YahooQuotePageLogoProvider(client, cooldown, new YahooFinancePriceProvider(), coinGecko);
    }

    private void page(String body) {
        routes.put(PAGE, ok(MediaType.TEXT_HTML_VALUE, body.getBytes(StandardCharsets.UTF_8)));
    }

    private void image(String url, String contentType, byte[] bytes) {
        routes.put(url, ok(contentType, bytes));
    }

    /** Raw bytes, not {@code body(String)}: that re-encodes as UTF-8 and would corrupt a PNG. */
    private static Mono<ClientResponse> ok(String contentType, byte[] body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", contentType)
            .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(body)))
            .build());
    }

    private static Mono<ClientResponse> status(HttpStatus status, String... headers) {
        ClientResponse.Builder builder = ClientResponse.create(status);
        for (int i = 0; i + 1 < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        return Mono.just(builder.build());
    }

    @Test
    void storesBothVariants_fromThePageThenTheImageHost() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, MediaType.IMAGE_PNG_VALUE, PNG);
        image(DARK, MediaType.IMAGE_PNG_VALUE, PNG_DARK);

        Lookup result = provider.lookup("AAPL");

        assertThat(result).isInstanceOfSatisfying(Lookup.Found.class, found -> {
            assertThat(found.light().bytes()).isEqualTo(PNG);
            assertThat(found.light().contentType()).isEqualTo("image/png");
            assertThat(found.dark().bytes()).isEqualTo(PNG_DARK);
        });
        assertThat(requested).containsExactly(PAGE, LIGHT, DARK);
    }

    @Test
    void a429OnThePage_armsTheCooldownFromRetryAfter_andIsNotRecordedAsAMiss() {
        routes.put(PAGE, status(HttpStatus.TOO_MANY_REQUESTS, "Retry-After", "120"));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.RateLimited.class);
        assertThat(cooldown.active()).isTrue();
        assertThat(cooldown.until()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void whileCoolingDown_noRequestLeavesTheHost() {
        cooldown.arm(null);

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.RateLimited.class);
        assertThat(requested).isEmpty();
    }

    @Test
    void a5xx_isUnavailable_andPausesTheNextLookups() {
        routes.put(PAGE, status(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
        assertThat(provider.lookup("MSFT")).isInstanceOf(Lookup.RateLimited.class);
        assertThat(requested).containsExactly(PAGE);
    }

    @Test
    void aQuotePageThatDoesNotExist_isAPermanentMiss() {
        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Absent.class);
        assertThat(cooldown.active()).isFalse();
    }

    @Test
    void aConsentPageWithoutJson_isRetriedLater_notAPermanentMiss() {
        page("<html><body><form action=\"https://consent.yahoo.com/v2/collectConsent\">Accept all</form></body></html>");

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
        assertThat(requested).containsExactly(PAGE);
        assertThat(cooldown.active()).isFalse();
    }

    @Test
    void jsonThatOnlyQuotesOtherSymbols_isRetriedLater() {
        page("<html><script type=\"application/json\">{\"quoteResponse\":{\"result\":["
            + "{\"symbol\":\"MSFT\",\"logoUrl\":\"https://s.yimg.com/lg/m.png\"}]}}</script></html>");

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
        assertThat(requested).containsExactly(PAGE);
    }

    @Test
    void aQuoteForTheSymbolWithoutALogo_isAPermanentMiss_andDownloadsNothing() {
        quotes("{\"symbol\":\"MSFT\",\"logoUrl\":\"https://s.yimg.com/lg/m.png\"},{\"symbol\":\"AAPL\",\"quoteType\":\"EQUITY\"}");

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Absent.class);
        assertThat(requested).containsExactly(PAGE);
    }

    @Test
    void aQuoteWithoutALogo_onAPageWithNoUsableMarkAtAll_isRetriedLater() {
        quotes("{\"symbol\":\"AAPL\",\"quoteType\":\"EQUITY\"}");

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
        assertThat(requested).containsExactly(PAGE);
    }

    @Test
    void onlyARecommendationsObjectForTheSymbol_isRetriedLater() {
        quotes("{\"symbol\":\"MSFT\",\"logoUrl\":\"https://s.yimg.com/lg/m.png\"},"
            + "{\"symbol\":\"AAPL\",\"recommendedSymbols\":[{\"symbol\":\"MSFT\",\"score\":0.25}]}");

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
        assertThat(requested).containsExactly(PAGE);
    }

    @Test
    void aLogoUrlOffTheImageHost_isRetriedLater_andNotDownloaded() {
        quotes("{\"symbol\":\"MSFT\",\"logoUrl\":\"https://s.yimg.com/lg/m.png\"},"
            + "{\"symbol\":\"AAPL\",\"quoteType\":\"EQUITY\",\"logoUrl\":\"https://cdn.example/aapl.png\"}");

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
        assertThat(requested).containsExactly(PAGE);
    }

    private void quotes(String quoteJson) {
        page("<html><script type=\"application/json\">{\"quoteResponse\":{\"result\":[" + quoteJson + "]}}</script></html>");
    }

    @Test
    void aRedirectedPage_isRetriedLater_andNotFollowed() {
        // A body worth parsing, so only the status can make this Unavailable.
        routes.put(PAGE, Mono.just(ClientResponse.create(HttpStatus.MOVED_PERMANENTLY)
            .header("Location", "https://consent.yahoo.com/")
            .header("Content-Type", MediaType.TEXT_HTML_VALUE)
            .body(YahooQuotePageParserTest.fixture("quote-page-aapl.html"))
            .build()));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
        assertThat(requested).containsExactly(PAGE);
    }

    @Test
    void aRedirectedImage_isRetriedLater() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        routes.put(LIGHT, status(HttpStatus.FOUND, "Location", "https://s.yimg.com/elsewhere.png"));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
        assertThat(requested).containsExactly(PAGE, LIGHT);
    }

    @Test
    void aConnectionCutMidPage_isRetriedLater_notRethrown() {
        // The whole page arrives before the cut, so only the cut itself can make this Unavailable.
        byte[] html = YahooQuotePageParserTest.fixture("quote-page-aapl.html").getBytes(StandardCharsets.UTF_8);
        routes.put(PAGE, Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.TEXT_HTML_VALUE)
            .body(Flux.concat(
                Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(html)),
                Flux.error(new IOException("Connection prematurely closed DURING response"))))
            .build()));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
        assertThat(requested).containsExactly(PAGE);
    }

    @Test
    void aMalformedContentTypeOnThePage_isRetriedLater_notRethrown() {
        routes.put(PAGE, ok("html", YahooQuotePageParserTest.fixture("quote-page-aapl.html").getBytes(StandardCharsets.UTF_8)));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
        assertThat(requested).containsExactly(PAGE);
    }

    @Test
    void aMalformedContentTypeOnTheImage_isRetriedLater_notRethrown() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, "png", PNG);

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    void anSvgMark_isRefused() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, "image/svg+xml", SVG);

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Absent.class);
    }

    @Test
    void anOversizedMark_isRefused() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, MediaType.IMAGE_PNG_VALUE, Arrays.copyOf(PNG, YahooQuotePageLogoProvider.MAX_IMAGE_BYTES + 1));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Absent.class);
    }

    @Test
    void aPageOverTheMemoryCap_isRetriedLater_notAPermanentMiss() {
        // The page's size is Yahoo's layout, the same for every ticker: recording it as ABSENT
        // would settle the whole portfolio on one page redesign.
        routes.put(PAGE, ok(MediaType.TEXT_HTML_VALUE, new byte[YahooQuotePageLogoProvider.MAX_PAGE_BYTES + 1]));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
        assertThat(cooldown.active()).isFalse();
    }

    @Test
    void anImageOverTheMemoryCap_isAPermanentMiss() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, MediaType.IMAGE_PNG_VALUE, Arrays.copyOf(PNG, YahooQuotePageLogoProvider.MAX_PAGE_BYTES + 1));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Absent.class);
    }

    @Test
    void anHtmlPageOverTheMemoryCap_servedForTheImage_isRetriedLater() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, MediaType.TEXT_HTML_VALUE, new byte[YahooQuotePageLogoProvider.MAX_PAGE_BYTES + 1]);

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    void anHtmlPageOverTheImageCap_servedForTheImage_isRetriedLater() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, MediaType.TEXT_HTML_VALUE, "<html>".repeat(50_000).getBytes(StandardCharsets.UTF_8));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    void anHtmlPageServedAsTheImage_isRetriedLater() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, MediaType.TEXT_HTML_VALUE, "<html>Too many requests</html>".getBytes(StandardCharsets.UTF_8));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    void anImageWithoutAContentType_isStoredByItsSignature() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        routes.put(LIGHT, Mono.just(ClientResponse.create(HttpStatus.OK)
            .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(PNG)))
            .build()));

        assertThat(provider.lookup("AAPL")).isInstanceOfSatisfying(Lookup.Found.class,
            found -> assertThat(found.light().contentType()).isEqualTo("image/png"));
    }

    @Test
    void anImageServedAsOctetStream_isStoredByItsSignature() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, MediaType.APPLICATION_OCTET_STREAM_VALUE, PNG);

        assertThat(provider.lookup("AAPL")).isInstanceOfSatisfying(Lookup.Found.class,
            found -> assertThat(found.light().contentType()).isEqualTo("image/png"));
    }

    @Test
    void anHtmlPageServedAsTheDarkVariant_isRetriedLater_notAMarkWithoutItsDarkVariant() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, MediaType.IMAGE_PNG_VALUE, PNG);
        image(DARK, MediaType.TEXT_HTML_VALUE, "<html></html>".getBytes(StandardCharsets.UTF_8));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    void aDarkVariantYahooDoesNotHave_keepsTheLightMark() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, MediaType.IMAGE_PNG_VALUE, PNG);

        assertThat(provider.lookup("AAPL")).isInstanceOfSatisfying(Lookup.Found.class, found -> {
            assertThat(found.light().bytes()).isEqualTo(PNG);
            assertThat(found.dark()).isNull();
        });
        assertThat(requested).containsExactly(PAGE, LIGHT, DARK);
    }

    @Test
    void aRateLimitedDarkVariant_isRateLimited_notAMarkWithoutItsDarkVariant() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, MediaType.IMAGE_PNG_VALUE, PNG);
        routes.put(DARK, status(HttpStatus.TOO_MANY_REQUESTS, "Retry-After", "90"));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.RateLimited.class);
        assertThat(cooldown.until()).isEqualTo(NOW.plusSeconds(90));
    }

    @Test
    void anUnavailableDarkVariant_isUnavailable_soTheTickerIsRetriedWhole() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, MediaType.IMAGE_PNG_VALUE, PNG);
        routes.put(DARK, status(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    void tickersThatCannotBeListedShares_neverReachTheNetwork() {
        assertThat(provider.supports("BTC")).isFalse();
        assertThat(provider.supports("US0378331005")).isFalse();
        assertThat(provider.supports("0xc579d4eb8179af7f322f028d12bddb845ca10a3b")).isFalse();
        assertThat(provider.supports("AAPL")).isTrue();
        assertThat(provider.supports("MC.PA")).isTrue();

        assertThat(provider.lookup("BTC")).isInstanceOf(Lookup.Absent.class);
        assertThat(requested).isEmpty();
    }

    @Test
    void validation_trustsTheSignature_notTheHeader() {
        assertThat(validated(PNG, "image/png").contentType()).isEqualTo("image/png");
        assertThat(validated(JPEG, "image/jpeg").contentType()).isEqualTo("image/jpeg");
        assertThat(validated(JPEG, "image/jpg").contentType()).isEqualTo("image/jpeg");
        assertThat(validated(WEBP, "image/webp").contentType()).isEqualTo("image/webp");
        assertThat(validated(PNG, "IMAGE/PNG; charset=binary").contentType()).isEqualTo("image/png");
        assertThat(validated(Arrays.copyOf(PNG, YahooQuotePageLogoProvider.MAX_IMAGE_BYTES), "image/png")).isNotNull();

        // A label the CDN got wrong or left generic: the bytes decide, and the stored type is theirs.
        assertThat(validated(PNG, null).contentType()).isEqualTo("image/png");
        assertThat(validated(PNG, "application/octet-stream").contentType()).isEqualTo("image/png");
        assertThat(validated(PNG, "binary/octet-stream").contentType()).isEqualTo("image/png");
        assertThat(validated(JPEG, "text/plain").contentType()).isEqualTo("image/jpeg");
        assertThat(validated(PNG, "text/html").contentType()).isEqualTo("image/png");
        assertThat(validated(PNG, "image/jpeg").contentType()).isEqualTo("image/png");

        // Refusals about the mark itself: asking again gets the same file.
        assertThat(YahooQuotePageLogoProvider.validate(SVG, "image/svg+xml")).isInstanceOf(Lookup.Absent.class);
        assertThat(YahooQuotePageLogoProvider.validate(PNG, "Image/SVG+XML")).isInstanceOf(Lookup.Absent.class);
        assertThat(YahooQuotePageLogoProvider.validate(
            Arrays.copyOf(PNG, YahooQuotePageLogoProvider.MAX_IMAGE_BYTES + 1), "image/png")).isInstanceOf(Lookup.Absent.class);
        assertThat(YahooQuotePageLogoProvider.validate(
            Arrays.copyOf(PNG, 300 * 1024), "application/octet-stream")).isInstanceOf(Lookup.Absent.class);

        // Not an image at all, whatever its size: an error page or a CDN hiccup, which says nothing of the mark.
        assertThat(YahooQuotePageLogoProvider.validate("<html>".getBytes(StandardCharsets.UTF_8), "text/html"))
            .isInstanceOf(Lookup.Unavailable.class);
        assertThat(YahooQuotePageLogoProvider.validate(new byte[300 * 1024], "text/html"))
            .isInstanceOf(Lookup.Unavailable.class);
        assertThat(YahooQuotePageLogoProvider.validate(new byte[300 * 1024], "image/png"))
            .isInstanceOf(Lookup.Unavailable.class);
        assertThat(YahooQuotePageLogoProvider.validate(SVG, "image/png")).isInstanceOf(Lookup.Unavailable.class);
        assertThat(YahooQuotePageLogoProvider.validate(SVG, null)).isInstanceOf(Lookup.Unavailable.class);
        assertThat(YahooQuotePageLogoProvider.validate(new byte[0], "image/png")).isInstanceOf(Lookup.Unavailable.class);
    }

    private static Image validated(byte[] bytes, String contentType) {
        Lookup result = YahooQuotePageLogoProvider.validate(bytes, contentType);
        assertThat(result).isInstanceOf(Lookup.Found.class);
        return ((Lookup.Found) result).light();
    }

    @Test
    void thePricePath_armsTheCooldownOnA429_butNeverWaitsOnIt() {
        Map<String, Mono<ClientResponse>> priceRoutes = new HashMap<>();
        List<String> priceRequests = new ArrayList<>();
        WebClient priceClient = WebClient.builder().exchangeFunction(request -> {
            String url = request.url().toString();
            priceRequests.add(url);
            return priceRoutes.getOrDefault(url, status(HttpStatus.TOO_MANY_REQUESTS, "Retry-After", "30"));
        }).build();
        YahooFinancePriceProvider prices = new YahooFinancePriceProvider(priceClient, cooldown);

        // A 429 on a price arms the pause the logo path reads...
        assertThat(prices.getPricesEur(Set.of("AAPL"))).isEmpty();
        assertThat(cooldown.until()).isEqualTo(NOW.plusSeconds(30));
        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.RateLimited.class);

        // ...and a pause armed by anyone never stops the next price request.
        priceRoutes.put("/v8/finance/chart/MSFT?range=1d&interval=1d", Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body("{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":400.0,\"currency\":\"EUR\"}}]}}")
            .build()));
        cooldown.arm(null);
        assertThat(prices.getPricesEur(Set.of("MSFT"))).containsEntry("MSFT", new BigDecimal("400.0"));
        assertThat(cooldown.active()).isTrue();
        assertThat(Duration.between(NOW, cooldown.until())).isEqualTo(Duration.ofSeconds(60));
    }
}
