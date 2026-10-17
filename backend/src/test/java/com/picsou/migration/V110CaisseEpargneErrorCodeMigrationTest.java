package com.picsou.migration;

import com.picsou.port.CaisseEpargneErrorCode;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves a database already at V109 can be upgraded in place to accept the current error enum.
 * Every statement is a constant with bound parameters: no value is concatenated into SQL.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class V110CaisseEpargneErrorCodeMigrationTest {

    private static final String INSERT_SESSION_ERROR =
        "INSERT INTO caisse_epargne_session (member_id, session_state, sync_status, last_sync_error) "
            + "VALUES (?, ?, ?, ?)";

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
                "PICSOU_REQUIRE_DOCKER_TESTS is set but the V110 upgrade test cannot be skipped.");
        }
        return available;
    }

    @BeforeAll
    static void migrateToV109() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("109")
            .load()
            .migrate();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void insertFailedSession(long member, String state, String error) throws SQLException {
        try (Connection c = connection(); PreparedStatement st = c.prepareStatement(INSERT_SESSION_ERROR)) {
            st.setLong(1, member);
            st.setString(2, state);
            st.setString(3, "FAILED");
            st.setString(4, error);
            st.execute();
        }
    }

    private static long newMember(String name) throws SQLException {
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "INSERT INTO family_member (display_name) VALUES (?) RETURNING id")) {
            st.setString(1, name);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void upgradesAnExistingV109DatabaseAndAcceptsTheWholeCurrentEnum() throws SQLException {
        long member = newMember("before-v110");
        insertFailedSession(member, "existing-session", "AUTH_ATTEMPT_EXPIRED");

        for (String code : new String[]{"KEYPAD_EXPIRED", "INVALID_POSITIONS"}) {
            long rejectedMember = newMember("v109-rejects-" + code);
            assertThatThrownBy(() -> insertFailedSession(rejectedMember, "x", code))
                .hasMessageContaining("ck_caisse_epargne_session_last_sync_error");
        }

        Flyway flyway = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("110") // later migrations are covered by their own tests
            .load();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("109");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("110");

        for (CaisseEpargneErrorCode code : CaisseEpargneErrorCode.values()) {
            long acceptedMember = newMember("v110-accepts-" + code.name());
            insertFailedSession(acceptedMember, "x", code.name());
        }
        long invalidMember = newMember("v110-rejects-unknown");
        assertThatThrownBy(() -> insertFailedSession(invalidMember, "x", "NOT_A_REAL_CODE"))
            .hasMessageContaining("ck_caisse_epargne_session_last_sync_error");

        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "SELECT session_state, last_sync_error FROM caisse_epargne_session WHERE member_id = ?")) {
            st.setLong(1, member);
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("session_state")).isEqualTo("existing-session");
                assertThat(rs.getString("last_sync_error")).isEqualTo("AUTH_ATTEMPT_EXPIRED");
            }
        }
    }
}
