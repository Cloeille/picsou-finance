package com.picsou.adapter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.exception.SyncException;
import com.picsou.port.CaisseEpargneErrorCode;
import com.picsou.port.CaisseEpargnePort;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The login half of the sidecar contract: {@code POST /initiate} and {@code POST /complete}.
 * One attempt only: a wrong password burns a bank attempt, so nothing here may replay a call.
 */
class CaisseEpargneAdapterAuthTest {

    private static final String CUSTOMER_ID = "12345678";
    private static final List<Integer> POSITIONS = List.of(7, 3, 0, 9, 4, 1);
    private static final String POSITIONS_JSON = "[7,3,0,9,4,1]";
    private static final String IMAGE = "data:image/png;base64,iVBORw0KGgo=";
    private static final String INITIATE_OK = initiateJson("proc-1", 5, 90);
    private static final String KEYPAD_OK = "{\"processId\":\"proc-1\",\"status\":\"SECURPASS_PENDING\"}";

    private static String initiateJson(String processId, int columns, int expiresIn) {
        String images = String.join(",", java.util.Collections.nCopies(10, "\"" + IMAGE + "\""));
        return "{\"processId\":\"" + processId + "\",\"keypad\":{\"images\":[" + images
            + "],\"columns\":" + columns + "},\"expiresInSeconds\":" + expiresIn + "}";
    }

    private record Call(String path, String body) {}

    // -- initiate: request and result ---------------------------------------

    @Test
    void initiateAuth_postsOnlyTheIdentifierAndMapsTheKeypad() {
        List<Call> calls = new ArrayList<>();
        CaisseEpargneAdapter adapter = recording(calls, HttpStatus.OK, INITIATE_OK);

        CaisseEpargnePort.InitiateResult result = adapter.initiateAuth(CUSTOMER_ID);

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.path()).isEqualTo("/initiate");
            assertThat(call.body()).isEqualTo("{\"customerId\":\"" + CUSTOMER_ID + "\"}");
        });
        assertThat(result.processId()).isEqualTo("proc-1");
        assertThat(result.keypad().columns()).isEqualTo(5);
        assertThat(result.keypad().images()).hasSize(10).allMatch(IMAGE::equals);
        assertThat(result.expiresInSeconds()).isEqualTo(90);
    }

    @Test
    void theInitiateBodyNeverPrintsTheIdentifier() throws Exception {
        Class<?> body = java.util.Arrays.stream(CaisseEpargneAdapter.class.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("InitiateBody")).findFirst().orElseThrow();
        var ctor = body.getDeclaredConstructors()[0];
        ctor.setAccessible(true);

        assertThat(ctor.newInstance(CUSTOMER_ID).toString()).doesNotContain(CUSTOMER_ID);
    }

    @Test
    void initiateAuth_mapsAnEmptyBodyToUnavailable() {
        CaisseEpargneAdapter adapter = returning(HttpStatus.OK, "");

        assertThatThrownBy(() -> adapter.initiateAuth(CUSTOMER_ID))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE.name()));
    }

    // -- initiate: error mapping --------------------------------------------

    @Test
    void initiateAuth_mapsEverySidecarCodeToItsStableCode() {
        record Case(HttpStatus status, String detail, CaisseEpargneErrorCode code) {}
        for (Case c : List.of(
            new Case(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", CaisseEpargneErrorCode.INVALID_CREDENTIALS),
            new Case(HttpStatus.CONFLICT, "KEYPAD_CHANGED", CaisseEpargneErrorCode.KEYPAD_CHANGED),
            new Case(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE", CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE),
            new Case(HttpStatus.BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED", CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED))) {
            CaisseEpargneAdapter adapter = returning(c.status(), "{\"detail\":\"" + c.detail() + "\"}");

            assertThatThrownBy(() -> adapter.initiateAuth(CUSTOMER_ID))
                .as(c.detail())
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(c.code().name()));
        }
    }

    @Test
    void initiateAuth_mapsABareStatusWhenTheSidecarSendsNoDetail() {
        record Case(HttpStatus status, CaisseEpargneErrorCode code) {}
        for (Case c : List.of(
            new Case(HttpStatus.UNAUTHORIZED, CaisseEpargneErrorCode.INVALID_CREDENTIALS),
            new Case(HttpStatus.CONFLICT, CaisseEpargneErrorCode.KEYPAD_CHANGED),
            new Case(HttpStatus.BAD_GATEWAY, CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE),
            new Case(HttpStatus.SERVICE_UNAVAILABLE, CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE),
            new Case(HttpStatus.INTERNAL_SERVER_ERROR, CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE))) {
            CaisseEpargneAdapter adapter = returning(c.status(), "oops");

            assertThatThrownBy(() -> adapter.initiateAuth(CUSTOMER_ID))
                .as(c.status().toString())
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(c.code().name()));
        }
    }

    @Test
    void initiateAuth_mapsTooManyPendingToARateLimitedMessage() {
        for (String body : List.of("{\"detail\":\"TOO_MANY_PENDING\"}", "")) {
            CaisseEpargneAdapter adapter = returning(HttpStatus.TOO_MANY_REQUESTS, body);

            assertThatThrownBy(() -> adapter.initiateAuth(CUSTOMER_ID))
                .isInstanceOfSatisfying(SyncException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE.name());
                    assertThat(error.getMessage()).containsIgnoringCase("too many");
                });
        }
    }

    @Test
    void initiateAuth_neverCarriesTheIdentifierInAnErrorOrALog() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        Level previous = root.getLevel();
        root.setLevel(Level.ALL);
        try {
            for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.CONFLICT,
                HttpStatus.TOO_MANY_REQUESTS, HttpStatus.BAD_GATEWAY, HttpStatus.OK)) {
                CaisseEpargneAdapter adapter = returning(status, "not json " + status.value());
                Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> adapter.initiateAuth(CUSTOMER_ID));
                for (Throwable t = thrown; t != null; t = t.getCause()) {
                    assertThat(String.valueOf(t.getMessage())).doesNotContain(CUSTOMER_ID);
                    assertThat(t.toString()).doesNotContain(CUSTOMER_ID);
                }
            }
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
        }
        assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(CUSTOMER_ID);
            assertThat(String.valueOf(event.getThrowableProxy() == null ? ""
                : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .doesNotContain(CUSTOMER_ID);
        });
    }

    // -- initiate: timeout and no retry -------------------------------------

    @Test
    void theAuthCallsHaveTheirOwnTimeouts() {
        assertThat(CaisseEpargneAdapter.INITIATE_TIMEOUT).isEqualTo(Duration.ofSeconds(90));
        // longer than the sidecar's 150 s wait for the human to approve on the phone
        assertThat(CaisseEpargneAdapter.COMPLETE_TIMEOUT).isEqualTo(Duration.ofSeconds(170));
    }

    @Test
    void initiateAuth_usesTheInitiateTimeoutAndSurfacesARetryableUnavailable() {
        ExchangeFunction neverResponds = request -> Mono.never();
        CaisseEpargneAdapter adapter = new CaisseEpargneAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(), new ObjectMapper(),
            Duration.ofSeconds(60), Duration.ofSeconds(60),
            Duration.ofMillis(20), Duration.ofSeconds(60));

        assertThatThrownBy(() -> adapter.initiateAuth(CUSTOMER_ID))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE.name());
                assertThat(error.getMessage()).contains("too long").doesNotContain(CUSTOMER_ID);
            });
    }

    @Test
    void completeAuth_usesTheCompleteTimeoutNotTheInitiateOne() {
        ExchangeFunction neverResponds = request -> Mono.never();
        CaisseEpargneAdapter adapter = new CaisseEpargneAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(), new ObjectMapper(),
            Duration.ofSeconds(60), Duration.ofSeconds(60),
            Duration.ofSeconds(60), Duration.ofMillis(20));

        assertThatThrownBy(() -> adapter.completeAuth("proc-1"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE.name()));
    }

    @Test
    void initiateAuth_isNeverRetriedWhateverTheFailure() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.CONFLICT,
            HttpStatus.TOO_MANY_REQUESTS, HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE,
            HttpStatus.INTERNAL_SERVER_ERROR)) {
            AtomicInteger requests = new AtomicInteger();
            CaisseEpargneAdapter adapter = adapterOf(request -> {
                requests.incrementAndGet();
                return Mono.just(ClientResponse.create(status).build());
            });

            assertThatThrownBy(() -> adapter.initiateAuth(CUSTOMER_ID)).isInstanceOf(SyncException.class);

            assertThat(requests).as(status.toString()).hasValue(1);
        }
    }

    @Test
    void initiateAuth_isNeverRetriedAfterATimeout() {
        AtomicInteger requests = new AtomicInteger();
        CaisseEpargneAdapter adapter = new CaisseEpargneAdapter(
            WebClient.builder().exchangeFunction(request -> {
                requests.incrementAndGet();
                return Mono.never();
            }).build(), new ObjectMapper(),
            Duration.ofSeconds(60), Duration.ofSeconds(60),
            Duration.ofMillis(20), Duration.ofMillis(20));

        assertThatThrownBy(() -> adapter.initiateAuth(CUSTOMER_ID)).isInstanceOf(SyncException.class);
        assertThatThrownBy(() -> adapter.completeAuth("proc-1")).isInstanceOf(SyncException.class);

        assertThat(requests).hasValue(2);
    }

    @Test
    void theProductionClientNeverReplaysAnAuthCallOnTheWire() throws Exception {
        AtomicInteger initiateHits = new AtomicInteger();
        AtomicInteger completeHits = new AtomicInteger();
        AtomicInteger keypadHits = new AtomicInteger();
        for (int status : new int[] {401, 409, 429, 502, 503}) {
            initiateHits.set(0);
            completeHits.set(0);
            keypadHits.set(0);
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/initiate", exchange -> {
                initiateHits.incrementAndGet();
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.createContext("/complete", exchange -> {
                completeHits.incrementAndGet();
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.createContext("/keypad", exchange -> {
                keypadHits.incrementAndGet();
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
            try {
                CaisseEpargneAdapter adapter = new CaisseEpargneAdapter(
                    new SidecarWebClientFactory("test-key"),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    new ObjectMapper());

                assertThatThrownBy(() -> adapter.initiateAuth(CUSTOMER_ID)).isInstanceOf(SyncException.class);
                assertThatThrownBy(() -> adapter.submitKeypad("proc-1", POSITIONS)).isInstanceOf(SyncException.class);
                assertThatThrownBy(() -> adapter.completeAuth("proc-1")).isInstanceOf(SyncException.class);

                assertThat(keypadHits).as("keypad " + status).hasValue(1);
                assertThat(initiateHits).as("initiate " + status).hasValue(1);
                assertThat(completeHits).as("complete " + status).hasValue(1);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void theConnectorDisablesReactorNettyRetry() {
        // reactor-netty replays a request once when a pooled connection is reset before the
        // request headers went out. The wire cannot show it (the server never saw the first
        // attempt), so the flag itself is asserted: no hidden second attempt on the bank.
        assertThat(CaisseEpargneAdapter.httpClient().configuration().isRetryDisabled()).isTrue();
    }

    @Test
    void initiateAuth_isNotReplayedWhenTheConnectionClosesWithoutAnAnswer() throws Exception {
        // reactor-netty replays a request once when a pooled connection closes before any
        // response. For /auth/initiate that is a second password attempt on the bank.
        AtomicInteger initiateHits = new AtomicInteger();
        AtomicInteger warmups = new AtomicInteger();
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        java.net.Socket socket = server.accept();
                        new Thread(() -> serveUntilInitiate(socket, initiateHits, warmups)).start();
                    }
                } catch (java.io.IOException ignored) {
                    // server closed
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            CaisseEpargneAdapter adapter = new CaisseEpargneAdapter(
                new SidecarWebClientFactory("test-key"),
                "http://127.0.0.1:" + server.getLocalPort(),
                new ObjectMapper());

            // Warm the pool: the connection is kept alive and reused by the next call.
            assertThatThrownBy(() -> adapter.completeAuth("warmup")).isInstanceOf(SyncException.class);
            assertThat(warmups).hasValue(1);

            assertThatThrownBy(() -> adapter.initiateAuth(CUSTOMER_ID)).isInstanceOf(SyncException.class);
            Thread.sleep(300); // a replay would arrive within this window

            assertThat(initiateHits).hasValue(1);
        }
    }

    /** Answers /complete with a keep-alive 502; reads /initiate then closes with no answer. */
    private static void serveUntilInitiate(java.net.Socket socket, AtomicInteger initiateHits, AtomicInteger warmups) {
        try (socket) {
            java.io.InputStream in = socket.getInputStream();
            while (true) {
                StringBuilder head = new StringBuilder();
                int previous;
                int current = 0;
                int newlines = 0;
                while (newlines < 4) {
                    previous = current;
                    current = in.read();
                    if (current < 0) {
                        return;
                    }
                    head.append((char) current);
                    newlines = (current == '\r' || current == '\n') ? newlines + 1 : 0;
                }
                String text = head.toString();
                int length = 0;
                for (String line : text.split("\r\n")) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        length = Integer.parseInt(line.substring(15).trim());
                    }
                }
                in.readNBytes(length);
                if (text.startsWith("POST /initiate")) {
                    initiateHits.incrementAndGet();
                    return; // close the socket without answering
                }
                warmups.incrementAndGet();
                socket.getOutputStream().write(
                    "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n".getBytes());
                socket.getOutputStream().flush();
            }
        } catch (java.io.IOException ignored) {
            // peer gone
        }
    }

    // -- keypad ---------------------------------------------------------------

    @Test
    void submitKeypad_postsTheProcessIdAndThePositionsToKeypad() {
        List<Call> calls = new ArrayList<>();
        CaisseEpargneAdapter adapter = recording(calls, HttpStatus.OK, KEYPAD_OK);

        adapter.submitKeypad("proc-1", POSITIONS);

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.path()).isEqualTo("/keypad");
            assertThat(call.body()).isEqualTo(
                "{\"processId\":\"proc-1\",\"positions\":" + POSITIONS_JSON + "}");
        });
    }

    @Test
    void submitKeypad_refusesAnAnswerThatIsNotSecurPassPending() {
        for (String body : List.of("{\"processId\":\"proc-1\",\"status\":\"DONE\"}",
            "{\"processId\":\"proc-1\"}", "")) {
            CaisseEpargneAdapter adapter = returning(HttpStatus.OK, body);

            assertThatThrownBy(() -> adapter.submitKeypad("proc-1", POSITIONS))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isIn(
                        CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED.name(),
                        CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE.name()));
        }
        assertThatThrownBy(() -> returning(HttpStatus.OK, "{\"processId\":\"proc-1\",\"status\":\"DONE\"}")
            .submitKeypad("proc-1", POSITIONS))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED.name()));
    }

    @Test
    void submitKeypad_mapsEverySidecarCodeToItsStableCode() {
        record Case(HttpStatus status, String detail, CaisseEpargneErrorCode code) {}
        for (Case c : List.of(
            new Case(HttpStatus.CONFLICT, "KEYPAD_CHANGED", CaisseEpargneErrorCode.KEYPAD_CHANGED),
            new Case(HttpStatus.REQUEST_TIMEOUT, "KEYPAD_EXPIRED", CaisseEpargneErrorCode.KEYPAD_EXPIRED),
            new Case(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_POSITIONS", CaisseEpargneErrorCode.INVALID_POSITIONS),
            new Case(HttpStatus.GONE, "AUTH_ATTEMPT_EXPIRED", CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED),
            new Case(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", CaisseEpargneErrorCode.INVALID_CREDENTIALS),
            new Case(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE", CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE),
            new Case(HttpStatus.BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED", CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED))) {
            CaisseEpargneAdapter adapter = returning(c.status(), "{\"detail\":\"" + c.detail() + "\"}");

            assertThatThrownBy(() -> adapter.submitKeypad("proc-1", POSITIONS))
                .as(c.detail())
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(c.code().name()));
        }
    }

    @Test
    void submitKeypad_mapsABareStatusWhenTheSidecarSendsNoDetail() {
        record Case(HttpStatus status, CaisseEpargneErrorCode code) {}
        for (Case c : List.of(
            new Case(HttpStatus.CONFLICT, CaisseEpargneErrorCode.KEYPAD_CHANGED),
            new Case(HttpStatus.REQUEST_TIMEOUT, CaisseEpargneErrorCode.KEYPAD_EXPIRED),
            new Case(HttpStatus.UNPROCESSABLE_ENTITY, CaisseEpargneErrorCode.INVALID_POSITIONS),
            new Case(HttpStatus.GONE, CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED),
            new Case(HttpStatus.UNAUTHORIZED, CaisseEpargneErrorCode.INVALID_CREDENTIALS),
            new Case(HttpStatus.BAD_GATEWAY, CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE))) {
            CaisseEpargneAdapter adapter = returning(c.status(), "oops");

            assertThatThrownBy(() -> adapter.submitKeypad("proc-1", POSITIONS))
                .as(c.status().toString())
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(c.code().name()));
        }
    }

    @Test
    void initiateStillMapsA408AsTheSecurPassTimeout() {
        // 408 means "keypad expired" only on /keypad; on /initiate and /complete it is unchanged.
        assertThatThrownBy(() -> returning(HttpStatus.REQUEST_TIMEOUT, "").initiateAuth(CUSTOMER_ID))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.APP_VALIDATION_TIMEOUT.name()));
        assertThatThrownBy(() -> returning(HttpStatus.REQUEST_TIMEOUT, "").completeAuth("proc-1"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.APP_VALIDATION_TIMEOUT.name()));
    }

    @Test
    void submitKeypad_hasA70SecondTimeoutAndSurfacesARetryableUnavailable() {
        assertThat(CaisseEpargneAdapter.KEYPAD_TIMEOUT).isEqualTo(Duration.ofSeconds(70));
        CaisseEpargneAdapter adapter = new CaisseEpargneAdapter(
            WebClient.builder().exchangeFunction(request -> Mono.never()).build(), new ObjectMapper(),
            Duration.ofSeconds(60), Duration.ofSeconds(60),
            Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofMillis(20));

        assertThatThrownBy(() -> adapter.submitKeypad("proc-1", POSITIONS))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE.name());
                assertThat(error.getMessage()).contains("too long");
            });
    }

    @Test
    void submitKeypad_isNeverRetriedWhateverTheFailure() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.REQUEST_TIMEOUT,
            HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.BAD_GATEWAY,
            HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.INTERNAL_SERVER_ERROR)) {
            AtomicInteger requests = new AtomicInteger();
            CaisseEpargneAdapter adapter = adapterOf(request -> {
                requests.incrementAndGet();
                return Mono.just(ClientResponse.create(status).build());
            });

            assertThatThrownBy(() -> adapter.submitKeypad("proc-1", POSITIONS)).isInstanceOf(SyncException.class);

            assertThat(requests).as(status.toString()).hasValue(1);
        }
    }

    @Test
    void submitKeypad_isNeverRetriedAfterATimeout() {
        AtomicInteger requests = new AtomicInteger();
        CaisseEpargneAdapter adapter = new CaisseEpargneAdapter(
            WebClient.builder().exchangeFunction(request -> {
                requests.incrementAndGet();
                return Mono.never();
            }).build(), new ObjectMapper(),
            Duration.ofSeconds(60), Duration.ofSeconds(60),
            Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofMillis(20));

        assertThatThrownBy(() -> adapter.submitKeypad("proc-1", POSITIONS)).isInstanceOf(SyncException.class);

        assertThat(requests).hasValue(1);
    }

    @Test
    void submitKeypad_neverCarriesThePositionsInAnErrorOrALog() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        Level previous = root.getLevel();
        root.setLevel(Level.ALL);
        try {
            for (HttpStatus status : List.of(HttpStatus.CONFLICT, HttpStatus.REQUEST_TIMEOUT,
                HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.BAD_GATEWAY, HttpStatus.OK)) {
                CaisseEpargneAdapter adapter = returning(status, "not json " + status.value());
                Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> adapter.submitKeypad("proc-1", POSITIONS));
                for (Throwable t = thrown; t != null; t = t.getCause()) {
                    assertThat(String.valueOf(t.getMessage())).doesNotContain(POSITIONS_JSON).doesNotContain("[7, 3");
                    assertThat(t.toString()).doesNotContain(POSITIONS_JSON).doesNotContain("[7, 3");
                }
            }
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
        }
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(POSITIONS_JSON).doesNotContain("[7, 3");
            assertThat(String.valueOf(event.getThrowableProxy() == null ? ""
                : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .doesNotContain(POSITIONS_JSON).doesNotContain("[7, 3");
        });
    }

    @Test
    void theKeypadBodyNeverPrintsThePositions() throws Exception {
        Class<?> body = java.util.Arrays.stream(CaisseEpargneAdapter.class.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("KeypadBody")).findFirst().orElseThrow();
        var ctor = body.getDeclaredConstructors()[0];
        ctor.setAccessible(true);

        assertThat(ctor.newInstance("proc-1", POSITIONS).toString())
            .doesNotContain("7, 3").doesNotContain("positions=[");
    }

    // -- complete -----------------------------------------------------------

    @Test
    void completeAuth_postsTheProcessIdAndReturnsTheSessionState() {
        List<Call> calls = new ArrayList<>();
        CaisseEpargneAdapter adapter = recording(calls, HttpStatus.OK, "{\"sessionState\":\"{\\\"cookies\\\":[]}\"}");

        String state = adapter.completeAuth("proc-1");

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.path()).isEqualTo("/complete");
            assertThat(call.body()).contains("\"processId\":\"proc-1\"");
        });
        assertThat(state).isEqualTo("{\"cookies\":[]}");
    }

    @Test
    void completeAuth_mapsEverySidecarCodeToItsStableCode() {
        record Case(HttpStatus status, String detail, CaisseEpargneErrorCode code) {}
        for (Case c : List.of(
            new Case(HttpStatus.GONE, "AUTH_ATTEMPT_EXPIRED", CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED),
            new Case(HttpStatus.REQUEST_TIMEOUT, "APP_VALIDATION_TIMEOUT", CaisseEpargneErrorCode.APP_VALIDATION_TIMEOUT),
            new Case(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", CaisseEpargneErrorCode.INVALID_CREDENTIALS),
            new Case(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE", CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE),
            new Case(HttpStatus.BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED", CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED))) {
            CaisseEpargneAdapter adapter = returning(c.status(), "{\"detail\":\"" + c.detail() + "\"}");

            assertThatThrownBy(() -> adapter.completeAuth("proc-1"))
                .as(c.detail())
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(c.code().name()));
        }
    }

    @Test
    void completeAuth_mapsABareStatusWhenTheSidecarSendsNoDetail() {
        record Case(HttpStatus status, CaisseEpargneErrorCode code) {}
        for (Case c : List.of(
            new Case(HttpStatus.GONE, CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED),
            new Case(HttpStatus.REQUEST_TIMEOUT, CaisseEpargneErrorCode.APP_VALIDATION_TIMEOUT),
            new Case(HttpStatus.UNAUTHORIZED, CaisseEpargneErrorCode.INVALID_CREDENTIALS),
            new Case(HttpStatus.BAD_GATEWAY, CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE))) {
            CaisseEpargneAdapter adapter = returning(c.status(), "oops");

            assertThatThrownBy(() -> adapter.completeAuth("proc-1"))
                .as(c.status().toString())
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(c.code().name()));
        }
    }

    @Test
    void completeAuth_neverLogsOrThrowsTheSessionState() {
        List<Call> calls = new ArrayList<>();
        CaisseEpargneAdapter adapter = recording(calls, HttpStatus.OK, "{\"sessionState\":\"cookie-secret\"}");
        assertThat(adapter.completeAuth("proc-1")).isEqualTo("cookie-secret");

        CaisseEpargneAdapter failing = returning(HttpStatus.BAD_GATEWAY, "{\"detail\":\"UPSTREAM_UNAVAILABLE\"}");
        assertThatThrownBy(() -> failing.completeAuth("proc-1"))
            .satisfies(error -> assertThat(String.valueOf(error.getMessage())).doesNotContain("cookie-secret"));
    }

    // -- helpers ------------------------------------------------------------

    private static CaisseEpargneAdapter adapterOf(ExchangeFunction exchange) {
        return new CaisseEpargneAdapter(WebClient.builder().exchangeFunction(exchange).build(), new ObjectMapper());
    }

    private static CaisseEpargneAdapter returning(HttpStatus status, String body) {
        return adapterOf(request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build()));
    }

    private static CaisseEpargneAdapter recording(List<Call> calls, HttpStatus status, String responseBody) {
        Consumer<Call> sink = calls::add;
        return adapterOf(request -> {
            MockClientHttpRequest recorder = new MockClientHttpRequest(request.method(), request.url());
            request.writeTo(recorder, ExchangeStrategies.withDefaults()).block();
            sink.accept(new Call(request.url().getPath(), recorder.getBodyAsString().block()));
            return Mono.just(ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(responseBody)
                .build());
        });
    }
}
