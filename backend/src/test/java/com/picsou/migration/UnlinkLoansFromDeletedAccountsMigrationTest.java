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

/**
 * Verifies {@code V154__unlink_loans_from_deleted_accounts.sql}: a loan or peer loan
 * still linked to a soft-deleted account is unlinked, one linked to a live account
 * keeps its link.
 */
@RequiresPostgres
class UnlinkLoansFromDeletedAccountsMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    private static long deletedProperty;
    private static long liveProperty;
    private static long debtOnDeleted;
    private static long debtOnLive;
    private static long peerLoanOnDeleted;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        PostgresContainers.migrateToOutOfOrder(POSTGRES, "153");

        try (Connection conn = PostgresContainers.connection(POSTGRES)) {
            long member = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Test') RETURNING id");
            deletedProperty = insertAccount(conn, member, "Sold house", "REAL_ESTATE", true);
            liveProperty = insertAccount(conn, member, "House", "REAL_ESTATE", false);
            debtOnDeleted = insertDebt(conn, insertAccount(conn, member, "Old mortgage", "LOAN", false), deletedProperty);
            debtOnLive = insertDebt(conn, insertAccount(conn, member, "Mortgage", "LOAN", false), liveProperty);

            long counterparty = insertReturningId(conn,
                "INSERT INTO peer_counterparty (member_id, name) VALUES (" + member + ", 'Borrower') RETURNING id");
            long peerLoan = insertAccount(conn, member, "Loan to a friend", "PEER_LOAN", false);
            peerLoanOnDeleted = insertReturningId(conn,
                "INSERT INTO peer_loan_terms (account_id, counterparty_id, principal, lent_at, linked_account_id) "
                    + "VALUES (" + peerLoan + ", " + counterparty + ", 1000, DATE '2026-01-01', "
                    + deletedProperty + ") RETURNING id");
        }

        PostgresContainers.migrateToOutOfOrder(POSTGRES, "154");
    }

    @Test
    void aLoanLinkedToADeletedAccountIsUnlinked() throws SQLException {
        assertThat(linkedAccount("debt", debtOnDeleted)).isNull();
        assertThat(linkedAccount("peer_loan_terms", peerLoanOnDeleted)).isNull();
    }

    @Test
    void aLoanLinkedToALiveAccountKeepsItsLink() throws SQLException {
        assertThat(linkedAccount("debt", debtOnLive)).isEqualTo(liveProperty);
    }

    private static Long linkedAccount(String table, long id) throws SQLException {
        try (Connection conn = PostgresContainers.connection(POSTGRES);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT linked_account_id FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long value = rs.getLong(1);
                return rs.wasNull() ? null : value;
            }
        }
    }

    private static long insertAccount(Connection conn, long member, String name, String type, boolean deleted)
        throws SQLException {
        return insertReturningId(conn,
            "INSERT INTO account (name, type, currency, current_balance, is_manual, member_id, deleted_at) "
                + "VALUES ('" + name + "', '" + type + "'::account_type, 'EUR', 100, true, " + member + ", "
                + (deleted ? "now()" : "NULL") + ") RETURNING id");
    }

    private static long insertDebt(Connection conn, long account, long linked) throws SQLException {
        return insertReturningId(conn,
            "INSERT INTO debt (account_id, linked_account_id, borrowed_amount, member_id) "
                + "VALUES (" + account + ", " + linked + ", 1000, "
                + "(SELECT member_id FROM account WHERE id = " + account + ")) RETURNING id");
    }

    private static long insertReturningId(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
