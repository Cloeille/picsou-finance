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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves a database already at V110 upgrades in place to carry the card nature, keeps its rows
 * and refuses a value outside the three known natures. Every statement is a constant with bound
 * parameters: no value is concatenated into SQL.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class V111AccountCardNatureMigrationTest {

    private static final String INSERT_ACCOUNT =
        "INSERT INTO account (name, type, provider, currency, current_balance, is_manual, member_id) "
            + "VALUES (?, ?::account_type, ?, ?, ?, ?, ?) RETURNING id";
    private static final String SET_NATURE = "UPDATE account SET card_nature = ? WHERE id = ?";
    private static final String READ_NATURE = "SELECT name, card_nature FROM account WHERE id = ?";

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
                "PICSOU_REQUIRE_DOCKER_TESTS is set but the V111 upgrade test cannot be skipped.");
        }
        return available;
    }

    @BeforeAll
    static void migrateToV110() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("110")
            .load()
            .migrate();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long newMember(String name) throws SQLException {
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "INSERT INTO family_member (display_name) VALUES (?) RETURNING id")) {
            st.setString(1, name);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long newAccount(long member, String name, String type) throws SQLException {
        try (Connection c = connection(); PreparedStatement st = c.prepareStatement(INSERT_ACCOUNT)) {
            st.setString(1, name);
            st.setString(2, type);
            st.setString(3, "test-provider");
            st.setString(4, "EUR");
            st.setBigDecimal(5, new java.math.BigDecimal("-12.50"));
            st.setBoolean(6, false);
            st.setLong(7, member);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void setNature(long account, String nature) throws SQLException {
        try (Connection c = connection(); PreparedStatement st = c.prepareStatement(SET_NATURE)) {
            st.setString(1, nature);
            st.setLong(2, account);
            st.executeUpdate();
        }
    }

    private static String readNature(long account, String expectedName) throws SQLException {
        try (Connection c = connection(); PreparedStatement st = c.prepareStatement(READ_NATURE)) {
            st.setLong(1, account);
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo(expectedName);
                return rs.getString("card_nature");
            }
        }
    }

    @Test
    void upgradesAnExistingV110DatabaseKeepsItsRowsAndBoundsTheNature() throws SQLException {
        long member = newMember("before-v111");
        long existing = newAccount(member, "existing-card", "CREDIT_CARD");

        assertThatThrownBy(() -> setNature(existing, "DEFERRED_DEBIT"))
            .hasMessageContaining("card_nature");

        Flyway flyway = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("110");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("111");

        // the pre-existing row survives with a NULL nature
        assertThat(readNature(existing, "existing-card")).isNull();

        for (String nature : new String[]{"IMMEDIATE_DEBIT", "DEFERRED_DEBIT", "CREDIT"}) {
            long account = newAccount(member, "card-" + nature, "CREDIT_CARD");
            setNature(account, nature);
            assertThat(readNature(account, "card-" + nature)).isEqualTo(nature);
        }

        long bad = newAccount(member, "bad-nature", "CREDIT_CARD");
        assertThatThrownBy(() -> setNature(bad, "PREPAID")).hasMessageContaining("card_nature");
        assertThatThrownBy(() -> setNature(bad, "deferred_debit")).hasMessageContaining("card_nature");
        assertThat(readNature(bad, "bad-nature")).isNull();
    }
}
