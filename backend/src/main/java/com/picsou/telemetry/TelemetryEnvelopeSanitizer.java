package com.picsou.telemetry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Rebuilds a Sentry envelope from a client-supplied one, keeping only {@code event} items, each
 * re-scrubbed with {@link TelemetryScrubber}. Session, replay, attachment, transaction,
 * client_report … items are dropped. The header keeps {@code sent_at} only and carries the
 * <em>configured</em> DSN — whatever DSN the client put in its header is ignored.
 */
public final class TelemetryEnvelopeSanitizer {

    static final int MAX_EVENTS = 5;
    private static final Pattern SENT_AT = Pattern.compile("^[0-9T:.+\\-Z]{10,40}$");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private TelemetryEnvelopeSanitizer() {
    }

    /** @return the envelope to forward, or empty when nothing survives (or the input is garbage). */
    public static Optional<byte[]> sanitize(byte[] raw, String configuredDsn, ObjectMapper mapper) {
        try {
            int[] pos = {0};
            Map<String, Object> header = readJson(readLine(raw, pos), mapper);
            if (header == null) {
                return Optional.empty();
            }
            List<byte[]> events = new ArrayList<>();
            while (pos[0] < raw.length && events.size() < MAX_EVENTS) {
                byte[] itemHeaderBytes = readLine(raw, pos);
                if (itemHeaderBytes.length == 0) {
                    continue; // blank separator line
                }
                Map<String, Object> itemHeader = readJson(itemHeaderBytes, mapper);
                if (itemHeader == null) {
                    break;
                }
                byte[] payload;
                if (itemHeader.get("length") instanceof Number n) {
                    int len = n.intValue();
                    if (len < 0 || pos[0] + len > raw.length) {
                        break;
                    }
                    payload = java.util.Arrays.copyOfRange(raw, pos[0], pos[0] + len);
                    pos[0] += len;
                    if (pos[0] < raw.length && raw[pos[0]] == '\n') {
                        pos[0]++;
                    }
                } else {
                    payload = readLine(raw, pos);
                }
                if (!"event".equals(itemHeader.get("type"))) {
                    continue;
                }
                Map<String, Object> event = readJson(payload, mapper);
                if (event == null) {
                    continue;
                }
                events.add(mapper.writeValueAsBytes(TelemetryScrubber.scrubEvent(event)));
            }
            if (events.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> outHeader = new LinkedHashMap<>();
            if (header.get("sent_at") instanceof String s && SENT_AT.matcher(s).matches()) {
                outHeader.put("sent_at", s);
            }
            outHeader.put("dsn", configuredDsn);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(mapper.writeValueAsBytes(outHeader));
            out.write('\n');
            for (byte[] event : events) {
                Map<String, Object> itemHeader = new LinkedHashMap<>();
                itemHeader.put("type", "event");
                itemHeader.put("length", event.length);
                out.write(mapper.writeValueAsBytes(itemHeader));
                out.write('\n');
                out.write(event);
                out.write('\n');
            }
            return Optional.of(out.toByteArray());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static byte[] readLine(byte[] raw, int[] pos) {
        int start = pos[0];
        int end = start;
        while (end < raw.length && raw[end] != '\n') {
            end++;
        }
        pos[0] = Math.min(end + 1, raw.length);
        if (end == raw.length) {
            pos[0] = raw.length;
        }
        return java.util.Arrays.copyOfRange(raw, start, end);
    }

    private static Map<String, Object> readJson(byte[] bytes, ObjectMapper mapper) {
        if (bytes.length == 0) {
            return null;
        }
        try {
            return mapper.readValue(new String(bytes, StandardCharsets.UTF_8), MAP);
        } catch (Exception e) {
            return null;
        }
    }
}
