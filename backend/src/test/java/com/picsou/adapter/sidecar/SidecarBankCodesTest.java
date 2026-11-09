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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The persisted-values proof: {@code sidecar_session.last_sync_error} is guarded by one CHECK with a
 * branch per bank listing the codes it may store, and {@link SidecarBank#codes()} has to be that list.
 */
@RequiresPostgres
class SidecarBankCodesTest {

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");
    /** One {@code bank = 'X' AND last_sync_error = ANY (ARRAY[...])} branch per bank. */
    private static final Pattern BRANCH = Pattern.compile("bank\\)::text = '([A-Z_]+)'::text\\) AND .*?ARRAY\\[(.*?)\\]");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @BeforeAll
    static void migrate() {
        PostgresContainers.flyway(POSTGRES).load().migrate();
    }

    @ParameterizedTest
    @EnumSource(SidecarBank.class)
    void theDeclaredCodesAreExactlyTheBanksBranchOfTheSessionCheck(SidecarBank bank) throws SQLException {
        Set<String> declared = new TreeSet<>();
        bank.codes().forEach(code -> declared.add(code.name()));
        Set<String> allowed = bank.storedInSidecarSession()
            ? allowedByCheck().get(bank.name())
            : quotedBy(ownTableCheck(bank));
        assertThat(allowed).isEqualTo(declared);
    }

    @Test
    void theSessionCheckHasOneBranchPerBankStoredThere() throws SQLException {
        Set<String> banks = new TreeSet<>();
        for (SidecarBank bank : SidecarBank.values()) {
            if (bank.storedInSidecarSession()) {
                banks.add(bank.name());
            }
        }
        assertThat(allowedByCheck().keySet()).isEqualTo(banks);
    }

    /** The own-table CHECK of a bank that does not live in {@code sidecar_session}. */
    private static String ownTableCheck(SidecarBank bank) {
        return switch (bank) {
            case LBP -> "ck_lbp_session_last_sync_error";
            default -> throw new IllegalArgumentException(bank + " is stored in sidecar_session");
        };
    }

    /** Every code name quoted by one CHECK constraint, found by name. */
    private static Set<String> quotedBy(String constraint) throws SQLException {
        String sql = "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c WHERE c.conname = ?";
        Set<String> names = new TreeSet<>();
        try (Connection c = PostgresContainers.connection(POSTGRES); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, constraint);
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next()).as("%s exists", constraint).isTrue();
                Matcher m = QUOTED.matcher(rs.getString(1));
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        return names;
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

    /** The codes each bank branch of {@code ck_sidecar_session_last_sync_error} quotes. */
    private static Map<String, Set<String>> allowedByCheck() throws SQLException {
        String sql = """
            SELECT pg_get_constraintdef(c.oid)
              FROM pg_constraint c
             WHERE c.conname = 'ck_sidecar_session_last_sync_error'
            """;
        String definition;
        try (Connection c = PostgresContainers.connection(POSTGRES);
             PreparedStatement st = c.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            assertThat(rs.next()).as("ck_sidecar_session_last_sync_error exists").isTrue();
            definition = rs.getString(1);
        }
        Map<String, Set<String>> allowed = new TreeMap<>();
        Matcher branch = BRANCH.matcher(definition);
        while (branch.find()) {
            Set<String> codes = new TreeSet<>();
            Matcher quoted = QUOTED.matcher(branch.group(2));
            while (quoted.find()) {
                codes.add(quoted.group(1));
            }
            assertThat(allowed.put(branch.group(1), codes)).as("one branch for %s", branch.group(1)).isNull();
        }
        return allowed;
    }
}
