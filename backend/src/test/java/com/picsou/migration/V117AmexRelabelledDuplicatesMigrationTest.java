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
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V117 removes only the uncategorised copy an AMEX re-sync made of a relabelled charge,
 * and nothing else: not a categorised copy, not three identical purchases, not another provider,
 * not a manual row. Every statement is a constant with bound parameters.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class V117AmexRelabelledDuplicatesMigrationTest {

    private static final String INSERT_ACCOUNT =
        "INSERT INTO account (name, type, provider, currency, current_balance, is_manual, member_id) "
            + "VALUES (?, ?::account_type, ?, 'EUR', 0, FALSE, ?) RETURNING id";
    private static final String INSERT_TX =
        "INSERT INTO transaction (account_id, date, description, amount, external_id, is_manual, category_id) "
            + "VALUES (?, DATE '2026-10-03', ?, ?, ?, ?, ?) RETURNING id";
    private static final String SURVIVORS =
        "SELECT description FROM transaction WHERE account_id = ? ORDER BY id";

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
                "PICSOU_REQUIRE_DOCKER_TESTS is set but the V117 upgrade test cannot be skipped.");
        }
        return available;
    }

    @BeforeAll
    static void migrateToV115() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("115")
            .load()
            .migrate();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long scalar(String sql, Object... params) throws SQLException {
        try (Connection c = connection(); PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) st.setObject(i + 1, params[i]);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long account(long member, String type, String provider) throws SQLException {
        try (Connection c = connection(); PreparedStatement st = c.prepareStatement(INSERT_ACCOUNT)) {
            st.setString(1, "card-" + provider + "-" + type);
            st.setString(2, type);
            st.setString(3, provider);
            st.setLong(4, member);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long tx(long account, String label, String amount, String externalId, boolean manual, Long category)
        throws SQLException {
        try (Connection c = connection(); PreparedStatement st = c.prepareStatement(INSERT_TX)) {
            st.setLong(1, account);
            st.setString(2, label);
            st.setBigDecimal(3, new BigDecimal(amount));
            st.setString(4, externalId);
            st.setBoolean(5, manual);
            st.setObject(6, category);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static List<String> survivors(long account) throws SQLException {
        List<String> labels = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement st = c.prepareStatement(SURVIVORS)) {
            st.setLong(1, account);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) labels.add(rs.getString(1));
            }
        }
        return labels;
    }

    @Test
    void removesOnlyTheUncategorisedRelabelledCopyOnAnAmexCardAndIsIdempotent() throws Exception {
        long member = scalar("INSERT INTO family_member (display_name) VALUES ('before-v117') RETURNING id");
        long category = scalar(
            "INSERT INTO category (member_id, name, kind) VALUES (?, 'Leisure', 'EXPENSE') RETURNING id", member);

        // The case from the issue: the older row keeps its category, the newer copy has none.
        long amex = account(member, "CREDIT_CARD", "American Express");
        tx(amex, "ANTHROPIC PBC", "-20.00", "amex_tx_" + "a".repeat(32), false, category);
        tx(amex, "ANTHROPIC* CLAUDE SUB   SAN FRANCISCO", "-20.00", "amex_tx_" + "b".repeat(32), false, null);
        // Two identical purchases: same label, so they are not a relabelled pair.
        tx(amex, "METRO TICKET", "-2.15", "amex_tx_" + "c".repeat(32), false, null);
        tx(amex, "METRO TICKET", "-2.15", "amex_tx_" + "d".repeat(32), false, null);
        // A copy the user already categorised is theirs to merge.
        tx(amex, "TICKETNET", "-45.00", "amex_tx_" + "e".repeat(32), false, null);
        tx(amex, "TICKETNET SITE INTERNET NANTERRE", "-45.00", "amex_tx_" + "f".repeat(32), false, category);
        // Three rows of one group could hold a genuine second purchase: left alone.
        tx(amex, "GROCERY STORE", "-9.00", "amex_tx_" + "1".repeat(32), false, null);
        tx(amex, "GROCERY STORE PARIS", "-9.00", "amex_tx_" + "2".repeat(32), false, null);
        tx(amex, "GROCERY STORE LYON", "-9.00", "amex_tx_" + "3".repeat(32), false, null);
        // A pending row is the sync's own business.
        tx(amex, "BAKERY", "-4.00", "amex_tx_" + "4".repeat(32), false, null);
        tx(amex, "BAKERY AND CAFE", "-4.00", "amex_txp_" + "5".repeat(32), false, null);
        // Different amounts are different charges.
        tx(amex, "TAXI", "-12.00", "amex_tx_" + "6".repeat(32), false, null);
        tx(amex, "TAXI COMPANY", "-12.10", "amex_tx_" + "7".repeat(32), false, null);
        // A manual row never matches.
        tx(amex, "GIFT", "-30.00", "amex_tx_" + "8".repeat(32), false, null);
        tx(amex, "GIFT FOR MUM", "-30.00", null, true, null);

        // The same shape on another provider or card type is out of scope.
        long other = account(member, "CREDIT_CARD", "Caisse d'Epargne");
        tx(other, "SHOP", "-7.00", "amex_tx_" + "9".repeat(32), false, null);
        tx(other, "SHOP ONLINE", "-7.00", "amex_tx_" + "0".repeat(32), false, null);

        Flyway flyway = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("117") // later migrations are covered by their own tests
            .load();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("115");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(survivors(amex)).containsExactly(
            "ANTHROPIC PBC",
            "METRO TICKET", "METRO TICKET",
            "TICKETNET", "TICKETNET SITE INTERNET NANTERRE",
            "GROCERY STORE", "GROCERY STORE PARIS", "GROCERY STORE LYON",
            "BAKERY", "BAKERY AND CAFE",
            "TAXI", "TAXI COMPANY",
            "GIFT", "GIFT FOR MUM");
        assertThat(survivors(other)).containsExactly("SHOP", "SHOP ONLINE");
        assertThat(scalar("SELECT category_id FROM transaction WHERE description = 'ANTHROPIC PBC'"))
            .isEqualTo(category);

        // Running the script again deletes nothing more.
        long before = scalar("SELECT count(*) FROM transaction");
        String script = new String(
            getClass().getResourceAsStream("/db/migration/V117__amex_relabelled_duplicates.sql").readAllBytes(),
            StandardCharsets.UTF_8);
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.execute(script);
        }
        assertThat(scalar("SELECT count(*) FROM transaction")).isEqualTo(before);
    }
}
