package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A synthetic Louve MCP server behind the real {@link LouveMcpClient} and {@link LouveAdapter}, so
 * a test outside this package can drive the whole read path from canned tool texts.
 */
public final class LouveFakeServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    public static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");

    private LouveFakeServer() {}

    /** Reads a snapshot where {@code toolText} answers each tool call by name and arguments. */
    public static LouveAdapter.Snapshot snapshot(Function<JsonNode, String> toolText) {
        LouveTransport transport = request -> {
            try {
                JsonNode message = JSON.readTree(request.body());
                String method = message.path("method").asText();
                if (method.equals("notifications/initialized")) {
                    return new LouveTransport.Response(202, Map.of(), "");
                }
                String result = method.equals("initialize")
                    ? "{\"protocolVersion\":\"2025-06-18\"}"
                    : "{\"content\":[{\"type\":\"text\",\"text\":"
                        + JSON.writeValueAsString(toolText.apply(message.path("params"))) + "}]}";
                return new LouveTransport.Response(200, Map.of("Content-Type", List.of("application/json")),
                    "{\"jsonrpc\":\"2.0\",\"id\":" + message.path("id").asInt() + ",\"result\":" + result + "}");
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalStateException(ex);
            }
        };
        LouveAdapter adapter = new LouveAdapter(new LouveOAuthClient(transport),
            new LouveMcpClient(transport, ignored -> {}), Clock.fixed(NOW, ZoneOffset.UTC));
        return adapter.read(LouveAdapter.serialize("synthetic-client",
            "https://picsou.example/sync/louve/callback", "synthetic-access", "synthetic-refresh",
            NOW.plusSeconds(3600)));
    }

    /** {@code portfolio} for get_portfolio, a matching sheet for each get_scpi. */
    public static LouveAdapter.Snapshot snapshot(String portfolio) {
        return snapshot(params -> params.path("name").asText().equals("get_portfolio")
            ? portfolio
            : "{\"urlId\":\"" + params.path("arguments").path("scpi").asText()
                + "\",\"sellingPriceByShare\":80,\"buyingPriceByShare\":80}");
    }
}
