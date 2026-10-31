package com.picsou.adapter;

import com.picsou.adapter.sidecar.SidecarAuthenticationException;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.exception.SyncException;
import com.picsou.model.CategoryKind;
import com.picsou.service.sync.SyncProgressService;
import com.picsou.service.sync.SyncProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class RevolutAdapterTest {

    @Test
    void sync_sendsAllowLoginFalseToSidecar() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> keys = new CopyOnWriteArrayList<>();
        try (TestServer server = TestServer.start(exchange -> {
            bodies.add(readBody(exchange));
            keys.add(exchange.getRequestHeaders().getFirst(SidecarWebClientFactory.API_KEY_HEADER));
            respond(exchange, 200, "{\"accounts\":[]}");
        })) {
            RevolutAdapter adapter = adapter(server);

            adapter.sync("+33600000000", "123456", 5L, false);

            assertThat(bodies).hasSize(1);
            assertThat(bodies.getFirst()).contains("\"allowLogin\":false");
            assertThat(keys).containsExactly("test-key");
        }
    }

    @Test
    void sync_reportsARejectedKeyAsADeploymentFaultRatherThanAnExpiredSession() throws Exception {
        try (TestServer server = TestServer.start(exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", SidecarWebClientFactory.AUTH_CHALLENGE);
            respond(exchange, 401, "{\"detail\":\"UNAUTHORIZED\"}");
        })) {
            RevolutAdapter adapter = adapter(server);

            assertThatThrownBy(() -> adapter.sync("+33600000000", "123456", 5L))
                .isInstanceOfSatisfying(SidecarAuthenticationException.class, error ->
                    assertThat(error.getCode()).isEqualTo(SidecarAuthenticationException.CODE));
        }
    }

    @Test
    void sync_stillMapsTheSidecarsOwnUnchallenged401ToSessionExpired() throws Exception {
        try (TestServer server = TestServer.start(exchange ->
            respond(exchange, 401, "{\"error\":\"SESSION_EXPIRED\"}"))) {
            RevolutAdapter adapter = adapter(server);

            assertThatThrownBy(() -> adapter.sync("+33600000000", "123456", 5L, false))
                .isInstanceOf(SyncException.class)
                .hasMessage("SESSION_EXPIRED");
        }
    }

    @Test
    void sync_mapsBrowserLaunchFailureToSyncException() throws Exception {
        try (TestServer server = TestServer.start(exchange ->
            respond(exchange, 503, "{\"error\":\"BROWSER_LAUNCH_FAILED\"}"))) {
            RevolutAdapter adapter = adapter(server);

            assertThatThrownBy(() -> adapter.sync("+33600000000", "123456", 5L))
                .isInstanceOf(SyncException.class)
                .hasMessage("BROWSER_LAUNCH_FAILED");
        }
    }

    @Test
    void sync_mapsHumanVerificationToACodedSyncException() throws Exception {
        try (TestServer server = TestServer.start(exchange ->
            respond(exchange, 403, "{\"error\":\"HUMAN_VERIFICATION_REQUIRED\"}"))) {
            RevolutAdapter adapter = adapter(server);

            assertThatThrownBy(() -> adapter.sync("+336****0000", "123456", 5L))
                .isInstanceOfSatisfying(SyncException.class, error -> {
                    assertThat(error).hasMessage("HUMAN_VERIFICATION_REQUIRED");
                    assertThat(error.getCode()).isEqualTo("HUMAN_VERIFICATION_REQUIRED");
                });
        }
    }

    @Test
    void sync_mapsARefusedPacedLoginToACodedSyncException() throws Exception {
        try (TestServer server = TestServer.start(exchange ->
            respond(exchange, 429, "{\"error\":\"LOGIN_PACED\"}"))) {
            RevolutAdapter adapter = adapter(server);

            assertThatThrownBy(() -> adapter.sync("+336****0000", "123456", 5L))
                .isInstanceOfSatisfying(SyncException.class, error -> {
                    assertThat(error).hasMessage("LOGIN_PACED");
                    assertThat(error.getCode()).isEqualTo("LOGIN_PACED");
                });
        }
    }

    /**
     * While Revolut asks for a captcha the sidecar publishes the remote view's port and one-time
     * password on {@code /progress}; the adapter hands both to the progress registry so the UI can
     * offer the link. The progress poll starts 1s after the call, so the sync is held for 1.5s.
     */
    @Test
    void sync_relaysTheCaptchaViewerOfTheSidecarToTheProgressService() throws Exception {
        SyncProgressService progress = mock(SyncProgressService.class);
        try (TestServer server = TestServer.start(exchange -> {
            sleep(1500);
            respond(exchange, 200, "{\"accounts\":[]}");
        }, "{\"phase\":\"AWAITING_HUMAN\",\"remainingSeconds\":200,\"viewerPort\":6080,\"viewerPassword\":\"Ab3dEf9Z\"}")) {
            RevolutAdapter adapter = new RevolutAdapter(
                new SidecarWebClientFactory("test-key"), server.baseUrl(), progress);

            adapter.sync("+33600000000", "123456", 5L);

            verify(progress, timeout(2000)).phase(eq(5L), eq(SyncProvider.REVOLUT), eq("AWAITING_HUMAN"),
                eq(200), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
            verify(progress, timeout(2000)).viewer(5L, SyncProvider.REVOLUT, 6080, "Ab3dEf9Z");
        }
    }

    @Test
    void sync_clearsTheViewerWhenTheSidecarReportsNone() throws Exception {
        SyncProgressService progress = mock(SyncProgressService.class);
        try (TestServer server = TestServer.start(exchange -> {
            sleep(1500);
            respond(exchange, 200, "{\"accounts\":[]}");
        }, "{\"phase\":\"LOGGING_IN\"}")) {
            RevolutAdapter adapter = new RevolutAdapter(
                new SidecarWebClientFactory("test-key"), server.baseUrl(), progress);

            adapter.sync("+33600000000", "123456", 5L);

            verify(progress, timeout(2000)).viewer(5L, SyncProvider.REVOLUT, null, null);
        }
    }

    @Test
    void sync_mapsAnUnrecognisedLoginPageToACodedSyncException() throws Exception {
        try (TestServer server = TestServer.start(exchange ->
            respond(exchange, 424, "{\"error\":\"UPSTREAM_FORMAT_CHANGED\"}"))) {
            RevolutAdapter adapter = adapter(server);

            assertThatThrownBy(() -> adapter.sync("+336****0000", "123456", 5L))
                .isInstanceOfSatisfying(SyncException.class, error -> {
                    assertThat(error).hasMessage("UPSTREAM_FORMAT_CHANGED");
                    assertThat(error.getCode()).isEqualTo("UPSTREAM_FORMAT_CHANGED");
                });
        }
    }

    @Test
    void sync_mapsTransactionKindFromSidecar() throws Exception {
        try (TestServer server = TestServer.start(exchange -> respond(exchange, 200, """
            {"accounts":[{"externalId":"wallet","name":"Wallet","type":"CHECKING","balance":10,
              "currency":"EUR","transactions":[{"externalId":"mirror-id","date":"2026-09-20",
              "description":"Transfer","amount":-5,"counterparty":null,"kind":"TRANSFER"}]}]}
            """))) {
            var accounts = adapter(server).sync("+336****0000", "123456", 5L, false);

            assertThat(accounts).singleElement().satisfies(account ->
                assertThat(account.txns()).singleElement().satisfies(transaction ->
                    assertThat(transaction.kind()).isEqualTo(CategoryKind.TRANSFER)));
        }
    }

    @Test
    void sync_mapsEachLegsOwnCurrencyFromSidecar() throws Exception {
        try (TestServer server = TestServer.start(exchange -> respond(exchange, 200, """
            {"accounts":[{"externalId":"wallet","name":"Wallet","type":"CHECKING","balance":10,
              "currency":"EUR","transactions":[
                {"externalId":"x","date":"2026-09-20","description":"Exchanged to EUR","amount":-0.05,
                 "counterparty":null,"currency":"BTC"},
                {"externalId":"y","date":"2026-09-20","description":"Coffee","amount":-3,
                 "counterparty":null}]}]}
            """))) {
            var accounts = adapter(server).sync("+336****0000", "123456", 5L, false);

            assertThat(accounts).singleElement().satisfies(account ->
                assertThat(account.txns()).extracting(com.picsou.port.RevolutPort.RevolutTxn::currency)
                    .containsExactly("BTC", null));
        }
    }

    private static RevolutAdapter adapter(TestServer server) {
        return new RevolutAdapter(
            new SidecarWebClientFactory("test-key"), server.baseUrl(), mock(SyncProgressService.class));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readBody(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange);
    }

    private static class TestServer implements AutoCloseable {
        private final HttpServer server;

        private TestServer(HttpServer server) {
            this.server = server;
        }

        static TestServer start(Handler handler) throws IOException {
            return start(handler, "{\"phase\":null}");
        }

        static TestServer start(Handler handler, String progressBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/sync", handler::handle);
            server.createContext("/progress/5", exchange -> respond(exchange, 200, progressBody));
            // The default executor is single-threaded: a /sync held open would starve /progress.
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return new TestServer(server);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
        }
    }
}
