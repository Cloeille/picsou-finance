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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V118 deletes the mis-scaled foreign leg of each Revolut exchange (#251), whatever the asset's
 * price, copies it to a backup table first, and touches nothing else.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class RevolutExchangeLegRepairMigrationTest {

    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    private static final String V118 = "db/migration/V118__revolut_exchange_leg_repair.sql";
    /** Descriptions are matched ignoring case, padding and repeated spaces. */
    private static final String PADDED = " exchanged  to eur ";

    private static long prodCategory;

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
            long oldEurA = softDeletedAccount(c, owner, "Old EUR pocket A", "Revolut");
            long oldEurB = softDeletedAccount(c, owner, "Old EUR pocket B", "Revolut");
            prodCategory = category(c, owner, "Crypto");

            // Crypto to EUR. Stored foreign leg = units x 1e6, so the gap to the EUR leg is
            // 1e6 / price: 17x for BTC, 400x for ETH. Each must go, whatever the gap.
            exchange(c, eur, crypto, "btc", "3000", "Exchanged from BTC", "-50000", "Exchanged to EUR");
            exchange(c, eur, crypto, "eth", "2500", "Exchanged from ETH", "-1000000", "Exchanged to EUR");
            exchange(c, eur, crypto, "sol", "1500", "Exchanged from SOL", "-10000000", "Exchanged to EUR");
            exchange(c, eur, crypto, "doge", "3", "Exchanged from DOGE", "-20000000", "Exchanged to EUR");
            // EUR to crypto: the debit is the EUR leg, the foreign credit names EUR.
            exchange(c, eur, crypto, "buy-btc", "-500", "Exchanged to BTC", "1000", "Exchanged from EUR");
            // Revolut may give both legs the same description.
            exchange(c, eur, crypto, "same", "3000", "Exchanged to EUR", "-50000", "Exchanged to EUR");
            exchange(c, eur, crypto, "padded", "100", "Exchanged from BTC", "-1000", PADDED);
            // Production shape: legacy rows without external_transaction_id, one foreign debit on
            // a live account and the EUR credit duplicated on two soft-deleted accounts, all
            // reading "Exchanged to EUR".
            legacyTx(c, eur, "prod", "-50000", "Exchanged to EUR", prodCategory);
            legacyTx(c, oldEurA, "prod", "3000", "Exchanged to EUR", null);
            legacyTx(c, oldEurB, "prod", "3000", "Exchanged to EUR", null);
            // A fee sharing the exchange's id is kept; only the foreign leg goes.
            exchange(c, eur, crypto, "fee", "7", "Exchanged from BTC", "-70000", "Exchanged to EUR");
            tx(c, usd, "fee", "-1", "Fee");

            // Survivors.
            exchange(c, usd, crypto, "usd-btc", "-200", "Exchanged to BTC", "300000", "Exchanged from USD");
            exchange(c, eur, crypto, "transfer", "-25", "To pocket", "25", "From pocket");
            exchange(c, eur, otherEur, "cross-member", "4.80", "Exchanged from BTC", "-48000",
                "Exchanged to EUR");
            insert(c, bourso, "bourso", "bourso:bourso", "-48000000", "Exchanged to EUR", null);
            insert(c, boursoLivret, "bourso", "bourso:bourso", "4.80", "Exchanged from BTC", null);
            tx(c, eur, "card", "-12.50", "Coffee");
            tx(c, eur, null, "6.00", "Exchanged from BTC");
            tx(c, crypto, null, "-60000", "Exchanged to EUR");
            tx(c, crypto, "lone", "-60000", "Exchanged to EUR");
            // The EUR leg is never the one deleted: a credit "to EUR", a debit "from EUR".
            exchange(c, eur, crypto, "eur-credit", "3000", "Exchanged to EUR", "-2", "Exchanged from BTC");
            exchange(c, eur, crypto, "eur-debit", "-500", "Exchanged from EUR", "2", "Exchanged to BTC");
            exchange(c, eur, crypto, "manual", "9", "Exchanged from BTC", "-90000", "Exchanged to EUR");
            try (Statement st = c.createStatement()) {
                st.execute("UPDATE transaction SET is_manual = true WHERE external_id = 'manual'");
            }
        }
        migrateTo("118");
    }

    @Test
    void deletesTheForeignLegOfEveryExchangeWhateverTheAssetPrice() throws SQLException {
        assertThat(transactionRows()).doesNotContain(
            "btc -Exchanged to EUR", "eth -Exchanged to EUR", "sol -Exchanged to EUR", "doge -Exchanged to EUR");
        assertThat(transactionRows()).contains(
            "btc +Exchanged from BTC", "eth +Exchanged from ETH", "sol +Exchanged from SOL",
            "doge +Exchanged from DOGE");
    }

    @Test
    void deletesTheForeignCreditOfAnEurToCryptoBuy() throws SQLException {
        assertThat(transactionRows()).doesNotContain("buy-btc +Exchanged from EUR");
        assertThat(transactionRows()).contains("buy-btc -Exchanged to BTC");
    }

    @Test
    void deletesTheForeignDebitWhenBothLegsShareOneDescription() throws SQLException {
        assertThat(transactionRows()).doesNotContain("same -Exchanged to EUR");
        assertThat(transactionRows()).contains("same +Exchanged to EUR");
    }

    @Test
    void deletesTheForeignDebitOfALegacyExchangeSpreadOverSoftDeletedDuplicates() throws SQLException {
        assertThat(transactionRows()).doesNotContain("prod -Exchanged to EUR");
        assertThat(Collections.frequency(transactionRows(), "prod +Exchanged to EUR")).isEqualTo(2);
    }

    @Test
    void ignoresCasePaddingAndRepeatedSpacesInTheDescription() throws SQLException {
        assertThat(transactionRows()).doesNotContain("padded -" + PADDED);
        assertThat(transactionRows()).contains("padded +Exchanged from BTC");
    }

    @Test
    void keepsAFeeSharingTheExchangeIdWhileItsForeignLegGoes() throws SQLException {
        assertThat(transactionRows()).doesNotContain("fee -Exchanged to EUR");
        assertThat(transactionRows()).contains("fee +Exchanged from BTC", "fee -Fee");
    }

    @Test
    void leavesEveryOtherRowAlone() throws SQLException {
        assertThat(transactionRows()).contains(
            "usd-btc -Exchanged to BTC", "usd-btc +Exchanged from USD",
            "transfer -To pocket", "transfer +From pocket",
            "cross-member +Exchanged from BTC", "cross-member -Exchanged to EUR",
            "bourso -Exchanged to EUR", "bourso +Exchanged from BTC",
            "card -Coffee",
            "null +Exchanged from BTC", "null -Exchanged to EUR",
            "lone -Exchanged to EUR",
            "eur-credit +Exchanged to EUR", "eur-credit -Exchanged from BTC",
            "eur-debit -Exchanged from EUR", "eur-debit +Exchanged to BTC",
            "manual +Exchanged from BTC", "manual -Exchanged to EUR");
        assertThat(transactionRows()).hasSize(29);
    }

    @Test
    void backsUpExactlyTheDeletedRowsWithTheirCategory() throws SQLException {
        assertThat(backupRows()).containsExactlyInAnyOrder(
            "btc -Exchanged to EUR", "eth -Exchanged to EUR", "sol -Exchanged to EUR",
            "doge -Exchanged to EUR", "buy-btc +Exchanged from EUR", "same -Exchanged to EUR",
            "padded -" + PADDED, "prod -Exchanged to EUR", "fee -Exchanged to EUR");
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT category_id FROM transaction_v118_backup WHERE external_id = 'prod'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(prodCategory);
        }
    }

    @Test
    void theBackupRestoresIntoTheTransactionTable() throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                st.execute("INSERT INTO transaction SELECT * FROM transaction_v118_backup");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM transaction")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(29 + 9);
                }
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void aSecondRunIsANoOp() throws SQLException, IOException {
        List<String> rowsBefore = transactionRows();
        List<String> backupBefore = backupRows();
        try (Connection c = connect(); Statement st = c.createStatement();
             InputStream sql = getClass().getClassLoader().getResourceAsStream(V118)) {
            st.execute(new String(sql.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(transactionRows()).containsExactlyInAnyOrderElementsOf(rowsBefore);
        assertThat(backupRows()).containsExactlyInAnyOrderElementsOf(backupBefore);
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
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO account (name, type, provider, currency, current_balance, is_manual, member_id) "
                + "VALUES (?, 'CHECKING'::account_type, ?, 'EUR', 0, false, ?) RETURNING id")) {
            ps.setString(1, name);
            ps.setString(2, provider);
            ps.setLong(3, member);
            return returnedId(ps);
        }
    }

    private static long softDeletedAccount(Connection c, long member, String name, String provider)
        throws SQLException {
        long account = account(c, member, name, provider);
        try (PreparedStatement ps = c.prepareStatement("UPDATE account SET deleted_at = NOW() WHERE id = ?")) {
            ps.setLong(1, account);
            ps.executeUpdate();
        }
        return account;
    }

    private static long category(Connection c, long member, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO category (member_id, name, kind) VALUES (?, ?, 'EXPENSE'::category_kind) RETURNING id")) {
            ps.setLong(1, member);
            ps.setString(2, name);
            return returnedId(ps);
        }
    }

    private static long returnedId(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exchange(Connection c, long accountA, long accountB, String externalId,
                                 String amountA, String descriptionA,
                                 String amountB, String descriptionB) throws SQLException {
        tx(c, accountA, externalId, amountA, descriptionA);
        tx(c, accountB, externalId, amountB, descriptionB);
    }

    /** A Revolut row as the importer writes it today, with a "revolut:" transaction key. */
    private static void tx(Connection c, long account, String externalId, String amount, String description)
        throws SQLException {
        insert(c, account, externalId, externalId == null ? null : "revolut:" + externalId, amount, description,
            null);
    }

    /** A legacy row: it has an external_id but no external_transaction_id. */
    private static void legacyTx(Connection c, long account, String externalId, String amount,
                                 String description, Long categoryId) throws SQLException {
        insert(c, account, externalId, null, amount, description, categoryId);
    }

    private static void insert(Connection c, long account, String externalId, String transactionKey,
                               String amount, String description, Long categoryId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO transaction (account_id, date, description, amount, native_currency, external_id, "
                + "external_transaction_id, is_manual, category_id) "
                + "VALUES (?, DATE '2026-09-12', ?, ?, 'EUR', ?, ?, false, ?)")) {
            ps.setLong(1, account);
            ps.setString(2, description);
            ps.setBigDecimal(3, new BigDecimal(amount));
            ps.setString(4, externalId);
            ps.setString(5, transactionKey);
            if (categoryId == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, categoryId);
            }
            ps.executeUpdate();
        }
    }

    private static List<String> transactionRows() throws SQLException {
        return rows("SELECT external_id, description, amount FROM transaction");
    }

    private static List<String> backupRows() throws SQLException {
        return rows("SELECT external_id, description, amount FROM transaction_v118_backup");
    }

    /** Each row as "<external_id> <sign><description>", the sign telling a debit from a credit. */
    private static List<String> rows(String query) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(query)) {
            while (rs.next()) {
                out.add(rs.getString(1) + " " + (rs.getBigDecimal(3).signum() < 0 ? "-" : "+") + rs.getString(2));
            }
        }
        return out;
    }
}
