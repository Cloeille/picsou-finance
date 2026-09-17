package com.picsou.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    @Test
    void fingerprint_isTheSha256Prefix_notJustAnyDigest() throws NoSuchAlgorithmException {
        // Pin the actual computation, not just the shape: a regression that swapped
        // the algorithm (MD5) or the truncation offset would still satisfy the
        // format-only assertions, but not this.
        String input = "eb-session-fixture-2026";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        String expected = "sha256:" + HexFormat.of().formatHex(digest, 0, 4);

        assertThat(LogSanitizer.fingerprint(input)).isEqualTo(expected);
    }

    @Test
    void fingerprint_neverContainsTheRawValue() {
        String sessionId = "b3f1c0de-2a4e-4f6a-9c8d-0123456789ab-super-secret";

        String out = LogSanitizer.fingerprint(sessionId);

        assertThat(out).doesNotContain(sessionId);
        assertThat(out).startsWith("sha256:");
    }

    @Test
    void fingerprint_isStableForTheSameInput() {
        assertThat(LogSanitizer.fingerprint("session-abc"))
            .isEqualTo(LogSanitizer.fingerprint("session-abc"));
    }

    @Test
    void fingerprint_differsForDifferentInputs() {
        assertThat(LogSanitizer.fingerprint("session-abc"))
            .isNotEqualTo(LogSanitizer.fingerprint("session-xyz"));
    }

    @Test
    void fingerprint_hasCompactFixedShape() {
        // "sha256:" (7) + 8 hex chars = 15 chars, regardless of input length.
        assertThat(LogSanitizer.fingerprint("short")).hasSize(15);
        assertThat(LogSanitizer.fingerprint("a-considerably-longer-session-identifier-value"))
            .hasSize(15)
            .matches("sha256:[0-9a-f]{8}");
    }

    @Test
    void fingerprint_returnsPlaceholderForNullOrBlank() {
        assertThat(LogSanitizer.fingerprint(null)).isEqualTo("<none>");
        assertThat(LogSanitizer.fingerprint("")).isEqualTo("<none>");
        assertThat(LogSanitizer.fingerprint("   ")).isEqualTo("<none>");
    }
}
