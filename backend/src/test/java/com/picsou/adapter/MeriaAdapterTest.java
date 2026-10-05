package com.picsou.adapter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.model.ExchangeType;
import com.picsou.port.CryptoExchangePort.ExchangePosition;
import com.picsou.port.CryptoExchangePort.Product;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the two things this adapter exists to get right.
 *
 * <p><b>Positions:</b> a Meria balance is spread over wallets, stakings and lendings, and each
 * stays its own line so the account page can show which is which. A staking contract's accrued
 * {@code reward} is reported as a <em>decomposition</em> of what is held, never added on top.
 *
 * <p><b>All or nothing:</b> every partial-read path must throw rather than return a shorter list.
 * The products collapse into a single account balance that gets written to a daily snapshot, so a
 * silently dropped call dents the net-worth history instead of surfacing as a missing sub-account.
 * The cases that are legitimately empty — a product the user hasn't subscribed to — arrive as
 * {@code data: []} or an empty body on a 2xx, never as an error.
 */
class MeriaAdapterTest {

    private static final String BASE_URL = "https://api.meria.test/v1";
    private static final String API_KEY = "meria-read-only-key";
    private static final String EMPTY = "{\"success\":true,\"data\":[]}";

    private final List<ClientRequest> requests = new ArrayList<>();
    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MeriaAdapter.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    // ── Aggregation ───────────────────────────────────────────────────────────

    @Test
    void fetchPositions_reportsOneLinePerProductAndCurrency() {
        MeriaAdapter adapter = adapterWith(
            """
            {"success":true,"data":[
              {"currencyCode":"ZEC","balance":0.00160223},
              {"currencyCode":"ETH","balance":0.03190596}
            ]}""",
            """
            {"success":true,"data":[
              {"currencyCode":"KAVA","amount":75,"reward":0.01027898,"lockedReward":0,
               "startDate":1590582716,"variations":[],"credits":[]},
              {"currencyCode":"ETH","amount":1.5,"reward":0.5,"lockedReward":0}
            ]}""",
            """
            {"success":true,"data":[
              {"currencyCode":"USDT","amount":75,"reward":0.01027898,"lockedReward":0}
            ]}"""
        );

        List<ExchangePosition> holdings = adapter.fetchPositions(API_KEY, null);

        // ETH is held twice — in spot and staked — and stays two lines: merging them is exactly
        // the distinction the account page exists to show. The caller sums per asset for the
        // balance.
        assertThat(holdings).extracting(ExchangePosition::product, ExchangePosition::symbol)
            .containsExactly(
                tuple(Product.SPOT, "ETH"), tuple(Product.SPOT, "ZEC"),
                tuple(Product.STAKING, "ETH"), tuple(Product.STAKING, "KAVA"),
                tuple(Product.LENDING, "USDT"));
        assertThat(positionOf(holdings, Product.SPOT, "ETH").quantity()).isEqualByComparingTo("0.03190596");
        assertThat(positionOf(holdings, Product.STAKING, "ETH").quantity()).isEqualByComparingTo("1.5");
        assertThat(positionOf(holdings, Product.STAKING, "KAVA").quantity()).isEqualByComparingTo("75");
        assertThat(positionOf(holdings, Product.LENDING, "USDT").quantity()).isEqualByComparingTo("75");
        assertThat(positionOf(holdings, Product.SPOT, "ZEC").quantity()).isEqualByComparingTo("0.00160223");
    }

    @Test
    void fetchPositions_doesNotAddAccruedInterestOnTopOfTheContractAmount() {
        // `reward` is the contract's cumulative interest to date and `amount` already reflects it.
        // Adding them shipped once and overstated a real account by exactly its total interest.
        MeriaAdapter adapter = adapterWith(EMPTY,
            """
            {"success":true,"data":[
              {"currencyCode":"ATOM","amount":33.154,"reward":13.424,"lockedReward":0}
            ]}""",
            EMPTY);

        List<ExchangePosition> positions = adapter.fetchPositions(API_KEY, null);

        ExchangePosition atom = positionOf(positions, Product.STAKING, "ATOM");
        assertThat(atom.quantity()).isEqualByComparingTo("33.154");
        // The reward is reported as a decomposition of what is held, never as an addition.
        assertThat(atom.interest()).isEqualByComparingTo("13.424");
        assertThat(atom.principal()).isEqualByComparingTo("19.730");
    }

    @Test
    void fetchPositions_reportsNoDecompositionWhenTheRewardCannotBeOne() {
        // interest decomposes the quantity, so a reward above it or below zero describes nothing
        // real. The quantity is still counted — it is what the balance is built from — but both
        // halves are dropped rather than publishing the impossible figure with the principal
        // blanked out beside it, which left the reader nothing to judge it against.
        MeriaAdapter adapter = adapterWith(EMPTY,
            """
            {"success":true,"data":[
              {"currencyCode":"ATOM","amount":10,"reward":12,"lockedReward":0},
              {"currencyCode":"KAVA","amount":10,"reward":-2,"lockedReward":0}
            ]}""",
            EMPTY);

        List<ExchangePosition> positions = adapter.fetchPositions(API_KEY, null);

        ExchangePosition atom = positionOf(positions, Product.STAKING, "ATOM");
        assertThat(atom.quantity()).isEqualByComparingTo("10");
        assertThat(atom.interest()).isNull();
        assertThat(atom.principal()).isNull();

        ExchangePosition kava = positionOf(positions, Product.STAKING, "KAVA");
        assertThat(kava.quantity()).isEqualByComparingTo("10");
        assertThat(kava.interest()).isNull();
        assertThat(kava.principal()).isNull();
    }

    @Test
    void fetchPositions_mergesTwoContractsAsUndecomposedWhenEitherIsUnusable() {
        // Summing a known interest with an unknown one reports a total the principal cannot be
        // reconciled against — the same half-stated figure, rebuilt by the merge.
        MeriaAdapter adapter = adapterWith(EMPTY,
            """
            {"success":true,"data":[
              {"currencyCode":"ATOM","amount":10,"reward":2,"lockedReward":0},
              {"currencyCode":"ATOM","amount":10,"reward":12,"lockedReward":0}
            ]}""",
            EMPTY);

        ExchangePosition atom = positionOf(adapter.fetchPositions(API_KEY, null), Product.STAKING, "ATOM");

        assertThat(atom.quantity()).isEqualByComparingTo("20");
        assertThat(atom.interest()).isNull();
        assertThat(atom.principal()).isNull();
    }

    @Test
    void fetchPositions_countsAContractWithNoRewardFieldsAtAll() {
        MeriaAdapter adapter = adapterWith(EMPTY,
            "{\"success\":true,\"data\":[{\"currencyCode\":\"XTZ\",\"amount\":42}]}",
            EMPTY);

        assertThat(quantityOf(adapter.fetchPositions(API_KEY, null), "XTZ"))
            .isEqualByComparingTo("42");
    }

    @Test
    void fetchPositions_dropsEntriesThatCannotBeCounted() {
        MeriaAdapter adapter = adapterWith(
            """
            {"success":true,"data":[
              {"currencyCode":"BTC","balance":0},
              {"currencyCode":"","balance":1},
              {"currencyCode":"ETH","balance":0.5}
            ]}""",
            EMPTY, EMPTY);

        assertThat(adapter.fetchPositions(API_KEY, null))
            .extracting(ExchangePosition::symbol).containsExactly("ETH");
    }

    @Test
    void fetchPositions_uppercasesSymbolsUnderAnyDefaultLocale() {
        // A bare toUpperCase() under a Turkish locale turns "i" into a dotless "ı", which would
        // silently mint a second ticker CoinGecko cannot price.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            MeriaAdapter adapter = adapterWith(
                "{\"success\":true,\"data\":[{\"currencyCode\":\" inj \",\"balance\":3}]}",
                EMPTY, EMPTY);

            assertThat(adapter.fetchPositions(API_KEY, null))
                .extracting(ExchangePosition::symbol).containsExactly("INJ");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void fetchPositions_returnsNothingWhenEveryProductIsEmpty() {
        // The "user holds no stakings and no lendings" case: empty, not an error.
        MeriaAdapter adapter = adapterWith(EMPTY, EMPTY, EMPTY);

        assertThat(adapter.fetchPositions(API_KEY, null)).isEmpty();
    }

    // ── All or nothing ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "HTTP {1} on /{0} fails the whole sync")
    @CsvSource({
        "wallets,401", "wallets,403", "wallets,404", "wallets,429", "wallets,500",
        "stakings,401", "stakings,403", "stakings,404", "stakings,429", "stakings,500",
        "lendings,401", "lendings,403", "lendings,404", "lendings,429", "lendings,500",
    })
    void fetchPositions_failsWhenAnyEndpointReturnsAnHttpError(String endpoint, int status) {
        // A perfectly healthy envelope behind a failing status, so the status is the only thing
        // left to fail the sync. Every fixture here used to carry `success:false` as well, which
        // meant an adapter ignoring the status code entirely and gating on the envelope alone
        // passed all fifteen cases — the three branches below were never exercised.
        MeriaAdapter adapter = adapterRouting(path -> path.equals(endpoint)
            ? response(HttpStatus.valueOf(status), "{\"success\":true,\"data\":[]}")
            : response(HttpStatus.OK, EMPTY));

        // Each class of status has to keep its own message: they are the difference between
        // telling the user their key was refused, that we were throttled, and that something
        // else went wrong upstream.
        String expected = switch (status) {
            case 401, 403 -> "rejected the API key";
            case 429 -> "rate-limited";
            default -> "answered HTTP " + status;
        };
        assertThatThrownBy(() -> adapter.fetchPositions(API_KEY, null))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getMessage()).contains(expected);
                assertThat(error.getMessage()).doesNotContain(API_KEY);
            });
        assertThat(logs.list).noneSatisfy(event ->
            assertThat(event.getFormattedMessage()).contains(API_KEY));
    }

    @Test
    void fetchPositions_failsWhenSuccessIsFalseOnHttp200() {
        // The envelope is the gate, not the status code.
        MeriaAdapter adapter = adapterWith(
            "{\"success\":false,\"error\":{\"code\":403,\"message\":\"UNAUTHORIZED\"}}",
            EMPTY, EMPTY);

        assertThatThrownBy(() -> adapter.fetchPositions(API_KEY, null))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("UNAUTHORIZED");
    }

    @Test
    void fetchPositions_readsAnEmptyBodyOnASuccessfulResponseAsNoEntries() {
        // Observed in production: Meria answers /stakings with HTTP 200 and no body at all when
        // the account holds no staking contracts. Failing there would break a healthy account.
        MeriaAdapter adapter = adapterWith(
            """
            {"success":true,"data":[{"currencyCode":"ETH","balance":0.5}]}""",
            "", "");

        assertThat(adapter.fetchPositions(API_KEY, null))
            .extracting(ExchangePosition::symbol).containsExactly("ETH");
    }

    @Test
    void fetchPositions_readsAnEmptyBodyWhateverContentTypeItIsDeclaredWith() {
        // The production shape: Meria's empty /stakings response is not declared as JSON, and a
        // typed codec turns that into a decode error reported as a WebClientResponseException
        // carrying the original 200 — which is why the body is read as raw bytes.
        MeriaAdapter adapter = adapterRouting(endpoint -> switch (endpoint) {
            case "wallets" -> response(HttpStatus.OK,
                "{\"success\":true,\"data\":[{\"currencyCode\":\"ETH\",\"balance\":0.5}]}");
            // No Content-Type header at all, no body.
            default -> Mono.just(ClientResponse.create(HttpStatus.OK, MeriaAdapter.STRATEGIES).build());
        });

        assertThat(adapter.fetchPositions(API_KEY, null))
            .extracting(ExchangePosition::symbol).containsExactly("ETH");
    }

    @Test
    void fetchPositions_parsesJsonEvenWhenItIsNotDeclaredAsJson() {
        MeriaAdapter adapter = adapterRouting(endpoint -> Mono.just(ClientResponse.create(HttpStatus.OK, MeriaAdapter.STRATEGIES)
            .header("Content-Type", MediaType.TEXT_PLAIN_VALUE)
            .body(endpoint.equals("wallets")
                ? "{\"success\":true,\"data\":[{\"currencyCode\":\"BTC\",\"balance\":0.25}]}"
                : EMPTY)
            .build()));

        assertThat(adapter.fetchPositions(API_KEY, null))
            .extracting(ExchangePosition::symbol).containsExactly("BTC");
    }

    @Test
    void fetchPositions_readsAStakingPayloadLargerThanTheFrameworkDefaultBuffer() {
        // The failure that actually broke the first release: /stakings carries every contract's
        // full variations+credits history, which blows past WebClient's 256 KB default and
        // surfaces as a DataBufferLimitException — or, through a typed codec, as an HTTP 200 with
        // an unreadable body. Build a contract with enough credits to exceed that default.
        StringBuilder credits = new StringBuilder();
        for (int i = 0; i < 12_000; i++) {
            if (i > 0) credits.append(',');
            credits.append("{\"amount\":0.00432936,\"date\":15907104").append(i % 100)
                .append(",\"releaseDate\":false,\"released\":1}");
        }
        String stakings = "{\"success\":true,\"data\":[{\"currencyCode\":\"ATOM\",\"amount\":100,"
            + "\"reward\":2.5,\"lockedReward\":0.75,\"variations\":[],\"credits\":["
            + credits + "]}]}";
        assertThat(stakings.length()).isGreaterThan(256 * 1024);

        MeriaAdapter adapter = adapterWith(EMPTY, stakings, EMPTY);

        assertThat(quantityOf(adapter.fetchPositions(API_KEY, null), "ATOM"))
            .isEqualByComparingTo("100");
    }

    @Test
    void fetchPositions_stillFailsWhenTheBodyIsUnparseableRatherThanEmpty() {
        MeriaAdapter adapter = adapterRouting(path -> response(HttpStatus.OK, "{not json"));

        assertThatThrownBy(() -> adapter.fetchPositions(API_KEY, null))
            .isInstanceOf(SyncException.class);
    }

    @Test
    void fetchPositions_failsOnTimeout_despiteReactorWrapping() {
        // TimeoutException is CHECKED, so block() delivers it wrapped in a ReactiveException.
        // Matching the declared type without unwrapping would miss the commonest real failure.
        MeriaAdapter adapter = new MeriaAdapter(
            WebClient.builder().baseUrl(BASE_URL)
                .exchangeFunction(request -> Mono.never()).build(),
            new ObjectMapper(), Duration.ofMillis(20));

        assertThatThrownBy(() -> adapter.fetchPositions(API_KEY, null))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("too long");
        assertThat(eventsAt(Level.WARN)).anySatisfy(event ->
            assertThat(event.getFormattedMessage()).containsIgnoringCase("timed out"));
    }

    @Test
    void fetchPositions_failsOnANonJsonBody_andLogsItSoItCanBeDiagnosed() {
        // Meria sits behind Cloudflare: an HTML challenge page instead of JSON is realistic, and
        // undiagnosable unless the body reaches the log.
        MeriaAdapter adapter = adapterRouting(path -> Mono.just(ClientResponse.create(HttpStatus.OK, MeriaAdapter.STRATEGIES)
            .header("Content-Type", MediaType.TEXT_HTML_VALUE)
            .body("<html><body>Vérification de votre identité</body></html>")
            .build()));

        assertThatThrownBy(() -> adapter.fetchPositions(API_KEY, null))
            .isInstanceOf(SyncException.class);
        assertThat(eventsAt(Level.WARN)).anySatisfy(event ->
            assertThat(event.getFormattedMessage()).contains("<html>"));
    }

    @Test
    void fetchPositions_stillFailsOnADefectOfOurOwn_butLogsItAsOne() {
        // An expected outage is a WARN; a bug on our side keeps its stacktrace at ERROR, so it
        // stays distinguishable instead of reading as "Meria was down".
        MeriaAdapter adapter = adapterRouting(path -> Mono.error(new IllegalStateException("a real bug")));

        assertThatThrownBy(() -> adapter.fetchPositions(API_KEY, null))
            .isInstanceOf(SyncException.class);
        // The event this defect produced, not merely some ERROR: isNotEmpty() was satisfied by
        // any unrelated error the adapter happened to log, which is no evidence that a bug on our
        // side is reported as one.
        assertThat(eventsAt(Level.ERROR)).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                .contains("failed unexpectedly")
                .contains(IllegalStateException.class.getName());
            // The stacktrace is the whole point of the level: without it this reads as an outage.
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    // ── Authentication ────────────────────────────────────────────────────────

    @Test
    void fetchPositions_authenticatesWithTheApiKeyHeaderAndNothingElse() {
        adapterWith(EMPTY, EMPTY, EMPTY).fetchPositions(API_KEY, null);

        assertThat(requests).hasSize(3).allSatisfy(request -> {
            assertThat(request.headers().getFirst("API-KEY")).isEqualTo(API_KEY);
            assertThat(request.headers().getFirst("Authorization")).isNull();
            assertThat(request.headers().getFirst("X-MBX-APIKEY")).isNull();
            // Meria never signs a request: no timestamp, no signature.
            assertThat(request.url().getQuery()).isNull();
        });
        assertThat(requests).extracting(request -> request.url().getPath())
            .containsExactly("/v1/wallets", "/v1/stakings", "/v1/lendings");
    }

    @Test
    void fetchPositions_ignoresTheApiSecretArgument() {
        adapterWith(EMPTY, EMPTY, EMPTY).fetchPositions(API_KEY, "leaked-secret");

        assertThat(requests).allSatisfy(request ->
            assertThat(request.headers().toSingleValueMap().values())
                .noneMatch(value -> value.contains("leaked-secret")));
    }

    // ── Connection test ───────────────────────────────────────────────────────

    @Test
    void testConnection_probesStatusAndAcceptsASuccessfulEnvelope() {
        assertThat(adapterWith(EMPTY, EMPTY, EMPTY).testConnection(API_KEY, null)).isTrue();
        assertThat(requests).singleElement()
            .satisfies(request -> assertThat(request.url().getPath()).isEqualTo("/v1/status"));
    }

    @Test
    void testConnection_rejectsAnUnauthorizedKey() {
        MeriaAdapter adapter = adapterRouting(path -> response(HttpStatus.FORBIDDEN,
            "{\"success\":false,\"error\":{\"code\":403,\"message\":\"UNAUTHORIZED\"}}"));

        assertThat(adapter.testConnection("wrong-key", null)).isFalse();
    }

    @Test
    void testConnection_rejectsASuccessFalseEnvelope() {
        MeriaAdapter adapter = adapterRouting(path -> response(HttpStatus.OK, "{\"success\":false}"));

        assertThat(adapter.testConnection(API_KEY, null)).isFalse();
    }

    @Test
    void testConnection_rejectsATimeout() {
        MeriaAdapter adapter = new MeriaAdapter(
            WebClient.builder().baseUrl(BASE_URL)
                .exchangeFunction(request -> Mono.never()).build(),
            new ObjectMapper(), Duration.ofMillis(20));

        assertThat(adapter.testConnection(API_KEY, null)).isFalse();
    }

    // ── Wiring contract ───────────────────────────────────────────────────────

    @Test
    void exchangeName_matchesTheEnumConstant() {
        // CryptoExchangeSyncService.findAdapter matches on this string; a typo silently makes
        // MERIA "not supported yet".
        assertThat(adapterWith(EMPTY, EMPTY, EMPTY).exchangeName())
            .isEqualTo(ExchangeType.MERIA.name());
    }

    @Test
    void requiresApiSecret_isFalse() {
        assertThat(adapterWith(EMPTY, EMPTY, EMPTY).requiresApiSecret()).isFalse();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private MeriaAdapter adapterWith(String wallets, String stakings, String lendings) {
        return adapterRouting(endpoint -> switch (endpoint) {
            case "wallets" -> response(HttpStatus.OK, wallets);
            case "stakings" -> response(HttpStatus.OK, stakings);
            case "lendings" -> response(HttpStatus.OK, lendings);
            case "status" -> response(HttpStatus.OK, "{\"success\":true}");
            default -> response(HttpStatus.NOT_FOUND,
                "{\"success\":false,\"error\":{\"code\":404,\"message\":\"ENDPOINT_NOT_FOUND\"}}");
        });
    }

    /** Routes on the last path segment, so each endpoint can answer differently. */
    private MeriaAdapter adapterRouting(Function<String, Mono<ClientResponse>> route) {
        ExchangeFunction exchange = request -> {
            requests.add(request);
            String path = request.url().getPath();
            return route.apply(path.substring(path.lastIndexOf('/') + 1));
        };
        return new MeriaAdapter(
            WebClient.builder().baseUrl(BASE_URL).exchangeFunction(exchange).build(),
            new ObjectMapper(), Duration.ofSeconds(5));
    }

    // ClientResponse.create(status) alone would decode under the framework's 256 KB default; the
    // production strategies are what this adapter actually runs with.
    private static Mono<ClientResponse> response(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status, MeriaAdapter.STRATEGIES)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
    }

    private static java.math.BigDecimal quantityOf(List<ExchangePosition> holdings, String symbol) {
        return holdings.stream()
            .filter(holding -> holding.symbol().equals(symbol))
            .findFirst().orElseThrow().quantity();
    }

    private static ExchangePosition positionOf(List<ExchangePosition> positions, Product product, String symbol) {
        return positions.stream()
            .filter(position -> position.product() == product && position.symbol().equals(symbol))
            .findFirst().orElseThrow();
    }

    private List<ILoggingEvent> eventsAt(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level).toList();
    }
}
