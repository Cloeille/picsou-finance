package com.picsou.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * In-memory circular buffer of raw Enable Banking HTTP calls (request + response), read by the
 * admin panel's call log. Bounded three ways so it stays a few megabytes on a small instance:
 * at most {@link #MAX_ENTRIES} entries, {@link #MAX_BODY_CHARS} characters per stored body, and
 * {@link #MAX_TOTAL_BODY_CHARS} characters across all bodies, the oldest entries going first.
 * Thread-safe via synchronized blocks.
 *
 * <p>The connector records calls by applying {@link #filter()} to its WebClient. Request bodies
 * are not captured: they are opaque body inserters at that point, and the response bodies are
 * what diagnose a sync.
 */
@Component
public class EnableBankingCallLogger {

    static final int MAX_ENTRIES = 200;
    static final int MAX_BODY_CHARS = 256 * 1024;
    /** About 8 to 16 MB of heap: a handful of capped catalog answers, or many account calls. */
    static final int MAX_TOTAL_BODY_CHARS = 8 * 1024 * 1024;

    public record CallEntry(
        Instant timestamp,
        String method,
        String url,
        String requestBody,
        int responseStatus,
        String responseBody
    ) {}

    private final Deque<CallEntry> buffer = new ArrayDeque<>(MAX_ENTRIES);
    private long totalBodyChars;

    public synchronized void log(String method, String url, String requestBody,
                                  int responseStatus, String responseBody) {
        CallEntry entry = new CallEntry(Instant.now(), method, url, requestBody, responseStatus, cap(responseBody));
        buffer.addLast(entry);
        totalBodyChars += bodyChars(entry);
        while (buffer.size() > MAX_ENTRIES || (totalBodyChars > MAX_TOTAL_BODY_CHARS && buffer.size() > 1)) {
            totalBodyChars -= bodyChars(buffer.pollFirst());
        }
    }

    /**
     * Records every response the client receives, whatever its status, then hands the caller a
     * response rebuilt from the same body so its deserialization is unaffected. The rebuilt body
     * is UTF-8 whatever charset the provider declared, so the Content-Type says so and the stale
     * Content-Length goes. A call that gets no response (timeout, refused connection) is not
     * recorded.
     */
    public ExchangeFilterFunction filter() {
        return (request, next) -> {
            String method = request.method().name();
            String url = request.url().toString();
            return next.exchange(request).flatMap(response -> response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {
                    log(method, url, "", response.statusCode().value(), body);
                    return response.mutate()
                        .headers(EnableBankingCallLogger::describeUtf8Body)
                        .body(body)
                        .build();
                }));
        };
    }

    /** Returns entries newest-first. */
    public synchronized List<CallEntry> entries() {
        List<CallEntry> list = new ArrayList<>(buffer);
        Collections.reverse(list);
        return list;
    }

    public synchronized void clear() {
        buffer.clear();
        totalBodyChars = 0;
    }

    private static void describeUtf8Body(HttpHeaders headers) {
        MediaType contentType = headers.getContentType();
        if (contentType != null && contentType.getCharset() != null) {
            headers.setContentType(new MediaType(contentType, StandardCharsets.UTF_8));
        }
        headers.remove(HttpHeaders.CONTENT_LENGTH);
    }

    private static long bodyChars(CallEntry entry) {
        return entry.responseBody() == null ? 0 : entry.responseBody().length();
    }

    private static String cap(String body) {
        if (body == null || body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        // Never keep half of a surrogate pair: a lone surrogate cannot be written as JSON, and
        // one in the buffer would fail every read of the log until it is cleared.
        int end = Character.isHighSurrogate(body.charAt(MAX_BODY_CHARS - 1)) ? MAX_BODY_CHARS - 1 : MAX_BODY_CHARS;
        return body.substring(0, end) + "…[truncated, " + body.length() + " characters in total]";
    }
}
