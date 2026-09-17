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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V86 adds no data, but it drops a NOT NULL — and that nullability is load-bearing rather than
 * cosmetic.
 *
 * <p>A sync now seeds a profile that carries an ISIN and nothing else. {@code refreshed_at} must
 * be NULL on such a row, because NULL is precisely what {@code SecurityProfileService.refreshStale}
 * reads as "due". Had the column stayed NOT NULL, seeding would have needed a sentinel timestamp,
 * which the 30-day cutoff would then read as freshly resolved — and the row would never be looked
 * up at all.
 *
 * <p>The ISIN CHECK is asserted too: it is the only thing stopping a malformed identifier from
 * reaching a provider as though it were real.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V86SecurityProfileIsinMigrationTest {

    static {
        // Same pin as the other migration tests: docker-java otherwise negotiates down to an API
        // version modern Engines refuse, and it surfaces as an ordinary "no Docker" skip.
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
        migrateTo("85");
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO security_profile (ticker, asset_type, sector_key, refreshed_at) "
                + "VALUES ('AI.PA', 'STOCK', 'basic_materials', NOW())");
        }
    }

    @Test
    @Order(1)
    void beforeTheMigrationAProfileCannotBeSeededWithoutATimestamp() throws SQLException {
        try (Connection c = connection(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.execute(
                "INSERT INTO security_profile (ticker, asset_type) VALUES ('CW8.PA', 'UNKNOWN')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("refreshed_at");
        }
    }

    @Test
    @Order(2)
    void afterTheMigrationAnIsinOnlyRowIsSeedableAndReadsAsNeverResolved() throws SQLException {
        migrateTo("86");

        try (Connection c = connection(); Statement st = c.createStatement()) {
            assertThatCode(() -> st.execute(
                "INSERT INTO security_profile (ticker, asset_type, isin) "
                    + "VALUES ('CW8.PA', 'UNKNOWN', 'LU1681043599')"))
                .doesNotThrowAnyException();

            try (ResultSet rs = st.executeQuery(
                "SELECT isin, refreshed_at FROM security_profile WHERE ticker = 'CW8.PA'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("isin")).isEqualTo("LU1681043599");
                assertThat(rs.getTimestamp("refreshed_at")).isNull();
            }

            // The pre-existing row is untouched: this migration adds a column, it does not
            // reinterpret anything already resolved.
            try (ResultSet rs = st.executeQuery(
                "SELECT isin, refreshed_at FROM security_profile WHERE ticker = 'AI.PA'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("isin")).isNull();
                assertThat(rs.getTimestamp("refreshed_at")).isNotNull();
            }
        }
    }

    @Test
    @Order(3)
    void aMalformedIsinIsRefused() throws SQLException {
        try (Connection c = connection(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.execute(
                "INSERT INTO security_profile (ticker, asset_type, isin) "
                    + "VALUES ('JUNK', 'UNKNOWN', 'not-an-isin')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_security_profile_isin");
        }
    }

    @Test
    @Order(4)
    void twoTickersMaySharePlainlyOneIsin() throws SQLException {
        // Deliberately not UNIQUE. OpenFIGI can map two tickers onto one ISIN, and a constraint
        // violation here would fail an entire sync over reference data nobody asked for.
        try (Connection c = connection(); Statement st = c.createStatement()) {
            assertThatCode(() -> st.execute(
                "INSERT INTO security_profile (ticker, asset_type, isin) "
                    + "VALUES ('CW8.MI', 'UNKNOWN', 'LU1681043599')"))
                .doesNotThrowAnyException();
        }
    }
}
