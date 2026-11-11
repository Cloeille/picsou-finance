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
 * V150 lets {@code sidecar_session} hold BNP Paribas. A database at V149 with sessions on the thirteen
 * banks (Banque Populaire and Trade Republic included) keeps every one of them untouched through V150, and the table then accepts a BNP session
 * under the same rules as the others: no stored login, only the codes its sidecar can report.
 */
@RequiresPostgres
class V150BnpSidecarSessionMigrationTest {

    private static final String DIGEST = """
        SELECT count(*), md5(string_agg(concat_ws('|', bank, member_id, session_state, encrypted_credentials,
               unsupported_contracts, last_validated_at, is_active, sync_status, last_sync_started_at,
               last_sync_completed_at, last_sync_error, created_at, updated_at, last_sync_life_ambiguous, region),
               ';' ORDER BY bank, member_id))
          FROM sidecar_session
        """;

    private static final String[] BANKS = {
        "AMEX", "AMUNDI", "BANQUE_POPULAIRE", "BOURSE_DIRECT", "BOURSO", "CAISSE_EPARGNE", "CIC", "CMES", "CORUM",
        "FORTUNEO", "NATIXIS", "SOFIDY", "TRADE_REPUBLIC"
    };

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    static String before;
    static String after;

    @BeforeAll
    static void seedV149AndMigrate() throws SQLException {
        PostgresContainers.migrateTo(POSTGRES, "149");
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            newMember(c, "saver");
            execute(c, "INSERT INTO family_member (display_name) SELECT 'bulk-' || g FROM generate_series(1, 200) g");
            for (String bank : BANKS) {
                // NATIXIS and the others keep no login: encrypted_credentials is only set for the banks that store one.
                // Only a Banque Populaire row carries a region (ck_sidecar_session_region).
                String region = bank.equals("BANQUE_POPULAIRE") ? "'AUVERGNE_RHONE_ALPES'" : "NULL";
                execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, sync_status, last_sync_error, "
                    + "last_validated_at, region) SELECT '" + bank + "', id, 's-' || id || '-" + bank + "', "
                    + "CASE WHEN id % 2 = 0 THEN 'FAILED' ELSE 'SUCCESS' END, "
                    + "CASE WHEN id % 2 = 0 THEN 'SESSION_EXPIRED' END, NOW() - (id || ' minutes')::interval, " + region + " "
                    + "FROM family_member");
            }
        }
        before = digest();
        PostgresContainers.migrateTo(POSTGRES, "150");
        after = digest();
    }

    @Test
    void everyExistingSessionIsLeftAsItWas() {
        assertThat(before).startsWith("2613 ");
        assertThat(after).isEqualTo(before);
    }

    @Test
    void bnpIsAcceptedWithoutALoginAndWithItsOwnCodes() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long other = newMember(c, "bnp-saver");
            execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state) VALUES ('BNP', " + other + ", 'jar')");
            execute(c, "UPDATE sidecar_session SET sync_status = 'FAILED', last_sync_error = 'KEYPAD_CHANGED' "
                + "WHERE bank = 'BNP' AND member_id = " + other);
            assertThat(count(c, "SELECT count(*) FROM sidecar_session WHERE bank = 'BNP'")).isOne();
        }
    }

    @Test
    void bnpKeepsNoStoredLoginAndRefusesAnotherBanksCode() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long other = newMember(c, "bnp-strict");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, "
                + "encrypted_credentials) VALUES ('BNP', " + other + ", 's', 'login')"))
                .hasMessageContaining("ck_sidecar_session_credentials");
        }
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long other = newMember(c, "bnp-code");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, "
                + "sync_status, last_sync_error) VALUES ('BNP', " + other + ", 's', 'FAILED', 'FIRST_VISIT_PENDING')"))
                .hasMessageContaining("ck_sidecar_session_last_sync_error");
        }
    }

    @Test
    void banquePopulaireAndTradeRepublicSurviveTheRebuiltChecks() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long other = newMember(c, "kept");
            execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, sync_status, last_sync_error, region) "
                + "VALUES ('BANQUE_POPULAIRE', " + other + ", 's', 'FAILED', 'KEYPAD_EXPIRED', 'AUVERGNE_RHONE_ALPES')");
            execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, sync_status, last_sync_error) "
                + "VALUES ('TRADE_REPUBLIC', " + other + ", 's', 'FAILED', 'INVALID_SESSION_STATE')");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, "
                + "sync_status, last_sync_error) VALUES ('NATIXIS', " + other + ", 's', 'FAILED', 'KEYPAD_EXPIRED')"))
                .hasMessageContaining("ck_sidecar_session_last_sync_error");
        }
    }

    @Test
    void theOtherBanksKeepTheirOwnCodes() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long other = newMember(c, "codes");
            execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, sync_status, last_sync_error) "
                + "VALUES ('SOFIDY', " + other + ", 's', 'FAILED', 'FIRST_VISIT_PENDING')");
            assertThatThrownBy(() -> execute(c, "INSERT INTO sidecar_session (bank, member_id, session_state, "
                + "sync_status, last_sync_error) VALUES ('AMEX', " + other + ", 's', 'FAILED', 'FIRST_VISIT_PENDING')"))
                .hasMessageContaining("ck_sidecar_session_last_sync_error");
        }
    }

    private static String digest() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES);
             ResultSet rs = c.createStatement().executeQuery(DIGEST)) {
            rs.next();
            return rs.getLong(1) + " " + rs.getString(2);
        }
    }

    private static long newMember(Connection c, String name) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("INSERT INTO family_member (display_name) VALUES (?) RETURNING id")) {
            st.setString(1, name);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        c.createStatement().execute(sql);
    }
}
