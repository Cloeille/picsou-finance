package com.picsou.migration;

import org.flywaydb.core.Flyway;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@code V86__unbounded_encrypted_session_tokens.sql}, which turns the three
 * encrypted columns whose length a third party controls into {@code TEXT}:
 * {@code trade_republic_session.session_token} and {@code refresh_token}, and
 * {@code degiro_session.session_blob}.
 *
 * <p>The first test reproduces #115 on the pre-migration schema: Trade Republic's session
 * token grew past 1472 bytes, {@code CryptoEncryption} inflates it to more than 2000
 * characters, and the INSERT that persists a session whose 2FA just succeeded is refused by
 * PostgreSQL. The other two pin what the migration must leave behind: the {@code text} type
 * on all three columns, the {@code NOT NULL} that the type change must not drop, and a
 * round trip of values far wider than any bound this migration removes.
 *
 * <p>Runs against real PostgreSQL via Testcontainers, like the other migration tests in this
 * package (the documented exception to the H2 rule, see {@code backend/CLAUDE.md}): the
 * refusal under test is a PostgreSQL length check, and {@code information_schema} is where
 * the resulting type is read back from. Neither is something H2 reproduces faithfully.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EncryptedSessionTokenWidthMigrationTest {

    static {
        // Same negotiation problem as WalletEvmMigrationTest: docker-java otherwise drops to
        // API 1.32, which Engine >= 28 refuses, and the failure is indistinguishable from
        // "this machine has no Docker" -- so the guard below would skip on a capable host.
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Container
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** @see WalletEvmMigrationTest#dockerAvailable() -- CI turns a skip into a hard failure. */
    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found. "
                    + "The V86 migration test cannot be skipped here. "
                    + "Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    /**
     * 2004 characters: what {@code CryptoEncryption} produces for a 1473-byte token, the
     * smallest plaintext whose ciphertext no longer fits {@code VARCHAR(2000)}.
     */
    private static final String FIRST_OVERFLOWING_CIPHERTEXT = "x".repeat(2004);

    /** Comfortably beyond every bound this migration removes (4000 was the widest). */
    private static final String WIDE_CIPHERTEXT = "y".repeat(12_000);

    private static long memberId;

    /** Brings the schema to V79, the state of a deployed instance, and seeds one member. */
    @BeforeAll
    static void migrateToPreviousStateAndSeed() throws SQLException {
        migrateTo("79");
        try (Connection conn = connect()) {
            memberId = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Test') RETURNING id");
        }
    }

    /** Reproduces #115 on the V79 schema: the first ciphertext that outgrows VARCHAR(2000) is refused. */
    @Test
    @Order(1)
    void beforeV86_aCurrentTradeRepublicTokenIsRefusedAfterASuccessfulTwoFactorLogin() throws SQLException {
        try (Connection conn = connect()) {
            assertThatThrownBy(() -> insertTradeRepublicSession(conn, FIRST_OVERFLOWING_CIPHERTEXT, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("value too long");
        }
    }

    /** After V86 the three columns read back as {@code text}, and the NOT NULL they carried survives the type change. */
    @Test
    @Order(2)
    void v86_makesTheThreeThirdPartyControlledColumnsUnboundedAndKeepsNotNull() throws SQLException {
        migrateTo("86");

        assertThat(columnType("trade_republic_session", "session_token")).isEqualTo("text");
        assertThat(columnType("trade_republic_session", "refresh_token")).isEqualTo("text");
        assertThat(columnType("degiro_session", "session_blob")).isEqualTo("text");

        // ALTER COLUMN ... TYPE must not loosen the constraints that were on the column.
        assertThat(isNullable("trade_republic_session", "session_token")).isEqualTo("NO");
        assertThat(isNullable("trade_republic_session", "refresh_token")).isEqualTo("YES");
        assertThat(isNullable("degiro_session", "session_blob")).isEqualTo("NO");
    }

    /** Values wider than any bound the migration removed round-trip intact on both tables. */
    @Test
    @Order(3)
    void afterV86_valuesWiderThanAnyFormerBoundRoundTripIntact() throws SQLException {
        try (Connection conn = connect()) {
            long trId = insertTradeRepublicSession(conn, WIDE_CIPHERTEXT, WIDE_CIPHERTEXT);
            assertThat(queryString(conn, "SELECT session_token FROM trade_republic_session WHERE id = " + trId))
                .isEqualTo(WIDE_CIPHERTEXT);
            assertThat(queryString(conn, "SELECT refresh_token FROM trade_republic_session WHERE id = " + trId))
                .isEqualTo(WIDE_CIPHERTEXT);

            long degiroId;
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO degiro_session (member_id, session_blob) VALUES (?, ?) RETURNING id")) {
                ps.setLong(1, memberId);
                ps.setString(2, WIDE_CIPHERTEXT);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    degiroId = rs.getLong(1);
                }
            }
            assertThat(queryString(conn, "SELECT session_blob FROM degiro_session WHERE id = " + degiroId))
                .isEqualTo(WIDE_CIPHERTEXT);
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Inserts a Trade Republic session row for the seeded member. The tokens go in as bound
     * parameters, so a 12 000-character value never becomes an SQL literal.
     */
    private static long insertTradeRepublicSession(Connection conn, String sessionToken, String refreshToken)
        throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO trade_republic_session (member_id, session_token, refresh_token) "
                + "VALUES (?, ?, ?) RETURNING id")) {
            ps.setLong(1, memberId);
            ps.setString(2, sessionToken);
            ps.setString(3, refreshToken);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** {@code information_schema.columns.data_type} of one column, e.g. {@code text}. */
    private static String columnType(String table, String column) throws SQLException {
        return informationSchema("data_type", table, column);
    }

    /** {@code information_schema.columns.is_nullable} of one column: {@code YES} or {@code NO}. */
    private static String isNullable(String table, String column) throws SQLException {
        return informationSchema("is_nullable", table, column);
    }

    /** One field of {@code information_schema.columns} for a column of the public schema. */
    private static String informationSchema(String field, String table, String column) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + field + " FROM information_schema.columns "
                     + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("column %s.%s must exist", table, column).isTrue();
                return rs.getString(1);
            }
        }
    }

    /** Applies the migration chain up to {@code version} inclusive, the way application.yml does. */
    private static void migrateTo(String version) {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(version)
            .outOfOrder(true) // mirrors application.yml
            .load()
            .migrate();
    }

    /** A fresh JDBC connection to the container; callers close it. */
    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Runs an {@code INSERT ... RETURNING id} and returns that id. */
    private static long insertReturningId(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** First column of the first row of {@code sql}, which must return one. */
    private static String queryString(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("query returned no row: %s", sql).isTrue();
            return rs.getString(1);
        }
    }
}
