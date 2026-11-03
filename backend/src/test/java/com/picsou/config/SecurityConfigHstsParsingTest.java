package com.picsou.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code HSTS_ENABLED} is parsed by Spring only: the nginx snippet and its shell parser are gone,
 * so there is no second parser to drift from. These tests pin the accepted tokens and the
 * startup warning rule.
 */
class SecurityConfigHstsParsingTest {

    @Test
    void acceptsTheDocumentedTruthyTokens() {
        assertThat(SecurityConfig.parseHstsEnabled("true")).isTrue();
        assertThat(SecurityConfig.parseHstsEnabled("1")).isTrue();
        assertThat(SecurityConfig.parseHstsEnabled("yes")).isTrue();
        assertThat(SecurityConfig.parseHstsEnabled("on")).isTrue();
    }

    @Test
    void isCaseInsensitiveAndToleratesSurroundingWhitespace() {
        assertThat(SecurityConfig.parseHstsEnabled("True")).isTrue();
        assertThat(SecurityConfig.parseHstsEnabled("TRUE")).isTrue();
        assertThat(SecurityConfig.parseHstsEnabled("  true  ")).isTrue();
        assertThat(SecurityConfig.parseHstsEnabled("\ttrue\n")).isTrue();
    }

    /**
     * Interior whitespace must NOT be squashed. {@code entrypoint.sh} deliberately trims only the
     * surrounding kind so a typo falls through to its warning rather than silently enabling HSTS.
     */
    @Test
    void doesNotAcceptInteriorWhitespaceOrUnknownValues() {
        assertThat(SecurityConfig.parseHstsEnabled("t rue")).isFalse();
        assertThat(SecurityConfig.parseHstsEnabled("enabled")).isFalse();
        assertThat(SecurityConfig.parseHstsEnabled("false")).isFalse();
        assertThat(SecurityConfig.parseHstsEnabled("0")).isFalse();
    }

    /** A bare {@code HSTS_ENABLED=} must be falsy, never an exception — see the field javadoc. */
    @Test
    void treatsEmptyAndNullAsDisabled() {
        assertThat(SecurityConfig.parseHstsEnabled("")).isFalse();
        assertThat(SecurityConfig.parseHstsEnabled("   ")).isFalse();
        assertThat(SecurityConfig.parseHstsEnabled(null)).isFalse();
    }

    @Test
    void warnsOnlyForValuesThatAreNeitherTruthyNorExplicitlyOff() {
        assertThat(SecurityConfig.isUnrecognisedHsts("enabled")).isTrue();
        assertThat(SecurityConfig.isUnrecognisedHsts("t rue")).isTrue();
        assertThat(SecurityConfig.isUnrecognisedHsts("true")).isFalse();
        assertThat(SecurityConfig.isUnrecognisedHsts(" OFF ")).isFalse();
        assertThat(SecurityConfig.isUnrecognisedHsts("no")).isFalse();
        assertThat(SecurityConfig.isUnrecognisedHsts("0")).isFalse();
        assertThat(SecurityConfig.isUnrecognisedHsts("")).isFalse();
        assertThat(SecurityConfig.isUnrecognisedHsts("   ")).isFalse();
        assertThat(SecurityConfig.isUnrecognisedHsts(null)).isFalse();
    }
}
