package com.picsou.service;

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
 * admin panel's call log. Capped at {@link #MAX_ENTRIES} entries of at most
 * {@link #MAX_BODY_CHARS} body characters each, so a few catalog calls cannot fill the heap.
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

    public record CallEntry(
        Instant timestamp,
        String method,
        String url,
        String requestBody,
        int responseStatus,
        String responseBody
    ) {}

    private final Deque<CallEntry> buffer = new ArrayDeque<>(MAX_ENTRIES);

    public synchronized void log(String method, String url, String requestBody,
                                  int responseStatus, String responseBody) {
        if (buffer.size() >= MAX_ENTRIES) {
            buffer.pollFirst();
        }
        buffer.addLast(new CallEntry(Instant.now(), method, url, requestBody, responseStatus, cap(responseBody)));
    }

    /**
     * Records every response the client receives, whatever its status, then hands the caller a
     * response rebuilt from the same body so its deserialization is unaffected. A call that gets
     * no response (timeout, refused connection) is not recorded.
     */
    public ExchangeFilterFunction filter() {
        return (request, next) -> {
            String method = request.method().name();
            String url = request.url().toString();
            return next.exchange(request).flatMap(response -> response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {
                    log(method, url, "", response.statusCode().value(), body);
                    return response.mutate().body(body).build();
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
    }

    private static String cap(String body) {
        if (body == null || body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        int bytes = body.getBytes(StandardCharsets.UTF_8).length;
        return body.substring(0, MAX_BODY_CHARS) + "…[truncated, " + bytes + " bytes in total]";
    }
}
