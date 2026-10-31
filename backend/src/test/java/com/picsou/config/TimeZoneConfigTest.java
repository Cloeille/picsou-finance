package com.picsou.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TimeZoneConfig} is the single place the application's civil zone is chosen, so these tests
 * pin the three things the rest of the codebase silently depends on: the default when nothing is
 * configured, that the JVM default actually moves (every {@code now()} and both price adapters read
 * it), and that a typo fails loudly instead of quietly dating snapshots in the wrong zone.
 */
class TimeZoneConfigTest {

    private TimeZone originalDefault;
    private String originalUserTimezone;

    @BeforeEach
    void rememberJvmZone() {
        originalDefault = TimeZone.getDefault();
        originalUserTimezone = System.getProperty("user.timezone");
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

    @Test
    void defaultsToEuropeParis_whenThePropertyIsAbsent() {
        assertThat(TimeZoneConfig.resolve(new MockEnvironment())).isEqualTo("Europe/Paris");
    }

    @Test
    void defaultsToEuropeParis_whenThePropertyIsBlank() {
        // An unset APP_TIMEZONE in a compose file expands to an empty string rather than to nothing,
        // so a blank value is the shape a "left it alone" deployment actually produces.
        MockEnvironment environment = new MockEnvironment().withProperty(TimeZoneConfig.PROPERTY, "   ");

        assertThat(TimeZoneConfig.resolve(environment)).isEqualTo("Europe/Paris");
    }

    @Test
    void readsTheConfiguredZone() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(TimeZoneConfig.PROPERTY, "America/New_York");

        assertThat(TimeZoneConfig.resolve(environment)).isEqualTo("America/New_York");
    }

    @Test
    void pinMovesTheJvmDefault_soEveryNowCallFollowsIt() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        TimeZoneConfig.pin("Europe/Paris");

        assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(ZoneId.systemDefault()).isEqualTo(ZoneId.of("Europe/Paris"));
    }

    @Test
    void pinAlsoSetsUserTimezone_soTheTwoCannotDisagree() {
        TimeZoneConfig.pin("Asia/Tokyo");

        assertThat(System.getProperty("user.timezone")).isEqualTo("Asia/Tokyo");
    }

    @Test
    void pinToleratesSurroundingWhitespace() {
        assertThat(TimeZoneConfig.pin("  Europe/Paris  ")).isEqualTo(ZoneId.of("Europe/Paris"));
    }

    @Test
    void rejectsAnUnknownZone_namingThePropertyToFix() {
        assertThatThrownBy(() -> TimeZoneConfig.parse("Europe/Pariss"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(TimeZoneConfig.PROPERTY)
            .hasMessageContaining("Europe/Pariss");
    }

    @Test
    void rejectsAPosixTzStringThatIsNotAZoneId() {
        // TZ=CEST-2 is a legal value for the `TZ` env var but not a ZoneId. Failing here is the
        // point: it means the container's TZ and app.timezone have drifted apart.
        assertThatThrownBy(() -> TimeZoneConfig.parse("CEST-2"))
            .isInstanceOf(IllegalStateException.class);
    }

}
