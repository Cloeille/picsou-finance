package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.exception.SyncException;
import org.springframework.web.reactive.function.client.WebClient;
import com.picsou.port.TradeRepublicPort.TrAccountData;
import com.picsou.port.TradeRepublicPort.TrTokens;
import org.springframework.web.reactive.function.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class TradeRepublicAdapterTest {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(2);

    private DisposableServer server;

    @Test
    void productionConstructorIsExplicitSpringInjectionPoint() throws NoSuchMethodException {
        assertThat(TradeRepublicAdapter.class
            .getConstructor(SidecarWebClientFactory.class, ObjectMapper.class, String.class)
            .isAnnotationPresent(Autowired.class))
            .isTrue();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void refreshSession_rejectedStatusMapsToSessionExpired(int status) {
        TradeRepublicAdapter adapter = adapterReturning(status, "{\"detail\":\"rejected\"}");

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessage("SESSION_EXPIRED");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void refreshSession_transientStatusMapsToUnavailable(int status) {
        TradeRepublicAdapter adapter = adapterReturning(status, "{\"detail\":\"temporary\"}");

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("unavailable");
        assertThat(thrown.getMessage()).isNotEqualTo("SESSION_EXPIRED");
    }

    @Test
    void refreshSession_timeoutMapsToUnavailable() {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> Mono.never())
            .bindNow();
        TradeRepublicAdapter adapter = adapterFor(server, Duration.ofMillis(25));

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("unavailable");
        assertThat(thrown.getMessage()).isNotEqualTo("SESSION_EXPIRED");
    }

    @Test
    void refreshSession_emptySuccessBodyIsNotSessionExpired() {
        TradeRepublicAdapter adapter = adapterReturning(200, "");

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("empty response");
        assertThat(thrown.getMessage()).isNotEqualTo("SESSION_EXPIRED");
    }

    @Test
    void refreshSession_missingSessionTokenIsNotSessionExpired() {
        TradeRepublicAdapter adapter = adapterReturning(200, "{\"refreshToken\":\"rotated\"}");

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("empty response");
        assertThat(thrown.getMessage()).isNotEqualTo("SESSION_EXPIRED");
    }

    @Test
    void refreshSession_validResponseKeepsPreviousRefreshTokenWhenNotRotated() {
        TradeRepublicAdapter adapter = adapterReturning(200, "{\"sessionToken\":\"new-session\"}");

        TrTokens tokens = adapter.refreshSession("refresh-token");

        assertThat(tokens.sessionToken()).isEqualTo("new-session");
        assertThat(tokens.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    void refreshSession_validResponseUsesRotatedRefreshToken() {
        TradeRepublicAdapter adapter = adapterReturning(
            200,
            "{\"sessionToken\":\"new-session\",\"refreshToken\":\"rotated\"}"
        );

        TrTokens tokens = adapter.refreshSession("refresh-token");

        assertThat(tokens.sessionToken()).isEqualTo("new-session");
        assertThat(tokens.refreshToken()).isEqualTo("rotated");
    }

    @Test
    void initiateAuth_wafBlockIsReportedAsTemporaryNotAsWrongCredentials() {
        TradeRepublicAdapter adapter = adapterReturning(503, "{\"detail\":\"TR_WAF_BLOCKED\"}");

        Throwable thrown = catchThrowable(() -> adapter.initiateAuth("+33600000000", "1234"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("Trade Republic blocked the request")
            .hasMessageNotContaining("credentials");
    }

    @Test
    void completeAuth_wafBlockIsReportedAsTemporaryNotAsAnInvalidCode() {
        TradeRepublicAdapter adapter = adapterReturning(503, "{\"detail\":\"TR_WAF_BLOCKED\"}");

        Throwable thrown = catchThrowable(() -> adapter.completeAuth("process-id", "1234"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("Trade Republic blocked the request")
            .hasMessageNotContaining("verification code");
    }

    @Test
    void completeAuth_validationErrorEchoingTheWafCodeIsNotAWafBlock() {
        // A FastAPI 422 echoes the rejected input: it must not pass for the sidecar's own detail.
        TradeRepublicAdapter adapter = adapterReturning(422, """
            {"detail":[{"type":"string_pattern_mismatch","loc":["body","processId"],\
            "msg":"String should match pattern","input":"TR_WAF_BLOCKED"}]}""");

        Throwable thrown = catchThrowable(() -> adapter.completeAuth("TR_WAF_BLOCKED", "1234"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("verification code")
            .hasMessageNotContaining("blocked the request");
    }

    @Test
    void initiateAuth_trTimeoutIsReportedAsNoResponseNotAsWrongCredentials() {
        TradeRepublicAdapter adapter = adapterReturning(504, "{\"detail\":\"TR_UNREACHABLE\"}");

        Throwable thrown = catchThrowable(() -> adapter.initiateAuth("+33600000000", "1234"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("did not respond")
            .hasMessageNotContaining("credentials");
    }

    @Test
    void initiateAuth_browserFailureIsReportedAsServiceUnavailableNotAsWrongCredentials() {
        TradeRepublicAdapter adapter = adapterReturning(503, "{\"detail\":\"TR_BROWSER_FAILED\"}");

        Throwable thrown = catchThrowable(() -> adapter.initiateAuth("+33600000000", "1234"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("unavailable")
            .hasMessageNotContaining("credentials");
    }

    @Test
    void completeAuth_unhandledSidecarErrorIsReportedAsServiceUnavailableNotAsAnInvalidCode() {
        TradeRepublicAdapter adapter = adapterReturning(500, "Internal Server Error");

        Throwable thrown = catchThrowable(() -> adapter.completeAuth("process-id", "1234"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("unavailable")
            .hasMessageNotContaining("verification code");
    }

    @Test
    void initiateAuth_trPinRejectionStillReportsTheCredentialError() {
        TradeRepublicAdapter adapter = adapterReturning(
            400, "{\"detail\":\"TR rejected request: {\\\"errors\\\":[{\\\"errorCode\\\":\\\"PIN_INVALID\\\"}]}\"}");

        Throwable thrown = catchThrowable(() -> adapter.initiateAuth("+33600000000", "1234"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessage("PIN_INVALID");
    }

    private TradeRepublicAdapter adapterReturning(int status, String body) {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> response
                .status(status)
                .header("Content-Type", "application/json")
                .sendString(Mono.just(body)))
            .bindNow();
        return adapterFor(server, RESPONSE_TIMEOUT);
    }

    private static TradeRepublicAdapter adapterFor(DisposableServer server, Duration timeout) {
        String baseUrl = "http://127.0.0.1:" + server.port();
        return new TradeRepublicAdapter(
            new ObjectMapper(),
            WebClient.builder().baseUrl(baseUrl).build(),
            timeout,
            URI.create("ws://127.0.0.1:" + server.port() + "/")
        );
    }

    // ─── fetchAccounts: which venue a position is priced on ────────────────────────────────

    private static final String BTC = "XF000BTC0017";

    /**
     * Issue #268: TR answered FORBIDDEN for the guessed {@code XF000BTC0017.TRD0}, and the
     * position was valued at its buy-in. The venue now comes from TR's instrument answer.
     */
    @Test
    void fetchAccounts_pricesAPositionOnTheExchangeItsInstrumentAnswerLists() {
        TradeRepublicAdapter adapter = adapterAgainstTr(
            BTC, "{\"exchangeIds\":[\"BHS\"]}", Map.of(BTC + ".BHS", "60000"));

        List<TrAccountData> accounts = adapter.fetchAccounts(sessionToken());

        TrAccountData securities = securities(accounts);
        assertThat(securities.positions()).singleElement().satisfies(position -> {
            assertThat(position.currentPrice()).isEqualByComparingTo("60000");
            assertThat(position.averageBuyIn()).isEqualByComparingTo("30000");
        });
        assertThat(securities.balanceEur()).isEqualByComparingTo("30000.00");
    }

    @Test
    void fetchAccounts_aTickerTrRefuses_leavesThePriceUnavailable_notTheBuyInDisguisedAsMarket() {
        TradeRepublicAdapter adapter = adapterAgainstTr(BTC, "{\"exchangeIds\":[\"BHS\"]}", Map.of());

        List<TrAccountData> accounts = adapter.fetchAccounts(sessionToken());

        TrAccountData securities = securities(accounts);
        assertThat(securities.positions()).singleElement().satisfies(position -> {
            assertThat(position.currentPrice()).isNull();
            assertThat(position.averageBuyIn()).isEqualByComparingTo("30000");
        });
        // The account total still carries the position at cost rather than dropping it.
        assertThat(securities.balanceEur()).isEqualByComparingTo("15000.00");
    }

    @Test
    void fetchAccounts_anInstrumentAnswerWithoutExchange_fallsBackToLsx() {
        String apple = "US0378331005";
        TradeRepublicAdapter adapter = adapterAgainstTr(
            apple, "{\"errors\":[{\"errorCode\":\"NOT_FOUND\"}]}", Map.of(apple + ".LSX", "200"));

        List<TrAccountData> accounts = adapter.fetchAccounts(sessionToken());

        assertThat(securities(accounts).positions()).singleElement()
            .satisfies(position -> assertThat(position.currentPrice()).isEqualByComparingTo("200"));
    }

    /**
     * A TR WebSocket holding one position of 0.5 units bought at 30 000. It answers the
     * instrument subscription with {@code instrumentAnswer} and prices only the ticker ids in
     * {@code pricedTickers}; any other ticker id gets the FORBIDDEN error TR sends for a venue
     * that does not list the instrument.
     */
    private TradeRepublicAdapter adapterAgainstTr(
            String isin, String instrumentAnswer, Map<String, String> pricedTickers) {
        return adapterAgainstTr("{\"categories\":[{\"positions\":[{\"isin\":\"" + isin
            + "\",\"netSize\":\"0.5\",\"averageBuyIn\":\"30000\"}]}]}", instrumentAnswer, pricedTickers);
    }

    /** Same server, with the {@code compactPortfolioByType} answer given as-is. */
    private TradeRepublicAdapter adapterAgainstTr(
            String portfolioAnswer, String instrumentAnswer, Map<String, String> pricedTickers) {
        ObjectMapper mapper = new ObjectMapper();
        Function<String, String> answer = message -> {
            if (message.startsWith("connect ")) return "connected";
            String[] parts = message.split(" ", 3);
            String id = parts[1];
            JsonNode sub;
            try {
                sub = mapper.readTree(parts[2]);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            return switch (sub.path("type").asText()) {
                case "availableCash" -> id + " A [{\"accountNumber\":\"CASH1\",\"currencyId\":\"EUR\",\"amount\":100}]";
                case "compactPortfolioByType" -> id + " A " + portfolioAnswer;
                case "instrument" -> (instrumentAnswer.contains("errors") ? id + " E " : id + " A ") + instrumentAnswer;
                case "ticker" -> {
                    String price = pricedTickers.get(sub.path("id").asText());
                    yield price != null
                        ? id + " A {\"last\":{\"price\":\"" + price + "\"}}"
                        : id + " E {\"errors\":[{\"errorCode\":\"FORBIDDEN\",\"errorMessage\":\"Forbidden\"}]}";
                }
                default -> null;
            };
        };
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes.ws("/", (in, out) ->
                out.sendString(in.receive().asString().mapNotNull(answer))))
            .bindNow();
        return adapterFor(server, RESPONSE_TIMEOUT);
    }

    /** A session token whose JWT claims carry one securities account, as TR issues them. */
    private static String sessionToken() {
        String claims = "{\"act\":{\"acc\":{\"owner\":{\"default\":{\"sec\":[\"SEC1\"],\"cash\":[\"CASH1\"]}}}}}";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + "."
            + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    private static TrAccountData securities(List<TrAccountData> accounts) {
        return accounts.stream()
            .filter(account -> "tr_securities".equals(account.externalId()))
            .findFirst()
            .orElseThrow();
    }

    // ─── shouldPersist: what an answer must be before it replaces the previous holdings ────

    /**
     * The case that wiped a PEA: the cash subscription answered (500 EUR), the portfolio
     * subscription answered with an error frame. Persisting it stored an account worth its
     * cash with no positions, so the service deleted every holding.
     */
    @Test
    void shouldPersist_anErroredPortfolioWithNoPositions_isSkipped_evenWithCash() {
        assertThat(TradeRepublicAdapter.shouldPersist(new java.math.BigDecimal("500"), true, true, true)).isFalse();
    }

    @Test
    void shouldPersist_aGenuinelyEmptiedPortfolio_isPersisted() {
        assertThat(TradeRepublicAdapter.shouldPersist(java.math.BigDecimal.ZERO, true, true, false)).isTrue();
    }

    @Test
    void shouldPersist_positionsWinOverAnErrorOnALaterFrame() {
        assertThat(TradeRepublicAdapter.shouldPersist(new java.math.BigDecimal("900"), false, true, true)).isTrue();
    }

    @Test
    void shouldPersist_anAccountThatNeverAnsweredAndHasNothing_isSkipped() {
        assertThat(TradeRepublicAdapter.shouldPersist(java.math.BigDecimal.ZERO, true, false, false)).isFalse();
    }

    // ─── untilAnsweredOrCapped: how long the WebSocket read may wait ───────────────────────

    /**
     * The case that stored no account at all: every ticker but one answered and kept
     * streaming deltas, the last one (an instrument TR never prices) stayed silent. Frames
     * never stopped, so only a cap on total time ends the read.
     */
    @Test
    void untilAnsweredOrCapped_aSubscriptionThatNeverAnswers_endsAtTheCap_whileOthersKeepTicking() {
        // A delta every 7s: frames at 7, 14, 21 and 28s, the next one would land after the cap.
        StepVerifier.withVirtualTime(() -> TradeRepublicAdapter.untilAnsweredOrCapped(
                Flux.interval(Duration.ofSeconds(7)), frame -> false, TradeRepublicAdapter.WS_WAIT_CAP))
            .expectSubscription()
            .thenAwait(TradeRepublicAdapter.WS_WAIT_CAP.minusSeconds(1))
            .expectNextCount(4)
            .expectNoEvent(Duration.ofMillis(999))
            .thenAwait(Duration.ofMillis(1))
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    /** The cap only helps if it ends the read before the hard timeout fails the whole sync. */
    @Test
    void wsWaitCap_endsTheReadBeforeTheHardTimeoutFailsTheSync() {
        assertThat(TradeRepublicAdapter.WS_WAIT_CAP).isLessThan(TradeRepublicAdapter.WS_HARD_TIMEOUT);
    }

    /**
     * The socket dropping mid-read used to end it like a timeout; the sync went on with what
     * had answered and the rest fell back to averageBuyIn. It must not fail the whole sync.
     */
    @Test
    void untilAnsweredOrCapped_aStreamThatErrorsMidRead_keepsTheFramesReceivedSoFar() {
        Flux<String> framesThenDrop = Flux.just("cash", "portfolio", "ticker")
            .concatWith(Flux.error(new IOException("Connection reset")));

        StepVerifier.create(TradeRepublicAdapter.untilAnsweredOrCapped(
                framesThenDrop, frame -> false, TradeRepublicAdapter.WS_WAIT_CAP))
            .expectNext("cash", "portfolio", "ticker")
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void untilAnsweredOrCapped_everythingAnswered_endsOnThatFrame_withoutWaitingForTheCap() {
        List<Long> frames = TradeRepublicAdapter
            .untilAnsweredOrCapped(Flux.interval(Duration.ofMillis(10)), frame -> frame == 2L, Duration.ofSeconds(30))
            .collectList()
            .block(Duration.ofSeconds(5));

        assertThat(frames).containsExactly(0L, 1L, 2L);
    }

    @Test
    void isBond_matchesTheExactTypeOnly_neverASubstring() {
        assertThat(TradeRepublicAdapter.isBond("bond")).isTrue();
        assertThat(TradeRepublicAdapter.isBond("stock")).isFalse();
        assertThat(TradeRepublicAdapter.isBond("bondEtf")).isFalse();
        assertThat(TradeRepublicAdapter.isBond(null)).isFalse();
    }

    private static final String TREASURY = "US91282CMK44";
    private static final String ETF = "IE00B4L5Y983";
    private static final String EUR_INSTRUMENT = "{\"exchangeIds\":[\"LSX\"],\"currencyId\":\"EUR\"}";

    private static String position(String isin, String extra) {
        return "{\"isin\":\"" + isin + "\",\"netSize\":\"500\",\"averageBuyIn\":\"0.8941\"" + extra + "}";
    }

    private static String portfolio(String bondCategoryPositions, String otherCategoryPositions) {
        return "{\"categories\":[{\"categoryType\":\"bond\",\"positions\":[" + bondCategoryPositions
            + "]},{\"categoryType\":\"etf\",\"positions\":[" + otherCategoryPositions + "]}]}";
    }

    @Test
    void fetchAccounts_scalesOnlyTheBondTickerByHundred_notTheEtfInAnotherCategory() {
        TradeRepublicAdapter adapter = adapterAgainstTr(
            portfolio(position(TREASURY, ""), position(ETF, "")), EUR_INSTRUMENT,
            Map.of(TREASURY + ".LSX", "98.25", ETF + ".LSX", "141.75"));

        TrAccountData securities = securities(adapter.fetchAccounts(sessionToken()));

        assertThat(securities.positions()).hasSize(2);
        assertThat(priceOf(securities, TREASURY)).isEqualByComparingTo("0.9825");
        assertThat(priceOf(securities, ETF)).isEqualByComparingTo("141.75");
        // 500 x 0.9825 + 500 x 141.75 + 100 cash
        assertThat(securities.balanceEur()).isEqualByComparingTo("71977.50");
    }

    @Test
    void fetchAccounts_aBondCategoryPosition_isScaled_evenWhenItsInstrumentTypeIsNotBond() {
        TradeRepublicAdapter adapter = adapterAgainstTr(
            portfolio(position(TREASURY, ",\"instrumentType\":\"other\""), position(ETF, "")),
            EUR_INSTRUMENT, Map.of(TREASURY + ".LSX", "98.25", ETF + ".LSX", "141.75"));

        TrAccountData securities = securities(adapter.fetchAccounts(sessionToken()));

        assertThat(priceOf(securities, TREASURY)).isEqualByComparingTo("0.9825");
    }

    @Test
    void fetchAccounts_aBondInstrumentTypeOutsideTheBondCategory_isScaled() {
        TradeRepublicAdapter adapter = adapterAgainstTr(
            portfolio("", position(TREASURY, ",\"instrumentType\":\"bond\"")),
            EUR_INSTRUMENT, Map.of(TREASURY + ".LSX", "98.25"));

        TrAccountData securities = securities(adapter.fetchAccounts(sessionToken()));

        assertThat(priceOf(securities, TREASURY)).isEqualByComparingTo("0.9825");
    }

    @Test
    void fetchAccounts_aBondWithANonEurNominal_leavesThePriceUnavailable() {
        TradeRepublicAdapter adapter = adapterAgainstTr(
            portfolio(position(TREASURY, ""), position(ETF, "")),
            "{\"exchangeIds\":[\"LSX\"],\"currencyId\":\"USD\"}",
            Map.of(TREASURY + ".LSX", "98.25"));

        TrAccountData securities = securities(adapter.fetchAccounts(sessionToken()));

        assertThat(priceOf(securities, TREASURY)).isNull();
    }

    @Test
    void fetchAccounts_aBondWithAnUnknownNominalCurrency_leavesThePriceUnavailable() {
        TradeRepublicAdapter adapter = adapterAgainstTr(
            portfolio(position(TREASURY, ""), ""), "{\"exchangeIds\":[\"LSX\"]}",
            Map.of(TREASURY + ".LSX", "98.25"));

        TrAccountData securities = securities(adapter.fetchAccounts(sessionToken()));

        assertThat(priceOf(securities, TREASURY)).isNull();
    }

    private static java.math.BigDecimal priceOf(TrAccountData account, String isin) {
        return account.positions().stream()
            .filter(position -> isin.equals(position.isin()))
            .findFirst().orElseThrow().currentPrice();
    }
}
