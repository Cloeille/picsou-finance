package com.picsou.migration;

import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@RequiresPostgres
class V156ResetIntegrationOffSwitchesMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @Test
    void removesOffSwitchesOnly() throws SQLException {
        PostgresContainers.migrateTo(POSTGRES, "155");
        try (Connection c = PostgresContainers.connection(POSTGRES); Statement st = c.createStatement()) {
            // V25 seeded integration.crypto.enabled = 'false'.
            st.executeUpdate("UPDATE app_setting SET value = 'FALSE' WHERE setting_key = 'integration.finary.enabled'");
            st.executeUpdate("INSERT INTO app_setting (setting_key, value) VALUES "
                + "('integration.lbp.enabled', 'true'), ('integration.fortuneo.enabled', 'TRUE'), "
                + "('integration.notes', 'false') "
                + "ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value");
        }

        PostgresContainers.migrateTo(POSTGRES, "156");

        Map<String, String> settings = new HashMap<>();
        try (Connection c = PostgresContainers.connection(POSTGRES); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT setting_key, value FROM app_setting")) {
            while (rs.next()) {
                settings.put(rs.getString(1), rs.getString(2));
            }
        }
        assertThat(settings)
            .doesNotContainKeys("integration.crypto.enabled", "integration.finary.enabled")
            .containsEntry("integration.lbp.enabled", "true")
            .containsEntry("integration.fortuneo.enabled", "TRUE")
            .containsEntry("integration.notes", "false");
    }
}
