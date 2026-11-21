package com.picsou.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V156 removes every stored integration off switch, so no install stops syncing on upgrade (#666). */
@Testcontainers
@EnabledIf("dockerAvailable")
class V156ResetIntegrationOffSwitchesMigrationTest {

    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Container
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found. "
                    + "The V156 migration test cannot be skipped. Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    @BeforeAll
    static void migrate() throws SQLException {
        migrateTo("155");
        // V25, V62 and V95 seeded seven integrations as 'false'; vary the case and add a few.
        setValue("integration.boursobank.enabled", "FALSE");
        upsert("integration.fortuneo.enabled", "true");
        upsert("integration.lbp.enabled", "TRUE");
        upsert("integration.boursedirect.enabled", "false");
        upsert("cors.allowed-origins", "false");
        upsert("integration.notes", "false");
        migrateTo("156");
    }

    @Test
    void removesEveryStoredOffSwitch() throws SQLException {
        Map<String, String> settings = settings();

        assertThat(settings).doesNotContainKeys(
            "integration.enablebanking.enabled",
            "integration.boursobank.enabled",
            "integration.traderepublic.enabled",
            "integration.finary.enabled",
            "integration.crypto.enabled",
            "integration.boursedirect.enabled");
    }

    @Test
    void keepsTheSwitchesStoredOn() throws SQLException {
        assertThat(settings())
            .containsEntry("integration.fortuneo.enabled", "true")
            .containsEntry("integration.lbp.enabled", "TRUE");
    }

    @Test
    void leavesOtherSettingsAlone() throws SQLException {
        assertThat(settings())
            .containsEntry("cors.allowed-origins", "false")
            .containsEntry("integration.notes", "false")
            .containsKey("setup.state");
    }

    private static void migrateTo(String version) {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(version)
            .load()
            .migrate();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void setValue(String key, String value) throws SQLException {
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "UPDATE app_setting SET value = ? WHERE setting_key = ?")) {
            st.setString(1, value);
            st.setString(2, key);
            assertThat(st.executeUpdate()).isEqualTo(1);
        }
    }

    private static void upsert(String key, String value) throws SQLException {
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "INSERT INTO app_setting (setting_key, value) VALUES (?, ?) "
                     + "ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value")) {
            st.setString(1, key);
            st.setString(2, value);
            st.executeUpdate();
        }
    }

    private static Map<String, String> settings() throws SQLException {
        Map<String, String> settings = new HashMap<>();
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement("SELECT setting_key, value FROM app_setting");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                settings.put(rs.getString(1), rs.getString(2));
            }
        }
        return settings;
    }
}
