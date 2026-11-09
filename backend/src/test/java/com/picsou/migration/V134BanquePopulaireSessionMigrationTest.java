package com.picsou.migration;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.BanquePopulaireRegion;
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
 * V134 applies on top of the whole chain and creates the Banque Populaire session table in the
 * shape Caisse d'Epargne's reached after V109 + V110, with every error code in one CHECK, plus the
 * regional bank the session was opened at.
 *
 * <p>Every statement is a constant with bound parameters: no value is concatenated into SQL.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class V134BanquePopulaireSessionMigrationTest {

    private static final String INSERT_SESSION =
        "INSERT INTO banque_populaire_session (member_id, session_state, region) VALUES (?, ?, 'RIVES_DE_PARIS')";
    private static final String INSERT_SESSION_REGION =
        "INSERT INTO banque_populaire_session (member_id, session_state, region) VALUES (?, ?, ?)";
    private static final String INSERT_SESSION_WITHOUT_REGION =
        "INSERT INTO banque_populaire_session (member_id, session_state) VALUES (?, ?)";
    private static final String INSERT_SESSION_STATUS =
        "INSERT INTO banque_populaire_session (member_id, session_state, sync_status, region) "
            + "VALUES (?, ?, ?, 'RIVES_DE_PARIS')";
    private static final String INSERT_SESSION_ERROR =
        "INSERT INTO banque_populaire_session (member_id, session_state, sync_status, last_sync_error, region) "
            + "VALUES (?, ?, ?, ?, 'RIVES_DE_PARIS')";

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
                    + "The V134 migration test cannot be skipped. Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    @BeforeAll
    static void migrate() throws SQLException {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("134")
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
                 "SELECT column_name FROM information_schema.columns WHERE table_name = 'banque_populaire_session'");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) columns.add(rs.getString(1));
        }
        assertThat(columns).isNotEmpty()
            .contains("member_id", "region", "session_state", "is_active", "sync_status", "last_sync_error")
            .doesNotContain("encrypted_credentials")
            .noneMatch(name -> name.contains("password") || name.contains("credential"));
    }

    @Test
    void acceptsEveryErrorCodeOfTheBankAndNoOther() throws SQLException {
        for (SidecarErrorCode code : SidecarBank.BANQUE_POPULAIRE.codes()) {
            long member = newMember("ok-" + code.name());
            exec(INSERT_SESSION_ERROR, member, "x", "FAILED", code.name());
        }
        for (String code : List.of("NOT_A_REAL_CODE", "session_expired", "", "INVALID_OTP")) {
            long member = newMember("bad-" + code);
            assertThatThrownBy(() -> exec(INSERT_SESSION_ERROR, member, "x", "FAILED", code))
                .hasMessageContaining("ck_banque_populaire_session_last_sync_error");
        }
    }

    @Test
    void acceptsEveryRegionOfTheEnumAndNoOther() throws SQLException {
        for (BanquePopulaireRegion region : BanquePopulaireRegion.values()) {
            long member = newMember("region-" + region.name());
            exec(INSERT_SESSION_REGION, member, "x", region.name());
        }
        for (String region : List.of("PARIS", "rives_de_paris", "")) {
            long member = newMember("bad-region-" + region);
            assertThatThrownBy(() -> exec(INSERT_SESSION_REGION, member, "x", region))
                .hasMessageContaining("ck_banque_populaire_session_region");
        }
    }

    @Test
    void refusesASessionWithoutARegion() throws SQLException {
        long member = newMember("no-region");
        assertThatThrownBy(() -> exec(INSERT_SESSION_WITHOUT_REGION, member, "x"))
            .hasMessageContaining("region");
    }

    @Test
    void theRegionColumnFitsTheLongestRegion() throws SQLException {
        int longest = 0;
        for (BanquePopulaireRegion region : BanquePopulaireRegion.values()) {
            longest = Math.max(longest, region.name().length());
        }
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "SELECT character_maximum_length FROM information_schema.columns "
                     + "WHERE table_name = 'banque_populaire_session' AND column_name = 'region'");
             ResultSet rs = st.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(longest);
        }
    }

    @Test
    void leavesTheCaisseEpargneTableUntouched() throws SQLException {
        long member = newMember("both-banks");
        exec(INSERT_SESSION, member, "bp");
        exec("INSERT INTO caisse_epargne_session (member_id, session_state) VALUES (?, ?)", member, "ce");
        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "SELECT COUNT(*) FROM caisse_epargne_session WHERE member_id = ?")) {
            st.setLong(1, member);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isOne();
            }
        }
    }

    @Test
    void aFailedSyncMustCarryAnErrorAndOnlyAFailedOne() throws SQLException {
        long a = newMember("failed-no-error");
        assertThatThrownBy(() -> exec(INSERT_SESSION_STATUS, a, "x", "FAILED"))
            .hasMessageContaining("ck_banque_populaire_session_failed_error");
        long b = newMember("idle-with-error");
        assertThatThrownBy(() -> exec(INSERT_SESSION_ERROR, b, "x", "IDLE", "INTERNAL_ERROR"))
            .hasMessageContaining("ck_banque_populaire_session_failed_error");
    }

    @Test
    void refusesAnUnknownSyncStatusAndASecondRowForTheSameMember() throws SQLException {
        long member = newMember("one-row");
        assertThatThrownBy(() -> exec(INSERT_SESSION_STATUS, member, "x", "PENDING"))
            .hasMessageContaining("ck_banque_populaire_session_sync_status");

        exec(INSERT_SESSION, member, "x");
        assertThatThrownBy(() -> exec(INSERT_SESSION, member, "y"))
            .hasMessageContaining("banque_populaire_session_member_id_key");
    }

    @Test
    void deletingTheMemberDeletesTheSession() throws SQLException {
        long member = newMember("cascade");
        exec(INSERT_SESSION, member, "x");

        exec("DELETE FROM family_member WHERE id = ?", member);

        try (Connection c = connection();
             PreparedStatement st = c.prepareStatement(
                 "SELECT COUNT(*) FROM banque_populaire_session WHERE member_id = ?")) {
            st.setLong(1, member);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }
}
