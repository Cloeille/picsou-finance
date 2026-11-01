package com.picsou.migration;

import com.picsou.port.CaisseEpargneErrorCode;
import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V109 applies on top of the whole chain. Its original CHECK predates the two later keypad
 * error codes; V110 adds those without rewriting this already-applied migration.
 *
 * <p>Every statement is a constant with bound parameters: no value is concatenated into SQL.
 */
@RequiresPostgres
class V109CaisseEpargneSessionMigrationTest {

    private static final String INSERT_SESSION =
        "INSERT INTO caisse_epargne_session (member_id, session_state) VALUES (?, ?)";
    private static final String INSERT_SESSION_STATUS =
        "INSERT INTO caisse_epargne_session (member_id, session_state, sync_status) VALUES (?, ?, ?)";
    private static final String INSERT_SESSION_ERROR =
        "INSERT INTO caisse_epargne_session (member_id, session_state, sync_status, last_sync_error) "
            + "VALUES (?, ?, ?, ?)";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @BeforeAll
    static void migrate() throws SQLException {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("109")
            .load()
            .migrate();
    }

    private static void exec(String sql, Object... params) throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES); PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                st.setObject(i + 1, params[i]);
            }
            st.execute();
        }
    }

    private static long newMember(String name) throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES);
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
        try (Connection c = PostgresContainers.connection(POSTGRES);
             PreparedStatement st = c.prepareStatement(
                 "SELECT column_name FROM information_schema.columns WHERE table_name = 'caisse_epargne_session'");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) columns.add(rs.getString(1));
        }
        assertThat(columns).isNotEmpty()
            .contains("member_id", "session_state", "is_active", "sync_status", "last_sync_error")
            .doesNotContain("encrypted_credentials")
            .noneMatch(name -> name.contains("password") || name.contains("credential"));
    }

    @Test
    void acceptsEveryErrorCodeSupportedByV109AndNoOther() throws SQLException {
        for (CaisseEpargneErrorCode code : CaisseEpargneErrorCode.values()) {
            if (code == CaisseEpargneErrorCode.KEYPAD_EXPIRED || code == CaisseEpargneErrorCode.INVALID_POSITIONS) {
                continue;
            }
            long member = newMember("ok-" + code.name());
            exec(INSERT_SESSION_ERROR, member, "x", "FAILED", code.name());
        }
        for (String code : List.of("KEYPAD_EXPIRED", "INVALID_POSITIONS", "NOT_A_REAL_CODE")) {
            long member = newMember("bad-" + code);
            assertThatThrownBy(() -> exec(INSERT_SESSION_ERROR, member, "x", "FAILED", code))
                .hasMessageContaining("ck_caisse_epargne_session_last_sync_error");
        }
    }

    @Test
    void acceptsTheV109LoginErrorCodesByName() throws SQLException {
        for (String code : List.of("INVALID_CREDENTIALS", "KEYPAD_CHANGED",
            "APP_VALIDATION_TIMEOUT", "AUTH_ATTEMPT_EXPIRED")) {
            long member = newMember("login-" + code);
            exec(INSERT_SESSION_ERROR, member, "x", "FAILED", code);
        }
    }

    @Test
    void aFailedSyncMustCarryAnErrorAndOnlyAFailedOne() throws SQLException {
        long a = newMember("failed-no-error");
        assertThatThrownBy(() -> exec(INSERT_SESSION_STATUS, a, "x", "FAILED"))
            .hasMessageContaining("ck_caisse_epargne_session_failed_error");
        long b = newMember("idle-with-error");
        assertThatThrownBy(() -> exec(INSERT_SESSION_ERROR, b, "x", "IDLE", "INTERNAL_ERROR"))
            .hasMessageContaining("ck_caisse_epargne_session_failed_error");
    }

    @Test
    void refusesAnUnknownSyncStatusAndASecondRowForTheSameMember() throws SQLException {
        long member = newMember("one-row");
        assertThatThrownBy(() -> exec(INSERT_SESSION_STATUS, member, "x", "PENDING"))
            .hasMessageContaining("ck_caisse_epargne_session_sync_status");

        exec(INSERT_SESSION, member, "x");
        assertThatThrownBy(() -> exec(INSERT_SESSION, member, "y"))
            .hasMessageContaining("caisse_epargne_session_member_id_key");
    }

    @Test
    void deletingTheMemberDeletesTheSession() throws SQLException {
        long member = newMember("cascade");
        exec(INSERT_SESSION, member, "x");

        exec("DELETE FROM family_member WHERE id = ?", member);

        try (Connection c = PostgresContainers.connection(POSTGRES);
             PreparedStatement st = c.prepareStatement(
                 "SELECT COUNT(*) FROM caisse_epargne_session WHERE member_id = ?")) {
            st.setLong(1, member);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }
}
