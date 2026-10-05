package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.picsou.port.CryptoExchangePort.ExchangePosition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how the <em>production</em> adapter is built, which the {@link MeriaAdapterTest} fixtures
 * cannot: those inject an {@code ExchangeFunction}, and a stubbed {@code ClientResponse} decodes
 * with its own strategies rather than the WebClient's. So a regression in the constructor —
 * Spring picking the wrong one, or the raised buffer limit going missing — would leave that whole
 * suite green. Both have a cost: the second one shipped, and cost a user two failed syncs.
 */
class MeriaAdapterWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(ObjectMapper.class)
        .withBean(MeriaAdapter.class);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void springSelectsTheProductionConstructorWithoutAnyConfiguredUrl() {
        contextRunner.run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(MeriaAdapter.class));
    }

    @Test
    void theBaseUrlCanBeOverridden() {
        contextRunner.withPropertyValues("app.meria.base-url=http://localhost:1/v1")
            .run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(MeriaAdapter.class));
    }

    @Test
    void theProductionClientReadsAStakingPayloadBiggerThanTheFrameworkDefault() throws IOException {
        // /stakings returns every contract's full variations+credits history — megabytes for a
        // long-standing account, well past WebClient's 256 KB default. Over that limit the body is
        // never assembled and the sync fails, which is what happened in production. Served over a
        // real socket so the production WebClient's own codec configuration is what decodes it.
        String stakings = stakingPayloadLargerThan(256 * 1024);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            String body = exchange.getRequestURI().getPath().endsWith("/stakings")
                ? stakings
                : "{\"success\":true,\"data\":[]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        MeriaAdapter adapter = new MeriaAdapter(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", new ObjectMapper());

        List<ExchangePosition> holdings = adapter.fetchPositions("key", null);

        assertThat(holdings).singleElement()
            .satisfies(holding -> {
                assertThat(holding.symbol()).isEqualTo("ATOM");
                assertThat(holding.quantity()).isEqualByComparingTo("100");
            });
    }

    private static String stakingPayloadLargerThan(int bytes) {
        StringBuilder credits = new StringBuilder();
        for (int i = 0; credits.length() < bytes; i++) {
            if (i > 0) credits.append(',');
            credits.append("{\"amount\":0.00432936,\"date\":15907104").append(i % 100)
                .append(",\"releaseDate\":false,\"released\":1}");
        }
        return "{\"success\":true,\"data\":[{\"currencyCode\":\"ATOM\",\"amount\":100,"
            + "\"reward\":2.5,\"lockedReward\":0.75,\"variations\":[],\"credits\":["
            + credits + "]}]}";
    }
}
