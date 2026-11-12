package com.picsou.adapter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarTestSupport;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.exception.SyncException;
import org.springframework.web.reactive.function.client.WebClient;
import com.picsou.adapter.TradeRepublicAdapter.TrAccountData;
import org.springframework.web.reactive.function.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
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
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class TradeRepublicAdapterTest {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(2);

    private DisposableServer server;

    @Test
    void productionConstructorIsExplicitSpringInjectionPoint() throws NoSuchMethodException {
        assertThat(TradeRepublicAdapter.class
            .getConstructor(SidecarWebClientFactory.class, ObjectMapper.class)
            .isAnnotationPresent(Autowired.class))
            .isTrue();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.disposeNow();
        }
    }

    // ─── auth: the sidecar's {"detail": CODE} contract ────────────────────────────────────

    @Test
    void initiateAuth_returnsTheFlowAndTheCountdown() {
        TradeRepublicAdapter adapter = adapterReturning(200,
            "{\"processId\":\"p1\",\"flow\":\"confirmInApp\",\"countdownInSeconds\":120}");

        TradeRepublicAdapter.TrInitiate result = adapter.initiateAuth("+336****0000", "1234");

        assertThat(result).isEqualTo(new TradeRepublicAdapter.TrInitiate("p1", "confirmInApp", 120));
    }

    @Test
    void initiateAuth_aBlankProcessIdIsAFormatChange() {
        TradeRepublicAdapter adapter = adapterReturning(200, "{\"processId\":\" \",\"flow\":\"otp\"}");

        assertThat(codeOf(catchThrowable(() -> adapter.initiateAuth("+336****0000", "1234"))))
            .isEqualTo("UPSTREAM_FORMAT_CHANGED");
    }

    @ParameterizedTest
    @CsvSource({
        "401, INVALID_CREDENTIALS, INVALID_CREDENTIALS",
        "429, RATE_LIMITED, RATE_LIMITED",
        "503, UPSTREAM_UNAVAILABLE, UPSTREAM_UNAVAILABLE",
        "500, INTERNAL_ERROR, INTERNAL_ERROR"
    })
    void initiateAuth_keepsTheCodeTheSidecarDeclares(int status, String detail, String expected) {
        TradeRepublicAdapter adapter = adapterReturning(status, "{\"detail\":\"" + detail + "\"}");

        assertThat(codeOf(catchThrowable(() -> adapter.initiateAuth("+336****0000", "1234")))).isEqualTo(expected);
    }

    @Test
    void initiateAuth_aBareUnauthorizedMeansTheCredentialsWereRejected() {
        TradeRepublicAdapter adapter = adapterReturning(401, "{}");

        assertThat(codeOf(catchThrowable(() -> adapter.initiateAuth("+336****0000", "1234"))))
            .isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void initiateAuth_aCodeTheBankNeverDeclaredIsUnavailable() {
        // CAPTCHA_BLOCKED exists, but Trade Republic's CHECK branch does not list it.
        TradeRepublicAdapter adapter = adapterReturning(502, "{\"detail\":\"CAPTCHA_BLOCKED\"}");

        assertThat(codeOf(catchThrowable(() -> adapter.initiateAuth("+336****0000", "1234"))))
            .isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    @Test
    void completeAuth_returnsTheSessionStateAndSendsAnExplicitNullTan() {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> request.receive().aggregate().asString().doOnNext(body::set)
                .then(response.header("Content-Type", "application/json")
                    .sendString(Mono.just("{\"sessionState\":\"{\\\"refresh\\\":\\\"r\\\"}\"}")).then()))
            .bindNow();
        TradeRepublicAdapter adapter = adapterFor(server, RESPONSE_TIMEOUT);

        String state = adapter.completeAuth("p1", null);

        assertThat(state).isEqualTo("{\"refresh\":\"r\"}");
        assertThat(body.get()).contains("\"processId\":\"p1\"").contains("\"tan\":null");
    }

    @ParameterizedTest
    @CsvSource({
        "408, APP_VALIDATION_TIMEOUT, APP_VALIDATION_TIMEOUT",
        "401, APP_VALIDATION_CANCELLED, APP_VALIDATION_CANCELLED",
        "401, INVALID_OTP, INVALID_OTP",
        "410, AUTH_ATTEMPT_EXPIRED, AUTH_ATTEMPT_EXPIRED",
        "502, UPSTREAM_FORMAT_CHANGED, UPSTREAM_FORMAT_CHANGED"
    })
    void completeAuth_keepsTheCodeTheSidecarDeclares(int status, String detail, String expected) {
        TradeRepublicAdapter adapter = adapterReturning(status, "{\"detail\":\"" + detail + "\"}");

        assertThat(codeOf(catchThrowable(() -> adapter.completeAuth("p1", "1234")))).isEqualTo(expected);
    }

    @Test
    void completeAuth_aBareGoneMeansTheAttemptExpired() {
        TradeRepublicAdapter adapter = adapterReturning(410, "{}");

        assertThat(codeOf(catchThrowable(() -> adapter.completeAuth("p1", null)))).isEqualTo("AUTH_ATTEMPT_EXPIRED");
    }

    @Test
    void completeAuth_aBlankStateIsAFormatChange() {
        TradeRepublicAdapter adapter = adapterReturning(200, "{\"sessionState\":\"\"}");

        assertThat(codeOf(catchThrowable(() -> adapter.completeAuth("p1", null)))).isEqualTo("UPSTREAM_FORMAT_CHANGED");
    }

    @Test
    void completeAuth_waitsLongerThanTheAuthTimeout() {
        server = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> Mono.delay(Duration.ofMillis(300))
                .then(response.header("Content-Type", "application/json")
                    .sendString(Mono.just("{\"sessionState\":\"state\"}")).then()))
            .bindNow();
        String baseUrl = "http://127.0.0.1:" + server.port();
        TradeRepublicAdapter adapter = new TradeRepublicAdapter(new ObjectMapper(),
            WebClient.builder().baseUrl(baseUrl).build(),
            Duration.ofMillis(50), Duration.ofSeconds(2), Duration.ofMillis(50), URI.create("ws://127.0.0.1:1/"));

        assertThat(adapter.completeAuth("p1", null)).isEqualTo("state");
        assertThat(codeOf(catchThrowable(() -> adapter.initiateAuth("+336****0000", "1234"))))
            .isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    // ─── openSession ──────────────────────────────────────────────────────────────────────

    @Test
    void openSession_returnsTheCookieValueAndTheClaims() {
        TradeRepublicAdapter adapter = adapterReturning(200,
            "{\"sessionToken\":\"cookie-value\",\"claims\":{\"act\":{\"acc\":{}}}}");

        TradeRepublicAdapter.TrSession session = adapter.openSession("{\"refresh\":\"r\"}");

        assertThat(session.sessionToken()).isEqualTo("cookie-value");
        assertThat(session.claims().path("act").isObject()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "401, SESSION_EXPIRED, SESSION_EXPIRED",
        "422, INVALID_SESSION_STATE, INVALID_SESSION_STATE",
        "429, RATE_LIMITED, RATE_LIMITED",
        "502, UPSTREAM_UNAVAILABLE, UPSTREAM_UNAVAILABLE"
    })
    void openSession_keepsTheCodeTheSidecarDeclares(int status, String detail, String expected) {
        TradeRepublicAdapter adapter = adapterReturning(status, "{\"detail\":\"" + detail + "\"}");

        assertThat(codeOf(catchThrowable(() -> adapter.openSession("state")))).isEqualTo(expected);
    }

    @Test
    void openSession_aBareUnauthorizedMeansTheStoredSessionDied() {
        TradeRepublicAdapter adapter = adapterReturning(401, "{}");

        assertThat(codeOf(catchThrowable(() -> adapter.openSession("state")))).isEqualTo("SESSION_EXPIRED");
    }

    @Test
    void openSession_aMissingTokenIsAFormatChange_notAnExpiredSession() {
        TradeRepublicAdapter adapter = adapterReturning(200, "{\"claims\":{}}");

        assertThat(codeOf(catchThrowable(() -> adapter.openSession("state")))).isEqualTo("UPSTREAM_FORMAT_CHANGED");
    }

    @Test
    void openSession_aTimeoutIsUnavailable_notAnExpiredSession() {
        server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> Mono.never()).bindNow();
        TradeRepublicAdapter adapter = adapterFor(server, Duration.ofMillis(25));

        assertThat(codeOf(catchThrowable(() -> adapter.openSession("state")))).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    // ─── fetchTransactionsCsv ─────────────────────────────────────────────────────────────

    @Test
    void fetchTransactionsCsv_postsTheWindowAndReturnsTheBodyUntouched() {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        String csv = "datetime,date\n2026-10-01T10:00:00Z,2026-10-01\n";
        server = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> request.receive().aggregate().asString().doOnNext(body::set)
                .then(Mono.fromRunnable(() -> path.set(request.path())))
                .then(response.header("Content-Type", "text/csv").sendString(Mono.just(csv)).then()))
            .bindNow();
        TradeRepublicAdapter adapter = adapterFor(server, RESPONSE_TIMEOUT);

        String result = adapter.fetchTransactionsCsv("state", LocalDate.of(2023, 11, 4), LocalDate.of(2026, 11, 4));

        assertThat(result).isEqualTo(csv);
        assertThat(path.get()).endsWith("transactions");
        assertThat(body.get()).contains("\"sessionState\":\"state\"")
            .contains("\"from\":\"2023-11-04\"").contains("\"to\":\"2026-11-04\"");
    }

    @Test
    void fetchTransactionsCsv_returnsAnExportLargerThanTheDefault256KibCodecLimit() {
        // A real 3-year export of 721 rows was 155 KB: about 1,200 rows cross Spring's 256 KiB default.
        String row = "2026-10-01T10:00:00Z,2026-10-01,DEFAULT,CASH,CARD_PAYMENT," + "X".repeat(180) + "\n";
        String csv = "datetime,date\n" + row.repeat(600 * 1024 / row.length());
        assertThat(csv.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(256 * 1024);
        server = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> request.receive().then(
                response.header("Content-Type", "text/csv").sendString(Mono.just(csv)).then()))
            .bindNow();
        TradeRepublicAdapter adapter = new TradeRepublicAdapter(
            SidecarTestSupport.factory("tr-auth", "http://127.0.0.1:" + server.port()), new ObjectMapper());

        String result = adapter.fetchTransactionsCsv("state", LocalDate.of(2023, 11, 4), LocalDate.of(2026, 11, 4));

        assertThat(result).isEqualTo(csv);
    }

    @ParameterizedTest
    @CsvSource({
        "401, SESSION_EXPIRED, SESSION_EXPIRED",
        "422, INVALID_SESSION_STATE, INVALID_SESSION_STATE",
        "422, INVALID_DATA, INVALID_DATA",
        "429, RATE_LIMITED, RATE_LIMITED",
        "502, UPSTREAM_UNAVAILABLE, UPSTREAM_UNAVAILABLE"
    })
    void fetchTransactionsCsv_keepsTheCodeTheSidecarDeclares(int status, String detail, String expected) {
        TradeRepublicAdapter adapter = adapterReturning(status, "{\"detail\":\"" + detail + "\"}");

        assertThat(codeOf(catchThrowable(() ->
            adapter.fetchTransactionsCsv("state", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1)))))
            .isEqualTo(expected);
    }

    private static String codeOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(SyncException.class);
        return ((SyncException) thrown).getCode();
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

    // ─── fetchAccounts: what reaches the log ───────────────────────────────────────────────

    /**
     * Whoever operates the instance reads the log. It must not carry what the bank's frames
     * carry: the cash account number, a balance, a quantity, a buy-in price, an account value.
     */
    @Test
    void fetchAccounts_logsNoAccountNumberBalanceOrPosition() {
        TradeRepublicAdapter adapter = adapterAgainstTr(
            BTC, "{\"exchangeIds\":[\"BHS\"]}", Map.of(BTC + ".BHS", "60000"));
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TradeRepublicAdapter.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);

        try {
            adapter.fetchAccounts(sessionToken());
        } finally {
            logger.detachAppender(logs);
        }

        // TRACE is the opt-in level for raw frames; everything an instance logs by default is checked.
        List<String> lines = logs.list.stream()
            .filter(event -> event.getLevel().isGreaterOrEqual(Level.DEBUG))
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
        assertThat(lines).anyMatch(line -> line.contains("1 positions"));
        assertThat(lines).anyMatch(line -> line.matches("TR WS <-- \\d+ A \\(\\d+ chars\\)"));
        assertThat(lines).noneMatch(line -> line.contains("CASH1"));
        assertThat(lines).noneMatch(line -> line.contains("\"amount\""));
        assertThat(lines).noneMatch(line -> line.contains("netSize") || line.contains("averageBuyIn"));
        assertThat(lines).noneMatch(line -> line.contains("30000"));
    }

    /**
     * A TR WebSocket holding one position of 0.5 units bought at 30 000. It answers the
     * instrument subscription with {@code instrumentAnswer} and prices only the ticker ids in
     * {@code pricedTickers}; any other ticker id gets the FORBIDDEN error TR sends for a venue
     * that does not list the instrument.
     */
    private TradeRepublicAdapter adapterAgainstTr(
            String isin, String instrumentAnswer, Map<String, String> pricedTickers) {
        return adapterAgainstPortfolio("{\"categories\":[{\"positions\":[{\"isin\":\"" + isin
            + "\",\"netSize\":\"0.5\",\"averageBuyIn\":\"30000\"}]}]}", instrumentAnswer, pricedTickers);
    }

    @Test
    void fetchAccounts_authenticatesTheHandshakeWithTheSessionCookie() {
        AtomicReference<String> cookie = new AtomicReference<>();
        TradeRepublicAdapter adapter = adapterAgainstPortfolio(
            "{\"categories\":[]}", "{}", Map.of(), cookie);

        adapter.fetchAccounts("cookie-value", claimsWithOneSecuritiesAccount());

        assertThat(cookie.get()).isEqualTo("tr_session=cookie-value");
    }

    @Test
    void fetchAccounts_readsTheSecuritiesAccountsFromTheClaims_notFromAnOpaqueToken() {
        TradeRepublicAdapter adapter = adapterAgainstTr(BTC, "{\"exchangeIds\":[\"BHS\"]}", Map.of(BTC + ".BHS", "60000"));

        List<TrAccountData> accounts = adapter.fetchAccounts("opaque-cookie-value", claimsWithOneSecuritiesAccount());

        assertThat(securities(accounts).positions()).singleElement()
            .satisfies(position -> assertThat(position.currentPrice()).isEqualByComparingTo("60000"));
    }

    @Test
    void fetchAccounts_fallsBackToTheTokenPayloadWhenTheClaimsCarryNoAccounts() {
        TradeRepublicAdapter adapter = adapterAgainstTr(BTC, "{\"exchangeIds\":[\"BHS\"]}", Map.of(BTC + ".BHS", "60000"));

        List<TrAccountData> accounts = adapter.fetchAccounts(sessionToken(), new ObjectMapper().createObjectNode());

        assertThat(securities(accounts).positions()).hasSize(1);
    }

    private static JsonNode claimsWithOneSecuritiesAccount() {
        try {
            return new ObjectMapper().readTree(
                "{\"act\":{\"acc\":{\"owner\":{\"default\":{\"sec\":[\"SEC1\"],\"cash\":[\"CASH1\"]}}}}}");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Same server, with the {@code compactPortfolioByType} answer given as-is. */
    private TradeRepublicAdapter adapterAgainstPortfolio(
            String portfolioAnswer, String instrumentAnswer, Map<String, String> pricedTickers) {
        return adapterAgainstPortfolio(portfolioAnswer, instrumentAnswer, pricedTickers, new AtomicReference<>());
    }

    private TradeRepublicAdapter adapterAgainstPortfolio(
            String portfolioAnswer, String instrumentAnswer, Map<String, String> pricedTickers,
            AtomicReference<String> handshakeCookie) {
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
            .route(routes -> routes.ws("/", (in, out) -> {
                handshakeCookie.set(in.headers().get("Cookie"));
                return out.sendString(in.receive().asString().mapNotNull(answer));
            }))
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
        TradeRepublicAdapter adapter = adapterAgainstPortfolio(
            portfolio(position(TREASURY, ""), position(ETF, "")), EUR_INSTRUMENT,
            Map.of(TREASURY + ".LSX", "98.25", ETF + ".LSX", "141.75"));

        TrAccountData securities = securities(adapter.fetchAccounts(sessionToken()));

        assertThat(securities.positions()).hasSize(2);
        assertThat(priceOf(securities, TREASURY)).isEqualByComparingTo("0.9825");
        assertThat(priceOf(securities, ETF)).isEqualByComparingTo("141.75");
        // The securities account has no cash pocket.
        assertThat(securities.balanceEur()).isEqualByComparingTo("71366.25");
    }

    @Test
    void fetchAccounts_aBondCategoryPosition_isScaled_evenWhenItsInstrumentTypeIsNotBond() {
        TradeRepublicAdapter adapter = adapterAgainstPortfolio(
            portfolio(position(TREASURY, ",\"instrumentType\":\"other\""), position(ETF, "")),
            EUR_INSTRUMENT, Map.of(TREASURY + ".LSX", "98.25", ETF + ".LSX", "141.75"));

        TrAccountData securities = securities(adapter.fetchAccounts(sessionToken()));

        assertThat(priceOf(securities, TREASURY)).isEqualByComparingTo("0.9825");
    }

    @Test
    void fetchAccounts_aBondInstrumentTypeOutsideTheBondCategory_isScaled() {
        TradeRepublicAdapter adapter = adapterAgainstPortfolio(
            portfolio("", position(TREASURY, ",\"instrumentType\":\"bond\"")),
            EUR_INSTRUMENT, Map.of(TREASURY + ".LSX", "98.25"));

        TrAccountData securities = securities(adapter.fetchAccounts(sessionToken()));

        assertThat(priceOf(securities, TREASURY)).isEqualByComparingTo("0.9825");
    }

    @Test
    void fetchAccounts_aBondWithANonEurNominal_leavesThePriceUnavailable() {
        TradeRepublicAdapter adapter = adapterAgainstPortfolio(
            portfolio(position(TREASURY, ""), ""),
            "{\"exchangeIds\":[\"LSX\"],\"currencyId\":\"USD\"}",
            Map.of(TREASURY + ".LSX", "98.25"));

        TrAccountData securities = securities(adapter.fetchAccounts(sessionToken()));

        assertThat(priceOf(securities, TREASURY)).isNull();
        assertThat(securities.balanceEur()).isEqualByComparingTo("447.05");
    }

    @Test
    void fetchAccounts_aBondWithAnUnknownNominalCurrency_leavesThePriceUnavailable() {
        TradeRepublicAdapter adapter = adapterAgainstPortfolio(
            portfolio(position(TREASURY, ""), ""), "{\"exchangeIds\":[\"LSX\"]}",
            Map.of(TREASURY + ".LSX", "98.25"));

        TrAccountData securities = securities(adapter.fetchAccounts(sessionToken()));

        assertThat(priceOf(securities, TREASURY)).isNull();
        assertThat(securities.balanceEur()).isEqualByComparingTo("447.05");
    }

    private static java.math.BigDecimal priceOf(TrAccountData account, String isin) {
        return account.positions().stream()
            .filter(position -> isin.equals(position.isin()))
            .findFirst().orElseThrow().currentPrice();
    }
}
