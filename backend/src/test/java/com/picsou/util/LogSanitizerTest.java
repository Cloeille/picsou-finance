package com.picsou.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

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
