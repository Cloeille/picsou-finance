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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the V96 Trade Republic valuation backfill against real PostgreSQL. */
@Testcontainers
@EnabledIf("dockerAvailable")
class TradeRepublicValuationMigrationTest {

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
                    + "The V96 migration test cannot be skipped. Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    private static long reconciledAccountId;
    private static long roundedAccountId;
    private static long nearMismatchAccountId;
    private static long peaWithCashAccountId;
    private static long partiallyBackfilledAccountId;
    private static long otherProviderAccountId;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        migrateTo("95");

        try (Connection conn = connect()) {
            long memberId = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Trade Republic') RETURNING id");

            reconciledAccountId = insertAccount(
                conn, memberId, "Reconciled CTO", "COMPTE_TITRES", "Trade Republic", "530.00", "tr-reconciled");
            insertHolding(conn, reconciledAccountId, "TR-A", "2", "80", "100", null, null);
            insertHolding(conn, reconciledAccountId, "TR-B", "3", "90", "110", null, null);

            // The adapter rounds each original position to cents before summing.
            roundedAccountId = insertAccount(
                conn, memberId, "Rounded CTO", "COMPTE_TITRES", "Trade Republic", "0.66", "tr-rounded");
            insertHolding(conn, roundedAccountId, "ROUND-A", "0.333", "1", "1", null, null);
            insertHolding(conn, roundedAccountId, "ROUND-B", "0.333", "1", "1", null, null);

            // Six cents is close, but cannot be explained by per-position cent rounding.
            nearMismatchAccountId = insertAccount(
                conn, memberId, "Near mismatch", "COMPTE_TITRES", "Trade Republic", "530.06", "tr-near");
            insertHolding(conn, nearMismatchAccountId, "NEAR-A", "2", "80", "100", null, null);
            insertHolding(conn, nearMismatchAccountId, "NEAR-B", "3", "90", "110", null, null);

            peaWithCashAccountId = insertAccount(
                conn, memberId, "PEA with cash", "PEA", "Trade Republic", "630.00", "tr-pea");
            insertHolding(conn, peaWithCashAccountId, "PEA-A", "2", "80", "100", null, null);
            insertHolding(conn, peaWithCashAccountId, "PEA-B", "3", "90", "110", null, null);

            partiallyBackfilledAccountId = insertAccount(
                conn, memberId, "Partial metadata", "COMPTE_TITRES", "Trade Republic", "530.00", "tr-partial");
            insertHolding(conn, partiallyBackfilledAccountId, "PARTIAL-A", "2", "80", "100", "200", "EUR");
            insertHolding(conn, partiallyBackfilledAccountId, "PARTIAL-B", "3", "90", "110", null, null);

            otherProviderAccountId = insertAccount(
                conn, memberId, "Other provider", "COMPTE_TITRES", "Bourse Direct", "530.00", "bd-control");
            insertHolding(conn, otherProviderAccountId, "BD-A", "2", "80", "100", null, null);
            insertHolding(conn, otherProviderAccountId, "BD-B", "3", "90", "110", null, null);
        }

        migrateTo("96");
    }

    @Test
    void backfillsOnlyCompleteExactlyReconciledLegacyTradeRepublicAccounts() throws SQLException {
        assertHolding(reconciledAccountId, "TR-A", "200", "EUR");
        assertHolding(reconciledAccountId, "TR-B", "330", "EUR");
        assertThat(queryBigDecimal(reconciledAccountId, "TR-A", "provider_pnl_eur")).isNull();

        assertHolding(roundedAccountId, "ROUND-A", "0.33", "EUR");
        assertHolding(roundedAccountId, "ROUND-B", "0.33", "EUR");

        assertHolding(nearMismatchAccountId, "NEAR-A", null, null);
        assertHolding(peaWithCashAccountId, "PEA-A", null, null);
        assertHolding(partiallyBackfilledAccountId, "PARTIAL-A", "200", "EUR");
        assertHolding(partiallyBackfilledAccountId, "PARTIAL-B", null, null);
        assertHolding(otherProviderAccountId, "BD-A", null, null);
    }

    private static void assertHolding(long accountId, String ticker, String valueEur, String currency)
        throws SQLException {
        BigDecimal actualValue = queryBigDecimal(accountId, ticker, "provider_value_eur");
        if (valueEur == null) {
            assertThat(actualValue).isNull();
        } else {
            assertThat(actualValue).isEqualByComparingTo(valueEur);
        }
        assertThat(queryString(accountId, ticker, "quote_currency")).isEqualTo(currency);
    }

    private static long insertAccount(
        Connection conn,
        long memberId,
        String name,
        String type,
        String provider,
        String currentBalance,
        String externalId
    ) throws SQLException {
        return insertReturningId(conn,
            "INSERT INTO account (name, type, provider, currency, current_balance, external_account_id, "
                + "is_manual, member_id) VALUES ('" + name + "', '" + type + "'::account_type, '"
                + provider + "', 'EUR', " + currentBalance + ", '" + externalId + "', false, "
                + memberId + ") RETURNING id");
    }

    private static void insertHolding(
        Connection conn,
        long accountId,
        String ticker,
        String quantity,
        String averageBuyIn,
        String currentPrice,
        String providerValueEur,
        String quoteCurrency
    ) throws SQLException {
        String valueSql = providerValueEur == null ? "NULL" : providerValueEur;
        String currencySql = quoteCurrency == null ? "NULL" : "'" + quoteCurrency + "'";
        exec(conn,
            "INSERT INTO account_holding (account_id, ticker, quantity, average_buy_in, current_price, "
                + "provider_value_eur, quote_currency) VALUES (" + accountId + ", '" + ticker + "', "
                + quantity + ", " + averageBuyIn + ", " + currentPrice + ", " + valueSql + ", "
                + currencySql + ")");
    }

    private static void migrateTo(String version) {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(version)
            .outOfOrder(true)
            .load()
            .migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long insertReturningId(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("insert returned no id: %s", sql).isTrue();
            return rs.getLong(1);
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private static BigDecimal queryBigDecimal(long accountId, String ticker, String column)
        throws SQLException {
        return queryHolding(accountId, ticker, column, rs -> rs.getBigDecimal(1));
    }

    private static String queryString(long accountId, String ticker, String column) throws SQLException {
        return queryHolding(accountId, ticker, column, rs -> rs.getString(1));
    }

    private static <T> T queryHolding(long accountId, String ticker, String column, SqlFunction<T> extractor)
        throws SQLException {
        if (readConn == null || readConn.isClosed()) {
            readConn = connect();
        }
        String sql = "SELECT " + column + " FROM account_holding WHERE account_id = ? AND ticker = ?";
        try (PreparedStatement ps = readConn.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            ps.setString(2, ticker);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("holding %s/%s exists", accountId, ticker).isTrue();
                return extractor.apply(rs);
            }
        }
    }

    private static Connection readConn;

    @AfterAll
    static void closeReadConnection() throws SQLException {
        if (readConn != null) {
            readConn.close();
        }
    }

    @FunctionalInterface
    private interface SqlFunction<T> {
        T apply(ResultSet rs) throws SQLException;
    }
}
