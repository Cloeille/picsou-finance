package com.picsou.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SafeRedirectTest {
    @ParameterizedTest
    @ValueSource(strings = {"/", "/accounts", "/settings?resume=export", "/oauth2/authorize?state=x", "/path#section"})
    void acceptsLocalPaths(String path) {
        assertThat(SafeRedirect.sanitize(path, "/fallback")).isEqualTo(path);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https://evil.test", "//evil.test", "/\\evil.test", "relative", "/x\r\nLocation: evil", "/x\u0000", "/x\u007f"})
    void rejectsUnsafeTargets(String path) {
        assertThat(SafeRedirect.sanitize(path, "/fallback")).isEqualTo("/fallback");
    }

    @Test
    void limitsLengthTo2048Characters() {
        assertThat(SafeRedirect.sanitize("/" + "a".repeat(2047), "/fallback")).hasSize(2048);
        assertThat(SafeRedirect.sanitize("/" + "a".repeat(2048), "/fallback")).isEqualTo("/fallback");
    }
}
