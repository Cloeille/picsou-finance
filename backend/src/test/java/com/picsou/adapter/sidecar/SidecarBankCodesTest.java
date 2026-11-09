package com.picsou.adapter.sidecar;

import com.picsou.port.SidecarErrorCode;
import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The persisted-values proof: every {@code *_session.last_sync_error} column is guarded by a CHECK
 * listing the codes its bank may store, and {@link SidecarBank#codes()} has to be that exact list.
 */
@RequiresPostgres
class SidecarBankCodesTest {

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @BeforeAll
    static void migrate() {
        PostgresContainers.flyway(POSTGRES).load().migrate();
    }

    @ParameterizedTest
    @EnumSource(SidecarBank.class)
    void theDeclaredCodesAreExactlyTheLatestCheckConstraintOnTheBanksTable(SidecarBank bank) throws SQLException {
        Set<String> allowed = allowedByCheck(tableOf(bank));

        Set<String> declared = new TreeSet<>();
        bank.codes().forEach(code -> declared.add(code.name()));

        assertThat(declared).isEqualTo(allowed);
    }

    @Test
    void everyConstantOfTheSharedEnumBelongsToAtLeastOneBank() {
        Set<SidecarErrorCode> used = EnumSet.noneOf(SidecarErrorCode.class);
        for (SidecarBank bank : SidecarBank.values()) {
            used.addAll(bank.codes());
        }

        assertThat(used).containsExactlyInAnyOrder(SidecarErrorCode.values());
    }

    @ParameterizedTest
    @EnumSource(SidecarBank.class)
    void everyDeclaredCodeHasAMessageAndNoOtherCodeDoes(SidecarBank bank) {
        for (SidecarErrorCode code : SidecarErrorCode.values()) {
            if (bank.declares(code)) {
                assertThat(bank.message(code)).isNotBlank();
                assertThat(bank.parse(code.name())).contains(code);
            } else {
                assertThat(bank.parse(code.name())).isEmpty();
                assertThatThrownBy(() -> bank.message(code)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    private static String tableOf(SidecarBank bank) {
        return bank.name().toLowerCase() + "_session";
    }

    /** Every code name quoted by the CHECK on the table that mentions INVALID_CREDENTIALS (all banks accept it). */
    private static Set<String> allowedByCheck(String table) throws SQLException {
        String sql = """
            SELECT pg_get_constraintdef(c.oid)
              FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.conrelid
             WHERE t.relname = ? AND c.contype = 'c'
               AND pg_get_constraintdef(c.oid) LIKE '%INVALID_CREDENTIALS%'
            """;
        Set<String> names = new TreeSet<>();
        int constraints = 0;
        try (Connection c = PostgresContainers.connection(POSTGRES); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, table);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    constraints++;
                    Matcher m = QUOTED.matcher(rs.getString(1));
                    while (m.find()) {
                        names.add(m.group(1));
                    }
                }
            }
        }
        assertThat(constraints).as("CHECK constraints listing the error codes of %s", table).isEqualTo(1);
        return names;
    }
}
