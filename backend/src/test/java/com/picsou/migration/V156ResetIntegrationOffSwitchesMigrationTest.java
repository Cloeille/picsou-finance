package com.picsou.migration;

import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V156 removes every stored integration off switch, so no install stops syncing on upgrade (#666). */
@RequiresPostgres
class V156ResetIntegrationOffSwitchesMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @BeforeAll
    static void seedV155AndMigrate() throws SQLException {
        PostgresContainers.migrateTo(POSTGRES, "155");
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            // V25, V62 and V95 seeded seven integrations as 'false'; vary the case and add a few.
            upsert(c, "integration.boursobank.enabled", "FALSE");
            upsert(c, "integration.boursedirect.enabled", "false");
            upsert(c, "integration.fortuneo.enabled", "true");
            upsert(c, "integration.lbp.enabled", "TRUE");
            upsert(c, "cors.allowed-origins", "false");
            upsert(c, "integration.notes", "false");
        }
        PostgresContainers.migrateTo(POSTGRES, "156");
    }

    @Test
    void removesEveryStoredOffSwitch() throws SQLException {
        assertThat(settings()).doesNotContainKeys(
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

    private static void upsert(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                 "INSERT INTO app_setting (setting_key, value) VALUES (?, ?) "
                     + "ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value")) {
            st.setString(1, key);
            st.setString(2, value);
            st.executeUpdate();
        }
    }

    private static Map<String, String> settings() throws SQLException {
        Map<String, String> settings = new HashMap<>();
        try (Connection c = PostgresContainers.connection(POSTGRES);
             PreparedStatement st = c.prepareStatement("SELECT setting_key, value FROM app_setting");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                settings.put(rs.getString(1), rs.getString(2));
            }
        }
        return settings;
    }
}
