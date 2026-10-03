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
    void aPageWithoutAMarkForTheSymbol_isAPermanentMiss_andDownloadsNothing() {
        page("<html><body>No quote here</body></html>");

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Absent.class);
        assertThat(requested).containsExactly(PAGE);
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
    void aPageOverTheMemoryCap_isAPermanentMiss_notAnOutage() {
        // The codec wraps the overflow in a WebClientResponseException carrying the 200 it was
        // served with. Read as an HTTP failure it would be retried weekly forever.
        routes.put(PAGE, ok(MediaType.TEXT_HTML_VALUE, new byte[YahooQuotePageLogoProvider.MAX_PAGE_BYTES + 1]));

        assertThat(provider.lookup("AAPL")).isInstanceOf(Lookup.Absent.class);
        assertThat(cooldown.active()).isFalse();
    }

    @Test
    void aFailedDarkVariant_keepsTheLightMark() {
        page(YahooQuotePageParserTest.fixture("quote-page-aapl.html"));
        image(LIGHT, MediaType.IMAGE_PNG_VALUE, PNG);
        routes.put(DARK, status(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(provider.lookup("AAPL")).isInstanceOfSatisfying(Lookup.Found.class, found -> {
            assertThat(found.light().bytes()).isEqualTo(PNG);
            assertThat(found.dark()).isNull();
        });
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
        assertThat(YahooQuotePageLogoProvider.validate(PNG, "image/png")).isNotNull();
        assertThat(YahooQuotePageLogoProvider.validate(JPEG, "image/jpeg")).isNotNull();
        assertThat(YahooQuotePageLogoProvider.validate(JPEG, "image/jpg"))
            .extracting(Image::contentType).isEqualTo("image/jpeg");
        assertThat(YahooQuotePageLogoProvider.validate(WEBP, "image/webp")).isNotNull();
        assertThat(YahooQuotePageLogoProvider.validate(PNG, "IMAGE/PNG; charset=binary"))
            .extracting(Image::contentType).isEqualTo("image/png");

        // An HTML error page served with a 200, a type that lies about its bytes, an SVG, no type.
        assertThat(YahooQuotePageLogoProvider.validate("<html>".getBytes(StandardCharsets.UTF_8), "text/html")).isNull();
        assertThat(YahooQuotePageLogoProvider.validate(PNG, "text/html")).isNull();
        assertThat(YahooQuotePageLogoProvider.validate(PNG, "image/jpeg")).isNull();
        assertThat(YahooQuotePageLogoProvider.validate(SVG, "image/svg+xml")).isNull();
        assertThat(YahooQuotePageLogoProvider.validate(SVG, "image/png")).isNull();
        assertThat(YahooQuotePageLogoProvider.validate(PNG, null)).isNull();
        assertThat(YahooQuotePageLogoProvider.validate(new byte[0], "image/png")).isNull();
        assertThat(YahooQuotePageLogoProvider.validate(
            Arrays.copyOf(PNG, YahooQuotePageLogoProvider.MAX_IMAGE_BYTES + 1), "image/png")).isNull();
        assertThat(YahooQuotePageLogoProvider.validate(
            Arrays.copyOf(PNG, YahooQuotePageLogoProvider.MAX_IMAGE_BYTES), "image/png")).isNotNull();
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
