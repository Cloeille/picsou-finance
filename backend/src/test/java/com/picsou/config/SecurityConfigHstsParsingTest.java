package com.picsou.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code HSTS_ENABLED} gates two independent emitters — nginx (via the snippet
 * {@code docker/entrypoint.sh} writes) and Spring Security ({@link SecurityConfig}). They parse the
 * same env var with two hand-written parsers, one in Bash and one in Java.
 *
 * <p>If those parsers diverge, a value like {@code on} enables one emitter and not the other. The
 * visible result is a half-enabled deployment, and on a locally-issued certificate that is the
 * browser lockout the gate exists to prevent — with no build or test failure to flag it. These
 * tests make the coupling enforceable instead of a comment.
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

    /**
     * The load-bearing one: read the truthy tokens straight out of {@code docker/entrypoint.sh}
     * and assert they are exactly {@link SecurityConfig#HSTS_TRUTHY}. Editing one side without the
     * other now fails the build.
     */
    @Test
    void javaAndShellParsersAcceptExactlyTheSameTokens() throws IOException {
        Path entrypoint = Path.of("..", "docker", "entrypoint.sh");
        assumeTrue(Files.exists(entrypoint), "docker/entrypoint.sh not reachable from this module");

        String script = Files.readString(entrypoint, StandardCharsets.UTF_8);

        // The enabling arm looks like:   true|1|yes|on)
        Matcher m = Pattern.compile("^\\s*([a-z0-9|]+)\\)\\s*$", Pattern.MULTILINE).matcher(script);
        Set<String> shellTokens = null;
        while (m.find()) {
            Set<String> candidate = new LinkedHashSet<>(Arrays.asList(m.group(1).split("\\|")));
            if (candidate.contains("true")) {
                shellTokens = candidate;
                break;
            }
        }

        assertThat(shellTokens)
            .as("could not locate the truthy case arm in docker/entrypoint.sh — "
                + "if its shape changed, update this test too")
            .isNotNull();
        assertThat(shellTokens)
            .as("docker/entrypoint.sh and SecurityConfig.HSTS_TRUTHY must accept the same tokens, "
                + "or nginx and Spring Security disagree about whether to send HSTS")
            .isEqualTo(SecurityConfig.HSTS_TRUTHY);
    }
}
