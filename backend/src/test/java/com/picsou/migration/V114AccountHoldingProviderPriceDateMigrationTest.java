package com.picsou.migration;

import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies V114 preserves existing holdings and stores the bank valuation date as a DATE. */
@RequiresPostgres
class V114AccountHoldingProviderPriceDateMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @BeforeAll
    static void migrateToV113() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("113")
            .load()
            .migrate();
    }

    private static long newMember() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES);
             PreparedStatement st = c.prepareStatement(
                 "INSERT INTO family_member (display_name) VALUES (?) RETURNING id")) {
            st.setString(1, "before-v114");
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long newPerAccount(long memberId) throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES);
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
        try (Connection c = PostgresContainers.connection(POSTGRES);
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
    void upgradesV113PreservesHoldingAndPersistsNullableNavDate() throws SQLException {
        long accountId = newPerAccount(newMember());
        long holdingId = insertHolding(accountId);
        Flyway flyway = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("114")
            .load();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("113");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("114");
        assertHolding(holdingId, null);

        LocalDate navDate = LocalDate.of(2026, 10, 12);
        try (Connection c = PostgresContainers.connection(POSTGRES);
             PreparedStatement st = c.prepareStatement(
                 "UPDATE account_holding SET provider_price_date = ? WHERE id = ?")) {
            st.setDate(1, java.sql.Date.valueOf(navDate));
            st.setLong(2, holdingId);
            assertThat(st.executeUpdate()).isEqualTo(1);
        }
        assertHolding(holdingId, navDate);
    }

    private static void assertHolding(long holdingId, LocalDate expectedNavDate) throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES);
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
