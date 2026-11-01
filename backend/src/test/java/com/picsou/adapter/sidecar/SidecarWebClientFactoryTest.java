package com.picsou.adapter.sidecar;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SidecarWebClientFactoryTest {

    private HttpServer server;
    private final List<String> keyHeaders = new CopyOnWriteArrayList<>();
    private final List<Boolean> keyHeaderPresent = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", exchange -> {
            keyHeaderPresent.add(exchange.getRequestHeaders().containsKey(SidecarWebClientFactory.API_KEY_HEADER));
            keyHeaders.add(exchange.getRequestHeaders().getFirst(SidecarWebClientFactory.API_KEY_HEADER));
            try {
                byte[] payload = "ok".getBytes();
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void sendsNoKeyHeaderWhenTheKeyIsBlank(String blank) {
        ping(new SidecarWebClientFactory(blank).create("Test", baseUrl()));

        assertThat(keyHeaderPresent).containsExactly(false);
    }

    @Test
    void sendsTheTrimmedKeyWhenOneIsConfigured() {
        ping(new SidecarWebClientFactory("  test-key \n").create("Test", baseUrl()));

        assertThat(keyHeaders).containsExactly("test-key");
    }

    private void ping(WebClient client) {
        client.get().uri("/ping").retrieve().bodyToMono(String.class).block();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
