package com.picsou.migration;

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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the two data-mutating migrations in the EVM fan-out change:
 * {@code V56__wallet_ethereum_to_evm.sql}, which rewrites {@code wallet_address.chain} and,
 * critically, the {@code account.external_account_id} tying a wallet to its synced account;
 * and {@code V57__wallet_evm_account_name.sql}, which relabels the auto-generated account
 * name without disturbing a user's own label.
 *
 * <p>If the id rewrite were wrong or missing, {@code WalletSyncService} would compute a
 * {@code wallet_evm_<id>} key that matches nothing, silently create a <em>second</em>
 * account, and orphan the original — taking its balance-snapshot history and its
 * holdings' {@code average_buy_in} cost basis with it. That loss is invisible until a
 * user notices their net-worth chart restarted, so it is asserted here rather than
 * discovered in production.
 *
 * <p>Runs against real PostgreSQL via Testcontainers because the migration chain is
 * PostgreSQL-flavoured — {@code CREATE TYPE ... AS ENUM} and V56's own
 * {@code split_part()} do not exist in H2.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WalletEvmMigrationTest {

    static {
        // docker-java otherwise negotiates down to Docker API 1.32, which Engine >= 28
        // refuses outright ("minimum supported API version is 1.40") -- which surfaces as
        // the *same* "no valid Docker environment" error a machine without Docker gives,
        // so the guard below would quietly skip this test on a perfectly capable host.
        // Set here rather than in surefire config so it also applies when the class is run
        // from an IDE or failsafe. 1.44 is satisfied by Engine >= 25.0 (Jan 2024).
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Container
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Gates the whole class on a reachable Docker daemon so the other 570-odd tests stay
     * runnable without one (notably {@code mvn test} inside a Maven image with no
     * {@code /var/run/docker.sock} mount). Evaluated as a JUnit {@code ExecutionCondition},
     * which runs <em>before</em> the Testcontainers extension tries to start the container.
     *
     * <p>A skip is invisible in a green build, so CI sets
     * {@code PICSOU_REQUIRE_DOCKER_TESTS=true},
     * which turns "no Docker" into a hard failure instead. Environment drift then shows up
     * as a red build rather than silently deleting the coverage.
     */
    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found. "
                    + "The V56/V57 migration test cannot be skipped here -- it is the only "
                    + "coverage of a data-mutating migration. Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    private static Long ethWalletId;
    private static Long solWalletId;
    private static Long ethAccountId;
    private static Long solAccountId;
    private static Long bankAccountId;
    private static Long labelledAccountId;
    private static Long trapWalletId;
    private static Long trapAccountId;

    /**
     * Brings the schema to V53 (the state a deployed instance is in before this change),
     * seeds a realistic pre-migration dataset, then applies V56 alone.
     */
    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        migrateTo("53");

        try (Connection conn = connect()) {
            long memberId = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Test') RETURNING id");

            // The wallet under migration, plus a Solana wallet as a negative control:
            // only ETHEREUM rows may be touched.
            ethWalletId = insertReturningId(conn,
                "INSERT INTO wallet_address (chain, address, member_id) "
                    + "VALUES ('ETHEREUM', '0xc579D4Eb8179aF7f322F028D12BDDB845cA10a3b', " + memberId + ") RETURNING id");
            solWalletId = insertReturningId(conn,
                "INSERT INTO wallet_address (chain, address, member_id) "
                    + "VALUES ('SOLANA', 'SoLaNaAddr', " + memberId + ") RETURNING id");

            // "ETHEREUM Wallet" is exactly what resolveAccount names an UNLABELLED wallet
            // (chain.name() + " Wallet") -- the name V57 has to rewrite.
            ethAccountId = insertAccount(conn, memberId, "ETHEREUM Wallet", "wallet_ethereum_" + ethWalletId);
            solAccountId = insertAccount(conn, memberId, "SOL Wallet", "wallet_solana_" + solWalletId);
            // A bank account whose external id is unrelated: the LIKE filter must not reach it.
            bankAccountId = insertAccount(conn, memberId, "Checking", "gocardless_abc_123");
            // A second migrated wallet the user LABELLED: V57 must not rename it.
            long labelledWalletId = insertReturningId(conn,
                "INSERT INTO wallet_address (chain, address, member_id, label) "
                    + "VALUES ('ETHEREUM', '0x2222222222222222222222222222222222222222', "
                    + memberId + ", 'My Ledger') RETURNING id");
            labelledAccountId = insertAccount(conn, memberId, "My Ledger", "wallet_ethereum_" + labelledWalletId);

            // The trap: a user whose chosen label happens to BE the auto-generated name.
            // resolveAccount uses a label verbatim, so this account is indistinguishable
            // from an auto-named one by name alone -- keying V57 on the name would destroy
            // a label that can never be recovered (resolveAccount never rewrites names).
            trapWalletId = insertReturningId(conn,
                "INSERT INTO wallet_address (chain, address, member_id, label) "
                    + "VALUES ('ETHEREUM', '0x3333333333333333333333333333333333333333', "
                    + memberId + ", 'ETHEREUM Wallet') RETURNING id");
            trapAccountId = insertAccount(conn, memberId, "ETHEREUM Wallet", "wallet_ethereum_" + trapWalletId);

            // Cost basis on the migrated account -- the value most expensive to lose,
            // since it cannot be recomputed from on-chain data.
            exec(conn, "INSERT INTO account_holding (account_id, ticker, quantity, average_buy_in) "
                + "VALUES (" + ethAccountId + ", 'ETH', 0.96100000, 1850.00000000)");
        }

        migrateTo("57");
    }

    @Test
    void renamesDefaultChainNamedAccount_butKeepsUserLabels() throws SQLException {
        // V57: an unlabelled wallet's account was named from the retired chain value and
        // resolveAccount never refreshes an existing account's name, so without this it
        // would read "ETHEREUM Wallet" forever while tracking BNB, POL and AVAX too.
        assertThat(queryString("SELECT name FROM account WHERE id = " + ethAccountId))
            .isEqualTo("EVM Wallet");
        assertThat(queryString("SELECT name FROM account WHERE id = " + labelledAccountId))
            .isEqualTo("My Ledger");
        assertThat(queryString("SELECT name FROM account WHERE id = " + bankAccountId))
            .isEqualTo("Checking");
        // The label that collides with the auto-generated name must survive: it is a user's
        // choice, and nothing else in the app would ever restore it.
        assertThat(queryString("SELECT name FROM account WHERE id = " + trapAccountId))
            .as("a user label identical to the default name must not be rewritten")
            .isEqualTo("ETHEREUM Wallet");
    }

    @Test
    void convertsEthereumWalletToEvm() throws SQLException {
        assertThat(queryString("SELECT chain FROM wallet_address WHERE id = " + ethWalletId))
            .isEqualTo("EVM");
    }

    @Test
    void leavesOtherChainsUntouched() throws SQLException {
        assertThat(queryString("SELECT chain FROM wallet_address WHERE id = " + solWalletId))
            .isEqualTo("SOLANA");
    }

    @Test
    void rewritesExternalAccountId_keepingTheSameAccountRow() throws SQLException {
        // Same row id, new key: this is what keeps snapshots and holdings attached
        // instead of the next sync creating a fresh, empty account.
        assertThat(queryString("SELECT external_account_id FROM account WHERE id = " + ethAccountId))
            .isEqualTo("wallet_evm_" + ethWalletId);
    }

    @Test
    void rewrittenIdMatchesWhatTheServiceWillCompute() throws SQLException {
        // WalletSyncService builds "wallet_" + chain.name().toLowerCase() + "_" + id.
        String chain = queryString("SELECT chain FROM wallet_address WHERE id = " + ethWalletId);
        String expected = "wallet_" + chain.toLowerCase() + "_" + ethWalletId;

        assertThat(queryString("SELECT external_account_id FROM account WHERE id = " + ethAccountId))
            .isEqualTo(expected);
    }

    @Test
    void preservesHoldingsAndCostBasis() throws SQLException {
        assertThat(queryLong("SELECT COUNT(*) FROM account_holding WHERE account_id = " + ethAccountId))
            .isEqualTo(1L);
        assertThat(queryBigDecimal(
            "SELECT average_buy_in FROM account_holding WHERE account_id = " + ethAccountId + " AND ticker = 'ETH'"))
            .isEqualByComparingTo("1850");
    }

    @Test
    void leavesUnrelatedExternalAccountIdsUntouched() throws SQLException {
        assertThat(queryString("SELECT external_account_id FROM account WHERE id = " + solAccountId))
            .isEqualTo("wallet_solana_" + solWalletId);
        assertThat(queryString("SELECT external_account_id FROM account WHERE id = " + bankAccountId))
            .isEqualTo("gocardless_abc_123");
    }

    /**
     * Ordered last: it is the only test that mutates the shared seeded dataset, so the
     * assertions above must observe the post-migration state, not the post-replay one.
     * (The replay is a no-op while V56 is correct — but that is the property under test,
     * so it cannot be assumed by the tests that run before it.)
     */
    @Test
    @Order(Integer.MAX_VALUE)
    void migrationSqlIsIdempotent_whenReplayedOnAlreadyMigratedData() throws Exception {
        // Replays the REAL V56 file against data it has already migrated (a repair, a
        // restored dump, a replayed deploy). Re-typing the SQL into the test instead
        // would assert nothing at all: after @BeforeAll both statements match zero
        // rows, so a copy passes no matter what the actual migration says -- and it
        // stops tracking the file the moment someone edits it.
        replay("V56__wallet_ethereum_to_evm.sql");

        // A second pass must leave the already-rewritten ids and chains exactly as they
        // were -- not re-split them into a truncated or doubled prefix.
        assertThat(queryString("SELECT chain FROM wallet_address WHERE id = " + ethWalletId))
            .isEqualTo("EVM");
        assertThat(queryString("SELECT external_account_id FROM account WHERE id = " + ethAccountId))
            .isEqualTo("wallet_evm_" + ethWalletId);
        assertThat(queryString("SELECT external_account_id FROM account WHERE id = " + bankAccountId))
            .isEqualTo("gocardless_abc_123");
        // Catches a broadened WHERE (e.g. LIKE 'wallet_%'), which on a replay would
        // silently rewrite the Solana wallet's account to a wallet_evm_ id and orphan it.
        assertThat(queryString("SELECT external_account_id FROM account WHERE id = " + solAccountId))
            .isEqualTo("wallet_solana_" + solWalletId);
        assertThat(queryLong("SELECT COUNT(*) FROM account_holding WHERE account_id = " + ethAccountId))
            .isEqualTo(1L);
    }

    /**
     * V57's replay safety rests on its own effect: once an account is renamed to "EVM Wallet"
     * its {@code a.name = 'ETHEREUM Wallet'} predicate no longer matches. That is easy to
     * break by broadening the WHERE, and the damage would be a user's chosen label — so pin
     * it against the real file rather than trusting the reasoning.
     */
    @Test
    @Order(Integer.MAX_VALUE - 1)
    void v55IsIdempotent_whenReplayedOnAlreadyRenamedAccounts() throws Exception {
        replay("V57__wallet_evm_account_name.sql");

        assertThat(queryString("SELECT name FROM account WHERE id = " + ethAccountId))
            .isEqualTo("EVM Wallet");
        // The two that must never be touched, on a replay least of all.
        assertThat(queryString("SELECT name FROM account WHERE id = " + labelledAccountId))
            .isEqualTo("My Ledger");
        assertThat(queryString("SELECT name FROM account WHERE id = " + trapAccountId))
            .as("a user label identical to the default must survive a replay too")
            .isEqualTo("ETHEREUM Wallet");
        assertThat(queryString("SELECT name FROM account WHERE id = " + bankAccountId))
            .isEqualTo("Checking");
    }

    @Test
    @Order(Integer.MAX_VALUE - 2)
    void latestSchema_hardensAndBackfillsOnlyReconciledBourseDirectData() throws Exception {
        migrateTo("61");
        long memberId;
        long reconciledAccountId;
        long unreconciledAccountId;
        try (Connection conn = connect()) {
            memberId = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Bourse Direct') RETURNING id");
            exec(conn, "INSERT INTO bourse_direct_session (member_id, session_state) VALUES ("
                + memberId + ", 'encrypted-state')");

            reconciledAccountId = insertReturningId(conn,
                "INSERT INTO account (name, type, provider, currency, current_balance, cash_balance, "
                    + "external_account_id, is_manual, member_id) VALUES "
                    + "('Legacy PEA', 'PEA'::account_type, 'Bourse Direct', 'EUR', 1250, 250, "
                    + "'bd-legacy-reconciled', false, " + memberId + ") RETURNING id");
            exec(conn, "INSERT INTO account_holding "
                + "(account_id, ticker, quantity, average_buy_in, current_price) VALUES ("
                + reconciledAccountId + ", 'FR0000000001', 10, 80, 100)");

            // Same legacy shape, but 10 x 100 does not equal total 1500 - cash 250.
            // V64 must refuse to guess that the broker's unlabelled quote was EUR.
            unreconciledAccountId = insertReturningId(conn,
                "INSERT INTO account (name, type, provider, currency, current_balance, cash_balance, "
                    + "external_account_id, is_manual, member_id) VALUES "
                    + "('Legacy CTO', 'COMPTE_TITRES'::account_type, 'Bourse Direct', 'EUR', 1500, 250, "
                    + "'bd-legacy-unreconciled', false, " + memberId + ") RETURNING id");
            exec(conn, "INSERT INTO account_holding "
                + "(account_id, ticker, quantity, average_buy_in, current_price) VALUES ("
                + unreconciledAccountId + ", 'US0000000001', 10, 80, 100)");
        }

        migrateTo("65");

        assertThat(queryString("SELECT sync_status FROM bourse_direct_session WHERE member_id = " + memberId))
            .isEqualTo("IDLE");
        assertThat(queryString("SELECT is_active::text FROM bourse_direct_session WHERE member_id = " + memberId))
            .isEqualTo("true");
        assertThat(queryBigDecimal(
            "SELECT provider_value_eur FROM account_holding WHERE account_id = " + reconciledAccountId))
            .isEqualByComparingTo("1000");
        assertThat(queryBigDecimal(
            "SELECT provider_pnl_eur FROM account_holding WHERE account_id = " + reconciledAccountId))
            .isEqualByComparingTo("200");
        assertThat(queryString(
            "SELECT quote_currency FROM account_holding WHERE account_id = " + reconciledAccountId))
            .isEqualTo("EUR");
        assertThat(queryString(
            "SELECT provider_value_eur::text FROM account_holding WHERE account_id = " + unreconciledAccountId))
            .isNull();

        try (Connection conn = connect()) {
            exec(conn, "UPDATE account_holding SET quote_currency = 'USD', "
                + "provider_value_eur = 1900, provider_pnl_eur = 120 WHERE account_id = " + ethAccountId);
            assertThatThrownBy(() -> exec(conn,
                "UPDATE account_holding SET quote_currency = 'usd' WHERE account_id = " + ethAccountId))
                .isInstanceOf(SQLException.class);

            exec(conn, "UPDATE bourse_direct_session SET sync_status = 'FAILED', "
                + "last_sync_error = 'INTERNAL_ERROR' WHERE member_id = " + memberId);
            assertThatThrownBy(() -> exec(conn,
                "UPDATE bourse_direct_session SET last_sync_error = 'UNKNOWN_ERROR' WHERE member_id = " + memberId))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> exec(conn,
                "UPDATE bourse_direct_session SET sync_status = 'SUCCESS' WHERE member_id = " + memberId))
                .isInstanceOf(SQLException.class);
            exec(conn, "UPDATE bourse_direct_session SET sync_status = 'IDLE', "
                + "last_sync_error = NULL WHERE member_id = " + memberId);
            assertThatThrownBy(() -> exec(conn,
                "UPDATE bourse_direct_session SET sync_status = 'FAILED' WHERE member_id = " + memberId))
                .isInstanceOf(SQLException.class);
            exec(conn, "DELETE FROM family_member WHERE id = " + memberId);
        }

        assertThat(queryString("SELECT quote_currency FROM account_holding WHERE account_id = " + ethAccountId))
            .isEqualTo("USD");
        assertThat(queryBigDecimal(
            "SELECT provider_value_eur FROM account_holding WHERE account_id = " + ethAccountId))
            .isEqualByComparingTo("1900");
        assertThat(queryLong("SELECT COUNT(*) FROM bourse_direct_session WHERE member_id = " + memberId))
            .isZero();
    }

    /** Executes a migration file from the classpath verbatim, as Flyway would. */
    private static void replay(String migrationFile) throws Exception {
        String sql;
        try (var in = WalletEvmMigrationTest.class.getResourceAsStream("/db/migration/" + migrationFile)) {
            assertThat(in).as("%s must be on the test classpath", migrationFile).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

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

    private static long insertAccount(Connection conn, long memberId, String name, String externalId)
        throws SQLException {
        return insertReturningId(conn,
            "INSERT INTO account (name, type, currency, current_balance, external_account_id, is_manual, member_id) "
                + "VALUES ('" + name + "', 'CRYPTO'::account_type, 'EUR', 100, '" + externalId + "', false, "
                + memberId + ") RETURNING id");
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
        return querySingle(sql, rs -> rs.getString(1));
    }

    private static long queryLong(String sql) throws SQLException {
        return querySingle(sql, rs -> rs.getLong(1));
    }

    private static BigDecimal queryBigDecimal(String sql) throws SQLException {
        return querySingle(sql, rs -> rs.getBigDecimal(1));
    }

    /**
     * Reuses one connection across the read-only assertions — a fresh JDBC connect per
     * single-value check pays a TCP handshake and auth round-trip each time. Autocommit
     * means it still sees writes committed by the mutating connections.
     */
    private static <T> T querySingle(String sql, SqlFunction<T> extractor) throws SQLException {
        if (readConn == null || readConn.isClosed()) {
            readConn = connect();
        }
        try (PreparedStatement ps = readConn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("query returned no row: %s", sql).isTrue();
            return extractor.apply(rs);
        }
    }

    private static Connection readConn;

    @AfterAll
    static void closeReadConnection() throws SQLException {
        if (readConn != null) readConn.close();
    }

    @FunctionalInterface
    private interface SqlFunction<T> {
        T apply(ResultSet rs) throws SQLException;
    }
}
