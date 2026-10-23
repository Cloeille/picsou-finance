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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V117 removes only the uncategorised copy a LATER AMEX sync made of a relabelled charge,
 * and nothing else: not two genuine purchases stored by one sync, not a copy carrying a category or
 * an AI suggestion, not three identical purchases, not another provider, not a manual row. The
 * removed row is copied to {@code amex_v117_removed}. Every statement is a constant with bound
 * parameters.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class V117AmexRelabelledDuplicatesMigrationTest {

    private static final String INSERT_ACCOUNT =
        "INSERT INTO account (name, type, provider, currency, current_balance, is_manual, member_id) "
            + "VALUES (?, ?::account_type, ?, 'EUR', 0, FALSE, ?) RETURNING id";
    private static final String INSERT_TX =
        "INSERT INTO transaction (account_id, date, description, amount, external_id, is_manual, category_id, "
            + "created_at, ai_suggested_category_id) "
            + "VALUES (?, DATE '2026-10-03', ?, ?, ?, ?, ?, ?, ?) RETURNING id";
    private static final String SURVIVORS =
        "SELECT description FROM transaction WHERE account_id = ? ORDER BY id";

    /** The sync that stored the original rows, and the one a day later that stored relabelled copies. */
    private static final Instant FIRST_SYNC = Instant.parse("2026-10-03T08:00:00Z");
    private static final Instant NEXT_SYNC = FIRST_SYNC.plus(1, ChronoUnit.DAYS);

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

    private static long tx(long account, String label, String amount, String externalId, boolean manual,
                            Long category, Instant createdAt, Long aiSuggestedCategory) throws SQLException {
        try (Connection c = connection(); PreparedStatement st = c.prepareStatement(INSERT_TX)) {
            st.setLong(1, account);
            st.setString(2, label);
            st.setBigDecimal(3, new BigDecimal(amount));
            st.setString(4, externalId);
            st.setBoolean(5, manual);
            st.setObject(6, category);
            st.setObject(7, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
            st.setObject(8, aiSuggestedCategory);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** A row stored by the first sync. */
    private static long stored(long account, String label, String amount, String externalId, boolean manual,
                               Long category) throws SQLException {
        return tx(account, label, amount, externalId, manual, category, FIRST_SYNC, null);
    }

    /** A row a later sync stored for a charge already held under another label. */
    private static long laterCopy(long account, String label, String amount, String externalId, boolean manual,
                                  Long category) throws SQLException {
        return tx(account, label, amount, externalId, manual, category, NEXT_SYNC, null);
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

    private static List<String> removedLabels() throws SQLException {
        List<String> labels = new ArrayList<>();
        try (Connection c = connection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT description FROM amex_v117_removed ORDER BY id")) {
            while (rs.next()) labels.add(rs.getString(1));
        }
        return labels;
    }

    @Test
    void removesOnlyTheUncategorisedCopyOfALaterSyncBacksItUpAndIsIdempotent() throws Exception {
        long member = scalar("INSERT INTO family_member (display_name) VALUES ('before-v117') RETURNING id");
        long category = scalar(
            "INSERT INTO category (member_id, name, kind) VALUES (?, 'Leisure', 'EXPENSE') RETURNING id", member);

        // The case from the issue: the older row keeps its category, the newer copy, stored by a
        // later sync, has none.
        long amex = account(member, "CREDIT_CARD", "American Express");
        stored(amex, "ANTHROPIC PBC", "-20.00", "amex_tx_" + "a".repeat(32), false, category);
        laterCopy(amex, "ANTHROPIC* CLAUDE SUB   SAN FRANCISCO", "-20.00", "amex_tx_" + "b".repeat(32), false, null);
        // Two identical purchases: same label, so they are not a relabelled pair.
        stored(amex, "METRO TICKET", "-2.15", "amex_tx_" + "c".repeat(32), false, null);
        laterCopy(amex, "METRO TICKET", "-2.15", "amex_tx_" + "d".repeat(32), false, null);
        // A copy the user already categorised is theirs to merge.
        stored(amex, "TICKETNET", "-45.00", "amex_tx_" + "e".repeat(32), false, null);
        laterCopy(amex, "TICKETNET SITE INTERNET NANTERRE", "-45.00", "amex_tx_" + "f".repeat(32), false, category);
        // Three rows of one group could hold a genuine second purchase: left alone.
        stored(amex, "GROCERY STORE", "-9.00", "amex_tx_" + "1".repeat(32), false, null);
        laterCopy(amex, "GROCERY STORE PARIS", "-9.00", "amex_tx_" + "2".repeat(32), false, null);
        laterCopy(amex, "GROCERY STORE LYON", "-9.00", "amex_tx_" + "3".repeat(32), false, null);
        // A pending row is the sync's own business.
        stored(amex, "BAKERY", "-4.00", "amex_tx_" + "4".repeat(32), false, null);
        laterCopy(amex, "BAKERY AND CAFE", "-4.00", "amex_txp_" + "5".repeat(32), false, null);
        // Different amounts are different charges.
        stored(amex, "TAXI", "-12.00", "amex_tx_" + "6".repeat(32), false, null);
        laterCopy(amex, "TAXI COMPANY", "-12.10", "amex_tx_" + "7".repeat(32), false, null);
        // A manual row never matches.
        stored(amex, "GIFT", "-30.00", "amex_tx_" + "8".repeat(32), false, null);
        laterCopy(amex, "GIFT FOR MUM", "-30.00", null, true, null);
        // Two genuine purchases at one merchant, same day and amount, labelled differently and stored
        // by the SAME sync (same created_at, nothing categorised): both are real, both stay.
        stored(amex, "AMZN MKTP FR*1A2B", "-9.99", "amex_tx_" + "g".repeat(32), false, null);
        stored(amex, "AMZN MKTP FR*3C4D", "-9.99", "amex_tx_" + "h".repeat(32), false, null);
        stored(amex, "SNCF", "-45.00", "amex_tx_" + "i".repeat(32), false, null);
        stored(amex, "SNCF CONNECT", "-45.00", "amex_tx_" + "j".repeat(32), false, null);
        stored(amex, "UBER *TRIP", "-12.50", "amex_tx_" + "k".repeat(32), false, null);
        stored(amex, "UBER *EATS", "-12.50", "amex_tx_" + "l".repeat(32), false, null);
        // A later-sync relabelled copy the AI already suggested a category for: the suggestion stays.
        stored(amex, "NETFLIX", "-13.49", "amex_tx_" + "m".repeat(32), false, null);
        tx(amex, "NETFLIX.COM LUXEMBOURG", "-13.49", "amex_tx_" + "n".repeat(32), false, null, NEXT_SYNC, category);

        // The same shape on another provider or card type is out of scope.
        long other = account(member, "CREDIT_CARD", "Caisse d'Epargne");
        stored(other, "SHOP", "-7.00", "amex_tx_" + "9".repeat(32), false, null);
        laterCopy(other, "SHOP ONLINE", "-7.00", "amex_tx_" + "0".repeat(32), false, null);

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
            "GIFT", "GIFT FOR MUM",
            "AMZN MKTP FR*1A2B", "AMZN MKTP FR*3C4D",
            "SNCF", "SNCF CONNECT",
            "UBER *TRIP", "UBER *EATS",
            "NETFLIX", "NETFLIX.COM LUXEMBOURG");
        assertThat(survivors(other)).containsExactly("SHOP", "SHOP ONLINE");
        assertThat(scalar("SELECT category_id FROM transaction WHERE description = 'ANTHROPIC PBC'"))
            .isEqualTo(category);

        // The removed copy, and only it, is kept in the backup table.
        assertThat(removedLabels()).containsExactly("ANTHROPIC* CLAUDE SUB   SAN FRANCISCO");

        // Running the script again fails on nothing and deletes nothing more.
        long before = scalar("SELECT count(*) FROM transaction");
        String script = new String(
            getClass().getResourceAsStream("/db/migration/V117__amex_relabelled_duplicates.sql").readAllBytes(),
            StandardCharsets.UTF_8);
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.execute(script);
        }
        assertThat(scalar("SELECT count(*) FROM transaction")).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM amex_v117_removed")).isEqualTo(1);

        // The restore recipe in the script header brings the removed row back.
        assertThat(scalar(
            "INSERT INTO transaction SELECT * FROM amex_v117_removed WHERE external_id = ? RETURNING id",
            "amex_tx_" + "b".repeat(32))).isPositive();
        assertThat(scalar("SELECT count(*) FROM transaction")).isEqualTo(before + 1);
    }
}
