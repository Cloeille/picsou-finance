package com.picsou.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads {@code logback-spring.xml} through Spring Boot's logging system, the way the application
 * does, and checks what reaches the console.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogbackSpringConfigTest {

    private static Logger initializedLogger() {
        LoggingSystem system = LoggingSystem.get(LogbackSpringConfigTest.class.getClassLoader());
        system.beforeInitialize();
        system.initialize(new LoggingInitializationContext(new MockEnvironment()), "classpath:logback-spring.xml", null);
        return LoggerFactory.getLogger("com.picsou.logback-spring-test");
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
}
