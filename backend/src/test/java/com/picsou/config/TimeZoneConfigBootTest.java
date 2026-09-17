package com.picsou.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The part of {@link TimeZoneConfig} that unit tests cannot reach: whether the listener actually
 * fires late enough to see {@code app.timezone}.
 *
 * <p>It listens on {@code ApplicationEnvironmentPreparedEvent}, the same event Spring Boot's own
 * config-data processing uses to load {@code application.yml}. Ordering decides which runs first,
 * and getting it wrong is silent — the listener would read nothing, fall back to the default, and
 * every assertion about the default zone would still pass while an operator's {@code APP_TIMEZONE}
 * was ignored. So this boots a real (tiny, non-web, no-DB) application and checks the JVM default
 * afterwards.
 *
 * <p>Deliberately not {@code @SpringBootTest}: that would find {@link com.picsou.PicsouApplication}
 * and drag in JPA, Flyway and the whole security chain to verify one {@code TimeZone.setDefault}.
 */
class TimeZoneConfigBootTest {

    private TimeZone originalDefault;
    private String originalUserTimezone;

    @BeforeEach
    void rememberJvmZone() {
        originalDefault = TimeZone.getDefault();
        originalUserTimezone = System.getProperty("user.timezone");
        // Start somewhere the assertions cannot pass by accident — this is the zone the published
        // image ran in before the fix, and the one CI runs in.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restoreJvmZone() {
        TimeZone.setDefault(originalDefault);
        if (originalUserTimezone == null) {
            System.clearProperty("user.timezone");
        } else {
            System.setProperty("user.timezone", originalUserTimezone);
        }
    }

    private ConfigurableApplicationContext boot(String... properties) {
        return new SpringApplicationBuilder(EmptyConfiguration.class)
            .web(WebApplicationType.NONE)
            .bannerMode(org.springframework.boot.Banner.Mode.OFF)
            .listeners(new TimeZoneConfig())
            .properties(properties)
            .run();
    }

    @Test
    void picksUpTheApplicationYmlDefault_notWhateverZoneTheHostRunsIn() {
        try (ConfigurableApplicationContext context = boot()) {
            assertThat(ZoneId.systemDefault()).isEqualTo(ZoneId.of("Europe/Paris"));
        }
    }

    @Test
    void anOperatorOverrideWins() {
        try (ConfigurableApplicationContext context = boot("--" + TimeZoneConfig.PROPERTY + "=America/New_York")) {
            assertThat(ZoneId.systemDefault()).isEqualTo(ZoneId.of("America/New_York"));
            assertThat(System.getProperty("user.timezone")).isEqualTo("America/New_York");
        }
    }

    /** No beans: the zone is pinned before the context is created, so there is nothing to wire. */
    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
