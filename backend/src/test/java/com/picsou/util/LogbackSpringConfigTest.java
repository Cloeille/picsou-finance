package com.picsou.util;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads {@code logback-spring.xml} through Spring Boot's logging system, the way the application
 * does, and checks what reaches the console and the log file.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogbackSpringConfigTest {

    private static Logger initializedLogger() {
        return initializedLogger(new MockEnvironment());
    }

    private static Logger initializedLogger(MockEnvironment environment) {
        LoggingSystem system = LoggingSystem.get(LogbackSpringConfigTest.class.getClassLoader());
        // initialize() is a no-op on an already-initialised context until cleanUp() clears the marker.
        system.cleanUp();
        system.beforeInitialize();
        system.initialize(new LoggingInitializationContext(environment), "classpath:logback-spring.xml",
            LogFile.get(environment));
        return LoggerFactory.getLogger("com.picsou.logback-spring-test");
    }

    private static boolean rootHasAppender(String name) {
        return ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(Logger.ROOT_LOGGER_NAME).getAppender(name) != null;
    }

    @AfterEach
    void forgetTheLogFile() {
        System.clearProperty("LOG_FILE");
        System.clearProperty("LOG_PATH");
        initializedLogger();
    }

    @Test
    void aForgedLineInTheMessageStaysOnTheSameLine(CapturedOutput output) {
        initializedLogger().warn("Lookup failed for {}", "AAPL\n2026-01-01T00:00:00.000Z  INFO 1 --- forged");

        assertThat(output.getOut())
            .contains("Lookup failed for AAPL?2026-01-01T00:00:00.000Z  INFO 1 --- forged")
            .doesNotContain("\n2026-01-01T00:00:00.000Z  INFO 1 --- forged");
    }

    @Test
    void terminalEscapesAreNeutralized(CapturedOutput output) {
        initializedLogger().warn("ticker {}", "\u001B[2Jwiped");

        assertThat(output.getOut()).contains("ticker ?[2Jwiped").doesNotContain("\u001B[2J");
    }

    @Test
    void exceptionMessagesAreNeutralized_butTheStackTraceStaysMultiLine(CapturedOutput output) {
        initializedLogger().error("Upstream call failed",
            new IllegalStateException("bad\r\nERROR forged", new RuntimeException("cause forged")));

        assertThat(output.getOut())
            .contains("java.lang.IllegalStateException: bad?ERROR forged")
            .contains("Caused by: java.lang.RuntimeException: cause?forged")
            .contains("\tat com.picsou.util.LogbackSpringConfigTest.");
    }

    @Test
    void keepsSpringBootsConsoleLayout(CapturedOutput output) {
        initializedLogger().info("plain message");

        assertThat(output.getOut()).containsPattern(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\S*\\s+INFO \\d+ --- .*com\\.picsou\\.logback-spring-test\\s*: plain message");
    }

    @Test
    void writesNoLogFileUnlessOneIsConfigured() {
        initializedLogger().info("console only");

        assertThat(rootHasAppender("CONSOLE")).isTrue();
        assertThat(rootHasAppender("FILE")).isFalse();
    }

    @Test
    void theConfiguredLogFileGetsTheSameNeutralisedOutput(@TempDir Path dir, CapturedOutput output) throws IOException {
        Path file = dir.resolve("picsou.log");
        MockEnvironment environment = new MockEnvironment().withProperty("logging.file.name", file.toString());

        initializedLogger(environment).warn("Lookup failed for {}", "AAPL\nERROR forged\tcolumn\u001B[2J");

        assertThat(rootHasAppender("FILE")).isTrue();
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
            .contains("Lookup failed for AAPL?ERROR forged\tcolumn?[2J")
            .doesNotContain("\nERROR forged")
            .containsPattern("\\d{4}-\\d{2}-\\d{2}T\\S+\\s+WARN \\d+ --- ");
        assertThat(output.getOut()).contains("Lookup failed for AAPL?ERROR forged\tcolumn?[2J");
    }
}
