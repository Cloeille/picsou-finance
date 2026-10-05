package com.picsou.migration;

import com.picsou.service.WalletSyncService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
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

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code V75__account_logo_key.sql}, which adds {@code account.logo_key} and
 * backfills the wallets that predate it with the default {@code WalletSyncService} now
 * writes at creation.
 *
 * <p>Without the backfill every wallet connected before this release would show a bare
 * color circle while every wallet connected after it showed the blockchain mark — the kind
 * of inconsistency nothing in the app ever repairs, since {@code resolveAccount} only
 * touches an existing account's balance and sync timestamp.
 *
 * <p>The join through {@code wallet_address} and the {@code logo_key IS NULL} guard are the
 * two parts worth pinning: the first keeps the update off accounts that merely look like
 * wallets, the second keeps a replay from resetting a Ledger a user has since chosen.
 *
 * <p>Runs against real PostgreSQL via Testcontainers: the migration chain is
 * PostgreSQL-flavoured ({@code CREATE TYPE ... AS ENUM}, {@code split_part()}), and this
 * migration's own {@code UPDATE ... FROM} join has no H2 equivalent worth trusting.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountLogoKeyMigrationTest {

    static {
        // Same negotiation problem as WalletEvmMigrationTest: docker-java otherwise drops to
        // API 1.32, which Engine >= 28 refuses, and the failure is indistinguishable from
        // "this machine has no Docker" -- so the guard below would skip on a capable host.
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Container
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** @see WalletEvmMigrationTest#dockerAvailable() — CI turns a skip into a hard failure. */
    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found. "
                    + "The V75 migration test cannot be skipped here. "
                    + "Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    private static Long btcWalletId;
    private static Long evmWalletId;
    private static Long btcAccountId;
    private static Long evmAccountId;
    private static Long orphanAccountId;
    private static Long exchangeAccountId;

    /** Brings the schema to V74 — the state of a deployed instance — seeds it, then applies V75. */
    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        migrateTo("74");

        try (Connection conn = connect()) {
            long memberId = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Test') RETURNING id");

            btcWalletId = insertReturningId(conn,
                "INSERT INTO wallet_address (chain, address, member_id) "
                    + "VALUES ('BITCOIN', 'bc1qexampleaddress', " + memberId + ") RETURNING id");
            // EVM doubles as the case check: the external id lowercases the chain name.
            evmWalletId = insertReturningId(conn,
                "INSERT INTO wallet_address (chain, address, member_id) "
                    + "VALUES ('EVM', '0xc579D4Eb8179aF7f322F028D12BDDB845cA10a3b', " + memberId + ") RETURNING id");

            btcAccountId = insertAccount(conn, memberId, "BITCOIN Wallet", "BTC", "wallet_bitcoin_" + btcWalletId);
            evmAccountId = insertAccount(conn, memberId, "EVM Wallet", "ETH", "wallet_evm_" + evmWalletId);
            // Looks like a wallet, isn't one: external_account_id is free text, and a wallet
            // deleted from the sync page leaves its account behind. A LIKE 'wallet\_%' filter
            // would hand this one a blockchain logo it has no business showing.
            orphanAccountId = insertAccount(conn, memberId, "Old wallet", "BTC", "wallet_bitcoin_424242");
            // An exchange account: already served by the provider -> logo map, must stay null
            // so the map keeps winning.
            exchangeAccountId = insertAccount(conn, memberId, "Meria", "MERIA", "meria_spot_1");
        }

        migrateTo("75");
    }

    @Test
    void backfillsEveryExistingWalletWithTheDefaultKey() throws SQLException {
        assertThat(queryString("SELECT logo_key FROM account WHERE id = " + btcAccountId))
            .isEqualTo("blockchain");
        assertThat(queryString("SELECT logo_key FROM account WHERE id = " + evmAccountId))
            .as("the external id lowercases the chain, so the join must too")
            .isEqualTo("blockchain");
    }

    @Test
    void leavesAccountsThatOnlyLookLikeWalletsAlone() throws SQLException {
        assertThat(queryString("SELECT logo_key FROM account WHERE id = " + orphanAccountId)).isNull();
        assertThat(queryString("SELECT logo_key FROM account WHERE id = " + exchangeAccountId)).isNull();
    }

    /**
     * The key the migration writes has to be the one the connector will keep writing, and the
     * one the frontend maps — three literals in three languages that nothing else ties together.
     */
    @Test
    void backfilledKeyMatchesTheServiceDefault() throws SQLException {
        assertThat(queryString("SELECT logo_key FROM account WHERE id = " + btcAccountId))
            .isEqualTo(WalletSyncService.DEFAULT_LOGO_KEY);
    }

    /**
     * Ordered last: it mutates the seeded rows, so the assertions above must see the
     * post-migration state rather than the post-replay one.
     */
    @Test
    @Order(Integer.MAX_VALUE)
    void replayKeepsAChoiceTheUserHasSinceMade() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "UPDATE account SET logo_key = 'ledger' WHERE id = " + btcAccountId);
        }

        replay("V75__account_logo_key.sql");

        // Replaying the real file (a restored dump, a repaired history) must not drag a Ledger
        // back to the generic mark -- that is what the `logo_key IS NULL` guard buys, and
        // dropping it from the WHERE would silently undo every user's choice.
        assertThat(queryString("SELECT logo_key FROM account WHERE id = " + btcAccountId))
            .isEqualTo("ledger");
        assertThat(queryString("SELECT logo_key FROM account WHERE id = " + evmAccountId))
            .isEqualTo("blockchain");
        assertThat(queryString("SELECT logo_key FROM account WHERE id = " + orphanAccountId)).isNull();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    /**
     * Executes the migration file from the classpath, statement by statement rather than in
     * one batch: its {@code ALTER TABLE ... ADD COLUMN} is the one thing a replay genuinely
     * cannot repeat, and a single batched execute would abort the transaction there and never
     * reach the UPDATE that is under test. Re-typing the UPDATE here instead would assert
     * nothing about the file that actually ships.
     *
     * <p>Comments are stripped before splitting on {@code ;} — prose is where semicolons
     * actually turn up in these files, and one inside a comment would otherwise cut a
     * statement in half. This is a splitter for V75 specifically, not a general SQL parser:
     * it would mangle a {@code --} or {@code ;} inside a string literal, which that file
     * does not have.
     */
    private static void replay(String migrationFile) throws Exception {
        String sql;
        try (var in = AccountLogoKeyMigrationTest.class.getResourceAsStream("/db/migration/" + migrationFile)) {
            assertThat(in).as("%s must be on the test classpath", migrationFile).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String stripped = sql.lines()
            .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
            .collect(Collectors.joining("\n"));

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String statement : stripped.split(";")) {
                if (statement.isBlank()) continue;
                try {
                    st.execute(statement);
                } catch (SQLException ex) {
                    if (!"42701".equals(ex.getSQLState())) { // duplicate_column
                        throw ex;
                    }
                }
            }
        }
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
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long insertAccount(Connection conn, long memberId, String name, String provider, String externalId)
        throws SQLException {
        return insertReturningId(conn,
            "INSERT INTO account (name, type, provider, currency, current_balance, external_account_id, "
                + "is_manual, member_id) VALUES ('" + name + "', 'CRYPTO'::account_type, '" + provider + "', "
                + "'EUR', 100, '" + externalId + "', false, " + memberId + ") RETURNING id");
    }

    private static long insertReturningId(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private static String queryString(String sql) throws SQLException {
        if (readConn == null || readConn.isClosed()) {
            readConn = connect();
        }
        try (PreparedStatement ps = readConn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("query returned no row: %s", sql).isTrue();
            return rs.getString(1);
        }
    }

    private static Connection readConn;

    @AfterAll
    static void closeReadConnection() throws SQLException {
        if (readConn != null) readConn.close();
    }
}
