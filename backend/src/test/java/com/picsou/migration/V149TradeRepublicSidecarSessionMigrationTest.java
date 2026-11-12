package com.picsou.migration;

import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V149 lets Trade Republic into {@code sidecar_session}, keeps every other bank's rows and
 * rules, and drops the old table: its rows are not copied, so members reconnect once.
 */
@RequiresPostgres
class V149TradeRepublicSidecarSessionMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    static long alice;
    static long bob;

    @BeforeAll
    static void seedV147AndMigrate() throws SQLException {
        PostgresContainers.migrateToOutOfOrder(POSTGRES, "147");
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            alice = newMember(c, "alice");
            execute(c, "INSERT INTO trade_republic_session (member_id, session_token, refresh_token) "
                + "VALUES (" + alice + ", 'old-token', 'old-refresh')");
            execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, sync_status, last_sync_error) "
                + "VALUES ('BOURSO', " + alice + ", 'bourso-state', 'FAILED', 'SESSION_EXPIRED')");
            bob = newMember(c, "bob");
            execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, region, sync_status, last_sync_error) "
                + "VALUES ('BANQUE_POPULAIRE', " + bob + ", 'bp-state', 'NORD', 'FAILED', 'KEYPAD_CHANGED')");
        }
        PostgresContainers.migrateToOutOfOrder(POSTGRES, "149");
    }

    @Test
    void theOldTableIsGoneAndItsRowsAreNotCopied() throws SQLException {
        assertThat(count("SELECT count(*) FROM pg_tables WHERE schemaname = 'public' "
            + "AND tablename = 'trade_republic_session'")).isZero();
        assertThat(count("SELECT count(*) FROM sidecar_session WHERE bank = 'TRADE_REPUBLIC' AND member_id = " + alice))
            .isZero();
    }

    @Test
    void otherBanksKeepTheirRows() throws SQLException {
        assertThat(count("SELECT count(*) FROM sidecar_session WHERE bank = 'BOURSO' AND member_id = " + alice
            + " AND session_state = 'bourso-state' AND last_sync_error = 'SESSION_EXPIRED'")).isEqualTo(1L);
    }

    @Test
    void banquePopulaireKeepsItsRowAndItsRules() throws SQLException {
        // V147 added the bank, its region column and its error codes; V149 rewrites the same CHECKs.
        assertThat(count("SELECT count(*) FROM sidecar_session WHERE bank = 'BANQUE_POPULAIRE' AND member_id = " + bob
            + " AND session_state = 'bp-state' AND region = 'NORD' AND last_sync_error = 'KEYPAD_CHANGED'")).isEqualTo(1L);
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "bp-new");
            execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, region, sync_status, last_sync_error) "
                + "VALUES ('BANQUE_POPULAIRE', " + member + ", 's', 'BRED', 'FAILED', 'INVALID_POSITIONS')");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, sync_status, "
                + "last_sync_error) VALUES ('BANQUE_POPULAIRE', " + newMember(c, "bp-no-region") + ", 's', 'FAILED', "
                + "'SESSION_EXPIRED')")).hasMessageContaining("ck_sidecar_session_region");
        }
    }

    @Test
    void tradeRepublicStoresAnySessionAndTheCodesItDeclares() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "tr");
            execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, sync_status, last_sync_error) "
                + "VALUES ('TRADE_REPUBLIC', " + member + ", 's', 'FAILED', 'APP_VALIDATION_CANCELLED')");
            execute(c, "UPDATE sidecar_session SET last_sync_error = 'INVALID_SESSION_STATE' "
                + "WHERE bank = 'TRADE_REPUBLIC' AND member_id = " + member);
        }
    }

    @Test
    void tradeRepublicCannotStoreACodeItNeverDeclares() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "undeclared");
            // CAPTCHA_BLOCKED belongs to Amundi only.
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session "
                + "(bank, member_id, session_state, sync_status, last_sync_error) "
                + "VALUES ('TRADE_REPUBLIC', " + member + ", 's', 'FAILED', 'CAPTCHA_BLOCKED')"))
                .hasMessageContaining("ck_sidecar_session_last_sync_error");
        }
    }

    @Test
    void anotherBankStillCannotStoreATradeRepublicOnlyCode() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "other");
            // INVALID_SESSION_STATE is declared by Caisse d'Epargne and Trade Republic, not by Amex.
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session "
                + "(bank, member_id, session_state, sync_status, last_sync_error) "
                + "VALUES ('AMEX', " + member + ", 's', 'FAILED', 'INVALID_SESSION_STATE')"))
                .hasMessageContaining("ck_sidecar_session_last_sync_error");
        }
    }

    @Test
    void tradeRepublicKeepsNoStoredLogin() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "login");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session "
                + "(bank, member_id, session_state, encrypted_credentials) "
                + "VALUES ('TRADE_REPUBLIC', " + member + ", 's', 'pin')"))
                .hasMessageContaining("ck_sidecar_session_credentials");
        }
    }

    @Test
    void anUnknownBankIsStillRefused() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c, "unknown");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state) "
                + "VALUES ('DEGIRO', " + member + ", 's')"))
                .hasMessageContaining("ck_sidecar_session_bank");
        }
    }

    private static long newMember(Connection c, String name) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
            "INSERT INTO family_member (display_name) VALUES (?) RETURNING id")) {
            st.setString(1, name);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES);
             ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        c.createStatement().execute(sql);
    }
}
