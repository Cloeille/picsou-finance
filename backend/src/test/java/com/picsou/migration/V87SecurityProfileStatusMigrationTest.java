package com.picsou.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V87 rewrites existing rows, so it gets a Testcontainers test by the letter of the convention.
 *
 * <p>What it has to get right is the repair. The old {@code refresh()} emptied a profile on any
 * provider failure and stamped {@code refreshed_at} regardless, so an emptied row is
 * indistinguishable from one that was never resolved — and both were locked out of retry for
 * thirty days by a timestamp neither had earned. The migration must put those rows back in the
 * queue while leaving genuinely resolved ones alone.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V87SecurityProfileStatusMigrationTest {

    static {
        System.setProperty("api.version", "1.44");
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && System.getenv("PICSOU_REQUIRE_DOCKER_TESTS") != null) {
            throw new IllegalStateException("Docker is required but unavailable");
        }
        return available;
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
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        migrateTo("86");
        try (Connection c = connection(); Statement st = c.createStatement()) {
            // A share that resolved.
            st.execute("INSERT INTO security_profile (id, ticker, asset_type, sector_key, refreshed_at) "
                + "VALUES (1, 'AI.PA', 'STOCK', 'basic_materials', NOW() - INTERVAL '2 days')");
            // A fund that resolved: its data lives in slices, not in sector_key.
            st.execute("INSERT INTO security_profile (id, ticker, asset_type, source, refreshed_at) "
                + "VALUES (2, 'CW8.PA', 'ETF', 'Boursorama', NOW() - INTERVAL '2 days')");
            st.execute("INSERT INTO security_composition_slice (profile_id, kind, label, percent) "
                + "VALUES (2, 'COUNTRY', 'US', 69.700)");
            // The casualty: emptied by a failed scrape, then stamped as if it had succeeded.
            st.execute("INSERT INTO security_profile (id, ticker, asset_type, source, refreshed_at) "
                + "VALUES (3, 'ESE.PA', 'ETF', 'Boursorama', NOW() - INTERVAL '2 days')");
            st.execute("SELECT setval('security_profile_id_seq', 3)");
        }
    }

    @Test
    @Order(1)
    void aResolvedProfileIsMarkedOkAndKeepsItsTimestamp() throws SQLException {
        migrateTo("87");

        try (Connection c = connection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT ticker, status, refreshed_at, last_attempt_at FROM security_profile "
                     + "WHERE ticker IN ('AI.PA', 'CW8.PA') ORDER BY ticker")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("ticker")).isEqualTo("AI.PA");
            assertThat(rs.getString("status")).isEqualTo("OK");
            assertThat(rs.getTimestamp("refreshed_at")).isNotNull();
            assertThat(rs.getTimestamp("last_attempt_at")).isNotNull();

            assertThat(rs.next()).isTrue();
            // Its data is in slices, so the migration must look there too — reading sector_key
            // alone would have condemned every correctly-resolved fund as empty.
            assertThat(rs.getString("ticker")).isEqualTo("CW8.PA");
            assertThat(rs.getString("status")).isEqualTo("OK");
        }
    }

    @Test
    @Order(2)
    void anEmptiedProfileIsPutBackInTheQueue() throws SQLException {
        try (Connection c = connection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT status, refreshed_at, last_attempt_at FROM security_profile "
                     + "WHERE ticker = 'ESE.PA'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("NEVER_FETCHED");
            // Clearing the timestamp is the repair: refreshStale reads NULL as due, so the fund
            // is looked up on the next pass instead of waiting out a month it never earned.
            assertThat(rs.getTimestamp("refreshed_at")).isNull();
            assertThat(rs.getTimestamp("last_attempt_at")).isNull();
        }
    }

    @Test
    @Order(3)
    void anUnknownStatusIsRefused() throws SQLException {
        try (Connection c = connection(); Statement st = c.createStatement()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.execute(
                "UPDATE security_profile SET status = 'PROBABLY' WHERE ticker = 'AI.PA'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_security_profile_status");
        }
    }
}
