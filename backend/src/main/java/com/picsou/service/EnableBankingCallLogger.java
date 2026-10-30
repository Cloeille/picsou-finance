package com.picsou.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory circular buffer of raw Enable Banking HTTP calls (request + response).
 * Capped at MAX_ENTRIES to avoid unbounded memory use. Thread-safe via synchronized blocks.
 */
@Component
public class EnableBankingCallLogger {

    private static final int MAX_ENTRIES = 200;

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
        buffer.addLast(new CallEntry(Instant.now(), method, url, requestBody, responseStatus, responseBody));
    }

    /** Returns entries newest-first. */
    public synchronized List<CallEntry> entries() {
        List<CallEntry> list = new ArrayList<>(buffer);
        java.util.Collections.reverse(list);
        return list;
    }

    public synchronized void clear() {
        buffer.clear();
    }
}
