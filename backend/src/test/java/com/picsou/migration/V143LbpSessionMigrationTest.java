package com.picsou.migration;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V143 applies on top of the whole chain and owns the {@code lbp_session} table.
 *
 * <p>Every statement is a constant with bound parameters: no value is concatenated into SQL.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class V143LbpSessionMigrationTest {

    private static final String INSERT_SESSION =
        "INSERT INTO lbp_session (member_id, session_state) VALUES (?, ?)";
    private static final String INSERT_SESSION_STATUS =
        "INSERT INTO lbp_session (member_id, session_state, sync_status) VALUES (?, ?, ?)";
    private static final String INSERT_SESSION_ERROR =
        "INSERT INTO lbp_session (member_id, session_state, sync_status, last_sync_error) "
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
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found. "
                    + "The V143 migration test cannot be skipped. Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    @BeforeAll
    static void migrate() throws SQLException {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("143")
            .load()
            .migrate();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void exec(String sql, Object... params) throws SQLException {
        try (Connection c = connection(); PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                st.setObject(i + 1, params[i]);
            }
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
    void theTableHasNoCredentialColumn() throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "SELECT column_name FROM information_schema.columns WHERE table_name = 'lbp_session'");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) columns.add(rs.getString(1));
        }
        assertThat(columns).isNotEmpty()
            .contains("member_id", "session_state", "is_active", "sync_status", "last_sync_error")
            .doesNotContain("encrypted_credentials")
            .noneMatch(name -> name.contains("password") || name.contains("credential")
                || name.contains("keypad"));
    }

    @Test
    void acceptsEveryErrorCodeAndNoOther() throws SQLException {
        for (SidecarErrorCode code : SidecarBank.LBP.codes()) {
            long member = newMember("ok-" + code.name());
            exec(INSERT_SESSION_ERROR, member, "x", "FAILED", code.name());
        }
        long member = newMember("bad-code");
        assertThatThrownBy(() -> exec(INSERT_SESSION_ERROR, member, "x", "FAILED", "NOT_A_REAL_CODE"))
            .hasMessageContaining("ck_lbp_session_last_sync_error");
    }

    @Test
    void aFailedSyncMustCarryAnErrorAndOnlyAFailedOne() throws SQLException {
        long a = newMember("failed-no-error");
        assertThatThrownBy(() -> exec(INSERT_SESSION_STATUS, a, "x", "FAILED"))
            .hasMessageContaining("ck_lbp_session_failed_error");
        long b = newMember("idle-with-error");
        assertThatThrownBy(() -> exec(INSERT_SESSION_ERROR, b, "x", "IDLE", "INTERNAL_ERROR"))
            .hasMessageContaining("ck_lbp_session_failed_error");
    }

    @Test
    void refusesAnUnknownSyncStatusAndASecondRowForTheSameMember() throws SQLException {
        long member = newMember("one-row");
        assertThatThrownBy(() -> exec(INSERT_SESSION_STATUS, member, "x", "PENDING"))
            .hasMessageContaining("ck_lbp_session_sync_status");

        exec(INSERT_SESSION, member, "x");
        assertThatThrownBy(() -> exec(INSERT_SESSION, member, "y"))
            .hasMessageContaining("lbp_session_member_id_key");
    }

    @Test
    void deletingTheMemberDeletesTheSession() throws SQLException {
        long member = newMember("cascade");
        exec(INSERT_SESSION, member, "x");

        exec("DELETE FROM family_member WHERE id = ?", member);

        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "SELECT COUNT(*) FROM lbp_session WHERE member_id = ?")) {
            st.setLong(1, member);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }
}
