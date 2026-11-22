package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.picsou.exception.SyncException;
import com.picsou.port.SidecarErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal JSON-RPC client for Louve Invest's MCP server over Streamable HTTP (protocol
 * {@value #PROTOCOL_VERSION}): {@code initialize}, {@code notifications/initialized}, then
 * {@code tools/call}. Only read-only tools are ever called.
 *
 * <p>An answer comes either as plain JSON or as a Server-Sent Events stream. The stream is split
 * on {@code \n} only, never on every Unicode line terminator: the JSON payload may carry
 * characters (U+2028, U+0085...) that a "universal newline" split would cut through.
 *
 * <p>A 502/503/504 or an unreachable host is retried once after a short pause, then reported as
 * {@code UPSTREAM_UNAVAILABLE}; so is an interrupt during the pause, without the retry. A 401 is {@link Unauthorized}, so the caller can refresh the
 * access token once and try again.
 */
@Component
public class LouveMcpClient {

    static final URI ENDPOINT = URI.create(LouveOAuthClient.RESOURCE);
    static final String PROTOCOL_VERSION = "2025-06-18";
    static final Duration RETRY_PAUSE = Duration.ofSeconds(2);

    private static final Logger log = LoggerFactory.getLogger(LouveMcpClient.class);
    /** Decimals stay decimals: a price read as a double would not come back to the cent. */
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    /** Pauses between a transient failure and its one retry. Tests pass a no-op. */
    interface Pause {
        void sleep(Duration duration);
    }

    private final LouveTransport transport;
    private final Pause pause;

    public LouveMcpClient() {
        this(new LouveTransport.Http(), LouveMcpClient::sleepUnlessInterrupted);
    }

    /**
     * The default pause. An interrupt (shutdown) restores the flag and ends the call as
     * {@code UPSTREAM_UNAVAILABLE} instead of retrying.
     */
    static void sleepUnlessInterrupted(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SyncException("Louve Invest is temporarily unavailable", ex,
                SidecarErrorCode.UPSTREAM_UNAVAILABLE.name());
        }
    }

    /** Visible for tests. */
    LouveMcpClient(LouveTransport transport, Pause pause) {
        this.transport = transport;
        this.pause = pause;
    }

    /** The access token was refused (expired or revoked). */
    public static final class Unauthorized extends SyncException {
        public Unauthorized() {
            super("The Louve Invest access was refused", null, SidecarErrorCode.SESSION_EXPIRED.name());
        }
    }

    /** The tool ran and reported a failure ({@code isError}); the protocol itself worked. */
    public static final class ToolFailed extends SyncException {
        ToolFailed(String tool) {
            super("Louve Invest could not answer " + tool, null, SidecarErrorCode.UPSTREAM_UNAVAILABLE.name());
        }
    }

    /** Opens an MCP session: {@code initialize}, then the {@code initialized} notification. */
    public Session open(String accessToken) {
        Session session = new Session(accessToken);
        ObjectNode params = JSON.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "picsou").put("version", "1");
        LouveTransport.Response response = session.post(session.request("initialize", params), true);
        response.header(SESSION_HEADER).filter(id -> id.matches("[\\x21-\\x7E]{1,200}"))
            .ifPresent(id -> session.sessionId = id);
        session.result(response, session.lastId);

        ObjectNode initialized = JSON.createObjectNode();
        initialized.put("jsonrpc", "2.0");
        initialized.put("method", "notifications/initialized");
        LouveTransport.Response ack = session.post(initialized, false);
        if (ack.status() != 202 && ack.status() != 200 && ack.status() != 204) {
            throw statusError(ack.status());
        }
        return session;
    }

    /** One MCP session. Not thread-safe; a sync uses it for one run. */
    public final class Session {
        private final String accessToken;
        private String sessionId;
        private int nextId = 1;
        private int lastId;

        private Session(String accessToken) {
            this.accessToken = accessToken;
        }

        /**
         * Calls a tool and returns its JSON result: {@code structuredContent} when the server
         * sends it, else the JSON held by the first text content item.
         */
        public JsonNode callTool(String tool, Map<String, Object> arguments) {
            ObjectNode params = JSON.createObjectNode();
            params.put("name", tool);
            params.set("arguments", JSON.valueToTree(arguments == null ? Map.of() : arguments));
            ObjectNode request = request("tools/call", params);
            JsonNode result = result(post(request, true), lastId);
            if (result.path("isError").asBoolean(false)) {
                log.warn("Louve Invest tool reported an error (tool={})", tool);
                throw new ToolFailed(tool);
            }
            JsonNode structured = result.get("structuredContent");
            if (structured != null && structured.isObject()) {
                return structured;
            }
            JsonNode parsed = textResult(tool, result);
            if (parsed == null || !parsed.isObject()) {
                // "[]" or a bare value would otherwise read as an empty, valid answer.
                throw formatChanged("Louve Invest answered " + tool + " with something else than an object");
            }
            return parsed;
        }

        private JsonNode textResult(String tool, JsonNode result) {
            JsonNode first = result.path("content").path(0);
            if (!"text".equals(first.path("type").asText()) || !first.path("text").isTextual()) {
                throw formatChanged("Louve Invest answered " + tool + " without a text result");
            }
            try {
                return JSON.readTree(first.get("text").asText());
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw formatChanged("Louve Invest answered " + tool + " with unreadable data");
            }
        }

        private ObjectNode request(String method, ObjectNode params) {
            lastId = nextId++;
            ObjectNode request = JSON.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", lastId);
            request.put("method", method);
            request.set("params", params);
            return request;
        }

        private LouveTransport.Response post(ObjectNode message, boolean expectsAnswer) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + accessToken);
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json, text/event-stream");
            headers.put("MCP-Protocol-Version", PROTOCOL_VERSION);
            if (sessionId != null) {
                headers.put(SESSION_HEADER, sessionId);
            }
            LouveTransport.Request request = new LouveTransport.Request("POST", ENDPOINT, headers, message.toString());
            LouveTransport.Response response = sendWithOneRetry(request);
            int status = response.status();
            if (status == 401) {
                throw new Unauthorized();
            }
            if (expectsAnswer && status != 200) {
                throw statusError(status);
            }
            return response;
        }

        private JsonNode result(LouveTransport.Response response, int id) {
            JsonNode message = answerTo(response, id);
            if (message.has("error")) {
                log.warn("Louve Invest answered a JSON-RPC error (code={})", message.path("error").path("code").asText());
                throw formatChanged("Louve Invest refused the request");
            }
            JsonNode result = message.get("result");
            if (result == null || !result.isObject()) {
                throw formatChanged("Louve Invest answered without a result");
            }
            return result;
        }
    }

    private LouveTransport.Response sendWithOneRetry(LouveTransport.Request request) {
        for (int attempt = 1; ; attempt++) {
            try {
                LouveTransport.Response response = transport.send(request);
                if (!isTransient(response.status()) || attempt == 2) {
                    return response;
                }
                log.info("Louve Invest answered a transient error, retrying once (status={})", response.status());
            } catch (LouveTransport.Unreachable ex) {
                if (attempt == 2) {
                    throw new SyncException("Louve Invest is temporarily unavailable", ex,
                        SidecarErrorCode.UPSTREAM_UNAVAILABLE.name());
                }
                log.info("Louve Invest could not be reached, retrying once (type={})", ex.getClass().getSimpleName());
            }
            pause.sleep(RETRY_PAUSE);
        }
    }

    private static boolean isTransient(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    /** The JSON-RPC message answering {@code id}, from a JSON body or an SSE stream. */
    static JsonNode answerTo(LouveTransport.Response response, int id) {
        String contentType = response.header("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        List<String> payloads = contentType.startsWith("text/event-stream")
            ? sseData(response.body())
            : List.of(response.body() == null ? "" : response.body());
        for (String payload : payloads) {
            JsonNode node;
            try {
                node = JSON.readTree(payload);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw formatChanged("Louve Invest sent an unreadable message");
            }
            if (node == null) {
                continue;
            }
            for (JsonNode message : node.isArray() ? iterable(node) : List.of(node)) {
                if (message.path("id").canConvertToInt() && message.path("id").asInt() == id) {
                    return message;
                }
            }
        }
        throw formatChanged("Louve Invest did not answer the request");
    }

    /**
     * The {@code data} of each event of an SSE body, multi-line data joined with {@code \n}.
     * Lines are split on {@code \n} only, with one trailing {@code \r} dropped (CRLF streams).
     */
    static List<String> sseData(String body) {
        List<String> events = new ArrayList<>();
        if (body == null) {
            return events;
        }
        StringBuilder data = null;
        for (String raw : body.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isEmpty()) {
                if (data != null) {
                    events.add(data.toString());
                    data = null;
                }
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (field.equals("data")) {
                if (data == null) {
                    data = new StringBuilder(value);
                } else {
                    data.append('\n').append(value);
                }
            }
        }
        if (data != null) {
            events.add(data.toString());
        }
        return events;
    }

    private static List<JsonNode> iterable(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        return items;
    }

    private static SyncException statusError(int status) {
        log.warn("Louve Invest MCP request failed (status={})", status);
        if (status == 403) {
            // insufficient_scope: the grant no longer covers the portfolio, only a new consent helps.
            return new SyncException("Louve Invest no longer grants access to the portfolio", null,
                SidecarErrorCode.SESSION_EXPIRED.name());
        }
        if (status == 429) {
            return new SyncException("Louve Invest is limiting requests, try again later", null,
                SidecarErrorCode.RATE_LIMITED.name());
        }
        if (status >= 500 || status == 404) {
            // 404 on a session id means Louve dropped the MCP session; the next run opens a new one.
            return new SyncException("Louve Invest is temporarily unavailable", null,
                SidecarErrorCode.UPSTREAM_UNAVAILABLE.name());
        }
        return formatChanged("Louve Invest refused the request");
    }

    /** The provider answered in a shape this client does not read; shared with {@link LouveAdapter}. */
    static SyncException formatChanged(String message) {
        return new SyncException(message, null, SidecarErrorCode.UPSTREAM_FORMAT_CHANGED.name());
    }
}
