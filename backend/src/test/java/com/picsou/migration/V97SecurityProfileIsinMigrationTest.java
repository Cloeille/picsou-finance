package com.picsou.migration;

import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V97 adds no data, but it drops a NOT NULL — and that nullability is load-bearing rather than
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
@RequiresPostgres
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V97SecurityProfileIsinMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        PostgresContainers.migrateTo(POSTGRES, "96");
        try (Connection c = PostgresContainers.connection(POSTGRES); Statement st = c.createStatement()) {
            st.execute("INSERT INTO security_profile (ticker, asset_type, sector_key, refreshed_at) "
                + "VALUES ('AI.PA', 'STOCK', 'basic_materials', NOW())");
        }
    }

    @Test
    @Order(1)
    void beforeTheMigrationAProfileCannotBeSeededWithoutATimestamp() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.execute(
                "INSERT INTO security_profile (ticker, asset_type) VALUES ('CW8.PA', 'UNKNOWN')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("refreshed_at");
        }
    }

    @Test
    @Order(2)
    void afterTheMigrationAnIsinOnlyRowIsSeedableAndReadsAsNeverResolved() throws SQLException {
        PostgresContainers.migrateTo(POSTGRES, "97");

        try (Connection c = PostgresContainers.connection(POSTGRES); Statement st = c.createStatement()) {
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
        try (Connection c = PostgresContainers.connection(POSTGRES); Statement st = c.createStatement()) {
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
        try (Connection c = PostgresContainers.connection(POSTGRES); Statement st = c.createStatement()) {
            assertThatCode(() -> st.execute(
                "INSERT INTO security_profile (ticker, asset_type, isin) "
                    + "VALUES ('CW8.MI', 'UNKNOWN', 'LU1681043599')"))
                .doesNotThrowAnyException();
        }
    }
}
