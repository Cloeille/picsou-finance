package com.picsou.telemetry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
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

    private static final Logger log = LoggerFactory.getLogger(TelemetryEnvelopeSanitizer.class);
    static final int MAX_EVENTS = 5;
    private static final Pattern SENT_AT = Pattern.compile("^[0-9T:.+\\-Z]{10,40}$");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private TelemetryEnvelopeSanitizer() {
    }

    /** @return the envelope to forward, or empty when nothing survives (or the input is garbage). */
    public static Optional<byte[]> sanitize(byte[] raw, String configuredDsn, ObjectMapper mapper) {
        try {
            ByteBuffer cursor = ByteBuffer.wrap(raw);
            Map<String, Object> header = readJson(readLine(cursor), mapper);
            if (header == null) {
                return Optional.empty();
            }
            List<byte[]> events = new ArrayList<>();
            while (cursor.hasRemaining() && events.size() < MAX_EVENTS) {
                byte[] itemHeaderBytes = readLine(cursor);
                if (itemHeaderBytes.length == 0) {
                    continue; // blank separator line
                }
                Map<String, Object> itemHeader = readJson(itemHeaderBytes, mapper);
                if (itemHeader == null) {
                    break;
                }
                byte[] payload;
                if (itemHeader.containsKey("length")) {
                    if (!(itemHeader.get("length") instanceof Number n)) {
                        return Optional.empty();
                    }
                    int len = exactLength(n, cursor.remaining());
                    if (len < 0) {
                        return Optional.empty();
                    }
                    payload = new byte[len];
                    cursor.get(payload);
                    if (cursor.hasRemaining()) {
                        cursor.mark();
                        if (cursor.get() != '\n') {
                            cursor.reset();
                        }
                    }
                } else {
                    payload = readLine(cursor);
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
            log.error("telemetry.envelope.sanitize.failed type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static int exactLength(Number number, int remaining) {
        try {
            long length = new BigDecimal(number.toString()).longValueExact();
            return length >= 0 && length <= remaining ? (int) length : -1;
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }

    private static byte[] readLine(ByteBuffer cursor) {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (cursor.hasRemaining()) {
            byte value = cursor.get();
            if (value == '\n') {
                break;
            }
            line.write(value);
        }
        return line.toByteArray();
    }

    private static Map<String, Object> readJson(byte[] bytes, ObjectMapper mapper) {
        if (bytes.length == 0) {
            return null;
        }
        try {
            return mapper.readerFor(MAP).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("telemetry.envelope.parse.failed type={}", e.getClass().getSimpleName());
            return null;
        }
    }
}
