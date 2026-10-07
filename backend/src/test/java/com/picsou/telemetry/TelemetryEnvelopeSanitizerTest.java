package com.picsou.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class TelemetryEnvelopeSanitizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EVENT = "{\"level\":\"error\",\"message\":\"safe message\"}";

    @Test
    void rejectsLengthsThatCannotBeRepresentedOrFitInRemainingBytes(CapturedOutput output) {
        for (String length : new String[] {"2147483647", "2147483648", "4294967296", "9223372036854775808"}) {
            assertThat(sanitize(item(length, "{}"))).as("length %s", length).isEmpty();
        }
        assertThat(output.getAll()).doesNotContain("IndexOutOfBoundsException");
    }

    @Test
    void rejectsFractionalAndNegativeLengths() {
        assertThat(sanitize(item("1.5", "{}"))).isEmpty();
        assertThat(sanitize(item("2.000000000000000001", "{}"))).isEmpty();
        assertThat(sanitize(item("-1", "{}"))).isEmpty();
        assertThat(sanitize(bytes("{}\n{\"type\":\"event\",\"length\":\"1\"}\n{}"))).isEmpty();
    }

    @Test
    void acceptsPayloadWhoseLengthExactlyMatchesRemainingBytesWithoutTrailingNewline() {
        byte[] envelope = bytes("{}\n{\"type\":\"event\",\"length\":" + utf8(EVENT).length + "}\n" + EVENT);

        assertThat(sanitize(envelope)).isPresent();
    }

    @Test
    void usesUtf8ByteLengthAndKeepsMultipleEventsWhileDroppingUnsupportedItems() {
        String unicodeEvent = "{\"level\":\"error\",\"message\":\"é€\"}";
        byte[] envelope = bytes("{}\n"
            + "{\"type\":\"session\",\"length\":2}\n{}\n"
            + "{\"type\":\"event\",\"length\":" + utf8(unicodeEvent).length + "}\n" + unicodeEvent + "\n"
            + "{\"type\":\"event\"}\n" + EVENT);

        Optional<byte[]> sanitized = sanitize(envelope);

        assertThat(sanitized).isPresent();
        String result = new String(sanitized.orElseThrow(), StandardCharsets.UTF_8);
        assertThat(result).contains("\"dsn\":\"https://configured.example/42\"")
            .contains("\"level\":\"error\"").doesNotContain("session");
    }

    @Test
    void rejectsTruncatedLengthPayloadInsteadOfForwardingEarlierEvents() {
        byte[] envelope = bytes("{}\n"
            + "{\"type\":\"event\"}\n" + EVENT + "\n"
            + "{\"type\":\"event\",\"length\":100}\n{}");

        assertThat(sanitize(envelope)).isEmpty();
    }

    private static Optional<byte[]> sanitize(byte[] envelope) {
        return TelemetryEnvelopeSanitizer.sanitize(envelope, "https://configured.example/42", MAPPER);
    }

    private static byte[] item(String length, String payload) {
        return bytes("{}\n{\"type\":\"event\",\"length\":" + length + "}\n" + payload);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String value) {
        return bytes(value);
    }
}
