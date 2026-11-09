package com.picsou.migration;

import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V148 lets a Banque Populaire session keep the encrypted identifier, and still refuses it elsewhere. */
@RequiresPostgres
class V148BanquePopulaireStoredIdentifierMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    private static long member;

    @BeforeAll
    static void migrate() throws SQLException {
        PostgresContainers.migrateTo(POSTGRES, "148");
        try (Connection c = connection(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("INSERT INTO family_member (display_name) VALUES ('Test') RETURNING id")) {
            rs.next();
            member = rs.getLong(1);
        }
    }

    @Test
    void aBanquePopulaireSessionMayKeepTheEncryptedIdentifier() throws SQLException {
        try (Connection c = connection()) {
            insert(c, "BANQUE_POPULAIRE", "'RIVES_DE_PARIS'", "'ciphertext'");
            assertThat(count(c, "BANQUE_POPULAIRE")).isEqualTo(1);
        }
    }

    @Test
    void aBanquePopulaireSessionStillWorksWithoutAnIdentifier() throws SQLException {
        try (Connection c = connection(); var st = c.createStatement()) {
            long other;
            try (ResultSet rs = st.executeQuery("INSERT INTO family_member (display_name) VALUES ('Other') RETURNING id")) {
                rs.next();
                other = rs.getLong(1);
            }
            assertThat(st.executeUpdate("INSERT INTO sidecar_session (bank, member_id, session_state, region) "
                + "VALUES ('BANQUE_POPULAIRE', " + other + ", 'state', 'BRED')")).isEqualTo(1);
        }
    }

    @Test
    void aBankWithoutAStoredLoginStillRefusesIt() throws SQLException {
        try (Connection c = connection()) {
            assertThatThrownBy(() -> insert(c, "AMEX", "NULL", "'ciphertext'"))
                .hasMessageContaining("ck_sidecar_session_credentials");
            insert(c, "CIC", "NULL", "'ciphertext'");
        }
    }

    private static void insert(Connection c, String bank, String region, String credentials) throws SQLException {
        try (var st = c.createStatement()) {
            st.executeUpdate("INSERT INTO sidecar_session (bank, member_id, session_state, region, encrypted_credentials) "
                + "VALUES ('" + bank + "', " + member + ", 'state', " + region + ", " + credentials + ")");
        }
    }

    private static int count(Connection c, String bank) throws SQLException {
        try (var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM sidecar_session WHERE bank = '" + bank
                 + "' AND encrypted_credentials IS NOT NULL")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
