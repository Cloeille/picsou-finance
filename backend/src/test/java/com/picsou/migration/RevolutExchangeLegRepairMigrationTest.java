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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V118 deletes the mis-scaled foreign leg of each Revolut exchange (#251) and nothing else: the
 * EUR leg, an equal-amount transfer, a lone row and another provider's rows all survive.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class RevolutExchangeLegRepairMigrationTest {

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
                    + "The V118 migration test cannot be skipped. Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        // 117 is reserved by the open AMEX PR (#247); out-of-order keeps either merge order valid.
        migrateTo("116");
        try (Connection c = connect()) {
            long member = id(c, "INSERT INTO family_member (display_name) VALUES ('Owner') RETURNING id");
            long eur = account(c, member, "EUR pocket", "Revolut");
            long crypto = account(c, member, "Crypto pocket", "Revolut");
            long bourso = account(c, member, "Bourso", "BoursoBank");
            long boursoLivret = account(c, member, "Bourso livret", "BoursoBank");

            tx(c, eur, "exchange-1", "4.80", "revolut:exchange-1", "Exchanged from BTC");
            tx(c, crypto, "exchange-1", "-48000000", "revolut:exchange-1", "Exchanged to EUR");
            tx(c, eur, "transfer-1", "-25", "revolut:transfer-1", "To pocket");
            tx(c, crypto, "transfer-1", "25", "revolut:transfer-1", "From pocket");
            tx(c, eur, "card-1", "-12.50", "revolut:card-1", "Coffee");
            tx(c, bourso, "bourso-1", "-48000000", "bourso:bourso-1", "Not Revolut");
            tx(c, boursoLivret, "bourso-1", "4.80", "bourso:bourso-1", "Not Revolut either");
        }
        migrateTo("118");
    }

    @Test
    void deletesOnlyTheMisScaledLegOfARevolutExchange() throws SQLException {
        assertThat(descriptions()).containsExactlyInAnyOrder(
            "Exchanged from BTC", "To pocket", "From pocket", "Coffee", "Not Revolut", "Not Revolut either");
    }

    private static void migrateTo(String version) {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(version)
            .outOfOrder(true) // mirrors application.yml
            .load()
            .migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long id(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long account(Connection c, long member, String name, String provider) throws SQLException {
        return id(c, "INSERT INTO account (name, type, provider, currency, current_balance, is_manual, member_id) "
            + "VALUES ('" + name + "', 'CHECKING'::account_type, '" + provider + "', 'EUR', 0, false, "
            + member + ") RETURNING id");
    }

    private static void tx(Connection c, long account, String externalId, String amount, String key,
                           String description) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO transaction (account_id, date, description, amount, native_currency, "
                + "external_id, external_transaction_id, is_manual) VALUES (" + account + ", DATE '2026-09-12', '"
                + description + "', " + amount + ", 'EUR', '" + externalId + "', '" + key + "', false)");
        }
    }

    private static List<String> descriptions() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT description FROM transaction")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }
}
