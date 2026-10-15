package com.picsou.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies V113 preserves existing holdings and stores the bank valuation date as a DATE. */
@Testcontainers
@EnabledIf("dockerAvailable")
class V113AccountHoldingProviderPriceDateMigrationTest {

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
                "PICSOU_REQUIRE_DOCKER_TESTS is set but the V113 migration test cannot be skipped.");
        }
        return available;
    }

    @BeforeAll
    static void migrateToV112() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("112")
            .load()
            .migrate();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long newMember() throws SQLException {
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "INSERT INTO family_member (display_name) VALUES (?) RETURNING id")) {
            st.setString(1, "before-v113");
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long newPerAccount(long memberId) throws SQLException {
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "INSERT INTO account (member_id, name, type, provider, currency, current_balance, is_manual, color) "
                     + "VALUES (?, ?, 'PER', ?, 'EUR', ?, FALSE, '#123456') RETURNING id")) {
            st.setLong(1, memberId);
            st.setString(2, "Synthetic PER");
            st.setString(3, "Caisse d'Epargne");
            st.setBigDecimal(4, new BigDecimal("200.00"));
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long insertHolding(long accountId) throws SQLException {
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "INSERT INTO account_holding (account_id, ticker, quantity, provider_value_eur) "
                     + "VALUES (?, ?, ?, ?) RETURNING id")) {
            st.setLong(1, accountId);
            st.setString(2, "SYNTHETIC");
            st.setBigDecimal(3, new BigDecimal("2"));
            st.setBigDecimal(4, new BigDecimal("200.00"));
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void upgradesV112PreservesHoldingAndPersistsNullableNavDate() throws SQLException {
        long accountId = newPerAccount(newMember());
        long holdingId = insertHolding(accountId);
        Flyway flyway = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("113")
            .load();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("112");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("113");
        assertHolding(holdingId, null);

        LocalDate navDate = LocalDate.of(2026, 10, 12);
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "UPDATE account_holding SET provider_price_date = ? WHERE id = ?")) {
            st.setDate(1, java.sql.Date.valueOf(navDate));
            st.setLong(2, holdingId);
            assertThat(st.executeUpdate()).isEqualTo(1);
        }
        assertHolding(holdingId, navDate);
    }

    private static void assertHolding(long holdingId, LocalDate expectedNavDate) throws SQLException {
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "SELECT provider_value_eur, provider_price_date FROM account_holding WHERE id = ?")) {
            st.setLong(1, holdingId);
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal("provider_value_eur")).isEqualByComparingTo("200.00");
                java.sql.Date storedDate = rs.getDate("provider_price_date");
                assertThat(storedDate == null ? null : storedDate.toLocalDate()).isEqualTo(expectedNavDate);
                assertThat(rs.next()).isFalse();
            }
        }
    }
}
