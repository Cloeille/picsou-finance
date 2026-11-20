package com.picsou.migration;

import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V154 lets a member hold several Caisse d'Epargne sessions; every other bank keeps one. */
@RequiresPostgres
class V154BpceMultipleSessionsMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @BeforeAll
    static void migrate() {
        PostgresContainers.migrateTo(POSTGRES, "154");
    }

    @Test
    void aMemberCanHoldTwoCaisseEpargneSessions() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c);
            insert(c, "CAISSE_EPARGNE", member, "one");
            insert(c, "CAISSE_EPARGNE", member, "two");
            try (ResultSet rs = c.createStatement().executeQuery(
                "SELECT count(*) FROM sidecar_session WHERE member_id = " + member)) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(2);
            }
        }
    }

    @Test
    void anotherBankStillAllowsOnlyOneSessionPerMember() throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES)) {
            long member = newMember(c);
            insert(c, "AMEX", member, "one");
            assertThatThrownBy(() -> insert(c, "AMEX", member, "two"))
                .hasMessageContaining("uq_sidecar_session_bank_member");
        }
    }

    private static long newMember(Connection c) throws SQLException {
        try (ResultSet rs = c.createStatement().executeQuery(
            "INSERT INTO family_member (display_name) VALUES ('m') RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void insert(Connection c, String bank, long member, String state) throws SQLException {
        c.createStatement().execute("INSERT INTO sidecar_session (bank, member_id, session_state) VALUES ('"
            + bank + "', " + member + ", '" + state + "')");
    }
}
