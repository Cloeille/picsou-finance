package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.CryptoExchangePort.ExchangeCredentials;
import com.picsou.port.CryptoExchangePort.ExchangePosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.picsou.port.CryptoExchangePort.Product.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@ExtendWith(OutputCaptureExtension.class)
class OkxAdapterTest {

    private static final ExchangeCredentials CREDENTIALS =
        new ExchangeCredentials("synthetic-key", "synthetic-secret", "synthetic-passphrase", "EEA");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2020-12-08T09:08:57.123Z"), ZoneOffset.ofHours(9));
    private static final String EMPTY = "{\"code\":\"0\",\"msg\":\"\",\"data\":[]}";
    private static final String TRADING = "/api/v5/account/balance";
    private static final String FUNDING = "/api/v5/asset/balances";
    private static final String SAVINGS = "/api/v5/finance/savings/balance";
    private static final String ORDERS = "/api/v5/finance/staking-defi/orders-active";
    private static final String ETH_STAKING = "/api/v5/finance/staking-defi/eth/balance";
    private static final String SOL_STAKING = "/api/v5/finance/staking-defi/sol/balance";
    private static final List<String> ENDPOINTS = List.of(TRADING, FUNDING, SAVINGS, ORDERS, ETH_STAKING, SOL_STAKING);

    private final List<ClientRequest> requests = new ArrayList<>();

    @Test
    void fetchesAndAggregatesAllSixCollectionsForTheVerifiedEeaShape() throws IOException {
        String[] names = {"trading", "funding", "savings", "orders", "eth-staking", "sol-staking"};
        List<String> bodies = new ArrayList<>();
        for (String name : names) {
            bodies.add(new ClassPathResource("okx/" + name + ".json").getContentAsString(StandardCharsets.UTF_8));
        }
        OkxAdapter adapter = adapter(path -> response(HttpStatus.OK, bodies.get(ENDPOINTS.indexOf(path))));

        List<ExchangePosition> positions = adapter.fetchPositions(CREDENTIALS);

        assertThat(positions).extracting(ExchangePosition::product, ExchangePosition::symbol, ExchangePosition::quantity)
            .containsExactlyInAnyOrder(tuple(SPOT, "COIN", new BigDecimal("15")),
                tuple(STAKING, "COIN", new BigDecimal("3")), tuple(LENDING, "COIN", new BigDecimal("9")),
                tuple(STAKING, "ETH", new BigDecimal("6")), tuple(STAKING, "SOL", new BigDecimal("7")));
        assertThat(positions).allSatisfy(position -> {
            assertThat(position.principal()).isNull();
            assertThat(position.interest()).isNull();
        });
        assertThat(requests).extracting(request -> request.url().getPath()).containsExactlyElementsOf(ENDPOINTS);
        assertThat(requests).allSatisfy(request -> assertThat(request.url().getHost()).isEqualTo("eea.example.test"));
    }

    @Test
    void signsTheConnectionProbeAndSendsOnlyOkxCredentials() {
        OkxAdapter adapter = adapter(path -> response(HttpStatus.OK, emptyFor(path)));
        assertThat(adapter.exchangeName()).isEqualTo("OKX");
        assertThat(adapter.requiresApiSecret()).isTrue();
        assertThat(adapter.requiresApiPassphrase()).isTrue();
        assertThat(adapter.testConnection(CREDENTIALS)).isTrue();

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo(HttpMethod.GET);
            assertThat(request.url().getRawPath()).isEqualTo(TRADING);
            assertThat(request.url().getRawQuery()).isNull();
            assertThat(request.headers().toSingleValueMap())
                .containsEntry("OK-ACCESS-KEY", CREDENTIALS.apiKey())
                .containsEntry("OK-ACCESS-PASSPHRASE", CREDENTIALS.apiPassphrase())
                .containsEntry("OK-ACCESS-TIMESTAMP", "2020-12-08T09:08:57.123Z")
                .containsEntry("OK-ACCESS-SIGN", "+O+VqOpuRsHqS13jsN4I0KR2xjidreyFK9lb/mTz1GU=")
                .containsEntry("User-Agent", "Picsou")
                .doesNotContainKeys("X-MBX-APIKEY", "API-KEY", "Authorization");
        });
    }

    @Test
    void signsTradingAndFundingWithMillisecondPrecisionAtAWholeSecond() {
        Clock wholeSecond = Clock.fixed(Instant.parse("2020-12-08T09:08:57Z"), CLOCK.getZone());
        var adapter = adapter(path -> response(HttpStatus.OK, emptyFor(path)), wholeSecond);

        adapter.fetchPositions(CREDENTIALS);

        assertThat(requests).filteredOn(request -> request.url().getRawPath().equals(TRADING)
            || request.url().getRawPath().equals(FUNDING)).satisfiesExactly(
            trading -> assertThat(trading.headers().toSingleValueMap())
                .containsEntry("OK-ACCESS-TIMESTAMP", "2020-12-08T09:08:57.000Z")
                .containsEntry("OK-ACCESS-SIGN", "W6szVeElG/gmzYB7Hu6LFaYZrtkDBBgE6G/03yzd3Pw="),
            funding -> assertThat(funding.headers().toSingleValueMap())
                .containsEntry("OK-ACCESS-TIMESTAMP", "2020-12-08T09:08:57.000Z")
                .containsEntry("OK-ACCESS-SIGN", "rjKcENZ3sH9mkggaRjujOXtZJtpS6dh098WsJ4XkaYg="));
    }

    @Test
    void routesEachConnectionUsingItsOwnRegion() {
        OkxAdapter adapter = adapter(path -> response(HttpStatus.OK, emptyFor(path)));
        adapter.testConnection(CREDENTIALS);
        adapter.testConnection(new ExchangeCredentials("us-key", "us-secret", "us-passphrase", "US"));
        assertThat(requests).extracting(request -> request.url().getHost())
            .containsExactly("eea.example.test", "us.example.test");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "UNKNOWN")
    void rejectsAnInvalidRegionBeforeSendingCredentials(String region) {
        OkxAdapter adapter = adapter(path -> response(HttpStatus.OK, emptyFor(path)));
        var credentials = new ExchangeCredentials("key", "secret", "passphrase", region);
        assertThatThrownBy(() -> adapter.testConnection(credentials)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.fetchPositions(credentials)).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void emptyCollectionsAreValid() {
        var adapter = withBody(TRADING, envelope("""
            {"totalEq":"0","details":[]}
            """));
        assertThat(adapter.fetchPositions(CREDENTIALS)).isEmpty();
    }

    @Test
    void acceptsEmptyTradingDetailsWithZeroTotalEquity() {
        var adapter = withBody(TRADING, envelope("""
            {"totalEq":"0.00","details":[]}
            """));
        assertThat(adapter.testConnection(CREDENTIALS)).isTrue();
        assertThat(adapter.fetchPositions(CREDENTIALS)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidTradingData")
    void probeAndSyncBothRejectInvalidTradingData(String data, String field) {
        var adapter = withBody(TRADING, envelope(data));
        assertThatThrownBy(() -> adapter.testConnection(CREDENTIALS))
            .isInstanceOf(SyncException.class).hasMessageContainingAll(field, TRADING);
        assertThatThrownBy(() -> adapter.fetchPositions(CREDENTIALS))
            .isInstanceOf(SyncException.class).hasMessageContainingAll(field, TRADING);
        assertThat(requests).extracting(request -> request.url().getPath()).containsExactly(TRADING, TRADING);
    }

    private static Stream<Arguments> invalidTradingData() {
        return Stream.of(
            Arguments.of("", "data"),
            Arguments.of("{\"details\":[]}", "totalEq"),
            Arguments.of("{}", "details"),
            Arguments.of("{\"totalEq\":\"-1\",\"details\":[]}", "details"),
            Arguments.of("{\"totalEq\":\"1\",\"details\":[]}", "details"),
            Arguments.of("{\"totalEq\":\"1\"}", "details"),
            Arguments.of("{\"totalEq\":\"bad\",\"details\":[]}", "totalEq"),
            Arguments.of("{\"details\":[{\"ccy\":\"COIN\"}]}", "eq"),
            Arguments.of("{\"details\":[]},{\"details\":[]}", "data"));
    }

    @Test
    void uppercasesSymbolsWithTheRootLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(withBody(FUNDING, "{\"code\":\"0\",\"data\":[{\"ccy\":\" coin \",\"bal\":\"4\"}]}")
                .fetchPositions(CREDENTIALS)).extracting(ExchangePosition::symbol).containsExactly("COIN");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @ParameterizedTest(name = "{1} on {0} fails the sync")
    @MethodSource("failures")
    void failsOnAnyEndpoint(String endpoint, Failure failure) {
        OkxAdapter adapter = adapter(path -> path.equals(endpoint) ? failure.reply() : response(HttpStatus.OK, emptyFor(path)));
        assertThatThrownBy(() -> adapter.fetchPositions(CREDENTIALS))
            .isInstanceOf(SyncException.class).hasMessageContaining(endpoint);
        assertThat(requests).extracting(request -> request.url().getPath())
            .containsExactlyElementsOf(ENDPOINTS.subList(0, ENDPOINTS.indexOf(endpoint) + 1));
    }

    private static Stream<Arguments> failures() {
        return ENDPOINTS.stream().flatMap(endpoint -> Arrays.stream(Failure.values())
            .map(failure -> Arguments.of(endpoint, failure)));
    }

    private enum Failure {
        PARTIAL_CONTENT, UNAUTHORIZED, RATE_LIMIT, SERVER_ERROR, API_ERROR, TIMEOUT, TRANSPORT, NON_JSON,
        MISSING_DATA, OBJECT_DATA, DUPLICATE_DATA;

        Mono<ClientResponse> reply() {
            return switch (this) {
                case PARTIAL_CONTENT -> response(HttpStatus.PARTIAL_CONTENT, EMPTY);
                case UNAUTHORIZED -> response(HttpStatus.UNAUTHORIZED, EMPTY);
                case RATE_LIMIT -> response(HttpStatus.TOO_MANY_REQUESTS, EMPTY);
                case SERVER_ERROR -> response(HttpStatus.INTERNAL_SERVER_ERROR, EMPTY);
                case API_ERROR -> response(HttpStatus.OK, "{\"code\":\"99999\",\"data\":[]}");
                case TIMEOUT -> Mono.never();
                case TRANSPORT -> Mono.error(new IOException("synthetic disconnect"));
                case NON_JSON -> response(HttpStatus.OK, "<html>synthetic edge error</html>");
                case MISSING_DATA -> response(HttpStatus.OK, "{\"code\":\"0\"}");
                case OBJECT_DATA -> response(HttpStatus.OK, "{\"code\":\"0\",\"data\":{}}");
                case DUPLICATE_DATA -> response(HttpStatus.OK, "{\"code\":\"0\",\"data\":[{}],\"data\":[]}");
            };
        }
    }

    @ParameterizedTest
    @CsvSource({"50011,rate-limited", "50102,server clock", "50111,rejected the API key",
        "50113,rejected the request signature", "51024,check the account's status and region on OKX"})
    void explainsKnownOkxCodes(String code, String explanation) {
        assertThatThrownBy(() -> withBody(TRADING, "{\"code\":\"" + code + "\",\"msg\":\"upstream message\",\"data\":[]}")
            .fetchPositions(CREDENTIALS)).isInstanceOf(SyncException.class)
            .hasMessageContaining(code).hasMessageContaining(explanation).hasMessageNotContaining("upstream message");
    }

    @ParameterizedTest
    @CsvSource({"OK", "UNAUTHORIZED"})
    void explainsRegionalKeyErrorsWithoutEchoingCredentials(HttpStatus status, CapturedOutput output) {
        String echoed = String.join(" ", CREDENTIALS.apiKey(), CREDENTIALS.apiSecret(), CREDENTIALS.apiPassphrase());
        OkxAdapter adapter = adapter(path -> response(status,
            "{\"code\":\"50119\",\"data\":[],\"msg\":\"" + echoed + "\"}"));
        assertThatThrownBy(() -> adapter.fetchPositions(CREDENTIALS)).isInstanceOf(SyncException.class)
            .hasMessageContainingAll("does not exist in the selected region", "Add exchange",
                "region where the key was created", "updates the existing connection",
                "Global = okx.com", "EEA = my.okx.com", "US = app.okx.com")
            .hasMessageNotContainingAny("Remove", CREDENTIALS.apiKey(), CREDENTIALS.apiSecret(), CREDENTIALS.apiPassphrase())
            .hasNoCause();
        assertThatThrownBy(() -> adapter.testConnection(CREDENTIALS)).isInstanceOf(SyncException.class)
            .hasMessageContaining("50119")
            .hasMessageNotContainingAny(CREDENTIALS.apiKey(), CREDENTIALS.apiSecret(), CREDENTIALS.apiPassphrase());
        assertThat(output.toString()).doesNotContain(CREDENTIALS.apiKey(), CREDENTIALS.apiSecret(), CREDENTIALS.apiPassphrase());
    }

    @Test
    void rejectsNegativeEquityBeforeNettingSpotBalances() {
        assertThatThrownBy(() -> withBody(TRADING,
            "{\"code\":\"0\",\"data\":[{\"details\":[{\"ccy\":\"COIN\",\"eq\":\"-2\"}]}]}")
            .fetchPositions(CREDENTIALS)).isInstanceOf(SyncException.class).hasMessageContaining("liabilities");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"ordId\":\"1\"}", "{\"ordId\":\"1\",\"investData\":[]}"})
    void rejectsActiveOrdersWithoutInvestments(String order) {
        assertThatThrownBy(() -> withBody(ORDERS, envelope(order))
            .fetchPositions(CREDENTIALS)).isInstanceOf(SyncException.class).hasMessageContaining("investData");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"investData\":[{\"ccy\":\"COIN\",\"amt\":\"1\"}]}",
        "{\"ordId\":\" \",\"investData\":[{\"ccy\":\"COIN\",\"amt\":\"1\"}]}"
    })
    void rejectsActiveOrdersWithoutANonblankId(String order) {
        assertThatThrownBy(() -> withBody(ORDERS, envelope(order)).fetchPositions(CREDENTIALS))
            .isInstanceOf(SyncException.class).hasMessageContainingAll("ordId", ORDERS);
    }

    @Test
    void rejectsDuplicateActiveOrderIds() {
        String order = "{\"ordId\":\"1\",\"investData\":[{\"ccy\":\"COIN\",\"amt\":\"2\"}]}";
        assertThatThrownBy(() -> withBody(ORDERS, envelope(order + "," + order)).fetchPositions(CREDENTIALS))
            .isInstanceOf(SyncException.class).hasMessageContainingAll("duplicate", "ordId", ORDERS);
    }

    @ParameterizedTest
    @MethodSource("invalidBalances")
    void rejectsInvalidCurrencyOrAmount(String endpoint, String body, String field) {
        assertThatThrownBy(() -> withBody(endpoint, body).fetchPositions(CREDENTIALS))
            .isInstanceOf(SyncException.class).hasMessageContaining("unexpected " + field).hasMessageContaining(endpoint);
    }

    private static Stream<Arguments> invalidBalances() {
        return ENDPOINTS.stream().flatMap(path -> {
            String field = path.equals(TRADING) ? "eq" : path.equals(FUNDING) ? "bal" : "amt";
            String currency = switch (path) {
                case ETH_STAKING -> "BETH";
                case SOL_STAKING -> "OKSOL";
                default -> "COIN";
            };
            return Stream.of(
                Arguments.of(path, balance(path, "{\"%s\":\"0\"}".formatted(field)), "ccy"),
                Arguments.of(path, balance(path, "{\"ccy\":\"%s\"}".formatted(currency)), field),
                Arguments.of(path, balance(path, "{\"ccy\":\"%s\",\"%s\":\"  \"}".formatted(currency, field)), field),
                Arguments.of(path, balance(path, "{\"ccy\":\"%s\",\"%s\":\"bad\"}".formatted(currency, field)), field));
        });
    }

    @ParameterizedTest
    @CsvSource({"OKSOL," + ETH_STAKING, "BETH," + SOL_STAKING})
    void rejectsUnexpectedStakingCurrencyEvenForZeroAmounts(String currency, String path) {
        var adapter = withBody(path, envelope("{\"ccy\":\"%s\",\"amt\":\"0\"}".formatted(currency)));
        assertThatThrownBy(() -> adapter.fetchPositions(CREDENTIALS))
            .isInstanceOf(SyncException.class).hasMessageContainingAll("ccy", path);
    }

    @ParameterizedTest
    @CsvSource({"ETH,BETH," + ETH_STAKING, "SOL,OKSOL," + SOL_STAKING})
    void keepsNativeSpotAndStaking(String asset, String receipt, String stakingPath) {
        var adapter = adapter(path -> response(HttpStatus.OK, path.equals(stakingPath)
            ? envelope("{\"ccy\":\"%s\",\"amt\":\"3\"}".formatted(receipt))
            : path.equals(FUNDING) ? envelope("{\"ccy\":\"%s\",\"bal\":\"2\"}".formatted(asset)) : emptyFor(path)));
        assertThat(adapter.fetchPositions(CREDENTIALS))
            .containsExactly(ExchangePosition.spot(asset, BigDecimal.valueOf(2)),
                new ExchangePosition(STAKING, asset, BigDecimal.valueOf(3), null, null));
    }

    @ParameterizedTest
    @CsvSource({"ETH,BETH," + ETH_STAKING, "SOL,OKSOL," + SOL_STAKING})
    void valuesSpotReceiptsAsTheUnderlyingAssetWhenStakingIsZero(String asset, String receipt, String stakingPath) {
        var adapter = adapter(path -> response(HttpStatus.OK, path.equals(stakingPath)
            ? envelope("{\"ccy\":\"%s\",\"amt\":\"0.0\"}".formatted(receipt))
            : path.equals(FUNDING) ? envelope("{\"ccy\":\"%s\",\"bal\":\"2\"}".formatted(receipt)) : emptyFor(path)));
        assertThat(adapter.fetchPositions(CREDENTIALS)).containsExactly(ExchangePosition.spot(asset, BigDecimal.valueOf(2)));
    }

    @ParameterizedTest
    @CsvSource({"BETH," + ETH_STAKING + "," + TRADING + ",eq", "OKSOL," + SOL_STAKING + "," + FUNDING + ",bal"})
    void rejectsAmbiguousReceiptOverlap(String receipt, String stakingPath, String spotPath, String field) {
        var bodies = Map.of(stakingPath, envelope("{\"ccy\":\"%s\",\"amt\":\"3\"}".formatted(receipt)),
            spotPath, balance(spotPath, "{\"ccy\":\"%s\",\"%s\":\"2\"}".formatted(receipt, field)));
        assertThatThrownBy(() -> adapter(path -> response(HttpStatus.OK, bodies.getOrDefault(path, emptyFor(path))))
            .fetchPositions(CREDENTIALS)).isInstanceOf(SyncException.class)
            .hasMessageContainingAll(receipt, "both spot and staking", "overlap");
    }

    private static String balance(String path, String row) {
        return envelope(switch (path) {
            case TRADING -> "{\"details\":[" + row + "]}";
            case ORDERS -> "{\"ordId\":\"1\",\"investData\":[" + row + "]}";
            default -> row;
        });
    }

    private static String envelope(String row) {
        return "{\"code\":\"0\",\"data\":[" + row + "]}";
    }

    private OkxAdapter withBody(String endpoint, String body) {
        return adapter(path -> response(HttpStatus.OK, path.equals(endpoint) ? body : emptyFor(path)));
    }

    /** An empty account: OKX still answers the trading endpoint with one zero-total row. */
    private static String emptyFor(String path) {
        return path.equals(TRADING) ? envelope("{\"totalEq\":\"0\",\"details\":[]}") : EMPTY;
    }

    private OkxAdapter adapter(Function<String, Mono<ClientResponse>> routing) {
        return adapter(routing, CLOCK);
    }

    private OkxAdapter adapter(Function<String, Mono<ClientResponse>> routing, Clock clock) {
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return routing.apply(request.url().getPath());
        }).build();
        return new OkxAdapter(client, Map.of("EEA", URI.create("https://eea.example.test"),
            "US", URI.create("https://us.example.test")), new ObjectMapper(), clock, Duration.ofMillis(100));
    }

    private static Mono<ClientResponse> response(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status).body(body).build());
    }
}
