package com.picsou.adapter.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SidecarWebClientFactoryTest {

    private HttpServer server;
    private final List<String> keyHeaders = new CopyOnWriteArrayList<>();
    private final List<Boolean> keyHeaderPresent = new CopyOnWriteArrayList<>();
    /** Over Spring's 256 KB default, under the factory's limit. */
    private static final int LARGE_BODY_BYTES = 1024 * 1024;

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
        server.createContext("/challenge", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", SidecarWebClientFactory.AUTH_CHALLENGE);
            try {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
        server.createContext("/large", exchange -> {
            byte[] payload = ("\"" + "x".repeat(LARGE_BODY_BYTES) + "\"").getBytes();
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
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
        ping(SidecarTestSupport.factory("test-auth", baseUrl(), blank).create("Test", "test-auth"));

        assertThat(keyHeaderPresent).containsExactly(false);
    }

    @Test
    void sendsTheTrimmedKeyWhenOneIsConfigured() {
        ping(SidecarTestSupport.factory("test-auth", baseUrl(), "  test-key \n").create("Test", "test-auth"));

        assertThat(keyHeaders).containsExactly("test-key");
    }

    @Test
    void mapsSidecarKeyChallengeToAuthenticationFailureWhenTheKeyIsBlank() {
        var client = SidecarTestSupport.factory("test-auth", baseUrl(), "").create("Test", "test-auth");

        assertThatThrownBy(() -> client.get().uri("/challenge").retrieve()
            .bodyToMono(String.class).block())
            .isInstanceOf(SidecarAuthenticationException.class);
    }

    @Test
    void decodesABodyLargerThanSpringsDefaultBufferForEveryAdapter() {
        var client = SidecarTestSupport.factory("test-auth", baseUrl()).create("Test", "test-auth");

        String body = client.get().uri("/large").retrieve().bodyToMono(String.class).block();

        assertThat(body).hasSize(LARGE_BODY_BYTES + 2);
    }

    @Test
    void keepsTheLimitWhenTheAdapterReplacesTheJsonDecoder() {
        var client = SidecarTestSupport.factory("test-auth", baseUrl()).create("Test", "test-auth",
            builder -> builder.codecs(c -> c.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder())));

        JsonNode body = client.get().uri("/large").retrieve().bodyToMono(JsonNode.class).block();

        assertThat(body.asText()).hasSize(LARGE_BODY_BYTES);
    }

    private void ping(WebClient client) {
        client.get().uri("/ping").retrieve().bodyToMono(String.class).block();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
