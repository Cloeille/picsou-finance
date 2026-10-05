package com.picsou.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
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

/**
 * Verifies {@code V77__merge_duplicate_sync_accounts.sql}, which repairs the rows
 * {@code WalletSyncService} wrote while it was the one connector missing the
 * "don't resurrect a deleted account" guard: every scheduled resync inserted a new account for
 * the same {@code external_account_id}, so a wallet deleted ten times left ten rows, each
 * holding a slice of the balance history.
 *
 * <p>What is worth pinning here is not the row count but where the history ends up. The
 * survivor has to absorb the losers' snapshots, and it has to do so through a
 * {@code UNIQUE (account_id, date)} that makes the obvious {@code UPDATE} fail the moment two
 * duplicates were synced on the same day — which is the normal case, not the edge one, since
 * the duplicates were produced by a daily job.
 *
 * <p>Runs against real PostgreSQL via Testcontainers: the migration is built on window
 * functions, {@code DELETE ... USING} and row-value comparison, none of which H2 reproduces
 * faithfully enough to be worth trusting.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class DuplicateSyncAccountMergeMigrationTest {

    static {
        // See AccountLogoKeyMigrationTest: docker-java otherwise negotiates down to API 1.32,
        // which Engine >= 28 refuses, and that failure looks exactly like "no Docker here".
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
                    + "The V77 migration test cannot be skipped here. "
                    + "Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    private static long liveDuplicateId;
    private static long oldDuplicateId;
    private static long midDuplicateId;
    private static long soloAccountId;
    private static long manualTwinAId;
    private static long manualTwinBId;
    private static long allDeletedNewestId;
    private static long allDeletedOldestId;
    private static long sameIdBankAId;
    private static long sameIdBankBId;
    private static long goalId;

    /** Brings the schema to V76 — a deployed instance — reproduces the damage, then applies V77. */
    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        migrateTo("76");

        try (Connection conn = connect()) {
            long memberId = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Test') RETURNING id");

            // The reported shape: one wallet deleted twice, so three rows for one external id.
            oldDuplicateId = insertAccount(conn, memberId, "Ledger BTC", "wallet_bitcoin_2", false,
                "2026-07-01", "2026-07-10");
            midDuplicateId = insertAccount(conn, memberId, "Ledger BTC", "wallet_bitcoin_2", false,
                "2026-07-11", "2026-07-20");
            liveDuplicateId = insertAccount(conn, memberId, "Ledger BTC", "wallet_bitcoin_2", false,
                "2026-07-21", null);

            // Distinct days, which must all survive the merge.
            insertSnapshot(conn, oldDuplicateId, "2026-07-02", "100");
            insertSnapshot(conn, midDuplicateId, "2026-07-12", "200");
            insertSnapshot(conn, liveDuplicateId, "2026-07-22", "300");

            // Same day on three rows: only one can land on the survivor. The most recently
            // created source wins, so the survivor's own row stays put and the mid-ranked
            // duplicate beats the oldest.
            insertSnapshot(conn, oldDuplicateId, "2026-08-01", "1");
            insertSnapshot(conn, midDuplicateId, "2026-08-01", "2");
            insertSnapshot(conn, liveDuplicateId, "2026-08-01", "3");
            // Same day on two losers only -- the survivor has nothing to defend, so the better
            // ranked loser's value is the one that must arrive.
            insertSnapshot(conn, oldDuplicateId, "2026-08-02", "10");
            insertSnapshot(conn, midDuplicateId, "2026-08-02", "20");

            // A holding and a transaction on a loser: both have to follow the history over.
            insertHolding(conn, oldDuplicateId, "BTC", "3");
            insertTransaction(conn, midDuplicateId, "2026-07-15", "42");
            // The same ticker on a better-ranked loser, so the UNIQUE (account_id, ticker)
            // collision branch actually runs and the more recent quantity is the one kept.
            insertHolding(conn, midDuplicateId, "BTC", "5");

            // Ownership: 60% on a loser and 70% on the survivor. Unioned they would describe a
            // 130% account, which no constraint would catch -- the survivor's set must win whole.
            insertOwnership(conn, liveDuplicateId, memberId, "70");
            long otherMemberId = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Other') RETURNING id");
            insertOwnership(conn, oldDuplicateId, otherMemberId, "60");

            // Exchange positions: one colliding on (product, ticker), one not.
            insertPosition(conn, oldDuplicateId, "SPOT", "BTC", "1");
            insertPosition(conn, midDuplicateId, "SPOT", "BTC", "2");
            insertPosition(conn, oldDuplicateId, "STAKING", "ETH", "9");

            // Both duplicates in one goal. The pair is the primary key, so one link survives.
            // chk_goal_deadline demands a future date, so it cannot be a literal.
            goalId = insertReturningId(conn,
                "INSERT INTO goal (name, target_amount, deadline, member_id) "
                    + "VALUES ('Trip', 1000, CURRENT_DATE + 365, " + memberId + ") RETURNING id");
            exec(conn, "INSERT INTO goal_account (goal_id, account_id) VALUES (" + goalId + ", " + oldDuplicateId + ")");
            exec(conn, "INSERT INTO goal_account (goal_id, account_id) VALUES (" + goalId + ", " + liveDuplicateId + ")");

            // Never duplicated: must come through untouched.
            soloAccountId = insertAccount(conn, memberId, "Meria", "crypto_exchange_meria", false,
                "2026-07-01", null);
            insertSnapshot(conn, soloAccountId, "2026-07-02", "500");

            // external_account_id is free text on manual accounts (see V75), so two manual rows
            // may legitimately share one. Merging them would destroy user data.
            manualTwinAId = insertAccount(conn, memberId, "Cash A", "my-notes", true, "2026-07-01", null);
            manualTwinBId = insertAccount(conn, memberId, "Cash B", "my-notes", true, "2026-07-02", null);

            // Two banks handing out the same opaque account id. Same member, same external id,
            // different institutions -- merging them would destroy one bank's account outright.
            sameIdBankAId = insertAccount(conn, memberId, "Compte A", "12345", false,
                "2026-07-01", null, "Bank A");
            sameIdBankBId = insertAccount(conn, memberId, "Compte B", "12345", false,
                "2026-07-02", null, "Bank B");

            // A wallet deleted and never resurrected: all rows soft-deleted. The newest wins,
            // and the survivor must stay deleted -- merging is not undeleting.
            allDeletedOldestId = insertAccount(conn, memberId, "Old SOL", "wallet_solana_9", false,
                "2026-06-01", "2026-06-05");
            allDeletedNewestId = insertAccount(conn, memberId, "Old SOL", "wallet_solana_9", false,
                "2026-06-06", "2026-06-10");
            insertSnapshot(conn, allDeletedOldestId, "2026-06-02", "7");
        }

        migrateTo("77");
    }

    @Test
    void keepsTheLiveRowAndDropsTheDuplicates() throws SQLException {
        assertThat(count("SELECT count(*) FROM account WHERE external_account_id = 'wallet_bitcoin_2'"))
            .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM account WHERE id = " + liveDuplicateId))
            .as("the live row is the survivor, not merely the newest")
            .isEqualTo(1);
    }

    @Test
    void survivorAbsorbsTheWholeHistory() throws SQLException {
        // 3 distinct days + one row for 2026-08-01 + one for 2026-08-02 = 5.
        assertThat(count("SELECT count(*) FROM balance_snapshot WHERE account_id = " + liveDuplicateId))
            .isEqualTo(5);
        assertThat(queryString(
            "SELECT min(date)::text || '..' || max(date)::text FROM balance_snapshot WHERE account_id = "
                + liveDuplicateId))
            .as("the merged curve is continuous, not a slice")
            .isEqualTo("2026-07-02..2026-08-02");
    }

    @Test
    void resolvesSameDayCollisionsInFavourOfTheMostRecentAccount() throws SQLException {
        // The survivor already held 2026-08-01; its own value must not be overwritten.
        assertThat(queryString("SELECT balance::numeric(20,0)::text FROM balance_snapshot "
            + "WHERE account_id = " + liveDuplicateId + " AND date = '2026-08-01'"))
            .isEqualTo("3");
        // Neither loser held 2026-08-02 on the survivor, so the better ranked one lands.
        assertThat(queryString("SELECT balance::numeric(20,0)::text FROM balance_snapshot "
            + "WHERE account_id = " + liveDuplicateId + " AND date = '2026-08-02'"))
            .isEqualTo("20");
    }

    @Test
    void movesEverythingElseTheLosersOwned() throws SQLException {
        assertThat(count("SELECT count(*) FROM account_holding WHERE account_id = " + liveDuplicateId
            + " AND ticker = 'BTC'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transaction WHERE account_id = " + liveDuplicateId))
            .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM goal_account WHERE goal_id = " + goalId))
            .as("the duplicate link collapses into the one the survivor already had")
            .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM goal_account WHERE goal_id = " + goalId
            + " AND account_id = " + liveDuplicateId)).isEqualTo(1);
    }

    @Test
    void keepsOneHoldingPerTickerAndPrefersTheMoreRecentAccount() throws SQLException {
        assertThat(count("SELECT count(*) FROM account_holding WHERE account_id = " + liveDuplicateId))
            .isEqualTo(1);
        assertThat(queryString("SELECT quantity::numeric(20,0)::text FROM account_holding "
            + "WHERE account_id = " + liveDuplicateId + " AND ticker = 'BTC'"))
            .as("the better ranked loser's quantity wins, not the oldest")
            .isEqualTo("5");
    }

    /**
     * Ownership is a description of how one whole is split, so the sets are never unioned:
     * 70% on the survivor plus 60% on a loser would total 130%, and only the per-row CHECK
     * exists to catch it — which it would not.
     */
    @Test
    void takesOneOwnershipSetWholeRatherThanUnioningThem() throws SQLException {
        assertThat(count("SELECT count(*) FROM account_ownership WHERE account_id = " + liveDuplicateId))
            .isEqualTo(1);
        assertThat(queryString("SELECT sum(share_percent)::numeric(6,0)::text FROM account_ownership "
            + "WHERE account_id = " + liveDuplicateId))
            .as("the survivor's own share stands; the loser's is dropped, not added")
            .isEqualTo("70");
    }

    @Test
    void reconcilesExchangePositionsOnTheirCompositeKey() throws SQLException {
        // (SPOT, BTC) collided and collapses to one; (STAKING, ETH) had no rival and moves.
        assertThat(count("SELECT count(*) FROM crypto_exchange_position WHERE account_id = "
            + liveDuplicateId)).isEqualTo(2);
        assertThat(queryString("SELECT quantity::numeric(20,0)::text FROM crypto_exchange_position "
            + "WHERE account_id = " + liveDuplicateId + " AND product = 'SPOT' AND ticker = 'BTC'"))
            .isEqualTo("2");
    }

    @Test
    void leavesUnduplicatedAndManualAccountsAlone() throws SQLException {
        assertThat(count("SELECT count(*) FROM account WHERE id = " + soloAccountId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM balance_snapshot WHERE account_id = " + soloAccountId))
            .isEqualTo(1);
        // Two manual accounts sharing free-text external ids are not duplicates of anything.
        assertThat(count("SELECT count(*) FROM account WHERE id IN ("
            + manualTwinAId + ", " + manualTwinBId + ")")).isEqualTo(2);
    }

    /**
     * An Enable Banking external id is the bank's own opaque string, so two institutions may
     * hand out the same one. Keying the merge on the id alone would collapse two real accounts
     * held at different banks into one and delete the other outright.
     */
    @Test
    void neverMergesTwoBanksThatShareAnOpaqueAccountId() throws SQLException {
        assertThat(count("SELECT count(*) FROM account WHERE id IN ("
            + sameIdBankAId + ", " + sameIdBankBId + ")")).isEqualTo(2);
    }

    @Test
    void mergingAnAllDeletedGroupKeepsItDeleted() throws SQLException {
        assertThat(count("SELECT count(*) FROM account WHERE id = " + allDeletedOldestId)).isZero();
        assertThat(count("SELECT count(*) FROM account WHERE id = " + allDeletedNewestId
            + " AND deleted_at IS NOT NULL"))
            .as("the newest row survives and stays deleted -- a merge is not an undelete")
            .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM balance_snapshot WHERE account_id = " + allDeletedNewestId))
            .as("history follows even when nothing is live, so a later restore is not empty")
            .isEqualTo(1);
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

    private static long insertAccount(
        Connection conn, long memberId, String name, String externalId, boolean manual,
        String createdAt, String deletedAt
    ) throws SQLException {
        return insertAccount(conn, memberId, name, externalId, manual, createdAt, deletedAt, "BTC");
    }

    private static long insertAccount(
        Connection conn, long memberId, String name, String externalId, boolean manual,
        String createdAt, String deletedAt, String provider
    ) throws SQLException {
        return insertReturningId(conn,
            "INSERT INTO account (name, type, provider, currency, current_balance, external_account_id, "
                + "is_manual, member_id, created_at, deleted_at) VALUES ('" + name + "', 'CRYPTO'::account_type, "
                + "'" + provider + "', 'EUR', 100, '" + externalId + "', " + manual + ", " + memberId + ", '"
                + createdAt + "', " + (deletedAt == null ? "NULL" : "'" + deletedAt + "'") + ") RETURNING id");
    }

    private static void insertSnapshot(Connection conn, long accountId, String date, String balance)
        throws SQLException {
        exec(conn, "INSERT INTO balance_snapshot (account_id, date, balance, invested_amount) VALUES ("
            + accountId + ", '" + date + "', " + balance + ", 0)");
    }

    private static void insertHolding(Connection conn, long accountId, String ticker, String quantity)
        throws SQLException {
        exec(conn, "INSERT INTO account_holding (account_id, ticker, name, quantity, current_price) VALUES ("
            + accountId + ", '" + ticker + "', '" + ticker + "', " + quantity + ", 1)");
    }

    private static void insertOwnership(Connection conn, long accountId, long memberId, String share)
        throws SQLException {
        exec(conn, "INSERT INTO account_ownership (account_id, member_id, share_percent) VALUES ("
            + accountId + ", " + memberId + ", " + share + ")");
    }

    private static void insertPosition(
        Connection conn, long accountId, String product, String ticker, String quantity
    ) throws SQLException {
        exec(conn, "INSERT INTO crypto_exchange_position (account_id, product, ticker, quantity) VALUES ("
            + accountId + ", '" + product + "', '" + ticker + "', " + quantity + ")");
    }

    private static void insertTransaction(Connection conn, long accountId, String date, String amount)
        throws SQLException {
        exec(conn, "INSERT INTO transaction (account_id, tx_type, date, amount) VALUES ("
            + accountId + ", 'DEPOSIT', '" + date + "', " + amount + ")");
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

    private static Connection readConn;

    private static String queryString(String sql) throws SQLException {
        if (readConn == null || readConn.isClosed()) {
            readConn = connect();
        }
        try (PreparedStatement ps = readConn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("query returned no row: %s", sql).isTrue();
            return rs.getString(1);
        }
    }

    private static int count(String sql) throws SQLException {
        return Integer.parseInt(queryString(sql));
    }

    @AfterAll
    static void closeReadConnection() throws SQLException {
        if (readConn != null) readConn.close();
    }
}
