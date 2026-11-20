package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.exception.SyncException;
import com.picsou.port.SidecarErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Synthetic MCP answers; the transport is a queue of canned responses. */
class LouveMcpClientTest {

    private final List<LouveTransport.Request> sent = new ArrayList<>();
    private final Deque<Object> answers = new ArrayDeque<>();
    private final List<Duration> pauses = new ArrayList<>();
    private final LouveMcpClient client = new LouveMcpClient(request -> {
        sent.add(request);
        Object next = answers.poll();
        if (next == null) throw new AssertionError("no answer for request " + sent.size());
        if (next instanceof RuntimeException ex) throw ex;
        return (LouveTransport.Response) next;
    }, pauses::add);

    private static LouveTransport.Response json(int status, String body) {
        return new LouveTransport.Response(status, Map.of("Content-Type", List.of("application/json")), body);
    }

    private static LouveTransport.Response sse(String body) {
        return new LouveTransport.Response(200, Map.of("content-type", List.of("text/event-stream")), body);
    }

    private void initialized() {
        answers.add(new LouveTransport.Response(200, Map.of(
            "Content-Type", List.of("application/json"), "Mcp-Session-Id", List.of("sess-1")),
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}"));
        answers.add(new LouveTransport.Response(202, Map.of(), ""));
    }

    private static String toolResult(int id, String innerJson) {
        String escaped = innerJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\""
            + escaped + "\"}]}}";
    }

    @Test
    void aToolCallSendsTheProtocolHeadersAndEchoesTheSessionId() {
        initialized();
        answers.add(json(200, toolResult(2, "{\"date\":\"2026-11-17\"}")));

        JsonNode result = client.open("access-1").callTool("get_portfolio", Map.of("includeExternal", false));

        assertThat(result.path("date").asText()).isEqualTo("2026-11-17");
        assertThat(sent).hasSize(3);
        LouveTransport.Request initialize = sent.get(0);
        assertThat(initialize.uri().toString()).isEqualTo("https://app.louveinvest.com/mcp");
        assertThat(initialize.headers()).containsEntry("Authorization", "Bearer access-1")
            .containsEntry("MCP-Protocol-Version", "2025-06-18")
            .containsEntry("Accept", "application/json, text/event-stream")
            .doesNotContainKey("Mcp-Session-Id");
        assertThat(initialize.body()).contains("\"method\":\"initialize\"");
        assertThat(sent.get(1).body()).contains("notifications/initialized").doesNotContain("\"id\"");
        assertThat(sent.get(1).headers()).containsEntry("Mcp-Session-Id", "sess-1");
        assertThat(sent.get(2).headers()).containsEntry("Mcp-Session-Id", "sess-1");
        assertThat(sent.get(2).body()).contains("\"name\":\"get_portfolio\"").contains("\"includeExternal\":false");
    }

    @Test
    void anSseAnswerIsSplitOnNewlinesOnlyAndJoinsMultiLineData() {
        initialized();
        // U+2028 and U+0085 inside the payload: a universal-newline split would cut the JSON there.
        String inner = "{\"scpiName\":\"Aurore Explore\u0085\",\"nbShares\":12}";
        String message = toolResult(2, inner);
        int cut = message.indexOf("\"result\"");
        answers.add(sse(": keep-alive\r\n"
            + "event: message\r\n"
            + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\r\n\r\n"
            + "event: message\n"
            + "data: " + message.substring(0, cut) + "\n"
            + "data: " + message.substring(cut) + "\n\n"));

        JsonNode result = client.open("access-1").callTool("get_portfolio", Map.of());

        assertThat(result.path("scpiName").asText()).isEqualTo("Aurore Explore\u0085");
        assertThat(result.path("nbShares").asInt()).isEqualTo(12);
    }

    @Test
    void decimalsAreReadExactly() {
        initialized();
        answers.add(json(200, toolResult(2, "{\"monthlyAmount\":9.37}")));

        JsonNode result = client.open("a").callTool("get_portfolio", Map.of());

        assertThat(result.get("monthlyAmount").decimalValue()).isEqualByComparingTo("9.37");
        assertThat(result.get("monthlyAmount").decimalValue().scale()).isEqualTo(2);
    }

    @Test
    void structuredContentIsPreferredWhenPresent() {
        initialized();
        answers.add(json(200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"structuredContent\":{\"x\":1},"
            + "\"content\":[{\"type\":\"text\",\"text\":\"not json\"}]}}"));

        assertThat(client.open("a").callTool("get_scpi", Map.of("scpi", "aurore")).path("x").asInt()).isEqualTo(1);
    }

    @Test
    void anUnauthorizedAnswerIsDistinguishedForARefresh() {
        answers.add(new LouveTransport.Response(401, Map.of("WWW-Authenticate",
            List.of("Bearer resource_metadata=\"https://app.louveinvest.com/.well-known/oauth-protected-resource\"")), ""));

        assertThatThrownBy(() -> client.open("expired"))
            .isInstanceOf(LouveMcpClient.Unauthorized.class)
            .extracting(ex -> ((SyncException) ex).getCode()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED.name());
    }

    @Test
    void aTransient503IsRetriedOnce() {
        answers.add(json(503, "backend read error"));
        initialized();
        answers.add(json(200, toolResult(2, "{\"ok\":true}")));

        JsonNode result = client.open("a").callTool("get_portfolio", Map.of());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(pauses).containsExactly(LouveMcpClient.RETRY_PAUSE);
    }

    @Test
    void a503TwiceIsUpstreamUnavailable() {
        answers.add(json(503, "backend read error"));
        answers.add(json(503, "backend read error"));

        assertCode(() -> client.open("a"), SidecarErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(pauses).hasSize(1);
    }

    @Test
    void anInterruptDuringThePauseStopsWithoutRetrying() {
        LouveMcpClient interruptible = new LouveMcpClient(request -> {
            sent.add(request);
            return json(503, "backend read error");
        }, LouveMcpClient::sleepUnlessInterrupted);
        Thread.currentThread().interrupt();
        try {
            assertCode(() -> interruptible.open("a"), SidecarErrorCode.UPSTREAM_UNAVAILABLE);
            assertThat(Thread.currentThread().isInterrupted()).as("the interrupt flag is restored").isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(sent).hasSize(1);
    }

    @Test
    void anUnreachableHostTwiceIsUpstreamUnavailable() {
        answers.add(new LouveTransport.Unreachable("down", null));
        answers.add(new LouveTransport.Unreachable("down", null));

        assertCode(() -> client.open("a"), SidecarErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void aToolErrorAndAProtocolErrorAreTellApart() {
        initialized();
        answers.add(json(200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":true,"
            + "\"content\":[{\"type\":\"text\",\"text\":\"unknown scpi\"}]}}"));
        LouveMcpClient.Session session = client.open("a");
        assertThatThrownBy(() -> session.callTool("get_scpi", Map.of("scpi", "x")))
            .isInstanceOf(LouveMcpClient.ToolFailed.class);

        answers.add(json(200, "{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32602,\"message\":\"bad\"}}"));
        assertCode(() -> session.callTool("get_scpi", Map.of()), SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void forbiddenAndRateLimitedMapToTheirCodes() {
        answers.add(json(403, "{\"error\":\"insufficient_scope\"}"));
        assertCode(() -> client.open("a"), SidecarErrorCode.SESSION_EXPIRED);

        answers.add(json(429, ""));
        assertCode(() -> client.open("a"), SidecarErrorCode.RATE_LIMITED);
    }

    @Test
    void anAnswerToAnotherRequestIsNotTakenForOurs() {
        answers.add(json(200, "{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}"));

        assertCode(() -> client.open("a"), SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void sseDataHandlesCrlfCommentsAndAFinalEventWithoutBlankLine() {
        assertThat(LouveMcpClient.sseData("data:a\r\ndata: b\r\n\r\n: c\ndata: d")).containsExactly("a\nb", "d");
    }

    private static void assertCode(Runnable call, SidecarErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SyncException.class,
            ex -> assertThat(ex.getCode()).isEqualTo(code.name()));
    }
}
