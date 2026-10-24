package com.picsou.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V118 deletes the mis-scaled foreign leg of each Revolut exchange (#251), whatever the asset's
 * price, and nothing else.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class RevolutExchangeLegRepairMigrationTest {

    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    private static final String V118 = "db/migration/V118__revolut_exchange_leg_repair.sql";

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
            long owner = id(c, "INSERT INTO family_member (display_name) VALUES ('Owner') RETURNING id");
            long other = id(c, "INSERT INTO family_member (display_name) VALUES ('Other') RETURNING id");
            long eur = account(c, owner, "EUR pocket", "Revolut");
            long crypto = account(c, owner, "Crypto pocket", "Revolut");
            long usd = account(c, owner, "USD pocket", "Revolut");
            long otherEur = account(c, other, "Other EUR pocket", "Revolut");
            long bourso = account(c, owner, "Bourso", "BoursoBank");
            long boursoLivret = account(c, owner, "Bourso livret", "BoursoBank");

            // Crypto to EUR. Stored foreign leg = units x 1e6, so the gap to the EUR leg is
            // 1e6 / price: 17x for BTC, 400x for ETH. Each must go, whatever the gap.
            exchange(c, eur, crypto, "btc", "3000", "Exchanged from BTC", "-50000", "Exchanged to EUR");
            exchange(c, eur, crypto, "eth", "2500", "Exchanged from ETH", "-1000000", "Exchanged to EUR");
            exchange(c, eur, crypto, "sol", "1500", "Exchanged from SOL", "-10000000", "Exchanged to EUR");
            exchange(c, eur, crypto, "doge", "3", "Exchanged from DOGE", "-20000000", "Exchanged to EUR");
            // EUR to crypto: the debit is the EUR leg, the foreign credit names EUR.
            exchange(c, eur, crypto, "buy-btc", "-500", "Exchanged to BTC", "1000", "Exchanged from EUR");

            // Survivors.
            exchange(c, usd, crypto, "usd-btc", "-200", "Exchanged to BTC", "300000", "Exchanged from USD");
            exchange(c, eur, crypto, "transfer", "-25", "To pocket", "25", "From pocket");
            exchange(c, eur, otherEur, "cross-member", "4.80", "Exchanged from BTC", "-48000",
                "Exchanged to EUR");
            exchange(c, bourso, boursoLivret, "bourso", "-48000000", "Exchanged to EUR", "4.80",
                "Exchanged from BTC");
            tx(c, eur, "card", "-12.50", "Coffee", false);
            tx(c, eur, null, "6.00", "Exchanged from BTC", false);
            tx(c, crypto, null, "-60000", "Exchanged to EUR", false);
            exchange(c, eur, crypto, "manual", "9", "Exchanged from BTC", "-90000", "Exchanged to EUR");
            try (Statement st = c.createStatement()) {
                st.execute("UPDATE transaction SET is_manual = true WHERE external_id = 'manual'");
            }
            exchange(c, eur, crypto, "three-rows", "7", "Exchanged from BTC", "-70000", "Exchanged to EUR");
            tx(c, usd, "three-rows", "-1", "Fee", false);
        }
        migrateTo("118");
    }

    @Test
    void deletesTheForeignLegOfEveryExchangeWhateverTheAssetPrice() throws SQLException {
        assertThat(rows()).doesNotContain(
            "btc Exchanged to EUR", "eth Exchanged to EUR", "sol Exchanged to EUR", "doge Exchanged to EUR",
            "buy-btc Exchanged from EUR");
        assertThat(rows()).contains(
            "btc Exchanged from BTC", "eth Exchanged from ETH", "sol Exchanged from SOL",
            "doge Exchanged from DOGE", "buy-btc Exchanged to BTC");
    }

    @Test
    void leavesEveryOtherRowAlone() throws SQLException {
        assertThat(rows()).contains(
            "usd-btc Exchanged to BTC", "usd-btc Exchanged from USD",
            "transfer To pocket", "transfer From pocket",
            "cross-member Exchanged from BTC", "cross-member Exchanged to EUR",
            "bourso Exchanged to EUR", "bourso Exchanged from BTC",
            "card Coffee",
            "null Exchanged from BTC", "null Exchanged to EUR",
            "manual Exchanged from BTC", "manual Exchanged to EUR",
            "three-rows Exchanged from BTC", "three-rows Exchanged to EUR", "three-rows Fee");
        assertThat(rows()).hasSize(21);
    }

    @Test
    void aSecondRunIsANoOp() throws SQLException, IOException {
        List<String> before = rows();
        try (Connection c = connect(); Statement st = c.createStatement();
             InputStream sql = getClass().getClassLoader().getResourceAsStream(V118)) {
            st.execute(new String(sql.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(rows()).containsExactlyInAnyOrderElementsOf(before);
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

    private static void exchange(Connection c, long accountA, long accountB, String externalId,
                                 String amountA, String descriptionA,
                                 String amountB, String descriptionB) throws SQLException {
        tx(c, accountA, externalId, amountA, descriptionA, externalId.equals("bourso"));
        tx(c, accountB, externalId, amountB, descriptionB, externalId.equals("bourso"));
    }

    private static void tx(Connection c, long account, String externalId, String amount, String description,
                           boolean bourso) throws SQLException {
        String key = externalId == null ? "NULL" : "'" + (bourso ? "bourso:" : "revolut:") + externalId + "'";
        String ext = externalId == null ? "NULL" : "'" + externalId + "'";
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO transaction (account_id, date, description, amount, native_currency, "
                + "external_id, external_transaction_id, is_manual) VALUES (" + account + ", DATE '2026-09-12', '"
                + description + "', " + amount + ", 'EUR', " + ext + ", " + key + ", false)");
        }
    }

    /** Each row as "<external_id> <description>". */
    private static List<String> rows() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT external_id, description FROM transaction")) {
            while (rs.next()) out.add(rs.getString(1) + " " + rs.getString(2));
        }
        return out;
    }
}
